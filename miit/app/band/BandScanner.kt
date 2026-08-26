package com.miit.app.band

import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanFilter
import android.companion.AssociationRequest
import android.companion.BluetoothLeDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.BroadcastReceiver
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Discovery and connection state machine.
 * Xiaomi Smart Band 9 follows the successful Gadgetbridge order:
 * Companion pairing -> Android bond -> RFCOMM/SPP -> SPP V1 -> SPP V2 -> auth.
 */
class BandScanner(context: Context) {
    companion object {
        private const val COMPANION_REQUEST_CODE = 7401
        private var activeScanner: BandScanner? = null
        fun dispatchCompanionResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
            if (requestCode != COMPANION_REQUEST_CODE) return false
            activeScanner?.handleCompanionResult(resultCode, data)
            return true
        }
    }

    private val appContext = context.applicationContext
    private val activity = context as? Activity
    private val bluetoothManager = appContext.getSystemService(BluetoothManager::class.java)
    private val adapter: BluetoothAdapter? get() = bluetoothManager?.adapter
    private val scanner: BluetoothLeScanner? get() = adapter?.bluetoothLeScanner
    private val _devices = MutableStateFlow<List<BandDevice>>(emptyList())
    val devices: StateFlow<List<BandDevice>> = _devices.asStateFlow()
    private val _state = MutableStateFlow(BandConnectionState.Idle)
    val state: StateFlow<BandConnectionState> = _state.asStateFlow()

    private var scanCallback: ScanCallback? = null
    private var pendingBondDevice: BandDevice? = null
    private var activeAddress: String? = null
    private var activeAuthKey: ByteArray? = null
    private var spp: XiaomiSppConnection? = null

    private val bondReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return
            val device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java) ?: return
            if (device.address != activeAddress) return
            val newState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)
            val oldState = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.BOND_NONE)
            MiitTestLog.add("Bond state: ${device.address} $oldState -> $newState")
            when (newState) {
                BluetoothDevice.BOND_BONDED -> {
                    val target = pendingBondDevice
                    pendingBondDevice = null
                    if (target != null) startSpp(target)
                }
                BluetoothDevice.BOND_NONE -> {
                    if (pendingBondDevice != null) {
                        pendingBondDevice = null
                        _state.value = BandConnectionState.Error
                        MiitTestLog.add("Android Bluetooth bond failed/cancelled")
                    }
                }
            }
        }
    }

    init {
        activeScanner = this
        runCatching {
            appContext.registerReceiver(bondReceiver, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED), Context.RECEIVER_EXPORTED)
        }.onFailure { MiitTestLog.add("Bond receiver registration failed: ${it.javaClass.simpleName}") }
    }

    @SuppressLint("MissingPermission")
    fun startScan() {
        if (adapter?.isEnabled != true || scanner == null) {
            _state.value = BandConnectionState.Error
            MiitTestLog.add("BLE scan unavailable")
            return
        }
        stopScan()
        _devices.value = emptyList()
        _state.value = BandConnectionState.Scanning
        MiitTestLog.add("BLE scan started")
        scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device
                val name = device.name ?: result.scanRecord?.deviceName ?: "Unknown device"
                if (!name.contains("Band", ignoreCase = true) && !name.contains("Xiaomi", ignoreCase = true) && !name.contains("Mi Smart", ignoreCase = true)) return
                val item = BandDevice(name, device.address, result.rssi)
                _devices.value = (_devices.value.filterNot { it.address == item.address } + item).sortedByDescending { it.rssi }
            }
            override fun onScanFailed(errorCode: Int) {
                MiitTestLog.add("BLE scan failed: error=$errorCode")
                _state.value = BandConnectionState.Error
            }
        }
        scanner?.startScan(scanCallback)
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        scanCallback?.let { scanner?.stopScan(it) }
        scanCallback = null
        if (_state.value == BandConnectionState.Scanning) _state.value = BandConnectionState.Idle
    }

    @SuppressLint("MissingPermission")
    fun connect(device: BandDevice, authKey: ByteArray?) {
        if (activeAddress != null || pendingBondDevice != null || _state.value == BandConnectionState.Connecting || _state.value == BandConnectionState.Authenticating) {
            MiitTestLog.add("Connect ignored: connection/authentication already in progress")
            return
        }
        stopScan()
        val remote = adapter?.getRemoteDevice(device.address) ?: run { _state.value = BandConnectionState.Error; return }
        if (authKey == null || authKey.size != 16) {
            _state.value = BandConnectionState.Error
            MiitTestLog.add("Connect rejected: valid 16-byte auth key required")
            return
        }
        activeAddress = device.address
        activeAuthKey = authKey.copyOf()
        pendingBondDevice = device
        MiitTestLog.add("Connect requested: ${device.name} ${device.address}; bond=${bondName(remote.bondState)}")
        if (remote.bondState == BluetoothDevice.BOND_BONDED) {
            pendingBondDevice = null
            startSpp(device)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && activity != null) {
            startCompanionPairing(remote)
        } else {
            startDirectBond(remote)
        }
    }

    @SuppressLint("MissingPermission")
    private fun startCompanionPairing(device: BluetoothDevice) {
        val manager = appContext.getSystemService(Context.COMPANION_DEVICE_SERVICE) as? CompanionDeviceManager
        if (manager == null) { startDirectBond(device); return }
        val filter = BluetoothLeDeviceFilter.Builder().setScanFilter(ScanFilter.Builder().setDeviceAddress(device.address).build()).build()
        val request = AssociationRequest.Builder().addDeviceFilter(filter).setSingleDevice(true).build()
        MiitTestLog.add("Xiaomi pairing: starting Android Companion Device association")
        runCatching {
            manager.associate(request, object : CompanionDeviceManager.Callback() {
                override fun onDeviceFound(chooserLauncher: android.content.IntentSender) {
                    MiitTestLog.add("Xiaomi pairing: Android confirmation UI requested")
                    activity?.startIntentSenderForResult(chooserLauncher, COMPANION_REQUEST_CODE, null, 0, 0, 0)
                }
                override fun onFailure(error: CharSequence?) {
                    MiitTestLog.add("Xiaomi pairing: Companion association failed: ${error ?: "unknown"}; falling back to direct bond")
                    val remote = adapter?.getRemoteDevice(device.address) ?: return
                    startDirectBond(remote)
                }
            }, null)
        }.onFailure {
            MiitTestLog.add("Xiaomi pairing: Companion API error ${it.javaClass.simpleName}; falling back to direct bond")
            startDirectBond(device)
        }
    }

    @SuppressLint("MissingPermission")
    private fun handleCompanionResult(resultCode: Int, data: Intent?) {
        if (resultCode != CompanionDeviceManager.RESULT_OK) {
            MiitTestLog.add("Xiaomi pairing: user/system did not confirm association (result=$resultCode)")
            _state.value = BandConnectionState.Error
            pendingBondDevice = null
            activeAddress = null
            activeAuthKey = null
            return
        }
        val selected = data?.getParcelableExtra(CompanionDeviceManager.EXTRA_DEVICE, BluetoothDevice::class.java)
        val target = selected ?: activeAddress?.let { adapter?.getRemoteDevice(it) }
        if (target == null) { _state.value = BandConnectionState.Error; return }
        MiitTestLog.add("Xiaomi pairing: association confirmed; starting Android Bluetooth bond")
        startDirectBond(target)
    }

    @SuppressLint("MissingPermission")
    private fun startDirectBond(device: BluetoothDevice) {
        _state.value = BandConnectionState.Connecting
        if (device.bondState == BluetoothDevice.BOND_BONDED) { val target=pendingBondDevice; pendingBondDevice=null; if(target!=null)startSpp(target); return }
        MiitTestLog.add("Starting Android Bluetooth bond")
        val ok = runCatching { device.createBond() }.getOrDefault(false)
        MiitTestLog.add("createBond() returned: $ok")
        if (!ok && device.bondState != BluetoothDevice.BOND_BONDING) {
            _state.value = BandConnectionState.Error
            pendingBondDevice = null
            activeAddress = null
            activeAuthKey = null
        }
    }

    @SuppressLint("MissingPermission")
    private fun startSpp(device: BandDevice) {
        val key = activeAuthKey ?: return
        spp?.close()
        _state.value = BandConnectionState.Connecting
        spp = XiaomiSppConnection(deviceAddress(device.address), key,
            onEvent = { MiitTestLog.add(it) },
            onState = { _state.value = it }
        )
        MiitTestLog.add("Starting Xiaomi RFCOMM/SPP transport")
        spp?.connect()
    }

    @SuppressLint("MissingPermission")
    private fun deviceAddress(address: String): BluetoothDevice = adapter!!.getRemoteDevice(address)

    private fun bondName(state: Int) = when (state) {
        BluetoothDevice.BOND_NONE -> "NONE"
        BluetoothDevice.BOND_BONDING -> "BONDING"
        BluetoothDevice.BOND_BONDED -> "BONDED"
        else -> "UNKNOWN"
    }

    fun close() {
        stopScan()
        runCatching { appContext.unregisterReceiver(bondReceiver) }
        spp?.close(); spp=null
        if (activeScanner === this) activeScanner = null
        activeAddress=null; activeAuthKey=null; pendingBondDevice=null
    }
}
