package com.miit.app.band

/**
 * Minimal proto2 decoder for the Xiaomi Command/System messages used after SPPv2
 * authentication. This intentionally extracts only fields needed by Miit and
 * ignores unknown protobuf fields so firmware differences do not break parsing.
 */
object XiaomiCommandParser {
    data class Parsed(
        val type: Int,
        val subtype: Int,
        val battery: Int? = null,
        val batteryState: Int? = null,
        val firmware: String? = null,
        val model: String? = null,
        val displays: List<BandDisplay> = emptyList()
    )

    fun parse(data: ByteArray): Parsed? {
        val root = ProtoReader(data)
        var type: Int? = null
        var subtype = 0
        var system: ByteArray? = null

        while (root.hasRemaining()) {
            val field = root.nextField() ?: break
            when (field.number) {
                1 -> type = field.varint?.toInt()
                2 -> subtype = field.varint?.toInt() ?: 0
                4 -> system = field.bytes
            }
        }
        if (type == null) return null
        if (type != 2 || system == null) return Parsed(type, subtype)

        var battery: Int? = null
        var batteryState: Int? = null
        var firmware: String? = null
        var model: String? = null
        var displays: List<BandDisplay> = emptyList()

        val sr = ProtoReader(system)
        while (sr.hasRemaining()) {
            val sf = sr.nextField() ?: break
            when (sf.number) {
                // System.power = field 2
                2 -> {
                    val power = sf.bytes ?: continue
                    val br = ProtoReader(power)
                    while (br.hasRemaining()) {
                        val bf = br.nextField() ?: break
                        // Power.battery = field 1
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
                }
                // System.deviceInfo = field 3
                3 -> {
                    val info = sf.bytes ?: continue
                    val ir = ProtoReader(info)
                    while (ir.hasRemaining()) {
                        val f = ir.nextField() ?: break
                        when (f.number) {
                            2 -> firmware = f.stringValue()
                            4 -> model = f.stringValue()
                        }
                    }
                }
                // System.displayItems = field 10
                10 -> {
                    val list = sf.bytes ?: continue
                    val dr = ProtoReader(list)
                    val parsedDisplays = mutableListOf<BandDisplay>()
                    while (dr.hasRemaining()) {
                        val f = dr.nextField() ?: break
                        if (f.number == 1 && f.bytes != null) {
                            parsedDisplays += parseDisplayItem(f.bytes)
                        }
                    }
                    displays = parsedDisplays
                }
            }
        }

        return Parsed(type, subtype, battery, batteryState, firmware, model, displays)
    }

    /** Proto2 request: Command(type=2, subtype=<>, system=<empty nested request>). */
    fun systemGet(subtype: Int): ByteArray =
        fieldVarint(1, 2) + fieldVarint(2, subtype) + fieldBytes(4, ByteArray(0))

    private fun parseDisplayItem(data: ByteArray): BandDisplay {
        var code: String? = null
        var name: String? = null
        var disabled = false
        var inMore = false
        val r = ProtoReader(data)
        while (r.hasRemaining()) {
            val f = r.nextField() ?: break
            when (f.number) {
                1 -> code = f.stringValue()
                2 -> name = f.stringValue()
                3 -> disabled = (f.varint ?: 0L) != 0L
                6 -> inMore = (f.varint ?: 0L) != 0L
            }
        }
        return BandDisplay(code = code, name = name, disabled = disabled, inMoreSection = inMore)
    }

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
        fun stringValue(): String? = bytes?.toString(Charsets.UTF_8)
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
