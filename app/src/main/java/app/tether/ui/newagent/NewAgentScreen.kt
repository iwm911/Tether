package app.tether.ui.newagent

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Laptop
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.RateReview
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.tether.LocalAppContainer
import app.tether.core.Connection
import app.tether.core.FallbackModels
import app.tether.core.LinkState
import app.tether.core.ModelOption
import app.tether.core.PermissionMode
import app.tether.core.ProjectSummary
import app.tether.core.RunRef
import app.tether.ui.chat.SlashPopup
import app.tether.ui.chat.matchSlashCommands
import app.tether.ui.components.Hairline
import app.tether.ui.components.MachineAvatar
import app.tether.ui.components.PrimaryButton
import app.tether.ui.components.SecondaryButton
import app.tether.ui.components.StatusDot
import app.tether.ui.components.TagChip
import app.tether.ui.components.TetherCard
import app.tether.ui.components.accentColor
import app.tether.ui.components.prettyPath
import app.tether.ui.components.projectName
import app.tether.ui.components.relativeTime
import app.tether.ui.components.rememberHaptics
import app.tether.ui.home.GitBranchBadge
import app.tether.ui.home.IconTile
import app.tether.ui.home.Loadable
import app.tether.ui.home.MiniBadge
import app.tether.ui.home.SkeletonBlock
import app.tether.ui.home.SkeletonListRow
import app.tether.ui.home.linkLook
import app.tether.ui.home.prettyModel
import app.tether.ui.theme.Space
import app.tether.ui.theme.TetherTheme
import kotlinx.coroutines.launch

private data class Suggestion(val text: String, val icon: ImageVector)

private val Suggestions = listOf(
    Suggestion("Explain this codebase", Icons.Rounded.AutoAwesome),
    Suggestion("Fix failing tests", Icons.Rounded.BugReport),
    Suggestion("Review my last commit", Icons.Rounded.RateReview),
)

@Composable
fun NewAgentScreen(
    connectionId: String?,
    cwd: String?,
    resumeSessionId: String?,
    onBack: () -> Unit,
    onStarted: (RunRef) -> Unit,
    onAddMachine: () -> Unit,
) {
    val container = LocalAppContainer.current
    val vm: NewAgentViewModel = viewModel(key = "new:$connectionId:$cwd:$resumeSessionId") {
        NewAgentViewModel(container, connectionId, cwd, resumeSessionId)
    }
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val haptics = rememberHaptics()
    val snackbar = remember { SnackbarHostState() }
    val currentOnStarted by rememberUpdatedState(onStarted)
    val scope = rememberCoroutineScope()

    var browsing by rememberSaveable { mutableStateOf(false) }
    var confirmBypass by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(vm) {
        vm.eventFlow.collect { e ->
            when (e) {
                is NewAgentEvent.Started -> { haptics.confirm(); currentOnStarted(e.ref) }
                is NewAgentEvent.Message -> launch { snackbar.showSnackbar(e.text) }
            }
        }
    }

    val canDictate = remember { runCatching { SpeechRecognizer.isRecognitionAvailable(context) }.getOrDefault(false) }
    val voiceLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            res.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let(vm::insertDictation)
        }
    }
    val imageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(NewAgentViewModel.MAX_IMAGES)) { uris ->
        vm.addImages(uris)
    }

    val resuming = state.resume != null
    val blocker = vm.blocker(state)

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            // ── Top bar ──
            Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.Rounded.Close, contentDescription = "Close") }
                Spacer(Modifier.width(4.dp))
                Text(if (resuming) "Continue session" else "New agent", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            }

            // ── Steps ──
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Space.gutter),
                verticalArrangement = Arrangement.spacedBy(Space.md),
            ) {
                state.resume?.let { r ->
                    ContinuingCard(r, onRetry = vm::retryResume)
                }
                PromptHero(resuming) {
                    PromptStep(
                        vm = vm,
                        state = state,
                        resuming = resuming,
                        canDictate = canDictate,
                        onDictate = {
                            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                                .putExtra(RecognizerIntent.EXTRA_PROMPT, "What should Claude do?")
                            try {
                                voiceLauncher.launch(intent)
                            } catch (_: ActivityNotFoundException) {
                                scope.launch { snackbar.showSnackbar("Voice input isn't available on this device") }
                            }
                        },
                        onAttach = if (state.effectiveBackground) null else {
                            { imageLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                        },
                    )
                }
                StepCard(1, "Where", summary = null) {
                    Column {
                        if (state.connections.isEmpty()) {
                            NoMachines(onAddMachine)
                        } else {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.sm), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                                state.connections.forEach { c ->
                                    MachinePickChip(
                                        connection = c,
                                        link = state.links[c.id],
                                        selected = c.id == state.connectionId,
                                        enabled = !state.locked || c.id == state.connectionId,
                                        onClick = { vm.selectMachine(c.id) },
                                    )
                                }
                            }
                            if (state.locked) {
                                Spacer(Modifier.height(Space.sm))
                                Text("Continuing a session keeps its machine and folder.", style = MaterialTheme.typography.bodySmall, color = TetherTheme.colors.faint)
                            }
                        }
                        if (state.connectionId != null) {
                            Spacer(Modifier.height(Space.lg))
                            FolderStep(
                                state = state,
                                onPick = { haptics.tick(); vm.selectFolder(it) },
                                onBrowse = { browsing = true },
                                onRetry = vm::retryProjects,
                            )
                        }
                    }
                }

                if (!resuming) StepCard(0, "Run", summary = null) {
                    AgentKindSelector(
                        background = state.background,
                        onSelect = { bg -> if (bg != state.background) { haptics.tick(); vm.selectBackground(bg) } },
                    )
                }

                StepCard(3, "Model", summary = null) {
                    ModelStep(selected = state.model, onSelect = { vm.selectModel(it) })
                }

                StepCard(4, "Permissions", summary = null) {
                    if (state.effectiveBackground) {
                        Text(
                            "A background agent's requests show here and in Claude Code on your computer. Answer in either place.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TetherTheme.colors.info,
                            modifier = Modifier.padding(bottom = Space.sm),
                        )
                    }
                    ModeStep(
                        selected = state.mode,
                        onSelect = { m ->
                            if (m == PermissionMode.BYPASS && state.mode != PermissionMode.BYPASS) confirmBypass = true
                            else { haptics.tick(); vm.selectMode(m) }
                        },
                    )
                }

                Spacer(Modifier.height(Space.lg))
            }

            // ── Sticky footer ──
            Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).padding(horizontal = Space.gutter).padding(top = Space.sm, bottom = Space.md)) {
                AnimatedVisibility(visible = state.error != null, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                    StartErrorCard(state.error ?: "", onDismiss = vm::dismissError, modifier = Modifier.padding(bottom = Space.md))
                }
                PrimaryButton(
                    text = when {
                        state.starting -> if (resuming) "Resuming…" else "Starting…"
                        resuming -> "Continue session"
                        state.effectiveBackground -> "Start in background"
                        else -> "Start agent"
                    },
                    onClick = { vm.start() },
                    enabled = blocker == null,
                    loading = state.starting,
                    modifier = Modifier.fillMaxWidth(),
                )
                AnimatedContent(targetState = blocker, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "blocker") { b ->
                    Text(
                        b ?: footerSummary(state),
                        style = MaterialTheme.typography.labelSmall,
                        color = TetherTheme.colors.faint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth().padding(top = Space.sm),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }
        }

        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.safeDrawing).padding(bottom = 96.dp)) { data ->
            Snackbar(data, shape = RoundedCornerShape(14.dp), containerColor = MaterialTheme.colorScheme.inverseSurface, contentColor = MaterialTheme.colorScheme.inverseOnSurface)
        }
    }

    val conn = state.connection
    if (browsing && conn != null) {
        DirectoryBrowserSheet(
            connectionId = conn.id,
            machineName = conn.name,
            startPath = state.cwd,
            onDismiss = { browsing = false },
            onPick = { path -> browsing = false; vm.selectFolder(path) },
        )
    }

    state.trustPrompt?.let { dir ->
        AlertDialog(
            onDismissRequest = vm::dismissTrust,
            icon = { Icon(Icons.Rounded.Shield, null, tint = TetherTheme.colors.info) },
            title = { Text("Trust this folder?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                    Text(
                        "Claude Code hasn't been opened in this folder on ${conn?.name ?: "this machine"} yet, so it won't start a background agent here.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        prettyPath(dir, maxLen = 44),
                        style = TetherTheme.type.monoSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(TetherTheme.colors.codeBg)
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                    )
                    Text(
                        "Trusting marks it as trusted for Claude Code (the same as accepting its trust prompt on the computer): Claude may read files and run its tools there.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { haptics.confirm(); vm.confirmTrust() }) {
                    Text("Trust and start", fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = { TextButton(onClick = vm::dismissTrust) { Text("Cancel") } },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(24.dp),
        )
    }

    state.mcpPrompt?.let { p ->
        AlertDialog(
            onDismissRequest = vm::dismissMcp,
            icon = { Icon(Icons.Rounded.Extension, null, tint = TetherTheme.colors.info) },
            title = { Text(if (p.servers.size == 1) "Enable this MCP server?" else "Enable these MCP servers?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                    Text(
                        "This folder's .mcp.json lists MCP servers Claude Code hasn't been told about on ${conn?.name ?: "this machine"} yet. Claude would ask on the computer and wait there.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        p.servers.joinToString("\n"),
                        style = TetherTheme.type.monoSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(TetherTheme.colors.codeBg)
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                    )
                    Text(
                        "MCP servers may run code or access system resources. Your choice is saved for this folder, the same as answering Claude's prompt on the computer.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { haptics.confirm(); vm.answerMcp(enable = true) }) {
                    Text("Enable and start", fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = { TextButton(onClick = { vm.answerMcp(enable = false) }) { Text("Start without") } },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(24.dp),
        )
    }

    if (confirmBypass) {
        AlertDialog(
            onDismissRequest = { confirmBypass = false },
            icon = { Icon(Icons.Rounded.Warning, null, tint = TetherTheme.colors.warning) },
            title = { Text("Skip every permission prompt?") },
            text = {
                Text(
                    "In Bypass mode Claude runs commands and edits files on ${conn?.name ?: "this machine"} without asking you first. " +
                        "Only use it in a sandbox or a repository you can easily restore.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmBypass = false; haptics.confirm(); vm.selectMode(PermissionMode.BYPASS) }) {
                    Text("Use Bypass", color = TetherTheme.colors.warning, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = { TextButton(onClick = { confirmBypass = false }) { Text("Cancel") } },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(24.dp),
        )
    }
}

// ───────────────────────────── Live vs Background ─────────────────────────────

@Composable
private fun AgentKindSelector(background: Boolean, onSelect: (Boolean) -> Unit) {
    val c = TetherTheme.colors
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(c.subtleFill)
                .padding(4.dp)
                .selectableGroup(),
        ) {
            AgentKindOption(!background, Icons.Rounded.PhoneAndroid, "Live", { onSelect(false) }, Modifier.weight(1f))
            AgentKindOption(background, Icons.Rounded.Computer, "Background", { onSelect(true) }, Modifier.weight(1f))
        }
        AnimatedContent(background, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "kindDesc") { bg ->
            Text(
                if (bg) "Recommended. Runs as claude --bg: it's in “claude agents” on your computer too, and you can attach to it there."
                else "Streams here live, with images, rewind and branching. Shows in “claude agents” only while it runs.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Space.sm, start = 4.dp),
            )
        }
    }
}

@Composable
private fun AgentKindOption(
    selected: Boolean,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = TetherTheme.colors
    val bg by animateColorAsState(if (selected) (if (c.isDark) Color(0xFF4A4A45) else c.card) else Color.Transparent, label = "kindBg")
    val fg by animateColorAsState(if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant, label = "kindFg")
    Row(
        modifier
            .heightIn(min = 42.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = if (selected) c.clay else fg, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(title, style = MaterialTheme.typography.labelLarge, color = fg)
    }
}

private fun footerSummary(s: NewAgentUiState): String {
    val parts = buildList {
        if (s.effectiveBackground) add("Background")
        s.connection?.name?.let(::add)
        s.cwd?.let { add(projectName(it)) }
        add(FallbackModels.firstOrNull { it.value == s.model }?.displayName ?: prettyModel(s.model))
        add(s.mode.label)
    }
    return parts.joinToString("  ·  ")
}

// ───────────────────────────── Step scaffolding ─────────────────────────────

@Composable
private fun StepCard(number: Int, title: String, summary: String?, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = Space.sm)) {
        Row(Modifier.padding(start = 4.dp, bottom = Space.sm), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = TetherTheme.type.section, color = TetherTheme.colors.faint, modifier = Modifier.weight(1f))
            if (summary != null) {
                Text(summary, style = MaterialTheme.typography.labelMedium, color = TetherTheme.colors.faint, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        TetherCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(Space.lg)) { Column { content() } }
    }
}

/** The hero: a serif question and the big prompt card, like Claude's home composer. */
@Composable
private fun PromptHero(resuming: Boolean, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            if (resuming) "Pick up where you left off" else "What should Claude work on?",
            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Normal),
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(start = 4.dp, top = Space.sm, bottom = Space.lg),
        )
        content()
    }
}

// ───────────────────────────── Continuing ─────────────────────────────

@Composable
private fun ContinuingCard(r: ResumeInfo, onRetry: () -> Unit) {
    val info = TetherTheme.colors.info
    TetherCard(Modifier.fillMaxWidth(), color = info.copy(alpha = 0.07f), border = info.copy(alpha = 0.3f), contentPadding = PaddingValues(Space.lg)) {
        Row(verticalAlignment = Alignment.Top) {
            IconTile(Icons.Rounded.History, info, size = 40.dp)
            Spacer(Modifier.width(Space.md))
            Column(Modifier.weight(1f)) {
                Text("Continuing session", style = MaterialTheme.typography.labelMedium, color = info)
                Spacer(Modifier.height(4.dp))
                when (val s = r.session) {
                    Loadable.Loading -> {
                        SkeletonBlock(Modifier.fillMaxWidth(0.8f).height(18.dp))
                        Spacer(Modifier.height(6.dp))
                        SkeletonBlock(Modifier.fillMaxWidth(0.5f).height(11.dp))
                    }
                    is Loadable.Failed -> {
                        Text("Session ${r.sessionId.take(8)}", style = MaterialTheme.typography.titleMedium)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Couldn't load its details", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                            TextButton(onClick = onRetry) { Text("Retry") }
                        }
                    }
                    is Loadable.Ready -> {
                        val session = s.value
                        Text(
                            session?.title?.ifBlank { null } ?: "Session ${r.sessionId.take(8)}",
                            style = MaterialTheme.typography.headlineSmall.copy(fontSize = 19.sp, lineHeight = 24.sp),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (session != null) {
                            Spacer(Modifier.height(4.dp))
                            val meta = buildList {
                                add(projectName(session.cwd))
                                add(relativeTime(session.updatedAt))
                                if (session.messageCount > 0) add("${session.messageCount} messages")
                            }
                            Text(meta.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (session.recentlyActive) {
                                Spacer(Modifier.height(6.dp))
                                MiniBadge("active on desktop — both will write to it", TetherTheme.colors.warning)
                            }
                        }
                    }
                }
            }
        }
    }
}

// ───────────────────────────── Machine ─────────────────────────────

@Composable
private fun NoMachines(onAddMachine: () -> Unit) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconTile(Icons.Rounded.Laptop, TetherTheme.colors.clay, size = 40.dp)
            Spacer(Modifier.width(Space.md))
            Column(Modifier.weight(1f)) {
                Text("No machines yet", style = MaterialTheme.typography.titleSmall)
                Text("Agents run on your own computer over SSH.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(Space.md))
        PrimaryButton("Add a machine", onClick = onAddMachine, icon = Icons.Rounded.Add, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun MachinePickChip(connection: Connection, link: LinkState?, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val accent = accentColor(connection.accent)
    val shape = CircleShape
    val bg by animateColorAsState(if (selected) TetherTheme.colors.clay.copy(alpha = 0.12f) else TetherTheme.colors.subtleFill, label = "mbg")
    val border by animateColorAsState(if (selected) TetherTheme.colors.clay.copy(alpha = 0.45f) else Color.Transparent, label = "mborder")
    val look = linkLook(link)
    val haptics = rememberHaptics()
    Row(
        Modifier
            .heightIn(min = 44.dp)
            .clip(shape)
            .background(bg)
            .border(1.dp, border, shape)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = { haptics.tick(); onClick() })
            .padding(start = 8.dp, end = 12.dp, top = 6.dp, bottom = 6.dp)
            .alpha(if (enabled) 1f else 0.45f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MachineAvatar(connection.name, connection.accent, size = 28.dp, modifier = Modifier.clip(CircleShape))
        Spacer(Modifier.width(8.dp))
        Text(
            connection.name,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium),
            color = if (enabled) MaterialTheme.colorScheme.onSurface else TetherTheme.colors.faint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        StatusDot(look.color, pulsing = look.pulsing, size = 6.dp)
    }
}

// ───────────────────────────── Folder ─────────────────────────────

@Composable
private fun FolderStep(state: NewAgentUiState, onPick: (String) -> Unit, onBrowse: () -> Unit, onRetry: () -> Unit) {
    val cwd = state.cwd
    Column {
        // Chosen folder, big.
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .then(if (!state.locked) Modifier.clickable(onClickLabel = "Browse folders", onClick = onBrowse) else Modifier)
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconTile(Icons.Rounded.Folder, TetherTheme.colors.clay, size = 42.dp)
            Spacer(Modifier.width(Space.md))
            Column(Modifier.weight(1f)) {
                AnimatedContent(targetState = cwd, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "cwd") { path ->
                    if (path == null) {
                        if (state.resolvingCwd || state.projects is Loadable.Loading) {
                            Column {
                                SkeletonBlock(Modifier.fillMaxWidth(0.5f).height(20.dp))
                                Spacer(Modifier.height(6.dp))
                                SkeletonBlock(Modifier.fillMaxWidth(0.75f).height(11.dp))
                            }
                        } else {
                            Column {
                                Text("Choose a folder", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("Where should Claude work?", style = MaterialTheme.typography.bodySmall, color = TetherTheme.colors.faint)
                            }
                        }
                    } else {
                        Column {
                            Text(projectName(path), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(prettyPath(path, maxLen = 48), style = TetherTheme.type.monoSmall, color = TetherTheme.colors.faint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            if (state.cwdSuggested && cwd != null) {
                Spacer(Modifier.width(Space.sm))
                MiniBadge("suggested", TetherTheme.colors.faint)
            }
        }

        if (state.locked) return@Column

        Spacer(Modifier.height(Space.md))
        Text("Recent projects", style = MaterialTheme.typography.labelMedium, color = TetherTheme.colors.faint, modifier = Modifier.padding(start = 10.dp))
        Spacer(Modifier.height(Space.xs))
        when (val p = state.projects) {
            Loadable.Loading -> Column { repeat(3) { SkeletonListRow(Modifier.padding(horizontal = 0.dp)) } }
            is Loadable.Failed -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Couldn't load projects — ${p.message}",
                    style = MaterialTheme.typography.bodySmall,
                    color = TetherTheme.colors.danger,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onRetry) { Text("Retry") }
            }
            is Loadable.Ready -> {
                val recent = p.value.filter { it.exists }.take(5)
                if (recent.isEmpty()) {
                    Text(
                        "No Claude Code projects on this machine yet. Browse to any folder.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TetherTheme.colors.faint,
                        modifier = Modifier.padding(vertical = Space.sm),
                    )
                } else {
                    Column(Modifier.selectableGroup()) {
                        recent.forEach { project -> RecentProjectRow(project, selected = project.cwd == cwd, onClick = { onPick(project.cwd) }) }
                    }
                }
            }
        }
        Spacer(Modifier.height(Space.sm))
        SecondaryButton("Browse…", onClick = onBrowse, icon = Icons.Rounded.FolderOpen, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun RecentProjectRow(project: ProjectSummary, selected: Boolean, onClick: () -> Unit) {
    val bg by animateColorAsState(if (selected) TetherTheme.colors.clay.copy(alpha = 0.10f) else Color.Transparent, label = "rpbg")
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    projectName(project.cwd),
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                project.gitBranch?.let {
                    Spacer(Modifier.width(6.dp))
                    GitBranchBadge(it, Modifier.weight(1f, fill = false))
                }
            }
            Text(
                prettyPath(project.cwd) + "  ·  " + relativeTime(project.lastActiveAt),
                style = TetherTheme.type.monoSmall,
                color = TetherTheme.colors.faint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(Space.sm))
        AnimatedVisibility(visible = selected, enter = fadeIn(), exit = fadeOut()) {
            Icon(Icons.Rounded.Check, contentDescription = "Selected", tint = TetherTheme.colors.clay, modifier = Modifier.size(20.dp))
        }
    }
}

// ───────────────────────────── Model ─────────────────────────────

@Composable
private fun ModelStep(selected: String, onSelect: (String) -> Unit) {
    val options: List<ModelOption> = remember(selected) {
        if (FallbackModels.any { it.value == selected }) FallbackModels
        else FallbackModels + ModelOption(selected, prettyModel(selected), "Your last choice")
    }
    Column {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.sm), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
            options.forEach { m ->
                TagChip(m.displayName, selected = m.value == selected, onClick = { onSelect(m.value) })
            }
        }
        val desc = options.firstOrNull { it.value == selected }?.description.orEmpty()
        AnimatedContent(targetState = desc, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "modelDesc") { d ->
            Text(d, style = MaterialTheme.typography.bodySmall, color = TetherTheme.colors.faint, modifier = Modifier.padding(top = Space.sm))
        }
    }
}

// ───────────────────────────── Permission mode ─────────────────────────────

@Composable
private fun ModeStep(selected: PermissionMode, onSelect: (PermissionMode) -> Unit) {
    Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        PermissionMode.entries.forEach { m ->
            val isSel = m == selected
            val danger = m == PermissionMode.BYPASS
            val accent = if (danger) TetherTheme.colors.warning else TetherTheme.colors.clay
            val bg by animateColorAsState(if (isSel) accent.copy(alpha = 0.10f) else Color.Transparent, label = "modebg")
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(bg)
                    .selectable(selected = isSel, role = Role.RadioButton, onClick = { onSelect(m) })
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioDot(isSel, accent)
                Spacer(Modifier.width(Space.md))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            m.label,
                            style = MaterialTheme.typography.titleSmall,
                            color = if (danger) TetherTheme.colors.warning else MaterialTheme.colorScheme.onSurface,
                        )
                        if (danger) {
                            Spacer(Modifier.width(6.dp))
                            Icon(Icons.Rounded.Warning, null, tint = TetherTheme.colors.warning, modifier = Modifier.size(15.dp))
                        }
                    }
                    Text(m.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun RadioDot(selected: Boolean, color: Color) {
    val ring by animateColorAsState(if (selected) color else MaterialTheme.colorScheme.outline, label = "ring")
    Box(Modifier.size(20.dp).clip(CircleShape).border(2.dp, ring, CircleShape), contentAlignment = Alignment.Center) {
        AnimatedVisibility(visible = selected, enter = fadeIn(), exit = fadeOut()) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        }
    }
}

// ───────────────────────────── Prompt ─────────────────────────────

@Composable
private fun PromptStep(
    vm: NewAgentViewModel,
    state: NewAgentUiState,
    resuming: Boolean,
    canDictate: Boolean,
    onDictate: () -> Unit,
    onAttach: (() -> Unit)?,
) {
    var focused by remember { mutableStateOf(false) }
    val c = TetherTheme.colors
    val borderColor by animateColorAsState(if (focused) c.clay.copy(alpha = 0.55f) else c.composerBorder, label = "promptBorder")
    val shape = RoundedCornerShape(28.dp)
    val prompt = vm.prompt
    val commands by vm.commands.collectAsStateWithLifecycle()
    val slashMatches = remember(prompt.text, commands) { matchSlashCommands(prompt.text, commands) }
    Column {
        AnimatedVisibility(visible = slashMatches.isNotEmpty(), enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            SlashPopup(matches = slashMatches, onPick = { vm.insertCommand(it.name) }, modifier = Modifier.padding(bottom = Space.sm))
        }
        Column(
            Modifier
                .fillMaxWidth()
                .then(if (!c.isDark) Modifier.shadow(12.dp, shape, ambientColor = Color(0x33000000), spotColor = Color(0x1F000000)) else Modifier)
                .clip(shape)
                .background(c.composer)
                .border(1.dp, borderColor, shape),
        ) {
            Box(Modifier.fillMaxWidth().heightIn(min = 132.dp).padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 8.dp)) {
                if (prompt.text.isEmpty()) {
                    Text(
                        if (resuming) "Add a message, or start and type later…" else "How can I help you today?",
                        style = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp),
                        color = TetherTheme.colors.faint,
                    )
                }
                BasicTextField(
                    value = prompt,
                    onValueChange = vm::onPromptChange,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp, lineHeight = 25.sp, color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(TetherTheme.colors.clay),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    minLines = 4,
                    modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
                )
            }

            AnimatedVisibility(visible = state.images.isNotEmpty() || state.processingImages > 0) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(Space.sm),
                    modifier = Modifier.padding(bottom = Space.sm),
                ) {
                    items(state.images, key = { it.id }) { img ->
                        Box(Modifier.animateItem().size(68.dp)) {
                            Image(
                                bitmap = img.thumbnail,
                                contentDescription = img.attachment.name,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.padding(top = 6.dp, end = 6.dp).size(62.dp).clip(RoundedCornerShape(12.dp)).border(1.dp, TetherTheme.colors.hairline, RoundedCornerShape(12.dp)),
                            )
                            Box(
                                Modifier
                                    .align(Alignment.TopEnd)
                                    .size(22.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.inverseSurface)
                                    .clickable(onClickLabel = "Remove image") { vm.removeImage(img.id) },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(Icons.Rounded.Close, contentDescription = "Remove image", tint = MaterialTheme.colorScheme.inverseOnSurface, modifier = Modifier.size(14.dp))
                            }
                        }
                    }
                    if (state.processingImages > 0) {
                        items(state.processingImages, key = { "proc:$it" }) {
                            Box(Modifier.size(68.dp), contentAlignment = Alignment.BottomStart) {
                                Box(Modifier.size(62.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = TetherTheme.colors.clay)
                                }
                            }
                        }
                    }
                }
            }

            Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (onAttach != null) IconButton(onClick = onAttach, enabled = state.images.size + state.processingImages < NewAgentViewModel.MAX_IMAGES) {
                    Icon(Icons.Rounded.AddPhotoAlternate, contentDescription = "Attach image", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (canDictate) {
                    IconButton(onClick = onDictate) {
                        Icon(Icons.Rounded.Mic, contentDescription = "Dictate", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.weight(1f))
                val count = state.images.size
                Text(
                    when {
                        count > 0 -> "$count/${NewAgentViewModel.MAX_IMAGES} images"
                        prompt.text.isNotEmpty() -> "${prompt.text.length} chars"
                        else -> ""
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = TetherTheme.colors.faint,
                    modifier = Modifier.padding(end = Space.md),
                )
            }
        }

        AnimatedVisibility(visible = prompt.text.isBlank() && !resuming, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            FlowRow(
                Modifier.padding(top = Space.md),
                horizontalArrangement = Arrangement.spacedBy(Space.sm),
                verticalArrangement = Arrangement.spacedBy(Space.sm),
            ) {
                Suggestions.forEach { s ->
                    TagChip(s.text, icon = s.icon, onClick = { vm.applySuggestion(s.text) })
                }
            }
        }
    }
}

// ───────────────────────────── Errors ─────────────────────────────

@Composable
private fun StartErrorCard(message: String, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val danger = TetherTheme.colors.danger
    var expanded by remember { mutableStateOf(false) }
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(danger.copy(alpha = 0.09f))
            .border(1.dp, danger.copy(alpha = 0.3f), RoundedCornerShape(14.dp))
            .clickable(onClickLabel = "Show details") { expanded = !expanded }
            .padding(start = Space.md, top = Space.sm, bottom = Space.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.ErrorOutline, null, tint = danger, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text("Couldn't start the agent", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (expanded) 8 else 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, contentDescription = "Dismiss error", modifier = Modifier.size(18.dp)) }
    }
}
