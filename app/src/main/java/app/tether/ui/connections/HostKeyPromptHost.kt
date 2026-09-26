package app.tether.ui.connections

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.GppBad
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tether.LocalAppContainer
import app.tether.core.HostKeyPrompt
import app.tether.ui.components.PrimaryButton
import app.tether.ui.components.SecondaryButton
import app.tether.ui.components.rememberHaptics
import app.tether.ui.lock.AppLock
import app.tether.ui.theme.Space
import app.tether.ui.theme.TetherTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Root-level host for SSH host-key trust questions (TOFU). Hosted once above the nav graph;
 * whenever the SSH layer asks, a Tether-styled dialog appears and the answer goes back on the bus.
 */
@Composable
fun HostKeyPromptHost() {
    val container = LocalAppContainer.current
    val prompt by container.hostKeyPrompts.pending.collectAsStateWithLifecycle()
    val settings by container.settings.settings.collectAsStateWithLifecycle()
    val unlocked by AppLock.unlocked.collectAsStateWithLifecycle()
    // Never ask over the app lock; the SSH layer's own timeout declines if the user doesn't unlock in time.
    if (settings.biometricLock && !unlocked) return
    val p = prompt ?: return
    key(p.id) {
        HostKeyDialog(p) { trust -> container.hostKeyPrompts.answer(p.id, trust) }
    }
}

internal fun prettyKeyType(t: String): String = when {
    t.contains("ed25519", ignoreCase = true) -> "Ed25519"
    t.contains("nistp256") -> "ECDSA P-256"
    t.contains("nistp384") -> "ECDSA P-384"
    t.contains("nistp521") -> "ECDSA P-521"
    t.contains("ecdsa", ignoreCase = true) -> "ECDSA"
    t.contains("rsa", ignoreCase = true) -> "RSA"
    t.contains("dss", ignoreCase = true) -> "DSA"
    else -> t
}

private fun hostLabel(p: HostKeyPrompt): String {
    val h = if (p.host.contains(':')) "[${p.host}]" else p.host
    return if (p.port == 22) h else "$h:${p.port}"
}

@Composable
private fun HostKeyDialog(p: HostKeyPrompt, onAnswer: (Boolean) -> Unit) {
    val colors = TetherTheme.colors
    val changed = p.previousFingerprint != null
    var answered by remember { mutableStateOf(false) }
    val answer: (Boolean) -> Unit = { trust -> if (!answered) { answered = true; onAnswer(trust) } }
    val tone = if (changed) colors.danger else colors.clay
    val haptics = rememberHaptics()
    LaunchedEffect(changed) { if (changed) haptics.confirm() }

    TetherDialog(
        onDismiss = { answer(false) },
        title = if (changed) "Host key changed" else "Trust this machine?",
        eyebrow = if (changed) "Security warning" else "First connection",
        icon = if (changed) Icons.Rounded.GppBad else Icons.Rounded.Shield,
        tone = tone,
        border = if (changed) colors.danger.copy(alpha = 0.55f) else colors.hairline,
        dismissOnOutside = false,
        body = if (changed) {
            "The key presented by ${hostLabel(p)} is different from the one you trusted before. This could be an attack — someone may be intercepting the connection. Only continue if you know the machine was reinstalled or its SSH keys were rotated."
        } else {
            "Tether hasn't connected to ${hostLabel(p)} before. Check that this fingerprint matches the machine's host key, then trust it — you'll only be asked again if it changes."
        },
        content = {
            Spacer(Modifier.height(Space.lg))
            HostKeyFacts(p, changed)
            if (!changed) {
                Spacer(Modifier.height(Space.md))
                HowToVerify()
            }
        },
    ) {
        if (changed) {
            val cancelFocus = remember { FocusRequester() }
            LaunchedEffect(Unit) {
                delay(120)
                runCatching { cancelFocus.requestFocus() }
            }
            Column(verticalArrangement = Arrangement.spacedBy(Space.md)) {
                PrimaryButton(
                    "Cancel — don't connect",
                    onClick = { answer(false) },
                    modifier = Modifier.fillMaxWidth().focusRequester(cancelFocus),
                    color = MaterialTheme.colorScheme.onSurface,
                    contentColor = MaterialTheme.colorScheme.surface,
                )
                HoldToConfirm(
                    label = "Hold to trust the new key",
                    color = colors.danger,
                    onConfirmed = { answer(true) },
                )
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(Space.md)) {
                SecondaryButton("Cancel", onClick = { answer(false) }, modifier = Modifier.weight(1f))
                PrimaryButton("Trust", onClick = { answer(true) }, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun HostKeyFacts(p: HostKeyPrompt, changed: Boolean) {
    val colors = TetherTheme.colors
    val shape = RoundedCornerShape(18.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.codeBg)
            .border(1.dp, if (changed) colors.danger.copy(alpha = 0.3f) else colors.hairline, shape)
            .padding(Space.lg),
    ) {
        FactLine("Host", hostLabel(p))
        Spacer(Modifier.height(Space.sm))
        FactLine("Key type", prettyKeyType(p.keyType))
        Spacer(Modifier.height(Space.md))
        if (changed) {
            Text("PREVIOUSLY TRUSTED", style = TetherTheme.type.eyebrow, color = colors.faint)
            Spacer(Modifier.height(6.dp))
            FingerprintBlock(p.previousFingerprint.orEmpty(), color = colors.faint, struck = true)
            Spacer(Modifier.height(Space.md))
            Text("NOW PRESENTED", style = TetherTheme.type.eyebrow, color = colors.danger)
            Spacer(Modifier.height(6.dp))
            FingerprintBlock(p.fingerprint, color = colors.danger)
        } else {
            FingerprintBlock(p.fingerprint)
        }
    }
}

@Composable
private fun FactLine(label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = TetherTheme.colors.faint, modifier = Modifier.width(76.dp))
        Text(value, style = TetherTheme.type.mono, color = TetherTheme.colors.codeText)
    }
}

@Composable
private fun HowToVerify() {
    var open by remember { mutableStateOf(false) }
    val colors = TetherTheme.colors
    Column(Modifier.fillMaxWidth().animateContentSize()) {
        Row(
            Modifier
                .clip(RoundedCornerShape(10.dp))
                .clickable(role = Role.Button) { open = !open }
                .padding(vertical = 6.dp, horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("How do I check?", style = MaterialTheme.typography.labelLarge, color = colors.clay)
            Icon(Icons.Rounded.ExpandMore, null, tint = colors.clay, modifier = Modifier.size(18.dp).rotate(if (open) 180f else 0f))
        }
        AnimatedVisibility(open, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            Column(Modifier.padding(top = Space.xs)) {
                Text(
                    "On the machine itself, run:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(Space.xs))
                Text(
                    "ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub",
                    style = TetherTheme.type.monoSmall,
                    color = colors.codeText,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(colors.codeBg).padding(horizontal = 8.dp, vertical = 6.dp),
                )
                Spacer(Modifier.height(Space.xs))
                Text(
                    "The SHA256 value it prints should match the groups above.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * A deliberately slow action: press and hold ~1.2 s to confirm. Used for trusting a changed host
 * key so it can never happen by an accidental tap. Accessibility services get a direct action.
 */
@Composable
private fun HoldToConfirm(label: String, color: Color, onConfirmed: () -> Unit) {
    val progress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()
    val confirm by rememberUpdatedState(onConfirmed)
    val shape = RoundedCornerShape(16.dp)
    var holding by remember { mutableStateOf(false) }
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 50.dp)
            .clip(shape)
            .border(1.dp, color.copy(alpha = 0.5f), shape)
            .drawBehind {
                drawRect(color.copy(alpha = 0.18f), size = Size(size.width * progress.value, size.height))
            }
            .semantics {
                role = Role.Button
                onClick(label = label) { confirm(); true }
            }
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    holding = true
                    haptics.tick()
                    val job: Job = scope.launch {
                        progress.animateTo(1f, tween(1200, easing = LinearEasing))
                        haptics.confirm()
                        confirm()
                    }
                    tryAwaitRelease()
                    holding = false
                    if (progress.value < 1f) {
                        job.cancel()
                        scope.launch { progress.animateTo(0f, tween(220)) }
                    }
                })
            }
            .padding(horizontal = 20.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (holding) "Keep holding…" else label,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
            color = color,
        )
    }
}
