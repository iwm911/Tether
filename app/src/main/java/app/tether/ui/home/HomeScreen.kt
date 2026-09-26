package app.tether.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.layout.requiredSize
import app.tether.R
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Laptop
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.tether.LocalAppContainer
import app.tether.core.AgentSummary
import app.tether.core.Connection
import app.tether.core.LinkState
import app.tether.core.ProjectSummary
import app.tether.core.RunRef
import app.tether.core.RunStatus
import app.tether.ui.components.EmptyState
import app.tether.ui.components.Hairline
import app.tether.ui.components.MachineAvatar
import app.tether.ui.components.PageHeader
import app.tether.ui.components.PrimaryButton
import app.tether.ui.components.SectionHeader
import app.tether.ui.components.StatusDot
import app.tether.ui.components.TetherCard
import app.tether.ui.components.prettyPath
import app.tether.ui.components.projectName
import app.tether.ui.components.relativeTime
import app.tether.ui.components.rememberHaptics
import app.tether.ui.newagent.NewAgentPrefs
import app.tether.ui.theme.Motion
import app.tether.ui.theme.Space
import app.tether.ui.theme.TetherTheme
import kotlinx.coroutines.launch
import java.util.Calendar

@Composable
fun HomeScreen(
    onOpenAgent: (RunRef) -> Unit,
    onNewAgent: (String?) -> Unit,
    onOpenMachine: (String) -> Unit,
    onOpenMachines: () -> Unit,
    onAddMachine: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val container = LocalAppContainer.current
    val vm: HomeViewModel = viewModel { HomeViewModel(container) }
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val haptics = rememberHaptics()
    val context = LocalContext.current
    val listState = rememberLazyListState()
    val pullState = rememberPullToRefreshState()
    var recentExpanded by rememberSaveable { mutableStateOf(true) }

    LaunchedEffect(vm) {
        vm.events.collect { m ->
            when (m) {
                is HomeMessage.Removed -> launch {
                    snackbar.currentSnackbarData?.dismiss()
                    val r = snackbar.showSnackbar("Removed “${m.title}”", actionLabel = "Undo", duration = SnackbarDuration.Short)
                    if (r == SnackbarResult.ActionPerformed) vm.undoDismiss(m.ref)
                }
                is HomeMessage.Error -> launch { snackbar.showSnackbar(m.text, withDismissAction = true, duration = SnackbarDuration.Long) }
            }
        }
    }

    val showHero = state.connections.isNotEmpty() && !state.showSkeleton && !state.hasAgents
    val latest by rememberUpdatedState(state)
    LaunchedEffect(showHero, state.connections.size, state.links.values.count { it is LinkState.Connected }) {
        if (showHero) vm.ensureQuickStart(latest)
    }

    val machinesById = remember(state.connections) { state.connections.associateBy { it.id } }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
        snackbarHost = {
            SnackbarHost(snackbar, Modifier.navigationBarsPadding()) { data ->
                Snackbar(
                    data,
                    shape = RoundedCornerShape(14.dp),
                    containerColor = MaterialTheme.colorScheme.inverseSurface,
                    contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                    actionColor = MaterialTheme.colorScheme.inversePrimary,
                )
            }
        },
        bottomBar = {
            if (state.connections.isNotEmpty()) {
                StartAgentBar("Start an agent…", onClick = { haptics.tick(); onNewAgent(null) })
            }
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
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = padding.calculateBottomPadding() + Space.lg),
            ) {
                item(key = "header", contentType = "header") {
                    HomeHeader(state, onOpenSettings, onOpenMachines = if (state.connections.isNotEmpty()) onOpenMachines else null)
                }
                item(key = "update", contentType = "update") {
                    app.tether.ui.update.UpdateBanner()
                }
                item(key = "keepalive", contentType = "keepalive") {
                    KeepAliveCard(hasMachines = state.connections.isNotEmpty())
                }

                if (state.connections.isNotEmpty()) {
                    item(key = "machines", contentType = "machines") {
                        MachineStrip(
                            connections = state.connections,
                            links = state.links,
                            errors = state.machineErrors,
                            liveCounts = state.liveCountByMachine,
                            onOpen = onOpenMachine,
                            onAdd = onAddMachine,
                            onManage = onOpenMachines,
                        )
                    }
                }

                state.machineErrors.forEach { (id, message) ->
                    val conn = machinesById[id] ?: return@forEach
                    item(key = "err:$id", contentType = "error") {
                        MachineErrorBanner(
                            machineName = conn.name,
                            accent = conn.accent,
                            message = message,
                            retrying = id in state.retrying,
                            onRetry = { haptics.tick(); vm.retryMachine(id) },
                            onOpen = { onOpenMachine(id) },
                            modifier = Modifier.animateItem().padding(horizontal = Space.gutter, vertical = 4.dp),
                        )
                    }
                }

                when {
                    state.connections.isEmpty() -> item(key = "welcome", contentType = "welcome") {
                        WelcomeState(onAddMachine, Modifier.animateItem())
                    }

                    state.showSkeleton -> {
                        item(key = "sk:h", contentType = "sectionHeader") { SectionHeader("Loading agents", Modifier.animateItem()) }
                        items(3, key = { "sk:$it" }, contentType = { "skeleton" }) {
                            SkeletonAgentCard(Modifier.animateItem().padding(horizontal = Space.gutter, vertical = 6.dp))
                        }
                    }

                    !state.hasAgents -> {
                        item(key = "hero", contentType = "hero") {
                            val first = state.quickStart?.connection ?: state.connections.first()
                            FirstAgentHero(
                                machine = first,
                                onStart = { onNewAgent(first.id) },
                                modifier = Modifier.animateItem().padding(horizontal = Space.gutter, vertical = 8.dp),
                            )
                        }
                        state.quickStart?.let { qs ->
                            item(key = "qs:${qs.connection.id}", contentType = "quickstart") {
                                QuickStartSection(
                                    qs = qs,
                                    onPick = { project ->
                                        haptics.tick()
                                        NewAgentPrefs.rememberFolder(context, qs.connection.id, project.cwd)
                                        onNewAgent(qs.connection.id)
                                    },
                                    onRetry = vm::retryQuickStart,
                                    modifier = Modifier.animateItem(),
                                )
                            }
                        }
                    }

                    else -> {
                        if (state.needsYou.isNotEmpty()) {
                            item(key = "h:needs", contentType = "sectionHeader") {
                                CountedSectionHeader("Needs you", state.needsYou.size, Modifier.animateItem(), color = TetherTheme.colors.warning)
                            }
                            items(state.needsYou, key = { "a:" + agentKey(it.ref.connectionId, it.ref.runId) }, contentType = { "needs" }) { a ->
                                val reqId = a.run.pending?.requestId
                                NeedsYouCard(
                                    agent = a,
                                    showMachine = true,
                                    decided = reqId?.let { state.decisions[vm.decisionKey(a.ref, it)] },
                                    onAllow = { haptics.confirm(); vm.respond(a, allow = true) },
                                    onDeny = { haptics.tick(); vm.respond(a, allow = false) },
                                    onOpen = { onOpenAgent(a.ref) },
                                    modifier = Modifier.animateItem(placementSpec = Motion.gentle()).padding(horizontal = Space.gutter, vertical = 6.dp),
                                )
                            }
                        }
                        if (state.working.isNotEmpty()) {
                            item(key = "h:working", contentType = "sectionHeader") {
                                CountedSectionHeader("Working", state.working.size, Modifier.animateItem(), color = TetherTheme.colors.faint)
                            }
                            items(state.working, key = { "a:" + agentKey(it.ref.connectionId, it.ref.runId) }, contentType = { "agent" }) { a ->
                                val answered = agentKey(a.ref.connectionId, a.ref.runId) in state.answeredKeys
                                AgentRunCard(
                                    agent = a,
                                    onClick = { onOpenAgent(a.ref) },
                                    showMachine = true,
                                    statusOverride = if (answered) RunStatus.WORKING else null,
                                    modifier = Modifier.animateItem(placementSpec = Motion.gentle()).padding(horizontal = Space.gutter, vertical = 6.dp),
                                )
                            }
                        }
                        if (state.recent.isNotEmpty()) {
                            item(key = "h:recent", contentType = "sectionHeader") {
                                CollapsibleHeader(
                                    "Recent",
                                    state.recent.size,
                                    expanded = recentExpanded,
                                    onToggle = { haptics.tick(); recentExpanded = !recentExpanded },
                                    modifier = Modifier.animateItem(),
                                )
                            }
                            if (recentExpanded) {
                                items(state.recent, key = { "a:" + agentKey(it.ref.connectionId, it.ref.runId) }, contentType = { "agent" }) { a ->
                                    val itemModifier = Modifier.animateItem(placementSpec = Motion.gentle()).padding(horizontal = Space.gutter, vertical = 6.dp)
                                    if (a.run.status == RunStatus.ENDED || a.run.status == RunStatus.FAILED) {
                                        DismissibleAgentCard(
                                            agent = a,
                                            showMachine = true,
                                            onOpen = { onOpenAgent(a.ref) },
                                            onDismiss = { haptics.confirm(); vm.dismiss(a) },
                                            modifier = itemModifier,
                                        )
                                    } else {
                                        AgentRunCard(agent = a, onClick = { onOpenAgent(a.ref) }, modifier = itemModifier, showMachine = true)
                                    }
                                }
                                if (state.recent.any { it.run.status == RunStatus.ENDED || it.run.status == RunStatus.FAILED }) {
                                    item(key = "hint:swipe", contentType = "hint") {
                                        Text(
                                            "Swipe an ended agent to remove it",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = TetherTheme.colors.faint,
                                            modifier = Modifier.animateItem().fillMaxWidth().padding(horizontal = Space.gutter, vertical = 8.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ───────────────────────────── Header ─────────────────────────────

private fun greeting(hour: Int): String = when (hour) {
    in 5..11 -> "Good morning"
    in 12..16 -> "Good afternoon"
    else -> "Good evening"
}

@Composable
private fun HomeHeader(state: HomeUiState, onOpenSettings: () -> Unit, onOpenMachines: (() -> Unit)?) {
    val hour = remember { Calendar.getInstance().get(Calendar.HOUR_OF_DAY) }
    val subtitle = when {
        state.connections.isEmpty() -> "Claude Code, on your own machines"
        state.showSkeleton -> "Checking in with your machines…"
        else -> {
            val parts = buildList {
                if (state.needsYou.isNotEmpty()) add(if (state.needsYou.size == 1) "One agent needs you" else "${state.needsYou.size} agents need you")
                if (state.working.isNotEmpty()) add(if (state.working.size == 1) "1 working" else "${state.working.size} working")
                if (isEmpty()) add(if (state.recent.isNotEmpty()) "All quiet — nothing running" else "No agents running yet")
            }
            parts.joinToString(" · ")
        }
    }
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(top = Space.xs, end = Space.xs), horizontalArrangement = Arrangement.End) {
            if (onOpenMachines != null) {
                IconButton(onClick = onOpenMachines) {
                    Icon(Icons.Outlined.Dns, contentDescription = "Machines", tint = TetherTheme.colors.faint)
                }
            }
            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Outlined.Settings, contentDescription = "Settings", tint = TetherTheme.colors.faint)
            }
        }
        Column(Modifier.padding(horizontal = Space.gutter).padding(top = Space.xl, bottom = Space.lg)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // The launcher foreground carries adaptive-icon padding; let it overflow a 36dp slot.
                Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                    Icon(
                        painterResource(R.drawable.ic_launcher_foreground),
                        contentDescription = null,
                        tint = Color.Unspecified,
                        modifier = Modifier.requiredSize(74.dp),
                    )
                }
                Spacer(Modifier.width(12.dp))
                Text(greeting(hour), style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Normal), color = MaterialTheme.colorScheme.onBackground)
            }
            Spacer(Modifier.height(10.dp))
            Text(subtitle, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ───────────────────────────── Machine strip ─────────────────────────────

@Composable
private fun MachineStrip(
    connections: List<Connection>,
    links: Map<String, LinkState>,
    errors: Map<String, String>,
    liveCounts: Map<String, Int>,
    onOpen: (String) -> Unit,
    onAdd: () -> Unit,
    onManage: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(bottom = Space.xs)) {
        LazyRow(
            contentPadding = PaddingValues(horizontal = Space.gutter),
            horizontalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
            items(connections, key = { it.id }) { c ->
                MachineChip(
                    connection = c,
                    link = links[c.id],
                    error = errors[c.id],
                    live = liveCounts[c.id] ?: 0,
                    onClick = { onOpen(c.id) },
                    modifier = Modifier.animateItem(),
                )
            }
            item(key = "add") { AddMachineChip(onAdd) }
        }
    }
}

@Composable
private fun MachineChip(connection: Connection, link: LinkState?, error: String?, live: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val look = linkLook(link, error)
    val shape = CircleShape
    Row(
        modifier
            .heightIn(min = 40.dp)
            .clip(shape)
            .background(TetherTheme.colors.subtleFill.copy(alpha = if (TetherTheme.colors.isDark) 0.7f else 1f))
            .clickable(onClickLabel = "Open ${connection.name}", onClick = onClick)
            .padding(start = 6.dp, end = 14.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MachineAvatar(connection.name, connection.accent, size = 26.dp, modifier = Modifier.clip(CircleShape))
        Spacer(Modifier.width(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                connection.name,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 132.dp),
            )
            Spacer(Modifier.width(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(look.color, pulsing = look.pulsing || live > 0, size = 5.dp)
                Text(
                    when {
                        live > 0 -> "$live running"
                        link is LinkState.Connected -> "Online"
                        link == LinkState.Connecting -> "Connecting"
                        link is LinkState.Failed || error != null -> "Offline"
                        else -> "Standby"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (live > 0) TetherTheme.colors.clay else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun AddMachineChip(onAdd: () -> Unit) {
    val shape = CircleShape
    Row(
        Modifier
            .heightIn(min = 40.dp)
            .clip(shape)
            .clickable(onClickLabel = "Add a machine", onClick = onAdd)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Add, null, tint = TetherTheme.colors.faint, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(4.dp))
        Text("Add machine", style = MaterialTheme.typography.labelLarge, color = TetherTheme.colors.faint)
    }
}

// ───────────────────────────── Empty states ─────────────────────────────

@Composable
private fun WelcomeState(onAddMachine: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        EmptyState(
            icon = Icons.Rounded.Laptop,
            title = "Connect a machine",
            body = "Tether runs Claude Code on your own computers over SSH. Add one and you can start, watch and approve agents from anywhere.",
            actionLabel = "Add a machine",
            onAction = onAddMachine,
        )
        TetherCard(Modifier.padding(horizontal = Space.gutter).fillMaxWidth(), contentPadding = PaddingValues(vertical = Space.sm)) {
            Column {
                FeatureRow(Icons.Rounded.Shield, "Your code stays put", "Agents run on your machine. The phone is just the remote.")
                Hairline(Modifier.padding(start = 60.dp))
                FeatureRow(Icons.Rounded.Bolt, "Approve from anywhere", "Allow or deny tool use with one tap — even from a notification.")
                Hairline(Modifier.padding(start = 60.dp))
                FeatureRow(Icons.Rounded.Schedule, "Nothing gets lost", "Agents keep working while your phone sleeps. Reopen and pick up where they are.")
            }
        }
    }
}

@Composable
private fun FeatureRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, body: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = Space.lg, vertical = Space.md), verticalAlignment = Alignment.Top) {
        IconTile(icon, TetherTheme.colors.clay, size = 32.dp)
        Spacer(Modifier.width(Space.md))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(2.dp))
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun FirstAgentHero(machine: Connection, onStart: () -> Unit, modifier: Modifier = Modifier) {
    val clay = TetherTheme.colors.clay
    TetherCard(
        modifier.fillMaxWidth(),
        contentPadding = PaddingValues(Space.xl),
    ) {
        Column {
            Box(
                Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(clay.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                Text("✻", color = clay, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(Space.lg))
            Text("Start your first agent", style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Normal))
            Spacer(Modifier.height(Space.sm))
            Text(
                "Pick a project on ${machine.name}, tell Claude what to do, and put your phone away. It'll ping you when it needs a decision.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(Space.xl))
            PrimaryButton("New agent", onClick = onStart, icon = Icons.Rounded.Add, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun QuickStartSection(qs: QuickStart, onPick: (ProjectSummary) -> Unit, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(top = Space.sm)) {
        SectionHeader("Jump back in · ${qs.connection.name}")
        val cardModifier = Modifier.padding(horizontal = Space.gutter).fillMaxWidth()
        when (val p = qs.projects) {
            Loadable.Loading -> TetherCard(cardModifier, contentPadding = PaddingValues(0.dp)) {
                Column {
                    repeat(3) { i ->
                        SkeletonListRow()
                        if (i < 2) Hairline(Modifier.padding(start = 66.dp))
                    }
                }
            }
            is Loadable.Failed -> ErrorCard(
                title = "Couldn't load projects from ${qs.connection.name}",
                message = p.message,
                onRetry = onRetry,
                modifier = cardModifier,
            )
            is Loadable.Ready -> if (p.value.isEmpty()) {
                Text(
                    "No Claude Code projects on ${qs.connection.name} yet — you can pick any folder when you start.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TetherTheme.colors.faint,
                    modifier = Modifier.padding(horizontal = Space.gutter, vertical = Space.xs),
                )
            } else {
                TetherCard(cardModifier, contentPadding = PaddingValues(0.dp)) {
                    Column {
                        p.value.forEachIndexed { i, project ->
                            QuickStartRow(project, onClick = { onPick(project) })
                            if (i < p.value.lastIndex) Hairline(Modifier.padding(start = 66.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QuickStartRow(project: ProjectSummary, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = "Start an agent in ${projectName(project.cwd)}", onClick = onClick)
            .padding(horizontal = Space.lg, vertical = Space.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconTile(Icons.Rounded.Folder, TetherTheme.colors.clay)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(projectName(project.cwd), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(prettyPath(project.cwd), style = TetherTheme.type.monoSmall, color = TetherTheme.colors.faint, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(Space.sm))
        Column(horizontalAlignment = Alignment.End) {
            Text(relativeTime(project.lastActiveAt), style = MaterialTheme.typography.labelSmall, color = TetherTheme.colors.faint)
            project.gitBranch?.let {
                Spacer(Modifier.height(3.dp))
                GitBranchBadge(it, Modifier.widthIn(max = 110.dp))
            }
        }
        Spacer(Modifier.width(Space.sm))
        Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, tint = TetherTheme.colors.faint, modifier = Modifier.size(18.dp))
    }
}

// ───────────────────────────── Swipe to remove ─────────────────────────────

@Composable
private fun DismissibleAgentCard(agent: AgentSummary, onOpen: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier, showMachine: Boolean = true) {
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) {
                currentOnDismiss(); true
            } else false
        },
    )
    SwipeToDismissBox(
        state = dismissState,
        modifier = modifier,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            val danger = TetherTheme.colors.danger
            val progress = dismissState.progress
            Row(
                Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(24.dp))
                    .background(danger.copy(alpha = if (dismissState.dismissDirection == SwipeToDismissBoxValue.EndToStart) 0.10f + 0.12f * progress else 0f))
                    .padding(horizontal = Space.xl),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (dismissState.dismissDirection == SwipeToDismissBoxValue.EndToStart) {
                    Text("Remove", style = MaterialTheme.typography.labelLarge, color = danger)
                    Spacer(Modifier.width(Space.sm))
                    Icon(Icons.Rounded.DeleteOutline, contentDescription = null, tint = danger)
                }
            }
        },
    ) {
        AgentRunCard(agent = agent, onClick = onOpen, showMachine = showMachine)
    }
}
