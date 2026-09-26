package app.tether.ui.nav

import android.Manifest
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContentTransitionScope.SlideDirection
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.tether.LocalAppContainer
import app.tether.core.RunRef
import app.tether.ui.chat.AgentScreen
import app.tether.ui.chat.SessionScreen
import app.tether.ui.connections.ConnectionEditorScreen
import app.tether.ui.connections.KeysScreen
import app.tether.ui.connections.MachinesScreen
import app.tether.ui.home.HomeScreen
import app.tether.ui.machine.MachineScreen
import app.tether.ui.newagent.NewAgentScreen
import app.tether.ui.onboarding.OnboardingScreen
import app.tether.ui.settings.SettingsScreen
import app.tether.ui.theme.Motion
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

object Routes {
    const val ONBOARDING = "onboarding"
    const val HOME = "home"
    const val MACHINES = "machines"
    const val MACHINE = "machine/{conn}"
    const val EDIT = "edit?id={id}"
    const val KEYS = "keys"
    const val SETTINGS = "settings"
    const val NEW = "new?conn={conn}&cwd={cwd}&resume={resume}"
    const val AGENT = "agent/{conn}/{run}"
    const val SESSION = "session/{conn}/{session}"

    fun machine(conn: String) = "machine/${enc(conn)}"
    fun edit(id: String?) = if (id == null) "edit" else "edit?id=${enc(id)}"
    fun new(conn: String? = null, cwd: String? = null, resume: String? = null): String {
        val q = buildList {
            conn?.let { add("conn=${enc(it)}") }
            cwd?.let { add("cwd=${enc(it)}") }
            resume?.let { add("resume=${enc(it)}") }
        }
        return if (q.isEmpty()) "new" else "new?" + q.joinToString("&")
    }
    fun agent(ref: RunRef) = "agent/${enc(ref.connectionId)}/${enc(ref.runId)}"
    fun session(conn: String, session: String) = "session/${enc(conn)}/${enc(session)}"
    private fun enc(s: String) = Uri.encode(s)
}

private fun NavHostController.openAgent(ref: RunRef, replaceCurrent: Boolean = false) {
    navigate(Routes.agent(ref)) {
        launchSingleTop = true
        if (replaceCurrent) currentDestination?.route?.let { popUpTo(it) { inclusive = true } }
    }
}

@Composable
fun TetherNavHost(pendingAgent: MutableStateFlow<RunRef?>) {
    val container = LocalAppContainer.current
    val nav = rememberNavController()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val start = remember {
        if (!container.settings.settings.value.onboardingDone && container.connections.connections.value.isEmpty()) Routes.ONBOARDING else Routes.HOME
    }

    // Android 13+: ask for notification permission once the user is past onboarding.
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val settings by container.settings.settings.collectAsStateWithLifecycle()
    LaunchedEffect(settings.onboardingDone) {
        if (settings.onboardingDone && Build.VERSION.SDK_INT >= 33) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    val deepLink by pendingAgent.collectAsStateWithLifecycle()
    LaunchedEffect(deepLink) {
        val ref = deepLink ?: return@LaunchedEffect
        pendingAgent.value = null
        nav.openAgent(ref)
    }

    fun finishOnboarding() = scope.launch { container.settings.update { it.copy(onboardingDone = true) } }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        NavHost(
            navController = nav,
            startDestination = start,
            enterTransition = { slideIntoContainer(SlideDirection.Start, tween(Motion.Medium, easing = Motion.Emphasized)) { it / 5 } + fadeIn(tween(Motion.Medium)) },
            exitTransition = { slideOutOfContainer(SlideDirection.Start, tween(Motion.Medium, easing = Motion.Emphasized)) { it / 10 } + fadeOut(tween(Motion.Short)) },
            popEnterTransition = { slideIntoContainer(SlideDirection.End, tween(Motion.Medium, easing = Motion.Emphasized)) { it / 10 } + fadeIn(tween(Motion.Medium)) },
            popExitTransition = { slideOutOfContainer(SlideDirection.End, tween(Motion.Medium, easing = Motion.Emphasized)) { it / 5 } + fadeOut(tween(Motion.Short)) },
        ) {
            composable(Routes.ONBOARDING, enterTransition = { fadeIn() }, exitTransition = { fadeOut(tween(Motion.Short)) }) {
                OnboardingScreen(
                    onAddMachine = { finishOnboarding(); nav.navigate(Routes.edit(null)) { popUpTo(Routes.ONBOARDING) { inclusive = true } } ; },
                    onFinish = { finishOnboarding(); nav.navigate(Routes.HOME) { popUpTo(Routes.ONBOARDING) { inclusive = true } } },
                )
            }
            composable(Routes.HOME) {
                HomeScreen(
                    onOpenAgent = { nav.openAgent(it) },
                    onNewAgent = { conn -> nav.navigate(Routes.new(conn = conn)) },
                    onOpenMachine = { nav.navigate(Routes.machine(it)) },
                    onOpenMachines = { nav.navigate(Routes.MACHINES) },
                    onAddMachine = { nav.navigate(Routes.edit(null)) },
                    onOpenSettings = { nav.navigate(Routes.SETTINGS) },
                )
            }
            composable(Routes.MACHINES) {
                MachinesScreen(
                    onBack = { nav.popBackStack() },
                    onAdd = { nav.navigate(Routes.edit(null)) },
                    onOpen = { nav.navigate(Routes.machine(it)) },
                    onEdit = { nav.navigate(Routes.edit(it)) },
                )
            }
            composable(Routes.MACHINE, arguments = listOf(navArgument("conn") { type = NavType.StringType })) { e ->
                val conn = e.arguments?.getString("conn")!!
                MachineScreen(
                    connectionId = conn,
                    onBack = { nav.popBackStack() },
                    onOpenAgent = { nav.openAgent(it) },
                    onOpenSession = { c, s -> nav.navigate(Routes.session(c, s)) },
                    onNewAgent = { c, cwd -> nav.navigate(Routes.new(conn = c, cwd = cwd)) },
                    onEdit = { nav.navigate(Routes.edit(it)) },
                )
            }
            composable(
                Routes.EDIT,
                arguments = listOf(navArgument("id") { type = NavType.StringType; nullable = true; defaultValue = null }),
                enterTransition = { slideIntoContainer(SlideDirection.Up, tween(Motion.Medium, easing = Motion.Emphasized)) { it / 6 } + fadeIn() },
                popExitTransition = { slideOutOfContainer(SlideDirection.Down, tween(Motion.Medium, easing = Motion.Emphasized)) { it / 6 } + fadeOut() },
            ) { e ->
                ConnectionEditorScreen(
                    connectionId = e.arguments?.getString("id"),
                    onBack = { if (!nav.popBackStack()) nav.navigate(Routes.HOME) },
                    onSaved = { id ->
                        if (!nav.popBackStack()) nav.navigate(Routes.HOME)
                        else if (nav.currentDestination?.route == Routes.HOME || nav.currentDestination?.route == Routes.MACHINES) nav.navigate(Routes.machine(id))
                    },
                    onManageKeys = { nav.navigate(Routes.KEYS) },
                )
            }
            composable(Routes.KEYS) { KeysScreen(onBack = { nav.popBackStack() }) }
            composable(Routes.SETTINGS) {
                SettingsScreen(
                    onBack = { nav.popBackStack() },
                    onOpenKeys = { nav.navigate(Routes.KEYS) },
                    onOpenMachines = { nav.navigate(Routes.MACHINES) },
                )
            }
            composable(
                Routes.NEW,
                arguments = listOf(
                    navArgument("conn") { type = NavType.StringType; nullable = true; defaultValue = null },
                    navArgument("cwd") { type = NavType.StringType; nullable = true; defaultValue = null },
                    navArgument("resume") { type = NavType.StringType; nullable = true; defaultValue = null },
                ),
                enterTransition = { slideIntoContainer(SlideDirection.Up, tween(Motion.Medium, easing = Motion.Emphasized)) { it / 6 } + fadeIn() },
                popExitTransition = { slideOutOfContainer(SlideDirection.Down, tween(Motion.Medium, easing = Motion.Emphasized)) { it / 6 } + fadeOut() },
            ) { e ->
                NewAgentScreen(
                    connectionId = e.arguments?.getString("conn"),
                    cwd = e.arguments?.getString("cwd"),
                    resumeSessionId = e.arguments?.getString("resume"),
                    onBack = { nav.popBackStack() },
                    onStarted = { nav.openAgent(it, replaceCurrent = true) },
                    onAddMachine = { nav.navigate(Routes.edit(null)) },
                )
            }
            composable(
                Routes.AGENT,
                arguments = listOf(navArgument("conn") { type = NavType.StringType }, navArgument("run") { type = NavType.StringType }),
                enterTransition = { scaleIn(tween(Motion.Medium, easing = Motion.Emphasized), initialScale = 0.96f) + fadeIn(tween(Motion.Medium)) },
                popExitTransition = { scaleOut(tween(Motion.Medium, easing = Motion.Emphasized), targetScale = 0.96f) + fadeOut(tween(Motion.Short)) },
            ) { e ->
                AgentScreen(
                    ref = RunRef(e.arguments?.getString("conn")!!, e.arguments?.getString("run")!!),
                    onBack = { if (!nav.popBackStack()) nav.navigate(Routes.HOME) },
                    onOpenMachine = { nav.navigate(Routes.machine(it)) },
                    // A branch stacks on top of its source (Back returns to the original conversation);
                    // launchSingleTop would replace it, since every agent shares one route pattern.
                    onOpenAgent = { nav.navigate(Routes.agent(it)) },
                )
            }
            composable(Routes.SESSION, arguments = listOf(navArgument("conn") { type = NavType.StringType }, navArgument("session") { type = NavType.StringType })) { e ->
                SessionScreen(
                    connectionId = e.arguments?.getString("conn")!!,
                    sessionId = e.arguments?.getString("session")!!,
                    onBack = { nav.popBackStack() },
                    onContinue = { nav.openAgent(it, replaceCurrent = true) },
                )
            }
        }
    }
}
