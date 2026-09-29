package com.miit.app

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.offset
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
import androidx.compose.material3.Slider
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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
    BRUSH("✎", "Brush"),
    LAYERS("≡", "Layers"),
    STYLE("✦", "Style"),
    AOD("☾", "AOD"),
    AI("✧", "AI"),
    EXPORT("⇩", "Export")
}

internal enum class EditorElementType {
    TIME, DATE, WEEKDAY, HEART_RATE, SPO2, STEPS, BATTERY, CALORIES,
    DISTANCE, SLEEP, WEATHER, DIGITAL_NUMBER, ANALOG_CLOCK, ANALOG_HAND, CLOCK_FACE, ARC_PROGRESS,
    LINE_PROGRESS, CONTAINER, TEXT, CIRCLE, RECTANGLE, ROUNDED_RECTANGLE, ELLIPSE, TRIANGLE, LINE, ARC, BRUSH, IMAGE
}

internal data class EditorElement(
    val id: Int,
    val type: EditorElementType,
    val preview: String,
    val x: Float = 50f,
    val y: Float = 50f,
    val size: Float = 24f,
    val width: Float = 76f,
    val height: Float = 55f,
    val color: Color = Color.White,
    val visible: Boolean = true,
    val locked: Boolean = false,
    val bold: Boolean = false,
    val alignment: String = "Center",
    val format: String = "",
    val handKind: String = "",
    val length: Float = 60f,
    val thickness: Float = 2f,
    val rotation: Float = 0f,
    val filled: Boolean = false,
    val cornerRadius: Float = 0f,
    val brushPath: String = ""
)

@Composable
fun MiitWatchFaceEditor(
    display: BandDisplay?,
    savedProject: java.io.File? = null,
    device: BandDevice?,
    onBack: () -> Unit,
    onAction: (String) -> Unit
) {
    val context = LocalContext.current
    val profile = remember(device?.model, device?.name) { resolveProfile(device) }
    val elements = remember(display?.stableId, savedProject?.absolutePath) {
        mutableStateListOf<EditorElement>().apply {
            if (savedProject != null) {
                addAll(SavedWatchfaceLoader.load(savedProject))
            } else {
                add(EditorElement(1, EditorElementType.TIME, "", 50f, 34f, 40f, format = "HH:mm"))
                add(EditorElement(2, EditorElementType.DATE, "", 50f, 50f, 17f, format = "DD MMM"))
                if (device?.batteryPercentage != null) {
                    add(EditorElement(3, EditorElementType.BATTERY, "", 50f, 63f, 16f))
                }
                if (device?.heartRate != null) {
                    add(EditorElement(4, EditorElementType.HEART_RATE, "", 50f, 72f, 16f))
                }
            }
        }
    }
    var nextId by remember(display?.stableId, savedProject?.absolutePath) { mutableIntStateOf(elements.maxOfOrNull { it.id }?.plus(1) ?: 1) }
    var selectedId by remember { mutableIntStateOf(0) }
    var selectedTool by remember { mutableStateOf(ToolCategory.ADD) }
    var previewMode by remember { mutableStateOf(false) }
    var aodEnabled by remember(savedProject?.absolutePath) { mutableStateOf(savedProject?.let { WatchfaceProjectStore.readAod(it) } ?: false) }
    var editingTextId by remember { mutableIntStateOf(0) }
    var sourcePicker by remember { mutableStateOf(false) }
    var imageTarget by remember { mutableIntStateOf(0) }
    var referencePath by remember(display?.stableId) { mutableStateOf(display?.previewPath) }
    var layersOpen by remember { mutableStateOf(false) }
    var referenceOpacity by remember { mutableStateOf(0.65f) }
    var brushSize by remember { mutableStateOf(6f) }
    var brushColor by remember { mutableStateOf(Color.White) }
    var colorMixerOpen by remember { mutableStateOf(false) }
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

    fun applyLayerAction(id: Int, action: String) {
        val index = elements.indexOfFirst { it.id == id }
        if (index < 0) return
        snapshotBeforeChange()
        when (action) {
            "hide" -> elements[index] = elements[index].copy(visible = !elements[index].visible)
            "lock" -> elements[index] = elements[index].copy(locked = !elements[index].locked)
            "delete" -> {
                elements.removeAt(index)
                selectedId = elements.firstOrNull()?.id ?: 0
            }
            "front" -> {
                val item = elements.removeAt(index)
                elements += item
                selectedId = item.id
            }
            "back" -> {
                val item = elements.removeAt(index)
                elements.add(0, item)
                selectedId = item.id
            }
            "up" -> {
                if (index < elements.lastIndex) {
                    val item = elements.removeAt(index)
                    elements.add(index + 1, item)
                    selectedId = item.id
                }
            }
            "down" -> {
                if (index > 0) {
                    val item = elements.removeAt(index)
                    elements.add(index - 1, item)
                    selectedId = item.id
                }
            }
        }
    }

    fun addElement(type: EditorElementType) {
        snapshotBeforeChange()
        if (type == EditorElementType.ANALOG_CLOCK) {
            val faceId = nextId++
            val hourId = nextId++
            val minuteId = nextId++
            val secondId = nextId++
            elements += EditorElement(faceId, EditorElementType.CLOCK_FACE, "", 50f, 48f, 0f, thickness = 2f, width = 100f, height = 100f)
            elements += EditorElement(hourId, EditorElementType.ANALOG_HAND, "", 50f, 48f, 0f, color = Color.White, handKind = "Hour", length = 36f, thickness = 5f)
            elements += EditorElement(minuteId, EditorElementType.ANALOG_HAND, "", 50f, 48f, 0f, color = Color.White, handKind = "Minute", length = 52f, thickness = 3.5f)
            elements += EditorElement(secondId, EditorElementType.ANALOG_HAND, "", 50f, 48f, 0f, color = Color(0xFFFF5B5B), handKind = "Second", length = 60f, thickness = 1.5f)
            selectedId = hourId
            selectedTool = ToolCategory.DATA
            return
        }
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
        selectedTool = toolForElement(type)
        colorMixerOpen = false
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
        Spacer(Modifier.height(8.dp))
        PhoneCameraSafeArea()
        Spacer(Modifier.height(6.dp))
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

        val selectedElement = elements.firstOrNull { it.id == selectedId }

        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            HorizontalToolBar(
                selected = selectedTool,
                onSelect = {
                    selectedTool = it
                    colorMixerOpen = false
                },
                modifier = Modifier.fillMaxWidth().height(60.dp)
            )

            Box(
                Modifier
                    .fillMaxWidth()
                    .height(58.dp)
                    .background(Color(0xFF18191D), RoundedCornerShape(14.dp))
            ) {
                if (selectedElement != null) {
                    ElementContextBar(
                        element = selectedElement,
                        colorMixerOpen = colorMixerOpen,
                        onToggleColorMixer = { colorMixerOpen = !colorMixerOpen },
                        onColorChange = { color ->
                            val index = elements.indexOfFirst { it.id == selectedElement.id }
                            if (index >= 0 && !selectedElement.locked) elements[index] = selectedElement.copy(color = color)
                        },
                        onOpacityChange = { alpha ->
                            val index = elements.indexOfFirst { it.id == selectedElement.id }
                            if (index >= 0 && !selectedElement.locked) {
                                elements[index] = selectedElement.copy(
                                    color = selectedElement.color.copy(alpha = alpha)
                                )
                            }
                        },
                        onFillToggle = {
                            val index = elements.indexOfFirst { it.id == selectedElement.id }
                            if (index >= 0 && !selectedElement.locked) {
                                elements[index] = selectedElement.copy(
                                    filled = !selectedElement.filled
                                )
                            }
                        },
                        onSizeChange = { size ->
                            val index = elements.indexOfFirst { it.id == selectedElement.id }
                            if (index >= 0 && !selectedElement.locked) {
                                elements[index] = selectedElement.copy(
                                    size = size,
                                    thickness = if (selectedElement.type == EditorElementType.BRUSH) size
                                    else selectedElement.thickness
                                )
                            }
                        },
                        onModify = { transform ->
                            val index = elements.indexOfFirst { it.id == selectedElement.id }
                            if (index >= 0 && !selectedElement.locked) {
                                elements[index] = transform(elements[index])
                            }
                        },
                        onProperties = { propertiesOpen = true },
                        onDelete = {
                            snapshotBeforeChange()
                            elements.removeAll { it.id == selectedElement.id }
                            selectedId = 0
                            colorMixerOpen = false
                        }
                    )
                } else {
                    Row(
                        Modifier.fillMaxSize().padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Select an element to edit",
                            color = Color(0xFFB0B4BE),
                            fontSize = 9.sp,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            profile.width.toString() + " × " + profile.height.toString() + " px",
                            color = Color(0xFF6F7480),
                            fontSize = 8.sp
                        )
                    }
                }
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(top = 4.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                SubToolBar(
                    category = selectedTool,
                    selected = selectedElement,
                    aodEnabled = aodEnabled,
                    onAodChange = { aodEnabled = it },
                    brushSize = brushSize,
                    brushColor = brushColor,
                    onBrushSizeChange = { brushSize = it },
                    onBrushColorChange = { brushColor = it },
                    onAdd = ::addElement,
                    selectHandByName = { hand ->
                        val idx = elements.indexOfFirst {
                            it.type == EditorElementType.ANALOG_HAND && it.handKind == hand
                        }
                        if (idx >= 0) selectedId = elements[idx].id
                    },
                    onAddSource = { name ->
                        val source = MiCreateCatalog.band9Sources.firstOrNull { it.name == name }
                        val id = nextId++
                        elements += EditorElement(
                            id,
                            EditorElementType.DIGITAL_NUMBER,
                            source?.name ?: name,
                            50f,
                            50f,
                            22f,
                            format = source?.idFprj ?: "0"
                        )
                        selectedId = id
                    },
                    onPickImage = {
                        val id = if (selectedId != 0) selectedId else nextId++
                        if (selectedId == 0) {
                            elements += EditorElement(id, EditorElementType.IMAGE, "Demo image", 50f, 50f, 24f)
                            selectedId = id
                        }
                        imageTarget = id
                        imagePicker.launch(arrayOf("image/*"))
                    },
                    onReference = {
                        imageTarget = -1
                        imagePicker.launch(arrayOf("image/*"))
                    },
                    onSourcePicker = { sourcePicker = true },
                    onModifySelected = { transform ->
                        val index = elements.indexOfFirst { it.id == selectedId }
                        if (index >= 0 && !elements[index].locked) {
                            elements[index] = transform(elements[index])
                        }
                    },
                    onEditText = {
                        if (selectedElement?.type == EditorElementType.TEXT) editingTextId = selectedId
                    },
                    onExport = { onAction("export") },
                    onBand = { onAction("band") },
                    onAi = { onAction("aiArrange") },
                    onLayerAction = { action ->
                        if (selectedId != 0) applyLayerAction(selectedId, action)
                    },
                    modifier = Modifier.width(84.dp).fillMaxHeight()
                )

                Box(
                    Modifier.weight(1f).fillMaxHeight()
                ) {
                    WatchCanvasV2(
                        elements = elements,
                        selectedId = selectedId,
                        profile = profile,
                        display = display,
                        device = device,
                        referencePath = referencePath,
                        referenceOpacity = referenceOpacity,
                        metadataOnly = display != null,
                        onSelect = {
                            selectedId = it
                            selectedTool = toolForElement(elements.firstOrNull { element -> element.id == it }?.type)
                            colorMixerOpen = false
                        },
                        onMove = { id, dx, dy ->
                            val index = elements.indexOfFirst { it.id == id }
                            if (index >= 0 && !elements[index].locked) {
                                val current = elements[index]
                                elements[index] = current.copy(
                                    x = (current.x + dx).coerceIn(0f, 100f),
                                    y = (current.y + dy).coerceIn(0f, 100f)
                                )
                            }
                        },
                        brushMode = selectedTool == ToolCategory.BRUSH,
                        brushSize = brushSize,
                        brushColor = brushColor,
                        onBrushStroke = { points ->
                            if (points.size >= 2) {
                                snapshotBeforeChange()
                                val path = points.joinToString(";") {
                                    it.first.toString() + "," + it.second.toString()
                                }
                                val first = points.first()
                                val id = nextId++
                                elements += EditorElement(
                                    id = id,
                                    type = EditorElementType.BRUSH,
                                    preview = "Brush stroke",
                                    x = first.first,
                                    y = first.second,
                                    size = brushSize,
                                    width = 100f,
                                    height = 100f,
                                    color = brushColor,
                                    thickness = brushSize,
                                    brushPath = path
                                )
                                selectedId = id
                            }
                        },
                        onResize = { id, dx, dy ->
                            val index = elements.indexOfFirst { it.id == id }
                            if (index >= 0 && !elements[index].locked) {
                                val current = elements[index]
                                elements[index] = current.copy(
                                    width = (current.width + dx / 1.66f).coerceIn(10f, 180f),
                                    height = (current.height + dy / 4.08f).coerceIn(10f, 180f)
                                )
                            }
                        },
                        onRotate = { id, delta ->
                            val index = elements.indexOfFirst { it.id == id }
                            if (index >= 0 && !elements[index].locked) {
                                elements[index] = elements[index].copy(
                                    rotation = normalizeAngle(elements[index].rotation + delta)
                                )
                            }
                        },
                        onDeleteElement = { id ->
                            snapshotBeforeChange()
                            elements.removeAll { it.id == id }
                            if (selectedId == id) selectedId = 0
                            colorMixerOpen = false
                        },
                        onInteractionEnd = {
                            selectedId = 0
                            colorMixerOpen = false
                        }
                    )
                }

                Box(
                    Modifier.width(44.dp).fillMaxHeight()
                ) {
                    LayerDockButton(
                        onClick = { layersOpen = !layersOpen },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 8.dp)
                    )
                    if (layersOpen) {
                        LayerPanel(
                            elements = elements,
                            selectedId = selectedId,
                            onSelect = {
                                selectedId = it
                                selectedTool = toolForElement(elements.firstOrNull { element -> element.id == it }?.type)
                                colorMixerOpen = false
                            },
                            onAction = { id, action -> applyLayerAction(id, action) },
                            onClose = { layersOpen = false },
                            modifier = Modifier
                                .align(Alignment.Center)
                                .width(214.dp)
                        )
                    }
                }
            }
        }

    }
}

@Composable
private fun HorizontalToolBar(
    selected: ToolCategory,
    onSelect: (ToolCategory) -> Unit,
    modifier: Modifier
) {
    Row(
        modifier
            .background(Color(0xFF121317))
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        ToolCategory.values()
            .filterNot { it == ToolCategory.LAYERS }
            .forEach { tool ->
                ToolCell(tool.icon, tool.title, selected == tool, onClick = { onSelect(tool) })
            }
    }
}

private fun isContextualElement(type: EditorElementType): Boolean = true

private fun normalizeAngle(value: Float): Float {
    var result = value % 360f
    if (result < 0f) result += 360f
    return result
}

private fun elementScaledWidth(element: EditorElement, base: Dp): Dp =
    base * (element.width / 76f).coerceIn(0.1f, 4f)

private fun elementScaledHeight(element: EditorElement, base: Dp): Dp =
    base * (element.height / 55f).coerceIn(0.1f, 4f)
@Composable
private fun ElementContextBar(
    element: EditorElement,
    colorMixerOpen: Boolean,
    onToggleColorMixer: () -> Unit,
    onColorChange: (Color) -> Unit,
    onOpacityChange: (Float) -> Unit,
    onFillToggle: () -> Unit,
    onSizeChange: (Float) -> Unit,
    onModify: (((EditorElement) -> EditorElement)) -> Unit,
    onProperties: () -> Unit,
    onDelete: () -> Unit
) {
    val hsv = remember(element.id, element.color) {
        FloatArray(3).also {
            android.graphics.Color.colorToHSV(element.color.toArgb(), it)
        }
    }
    var hue by remember(element.id, element.color) { mutableStateOf(hsv[0]) }
    var saturation by remember(element.id, element.color) { mutableStateOf(hsv[1]) }
    var value by remember(element.id, element.color) { mutableStateOf(hsv[2]) }

    fun applyColor() {
        onColorChange(Color.hsv(hue, saturation, value, element.color.alpha))
    }

    val isShape = element.type in setOf(
        EditorElementType.CIRCLE,
        EditorElementType.RECTANGLE,
        EditorElementType.ROUNDED_RECTANGLE,
        EditorElementType.ELLIPSE,
        EditorElementType.TRIANGLE
    )
    val isText = element.type == EditorElementType.TEXT
    val isBrush = element.type == EditorElementType.BRUSH

    Box(
        Modifier
            .fillMaxWidth()
            .height(56.dp)
            .background(Color(0xFF18191D))
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(56.dp)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            ContextObjectChip(
                icon = when {
                    isBrush -> "✎"
                    isText -> "T"
                    isShape -> "◇"
                    else -> "•"
                },
                label = element.type.name.replace('_', ' ')
            )
            ContextIcon(
                icon = "◉",
                label = "Color",
                active = colorMixerOpen,
                tint = element.color,
                onClick = onToggleColorMixer
            )
            if (isShape) {
                ContextIcon(
                    icon = if (element.filled) "●" else "○",
                    label = if (element.filled) "Fill" else "Stroke",
                    active = element.filled,
                    onClick = onFillToggle
                )
            }
            if (isBrush) {
                ContextIcon(
                    icon = "⌁",
                    label = "Size",
                    active = false,
                    onClick = {
                        onSizeChange((element.thickness + 2f).coerceIn(2f, 40f))
                    }
                )
            }
            if (isText) {
                ContextIcon(
                    icon = "B",
                    label = "Bold",
                    active = element.bold,
                    onClick = { onModify { it.copy(bold = !it.bold) } }
                )
                ContextIcon(
                    icon = when (element.alignment) {
                        "Left" -> "≪"
                        "Right" -> "≫"
                        else -> "≡"
                    },
                    label = "Align",
                    active = false,
                    onClick = {
                        onModify {
                            when (it.alignment) {
                                "Left" -> it.copy(alignment = "Center")
                                "Center" -> it.copy(alignment = "Right")
                                else -> it.copy(alignment = "Left")
                            }
                        }
                    }
                )
            }
            ContextIcon(
                icon = "↻",
                label = "Rotate",
                active = false,
                onClick = { onModify { it.copy(rotation = normalizeAngle(it.rotation + 15f)) } }
            )
            ContextIcon(
                icon = "⚙",
                label = "Edit",
                active = false,
                onClick = onProperties
            )
            ContextIcon(
                icon = "×",
                label = "Delete",
                active = false,
                destructive = true,
                onClick = onDelete
            )
        }

        if (colorMixerOpen) {
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .offset(y = 56.dp)
                    .padding(horizontal = 10.dp)
                    .fillMaxWidth()
                    .background(Color(0xF0202126), RoundedCornerShape(18.dp))
                    .border(1.dp, Color(0x33454A57), RoundedCornerShape(18.dp))
                    .padding(10.dp)
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier
                            .size(30.dp)
                            .background(element.color, RoundedCornerShape(9.dp))
                            .border(1.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(9.dp))
                    )
                    Text(
                        "Color mixer",
                        color = Color.White,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                    Spacer(Modifier.weight(1f))
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        listOf(
                            Color.White, Color.Black, Color(0xFFFF5B5B), Color(0xFFFFB547),
                            Color(0xFFE5E76B), Color(0xFF58D68D), Color(0xFF55E6E6),
                            Color(0xFF55B7FF), Color(0xFF9D7BFF), Color(0xFFFF75C3)
                        ).forEach { swatch ->
                            Box(
                                Modifier
                                    .size(18.dp)
                                    .background(swatch, androidx.compose.foundation.shape.CircleShape)
                                    .border(
                                        1.dp,
                                        if (swatch.toArgb() == element.color.toArgb())
                                            Color.White else Color.Transparent,
                                        androidx.compose.foundation.shape.CircleShape
                                    )
                                    .clickable {
                                        val hsvValue = FloatArray(3)
                                        android.graphics.Color.colorToHSV(swatch.toArgb(), hsvValue)
                                        hue = hsvValue[0]
                                        saturation = hsvValue[1]
                                        value = hsvValue[2]
                                        applyColor()
                                    }
                            )
                        }
                    }
                }
                Spacer(Modifier.height(5.dp))
                Text("Hue", color = Color(0xFF9EA3AE), fontSize = 8.sp)
                Slider(
                    value = hue,
                    onValueChange = { hue = it; applyColor() },
                    valueRange = 0f..360f,
                    modifier = Modifier.fillMaxWidth().height(22.dp)
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("S", color = Color(0xFF9EA3AE), fontSize = 8.sp, modifier = Modifier.width(10.dp))
                    Slider(
                        value = saturation,
                        onValueChange = { saturation = it; applyColor() },
                        valueRange = 0f..1f,
                        modifier = Modifier.weight(1f).height(22.dp)
                    )
                    Text("V", color = Color(0xFF9EA3AE), fontSize = 8.sp, modifier = Modifier.width(10.dp))
                    Slider(
                        value = value,
                        onValueChange = { value = it; applyColor() },
                        valueRange = 0f..1f,
                        modifier = Modifier.weight(1f).height(22.dp)
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Opacity", color = Color(0xFF9EA3AE), fontSize = 8.sp, modifier = Modifier.width(42.dp))
                    Slider(
                        value = element.color.alpha,
                        onValueChange = onOpacityChange,
                        valueRange = 0f..1f,
                        modifier = Modifier.weight(1f).height(22.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun ContextObjectChip(icon: String, label: String) {
    Row(
        Modifier
            .background(Color(0xFF25262C), RoundedCornerShape(12.dp))
            .padding(horizontal = 9.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(21.dp)
                .background(Color(0xFF31343C), RoundedCornerShape(7.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text(icon, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
        Text(
            label,
            color = Color(0xFFD6D9E0),
            fontSize = 8.sp,
            modifier = Modifier.padding(start = 6.dp)
        )
    }
}

@Composable
private fun ContextIcon(
    icon: String,
    label: String,
    active: Boolean,
    tint: Color = Color.White,
    destructive: Boolean = false,
    onClick: () -> Unit
) {
    Column(
        Modifier
            .width(48.dp)
            .clickable(onClick = onClick)
            .padding(vertical = 3.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier
                .size(34.dp)
                .background(
                    if (active) Color(0xFF343843) else Color.Transparent,
                    RoundedCornerShape(11.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                icon,
                color = if (destructive) Color(0xFFFF6B6B) else tint,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
        Text(
            label,
            color = if (destructive) Color(0xFFFF8282) else Color(0xFFA4A8B2),
            fontSize = 7.sp,
            maxLines = 1
        )
    }
}

@Composable
private fun SubToolBar(
    category: ToolCategory,
    selected: EditorElement?,
    aodEnabled: Boolean,
    onAodChange: (Boolean) -> Unit,
    brushSize: Float,
    brushColor: Color,
    onBrushSizeChange: (Float) -> Unit,
    onBrushColorChange: (Color) -> Unit,
    onAdd: (EditorElementType) -> Unit,
    onAddSource: (String) -> Unit,
    selectHandByName: (String) -> Unit,
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
            SubAction("◇", "Shapes") { onAdd(EditorElementType.RECTANGLE) },
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
        ToolCategory.DATA -> {
            if (selected?.type == EditorElementType.ANALOG_HAND) {
                listOf(
                    SubAction("H", selected.handKind.ifBlank { "Hand" }) { },
                    SubAction("L+", "Length+") { onModifySelected { it.copy(length = (it.length + 5f).coerceAtMost(95f)) } },
                    SubAction("L−", "Length−") { onModifySelected { it.copy(length = (it.length - 5f).coerceAtLeast(10f)) } },
                    SubAction("T+", "Thick+") { onModifySelected { it.copy(thickness = (it.thickness + 0.5f).coerceAtMost(10f)) } },
                    SubAction("T−", "Thick−") { onModifySelected { it.copy(thickness = (it.thickness - 0.5f).coerceAtLeast(0.5f)) } },
                    SubAction("W", "White") { onModifySelected { it.copy(color = Color.White) } },
                    SubAction("R", "Red") { onModifySelected { it.copy(color = Color(0xFFFF5B5B)) } },
                    SubAction("B", "Blue") { onModifySelected { it.copy(color = Color(0xFF55B7FF)) } },
                    SubAction("H", "Hour") { selectHandByName("Hour") },
                    SubAction("M", "Minute") { selectHandByName("Minute") },
                    SubAction("S", "Second") { selectHandByName("Second") }
                )
            } else if (selected?.type == EditorElementType.ARC_PROGRESS || selected?.type == EditorElementType.ARC) {
                listOf(
                    SubAction("T+", "Thick+") { onModifySelected { it.copy(thickness = (it.thickness + 0.5f).coerceAtMost(12f)) } },
                    SubAction("T−", "Thick−") { onModifySelected { it.copy(thickness = (it.thickness - 0.5f).coerceAtLeast(0.5f)) } },
                    SubAction("S", "Start−") { onModifySelected { it.copy(rotation = it.rotation - 10f) } },
                    SubAction("E", "End+") { onModifySelected { it.copy(rotation = it.rotation + 10f) } },
                    SubAction("W", "White") { onModifySelected { it.copy(color = Color.White) } },
                    SubAction("C", "Cyan") { onModifySelected { it.copy(color = Color(0xFF55E6E6)) } }
                )
            } else if (selected?.type == EditorElementType.LINE_PROGRESS || selected?.type == EditorElementType.LINE) {
                listOf(
                    SubAction("L+", "Length+") { onModifySelected { it.copy(length = (it.length + 5f).coerceAtMost(100f)) } },
                    SubAction("L−", "Length−") { onModifySelected { it.copy(length = (it.length - 5f).coerceAtLeast(10f)) } },
                    SubAction("T+", "Thick+") { onModifySelected { it.copy(thickness = (it.thickness + 0.5f).coerceAtMost(12f)) } },
                    SubAction("T−", "Thick−") { onModifySelected { it.copy(thickness = (it.thickness - 0.5f).coerceAtLeast(0.5f)) } },
                    SubAction("↻", "Rotate") { onModifySelected { it.copy(rotation = (it.rotation + 15f) % 360f) } },
                    SubAction("W", "White") { onModifySelected { it.copy(color = Color.White) } }
                )
            } else {
                listOf(
                    SubAction("Src", "Source") { onSourcePicker() },
                    SubAction("12", "Digital") { onAdd(EditorElementType.DIGITAL_NUMBER) },
                    SubAction("H", "Hour") { onAddSource("Hour") },
                    SubAction("M", "Minute") { onAddSource("Minute") },
                    SubAction("S", "Second") { onAddSource("Second") },
                    SubAction("♥", "Heart") { onAddSource("Heart rate") },
                    SubAction("O₂", "SpO₂") { onAdd(EditorElementType.SPO2) },
                    SubAction("↟", "Steps") { onAddSource("Current step count") },
                    SubAction("▣", "Battery") { onAddSource("Battery percent") },
                    SubAction("Cal", "Calories") { onAddSource("Active Calorie") },
                    SubAction("%", "Goal") { onAddSource("Current step (percent)") },
                    SubAction("☾", "Sleep") { onAddSource("Sleep score") },
                    SubAction("⚡", "Charge") { onAddSource("BT connection status") },
                    SubAction("°", "Temp") { onAddSource("Weather temp (C)") },
                    SubAction("☁", "Weather") { onAddSource("Weather type (icon)") }
                )
            }
        }
        ToolCategory.SHAPE -> listOf(
            SubAction("○", "Circle") { onAdd(EditorElementType.CIRCLE) },
            SubAction("▭", "Round") { onAdd(EditorElementType.ROUNDED_RECTANGLE) },
            SubAction("□", "Rect") { onAdd(EditorElementType.RECTANGLE) },
            SubAction("⬭", "Ellipse") { onAdd(EditorElementType.ELLIPSE) },
            SubAction("△", "Triangle") { onAdd(EditorElementType.TRIANGLE) },
            SubAction("／", "Line") { onAdd(EditorElementType.LINE) },
            SubAction("◔", "Arc") { onAdd(EditorElementType.ARC) }
        )
        ToolCategory.MEDIA -> listOf(
            SubAction("▧", "Photo") { onPickImage() },
            SubAction("◎", "Reference") { onReference() }
        )
        ToolCategory.BRUSH -> listOf(
            SubAction("✎", "Brush") { },
            SubAction("2", "2 px") { onBrushSizeChange(2f) },
            SubAction("4", "4 px") { onBrushSizeChange(4f) },
            SubAction("6", "6 px") { onBrushSizeChange(6f) },
            SubAction("10", "10 px") { onBrushSizeChange(10f) },
            SubAction("16", "16 px") { onBrushSizeChange(16f) },
            SubAction("24", "24 px") { onBrushSizeChange(24f) },
            SubAction("W", "White") { onBrushColorChange(Color.White) },
            SubAction("K", "Black") { onBrushColorChange(Color.Black) },
            SubAction("R", "Red") { onBrushColorChange(Color(0xFFFF5252)) },
            SubAction("O", "Orange") { onBrushColorChange(Color(0xFFFF9800)) },
            SubAction("Y", "Yellow") { onBrushColorChange(Color(0xFFFFD740)) },
            SubAction("G", "Green") { onBrushColorChange(Color(0xFF69F0AE)) },
            SubAction("C", "Cyan") { onBrushColorChange(Color(0xFF40C4FF)) },
            SubAction("B", "Blue") { onBrushColorChange(Color(0xFF536DFE)) },
            SubAction("P", "Purple") { onBrushColorChange(Color(0xFFB388FF)) },
            SubAction("M", "Pink") { onBrushColorChange(Color(0xFFFF4081)) }
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

    Column(
        modifier
            .fillMaxHeight()
            .background(Color(0xFF15161A), RoundedCornerShape(16.dp))
            .border(1.dp, Color(0x223F4652), RoundedCornerShape(16.dp))
            .padding(horizontal = 5.dp, vertical = 7.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(category.name.replace('_', ' '), color = Color(0xFF8B91A0), fontSize = 8.sp, maxLines = 1)
        Spacer(Modifier.height(5.dp))
        androidx.compose.foundation.lazy.LazyColumn(
            verticalArrangement = Arrangement.spacedBy(4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxSize()
        ) {
            items(items.size) { index ->
                val action = items[index]
                VerticalToolCell(action.icon, action.title, action.onClick)
            }
        }
    }
}

@Composable
private fun VerticalToolCell(icon: String, title: String, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier.size(40.dp).background(Color(0xFF24262C), RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text(icon, color = Color(0xFFE2E5EA), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
        Text(title, color = Color(0xFFA7ABB5), fontSize = 7.sp, maxLines = 1)
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
    val sources = MiCreateCatalog.band9Sources.filter { it.idFprj != "0" }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Digital data source") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                items(sources) { source ->
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable { onSelect(source.name) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        DigitalSourcePreview(source.name)
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(source.name, color = Color.White, fontSize = 11.sp)
                            Text(
                                source.description.ifBlank { source.idFprj },
                                color = Color.Gray,
                                fontSize = 8.sp
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

@Composable
private fun DigitalSourcePreview(name: String) {
    val sample = when (name) {
        "Hour" -> "14"
        "Hour Low" -> "4"
        "Hour High" -> "1"
        "Minute" -> "37"
        "Minute Low" -> "7"
        "Minute High" -> "3"
        "Second" -> "52"
        "Second Low" -> "2"
        "Second High" -> "5"
        "Day" -> "26"
        "Day Low" -> "6"
        "Day High" -> "2"
        "Week" -> "MON"
        "Month" -> "09"
        "Year" -> "2026"
        "AM/PM" -> "PM"
        "Battery percent" -> "86%"
        "Heart rate" -> "72"
        "Current step count" -> "8421"
        "Current step (percent)" -> "84%"
        "Active Calorie" -> "326"
        "Sleep score" -> "88"
        "Weather temp (C)" -> "27°"
        else -> "12"
    }
    Box(
        Modifier.width(52.dp).height(34.dp)
            .background(Color.Black, RoundedCornerShape(6.dp))
            .padding(3.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(sample, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
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
        Modifier
            .width(58.dp)
            .padding(vertical = 4.dp)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier
                .size(38.dp)
                .background(
                    if (selected) Color(0xFF343843) else Color.Transparent,
                    RoundedCornerShape(13.dp)
                )
                .border(
                    1.dp,
                    if (selected) Color(0xFF8B7BFF) else Color.Transparent,
                    RoundedCornerShape(13.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                icon,
                color = if (selected) Color.White else Color(0xFFD3D6DE),
                fontSize = 19.sp,
                fontWeight = FontWeight.Bold
            )
        }
        Text(
            title,
            color = if (selected) Color.White else Color(0xFF8E929B),
            fontSize = 8.sp,
            maxLines = 1
        )
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
    onMove: (Int, Float, Float) -> Unit,
    brushMode: Boolean = false,
    brushSize: Float = 6f,
    brushColor: Color = Color.White,
    onBrushStroke: (List<Pair<Float, Float>>) -> Unit = {},
    onResize: (Int, Float, Float) -> Unit = { _, _, _ -> },
    onRotate: (Int, Float) -> Unit = { _, _ -> },
    onDeleteElement: (Int) -> Unit = {},
    onInteractionEnd: () -> Unit = {}
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
                        EditorElementType.BRUSH -> EditorBrushLayer(element, element.id == selectedId)
                        EditorElementType.IMAGE -> EditorImageLayer(element, x, y, element.id == selectedId, onSelect, onMove)
                        EditorElementType.CIRCLE, EditorElementType.RECTANGLE, EditorElementType.ROUNDED_RECTANGLE,
                        EditorElementType.ELLIPSE, EditorElementType.TRIANGLE, EditorElementType.LINE, EditorElementType.ARC ->
                            EditorShapeLayer(element, x, y, element.id == selectedId, onSelect, onMove)
                        EditorElementType.ANALOG_CLOCK -> EditorAnalogLayer(element, x, y, element.id == selectedId, onSelect, onMove)
                        EditorElementType.ANALOG_HAND -> EditorAnalogHandLayer(element, x, y, element.id == selectedId, onSelect, onMove)
                        EditorElementType.CLOCK_FACE -> EditorClockFaceLayer(element, x, y, element.id == selectedId, onSelect, onMove)
                        EditorElementType.ARC_PROGRESS, EditorElementType.LINE_PROGRESS ->
                            EditorProgressLayer(
                                element,
                                x,
                                y,
                                device,
                                element.id == selectedId,
                                onSelect,
                                onMove
                            )
                        EditorElementType.CONTAINER -> Box(
                            Modifier.padding(start = x, top = y)
                                .size(
                                    elementScaledWidth(element, 80.dp),
                                    elementScaledHeight(element, 45.dp)
                                )
                                .graphicsLayer(rotationZ = element.rotation)
                                .background(Color.Transparent, RoundedCornerShape(5.dp))
                                .pointerInput(element.id) {
                                    detectDragGestures { change, amount ->
                                        change.consume()
                                        onSelect(element.id)
                                        onMove(element.id, amount.x / 1.66f, amount.y / 4.08f)
                                    }
                                }
                        )
                        else -> EditorTextLayer(element, x, y, element.id == selectedId, onSelect, onMove, device)
                    }
                }
                elements.firstOrNull { it.id == selectedId }?.let { selected ->
                    EditorSelectionOverlay(
                        element = selected,
                        canvasWidthDp = 166.dp,
                        canvasHeightDp = ((190f * profile.height / profile.width) - 10f).dp,
                        onMove = { id, dx, dy -> onMove(id, dx / 1.66f, dy / 4.08f) },
                        onResize = onResize,
                        onRotate = onRotate,
                        onDelete = onDeleteElement,
                        onInteractionEnd = onInteractionEnd
                    )
                }
                if (metadataOnly && display?.previewPath == null) {
                    Text(
                        "No saved preview found • use Media to add a reference image",
                        color = Color.Gray,
                        fontSize = 10.sp,
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp)
                    )
                }
                if (brushMode) {
                    var stroke by remember { mutableStateOf<List<Offset>>(emptyList()) }
                    Canvas(
                        Modifier
                            .fillMaxSize()
                            .pointerInput(brushMode, brushSize, brushColor) {
                                detectDragGestures(
                                    onDragStart = { offset -> stroke = listOf(offset) },
                                    onDrag = { change, _ ->
                                        change.consume()
                                        stroke = stroke + change.position
                                    },
                                    onDragEnd = {
                                        val w = size.width.coerceAtLeast(1).toFloat()
                                        val h = size.height.coerceAtLeast(1).toFloat()
                                        val normalized = stroke.map {
                                            (it.x / w * 100f).coerceIn(0f, 100f) to
                                                (it.y / h * 100f).coerceIn(0f, 100f)
                                        }
                                        onBrushStroke(normalized)
                                        stroke = emptyList()
                                    },
                                    onDragCancel = { stroke = emptyList() }
                                )
                            }
                    ) {
                        if (stroke.isNotEmpty()) {
                            val path = androidx.compose.ui.graphics.Path().apply {
                                moveTo(stroke.first().x, stroke.first().y)
                                stroke.drop(1).forEach { lineTo(it.x, it.y) }
                            }
                            drawPath(
                                path = path,
                                color = brushColor,
                                style = Stroke(
                                    width = brushSize.dp.toPx(),
                                    cap = StrokeCap.Round,
                                    join = androidx.compose.ui.graphics.StrokeJoin.Round
                                )
                            )
                        }
                    }
                }
            }

        }
    }
}

@Composable
private fun EditorBrushLayer(
    element: EditorElement,
    selected: Boolean
) {
    val points = remember(element.brushPath) {
        element.brushPath.split(";").mapNotNull { pair ->
            val parts = pair.split(",")
            if (parts.size != 2) return@mapNotNull null
            val px = parts[0].toFloatOrNull() ?: return@mapNotNull null
            val py = parts[1].toFloatOrNull() ?: return@mapNotNull null
            px to py
        }
    }
    Canvas(Modifier.fillMaxSize()) {
        if (points.isEmpty()) return@Canvas
        val minX = points.minOf { it.first }
        val maxX = points.maxOf { it.first }
        val minY = points.minOf { it.second }
        val maxY = points.maxOf { it.second }
        val centerX = ((minX + maxX) / 2f) / 100f * size.width
        val centerY = ((minY + maxY) / 2f) / 100f * size.height
        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(points.first().first / 100f * size.width, points.first().second / 100f * size.height)
            points.drop(1).forEach { point ->
                lineTo(point.first / 100f * size.width, point.second / 100f * size.height)
            }
        }
        withTransform({
            translate(centerX, centerY)
            rotate(element.rotation)
            scale(element.width / 100f, element.height / 100f)
            translate(-centerX, -centerY)
        }) {
            if (selected) drawPath(path, color = element.color.copy(alpha = 0.22f), style = Stroke(width = (element.thickness + 5f).dp.toPx(), cap = StrokeCap.Round))
            drawPath(path, color = element.color, style = Stroke(width = element.thickness.coerceAtLeast(1f).dp.toPx(), cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
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
            .size(elementScaledWidth(element, 86.dp), elementScaledHeight(element, 64.dp))
            .graphicsLayer(rotationZ = element.rotation)
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
        bitmap?.let { Image(it, "Watch-face image", Modifier.fillMaxSize()) }
            ?: DemoImagePreview()
    }
}

@Composable
private fun DemoImagePreview() {
    Canvas(
        Modifier.fillMaxSize().background(Color(0xFF111722), RoundedCornerShape(5.dp))
    ) {
        drawRect(Color(0xFF172A45))
        drawCircle(
            Color(0xFFFFD36B),
            radius = size.minDimension * 0.16f,
            center = Offset(size.width * 0.72f, size.height * 0.24f)
        )
        val mountain = androidx.compose.ui.graphics.Path().apply {
            moveTo(0f, size.height * 0.72f)
            lineTo(size.width * 0.28f, size.height * 0.43f)
            lineTo(size.width * 0.48f, size.height * 0.66f)
            lineTo(size.width * 0.70f, size.height * 0.37f)
            lineTo(size.width, size.height * 0.70f)
            lineTo(size.width, size.height)
            lineTo(0f, size.height)
            close()
        }
        drawPath(mountain, Color(0xFF416B5B))
        drawRect(
            Color(0xFF203A38),
            topLeft = Offset(0f, size.height * 0.78f),
            size = androidx.compose.ui.geometry.Size(size.width, size.height * 0.22f)
        )
        drawLine(
            Color.White.copy(alpha = 0.85f),
            Offset(size.width * 0.12f, size.height * 0.85f),
            Offset(size.width * 0.58f, size.height * 0.85f),
            strokeWidth = 2.dp.toPx()
        )
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
            .size(elementScaledWidth(element, 82.dp), elementScaledHeight(element, 62.dp))
            .graphicsLayer(rotationZ = element.rotation)
            .pointerInput(element.id) {
                detectDragGestures(
                    onDragStart = { onSelect(element.id) },
                    onDrag = { change, amount ->
                        change.consume()
                        onMove(element.id, amount.x / 1.66f, amount.y / 4.08f)
                    }
                )
            }
    ) {
        val stroke = Stroke(element.thickness.coerceAtLeast(0.5f).dp.toPx())
        val fill = element.filled
        when (element.type) {
            EditorElementType.CIRCLE -> if (fill) drawCircle(element.color) else drawCircle(element.color, style = stroke)
            EditorElementType.ELLIPSE -> {
                if (fill) drawOval(element.color) else drawOval(element.color, style = stroke)
            }
            EditorElementType.RECTANGLE -> {
                if (fill) drawRect(element.color) else drawRect(element.color, style = stroke)
            }
            EditorElementType.ROUNDED_RECTANGLE -> {
                val r = element.cornerRadius.coerceAtLeast(2f).dp.toPx()
                if (fill) drawRoundRect(color = element.color, cornerRadius = androidx.compose.ui.geometry.CornerRadius(r, r))
                else drawRoundRect(color = element.color, cornerRadius = androidx.compose.ui.geometry.CornerRadius(r, r), style = stroke)
            }
            EditorElementType.TRIANGLE -> {
                val path = androidx.compose.ui.graphics.Path().apply {
                    moveTo(size.width / 2f, 0f)
                    lineTo(size.width, size.height)
                    lineTo(0f, size.height)
                    close()
                }
                if (fill) drawPath(path, color = element.color) else drawPath(path, color = element.color, style = stroke)
            }
            EditorElementType.LINE -> drawLine(
                element.color,
                Offset(0f, size.height / 2f),
                Offset(size.width, size.height / 2f),
                strokeWidth = element.thickness.coerceAtLeast(0.5f).dp.toPx(),
                cap = StrokeCap.Round
            )
            EditorElementType.ARC -> drawArc(element.color, -90f, 270f, false, style = stroke)
            else -> Unit
        }
        if (selected) drawRect(Color(0x663F78FF), style = Stroke(1.dp.toPx()))
    }
}


@Composable
private fun EditorClockFaceLayer(
    element: EditorElement,
    x: Dp,
    y: Dp,
    selected: Boolean,
    onSelect: (Int) -> Unit,
    onMove: (Int, Float, Float) -> Unit
) {
    Canvas(
        Modifier.padding(start = x, top = y).size(element.width.dp, element.height.dp)
            .pointerInput(element.id) {
                detectDragGestures { change, amount ->
                    change.consume()
                    onSelect(element.id)
                    onMove(element.id, amount.x / 1.66f, amount.y / 4.08f)
                }
            }
    ) {
        drawCircle(element.color, style = Stroke(element.thickness.dp.toPx()))
        if (selected) drawCircle(Color(0xFF3F78FF), style = Stroke(1.dp.toPx()))
    }
}

@Composable
private fun EditorAnalogHandLayer(
    element: EditorElement,
    x: Dp,
    y: Dp,
    selected: Boolean,
    onSelect: (Int) -> Unit,
    onMove: (Int, Float, Float) -> Unit
) {
    val now = java.util.Calendar.getInstance()
    val h = now.get(java.util.Calendar.HOUR)
    val m = now.get(java.util.Calendar.MINUTE)
    val s = now.get(java.util.Calendar.SECOND)
    val angle = when (element.handKind) {
        "Hour" -> h / 12f * 360f + m / 60f * 30f
        "Minute" -> m / 60f * 360f
        else -> s / 60f * 360f
    } + element.rotation - 90f
    Canvas(
        Modifier.padding(start = x, top = y).size(150.dp).pointerInput(element.id) {
            detectDragGestures(
                onDragStart = { onSelect(element.id) },
                onDrag = { change, amount ->
                    change.consume()
                    onMove(element.id, amount.x / 1.66f, amount.y / 4.08f)
                }
            )
        }
    ) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val length = element.length / 100f * size.minDimension / 2f
        val radians = Math.toRadians(angle.toDouble())
        val ex = cx + kotlin.math.cos(radians).toFloat() * length
        val ey = cy + kotlin.math.sin(radians).toFloat() * length
        drawLine(element.color, Offset(cx, cy), Offset(ex, ey), element.thickness.coerceAtLeast(0.5f).dp.toPx(), cap = StrokeCap.Round)
        if (selected) drawCircle(Color(0xFF3F78FF), 4.dp.toPx(), Offset(cx, cy))
    }
}

@Composable
private fun EditorAnalogLayer(
    element: EditorElement,
    x: Dp,
    y: Dp,
    selected: Boolean,
    onSelect: (Int) -> Unit,
    onMove: (Int, Float, Float) -> Unit
) {
    Canvas(
        Modifier
            .padding(start = x, top = y)
            .size(
                elementScaledWidth(element, 84.dp),
                elementScaledHeight(element, 84.dp)
            )
            .graphicsLayer(rotationZ = element.rotation)
            .pointerInput(element.id) {
                detectDragGestures(
                    onDragStart = { onSelect(element.id) },
                    onDrag = { change, amount ->
                        change.consume()
                        onMove(element.id, amount.x / 1.66f, amount.y / 4.08f)
                    }
                )
            }
    ) {
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
    device: BandDevice?,
    selected: Boolean = false,
    onSelect: (Int) -> Unit = {},
    onMove: (Int, Float, Float) -> Unit = { _, _, _ -> }
) {
    val value = when (element.preview) {
        "Battery percent" -> (device?.batteryPercentage ?: 0).coerceIn(0, 100) / 100f
        else -> 0f
    }
    Canvas(
        Modifier
            .padding(start = x, top = y)
            .size(
                elementScaledWidth(element, 92.dp),
                elementScaledHeight(element, 30.dp)
            )
            .graphicsLayer(rotationZ = element.rotation)
            .pointerInput(element.id) {
                detectDragGestures(
                    onDragStart = { onSelect(element.id) },
                    onDrag = { change, amount ->
                        change.consume()
                        onMove(element.id, amount.x / 1.66f, amount.y / 4.08f)
                    }
                )
            }
    ) {
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
private fun EditorTextLayer(
    element: EditorElement,
    x: Dp,
    y: Dp,
    selected: Boolean,
    onSelect: (Int) -> Unit,
    onMove: (Int, Float, Float) -> Unit,
    device: BandDevice?
) {
    Box(
        Modifier.padding(start = x, top = y)
            .size(elementScaledWidth(element, 104.dp), elementScaledHeight(element, 48.dp))
            .graphicsLayer(rotationZ = element.rotation)
            .pointerInput(element.id) {
                detectDragGestures { change, amount ->
                    change.consume()
                    onSelect(element.id)
                    onMove(element.id, amount.x / 1.66f, amount.y / 4.08f)
                }
            }
            .background(if (selected) Color(0x183F78FF) else Color.Transparent, RoundedCornerShape(4.dp)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            renderElementValue(element, device),
            color = element.color,
            fontSize = element.size.sp,
            fontWeight = if (element.bold || element.type == EditorElementType.TIME) FontWeight.Bold else FontWeight.Normal,
            textAlign = when (element.alignment) {
                "Left" -> TextAlign.Start
                "Right" -> TextAlign.End
                else -> TextAlign.Center
            },
            maxLines = 2,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun LayerDockButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.size(44.dp).background(Color(0xFF1B1C21), RoundedCornerShape(13.dp)).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Text("≡", color = Color.White, fontSize = 22.sp)
    }
}

@Composable
private fun EditorSelectionOverlay(
    element: EditorElement,
    canvasWidthDp: Dp,
    canvasHeightDp: Dp,
    onMove: (Int, Float, Float) -> Unit,
    onResize: (Int, Float, Float) -> Unit,
    onRotate: (Int, Float) -> Unit,
    onDelete: (Int) -> Unit,
    onInteractionEnd: () -> Unit = {}
) {
    val points = remember(element.id, element.brushPath) {
        element.brushPath.split(";").mapNotNull { pair ->
            val p = pair.split(",")
            if (p.size == 2) {
                val px = p[0].toFloatOrNull()
                val py = p[1].toFloatOrNull()
                if (px != null && py != null) px to py else null
            } else null
        }
    }
    val brush = element.type == EditorElementType.BRUSH && points.isNotEmpty()
    val left = if (brush) points.minOf { it.first } else element.x
    val top = if (brush) points.minOf { it.second } else element.y
    val base = when (element.type) {
        EditorElementType.IMAGE -> 86f to 64f
        EditorElementType.CIRCLE,
        EditorElementType.RECTANGLE,
        EditorElementType.ROUNDED_RECTANGLE,
        EditorElementType.ELLIPSE,
        EditorElementType.TRIANGLE,
        EditorElementType.LINE,
        EditorElementType.ARC -> 82f to 62f
        EditorElementType.ANALOG_CLOCK -> 78f to 78f
        EditorElementType.ANALOG_HAND -> 150f to 150f
        EditorElementType.CLOCK_FACE -> element.width.coerceAtLeast(40f) to element.height.coerceAtLeast(40f)
        EditorElementType.ARC_PROGRESS,
        EditorElementType.LINE_PROGRESS -> 85f to 28f
        EditorElementType.CONTAINER -> 80f to 45f
        EditorElementType.BRUSH -> 82f to 62f
        else -> 104f to 48f
    }
    val width = if (brush) {
        ((points.maxOf { it.first } - left).coerceAtLeast(10f) / 100f * canvasWidthDp.value).dp *
            (element.width / 100f).coerceIn(0.1f, 4f)
    } else {
        (base.first * (element.width / 76f).coerceIn(0.1f, 4f)).dp
    }
    val height = if (brush) {
        ((points.maxOf { it.second } - top).coerceAtLeast(10f) / 100f * canvasHeightDp.value).dp *
            (element.height / 100f).coerceIn(0.1f, 4f)
    } else {
        (base.second * (element.height / 55f).coerceIn(0.1f, 4f)).dp
    }
    Box(
        Modifier
            .offset(
                (left / 100f * canvasWidthDp.value).dp,
                (top / 100f * canvasHeightDp.value).dp
            )
            .size(width.coerceAtLeast(38.dp), height.coerceAtLeast(38.dp))
            .graphicsLayer(rotationZ = element.rotation)
            .border(1.dp, Color(0xFF7C8CFF), RoundedCornerShape(5.dp))
            .pointerInput(element.id, element.locked) {
                detectDragGestures(
                    onDragEnd = onInteractionEnd,
                    onDragCancel = onInteractionEnd
                ) { change, amount ->
                    change.consume()
                    if (!element.locked) onMove(element.id, amount.x, amount.y)
                }
            }
    ) {
        Text(
            elementDisplayName(element),
            color = Color(0xFFD7DAE2),
            fontSize = 6.sp,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset(y = 2.dp)
                .background(Color(0xCC111318), RoundedCornerShape(3.dp))
                .padding(horizontal = 4.dp, vertical = 2.dp)
        )
        TransformHandle(Alignment.TopStart, "×", onClick = { onDelete(element.id) })
        TransformHandle(
            Alignment.TopEnd,
            "↻",
            onDrag = { dx, dy -> onRotate(element.id, (dx - dy) * 1.2f) },
            onDragEnd = onInteractionEnd
        )
        TransformHandle(
            Alignment.BottomEnd,
            "↘",
            onDrag = { dx, dy -> onResize(element.id, dx, dy) },
            onDragEnd = onInteractionEnd
        )
    }
}

private fun toolForElement(type: EditorElementType?): ToolCategory = when (type) {
    EditorElementType.TEXT -> ToolCategory.TEXT
    EditorElementType.TIME, EditorElementType.DATE, EditorElementType.WEEKDAY,
    EditorElementType.HEART_RATE, EditorElementType.SPO2, EditorElementType.STEPS,
    EditorElementType.BATTERY, EditorElementType.CALORIES, EditorElementType.DISTANCE,
    EditorElementType.SLEEP, EditorElementType.WEATHER, EditorElementType.DIGITAL_NUMBER,
    EditorElementType.ANALOG_CLOCK, EditorElementType.ANALOG_HAND, EditorElementType.CLOCK_FACE,
    EditorElementType.ARC_PROGRESS, EditorElementType.LINE_PROGRESS -> ToolCategory.DATA
    EditorElementType.CIRCLE, EditorElementType.RECTANGLE, EditorElementType.ROUNDED_RECTANGLE,
    EditorElementType.ELLIPSE, EditorElementType.TRIANGLE, EditorElementType.LINE,
    EditorElementType.ARC, EditorElementType.CONTAINER -> ToolCategory.SHAPE
    EditorElementType.BRUSH -> ToolCategory.BRUSH
    EditorElementType.IMAGE -> ToolCategory.MEDIA
    null -> ToolCategory.ADD
}

private fun elementDisplayName(element: EditorElement): String = when (element.type) {
    EditorElementType.ANALOG_HAND -> (element.handKind.ifBlank { "Analog" }) + " hand"
    EditorElementType.DIGITAL_NUMBER -> "Digital number"
    EditorElementType.ARC_PROGRESS -> "Arc progress"
    EditorElementType.LINE_PROGRESS -> "Line progress"
    EditorElementType.ROUNDED_RECTANGLE -> "Rounded rectangle"
    EditorElementType.HEART_RATE -> "Heart rate"
    EditorElementType.SPO2 -> "SpO₂"
    else -> element.type.name.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }
}

@Composable
private fun TransformHandle(
    alignment: Alignment,
    label: String,
    onClick: (() -> Unit)? = null,
    onDrag: ((Float, Float) -> Unit)? = null,
    onDragEnd: () -> Unit = {}
) {
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .align(alignment)
                .offset(
                    x = if (alignment == Alignment.TopStart || alignment == Alignment.BottomStart) (-9).dp else 9.dp,
                    y = if (alignment == Alignment.TopStart || alignment == Alignment.TopEnd) (-9).dp else 9.dp
                )
                .size(20.dp)
                .background(Color(0xFF1E293B), androidx.compose.foundation.shape.CircleShape)
                .then(
                    if (onDrag != null) Modifier.pointerInput(label) {
                        detectDragGestures(
                            onDragEnd = onDragEnd,
                            onDragCancel = onDragEnd
                        ) { change, amount ->
                            change.consume()
                            onDrag(amount.x, amount.y)
                        }
                    } else Modifier.clickable { onClick?.invoke() }
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(label, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun LayerPanel(
    elements: List<EditorElement>,
    selectedId: Int,
    onSelect: (Int) -> Unit,
    onAction: (Int, String) -> Unit = { _, _ -> },
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
                    .padding(vertical = 5.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier.size(30.dp)
                        .background(Color(0xFF24252A), RoundedCornerShape(6.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        when (element.type) {
                            EditorElementType.BRUSH -> "✎"
                            EditorElementType.TEXT -> "T"
                            EditorElementType.IMAGE -> "▧"
                            EditorElementType.TIME, EditorElementType.DATE, EditorElementType.WEEKDAY,
                            EditorElementType.DIGITAL_NUMBER -> "12"
                            else -> "◇"
                        },
                        color = Color.White,
                        fontSize = 11.sp
                    )
                }
                Text(
                    element.type.name.replace('_', ' '),
                    color = if (element.id == selectedId) Color(0xFF4BC9BF) else Color.White,
                    fontSize = 8.sp,
                    modifier = Modifier.weight(1f).padding(start = 5.dp)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(1.dp)) {
                    LayerMiniButton(if (element.visible) "●" else "○") { onAction(element.id, "hide") }
                    LayerMiniButton(if (element.locked) "⌑" else "□") { onAction(element.id, "lock") }
                    LayerMiniButton("↑") { onAction(element.id, "front") }
                    LayerMiniButton("↓") { onAction(element.id, "back") }
                }
            }
        }
    }
}

@Composable
private fun LayerMiniButton(label: String, onClick: () -> Unit) {
    Box(
        Modifier.size(22.dp)
            .background(Color(0xFF202126), RoundedCornerShape(5.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { Text(label, color = Color.White, fontSize = 9.sp) }
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
    var length by remember(element.id) { mutableStateOf(element.length.toString()) }
    var thickness by remember(element.id) { mutableStateOf(element.thickness.toString()) }
    var rotation by remember(element.id) { mutableStateOf(element.rotation.toString()) }
    var cornerRadius by remember(element.id) { mutableStateOf(element.cornerRadius.toString()) }
    var filled by remember(element.id) { mutableStateOf(element.filled) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(element.type.name.replace('_', ' ')) },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                item { OutlinedTextField(x, { x = it }, label = { Text("X %") }, singleLine = true) }
                item { OutlinedTextField(y, { y = it }, label = { Text("Y %") }, singleLine = true) }
                item { OutlinedTextField(size, { size = it }, label = { Text("Font/size") }, singleLine = true) }
                if (element.type == EditorElementType.BRUSH) {
                    item { OutlinedTextField(thickness, { thickness = it }, label = { Text("Brush size") }, singleLine = true) }
                }
                if (element.type == EditorElementType.ANALOG_HAND) {
                    item { Text("Hand: " + element.handKind, color = Color.Gray, fontSize = 11.sp) }
                    item { OutlinedTextField(length, { length = it }, label = { Text("Length %") }, singleLine = true) }
                    item { OutlinedTextField(thickness, { thickness = it }, label = { Text("Thickness") }, singleLine = true) }
                    item { OutlinedTextField(rotation, { rotation = it }, label = { Text("Rotation offset °") }, singleLine = true) }
                }
                if (element.type in setOf(
                    EditorElementType.CIRCLE, EditorElementType.RECTANGLE, EditorElementType.ROUNDED_RECTANGLE,
                    EditorElementType.ELLIPSE, EditorElementType.TRIANGLE, EditorElementType.LINE, EditorElementType.ARC,
                    EditorElementType.ARC_PROGRESS, EditorElementType.LINE_PROGRESS, EditorElementType.CLOCK_FACE
                )) {
                    item { OutlinedTextField(thickness, { thickness = it }, label = { Text("Stroke thickness") }, singleLine = true) }
                    item { OutlinedTextField(rotation, { rotation = it }, label = { Text("Rotation °") }, singleLine = true) }
                }
                if (element.type == EditorElementType.ROUNDED_RECTANGLE) {
                    item { OutlinedTextField(cornerRadius, { cornerRadius = it }, label = { Text("Corner radius") }, singleLine = true) }
                }
                if (element.type in setOf(
                    EditorElementType.CIRCLE, EditorElementType.RECTANGLE, EditorElementType.ROUNDED_RECTANGLE,
                    EditorElementType.ELLIPSE, EditorElementType.TRIANGLE
                )) {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Filled", color = Color.White, modifier = Modifier.weight(1f))
                            TextButton(onClick = { filled = !filled }) { Text(if (filled) "ON" else "OFF") }
                        }
                    }
                }
                if (element.type == EditorElementType.TIME || element.type == EditorElementType.DATE || element.type == EditorElementType.WEEKDAY) {
                    item { OutlinedTextField(format, { format = it }, label = { Text("Format") }, singleLine = true) }
                    item {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
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
                        size = size.toFloatOrNull()?.coerceIn(0.1f, 72f) ?: element.size,
                        format = format,
                        length = length.toFloatOrNull()?.coerceIn(1f, 100f) ?: element.length,
                        thickness = thickness.toFloatOrNull()?.coerceIn(0.5f, 20f) ?: element.thickness,
                        rotation = rotation.toFloatOrNull() ?: element.rotation,
                        cornerRadius = cornerRadius.toFloatOrNull()?.coerceIn(0f, 50f) ?: element.cornerRadius,
                        filled = filled
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
    Column(
        Modifier.fillMaxSize().background(Color.Black).statusBarsPadding(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        PhoneCameraSafeArea()
        Row(
            Modifier
                .fillMaxWidth()
                .height(50.dp)
                .background(Color(0xFF0E0F12))
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ToolGlyph("‹", "Back to editor", onBack)
            Text(
                if (aod) "AOD Preview" else "Watch Face Preview",
                color = Color.White,
                fontSize = 14.sp,
                modifier = Modifier.weight(1f)
            )
            Text(
                profile.width.toString() + "×" + profile.height.toString(),
                color = Color(0xFF8A8F9A),
                fontSize = 8.sp
            )
        }
        Box(
            Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            WatchCanvasV2(
                elements = elements,
                selectedId = 0,
                profile = profile,
                display = null,
                device = null,
                referencePath = null,
                referenceOpacity = 1f,
                metadataOnly = false,
                onSelect = {},
                onMove = { _, _, _ -> }
            )
        }
    }
}

@Composable
private fun PhoneCameraSafeArea() {
    Box(
        Modifier.fillMaxWidth().height(11.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier
                .size(7.dp)
                .background(Color(0xFF050608), androidx.compose.foundation.shape.CircleShape)
                .border(1.dp, Color(0xFF252832), androidx.compose.foundation.shape.CircleShape)
        )
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
        buildString {
            append("{")
            append("\"id\":").append(e.id).append(",")
            append("\"type\":\"").append(e.type.name).append("\",")
            append("\"preview\":\"").append(jsonEscape(e.preview)).append("\",")
            append("\"x\":").append(e.x).append(",\"y\":").append(e.y).append(",")
            append("\"size\":").append(e.size).append(",\"width\":").append(e.width)
                .append(",\"height\":").append(e.height).append(",")
            append("\"color\":\"").append(e.color.value.toString(16)).append("\",")
            append("\"bold\":").append(e.bold).append(",")
            append("\"alignment\":\"").append(jsonEscape(e.alignment)).append("\",")
            append("\"format\":\"").append(jsonEscape(e.format)).append("\",")
            append("\"handKind\":\"").append(jsonEscape(e.handKind)).append("\",")
            append("\"length\":").append(e.length).append(",\"thickness\":").append(e.thickness).append(",")
            append("\"rotation\":").append(e.rotation).append(",\"filled\":").append(e.filled).append(",")
            append("\"cornerRadius\":").append(e.cornerRadius).append(",")
            append("\"visible\":").append(e.visible).append(",\"locked\":").append(e.locked).append(",")
            append("\"brushPath\":\"").append(jsonEscape(e.brushPath)).append("\"")
            append("}")
        }
    }

private fun jsonEscape(value: String): String =
    value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")

private fun livePreview(type: EditorElementType, device: BandDevice?): String = when (type) {
    EditorElementType.TIME -> java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
    EditorElementType.DATE -> java.text.SimpleDateFormat("dd MMM", java.util.Locale.getDefault()).format(java.util.Date())
    EditorElementType.WEEKDAY -> java.text.SimpleDateFormat("EEE", java.util.Locale.getDefault()).format(java.util.Date())
    EditorElementType.HEART_RATE -> (device?.heartRate ?: 72).toString() + " bpm"
    EditorElementType.SPO2 -> "98%"
    EditorElementType.STEPS -> "8,421"
    EditorElementType.BATTERY -> (device?.batteryPercentage ?: 86).toString() + "%"
    EditorElementType.CALORIES -> "326 kcal"
    EditorElementType.DISTANCE -> "4.6 km"
    EditorElementType.SLEEP -> "7h 28m"
    EditorElementType.WEATHER -> "27° • Sunny"
    EditorElementType.DIGITAL_NUMBER -> "12 345"
    EditorElementType.ANALOG_CLOCK -> "14:37:52"
    EditorElementType.ARC_PROGRESS -> "76%"
    EditorElementType.LINE_PROGRESS -> "84%"
    EditorElementType.BRUSH -> "Brush stroke"
    EditorElementType.CONTAINER -> "Container"
    EditorElementType.ANALOG_HAND -> "Hand"
    EditorElementType.CLOCK_FACE -> "Clock face"
    EditorElementType.TEXT -> "Text"
    EditorElementType.IMAGE -> "Demo image"
    EditorElementType.CIRCLE -> "Circle"
    EditorElementType.RECTANGLE -> "Rectangle"
    EditorElementType.ROUNDED_RECTANGLE -> "Rounded rectangle"
    EditorElementType.ELLIPSE -> "Oval"
    EditorElementType.TRIANGLE -> "Triangle"
    EditorElementType.LINE -> "Line"
    EditorElementType.ARC -> "Arc"
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
