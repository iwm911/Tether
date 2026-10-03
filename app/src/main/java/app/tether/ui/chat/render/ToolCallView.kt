package app.tether.ui.chat.render

import androidx.compose.foundation.background

import androidx.compose.ui.unit.sp

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.tether.core.ChatItem
import app.tether.core.ToolStatus
import app.tether.ui.components.ClaudeSpinner
import app.tether.ui.components.StatusDot
import app.tether.ui.components.formatElapsed
import app.tether.ui.components.rememberHaptics
import app.tether.ui.theme.Motion
import app.tether.ui.theme.TetherColors
import app.tether.ui.theme.TetherTheme
import java.util.Locale

/** Everything a tool row and its detail need, parsed once per input/result change. */
internal class ToolParsed(
    val input: kotlinx.serialization.json.JsonObject?,
    val structured: kotlinx.serialization.json.JsonObject?,
    val diff: DiffData?,
)

internal val ToolStatus.isActive: Boolean
    get() = this == ToolStatus.RUNNING || this == ToolStatus.STREAMING_INPUT

private val EDIT_TOOLS = setOf("Edit", "MultiEdit", "Write", "NotebookEdit")

@Composable
internal fun ToolCallView(
    item: ChatItem.ToolCall,
    modifier: Modifier = Modifier,
    showThinking: Boolean,
    compactTools: Boolean,
    depth: Int,
) {
    val cwd = LocalChatCwd.current
    val colors = TetherTheme.colors
    val haptics = rememberHaptics()
    val label = remember(item.name, item.inputJson, cwd) { ToolPresentation.describe(item.name, item.inputJson, cwd) }
    val parsed = remember(item.name, item.inputJson, item.result) {
        val input = parseJsonObject(item.inputJson)
        val structured = parseJsonObject(item.result?.structuredJson)
        ToolParsed(input, structured, if (item.name in EDIT_TOOLS) editDiff(item.name, item.inputJson, input, structured) else null)
    }
    var expanded by rememberSaveable(item.key) {
        mutableStateOf(!compactTools || item.name == "ExitPlanMode")
    }
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, Motion.gentle(), label = "toolChevron")
    val meta = remember(item, parsed, colors) { toolMeta(item, parsed, colors) }
    val statusWord = statusDescription(item.status)

    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 38.dp)
                .clip(RoundedCornerShape(12.dp))
                .clickable(role = Role.Button, onClickLabel = if (expanded) "Collapse" else "Expand") {
                    haptics.tick()
                    expanded = !expanded
                }
                .semantics {
                    contentDescription = "${label.verb} ${label.target}".trim()
                    stateDescription = statusWord + if (expanded) ", expanded" else ", collapsed"
                }
                .padding(start = 2.dp, end = 4.dp, top = 5.dp, bottom = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ToolStatusGlyph(item.status)
            Spacer(Modifier.width(8.dp))
            Icon(label.icon, contentDescription = null, tint = colors.faint, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(7.dp))
            Text(
                label.verb,
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium, fontSize = 13.5.sp),
                color = if (item.status == ToolStatus.DENIED || item.status == ToolStatus.INTERRUPTED) colors.faint else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            if (label.target.isNotEmpty()) {
                Spacer(Modifier.width(8.dp))
                val targetStyle = TetherTheme.type.monoSmall
                val targetColor = colors.faint
                if (looksLikePathTarget(item.name)) {
                    MiddleEllipsisText(label.target, style = targetStyle, color = targetColor, modifier = Modifier.weight(1f))
                } else {
                    Text(
                        label.target,
                        style = targetStyle,
                        color = targetColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
            } else {
                Spacer(Modifier.weight(1f))
            }
            item.decision?.let { d ->
                Spacer(Modifier.width(8.dp))
                DecisionBadge(d, question = item.name == app.tether.core.ASK_USER_QUESTION)
            }
            if (meta != null) {
                Spacer(Modifier.width(8.dp))
                Text(meta, style = MaterialTheme.typography.labelSmall, color = colors.faint, maxLines = 1)
            }
            Spacer(Modifier.width(2.dp))
            Icon(
                Icons.Rounded.ExpandMore,
                contentDescription = null,
                tint = colors.faint,
                modifier = Modifier.size(18.dp).rotate(rotation),
            )
        }

        // While a subagent works, surface its latest step under the collapsed row.
        if (!expanded && (item.name == "Task" || item.name == "Agent") && item.status.isActive) {
            val latest = item.children.lastOrNull { it is ChatItem.ToolCall && it.name !in ToolPresentation.hiddenTools } as? ChatItem.ToolCall
            if (latest != null) {
                val sub = remember(latest.name, latest.inputJson, cwd) { ToolPresentation.describe(latest.name, latest.inputJson, cwd) }
                AnimatedContent(
                    targetState = "${sub.verb}  ${sub.target}".trim(),
                    transitionSpec = { (fadeIn(tween(Motion.Short)) + scaleIn(initialScale = 0.98f)) togetherWith fadeOut(tween(Motion.Short)) },
                    label = "subStep",
                ) { line ->
                    Text(
                        "↳ $line",
                        style = TetherTheme.type.monoSmall,
                        color = colors.faint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 50.dp, end = 8.dp, bottom = 6.dp),
                    )
                }
            }
        }

        // A workflow returns at once and runs in the background: while its agents work, say where it is.
        if (!expanded && item.name == app.tether.core.WORKFLOW_TOOL) {
            val agents = item.toolUseId?.let { LocalWorkflowAgents.current[it] }.orEmpty()
            val running = agents.filter { it.running }
            if (running.isNotEmpty()) {
                val line = listOfNotNull(running.last().phase, "${running.size} of ${agents.size} agents running").joinToString(" · ")
                AnimatedContent(
                    targetState = line,
                    transitionSpec = { fadeIn(tween(Motion.Short)) togetherWith fadeOut(tween(Motion.Short)) },
                    label = "workflowStep",
                ) { text ->
                    Text(
                        "↳ $text",
                        style = TetherTheme.type.monoSmall,
                        color = colors.faint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 50.dp, end = 8.dp, bottom = 6.dp),
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(animationSpec = tween(Motion.Medium, easing = Motion.Emphasized), expandFrom = Alignment.Top) +
                fadeIn(tween(Motion.Medium)),
            exit = shrinkVertically(animationSpec = tween(Motion.Short, easing = Motion.Standard), shrinkTowards = Alignment.Top) +
                fadeOut(tween(Motion.Short)),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 26.dp, top = 2.dp, bottom = 10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ToolDetail(item, parsed, showThinking = showThinking, depth = depth)
            }
        }
    }
}

private fun looksLikePathTarget(name: String) =
    name in setOf("Read", "Edit", "MultiEdit", "Write", "NotebookEdit", "NotebookRead", "LS")

private fun statusDescription(s: ToolStatus) = when (s) {
    ToolStatus.STREAMING_INPUT -> "Preparing"
    ToolStatus.RUNNING -> "Running"
    ToolStatus.AWAITING_PERMISSION -> "Waiting for approval"
    ToolStatus.SUCCESS -> "Done"
    ToolStatus.ERROR -> "Failed"
    ToolStatus.DENIED -> "Denied"
    ToolStatus.INTERRUPTED -> "Interrupted"
}

@Composable
internal fun ToolStatusGlyph(status: ToolStatus, modifier: Modifier = Modifier) {
    val c = TetherTheme.colors
    Box(modifier.size(18.dp), contentAlignment = Alignment.Center) {
        AnimatedContent(
            targetState = status,
            contentKey = { if (it.isActive) ToolStatus.RUNNING else it },
            transitionSpec = { (fadeIn(tween(Motion.Short)) + scaleIn(initialScale = 0.5f)) togetherWith fadeOut(tween(Motion.Short)) },
            label = "toolStatus",
        ) { s ->
            when (s) {
                ToolStatus.STREAMING_INPUT, ToolStatus.RUNNING -> ClaudeSpinner(fontSize = 13f)
                ToolStatus.AWAITING_PERMISSION -> StatusDot(c.warning, pulsing = true, size = 7.dp)
                ToolStatus.SUCCESS -> Icon(Icons.Rounded.Check, contentDescription = null, tint = c.success.copy(alpha = 0.8f), modifier = Modifier.size(15.dp))
                ToolStatus.ERROR -> Icon(Icons.Rounded.Close, contentDescription = null, tint = c.danger, modifier = Modifier.size(16.dp))
                ToolStatus.DENIED -> Icon(Icons.Rounded.Block, contentDescription = null, tint = c.faint, modifier = Modifier.size(15.dp))
                ToolStatus.INTERRUPTED -> Icon(Icons.Rounded.Stop, contentDescription = null, tint = c.faint, modifier = Modifier.size(16.dp))
            }
        }
    }
}

/** Tiny "Allowed" / "Denied" / "Needs you" label folded into a tool row. */
@Composable
internal fun DecisionBadge(state: app.tether.core.PermissionState, question: Boolean = false) {
    val c = TetherTheme.colors
    val (label, color) = when (state) {
        app.tether.core.PermissionState.PENDING -> (if (question) "Your answer" else "Needs you") to c.warning
        app.tether.core.PermissionState.ALLOWED -> (if (question) "Answered" else "Allowed") to c.success
        app.tether.core.PermissionState.DENIED -> (if (question) "Skipped" else "Denied") to (if (question) c.faint else c.danger)
        app.tether.core.PermissionState.CANCELLED -> "Cancelled" to c.faint
    }
    Text(
        label,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

// ───────────────────────────── Trailing meta ─────────────────────────────

private val EXIT_CODE = Regex("(?i)^\\s*exit code[: ]+(\\d+)")

internal fun formatDuration(ms: Long): String = when {
    ms < 0 -> ""
    ms < 60_000 -> String.format(Locale.US, "%.1fs", ms / 1000.0)
    else -> formatElapsed(ms)
}

private fun toolMeta(item: ChatItem.ToolCall, p: ToolParsed, c: TetherColors): AnnotatedString? {
    val parts = ArrayList<AnnotatedString>()
    fun plain(s: String) { parts += AnnotatedString(s) }
    val resultText = item.result?.text
    when (item.name) {
        "Edit", "MultiEdit", "NotebookEdit" -> p.diff?.takeIf { it.added + it.removed > 0 }?.let { parts += diffStatText(it.added, it.removed, c) }
        "Write" -> {
            val content = p.input?.str("content")
            if (p.structured?.str("type") == "update" && p.diff != null) parts += diffStatText(p.diff.added, p.diff.removed, c)
            else if (content != null) plain(lineCountLabel(content.trimEnd('\n').split('\n').size))
        }
        "Read" -> {
            val file = p.structured?.obj("file")
            val n = file?.int("numLines") ?: resultText?.let { t -> ReadResult.parse(t).lines.size.takeIf { it > 0 } }
            if (n != null && item.result?.isError != true) plain(lineCountLabel(n))
        }
        "Bash" -> {
            val code = resultText?.let { EXIT_CODE.find(it)?.groupValues?.get(1) }
            when {
                p.structured?.bool("interrupted") == true -> plain("interrupted")
                code != null -> parts += buildAnnotatedString { withStyle(SpanStyle(color = c.danger)) { append("exit $code") } }
                item.status == ToolStatus.ERROR -> parts += buildAnnotatedString { withStyle(SpanStyle(color = c.danger)) { append("failed") } }
            }
        }
        "Grep", "Glob" -> {
            val files = p.structured?.int("numFiles")
            val lines = p.structured?.int("numLines")
            when {
                lines != null && p.structured?.str("mode") == "content" -> plain(if (lines == 1) "1 match" else "$lines matches")
                files != null -> plain(if (files == 1) "1 file" else "$files files")
                resultText != null && item.status == ToolStatus.SUCCESS -> {
                    val n = SearchResult.parse(resultText).size
                    plain(if (n == 1) "1 result" else "$n results")
                }
            }
        }
        "Task", "Agent" -> {
            val n = p.structured?.int("totalToolUseCount")
                ?: item.children.count { it is ChatItem.ToolCall }.takeIf { it > 0 }
            if (n != null) plain(if (n == 1) "1 tool" else "$n tools")
        }
    }
    val started = item.startedAt
    val finished = item.finishedAt
    if (started != null && finished != null && !item.status.isActive && finished >= started) {
        val d = finished - started
        if (d >= 500) plain(formatDuration(d))
    }
    if (parts.isEmpty()) return null
    return buildAnnotatedString {
        parts.forEachIndexed { i, s ->
            if (i > 0) append(" · ")
            append(s)
        }
    }
}

private fun lineCountLabel(n: Int) = if (n == 1) "1 line" else "$n lines"

internal fun diffStatText(added: Int, removed: Int, c: TetherColors): AnnotatedString = buildAnnotatedString {
    withStyle(SpanStyle(color = c.success, fontWeight = FontWeight.Medium)) { append("+$added") }
    append(" ")
    withStyle(SpanStyle(color = c.danger, fontWeight = FontWeight.Medium)) { append("−$removed") }
}

// ───────────────────────────── Edit diff derivation ─────────────────────────────

internal fun editDiff(
    name: String,
    inputJson: String,
    input: kotlinx.serialization.json.JsonObject?,
    structured: kotlinx.serialization.json.JsonObject?,
): DiffData? = try {
    DiffModel.fromStructuredPatch(structured?.get("structuredPatch"))
        ?: when (name) {
            "MultiEdit" -> {
                val edits = input?.arr("edits")?.mapNotNull { e ->
                    val o = e as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
                    (o.str("old_string") ?: "") to (o.str("new_string") ?: "")
                }
                if (edits.isNullOrEmpty()) null else DiffModel.fromEdits(edits)
            }
            "Edit" -> {
                val old = input?.str("old_string") ?: partialString(inputJson, "old_string")
                val new = input?.str("new_string") ?: partialString(inputJson, "new_string")
                if (old == null && new == null) null else DiffModel.fromStrings(old.orEmpty(), new.orEmpty())
            }
            "NotebookEdit" -> {
                val src = input?.str("new_source") ?: partialString(inputJson, "new_source")
                if (src == null) null
                else if (input?.str("edit_mode") == "delete") DiffModel.fromStrings(src, "")
                else DiffModel.fromStrings("", src)
            }
            else -> null
        }
} catch (t: Throwable) {
    null
}
