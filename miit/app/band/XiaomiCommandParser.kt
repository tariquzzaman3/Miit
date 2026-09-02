package com.miit.app.band

/**
 * Small protobuf reader for the Xiaomi command messages used after SPPv2
 * authentication. Unknown fields are skipped so newer firmware can still
 * be read without breaking the connection.
 *
 * Command ids mirror Gadgetbridge's XiaomiSystemService and
 * XiaomiWatchfaceService:
 * system=2, watchface=4.
 */
object XiaomiCommandParser {
    const val TYPE_SYSTEM = 2
    const val TYPE_WATCHFACE = 4

    const val SYSTEM_BATTERY = 1
    const val SYSTEM_DEVICE_INFO = 2
    const val SYSTEM_DISPLAY_ITEMS_GET = 29
    const val SYSTEM_WIDGET_SCREENS_GET = 51
    const val SYSTEM_WIDGET_PARTS_GET = 53
    const val SYSTEM_DEVICE_STATE_GET = 78
    const val SYSTEM_DEVICE_STATE = 79

    const val WATCHFACE_LIST = 0

    data class Parsed(
        val type: Int,
        val subtype: Int,
        val battery: Int? = null,
        val batteryState: Int? = null,
        val charging: Boolean? = null,
        val firmware: String? = null,
        val model: String? = null,
        val hardware: String? = null,
        val serialNumber: String? = null,
        val displays: List<BandDisplay> = emptyList()
    )

    fun parse(data: ByteArray): Parsed? {
        val root = ProtoReader(data)
        var type: Int? = null
        var subtype = 0
        var system: ByteArray? = null
        var watchface: ByteArray? = null

        while (root.hasRemaining()) {
            val field = root.nextField() ?: break
            when (field.number) {
                1 -> type = field.varint?.toInt()
                2 -> subtype = field.varint?.toInt() ?: 0
                4 -> system = field.bytes
                6 -> watchface = field.bytes
            }
        }

        if (type == null) return null

        return when (type) {
            TYPE_SYSTEM -> parseSystem(type, subtype, system)
            TYPE_WATCHFACE -> parseWatchface(type, subtype, watchface)
            else -> Parsed(type, subtype)
        }
    }

    private fun parseSystem(type: Int, subtype: Int, system: ByteArray?): Parsed {
        if (system == null) return Parsed(type, subtype)

        var battery: Int? = null
        var batteryState: Int? = null
        var charging: Boolean? = null
        var firmware: String? = null
        var model: String? = null
        var hardware: String? = null
        var serialNumber: String? = null
        var displays: List<BandDisplay> = emptyList()

        val sr = ProtoReader(system)
        while (sr.hasRemaining()) {
            val sf = sr.nextField() ?: break
            when (sf.number) {
                // System.power
                2 -> {
                    val power = sf.bytes ?: continue
                    val br = ProtoReader(power)
                    while (br.hasRemaining()) {
                        val bf = br.nextField() ?: break
                        if (bf.number == 1 && bf.bytes != null) {
                            val b = ProtoReader(bf.bytes)
                            while (b.hasRemaining()) {
                                val item = b.nextField() ?: break
                                when (item.number) {
                                    1 -> battery = item.varint?.toInt()
                                    2 -> batteryState = item.varint?.toInt()
                                }
                            }
                        }
                    }
                    charging = batteryState == 1
                }

                // System.deviceInfo
                3 -> {
                    val info = sf.bytes ?: continue
                    val ir = ProtoReader(info)
                    while (ir.hasRemaining()) {
                        val f = ir.nextField() ?: break
                        when (f.number) {
                            1 -> firmware = f.stringValue()
                            2 -> firmware = firmware ?: f.stringValue()
                            3 -> serialNumber = f.stringValue()
                            4 -> model = f.stringValue()
                            5 -> hardware = f.stringValue()
                        }
                    }
                }

                // System.displayItems. Gadgetbridge models this as a repeated
                // DisplayItem nested under the displayItems message.
                10 -> {
                    val list = sf.bytes ?: continue
                    displays = parseDisplayItems(list)
                }

                // Some firmware exposes display items under a different
                // nested tag. Try it without making it mandatory.
                11 -> {
                    val list = sf.bytes ?: continue
                    if (displays.isEmpty()) displays = parseDisplayItems(list)
                }
            }
        }

        return Parsed(
            type = type,
            subtype = subtype,
            battery = battery,
            batteryState = batteryState,
            charging = charging,
            firmware = firmware,
            model = model,
            hardware = hardware,
            serialNumber = serialNumber,
            displays = displays
        )
    }

    private fun parseDisplayItems(data: ByteArray): List<BandDisplay> {
        val result = mutableListOf<BandDisplay>()
        val r = ProtoReader(data)
        while (r.hasRemaining()) {
            val f = r.nextField() ?: break
            if (f.bytes != null) {
                val candidate = parseDisplayItem(f.bytes)
                if (candidate.code != null || candidate.name != null) result += candidate
            }
        }

        // A single DisplayItem may also arrive directly rather than wrapped
        // in a repeated field.
        if (result.isEmpty()) {
            val candidate = parseDisplayItem(data)
            if (candidate.code != null || candidate.name != null) result += candidate
        }
        return result.distinctBy { (it.code ?: "") + "\u0000" + (it.name ?: "") }
    }

    private fun parseDisplayItem(data: ByteArray): BandDisplay {
        var code: String? = null
        var name: String? = null
        var disabled = false
        var inMore = false
        val r = ProtoReader(data)
        while (r.hasRemaining()) {
            val f = r.nextField() ?: break
            when (f.number) {
                1 -> code = f.stringValue() ?: f.varint?.toString()
                2 -> name = f.stringValue() ?: f.varint?.toString()
                3 -> disabled = (f.varint ?: 0L) != 0L
                6 -> inMore = (f.varint ?: 0L) != 0L
            }
        }
        return BandDisplay(code, name, disabled, inMore)
    }

    private fun parseWatchface(type: Int, subtype: Int, watchface: ByteArray?): Parsed {
        if (watchface == null) return Parsed(type, subtype)
        if (subtype != WATCHFACE_LIST) return Parsed(type, subtype)

        val list = mutableListOf<BandDisplay>()
        val wr = ProtoReader(watchface)
        while (wr.hasRemaining()) {
            val f = wr.nextField() ?: break
            if (f.number == 1 && f.bytes != null) {
                val info = ProtoReader(f.bytes)
                var id: String? = null
                var name: String? = null
                var active = false
                var canDelete = false
                while (info.hasRemaining()) {
                    val wf = info.nextField() ?: break
                    when (wf.number) {
                        1 -> id = wf.stringValue()
                        2 -> name = wf.stringValue()
                        3 -> active = (wf.varint ?: 0L) != 0L
                        4 -> canDelete = (wf.varint ?: 0L) != 0L
                    }
                }
                if (id != null || name != null) {
                    list += BandDisplay(
                        code = id,
                        name = name,
                        disabled = false,
                        inMoreSection = canDelete
                    )
                }
            }
        }
        return Parsed(type, subtype, displays = list)
    }

    fun systemGet(subtype: Int): ByteArray =
        command(TYPE_SYSTEM, subtype)

    fun watchfaceListGet(): ByteArray =
        command(TYPE_WATCHFACE, WATCHFACE_LIST)

    fun command(type: Int, subtype: Int): ByteArray =
        fieldVarint(1, type) + fieldVarint(2, subtype)

    private fun fieldBytes(number: Int, bytes: ByteArray): ByteArray =
        varint((number shl 3) or 2) + varint(bytes.size) + bytes

    private fun fieldVarint(number: Int, value: Int): ByteArray =
        varint(number shl 3) + varint(value)

    private fun varint(valueIn: Int): ByteArray {
        var value = valueIn
        val out = ArrayList<Byte>()
        do {
            var b = value and 0x7F
            value = value ushr 7
            if (value != 0) b = b or 0x80
            out += b.toByte()
        } while (value != 0)
        return out.toByteArray()
    }

    private data class Field(
        val number: Int,
        val wireType: Int,
        val varint: Long? = null,
        val bytes: ByteArray? = null
    ) {
        fun stringValue(): String? = bytes?.toString(Charsets.UTF_8)?.takeIf { it.isNotEmpty() }
    }

    private class ProtoReader(private val data: ByteArray) {
        private var pos = 0
        fun hasRemaining(): Boolean = pos < data.size

        fun nextField(): Field? {
            if (!hasRemaining()) return null
            val tag = readVarint() ?: return null
            val number = (tag ushr 3).toInt()
            val wire = (tag and 7).toInt()
            return when (wire) {
                0 -> Field(number, wire, varint = readVarint())
                1 -> {
                    if (pos + 8 > data.size) return null
                    pos += 8
                    Field(number, wire)
                }
                2 -> {
                    val length = readVarint()?.toInt() ?: return null
                    if (length < 0 || pos + length > data.size) return null
                    val bytes = data.copyOfRange(pos, pos + length)
                    pos += length
                    Field(number, wire, bytes = bytes)
                }
                5 -> {
                    if (pos + 4 > data.size) return null
                    pos += 4
                    Field(number, wire)
                }
                else -> null
            }
        }

        private fun readVarint(): Long? {
            var shift = 0
            var result = 0L
            while (pos < data.size && shift <= 63) {
                val b = data[pos++].toInt() and 0xFF
                result = result or ((b and 0x7F).toLong() shl shift)
                if ((b and 0x80) == 0) return result
                shift += 7
            }
            return null
        }
    }
}
