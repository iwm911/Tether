package app.tether.remote

import app.tether.core.RunRef
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import java.time.Instant

/** A failure the remote side explained in a human sentence (helper `{"error": …}`, missing python…). */
class RemoteException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Optional capability of a [app.tether.core.ClaudeRemote]: transcript lines of the session a run resumed. */
interface RunHistorySource {
    /** Transcript lines of the resumed session written before the run started (empty for fresh runs). */
    suspend fun loadRunHistory(ref: RunRef): List<String>
}

internal object RemoteJson {
    val json: Json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        explicitNulls = false
        encodeDefaults = true
    }

    /** Parses one JSONL line into an object, or null for blanks / non-objects / garbage. */
    fun parseObject(line: String): JsonObject? {
        val t = line.trim()
        if (t.isEmpty() || t[0] != '{') return null
        return try {
            json.parseToJsonElement(t) as? JsonObject
        } catch (_: Exception) {
            null
        }
    }

    fun parseElement(text: String?): JsonElement? {
        if (text.isNullOrBlank()) return null
        return try {
            json.parseToJsonElement(text)
        } catch (_: Exception) {
            null
        }
    }
}

// ───────────────────────────── JsonObject accessors (null-safe, type-tolerant) ─────────────────────────────

internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
internal fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray

internal fun JsonObject.str(key: String): String? {
    val p = this[key] as? JsonPrimitive ?: return null
    return if (p.isString) p.content else null
}

internal fun JsonObject.long(key: String): Long? {
    val p = this[key] as? JsonPrimitive ?: return null
    if (p.isString) return null
    return p.longOrNull ?: p.doubleOrNull?.toLong()
}

internal fun JsonObject.int(key: String): Int? = long(key)?.toInt()

internal fun JsonObject.double(key: String): Double? {
    val p = this[key] as? JsonPrimitive ?: return null
    if (p.isString) return null
    return p.doubleOrNull
}

internal fun JsonObject.bool(key: String): Boolean? {
    val p = this[key] as? JsonPrimitive ?: return null
    if (p.isString) return null
    return p.booleanOrNull
}

internal fun isoToEpochMs(s: String?): Long? {
    if (s.isNullOrBlank()) return null
    return try {
        Instant.parse(s).toEpochMilli()
    } catch (_: Exception) {
        null
    }
}

/** POSIX single-quote shell quoting — safe for any byte sequence except NUL. */
internal fun shellQuote(s: String): String {
    if (s.isEmpty()) return "''"
    if (s.all { it.isLetterOrDigit() || it in "@%+=:,./_-" }) return s
    return "'" + s.replace("'", "'\"'\"'") + "'"
}

/** UTF-8 encoded length of [s] without allocating (surrogate pairs = 4 bytes). */
internal fun utf8Length(s: String): Int {
    var n = 0
    var i = 0
    val len = s.length
    while (i < len) {
        val c = s[i]
        when {
            c.code < 0x80 -> n += 1
            c.code < 0x800 -> n += 2
            Character.isHighSurrogate(c) && i + 1 < len && Character.isLowSurrogate(s[i + 1]) -> {
                n += 4
                i++
            }
            else -> n += 3
        }
        i++
    }
    return n
}

internal fun projectNameOf(path: String): String = path.trimEnd('/').substringAfterLast('/').ifEmpty { "/" }

internal fun friendlyMessage(e: Throwable): String {
    val m = e.message?.trim()
    if (!m.isNullOrEmpty()) return m
    return when (e) {
        is java.net.UnknownHostException -> "Host not found."
        is java.net.SocketTimeoutException -> "The machine did not answer in time."
        is java.net.ConnectException -> "Could not connect to the machine."
        is java.io.IOException -> "The connection to the machine was lost."
        else -> e.javaClass.simpleName
    }
}
