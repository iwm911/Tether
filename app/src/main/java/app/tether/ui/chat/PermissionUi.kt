package app.tether.ui.chat

import androidx.compose.ui.unit.sp

import androidx.compose.animation.core.animateFloatAsState

import androidx.compose.material.icons.rounded.ExpandMore

import androidx.compose.ui.semantics.Role

import androidx.compose.foundation.border

import androidx.compose.foundation.clickable

import androidx.compose.ui.draw.rotate

import androidx.compose.ui.draw.shadow

import androidx.compose.ui.platform.LocalConfiguration

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.QuestionAnswer
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Gavel
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.tether.core.ChatItem
import app.tether.core.PermissionDecision
import app.tether.core.PermissionState
import app.tether.core.SEND_MESSAGE
import app.tether.core.SendMessageInput
import app.tether.core.WORKFLOW_TOOL
import app.tether.core.WorkflowMeta
import app.tether.core.parseSendMessage
import app.tether.ui.chat.render.CodeBlock
import app.tether.ui.chat.render.ToolPresentation
import app.tether.ui.components.CodeChip
import app.tether.ui.components.PrimaryButton
import app.tether.ui.components.SecondaryButton
import app.tether.ui.components.StatusDot
import app.tether.ui.components.TetherTextField
import app.tether.ui.components.prettyPath
import app.tether.ui.components.rememberHaptics
import app.tether.ui.theme.Motion
import app.tether.ui.theme.Space
import app.tether.ui.theme.TetherTheme
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// ───────────────────────────── Summary model ─────────────────────────────

/** One line of a compact diff / file preview inside a permission prompt. */
internal data class PreviewLine(val kind: Char, val text: String)

internal data class PermissionSummary(
    /** "run a command", "edit a file"… — completes "Claude wants to …". */
    val action: String,
    /** Short verb for collapsed rows ("Bash", "Edit"). */
    val verb: String,
    /** Short mono target for collapsed rows. */
    val target: String,
    val icon: ImageVector?,
    val command: String? = null,
    val path: String? = null,
    val link: String? = null,
    val note: String? = null,
    val preview: List<PreviewLine> = emptyList(),
    val previewTruncated: Boolean = false,
    val fields: List<Pair<String, String>> = emptyList(),
    /** A message to another session (`SendMessage`): the panel shows it as the message it is. */
    val message: SendMessageInput? = null,
)

private val PermJson = Json { ignoreUnknownKeys = true; isLenient = true }

private fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }

private fun JsonObject.num(key: String): Long? =
    (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toDoubleOrNull()?.toLong()

private fun JsonElement.compact(max: Int = 160): String {
    val s = when (this) {
        is JsonPrimitive -> content
        else -> toString()
    }
    return if (s.length > max) s.take(max - 1) + "…" else s
}

private fun lineCount(s: String?): Int = if (s.isNullOrEmpty()) 0 else s.trimEnd('\n').count { it == '\n' } + 1

private fun plural(n: Int, one: String, many: String = one + "s") = "$n ${if (n == 1) one else many}"

private const val PreviewMax = 10

private fun diffPreview(old: String?, new: String?): Pair<List<PreviewLine>, Boolean> {
    val o = old?.trimEnd('\n')?.split('\n') ?: emptyList()
    val n = new?.trimEnd('\n')?.split('\n') ?: emptyList()
    val lines = o.map { PreviewLine('-', it) } + n.map { PreviewLine('+', it) }
    return lines.take(PreviewMax) to (lines.size > PreviewMax)
}

/** Turns a `can_use_tool` request into something a human can decide on at a glance. Pure; cheap. */
internal fun summarizePermission(toolName: String, inputJson: String): PermissionSummary {
    val label = runCatching { ToolPresentation.describe(toolName, inputJson) }.getOrNull()
    val obj = runCatching { PermJson.parseToJsonElement(inputJson) as? JsonObject }.getOrNull() ?: JsonObject(emptyMap())
    val verb = label?.verb?.takeIf { it.isNotBlank() } ?: toolName
    val target = label?.target.orEmpty()
    val icon = label?.icon
    return when (toolName) {
        "Bash", "BashOutput" -> PermissionSummary(
            action = "run a command", verb = verb, target = target.ifEmpty { obj.str("command").orEmpty() }, icon = icon,
            command = obj.str("command"), note = obj.str("description"),
        )
        "Edit" -> {
            val old = obj.str("old_string")
            val new = obj.str("new_string")
            val (preview, more) = diffPreview(old, new)
            val all = (obj["replace_all"] as? JsonPrimitive)?.content == "true"
            PermissionSummary(
                action = "edit a file", verb = verb, target = target, icon = icon,
                path = obj.str("file_path"),
                note = "Replaces ${plural(lineCount(old), "line")} with ${lineCount(new)}" + if (all) " · every occurrence" else "",
                preview = preview, previewTruncated = more,
            )
        }
        "MultiEdit" -> {
            val edits = (obj["edits"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            val first = edits.firstOrNull()
            val (preview, more) = diffPreview(first?.str("old_string"), first?.str("new_string"))
            PermissionSummary(
                action = "edit a file", verb = verb, target = target, icon = icon,
                path = obj.str("file_path"), note = plural(edits.size, "edit") + " in this file",
                preview = preview, previewTruncated = more || edits.size > 1,
            )
        }
        "Write" -> {
            val content = obj.str("content")
            val lines = content?.trimEnd('\n')?.split('\n').orEmpty()
            PermissionSummary(
                action = "write a file", verb = verb, target = target, icon = icon,
                path = obj.str("file_path"), note = if (content != null) "Writes ${plural(lines.size, "line")}" else null,
                preview = lines.take(PreviewMax).map { PreviewLine('+', it) }, previewTruncated = lines.size > PreviewMax,
            )
        }
        "NotebookEdit" -> PermissionSummary(
            action = "edit a notebook", verb = verb, target = target, icon = icon,
            path = obj.str("notebook_path"), note = obj.str("edit_mode")?.let { "Mode: $it" },
            preview = obj.str("new_source")?.split('\n').orEmpty().take(PreviewMax).map { PreviewLine('+', it) },
        )
        "Read" -> {
            val off = obj.num("offset")
            val lim = obj.num("limit")
            PermissionSummary(
                action = "read a file", verb = verb, target = target, icon = icon, path = obj.str("file_path"),
                note = when {
                    off != null && lim != null -> "Lines $off–${off + lim}"
                    lim != null -> "First $lim lines"
                    else -> null
                },
            )
        }
        "WebFetch" -> PermissionSummary(
            action = "fetch a web page", verb = verb, target = target, icon = icon,
            link = obj.str("url"), note = obj.str("prompt"),
        )
        "WebSearch" -> PermissionSummary(
            action = "search the web", verb = verb, target = target, icon = icon, link = obj.str("query"),
        )
        "Glob", "Grep" -> PermissionSummary(
            action = "search your files", verb = verb, target = target, icon = icon,
            link = obj.str("pattern"), path = obj.str("path"),
        )
        WORKFLOW_TOOL -> {
            val meta = WorkflowMeta.of(obj)
            PermissionSummary(
                action = "run a multi-agent workflow", verb = verb, target = target, icon = icon,
                note = meta.description ?: meta.name,
                fields = listOfNotNull(
                    meta.name?.takeIf { meta.description != null }?.let { "name" to it },
                    meta.phases.takeIf { it.isNotEmpty() }?.let { "phases" to it.joinToString(" → ") },
                    meta.scriptPath?.let { "script" to it },
                    meta.resumeFrom?.let { "resumes" to it },
                    obj["args"]?.let { "args" to it.compact(400) },
                ),
            )
        }
        "Task", "Agent" -> PermissionSummary(
            action = "start a subagent", verb = verb, target = target, icon = icon,
            note = obj.str("description"), fields = listOfNotNull(obj.str("prompt")?.let { "prompt" to it.take(400) }),
        )
        SEND_MESSAGE -> {
            val msg = parseSendMessage(inputJson)
            PermissionSummary(
                action = "message ${msg.recipient}", verb = verb, target = msg.recipient, icon = icon,
                note = msg.text, message = msg,
            )
        }
        else -> {
            val action = if (toolName.startsWith("mcp__")) {
                val parts = toolName.removePrefix("mcp__").split("__", limit = 2)
                "use ${parts.getOrElse(1) { parts[0] }.replace('_', ' ')} (${parts[0]})"
            } else "use $verb"
            PermissionSummary(
                action = action, verb = verb, target = target, icon = icon,
                fields = obj.entries.take(5).map { (k, v) -> k to v.compact() },
            )
        }
    }
}

private fun suggestionButtonLabel(label: String): String {
    val l = label.trim()
    return if (l.startsWith("always", ignoreCase = true) || l.startsWith("allow", ignoreCase = true)) l else "Always allow $l"
}

// ───────────────────────────── Target detail ─────────────────────────────

@Composable
internal fun PermissionTarget(summary: PermissionSummary, modifier: Modifier = Modifier, maxCodeHeight: Int = 180) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
        summary.command?.let { cmd ->
            Box(Modifier.fillMaxWidth().heightIn(max = maxCodeHeight.dp).clip(RoundedCornerShape(14.dp)).verticalScroll(rememberScrollState())) {
                CodeBlock(code = cmd, language = "bash")
            }
        }
        summary.path?.let { p ->
            Text(
                prettyPath(p, maxLen = 64),
                style = TetherTheme.type.mono,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        summary.link?.let { CodeChip(it, maxLines = 3) }
        if (summary.preview.isNotEmpty()) PreviewBlock(summary.preview, summary.previewTruncated)
        summary.fields.forEach { (k, v) ->
            Column {
                Text(k, style = TetherTheme.type.eyebrow, color = TetherTheme.colors.faint)
                Text(v, style = TetherTheme.type.monoSmall, color = MaterialTheme.colorScheme.onSurface, maxLines = 4, overflow = TextOverflow.Ellipsis)
            }
        }
        summary.note?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun PreviewBlock(lines: List<PreviewLine>, truncated: Boolean) {
    val c = TetherTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(c.codeBg)
            .horizontalScroll(rememberScrollState())
            .padding(vertical = 6.dp),
    ) {
        lines.forEach { line ->
            val (bg, fg) = when (line.kind) {
                '+' -> c.diffAddBg to c.diffAddText
                '-' -> c.diffDelBg to c.diffDelText
                else -> Color.Transparent to c.codeText
            }
            Text(
                "${line.kind} ${line.text}",
                style = TetherTheme.type.monoSmall,
                color = fg,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.background(bg).padding(horizontal = 10.dp, vertical = 1.dp),
            )
        }
        if (truncated) {
            Text("  …", style = TetherTheme.type.monoSmall, color = c.faint, modifier = Modifier.padding(horizontal = 10.dp))
        }
    }
}

@Composable
private fun ToolBadge(icon: ImageVector?, tint: Color) {
    Box(
        Modifier.size(34.dp).clip(RoundedCornerShape(11.dp)).background(tint.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon ?: Icons.Rounded.Gavel, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
    }
}

// ───────────────────────────── Inline card ─────────────────────────────

/**
 * The permission request as it sits in the transcript. Pending: amber card with quick buttons.
 * Answered: collapses to a one-line receipt ("✓ Allowed · Bash npm test").
 */
@Composable
fun PermissionCard(
    item: ChatItem.Permission,
    responding: Boolean,
    onRespond: (String, PermissionDecision) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (item.toolName == app.tether.core.ASK_USER_QUESTION) {
        // The picker lives in the decision panel; the history keeps one quiet line.
        val q = remember(item.inputJson) { app.tether.core.AskQuestions.parse(item.inputJson).questions.firstOrNull()?.question }
        Row(modifier.fillMaxWidth().padding(horizontal = Space.gutter, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.QuestionAnswer, contentDescription = null, tint = TetherTheme.colors.clay, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                when (item.state) {
                    PermissionState.PENDING -> "Claude asked" + (q?.let { ": $it" } ?: " a question")
                    PermissionState.DENIED -> "Question skipped"
                    PermissionState.CANCELLED -> "Question withdrawn"
                    else -> "Answered" + (q?.let { ": $it" } ?: "")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        return
    }
    val summary = remember(item.toolName, item.inputJson) { summarizePermission(item.toolName, item.inputJson) }
    AnimatedContent(
        targetState = item.state,
        transitionSpec = { fadeIn(tween(Motion.Medium)) togetherWith fadeOut(tween(Motion.Short)) using SizeTransform(clip = false) },
        contentKey = { it == PermissionState.PENDING },
        modifier = modifier,
        label = "permState",
    ) { st ->
        if (st == PermissionState.PENDING) PendingCard(item, summary, responding, onRespond)
        else AnsweredRow(st, summary)
    }
}

@Composable
private fun PendingCard(
    item: ChatItem.Permission,
    summary: PermissionSummary,
    responding: Boolean,
    onRespond: (String, PermissionDecision) -> Unit,
) {
    // The decision itself lives in the sticky panel above the composer; here it's just a marker.
    val warn = TetherTheme.colors.warning
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        StatusDot(warn, pulsing = !responding, size = 6.dp)
        Spacer(Modifier.width(4.dp))
        Text(
            permissionTitle(summary),
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium, fontSize = 13.5.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Spacer(Modifier.width(8.dp))
        app.tether.ui.chat.render.DecisionBadge(PermissionState.PENDING)
    }
}

/** "Run a command", "Edit greet.py", "Fetch a web page"... the panel's one-line title. */
internal fun permissionTitle(summary: PermissionSummary): String {
    val file = summary.path?.trimEnd('/')?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
    val verb = when {
        summary.action.startsWith("edit") -> "Edit"
        summary.action.startsWith("write") -> "Write"
        summary.action.startsWith("read") -> "Read"
        else -> null
    }
    return if (verb != null && file != null) "$verb $file" else summary.action.replaceFirstChar { it.uppercase() }
}

@Composable
private fun BlockedPathLine(path: String, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.FolderOpen, contentDescription = null, tint = TetherTheme.colors.faint, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(6.dp))
        Text("Path  ", style = MaterialTheme.typography.bodySmall, color = TetherTheme.colors.faint)
        CodeChip(prettyPath(path, maxLen = 36), modifier = Modifier.weight(1f, fill = false))
    }
}

@Composable
private fun AnsweredRow(state: PermissionState, summary: PermissionSummary) {
    val isMessage = summary.message != null
    val (label, color, icon) = when (state) {
        PermissionState.ALLOWED -> Triple(if (isMessage) "Sent" else "Allowed", TetherTheme.colors.success, Icons.Rounded.Check)
        PermissionState.DENIED -> Triple(if (isMessage) "Not sent" else "Denied", TetherTheme.colors.danger, Icons.Rounded.Close)
        else -> Triple("Cancelled", TetherTheme.colors.faint, Icons.Rounded.Block)
    }
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.clip(CircleShape).background(color.copy(alpha = 0.12f)).padding(start = 6.dp, end = 10.dp, top = 3.dp, bottom = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
            Text(label, style = MaterialTheme.typography.labelSmall, color = color)
        }
        Spacer(Modifier.width(Space.sm))
        Text(summary.verb, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold), color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (summary.target.isNotBlank()) {
            Spacer(Modifier.width(6.dp))
            Text(
                summary.target,
                style = TetherTheme.type.monoSmall,
                color = TetherTheme.colors.faint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
    }
}

// ───────────────────────────── Sticky decision panel ─────────────────────────────

/**
 * Always-reachable decision panel for the oldest unanswered request, docked above the composer.
 * Slides up with a spring; a haptic tick announces each new request.
 */
@Composable
fun DecisionPanel(
    pending: List<ChatItem.Permission>,
    respondingIds: Set<String>,
    onRespond: (String, PermissionDecision) -> Unit,
    modifier: Modifier = Modifier,
    respondErrors: Map<String, String> = emptyMap(),
    onFetchQuestion: (suspend () -> String)? = null,
) {
    val first = pending.firstOrNull()
    AnimatedContent(
        targetState = first,
        contentKey = { it?.requestId },
        modifier = modifier,
        transitionSpec = {
            when {
                initialState == null ->
                    (slideInVertically(spring(dampingRatio = 0.82f, stiffness = Spring.StiffnessMediumLow)) { it } + fadeIn(tween(Motion.Short))) togetherWith
                        fadeOut(tween(Motion.Short))
                targetState == null ->
                    fadeIn(tween(Motion.Short)) togetherWith
                        (slideOutVertically(tween(Motion.Medium, easing = Motion.Emphasized)) { it } + fadeOut(tween(Motion.Short)))
                else ->
                    (slideInHorizontally(Motion.gentle()) { it / 3 } + fadeIn(tween(Motion.Medium))) togetherWith
                        (slideOutHorizontally(tween(Motion.Short)) { -it / 3 } + fadeOut(tween(Motion.Short)))
            } using SizeTransform(clip = false)
        },
        label = "decision",
    ) { req ->
        if (req == null) {
            Spacer(Modifier.fillMaxWidth())
        } else if (req.toolName == app.tether.core.ASK_USER_QUESTION) {
            QuestionBody(
                req,
                responding = req.requestId in respondingIds,
                error = respondErrors[req.requestId],
                onRespond = onRespond,
                onFetch = onFetchQuestion,
            )
        } else {
            DecisionBody(
                item = req,
                position = (pending.indexOfFirst { it.requestId == req.requestId }.takeIf { it >= 0 } ?: 0) + 1,
                total = pending.size.coerceAtLeast(1),
                responding = req.requestId in respondingIds,
                onRespond = onRespond,
            )
        }
    }
}

@Composable
private fun DecisionBody(
    item: ChatItem.Permission,
    position: Int,
    total: Int,
    responding: Boolean,
    onRespond: (String, PermissionDecision) -> Unit,
) {
    val c = TetherTheme.colors
    val warn = c.warning
    val haptics = rememberHaptics()
    val summary = remember(item.toolName, item.inputJson) { summarizePermission(item.toolName, item.inputJson) }
    val message = summary.message
    var moreOpen by remember(item.requestId) { mutableStateOf(false) }
    var codeOpen by remember(item.requestId) { mutableStateOf(false) }
    var feedback by remember(item.requestId) { mutableStateOf("") }
    var pressed by remember(item.requestId) { mutableStateOf<String?>(null) }
    LaunchedEffect(item.requestId) { haptics.confirm() }
    // Never more than ~40% of the screen; "More options" may take a little more while open.
    val maxPanel = (LocalConfiguration.current.screenHeightDp * if (moreOpen) 0.62f else 0.40f).dp
    val shape = RoundedCornerShape(24.dp)

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(top = Space.sm)
            .then(if (!c.isDark) Modifier.shadow(12.dp, shape, ambientColor = Color(0x33000000), spotColor = Color(0x22000000)) else Modifier)
            .clip(shape)
            .background(c.composer)
            .border(1.dp, warn.copy(alpha = if (c.isDark) 0.35f else 0.45f), shape)
            .heightIn(max = maxPanel)
            .padding(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 10.dp),
    ) {
        // Only the summary scrolls; the decision buttons always stay visible at the panel's bottom.
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(summary.icon ?: Icons.Rounded.Gavel, contentDescription = null, tint = if (message != null) c.info else warn, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
                Text(
                    permissionTitle(summary),
                    style = MaterialTheme.typography.titleSmall.copy(fontSize = 15.sp),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (total > 1) {
                    Text("$position of $total", style = MaterialTheme.typography.labelSmall, color = c.faint)
                }
            }
            val sub = if (message != null) {
                // The exact address too when the title shows a shortened one, so the user knows where it goes.
                listOfNotNull("To another Claude session", message.to?.takeIf { it != message.recipient }).joinToString("  ·  ")
            } else listOfNotNull(
                summary.path?.let { prettyPath(it, maxLen = 44) },
                summary.note?.takeIf { summary.command != null || summary.path != null },
            ).joinToString("  ·  ").ifBlank { null }
            sub?.let {
                Spacer(Modifier.height(2.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = c.faint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 28.dp),
                )
            }
            if (message?.text != null) {
                MessagePreview(message.text, expanded = codeOpen, onToggle = { haptics.tick(); codeOpen = !codeOpen }, modifier = Modifier.padding(top = 12.dp))
            } else {
                PanelCode(summary, expanded = codeOpen, onToggle = { haptics.tick(); codeOpen = !codeOpen }, modifier = Modifier.padding(top = 12.dp))
            }
            item.blockedPath?.let { BlockedPathLine(it, Modifier.padding(top = Space.sm)) }

            AnimatedVisibility(
                visible = moreOpen,
                enter = expandVertically(Motion.gentle()) + fadeIn(),
                exit = shrinkVertically(tween(Motion.Short)) + fadeOut(tween(Motion.Short)),
            ) {
                Column(Modifier.padding(top = Space.md), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                    item.suggestions.forEachIndexed { i, s ->
                        SecondaryButton(
                            suggestionButtonLabel(s.label),
                            onClick = {
                                haptics.confirm()
                                pressed = "always$i"
                                onRespond(item.requestId, PermissionDecision.Allow(alwaysAllow = listOf(s.rawJson)))
                            },
                            enabled = !responding,
                            icon = Icons.Rounded.DoneAll,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    TetherTextField(
                        value = feedback,
                        onValueChange = { feedback = it },
                        label = "Tell Claude what to do instead",
                        placeholder = if (message != null) "e.g. Wait until the tests pass, then send it" else "e.g. Use the staging database, not prod",
                        singleLine = false,
                        minLines = 2,
                    )
                    SecondaryButton(
                        if (message != null) "Don't send, tell Claude" else "Deny with feedback",
                        onClick = {
                            haptics.confirm()
                            pressed = "feedback"
                            val msg = feedback.trim()
                            onRespond(item.requestId, if (msg.isEmpty()) PermissionDecision.Deny() else PermissionDecision.Deny(message = msg))
                        },
                        enabled = !responding && feedback.isNotBlank(),
                        contentColor = c.danger,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(Space.sm), verticalAlignment = Alignment.CenterVertically) {
            SecondaryButton(
                if (message != null) "Don't send" else "Deny",
                onClick = { haptics.confirm(); pressed = "deny"; onRespond(item.requestId, PermissionDecision.Deny()) },
                enabled = !responding,
                modifier = Modifier.weight(1f),
            )
            PrimaryButton(
                if (message != null) "Send" else "Allow once",
                onClick = { haptics.confirm(); pressed = "allow"; onRespond(item.requestId, PermissionDecision.Allow()) },
                enabled = !responding,
                loading = responding && pressed == "allow",
                modifier = Modifier.weight(1f),
            )
        }
        val rot by animateFloatAsState(if (moreOpen) 180f else 0f, Motion.gentle(), label = "moreChevron")
        Row(
            Modifier
                .align(Alignment.CenterHorizontally)
                .padding(top = 4.dp)
                .clip(RoundedCornerShape(10.dp))
                .clickable(enabled = !responding, role = Role.Button, onClickLabel = if (moreOpen) "Fewer options" else "More options") {
                    haptics.tick(); moreOpen = !moreOpen
                }
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(if (moreOpen) "Fewer options" else "More options", style = MaterialTheme.typography.labelMedium, color = c.faint)
            Spacer(Modifier.width(2.dp))
            Icon(Icons.Rounded.ExpandMore, contentDescription = null, tint = c.faint, modifier = Modifier.size(16.dp).rotate(rot))
        }
    }
}

/**
 * The message a session wants to send another, drawn like the outgoing message row it becomes once
 * sent (prose, not code): 6 lines, tap to see it all.
 */
@Composable
private fun MessagePreview(text: String, expanded: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val c = TetherTheme.colors
    val tint = c.info
    val shape = RoundedCornerShape(16.dp)
    val collapsedMax = 6
    var overflows by remember(text) { mutableStateOf(false) }
    val canExpand = overflows || expanded
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(tint.copy(alpha = 0.07f))
            .border(1.dp, tint.copy(alpha = 0.22f), shape)
            .then(if (canExpand) Modifier.clickable(role = Role.Button, onClickLabel = if (expanded) "Collapse" else "Show all", onClick = onToggle) else Modifier)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = if (expanded) Int.MAX_VALUE else collapsedMax,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { if (!expanded) overflows = it.hasVisualOverflow },
        )
        if (canExpand) {
            Text(
                if (expanded) "Show less" else "Show all",
                style = MaterialTheme.typography.labelSmall,
                color = c.faint,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

/** The command / diff / target in a small mono block: 4 lines, tap to see it all. */
@Composable
private fun PanelCode(summary: PermissionSummary, expanded: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val c = TetherTheme.colors
    val lines: List<PreviewLine> = remember(summary) {
        when {
            summary.command != null -> summary.command.trimEnd().split('\n').map { PreviewLine(' ', it) }
            summary.preview.isNotEmpty() -> summary.preview
            summary.link != null -> listOf(PreviewLine(' ', summary.link))
            summary.fields.isNotEmpty() -> summary.fields.map { (k, v) -> PreviewLine(' ', "$k: $v") }
            summary.note != null && summary.path == null -> listOf(PreviewLine(' ', summary.note))
            else -> emptyList()
        }
    }
    if (lines.isEmpty()) return
    val collapsedMax = 4
    val canExpand = lines.size > collapsedMax
    val shown = if (expanded) lines else lines.take(collapsedMax)
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(c.codeBg)
            .then(if (canExpand) Modifier.clickable(role = Role.Button, onClickLabel = if (expanded) "Collapse" else "Show all", onClick = onToggle) else Modifier)
            .heightIn(max = 220.dp)
            .verticalScroll(rememberScrollState())
            .padding(vertical = 8.dp),
    ) {
        Column(Modifier.horizontalScroll(rememberScrollState())) {
            shown.forEach { line ->
                val (bg, fg) = when (line.kind) {
                    '+' -> c.diffAddBg to c.diffAddText
                    '-' -> c.diffDelBg to c.diffDelText
                    else -> Color.Transparent to c.codeText
                }
                Text(
                    if (line.kind == ' ') line.text else "${line.kind} ${line.text}",
                    style = TetherTheme.type.monoSmall,
                    color = fg,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.background(bg).padding(horizontal = 12.dp, vertical = 1.dp),
                )
            }
        }
        if (canExpand && !expanded) {
            Text(
                "Show all · ${lines.size - collapsedMax} more " + if (lines.size - collapsedMax == 1) "line" else "lines",
                style = MaterialTheme.typography.labelSmall,
                color = c.faint,
                modifier = Modifier.padding(start = 12.dp, top = 6.dp),
            )
        }
    }
}
