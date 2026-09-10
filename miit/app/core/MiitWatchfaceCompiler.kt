package com.miit.app

/**
 * Safe compiler foundation for MIIT watch-face projects.
 *
 * This intentionally produces an intermediate JSON representation rather than
 * pretending to be a Xiaomi-native .FACE/.bin package. Native installation
 * remains a separate protocol/compiler task.
 */
object MiitWatchfaceCompiler {
    data class Target(
        val width: Int,
        val height: Int,
        val model: String? = null,
        val aod: Boolean = false
    )

    data class Result(
        val json: String,
        val warnings: List<String>,
        val elementCount: Int
    )

    internal fun compile(
        target: Target,
        elements: List<EditorElement>
    ): Result {
        require(target.width > 0 && target.height > 0) { "Watch-face target size must be positive" }

        val warnings = mutableListOf<String>()
        val visible = elements.filter { it.visible }

        if (visible.isEmpty()) {
            warnings += "The project contains no visible elements."
        }
        if (target.width == 192 && target.height == 490) {
            // Xiaomi Smart Band 9 profile currently supported by MIIT.
        } else if (target.width == 212 && target.height == 520) {
            // Xiaomi Smart Band 10 profile currently supported by MIIT.
        } else {
            warnings += "Target size ${target.width}x${target.height} is not a known MIIT band profile."
        }

        val jsonElements = visible.joinToString(",\n") { element ->
            elementJson(element)
        }

        val json = buildString {
            append("{\n")
            append("  \"format\": \"miit-watchface-ir\",\n")
            append("  \"version\": 1,\n")
            append("  \"target\": {\n")
            append("    \"width\": ").append(target.width).append(",\n")
            append("    \"height\": ").append(target.height).append(",\n")
            append("    \"model\": ").append(stringOrNull(target.model)).append("\n")
            append("  },\n")
            append("  \"aod\": ").append(target.aod).append(",\n")
            append("  \"elements\": [\n")
            append(jsonElements)
            append("\n  ]\n")
            append("}\n")
        }

        return Result(json = json, warnings = warnings, elementCount = visible.size)
    }

    private fun elementJson(element: EditorElement): String = buildString {
        append("    {")
        append("\"id\": ").append(element.id).append(", ")
        append("\"type\": ").append(string(element.type.name)).append(", ")
        append("\"preview\": ").append(string(element.preview)).append(", ")
        append("\"x\": ").append(number(element.x)).append(", ")
        append("\"y\": ").append(number(element.y)).append(", ")
        append("\"size\": ").append(number(element.size)).append(", ")
        append("\"width\": ").append(number(element.width)).append(", ")
        append("\"height\": ").append(number(element.height)).append(", ")
        append("\"color\": ").append(string(element.color.value.toString(16).padStart(8, '0'))).append(", ")
        append("\"locked\": ").append(element.locked).append(", ")
        append("\"bold\": ").append(element.bold).append(", ")
        append("\"alignment\": ").append(string(element.alignment)).append(", ")
        append("\"format\": ").append(string(element.format)).append(", ")
        append("\"handKind\": ").append(string(element.handKind)).append(", ")
        append("\"length\": ").append(number(element.length)).append(", ")
        append("\"thickness\": ").append(number(element.thickness)).append(", ")
        append("\"rotation\": ").append(number(element.rotation)).append(", ")
        append("\"filled\": ").append(element.filled).append(", ")
        append("\"cornerRadius\": ").append(number(element.cornerRadius))
        append("}")
    }

    private fun number(value: Float): String =
        if (value.isFinite()) value.toString() else "0"

    private fun stringOrNull(value: String?): String =
        value?.let(::string) ?: "null"

    private fun string(value: String): String = buildString {
        append('"')
        value.forEach { ch ->
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(ch)
            }
        }
        append('"')
    }
}
