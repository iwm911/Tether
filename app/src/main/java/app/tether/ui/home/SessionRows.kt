package app.tether.ui.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.QuestionAnswer
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.tether.core.AskQuestions
import app.tether.core.Connection
import app.tether.core.Session
import app.tether.core.SessionPending
import app.tether.ui.components.CodeChip
import app.tether.ui.components.MachineBadge
import app.tether.ui.components.PrimaryButton
import app.tether.ui.components.SecondaryButton
import app.tether.ui.components.StatusDot
import app.tether.ui.components.TagChip
import app.tether.ui.components.TetherCard
import app.tether.ui.components.accentColor
import app.tether.ui.components.projectName
import app.tether.ui.components.relativeTime
import app.tether.ui.theme.Serif
import app.tether.ui.theme.Space
import app.tether.ui.theme.TetherTheme

@Composable
fun SessionBadge.color(): Color = when (this) {
    SessionBadge.WORKING -> TetherTheme.colors.clay
    SessionBadge.NEEDS_YOU -> TetherTheme.colors.warning
    SessionBadge.IDLE -> TetherTheme.colors.success
    SessionBadge.DONE -> TetherTheme.colors.faint
    SessionBadge.FAILED -> TetherTheme.colors.danger
    SessionBadge.OFFLINE -> TetherTheme.colors.faint
}

/** Dot + label: working / needs you / idle / done / failed. */
@Composable
fun SessionStateBadge(badge: SessionBadge, modifier: Modifier = Modifier) {
    val c by animateColorAsState(badge.color(), label = "sessionBadge")
    Row(modifier.padding(end = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        StatusDot(c, pulsing = badge == SessionBadge.WORKING || badge == SessionBadge.NEEDS_YOU, size = 6.dp)
        Text(badge.label, style = MaterialTheme.typography.labelMedium, color = c, maxLines = 1)
    }
}

/** The small "in terminal" mark: a `claude` open on the computer holds this session. */
@Composable
fun InTerminalMark(modifier: Modifier = Modifier) {
    MiniBadge("in terminal", TetherTheme.colors.info, modifier, icon = Icons.Rounded.Terminal)
}

/**
 * One row of the session list (Home and Machine). Needs-you rows are amber and carry the prompt:
 * Allow / Deny inline for a tool permission ([decided] = the optimistic answer), Answer / Review
 * (opens the session) for a question or dialog.
 */
@Composable
fun SessionCard(
    session: Session,
    machine: Connection?,
    showMachine: Boolean,
    decided: Boolean?,
    onOpen: () -> Unit,
    onAllow: () -> Unit,
    onDeny: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val badge = session.badge()
    val needsYou = badge == SessionBadge.NEEDS_YOU
    val faded = badge == SessionBadge.DONE || badge == SessionBadge.OFFLINE
    val warning = TetherTheme.colors.warning
    TetherCard(
        modifier = modifier.fillMaxWidth(),
        onClick = onOpen,
        color = if (needsYou) warning.copy(alpha = if (TetherTheme.colors.isDark) 0.09f else 0.07f).compositeOver(TetherTheme.colors.card) else TetherTheme.colors.card,
        border = if (needsYou) warning.copy(alpha = if (TetherTheme.colors.isDark) 0.22f else 0.30f) else TetherTheme.colors.cardBorder,
        contentPadding = PaddingValues(start = 20.dp, end = 16.dp, top = 16.dp, bottom = 16.dp),
    ) {
        Column {
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    session.title,
                    style = MaterialTheme.typography.headlineSmall.copy(
                        fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.Normal,
                        fontFamily = if (needsYou) Serif else MaterialTheme.typography.headlineSmall.fontFamily,
                    ),
                    color = if (faded) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(10.dp))
                SessionStateBadge(badge, Modifier.padding(top = 2.dp))
            }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                val metaStyle = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Normal)
                Text(
                    projectName(session.cwd.ifEmpty { "/" }),
                    style = metaStyle,
                    color = TetherTheme.colors.faint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (session.updatedAt > 0) {
                    // Offline: the time is when the machine last told us anything about it.
                    val time = relativeTime(session.updatedAt)
                    Text(if (session.offline) "  ·  last seen $time" else "  ·  $time", style = metaStyle, color = TetherTheme.colors.faint, maxLines = 1, softWrap = false)
                }
                if (showMachine && machine != null) {
                    Spacer(Modifier.width(8.dp))
                    MachineBadge(machine.name, machine.accent)
                }
                if (session.heldByTerminal) {
                    Spacer(Modifier.width(8.dp))
                    InTerminalMark()
                }
            }
            if (needsYou) {
                NeedsYouBody(session, decided, onOpen, onAllow, onDeny)
            } else {
                val snippet = session.lastText?.let(::plainSnippet)?.takeIf { it.isNotBlank() }
                if (snippet != null) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        snippet,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (badge == SessionBadge.FAILED) TetherTheme.colors.danger else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun NeedsYouBody(session: Session, decided: Boolean?, onOpen: () -> Unit, onAllow: () -> Unit, onDeny: () -> Unit) {
    val warning = TetherTheme.colors.warning
    val pending = session.pending
    val inline = session.inlinePermission()
    Spacer(Modifier.height(10.dp))
    when (pending) {
        is SessionPending.Permission -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(toolIcon(pending.toolName), null, tint = warning, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Claude " + permissionVerb(pending.toolName), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (pending.summary.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                CodeChip(pending.summary, maxLines = 3)
            }
        }
        is SessionPending.Question -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.QuestionAnswer, null, tint = TetherTheme.colors.clay, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Claude has a question", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val q = runCatching { AskQuestions.parse(pending.inputJson).questions.firstOrNull()?.question }.getOrNull() ?: pending.summary
            if (q.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(q, style = MaterialTheme.typography.bodyLarge, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
        is SessionPending.Dialog -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Tune, null, tint = warning, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    pending.title.trim().ifEmpty { "Claude Code is waiting on a prompt" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        null -> Text(
            session.waitingFor?.takeIf { it.isNotBlank() }?.let { "Waiting for $it" } ?: "Claude is waiting for you.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (session.heldByTerminal) return // answered in the terminal that holds it
    Spacer(Modifier.height(12.dp))
    AnimatedContent(
        targetState = decided,
        transitionSpec = {
            (fadeIn(tween(220, delayMillis = 90)) + expandVertically()) togetherWith (fadeOut(tween(120)) + shrinkVertically()) using SizeTransform(clip = true)
        },
        label = "sessionDecision",
    ) { d ->
        when {
            d == null && inline != null -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SecondaryButton("Deny", onClick = onDeny, modifier = Modifier.weight(1f), icon = Icons.Rounded.Close)
                PrimaryButton("Allow", onClick = onAllow, modifier = Modifier.weight(1f), icon = Icons.Rounded.Check)
            }
            d == null && pending is SessionPending.Question ->
                PrimaryButton("Answer", onClick = onOpen, modifier = Modifier.fillMaxWidth(), icon = Icons.Rounded.QuestionAnswer)
            d == null -> PrimaryButton("Review", onClick = onOpen, modifier = Modifier.fillMaxWidth())
            else -> {
                val c = if (d) TetherTheme.colors.success else TetherTheme.colors.danger
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 40.dp).clip(RoundedCornerShape(14.dp)).background(c.copy(alpha = 0.1f)).padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(if (d) Icons.Rounded.Check else Icons.Rounded.Block, null, tint = c, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(
                        if (d) "Allowed · Claude is continuing" else "Denied · Claude will find another way",
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                        color = c,
                    )
                }
            }
        }
    }
}

/**
 * Filter chips: machines (when [machineChips] is not empty) then projects. "All" clears that part.
 */
@Composable
fun SessionFilterChips(
    filter: SessionFilter,
    machineChips: List<String>,
    machines: Map<String, Connection>,
    projects: List<ProjectChip>,
    onMachine: (String?) -> Unit,
    onProject: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (machineChips.isEmpty() && projects.size < 2 && filter.project == null) return
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
        if (machineChips.isNotEmpty()) {
            LazyRow(contentPadding = PaddingValues(horizontal = Space.gutter), horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                item(key = "m:all") { TagChip("All machines", selected = filter.machine == null, onClick = { onMachine(null) }) }
                items(machineChips, key = { "m:$it" }) { id ->
                    val c = machines[id]
                    TagChip(
                        c?.name ?: id,
                        selected = filter.machine == id,
                        accent = c?.let { accentColor(it.accent) } ?: TetherTheme.colors.clay,
                        onClick = { onMachine(if (filter.machine == id) null else id) },
                    )
                }
            }
        }
        if (projects.size >= 2 || filter.project != null) {
            LazyRow(contentPadding = PaddingValues(horizontal = Space.gutter), horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                item(key = "p:all") { TagChip("All projects", selected = filter.project == null, onClick = { onProject(null) }) }
                items(projects, key = { "p:" + it.cwd }) { p ->
                    val selected = filter.project != null && samePath(filter.project, p.cwd)
                    TagChip(
                        projectName(p.cwd),
                        icon = Icons.Rounded.Folder,
                        selected = selected,
                        onClick = { onProject(if (selected) null else p.cwd) },
                    )
                }
            }
        }
    }
}

/** "Show older" at the end of the list (paging past what the watch carries). */
@Composable
fun ShowOlderButton(loading: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(horizontal = Space.gutter, vertical = Space.sm), contentAlignment = Alignment.Center) {
        SecondaryButton(if (loading) "Loading…" else "Show older sessions", onClick = onClick, enabled = !loading)
    }
}
