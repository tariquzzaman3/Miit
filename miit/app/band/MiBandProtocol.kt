package com.miit.app.band

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import java.util.UUID

/**
 * Xiaomi Mi Band protocol constants and small helpers.
 *
 * This is intentionally a protocol layer, separate from the scanner/UI. Older
 * Mi Band families expose the FEE0/FEE1 services, while newer Xiaomi Smart Band
 * generations use different protocols. Unsupported generations must fail
 * safely rather than pretending to be authenticated.
 */
object MiBandProtocol {
    val SERVICE_FEE0: UUID = UUID.fromString("0000fee0-0000-1000-8000-00805f9b34fb")
    val SERVICE_FEE1: UUID = UUID.fromString("0000fee1-0000-1000-8000-00805f9b34fb")
    val CHARACTERISTIC_AUTH: UUID = UUID.fromString("00000009-0000-3512-2118-0009af100700")
    val CHARACTERISTIC_CONTROL_POINT: UUID = UUID.fromString("00000019-0000-3512-2118-0009af100700")
    val CHARACTERISTIC_DEVICE_INFO: UUID = UUID.fromString("00000004-0000-3512-2118-0009af100700")

    const val AUTH_SEND_KEY: Byte = 0x01
    const val AUTH_REQUEST_RANDOM: Byte = 0x02
    const val AUTH_SEND_ENCRYPTED: Byte = 0x03
    const val AUTH_RESPONSE: Byte = 0x10
    const val AUTH_SUCCESS: Byte = 0x01
    const val AUTH_FAILURE: Byte = 0x04
    const val AUTH_COMMAND_BYTE: Byte = 0x08

    /** Returns true when the discovered GATT table looks like the classic Mi Band protocol. */
    fun hasClassicMiBandServices(gatt: BluetoothGatt): Boolean =
        gatt.getService(SERVICE_FEE0) != null || gatt.getService(SERVICE_FEE1) != null

    /** Find the authentication characteristic exposed by classic Mi Band firmware. */
    fun findAuthenticationCharacteristic(gatt: BluetoothGatt): BluetoothGattCharacteristic? {
        return gatt.getService(SERVICE_FEE1)?.getCharacteristic(CHARACTERISTIC_AUTH)
            ?: gatt.getService(SERVICE_FEE0)?.getCharacteristic(CHARACTERISTIC_AUTH)
    }

    /** Build the first authentication packet. The actual secret is supplied separately. */
    fun buildAuthKeyCommand(authKey: ByteArray): ByteArray =
        byteArrayOf(AUTH_SEND_KEY, AUTH_COMMAND_BYTE) + authKey

    fun buildRandomRequestCommand(): ByteArray =
        byteArrayOf(AUTH_REQUEST_RANDOM, AUTH_COMMAND_BYTE)

    /**
     * The random challenge is encrypted with the user's 16-byte authentication key.
     * Encryption is intentionally implemented in MiBandAuthenticator so this class
     * remains a pure protocol-constant layer.
     */
    fun buildEncryptedChallengeCommand(encrypted: ByteArray): ByteArray =
        byteArrayOf(AUTH_SEND_ENCRYPTED, AUTH_COMMAND_BYTE) + encrypted

    fun isAuthenticationSuccess(value: ByteArray): Boolean =
        value.size >= 3 && value[0] == AUTH_RESPONSE && value[2] == AUTH_SUCCESS

    fun isAuthenticationFailure(value: ByteArray): Boolean =
        value.size >= 3 && value[0] == AUTH_RESPONSE && value[2] == AUTH_FAILURE
}
