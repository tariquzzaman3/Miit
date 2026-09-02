package com.miit.app.band

/**
 * Parser for the Xiaomi protobuf Command messages used after SPPv2 authentication.
 *
 * The field numbers here follow Gadgetbridge's xiaomi.proto:
 * Command.system = field 4, Command.watchface = field 6.
 * System.displayItems = field 10, Watchface.watchfaceList = field 1.
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
    const val WATCHFACE_SET = 1
    const val WATCHFACE_DELETE = 2
    const val WATCHFACE_INSTALL = 4

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

        val t = type ?: return null
        return when (t) {
            TYPE_SYSTEM -> parseSystem(t, subtype, system)
            TYPE_WATCHFACE -> parseWatchface(t, subtype, watchface)
            else -> Parsed(t, subtype)
        }
    }

    private fun parseSystem(type: Int, subtype: Int, system: ByteArray?): Parsed {
        if (system == null) return Parsed(type, subtype)

        var battery: Int? = null
        var batteryState: Int? = null
        var charging: Boolean? = null
        var firmware: String? = null
        var model: String? = null
        var serialNumber: String? = null
        var displays = emptyList<BandDisplay>()

        val r = ProtoReader(system)
        while (r.hasRemaining()) {
            val field = r.nextField() ?: break
            when (field.number) {
                // System.power = 2, Power.battery = 1
                2 -> {
                    val power = field.bytes ?: continue
                    val pr = ProtoReader(power)
                    while (pr.hasRemaining()) {
                        val pf = pr.nextField() ?: break
                        if (pf.number != 1 || pf.bytes == null) continue
                        val br = ProtoReader(pf.bytes)
                        while (br.hasRemaining()) {
                            val bf = br.nextField() ?: break
                            when (bf.number) {
                                1 -> battery = bf.varint?.toInt()
                                2 -> batteryState = bf.varint?.toInt()
                            }
                        }
                    }
                    charging = batteryState == 1
                }

                // System.deviceInfo = 3
                // DeviceInfo: serialNumber=1, firmware=2, unknown3=3, model=4
                3 -> {
                    val info = field.bytes ?: continue
                    val ir = ProtoReader(info)
                    while (ir.hasRemaining()) {
                        val f = ir.nextField() ?: break
                        when (f.number) {
                            1 -> serialNumber = f.stringValue() ?: serialNumber
                            2 -> firmware = f.stringValue() ?: firmware
                            4 -> model = f.stringValue() ?: model
                        }
                    }
                }

                // System.displayItems = 10
                10 -> {
                    val items = field.bytes ?: continue
                    val parsed = parseDisplayItems(items)
                    if (parsed.isNotEmpty()) displays = mergeDisplays(displays, parsed)
                }

                // Device-state responses can also tell us charging state.
                49 -> {
                    val state = field.bytes ?: continue
                    val sr = ProtoReader(state)
                    while (sr.hasRemaining()) {
                        val f = sr.nextField() ?: break
                        if (f.number == 1) {
                            val stateValue = f.varint?.toInt()
                            if (stateValue != null) charging = stateValue == 1
                        }
                    }
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
            serialNumber = serialNumber,
            displays = displays
        )
    }

    private fun parseDisplayItems(data: ByteArray): List<BandDisplay> {
        val result = mutableListOf<BandDisplay>()
        val r = ProtoReader(data)

        // DisplayItems contains repeated DisplayItem in field 1.
        while (r.hasRemaining()) {
            val f = r.nextField() ?: break
            if (f.number == 1 && f.bytes != null) {
                parseDisplayItem(f.bytes)?.let { result += it }
            }
        }

        // Be tolerant of firmware that returns a single DisplayItem directly.
        if (result.isEmpty()) {
            parseDisplayItem(data)?.let { result += it }
        }

        return mergeDisplays(emptyList(), result)
    }

    private fun parseDisplayItem(data: ByteArray): BandDisplay? {
        var code: String? = null
        var name: String? = null
        var disabled = false
        var inMore = false
        var isSettings = false

        val r = ProtoReader(data)
        while (r.hasRemaining()) {
            val f = r.nextField() ?: break
            when (f.number) {
                1 -> code = f.stringValue()
                2 -> name = f.stringValue()
                3 -> disabled = f.varint == 1L
                4 -> isSettings = f.varint == 1L
                6 -> inMore = f.varint == 1L
            }
        }

        // Do not expose the band's settings item as a user-editable display.
        if (isSettings) return null
        if (code == null && name == null) return null

        return BandDisplay(
            code = code,
            name = name,
            disabled = disabled,
            inMoreSection = inMore,
            source = BandDisplay.Source.DISPLAY_ITEM
        )
    }

    private fun parseWatchface(type: Int, subtype: Int, watchface: ByteArray?): Parsed {
        if (watchface == null) return Parsed(type, subtype)
        if (subtype != WATCHFACE_LIST) return Parsed(type, subtype)

        val list = mutableListOf<BandDisplay>()
        val r = ProtoReader(watchface)

        // Watchface.watchfaceList = field 1; WatchfaceList.watchface is repeated field 1.
        while (r.hasRemaining()) {
            val f = r.nextField() ?: break
            if (f.number != 1 || f.bytes == null) continue

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
                    3 -> active = wf.varint == 1L
                    4 -> canDelete = wf.varint == 1L
                }
            }

            if (id != null || name != null) {
                list += BandDisplay(
                    code = id,
                    name = name,
                    active = active,
                    canDelete = canDelete,
                    source = BandDisplay.Source.WATCHFACE
                )
            }
        }

        return Parsed(type, subtype, displays = mergeDisplays(emptyList(), list))
    }

    private fun mergeDisplays(
        first: List<BandDisplay>,
        second: List<BandDisplay>
    ): List<BandDisplay> {
        val out = LinkedHashMap<String, BandDisplay>()
        (first + second).forEach { display ->
            val key = display.stableId
            val previous = out[key]
            out[key] = if (previous == null) display else previous.copy(
                name = display.name ?: previous.name,
                disabled = display.disabled || previous.disabled,
                inMoreSection = display.inMoreSection || previous.inMoreSection,
                active = display.active || previous.active,
                canDelete = display.canDelete || previous.canDelete
            )
        }
        return out.values.toList()
    }

    fun systemGet(subtype: Int): ByteArray = command(TYPE_SYSTEM, subtype)

    fun watchfaceListGet(): ByteArray = command(TYPE_WATCHFACE, WATCHFACE_LIST)

    fun command(type: Int, subtype: Int): ByteArray =
        fieldVarint(1, type) + fieldVarint(2, subtype)

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
        fun stringValue(): String? =
            bytes?.toString(Charsets.UTF_8)?.takeIf { it.isNotEmpty() }
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
