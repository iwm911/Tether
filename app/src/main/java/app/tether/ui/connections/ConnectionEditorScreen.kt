package app.tether.ui.connections

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.NetworkCheck
import androidx.compose.material.icons.rounded.Password
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.tether.LocalAppContainer
import app.tether.core.SshKey
import app.tether.ui.components.EmptyState
import app.tether.ui.components.Hairline
import app.tether.ui.components.MachineAvatar
import app.tether.ui.components.PrimaryButton
import app.tether.ui.components.SecondaryButton
import app.tether.ui.components.SectionHeader
import app.tether.ui.components.TagChip
import app.tether.ui.components.TetherCard
import app.tether.ui.components.TetherTextField
import app.tether.ui.components.TetherTopBar
import app.tether.ui.components.rememberHaptics
import app.tether.ui.theme.Space
import app.tether.ui.theme.TetherTheme

@Composable
fun ConnectionEditorScreen(
    connectionId: String?,
    onBack: () -> Unit,
    onSaved: (String) -> Unit,
    onManageKeys: () -> Unit,
) {
    val container = LocalAppContainer.current
    val vm: ConnectionEditorViewModel = viewModel(key = "editor:${connectionId ?: "new"}") {
        ConnectionEditorViewModel(container, connectionId)
    }
    val s by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val haptics = rememberHaptics()
    LaunchedEffect(vm) { vm.messages.collect { snackbar.showSnackbar(it) } }

    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    val requestBack: () -> Unit = {
        if (s.dirty && !s.saving) {
            confirmDiscard = true
        } else {
            onBack()
        }
    }
    BackHandler(enabled = s.dirty && !s.saving) { confirmDiscard = true }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TetherTopBar(
                title = if (s.isEdit) "Edit machine" else "New machine",
                subtitle = if (s.isEdit && s.displayName.isNotEmpty()) s.displayName else null,
                onBack = requestBack,
                modifier = Modifier.statusBarsPadding().background(MaterialTheme.colorScheme.background),
            )
        },
        bottomBar = {
            if (!s.loading && !s.missing) {
                SaveBar(
                    ui = s,
                    onSave = { haptics.confirm(); vm.save(onSaved) },
                )
            }
        },
        snackbarHost = { TetherSnackbarHost(snackbar) },
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            when {
                s.loading -> EditorSkeleton()
                s.missing -> EmptyState(
                    icon = Icons.Rounded.Dns,
                    title = "Machine not found",
                    body = "It may have been removed from this phone.",
                    actionLabel = "Go back",
                    onAction = onBack,
                )
                else -> EditorForm(s, vm, onManageKeys)
            }
        }
    }

    if (s.importOpen) {
        ImportKeySheet(
            importing = s.importing,
            error = s.importError,
            onImport = vm::importKey,
            onDismiss = vm::closeImport,
        )
    }
    if (s.installOpen) {
        val key = s.selectedKey
        if (key != null) InstallKeyDialog(s, key, onInstall = vm::installKey, onDismiss = vm::closeInstall)
    }
    if (confirmDiscard) {
        ConfirmDialog(
            title = "Discard changes?",
            body = if (s.isEdit) "Your edits to this machine won't be saved." else "This machine won't be added.",
            confirmLabel = "Discard",
            danger = true,
            dismissLabel = "Keep editing",
            onConfirm = { confirmDiscard = false; onBack() },
            onDismiss = { confirmDiscard = false },
        )
    }
}

// ───────────────────────────── Form ─────────────────────────────

@Composable
private fun EditorForm(s: EditorUi, vm: ConnectionEditorViewModel, onManageKeys: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        PreviewHeader(s)
        Spacer(Modifier.height(Space.md))

        SectionHeader("Connection")
        Column(Modifier.padding(horizontal = Space.gutter), verticalArrangement = Arrangement.spacedBy(Space.md)) {
            TetherTextField(
                value = s.host,
                onValueChange = vm::setHost,
                modifier = Modifier.onFocusChanged { if (!it.isFocused) vm.commitHost() },
                label = "Host",
                placeholder = "devbox.local · 192.168.1.20",
                supporting = s.hostError ?: "Tip: paste user@host:port to fill everything at once",
                isError = s.hostError != null,
                mono = true,
                keyboardType = KeyboardType.Uri,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(Space.md)) {
                TetherTextField(
                    value = s.username,
                    onValueChange = vm::setUsername,
                    label = "Username",
                    placeholder = "me",
                    mono = true,
                    isError = s.usernameError != null,
                    supporting = s.usernameError,
                    modifier = Modifier.weight(1.7f),
                )
                TetherTextField(
                    value = s.port,
                    onValueChange = vm::setPort,
                    label = "Port",
                    mono = true,
                    keyboardType = KeyboardType.Number,
                    isError = s.portError != null,
                    supporting = if (s.portError != null) "1–65535" else null,
                    modifier = Modifier.weight(1f),
                )
            }
            TetherTextField(
                value = s.name,
                onValueChange = vm::setName,
                label = "Name",
                placeholder = s.host.trim().ifEmpty { "My laptop" },
                supporting = "Optional — defaults to the host",
            )
        }

        Spacer(Modifier.height(Space.xl))
        SectionHeader("Sign in")
        Column(Modifier.padding(horizontal = Space.gutter)) {
            SegmentedControl(
                options = listOf("Key", "Password"),
                icons = listOf(Icons.Rounded.Key, Icons.Rounded.Password),
                selectedIndex = if (s.auth == AuthKind.KEY) 0 else 1,
                onSelect = { vm.setAuth(if (it == 0) AuthKind.KEY else AuthKind.PASSWORD) },
            )
            Spacer(Modifier.height(Space.lg))
            AnimatedContent(
                targetState = s.auth,
                transitionSpec = {
                    val dir = if (targetState == AuthKind.PASSWORD) 1 else -1
                    (slideInHorizontally(tween(280)) { it / 6 * dir } + fadeIn(tween(220)))
                        .togetherWith(slideOutHorizontally(tween(220)) { -it / 6 * dir } + fadeOut(tween(160)))
                        .using(SizeTransform(clip = false))
                },
                label = "auth",
            ) { kind ->
                when (kind) {
                    AuthKind.KEY -> KeyAuthPanel(s, vm, onManageKeys)
                    AuthKind.PASSWORD -> PasswordAuthPanel(s, vm)
                }
            }
        }

        Spacer(Modifier.height(Space.xl))
        AdvancedSection(s, vm)

        Spacer(Modifier.height(Space.xl))
        SectionHeader("Check")
        Column(Modifier.padding(horizontal = Space.gutter)) {
            SecondaryButton(
                text = when {
                    s.test.running -> "Testing…"
                    s.test.started -> "Test again"
                    else -> "Test connection"
                },
                icon = Icons.Rounded.NetworkCheck,
                onClick = vm::test,
                enabled = s.canTest,
                modifier = Modifier.fillMaxWidth(),
            )
            AnimatedVisibility(
                visible = s.test.started,
                enter = fadeIn(tween(240)) + expandVertically(spring(stiffness = Spring.StiffnessMediumLow)),
                exit = fadeOut(tween(160)) + shrinkVertically(tween(220)),
            ) {
                Column {
                    Spacer(Modifier.height(Space.md))
                    TestChecklist(s.test)
                }
            }
            if (!s.test.started) {
                Text(
                    "Tether reaches the machine, verifies its identity, signs in and looks for Claude Code.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TetherTheme.colors.faint,
                    modifier = Modifier.padding(top = Space.sm, start = Space.xs, end = Space.xs),
                )
            }
        }
        Spacer(Modifier.height(Space.xxxl))
    }
}

@Composable
private fun PreviewHeader(s: EditorUi) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Space.gutter, vertical = Space.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MachineAvatar(name = s.displayName.ifEmpty { "New machine" }, accent = s.accent, size = 56.dp)
        Spacer(Modifier.width(Space.lg))
        Column(Modifier.weight(1f)) {
            AnimatedContent(
                targetState = s.displayName.ifEmpty { if (s.isEdit) "Machine" else "New machine" },
                transitionSpec = { fadeIn(tween(160)).togetherWith(fadeOut(tween(120))) },
                label = "pvname",
            ) {
                Text(it, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            val address = s.address
            Text(
                address.ifEmpty { "user@host" },
                style = TetherTheme.type.monoSmall,
                color = if (address.isEmpty()) TetherTheme.colors.faint.copy(alpha = 0.6f) else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ───────────────────────────── Auth panels ─────────────────────────────

@Composable
private fun KeyAuthPanel(s: EditorUi, vm: ConnectionEditorViewModel, onManageKeys: () -> Unit) {
    val colors = TetherTheme.colors
    Column(Modifier.fillMaxWidth()) {
        if (s.keys.isEmpty()) {
            TetherCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(Space.xl)) {
                Column {
                    Box(
                        Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(colors.clay.copy(alpha = 0.14f)),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Rounded.Key, null, tint = colors.clay, modifier = Modifier.size(22.dp)) }
                    Spacer(Modifier.height(Space.md))
                    Text("No keys on this phone yet", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(Space.xs))
                    Text(
                        "Generate an Ed25519 key here — the private half is encrypted on this phone and never leaves it. Or import one you already use.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(Space.lg))
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.md)) {
                        PrimaryButton("Generate key", onClick = vm::generateKey, loading = s.generating, icon = Icons.Rounded.Add, modifier = Modifier.weight(1.3f))
                        SecondaryButton("Import", onClick = vm::openImport, icon = Icons.Rounded.FileOpen, modifier = Modifier.weight(1f))
                    }
                }
            }
        } else {
            TetherCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(vertical = Space.xs)) {
                Column {
                    s.keys.forEach { key ->
                        KeyOptionRow(key, selected = key.id == s.keyId, onClick = { vm.selectKey(key.id) })
                    }
                    Hairline(Modifier.padding(horizontal = Space.lg))
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = Space.md, vertical = Space.sm),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Space.sm),
                    ) {
                        SmallAction(
                            text = if (s.generating) "Generating…" else "New key",
                            icon = Icons.Rounded.Add,
                            tint = colors.clay,
                            enabled = !s.generating,
                            onClick = vm::generateKey,
                        )
                        SmallAction("Import", Icons.Rounded.FileOpen, onClick = vm::openImport)
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = onManageKeys) {
                            Text("Manage", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            val selected = s.selectedKey
            AnimatedContent(
                targetState = selected,
                contentKey = { it?.id },
                transitionSpec = {
                    (fadeIn(tween(260)) + expandVertically(spring(stiffness = Spring.StiffnessMediumLow)))
                        .togetherWith(fadeOut(tween(140)))
                        .using(SizeTransform(clip = false))
                },
                label = "pubkey",
            ) { key ->
                if (key != null) {
                    Column {
                        Spacer(Modifier.height(Space.md))
                        PublicKeyCard(
                            key = key,
                            reveal = key.id == s.revealKeyId,
                            onInstall = vm::openInstall,
                            installEnabled = s.canInstall,
                        )
                        Text(
                            if (s.canInstall) "Add this public key to ~/.ssh/authorized_keys on the machine — or tap Install on server and Tether does it once with your password."
                            else "Fill in host and username to install this key on the server.",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.faint,
                            modifier = Modifier.padding(top = Space.sm, start = Space.xs, end = Space.xs),
                        )
                    }
                } else {
                    Text(
                        "Pick a key to sign in with.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.warning,
                        modifier = Modifier.padding(top = Space.md, start = Space.xs),
                    )
                }
            }
        }
    }
}

@Composable
private fun KeyOptionRow(key: SshKey, selected: Boolean, onClick: () -> Unit) {
    val colors = TetherTheme.colors
    val haptics = rememberHaptics()
    val ring by animateColorAsState(if (selected) colors.clay else colors.faint.copy(alpha = 0.6f), label = "ring")
    val dot by animateDpAsState(if (selected) 10.dp else 0.dp, spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMedium), label = "dot")
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton) { haptics.tick(); onClick() }
            .padding(horizontal = Space.lg, vertical = Space.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(22.dp).border(2.dp, ring, CircleShape), contentAlignment = Alignment.Center) {
            Box(Modifier.size(dot).background(colors.clay, CircleShape))
        }
        Spacer(Modifier.width(Space.md))
        Column(Modifier.weight(1f)) {
            Text(key.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${prettyAlgorithm(key.algorithm)} · ${shortFingerprint(key.fingerprint)}",
                style = TetherTheme.type.monoSmall,
                color = colors.faint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (key.hasPassphrase) {
            Spacer(Modifier.width(Space.sm))
            TagChip("Passphrase")
        }
    }
}

@Composable
private fun PasswordAuthPanel(s: EditorUi, vm: ConnectionEditorViewModel) {
    val colors = TetherTheme.colors
    var visible by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        TetherTextField(
            value = s.password,
            onValueChange = vm::setPassword,
            label = if (s.hasSavedPassword && s.password.isEmpty()) "Password · saved" else "Password",
            placeholder = if (s.hasSavedPassword) "••••• saved" else null,
            supporting = if (s.hasSavedPassword) "Leave empty to keep the saved password" else "Stored encrypted on this phone",
            password = !visible,
            mono = visible,
            trailing = {
                IconButton(onClick = { visible = !visible }) {
                    Icon(
                        if (visible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                        contentDescription = if (visible) "Hide password" else "Show password",
                    )
                }
            },
        )
        Spacer(Modifier.height(Space.md))
        TetherCard(
            Modifier.fillMaxWidth(),
            color = colors.clay.copy(alpha = 0.06f),
            border = colors.clay.copy(alpha = 0.22f),
            contentPadding = PaddingValues(start = Space.lg, end = Space.sm, top = Space.md, bottom = Space.md),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Key, null, tint = colors.clay, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(Space.md))
                Column(Modifier.weight(1f)) {
                    Text("Prefer a key?", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Keys are safer than passwords. Generate one, then Install on server uses this password once.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { vm.setAuth(AuthKind.KEY) }) { Text("Use a key", color = colors.clay) }
            }
        }
    }
}

// ───────────────────────────── Advanced ─────────────────────────────

@Composable
private fun AdvancedSection(s: EditorUi, vm: ConnectionEditorViewModel) {
    val colors = TetherTheme.colors
    val rot by animateFloatAsState(if (s.advancedOpen) 180f else 0f, spring(stiffness = Spring.StiffnessMediumLow), label = "adv")
    Column(Modifier.fillMaxWidth().animateContentSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button, onClickLabel = if (s.advancedOpen) "Collapse" else "Expand") { vm.toggleAdvanced() }
                .padding(horizontal = Space.gutter, vertical = Space.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Tune, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(Space.md))
            Column(Modifier.weight(1f)) {
                Text("Advanced", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Claude path, starting folder, colour",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(Icons.Rounded.ExpandMore, null, tint = colors.faint, modifier = Modifier.rotate(rot))
        }
        AnimatedVisibility(
            s.advancedOpen,
            enter = fadeIn(tween(220)) + expandVertically(spring(stiffness = Spring.StiffnessMediumLow)),
            exit = fadeOut(tween(140)) + shrinkVertically(tween(220)),
        ) {
            Column(Modifier.padding(horizontal = Space.gutter), verticalArrangement = Arrangement.spacedBy(Space.md)) {
                Spacer(Modifier.height(Space.xs))
                TetherTextField(
                    value = s.claudePath,
                    onValueChange = vm::setClaudePath,
                    label = "Claude Code binary",
                    placeholder = "Auto-detect",
                    supporting = "Leave empty to find it automatically (~/.local/bin, npm global, Homebrew…)",
                    mono = true,
                )
                TetherTextField(
                    value = s.defaultCwd,
                    onValueChange = vm::setDefaultCwd,
                    label = "Default directory",
                    placeholder = "~ (home)",
                    supporting = "Where new agents start unless you pick another folder",
                    mono = true,
                )
                Spacer(Modifier.height(Space.xs))
                Text("Accent", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                AccentSwatches(selected = s.accent, onSelect = vm::setAccent)
                Spacer(Modifier.height(Space.xs))
            }
        }
    }
}

// ───────────────────────────── Save bar, dialogs, skeleton ─────────────────────────────

@Composable
private fun SaveBar(ui: EditorUi, onSave: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .navigationBarsPadding()
            .imePadding(),
    ) {
        Hairline()
        Column(Modifier.padding(horizontal = Space.gutter, vertical = Space.md)) {
            AnimatedVisibility(ui.missingSummary != null, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                Text(
                    ui.missingSummary.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = TetherTheme.colors.faint,
                    modifier = Modifier.padding(bottom = Space.sm, start = Space.xs),
                )
            }
            PrimaryButton(
                text = if (ui.isEdit) "Save changes" else "Save machine",
                onClick = onSave,
                enabled = ui.canSave,
                loading = ui.saving,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun InstallKeyDialog(s: EditorUi, key: SshKey, onInstall: (String) -> Unit, onDismiss: () -> Unit) {
    var pw by rememberSaveable { mutableStateOf(s.password) }
    var visible by rememberSaveable { mutableStateOf(false) }
    val colors = TetherTheme.colors
    TetherDialog(
        onDismiss = { if (!s.installing) onDismiss() },
        title = "Install on server",
        icon = Icons.Rounded.Upload,
        body = "Tether signs in to ${s.address} once with this password and adds “${key.name}” to ~/.ssh/authorized_keys. The password isn't kept.",
        dismissOnOutside = !s.installing,
        content = {
            Spacer(Modifier.height(Space.lg))
            TetherTextField(
                value = pw,
                onValueChange = { pw = it },
                label = "Password for ${s.username.trim().ifEmpty { "user" }}",
                password = !visible,
                mono = visible,
                isError = s.installError != null,
                trailing = {
                    IconButton(onClick = { visible = !visible }) {
                        Icon(if (visible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, if (visible) "Hide password" else "Show password")
                    }
                },
            )
            AnimatedVisibility(s.installError != null, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                Text(
                    s.installError.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.danger,
                    modifier = Modifier.padding(top = Space.sm),
                )
            }
        },
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(Space.md)) {
            SecondaryButton("Cancel", onClick = onDismiss, enabled = !s.installing, modifier = Modifier.weight(1f))
            PrimaryButton(
                "Install",
                onClick = { onInstall(pw) },
                enabled = pw.isNotEmpty(),
                loading = s.installing,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun EditorSkeleton() {
    Column(Modifier.fillMaxWidth().padding(horizontal = Space.gutter, vertical = Space.sm), verticalArrangement = Arrangement.spacedBy(Space.md)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SkeletonBlock(Modifier.size(56.dp), RoundedCornerShape(18.dp))
            Spacer(Modifier.width(Space.lg))
            Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                SkeletonBlock(Modifier.width(160.dp).height(22.dp))
                SkeletonBlock(Modifier.width(120.dp).height(14.dp))
            }
        }
        Spacer(Modifier.height(Space.lg))
        repeat(3) { SkeletonBlock(Modifier.fillMaxWidth().height(56.dp)) }
        Spacer(Modifier.height(Space.lg))
        SkeletonBlock(Modifier.fillMaxWidth().height(48.dp))
        SkeletonBlock(Modifier.fillMaxWidth().height(120.dp), RoundedCornerShape(20.dp))
    }
}
