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

    private fun escape(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
}
