package app.tether.ui.chat.render

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckBox
import androidx.compose.material.icons.rounded.CheckBoxOutlineBlank
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.tether.ui.theme.Inter
import app.tether.ui.theme.Mono
import app.tether.ui.theme.Serif
import app.tether.ui.theme.TetherTheme
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Renders Claude's Markdown: headings, paragraphs, lists (nested, task lists), quotes, rules,
 * fenced/indented code ([CodeBlock]), GFM tables, and inline bold/italic/strike/code/links/paths.
 * Text is selectable. While [streaming], a soft clay caret blinks at the end.
 */
@Composable
fun Markdown(text: String, modifier: Modifier = Modifier, streaming: Boolean = false) {
    MarkdownContent(text = text, modifier = modifier, streaming = streaming, tone = MdTone.BODY)
}

/** Visual variants of the renderer used inside the app. */
internal enum class MdTone {
    /** Assistant prose. */
    BODY,
    /** Smaller prose inside tool detail panels / plan cards. */
    COMPACT,
    /** Faint italic prose for thinking. */
    THOUGHT,
}

@Immutable
internal data class MdPalette(
    val text: Color,
    val muted: Color,
    val link: Color,
    val codeText: Color,
    val codeBg: Color,
    val path: Color,
    val rule: Color,
    val quoteRule: Color,
    val check: Color,
    val headerBg: Color,
)

@Composable
internal fun MarkdownContent(
    text: String,
    modifier: Modifier = Modifier,
    streaming: Boolean = false,
    tone: MdTone = MdTone.BODY,
) {
    val blocks = remember(text) { MarkdownParser.parse(text) }
    val c = TetherTheme.colors
    val scheme = MaterialTheme.colorScheme
    val palette = MdPalette(
        text = when (tone) {
            MdTone.THOUGHT -> scheme.onSurfaceVariant
            else -> scheme.onSurface
        },
        muted = scheme.onSurfaceVariant,
        link = c.clay,
        codeText = c.codeText,
        codeBg = c.codeBg,
        path = if (tone == MdTone.THOUGHT) scheme.onSurfaceVariant else scheme.onSurface,
        rule = c.hairline,
        quoteRule = c.clay.copy(alpha = 0.45f),
        check = c.success,
        headerBg = scheme.surfaceContainerHigh,
    )
    val base = when (tone) {
        MdTone.BODY -> MaterialTheme.typography.bodyLarge.copy(fontFamily = Serif, fontSize = 16.5.sp, lineHeight = 26.sp) // SPEC §8: prose in the serif
        MdTone.COMPACT -> MaterialTheme.typography.bodyMedium
        MdTone.THOUGHT -> MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic)
    }.copy(color = palette.text)

    SelectionContainer(modifier) {
        if (blocks.isEmpty()) {
            if (streaming) StandaloneCaret() else Box(Modifier)
        } else {
            MdBlocks(blocks, palette, base, caret = streaming, spacing = if (tone == MdTone.BODY) 12 else 8, depth = 0)
        }
    }
}

// ───────────────────────────── Blocks ─────────────────────────────

private fun acceptsCaret(b: MdBlock): Boolean = when (b) {
    is MdBlock.Paragraph, is MdBlock.Heading -> true
    is MdBlock.ListBlock -> b.items.lastOrNull()?.blocks?.lastOrNull()?.let { acceptsCaret(it) } ?: false
    is MdBlock.Quote -> b.blocks.lastOrNull()?.let { acceptsCaret(it) } ?: false
    else -> false
}

@Composable
private fun MdBlocks(
    blocks: List<MdBlock>,
    palette: MdPalette,
    base: TextStyle,
    caret: Boolean,
    spacing: Int,
    depth: Int,
    modifier: Modifier = Modifier,
) {
    val lastIdx = blocks.lastIndex
    val inlineCaret = caret && blocks.isNotEmpty() && acceptsCaret(blocks[lastIdx])
    Column(modifier, verticalArrangement = Arrangement.spacedBy(spacing.dp)) {
        blocks.forEachIndexed { idx, block ->
            val withCaret = inlineCaret && idx == lastIdx
            when (block) {
                is MdBlock.Paragraph -> MdParagraph(block.inlines, palette, base, withCaret)
                is MdBlock.Heading -> MdHeading(block, palette, base, withCaret, first = idx == 0)
                is MdBlock.CodeFence -> CodeView(
                    code = block.code,
                    language = block.language,
                    label = block.language,
                    modifier = Modifier.padding(vertical = 2.dp),
                    // Positional identity + a stable key: "Show all" survives streaming deltas.
                    stateKey = "md-fence",
                )
                is MdBlock.ListBlock -> MdList(block, palette, base, withCaret, depth)
                is MdBlock.Quote -> MdQuote(block, palette, base, withCaret, depth)
                MdBlock.Rule -> Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp)
                        .height(1.dp)
                        .background(palette.rule)
                )
                is MdBlock.Table -> MdTable(block, palette, base)
            }
        }
        if (caret && !inlineCaret) StandaloneCaret()
    }
}

@Composable
private fun MdParagraph(inlines: List<MdInline>, palette: MdPalette, style: TextStyle, caret: Boolean) {
    val rich = remember(inlines, palette) { buildRich(inlines, palette) }
    RichText(rich, style, caret = caret)
}

@Composable
private fun MdHeading(h: MdBlock.Heading, palette: MdPalette, base: TextStyle, caret: Boolean, first: Boolean) {
    val scale = (base.fontSize.value / 16f).coerceIn(0.8f, 1.2f)
    val style = when (h.level) {
        1 -> TextStyle(fontFamily = Serif, fontWeight = FontWeight.SemiBold, fontSize = (24 * scale).sp, lineHeight = (30 * scale).sp, letterSpacing = (-0.2).sp)
        2 -> TextStyle(fontFamily = Serif, fontWeight = FontWeight.Medium, fontSize = (20.5f * scale).sp, lineHeight = (27 * scale).sp, letterSpacing = (-0.1).sp)
        3 -> TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = (17 * scale).sp, lineHeight = (24 * scale).sp)
        4 -> TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = (15.5f * scale).sp, lineHeight = (22 * scale).sp)
        else -> TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = (14 * scale).sp, lineHeight = (20 * scale).sp, letterSpacing = 0.2.sp)
    }.copy(
        color = if (h.level >= 5) palette.muted else palette.text,
        fontStyle = base.fontStyle,
    )
    val rich = remember(h.inlines, palette) { buildRich(h.inlines, palette) }
    val top = if (first) 0.dp else when (h.level) { 1 -> 10.dp; 2 -> 8.dp; else -> 4.dp }
    Column(Modifier.padding(top = top)) {
        RichText(rich, style, caret = caret)
        if (h.level == 1) {
            Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(palette.rule))
        }
    }
}

@Composable
private fun MdList(list: MdBlock.ListBlock, palette: MdPalette, base: TextStyle, caret: Boolean, depth: Int) {
    val density = LocalDensity.current
    val lineHeightDp = with(density) { (if (base.lineHeight.isSp) base.lineHeight else (base.fontSize * 1.5f)).toDp() }
    val lastNumber = list.start + list.items.size - 1
    val markerWidth = if (list.ordered) (lastNumber.toString().length * 9 + 10).dp else 16.dp
    val bullet = when (depth % 3) { 0 -> "•"; 1 -> "◦"; else -> "▪" }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        list.items.forEachIndexed { idx, item ->
            val itemCaret = caret && idx == list.items.lastIndex
            Row(Modifier.fillMaxWidth()) {
                Box(Modifier.width(markerWidth).height(lineHeightDp), contentAlignment = if (list.ordered) Alignment.CenterEnd else Alignment.Center) {
                    if (list.ordered) {
                        Text(
                            "${list.start + idx}.",
                            style = base.copy(color = palette.muted, fontFeatureSettings = "tnum", fontStyle = FontStyle.Normal),
                            maxLines = 1,
                        )
                    } else {
                        Text(bullet, style = base.copy(color = palette.muted, fontWeight = FontWeight.Bold), maxLines = 1)
                    }
                }
                Spacer(Modifier.width(if (list.ordered) 8.dp else 6.dp))
                if (item.checked != null) {
                    Box(Modifier.height(lineHeightDp), contentAlignment = Alignment.Center) {
                        Icon(
                            if (item.checked) Icons.Rounded.CheckBox else Icons.Rounded.CheckBoxOutlineBlank,
                            contentDescription = if (item.checked) "Done" else "Not done",
                            tint = if (item.checked) palette.check else palette.muted,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                }
                Box(Modifier.weight(1f)) {
                    val itemBase = if (item.checked == true) base.copy(color = palette.muted) else base
                    if (item.blocks.isEmpty()) {
                        if (itemCaret) StandaloneCaret()
                    } else {
                        MdBlocks(item.blocks, palette, itemBase, caret = itemCaret, spacing = 6, depth = depth + 1)
                    }
                }
            }
        }
    }
}

@Composable
private fun MdQuote(q: MdBlock.Quote, palette: MdPalette, base: TextStyle, caret: Boolean, depth: Int) {
    val rule = palette.quoteRule
    Box(
        Modifier
            .fillMaxWidth()
            .drawBehind {
                val w = 3.dp.toPx()
                drawRoundRect(rule, size = Size(w, size.height), cornerRadius = CornerRadius(w / 2, w / 2))
            }
            .padding(start = 14.dp, top = 2.dp, bottom = 2.dp)
    ) {
        MdBlocks(
            q.blocks,
            palette.copy(text = palette.muted),
            base.copy(color = palette.muted),
            caret = caret,
            spacing = 8,
            depth = depth,
        )
    }
}

@Composable
private fun MdTable(t: MdBlock.Table, palette: MdPalette, base: TextStyle) {
    val cols = t.aligns.size.coerceAtLeast(t.header.size)
    if (cols == 0) return
    val header = remember(t, palette) { t.header.map { buildRich(listOf(MdInline.Bold(it)), palette) } }
    val rows = remember(t, palette) { t.rows.map { r -> r.map { buildRich(it, palette) } } }
    val cellStyle = base.copy(fontSize = base.fontSize * 0.92f, lineHeight = base.lineHeight * 0.92f)
    val shape = RoundedCornerShape(12.dp)
    val hairline = palette.rule
    val headerBg = palette.headerBg
    val density = LocalDensity.current

    Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        Layout(
            modifier = Modifier.clip(shape).border(1.dp, hairline, shape),
            content = {
                for (cIdx in 0 until cols) {
                    TableCell(header.getOrNull(cIdx) ?: RichContent.Empty, cellStyle, t.aligns.getOrElse(cIdx) { MdAlign.START }, headerBg, hairline, last = cIdx == cols - 1)
                }
                rows.forEach { r ->
                    for (cIdx in 0 until cols) {
                        TableCell(r.getOrNull(cIdx) ?: RichContent.Empty, cellStyle, t.aligns.getOrElse(cIdx) { MdAlign.START }, null, hairline, last = cIdx == cols - 1)
                    }
                }
            },
        ) { measurables, _ ->
            val maxCell = with(density) { 280.dp.roundToPx() }
            val minCell = with(density) { 56.dp.roundToPx() }
            val hairPx = with(density) { 1.dp.roundToPx() }
            val nRows = measurables.size / cols
            val colW = IntArray(cols)
            measurables.forEachIndexed { i, m ->
                val cIdx = i % cols
                colW[cIdx] = max(colW[cIdx], m.maxIntrinsicWidth(Constraints.Infinity).coerceIn(minCell, maxCell))
            }
            val rowH = IntArray(nRows)
            measurables.forEachIndexed { i, m ->
                val r = i / cols
                if (r < nRows) rowH[r] = max(rowH[r], m.minIntrinsicHeight(colW[i % cols]))
            }
            val placeables = measurables.mapIndexed { i, m ->
                val r = (i / cols).coerceAtMost(nRows - 1)
                // Every row but the last gets a 1px bottom hairline drawn by the cell itself.
                m.measure(Constraints.fixed(colW[i % cols], rowH[r] + if (r < nRows - 1) hairPx else 0))
            }
            val width = colW.sum()
            val height = rowH.sum() + hairPx * (nRows - 1).coerceAtLeast(0)
            layout(width, height) {
                var y = 0
                for (r in 0 until nRows) {
                    var x = 0
                    for (cIdx in 0 until cols) {
                        placeables[r * cols + cIdx].place(x, y)
                        x += colW[cIdx]
                    }
                    y += rowH[r] + if (r < nRows - 1) hairPx else 0
                }
            }
        }
    }
}

@Composable
private fun TableCell(rich: RichContent, style: TextStyle, align: MdAlign, bg: Color?, hairline: Color, last: Boolean) {
    Box(
        Modifier
            .then(if (bg != null) Modifier.background(bg) else Modifier)
            .drawBehind {
                val px = 1.dp.toPx()
                drawRect(hairline, topLeft = Offset(0f, size.height - px), size = Size(size.width, px))
                if (!last) drawRect(hairline, topLeft = Offset(size.width - px, 0f), size = Size(px, size.height))
            }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = when (align) {
            MdAlign.START -> Alignment.TopStart
            MdAlign.CENTER -> Alignment.TopCenter
            MdAlign.END -> Alignment.TopEnd
        },
    ) {
        RichText(
            rich,
            style.copy(
                textAlign = when (align) {
                    MdAlign.START -> TextAlign.Start
                    MdAlign.CENTER -> TextAlign.Center
                    MdAlign.END -> TextAlign.End
                }
            ),
            caret = false,
        )
    }
}

// ───────────────────────────── Inline → AnnotatedString ─────────────────────────────

/** Annotated text plus the character ranges that are inline code (painted with a rounded background). */
@Immutable
internal class RichContent(val text: AnnotatedString, val codeRanges: List<IntRange>) {
    companion object {
        val Empty = RichContent(AnnotatedString(""), emptyList())
    }
}

private const val THIN_SPACE = ' '
private const val CARET_ID = "tether.caret"

internal fun buildRich(inlines: List<MdInline>, palette: MdPalette): RichContent {
    val ranges = ArrayList<IntRange>()
    val linkStyles = TextLinkStyles(
        style = SpanStyle(color = palette.link, textDecoration = TextDecoration.Underline),
        pressedStyle = SpanStyle(color = palette.link, background = palette.link.copy(alpha = 0.16f), textDecoration = TextDecoration.Underline),
    )
    val codeSpan = SpanStyle(fontFamily = Mono, fontSize = 0.86.em, color = palette.codeText, fontStyle = FontStyle.Normal, fontWeight = FontWeight.Normal)
    val pathSpan = SpanStyle(fontFamily = Mono, fontSize = 0.88.em, color = palette.path, fontStyle = FontStyle.Normal)

    val text = buildAnnotatedString {
        var lastChar: Char? = null
        fun put(str: String) {
            if (str.isEmpty()) return
            append(str)
            lastChar = str[str.length - 1]
        }
        fun putC(ch: Char) {
            append(ch)
            lastChar = ch
        }
        fun emit(list: List<MdInline>, inLink: Boolean) {
            list.forEachIndexed { idx, node ->
                when (node) {
                    is MdInline.Text -> put(node.text)
                    is MdInline.Bold -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { emit(node.children, inLink) }
                    is MdInline.Italic -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { emit(node.children, inLink) }
                    is MdInline.Strike -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { emit(node.children, inLink) }
                    is MdInline.Code -> {
                        val prev = lastChar
                        if (prev != null && !prev.isWhitespace() && prev != '(' && prev != THIN_SPACE) putC(THIN_SPACE)
                        val start = length
                        withStyle(codeSpan) { put(node.code) }
                        if (length > start) ranges += start until length
                        val next = list.getOrNull(idx + 1)
                        val nextChar = when (next) {
                            is MdInline.Text -> next.text.firstOrNull()
                            null -> null
                            else -> 'x'
                        }
                        if (nextChar != null && !nextChar.isWhitespace() && nextChar !in ".,;:!?)") putC(THIN_SPACE)
                    }
                    is MdInline.Link -> {
                        if (inLink) emit(node.children, true)
                        else withLink(LinkAnnotation.Url(node.url, linkStyles)) { emit(node.children, true) }
                    }
                    is MdInline.Path -> withStyle(pathSpan) { put(node.path) }
                    MdInline.LineBreak -> putC('\n')
                }
            }
        }
        emit(inlines, false)
    }
    return RichContent(text, ranges)
}

// ───────────────────────────── Rich text view ─────────────────────────────

@Composable
internal fun RichText(rich: RichContent, style: TextStyle, caret: Boolean, modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE) {
    val codeBg = TetherTheme.colors.codeBg
    val text = remember(rich, caret) {
        if (!caret) rich.text
        else buildAnnotatedString {
            append(rich.text)
            appendInlineContent(CARET_ID, "▍")
        }
    }
    val inline = if (caret) CaretInlineContent else emptyMap()
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val ranges = rich.codeRanges
    Text(
        text = text,
        style = style,
        inlineContent = inline,
        maxLines = maxLines,
        onTextLayout = { layout = it },
        modifier = modifier.then(
            if (ranges.isEmpty()) Modifier
            else Modifier.drawBehind {
                val l = layout ?: return@drawBehind
                if (l.layoutInput.text.length != text.length) return@drawBehind
                val pad = 2.5.dp.toPx()
                val radius = 5.dp.toPx()
                for (r in ranges) drawCodeBackground(l, r, codeBg, pad, radius)
            }
        ),
    )
}

private val CaretInlineContent: Map<String, InlineTextContent> = mapOf(
    CARET_ID to InlineTextContent(
        Placeholder(width = 0.55.em, height = 1.05.em, placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter)
    ) {
        BlinkingCaret(Modifier.fillMaxWidth().padding(start = 2.dp))
    }
)

private fun DrawScope.drawCodeBackground(l: TextLayoutResult, r: IntRange, color: Color, pad: Float, radius: Float) {
    val len = l.layoutInput.text.length
    if (r.first < 0 || r.last >= len || r.isEmpty()) return
    val firstLine = l.getLineForOffset(r.first)
    val lastLine = l.getLineForOffset(r.last)
    for (line in firstLine..lastLine) {
        if (line >= l.lineCount) break
        val a = max(r.first, l.getLineStart(line))
        val b = min(r.last, l.getLineEnd(line, visibleEnd = true) - 1)
        if (b < a) continue
        val boxA = l.getBoundingBox(a)
        val boxB = l.getBoundingBox(b)
        val left = min(boxA.left, boxB.right)
        val right = max(boxA.left, boxB.right)
        val top = l.getLineTop(line)
        val bottom = l.getLineBottom(line)
        val h = bottom - top
        val inset = h * 0.13f
        drawRoundRect(
            color = color,
            topLeft = Offset(left - pad, top + inset),
            size = Size(abs(right - left) + pad * 2, h - inset * 2),
            cornerRadius = CornerRadius(radius, radius),
        )
    }
}
