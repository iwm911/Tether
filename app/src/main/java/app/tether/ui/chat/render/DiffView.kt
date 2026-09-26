package app.tether.ui.chat.render

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.tether.ui.components.Hairline
import app.tether.ui.theme.Motion
import app.tether.ui.theme.TetherTheme

/** "+12 −3" in success / danger colours. */
@Composable
internal fun DiffStat(added: Int, removed: Int, modifier: Modifier = Modifier) {
    val c = TetherTheme.colors
    val text = remember(added, removed, c) {
        buildAnnotatedString {
            withStyle(SpanStyle(color = c.success)) { append("+$added") }
            append(" ")
            withStyle(SpanStyle(color = c.danger)) { append("−$removed") }
        }
    }
    Text(text, style = TetherTheme.type.monoSmall.copy(fontWeight = FontWeight.Medium), modifier = modifier, maxLines = 1)
}

/**
 * Unified diff card: old/new line-number gutters, add/del backgrounds, faint hunk headers,
 * horizontal scroll for long lines, collapsed after [collapseAt] lines.
 */
@Composable
internal fun DiffView(
    diff: DiffData,
    modifier: Modifier = Modifier,
    path: String? = null,
    collapseAt: Int = 60,
    stateKey: Any? = null,
) {
    val c = TetherTheme.colors
    val shape = RoundedCornerShape(14.dp)
    val total = diff.lines.size
    val collapsible = total > collapseAt + 4
    var expanded by rememberSaveable(stateKey, "diff") { mutableStateOf(false) }
    val shown = if (collapsible && !expanded) diff.lines.subList(0, collapseAt) else diff.lines
    val mono = codeStyle(small = true)
    val showNumbers = diff.hasNumbers
    val digits = diff.maxLineNo.toString().length.coerceAtLeast(2)
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val gutterW = remember(digits, mono) {
        with(density) { measurer.measure("0".repeat(digits), mono).size.width.toDp() } + 10.dp
    }
    var viewport by remember { mutableIntStateOf(0) }
    val unified = remember(diff, path) { DiffModel.toUnified(diff, path) }

    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(c.codeBg)
            .border(1.dp, c.hairline, shape)
            .animateContentSize(animationSpec = Motion.gentle())
            .semantics { contentDescription = "Diff, ${diff.added} lines added, ${diff.removed} removed" }
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 40.dp).padding(start = 14.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                if (path != null) {
                    MiddleEllipsisText(
                        path,
                        style = TetherTheme.type.monoSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(Modifier.width(10.dp))
                } else {
                    Text("DIFF", style = TetherTheme.type.eyebrow, color = c.faint)
                    Spacer(Modifier.width(10.dp))
                }
                DiffStat(diff.added, diff.removed)
            }
            CopyButton(text = { unified })
        }
        Hairline()
        if (diff.isEmpty) {
            Text(
                "No changes",
                style = MaterialTheme.typography.bodySmall,
                color = c.faint,
                modifier = Modifier.padding(14.dp),
            )
        } else {
            Box(
                Modifier
                    .fillMaxWidth()
                    .onSizeChanged { viewport = it.width }
                    .horizontalScroll(rememberScrollState())
            ) {
                SelectionContainer {
                    Column(
                        Modifier
                            .widthIn(min = with(density) { viewport.toDp() })
                            .width(IntrinsicSize.Max)
                            .padding(vertical = 6.dp)
                    ) {
                        shown.forEach { line -> DiffRow(line, showNumbers, gutterW, mono) }
                    }
                }
            }
        }
        if (collapsible) {
            ExpandToggle(
                expanded = expanded,
                collapsedLabel = "Show all $total lines",
                onToggle = { expanded = !expanded },
                modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
            )
        }
    }
}

@Composable
private fun DiffRow(line: DiffLine, showNumbers: Boolean, gutterW: androidx.compose.ui.unit.Dp, mono: androidx.compose.ui.text.TextStyle) {
    val c = TetherTheme.colors
    val (bg, fg, sign) = when (line.kind) {
        DiffLineKind.ADD -> Triple(c.diffAddBg, c.diffAddText, "+")
        DiffLineKind.DEL -> Triple(c.diffDelBg, c.diffDelText, "−")
        DiffLineKind.CONTEXT -> Triple(Color.Transparent, c.codeText, " ")
        DiffLineKind.HUNK -> Triple(c.hairline.copy(alpha = 0.45f), c.faint, "")
        DiffLineKind.NOTE -> Triple(Color.Transparent, c.faint, "")
    }
    val gutterColor = when (line.kind) {
        DiffLineKind.ADD -> c.diffAddText.copy(alpha = 0.55f)
        DiffLineKind.DEL -> c.diffDelText.copy(alpha = 0.55f)
        else -> c.faint.copy(alpha = 0.7f)
    }
    Row(
        Modifier
            .fillMaxWidth()
            .background(bg)
            .padding(end = 16.dp),
        verticalAlignment = Alignment.Top,
    ) {
        if (line.kind == DiffLineKind.HUNK || line.kind == DiffLineKind.NOTE) {
            Text(
                line.text,
                style = mono.copy(color = fg),
                softWrap = false,
                modifier = Modifier.padding(start = 12.dp, top = 3.dp, bottom = 3.dp),
            )
            return@Row
        }
        if (showNumbers) {
            Text(
                line.oldNo?.toString() ?: "",
                style = mono.copy(color = gutterColor, textAlign = TextAlign.End),
                softWrap = false,
                modifier = Modifier.width(gutterW).padding(end = 2.dp),
            )
            Text(
                line.newNo?.toString() ?: "",
                style = mono.copy(color = gutterColor, textAlign = TextAlign.End),
                softWrap = false,
                modifier = Modifier.width(gutterW).padding(end = 2.dp),
            )
        }
        Text(
            sign,
            style = mono.copy(color = fg, fontWeight = FontWeight.Medium),
            modifier = Modifier.padding(start = if (showNumbers) 8.dp else 12.dp).width(14.dp),
        )
        Text(
            line.text.ifEmpty { " " },
            style = mono.copy(color = fg),
            softWrap = false,
        )
    }
}
