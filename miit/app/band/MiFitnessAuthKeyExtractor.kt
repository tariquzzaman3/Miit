package com.miit.app.band

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Finds Xiaomi/Mi Fitness authentication-key candidates from data already stored
 * on the phone. No Xiaomi account credentials or network request are used.
 */
object MiFitnessAuthKeyExtractor {
    data class Candidate(
        val key: String,
        val source: String,
        val deviceHint: String? = null
    )

    private val keyPatterns = listOf(
        Regex("""(?i)\b(?:auth[_-]?key|authkey|encrypt[_-]?key|token)\s*[=:]\s*["']?([0-9a-f]{32})["']?"""),
        Regex("""(?i)["'](?:auth[_-]?key|authkey|encrypt[_-]?key|token)["']\s*:\s*["']([0-9a-f]{32})["']""")
    )

    private val devicePatterns = listOf(
        Regex("""(?i)\b(?:mac|bluetooth[_-]?address|device[_-]?id)\s*[=:]\s*["']?([0-9a-f]{12,17})["']?"""),
        Regex("""(?i)\b(MI[_ -]?BAND[^\r\n,;]*)""")
    )

    fun find(context: Context): List<Candidate> {
        val candidates = linkedMapOf<String, Candidate>()

        fun inspect(text: String, source: String) {
            val deviceHint = devicePatterns.firstNotNullOfOrNull { it.find(text)?.groupValues?.getOrNull(1) }
            keyPatterns.forEach { pattern ->
                pattern.findAll(text).forEach { match ->
                    val key = match.groupValues[1].lowercase()
                    candidates.putIfAbsent(key, Candidate(key, source, deviceHint))
                }
            }
        }

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
                        val name = cursor.getString(nameCol) ?: continue
                        val relativePath = cursor.getString(pathCol).orEmpty()
                        val relevant = relativePath.contains("Download", ignoreCase = true) ||
                            name.contains("wearable", ignoreCase = true) ||
                            name.contains("xiaomi", ignoreCase = true) ||
                            name.contains("mi fitness", ignoreCase = true) ||
                            name.contains("transfer.device", ignoreCase = true)
                        if (!relevant) continue

                        val uri = Uri.withAppendedPath(
                            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                            cursor.getLong(idCol).toString()
                        )
                        resolver.openInputStream(uri)?.use { input ->
                            inspectStream(input, name, "Mi Fitness Downloads/" + relativePath + name, ::inspect)
                        }
                    }
                }
            }.onFailure {
                inspect("", "Mi Fitness Downloads scan error: " + it.javaClass.simpleName)
            }
        } else {
            val download = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            scanFiles(download, ::inspect)
        }

        listOf(
            "/storage/emulated/0/Android/data/com.xiaomi.wearable/files/log",
            "/storage/emulated/0/Android/data/com.mi.health/files/log"
        ).forEach { path ->
            val directory = File(path)
            if (directory.exists()) scanFiles(directory, ::inspect)
        }

        return candidates.values.toList()
    }

    private fun scanFiles(directory: File, inspect: (String, String) -> Unit) {
        directory.listFiles()
            ?.sortedByDescending { it.lastModified() }
            ?.take(80)
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
                        val bytes = zip.readBytesLimited(4 * 1024 * 1024)
                        inspect(bytes.toString(Charsets.UTF_8), source + "!/" + entry.name)
                    }
                    entry = zip.nextEntry
                }
            }
        } else {
            val bytes = input.readBytesLimited(4 * 1024 * 1024)
            inspect(bytes.toString(Charsets.UTF_8), source)
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
