package app.tether.ui.chat

import app.tether.core.PermissionState

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tether.AppContainer
import app.tether.core.AppSettings
import app.tether.core.ChatItem
import app.tether.core.Connection
import app.tether.core.ConversationState
import app.tether.core.NoticeKind
import app.tether.core.PermissionDecision
import app.tether.core.PermissionMode
import app.tether.core.FallbackModels
import app.tether.core.RunRef
import app.tether.core.NativeTimelineEntry
import app.tether.core.SlashCommand
import app.tether.core.isNative
import app.tether.core.nativeId
import app.tether.remote.NativeAgents
import app.tether.core.RunStatus
import app.tether.core.StartRunRequest
import app.tether.ui.components.projectName
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ───────────────────────────── Shared helpers ─────────────────────────────

/**
 * Items the list actually renders. TodoWrite rows are drawn by the todo strip, and thinking rows are
 * dropped entirely when the user turned thinking off — filtering here (off the main thread) keeps
 * the LazyColumn free of zero-height slots that would still take spacing.
 */
internal fun visibleChatItems(items: List<ChatItem>, showThinking: Boolean): List<ChatItem> {
    // Permission answers fold into the tool row they belong to (one row, a small badge),
    // instead of a second "Allowed Edit …" row right under it.
    val toolIds = HashSet<String>()
    fun collect(list: List<ChatItem>) {
        for (i in list) if (i is ChatItem.ToolCall) { toolIds += i.toolUseId; collect(i.children) }
    }
    collect(items)
    val decisions = HashMap<String, PermissionState>()
    for (i in items) if (i is ChatItem.Permission && i.toolUseId != null && i.toolUseId in toolIds) decisions[i.toolUseId] = i.state
    fun ChatItem.ToolCall.withDecision(): ChatItem.ToolCall {
        val d = decisions[toolUseId]
        val kids = if (children.isEmpty()) children else children.map { (it as? ChatItem.ToolCall)?.withDecision() ?: it }
        return if (d == decision && kids === children) this else copy(decision = d, children = kids)
    }

    val seen = HashSet<String>(items.size * 2)
    val out = ArrayList<ChatItem>(items.size)
    for (raw in items) {
        var item = raw
        val keep = when (item) {
            is ChatItem.ToolCall -> item.name != "TodoWrite"
            // Redacted / empty thinking has nothing to open — don't spend a row on it.
            is ChatItem.Thinking -> showThinking && (item.streaming || item.text.isNotBlank())
            is ChatItem.Permission -> item.toolUseId == null || item.toolUseId !in toolIds
            else -> true
        }
        if (!keep) continue
        if (item is ChatItem.ToolCall && decisions.isNotEmpty()) item = item.withDecision()
        // Consecutive thinking blocks read as one quiet "Thought" affordance.
        val prev = out.lastOrNull()
        if (item is ChatItem.Thinking && prev is ChatItem.Thinking) {
            val tokens = listOfNotNull(prev.estimatedTokens, item.estimatedTokens).takeIf { it.isNotEmpty() }?.sum()
            out[out.lastIndex] = prev.copy(
                text = listOf(prev.text, item.text).filter { it.isNotBlank() }.joinToString("\n\n"),
                streaming = item.streaming,
                estimatedTokens = tokens,
            )
            continue
        }
        // LazyColumn crashes on duplicate keys — never trust upstream blindly.
        if (seen.add(item.key)) {
            out += item
        } else {
            var n = 2
            while (!seen.add("${item.key}#$n")) n++
            out += item.withKey("${item.key}#$n")
        }
    }
    return out
}

internal fun friendlyError(t: Throwable, fallback: String = "Something went wrong"): String {
    val msg = t.message?.trim().orEmpty()
    return when {
        msg.isNotEmpty() -> msg
        else -> t::class.simpleName?.let { "$fallback ($it)" } ?: fallback
    }
}

/** Re-keys an item so history carried over from a previous run can never collide with the new run's keys. */
private fun ChatItem.rekeyed(prefix: String): ChatItem = when (this) {
    is ChatItem.User -> copy(key = prefix + key, queued = false)
    is ChatItem.AssistantText -> copy(key = prefix + key, streaming = false)
    is ChatItem.Thinking -> copy(key = prefix + key, streaming = false)
    is ChatItem.ToolCall -> copy(key = prefix + key, children = children.map { it.rekeyed(prefix) })
    else -> withKey(prefix + key)
}

internal fun ChatItem.withKey(newKey: String): ChatItem = when (this) {
    is ChatItem.User -> copy(key = newKey)
    is ChatItem.AssistantText -> copy(key = newKey)
    is ChatItem.Thinking -> copy(key = newKey)
    is ChatItem.ToolCall -> copy(key = newKey)
    is ChatItem.Permission -> copy(key = newKey)
    is ChatItem.TurnSummary -> copy(key = newKey)
    is ChatItem.Notice -> copy(key = newKey)
}

// ───────────────────────────── Agent conversation ─────────────────────────────

data class ChatUiState(
    val ref: RunRef,
    val conversation: ConversationState,
    /** Items to render (carried history + live run, filtered). */
    val items: List<ChatItem>,
    val machineName: String?,
    val machineAccent: Int,
    val showThinking: Boolean,
    val compactTools: Boolean,
    /** The conversation stream itself failed (distinct from [ConversationState.error]). */
    val streamError: String?,
    val resuming: Boolean,
    val replying: Boolean = false,
    val removing: Boolean = false,
    val stopping: Boolean = false,
    val respondingIds: Set<String>,
    /** Effective mode, including an optimistic value while a mode change is in flight. */
    val permissionMode: String?,
    /** Effective model, including an optimistic value while a model change is in flight. */
    val model: String?,
    /**
     * Background agent: a model (or, while it's stopped, a mode) picked on the phone that the next
     * message applies by restarting it under the new settings. Null when nothing is waiting.
     */
    val nativeNextModel: String? = null,
    val nativeNextMode: String? = null,
) {
    val status: RunStatus get() = conversation.status
    /** Claude Code's own background agent (read-mostly: reply continues it, approvals happen on the computer). */
    val isNative: Boolean get() = ref.isNative
    /** Native agents never "end" here: a stopped one can still be continued. */
    val ended: Boolean get() = !isNative && (status == RunStatus.ENDED || status == RunStatus.FAILED)
    val title: String get() = conversation.title?.takeIf { it.isNotBlank() } ?: conversation.cwd?.let(::projectName) ?: "Agent"
}

private data class LocalChatState(
    val streamError: String? = null,
    val resuming: Boolean = false,
    val respondingIds: Set<String> = emptySet(),
    val pendingMode: String? = null,
    val pendingModeBase: String? = null,
    val pendingModel: String? = null,
    val pendingModelBase: String? = null,
    /** History of earlier runs of this same session, shown above the live run after a resume. */
    val carried: List<ChatItem> = emptyList(),
    /** A native reply (stop + resume on the machine) is in flight. */
    val replying: Boolean = false,
    /** Background agent settings waiting for the next message (see [ChatUiState.nativeNextModel]). */
    val nativeNextModel: String? = null,
    val nativeNextMode: String? = null,
    val removing: Boolean = false,
    val stopping: Boolean = false,
)

class ChatViewModel(private val container: AppContainer, initialRef: RunRef) : ViewModel() {

    private val refFlow = MutableStateFlow(initialRef)
    val ref: StateFlow<RunRef> = refFlow.asStateFlow()

    private val retryTick = MutableStateFlow(0)
    private val conv = MutableStateFlow(ConversationState(ref = initialRef))
    private val local = MutableStateFlow(LocalChatState())

    /** The composer draft lives here so it survives rotation and a failed send can be restored. */
    val composer = ComposerState()

    /** Background agents have no initialize reply to read commands from: ask the machine. */
    val nativeCommands: StateFlow<List<SlashCommand>> = viewModelScope.slashCommandsFor(
        container.agents,
        combine(refFlow, conv) { r, c -> c.cwd?.takeIf { r.isNative }?.let { r.connectionId to it } },
    )

    private val _messages = Channel<String>(Channel.BUFFERED)
    /** One-shot human messages for the snackbar. */
    val messages: Flow<String> = _messages.receiveAsFlow()

    val state: StateFlow<ChatUiState> = combine(
        conv,
        refFlow,
        container.connections.connections,
        container.settings.settings,
        local,
    ) { c: ConversationState, r: RunRef, conns: List<Connection>, s: AppSettings, l: LocalChatState ->
        val machine = conns.firstOrNull { it.id == r.connectionId }
        val live = visibleChatItems(c.items, s.showThinking)
        val items = mergeCarried(l.carried, live, s.showThinking)
        ChatUiState(
            ref = r,
            conversation = c,
            items = items,
            machineName = machine?.name,
            machineAccent = machine?.accent ?: 0,
            showThinking = s.showThinking,
            compactTools = s.compactTools,
            streamError = l.streamError,
            resuming = l.resuming,
            replying = l.replying,
            removing = l.removing,
            stopping = l.stopping,
            respondingIds = l.respondingIds,
            permissionMode = l.nativeNextMode ?: l.pendingMode ?: c.permissionMode,
            model = l.nativeNextModel ?: l.pendingModel ?: c.model,
            nativeNextModel = l.nativeNextModel,
            nativeNextMode = l.nativeNextMode,
        )
    }
        .flowOn(Dispatchers.Default)
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            ChatUiState(
                ref = initialRef,
                conversation = ConversationState(ref = initialRef),
                items = emptyList(),
                machineName = container.connections.get(initialRef.connectionId)?.name,
                machineAccent = container.connections.get(initialRef.connectionId)?.accent ?: 0,
                showThinking = container.settings.settings.value.showThinking,
                compactTools = container.settings.settings.value.compactTools,
                streamError = null,
                resuming = false,
                respondingIds = emptySet(),
                permissionMode = null,
                model = null,
            ),
        )

    init {
        Drafts.init(container.app)
        composer.bindDraft(viewModelScope, refFlow.map { Drafts.run(it) })
        viewModelScope.launch {
            combine(refFlow, retryTick) { r, _ -> r }.collectLatest { r ->
                var attempt = 0
                while (true) {
                    try {
                        container.agents.conversation(r).collect { next ->
                            attempt = 0
                            if (local.value.streamError != null) local.update { it.copy(streamError = null) }
                            reconcileOptimistic(next)
                            conv.value = next
                        }
                        break // the stream completed normally (run finished and the hub closed it)
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (t: Throwable) {
                        local.update { it.copy(streamError = friendlyError(t, "Lost the connection to this agent")) }
                        attempt++
                        // Gentle backoff: 2s, 4s, 8s … capped at 30s. Retry() short-circuits this.
                        delay((1_000L shl attempt.coerceAtMost(5)).coerceAtMost(30_000L))
                    }
                }
            }
        }
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

    private fun mergeCarried(carried: List<ChatItem>, live: List<ChatItem>, showThinking: Boolean): List<ChatItem> {
        if (carried.isEmpty()) return live
        // If the new run replays the resumed session's history itself, drop our carried copy.
        val carriedPrompts = carried.asSequence().filterIsInstance<ChatItem.User>().map { it.text.trim() }.filter { it.isNotEmpty() }.toSet()
        val replayed = carriedPrompts.isNotEmpty() && live.any { it is ChatItem.User && it.text.trim() in carriedPrompts }
        return if (replayed) live else visibleChatItems(carried, showThinking) + live
    }

    private fun say(text: String) { _messages.trySend(text) }

    private fun launchAction(failure: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                say("$failure — ${friendlyError(t)}")
            }
        }
    }

    // ── Actions ──

    private val _closed = Channel<Unit>(Channel.BUFFERED)
    /** Emits once this agent was removed — the screen goes back. */
    val closed: Flow<Unit> = _closed.receiveAsFlow()

    /** Native agent: continues the conversation (the hub stops + resumes it on the machine). */
    private fun replyNative() {
        if (local.value.replying) return
        val (text, attachments) = composer.take() ?: return
        val r = refFlow.value
        val model = local.value.nativeNextModel
        val mode = local.value.nativeNextMode
        local.update { it.copy(replying = true) }
        viewModelScope.launch {
            try {
                val next = container.agents.continueNative(r, text, model, mode)
                if (model != null || mode != null) local.update { it.copy(nativeNextModel = null, nativeNextMode = null) }
                if (next != r) refFlow.value = next // claude continued it under a new id: follow it
                if (r.nativeId?.startsWith(NativeAgents.TERMINAL_PREFIX) == true) say("Continued in a background copy · the terminal session is untouched")
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                composer.restore(text, attachments)
                say("Couldn't continue — ${friendlyError(t)}")
            } finally {
                local.update { it.copy(replying = false) }
            }
        }
    }

    /** Deletes the agent on the machine (`claude rm` for a background agent), then closes the screen. */
    fun remove() {
        if (local.value.removing) return
        val r = refFlow.value
        local.update { it.copy(removing = true) }
        viewModelScope.launch {
            try {
                container.agents.remove(r)
                _closed.send(Unit)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                say("Couldn't remove — ${friendlyError(t)}")
            } finally {
                local.update { it.copy(removing = false) }
            }
        }
    }

    suspend fun nativeTimeline(): List<NativeTimelineEntry> = container.agents.nativeTimeline(refFlow.value)

    /** Reads a background agent's pending question from its screen (it isn't on disk yet). */
    suspend fun fetchQuestion(): String = container.agents.nativeQuestion(refFlow.value)

    // ── branching (edit / retry / fork) ──
    private val _branched = Channel<RunRef>(Channel.BUFFERED)
    /** A new branch run started: the screen opens it. */
    val branched: Flow<RunRef> = _branched.receiveAsFlow()
    val branching = MutableStateFlow(false)

    /**
     * Starts a branch of this conversation. [fromSession] false = a fresh conversation (editing the
     * very first message); [atUuid] null with [fromSession] = copy the whole session.
     */
    fun branch(fromSession: Boolean, atUuid: String?, prompt: String?, restoreBefore: String? = null) {
        if (branching.value) return
        val c = state.value.conversation
        val sid = c.sessionId
        if (fromSession && sid == null) { say("This conversation has no session yet."); return }
        branching.value = true
        viewModelScope.launch {
            try {
                val ref = container.agents.branch(
                    connectionId = refFlow.value.connectionId,
                    sessionId = if (fromSession) sid else null,
                    cwd = c.cwd ?: "~",
                    atUuid = atUuid,
                    prompt = prompt,
                    title = state.value.title,
                    restoreBefore = restoreBefore,
                    sourceRunId = refFlow.value.runId,
                )
                _branched.send(ref)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                say("Couldn't branch — ${friendlyError(t)}")
            } finally {
                branching.value = false
            }
        }
    }

    suspend fun previewRewind(messageId: String): app.tether.core.RewindResult {
        val c = state.value.conversation
        val sid = c.sessionId ?: return app.tether.core.RewindResult(false)
        return container.agents.previewRewind(refFlow.value.connectionId, sid, messageId, c.cwd ?: "~", refFlow.value.runId)
    }

    suspend fun nativeLogs(): String = container.agents.nativeLogs(refFlow.value)

    /** Sends the composer's draft. Sending while Claude works is allowed: the CLI queues it. */
    fun send() {
        if (refFlow.value.isNative) return replyNative()
        val (text, attachments) = composer.take() ?: return
        val images = attachments.map { it.image }
        val r = refFlow.value
        viewModelScope.launch {
            try {
                container.agents.send(r, text, images)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                composer.restore(text, attachments)
                say("Couldn't send — ${friendlyError(t)}")
            }
        }
    }

    fun respond(requestId: String, decision: PermissionDecision) {
        if (requestId in local.value.respondingIds) return
        local.update { it.copy(respondingIds = it.respondingIds + requestId) }
        val r = refFlow.value
        viewModelScope.launch {
            try {
                container.agents.respond(r, requestId, decision)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                say("Couldn't answer Claude — ${friendlyError(t)}")
            } finally {
                local.update { it.copy(respondingIds = it.respondingIds - requestId) }
            }
        }
    }

    fun interrupt() {
        val r = refFlow.value
        launchAction("Couldn't interrupt") { container.agents.interrupt(r) }
    }

    /** Advances the Shift+Tab cycle; returns the mode it moved to (for the chip's transient label). */
    fun cycleMode(): PermissionMode {
        val cur = PermissionMode.fromCli(state.value.permissionMode) ?: PermissionMode.DEFAULT
        val idx = PermissionMode.cycle.indexOf(cur)
        val next = if (idx < 0) PermissionMode.DEFAULT else PermissionMode.cycle[(idx + 1) % PermissionMode.cycle.size]
        setMode(next)
        return next
    }

    fun setMode(mode: PermissionMode) {
        val nativeRun = conv.value.nativeRun
        if (refFlow.value.isNative && nativeRun?.alive != true) {
            // A stopped agent has no terminal to Shift+Tab: the mode rides along with the next message.
            local.update { it.copy(nativeNextMode = mode.cli.takeIf { m -> m != (conv.value.permissionMode ?: PermissionMode.DEFAULT.cli) }) }
            return
        }
        val base = conv.value.permissionMode
        if (mode.cli == (local.value.pendingMode ?: base)) return
        local.update { it.copy(pendingMode = mode.cli, pendingModeBase = base) }
        val r = refFlow.value
        viewModelScope.launch {
            try {
                container.agents.setPermissionMode(r, mode.cli)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                local.update { it.copy(pendingMode = null, pendingModeBase = null) }
                say("Couldn't switch to ${mode.label} — ${friendlyError(t)}")
            }
        }
    }

    fun setModel(model: String) {
        if (refFlow.value.isNative) {
            // Claude Code switches a background agent's model only on a restart, which the next message does.
            val same = modelLabel(model, FallbackModels) == modelLabel(conv.value.model, FallbackModels)
            local.update { it.copy(nativeNextModel = model.takeIf { !same }) }
            return
        }
        val base = conv.value.model
        if (model == (local.value.pendingModel ?: base)) return
        local.update { it.copy(pendingModel = model, pendingModelBase = base) }
        val r = refFlow.value
        viewModelScope.launch {
            try {
                container.agents.setModel(r, model)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                local.update { it.copy(pendingModel = null, pendingModelBase = null) }
                say("Couldn't change model — ${friendlyError(t)}")
            }
        }
    }

    fun stop() {
        if (local.value.stopping) return
        val r = refFlow.value
        local.update { it.copy(stopping = true) }
        viewModelScope.launch {
            try {
                container.agents.stop(r)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                say("Couldn't stop the agent — ${friendlyError(t)}")
            } finally {
                local.update { it.copy(stopping = false) }
            }
        }
    }

    /** Re-subscribes to the conversation and nudges the hub to refresh this machine. */
    fun retry() {
        local.update { it.copy(streamError = null) }
        retryTick.update { it + 1 }
        val conn = refFlow.value.connectionId
        viewModelScope.launch {
            runCatching { container.agents.refresh(conn) }
        }
    }

    /** Starts a new run resuming this session, then swaps this screen onto it in place. */
    fun resume() {
        if (local.value.resuming) return
        val c = conv.value
        val sid = c.sessionId
        if (sid == null) {
            say("This agent never started a session, so there's nothing to resume")
            return
        }
        val old = refFlow.value
        local.update { it.copy(resuming = true) }
        viewModelScope.launch {
            try {
                val newRef = container.agents.start(
                    old.connectionId,
                    StartRunRequest(
                        cwd = c.cwd ?: "~",
                        resumeSessionId = sid,
                        permissionMode = c.permissionMode?.takeIf { PermissionMode.fromCli(it) != null && it != PermissionMode.BYPASS.cli },
                        title = c.title,
                    ),
                )
                val history = withContext(Dispatchers.Default) {
                    val prefix = "run:${old.runId}:"
                    local.value.carried + c.items.map { it.rekeyed(prefix) } +
                        ChatItem.Notice("${prefix}resumed", "Resumed in a new run", NoticeKind.SESSION_START)
                }
                conv.value = c.copy(
                    ref = newRef,
                    items = emptyList(),
                    status = RunStatus.STARTING,
                    pendingPermissions = emptyList(),
                    todos = emptyList(),
                    error = null,
                    loadingHistory = true,
                    workingSince = null,
                    thinkingTokens = null,
                    queuedCount = 0,
                )
                local.update { it.copy(carried = history, resuming = false, streamError = null, respondingIds = emptySet()) }
                refFlow.value = newRef
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                local.update { it.copy(resuming = false) }
                say("Couldn't resume — ${friendlyError(t)}")
            }
        }
    }
}

// ───────────────────────────── Read-only session transcript ─────────────────────────────

data class SessionUiState(
    val loading: Boolean = true,
    val conversation: ConversationState? = null,
    val items: List<ChatItem> = emptyList(),
    val error: String? = null,
    val continuing: Boolean = false,
    val machineName: String? = null,
    val machineAccent: Int = 0,
    val showThinking: Boolean = true,
    val compactTools: Boolean = true,
) {
    val title: String
        get() = conversation?.title?.takeIf { it.isNotBlank() }
            ?: conversation?.cwd?.let(::projectName)
            ?: "Session"
}

class SessionViewModel(
    private val container: AppContainer,
    private val connectionId: String,
    private val sessionId: String,
) : ViewModel() {

    private val _state = MutableStateFlow(
        SessionUiState(
            machineName = container.connections.get(connectionId)?.name,
            machineAccent = container.connections.get(connectionId)?.accent ?: 0,
            showThinking = container.settings.settings.value.showThinking,
            compactTools = container.settings.settings.value.compactTools,
        )
    )
    val state: StateFlow<SessionUiState> = _state.asStateFlow()

    val composer = ComposerState()

    val commands: StateFlow<List<SlashCommand>> = viewModelScope.slashCommandsFor(
        container.agents,
        _state.map { s -> s.conversation?.cwd?.let { connectionId to it } },
    )

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = _messages.receiveAsFlow()

    val branching = MutableStateFlow(false)

    /** Branch this past session at a message (see ChatViewModel.branch); opens via [continued]. */
    fun branch(fromSession: Boolean, atUuid: String?, prompt: String?, restoreBefore: String? = null) {
        if (branching.value) return
        val conv = _state.value.conversation
        branching.value = true
        viewModelScope.launch {
            try {
                val ref = container.agents.branch(
                    connectionId = connectionId,
                    sessionId = if (fromSession) sessionId else null,
                    cwd = conv?.cwd ?: "~",
                    atUuid = atUuid,
                    prompt = prompt,
                    title = conv?.title,
                    restoreBefore = restoreBefore,
                )
                _continued.send(ref)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                _messages.send("Couldn't branch — ${friendlyError(t)}")
            } finally {
                branching.value = false
            }
        }
    }

    suspend fun previewRewind(messageId: String): app.tether.core.RewindResult =
        container.agents.previewRewind(connectionId, sessionId, messageId, _state.value.conversation?.cwd ?: "~")

    private val _continued = Channel<RunRef>(Channel.BUFFERED)
    /** Emits once the continuation run has started — the screen navigates to it. */
    val continued: Flow<RunRef> = _continued.receiveAsFlow()

    init {
        Drafts.init(container.app)
        composer.bindDraft(viewModelScope, flowOf(Drafts.session(connectionId, sessionId)))
        load()
        viewModelScope.launch {
            container.settings.settings.collect { s ->
                val cur = _state.value
                if (cur.showThinking != s.showThinking || cur.compactTools != s.compactTools) {
                    val items = cur.conversation?.let { withContext(Dispatchers.Default) { visibleChatItems(it.items, s.showThinking) } } ?: cur.items
                    _state.update { it.copy(showThinking = s.showThinking, compactTools = s.compactTools, items = items) }
                }
            }
        }
    }

    fun load() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val conv = container.agents.transcript(connectionId, sessionId)
                val items = withContext(Dispatchers.Default) { visibleChatItems(conv.items, _state.value.showThinking) }
                _state.update { it.copy(loading = false, conversation = conv, items = items, error = null) }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                _state.update { it.copy(loading = false, error = friendlyError(t, "Couldn't load this conversation")) }
            }
        }
    }

    /** Starts a new run that resumes this session with the composer's prompt. */
    fun continueConversation() {
        if (_state.value.continuing) return
        val (text, attachments) = composer.take() ?: return
        val cwd = _state.value.conversation?.cwd ?: "~"
        val title = _state.value.conversation?.title
        _state.update { it.copy(continuing = true) }
        viewModelScope.launch {
            try {
                val ref = container.agents.start(
                    connectionId,
                    StartRunRequest(
                        cwd = cwd, prompt = text, resumeSessionId = sessionId, title = title,
                        model = container.settings.settings.value.defaultModel.takeIf { it != "default" && it.isNotBlank() },
                        // Never inherit Bypass silently when continuing from history.
                        permissionMode = container.settings.settings.value.defaultPermissionMode
                            .takeIf { it != PermissionMode.BYPASS.cli } ?: PermissionMode.DEFAULT.cli,
                    ),
                    attachments.map { it.image },
                )
                _continued.send(ref)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                composer.restore(text, attachments)
                _messages.trySend("Couldn't continue — ${friendlyError(t)}")
            } finally {
                _state.update { it.copy(continuing = false) }
            }
        }
    }
}
