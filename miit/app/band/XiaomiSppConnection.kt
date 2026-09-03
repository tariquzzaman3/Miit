package com.miit.app.band

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.os.Build
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.bouncycastle.crypto.engines.AESEngine
import org.bouncycastle.crypto.modes.CCMBlockCipher
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter

/**
 * Bluetooth Classic RFCOMM/SPP transport used by Xiaomi Smart Band 9.
 *
 * The state machine mirrors the successful Gadgetbridge sequence:
 * RFCOMM -> SPPv1 version request -> SPPv2 session negotiation -> Xiaomi auth.
 * All device-specific values are supplied at runtime.
 */
class XiaomiSppConnection(
    private val device: BluetoothDevice,
    private val authKey: ByteArray,
    private val onEvent: (String) -> Unit,
    private val onState: (BandConnectionState) -> Unit,
    private val onData: (BandDataUpdate) -> Unit = {}
) {
    companion object {
        private val SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private val V1_MAGIC = byteArrayOf(0xBA.toByte(), 0xDC.toByte(), 0xFE.toByte())
        private val V2_MAGIC = byteArrayOf(0xA5.toByte(), 0xA5.toByte())
        private val V1_VERSION_REQUEST = byteArrayOf(
            0xBA.toByte(), 0xDC.toByte(), 0xFE.toByte(),
            0x00, 0xC0.toByte(),
            0x03, 0x00,
            0x00, 0x00, 0x00,
            0xEF.toByte()
        )
    }

    @Volatile private var running = false
    @Volatile private var authenticated = false
    private var socket: BluetoothSocket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null
    private var worker: Thread? = null
    private val writeLock = Any()
    private val txSequence = AtomicInteger(0)
    private val rxBuffer = ByteArrayOutputStream()
    private var parserVersion = 1
    private var auth: XiaomiSppAuthenticator? = null

    @SuppressLint("MissingPermission")
    fun connect() {
        if (running) {
            onEvent("Xiaomi SPP: connect ignored; already running")
            return
        }
        if (authKey.size != 16) {
            onEvent("Xiaomi SPP: auth key invalid; expected 16 bytes")
            onState(BandConnectionState.Error)
            return
        }
        worker = Thread({ runConnection() }, "Miit-Xiaomi-SPP").also { it.start() }
    }

    fun close() {
        running = false
        runCatching { socket?.close() }
        runCatching { worker?.interrupt() }
        worker = null
        socket = null
        input = null
        output = null
        authenticated = false
        auth = null
        rxBuffer.reset()
        parserVersion = 1
        txSequence.set(0)
    }

    @SuppressLint("MissingPermission")
    private fun runConnection() {
        running = true
        try {
            onState(BandConnectionState.Connecting)
            onEvent("Xiaomi SPP: opening RFCOMM socket uuid=$SPP_UUID")
            val s = device.createRfcommSocketToServiceRecord(SPP_UUID)
            socket = s
            s.connect()
            input = s.inputStream
            output = s.outputStream
            onEvent("Xiaomi SPP: RFCOMM connected")
            onState(BandConnectionState.Connecting)
            sendRaw(V1_VERSION_REQUEST)
            onEvent("Xiaomi SPP: sent SPPv1 version request")

            val buf = ByteArray(4096)
            while (running) {
                val n = input?.read(buf) ?: -1
                if (n < 0) break
                if (n == 0) continue
                rxBuffer.write(buf, 0, n)
                parseAvailable()
            }
        } catch (t: Throwable) {
            if (running) {
                onEvent("Xiaomi SPP connection error: ${t.javaClass.simpleName}: ${t.message ?: "unknown"}")
            }
        } finally {
            val wasAuthenticated = authenticated
            running = false
            runCatching { socket?.close() }
            socket = null
            input = null
            output = null
            auth = null
            if (wasAuthenticated) onState(BandConnectionState.Disconnected) else onState(BandConnectionState.Error)
        }
    }

    private fun parseAvailable() {
        while (true) {
            val data = rxBuffer.toByteArray()
            if (data.isEmpty()) return
            val magic = if (parserVersion == 1) V1_MAGIC else V2_MAGIC
            val offset = indexOfMagic(data, magic)
            if (offset < 0) {
                rxBuffer.reset()
                val keep = magic.size - 1
                if (data.size >= keep) rxBuffer.write(data, data.size - keep, keep) else rxBuffer.write(data)
                return
            }
            if (offset > 0) {
                consume(offset)
                continue
            }
            val progressed = if (parserVersion == 1) parseV1(data) else parseV2(data)
            if (!progressed) return
        }
    }

    private fun parseV1(data: ByteArray): Boolean {
        if (data.size < 11) return false
        val payloadHeaderLength = u16le(data, 5)
        if (payloadHeaderLength < 3) {
            onEvent("Xiaomi SPPv1: invalid payload length=$payloadHeaderLength")
            consume(1)
            return true
        }
        val payloadLength = payloadHeaderLength - 3
        val totalLength = 11 + payloadLength
        if (data.size < totalLength) return false
        if (data[totalLength - 1] != 0xEF.toByte()) {
            onEvent("Xiaomi SPPv1: invalid epilogue")
            consume(1)
            return true
        }
        val channel = data[3].toInt() and 0x0F
        val flags = data[4].toInt() and 0xFF
        val opcode = data[7].toInt() and 0xFF
        val serial = data[8].toInt() and 0xFF
        val dataType = data[9].toInt() and 0xFF
        val payload = data.copyOfRange(10, 10 + payloadLength)
        consume(totalLength)
        onEvent("Xiaomi SPPv1 packet: channel=$channel flags=0x${flags.toString(16)} opcode=$opcode serial=$serial type=$dataType payload=${payload.size}")
        if (channel == 0 && opcode == 1 && dataType == 0 && payload.size == 3) {
            val version = payload.joinToString("") { "%02X".format(it.toInt() and 0xFF) }
            onEvent("Xiaomi SPP: protocol version=$version")
            if (payload.contentEquals(byteArrayOf(0x02, 0x01, 0x09))) {
                parserVersion = 2
                onEvent("Xiaomi SPP: switching to SPPv2")
                sendSessionConfig()
            } else {
                onEvent("Xiaomi SPP: unsupported protocol version=$version")
                onState(BandConnectionState.Error)
                running = false
            }
        }
        return true
    }

    private fun sendSessionConfig() {
        val payload = byteArrayOf(
            0x01,
            0x01, 0x03, 0x00, 0x01, 0x00, 0x00,
            0x02, 0x02, 0x00, 0x00, 0xFC.toByte(),
            0x03, 0x02, 0x00, 0x20, 0x00,
            0x04, 0x02, 0x00, 0x10, 0x27
        )
        sendRaw(encodeV2(packetType = 2, sequence = 0, payload = payload))
        onEvent("Xiaomi SPP: sent SPPv2 session config")
    }

    private fun parseV2(data: ByteArray): Boolean {
        if (data.size < 8) return false
        val packetType = data[2].toInt() and 0x0F
        val sequence = data[3].toInt() and 0xFF
        val payloadLength = u16le(data, 4)
        val totalLength = 8 + payloadLength
        if (data.size < totalLength) return false
        val givenChecksum = u16le(data, 6)
        val payload = data.copyOfRange(8, totalLength)
        val calculatedChecksum = crc16(payload)
        if (givenChecksum != calculatedChecksum) {
            onEvent("Xiaomi SPPv2 checksum mismatch seq=$sequence given=0x${givenChecksum.toString(16)} calculated=0x${calculatedChecksum.toString(16)}")
            consume(1)
            return true
        }
        consume(totalLength)

        when (packetType) {
            1 -> onEvent("Xiaomi SPPv2 ACK sequence=$sequence")
            2 -> {
                val opcode = payload.firstOrNull()?.toInt()?.and(0xFF) ?: -1
                onEvent("Xiaomi SPPv2 session response opcode=$opcode")
                if (opcode == 2) startAuthentication()
            }
            3 -> {
                if (payload.size < 2) {
                    onEvent("Xiaomi SPPv2 data packet too short")
                    return true
                }
                val rawChannel = payload[0].toInt() and 0x0F
                val opcode = payload[1].toInt() and 0xFF
                val body = payload.copyOfRange(2, payload.size)
                onEvent("Xiaomi SPPv2 data: channel=$rawChannel opcode=$opcode bytes=${body.size}")
                // Gadgetbridge processes the command before acknowledging the received frame.
                if (rawChannel == 1) {
                    val commandBody = if (opcode == 2 && authenticated) auth?.decryptV2(body) ?: body else body
                    if (authenticated) {
                        handleRuntimeCommand(commandBody)
                    } else {
                        auth?.handleCommand(commandBody)
                    }
                }
                sendAck(sequence)
            }
            else -> onEvent("Xiaomi SPPv2 unsupported packet type=$packetType sequence=$sequence")
        }
        return true
    }

    private fun handleRuntimeCommand(commandBody: ByteArray) {
        val parsed = XiaomiCommandParser.parse(commandBody)
        if (parsed == null) {
            onEvent("Xiaomi command: protobuf parse failed bytes=${commandBody.size}")
            return
        }
        onEvent("Xiaomi command: type=${parsed.type} subtype=${parsed.subtype}")
        if (parsed.battery != null || parsed.batteryState != null || parsed.charging != null ||
            parsed.firmware != null || parsed.model != null || parsed.hardware != null ||
            parsed.serialNumber != null
        ) {
            onData(BandDataUpdate(
                batteryPercentage = parsed.battery,
                batteryState = parsed.batteryState,
                charging = parsed.charging,
                firmware = parsed.firmware,
                model = parsed.model,
                hardware = parsed.hardware,
                serialNumber = parsed.serialNumber
            ))
        }
        if (parsed.displays.isNotEmpty()) onData(BandDataUpdate(displays = parsed.displays))
    }

    private fun startAuthentication() {
        if (auth != null) return
        onState(BandConnectionState.Authenticating)
        auth = XiaomiSppAuthenticator(
            secretKey = authKey.copyOf(),
            onEvent = { event -> onEvent("Xiaomi auth: $event") },
            onResult = { success ->
                authenticated = success
                onState(if (success) BandConnectionState.Authenticated else BandConnectionState.Error)
                if (success) {
                    onEvent("Xiaomi auth: initialized")
                    requestInitialRuntimeData()
                }
            },
            sendPlain = { payload -> sendData(payload, encrypted = false) }
        )
        auth?.start()
    }

    private fun requestInitialRuntimeData() {
        val requests = listOf(
            "device info" to XiaomiCommandParser.systemGet(XiaomiCommandParser.SYSTEM_DEVICE_INFO),
            "device state" to XiaomiCommandParser.systemGet(XiaomiCommandParser.SYSTEM_DEVICE_STATE_GET),
            "battery" to XiaomiCommandParser.systemGet(XiaomiCommandParser.SYSTEM_BATTERY),
            "display items" to XiaomiCommandParser.systemGet(XiaomiCommandParser.SYSTEM_DISPLAY_ITEMS_GET),
            "widgets" to XiaomiCommandParser.systemGet(XiaomiCommandParser.SYSTEM_WIDGET_SCREENS_GET),
            "widget parts" to XiaomiCommandParser.systemGet(XiaomiCommandParser.SYSTEM_WIDGET_PARTS_GET),
            "watchface list" to XiaomiCommandParser.watchfaceListGet()
        )
        requests.forEach { (name, payload) -> sendProtoCommand(name, payload) }
    }

    fun sendProtoCommand(name: String, payload: ByteArray): Boolean {
        if (!running || !authenticated || auth == null) {
            onEvent("Xiaomi SPP: send '$name' rejected; not authenticated")
            return false
        }
        val encryptedPayload = auth?.encryptV2(payload) ?: return false
        val raw = byteArrayOf(1, 2) + encryptedPayload
        val sequence = txSequence.getAndIncrement() and 0xFF
        sendRaw(encodeV2(3, sequence, raw))
        onEvent("Xiaomi SPPv2: sent encrypted command '$name' sequence=$sequence bytes=${payload.size}")
        return true
    }

    private fun sendAck(sequence: Int) {
        sendRaw(encodeV2(packetType = 1, sequence = sequence, payload = ByteArray(0)))
        onEvent("Xiaomi SPPv2: sent ACK sequence=$sequence")
    }

    private fun sendData(payload: ByteArray, encrypted: Boolean) {
        val opcode = if (encrypted) 2 else 1
        val raw = ByteArray(2 + payload.size)
        raw[0] = 1
        raw[1] = opcode.toByte()
        payload.copyInto(raw, 2)
        val sequence = txSequence.getAndIncrement() and 0xFF
        sendRaw(encodeV2(packetType = 3, sequence = sequence, payload = raw))
        onEvent("Xiaomi SPPv2: sent data sequence=$sequence opcode=$opcode bytes=${payload.size}")
    }

    private fun encodeV2(packetType: Int, sequence: Int, payload: ByteArray): ByteArray {
        val out = ByteArray(8 + payload.size)
        out[0] = 0xA5.toByte()
        out[1] = 0xA5.toByte()
        out[2] = (packetType and 0x0F).toByte()
        out[3] = (sequence and 0xFF).toByte()
        putU16le(out, 4, payload.size)
        putU16le(out, 6, crc16(payload))
        payload.copyInto(out, 8)
        return out
    }

    private fun sendRaw(bytes: ByteArray) {
        synchronized(writeLock) {
            val stream = output ?: throw IllegalStateException("RFCOMM output stream is not ready")
            stream.write(bytes)
            stream.flush()
        }
    }

    private fun consume(count: Int) {
        if (count <= 0) return
        val current = rxBuffer.toByteArray()
        rxBuffer.reset()
        if (count < current.size) rxBuffer.write(current, count, current.size - count)
    }

    private fun indexOfMagic(data: ByteArray, magic: ByteArray): Int {
        if (data.size < magic.size) return -1
        for (i in 0..(data.size - magic.size)) {
            if (data.copyOfRange(i, i + magic.size).contentEquals(magic)) return i
        }
        return -1
    }

    private fun u16le(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)

    private fun putU16le(data: ByteArray, offset: Int, value: Int) {
        data[offset] = value.toByte()
        data[offset + 1] = (value ushr 8).toByte()
    }

    private fun crc16(payload: ByteArray): Int {
        var crc = 0
        for (b in payload) {
            for (bit in 0 until 8) {
                crc = crc shl 1
                if ((((crc ushr 16) and 1) xor ((b.toInt() ushr bit) and 1)) == 1) crc = crc xor 0x8005
            }
        }
        return Integer.reverse(crc) ushr 16
    }
}

/** Xiaomi authentication using the same derivation and AES-CCM parameters as Gadgetbridge. */
private class XiaomiSppAuthenticator(
    private val secretKey: ByteArray,
    private val onEvent: (String) -> Unit,
    private val onResult: (Boolean) -> Unit,
    private val sendPlain: (ByteArray) -> Unit
) {
    private val phoneNonce = ByteArray(16)
    private var stage = 0
    private var encryptionKey = ByteArray(16)
    private var decryptionKey = ByteArray(16)
    private var encryptionNonce = ByteArray(4)
    private var decryptionNonce = ByteArray(4)

    fun start() {
        SecureRandom().nextBytes(phoneNonce)
        stage = 1
        val command = XiaomiAuthProto.commandNonce(phoneNonce)
        onEvent("auth_step_1 bytes=${command.size}")
        sendPlain(command)
    }

    fun handleCommand(data: ByteArray): Boolean {
        val parsed = XiaomiAuthProto.parseCommand(data) ?: run {
            onEvent("auth_command_unparsed bytes=${data.size}")
            return false
        }
        onEvent("response subtype=${parsed.subtype}")
        if (parsed.type != 1) return false

        when (parsed.subtype) {
            26 -> {
                val watch = parsed.watch ?: return false
                if (stage != 1 || watch.nonce.size != 16 || watch.hmac.size != 32) return false
                val derived = derive(secretKey, phoneNonce, watch.nonce)
                decryptionKey = derived.copyOfRange(0, 16)
                encryptionKey = derived.copyOfRange(16, 32)
                decryptionNonce = derived.copyOfRange(32, 36)
                encryptionNonce = derived.copyOfRange(36, 40)

                val expectedHmac = hmac(decryptionKey, watch.nonce + phoneNonce)
                if (!expectedHmac.contentEquals(watch.hmac)) {
                    onEvent("watch_hmac_mismatch")
                    onResult(false)
                    return true
                }

                stage = 2
                val encryptedNonces = hmac(encryptionKey, phoneNonce + watch.nonce)
                val deviceInfo = XiaomiAuthProto.authDeviceInfo(
                    Build.VERSION.SDK_INT,
                    Build.MODEL,
                    java.util.Locale.getDefault().getLanguage()
                )
                val encryptedDeviceInfo = ccmEncrypt(
                    encryptionKey,
                    packetNonce(encryptionNonce, 0),
                    deviceInfo
                )
                val command = XiaomiAuthProto.commandAuth(encryptedNonces, encryptedDeviceInfo)
                onEvent("auth_step_2 bytes=${command.size}")
                sendPlain(command)
                return true
            }
            27 -> {
                if (stage == 2) {
                    stage = 3
                    onEvent("authenticated")
                    onResult(true)
                    return true
                }
                onResult(false)
                return true
            }
        }
        return false
    }

    fun encryptV2(message: ByteArray): ByteArray =
        ctrCrypt(Cipher.ENCRYPT_MODE, encryptionKey, encryptionKey, message)

    fun decryptV2(ciphertext: ByteArray): ByteArray =
        ctrCrypt(Cipher.DECRYPT_MODE, decryptionKey, decryptionKey, ciphertext)

    private fun ctrCrypt(op: Int, key: ByteArray, iv: ByteArray, message: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(op, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(message)
    }

    private fun derive(secret: ByteArray, phone: ByteArray, watch: ByteArray): ByteArray {
        val initial = hmac(phone + watch, secret)
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(initial, "HmacSHA256"))
        val label = "miwear-auth".toByteArray(Charsets.UTF_8)
        val out = ByteArray(64)
        var previous = ByteArray(0)
        var counter = 1
        var offset = 0
        while (offset < out.size) {
            mac.update(previous)
            mac.update(label)
            mac.update(counter.toByte())
            previous = mac.doFinal()
            val copy = minOf(previous.size, out.size - offset)
            previous.copyInto(out, offset, 0, copy)
            offset += copy
            counter++
        }
        return out
    }

    private fun hmac(key: ByteArray, value: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(value)
    }

    private fun packetNonce(nonce4: ByteArray, sequence: Int): ByteArray =
        ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).put(nonce4).putInt(0).putInt(sequence).array()

    private fun ccmEncrypt(key: ByteArray, nonce: ByteArray, payload: ByteArray): ByteArray {
        val cipher = CCMBlockCipher(AESEngine())
        cipher.init(true, AEADParameters(KeyParameter(key), 32, nonce, null))
        val out = ByteArray(cipher.getOutputSize(payload.size))
        val count = cipher.processBytes(payload, 0, payload.size, out, 0)
        cipher.doFinal(out, count)
        return out
    }
}

private object XiaomiAuthProto {
    data class WatchNonce(val nonce: ByteArray, val hmac: ByteArray)
    data class ParsedCommand(val type: Int, val subtype: Int, val status: Int, val authStatus: Int, val watch: WatchNonce?)

    fun commandNonce(nonce: ByteArray): ByteArray =
        command(26, fieldBytes(3, fieldBytes(30, fieldBytes(1, nonce))))

    fun commandAuth(encryptedNonces: ByteArray, encryptedDeviceInfo: ByteArray): ByteArray =
        command(27, fieldBytes(3, fieldBytes(32, fieldBytes(1, encryptedNonces) + fieldBytes(2, encryptedDeviceInfo))))

    fun authDeviceInfo(api: Int, model: String, language: String): ByteArray {
        val fixedApi = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(api.toFloat()).array()
        val region = language.take(2).uppercase(java.util.Locale.ROOT)
        return fieldVarint(1, 0) + fieldFixed32(2, fixedApi) + fieldString(3, model) + fieldVarint(4, 224) + fieldString(5, region)
    }

    fun parseCommand(data: ByteArray): ParsedCommand? {
        var position = 0
        var type = 0
        var subtype = 0
        var status = 0
        var authStatus = 0
        var watch: WatchNonce? = null
        while (position < data.size) {
            val tag = readVarint(data, position) ?: return null
            position = tag.next
            val field = tag.value ushr 3
            val wire = tag.value and 7
            when (field) {
                1, 2 -> {
                    if (wire != 0) return null
                    val value = readVarint(data, position) ?: return null
                    position = value.next
                    if (field == 1) type = value.value else subtype = value.value
                }
                3 -> {
                    if (wire != 2) return null
                    val nested = readBytes(data, position) ?: return null
                    position = nested.next
                    val auth = parseAuth(nested.bytes)
                    authStatus = auth.status
                    watch = auth.watch
                }
                else -> position = skipField(data, position, wire) ?: return null
            }
        }
        return ParsedCommand(type, subtype, status, authStatus, watch)
    }

    private data class AuthParsed(val status: Int, val watch: WatchNonce?)

    private fun parseAuth(data: ByteArray): AuthParsed {
        var position = 0
        var status = 0
        var watch: WatchNonce? = null
        while (position < data.size) {
            val tag = readVarint(data, position) ?: break
            position = tag.next
            val field = tag.value ushr 3
            val wire = tag.value and 7
            when {
                field == 1 && wire == 0 -> {
                    val value = readVarint(data, position) ?: break
                    position = value.next
                    status = value.value
                }
                field == 31 && wire == 2 -> {
                    val nested = readBytes(data, position) ?: break
                    position = nested.next
                    watch = parseWatchNonce(nested.bytes)
                }
                field == 37 && wire == 2 -> {
                    val nested = readBytes(data, position) ?: break
                    position = nested.next
                    val nestedTag = readVarint(nested.bytes, 0)
                    if (nestedTag != null && (nestedTag.value ushr 3) == 1 && (nestedTag.value and 7) == 0) {
                        val nestedValue = readVarint(nested.bytes, nestedTag.next)
                        if (nestedValue != null) status = nestedValue.value
                    }
                }
                else -> position = skipField(data, position, wire) ?: break
            }
        }
        return AuthParsed(status, watch)
    }

    private fun parseWatchNonce(data: ByteArray): WatchNonce {
        var position = 0
        var nonce = ByteArray(0)
        var hmac = ByteArray(0)
        while (position < data.size) {
            val tag = readVarint(data, position) ?: break
            position = tag.next
            val field = tag.value ushr 3
            val wire = tag.value and 7
            if (wire != 2) {
                position = skipField(data, position, wire) ?: break
                continue
            }
            val bytes = readBytes(data, position) ?: break
            position = bytes.next
            when (field) {
                1 -> nonce = bytes.bytes
                2 -> hmac = bytes.bytes
            }
        }
        return WatchNonce(nonce, hmac)
    }

    private fun command(subtype: Int, auth: ByteArray): ByteArray = fieldVarint(1, 1) + fieldVarint(2, subtype) + auth
    private fun fieldBytes(number: Int, value: ByteArray): ByteArray = varintTag(number, 2) + varintValue(value.size) + value
    private fun fieldString(number: Int, value: String): ByteArray = fieldBytes(number, value.toByteArray(Charsets.UTF_8))
    private fun fieldVarint(number: Int, value: Int): ByteArray = varintTag(number, 0) + varintValue(value)
    private fun fieldFixed32(number: Int, value: ByteArray): ByteArray = varintTag(number, 5) + value
    private fun varintTag(number: Int, wireType: Int): ByteArray = varintValue((number shl 3) or wireType)
    private fun varintValue(input: Int): ByteArray {
        var value = input
        val out = ArrayList<Byte>()
        do {
            var b = value and 0x7F
            value = value ushr 7
            if (value != 0) b = b or 0x80
            out.add(b.toByte())
        } while (value != 0)
        return out.toByteArray()
    }
    private data class ReadVarint(val value: Int, val next: Int)
    private data class ReadBytes(val bytes: ByteArray, val next: Int)
    private fun readVarint(data: ByteArray, start: Int): ReadVarint? {
        var position = start
        var value = 0
        var shift = 0
        while (position < data.size && shift < 32) {
            val b = data[position++].toInt() and 0xFF
            value = value or ((b and 0x7F) shl shift)
            if ((b and 0x80) == 0) return ReadVarint(value, position)
            shift += 7
        }
        return null
    }
    private fun readBytes(data: ByteArray, start: Int): ReadBytes? {
        val length = readVarint(data, start) ?: return null
        if (length.value < 0 || length.next + length.value > data.size) return null
        return ReadBytes(data.copyOfRange(length.next, length.next + length.value), length.next + length.value)
    }
    private fun skipField(data: ByteArray, start: Int, wireType: Int): Int? = when (wireType) {
        0 -> readVarint(data, start)?.next
        1 -> if (start + 8 <= data.size) start + 8 else null
        2 -> readBytes(data, start)?.next
        5 -> if (start + 4 <= data.size) start + 4 else null
        else -> null
    }
}
