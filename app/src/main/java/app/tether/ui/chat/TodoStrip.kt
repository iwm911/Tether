package app.tether.ui.chat

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.tether.core.TodoItem
import app.tether.core.TodoStatus
import app.tether.ui.components.ShimmerText
import app.tether.ui.components.rememberHaptics
import app.tether.ui.theme.Motion
import app.tether.ui.theme.Space
import app.tether.ui.theme.TetherTheme

/**
 * "Plan · 2/5" — Claude's TodoWrite list as a live checklist pinned under the top bar.
 * Collapsed it shows the in-progress step shimmering; expanded, the whole list.
 */
@Composable
fun TodoStrip(todos: List<TodoItem>, modifier: Modifier = Modifier) {
    AnimatedVisibility(
        visible = todos.isNotEmpty(),
        modifier = modifier,
        enter = expandVertically(Motion.gentle()) + fadeIn(tween(Motion.Medium)),
        exit = shrinkVertically(tween(Motion.Medium, easing = Motion.Emphasized)) + fadeOut(tween(Motion.Short)),
    ) {
        TodoCard(todos)
    }
}

@Composable
private fun TodoCard(todos: List<TodoItem>) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val haptics = rememberHaptics()
    val total = todos.size.coerceAtLeast(1)
    val done = todos.count { it.status == TodoStatus.COMPLETED }
    val active = todos.firstOrNull { it.status == TodoStatus.IN_PROGRESS }
    val allDone = done == todos.size && todos.isNotEmpty()
    val progress by animateFloatAsState(done.toFloat() / total, Motion.gentle(), label = "todoProgress")
    val barColor by animateColorAsState(if (allDone) TetherTheme.colors.success else TetherTheme.colors.clay, label = "todoBar")
    val chevron by animateFloatAsState(if (expanded) 180f else 0f, Motion.gentle(), label = "todoChevron")
    val shape = RoundedCornerShape(18.dp)

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = Space.xs)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .border(1.dp, TetherTheme.colors.hairline, shape)
            .clickable(role = Role.Button, onClickLabel = if (expanded) "Collapse plan" else "Expand plan") {
                haptics.tick(); expanded = !expanded
            }
            .semantics { contentDescription = "Plan, $done of ${todos.size} done" },
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 14.dp, end = 10.dp, top = 11.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Checklist, contentDescription = null, tint = barColor, modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                if (allDone) "Plan complete" else "Plan",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            AnimatedContent(done, transitionSpec = { fadeIn(tween(Motion.Short)) togetherWith fadeOut(tween(Motion.Short)) }, label = "todoCount") { d ->
                Text(" · $d/${todos.size}", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Normal), color = TetherTheme.colors.faint)
            }
            Spacer(Modifier.width(10.dp))
            Box(Modifier.weight(1f)) {
                if (!expanded && active != null) {
                    AnimatedContent(active.activeForm.ifBlank { active.content }, transitionSpec = { fadeIn(tween(Motion.Medium)) togetherWith fadeOut(tween(Motion.Short)) }, label = "todoActive") { text ->
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
        Box(Modifier.fillMaxWidth().height(2.dp).background(TetherTheme.colors.hairline)) {
            Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).fillMaxHeight().background(barColor))
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
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                todos.forEach { TodoRow(it) }
            }
        }
    }
}

@Composable
private fun TodoRow(todo: TodoItem) {
    val c = TetherTheme.colors
    Row(verticalAlignment = Alignment.Top) {
        Box(Modifier.padding(top = 2.dp)) { TodoGlyph(todo.status) }
        Spacer(Modifier.width(10.dp))
        when (todo.status) {
            TodoStatus.COMPLETED -> Text(
                todo.content,
                style = MaterialTheme.typography.bodyMedium.copy(textDecoration = TextDecoration.LineThrough),
                color = c.faint,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            TodoStatus.IN_PROGRESS -> Text(
                todo.activeForm.ifBlank { todo.content },
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                color = c.clay,
                maxLines = 3, overflow = TextOverflow.Ellipsis,
            )
            TodoStatus.PENDING -> Text(
                todo.content,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 3, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** ✓ done / ◐ in progress / ○ pending — drawn, so it looks identical on every device font. */
@Composable
private fun TodoGlyph(status: TodoStatus) {
    val c = TetherTheme.colors
    val color = when (status) {
        TodoStatus.COMPLETED -> c.success
        TodoStatus.IN_PROGRESS -> c.clay
        TodoStatus.PENDING -> c.faint
    }
    Canvas(Modifier.size(16.dp)) {
        val stroke = 1.6.dp.toPx()
        val r = size.minDimension / 2 - stroke / 2
        val center = Offset(size.width / 2, size.height / 2)
        when (status) {
            TodoStatus.COMPLETED -> {
                drawCircle(color, radius = r + stroke / 2, center = center)
                val w = size.width
                val h = size.height
                val p1 = Offset(w * 0.28f, h * 0.52f)
                val p2 = Offset(w * 0.44f, h * 0.68f)
                val p3 = Offset(w * 0.73f, h * 0.36f)
                val onColor = if (c.isDark) Color(0xFF131211) else Color.White
                drawLine(onColor, p1, p2, strokeWidth = stroke, cap = StrokeCap.Round)
                drawLine(onColor, p2, p3, strokeWidth = stroke, cap = StrokeCap.Round)
            }
            TodoStatus.IN_PROGRESS -> {
                drawCircle(color, radius = r, center = center, style = Stroke(stroke))
                drawArc(
                    color, startAngle = 90f, sweepAngle = 180f, useCenter = true,
                    topLeft = Offset(center.x - r, center.y - r), size = Size(r * 2, r * 2),
                )
            }
            TodoStatus.PENDING -> drawCircle(color, radius = r, center = center, style = Stroke(stroke))
        }
    }
}
