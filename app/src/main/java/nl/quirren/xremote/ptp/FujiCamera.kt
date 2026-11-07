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
        /** What libfuji (and Fuji's own app) set when re-entering live view. */
        const val LIVEVIEW_REMOTE_VERSION = 0x0002000CL
    }

    val client = FujiPtpClient(socketFactory, log)
    var deviceInfo: DeviceInfo? = null; private set
    val descs = linkedMapOf<Int, PropDesc>()
    private var liveView: LiveViewStream? = null
    private var events: EventChannel? = null
    private var descUnsupported = false

    fun connect(clientName: String) {
        client.connect(host, PORT_CONTROL, clientName)

        val open = client.transaction(PtpOp.OPEN_SESSION, listOf(1))
        if (open.code == 0x201E) {   // left over from an app that didn't disconnect cleanly
            log.log("Old session still open on the camera, closing it first")
            client.transaction(PtpOp.CLOSE_SESSION)
            expectOk(client.transaction(PtpOp.OPEN_SESSION, listOf(1)), "OpenSession")
        } else expectOk(open, "OpenSession")

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
        remoteVersion = version

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
    /** Returns true if the camera answered. */
    fun startRemote(): Boolean {
        return try {
            val r = client.transaction(PtpOp.INITIATE_OPEN_CAPTURE, listOf(0, 0), timeoutMs = 12000)
            openCaptureTid = client.lastTid
            log.log("InitiateOpenCapture: ${PtpResp.name(r.code)} (session #$openCaptureTid)")
            r.ok
        } catch (e: java.net.SocketTimeoutException) {
            log.log("InitiateOpenCapture got no reply (a late reply is ignored)")
            false
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
        out[FujiProp.OBJECT_COUNT]?.let { numObjects = it.toInt() }
        out[FujiProp.CAMERA_STATE]?.let { cameraState = it.toInt() }
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

    /**
     * Downloads an object in 1 MB chunks. Keeps reading while chunks come back full, because in remote
     * mode the camera can report a wrong size (seen: every file "164 KB").
     */
    fun download(info: ObjectInfo, progress: (Float) -> Unit): ByteArray? {
        val chunk = 1024L * 1024
        val out = java.io.ByteArrayOutputStream(maxOf(info.size.toInt(), 1 shl 20))
        var off = 0L
        while (true) {
            val r = client.transaction(PtpOp.GET_PARTIAL_OBJECT, listOf(info.handle, off, chunk), timeoutMs = 20000)
            val d = r.data
            if (!r.ok || d == null || d.isEmpty()) {
                if (off == 0L) { log.log("  GetPartialObject: ${PtpResp.name(r.code)}"); break }
                break   // end of file
            }
            out.write(d); off += d.size
            if (info.size > 0) progress(minOf(1f, off.toFloat() / info.size))
            if (d.size < chunk) break
        }
        if (off == 0L) {
            val r = client.transaction(PtpOp.GET_OBJECT, listOf(info.handle), timeoutMs = 60000)
            return if (r.ok) r.data else null
        }
        val bytes = out.toByteArray()
        val complete = bytes.size >= 2 && bytes[bytes.size - 2] == 0xFF.toByte() && bytes[bytes.size - 1] == 0xD9.toByte()
        log.log("  downloaded ${bytes.size / 1024} KB (reported ${info.size / 1024} KB), JPEG end marker ${if (complete) "found" else "MISSING"}")
        return bytes
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

    var remoteVersion = 0x00020007L; private set
    @Volatile var numObjects = -1; private set
    @Volatile var cameraState = -1; private set
    @Volatile var inGallery = false; private set

    private fun events() { try { pollChanges() } catch (e: java.net.SocketTimeoutException) { log.log("  events timed out") } }

    /** One mode-switch step: logged, patient, and never aborts the sequence. */
    private fun step(label: String, block: () -> PtpResponse?) {
        try {
            val r = block()
            log.log("  $label: ${r?.let { PtpResp.name(it.code) } ?: "-"}")
        } catch (e: java.net.SocketTimeoutException) {
            log.log("  $label: no reply (continuing)")
        }
    }

    private fun setStep(label: String, code: Int, value: Long, size: Int) = step(label) {
        val w = LeWriter()
        if (size == 2) w.u16(value.toInt()) else w.u32(value)
        client.transaction(PtpOp.SET_DEVICE_PROP_VALUE, listOf(code.toLong()), w.toByteArray(), timeoutMs = 12000)
    }

    /** libfuji's end_liveview: poll events, then close the capture session opened by InitiateOpenCapture. */
    fun endLiveView() {
        events()
        step("TerminateOpenCapture(#$openCaptureTid)") {
            // The X-T3 never answers this (seen in every log), so don't wait long; a late reply is dropped
            client.transaction(PtpOp.TERMINATE_OPEN_CAPTURE, listOf(openCaptureTid), timeoutMs = 1500)
        }
    }

    /**
     * Remote image view ("gallery") mode, following libfuji. Only in this state does the camera allow
     * object access; photos are then numbered 1..N (N from property 0xD222).
     */
    fun enterGallery() {
        log.log("Switching camera to gallery mode")
        endLiveView()
        events()
        setStep("CameraState = remote access", FujiProp.CAMERA_STATE, 6, 2)
        events(); events()
        setStep("ClientState = remote image view", FujiProp.CLIENT_STATE, 11, 2)
        events()
        step("get RemoteGetObjectVersion") { client.transaction(PtpOp.GET_DEVICE_PROP_VALUE, listOf(FujiProp.REMOTE_GET_OBJECT_VERSION.toLong()), timeoutMs = 12000) }
        setStep("RemoteGetObjectVersion = 5", FujiProp.REMOTE_GET_OBJECT_VERSION, 5, 4)
        events()
        inGallery = true
        log.log("  camera state $cameraState, $numObjects objects on card")
    }

    /** Back to remote live view on the already open sockets (libfuji's config_liveview, subsequent switch). */
    fun enterLiveView() {
        log.log("Switching camera back to live view")
        events()
        setStep("CameraState = remote access", FujiProp.CAMERA_STATE, 6, 2)
        events()
        setStep("ClientState = remote mode", FujiProp.CLIENT_STATE, REMOTE_MODE, 2)
        step("get RemoteVersion") { client.transaction(PtpOp.GET_DEVICE_PROP_VALUE, listOf(FujiProp.REMOTE_VERSION.toLong()), timeoutMs = 12000) }
        setStep("RemoteVersion = ${hexL(remoteVersion)}", FujiProp.REMOTE_VERSION, remoteVersion, 4)
        events()
        startRemote()
        events()
        inGallery = false
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

    /** The quick post-shot preview the camera offers with event 0xC001. */
    fun capturePreview(): ByteArray? = try {
        tryJpeg("GetCapturePreview", client.transaction(PtpOp.FUJI_GET_CAPTURE_PREVIEW, timeoutMs = 8000))
    } catch (e: java.net.SocketTimeoutException) { log.log("  GetCapturePreview timed out"); null }

    private fun dscfNumber(name: String) = Regex("(\\d{3,})").findAll(name).lastOrNull()?.value?.toLongOrNull() ?: -1

    /**
     * Downloads the newest JPEG on the card. Must be called in gallery mode. Checks both ends of the list,
     * since some bodies sort newest first and others oldest first.
     */
    fun fetchNewestJpeg(progress: (Float) -> Unit): Triple<String, ByteArray, String>? {
        val n = numObjects
        if (n <= 0) { log.log("  camera reported no objects ($n)"); return null }
        setStep("EnableCorrectFileSize = 1", FujiProp.ENABLE_CORRECT_FILE_SIZE, 1, 2)
        try { return fetchNewestJpegInner(n, progress) } finally {
            events()
            setStep("EnableCorrectFileSize = 0", FujiProp.ENABLE_CORRECT_FILE_SIZE, 0, 2)
        }
    }

    private fun fetchNewestJpegInner(n: Int, progress: (Float) -> Unit): Triple<String, ByteArray, String>? {
        val candidates = (listOf(1L, 2L) + listOf(n.toLong(), n - 1L)).filter { it in 1..n.toLong() }.distinct()
        val infos = candidates.mapNotNull { h -> objectInfo(h)?.also { log.log("  object $h: ${it.filename} ${it.size / 1024} KB, ${it.width}x${it.height}, format ${hex16(it.format)}") } }
        val best = infos.filter { it.isJpeg }.maxByOrNull { dscfNumber(it.filename) }
            ?: run { log.log("  no JPEG among the newest objects"); return null }
        log.log("  downloading ${best.filename}")
        val bytes = download(best, progress) ?: return null
        return Triple(best.filename, bytes, "gallery object ${best.handle}")
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
