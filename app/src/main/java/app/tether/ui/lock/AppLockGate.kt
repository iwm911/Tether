package app.tether.ui.lock

import androidx.compose.material3.Icon

import androidx.compose.foundation.layout.size

import androidx.compose.foundation.layout.requiredSize

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tether.LocalAppContainer
import app.tether.ui.components.PrimaryButton
import app.tether.ui.components.SecondaryButton
import app.tether.ui.theme.Space
import app.tether.ui.theme.TetherTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Wraps the whole app. When Settings › Biometric lock is on, the content is blurred behind a
 * branded lock screen until the user passes BiometricPrompt (biometric or device credential).
 * Locks on every cold start and when the user returns after Settings › Lock after; never when off.
 */
@Composable
fun AppLockGate(content: @Composable () -> Unit) {
    val container = LocalAppContainer.current
    val settings by container.settings.settings.collectAsStateWithLifecycle()
    val unlocked by AppLock.unlocked.collectAsStateWithLifecycle()
    val generation by AppLock.generation.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = remember(context) { context.findFragmentActivity() }
    val scope = rememberCoroutineScope()

    val lockEnabled = settings.biometricLock
    val locked = lockEnabled && !unlocked

    // With the lock on, keep agents out of the Recents thumbnail (API 33+). Screenshots and screen
    // recording stay allowed: no FLAG_SECURE.
    LaunchedEffect(lockEnabled, activity) {
        if (activity != null && Build.VERSION.SDK_INT >= 33) activity.setRecentsScreenshotEnabled(!lockEnabled)
    }

    var error by remember { mutableStateOf<String?>(null) }
    var unavailable by remember { mutableStateOf(false) }
    var prompting by remember { mutableStateOf(false) }

    fun prompt() {
        val act = activity ?: return
        if (prompting) return
        if (!AppLock.isAvailable(act)) {
            unavailable = true
            error = AppLock.unavailableReason(act)
            return
        }
        prompting = true
        error = null
        AppLock.authenticate(act, title = "Unlock Tether", subtitle = "Confirm it's you to see your agents") { outcome ->
            prompting = false
            when (outcome) {
                AuthOutcome.Success -> { error = null; unavailable = false; AppLock.markUnlocked() }
                AuthOutcome.Cancelled -> Unit
                is AuthOutcome.Error -> { error = outcome.message; unavailable = outcome.unavailable }
            }
        }
    }

    // Auto-prompt once per lock event, as soon as the activity is resumed (prompt needs a live FragmentManager).
    LaunchedEffect(locked, generation) {
        if (!locked) return@LaunchedEffect
        val act = activity ?: return@LaunchedEffect
        act.lifecycle.currentStateFlow.first { it.isAtLeast(Lifecycle.State.RESUMED) }
        delay(280)
        if (AppLock.unlocked.value) return@LaunchedEffect
        prompt()
    }

    val blur by animateDpAsState(if (locked) 28.dp else 0.dp, tween(360), label = "lockblur")

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .then(if (blur > 0.dp) Modifier.blur(blur) else Modifier)
                .then(if (locked) Modifier.clearAndSetSemantics { } else Modifier),
        ) {
            content()
        }
        // The lock screen lives in its own full-screen dialog window: dialogs and sheets are separate
        // windows too, so an in-window overlay could end up *under* one left open before a re-lock.
        val visibility = remember { MutableTransitionState(false) }
        visibility.targetState = locked
        // Windows opened after the lock (e.g. a dialog triggered by a late network result) stack above
        // it. When the lock loses focus to one, re-create it so it's on top again.
        var raise by remember { mutableIntStateOf(0) }
        // One raise per focus loss: the notification shade or a system dialog also take focus.
        var awaitingFocus by remember { mutableStateOf(false) }
        if (visibility.currentState || visibility.targetState) key(raise) {
            Dialog(
                onDismissRequest = { activity?.moveTaskToBack(true) },
                properties = DialogProperties(
                    dismissOnBackPress = true,
                    dismissOnClickOutside = false,
                    usePlatformDefaultWidth = false,
                    decorFitsSystemWindows = false,
                ),
            ) {
                // No platform dim: the lock screen paints its own calm, full-bleed surface.
                val dialogWindow = (androidx.compose.ui.platform.LocalView.current.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window
                androidx.compose.runtime.SideEffect { dialogWindow?.setDimAmount(0f) }
                val focused = LocalWindowInfo.current.isWindowFocused
                LaunchedEffect(focused, prompting, locked) {
                    if (focused) { awaitingFocus = false; return@LaunchedEffect }
                    if (prompting || !locked || awaitingFocus) return@LaunchedEffect
                    delay(250)
                    val resumed = activity?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) == true
                    if (resumed && !prompting && !AppLock.unlocked.value) {
                        awaitingFocus = true
                        raise++
                    }
                }
                AnimatedVisibility(visibleState = visibility, enter = fadeIn(tween(200)), exit = fadeOut(tween(320))) {
                    LockScreen(
                        error = error,
                        unavailable = unavailable,
                        prompting = prompting,
                        onUnlock = { prompt() },
                        onDisableLock = {
                            AppLock.markUnlocked()
                            unavailable = false
                            error = null
                            scope.launch { container.settings.update { it.copy(biometricLock = false) } }
                        },
                    )
                }
            }
        }
        if (locked) {
            BackHandler { activity?.moveTaskToBack(true) }
        }
    }
}

@Composable
private fun LockScreen(
    error: String?,
    unavailable: Boolean,
    prompting: Boolean,
    onUnlock: () -> Unit,
    onDisableLock: () -> Unit,
) {
    val colors = TetherTheme.colors
    val bg = MaterialTheme.colorScheme.background
    Box(
        Modifier
            .fillMaxSize()
            .background(if (Build.VERSION.SDK_INT >= 31) bg.copy(alpha = 0.94f) else bg)
            // Swallow every touch so nothing underneath can be operated while locked.
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent().changes.forEach { it.consume() }
                    }
                }
            }
            .windowInsetsPadding(WindowInsets.safeDrawing),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.widthIn(max = 420.dp).fillMaxWidth().padding(horizontal = Space.xxl),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            AnimatedVisibility(true, enter = scaleIn(initialScale = 0.85f) + fadeIn()) {
                Box(Modifier.size(72.dp), contentAlignment = Alignment.Center) {
                    Icon(
                        androidx.compose.ui.res.painterResource(app.tether.R.drawable.ic_launcher_foreground),
                        contentDescription = null,
                        tint = androidx.compose.ui.graphics.Color.Unspecified,
                        modifier = Modifier.requiredSize(148.dp),
                    )
                }
            }
            Spacer(Modifier.height(Space.xxl))
            Text("Tether is locked", style = MaterialTheme.typography.headlineMedium.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Normal), color = MaterialTheme.colorScheme.onBackground, textAlign = TextAlign.Center)
            Spacer(Modifier.height(Space.sm))
            Text(
                "Your agents keep running. Unlock to check in on them.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(Space.xxl))
            PrimaryButton(
                "Unlock Tether",
                onClick = onUnlock,
                icon = Icons.Rounded.Fingerprint,
                loading = prompting,
                modifier = Modifier.fillMaxWidth(),
            )
            AnimatedVisibility(error != null, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                Text(
                    error.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.danger,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = Space.md),
                )
            }
            AnimatedVisibility(unavailable, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Spacer(Modifier.height(Space.lg))
                    Text(
                        "This device no longer has a screen lock, so Tether can't confirm it's you.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(Space.md))
                    SecondaryButton("Continue and turn off app lock", onClick = onDisableLock, modifier = Modifier.fillMaxWidth())
                }
            }
            Spacer(Modifier.height(Space.xl))
            Text(
                "Fingerprint, face or your screen lock",
                style = MaterialTheme.typography.labelMedium,
                color = colors.faint,
            )
        }
    }
}
