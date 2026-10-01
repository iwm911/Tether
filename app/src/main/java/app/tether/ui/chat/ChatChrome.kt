package app.tether.ui.chat

import androidx.compose.foundation.shape.CircleShape

import androidx.compose.foundation.clickable

import androidx.compose.foundation.layout.fillMaxHeight

import androidx.compose.material.icons.outlined.Info

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.CallSplit
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.StopCircle
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.tether.LocalAppContainer
import app.tether.core.ChatItem
import app.tether.core.FallbackModels
import app.tether.core.LinkState
import app.tether.core.ModelOption
import app.tether.core.PermissionMode
import app.tether.core.PermissionDecision
import app.tether.core.RateLimitInfo
import app.tether.core.RunRef
import app.tether.core.RunStatus
import app.tether.ui.components.MachineAvatar
import app.tether.ui.components.PrimaryButton
import app.tether.ui.components.StatusDot
import app.tether.ui.components.TetherTopBar
import app.tether.ui.components.WorkingIndicator
import app.tether.ui.components.backgroundLabel
import app.tether.ui.components.compactNumber
import app.tether.ui.components.formatCost
import app.tether.ui.components.label
import app.tether.ui.components.prettyPath
import app.tether.ui.components.rememberHaptics
import app.tether.ui.theme.Motion
import app.tether.ui.theme.Space
import app.tether.ui.theme.TetherTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

// Shared chrome of the conversation screens (session screen, legacy agent screen).

private const val DefaultContextWindow = 200_000L
private const val LongContextWindow = 1_000_000L

/** The CLI-reported window; until a turn reports it, guess from the model id and the tokens already in use. */
internal fun contextWindowFor(reported: Long?, model: String?, tokens: Long?): Long = when {
    reported != null && reported > 0 -> reported
    model?.lowercase()?.contains("[1m]") == true -> LongContextWindow
    (tokens ?: 0L) > DefaultContextWindow -> LongContextWindow
    else -> DefaultContextWindow
}

/** Rate-limit warnings the user closed, keyed by kind + window + reset, so a new window or a hard limit shows again. */
private val dismissedRateBanners = mutableStateListOf<String>()

/** Holds the screen awake while the calling screen is shown, if the user turned that on in Settings. */
@Composable
internal fun KeepScreenOnIfEnabled() {
    val settings by LocalAppContainer.current.settings.settings.collectAsStateWithLifecycle()
    if (!settings.keepScreenOn) return
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
}

// ───────────────────────────── Top bar pieces ─────────────────────────────

@Composable
internal fun ContextMeter(
    tokens: Long?,
    window: Long,
    costUsd: Double,
    model: String?,
    models: List<ModelOption>,
    rateLimit: RateLimitInfo?,
    cwd: String?,
    plan: String? = null,
) {
    var open by remember { mutableStateOf(false) }
    val haptics = rememberHaptics()
    val fraction = ((tokens ?: 0L).toFloat() / window).coerceIn(0f, 1f)
    val animated by animateFloatAsState(fraction, Motion.gentle(), label = "ctx")
    val c = TetherTheme.colors
    val arc by animateColorAsState(
        when {
            fraction >= 0.9f -> c.danger
            fraction >= 0.75f -> c.warning
            else -> c.clay
        },
        label = "ctxColor",
    )
    val pct = (fraction * 100).roundToInt()
    Box {
        Row(
            Modifier
                .padding(end = 2.dp)
                .clip(CircleShape)
                .clickable(role = androidx.compose.ui.semantics.Role.Button) { haptics.tick(); open = true }
                .semantics { contentDescription = if (tokens != null) "Context $pct percent used" else "Session info" }
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (tokens != null) {
                // A tiny linear meter + the number: reads as data, never as a spinner.
                Box(Modifier.width(22.dp).height(4.dp).clip(CircleShape).background(c.hairline)) {
                    Box(Modifier.fillMaxWidth(animated.coerceAtLeast(0.04f)).fillMaxHeight().clip(CircleShape).background(arc))
                }
                Spacer(Modifier.width(7.dp))
                Text("$pct%", style = MaterialTheme.typography.labelMedium, color = c.faint)
            } else {
                Icon(Icons.Outlined.Info, contentDescription = null, tint = c.faint, modifier = Modifier.size(20.dp))
            }
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            shape = RoundedCornerShape(18.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            border = BorderStroke(1.dp, TetherTheme.colors.hairline),
        ) {
            Column(Modifier.widthIn(min = 240.dp, max = 320.dp).padding(horizontal = Space.lg, vertical = Space.sm)) {
                Text("Session", style = MaterialTheme.typography.labelMedium, color = TetherTheme.colors.faint)
                Spacer(Modifier.height(Space.sm))
                InfoLine(
                    "Context",
                    if (tokens != null) "${compactNumber(tokens)} / ${compactNumber(window)} · $pct%" else "Not measured yet",
                )
                InfoLine("Model", modelLabel(model, models))
                if (plan != null) {
                    InfoLine("Plan", plan)
                    val windows = rateLimit?.windows.orEmpty().ifEmpty {
                        rateLimit?.utilization?.let { listOf(app.tether.core.UsageWindow(rateLimit.windowLabel ?: "Usage", it, rateLimit.resetsAt)) }.orEmpty()
                    }
                    for (w in windows) UsageLine(w)
                    if (windows.isEmpty()) InfoLine("Usage", "Shown after Claude's next reply")
                    if (costUsd > 0) {
                        Text(
                            "≈ ${formatCost(costUsd)} at API prices — included in your plan, not billed",
                            style = MaterialTheme.typography.labelSmall,
                            color = TetherTheme.colors.faint,
                            modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
                        )
                    }
                } else {
                    InfoLine("Spent", formatCost(costUsd) + " (API)")
                    rateLimit?.utilization?.let { u ->
                        val window = rateLimit.windowLabel?.replace('_', ' ') ?: "usage window"
                        InfoLine("Usage", "${(u * 100).roundToInt().coerceIn(0, 100)}% of $window" + (formatReset(rateLimit.resetsAt)?.let { " · resets $it" } ?: ""))
                    }
                }
                cwd?.let { InfoLine("Folder", prettyPath(it, maxLen = 36), mono = true) }
            }
        }
    }
}

/** One plan-usage window: "5-hour limit  ▓▓▓░░  45% · resets 14:00". */
@Composable
private fun UsageLine(w: app.tether.core.UsageWindow) {
    val pct = (w.utilization * 100).roundToInt().coerceIn(0, 100)
    val c = TetherTheme.colors
    val color = when {
        pct >= 90 -> c.danger
        pct >= 75 -> c.warning
        else -> c.clay
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${w.label} limit", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            Text(
                "$pct%" + (formatReset(w.resetsAt)?.let { " · resets $it" } ?: ""),
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Spacer(Modifier.height(5.dp))
        Box(Modifier.fillMaxWidth().height(5.dp).clip(CircleShape).background(c.hairline)) {
            Box(Modifier.fillMaxWidth((pct / 100f).coerceAtLeast(0.02f)).fillMaxHeight().clip(CircleShape).background(color))
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String, mono: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(72.dp))
        Text(
            value,
            style = if (mono) TetherTheme.type.monoSmall else MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun OverflowMenu(
    hasSession: Boolean,
    rawView: Boolean,
    canStop: Boolean,
    onCopySession: () -> Unit,
    onOpenMachine: () -> Unit,
    onToggleRaw: () -> Unit,
    onStop: () -> Unit,
    onFork: () -> Unit = {},
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Rounded.MoreVert, contentDescription = "More options") }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            shape = RoundedCornerShape(18.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            border = BorderStroke(1.dp, TetherTheme.colors.hairline),
        ) {
            DropdownMenuItem(
                text = { Text("Copy session id") },
                leadingIcon = { Icon(Icons.Rounded.ContentCopy, contentDescription = null) },
                enabled = hasSession,
                onClick = { open = false; onCopySession() },
            )
            DropdownMenuItem(
                text = { Text("Fork conversation") },
                leadingIcon = { Icon(Icons.AutoMirrored.Rounded.CallSplit, contentDescription = null) },
                enabled = hasSession,
                onClick = { open = false; onFork() },
            )
            DropdownMenuItem(
                text = { Text("Open machine") },
                leadingIcon = { Icon(Icons.Rounded.Computer, contentDescription = null) },
                onClick = { open = false; onOpenMachine() },
            )
            DropdownMenuItem(
                text = { Text(if (rawView) "Hide raw events" else "Show raw events") },
                leadingIcon = { Icon(Icons.Rounded.Code, contentDescription = null) },
                onClick = { open = false; onToggleRaw() },
            )
            if (canStop) {
                HorizontalDivider(color = TetherTheme.colors.hairline, modifier = Modifier.padding(vertical = 4.dp))
                DropdownMenuItem(
                    text = { Text("Stop agent", color = TetherTheme.colors.danger) },
                    leadingIcon = { Icon(Icons.Rounded.StopCircle, contentDescription = null, tint = TetherTheme.colors.danger) },
                    onClick = { open = false; onStop() },
                )
            }
        }
    }
}

// ───────────────────────────── Banners ─────────────────────────────

private sealed interface BannerKind {
    data class Connecting(val first: Boolean) : BannerKind
    data class Failed(val message: String) : BannerKind
}

@Composable
internal fun LinkBanner(link: LinkState, streamError: String?, loading: Boolean, machineName: String?, onRetry: () -> Unit) {
    val kind: BannerKind? = when {
        streamError != null -> BannerKind.Failed(streamError)
        link is LinkState.Failed -> BannerKind.Failed(link.message)
        link is LinkState.Connecting -> BannerKind.Connecting(first = loading)
        link is LinkState.Idle && loading -> BannerKind.Connecting(first = true)
        else -> null
    }
    // Debounce "connecting" so a fast re-attach never flashes a banner.
    var shown by remember { mutableStateOf<BannerKind?>(null) }
    LaunchedEffect(kind) {
        if (kind is BannerKind.Connecting && shown == null) delay(700)
        shown = kind
    }
    AnimatedContent(
        targetState = shown,
        contentKey = { it?.let { k -> k::class } },
        transitionSpec = {
            (fadeIn(tween(Motion.Medium)) + expandVertically(Motion.gentle())) togetherWith
                (fadeOut(tween(Motion.Short)) + shrinkVertically(tween(Motion.Medium))) using SizeTransform(clip = true)
        },
        label = "linkBanner",
    ) { k ->
        when (k) {
            null -> Spacer(Modifier.fillMaxWidth())
            is BannerKind.Connecting -> BannerRow(
                tint = TetherTheme.colors.info,
                leading = { CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 1.8.dp, color = TetherTheme.colors.info) },
                title = if (k.first) "Connecting to ${machineName ?: "machine"}…" else "Reconnecting…",
                detail = if (k.first) null else "Claude keeps working on the machine meanwhile",
            )
            is BannerKind.Failed -> BannerRow(
                tint = TetherTheme.colors.danger,
                leading = { Icon(Icons.Rounded.WifiOff, contentDescription = null, tint = TetherTheme.colors.danger, modifier = Modifier.size(16.dp)) },
                title = "Can't reach ${machineName ?: "the machine"}",
                detail = k.message,
                action = "Retry",
                onAction = onRetry,
            )
        }
    }
}

@Composable
internal fun RateLimitBanner(info: RateLimitInfo?) {
    val status = info?.status?.lowercase()
    val kind = when {
        status == null -> null
        status.contains("reject") || status.contains("exceed") -> "limit"
        status.contains("warn") -> "warn"
        else -> null
    }
    val dismissKey = kind?.let { "$it|${info?.windowLabel}|${info?.resetsAt}" }
    AnimatedContent(
        targetState = kind?.takeIf { dismissKey !in dismissedRateBanners },
        transitionSpec = {
            (fadeIn(tween(Motion.Medium)) + expandVertically(Motion.gentle())) togetherWith
                (fadeOut(tween(Motion.Short)) + shrinkVertically(tween(Motion.Medium))) using SizeTransform(clip = true)
        },
        label = "rateBanner",
    ) { k ->
        if (k == null || info == null) {
            Spacer(Modifier.fillMaxWidth())
        } else {
            val reset = formatReset(info.resetsAt)
            val pct = info.utilization?.let { "${(it * 100).roundToInt().coerceIn(0, 100)}% used" }
            BannerRow(
                tint = if (k == "limit") TetherTheme.colors.danger else TetherTheme.colors.warning,
                leading = {
                    Icon(
                        Icons.Rounded.WarningAmber, contentDescription = null,
                        tint = if (k == "limit") TetherTheme.colors.danger else TetherTheme.colors.warning,
                        modifier = Modifier.size(16.dp),
                    )
                },
                title = if (k == "limit") "Usage limit reached" else "Approaching your usage limit",
                detail = listOfNotNull(pct, reset?.let { "resets $it" }).joinToString(" · ").ifEmpty { null },
                onClose = { dismissKey?.let(dismissedRateBanners::add) },
            )
        }
    }
}

/** "today 14:00", "tomorrow 09:00", or "Tue, Oct 6, 14:00" — weekly windows reset days away. */
internal fun formatReset(resetsAt: Long?): String? {
    if (resetsAt == null || resetsAt <= 0) return null
    val ms = if (resetsAt < 100_000_000_000L) resetsAt * 1000 else resetsAt
    val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(ms))
    val days = ChronoUnit.DAYS.between(LocalDate.now(), Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate())
    return when (days) {
        0L -> "today $time"
        1L -> "tomorrow $time"
        else -> {
            val locale = Locale.getDefault()
            SimpleDateFormat(android.text.format.DateFormat.getBestDateTimePattern(locale, "EEEMMMdjmm"), locale).format(Date(ms))
        }
    }
}

@Composable
internal fun BannerRow(
    tint: Color,
    leading: @Composable () -> Unit,
    title: String,
    detail: String? = null,
    action: String? = null,
    onAction: (() -> Unit)? = null,
    onClose: (() -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = Space.xs)
            .clip(RoundedCornerShape(14.dp))
            .background(tint.copy(alpha = 0.11f))
            .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading()
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (detail != null) {
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        if (action != null && onAction != null) {
            TextButton(onClick = onAction) { Text(action, color = tint, style = MaterialTheme.typography.labelLarge) }
        } else if (onClose != null) {
            IconButton(onClick = onClose, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Rounded.Close, contentDescription = "Dismiss", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
            }
        } else Spacer(Modifier.width(8.dp))
    }
}

@Composable
internal fun ReadyEmpty(cwd: String?, machineName: String?) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = Space.xxl, vertical = 56.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        Text("✻", style = MaterialTheme.typography.displaySmall, color = TetherTheme.colors.clay)
        Text("Ready when you are", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onBackground)
        Text(
            buildString {
                append("Claude is waiting")
                if (cwd != null) append(" in ${prettyPath(cwd, maxLen = 32)}")
                if (machineName != null) append(" on $machineName")
                append(". Type / for commands.")
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}
