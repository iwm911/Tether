package app.tether.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tether.BuildConfig
import app.tether.LocalAppContainer
import app.tether.ui.components.PrimaryButton
import app.tether.ui.theme.Space
import app.tether.ui.theme.TetherTheme
import kotlinx.coroutines.launch

/** Plain-language page listing exactly what analytics sends. */
val PrivacyUrl = "https://github.com/${BuildConfig.UPDATE_REPO}/blob/main/PRIVACY.md"

/** One-time Home card announcing anonymous analytics; nothing is sent until it's acknowledged. */
@Composable
fun AnalyticsNoticeCard(modifier: Modifier = Modifier) {
    val container = LocalAppContainer.current
    val settings by container.settings.settings.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val uri = LocalUriHandler.current
    val show = container.analytics.available && !settings.analyticsNoticeSeen
    AnimatedVisibility(show, modifier = modifier, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.fillMaxWidth().padding(horizontal = Space.gutter, vertical = 6.dp),
        ) {
            Column(Modifier.padding(horizontal = Space.lg, vertical = 14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Insights, contentDescription = null, tint = TetherTheme.colors.clay, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Text("Anonymous usage stats", style = MaterialTheme.typography.titleSmall)
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "Tether counts things like app opens and agents started, so we know it's worth building. " +
                        "No IDs, no machine names, no prompts or code. You can turn it off any time in Settings.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { uri.openUri(PrivacyUrl) }) {
                        Text("What's sent", color = TetherTheme.colors.faint)
                    }
                    TextButton(onClick = {
                        scope.launch { container.settings.update { it.copy(analyticsEnabled = false, analyticsNoticeSeen = true) } }
                    }) {
                        Text("Turn off", color = TetherTheme.colors.faint)
                    }
                    Spacer(Modifier.weight(1f))
                    PrimaryButton("OK", onClick = {
                        scope.launch {
                            container.settings.update { it.copy(analyticsNoticeSeen = true) }
                            container.analytics.flushSoon()
                        }
                    })
                }
            }
        }
    }
}
