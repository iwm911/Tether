package app.tether.remote

import app.tether.core.ChatItem
import app.tether.core.FollowEvent
import app.tether.core.PeerDirection
import app.tether.core.RunStatus
import app.tether.core.Session
import app.tether.core.SessionPending
import app.tether.core.SessionRef
import app.tether.core.SessionState
import app.tether.core.SubagentStatus
import app.tether.core.WatchMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The helper's REAL output (fixtures/contract, captured by tools/helper_tests/test_contract.py, which also
 * fails when the helper's output drifts from them) through the app's parsers and reducer: every line is
 * understood, and every field the helper sends survives into the Kotlin models unchanged.
 */
class HelperContractTest {

    private val explicit = Json { encodeDefaults = true; explicitNulls = true }

    private fun fixture(name: String): String =
        (javaClass.classLoader!!.getResource("fixtures/contract/$name") ?: error("missing fixture $name")).readText()

    private fun lines(name: String) = fixture(name).lines().filter { it.isNotBlank() }

    private fun obj(s: String) = RemoteJson.parseObject(s) ?: error("not an object: $s")

    /** A Session encoded back to JSON as the helper would write it (nulls explicit, no app-only fields). */
    private fun encode(s: Session): JsonObject {
        val o = explicit.encodeToJsonElement(Session.serializer(), s) as JsonObject
        return JsonObject(o - "connectionId")
    }

    private fun same(a: JsonElement?, b: JsonElement?): Boolean {
        val x = a ?: JsonNull
        val y = b ?: JsonNull
        if (x is JsonObject && y is JsonObject) return (x.keys + y.keys).all { same(x[it], y[it]) }
        if (x is JsonArray && y is JsonArray) return x.size == y.size && x.indices.all { same(x[it], y[it]) }
        if (x is JsonPrimitive && y is JsonPrimitive && !x.isString && !y.isString && x !is JsonNull && y !is JsonNull) {
            return x.content.toDoubleOrNull() == y.content.toDoubleOrNull() || x.content == y.content
        }
        return x == y
    }

    /** Every key the helper sent is a model field, with the same value after decoding. */
    private fun assertRoundTrips(raw: JsonObject, what: String) {
        val decoded = SessionProtocol.decodeSession(raw) ?: return fail("$what: not decoded: $raw")
        val back = encode(decoded)
        for ((k, v) in raw) {
            assertTrue("$what: helper field '$k' has no model field", k in back)
            assertTrue("$what: '$k' changed: helper=$v app=${back[k]}", same(v, back[k]))
        }
    }

    @Test
    fun sessionsRoundTripEveryField() {
        val raw = obj(fixture("sessions.json")).arr("sessions")!!.map { it as JsonObject }
        val parsed = SessionProtocol.parseSessionList(fixture("sessions.json"))
        assertEquals(raw.size, parsed.size)
        raw.forEach { assertRoundTrips(it, "session ${it.str("short")}") }
        val states = parsed.map { it.state }.toSet()
        assertEquals(setOf(SessionState.WORKING, SessionState.NEEDS_YOU, SessionState.DONE, SessionState.FAILED), states)
        val q = parsed.mapNotNull { it.pending }.single() as SessionPending.Question
        assertEquals("AskUserQuestion", q.toolName)
        assertTrue(q.inputJson.contains("Which color?"))
        // A tool permission (needs_you on "permission prompt", the unanswered tool_use in the transcript).
        val permRaw = obj(fixture("session_permission.json"))
        assertRoundTrips(permRaw, "permission session")
        val p = SessionProtocol.decodeSession(permRaw)!!.pending as SessionPending.Permission
        assertEquals("Bash", p.toolName)
        assertTrue(p.inputJson.contains("rm -rf build"))
        parsed.forEach { assertEquals(it.sessionId.take(8), it.short) }
    }

    @Test
    fun daemonStatus() {
        val st = SessionProtocol.parseDaemonStatus(fixture("daemon_status.json"))!!
        assertTrue(st.running)
        assertEquals(1, st.proto)
        assertEquals("2.1.287", st.version)
    }

    @Test
    fun watchLines() {
        val ls = lines("watch.jsonl")
        val msgs = ls.map { SessionProtocol.parseWatchLine(it) }
        val snap = msgs[0] as WatchMessage.Snapshot
        assertEquals(obj(ls[0]).arr("snapshot")!!.size, snap.sessions.size)
        obj(ls[0]).arr("snapshot")!!.forEach { assertRoundTrips(it as JsonObject, "watch snapshot") }
        val changed = msgs[1] as WatchMessage.Changed
        assertEquals(listOf("renamed job"), changed.changed.map { it.name })
        assertTrue(changed.removed.isEmpty())
        val removed = msgs[2] as WatchMessage.Changed
        assertTrue(removed.changed.isEmpty())
        assertEquals(1, removed.removed.size)
    }

    private fun followEvents(name: String): List<FollowEvent> {
        val ls = lines(name)
        return ls.map { l -> SessionProtocol.parseFollowLine(l) ?: error("follow line not understood: ${l.take(200)}") }
    }

    @Test
    fun followHistoryEveryEventUnderstood() {
        val raw = lines("follow_history.jsonl").map { obj(it) }
        val evs = followEvents("follow_history.jsonl")
        assertEquals(raw.size, evs.size)
        // Field-by-field: what the helper sent is what the app holds.
        for ((r, e) in raw.zip(evs)) when (e) {
            is FollowEvent.Line -> {
                assertEquals(r.long("offset"), e.offset)
                assertEquals(r.obj("line")!!.str("uuid"), e.uuid)
            }
            is FollowEvent.Peer -> {
                assertEquals(PeerDirection.IN, e.message.dir)
                assertEquals(r.str("from"), e.message.peer)
                assertEquals(r.str("fromName"), e.message.peerName)
                assertEquals(r.str("fromSessionId"), e.message.peerSessionId)
                assertEquals(r.str("text"), e.message.text)
                assertEquals(r.long("at"), e.message.at)
            }
            is FollowEvent.Subagent -> {
                assertEquals(r.str("agentId"), e.info.agentId)
                assertEquals(r.str("agentType"), e.info.agentType)
                assertEquals(r.str("toolUseId"), e.info.toolUseId)
                assertEquals(r.bool("background"), e.info.background)
                assertEquals(r.str("status"), e.info.status.name.lowercase())
            }
            is FollowEvent.Task -> {
                assertEquals(r.str("taskId"), e.task.taskId)
                assertEquals(r.str("kind"), e.task.kind.name.lowercase())
                assertEquals(r.str("status"), e.task.status.name.lowercase())
                assertEquals(r.str("summary"), e.task.summary)
                assertEquals(r.str("outputFile"), e.task.outputFile)
            }
            is FollowEvent.Todos -> {
                assertEquals(r.str("listId"), e.listId)
                assertEquals(r.arr("items")!!.size, e.items.size)
            }
            is FollowEvent.State -> assertRoundTrips(r.obj("session")!!, "follow state")
            is FollowEvent.CaughtUp -> assertEquals(r.long("offset"), e.offset)
            else -> fail("unexpected history event $e")
        }

        val sid = evs.filterIsInstance<FollowEvent.State>().first().session.sessionId
        val r = SessionReducer(SessionRef("m", sid)) { 1_790_870_000_000L }
        evs.forEach { r.accept(it) }
        val c = r.snapshot()
        val live = c.live!!
        assertTrue(live.caughtUp)
        assertEquals((evs.last() as FollowEvent.CaughtUp).offset, live.offset)
        assertEquals(2, live.subagents.size)
        assertTrue(live.subagents.all { it.status == SubagentStatus.DONE })
        assertEquals(1, live.peers.count { it.dir == PeerDirection.IN })
        assertEquals(2, live.tasks.size)
        assertTrue(c.todos.isNotEmpty())
        assertEquals(RunStatus.IDLE, c.status) // retired, nothing running
        assertFalse(c.loadingHistory)
        assertNull(live.draft)
        // The peer's <cross-session-message> user line is not shown twice.
        assertFalse(c.items.any { it is ChatItem.User && it.text.contains("cross-session-message") })
        assertEquals(2, c.items.count { it is ChatItem.AssistantText })
    }

    @Test
    fun followLiveDraftsThenTheLine() {
        val raw = lines("follow_live.jsonl").map { obj(it) }
        val evs = followEvents("follow_live.jsonl")
        assertEquals(raw.size, evs.size)
        val sid = evs.filterIsInstance<FollowEvent.State>().first().session.sessionId
        var now = 1_790_870_000_000L
        val r = SessionReducer(SessionRef("m", sid)) { now }
        var sawDraftRow = 0
        var sawStatus = false
        var finalText: String? = null
        for ((o, e) in raw.zip(evs)) {
            now += 30
            r.accept(e)
            val c = r.snapshot()
            when (e) {
                is FollowEvent.Draft -> {
                    assertEquals(o.str("text"), e.text)
                    val last = c.items.last() as ChatItem.AssistantText
                    assertTrue(last.streaming)
                    assertEquals(e.text, last.text)
                    sawDraftRow++
                }
                is FollowEvent.Status -> if (e.verb != null) {
                    assertEquals(o.str("verb"), c.live!!.status!!.verb)
                    assertEquals(RunStatus.WORKING, c.status)
                    assertNotNull(c.workingSince)
                    sawStatus = true
                }
                is FollowEvent.Line -> if (o.obj("line")!!.str("type") == "assistant") {
                    finalText = c.items.filterIsInstance<ChatItem.AssistantText>().lastOrNull { !it.streaming }?.text
                }
                else -> Unit
            }
        }
        val end = r.snapshot()
        assertTrue("drafts became the streaming row ($sawDraftRow)", sawDraftRow >= 3)
        assertTrue(sawStatus)
        assertNull("draft gone once the line landed", end.live!!.draft)
        assertFalse(end.items.any { it is ChatItem.AssistantText && it.streaming })
        assertTrue(finalText!!.contains("END-OF-REPLY"))
        assertEquals(1, end.items.count { it is ChatItem.AssistantText && it.text.contains("END-OF-REPLY") })
    }

    @Test
    fun writeRepliesAndErrorCodes() {
        for (l in lines("writes.jsonl")) {
            val w = obj(l)
            val cmd = w.str("cmd")!!
            val out = w.obj("out")!!
            if (w.long("rc") != 0L) {
                try {
                    SessionProtocol.throwIfError(out)
                    fail("$cmd: no error raised")
                } catch (e: RemoteException) {
                    assertEquals(out.str("code"), e.code)
                    assertEquals(out.str("error"), e.message)
                }
                continue
            }
            when (cmd) {
                "send", "send-wake" -> assertEquals(cmd == "send-wake", out.bool("woke"))
                "key", "stop", "rm" -> assertEquals(true, out.bool("ok"))
                "interrupt", "new" -> assertRoundTrips(out, cmd)
                else -> fail("unknown write $cmd")
            }
        }
    }
}
