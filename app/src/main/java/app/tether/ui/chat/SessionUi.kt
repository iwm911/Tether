package app.tether.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.automirrored.rounded.Reply
import androidx.compose.material.icons.rounded.CheckBox
import androidx.compose.material.icons.rounded.CheckBoxOutlineBlank
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.StopCircle
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.tether.core.DialogKind
import app.tether.core.SessionKey
import app.tether.core.SessionPending
import app.tether.core.SessionStatusLine
import app.tether.core.SubagentInfo
import app.tether.core.SubagentStatus
import app.tether.ui.components.ClaudeSpinner
import app.tether.ui.components.PrimaryButton
import app.tether.ui.components.SecondaryButton
import app.tether.ui.components.StatusDot
import app.tether.ui.components.WorkingIndicator
import app.tether.ui.components.rememberHaptics
import app.tether.ui.theme.Space
import app.tether.ui.theme.TetherTheme

/*
 * Pieces of the one-session screen ([SessionChatScreen]): the dialog panel for startup / session
 * dialogs, the subagent strip, the held-by-terminal bar, the read-only subagent bar, the status
 * footer and the overflow menu.
 */

// ───────────────────────────── Status footer ─────────────────────────────

/** The CLI spinner line ("Improvising… 12s · ↓ 1.2k tokens") from `status` events, else a generic one. */
@Composable
internal fun SessionStatusFooter(status: SessionStatusLine?, fallbackSince: Long?, seed: Int, waiting: Boolean) {
    if (waiting) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(TetherTheme.colors.warning, pulsing = true, size = 7.dp)
            Spacer(Modifier.width(4.dp))
            Text("Waiting for you", style = MaterialTheme.typography.bodyMedium, color = TetherTheme.colors.warning)
        }
        return
    }
    WorkingIndicator(
        since = status?.since ?: fallbackSince,
        tokens = status?.tokens?.takeIf { it in 1..Int.MAX_VALUE.toLong() }?.toInt(),
        verbSeed = seed,
        label = status?.verb?.let(::statusVerb),
    )
}

// ───────────────────────────── Docks ─────────────────────────────

/** Replaces the composer while a `claude` in a terminal holds the session. */
@Composable
internal fun TerminalHeldBar(machineName: String?) {
    Surface(
        shape = RoundedCornerShape(26.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, TetherTheme.colors.hairline),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(top = 6.dp, bottom = 8.dp),
    ) {
        Row(
            Modifier.padding(horizontal = Space.lg, vertical = Space.md).semantics(mergeDescendants = true) {},
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Terminal, contentDescription = null, tint = TetherTheme.colors.faint, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Text(
                heldByTerminalText(machineName),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Above the composer while Claude has handed its turn back: what it asks the user to do, and its ready-made
 * reply (e.g. `! gh pr merge 16`) as a chip that fills the composer, to edit or send as is.
 */
@Composable
internal fun HandoffPanel(needs: String?, suggestedReply: String?, onUseReply: (String) -> Unit) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, TetherTheme.colors.hairline),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 8.dp),
    ) {
        Column(Modifier.padding(horizontal = Space.lg, vertical = Space.md)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.AutoMirrored.Rounded.Reply, contentDescription = null, tint = TetherTheme.colors.clay, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text("Your turn · Claude asks you to", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!needs.isNullOrBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(needs, style = MaterialTheme.typography.bodyMedium, maxLines = 4, overflow = TextOverflow.Ellipsis)
            }
            if (!suggestedReply.isNullOrBlank()) {
                Spacer(Modifier.height(10.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(TetherTheme.colors.codeBg)
                        .clickable(role = Role.Button, onClickLabel = "Put in the reply box") { onUseReply(suggestedReply) }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        suggestedReply,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text("Use", style = MaterialTheme.typography.labelLarge, color = TetherTheme.colors.clay)
                }
            }
        }
    }
}

internal fun heldByTerminalText(machineName: String?) =
    "Open in a terminal on ${machineName ?: "your computer"} · type /bg there to continue here"

/** Replaces the composer in a subagent's transcript. */
@Composable
internal fun ReadOnlySubagentBar(label: String, onOpenSession: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(26.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, TetherTheme.colors.hairline),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(top = 6.dp, bottom = 8.dp),
    ) {
        Row(Modifier.padding(start = Space.lg, end = Space.sm, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Visibility, contentDescription = null, tint = TetherTheme.colors.faint, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Text(
                "$label · read-only",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onOpenSession) { Text("Session", style = MaterialTheme.typography.labelLarge) }
        }
    }
}

// ───────────────────────────── Subagents ─────────────────────────────

internal fun subagentLabel(s: SubagentInfo): String =
    s.description?.takeIf { it.isNotBlank() }
        ?: s.agentType?.takeIf { it.isNotBlank() && it != "general-purpose" }?.replace('-', ' ')?.replaceFirstChar { it.uppercase() }
        ?: "Subagent ${s.agentId.take(6)}"

/** The session's subagents: one quiet line, tap to list them; each opens its transcript. */
@Composable
internal fun SubagentStrip(subagents: List<SubagentInfo>, onOpen: (SubagentInfo) -> Unit, modifier: Modifier = Modifier) {
    if (subagents.isEmpty()) return
    var expanded by remember { mutableStateOf(false) }
    val running = subagents.count { it.status == SubagentStatus.RUNNING }
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
                    .clickable(role = Role.Button, onClickLabel = if (expanded) "Hide subagents" else "Show subagents") { expanded = !expanded }
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (running > 0) ClaudeSpinner(fontSize = 13f) else StatusDot(TetherTheme.colors.faint, size = 7.dp)
                Spacer(Modifier.width(8.dp))
                Text(
                    when {
                        running > 0 -> "$running of ${subagents.size} subagents running"
                        subagents.size == 1 -> "1 subagent"
                        else -> "${subagents.size} subagents"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                val rot by animateFloatAsState(if (expanded) 0f else -90f, label = "subChev")
                Icon(Icons.Rounded.ExpandMore, contentDescription = null, tint = TetherTheme.colors.faint, modifier = Modifier.size(18.dp).rotate(rot))
            }
            AnimatedVisibility(expanded, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                Column(Modifier.padding(bottom = 6.dp)) {
                    for (s in subagents.sortedByDescending { it.status == SubagentStatus.RUNNING }) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 44.dp)
                                .clickable(role = Role.Button, onClickLabel = "Open transcript") { onOpen(s) }
                                .padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (s.status == SubagentStatus.RUNNING) ClaudeSpinner(fontSize = 12f)
                            else StatusDot(TetherTheme.colors.success, size = 6.dp)
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(subagentLabel(s), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                val meta = listOfNotNull(
                                    s.agentType?.takeIf { it.isNotBlank() },
                                    s.model?.let { modelLabel(it, emptyList()) },
                                    "background".takeIf { s.background },
                                ).joinToString(" · ")
                                if (meta.isNotEmpty()) Text(meta, style = MaterialTheme.typography.labelSmall, color = TetherTheme.colors.faint, maxLines = 1)
                            }
                            Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null, tint = TetherTheme.colors.faint, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
        }
    }
}

// ───────────────────────────── Dialogs (decision 5) ─────────────────────────────

/**
 * A startup / session dialog the session is blocked on (MCP servers, folder trust, notices…).
 * Known dialogs get a native panel; anything else shows the screen text and a key pad. Every
 * button is a key press in the session's terminal.
 */
@Composable
internal fun SessionDialogPanel(dialog: SessionPending.Dialog, busy: Boolean, onKeys: (List<SessionKey>) -> Unit, modifier: Modifier = Modifier) {
    val haptics = rememberHaptics()
    LaunchedEffect(dialog.title, dialog.dialog) { haptics.confirm() }
    val maxPanel = (LocalConfiguration.current.screenHeightDp * 0.55f).dp
    val c = TetherTheme.colors
    val shape = RoundedCornerShape(24.dp)
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(top = Space.sm)
            .clip(shape)
            .background(c.composer)
            .border(1.dp, c.warning.copy(alpha = if (c.isDark) 0.35f else 0.45f), shape)
            .heightIn(max = maxPanel)
            .padding(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                when (dialog.dialog) {
                    DialogKind.MCP_SERVERS -> Icons.Rounded.Dns
                    DialogKind.TRUST -> Icons.Rounded.FolderOpen
                    DialogKind.OTHER -> Icons.Rounded.Keyboard
                },
                contentDescription = null,
                tint = c.warning,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                dialogTitle(dialog),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (busy) ClaudeSpinner(fontSize = 13f)
        }
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(top = 8.dp)) {
            when (dialog.dialog) {
                DialogKind.MCP_SERVERS -> McpServersBody(dialog, busy, onKeys)
                DialogKind.TRUST -> TrustBody(dialog)
                DialogKind.OTHER -> ScreenBody(dialog)
            }
        }
        Spacer(Modifier.height(12.dp))
        when (dialog.dialog) {
            DialogKind.MCP_SERVERS -> Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                SecondaryButton("Reject all", onClick = { haptics.confirm(); onKeys(listOf(SessionKey.Esc)) }, enabled = !busy, modifier = Modifier.weight(1f))
                PrimaryButton("Continue", onClick = { haptics.confirm(); onKeys(mcpContinueKeys(dialog)) }, enabled = !busy, modifier = Modifier.weight(1f))
            }
            else -> {
                OptionButtons(dialog, busy) { haptics.confirm(); onKeys(it) }
                KeyPad(dialog.keys.ifEmpty { DefaultDialogKeys }, busy) { haptics.tick(); onKeys(it) }
            }
        }
    }
}

internal fun dialogTitle(d: SessionPending.Dialog): String = d.title.trim().ifEmpty {
    when (d.dialog) {
        DialogKind.MCP_SERVERS -> "New MCP servers found in this project"
        DialogKind.TRUST -> "Do you trust the files in this folder?"
        DialogKind.OTHER -> "Claude Code is asking something"
    }
}

/**
 * Keys that select option [index] of a list dialog when the helper gave no key of its own: the
 * terminal's list cursor starts at the first option, so Down × index then Enter.
 */
internal fun optionKeys(dialog: SessionPending.Dialog, index: Int): List<SessionKey> {
    val own = dialog.options.getOrNull(index)?.key?.takeIf { it.isNotBlank() }
    if (own != null) return listOf(dialogKey(own))
    return List(index) { SessionKey.Down } + SessionKey.Enter
}

/** MCP servers: a checklist; each tap toggles that server in the terminal (its key, else ↓… Space). */
@Composable
private fun McpServersBody(dialog: SessionPending.Dialog, busy: Boolean, onKeys: (List<SessionKey>) -> Unit) {
    if (dialog.body.isNotBlank()) {
        Text(dialog.body.trim(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 6.dp))
    }
    dialog.options.forEachIndexed { i, o ->
        val checked = o.checked == true
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .clip(RoundedCornerShape(10.dp))
                .clickable(enabled = !busy, role = Role.Checkbox, onClickLabel = if (checked) "Turn off" else "Turn on") {
                    onKeys(toggleKeys(dialog, i))
                }
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (checked) Icons.Rounded.CheckBox else Icons.Rounded.CheckBoxOutlineBlank,
                contentDescription = null,
                tint = if (checked) TetherTheme.colors.clay else TetherTheme.colors.faint,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(o.label, style = TetherTheme.type.monoSmall, color = MaterialTheme.colorScheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
    Text(
        "Continue enables the checked servers; Reject all starts without them.",
        style = MaterialTheme.typography.labelSmall,
        color = TetherTheme.colors.faint,
        modifier = Modifier.padding(top = 6.dp),
    )
}

/**
 * Toggling a checklist row: its own key when the helper gave one, else move the cursor there and
 * press Space. The cursor is wherever the user left it, so anchor at the bottom first: in Claude
 * Code's MCP checklist (verified live on 2.1.287) Down stops at the "Enable selected" row under the
 * last option while Up wraps from the first option to the last, so Down × (n + 1) always lands on
 * "Enable selected" and Up × (n − index) then reaches the row.
 */
internal fun toggleKeys(dialog: SessionPending.Dialog, index: Int): List<SessionKey> {
    val own = dialog.options.getOrNull(index)?.key?.takeIf { it.isNotBlank() }
    if (own != null) return listOf(dialogKey(own))
    val n = dialog.options.size
    return List(n + 1) { SessionKey.Down } + List(n - index) { SessionKey.Up } + SessionKey.Space
}

/** MCP checklist "Continue": Enter on a server row toggles it, so go down to "Enable selected" first. */
internal fun mcpContinueKeys(dialog: SessionPending.Dialog): List<SessionKey> =
    List(dialog.options.size + 1) { SessionKey.Down } + SessionKey.Enter

@Composable
private fun TrustBody(dialog: SessionPending.Dialog) {
    Text(
        dialog.body.trim().ifEmpty { "Claude Code will read, edit and run files in this folder." },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Unknown dialog: what the terminal shows, as-is (or, when nothing could be read, how to answer it). */
@Composable
private fun ScreenBody(dialog: SessionPending.Dialog) {
    if (dialog.body.isBlank()) {
        if (dialog.options.isEmpty()) {
            Text(
                "Couldn't read this prompt from the machine's screen. Answer it with the keys below, or at the terminal.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(TetherTheme.colors.codeBg)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(dialog.body.trimEnd(), style = TetherTheme.type.monoSmall, color = TetherTheme.colors.codeText, softWrap = false)
    }
}

@Composable
private fun OptionButtons(dialog: SessionPending.Dialog, busy: Boolean, onKeys: (List<SessionKey>) -> Unit) {
    if (dialog.options.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
        dialog.options.forEachIndexed { i, o ->
            if (i == 0) PrimaryButton(o.label, onClick = { onKeys(optionKeys(dialog, i)) }, enabled = !busy, modifier = Modifier.fillMaxWidth())
            else SecondaryButton(o.label, onClick = { onKeys(optionKeys(dialog, i)) }, enabled = !busy, modifier = Modifier.fillMaxWidth())
        }
    }
}

/** ↑ ↓ Space Enter Esc and digits: anything the dialog in the terminal might need. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun KeyPad(keys: List<String>, busy: Boolean, onKeys: (List<SessionKey>) -> Unit) {
    FlowRow(
        Modifier.fillMaxWidth().padding(top = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for (k in keys) {
            val shape = RoundedCornerShape(10.dp)
            Box(
                Modifier
                    .widthIn(min = 44.dp)
                    .heightIn(min = 40.dp)
                    .clip(shape)
                    .border(1.dp, TetherTheme.colors.hairline, shape)
                    .clickable(enabled = !busy, role = Role.Button) { onKeys(listOf(dialogKey(k))) }
                    .semantics { contentDescription = "Press $k" }
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(keyLabel(k), style = TetherTheme.type.monoSmall, color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

// ───────────────────────────── Menu & confirmations ─────────────────────────────

@Composable
internal fun SessionOverflowMenu(
    rawView: Boolean,
    canStop: Boolean,
    canRemove: Boolean,
    onCopySession: () -> Unit,
    onOpenMachine: () -> Unit,
    onToggleRaw: () -> Unit,
    onStop: () -> Unit,
    onRemove: () -> Unit,
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
                onClick = { open = false; onCopySession() },
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
            if (canStop || canRemove) HorizontalDivider(color = TetherTheme.colors.hairline, modifier = Modifier.padding(vertical = 4.dp))
            if (canStop) {
                DropdownMenuItem(
                    text = { Text("Stop session") },
                    leadingIcon = { Icon(Icons.Rounded.StopCircle, contentDescription = null) },
                    onClick = { open = false; onStop() },
                )
            }
            if (canRemove) {
                DropdownMenuItem(
                    text = { Text("Remove session", color = TetherTheme.colors.danger) },
                    leadingIcon = { Icon(Icons.Rounded.DeleteOutline, contentDescription = null, tint = TetherTheme.colors.danger) },
                    onClick = { open = false; onRemove() },
                )
            }
        }
    }
}

@Composable
internal fun ConfirmSessionDialog(
    title: String,
    body: String,
    confirm: String,
    danger: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = { Text(title, style = MaterialTheme.typography.headlineSmall) },
        text = { Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirm, color = if (danger) TetherTheme.colors.danger else MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", style = MaterialTheme.typography.labelLarge) } },
    )
}
