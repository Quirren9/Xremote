package nl.quirren.xremote.ptp

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.SocketFactory

/**
 * Live view on port 55742: repeated packets of u32 length + ~14 byte header + JPEG.
 * If the framing doesn't look right, falls back to scanning for JPEG SOI/EOI markers.
 */
class LiveViewStream(
    private val socketFactory: SocketFactory,
    private val host: String,
    private val port: Int,
    private val log: PtpLog,
    private val onFrame: (ByteArray) -> Unit,
) {
    @Volatile private var running = false
    private var socket: Socket? = null
    private var thread: Thread? = null

    fun start() {
        running = true
        thread = Thread({ runLoop() }, "liveview").apply { isDaemon = true; start() }
    }

    private fun runLoop() {
        var attempt = 0
        while (running) {
            try {
                val s = socketFactory.createSocket()
                s.tcpNoDelay = true
                s.connect(InetSocketAddress(host, port), 5000)
                s.soTimeout = 10000
                s.receiveBufferSize = 512 * 1024
                socket = s
                log.log("Live view connected on :$port")
                attempt = 0
                read(BufferedInputStream(s.getInputStream(), 256 * 1024))
            } catch (e: Exception) {
                if (!running) break
                attempt++
                log.log("Live view: ${e.javaClass.simpleName}: ${e.message} (retry $attempt)")
                try { socket?.close() } catch (_: Exception) {}
                if (attempt > 20) { log.log("Live view gave up"); break }
                Thread.sleep(minOf(500L * attempt, 3000L))
            }
        }
    }

    private fun read(raw: InputStream) {
        val input = DataInputStream(raw)
        var warnedNoSoi = false
        val lenB = ByteArray(4)
        while (running) {
            input.readFully(lenB)
            val len = lenB.u32(0)
            if (len < 20 || len > 16L * 1024 * 1024) {
                log.log("Live view framing unexpected (len=$len), switching to marker scan")
                scanMarkers(lenB, input)
                return
            }
            val body = ByteArray((len - 4).toInt())
            input.readFully(body)
            val soi = findSoi(body, 0, minOf(body.size - 1, 512))
            if (soi < 0) {
                if (!warnedNoSoi) { log.log("Live view packet without JPEG: ${body.hex(32)}"); warnedNoSoi = true }
                continue
            }
            onFrame(if (soi == 0) body else body.copyOfRange(soi, body.size))
        }
    }

    private fun findSoi(b: ByteArray, from: Int, to: Int): Int {
        for (i in from until to) if (b[i] == 0xFF.toByte() && b[i + 1] == 0xD8.toByte()) return i
        return -1
    }

    /** Framing-agnostic fallback: emit everything between FFD8 and FFD9. */
    private fun scanMarkers(prefix: ByteArray, input: InputStream) {
        val buf = ByteArrayOutputStream(256 * 1024)
        var inFrame = false
        var prev = -1
        fun feed(b: Int) {
            if (!inFrame) {
                if (prev == 0xFF && b == 0xD8) { inFrame = true; buf.reset(); buf.write(0xFF); buf.write(0xD8) }
            } else {
                buf.write(b)
                if (prev == 0xFF && b == 0xD9) { inFrame = false; onFrame(buf.toByteArray()) }
                if (buf.size() > 16 * 1024 * 1024) { inFrame = false; buf.reset() }
            }
            prev = b
        }
        prefix.forEach { feed(it.toInt() and 0xFF) }
        val chunk = ByteArray(64 * 1024)
        while (running) {
            val n = input.read(chunk)
            if (n < 0) throw java.io.EOFException("stream closed")
            for (i in 0 until n) feed(chunk[i].toInt() and 0xFF)
        }
    }

    fun stop() {
        running = false
        try { socket?.close() } catch (_: Exception) {}
        thread?.interrupt()
    }
}

/** Event channel on port 55741. We keep it open (the camera expects it) and log what arrives. */
class EventChannel(
    private val socketFactory: SocketFactory,
    private val host: String,
    private val port: Int,
    private val log: PtpLog,
    private val onEvent: (code: Int, payload: ByteArray) -> Unit,
) {
    @Volatile private var running = false
    private var socket: Socket? = null

    fun start() {
        running = true
        Thread({
            var attempt = 0
            while (running) try {
                val s = socketFactory.createSocket()
                s.connect(InetSocketAddress(host, port), 5000)
                s.soTimeout = 0
                socket = s
                log.log("Event channel connected on :$port")
                attempt = 0
                val input = DataInputStream(BufferedInputStream(s.getInputStream()))
                val lenB = ByteArray(4)
                while (running) {
                    input.readFully(lenB)
                    val len = lenB.u32(0)
                    if (len < 8 || len > 1024 * 1024) { log.log("Event channel: odd length $len, stopping"); break }
                    val body = ByteArray((len - 4).toInt()); input.readFully(body)
                    val code = if (body.size >= 4) body.u16(2) else 0
                    log.log("< async ${body.hex(32)}")
                    onEvent(code, body)
                }
                break
            } catch (e: Exception) {
                if (!running) break
                attempt++
                log.log("Event channel: ${e.message} (retry $attempt)")
                try { socket?.close() } catch (_: Exception) {}
                if (attempt > 20) { log.log("Event channel gave up"); break }
                Thread.sleep(minOf(500L * attempt, 3000L))
            }
        }, "events").apply { isDaemon = true; start() }
    }

    fun stop() {
        running = false
        try { socket?.close() } catch (_: Exception) {}
    }
}
