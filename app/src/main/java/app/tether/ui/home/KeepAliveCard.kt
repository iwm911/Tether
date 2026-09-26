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
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tether.LocalAppContainer
import app.tether.service.BatteryOptimization
import app.tether.ui.components.PrimaryButton
import app.tether.ui.theme.Space
import app.tether.ui.theme.TetherTheme
import kotlinx.coroutines.launch

/** One-time Home card: let Tether run unrestricted so kept-alive sessions survive the screen being off. */
@Composable
fun KeepAliveCard(hasMachines: Boolean, modifier: Modifier = Modifier) {
    val container = LocalAppContainer.current
    val settings by container.settings.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var unrestricted by remember { mutableStateOf(BatteryOptimization.isUnrestricted(context)) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) unrestricted = BatteryOptimization.isUnrestricted(context) }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    val show = hasMachines && settings.keepConnectionsAlive && !unrestricted && !settings.batteryPromptDismissed
    AnimatedVisibility(show, modifier = modifier, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.fillMaxWidth().padding(horizontal = Space.gutter, vertical = 6.dp),
        ) {
            Column(Modifier.padding(horizontal = Space.lg, vertical = 14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.BatteryChargingFull, contentDescription = null, tint = TetherTheme.colors.clay, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Text("Keep sessions alive in the background", style = MaterialTheme.typography.titleSmall)
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "Android may pause Tether's SSH connections when the screen is off. Let it run unrestricted — like Termius — so agents stay attached.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { scope.launch { container.settings.update { it.copy(batteryPromptDismissed = true) } } }) {
                        Text("Not now", color = TetherTheme.colors.faint)
                    }
                    Spacer(Modifier.weight(1f))
                    PrimaryButton("Allow", onClick = { BatteryOptimization.request(context) })
                }
            }
        }
    }
}
