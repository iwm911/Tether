package app.tether.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.tether.core.SessionHub
import app.tether.core.SlashCommand
import app.tether.ui.theme.TetherTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Commands matching a draft that is a bare `/word` (no whitespace yet): prefix matches first, shortest
 * name first, then substring matches. Empty when the draft is not a command being typed.
 */
internal fun matchSlashCommands(text: String, commands: List<SlashCommand>): List<SlashCommand> {
    if (!text.startsWith("/") || text.any { it.isWhitespace() }) return emptyList()
    val q = text.drop(1)
    val norm = commands.map { it.copy(name = it.name.removePrefix("/")) }.distinctBy { it.name }
    val starts = norm.filter { it.name.startsWith(q, ignoreCase = true) }
    val contains = norm.filter { !it.name.startsWith(q, ignoreCase = true) && it.name.contains(q, ignoreCase = true) }
    return (starts.sortedBy { it.name.length } + contains).take(40)
}

/**
 * Slash commands of whatever machine folder [target] points at, for composers with no session
 * to ask yet. Stays empty until known, and on failure (the popup just doesn't show).
 */
@OptIn(FlowPreview::class)
internal fun CoroutineScope.slashCommandsFor(hub: SessionHub, target: Flow<Pair<String, String>?>): StateFlow<List<SlashCommand>> {
    val out = MutableStateFlow<List<SlashCommand>>(emptyList())
    launch {
        target.distinctUntilChanged().debounce(300).collectLatest { t ->
            out.value = emptyList()
            if (t == null) return@collectLatest
            out.value = try {
                hub.slashCommands(t.first, t.second)
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Throwable) {
                emptyList()
            }
        }
    }
    return out.asStateFlow()
}

@Composable
internal fun SlashPopup(matches: List<SlashCommand>, onPick: (SlashCommand) -> Unit, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, TetherTheme.colors.hairline),
        modifier = modifier.fillMaxWidth(),
    ) {
        LazyColumn(Modifier.heightIn(max = 264.dp), contentPadding = PaddingValues(vertical = 6.dp)) {
            items(matches, key = { it.name }) { cmd ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable(role = Role.Button, onClickLabel = "Insert /${cmd.name}") { onPick(cmd) }
                        .padding(horizontal = 16.dp, vertical = 9.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "/${cmd.name}",
                            style = TetherTheme.type.mono.copy(fontWeight = FontWeight.Medium),
                            color = TetherTheme.colors.clay,
                            maxLines = 1,
                        )
                        if (cmd.argumentHint.isNotBlank()) {
                            Spacer(Modifier.width(8.dp))
                            Text(cmd.argumentHint, style = TetherTheme.type.monoSmall, color = TetherTheme.colors.faint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    if (cmd.description.isNotBlank()) {
                        Text(
                            cmd.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}
