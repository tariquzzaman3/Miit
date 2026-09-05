package com.miit.app.band

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.companion.AssociationRequest
import android.companion.BluetoothDeviceFilter
import android.companion.BluetoothLeDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executors

/** Discovery and connection state machine for Xiaomi Smart Band 8+/9/10. */
class BandScanner(context: Context, initialActivity: Activity? = null) {
    companion object {
        private const val COMPANION_REQUEST_CODE = 7401
        private var activeScanner: BandScanner? = null
        @Volatile private var singleton: BandScanner? = null

        @Synchronized
        fun getInstance(context: Context): BandScanner {
            val existing = singleton
            return if (existing != null) {
                existing.updateActivity(context)
                existing
            } else {
                BandScanner(context.applicationContext, context as? Activity).also {
                    singleton = it
                    activeScanner = it
                }
            }
        }

        fun dispatchCompanionResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
            if (requestCode != COMPANION_REQUEST_CODE) return false
            activeScanner?.handleCompanionResult(resultCode, data)
            return true
        }
    }

    private val appContext = context.applicationContext
    @Volatile private var activity: Activity? = initialActivity
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
    private var automaticMode = false
    private var automaticAttemptedAddress: String? = null
    private val worker = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

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
                    if (target != null) startSpp(target)
                }
                BluetoothDevice.BOND_NONE -> if (pendingBondDevice != null) {
                    pendingBondDevice = null
                    _state.value = BandConnectionState.Error
                    MiitTestLog.add("Android Bluetooth bond failed/cancelled")
                }
            }
        }
    }

    private fun updateActivity(context: Context) {
        activity = context as? Activity
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

        // Automatic-first connection: find the key from Mi Fitness export, then scan and
        // connect to the first Xiaomi Band discovered. Manual entry remains the fallback.
        beginAutomaticConnection()
    }

    private fun beginAutomaticConnection() {
        worker.execute {
            MiitTestLog.add("Automatic connection: searching Download/wearablelog/*.zip")
            val candidates = runCatching { MiFitnessAuthKeyExtractor.find(appContext) }
                .onFailure { MiitTestLog.add("Automatic auth-key search failed: ${it.javaClass.simpleName}") }
                .getOrDefault(emptyList())
            val key = candidates.firstOrNull()?.key?.let { AuthKeyParser.parse(it) }
            if (key == null) {
                MiitTestLog.add("Automatic connection: no Mi Fitness auth key found; manual auth-key fallback available")
                mainHandler.post { startScan() }
                return@execute
            }
            automaticMode = true
            activeAuthKey = key.copyOf()
            appContext.getSharedPreferences("miit_pairing", Context.MODE_PRIVATE)
                .edit().putString("auth_key", candidates.first().key).apply()
            MiitTestLog.add("Automatic connection: auth key found; starting Band scan")
            mainHandler.post { startScan() }
        }
    }

    @SuppressLint("MissingPermission")
    fun startScan() {
        val bt = adapter
        val bleScanner = scanner
        if (Build.VERSION.SDK_INT >= 31 &&
            appContext.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED
        ) {
            MiitTestLog.add("BLE scan waiting for BLUETOOTH_SCAN permission")
            mainHandler.postDelayed({ startScan() }, 1000)
            return
        }
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

                // Automatic mode immediately uses the discovered Mi Fitness key.
                if (automaticMode && automaticAttemptedAddress == null &&
                    _state.value == BandConnectionState.Scanning && activeAuthKey?.size == 16
                ) {
                    automaticAttemptedAddress = item.address
                    MiitTestLog.add("Automatic connection: trying ${item.name} ${item.address}")
                    connect(item, activeAuthKey?.copyOf())
                }
            }
            override fun onScanFailed(errorCode: Int) {
                MiitTestLog.add("BLE scan failed: error=$errorCode")
                _state.value = BandConnectionState.Error
            }
        }
        runCatching { bleScanner.startScan(scanCallback) }
            .onFailure {
                MiitTestLog.add("BLE scan start exception: ${it.javaClass.simpleName}")
                _state.value = BandConnectionState.Error
            }
    }

    private fun isLikelyXiaomiBand(name: String) =
        name.contains("Band", true) || name.contains("Xiaomi", true) || name.contains("Mi Smart", true)

    @SuppressLint("MissingPermission")
    fun stopScan() {
        scanCallback?.let { runCatching { scanner?.stopScan(it) } }
        scanCallback = null
        if (_state.value == BandConnectionState.Scanning) _state.value = BandConnectionState.Idle
    }

    @SuppressLint("MissingPermission")
    private fun restoreLastConnection() {
        mainHandler.postDelayed({
            val prefs = appContext.getSharedPreferences("miit_pairing", Context.MODE_PRIVATE)
            val address = prefs.getString("last_connected_address", null) ?: return@postDelayed
            val keyText = prefs.getString("auth_key", null) ?: return@postDelayed
            val key = AuthKeyParser.parse(keyText) ?: return@postDelayed
            val remote = runCatching { adapter?.getRemoteDevice(address) }.getOrNull() ?: return@postDelayed
            if (remote.bondState != BluetoothDevice.BOND_BONDED) return@postDelayed
            if (activeAddress != null || spp != null) return@postDelayed
            val item = BandDevice(
                name = remote.name ?: "Xiaomi Band",
                address = remote.address,
                rssi = 0
            )
            _devices.value = listOf(item)
            activeAuthKey = key.copyOf()
            automaticMode = false
            MiitTestLog.add("Restoring previous Xiaomi Band connection")
            connect(item, key)
        }, 350)
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
        val key = authKey ?: activeAuthKey
        if (key == null || key.size != 16) {
            _state.value = BandConnectionState.Error
            MiitTestLog.add("Connect rejected: valid 16-byte Xiaomi auth key required")
            return
        }
        stopScan()
        activeAddress = remote.address
        activeAuthKey = key.copyOf()
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
        val filter = if (device.type == BluetoothDevice.DEVICE_TYPE_CLASSIC)
            BluetoothDeviceFilter.Builder().setAddress(device.address).build()
        else BluetoothLeDeviceFilter.Builder().setScanFilter(android.bluetooth.le.ScanFilter.Builder().setDeviceAddress(device.address).build()).build()
        val request = AssociationRequest.Builder().addDeviceFilter(filter).setSingleDevice(true).build()
        runCatching {
            manager.associate(request, object : CompanionDeviceManager.Callback() {
                override fun onDeviceFound(chooserLauncher: android.content.IntentSender) {
                    activity?.startIntentSenderForResult(chooserLauncher, COMPANION_REQUEST_CODE, null, 0, 0, 0)
                }
                override fun onFailure(error: CharSequence?) {
                    MiitTestLog.add("Xiaomi pairing: Companion association failed: ${error ?: "unknown"}; using Android bond")
                    startDirectBond(device)
                }
            }, null)
        }.onFailure {
            MiitTestLog.add("Xiaomi pairing: Companion API error ${it.javaClass.simpleName}; using Android bond")
            startDirectBond(device)
        }
    }

    @SuppressLint("MissingPermission")
    private fun handleCompanionResult(resultCode: Int, data: Intent?) {
        if (resultCode != CompanionDeviceManager.RESULT_OK) {
            _state.value = BandConnectionState.Error
            pendingBondDevice = null; activeAddress = null; activeAuthKey = null
            automaticMode = false
            MiitTestLog.add("Automatic pairing was not completed; manual auth-key fallback remains available")
            return
        }
        val selected = data?.getParcelableExtra(CompanionDeviceManager.EXTRA_DEVICE, BluetoothDevice::class.java)
        val target = selected ?: activeAddress?.let { adapter?.getRemoteDevice(it) }
        if (target == null) { _state.value = BandConnectionState.Error; return }
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
            automaticMode = false
            _state.value = BandConnectionState.Error
            pendingBondDevice = null; activeAddress = null; activeAuthKey = null
            MiitTestLog.add("Automatic connection failed before Xiaomi authentication; manual auth-key fallback available")
        }
    }

    @SuppressLint("MissingPermission")
    private fun startSpp(device: BandDevice) {
        val key = activeAuthKey ?: run { _state.value = BandConnectionState.Error; MiitTestLog.add("Xiaomi SPP: missing auth key"); return }
        spp?.close()
        _state.value = BandConnectionState.Connecting
        val remote = adapter?.getRemoteDevice(device.address) ?: run { _state.value = BandConnectionState.Error; return }
        if (remote.bondState != BluetoothDevice.BOND_BONDED) { pendingBondDevice = device; return }
        spp = XiaomiSppConnection(
            device = remote,
            authKey = key.copyOf(),
            onEvent = { MiitTestLog.add(it) },
            onData = { update -> updateData(device.address, update) },
            onState = { newState ->
                _state.value = newState
                when (newState) {
                    BandConnectionState.Authenticated -> {
                        automaticMode = false
                        updateConnected(device.address, true, true)
                        appContext.getSharedPreferences("miit_pairing", Context.MODE_PRIVATE)
                            .edit()
                            .putString("last_connected_address", device.address)
                            .putString("last_connected_name", device.name)
                            .apply()
                    }
                    BandConnectionState.Error, BandConnectionState.Disconnected -> {
                        updateConnected(device.address, false, false)
                        activeAddress = null; activeAuthKey = null
                        automaticMode = false
                        MiitTestLog.add("Automatic connection failed; manual auth-key fallback is available")
                    }
                    else -> Unit
                }
            }
        )
        MiitTestLog.add("Starting Xiaomi RFCOMM/SPP transport after Android bond")
        spp?.connect()
    }

    private fun updateData(address: String, update: BandDataUpdate) {
        _devices.value = _devices.value.map { item ->
            if (!item.address.equals(address, true)) return@map item
            val incoming = update.displays.orEmpty()
            val incomingMenu = incoming.filter { it.source == BandDisplay.Source.DISPLAY_ITEM }
            val incomingWatchfaces = update.watchfaces.orEmpty() + incoming.filter { it.source == BandDisplay.Source.WATCHFACE }
            item.copy(
                model = update.model ?: item.model,
                firmware = update.firmware ?: item.firmware,
                hardware = update.hardware ?: item.hardware,
                serialNumber = update.serialNumber ?: item.serialNumber,
                batteryPercentage = update.batteryPercentage ?: item.batteryPercentage,
                batteryState = update.batteryState ?: item.batteryState,
                charging = update.charging ?: item.charging,
                displays = mergeDisplays(item.displays.filter { it.source == BandDisplay.Source.DISPLAY_ITEM }, incomingMenu),
                watchfaces = mergeDisplays(item.watchfaces, incomingWatchfaces),
                heartRate = update.heartRate ?: item.heartRate
            )
        }
    }

    private fun mergeDisplays(existing: List<BandDisplay>, incoming: List<BandDisplay>?): List<BandDisplay> {
        if (incoming.isNullOrEmpty()) return existing
        val byId = LinkedHashMap<String, BandDisplay>()
        (existing + incoming).forEach { display ->
            val key = display.stableId
            val previous = byId[key]
            byId[key] = if (previous == null) display else previous.copy(
                name = display.name ?: previous.name,
                disabled = display.disabled || previous.disabled,
                inMoreSection = display.inMoreSection || previous.inMoreSection,
                active = display.active || previous.active,
                canDelete = display.canDelete || previous.canDelete
            )
        }
        return byId.values.toList()
    }

    private fun updateConnected(address: String, connected: Boolean, authenticated: Boolean) {
        _devices.value = _devices.value.map { if (it.address.equals(address, true)) it.copy(connected = connected, authenticated = authenticated) else it }
    }

    private fun bondName(state: Int) = when (state) {
        BluetoothDevice.BOND_NONE -> "NONE"
        BluetoothDevice.BOND_BONDING -> "BONDING"
        BluetoothDevice.BOND_BONDED -> "BONDED"
        else -> "UNKNOWN"
    }

    fun close() {
        // Activity disposal must not terminate the process-scoped Band connection.
        stopScan()
    }

    fun shutdownForProcess() {
        stopScan()
        runCatching { appContext.unregisterReceiver(bondReceiver) }
        spp?.close()
        spp = null
        worker.shutdownNow()
        activeAddress = null
        activeAuthKey = null
        pendingBondDevice = null
        if (activeScanner === this) activeScanner = null
        if (singleton === this) singleton = null
    }
}
