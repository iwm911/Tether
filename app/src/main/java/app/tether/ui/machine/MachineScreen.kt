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
import app.tether.core.RunRef
import app.tether.core.SessionSummary
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

private enum class MachineTab(val label: String) { AGENTS("Agents"), PROJECTS("Projects"), SESSIONS("Sessions") }

@Composable
fun MachineScreen(
    connectionId: String,
    onBack: () -> Unit,
    onOpenAgent: (RunRef) -> Unit,
    onOpenSession: (String, String) -> Unit,
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
    var tabIndex by rememberSaveable { mutableIntStateOf(-1) }
    val tab = when {
        tabIndex >= 0 -> MachineTab.entries[tabIndex]
        state.agents.isNotEmpty() -> MachineTab.AGENTS
        else -> MachineTab.PROJECTS
    }
    var expandedProjects by remember { mutableStateOf(setOf<String>()) }

    LaunchedEffect(vm) { vm.events.collect { launch { snackbar.showSnackbar(it) } } }

    val collapsedHeader by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 } }

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
            app.tether.ui.home.StartAgentBar("Start an agent on ${conn.name}…", onClick = { haptics.tick(); onNewAgent(conn.id, null) })
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
                        modifier = Modifier.padding(horizontal = Space.gutter, vertical = Space.sm),
                    )
                }
                stickyHeader(key = "tabs", contentType = "tabs") {
                    Box(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).padding(horizontal = Space.gutter, vertical = Space.sm)) {
                        SegmentedTabs(
                            labels = MachineTab.entries.map { it.label },
                            counts = listOf(
                                state.agents.size.takeIf { it > 0 },
                                state.projects.valueOrNull?.size,
                                state.sessions.valueOrNull?.size,
                            ),
                            selected = tab.ordinal,
                            onSelect = { i -> if (i != tab.ordinal) { haptics.tick(); tabIndex = i } },
                        )
                    }
                }

                when (tab) {
                    MachineTab.AGENTS -> {
                        if (state.agents.isEmpty()) {
                            item(key = "agents:empty", contentType = "empty") {
                                InlineEmpty(
                                    icon = Icons.Rounded.SmartToy,
                                    title = "No agents on ${conn.name}",
                                    body = "Start one in any project — it keeps running on the machine even when your phone sleeps.",
                                    actionLabel = "New agent here",
                                    onAction = { onNewAgent(conn.id, null) },
                                    modifier = Modifier.animateItem(),
                                )
                            }
                        } else {
                            items(state.agents, key = { "agent:" + it.ref.runId }, contentType = { "agent" }) { a ->
                                AgentRunCard(
                                    agent = a,
                                    onClick = { onOpenAgent(a.ref) },
                                    showMachine = false,
                                    modifier = Modifier.animateItem(placementSpec = Motion.gentle()).padding(horizontal = Space.gutter, vertical = 5.dp),
                                )
                            }
                        }
                    }

                    MachineTab.PROJECTS -> when (val p = state.projects) {
                        Loadable.Loading -> items(5, key = { "psk:$it" }, contentType = { "skeleton" }) {
                            TetherCard(Modifier.animateItem().padding(horizontal = Space.gutter, vertical = 4.dp).fillMaxWidth(), contentPadding = PaddingValues(0.dp)) {
                                SkeletonListRow()
                            }
                        }
                        is Loadable.Failed -> item(key = "projects:error", contentType = "error") {
                            ErrorCard(
                                title = "Couldn't load projects",
                                message = p.message,
                                onRetry = { vm.loadProjects() },
                                modifier = Modifier.animateItem().padding(horizontal = Space.gutter, vertical = Space.sm),
                            )
                        }
                        is Loadable.Ready -> if (p.value.isEmpty()) {
                            item(key = "projects:empty", contentType = "empty") {
                                InlineEmpty(
                                    icon = Icons.Rounded.FolderOpen,
                                    title = "No projects yet",
                                    body = "Folders where Claude Code has been used on ${conn.name} will show up here.",
                                    actionLabel = "Start in a folder",
                                    onAction = { onNewAgent(conn.id, null) },
                                    modifier = Modifier.animateItem(),
                                )
                            }
                        } else {
                            items(p.value, key = { "project:" + it.cwd }, contentType = { "project" }) { project ->
                                val expanded = project.cwd in expandedProjects
                                ProjectCard(
                                    project = project,
                                    expanded = expanded,
                                    sessions = state.projectSessions[project.cwd],
                                    onToggle = {
                                        haptics.tick()
                                        expandedProjects = if (expanded) expandedProjects - project.cwd else expandedProjects + project.cwd
                                        if (!expanded) vm.loadProjectSessions(project.cwd)
                                    },
                                    onRetrySessions = { vm.loadProjectSessions(project.cwd, force = true) },
                                    onOpenSession = { s -> openSession(s, conn.id, onOpenAgent, onOpenSession) },
                                    onNewAgentHere = { onNewAgent(conn.id, project.cwd) },
                                    modifier = Modifier.animateItem(placementSpec = Motion.gentle()).padding(horizontal = Space.gutter, vertical = 4.dp),
                                )
                            }
                        }
                    }

                    MachineTab.SESSIONS -> when (val s = state.sessions) {
                        Loadable.Loading -> items(6, key = { "ssk:$it" }, contentType = { "skeleton" }) {
                            TetherCard(Modifier.animateItem().padding(horizontal = Space.gutter, vertical = 4.dp).fillMaxWidth(), contentPadding = PaddingValues(0.dp)) {
                                SkeletonListRow(leading = false)
                            }
                        }
                        is Loadable.Failed -> item(key = "sessions:error", contentType = "error") {
                            ErrorCard(
                                title = "Couldn't load sessions",
                                message = s.message,
                                onRetry = { vm.loadSessions() },
                                modifier = Modifier.animateItem().padding(horizontal = Space.gutter, vertical = Space.sm),
                            )
                        }
                        is Loadable.Ready -> if (s.value.isEmpty()) {
                            item(key = "sessions:empty", contentType = "empty") {
                                InlineEmpty(
                                    icon = Icons.Rounded.Forum,
                                    title = "No sessions yet",
                                    body = "Every Claude Code conversation on ${conn.name} — from Tether or the desktop — lands here.",
                                    modifier = Modifier.animateItem(),
                                )
                            }
                        } else {
                            items(s.value, key = { "session:" + it.sessionId }, contentType = { "session" }) { session ->
                                TetherCard(
                                    Modifier.animateItem().padding(horizontal = Space.gutter, vertical = 4.dp).fillMaxWidth(),
                                    onClick = { openSession(session, conn.id, onOpenAgent, onOpenSession) },
                                    contentPadding = PaddingValues(0.dp),
                                ) {
                                    SessionRow(session, showProject = true)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun openSession(s: SessionSummary, connectionId: String, onOpenAgent: (RunRef) -> Unit, onOpenSession: (String, String) -> Unit) {
    val live = s.liveRunId
    if (live != null) onOpenAgent(RunRef(connectionId, live)) else onOpenSession(connectionId, s.sessionId)
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

// ───────────────────────────── Segmented tabs ─────────────────────────────

@Composable
private fun SegmentedTabs(labels: List<String>, counts: List<Int?>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val outer = RoundedCornerShape(14.dp)
    val inner = RoundedCornerShape(10.dp)
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(46.dp)
            .clip(outer)
            .background(TetherTheme.colors.subtleFill)
            .padding(4.dp),
    ) {
        val segment = maxWidth / labels.size
        val indicatorOffset by animateDpAsState(segment * selected, animationSpec = Motion.gentle(), label = "tabIndicator")
        Box(
            Modifier
                .offset(x = indicatorOffset)
                .width(segment)
                .fillMaxHeight()
                .clip(inner)
                .background(if (TetherTheme.colors.isDark) Color(0xFF4A4A45) else TetherTheme.colors.card)
        )
        Row(Modifier.fillMaxSize().selectableGroup()) {
            labels.forEachIndexed { i, label ->
                val isSel = i == selected
                val color by animateColorAsState(if (isSel) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant, label = "tabColor")
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(inner)
                        .selectable(selected = isSel, role = Role.Tab, onClick = { onSelect(i) }),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(label, style = MaterialTheme.typography.labelLarge.copy(fontWeight = if (isSel) FontWeight.SemiBold else FontWeight.Medium), color = color)
                        val c = counts.getOrNull(i)
                        if (c != null) {
                            Spacer(Modifier.width(5.dp))
                            Text("$c", style = MaterialTheme.typography.labelSmall, color = TetherTheme.colors.faint)
                        }
                    }
                }
            }
        }
    }
}

// ───────────────────────────── Projects ─────────────────────────────

@Composable
private fun ProjectCard(
    project: ProjectSummary,
    expanded: Boolean,
    sessions: Loadable<List<SessionSummary>>?,
    onToggle: () -> Unit,
    onRetrySessions: () -> Unit,
    onOpenSession: (SessionSummary) -> Unit,
    onNewAgentHere: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val rot by animateFloatAsState(if (expanded) 180f else 0f, label = "projChevron")
    val border by animateColorAsState(if (expanded) TetherTheme.colors.clay.copy(alpha = 0.35f) else TetherTheme.colors.cardBorder, label = "projBorder")
    TetherCard(modifier.fillMaxWidth(), border = border, contentPadding = PaddingValues(0.dp)) {
        Column {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(onClickLabel = if (expanded) "Collapse" else "Show sessions", onClick = onToggle)
                    .padding(horizontal = Space.lg, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconTile(Icons.Rounded.Folder, if (project.exists) TetherTheme.colors.clay else TetherTheme.colors.faint)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        projectName(project.cwd),
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = if (project.exists) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(prettyPath(project.cwd), style = TetherTheme.type.monoSmall, color = TetherTheme.colors.faint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(5.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            buildString {
                                append(if (project.sessionCount == 1) "1 session" else "${project.sessionCount} sessions")
                                append(" · ")
                                append(relativeTime(project.lastActiveAt))
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        project.gitBranch?.let { GitBranchBadge(it, Modifier.weight(1f, fill = false)) }
                        if (!project.exists) MiniBadge("Missing", TetherTheme.colors.faint)
                    }
                }
                Icon(Icons.Rounded.ExpandMore, null, tint = TetherTheme.colors.faint, modifier = Modifier.rotate(rot))
            }
            AnimatedVisibility(visible = expanded, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                Column {
                    Hairline()
                    when (sessions) {
                        null, Loadable.Loading -> repeat(2) { SkeletonListRow(leading = false) }
                        is Loadable.Failed -> Row(Modifier.fillMaxWidth().padding(start = Space.lg, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(sessions.message, style = MaterialTheme.typography.bodySmall, color = TetherTheme.colors.danger, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            TextButton(onClick = onRetrySessions) { Text("Retry") }
                        }
                        is Loadable.Ready -> if (sessions.value.isEmpty()) {
                            Text(
                                "No sessions in this folder yet.",
                                style = MaterialTheme.typography.bodySmall,
                                color = TetherTheme.colors.faint,
                                modifier = Modifier.padding(horizontal = Space.lg, vertical = Space.md),
                            )
                        } else {
                            val shown = sessions.value.take(8)
                            shown.forEachIndexed { i, s ->
                                Box(Modifier.fillMaxWidth().clickable(onClick = { onOpenSession(s) })) { SessionRow(s, showProject = false, compact = true) }
                                if (i < shown.lastIndex) Hairline(Modifier.padding(start = Space.lg))
                            }
                            if (sessions.value.size > shown.size) {
                                Text(
                                    "+ ${sessions.value.size - shown.size} older — see the Sessions tab",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TetherTheme.colors.faint,
                                    modifier = Modifier.padding(horizontal = Space.lg, vertical = Space.sm),
                                )
                            }
                        }
                    }
                    Hairline()
                    Row(Modifier.fillMaxWidth().padding(Space.md), horizontalArrangement = Arrangement.End) {
                        SecondaryButton(
                            "New agent here",
                            onClick = onNewAgentHere,
                            icon = Icons.Rounded.Add,
                            enabled = project.exists,
                            contentColor = TetherTheme.colors.clay,
                        )
                    }
                }
            }
        }
    }
}

// ───────────────────────────── Sessions ─────────────────────────────

@Composable
private fun SessionRow(session: SessionSummary, showProject: Boolean, compact: Boolean = false) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Space.lg, vertical = if (compact) 11.dp else 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                session.title.ifBlank { "Untitled session" },
                style = if (compact) MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium) else MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val meta = buildList {
                    if (showProject) add(projectName(session.cwd))
                    add(relativeTime(session.updatedAt))
                    if (session.messageCount > 0) add(if (session.messageCount == 1) "1 message" else "${session.messageCount} messages")
                }
                Text(
                    meta.joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (session.liveRunId != null) {
                    MiniBadge("live", TetherTheme.colors.clay, pulsing = true)
                } else if (session.recentlyActive) {
                    MiniBadge("active on desktop", TetherTheme.colors.info)
                }
            }
        }
        Spacer(Modifier.width(Space.sm))
        Icon(Icons.Rounded.ChevronRight, null, tint = TetherTheme.colors.faint, modifier = Modifier.size(20.dp))
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
