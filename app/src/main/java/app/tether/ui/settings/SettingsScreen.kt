package app.tether.ui.settings

import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.BrightnessAuto
import androidx.compose.material.icons.rounded.BrightnessHigh
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.NotificationsOff
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Policy
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Vibration
import androidx.compose.material.icons.rounded.ViewAgenda
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.tether.BuildConfig
import app.tether.LocalAppContainer
import app.tether.core.FallbackModels
import app.tether.core.KnownHost
import app.tether.core.PermissionMode
import app.tether.core.ThemeMode
import app.tether.ui.components.Hairline
import app.tether.ui.components.ListRow
import app.tether.ui.components.SectionHeader
import app.tether.ui.components.TetherTopBar
import app.tether.ui.components.ToggleRow
import app.tether.ui.components.rememberHaptics
import app.tether.ui.connections.ConfirmDialog
import app.tether.ui.connections.SegmentedControl
import app.tether.ui.connections.TetherSnackbarHost
import app.tether.ui.connections.prettyKeyType
import app.tether.ui.connections.shortFingerprint
import app.tether.ui.lock.AppLock
import app.tether.ui.lock.AuthOutcome
import app.tether.ui.lock.TetherMark
import app.tether.ui.lock.findFragmentActivity
import app.tether.ui.theme.Space
import app.tether.ui.theme.TetherTheme
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(onBack: () -> Unit, onOpenKeys: () -> Unit, onOpenMachines: () -> Unit) {
    val container = LocalAppContainer.current
    val vm: SettingsViewModel = viewModel { SettingsViewModel(container) }
    val s by vm.settings.collectAsStateWithLifecycle()
    val hosts by vm.knownHosts.collectAsStateWithLifecycle()
    val machineCount by vm.machineCount.collectAsStateWithLifecycle()
    val keyCount by vm.keyCount.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(vm) { vm.messages.collect { snackbar.showSnackbar(it) } }

    var notificationsAllowed by remember { mutableStateOf(NotificationManagerCompat.from(context).areNotificationsEnabled()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        notificationsAllowed = NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    var modelSheet by remember { mutableStateOf(false) }
    var modeSheet by remember { mutableStateOf(false) }
    var lockAfterSheet by remember { mutableStateOf(false) }
    var forgetting by remember { mutableStateOf<KnownHost?>(null) }
    var hostsOpen by remember { mutableStateOf(false) }
    val scroll = rememberScrollState()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = { TetherTopBar(title = if (scroll.value > 60) "Settings" else "", onBack = onBack, modifier = Modifier.statusBarsPadding()) },
        snackbarHost = { TetherSnackbarHost(snackbar, Modifier.navigationBarsPadding()) },
    ) { pad ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(pad)
                .verticalScroll(scroll)
                .navigationBarsPadding()
                .padding(bottom = Space.xxxl),
        ) {
            Column(Modifier.padding(horizontal = Space.gutter, vertical = Space.sm)) {
                Text("Settings", style = MaterialTheme.typography.headlineLarge)
            }
            Spacer(Modifier.height(Space.md))

            // ───────── Appearance ─────────
            SettingsGroup("Appearance") {
                Column(Modifier.padding(horizontal = Space.lg, vertical = Space.lg)) {
                    Text("Theme", style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(Space.md))
                    SegmentedControl(
                        options = listOf("System", "Dark", "Light"),
                        icons = listOf(Icons.Rounded.BrightnessAuto, Icons.Rounded.DarkMode, Icons.Rounded.LightMode),
                        selectedIndex = when (s.theme) { ThemeMode.SYSTEM -> 0; ThemeMode.DARK -> 1; ThemeMode.LIGHT -> 2 },
                        onSelect = { i -> vm.update { it.copy(theme = ThemeMode.entries[i]) } },
                    )
                }
                RowDivider()
                if (Build.VERSION.SDK_INT >= 31) {
                    ToggleRow(
                        title = "Dynamic colour",
                        subtitle = "Tint Tether with your wallpaper's colours",
                        icon = Icons.Rounded.Palette,
                        checked = s.dynamicColor,
                        onCheckedChange = { on -> vm.update { it.copy(dynamicColor = on) } },
                    )
                } else {
                    ListRow(title = "Dynamic colour", subtitle = "Needs Android 12 or newer", icon = Icons.Rounded.Palette)
                }
                RowDivider()
                CodeSizeRow(scale = s.codeFontScale, onCommit = { v -> vm.update { it.copy(codeFontScale = v) } })
                RowDivider()
                ToggleRow(
                    title = "Haptics",
                    subtitle = "Gentle ticks on send, approve and mode changes",
                    icon = Icons.Rounded.Vibration,
                    checked = s.haptics,
                    onCheckedChange = { on -> vm.update { it.copy(haptics = on) } },
                )
            }

            // ───────── Agents ─────────
            SettingsGroup("Agents") {
                val model = FallbackModels.firstOrNull { it.value == s.defaultModel }
                ListRow(
                    title = "Default model",
                    subtitle = model?.let { "${it.displayName} · ${it.description}" } ?: s.defaultModel,
                    icon = Icons.Rounded.SmartToy,
                    showChevron = true,
                    onClick = { modelSheet = true },
                )
                RowDivider()
                val mode = PermissionMode.fromCli(s.defaultPermissionMode) ?: PermissionMode.DEFAULT
                ListRow(
                    title = "Default permission mode",
                    subtitle = "${mode.label} · ${mode.description}",
                    icon = Icons.Rounded.Shield,
                    iconTint = if (mode == PermissionMode.BYPASS) TetherTheme.colors.danger else MaterialTheme.colorScheme.onSurfaceVariant,
                    showChevron = true,
                    onClick = { modeSheet = true },
                )
                RowDivider()
                ToggleRow(
                    title = "Show thinking",
                    subtitle = "Include Claude's thinking blocks in conversations",
                    icon = Icons.Rounded.Psychology,
                    checked = s.showThinking,
                    onCheckedChange = { on -> vm.update { it.copy(showThinking = on) } },
                )
                RowDivider()
                ToggleRow(
                    title = "Compact tool rows",
                    subtitle = "Collapse tool calls to one line until tapped",
                    icon = Icons.Rounded.ViewAgenda,
                    checked = s.compactTools,
                    onCheckedChange = { on -> vm.update { it.copy(compactTools = on) } },
                )
                RowDivider()
                ToggleRow(
                    title = "Keep screen on",
                    subtitle = "Don't let the screen turn off while a conversation is open",
                    icon = Icons.Rounded.BrightnessHigh,
                    checked = s.keepScreenOn,
                    onCheckedChange = { on -> vm.update { it.copy(keepScreenOn = on) } },
                )
            }

            // ───────── Notifications ─────────
            SettingsGroup("Notifications") {
                AnimatedVisibility(!notificationsAllowed, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                    Column {
                        ListRow(
                            title = "Notifications are off for Tether",
                            subtitle = "You won't hear when an agent needs you. Tap to allow them.",
                            icon = Icons.Rounded.NotificationsOff,
                            iconTint = TetherTheme.colors.warning,
                            showChevron = true,
                            onClick = {
                                val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                try { context.startActivity(intent) } catch (_: ActivityNotFoundException) { }
                            },
                        )
                        RowDivider()
                    }
                }
                ToggleRow(
                    title = "Permission requests",
                    subtitle = "Alert me when an agent needs approval — with Allow and Deny right in the notification",
                    icon = Icons.Rounded.NotificationsActive,
                    checked = s.notifyPermissions,
                    onCheckedChange = { on -> vm.update { it.copy(notifyPermissions = on) } },
                )
                RowDivider()
                ToggleRow(
                    title = "Turn complete",
                    subtitle = "Tell me when an agent finishes and it's my turn",
                    icon = Icons.Rounded.DoneAll,
                    checked = s.notifyCompletion,
                    onCheckedChange = { on -> vm.update { it.copy(notifyCompletion = on) } },
                )
                RowDivider()
                ToggleRow(
                    title = "New Tether versions",
                    subtitle = "Check for updates in the background and tell me when one is out",
                    icon = Icons.Rounded.SystemUpdate,
                    checked = s.notifyAppUpdates,
                    onCheckedChange = { on -> vm.update { it.copy(notifyAppUpdates = on) } },
                )
                RowDivider()
                ToggleRow(
                    title = "Background watch",
                    subtitle = "Keep watching running agents while Tether is closed. Shows a quiet ongoing notification.",
                    icon = Icons.Rounded.Sync,
                    checked = s.backgroundWatch,
                    onCheckedChange = { on -> vm.update { it.copy(backgroundWatch = on) } },
                )
            }

            // ───────── Connections ─────────
            SettingsGroup("Connections") {
                ToggleRow(
                    title = "Keep connections alive",
                    subtitle = "Like a terminal app: SSH sessions stay open in the background until you disconnect from the notification. Uses a little more battery.",
                    icon = Icons.Rounded.Sync,
                    checked = s.keepConnectionsAlive,
                    onCheckedChange = { on -> vm.update { it.copy(keepConnectionsAlive = on) } },
                )
                RowDivider()
                var unrestricted by remember { mutableStateOf(app.tether.service.BatteryOptimization.isUnrestricted(context)) }
                val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
                androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
                    val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
                        if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) unrestricted = app.tether.service.BatteryOptimization.isUnrestricted(context)
                    }
                    lifecycleOwner.lifecycle.addObserver(obs)
                    onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
                }
                ListRow(
                    title = "Battery",
                    subtitle = if (unrestricted) "Unrestricted — Android won't pause Tether's connections"
                    else "Optimised — Android may pause connections when the screen is off",
                    icon = Icons.Rounded.BatteryChargingFull,
                    onClick = if (unrestricted) null else ({ app.tether.service.BatteryOptimization.request(context) }),
                    trailing = if (unrestricted) null else ({ androidx.compose.material3.Text("Allow", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary) }),
                )
            }

            // ───────── Security ─────────
            SettingsGroup("Security") {
                val lockAvailable = remember(context) { AppLock.isAvailable(context) }
                ToggleRow(
                    title = "App lock",
                    subtitle = when {
                        s.biometricLock -> "Unlock with fingerprint, face or screen lock"
                        lockAvailable -> "Require fingerprint, face or screen lock to open Tether"
                        else -> AppLock.unavailableReason(context)
                    },
                    icon = Icons.Rounded.Fingerprint,
                    checked = s.biometricLock,
                    onCheckedChange = { on ->
                        if (!on) {
                            vm.update { it.copy(biometricLock = false) }
                        } else {
                            val act = context.findFragmentActivity()
                            when {
                                act == null -> vm.say("App lock isn't available here")
                                !AppLock.isAvailable(act) -> vm.say(AppLock.unavailableReason(act))
                                else -> AppLock.authenticate(act, title = "Turn on app lock", subtitle = "Confirm it's you") { outcome ->
                                    when (outcome) {
                                        AuthOutcome.Success -> {
                                            AppLock.markUnlocked()
                                            vm.update { it.copy(biometricLock = true) }
                                            vm.say("App lock is on")
                                        }
                                        AuthOutcome.Cancelled -> Unit
                                        is AuthOutcome.Error -> vm.say(outcome.message)
                                    }
                                }
                            }
                        }
                    },
                )
                if (s.biometricLock) {
                    RowDivider()
                    ListRow(
                        title = "Lock after",
                        subtitle = if (s.lockAfterSeconds == 0) "As soon as you leave Tether" else AppLock.lockAfterLabel(s.lockAfterSeconds) + " away",
                        icon = Icons.Rounded.Timer,
                        showChevron = true,
                        onClick = { lockAfterSheet = true },
                    )
                }
                if (container.secrets.requireUnlockSupported) {
                    RowDivider()
                    var keysNeedUnlock by remember { mutableStateOf(container.secrets.requireUnlock) }
                    var switching by remember { mutableStateOf(false) }
                    val keyScope = rememberCoroutineScope()
                    ToggleRow(
                        title = "Keys only while unlocked",
                        subtitle = if (keysNeedUnlock) "SSH keys and passwords can't be read while the phone is locked. Open connections stay up; new ones wait for unlock."
                        else "Lock SSH keys and passwords whenever the phone is locked",
                        icon = Icons.Rounded.Key,
                        checked = keysNeedUnlock,
                        onCheckedChange = { on ->
                            if (switching) return@ToggleRow
                            switching = true
                            keyScope.launch {
                                val result = withContext(Dispatchers.IO) { runCatching { container.secrets.setRequireUnlock(on) } }
                                switching = false
                                result.onSuccess {
                                    keysNeedUnlock = on
                                    vm.say(if (on) "Keys now lock with your phone" else "Keys no longer lock with your phone")
                                }.onFailure { vm.say("Couldn't change that: ${it.message ?: "try again"}") }
                            }
                        },
                    )
                }
                RowDivider()
                val toggleHosts: (() -> Unit)? = if (hosts.isNotEmpty()) {
                    { hostsOpen = !hostsOpen }
                } else {
                    null
                }
                ListRow(
                    title = "Trusted hosts",
                    subtitle = when (hosts.size) {
                        0 -> "Machines appear here after you verify them"
                        1 -> "1 host key"
                        else -> "${hosts.size} host keys"
                    },
                    icon = Icons.Rounded.Security,
                    onClick = toggleHosts,
                    trailing = {
                        if (hosts.isNotEmpty()) {
                            Text(
                                if (hostsOpen) "Hide" else "Show",
                                style = MaterialTheme.typography.labelLarge,
                                color = TetherTheme.colors.clay,
                            )
                        }
                    },
                )
                AnimatedVisibility(hostsOpen && hosts.isNotEmpty(), enter = fadeIn() + expandVertically(spring(stiffness = Spring.StiffnessMediumLow)), exit = fadeOut() + shrinkVertically()) {
                    Column(Modifier.padding(bottom = Space.sm)) {
                        hosts.forEach { h -> KnownHostRow(h, onRemove = { forgetting = h }) }
                    }
                }
            }

            // ───────── Privacy ─────────
            if (container.analytics.available) {
                SettingsGroup("Privacy") {
                    ToggleRow(
                        title = "Share anonymous usage stats",
                        subtitle = "Counts like app opens and agents started. Never machines, prompts or code.",
                        icon = Icons.Rounded.Insights,
                        checked = s.analyticsEnabled,
                        onCheckedChange = { on -> vm.update { it.copy(analyticsEnabled = on, analyticsNoticeSeen = true) } },
                    )
                    RowDivider()
                    val uri = LocalUriHandler.current
                    ListRow(
                        title = "What's shared",
                        subtitle = "Exactly what Tether sends, and what it never does",
                        icon = Icons.Rounded.Policy,
                        showChevron = true,
                        onClick = { uri.openUri(app.tether.ui.home.PrivacyUrl) },
                    )
                }
            }

            // ───────── Machines & keys ─────────
            SettingsGroup("Machines & keys") {
                ListRow(
                    title = "Machines",
                    subtitle = when (machineCount) { 0 -> "None yet"; 1 -> "1 machine"; else -> "$machineCount machines" },
                    icon = Icons.Rounded.Dns,
                    showChevron = true,
                    onClick = onOpenMachines,
                )
                RowDivider()
                ListRow(
                    title = "SSH keys",
                    subtitle = when (keyCount) { 0 -> "None yet"; 1 -> "1 key"; else -> "$keyCount keys" },
                    icon = Icons.Rounded.Key,
                    showChevron = true,
                    onClick = onOpenKeys,
                )
            }

            // ───────── About ─────────
            SectionHeader("About")
            app.tether.ui.update.UpdateSettingsRow(BuildConfig.VERSION_NAME)
            AboutBlock()
        }
    }

    if (modelSheet) {
        PickerSheet(
            title = "Default model",
            subtitle = "Used for new agents. You can switch models any time inside a conversation.",
            options = FallbackModels.map { PickerOption(it.value, it.displayName, it.description) },
            selected = s.defaultModel,
            onSelect = { v -> vm.update { it.copy(defaultModel = v) }; modelSheet = false },
            onDismiss = { modelSheet = false },
        )
    }
    if (lockAfterSheet) {
        PickerSheet(
            title = "Lock after",
            subtitle = "How long you can be away before Tether asks to unlock again. Screens Tether opens itself, like the photo picker, don't count.",
            options = AppLock.LOCK_AFTER_CHOICES.map {
                PickerOption(
                    it.toString(),
                    AppLock.lockAfterLabel(it),
                    when (it) {
                        0 -> "Every time you leave the app or turn off the screen"
                        else -> "Quick switches to other apps don't lock"
                    },
                )
            },
            selected = s.lockAfterSeconds.toString(),
            onSelect = { v -> vm.update { it.copy(lockAfterSeconds = v.toInt()) }; lockAfterSheet = false },
            onDismiss = { lockAfterSheet = false },
        )
    }
    if (modeSheet) {
        PickerSheet(
            title = "Default permission mode",
            subtitle = "How much a new agent may do before asking. Tap the mode chip in a conversation to change it live.",
            options = PermissionMode.entries.map {
                PickerOption(it.cli, it.label, it.description, danger = it == PermissionMode.BYPASS)
            },
            selected = s.defaultPermissionMode,
            onSelect = { v -> vm.update { it.copy(defaultPermissionMode = v) }; modeSheet = false },
            onDismiss = { modeSheet = false },
        )
    }
    forgetting?.let { h ->
        ConfirmDialog(
            title = "Forget ${h.host}?",
            body = "Tether will ask you to verify this machine's fingerprint again the next time it connects to ${h.host}:${h.port}.",
            confirmLabel = "Forget",
            danger = true,
            icon = Icons.Rounded.DeleteOutline,
            onConfirm = { forgetting = null; vm.removeHost(h) },
            onDismiss = { forgetting = null },
        )
    }
}

// ───────────────────────────── Building blocks ─────────────────────────────

@Composable
private fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(24.dp)
    SectionHeader(title)
    Column(
        Modifier
            .padding(horizontal = Space.gutter)
            .fillMaxWidth()
            .clip(shape)
            .background(TetherTheme.colors.card)
            .then(if (TetherTheme.colors.cardBorder.alpha > 0f) Modifier.border(1.dp, TetherTheme.colors.cardBorder, shape) else Modifier)
            .padding(vertical = Space.xs),
        content = content,
    )
    Spacer(Modifier.height(Space.lg))
}

@Composable
private fun RowDivider() {
    Hairline(Modifier.padding(horizontal = Space.xl))
}

@Composable
private fun CodeSizeRow(scale: Float, onCommit: (Float) -> Unit) {
    val colors = TetherTheme.colors
    var value by remember(scale) { mutableFloatStateOf(scale.coerceIn(0.8f, 1.4f)) }
    val haptics = rememberHaptics()
    Column(Modifier.fillMaxWidth().padding(horizontal = Space.lg, vertical = Space.lg)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Code text size", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text("${(value * 100).roundToInt()}%", style = TetherTheme.type.mono, color = colors.clay)
        }
        Spacer(Modifier.height(Space.md))
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(colors.codeBg)
                .border(1.dp, colors.hairline, RoundedCornerShape(14.dp))
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            Text(
                codeSample(colors.codeText, colors.synKeyword, colors.synString, colors.synComment, colors.synType, colors.synNumber),
                style = TetherTheme.type.mono.copy(
                    fontSize = (13f * value).sp,
                    lineHeight = (19f * value).sp,
                ),
                softWrap = false,
            )
        }
        Spacer(Modifier.height(Space.sm))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("A", style = TetherTheme.type.monoSmall.copy(fontSize = 11.sp), color = colors.faint)
            Slider(
                value = value,
                onValueChange = { v ->
                    val snapped = (v * 10f).roundToInt() / 10f
                    if (snapped != value) haptics.tick()
                    value = snapped
                },
                onValueChangeFinished = { onCommit(value) },
                valueRange = 0.8f..1.4f,
                steps = 5,
                colors = SliderDefaults.colors(
                    thumbColor = colors.clay,
                    activeTrackColor = colors.clay,
                    inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    activeTickColor = Color.Transparent,
                    inactiveTickColor = Color.Transparent,
                ),
                modifier = Modifier.weight(1f).padding(horizontal = Space.sm),
            )
            Text("A", style = TetherTheme.type.mono.copy(fontSize = 17.sp), color = colors.faint)
        }
    }
}

private fun codeSample(base: Color, kw: Color, str: Color, comment: Color, type: Color, num: Color): AnnotatedString =
    buildAnnotatedString {
        withStyle(SpanStyle(color = comment)) { append("// the agent's last edit\n") }
        withStyle(SpanStyle(color = kw)) { append("fun ") }
        withStyle(SpanStyle(color = base)) { append("retry(") }
        withStyle(SpanStyle(color = base)) { append("times: ") }
        withStyle(SpanStyle(color = type)) { append("Int") }
        withStyle(SpanStyle(color = base)) { append(" = ") }
        withStyle(SpanStyle(color = num)) { append("3") }
        withStyle(SpanStyle(color = base)) { append(") {\n    log(") }
        withStyle(SpanStyle(color = str)) { append("\"tethered ✓\"") }
        withStyle(SpanStyle(color = base)) { append(")\n}") }
    }

@Composable
private fun KnownHostRow(h: KnownHost, onRemove: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 58.dp, end = Space.xs, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(vertical = Space.sm)) {
            Text(
                if (h.port == 22) h.host else "${h.host}:${h.port}",
                style = TetherTheme.type.mono,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${prettyKeyType(h.keyType)} · ${shortFingerprint(h.fingerprint)}",
                style = TetherTheme.type.monoSmall,
                color = TetherTheme.colors.faint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onRemove) {
            Icon(Icons.Rounded.DeleteOutline, contentDescription = "Forget ${h.host}", tint = TetherTheme.colors.faint)
        }
    }
}

@Composable
private fun AboutBlock() {
    val colors = TetherTheme.colors
    Column(
        Modifier.fillMaxWidth().padding(horizontal = Space.gutter, vertical = Space.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        TetherMark(size = 56.dp)
        Spacer(Modifier.height(Space.md))
        Text("Tether", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            style = TetherTheme.type.monoSmall,
            color = colors.faint,
        )
        Spacer(Modifier.height(Space.md))
        Text(
            "Tether is an independent client for Claude Code. It connects to your own machines over SSH — your code and conversations stay there.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

// ───────────────────────────── Picker sheet ─────────────────────────────

private data class PickerOption(val value: String, val title: String, val description: String, val danger: Boolean = false)

@Composable
private fun PickerSheet(
    title: String,
    subtitle: String,
    options: List<PickerOption>,
    selected: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val haptics = rememberHaptics()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = Space.xl),
        ) {
            Column(Modifier.padding(horizontal = Space.gutter)) {
                Text(title, style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(Space.xs))
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(Space.md))
            options.forEach { o ->
                PickerRow(o, selected = o.value == selected, onClick = { haptics.tick(); onSelect(o.value) })
            }
        }
    }
}

@Composable
private fun PickerRow(o: PickerOption, selected: Boolean, onClick: () -> Unit) {
    val colors = TetherTheme.colors
    val accent = if (o.danger) colors.danger else colors.clay
    val bg by animateColorAsState(if (selected) accent.copy(alpha = 0.09f) else Color.Transparent, label = "pickbg")
    val ring by animateColorAsState(if (selected) accent else colors.faint.copy(alpha = 0.6f), label = "pickring")
    val dot by animateDpAsState(if (selected) 10.dp else 0.dp, spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMedium), label = "pickdot")
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Space.md, vertical = 2.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(bg)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = Space.md, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(22.dp).border(2.dp, ring, CircleShape), contentAlignment = Alignment.Center) {
            Box(Modifier.size(dot).background(accent, CircleShape))
        }
        Spacer(Modifier.width(Space.lg))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.title, style = MaterialTheme.typography.bodyLarge, color = if (o.danger) colors.danger else MaterialTheme.colorScheme.onSurface)
                if (o.danger) {
                    Spacer(Modifier.width(6.dp))
                    Icon(Icons.Rounded.WarningAmber, null, tint = colors.danger, modifier = Modifier.size(16.dp))
                }
            }
            Text(o.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
