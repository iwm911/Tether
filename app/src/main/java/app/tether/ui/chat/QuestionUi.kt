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
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.QuestionAnswer
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.tether.core.AskAnswer
import app.tether.core.AskPrompt
import app.tether.core.AskQuestion
import app.tether.core.AskQuestions
import app.tether.core.ChatItem
import app.tether.core.PermissionDecision
import app.tether.ui.components.ClaudeSpinner
import app.tether.ui.components.PrimaryButton
import app.tether.ui.components.TetherTextField
import app.tether.ui.components.rememberHaptics
import app.tether.ui.theme.Motion
import app.tether.ui.theme.Serif
import app.tether.ui.theme.Space
import app.tether.ui.theme.TetherTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Claude's AskUserQuestion as a native picker — the phone version of Claude Code's question UI.
 * One question at a time ("1 of 2"), options as tappable cards (radio or checkbox), an optional
 * typed answer, then Submit. Single-choice questions advance on tap. The header folds the panel
 * down to one line, so the conversation above it can be read before answering.
 */
@Composable
internal fun QuestionBody(
    item: ChatItem.Permission,
    responding: Boolean,
    error: String?,
    onRespond: (String, PermissionDecision) -> Unit,
    onFetch: (suspend () -> String)?,
) {
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    // Re-read when the same request comes back with more known (e.g. multiSelect read off the screen later).
    var inputJson by remember(item.requestId, item.inputJson) { mutableStateOf(item.inputJson) }
    val prompt: AskPrompt = remember(inputJson) { AskQuestions.parse(inputJson) }
    var loadError by remember(item.requestId) { mutableStateOf<String?>(null) }
    var loading by remember(item.requestId) { mutableStateOf(false) }

    fun fetch() {
        val f = onFetch ?: return
        loading = true
        loadError = null
        scope.launch {
            try {
                inputJson = f()
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                loadError = t.message ?: "Couldn't read the question."
            } finally {
                loading = false
            }
        }
    }
    LaunchedEffect(item.requestId, prompt.needsFetch) {
        haptics.confirm()
        if (prompt.needsFetch && onFetch != null) fetch()
    }

    var collapsed by remember(item.requestId) { mutableStateOf(false) }
    LaunchedEffect(error) { if (error != null) collapsed = false }
    // The dock rides the keyboard: size the panel against the screen the keyboard leaves.
    val imeDp = with(LocalDensity.current) { WindowInsets.ime.getBottom(this).toDp() }
    val maxPanel = ((LocalConfiguration.current.screenHeightDp.dp - imeDp) * 0.55f).coerceAtLeast(180.dp)
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, TetherTheme.colors.clay.copy(alpha = 0.35f)),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Column(Modifier.heightIn(max = maxPanel).padding(horizontal = Space.lg, vertical = 14.dp)) {
            val questions = prompt.questions
            when {
                questions.isEmpty() && (loading || (prompt.needsFetch && loadError == null)) -> {
                    Header(null, null)
                    Row(Modifier.padding(vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                        ClaudeSpinner(fontSize = 15f)
                        Spacer(Modifier.width(10.dp))
                        Text("Reading the question…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                questions.isEmpty() -> {
                    Header(null, null)
                    Text(
                        loadError ?: "This question can't be shown here.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TetherTheme.colors.danger,
                        modifier = Modifier.padding(vertical = 10.dp),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (onFetch != null) TextButton(onClick = { fetch() }) { Text("Try again") }
                        TextButton(onClick = { onRespond(item.requestId, PermissionDecision.Deny("The user chose not to answer.")) }) { Text("Skip") }
                    }
                }
                else -> QuestionSteps(item, questions, responding, error, collapsed, { collapsed = !collapsed }, onRespond)
            }
        }
    }
}

@Composable
private fun Header(step: Int?, total: Int?, collapsed: Boolean = false, onToggle: (() -> Unit)? = null) {
    val rot by animateFloatAsState(if (collapsed) 180f else 0f, label = "questionFold")
    Row(
        if (onToggle == null) Modifier
        else Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClickLabel = if (collapsed) "Show the question" else "Hide the question", onClick = onToggle),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.QuestionAnswer, contentDescription = null, tint = TetherTheme.colors.clay, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text("Claude has a question", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        if (step != null && total != null && total > 1) {
            Text("$step of $total", style = MaterialTheme.typography.labelMedium, color = TetherTheme.colors.faint)
        }
        if (onToggle != null) {
            Spacer(Modifier.width(6.dp))
            Icon(Icons.Rounded.ExpandMore, contentDescription = null, tint = TetherTheme.colors.faint, modifier = Modifier.size(22.dp).rotate(rot))
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.QuestionSteps(
    item: ChatItem.Permission,
    questions: List<AskQuestion>,
    responding: Boolean,
    error: String?,
    collapsed: Boolean,
    onToggleCollapsed: () -> Unit,
    onRespond: (String, PermissionDecision) -> Unit,
) {
    val haptics = rememberHaptics()
    val answers = remember(item.requestId, questions.size) { mutableStateListOf(*Array(questions.size) { AskAnswer() }) }
    var step by remember(item.requestId) { mutableIntStateOf(0) }
    var otherOpen by remember(item.requestId) { mutableStateOf(setOf<Int>()) }
    // Set when "Something else…" is tapped open: its text field takes the focus (and the keyboard).
    var focusOther by remember(item.requestId) { mutableStateOf<Int?>(null) }
    val scope = rememberCoroutineScope()
    val last = step == questions.lastIndex

    fun submit() {
        haptics.confirm()
        onRespond(item.requestId, PermissionDecision.Answer(answers.toList()))
    }

    Header(step + 1, questions.size, collapsed, onToggleCollapsed)
    if (collapsed) {
        // Folded to one line of the question, so the chat above can be scrolled and read.
        Text(
            questions[step].question,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp).clickable(onClick = onToggleCollapsed),
        )
        return
    }
    Spacer(Modifier.height(10.dp))
    AnimatedContent(
        targetState = step,
        transitionSpec = {
            val dir = if (targetState > initialState) 1 else -1
            (slideInHorizontally(Motion.gentle()) { dir * it / 4 } + fadeIn(tween(Motion.Medium))) togetherWith
                (slideOutHorizontally(tween(Motion.Short)) { -dir * it / 4 } + fadeOut(tween(Motion.Short)))
        },
        modifier = Modifier.weight(1f, fill = false),
        label = "questionStep",
    ) { i ->
        val q = questions[i]
        Column(Modifier.verticalScroll(rememberScrollState())) {
            if (q.header.isNotBlank()) {
                Text(q.header.uppercase(), style = TetherTheme.type.eyebrow, color = TetherTheme.colors.clay)
                Spacer(Modifier.height(4.dp))
            }
            Text(
                q.question,
                style = MaterialTheme.typography.headlineSmall.copy(fontFamily = Serif, fontSize = 19.sp, lineHeight = 25.sp, fontWeight = FontWeight.Normal),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(2.dp))
            Text(if (q.multiSelect) "Choose any" else "Choose one", style = MaterialTheme.typography.labelMedium, color = TetherTheme.colors.faint)
            Spacer(Modifier.height(10.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                q.options.forEachIndexed { oi, opt ->
                    val selected = oi in answers[i].choices
                    OptionCard(
                        label = opt.label,
                        description = opt.description,
                        selected = selected,
                        multi = q.multiSelect,
                        enabled = !responding,
                        onClick = {
                            haptics.tick()
                            val a = answers[i]
                            answers[i] = if (q.multiSelect) {
                                a.copy(choices = if (selected) a.choices - oi else a.choices + oi)
                            } else {
                                AskAnswer(choices = listOf(oi))
                            }
                            if (!q.multiSelect) {
                                otherOpen = otherOpen - i
                                if (!last) scope.launch { delay(260); if (step == i) step = i + 1 }
                            }
                        },
                    )
                }
                val otherSelected = i in otherOpen
                OptionCard(
                    label = "Something else…",
                    description = if (otherSelected) null else "Type your own answer",
                    selected = otherSelected,
                    multi = q.multiSelect,
                    enabled = !responding,
                    onClick = {
                        haptics.tick()
                        otherOpen = if (otherSelected) otherOpen - i else otherOpen + i
                        focusOther = if (otherSelected) null else i
                        if (!q.multiSelect && !otherSelected) answers[i] = AskAnswer(other = answers[i].other)
                        if (otherSelected) answers[i] = answers[i].copy(other = null)
                    },
                )
                AnimatedVisibility(otherSelected, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                    val focus = remember { FocusRequester() }
                    val bring = remember { BringIntoViewRequester() }
                    val keyboard = LocalSoftwareKeyboardController.current
                    LaunchedEffect(focusOther) {
                        if (focusOther != i) return@LaunchedEffect
                        focusOther = null
                        delay(Motion.Medium.toLong())  // let the field expand first
                        runCatching { focus.requestFocus() }
                        keyboard?.show()
                        delay(300)  // then scroll it into view once the keyboard has pushed the panel up
                        bring.bringIntoView()
                    }
                    TetherTextField(
                        value = answers[i].other.orEmpty(),
                        onValueChange = { v -> answers[i] = answers[i].copy(other = v, choices = if (q.multiSelect) answers[i].choices else emptyList()) },
                        label = "Your answer",
                        singleLine = false,
                        modifier = Modifier.padding(top = 2.dp).bringIntoViewRequester(bring).focusRequester(focus),
                    )
                }
            }
        }
    }
    if (error != null) {
        // Next to the buttons, never scrolled out of sight: the answer didn't go through.
        Text(
            "Couldn't send your answer — $error",
            style = MaterialTheme.typography.bodySmall,
            color = TetherTheme.colors.danger,
            modifier = Modifier.padding(top = 10.dp),
        )
    }
    Spacer(Modifier.height(12.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(
            onClick = { onRespond(item.requestId, PermissionDecision.Deny("The user chose not to answer.")) },
            enabled = !responding,
        ) { Text("Skip", color = TetherTheme.colors.faint) }
        Spacer(Modifier.weight(1f))
        if (step > 0) {
            TextButton(onClick = { step -= 1 }, enabled = !responding) { Text("Back") }
            Spacer(Modifier.width(4.dp))
        }
        val ready = AskQuestions.isComplete(questions[step], answers.getOrNull(step))
        val allReady = questions.indices.all { AskQuestions.isComplete(questions[it], answers.getOrNull(it)) }
        PrimaryButton(
            text = if (last) "Submit" else "Next",
            onClick = { if (last) submit() else step += 1 },
            enabled = if (last) allReady else ready,
            loading = responding,
        )
    }
}

@Composable
private fun OptionCard(
    label: String,
    description: String?,
    selected: Boolean,
    multi: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val c = TetherTheme.colors
    val bg by animateColorAsState(if (selected) c.clay.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceContainerHigh, label = "optBg")
    val edge by animateColorAsState(if (selected) c.clay.copy(alpha = 0.6f) else c.hairline, label = "optEdge")
    val shape = RoundedCornerShape(16.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(bg)
            .border(1.dp, edge, shape)
            .clickable(enabled = enabled, role = if (multi) Role.Checkbox else Role.RadioButton, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SelectMark(selected, multi)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium), color = MaterialTheme.colorScheme.onSurface)
            if (!description.isNullOrBlank()) {
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun SelectMark(selected: Boolean, multi: Boolean) {
    val c = TetherTheme.colors
    val shape = if (multi) RoundedCornerShape(6.dp) else CircleShape
    Box(
        Modifier
            .size(22.dp)
            .clip(shape)
            .background(if (selected) c.clay else androidx.compose.ui.graphics.Color.Transparent)
            .border(1.5.dp, if (selected) c.clay else MaterialTheme.colorScheme.outline, shape),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            if (multi) Icon(Icons.Rounded.Check, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(15.dp))
            else Box(Modifier.size(8.dp).clip(CircleShape).background(MaterialTheme.colorScheme.onPrimary))
        }
    }
}

/** One line for the chat history once a question was answered: "Color → Blue · Pets → Cats, Dogs". */
internal fun answeredSummary(inputJson: String?, structuredJson: String?): String? {
    val prompt = AskQuestions.parse(structuredJson ?: inputJson)
    val answers = runCatching {
        (kotlinx.serialization.json.Json.parseToJsonElement(structuredJson ?: "{}") as? kotlinx.serialization.json.JsonObject)
            ?.get("answers") as? kotlinx.serialization.json.JsonObject
    }.getOrNull() ?: return null
    return prompt.questions.mapNotNull { q ->
        val a = (answers[q.question] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: return@mapNotNull null
        "${q.header.ifBlank { q.question }} → $a"
    }.joinToString(" · ").ifBlank { null }
}
