package app.tether.remote

import app.tether.core.AgentEvent
import app.tether.core.AgentHub
import app.tether.core.AgentSummary
import app.tether.core.ChatItem
import app.tether.core.ClaudeRemote
import app.tether.core.ConnectionRepository
import app.tether.core.ConversationState
import app.tether.core.ImageAttachment
import app.tether.core.LinkState
import app.tether.core.McpChoice
import app.tether.core.NativeStartResult
import app.tether.core.NativeTimelineEntry
import app.tether.core.PermissionDecision
import app.tether.core.RunKind
import app.tether.core.PermissionState
import app.tether.core.isNative
import app.tether.core.RunInfo
import app.tether.core.RunRef
import app.tether.core.RunStatus
import app.tether.core.SettingsRepository
import app.tether.core.SlashCommand
import app.tether.core.SshManager
import app.tether.core.StartRunRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min

/**
 * The app-facing agent API.
 *
 * - [agents]: merged from one `helper watch` stream per machine, open while anything observes
 *   [agents], a conversation is open, or background watch is on; reconnects with 1→30 s backoff.
 * - [conversation]: per-run hot state — resumed-session history + in.jsonl decisions + out.jsonl
 *   tailed from the last byte offset, reduced by [StreamReducer], emitted at ≤ ~30 fps.
 * - Writes (send / respond / interrupt / mode / model) append stream-json lines to the run's stdin
 *   log and update the local reducer optimistically.
 */
class DefaultAgentHub(
    private val remote: ClaudeRemote,
    private val ssh: SshManager,
    private val connections: ConnectionRepository,
    @Suppress("unused") private val settings: SettingsRepository,
    private val scope: CoroutineScope,
) : AgentHub {

    // The StateFlow instance itself is exposed so its subscriptionCount reflects real observers.
    private val agentsState = MutableStateFlow<List<AgentSummary>>(emptyList())
    override val agents: StateFlow<List<AgentSummary>> = agentsState

    private val errorsState = MutableStateFlow<Map<String, String>>(emptyMap())
    override val machineErrors: StateFlow<Map<String, String>> = errorsState.asStateFlow()

    private val eventsFlow = MutableSharedFlow<AgentEvent>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val events: SharedFlow<AgentEvent> = eventsFlow.asSharedFlow()

    /** Last known runs per connection id. */
    private val snapshots = MutableStateFlow<Map<String, List<RunInfo>>>(emptyMap())
    private val snapshotLock = Any()
    private val notifiedPermissions = HashSet<String>()

    private val backgroundWatch = MutableStateFlow(false)
    private val openConversations = MutableStateFlow(0)
    private val watchJobs = HashMap<String, Job>()
    private val paused = MutableStateFlow(false)

    private val sessions = object : LinkedHashMap<RunRef, ConvSession>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<RunRef, ConvSession>): Boolean =
            size > MAX_CACHED_SESSIONS && eldest.value.subscribers == 0
    }
    private val flows = ConcurrentHashMap<RunRef, Flow<ConversationState>>()

    init {
        val wanted = combine(agentsState.subscriptionCount, backgroundWatch, openConversations, paused) { subs, bg, open, off ->
            !off && (subs > 0 || bg || open > 0)
        }.distinctUntilChanged()
        val ids = connections.connections.map { list -> list.map { it.id }.toSet() }.distinctUntilChanged()
        scope.launch {
            combine(ids, wanted) { set, want -> set to want }.collectLatest { (set, want) ->
                pruneRemovedConnections(set)
                if (want) {
                    syncWatchers(set)
                } else {
                    if (!paused.value) delay(WATCH_LINGER_MS) // brief app switches should not tear the streams down
                    syncWatchers(emptySet())
                }
            }
        }
        // Machine renamed / recoloured → agent cards follow.
        scope.launch { connections.connections.collect { rebuildAgents() } }
    }

    // ═══════════════════════════════════════ machine watch ═══════════════════════════════════════

    private fun syncWatchers(target: Set<String>) {
        synchronized(watchJobs) {
            val it = watchJobs.entries.iterator()
            while (it.hasNext()) {
                val (id, job) = it.next()
                if (id !in target || !job.isActive) {
                    if (id !in target) job.cancel()
                    it.remove()
                }
            }
            for (id in target) {
                if (watchJobs[id] == null) watchJobs[id] = scope.launch { watchLoop(id) }
            }
        }
    }

    private suspend fun watchLoop(connectionId: String) {
        var backoff = 1_000L
        var failures = 0
        while (true) {
            val epoch = epochOf(connectionId)
            try {
                remote.watch(connectionId).collect { runs ->
                    backoff = 1_000L
                    failures = 0
                    clearError(connectionId)
                    applySnapshot(connectionId, runs)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                failures++
                if (!isLinkBlip(e, failures)) setError(connectionId, friendlyMessage(e))
            }
            retryPause(connectionId, epoch, backoff)
            backoff = min(backoff * 2, 30_000L)
        }
    }

    private fun epochOf(connectionId: String): Long = ssh.linkEpochs.value[connectionId] ?: 0L

    /**
     * Pause before re-attaching a failed stream — skipped entirely once the link has been
     * re-established since the stream started (app back from background, network switch).
     */
    private suspend fun retryPause(connectionId: String, startedEpoch: Long, backoff: Long) {
        withTimeoutOrNull(backoff) { ssh.linkEpochs.first { (it[connectionId] ?: 0L) != startedEpoch } }
    }

    /** A dropped link that is being re-established: show "Reconnecting…", not an error card. */
    private fun isLinkBlip(e: Throwable, failures: Int): Boolean = e is java.io.IOException && failures < LINK_BLIP_TOLERANCE

    private fun pruneRemovedConnections(ids: Set<String>) {
        var changed = false
        snapshots.update { m ->
            val kept = m.filterKeys { it in ids }
            if (kept.size != m.size) changed = true
            kept
        }
        errorsState.update { m -> m.filterKeys { it in ids } }
        if (changed) rebuildAgents()
    }

    private fun setError(id: String, message: String) = errorsState.update { it + (id to message) }
    private fun clearError(id: String) = errorsState.update { if (id in it) it - id else it }

    private fun titleFor(run: RunInfo): String = run.title?.takeIf { it.isNotBlank() } ?: projectNameOf(run.cwd)

    /** Replaces a machine's run list, emitting events for transitions since the previous snapshot. */
    private fun applySnapshot(connectionId: String, runs: List<RunInfo>) {
        val out = ArrayList<AgentEvent>()
        synchronized(snapshotLock) {
            val prev = snapshots.value[connectionId]
            val prevById = prev?.associateBy { it.runId }
            for (run in runs) {
                val ref = RunRef(connectionId, run.runId)
                val title = titleFor(run)
                run.pending?.let { p ->
                    if (notifiedPermissions.add("$connectionId/${run.runId}/${p.requestId}")) {
                        out.add(AgentEvent.PermissionRequested(ref, title, p.requestId, p.toolName, p.summary))
                    }
                }
                val before = prevById?.get(run.runId) ?: continue
                if (run.isNative) {
                    // A background agent finishes a stretch of work: working → done / blocked.
                    if (before.status == RunStatus.WORKING && run.alive && run.status == RunStatus.IDLE) {
                        out.add(AgentEvent.TurnCompleted(ref, title, success = true, snippet = run.lastText ?: run.detail))
                    }
                } else if (before.status != RunStatus.IDLE && run.status == RunStatus.IDLE && run.turns > before.turns) {
                    out.add(AgentEvent.TurnCompleted(ref, title, success = run.error == null, snippet = run.lastText))
                }
                if (before.alive && !run.alive && !run.terminal) { // a terminal session was closed at the computer
                    out.add(AgentEvent.Ended(ref, title, error = if (run.status == RunStatus.FAILED) run.error else null))
                }
            }
            snapshots.update { it + (connectionId to runs) }
        }
        rebuildAgents()
        out.forEach { eventsFlow.tryEmit(it) }
    }

    /** Inserts / replaces one run without event diffing (after our own start / respond). */
    private fun mergeRun(connectionId: String, run: RunInfo) {
        synchronized(snapshotLock) {
            snapshots.update { m ->
                val list = m[connectionId].orEmpty()
                val next = if (list.any { it.runId == run.runId }) list.map { if (it.runId == run.runId) run else it } else listOf(run) + list
                m + (connectionId to next)
            }
        }
        rebuildAgents()
    }

    private fun updateRun(ref: RunRef, transform: (RunInfo) -> RunInfo) {
        val cur = snapshots.value[ref.connectionId]?.firstOrNull { it.runId == ref.runId } ?: return
        mergeRun(ref.connectionId, transform(cur))
    }

    private fun rebuildAgents() {
        val byId = connections.connections.value.associateBy { it.id }
        val list = ArrayList<AgentSummary>()
        for ((cid, runs) in snapshots.value) {
            val conn = byId[cid] ?: continue
            for (run in runs) list.add(AgentSummary(RunRef(cid, run.runId), conn, run.copy(title = titleFor(run))))
        }
        list.sortByDescending { it.run.updatedAt }
        agentsState.value = list
    }

    override suspend fun refresh(connectionId: String?) {
        val targets = if (connectionId != null) listOf(connectionId) else connections.connections.value.map { it.id }
        coroutineScope {
            targets.map { id ->
                async {
                    try {
                        applySnapshot(id, remote.listRuns(id))
                        clearError(id)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        setError(id, friendlyMessage(e))
                    }
                }
            }.awaitAll()
        }
    }

    override fun setBackgroundWatch(enabled: Boolean) {
        backgroundWatch.value = enabled
    }

    override fun setPaused(paused: Boolean) {
        this.paused.value = paused
    }

    // ═══════════════════════════════════════ conversations ═══════════════════════════════════════

    private class ConvSession(val ref: RunRef) {
        val lock = Any()
        val reducer = StreamReducer()
        @Volatile var offset = 0L
        @Volatile var initialized = false
        @Volatile var caughtUp = false
        @Volatile var catchUpTarget = 0L
        @Volatile var error: String? = null
        @Volatile var subscribers = 0
        val version = MutableStateFlow(0L)
        fun bump() = version.update { it + 1 }
    }

    private fun sessionFor(ref: RunRef): ConvSession = synchronized(sessions) { sessions.getOrPut(ref) { ConvSession(ref) } }
    private fun existingSession(ref: RunRef): ConvSession? = synchronized(sessions) { sessions[ref] }

    private fun currentRun(ref: RunRef): RunInfo? = snapshots.value[ref.connectionId]?.firstOrNull { it.runId == ref.runId }

    override fun conversation(ref: RunRef): Flow<ConversationState> =
        flows.getOrPut(ref) {
            (if (ref.isNative) buildNativeConversation(ref) else buildConversation(ref))
                .shareIn(scope, SharingStarted.WhileSubscribed(5_000), replay = 1)
        }

    // ─────────────── native background agents: transcript followed live + job state ───────────────

    private class NativeSession(val ref: RunRef) {
        val lock = Any()
        var reducer = StreamReducer()
        @Volatile var loadedOnce = false
        @Volatile var caughtUp = false
        @Volatile var error: String? = null
        @Volatile var workingSince: Long? = null
        val version = MutableStateFlow(0L)
        fun bump() = version.update { it + 1 }
    }

    private val nativeSessions = ConcurrentHashMap<RunRef, NativeSession>()

    private fun buildNativeConversation(ref: RunRef): Flow<ConversationState> = channelFlow {
        val s = nativeSessions.getOrPut(ref) { NativeSession(ref) }
        openConversations.update { it + 1 }
        try {
            if (!s.loadedOnce) send(loadingState(ref, currentRun(ref)).copy(kind = RunKind.NATIVE, nativeRun = currentRun(ref)))
            launch(Dispatchers.Default) { pumpNative(s) }
            launch {
                delay(CATCH_UP_TIMEOUT_MS)
                if (!s.caughtUp) {
                    s.caughtUp = true
                    s.bump()
                }
            }
            val runFlow = snapshots.map { it[ref.connectionId]?.firstOrNull { r -> r.runId == ref.runId } to it.containsKey(ref.connectionId) }
                .distinctUntilChanged()
            val linkFlow = ssh.states.map { it[ref.connectionId] ?: LinkState.Idle }.distinctUntilChanged()
            combine(s.version, runFlow, linkFlow) { _, run, link -> Triple(run.first, run.second, link) }
                .conflate()
                .collect { (run, machineKnown, link) ->
                    send(buildNativeState(s, run, machineKnown, link))
                    delay(if (s.caughtUp) FRAME_MS else CATCH_UP_FRAME_MS)
                }
        } finally {
            openConversations.update { it - 1 }
        }
    }

    /**
     * Follows the agent's transcript. The first load renders progressively; a re-attach rebuilds
     * the history off-screen and swaps it in at the caught-up marker (no flash of a half list).
     */
    private suspend fun pumpNative(s: NativeSession) {
        var backoff = 1_000L
        var failures = 0
        while (true) {
            val epoch = epochOf(s.ref.connectionId)
            try {
                val fresh = if (s.loadedOnce) StreamReducer() else null
                var catching = true
                remote.followNative(s.ref).collect { line ->
                    when {
                        NativeAgents.isHeartbeat(line) -> Unit
                        NativeAgents.isCaughtUpMarker(line) -> {
                            synchronized(s.lock) { if (fresh != null) s.reducer = fresh }
                            catching = false
                            s.loadedOnce = true
                            s.caughtUp = true
                            s.error = null
                            backoff = 1_000L
                            failures = 0
                            s.bump()
                        }
                        catching && fresh != null -> fresh.acceptTranscript(line)
                        else -> {
                            val changed = synchronized(s.lock) { s.reducer.acceptTranscript(line) }
                            if (changed) s.bump()
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                failures++
                if (!isLinkBlip(e, failures)) s.error = friendlyMessage(e)
                s.bump()
            }
            retryPause(s.ref.connectionId, epoch, backoff)
            backoff = min(backoff * 2, 30_000L)
        }
    }

    private fun buildNativeState(s: NativeSession, run: RunInfo?, machineKnown: Boolean, link: LinkState): ConversationState {
        val base = synchronized(s.lock) { s.reducer.snapshot() }
        var error = s.error
        val status = when {
            run != null -> run.status
            machineKnown && s.caughtUp -> {
                error = error ?: "This background agent no longer exists on the machine."
                RunStatus.ENDED
            }
            else -> RunStatus.STARTING
        }
        if (status == RunStatus.WORKING) {
            if (s.workingSince == null) s.workingSince = run?.let { workingSinceOf(it) } ?: System.currentTimeMillis()
        } else {
            s.workingSince = null
        }
        val cwd = run?.cwd ?: base.cwd
        return base.copy(
            ref = s.ref,
            status = status,
            kind = RunKind.NATIVE,
            nativeRun = run,
            title = run?.title?.takeIf { it.isNotBlank() } ?: base.title ?: cwd?.let { projectNameOf(it) },
            cwd = cwd,
            sessionId = run?.sessionId ?: base.sessionId,
            model = base.model ?: run?.model,
            permissionMode = run?.permissionMode ?: base.permissionMode,
            pendingPermissions = run?.pending?.takeIf { status == RunStatus.AWAITING_PERMISSION }?.let { p ->
                listOf(
                    ChatItem.Permission(
                        key = "perm-" + p.requestId,
                        requestId = p.requestId,
                        toolName = p.toolName,
                        toolUseId = p.requestId.removePrefix(NativeAgents.PERMISSION_PREFIX),
                        inputJson = p.inputJson ?: "{}",
                        description = null,
                        blockedPath = null,
                        suggestions = emptyList(),
                        state = PermissionState.PENDING,
                    )
                )
            } ?: emptyList(),
            commands = emptyList(),
            link = link,
            loadingHistory = !s.caughtUp,
            error = error,
            workingSince = s.workingSince,
            thinkingTokens = null,
        )
    }

    /** Start of the current working stretch, from the timeline tail (the dashboard keeps the last few). */
    private fun workingSinceOf(run: RunInfo): Long? {
        var since: Long? = null
        for (e in run.timeline.asReversed()) {
            if (e.state != "working") break
            if (e.at > 0) since = e.at
        }
        return since
    }

    private fun buildConversation(ref: RunRef): Flow<ConversationState> = channelFlow {
        val s = sessionFor(ref)
        s.subscribers++
        openConversations.update { it + 1 }
        try {
            // Something on screen immediately (title/cwd from the dashboard), then the real thing.
            if (!s.initialized) send(loadingState(ref, currentRun(ref)))
            launch(Dispatchers.Default) { pump(s) }
            launch {
                delay(CATCH_UP_TIMEOUT_MS)
                if (!s.caughtUp) {
                    s.caughtUp = true
                    s.bump()
                }
            }
            val runFlow = snapshots.map { it[ref.connectionId]?.firstOrNull { r -> r.runId == ref.runId } to it.containsKey(ref.connectionId) }
                .distinctUntilChanged()
            val linkFlow = ssh.states.map { it[ref.connectionId] ?: LinkState.Idle }.distinctUntilChanged()
            combine(s.version, runFlow, linkFlow) { _, run, link -> Triple(run.first, run.second, link) }
                .conflate()
                .collect { (run, machineKnown, link) ->
                    send(buildState(s, run, machineKnown, link))
                    delay(if (s.caughtUp) FRAME_MS else CATCH_UP_FRAME_MS)
                }
        } finally {
            s.subscribers--
            openConversations.update { it - 1 }
        }
    }

    private fun loadingState(ref: RunRef, run: RunInfo?) = ConversationState(
        ref = ref,
        status = run?.status ?: RunStatus.STARTING,
        title = run?.let { titleFor(it) },
        cwd = run?.cwd,
        sessionId = run?.sessionId,
        model = run?.model,
        permissionMode = run?.permissionMode,
        totalCostUsd = run?.costUsd ?: 0.0,
        link = ssh.states.value[ref.connectionId] ?: LinkState.Idle,
        loadingHistory = true,
    )

    /** History + stdin decisions once, then tail out.jsonl forever (reconnecting from the last offset). */
    private suspend fun pump(s: ConvSession) {
        val ref = s.ref
        var backoff = 1_000L
        while (!s.initialized) {
            try {
                val history = (remote as? RunHistorySource)?.loadRunHistory(ref).orEmpty()
                val input = remote.readInput(ref)
                synchronized(s.lock) {
                    if (!s.initialized) {
                        for (line in history) s.reducer.acceptTranscript(line)
                        if (history.isNotEmpty()) {
                            val run = currentRun(ref)
                            val forked = run?.forked == true || run?.title?.startsWith("\u21b3") == true
                            s.reducer.addHistoryBoundary(if (forked) "\u21b3 Branched here" else "Resumed session")
                        }
                        s.reducer.applyInput(input, historical = true)
                        s.initialized = true
                    }
                }
                s.error = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (e !is java.io.IOException) s.error = friendlyMessage(e)
                s.bump()
                retryPause(ref.connectionId, epochOf(ref.connectionId), backoff)
                backoff = min(backoff * 2, 30_000L)
            }
        }
        // How much output exists right now = how far to read before the history counts as loaded.
        val run = currentRun(ref) ?: try {
            remote.listRuns(ref.connectionId).also { applySnapshot(ref.connectionId, it) }.firstOrNull { it.runId == ref.runId }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            null
        }
        s.catchUpTarget = run?.outBytes ?: 0L
        if (s.offset >= s.catchUpTarget) s.caughtUp = true
        s.bump()

        backoff = 1_000L
        var failures = 0
        while (true) {
            val epoch = epochOf(ref.connectionId)
            try {
                coroutineScope {
                  // An idle run emits nothing, so a successful re-attach would otherwise never clear
                  // a previous "Connection lost": clear it once the tail has stayed up for a moment.
                  val clearer = launch {
                      delay(TAIL_HEALTHY_MS)
                      if (s.error != null) { s.error = null; s.bump() }
                  }
                  try {
                remote.tail(ref, s.offset).collect { tl ->
                    synchronized(s.lock) {
                        s.reducer.accept(tl.line)
                        s.offset = tl.endOffset
                    }
                    if (!s.caughtUp && s.offset >= s.catchUpTarget) s.caughtUp = true
                    if (s.error != null) s.error = null
                    backoff = 1_000L
                    failures = 0
                    s.bump()
                }
                  } finally {
                      clearer.cancel()
                  }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                failures++
                if (!isLinkBlip(e, failures)) s.error = friendlyMessage(e)
                s.bump()
            }
            retryPause(ref.connectionId, epoch, backoff)
            backoff = min(backoff * 2, 30_000L)
        }
    }

    private fun buildState(s: ConvSession, run: RunInfo?, machineKnown: Boolean, link: LinkState): ConversationState {
        val base = synchronized(s.lock) { s.reducer.snapshot() }
        var status = base.status
        var error = s.error
        if (run != null) {
            if (!run.alive) {
                status = if (run.status == RunStatus.FAILED) RunStatus.FAILED else RunStatus.ENDED
                if (status == RunStatus.FAILED) error = error ?: run.error
            } else if (!s.caughtUp || (status == RunStatus.STARTING && run.status != RunStatus.STARTING)) {
                status = run.status
            }
        } else if (machineKnown && s.initialized) {
            // The machine answered and this run is not among its runs: it was removed.
            status = RunStatus.ENDED
            error = error ?: "This agent no longer exists on the machine."
        }
        val live = status == RunStatus.WORKING || status == RunStatus.AWAITING_PERMISSION
        val cwd = base.cwd ?: run?.cwd
        return base.copy(
            ref = s.ref,
            status = status,
            title = run?.title?.takeIf { it.isNotBlank() } ?: base.title ?: cwd?.let { projectNameOf(it) },
            cwd = cwd,
            sessionId = base.sessionId ?: run?.sessionId,
            model = base.model ?: run?.model,
            permissionMode = base.permissionMode ?: run?.permissionMode,
            totalCostUsd = if (base.totalCostUsd > 0.0) base.totalCostUsd else run?.costUsd ?: 0.0,
            pendingPermissions = if (status == RunStatus.ENDED || status == RunStatus.FAILED) emptyList() else base.pendingPermissions,
            link = link,
            loadingHistory = !s.initialized || !s.caughtUp,
            error = error,
            workingSince = if (live) base.workingSince ?: run?.updatedAt else null,
            thinkingTokens = if (live) base.thinkingTokens else null,
        )
    }

    override suspend fun transcript(connectionId: String, sessionId: String): ConversationState {
        val lines = remote.loadTranscript(connectionId, sessionId)
        return withContext(Dispatchers.Default) {
            val r = StreamReducer()
            for (l in lines) r.acceptTranscript(l)
            val snap = r.snapshot()
            snap.copy(
                status = RunStatus.ENDED,
                loadingHistory = false,
                title = snap.title ?: snap.items.firstNotNullOfOrNull { (it as? ChatItem.User)?.text?.lineSequence()?.firstOrNull()?.take(80) }
                    ?: snap.cwd?.let { projectNameOf(it) } ?: "Session",
                sessionId = snap.sessionId ?: sessionId,
                pendingPermissions = emptyList(),
                workingSince = null,
                thinkingTokens = null,
                link = ssh.states.value[connectionId] ?: LinkState.Idle,
            )
        }
    }

    // ═══════════════════════════════════════ actions ═══════════════════════════════════════

    override suspend fun start(connectionId: String, request: StartRunRequest, images: List<ImageAttachment>): RunRef {
        val withImages = images.isNotEmpty()
        val req = if (withImages) request.copy(prompt = null) else request
        val info = remote.startRun(connectionId, req)
        val ref = RunRef(connectionId, info.runId)
        mergeRun(connectionId, info.copy(status = if (!request.prompt.isNullOrBlank() || withImages) RunStatus.WORKING else info.status))
        if (info.status == RunStatus.FAILED) {
            throw RemoteException(info.error ?: "Claude Code exited right after starting.")
        }
        // Local commands (/cost…) are never echoed back, so a slash prompt would otherwise not show at all.
        val slashPrompt = request.prompt?.trim()?.takeIf { !withImages && it.startsWith("/") }
        if (slashPrompt != null) {
            val s = sessionFor(ref)
            synchronized(s.lock) { s.reducer.addOptimisticUser(slashPrompt, 0, queued = false) }
            s.bump()
        }
        if (withImages) send(ref, request.prompt.orEmpty(), images)
        scope.launch {
            delay(1_500)
            try {
                refresh(connectionId)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
            }
        }
        return ref
    }

    override suspend fun branch(
        connectionId: String,
        sessionId: String?,
        cwd: String,
        atUuid: String?,
        prompt: String?,
        title: String?,
        restoreBefore: String?,
        sourceRunId: String?,
    ): RunRef {
        val live = sourceRunId?.takeIf { !it.startsWith(app.tether.core.NATIVE_RUN_PREFIX) }
        if (restoreBefore != null && sessionId != null) {
            val r = remote.rewindFiles(connectionId, sessionId, restoreBefore, cwd, dryRun = false, runId = live)
            if (!r.canRewind || r.error != null) throw RemoteException(r.error ?: "These files can't be restored — no checkpoint for that message.")
        }
        val source = sourceRunId?.let { currentRun(RunRef(connectionId, it)) }
        val s = settings.settings.value
        val mode = (source?.permissionMode ?: s.defaultPermissionMode).takeIf { it != app.tether.core.PermissionMode.BYPASS.cli }
            ?: app.tether.core.PermissionMode.DEFAULT.cli
        val model = source?.model?.takeIf { !source.isNative } ?: s.defaultModel.takeIf { it != "default" && it.isNotBlank() }
        val req = StartRunRequest(
            cwd = cwd,
            prompt = prompt?.takeIf { it.isNotBlank() },
            model = model,
            permissionMode = mode,
            resumeSessionId = sessionId,
            forkSession = sessionId != null,
            resumeAt = if (sessionId != null) atUuid else null,
            title = title?.let { "\u21b3 " + it.removePrefix("\u21b3 ").take(80) },
        )
        return start(connectionId, req)
    }

    /** Per machine + folder; the helper caches too, this only saves the SSH round trip. */
    private val commandCache = ConcurrentHashMap<Pair<String, String>, List<SlashCommand>>()

    override suspend fun slashCommands(connectionId: String, cwd: String): List<SlashCommand> {
        val key = connectionId to cwd
        commandCache[key]?.let { return it }
        return remote.listCommands(connectionId, cwd).also { if (it.isNotEmpty()) commandCache[key] = it }
    }

    override suspend fun previewRewind(connectionId: String, sessionId: String, messageId: String, cwd: String, sourceRunId: String?): app.tether.core.RewindResult =
        remote.rewindFiles(connectionId, sessionId, messageId, cwd, dryRun = true, runId = sourceRunId?.takeIf { !it.startsWith(app.tether.core.NATIVE_RUN_PREFIX) })

    override suspend fun startNative(connectionId: String, request: StartRunRequest, trustFolder: Boolean, mcp: McpChoice?): NativeStartResult {
        val res = remote.startNative(connectionId, request, trustFolder, mcp)
        if (res is NativeStartResult.Started) {
            try {
                refresh(connectionId)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
            }
        }
        return res
    }

    override suspend fun continueNative(ref: RunRef, text: String): RunRef {
        val msg = text.trim()
        if (msg.isEmpty()) return ref
        val s = nativeSessions[ref]
        val before = currentRun(ref)
        // A terminal session is not touched: the reply starts a background copy, which the screen then follows.
        val terminal = before?.terminal == true
        val busy = before?.alive == true && before.status == RunStatus.WORKING
        val key = if (terminal) null else s?.let { synchronized(it.lock) { it.reducer.addOptimisticUser(msg, 0, queued = busy) } }
        s?.bump()
        if (!busy && !terminal) updateRun(ref) { it.copy(status = RunStatus.WORKING, nativeState = "working", nativeStatus = "busy", detail = if (it.alive) "Reading your message…" else "Continuing…", updatedAt = System.currentTimeMillis()) }
        val info = try {
            remote.replyNative(ref, msg)
        } catch (e: Throwable) {
            if (s != null && key != null) {
                synchronized(s.lock) { s.reducer.removeOptimistic(key) }
                s.bump()
            }
            try {
                refresh(ref.connectionId)
            } catch (c: CancellationException) {
                throw c
            } catch (_: Throwable) {
            }
            throw e
        }
        val next = RunRef(ref.connectionId, info.runId)
        mergeRun(ref.connectionId, if (info.status == RunStatus.IDLE) info.copy(status = RunStatus.WORKING, nativeState = "working", nativeStatus = "busy") else info)
        return next
    }

    override suspend fun nativeTimeline(ref: RunRef): List<NativeTimelineEntry> = remote.nativeTimeline(ref)

    override suspend fun nativeLogs(ref: RunRef): String = remote.nativeLogs(ref)

    override suspend fun send(ref: RunRef, text: String, images: List<ImageAttachment>) {
        if (text.isBlank() && images.isEmpty()) return
        if (ref.isNative) {
            val next = continueNative(ref, text)
            if (next != ref) throw RemoteException("Claude continued this conversation as a new background agent.")
            return
        }
        val content = buildJsonArray {
            for (img in images) {
                add(buildJsonObject {
                    put("type", "image")
                    putJsonObject("source") {
                        put("type", "base64")
                        put("media_type", img.mimeType.ifBlank { "image/jpeg" })
                        put("data", Base64.getEncoder().encodeToString(img.bytes))
                    }
                })
            }
            if (text.isNotBlank()) add(buildJsonObject { put("type", "text"); put("text", text) })
        }
        val line = buildJsonObject {
            put("type", "user")
            putJsonObject("message") {
                put("role", "user")
                put("content", content)
            }
        }.toString()
        val s = existingSession(ref)
        val busy = when (s?.let { synchronized(it.lock) { it.reducer.currentStatus } } ?: currentRun(ref)?.status) {
            RunStatus.WORKING, RunStatus.AWAITING_PERMISSION, RunStatus.STARTING -> true
            else -> false
        }
        val key = s?.let {
            synchronized(it.lock) { it.reducer.addOptimisticUser(text.trim(), images.size, queued = busy && !text.trimStart().startsWith("/")) }
        }
        s?.bump()
        try {
            remote.writeInput(ref, listOf(line))
        } catch (e: Throwable) {
            if (s != null && key != null) {
                synchronized(s.lock) { s.reducer.removeOptimistic(key) }
                s.bump()
            }
            throw e
        }
        updateRun(ref) { if (it.alive && it.status == RunStatus.IDLE) it.copy(status = RunStatus.WORKING, updatedAt = System.currentTimeMillis()) else it }
    }

    override suspend fun respond(ref: RunRef, requestId: String, decision: PermissionDecision) {
        if (ref.isNative) return respondNative(ref, decision)
        val response: JsonObject = when (decision) {
            is PermissionDecision.Allow -> {
                val input: JsonElement = RemoteJson.parseElement(decision.updatedInputJson)
                    ?: existingSession(ref)?.let { s -> synchronized(s.lock) { s.reducer.permissionInput(requestId) } }?.let { RemoteJson.parseElement(it) }
                    ?: pendingInput(ref, requestId)
                    ?: JsonObject(emptyMap())
                val rules = decision.alwaysAllow.mapNotNull { RemoteJson.parseElement(it) as? JsonObject }
                buildJsonObject {
                    put("behavior", "allow")
                    put("updatedInput", input)
                    if (rules.isNotEmpty()) put("updatedPermissions", JsonArray(rules))
                }
            }
            is PermissionDecision.Answer -> {
                val original = existingSession(ref)?.let { s -> synchronized(s.lock) { s.reducer.permissionInput(requestId) } }
                    ?: pendingInput(ref, requestId)?.toString()
                buildJsonObject {
                    put("behavior", "allow")
                    put("updatedInput", RemoteJson.parseElement(app.tether.core.AskQuestions.updatedInput(original, decision.answers)) ?: JsonObject(emptyMap()))
                }
            }
            is PermissionDecision.Deny -> buildJsonObject {
                put("behavior", "deny")
                put("message", decision.message.ifBlank { "The user denied this action." })
                put("interrupt", decision.interrupt)
            }
        }
        val line = buildJsonObject {
            put("type", "control_response")
            putJsonObject("response") {
                put("subtype", "success")
                put("request_id", requestId)
                put("response", response)
            }
        }.toString()
        remote.writeInput(ref, listOf(line))
        existingSession(ref)?.let { s ->
            synchronized(s.lock) { s.reducer.applyInput(listOf(line)) }
            s.bump()
        }
        updateRun(ref) { r ->
            if (r.pending?.requestId == requestId) r.copy(pending = null, status = RunStatus.WORKING, updatedAt = System.currentTimeMillis()) else r
        }
    }

    /** The pending request's input from the dashboard snapshot, or fetched fresh (notification path). */
    private suspend fun pendingInput(ref: RunRef, requestId: String): JsonElement? {
        currentRun(ref)?.pending?.takeIf { it.requestId == requestId }?.inputJson?.let { return RemoteJson.parseElement(it) }
        val runs = try {
            remote.listRuns(ref.connectionId)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            return null
        }
        applySnapshot(ref.connectionId, runs)
        val p = runs.firstOrNull { it.runId == ref.runId }?.pending ?: throw RemoteException("This request was already answered.")
        if (p.requestId != requestId) throw RemoteException("This request was already answered.")
        return RemoteJson.parseElement(p.inputJson)
    }

    private suspend fun control(ref: RunRef, prefix: String, request: JsonObject) {
        val line = buildJsonObject {
            put("type", "control_request")
            put("request_id", "${prefix}_${UUID.randomUUID().toString().take(8)}")
            put("request", request)
        }.toString()
        remote.writeInput(ref, listOf(line))
        existingSession(ref)?.let { s ->
            synchronized(s.lock) { s.reducer.applyInput(listOf(line)) }
            s.bump()
        }
    }

    /** Native approval: typed into the agent's own prompt ("1" / Esc); feedback follows as a message. */
    private suspend fun respondNative(ref: RunRef, decision: PermissionDecision) {
        if (decision is PermissionDecision.Answer) {
            updateRun(ref) { it.copy(status = RunStatus.WORKING, nativeStatus = "busy", pending = null, updatedAt = System.currentTimeMillis()) }
            nativeSessions[ref]?.bump()
            val info = try {
                remote.askNative(ref, decision.answers)
            } catch (e: Throwable) {
                try { refresh(ref.connectionId) } catch (c: CancellationException) { throw c } catch (_: Throwable) {}
                throw e
            }
            mergeRun(ref.connectionId, info)
            return
        }
        val allow = decision is PermissionDecision.Allow
        updateRun(ref) { it.copy(status = RunStatus.WORKING, nativeStatus = "busy", pending = null, updatedAt = System.currentTimeMillis()) }
        nativeSessions[ref]?.bump()
        val info = try {
            remote.answerNative(ref, allow)
        } catch (e: Throwable) {
            try { refresh(ref.connectionId) } catch (c: CancellationException) { throw c } catch (_: Throwable) {}
            throw e
        }
        mergeRun(ref.connectionId, info)
        val feedback = (decision as? PermissionDecision.Deny)?.message?.trim()
        if (!feedback.isNullOrEmpty() && feedback != PermissionDecision.Deny().message) continueNative(ref, feedback)
    }

    override suspend fun nativeQuestion(ref: RunRef): String {
        val input = remote.nativeQuestion(ref)
        // Patch the fetched question into the pending prompt so every screen shows it.
        updateRun(ref) { r -> r.pending?.let { p -> r.copy(pending = p.copy(inputJson = input)) } ?: r }
        nativeSessions[ref]?.bump()
        return input
    }

    override suspend fun interrupt(ref: RunRef) {
        if (ref.isNative) {
            mergeRun(ref.connectionId, remote.interruptNative(ref))
            return
        }
        control(ref, "int", buildJsonObject { put("subtype", "interrupt") })
    }

    override suspend fun setPermissionMode(ref: RunRef, mode: String) {
        if (ref.isNative) throw RemoteException("A background agent takes its approvals and settings on the computer.")
        control(ref, "mode", buildJsonObject { put("subtype", "set_permission_mode"); put("mode", mode) })
        updateRun(ref) { it.copy(permissionMode = mode) }
    }

    override suspend fun setModel(ref: RunRef, model: String) {
        if (ref.isNative) throw RemoteException("A background agent takes its approvals and settings on the computer.")
        control(ref, "model", buildJsonObject { put("subtype", "set_model"); put("model", model) })
    }

    override suspend fun stop(ref: RunRef) {
        remote.stopRun(ref)
        // No optimistic alive=false here: the refreshed snapshot must see the alive→dead transition
        // so the Ended event fires for every observer.
        try {
            refresh(ref.connectionId)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            updateRun(ref) { it.copy(alive = false, status = RunStatus.ENDED, pending = null) }
        }
    }

    override suspend fun remove(ref: RunRef) {
        remote.deleteRun(ref)
        synchronized(snapshotLock) {
            snapshots.update { m ->
                val list = m[ref.connectionId] ?: return@update m
                m + (ref.connectionId to list.filterNot { it.runId == ref.runId })
            }
        }
        synchronized(sessions) {
            if (sessions[ref]?.subscribers == 0) sessions.remove(ref)
        }
        if (ref.isNative) nativeSessions.remove(ref)
        rebuildAgents()
    }

    private companion object {
        const val FRAME_MS = 33L
        const val CATCH_UP_FRAME_MS = 200L
        const val TAIL_HEALTHY_MS = 3_000L
        /** Consecutive link failures shown as "Reconnecting…" before an error card appears. */
        private const val LINK_BLIP_TOLERANCE = 3
        const val CATCH_UP_TIMEOUT_MS = 6_000L
        const val WATCH_LINGER_MS = 5_000L
        const val MAX_CACHED_SESSIONS = 12
    }
}
