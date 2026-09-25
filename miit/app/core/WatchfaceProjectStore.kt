package com.miit.app

import android.content.Context
import org.json.JSONObject
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
        val displayName = name.trim().ifBlank { "MIIT watch face" }
        val safeName = displayName
            .replace(Regex("[^A-Za-z0-9._-]+"), "_")
            .take(60)
        val directory = context.filesDir.resolve(DIR).also { it.mkdirs() }
        val file = File(directory, "$safeName-${UUID.randomUUID()}.miit.json")

        val json = JSONObject()
            .put("format", "miit-watchface-project")
            .put("version", 2)
            .put("id", UUID.randomUUID().toString())
            .put("name", displayName)
            .put("target", JSONObject().put("width", width).put("height", height))
            .put("aod", aod)
            .put("elements", org.json.JSONTokener(elementsJson).nextValue())

        file.writeText(json.toString(2))
        return file
    }

    fun list(context: Context): List<File> =
        context.filesDir.resolve(DIR).listFiles()
            ?.filter { it.isFile && it.extension == "json" }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()

    private fun readJson(file: File): JSONObject? =
        runCatching { JSONObject(file.readText()) }.getOrNull()

    fun readName(file: File): String =
        readJson(file)?.optString("name")?.takeIf { it.isNotBlank() }
            ?: file.nameWithoutExtension

    fun readTarget(file: File): Pair<Int, Int> {
        val target = readJson(file)?.optJSONObject("target")
        return Pair(target?.optInt("width", 0) ?: 0, target?.optInt("height", 0) ?: 0)
    }

    fun readElementsJson(file: File): String? =
        readJson(file)?.optJSONArray("elements")?.toString()

    fun readAod(file: File): Boolean =
        readJson(file)?.optBoolean("aod", false) ?: false
}
