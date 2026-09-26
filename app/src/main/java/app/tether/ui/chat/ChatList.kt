package app.tether.ui.chat

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.tether.core.ChatItem
import app.tether.ui.chat.render.ChatItemView
import app.tether.ui.components.SecondaryButton
import app.tether.ui.components.rememberHaptics
import app.tether.ui.theme.Motion
import app.tether.ui.theme.Space
import app.tether.ui.theme.TetherTheme
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

private fun ChatItem.contentType(): Int = when (this) {
    is ChatItem.User -> 1
    is ChatItem.AssistantText -> 2
    is ChatItem.Thinking -> 3
    is ChatItem.ToolCall -> 4
    is ChatItem.Permission -> 5
    is ChatItem.TurnSummary -> 6
    is ChatItem.Notice -> 7
}

/** Vertical rhythm: prose breathes, tool rows stack tightly like the CLI. */
private fun ChatItem.verticalGap(): Dp = when (this) {
    is ChatItem.User -> 14.dp
    is ChatItem.AssistantText -> 8.dp
    is ChatItem.Thinking -> 2.dp
    is ChatItem.ToolCall -> 1.dp
    is ChatItem.Permission -> 8.dp
    is ChatItem.TurnSummary -> 8.dp
    is ChatItem.Notice -> 8.dp
}

/**
 * The conversation list. Follows the bottom while the user is there (streaming text included),
 * stops following as soon as they drag up, and offers a "New activity" pill to jump back.
 *
 * Laid out bottom-up ([LazyColumn] `reverseLayout`, index 0 = newest) so a long transcript opens
 * anchored at the latest message instead of rendering from the top and chasing the end as rows
 * are measured. [items] stay chronological; note "older content above" is [LazyListState.canScrollForward].
 *
 * Every row gets the horizontal gutter and vertical rhythm here; [ChatItemView] draws no outer padding.
 */
@Composable
internal fun ChatList(
    items: List<ChatItem>,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    contentPadding: PaddingValues = PaddingValues(),
    showThinking: Boolean = true,
    compactTools: Boolean = true,
    loading: Boolean = false,
    error: String? = null,
    errorTitle: String = "Couldn't load this conversation",
    onRetry: (() -> Unit)? = null,
    header: (@Composable () -> Unit)? = null,
    footer: (@Composable () -> Unit)? = null,
    empty: (@Composable () -> Unit)? = null,
    permission: @Composable (ChatItem.Permission, Modifier) -> Unit = { p, m -> PermissionCard(p, responding = false, onRespond = { _, _ -> }, modifier = m) },
    actions: MessageActions? = null,
) {
    val ends = remember(items, actions != null) { if (actions != null) turnEnds(items) else emptyMap() }
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()
    val dragged by listState.interactionSource.collectIsDraggedAsState()
    var follow by rememberSaveable { mutableStateOf(true) }
    var countAtUnfollow by remember { mutableIntStateOf(0) }
    val itemCount by rememberUpdatedState(items.size)

    // Follow bookkeeping: reaching the end re-arms following; dragging away from it disarms.
    LaunchedEffect(listState) {
        snapshotFlow { dragged to listState.canScrollBackward }.collect { (isDragged, awayFromEnd) ->
            if (!awayFromEnd) {
                follow = true
            } else if (isDragged && follow) {
                follow = false
                countAtUnfollow = itemCount
            }
        }
    }
    // Bottom-anchored layout keeps growing rows pinned; only new rows can land below the fold.
    LaunchedEffect(listState) {
        snapshotFlow { follow && !dragged && listState.canScrollBackward }.collectLatest { should ->
            if (should) listState.scrollToItem(0)
        }
    }

    val showPill by remember { derivedStateOf { !follow && listState.canScrollBackward } }
    val hasNew = !follow && items.size > countAtUnfollow

    Box(modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            contentPadding = contentPadding,
            reverseLayout = true,
            verticalArrangement = Arrangement.Top,
            modifier = Modifier.fillMaxSize(),
        ) {
            // Emitted newest-first: index 0 sits at the bottom.
            if (footer != null) {
                item(key = "__footer", contentType = 104) {
                    Box(Modifier.animateItem(placementSpec = null).fillMaxWidth().padding(horizontal = Space.gutter, vertical = 10.dp)) { footer() }
                }
            }
            if (!loading && items.isEmpty() && error == null && empty != null) {
                item(key = "__empty", contentType = 103) { Box(Modifier.animateItem()) { empty() } }
            }
            if (error != null) {
                item(key = "__error", contentType = 102) {
                    ErrorCard(
                        title = errorTitle,
                        detail = error,
                        onRetry = onRetry,
                        modifier = Modifier.animateItem().padding(horizontal = Space.gutter, vertical = Space.md),
                    )
                }
            }
            items(items.asReversed(), key = { it.key }, contentType = { it.contentType() }) { item ->
                val m = Modifier
                    .animateItem(
                        fadeInSpec = tween(Motion.Medium),
                        placementSpec = spring(stiffness = Spring.StiffnessMediumLow, visibilityThreshold = IntOffset.VisibilityThreshold),
                        fadeOutSpec = tween(Motion.Short),
                    )
                    .fillMaxWidth()
                    .padding(horizontal = Space.gutter, vertical = item.verticalGap())
                if (item is ChatItem.Permission) permission(item, m)
                else if (actions != null && item is ChatItem.AssistantText && ends.containsKey(item.key)) {
                    androidx.compose.foundation.layout.Column(m) {
                        ChatItemView(item, Modifier.fillMaxWidth(), showThinking = showThinking, compactTools = compactTools)
                        MessageActionRow(item, ends[item.key], actions)
                    }
                } else androidx.compose.runtime.CompositionLocalProvider(LocalUserLongPress provides actions?.onUserMessage) {
                    ChatItemView(item, m, showThinking = showThinking, compactTools = compactTools)
                }
            }
            if (loading && items.isEmpty()) {
                item(key = "__skeleton", contentType = 101) {
                    SkeletonTranscript(Modifier.animateItem(fadeInSpec = null, placementSpec = null))
                }
            }
            if (header != null) {
                item(key = "__header", contentType = 100) { Box(Modifier.animateItem()) { header() } }
            }
        }

        AnimatedVisibility(
            visible = showPill,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = contentPadding.calculateBottomPadding() + 10.dp),
            enter = fadeIn(tween(Motion.Short)) + scaleIn(Motion.bouncy(), initialScale = 0.85f) + slideInVertically(Motion.gentle()) { it / 2 },
            exit = fadeOut(tween(Motion.Short)) + scaleOut(tween(Motion.Short), targetScale = 0.9f) + slideOutVertically(tween(Motion.Short)) { it / 2 },
        ) {
            JumpPill(
                hasNew = hasNew,
                onClick = {
                    haptics.tick()
                    scope.launch {
                        if (listState.firstVisibleItemIndex > 12) listState.scrollToItem(6)
                        listState.animateScrollToItem(0)
                        follow = true
                    }
                },
            )
        }
    }
}

@Composable
private fun JumpPill(hasNew: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = if (hasNew) TetherTheme.colors.clay else MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, if (hasNew) TetherTheme.colors.clay.copy(alpha = 0.45f) else TetherTheme.colors.hairline),
        modifier = Modifier.semantics { contentDescription = if (hasNew) "New activity, jump to latest" else "Jump to latest" },
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.ArrowDownward, contentDescription = null, modifier = Modifier.size(16.dp))
            AnimatedContent(hasNew, transitionSpec = { fadeIn(tween(Motion.Short)) togetherWith fadeOut(tween(Motion.Short)) }, label = "pill") { n ->
                if (n) {
                    Row {
                        Spacer(Modifier.width(6.dp))
                        Text("New activity", style = MaterialTheme.typography.labelLarge)
                    }
                } else {
                    Spacer(Modifier.width(0.dp))
                }
            }
        }
    }
}

// ───────────────────────────── States ─────────────────────────────

@Composable
internal fun ErrorCard(title: String, detail: String, onRetry: (() -> Unit)?, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    val danger = TetherTheme.colors.danger
    val chevron by animateFloatAsState(if (open) 180f else 0f, Motion.gentle(), label = "errChevron")
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, danger.copy(alpha = 0.4f)),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(Space.lg)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(34.dp).clip(RoundedCornerShape(11.dp)).background(danger.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Rounded.ErrorOutline, contentDescription = null, tint = danger, modifier = Modifier.size(19.dp)) }
                Spacer(Modifier.width(Space.md))
                Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(Space.sm))
            TextButton(onClick = { open = !open }, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp)) {
                Text(if (open) "Hide details" else "Show details", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Icon(Icons.Rounded.ExpandMore, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp).rotate(chevron))
            }
            AnimatedVisibility(open, enter = expandVertically(Motion.gentle()) + fadeIn(), exit = shrinkVertically(tween(Motion.Short)) + fadeOut()) {
                Text(
                    detail,
                    style = TetherTheme.type.monoSmall,
                    color = TetherTheme.colors.codeText,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = Space.xs)
                        .clip(RoundedCornerShape(10.dp))
                        .background(TetherTheme.colors.codeBg)
                        .padding(10.dp),
                )
            }
            if (onRetry != null) {
                Spacer(Modifier.height(Space.md))
                SecondaryButton("Try again", onClick = onRetry, icon = Icons.Rounded.Refresh)
            }
        }
    }
}

@Composable
private fun rememberShimmerBrush(): Brush {
    val base = MaterialTheme.colorScheme.surfaceContainerHigh
    val hi = MaterialTheme.colorScheme.surfaceContainerHighest
    val t = rememberInfiniteTransition(label = "skeleton")
    val x by t.animateFloat(-600f, 1600f, infiniteRepeatable(tween(1400, easing = LinearEasing)), label = "skeletonX")
    return Brush.linearGradient(listOf(base, hi, base), start = Offset(x, 0f), end = Offset(x + 500f, 200f))
}

/** Shimmering stand-in for a transcript while history replays. */
@Composable
internal fun SkeletonTranscript(modifier: Modifier = Modifier) {
    val brush = rememberShimmerBrush()
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = Space.gutter, vertical = Space.lg)
            .semantics { contentDescription = "Loading conversation" },
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) { SkeletonBar(brush, 0.62f, height = 44.dp, radius = 18.dp) }
        Spacer(Modifier.height(6.dp))
        SkeletonBar(brush, 0.94f); SkeletonBar(brush, 0.88f); SkeletonBar(brush, 0.52f)
        Spacer(Modifier.height(4.dp))
        repeat(3) { i ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(14.dp).clip(CircleShape).background(brush))
                Spacer(Modifier.width(10.dp))
                SkeletonBar(brush, listOf(0.46f, 0.6f, 0.38f)[i], height = 11.dp)
            }
        }
        Spacer(Modifier.height(4.dp))
        SkeletonBar(brush, 0.9f); SkeletonBar(brush, 0.72f)
        Spacer(Modifier.height(10.dp))
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) { SkeletonBar(brush, 0.44f, height = 38.dp, radius = 18.dp) }
        Spacer(Modifier.height(6.dp))
        SkeletonBar(brush, 0.84f); SkeletonBar(brush, 0.66f)
    }
}

@Composable
private fun SkeletonBar(brush: Brush, widthFraction: Float, height: Dp = 12.dp, radius: Dp = 6.dp) {
    Box(Modifier.fillMaxWidth(widthFraction).height(height).clip(RoundedCornerShape(radius)).background(brush))
}

/** Debug view behind "Show raw events": one mono line per rendered item. */
@Composable
internal fun RawItemsList(items: List<ChatItem>, contentPadding: PaddingValues, modifier: Modifier = Modifier) {
    LazyColumn(modifier.fillMaxSize(), contentPadding = contentPadding) {
        item(key = "__rawheader") {
            Text(
                "${items.size} items · debug view",
                style = TetherTheme.type.eyebrow,
                color = TetherTheme.colors.faint,
                modifier = Modifier.padding(horizontal = Space.gutter, vertical = Space.sm),
            )
        }
        items(items, key = { it.key }) { item ->
            val (type, text) = rawDescribe(item)
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 2.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(TetherTheme.colors.codeBg)
                    .padding(horizontal = 10.dp, vertical = 7.dp),
            ) {
                Row {
                    Text(type, style = TetherTheme.type.monoSmall, color = TetherTheme.colors.synKeyword)
                    Spacer(Modifier.width(8.dp))
                    Text(item.key, style = TetherTheme.type.monoSmall, color = TetherTheme.colors.faint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (text.isNotEmpty()) {
                    Text(text, style = TetherTheme.type.monoSmall, color = TetherTheme.colors.codeText, maxLines = 4, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        if (items.isEmpty()) {
            item(key = "__rawempty") {
                Text(
                    "No events yet",
                    style = MaterialTheme.typography.bodySmall,
                    color = TetherTheme.colors.faint,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(Space.xxl),
                )
            }
        }
    }
}

private fun rawDescribe(item: ChatItem): Pair<String, String> = when (item) {
    is ChatItem.User -> "user" to buildString {
        append(item.text.take(240))
        if (item.imageCount > 0) append("  [+${item.imageCount} image]")
        if (item.queued) append("  (queued)")
    }
    is ChatItem.AssistantText -> (if (item.streaming) "text…" else "text") to item.text.take(240)
    is ChatItem.Thinking -> "thinking" to item.text.take(160)
    is ChatItem.ToolCall -> "tool:${item.name} ${item.status.name.lowercase()}" to item.inputJson.take(240)
    is ChatItem.Permission -> "permission ${item.state.name.lowercase()}" to "${item.toolName} ${item.inputJson.take(200)}"
    is ChatItem.TurnSummary -> "result" to "success=${item.success} turns=${item.numTurns} ms=${item.durationMs} cost=${item.costUsd}" + (item.errorText?.let { " err=$it" } ?: "")
    is ChatItem.Notice -> "notice:${item.kind.name.lowercase()}" to item.text
}
