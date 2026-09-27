package app.tether.ui.chat

import androidx.compose.foundation.shape.CircleShape

import androidx.compose.foundation.clickable

import androidx.compose.foundation.layout.fillMaxHeight

import androidx.compose.material.icons.outlined.Info

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.CallSplit
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.StopCircle
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.tether.LocalAppContainer
import app.tether.core.ChatItem
import app.tether.core.LinkState
import app.tether.core.ModelOption
import app.tether.core.PermissionDecision
import app.tether.core.RateLimitInfo
import app.tether.core.RunRef
import app.tether.core.RunStatus
import app.tether.ui.components.MachineAvatar
import app.tether.ui.components.PrimaryButton
import app.tether.ui.components.StatusDot
import app.tether.ui.components.TetherTopBar
import app.tether.ui.components.WorkingIndicator
import app.tether.ui.components.backgroundLabel
import app.tether.ui.components.compactNumber
import app.tether.ui.components.formatCost
import app.tether.ui.components.label
import app.tether.ui.components.prettyPath
import app.tether.ui.components.rememberHaptics
import app.tether.ui.theme.Motion
import app.tether.ui.theme.Space
import app.tether.ui.theme.TetherTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

private const val DefaultContextWindow = 200_000L
private const val LongContextWindow = 1_000_000L

/** The CLI-reported window; until a turn reports it, guess from the model id and the tokens already in use. */
private fun contextWindowFor(reported: Long?, model: String?, tokens: Long?): Long = when {
    reported != null && reported > 0 -> reported
    model?.lowercase()?.contains("[1m]") == true -> LongContextWindow
    (tokens ?: 0L) > DefaultContextWindow -> LongContextWindow
    else -> DefaultContextWindow
}

/** Rate-limit warnings the user closed, keyed by kind + window + reset, so a new window or a hard limit shows again. */
private val dismissedRateBanners = mutableStateListOf<String>()

/** Holds the screen awake while the calling screen is shown, if the user turned that on in Settings. */
@Composable
internal fun KeepScreenOnIfEnabled() {
    val settings by LocalAppContainer.current.settings.settings.collectAsStateWithLifecycle()
    if (!settings.keepScreenOn) return
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
}

/** The heart of the app: one live Claude Code agent as a conversation. */
@Composable
fun AgentScreen(ref: RunRef, onBack: () -> Unit, onOpenMachine: (String) -> Unit, onOpenAgent: (RunRef) -> Unit = {}) {
    val container = LocalAppContainer.current
    val vm: ChatViewModel = viewModel(key = "chat/${ref.connectionId}/${ref.runId}") { ChatViewModel(container, ref) }
    val state by vm.state.collectAsStateWithLifecycle()
    val conv = state.conversation
    val machines by app.tether.LocalAppContainer.current.connections.connections.collectAsStateWithLifecycle()
    val plan = conv.planName ?: machines.firstOrNull { it.id == state.ref.connectionId }?.lastPlan

    KeepScreenOnIfEnabled()

    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(vm) { vm.messages.collect { snackbar.showSnackbar(it) } }
    LaunchedEffect(vm) { vm.closed.collect { onBack() } }
    LaunchedEffect(vm) { vm.branched.collect { onOpenAgent(it) } }
    val branching by vm.branching.collectAsStateWithLifecycle()
    val nativeCommands by vm.nativeCommands.collectAsStateWithLifecycle()
    var sheetFor by remember { mutableStateOf<ChatItem.User?>(null) }
    var editFor by remember { mutableStateOf<Pair<ChatItem.User, Boolean>?>(null) }
    val messageActions = remember(vm) {
        MessageActions(
            onUserMessage = { sheetFor = it },
            onBranchAfter = { reply -> vm.branch(fromSession = true, atUuid = reply.uuid, prompt = null) },
            onRetryTurn = { user -> editFor = user to true },
        )
    }
    sheetFor?.let { m ->
        UserMessageSheet(m, onDismiss = { sheetFor = null }, onEdit = { editFor = m to false }, onRetry = { editFor = m to true })
    }
    editFor?.let { (m, retry) ->
        EditBranchSheet(
            message = m,
            retry = retry,
            onDismiss = { editFor = null },
            previewRewind = { id -> vm.previewRewind(id) },
            branching = branching,
            onBranch = { text, restore ->
                vm.branch(fromSession = m.forkPointUuid != null, atUuid = m.forkPointUuid, prompt = text, restoreBefore = if (restore) m.uuid else null)
            },
        )
    }
    LaunchedEffect(branching) { if (!branching && editFor != null) editFor = null }
    var confirmRemove by remember { mutableStateOf(false) }
    var showTimeline by remember { mutableStateOf(false) }
    var showLogs by remember { mutableStateOf(false) }
    val native = state.isNative

    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val density = LocalDensity.current
    val listState = rememberLazyListState()
    var rawView by rememberSaveable { mutableStateOf(false) }
    var confirmStop by remember { mutableStateOf(false) }
    var bottomPx by remember { mutableIntStateOf(0) }
    val bottomDp = with(density) { bottomPx.toDp() }
    BackHandler(enabled = rawView) { rawView = false }

    val onRespond: (String, PermissionDecision) -> Unit = remember(vm) { { id, d -> vm.respond(id, d) } }
    val respondingIds = state.respondingIds
    val permissionSlot: @Composable (ChatItem.Permission, Modifier) -> Unit = remember(respondingIds, onRespond) {
        { p, m -> PermissionCard(p, responding = p.requestId in respondingIds, onRespond = onRespond, modifier = m) }
    }

    val linkDown = conv.link is LinkState.Failed || state.streamError != null
    val statusLabel = when {
        linkDown -> "Offline"
        native && conv.nativeRun != null -> app.tether.ui.home.nativeStateLabel(conv.nativeRun!!)
        else -> conv.status.label
    }

    val bg = MaterialTheme.colorScheme.background
    CompositionLocalProvider(app.tether.ui.components.LocalPlanName provides plan) {
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
                if (scrolled) TetherTheme.colors.hairline else Color.Transparent, tween(Motion.Medium), label = "headerLine",
            )
            Column(Modifier.fillMaxWidth().background(bg).statusBarsPadding()) {
                TetherTopBar(
                    title = state.title,
                    subtitleContent = {
                        app.tether.ui.components.MachineBadge(state.machineName ?: "Unknown machine", state.machineAccent)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            statusLabel,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                        if (native) NativeOverflowMenu(
                            run = conv.nativeRun,
                            hasSession = conv.sessionId != null,
                            onCopySession = {
                                conv.sessionId?.let { sid ->
                                    clipboard.setText(AnnotatedString(sid))
                                    if (Build.VERSION.SDK_INT < 33) scope.launch { snackbar.showSnackbar("Session id copied") }
                                }
                            },
                            onOpenMachine = { onOpenMachine(state.ref.connectionId) },
                            onTimeline = { showTimeline = true },
                            onLogs = { showLogs = true },
                            onStop = { confirmStop = true },
                            onRemove = { confirmRemove = true },
                            onFork = { vm.branch(fromSession = true, atUuid = null, prompt = null) },
                        ) else OverflowMenu(
                            hasSession = conv.sessionId != null,
                            rawView = rawView,
                            canStop = !state.ended,
                            onCopySession = {
                                conv.sessionId?.let { sid ->
                                    clipboard.setText(AnnotatedString(sid))
                                    if (Build.VERSION.SDK_INT < 33) scope.launch { snackbar.showSnackbar("Session id copied") }
                                }
                            },
                            onOpenMachine = { onOpenMachine(state.ref.connectionId) },
                            onToggleRaw = { rawView = !rawView },
                            onStop = { confirmStop = true },
                            onFork = { vm.branch(fromSession = true, atUuid = null, prompt = null) },
                        )
                    },
                )
                LinkBanner(
                    link = conv.link,
                    streamError = state.streamError,
                    loading = conv.loadingHistory,
                    machineName = state.machineName,
                    onRetry = vm::retry,
                )
                RateLimitBanner(conv.rateLimit)
                if (native) NativeHeaderCard(conv.nativeRun, onOpenTimeline = { showTimeline = true })
                TodoStrip(conv.todos)
                if (!native) BackgroundTasksStrip(conv.backgroundTasks.takeIf { !state.ended }.orEmpty())
                Box(Modifier.fillMaxWidth().height(1.dp).background(headerLine))
            }

            // ── Conversation ──
            val backgroundOnly = conv.status == RunStatus.IDLE && conv.backgroundTasks.isNotEmpty()
            val showFooter = conv.status == RunStatus.WORKING || conv.status == RunStatus.STARTING || conv.status == RunStatus.AWAITING_PERMISSION || backgroundOnly
            Box(Modifier.weight(1f).fillMaxWidth()) {
                val listPadding = PaddingValues(top = Space.sm, bottom = bottomDp + Space.sm)
                AnimatedContent(
                    targetState = rawView,
                    transitionSpec = { fadeIn(tween(Motion.Medium)) togetherWith fadeOut(tween(Motion.Short)) },
                    label = "rawToggle",
                ) { raw ->
                    if (raw) {
                        RawItemsList(conv.items, contentPadding = listPadding)
                    } else {
                        androidx.compose.runtime.CompositionLocalProvider(app.tether.ui.chat.render.LocalChatCwd provides conv.cwd) {
                        ChatList(
                            items = state.items,
                            listState = listState,
                            contentPadding = listPadding,
                            actions = messageActions,
                            showThinking = state.showThinking,
                            compactTools = state.compactTools,
                            loading = conv.loadingHistory,
                            error = conv.error?.takeIf { !state.ended },
                            errorTitle = if (conv.status == app.tether.core.RunStatus.FAILED) "Claude hit a problem" else "Live updates interrupted",
                            onRetry = vm::retry,
                            // A native agent's live activity is already in its header card.
                            footer = if (showFooter && !native) {
                                { ListFooter(conv.status, conv.workingSince, conv.thinkingTokens, state.ref.runId.hashCode(), conv.backgroundTasks.size) }
                            } else null,
                            empty = if (!state.ended && !native) { { ReadyEmpty(conv.cwd, state.machineName) } } else null,
                            permission = permissionSlot,
                        )
                        }
                    }
                }
            }
        }

        // ── Bottom dock: decision panel + composer, riding the keyboard ──
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
            if (!rawView) {
                DecisionPanel(
                    pending = conv.pendingPermissions,
                    respondingIds = respondingIds,
                    onRespond = onRespond,
                    onFetchQuestion = if (native) ({ vm.fetchQuestion() }) else null,
                )
            }
            // 0 = live composer, 1 = ended, 3 = native composer (always available; queues while busy)
            val dockMode = when {
                native -> 3
                state.ended -> 1
                else -> 0
            }
            AnimatedContent(
                targetState = dockMode,
                transitionSpec = {
                    (fadeIn(tween(Motion.Medium)) + slideInVertically(Motion.gentle()) { it / 3 }) togetherWith
                        (fadeOut(tween(Motion.Short)) + slideOutVertically(tween(Motion.Short)) { it / 3 }) using SizeTransform(clip = false)
                },
                label = "composerOrEnded",
            ) { mode ->
                if (mode == 3) {
                    val run = conv.nativeRun
                    val nativeWorking = conv.status == RunStatus.WORKING || conv.status == RunStatus.STARTING
                    val alive = run?.alive == true
                    Composer(
                        state = vm.composer,
                        onSend = vm::send,
                        placeholder = if (nativeWorking) "Add a message — Claude reads it next…" else "Reply to Claude…",
                        enabled = !state.replying && !conv.loadingHistory && conv.status != RunStatus.AWAITING_PERMISSION,
                        sending = state.replying,
                        working = nativeWorking && alive,
                        onStop = vm::interrupt,
                        hint = when {
                            conv.status == RunStatus.AWAITING_PERMISSION -> "Answer Claude's request above first"
                            nativeWorking && alive -> "Messages queue while Claude works · ■ interrupts"
                            !alive -> "Resumes this background agent"
                            else -> "Sends to this background agent"
                        },
                        commands = nativeCommands,
                        allowAttachments = false,
                        onError = { msg -> scope.launch { snackbar.showSnackbar(msg) } },
                    )
                } else if (mode == 1) {
                    EndedCard(
                        failed = conv.status == RunStatus.FAILED,
                        error = conv.error,
                        canResume = conv.sessionId != null,
                        resuming = state.resuming,
                        onResume = vm::resume,
                    )
                } else {
                    val starting = conv.status == RunStatus.STARTING && conv.loadingHistory
                    Composer(
                        state = vm.composer,
                        onSend = vm::send,
                        placeholder = when { starting -> "Starting Claude…"; state.items.isEmpty() -> "How can I help you today?"; else -> "Reply to Claude…" },
                        enabled = !starting && !state.resuming,
                        working = conv.status == RunStatus.WORKING,
                        onStop = vm::interrupt,
                        permissionMode = state.permissionMode,
                        onCycleMode = vm::cycleMode,
                        onSelectMode = vm::setMode,
                        model = state.model,
                        models = conv.models,
                        onSelectModel = vm::setModel,
                        commands = conv.commands,
                        hint = conv.queuedCount.takeIf { it > 0 }?.let { n ->
                            if (n == 1) "1 message queued · Claude reads it next" else "$n messages queued · Claude reads them next"
                        },
                        onError = { msg -> scope.launch { snackbar.showSnackbar(msg) } },
                    )
                }
            }
        }

        SnackbarHost(
            snackbar,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = bottomDp + Space.sm),
        )
    }
    }

    if (confirmStop && native) {
        StopNativeDialog(onConfirm = { confirmStop = false; vm.stop() }, onDismiss = { confirmStop = false })
    } else if (confirmStop) {
        StopAgentDialog(
            machineName = state.machineName,
            onConfirm = { confirmStop = false; vm.stop() },
            onDismiss = { confirmStop = false },
        )
    }
    if (confirmRemove) {
        RemoveNativeDialog(onConfirm = { confirmRemove = false; vm.remove() }, onDismiss = { confirmRemove = false })
    }
    if (showTimeline) {
        NativeTimelineSheet(key = state.ref, load = vm::nativeTimeline, onDismiss = { showTimeline = false })
    }
    if (showLogs) {
        NativeLogsSheet(key = state.ref, load = vm::nativeLogs, onDismiss = { showLogs = false })
    }
}

// ───────────────────────────── Top bar pieces ─────────────────────────────

@Composable
private fun ContextMeter(
    tokens: Long?,
    window: Long,
    costUsd: Double,
    model: String?,
    models: List<ModelOption>,
    rateLimit: RateLimitInfo?,
    cwd: String?,
    plan: String? = null,
) {
    var open by remember { mutableStateOf(false) }
    val haptics = rememberHaptics()
    val fraction = ((tokens ?: 0L).toFloat() / window).coerceIn(0f, 1f)
    val animated by animateFloatAsState(fraction, Motion.gentle(), label = "ctx")
    val c = TetherTheme.colors
    val arc by animateColorAsState(
        when {
            fraction >= 0.9f -> c.danger
            fraction >= 0.75f -> c.warning
            else -> c.clay
        },
        label = "ctxColor",
    )
    val pct = (fraction * 100).roundToInt()
    Box {
        Row(
            Modifier
                .padding(end = 2.dp)
                .clip(CircleShape)
                .clickable(role = androidx.compose.ui.semantics.Role.Button) { haptics.tick(); open = true }
                .semantics { contentDescription = if (tokens != null) "Context $pct percent used" else "Session info" }
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (tokens != null) {
                // A tiny linear meter + the number: reads as data, never as a spinner.
                Box(Modifier.width(22.dp).height(4.dp).clip(CircleShape).background(c.hairline)) {
                    Box(Modifier.fillMaxWidth(animated.coerceAtLeast(0.04f)).fillMaxHeight().clip(CircleShape).background(arc))
                }
                Spacer(Modifier.width(7.dp))
                Text("$pct%", style = MaterialTheme.typography.labelMedium, color = c.faint)
            } else {
                Icon(Icons.Outlined.Info, contentDescription = null, tint = c.faint, modifier = Modifier.size(20.dp))
            }
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            shape = RoundedCornerShape(18.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            border = BorderStroke(1.dp, TetherTheme.colors.hairline),
        ) {
            Column(Modifier.widthIn(min = 240.dp, max = 320.dp).padding(horizontal = Space.lg, vertical = Space.sm)) {
                Text("Session", style = MaterialTheme.typography.labelMedium, color = TetherTheme.colors.faint)
                Spacer(Modifier.height(Space.sm))
                InfoLine(
                    "Context",
                    if (tokens != null) "${compactNumber(tokens)} / ${compactNumber(window)} · $pct%" else "Not measured yet",
                )
                InfoLine("Model", modelLabel(model, models))
                if (plan != null) {
                    InfoLine("Plan", plan)
                    val windows = rateLimit?.windows.orEmpty().ifEmpty {
                        rateLimit?.utilization?.let { listOf(app.tether.core.UsageWindow(rateLimit.windowLabel ?: "Usage", it, rateLimit.resetsAt)) }.orEmpty()
                    }
                    for (w in windows) UsageLine(w)
                    if (windows.isEmpty()) InfoLine("Usage", "Shown after Claude's next reply")
                    if (costUsd > 0) {
                        Text(
                            "≈ ${formatCost(costUsd)} at API prices — included in your plan, not billed",
                            style = MaterialTheme.typography.labelSmall,
                            color = TetherTheme.colors.faint,
                            modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
                        )
                    }
                } else {
                    InfoLine("Spent", formatCost(costUsd) + " (API)")
                    rateLimit?.utilization?.let { u ->
                        val window = rateLimit.windowLabel?.replace('_', ' ') ?: "usage window"
                        InfoLine("Usage", "${(u * 100).roundToInt().coerceIn(0, 100)}% of $window" + (formatReset(rateLimit.resetsAt)?.let { " · resets $it" } ?: ""))
                    }
                }
                cwd?.let { InfoLine("Folder", prettyPath(it, maxLen = 36), mono = true) }
            }
        }
    }
}

/** One plan-usage window: "5-hour limit  ▓▓▓░░  45% · resets 14:00". */
@Composable
private fun UsageLine(w: app.tether.core.UsageWindow) {
    val pct = (w.utilization * 100).roundToInt().coerceIn(0, 100)
    val c = TetherTheme.colors
    val color = when {
        pct >= 90 -> c.danger
        pct >= 75 -> c.warning
        else -> c.clay
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${w.label} limit", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            Text(
                "$pct%" + (formatReset(w.resetsAt)?.let { " · resets $it" } ?: ""),
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Spacer(Modifier.height(5.dp))
        Box(Modifier.fillMaxWidth().height(5.dp).clip(CircleShape).background(c.hairline)) {
            Box(Modifier.fillMaxWidth((pct / 100f).coerceAtLeast(0.02f)).fillMaxHeight().clip(CircleShape).background(color))
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String, mono: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(72.dp))
        Text(
            value,
            style = if (mono) TetherTheme.type.monoSmall else MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun OverflowMenu(
    hasSession: Boolean,
    rawView: Boolean,
    canStop: Boolean,
    onCopySession: () -> Unit,
    onOpenMachine: () -> Unit,
    onToggleRaw: () -> Unit,
    onStop: () -> Unit,
    onFork: () -> Unit = {},
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Rounded.MoreVert, contentDescription = "More options") }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            shape = RoundedCornerShape(18.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            border = BorderStroke(1.dp, TetherTheme.colors.hairline),
        ) {
            DropdownMenuItem(
                text = { Text("Copy session id") },
                leadingIcon = { Icon(Icons.Rounded.ContentCopy, contentDescription = null) },
                enabled = hasSession,
                onClick = { open = false; onCopySession() },
            )
            DropdownMenuItem(
                text = { Text("Fork conversation") },
                leadingIcon = { Icon(Icons.AutoMirrored.Rounded.CallSplit, contentDescription = null) },
                enabled = hasSession,
                onClick = { open = false; onFork() },
            )
            DropdownMenuItem(
                text = { Text("Open machine") },
                leadingIcon = { Icon(Icons.Rounded.Computer, contentDescription = null) },
                onClick = { open = false; onOpenMachine() },
            )
            DropdownMenuItem(
                text = { Text(if (rawView) "Hide raw events" else "Show raw events") },
                leadingIcon = { Icon(Icons.Rounded.Code, contentDescription = null) },
                onClick = { open = false; onToggleRaw() },
            )
            if (canStop) {
                HorizontalDivider(color = TetherTheme.colors.hairline, modifier = Modifier.padding(vertical = 4.dp))
                DropdownMenuItem(
                    text = { Text("Stop agent", color = TetherTheme.colors.danger) },
                    leadingIcon = { Icon(Icons.Rounded.StopCircle, contentDescription = null, tint = TetherTheme.colors.danger) },
                    onClick = { open = false; onStop() },
                )
            }
        }
    }
}

// ───────────────────────────── Banners ─────────────────────────────

private sealed interface BannerKind {
    data class Connecting(val first: Boolean) : BannerKind
    data class Failed(val message: String) : BannerKind
}

@Composable
private fun LinkBanner(link: LinkState, streamError: String?, loading: Boolean, machineName: String?, onRetry: () -> Unit) {
    val kind: BannerKind? = when {
        streamError != null -> BannerKind.Failed(streamError)
        link is LinkState.Failed -> BannerKind.Failed(link.message)
        link is LinkState.Connecting -> BannerKind.Connecting(first = loading)
        link is LinkState.Idle && loading -> BannerKind.Connecting(first = true)
        else -> null
    }
    // Debounce "connecting" so a fast re-attach never flashes a banner.
    var shown by remember { mutableStateOf<BannerKind?>(null) }
    LaunchedEffect(kind) {
        if (kind is BannerKind.Connecting && shown == null) delay(700)
        shown = kind
    }
    AnimatedContent(
        targetState = shown,
        contentKey = { it?.let { k -> k::class } },
        transitionSpec = {
            (fadeIn(tween(Motion.Medium)) + expandVertically(Motion.gentle())) togetherWith
                (fadeOut(tween(Motion.Short)) + shrinkVertically(tween(Motion.Medium))) using SizeTransform(clip = true)
        },
        label = "linkBanner",
    ) { k ->
        when (k) {
            null -> Spacer(Modifier.fillMaxWidth())
            is BannerKind.Connecting -> BannerRow(
                tint = TetherTheme.colors.info,
                leading = { CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 1.8.dp, color = TetherTheme.colors.info) },
                title = if (k.first) "Connecting to ${machineName ?: "machine"}…" else "Reconnecting…",
                detail = if (k.first) null else "Claude keeps working on the machine meanwhile",
            )
            is BannerKind.Failed -> BannerRow(
                tint = TetherTheme.colors.danger,
                leading = { Icon(Icons.Rounded.WifiOff, contentDescription = null, tint = TetherTheme.colors.danger, modifier = Modifier.size(16.dp)) },
                title = "Can't reach ${machineName ?: "the machine"}",
                detail = k.message,
                action = "Retry",
                onAction = onRetry,
            )
        }
    }
}

@Composable
private fun RateLimitBanner(info: RateLimitInfo?) {
    val status = info?.status?.lowercase()
    val kind = when {
        status == null -> null
        status.contains("reject") || status.contains("exceed") -> "limit"
        status.contains("warn") -> "warn"
        else -> null
    }
    val dismissKey = kind?.let { "$it|${info?.windowLabel}|${info?.resetsAt}" }
    AnimatedContent(
        targetState = kind?.takeIf { dismissKey !in dismissedRateBanners },
        transitionSpec = {
            (fadeIn(tween(Motion.Medium)) + expandVertically(Motion.gentle())) togetherWith
                (fadeOut(tween(Motion.Short)) + shrinkVertically(tween(Motion.Medium))) using SizeTransform(clip = true)
        },
        label = "rateBanner",
    ) { k ->
        if (k == null || info == null) {
            Spacer(Modifier.fillMaxWidth())
        } else {
            val reset = formatReset(info.resetsAt)
            val pct = info.utilization?.let { "${(it * 100).roundToInt().coerceIn(0, 100)}% used" }
            BannerRow(
                tint = if (k == "limit") TetherTheme.colors.danger else TetherTheme.colors.warning,
                leading = {
                    Icon(
                        Icons.Rounded.WarningAmber, contentDescription = null,
                        tint = if (k == "limit") TetherTheme.colors.danger else TetherTheme.colors.warning,
                        modifier = Modifier.size(16.dp),
                    )
                },
                title = if (k == "limit") "Usage limit reached" else "Approaching your usage limit",
                detail = listOfNotNull(pct, reset?.let { "resets $it" }).joinToString(" · ").ifEmpty { null },
                onClose = { dismissKey?.let(dismissedRateBanners::add) },
            )
        }
    }
}

/** "today 14:00", "tomorrow 09:00", or "Tue, Oct 6, 14:00" — weekly windows reset days away. */
private fun formatReset(resetsAt: Long?): String? {
    if (resetsAt == null || resetsAt <= 0) return null
    val ms = if (resetsAt < 100_000_000_000L) resetsAt * 1000 else resetsAt
    val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(ms))
    val days = ChronoUnit.DAYS.between(LocalDate.now(), Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate())
    return when (days) {
        0L -> "today $time"
        1L -> "tomorrow $time"
        else -> {
            val locale = Locale.getDefault()
            SimpleDateFormat(android.text.format.DateFormat.getBestDateTimePattern(locale, "EEEMMMdjmm"), locale).format(Date(ms))
        }
    }
}

@Composable
private fun BannerRow(
    tint: Color,
    leading: @Composable () -> Unit,
    title: String,
    detail: String? = null,
    action: String? = null,
    onAction: (() -> Unit)? = null,
    onClose: (() -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = Space.xs)
            .clip(RoundedCornerShape(14.dp))
            .background(tint.copy(alpha = 0.11f))
            .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading()
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (detail != null) {
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        if (action != null && onAction != null) {
            TextButton(onClick = onAction) { Text(action, color = tint, style = MaterialTheme.typography.labelLarge) }
        } else if (onClose != null) {
            IconButton(onClick = onClose, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Rounded.Close, contentDescription = "Dismiss", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
            }
        } else Spacer(Modifier.width(8.dp))
    }
}

// ───────────────────────────── List states & dock ─────────────────────────────

@Composable
private fun ListFooter(status: RunStatus, since: Long?, tokens: Int?, seed: Int, background: Int) {
    AnimatedContent(
        targetState = status == RunStatus.AWAITING_PERMISSION,
        transitionSpec = { fadeIn(tween(Motion.Medium)) togetherWith fadeOut(tween(Motion.Short)) },
        label = "footer",
    ) { waiting ->
        if (waiting) {
            Spacer(Modifier.fillMaxWidth())
        } else {
            WorkingIndicator(
                since = since,
                tokens = tokens,
                verbSeed = seed,
                label = when {
                    status == RunStatus.STARTING -> "Starting Claude"
                    status == RunStatus.IDLE -> backgroundLabel(background)
                    else -> null
                },
            )
        }
    }
}

@Composable
private fun WaitingForYouLine() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        StatusDot(TetherTheme.colors.warning, pulsing = true, size = 7.dp)
        Spacer(Modifier.width(4.dp))
        Text("Waiting for your decision", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium), color = TetherTheme.colors.warning)
    }
}

@Composable
private fun ReadyEmpty(cwd: String?, machineName: String?) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = Space.xxl, vertical = 56.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        Text("✻", style = MaterialTheme.typography.displaySmall, color = TetherTheme.colors.clay)
        Text("Ready when you are", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onBackground)
        Text(
            buildString {
                append("Claude is waiting")
                if (cwd != null) append(" in ${prettyPath(cwd, maxLen = 32)}")
                if (machineName != null) append(" on $machineName")
                append(". Type / for commands.")
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

@Composable
private fun EndedCard(failed: Boolean, error: String?, canResume: Boolean, resuming: Boolean, onResume: () -> Unit) {
    val tint = if (failed) TetherTheme.colors.danger else TetherTheme.colors.faint
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = TetherTheme.colors.composer,
        border = BorderStroke(1.dp, if (failed) tint.copy(alpha = 0.4f) else TetherTheme.colors.composerBorder),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(top = 6.dp, bottom = 8.dp),
    ) {
        Row(Modifier.padding(start = Space.lg, end = Space.md, top = Space.md, bottom = Space.md), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(tint.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.History, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp)) }
            Spacer(Modifier.width(Space.md))
            Column(Modifier.weight(1f)) {
                Text(
                    if (failed) "This agent stopped with an error" else "This agent has ended",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    when {
                        failed && !error.isNullOrBlank() -> error
                        canResume -> "Resume to pick up with the full context."
                        else -> "There's no session to resume."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (canResume) {
                Spacer(Modifier.width(Space.sm))
                PrimaryButton("Resume", onClick = onResume, loading = resuming, icon = Icons.Rounded.PlayArrow)
            }
        }
    }
}

@Composable
private fun StopAgentDialog(machineName: String?, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        icon = { Icon(Icons.Rounded.StopCircle, contentDescription = null, tint = TetherTheme.colors.danger) },
        title = { Text("Stop this agent?", style = MaterialTheme.typography.headlineSmall) },
        text = {
            Text(
                "Claude will be shut down on ${machineName ?: "the machine"}. The conversation is saved, so you can resume it later.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Stop agent", color = TetherTheme.colors.danger, style = MaterialTheme.typography.labelLarge) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", style = MaterialTheme.typography.labelLarge) }
        },
    )
}
