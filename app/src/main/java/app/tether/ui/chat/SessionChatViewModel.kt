package app.tether.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tether.core.AppSettings
import app.tether.core.ChatItem
import app.tether.core.Connection
import app.tether.core.ConversationState
import app.tether.core.PermissionDecision
import app.tether.core.PermissionMode
import app.tether.core.RunStatus
import app.tether.core.Session
import app.tether.core.SessionErrorCodes
import app.tether.core.SessionHub
import app.tether.core.SessionKey
import app.tether.core.SessionLive
import app.tether.core.SessionPending
import app.tether.core.SessionProcess
import app.tether.core.SessionRef
import app.tether.core.SlashCommand
import app.tether.remote.RemoteException
import app.tether.ui.components.projectName
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Everything the session screen draws. */
data class SessionChatUiState(
    val ref: SessionRef,
    /** A subagent's transcript, read-only; null for the session itself. */
    val agentId: String?,
    val conversation: ConversationState,
    /** Rows to render: transcript + draft, filtered, with peer rows. */
    val items: List<ChatItem>,
    val machineName: String?,
    val machineAccent: Int,
    val showThinking: Boolean,
    val compactTools: Boolean,
    /** A message is on its way (upload + send). */
    val sending: Boolean = false,
    /** The message being sent had to wake a retired session first. */
    val waking: Boolean = false,
    val respondingIds: Set<String> = emptySet(),
    /** Keys for a dialog are on their way. */
    val pressing: Boolean = false,
    val stopping: Boolean = false,
    val removing: Boolean = false,
    /** Effective mode / model, including an optimistic value right after a chip tap. */
    val permissionMode: String?,
    val model: String?,
) {
    val live: SessionLive? get() = conversation.live
    val session: Session? get() = live?.session
    val readOnly: Boolean get() = agentId != null
    val heldByTerminal: Boolean get() = live?.heldByTerminal == true
    /** No worker runs: the next message wakes it (same id). Unknown sessions count as retired. */
    val retired: Boolean get() = session?.process != SessionProcess.LIVE
    val working: Boolean get() = conversation.status == RunStatus.WORKING
    val dialog: SessionPending.Dialog? get() = live?.pending as? SessionPending.Dialog
    val title: String
        get() = session?.title?.takeIf { it.isNotBlank() }
            ?: conversation.title?.takeIf { it.isNotBlank() }
            ?: conversation.cwd?.let(::projectName)
            ?: ref.short
}

private data class LocalSessionState(
    val sending: Boolean = false,
    val waking: Boolean = false,
    val respondingIds: Set<String> = emptySet(),
    val pressing: Boolean = false,
    val stopping: Boolean = false,
    val removing: Boolean = false,
    val pendingMode: String? = null,
    val pendingModeBase: String? = null,
    val pendingModel: String? = null,
    val pendingModelBase: String? = null,
)

/**
 * One Claude Code session (or one of its subagents, read-only) on [SessionHub]: history then live
 * events, and everything an attached terminal could type — messages, slash commands, Shift+Tab,
 * Esc, prompt answers, dialog keys.
 */
@OptIn(FlowPreview::class)
class SessionChatViewModel(
    private val hub: SessionHub,
    val ref: SessionRef,
    val agentId: String? = null,
    settings: StateFlow<AppSettings>,
    machine: Flow<Connection?> = flowOf(null),
    private val loadCommands: suspend (connectionId: String, cwd: String) -> List<SlashCommand> = { _, _ -> emptyList() },
    /** How long an optimistic mode / model label waits for the session to confirm it. */
    private val optimisticTimeoutMs: Long = 8_000,
    /** Where the item list is computed (off the main thread). */
    compute: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.Default,
    bindDraft: (ComposerState, kotlinx.coroutines.CoroutineScope) -> Unit = { _, _ -> },
) : ViewModel() {

    private val local = MutableStateFlow(LocalSessionState())
    private val conv: StateFlow<ConversationState> = hub.open(ref, agentId)

    /** The composer draft lives here so it survives rotation and a failed send can be restored. */
    val composer = ComposerState()

    private val _messages = Channel<String>(Channel.BUFFERED)
    /** One-shot human messages for the snackbar. */
    val messages: Flow<String> = _messages.receiveAsFlow()

    private val _closed = Channel<Unit>(Channel.BUFFERED)
    /** Emits once the session was removed: the screen goes back. */
    val closed: Flow<Unit> = _closed.receiveAsFlow()

    private val machineFlow = machine.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val state: StateFlow<SessionChatUiState> = combine(conv, settings, local, machineFlow) { c, s, l, m ->
        SessionChatUiState(
            ref = ref,
            agentId = agentId,
            conversation = c.copy(pendingPermissions = withSessionSuggestions(c.pendingPermissions)),
            items = sessionChatItems(c, s.showThinking),
            machineName = m?.name,
            machineAccent = m?.accent ?: 0,
            showThinking = s.showThinking,
            compactTools = s.compactTools,
            sending = l.sending,
            waking = l.waking,
            respondingIds = l.respondingIds,
            pressing = l.pressing,
            stopping = l.stopping,
            removing = l.removing,
            permissionMode = l.pendingMode ?: c.permissionMode,
            model = l.pendingModel ?: c.model,
        )
    }
        .flowOn(compute)
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            SessionChatUiState(
                ref = ref,
                agentId = agentId,
                conversation = conv.value,
                items = emptyList(),
                machineName = null,
                machineAccent = 0,
                showThinking = settings.value.showThinking,
                compactTools = settings.value.compactTools,
                permissionMode = conv.value.permissionMode,
                model = conv.value.model,
            ),
        )

    /** Slash commands of the session's folder, for the `/` popup (sent as text like a terminal would). */
    val commands: StateFlow<List<SlashCommand>> = run {
        val out = MutableStateFlow<List<SlashCommand>>(emptyList())
        viewModelScope.launch {
            conv.map { it.cwd?.takeIf { c -> c.isNotBlank() } }.distinctUntilChanged().debounce(300).collectLatest { cwd ->
                if (cwd == null || agentId != null) return@collectLatest
                out.value = try {
                    loadCommands(ref.connectionId, cwd)
                } catch (ce: CancellationException) {
                    throw ce
                } catch (_: Throwable) {
                    emptyList()
                }
            }
        }
        out.asStateFlow()
    }

    init {
        bindDraft(composer, viewModelScope)
        viewModelScope.launch { conv.collect(::reconcileOptimistic) }
    }

    private fun reconcileOptimistic(next: ConversationState) {
        val l = local.value
        var changed = l
        if (l.pendingMode != null && (next.permissionMode == l.pendingMode || next.permissionMode != l.pendingModeBase)) {
            changed = changed.copy(pendingMode = null, pendingModeBase = null)
        }
        if (l.pendingModel != null && (next.model == l.pendingModel || next.model != l.pendingModelBase)) {
            changed = changed.copy(pendingModel = null, pendingModelBase = null)
        }
        if (changed !== l) local.value = changed
    }

    private fun say(text: String) { _messages.trySend(text) }

    private fun describe(t: Throwable): String = when ((t as? RemoteException)?.code) {
        SessionErrorCodes.EHELD -> "A terminal on ${state.value.machineName ?: "the machine"} has this session open — type /bg there to continue here"
        SessionErrorCodes.ENODAEMON -> "Claude Code's background service isn't running on ${state.value.machineName ?: "the machine"}"
        SessionErrorCodes.ENOSESSION -> "This session no longer exists on the machine"
        else -> friendlyError(t)
    }

    private fun launchAction(failure: String, block: suspend () -> Unit): Job = viewModelScope.launch {
        try {
            block()
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            say("$failure — ${describe(t)}")
        }
    }

    // ── messages ──

    /** Sends the composer's draft (text, slash command or images). A retired session wakes first. */
    fun send() {
        if (agentId != null || local.value.sending) return
        val (text, attachments) = composer.take() ?: return
        val wake = state.value.retired
        local.update { it.copy(sending = true, waking = wake) }
        viewModelScope.launch {
            try {
                hub.send(ref, text, attachments.map { it.image })
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                composer.restore(text, attachments)
                say("Couldn't send — ${describe(t)}")
            } finally {
                local.update { it.copy(sending = false, waking = false) }
            }
        }
    }

    // ── keys ──

    /** Presses keys in the session's terminal (dialogs, the key pad). */
    fun pressKeys(keys: List<SessionKey>) {
        if (keys.isEmpty() || agentId != null) return
        local.update { it.copy(pressing = true) }
        viewModelScope.launch {
            try {
                hub.key(ref, keys)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                say("Couldn't press that — ${describe(t)}")
            } finally {
                local.update { it.copy(pressing = false) }
            }
        }
    }

    /** The stop button: Esc, like in the terminal. The session stays. */
    fun interrupt() = launchAction("Couldn't interrupt") { hub.interrupt(ref) }

    /** The mode chip: one Shift+Tab. Returns the mode it should land on (for the chip's toast). */
    fun cycleMode(): PermissionMode {
        val next = nextMode(state.value.permissionMode)
        moveMode(next, presses = 1)
        return next
    }

    /** The mode sheet: as many Shift+Tabs as it takes to reach [mode]. */
    fun setMode(mode: PermissionMode) {
        val presses = modeKeyPresses(state.value.permissionMode, mode)
        if (presses == null) {
            say("${mode.label} mode can only be chosen when the session starts")
            return
        }
        if (presses == 0) return
        moveMode(mode, presses)
    }

    private fun moveMode(target: PermissionMode, presses: Int) {
        if (agentId != null) return
        val base = conv.value.permissionMode
        local.update { it.copy(pendingMode = target.cli, pendingModeBase = base) }
        viewModelScope.launch {
            try {
                hub.key(ref, List(presses) { SessionKey.ShiftTab })
                expireOptimistic { it.pendingMode == target.cli }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                local.update { it.copy(pendingMode = null, pendingModeBase = null) }
                say("Couldn't switch to ${target.label} — ${describe(t)}")
            }
        }
    }

    /** The model chip: types `/model <x>` into the session. */
    fun setModel(model: String) {
        if (agentId != null) return
        val base = conv.value.model
        if (model == (local.value.pendingModel ?: base)) return
        local.update { it.copy(pendingModel = model, pendingModelBase = base) }
        viewModelScope.launch {
            try {
                hub.send(ref, modelCommand(model))
                expireOptimistic { it.pendingModel == model }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                local.update { it.copy(pendingModel = null, pendingModelBase = null) }
                say("Couldn't change model — ${describe(t)}")
            }
        }
    }

    /** An optimistic label the session never confirmed falls back to what it reports. */
    private suspend fun expireOptimistic(stillPending: (LocalSessionState) -> Boolean) {
        delay(optimisticTimeoutMs)
        local.update { l ->
            if (!stillPending(l)) l
            else l.copy(pendingMode = null, pendingModeBase = null, pendingModel = null, pendingModelBase = null)
        }
    }

    // ── prompts ──

    /** Answers the permission / question panel. [requestId] is the tool_use id of the prompt. */
    fun respond(requestId: String, decision: PermissionDecision) {
        if (requestId in local.value.respondingIds || agentId != null) return
        val toolName = conv.value.pendingPermissions.firstOrNull { it.requestId == requestId }?.toolName.orEmpty()
        local.update { it.copy(respondingIds = it.respondingIds + requestId) }
        viewModelScope.launch {
            try {
                when (val r = sessionReplyFor(toolName, decision)) {
                    is SessionReply.Answer -> hub.answer(ref, r.decision, r.message)
                    is SessionReply.Ask -> hub.ask(ref, r.answers)
                    SessionReply.Dismiss -> hub.key(ref, listOf(SessionKey.Esc))
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                say("Couldn't answer Claude — ${describe(t)}")
            } finally {
                local.update { it.copy(respondingIds = it.respondingIds - requestId) }
            }
        }
    }

    // ── lifecycle ──

    /** Retires the worker; the next message wakes it with the same id and history. */
    fun stop() {
        if (local.value.stopping) return
        local.update { it.copy(stopping = true) }
        viewModelScope.launch {
            try {
                hub.stop(ref)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                say("Couldn't stop the session — ${describe(t)}")
            } finally {
                local.update { it.copy(stopping = false) }
            }
        }
    }

    /** Kills and deletes the session's job (the transcript stays on the machine), then closes. */
    fun remove() {
        if (local.value.removing) return
        local.update { it.copy(removing = true) }
        viewModelScope.launch {
            try {
                hub.remove(ref)
                _closed.send(Unit)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                say("Couldn't remove — ${describe(t)}")
            } finally {
                local.update { it.copy(removing = false) }
            }
        }
    }

    /** Nudges the hub to re-read this machine (the follow stream reconnects on its own). */
    fun retry() {
        viewModelScope.launch { runCatching { hub.refresh(ref.connectionId) } }
    }
}
