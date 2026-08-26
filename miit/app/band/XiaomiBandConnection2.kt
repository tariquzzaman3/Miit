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
import java.util.Locale
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

/** Stable Xiaomi Band 9 SPP transport plus post-auth device initialization. */
class XiaomiBandConnection2(
    private val device: BluetoothDevice,
    private val authKey: ByteArray,
    private val onEvent: (String) -> Unit,
    private val onState: (BandConnectionState) -> Unit,
    private val onDataUpdate: (BandDataUpdate) -> Unit
) {
    companion object {
        private val SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private val V1_REQUEST = byteArrayOf(
            0xBA.toByte(), 0xDC.toByte(), 0xFE.toByte(), 0x00,
            0xC0.toByte(), 0x03, 0x00, 0x00, 0x00, 0x00, 0xEF.toByte()
        )
        private const val ACK = 1
        private const val SESSION = 2
        private const val DATA = 3
    }

    @Volatile private var running = false
    @Volatile private var authenticated = false
    private var socket: BluetoothSocket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null
    private var thread: Thread? = null
    private val writeLock = Any()
    private val txSequence = AtomicInteger(0)
    private val rxBuffer = ByteArrayOutputStream()
    private var v2 = false
    private var auth: XiaomiAuthEngine? = null

    @SuppressLint("MissingPermission")
    fun connect() {
        if (running) return
        if (authKey.size != 16) {
            onEvent("Xiaomi SPP: invalid auth key length")
            onState(BandConnectionState.Error)
            return
        }
        thread = Thread({ runConnection() }, "Miit-Xiaomi-Band2").also { it.start() }
    }

    fun close() {
        running = false
        runCatching { socket?.close() }
        runCatching { thread?.interrupt() }
        socket = null
        input = null
        output = null
        thread = null
        auth = null
        authenticated = false
        rxBuffer.reset()
        txSequence.set(0)
        v2 = false
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
            write(V1_REQUEST)
            onEvent("Xiaomi SPP: sent SPPv1 version request")

            val temp = ByteArray(4096)
            while (running) {
                val count = input?.read(temp) ?: -1
                if (count < 0) break
                if (count > 0) {
                    rxBuffer.write(temp, 0, count)
                    parseLoop()
                }
            }
        } catch (t: Throwable) {
            if (running) onEvent("Xiaomi SPP error: ${t.javaClass.simpleName}: ${t.message ?: "unknown"}")
        } finally {
            val success = authenticated
            running = false
            runCatching { socket?.close() }
            socket = null
            input = null
            output = null
            auth = null
            onState(if (success) BandConnectionState.Disconnected else BandConnectionState.Error)
        }
    }

    private fun parseLoop() {
        while (true) {
            val data = rxBuffer.toByteArray()
            if (data.isEmpty()) return
            val magic = if (v2) byteArrayOf(0xA5.toByte(), 0xA5.toByte()) else byteArrayOf(0xBA.toByte(), 0xDC.toByte(), 0xFE.toByte())
            val offset = findMagic(data, magic)
            if (offset < 0) {
                rxBuffer.reset()
                val keep = magic.size - 1
                if (data.size >= keep) rxBuffer.write(data, data.size - keep, keep) else rxBuffer.write(data)
                return
            }
            if (offset > 0) { consume(offset); continue }
            val complete = if (v2) parseV2(data) else parseV1(data)
            if (!complete) return
        }
    }

    private fun parseV1(data: ByteArray): Boolean {
        if (data.size < 11) return false
        val length = u16(data, 5)
        if (length < 3) { consume(1); return true }
        val payloadLength = length - 3
        val total = 11 + payloadLength
        if (data.size < total) return false
        if (data[total - 1] != 0xEF.toByte()) { consume(1); return true }
        val channel = data[3].toInt() and 0x0F
        val opcode = data[7].toInt() and 0xFF
        val payloadType = data[9].toInt() and 0xFF
        val payload = data.copyOfRange(10, 10 + payloadLength)
        consume(total)
        onEvent("Xiaomi SPPv1 packet: channel=$channel opcode=$opcode type=$payloadType payload=${payload.size}")
        if (channel == 0 && opcode == 1 && payloadType == 0 && payload.size == 3 && payload.contentEquals(byteArrayOf(2, 1, 9))) {
            onEvent("Xiaomi SPP: protocol version=020109")
            v2 = true
            sendSessionConfig()
        }
        return true
    }

    private fun sendSessionConfig() {
        val payload = byteArrayOf(
            1,
            1, 3, 0, 1, 0, 0,
            2, 2, 0, 0, 0xFC.toByte(),
            3, 2, 0x20, 0,
            4, 2, 0x10, 0x27
        )
        write(encodeV2(SESSION, 0, payload))
        onEvent("Xiaomi SPP: sent SPPv2 session config")
    }

    private fun parseV2(data: ByteArray): Boolean {
        if (data.size < 8) return false
        val packetType = data[2].toInt() and 0x0F
        val sequence = data[3].toInt() and 0xFF
        val payloadLength = u16(data, 4)
        val total = 8 + payloadLength
        if (data.size < total) return false
        val payload = data.copyOfRange(8, total)
        if (u16(data, 6) != crc16(payload)) { consume(1); return true }
        consume(total)
        when (packetType) {
            ACK -> onEvent("Xiaomi SPPv2 ACK sequence=$sequence")
            SESSION -> {
                val opcode = payload.firstOrNull()?.toInt()?.and(0xFF) ?: -1
                onEvent("Xiaomi SPPv2 session response opcode=$opcode")
                if (opcode == 2) beginAuth()
            }
            DATA -> parseData(sequence, payload)
        }
        return true
    }

    private fun parseData(sequence: Int, payload: ByteArray) {
        if (payload.size < 2) { sendAck(sequence); return }
        val channel = payload[0].toInt() and 0x0F
        val opcode = payload[1].toInt() and 0xFF
        val encoded = payload.copyOfRange(2, payload.size)
        val plain = if (opcode == 2) {
            try { auth?.decryptV2(encoded) } catch (t: Throwable) {
                onEvent("Xiaomi SPPv2 decrypt failed: ${t.javaClass.simpleName}")
                null
            }
        } else encoded
        if (plain == null) { sendAck(sequence); return }
        onEvent("Xiaomi SPPv2 data: channel=$channel opcode=$opcode bytes=${plain.size}")
        if (channel == 1) {
            if (authenticated) handleSystemResponse(plain) else auth?.handle(plain)
        }
        sendAck(sequence)
    }

    private fun beginAuth() {
        if (auth != null) return
        onState(BandConnectionState.Authenticating)
        auth = XiaomiAuthEngine(
            secretKey = authKey.copyOf(),
            onEvent = { onEvent("Xiaomi auth: $it") },
            sendPlain = { sendData(it, encrypted = false) },
            onResult = { ok ->
                authenticated = ok
                if (!ok) {
                    onState(BandConnectionState.Error)
                } else {
                    onState(BandConnectionState.Authenticated)
                    onEvent("Xiaomi auth: authenticated")
                    onEvent("Xiaomi auth: initialized")
                    initializeSystem()
                }
            }
        )
        auth?.start()
    }

    private fun initializeSystem() {
        // Gadgetbridge XiaomiSystemService initializes device services after auth.
        sendSystemGet(2)  // device info
        sendSystemGet(1)  // battery
        sendSystemGet(29) // display items
        onEvent("Xiaomi init: requested device info, battery and display items")
    }

    private fun sendSystemGet(subtype: Int) {
        val command = Proto.systemGet(subtype)
        sendData(command, encrypted = true)
        onEvent("Xiaomi init: sent system command subtype=$subtype")
    }

    private fun handleSystemResponse(data: ByteArray) {
        val parsed = XiaomiCommandParser.parse(data) ?: run {
            onEvent("Xiaomi init: unable to parse protobuf response bytes=${data.size}")
            return
        }
        if (parsed.type != 2) return
        parsed.battery?.let {
            onEvent("Xiaomi init: battery=$it%")
            onDataUpdate(BandDataUpdate(batteryPercentage = it))
        }
        if (parsed.firmware != null || parsed.model != null) {
            onEvent("Xiaomi init: device information received")
            onDataUpdate(BandDataUpdate(firmware = parsed.firmware, model = parsed.model))
        }
        if (parsed.displays.isNotEmpty()) {
            onEvent("Xiaomi init: display items=${parsed.displays.size}")
            onDataUpdate(BandDataUpdate(displays = parsed.displays))
        }
    }

    private fun sendAck(sequence: Int) { write(encodeV2(ACK, sequence, ByteArray(0))); onEvent("Xiaomi SPPv2: sent ACK sequence=$sequence") }

    private fun sendData(payload: ByteArray, encrypted: Boolean) {
        val body = if (encrypted) auth?.encryptV2(payload) ?: throw IllegalStateException("Xiaomi encryption not initialized") else payload
        val raw = byteArrayOf(1, if (encrypted) 2 else 1) + body
        val sequence = txSequence.getAndIncrement() and 0xFF
        write(encodeV2(DATA, sequence, raw))
        onEvent("Xiaomi SPPv2: sent data sequence=$sequence opcode=${if (encrypted) 2 else 1} bytes=${payload.size}")
    }

    private fun write(bytes: ByteArray) {
        synchronized(writeLock) {
            val stream = output ?: throw IllegalStateException("RFCOMM output stream not ready")
            stream.write(bytes)
            stream.flush()
        }
    }

    private fun encodeV2(type: Int, sequence: Int, payload: ByteArray): ByteArray {
        val out = ByteArray(8 + payload.size)
        out[0] = 0xA5.toByte(); out[1] = 0xA5.toByte(); out[2] = (type and 0x0F).toByte(); out[3] = sequence.toByte()
        putU16(out, 4, payload.size); putU16(out, 6, crc16(payload)); payload.copyInto(out, 8)
        return out
    }

    private fun consume(count: Int) {
        val data = rxBuffer.toByteArray(); rxBuffer.reset(); if (count < data.size) rxBuffer.write(data, count, data.size - count)
    }

    private fun findMagic(data: ByteArray, magic: ByteArray): Int {
        if (data.size < magic.size) return -1
        for (i in 0..(data.size - magic.size)) if (data.copyOfRange(i, i + magic.size).contentEquals(magic)) return i
        return -1
    }

    private fun u16(data: ByteArray, offset: Int): Int = (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)
    private fun putU16(data: ByteArray, offset: Int, value: Int) { data[offset] = value.toByte(); data[offset + 1] = (value ushr 8).toByte() }
    private fun crc16(payload: ByteArray): Int { var crc = 0; for (b in payload) for (bit in 0 until 8) { crc = crc shl 1; if ((((crc ushr 16) and 1) xor ((b.toInt() ushr bit) and 1)) == 1) crc = crc xor 0x8005 }; return Integer.reverse(crc) ushr 16 }
}

private class XiaomiAuthEngine(
    private val secretKey: ByteArray,
    private val onEvent: (String) -> Unit,
    private val sendPlain: (ByteArray) -> Unit,
    private val onResult: (Boolean) -> Unit
) {
    private val phoneNonce = ByteArray(16)
    private var stage = 0
    private var encryptionKey = ByteArray(16)
    private var decryptionKey = ByteArray(16)

    fun start() {
        SecureRandom().nextBytes(phoneNonce)
        stage = 1
        sendPlain(Proto.authNonce(phoneNonce))
        onEvent("auth_step_1 bytes=27")
    }

    fun handle(data: ByteArray): Boolean {
        val auth = Proto.parseAuth(data) ?: run { onEvent("auth_command_unparsed bytes=${data.size}"); return false }
        onEvent("response subtype=${auth.subtype}")
        when (auth.subtype) {
            26 -> {
                val watch = auth.watch ?: return false
                if (stage != 1 || watch.nonce.size != 16 || watch.hmac.size != 32) return false
                val derived = derive(secretKey, phoneNonce, watch.nonce)
                System.arraycopy(derived, 0, decryptionKey, 0, 16)
                System.arraycopy(derived, 16, encryptionKey, 0, 16)
                val expected = hmac(decryptionKey, watch.nonce + phoneNonce)
                if (!expected.contentEquals(watch.hmac)) { onEvent("watch_hmac_mismatch"); onResult(false); return true }
                stage = 2
                val encryptedNonces = hmac(encryptionKey, phoneNonce + watch.nonce)
                val info = Proto.authDeviceInfo(Build.VERSION.SDK_INT, Build.MODEL, Locale.getDefault().language)
                val encryptedInfo = ccm(encryptionKey, authNonce(encryptionKey), info)
                sendPlain(Proto.authStep2(encryptedNonces, encryptedInfo))
                onEvent("auth_step_2 bytes=76")
                return true
            }
            27 -> { if (stage == 2) { stage = 3; onResult(true) } else onResult(false); return true }
        }
        return false
    }

    fun encryptV2(message: ByteArray): ByteArray = ctr(Cipher.ENCRYPT_MODE, encryptionKey, encryptionKey, message)
    fun decryptV2(ciphertext: ByteArray): ByteArray = ctr(Cipher.DECRYPT_MODE, decryptionKey, decryptionKey, ciphertext)

    private fun authNonce(key: ByteArray): ByteArray = key
    private fun ctr(mode: Int, key: ByteArray, iv: ByteArray, input: ByteArray): ByteArray { val cipher = Cipher.getInstance("AES/CTR/NoPadding"); cipher.init(mode, SecretKeySpec(key, "AES"), IvParameterSpec(iv)); return cipher.doFinal(input) }
    private fun hmac(key: ByteArray, input: ByteArray): ByteArray { val mac = Mac.getInstance("HmacSHA256"); mac.init(SecretKeySpec(key, "HmacSHA256")); return mac.doFinal(input) }
    private fun derive(secret: ByteArray, phone: ByteArray, watch: ByteArray): ByteArray { val mac = Mac.getInstance("HmacSHA256"); mac.init(SecretKeySpec(phone + watch, "HmacSHA256")); val base = mac.doFinal(secret); mac.init(SecretKeySpec(base, "HmacSHA256")); val label = "miwear-auth".toByteArray(); val out = ByteArray(64); var prev = ByteArray(0); var c = 1; var pos = 0; while (pos < 64) { mac.update(prev); mac.update(label); mac.update(c.toByte()); prev = mac.doFinal(); val n = minOf(prev.size, 64 - pos); prev.copyInto(out, pos, 0, n); pos += n; c++ }; return out }
    private fun ccm(key: ByteArray, nonce4: ByteArray, payload: ByteArray): ByteArray { val nonce = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).put(nonce4).putInt(0).putInt(0).array(); val cipher = CCMBlockCipher(AESEngine()); cipher.init(true, AEADParameters(KeyParameter(key), 32, nonce, null)); val out = ByteArray(cipher.getOutputSize(payload.size)); val n = cipher.processBytes(payload,0,payload.size,out,0); cipher.doFinal(out,n); return out }
}

private object Proto {
    data class AuthResult(val subtype: Int, val watch: WatchNonce?)
    data class WatchNonce(val nonce: ByteArray, val hmac: ByteArray)

    fun systemGet(subtype: Int): ByteArray = fieldVarint(1, 2) + fieldVarint(2, subtype) + fieldBytes(4, ByteArray(0))
    fun authNonce(nonce: ByteArray): ByteArray = fieldVarint(1,1) + fieldVarint(2,26) + fieldBytes(3, fieldBytes(30, fieldBytes(1, nonce)))
    fun authStep2(nonces: ByteArray, info: ByteArray): ByteArray = fieldVarint(1,1) + fieldVarint(2,27) + fieldBytes(3, fieldBytes(32, fieldBytes(1, nonces) + fieldBytes(2, info)))
    fun authDeviceInfo(api: Int, model: String, language: String): ByteArray {
        val fixed = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(api.toFloat()).array()
        return fieldVarint(1,0) + fieldFixed32(2,fixed) + fieldString(3,model) + fieldVarint(4,224) + fieldString(5,language.take(2).uppercase(Locale.ROOT))
    }
    fun parseAuth(data: ByteArray): AuthResult? {
        val root = ProtoReader(data); var subtype: Int? = null; var authBytes: ByteArray? = null
        while (root.hasRemaining()) { val f = root.next() ?: return null; when(f.number){2->subtype=f.varint?.toInt();3->authBytes=f.bytes} }
        val st = subtype ?: return null; val a = authBytes ?: return AuthResult(st,null); val ar=ProtoReader(a); var watchBytes:ByteArray?=null
        while(ar.hasRemaining()){val f=ar.next()?:return null;if(f.number==31)watchBytes=f.bytes}
        val watch=watchBytes?.let{parseWatch(it)};return AuthResult(st,watch)
    }
    private fun parseWatch(data:ByteArray):WatchNonce?{val r=ProtoReader(data);var n:ByteArray?=null;var h:ByteArray?=null;while(r.hasRemaining()){val f=r.next()?:return null;when(f.number){1->n=f.bytes;2->h=f.bytes}};return if(n!=null&&h!=null)WatchNonce(n!!,h!!)else null}
    private fun fieldVarint(n:Int,v:Int)=tag(n,0)+varint(v)
    private fun fieldBytes(n:Int,v:ByteArray)=tag(n,2)+varint(v.size)+v
    private fun fieldString(n:Int,v:String)=fieldBytes(n,v.toByteArray())
    private fun fieldFixed32(n:Int,v:ByteArray)=tag(n,5)+v
    private fun tag(n:Int,w:Int)=varint((n shl 3)or w)
    private fun varint(v0:Int):ByteArray{var v=v0;val out=ArrayList<Byte>();do{var b=v and 127;v=v ushr 7;if(v!=0)b=b or 128;out.add(b.toByte())}while(v!=0);return out.toByteArray()}

    private data class Field(val number:Int,val wire:Int,val varint:Long?=null,val bytes:ByteArray?=null)
    private class ProtoReader(private val data:ByteArray){private var pos=0;fun hasRemaining()=pos<data.size;fun next():Field?{if(!hasRemaining())return null;val tag=readVarint()?:return null;val n=(tag ushr 3).toInt();val w=(tag and 7).toInt();return when(w){0->Field(n,w,varint=readVarint());1->{if(pos+8>data.size)null else{pos+=8;Field(n,w)}};2->{val l=readVarint()?.toInt()?:return null;if(l<0||pos+l>data.size)return null;val b=data.copyOfRange(pos,pos+l);pos+=l;Field(n,w,bytes=b)};5->{if(pos+4>data.size)null else{pos+=4;Field(n,w)}};else->null}};private fun readVarint():Long?{var s=0;var r=0L;while(pos<data.size&&s<64){val b=data[pos++].toInt()and 255;r=r or((b and 127).toLong()shl s);if((b and 128)==0)return r;s+=7};return null}}
}
