package app.tether.ui.newagent

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tether.AppContainer
import app.tether.core.Connection
import app.tether.core.ImageAttachment
import app.tether.core.LinkState
import app.tether.core.McpChoice
import app.tether.core.PermissionMode
import app.tether.core.ProjectSummary
import app.tether.core.RunRef
import app.tether.core.NativeStartResult
import app.tether.core.SessionSummary
import app.tether.core.SlashCommand
import app.tether.core.StartRunRequest
import app.tether.ui.chat.slashCommandsFor
import app.tether.ui.home.Loadable
import app.tether.ui.home.attempt
import app.tether.ui.home.humanMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

class PickedImage(val id: Long, val attachment: ImageAttachment, val thumbnail: ImageBitmap)

data class ResumeInfo(val sessionId: String, val session: Loadable<SessionSummary?>)

data class NewAgentUiState(
    val connections: List<Connection> = emptyList(),
    val links: Map<String, LinkState> = emptyMap(),
    val connectionId: String? = null,
    val cwd: String? = null,
    /** The folder was picked by Tether (most recent project / home), not the user. */
    val cwdSuggested: Boolean = false,
    val resolvingCwd: Boolean = false,
    val projects: Loadable<List<ProjectSummary>> = Loadable.Loading,
    val model: String = "default",
    val mode: PermissionMode = PermissionMode.DEFAULT,
    val images: List<PickedImage> = emptyList(),
    val processingImages: Int = 0,
    val starting: Boolean = false,
    val error: String? = null,
    val resume: ResumeInfo? = null,
    /** Resuming pins the machine and folder to the session's. */
    val locked: Boolean = false,
    /** Start a native `claude --bg` agent instead of a Tether live run. */
    val background: Boolean = false,
    /** Claude Code does not trust this folder yet: ask before marking it trusted. */
    val trustPrompt: String? = null,
    /** The folder has project MCP servers Claude Code would ask about: enable or skip them first. */
    val mcpPrompt: NativeStartResult.McpApproval? = null,
) {
    /** Background is not offered when continuing a session (the live run carries the history). */
    val effectiveBackground: Boolean get() = background && resume == null
    val connection: Connection? get() = connections.firstOrNull { it.id == connectionId }
}

sealed interface NewAgentEvent {
    data class Started(val ref: RunRef) : NewAgentEvent
    data class Message(val text: String) : NewAgentEvent
}

private data class Form(
    val connectionId: String? = null,
    val cwd: String? = null,
    val cwdSuggested: Boolean = false,
    val resolvingCwd: Boolean = false,
    val projects: Loadable<List<ProjectSummary>> = Loadable.Loading,
    val model: String = "default",
    val mode: PermissionMode = PermissionMode.DEFAULT,
    val images: List<PickedImage> = emptyList(),
    val processingImages: Int = 0,
    val starting: Boolean = false,
    val error: String? = null,
    val resume: ResumeInfo? = null,
    val background: Boolean = false,
    val trustPrompt: String? = null,
    val mcpPrompt: NativeStartResult.McpApproval? = null,
)

class NewAgentViewModel(
    private val container: AppContainer,
    private val argConnectionId: String?,
    private val argCwd: String?,
    private val resumeSessionId: String?,
) : ViewModel() {
    private val ctx = container.app
    private val form = MutableStateFlow(Form())
    private val events = Channel<NewAgentEvent>(Channel.BUFFERED)
    val eventFlow: Flow<NewAgentEvent> = events.receiveAsFlow()
    private var projectsJob: Job? = null
    private val ids = AtomicLong(1)

    /** Prompt lives in Compose state so the text field keeps cursor/selection across dictation inserts. */
    var prompt by mutableStateOf(TextFieldValue(""))
        private set

    val state: StateFlow<NewAgentUiState> = combine(container.connections.connections, container.ssh.states, form) { conns, links, f ->
        NewAgentUiState(
            connections = conns,
            links = links,
            connectionId = f.connectionId,
            cwd = f.cwd,
            cwdSuggested = f.cwdSuggested,
            resolvingCwd = f.resolvingCwd,
            projects = f.projects,
            model = f.model,
            mode = f.mode,
            images = f.images,
            processingImages = f.processingImages,
            starting = f.starting,
            error = f.error,
            resume = f.resume,
            locked = f.locked(),
            background = f.background,
            trustPrompt = f.trustPrompt,
            mcpPrompt = f.mcpPrompt,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NewAgentUiState(connections = container.connections.connections.value))

    val commands: StateFlow<List<SlashCommand>> = viewModelScope.slashCommandsFor(
        container.agents,
        form.map { f -> f.cwd?.let { cwd -> f.connectionId?.let { it to cwd } } },
    )

    init {
        val settings = container.settings.settings.value
        val model = NewAgentPrefs.model(ctx, settings.defaultModel)
        val mode = PermissionMode.fromCli(NewAgentPrefs.mode(ctx, settings.defaultPermissionMode))
            ?: PermissionMode.fromCli(settings.defaultPermissionMode)
            ?: PermissionMode.DEFAULT
        form.update {
            it.copy(
                model = model, mode = mode, resume = resumeSessionId?.let { id -> ResumeInfo(id, Loadable.Loading) },
                background = NewAgentPrefs.background(ctx),
            )
        }

        val conns = container.connections.connections.value
        val initial = argConnectionId?.takeIf { id -> conns.any { it.id == id } }
            ?: NewAgentPrefs.lastConnection(ctx)?.takeIf { id -> conns.any { it.id == id } }
            ?: conns.firstOrNull()?.id
        if (initial != null) selectMachine(initial, fromUser = false)
        if (resumeSessionId != null) loadResume()

        // A machine added from the empty state (or deleted elsewhere) while this screen is open.
        viewModelScope.launch {
            container.connections.connections.collect { list ->
                val sel = form.value.connectionId
                if (sel == null && list.isNotEmpty()) selectMachine(list.last().id, fromUser = false)
                else if (sel != null && list.none { it.id == sel }) {
                    val next = list.firstOrNull()?.id
                    if (next != null) selectMachine(next, fromUser = false)
                    else form.update { it.copy(connectionId = null, cwd = null, projects = Loadable.Loading) }
                }
            }
        }
    }

    // ───────────── Machine & folder ─────────────

    fun selectMachine(id: String, fromUser: Boolean = true) {
        if (form.value.locked() && fromUser) return
        if (form.value.connectionId == id && form.value.projects !is Loadable.Failed && fromUser) return
        val conn = container.connections.get(id)
        val explicit = when {
            id == argConnectionId && argCwd != null -> argCwd
            else -> NewAgentPrefs.folder(ctx, id) ?: conn?.defaultCwd
        }
        val keepResumeCwd = form.value.resume?.session?.valueOrNull?.cwd?.takeIf { form.value.connectionId == id }
        form.update {
            it.copy(
                connectionId = id,
                cwd = keepResumeCwd ?: explicit,
                cwdSuggested = false,
                projects = Loadable.Loading,
                error = null,
            )
        }
        loadProjects(id)
    }

    private fun Form.locked() = resume != null && connectionId != null && argConnectionId != null

    fun retryProjects() {
        form.value.connectionId?.let { loadProjects(it) }
    }

    private fun loadProjects(id: String) {
        projectsJob?.cancel()
        projectsJob = viewModelScope.launch {
            val r = attempt { container.remote.listProjects(id) }
            if (form.value.connectionId != id) return@launch
            r.onSuccess { list ->
                val sorted = list.sortedByDescending { it.lastActiveAt }
                form.update { it.copy(projects = Loadable.Ready(sorted)) }
                if (form.value.cwd == null && form.value.resume == null) {
                    val suggestion = sorted.firstOrNull { it.exists }?.cwd
                    if (suggestion != null) form.update { it.copy(cwd = suggestion, cwdSuggested = true) }
                    else resolveHome(id)
                }
            }.onFailure { e ->
                form.update { it.copy(projects = Loadable.Failed(e.humanMessage())) }
                if (form.value.cwd == null && form.value.resume == null) resolveHome(id)
            }
        }
    }

    /** Last resort default: the remote $HOME (what `listDir(null)` lists). */
    private suspend fun resolveHome(id: String) {
        form.update { it.copy(resolvingCwd = true) }
        val home = attempt { container.remote.listDir(id, null).path }.getOrNull()
        form.update { f ->
            if (f.connectionId == id && f.cwd == null && home != null) f.copy(cwd = home, cwdSuggested = true, resolvingCwd = false)
            else f.copy(resolvingCwd = false)
        }
    }

    fun selectFolder(path: String) {
        if (form.value.locked()) return
        form.update { it.copy(cwd = path, cwdSuggested = false, error = null) }
    }

    // ───────────── Resume ─────────────

    private fun loadResume() {
        val sid = resumeSessionId ?: return
        viewModelScope.launch {
            val conn = form.value.connectionId ?: argConnectionId
            if (conn == null) {
                form.update { it.copy(resume = ResumeInfo(sid, Loadable.Ready(null))) }
                return@launch
            }
            val r = attempt {
                val scoped = if (argCwd != null) container.remote.listSessions(conn, argCwd, 200).firstOrNull { it.sessionId == sid } else null
                scoped ?: container.remote.listSessions(conn, null, 200).firstOrNull { it.sessionId == sid }
            }
            r.onSuccess { s ->
                form.update { f ->
                    f.copy(
                        resume = ResumeInfo(sid, Loadable.Ready(s)),
                        cwd = s?.cwd ?: f.cwd,
                        cwdSuggested = if (s != null) false else f.cwdSuggested,
                    )
                }
                if (s == null && form.value.cwd == null) resolveHome(conn)
            }.onFailure { e ->
                form.update { it.copy(resume = ResumeInfo(sid, Loadable.Failed(e.humanMessage()))) }
                if (form.value.cwd == null) resolveHome(conn)
            }
        }
    }

    fun retryResume() {
        val r = form.value.resume ?: return
        form.update { it.copy(resume = r.copy(session = Loadable.Loading)) }
        loadResume()
    }

    // ───────────── Options ─────────────

    fun selectModel(value: String) = form.update { it.copy(model = value) }

    fun selectMode(mode: PermissionMode) = form.update { it.copy(mode = mode) }

    fun selectBackground(background: Boolean) {
        form.update { it.copy(background = background, error = null) }
        NewAgentPrefs.rememberBackground(ctx, background)
        if (background && form.value.images.isNotEmpty()) {
            events.trySend(NewAgentEvent.Message("Background agents take text only — images will not be sent"))
        }
    }

    // ───────────── Prompt ─────────────

    fun onPromptChange(v: TextFieldValue) {
        prompt = v
        if (form.value.error != null) form.update { it.copy(error = null) }
    }

    fun insertCommand(name: String) {
        val t = "/${name.removePrefix("/")} "
        prompt = TextFieldValue(t, TextRange(t.length))
    }

    fun applySuggestion(text: String) {
        val cur = prompt.text
        val next = if (cur.isBlank()) text else cur.trimEnd() + "\n\n" + text
        prompt = TextFieldValue(next, TextRange(next.length))
    }

    fun insertDictation(spoken: String) {
        val said = spoken.trim()
        if (said.isEmpty()) return
        val v = prompt
        val start = v.selection.min.coerceIn(0, v.text.length)
        val end = v.selection.max.coerceIn(0, v.text.length)
        val before = v.text.substring(0, start)
        val after = v.text.substring(end)
        val lead = if (before.isNotEmpty() && !before.last().isWhitespace()) " " else ""
        val insert = lead + (if (before.isBlank()) said.replaceFirstChar { it.uppercase() } else said)
        val trail = if (after.isNotEmpty() && !after.first().isWhitespace()) " " else ""
        val text = before + insert + trail + after
        prompt = TextFieldValue(text, TextRange(before.length + insert.length + trail.length))
    }

    // ───────────── Images ─────────────

    fun addImages(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val room = MAX_IMAGES - form.value.images.size - form.value.processingImages
        if (room <= 0) {
            events.trySend(NewAgentEvent.Message("You can attach up to $MAX_IMAGES images"))
            return
        }
        val take = uris.take(room)
        if (uris.size > take.size) events.trySend(NewAgentEvent.Message("Only the first ${take.size} fit — $MAX_IMAGES images max"))
        form.update { it.copy(processingImages = it.processingImages + take.size) }
        val resolver = ctx.contentResolver
        take.forEach { uri ->
            viewModelScope.launch {
                val r = attempt { withContext(Dispatchers.Default) { ImageProcessor.process(resolver, uri) } }
                r.onSuccess { p ->
                    val picked = PickedImage(ids.getAndIncrement(), p.attachment, p.thumbnail.asImageBitmap())
                    form.update { it.copy(images = it.images + picked, processingImages = (it.processingImages - 1).coerceAtLeast(0)) }
                }.onFailure { e ->
                    form.update { it.copy(processingImages = (it.processingImages - 1).coerceAtLeast(0)) }
                    events.trySend(NewAgentEvent.Message("Couldn't attach image — ${e.humanMessage()}"))
                }
            }
        }
    }

    fun removeImage(id: Long) = form.update { f -> f.copy(images = f.images.filterNot { it.id == id }) }

    // ───────────── Start ─────────────

    /** Why Start is disabled, or null when ready. */
    fun blocker(s: NewAgentUiState): String? = when {
        s.connections.isEmpty() -> "Add a machine to start"
        s.connectionId == null -> "Choose a machine"
        s.cwd == null -> if (s.resolvingCwd) "Finding a folder…" else "Choose a folder"
        s.processingImages > 0 -> "Preparing images…"
        s.resume == null && prompt.text.isBlank() -> "Tell Claude what to do"
        else -> null
    }

    /**
     * Native background agent: `claude --bg` on the machine; [trust] marks the folder trusted first,
     * [mcp] answers Claude's project MCP server question (else the agent would wait on it at the keyboard).
     */
    private fun startBackground(s: NewAgentUiState, trust: Boolean, mcp: McpChoice? = null) {
        val conn = s.connectionId ?: return
        val cwd = s.cwd ?: return
        val request = StartRunRequest(
            cwd = cwd,
            prompt = prompt.text.trim(),
            model = s.model.takeIf { it != "default" && it.isNotBlank() },
            permissionMode = s.mode.cli.takeIf { it != PermissionMode.DEFAULT.cli },
        )
        form.update { it.copy(starting = true, error = null, trustPrompt = null, mcpPrompt = null) }
        viewModelScope.launch {
            attempt { container.agents.startNative(conn, request, trustFolder = trust, mcp = mcp) }
                .onSuccess { res ->
                    when (res) {
                        is NativeStartResult.Started -> {
                            val settings = container.settings.settings.value
                            NewAgentPrefs.rememberChoices(ctx, conn, cwd, s.model, s.mode.cli, settings.defaultModel, settings.defaultPermissionMode)
                            events.trySend(NewAgentEvent.Started(res.ref))
                        }
                        is NativeStartResult.Untrusted -> form.update { it.copy(starting = false, trustPrompt = res.cwd) }
                        is NativeStartResult.McpApproval -> form.update { it.copy(starting = false, mcpPrompt = res) }
                    }
                }
                .onFailure { e -> form.update { it.copy(starting = false, error = e.humanMessage()) } }
        }
    }

    /** The user agreed to trust the folder: mark it trusted for Claude Code and start. */
    fun confirmTrust() {
        val s = state.value
        if (s.starting) return
        startBackground(s, trust = true)
    }

    fun dismissTrust() = form.update { it.copy(trustPrompt = null) }

    /** The user chose whether the folder's project MCP servers run; record it for Claude Code and start. */
    fun answerMcp(enable: Boolean) {
        val s = state.value
        if (s.starting) return
        startBackground(s, trust = false, mcp = if (enable) McpChoice.ENABLE else McpChoice.SKIP)
    }

    fun dismissMcp() = form.update { it.copy(mcpPrompt = null) }

    fun start() {
        val s = state.value
        if (s.starting || blocker(s) != null) return
        if (s.effectiveBackground) return startBackground(s, trust = false)
        val conn = s.connectionId ?: return
        val cwd = s.cwd ?: return
        val text = prompt.text.trim()
        val request = StartRunRequest(
            cwd = cwd,
            prompt = text.ifBlank { null },
            model = s.model.takeIf { it != "default" && it.isNotBlank() },
            permissionMode = s.mode.cli, // "default" is applied by the helper via set_permission_mode
            resumeSessionId = resumeSessionId,
            // The first line of the prompt names the agent everywhere (Home cards, the top bar).
            title = text.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(80)?.takeIf { resumeSessionId == null },
        )
        form.update { it.copy(starting = true, error = null) }
        viewModelScope.launch {
            attempt { container.agents.start(conn, request, s.images.map { it.attachment }) }
                .onSuccess { ref ->
                    val settings = container.settings.settings.value
                    NewAgentPrefs.rememberChoices(ctx, conn, cwd, s.model, s.mode.cli, settings.defaultModel, settings.defaultPermissionMode)
                    events.trySend(NewAgentEvent.Started(ref))
                }
                .onFailure { e ->
                    form.update { it.copy(starting = false, error = e.humanMessage()) }
                }
        }
    }

    fun dismissError() = form.update { it.copy(error = null) }

    companion object {
        const val MAX_IMAGES = 5
    }
}
