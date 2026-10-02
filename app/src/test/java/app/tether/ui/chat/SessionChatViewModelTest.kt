package app.tether.ui.chat

import app.tether.core.AppSettings
import app.tether.core.AskAnswer
import app.tether.core.ChatItem
import app.tether.core.ConversationState
import app.tether.core.DialogKind
import app.tether.core.FollowEvent
import app.tether.core.ImageAttachment
import app.tether.core.NewSessionRequest
import app.tether.core.NewSessionResult
import app.tether.core.PermissionDecision
import app.tether.core.PermissionMode
import app.tether.core.Session
import app.tether.core.SessionDecision
import app.tether.core.SessionErrorCodes
import app.tether.core.SessionEvent
import app.tether.core.SessionHub
import app.tether.core.SessionKey
import app.tether.core.SessionPending
import app.tether.core.SessionProcess
import app.tether.core.SessionRef
import app.tether.core.SessionState
import app.tether.core.SlashCommand
import app.tether.remote.RemoteException
import app.tether.remote.SessionProtocol
import app.tether.remote.SessionReducer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The session screen's ViewModel against a fake [SessionHub]: every write goes to the right call. */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionChatViewModelTest {

    private val sid = "7b9f8c4c-6ac7-4d5a-9111-003abd24390e"
    private val ref = SessionRef("conn", sid)
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private class FakeHub(initial: ConversationState) : SessionHub {
        val conv = MutableStateFlow(initial)
        val calls = mutableListOf<String>()
        var sendGate: CompletableDeferred<Unit>? = null
        var failWith: Throwable? = null
        /** What setMode reports the footer showed afterwards (null: the requested mode). */
        var landed: String? = null
        val opened = mutableListOf<Pair<SessionRef, String?>>()

        private fun record(s: String) { calls += s; failWith?.let { throw it } }

        override val sessions: StateFlow<List<Session>> = MutableStateFlow(emptyList())
        override val machineErrors: StateFlow<Map<String, String>> = MutableStateFlow(emptyMap())
        override val events: SharedFlow<SessionEvent> = MutableSharedFlow()
        override fun session(ref: SessionRef): Session? = conv.value.live?.session
        override fun open(ref: SessionRef, agentId: String?): StateFlow<ConversationState> { opened += ref to agentId; return conv }
        override suspend fun refresh(connectionId: String?) { calls += "refresh $connectionId" }
        override suspend fun history(connectionId: String, cwd: String?, limit: Int, before: Long?) = emptyList<Session>()
        override suspend fun new(connectionId: String, request: NewSessionRequest, images: List<ImageAttachment>): NewSessionResult = error("unused")
        override suspend fun send(ref: SessionRef, text: String, images: List<ImageAttachment>): Boolean {
            sendGate?.await()
            record("send $text images=${images.size}")
            return true
        }
        override suspend fun key(ref: SessionRef, keys: List<SessionKey>) = record("key " + keys.joinToString(",") { (it as? SessionKey.Named)?.name ?: "text:" + (it as SessionKey.Text).text })
        override suspend fun setMode(ref: SessionRef, mode: String?): String? {
            record("mode ${mode ?: "once"}")
            return landed ?: mode
        }
        override suspend fun answer(ref: SessionRef, decision: SessionDecision, message: String?, toolUseId: String?) = record("answer ${decision.wire} ${message ?: "-"}${toolUseId?.let { " $it" } ?: ""}")
        override suspend fun ask(ref: SessionRef, answers: List<AskAnswer>) = record("ask ${answers.map { it.choices }}")
        override suspend fun interrupt(ref: SessionRef) = record("interrupt")
        override suspend fun stop(ref: SessionRef) = record("stop")
        override suspend fun remove(ref: SessionRef) = record("rm")
        override fun setBackgroundWatch(enabled: Boolean) {}
        override fun setPaused(paused: Boolean) {}
        override suspend fun hold(connectionId: String) {}
        override fun release(connectionId: String) {}
    }

    private fun reducer() = SessionReducer(ref) { 1_000_000L }

    private fun fixture(name: String): List<FollowEvent> =
        (javaClass.classLoader!!.getResource("fixtures/$name") ?: error("missing fixture $name")).readText()
            .lines().filter { it.isNotBlank() }.mapNotNull { SessionProtocol.parseFollowLine(it) }

    private fun session(
        state: SessionState = SessionState.IDLE,
        process: SessionProcess = SessionProcess.LIVE,
        mode: String? = null,
        model: String? = null,
        pending: SessionPending? = null,
    ) = Session(sessionId = sid, cwd = "/p", name = "probe", state = state, process = process, permissionMode = mode, model = model, pending = pending, updatedAt = 1, connectionId = "conn")

    private fun stateWith(s: Session): ConversationState {
        val r = reducer()
        r.applySession(s)
        r.accept(FollowEvent.CaughtUp(10))
        return r.snapshot()
    }

    private fun TestScope.vm(hub: FakeHub, agentId: String? = null, commands: List<SlashCommand> = emptyList()): SessionChatViewModel {
        val vm = SessionChatViewModel(
            hub = hub,
            ref = ref,
            agentId = agentId,
            settings = MutableStateFlow(AppSettings()),
            loadCommands = { _, _ -> commands },
            optimisticTimeoutMs = 5_000,
            compute = dispatcher,
        )
        backgroundScope.launch { vm.state.collect {} }
        backgroundScope.launch { vm.commands.collect {} }
        return vm
    }

    @Test
    fun opensTheSessionAndRendersHistoryDraftAndFinal() = runTest(dispatcher) {
        val r = reducer()
        val hub = FakeHub(r.snapshot())
        val vm = vm(hub)
        val evs = fixture("session/follow_turn.jsonl")
        val draftAt = evs.indexOfFirst { it is FollowEvent.Draft && it.text == "Hello wor" }
        evs.take(draftAt + 1).forEach { r.accept(it) }
        hub.conv.value = r.snapshot()
        advanceUntilIdle()
        assertEquals(listOf(ref to null), hub.opened)
        val streaming = vm.state.value.items.last() as ChatItem.AssistantText
        assertTrue(streaming.streaming)
        assertEquals("Hello wor", streaming.text)
        assertEquals("Improvising", vm.state.value.live?.status?.verb?.let(::statusVerb))

        evs.drop(draftAt + 1).forEach { r.accept(it) }
        hub.conv.value = r.snapshot()
        advanceUntilIdle()
        val texts = vm.state.value.items.filterIsInstance<ChatItem.AssistantText>()
        assertTrue(texts.none { it.streaming })
        assertEquals("Hello **world**!", texts.last().text.trim())
        assertNull(vm.state.value.live?.status)
    }

    @Test
    fun sendingToARetiredSessionShowsWakingThenClears() = runTest(dispatcher) {
        val hub = FakeHub(stateWith(session(process = SessionProcess.RETIRED)))
        val vm = vm(hub)
        advanceUntilIdle()
        assertTrue(vm.state.value.retired)
        hub.sendGate = CompletableDeferred()
        vm.composer.appendText("hello again")
        vm.send()
        advanceUntilIdle()
        assertTrue(vm.state.value.waking)
        assertTrue(vm.state.value.sending)
        assertEquals("Waking…", sessionStatusLabel(vm.state.value))
        assertTrue("draft taken", vm.composer.isEmpty)
        hub.sendGate!!.complete(Unit)
        advanceUntilIdle()
        assertFalse(vm.state.value.waking)
        assertFalse(vm.state.value.sending)
        assertEquals(listOf("send hello again images=0"), hub.calls)
    }

    @Test
    fun aLiveSessionSendsWithoutWaking() = runTest(dispatcher) {
        val hub = FakeHub(stateWith(session(process = SessionProcess.LIVE)))
        val vm = vm(hub)
        advanceUntilIdle()
        hub.sendGate = CompletableDeferred()
        vm.composer.appendText("/compact keep tests")
        vm.send()
        advanceUntilIdle()
        assertTrue(vm.state.value.sending)
        assertFalse(vm.state.value.waking)
        hub.sendGate!!.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("send /compact keep tests images=0"), hub.calls)
    }

    @Test
    fun aFailedSendPutsTheDraftBackAndExplainsTheTerminalHold() = runTest(dispatcher) {
        val hub = FakeHub(stateWith(session()))
        val vm = vm(hub)
        val messages = mutableListOf<String>()
        backgroundScope.launch { vm.messages.collect { messages += it } }
        advanceUntilIdle()
        hub.failWith = RemoteException("held", code = SessionErrorCodes.EHELD)
        vm.composer.appendText("please")
        vm.send()
        advanceUntilIdle()
        assertEquals("please", vm.composer.value.text)
        assertTrue(messages.single(), messages.single().contains("type /bg there"))
    }

    @Test
    fun modeChipPressesShiftTabAndShowsTheModeUntilConfirmed() = runTest(dispatcher) {
        val hub = FakeHub(stateWith(session(mode = "default")))
        val vm = vm(hub)
        advanceUntilIdle()
        assertEquals(PermissionMode.ACCEPT_EDITS, vm.cycleMode())
        advanceTimeBy(100)
        assertEquals("acceptEdits", vm.state.value.permissionMode)
        assertEquals(listOf("mode once"), hub.calls)

        vm.setMode(PermissionMode.AUTO) // the helper presses Shift+Tab until the footer shows auto
        advanceTimeBy(100)
        assertEquals("mode auto", hub.calls.last())
        assertEquals("auto", vm.state.value.permissionMode)

        // The session confirms: the optimistic value gives way to the real one.
        hub.conv.value = stateWith(session(mode = "auto").copy(updatedAt = 2))
        advanceTimeBy(100)
        assertEquals("auto", vm.state.value.permissionMode)

        // Never confirmed → falls back to what the session reports after the timeout.
        vm.cycleMode()
        advanceTimeBy(100)
        assertEquals("default", vm.state.value.permissionMode)
        advanceTimeBy(6_000)
        assertEquals("auto", vm.state.value.permissionMode)

        val before = hub.calls.size
        vm.setMode(PermissionMode.BYPASS)
        advanceUntilIdle()
        assertEquals("bypass is not a cycle step", before, hub.calls.size)
    }

    @Test
    fun theChipShowsWhereTheCycleReallyLanded() = runTest(dispatcher) {
        // Haiku offers no auto mode: Shift+Tab from plan goes straight back to manual.
        val hub = FakeHub(stateWith(session(mode = "plan")))
        val vm = vm(hub)
        advanceUntilIdle()
        hub.landed = "default"
        assertEquals(PermissionMode.AUTO, vm.cycleMode())
        advanceTimeBy(100)
        assertEquals("default", vm.state.value.permissionMode)
    }

    @Test
    fun aStoppedSessionKeepsItsModeUntilItWakes() = runTest(dispatcher) {
        val hub = FakeHub(stateWith(session(mode = "default", process = SessionProcess.RETIRED)))
        val vm = vm(hub)
        val messages = mutableListOf<String>()
        backgroundScope.launch { vm.messages.collect { messages += it } }
        advanceUntilIdle()
        assertNull(vm.cycleMode())
        vm.setMode(PermissionMode.PLAN)
        advanceUntilIdle()
        assertTrue(hub.calls.isEmpty())
        assertTrue(messages.all { it.contains("wake") })
        assertEquals("Stopped", sessionStatusLabel(vm.state.value))
    }

    @Test
    fun helperSaysWhyASessionIsMissing() = runTest(dispatcher) {
        val hub = FakeHub(stateWith(session()))
        val vm = vm(hub)
        val messages = mutableListOf<String>()
        backgroundScope.launch { vm.messages.collect { messages += it } }
        advanceUntilIdle()
        hub.failWith = RemoteException("This session isn't running. Send it a message to wake it.", code = SessionErrorCodes.ENOSESSION)
        vm.pressKeys(listOf(SessionKey.Enter))
        advanceUntilIdle()
        assertTrue(messages.single(), messages.single().contains("Send it a message to wake it"))
    }

    @Test
    fun aModelChangeOnAStoppedSessionShowsWakingAndHoldsItsLabel() = runTest(dispatcher) {
        val hub = FakeHub(stateWith(session(model = "claude-haiku-4-5", process = SessionProcess.RETIRED)))
        val vm = vm(hub)
        advanceUntilIdle()
        hub.sendGate = CompletableDeferred()
        vm.setModel("sonnet")
        advanceTimeBy(100)
        assertTrue(vm.state.value.waking)
        assertEquals("Waking…", sessionStatusLabel(vm.state.value))
        // Mid-wake the session reports something else (the resumed worker's first state): the label holds.
        hub.conv.value = stateWith(session(model = "claude-opus-5", process = SessionProcess.LIVE).copy(updatedAt = 2))
        advanceTimeBy(10_000)
        assertEquals("sonnet", vm.state.value.model)
        hub.sendGate!!.complete(Unit)
        advanceTimeBy(100)
        assertFalse(vm.state.value.waking)
        assertEquals(listOf("send /model sonnet images=0"), hub.calls)
    }

    @Test
    fun modelChipTypesSlashModel() = runTest(dispatcher) {
        val hub = FakeHub(stateWith(session(model = "claude-sonnet-4-5")))
        val vm = vm(hub)
        advanceUntilIdle()
        vm.setModel("haiku")
        advanceTimeBy(100)
        assertEquals(listOf("send /model haiku images=0"), hub.calls)
        assertEquals("haiku", vm.state.value.model)
    }

    @Test
    fun promptsAnswerThroughAnswerAskOrEsc() = runTest(dispatcher) {
        val perm = SessionPending.Permission(toolUseId = "toolu_P", toolName = "Bash", inputJson = "{\"command\":\"ls\"}")
        val hub = FakeHub(stateWith(session(state = SessionState.NEEDS_YOU, pending = perm)))
        val vm = vm(hub)
        advanceUntilIdle()
        val p = vm.state.value.conversation.pendingPermissions.single()
        assertEquals(ALWAYS_ALLOW_SENTINEL, p.suggestions.single().rawJson)
        vm.respond("toolu_P", PermissionDecision.Allow(alwaysAllow = listOf(p.suggestions.single().rawJson)))
        advanceUntilIdle()
        vm.respond("toolu_P", PermissionDecision.Deny("not prod"))
        advanceUntilIdle()

        val q = SessionPending.Question(toolUseId = "toolu_Q", inputJson = "{\"questions\":[]}")
        hub.conv.value = stateWith(session(state = SessionState.NEEDS_YOU, pending = q).copy(updatedAt = 3))
        advanceUntilIdle()
        vm.respond("toolu_Q", PermissionDecision.Answer(listOf(AskAnswer(listOf(0)))))
        advanceUntilIdle()
        vm.respond("toolu_Q", PermissionDecision.Deny("The user chose not to answer."))
        advanceUntilIdle()
        // Answers name the prompt they answer: the helper refuses (ESTALE) if another one is open by then.
        assertEquals(listOf("answer allow_always - toolu_P", "answer deny not prod toolu_P", "ask [[0]]", "key esc"), hub.calls)
    }

    @Test
    fun dialogKeysStopAndRemove() = runTest(dispatcher) {
        val dialog = SessionPending.Dialog(dialog = DialogKind.MCP_SERVERS, title = "2 new MCP servers found")
        val hub = FakeHub(stateWith(session(state = SessionState.NEEDS_YOU, pending = dialog)))
        val vm = vm(hub)
        var closed = false
        backgroundScope.launch { vm.closed.collect { closed = true } }
        advanceUntilIdle()
        assertEquals(DialogKind.MCP_SERVERS, vm.state.value.dialog?.dialog)
        assertTrue("dialogs are not permission rows", vm.state.value.conversation.pendingPermissions.isEmpty())
        vm.pressKeys(listOf(SessionKey.Esc))
        vm.interrupt()
        vm.stop()
        advanceUntilIdle()
        vm.remove()
        advanceUntilIdle()
        assertEquals(listOf("key esc", "interrupt", "stop", "rm"), hub.calls)
        assertTrue(closed)
    }

    @Test
    fun anUnreadWaitGetsTheKeyPadButAHandoffDoesNot() = runTest(dispatcher) {
        // Needs-you with nothing read from the screen: an empty dialog so the key pad can still answer it.
        val hub = FakeHub(stateWith(session(state = SessionState.NEEDS_YOU)))
        val vm = vm(hub)
        advanceUntilIdle()
        assertTrue(vm.state.value.dialog != null)
        // Claude ended its turn handing work back: answered with a normal message. Regression: the phone showed
        // an empty "Claude is asking" key pad with no question in it.
        hub.conv.value = stateWith(session(state = SessionState.NEEDS_YOU).copy(handoff = true, waitingFor = "rebuild and test", updatedAt = 2))
        advanceUntilIdle()
        assertEquals(null, vm.state.value.dialog)
    }

    @Test
    fun aSubagentViewIsReadOnly() = runTest(dispatcher) {
        val hub = FakeHub(stateWith(session()))
        val vm = vm(hub, agentId = "a1b2c3", commands = listOf(SlashCommand("compact", "")))
        advanceUntilIdle()
        assertEquals(listOf(ref to "a1b2c3"), hub.opened)
        assertTrue(vm.state.value.readOnly)
        vm.composer.appendText("hi")
        vm.send()
        vm.cycleMode()
        vm.pressKeys(listOf(SessionKey.Enter))
        advanceUntilIdle()
        assertTrue(hub.calls.isEmpty())
        assertTrue("no slash commands in a subagent view", vm.commands.value.isEmpty())
    }

    @Test
    fun slashCommandsLoadForTheSessionFolder() = runTest(dispatcher) {
        val hub = FakeHub(stateWith(session()))
        val vm = vm(hub, commands = listOf(SlashCommand("compact", "Compact")))
        advanceUntilIdle()
        assertEquals("compact", vm.commands.first { it.isNotEmpty() }.single().name)
    }
}
