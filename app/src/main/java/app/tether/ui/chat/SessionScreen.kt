package app.tether.ui.chat

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.History
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.tether.LocalAppContainer
import app.tether.core.ChatItem
import app.tether.core.RunRef
import app.tether.ui.components.EmptyState
import app.tether.ui.components.MachineAvatar
import app.tether.ui.components.TetherTopBar
import app.tether.ui.components.prettyPath
import app.tether.ui.theme.Motion
import app.tether.ui.theme.Space
import app.tether.ui.theme.TetherTheme
import kotlinx.coroutines.launch

/** A past Claude Code session, read-only, with a composer to continue it as a new live agent. */
@Composable
fun SessionScreen(connectionId: String, sessionId: String, onBack: () -> Unit, onContinue: (RunRef) -> Unit) {
    val container = LocalAppContainer.current
    val vm: SessionViewModel = viewModel(key = "session/$connectionId/$sessionId") { SessionViewModel(container, connectionId, sessionId) }
    val state by vm.state.collectAsStateWithLifecycle()
    KeepScreenOnIfEnabled()
    val machines by app.tether.LocalAppContainer.current.connections.connections.collectAsStateWithLifecycle()
    val plan = machines.firstOrNull { it.id == connectionId }?.lastPlan
    val branching by vm.branching.collectAsStateWithLifecycle()
    var sheetFor by remember { mutableStateOf<ChatItem.User?>(null) }
    var editFor by remember { mutableStateOf<Pair<ChatItem.User, Boolean>?>(null) }
    val sessionActions = remember(vm) {
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
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val listState = rememberLazyListState()
    var bottomPx by remember { mutableIntStateOf(0) }
    val bottomDp = with(density) { bottomPx.toDp() }

    LaunchedEffect(vm) { vm.messages.collect { snackbar.showSnackbar(it) } }
    LaunchedEffect(vm) { vm.continued.collect { onContinue(it) } }

    val conv = state.conversation
    val pathLabel = conv?.cwd?.let { prettyPath(it, maxLen = 40) }
    val bg = MaterialTheme.colorScheme.background

    CompositionLocalProvider(app.tether.ui.components.LocalPlanName provides plan) {
    Box(
        Modifier
            .fillMaxSize()
            .background(bg)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)),
    ) {
        Column(Modifier.fillMaxSize()) {
            val scrolled by remember { derivedStateOf { listState.canScrollBackward } }
            val headerLine by animateColorAsState(
                if (scrolled) TetherTheme.colors.hairline else Color.Transparent, tween(Motion.Medium), label = "sessionHeaderLine",
            )
            Column(Modifier.fillMaxWidth().background(bg).statusBarsPadding()) {
                TetherTopBar(
                    title = if (state.loading && conv == null) "Loading…" else state.title,
                    subtitleContent = {
                        if (pathLabel != null) {
                            androidx.compose.material3.Text(
                                pathLabel,
                                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                                color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            androidx.compose.foundation.layout.Spacer(Modifier.width(8.dp))
                        }
                        state.machineName?.let { app.tether.ui.components.MachineBadge(it, state.machineAccent) }
                    },
                    onBack = onBack,
                )
                Box(Modifier.fillMaxWidth().height(1.dp).background(headerLine))
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
              androidx.compose.runtime.CompositionLocalProvider(app.tether.ui.chat.render.LocalChatCwd provides state.conversation?.cwd) {
                ChatList(
                    items = state.items,
                    listState = listState,
                    actions = sessionActions,
                    contentPadding = PaddingValues(top = Space.sm, bottom = bottomDp + Space.sm),
                    showThinking = state.showThinking,
                    compactTools = state.compactTools,
                    loading = state.loading,
                    error = state.error,
                    errorTitle = "Couldn't load this conversation",
                    onRetry = vm::load,
                    header = if (!state.loading && state.error == null && state.items.isNotEmpty()) {
                        { HistoryHeader(count = state.items.count { it is ChatItem.User }) }
                    } else null,
                    empty = {
                        EmptyState(
                            icon = Icons.Rounded.ChatBubbleOutline,
                            title = "Nothing to show",
                            body = "This session has no messages Tether can display. You can still continue it below.",
                        )
                    },
                )
              }
            }
        }

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
            Composer(
                state = vm.composer,
                onSend = vm::continueConversation,
                placeholder = if (conv == null) "Loading conversation…" else "Continue this conversation…",
                enabled = conv != null,
                sending = state.continuing,
                onError = { msg -> scope.launch { snackbar.showSnackbar(msg) } },
            )
        }

        SnackbarHost(snackbar, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = bottomDp + Space.sm))
    }
    }
}

@Composable
private fun HistoryHeader(count: Int) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Space.gutter, vertical = Space.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(start = 8.dp, end = 12.dp, top = 5.dp, bottom = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.History, contentDescription = null, tint = TetherTheme.colors.faint, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(6.dp))
            Text("Read-only history", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(Space.sm))
        if (count > 0) {
            Text(
                if (count == 1) "1 prompt" else "$count prompts",
                style = MaterialTheme.typography.labelMedium,
                color = TetherTheme.colors.faint,
            )
        }
    }
}
