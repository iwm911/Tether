package app.tether.remote

import app.tether.core.AskAnswer
import app.tether.core.DialogKind
import app.tether.core.FollowEvent
import app.tether.core.Holder
import app.tether.core.NewSessionRequest
import app.tether.core.PeerDirection
import app.tether.core.Session
import app.tether.core.SessionDecision
import app.tether.core.SessionErrorCodes
import app.tether.core.SessionKey
import app.tether.core.SessionPending
import app.tether.core.SessionProcess
import app.tether.core.SessionRef
import app.tether.core.SessionState
import app.tether.core.SessionTaskKind
import app.tether.core.SessionTaskStatus
import app.tether.core.SubagentStatus
import app.tether.core.WatchMessage
import app.tether.core.identity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Parsing of the helper ↔ app protocol (HELPER_VERSION 2.0.0) and the request bodies the app sends. */
class SessionProtocolTest {

    private fun fixture(name: String): String =
        (javaClass.classLoader!!.getResource("fixtures/$name") ?: error("missing fixture $name")).readText()

    private fun lines(name: String) = fixture(name).lines().filter { it.isNotBlank() }

    // ───────────── sessions ─────────────

    @Test
    fun parsesEverySessionFieldOfTheContract() {
        val list = SessionProtocol.parseSessionList(fixture("session/sessions.json"))
        assertEquals(7, list.size)
        val s = list[0]
        assertEquals("7b9f8c4c-6ac7-4d5a-9111-003abd24390e", s.sessionId)
        assertEquals("7b9f8c4c", s.short)
        assertEquals("/home/user/tether-exp/live", s.cwd)
        assertEquals("tether h1 probe", s.name)
        assertEquals("Reply with exactly: PONG-ONE. Do not use any tools.", s.intent)
        assertEquals(SessionState.WORKING, s.state)
        assertNull(s.waitingFor)
        assertNull(s.pending)
        assertEquals(SessionProcess.LIVE, s.process)
        assertEquals(Holder.DAEMON, s.heldBy)
        assertNull(s.terminalPid)
        assertEquals(1790874905661L, s.startedAt)
        assertEquals(1790875176996L, s.updatedAt)
        assertEquals("PONG-FOUR", s.lastText)
        assertEquals(2709L, s.tokens)
        assertEquals("haiku", s.model)
        assertEquals("acceptEdits", s.permissionMode)
        assertEquals(1, s.inFlight.tasks)
        assertEquals(2, s.inFlight.queued)
        assertEquals(listOf("shell"), s.inFlight.kinds)
        assertEquals(1, s.children.size)
        assertEquals("3557", s.children[0].id)
        assertEquals("https://github.com/o/r/pull/3557", s.children[0].href)
        assertEquals("pr", s.children[0].kind)
        assertEquals("main", s.gitBranch)
        assertEquals("tether h1 probe", s.title)
        assertTrue(s.isLive)
        assertFalse(s.needsYou)
    }

    @Test
    fun parsesPermissionQuestionAndDialogPending() {
        val list = SessionProtocol.parseSessionList(fixture("session/sessions.json"))
        val perm = list[1].pending as SessionPending.Permission
        assertEquals("toolu_01PERM", perm.toolUseId)
        assertEquals("Bash", perm.toolName)
        assertEquals("npm test", perm.summary)
        assertEquals("{\"command\":\"npm test\"}", perm.inputJson)
        assertEquals("perm:toolu_01PERM", perm.identity)
        assertTrue(list[1].needsYou)
        assertEquals("permission", list[1].waitingFor)
        // no name → the first prompt is the title
        assertEquals("Run the tests", list[1].title)

        val q = list[2].pending as SessionPending.Question
        assertEquals("toolu_01ASK", q.toolUseId)
        assertEquals("AskUserQuestion", q.toolName)
        assertEquals("Which database?", q.summary)
        assertTrue(q.inputJson.contains("Postgres"))

        val d = list[3].pending as SessionPending.Dialog
        assertEquals(DialogKind.MCP_SERVERS, d.dialog)
        assertEquals("2 new MCP servers found in .mcp.json", d.title)
        assertEquals("Select any you wish to enable.", d.body)
        assertEquals(2, d.options.size)
        assertEquals("fs", d.options[0].label)
        assertEquals(true, d.options[0].checked)
        assertEquals("1", d.options[0].key)
        assertEquals(false, d.options[1].checked)
        assertEquals(listOf("up", "down", "space", "enter", "esc"), d.keys)
    }

    @Test
    fun terminalHeldSession() {
        val s = SessionProtocol.parseSessionList(fixture("session/sessions.json"))[4]
        assertEquals(Holder.TERMINAL, s.heldBy)
        assertTrue(s.heldByTerminal)
        assertEquals(4242, s.terminalPid)
    }

    @Test
    fun missingAndUnknownFieldsFallBackToDefaults() {
        val list = SessionProtocol.parseSessionList(fixture("session/sessions.json"))
        val sparse = list[5]
        assertEquals("eeeeeeee", sparse.short) // short derived from the session id
        assertEquals(SessionState.DONE, sparse.state)
        assertEquals(SessionProcess.RETIRED, sparse.process)
        assertEquals(Holder.NONE, sparse.heldBy)
        assertEquals("", sparse.name)
        assertEquals("old", sparse.title) // project folder
        assertEquals(0, sparse.inFlight.tasks)

        val odd = list[6]
        assertEquals(SessionState.IDLE, odd.state) // unknown state value → default
        val d = odd.pending as SessionPending.Dialog // unknown pending kind → generic dialog
        assertEquals(DialogKind.OTHER, d.dialog)
        assertEquals("Auto mode", d.title)
        assertEquals("Something new", d.body)
    }

    @Test
    fun sessionRoundTripsThroughJson() {
        val list = SessionProtocol.parseSessionList(fixture("session/sessions.json"))
        val json = RemoteJson.json
        for (s in list.take(5)) {
            val text = json.encodeToString(Session.serializer(), s)
            assertEquals(s, json.decodeFromString(Session.serializer(), text))
        }
        // the dialog pending keeps its kind on the wire
        val enc = json.encodeToString(Session.serializer(), list[3])
        assertEquals("dialog", Json.parseToJsonElement(enc).jsonObject["pending"]!!.jsonObject["kind"].toString().trim('"'))
    }

    @Test
    fun refShortIsTheDaemonShortOfH1Fixture() {
        // H1's daemon fixture: state.json carries sessionId and daemonShort = sessionId[:8].
        val st = RemoteJson.parseObject(fixture("daemon/state_live_idle.json"))!!
        val ref = SessionRef("c", st.str("sessionId")!!)
        assertEquals(st.str("daemonShort"), ref.short)
        val reg = RemoteJson.parseObject(fixture("daemon/registry_bg_idle.json"))!!
        assertEquals(reg.str("jobId"), SessionRef("c", reg.str("sessionId")!!).short)
    }

    @Test
    fun bareArrayAndGarbageSessionLists() {
        assertEquals(1, SessionProtocol.parseSessionList("""[{"sessionId":"abcdef0123"}]""").size)
        assertTrue(SessionProtocol.parseSessionList("nope").isEmpty())
        // an entry without a sessionId is skipped, the rest survive
        assertEquals(1, SessionProtocol.parseSessionList("""{"sessions":[{"cwd":"/x"},{"sessionId":"s1"}]}""").size)
    }

    @Test
    fun daemonStatusFromCommandAndProbe() {
        val a = SessionProtocol.parseDaemonStatus("""{"running":true,"proto":1,"version":"2.1.287","auth":"ok","pid":42}""")!!
        assertTrue(a.running)
        assertEquals(1, a.proto)
        assertEquals("2.1.287", a.version)
        assertEquals("ok", a.auth)
        val b = SessionProtocol.parseDaemonStatus("""{"hostname":"h","daemon":{"running":false,"auth":"needs_login"}}""")!!
        assertFalse(b.running)
        assertEquals("needs_login", b.auth)
    }

    // ───────────── watch ─────────────

    @Test
    fun parsesWatchStream() {
        val msgs = lines("session/watch.jsonl").map { SessionProtocol.parseWatchLine(it) }
        val snap = msgs[0] as WatchMessage.Snapshot
        assertEquals(2, snap.sessions.size)
        assertEquals(1790875200000L, (msgs[1] as WatchMessage.Heartbeat).at)
        val ch = msgs[2] as WatchMessage.Changed
        assertEquals(SessionState.NEEDS_YOU, ch.changed.single().state)
        assertTrue(ch.removed.isEmpty())
        val rm = msgs[3] as WatchMessage.Changed
        assertEquals(listOf("dddddddd-1111-4222-8333-444444444444"), rm.removed)
        assertNull(msgs[5]) // unknown object
        assertNull(msgs[6]) // not JSON
    }

    @Test
    fun errorLinesCarryTheirCode() {
        try {
            SessionProtocol.parseWatchLine("""{"error":"The Claude Code daemon is not running.","code":"ENODAEMON"}""")
            fail("expected an exception")
        } catch (e: RemoteException) {
            assertEquals("The Claude Code daemon is not running.", e.message)
            assertEquals(SessionErrorCodes.ENODAEMON, e.code)
        }
        try {
            SessionProtocol.parseFollowLine("""{"error":"no such session","code":"ENOSESSION"}""")
            fail("expected an exception")
        } catch (e: RemoteException) {
            assertEquals(SessionErrorCodes.ENOSESSION, e.code)
        }
    }

    // ───────────── follow ─────────────

    @Test
    fun parsesEveryFollowEvent() {
        val turn = lines("session/follow_turn.jsonl").map { SessionProtocol.parseFollowLine(it) }
        val line = turn[0] as FollowEvent.Line
        assertEquals("u-1", line.uuid)
        assertEquals("user", RemoteJson.parseObject(line.json)!!.str("type"))
        assertNull(line.offset)
        assertEquals(SessionState.IDLE, (turn[2] as FollowEvent.State).session.state)
        assertEquals(4096L, (turn[3] as FollowEvent.CaughtUp).offset)
        val st = turn[6] as FollowEvent.Status
        assertEquals("Improvising…", st.verb)
        assertEquals(3L, st.elapsedS)
        assertEquals(120L, st.tokens)
        assertEquals("Hel", (turn[7] as FollowEvent.Draft).text)
        assertEquals(FollowEvent.DraftClear, turn[12])
        assertEquals(FollowEvent.Status(null, null, null), turn[13])

        val extras = lines("session/follow_extras.jsonl").map { SessionProtocol.parseFollowLine(it) }
        val peer = (extras[1] as FollowEvent.Peer).message
        assertEquals(PeerDirection.IN, peer.dir)
        assertEquals("uds:/run/user/1001/cc-socks/99.sock", peer.peer)
        assertEquals("builder", peer.peerName)
        assertEquals("99999999-1111-4222-8333-444444444444", peer.peerSessionId)
        assertEquals("tests are green", peer.text)
        assertEquals(1790875200000L, peer.at)
        val sub = (extras[5] as FollowEvent.Subagent).info
        assertEquals("a1b2c3", sub.agentId)
        assertEquals("Explore", sub.agentType)
        assertEquals("Find the config loader", sub.description)
        assertEquals("toolu_AG", sub.toolUseId)
        assertEquals("haiku", sub.model)
        assertFalse(sub.background)
        assertEquals(SubagentStatus.RUNNING, sub.status)
        val task = (extras[8] as FollowEvent.Task).task
        assertEquals("b7x1", task.taskId)
        assertEquals("toolu_BG", task.toolUseId)
        assertEquals(SessionTaskKind.SHELL, task.kind)
        assertEquals(SessionTaskStatus.RUNNING, task.status)
        assertEquals("npm run dev", task.summary)
        assertEquals("/tmp/claude-1001/p/s/tasks/b7x1.output", task.outputFile)
        assertEquals(SessionTaskKind.OTHER, (extras[11] as FollowEvent.Task).task.kind) // unknown kind
        val todos = extras[12] as FollowEvent.Todos
        assertEquals("L1", todos.listId)
        assertEquals(3, todos.items.size)
        assertEquals("in_progress", todos.items[1].status)
        assertEquals("Fix bug", todos.items[1].subject)
        assertNull(extras[15]) // an event this app does not know yet
    }

    @Test
    fun lineEventKeepsAnOptionalOffset() {
        val e = SessionProtocol.parseFollowLine("""{"e":"line","offset":123,"line":{"type":"user","uuid":"x"}}""") as FollowEvent.Line
        assertEquals(123L, e.offset)
        assertNull(SessionProtocol.parseFollowLine("""{"e":"line","line":"not an object"}"""))
    }

    // ───────────── request bodies and argv ─────────────

    private fun obj(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject

    @Test
    fun requestBodiesMatchTheContract() {
        assertEquals(
            obj("""{"cwd":"/p","prompt":"hi","model":"haiku","permissionMode":"plan","images":["/h/.tether/uploads/a.png"]}"""),
            obj(SessionProtocol.newBody(NewSessionRequest("/p", "hi", "haiku", "plan", listOf("/h/.tether/uploads/a.png")))),
        )
        assertEquals(obj("""{"cwd":"/p","prompt":"hi"}"""), obj(SessionProtocol.newBody(NewSessionRequest("/p", "hi"))))
        assertEquals(obj("""{"cwd":"/p","prompt":"hi","trust":true}"""), obj(SessionProtocol.newBody(NewSessionRequest("/p", "hi", trust = true))))
        assertEquals(obj("""{"text":"go"}"""), obj(SessionProtocol.sendBody("go", emptyList())))
        assertEquals(obj("""{"text":"see","images":["/a.png","/b.jpg"]}"""), obj(SessionProtocol.sendBody("see", listOf("/a.png", "/b.jpg"))))
        assertEquals(
            obj("""{"keys":["shift-tab","esc","enter","up","down","left","right","tab","space","3",{"text":"/model opus"}]}"""),
            obj(SessionProtocol.keyBody(listOf(
                SessionKey.ShiftTab, SessionKey.Esc, SessionKey.Enter, SessionKey.Up, SessionKey.Down,
                SessionKey.Left, SessionKey.Right, SessionKey.Tab, SessionKey.Space, SessionKey.digit(3),
                SessionKey.Text("/model opus"),
            ))),
        )
        assertEquals(obj("""{"decision":"allow"}"""), obj(SessionProtocol.answerBody(SessionDecision.ALLOW, null)))
        assertEquals(obj("""{"decision":"allow_always"}"""), obj(SessionProtocol.answerBody(SessionDecision.ALLOW_ALWAYS, "  ")))
        assertEquals(obj("""{"decision":"deny","message":"use yarn"}"""), obj(SessionProtocol.answerBody(SessionDecision.DENY, " use yarn ")))
        assertEquals(obj("""{"decision":"allow","toolUseId":"toolu_1"}"""), obj(SessionProtocol.answerBody(SessionDecision.ALLOW, null, "toolu_1")))
        assertEquals(obj("""{"mode":"plan"}"""), obj(SessionProtocol.modeBody("plan")))
        assertEquals(obj("""{"mode":""}"""), obj(SessionProtocol.modeBody(null)))
        assertEquals(
            obj("""{"answers":[{"choices":[0,2],"other":null},{"choices":[],"other":"Redis"}]}"""),
            obj(SessionProtocol.askBody(listOf(AskAnswer(listOf(0, 2)), AskAnswer(emptyList(), " Redis ")))),
        )
    }

    @Test
    fun keyNamesAreTheContractSet() {
        assertEquals(
            setOf("shift-tab", "esc", "enter", "up", "down", "left", "right", "tab", "space", "1", "2", "3", "4", "5", "6", "7", "8", "9"),
            SessionKey.NAMES,
        )
    }

    @Test
    fun argvForSessionsAndFollow() {
        assertEquals(listOf("sessions"), SessionProtocol.sessionsArgs(null, null, null))
        assertEquals(listOf("sessions", "--cwd", "/p", "--limit", "60", "--before", "99"), SessionProtocol.sessionsArgs("/p", 60, 99))
        assertEquals(listOf("follow", "sid"), SessionProtocol.followArgs("sid", null, 0))
        assertEquals(listOf("follow", "sid", "--agent", "a1", "--from", "4096"), SessionProtocol.followArgs("sid", "a1", 4096))
    }
}
