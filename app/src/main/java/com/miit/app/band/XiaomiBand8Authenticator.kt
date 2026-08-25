package com.miit.app.band

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.os.Build
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.bouncycastle.crypto.engines.AESEngine
import org.bouncycastle.crypto.modes.CCMBlockCipher
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter

/** Xiaomi Smart Band 8+ encrypted binding handshake. */
class XiaomiBand8Authenticator(
    private val key: ByteArray,
    private val onEvent: (String) -> Unit,
    private val onResult: (Boolean, String?) -> Unit
) {
    private val phoneNonce = ByteArray(16)
    private var stage = 0

    fun start(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic): Boolean {
        if (key.size != 16) return fail("Auth key must be 32 hexadecimal characters")
        SecureRandom().nextBytes(phoneNonce)
        stage = 1
        onEvent("auth_started")
        return write(gatt, characteristic, XiaomiProtoLite.commandNonce(phoneNonce))
    }

    fun onNotification(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray): Boolean {
        val command = XiaomiProtoLite.parseCommand(value) ?: return false
        if (command.type != 1) return false
        when (command.subtype) {
            26 -> {
                val watch = command.watchNonce ?: return fail("Band returned an invalid authentication nonce")
                if (stage != 1 || watch.nonce.size != 16 || watch.hmac.size != 32) return false
                val stepKey = authStep3Kdf(key, phoneNonce, watch.nonce)
                val decryptionKey = stepKey.copyOfRange(0, 16)
                val encryptionKey = stepKey.copyOfRange(16, 32)
                val encryptionNonce = stepKey.copyOfRange(36, 40)
                val expectedWatchHmac = hmac(decryptionKey, watch.nonce + phoneNonce)
                if (!expectedWatchHmac.contentEquals(watch.hmac)) return fail("Band authentication key rejected")
                val encryptedNonces = hmac(encryptionKey, phoneNonce + watch.nonce)
                val info = XiaomiProtoLite.authDeviceInfo(Build.VERSION.SDK_INT, Build.MODEL, java.util.Locale.getDefault().language)
                val encryptedInfo = ccmEncrypt(encryptionKey, packetNonce(encryptionNonce, 0), info)
                stage = 2
                onEvent("auth_challenge_verified")
                return write(gatt, characteristic, XiaomiProtoLite.commandAuth(encryptedNonces, encryptedInfo))
            }
            27 -> {
                if (command.status == 1 || command.authStatus == 1) {
                    stage = 3
                    onEvent("auth_ok")
                    onResult(true, null)
                    return true
                }
                return fail("Band rejected Xiaomi authentication (status=${command.status})")
            }
        }
        return false
    }

    private fun fail(message: String): Boolean {
        stage = -1
        onEvent("auth_failed: $message")
        onResult(false, message)
        return false
    }

    private fun write(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, data: ByteArray): Boolean {
        @Suppress("DEPRECATION") characteristic.value = data
        @Suppress("DEPRECATION") return gatt.writeCharacteristic(characteristic)
    }

    private fun hmac(key: ByteArray, input: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(input)
    }

    private fun authStep3Kdf(secret: ByteArray, phone: ByteArray, watch: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(hmac(phone + watch, secret), "HmacSHA256"))
        val output = ByteArray(64)
        var previous = ByteArray(0)
        var counter = 1
        var offset = 0
        while (offset < output.size) {
            mac.update(previous)
            mac.update("miwear-auth".toByteArray(Charsets.UTF_8))
            mac.update(counter.toByte())
            previous = mac.doFinal()
            val copy = minOf(previous.size, output.size - offset)
            previous.copyInto(output, offset, 0, copy)
            offset += copy
            counter++
        }
        return output
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

/** Minimal protobuf2 codec for Xiaomi authentication messages. */
private object XiaomiProtoLite {
    data class Parsed(val type: Int, val subtype: Int, val status: Int, val authStatus: Int, val watchNonce: WatchNonce?)
    data class WatchNonce(val nonce: ByteArray, val hmac: ByteArray)

    fun commandNonce(nonce: ByteArray): ByteArray = command(26, fieldMessage(3, fieldMessage(30, fieldBytes(1, nonce))))

    fun commandAuth(encryptedNonces: ByteArray, encryptedInfo: ByteArray): ByteArray {
        val step3 = fieldBytes(1, encryptedNonces) + fieldBytes(2, encryptedInfo)
        return command(27, fieldMessage(3, fieldMessage(32, step3)))
    }

    fun authDeviceInfo(api: Int, model: String, language: String): ByteArray {
        val floatApi = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(api.toFloat()).array()
        return fieldVarint(1, 0) + fieldFixed32(2, floatApi) + fieldString(3, model) + fieldVarint(4, 224) + fieldString(5, language.take(2).uppercase())
    }

    fun parseCommand(data: ByteArray): Parsed? {
        var pos = 0; var type = 0; var subtype = 0; var status = 0; var authStatus = 0; var watch: WatchNonce? = null
        while (pos < data.size) {
            val tag = readVarint(data, pos) ?: return null; pos = tag.next
            val field = tag.value ushr 3; val wire = tag.value and 7
            when (field) {
                1, 2, 100 -> { if (wire != 0) return null; val v = readVarint(data, pos) ?: return null; pos = v.next; when (field) { 1 -> type = v.value; 2 -> subtype = v.value; 100 -> status = v.value } }
                3 -> { if (wire != 2) return null; val b = readBytes(data, pos) ?: return null; pos = b.next; val nested = parseAuth(b.bytes); authStatus = nested.status; watch = nested.watch }
                else -> pos = skipField(data, pos, wire) ?: return null
            }
        }
        return Parsed(type, subtype, status, authStatus, watch)
    }

    private data class AuthParsed(val status: Int, val watch: WatchNonce?)
    private fun parseAuth(data: ByteArray): AuthParsed {
        var pos = 0; var status = 0; var watch: WatchNonce? = null
        while (pos < data.size) {
            val tag = readVarint(data, pos) ?: break; pos = tag.next
            val field = tag.value ushr 3; val wire = tag.value and 7
            if (field == 8 && wire == 0) { val v = readVarint(data, pos) ?: break; pos = v.next; status = v.value }
            else if (field == 31 && wire == 2) { val b = readBytes(data, pos) ?: break; pos = b.next; watch = parseWatchNonce(b.bytes) }
            else { pos = skipField(data, pos, wire) ?: break }
        }
        return AuthParsed(status, watch)
    }

    private fun parseWatchNonce(data: ByteArray): WatchNonce? {
        var pos = 0; var nonce = ByteArray(0); var hmac = ByteArray(0)
        while (pos < data.size) {
            val tag = readVarint(data, pos) ?: return null; pos = tag.next
            val field = tag.value ushr 3; val wire = tag.value and 7
            if (wire != 2) { pos = skipField(data, pos, wire) ?: return null; continue }
            val b = readBytes(data, pos) ?: return null; pos = b.next
            if (field == 1) nonce = b.bytes else if (field == 2) hmac = b.bytes
        }
        return WatchNonce(nonce, hmac)
    }

    private fun command(subtype: Int, auth: ByteArray): ByteArray = fieldVarint(1, 1) + fieldVarint(2, subtype) + auth
    private fun fieldMessage(number: Int, value: ByteArray): ByteArray = fieldBytes(number, value)
    private fun fieldBytes(number: Int, value: ByteArray): ByteArray = varint((number shl 3) or 2) + varint(value.size) + value
    private fun fieldString(number: Int, value: String): ByteArray = fieldBytes(number, value.toByteArray(Charsets.UTF_8))
    private fun fieldVarint(number: Int, value: Int): ByteArray = varint(number shl 3) + varint(value)
    private fun fieldFixed32(number: Int, value: ByteArray): ByteArray = varint((number shl 3) or 5) + value
    private fun varint(value: Int): ByteArray { var v = value; val out = ArrayList<Byte>(); do { var b = v and 0x7f; v = v ushr 7; if (v != 0) b = b or 0x80; out.add(b.toByte()) } while (v != 0); return out.toByteArray() }
    private data class Read(val value: Int, val next: Int)
    private data class Bytes(val bytes: ByteArray, val next: Int)
    private fun readVarint(data: ByteArray, start: Int): Read? { var pos = start; var value = 0; var shift = 0; while (pos < data.size && shift < 32) { val b = data[pos++].toInt() and 0xff; value = value or ((b and 0x7f) shl shift); if ((b and 0x80) == 0) return Read(value, pos); shift += 7 }; return null }
    private fun readBytes(data: ByteArray, start: Int): Bytes? { val l = readVarint(data, start) ?: return null; if (l.value < 0 || l.next + l.value > data.size) return null; return Bytes(data.copyOfRange(l.next, l.next + l.value), l.next + l.value) }
    private fun skipField(data: ByteArray, start: Int, wire: Int): Int? = when (wire) { 0 -> readVarint(data, start)?.next; 1 -> if (start + 8 <= data.size) start + 8 else null; 2 -> readBytes(data, start)?.next; 5 -> if (start + 4 <= data.size) start + 4 else null; else -> null }
}
