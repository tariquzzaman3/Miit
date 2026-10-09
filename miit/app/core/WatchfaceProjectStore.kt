package com.miit.app

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Persistent MIIT editor projects. The filename is immutable; the project name is data. */
object WatchfaceProjectStore {
    private const val DIR = "watchfaces"

    fun save(
        context: Context,
        name: String,
        width: Int,
        height: Int,
        aod: Boolean,
        elementsJson: String,
        model: String = "",
        countryVariant: String = "",
        format: String = ""
    ): File {
        val directory = context.filesDir.resolve(DIR).apply { mkdirs() }
        val file = directory.resolve(UUID.randomUUID().toString() + ".miit.json")
        val json = JSONObject().apply {
            put("format", "miit-watchface-project")
            put("version", 3)
            put("id", UUID.randomUUID().toString())
            put("name", name.trim().ifBlank { "MIIT watch face" })
            put(
                "target",
                JSONObject()
                    .put("width", width.coerceAtLeast(0))
                    .put("height", height.coerceAtLeast(0))
                    .put("model", model.trim())
                    .put("countryVariant", countryVariant.trim())
                    .put("format", format.trim())
            )
            put("aod", aod)
            put("elements", org.json.JSONArray(elementsJson))
        }
        file.writeText(json.toString(2))
        return file
    }

    fun list(context: Context): List<File> =
        context.filesDir.resolve(DIR).listFiles()
            ?.filter { it.isFile && it.extension == "json" }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()

    fun readName(file: File): String =
        runCatching { JSONObject(file.readText()).optString("name", file.nameWithoutExtension) }
            .getOrDefault(file.nameWithoutExtension)

    fun readTarget(file: File): Pair<Int, Int> =
        runCatching {
            val target = JSONObject(file.readText()).optJSONObject("target")
            Pair(target?.optInt("width", 0) ?: 0, target?.optInt("height", 0) ?: 0)
        }.getOrDefault(0 to 0)

    fun readModel(file: File): String =
        runCatching { JSONObject(file.readText()).optJSONObject("target")?.optString("model", "").orEmpty() }
            .getOrDefault("")

    fun readCountryVariant(file: File): String =
        runCatching { JSONObject(file.readText()).optJSONObject("target")?.optString("countryVariant", "").orEmpty() }
            .getOrDefault("")

    fun readFormat(file: File): String =
        runCatching { JSONObject(file.readText()).optJSONObject("target")?.optString("format", "").orEmpty() }
            .getOrDefault("")

    fun readElementsJson(file: File): String? =
        runCatching { JSONObject(file.readText()).optJSONArray("elements")?.toString() }.getOrNull()

    fun readAod(file: File): Boolean =
        runCatching { JSONObject(file.readText()).optBoolean("aod", false) }.getOrDefault(false)
}
