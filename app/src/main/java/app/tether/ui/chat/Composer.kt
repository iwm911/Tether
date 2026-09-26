package app.tether.ui.chat

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.tether.core.ImageAttachment
import app.tether.core.ModelOption
import app.tether.core.PermissionMode
import app.tether.core.SlashCommand
import app.tether.core.FallbackModels
import app.tether.ui.components.rememberHaptics
import app.tether.ui.theme.Motion
import app.tether.ui.theme.Space
import app.tether.ui.theme.TetherTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// ───────────────────────────── State ─────────────────────────────

/** An image staged in the composer: the payload sent to Claude plus a small thumbnail for display. */
class ComposerAttachment(val id: Long, val image: ImageAttachment, val thumbnail: ImageBitmap)

/** Draft text + staged images. Held by the ViewModel so it survives rotation and failed sends. */
@Stable
class ComposerState(initial: String = "") {
    var value by mutableStateOf(TextFieldValue(initial, TextRange(initial.length)))
    val attachments = mutableStateListOf<ComposerAttachment>()
    /** Images currently being decoded / downscaled. */
    var processing by mutableIntStateOf(0)

    val hasText: Boolean get() = value.text.isNotBlank()
    val canSend: Boolean get() = (hasText || attachments.isNotEmpty()) && processing == 0
    val isEmpty: Boolean get() = value.text.isEmpty() && attachments.isEmpty()

    /** Removes and returns the draft, or null when there is nothing to send. */
    fun take(): Pair<String, List<ComposerAttachment>>? {
        val text = value.text.trim()
        if (text.isEmpty() && attachments.isEmpty()) return null
        val staged = attachments.toList()
        value = TextFieldValue("")
        attachments.clear()
        return text to staged
    }

    /** Puts a draft back after a failed send without clobbering anything typed since. */
    fun restore(text: String, staged: List<ComposerAttachment>) {
        val cur = value.text
        val merged = when {
            cur.isBlank() -> text
            text.isBlank() -> cur
            else -> text + "\n" + cur
        }
        value = TextFieldValue(merged, TextRange(merged.length))
        val existing = attachments.map { it.id }.toSet()
        attachments.addAll(0, staged.filter { it.id !in existing })
    }

    fun insertCommand(name: String) {
        val t = "/${name.removePrefix("/")} "
        value = TextFieldValue(t, TextRange(t.length))
    }

    fun appendText(extra: String) {
        val cur = value.text
        val sep = if (cur.isEmpty() || cur.endsWith(" ") || cur.endsWith("\n")) "" else " "
        val t = cur + sep + extra.trim()
        value = TextFieldValue(t, TextRange(t.length))
    }
}

// ───────────────────────────── Presentation helpers ─────────────────────────────

internal fun PermissionMode.icon(): ImageVector = when (this) {
    PermissionMode.DEFAULT -> Icons.Rounded.Shield
    PermissionMode.ACCEPT_EDITS -> Icons.Rounded.EditNote
    PermissionMode.PLAN -> Icons.Rounded.Checklist
    PermissionMode.AUTO -> Icons.Rounded.AutoAwesome
    PermissionMode.BYPASS -> Icons.Rounded.WarningAmber
}

@Composable
internal fun PermissionMode.tint(): Color = when (this) {
    PermissionMode.DEFAULT -> MaterialTheme.colorScheme.onSurfaceVariant
    PermissionMode.ACCEPT_EDITS -> TetherTheme.colors.success
    PermissionMode.PLAN -> TetherTheme.colors.info
    PermissionMode.AUTO -> TetherTheme.colors.clay
    PermissionMode.BYPASS -> TetherTheme.colors.danger
}

/** "claude-opus-4-5-20251101" → "Opus 4.5"; matches a CLI-reported option first. */
internal fun modelLabel(model: String?, options: List<ModelOption>): String {
    if (model.isNullOrBlank()) return "Default"
    options.firstOrNull { it.value.equals(model, ignoreCase = true) }?.let { return it.displayName }
    val m = model.lowercase()
    val family = listOf("opus", "sonnet", "haiku").firstOrNull { it in m }
        ?: return model.removePrefix("claude-").take(18)
    val name = family.replaceFirstChar { it.uppercase() }
    val v = Regex("$family-(\\d+)(?:-(\\d{1,2}))?(?=-|\\[|$)").find(m)
    val version = v?.let { r ->
        val major = r.groupValues[1]
        val minor = r.groupValues.getOrNull(2).orEmpty()
        if (minor.isNotEmpty()) "$major.$minor" else major
    }
    val longCtx = if ("[1m]" in m) " 1M" else ""
    return if (version != null) "$name $version$longCtx" else "$name$longCtx"
}

private fun modelMatches(option: ModelOption, model: String?): Boolean {
    if (model.isNullOrBlank()) return option.value == "default"
    if (option.value.equals(model, ignoreCase = true)) return true
    val v = option.value.lowercase()
    return v in listOf("opus", "sonnet", "haiku") && v in model.lowercase()
}

private val attachmentIds = AtomicLong(1)

/** Decodes, orients, downscales (≤ [maxSide] px) and JPEG-encodes a picked image. Runs on IO. */
private suspend fun loadAttachment(context: Context, uri: Uri, maxSide: Int = 1568): ComposerAttachment = withContext(Dispatchers.IO) {
    val decoded: Bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val w = info.size.width
            val h = info.size.height
            val scale = min(1f, maxSide.toFloat() / max(w, h).coerceAtLeast(1))
            if (scale < 1f) decoder.setTargetSize((w * scale).roundToInt().coerceAtLeast(1), (h * scale).roundToInt().coerceAtLeast(1))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    } else {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val raw = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: throw IllegalArgumentException("Unsupported image")
        val longest = max(raw.width, raw.height)
        if (longest > maxSide) {
            val s = maxSide.toFloat() / longest
            Bitmap.createScaledBitmap(raw, (raw.width * s).roundToInt().coerceAtLeast(1), (raw.height * s).roundToInt().coerceAtLeast(1), true)
        } else raw
    }
    // JPEG has no alpha: flatten transparent images onto white so they don't turn black.
    val opaque = if (decoded.hasAlpha()) {
        val flat = Bitmap.createBitmap(decoded.width, decoded.height, Bitmap.Config.ARGB_8888)
        Canvas(flat).apply { drawColor(android.graphics.Color.WHITE); drawBitmap(decoded, 0f, 0f, null) }
        flat
    } else decoded
    val bytes = ByteArrayOutputStream().use { out ->
        opaque.compress(Bitmap.CompressFormat.JPEG, 85, out)
        out.toByteArray()
    }
    val thumbSide = 176f
    val ts = min(1f, thumbSide / max(opaque.width, opaque.height))
    val thumb = Bitmap.createScaledBitmap(opaque, (opaque.width * ts).roundToInt().coerceAtLeast(1), (opaque.height * ts).roundToInt().coerceAtLeast(1), true)
    val id = attachmentIds.getAndIncrement()
    ComposerAttachment(id, ImageAttachment(bytes, "image/jpeg", "image_$id.jpg"), thumb.asImageBitmap())
}

private enum class SendAction { SEND, STOP, IDLE }

// ───────────────────────────── Composer ─────────────────────────────

/**
 * The bottom composer. Insets (IME + navigation bar) are applied by the screen's bottom container
 * so the decision panel above it moves with the keyboard too.
 */
@Composable
fun Composer(
    state: ComposerState,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Message Claude…",
    enabled: Boolean = true,
    working: Boolean = false,
    onStop: (() -> Unit)? = null,
    sending: Boolean = false,
    permissionMode: String? = null,
    onCycleMode: (() -> PermissionMode)? = null,
    onSelectMode: ((PermissionMode) -> Unit)? = null,
    model: String? = null,
    models: List<ModelOption> = emptyList(),
    onSelectModel: ((String) -> Unit)? = null,
    commands: List<SlashCommand> = emptyList(),
    hint: String? = null,
    allowAttachments: Boolean = true,
    onError: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    val currentOnError by rememberUpdatedState(onError)

    // Transient "Plan mode — …" label after a tap on the mode chip.
    var toastMode by remember { mutableStateOf<PermissionMode?>(null) }
    var toastSeq by remember { mutableIntStateOf(0) }
    LaunchedEffect(toastSeq) {
        if (toastSeq > 0) { delay(1_800); toastMode = null }
    }
    var showModeSheet by remember { mutableStateOf(false) }
    var showModelSheet by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(maxItems = 4)) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        val room = (MaxAttachments - state.attachments.size).coerceAtLeast(0)
        if (room < uris.size) currentOnError("You can attach up to $MaxAttachments images")
        uris.take(room).forEach { uri ->
            state.processing++
            scope.launch {
                try {
                    state.attachments.add(loadAttachment(context, uri))
                } catch (ce: CancellationException) {
                    throw ce
                } catch (t: Throwable) {
                    currentOnError("Couldn't attach that image")
                } finally {
                    state.processing--
                }
            }
        }
    }
    val dictation = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            res.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.takeIf { it.isNotBlank() }?.let(state::appendText)
        }
    }

    val slashQuery by remember(state) {
        derivedStateOf<String?> {
            val t = state.value.text
            if (t.startsWith("/") && t.none { it.isWhitespace() }) t.drop(1) else null
        }
    }
    val slashMatches by remember(state, commands) {
        derivedStateOf<List<SlashCommand>> {
            val q = slashQuery ?: return@derivedStateOf emptyList()
            val norm = commands.map { it.copy(name = it.name.removePrefix("/")) }
            val starts = norm.filter { it.name.startsWith(q, ignoreCase = true) }
            val contains = norm.filter { !it.name.startsWith(q, ignoreCase = true) && it.name.contains(q, ignoreCase = true) }
            (starts.sortedBy { it.name.length } + contains).take(40)
        }
    }

    val action = when {
        working && state.isEmpty && onStop != null -> SendAction.STOP
        state.canSend && enabled && !sending -> SendAction.SEND
        else -> SendAction.IDLE
    }

    Column(modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(top = 6.dp, bottom = 8.dp)) {
        AnimatedVisibility(
            visible = slashMatches.isNotEmpty(),
            enter = fadeIn(tween(Motion.Short)) + slideInVertically(Motion.gentle()) { it / 4 },
            exit = fadeOut(tween(Motion.Short)) + slideOutVertically(tween(Motion.Short)) { it / 4 },
        ) {
            SlashPopup(
                matches = slashMatches,
                onPick = { cmd -> haptics.tick(); state.insertCommand(cmd.name) },
                modifier = Modifier.padding(bottom = Space.sm),
            )
        }

        AnimatedVisibility(
            visible = toastMode != null,
            enter = fadeIn(tween(Motion.Short)) + expandVertically(Motion.gentle()),
            exit = fadeOut(tween(Motion.Medium)) + shrinkVertically(tween(Motion.Medium)),
        ) {
            val m = toastMode
            if (m != null) ModeToast(m, Modifier.padding(bottom = Space.sm, start = Space.xs))
        }

        AnimatedVisibility(
            visible = hint != null,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            Text(
                hint.orEmpty(),
                style = MaterialTheme.typography.labelSmall,
                color = TetherTheme.colors.faint,
                modifier = Modifier.padding(start = Space.md, bottom = Space.xs),
            )
        }

        val tc = TetherTheme.colors
        val borderColor by animateColorAsState(
            if (working) tc.clay.copy(alpha = 0.35f) else tc.composerBorder,
            tween(Motion.Medium), label = "composerBorder",
        )
        val composerShape = RoundedCornerShape(28.dp)
        Surface(
            shape = composerShape,
            color = tc.composer,
            border = BorderStroke(1.dp, borderColor),
            shadowElevation = if (tc.isDark) 0.dp else 6.dp,
            modifier = Modifier.fillMaxWidth().alpha(if (enabled) 1f else 0.6f),
        ) {
            Column {
                AnimatedVisibility(visible = state.attachments.isNotEmpty() || state.processing > 0) {
                    AttachmentRow(state, onRemove = { a -> haptics.tick(); state.attachments.remove(a) })
                }
                BasicTextField(
                    value = state.value,
                    onValueChange = { state.value = it },
                    enabled = enabled,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(TetherTheme.colors.clay),
                    minLines = 1,
                    maxLines = 6,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 8.dp)
                        .semantics { contentDescription = placeholder },
                    decorationBox = { inner ->
                        Box {
                            if (state.value.text.isEmpty()) {
                                Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = TetherTheme.colors.faint, maxLines = 1)
                            }
                            inner()
                        }
                    },
                )
                Row(
                    Modifier.fillMaxWidth().padding(start = 10.dp, end = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        if (onCycleMode != null || onSelectMode != null) {
                            val mode = PermissionMode.fromCli(permissionMode)
                            ComposerChip(
                                text = mode?.label ?: permissionMode?.replaceFirstChar { it.uppercase() } ?: PermissionMode.DEFAULT.label,
                                icon = (mode ?: PermissionMode.DEFAULT).icon(),
                                tint = (mode ?: PermissionMode.DEFAULT).tint(),
                                emphasised = mode != null && mode != PermissionMode.DEFAULT,
                                enabled = enabled,
                                contentDescription = "Permission mode: ${mode?.label ?: "Ask"}. Tap to cycle, long-press for all modes",
                                onClick = {
                                    val next = onCycleMode?.invoke()
                                    if (next != null) {
                                        haptics.confirm()
                                        toastMode = next
                                        toastSeq++
                                    } else showModeSheet = true
                                },
                                onLongClick = { haptics.confirm(); showModeSheet = true },
                            )
                        }
                        if (onSelectModel != null) {
                            ComposerChip(
                                text = modelLabel(model, models.ifEmpty { FallbackModels }),
                                icon = Icons.Rounded.Bolt,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                emphasised = false,
                                enabled = enabled,
                                trailingIcon = Icons.Rounded.ExpandMore,
                                contentDescription = "Model: ${modelLabel(model, models)}. Tap to change",
                                onClick = { showModelSheet = true },
                                onLongClick = null,
                            )
                        }
                    }
                    if (allowAttachments) {
                        IconButton(
                            onClick = {
                                haptics.tick()
                                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            },
                            enabled = enabled,
                        ) {
                            Icon(Icons.Rounded.AddPhotoAlternate, contentDescription = "Attach image", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    IconButton(
                        onClick = {
                            haptics.tick()
                            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                                .putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak to Claude")
                            try {
                                dictation.launch(intent)
                            } catch (e: ActivityNotFoundException) {
                                currentOnError("Voice input isn't available on this device")
                            }
                        },
                        enabled = enabled,
                    ) {
                        Icon(Icons.Rounded.Mic, contentDescription = "Dictate", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    SendButton(
                        action = action,
                        sending = sending,
                        onClick = {
                            when (action) {
                                SendAction.SEND -> { haptics.tick(); onSend() }
                                SendAction.STOP -> { haptics.confirm(); onStop?.invoke() }
                                SendAction.IDLE -> Unit
                            }
                        },
                    )
                }
            }
        }
    }

    if (showModeSheet) {
        ModeSheet(
            current = PermissionMode.fromCli(permissionMode) ?: PermissionMode.DEFAULT,
            onPick = { m ->
                haptics.confirm()
                showModeSheet = false
                onSelectMode?.invoke(m)
                toastMode = m
                toastSeq++
            },
            onDismiss = { showModeSheet = false },
        )
    }
    if (showModelSheet) {
        ModelSheet(
            options = models.ifEmpty { FallbackModels },
            current = model,
            onPick = { opt ->
                haptics.confirm()
                showModelSheet = false
                onSelectModel?.invoke(opt.value)
            },
            onDismiss = { showModelSheet = false },
        )
    }
}

private const val MaxAttachments = 6

// ───────────────────────────── Pieces ─────────────────────────────

@Composable
private fun SendButton(action: SendAction, sending: Boolean, onClick: () -> Unit) {
    val clay = TetherTheme.colors.clay
    val bg by animateColorAsState(
        when (action) {
            SendAction.SEND -> clay
            SendAction.STOP -> clay
            SendAction.IDLE -> clay.copy(alpha = if (TetherTheme.colors.isDark) 0.30f else 0.35f)
        },
        tween(Motion.Short), label = "sendBg",
    )
    val fg by animateColorAsState(
        if (action == SendAction.IDLE) Color.White.copy(alpha = 0.75f) else MaterialTheme.colorScheme.onPrimary,
        tween(Motion.Short), label = "sendFg",
    )
    val corner by animateDpAsState(if (action == SendAction.STOP) 12.dp else 20.dp, Motion.gentle(), label = "sendCorner")
    val shape = RoundedCornerShape(corner)
    Box(
        Modifier
            .padding(start = 4.dp)
            .size(40.dp)
            .clip(shape)
            .background(bg)
            .clickable(
                enabled = action != SendAction.IDLE && !sending,
                role = Role.Button,
                onClickLabel = if (action == SendAction.STOP) "Stop Claude" else "Send",
                onClick = onClick,
            )
            .semantics { contentDescription = if (action == SendAction.STOP) "Stop" else "Send" },
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(
            targetState = if (sending) null else action,
            transitionSpec = {
                (scaleIn(Motion.bouncy(), initialScale = 0.6f) + fadeIn(tween(Motion.Short))) togetherWith
                    (scaleOut(tween(Motion.Short), targetScale = 0.6f) + fadeOut(tween(Motion.Short)))
            },
            label = "sendIcon",
        ) { a ->
            when (a) {
                null -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = fg)
                SendAction.STOP -> Box(Modifier.size(13.dp).clip(RoundedCornerShape(3.dp)).background(fg))
                else -> Icon(Icons.Rounded.ArrowUpward, contentDescription = null, tint = fg, modifier = Modifier.size(21.dp))
            }
        }
    }
}

@Composable
private fun ComposerChip(
    text: String,
    icon: ImageVector,
    tint: Color,
    emphasised: Boolean,
    enabled: Boolean,
    contentDescription: String,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    trailingIcon: ImageVector? = null,
) {
    val bg by animateColorAsState(
        if (emphasised) tint.copy(alpha = 0.14f) else Color.Transparent,
        tween(Motion.Medium), label = "chipBg",
    )
    val fg by animateColorAsState(tint, tween(Motion.Medium), label = "chipFg")
    Row(
        Modifier
            .heightIn(min = 34.dp)
            .clip(CircleShape)
            .background(bg)
            .border(1.dp, if (emphasised) tint.copy(alpha = 0.30f) else TetherTheme.colors.composerBorder, CircleShape)
            .combinedClickable(enabled = enabled, role = Role.Button, onClick = onClick, onLongClick = onLongClick)
            .semantics { this.contentDescription = contentDescription }
            .padding(start = 10.dp, end = if (trailingIcon != null) 6.dp else 12.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(6.dp))
        AnimatedContent(text, transitionSpec = { (fadeIn(tween(Motion.Short)) + slideInVertically { it / 2 }) togetherWith (fadeOut(tween(Motion.Short)) + slideOutVertically { -it / 2 }) }, label = "chipText") {
            Text(it, style = MaterialTheme.typography.labelMedium, color = fg, maxLines = 1)
        }
        if (trailingIcon != null) {
            Icon(trailingIcon, contentDescription = null, tint = fg.copy(alpha = 0.7f), modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun ModeToast(mode: PermissionMode, modifier: Modifier = Modifier) {
    val tint = mode.tint()
    Row(
        modifier
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.inverseSurface)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(mode.icon(), contentDescription = null, tint = if (mode == PermissionMode.DEFAULT) MaterialTheme.colorScheme.inverseOnSurface else tint, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            "${mode.label} mode",
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.inverseOnSurface,
        )
        Text(
            "  ·  ${mode.description}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.7f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun AttachmentRow(state: ComposerState, onRemove: (ComposerAttachment) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(state.attachments, key = { it.id }) { a ->
            Box(Modifier.animateItem().size(64.dp)) {
                Image(
                    bitmap = a.thumbnail,
                    contentDescription = "Attached image",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(64.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .border(1.dp, TetherTheme.colors.hairline, RoundedCornerShape(14.dp)),
                )
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(3.dp)
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(TetherTheme.colors.scrim)
                        .clickable(role = Role.Button, onClickLabel = "Remove image") { onRemove(a) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.Close, contentDescription = "Remove image", tint = Color.White, modifier = Modifier.size(14.dp))
                }
            }
        }
        if (state.processing > 0) {
            item(key = "processing") {
                Box(
                    Modifier
                        .animateItem()
                        .size(64.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = TetherTheme.colors.clay)
                }
            }
        }
    }
}

@Composable
private fun SlashPopup(matches: List<SlashCommand>, onPick: (SlashCommand) -> Unit, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, TetherTheme.colors.hairline),
        modifier = modifier.fillMaxWidth(),
    ) {
        LazyColumn(Modifier.heightIn(max = 264.dp), contentPadding = PaddingValues(vertical = 6.dp)) {
            items(matches, key = { it.name }) { cmd ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable(role = Role.Button, onClickLabel = "Insert /${cmd.name}") { onPick(cmd) }
                        .padding(horizontal = 16.dp, vertical = 9.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "/${cmd.name}",
                            style = TetherTheme.type.mono.copy(fontWeight = FontWeight.Medium),
                            color = TetherTheme.colors.clay,
                            maxLines = 1,
                        )
                        if (cmd.argumentHint.isNotBlank()) {
                            Spacer(Modifier.width(8.dp))
                            Text(cmd.argumentHint, style = TetherTheme.type.monoSmall, color = TetherTheme.colors.faint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    if (cmd.description.isNotBlank()) {
                        Text(
                            cmd.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ModeSheet(current: PermissionMode, onPick: (PermissionMode) -> Unit, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.padding(bottom = Space.lg)) {
            SheetTitle("Permission mode", "How much Claude may do without asking. Tap the chip to cycle like Shift+Tab.")
            PermissionMode.entries.forEach { m ->
                val tint = m.tint()
                val selected = m == current
                val danger = m == PermissionMode.BYPASS
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(role = Role.RadioButton) { onPick(m) }
                        .background(if (selected) tint.copy(alpha = 0.08f) else Color.Transparent)
                        .padding(horizontal = Space.gutter, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(tint.copy(alpha = 0.14f)),
                        contentAlignment = Alignment.Center,
                    ) { Icon(m.icon(), contentDescription = null, tint = tint, modifier = Modifier.size(20.dp)) }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            m.label,
                            style = MaterialTheme.typography.titleSmall,
                            color = if (danger) TetherTheme.colors.danger else MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            m.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (danger) TetherTheme.colors.danger.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (selected) Icon(Icons.Rounded.Check, contentDescription = "Selected", tint = tint)
                }
            }
        }
    }
}

@Composable
private fun ModelSheet(options: List<ModelOption>, current: String?, onPick: (ModelOption) -> Unit, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.padding(bottom = Space.lg)) {
            SheetTitle("Model", "Applies from Claude's next message.")
            val selectedIndex = options.indexOfFirst { modelMatches(it, current) }
            options.forEachIndexed { i, opt ->
                val selected = i == selectedIndex
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(role = Role.RadioButton) { onPick(opt) }
                        .background(if (selected) TetherTheme.colors.clay.copy(alpha = 0.08f) else Color.Transparent)
                        .padding(horizontal = Space.gutter, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(opt.displayName, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                        if (opt.description.isNotBlank()) {
                            Text(opt.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    if (selected) Icon(Icons.Rounded.Check, contentDescription = "Selected", tint = TetherTheme.colors.clay)
                }
            }
        }
    }
}

@Composable
internal fun SheetTitle(title: String, subtitle: String? = null) {
    Column(Modifier.padding(horizontal = Space.gutter).padding(bottom = Space.md)) {
        Text(title, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
        if (subtitle != null) {
            Spacer(Modifier.height(4.dp))
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
