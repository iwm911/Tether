package app.tether.ui.machine

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tether.AppContainer
import app.tether.core.AgentSummary
import app.tether.core.Connection
import app.tether.core.LinkState
import app.tether.core.ProbeResult
import app.tether.core.ProjectSummary
import app.tether.core.SessionSummary
import app.tether.ui.home.Loadable
import app.tether.ui.home.attempt
import app.tether.ui.home.humanMessage
import app.tether.ui.home.sortedForDisplay
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch

data class MachineUiState(
    val connection: Connection? = null,
    /** False only until the repository has been consulted once. */
    val known: Boolean = true,
    val link: LinkState? = null,
    val machineError: String? = null,
    val agents: List<AgentSummary> = emptyList(),
    val probe: Loadable<ProbeResult> = Loadable.Loading,
    val projects: Loadable<List<ProjectSummary>> = Loadable.Loading,
    val sessions: Loadable<List<SessionSummary>> = Loadable.Loading,
    val projectSessions: Map<String, Loadable<List<SessionSummary>>> = emptyMap(),
    val refreshing: Boolean = false,
    val disconnecting: Boolean = false,
)

private data class MachineLocal(
    val probe: Loadable<ProbeResult> = Loadable.Loading,
    val projects: Loadable<List<ProjectSummary>> = Loadable.Loading,
    val sessions: Loadable<List<SessionSummary>> = Loadable.Loading,
    val projectSessions: Map<String, Loadable<List<SessionSummary>>> = emptyMap(),
    val refreshing: Boolean = false,
    val disconnecting: Boolean = false,
)

class MachineViewModel(private val container: AppContainer, val connectionId: String) : ViewModel() {
    private val local = MutableStateFlow(MachineLocal())
    private val messages = Channel<String>(Channel.BUFFERED)
    val events: Flow<String> = messages.receiveAsFlow()
    private val projectJobs = mutableMapOf<String, Job>()

    val state: StateFlow<MachineUiState> = combine(
        container.connections.connections,
        container.ssh.states,
        container.agents.agents,
        container.agents.machineErrors,
        local,
    ) { conns, links, agents, errors, l ->
        val conn = conns.firstOrNull { it.id == connectionId }
        MachineUiState(
            connection = conn,
            known = conn != null,
            link = links[connectionId],
            machineError = errors[connectionId],
            agents = agents.filter { it.ref.connectionId == connectionId }.sortedForDisplay(),
            probe = l.probe,
            projects = l.projects,
            sessions = l.sessions,
            projectSessions = l.projectSessions,
            refreshing = l.refreshing,
            disconnecting = l.disconnecting,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        MachineUiState(connection = container.connections.get(connectionId), known = container.connections.get(connectionId) != null),
    )

    init {
        if (container.connections.get(connectionId) != null) {
            loadProbe()
            loadProjects()
            loadSessions()
        }
    }

    fun loadProbe(): Job = viewModelScope.launch {
        if (local.value.probe !is Loadable.Ready) local.update { it.copy(probe = Loadable.Loading) }
        val r = attempt { container.remote.probe(connectionId) }
        r.onSuccess { p ->
            local.update { it.copy(probe = Loadable.Ready(p)) }
            attempt { container.connections.markConnected(connectionId, p.hostname, p.claudeVersion) }
        }.onFailure { e ->
            // Keep a previous good probe on a failed refresh; the link dot already tells the story.
            if (local.value.probe !is Loadable.Ready) local.update { it.copy(probe = Loadable.Failed(e.humanMessage())) }
        }
    }

    fun loadProjects(): Job = viewModelScope.launch {
        if (local.value.projects !is Loadable.Ready) local.update { it.copy(projects = Loadable.Loading) }
        val r = attempt { container.remote.listProjects(connectionId) }
        r.onSuccess { list ->
            local.update { it.copy(projects = Loadable.Ready(list.sortedByDescending { p -> p.lastActiveAt })) }
        }.onFailure { e ->
            if (local.value.projects is Loadable.Ready) messages.trySend("Couldn't refresh projects — ${e.humanMessage()}")
            else local.update { it.copy(projects = Loadable.Failed(e.humanMessage())) }
        }
    }

    fun loadSessions(): Job = viewModelScope.launch {
        if (local.value.sessions !is Loadable.Ready) local.update { it.copy(sessions = Loadable.Loading) }
        val r = attempt { container.remote.listSessions(connectionId, null, 60) }
        r.onSuccess { list ->
            local.update { it.copy(sessions = Loadable.Ready(list.sortedByDescending { s -> s.updatedAt })) }
        }.onFailure { e ->
            if (local.value.sessions is Loadable.Ready) messages.trySend("Couldn't refresh sessions — ${e.humanMessage()}")
            else local.update { it.copy(sessions = Loadable.Failed(e.humanMessage())) }
        }
    }

    /** Sessions of one project, loaded when its row is expanded. */
    fun loadProjectSessions(cwd: String, force: Boolean = false) {
        val current = local.value.projectSessions[cwd]
        if (!force && (current is Loadable.Ready || projectJobs[cwd]?.isActive == true)) return
        projectJobs[cwd]?.cancel()
        if (current !is Loadable.Ready) local.update { it.copy(projectSessions = it.projectSessions + (cwd to Loadable.Loading)) }
        projectJobs[cwd] = viewModelScope.launch {
            val r = attempt { container.remote.listSessions(connectionId, cwd, 12) }
            val v: Loadable<List<SessionSummary>> = r.fold(
                onSuccess = { Loadable.Ready(it.sortedByDescending { s -> s.updatedAt }) },
                onFailure = { Loadable.Failed(it.humanMessage()) },
            )
            if (v is Loadable.Failed && current is Loadable.Ready) return@launch
            local.update { it.copy(projectSessions = it.projectSessions + (cwd to v)) }
        }
    }

    fun refresh() {
        if (local.value.refreshing) return
        local.update { it.copy(refreshing = true) }
        viewModelScope.launch {
            val t0 = System.currentTimeMillis()
            val hub = launch { attempt { container.agents.refresh(connectionId) } }
            val expanded = local.value.projectSessions.keys.toList()
            val jobs = listOf(loadProbe(), loadProjects(), loadSessions(), hub)
            expanded.forEach { loadProjectSessions(it, force = true) }
            jobs.joinAll()
            val spent = System.currentTimeMillis() - t0
            if (spent < 650) delay(650 - spent)
            local.update { it.copy(refreshing = false) }
        }
    }

    fun disconnect() {
        if (local.value.disconnecting) return
        local.update { it.copy(disconnecting = true) }
        viewModelScope.launch {
            val name = state.value.connection?.name ?: "machine"
            attempt { container.ssh.disconnect(connectionId) }
                .onSuccess { messages.trySend("Disconnected from $name") }
                .onFailure { messages.trySend("Couldn't disconnect — ${it.humanMessage()}") }
            local.update { it.copy(disconnecting = false) }
        }
    }
}
