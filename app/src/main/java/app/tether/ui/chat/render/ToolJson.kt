package app.tether.ui.chat.render

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/* Defensive JSON access for tool inputs / results. Nothing here throws. */

internal val ToolJson = Json { ignoreUnknownKeys = true; isLenient = true }

@OptIn(ExperimentalSerializationApi::class)
private val PrettyJson = Json { prettyPrint = true; prettyPrintIndent = "  " }

internal fun parseJson(text: String?): JsonElement? {
    if (text.isNullOrBlank()) return null
    return try { ToolJson.parseToJsonElement(text) } catch (t: Throwable) { null }
}

internal fun parseJsonObject(text: String?): JsonObject? = parseJson(text) as? JsonObject

internal fun prettyJson(el: JsonElement): String = try {
    PrettyJson.encodeToString(JsonElement.serializer(), el)
} catch (t: Throwable) {
    el.toString()
}

/** Pretty-prints [text] when it is valid JSON, else returns it unchanged. */
internal fun prettyJsonText(text: String): String = parseJson(text)?.let { prettyJson(it) } ?: text

internal fun JsonObject.str(key: String): String? {
    val p = this[key] as? JsonPrimitive ?: return null
    if (p is JsonNull) return null
    return p.content
}

internal fun JsonObject.int(key: String): Int? = str(key)?.toDoubleOrNull()?.toInt()

internal fun JsonObject.long(key: String): Long? = str(key)?.toDoubleOrNull()?.toLong()

internal fun JsonObject.bool(key: String): Boolean? = when (str(key)?.lowercase()) {
    "true" -> true
    "false" -> false
    else -> null
}

internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

internal fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray

internal fun JsonArray.strings(): List<String> = mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p !is JsonNull }?.content }

/** Human text of a JSON value for key/value rows: strings unquoted, containers pretty-printed. */
internal fun JsonElement.displayText(): String = when (this) {
    is JsonNull -> "null"
    is JsonPrimitive -> content
    else -> prettyJson(this)
}

/**
 * Pulls a string field out of possibly-incomplete JSON (tool input while it streams in),
 * e.g. `{"file_path":"/src/Ap` → `/src/Ap`.
 */
internal fun partialString(json: String, key: String): String? {
    val idx = json.indexOf("\"$key\"")
    if (idx < 0) return null
    var i = idx + key.length + 2
    while (i < json.length && json[i].isWhitespace()) i++
    if (i >= json.length || json[i] != ':') return null
    i++
    while (i < json.length && json[i].isWhitespace()) i++
    if (i >= json.length || json[i] != '"') return null
    i++
    val sb = StringBuilder()
    while (i < json.length) {
        val c = json[i]
        if (c == '"') break
        if (c == '\\' && i + 1 < json.length) {
            when (val e = json[i + 1]) {
                'n' -> sb.append('\n')
                't' -> sb.append('\t')
                'r' -> {}
                'u' -> {
                    if (i + 5 < json.length) {
                        json.substring(i + 2, i + 6).toIntOrNull(16)?.let { sb.append(it.toChar()) }
                        i += 4
                    }
                }
                else -> sb.append(e)
            }
            i += 2
            continue
        }
        sb.append(c)
        i++
    }
    return sb.toString()
}

private val ANSI = Regex("\u001B\\[[0-9;?]*[ -/]*[@-~]|\u001B\\][^\u0007]*\u0007")
private val SYSTEM_REMINDER = Regex("<system-reminder>[\\s\\S]*?(?:</system-reminder>|$)")

/** Removes ANSI escapes and injected `<system-reminder>` blocks from tool output. */
internal fun cleanOutput(text: String): String =
    text.replace(ANSI, "").replace(SYSTEM_REMINDER, "").replace("\r\n", "\n").trimEnd()
