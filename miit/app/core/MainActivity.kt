package com.miit.app

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.BackHandler
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.miit.app.band.AuthKeyParser
import com.miit.app.band.BandConnectionState
import com.miit.app.band.BandDevice
import com.miit.app.band.BandDisplay
import com.miit.app.band.BandScanner
import com.miit.app.band.MiitTestLog
import com.miit.app.band.MiitConnectionService
import com.miit.app.band.MiFitnessAuthKeyExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class MiitScreen { CONNECTION, BAND, EDITOR }

class MainActivity : ComponentActivity() {
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        MiitTestLog.add("Permissions result: ${result.entries.joinToString { "${it.key}=${it.value}" }}")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MiitTestLog.add("App started")
        val permissions = if (Build.VERSION.SDK_INT >= 33) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.POST_NOTIFICATIONS
            )
        } else if (Build.VERSION.SDK_INT >= 31) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        setContent { MiitApp() }
        window.decorView.post {
            permissionLauncher.launch(permissions)
        }
    }

    @Deprecated("Use Activity Result APIs")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        BandScanner.dispatchCompanionResult(requestCode, resultCode, data)
    }
}

@Composable
private fun MiitApp() {
    val context = LocalContext.current
    val scanner = remember { BandScanner.getInstance(context) }
    val devices by scanner.devices.collectAsState()
    val connectionState by scanner.state.collectAsState()
    val prefs = remember { context.getSharedPreferences("miit_pairing", Context.MODE_PRIVATE) }
    var authKeyText by remember { mutableStateOf(prefs.getString("auth_key", "") ?: "") }
    var authStatus by remember { mutableStateOf<String?>(null) }
    var authCandidates by remember { mutableStateOf<List<MiFitnessAuthKeyExtractor.Candidate>>(emptyList()) }
    var connectedBand by remember { mutableStateOf<BandDevice?>(null) }
    var automaticConnection by remember { mutableStateOf(false) }
    var automaticKeyIndex by remember { mutableStateOf(0) }
    var editingDisplay by remember { mutableStateOf<BandDisplay?>(null) }
    var savedProject by remember { mutableStateOf<java.io.File?>(null) }
    var savedProjectsRefresh by remember { mutableStateOf(0) }
    var savedProject by remember { mutableStateOf<java.io.File?>(null) }
    var savedProjectsVersion by remember { mutableStateOf(0) }
    var screen by remember { mutableStateOf(if (devices.any { it.authenticated }) MiitScreen.BAND else MiitScreen.CONNECTION) }
    var showHelp by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun acceptKeys(found: List<MiFitnessAuthKeyExtractor.Candidate>, startAutomatically: Boolean = false) {
        when {
            found.isEmpty() -> {
                automaticConnection = false
                authStatus = "Automatic auth-key retrieval failed. Enter the Auth Key manually."
            }
            found.size == 1 -> {
                authKeyText = found[0].key
                prefs.edit().putString("auth_key", found[0].key).apply()
                automaticConnection = startAutomatically
                authStatus = "Auth key found automatically ✓"
                if (startAutomatically) scanner.startScan()
            }
            else -> {
                authCandidates = found.distinctBy { it.key }
                automaticKeyIndex = 0
                automaticConnection = startAutomatically
                authStatus = if (authCandidates.size == 1) "One auth key found ✓ Connecting automatically…" else "Found " + authCandidates.size + " unique keys. MIIT will try them automatically…"
                if (startAutomatically) scanner.startScan()
            }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        authStatus = "Reading selected wearablelog ZIP…"
        scope.launch {
            val result = withContext(Dispatchers.IO) { MiFitnessAuthKeyExtractor.findFromUri(context, uri) }
            acceptKeys(result, startAutomatically = true)
        }
    }

    LaunchedEffect(devices, automaticConnection, authCandidates, automaticKeyIndex, connectionState) {
        if (!automaticConnection || authCandidates.isEmpty()) return@LaunchedEffect
        val target = devices.firstOrNull()
        val candidate = authCandidates.getOrNull(automaticKeyIndex)
        if (target != null && candidate != null && connectionState == BandConnectionState.Scanning) {
            val key = AuthKeyParser.parse(candidate.key)
            if (key != null) {
                authKeyText = candidate.key
                authStatus = "Trying automatic key " + (automaticKeyIndex + 1) + "/" + authCandidates.size + "…"
                scanner.connect(target, key)
            }
        } else if (connectionState == BandConnectionState.Error) {
            val next = automaticKeyIndex + 1
            if (next < authCandidates.size) {
                automaticKeyIndex = next
                authStatus = "Previous key did not authenticate. Trying the next key automatically…"
                scanner.startScan()
            } else {
                automaticConnection = false
                authStatus = "Automatic connection could not authenticate this Band. Use the Manual fallback."
            }
        }
    }
    val authenticated = devices.firstOrNull { it.authenticated }
    LaunchedEffect(authenticated, connectionState) {
        if (authenticated != null && (connectionState == BandConnectionState.Authenticated || authenticated.connected)) {
            connectedBand = authenticated
            screen = MiitScreen.BAND
            runCatching {
                val intent = Intent(context, MiitConnectionService::class.java)
                if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent)
                else context.startService(intent)
            }
        } else if (connectionState == BandConnectionState.Connecting ||
            connectionState == BandConnectionState.Authenticating ||
            connectionState == BandConnectionState.Authenticated
        ) {
            authenticated?.let { connectedBand = it }
        }
    }

    BackHandler(enabled = screen != MiitScreen.CONNECTION) {
        if (screen == MiitScreen.EDITOR) screen = MiitScreen.BAND
    }
    DisposableEffect(Unit) {
        onDispose {
            // Keep the process-scoped Xiaomi connection alive when the Activity leaves.
            scanner.close()
        }
    }

    Box(Modifier.fillMaxSize()) {
        when (screen) {
            MiitScreen.CONNECTION -> ConnectionScreen(
                scanner = scanner,
                devices = devices,
                state = connectionState,
                authKeyText = authKeyText,
                onAuthKeyChange = {
                    authKeyText = it
                    prefs.edit().putString("auth_key", it.trim()).apply()
                },
                authStatus = authStatus,
                onFindKey = {
                    authStatus = "Searching Download/wearablelog/<timestamp>log.zip…"
                    scope.launch {
                        val result = withContext(Dispatchers.IO) { MiFitnessAuthKeyExtractor.find(context) }
                        acceptKeys(result, startAutomatically = true)
                    }
                },
                onPickZip = { picker.launch(arrayOf("application/zip", "application/octet-stream")) },
                onHelp = { showHelp = true }
            )
            MiitScreen.BAND -> connectedBand?.let { band ->
                BandScreen(
                    band = band,
                    onEdit = { display -> editingDisplay = display; savedProject = null; screen = MiitScreen.EDITOR },
                    onNew = { editingDisplay = null; savedProject = null; screen = MiitScreen.EDITOR },
                    savedProjects = WatchfaceProjectStore.list(context),
                    onOpenSaved = { file -> savedProject = file; editingDisplay = null; screen = MiitScreen.EDITOR },
                    onSavedDeleted = { savedProjectsVersion++ }
                )
            }
            MiitScreen.EDITOR -> MiitWatchFaceEditor(
                display = editingDisplay,
                savedProject = savedProject,
                device = connectedBand,
                onBack = { screen = MiitScreen.BAND },
                onAction = { action ->
                    when (action) {
                        "save" -> Toast.makeText(context, "Project saved on this phone.", Toast.LENGTH_SHORT).show()
                        "export" -> Toast.makeText(context, "Export compiler stage is next.", Toast.LENGTH_SHORT).show()
                        "band" -> Toast.makeText(context, "Direct installation is not implemented yet.", Toast.LENGTH_LONG).show()
                    }
                }
            )
        }
        FloatingActionButton(
            onClick = {
                val text = MiitTestLog.text(context)
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Miit testing logs", text))
                Toast.makeText(context, "Testing log copied.", Toast.LENGTH_SHORT).show()
            },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp).zIndex(5f),
            shape = RoundedCornerShape(12.dp)
        ) { Text("LOG", fontSize = 11.sp, fontWeight = FontWeight.Bold) }
    }

    if (showHelp) AuthKeyInstructionsDialog(onDismiss = { showHelp = false })
    // Multiple extracted keys are tested automatically.
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConnectionScreen(
    scanner: BandScanner,
    devices: List<BandDevice>,
    state: BandConnectionState,
    authKeyText: String,
    onAuthKeyChange: (String) -> Unit,
    authStatus: String?,
    onFindKey: () -> Unit,
    onPickZip: () -> Unit,
    onHelp: () -> Unit
) {
    val context = LocalContext.current
    Scaffold(topBar = { TopAppBar(title = { Text("Connect your Xiaomi Band") }) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Automatic — Recommended", style = MaterialTheme.typography.titleLarge)
                        Text("MIIT searches Download/wearablelog, opens the timestamped ZIP, then searches the log files inside it.", color = Color.Gray)
                        Button(onClick = onFindKey, modifier = Modifier.fillMaxWidth()) { Text("Get auth key from Mi Fitness") }
                        OutlinedButton(onClick = onPickZip, modifier = Modifier.fillMaxWidth()) { Text("Select wearablelog ZIP") }
                        authStatus?.let { Text(it, fontSize = 12.sp) }
                        OutlinedButton(onClick = onHelp, modifier = Modifier.fillMaxWidth()) { Text("How it works") }
                    }
                }
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Manual fallback", style = MaterialTheme.typography.titleLarge)
                        OutlinedTextField(
                            value = authKeyText,
                            onValueChange = onAuthKeyChange,
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Xiaomi auth key") },
                            supportingText = { Text("32 hexadecimal characters") },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { scanner.startScan() }) { Text(if (state == BandConnectionState.Scanning) "Scanning…" else "Scan for band") }
                            if (state == BandConnectionState.Scanning) OutlinedButton(onClick = { scanner.stopScan() }) { Text("Stop") }
                        }
                    }
                }
            }
            if (devices.isNotEmpty()) {
                item { Text("Nearby Xiaomi bands", style = MaterialTheme.typography.titleLarge) }
                items(devices, key = { it.address }) { device ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text(device.name, style = MaterialTheme.typography.titleMedium)
                            Text(if (device.authenticated) "✓ Connected and authenticated" else "Available")
                            Text("Signal: ${device.rssi} dBm")
                            Button(onClick = {
                                val key = AuthKeyParser.parse(authKeyText)
                                if (key == null) Toast.makeText(context, "Enter a valid 32-character hexadecimal auth key first.", Toast.LENGTH_LONG).show()
                                else scanner.connect(device, key)
                            }, enabled = !device.connected && !device.authenticated) { Text("Connect") }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BandScreen(
    band: BandDevice,
    onEdit: (BandDisplay) -> Unit,
    onNew: () -> Unit,
    savedProjects: List<java.io.File> = emptyList(),
    onOpenSaved: (java.io.File) -> Unit = {},
    onDeleteSaved: () -> Unit = {}
) {
    val context = LocalContext.current
    val runtimeInfo = buildString {
        band.batteryPercentage?.let { append("🔋 ").append(it).append("%") }
        if (band.charging == true) append(" • ⚡")
        band.model?.let { append(" • ").append(it) }
        band.firmware?.let { append(" • ").append(it) }
        band.countryVariant?.takeIf { it.isNotBlank() }?.let { append(" • ").append(it) }
    }
    Scaffold(topBar = { TopAppBar(title = { Text(band.name) }) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text(runtimeInfo.ifBlank { "Connected • reading Band information…" }, color = Color.Gray, fontSize = 13.sp) }
            item { Text("Watch faces", style = MaterialTheme.typography.titleLarge) }
            if (band.watchfaces.isEmpty()) item { Text("No watch-face entries received yet.", color = Color.Gray, fontSize = 13.sp) }
            else items(band.watchfaces, key = { it.stableId }) { face ->
                var previewPath by remember(face.stableId) { mutableStateOf(face.previewPath) }
                LaunchedEffect(face.stableId) { if (previewPath == null) previewPath = withContext(Dispatchers.IO) { WatchfacePreviewResolver.find(context, face, band.model)?.file?.absolutePath } }
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        PreviewThumb(previewPath)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text(face.name ?: face.code ?: "Unnamed watch face")
                            face.code?.let { Text(it, color = Color.Gray, fontSize = 10.sp) }
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                OutlinedButton(onClick = { onEdit(face.copy(previewPath = previewPath)) }) { Text("Edit") }
                                OutlinedButton(onClick = {
                                    val q = Uri.encode((face.name ?: face.code ?: band.name) + " Xiaomi Smart Band watch face")
                                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=$q"))) }
                                }) { Text("Online") }
                            }
                        }
                    }
                }
            }
            item { HorizontalDivider(); Text("Saved designs", style = MaterialTheme.typography.titleLarge) }
            if (savedProjects.isEmpty()) item { Text("Your saved designs will appear here.", color = Color.Gray, fontSize = 13.sp) }
            else items(savedProjects, key = { it.absolutePath + it.lastModified() }) { file ->
                val name = WatchfaceProjectStore.readName(file)
                val target = WatchfaceProjectStore.readTarget(file)
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.width(68.dp).height(106.dp).background(Color.Black, RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) { Text("${target.first}×${target.second}", color = Color.Gray, fontSize = 9.sp) }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text(name)
                            Text("${target.first} × ${target.second} px", color = Color.Gray, fontSize = 9.sp)
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                OutlinedButton(onClick = { onOpenSaved(file) }) { Text("Open") }
                                OutlinedButton(onClick = { if (file.delete()) onDeleteSaved() }) { Text("Delete") }
                            }
                        }
                    }
                }
            }
            item { Button(onClick = onNew, Modifier.fillMaxWidth()) { Text("＋ Custom display") } }
        }
    }
}

@Composable
private fun PreviewThumb(path: String?) {
    val bitmap = remember(path) {
        path?.let { runCatching { BitmapFactory.decodeFile(it)?.asImageBitmap() }.getOrNull() }
    }
    Box(
        Modifier.width(76.dp).height(122.dp).background(Color.Black, RoundedCornerShape(15.dp)),
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = "Watch-face preview",
                modifier = Modifier.fillMaxSize().padding(3.dp)
            )
        } else {
            Text("Preview\nnot found", color = Color.Gray, fontSize = 9.sp)
        }
    }
}


@Composable
private fun AuthKeyInstructionsDialog(onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Mi Fitness auth key") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text("1. In Mi Fitness open Profile → About this app.")
            Text("2. Repeatedly tap the Mi Fitness logo to generate the wearable log export.")
            Text("3. The usual location is Download/wearablelog/<timestamp>log.zip.")
            Text("4. MIIT opens that ZIP and searches all contained log files for token/authKey/encryptKey values.")
            Text("5. If Android does not allow automatic folder access, use Select wearablelog ZIP. Manual auth-key entry remains available.")
        }
    }, confirmButton = { Button(onClick = onDismiss) { Text("Done") } })
}

@Composable
private fun AuthKeyCandidatesDialog(
    candidates: List<MiFitnessAuthKeyExtractor.Candidate>,
    onSelect: (MiFitnessAuthKeyExtractor.Candidate) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Choose auth key") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
            candidates.forEachIndexed { index, candidate ->
                OutlinedButton(onClick = { onSelect(candidate) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Candidate ${index + 1}\n${candidate.source.take(100)}")
                }
            }
            Text("The complete key is hidden here.", color = Color.Gray, fontSize = 11.sp)
        }
    }, confirmButton = { OutlinedButton(onClick = onDismiss) { Text("Cancel") } })
}
