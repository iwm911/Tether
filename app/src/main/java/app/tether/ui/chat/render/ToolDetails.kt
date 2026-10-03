package app.tether.ui.chat.render

import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Map
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import app.tether.core.ChatItem
import app.tether.core.ToolStatus
import app.tether.core.WORKFLOW_TOOL
import app.tether.core.WorkflowMeta
import app.tether.ui.components.ClaudeSpinner
import app.tether.ui.components.ShimmerText
import app.tether.ui.components.StatusDot
import app.tether.ui.components.rememberHaptics
import app.tether.ui.theme.Motion
import app.tether.ui.theme.TetherTheme
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// ───────────────────────────── Result parsers (pure) ─────────────────────────────

/** A Read result with Claude Code's `     12→code` gutter split out. */
internal class ReadResult(val startLine: Int, val lines: List<String>, val numbered: Boolean) {
    val code: String get() = lines.joinToString("\n")

    companion object {
        private val NUMBERED = Regex("^\\s*(\\d+)[→\\t](.*)$")

        fun parse(text: String): ReadResult {
            val clean = cleanOutput(text)
            if (clean.isEmpty()) return ReadResult(1, emptyList(), false)
            val raw = clean.split('\n')
            var start = -1
            var matched = 0
            val out = ArrayList<String>(raw.size)
            for (l in raw) {
                val m = NUMBERED.matchEntire(l)
                if (m != null) {
                    if (start < 0) start = m.groupValues[1].toIntOrNull() ?: 1
                    out += m.groupValues[2]
                    matched++
                } else out += l
            }
            val numbered = matched > 0 && matched >= raw.size * 0.6
            return if (numbered) ReadResult(start.coerceAtLeast(1), out, true) else ReadResult(1, raw, false)
        }
    }
}

/** Grep/Glob plain-text results: file rows (minus the "Found N files" header). */
internal object SearchResult {
    private val HEADER = Regex("^(Found \\d+ (files?|matches?|lines?)|No (files|matches) found).*", RegexOption.IGNORE_CASE)

    fun parse(text: String): List<String> =
        cleanOutput(text).split('\n').map { it.trimEnd() }.filter { it.isNotBlank() && !HEADER.matches(it.trim()) }
}

// ───────────────────────────── Dispatcher ─────────────────────────────

@Composable
internal fun ToolDetail(item: ChatItem.ToolCall, parsed: ToolParsed, showThinking: Boolean, depth: Int) {
    val name = item.name
    when {
        name == "Bash" -> BashDetail(item, parsed)
        name == "Edit" || name == "MultiEdit" -> EditDetail(item, parsed)
        name == "Write" -> WriteDetail(item, parsed)
        name == "NotebookEdit" -> NotebookDetail(item, parsed)
        name == "Read" || name == "NotebookRead" -> ReadDetail(item, parsed)
        name == "Grep" || name == "Glob" || name == "LS" -> SearchDetail(item, parsed)
        name == "WebFetch" -> WebDetail(item, parsed, isSearch = false)
        name == "WebSearch" -> WebDetail(item, parsed, isSearch = true)
        name == "Task" || name == "Agent" -> TaskDetail(item, parsed, showThinking, depth)
        name == "ExitPlanMode" -> PlanDetail(item, parsed)
        name == WORKFLOW_TOOL -> WorkflowDetail(item, parsed)
        name.startsWith("mcp__") -> McpDetail(item, parsed)
        else -> GenericDetail(item, parsed)
    }
    RawToggle(item, parsed)
}

// ───────────────────────────── Shared bits ─────────────────────────────

/** Status line for calls without a result yet, and the error / denied panel when there is one. */
@Composable
private fun ResultState(item: ChatItem.ToolCall, showErrors: Boolean = true) {
    val c = TetherTheme.colors
    val r = item.result
    when {
        r == null && item.status == ToolStatus.STREAMING_INPUT -> PendingLine("Preparing…")
        r == null && item.status == ToolStatus.RUNNING -> PendingLine("Running…")
        r == null && item.status == ToolStatus.AWAITING_PERMISSION ->
            Text("Waiting for your approval", style = MaterialTheme.typography.bodySmall, color = c.warning)
        r == null && item.status == ToolStatus.INTERRUPTED ->
            Text("Interrupted before it finished", style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic), color = c.faint)
        r == null && item.status == ToolStatus.DENIED ->
            Text("Denied — Claude was told not to do this", style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic), color = c.faint)
        r != null && showErrors && item.status == ToolStatus.DENIED ->
            OutputPanel(r.text.ifBlank { "Denied" }, label = "Denied", tone = PanelTone.MUTED, wrap = true, stateKey = item.key)
        r != null && showErrors && r.isError ->
            OutputPanel(r.text, label = "Error", tone = PanelTone.ERROR, wrap = true, stateKey = item.key)
    }
}

@Composable
private fun PendingLine(text: String) {
    val c = TetherTheme.colors
    ShimmerText(text, MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic), base = c.faint)
}

@Composable
private fun InfoChip(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurfaceVariant, mono: Boolean = true) {
    val c = TetherTheme.colors
    Text(
        text,
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(c.codeBg)
            .border(1.dp, c.hairline, RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        style = if (mono) TetherTheme.type.monoSmall else MaterialTheme.typography.labelMedium,
        color = color,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun Badge(text: String, color: Color, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        modifier = modifier
            .clip(CircleShape)
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        style = TetherTheme.type.eyebrow,
        color = color,
    )
}

@Composable
private fun Caption(text: String) {
    Text(text.uppercase(), style = TetherTheme.type.eyebrow, color = TetherTheme.colors.faint)
}

private fun resultOrNull(item: ChatItem.ToolCall): String? =
    item.result?.takeIf { !it.isError && item.status != ToolStatus.DENIED }?.text

// ───────────────────────────── Bash ─────────────────────────────

@Composable
private fun BashDetail(item: ChatItem.ToolCall, p: ToolParsed) {
    val c = TetherTheme.colors
    val command = p.input?.str("command") ?: partialString(item.inputJson, "command").orEmpty()
    val description = p.input?.str("description")
    val background = p.input?.bool("run_in_background") == true
    if (description != null) {
        Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (command.isNotEmpty()) CodeView(command, "bash", label = if (background) "bash · background" else "bash", collapseAt = 10, lineNumbers = false, stateKey = item.key + ":cmd")

    val r = item.result
    if (r == null) { ResultState(item); return }
    val s = p.structured
    val stdout = s?.str("stdout")
    val stderr = s?.str("stderr")
    val interrupted = s?.bool("interrupted") == true
    if (stdout != null || stderr != null) {
        val out = stdout.orEmpty()
        val err = stderr.orEmpty()
        when {
            out.isNotBlank() || err.isBlank() && !r.isError ->
                OutputPanel(out, label = "Output", tone = if (r.isError && err.isBlank()) PanelTone.ERROR else PanelTone.NORMAL, stateKey = item.key + ":out")
        }
        if (err.isNotBlank()) OutputPanel(err, label = "stderr", tone = PanelTone.ERROR, stateKey = item.key + ":err")
        if (out.isBlank() && err.isBlank() && r.isError) OutputPanel(r.text, label = "Error", tone = PanelTone.ERROR, wrap = true, stateKey = item.key + ":e")
    } else {
        OutputPanel(
            r.text,
            label = when { item.status == ToolStatus.DENIED -> "Denied"; r.isError -> "Error"; else -> "Output" },
            tone = when { item.status == ToolStatus.DENIED -> PanelTone.MUTED; r.isError -> PanelTone.ERROR; else -> PanelTone.NORMAL },
            wrap = item.status == ToolStatus.DENIED,
            stateKey = item.key + ":out",
        )
    }
    if (interrupted) {
        Text("Interrupted", style = MaterialTheme.typography.labelSmall, color = c.faint)
    }
}

// ───────────────────────────── Edit / Write / Notebook ─────────────────────────────

@Composable
private fun EditDetail(item: ChatItem.ToolCall, p: ToolParsed) {
    val cwd = LocalChatCwd.current
    val path = p.input?.str("file_path") ?: partialString(item.inputJson, "file_path")
    val shownPath = path?.let { ToolPresentation.displayPath(it, cwd) }
    ResultState(item)
    if (p.input?.bool("replace_all") == true) Badge("Replace all", TetherTheme.colors.info)
    val diff = p.diff
    if (diff != null) DiffView(diff, path = shownPath, stateKey = item.key)
    else if (item.status == ToolStatus.STREAMING_INPUT) PendingLine("Composing edit…")
}

@Composable
private fun WriteDetail(item: ChatItem.ToolCall, p: ToolParsed) {
    val c = TetherTheme.colors
    val cwd = LocalChatCwd.current
    val path = p.input?.str("file_path") ?: partialString(item.inputJson, "file_path")
    val content = p.input?.str("content") ?: partialString(item.inputJson, "content").orEmpty()
    val type = p.structured?.str("type")
    val lines = remember(content) { if (content.isEmpty()) emptyList() else content.trimEnd('\n').split('\n') }
    ResultState(item)
    Row(verticalAlignment = Alignment.CenterVertically) {
        when (type) {
            "create" -> Badge("New file", c.success)
            "update" -> Badge("Updated", c.info)
            else -> Badge(if (item.status.isActive) "Writing" else "Write", c.faint)
        }
        Spacer(Modifier.width(8.dp))
        Text(
            if (lines.size == 1) "1 line" else "${lines.size} lines",
            style = MaterialTheme.typography.labelSmall,
            color = c.faint,
        )
    }
    val diff = p.diff
    if (type == "update" && diff != null) {
        DiffView(diff, path = path?.let { ToolPresentation.displayPath(it, cwd) }, stateKey = item.key)
    } else if (lines.isNotEmpty()) {
        val preview = remember(lines) { lines.take(40).joinToString("\n") }
        val lang = SyntaxHighlighter.languageForPath(path)
        CodeView(
            preview,
            language = lang,
            label = path?.substringAfterLast('/') ?: lang,
            collapseAt = 18,
            stateKey = item.key + ":write",
        )
        if (lines.size > 40) {
            Text("+ ${lines.size - 40} more lines", style = MaterialTheme.typography.labelSmall, color = c.faint)
        }
    }
}

@Composable
private fun NotebookDetail(item: ChatItem.ToolCall, p: ToolParsed) {
    val c = TetherTheme.colors
    val mode = p.input?.str("edit_mode") ?: "replace"
    val cellType = p.input?.str("cell_type")
    val cellId = p.input?.str("cell_id")
    ResultState(item)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Badge(mode, if (mode == "delete") c.danger else c.info)
        if (cellType != null) InfoChip(cellType, mono = false)
        if (cellId != null) InfoChip("cell $cellId")
    }
    val src = p.input?.str("new_source") ?: partialString(item.inputJson, "new_source")
    if (!src.isNullOrEmpty() && mode != "delete") {
        CodeView(src, if (cellType == "markdown") "markdown" else "python", collapseAt = 18, stateKey = item.key + ":nb")
    }
}

// ───────────────────────────── Read ─────────────────────────────

@Composable
private fun ReadDetail(item: ChatItem.ToolCall, p: ToolParsed) {
    val c = TetherTheme.colors
    val cwd = LocalChatCwd.current
    val path = p.input?.str("file_path") ?: p.input?.str("notebook_path") ?: partialString(item.inputJson, "file_path")
    val offset = p.input?.int("offset")
    val limit = p.input?.int("limit")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (path != null) InfoChip(ToolPresentation.displayPath(path, cwd, maxLen = 80))
        if (offset != null || limit != null) {
            val from = offset ?: 1
            InfoChip(if (limit != null) "lines $from–${from + limit - 1}" else "from line $from", mono = false)
        }
    }
    ResultState(item)
    val text = resultOrNull(item) ?: return
    val file = p.structured?.obj("file")
    val structuredType = p.structured?.str("type")
    if (structuredType == "image" || structuredType == "pdf") {
        Text(
            if (structuredType == "image") "Image attached to the conversation" else "PDF attached to the conversation",
            style = MaterialTheme.typography.bodySmall,
            color = c.faint,
        )
        return
    }
    val read = remember(text, file) {
        val content = file?.str("content")
        if (content != null) ReadResult(file?.int("startLine") ?: offset ?: 1, content.trimEnd('\n').split('\n'), true)
        else ReadResult.parse(text)
    }
    if (read.lines.isEmpty() || read.lines.all { it.isBlank() }) {
        Text("Empty file", style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic), color = c.faint)
        return
    }
    CodeView(
        read.code,
        language = SyntaxHighlighter.languageForPath(path),
        label = path?.substringAfterLast('/'),
        startLine = read.startLine,
        collapseAt = 20,
        lineNumbers = read.numbered,
        stateKey = item.key + ":read",
    )
    val total = file?.int("totalLines")
    if (total != null && total > read.lines.size) {
        Text(
            "Showing lines ${read.startLine}–${read.startLine + read.lines.size - 1} of $total",
            style = MaterialTheme.typography.labelSmall,
            color = c.faint,
        )
    }
}

// ───────────────────────────── Grep / Glob / LS ─────────────────────────────

@Composable
private fun SearchDetail(item: ChatItem.ToolCall, p: ToolParsed) {
    val c = TetherTheme.colors
    val cwd = LocalChatCwd.current
    val input = p.input
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        (input?.str("pattern") ?: partialString(item.inputJson, "pattern"))?.let { InfoChip(if (item.name == "Grep") "\"$it\"" else it) }
        input?.str("path")?.let { InfoChip("in " + ToolPresentation.displayPath(it, cwd), mono = true) }
        input?.str("glob")?.let { InfoChip("glob $it") }
        input?.str("type")?.let { InfoChip("type $it") }
        input?.str("output_mode")?.let { InfoChip(it.replace('_', ' '), mono = false) }
        if (input?.bool("-i") == true) InfoChip("ignore case", mono = false)
        if (input?.bool("multiline") == true) InfoChip("multiline", mono = false)
    }
    ResultState(item)
    val text = resultOrNull(item) ?: return
    val s = p.structured
    val contentMode = s?.str("mode") == "content" || input?.str("output_mode") == "content"
    if (contentMode) {
        val content = s?.str("content") ?: text
        if (content.isBlank()) EmptyResult("No matches") else OutputPanel(content, label = "Matches", collapseAt = 15, stateKey = item.key + ":m")
        return
    }
    val files = remember(text, s) { s?.arr("filenames")?.strings() ?: SearchResult.parse(text) }
    if (files.isEmpty()) { EmptyResult(if (item.name == "LS") "Empty directory" else "No matches"); return }
    FileList(files, cwd, truncated = s?.bool("truncated") == true, stateKey = item.key)
}

@Composable
private fun EmptyResult(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic), color = TetherTheme.colors.faint)
}

@Composable
private fun FileList(files: List<String>, cwd: String?, truncated: Boolean, stateKey: String, collapseAt: Int = 12) {
    val c = TetherTheme.colors
    var expanded by rememberSaveable(stateKey, "files") { mutableStateOf(false) }
    val collapsible = files.size > collapseAt + 2
    val shown = if (collapsible && !expanded) files.take(collapseAt) else files
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(c.codeBg)
            .border(1.dp, c.hairline, shape)
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                (if (files.size == 1) "1 RESULT" else "${files.size} RESULTS") + if (truncated) " · TRUNCATED" else "",
                style = TetherTheme.type.eyebrow,
                color = c.faint,
            )
            Spacer(Modifier.weight(1f))
            CopyButton(text = { files.joinToString("\n") })
        }
        SelectionContainer {
            Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = if (collapsible) 0.dp else 10.dp)) {
                shown.forEach { f ->
                    Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Description, contentDescription = null, tint = c.faint, modifier = Modifier.size(13.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (f.startsWith("/") || f.startsWith("~")) ToolPresentation.displayPath(f, cwd, maxLen = 120) else f,
                            style = codeStyle(small = true),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        if (collapsible) {
            ExpandToggle(
                expanded = expanded,
                collapsedLabel = "Show all ${files.size}",
                onToggle = { expanded = !expanded },
                modifier = Modifier.padding(start = 2.dp, bottom = 2.dp),
            )
        }
    }
}

// ───────────────────────────── Web ─────────────────────────────

@Composable
private fun WebDetail(item: ChatItem.ToolCall, p: ToolParsed, isSearch: Boolean) {
    val c = TetherTheme.colors
    if (isSearch) {
        val q = p.input?.str("query") ?: partialString(item.inputJson, "query")
        if (q != null) Text("“$q”", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium))
        val domains = p.input?.arr("allowed_domains")?.strings().orEmpty()
        if (domains.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                domains.forEach { InfoChip(it) }
            }
        }
    } else {
        val url = p.input?.str("url") ?: partialString(item.inputJson, "url")
        if (url != null) {
            val link = remember(url, c.clay) {
                buildAnnotatedString {
                    withLink(
                        LinkAnnotation.Url(
                            url,
                            TextLinkStyles(style = SpanStyle(color = c.clay, textDecoration = TextDecoration.Underline)),
                        )
                    ) { append(url) }
                }
            }
            Text(link, style = TetherTheme.type.monoSmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        p.input?.str("prompt")?.let {
            Text(it, style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    ResultState(item)
    val text = resultOrNull(item) ?: return
    val clean = remember(text) { cleanOutput(text) }
    if (clean.isBlank()) { EmptyResult("No content"); return }
    ResultCard(label = if (isSearch) "Results" else "Page summary", copyText = clean, key = item.key) {
        MarkdownContent(clean, tone = MdTone.COMPACT)
    }
}

/** Hairline card with a caption + Copy, and collapsible prose inside. */
@Composable
private fun ResultCard(label: String, copyText: String, key: String, content: @Composable () -> Unit) {
    val c = TetherTheme.colors
    val shape = RoundedCornerShape(14.dp)
    val bg = MaterialTheme.colorScheme.surfaceContainerLow
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(bg)
            .border(1.dp, c.hairline, shape)
            .padding(start = 14.dp, end = 4.dp, bottom = 6.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Caption(label)
            Spacer(Modifier.weight(1f))
            CopyButton(text = { copyText })
        }
        Box(Modifier.padding(end = 10.dp)) {
            CollapsibleBox(collapsedHeight = 240.dp, fadeColor = bg, key = key + ":" + label) { content() }
        }
    }
}

// ───────────────────────────── Task / subagent ─────────────────────────────

@Composable
private fun TaskDetail(item: ChatItem.ToolCall, p: ToolParsed, showThinking: Boolean, depth: Int) {
    val c = TetherTheme.colors
    val description = p.input?.str("description")
    val type = p.input?.str("subagent_type")
    val prompt = p.input?.str("prompt") ?: partialString(item.inputJson, "prompt")
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (type != null) {
            Badge(type.replace('-', ' '), c.clay)
            Spacer(Modifier.width(8.dp))
        }
        if (description != null) {
            Text(
                description,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
    if (!prompt.isNullOrBlank()) {
        ResultCard(label = "Prompt", copyText = prompt, key = item.key) {
            MarkdownContent(prompt, tone = MdTone.COMPACT)
        }
    }

    LocalSubagentLinks.current[item.toolUseId]?.let { open ->
        app.tether.ui.components.SecondaryButton(
            "Open subagent transcript",
            onClick = open,
            icon = androidx.compose.material.icons.Icons.AutoMirrored.Rounded.OpenInNew,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    val children = item.children
    val rule = c.clay.copy(alpha = 0.5f)
    if (children.isNotEmpty() || item.status.isActive) {
        Column(
            Modifier
                .fillMaxWidth()
                .drawBehind {
                    val w = 2.dp.toPx()
                    drawRoundRect(rule, size = Size(w, size.height), cornerRadius = CornerRadius(w / 2, w / 2))
                }
                .padding(start = 12.dp, top = 2.dp, bottom = 2.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            if (children.isEmpty()) PendingLine("Starting subagent…")
            children.forEach { child ->
                if (child.isRenderable(showThinking)) {
                    ChatItemContent(child, Modifier.fillMaxWidth(), showThinking = showThinking, compactTools = true, depth = depth + 1)
                }
            }
            if (children.isNotEmpty() && item.status.isActive && children.none { it is ChatItem.ToolCall && it.status.isActive }) {
                PendingLine("Working…")
            }
        }
    }
    ResultState(item)
    val result = resultOrNull(item)
    if (!result.isNullOrBlank()) {
        val clean = remember(result) { cleanOutput(result) }
        ResultCard(label = "Result", copyText = clean, key = item.key) {
            MarkdownContent(clean, tone = MdTone.COMPACT)
        }
    }
}

// ───────────────────────────── ExitPlanMode ─────────────────────────────

@Composable
private fun PlanDetail(item: ChatItem.ToolCall, p: ToolParsed) {
    val c = TetherTheme.colors
    val plan = p.input?.str("plan") ?: partialString(item.inputJson, "plan").orEmpty()
    val shape = RoundedCornerShape(18.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(c.clay.copy(alpha = if (c.isDark) 0.06f else 0.05f))
            .border(1.dp, c.clay.copy(alpha = 0.35f), shape)
            .padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 14.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Map, contentDescription = null, tint = c.clay, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text("PLAN", style = TetherTheme.type.eyebrow, color = c.clay)
            Spacer(Modifier.weight(1f))
            if (plan.isNotBlank()) CopyButton(text = { plan })
        }
        Spacer(Modifier.size(6.dp))
        Box(Modifier.padding(end = 10.dp)) {
            if (plan.isBlank()) PendingLine("Drafting plan…")
            else MarkdownContent(plan, tone = MdTone.COMPACT, streaming = item.status == ToolStatus.STREAMING_INPUT)
        }
    }
    ResultState(item)
}

// ───────────────────────────── Workflow ─────────────────────────────

/** A multi-agent workflow: what it does, its phases, its agents (live, each opens its transcript), the script. */
@Composable
private fun WorkflowDetail(item: ChatItem.ToolCall, p: ToolParsed) {
    val c = TetherTheme.colors
    val meta = remember(item.inputJson) { WorkflowMeta.of(p.input) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        meta.name?.let {
            Badge(it.replace('-', ' '), c.clay)
            Spacer(Modifier.width(8.dp))
        }
        meta.description?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium), maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }
    if (meta.phases.isNotEmpty()) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            meta.phases.forEachIndexed { i, ph -> InfoChip("${i + 1}. $ph", mono = false) }
        }
    }
    val agents = item.toolUseId?.let { LocalWorkflowAgents.current[it] }.orEmpty()
    if (agents.isNotEmpty()) WorkflowAgentList(agents)
    meta.scriptPath?.let { InfoChip(ToolPresentation.displayPath(it, LocalChatCwd.current)) }
    meta.resumeFrom?.let { Text("Resumes run $it", style = MaterialTheme.typography.bodySmall, color = c.faint) }
    p.input?.get("args")?.let { args ->
        OutputPanel(prettyJson(args), label = "Args", collapseAt = 10, stateKey = item.key + ":args")
    }
    val script = p.input?.str("script") ?: partialString(item.inputJson, "script")
    if (!script.isNullOrBlank()) {
        OutputPanel(script, label = "Script", collapseAt = 12, stateKey = item.key + ":script")
    }
    ResultState(item)
    resultOrNull(item)?.let { ResultText(it, item.key) }
}

@Composable
private fun WorkflowAgentList(agents: List<WorkflowAgentLink>) {
    val c = TetherTheme.colors
    val shape = RoundedCornerShape(12.dp)
    val running = agents.count { it.running }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .border(1.dp, c.hairline, shape)
            .padding(vertical = 6.dp),
    ) {
        Text(
            (if (running > 0) "AGENTS · $running of ${agents.size} running" else "AGENTS · ${agents.size}"),
            style = TetherTheme.type.eyebrow, color = c.faint,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        )
        val phases = agents.mapNotNull { it.phase }.distinct()
        val sorted = agents.sortedWith(compareBy({ a -> a.phase?.let { phases.indexOf(it) } ?: Int.MAX_VALUE }, { a -> !a.running }))
        for (a in sorted) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 40.dp)
                    .clickable(role = Role.Button, onClickLabel = "Open transcript", onClick = a.open)
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (a.running) ClaudeSpinner(fontSize = 12f) else StatusDot(c.success, size = 6.dp)
                Spacer(Modifier.width(8.dp))
                Text(a.label, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                a.phase?.let {
                    Spacer(Modifier.width(8.dp))
                    Text(it, style = MaterialTheme.typography.labelSmall, color = c.faint, maxLines = 1)
                }
                Spacer(Modifier.width(6.dp))
                Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null, tint = c.faint, modifier = Modifier.size(14.dp))
            }
        }
    }
}

// ───────────────────────────── MCP / generic ─────────────────────────────

@Composable
private fun McpDetail(item: ChatItem.ToolCall, p: ToolParsed) {
    val c = TetherTheme.colors
    val (server, tool) = ToolPresentation.mcpParts(item.name)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Badge(server, c.info)
        Spacer(Modifier.width(8.dp))
        Text(tool, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium))
    }
    InputList(item, p)
    ResultState(item)
    resultOrNull(item)?.let { ResultText(it, item.key) }
}

@Composable
private fun GenericDetail(item: ChatItem.ToolCall, p: ToolParsed) {
    InputList(item, p)
    ResultState(item)
    resultOrNull(item)?.let { ResultText(it, item.key) }
}

@Composable
private fun InputList(item: ChatItem.ToolCall, p: ToolParsed) {
    val input = p.input
    when {
        input != null && input.isNotEmpty() -> KeyValueList(input, item.key)
        input == null && item.inputJson.isNotBlank() && item.status == ToolStatus.STREAMING_INPUT -> PendingLine("Preparing…")
        input == null && item.inputJson.isNotBlank() && item.inputJson.trim() != "{}" ->
            OutputPanel(item.inputJson, label = "Input", wrap = true, stateKey = item.key + ":in")
    }
}

@Composable
private fun ResultText(text: String, key: String) {
    val clean = remember(text) { cleanOutput(text) }
    val json = remember(clean) { if (clean.startsWith("{") || clean.startsWith("[")) parseJson(clean) else null }
    val isJson = json != null
    val pretty = remember(clean, json) { json?.let { prettyJson(it) } ?: clean }
    if (isJson) OutputPanel(pretty, label = "Result", collapseAt = 16, stateKey = "$key:res")
    else OutputPanel(pretty, label = "Result", wrap = true, collapseAt = 14, stateKey = "$key:res")
}

/** Tool input as readable key/value rows; long values collapse. */
@Composable
internal fun KeyValueList(obj: JsonObject, stateKey: String) {
    val c = TetherTheme.colors
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .border(1.dp, c.hairline, shape)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        obj.entries.take(40).forEach { (k, v) -> KeyValueRow(k, v, "$stateKey:$k") }
        if (obj.size > 40) Text("+ ${obj.size - 40} more fields", style = MaterialTheme.typography.labelSmall, color = c.faint)
    }
}

@Composable
private fun KeyValueRow(key: String, value: JsonElement, stateKey: String) {
    val c = TetherTheme.colors
    val text = remember(value) { value.displayText() }
    val isString = value is JsonPrimitive && value.isString
    val short = text.length <= 42 && !text.contains('\n')
    val keyLabel = key.replace('_', ' ')
    if (short) {
        Row(verticalAlignment = Alignment.Top) {
            Text(keyLabel, style = MaterialTheme.typography.labelMedium, color = c.faint, modifier = Modifier.width(96.dp))
            Spacer(Modifier.width(8.dp))
            SelectionContainer(Modifier.weight(1f)) {
                Text(
                    text,
                    style = if (isString) TetherTheme.type.monoSmall.copy(color = MaterialTheme.colorScheme.onSurface)
                    else TetherTheme.type.monoSmall.copy(color = c.synNumber),
                )
            }
        }
    } else {
        Column {
            Text(keyLabel, style = MaterialTheme.typography.labelMedium, color = c.faint)
            Spacer(Modifier.size(4.dp))
            LongValue(text, stateKey)
        }
    }
}

@Composable
private fun LongValue(text: String, stateKey: String) {
    var expanded by rememberSaveable(stateKey) { mutableStateOf(false) }
    val lineCount = remember(text) { text.count { it == '\n' } + 1 }
    val collapsible = lineCount > 6 || text.length > 420
    SelectionContainer {
        Text(
            text,
            style = TetherTheme.type.monoSmall.copy(color = MaterialTheme.colorScheme.onSurface),
            maxLines = if (collapsible && !expanded) 6 else Int.MAX_VALUE,
            overflow = TextOverflow.Ellipsis,
        )
    }
    if (collapsible) {
        ExpandToggle(expanded = expanded, collapsedLabel = "Show all", onToggle = { expanded = !expanded }, modifier = Modifier.padding(top = 2.dp))
    }
}

// ───────────────────────────── Raw ─────────────────────────────

/** "View raw" — the only place raw JSON is shown. */
@Composable
private fun RawToggle(item: ChatItem.ToolCall, p: ToolParsed) {
    val c = TetherTheme.colors
    val haptics = rememberHaptics()
    var open by rememberSaveable(item.key, "raw") { mutableStateOf(false) }
    Row(
        Modifier
            .heightIn(min = 36.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(role = Role.Button, onClickLabel = if (open) "Hide raw" else "View raw") { haptics.tick(); open = !open }
            .padding(horizontal = 6.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Code, contentDescription = null, tint = c.faint, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(6.dp))
        Text(if (open) "Hide raw" else "View raw", style = MaterialTheme.typography.labelSmall, color = c.faint)
    }
    AnimatedVisibility(
        visible = open,
        enter = expandVertically(tween(Motion.Medium, easing = Motion.Emphasized)) + fadeIn(tween(Motion.Medium)),
        exit = shrinkVertically(tween(Motion.Short)) + fadeOut(tween(Motion.Short)),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val inputText = remember(item.inputJson, p.input) { p.input?.let { prettyJson(it) } ?: item.inputJson }
            CodeView(inputText.ifBlank { "{}" }, "json", label = "input", collapseAt = 30, stateKey = item.key + ":rawin")
            val structured = p.structured
            if (structured != null) {
                val s = remember(structured) { prettyJson(structured) }
                CodeView(s, "json", label = "tool_use_result", collapseAt = 30, stateKey = item.key + ":rawres")
            } else {
                item.result?.text?.takeIf { it.isNotBlank() }?.let {
                    CodeView(it, null, label = "result", collapseAt = 30, stateKey = item.key + ":rawtext")
                }
            }
        }
    }
}
