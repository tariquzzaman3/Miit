package com.miit.app

import androidx.compose.ui.graphics.Color
import org.json.JSONArray
import java.io.File

internal object SavedWatchfaceLoader {
    internal fun load(file: File): List<EditorElement> {
        val raw = runCatching { file.readText() }.getOrNull() ?: return emptyList()
        val start = raw.indexOf('[')
        val end = raw.lastIndexOf(']')
        if (start < 0 || end <= start) return emptyList()

        return runCatching {
            val array = JSONArray(raw.substring(start, end + 1))
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val type = runCatching {
                        EditorElementType.valueOf(item.optString("type"))
                    }.getOrNull() ?: continue

                    add(
                        EditorElement(
                            id = item.optInt("id", i + 1),
                            type = type,
                            preview = item.optString("preview", ""),
                            x = item.optDouble("x", 50.0).toFloat(),
                            y = item.optDouble("y", 50.0).toFloat(),
                            size = item.optDouble("size", 24.0).toFloat(),
                            width = item.optDouble("width", 76.0).toFloat(),
                            height = item.optDouble("height", 55.0).toFloat(),
                            color = parseColor(item.optString("color", ""), Color.White),
                            visible = item.optBoolean("visible", true),
                            locked = item.optBoolean("locked", false),
                            bold = item.optBoolean("bold", false),
                            alignment = item.optString("alignment", "Center"),
                            format = item.optString("format", ""),
                            handKind = item.optString("handKind", ""),
                            length = item.optDouble("length", 60.0).toFloat(),
                            thickness = item.optDouble("thickness", 2.0).toFloat(),
                            rotation = item.optDouble("rotation", 0.0).toFloat(),
                            filled = item.optBoolean("filled", false),
                            cornerRadius = item.optDouble("cornerRadius", 0.0).toFloat()
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun parseColor(raw: String, fallback: Color): Color {
        val normalized = raw.removePrefix("0x").removePrefix("0X").trim()
        if (normalized.isEmpty()) return fallback
        return runCatching { Color(normalized.toULong(16)) }.getOrDefault(fallback)
    }
}
