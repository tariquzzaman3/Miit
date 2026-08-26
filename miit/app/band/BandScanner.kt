package com.miit.app.band

import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.companion.AssociationRequest
import android.companion.BluetoothDeviceFilter
import android.companion.BluetoothLeDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Discovery and connection state machine for Xiaomi Smart Band 8+/9. */
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
            if (!device.address.equals(activeAddress, ignoreCase = true)) return
            val newState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)
            val oldState = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.BOND_NONE)
            MiitTestLog.add("Bond state: ${device.address} $oldState -> $newState")
            when (newState) {
                BluetoothDevice.BOND_BONDED -> {
                    val target = pendingBondDevice
                    pendingBondDevice = null
                    if (target != null) {
                        MiitTestLog.add("Bond complete; preparing Xiaomi SPP connection")
                        startSpp(target)
                    }
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
            appContext.registerReceiver(
                bondReceiver,
                IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
                Context.RECEIVER_EXPORTED
            )
        }.onFailure { MiitTestLog.add("Bond receiver registration failed: ${it.javaClass.simpleName}") }
    }

    @SuppressLint("MissingPermission")
    fun startScan() {
        val bt = adapter
        val bleScanner = scanner
        if (bt?.isEnabled != true || bleScanner == null) {
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
                if (!isLikelyXiaomiBand(name)) return
                val item = BandDevice(name, device.address, result.rssi)
                _devices.value = (_devices.value.filterNot { it.address == item.address } + item).sortedByDescending { it.rssi }
            }
            override fun onScanFailed(errorCode: Int) {
                MiitTestLog.add("BLE scan failed: error=$errorCode")
                _state.value = BandConnectionState.Error
            }
        }
        bleScanner.startScan(scanCallback)
    }

    private fun isLikelyXiaomiBand(name: String) = name.contains("Band", true) || name.contains("Xiaomi", true) || name.contains("Mi Smart", true)

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
        val remote = adapter?.getRemoteDevice(device.address) ?: run {
            _state.value = BandConnectionState.Error
            MiitTestLog.add("Connect failed: unable to resolve Bluetooth device")
            return
        }
        if (authKey == null || authKey.size != 16) {
            _state.value = BandConnectionState.Error
            MiitTestLog.add("Connect rejected: valid 16-byte Xiaomi auth key required")
            return
        }
        stopScan()
        activeAddress = remote.address
        activeAuthKey = authKey.copyOf()
        pendingBondDevice = device
        _state.value = BandConnectionState.Connecting
        MiitTestLog.add("Connect requested: ${device.name} ${device.address}; bond=${bondName(remote.bondState)}; transport=RFCOMM/SPP")
        when (remote.bondState) {
            BluetoothDevice.BOND_BONDED -> { pendingBondDevice = null; startSpp(device) }
            BluetoothDevice.BOND_BONDING -> MiitTestLog.add("Bluetooth bond already in progress; waiting for BOND_BONDED")
            else -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && activity != null) startCompanionPairing(remote) else startDirectBond(remote)
        }
    }

    @SuppressLint("MissingPermission")
    private fun startCompanionPairing(device: BluetoothDevice) {
        val manager = appContext.getSystemService(Context.COMPANION_DEVICE_SERVICE) as? CompanionDeviceManager
        if (manager == null) { startDirectBond(device); return }
        val filter = if (device.type == BluetoothDevice.DEVICE_TYPE_CLASSIC) BluetoothDeviceFilter.Builder().setAddress(device.address).build()
        else BluetoothLeDeviceFilter.Builder().setScanFilter(ScanFilter.Builder().setDeviceAddress(device.address).build()).build()
        val request = AssociationRequest.Builder().addDeviceFilter(filter).setSingleDevice(true).build()
        MiitTestLog.add("Xiaomi pairing: requesting Android Companion confirmation")
        runCatching {
            manager.associate(request, object : CompanionDeviceManager.Callback() {
                override fun onDeviceFound(chooserLauncher: android.content.IntentSender) {
                    MiitTestLog.add("Xiaomi pairing: Android system confirmation UI requested")
                    activity?.startIntentSenderForResult(chooserLauncher, COMPANION_REQUEST_CODE, null, 0, 0, 0)
                }
                override fun onFailure(error: CharSequence?) {
                    MiitTestLog.add("Xiaomi pairing: Companion association failed: ${error ?: "unknown"}; falling back to Android bond")
                    startDirectBond(device)
                }
            }, null)
        }.onFailure {
            MiitTestLog.add("Xiaomi pairing: Companion API error ${it.javaClass.simpleName}; falling back to Android bond")
            startDirectBond(device)
        }
    }

    @SuppressLint("MissingPermission")
    private fun handleCompanionResult(resultCode: Int, data: Intent?) {
        if (resultCode != CompanionDeviceManager.RESULT_OK) {
            MiitTestLog.add("Xiaomi pairing: Android association not confirmed (result=$resultCode)")
            _state.value = BandConnectionState.Error
            pendingBondDevice = null; activeAddress = null; activeAuthKey = null
            return
        }
        val selected = data?.getParcelableExtra(CompanionDeviceManager.EXTRA_DEVICE, BluetoothDevice::class.java)
        val target = selected ?: activeAddress?.let { adapter?.getRemoteDevice(it) }
        if (target == null) { _state.value = BandConnectionState.Error; MiitTestLog.add("Xiaomi pairing: Android returned no Bluetooth device"); return }
        MiitTestLog.add("Xiaomi pairing: Android association confirmed")
        startDirectBond(target)
    }

    @SuppressLint("MissingPermission")
    private fun startDirectBond(device: BluetoothDevice) {
        _state.value = BandConnectionState.Connecting
        if (device.bondState == BluetoothDevice.BOND_BONDED) {
            pendingBondDevice?.let { target -> pendingBondDevice = null; startSpp(target) }
                ?: startSpp(BandDevice(device.name ?: "Xiaomi Band", device.address, 0))
            return
        }
        MiitTestLog.add("Starting Android Bluetooth bond")
        val started = runCatching { device.createBond() }.getOrDefault(false)
        MiitTestLog.add("createBond() returned: $started")
        if (!started && device.bondState != BluetoothDevice.BOND_BONDING) {
            _state.value = BandConnectionState.Error
            pendingBondDevice = null; activeAddress = null; activeAuthKey = null
        }
    }

    @SuppressLint("MissingPermission")
    private fun startSpp(device: BandDevice) {
        val key = activeAuthKey ?: run { _state.value = BandConnectionState.Error; MiitTestLog.add("Xiaomi SPP: missing auth key"); return }
        spp?.close(); _state.value = BandConnectionState.Connecting
        val remote = adapter?.getRemoteDevice(device.address) ?: run { _state.value = BandConnectionState.Error; return }
        if (remote.bondState != BluetoothDevice.BOND_BONDED) { pendingBondDevice = device; return }
        spp = XiaomiSppConnection(
            device = remote,
            authKey = key.copyOf(),
            onEvent = { MiitTestLog.add(it) },
            onState = { newState ->
                _state.value = newState
                when (newState) {
                    BandConnectionState.Authenticated -> updateConnected(device.address, true, true)
                    BandConnectionState.Error, BandConnectionState.Disconnected -> { updateConnected(device.address, false, false); activeAddress = null; activeAuthKey = null }
                    else -> Unit
                }
            }
        )
        MiitTestLog.add("Starting Xiaomi RFCOMM/SPP transport after Android bond")
        spp?.connect()
    }

    private fun updateConnected(address: String, connected: Boolean, authenticated: Boolean) {
        _devices.value = _devices.value.map { if (it.address.equals(address, true)) it.copy(connected = connected, authenticated = authenticated) else it }
    }

    private fun bondName(state: Int) = when (state) { BluetoothDevice.BOND_NONE -> "NONE"; BluetoothDevice.BOND_BONDING -> "BONDING"; BluetoothDevice.BOND_BONDED -> "BONDED"; else -> "UNKNOWN" }

    fun close() {
        stopScan(); runCatching { appContext.unregisterReceiver(bondReceiver) }; spp?.close(); spp = null
        if (activeScanner === this) activeScanner = null
        activeAddress = null; activeAuthKey = null; pendingBondDevice = null
    }
}
