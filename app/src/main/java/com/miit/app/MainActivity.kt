package com.miit.app

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController

class MainActivity : ComponentActivity() {
    private val permissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            permissions.launch(arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT))
        }
        setContent { MiitApp() }
    }
}

@Composable
fun MiitApp() {
    val nav = rememberNavController()
    var selected by remember { mutableIntStateOf(0) }
    val routes = listOf("home", "editor", "settings")

    MaterialTheme {
        Scaffold(
            topBar = { TopAppBar(title = { Text("Miit") }) },
            bottomBar = {
                NavigationBar {
                    listOf("Band", "Editor", "Settings").forEachIndexed { index, label ->
                        NavigationBarItem(
                            selected = selected == index,
                            onClick = { selected = index; nav.navigate(routes[index]) },
                            icon = { Text(listOf("⌚", "✎", "⚙")[index]) },
                            label = { Text(label) }
                        )
                    }
                }
            }
        ) { padding ->
            NavHost(navController = nav, startDestination = "home", modifier = Modifier.padding(padding)) {
                composable("home") { HomeScreen(onEditor = { selected = 1; nav.navigate("editor") }) }
                composable("editor") { EditorScreen() }
                composable("settings") { SettingsScreen() }
            }
        }
    }
}

@Composable
private fun HomeScreen(onEditor: () -> Unit) {
    var connected by remember { mutableStateOf(false) }
    var deviceName by remember { mutableStateOf("No band connected") }
    val saved = remember { mutableStateListOf("My first watchface", "Minimal digital") }

    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(if (connected) deviceName else "Mi Band not connected", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Text(if (connected) "Ready for compatible watchface operations" else "Connect a band to detect model, region and firmware.")
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = {
                        connected = true
                        deviceName = "Mi Band • detected device"
                    }) { Text(if (connected) "Refresh device" else "Connect band") }
                }
            }
        }
        item { Text("My displays", style = MaterialTheme.typography.titleLarge) }
        items(saved) { name ->
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(16.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(name)
                    Button(onClick = onEditor) { Text("Edit") }
                }
            }
        }
        item {
            Button(onClick = onEditor, Modifier.fillMaxWidth()) { Text("＋ Create from scratch") }
        }
    }
}

@Composable
private fun EditorScreen() {
    var tool by remember { mutableStateOf("Select") }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Watchface editor", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(12.dp))
        Card(Modifier.fillMaxWidth().weight(1f)) {
            Column(Modifier.fillMaxSize().padding(16.dp)) {
                Text("Canvas preview", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(12.dp))
                Column(Modifier.fillMaxWidth().weight(1f).background(MaterialTheme.colorScheme.surfaceVariant).padding(24.dp)) {
                    Text("12:45", style = MaterialTheme.typography.displayLarge)
                    Text("Tuesday  •  25 Aug")
                    Spacer(Modifier.height(20.dp))
                    Text("❤️ 72   •   6,421 steps")
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Select", "Text", "Image", "Shape", "AI", "Font").forEach { t ->
                FilterChip(selected = tool == t, onClick = { tool = t }, label = { Text(t) })
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {}) { Text("Save") }
            Button(onClick = {}) { Text("Send to band") }
        }
    }
}

@Composable
private fun SettingsScreen() {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("AI providers", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                Text("Add your own API keys. Miit will keep provider configuration local to the device.")
                Spacer(Modifier.height(12.dp))
                listOf("Local / offline AI", "OpenAI-compatible API", "Google AI-compatible API").forEach { Text("• $it") }
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Fonts", style = MaterialTheme.typography.titleMedium)
                Text("Use Android/device fonts, imported font files, and Google Fonts where licensing permits.")
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Compatibility", style = MaterialTheme.typography.titleMedium)
                Text("Device-specific communication and compiler modules are designed to be replaceable per band family and firmware format.")
            }
        }
    }
}
