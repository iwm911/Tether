package app.tether.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import app.tether.core.AgentHub
import app.tether.core.SlashCommand
import app.tether.ui.chat.render.Markdown
import app.tether.ui.theme.Space
import app.tether.ui.theme.TetherTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Claude Code's /btw is a TUI-only command (`claude -p` answers "/btw isn't available in this
 * environment"), and Claude's initialize list leaves it out. Tether runs it itself: the helper asks
 * a forked, unsaved, tool-less copy of the session, and the answer shows in a sheet, never in the chat.
 */
internal val BTW_COMMAND = SlashCommand("btw", "Ask a quick side question without adding it to the conversation", "<question>")

private val BTW = Regex("^/btw(?:\\s+([\\s\\S]*))?$")

/** The question of a `/btw …` draft ("" when there is none yet), or null when it isn't one. */
internal fun btwQuestion(text: String): String? = BTW.matchEntire(text.trim())?.let { it.groupValues[1].trim() }

/** [commands] plus /btw, for composers backed by a session. */
internal fun withBtw(commands: List<SlashCommand>): List<SlashCommand> =
    if (commands.any { it.name.removePrefix("/") == BTW_COMMAND.name }) commands else commands + BTW_COMMAND

data class BtwState(val question: String, val answer: String? = null, val error: String? = null) {
    val loading: Boolean get() = answer == null && error == null
}

/** One side question at a time per chat; asking again replaces it. */
internal class BtwController(private val scope: CoroutineScope, private val hub: AgentHub) {
    private val _state = MutableStateFlow<BtwState?>(null)
    val state: StateFlow<BtwState?> = _state.asStateFlow()
    private var job: Job? = null

    /**
     * Takes a `/btw …` draft out of [composer] and asks it; false (draft untouched) when it isn't one.
     * Staged attachments stay in the composer: side questions are text only.
     */
    fun intercept(
        composer: ComposerState,
        connectionId: String,
        sessionId: String?,
        cwd: String?,
        model: String?,
        say: (String) -> Unit,
    ): Boolean {
        val question = btwQuestion(composer.value.text) ?: return false
        when {
            question.isEmpty() -> say("Type a question after /btw")
            sessionId == null -> say("There's no conversation to ask about yet")
            else -> {
                composer.value = TextFieldValue("", TextRange(0))
                ask(connectionId, sessionId, cwd ?: "~", question, model)
            }
        }
        return true
    }

    private fun ask(connectionId: String, sessionId: String, cwd: String, question: String, model: String?) {
        job?.cancel()
        _state.value = BtwState(question)
        job = scope.launch {
            val next = try {
                BtwState(question, answer = hub.askAside(connectionId, sessionId, cwd, question, model).ifBlank { "(No answer)" })
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                BtwState(question, error = friendlyError(t, "Couldn't get an answer"))
            }
            _state.update { cur -> if (cur?.question == question) next else cur }
        }
    }

    fun dismiss() {
        job?.cancel()
        job = null
        _state.value = null
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BtwSheet(state: BtwState, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .heightIn(max = 560.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Space.gutter)
                .padding(bottom = Space.md),
            verticalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.ChatBubbleOutline, contentDescription = null, tint = TetherTheme.colors.clay, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
                Text("By the way", style = MaterialTheme.typography.titleMedium)
            }
            Text(state.question, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            when {
                state.loading -> Row(Modifier.padding(vertical = Space.sm), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = TetherTheme.colors.clay)
                    Spacer(Modifier.width(10.dp))
                    Text("Claude is answering…", style = MaterialTheme.typography.bodyMedium, color = TetherTheme.colors.faint)
                }
                state.error != null -> Text(state.error, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                else -> Markdown(state.answer.orEmpty())
            }
            Text(
                "Side question · not added to the conversation",
                style = MaterialTheme.typography.labelSmall,
                color = TetherTheme.colors.faint,
            )
        }
    }
}
