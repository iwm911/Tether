package app.tether.ui.chat

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.tether.LocalAppContainer
import app.tether.core.ChatItem
import app.tether.core.LinkState
import app.tether.core.PermissionDecision
import app.tether.core.RunStatus
import app.tether.core.SessionRef
import app.tether.core.SessionState
import app.tether.core.SubagentStatus
import app.tether.ui.chat.render.LocalChatCwd
import app.tether.ui.chat.render.LocalSubagentLinks
import app.tether.ui.chat.render.LocalWorkflowAgents
import app.tether.ui.chat.render.WorkflowAgentLink
import app.tether.ui.components.LocalPlanName
import app.tether.ui.components.MachineBadge
import app.tether.ui.components.TetherTopBar
import app.tether.ui.components.label
import app.tether.ui.theme.Motion
import app.tether.ui.theme.Space
import app.tether.ui.theme.TetherTheme
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** One-line status under the title. */
internal fun sessionStatusLabel(s: SessionChatUiState): String {
    val link = s.conversation.link
    val session = s.session
    return when {
        link is LinkState.Failed -> "Offline"
        s.readOnly -> if (s.conversation.status == RunStatus.WORKING) "Subagent working" else "Subagent"
        s.waking -> "Waking…"
        s.heldByTerminal -> if (session?.needsYou == true) "Waiting in the terminal" else "In a terminal"
        session == null -> if (s.conversation.loadingHistory) "Loading…" else s.conversation.status.label
        session.state == SessionState.NEEDS_YOU -> if (session.handoff) "Your turn" else "Needs you"
        session.state == SessionState.WORKING -> "Working"
        session.state == SessionState.FAILED -> "Failed"
        s.retired -> "Stopped"
        else -> "Idle"
    }
}

/** The line above the composer: waking, how to wake a stopped session, the queue. */
internal fun sessionComposerHint(s: SessionChatUiState): String? {
    val queued = s.conversation.queuedCount
    return when {
        s.waking -> "Waking…"
        s.retired && !s.sending && s.session != null -> "Stopped · your reply wakes it"
        queued > 0 -> if (queued == 1) "1 message queued · Claude reads it next" else "$queued messages queued · Claude reads them next"
        else -> null
    }
}

/**
 * The session screen: one screen for every Claude Code session — started here, in a terminal, or
 * yesterday — and, with [agentId], a read-only view of one of its subagents. History, then live
 * updates (streaming draft, spinner line, prompts). Everything an attached terminal can do is here.
 */
@Composable
fun SessionChatScreen(
    ref: SessionRef,
    agentId: String? = null,
    onBack: () -> Unit,
    onOpenMachine: (String) -> Unit,
    onOpenSubagent: (agentId: String) -> Unit = {},
) {
    val container = LocalAppContainer.current
    val vm: SessionChatViewModel = viewModel(key = "sessionchat/${ref.connectionId}/${ref.sessionId}/${agentId.orEmpty()}") {
        SessionChatViewModel(
            hub = container.sessions,
            ref = ref,
            agentId = agentId,
            settings = container.settings.settings,
            machine = container.connections.connections.map { list -> list.firstOrNull { it.id == ref.connectionId } },
            loadCommands = { conn, cwd -> container.sessions.slashCommands(conn, cwd) },
            bindDraft = { composer, scope ->
                if (agentId == null) {
                    Drafts.init(container.app)
                    composer.bindDraft(scope, flowOf(Drafts.session(ref.connectionId, ref.sessionId)))
                }
            },
        )
    }
    val state by vm.state.collectAsStateWithLifecycle()
    val slash by vm.commands.collectAsStateWithLifecycle()
    val conv = state.conversation
    val live = state.live
    val machines by container.connections.connections.collectAsStateWithLifecycle()
    val plan = conv.planName ?: machines.firstOrNull { it.id == ref.connectionId }?.lastPlan

    KeepScreenOnIfEnabled()

    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(vm) { vm.messages.collect { snackbar.showSnackbar(it) } }
    LaunchedEffect(vm) { vm.closed.collect { onBack() } }

    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val density = LocalDensity.current
    val listState = rememberLazyListState()
    var rawView by rememberSaveable { mutableStateOf(false) }
    var confirmStop by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    var bottomPx by remember { mutableIntStateOf(0) }
    val bottomDp = with(density) { bottomPx.toDp() }
    BackHandler(enabled = rawView) { rawView = false }

    val onRespond: (String, PermissionDecision) -> Unit = remember(vm) { { id, d -> vm.respond(id, d) } }
    val respondingIds = state.respondingIds
    val permissionSlot: @Composable (ChatItem.Permission, Modifier) -> Unit = remember(respondingIds, onRespond) {
        { p, m -> PermissionCard(p, responding = p.requestId in respondingIds, onRespond = onRespond, modifier = m) }
    }
    // Task/Agent rows that spawned a subagent link to its transcript.
    val subagentLinks: Map<String, () -> Unit> = remember(live?.subagents, agentId) {
        if (agentId != null) emptyMap()
        else live?.subagents.orEmpty().filter { it.workflowRunId == null }
            .mapNotNull { s -> s.toolUseId?.let { id -> id to { onOpenSubagent(s.agentId) } } }.toMap()
    }
    // Workflow rows list their run's agents.
    val workflowAgents: Map<String, List<WorkflowAgentLink>> = remember(live?.subagents, agentId) {
        if (agentId != null) emptyMap()
        else live?.subagents.orEmpty().filter { it.workflowRunId != null && !it.toolUseId.isNullOrEmpty() }
            .groupBy({ it.toolUseId!! }) { s ->
                WorkflowAgentLink(subagentLabel(s), s.phase, s.status == SubagentStatus.RUNNING) { onOpenSubagent(s.agentId) }
            }
    }
    val subagentTitle = remember(live?.subagents, agentId) {
        agentId?.let { id -> live?.subagents?.firstOrNull { it.agentId == id }?.let(::subagentLabel) ?: "Subagent" }
    }

    val bg = MaterialTheme.colorScheme.background
    CompositionLocalProvider(LocalPlanName provides plan) {
    Box(
        Modifier
            .fillMaxSize()
            .background(bg)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)),
    ) {
        Column(Modifier.fillMaxSize()) {
            // ── Header ──
            val scrolled by remember { derivedStateOf { listState.canScrollForward } }
            val headerLine by animateColorAsState(
                if (scrolled) TetherTheme.colors.hairline else Color.Transparent, tween(Motion.Medium), label = "sessionHeaderLine",
            )
            Column(Modifier.fillMaxWidth().background(bg).statusBarsPadding()) {
                TetherTopBar(
                    title = subagentTitle ?: state.title,
                    subtitleContent = {
                        MachineBadge(state.machineName ?: "Unknown machine", state.machineAccent)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            sessionStatusLabel(state),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (state.session?.needsYou == true && !state.heldByTerminal) TetherTheme.colors.warning else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                    },
                    onBack = onBack,
                    actions = {
                        ContextMeter(
                            tokens = conv.contextTokens,
                            window = contextWindowFor(conv.contextWindow, state.model ?: conv.model, conv.contextTokens),
                            costUsd = conv.totalCostUsd,
                            model = state.model,
                            models = conv.models,
                            rateLimit = conv.rateLimit,
                            cwd = conv.cwd,
                            plan = plan,
                        )
                        SessionOverflowMenu(
                            rawView = rawView,
                            canStop = !state.readOnly && !state.heldByTerminal && !state.retired,
                            canRemove = !state.readOnly && !state.heldByTerminal && state.session != null,
                            onCopySession = {
                                clipboard.setText(AnnotatedString(ref.sessionId))
                                if (Build.VERSION.SDK_INT < 33) scope.launch { snackbar.showSnackbar("Session id copied") }
                            },
                            onOpenMachine = { onOpenMachine(ref.connectionId) },
                            onToggleRaw = { rawView = !rawView },
                            onStop = { confirmStop = true },
                            onRemove = { confirmRemove = true },
                        )
                    },
                )
                LinkBanner(
                    link = conv.link,
                    streamError = conv.error,
                    loading = conv.loadingHistory,
                    machineName = state.machineName,
                    onRetry = vm::retry,
                )
                RateLimitBanner(conv.rateLimit)
                TodoStrip(conv.todos)
                // Subagents get their own strip (it opens their transcripts): keep them out of "Background".
                val subagents = if (agentId == null) live?.subagents.orEmpty() else emptyList()
                BackgroundTasksStrip(if (subagents.isEmpty()) conv.backgroundTasks else conv.backgroundTasks.filterNot { it.isAgent })
                SubagentStrip(
                    subagents,
                    onOpen = { onOpenSubagent(it.agentId) },
                    workflowNames = remember(live?.tasks) { workflowNames(live?.tasks.orEmpty()) },
                )
                Box(Modifier.fillMaxWidth().height(1.dp).background(headerLine))
            }

            // ── Conversation ──
            // Held by a terminal, the prompt is answered at that keyboard: nothing here asks for the user.
            // A hand-off (Claude ended its turn with a note) is answered by a normal message: no "Waiting for you".
            val waiting = state.session?.needsYou == true && state.session?.handoff != true && !state.heldByTerminal
            val showFooter = live?.status != null || conv.status == RunStatus.WORKING || waiting
            Box(Modifier.weight(1f).fillMaxWidth()) {
                val listPadding = PaddingValues(top = Space.sm, bottom = bottomDp + Space.sm)
                AnimatedContent(
                    targetState = rawView,
                    transitionSpec = { fadeIn(tween(Motion.Medium)) togetherWith fadeOut(tween(Motion.Short)) },
                    label = "sessionRawToggle",
                ) { raw ->
                    if (raw) {
                        RawItemsList(state.items, contentPadding = listPadding)
                    } else {
                        CompositionLocalProvider(LocalChatCwd provides conv.cwd, LocalSubagentLinks provides subagentLinks, LocalWorkflowAgents provides workflowAgents) {
                            ChatList(
                                items = state.items,
                                listState = listState,
                                contentPadding = listPadding,
                                showThinking = state.showThinking,
                                compactTools = state.compactTools,
                                loading = conv.loadingHistory,
                                footer = if (showFooter) {
                                    { SessionStatusFooter(live?.status, conv.workingSince, ref.sessionId.hashCode(), waiting = waiting && live?.status == null) }
                                } else null,
                                empty = if (agentId == null) { { ReadyEmpty(conv.cwd, state.machineName) } } else null,
                                permission = permissionSlot,
                            )
                        }
                    }
                }
            }
        }

        // ── Bottom dock: prompt panel + composer (or the bar that replaces it), riding the keyboard ──
        val fadePx = with(density) { 28.dp.toPx() }
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .onSizeChanged { bottomPx = it.height }
                .background(Brush.verticalGradient(listOf(bg.copy(alpha = 0f), bg), startY = 0f, endY = fadePx))
                .padding(top = Space.md)
                .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime)),
        ) {
            val interactive = !state.readOnly && !state.heldByTerminal
            if (!rawView && interactive) {
                val dialog = state.dialog
                if (dialog != null) {
                    SessionDialogPanel(dialog, busy = state.pressing, onKeys = vm::pressKeys)
                } else {
                    DecisionPanel(
                        pending = conv.pendingPermissions,
                        respondingIds = respondingIds,
                        onRespond = onRespond,
                        respondErrors = state.respondErrors,
                    )
                }
                val s = state.session
                AnimatedVisibility(s != null && s.needsYou && s.handoff && dialog == null, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                    HandoffPanel(s?.waitingFor, s?.suggestedReply, onUseReply = { vm.composer.value = TextFieldValue(it, TextRange(it.length)) })
                }
            }
            // 0 = composer, 1 = held by a terminal, 2 = read-only subagent view
            val dockMode = when {
                state.readOnly -> 2
                state.heldByTerminal -> 1
                else -> 0
            }
            AnimatedContent(
                targetState = dockMode,
                transitionSpec = {
                    (fadeIn(tween(Motion.Medium)) + slideInVertically(Motion.gentle()) { it / 3 }) togetherWith
                        (fadeOut(tween(Motion.Short)) + slideOutVertically(tween(Motion.Short)) { it / 3 }) using SizeTransform(clip = false)
                },
                label = "sessionDock",
            ) { mode ->
                when (mode) {
                    2 -> ReadOnlySubagentBar(subagentTitle ?: "Subagent", onOpenSession = onBack)
                    1 -> TerminalHeldBar(state.machineName)
                    else -> Composer(
                        state = vm.composer,
                        onSend = vm::send,
                        placeholder = when {
                            state.items.isEmpty() && !conv.loadingHistory -> "How can I help you today?"
                            state.working -> "Add a message — Claude reads it next…"
                            else -> "Reply to Claude…"
                        },
                        enabled = true,
                        sending = state.sending,
                        working = state.working,
                        onStop = vm::interrupt,
                        permissionMode = state.permissionMode,
                        onCycleMode = vm::cycleMode,
                        modeEnabled = !state.retired,
                        onSelectMode = vm::setMode,
                        model = state.model,
                        models = conv.models,
                        onSelectModel = vm::setModel,
                        commands = (conv.commands + slash).distinctBy { it.name.removePrefix("/") },
                        hint = sessionComposerHint(state),
                        onError = { msg -> scope.launch { snackbar.showSnackbar(msg) } },
                    )
                }
            }
        }

        SnackbarHost(snackbar, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = bottomDp + Space.sm))
    }
    }

    if (confirmStop) {
        ConfirmSessionDialog(
            title = "Stop this session?",
            body = "Claude stops on ${state.machineName ?: "the machine"}. The conversation stays — your next message wakes it with the same history.",
            confirm = "Stop",
            danger = false,
            onConfirm = { confirmStop = false; vm.stop() },
            onDismiss = { confirmStop = false },
        )
    }
    if (confirmRemove) {
        ConfirmSessionDialog(
            title = "Remove this session?",
            body = "It is stopped and removed from Claude Code's list on ${state.machineName ?: "the machine"}. The transcript file stays on disk.",
            confirm = "Remove",
            danger = true,
            onConfirm = { confirmRemove = false; vm.remove() },
            onDismiss = { confirmRemove = false },
        )
    }
}
