package app.tether.remote

import app.tether.core.AskAnswer
import app.tether.core.AuthMethod
import app.tether.core.ChatItem
import app.tether.core.Connection
import app.tether.core.ConnectionRepository
import app.tether.core.ConversationState
import app.tether.core.DaemonStatus
import app.tether.core.ExecResult
import app.tether.core.FollowEvent
import app.tether.core.Holder
import app.tether.core.ImageAttachment
import app.tether.core.LinkState
import app.tether.core.NewSessionRequest
import app.tether.core.NewSessionResult
import app.tether.core.ProbeResult
import app.tether.core.RunStatus
import app.tether.core.Session
import app.tether.core.SessionDecision
import app.tether.core.SessionErrorCodes
import app.tether.core.SessionEvent
import app.tether.core.SessionKey
import app.tether.core.SessionPending
import app.tether.core.SessionRef
import app.tether.core.SessionRemote
import app.tether.core.SessionState
import app.tether.core.SshManager
import app.tether.core.TestProgress
import app.tether.core.WatchMessage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.util.Collections
import java.util.concurrent.ConcurrentLinkedQueue

/** [DefaultSessionHub] against a fake [SessionRemote]: watch → list + events, open → follow reducer, writes. */
class DefaultSessionHubTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val remote = FakeRemote()
    private val hub = DefaultSessionHub(remote, FakeSsh(), Conns(), scope, frameMs = 0)

    @After
    fun tearDown() = scope.cancel()

    private fun fixture(name: String): String =
        (javaClass.classLoader!!.getResource("fixtures/$name") ?: error("missing fixture $name")).readText()

    private fun events(name: String): List<FollowEvent> =
        fixture(name).lines().filter { it.isNotBlank() }.mapNotNull { SessionProtocol.parseFollowLine(it) }

    private fun s(id: String, state: SessionState, updatedAt: Long, pending: SessionPending? = null, heldBy: Holder = Holder.DAEMON, lastText: String? = null) =
        Session(sessionId = id, cwd = "/p", name = "n-$id", state = state, pending = pending, heldBy = heldBy, updatedAt = updatedAt, lastText = lastText)

    private suspend fun <T> StateFlow<T>.waitFor(timeoutMs: Long = 5_000, predicate: (T) -> Boolean): T =
        withTimeout(timeoutMs) { first(predicate) }

    private suspend fun waitUntil(timeoutMs: Long = 5_000, cond: () -> Boolean) {
        withTimeout(timeoutMs) { while (!cond()) delay(10) }
    }

    // ───────────── watch → sessions + events ─────────────

    @Test
    fun watchBuildsTheListAndEmitsTransitions() = runBlocking {
        val seen = Collections.synchronizedList(ArrayList<SessionEvent>())
        val evJob = launch(Dispatchers.Default) { hub.events.collect { seen.add(it) } }
        val listJob = launch(Dispatchers.Default) { hub.sessions.collect { } } // an observer starts the watch
        waitUntil { remote.watchCalls > 0 && remote.watch.subscriptionCount.value > 0 }

        val a = "aaaaaaaa-0000-0000-0000-000000000001"
        val t = "tttttttt-0000-0000-0000-000000000002"
        remote.watch.emit(WatchMessage.Snapshot(listOf(s(a, SessionState.WORKING, 10), s(t, SessionState.WORKING, 20, heldBy = Holder.TERMINAL))))
        val list = hub.sessions.waitFor { it.size == 2 }
        assertEquals(listOf(t, a), list.map { it.sessionId }) // newest first
        assertTrue(list.all { it.connectionId == CONN })
        assertEquals(SessionRef(CONN, a), list[1].ref)

        val perm = SessionPending.Permission("toolu_1", "Bash", "ls")
        remote.watch.emit(WatchMessage.Changed(listOf(s(a, SessionState.NEEDS_YOU, 30, perm)), emptyList()))
        remote.watch.emit(WatchMessage.Changed(listOf(s(a, SessionState.NEEDS_YOU, 31, perm)), emptyList())) // same prompt
        remote.watch.emit(WatchMessage.Heartbeat(1))
        remote.watch.emit(WatchMessage.Changed(listOf(s(a, SessionState.WORKING, 40)), listOf(t)))
        hub.sessions.waitFor { it.size == 1 }
        remote.watch.emit(WatchMessage.Changed(listOf(s(a, SessionState.IDLE, 50, lastText = "done!")), emptyList()))
        remote.watch.emit(WatchMessage.Changed(listOf(s(a, SessionState.NEEDS_YOU, 60, perm)), emptyList())) // re-armed after leaving
        remote.watch.emit(WatchMessage.Changed(listOf(s(a, SessionState.FAILED, 70)), emptyList()))
        waitUntil { seen.size >= 4 }
        delay(100)
        assertEquals(4, seen.size)
        val needs = seen[0] as SessionEvent.NeedsYou
        assertEquals(SessionRef(CONN, a), needs.ref)
        assertEquals("n-$a", needs.title)
        assertEquals(perm, needs.pending)
        assertEquals("done!", (seen[1] as SessionEvent.TurnDone).snippet)
        assertTrue(seen[2] is SessionEvent.NeedsYou)
        assertTrue(seen[3] is SessionEvent.Failed)
        assertEquals(SessionState.FAILED, hub.session(SessionRef(CONN, a))!!.state)
        evJob.cancel()
        listJob.cancel()
    }

    @Test
    fun terminalHeldSessionsNeverNotify() = runBlocking {
        val seen = Collections.synchronizedList(ArrayList<SessionEvent>())
        val evJob = launch(Dispatchers.Default) { hub.events.collect { seen.add(it) } }
        val listJob = launch(Dispatchers.Default) { hub.sessions.collect { } }
        waitUntil { remote.watch.subscriptionCount.value > 0 }
        val t = "tttttttt-0000-0000-0000-000000000002"
        remote.watch.emit(WatchMessage.Snapshot(listOf(s(t, SessionState.WORKING, 1, heldBy = Holder.TERMINAL))))
        remote.watch.emit(WatchMessage.Changed(listOf(s(t, SessionState.NEEDS_YOU, 2, SessionPending.Permission("x"), heldBy = Holder.TERMINAL)), emptyList()))
        remote.watch.emit(WatchMessage.Changed(listOf(s(t, SessionState.IDLE, 3, heldBy = Holder.TERMINAL)), emptyList()))
        hub.sessions.waitFor { it.firstOrNull()?.state == SessionState.IDLE }
        delay(100)
        assertTrue(seen.isEmpty())
        evJob.cancel()
        listJob.cancel()
    }

    @Test
    fun watchErrorIsReportedPerMachine() = runBlocking {
        remote.watchError = RemoteException("The Claude Code daemon is not running.", code = SessionErrorCodes.ENODAEMON)
        val listJob = launch(Dispatchers.Default) { hub.sessions.collect { } }
        val errors = hub.machineErrors.waitFor { it.isNotEmpty() }
        assertEquals("The Claude Code daemon is not running.", errors[CONN])
        listJob.cancel()
    }

    @Test
    fun refreshAndHistory() = runBlocking {
        val a = "aaaaaaaa-0000-0000-0000-000000000001"
        remote.sessionList = listOf(s(a, SessionState.IDLE, 5))
        hub.refresh(CONN)
        assertEquals(listOf(a), hub.sessions.value.map { it.sessionId })
        assertEquals(CONN, hub.sessions.value.single().connectionId)
        val older = hub.history(CONN, cwd = "/p", limit = 10, before = 5)
        assertEquals(Triple("/p", 10, 5L), remote.lastSessionsArgs)
        assertEquals(CONN, older.single().connectionId)
    }

    // ───────────── open → follow reducer ─────────────

    @Test
    fun openStreamsDraftThenFinalAndResumesFromTheOffsetAfterADrop() = runBlocking {
        val evs = events("session/follow_turn.jsonl")
        val ref = SessionRef(CONN, SID)
        val gate = CompletableDeferred<Unit>()
        // first stream: history, caughtUp at 4096, the live prompt + a draft; then the link drops
        remote.followScripts.add {
            evs.take(9).forEach { emit(it) }
            gate.await()
            throw IOException("link dropped")
        }
        // second stream: the helper resends from the offset (the prompt again), then the rest of the turn
        remote.followScripts.add {
            evs.subList(5, evs.size).forEach { emit(it) }
            awaitCancellation()
        }
        val flow = hub.open(ref)
        val job = launch(Dispatchers.Default) { flow.collect { } }

        var st = flow.waitFor { it.live?.draft == "Hello wor" }
        assertEquals(RunStatus.WORKING, st.status)
        assertTrue((st.items.last() as ChatItem.AssistantText).streaming)
        assertFalse(st.loadingHistory)

        gate.complete(Unit)
        st = flow.waitFor(timeoutMs = 10_000) { s -> s.live?.draft == null && s.items.count { it is ChatItem.AssistantText } == 2 && s.status == RunStatus.IDLE }
        assertEquals(listOf(Triple(SID, null, 0L), Triple(SID, null, 4096L)), remote.followCalls.toList())
        assertEquals(2, st.items.count { it is ChatItem.User }) // the replayed prompt is not doubled
        assertNull(st.error) // a single blip is not an error
        assertEquals("Hello **world**!", (st.items.last() as ChatItem.AssistantText).text)
        job.cancel()
    }

    @Test
    fun watchListFeedsTheOpenConversation() = runBlocking {
        val ref = SessionRef(CONN, SID)
        remote.followScripts.add { emit(FollowEvent.CaughtUp(10)); awaitCancellation() }
        val flow = hub.open(ref)
        val job = launch(Dispatchers.Default) { flow.collect { } }
        flow.waitFor { !it.loadingHistory }
        // an open conversation alone keeps the machine watched
        waitUntil { remote.watch.subscriptionCount.value > 0 }
        val perm = SessionPending.Permission("toolu_9", "Edit", "src/A.kt", "{}")
        remote.watch.emit(WatchMessage.Snapshot(listOf(s(SID, SessionState.NEEDS_YOU, 5, perm))))
        val st = flow.waitFor { it.status == RunStatus.AWAITING_PERMISSION }
        assertEquals("toolu_9", st.pendingPermissions.single().requestId)
        assertEquals(perm, st.live!!.pending)
        assertEquals("n-$SID", st.title)
        job.cancel()
    }

    @Test
    fun subagentViewFollowsWithAgentId() = runBlocking {
        val ref = SessionRef(CONN, SID)
        remote.followScripts.add { emit(FollowEvent.CaughtUp(0)); awaitCancellation() }
        val flow = hub.open(ref, agentId = "a1b2c3")
        val job = launch(Dispatchers.Default) { flow.collect { } }
        val st = flow.waitFor { !it.loadingHistory }
        assertEquals("a1b2c3", st.live!!.agentId)
        assertEquals(Triple(SID, "a1b2c3", 0L), remote.followCalls.first())
        job.cancel()
    }

    @Test
    fun repeatedFailuresSurfaceAnError() = runBlocking {
        val ref = SessionRef(CONN, SID)
        repeat(3) { remote.followScripts.add { throw RemoteException("no such session", code = SessionErrorCodes.ENOSESSION) } }
        remote.followScripts.add { awaitCancellation() }
        val flow = hub.open(ref)
        val job = launch(Dispatchers.Default) { flow.collect { } }
        val st = flow.waitFor { it.error != null }
        assertEquals("no such session", st.error)
        job.cancel()
    }

    // ───────────── writes ─────────────

    @Test
    fun sendShowsTheMessageAtOnceAndDropsItOnFailure() = runBlocking {
        val ref = SessionRef(CONN, SID)
        remote.followScripts.add { emit(FollowEvent.CaughtUp(0)); awaitCancellation() }
        val flow = hub.open(ref)
        val job = launch(Dispatchers.Default) { flow.collect { } }
        flow.waitFor { !it.loadingHistory }

        val release = CompletableDeferred<Unit>()
        remote.sendGate = release
        val sending = async(Dispatchers.Default) { hub.send(ref, "hello there", listOf(ImageAttachment(byteArrayOf(1), "image/png", "a.png"))) }
        val st = flow.waitFor { s -> s.items.any { it is ChatItem.User && it.text == "hello there" } }
        assertEquals(1, (st.items.last() as ChatItem.User).imageCount)
        release.complete(Unit)
        assertTrue(sending.await())
        assertEquals(Triple(SID, "hello there", listOf("/home/u/.tether/uploads/0.png")), remote.lastSend)

        remote.sendGate = null
        remote.sendError = RemoteException("Open in a terminal on the machine", code = SessionErrorCodes.EHELD)
        try {
            hub.send(ref, "second")
            fail("expected EHELD")
        } catch (e: RemoteException) {
            assertEquals(SessionErrorCodes.EHELD, e.code)
        }
        flow.waitFor { s -> s.items.none { it is ChatItem.User && it.text == "second" } }
        job.cancel()
    }

    @Test
    fun newSessionStartedOrUntrusted() = runBlocking {
        val req = NewSessionRequest(cwd = "/p", prompt = "go", model = "haiku")
        val r = hub.new(CONN, req, listOf(ImageAttachment(byteArrayOf(1), "image/jpeg", "x.jpg")))
        val started = r as NewSessionResult.Started
        assertEquals(CONN, started.session.connectionId)
        assertEquals(listOf("/home/u/.tether/uploads/0.jpg"), remote.lastNew!!.images)
        assertEquals("haiku", remote.lastNew!!.model)
        assertTrue(hub.sessions.value.any { it.sessionId == started.session.sessionId })

        remote.newError = RemoteException("Claude Code does not trust /q yet.", code = SessionErrorCodes.EUNTRUSTED)
        assertEquals(NewSessionResult.Untrusted("/q"), hub.new(CONN, NewSessionRequest("/q", "go")))
        remote.newError = RemoteException("daemon down", code = SessionErrorCodes.ENODAEMON)
        try {
            hub.new(CONN, NewSessionRequest("/q", "go"))
            fail("expected ENODAEMON")
        } catch (e: RemoteException) {
            assertEquals(SessionErrorCodes.ENODAEMON, e.code)
        }
    }

    @Test
    fun answerAskInterruptMergeTheReturnedSession() = runBlocking {
        val ref = SessionRef(CONN, SID)
        remote.sessionList = listOf(s(SID, SessionState.NEEDS_YOU, 1, SessionPending.Permission("t")))
        hub.refresh(CONN)
        remote.writeResult = s(SID, SessionState.WORKING, 2)
        hub.answer(ref, SessionDecision.DENY, "no")
        assertEquals(Triple(SID, SessionDecision.DENY, "no"), remote.lastAnswer)
        assertEquals(SessionState.WORKING, hub.session(ref)!!.state)
        assertEquals(CONN, hub.session(ref)!!.connectionId)

        remote.writeResult = s(SID, SessionState.NEEDS_YOU, 3)
        hub.ask(ref, listOf(AskAnswer(listOf(1))))
        assertEquals(listOf(AskAnswer(listOf(1))), remote.lastAsk)
        assertEquals(SessionState.NEEDS_YOU, hub.session(ref)!!.state)

        remote.writeResult = s(SID, SessionState.IDLE, 4)
        hub.interrupt(ref)
        assertEquals(SessionState.IDLE, hub.session(ref)!!.state)

        // an older reply never overwrites a newer description
        remote.writeResult = s(SID, SessionState.WORKING, 1)
        hub.interrupt(ref)
        assertEquals(SessionState.IDLE, hub.session(ref)!!.state)

        hub.key(ref, listOf(SessionKey.ShiftTab, SessionKey.Text("/model opus")))
        assertEquals(listOf(SessionKey.ShiftTab, SessionKey.Text("/model opus")), remote.lastKeys)

        hub.stop(ref)
        assertEquals(SID, remote.stopped)
        hub.remove(ref)
        assertEquals(SID, remote.removed)
        assertNull(hub.session(ref))
    }

    // ───────────── fakes ─────────────

    private class FakeRemote : SessionRemote {
        val watch = MutableSharedFlow<WatchMessage>(replay = 0, extraBufferCapacity = 64)
        @Volatile var watchCalls = 0
        @Volatile var watchError: Throwable? = null
        var sessionList: List<Session> = emptyList()
        var lastSessionsArgs: Triple<String?, Int?, Long?>? = null
        val followScripts = ConcurrentLinkedQueue<suspend FlowCollector<FollowEvent>.() -> Unit>()
        val followCalls = ConcurrentLinkedQueue<Triple<String, String?, Long>>()
        @Volatile var sendGate: CompletableDeferred<Unit>? = null
        @Volatile var sendError: Throwable? = null
        var lastSend: Triple<String, String, List<String>>? = null
        var lastNew: NewSessionRequest? = null
        var newError: Throwable? = null
        var writeResult: Session? = null
        var lastAnswer: Triple<String, SessionDecision, String?>? = null
        var lastAsk: List<AskAnswer>? = null
        var lastKeys: List<SessionKey>? = null
        var stopped: String? = null
        var removed: String? = null
        private var uploads = 0

        override suspend fun daemonStatus(connectionId: String) = DaemonStatus(running = true, proto = 1)
        override suspend fun sessions(connectionId: String, cwd: String?, limit: Int?, before: Long?): List<Session> {
            lastSessionsArgs = Triple(cwd, limit, before)
            return sessionList
        }
        override fun watchSessions(connectionId: String): Flow<WatchMessage> = flow {
            watchCalls++
            watchError?.let { throw it }
            watch.collect { emit(it) }
        }
        override fun follow(connectionId: String, sessionId: String, agentId: String?, fromOffset: Long): Flow<FollowEvent> = flow {
            followCalls.add(Triple(sessionId, agentId, fromOffset))
            val script = followScripts.poll() ?: { awaitCancellation() }
            script()
        }
        override suspend fun newSession(connectionId: String, request: NewSessionRequest): Session {
            newError?.let { throw it }
            lastNew = request
            return Session(sessionId = "nnnnnnnn-0000-0000-0000-000000000009", cwd = request.cwd, state = SessionState.WORKING, updatedAt = 99)
        }
        override suspend fun send(connectionId: String, sessionId: String, text: String, images: List<String>): Boolean {
            sendGate?.await()
            sendError?.let { throw it }
            lastSend = Triple(sessionId, text, images)
            return true
        }
        override suspend fun key(connectionId: String, sessionId: String, keys: List<SessionKey>) { lastKeys = keys }
        override suspend fun answer(connectionId: String, sessionId: String, decision: SessionDecision, message: String?): Session {
            lastAnswer = Triple(sessionId, decision, message)
            return writeResult!!
        }
        override suspend fun ask(connectionId: String, sessionId: String, answers: List<AskAnswer>): Session {
            lastAsk = answers
            return writeResult!!
        }
        override suspend fun interrupt(connectionId: String, sessionId: String): Session = writeResult!!
        override suspend fun stop(connectionId: String, sessionId: String) { stopped = sessionId }
        override suspend fun rm(connectionId: String, sessionId: String) { removed = sessionId }
        override suspend fun uploadImage(connectionId: String, image: ImageAttachment): String {
            val ext = if (image.mimeType == "image/png") "png" else "jpg"
            return "/home/u/.tether/uploads/${uploads++}.$ext"
        }
    }

    private class FakeSsh : SshManager {
        override val states: StateFlow<Map<String, LinkState>> = MutableStateFlow(mapOf(CONN to LinkState.Connected(0L)))
        override val linkEpochs = MutableStateFlow<Map<String, Long>>(emptyMap())
        override suspend fun revalidate() {}
        override suspend fun exec(connectionId: String, command: String, stdin: ByteArray?, timeoutMs: Long) = ExecResult(0, "", "")
        override fun streamLines(connectionId: String, command: String): Flow<String> = emptyFlow()
        override suspend fun upload(connectionId: String, remotePath: String, bytes: ByteArray, mode: Int) = Unit
        override suspend fun download(connectionId: String, remotePath: String, dest: java.io.File, onProgress: (Long, Long) -> Unit) {}
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

    private companion object {
        const val CONN = "conn-1"
        const val SID = "7b9f8c4c-6ac7-4d5a-9111-003abd24390e"
    }
}
