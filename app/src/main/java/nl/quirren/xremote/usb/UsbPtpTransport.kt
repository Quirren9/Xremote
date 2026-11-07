package nl.quirren.xremote.usb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import nl.quirren.xremote.ptp.LeReader
import nl.quirren.xremote.ptp.LeWriter
import nl.quirren.xremote.ptp.PtpEventSource
import nl.quirren.xremote.ptp.PtpException
import nl.quirren.xremote.ptp.PtpLog
import nl.quirren.xremote.ptp.PtpResp
import nl.quirren.xremote.ptp.PtpResponse
import nl.quirren.xremote.ptp.PtpTransport
import nl.quirren.xremote.ptp.hex
import nl.quirren.xremote.ptp.hex16
import nl.quirren.xremote.ptp.hexL
import nl.quirren.xremote.ptp.u16
import nl.quirren.xremote.ptp.u32
import java.io.ByteArrayOutputStream
import java.net.SocketTimeoutException

object FujiUsb {
    const val FUJI_VENDOR = 0x04CB

    fun findCamera(um: UsbManager): UsbDevice? = um.deviceList.values.firstOrNull { it.vendorId == FUJI_VENDOR }

    fun open(um: UsbManager, dev: UsbDevice, log: PtpLog): UsbPtpTransport {
        val intfs = (0 until dev.interfaceCount).map { dev.getInterface(it) }
        log.log("USB device ${dev.productName ?: dev.deviceName} (${hex16(dev.vendorId)}:${hex16(dev.productId)}), " +
            "interfaces: ${intfs.joinToString { "class ${it.interfaceClass}" }}")
        val intf = intfs.firstOrNull { it.interfaceClass == UsbConstants.USB_CLASS_STILL_IMAGE }
            ?: throw IllegalStateException(
                if (intfs.any { it.interfaceClass == UsbConstants.USB_CLASS_MASS_STORAGE })
                    "The camera is in card reader mode. On the camera: SET UP > CONNECTION SETTING > PC CONNECTION MODE > USB TETHER SHOOTING AUTO, then plug in again."
                else "The camera offers no tether interface. Set PC CONNECTION MODE to USB TETHER SHOOTING AUTO."
            )
        var epIn: UsbEndpoint? = null; var epOut: UsbEndpoint? = null; var epInt: UsbEndpoint? = null
        for (i in 0 until intf.endpointCount) {
            val e = intf.getEndpoint(i)
            when {
                e.type == UsbConstants.USB_ENDPOINT_XFER_BULK && e.direction == UsbConstants.USB_DIR_IN -> epIn = e
                e.type == UsbConstants.USB_ENDPOINT_XFER_BULK && e.direction == UsbConstants.USB_DIR_OUT -> epOut = e
                e.type == UsbConstants.USB_ENDPOINT_XFER_INT && e.direction == UsbConstants.USB_DIR_IN -> epInt = e
            }
        }
        if (epIn == null || epOut == null) throw IllegalStateException("USB tether interface has no bulk endpoints")
        val conn = um.openDevice(dev) ?: throw IllegalStateException("Android didn't open the USB device (permission?)")
        if (!conn.claimInterface(intf, true)) { conn.close(); throw IllegalStateException("Couldn't claim the camera's USB interface. Close other camera apps and plug in again.") }
        log.log("USB tether interface claimed (packet ${epIn.maxPacketSize} B, events ${if (epInt != null) "yes" else "no"})")
        return UsbPtpTransport(conn, intf, epIn, epOut, epInt, log, dev.productName)
    }
}

/** Plain PTP over USB bulk endpoints: the same containers Fuji's WiFi protocol uses, minus the TCP framing. */
class UsbPtpTransport(
    private val conn: UsbDeviceConnection,
    private val intf: UsbInterface,
    private val epIn: UsbEndpoint,
    private val epOut: UsbEndpoint,
    private val epInt: UsbEndpoint?,
    private val log: PtpLog,
    override val cameraName: String?,
) : PtpTransport, PtpEventSource {
    @Volatile override var verbose = true
    private var tid = 0L
    override val lastTid get() = tid
    @Volatile private var open = true
    override val connected get() = open
    private val lock = Any()
    private val buf = ByteArray(1 shl 20)
    /** Bytes read past the end of the previous container (device without zero-length packets). */
    private var leftover: ByteArray? = null

    private fun container(type: Int, code: Int, id: Long, payload: ByteArray): ByteArray =
        LeWriter().u32(12L + payload.size).u16(type).u16(code).u32(id).bytes(payload).toByteArray()

    private fun write(b: ByteArray, timeoutMs: Int) {
        var off = 0
        while (off < b.size) {
            val n = conn.bulkTransfer(epOut, b, off, b.size - off, timeoutMs)
            if (n < 0) throw SocketTimeoutException("USB write timed out")
            off += n
        }
        if (b.size % epOut.maxPacketSize == 0) conn.bulkTransfer(epOut, ByteArray(0), 0, timeoutMs)  // ZLP
    }

    private fun readContainer(deadline: Long): ByteArray {
        val out = ByteArrayOutputStream()
        leftover?.let { out.write(it); leftover = null }
        var need = -1L
        while (true) {
            if (need < 0 && out.size() >= 4) need = out.toByteArray().u32(0)
            if (need in 0..out.size().toLong()) break
            val remaining = (deadline - System.currentTimeMillis()).toInt()
            if (remaining <= 0) throw SocketTimeoutException("USB read timed out")
            val n = conn.bulkTransfer(epIn, buf, buf.size, remaining)
            if (n < 0) throw SocketTimeoutException("USB read timed out")
            if (n == 0) continue   // zero-length packet
            out.write(buf, 0, n)
        }
        val all = out.toByteArray()
        if (need < 12 || need > 256L * 1024 * 1024) throw PtpException("Bad USB container length $need (${all.hex(16)})")
        if (all.size > need) leftover = all.copyOfRange(need.toInt(), all.size)
        return if (all.size.toLong() == need) all else all.copyOfRange(0, need.toInt())
    }

    override fun transaction(op: Int, params: List<Long>, dataOut: ByteArray?, timeoutMs: Int): PtpResponse = synchronized(lock) {
        if (!open) throw PtpException("Not connected")
        val id = ++tid
        val v = verbose
        if (v) log.log("> ${hex16(op)} #$id " + params.joinToString(" ") { hexL(it) } + (dataOut?.let { " data: ${it.hex(24)}" } ?: ""))
        val p = LeWriter().apply { params.forEach { u32(it) } }.toByteArray()
        write(container(1, op, id, p), timeoutMs)
        if (dataOut != null) write(container(2, op, id, dataOut), timeoutMs)
        val deadline = System.currentTimeMillis() + timeoutMs
        var data: ByteArray? = null
        while (true) {
            val c = readContainer(deadline)
            val type = c.u16(4); val code = c.u16(6); val rid = c.u32(8)
            val payload = c.copyOfRange(12, c.size)
            when (type) {
                2 -> data = if (data == null) payload else data + payload
                3 -> {
                    if (rid != id) { log.log("! dropping late USB response ${PtpResp.name(code)} for #$rid"); continue }
                    val rp = LeReader(payload).let { r -> List(payload.size / 4) { r.u32() } }
                    val resp = PtpResponse(code, rp, data)
                    if (v || !resp.ok) log.log("< ${hex16(op)} #$id $resp" + (data?.let { " ${it.hex(32)}" } ?: ""))
                    return resp
                }
                else -> log.log("< USB container type $type ${c.hex(24)}")
            }
        }
        @Suppress("UNREACHABLE_CODE") error("unreachable")
    }

    private var eventThread: Thread? = null

    override fun startEvents(onEvent: (Int, ByteArray) -> Unit) {
        val ep = epInt ?: return
        eventThread = Thread({
            val b = ByteArray(maxOf(64, ep.maxPacketSize))
            while (open) {
                val n = try { conn.bulkTransfer(ep, b, b.size, 1000) } catch (_: Exception) { -1 }
                if (n >= 12) {
                    val body = b.copyOfRange(4, n)
                    log.log("< USB event ${body.hex(24)}")
                    try { onEvent(body.u16(2), body) } catch (e: Exception) { log.log("Event handler: ${e.message}") }
                }
            }
        }, "usb-events").apply { isDaemon = true; start() }
    }

    override fun stopEvents() { eventThread?.interrupt() }

    override fun close() {
        open = false
        runCatching { conn.releaseInterface(intf) }
        runCatching { conn.close() }
    }
}
