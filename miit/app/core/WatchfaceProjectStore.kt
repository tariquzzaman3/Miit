package com.miit.app

import android.content.Context
import java.io.File
import java.util.UUID

object WatchfaceProjectStore {
    private const val DIR = "watchfaces"
    private const val FORMAT = "miit-watchface-project"
    private const val VERSION = 2

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
            .ifBlank { "MIIT_watch_face" }
        val projectId = UUID.randomUUID().toString()
        val file = context.filesDir.resolve(DIR).resolve("${safeName}_$projectId.miit.json")
        file.parentFile?.mkdirs()

        val json = buildString {
            append("{\n")
            append("  \"format\": \"").append(FORMAT).append("\",\n")
            append("  \"version\": ").append(VERSION).append(",\n")
            append("  \"id\": \"").append(projectId).append("\",\n")
            append("  \"name\": \"").append(escape(name)).append("\",\n")
            append("  \"target\": {\"width\": ").append(width)
                .append(", \"height\": ").append(height).append("},\n")
            append("  \"aod\": ").append(aod).append(",\n")
            append("  \"elements\": ").append(elementsJson).append("\n")
            append("}\n")
        }
        file.writeText(json)
        return file
    }

    fun list(context: Context): List<File> =
        context.filesDir.resolve(DIR).listFiles()
            ?.filter { it.isFile && it.extension == "json" }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()

    fun readId(file: File): String? =
        Regex("""\"id\"\s*:\s*\"([^\"]+)\"""")
            .find(runCatching { file.readText() }.getOrDefault(""))
            ?.groupValues
            ?.getOrNull(1)

    fun readName(file: File): String {
        val text = runCatching { file.readText() }.getOrDefault("")
        return Regex("""\"name\"\s*:\s*\"([^\"]*)\"""")
            .find(text)?.groupValues?.getOrNull(1)
            ?: file.nameWithoutExtension.substringBeforeLast("_", file.nameWithoutExtension)
    }

    fun readVersion(file: File): Int {
        val text = runCatching { file.readText() }.getOrDefault("")
        return Regex("""\"version\"\s*:\s*(\d+)""")
            .find(text)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 1
    }

    fun readTarget(file: File): Pair<Int, Int> {
        val text = runCatching { file.readText() }.getOrDefault("")
        val match = Regex("""\"target\"\s*:\s*\{\s*\"width\"\s*:\s*(\d+)\s*,\s*\"height\"\s*:\s*(\d+)\s*\}""").find(text)
        return Pair(
            match?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0,
            match?.groupValues?.getOrNull(2)?.toIntOrNull() ?: 0
        )
    }

    fun readElementsJson(file: File): String? {
        val text = runCatching { file.readText() }.getOrNull() ?: return null
        val start = text.indexOf("\"elements\"")
        if (start < 0) return null
        val arrayStart = text.indexOf('[', start)
        if (arrayStart < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        for (i in arrayStart until text.length) {
            val ch = text[i]
            if (inString) {
                if (escaped) escaped = false
                else if (ch == '\\') escaped = true
                else if (ch == '"') inString = false
            } else {
                when (ch) {
                    '"' -> inString = true
                    '[' -> depth++
                    ']' -> {
                        depth--
                        if (depth == 0) return text.substring(arrayStart, i + 1)
                    }
                }
            }
        }
        return null
    }

    fun readAod(file: File): Boolean {
        val text = runCatching { file.readText() }.getOrDefault("")
        return Regex("""\"aod\"\s*:\s*(true|false)""")
            .find(text)?.groupValues?.getOrNull(1) == "true"
    }

    private fun escape(value: String): String =
        value.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
}
