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
import app.tether.core.NewSessionRequest
import app.tether.core.NewSessionResult
import app.tether.core.PermissionMode
import app.tether.core.ProjectSummary
import app.tether.core.SessionRef
import app.tether.core.SlashCommand
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
    /** Claude Code does not trust this folder yet (`EUNTRUSTED`): ask before marking it trusted. */
    val trustPrompt: String? = null,
) {
    val connection: Connection? get() = connections.firstOrNull { it.id == connectionId }
}

sealed interface NewAgentEvent {
    data class Started(val ref: SessionRef) : NewAgentEvent
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
    val trustPrompt: String? = null,
)

/**
 * The `new` request the form makes: the default model / permission mode are left out (the
 * session then uses the machine's own defaults); [trust] marks the folder trusted first.
 */
internal fun newSessionRequest(cwd: String, prompt: String, model: String, mode: PermissionMode, trust: Boolean): NewSessionRequest =
    NewSessionRequest(
        cwd = cwd,
        prompt = prompt.trim(),
        model = model.trim().takeIf { it.isNotEmpty() && it != "default" },
        permissionMode = mode.cli.takeIf { it != PermissionMode.DEFAULT.cli },
        trust = trust,
    )

class NewAgentViewModel(
    private val container: AppContainer,
    private val argConnectionId: String?,
    private val argCwd: String?,
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
            trustPrompt = f.trustPrompt,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NewAgentUiState(connections = container.connections.connections.value))

    val commands: StateFlow<List<SlashCommand>> = viewModelScope.slashCommandsFor(
        container.sessions,
        form.map { f -> f.cwd?.let { cwd -> f.connectionId?.let { it to cwd } } },
    )

    init {
        val settings = container.settings.settings.value
        val model = NewAgentPrefs.model(ctx, settings.defaultModel)
        val mode = PermissionMode.fromCli(NewAgentPrefs.mode(ctx, settings.defaultPermissionMode))
            ?: PermissionMode.fromCli(settings.defaultPermissionMode)
            ?: PermissionMode.DEFAULT
        form.update { it.copy(model = model, mode = mode) }

        val conns = container.connections.connections.value
        val initial = argConnectionId?.takeIf { id -> conns.any { it.id == id } }
            ?: NewAgentPrefs.lastConnection(ctx)?.takeIf { id -> conns.any { it.id == id } }
            ?: conns.firstOrNull()?.id
        if (initial != null) selectMachine(initial, fromUser = false)

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
        if (form.value.connectionId == id && form.value.projects !is Loadable.Failed && fromUser) return
        val conn = container.connections.get(id)
        val explicit = when {
            id == argConnectionId && argCwd != null -> argCwd
            else -> NewAgentPrefs.folder(ctx, id) ?: conn?.defaultCwd
        }
        form.update {
            it.copy(
                connectionId = id,
                cwd = explicit,
                cwdSuggested = false,
                projects = Loadable.Loading,
                error = null,
            )
        }
        loadProjects(id)
    }

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
                if (form.value.cwd == null) {
                    val suggestion = sorted.firstOrNull { it.exists }?.cwd
                    if (suggestion != null) form.update { it.copy(cwd = suggestion, cwdSuggested = true) }
                    else resolveHome(id)
                }
            }.onFailure { e ->
                form.update { it.copy(projects = Loadable.Failed(e.humanMessage())) }
                if (form.value.cwd == null) resolveHome(id)
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
        form.update { it.copy(cwd = path, cwdSuggested = false, error = null) }
    }

    // ───────────── Options ─────────────

    fun selectModel(value: String) = form.update { it.copy(model = value) }

    fun selectMode(mode: PermissionMode) = form.update { it.copy(mode = mode) }

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
        prompt.text.isBlank() -> "Tell Claude what to do"
        else -> null
    }

    /** Starts the session (`new`); [trust] marks the folder trusted for Claude Code first. */
    private fun startSession(s: NewAgentUiState, trust: Boolean) {
        val conn = s.connectionId ?: return
        val cwd = s.cwd ?: return
        val request = newSessionRequest(cwd, prompt.text, s.model, s.mode, trust)
        form.update { it.copy(starting = true, error = null, trustPrompt = null) }
        viewModelScope.launch {
            attempt { container.sessions.new(conn, request, s.images.map { it.attachment }) }
                .onSuccess { res ->
                    when (res) {
                        is NewSessionResult.Started -> {
                            val settings = container.settings.settings.value
                            NewAgentPrefs.rememberChoices(ctx, conn, cwd, s.model, s.mode.cli, settings.defaultModel, settings.defaultPermissionMode)
                            events.trySend(NewAgentEvent.Started(SessionRef(conn, res.session.sessionId)))
                        }
                        is NewSessionResult.Untrusted -> form.update { it.copy(starting = false, trustPrompt = res.cwd) }
                    }
                }
                .onFailure { e -> form.update { it.copy(starting = false, error = e.humanMessage()) } }
        }
    }

    fun start() {
        val s = state.value
        if (s.starting || blocker(s) != null) return
        startSession(s, trust = false)
    }

    /** The user agreed to trust the folder: mark it trusted for Claude Code and start. */
    fun confirmTrust() {
        val s = state.value
        if (s.starting || blocker(s) != null) return
        startSession(s, trust = true)
    }

    fun dismissTrust() = form.update { it.copy(trustPrompt = null) }

    fun dismissError() = form.update { it.copy(error = null) }

    companion object {
        const val MAX_IMAGES = 5
    }
}
