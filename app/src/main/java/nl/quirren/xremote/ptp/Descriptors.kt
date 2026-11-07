package nl.quirren.xremote.ptp

sealed class PropForm {
    data object None : PropForm()
    data class Range(val min: Long, val max: Long, val step: Long) : PropForm()
    data class Enum(val values: List<Long>) : PropForm()
}

data class PropDesc(
    val code: Int,
    val dataType: Int,
    val writable: Boolean,
    val current: Long,
    val text: String? = null,
    val form: PropForm = PropForm.None,
    /** True when built from a plain value read because the camera refused GetDevicePropDesc. */
    val synthetic: Boolean = false,
) {
    /** All selectable values, expanded from enum or (reasonably small) range. */
    fun choices(): List<Long> = when (form) {
        is PropForm.Enum -> form.values
        is PropForm.Range -> {
            val step = if (form.step <= 0) 1 else form.step
            val n = (form.max - form.min) / step + 1
            if (n in 1..400) (0 until n).map { form.min + it * step } else emptyList()
        }
        PropForm.None -> KnownProps.get(code)?.fallbackChoices ?: emptyList()
    }

    companion object {
        /** Standard PTP DevicePropDesc dataset. */
        fun parse(data: ByteArray): PropDesc {
            val r = LeReader(data)
            val code = r.u16()
            val type = r.u16()
            val getSet = r.u8()
            r.readAny(type) // factory default
            val (cur, text) = r.readAny(type)
            val form = if (r.remaining > 0) {
                when (r.u8()) {
                    1 -> PropForm.Range(r.value(type), r.value(type), r.value(type))
                    2 -> {
                        val n = r.u16()
                        PropForm.Enum(List(n) { r.value(type) })
                    }
                    else -> PropForm.None
                }
            } else PropForm.None
            return PropDesc(code, type, getSet == 1, cur, text, form)
        }
    }
}

data class DeviceInfo(
    val vendorExt: String,
    val operations: List<Int>,
    val events: List<Int>,
    val properties: List<Int>,
    val manufacturer: String,
    val model: String,
    val version: String,
    val serial: String,
) {
    companion object {
        fun parse(data: ByteArray): DeviceInfo {
            val r = LeReader(data)
            r.u16(); r.u32(); r.u16()
            val ext = r.ptpString()
            r.u16()
            val ops = r.u16Array()
            val evs = r.u16Array()
            val props = r.u16Array()
            r.u16Array(); r.u16Array()
            return DeviceInfo(ext, ops, evs, props, r.ptpString(), r.ptpString(), r.ptpString(), r.ptpString())
        }
    }
}
