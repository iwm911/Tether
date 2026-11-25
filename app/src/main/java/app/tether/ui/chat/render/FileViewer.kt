package app.tether.ui.chat.render

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BrokenImage
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.automirrored.rounded.Article
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.tether.core.RemoteFiles
import app.tether.ui.theme.Mono
import app.tether.ui.theme.TetherTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Files on the session's machine the chat can show: [open] takes a path as Claude wrote it (relative to
 * [base], the session's folder) and opens it full screen; [files] reads it over SFTP.
 */
@Stable
class ChatFiles(val files: RemoteFiles, val base: String?, val open: (String) -> Unit) {
    fun resolve(path: String): String? = FilePreview.resolve(path, base)
}

/** Present inside a session's chat (null elsewhere: paths stay plain text, `![](path)` stays a link). */
val LocalChatFiles = compositionLocalOf<ChatFiles?> { null }

/**
 * Lets [content] show the images and Markdown files Claude points at: a tap on such a path opens it in a
 * viewer here, `![alt](path)` shows the picture inline. Other links go to the app's own handler. With no
 * [files] (no machine to read from) [content] is left as it is.
 */
@Composable
fun FileLinkHost(files: RemoteFiles?, base: String?, content: @Composable () -> Unit) {
    if (files == null) {
        content()
        return
    }
    val outer = LocalUriHandler.current
    var viewing by remember { mutableStateOf<String?>(null) }
    val chat = remember(files, base) {
        ChatFiles(files, base) { raw -> FilePreview.resolve(raw, base)?.let { viewing = it } }
    }
    val handler = remember(outer, chat) {
        object : UriHandler {
            override fun openUri(uri: String) {
                val path = FilePreview.fromLink(uri)
                if (path != null) chat.open(path) else outer.openUri(uri)
            }
        }
    }
    CompositionLocalProvider(LocalUriHandler provides handler, LocalChatFiles provides chat) { content() }
    viewing?.let { path -> FileViewerDialog(path, files, onDismiss = { viewing = null }) }
}

// ───────────────────────────── Loading ─────────────────────────────

private sealed interface Loaded {
    data object Loading : Loaded
    data class Failed(val message: String) : Loaded
    class Picture(val bitmap: ImageBitmap) : Loaded
    data class Markdown(val text: String) : Loaded
}

@Composable
private fun rememberRemoteFile(files: RemoteFiles, path: String, kind: PreviewKind, maxPx: Int, fresh: Boolean, reload: Int): Loaded {
    val state by produceState<Loaded>(Loaded.Loading, files, path, reload) {
        value = Loaded.Loading
        value = try {
            withContext(Dispatchers.IO) {
                when (kind) {
                    PreviewKind.IMAGE -> {
                        val f = files.fetch(path, FilePreview.MAX_IMAGE_BYTES, fresh = fresh || reload > 0)
                        decodeBitmap(f, maxPx)?.let { Loaded.Picture(it) } ?: Loaded.Failed("Not an image Tether can show")
                    }
                    PreviewKind.MARKDOWN -> {
                        val f = files.fetch(path, FilePreview.MAX_MARKDOWN_BYTES, fresh = true)
                        Loaded.Markdown(f.readText())
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Loaded.Failed(e.message?.takeIf { it.isNotBlank() } ?: "Couldn't read the file")
        }
    }
    return state
}

/** Decodes [file], downscaled by powers of two so its longer side is at most about [maxPx]. */
private fun decodeBitmap(file: File, maxPx: Int): ImageBitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxPx) sample *= 2
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    return BitmapFactory.decodeFile(file.path, opts)?.asImageBitmap()
}

// ───────────────────────────── Inline picture ─────────────────────────────

/** A picture from the machine inside Claude's reply; tap opens it full screen. */
@Composable
internal fun InlineRemoteImage(path: String, caption: String?, modifier: Modifier = Modifier) {
    val chat = LocalChatFiles.current ?: return
    val resolved = remember(chat, path) { chat.resolve(path) } ?: return
    val c = TetherTheme.colors
    val shape = RoundedCornerShape(12.dp)
    val loaded = rememberRemoteFile(chat.files, resolved, PreviewKind.IMAGE, maxPx = 1280, fresh = false, reload = 0)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .border(1.dp, c.hairline, shape)
                .background(c.codeBg)
                .clickable { chat.open(resolved) },
            contentAlignment = Alignment.Center,
        ) {
            when (loaded) {
                Loaded.Loading -> Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = c.clay)
                }
                is Loaded.Picture -> Image(
                    bitmap = loaded.bitmap,
                    contentDescription = caption ?: FilePreview.name(resolved),
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp),
                )
                is Loaded.Failed -> Row(
                    Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(Icons.Rounded.BrokenImage, null, tint = c.faint, modifier = Modifier.size(18.dp))
                    Text(
                        "Couldn't show ${FilePreview.name(resolved)} — ${loaded.message}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                is Loaded.Markdown -> Unit
            }
        }
        val label = caption ?: FilePreview.name(resolved)
        Text(label, style = MaterialTheme.typography.labelSmall, color = c.faint, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

// ───────────────────────────── Full-screen viewer ─────────────────────────────

/** [path] (already resolved) full screen: a zoomable picture, or the Markdown file rendered (source on tap). */
@Composable
internal fun FileViewerDialog(path: String, files: RemoteFiles, onDismiss: () -> Unit) {
    val kind = FilePreview.kind(path) ?: return
    var reload by remember(path) { mutableIntStateOf(0) }
    var source by remember(path) { mutableStateOf(false) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.navigationBars))) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, contentDescription = "Close") }
                    Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
                        Text(FilePreview.name(path), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            path,
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = Mono),
                            color = TetherTheme.colors.faint,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (kind == PreviewKind.MARKDOWN) {
                        IconButton(onClick = { source = !source }) {
                            Icon(if (source) Icons.AutoMirrored.Rounded.Article else Icons.Rounded.Code, contentDescription = if (source) "Show rendered" else "Show source")
                        }
                    }
                    IconButton(onClick = { reload++ }) { Icon(Icons.Rounded.Refresh, contentDescription = "Reload") }
                }
                val loaded = rememberRemoteFile(files, path, kind, maxPx = 4096, fresh = true, reload = reload)
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    when (loaded) {
                        Loaded.Loading -> CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.5.dp, color = TetherTheme.colors.clay)
                        is Loaded.Failed -> Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                            Icon(Icons.Rounded.BrokenImage, null, tint = TetherTheme.colors.faint, modifier = Modifier.size(36.dp))
                            Text(
                                "Couldn't open this file",
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.padding(top = 12.dp),
                            )
                            Text(
                                loaded.message,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                        is Loaded.Picture -> ZoomableImage(loaded.bitmap, FilePreview.name(path))
                        is Loaded.Markdown -> {
                            // Links inside the file are relative to the file's own folder.
                            FileLinkHost(files, base = FilePreview.parent(path)) {
                                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp)) {
                                    if (source) CodeView(loaded.text, language = "markdown", collapseAt = Int.MAX_VALUE, stateKey = "file-source:$path")
                                    else MarkdownContent(loaded.text)
                                    Box(Modifier.height(24.dp).width(1.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ZoomableImage(bitmap: ImageBitmap, description: String) {
    var scale by remember(bitmap) { mutableFloatStateOf(1f) }
    var offset by remember(bitmap) { mutableStateOf(Offset.Zero) }
    Image(
        bitmap = bitmap,
        contentDescription = description,
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(bitmap) {
                detectTapGestures(onDoubleTap = {
                    if (scale > 1f) { scale = 1f; offset = Offset.Zero } else scale = 2.5f
                })
            }
            .pointerInput(bitmap) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 8f)
                    offset = if (scale == 1f) Offset.Zero else offset + pan * scale
                }
            }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
            },
    )
}
