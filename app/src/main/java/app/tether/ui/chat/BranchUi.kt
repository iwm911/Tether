package app.tether.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.CallSplit
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.tether.core.ChatItem
import app.tether.core.RewindResult
import app.tether.ui.components.ClaudeSpinner
import app.tether.ui.components.PrimaryButton
import app.tether.ui.components.TetherTextField
import app.tether.ui.components.projectName
import app.tether.ui.components.rememberHaptics
import app.tether.ui.theme.Space
import app.tether.ui.theme.TetherTheme
import kotlinx.coroutines.CancellationException

/*
 * Editing history the Claude Code way: nothing is ever rewritten. Editing a message, retrying it
 * or forking at a reply starts a BRANCH — a new live run resumed from the same session up to that
 * point (`--resume <session> --fork-session --resume-session-at <uuid>`). The original stays as
 * it was. Optionally files are restored to how they were before the edited message (Claude Code's
 * checkpoints, `rewind_files`) — previewed first, never silently.
 */

/** What the chat list can do with messages. Null handlers hide the action. */
class MessageActions(
    val onUserMessage: (ChatItem.User) -> Unit,
    /** Keep everything up to this reply, continue in a branch. */
    val onBranchAfter: ((ChatItem.AssistantText) -> Unit)?,
    /** Re-run the prompt that led to this reply, in a branch. */
    val onRetryTurn: ((ChatItem.User) -> Unit)?,
)

/** Long-press on a user bubble; null = the bubble falls back to "copy". */
val LocalUserLongPress = staticCompositionLocalOf<((ChatItem.User) -> Unit)?> { null }

/** Quiet icon row under Claude's last reply of a turn: Copy · Branch from here · Retry. */
@Composable
internal fun MessageActionRow(
    reply: ChatItem.AssistantText,
    turnPrompt: ChatItem.User?,
    actions: MessageActions,
    modifier: Modifier = Modifier,
) {
    val clipboard = LocalClipboardManager.current
    val haptics = rememberHaptics()
    var copied by remember(reply.key) { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { kotlinx.coroutines.delay(1800); copied = false } }
    Row(modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        SmallAction(Icons.Rounded.ContentCopy, if (copied) "Copied" else "Copy") {
            clipboard.setText(AnnotatedString(reply.text)); haptics.confirm(); copied = true
        }
        if (actions.onBranchAfter != null && reply.uuid != null) {
            SmallAction(Icons.AutoMirrored.Rounded.CallSplit, "Branch") { actions.onBranchAfter.invoke(reply) }
        }
        if (actions.onRetryTurn != null && turnPrompt?.uuid != null) {
            SmallAction(Icons.Rounded.Refresh, "Retry") { actions.onRetryTurn.invoke(turnPrompt) }
        }
    }
}

@Composable
private fun SmallAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(role = Role.Button, onClickLabel = label, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = TetherTheme.colors.faint, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(5.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = TetherTheme.colors.faint)
    }
}

/** Sheet for a long-pressed user message. */
@Composable
internal fun UserMessageSheet(
    message: ChatItem.User,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onRetry: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val haptics = rememberHaptics()
    val branchable = message.uuid != null
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = Space.md)) {
            Text(
                message.text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = Space.gutter, vertical = Space.sm),
            )
            SheetRow(Icons.Rounded.Edit, "Edit & branch", "Change this message and continue in a new branch", branchable) { onDismiss(); onEdit() }
            SheetRow(Icons.Rounded.Refresh, "Retry", "Send it again in a new branch", branchable) { onDismiss(); onRetry() }
            SheetRow(Icons.Rounded.ContentCopy, "Copy", null, true) {
                clipboard.setText(AnnotatedString(message.text)); haptics.confirm(); onDismiss()
            }
            if (!branchable) {
                Text(
                    "Branching is available once Claude has received this message.",
                    style = MaterialTheme.typography.labelSmall,
                    color = TetherTheme.colors.faint,
                    modifier = Modifier.padding(horizontal = Space.gutter, vertical = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun SheetRow(icon: ImageVector, title: String, subtitle: String?, enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = Space.gutter, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val tint = if (enabled) MaterialTheme.colorScheme.onSurface else TetherTheme.colors.faint
        Icon(icon, contentDescription = null, tint = if (enabled) TetherTheme.colors.clay else TetherTheme.colors.faint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(16.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = tint)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = TetherTheme.colors.faint)
        }
    }
}

/**
 * Edit (or retry) a message into a branch. Shows the file-restore option only when Claude Code
 * has a checkpoint for that message, with a preview of what would change.
 */
@Composable
internal fun EditBranchSheet(
    message: ChatItem.User,
    retry: Boolean,
    onDismiss: () -> Unit,
    previewRewind: suspend (String) -> RewindResult,
    onBranch: (text: String, restoreFiles: Boolean) -> Unit,
    branching: Boolean,
) {
    val haptics = rememberHaptics()
    var text by remember(message.key) { mutableStateOf(message.text) }
    var restore by remember(message.key) { mutableStateOf(false) }
    var preview by remember(message.key) { mutableStateOf<RewindResult?>(null) }
    var loadingPreview by remember(message.key) { mutableStateOf(true) }
    LaunchedEffect(message.key) {
        val id = message.uuid
        preview = if (id == null) null else try {
            previewRewind(id)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            null
        }
        loadingPreview = false
    }
    val files = preview?.takeIf { it.canRewind && it.error == null }?.filesChanged.orEmpty()
    ModalBottomSheet(onDismissRequest = { if (!branching) onDismiss() }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().imePadding().navigationBarsPadding().padding(horizontal = Space.gutter).padding(bottom = Space.lg)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.AutoMirrored.Rounded.CallSplit, contentDescription = null, tint = TetherTheme.colors.clay, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text(if (retry) "Retry in a new branch" else "Edit & branch", style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "Claude continues from just before this message. The original conversation stays as it is.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(Space.md))
            TetherTextField(
                value = text,
                onValueChange = { text = it },
                label = "Message",
                singleLine = false,
                minLines = 2,
            )
            Spacer(Modifier.height(Space.md))
            when {
                loadingPreview -> Row(verticalAlignment = Alignment.CenterVertically) {
                    ClaudeSpinner(fontSize = 13f)
                    Spacer(Modifier.width(8.dp))
                    Text("Checking file checkpoints…", style = MaterialTheme.typography.bodySmall, color = TetherTheme.colors.faint)
                }
                files.isNotEmpty() -> {
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { haptics.tick(); restore = !restore }.padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.History, contentDescription = null, tint = TetherTheme.colors.faint, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Also restore files", style = MaterialTheme.typography.bodyLarge)
                            val p = preview!!
                            Text(
                                "${files.size} file${if (files.size == 1) "" else "s"} back to before this message (+${p.insertions} −${p.deletions})",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = restore,
                            onCheckedChange = { haptics.tick(); restore = it },
                            colors = SwitchDefaults.colors(checkedTrackColor = MaterialTheme.colorScheme.primary, checkedThumbColor = MaterialTheme.colorScheme.onPrimary),
                        )
                    }
                    AnimatedVisibility(restore, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                        Column(Modifier.padding(start = 32.dp, top = 2.dp)) {
                            for (f in files.take(8)) {
                                Text(projectName(f), style = TetherTheme.type.monoSmall, color = TetherTheme.colors.codeText, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            if (files.size > 8) Text("+${files.size - 8} more", style = MaterialTheme.typography.labelSmall, color = TetherTheme.colors.faint)
                            Text(
                                "Changes made after this message are undone on the machine.",
                                style = MaterialTheme.typography.labelSmall,
                                color = TetherTheme.colors.warning,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                }
                else -> Text(
                    "Files stay as they are now.",
                    style = MaterialTheme.typography.labelSmall,
                    color = TetherTheme.colors.faint,
                )
            }
            Spacer(Modifier.height(Space.lg))
            PrimaryButton(
                if (retry) "Retry" else "Branch & send",
                onClick = { haptics.confirm(); onBranch(text.trim(), restore && files.isNotEmpty()) },
                enabled = text.isNotBlank() && !branching,
                loading = branching,
                modifier = Modifier.fillMaxWidth(),
                icon = Icons.AutoMirrored.Rounded.CallSplit,
            )
        }
    }
}

/** For the list: the last Claude reply of each turn, and the prompt that started that turn. */
internal fun turnEnds(items: List<ChatItem>): Map<String, ChatItem.User?> {
    val out = HashMap<String, ChatItem.User?>()
    var prompt: ChatItem.User? = null
    var lastReply: ChatItem.AssistantText? = null
    fun close() { lastReply?.let { if (!it.streaming) out[it.key] = prompt }; lastReply = null }
    for (it in items) {
        when (it) {
            is ChatItem.User -> { close(); prompt = it }
            is ChatItem.AssistantText -> lastReply = it
            is ChatItem.TurnSummary -> close()
            else -> Unit
        }
    }
    close()
    return out
}
