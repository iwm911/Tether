package app.tether.ui.home

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.tether.core.RunInfo
import app.tether.core.RunStatus
import app.tether.ui.components.ClaudeSpinner
import app.tether.ui.components.ShimmerText
import app.tether.ui.components.StatusDot
import app.tether.ui.components.compactNumber
import app.tether.ui.theme.TetherTheme

/*
 * Shared bits for Claude Code's own background agents (`claude --bg`), used by Home, the machine
 * screen and the agent screen.
 */

/** The small "Background" tag that marks a native agent. */
@Composable
fun BackgroundBadge(modifier: Modifier = Modifier) {
    MiniBadge("Background", TetherTheme.colors.faint, modifier, icon = Icons.Rounded.Computer)
}

/** Human label for a native agent's state. */
fun nativeStateLabel(run: RunInfo): String = when {
    !run.alive -> "Stopped"
    run.nativeStatus == "waiting" -> "Needs you"
    run.nativeStatus == "busy" || (run.nativeStatus == null && run.nativeState == "working") -> "Working"
    run.nativeState == "blocked" -> "Your turn"
    run.nativeState == "done" -> "Done"
    run.status == RunStatus.STARTING -> "Starting"
    else -> run.nativeState?.replaceFirstChar { it.uppercase() } ?: "Idle"
}

@Composable
fun nativeStateColor(run: RunInfo): Color = when {
    !run.alive -> TetherTheme.colors.faint
    run.nativeStatus == "waiting" -> TetherTheme.colors.warning
    run.nativeStatus == "busy" || (run.nativeStatus == null && run.nativeState == "working") -> TetherTheme.colors.clay
    else -> TetherTheme.colors.success
}

/** Status pill for a native agent (its states differ from a live run's). */
@Composable
fun NativeStatusPill(run: RunInfo, modifier: Modifier = Modifier) {
    val c by animateColorAsState(nativeStateColor(run), label = "npill")
    Row(
        modifier
            .padding(end = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(c, pulsing = run.alive && (run.nativeStatus == "busy" || run.nativeStatus == "waiting"), size = 6.dp)
        Text(nativeStateLabel(run), style = MaterialTheme.typography.labelMedium, color = c)
    }
}

/** "✻ Checking CLI agent features…" — the agent's own live one-line activity. */
@Composable
fun NativeActivityLine(detail: String?, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        ClaudeSpinner()
        Spacer(Modifier.width(6.dp))
        val text = detail?.takeIf { it.isNotBlank() }?.trimEnd('.', '…') ?: "Working"
        ShimmerText(
            "$text…",
            MaterialTheme.typography.bodyMedium,
            base = MaterialTheme.colorScheme.onSurfaceVariant,
            highlight = TetherTheme.colors.clay,
            maxLines = 2,
        )
    }
}

/** "6 subagents · 2 running" (null when there are none). */
fun subagentSummary(run: RunInfo): String? {
    val n = run.subagents.size
    if (n == 0) return null
    val running = run.subagents.count { it.running }
    val base = if (n == 1) "1 subagent" else "$n subagents"
    return if (running in 1 until n) "$base · $running running" else base
}

/** Meta line entries for a native card: subagents, tokens. */
fun nativeMeta(run: RunInfo): List<String> = buildList {
    subagentSummary(run)?.let { add(it) }
    if (run.tokens > 0) add("${compactNumber(run.tokens)} tokens")
}
