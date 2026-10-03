package app.tether.ui.chat

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Terminal
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.tether.core.BackgroundTask
import app.tether.ui.components.ClaudeSpinner
import app.tether.ui.components.ShimmerText
import app.tether.ui.components.compactNumber
import app.tether.ui.components.rememberHaptics
import app.tether.ui.theme.Motion
import app.tether.ui.theme.Space
import app.tether.ui.theme.TetherTheme

/**
 * "Background · 2" — shell commands, subagents and workflows the session started that keep running after the
 * turn ends (they never stream into the conversation). Collapsed it shows the newest one; expanded, all.
 */
@Composable
fun BackgroundTasksStrip(tasks: List<BackgroundTask>, modifier: Modifier = Modifier) {
    AnimatedVisibility(
        visible = tasks.isNotEmpty(),
        modifier = modifier,
        enter = expandVertically(Motion.gentle()) + fadeIn(tween(Motion.Medium)),
        exit = shrinkVertically(tween(Motion.Medium, easing = Motion.Emphasized)) + fadeOut(tween(Motion.Short)),
    ) {
        // Keep the last non-empty list so the exit animation has something to show.
        var shown by remember { mutableStateOf(tasks) }
        if (tasks.isNotEmpty()) shown = tasks
        BackgroundTasksCard(shown)
    }
}

@Composable
private fun BackgroundTasksCard(tasks: List<BackgroundTask>) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val haptics = rememberHaptics()
    val chevron by animateFloatAsState(if (expanded) 180f else 0f, Motion.gentle(), label = "bgChevron")
    val shape = RoundedCornerShape(18.dp)
    val latest = tasks.lastOrNull()
    val agents = tasks.count { it.isAgent }
    val workflows = tasks.count { it.isWorkflow }
    val title = when (tasks.size) {
        agents -> if (agents == 1) "Subagent" else "Subagents"
        workflows -> if (workflows == 1) "Workflow" else "Workflows"
        else -> "Background"
    }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = Space.xs)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .border(1.dp, TetherTheme.colors.hairline, shape)
            .clickable(role = Role.Button, onClickLabel = if (expanded) "Collapse background tasks" else "Expand background tasks") {
                haptics.tick(); expanded = !expanded
            }
            .semantics { contentDescription = "${tasks.size} background tasks running" },
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 14.dp, end = 10.dp, top = 11.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ClaudeSpinner(fontSize = 14f)
            Spacer(Modifier.width(8.dp))
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
            AnimatedContent(tasks.size, transitionSpec = { fadeIn(tween(Motion.Short)) togetherWith fadeOut(tween(Motion.Short)) }, label = "bgCount") { n ->
                Text(" · $n", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Normal), color = TetherTheme.colors.faint)
            }
            Spacer(Modifier.width(10.dp))
            Box(Modifier.weight(1f)) {
                if (!expanded && latest != null) {
                    AnimatedContent(latest.description, transitionSpec = { fadeIn(tween(Motion.Medium)) togetherWith fadeOut(tween(Motion.Short)) }, label = "bgLatest") { text ->
                        ShimmerText(
                            text,
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                            base = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
            Icon(
                Icons.Rounded.ExpandMore,
                contentDescription = null,
                tint = TetherTheme.colors.faint,
                modifier = Modifier.size(20.dp).rotate(chevron),
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(Motion.gentle()) + fadeIn(tween(Motion.Medium)),
            exit = shrinkVertically(tween(Motion.Medium, easing = Motion.Emphasized)) + fadeOut(tween(Motion.Short)),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 300.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(start = 14.dp, end = 14.dp, top = 2.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                tasks.forEach { BackgroundTaskRow(it) }
            }
        }
    }
}

@Composable
private fun BackgroundTaskRow(task: BackgroundTask) {
    val c = TetherTheme.colors
    Row(verticalAlignment = Alignment.Top) {
        Icon(
            when {
                task.isWorkflow -> Icons.Rounded.Hub
                task.isAgent -> Icons.Rounded.AccountTree
                else -> Icons.Rounded.Terminal
            },
            contentDescription = null,
            tint = c.clay,
            modifier = Modifier.padding(top = 2.dp).size(16.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                task.description,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            val meta = buildList {
                add(task.subagentType ?: when {
                    task.isWorkflow -> "Workflow"
                    task.isAgent -> "Agent"
                    else -> "Shell"
                })
                task.lastToolName?.let { add(it) }
                task.toolUses?.takeIf { it > 0 }?.let { add(if (it == 1) "1 tool use" else "$it tool uses") }
                task.totalTokens?.takeIf { it > 0 }?.let { add("${compactNumber(it)} tokens") }
            }
            Text(meta.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = c.faint, maxLines = 1, overflow = TextOverflow.Ellipsis)
            task.summary?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
