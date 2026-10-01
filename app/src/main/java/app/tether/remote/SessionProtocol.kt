package app.tether.remote

import app.tether.core.AskAnswer
import app.tether.core.DaemonStatus
import app.tether.core.FollowEvent
import app.tether.core.NewSessionRequest
import app.tether.core.PeerDirection
import app.tether.core.PeerMessage
import app.tether.core.Session
import app.tether.core.SessionDecision
import app.tether.core.SessionKey
import app.tether.core.SessionTask
import app.tether.core.SessionTodo
import app.tether.core.SubagentInfo
import app.tether.core.WatchMessage
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Codec for the helper ↔ app protocol of the one-session model (HELPER_VERSION 2.0.0, see
 * docs/plans/one-session-daemon.md). Pure Kotlin; parsing is tolerant: an unreadable line or a
 * line of a kind this app does not know yet is skipped (null), never an exception.
 */
object SessionProtocol {
    private val json = RemoteJson.json

    // ───────────────────────────── sessions ─────────────────────────────

    fun decodeSession(element: JsonElement): Session? = try {
        json.decodeFromJsonElement(Session.serializer(), element)
    } catch (e: Exception) {
        null
    }

    fun decodeSessions(arr: JsonArray?): List<Session> = arr?.mapNotNull { decodeSession(it) }.orEmpty()

    /** `{"sessions":[…]}` (also accepts a bare array). */
    fun parseSessionList(text: String): List<Session> {
        val el = RemoteJson.parseElement(text.trim()) ?: return emptyList()
        return when (el) {
            is JsonObject -> decodeSessions(el.arr("sessions"))
            is JsonArray -> decodeSessions(el)
            else -> emptyList()
        }
    }

    fun parseDaemonStatus(text: String): DaemonStatus? {
        val o = RemoteJson.parseObject(text) ?: return null
        val d = o.obj("daemon") ?: o
        return try { json.decodeFromJsonElement(DaemonStatus.serializer(), d) } catch (e: Exception) { null }
    }

    // ───────────────────────────── watch ─────────────────────────────

    /** One line of `watch`. Throws [RemoteException] for an `{"error":…}` line. */
    fun parseWatchLine(line: String): WatchMessage? {
        val o = RemoteJson.parseObject(line) ?: return null
        throwIfError(o)
        o.arr("snapshot")?.let { return WatchMessage.Snapshot(decodeSessions(it)) }
        if (o.containsKey("changed") || o.containsKey("removed")) {
            val removed = o.arr("removed")?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }.orEmpty()
            return WatchMessage.Changed(decodeSessions(o.arr("changed")), removed)
        }
        o.long("hb")?.let { return WatchMessage.Heartbeat(it) }
        return null
    }

    // ───────────────────────────── follow ─────────────────────────────

    /** One line of `follow`. Throws [RemoteException] for an `{"error":…}` line. */
    fun parseFollowLine(line: String): FollowEvent? {
        val o = RemoteJson.parseObject(line) ?: return null
        throwIfError(o)
        return when (o.str("e")) {
            "line" -> {
                val l = o["line"] as? JsonObject ?: return null
                FollowEvent.Line(l.toString(), l.str("uuid"), o.long("offset"))
            }
            "draft" -> FollowEvent.Draft(o.str("text").orEmpty())
            "draftClear" -> FollowEvent.DraftClear
            "status" -> FollowEvent.Status(o.str("verb")?.takeIf { it.isNotBlank() }, o.long("elapsedS"), o.long("tokens"))
            "state" -> o["session"]?.let { decodeSession(it) }?.let { FollowEvent.State(it) }
            "peer" -> {
                val text = o.str("text") ?: return null
                FollowEvent.Peer(
                    PeerMessage(
                        dir = if (o.str("dir") == "out") PeerDirection.OUT else PeerDirection.IN,
                        peer = o.str("from") ?: o.str("to"),
                        peerName = o.str("fromName") ?: o.str("toName"),
                        peerSessionId = o.str("fromSessionId") ?: o.str("toSessionId"),
                        text = text,
                        at = o.long("at"),
                    ),
                )
            }
            "subagent" -> decode(o, SubagentInfo.serializer())?.let { FollowEvent.Subagent(it) }
            "task" -> decode(o, SessionTask.serializer())?.let { FollowEvent.Task(it) }
            "todos" -> FollowEvent.Todos(
                listId = o.str("listId") ?: "",
                items = (o.arr("items") ?: JsonArray(emptyList())).mapNotNull { decode(it, SessionTodo.serializer()) },
            )
            "caughtUp" -> FollowEvent.CaughtUp(o.long("offset") ?: 0L)
            else -> null
        }
    }

    private fun <T> decode(e: JsonElement, s: kotlinx.serialization.KSerializer<T>): T? = try {
        json.decodeFromJsonElement(s, e)
    } catch (x: Exception) {
        null
    }

    /** `{"error": "…", "code": "…"}` → [RemoteException] carrying the code. */
    fun throwIfError(o: JsonObject) {
        val err = o.str("error") ?: return
        throw RemoteException(err, code = o.str("code"))
    }

    // ───────────────────────────── request bodies (stdin JSON) ─────────────────────────────

    fun newBody(r: NewSessionRequest): String = buildJsonObject {
        put("cwd", r.cwd)
        put("prompt", r.prompt)
        r.model?.let { put("model", it) }
        r.permissionMode?.let { put("permissionMode", it) }
        if (r.images.isNotEmpty()) put("images", stringArray(r.images))
        if (r.trust) put("trust", true)
    }.toString()

    fun sendBody(text: String, images: List<String>): String = buildJsonObject {
        put("text", text)
        if (images.isNotEmpty()) put("images", stringArray(images))
    }.toString()

    fun keyBody(keys: List<SessionKey>): String = buildJsonObject {
        put("keys", buildJsonArray {
            for (k in keys) when (k) {
                is SessionKey.Named -> add(JsonPrimitive(k.name))
                is SessionKey.Text -> add(buildJsonObject { put("text", k.text) })
            }
        })
    }.toString()

    fun answerBody(decision: SessionDecision, message: String?): String = buildJsonObject {
        put("decision", decision.wire)
        message?.trim()?.takeIf { it.isNotEmpty() }?.let { put("message", it) }
    }.toString()

    fun askBody(answers: List<AskAnswer>): String = buildJsonObject {
        put("answers", buildJsonArray {
            for (a in answers) add(buildJsonObject {
                put("choices", JsonArray(a.choices.map { JsonPrimitive(it) }))
                put("other", a.other?.trim()?.takeIf { it.isNotEmpty() }?.let { JsonPrimitive(it) } ?: kotlinx.serialization.json.JsonNull)
            })
        })
    }.toString()

    private fun stringArray(items: List<String>) = JsonArray(items.map { JsonPrimitive(it) })

    // ───────────────────────────── argv ─────────────────────────────

    fun sessionsArgs(cwd: String?, limit: Int?, before: Long?): List<String> = buildList {
        add("sessions")
        if (!cwd.isNullOrBlank()) { add("--cwd"); add(cwd) }
        if (limit != null) { add("--limit"); add(limit.coerceIn(1, 1000).toString()) }
        if (before != null) { add("--before"); add(before.toString()) }
    }

    fun followArgs(sessionId: String, agentId: String?, fromOffset: Long): List<String> = buildList {
        add("follow"); add(sessionId)
        if (!agentId.isNullOrBlank()) { add("--agent"); add(agentId) }
        if (fromOffset > 0) { add("--from"); add(fromOffset.toString()) }
    }
}
