package com.miit.app.band

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.bouncycastle.crypto.engines.AESEngine
import org.bouncycastle.crypto.modes.CCMBlockCipher
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter

/** Bluetooth Classic RFCOMM/SPP transport used by Xiaomi Smart Band 9/10. */
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
            0xBA.toByte(), 0xDC.toByte(), 0xFE.toByte(), 0x00, 0xC0.toByte(),
            0x03, 0x00, 0x00, 0x00, 0x00, 0xEF.toByte()
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
    private val requestExecutor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "Miit-Xiaomi-Requests").apply { isDaemon = true }
    }
    private var parserVersion = 1
    private var auth: XiaomiSppAuthenticator? = null

    @SuppressLint("MissingPermission")
    fun connect() {
        if (running) return
        if (authKey.size != 16) {
            onEvent("Xiaomi SPP: auth key invalid; expected 16 bytes")
            onState(BandConnectionState.Error)
            return
        }
        worker = Thread({ runConnection() }, "Miit-Xiaomi-SPP").also { it.start() }
    }

    fun close() {
        running = false
        requestExecutor.shutdownNow()
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
            if (running) onEvent("Xiaomi SPP connection error: ${t.javaClass.simpleName}: ${t.message ?: "unknown"}")
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
            if (offset > 0) { consume(offset); continue }
            val progressed = if (parserVersion == 1) parseV1(data) else parseV2(data)
            if (!progressed) return
        }
    }

    private fun parseV1(data: ByteArray): Boolean {
        if (data.size < 11) return false
        val payloadHeaderLength = u16le(data, 5)
        if (payloadHeaderLength < 3) { consume(1); return true }
        val payloadLength = payloadHeaderLength - 3
        val totalLength = 11 + payloadLength
        if (data.size < totalLength) return false
        if (data[totalLength - 1] != 0xEF.toByte()) { consume(1); return true }
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
            0x01, 0x01, 0x03, 0x00, 0x01, 0x00, 0x00,
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
        if (givenChecksum != calculatedChecksum) { consume(1); return true }
        consume(totalLength)
        when (packetType) {
            1 -> onEvent("Xiaomi SPPv2 ACK sequence=$sequence")
            2 -> {
                val opcode = payload.firstOrNull()?.toInt()?.and(0xFF) ?: -1
                onEvent("Xiaomi SPPv2 session response opcode=$opcode")
                if (opcode == 2) startAuthentication()
            }
            3 -> {
                if (payload.size < 2) return true
                val rawChannel = payload[0].toInt() and 0x0F
                val opcode = payload[1].toInt() and 0xFF
                val body = payload.copyOfRange(2, payload.size)
                if (rawChannel == 1) {
                    val commandBody = if (opcode == 2 && authenticated) auth?.decryptV2(body) ?: body else body
                    if (authenticated) handleRuntimeCommand(commandBody) else auth?.handleCommand(commandBody)
                }
                sendAck(sequence)
            }
        }
        return true
    }

    private fun handleRuntimeCommand(commandBody: ByteArray) {
        val parsed = XiaomiCommandParser.parse(commandBody)
        if (parsed == null) { onEvent("Xiaomi command: protobuf parse failed bytes=${commandBody.size}"); return }
        onEvent("Xiaomi command: type=${parsed.type} subtype=${parsed.subtype}")
        if (parsed.type == XiaomiCommandParser.TYPE_WATCHFACE) {
            onEvent("Xiaomi inventory: received watchface metadata count=${parsed.watchfaces.size}")
            if (parsed.watchfaces.isNotEmpty()) onData(BandDataUpdate(watchfaces = parsed.watchfaces))
        }
        if (parsed.type == XiaomiCommandParser.TYPE_SYSTEM && parsed.screenItems.isNotEmpty()) {
            onEvent("Xiaomi inventory: received band menu items count=${parsed.screenItems.size}")
        }
        if (parsed.battery != null || parsed.batteryState != null || parsed.charging != null ||
            parsed.firmware != null || parsed.model != null || parsed.hardware != null || parsed.serialNumber != null
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
            }
        )
        auth?.begin()
    }

    private fun requestInitialRuntimeData() {
        requestExecutor.execute {
            runCatching {
                sendProtoCommand("device info", XiaomiCommandParser.systemGet(XiaomiCommandParser.SYSTEM_DEVICE_INFO))
                TimeUnit.MILLISECONDS.sleep(250)
                sendProtoCommand("device state", XiaomiCommandParser.systemGet(XiaomiCommandParser.SYSTEM_DEVICE_STATE_GET))
                TimeUnit.MILLISECONDS.sleep(250)
                sendProtoCommand("battery", XiaomiCommandParser.systemGet(XiaomiCommandParser.SYSTEM_BATTERY))
                TimeUnit.MILLISECONDS.sleep(250)
                sendProtoCommand("display items", XiaomiCommandParser.systemGet(XiaomiCommandParser.SYSTEM_DISPLAY_ITEMS_GET))
                TimeUnit.MILLISECONDS.sleep(250)
                sendProtoCommand("widget screens", XiaomiCommandParser.systemGet(XiaomiCommandParser.SYSTEM_WIDGET_SCREENS_GET))
                TimeUnit.MILLISECONDS.sleep(250)
                sendProtoCommand("widget parts", XiaomiCommandParser.systemGet(XiaomiCommandParser.SYSTEM_WIDGET_PARTS_GET))
                TimeUnit.MILLISECONDS.sleep(250)
                sendProtoCommand("watchface list", XiaomiCommandParser.watchfaceListGet())
            }.onFailure { onEvent("Xiaomi runtime request error: ${it.javaClass.simpleName}: ${it.message ?: "unknown"}") }
        }
    }

    private fun sendProtoCommand(name: String, payload: ByteArray) {
        val encrypted = auth?.encryptV2(payload) ?: run { onEvent("Xiaomi runtime: cannot send $name; auth session unavailable"); return }
        val seq = txSequence.getAndIncrement() and 0xFF
        val body = byteArrayOf(0x01, 0x02) + encrypted
        sendRaw(encodeV2(packetType = 3, sequence = seq, payload = body))
        onEvent("Xiaomi runtime: requested $name seq=$seq")
    }

    private fun sendAck(sequence: Int) {
        sendRaw(encodeV2(packetType = 1, sequence = sequence, payload = byteArrayOf()))
    }

    private fun sendRaw(data: ByteArray) {
        synchronized(writeLock) { output?.write(data); output?.flush() }
    }

    private fun consume(count: Int) {
        val data = rxBuffer.toByteArray()
        rxBuffer.reset()
        if (count < data.size) rxBuffer.write(data, count, data.size - count)
    }

    private fun indexOfMagic(data: ByteArray, magic: ByteArray): Int {
        if (data.size < magic.size) return -1
        outer@ for (i in 0..data.size - magic.size) {
            for (j in magic.indices) if (data[i + j] != magic[j]) continue@outer
            return i
        }
        return -1
    }

    private fun u16le(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)

    private fun crc16(data: ByteArray): Int {
        var crc = 0xFFFF
        for (b in data) {
            crc = crc xor (b.toInt() and 0xFF)
            repeat(8) { crc = if ((crc and 1) != 0) (crc ushr 1) xor 0xA001 else crc ushr 1 }
        }
        return crc and 0xFFFF
    }

    private fun encodeV2(packetType: Int, sequence: Int, payload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(0xA5); out.write(0xA5); out.write(packetType and 0x0F); out.write(sequence and 0xFF)
        out.write(payload.size and 0xFF); out.write((payload.size ushr 8) and 0xFF)
        val crc = crc16(payload)
        out.write(crc and 0xFF); out.write((crc ushr 8) and 0xFF)
        out.write(payload)
        return out.toByteArray()
    }
}
