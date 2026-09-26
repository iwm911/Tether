package app.tether.ui.connections

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.NetworkCheck
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.tether.LocalAppContainer
import app.tether.core.Connection
import app.tether.core.LinkState
import app.tether.ui.components.EmptyState
import app.tether.ui.components.MachineAvatar
import app.tether.ui.components.PageHeader
import app.tether.ui.components.PrimaryButton
import app.tether.ui.components.SecondaryButton
import app.tether.ui.components.StatusDot
import app.tether.ui.components.TagChip
import app.tether.ui.components.TetherCard
import app.tether.ui.components.TetherTopBar
import app.tether.ui.components.rememberHaptics
import app.tether.ui.components.relativeTime
import app.tether.ui.theme.Space
import app.tether.ui.theme.TetherTheme

@Composable
fun MachinesScreen(
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onOpen: (String) -> Unit,
    onEdit: (String) -> Unit,
) {
    val container = LocalAppContainer.current
    val vm: MachinesViewModel = viewModel { MachinesViewModel(container) }
    val machines by vm.items.collectAsStateWithLifecycle()
    val test by vm.test.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(vm) { vm.messages.collect { snackbar.showSnackbar(it) } }

    var deleting by remember { mutableStateOf<MachineItem?>(null) }
    val listState = rememberLazyListState()
    val atTop by remember { derivedStateOf { listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset < 40 } }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TetherTopBar(
                title = if (atTop) "" else "Machines",
                onBack = onBack,
                modifier = Modifier.statusBarsPadding(),
            )
        },
        floatingActionButton = {
            if (machines.isNotEmpty()) {
                ExtendedFloatingActionButton(
                    text = { Text("Add machine", style = MaterialTheme.typography.labelLarge) },
                    icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                    onClick = onAdd,
                    expanded = atTop,
                    shape = RoundedCornerShape(18.dp),
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    elevation = FloatingActionButtonDefaults.elevation(0.dp, 0.dp, 0.dp, 0.dp),
                    modifier = Modifier.navigationBarsPadding(),
                )
            }
        },
        snackbarHost = { TetherSnackbarHost(snackbar, Modifier.navigationBarsPadding()) },
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            if (machines.isEmpty()) {
                Box(
                    Modifier.fillMaxSize().navigationBarsPadding().padding(bottom = 48.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    EmptyState(
                        icon = Icons.Rounded.Dns,
                        title = "No machines yet",
                        body = "Add a computer you can reach over SSH — your laptop, a dev box, a cloud VM. Claude Code runs there; this phone is the remote.",
                        actionLabel = "Add a machine",
                        onAction = onAdd,
                    )
                }
            } else {
                val online = machines.count { it.link is LinkState.Connected }
                val running = machines.sumOf { it.running }
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 120.dp),
                    verticalArrangement = Arrangement.spacedBy(Space.md),
                ) {
                    item(key = "header") {
                        PageHeader(
                            title = "Machines",
                            eyebrow = buildString {
                                append("$online of ${machines.size} connected")
                                if (running > 0) append(" · $running running")
                            },
                            subtitle = "Computers Tether reaches over SSH.",
                        )
                    }
                    items(machines, key = { it.connection.id }) { item ->
                        MachineCard(
                            item = item,
                            onOpen = { onOpen(item.connection.id) },
                            onEdit = { onEdit(item.connection.id) },
                            onTest = { vm.test(item.connection) },
                            onDisconnect = { vm.disconnect(item.connection) },
                            onDelete = { deleting = item },
                            modifier = Modifier.animateItem().padding(horizontal = Space.gutter),
                        )
                    }
                    item(key = "footer") {
                        Text(
                            "Agents run detached on the machine, so removing or disconnecting it here never stops them.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TetherTheme.colors.faint,
                            modifier = Modifier.padding(horizontal = Space.gutter + Space.xs, vertical = Space.sm).navigationBarsPadding(),
                        )
                    }
                }
            }
        }
    }

    deleting?.let { item ->
        val c = item.connection
        ConfirmDialog(
            title = "Remove ${c.name}?",
            body = buildString {
                append("Tether forgets this machine and its saved password on this phone. Nothing on the machine is touched.")
                if (item.running > 0) {
                    append(" ${item.running} agent${if (item.running == 1) " is" else "s are"} still running there and will keep going.")
                }
            },
            confirmLabel = "Remove",
            danger = true,
            icon = Icons.Rounded.DeleteOutline,
            onConfirm = { deleting = null; vm.delete(c) },
            onDismiss = { deleting = null },
        )
    }

    test?.let { t ->
        MachineTestSheet(
            ui = t,
            onDismiss = vm::dismissTest,
            onRetry = { vm.test(t.connection) },
            onEdit = { vm.dismissTest(); onEdit(t.connection.id) },
        )
    }
}

@Composable
private fun MachineCard(
    item: MachineItem,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onTest: () -> Unit,
    onDisconnect: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = item.connection
    val colors = TetherTheme.colors
    val haptics = rememberHaptics()
    var menu by remember { mutableStateOf(false) }
    val border by animateColorAsState(if (item.needsYou > 0) colors.warning.copy(alpha = 0.5f) else colors.cardBorder, label = "mborder")
    TetherCard(
        modifier = modifier.fillMaxWidth(),
        onClick = onOpen,
        onLongClick = { haptics.confirm(); menu = true },
        border = border,
        contentPadding = PaddingValues(start = Space.lg, top = Space.lg, bottom = Space.lg, end = Space.xs),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            MachineAvatar(c.name, c.accent, size = 48.dp)
            Spacer(Modifier.width(Space.md))
            Column(Modifier.weight(1f).padding(top = 1.dp)) {
                Text(c.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${c.username}@${c.host}:${c.port}",
                    style = TetherTheme.type.monoSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(Space.sm))
                LinkLine(item)
                if (item.running > 0 || item.needsYou > 0) {
                    Spacer(Modifier.height(Space.md))
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                        if (item.needsYou > 0) TagChip("${item.needsYou} need${if (item.needsYou == 1) "s" else ""} you", selected = true, accent = colors.warning)
                        if (item.running > 0) app.tether.ui.home.MiniBadge("${item.running} running", colors.clay, pulsing = true)
                    }
                }
            }
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Rounded.MoreVert, contentDescription = "More for ${c.name}", tint = colors.faint)
                }
                DropdownMenu(
                    expanded = menu,
                    onDismissRequest = { menu = false },
                    shape = RoundedCornerShape(16.dp),
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    tonalElevation = 0.dp,
                    shadowElevation = 6.dp,
                    border = BorderStroke(1.dp, colors.hairline),
                ) {
                    MenuRow("Edit", Icons.Rounded.Edit) { menu = false; onEdit() }
                    MenuRow("Test connection", Icons.Rounded.NetworkCheck) { menu = false; onTest() }
                    if (item.link is LinkState.Connected || item.link is LinkState.Connecting) {
                        MenuRow("Disconnect", Icons.Rounded.LinkOff) { menu = false; onDisconnect() }
                    }
                    MenuRow("Remove", Icons.Rounded.DeleteOutline, tint = colors.danger) { menu = false; onDelete() }
                }
            }
        }
    }
}

@Composable
private fun MenuRow(text: String, icon: ImageVector, tint: Color = MaterialTheme.colorScheme.onSurface, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(text, color = tint, style = MaterialTheme.typography.bodyLarge) },
        leadingIcon = { Icon(icon, null, tint = tint) },
        onClick = onClick,
    )
}

@Composable
private fun LinkLine(item: MachineItem) {
    val colors = TetherTheme.colors
    val link = item.link
    val failed = link is LinkState.Failed || (link is LinkState.Idle && item.error != null)
    val (color, label) = when {
        link is LinkState.Connected -> colors.success to ("Connected" + (link.latencyMs?.let { " · $it ms" } ?: ""))
        link is LinkState.Connecting -> colors.info to "Connecting…"
        failed -> colors.danger to "Unreachable"
        else -> colors.faint to (item.connection.lastConnectedAt?.let { seenAgo(it) } ?: "Not connected yet")
    }
    val errorText = (link as? LinkState.Failed)?.message ?: item.error
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(color, pulsing = link is LinkState.Connecting, size = 7.dp)
            Spacer(Modifier.width(2.dp))
            AnimatedContent(label, transitionSpec = { fadeIn().togetherWith(fadeOut()) }, label = "link") {
                Text(it, style = MaterialTheme.typography.labelMedium, color = color)
            }
            item.connection.lastClaudeVersion?.let { v ->
                Text(
                    "  ·  Claude $v",
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.faint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        AnimatedVisibility(failed && errorText != null) {
            Text(
                errorText.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = colors.danger.copy(alpha = 0.85f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp, start = 4.dp),
            )
        }
    }
}

private fun seenAgo(epochMs: Long): String {
    val r = relativeTime(epochMs)
    return when {
        r == "now" -> "Seen just now"
        r.last() == 'm' || r.last() == 'h' -> "Seen $r ago"
        else -> "Seen $r"
    }
}

@Composable
private fun MachineTestSheet(ui: MachineTestUi, onDismiss: () -> Unit, onRetry: () -> Unit, onEdit: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val c: Connection = ui.connection
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Space.gutter)
                .padding(bottom = Space.xl),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                MachineAvatar(c.name, c.accent, size = 44.dp)
                Spacer(Modifier.width(Space.md))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (ui.test.running) "Testing ${c.name}…" else c.name,
                        style = MaterialTheme.typography.headlineSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text("${c.username}@${c.host}:${c.port}", style = TetherTheme.type.monoSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(Space.lg))
            TestChecklist(ui.test)
            Spacer(Modifier.height(Space.xl))
            AnimatedContent(ui.test.running to ui.test.succeeded, transitionSpec = { fadeIn().togetherWith(fadeOut()) }, label = "tsheet") { (running, ok) ->
                when {
                    running -> SecondaryButton("Cancel", onClick = onDismiss, modifier = Modifier.fillMaxWidth())
                    ok -> PrimaryButton("Done", onClick = onDismiss, modifier = Modifier.fillMaxWidth())
                    else -> Row(horizontalArrangement = Arrangement.spacedBy(Space.md)) {
                        SecondaryButton("Edit machine", onClick = onEdit, icon = Icons.Rounded.Edit, modifier = Modifier.weight(1f))
                        PrimaryButton("Try again", onClick = onRetry, icon = Icons.Rounded.NetworkCheck, modifier = Modifier.weight(1f))
                    }
                }
            }
            Spacer(Modifier.size(Space.sm))
        }
    }
}
