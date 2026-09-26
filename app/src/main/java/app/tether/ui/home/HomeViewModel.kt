package app.tether.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tether.AppContainer
import app.tether.core.AgentSummary
import app.tether.core.Connection
import app.tether.core.LinkState
import app.tether.core.PermissionDecision
import app.tether.core.ProjectSummary
import app.tether.core.RunRef
import app.tether.core.RunStatus
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
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
import kotlinx.coroutines.launch

/** One decision the user made from a Home card, before the hub reports the agent moving on. */
data class QuickDecision(val allow: Boolean, val settled: Boolean)

data class QuickStart(val connection: Connection, val projects: Loadable<List<ProjectSummary>>)

data class HomeUiState(
    val connections: List<Connection> = emptyList(),
    val links: Map<String, LinkState> = emptyMap(),
    val machineErrors: Map<String, String> = emptyMap(),
    val needsYou: List<AgentSummary> = emptyList(),
    val working: List<AgentSummary> = emptyList(),
    val recent: List<AgentSummary> = emptyList(),
    /** Unsettled optimistic decisions, keyed by [HomeViewModel.decisionKey]. */
    val decisions: Map<String, Boolean> = emptyMap(),
    /** Agents whose card was optimistically answered and now reads as working. */
    val answeredKeys: Set<String> = emptySet(),
    val liveCountByMachine: Map<String, Int> = emptyMap(),
    val showSkeleton: Boolean = false,
    val refreshing: Boolean = false,
    val retrying: Set<String> = emptySet(),
    val quickStart: QuickStart? = null,
) {
    val hasAgents get() = needsYou.isNotEmpty() || working.isNotEmpty() || recent.isNotEmpty()
}

sealed interface HomeMessage {
    data class Removed(val ref: RunRef, val title: String) : HomeMessage
    data class Error(val text: String) : HomeMessage
}

private data class Local(
    val decisions: Map<String, QuickDecision> = emptyMap(),
    val hidden: Set<String> = emptySet(),
    val refreshing: Boolean = false,
    val retrying: Set<String> = emptySet(),
    /** 0 = first 2.5 s, 1 = grace while a machine is still connecting, 2 = settled. */
    val loadPhase: Int = 0,
    val firstRefreshDone: Boolean = false,
    val quickStart: QuickStart? = null,
)

class HomeViewModel(private val container: AppContainer) : ViewModel() {
    private val hub = container.agents
    private val local = MutableStateFlow(Local())
    private val messages = Channel<HomeMessage>(Channel.BUFFERED)
    val events: Flow<HomeMessage> = messages.receiveAsFlow()

    private val pendingRemovals = mutableMapOf<String, Pair<RunRef, Job>>()
    private var quickStartJob: Job? = null

    val state: StateFlow<HomeUiState> = combine(
        container.connections.connections,
        container.ssh.states,
        hub.agents,
        hub.machineErrors,
        local,
    ) { conns, links, agents, errors, l -> reduce(conns, links, agents, errors, l) }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            // Seed from current values so the first frame is already the right state (no empty-state flash).
            reduce(container.connections.connections.value, container.ssh.states.value, hub.agents.value, hub.machineErrors.value, local.value),
        )

    init {
        viewModelScope.launch {
            attempt { hub.refresh() }
            local.update { it.copy(firstRefreshDone = true) }
        }
        viewModelScope.launch {
            delay(2_500)
            local.update { it.copy(loadPhase = 1) }
            delay(5_500)
            local.update { it.copy(loadPhase = 2) }
        }
    }

    private fun reduce(
        conns: List<Connection>,
        links: Map<String, LinkState>,
        agents: List<AgentSummary>,
        errors: Map<String, String>,
        l: Local,
    ): HomeUiState {
        val known = conns.map { it.id }.toSet()
        val visible = agents.filter { agentKey(it.ref.connectionId, it.ref.runId) !in l.hidden && it.ref.connectionId in known }
        val answered = mutableSetOf<String>()
        val unsettled = mutableMapOf<String, Boolean>()
        val needs = mutableListOf<AgentSummary>()
        val working = mutableListOf<AgentSummary>()
        val recent = mutableListOf<AgentSummary>()
        for (a in visible.sortedForDisplay()) {
            val p = a.run.pending
            when (a.run.status) {
                RunStatus.AWAITING_PERMISSION -> {
                    val d = p?.let { l.decisions[decisionKey(a.ref, it.requestId)] }
                    if (d != null && d.settled) {
                        answered += agentKey(a.ref.connectionId, a.ref.runId)
                        working += a
                    } else {
                        if (d != null) unsettled[decisionKey(a.ref, p.requestId)] = d.allow
                        needs += a
                    }
                }
                RunStatus.STARTING, RunStatus.WORKING -> working += a
                RunStatus.IDLE, RunStatus.ENDED, RunStatus.FAILED -> recent += a
            }
        }
        val liveCounts = visible.filter { it.run.status == RunStatus.STARTING || it.run.status == RunStatus.WORKING || it.run.status == RunStatus.AWAITING_PERMISSION }
            .groupingBy { it.ref.connectionId }.eachCount()
        val anyConnecting = conns.any { links[it.id] == LinkState.Connecting }
        val stillLoading = when (l.loadPhase) {
            0 -> true
            1 -> anyConnecting && !l.firstRefreshDone
            else -> false
        }
        val relevantErrors = errors.filterKeys { it in known }
        val skeleton = visible.isEmpty() && conns.isNotEmpty() && relevantErrors.isEmpty() && stillLoading
        return HomeUiState(
            connections = conns,
            links = links,
            machineErrors = relevantErrors,
            needsYou = needs,
            working = working,
            recent = recent,
            decisions = unsettled,
            answeredKeys = answered,
            liveCountByMachine = liveCounts,
            showSkeleton = skeleton,
            refreshing = l.refreshing,
            retrying = l.retrying,
            quickStart = l.quickStart?.takeIf { qs -> conns.any { it.id == qs.connection.id } },
        )
    }

    fun decisionKey(ref: RunRef, requestId: String) = "${ref.connectionId}/${ref.runId}/$requestId"

    fun respond(agent: AgentSummary, allow: Boolean) {
        val pending = agent.run.pending ?: return
        val key = decisionKey(agent.ref, pending.requestId)
        if (local.value.decisions.containsKey(key)) return
        local.update { it.copy(decisions = it.decisions + (key to QuickDecision(allow, settled = false))) }
        viewModelScope.launch {
            val minDelay = async { delay(750) }
            val decision = if (allow) PermissionDecision.Allow() else PermissionDecision.Deny()
            val result = attempt { hub.respond(agent.ref, pending.requestId, decision) }
            minDelay.await()
            result.fold(
                onSuccess = {
                    local.update { l -> l.copy(decisions = l.decisions + (key to QuickDecision(allow, settled = true))) }
                    // Forget the decision once the hub has certainly caught up, so a later identical id can't collide.
                    delay(60_000)
                    local.update { l -> l.copy(decisions = l.decisions - key) }
                },
                onFailure = { e ->
                    local.update { l -> l.copy(decisions = l.decisions - key) }
                    messages.trySend(HomeMessage.Error("Couldn't send your answer — ${e.humanMessage()}"))
                },
            )
        }
    }

    fun refresh() {
        if (local.value.refreshing) return
        local.update { it.copy(refreshing = true) }
        viewModelScope.launch {
            val t0 = System.currentTimeMillis()
            val r = attempt { hub.refresh() }
            val spent = System.currentTimeMillis() - t0
            if (spent < 650) delay(650 - spent)
            local.update { it.copy(refreshing = false, firstRefreshDone = true) }
            r.exceptionOrNull()?.let { messages.trySend(HomeMessage.Error("Refresh failed — ${it.humanMessage()}")) }
            local.value.quickStart?.let { qs -> if (qs.projects is Loadable.Failed) loadQuickStart(qs.connection, force = true) }
        }
    }

    fun retryMachine(connectionId: String) {
        if (connectionId in local.value.retrying) return
        local.update { it.copy(retrying = it.retrying + connectionId) }
        viewModelScope.launch {
            attempt { hub.refresh(connectionId) }
            local.update { it.copy(retrying = it.retrying - connectionId) }
        }
    }

    /** Swipe-to-dismiss on an ended agent: hide now, delete after the Undo window. */
    fun dismiss(agent: AgentSummary) {
        val key = agentKey(agent.ref.connectionId, agent.ref.runId)
        if (key in local.value.hidden) return
        local.update { it.copy(hidden = it.hidden + key) }
        messages.trySend(HomeMessage.Removed(agent.ref, agent.run.displayTitle()))
        val job = viewModelScope.launch {
            delay(4_600)
            pendingRemovals.remove(key)
            commitRemove(agent.ref, key)
        }
        pendingRemovals[key] = agent.ref to job
    }

    fun undoDismiss(ref: RunRef) {
        val key = agentKey(ref.connectionId, ref.runId)
        pendingRemovals.remove(key)?.second?.cancel()
        local.update { it.copy(hidden = it.hidden - key) }
    }

    private suspend fun commitRemove(ref: RunRef, key: String) {
        attempt { hub.remove(ref) }.onFailure { e ->
            local.update { it.copy(hidden = it.hidden - key) }
            messages.trySend(HomeMessage.Error("Couldn't remove the agent — ${e.humanMessage()}"))
        }
    }

    /** Pick the machine for the "start your first agent" quick-start list (sticky once chosen). */
    fun ensureQuickStart(s: HomeUiState) {
        val current = local.value.quickStart
        if (current != null && current.projects !is Loadable.Failed) return
        if (quickStartJob?.isActive == true) return
        val target = s.connections.firstOrNull { s.links[it.id] is LinkState.Connected }
            ?: s.connections.firstOrNull { it.id !in s.machineErrors && s.links[it.id] !is LinkState.Failed }
            ?: s.connections.firstOrNull()
            ?: return
        // A failed machine is only retried by the user; auto-switch only to a different one.
        if (current != null && current.connection.id == target.id) return
        loadQuickStart(target, force = true)
    }

    fun retryQuickStart() {
        local.value.quickStart?.let { loadQuickStart(it.connection, force = true) }
    }

    private fun loadQuickStart(conn: Connection, force: Boolean) {
        if (!force && local.value.quickStart?.connection?.id == conn.id) return
        quickStartJob?.cancel()
        local.update { it.copy(quickStart = QuickStart(conn, Loadable.Loading)) }
        quickStartJob = viewModelScope.launch {
            val r = attempt { container.remote.listProjects(conn.id) }
            val value: Loadable<List<ProjectSummary>> = r.fold(
                onSuccess = { list -> Loadable.Ready(list.filter { it.exists }.sortedByDescending { it.lastActiveAt }.take(4)) },
                onFailure = { Loadable.Failed(it.humanMessage()) },
            )
            local.update { it.copy(quickStart = QuickStart(conn, value)) }
        }
    }

    override fun onCleared() {
        // Leaving Home inside the Undo window still honours the swipe.
        val refs = pendingRemovals.values.map { it.first }
        pendingRemovals.values.forEach { it.second.cancel() }
        pendingRemovals.clear()
        refs.forEach { ref -> container.scope.launch { attempt { hub.remove(ref) } } }
        super.onCleared()
    }
}
