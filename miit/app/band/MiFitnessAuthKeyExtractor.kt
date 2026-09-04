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
            extractDeviceRecords(text).forEach { record ->
                val model = record.model
                val key = record.key
                if (key != null) {
                    candidates.putIfAbsent(
                        key,
                        Candidate(
                            key = key,
                            source = source,
                            deviceHint = model ?: record.mac ?: record.did
                        )
                    )
                }
            }
        }

        // Fixed parent folder: Download/wearablelog/
        val wearableLog = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            .resolve("wearablelog")
        if (wearableLog.isDirectory) {
            scanDirectory(wearableLog) { text, source -> inspect(text, source) }
        }

        // Android 10+ shared Downloads. Filename is deliberately ignored:
        // every ZIP in Download/wearablelog is eligible.
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
                        if (!path.replace('\\', '/').contains("Download/wearablelog/", true)) continue
                        if (!name.endsWith(".zip", true)) continue
                        val uri = Uri.withAppendedPath(
                            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                            cursor.getLong(idCol).toString()
                        )
                        resolver.openInputStream(uri)?.use { input ->
                            inspectStream(input, name, "Download/wearablelog/$name") { text, source ->
                                inspect(text, source)
                            }
                        }
                    }
                }
            }.onFailure {
                MiitTestLog.add("Mi Fitness Download scan error: " + it.javaClass.simpleName)
            }
        }

        return candidates.values.toList()
    }

    private data class DeviceRecord(
        val key: String?,
        val model: String?,
        val mac: String?,
        val did: String?
    )

    /**
     * Mi Fitness 3.x exports deviceInfo as JSON embedded in a text log.
     * For each embedded device record, authKey may be null; encryptKey/token
     * are the usable 32-hex pairing secret seen in current exports.
     */
    private fun extractDeviceRecords(text: String): List<DeviceRecord> {
        val records = mutableListOf<DeviceRecord>()
        val anchor = Regex("""(?s)"deviceInfo"\s*:\s*\{""")
        anchor.findAll(text).forEach { match ->
            val window = text.substring(match.range.first, minOf(text.length, match.range.first + 12000))
            val model = Regex("""(?s)"model"\s*:\s*"([^"]+)"""").find(window)?.groupValues?.get(1)
            val did = Regex("""(?s)"did"\s*:\s*"([^"]+)"""").find(window)?.groupValues?.get(1)
            val mac = Regex("""(?s)"mac"\s*:\s*"([^"]+)"""").find(window)?.groupValues?.get(1)
            val detail = Regex("""(?s)"detail"\s*:\s*\{(.*?)\}""").find(window)?.groupValues?.get(1).orEmpty()

            val authKey = Regex("""(?i)"authKey"\s*:\s*"([0-9a-f]{32})"""").find(detail)?.groupValues?.get(1)
            val encryptKey = Regex("""(?i)"encryptKey"\s*:\s*"([0-9a-f]{32})"""").find(detail)?.groupValues?.get(1)
            val token = Regex("""(?i)"token"\s*:\s*"([0-9a-f]{32})"""").find(detail)?.groupValues?.get(1)

            // Priority: explicit authKey, otherwise encryptKey, otherwise token.
            val key = authKey ?: encryptKey ?: token
            records += DeviceRecord(key?.lowercase(), model, mac, did)
        }

        // Fallback for logs that do not contain the enclosing deviceInfo object.
        if (records.isEmpty()) {
            val key = Regex("""(?i)"(?:authKey|encryptKey|token)"\s*:\s*"([0-9a-f]{32})"""")
                .findAll(text)
                .lastOrNull()
                ?.groupValues?.get(1)
                ?.lowercase()
            if (key != null) records += DeviceRecord(key, null, null, null)
        }

        return records
    }

    /** Reads the user-selected Mi Fitness ZIP first, then falls back to plain text. */
    fun findFromUri(context: Context, uri: Uri): List<Candidate> {
        val candidates = linkedMapOf<String, Candidate>()
        fun inspect(text: String, source: String) {
            if (text.isBlank()) return
            val deviceHint = devicePatterns.firstNotNullOfOrNull { it.find(text)?.groupValues?.getOrNull(1) }
            keyPatterns.forEach { pattern ->
                pattern.findAll(text).forEach { match ->
                    val key = match.groupValues.last().lowercase().removePrefix("0x")
                    if (key.matches(Regex("[0-9a-f]{32}"))) {
                        candidates.putIfAbsent(key, Candidate(key, source, deviceHint))
                    }
                }
            }

            // Mi Fitness commonly writes the key as token=... inside Transfer.device.log.
            // Prefer the last occurrence because logs can contain several pairing records.
            Regex("""(?i)\b(?:token|authKey|encryptKey|huamiAuthKey)\s*=\s*(?:0x)?([0-9a-f]{32})\b""")
                .findAll(text)
                .lastOrNull()
                ?.let { match ->
                    val key = match.groupValues[1].lowercase()
                    candidates.putIfAbsent(key, Candidate(key, source, deviceHint))
                }
        }

        // The previous implementation treated the document-picker URI as plain text
        // because it used the synthetic name "selected-log". That cannot read a ZIP.
        // Try the ZIP container directly; if it is not a ZIP, reopen it as text.
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                ZipInputStream(input.buffered()).use { zip ->
                    var entry = zip.nextEntry
                    var foundZipEntry = false
                    while (entry != null) {
                        foundZipEntry = true
                        if (!entry.isDirectory) {
                            val bytes = zip.readBytesLimited(8 * 1024 * 1024)
                            inspect(bytes.toString(Charsets.UTF_8), uri.toString() + "!/" + entry.name)
                        }
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                    if (!foundZipEntry) throw java.util.zip.ZipException("empty archive")
                }
            }
        }.recoverCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                inspect(input.readBytesLimited(16 * 1024 * 1024).toString(Charsets.UTF_8), uri.toString())
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
