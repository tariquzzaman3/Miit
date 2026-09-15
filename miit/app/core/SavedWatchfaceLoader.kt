package com.miit.app

import org.json.JSONArray
import java.io.File

internal object SavedWatchfaceLoader {
    internal fun load(file: File): List<EditorElement> {
        val raw = runCatching { file.readText() }.getOrNull() ?: return emptyList()
        val arrayStart = raw.indexOf('[')
        val arrayEnd = raw.lastIndexOf(']')
        if (arrayStart < 0 || arrayEnd < arrayStart) return emptyList()

        return runCatching {
            val array = JSONArray(raw.substring(arrayStart, arrayEnd + 1))
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val type = runCatching {
                        EditorElementType.valueOf(item.optString("type"))
                    }.getOrNull() ?: continue

                    val color = item.optString("color", "ffffffff")
                        .removePrefix("#")
                        .toLongOrNull(16)
                        ?.let { androidx.compose.ui.graphics.Color(it.toULong()) }
                        ?: androidx.compose.ui.graphics.Color.White

                    add(
                        EditorElement(
                            id = item.optInt("id", i + 1),
                            type = type,
                            preview = item.optString("preview", ""),
                            x = item.optDouble("x", 50.0).toFloat().coerceIn(0f, 100f),
                            y = item.optDouble("y", 50.0).toFloat().coerceIn(0f, 100f),
                            size = item.optDouble("size", 24.0).toFloat().coerceAtLeast(0.1f),
                            width = item.optDouble("width", 76.0).toFloat().coerceAtLeast(0.1f),
                            height = item.optDouble("height", 55.0).toFloat().coerceAtLeast(0.1f),
                            color = color,
                            visible = item.optBoolean("visible", true),
                            locked = item.optBoolean("locked", false),
                            bold = item.optBoolean("bold", false),
                            alignment = item.optString("alignment", "Center"),
                            format = item.optString("format", ""),
                            handKind = item.optString("handKind", ""),
                            length = item.optDouble("length", 60.0).toFloat().coerceIn(1f, 100f),
                            thickness = item.optDouble("thickness", 2.0).toFloat().coerceAtLeast(0.1f),
                            rotation = item.optDouble("rotation", 0.0).toFloat(),
                            filled = item.optBoolean("filled", false),
                            cornerRadius = item.optDouble("cornerRadius", 0.0).toFloat().coerceAtLeast(0f)
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }
}
