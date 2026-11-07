package nl.quirren.xremote.ptp

import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.SocketFactory

fun interface PtpLog { fun log(msg: String) }

class PtpException(msg: String) : IOException(msg)

class PtpResponse(val code: Int, val params: List<Long>, val data: ByteArray?) {
    val ok get() = code == PtpResp.OK
    override fun toString() = "${PtpResp.name(code)}" +
        (if (params.isNotEmpty()) " params=" + params.joinToString { hexL(it) } else "") +
        (data?.let { " data[${it.size}]" } ?: "")
}

/**
 * Command channel of Fujifilm's PTP/IP dialect.
 *
 * Fuji uses a standard PTP/IP InitCommandRequest to register, then switches to
 * USB-style PTP containers over TCP: u32 length, u16 type, u16 code, u32 transaction id, payload.
 * Types: 1 = command, 2 = data, 3 = response, 4 = event.
 */
class FujiPtpClient(
    private val socketFactory: SocketFactory = SocketFactory.getDefault(),
    private val log: PtpLog,
) {
    private var socket: Socket? = null
    private lateinit var input: DataInputStream
    private lateinit var output: OutputStream
    private var tid = 0L
    private val lock = Any()

    /** Camera name from the registration reply, e.g. "X-T3". */
    var cameraName: String? = null; private set

    /** Set to false to stop logging every command (e.g. during fast polling). */
    @Volatile var verbose = true

    val connected get() = socket?.isConnected == true && socket?.isClosed == false

    fun connect(host: String, port: Int, clientName: String, connectTimeoutMs: Int = 6000, registerTimeoutMs: Int = 45000) {
        log.log("Connecting to $host:$port")
        val s = socketFactory.createSocket()
        s.tcpNoDelay = true
        s.connect(InetSocketAddress(host, port), connectTimeoutMs)
        socket = s
        input = DataInputStream(s.getInputStream().buffered(64 * 1024))
        output = s.getOutputStream()

        // Registration. The camera may show "accept this device?" on first pairing,
        // so the reply can take as long as the user needs to press OK.
        val req = initCommandRequest(clientName)
        log.log("> InitCommandRequest ${req.hex(40)}")
        output.write(req); output.flush()
        s.soTimeout = registerTimeoutMs
        val reply = readRaw()
        val type = reply.u32(0)
        log.log("< Init reply type=$type ${reply.hex(48)}")
        when (type) {
            2L -> {
                cameraName = utf16(reply, 24)
                log.log("Registered with camera ${cameraName ?: ""}")
            }
            5L -> {
                val reason = if (reply.size >= 8) reply.u32(4) else 0
                throw PtpException("Camera refused connection (${PtpResp.name(reason.toInt())}). " +
                    "If it says busy, turn WiFi off/on on the camera and try again.")
            }
            else -> throw PtpException("Unexpected init reply type $type")
        }
        s.soTimeout = 8000
        tid = 0
    }

    private fun utf16(b: ByteArray, off: Int): String? {
        if (b.size < off + 2) return null
        val sb = StringBuilder()
        var i = off
        while (i + 1 < b.size) { val c = b.u16(i); if (c == 0) break; sb.append(c.toChar()); i += 2 }
        return sb.toString().takeIf { it.isNotBlank() }
    }

    /** Reads one length-prefixed packet, returns bytes after the length field. */
    private fun readRaw(): ByteArray {
        val lenBytes = ByteArray(4)
        input.readFully(lenBytes)
        val len = lenBytes.u32(0)
        if (len < 8 || len > 64L * 1024 * 1024) throw PtpException("Bad packet length $len (${lenBytes.hex()})")
        val body = ByteArray((len - 4).toInt())
        input.readFully(body)
        return body
    }

    private fun container(type: Int, code: Int, id: Long, payload: ByteArray): ByteArray =
        LeWriter().u32(12L + payload.size).u16(type).u16(code).u32(id).bytes(payload).toByteArray()

    fun transaction(
        op: Int,
        params: List<Long> = emptyList(),
        dataOut: ByteArray? = null,
        timeoutMs: Int = 8000,
    ): PtpResponse = synchronized(lock) {
        val s = socket ?: throw PtpException("Not connected")
        s.soTimeout = timeoutMs
        val id = ++tid
        val p = LeWriter().apply { params.forEach { u32(it) } }.toByteArray()
        val v = verbose
        if (v) log.log("> ${hex16(op)} #$id " + params.joinToString(" ") { hexL(it) } +
            (dataOut?.let { " data: ${it.hex(24)}" } ?: ""))
        output.write(container(1, op, id, p))
        if (dataOut != null) output.write(container(2, op, id, dataOut))
        output.flush()

        var data: ByteArray? = null
        while (true) {
            val body = readRaw()
            if (body.size < 8) throw PtpException("Short container ${body.hex()}")
            val type = body.u16(0)
            val code = body.u16(2)
            val rid = body.u32(4)
            val payload = body.copyOfRange(8, body.size)
            when (type) {
                2 -> if (rid == id) data = if (data == null) payload else data + payload
                    else log.log("! dropping late data for #$rid")
                3 -> {
                    if (rid != id) {   // late answer to an earlier, timed-out command
                        log.log("! dropping late response ${PtpResp.name(code)} for #$rid (waiting for #$id)")
                        continue
                    }
                    val rp = LeReader(payload).let { r -> List(payload.size / 4) { r.u32() } }
                    val resp = PtpResponse(code, rp, data)
                    if (v || !resp.ok) log.log("< ${hex16(op)} #$id $resp" + (data?.let { " ${it.hex(32)}" } ?: ""))
                    return resp
                }
                4 -> log.log("< event ${hex16(code)} ${payload.hex(24)}")
                else -> log.log("< unknown container type $type ${body.hex(32)}")
            }
        }
        @Suppress("UNREACHABLE_CODE") error("unreachable")
    }

    fun close() {
        try { socket?.close() } catch (_: Exception) {}
        socket = null
    }

    companion object {
        /** Fuji's registration packet layout, per fuji-cam-wifi-tool / camlib. */
        fun initCommandRequest(name: String): ByteArray {
            val nameBytes = ByteArray(54)
            val chars = name.take(26)
            chars.forEachIndexed { i, c ->
                nameBytes[i * 2] = (c.code and 0xFF).toByte()
                nameBytes[i * 2 + 1] = (c.code ushr 8).toByte()
            }
            val guid = byteArrayOf(
                0xad.toByte(), 0xa5.toByte(), 0x48, 0x5d, 0x87.toByte(), 0xb2.toByte(), 0x7f, 0x0b,
                0xd3.toByte(), 0xd5.toByte(), 0xde.toByte(), 0xd0.toByte(), 0x02, 0x78, 0xa8.toByte(), 0xc0.toByte(),
            )
            val body = LeWriter().u32(1).u32(0x8f53e4f2L).bytes(guid).bytes(nameBytes).toByteArray()
            return LeWriter().u32(4L + body.size).bytes(body).toByteArray()
        }
    }
}
