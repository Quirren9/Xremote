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
            if (info.ok && info.data != null && info.data.isNotEmpty()) {
                deviceInfo = DeviceInfo.parse(info.data)
                log.log("Device: ${deviceInfo?.manufacturer} ${deviceInfo?.model} fw ${deviceInfo?.version}, " +
                    "${deviceInfo?.properties?.size} props, ${deviceInfo?.operations?.size} ops")
            }
        } catch (e: Exception) { log.log("DeviceInfo parse failed: ${e.message}") }

        // Fuji's remote-mode property list: u32 count, then per entry u32 length (incl. itself) + DevicePropDesc.
        val caps = client.transaction(PtpOp.FUJI_CAMERA_CAPABILITIES)
        if (caps.ok && caps.data != null) parseCapabilities(caps.data)
    }

    /** Parses the 0x902B blob. Entries that fail to parse are skipped. */
    fun parseCapabilities(data: ByteArray): Int {
        if (data.size < 4) return 0
        val count = data.u32(0).toInt()
        var off = 4
        var parsed = 0
        for (i in 0 until count) {
            if (off + 4 > data.size) break
            val len = data.u32(off).toInt()
            if (len < 8 || off + len > data.size) { log.log("Capabilities entry $i has bad length $len, stopping"); break }
            try {
                val d = PropDesc.parse(data.copyOfRange(off + 4, off + len))
                if (d.code !in FujiProp.internal) { descs[d.code] = d; capCodes += d.code; parsed++ }
                log.log("  cap ${hex16(d.code)} ${KnownProps.nameOf(d.code)} = ${KnownProps.format(d.code, d.current, d.text)}" +
                    " rw=${d.writable} options=${d.choices().size}")
            } catch (e: Exception) {
                log.log("  cap entry $i parse failed: ${e.message}")
            }
            off += len
        }
        log.log("Camera listed $parsed remote properties")
        fromCapabilities = parsed > 0
        return parsed
    }

    private var fromCapabilities = false

    /**
     * Last handshake step: InitiateOpenCapture(0, 0) puts the camera into remote live view, after which
     * it accepts connections on the event (55741) and live view (55742) ports. Never fails the connection.
     */
    fun startRemote() {
        try {
            val r = client.transaction(PtpOp.INITIATE_OPEN_CAPTURE, listOf(0, 0), timeoutMs = 5000)
            openCaptureTid = client.lastTid
            log.log("InitiateOpenCapture: ${PtpResp.name(r.code)} (session #$openCaptureTid)")
        } catch (e: java.net.SocketTimeoutException) {
            log.log("InitiateOpenCapture got no reply (continuing, a late reply is ignored)")
        }
    }

    fun startStreams(onFrame: (ByteArray) -> Unit, onEvent: (Int, ByteArray) -> Unit = { _, _ -> }) {
        events = EventChannel(socketFactory, host, PORT_EVENTS, log, onEvent).also { it.start() }
        liveView = LiveViewStream(socketFactory, host, PORT_LIVEVIEW, log, onFrame).also { it.start() }
    }

    /** Discovers every property the camera exposes, with its allowed values where available. */
    fun discover(): Map<Int, PropDesc> {
        val codes = LinkedHashSet<Int>()
        deviceInfo?.properties?.let { codes.addAll(it) }
        if (fromCapabilities) {
            // The camera already described its remote controls; only add status values it didn't list.
            codes.addAll(listOf(FujiProp.BATTERY, FujiProp.SHOTS_REMAINING, FujiProp.MOVIE_REMAINING, FujiProp.FOCUS_POINT))
            descUnsupported = true
        } else codes.addAll(KnownProps.all.keys)
        codes.removeAll(FujiProp.internal)
        codes.removeAll(descs.keys)
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
        val writable = if (fromCapabilities) code == FujiProp.FOCUS_POINT else code !in readOnlyGuess
        return PropDesc(code, type, writable = writable, current = value, synthetic = true)
    }

    private var changeLogBudget = 2

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
        if (n > 0 && changeLogBudget > 0) { changeLogBudget--; log.log("D212 raw: ${d.hex(80)}") }
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
        val r = client.transaction(PtpOp.INITIATE_CAPTURE, listOf(0, 0), timeoutMs = 8000)
        log.log("Capture: ${PtpResp.name(r.code)}")
        return r.ok
    }

    fun objectInfo(handle: Long): ObjectInfo? {
        val r = client.transaction(PtpOp.GET_OBJECT_INFO, listOf(handle), timeoutMs = 5000)
        val d = r.data ?: return null
        if (!r.ok || d.isEmpty()) return null
        return try { ObjectInfo.parse(handle, d) } catch (e: Exception) { log.log("ObjectInfo parse: ${e.message}"); null }
    }

    /** Downloads an object in 512 KB chunks (falls back to a single GetObject). */
    fun download(info: ObjectInfo, progress: (Float) -> Unit): ByteArray? {
        val chunk = 512L * 1024
        val out = java.io.ByteArrayOutputStream(info.size.toInt().coerceAtLeast(0))
        var off = 0L
        while (info.size > 0 && off < info.size) {
            val n = minOf(chunk, info.size - off)
            val r = client.transaction(PtpOp.GET_PARTIAL_OBJECT, listOf(info.handle, off, n), timeoutMs = 15000)
            val d = r.data
            if (!r.ok || d == null || d.isEmpty()) {
                if (off == 0L) break   // partial reads not supported: try the whole object
                log.log("Download stopped at $off/${info.size}: ${PtpResp.name(r.code)}"); return null
            }
            out.write(d); off += d.size
            progress(off.toFloat() / info.size)
        }
        if (off > 0) return out.toByteArray()
        val r = client.transaction(PtpOp.GET_OBJECT, listOf(info.handle), timeoutMs = 60000)
        return if (r.ok) r.data else null
    }

    fun reconnectLiveView() = liveView?.reconnect()

    private var openCaptureTid = 0L

    /** Closes the open capture session (the camera keeps live view off until it is reopened). */
    fun terminateCapture() {
        try {
            val r = client.transaction(PtpOp.TERMINATE_OPEN_CAPTURE, listOf(openCaptureTid), timeoutMs = 5000)
            log.log("TerminateOpenCapture(#$openCaptureTid): ${PtpResp.name(r.code)}")
        } catch (e: java.net.SocketTimeoutException) {
            log.log("TerminateOpenCapture got no reply")
        }
    }

    /** Full live view restart: close the capture session, reopen it, reconnect the stream. */
    fun restartLiveView() {
        terminateCapture()
        startRemote()
        reconnectLiveView()
    }

    private fun jpegStart(d: ByteArray?): Int {
        if (d == null || d.size < 4) return -1
        for (i in 0 until minOf(d.size - 1, 1024)) if (d[i] == 0xFF.toByte() && d[i + 1] == 0xD8.toByte()) return i
        return -1
    }

    private fun tryJpeg(label: String, r: PtpResponse): ByteArray? {
        val d = r.data
        val at = jpegStart(d)
        log.log("  $label: ${PtpResp.name(r.code)}, ${d?.size ?: 0} bytes${if (at >= 0) ", JPEG at $at" else ""}")
        return if (r.ok && d != null && at >= 0) d.copyOfRange(at, d.size) else null
    }

    /**
     * After a shot the X-T3 announces (handle, size) but rejects GetObjectInfo on it in remote mode.
     * Tries the plausible ways to get the image, in order, logging each, and returns the first JPEG.
     * Returns (file name or null, bytes, method).
     */
    fun fetchShot(handle: Long, size: Long): Triple<String?, ByteArray, String>? {
        log.log("Fetching shot ${hexL(handle)} (${size} bytes announced)")
        fun t(op: Int, p: List<Long>, ms: Int = 15000) = try { client.transaction(op, p, timeoutMs = ms) }
            catch (e: java.net.SocketTimeoutException) { log.log("  ${hex16(op)} timed out"); null }

        t(PtpOp.GET_OBJECT, listOf(handle))?.let { r -> tryJpeg("GetObject", r)?.let { return Triple(null, it, "GetObject") } }
        if (size > 0) t(PtpOp.GET_PARTIAL_OBJECT, listOf(handle, 0, size))?.let { r ->
            tryJpeg("GetPartialObject", r)?.let { return Triple(null, it, "GetPartialObject") }
        }
        t(PtpOp.GET_THUMB, listOf(handle))?.let { r -> tryJpeg("GetThumb", r)?.let { return Triple(null, it, "GetThumb") } }

        // Storage access may only open up once the capture session is closed.
        terminateCapture()
        t(PtpOp.GET_OBJECT_INFO, listOf(handle), 5000)?.let { r -> log.log("  GetObjectInfo after terminate: ${PtpResp.name(r.code)}") }
        val hs = t(PtpOp.GET_OBJECT_HANDLES, listOf(0xFFFFFFFFL, 0, 0), 15000)
        val handles = hs?.data?.takeIf { hs.ok && it.size >= 4 }?.let { d -> val n = d.u32(0).toInt(); (0 until minOf(n, (d.size - 4) / 4)).map { d.u32(4 + it * 4) } } ?: emptyList()
        log.log("  GetObjectHandles: ${hs?.let { PtpResp.name(it.code) }}, ${handles.size} objects, last ${handles.takeLast(3).joinToString { hexL(it) }}")
        for (h in listOfNotNull(handle.takeIf { it in handles }, handles.lastOrNull()).distinct()) {
            val info = objectInfo(h) ?: continue
            log.log("  object ${hexL(h)}: ${info.filename} ${info.size} bytes format ${hex16(info.format)}")
            if (!info.isJpeg) continue
            val bytes = download(info) {}
            if (bytes != null && jpegStart(bytes) == 0) return Triple(info.filename, bytes, "GetObjectHandles+download")
        }
        return null
    }

    /** Codes the camera listed as remotely controllable (empty if it sent no list). */
    val controllable: Set<Int> get() = if (fromCapabilities) capCodes else emptySet()
    private val capCodes = HashSet<Int>()

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
