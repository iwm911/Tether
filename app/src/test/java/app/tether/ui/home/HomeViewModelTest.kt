package app.tether.ui.home

import app.tether.core.AskAnswer
import app.tether.core.AuthMethod
import app.tether.core.Connection
import app.tether.core.ConversationState
import app.tether.core.ImageAttachment
import app.tether.core.LinkState
import app.tether.core.NewSessionRequest
import app.tether.core.NewSessionResult
import app.tether.core.ProjectSummary
import app.tether.core.Session
import app.tether.core.SessionDecision
import app.tether.core.SessionEvent
import app.tether.core.SessionHub
import app.tether.core.SessionKey
import app.tether.core.SessionPending
import app.tether.core.SessionRef
import app.tether.core.SessionState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Home's ViewModel against a fake [SessionHub]: one list, chips, paging, inline Allow / Deny. */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private class FakeHub(initial: List<Session>) : SessionHub {
        val list = MutableStateFlow(initial)
        val errors = MutableStateFlow<Map<String, String>>(emptyMap())
        val calls = mutableListOf<String>()
        var answerGate: CompletableDeferred<Unit>? = null
        var answerFails: Throwable? = null
        var historyPages: (String, String?, Long?) -> List<Session> = { _, _, _ -> emptyList() }

        override val sessions: StateFlow<List<Session>> = list
        override val machineErrors: StateFlow<Map<String, String>> = errors
        override val events: SharedFlow<SessionEvent> = MutableSharedFlow()
        override fun session(ref: SessionRef): Session? = list.value.firstOrNull { it.ref == ref }
        override fun open(ref: SessionRef, agentId: String?): StateFlow<ConversationState> = error("unused")
        override suspend fun refresh(connectionId: String?) { calls += "refresh $connectionId" }
        override suspend fun history(connectionId: String, cwd: String?, limit: Int, before: Long?): List<Session> {
            calls += "history $connectionId cwd=$cwd limit=$limit before=$before"
            return historyPages(connectionId, cwd, before)
        }
        override suspend fun new(connectionId: String, request: NewSessionRequest, images: List<ImageAttachment>): NewSessionResult = error("unused")
        override suspend fun send(ref: SessionRef, text: String, images: List<ImageAttachment>): Boolean = error("unused")
        override suspend fun key(ref: SessionRef, keys: List<SessionKey>) = error("unused")
        override suspend fun answer(ref: SessionRef, decision: SessionDecision, message: String?, toolUseId: String?) {
            calls += "answer ${ref.sessionId} ${decision.wire}"
            answerGate?.await()
            answerFails?.let { throw it }
        }
        override suspend fun ask(ref: SessionRef, answers: List<AskAnswer>) = error("unused")
        override suspend fun interrupt(ref: SessionRef) = error("unused")
        override suspend fun stop(ref: SessionRef) = error("unused")
        override suspend fun remove(ref: SessionRef) = error("unused")
        override fun setBackgroundWatch(enabled: Boolean) {}
        override fun setPaused(paused: Boolean) {}
        override suspend fun hold(connectionId: String) {}
        override fun release(connectionId: String) {}
    }

    private fun conn(id: String) = Connection(id = id, name = "box-$id", host = "h", username = "u", auth = AuthMethod.Password, createdAt = 0)

    private fun s(id: String, conn: String = "a", cwd: String = "/p/app", state: SessionState = SessionState.IDLE, updated: Long, pending: SessionPending? = null) =
        Session(sessionId = id, cwd = cwd, state = state, updatedAt = updated, pending = pending, connectionId = conn)

    private val perm = SessionPending.Permission(toolUseId = "tu1", toolName = "Bash", summary = "rm -rf build")

    private fun TestScope.vm(hub: FakeHub, machines: List<String> = listOf("a", "b"), projects: List<ProjectSummary> = emptyList()): HomeViewModel {
        val vm = HomeViewModel(
            hub = hub,
            connections = MutableStateFlow(machines.map(::conn)),
            links = MutableStateFlow<Map<String, LinkState>>(emptyMap()),
            loadProjects = { projects },
            loadTimers = false,
        )
        backgroundScope.launch { vm.state.collect {} }
        backgroundScope.launch { vm.events.collect { m -> if (m is HomeMessage.Error) errors += m.text } }
        return vm
    }

    private val errors = mutableListOf<String>()

    @Test
    fun oneListNeedsYouFirstThenRecentAcrossMachines() = runTest(dispatcher) {
        val hub = FakeHub(listOf(
            s("idle-a", updated = 10),
            s("work-b", conn = "b", state = SessionState.WORKING, updated = 50),
            s("ask-a", state = SessionState.NEEDS_YOU, updated = 1, pending = perm),
            s("ghost", conn = "deleted-machine", updated = 99),
        ))
        val vm = vm(hub)
        advanceUntilIdle()
        val st = vm.state.value
        assertEquals(listOf("ask-a", "work-b", "idle-a"), st.sessions.map { it.sessionId })
        assertEquals(3, st.totalSessions)
        assertEquals(1, st.needsYouCount)
        assertEquals(1, st.workingCount)
        assertEquals(mapOf("a" to 1, "b" to 1), st.liveCountByMachine)
        assertEquals(listOf("a", "b"), st.machineChips)
        assertFalse(st.showSkeleton)
        assertTrue("refresh null" in hub.calls)

        // Live updates re-sort: the idle one becomes needs-you.
        hub.list.value = hub.list.value.map { if (it.sessionId == "idle-a") it.copy(state = SessionState.NEEDS_YOU, updatedAt = 60) else it }
        advanceUntilIdle()
        assertEquals(listOf("idle-a", "ask-a", "work-b"), vm.state.value.sessions.map { it.sessionId })
    }

    @Test
    fun machineAndProjectChipsFilter() = runTest(dispatcher) {
        val hub = FakeHub(listOf(
            s("a1", cwd = "/p/one", updated = 3),
            s("a2", cwd = "/p/two", updated = 2),
            s("b1", conn = "b", cwd = "/p/one", updated = 1),
        ))
        val vm = vm(hub)
        advanceUntilIdle()
        assertEquals(listOf("/p/one", "/p/two"), vm.state.value.projectChips.map { it.cwd })

        vm.selectMachine("b")
        advanceUntilIdle()
        assertEquals(listOf("b1"), vm.state.value.sessions.map { it.sessionId })
        assertEquals(listOf("/p/one"), vm.state.value.projectChips.map { it.cwd })

        vm.selectMachine(null)
        vm.selectProject("/p/one/")
        advanceUntilIdle()
        assertEquals(listOf("a1", "b1"), vm.state.value.sessions.map { it.sessionId })
        assertEquals("/p/one", vm.state.value.filter.project)

        // Picking a machine clears the project (projects belong to a machine).
        vm.selectMachine("a")
        advanceUntilIdle()
        assertNull(vm.state.value.filter.project)
        assertEquals(listOf("a1", "a2"), vm.state.value.sessions.map { it.sessionId })
    }

    @Test
    fun singleMachineHasNoMachineChips() = runTest(dispatcher) {
        val vm = vm(FakeHub(listOf(s("a1", updated = 1), s("a2", updated = 2))), machines = listOf("a"))
        advanceUntilIdle()
        assertEquals(emptyList<String>(), vm.state.value.machineChips)
    }

    @Test
    fun inlineAllowShowsAtOnceAndAnswersTheSession() = runTest(dispatcher) {
        val ask = s("ask-a", state = SessionState.NEEDS_YOU, updated = 1, pending = perm)
        val hub = FakeHub(listOf(ask))
        val vm = vm(hub)
        advanceUntilIdle()
        vm.respond(ask, allow = true)
        runCurrent()
        val key = decisionKey(ask.ref, perm)
        assertEquals(true, vm.state.value.decisions[key])
        assertEquals(listOf("answer ask-a allow"), hub.calls.filter { it.startsWith("answer") })
        // A second tap does nothing.
        vm.respond(ask, allow = false)
        runCurrent()
        assertEquals(1, hub.calls.count { it.startsWith("answer") })
        advanceTimeBy(61_000)
        runCurrent()
        assertNull(vm.state.value.decisions[key])
        assertTrue(errors.isEmpty())
    }

    @Test
    fun failedDenyIsUndoneAndExplained() = runTest(dispatcher) {
        val ask = s("ask-a", state = SessionState.NEEDS_YOU, updated = 1, pending = perm)
        val hub = FakeHub(listOf(ask)).apply { answerFails = IllegalStateException("The session is no longer waiting.") }
        val vm = vm(hub)
        advanceUntilIdle()
        vm.respond(ask, allow = false)
        runCurrent()
        assertEquals(false, vm.state.value.decisions[decisionKey(ask.ref, perm)])
        advanceTimeBy(800)
        runCurrent()
        assertNull(vm.state.value.decisions[decisionKey(ask.ref, perm)])
        assertEquals(listOf("Couldn't send your answer — The session is no longer waiting."), errors)
    }

    @Test
    fun questionsAndDialogsAreNotAnsweredInline() = runTest(dispatcher) {
        val q = s("q", state = SessionState.NEEDS_YOU, updated = 1, pending = SessionPending.Question(toolUseId = "x"))
        val hub = FakeHub(listOf(q))
        val vm = vm(hub)
        advanceUntilIdle()
        vm.respond(q, allow = true)
        advanceUntilIdle()
        assertTrue(hub.calls.none { it.startsWith("answer") })
    }

    @Test
    fun showOlderPagesPerMachineFromTheOldestShown() = runTest(dispatcher) {
        val hub = FakeHub(listOf(s("a1", updated = 100), s("a2", updated = 80), s("b1", conn = "b", updated = 70)))
        hub.historyPages = { c, _, before ->
            when (c) {
                "a" -> if (before == 80L) (1..30).map { s("old-a$it", updated = 80L - it) } else listOf(s("older-a", updated = 1))
                else -> listOf(s("old-b", conn = "b", updated = 5))
            }
        }
        val vm = vm(hub)
        advanceUntilIdle()
        assertTrue(vm.state.value.canLoadOlder)

        vm.loadOlder()
        advanceUntilIdle()
        assertTrue("history a cwd=null limit=30 before=80" in hub.calls)
        assertTrue("history b cwd=null limit=30 before=70" in hub.calls)
        val st = vm.state.value
        assertEquals(3 + 30 + 1, st.sessions.size)
        assertEquals("old-b", st.sessions.last().sessionId)
        // b gave a short page (done); a gave a full one (more to come).
        assertTrue(st.canLoadOlder)

        vm.loadOlder()
        advanceUntilIdle()
        assertTrue("history a cwd=null limit=30 before=50" in hub.calls)
        assertEquals(1, hub.calls.count { it.startsWith("history b") })
        assertFalse(vm.state.value.canLoadOlder)
        assertEquals("older-a", vm.state.value.sessions.first { it.sessionId == "older-a" }.sessionId)

        // Pull-to-refresh starts over from what the watch carries.
        vm.refresh()
        advanceUntilIdle()
        assertEquals(3, vm.state.value.sessions.size)
        assertTrue(vm.state.value.canLoadOlder)
    }

    @Test
    fun showOlderWithAProjectChipPagesThatProject() = runTest(dispatcher) {
        val hub = FakeHub(listOf(s("a1", cwd = "/p/one", updated = 100), s("a2", cwd = "/p/two", updated = 90)))
        val vm = vm(hub, machines = listOf("a"))
        advanceUntilIdle()
        vm.selectProject("/p/one")
        vm.loadOlder()
        advanceUntilIdle()
        assertEquals(listOf("history a cwd=/p/one limit=30 before=100"), hub.calls.filter { it.startsWith("history") })
    }

    @Test
    fun noSessionsShowsTheQuickStart() = runTest(dispatcher) {
        val vm = vm(FakeHub(emptyList()), machines = listOf("a"), projects = listOf(ProjectSummary("/p/x", 1, 5), ProjectSummary("/gone", 1, 9, exists = false)))
        advanceUntilIdle()
        assertFalse(vm.state.value.hasSessions)
        assertFalse(vm.state.value.canLoadOlder)
        vm.ensureQuickStart(vm.state.value)
        advanceUntilIdle()
        assertEquals(Loadable.Ready(listOf(ProjectSummary("/p/x", 1, 5))), vm.state.value.quickStart?.projects)
    }

    @Test
    fun unreachableMachineIsReportedAndNotPaged() = runTest(dispatcher) {
        val hub = FakeHub(listOf(s("a1", updated = 5)))
        hub.errors.value = mapOf("b" to "Can't reach it", "zzz" to "not a machine")
        val vm = vm(hub)
        advanceUntilIdle()
        assertEquals(mapOf("b" to "Can't reach it"), vm.state.value.machineErrors)
        vm.loadOlder()
        advanceUntilIdle()
        assertEquals(listOf("history a cwd=null limit=30 before=5"), hub.calls.filter { it.startsWith("history") })
    }
}
