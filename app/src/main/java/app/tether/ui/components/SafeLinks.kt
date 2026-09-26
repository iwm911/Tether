package app.tether.ui.components

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import app.tether.BuildConfig
import app.tether.ui.connections.ConfirmDialog

/**
 * Every link tap in the app goes through here (Compose's `LinkAnnotation.Url` and `LocalUriHandler`).
 * Links in chat come from the remote machine and Claude's output, so they're untrusted: only
 * http(s) and mailto are opened, and only after showing the real address. Other schemes (custom
 * app schemes, `tel:`, `market:`, `file:`, `content:` …) are ignored. The project's own GitHub
 * pages open directly.
 */
@Composable
fun SafeLinks(content: @Composable () -> Unit) {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<Uri?>(null) }

    fun open(uri: Uri) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE))
        } catch (_: ActivityNotFoundException) {
        }
    }

    val handler = remember {
        object : UriHandler {
            override fun openUri(uri: String) {
                val parsed = safeUri(uri) ?: return
                if (isOwnPage(parsed)) open(parsed) else pending = parsed
            }
        }
    }
    CompositionLocalProvider(LocalUriHandler provides handler) { content() }

    pending?.let { uri ->
        ConfirmDialog(
            title = if (uri.scheme == "mailto") "Write an email?" else "Open ${uri.host}?",
            body = uri.toString(),
            confirmLabel = "Open",
            onConfirm = { pending = null; open(uri) },
            onDismiss = { pending = null },
            icon = Icons.Rounded.OpenInNew,
        )
    }
}

private val ALLOWED_SCHEMES = setOf("https", "http", "mailto")

/** The link as a Uri if it's one Tether is willing to open, else null. */
internal fun safeUri(raw: String): Uri? {
    val uri = runCatching { Uri.parse(raw.trim()) }.getOrNull() ?: return null
    val scheme = uri.scheme?.lowercase() ?: return null
    if (scheme !in ALLOWED_SCHEMES) return null
    if (scheme != "mailto" && uri.host.isNullOrBlank()) return null
    return uri
}

private fun isOwnPage(uri: Uri): Boolean =
    uri.scheme == "https" && uri.host == "github.com" &&
        (uri.path ?: "").startsWith("/${BuildConfig.UPDATE_REPO}/")
