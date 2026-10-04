package app.tether.remote

import app.tether.core.AskAnswer
import app.tether.core.ConnectionRepository
import app.tether.core.ConversationState
import app.tether.core.FollowEvent
import app.tether.core.ImageAttachment
import app.tether.core.LinkState
import app.tether.core.NewSessionRequest
import app.tether.core.NewSessionResult
import app.tether.core.Session
import app.tether.core.SessionDecision
import app.tether.core.SessionErrorCodes
import app.tether.core.SessionEvent
import app.tether.core.SessionHub
import app.tether.core.SessionKey
import app.tether.core.SessionLive
import app.tether.core.SessionRef
import app.tether.core.SessionRemote
import app.tether.core.SessionState
import app.tether.core.SlashCommand
import app.tether.core.SshManager
import app.tether.core.WatchMessage
import app.tether.core.identity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min

/**
 * [SessionHub] over the helper's one-session commands ([SessionRemote]).
 *
 * - [sessions]: one `watch` stream per machine, open while anything observes [sessions], a
 *   conversation is open, or background watch is on; reconnects with 1→30 s backoff (at once when
 *   the SSH link was re-established meanwhile). Transitions become [events].
 * - [open]: per session (or subagent) a [SessionReducer] fed by `follow`, combined with the newest
 *   Session from the watch list, emitted at ≤ ~30 fps. A dropped stream resumes from the last
 *   transcript offset; the reducer is kept (LRU) so re-opening is instant and resumes too.
 * - Writes go straight to the helper; a returned Session is merged at once.
 */
class DefaultSessionHub(
    private val remote: SessionRemote,
    private val ssh: SshManager,
    private val connections: ConnectionRepository,
    private val scope: CoroutineScope,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val frameMs: Long = FRAME_MS,
) : SessionHub {

    // connectionId → sessionId → Session
    private val machines = MutableStateFlow<Map<String, Map<String, Session>>>(emptyMap())
    private val machinesLock = Any()

    // The StateFlow instance itself is exposed so its subscriptionCount reflects real observers.
    private val sessionsState = MutableStateFlow<List<Session>>(emptyList())
    override val sessions: StateFlow<List<Session>> = sessionsState

    private val errorsState = MutableStateFlow<Map<String, String>>(emptyMap())
    override val machineErrors: StateFlow<Map<String, String>> = errorsState.asStateFlow()

    private val eventsFlow = MutableSharedFlow<SessionEvent>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val events: SharedFlow<SessionEvent> = eventsFlow.asSharedFlow()
    private val transitions = SessionTransitions()

    private val backgroundWatch = MutableStateFlow(false)
    private val openConversations = MutableStateFlow(0)
    private val paused = MutableStateFlow(false)
    private val held = MutableStateFlow<Set<String>>(emptySet())
    private val watchJobs = HashMap<String, Job>()

    init {
        val wanted = combine(sessionsState.subscriptionCount, backgroundWatch, openConversations, paused) { subs, bg, open, off ->
            !off && (subs > 0 || bg || open > 0)
        }.distinctUntilChanged()
        val ids = connections.connections.map { list -> list.map { it.id }.toSet() }.distinctUntilChanged()
        scope.launch {
            combine(ids, wanted, held) { set, want, off -> Triple(set, want, off) }.collectLatest { (set, want, off) ->
                pruneRemovedConnections(set)
                if (want) {
                    syncWatchers(set - off)
                } else {
                    if (!paused.value) delay(WATCH_LINGER_MS) // brief app switches should not tear the streams down
                    syncWatchers(emptySet())
                }
            }
        }
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
                remote.watchSessions(connectionId).collect { m ->
                    backoff = 1_000L
                    failures = 0
                    clearError(connectionId)
                    when (m) {
                        is WatchMessage.Snapshot -> replaceMachine(connectionId, m.sessions)
                        is WatchMessage.Changed -> patchMachine(connectionId, m.changed, m.removed)
                        is WatchMessage.Heartbeat -> Unit
                    }
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

    private suspend fun retryPause(connectionId: String, startedEpoch: Long, backoff: Long) {
        withTimeoutOrNull(backoff) { ssh.linkEpochs.first { (it[connectionId] ?: 0L) != startedEpoch } }
    }

    private fun isLinkBlip(e: Throwable, failures: Int): Boolean = e is java.io.IOException && failures < LINK_BLIP_TOLERANCE

    private fun pruneRemovedConnections(ids: Set<String>) {
        var changed = false
        synchronized(machinesLock) {
            machines.update { m ->
                val kept = m.filterKeys { it in ids }
                if (kept.size != m.size) changed = true
                kept
            }
        }
        synchronized(machinesLock) {
            val before = errorsState.value
            errorsState.update { m -> m.filterKeys { it in ids } }
            if (changed || errorsState.value != before) rebuild()
        }
    }

    /**
     * A machine whose watch keeps failing: its sessions stay listed with their last known state, marked
     * [Session.offline] (not counted as working / needing you, no inline answers) until it answers again.
     */
    private fun setError(id: String, message: String) = synchronized(machinesLock) {
        val was = errorsState.value[id]
        errorsState.update { it + (id to message) }
        if (was == null) rebuild()
    }

    private fun clearError(id: String) = synchronized(machinesLock) {
        if (id in errorsState.value) {
            errorsState.update { it - id }
            rebuild()
        }
    }

    private fun withMachine(list: Collection<Session>, connectionId: String) =
        list.map { if (it.connectionId == connectionId) it else it.copy(connectionId = connectionId) }

    /**
     * A machine's list. A watch snapshot replaces it ([merge] false: it is the whole list). A `sessions`
     * refresh merges ([merge] true): its list is capped lower than the watch's and may be older than the
     * watch's last change, so sessions it leaves out stay and, per session, the newer copy wins (a tie
     * goes to the refresh, the later read). Emits events for transitions.
     */
    private fun replaceMachine(connectionId: String, list: List<Session>, merge: Boolean = false) {
        synchronized(machinesLock) {
            val out = ArrayList<SessionEvent>()
            val prev = machines.value[connectionId]
            val next = LinkedHashMap<String, Session>()
            if (merge && prev != null) next.putAll(prev)
            for (s in withMachine(list, connectionId)) {
                val old = prev?.get(s.sessionId)
                if (merge && old != null && old.updatedAt > s.updatedAt) continue
                next[s.sessionId] = s
                out += transitions.diff(old, s, firstSight = prev == null)
            }
            if (!merge && prev != null) {
                for (sid in prev.keys) if (sid !in next) transitions.forget(SessionRef(connectionId, sid))
            }
            machines.update { it + (connectionId to next) }
            rebuild()
            out.forEach { eventsFlow.tryEmit(it) }
        }
    }

    private fun patchMachine(connectionId: String, changed: List<Session>, removed: List<String>) {
        synchronized(machinesLock) {
            val out = ArrayList<SessionEvent>()
            val cur = machines.value[connectionId]
            val next = LinkedHashMap(cur.orEmpty())
            for (s in withMachine(changed, connectionId)) {
                out += transitions.diff(cur?.get(s.sessionId), s, firstSight = cur?.get(s.sessionId) == null)
                next[s.sessionId] = s
            }
            for (sid in removed) {
                next.remove(sid)
                transitions.forget(SessionRef(connectionId, sid))
            }
            machines.update { it + (connectionId to next) }
            rebuild()
            out.forEach { eventsFlow.tryEmit(it) }
        }
    }

    /** Inserts / replaces one session after our own write, without events (unless it is newer and transitions). */
    private fun mergeSession(s: Session) {
        val cid = s.connectionId
        if (cid.isEmpty()) return
        synchronized(machinesLock) {
            machines.update { m ->
                val cur = m[cid].orEmpty()
                val old = cur[s.sessionId]
                if (old != null && old.updatedAt > s.updatedAt) return@update m
                m + (cid to (LinkedHashMap(cur).apply { put(s.sessionId, s) }))
            }
            rebuild()
        }
    }

    private fun dropSession(ref: SessionRef) {
        synchronized(machinesLock) {
            machines.update { m ->
                val cur = m[ref.connectionId] ?: return@update m
                if (ref.sessionId !in cur) m else m + (ref.connectionId to (cur - ref.sessionId))
            }
            transitions.forget(ref)
            rebuild()
        }
    }

    /** Publishes [sessions] from [machines]. Callers hold [machinesLock], so the newest state is written last. */
    private fun rebuild() {
        val offline = errorsState.value.keys
        val all = ArrayList<Session>()
        for ((cid, byId) in machines.value) {
            if (cid in offline) byId.values.mapTo(all) { it.copy(offline = true) } else all.addAll(byId.values)
        }
        all.sortByDescending { it.updatedAt }
        sessionsState.value = all
    }

    override fun session(ref: SessionRef): Session? = machines.value[ref.connectionId]?.get(ref.sessionId)

    override suspend fun refresh(connectionId: String?) {
        if (connectionId != null) release(connectionId)
        val targets = if (connectionId != null) listOf(connectionId) else connections.connections.value.map { it.id }.filter { it !in held.value }
        coroutineScope {
            targets.map { id ->
                async {
                    try {
                        replaceMachine(id, remote.sessions(id), merge = true)
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

    override suspend fun history(connectionId: String, cwd: String?, limit: Int, before: Long?): List<Session> =
        remote.sessions(connectionId, cwd, limit, before).map { if (it.connectionId == connectionId) it else it.copy(connectionId = connectionId) }

    override fun setBackgroundWatch(enabled: Boolean) {
        backgroundWatch.value = enabled
    }

    override fun setPaused(paused: Boolean) {
        this.paused.value = paused
    }

    override suspend fun hold(connectionId: String) {
        held.update { it + connectionId }
        val job = synchronized(watchJobs) { watchJobs.remove(connectionId) }
        job?.cancelAndJoin()
    }

    override fun release(connectionId: String) {
        held.update { it - connectionId }
    }

    private val commandCache = ConcurrentHashMap<Pair<String, String>, List<SlashCommand>>()

    override suspend fun slashCommands(connectionId: String, cwd: String): List<SlashCommand> {
        val key = connectionId to cwd
        commandCache[key]?.let { return it }
        return remote.listCommands(connectionId, cwd).also { if (it.isNotEmpty()) commandCache[key] = it }
    }

    // ═══════════════════════════════════════ conversations ═══════════════════════════════════════

    private data class ConvKey(val ref: SessionRef, val agentId: String?)

    private inner class Conv(val key: ConvKey) {
        val lock = Any()
        val reducer = SessionReducer(key.ref, key.agentId, clock)
        @Volatile var error: String? = null
        /** Open collectors of this conversation; the LRU only evicts idle ones. Guarded by convs. */
        var subscribers = 0
        /** The watch-list Session last applied (identity): a version bump does not re-apply it. */
        var lastWatchSession: Session? = null
        val version = MutableStateFlow(0L)
        fun bump() = version.update { it + 1 }
    }

    private val convs = object : LinkedHashMap<ConvKey, Conv>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<ConvKey, Conv>): Boolean =
            size > MAX_CACHED_CONVERSATIONS && eldest.value.subscribers == 0
    }
    private val flows = ConcurrentHashMap<ConvKey, StateFlow<ConversationState>>()

    /** The conversation for key, counted as in use until [releaseConv]. */
    private fun acquireConv(key: ConvKey): Conv = synchronized(convs) {
        val c = convs[key] ?: Conv(key).also { convs[key] = it }
        c.subscribers++
        c
    }

    private fun releaseConv(c: Conv) = synchronized(convs) {
        c.subscribers--
        // Over the cap while everything was in use: trim the idle ones now (oldest first).
        if (convs.size > MAX_CACHED_CONVERSATIONS) {
            val it = convs.entries.iterator()
            while (convs.size > MAX_CACHED_CONVERSATIONS && it.hasNext()) {
                val e = it.next()
                if (e.value.subscribers == 0) it.remove()
            }
        }
    }

    private fun initialState(ref: SessionRef, agentId: String?): ConversationState {
        val s = session(ref)
        return ConversationState(
            title = s?.title,
            cwd = s?.cwd,
            sessionId = ref.sessionId,
            model = s?.model,
            permissionMode = s?.permissionMode,
            loadingHistory = true,
            live = SessionLive(ref = ref, agentId = agentId, session = s, pending = s?.pending, heldByTerminal = s?.heldByTerminal == true),
        )
    }

    override fun open(ref: SessionRef, agentId: String?): StateFlow<ConversationState> {
        val key = ConvKey(ref, agentId)
        return flows.getOrPut(key) {
            channelFlow {
                val c = acquireConv(key)
                openConversations.update { it + 1 }
                try {
                    launch(Dispatchers.Default) { pump(c) }
                    val sessionFlow = machines.map { it[ref.connectionId]?.get(ref.sessionId) }.distinctUntilChanged()
                    val linkFlow = ssh.states.map { it[ref.connectionId] ?: LinkState.Idle }.distinctUntilChanged()
                    combine(c.version, sessionFlow, linkFlow) { _, s, link -> s to link }
                        .conflate()
                        .collect { (s, link) ->
                            val state = synchronized(c.lock) {
                                // Only a new watch copy is applied (a version bump re-runs this with the old one),
                                // and it never replaces the follow's own state unless strictly newer.
                                if (s != null && s !== c.lastWatchSession) {
                                    c.lastWatchSession = s
                                    c.reducer.applySession(s, fromWatch = true)
                                }
                                c.reducer.snapshot(link, c.error)
                            }
                            send(state)
                            if (frameMs > 0) delay(frameMs)
                        }
                } finally {
                    openConversations.update { it - 1 }
                    releaseConv(c)
                }
            // replayExpirationMillis = 0: once nobody watches, the flow drops its last snapshot (every chat item)
            // and holds only the small initial state; the reducer itself is cached (LRU) in convs.
            }.stateIn(scope, SharingStarted.WhileSubscribed(5_000, replayExpirationMillis = 0), initialState(ref, agentId))
        }
    }

    private suspend fun pump(c: Conv) {
        val ref = c.key.ref
        var backoff = 1_000L
        var failures = 0
        while (true) {
            val epoch = epochOf(ref.connectionId)
            try {
                val from = synchronized(c.lock) { c.reducer.beginFollow() }
                c.bump()
                remote.follow(ref.connectionId, ref.sessionId, c.key.agentId, from).collect { e ->
                    val changed = synchronized(c.lock) { c.reducer.accept(e) }
                    if (e is FollowEvent.CaughtUp) {
                        backoff = 1_000L
                        failures = 0
                        if (c.error != null) { c.error = null; c.bump() }
                    }
                    if (changed) c.bump()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                failures++
                if (!isLinkBlip(e, failures)) c.error = friendlyMessage(e)
                synchronized(c.lock) { c.reducer.onDisconnected() }
                c.bump()
            }
            retryPause(ref.connectionId, epoch, backoff)
            backoff = min(backoff * 2, 30_000L)
        }
    }

    // ═══════════════════════════════════════ writes ═══════════════════════════════════════

    private suspend fun upload(connectionId: String, images: List<ImageAttachment>): List<String> =
        images.map { remote.uploadImage(connectionId, it) }

    override suspend fun new(connectionId: String, request: NewSessionRequest, images: List<ImageAttachment>): NewSessionResult {
        val paths = upload(connectionId, images)
        return try {
            val s = remote.newSession(connectionId, request.copy(images = request.images + paths))
            val withMachine = if (s.connectionId == connectionId) s else s.copy(connectionId = connectionId)
            mergeSession(withMachine)
            NewSessionResult.Started(withMachine)
        } catch (e: RemoteException) {
            if (e.code == SessionErrorCodes.EUNTRUSTED) NewSessionResult.Untrusted(request.cwd) else throw e
        }
    }

    override suspend fun send(ref: SessionRef, text: String, images: List<ImageAttachment>): Boolean {
        val paths = upload(ref.connectionId, images)
        val conv = synchronized(convs) { convs.entries.firstOrNull { it.key.ref == ref && it.key.agentId == null }?.value }
        val busy = session(ref)?.state == SessionState.WORKING
        val local = conv?.let { c -> synchronized(c.lock) { c.reducer.addOptimisticUser(text, images.size, queued = busy) }.also { c.bump() } }
        try {
            return remote.send(ref.connectionId, ref.sessionId, text, paths)
        } catch (e: Throwable) {
            if (conv != null && local != null) {
                synchronized(conv.lock) { conv.reducer.removeOptimistic(local) }
                conv.bump()
            }
            throw e
        }
    }

    override suspend fun key(ref: SessionRef, keys: List<SessionKey>) {
        remote.key(ref.connectionId, ref.sessionId, keys)
    }

    override suspend fun setMode(ref: SessionRef, mode: String?): String? =
        remote.setMode(ref.connectionId, ref.sessionId, mode)

    override suspend fun answer(ref: SessionRef, decision: SessionDecision, message: String?, toolUseId: String?) {
        mergeSession(remote.answer(ref.connectionId, ref.sessionId, decision, message, toolUseId).ofMachine(ref))
    }

    override suspend fun ask(ref: SessionRef, answers: List<AskAnswer>) {
        mergeSession(remote.ask(ref.connectionId, ref.sessionId, answers).ofMachine(ref))
    }

    override suspend fun interrupt(ref: SessionRef) {
        mergeSession(remote.interrupt(ref.connectionId, ref.sessionId).ofMachine(ref))
    }

    override suspend fun btw(ref: SessionRef, question: String): String =
        remote.btw(ref.connectionId, ref.sessionId, question)

    override suspend fun stop(ref: SessionRef) {
        remote.stop(ref.connectionId, ref.sessionId)
    }

    override suspend fun remove(ref: SessionRef) {
        remote.rm(ref.connectionId, ref.sessionId)
        dropSession(ref)
    }

    private fun Session.ofMachine(ref: SessionRef) = if (connectionId == ref.connectionId) this else copy(connectionId = ref.connectionId)

    private companion object {
        const val FRAME_MS = 33L
        const val WATCH_LINGER_MS = 15_000L
        const val LINK_BLIP_TOLERANCE = 3
        const val MAX_CACHED_CONVERSATIONS = 8
    }
}

/**
 * Turns successive descriptions of a session into notification events. Thread-confined to the
 * hub's machine lock. A needs-you prompt is announced once per prompt (identity of `pending`);
 * leaving needs-you re-arms it. Terminal-held sessions never notify: someone is at that keyboard.
 */
class SessionTransitions {
    private val announced = HashMap<SessionRef, String>()

    fun diff(prev: Session?, next: Session, firstSight: Boolean): List<SessionEvent> {
        val ref = next.ref
        val out = ArrayList<SessionEvent>(1)
        if (next.state != SessionState.NEEDS_YOU) announced.remove(ref)
        if (next.heldByTerminal) return out
        when {
            next.state == SessionState.NEEDS_YOU -> {
                val id = next.pending?.identity ?: next.waitingFor ?: "needs-you"
                if (announced[ref] != id) {
                    announced[ref] = id
                    out += SessionEvent.NeedsYou(ref, next.title, next.pending, next.waitingFor)
                }
            }
            prev == null || firstSight -> Unit
            prev.state == SessionState.WORKING && (next.state == SessionState.IDLE || next.state == SessionState.DONE) ->
                out += SessionEvent.TurnDone(ref, next.title, next.lastText)
            prev.state != SessionState.FAILED && next.state == SessionState.FAILED ->
                out += SessionEvent.Failed(ref, next.title, next.lastText ?: next.waitingFor)
        }
        return out
    }

    fun forget(ref: SessionRef) {
        announced.remove(ref)
    }
}
