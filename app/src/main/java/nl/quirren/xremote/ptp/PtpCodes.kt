package nl.quirren.xremote.ptp

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

object PtpOp {
    const val GET_DEVICE_INFO = 0x1001
    const val OPEN_SESSION = 0x1002
    const val CLOSE_SESSION = 0x1003
    const val GET_OBJECT_INFO = 0x1008
    const val GET_OBJECT = 0x1009
    const val INITIATE_CAPTURE = 0x100E
    const val GET_PARTIAL_OBJECT = 0x101B
    const val GET_DEVICE_PROP_DESC = 0x1014
    const val GET_DEVICE_PROP_VALUE = 0x1015
    const val SET_DEVICE_PROP_VALUE = 0x1016

    // Fujifilm vendor operations (from the open-source reverse-engineering projects)
    const val FUJI_CAMERA_CAPABILITIES = 0x902B
    const val FUJI_START_REMOTE = 0x9037   // no reply on the X-T3, not used
    const val INITIATE_OPEN_CAPTURE = 0x101C   // starts remote live view; camera then opens 55741/55742
}

object PtpResp {
    const val OK = 0x2001
    const val GENERAL_ERROR = 0x2002
    const val OPERATION_NOT_SUPPORTED = 0x2005
    const val DEVICE_BUSY = 0x2019
    const val INVALID_DEVICE_PROP_FORMAT = 0x201B
    const val INVALID_DEVICE_PROP_VALUE = 0x201C
    const val DEVICE_PROP_NOT_SUPPORTED = 0x200A

    fun name(code: Int): String = when (code) {
        OK -> "OK"
        GENERAL_ERROR -> "GeneralError"
        0x2003 -> "SessionNotOpen"
        0x2004 -> "InvalidTransactionID"
        OPERATION_NOT_SUPPORTED -> "OperationNotSupported"
        0x2006 -> "ParameterNotSupported"
        DEVICE_PROP_NOT_SUPPORTED -> "DevicePropNotSupported"
        DEVICE_BUSY -> "DeviceBusy"
        INVALID_DEVICE_PROP_FORMAT -> "InvalidDevicePropFormat"
        INVALID_DEVICE_PROP_VALUE -> "InvalidDevicePropValue"
        0x201E -> "SessionAlreadyOpen"
        else -> hex16(code)
    }
}

object PtpType {
    const val INT8 = 1
    const val UINT8 = 2
    const val INT16 = 3
    const val UINT16 = 4
    const val INT32 = 5
    const val UINT32 = 6
    const val INT64 = 7
    const val UINT64 = 8
    const val STR = 0xFFFF

    fun size(type: Int): Int = when (type) {
        INT8, UINT8 -> 1
        INT16, UINT16 -> 2
        INT32, UINT32 -> 4
        INT64, UINT64 -> 8
        else -> 4
    }
}

/** Fujifilm property codes used during the remote-mode handshake and for status. */
object FujiProp {
    const val CAMERA_STATE = 0xDF00
    const val CLIENT_STATE = 0xDF01      // "function mode": 5 = remote shooting
    const val REMOTE_VERSION = 0xDF24
    const val CHANGED_PROPS = 0xD212     // list of (code, value) that changed since last poll
    const val SHUTTER_PRESS = 0xD208
    const val FOCUS_LOCK = 0xD209
    const val FOCUS_POINT = 0xD17C
    const val BATTERY = 0xD242
    const val SHOTS_REMAINING = 0xD229
    const val MOVIE_REMAINING = 0xD22A
    const val DEVICE_ERROR = 0xD21B

    /** Properties that are part of the protocol plumbing, not user controls. */
    val internal = setOf(CAMERA_STATE, CLIENT_STATE, REMOTE_VERSION, CHANGED_PROPS, SHUTTER_PRESS, DEVICE_ERROR)
}

enum class PropGroup(val title: String) { EXPOSURE("Exposure"), IMAGE("Image"), FOCUS("Focus & drive"), STATUS("Status"), OTHER("Other") }

class KnownProp(
    val code: Int,
    val name: String,
    val short: String,
    val group: PropGroup,
    val order: Int,
    /** Value list to offer when the camera doesn't send a descriptor. */
    val fallbackChoices: List<Long> = emptyList(),
    val format: (Long) -> String = { hexL(it) },
)

object KnownProps {
    private fun map(vararg p: Pair<Long, String>): (Long) -> String {
        val m = mapOf(*p)
        return { v -> m[v] ?: hexL(v) }
    }

    val filmSims = listOf(
        1L to "Provia", 2L to "Velvia", 3L to "Astia", 4L to "Pro Neg Hi", 5L to "Pro Neg Std",
        6L to "Mono", 7L to "Mono+Ye", 8L to "Mono+R", 9L to "Mono+G", 10L to "Sepia",
        11L to "Cl. Chrome", 12L to "Acros", 13L to "Acros+Ye", 14L to "Acros+R", 15L to "Acros+G",
        16L to "Eterna",
    )

    private val wb = listOf(
        2L to "Auto", 4L to "Daylight", 6L to "Incand.", 0x8001L to "Fluor. 1", 0x8002L to "Fluor. 2",
        0x8003L to "Fluor. 3", 0x8006L to "Shade", 0x8007L to "Kelvin", 0x8008L to "Underwater",
        0x8009L to "Custom 1", 0x800AL to "Custom 2", 0x800BL to "Custom 3",
    )

    fun fNumber(v: Long): String = if (v <= 0) "A" else String.format(Locale.US, "f/%.1f", v / 100.0)

    fun ev(v: Long): String {
        val ev = v / 1000.0
        return if (abs(ev) < 0.05) "±0" else String.format(Locale.US, "%+.1f", ev)
    }

    fun iso(v: Long): String {
        if (v == 0xFFFFFFFFL || v == -1L) return "AUTO"
        val base = v and 0xFFFFFF
        val auto = v and 0x80000000L != 0L
        val ext = v and 0x40000000L != 0L
        val s = if (base == 0L) "AUTO" else base.toString()
        return (if (auto && base != 0L) "A " else "") + s + (if (ext) " ext" else "")
    }

    /**
     * Fuji's 0xD240 shutter value. Encoding is not fully documented; treated here as
     * microseconds with flag bits masked. The raw value is always shown alongside.
     */
    fun shutter(v: Long): String {
        val us = v and 0x0FFFFFFF
        if (us == 0L) return "A"
        return if (us < 1_000_000) "1/" + (1_000_000.0 / us).roundToLong()
        else String.format(Locale.US, "%.1f\"", us / 1_000_000.0).replace(".0\"", "\"")
    }

    fun shutterStd(v: Long): String { // standard 0x500D, units of 0.1 ms
        if (v <= 0) return "A"
        return if (v < 10000) "1/" + (10000.0 / v).roundToLong() else "${v / 10000}\""
    }

    val all: Map<Int, KnownProp> = listOf(
        KnownProp(0x500E, "Exposure mode", "Mode", PropGroup.EXPOSURE, 0,
            format = map(1L to "M", 2L to "P", 3L to "A", 4L to "S", 0x8001L to "Movie")),
        KnownProp(0xD240, "Shutter speed", "SS", PropGroup.EXPOSURE, 1, format = ::shutter),
        KnownProp(0x500D, "Exposure time", "SS", PropGroup.EXPOSURE, 2, format = ::shutterStd),
        KnownProp(0x5007, "Aperture", "F", PropGroup.EXPOSURE, 3, format = ::fNumber),
        KnownProp(0xD02A, "ISO", "ISO", PropGroup.EXPOSURE, 4, format = ::iso),
        KnownProp(0x500F, "ISO (std)", "ISO", PropGroup.EXPOSURE, 5, format = ::iso),
        KnownProp(0x5010, "Exposure comp.", "EV", PropGroup.EXPOSURE, 6,
            fallbackChoices = (-15..15).map { (it * 1000.0 / 3).roundToLong() },
            format = ::ev),
        KnownProp(0x500B, "Metering", "Meter", PropGroup.EXPOSURE, 7,
            format = map(1L to "Average", 2L to "Center", 3L to "Multi", 4L to "Spot",
                0x8001L to "Multi", 0x8002L to "Spot", 0x8003L to "Average", 0x8004L to "Center")),
        KnownProp(0x500C, "Flash", "Flash", PropGroup.EXPOSURE, 8,
            format = map(1L to "Auto", 2L to "Off", 3L to "Fill", 4L to "Red-eye", 0x8001L to "Commander",
                0x8002L to "Off", 0x8003L to "Slow sync", 0x8004L to "2nd curtain", 0x8005L to "TTL", 0x8009L to "Off")),

        KnownProp(0xD001, "Film simulation", "Film", PropGroup.IMAGE, 10,
            fallbackChoices = filmSims.map { it.first }, format = map(*filmSims.toTypedArray())),
        KnownProp(0x5005, "White balance", "WB", PropGroup.IMAGE, 11,
            fallbackChoices = wb.map { it.first }, format = map(*wb.toTypedArray())),
        KnownProp(0xD018, "Image quality", "Quality", PropGroup.IMAGE, 12,
            format = map(1L to "RAW", 2L to "Fine", 3L to "Normal", 4L to "Fine+RAW", 5L to "Normal+RAW")),
        KnownProp(0x5003, "Image size", "Size", PropGroup.IMAGE, 13),
        KnownProp(0x5004, "Compression", "Comp.", PropGroup.IMAGE, 14),

        KnownProp(0x500A, "Focus mode", "AF", PropGroup.FOCUS, 20,
            format = map(1L to "MF", 2L to "AF", 0x8001L to "AF-S", 0x8002L to "AF-C")),
        KnownProp(0xD17C, "Focus point", "Point", PropGroup.FOCUS, 21,
            format = { v -> "${(v ushr 8) and 0xFF},${v and 0xFF}" }),
        KnownProp(0x5012, "Self-timer", "Timer", PropGroup.FOCUS, 22,
            fallbackChoices = listOf(0L, 2L, 4L),
            // X-T3 reports 0 / 2 / 4 (assumed Off / 2 s / 10 s); larger values are milliseconds
            format = { v -> when { v == 0L -> "Off"; v == 2L -> "2s"; v == 4L -> "10s"; v >= 1000 -> "${v / 1000}s"; else -> hexL(v) } }),
        KnownProp(0x5013, "Drive mode", "Drive", PropGroup.FOCUS, 23,
            format = map(1L to "Single", 2L to "Burst", 3L to "Timelapse")),
        KnownProp(FujiProp.FOCUS_LOCK, "Focus lock", "AF-L", PropGroup.FOCUS, 24,
            format = map(0L to "Off", 1L to "On")),

        KnownProp(FujiProp.BATTERY, "Battery", "Batt", PropGroup.STATUS, 30, format = { "$it" }),
        KnownProp(0x5001, "Battery level", "Batt", PropGroup.STATUS, 31, format = { "$it%" }),
        KnownProp(FujiProp.SHOTS_REMAINING, "Shots remaining", "Shots", PropGroup.STATUS, 32, format = { "$it" }),
        KnownProp(FujiProp.MOVIE_REMAINING, "Movie remaining", "Movie", PropGroup.STATUS, 33, format = { "${it / 60}:${(it % 60).toString().padStart(2, '0')}" }),
    ).associateBy { it.code }

    fun get(code: Int): KnownProp? = all[code]
    fun nameOf(code: Int): String = all[code]?.name ?: "Property ${hex16(code)}"
    fun format(code: Int, v: Long, text: String? = null): String = text ?: all[code]?.format?.invoke(v) ?: hexL(v)
}
