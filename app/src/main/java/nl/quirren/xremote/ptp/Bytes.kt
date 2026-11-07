package nl.quirren.xremote.ptp

import java.io.ByteArrayOutputStream

/** Little-endian helpers. PTP is little-endian everywhere. */

fun ByteArray.u8(o: Int): Int = this[o].toInt() and 0xFF
fun ByteArray.u16(o: Int): Int = u8(o) or (u8(o + 1) shl 8)
fun ByteArray.u32(o: Int): Long = u16(o).toLong() or (u16(o + 2).toLong() shl 16)

fun ByteArray.hex(max: Int = 96): String {
    val n = minOf(size, max)
    val sb = StringBuilder(n * 3 + 16)
    for (i in 0 until n) {
        if (i > 0) sb.append(' ')
        sb.append(HEX[u8(i) ushr 4]).append(HEX[u8(i) and 0xF])
    }
    if (size > max) sb.append(" ... (").append(size).append(" bytes)")
    return sb.toString()
}

private val HEX = "0123456789abcdef".toCharArray()

fun hex16(v: Int) = "0x" + v.toString(16).uppercase().padStart(4, '0')
fun hexL(v: Long) = "0x" + v.toString(16).uppercase()

class LeWriter {
    private val out = ByteArrayOutputStream()
    fun u8(v: Int) = apply { out.write(v and 0xFF) }
    fun u16(v: Int) = apply { u8(v); u8(v ushr 8) }
    fun u32(v: Long) = apply { u16((v and 0xFFFF).toInt()); u16(((v ushr 16) and 0xFFFF).toInt()) }
    fun u64(v: Long) = apply { u32(v and 0xFFFFFFFFL); u32(v ushr 32) }
    fun bytes(b: ByteArray) = apply { out.write(b) }
    fun toByteArray(): ByteArray = out.toByteArray()
}

class LeReader(private val b: ByteArray, var pos: Int = 0) {
    val remaining get() = b.size - pos
    fun u8(): Int = b.u8(pos).also { pos += 1 }
    fun u16(): Int = b.u16(pos).also { pos += 2 }
    fun u32(): Long = b.u32(pos).also { pos += 4 }
    fun u64(): Long = (u32() or (u32() shl 32))
    fun i8(): Long = u8().toByte().toLong()
    fun i16(): Long = u16().toShort().toLong()
    fun i32(): Long = u32().toInt().toLong()

    /** PTP string: u8 char count (incl. terminator), then UTF-16LE chars. */
    fun ptpString(): String {
        val n = u8()
        if (n == 0) return ""
        val chars = CharArray(n) { u16().toChar() }
        return String(chars).trimEnd('\u0000')
    }

    fun u16Array(): List<Int> {
        val n = u32().toInt()
        require(n in 0..10000) { "bad array length $n" }
        return List(n) { u16() }
    }

    /** Reads a value of the given PTP data type. Strings return 0 (use [readAny] for text). */
    fun value(type: Int): Long = when (type) {
        PtpType.INT8 -> i8()
        PtpType.UINT8 -> u8().toLong()
        PtpType.INT16 -> i16()
        PtpType.UINT16 -> u16().toLong()
        PtpType.INT32 -> i32()
        PtpType.UINT32 -> u32()
        PtpType.INT64, PtpType.UINT64 -> u64()
        PtpType.STR -> { ptpString(); 0L }
        else -> {
            if (type and 0x4000 != 0) { // array: u32 count + elements
                val n = u32().toInt()
                repeat(n) { value(type and 0x3FFF) }
                0L
            } else error("unsupported data type ${hex16(type)}")
        }
    }

    fun readAny(type: Int): Pair<Long, String?> =
        if (type == PtpType.STR) 0L to ptpString() else value(type) to null
}
