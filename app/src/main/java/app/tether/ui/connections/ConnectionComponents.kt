package app.tether.ui.connections

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.tether.core.MachineAccents
import app.tether.core.ProbeResult
import app.tether.core.SshKey
import app.tether.core.TestProgress
import app.tether.core.TestStep
import app.tether.ui.components.PrimaryButton
import app.tether.ui.components.SecondaryButton
import app.tether.ui.components.TagChip
import app.tether.ui.components.TetherTextField
import app.tether.ui.components.accentColor
import app.tether.ui.components.rememberHaptics
import app.tether.ui.theme.Space
import app.tether.ui.theme.TetherTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * Building blocks shared by the machines, editor, keys, host-key and settings screens.
 * Kept `internal` to the app module; integration may hoist any of them into ui/components.
 */

// ───────────────────────────── Connection test model ─────────────────────────────

internal enum class StepPhase { PENDING, RUNNING, DONE, FAILED }

internal data class StepUi(
    val step: TestStep,
    val phase: StepPhase = StepPhase.PENDING,
    val detail: String? = null,
    val error: String? = null,
)

internal data class TestUi(
    val running: Boolean = false,
    val steps: List<StepUi> = TestStep.entries.map { StepUi(it) },
    val probe: ProbeResult? = null,
    val failure: String? = null,
) {
    val started: Boolean get() = running || probe != null || failure != null || steps.any { it.phase != StepPhase.PENDING }
    val failedStep: StepUi? get() = steps.firstOrNull { it.phase == StepPhase.FAILED }
    val succeeded: Boolean get() = !running && probe != null && probe.problem == null && failure == null
}

/** Folds one progress report into the checklist; earlier unfinished steps are implicitly done. */
internal fun TestUi.advance(p: TestProgress): TestUi {
    val idx = p.step.ordinal
    return copy(steps = steps.mapIndexed { i, s ->
        when {
            i < idx && (s.phase == StepPhase.PENDING || s.phase == StepPhase.RUNNING) -> s.copy(phase = StepPhase.DONE)
            i == idx && p.error != null -> s.copy(phase = StepPhase.FAILED, error = p.error, detail = p.detail ?: s.detail)
            i == idx && p.done -> s.copy(phase = StepPhase.DONE, detail = p.detail ?: s.detail)
            i == idx -> s.copy(phase = StepPhase.RUNNING, detail = p.detail ?: s.detail)
            else -> s
        }
    })
}

internal fun TestUi.finish(result: Result<ProbeResult>): TestUi {
    val probe = result.getOrNull()
    if (probe != null) {
        val problem = probe.problem
        if (problem == null) {
            return copy(
                running = false, probe = probe, failure = null,
                steps = steps.map { if (it.phase == StepPhase.FAILED) it else it.copy(phase = StepPhase.DONE) },
            )
        }
        val target: TestStep = steps.firstOrNull { it.phase == StepPhase.RUNNING }?.step
            ?: if (probe.claudePath == null) TestStep.CLAUDE else TestStep.READY
        return copy(
            running = false, probe = probe, failure = null,
            steps = steps.map { s ->
                when {
                    s.step == target -> s.copy(phase = StepPhase.FAILED, error = problem)
                    s.step.ordinal < target.ordinal && s.phase != StepPhase.FAILED -> s.copy(phase = StepPhase.DONE)
                    s.phase == StepPhase.RUNNING -> s.copy(phase = StepPhase.PENDING)
                    else -> s
                }
            },
        )
    }
    val msg = friendlyError(result.exceptionOrNull() ?: IllegalStateException("Connection failed"))
    val alreadyFailed = steps.any { it.phase == StepPhase.FAILED }
    val target: TestStep? = steps.firstOrNull { it.phase == StepPhase.RUNNING }?.step
        ?: steps.firstOrNull { it.phase == StepPhase.PENDING }?.step
    val newSteps = if (alreadyFailed || target == null) {
        steps.map { if (it.phase == StepPhase.RUNNING) it.copy(phase = StepPhase.PENDING) else it }
    } else {
        steps.map { if (it.step == target) it.copy(phase = StepPhase.FAILED, error = msg) else it }
    }
    return copy(running = false, probe = null, failure = msg, steps = newSteps)
}

internal fun friendlyError(e: Throwable): String {
    val m = e.message?.trim().orEmpty()
    return when {
        m.isEmpty() -> "Something went wrong (${e::class.java.simpleName})."
        else -> m.replaceFirstChar { it.uppercase() }
    }
}

private sealed interface TestEvent {
    data class Progress(val p: TestProgress) : TestEvent
    data class Done(val result: Result<ProbeResult>) : TestEvent
}

/**
 * Runs a connection test and replays its progress with a small minimum gap between steps,
 * so a fast LAN test still reads as a checklist ticking down instead of a single flash.
 */
internal suspend fun runPacedTest(
    block: suspend (onProgress: (TestProgress) -> Unit) -> Result<ProbeResult>,
    onUpdate: ((TestUi) -> TestUi) -> Unit,
): Result<ProbeResult> = coroutineScope {
    onUpdate { TestUi(running = true) }
    val events = Channel<TestEvent>(Channel.UNLIMITED)
    val consumer = launch {
        delay(120)
        for (ev in events) {
            when (ev) {
                is TestEvent.Progress -> onUpdate { it.advance(ev.p) }
                is TestEvent.Done -> onUpdate { it.finish(ev.result) }
            }
            delay(230)
        }
    }
    val result = try {
        block { p -> events.trySend(TestEvent.Progress(p)); Unit }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }
    events.trySend(TestEvent.Done(result))
    events.close()
    consumer.join()
    result
}

private fun hintFor(step: TestStep?): String? = when (step) {
    TestStep.RESOLVE -> "Check the host name and port, and that the machine is on and reachable from this network (VPN, Tailscale, firewall)."
    TestStep.HOST_KEY -> "The machine's host key wasn't trusted, so Tether stopped before signing in."
    TestStep.AUTH -> "Check the username and the key or password. For key sign-in, the public key must be in ~/.ssh/authorized_keys on the machine."
    TestStep.CLAUDE -> "Install Claude Code on the machine (npm i -g @anthropic-ai/claude-code), or set its full path under Advanced."
    TestStep.READY -> null
    null -> null
}

// ───────────────────────────── Checklist UI ─────────────────────────────

@Composable
internal fun TestChecklist(ui: TestUi, modifier: Modifier = Modifier) {
    val colors = TetherTheme.colors
    Column(modifier.fillMaxWidth()) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .border(1.dp, colors.hairline, RoundedCornerShape(20.dp))
                .padding(vertical = Space.sm),
        ) {
            ui.steps.forEach { s -> TestStepRow(s) }
        }
        AnimatedVisibility(
            visible = !ui.running && (ui.probe != null || ui.failure != null),
            enter = fadeIn(tween(280)) + expandVertically(tween(320)),
            exit = fadeOut(tween(160)) + shrinkVertically(tween(200)),
        ) {
            Column {
                Spacer(Modifier.height(Space.md))
                TestSummaryCard(ui)
            }
        }
    }
}

@Composable
private fun TestStepRow(s: StepUi) {
    val colors = TetherTheme.colors
    val labelColor by animateColorAsState(
        when (s.phase) {
            StepPhase.PENDING -> colors.faint
            StepPhase.RUNNING -> MaterialTheme.colorScheme.onSurface
            StepPhase.DONE -> MaterialTheme.colorScheme.onSurface
            StepPhase.FAILED -> colors.danger
        },
        label = "steplabel",
    )
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Space.lg, vertical = 9.dp)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            AnimatedContent(
                targetState = s.phase,
                transitionSpec = {
                    (scaleIn(spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMedium), initialScale = 0.4f) + fadeIn(tween(160)))
                        .togetherWith(fadeOut(tween(120)))
                },
                label = "glyph",
            ) { phase ->
                when (phase) {
                    StepPhase.PENDING -> Box(Modifier.size(16.dp).border(1.5.dp, colors.faint.copy(alpha = 0.7f), CircleShape))
                    StepPhase.RUNNING -> CircularProgressIndicator(Modifier.size(17.dp), strokeWidth = 2.dp, color = colors.clay)
                    StepPhase.DONE -> Box(
                        Modifier.size(20.dp).background(colors.success.copy(alpha = 0.18f), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Rounded.Check, contentDescription = "Done", tint = colors.success, modifier = Modifier.size(14.dp)) }
                    StepPhase.FAILED -> Box(
                        Modifier.size(20.dp).background(colors.danger.copy(alpha = 0.18f), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Rounded.Close, contentDescription = "Failed", tint = colors.danger, modifier = Modifier.size(14.dp)) }
                }
            }
        }
        Spacer(Modifier.width(Space.md))
        Column(Modifier.weight(1f).padding(top = 2.dp)) {
            Text(
                s.step.label,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = if (s.phase == StepPhase.RUNNING) FontWeight.SemiBold else FontWeight.Normal),
                color = labelColor,
            )
            val sub = s.error ?: s.detail
            AnimatedVisibility(sub != null, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                Text(
                    sub.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (s.error != null) colors.danger.copy(alpha = 0.9f) else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun TestSummaryCard(ui: TestUi) {
    val colors = TetherTheme.colors
    val probe = ui.probe
    val ok = probe != null && probe.problem == null && ui.failure == null
    val tone = if (ok) colors.success else if (probe != null) colors.warning else colors.danger
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(tone.copy(alpha = 0.09f))
            .border(1.dp, tone.copy(alpha = 0.35f), RoundedCornerShape(20.dp))
            .padding(Space.lg),
    ) {
        if (ok && probe != null) {
            Text("READY", style = TetherTheme.type.eyebrow, color = tone)
            Spacer(Modifier.height(6.dp))
            Text(
                "Claude Code ${probe.claudeVersion ?: ""} found on ${probe.hostname}".replace("  ", " "),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(Space.md))
            ProbeFacts(probe)
        } else if (probe != null) {
            Text("CONNECTED, BUT…", style = TetherTheme.type.eyebrow, color = tone)
            Spacer(Modifier.height(6.dp))
            Text(probe.problem.orEmpty(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            hintFor(ui.failedStep?.step)?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(Space.md))
            ProbeFacts(probe)
        } else {
            Text("COULDN'T CONNECT", style = TetherTheme.type.eyebrow, color = tone)
            Spacer(Modifier.height(6.dp))
            Text(ui.failure.orEmpty(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            hintFor(ui.failedStep?.step)?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ProbeFacts(probe: ProbeResult) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FactRow("Host", probe.hostname)
        if (probe.os.isNotBlank()) FactRow("System", probe.os)
        FactRow("Claude", probe.claudePath ?: "not found")
        probe.pythonVersion?.let { FactRow("Python", it) }
    }
}

@Composable
private fun FactRow(label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = TetherTheme.colors.faint, modifier = Modifier.width(64.dp))
        Text(value, style = TetherTheme.type.monoSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// ───────────────────────────── Segmented control ─────────────────────────────

/** Tether's segmented control: a sliding, springy pill behind the selected option. */
@Composable
internal fun SegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    icons: List<ImageVector?> = emptyList(),
    height: Dp = 48.dp,
) {
    val haptics = rememberHaptics()
    val colors = TetherTheme.colors
    val outer = RoundedCornerShape(15.dp)
    val inner = RoundedCornerShape(11.dp)
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(height)
            .clip(outer)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .border(1.dp, colors.hairline, outer)
            .padding(4.dp),
    ) {
        val segW = maxWidth / options.size.coerceAtLeast(1)
        val x by animateDpAsState(segW * selectedIndex, spring(dampingRatio = 0.78f, stiffness = Spring.StiffnessMediumLow), label = "seg")
        Box(
            Modifier
                .offset(x = x)
                .width(segW)
                .fillMaxHeight()
                .clip(inner)
                .background(if (colors.isDark) MaterialTheme.colorScheme.surfaceBright else MaterialTheme.colorScheme.surfaceContainerLowest)
                .border(1.dp, colors.clay.copy(alpha = 0.35f), inner),
        )
        Row(Modifier.fillMaxSize()) {
            options.forEachIndexed { i, label ->
                val selected = i == selectedIndex
                val fg by animateColorAsState(
                    if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    label = "segfg",
                )
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(inner)
                        .selectable(selected = selected, role = Role.Tab) {
                            if (!selected) { haptics.tick(); onSelect(i) }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val icon = icons.getOrNull(i)
                        if (icon != null) {
                            Icon(icon, null, tint = if (selected) colors.clay else fg, modifier = Modifier.size(17.dp))
                            Spacer(Modifier.width(7.dp))
                        }
                        Text(label, style = MaterialTheme.typography.labelLarge.copy(fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium), color = fg, maxLines = 1)
                    }
                }
            }
        }
    }
}

// ───────────────────────────── Accent swatches ─────────────────────────────

@Composable
internal fun AccentSwatches(selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val haptics = rememberHaptics()
    FlowRow(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        MachineAccents.indices.forEach { i ->
            val c = accentColor(i)
            val isSel = i == selected
            val ring by animateDpAsState(if (isSel) 2.dp else 0.dp, label = "ring")
            Box(
                Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .border(ring, c, CircleShape)
                    .selectable(selected = isSel, role = Role.RadioButton) { haptics.tick(); onSelect(i) }
                    .semantics { contentDescription = "Accent ${i + 1}" }
                    .padding(5.dp),
                contentAlignment = Alignment.Center,
            ) {
                val checkScale by animateFloatAsState(
                    if (isSel) 1f else 0f,
                    spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMedium),
                    label = "swcheck",
                )
                Box(Modifier.fillMaxSize().clip(CircleShape).background(c), contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Rounded.Check,
                        null,
                        tint = Color(0xFF1C0F09),
                        modifier = Modifier.size(18.dp).graphicsLayer {
                            scaleX = checkScale
                            scaleY = checkScale
                            alpha = checkScale.coerceIn(0f, 1f)
                        },
                    )
                }
            }
        }
    }
}

// ───────────────────────────── Keys ─────────────────────────────

internal fun sharePublicKey(context: Context, key: SshKey) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, "SSH public key — ${key.name}")
        putExtra(Intent.EXTRA_TEXT, key.publicKey)
    }
    try {
        context.startActivity(Intent.createChooser(send, "Share public key").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
    }
}

internal fun prettyAlgorithm(alg: String): String = when (alg.lowercase()) {
    "ed25519", "ssh-ed25519" -> "Ed25519"
    "rsa", "ssh-rsa" -> "RSA"
    "ecdsa" -> "ECDSA"
    else -> alg.uppercase()
}

/** "SHA256:abcd…" → "abcd…wxyz" for tight rows. */
internal fun shortFingerprint(fp: String): String {
    val body = fp.substringAfter(':', fp)
    return if (body.length <= 16) body else body.take(8) + "…" + body.takeLast(6)
}

/** "SHA256:AbCdEfGh…" → ("SHA256", ["AbCd","EfGh",…]). */
internal fun fingerprintGroups(fp: String): Pair<String?, List<String>> {
    val hasPrefix = fp.contains(':') && fp.substringBefore(':').length <= 8
    val prefix = if (hasPrefix) fp.substringBefore(':') else null
    val body = if (hasPrefix) fp.substringAfter(':') else fp
    val clean = body.replace(":", "")
    return prefix to clean.chunked(4)
}

@Composable
internal fun FingerprintBlock(
    fingerprint: String,
    modifier: Modifier = Modifier,
    color: Color = TetherTheme.colors.codeText,
    struck: Boolean = false,
) {
    val (prefix, groups) = fingerprintGroups(fingerprint)
    Column(modifier.semantics(mergeDescendants = true) { contentDescription = "Fingerprint $fingerprint" }) {
        if (prefix != null) {
            Text(prefix, style = TetherTheme.type.eyebrow, color = color.copy(alpha = 0.6f))
            Spacer(Modifier.height(6.dp))
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            groups.forEach { g ->
                Text(
                    g,
                    style = TetherTheme.type.mono.copy(
                        textDecoration = if (struck) androidx.compose.ui.text.style.TextDecoration.LineThrough else null,
                    ),
                    color = color,
                )
            }
        }
    }
}

/** A small action that flips its icon to a check for a moment after firing. */
@Composable
internal fun CopyAction(text: String, label: String = "Copy", modifier: Modifier = Modifier) {
    val clipboard = LocalClipboardManager.current
    val haptics = rememberHaptics()
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { delay(1600); copied = false } }
    SmallAction(
        text = if (copied) "Copied" else label,
        icon = if (copied) Icons.Rounded.Check else Icons.Rounded.ContentCopy,
        tint = if (copied) TetherTheme.colors.success else MaterialTheme.colorScheme.onSurface,
        modifier = modifier,
    ) {
        clipboard.setText(AnnotatedString(text)); haptics.tick(); copied = true
    }
}

@Composable
internal fun SmallAction(
    text: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier
            .heightIn(min = 40.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .border(1.dp, TetherTheme.colors.hairline, shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AnimatedContent(icon, transitionSpec = { (scaleIn(initialScale = 0.6f) + fadeIn()).togetherWith(fadeOut()) }, label = "saicon") {
            Icon(it, null, tint = if (enabled) tint else tint.copy(alpha = 0.4f), modifier = Modifier.size(16.dp))
        }
        Spacer(Modifier.width(7.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = if (enabled) tint else tint.copy(alpha = 0.4f))
    }
}

/**
 * The public half of a key, in mono, with Copy / Share (and optionally "Install on server").
 * With [reveal] the text is unveiled left-to-right behind a clay scan line — the moment a key is born.
 */
@Composable
internal fun PublicKeyCard(
    key: SshKey,
    modifier: Modifier = Modifier,
    reveal: Boolean = false,
    onInstall: (() -> Unit)? = null,
    installEnabled: Boolean = true,
) {
    val colors = TetherTheme.colors
    val context = LocalContext.current
    val progress = remember(key.id) { Animatable(if (reveal) 0f else 1f) }
    LaunchedEffect(key.id, reveal) {
        if (reveal && progress.value < 1f) {
            delay(180)
            progress.animateTo(1f, tween(900, easing = FastOutSlowInEasing))
        }
    }
    val glow = rememberInfiniteTransition(label = "keyglow")
    val glowA by glow.animateFloat(0.25f, 0.55f, infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Reverse), label = "ga")
    val borderColor = if (reveal) colors.clay.copy(alpha = glowA) else colors.hairline
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.codeBg)
            .border(1.dp, borderColor, shape)
            .padding(Space.lg),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(32.dp).clip(RoundedCornerShape(10.dp)).background(colors.clay.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.Key, null, tint = colors.clay, modifier = Modifier.size(17.dp)) }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(key.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${prettyAlgorithm(key.algorithm)} · public key",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            AnimatedVisibility(reveal, enter = scaleIn(spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMediumLow)) + fadeIn(), exit = fadeOut()) {
                TagChip("New", selected = true)
            }
        }
        Spacer(Modifier.height(Space.md))
        SelectionContainer {
            Text(
                key.publicKey,
                style = TetherTheme.type.monoSmall,
                color = colors.codeText,
                maxLines = 6,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.drawWithContent {
                    val p = progress.value
                    if (p >= 1f) {
                        drawContent()
                    } else {
                        clipRect(right = size.width * p) { this@drawWithContent.drawContent() }
                        val x = size.width * p
                        drawLine(colors.clay, Offset(x, 0f), Offset(x, size.height), strokeWidth = 2.dp.toPx())
                    }
                },
            )
        }
        Spacer(Modifier.height(Space.sm))
        Text(key.fingerprint, style = TetherTheme.type.monoSmall, color = colors.faint, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(Space.md))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.sm), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
            CopyAction(key.publicKey)
            SmallAction("Share", Icons.Rounded.Share) { sharePublicKey(context, key) }
            if (onInstall != null) {
                SmallAction("Install on server", Icons.Rounded.Upload, tint = colors.clay, enabled = installEnabled, onClick = onInstall)
            }
        }
    }
}

// ───────────────────────────── Dialogs ─────────────────────────────

/** Tether-styled dialog shell: hairline card, serif title, optional icon badge, soft spring entrance. */
@Composable
internal fun TetherDialog(
    onDismiss: () -> Unit,
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    tone: Color = TetherTheme.colors.clay,
    eyebrow: String? = null,
    body: String? = null,
    dismissOnOutside: Boolean = true,
    border: Color = TetherTheme.colors.hairline,
    content: @Composable () -> Unit = {},
    actions: @Composable () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(dismissOnBackPress = true, dismissOnClickOutside = dismissOnOutside, usePlatformDefaultWidth = false),
    ) {
        val appear = remember { Animatable(0f) }
        LaunchedEffect(Unit) { appear.animateTo(1f, spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow)) }
        val shape = RoundedCornerShape(28.dp)
        Column(
            modifier
                .padding(horizontal = 22.dp, vertical = 24.dp)
                .widthIn(max = 460.dp)
                .fillMaxWidth()
                .graphicsLayer {
                    val v = appear.value
                    alpha = v.coerceIn(0f, 1f)
                    scaleX = 0.94f + 0.06f * v
                    scaleY = 0.94f + 0.06f * v
                    translationY = (1f - v) * 24f
                }
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .border(1.dp, border, shape)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
        ) {
            if (icon != null) {
                Box(
                    Modifier.size(48.dp).clip(RoundedCornerShape(16.dp)).background(tone.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center,
                ) { Icon(icon, null, tint = tone, modifier = Modifier.size(24.dp)) }
                Spacer(Modifier.height(Space.lg))
            }
            if (eyebrow != null) {
                Text(eyebrow.uppercase(), style = TetherTheme.type.eyebrow, color = tone)
                Spacer(Modifier.height(6.dp))
            }
            Text(title, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
            if (body != null) {
                Spacer(Modifier.height(Space.sm))
                Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            content()
            Spacer(Modifier.height(Space.xl))
            actions()
        }
    }
}

@Composable
internal fun ConfirmDialog(
    title: String,
    body: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    icon: ImageVector? = null,
    danger: Boolean = false,
    dismissLabel: String = "Cancel",
    extra: @Composable () -> Unit = {},
) {
    val colors = TetherTheme.colors
    TetherDialog(
        onDismiss = onDismiss,
        title = title,
        body = body,
        icon = icon,
        tone = if (danger) colors.danger else colors.clay,
        content = extra,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(Space.md)) {
            SecondaryButton(dismissLabel, onClick = onDismiss, modifier = Modifier.weight(1f))
            PrimaryButton(
                confirmLabel,
                onClick = onConfirm,
                modifier = Modifier.weight(1f),
                color = if (danger) colors.danger else MaterialTheme.colorScheme.primary,
                contentColor = if (danger) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onPrimary,
            )
        }
    }
}

/** Name prompt for a new key. */
@Composable
internal fun GenerateKeyDialog(generating: Boolean, onGenerate: (String) -> Unit, onDismiss: () -> Unit) {
    var name by rememberSaveable { mutableStateOf(defaultKeyName()) }
    TetherDialog(
        onDismiss = { if (!generating) onDismiss() },
        title = "New SSH key",
        body = "An Ed25519 key pair is created on this phone. The private half is encrypted with the Android Keystore and never leaves the device.",
        icon = Icons.Rounded.Key,
        content = {
            Spacer(Modifier.height(Space.lg))
            TetherTextField(name, { name = it }, label = "Key name", placeholder = defaultKeyName())
        },
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(Space.md)) {
            SecondaryButton("Cancel", onClick = onDismiss, enabled = !generating, modifier = Modifier.weight(1f))
            PrimaryButton("Generate", onClick = { onGenerate(name.ifBlank { defaultKeyName() }.trim()) }, loading = generating, modifier = Modifier.weight(1f))
        }
    }
}

internal fun defaultKeyName(): String {
    val model = Build.MODEL?.trim().orEmpty().ifEmpty { "Android" }
    return "$model · Tether"
}

/** Import a private key: pick a file (SAF) or paste text; optional passphrase. */
@Composable
internal fun ImportKeySheet(
    importing: Boolean,
    error: String?,
    onImport: (name: String, text: String, passphrase: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val colors = TetherTheme.colors
    var name by rememberSaveable { mutableStateOf("") }
    var text by rememberSaveable { mutableStateOf("") }
    var passphrase by rememberSaveable { mutableStateOf("") }
    var showPass by rememberSaveable { mutableStateOf(false) }
    var localError by remember { mutableStateOf<String?>(null) }
    var fileLabel by rememberSaveable { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) scope.launch {
            val loaded = withContext(Dispatchers.IO) { readKeyFile(context, uri) }
            loaded.fold(
                onSuccess = { (display, content) ->
                    text = content
                    fileLabel = display
                    if (name.isBlank()) name = display.substringBeforeLast('.').ifBlank { display }
                    localError = null
                },
                onFailure = { localError = it.message ?: "Couldn't read that file." },
            )
        }
    }

    val trimmed = text.trim()
    val looksPublic = trimmed.startsWith("ssh-") || trimmed.startsWith("ecdsa-sha2")
    val looksPrivate = trimmed.contains("PRIVATE KEY")
    val shownError = localError ?: error ?: when {
        looksPublic -> "That's a public key. Paste the private key — it starts with -----BEGIN … PRIVATE KEY-----."
        trimmed.isNotEmpty() && !looksPrivate -> "This doesn't look like an OpenSSH or PEM private key."
        else -> null
    }
    val canImport = looksPrivate && !importing

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = { if (!importing) onDismiss() },
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = Space.gutter)
                .padding(bottom = Space.xl),
        ) {
            Text("IMPORT", style = TetherTheme.type.eyebrow, color = colors.clay)
            Spacer(Modifier.height(6.dp))
            Text("Bring an existing key", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(Space.xs))
            Text(
                "Choose a private key file, or paste it. OpenSSH and PEM formats (Ed25519, ECDSA, RSA) are supported.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(Space.lg))
            Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                SmallAction("Choose file", Icons.Rounded.FileOpen, tint = colors.clay) { picker.launch(arrayOf("*/*")) }
                SmallAction("Paste", Icons.Rounded.ContentPaste) {
                    clipboard.getText()?.text?.let { text = it; fileLabel = null; localError = null }
                }
            }
            AnimatedVisibility(fileLabel != null) {
                Text(
                    "Loaded ${fileLabel.orEmpty()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.success,
                    modifier = Modifier.padding(top = Space.sm),
                )
            }
            Spacer(Modifier.height(Space.lg))
            TetherTextField(name, { name = it }, label = "Name", placeholder = "e.g. id_ed25519 from laptop")
            Spacer(Modifier.height(Space.md))
            TetherTextField(
                value = text,
                onValueChange = { text = it; fileLabel = null; localError = null },
                label = "Private key",
                placeholder = "-----BEGIN OPENSSH PRIVATE KEY-----",
                mono = true,
                singleLine = false,
                minLines = 5,
                isError = shownError != null,
            )
            Spacer(Modifier.height(Space.md))
            TetherTextField(
                value = passphrase,
                onValueChange = { passphrase = it },
                label = "Passphrase (if the key has one)",
                password = !showPass,
                mono = showPass,
                trailing = {
                    IconButton(onClick = { showPass = !showPass }) {
                        Icon(if (showPass) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, if (showPass) "Hide passphrase" else "Show passphrase")
                    }
                },
            )
            AnimatedVisibility(shownError != null, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                Text(
                    shownError.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.danger,
                    modifier = Modifier.padding(top = Space.md),
                )
            }
            Spacer(Modifier.height(Space.xl))
            PrimaryButton(
                "Import key",
                onClick = { onImport(name.ifBlank { "Imported key" }.trim(), trimmed, passphrase.ifEmpty { null }) },
                enabled = canImport,
                loading = importing,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.navigationBarsPadding())
        }
    }
}

private fun readKeyFile(context: Context, uri: Uri): Result<Pair<String, String>> = runCatching {
    val display = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
        if (c.moveToFirst()) {
            val sizeIdx = c.getColumnIndex(OpenableColumns.SIZE)
            if (sizeIdx >= 0 && !c.isNull(sizeIdx) && c.getLong(sizeIdx) > 64 * 1024) {
                throw IllegalArgumentException("That file is too large to be a private key.")
            }
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0) c.getString(idx) else null
        } else null
    } ?: "key"
    val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
        val buf = input.readBytes()
        if (buf.size > 64 * 1024) throw IllegalArgumentException("That file is too large to be a private key.")
        buf
    } ?: throw IllegalArgumentException("Couldn't open that file.")
    val text = bytes.toString(Charsets.UTF_8)
    if (!text.contains("PRIVATE KEY")) throw IllegalArgumentException("“$display” doesn't contain a private key.")
    display to text
}

// ───────────────────────────── Snackbar & skeleton ─────────────────────────────

@Composable
internal fun TetherSnackbarHost(state: SnackbarHostState, modifier: Modifier = Modifier) {
    SnackbarHost(state, modifier) { data ->
        val shape = RoundedCornerShape(16.dp)
        Row(
            Modifier
                .padding(horizontal = Space.lg, vertical = Space.sm)
                .fillMaxWidth()
                .clip(shape)
                .background(MaterialTheme.colorScheme.inverseSurface)
                .padding(start = Space.lg, end = Space.xs, top = 4.dp, bottom = 4.dp)
                .heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                data.visuals.message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.inverseOnSurface,
                modifier = Modifier.weight(1f).padding(vertical = 10.dp),
            )
            val action = data.visuals.actionLabel
            if (action != null) {
                TextButton(onClick = { data.performAction() }) {
                    Text(action, color = MaterialTheme.colorScheme.inversePrimary, style = MaterialTheme.typography.labelLarge)
                }
            } else {
                Spacer(Modifier.width(Space.md))
            }
        }
    }
}

/** Shimmering placeholder block (loading states). */
@Composable
internal fun SkeletonBlock(modifier: Modifier = Modifier, shape: RoundedCornerShape = RoundedCornerShape(14.dp)) {
    val t = rememberInfiniteTransition(label = "skel")
    val a by t.animateFloat(0.45f, 0.9f, infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse), label = "skela")
    Box(modifier.clip(shape).graphicsLayer { alpha = a }.background(MaterialTheme.colorScheme.surfaceContainerHigh))
}

internal val FormPadding = PaddingValues(horizontal = Space.gutter)
