package app.sunrestic

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat
import kotlinx.coroutines.delay

/** Bottom bar tabs. */
enum class Screen(val label: String, val icon: ImageVector, val selectedIcon: ImageVector) {
    HOME("Home", Icons.Outlined.Home, Icons.Filled.Home),
    SNAPSHOTS("Snapshots", Icons.Outlined.History, Icons.Filled.History),
    REPO("Repo", Icons.Outlined.Cloud, Icons.Filled.Cloud),
    SCHEDULES("Schedules", Icons.Outlined.Schedule, Icons.Filled.Schedule),
    SETTINGS("Settings", Icons.Outlined.Settings, Icons.Filled.Settings),
}

/** Full-screen pages opened from the tabs. They have their own top bar and no bottom bar. */
enum class Page { APPS, FOLDERS, SNAPSHOT, WIZARD, SELFCHECK, PHONE }

class MainActivity : ComponentActivity() {
    private val model: AppModel by viewModels()
    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Edge-to-edge: content draws under transparent system bars; the Scaffold handles insets.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false

        if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        handleIntent(intent)
        model.recoverDisabledApps()
        setContent {
            AppTheme(amoled = model.amoled) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { AppRoot(model) }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    /** The daily reminder notification opens the app with this flag: unlock, then start the backup. */
    private fun handleIntent(i: Intent?) {
        if (i?.getBooleanExtra(Scheduler.EXTRA_RUN, false) == true) {
            model.pendingScheduled = true
            i.removeExtra(Scheduler.EXTRA_RUN)
        }
    }

    override fun onStart() {
        super.onStart()
        model.onForegrounded()
    }

    override fun onResume() {
        super.onResume()
        model.resumed = true
    }

    override fun onPause() {
        model.resumed = false
        super.onPause()
    }

    override fun onStop() {
        super.onStop()
        model.onBackgrounded()
    }
}

@Composable
fun AppRoot(m: AppModel) {
    val activity = LocalContext.current.findActivity()

    // Ask for the fingerprint once per app start (and again after the idle lock dropped the password).
    // The prompt only works once the screen is resumed (opening from a notification starts earlier), so wait for that.
    LaunchedEffect(m.foregroundTick, m.resumed) {
        if (!m.resumed) return@LaunchedEffect
        delay(300)
        if (!m.unlockTried && m.vaultSaved && m.password.isEmpty() && !m.unlocking && activity != null) {
            m.unlockTried = true
            m.unlockPassword(activity)
        }
    }

    // Tapped the daily reminder: unlock if needed, then run the backup.
    LaunchedEffect(m.pendingScheduled, m.password, m.resumed) {
        if (!m.pendingScheduled || !m.resumed) return@LaunchedEffect
        delay(300)
        when {
            m.password.isNotEmpty() -> {
                m.pendingScheduled = false
                m.runScheduled()
            }
            !m.repoConfigured -> m.pendingScheduled = false
            m.unlocking -> Unit // waiting for the fingerprint; this effect runs again when the password arrives
            m.vaultSaved && activity != null -> m.unlockPassword(activity)
            else -> m.pendingScheduled = false // password has to be typed; the user sees Home
        }
    }

    // Tags are kept in the repository: pull them after unlocking, push them after a change or a finished job.
    LaunchedEffect(m.ready, JobHub.busy, m.tagsDirty) {
        m.syncTagsIfNeeded()
        m.applyPendingTagRenames()
    }

    BackHandler(enabled = m.page != null) { m.closePage() }
    BackHandler(enabled = m.page == null && m.screen != Screen.HOME) { m.screen = Screen.HOME }

    m.confirm?.let { (msg, action) ->
        AlertDialog(
            onDismissRequest = { m.confirm = null },
            icon = { Icon(Icons.Filled.Warning, null) },
            title = { Text("Are you sure?") },
            text = { Text(msg) },
            confirmButton = { TextButton({ m.confirm = null; action() }) { Text("Yes") } },
            dismissButton = { TextButton({ m.confirm = null }) { Text("Cancel") } },
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        )
    }

    if (m.showLog) LogSheet { m.showLog = false }

    BackHandler(enabled = JobHub.screenOpen) { JobHub.screenOpen = false }

    Box(Modifier.fillMaxSize()) {
    AnimatedContent(
        targetState = m.page,
        transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
        label = "page",
    ) { page ->
        when (page) {
            null -> Tabs(m)
            Page.APPS -> AppsPage(m)
            Page.FOLDERS -> FoldersPage(m)
            Page.SNAPSHOT -> SnapshotDetailsPage(m)
            Page.WIZARD -> RestoreWizardPage(m)
            Page.SELFCHECK -> SelfCheckPage(m)
            Page.PHONE -> PhoneDataPage(m)
        }
    }
    AnimatedVisibility(
        visible = JobHub.screenOpen,
        enter = fadeIn(tween(200)) + slideInVertically(tween(260)) { it / 10 },
        exit = fadeOut(tween(160)),
    ) { JobScreen(m) }
    }
}

@Composable
private fun Tabs(m: AppModel) {
    val activity = LocalContext.current.findActivity()
    val cs = MaterialTheme.colorScheme
    Scaffold(
        containerColor = cs.background,
        bottomBar = {
            CompactNavBar {
                Screen.values().forEach { s ->
                    val selected = m.screen == s
                    CompactNavItem(s.label, if (selected) s.selectedIcon else s.icon, selected) { m.screen = s }
                }
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            MainHeader(
                title = "Sunrestic",
                badge = when {
                    !m.repoConfigured -> "Setup"
                    else -> m.backendLabel
                },
                left = {
                    when {
                        !m.repoConfigured -> RoundIconButton(Icons.Filled.Storage, "Set up repository") { m.screen = Screen.REPO }
                        m.password.isEmpty() -> RoundIconButton(Icons.Filled.Fingerprint, "Unlock", tint = cs.primary) {
                            if (m.vaultSaved && activity != null) m.unlockPassword(activity) else m.screen = Screen.REPO
                        }
                        else -> RoundIconButton(Icons.Filled.LockOpen, "Lock", enabled = !JobHub.busy) { m.lock() }
                    }
                },
                right = {
                    RoundIconButton(Icons.Outlined.Description, "Log", badge = JobHub.busy) { m.showLog = true }
                },
            )
            JobBanner { m.showLog = true }
            Box(Modifier.weight(1f)) {
                Crossfade(targetState = m.screen, animationSpec = tween(160), label = "screen") { s ->
                    when (s) {
                        Screen.HOME -> HomeScreen(m)
                        Screen.SNAPSHOTS -> SnapshotsScreen(m)
                        Screen.REPO -> RepositoryScreen(m)
                        Screen.SCHEDULES -> ScheduleScreen(m)
                        Screen.SETTINGS -> SettingsScreen(m)
                    }
                }
            }
        }
    }
}

/** Frame for a full-screen page: system bar insets, its own top bar, the job banner, then the content. */
@Composable
fun PageFrame(
    m: AppModel,
    topBar: @Composable () -> Unit,
    fab: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        floatingActionButton = { fab?.invoke() },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            topBar()
            JobBanner { m.showLog = true }
            Box(Modifier.weight(1f)) { content() }
        }
    }
}
