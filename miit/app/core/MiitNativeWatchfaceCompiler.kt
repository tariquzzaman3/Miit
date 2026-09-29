package com.miit.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.net.Uri
import java.io.ByteArrayOutputStream
import androidx.compose.ui.graphics.toArgb
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Builds a native-looking Xiaomi Smart Band face container from the current
 * editor canvas. The first native install path intentionally rasterizes the
 * editable canvas into one full-screen image, which keeps every editor object
 * visually faithful while the native widget compiler is still being expanded.
 */
object MiitNativeWatchfaceCompiler {
    data class Target(
        val width: Int,
        val height: Int,
        val model: String?
    )

    data class Result(
        val id: String,
        val name: String,
        val bytes: ByteArray,
        val warnings: List<String>
    )

    internal fun compile(
        context: Context,
        name: String,
        target: Target,
        elements: List<EditorElement>,
        aod: Boolean
    ): Result {
        require(target.width > 0 && target.height > 0) { "Invalid watch-face target size" }
        require(
            (target.width == 192 && target.height == 490) ||
                (target.width == 212 && target.height == 520)
        ) {
            "Unsupported watch-face target " + target.width + "×" + target.height
        }
        val warnings = mutableListOf<String>()

        if (target.width != 192 || target.height != 490) {
            if (target.width != 212 || target.height != 520) {
                warnings += "Native install is currently tuned for Smart Band 9 (192×490) and Band 10 (212×520)."
            }
        }

        val safeName = name.trim().ifBlank { "MIIT Watch Face" }.take(60)
        val normalBitmap = render(context, target, elements, darkAod = false)
        val normalImage = encodeImage(normalBitmap)
        normalBitmap.recycle()

        val faceCount = if (aod) 2 else 1
        val headersEnd = MAIN_HEADER_SIZE + faceCount * FACE_HEADER_SIZE
        val normalDefinitionStart = headersEnd
        val normalDefinitionBytes = DEFINITION_SIZE * 2
        val normalPropertyBytes = ELEMENT_PROPERTY_SIZE
        val normalPropertyStart = normalDefinitionStart + normalDefinitionBytes
        // Resource 0 is the active image itself; never point a definition past its data.
        val previewOffset = normalPropertyStart + normalPropertyBytes
        val normalResourceStart = previewOffset

        val aodBitmap = if (aod) render(context, target, elements, darkAod = true) else null
        val aodImage = aodBitmap?.let { encodeImage(it) }
        aodBitmap?.recycle()

        val id = stableId(safeName, target, normalImage, aodImage)

        val normalFaceData = makeFaceData(
            definitionStart = normalDefinitionStart,
            propertyStart = normalPropertyStart,
            resourceStart = normalResourceStart,
            imageBytes = normalImage
        )

        var aodDefinitionStart = 0
        var aodFaceData: ByteArray? = null
        var aodResourceStart = 0
        if (aodImage != null) {
            aodDefinitionStart = normalResourceStart + normalImage.size
            val aodPropertyStart = aodDefinitionStart + normalDefinitionBytes
            aodResourceStart = aodPropertyStart + normalPropertyBytes
            aodFaceData = makeFaceData(
                definitionStart = aodDefinitionStart,
                propertyStart = aodPropertyStart,
                resourceStart = aodResourceStart,
                imageBytes = aodImage
            )
        }

        val mainHeader = makeMainHeader(
            id = id,
            name = safeName,
            faceCount = faceCount,
            previewOffset = previewOffset
        )
        val normalFaceHeader = makeFaceHeader(
            definitionStart = normalDefinitionStart,
            previewOffset = previewOffset
        )
        val aodFaceHeader = if (aodImage != null) {
            makeFaceHeader(
                definitionStart = aodDefinitionStart,
                previewOffset = 0
            )
        } else null

        val parts = ArrayList<ByteArray>()
        parts += mainHeader
        parts += normalFaceHeader
        if (aodFaceHeader != null) parts += aodFaceHeader
        parts += normalFaceData
        parts += normalImage
        if (aodFaceData != null && aodImage != null) {
            parts += aodFaceData
            parts += aodImage
        }

        val binary = concatenate(parts)
        val safety = MiitWatchfaceSafety.inspectPackage(id, binary)
        require(safety.safe) {
            "Generated watch-face package failed MIIT safety validation: " +
                safety.errors.joinToString("; ")
        }
        warnings += safety.warnings
        warnings += "The current native install path rasterizes the editor into a static full-screen face; on-band widgets are not yet dynamic."
        return Result(id, safeName, binary, warnings.distinct())
    }

    private const val MAIN_HEADER_SIZE = 0xA8
    private const val FACE_HEADER_SIZE = 0x58
    private const val DEFINITION_SIZE = 16
    private const val ELEMENT_PROPERTY_SIZE = 16
    private const val IMAGE_HEADER_SIZE = 12

    private fun stableId(name: String, target: Target, normalImage: ByteArray, aodImage: ByteArray?): String {
        val digestInput = ByteArrayOutputStream().apply {
            write(name.toByteArray(Charsets.UTF_8))
            write(0)
            write((target.width.toString() + "x" + target.height + "|" + target.model.orEmpty()).toByteArray(Charsets.UTF_8))
            write(0)
            write(normalImage)
            aodImage?.let {
                write(0)
                write(it)
            }
        }.toByteArray()
        val digest = MessageDigest.getInstance("SHA-256").digest(digestInput)
        var value = 0L
        for (index in 0 until 4) {
            value = (value shl 8) or (digest[index].toLong() and 0xFFL)
        }
        value = (value % 900_000_000L) + 100_000_000L
        return value.toString()
    }

    private fun makeMainHeader(
        id: String,
        name: String,
        faceCount: Int,
        previewOffset: Int
    ): ByteArray {
        val header = ByteArray(MAIN_HEADER_SIZE)
        header[0] = 0x5A
        header[1] = 0xA5.toByte()
        header[2] = 0x34
        header[3] = 0x12
        writeU32(header, 0x10, 0x800)
        writeU16(header, 0x1C, faceCount)
        writeU16(header, 0x1E, if (faceCount > 1) faceCount + 2 else 1)
        writeU32(header, 0x20, previewOffset)
        writeFixed(header, 0x28, 9, id)
        writeFixed(header, 0x68, 60, name)
        return header
    }

    private fun makeFaceHeader(
        definitionStart: Int,
        previewOffset: Int
    ): ByteArray {
        val header = ByteArray(FACE_HEADER_SIZE)
        writeU32(header, 0x04, previewOffset)

        var tableOffset = definitionStart
        writeSection(header, 0x08, 1, tableOffset)
        tableOffset += DEFINITION_SIZE

        writeSection(header, 0x10, 0, tableOffset)

        writeSection(header, 0x18, 1, tableOffset)
        tableOffset += DEFINITION_SIZE

        writeSection(header, 0x20, 0, tableOffset)
        writeSection(header, 0x28, 0, tableOffset)
        writeSection(header, 0x30, 0, tableOffset)
        writeSection(header, 0x38, 0, tableOffset)
        writeSection(header, 0x40, 0, tableOffset)
        writeSection(header, 0x48, 0, tableOffset)
        writeSection(header, 0x50, 0, tableOffset)
        return header
    }

    private fun writeSection(header: ByteArray, offset: Int, count: Int, tableOffset: Int) {
        writeU32(header, offset, count)
        writeU32(header, offset + 4, tableOffset)
    }

    private fun writeDefinition(
        buffer: ByteArray,
        offset: Int,
        index: Int,
        type: Int,
        target: Int,
        length: Int
    ) {
        writeU16(buffer, offset, index)
        buffer[offset + 3] = type.toByte()
        writeU32(buffer, offset + 8, target)
        writeU32(buffer, offset + 12, length)
    }

    private fun makeFaceData(
        definitionStart: Int,
        propertyStart: Int,
        resourceStart: Int,
        imageBytes: ByteArray
    ): ByteArray {
        val output = ByteArray(DEFINITION_SIZE * 2 + ELEMENT_PROPERTY_SIZE)

        writeDefinition(
            output,
            0,
            index = 0,
            type = 0,
            target = propertyStart,
            length = ELEMENT_PROPERTY_SIZE
        )
        writeDefinition(
            output,
            DEFINITION_SIZE,
            index = 0,
            type = 2,
            target = resourceStart,
            length = imageBytes.size
        )

        // This property describes one element: reference index/type + x/y.
        writeU24(output, DEFINITION_SIZE * 2, 0)
        output[DEFINITION_SIZE * 2 + 3] = 2
        writeU16(output, DEFINITION_SIZE * 2 + 4, 0)
        writeU16(output, DEFINITION_SIZE * 2 + 6, 0)

        return output
    }

    private fun encodeImage(bitmap: Bitmap): ByteArray {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        // Native uncompressed image blocks are BGRA bytes with a 12-byte header.
        val raw = ByteArray(pixels.size * 4)
        var out = 0
        for (pixel in pixels) {
            raw[out++] = (pixel and 0xFF).toByte()
            raw[out++] = ((pixel ushr 8) and 0xFF).toByte()
            raw[out++] = ((pixel ushr 16) and 0xFF).toByte()
            raw[out++] = ((pixel ushr 24) and 0xFF).toByte()
        }

        val block = ByteArray(IMAGE_HEADER_SIZE + raw.size)
        block[0] = 0
        block[1] = 0
        writeU16(block, 4, width)
        writeU16(block, 6, height)
        writeU32(block, 8, raw.size)
        raw.copyInto(block, IMAGE_HEADER_SIZE)
        return block
    }

    private fun render(
        context: Context,
        target: Target,
        elements: List<EditorElement>,
        darkAod: Boolean
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(target.width, target.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(android.graphics.Color.BLACK)

        val scaleX = target.width / 192f
        val scaleY = target.height / 490f
        val canvasScale = (scaleX + scaleY) / 2f

        elements.filter { it.visible }.forEach { element ->
            drawElement(context, canvas, element, target, canvasScale, darkAod)
        }
        return bitmap
    }

    private fun drawElement(
        context: Context,
        canvas: Canvas,
        element: EditorElement,
        target: Target,
        scale: Float,
        darkAod: Boolean
    ) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = element.color.toArgb()
            alpha = if (darkAod) (element.color.alpha * 180f).toInt() else (element.color.alpha * 255f).toInt()
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        val x = element.x.coerceIn(0f, 100f) / 100f * target.width
        val y = element.y.coerceIn(0f, 100f) / 100f * target.height
        val baseWidth = when (element.type) {
            EditorElementType.IMAGE -> 86f
            EditorElementType.TEXT -> 104f
            EditorElementType.ANALOG_CLOCK -> 84f
            EditorElementType.ANALOG_HAND -> 150f
            EditorElementType.CLOCK_FACE -> element.width.coerceAtLeast(40f)
            EditorElementType.ARC_PROGRESS, EditorElementType.LINE_PROGRESS -> 92f
            else -> 82f
        } * scale
        val baseHeight = when (element.type) {
            EditorElementType.IMAGE -> 64f
            EditorElementType.TEXT -> 48f
            EditorElementType.ANALOG_CLOCK -> 84f
            EditorElementType.ANALOG_HAND -> 150f
            EditorElementType.CLOCK_FACE -> element.height.coerceAtLeast(40f)
            EditorElementType.ARC_PROGRESS, EditorElementType.LINE_PROGRESS -> 30f
            else -> 62f
        } * scale
        val width = baseWidth * (element.width / 76f).coerceIn(0.1f, 4f)
        val height = baseHeight * (element.height / 55f).coerceIn(0.1f, 4f)

        canvas.save()
        canvas.rotate(element.rotation, x + width / 2f, y + height / 2f)

        when (element.type) {
            EditorElementType.IMAGE -> drawImage(context, canvas, element, x, y, width, height, paint)
            EditorElementType.TEXT,
            EditorElementType.TIME,
            EditorElementType.DATE,
            EditorElementType.WEEKDAY,
            EditorElementType.HEART_RATE,
            EditorElementType.SPO2,
            EditorElementType.STEPS,
            EditorElementType.BATTERY,
            EditorElementType.CALORIES,
            EditorElementType.DISTANCE,
            EditorElementType.SLEEP,
            EditorElementType.WEATHER,
            EditorElementType.DIGITAL_NUMBER -> drawTextElement(canvas, element, target, x, y, width, height, scale, paint)
            EditorElementType.CIRCLE -> drawCircle(canvas, paint, x, y, width, height, element.filled)
            EditorElementType.ELLIPSE -> drawOval(canvas, paint, x, y, width, height, element.filled)
            EditorElementType.RECTANGLE -> drawRect(canvas, paint, x, y, width, height, element.filled)
            EditorElementType.ROUNDED_RECTANGLE -> {
                paint.style = if (element.filled) Paint.Style.FILL else Paint.Style.STROKE
                paint.strokeWidth = element.thickness.coerceAtLeast(0.5f) * scale
                canvas.drawRoundRect(
                    RectF(x, y, x + width, y + height),
                    element.cornerRadius.coerceAtLeast(2f) * scale,
                    element.cornerRadius.coerceAtLeast(2f) * scale,
                    paint
                )
            }
            EditorElementType.TRIANGLE -> {
                paint.style = if (element.filled) Paint.Style.FILL else Paint.Style.STROKE
                paint.strokeWidth = element.thickness.coerceAtLeast(0.5f) * scale
                val path = Path().apply {
                    moveTo(x + width / 2f, y)
                    lineTo(x + width, y + height)
                    lineTo(x, y + height)
                    close()
                }
                canvas.drawPath(path, paint)
            }
            EditorElementType.LINE -> {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = element.thickness.coerceAtLeast(0.5f) * scale
                canvas.drawLine(x, y + height / 2f, x + width, y + height / 2f, paint)
            }
            EditorElementType.ARC -> {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = element.thickness.coerceAtLeast(0.5f) * scale
                canvas.drawArc(RectF(x, y, x + width, y + height), -90f, 270f, false, paint)
            }
            EditorElementType.BRUSH -> drawBrush(canvas, element, target, scale, paint)
            EditorElementType.ANALOG_CLOCK,
            EditorElementType.CLOCK_FACE -> drawClockFace(canvas, element, x, y, width, height, scale, paint)
            EditorElementType.ANALOG_HAND -> drawAnalogHand(canvas, element, x, y, width, height, scale, paint)
            EditorElementType.ARC_PROGRESS,
            EditorElementType.LINE_PROGRESS -> drawProgress(canvas, element, x, y, width, height, scale, paint)
            EditorElementType.CONTAINER -> Unit
        }

        canvas.restore()
    }

    private fun drawImage(
        context: Context,
        canvas: Canvas,
        element: EditorElement,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        paint: Paint
    ) {
        val bitmap = decodeImage(context, element.preview)
        if (bitmap != null) {
            canvas.drawBitmap(bitmap, null, RectF(x, y, x + width, y + height), paint)
            bitmap.recycle()
        } else {
            paint.style = Paint.Style.FILL
            paint.color = android.graphics.Color.rgb(27, 42, 62)
            canvas.drawRect(x, y, x + width, y + height, paint)
            paint.color = android.graphics.Color.rgb(255, 211, 107)
            canvas.drawCircle(x + width * .72f, y + height * .24f, minOf(width, height) * .16f, paint)
            paint.color = android.graphics.Color.rgb(65, 107, 91)
            val path = Path().apply {
                moveTo(x, y + height * .72f)
                lineTo(x + width * .28f, y + height * .43f)
                lineTo(x + width * .48f, y + height * .66f)
                lineTo(x + width * .70f, y + height * .37f)
                lineTo(x + width, y + height * .70f)
                lineTo(x + width, y + height)
                lineTo(x, y + height)
                close()
            }
            canvas.drawPath(path, paint)
        }
    }

    private fun decodeImage(context: Context, source: String): Bitmap? =
        runCatching {
            when {
                source.startsWith("content://") ->
                    context.contentResolver.openInputStream(Uri.parse(source))?.use(BitmapFactory::decodeStream)
                source.isNotBlank() && source != "Demo image" && source != "Image preview" ->
                    BitmapFactory.decodeFile(source)
                else -> null
            }
        }.getOrNull()

    private fun drawTextElement(
        canvas: Canvas,
        element: EditorElement,
        target: Target,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        scale: Float,
        paint: Paint
    ) {
        paint.style = Paint.Style.FILL
        paint.textSize = element.size.coerceIn(4f, 160f) * scale
        paint.typeface = if (element.bold || element.type == EditorElementType.TIME) android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT

        val value = renderValue(element)
        val metrics = paint.fontMetrics
        val baseline = y + height / 2f - (metrics.ascent + metrics.descent) / 2f
        paint.textAlign = when (element.alignment) {
            "Left" -> Paint.Align.LEFT
            "Right" -> Paint.Align.RIGHT
            else -> Paint.Align.CENTER
        }
        val tx = when (paint.textAlign) {
            Paint.Align.LEFT -> x
            Paint.Align.RIGHT -> x + width
            else -> x + width / 2f
        }
        canvas.drawText(value, tx, baseline, paint)
    }

    private fun renderValue(element: EditorElement): String {
        val now = java.util.Date()
        return when (element.type) {
            EditorElementType.TIME ->
                SimpleDateFormat(element.format.ifBlank { "HH:mm" }, Locale.getDefault()).format(now)
            EditorElementType.DATE -> SimpleDateFormat(
                when (element.format) {
                    "DD/MM" -> "dd/MM"
                    "MM/DD" -> "MM/dd"
                    "DD MMM" -> "dd MMM"
                    "DD MMM YYYY" -> "dd MMM yyyy"
                    else -> "dd MMM"
                },
                Locale.getDefault()
            ).format(now)
            EditorElementType.WEEKDAY -> SimpleDateFormat("EEE", Locale.getDefault()).format(now)
            EditorElementType.HEART_RATE -> element.preview.ifBlank { "72 bpm" }
            EditorElementType.SPO2 -> element.preview.ifBlank { "98%" }
            EditorElementType.STEPS -> element.preview.ifBlank { "8,421 steps" }
            EditorElementType.BATTERY -> element.preview.ifBlank { "86%" }
            EditorElementType.CALORIES -> element.preview.ifBlank { "326 kcal" }
            EditorElementType.DISTANCE -> element.preview.ifBlank { "4.6 km" }
            EditorElementType.SLEEP -> element.preview.ifBlank { "7h 28m" }
            EditorElementType.WEATHER -> element.preview.ifBlank { "27° • Sunny" }
            EditorElementType.DIGITAL_NUMBER -> element.preview.ifBlank { "12,345" }
            EditorElementType.TEXT -> element.preview.ifBlank { "Hello MIIT" }
            else -> element.preview.ifBlank { element.type.name.replace('_', ' ') }
        }
    }

    private fun drawCircle(canvas: Canvas, paint: Paint, x: Float, y: Float, width: Float, height: Float, filled: Boolean) {
        paint.style = if (filled) Paint.Style.FILL else Paint.Style.STROKE
        paint.strokeWidth = 2f
        canvas.drawOval(RectF(x, y, x + width, y + height), paint)
    }

    private fun drawOval(canvas: Canvas, paint: Paint, x: Float, y: Float, width: Float, height: Float, filled: Boolean) {
        paint.style = if (filled) Paint.Style.FILL else Paint.Style.STROKE
        paint.strokeWidth = 2f
        canvas.drawOval(RectF(x, y, x + width, y + height), paint)
    }

    private fun drawRect(canvas: Canvas, paint: Paint, x: Float, y: Float, width: Float, height: Float, filled: Boolean) {
        paint.style = if (filled) Paint.Style.FILL else Paint.Style.STROKE
        paint.strokeWidth = 2f
        canvas.drawRect(x, y, x + width, y + height, paint)
    }

    private fun drawClockFace(canvas: Canvas, element: EditorElement, x: Float, y: Float, width: Float, height: Float, scale: Float, paint: Paint) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = element.thickness.coerceAtLeast(1f) * scale
        val cx = x + width / 2f
        val cy = y + height / 2f
        val radius = minOf(width, height) / 2f - paint.strokeWidth
        canvas.drawCircle(cx, cy, radius, paint)

        if (element.type == EditorElementType.ANALOG_CLOCK) {
            val calendar = Calendar.getInstance()
            val h = calendar.get(Calendar.HOUR)
            val m = calendar.get(Calendar.MINUTE)
            val s = calendar.get(Calendar.SECOND)
            drawHand(canvas, paint, cx, cy, h / 12f * 360f - 90f + m / 60f * 30f, radius * .48f, element.thickness * 1.8f * scale)
            drawHand(canvas, paint, cx, cy, m / 60f * 360f - 90f, radius * .72f, element.thickness * 1.2f * scale)
            val oldColor = paint.color
            paint.color = android.graphics.Color.rgb(255, 91, 91)
            drawHand(canvas, paint, cx, cy, s / 60f * 360f - 90f, radius * .84f, maxOf(1f, element.thickness * .6f) * scale)
            paint.color = oldColor
        }
    }

    private fun drawAnalogHand(canvas: Canvas, element: EditorElement, x: Float, y: Float, width: Float, height: Float, scale: Float, paint: Paint) {
        val calendar = Calendar.getInstance()
        val h = calendar.get(Calendar.HOUR)
        val m = calendar.get(Calendar.MINUTE)
        val s = calendar.get(Calendar.SECOND)
        val angle = when (element.handKind) {
            "Hour" -> h / 12f * 360f + m / 60f * 30f
            "Minute" -> m / 60f * 360f
            else -> s / 60f * 360f
        } + element.rotation - 90f
        drawHand(
            canvas,
            paint,
            x + width / 2f,
            y + height / 2f,
            angle,
            element.length / 100f * minOf(width, height) / 2f,
            element.thickness.coerceAtLeast(0.5f) * scale
        )
    }

    private fun drawHand(canvas: Canvas, paint: Paint, cx: Float, cy: Float, angle: Float, length: Float, stroke: Float) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = stroke
        val radians = Math.toRadians(angle.toDouble())
        canvas.drawLine(
            cx,
            cy,
            cx + kotlin.math.cos(radians).toFloat() * length,
            cy + kotlin.math.sin(radians).toFloat() * length,
            paint
        )
    }

    private fun drawProgress(canvas: Canvas, element: EditorElement, x: Float, y: Float, width: Float, height: Float, scale: Float, paint: Paint) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = maxOf(2f, element.thickness * scale)
        val value = when (element.type) {
            EditorElementType.LINE_PROGRESS -> .84f
            EditorElementType.ARC_PROGRESS -> .76f
            else -> .68f
        }
        if (element.type == EditorElementType.LINE_PROGRESS) {
            val cy = y + height / 2f
            val old = paint.color
            paint.color = android.graphics.Color.DKGRAY
            canvas.drawLine(x, cy, x + width, cy, paint)
            paint.color = old
            canvas.drawLine(x, cy, x + width * value, cy, paint)
        } else {
            val rect = RectF(x, y, x + width, y + height)
            val old = paint.color
            paint.color = android.graphics.Color.DKGRAY
            canvas.drawArc(rect, -90f, 360f, false, paint)
            paint.color = old
            canvas.drawArc(rect, -90f, value * 360f, false, paint)
        }
    }

    private fun drawBrush(canvas: Canvas, element: EditorElement, target: Target, scale: Float, paint: Paint) {
        val path = Path()
        val points = element.brushPath.split(";").mapNotNull { raw ->
            val p = raw.split(",")
            if (p.size != 2) null
            else {
                val px = p[0].toFloatOrNull()
                val py = p[1].toFloatOrNull()
                if (px == null || py == null) null else px to py
            }
        }
        if (points.isEmpty()) return
        path.moveTo(points.first().first / 100f * target.width, points.first().second / 100f * target.height)
        points.drop(1).forEach { point ->
            path.lineTo(point.first / 100f * target.width, point.second / 100f * target.height)
        }
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = element.thickness.coerceAtLeast(1f) * scale
        canvas.drawPath(path, paint)
    }

    private fun writeFixed(buffer: ByteArray, offset: Int, length: Int, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        val count = minOf(length, bytes.size)
        bytes.copyInto(buffer, offset, 0, count)
    }

    private fun writeU16(buffer: ByteArray, offset: Int, value: Int) {
        buffer[offset] = value.toByte()
        buffer[offset + 1] = (value ushr 8).toByte()
    }

    private fun writeU24(buffer: ByteArray, offset: Int, value: Int) {
        buffer[offset] = value.toByte()
        buffer[offset + 1] = (value ushr 8).toByte()
        buffer[offset + 2] = (value ushr 16).toByte()
    }

    private fun writeU32(buffer: ByteArray, offset: Int, value: Int) {
        buffer[offset] = value.toByte()
        buffer[offset + 1] = (value ushr 8).toByte()
        buffer[offset + 2] = (value ushr 16).toByte()
        buffer[offset + 3] = (value ushr 24).toByte()
    }

    private fun concatenate(parts: List<ByteArray>): ByteArray {
        val total = parts.sumOf { it.size }
        val output = ByteArray(total)
        var offset = 0
        parts.forEach { part ->
            part.copyInto(output, offset)
            offset += part.size
        }
        return output
    }
}
