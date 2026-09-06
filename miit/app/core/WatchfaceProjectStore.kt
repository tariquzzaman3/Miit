package com.miit.app

import android.content.Context
import java.io.File
import java.util.UUID

object WatchfaceProjectStore {
    private const val DIR = "watchfaces"

    fun save(
        context: Context,
        name: String,
        width: Int,
        height: Int,
        aod: Boolean,
        elementsJson: String
    ): File {
        val safeName = name.trim().ifBlank { "MIIT watch face" }
            .replace(Regex("[^A-Za-z0-9._-]+"), "_")
            .take(60)
        val file = File(context.filesDir.resolve(DIR), safeName + ".miit.json")
        file.parentFile?.mkdirs()
        val json = "{\n" +
            "  \"format\": \"miit-watchface-project\",\n" +
            "  \"version\": 1,\n" +
            "  \"id\": \"" + UUID.randomUUID().toString() + "\",\n" +
            "  \"name\": \"" + escape(name) + "\",\n" +
            "  \"target\": {\"width\": " + width + ", \"height\": " + height + "},\n" +
            "  \"aod\": " + aod + ",\n" +
            "  \"elements\": " + elementsJson + "\n}"
        file.writeText(json)
        return file
    }

    fun list(context: Context): List<File> =
        context.filesDir.resolve(DIR).listFiles()
            ?.filter { it.isFile && it.extension == "json" }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()

    fun readName(file: File): String {
        val text = runCatching { file.readText() }.getOrDefault("")
        return Regex("""\"name\"\s*:\s*\"([^\"]*)\"""").find(text)?.groupValues?.getOrNull(1) ?: file.nameWithoutExtension
    }

    fun readTarget(file: File): Pair<Int, Int> {
        val text = runCatching { file.readText() }.getOrDefault("")
        val match = Regex("""\"target\"\s*:\s*\{\"width\"\s*:\s*(\d+)\s*,\s*\"height\"\s*:\s*(\d+)""").find(text)
        return Pair(match?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0, match?.groupValues?.getOrNull(2)?.toIntOrNull() ?: 0)
    }

    fun readElementsJson(file: File): String? {
        val text = runCatching { file.readText() }.getOrNull() ?: return null
        val start = text.indexOf("\"elements\"")
        if (start < 0) return null
        val arrayStart = text.indexOf('[', start)
        if (arrayStart < 0) return null
        var depth = 0; var inString = false; var escaped = false
        for (i in arrayStart until text.length) {
            val ch = text[i]
            if (inString) {
                if (escaped) escaped = false else if (ch == '\\') escaped = true else if (ch == '"') inString = false
            } else when (ch) {
                '"' -> inString = true
                '[' -> depth++
                ']' -> { depth--; if (depth == 0) return text.substring(arrayStart, i + 1) }
            }
        }
        return null
    }

    fun readAod(file: File): Boolean {
        val text = runCatching { file.readText() }.getOrDefault("")
        return Regex("""\"aod\"\s*:\s*(true|false)""").find(text)?.groupValues?.getOrNull(1) == "true"
    }
    private fun escape(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
}
