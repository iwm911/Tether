package app.tether.ui.chat.render

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.tether.ui.components.rememberHaptics
import app.tether.ui.theme.Motion
import app.tether.ui.theme.TetherColors
import app.tether.ui.theme.TetherTheme
import kotlinx.coroutines.delay

// ───────────────────────────── Composition locals (optional; screen may provide) ─────────────────────────────

/** Working directory of the conversation — tool paths are shown relative to it. */
val LocalChatCwd = compositionLocalOf<String?> { null }

/**
 * Subagent transcripts the screen can open read-only, keyed by the Task/Agent tool_use id that
 * spawned them (one-session model). Empty = no "Open transcript" affordance.
 */
val LocalSubagentLinks = compositionLocalOf<Map<String, () -> Unit>> { emptyMap() }

/** Multiplier for code/mono text (Settings → Code text size). */
val LocalCodeFontScale = compositionLocalOf { 1f }

@Composable
internal fun codeStyle(small: Boolean = false): TextStyle {
    val base = if (small) TetherTheme.type.monoSmall else TetherTheme.type.mono
    val scale = LocalCodeFontScale.current.coerceIn(0.7f, 1.6f)
    if (scale == 1f) return base.copy(color = TetherTheme.colors.codeText)
    return base.copy(fontSize = base.fontSize * scale, lineHeight = base.lineHeight * scale, color = TetherTheme.colors.codeText)
}

// ───────────────────────────── Syntax colouring ─────────────────────────────

internal fun synSpanStyle(kind: SynKind, c: TetherColors): SpanStyle = when (kind) {
    SynKind.KEYWORD -> SpanStyle(color = c.synKeyword)
    SynKind.STRING -> SpanStyle(color = c.synString)
    SynKind.COMMENT -> SpanStyle(color = c.synComment, fontStyle = FontStyle.Italic)
    SynKind.NUMBER -> SpanStyle(color = c.synNumber)
    SynKind.TYPE -> SpanStyle(color = c.synType)
    SynKind.KEY -> SpanStyle(color = c.synType)
    SynKind.VARIABLE -> SpanStyle(color = c.synType)
    SynKind.ANNOTATION -> SpanStyle(color = c.synKeyword)
    SynKind.ADD -> SpanStyle(color = c.diffAddText, background = c.diffAddBg)
    SynKind.DEL -> SpanStyle(color = c.diffDelText, background = c.diffDelBg)
    SynKind.HUNK -> SpanStyle(color = c.faint)
    SynKind.META -> SpanStyle(color = c.synKeyword, fontWeight = FontWeight.Medium)
}

internal fun highlighted(code: String, language: String?, c: TetherColors): AnnotatedString {
    val spans = SyntaxHighlighter.highlight(code, language)
    if (spans.isEmpty()) return AnnotatedString(code)
    return buildAnnotatedString {
        append(code)
        for (s in spans) {
            val start = s.start.coerceIn(0, code.length)
            val end = s.end.coerceIn(start, code.length)
            if (end > start) addStyle(synSpanStyle(s.kind, c), start, end)
        }
    }
}

// ───────────────────────────── Copy ─────────────────────────────

/** Small "Copy" pill: clipboard + haptic + a checkmark that morphs back after a moment. */
@Composable
internal fun CopyButton(
    text: () -> String,
    modifier: Modifier = Modifier,
    label: String = "Copy",
    showLabel: Boolean = true,
) {
    val clipboard = LocalClipboardManager.current
    val haptics = rememberHaptics()
    val colors = TetherTheme.colors
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) { delay(1600); copied = false }
    }
    Row(
        modifier
            .heightIn(min = 36.dp)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClickLabel = label) {
                clipboard.setText(AnnotatedString(text()))
                haptics.confirm()
                copied = true
            }
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AnimatedContent(
            targetState = copied,
            transitionSpec = { (fadeIn(tween(Motion.Short)) + scaleIn(initialScale = 0.6f)) togetherWith fadeOut(tween(Motion.Short)) },
            label = "copy",
        ) { done ->
            if (done) Icon(Icons.Rounded.Check, contentDescription = "Copied", tint = colors.success, modifier = Modifier.size(15.dp))
            else Icon(Icons.Rounded.ContentCopy, contentDescription = if (showLabel) null else label, tint = colors.faint, modifier = Modifier.size(14.dp))
        }
        if (showLabel) {
            Spacer(Modifier.width(5.dp))
            AnimatedContent(copied, transitionSpec = { fadeIn(tween(Motion.Short)) togetherWith fadeOut(tween(Motion.Short)) }, label = "copyLabel") { done ->
                Text(
                    if (done) "Copied" else label,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (done) colors.success else colors.faint,
                )
            }
        }
    }
}

// ───────────────────────────── Streaming caret ─────────────────────────────

/** Soft blinking clay bar, the "▍" at the end of streaming text. Fills its placeholder. */
@Composable
internal fun BlinkingCaret(modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "caret")
    val a by t.animateFloat(
        initialValue = 1f,
        targetValue = 0.12f,
        animationSpec = infiniteRepeatable(tween(560), RepeatMode.Reverse),
        label = "caretAlpha",
    )
    val clay = TetherTheme.colors.clay
    Box(modifier.graphicsLayer { alpha = a }, contentAlignment = Alignment.CenterStart) {
        Box(
            Modifier
                .fillMaxHeight()
                .width(3.dp)
                .padding(vertical = 1.dp)
                .clip(RoundedCornerShape(1.5.dp))
                .background(clay)
        )
    }
}

/** A caret on its own line (after a code block, or when no text has arrived yet). */
@Composable
internal fun StandaloneCaret(modifier: Modifier = Modifier) {
    BlinkingCaret(modifier.padding(top = 2.dp).size(width = 8.dp, height = 20.dp))
}

// ───────────────────────────── Middle-ellipsis text ─────────────────────────────

/** Single-line text that keeps the head and (mostly) the tail: `src/…/deep/File.kt`. */
@Composable
internal fun MiddleEllipsisText(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    tailBias: Float = 0.65f,
) {
    val measurer = rememberTextMeasurer(cacheSize = 16)
    BoxWithConstraints(modifier) {
        val maxPx = constraints.maxWidth
        val shown = remember(text, style, maxPx) {
            if (maxPx == Constraints.Infinity || text.length < 4) text
            else {
                fun fits(s: String) = measurer.measure(s, style, maxLines = 1, softWrap = false).size.width <= maxPx
                if (fits(text)) text
                else {
                    var lo = 1
                    var hi = text.length - 1
                    var best = "…"
                    while (lo <= hi) {
                        val mid = (lo + hi) / 2
                        val tail = (mid * tailBias).toInt().coerceAtLeast(1)
                        val head = (mid - tail).coerceAtLeast(0)
                        val candidate = text.take(head) + "…" + text.takeLast(tail)
                        if (fits(candidate)) { best = candidate; lo = mid + 1 } else hi = mid - 1
                    }
                    best
                }
            }
        }
        Text(shown, style = style, color = color, maxLines = 1, softWrap = false, overflow = TextOverflow.Clip)
    }
}

// ───────────────────────────── Collapsible content ─────────────────────────────

/**
 * Shows [content] clipped to [collapsedHeight] with a fade and a "Show more" toggle when it is
 * taller; otherwise shows it as-is with no chrome.
 */
@Composable
internal fun CollapsibleBox(
    modifier: Modifier = Modifier,
    collapsedHeight: Dp = 220.dp,
    fadeColor: Color = MaterialTheme.colorScheme.background,
    key: Any? = null,
    content: @Composable () -> Unit,
) {
    var expanded by rememberSaveable(key) { mutableStateOf(false) }
    var overflowing by remember { mutableStateOf(false) }
    val limitPx = with(LocalDensity.current) { collapsedHeight.roundToPx() }
    Column(modifier.animateContentSize(animationSpec = Motion.gentle())) {
        Box {
            Layout(content = content, modifier = Modifier.clipToBounds()) { measurables, constraints ->
                val loose = constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity)
                val placeables = measurables.map { it.measure(loose) }
                val fullH = placeables.maxOfOrNull { it.height } ?: 0
                val w = placeables.maxOfOrNull { it.width } ?: constraints.minWidth
                val over = fullH > limitPx + limitPx / 6
                if (over != overflowing) overflowing = over
                val h = if (over && !expanded) limitPx else fullH
                layout(w.coerceIn(constraints.minWidth, constraints.maxWidth), h) {
                    placeables.forEach { it.place(0, 0) }
                }
            }
            if (overflowing && !expanded) {
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(48.dp)
                        .background(Brush.verticalGradient(listOf(fadeColor.copy(alpha = 0f), fadeColor)))
                )
            }
        }
        if (overflowing) {
            ExpandToggle(expanded = expanded, collapsedLabel = "Show more", onToggle = { expanded = !expanded })
        }
    }
}

/** "Show all N lines" / "Show less" footer button with a rotating chevron. */
@Composable
internal fun ExpandToggle(
    expanded: Boolean,
    collapsedLabel: String,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    expandedLabel: String = "Show less",
) {
    val haptics = rememberHaptics()
    val rotation by androidx.compose.animation.core.animateFloatAsState(if (expanded) 180f else 0f, Motion.gentle(), label = "chev")
    Row(
        modifier
            .heightIn(min = 40.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(role = Role.Button) { haptics.tick(); onToggle() }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (expanded) expandedLabel else collapsedLabel,
            style = MaterialTheme.typography.labelMedium,
            color = TetherTheme.colors.clay,
        )
        Spacer(Modifier.width(4.dp))
        Icon(Icons.Rounded.ExpandMore, contentDescription = null, tint = TetherTheme.colors.clay, modifier = Modifier.size(16.dp).rotate(rotation))
    }
}

// ───────────────────────────── Output panel ─────────────────────────────

enum class PanelTone { NORMAL, ERROR, MUTED }

/**
 * Mono output panel with a header (label · N lines · Copy), collapsed to [collapseAt] lines.
 * [wrap] = false keeps terminal alignment with horizontal scroll.
 */
@Composable
internal fun OutputPanel(
    text: String,
    modifier: Modifier = Modifier,
    label: String = "Output",
    tone: PanelTone = PanelTone.NORMAL,
    collapseAt: Int = 12,
    wrap: Boolean = false,
    stateKey: Any? = null,
) {
    val c = TetherTheme.colors
    val clean = remember(text) { cleanOutput(text) }
    val lines = remember(clean) { if (clean.isEmpty()) emptyList() else clean.split('\n') }
    var expanded by rememberSaveable(stateKey, label) { mutableStateOf(false) }
    val collapsible = lines.size > collapseAt + 2
    val shown = remember(lines, expanded, collapsible) {
        if (collapsible && !expanded) lines.take(collapseAt).joinToString("\n") else clean
    }
    val (bg, border, fg) = when (tone) {
        PanelTone.NORMAL -> Triple(c.codeBg, c.hairline, c.codeText)
        PanelTone.ERROR -> Triple(c.danger.copy(alpha = if (c.isDark) 0.09f else 0.06f), c.danger.copy(alpha = 0.28f), c.danger)
        PanelTone.MUTED -> Triple(c.codeBg, c.hairline, c.faint)
    }
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(bg)
            .border(1.dp, border, shape)
            .animateContentSize(animationSpec = Motion.gentle())
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                label.uppercase(),
                style = TetherTheme.type.eyebrow,
                color = if (tone == PanelTone.ERROR) c.danger else c.faint,
            )
            if (lines.size > 1) {
                Text(
                    "  ·  ${lines.size} lines",
                    style = MaterialTheme.typography.labelSmall,
                    color = c.faint,
                )
            }
            Spacer(Modifier.weight(1f))
            if (clean.isNotEmpty()) CopyButton(text = { clean })
        }
        if (clean.isEmpty()) {
            Text(
                "No output",
                style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic),
                color = c.faint,
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
            )
        } else {
            val style = codeStyle(small = true).copy(color = fg)
            val textMod = Modifier.padding(start = 12.dp, end = 12.dp, bottom = if (collapsible) 2.dp else 12.dp)
            SelectionContainer {
                if (wrap) {
                    Text(shown, style = style, modifier = textMod)
                } else {
                    Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                        Text(shown, style = style, softWrap = false, modifier = textMod)
                    }
                }
            }
            if (collapsible) {
                ExpandToggle(
                    expanded = expanded,
                    collapsedLabel = "Show all ${lines.size} lines",
                    onToggle = { expanded = !expanded },
                    modifier = Modifier.padding(start = 2.dp, bottom = 2.dp),
                )
            }
        }
    }
}

/** Small caption row: eyebrow label, optional trailing content. */
@Composable
internal fun DetailCaption(text: String, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(text.uppercase(), style = TetherTheme.type.eyebrow, color = TetherTheme.colors.faint)
        trailing?.invoke()
    }
}
