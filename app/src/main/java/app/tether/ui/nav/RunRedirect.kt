package app.tether.ui.nav

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import app.tether.LocalAppContainer
import app.tether.core.AgentSummary
import app.tether.core.RunRef
import app.tether.core.Session
import app.tether.core.SessionRef
import app.tether.core.nativeId
import app.tether.remote.NativeAgents
import app.tether.ui.chat.AgentScreen
import app.tether.ui.components.ClaudeSpinner
import app.tether.ui.theme.Space
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The session a run-model ref (a Tether run or a native background agent, as Home and the old
 * notifications still name them) shows: its session id, else the daemon session whose short id /
 * terminal pid matches the native agent. Null while unknown (a run that has not started yet).
 */
internal fun sessionRefForRun(ref: RunRef, runs: List<AgentSummary>, sessions: List<Session>): SessionRef? {
    val run = runs.firstOrNull { it.ref == ref }?.run
    run?.sessionId?.takeIf { it.isNotBlank() }?.let { return SessionRef(ref.connectionId, it) }
    val native = ref.nativeId ?: run?.nativeId ?: return null
    val mine = sessions.filter { it.connectionId == ref.connectionId }
    val match = if (native.startsWith(NativeAgents.TERMINAL_PREFIX)) {
        val pid = native.removePrefix(NativeAgents.TERMINAL_PREFIX).toIntOrNull()
        mine.firstOrNull { pid != null && it.terminalPid == pid }
    } else {
        mine.firstOrNull { it.short == native || it.sessionId.startsWith(native) }
    }
    return match?.let { SessionRef(ref.connectionId, it.sessionId) }
}

/**
 * Opens a run-model ref on the session screen: resolves its session and hands it to [onResolved]
 * (which replaces this destination). A run that never gets a session id within [timeoutMs] — a
 * Tether live run that failed to start — falls back to the legacy run screen until phase R.
 */
@Composable
internal fun RunRedirect(
    ref: RunRef,
    onResolved: (SessionRef) -> Unit,
    onBack: () -> Unit,
    onOpenMachine: (String) -> Unit,
    onOpenAgent: (RunRef) -> Unit,
    timeoutMs: Long = 20_000,
) {
    val container = LocalAppContainer.current
    var fallback by remember(ref) { mutableStateOf(false) }
    LaunchedEffect(ref) {
        val immediate = sessionRefForRun(ref, container.agents.agents.value, container.sessions.sessions.value)
        if (immediate != null) { onResolved(immediate); return@LaunchedEffect }
        launch { runCatching { container.agents.refresh(ref.connectionId) } }
        launch { runCatching { container.sessions.refresh(ref.connectionId) } }
        val found = withTimeoutOrNull(timeoutMs) {
            combine(container.agents.agents, container.sessions.sessions) { runs, sessions -> sessionRefForRun(ref, runs, sessions) }
                .first { it != null }
        }
        if (found != null) onResolved(found) else fallback = true
    }
    if (fallback) {
        AgentScreen(ref = ref, onBack = onBack, onOpenMachine = onOpenMachine, onOpenAgent = onOpenAgent)
    } else {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                ClaudeSpinner(fontSize = 22f)
                Text("Opening session…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
