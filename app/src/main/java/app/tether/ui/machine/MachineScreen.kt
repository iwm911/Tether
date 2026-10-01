package app.tether.ui.machine

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.tether.LocalAppContainer
import app.tether.core.Connection
import app.tether.core.LinkState
import app.tether.core.ProbeResult
import app.tether.core.ProjectSummary
import app.tether.core.SessionRef
import app.tether.ui.home.SessionCard
import app.tether.ui.home.SessionFilterChips
import app.tether.ui.home.ShowOlderButton
import app.tether.ui.home.SkeletonAgentCard
import app.tether.ui.home.decisionKey
import app.tether.ui.home.inlinePermission
import app.tether.ui.home.listKey
import app.tether.ui.components.EmptyState
import app.tether.ui.components.Hairline
import app.tether.ui.components.MachineAvatar
import app.tether.ui.components.PrimaryButton
import app.tether.ui.components.SecondaryButton
import app.tether.ui.components.StatusDot
import app.tether.ui.components.TetherCard
import app.tether.ui.components.TetherTopBar
import app.tether.ui.components.prettyPath
import app.tether.ui.components.projectName
import app.tether.ui.components.relativeTime
import app.tether.ui.components.rememberHaptics
import app.tether.ui.home.AgentRunCard
import app.tether.ui.home.ErrorCard
import app.tether.ui.home.GitBranchBadge
import app.tether.ui.home.IconTile
import app.tether.ui.home.Loadable
import app.tether.ui.home.MiniBadge
import app.tether.ui.home.SkeletonBlock
import app.tether.ui.home.SkeletonListRow
import app.tether.ui.home.linkLook
import app.tether.ui.theme.Motion
import app.tether.ui.theme.Space
import app.tether.ui.theme.TetherTheme
import kotlinx.coroutines.launch

@Composable
fun MachineScreen(
    connectionId: String,
    onBack: () -> Unit,
    onOpenSession: (SessionRef) -> Unit,
    onNewAgent: (String, String?) -> Unit,
    onEdit: (String) -> Unit,
) {
    val container = LocalAppContainer.current
    val vm: MachineViewModel = viewModel(key = "machine:$connectionId") { MachineViewModel(container, connectionId) }
    val state by vm.state.collectAsStateWithLifecycle()
    val conn = state.connection

    if (conn == null) {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).windowInsetsPadding(WindowInsets.safeDrawing)) {
            TetherTopBar(title = "Machine", onBack = onBack)
            EmptyState(
                icon = Icons.Rounded.Dns,
                title = "Machine not found",
                body = "This machine may have been removed from Tether.",
                actionLabel = "Go back",
                onAction = onBack,
            )
        }
        return
    }

    val haptics = rememberHaptics()
    val snackbar = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    val pullState = rememberPullToRefreshState()

    LaunchedEffect(vm) { vm.events.collect { launch { snackbar.showSnackbar(it) } } }

    val collapsedHeader by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 } }
    val machines = remember(conn) { mapOf(conn.id to conn) }
    // "New session here": the selected project's folder when a project chip is on.
    val newHere = { haptics.tick(); onNewAgent(conn.id, state.filter.project) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
        topBar = {
            MachineTopBar(
                connection = conn,
                titleVisible = collapsedHeader,
                link = state.link,
                error = state.machineError,
                onBack = onBack,
                onEdit = { onEdit(conn.id) },
                onRefresh = { vm.refresh() },
                onDisconnect = { haptics.tick(); vm.disconnect() },
                canDisconnect = state.link is LinkState.Connected || state.link == LinkState.Connecting,
            )
        },
        snackbarHost = {
            SnackbarHost(snackbar, Modifier.navigationBarsPadding()) { data ->
                Snackbar(data, shape = RoundedCornerShape(14.dp), containerColor = MaterialTheme.colorScheme.inverseSurface, contentColor = MaterialTheme.colorScheme.inverseOnSurface)
            }
        },
        bottomBar = {
            app.tether.ui.home.StartAgentBar(
                state.filter.project?.let { "New session in ${projectName(it)}…" } ?: "New session here…",
                onClick = newHere,
            )
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = { haptics.tick(); vm.refresh() },
            state = pullState,
            modifier = Modifier.fillMaxSize().padding(top = padding.calculateTopPadding()),
            indicator = {
                PullToRefreshDefaults.Indicator(
                    state = pullState,
                    isRefreshing = state.refreshing,
                    modifier = Modifier.align(Alignment.TopCenter),
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    color = TetherTheme.colors.clay,
                )
            },
        ) {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = padding.calculateBottomPadding() + Space.lg)) {
                item(key = "header", contentType = "header") {
                    MachineHeaderCard(
                        connection = conn,
                        link = state.link,
                        error = state.machineError,
                        probe = state.probe,
                        onRetryProbe = { vm.loadProbe() },
                        onNewSession = newHere,
                        modifier = Modifier.padding(horizontal = Space.gutter, vertical = Space.sm),
                    )
                }
                state.machineError?.let { err ->
                    item(key = "error", contentType = "error") {
                        ErrorCard(
                            title = "Couldn't load sessions",
                            message = err,
                            onRetry = { vm.refresh() },
                            retrying = state.refreshing,
                            modifier = Modifier.animateItem().padding(horizontal = Space.gutter, vertical = Space.sm),
                        )
                    }
                }
                item(key = "filters", contentType = "filters") {
                    SessionFilterChips(
                        filter = state.filter,
                        machineChips = emptyList(),
                        machines = machines,
                        projects = state.projectChips,
                        onMachine = {},
                        onProject = vm::selectProject,
                        modifier = Modifier.animateItem().padding(vertical = Space.xs),
                    )
                }
                when {
                    state.loadingSessions -> items(4, key = { "ssk:$it" }, contentType = { "skeleton" }) {
                        SkeletonAgentCard(Modifier.animateItem().padding(horizontal = Space.gutter, vertical = 6.dp))
                    }
                    state.totalSessions == 0 -> item(key = "sessions:empty", contentType = "empty") {
                        InlineEmpty(
                            icon = Icons.Rounded.Forum,
                            title = "No sessions on ${conn.name}",
                            body = "Every Claude Code session on ${conn.name} — from Tether or a terminal — shows up here.",
                            actionLabel = "New session here",
                            onAction = newHere,
                            modifier = Modifier.animateItem(),
                        )
                    }
                    state.sessions.isEmpty() -> item(key = "sessions:filtered", contentType = "hint") {
                        Text(
                            "No sessions in this project yet.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TetherTheme.colors.faint,
                            modifier = Modifier.animateItem().fillMaxWidth().padding(horizontal = Space.gutter, vertical = Space.lg),
                        )
                    }
                    else -> items(state.sessions, key = { "s:" + it.listKey() }, contentType = { if (it.needsYou) "needs" else "session" }) { s ->
                        SessionCard(
                            session = s,
                            machine = conn,
                            showMachine = false,
                            decided = s.inlinePermission()?.let { state.decisions[decisionKey(s.ref, it)] },
                            onOpen = { onOpenSession(s.ref) },
                            onAllow = { haptics.confirm(); vm.respond(s, allow = true) },
                            onDeny = { haptics.tick(); vm.respond(s, allow = false) },
                            modifier = Modifier.animateItem(placementSpec = Motion.gentle()).padding(horizontal = Space.gutter, vertical = 6.dp),
                        )
                    }
                }
                if (state.canLoadOlder && !state.loadingSessions) {
                    item(key = "older", contentType = "older") {
                        ShowOlderButton(loading = state.loadingOlder, onClick = { haptics.tick(); vm.loadOlder() }, modifier = Modifier.animateItem())
                    }
                }
            }
        }
    }
}

// ───────────────────────────── Top bar ─────────────────────────────

@Composable
private fun MachineTopBar(
    connection: Connection,
    titleVisible: Boolean,
    link: LinkState?,
    error: String?,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onRefresh: () -> Unit,
    onDisconnect: () -> Unit,
    canDisconnect: Boolean,
) {
    var menu by remember { mutableStateOf(false) }
    val titleAlpha by animateFloatAsState(if (titleVisible) 1f else 0f, label = "titleAlpha")
    val look = linkLook(link, error)
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)).heightIn(min = 60.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
        Row(Modifier.weight(1f).alpha(titleAlpha), verticalAlignment = Alignment.CenterVertically) {
            MachineAvatar(connection.name, connection.accent, size = 26.dp)
            Spacer(Modifier.width(10.dp))
            Column {
                Text(connection.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(look.color, pulsing = look.pulsing, size = 5.dp)
                    Text(look.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, contentDescription = "More options") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(14.dp)) {
                DropdownMenuItem(
                    text = { Text("Edit machine") },
                    leadingIcon = { Icon(Icons.Rounded.Edit, null) },
                    onClick = { menu = false; onEdit() },
                )
                DropdownMenuItem(
                    text = { Text("Refresh") },
                    leadingIcon = { Icon(Icons.Rounded.Refresh, null) },
                    onClick = { menu = false; onRefresh() },
                )
                DropdownMenuItem(
                    text = { Text("Disconnect") },
                    leadingIcon = { Icon(Icons.Rounded.LinkOff, null) },
                    enabled = canDisconnect,
                    onClick = { menu = false; onDisconnect() },
                )
            }
        }
    }
}

// ───────────────────────────── Header card ─────────────────────────────

@Composable
private fun MachineHeaderCard(
    connection: Connection,
    link: LinkState?,
    error: String?,
    probe: Loadable<ProbeResult>,
    onRetryProbe: () -> Unit,
    onNewSession: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val look = linkLook(link, error ?: (probe as? Loadable.Failed)?.message)
    val result = probe.valueOrNull
    val probing = probe is Loadable.Loading
    TetherCard(modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp), contentPadding = PaddingValues(Space.xl)) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                MachineAvatar(connection.name, connection.accent, size = 56.dp)
                Spacer(Modifier.width(Space.lg))
                Column(Modifier.weight(1f)) {
                    Text(connection.name, style = MaterialTheme.typography.headlineMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${connection.username}@${connection.host}:${connection.port}",
                        style = TetherTheme.type.mono,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusDot(look.color, pulsing = look.pulsing || probing, size = 6.dp)
                        Text(if (probing && link !is LinkState.Connected) "Checking…" else look.label, style = MaterialTheme.typography.labelMedium, color = look.color)
                    }
                }
            }
            Spacer(Modifier.height(Space.lg))
            Hairline()
            Spacer(Modifier.height(Space.md))
            Row(Modifier.fillMaxWidth()) {
                InfoCell(
                    label = "Hostname",
                    value = result?.hostname ?: connection.lastHostname,
                    loading = probing && connection.lastHostname == null,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(Space.md))
                InfoCell(
                    label = "Claude Code",
                    value = (result?.claudeVersion ?: connection.lastClaudeVersion) ?: if (result != null) "Not found" else null,
                    loading = probing && connection.lastClaudeVersion == null,
                    modifier = Modifier.weight(1f),
                    valueColor = if (result != null && result.claudeVersion == null) TetherTheme.colors.danger else null,
                )
            }
            if (result != null && result.os.isNotBlank()) {
                Spacer(Modifier.height(Space.md))
                Row(Modifier.fillMaxWidth()) {
                    InfoCell(label = "System", value = result.os, loading = false, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(Space.md))
                    InfoCell(label = "Python", value = result.pythonVersion ?: "Not found", loading = false, modifier = Modifier.weight(1f))
                }
            }
            val problem = result?.problem
            AnimatedVisibility(visible = problem != null, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                Row(
                    Modifier.padding(top = Space.lg).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(TetherTheme.colors.warning.copy(alpha = 0.1f)).padding(Space.md),
                    verticalAlignment = Alignment.Top,
                ) {
                    Icon(Icons.Rounded.Warning, null, tint = TetherTheme.colors.warning, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(problem ?: "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
                }
            }
            AnimatedVisibility(visible = probe is Loadable.Failed, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                Row(
                    Modifier.padding(top = Space.md).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(TetherTheme.colors.danger.copy(alpha = 0.08f)).padding(start = Space.md, top = 4.dp, bottom = 4.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        (probe as? Loadable.Failed)?.message ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onRetryProbe) { Text("Retry", color = TetherTheme.colors.danger) }
                }
            }
            Spacer(Modifier.height(Space.lg))
            SecondaryButton(
                "New session here",
                onClick = onNewSession,
                icon = Icons.Rounded.Add,
                contentColor = TetherTheme.colors.clay,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun InfoCell(label: String, value: String?, loading: Boolean, modifier: Modifier = Modifier, valueColor: Color? = null) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = TetherTheme.colors.faint)
        Spacer(Modifier.height(4.dp))
        if (loading) {
            SkeletonBlock(Modifier.fillMaxWidth(0.8f).height(14.dp))
        } else {
            Text(
                value ?: "—",
                style = TetherTheme.type.mono,
                color = valueColor ?: MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ───────────────────────────── Empty ─────────────────────────────

@Composable
private fun InlineEmpty(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = Space.xxl, vertical = Space.xxxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        IconTile(icon, TetherTheme.colors.clay, size = 52.dp)
        Spacer(Modifier.height(Space.lg))
        Text(title, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onBackground)
        Spacer(Modifier.height(Space.sm))
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(Space.xl))
            PrimaryButton(actionLabel, onClick = onAction, icon = Icons.Rounded.Add)
        }
    }
}
