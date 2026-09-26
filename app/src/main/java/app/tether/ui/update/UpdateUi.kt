package app.tether.ui.update

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tether.LocalAppContainer
import app.tether.update.UpdateState
import app.tether.ui.components.ClaudeSpinner
import app.tether.ui.components.PrimaryButton
import app.tether.ui.theme.Space
import app.tether.ui.theme.TetherTheme
import java.util.Locale

private fun mb(bytes: Long) = String.format(Locale.US, "%.1f MB", bytes / 1_048_576.0)

/** Slim card shown at the top of Home whenever an update is available or in progress. */
@Composable
fun UpdateBanner(modifier: Modifier = Modifier) {
    val updates = LocalAppContainer.current.updates
    val state by updates.state.collectAsStateWithLifecycle()
    val visible = state is UpdateState.Available || state is UpdateState.Downloading || state is UpdateState.Installing ||
        state is UpdateState.NeedsPermission || (state is UpdateState.Failed && (state as UpdateState.Failed).info != null)
    AnimatedVisibility(visible, modifier = modifier, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
        UpdateCard(state)
    }
}

@Composable
fun UpdateCard(state: UpdateState, modifier: Modifier = Modifier) {
    val updates = LocalAppContainer.current.updates
    val ctx = LocalContext.current
    val c = TetherTheme.colors
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = c.clay.copy(alpha = if (c.isDark) 0.10f else 0.08f),
        modifier = modifier.fillMaxWidth().padding(horizontal = Space.gutter, vertical = 6.dp),
    ) {
        Column(Modifier.padding(horizontal = Space.lg, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.SystemUpdate, contentDescription = null, tint = c.clay, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    val title = when (state) {
                        is UpdateState.Available -> "Tether ${state.info.versionName} is available"
                        is UpdateState.Downloading -> "Downloading Tether ${state.info.versionName}"
                        is UpdateState.Installing -> "Installing Tether ${state.info.versionName}"
                        is UpdateState.NeedsPermission -> "One step before updating"
                        is UpdateState.Failed -> "Update didn't finish"
                        else -> ""
                    }
                    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                    val sub = when (state) {
                        is UpdateState.Available -> state.info.notes.ifBlank { "From ${state.machineName}" }
                        is UpdateState.Downloading -> if (state.total > 0) "${mb(state.done)} of ${mb(state.total)}" else mb(state.done)
                        is UpdateState.Installing -> "Confirm in the Android dialog"
                        is UpdateState.NeedsPermission -> "Allow Tether to install updates in Android settings"
                        is UpdateState.Failed -> state.message
                        else -> ""
                    }
                    if (sub.isNotBlank()) {
                        Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                Spacer(Modifier.width(10.dp))
                when (state) {
                    is UpdateState.Available -> PrimaryButton("Update", onClick = { updates.install() })
                    is UpdateState.NeedsPermission -> PrimaryButton("Allow", onClick = { updates.openInstallPermissionSettings(ctx) })
                    is UpdateState.Failed -> TextButton(onClick = { updates.dismissError(); updates.install() }) { Text("Retry") }
                    is UpdateState.Installing -> ClaudeSpinner(fontSize = 15f)
                    else -> Unit
                }
            }
            if (state is UpdateState.Downloading) {
                Spacer(Modifier.height(10.dp))
                val frac = if (state.total > 0) (state.done.toFloat() / state.total).coerceIn(0f, 1f) else 0f
                Box(Modifier.fillMaxWidth().height(5.dp).clip(CircleShape).background(c.hairline)) {
                    Box(Modifier.fillMaxWidth(frac.coerceAtLeast(0.02f)).fillMaxHeight().clip(CircleShape).background(c.clay))
                }
            }
        }
    }
}

/** Settings row: current version, status, Check / Update. */
@Composable
fun UpdateSettingsRow(currentVersion: String) {
    val updates = LocalAppContainer.current.updates
    val state by updates.state.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxWidth().padding(horizontal = Space.gutter, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Updates", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                val status = when (val s = state) {
                    is UpdateState.Checking -> "Checking your machines…"
                    is UpdateState.UpToDate -> "Tether $currentVersion is up to date"
                    is UpdateState.Available -> "Version ${s.info.versionName} is available on ${s.machineName}"
                    is UpdateState.Downloading -> "Downloading…"
                    is UpdateState.Installing -> "Installing…"
                    is UpdateState.NeedsPermission -> "Needs permission to install"
                    is UpdateState.Failed -> s.message
                    UpdateState.Idle -> "Version $currentVersion · served from your own machines"
                }
                Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            when (state) {
                is UpdateState.Checking -> ClaudeSpinner(fontSize = 15f)
                is UpdateState.Available, is UpdateState.Downloading, is UpdateState.Installing, is UpdateState.NeedsPermission -> Unit
                else -> TextButton(onClick = { updates.check() }) { Text("Check now", style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold)) }
            }
        }
        val s = state
        if (s is UpdateState.Available || s is UpdateState.Downloading || s is UpdateState.Installing || s is UpdateState.NeedsPermission) {
            Spacer(Modifier.height(8.dp))
            UpdateCard(s, Modifier.padding(horizontal = 0.dp))
        }
    }
}
