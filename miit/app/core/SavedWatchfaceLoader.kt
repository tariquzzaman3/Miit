package com.miit.app

import org.json.JSONArray
import java.io.File

internal object SavedWatchfaceLoader {
    internal fun load(file: File): List<EditorElement> {
        val raw = runCatching { file.readText() }.getOrNull() ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw.substring(raw.indexOf('['), raw.lastIndexOf(']') + 1))
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val type = runCatching { EditorElementType.valueOf(item.optString("type")) }.getOrNull() ?: continue
                    add(EditorElement(
                        id = item.optInt("id", i + 1),
                        type = type,
                        preview = item.optString("preview", ""),
                        x = item.optDouble("x", 50.0).toFloat(),
                        y = item.optDouble("y", 50.0).toFloat(),
                        size = item.optDouble("size", 24.0).toFloat(),
                        visible = item.optBoolean("visible", true),
                        locked = item.optBoolean("locked", false)
                    ))
                }
            }
        }.getOrDefault(emptyList())
    }
}