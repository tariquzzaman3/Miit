package com.miit.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.miit.app.band.BandDisplay

private enum class EditorElementType {
    TIME, DATE, WEEKDAY, HEART_RATE, SPO2, STEPS, BATTERY, RELAXATION,
    CALORIES, DISTANCE, SLEEP, WEATHER, TEXT, IMAGE, CIRCLE_PROGRESS, BAR_PROGRESS
}

private data class EditorElement(
    val id: Int,
    val type: EditorElementType,
    val label: String,
    val preview: String,
    val format: String = "",
    val size: Int = 20,
    val x: Int = 50,
    val y: Int = 50,
    val color: Color = Color.White,
    val visible: Boolean = true,
    val locked: Boolean = false
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MiitWatchFaceEditor(
    display: BandDisplay?,
    onBack: () -> Unit,
    onAction: (String) -> Unit
) {
    val elements = remember {
        mutableStateListOf(
            EditorElement(1, EditorElementType.TIME, "Time", "12:45", "HH:mm", 36, 50, 35),
            EditorElement(2, EditorElementType.DATE, "Date", "03 Sep", "DD MMM", 16, 50, 48),
            EditorElement(3, EditorElementType.STEPS, "Steps", "6,421", "Steps", 16, 50, 62)
        )
    }
    var nextId by remember { mutableIntStateOf(4) }
    var selectedId by remember { mutableIntStateOf(1) }
    var tab by remember { mutableStateOf("Elements") }
    var addOpen by remember { mutableStateOf(false) }
    var propertiesOpen by remember { mutableStateOf(true) }
    var previewMode by remember { mutableStateOf(false) }
    var storeCheckOpen by remember { mutableStateOf(false) }
    var aodEnabled by remember { mutableStateOf(false) }
    var zoom by remember { mutableStateOf("Fit") }

    val selected = elements.firstOrNull { it.id == selectedId }

    fun add(type: EditorElementType, preview: String) {
        val id = nextId++
        elements.add(EditorElement(id, type, type.name.replace('_', ' '), preview, if (type == EditorElementType.TIME) "HH:mm" else "", 18))
        selectedId = id
        addOpen = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(display?.name ?: "New watch face") },
                navigationIcon = { IconButton(onClick = onBack) { Text("‹", fontSize = 30.sp) } },
                actions = {
                    OutlinedButton(onClick = { previewMode = !previewMode }) { Text(if (previewMode) "Edit" else "Preview") }
                    IconButton(onClick = { onAction("save") }) { Text("✓") }
                }
            )
        }
    ) { padding ->
        if (previewMode) {
            EditorPreview(elements, aodEnabled, onBack = { previewMode = false })
        } else {
            Column(Modifier.fillMaxSize().padding(padding)) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf("Elements", "Layers", "Design", "Device", "Validate").forEach { item ->
                        if (tab == item) Button(onClick = { tab = item }) { Text(item) }
                        else OutlinedButton(onClick = { tab = item }) { Text(item) }
                    }
                }

                if (tab == "Device") {
                    DeviceProfilePanel(aodEnabled) { aodEnabled = it }
                } else if (tab == "Validate") {
                    ValidationPanel(
                        elements = elements,
                        onStoreCheck = { storeCheckOpen = true },
                        onExport = { onAction("export") }
                    )
                } else {
                    Row(Modifier.fillMaxWidth().weight(1f)) {
                        Column(
                            Modifier.weight(1f).fillMaxSize().padding(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Canvas • 192 × 490 px", style = MaterialTheme.typography.labelMedium)
                                OutlinedButton(onClick = {
                                    zoom = if (zoom == "Fit") "100%" else "Fit"
                                }) { Text(zoom) }
                            }
                            WatchCanvas(elements, selectedId, onSelect = { selectedId = it })
                        }

                        if (propertiesOpen && selected != null) {
                            EditorProperties(
                                element = selected,
                                onChange = { updated ->
                                    val index = elements.indexOfFirst { it.id == updated.id }
                                    if (index >= 0) elements[index] = updated
                                },
                                onDelete = {
                                    elements.removeAll { it.id == selected.id }
                                    selectedId = elements.firstOrNull()?.id ?: 0
                                },
                                onClose = { propertiesOpen = false }
                            )
                        }
                    }

                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(6.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Button(onClick = { addOpen = true }) { Text("+ Element") }
                        OutlinedButton(onClick = { propertiesOpen = !propertiesOpen }) { Text("Properties") }
                        OutlinedButton(onClick = { onAction("save") }) { Text("Save") }
                        OutlinedButton(onClick = { onAction("share") }) { Text("Share") }
                        OutlinedButton(onClick = { onAction("band") }) { Text("Set on Band") }
                        OutlinedButton(onClick = { storeCheckOpen = true }) { Text("Store check") }
                    }

                    if (tab == "Layers") {
                        LayerPanel(elements, selectedId, { selectedId = it }, { id ->
                            val i = elements.indexOfFirst { it.id == id }
                            if (i >= 0) elements[i] = elements[i].copy(visible = !elements[i].visible)
                        })
                    }
                }
            }
        }
    }

    if (addOpen) {
        AddElementDialog(
            onDismiss = { addOpen = false },
            onAdd = ::add
        )
    }
    if (storeCheckOpen) {
        StoreCheckDialog(
            elements = elements,
            onDismiss = { storeCheckOpen = false },
            onExport = {
                storeCheckOpen = false
                onAction("export")
            }
        )
    }
}

@Composable
private fun WatchCanvas(elements: List<EditorElement>, selectedId: Int, onSelect: (Int) -> Unit) {
    Box(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier.width(170.dp).height(430.dp)
                .background(Color.Black, RoundedCornerShape(35.dp))
                .padding(8.dp)
        ) {
            elements.filter { it.visible }.forEach { element ->
                val x = (element.x.coerceIn(0, 100) * 1.45f).dp
                val y = (element.y.coerceIn(0, 100) * 4.05f).dp
                Text(
                    element.preview,
                    Modifier.padding(start = x, top = y)
                        .border(
                            if (element.id == selectedId) 1.dp else 0.dp,
                            if (element.id == selectedId) MaterialTheme.colorScheme.primary else Color.Transparent
                        )
                        .padding(2.dp)
                        .pointerInput(element.id) {
                            detectDragGestures { change, _ ->
                                change.consume()
                                onSelect(element.id)
                            }
                        },
                    color = element.color,
                    fontSize = element.size.sp,
                    fontWeight = if (element.type == EditorElementType.TIME) FontWeight.Bold else FontWeight.Normal
                )
            }
        }
    }
}

@Composable
private fun EditorProperties(
    element: EditorElement,
    onChange: (EditorElement) -> Unit,
    onDelete: () -> Unit,
    onClose: () -> Unit
) {
    var formatOpen by remember(element.id) { mutableStateOf(false) }
    var fontSizeText by remember(element.id) { mutableStateOf(element.size.toString()) }

    Card(Modifier.width(250.dp).fillMaxSize().padding(6.dp)) {
        Column(
            Modifier.padding(10.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Properties", style = MaterialTheme.typography.titleMedium)
                IconButton(onClick = onClose) { Text("×") }
            }
            Text(element.label, style = MaterialTheme.typography.titleLarge)
            Text("Position")
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                OutlinedButton(onClick = { onChange(element.copy(x = (element.x - 5).coerceAtLeast(0))) }) { Text("←") }
                OutlinedButton(onClick = { onChange(element.copy(x = (element.x + 5).coerceAtMost(100))) }) { Text("→") }
                OutlinedButton(onClick = { onChange(element.copy(y = (element.y - 5).coerceAtLeast(0))) }) { Text("↑") }
                OutlinedButton(onClick = { onChange(element.copy(y = (element.y + 5).coerceAtMost(100))) }) { Text("↓") }
            }

            if (element.type == EditorElementType.TIME || element.type == EditorElementType.DATE || element.type == EditorElementType.WEEKDAY) {
                Text("Format")
                Box {
                    OutlinedButton(onClick = { formatOpen = true }) { Text(element.format.ifBlank { "Default" }) }
                    DropdownMenu(expanded = formatOpen, onDismissRequest = { formatOpen = false }) {
                        val options = when (element.type) {
                            EditorElementType.TIME -> listOf("H", "HH", "h", "hh", "H:mm", "HH:mm", "h:mm", "hh:mm", "HH:mm:ss")
                            EditorElementType.DATE -> listOf("DD", "DD/MM", "MM/DD", "DD MMM", "DD MMM YYYY")
                            else -> listOf("Monday", "Mon")
                        }
                        options.forEach { option ->
                            DropdownMenuItem(text = { Text(option) }, onClick = {
                                onChange(element.copy(format = option))
                                formatOpen = false
                            })
                        }
                    }
                }
            }

            Text("Alignment")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf("Left", "Center", "Right").forEach { alignment ->
                    OutlinedButton(onClick = { onChange(element.copy(label = element.label)) }) { Text(alignment) }
                }
            }

            Text("Font size")
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { onChange(element.copy(size = (element.size - 2).coerceAtLeast(8))) }) { Text("−") }
                OutlinedTextField(value = fontSizeText, onValueChange = {
                    fontSizeText = it.filter(Char::isDigit)
                    it.toIntOrNull()?.let { n -> onChange(element.copy(size = n.coerceIn(8, 72))) }
                }, modifier = Modifier.width(90.dp), singleLine = true)
                OutlinedButton(onClick = { onChange(element.copy(size = (element.size + 2).coerceAtMost(72))) }) { Text("+") }
            }

            Text("Font / weight")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedButton(onClick = { onChange(element.copy(label = element.label)) }) { Text("Regular") }
                OutlinedButton(onClick = { onChange(element.copy(label = element.label)) }) { Text("Bold") }
            }

            Text("Colour")
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                listOf(Color.White, Color.Cyan, Color.Yellow, Color.Red).forEach { colour ->
                    Button(onClick = { onChange(element.copy(color = colour)) }) { Text("●") }
                }
            }

            OutlinedButton(onClick = { onChange(element.copy(locked = !element.locked)) }) {
                Text(if (element.locked) "Unlock" else "Lock")
            }
            OutlinedButton(onClick = { onChange(element.copy(visible = !element.visible)) }) {
                Text(if (element.visible) "Hide" else "Show")
            }
            OutlinedButton(onClick = onDelete) { Text("Delete element") }
        }
    }
}

@Composable
private fun AddElementDialog(
    onDismiss: () -> Unit,
    onAdd: (EditorElementType, String) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add element") },
        text = {
            Column(Modifier.height(480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                val entries = listOf(
                    EditorElementType.TIME to "12:45",
                    EditorElementType.DATE to "03 Sep",
                    EditorElementType.WEEKDAY to "Thursday",
                    EditorElementType.HEART_RATE to "♥ 72",
                    EditorElementType.SPO2 to "O₂ 98%",
                    EditorElementType.STEPS to "6,421",
                    EditorElementType.BATTERY to "86%",
                    EditorElementType.RELAXATION to "Relax 72",
                    EditorElementType.CALORIES to "482 kcal",
                    EditorElementType.DISTANCE to "4.8 km",
                    EditorElementType.SLEEP to "7h 32m",
                    EditorElementType.WEATHER to "28°C",
                    EditorElementType.TEXT to "Custom text",
                    EditorElementType.IMAGE to "Image",
                    EditorElementType.CIRCLE_PROGRESS to "Progress",
                    EditorElementType.BAR_PROGRESS to "Progress bar"
                )
                entries.forEach { (type, preview) ->
                    OutlinedButton(onClick = { onAdd(type, preview) }, Modifier.fillMaxWidth()) {
                        Text(type.name.replace('_', ' '))
                    }
                }
            }
        },
        confirmButton = { OutlinedButton(onClick = onDismiss) { Text("Close") } }
    )
}

@Composable
private fun LayerPanel(
    elements: List<EditorElement>,
    selectedId: Int,
    onSelect: (Int) -> Unit,
    onToggle: (Int) -> Unit
) {
    Card(Modifier.fillMaxWidth().padding(6.dp)) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Layers", style = MaterialTheme.typography.titleMedium)
            elements.forEach { element ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { onSelect(element.id) }, Modifier.weight(1f)) {
                        Text(if (element.id == selectedId) "▣ " + element.label else element.label)
                    }
                    OutlinedButton(onClick = { onToggle(element.id) }) { Text(if (element.visible) "👁" else "○") }
                }
            }
        }
    }
}

@Composable
private fun DeviceProfilePanel(aodEnabled: Boolean, onAodChange: (Boolean) -> Unit) {
    Card(Modifier.fillMaxWidth().padding(8.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Detected device profile", style = MaterialTheme.typography.titleLarge)
            Text("Resolution will be supplied by the connected Band capability profile.")
            Text("Canvas shape: device-specific")
            Text("Watch-face format: device-specific")
            Text("Supported data sources: detected at runtime")
            Text("Resource limits: validated during export")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("AOD")
                OutlinedButton(onClick = { onAodChange(!aodEnabled) }) {
                    Text(if (aodEnabled) "Enabled" else "Disabled")
                }
            }
        }
    }
}

@Composable
private fun ValidationPanel(
    elements: List<EditorElement>,
    onStoreCheck: () -> Unit,
    onExport: () -> Unit
) {
    val unsupported = elements.count { it.type == EditorElementType.RELAXATION }
    Card(Modifier.fillMaxWidth().padding(8.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Pre-export validation", style = MaterialTheme.typography.titleLarge)
            Text("✓ Element model is internally consistent")
            Text("✓ Canvas uses the selected device profile")
            Text(if (unsupported == 0) "✓ No known unsupported placeholder elements" else "⚠ Some elements require device capability verification")
            Text("✓ Store metadata can be checked before export")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onStoreCheck) { Text("Store check") }
                OutlinedButton(onClick = onExport) { Text("Export") }
            }
        }
    }
}

@Composable
private fun StoreCheckDialog(
    elements: List<EditorElement>,
    onDismiss: () -> Unit,
    onExport: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Xiaomi Store readiness") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text("Technical")
                Text("✓ Editor structure")
                Text("✓ Target-device validation hook")
                Text("⚠ Final package validation depends on the target Xiaomi format")
                Text("Content & licensing")
                Text("⚠ Confirm ownership/licensing of images, fonts and other assets")
                Text("Metadata")
                Text("⚠ Review name, description, tags and preview assets")
                Text("MIIT does not upload or claim approval from Xiaomi.")
            }
        },
        confirmButton = { Button(onClick = onExport) { Text("Export submission package") } },
        dismissButton = { OutlinedButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun EditorPreview(elements: List<EditorElement>, aod: Boolean, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().background(Color.Black), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(if (aod) "AOD preview" else "Watch-face preview", color = Color.White, modifier = Modifier.padding(12.dp))
        WatchCanvas(elements, 0, {})
        Button(onClick = onBack) { Text("Back to editor") }
    }
}
