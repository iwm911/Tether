package app.tether.remote

import app.tether.core.AgentEvent
import app.tether.core.AppSettings
import app.tether.core.AuthMethod
import app.tether.core.ChatItem
import app.tether.core.ClaudeRemote
import app.tether.core.Connection
import app.tether.core.ConnectionRepository
import app.tether.core.DirListing
import app.tether.core.ExecResult
import app.tether.core.LinkState
import app.tether.core.ProbeResult
import app.tether.core.ProjectSummary
import app.tether.core.RunInfo
import app.tether.core.RunKind
import app.tether.core.RunRef
import app.tether.core.RunStatus
import app.tether.core.SessionSummary
import app.tether.core.SettingsRepository
import app.tether.core.SshManager
import app.tether.core.StartRunRequest
import app.tether.core.TailLine
import app.tether.core.TestProgress
import app.tether.core.isNative
import app.tether.core.nativeId
import app.tether.core.nativeRunRef
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeAgentsTest {

    private fun fixture(name: String): String {
        val url = javaClass.classLoader!!.getResource("fixtures/$name") ?: error("missing fixture $name")
        return url.readText()
    }

    // ───────────── parsing / mapping of the helper's native-list JSON ─────────────

    @Test
    fun parsesNativeListAndIgnoresUnknownFields() {
        val list = NativeAgents.parseList(fixture("native_list.json"))
        assertEquals(3, list.size)
        val w = list[0]
        assertEquals("a1b2c3d4", w.id)
        assertEquals("working", w.state)
        assertTrue(w.alive)
        assertEquals(2, w.fan.size)
        assertTrue(w.fan[0].running)
        assertEquals("Native agents", w.fan[0].group)
        assertFalse(w.fan[1].running)
        assertEquals(1790371100000L, w.fan[1].doneAt)
        assertEquals(1067517L, w.tokens)
        assertEquals(2, w.timeline.size)
        // missing optional keys fall back to defaults
        assertEquals(0, list[2].tasks)
        assertNull(list[2].pid)
    }

    @Test
    fun mapsWorkingAgentToRunInfo() {
        val r = NativeAgents.toRunInfo(NativeAgents.parseList(fixture("native_list.json"))[0])
        assertEquals("native-a1b2c3d4", r.runId)
        assertEquals(RunKind.NATIVE, r.kind)
        assertTrue(r.isNative)
        assertEquals(RunStatus.WORKING, r.status)
        assertEquals("Checking CLI agent/background/remote features", r.detail)
        assertEquals("demo agent", r.title)
        assertEquals(2, r.subagents.size)
        assertEquals("auto", r.permissionMode)
        // no result yet: last non-blank timeline text is the snippet
        assertEquals("Phase 2 is running", r.lastText)
        assertTrue(RunRef("c", r.runId).isNative)
        assertEquals("a1b2c3d4", RunRef("c", r.runId).nativeId)
    }

    @Test
    fun terminalSessionIsWatchOnly() {
        val json = """[{"id":"term-4242","sessionId":"647b67c2-0fb0-483a-b4d5-b7ba37aec8d5","cwd":"/tmp/t","kind":"interactive",
            "name":"Pong reply","state":"idle","status":"waiting","pid":4242,"alive":true,"startedAt":1,"updatedAt":2}]"""
        val d = NativeAgents.parseList(json).single()
        val r = NativeAgents.toRunInfo(d)
        assertTrue(r.terminal)
        assertTrue(r.isNative)
        assertEquals("native-term-4242", r.runId)
        assertEquals("Pong reply", r.title)
        // Its prompt is answered in the terminal: nothing to approve here, and it does not count as needing the phone.
        assertEquals(RunStatus.IDLE, r.status)
        assertFalse(r.nativeBlocked)
        assertNull(r.pending)
        val busy = NativeAgents.toRunInfo(d.copy(status = "busy", state = "working"))
        assertEquals(RunStatus.WORKING, busy.status)
    }

    @Test
    fun doneAliveIsIdleAndStoppedIsEnded() {
        val list = NativeAgents.parseList(fixture("native_list.json")).map(NativeAgents::toRunInfo)
        val done = list[1]
        assertEquals(RunStatus.IDLE, done.status)
        assertEquals("done", done.nativeState)
        assertEquals("hello", done.lastText)
        assertEquals("haiku", done.model)
        val stopped = list[2]
        assertEquals(RunStatus.ENDED, stopped.status)
        assertFalse(stopped.alive)
        // job state "blocked" only means "waiting for the next message", not an approval prompt
        assertFalse(stopped.nativeBlocked)
    }

    @Test
    fun waitingStatusIsAnApprovalWithThePendingTool() {
        val json = """{"id":"ab12cd34","sessionId":"ab12cd34-0000-0000-0000-000000000000","cwd":"/w","state":"working",
            "status":"waiting","pid":42,"alive":true,"startedAt":1,"updatedAt":2,
            "pendingTool":{"toolUseId":"toolu_1","toolName":"Bash","summary":"touch x","inputJson":"{\"command\":\"touch x\"}"}}"""
        val r = NativeAgents.toRunInfo(NativeAgents.parseOne(json))
        assertEquals(RunStatus.AWAITING_PERMISSION, r.status)
        assertTrue(r.nativeBlocked)
        assertEquals("native:toolu_1", r.pending?.requestId)
        assertEquals("touch x", r.pending?.summary)
        val idleBlocked = NativeAgents.toRunInfo(NativeAgents.parseOne(json.replace("\"waiting\"", "\"idle\"").replace("\"working\"", "\"blocked\"")))
        assertEquals(RunStatus.IDLE, idleBlocked.status)
        assertEquals(null, idleBlocked.pending)
        val busy = NativeAgents.toRunInfo(NativeAgents.parseOne(json.replace("\"waiting\"", "\"busy\"")))
        assertEquals(RunStatus.WORKING, busy.status)
    }

    @Test
    fun parsesWatchLineAndRejectsOtherLines() {
        val arr = fixture("native_list.json").trim()
        val parsed = NativeAgents.parseWatchLine("{\"native\":$arr}")
        assertNotNull(parsed)
        assertEquals(3, parsed!!.size)
        assertNull(NativeAgents.parseWatchLine("{\"hb\":123}"))
        assertNull(NativeAgents.parseWatchLine("[]"))
        assertEquals(0, NativeAgents.parseWatchLine("{\"native\":[]}")!!.size)
        assertTrue(NativeAgents.isCaughtUpMarker("{\"tether\":\"caught-up\"}"))
        assertTrue(NativeAgents.isHeartbeat("{\"hb\":1790371955122}"))
    }

    @Test
    fun parsesReplyWithForkFlag() {
        val d = NativeAgents.parseOne(
            """{"id":"e348fa09","sessionId":"e348fa09-c0ed","cwd":"/x","state":"working","alive":true,"pid":5,""" +
                """"startedAt":1,"updatedAt":2,"previousId":"4653077f","forked":true}""",
        )
        assertEquals("4653077f", d.previousId)
        assertTrue(d.forked)
        assertEquals(RunStatus.WORKING, NativeAgents.status(d))
    }

    @Test
    fun parsesTimeline() {
        val t = NativeAgents.parseTimeline("""[{"at":10,"state":"working","detail":"a","text":""},{"at":20,"state":"done","detail":"b","text":"hi"}]""")
        assertEquals(2, t.size)
        assertEquals("done", t[1].state)
        assertEquals("hi", t[1].text)
    }

    // ───────────── hub: native events + conversation ─────────────

    private class FakeSsh : SshManager {
        override suspend fun download(connectionId: String, remotePath: String, dest: java.io.File, onProgress: (Long, Long) -> Unit) {}

        override val linkEpochs = kotlinx.coroutines.flow.MutableStateFlow<Map<String, Long>>(emptyMap())
        override suspend fun revalidate() {}

        override val states: StateFlow<Map<String, LinkState>> = MutableStateFlow(mapOf(CONN to LinkState.Connected(0L)))
        override suspend fun exec(connectionId: String, command: String, stdin: ByteArray?, timeoutMs: Long) = ExecResult(0, "", "")
        override fun streamLines(connectionId: String, command: String): Flow<String> = emptyFlow()
        override suspend fun upload(connectionId: String, remotePath: String, bytes: ByteArray, mode: Int) = Unit
        override suspend fun test(connection: Connection, password: String?, onProgress: (TestProgress) -> Unit) =
            Result.failure<ProbeResult>(UnsupportedOperationException())
        override suspend fun installPublicKey(connection: Connection, password: String?, publicKey: String) =
            Result.failure<Unit>(UnsupportedOperationException())
        override suspend fun disconnect(connectionId: String) = Unit
        override suspend fun disconnectAll() = Unit
    }

    private class Conns : ConnectionRepository {
        private val c = Connection(id = CONN, name = "Workstation", host = "h", username = "u", auth = AuthMethod.Password, createdAt = 0L)
        override val connections: StateFlow<List<Connection>> = MutableStateFlow(listOf(c))
        override fun get(id: String): Connection? = if (id == CONN) c else null
        override suspend fun upsert(connection: Connection, password: String?) = Unit
        override suspend fun delete(id: String) = Unit
        override suspend fun markConnected(id: String, hostname: String?, claudeVersion: String?) = Unit
        override fun newId(): String = "x"
    }

    private class Settings : SettingsRepository {
        override val settings: StateFlow<AppSettings> = MutableStateFlow(AppSettings())
        override suspend fun update(transform: (AppSettings) -> AppSettings) = Unit
    }

    private class FakeRemote(val snapshots: MutableSharedFlow<List<RunInfo>>, val transcript: List<String>) : ClaudeRemote {
        var current: List<RunInfo> = emptyList()
        override suspend fun probe(connectionId: String): ProbeResult = error("unused")
        override suspend fun listProjects(connectionId: String): List<ProjectSummary> = emptyList()
        override suspend fun listSessions(connectionId: String, cwd: String?, limit: Int): List<SessionSummary> = emptyList()
        override suspend fun loadTranscript(connectionId: String, sessionId: String): List<String> = emptyList()
        override suspend fun listRuns(connectionId: String): List<RunInfo> = current
        override suspend fun startRun(connectionId: String, request: StartRunRequest): RunInfo = error("unused")
        override suspend fun writeInput(ref: RunRef, jsonLines: List<String>) = Unit
        override suspend fun readInput(ref: RunRef): List<String> = emptyList()
        override fun tail(ref: RunRef, fromOffset: Long): Flow<TailLine> = emptyFlow()
        override fun watch(connectionId: String): Flow<List<RunInfo>> = snapshots
        override suspend fun stopRun(ref: RunRef) = Unit
        override suspend fun deleteRun(ref: RunRef) = Unit
        override suspend fun listDir(connectionId: String, path: String?): DirListing = error("unused")
        override fun followNative(ref: RunRef): Flow<String> = flow {
            transcript.forEach { emit(it) }
            emit("{\"tether\":\"caught-up\"}")
            kotlinx.coroutines.awaitCancellation()
        }
    }

    private fun nativeRun(state: String, detail: String) = NativeAgents.toRunInfo(
        NativeAgentDto(id = "4653077f", sessionId = "4653077f-a6b4", cwd = "/home/me/native", name = "hello test",
            state = state, alive = true, pid = 1, startedAt = 100, updatedAt = 200, detail = detail,
            result = if (state == "done") "hello" else null),
    )

    @Test
    fun hubEmitsTurnCompletedWhenNativeAgentFinishes() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val snaps = MutableSharedFlow<List<RunInfo>>(replay = 1)
            val remote = FakeRemote(snaps, emptyList())
            val hub = DefaultAgentHub(remote, FakeSsh(), Conns(), Settings(), scope)
            val events = mutableListOf<AgentEvent>()
            scope.launch { hub.events.collect { events.add(it) } }
            scope.launch { hub.agents.collect { } } // an observer keeps the machine watch open
            snaps.emit(listOf(nativeRun("working", "Saying hello")))
            withTimeout(5_000) { hub.agents.first { a -> a.any { it.run.detail == "Saying hello" } } }
            snaps.emit(listOf(nativeRun("done", "replied with hello")))
            withTimeout(5_000) { while (events.none { it is AgentEvent.TurnCompleted }) kotlinx.coroutines.delay(20) }
            val e = events.filterIsInstance<AgentEvent.TurnCompleted>().single()
            assertEquals(nativeRunRef(CONN, "4653077f"), e.ref)
            assertEquals("hello", e.snippet)
            val a = hub.agents.value.single()
            assertEquals(RunStatus.IDLE, a.run.status)
            assertEquals(RunKind.NATIVE, a.run.kind)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun nativeConversationRendersTranscriptWithLiveState() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val transcript = listOf(
                """{"type":"user","uuid":"u1","timestamp":"2026-09-26T00:32:33.000Z","message":{"role":"user","content":"Reply with just the word hello."}}""",
                """{"type":"assistant","uuid":"a1","timestamp":"2026-09-26T00:32:35.000Z","message":{"id":"m1","role":"assistant","model":"claude-haiku-4-5","content":[{"type":"text","text":"hello"}],"usage":{"input_tokens":10,"output_tokens":1}}}""",
                """{"type":"ai-title","aiTitle":"Reply with hello"}""",
            )
            val snaps = MutableSharedFlow<List<RunInfo>>(replay = 1)
            val remote = FakeRemote(snaps, transcript)
            val hub = DefaultAgentHub(remote, FakeSsh(), Conns(), Settings(), scope)
            scope.launch { hub.agents.collect { } }
            snaps.emit(listOf(nativeRun("done", "replied with hello")))
            val ref = nativeRunRef(CONN, "4653077f")
            val s = withTimeout(8_000) { hub.conversation(ref).first { !it.loadingHistory && it.nativeRun != null && it.items.size >= 2 } }
            assertEquals(RunKind.NATIVE, s.kind)
            assertEquals(RunStatus.IDLE, s.status)
            assertEquals("replied with hello", s.nativeRun?.detail)
            assertEquals("Reply with just the word hello.", (s.items[0] as ChatItem.User).text)
            assertEquals("hello", (s.items[1] as ChatItem.AssistantText).text)
            assertTrue(s.pendingPermissions.isEmpty())
            assertEquals("hello test", s.title) // the agent's own name wins over the transcript title
        } finally {
            scope.cancel()
        }
    }

    private companion object {
        const val CONN = "m1"
    }
}
