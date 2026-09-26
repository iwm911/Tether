package app.tether

import android.content.Intent
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tether.core.RunRef
import app.tether.ui.components.LocalHapticsEnabled
import app.tether.ui.connections.HostKeyPromptHost
import app.tether.ui.components.SafeLinks
import app.tether.ui.lock.AppLock
import app.tether.ui.lock.AppLockGate
import app.tether.ui.nav.TetherNavHost
import app.tether.ui.theme.TetherTheme
import kotlinx.coroutines.flow.MutableStateFlow

/** FragmentActivity (not plain ComponentActivity) because BiometricPrompt needs it. */
class MainActivity : FragmentActivity() {

    /** Deep link from a notification: open this agent. Consumed by the nav host. */
    val pendingAgent = MutableStateFlow<RunRef?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        // Only a fresh launch consumes the deep link: a recreation (rotation, process-death restore)
        // or a relaunch from Recents re-delivers the same intent and must not re-open the agent.
        if (savedInstanceState == null &&
            (intent?.flags ?: 0) and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY == 0
        ) {
            handleIntent(intent)
        }
        val container = (application as TetherApp).container
        setContent {
            val settings by container.settings.settings.collectAsStateWithLifecycle()
            CompositionLocalProvider(
                LocalAppContainer provides container,
                LocalHapticsEnabled provides settings.haptics,
                app.tether.ui.chat.render.LocalCodeFontScale provides settings.codeFontScale,
            ) {
                TetherTheme(themeMode = settings.theme, dynamicColor = settings.dynamicColor) {
                    AppLockGate {
                        SafeLinks {
                            TetherNavHost(pendingAgent = pendingAgent)
                            HostKeyPromptHost()
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val updates = (application as TetherApp).container.updates
        updates.onResumeAfterPermission()
        updates.maybeCheck()
    }

    // Every activity Tether starts itself (pickers, settings pages, browser, BiometricPrompt's
    // credential screen) goes through here; tell the app lock this isn't the user leaving.
    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun startActivityForResult(intent: Intent, requestCode: Int, options: Bundle?) {
        AppLock.launchingExternal()
        super.startActivityForResult(intent, requestCode, options)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val c = intent?.getStringExtra(EXTRA_CONNECTION_ID) ?: return
        val r = intent.getStringExtra(EXTRA_RUN_ID) ?: return
        pendingAgent.value = RunRef(c, r)
        intent.removeExtra(EXTRA_CONNECTION_ID)
        intent.removeExtra(EXTRA_RUN_ID)
    }

    companion object {
        const val EXTRA_CONNECTION_ID = "app.tether.extra.CONNECTION_ID"
        const val EXTRA_RUN_ID = "app.tether.extra.RUN_ID"
    }
}
