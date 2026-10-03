package app.tether.ui.home

import androidx.compose.foundation.layout.navigationBarsPadding


import androidx.compose.material.icons.rounded.ArrowUpward


import androidx.compose.ui.draw.shadow


import androidx.compose.animation.core.LinearEasing

import androidx.compose.animation.core.animateFloat

import androidx.compose.animation.core.animateFloatAsState

import androidx.compose.animation.core.infiniteRepeatable

import androidx.compose.animation.core.rememberInfiniteTransition

import androidx.compose.animation.core.tween

import androidx.compose.foundation.background

import androidx.compose.foundation.border

import androidx.compose.foundation.clickable

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

import androidx.compose.foundation.shape.CircleShape

import androidx.compose.foundation.shape.RoundedCornerShape

import androidx.compose.material.icons.Icons

import androidx.compose.material.icons.automirrored.rounded.CallSplit

import androidx.compose.material.icons.rounded.Check

import androidx.compose.material.icons.rounded.CloudOff

import androidx.compose.material.icons.rounded.Code

import androidx.compose.material.icons.rounded.Description

import androidx.compose.material.icons.rounded.Edit

import androidx.compose.material.icons.rounded.ExpandMore

import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Language

import androidx.compose.material.icons.rounded.Search

import androidx.compose.material.icons.rounded.SmartToy

import androidx.compose.material.icons.rounded.Terminal

import androidx.compose.material3.Icon

import androidx.compose.material3.MaterialTheme

import androidx.compose.material3.Text

import androidx.compose.material3.TextButton

import androidx.compose.runtime.getValue

import androidx.compose.runtime.setValue

import androidx.compose.runtime.Composable

import androidx.compose.runtime.mutableStateOf

import androidx.compose.runtime.saveable.rememberSaveable

import androidx.compose.ui.Alignment

import androidx.compose.ui.Modifier

import androidx.compose.ui.draw.clip

import androidx.compose.ui.draw.drawBehind

import androidx.compose.ui.draw.rotate

import androidx.compose.ui.geometry.Offset

import androidx.compose.ui.graphics.Brush

import androidx.compose.ui.graphics.Color

import androidx.compose.ui.graphics.Shape

import androidx.compose.ui.graphics.vector.ImageVector

import androidx.compose.ui.semantics.Role

import androidx.compose.ui.text.style.TextOverflow

import androidx.compose.ui.unit.Dp

import androidx.compose.ui.unit.dp

import androidx.compose.ui.unit.sp

import app.tether.core.LinkState

import app.tether.ui.components.MachineAvatar

import app.tether.ui.components.SecondaryButton

import app.tether.ui.components.StatusDot

import app.tether.ui.components.TetherCard

import app.tether.ui.theme.Space

import app.tether.ui.theme.TetherTheme

import java.io.IOException

import java.net.ConnectException

import java.net.NoRouteToHostException

import java.net.SocketTimeoutException

import java.net.UnknownHostException

import kotlin.coroutines.cancellation.CancellationException


/*
 * Building blocks shared by Home, Machine detail and New agent (agent D's packages).
 * Public so ui.machine / ui.newagent can use them; candidates for hoisting into ui/components.
 */

// ───────────────────────────── Async state ─────────────────────────────

/** Minimal three-state holder for anything fetched from a machine. */
sealed interface Loadable<out T> {
    data object Loading : Loadable<Nothing>
    data class Ready<T>(val value: T) : Loadable<T>
    data class Failed(val message: String) : Loadable<Nothing>

    val valueOrNull: T? get() = (this as? Ready<T>)?.value
}

/** Like runCatching, but never swallows coroutine cancellation. */
suspend fun <T> attempt(block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (c: CancellationException) {
    throw c
} catch (t: Throwable) {
    Result.failure(t)
}

/** A human sentence for an exception coming out of the SSH / remote layers. */
fun Throwable.humanMessage(): String {
    val root = generateSequence(this) { it.cause }.last()
    return when {
        this is UnknownHostException || root is UnknownHostException -> "Couldn't find that host. Check the address or your network."
        this is SocketTimeoutException || root is SocketTimeoutException -> "The machine took too long to answer."
        this is NoRouteToHostException || root is NoRouteToHostException -> "No route to the machine — is it on the same network?"
        this is ConnectException || root is ConnectException -> "The machine refused the connection. Is SSH running?"
        !message.isNullOrBlank() -> message!!.trim().lineSequence().first().take(220)
        this is IOException -> "The connection dropped."
        else -> "Something went wrong (${javaClass.simpleName})."
    }
}

// ───────────────────────────── Agent ordering ─────────────────────────────

/** Last assistant text with the loudest markdown stripped, for 2-line previews. */
fun plainSnippet(text: String): String = text
    .replace(Regex("```[a-zA-Z0-9_-]*"), "")
    .replace(Regex("(?m)^#{1,6}\\s+"), "")
    .replace(Regex("(?m)^\\s*[-*+]\\s+"), "• ")
    .replace("**", "")
    .replace("__", "")
    .replace("`", "")
    .replace(Regex("\\s+"), " ")
    .trim()

// ───────────────────────────── Tools ─────────────────────────────

fun permissionVerb(toolName: String): String = when (toolName) {
    "Bash", "BashOutput", "KillShell" -> "wants to run"
    "Edit", "MultiEdit", "Write", "NotebookEdit" -> "wants to edit"
    "Read", "NotebookRead" -> "wants to read"
    "Grep", "Glob", "LS" -> "wants to search"
    "WebFetch" -> "wants to fetch"
    "WebSearch" -> "wants to search the web for"
    "Task", "Agent" -> "wants to start a subagent"
    "Workflow" -> "wants to run a workflow"
    "ExitPlanMode" -> "is ready to leave plan mode"
    else -> "wants to use $toolName"
}

fun toolIcon(toolName: String): ImageVector = when (toolName) {
    "Bash", "BashOutput", "KillShell" -> Icons.Rounded.Terminal
    "Edit", "MultiEdit", "Write", "NotebookEdit" -> Icons.Rounded.Edit
    "Read", "NotebookRead" -> Icons.Rounded.Description
    "Grep", "Glob", "LS" -> Icons.Rounded.Search
    "WebFetch", "WebSearch" -> Icons.Rounded.Language
    "Task", "Agent" -> Icons.Rounded.SmartToy
    "Workflow" -> Icons.Rounded.Hub
    else -> Icons.Rounded.Code
}

// ───────────────────────────── Link state ─────────────────────────────

data class LinkLook(val color: Color, val label: String, val pulsing: Boolean)

@Composable
fun linkLook(state: LinkState?, error: String? = null): LinkLook {
    val c = TetherTheme.colors
    return when (state) {
        is LinkState.Connected -> LinkLook(c.success, if (state.latencyMs != null) "Connected · ${state.latencyMs} ms" else "Connected", false)
        LinkState.Connecting -> LinkLook(c.warning, "Connecting…", true)
        is LinkState.Failed -> LinkLook(c.danger, "Unreachable", false)
        LinkState.Idle, null -> if (error != null) LinkLook(c.danger, "Unreachable", false) else LinkLook(c.faint, "Not connected", false)
    }
}

// ───────────────────────────── Skeletons ─────────────────────────────

/** A shimmering placeholder block — the loading language of the app (never a lone spinner). */
@Composable
fun SkeletonBlock(modifier: Modifier = Modifier, shape: Shape = RoundedCornerShape(8.dp)) {
    val base = MaterialTheme.colorScheme.surfaceContainerHighest
    val hi = MaterialTheme.colorScheme.surfaceBright
    val t = rememberInfiniteTransition(label = "skeleton")
    val x by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1400, easing = LinearEasing)), label = "skx")
    Box(
        modifier
            .clip(shape)
            .drawBehind {
                val band = size.width * 0.55f
                val start = -band + (size.width + band) * x
                drawRect(
                    Brush.linearGradient(
                        colors = listOf(base, hi, base),
                        start = Offset(start, 0f),
                        end = Offset(start + band, size.height * 0.4f),
                    )
                )
            }
    )
}

@Composable
fun SkeletonAgentCard(modifier: Modifier = Modifier) {
    TetherCard(modifier.fillMaxWidth()) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SkeletonBlock(Modifier.size(22.dp), RoundedCornerShape(7.dp))
                Spacer(Modifier.width(8.dp))
                SkeletonBlock(Modifier.width(90.dp).height(12.dp))
                Spacer(Modifier.weight(1f))
                SkeletonBlock(Modifier.width(72.dp).height(20.dp), CircleShape)
            }
            Spacer(Modifier.height(14.dp))
            SkeletonBlock(Modifier.fillMaxWidth(0.55f).height(20.dp))
            Spacer(Modifier.height(12.dp))
            SkeletonBlock(Modifier.fillMaxWidth().height(12.dp))
            Spacer(Modifier.height(7.dp))
            SkeletonBlock(Modifier.fillMaxWidth(0.72f).height(12.dp))
        }
    }
}

@Composable
fun SkeletonListRow(modifier: Modifier = Modifier, leading: Boolean = true) {
    Row(modifier.fillMaxWidth().padding(horizontal = Space.lg, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        if (leading) {
            SkeletonBlock(Modifier.size(36.dp), RoundedCornerShape(11.dp))
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f)) {
            SkeletonBlock(Modifier.fillMaxWidth(0.45f).height(14.dp))
            Spacer(Modifier.height(8.dp))
            SkeletonBlock(Modifier.fillMaxWidth(0.7f).height(10.dp))
        }
    }
}

// ───────────────────────────── Errors ─────────────────────────────

/** Human sentence + optional detail expander + Retry. */
@Composable
fun ErrorCard(
    title: String,
    message: String?,
    modifier: Modifier = Modifier,
    icon: ImageVector = Icons.Rounded.CloudOff,
    retrying: Boolean = false,
    onRetry: (() -> Unit)? = null,
) {
    var showDetail by rememberSaveable { mutableStateOf(false) }
    val danger = TetherTheme.colors.danger
    TetherCard(modifier.fillMaxWidth(), color = danger.copy(alpha = 0.07f), border = danger.copy(alpha = 0.28f)) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = danger, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
            }
            if (!message.isNullOrBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = if (showDetail) Int.MAX_VALUE else 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clickable(onClickLabel = "Show details") { showDetail = !showDetail },
                )
            }
            if (onRetry != null) {
                Spacer(Modifier.height(10.dp))
                SecondaryButton(if (retrying) "Retrying…" else "Retry", onClick = onRetry, enabled = !retrying)
            }
        }
    }
}

/** Slim per-machine banner used on Home when a machine can't be reached. */
@Composable
fun MachineErrorBanner(
    machineName: String,
    accent: Int,
    message: String,
    retrying: Boolean,
    onRetry: () -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val danger = TetherTheme.colors.danger
    var expanded by rememberSaveable(machineName) { mutableStateOf(false) }
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(danger.copy(alpha = 0.07f))
            .border(1.dp, danger.copy(alpha = 0.22f), RoundedCornerShape(16.dp))
            .clickable(onClickLabel = "Open machine", onClick = onOpen)
            .padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MachineAvatar(machineName, accent, size = 28.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("Can't reach $machineName", style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (expanded) 6 else 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clickable(onClickLabel = "Show details") { expanded = !expanded },
            )
        }
        TextButton(onClick = onRetry, enabled = !retrying) {
            Text(if (retrying) "Retrying…" else "Retry", color = if (retrying) TetherTheme.colors.faint else danger)
        }
    }
}

// ───────────────────────────── Small bits ─────────────────────────────

/** A tiny rounded label, e.g. a git branch or "active on desktop". */
@Composable
fun MiniBadge(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    pulsing: Boolean = false,
    mono: Boolean = false,
) {
    Row(
        modifier
            .clip(RoundedCornerShape(7.dp))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (pulsing) {
            StatusDot(color, pulsing = true, size = 5.dp)
        } else if (icon != null) {
            Icon(icon, null, tint = color, modifier = Modifier.size(12.dp))
            Spacer(Modifier.width(4.dp))
        }
        Text(
            text,
            style = if (mono) TetherTheme.type.monoSmall.copy(fontSize = 10.5.sp) else MaterialTheme.typography.labelSmall,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun GitBranchBadge(branch: String, modifier: Modifier = Modifier) {
    MiniBadge(branch, TetherTheme.colors.info, modifier, icon = Icons.AutoMirrored.Rounded.CallSplit, mono = true)
}

/** Rounded folder/project tile with an icon. */
@Composable
fun IconTile(icon: ImageVector, tint: Color, modifier: Modifier = Modifier, size: Dp = 36.dp) {
    Box(
        modifier.size(size).clip(RoundedCornerShape(size * 0.3f)).background(tint.copy(alpha = 0.13f)),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = tint, modifier = Modifier.size(size * 0.52f)) }
}

/** Section header that toggles a group open/closed. */
@Composable
fun CollapsibleHeader(text: String, count: Int, expanded: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val rot by animateFloatAsState(if (expanded) 0f else -90f, label = "chev")
    Row(
        modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClickLabel = if (expanded) "Collapse" else "Expand", onClick = onToggle)
            .padding(start = Space.gutter, end = Space.gutter, top = Space.lg, bottom = Space.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = TetherTheme.type.section, color = TetherTheme.colors.faint)
        Spacer(Modifier.width(6.dp))
        Text("$count", style = TetherTheme.type.section, color = TetherTheme.colors.faint.copy(alpha = 0.6f))
        Spacer(Modifier.weight(1f))
        Icon(Icons.Rounded.ExpandMore, null, tint = TetherTheme.colors.faint, modifier = Modifier.size(20.dp).rotate(rot))
    }
}

@Composable
fun CountedSectionHeader(text: String, count: Int, modifier: Modifier = Modifier, color: Color = TetherTheme.colors.faint) {
    Row(modifier.fillMaxWidth().padding(start = Space.gutter, end = Space.gutter, top = Space.lg, bottom = Space.sm), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = TetherTheme.type.section, color = color)
        Spacer(Modifier.width(6.dp))
        Text("$count", style = TetherTheme.type.section, color = color.copy(alpha = 0.6f))
    }
}

// ───────────────────────────── Agent cards ─────────────────────────────

/** `claude-opus-4-1-20250805` → `Opus 4.1`; aliases pass through capitalised. */
fun prettyModel(model: String): String {
    val m = Regex("claude-(opus|sonnet|haiku)-(\\d+)(?:-(\\d))?", RegexOption.IGNORE_CASE).find(model)
    if (m != null) {
        val fam = m.groupValues[1].replaceFirstChar { it.uppercase() }
        val minor = m.groupValues[3]
        return if (minor.isNotEmpty()) "$fam ${m.groupValues[2]}.$minor" else "$fam ${m.groupValues[2]}"
    }
    return model.substringBefore('[').replaceFirstChar { it.uppercase() }
}

/** Cowork-style input at the bottom of Home: a big quiet card that opens New agent. */
@Composable
fun StartAgentBar(placeholder: String, onClick: () -> Unit) {
    val bg = MaterialTheme.colorScheme.background
    val c = TetherTheme.colors
    val shape = RoundedCornerShape(28.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(bg.copy(alpha = 0f), bg, bg), startY = 0f, endY = 60f))
            .navigationBarsPadding()
            .padding(start = 14.dp, end = 14.dp, top = Space.md, bottom = Space.sm),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .then(if (!c.isDark) Modifier.shadow(10.dp, shape, ambientColor = Color(0x33000000), spotColor = Color(0x22000000)) else Modifier)
                .clip(shape)
                .background(c.composer)
                .border(1.dp, c.composerBorder, shape)
                .clickable(onClickLabel = "Start a new agent", onClick = onClick)
                .heightIn(min = 64.dp)
                .padding(start = 22.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                placeholder,
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp),
                color = c.faint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(c.clay),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.ArrowUpward, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
            }
        }
    }
}

