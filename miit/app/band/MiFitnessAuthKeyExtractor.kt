package com.miit.app.band

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

/** Finds Xiaomi/Mi Fitness authentication-key candidates from wearablelog exports. */
object MiFitnessAuthKeyExtractor {
    data class Candidate(val key: String, val source: String, val deviceHint: String? = null)

    private val keyPatterns = listOf(
        Regex("""(?is)\\b(?:auth[_-]?key|authkey|encrypt[_-]?key|token)\\b.{0,240}?([0-9a-f]{32})\\b"""),
        Regex("""(?is)["'](?:auth[_-]?key|authkey|encrypt[_-]?key|token)["']\\s*:\\s*["']([0-9a-f]{32})["']"""),
        Regex("""(?is)\\b(?:token|encryptKey|authKey)\\s*=\\s*["']?([0-9a-f]{32})["']?""")
    )

    private val devicePatterns = listOf(
        Regex("""(?i)\\b(?:mac|bluetooth[_-]?address|device[_-]?id)\\s*[=:]\\s*["']?([0-9a-f:]{12,17})["']?"""),
        Regex("""(?i)\\b(MI[_ -]?BAND[^\\r\\n,;]*)""")
    )

    fun find(context: Context): List<Candidate> {
        if (Build.VERSION.SDK_INT in 29..32 &&
            context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            (context as? Activity)?.let { activity ->
                Handler(Looper.getMainLooper()).post {
                    activity.requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), 7402)
                }
            }
            return emptyList()
        }

        val candidates = linkedMapOf<String, Candidate>()
        fun inspect(text: String, source: String) {
            val deviceHint = devicePatterns.firstNotNullOfOrNull { it.find(text)?.groupValues?.getOrNull(1) }
            keyPatterns.forEach { pattern ->
                pattern.findAll(text).forEach { match ->
                    val key = match.groupValues.last().lowercase()
                    if (key.length == 32) candidates.putIfAbsent(key, Candidate(key, source, deviceHint))
                }
            }
        }

        // Expected Mi Fitness export layout: Download/wearablelog/<timestamp>log.zip
        val wearableLog = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            .resolve("wearablelog")
        if (wearableLog.exists()) {
            scanDirectory(wearableLog) { text, source -> inspect(text, source) }
        }

        // Newer Android: use the shared Downloads collection when it exposes the folder.
        if (Build.VERSION.SDK_INT >= 29) {
            val resolver = context.contentResolver
            val projection = arrayOf(
                MediaStore.Downloads._ID,
                MediaStore.Downloads.DISPLAY_NAME,
                MediaStore.Downloads.RELATIVE_PATH
            )
            runCatching {
                resolver.query(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    projection,
                    null,
                    null,
                    MediaStore.Downloads.DATE_MODIFIED + " DESC"
                )?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID)
                    val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME)
                    val pathCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads.RELATIVE_PATH)
                    while (cursor.moveToNext()) {
                        val name = cursor.getString(nameCol).orEmpty()
                        val path = cursor.getString(pathCol).orEmpty()
                        if (!path.contains("wearablelog", true) && !name.contains("wearablelog", true)) continue
                        val uri = Uri.withAppendedPath(
                            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                            cursor.getLong(idCol).toString()
                        )
                        resolver.openInputStream(uri)?.use { input ->
                            inspectStream(input, name, "Downloads/$path$name") { text, source -> inspect(text, source) }
                        }
                    }
                }
            }
        }

        // Older Android installations may expose Mi Fitness private logs directly.
        listOf(
            "/storage/emulated/0/Android/data/com.xiaomi.wearable/files/log",
            "/storage/emulated/0/Android/data/com.mi.health/files/log"
        ).forEach { path ->
            val directory = File(path)
            if (directory.exists()) scanDirectory(directory) { text, source -> inspect(text, source) }
        }

        return candidates.values.toList()
    }

    /** Scans a ZIP/log selected through Android's document picker. */
    fun findFromUri(context: Context, uri: Uri): List<Candidate> {
        val candidates = linkedMapOf<String, Candidate>()
        fun inspect(text: String, source: String) {
            val deviceHint = devicePatterns.firstNotNullOfOrNull { it.find(text)?.groupValues?.getOrNull(1) }
            keyPatterns.forEach { pattern ->
                pattern.findAll(text).forEach { match ->
                    val key = match.groupValues.last().lowercase()
                    if (key.length == 32) candidates.putIfAbsent(key, Candidate(key, source, deviceHint))
                }
            }
        }
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                inspectStream(input, "selected-log", uri.toString()) { text, source -> inspect(text, source) }
            }
        }
        return candidates.values.toList()
    }

    private fun scanDirectory(directory: File, inspect: (String, String) -> Unit) {
        directory.listFiles()
            ?.sortedByDescending { it.lastModified() }
            ?.take(100)
            ?.forEach { file ->
                if (!file.isFile) return@forEach
                runCatching {
                    file.inputStream().use { input ->
                        inspectStream(input, file.name, file.absolutePath, inspect)
                    }
                }
            }
    }

    private fun inspectStream(
        input: InputStream,
        name: String,
        source: String,
        inspect: (String, String) -> Unit
    ) {
        if (name.endsWith(".zip", ignoreCase = true)) {
            ZipInputStream(input.buffered()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val bytes = zip.readBytesLimited(8 * 1024 * 1024)
                        // Search every file inside the export: auth-key logs do not need a special filename.
                        inspect(bytes.toString(Charsets.UTF_8), "$source!/${entry.name}")
                    }
                    entry = zip.nextEntry
                }
            }
        } else {
            inspect(input.readBytesLimited(8 * 1024 * 1024).toString(Charsets.UTF_8), source)
        }
    }

    private fun InputStream.readBytesLimited(maxBytes: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0
        while (total < maxBytes) {
            val n = read(buffer, 0, minOf(buffer.size, maxBytes - total))
            if (n <= 0) break
            out.write(buffer, 0, n)
            total += n
        }
        return out.toByteArray()
    }
}
