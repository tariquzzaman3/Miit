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

/**
 * Finds Xiaomi/Mi Fitness authentication-key candidates from the exported
 * wearablelog directory and Mi Fitness log locations. No network is used.
 */
object MiFitnessAuthKeyExtractor {
    data class Candidate(
        val key: String,
        val source: String,
        val deviceHint: String? = null
    )

    private val keyPatterns = listOf(
        Regex("""(?is)\\b(?:auth[_-]?key|authkey|encrypt[_-]?key|token)\\b.{0,180}?([0-9a-f]{32})\\b"""),
        Regex("""(?is)["'](?:auth[_-]?key|authkey|encrypt[_-]?key|token)["']\\s*:\\s*["']([0-9a-f]{32})["']"""),
        Regex("""(?is)\\b(?:token|encryptKey|authKey)\\s*=\\s*["']?([0-9a-f]{32})["']?""")
    )

    private val devicePatterns = listOf(
        Regex("""(?i)\\b(?:mac|bluetooth[_-]?address|device[_-]?id)\\s*[=:]\\s*["']?([0-9a-f:]{12,17})["']?"""),
        Regex("""(?i)\\b(MI[_ -]?BAND[^\\r\\n,;]*)""")
    )

    fun find(context: Context): List<Candidate> {
        // Android 10–12 can require READ_EXTERNAL_STORAGE for direct/shared log access.
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

        // The expected export layout is Download/wearablelog/<log>.zip.
        val wearableLog = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            .resolve("wearablelog")
        if (wearableLog.exists()) {
            scanDirectory(wearableLog, inspect)
        }

        // Also inspect the shared Downloads provider, which is the reliable path on newer Android.
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
                        val relativePath = cursor.getString(pathCol).orEmpty()
                        val inWearableLog = relativePath.contains("wearablelog", ignoreCase = true)
                        if (!inWearableLog && !name.contains("wearablelog", ignoreCase = true) && !name.endsWith("log.zip", true)) continue
                        val uri = Uri.withAppendedPath(
                            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                            cursor.getLong(idCol).toString()
                        )
                        resolver.openInputStream(uri)?.use { input ->
                            inspectStream(input, name, "Downloads/$relativePath$name", ::inspect)
                        }
                    }
                }
            }.onFailure { /* Fall through to direct log locations. */ }
        }

        listOf(
            "/storage/emulated/0/Android/data/com.xiaomi.wearable/files/log",
            "/storage/emulated/0/Android/data/com.mi.health/files/log"
        ).forEach { path ->
            val directory = File(path)
            if (directory.exists()) scanDirectory(directory, inspect)
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
                        val entryName = entry.name
                        val relevant = entryName.contains("XiaomiFit", true) ||
                            entryName.contains("Transfer.device", true) ||
                            entryName.contains("token", true) ||
                            entryName.contains("auth", true) ||
                            entryName.endsWith(".log", true) ||
                            entryName.endsWith(".txt", true)
                        if (relevant) {
                            val bytes = zip.readBytesLimited(8 * 1024 * 1024)
                            inspect(bytes.toString(Charsets.UTF_8), source + "!/" + entryName)
                        }
                    }
                    entry = zip.nextEntry
                }
            }
        } else {
            val bytes = input.readBytesLimited(8 * 1024 * 1024)
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
