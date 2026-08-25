package com.miit.app.band

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/** BLE discovery/transport layer. Band 8+ uses Android bonding followed by Xiaomi app-layer authentication. */
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
    private var xiaomiAuthenticator: XiaomiBand8Authenticator? = null
    private var pendingBondDevice: BandDevice? = null
    private var pendingAuthKey: ByteArray? = null

    private val bondReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission") override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return
            val device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java) ?: return
            val newState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)
            val oldState = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.BOND_NONE)
            MiitTestLog.add("Bond state: ${device.address} $oldState -> $newState")
            if (newState == BluetoothDevice.BOND_BONDED && pendingBondDevice?.address == device.address) {
                val target = pendingBondDevice; val key = pendingAuthKey
                pendingBondDevice = null; pendingAuthKey = null; target?.let { openGatt(it, key) }
            } else if (newState == BluetoothDevice.BOND_NONE && pendingBondDevice?.address == device.address) {
                pendingBondDevice = null; pendingAuthKey = null; _state.value = BandConnectionState.Error
                MiitTestLog.add("Android Bluetooth bond failed/cancelled")
            }
        }
    }

    init { runCatching { appContext.registerReceiver(bondReceiver, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED), Context.RECEIVER_EXPORTED) }.onFailure { MiitTestLog.add("Bond receiver registration failed: ${it.javaClass.simpleName}") } }

    @SuppressLint("MissingPermission") fun startScan() {
        if (adapter?.isEnabled != true || scanner == null) { _state.value = BandConnectionState.Error; MiitTestLog.add("BLE scan unavailable"); return }
        stopScan(); _devices.value = emptyList(); _state.value = BandConnectionState.Scanning; MiitTestLog.add("BLE scan started")
        scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device; val name = device.name ?: result.scanRecord?.deviceName ?: "Unknown device"
                if (!isLikelyMiBand(name)) return
                val item = BandDevice(name, device.address, result.rssi)
                _devices.value = (_devices.value.filterNot { it.address == item.address } + item).sortedByDescending { it.rssi }
            }
            override fun onScanFailed(errorCode: Int) { MiitTestLog.add("BLE scan failed: error=$errorCode"); _state.value = BandConnectionState.Error }
        }
        scanner?.startScan(scanCallback)
    }

    @SuppressLint("MissingPermission") fun stopScan() { scanCallback?.let { scanner?.stopScan(it) }; scanCallback = null; if (_state.value == BandConnectionState.Scanning) _state.value = BandConnectionState.Idle }
    @SuppressLint("MissingPermission") fun connect(device: BandDevice) = connect(device, null)

    @SuppressLint("MissingPermission") fun connect(device: BandDevice, authKey: ByteArray?) {
        stopScan(); val remote = adapter?.getRemoteDevice(device.address) ?: run { _state.value = BandConnectionState.Error; return }
        gatt?.close(); gatt = null; pendingAuthKey = authKey
        MiitTestLog.add("Connect requested: ${device.name} ${device.address}; bond=${bondStateName(remote.bondState)}; authKey=${if (authKey != null) "present" else "missing"}")
        if (remote.bondState != BluetoothDevice.BOND_BONDED) {
            pendingBondDevice = device; _state.value = BandConnectionState.Connecting; MiitTestLog.add("Waiting for Android Bluetooth bond before opening GATT")
            val started = runCatching { remote.createBond() }.getOrDefault(false); MiitTestLog.add("createBond() returned: $started")
            if (!started) { pendingBondDevice = null; pendingAuthKey = null; _state.value = BandConnectionState.Error }; return
        }
        pendingAuthKey = null; openGatt(device, authKey)
    }

    private fun bondStateName(state: Int): String = when (state) { BluetoothDevice.BOND_NONE -> "NONE"; BluetoothDevice.BOND_BONDING -> "BONDING"; BluetoothDevice.BOND_BONDED -> "BONDED"; else -> "UNKNOWN" }

    @SuppressLint("MissingPermission") private fun openGatt(device: BandDevice, authKey: ByteArray? = null) {
        val remote = adapter?.getRemoteDevice(device.address) ?: run { _state.value = BandConnectionState.Error; return }
        _state.value = BandConnectionState.Connecting; MiitTestLog.add("Opening GATT connection to ${device.address} after bond is ready")
        gatt = remote.connectGatt(appContext, false, callback(authKey), BluetoothDevice.TRANSPORT_LE)
    }

    private fun callback(authKey: ByteArray?) = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            MiitTestLog.add("GATT state: status=$status newState=$newState")
            if (status != BluetoothGatt.GATT_SUCCESS) { _state.value = BandConnectionState.Error; g.close(); return }
            when (newState) { BluetoothProfile.STATE_CONNECTED -> { _state.value = BandConnectionState.Connected; MiitTestLog.add("GATT connected; discovering services"); g.discoverServices() }; BluetoothProfile.STATE_DISCONNECTED -> _state.value = BandConnectionState.Disconnected }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            MiitTestLog.add("GATT services discovered: status=$status count=${g.services.size}")
            if (status != BluetoothGatt.GATT_SUCCESS) { _state.value = BandConnectionState.Error; return }
            g.services.forEach { service -> MiitTestLog.add("Service ${service.uuid} chars=${service.characteristics.size}"); service.characteristics.forEach { c -> MiitTestLog.add("Char ${c.uuid} props=${c.properties}") } }
            readDeviceInformation(g)
            val xiaomiAuth = g.getService(XIAOMI_SERVICE)?.getCharacteristic(XIAOMI_COMMAND)
            val classicAuth = MiBandProtocol.findAuthenticationCharacteristic(g)
            if (xiaomiAuth != null) {
                if (authKey == null || authKey.size != 16) { _state.value = BandConnectionState.AwaitingXiaomiBinding; MiitTestLog.add("Band 8+ auth service found, but no valid auth key is configured"); return }
                enableNotification(g, xiaomiAuth); _state.value = BandConnectionState.Authenticating
                xiaomiAuthenticator = XiaomiBand8Authenticator(authKey, { e -> MiitTestLog.add("Xiaomi auth: $e") }) { ok, error -> _state.value = if (ok) BandConnectionState.Authenticated else BandConnectionState.Error; MiitTestLog.add(if (ok) "Xiaomi auth: initialized" else "Xiaomi auth failed: ${error ?: "unknown error"}") }
                xiaomiAuthenticator?.start(g, xiaomiAuth); return
            }
            if (classicAuth != null && authKey != null && authKey.size == 16) {
                enableNotification(g, classicAuth); _state.value = BandConnectionState.Authenticating
                authenticator = MiBandAuthenticator(authKey) { ok, _ -> _state.value = if (ok) BandConnectionState.Authenticated else BandConnectionState.Error }; authenticator?.begin(g, classicAuth)
            } else if (MiBandProtocol.hasClassicMiBandServices(g)) { MiitTestLog.add("Classic FEE0/FEE1 protocol detected; no auth key supplied"); _state.value = BandConnectionState.Connected }
            else { _state.value = BandConnectionState.AwaitingXiaomiBinding; MiitTestLog.add("Xiaomi Band 8+ services detected: Android bond complete, Xiaomi app-layer binding/auth still required") }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            when (characteristic.uuid) { MiBandProtocol.CHARACTERISTIC_AUTH -> authenticator?.onNotification(g, characteristic, value); XIAOMI_COMMAND -> xiaomiAuthenticator?.onNotification(g, characteristic, value) }
        }
        @Suppress("DEPRECATION") override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) { onCharacteristicChanged(g, characteristic, characteristic.value ?: byteArrayOf()) }
        override fun onCharacteristicRead(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) { if (status == BluetoothGatt.GATT_SUCCESS) updateDeviceInfo(characteristic.uuid, characteristic.value?.toString(Charsets.UTF_8)?.trim().orEmpty()); readNextDeviceInfo(g) }
    }

    @SuppressLint("MissingPermission") private fun enableNotification(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        gatt.setCharacteristicNotification(characteristic, true); val descriptor = characteristic.getDescriptor(CLIENT_CONFIG_DESCRIPTOR) ?: return
        @Suppress("DEPRECATION") descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE; @Suppress("DEPRECATION") gatt.writeDescriptor(descriptor)
    }
    @SuppressLint("MissingPermission") private fun readDeviceInformation(gatt: BluetoothGatt) { pendingReads.clear(); val service = gatt.getService(DEVICE_INFORMATION_SERVICE) ?: return; listOf(MODEL_NUMBER_UUID, FIRMWARE_UUID, MANUFACTURER_UUID).forEach { service.getCharacteristic(it)?.let(pendingReads::add) }; readNextDeviceInfo(gatt) }
    private val pendingReads = ArrayDeque<BluetoothGattCharacteristic>()
    @SuppressLint("MissingPermission") private fun readNextDeviceInfo(gatt: BluetoothGatt) { pendingReads.removeFirstOrNull()?.let(gatt::readCharacteristic) }
    private fun updateDeviceInfo(uuid: UUID, value: String) { val address = gatt?.device?.address ?: return; val current = _devices.value.firstOrNull { it.address == address } ?: return; val updated = when (uuid) { MODEL_NUMBER_UUID -> current.copy(model = value); FIRMWARE_UUID -> current.copy(firmware = value); MANUFACTURER_UUID -> current.copy(manufacturer = value); else -> current }; _devices.value = _devices.value.map { if (it.address == address) updated else it }; if (value.isNotBlank()) MiitTestLog.add("Device info ${uuid}: ${value.take(120)}") }
    fun close() { stopScan(); pendingBondDevice = null; pendingAuthKey = null; gatt?.close(); gatt = null; authenticator = null; xiaomiAuthenticator = null; runCatching { appContext.unregisterReceiver(bondReceiver) }; _state.value = BandConnectionState.Idle }
    private fun isLikelyMiBand(name: String): Boolean { val n = name.lowercase(); return n.contains("mi band") || n.contains("mi smart band") || n.contains("xiaomi band") || n.contains("smart band") }

    companion object {
        private val DEVICE_INFORMATION_SERVICE = UUID.fromString("0000180a-0000-1000-8000-00805f9b34fb")
        private val MODEL_NUMBER_UUID = UUID.fromString("00002a24-0000-1000-8000-00805f9b34fb")
        private val FIRMWARE_UUID = UUID.fromString("00002a26-0000-1000-8000-00805f9b34fb")
        private val MANUFACTURER_UUID = UUID.fromString("00002a29-0000-1000-8000-00805f9b34fb")
        private val CLIENT_CONFIG_DESCRIPTOR = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private val XIAOMI_SERVICE = UUID.fromString("00003802-0000-1000-8000-00805f9b34fb")
        private val XIAOMI_COMMAND = UUID.fromString("00004a02-0000-1000-8000-00805f9b34fb")
    }
}
