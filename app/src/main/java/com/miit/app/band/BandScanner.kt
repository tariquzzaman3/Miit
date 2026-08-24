package com.miit.app.band

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/** BLE discovery/transport layer. Xiaomi authentication is enabled only when a key is supplied. */
class BandScanner(context: Context) {
    private val appContext = context.applicationContext
    private val bluetoothManager = appContext.getSystemService(BluetoothManager::class.java)
    private val adapter: BluetoothAdapter? get() = bluetoothManager?.adapter
    private val scanner: BluetoothLeScanner? get() = adapter?.bluetoothLeScanner

    private val _devices = MutableStateFlow<List<BandDevice>>(emptyList())
    val devices: StateFlow<List<BandDevice>> = _devices.asStateFlow()
    private val _state = MutableStateFlow(BandConnectionState.Idle)
    val state: StateFlow<BandConnectionState> = _state.asStateFlow()

    private var scanCallback: ScanCallback? = null
    private var gatt: BluetoothGatt? = null
    private var authenticator: MiBandAuthenticator? = null
    private var authCharacteristic: BluetoothGattCharacteristic? = null

    @SuppressLint("MissingPermission")
    fun startScan() {
        if (adapter?.isEnabled != true || scanner == null) { _state.value = BandConnectionState.Error; return }
        stopScan(); _devices.value = emptyList(); _state.value = BandConnectionState.Scanning
        scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device
                val name = device.name ?: result.scanRecord?.deviceName ?: "Unknown device"
                if (!isLikelyMiBand(name)) return
                val item = BandDevice(name, device.address, result.rssi)
                _devices.value = (_devices.value.filterNot { it.address == item.address } + item).sortedByDescending { it.rssi }
            }
            override fun onScanFailed(errorCode: Int) { _state.value = BandConnectionState.Error }
        }
        scanner?.startScan(scanCallback)
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        scanCallback?.let { scanner?.stopScan(it) }; scanCallback = null
        if (_state.value == BandConnectionState.Scanning) _state.value = BandConnectionState.Idle
    }

    @SuppressLint("MissingPermission")
    fun connect(device: BandDevice) = connect(device, null)

    @SuppressLint("MissingPermission")
    fun connect(device: BandDevice, authKey: ByteArray?) {
        stopScan()
        val remote = adapter?.getRemoteDevice(device.address) ?: run { _state.value = BandConnectionState.Error; return }
        gatt?.close(); _state.value = BandConnectionState.Connecting
        gatt = remote.connectGatt(appContext, false, callback(authKey))
    }

    private fun callback(authKey: ByteArray?) = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) { _state.value = BandConnectionState.Error; g.close(); return }
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> { _state.value = BandConnectionState.Connected; g.discoverServices() }
                BluetoothProfile.STATE_DISCONNECTED -> _state.value = BandConnectionState.Disconnected
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) { _state.value = BandConnectionState.Error; return }
            readDeviceInformation(g)
            val auth = g.getService(MiBandProtocol.FEE1_SERVICE)?.getCharacteristic(MiBandProtocol.AUTH_CHARACTERISTIC)
            authCharacteristic = auth
            if (auth != null && authKey != null && authKey.size == 16) {
                enableNotification(g, auth)
                _state.value = BandConnectionState.Authenticating
                authenticator = MiBandAuthenticator(authKey) { ok, _ ->
                    _state.value = if (ok) BandConnectionState.Authenticated else BandConnectionState.Error
                }
                authenticator?.begin(g, auth)
            } else {
                _state.value = BandConnectionState.Connected
            }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            if (characteristic.uuid == MiBandProtocol.AUTH_CHARACTERISTIC) authenticator?.onNotification(g, characteristic, value)
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            onCharacteristicChanged(g, characteristic, characteristic.value ?: byteArrayOf())
        }

        override fun onCharacteristicRead(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val value = characteristic.value?.toString(Charsets.UTF_8)?.trim().orEmpty()
                updateDeviceInfo(characteristic.uuid, value)
            }
            readNextDeviceInfo(g)
        }
    }

    @SuppressLint("MissingPermission")
    private fun enableNotification(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        gatt.setCharacteristicNotification(characteristic, true)
        val descriptor = characteristic.getDescriptor(CLIENT_CONFIG_DESCRIPTOR) ?: return
        @Suppress("DEPRECATION") descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        @Suppress("DEPRECATION") gatt.writeDescriptor(descriptor)
    }

    @SuppressLint("MissingPermission")
    private fun readDeviceInformation(gatt: BluetoothGatt) {
        pendingReads.clear()
        val service = gatt.getService(DEVICE_INFORMATION_SERVICE) ?: return
        listOf(MODEL_NUMBER_UUID, FIRMWARE_UUID, MANUFACTURER_UUID).forEach { uuid -> service.getCharacteristic(uuid)?.let { pendingReads.add(it) } }
        readNextDeviceInfo(gatt)
    }

    private val pendingReads = ArrayDeque<BluetoothGattCharacteristic>()
    @SuppressLint("MissingPermission")
    private fun readNextDeviceInfo(gatt: BluetoothGatt) { pendingReads.removeFirstOrNull()?.let { gatt.readCharacteristic(it) } }

    private fun updateDeviceInfo(uuid: UUID, value: String) {
        val address = gatt?.device?.address ?: return
        val current = _devices.value.firstOrNull { it.address == address } ?: return
        val updated = when (uuid) {
            MODEL_NUMBER_UUID -> current.copy(model = value)
            FIRMWARE_UUID -> current.copy(firmware = value)
            MANUFACTURER_UUID -> current.copy(manufacturer = value)
            else -> current
        }
        _devices.value = _devices.value.map { if (it.address == address) updated else it }
    }

    fun close() { stopScan(); gatt?.close(); gatt = null; authenticator = null; authCharacteristic = null; _state.value = BandConnectionState.Idle }

    private fun isLikelyMiBand(name: String): Boolean {
        val n = name.lowercase()
        return n.contains("mi band") || n.contains("mi smart band") || n.contains("xiaomi band") || n.contains("smart band")
    }

    companion object {
        private val DEVICE_INFORMATION_SERVICE = UUID.fromString("0000180a-0000-1000-8000-00805f9b34fb")
        private val MODEL_NUMBER_UUID = UUID.fromString("00002a24-0000-1000-8000-00805f9b34fb")
        private val FIRMWARE_UUID = UUID.fromString("00002a26-0000-1000-8000-00805f9b34fb")
        private val MANUFACTURER_UUID = UUID.fromString("00002a29-0000-1000-8000-00805f9b34fb")
        private val CLIENT_CONFIG_DESCRIPTOR = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
