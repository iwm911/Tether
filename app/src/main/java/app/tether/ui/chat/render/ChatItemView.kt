package app.tether.ui.chat.render

import androidx.compose.ui.text.font.FontWeight
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Compress
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.StopCircle
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.tether.core.ChatItem
import app.tether.core.NoticeKind
import app.tether.ui.components.Hairline
import app.tether.ui.components.ShimmerText
import app.tether.ui.components.compactNumber
import app.tether.ui.components.formatCost
import app.tether.ui.components.formatElapsed
import app.tether.ui.components.rememberHaptics
import app.tether.ui.theme.Mono
import app.tether.ui.theme.Motion
import app.tether.ui.theme.TetherTheme
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

/**
 * Renders one conversation row. Emits nothing for [ChatItem.Permission], hidden tools (TodoWrite)
 * and — when [showThinking] is false — thinking. Use [isRenderable] to filter list items up front.
 * Applies no horizontal gutter of its own; the screen supplies it.
 */
@Composable
fun ChatItemView(
    item: ChatItem,
    modifier: Modifier = Modifier,
    showThinking: Boolean = true,
    compactTools: Boolean = true,
) {
    ChatItemContent(item, modifier, showThinking, compactTools, depth = 0)
}

/** True when [ChatItemView] draws something for this item (lets a list skip empty rows). */
fun ChatItem.isRenderable(showThinking: Boolean = true): Boolean = when (this) {
    is ChatItem.Permission -> false
    is ChatItem.ToolCall -> name !in ToolPresentation.hiddenTools
    is ChatItem.Thinking -> showThinking
    is ChatItem.AssistantText -> streaming || text.isNotBlank()
    else -> true
}

@Composable
internal fun ChatItemContent(
    item: ChatItem,
    modifier: Modifier,
    showThinking: Boolean,
    compactTools: Boolean,
    depth: Int,
) {
    when (item) {
        is ChatItem.User -> UserBubble(item, modifier)
        is ChatItem.AssistantText -> if (item.streaming || item.text.isNotBlank()) {
            Markdown(item.text, modifier.fillMaxWidth(), streaming = item.streaming)
        }
        is ChatItem.Thinking -> if (showThinking) ThinkingView(item, modifier)
        is ChatItem.ToolCall -> if (item.name !in ToolPresentation.hiddenTools) {
            ToolCallView(item, modifier, showThinking = showThinking, compactTools = compactTools, depth = depth)
        }
        is ChatItem.Permission -> Unit
        is ChatItem.TurnSummary -> TurnSummaryView(item, modifier)
        is ChatItem.Notice -> NoticeView(item, modifier)
    }
}

// ───────────────────────────── User ─────────────────────────────

private val SLASH_COMMAND = Regex("^/[\\w:.\\-]+")

@Composable
private fun UserBubble(item: ChatItem.User, modifier: Modifier) {
    val c = TetherTheme.colors
    val clipboard = LocalClipboardManager.current
    val haptics = rememberHaptics()
    val longPress = app.tether.ui.chat.LocalUserLongPress.current
    var selectable by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    var showTime by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) { delay(2400); copied = false }
    }
    val text = remember(item.text, c.clay) {
        val m = SLASH_COMMAND.find(item.text)
        if (m == null) AnnotatedString(item.text)
        else buildAnnotatedString {
            withStyle(SpanStyle(fontFamily = Mono, color = c.clay)) { append(m.value) }
            append(item.text.substring(m.range.last + 1))
        }
    }
    val shape = RoundedCornerShape(20.dp)
    val textStyle = MaterialTheme.typography.bodyLarge.copy(color = c.onUserBubble)

    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Column(Modifier.fillMaxWidth(0.85f), horizontalAlignment = Alignment.End) {
            Column(
                Modifier
                    .clip(shape)
                    .background(c.userBubble)
                    .then(if (item.queued) Modifier.border(1.dp, c.clay.copy(alpha = 0.35f), shape) else Modifier)
                    .combinedClickable(
                        onClickLabel = "Show time",
                        onLongClickLabel = if (longPress != null) "Message actions" else "Copy message",
                        onLongClick = {
                            haptics.confirm()
                            if (longPress != null) longPress(item)
                            else {
                                clipboard.setText(AnnotatedString(item.text))
                                copied = true
                            }
                        },
                        onClick = { if (item.timestamp != null) showTime = !showTime },
                    )
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalAlignment = Alignment.Start,
            ) {
                if (item.imageCount > 0) {
                    Row(
                        Modifier
                            .clip(CircleShape)
                            .background(c.onUserBubble.copy(alpha = 0.08f))
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.Image, contentDescription = null, tint = c.onUserBubble.copy(alpha = 0.75f), modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            if (item.imageCount == 1) "1 image" else "${item.imageCount} images",
                            style = MaterialTheme.typography.labelMedium,
                            color = c.onUserBubble.copy(alpha = 0.75f),
                        )
                    }
                    if (item.text.isNotBlank()) Spacer(Modifier.height(8.dp))
                }
                if (item.text.isNotBlank()) {
                    if (selectable) SelectionContainer { Text(text, style = textStyle) }
                    else Text(text, style = textStyle)
                }
            }

            AnimatedVisibility(
                visible = item.queued || copied || (showTime && item.timestamp != null),
                enter = expandVertically(tween(Motion.Short)) + fadeIn(tween(Motion.Short)),
                exit = shrinkVertically(tween(Motion.Short)) + fadeOut(tween(Motion.Short)),
            ) {
                Row(
                    Modifier.padding(top = 4.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (showTime && item.timestamp != null) {
                        val t = remember(item.timestamp) { DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(item.timestamp)) }
                        Text(t, style = MaterialTheme.typography.labelSmall, color = c.faint)
                    }
                    if (copied) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Check, contentDescription = null, tint = c.success, modifier = Modifier.size(13.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Copied", style = MaterialTheme.typography.labelSmall, color = c.success)
                        }
                        if (!selectable && item.text.isNotBlank()) {
                            Text(
                                "Select text",
                                style = MaterialTheme.typography.labelSmall,
                                color = c.clay,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable(role = Role.Button) { selectable = true; copied = false }
                                    .padding(horizontal = 6.dp, vertical = 4.dp),
                            )
                        }
                    }
                    if (item.queued) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Schedule, contentDescription = null, tint = c.clay, modifier = Modifier.size(13.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Queued", style = MaterialTheme.typography.labelSmall, color = c.clay)
                        }
                    }
                }
            }
        }
    }
}

// ───────────────────────────── Thinking ─────────────────────────────

@Composable
private fun ThinkingView(item: ChatItem.Thinking, modifier: Modifier) {
    val c = TetherTheme.colors
    val haptics = rememberHaptics()
    var expanded by rememberSaveable(item.key) { mutableStateOf(false) }
    // Measure how long we watched it think (live runs); history falls back to token counts.
    var startedAt by remember(item.key) { mutableLongStateOf(0L) }
    var durationMs by rememberSaveable(item.key) { mutableStateOf(-1L) }
    LaunchedEffect(item.streaming) {
        val now = System.currentTimeMillis()
        if (item.streaming) {
            if (startedAt == 0L) startedAt = now
        } else if (startedAt != 0L && durationMs < 0) {
            durationMs = now - startedAt
        }
    }
    val hasText = item.text.isNotBlank()
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, Motion.gentle(), label = "thinkChevron")
    val style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Normal)
    val doneLabel = when {
        durationMs >= 1000 -> "Thought for ${formatElapsed(durationMs)}"
        else -> "Thought"
    }

    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier
                .clip(RoundedCornerShape(10.dp))
                .clickable(enabled = hasText, role = Role.Button, onClickLabel = if (expanded) "Hide thinking" else "Show thinking") {
                    haptics.tick(); expanded = !expanded
                }
                .heightIn(min = 30.dp)
                .padding(horizontal = 4.dp, vertical = 4.dp)
                .semantics { contentDescription = if (item.streaming) "Thinking" else doneLabel },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("✻", style = style, color = if (item.streaming) c.clay else c.faint.copy(alpha = 0.7f))
            Spacer(Modifier.width(6.dp))
            if (item.streaming) {
                ShimmerText("Thinking…", style, base = c.faint)
                val tokens = item.estimatedTokens
                if (tokens != null && tokens > 0) {
                    Spacer(Modifier.width(8.dp))
                    Text("↓ ${compactNumber(tokens.toLong())} tokens", style = MaterialTheme.typography.bodySmall, color = c.faint)
                }
            } else {
                Text(doneLabel, style = style, color = c.faint.copy(alpha = 0.85f))
            }
            if (hasText) {
                Spacer(Modifier.width(4.dp))
                Icon(Icons.Rounded.ExpandMore, contentDescription = null, tint = c.faint.copy(alpha = 0.7f), modifier = Modifier.size(14.dp).rotate(rotation))
            }
        }
        AnimatedVisibility(
            visible = expanded && hasText,
            enter = expandVertically(tween(Motion.Medium, easing = Motion.Emphasized)) + fadeIn(tween(Motion.Medium)),
            exit = shrinkVertically(tween(Motion.Short)) + fadeOut(tween(Motion.Short)),
        ) {
            val rule = c.faint.copy(alpha = 0.45f)
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 8.dp, top = 2.dp, bottom = 6.dp)
                    .drawBehind {
                        val w = 2.dp.toPx()
                        drawRoundRect(rule, size = Size(w, size.height), cornerRadius = CornerRadius(w / 2, w / 2))
                    }
                    .padding(start = 14.dp)
            ) {
                MarkdownContent(item.text, tone = MdTone.THOUGHT, streaming = item.streaming)
            }
        }
    }
}

// ───────────────────────────── Turn summary ─────────────────────────────

@Composable
private fun TurnSummaryView(item: ChatItem.TurnSummary, modifier: Modifier) {
    val c = TetherTheme.colors
    val onPlan = app.tether.ui.components.LocalPlanName.current != null
    val parts = buildList {
        item.numTurns?.takeIf { it > 0 }?.let { add(if (it == 1) "1 turn" else "$it turns") }
        item.durationMs?.takeIf { it > 0 }?.let { add(formatElapsed(it)) }
        // On a Claude plan the CLI's dollar figure is an API-equivalent estimate, not a charge.
        if (!onPlan) item.costUsd?.takeIf { it > 0 }?.let { add(formatCost(it)) }
    }
    val meta = parts.joinToString(" · ")
    Column(
        modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .semantics(mergeDescendants = true) {},
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (item.success) {
            Text(
                "✓  " + meta.ifEmpty { "Done" },
                style = MaterialTheme.typography.labelSmall,
                color = c.faint,
                textAlign = TextAlign.Center,
            )
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.ErrorOutline, contentDescription = null, tint = c.danger, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    if (meta.isEmpty()) "Turn failed" else "Turn failed · $meta",
                    style = MaterialTheme.typography.labelSmall,
                    color = c.danger,
                )
            }
            val err = item.errorText?.trim()
            if (!err.isNullOrEmpty()) {
                Spacer(Modifier.height(4.dp))
                SelectionContainer {
                    Text(
                        err,
                        style = MaterialTheme.typography.bodySmall,
                        color = c.danger,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.widthIn(max = 340.dp).padding(horizontal = 16.dp),
                    )
                }
            }
        }
    }
}

// ───────────────────────────── Notice ─────────────────────────────

private data class NoticeLook(val icon: ImageVector, val color: Color)

@Composable
private fun NoticeView(item: ChatItem.Notice, modifier: Modifier) {
    val c = TetherTheme.colors
    val look = when (item.kind) {
        NoticeKind.INFO -> NoticeLook(Icons.Rounded.Info, c.faint)
        NoticeKind.WARNING -> NoticeLook(Icons.Rounded.WarningAmber, c.warning)
        NoticeKind.ERROR -> NoticeLook(Icons.Rounded.ErrorOutline, c.danger)
        NoticeKind.MODE_CHANGE -> NoticeLook(Icons.Rounded.Tune, c.clay)
        NoticeKind.MODEL_CHANGE -> NoticeLook(Icons.Rounded.AutoAwesome, c.info)
        NoticeKind.INTERRUPTED -> NoticeLook(Icons.Rounded.StopCircle, c.faint)
        NoticeKind.COMPACTED -> NoticeLook(Icons.Rounded.Compress, c.faint)
        NoticeKind.SESSION_START -> NoticeLook(Icons.Rounded.PlayCircle, c.faint)
    }
    Row(
        modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.widthIn(max = 320.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(look.icon, contentDescription = null, tint = look.color.copy(alpha = 0.8f), modifier = Modifier.size(13.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                item.text.replaceFirstChar { it.uppercase() },
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Normal),
                color = look.color,
                textAlign = TextAlign.Center,
                maxLines = 2,
            )
        }
    }
}
