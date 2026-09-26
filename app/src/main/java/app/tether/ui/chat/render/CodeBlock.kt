package app.tether.ui.chat.render

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.tether.ui.components.Hairline
import app.tether.ui.theme.Motion
import app.tether.ui.theme.TetherTheme

/**
 * A code card: language eyebrow, line count, Copy; horizontally scrollable, syntax-highlighted mono
 * body with line numbers (> 3 lines), collapsed to 18 lines behind "Show all N lines".
 */
@Composable
fun CodeBlock(code: String, language: String?, modifier: Modifier = Modifier) {
    CodeView(code = code, language = language, modifier = modifier)
}

/**
 * The configurable code view behind [CodeBlock].
 * @param startLine first line number shown in the gutter (Read previews start mid-file).
 * @param lineNumbers null = automatic (on for more than 3 lines).
 * @param label header label; defaults to the language.
 */
@Composable
internal fun CodeView(
    code: String,
    language: String?,
    modifier: Modifier = Modifier,
    startLine: Int = 1,
    collapseAt: Int = 18,
    lineNumbers: Boolean? = null,
    label: String? = null,
    showHeader: Boolean = true,
    stateKey: Any? = null,
) {
    val c = TetherTheme.colors
    val body = remember(code) { code.trimEnd('\n') }
    val lines = remember(body) { body.split('\n') }
    val lineCount = lines.size
    val collapsible = lineCount > collapseAt + 2
    var expanded by rememberSaveable(stateKey ?: body.hashCode(), collapseAt) { mutableStateOf(false) }
    val full = remember(body, language, c) { highlighted(body, language, c) }
    val shown = remember(full, lines, expanded, collapsible, collapseAt) {
        if (!collapsible || expanded) full
        else {
            var end = 0
            for (k in 0 until collapseAt) end += lines[k].length + 1
            full.subSequence(0, (end - 1).coerceIn(0, full.length))
        }
    }
    val shownLines = if (collapsible && !expanded) collapseAt else lineCount
    val showNumbers = lineNumbers ?: (lineCount > 3)
    val style = codeStyle()
    val shape = RoundedCornerShape(14.dp)
    val langLabel = (label ?: language?.takeIf { it.isNotBlank() } ?: "code").lowercase()

    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(c.codeBg)
            .animateContentSize(animationSpec = Motion.gentle())
            .semantics { contentDescription = "Code block, $langLabel, $lineCount lines" }
    ) {
        if (showHeader) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 40.dp).padding(start = 14.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(langLabel, style = MaterialTheme.typography.labelMedium, color = c.faint)
                if (lineCount > 1) {
                    Text(
                        "  ·  $lineCount lines",
                        style = MaterialTheme.typography.labelSmall,
                        color = c.faint.copy(alpha = 0.8f),
                    )
                }
                Spacer(Modifier.weight(1f))
                CopyButton(text = { body })
            }
        }
        Box {
            Row(Modifier.fillMaxWidth().padding(top = if (showHeader) 0.dp else 10.dp, bottom = if (collapsible) 4.dp else 12.dp)) {
                if (showNumbers) {
                    val numbers = remember(startLine, shownLines) {
                        (startLine until startLine + shownLines).joinToString("\n")
                    }
                    Text(
                        numbers,
                        style = style.copy(color = c.faint.copy(alpha = 0.7f), textAlign = TextAlign.End),
                        softWrap = false,
                        modifier = Modifier.padding(start = 10.dp, end = 12.dp),
                    )
                }
                Box(
                    Modifier
                        .weight(1f)
                        .horizontalScroll(rememberScrollState())
                ) {
                    SelectionContainer {
                        Text(
                            shown,
                            style = style,
                            softWrap = false,
                            modifier = Modifier.padding(start = if (showNumbers) 0.dp else 14.dp, end = 16.dp),
                        )
                    }
                }
            }
            if (collapsible && !expanded) {
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(40.dp)
                        .background(Brush.verticalGradient(listOf(c.codeBg.copy(alpha = 0f), c.codeBg)))
                )
            }
        }
        if (collapsible) {
            ExpandToggle(
                expanded = expanded,
                collapsedLabel = "Show all $lineCount lines",
                onToggle = { expanded = !expanded },
                modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
            )
        }
    }
}
