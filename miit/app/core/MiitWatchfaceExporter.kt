package com.miit.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Color as AndroidColor
import com.miit.app.band.BandDevice
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

data class WatchfaceValidation(
    val ok: Boolean,
    val errors: List<String>,
    val warnings: List<String>
)

object MiitWatchfaceExporter {
    fun validate(profile: DeviceProfile, elements: List<EditorElement>): WatchfaceValidation {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        if (profile.width <= 0 || profile.height <= 0) errors += "Invalid Band canvas size."
        elements.forEach { element ->
            if (element.x !in 0f..100f || element.y !in 0f..100f) errors += element.type.name + ": element is outside the canvas."
            if (element.visible && element.type == EditorElementType.IMAGE && element.preview.isBlank()) errors += "Image " + element.id + " has no source image."
        }
        val visibleCount = elements.count { it.visible }
        val imageCount = elements.count { it.visible && it.type == EditorElementType.IMAGE }
        if (visibleCount > 20) warnings += "Many widgets are present; reduce widget count where possible."
        if (imageCount > 6) warnings += "Many image widgets are present; merge static artwork where practical."
        if (imageCount > 0) warnings += "Keep transparent/RGBA images to a minimum to reduce watch-face resource usage."
        warnings += "Mi-Create recommends minimizing widget count, image size, transparency and animation complexity."
        warnings += "Native Xiaomi .FACE/.bin compilation requires the compatible watch-face compiler; this exporter does not claim to be that compiler."
        return WatchfaceValidation(errors.isEmpty(), errors, warnings)
    }

    fun renderPreview(profile: DeviceProfile, elements: List<EditorElement>, device: BandDevice?): Bitmap {
        val bitmap = Bitmap.createBitmap(profile.width, profile.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(AndroidColor.BLACK)
        val sx = profile.width / 100f
        val sy = profile.height / 100f
        val scale = profile.width / 192f
        elements.filter { it.visible }.forEach { element ->
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = editorColor(element.color)
                textSize = max(8f, element.size * scale)
                strokeWidth = max(1f, element.thickness * scale)
                style = Paint.Style.FILL
                isFakeBoldText = element.bold
            }
            val x = element.x * sx
            val y = element.y * sy
            when (element.type) {
                EditorElementType.TEXT -> canvas.drawText(element.preview.ifBlank { "Text" }, x, y, paint)
                EditorElementType.TIME -> canvas.drawText(SimpleDateFormat(element.format.ifBlank { "HH:mm" }, Locale.getDefault()).format(Date()), x, y, paint)
                EditorElementType.DATE -> {
                    val format = when (element.format) {
                        "DD/MM" -> "dd/MM"
                        "MM/DD" -> "MM/dd"
                        "DD MMM" -> "dd MMM"
                        "DD MMM YYYY" -> "dd MMM yyyy"
                        else -> "dd"
                    }
                    canvas.drawText(SimpleDateFormat(format, Locale.getDefault()).format(Date()), x, y, paint)
                }
                EditorElementType.WEEKDAY -> canvas.drawText(SimpleDateFormat(if (element.format == "EEEE") "EEEE" else "EEE", Locale.getDefault()).format(Date()), x, y, paint)
                EditorElementType.BATTERY -> device?.batteryPercentage?.let { canvas.drawText(it.toString() + "%", x, y, paint) }
                EditorElementType.HEART_RATE -> device?.heartRate?.let { canvas.drawText("♥ " + it, x, y, paint) }
                EditorElementType.CIRCLE, EditorElementType.ELLIPSE -> {
                    paint.style = if (element.filled) Paint.Style.FILL else Paint.Style.STROKE
                    canvas.drawOval(RectF(x - element.width * sx / 2f, y - element.height * sy / 2f, x + element.width * sx / 2f, y + element.height * sy / 2f), paint)
                }
                EditorElementType.RECTANGLE -> {
                    paint.style = if (element.filled) Paint.Style.FILL else Paint.Style.STROKE
                    canvas.drawRect(RectF(x - element.width * sx / 2f, y - element.height * sy / 2f, x + element.width * sx / 2f, y + element.height * sy / 2f), paint)
                }
                EditorElementType.ROUNDED_RECTANGLE -> {
                    paint.style = if (element.filled) Paint.Style.FILL else Paint.Style.STROKE
                    canvas.drawRoundRect(RectF(x - element.width * sx / 2f, y - element.height * sy / 2f, x + element.width * sx / 2f, y + element.height * sy / 2f), element.cornerRadius * sx, element.cornerRadius * sy, paint)
                }
                EditorElementType.TRIANGLE -> {
                    val path = android.graphics.Path().apply {
                        moveTo(x, y - element.height * sy / 2f)
                        lineTo(x + element.width * sx / 2f, y + element.height * sy / 2f)
                        lineTo(x - element.width * sx / 2f, y + element.height * sy / 2f)
                        close()
                    }
                    paint.style = if (element.filled) Paint.Style.FILL else Paint.Style.STROKE
                    canvas.drawPath(path, paint)
                }
                EditorElementType.LINE -> {
                    paint.style = Paint.Style.STROKE
                    canvas.save()
                    canvas.rotate(element.rotation, x, y)
                    canvas.drawLine(x - element.length * sx / 2f, y, x + element.length * sx / 2f, y, paint)
                    canvas.restore()
                }
                EditorElementType.ARC, EditorElementType.ARC_PROGRESS -> {
                    paint.style = Paint.Style.STROKE
                    canvas.drawArc(RectF(x - element.width * sx / 2f, y - element.height * sy / 2f, x + element.width * sx / 2f, y + element.height * sy / 2f), element.rotation - 90f, 270f, false, paint)
                }
                EditorElementType.ANALOG_CLOCK -> drawAnalogClock(canvas, x, y, element, scale)
                EditorElementType.ANALOG_HAND -> drawAnalogHand(canvas, x, y, element, scale)
                EditorElementType.CLOCK_FACE -> {
                    paint.style = Paint.Style.STROKE
                    canvas.drawCircle(x, y, min(element.width * sx, element.height * sy) / 2f, paint)
                }
                else -> Unit
            }
        }
        return bitmap
    }

    fun exportBundle(context: Context, profile: DeviceProfile, elements: List<EditorElement>, device: BandDevice?, name: String): File {
        val validation = validate(profile, elements)
        require(validation.ok) { validation.errors.joinToString("\n") }
        val dir = File(context.filesDir, "watchface_exports").apply { mkdirs() }
        val safe = name.replace(Regex("[^A-Za-z0-9._-]+"), "_").ifBlank { "miit_watchface" }
        val file = File(dir, safe + "-" + profile.width + "x" + profile.height + ".miit.zip")
        val preview = renderPreview(profile, elements, device)
        val png = ByteArrayOutputStream().use { stream -> preview.compress(Bitmap.CompressFormat.PNG, 100, stream); stream.toByteArray() }
        val project = WatchfaceProjectSerializer.serialize(name, profile.width, profile.height, elements)
        ZipOutputStream(FileOutputStream(file)).use { zip ->
            fun entry(nameInZip: String, data: ByteArray) { zip.putNextEntry(ZipEntry(nameInZip)); zip.write(data); zip.closeEntry() }
            entry("preview.png", png)
            entry("project.json", project.toByteArray(Charsets.UTF_8))
            entry("README.txt", (
                "MIIT watch-face export\n" +
                "Target: " + profile.width + "x" + profile.height + "\n" +
                "Generated: " + SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()) + "\n\n" +
                "preview.png is the exact device-sized preview.\n" +
                "project.json contains the editable MIIT design.\n" +
                "A native Xiaomi .FACE/.bin file must be produced by a compatible watch-face compiler.\n"
            ).toByteArray(Charsets.UTF_8))
        }
        return file
    }

    private fun drawAnalogClock(canvas: Canvas, x: Float, y: Float, element: EditorElement, scale: Float) {
        val now = java.util.Calendar.getInstance()
        val h = now.get(java.util.Calendar.HOUR)
        val m = now.get(java.util.Calendar.MINUTE)
        val s = now.get(java.util.Calendar.SECOND)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = editorColor(element.color); style = Paint.Style.STROKE }
        val radius = min(element.width, element.height) * scale / 2f
        paint.strokeWidth = max(1f, element.thickness * scale)
        canvas.drawCircle(x, y, radius, paint)
        fun hand(degrees: Float, length: Float, width: Float) {
            val a = Math.toRadians((degrees - 90f).toDouble())
            paint.strokeWidth = max(1f, width * scale)
            canvas.drawLine(x, y, x + cos(a).toFloat() * length * scale, y + sin(a).toFloat() * length * scale, paint)
        }
        hand(h / 12f * 360f + m / 60f * 30f, 36f, 5f)
        hand(m / 60f * 360f, 52f, 3.5f)
        hand(s / 60f * 360f, 60f, 1.5f)
    }

    private fun drawAnalogHand(canvas: Canvas, x: Float, y: Float, element: EditorElement, scale: Float) {
        val now = java.util.Calendar.getInstance()
        val h = now.get(java.util.Calendar.HOUR)
        val m = now.get(java.util.Calendar.MINUTE)
        val s = now.get(java.util.Calendar.SECOND)
        val degrees = when (element.handKind) {
            "Hour" -> h / 12f * 360f + m / 60f * 30f
            "Minute" -> m / 60f * 360f
            else -> s / 60f * 360f
        } + element.rotation
        val radians = Math.toRadians((degrees - 90f).toDouble())
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = editorColor(element.color)
            style = Paint.Style.STROKE
            strokeWidth = max(1f, element.thickness * scale)
            strokeCap = Paint.Cap.ROUND
        }
        val length = element.length * scale
        canvas.drawLine(x, y, x + cos(radians).toFloat() * length, y + sin(radians).toFloat() * length, paint)
    }
}

private fun editorColor(color: androidx.compose.ui.graphics.Color): Int = AndroidColor.argb(
    (color.alpha * 255f).toInt().coerceIn(0, 255),
    (color.red * 255f).toInt().coerceIn(0, 255),
    (color.green * 255f).toInt().coerceIn(0, 255),
    (color.blue * 255f).toInt().coerceIn(0, 255)
)

private object WatchfaceProjectSerializer {
    fun serialize(name: String, width: Int, height: Int, elements: List<EditorElement>): String =
        "{\"name\":\"" + escape(name) + "\",\"target\":{\"width\":" + width + ",\"height\":" + height + "},\"elements\":[" +
            elements.joinToString(",") { e ->
                "{\"id\":" + e.id + ",\"type\":\"" + e.type.name + "\",\"preview\":\"" + escape(e.preview) + "\",\"x\":" + e.x + ",\"y\":" + e.y + ",\"size\":" + e.size + ",\"width\":" + e.width + ",\"height\":" + e.height + ",\"bold\":" + e.bold + ",\"format\":\"" + escape(e.format) + "\",\"handKind\":\"" + escape(e.handKind) + "\",\"length\":" + e.length + ",\"thickness\":" + e.thickness + ",\"rotation\":" + e.rotation + ",\"filled\":" + e.filled + ",\"cornerRadius\":" + e.cornerRadius + ",\"visible\":" + e.visible + ",\"locked\":" + e.locked + "}"
            } + "]}"

    private fun escape(value: String): String = value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
}