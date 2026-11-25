package com.david.gitly

import android.content.Context
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.david.gitly.ui.ExploreScreen
import com.david.gitly.ui.FeedScreen
import com.david.gitly.ui.FriendsScreen
import com.david.gitly.ui.LoginScreen
import com.david.gitly.ui.PeopleScreen
import com.david.gitly.ui.ProfileScreen
import com.david.gitly.ui.RepoScreen
import com.david.gitly.ui.SettingsScreen
import com.david.gitly.ui.resolve
import java.util.Locale

/** Pantallas que se apilan sobre las pestañas. */
sealed interface Route {
    data class Repo(val fullName: String) : Route
    data class User(val login: String) : Route
    data class People(val login: String, val followers: Boolean) : Route
    data object Settings : Route
    data object EditProfile : Route
    data class Chat(val login: String) : Route
    data object AddAccount : Route
}

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()
    private val pendingRepo = mutableStateOf<String?>(null)

    /** Idioma elegido en Ajustes (o el del sistema si está vacío). */
    override fun attachBaseContext(base: Context) {
        val lang = Store(base).load().settings.language
        if (lang.isBlank()) { super.attachBaseContext(base); return }
        val conf = Configuration(base.resources.configuration).apply { setLocale(Locale.forLanguageTag(lang)) }
        super.attachBaseContext(base.createConfigurationContext(conf))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handle(intent)
        setContent {
            GitlyTheme(vm.settings) { App(vm, pendingRepo, onLanguageChanged = { recreate() }) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    /** https://github.com/owner/repo[/...] → abrir ese repo. */
    private fun handle(intent: Intent?) {
        val peer = intent?.getStringExtra(com.david.gitly.push.Notifications.EXTRA_PEER)
        val acc = intent?.getStringExtra(com.david.gitly.push.Notifications.EXTRA_ACCOUNT)
        if (peer != null && acc != null) {
            intent.removeExtra(com.david.gitly.push.Notifications.EXTRA_PEER)
            vm.openChatFromNotification(acc, peer)
            return
        }
        intent?.data?.takeIf { it.scheme == "gitly" && it.host == "oauth" }?.let { vm.completeWebLogin(it); return }
        val seg = intent?.data?.takeIf { it.host == "github.com" }?.pathSegments ?: return
        if (seg.size >= 2 && seg[0] !in RESERVED) pendingRepo.value = "${seg[0]}/${seg[1].removeSuffix(".git")}"
    }

    companion object {
        private val RESERVED = setOf("settings", "orgs", "topics", "search", "explore", "notifications", "login", "marketplace", "sponsors", "features")
    }
}

/** Esquema de respaldo cuando no hay color dinámico (Android < 12 o desactivado): semilla azul pizarra. */
private val FallbackLight: ColorScheme = lightColorScheme(
    primary = Color(0xFF3A5BA9), onPrimary = Color.White, primaryContainer = Color(0xFFDAE2FF), onPrimaryContainer = Color(0xFF001848),
    secondary = Color(0xFF575E71), secondaryContainer = Color(0xFFDBE2F9), onSecondaryContainer = Color(0xFF141B2C),
    tertiary = Color(0xFF715573), tertiaryContainer = Color(0xFFFBD7FC),
    surface = Color(0xFFFAF8FF), surfaceContainerLow = Color(0xFFF3F3FA), surfaceContainer = Color(0xFFEDEDF4),
    surfaceContainerHigh = Color(0xFFE8E7EF), surfaceContainerHighest = Color(0xFFE2E2E9),
)
private val FallbackDark: ColorScheme = darkColorScheme(
    primary = Color(0xFFB1C5FF), onPrimary = Color(0xFF002C71), primaryContainer = Color(0xFF1F438F), onPrimaryContainer = Color(0xFFDAE2FF),
    secondary = Color(0xFFBFC6DC), secondaryContainer = Color(0xFF3F4759), onSecondaryContainer = Color(0xFFDBE2F9),
    tertiary = Color(0xFFDEBCDF), tertiaryContainer = Color(0xFF583E5B),
    surface = Color(0xFF121318), surfaceContainerLow = Color(0xFF1A1B21), surfaceContainer = Color(0xFF1E1F25),
    surfaceContainerHigh = Color(0xFF282A2F), surfaceContainerHighest = Color(0xFF33353A),
)

@Composable
fun GitlyTheme(settings: Settings, content: @Composable () -> Unit) {
    val dark = when (settings.theme) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val ctx = LocalContext.current
    val scheme = when {
        settings.dynamicColor && Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> FallbackDark
        else -> FallbackLight
    }
    // Iconos de las barras del sistema acordes al tema elegido (no al del sistema).
    val view = androidx.compose.ui.platform.LocalView.current
    if (!view.isInEditMode) androidx.compose.runtime.SideEffect {
        val window = (view.context as android.app.Activity).window
        androidx.core.view.WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

private enum class Tab(val label: Int, val icon: ImageVector, val selectedIcon: ImageVector) {
    FEED(R.string.tab_for_you, Icons.Outlined.AutoAwesome, Icons.Filled.AutoAwesome),
    EXPLORE(R.string.tab_explore, Icons.Outlined.Explore, Icons.Filled.Explore),
    FRIENDS(R.string.tab_friends, Icons.Outlined.People, Icons.Filled.People),
    PROFILE(R.string.tab_profile, Icons.Outlined.Person, Icons.Outlined.Person),
}

@Composable
private fun App(vm: AppViewModel, pendingRepo: MutableState<String?>, onLanguageChanged: () -> Unit) {
    val ctx = LocalContext.current
    val snack = remember { SnackbarHostState() }
    LaunchedEffect(vm.message) {
        vm.message?.let { snack.showSnackbar(it.resolve(ctx)); vm.message = null }
    }
    val stack = vm.nav
    val open: (Route) -> Unit = { stack.add(it) }
    val back: () -> Unit = { if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex) }

    val acc = vm.account
    if (acc == null || stack.lastOrNull() == Route.AddAccount) {
        Scaffold(snackbarHost = { SnackbarHost(snack) }) { pad ->
            Box(Modifier.padding(pad)) {
                LoginScreen(vm, onCancel = if (acc != null) back else null, onDone = { if (stack.lastOrNull() == Route.AddAccount) back() })
            }
        }
        if (acc != null) BackHandler(onBack = back)
        return
    }

    var tab by rememberSaveable { mutableStateOf(Tab.FEED) }
    NotificationSetup(vm)
    LaunchedEffect(pendingRepo.value) {
        pendingRepo.value?.let { stack.add(Route.Repo(it)); pendingRepo.value = null }
    }
    BackHandler(enabled = stack.isNotEmpty(), onBack = back)

    Scaffold(
        snackbarHost = { SnackbarHost(snack) },
        bottomBar = {
            if (stack.isEmpty()) NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = {
                            if (t == Tab.PROFILE && acc.avatarUrl.isNotBlank()) {
                                AsyncImage(com.david.gitly.ui.avatarUrl(acc.avatarUrl, 64), null, Modifier.size(26.dp).clip(CircleShape))
                            } else if (t == Tab.FRIENDS && vm.unreadTotal > 0) {
                                androidx.compose.material3.BadgedBox(badge = { androidx.compose.material3.Badge { Text("${vm.unreadTotal}") } }) {
                                    Icon(if (tab == t) t.selectedIcon else t.icon, contentDescription = null)
                                }
                            } else Icon(if (tab == t) t.selectedIcon else t.icon, contentDescription = null)
                        },
                        label = { Text(stringResource(t.label)) },
                    )
                }
            }
        },
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            // Entrar en una pantalla la desliza desde la derecha; volver, al revés (como en Android).
            androidx.compose.animation.AnimatedContent(
                targetState = stack.lastOrNull() to stack.size,
                transitionSpec = {
                    val forward = targetState.second > initialState.second
                    val enter = androidx.compose.animation.slideInHorizontally(androidx.compose.animation.core.tween(280)) { w -> if (forward) w / 3 else -w / 3 } +
                        androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(220))
                    val exit = androidx.compose.animation.slideOutHorizontally(androidx.compose.animation.core.tween(280)) { w -> if (forward) -w / 6 else w / 3 } +
                        androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(180))
                    enter togetherWith exit
                },
                label = "rutas",
            ) { (top, _) ->
            if (top != null) {
                when (top) {
                    is Route.Repo -> RepoScreen(vm, top.fullName, onBack = back, open = open)
                    is Route.User -> ProfileScreen(vm, top.login, open = open, onBack = back)
                    is Route.People -> PeopleScreen(vm, top.login, top.followers, open = open, onBack = back)
                    Route.Settings -> SettingsScreen(vm, open = open, onBack = back, onLanguageChanged = onLanguageChanged)
                    Route.EditProfile -> com.david.gitly.ui.EditProfileScreen(vm, onBack = back)
                    is Route.Chat -> com.david.gitly.ui.ChatScreen(vm, top.login, open = open, onBack = back)
                    Route.AddAccount -> Unit
                }
            } else androidx.compose.animation.Crossfade(targetState = tab, animationSpec = androidx.compose.animation.core.tween(200), label = "pestañas") { t ->
                when (t) {
                    Tab.FEED -> FeedScreen(vm, open, onOpenChats = { tab = Tab.FRIENDS })
                    Tab.EXPLORE -> ExploreScreen(vm, open)
                    Tab.FRIENDS -> FriendsScreen(vm, open)
                    Tab.PROFILE -> ProfileScreen(vm, acc.login, open = open, onBack = null)
                }
            }
            }
        }
    }
}

/**
 * Con los mensajes activados: pedir permiso de notificaciones (Android 13+, una sola vez) y, si hay
 * un único distribuidor UnifiedPush instalado, activar el timbre sin preguntar.
 */
@Composable
private fun NotificationSetup(vm: AppViewModel) {
    val ctx = LocalContext.current
    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { vm.refreshPushStatus() }
    LaunchedEffect(vm.messagingEnabled) {
        if (!vm.messagingEnabled) return@LaunchedEffect
        com.david.gitly.push.SyncWorker.schedulePeriodic(ctx)
        val prefs = ctx.getSharedPreferences("push", Context.MODE_PRIVATE)
        if (android.os.Build.VERSION.SDK_INT >= 33 && !prefs.getBoolean("asked_permission", false) &&
            ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            prefs.edit().putBoolean("asked_permission", true).apply()
            launcher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        val act = ctx as? android.app.Activity ?: return@LaunchedEffect
        val push = com.david.gitly.push.Push
        if (push.active(ctx) != null) push.register(act) // renueva la URL si cambió
        else if (push.distributors(ctx).size == 1) push.register(act) { vm.refreshPushStatus() }
    }
}
