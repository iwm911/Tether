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
import androidx.compose.ui.unit.dp
import app.tether.ui.chat.render.Markdown
import app.tether.ui.theme.Space
import app.tether.ui.theme.TetherTheme

/*
 * Claude Code's /btw answers a side question from the session's context, in a panel over its prompt box, and adds
 * nothing to the conversation. The helper types it into the session's terminal and reads the answer back; the phone
 * shows it in a sheet instead of sending the draft as a message.
 */

private val BTW = Regex("^/btw(?:\\s+([\\s\\S]*))?$")

/** The question of a `/btw …` draft ("" when there is none yet), or null when the draft isn't one. */
internal fun btwQuestion(text: String): String? = BTW.matchEntire(text.trim())?.let { it.groupValues[1].trim() }

data class BtwState(val question: String, val answer: String? = null, val error: String? = null) {
    val loading: Boolean get() = answer == null && error == null
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
