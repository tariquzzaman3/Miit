package com.miit.app.band

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

/** Finds Xiaomi/Mi Fitness authentication-key candidates from wearablelog exports. */
object MiFitnessAuthKeyExtractor {
    data class Candidate(val key: String, val source: String, val deviceHint: String? = null)

    // Mi Fitness exports have appeared with token/authKey/encryptKey in several log formats.
    // Keep these patterns deliberately strict: the value must be exactly a 32-character hex key.
    private val keyPatterns = listOf(
        Regex("""(?is)\b(?:auth[_-]?key|authkey|encrypt[_-]?key|encryptkey|token)\b.{0,240}?([0-9a-f]{32})\b"""),
        Regex("""(?is)["'](?:auth[_-]?key|authkey|encrypt[_-]?key|encryptkey|token)["']\s*:\s*["']([0-9a-f]{32})["']"""),
        Regex("""(?is)\b(?:token|encryptKey|authKey)\s*=\s*["']?([0-9a-f]{32})["']?"""),
        Regex("""(?is)\b(?:token|encryptKey|authKey)\s*:\s*([0-9a-f]{32})\b""")
    )

    private val devicePatterns = listOf(
        Regex("""(?i)\b(?:mac|bluetooth[_-]?address|device[_-]?id)\s*[=:]\s*["']?([0-9a-f:]{12,17})["']?"""),
        Regex("""(?i)\b(MI[_ -]?BAND[^\r\n,;]*)""")
    )

    fun find(context: Context): List<Candidate> {
        val candidates = linkedMapOf<String, Candidate>()
        fun inspect(text: String, source: String) {
            if (text.isBlank()) return
            val deviceHint = devicePatterns.firstNotNullOfOrNull { it.find(text)?.groupValues?.getOrNull(1) }
            keyPatterns.forEach { pattern ->
                pattern.findAll(text).forEach { match ->
                    val key = match.groupValues.last().lowercase()
                    if (key.matches(Regex("[0-9a-f]{32}"))) {
                        candidates.putIfAbsent(key, Candidate(key, source, deviceHint))
                    }
                }
            }
        }

        // Primary automatic route requested by MIIT:
        // Download/wearablelog/<timestamp>log.zip -> scan every file inside the ZIP.
        val wearableLog = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            .resolve("wearablelog")
        if (wearableLog.isDirectory) {
            scanDirectory(wearableLog) { text, source -> inspect(text, source) }
        }

        // Android 10+ MediaStore route. This is important on Android 15 where direct
        // filesystem access to Downloads subfolders may be restricted.
        if (android.os.Build.VERSION.SDK_INT >= 29) {
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
                        // Match the actual Mi Fitness export folder, not arbitrary ZIPs.
                        if (!path.replace('\\', '/').contains("Download/wearablelog/", true)) continue
                        if (!name.endsWith(".zip", true)) continue
                        val uri = Uri.withAppendedPath(
                            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                            cursor.getLong(idCol).toString()
                        )
                        resolver.openInputStream(uri)?.use { input ->
                            inspectStream(input, name, "Download/wearablelog/$name") { text, source -> inspect(text, source) }
                        }
                    }
                }
            }
        }

        return candidates.values.toList()
    }

    /** Scans a ZIP/log selected through Android's document picker. */
    fun findFromUri(context: Context, uri: Uri): List<Candidate> {
        val candidates = linkedMapOf<String, Candidate>()
        fun inspect(text: String, source: String) {
            if (text.isBlank()) return
            val deviceHint = devicePatterns.firstNotNullOfOrNull { it.find(text)?.groupValues?.getOrNull(1) }
            keyPatterns.forEach { pattern ->
                pattern.findAll(text).forEach { match ->
                    val key = match.groupValues.last().lowercase()
                    if (key.matches(Regex("[0-9a-f]{32}"))) {
                        candidates.putIfAbsent(key, Candidate(key, source, deviceHint))
                    }
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
                        inspect(bytes.toString(Charsets.UTF_8), "$source!/${entry.name}")
                    }
                    zip.closeEntry()
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
