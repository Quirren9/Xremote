package nl.quirren.xremote.ptp

import javax.net.SocketFactory

/**
 * High-level Fujifilm remote-shooting session. All calls block; run them off the main thread.
 *
 * The handshake follows what the open-source projects (fuji-cam-wifi-tool, camlib/Fudge)
 * found for X-series bodies. Every step is logged so it can be tuned on a real X-T3.
 */
class FujiCamera(
    private val socketFactory: SocketFactory,
    val host: String,
    private val log: PtpLog,
) {
    companion object {
        const val PORT_CONTROL = 55740
        const val PORT_EVENTS = 55741
        const val PORT_LIVEVIEW = 55742
        const val REMOTE_MODE = 5L
    }

    val client = FujiPtpClient(socketFactory, log)
    var deviceInfo: DeviceInfo? = null; private set
    val descs = linkedMapOf<Int, PropDesc>()
    private var liveView: LiveViewStream? = null
    private var events: EventChannel? = null
    private var descUnsupported = false

    fun connect(clientName: String) {
        client.connect(host, PORT_CONTROL, clientName)

        expectOk(client.transaction(PtpOp.OPEN_SESSION, listOf(1)), "OpenSession", allow = setOf(0x201E))

        // Tell the camera we are a remote-shooting client.
        if (!setRaw(FujiProp.CLIENT_STATE, REMOTE_MODE, 2) && !setRaw(FujiProp.CLIENT_STATE, REMOTE_MODE, 4)) {
            log.log("! Could not set client state, continuing anyway")
        }
        client.transaction(PtpOp.GET_DEVICE_PROP_VALUE, listOf(FujiProp.CAMERA_STATE.toLong()))

        // Remote protocol version: echo what the camera reports, or use the known value.
        val ver = client.transaction(PtpOp.GET_DEVICE_PROP_VALUE, listOf(FujiProp.REMOTE_VERSION.toLong()))
        val version = if (ver.ok && ver.data != null && ver.data.size >= 4) ver.data.u32(0) else 0x00020005L
        log.log("Remote version ${hexL(version)}")
        setRaw(FujiProp.REMOTE_VERSION, version, 4)

        try {
            val info = client.transaction(PtpOp.GET_DEVICE_INFO)
            if (info.ok && info.data != null) {
                deviceInfo = DeviceInfo.parse(info.data)
                log.log("Device: ${deviceInfo?.manufacturer} ${deviceInfo?.model} fw ${deviceInfo?.version}, " +
                    "${deviceInfo?.properties?.size} props, ${deviceInfo?.operations?.size} ops")
            }
        } catch (e: Exception) { log.log("DeviceInfo parse failed: ${e.message}") }

        client.transaction(PtpOp.FUJI_CAMERA_CAPABILITIES)
        client.transaction(PtpOp.FUJI_START_REMOTE)
    }

    fun startStreams(onFrame: (ByteArray) -> Unit, onEvent: (Int, ByteArray) -> Unit = { _, _ -> }) {
        events = EventChannel(socketFactory, host, PORT_EVENTS, log, onEvent).also { it.start() }
        liveView = LiveViewStream(socketFactory, host, PORT_LIVEVIEW, log, onFrame).also { it.start() }
    }

    /** Discovers every property the camera exposes, with its allowed values where available. */
    fun discover(): Map<Int, PropDesc> {
        val codes = LinkedHashSet<Int>()
        deviceInfo?.properties?.let { codes.addAll(it) }
        codes.addAll(KnownProps.all.keys)
        codes.removeAll(FujiProp.internal)
        for (code in codes) {
            val d = describe(code) ?: continue
            descs[code] = d
        }
        log.log("Discovered ${descs.size} properties")
        return descs
    }

    private fun describe(code: Int): PropDesc? {
        if (!descUnsupported) {
            val r = client.transaction(PtpOp.GET_DEVICE_PROP_DESC, listOf(code.toLong()))
            if (r.ok && r.data != null) {
                return try { PropDesc.parse(r.data) } catch (e: Exception) {
                    log.log("Desc parse ${hex16(code)} failed: ${e.message}"); null
                }
            }
            if (r.code == PtpResp.OPERATION_NOT_SUPPORTED) {
                log.log("Camera has no GetDevicePropDesc, falling back to plain values")
                descUnsupported = true
            } else return null
        }
        val v = client.transaction(PtpOp.GET_DEVICE_PROP_VALUE, listOf(code.toLong()))
        val d = v.data
        if (!v.ok || d == null || d.isEmpty()) return null
        val type = when (d.size) { 1 -> PtpType.UINT8; 2 -> PtpType.UINT16; 8 -> PtpType.UINT64; else -> PtpType.UINT32 }
        val value = when (d.size) { 1 -> d.u8(0).toLong(); 2 -> d.u16(0).toLong(); else -> d.u32(0) }
        return PropDesc(code, type, writable = code !in readOnlyGuess, current = value, synthetic = true)
    }

    private val readOnlyGuess = setOf(FujiProp.BATTERY, 0x5001, FujiProp.SHOTS_REMAINING, FujiProp.MOVIE_REMAINING)

    fun readValue(code: Int): Long? {
        val r = client.transaction(PtpOp.GET_DEVICE_PROP_VALUE, listOf(code.toLong()))
        val d = r.data ?: return null
        if (!r.ok || d.isEmpty()) return null
        val type = descs[code]?.dataType ?: PtpType.UINT32
        return try { LeReader(d).value(type) } catch (_: Exception) { if (d.size >= 4) d.u32(0) else d.u16(0).toLong() }
    }

    /** Fuji's change list: u16 count, then (u16 code, u32 value) entries. */
    fun pollChanges(): Map<Int, Long> {
        val r = client.transaction(PtpOp.GET_DEVICE_PROP_VALUE, listOf(FujiProp.CHANGED_PROPS.toLong()))
        val d = r.data ?: return emptyMap()
        if (!r.ok || d.size < 2) return emptyMap()
        val n = d.u16(0)
        if (d.size < 2 + n * 6) return emptyMap()
        val out = HashMap<Int, Long>()
        val rd = LeReader(d, 2)
        repeat(n) { out[rd.u16()] = rd.u32() }
        return out
    }

    fun setValue(code: Int, value: Long): Boolean {
        val d = descs[code]
        val size = d?.let { PtpType.size(it.dataType) } ?: 4
        var type = d?.dataType
        var ok = setRaw(code, value, size)
        // Size guess may be wrong for synthetic descriptors; try the other common width.
        if (!ok && d?.synthetic != false) {
            val alt = if (size == 4) 2 else 4
            ok = setRaw(code, value, alt)
            if (ok) type = if (alt == 2) PtpType.UINT16 else PtpType.UINT32
        }
        if (ok && d != null) descs[code] = d.copy(current = value, dataType = type ?: d.dataType)
        return ok
    }

    private fun setRaw(code: Int, value: Long, size: Int): Boolean {
        val w = LeWriter()
        when (size) { 1 -> w.u8(value.toInt()); 2 -> w.u16(value.toInt()); 8 -> w.u64(value); else -> w.u32(value) }
        val r = client.transaction(PtpOp.SET_DEVICE_PROP_VALUE, listOf(code.toLong()), w.toByteArray())
        return r.ok
    }

    fun shutter(): Boolean {
        setRaw(FujiProp.SHUTTER_PRESS, 0x0200, 2)
        val r = client.transaction(PtpOp.INITIATE_CAPTURE, listOf(0, 0), timeoutMs = 20000)
        return r.ok
    }

    /** x/y in the camera's focus grid. */
    fun setFocusPoint(x: Int, y: Int): Boolean = setValue(FujiProp.FOCUS_POINT, ((x and 0xFF) shl 8 or (y and 0xFF)).toLong())

    fun close() {
        liveView?.stop(); events?.stop()
        try { if (client.connected) client.transaction(PtpOp.CLOSE_SESSION, timeoutMs = 2000) } catch (_: Exception) {}
        client.close()
    }

    private fun expectOk(r: PtpResponse, what: String, allow: Set<Int> = emptySet()) {
        if (!r.ok && r.code !in allow) throw PtpException("$what failed: ${PtpResp.name(r.code)}")
    }
}
