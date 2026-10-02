package app.tether.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.tether.core.MachineAccents
import app.tether.core.RunStatus
import app.tether.core.projectRoot
import app.tether.ui.theme.Space
import app.tether.ui.theme.TetherTheme
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

// ───────────────────────────── Haptics ─────────────────────────────

val LocalHapticsEnabled = staticCompositionLocalOf { true }

/**
 * The Claude subscription behind the conversation on screen ("Claude Team"), or null for API-key
 * billing. Subscription usage isn't charged per token, so dollar figures are hidden when set.
 */
val LocalPlanName = staticCompositionLocalOf<String?> { null }

class Haptics(private val fire: (HapticFeedbackType) -> Unit, private val enabled: Boolean) {
    fun tick() { if (enabled) fire(HapticFeedbackType.TextHandleMove) }
    fun confirm() { if (enabled) fire(HapticFeedbackType.LongPress) }
}

@Composable
fun rememberHaptics(): Haptics {
    val h = LocalHapticFeedback.current
    val enabled = LocalHapticsEnabled.current
    return remember(h, enabled) { Haptics({ h.performHapticFeedback(it) }, enabled) }
}

// ───────────────────────────── Status ─────────────────────────────

@Composable
fun RunStatus.color(): Color = when (this) {
    RunStatus.STARTING -> TetherTheme.colors.info
    RunStatus.WORKING -> TetherTheme.colors.clay
    RunStatus.AWAITING_PERMISSION -> TetherTheme.colors.warning
    RunStatus.IDLE -> TetherTheme.colors.success
    RunStatus.ENDED -> TetherTheme.colors.faint
    RunStatus.FAILED -> TetherTheme.colors.danger
}

val RunStatus.label: String
    get() = when (this) {
        RunStatus.STARTING -> "Starting"
        RunStatus.WORKING -> "Working"
        RunStatus.AWAITING_PERMISSION -> "Needs you"
        RunStatus.IDLE -> "Your turn"
        RunStatus.ENDED -> "Ended"
        RunStatus.FAILED -> "Failed"
    }

val RunStatus.isLive: Boolean get() = this == RunStatus.STARTING || this == RunStatus.WORKING || this == RunStatus.AWAITING_PERMISSION

/** "Running 2 background tasks" — the session's turn is over but its shells / subagents are not. */
fun backgroundLabel(count: Int): String = if (count == 1) "Running 1 background task" else "Running $count background tasks"

/** A dot that breathes while [pulsing]. */
@Composable
fun StatusDot(color: Color, pulsing: Boolean = false, size: Dp = 8.dp, modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "dot")
    val halo by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1400, easing = LinearEasing)), label = "halo")
    Box(modifier.size(size * 2.2f), contentAlignment = Alignment.Center) {
        if (pulsing) {
            Box(
                Modifier
                    .size(size * (1f + 1.2f * halo))
                    .graphicsLayer { alpha = (1f - halo) * 0.55f }
                    .background(color, CircleShape)
            )
        }
        Box(Modifier.size(size).background(color, CircleShape))
    }
}

@Composable
fun StatusPill(status: RunStatus, modifier: Modifier = Modifier) {
    val c by animateColorAsState(status.color(), label = "pill")
    Row(
        modifier.padding(end = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(c, pulsing = status.isLive, size = 6.dp)
        AnimatedContent(status.label, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "pilltext") {
            Text(it, style = MaterialTheme.typography.labelMedium, color = c)
        }
    }
}

// ───────────────────────────── Claude-style spinner ─────────────────────────────

private val SpinnerGlyphs = listOf("·", "✢", "✳", "✶", "✻", "✽", "✻", "✶", "✳", "✢")
val WorkingVerbs = listOf(
    "Thinking", "Pondering", "Crafting", "Brewing", "Weaving", "Tinkering", "Conjuring", "Mulling",
    "Noodling", "Percolating", "Reticulating", "Cogitating", "Synthesizing", "Wrangling", "Musing",
)

/** The ✻ glyph animation Claude Code shows while working. */
@Composable
fun ClaudeSpinner(modifier: Modifier = Modifier, color: Color = TetherTheme.colors.clay, fontSize: Float = 16f) {
    var i by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(110); i = (i + 1) % SpinnerGlyphs.size } }
    Box(modifier.widthIn(min = (fontSize * 1.2f).dp), contentAlignment = Alignment.Center) {
        Text(SpinnerGlyphs[i], color = color, fontSize = fontSize.sp, fontWeight = FontWeight.Bold)
    }
}

/** Text with a moving highlight band — used for the working verb. */
@Composable
fun ShimmerText(text: String, style: TextStyle, base: Color, highlight: Color = TetherTheme.colors.shimmer, modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE) {
    val t = rememberInfiniteTransition(label = "shimmer")
    val x by t.animateFloat(-0.4f, 1.4f, infiniteRepeatable(tween(1800, easing = LinearEasing), RepeatMode.Restart), label = "x")
    Text(
        text,
        modifier = modifier,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        style = style.copy(
            brush = Brush.linearGradient(
                0f to base, (x - 0.18f).coerceIn(0f, 1f) to base, x.coerceIn(0f, 1f) to highlight,
                (x + 0.18f).coerceIn(0f, 1f) to base, 1f to base,
                start = Offset.Zero, end = Offset(600f, 0f),
            )
        ),
    )
}

/** "✻ Pondering… 14s · ↓ 1.2k tokens" — the live working line. */
@Composable
fun WorkingIndicator(since: Long?, tokens: Int? = null, verbSeed: Int = 0, modifier: Modifier = Modifier, label: String? = null) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(1000); now = System.currentTimeMillis() } }
    val verb = label ?: WorkingVerbs[abs(verbSeed + ((since ?: 0L) / 7000L).toInt()) % WorkingVerbs.size]
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        ClaudeSpinner()
        Spacer(Modifier.width(6.dp))
        ShimmerText("$verb…", MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium), base = TetherTheme.colors.clay)
        val parts = buildList {
            if (since != null) add(formatElapsed(now - since))
            if (tokens != null && tokens > 0) add("↓ ${compactNumber(tokens.toLong())} tokens")
        }
        if (parts.isNotEmpty()) {
            Spacer(Modifier.width(8.dp))
            Text(parts.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = TetherTheme.colors.faint)
        }
    }
}

// ───────────────────────────── Surfaces ─────────────────────────────

/** Soft filled card — no heavy outline (a whisper of an edge in light). The primary container in Tether. */
@Composable
fun TetherCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    color: Color = TetherTheme.colors.card,
    border: Color = TetherTheme.colors.cardBorder,
    shape: RoundedCornerShape = RoundedCornerShape(24.dp),
    contentPadding: PaddingValues = PaddingValues(Space.xl),
    content: @Composable () -> Unit,
) {
    val clickable = if (onClick != null || onLongClick != null)
        Modifier.combinedClickable(onClick = { onClick?.invoke() }, onLongClick = onLongClick) else Modifier
    Box(
        modifier
            .clip(shape)
            .background(color)
            .then(if (border.alpha > 0f) Modifier.border(BorderStroke(1.dp, border), shape) else Modifier)
            .then(clickable)
            .padding(contentPadding)
    ) { content() }
}

@Composable
fun Hairline(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(TetherTheme.colors.hairline))
}

/** Small uppercase label above a group. */
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier, trailing: (@Composable RowScope.() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(start = Space.gutter, end = Space.gutter, top = Space.lg, bottom = Space.sm), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = TetherTheme.type.section, color = TetherTheme.colors.faint, modifier = Modifier.weight(1f))
        trailing?.invoke(this)
    }
}

/** Serif page title block used at the top of primary screens. */
@Composable
fun PageHeader(title: String, modifier: Modifier = Modifier, eyebrow: String? = null, subtitle: String? = null) {
    Column(modifier.padding(horizontal = Space.gutter, vertical = Space.sm)) {
        if (eyebrow != null) {
            Text(eyebrow.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.labelMedium, color = TetherTheme.colors.clay)
            Spacer(Modifier.height(4.dp))
        }
        Text(title, style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.onBackground)
        if (subtitle != null) {
            Spacer(Modifier.height(4.dp))
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Minimal top bar: back arrow, title/subtitle, trailing actions. No elevation, no colour fill. */
@Composable
fun TetherTopBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    /** Rich subtitle row (e.g. a [MachineBadge] + status); takes precedence over [subtitle]. */
    subtitleContent: (@Composable RowScope.() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 60.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
        } else Spacer(Modifier.width(Space.lg))
        if (leading != null) { leading(); Spacer(Modifier.width(10.dp)) }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitleContent != null) {
                Row(Modifier.padding(top = 3.dp), verticalAlignment = Alignment.CenterVertically) { subtitleContent() }
            } else if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        actions()
    }
}

/**
 * The machine an agent runs on, as a pill in the machine's accent colour. Place it OUTSIDE any
 * weighted text in a Row (non-weighted children are measured first), so a long project name can
 * never push it out; only a very long machine name ellipsises, inside its own [maxWidth].
 */
@Composable
fun MachineBadge(name: String, accent: Int, modifier: Modifier = Modifier, maxWidth: Dp = 150.dp) {
    val c = accentColor(accent)
    Row(
        modifier
            .widthIn(max = maxWidth)
            .clip(CircleShape)
            .background(c.copy(alpha = if (TetherTheme.colors.isDark) 0.18f else 0.14f))
            .padding(start = 7.dp, end = 9.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(6.dp).background(c, CircleShape))
        Spacer(Modifier.width(5.dp))
        Text(
            name,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
            color = if (TetherTheme.colors.isDark) c else c.copy(red = c.red * 0.72f, green = c.green * 0.72f, blue = c.blue * 0.72f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(modifier.fillMaxWidth().padding(horizontal = Space.xxl, vertical = Space.xxxl), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(64.dp).clip(RoundedCornerShape(20.dp)).background(TetherTheme.colors.clay.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, tint = TetherTheme.colors.clay, modifier = Modifier.size(30.dp)) }
        Spacer(Modifier.height(Space.xl))
        Text(title, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onBackground)
        Spacer(Modifier.height(Space.sm))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(Space.xl))
            PrimaryButton(actionLabel, onClick = onAction)
        }
    }
}

// ───────────────────────────── Buttons & chips ─────────────────────────────

@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    icon: ImageVector? = null,
    color: Color = MaterialTheme.colorScheme.primary,
    contentColor: Color = MaterialTheme.colorScheme.onPrimary,
) {
    val haptics = rememberHaptics()
    Surface(
        onClick = { haptics.tick(); onClick() },
        enabled = enabled && !loading,
        modifier = modifier.heightIn(min = 50.dp),
        shape = RoundedCornerShape(16.dp),
        color = if (enabled) color else TetherTheme.colors.subtleFill,
        contentColor = if (enabled) contentColor else TetherTheme.colors.faint,
    ) {
        Row(Modifier.padding(horizontal = 22.dp, vertical = 14.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            if (loading) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = contentColor)
                Spacer(Modifier.width(10.dp))
            } else if (icon != null) {
                Icon(icon, null, Modifier.size(19.dp)); Spacer(Modifier.width(8.dp))
            }
            Text(text, style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold))
        }
    }
}

@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    val haptics = rememberHaptics()
    Surface(
        onClick = { haptics.tick(); onClick() },
        enabled = enabled,
        modifier = modifier.heightIn(min = 50.dp),
        shape = RoundedCornerShape(16.dp),
        color = TetherTheme.colors.subtleFill,
        contentColor = if (enabled) contentColor else contentColor.copy(alpha = 0.4f),
    ) {
        Row(Modifier.padding(horizontal = 20.dp, vertical = 14.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) { Icon(icon, null, Modifier.size(19.dp)); Spacer(Modifier.width(8.dp)) }
            Text(text, style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold))
        }
    }
}

/** Pill-shaped selectable chip. */
@Composable
fun TagChip(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    selected: Boolean = false,
    accent: Color = TetherTheme.colors.clay,
    onClick: (() -> Unit)? = null,
) {
    val bg by animateColorAsState(if (selected) accent.copy(alpha = 0.14f) else TetherTheme.colors.subtleFill, label = "chipbg")
    val fg by animateColorAsState(if (selected) accent else MaterialTheme.colorScheme.onSurfaceVariant, label = "chipfg")
    val haptics = rememberHaptics()
    val shape = CircleShape
    Row(
        modifier
            .clip(shape)
            .background(bg)
            .then(if (selected) Modifier.border(1.dp, accent.copy(alpha = 0.35f), shape) else Modifier)
            .then(if (onClick != null) Modifier.combinedClickable(onClick = { haptics.tick(); onClick() }) else Modifier)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) { Icon(icon, null, tint = fg, modifier = Modifier.size(15.dp)); Spacer(Modifier.width(6.dp)) }
        Text(text, style = MaterialTheme.typography.labelMedium, color = fg, maxLines = 1)
    }
}

// ───────────────────────────── Machines ─────────────────────────────

fun accentColor(index: Int): Color = Color(MachineAccents[((index % MachineAccents.size) + MachineAccents.size) % MachineAccents.size])

/** Rounded-square monogram in the machine's accent colour. */
@Composable
fun MachineAvatar(name: String, accent: Int, modifier: Modifier = Modifier, size: Dp = 40.dp) {
    val c = accentColor(accent)
    val initials = name.split(' ', '-', '_', '.').filter { it.isNotBlank() }.take(2).joinToString("") { it.first().uppercase() }.ifEmpty { "?" }
    Box(
        modifier.size(size).clip(RoundedCornerShape(size * 0.32f)).background(c.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(initials, color = c, style = MaterialTheme.typography.titleSmall.copy(fontSize = (size.value * 0.36f).sp))
    }
}

// ───────────────────────────── Forms & rows ─────────────────────────────

@Composable
fun TetherTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    supporting: String? = null,
    isError: Boolean = false,
    mono: Boolean = false,
    password: Boolean = false,
    singleLine: Boolean = true,
    minLines: Int = 1,
    keyboardType: KeyboardType = KeyboardType.Text,
    trailing: (@Composable () -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it, color = TetherTheme.colors.faint) } },
        supportingText = supporting?.let { { Text(it) } },
        isError = isError,
        singleLine = singleLine,
        minLines = minLines,
        textStyle = if (mono) TetherTheme.type.mono.copy(fontSize = 14.sp) else MaterialTheme.typography.bodyLarge,
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (password) KeyboardType.Password else keyboardType, autoCorrectEnabled = !mono && !password),
        trailingIcon = trailing,
        leadingIcon = leading,
        shape = RoundedCornerShape(14.dp),
        colors = OutlinedTextFieldDefaults.colors(
            unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f),
            focusedBorderColor = MaterialTheme.colorScheme.primary,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        modifier = modifier.fillMaxWidth(),
    )
}

/** Settings-style row. */
@Composable
fun ListRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    iconTint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    showChevron: Boolean = false,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier.fillMaxWidth()
            .then(if (onClick != null) Modifier.combinedClickable(onClick = onClick) else Modifier)
            .padding(horizontal = Space.gutter, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = iconTint, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(16.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (trailing != null) { Spacer(Modifier.width(12.dp)); trailing() }
        if (showChevron) Icon(Icons.Rounded.ChevronRight, null, tint = TetherTheme.colors.faint)
    }
}

@Composable
fun ToggleRow(title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier, subtitle: String? = null, icon: ImageVector? = null) {
    val haptics = rememberHaptics()
    ListRow(title, modifier, subtitle, icon, onClick = { haptics.tick(); onCheckedChange(!checked) }) {
        Switch(
            checked = checked,
            onCheckedChange = { haptics.tick(); onCheckedChange(it) },
            colors = SwitchDefaults.colors(checkedTrackColor = MaterialTheme.colorScheme.primary, checkedThumbColor = MaterialTheme.colorScheme.onPrimary),
        )
    }
}

/** Monospace inline "code" chip, e.g. a path or command. */
@Composable
fun CodeChip(text: String, modifier: Modifier = Modifier, maxLines: Int = 1) {
    Text(
        text,
        modifier = modifier.clip(RoundedCornerShape(6.dp)).background(TetherTheme.colors.codeBg).padding(horizontal = 6.dp, vertical = 2.dp),
        style = TetherTheme.type.monoSmall,
        color = TetherTheme.colors.codeText,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

/** Draws a thin accent bar on the start edge (for "needs you" rows). */
fun Modifier.accentEdge(color: Color, width: Dp = 3.dp): Modifier = drawBehind {
    drawRect(color, size = androidx.compose.ui.geometry.Size(width.toPx(), size.height))
}

@Composable
fun ProvideContentColor(color: Color, content: @Composable () -> Unit) =
    androidx.compose.runtime.CompositionLocalProvider(LocalContentColor provides color, content = content)

// ───────────────────────────── Formatting ─────────────────────────────

fun relativeTime(epochMs: Long, now: Long = System.currentTimeMillis()): String {
    val d = now - epochMs
    return when {
        d < 45_000 -> "now"
        d < 3_600_000 -> "${d / 60_000}m"
        d < 86_400_000 -> "${d / 3_600_000}h"
        d < 2 * 86_400_000 -> "Yesterday"
        d < 7 * 86_400_000 -> SimpleDateFormat("EEE", Locale.getDefault()).format(Date(epochMs))
        else -> SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(epochMs))
    }
}

fun formatElapsed(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return when {
        s < 60 -> "${s}s"
        s < 3600 -> "${s / 60}m ${s % 60}s"
        else -> "${s / 3600}h ${(s % 3600) / 60}m"
    }
}

fun formatCost(usd: Double): String = when {
    usd <= 0.0 -> "$0"
    usd < 0.01 -> "<$0.01"
    usd < 10 -> "$" + String.format(Locale.US, "%.2f", usd)
    else -> "$" + String.format(Locale.US, "%.1f", usd)
}

fun compactNumber(n: Long): String = when {
    n < 1000 -> n.toString()
    n < 1_000_000 -> String.format(Locale.US, if (n < 10_000) "%.1fk" else "%.0fk", n / 1000.0)
    else -> String.format(Locale.US, "%.1fM", n / 1_000_000.0)
}

/** `/home/me/Projects/app` → `~/Projects/app` (when [home] known), long paths keep the tail. */
fun prettyPath(path: String, home: String? = null, maxLen: Int = 42): String {
    var p = path
    if (home != null && home.length > 1 && (p == home || p.startsWith("$home/"))) p = "~" + p.removePrefix(home)
    else Regex("^/(home|Users)/[^/]+").find(p)?.let { p = "~" + p.substring(it.range.last + 1) }
    if (p.length > maxLen) {
        val parts = p.split('/')
        var tail = parts.last()
        var i = parts.size - 2
        while (i > 0 && tail.length + parts[i].length + 2 < maxLen - 2) { tail = parts[i] + "/" + tail; i-- }
        p = "…/$tail"
    }
    return p
}

/** Last path component of the project root (a worktree shows its project's name) — a project's display name. */
fun projectName(path: String): String = projectRoot(path).trimEnd('/').substringAfterLast('/').ifEmpty { "/" }
