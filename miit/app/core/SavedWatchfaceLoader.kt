package com.miit.app

import org.json.JSONArray
import java.io.File

internal object SavedWatchfaceLoader {
    internal fun load(file: File): List<EditorElement> {
        val raw = runCatching { file.readText() }.getOrNull() ?: return emptyList()
        return runCatching {
            val root = org.json.JSONObject(raw)
            val array = root.optJSONArray("elements") ?: JSONArray()
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val type = runCatching {
                        EditorElementType.valueOf(item.optString("type"))
                    }.getOrNull() ?: continue

                    val color = runCatching {
                        val rawColor = item.optString("color", "")
                            .removePrefix("0x").removePrefix("0X")
                        if (rawColor.isBlank()) androidx.compose.ui.graphics.Color.White
                        else androidx.compose.ui.graphics.Color(rawColor.toLong(16))
                    }.getOrDefault(androidx.compose.ui.graphics.Color.White)

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
                            color = color,
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
                            cornerRadius = item.optDouble("cornerRadius", 0.0).toFloat(),
                            brushPath = item.optString("brushPath", "")
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }
}
