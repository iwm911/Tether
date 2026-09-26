package app.tether.ui.chat

import app.tether.ui.home.nativeStateLabel
import app.tether.ui.home.nativeStateColor
import app.tether.core.RunStatus
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.CallSplit
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.StopCircle
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.tether.core.NativeSubagent
import app.tether.core.NativeTimelineEntry
import app.tether.core.RunInfo
import app.tether.ui.components.ClaudeSpinner
import app.tether.ui.components.PrimaryButton
import app.tether.ui.components.SecondaryButton
import app.tether.ui.components.StatusDot
import app.tether.ui.components.compactNumber
import app.tether.ui.components.formatElapsed
import app.tether.ui.components.rememberHaptics
import app.tether.ui.home.BackgroundBadge
import app.tether.ui.home.NativeActivityLine
import app.tether.ui.home.NativeStatusPill
import app.tether.ui.home.subagentSummary
import app.tether.ui.theme.Space
import app.tether.ui.theme.TetherTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/*
 * Agent-screen pieces for Claude Code's own background agents (`claude --bg`): a live header card
 * (state, one-line activity, subagent fan-out), the "working in the background" dock, the activity
 * timeline and terminal-output sheets, and their overflow menu.
 */

/** Live state card pinned under the top bar. */
@Composable
internal fun NativeHeaderCard(run: RunInfo?, onOpenTimeline: () -> Unit, modifier: Modifier = Modifier) {
    if (run == null) return
    var expanded by remember { mutableStateOf(false) }
    val working = run.alive && (run.status == RunStatus.WORKING || run.status == RunStatus.STARTING)
    val line = when {
        run.nativeBlocked -> "Needs your answer" + (run.pending?.summary?.takeIf { it.isNotBlank() }?.let { " — $it" } ?: "")
        working -> run.detail?.takeIf { it.isNotBlank() } ?: "Working…"
        else -> run.detail?.takeIf { it.isNotBlank() } ?: run.lastText?.takeIf { it.isNotBlank() } ?: nativeStateLabel(run)
    }
    val running = run.subagents.count { it.running }
    val hasDetails = run.subagents.isNotEmpty() || run.tokens > 0 || line.length > 60
    // One quiet line by default (the top bar already says "Working"/"Needs you"); tap for details.
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Column(Modifier.animateContentSize()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 44.dp)
                    .clickable(enabled = hasDetails, role = Role.Button, onClickLabel = if (expanded) "Hide details" else "Show details") { expanded = !expanded }
                    .padding(start = 12.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (working) ClaudeSpinner(fontSize = 13f) else StatusDot(nativeStateColor(run), pulsing = run.nativeBlocked, size = 7.dp)
                Spacer(Modifier.width(8.dp))
                Text(
                    line,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (run.nativeBlocked) TetherTheme.colors.warning else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = if (expanded) 4 else 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (run.subagents.isNotEmpty()) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (running > 0) "$running/${run.subagents.size} agents" else "${run.subagents.size} agents",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (running > 0) TetherTheme.colors.clay else TetherTheme.colors.faint,
                    )
                }
                if (hasDetails) {
                    val rot by animateFloatAsState(if (expanded) 0f else -90f, label = "hdrChev")
                    Icon(Icons.Rounded.ExpandMore, contentDescription = null, tint = TetherTheme.colors.faint, modifier = Modifier.padding(start = 2.dp).size(18.dp).rotate(rot))
                }
                IconButton(onClick = onOpenTimeline, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Rounded.History, contentDescription = "Activity timeline", tint = TetherTheme.colors.faint, modifier = Modifier.size(18.dp))
                }
            }
            AnimatedVisibility(expanded, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    for (sub in run.subagents.sortedWith(compareByDescending<NativeSubagent> { it.running }.thenBy { it.startedAt ?: 0L })) {
                        SubagentRow(sub)
                    }
                    if (run.tokens > 0) {
                        Text("${compactNumber(run.tokens)} tokens used", style = MaterialTheme.typography.labelSmall, color = TetherTheme.colors.faint, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun SubagentRow(sub: NativeSubagent) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    if (sub.running) {
        LaunchedEffect(sub.id) { while (true) { delay(1_000); now = System.currentTimeMillis() } }
    }
    Row(Modifier.fillMaxWidth().heightIn(min = 32.dp).padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(22.dp), contentAlignment = Alignment.Center) {
            if (sub.running) {
                ClaudeSpinner(fontSize = 13f)
            } else {
                Icon(Icons.Rounded.CheckCircle, contentDescription = "Done", tint = TetherTheme.colors.success, modifier = Modifier.size(15.dp))
            }
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(sub.label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val kind = listOfNotNull(sub.group?.takeIf { it.isNotBlank() && it != sub.label }, sub.kind.takeIf { it.isNotBlank() && it != "agent" })
            if (kind.isNotEmpty()) {
                Text(kind.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = TetherTheme.colors.faint, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        val start = sub.startedAt
        val dur = when {
            start == null -> null
            sub.running -> formatElapsed(now - start)
            sub.doneAt != null -> formatElapsed(sub.doneAt!! - start)
            else -> null
        }
        Text(
            listOfNotNull(if (sub.running) "running" else "done", dur).joinToString(" · "),
            style = TetherTheme.type.monoSmall,
            color = if (sub.running) TetherTheme.colors.clay else TetherTheme.colors.faint,
        )
    }
}

/** Replaces the composer while a background agent works: nothing to type into yet, but it can be stopped. */
@Composable
internal fun NativeWorkingDock(stopping: Boolean, onStop: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(26.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, TetherTheme.colors.hairline),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(top = 6.dp, bottom = 8.dp),
    ) {
        Row(Modifier.padding(start = Space.lg, end = Space.md, top = Space.md, bottom = Space.md), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "Working in the background",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    "You can reply when it finishes",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(Space.sm))
            SecondaryButton(if (stopping) "Stopping…" else "Stop", onClick = onStop, icon = Icons.Rounded.StopCircle, enabled = !stopping)
        }
    }
}

@Composable
internal fun NativeOverflowMenu(
    run: RunInfo?,
    hasSession: Boolean,
    onCopySession: () -> Unit,
    onOpenMachine: () -> Unit,
    onTimeline: () -> Unit,
    onLogs: () -> Unit,
    onStop: () -> Unit,
    onRemove: () -> Unit,
    onFork: () -> Unit = {},
) {
    var open by remember { mutableStateOf(false) }
    val haptics = rememberHaptics()
    Box {
        IconButton(onClick = { haptics.tick(); open = true }) { Icon(Icons.Rounded.MoreVert, contentDescription = "More options") }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            shape = RoundedCornerShape(18.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            border = BorderStroke(1.dp, TetherTheme.colors.hairline),
        ) {
            DropdownMenuItem(
                text = { Text("View terminal output") },
                leadingIcon = { Icon(Icons.Rounded.Terminal, contentDescription = null) },
                onClick = { open = false; onLogs() },
            )
            DropdownMenuItem(
                text = { Text("Activity timeline") },
                leadingIcon = { Icon(Icons.Rounded.History, contentDescription = null) },
                onClick = { open = false; onTimeline() },
            )
            DropdownMenuItem(
                text = { Text("Fork into a live agent") },
                leadingIcon = { Icon(Icons.AutoMirrored.Rounded.CallSplit, contentDescription = null) },
                enabled = hasSession,
                onClick = { open = false; onFork() },
            )
            DropdownMenuItem(
                text = { Text("Copy session id") },
                leadingIcon = { Icon(Icons.Rounded.ContentCopy, contentDescription = null) },
                enabled = hasSession,
                onClick = { open = false; onCopySession() },
            )
            DropdownMenuItem(
                text = { Text("Open machine") },
                leadingIcon = { Icon(Icons.Rounded.Computer, contentDescription = null) },
                onClick = { open = false; onOpenMachine() },
            )
            HorizontalDivider(color = TetherTheme.colors.hairline, modifier = Modifier.padding(vertical = 4.dp))
            if (run?.alive == true) {
                DropdownMenuItem(
                    text = { Text("Stop agent", color = TetherTheme.colors.danger) },
                    leadingIcon = { Icon(Icons.Rounded.StopCircle, contentDescription = null, tint = TetherTheme.colors.danger) },
                    onClick = { open = false; onStop() },
                )
            }
            DropdownMenuItem(
                text = { Text("Remove agent", color = TetherTheme.colors.danger) },
                leadingIcon = { Icon(Icons.Rounded.DeleteOutline, contentDescription = null, tint = TetherTheme.colors.danger) },
                onClick = { open = false; onRemove() },
            )
        }
    }
}

@Composable
internal fun RemoveNativeDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        icon = { Icon(Icons.Rounded.DeleteOutline, contentDescription = null, tint = TetherTheme.colors.danger) },
        title = { Text("Remove this agent?", style = MaterialTheme.typography.headlineSmall) },
        text = {
            Text(
                "It disappears from “claude agents” on your computer (claude rm). The conversation transcript stays on disk and remains in this machine's sessions.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Remove", color = TetherTheme.colors.danger, style = MaterialTheme.typography.labelLarge) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", style = MaterialTheme.typography.labelLarge) } },
    )
}

@Composable
internal fun StopNativeDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        icon = { Icon(Icons.Rounded.StopCircle, contentDescription = null, tint = TetherTheme.colors.danger) },
        title = { Text("Stop this agent?", style = MaterialTheme.typography.headlineSmall) },
        text = {
            Text(
                "Claude stops working (claude stop). Its conversation is kept — reply any time to continue it.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Stop agent", color = TetherTheme.colors.danger, style = MaterialTheme.typography.labelLarge) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", style = MaterialTheme.typography.labelLarge) } },
    )
}

private sealed interface Loaded<out T> {
    data object Loading : Loaded<Nothing>
    data class Ok<T>(val value: T) : Loaded<T>
    data class Err(val message: String) : Loaded<Nothing>
}

@Composable
private fun <T> rememberLoad(key: Any, load: suspend () -> T): Pair<Loaded<T>, () -> Unit> {
    var tick by remember { mutableIntStateOf(0) }
    var state by remember { mutableStateOf<Loaded<T>>(Loaded.Loading) }
    LaunchedEffect(key, tick) {
        state = Loaded.Loading
        state = try {
            Loaded.Ok(load())
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Loaded.Err(friendlyError(t, "Couldn't load"))
        }
    }
    return state to { tick++ }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SheetFrame(title: String, subtitle: String?, onDismiss: () -> Unit, actions: @Composable () -> Unit = {}, content: @Composable () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    ) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.88f)) {
            Row(Modifier.fillMaxWidth().padding(start = Space.gutter, end = Space.sm), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
                    if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = TetherTheme.colors.faint)
                }
                actions()
                IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, contentDescription = "Close") }
            }
            Spacer(Modifier.height(Space.sm))
            content()
        }
    }
}

@Composable
private fun SheetMessage(text: String, onRetry: (() -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().padding(Space.xxl), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (onRetry != null) {
            Spacer(Modifier.height(Space.md))
            PrimaryButton("Try again", onClick = onRetry)
        }
    }
}

@Composable
private fun SheetLoading() {
    Row(Modifier.fillMaxWidth().padding(Space.xxl), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        ClaudeSpinner()
        Spacer(Modifier.width(8.dp))
        Text("Loading…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
private val dayFmt = SimpleDateFormat("EEE d MMM", Locale.getDefault())

/** The agent's activity history: every state/detail change it reported, newest first. */
@Composable
internal fun NativeTimelineSheet(key: Any, load: suspend () -> List<NativeTimelineEntry>, onDismiss: () -> Unit) {
    val (state, retry) = rememberLoad(key, load)
    SheetFrame("Activity", "What this background agent reported, newest first", onDismiss) {
        when (state) {
            Loaded.Loading -> SheetLoading()
            is Loaded.Err -> SheetMessage(state.message, retry)
            is Loaded.Ok -> {
                val entries = state.value.asReversed()
                if (entries.isEmpty()) {
                    SheetMessage("No activity recorded yet.")
                } else {
                    LazyColumn(Modifier.fillMaxWidth(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = Space.xxl)) {
                        itemsIndexed(entries, key = { i, e -> "${e.at}:$i" }) { i, e ->
                            val prevDay = entries.getOrNull(i - 1)?.let { dayFmt.format(Date(it.at)) }
                            val day = dayFmt.format(Date(e.at))
                            if (day != prevDay) {
                                Text(
                                    day.uppercase(),
                                    style = TetherTheme.type.eyebrow,
                                    color = TetherTheme.colors.faint,
                                    modifier = Modifier.padding(start = Space.gutter, top = Space.md, bottom = 4.dp),
                                )
                            }
                            TimelineRow(e, last = i == entries.lastIndex)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TimelineRow(e: NativeTimelineEntry, last: Boolean) {
    var expanded by remember { mutableStateOf(false) }
    val color = when (e.state) {
        "working" -> TetherTheme.colors.clay
        "blocked" -> TetherTheme.colors.warning
        "done" -> TetherTheme.colors.success
        else -> TetherTheme.colors.faint
    }
    val text = e.text?.trim()?.takeIf { it.isNotEmpty() }
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (text != null) Modifier.clickable { expanded = !expanded } else Modifier)
            .padding(horizontal = Space.gutter, vertical = 6.dp),
    ) {
        Text(
            if (e.at > 0) timeFmt.format(Date(e.at)) else "",
            style = TetherTheme.type.monoSmall,
            color = TetherTheme.colors.faint,
            modifier = Modifier.width(46.dp).padding(top = 2.dp),
        )
        Column(Modifier.width(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            StatusDot(color, size = 7.dp)
            if (!last) Box(Modifier.width(1.dp).height(24.dp).background(TetherTheme.colors.hairline))
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f).animateContentSize()) {
            Text(
                e.detail?.takeIf { it.isNotBlank() } ?: e.state?.replaceFirstChar { it.uppercase() } ?: "Update",
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (text != null) {
                Text(
                    text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = if (expanded) Int.MAX_VALUE else 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

/** `claude logs <id>`: the agent's terminal as it stands, in mono. */
@Composable
internal fun NativeLogsSheet(key: Any, load: suspend () -> String, onDismiss: () -> Unit) {
    val (state, retry) = rememberLoad(key, load)
    val clipboard = LocalClipboardManager.current
    SheetFrame(
        "Terminal output",
        "claude logs · as it appears on your computer",
        onDismiss,
        actions = {
            if (state is Loaded.Ok && state.value.isNotBlank()) {
                IconButton(onClick = { clipboard.setText(AnnotatedString(state.value)) }) {
                    Icon(Icons.Rounded.ContentCopy, contentDescription = "Copy output")
                }
            }
            IconButton(onClick = retry) { Icon(Icons.Rounded.History, contentDescription = "Reload") }
        },
    ) {
        when (state) {
            Loaded.Loading -> SheetLoading()
            is Loaded.Err -> SheetMessage(state.message, retry)
            is Loaded.Ok -> if (state.value.isBlank()) {
                SheetMessage("No terminal output yet.")
            } else {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .padding(bottom = Space.lg)
                        .clip(RoundedCornerShape(16.dp))
                        .background(TetherTheme.colors.codeBg)
                        .verticalScroll(rememberScrollState())
                        .horizontalScroll(rememberScrollState())
                        .padding(Space.md),
                ) {
                    SelectionContainer {
                        Text(
                            state.value,
                            style = TetherTheme.type.monoSmall.copy(fontSize = 11.5.sp, lineHeight = 16.sp),
                            color = MaterialTheme.colorScheme.onSurface,
                            softWrap = false,
                        )
                    }
                }
            }
        }
    }
}
