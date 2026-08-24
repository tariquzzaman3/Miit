package com.miit.app.band

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * Authentication state machine for the classic Huami/Mi Band FEE1 protocol.
 *
 * The authentication key is supplied by the caller and is never persisted here.
 * Newer bands can provide a different implementation behind the same concept.
 */
class MiBandAuthenticator(
    private val authKey: ByteArray,
    private val onResult: (Boolean, String?) -> Unit
) {
    private enum class Stage { IDLE, KEY_SENT, RANDOM_REQUESTED, COMPLETE }

    private var stage = Stage.IDLE
    private var randomNumber: ByteArray? = null

    fun begin(gatt: BluetoothGatt, authCharacteristic: BluetoothGattCharacteristic): Boolean {
        if (authKey.size != 16) {
            onResult(false, "Authentication key must contain 16 bytes")
            return false
        }
        stage = Stage.KEY_SENT
        return write(gatt, authCharacteristic, byteArrayOf(0x01, 0x08) + authKey)
    }

    fun onNotification(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray
    ): Boolean {
        if (value.size < 3 || value[0] != 0x10.toByte()) return false

        return when (value[2].toInt() and 0xff) {
            0x01 -> {
                stage = Stage.COMPLETE
                onResult(true, null)
                true
            }
            0x04 -> {
                stage = Stage.IDLE
                onResult(false, "Band rejected authentication key")
                true
            }
            else -> {
                when (stage) {
                    Stage.KEY_SENT -> {
                        stage = Stage.RANDOM_REQUESTED
                        write(gatt, characteristic, byteArrayOf(0x02, 0x08))
                    }
                    Stage.RANDOM_REQUESTED -> {
                        val random = value.copyOfRange(3, value.size)
                        randomNumber = random
                        stage = Stage.COMPLETE
                        val encrypted = encrypt(random)
                        write(gatt, characteristic, byteArrayOf(0x03, 0x08) + encrypted)
                    }
                    else -> false
                }
            }
        }
    }

    private fun encrypt(data: ByteArray): ByteArray {
        require(data.size % 16 == 0) { "Authentication payload must be a multiple of 16 bytes" }
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(authKey, "AES"))
        return cipher.doFinal(data)
    }

    private fun write(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        data: ByteArray
    ): Boolean {
        @Suppress("DEPRECATION")
        characteristic.value = data
        @Suppress("DEPRECATION")
        return gatt.writeCharacteristic(characteristic)
    }
}

object AuthKeyParser {
    private val hex = Regex("^[0-9a-fA-F]{32}$")

    fun parse(value: String): ByteArray? {
        val normalized = value.trim().replace(" ", "")
        if (!hex.matches(normalized)) return null
        return ByteArray(16) { index ->
            normalized.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }
}
