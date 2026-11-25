package app.sunrestic

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/** Appearance, snapshot clean-up, connection tests, repository health and the log. */
@Composable
fun SettingsScreen(m: AppModel) {
    val ctx = LocalContext.current
    val busy = JobHub.busy
    val canRun = !busy && m.ready
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            val ok = runCatching { ctx.contentResolver.openOutputStream(uri)?.use { it.write(m.exportSettings().toByteArray()) } }.isSuccess
            Toast.makeText(ctx, if (ok) "Settings exported" else "Could not write the file", Toast.LENGTH_SHORT).show()
        }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val text = runCatching { ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } }.getOrNull()
            val ok = text != null && m.importSettings(text)
            Toast.makeText(ctx, if (ok) "Settings imported" else "That is not a settings file", Toast.LENGTH_SHORT).show()
        }
    }

    ScreenScroll {
        Group({
            ActionRow(
                title = "Check everything",
                subtitle = "Tests the connection, repository, alarm and permissions, and says what is wrong",
                icon = Icons.Filled.CheckCircle,
            ) { m.runSelfCheckFresh() }
        })

        SectionLabel("Appearance", icon = Icons.Filled.DarkMode)
        Group({
            Box(Modifier.padding(horizontal = 18.dp, vertical = 8.dp)) {
                SwitchRow(
                    title = "Pure black in dark mode",
                    subtitle = "AMOLED background; cards become near-black",
                    checked = m.amoled,
                    icon = Icons.Filled.DarkMode,
                    onChange = { m.amoled = it },
                )
            }
        })

        SectionLabel("Security", icon = Icons.Filled.Lock)
        SectionCard(
            "Lock after",
            icon = Icons.Filled.Lock,
            subtitle = "The repository password is kept only in memory. After this long in the background the app locks " +
                "and asks for your fingerprint again. It also locks if the app is closed.",
        ) {
            @OptIn(ExperimentalLayoutApi::class)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(1 to "1 min", 5 to "5 min", 15 to "15 min", 60 to "1 hour", 0 to "Never").forEach { (v, label) ->
                    ChoiceChip(label, selected = m.lockMinutes == v) { m.lockMinutes = v }
                }
            }
        }

        SectionLabel("Snapshots", icon = Icons.Filled.CleaningServices)
        SectionCard(
            "Keep rules",
            icon = Icons.Filled.CleaningServices,
            subtitle = "Keeps the newest few of each kind, plus one per day, week or month going back. " +
                "Never removes the only backup of an app.",
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Field("Newest", m.keepLast, Modifier.weight(1f), number = true) { m.keepLast = it }
                Field("Daily", m.keepDaily, Modifier.weight(1f), number = true) { m.keepDaily = it }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Field("Weekly", m.keepWeekly, Modifier.weight(1f), number = true) { m.keepWeekly = it }
                Field("Monthly", m.keepMonthly, Modifier.weight(1f), number = true) { m.keepMonthly = it }
            }
            PillButton(
                "Prune old snapshots",
                Modifier.fillMaxWidth(),
                strong = true,
                enabled = canRun && m.retention != null,
            ) { m.askPrune() }
        }
        Group(
            {
                ActionRow(
                    title = "Repo size",
                    subtitle = "Show how much space the repository uses",
                    icon = Icons.Filled.Storage,
                    enabled = canRun,
                ) { m.go("restic stats") { m.runner.stats(it) } }
            },
            {
                ActionRow(
                    title = "Delete restore safety copies",
                    subtitle = "Restoring keeps the data an app had before, until its next restore. This frees that space.",
                    icon = Icons.Filled.CleaningServices,
                    enabled = !busy && m.useRoot,
                ) {
                    m.confirm = "Delete the safety copies of app data kept by earlier restores? You can then no longer go back to them." to {
                        m.go("delete restore safety copies", openLog = false) { m.runner.clearRestoreCopies() }
                    }
                }
            },
            {
                ActionRow(
                    title = "Reclaim space",
                    subtitle = "Remove data no snapshot needs any more; can be slow over sftp",
                    icon = Icons.Filled.CleaningServices,
                    enabled = canRun,
                ) { m.go("prune") { m.runner.prune(it) } }
            },
        )

        SectionLabel("Connection", icon = Icons.Filled.Dns)
        Group(
            {
                ActionRow(
                    title = "Test SSH",
                    subtitle = if (m.backend == "sftp") "Try to connect to the server with your key" else "Only for SFTP repositories",
                    icon = Icons.Filled.Dns,
                    enabled = !busy && m.repoConfigured && m.backend == "sftp",
                ) { m.go("ssh test") { m.runner.testSsh(it) } }
            },
            {
                ActionRow(
                    title = "Tool versions",
                    subtitle = "Show the bundled restic and ssh versions",
                    icon = Icons.Filled.Info,
                    enabled = !busy,
                ) { m.go("versions") { m.runner.versions(it) } }
            },
        )

        SectionLabel("Health", icon = Icons.Filled.CheckCircle)
        Group(
            {
                ActionRow(
                    title = "Check repository",
                    subtitle = "Verifies the structure and the snapshot index",
                    icon = Icons.Filled.CheckCircle,
                    enabled = canRun,
                ) { m.runCheck(null) }
            },
            {
                ActionRow(
                    title = "Deep check (5% of data)",
                    subtitle = "Also reads a random 5% of the stored data; can take a while over sftp",
                    icon = Icons.Filled.Storage,
                    enabled = canRun,
                ) { m.runCheck("5%") }
            },
            {
                ActionRow(
                    title = "Unlock repository",
                    subtitle = "Removes stale locks left behind by a crashed run",
                    icon = Icons.Filled.LockOpen,
                    enabled = canRun,
                    destructive = true,
                ) {
                    m.confirm = "Remove ALL repo locks? Only if nothing else is using the repo." to {
                        m.go("unlock") { m.runner.unlock(it) }
                    }
                }
            },
        )

        SectionLabel("Settings backup", icon = Icons.Filled.Info)
        Group(
            {
                ActionRow(
                    title = "Export settings",
                    subtitle = "Repository address, schedule, folders and tags. Passwords and keys are not included.",
                    icon = Icons.Filled.Upload,
                ) { exporter.launch("sunrestic-settings.json") }
            },
            {
                ActionRow(
                    title = "Import settings",
                    subtitle = "Load a file made by Export settings",
                    icon = Icons.Filled.Download,
                ) { importer.launch(arrayOf("application/json", "text/plain", "*/*")) }
            },
        )

        SectionLabel("Log", icon = Icons.Filled.Build)
        Group(
            {
                ActionRow(
                    title = "Open log",
                    subtitle = "See the output of the latest actions",
                    icon = Icons.Filled.Description,
                ) { m.showLog = true }
            },
            {
                ActionRow(
                    title = "Copy saved log",
                    subtitle = "The full history is kept on the phone, also after the app restarts",
                    icon = Icons.Filled.ContentCopy,
                ) {
                    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("log", JobHub.savedLog()))
                    Toast.makeText(ctx, "Log copied", Toast.LENGTH_SHORT).show()
                }
            },
        )
    }
}
