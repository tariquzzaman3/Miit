package com.miit.app

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

internal data class MiitAiOperation(
    val op: String,
    val id: Int? = null,
    val x: Float? = null,
    val y: Float? = null,
    val width: Float? = null,
    val height: Float? = null,
    val rotation: Float? = null,
    val color: String? = null,
    val size: Float? = null,
    val bold: Boolean? = null,
    val alignment: String? = null,
    val filled: Boolean? = null,
    val text: String? = null,
    val dataType: String? = null
)

internal data class MiitAiPlan(
    val message: String,
    val operations: List<MiitAiOperation>
)

internal object MiitAiClient {
    private const val OPENAI_URL = "https://api.openai.com/v1/responses"
    private const val GEMINI_URL = "https://generativelanguage.googleapis.com/v1/interactions"
    private const val OPENROUTER_URL = "https://openrouter.ai/api/v1/chat/completions"

    private const val OPENAI_DEFAULT_MODEL = "gpt-5.6-luna"
    private const val GEMINI_DEFAULT_MODEL = "gemini-3.8-flash"
    private const val OPENROUTER_DEFAULT_MODEL = "openai/gpt-5.6-luna"
    private const val CONNECT_TIMEOUT_MS = 15000
    private const val READ_TIMEOUT_MS = 45000

    private val schema = JSONObject(
        """
        {
          "type":"object",
          "additionalProperties":false,
          "properties":{
            "message":{"type":"string"},
            "operations":{
              "type":"array",
              "items":{
                "type":"object",
                "additionalProperties":false,
                "properties":{
                  "op":{"type":"string"},
                  "id":{"type":["integer","null"]},
                  "x":{"type":["number","null"]},
                  "y":{"type":["number","null"]},
                  "width":{"type":["number","null"]},
                  "height":{"type":["number","null"]},
                  "rotation":{"type":["number","null"]},
                  "color":{"type":["string","null"]},
                  "size":{"type":["number","null"]},
                  "bold":{"type":["boolean","null"]},
                  "alignment":{"type":["string","null"]},
                  "filled":{"type":["boolean","null"]},
                  "text":{"type":["string","null"]},
                  "dataType":{"type":["string","null"]}
                },
                "required":[
                  "op","id","x","y","width","height","rotation",
                  "color","size","bold","alignment","filled","text","dataType"
                ]
              }
            }
          },
          "required":["message","operations"]
        }
        """.trimIndent()
    )

    private val SYSTEM_PROMPT = """
You are MIIT AI, a conversational watch-face design assistant inspired by the interaction
philosophy of creative editors such as PicsArt Assistant.

You operate on a Xiaomi Smart Band watch-face composition represented as editable layers.
Think about hierarchy, spacing, alignment, balance, contrast, legibility, and the small portrait canvas.

Conversation behavior:
- Understand natural requests such as move, resize, recolor, center, clean up, simplify,
  make sporty, make minimal, add heart rate, add text, or rearrange.
- Treat follow-up requests as edits to the current state.
- Use the supplied layer context; do not invent ids.
- Be concise and action-oriented.
- Preserve anything the user explicitly says to keep.

Editor safety:
- Never modify a locked layer.
- x/y are percentages from 0 to 100.
- width/height are editor scale values from 10 to 180.
- rotation is degrees.
- Colors are #RRGGBB or #AARRGGBB.
- Keep important watch information readable and inside the canvas.
- Prefer several moderate edits over extreme changes.

Allowed operations:
move, resize, rotate, color, style, delete, front, back, add_text, add_data,
arrange_grid, center_selected.

Return only JSON matching the supplied schema.
""".trimIndent()

    fun run(
        providerSetting: String,
        apiKey: String,
        prompt: String,
        contextJson: String,
        history: List<String>
    ): Result<MiitAiPlan> = runCatching {
        require(providerSetting.isNotBlank()) { "Choose an AI provider in Settings first." }
        require(apiKey.isNotBlank()) { "Add an AI API key in Settings first." }
        val requestText = buildUserPrompt(prompt, contextJson, history)
        val raw = when (providerSetting.trim().lowercase()) {
            "gemini" -> callGemini(apiKey.trim(), requestText, providerSetting)
            "google" -> callGemini(apiKey.trim(), requestText, providerSetting)
            "openrouter" -> callOpenRouter(apiKey.trim(), requestText, providerSetting)
            else -> callOpenAi(apiKey.trim(), requestText, providerSetting)
        }
        parsePlan(raw)
    }

    private fun buildUserPrompt(prompt: String, contextJson: String, history: List<String>): String {
        val historyText = history.takeLast(8).joinToString("\n")
        return buildString {
            appendLine("CURRENT WATCH-FACE STATE:")
            appendLine(contextJson)
            appendLine()
            appendLine("RECENT CONVERSATION:")
            appendLine(if (historyText.isBlank()) "(none)" else historyText)
            appendLine()
            appendLine("USER REQUEST:")
            appendLine(prompt)
            appendLine()
            append("Choose the safest useful editable operations now. Use an empty operation list when no change is needed.")
        }
    }

    private fun callOpenAi(apiKey: String, requestText: String, providerSetting: String): String {
        val model = providerSetting.substringAfter(":", "").trim().ifBlank { OPENAI_DEFAULT_MODEL }
        val input = JSONArray()
            .put(
                JSONObject()
                    .put("role", "system")
                    .put("content", JSONArray().put(JSONObject().put("type", "input_text").put("text", SYSTEM_PROMPT)))
            )
            .put(
                JSONObject()
                    .put("role", "user")
                    .put("content", JSONArray().put(JSONObject().put("type", "input_text").put("text", requestText)))
            )
        val body = JSONObject()
            .put("model", model)
            .put("input", input)
            .put(
                "text",
                JSONObject().put(
                    "format",
                    JSONObject()
                        .put("type", "json_schema")
                        .put("name", "miit_editor_plan")
                        .put("strict", true)
                        .put("schema", schema)
                )
            )
        return postJson(OPENAI_URL, apiKey, body, emptyMap())
    }

    private fun callGemini(apiKey: String, requestText: String, providerSetting: String): String {
        val model = providerSetting.substringAfter(":", "").trim().ifBlank { GEMINI_DEFAULT_MODEL }
        val body = JSONObject()
            .put("model", model)
            .put("input", requestText)
            .put("system_instruction", SYSTEM_PROMPT)
            .put(
                "response_format",
                JSONObject()
                    .put("type", "text")
                    .put("mime_type", "application/json")
                    .put("schema", schema)
            )
            .put("store", false)
        return postJson(GEMINI_URL + "?key=" + java.net.URLEncoder.encode(apiKey, "UTF-8"), null, body, emptyMap())
    }

    private fun callOpenRouter(apiKey: String, requestText: String, providerSetting: String): String {
        val model = providerSetting.substringAfter(":", "").trim().ifBlank { OPENROUTER_DEFAULT_MODEL }
        val body = JSONObject()
            .put("model", model)
            .put(
                "messages",
                JSONArray()
                    .put(JSONObject().put("role", "system").put("content", SYSTEM_PROMPT))
                    .put(JSONObject().put("role", "user").put("content", requestText))
            )
            .put("response_format", JSONObject().put("type", "json_object"))
            .put("temperature", 0.2)
        return postJson(
            OPENROUTER_URL,
            apiKey,
            body,
            mapOf(
                "HTTP-Referer" to "https://github.com/tariquzzaman3/Miit",
                "X-Title" to "MIIT Xiaomi Band Watch Face Editor"
            )
        )
    }

    private fun postJson(
        endpoint: String,
        apiKey: String?,
        body: JSONObject,
        extraHeaders: Map<String, String>
    ): String {
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            if (!apiKey.isNullOrBlank()) setRequestProperty("Authorization", "Bearer " + apiKey)
            extraHeaders.forEach { (name, value) -> setRequestProperty(name, value) }
        }
        try {
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val responseText = BufferedReader(InputStreamReader(stream ?: error("AI provider returned no response"), Charsets.UTF_8)).use { it.readText() }
            if (status !in 200..299) {
                val detail = runCatching {
                    val error = JSONObject(responseText).optString("error")
                    if (error.isBlank()) responseText.take(500) else error
                }.getOrDefault(responseText.take(500))
                error("AI provider HTTP " + status + ": " + detail)
            }
            return responseText
        } finally {
            connection.disconnect()
        }
    }

    private fun parsePlan(rawResponse: String): MiitAiPlan {
        val jsonText = extractJsonText(rawResponse)
            .trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        val root = JSONObject(jsonText)
        val operationsJson = root.optJSONArray("operations") ?: JSONArray()
        val operations = mutableListOf<MiitAiOperation>()
        for (index in 0 until operationsJson.length()) {
            val item = operationsJson.optJSONObject(index) ?: continue
            operations += MiitAiOperation(
                op = item.optString("op"),
                id = item.nullableInt("id"),
                x = item.nullableFloat("x"),
                y = item.nullableFloat("y"),
                width = item.nullableFloat("width"),
                height = item.nullableFloat("height"),
                rotation = item.nullableFloat("rotation"),
                color = item.nullableString("color"),
                size = item.nullableFloat("size"),
                bold = item.nullableBoolean("bold"),
                alignment = item.nullableString("alignment"),
                filled = item.nullableBoolean("filled"),
                text = item.nullableString("text"),
                dataType = item.nullableString("dataType")
            )
        }
        return MiitAiPlan(root.optString("message").ifBlank { "Done." }, operations)
    }

    private fun extractJsonText(rawResponse: String): String {
        val root = JSONObject(rawResponse)
        root.optString("output_text").takeIf { it.isNotBlank() }?.let { return it }
        root.optString("text").takeIf { it.isNotBlank() }?.let { return it }

        root.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.let { message ->
            message.optString("content").takeIf { it.isNotBlank() }?.let { return it }
        }

        val outputs = root.optJSONArray("outputs")
        if (outputs != null) {
            for (i in 0 until outputs.length()) {
                val content = outputs.optJSONObject(i)?.optJSONArray("content") ?: continue
                for (j in 0 until content.length()) {
                    val text = content.optJSONObject(j)?.optString("text").orEmpty()
                    if (text.isNotBlank()) return text
                }
            }
        }

        val steps = root.optJSONArray("steps")
        if (steps != null) {
            for (i in 0 until steps.length()) {
                val content = steps.optJSONObject(i)?.optJSONArray("content") ?: continue
                for (j in 0 until content.length()) {
                    val text = content.optJSONObject(j)?.optString("text").orEmpty()
                    if (text.isNotBlank()) return text
                }
            }
        }
        error("AI provider returned no text result")
    }

    private fun JSONObject.nullableInt(name: String): Int? =
        if (!has(name) || isNull(name)) null else optInt(name)

    private fun JSONObject.nullableFloat(name: String): Float? =
        if (!has(name) || isNull(name)) null else optDouble(name).toFloat()

    private fun JSONObject.nullableBoolean(name: String): Boolean? =
        if (!has(name) || isNull(name)) null else optBoolean(name)

    private fun JSONObject.nullableString(name: String): String? =
        if (!has(name) || isNull(name)) null else optString(name).takeIf { it.isNotBlank() }
}