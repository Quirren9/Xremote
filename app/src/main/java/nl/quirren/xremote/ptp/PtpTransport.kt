package nl.quirren.xremote.ptp

/** A PTP command channel: Fuji's PTP/IP over WiFi, or plain PTP over a USB cable. */
interface PtpTransport {
    /** Set to false to stop logging every command (e.g. during fast polling). */
    var verbose: Boolean
    /** Transaction id of the most recent command. */
    val lastTid: Long
    val connected: Boolean
    /** Camera name, if the transport learned it while connecting. */
    val cameraName: String?
    /** Timeouts surface as java.net.SocketTimeoutException on every transport. */
    fun transaction(op: Int, params: List<Long> = emptyList(), dataOut: ByteArray? = null, timeoutMs: Int = 8000): PtpResponse
    fun close()
}

/** Transports that deliver camera events themselves (USB interrupt endpoint). */
interface PtpEventSource {
    /** body = container without its length field: u16 type, u16 code, u32 tid, u32 params. */
    fun startEvents(onEvent: (Int, ByteArray) -> Unit)
    fun stopEvents()
}

/**
 * Live view over USB: Fuji serves the current frame as a virtual object (libgphoto2 uses 0x80000001).
 * Polls it in a loop; every frame is one command on the shared channel.
 */
class PolledLiveView(
    private val client: PtpTransport,
    private val log: PtpLog,
    private val onStartCapture: () -> Unit,
    private val onFrame: (ByteArray) -> Unit,
) {
    @Volatile private var running = false
    private var thread: Thread? = null

    fun start() {
        running = true
        thread = Thread({ loop() }, "usb-liveview").apply { isDaemon = true; start() }
    }

    private fun loop() {
        var failures = 0
        var triedCapture = false
        var logged = false
        while (running) {
            try {
                val r = client.transaction(PtpOp.GET_OBJECT, listOf(LIVEVIEW_HANDLE), timeoutMs = 3000)
                val d = r.data
                val at = if (r.ok && d != null) soi(d) else -1
                if (at >= 0) {
                    if (!logged) { log.log("USB live view: first frame (${d!!.size} bytes)"); logged = true }
                    failures = 0
                    onFrame(if (at == 0) d!! else d!!.copyOfRange(at, d.size))
                    Thread.sleep(20)
                    continue
                }
                failures++
                if (failures == 1) log.log("USB live view: ${PtpResp.name(r.code)}, ${d?.size ?: 0} bytes")
                if (failures == 3 && !triedCapture) {
                    triedCapture = true
                    log.log("USB live view: starting capture mode first")
                    runCatching { onStartCapture() }
                }
            } catch (e: java.net.SocketTimeoutException) {
                failures++
            } catch (e: Exception) {
                if (!running) break
                failures++
                if (failures % 20 == 1) log.log("USB live view error: ${e.message}")
            }
            Thread.sleep(if (failures > 10) 1000 else 200)
        }
    }

    private fun soi(d: ByteArray): Int {
        for (i in 0 until minOf(d.size - 1, 2048)) if (d[i] == 0xFF.toByte() && d[i + 1] == 0xD8.toByte()) return i
        return -1
    }

    fun stop() { running = false; thread?.interrupt() }

    companion object { const val LIVEVIEW_HANDLE = 0x80000001L }
}
