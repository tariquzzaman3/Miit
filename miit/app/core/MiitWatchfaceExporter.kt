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

    fun exportMiCreateProject(
        context: Context, profile: DeviceProfile, elements: List<EditorElement>, device: BandDevice?, name: String
    ): File {
        val validation = validate(profile, elements)
        require(validation.ok) { validation.errors.joinToString("\\n") }
        val root = File(context.filesDir, "watchface_exports").apply { mkdirs() }
        val safe = name.replace(Regex("[^A-Za-z0-9._-]+"), "_").ifBlank { "miit_watchface" }
        val projectDir = File(root, safe).apply { mkdirs(); File(this, "images").mkdirs(); File(this, "output").mkdirs() }
        val deviceType = when {
            profile.deviceId.contains("band_10", true) -> "466"
            profile.deviceId.contains("band_9", true) -> "366"
            profile.deviceId.contains("band_8", true) -> "9"
            else -> "366"
        }
        File(projectDir, safe + ".fprj").writeText(MiCreateFprjWriter.write(deviceType, name, elements, profile.width, profile.height))
        FileOutputStream(File(projectDir, "images/preview.png")).use { out -> renderPreview(profile, elements, device).compress(Bitmap.CompressFormat.PNG, 100, out) }
        File(projectDir, "README.txt").writeText(
            "MIIT / Mi-Create project export\\n" +
            "Target: " + profile.width + "x" + profile.height + "\\n" +
            "Device type: " + deviceType + "\\n\\n" +
            "Open the .fprj project with Mi-Create v1.1.1 or another compatible FPRJ workflow.\\n" +
            "MIIT does not bundle the external watch-face compiler.\\n"
        )
        return projectDir
    }

private object MiCreateFprjWriter {
    fun write(deviceType: String, title: String, elements: List<EditorElement>, width: Int, height: Int): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\\n")
        append("<FaceProject DeviceType=\"").append(xml(deviceType)).append("\" Id=\"0\">\\n")
        append("  <Screen Title=\"").append(xml(title)).append("\" Bitmap=\"\">\\n")
        elements.filter { it.visible }.forEachIndexed { index, e -> append(widget(index + 1, e, width, height)) }
        append("  </Screen>\\n</FaceProject>\\n")
    }

    private fun widget(index: Int, e: EditorElement, width: Int, height: Int): String {
        val name = "miit_$index"
        val x = (e.x / 100f * width).toInt()
        val y = (e.y / 100f * height).toInt()
        val w = e.width.toInt().coerceAtLeast(1)
        val h = e.height.toInt().coerceAtLeast(1)
        return when (e.type) {
            EditorElementType.DIGITAL_NUMBER, EditorElementType.TIME, EditorElementType.DATE, EditorElementType.WEEKDAY,
            EditorElementType.BATTERY, EditorElementType.HEART_RATE, EditorElementType.SPO2, EditorElementType.STEPS,
            EditorElementType.CALORIES, EditorElementType.DISTANCE, EditorElementType.SLEEP, EditorElementType.WEATHER ->
                "<Widget Shape=\"32\" Name=\"$name\" BitmapList=\"\" X=\"$x\" Y=\"$y\" Width=\"$w\" Height=\"$h\" Alpha=\"255\" Visible_Src=\"0\" Digits=\"1\" Alignment=\"${alignment(e)}\" Value_Src=\"${sourceId(e)}\" Spacing=\"0\" Blanking=\"0\"/>\\n"
            EditorElementType.ANALOG_CLOCK ->
                "<Widget Shape=\"27\" Name=\"$name\" X=\"$x\" Y=\"$y\" Width=\"$w\" Height=\"$h\" Alpha=\"255\" Visible_Src=\"0\" HourHandCorrection_En=\"0\" MinuteHandCorrection_En=\"0\" Background_ImageName=\"\" BgImage_rotate_xc=\"0\" BgImage_rotate_yc=\"0\" HourHand_ImageName=\"\" HourImage_rotate_xc=\"0\" HourImage_rotate_yc=\"0\" MinuteHand_Image=\"\" MinuteImage_rotate_xc=\"0\" MinuteImage_rotate_yc=\"0\" SecondHand_Image=\"\" SecondImage_rotate_xc=\"0\" SecondImage_rotate_yc=\"0\"/>\\n"
            EditorElementType.ARC, EditorElementType.ARC_PROGRESS ->
                "<Widget Shape=\"42\" Name=\"$name\" X=\"${x - w / 2}\" Y=\"${y - h / 2}\" Width=\"$w\" Height=\"$h\" Alpha=\"255\" Visible_Src=\"0\" Rotate_xc=\"${w / 2}\" Rotate_yc=\"${h / 2}\" Radius=\"${minOf(w, h) / 2}\" Line_Width=\"${e.thickness.toInt().coerceAtLeast(1)}\" Butt_cap_ending_style_En=\"0\" StartAngle=\"${e.rotation.toInt()}\" EndAngle=\"${(e.rotation + 270f).toInt()}\" Range_Min=\"0\" Range_Max=\"100\" Range_MinStep=\"0\" Range_Step=\"0\" Background_ImageName=\"\" Foreground_ImageName=\"\" Range_Max_Src=\"0\" Range_Val_Src=\"${sourceId(e)}\"/>\\n"
            EditorElementType.CONTAINER ->
                "<Widget Shape=\"34\" Name=\"$name\" X=\"$x\" Y=\"$y\" Width=\"$w\" Height=\"$h\" Alpha=\"255\" Visible_Src=\"0\"/>\\n"
            EditorElementType.IMAGE ->
                "<Widget Shape=\"30\" Name=\"$name\" Bitmap=\"preview.png\" X=\"$x\" Y=\"$y\" Width=\"$w\" Height=\"$h\" Alpha=\"255\" Visible_Src=\"0\"/>\\n"
            else -> ""
        }
    }

    private fun sourceId(e: EditorElement): Int = when (e.preview) {
        "Hour" -> 8; "Minute" -> 9; "Second" -> 10; "Day" -> 11; "Week" -> 12; "Month" -> 13; "Year" -> 14;
        "Battery percent" -> 20; "Heart rate" -> 21; "Current step count" -> 23; "Active Calorie" -> 25;
        "Sleep score" -> 29; "Weather temp (C)" -> 15; else -> 0
    }
    private fun alignment(e: EditorElement): Int = when (e.alignment) { "Left" -> 0; "Right" -> 2; else -> 1 }
    private fun xml(v: String) = v.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")
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