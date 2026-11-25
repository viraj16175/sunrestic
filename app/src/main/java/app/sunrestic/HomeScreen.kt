package app.sunrestic

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** "196.42 GB" — two decimals, like Swift Backup. */
fun fmtGb(n: Long): String {
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var v = n.toDouble()
    var i = 0
    while (v >= 1024 && i < units.lastIndex) { v /= 1024; i++ }
    return if (i == 0) "$n B" else "%.2f %s".format(v, units[i])
}

private fun lastBackupText(t: Long): String {
    if (t <= 0L) return "No backup yet"
    val mins = (System.currentTimeMillis() - t) / 60_000
    return "Last backup " + when {
        mins < 1 -> "just now"
        mins < 60 -> "$mins min ago"
        mins < 48 * 60 -> "${mins / 60} h ago"
        else -> "${mins / (24 * 60)} days ago"
    }
}

@Composable
fun HomeScreen(m: AppModel) {
    val activity = androidx.compose.ui.platform.LocalContext.current.findActivity()
    val cs = MaterialTheme.colorScheme
    val x = LocalExtra.current
    val busy = JobHub.busy

    LaunchedEffect(m.useRoot) { if (m.useRoot && m.rootGranted == null) m.checkRoot() }

    LaunchedEffect(m.ready, JobHub.busy, JobHub.lastSuccess) { m.refreshRepoBytesIfStale() }
    LaunchedEffect(m.ready, JobHub.busy, JobHub.dataVersion) { m.syncLastBackupFromRepo() }

    ScreenScroll {
        // One status card: where backups go, whether it is ready, when the last one ran.
        val (repoValue, repoColor, repoIcon) = when {
            !m.repoConfigured -> Triple("Not set up", cs.error, Icons.Filled.Storage)
            m.password.isEmpty() -> Triple("Locked", cs.error, Icons.Filled.Lock)
            m.connectionProblem.isNotEmpty() -> Triple("Can't reach", cs.error, Icons.Filled.Error)
            else -> Triple("Ready", x.good, Icons.Filled.CheckCircle)
        }
        OverviewCard {
            StatusRow(
                label = "Repository · ${m.backendLabel}",
                value = repoValue,
                valueColor = repoColor,
                sub = if (m.ready && m.connectionProblem.isNotEmpty()) m.connectionProblem else if (m.repoConfigured) m.repoSummary else "Choose where backups are stored",
                sub2 = if (m.ready) lastBackupText(JobHub.lastSuccess) else null,
                icon = repoIcon,
                iconColor = repoColor,
            ) {
                if (m.password.isEmpty() && m.vaultSaved && activity != null) m.unlockPassword(activity)
                else m.screen = Screen.REPO
            }

            if (m.useRoot) {
                HorizontalDivider(color = cs.outlineVariant)
                val granted = m.rootGranted
                StatusRow(
                    label = "Root",
                    value = when (granted) {
                        true -> "Granted"
                        false -> "Not granted"
                        else -> "Checking…"
                    },
                    valueColor = when (granted) {
                        true -> x.good
                        false -> cs.error
                        else -> cs.onSurfaceVariant
                    },
                    sub = m.rootInfo.ifEmpty { if (granted == false) "App backups need root" else "" },
                    icon = if (granted == false) Icons.Filled.Error else Icons.Filled.CheckCircle,
                    iconColor = if (granted == false) cs.error else x.good,
                ) { m.checkRoot() }
            }

            if (m.ready) {
                HorizontalDivider(color = cs.outlineVariant)
                val bytes = m.repoBytes
                val snaps = m.snapList.size
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    CardLabel("Repository storage")
                    Text(
                        if (bytes != null) fmtGb(bytes) else "Reading…",
                        style = MaterialTheme.typography.titleLarge,
                    )
                    if (snaps > 0) {
                        Text(
                            if (snaps == 1) "1 snapshot" else "$snaps snapshots",
                            style = MaterialTheme.typography.bodyMedium,
                            color = cs.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        // Its own card: this is a whole walk-through, not one more app action.
        if (m.repoConfigured) {
            Surface(
                onClick = { m.page = Page.WIZARD },
                shape = MaterialTheme.shapes.large,
                color = cs.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    IconBadge(Icons.Filled.PhoneAndroid, size = 44.dp)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Restore a phone", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            "New or wiped phone? Connect, bring your tags back, then restore your apps and folders step by step.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = cs.onSurfaceVariant,
                        )
                    }
                    Icon(Icons.Filled.ChevronRight, null, tint = cs.onSurfaceVariant)
                }
            }
        }

        if (!m.repoConfigured) {
            Surface(shape = MaterialTheme.shapes.large, color = cs.primaryContainer, contentColor = cs.onPrimaryContainer, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Welcome", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Start a new backup by choosing where it goes. Or, if you already have backups from another phone, restore them step by step.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PillButton("Set up backups", Modifier.weight(1f), strong = true) { m.screen = Screen.REPO }
                        PillButton("Restore a phone", Modifier.weight(1f)) { m.page = Page.WIZARD }
                    }
                }
            }
        }

        if (m.repoConfigured) {
            SectionLabel("Health")
            val last = JobHub.lastSuccess
            val staleDays = if (last > 0) (System.currentTimeMillis() - last) / DAY_MS else Long.MAX_VALUE
            Group(
                {
                    HealthRow(
                        title = "Last backup",
                        sub = if (last <= 0L) "No backup yet" else lastBackupText(last).removePrefix("Last backup "),
                        ok = last > 0L && staleDays < 7,
                        warning = if (last > 0L && staleDays >= 7) "$staleDays days without a backup" else if (last <= 0L) "Nothing is protected yet" else null,
                    )
                },
                {
                    HealthRow(
                        title = "Backup integrity",
                        sub = when {
                            m.lastCheckAt <= 0L -> "Never verified"
                            m.lastCheckOk -> "Verified " + agoText(m.lastCheckAt)
                            else -> "The last check failed"
                        },
                        ok = m.lastCheckAt > 0L && m.lastCheckOk,
                        warning = if (m.lastCheckAt > 0L && !m.lastCheckOk) "Open the log to see why" else null,
                        action = "Verify",
                        enabled = !busy && m.ready,
                    ) { m.runCheck("5%") }
                },
            )
        }

        SectionLabel("Apps")
        Group(
            {
                ActionRow("Back up apps", Icons.Filled.Android, subtitle = "Choose installed apps to save") {
                    m.appsMode = 0
                    m.page = Page.APPS
                }
            },
            {
                ActionRow(
                    "Restore apps",
                    Icons.Filled.Restore,
                    subtitle = if (BuildConfig.PLAY && !m.useRoot) "Needs root in this version" else "Bring apps back with their data",
                    enabled = m.ready && (m.useRoot || !BuildConfig.PLAY),
                ) {
                    m.appsMode = 1
                    m.page = Page.APPS
                }
            },
        )

        SectionLabel("Contacts, calls & messages")
        Group(
            {
                ActionRow(
                    "Back up contacts & messages",
                    Icons.Filled.Contacts,
                    subtitle = "Contacts, call log, text messages and wallpaper. No root needed",
                ) {
                    m.phoneMode = 0
                    m.page = Page.PHONE
                }
            },
            {
                ActionRow(
                    "Restore contacts & messages",
                    Icons.Filled.Restore,
                    subtitle = "Add back what the phone is missing",
                    enabled = m.ready,
                ) {
                    m.phoneMode = 1
                    m.page = Page.PHONE
                }
            },
        )

        SectionLabel("Folders")
        val n = m.folders.size
        Group(
            {
                ActionRow(
                    "Back up folders",
                    Icons.Filled.Folder,
                    subtitle = when (n) {
                        0 -> "Choose folders to save"
                        1 -> "1 saved folder"
                        else -> "$n saved folders"
                    },
                ) {
                    m.foldersMode = 0
                    m.page = Page.FOLDERS
                }
            },
            {
                ActionRow(
                    "Restore folders",
                    Icons.Filled.Restore,
                    subtitle = "Bring folders back, to where they came from or elsewhere",
                    enabled = m.ready,
                ) {
                    m.foldersMode = 1
                    m.page = Page.FOLDERS
                }
            },
        )
    }
}

private const val DAY_MS = 24L * 60 * 60 * 1000

/** One health line: a tick or a warning, what it is, how it stands, and an optional action. */
@Composable
private fun HealthRow(
    title: String,
    sub: String,
    ok: Boolean,
    warning: String? = null,
    action: String? = null,
    enabled: Boolean = true,
    onAction: () -> Unit = {},
) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().padding(start = 14.dp, top = 8.dp, end = 6.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        IconBadge(
            if (ok) Icons.Filled.CheckCircle else Icons.Filled.Warning,
            container = if (ok) cs.primaryContainer else cs.errorContainer,
            tint = if (ok) cs.onPrimaryContainer else cs.onErrorContainer,
        )
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(sub, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
            if (warning != null) Text(warning, style = MaterialTheme.typography.bodyMedium, color = cs.error)
        }
        if (action != null) TextButton(onClick = onAction, enabled = enabled) { Text(action) }
    }
}

/** Status line: small label, coloured value, details, and a round badge on the right. */
@Composable
private fun StatusRow(
    label: String,
    value: String,
    valueColor: androidx.compose.ui.graphics.Color,
    sub: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconColor: androidx.compose.ui.graphics.Color,
    sub2: String? = null,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            CardLabel(label)
            Text(value, style = MaterialTheme.typography.titleLarge, color = valueColor)
            if (sub.isNotEmpty()) {
                Text(sub, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (sub2 != null) Text(sub2, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
        }
        Spacer(Modifier.width(10.dp))
        RoundIconButton(icon, null, size = 40.dp, container = cs.surfaceContainerHigh, tint = iconColor, onClick = onClick)
    }
}
