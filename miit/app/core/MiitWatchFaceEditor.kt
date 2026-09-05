package com.miit.app

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.miit.app.band.BandDevice
import com.miit.app.band.BandDisplay

private enum class ToolCategory(val icon: String, val title: String) {
    ADD("＋", "Add"),
    TEXT("T", "Text"),
    DATA("◉", "Data"),
    SHAPE("○", "Shape"),
    MEDIA("▣", "Media"),
    LAYERS("≡", "Layers"),
    STYLE("✦", "Style"),
    AOD("☾", "AOD"),
    AI("✧", "AI"),
    EXPORT("⇩", "Export")
}

private enum class EditorElementType {
    TIME, DATE, WEEKDAY, HEART_RATE, SPO2, STEPS, BATTERY, CALORIES,
    DISTANCE, SLEEP, WEATHER, DIGITAL_NUMBER, ANALOG_CLOCK, ARC_PROGRESS,
    LINE_PROGRESS, CONTAINER, TEXT, CIRCLE, RECTANGLE, LINE, ARC, IMAGE
}

private data class EditorElement(
    val id: Int,
    val type: EditorElementType,
    val preview: String,
    val x: Float = 50f,
    val y: Float = 50f,
    val size: Float = 24f,
    val color: Color = Color.White,
    val visible: Boolean = true,
    val locked: Boolean = false,
    val bold: Boolean = false,
    val alignment: String = "Center",
    val format: String = ""
)

@Composable
fun MiitWatchFaceEditor(
    display: BandDisplay?,
    device: BandDevice?,
    onBack: () -> Unit,
    onAction: (String) -> Unit
) {
    val context = LocalContext.current
    val profile = remember(device?.model, device?.name) { resolveProfile(device) }
    val elements = remember(display?.stableId) {
        mutableStateListOf<EditorElement>().apply {
            if (display == null) {
                add(EditorElement(1, EditorElementType.TIME, "", 50f, 34f, 40f, format = "HH:mm"))
                add(EditorElement(2, EditorElementType.DATE, "", 50f, 50f, 17f, format = "DD MMM"))
                if (device?.batteryPercentage != null) {
                    add(EditorElement(3, EditorElementType.BATTERY, "", 50f, 63f, 16f))
                }
            }
        }
    }
    var nextId by remember(display?.stableId) { mutableIntStateOf(elements.maxOfOrNull { it.id }?.plus(1) ?: 1) }
    var selectedId by remember { mutableIntStateOf(elements.firstOrNull()?.id ?: 0) }
    var selectedTool by remember { mutableStateOf(ToolCategory.ADD) }
    var previewMode by remember { mutableStateOf(false) }
    var aodEnabled by remember { mutableStateOf(false) }
    var editingTextId by remember { mutableIntStateOf(0) }
    var sourcePicker by remember { mutableStateOf(false) }
    var imageTarget by remember { mutableIntStateOf(0) }
    var referencePath by remember(display?.stableId) { mutableStateOf(display?.previewPath) }
    var layersOpen by remember { mutableStateOf(false) }
    var referenceOpacity by remember { mutableStateOf(0.65f) }
    var propertiesOpen by remember { mutableStateOf(false) }
    var undoStack by remember { mutableStateOf<List<List<EditorElement>>>(emptyList()) }
    var redoStack by remember { mutableStateOf<List<List<EditorElement>>>(emptyList()) }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && imageTarget != 0) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            if (imageTarget == -1) {
                referencePath = uri.toString()
            } else {
                val index = elements.indexOfFirst { it.id == imageTarget }
                if (index >= 0) {
                    elements[index] = elements[index].copy(preview = uri.toString())
                }
            }
        }
        imageTarget = 0
    }

    fun snapshotBeforeChange() {
        undoStack = (undoStack + listOf(elements.toList())).takeLast(40)
        redoStack = emptyList()
    }

    fun addElement(type: EditorElementType) {
        snapshotBeforeChange()
        val id = nextId++
        elements += EditorElement(
            id = id,
            type = type,
            preview = livePreview(type, device),
            x = 50f,
            y = 50f,
            size = if (type == EditorElementType.TIME) 36f else 18f
        )
        selectedId = id
        if (type == EditorElementType.TEXT) editingTextId = id
        if (type == EditorElementType.DIGITAL_NUMBER) sourcePicker = true
        if (type == EditorElementType.IMAGE) {
            imageTarget = id
            imagePicker.launch(arrayOf("image/*"))
        }
    }

    if (sourcePicker) {
        MiCreateSourceDialog(
            onDismiss = { sourcePicker = false },
            onSelect = { source ->
                val index = elements.indexOfFirst { it.id == selectedId }
                if (index >= 0) {
                    elements[index] = elements[index].copy(preview = source)
                }
                sourcePicker = false
            }
        )
    }

    if (editingTextId != 0) {
        val item = elements.firstOrNull { it.id == editingTextId }
        if (item != null) {
            TextEditDialog(
                initial = item.preview,
                onDismiss = { editingTextId = 0 },
                onApply = { value ->
                    elements[elements.indexOfFirst { it.id == editingTextId }] = elements[elements.indexOfFirst { it.id == editingTextId }].copy(preview = value)
                    editingTextId = 0
                }
            )
        }
    }

    if (propertiesOpen) {
        val selected = elements.firstOrNull { it.id == selectedId }
        if (selected != null) {
            EditorPropertiesDialog(
                element = selected,
                onDismiss = { propertiesOpen = false },
                onApply = { updated ->
                    snapshotBeforeChange()
                    val index = elements.indexOfFirst { it.id == updated.id }
                    if (index >= 0) elements[index] = updated
                    propertiesOpen = false
                }
            )
        }
    }

    if (previewMode) {
        FullPreview(
            elements = elements,
            profile = profile,
            aod = aodEnabled,
            onBack = { previewMode = false }
        )
        return
    }

    Column(Modifier.fillMaxSize().background(Color(0xFF101114)).statusBarsPadding()) {
        // Leave a clean safe area for the phone status bar/camera cut-out.
        Spacer(Modifier.height(14.dp))
        // Minimal editor header: icon-first, no Material button/card treatment.
        Row(
            Modifier.fillMaxWidth().height(52.dp).background(Color(0xFF18191D)).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            ToolGlyph("‹", "Back", onBack)
            Text(
                display?.name?.takeIf { it.isNotBlank() } ?: "New watch face",
                color = Color.White,
                fontSize = 15.sp,
                maxLines = 1,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                ToolGlyph("↶", "Undo") {
                    undoStack.lastOrNull()?.let { previous ->
                        redoStack = (redoStack + listOf(elements.toList())).takeLast(40)
                        undoStack = undoStack.dropLast(1)
                        elements.clear()
                        elements.addAll(previous)
                        selectedId = elements.lastOrNull()?.id ?: 0
                    }
                }
                ToolGlyph("↷", "Redo") {
                    redoStack.lastOrNull()?.let { next ->
                        undoStack = (undoStack + listOf(elements.toList())).takeLast(40)
                        redoStack = redoStack.dropLast(1)
                        elements.clear()
                        elements.addAll(next)
                        selectedId = elements.lastOrNull()?.id ?: 0
                    }
                }
                ToolGlyph("⌁", "Preview") { previewMode = true }
                ToolGlyph("✓", "Save") {
                    WatchfaceProjectStore.save(
                        context,
                        display?.name ?: "MIIT watch face",
                        profile.width,
                        profile.height,
                        aodEnabled,
                        serializeElements(elements)
                    )
                    Toast.makeText(context, "Project saved on this phone.", Toast.LENGTH_SHORT).show()
                    onAction("save")
                }
            }
        }

        // BAR 1 — main tools, horizontally swipeable.
        HorizontalToolBar(
            selected = selectedTool,
            onSelect = { selectedTool = it },
            modifier = Modifier.fillMaxWidth().height(66.dp)
        )

        // BAR 2 — contextual sub-tools, horizontally swipeable.
        SubToolBar(
            category = selectedTool,
            aodEnabled = aodEnabled,
            onAodChange = { aodEnabled = it },
            onAdd = ::addElement,
            onPickImage = {
                val id = if (selectedId != 0) selectedId else nextId++
                if (selectedId == 0) {
                    elements += EditorElement(id, EditorElementType.IMAGE, "", 50f, 50f, 24f)
                    selectedId = id
                }
                imageTarget = id
                imagePicker.launch(arrayOf("image/*"))
            },
            onReference = {
                imageTarget = -1
                imagePicker.launch(arrayOf("image/*"))
            },
            onModifySelected = { transform ->
                val index = elements.indexOfFirst { it.id == selectedId }
                if (index >= 0 && !elements[index].locked) elements[index] = transform(elements[index])
            },
            onEditText = {
                if (elements.firstOrNull { it.id == selectedId }?.type == EditorElementType.TEXT) editingTextId = selectedId
            },
            onSourcePicker = { sourcePicker = true },
            onExport = { onAction("export") },
            onBand = { onAction("band") },
            onAi = {
                val visible = elements.filter { it.visible }
                if (visible.isNotEmpty()) {
                    val count = visible.size
                    var n = 0
                    visible.forEach {
                        val index = elements.indexOfFirst { e -> e.id == it.id }
                        if (index >= 0) {
                            elements[index] = elements[index].copy(x = 50f, y = if (count == 1) 50f else 10f + (84f / (count - 1)) * n++)
                        }
                    }
                }
            },
            onLayerAction = { action ->
                layersOpen = true
                snapshotBeforeChange()
                val selected = elements.indexOfFirst { it.id == selectedId }
                if (selected < 0) return@SubToolBar
                when (action) {
                    "hide" -> elements[selected] = elements[selected].copy(visible = !elements[selected].visible)
                    "lock" -> elements[selected] = elements[selected].copy(locked = !elements[selected].locked)
                    "delete" -> {
                        elements.removeAt(selected)
                        selectedId = elements.firstOrNull()?.id ?: 0
                    }
                    "front" -> {
                        val item = elements.removeAt(selected)
                        elements += item
                    }
                    "back" -> {
                        val item = elements.removeAt(selected)
                        elements.add(0, item)
                    }
                }
            },
            modifier = Modifier.fillMaxWidth().height(58.dp)
        )

        // MIDDLE — actual editor display.
        Box(Modifier.fillMaxSize().weight(1f)) {
            WatchCanvasV2(
                elements = elements,
                selectedId = selectedId,
                profile = profile,
                display = display,
                device = device,
                referencePath = referencePath,
                referenceOpacity = referenceOpacity,
                metadataOnly = display != null,
                onSelect = { selectedId = it },
                onMove = { id, dx, dy ->
                    val index = elements.indexOfFirst { it.id == id }
                    if (index >= 0 && !elements[index].locked) {
                        val current = elements[index]
                        elements[index] = current.copy(
                            x = (current.x + dx).coerceIn(0f, 100f),
                            y = (current.y + dy).coerceIn(0f, 100f)
                        )
                    }
                }
            )
            if (layersOpen) {
                LayerPanel(
                    elements = elements,
                    selectedId = selectedId,
                    onSelect = { selectedId = it },
                    onClose = { layersOpen = false },
                    modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(175.dp)
                )
            }
        }

        // Lightweight selected-element strip; still icon-first.
        SelectedElementBar(
            selected = elements.firstOrNull { it.id == selectedId },
            onChangeSize = { delta ->
                val index = elements.indexOfFirst { it.id == selectedId }
                if (index >= 0) elements[index] = elements[index].copy(size = (elements[index].size + delta).coerceIn(10f, 72f))
            },
            onDelete = {
                elements.removeAll { it.id == selectedId }
                selectedId = elements.firstOrNull()?.id ?: 0
            },
            onExport = { onAction("export") },
            onBand = { onAction("band") },
            onProperties = { propertiesOpen = true }
        )
    }
}

@Composable
private fun HorizontalToolBar(
    selected: ToolCategory,
    onSelect: (ToolCategory) -> Unit,
    modifier: Modifier
) {
    Row(
        modifier.background(Color(0xFF202126)).horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        ToolCategory.values().forEach { tool ->
            ToolCell(tool.icon, tool.title, selected == tool, onClick = { onSelect(tool) })
        }
    }
}

@Composable
private fun SubToolBar(
    category: ToolCategory,
    aodEnabled: Boolean,
    onAodChange: (Boolean) -> Unit,
    onAdd: (EditorElementType) -> Unit,
    onPickImage: () -> Unit,
    onReference: () -> Unit,
    onSourcePicker: () -> Unit,
    onLayerAction: (String) -> Unit,
    modifier: Modifier,
    onModifySelected: ((EditorElement) -> EditorElement) -> Unit = {},
    onEditText: () -> Unit = {},
    onExport: () -> Unit = {},
    onBand: () -> Unit = {},
    onAi: () -> Unit = {}
) {
    val items = when (category) {
        ToolCategory.ADD -> listOf(
            SubAction("▧", "Image") { onPickImage() },
            SubAction("▤", "Image List") { onAdd(EditorElementType.IMAGE) },
            SubAction("123", "Digital") { onAdd(EditorElementType.DIGITAL_NUMBER) },
            SubAction("◷", "Analog") { onAdd(EditorElementType.ANALOG_CLOCK) },
            SubAction("◔", "Arc") { onAdd(EditorElementType.ARC_PROGRESS) },
            SubAction("━", "Line") { onAdd(EditorElementType.LINE_PROGRESS) },
            SubAction("▢", "Container") { onAdd(EditorElementType.CONTAINER) },
            SubAction("T", "Text") { onAdd(EditorElementType.TEXT) }
        )
        ToolCategory.TEXT -> listOf(
            SubAction("T", "Text") { onAdd(EditorElementType.TEXT) },
            SubAction("✎", "Edit") { onEditText() },
            SubAction("A+", "Size+") { onModifySelected { it.copy(size = (it.size + 2f).coerceAtMost(72f)) } },
            SubAction("A−", "Size−") { onModifySelected { it.copy(size = (it.size - 2f).coerceAtLeast(8f)) } },
            SubAction("B", "Bold") { onModifySelected { it.copy(bold = !it.bold) } },
            SubAction("L", "Left") { onModifySelected { it.copy(alignment = "Left", x = 12f) } },
            SubAction("C", "Center") { onModifySelected { it.copy(alignment = "Center", x = 50f) } },
            SubAction("R", "Right") { onModifySelected { it.copy(alignment = "Right", x = 88f) } }
        )
        ToolCategory.DATA -> listOf(
            SubAction("Src", "Source") { onSourcePicker() },
            SubAction("♥", "Heart") { onAdd(EditorElementType.DIGITAL_NUMBER) },
            SubAction("O₂", "SpO₂") { onAdd(EditorElementType.SPO2) },
            SubAction("↟", "Steps") { onAdd(EditorElementType.STEPS) },
            SubAction("▣", "Battery") { onAdd(EditorElementType.BATTERY) },
            SubAction("Cal", "Active Cal") { onAdd(EditorElementType.DIGITAL_NUMBER) },
            SubAction("%", "Goals") { onAdd(EditorElementType.DIGITAL_NUMBER) },
            SubAction("☾", "Sleep") { onAdd(EditorElementType.DIGITAL_NUMBER) },
            SubAction("⚡", "Charge") { onAdd(EditorElementType.DIGITAL_NUMBER) },
            SubAction("°", "Temp") { onAdd(EditorElementType.DIGITAL_NUMBER) },
            SubAction("≋", "Pressure") { onAdd(EditorElementType.DIGITAL_NUMBER) },
            SubAction("↟", "Elevation") { onAdd(EditorElementType.DIGITAL_NUMBER) },
            SubAction("☁", "Weather") { onAdd(EditorElementType.DIGITAL_NUMBER) }
        )
        ToolCategory.SHAPE -> listOf(
            SubAction("○", "Circle") { onAdd(EditorElementType.CIRCLE) },
            SubAction("□", "Rect") { onAdd(EditorElementType.RECTANGLE) },
            SubAction("／", "Line") { onAdd(EditorElementType.LINE) },
            SubAction("◔", "Arc") { onAdd(EditorElementType.ARC) }
        )
        ToolCategory.MEDIA -> listOf(
            SubAction("▧", "Photo") { onPickImage() },
            SubAction("◎", "Reference") { onReference() }
        )
        ToolCategory.LAYERS -> listOf(
            SubAction("↑", "Front") { onLayerAction("front") },
            SubAction("↓", "Back") { onLayerAction("back") },
            SubAction("◉", "Show") { onLayerAction("hide") },
            SubAction("⌑", "Lock") { onLayerAction("lock") },
            SubAction("×", "Delete") { onLayerAction("delete") }
        )
        ToolCategory.STYLE -> listOf(SubAction("W", "White") { onModifySelected { it.copy(color = Color.White) } }, SubAction("B", "Blue") { onModifySelected { it.copy(color = Color(0xFF55B7FF)) } }, SubAction("Y", "Yellow") { onModifySelected { it.copy(color = Color(0xFFFFD54F)) } }, SubAction("R", "Red") { onModifySelected { it.copy(color = Color(0xFFFF6B6B)) } }, SubAction("B+", "Bold") { onModifySelected { it.copy(bold = !it.bold) } })
        ToolCategory.AOD -> listOf(SubAction(if (aodEnabled) "●" else "○", if (aodEnabled) "AOD on" else "AOD off") { onAodChange(!aodEnabled) })
        ToolCategory.AI -> listOf(
            SubAction("✧", "Arrange") { onAi() },
            SubAction("◎", "Center") { onAi() },
            SubAction("✓", "Clean") { onAi() }
        )
        ToolCategory.EXPORT -> listOf(
            SubAction("⇩", "Save") { onExport() },
            SubAction("✓", "Check") { onExport() },
            SubAction("⌂", "Band") { onBand() }
        )
    }

    Row(
        modifier.background(Color(0xFF17181B)).horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items.forEach { action ->
            ToolCell(action.icon, action.title, false, action.onClick)
        }
    }
}

private data class SubAction(val icon: String, val title: String, val onClick: () -> Unit)

@Composable
private fun TextEditDialog(
    initial: String,
    onDismiss: () -> Unit,
    onApply: (String) -> Unit
) {
    var text by remember(initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit text") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = { Button(onClick = { onApply(text) }) { Text("Apply") } },
        dismissButton = { OutlinedButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun MiCreateSourceDialog(
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit
) {
    val sources = MiCreateCatalog.band9Sources
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Mi-Create data source") },
        text = {
            LazyColumn {
                items(sources.filter { it.idFprj != "0" }) { source ->
                    TextButton(
                        onClick = { onSelect(source.name) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.fillMaxWidth()) {
                            Text(source.name)
                            Text(source.idFprj, color = Color.Gray, fontSize = 8.sp)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}


@Composable
private fun ToolGlyph(icon: String, description: String, onClick: () -> Unit) {
    Box(
        Modifier.size(38.dp).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(icon, color = Color.White, fontSize = 22.sp)
    }
}

@Composable
private fun ToolCell(icon: String, title: String, selected: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.width(58.dp).padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier.size(36.dp)
                .background(if (selected) Color(0xFF3E7BFF) else Color.Transparent, RoundedCornerShape(10.dp))
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Text(icon, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        Text(title, color = Color.LightGray, fontSize = 9.sp)
    }
}

@Composable
private fun WatchCanvasV2(
    elements: List<EditorElement>,
    selectedId: Int,
    profile: DeviceProfile,
    display: BandDisplay?,
    device: BandDevice?,
    referencePath: String?,
    referenceOpacity: Float,
    metadataOnly: Boolean,
    onSelect: (Int) -> Unit,
    onMove: (Int, Float, Float) -> Unit
) {
    Box(Modifier.fillMaxSize().background(Color(0xFF0D0E10)), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier
                    .width(190.dp)
                    .height((190f * profile.height / profile.width).dp)
                    .background(Color.Black, RoundedCornerShape(34.dp))
            ) {
                referencePath?.let { preview ->
                    EditorReferenceImage(preview, Modifier.fillMaxSize().alpha(referenceOpacity))
                }
                elements.filter { it.visible }.forEach { element ->
                    val x = (element.x / 100f * 166f).dp
                    val y = (element.y / 100f * ((190f * profile.height / profile.width) - 10f)).dp
                    when (element.type) {
                        EditorElementType.IMAGE -> EditorImageLayer(element, x, y, element.id == selectedId, onSelect, onMove)
                        EditorElementType.CIRCLE, EditorElementType.RECTANGLE, EditorElementType.LINE, EditorElementType.ARC ->
                            EditorShapeLayer(element, x, y, element.id == selectedId, onSelect, onMove)
                        EditorElementType.ANALOG_CLOCK -> EditorAnalogLayer(element, x, y, element.id == selectedId, onSelect)
                        EditorElementType.ARC_PROGRESS, EditorElementType.LINE_PROGRESS -> EditorProgressLayer(element, x, y, device)
                        EditorElementType.CONTAINER -> Box(
                            Modifier.padding(start = x, top = y)
                                .size(80.dp, 45.dp)
                                .background(Color.Transparent, RoundedCornerShape(5.dp))
                                .pointerInput(element.id) {
                                    detectDragGestures { change, amount ->
                                        change.consume()
                                        onSelect(element.id)
                                        onMove(element.id, amount.x / 1.66f, amount.y / 4.08f)
                                    }
                                }
                        )
                        else -> Text(
                            renderElementValue(element, device),
                            color = element.color,
                            fontSize = element.size.sp,
                            fontWeight = if (element.bold || element.type == EditorElementType.TIME) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier.padding(start = x, top = y).pointerInput(element.id) {
                                detectDragGestures { change, amount ->
                                    change.consume()
                                    onSelect(element.id)
                                    onMove(element.id, amount.x / 1.66f, amount.y / 4.08f)
                                }
                            }
                        )
                    }
                }
                if (metadataOnly && display?.previewPath == null) {
                    Text(
                        "No saved preview found • use Media to add a reference image",
                        color = Color.Gray,
                        fontSize = 10.sp,
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp)
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text("${profile.width} × ${profile.height} px  •  ${profile.source}", color = Color.Gray, fontSize = 10.sp)
            if (metadataOnly) {
                Text("Band metadata only — add a reference image to trace the original face", color = Color(0xFF9AA0AA), fontSize = 10.sp)
            }
        }
    }
}

@Composable
private fun EditorImageLayer(
    element: EditorElement,
    x: Dp,
    y: Dp,
    selected: Boolean,
    onSelect: (Int) -> Unit,
    onMove: (Int, Float, Float) -> Unit
) {
    val context = LocalContext.current
    var bitmap by remember(element.preview) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(element.preview) {
        bitmap = withContext(Dispatchers.IO) {
            runCatching {
                if (element.preview.startsWith("content://")) {
                    context.contentResolver.openInputStream(Uri.parse(element.preview))?.use {
                        BitmapFactory.decodeStream(it)?.asImageBitmap()
                    }
                } else null
            }.getOrNull()
        }
    }
    Box(
        Modifier.padding(start = x, top = y)
            .size(86.dp, 64.dp)
            .background(if (selected) Color(0x443F78FF) else Color.Transparent, RoundedCornerShape(4.dp))
            .pointerInput(element.id) {
                detectDragGestures { change, amount ->
                    change.consume()
                    onSelect(element.id)
                    onMove(element.id, amount.x / 1.66f, amount.y / 4.08f)
                }
            },
        contentAlignment = Alignment.Center
    ) {
        bitmap?.let { Image(it, "Inserted image", Modifier.fillMaxSize()) }
            ?: Text("Photo", color = Color.Gray, fontSize = 9.sp)
    }
}

@Composable
private fun EditorShapeLayer(
    element: EditorElement,
    x: Dp,
    y: Dp,
    selected: Boolean,
    onSelect: (Int) -> Unit,
    onMove: (Int, Float, Float) -> Unit
) {
    Canvas(
        Modifier.padding(start = x, top = y)
            .size(76.dp, 55.dp)
            .pointerInput(element.id) {
                detectDragGestures { change, amount ->
                    change.consume()
                    onSelect(element.id)
                    onMove(element.id, amount.x / 1.66f, amount.y / 4.08f)
                }
            }
    ) {
        val stroke = Stroke(2.dp.toPx())
        when (element.type) {
            EditorElementType.CIRCLE -> drawCircle(element.color, style = stroke)
            EditorElementType.RECTANGLE -> drawRect(element.color, style = stroke)
            EditorElementType.LINE -> drawLine(element.color, Offset(0f, size.height / 2f), Offset(size.width, size.height / 2f), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
            EditorElementType.ARC -> drawArc(element.color, -90f, 270f, false, style = stroke)
            else -> Unit
        }
        if (selected) drawRect(Color(0x663F78FF), style = Stroke(1.dp.toPx()))
    }
}

@Composable
private fun EditorAnalogLayer(
    element: EditorElement,
    x: Dp,
    y: Dp,
    selected: Boolean,
    onSelect: (Int) -> Unit
) {
    Canvas(Modifier.padding(start = x, top = y).size(78.dp).clickable { onSelect(element.id) }) {
        val now = java.util.Calendar.getInstance()
        val h = now.get(java.util.Calendar.HOUR)
        val m = now.get(java.util.Calendar.MINUTE)
        val s = now.get(java.util.Calendar.SECOND)
        val cx = size.width / 2f
        val cy = size.height / 2f
        val radius = size.minDimension / 2f - 2.dp.toPx()
        drawCircle(element.color, radius, style = Stroke(2.dp.toPx()))
        fun hand(angle: Float, length: Float, width: Float) {
            val rad = Math.toRadians(angle.toDouble())
            drawLine(element.color, Offset(cx, cy), Offset(cx + kotlin.math.cos(rad).toFloat() * length, cy + kotlin.math.sin(rad).toFloat() * length), strokeWidth = width)
        }
        hand(h / 12f * 360f - 90f, radius * .48f, 4f)
        hand(m / 60f * 360f - 90f, radius * .72f, 3f)
        hand(s / 60f * 360f - 90f, radius * .84f, 1.5f)
        if (selected) drawCircle(Color(0xFF3F78FF), radius + 2.dp.toPx(), style = Stroke(1.dp.toPx()))
    }
}

@Composable
private fun EditorProgressLayer(
    element: EditorElement,
    x: Dp,
    y: Dp,
    device: BandDevice?
) {
    val value = when (element.preview) {
        "Battery percent" -> (device?.batteryPercentage ?: 0).coerceIn(0, 100) / 100f
        else -> 0f
    }
    Canvas(Modifier.padding(start = x, top = y).size(85.dp, 28.dp)) {
        if (element.type == EditorElementType.LINE_PROGRESS) {
            drawLine(Color.DarkGray, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 4.dp.toPx())
            drawLine(element.color, Offset(0f, size.height / 2), Offset(size.width * value, size.height / 2), 4.dp.toPx())
        } else {
            val radius = size.minDimension / 2f - 3.dp.toPx()
            drawArc(Color.DarkGray, -90f, 360f, false, style = Stroke(4.dp.toPx()))
            drawArc(element.color, -90f, value * 360f, false, style = Stroke(4.dp.toPx()))
        }
    }
}

@Composable
private fun EditorReferenceImage(source: String, modifier: Modifier) {
    val context = LocalContext.current
    var bitmap by remember(source) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(source) {
        bitmap = withContext(Dispatchers.IO) {
            runCatching {
                if (source.startsWith("content://")) {
                    context.contentResolver.openInputStream(Uri.parse(source))?.use {
                        BitmapFactory.decodeStream(it)?.asImageBitmap()
                    }
                } else {
                    BitmapFactory.decodeFile(source)?.asImageBitmap()
                }
            }.getOrNull()
        }
    }
    bitmap?.let { Image(it, "Band display reference", modifier) }
}

@Composable
private fun LayerPanel(
    elements: List<EditorElement>,
    selectedId: Int,
    onSelect: (Int) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier
) {
    Column(
        modifier.background(Color(0xFF15161A), RoundedCornerShape(topStart = 18.dp, bottomStart = 18.dp)).padding(8.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Layers", color = Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f))
            ToolGlyph("×", "Close", onClose)
        }
        Spacer(Modifier.height(4.dp))
        elements.asReversed().forEach { element ->
            Row(
                Modifier.fillMaxWidth()
                    .background(
                        if (element.id == selectedId) Color(0x334BC9BF) else Color.Transparent,
                        RoundedCornerShape(8.dp)
                    )
                    .clickable { onSelect(element.id) }
                    .padding(vertical = 7.dp, horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(if (element.visible) "◉" else "○", color = Color.White, modifier = Modifier.width(23.dp))
                Text(
                    element.type.name.replace('_', ' '),
                    color = if (element.id == selectedId) Color(0xFF4BC9BF) else Color.White,
                    fontSize = 9.sp,
                    modifier = Modifier.weight(1f)
                )
                if (element.locked) Text("⌑", color = Color.Gray, fontSize = 10.sp)
            }
        }
    }
}

@Composable
private fun SelectedElementBar(
    selected: EditorElement?,
    onChangeSize: (Float) -> Unit,
    onDelete: () -> Unit,
    onExport: () -> Unit,
    onBand: () -> Unit,
    onProperties: () -> Unit,
    onAi: () -> Unit = {}
) {
    Row(
        Modifier.fillMaxWidth().height(54.dp).background(Color(0xFF18191D)).horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(selected?.type?.name ?: "Nothing selected", color = Color.White, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 4.dp))
        listOf("⚙" to onProperties, "−" to { onChangeSize(-2f) }, "+" to { onChangeSize(2f) }, "Export" to onExport, "Band" to onBand, "×" to onDelete).forEach { (label, action) ->
            Box(
                Modifier.size(44.dp).background(Color(0xFF27282D), RoundedCornerShape(10.dp)).clickable(onClick = action),
                contentAlignment = Alignment.Center
            ) { Text(label, color = Color.White, fontSize = 12.sp) }
        }
    }
}

@Composable
private fun EditorPropertiesDialog(
    element: EditorElement,
    onDismiss: () -> Unit,
    onApply: (EditorElement) -> Unit
) {
    var size by remember(element.id) { mutableStateOf(element.size.toString()) }
    var x by remember(element.id) { mutableStateOf(element.x.toString()) }
    var y by remember(element.id) { mutableStateOf(element.y.toString()) }
    var format by remember(element.id) { mutableStateOf(element.format) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Element properties") },
        text = {
            LazyColumn {
                item { Text(element.type.name.replace('_', ' '), color = Color.Gray, fontSize = 11.sp) }
                item { OutlinedTextField(x, { x = it }, label = { Text("X %") }, singleLine = true) }
                item { OutlinedTextField(y, { y = it }, label = { Text("Y %") }, singleLine = true) }
                item { OutlinedTextField(size, { size = it }, label = { Text("Size") }, singleLine = true) }
                if (element.type == EditorElementType.TIME || element.type == EditorElementType.DATE || element.type == EditorElementType.WEEKDAY) {
                    item { OutlinedTextField(format, { format = it }, label = { Text("Format") }, singleLine = true) }
                    item {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            val presets = when (element.type) {
                                EditorElementType.TIME -> listOf("H", "HH", "h", "hh", "H:mm", "HH:mm", "h:mm", "hh:mm", "HH:mm:ss")
                                EditorElementType.DATE -> listOf("DD", "DD/MM", "MM/DD", "DD MMM", "DD MMM YYYY")
                                else -> listOf("EEE", "EEEE")
                            }
                            items(presets) { preset -> TextButton(onClick = { format = preset }) { Text(preset) } }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                onApply(
                    element.copy(
                        x = x.toFloatOrNull()?.coerceIn(0f, 100f) ?: element.x,
                        y = y.toFloatOrNull()?.coerceIn(0f, 100f) ?: element.y,
                        size = size.toFloatOrNull()?.coerceIn(8f, 72f) ?: element.size,
                        format = format
                    )
                )
            }) { Text("Apply") }
        },
        dismissButton = { OutlinedButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun FullPreview(
    elements: List<EditorElement>,
    profile: DeviceProfile,
    aod: Boolean,
    onBack: () -> Unit
) {
    Column(Modifier.fillMaxSize().background(Color.Black), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            Modifier.fillMaxWidth().height(52.dp).background(Color(0xFF18191D)).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ToolGlyph("‹", "Back", onBack)
            Text(if (aod) "AOD Preview" else "Watch Face Preview", color = Color.White, fontSize = 15.sp)
        }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            WatchCanvasV2(elements, 0, profile, null, null, null, 1f, false, {}, { _, _, _ -> })
        }
    }
}

private data class DeviceProfile(val width: Int, val height: Int, val source: String)

private fun resolveProfile(device: BandDevice?): DeviceProfile {
    val model = (device?.model ?: device?.name ?: "").lowercase()
    return when {
        "band 10" in model || "smart band 10" in model -> DeviceProfile(212, 520, "Xiaomi Smart Band 10")
        "band 9" in model || "smart band 9" in model -> DeviceProfile(192, 490, "Xiaomi Smart Band 9")
        else -> DeviceProfile(192, 490, "Runtime profile unavailable — verify target device")
    }
}

private fun serializeElements(elements: List<EditorElement>): String =
    elements.joinToString(prefix = "[", postfix = "]") { e ->
        "{\"id\":${e.id},\"type\":\"${e.type.name}\",\"preview\":\"${jsonEscape(e.preview)}\",\"x\":${e.x},\"y\":${e.y},\"size\":${e.size},\"visible\":${e.visible},\"locked\":${e.locked}}"
    }

private fun jsonEscape(value: String): String =
    value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")

private fun livePreview(type: EditorElementType, device: BandDevice?): String = when (type) {
    EditorElementType.TIME -> java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
    EditorElementType.DATE -> java.text.SimpleDateFormat("dd MMM", java.util.Locale.getDefault()).format(java.util.Date())
    EditorElementType.WEEKDAY -> java.text.SimpleDateFormat("EEE", java.util.Locale.getDefault()).format(java.util.Date())
    EditorElementType.HEART_RATE -> device?.heartRate?.let { "♥ $it" } ?: "♥"
    EditorElementType.BATTERY -> device?.batteryPercentage?.let { "$it%" } ?: "▣"
    EditorElementType.SPO2 -> "O₂"
    EditorElementType.STEPS -> "↟"
    EditorElementType.CALORIES -> "Cal"
    EditorElementType.DISTANCE -> "↗"
    EditorElementType.SLEEP -> "☾"
    EditorElementType.WEATHER -> "⌂"
    EditorElementType.TEXT -> "Text"
    EditorElementType.IMAGE -> "Image"
    EditorElementType.DIGITAL_NUMBER -> "NUMBER"
    EditorElementType.ANALOG_CLOCK -> ""
    EditorElementType.ARC_PROGRESS -> ""
    EditorElementType.LINE_PROGRESS -> ""
    EditorElementType.CONTAINER -> ""
    EditorElementType.CIRCLE -> "○"
    EditorElementType.RECTANGLE -> "□"
    EditorElementType.LINE -> "—"
    EditorElementType.ARC -> "◔"
}

private fun renderElementValue(element: EditorElement, device: BandDevice?): String = when (element.type) {
    EditorElementType.TIME -> {
        val format = element.format.ifBlank { "HH:mm" }
        java.text.SimpleDateFormat(format, java.util.Locale.getDefault()).format(java.util.Date())
    }
    EditorElementType.DATE -> java.text.SimpleDateFormat(
        when (element.format) {
            "DD/MM" -> "dd/MM"
            "MM/DD" -> "MM/dd"
            "DD MMM" -> "dd MMM"
            "DD MMM YYYY" -> "dd MMM yyyy"
            else -> "dd"
        },
        java.util.Locale.getDefault()
    ).format(java.util.Date())
    EditorElementType.WEEKDAY -> java.text.SimpleDateFormat(
        if (element.format == "Monday") "EEEE" else "EEE",
        java.util.Locale.getDefault()
    ).format(java.util.Date())
    EditorElementType.HEART_RATE -> device?.heartRate?.let { "♥ $it" } ?: element.preview.ifBlank { "♥" }
    EditorElementType.BATTERY -> device?.batteryPercentage?.let { "$it%" } ?: element.preview.ifBlank { "▣" }
    EditorElementType.TEXT -> element.preview.ifBlank { "Text" }
    else -> element.preview.ifBlank { livePreview(element.type, device) }
}
