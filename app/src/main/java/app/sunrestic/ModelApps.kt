package app.sunrestic

import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import kotlin.concurrent.thread

/** Apps: loading, selecting, backing up and restoring. These are extensions of [AppModel], which holds the state. */

/** Ticks [pkgs] in the restore list and opens it, ready for "Restore options". */
internal fun AppModel.openRestoreWith(pkgs: Set<String>) {
    restoreSel = pkgs
    appFilter = AppFilter.ALL
    tagFilter = ""
    appsMode = 1
    page = Page.APPS
}

@Volatile private var estimateSeq = 0

internal fun AppModel.estimateSelected(pkgs: Set<String>, comps: Set<Component>) {
    val seq = ++estimateSeq
    estimateBytes = null
    if (!useRoot || pkgs.isEmpty()) return
    val list = (apps.ifEmpty { return }).filter { it.pkg in pkgs }
    thread {
        val bytes = runCatching { AppBackup.sizeOf(AppBackup.existing(comps.flatMap { c -> list.flatMap { c.paths(it) } })) }.getOrNull()
        if (seq == estimateSeq) estimateBytes = bytes // only the newest question may answer
    }
}

// ---- apps ---------------------------------------------------------------

internal fun AppModel.toggleApp(pkg: String, restore: Boolean) {
    if (restore) {
        restoreSel = if (pkg in restoreSel) restoreSel - pkg else restoreSel + pkg
    } else {
        selected = if (pkg in selected) selected - pkg else selected + pkg
    }
}

internal fun AppModel.setAppSelection(restore: Boolean, pkgs: Set<String>) {
    if (restore) restoreSel = pkgs else selected = pkgs
}

internal fun AppModel.loadApps() {
    if (loadingApps) return
    loadingApps = true
    loadDone = 0
    loadTotal = 0
    thread {
        try {
            apps = AppBackup.listApps(ctx) { done, total ->
                // Update the UI every 15 apps, not 300 times in a row.
                if (done == total || done % 15 == 0) {
                    loadDone = done
                    loadTotal = total
                }
            }
        } catch (e: Exception) {
            JobHub.append("error: ${e.message}\n")
        }
        loadingApps = false
    }
}

/** Reads the backed-up app list when it was never read, or a backup / delete happened since. */
internal fun AppModel.refreshBackedIfStale() {
    if (ready && !JobHub.busy && backedStamp != JobHub.dataVersion.toLong()) loadBacked()
}

internal fun AppModel.loadBacked() {
    if (loadingBacked) return
    loadingBacked = true
    val c = config()
    val stamp = JobHub.dataVersion.toLong()
    thread {
        try {
            val pm = ctx.packageManager
            // The reader, not the job runner: a Cancel must never hit a lookup that happens to be running.
            val problems = ArrayList<String>()
            backed = reader.readManifest(c) { problems += it }.filter { it.pkg != ctx.packageName }.map { a ->
                val installed = runCatching { pm.getPackageInfo(a.pkg, 0) }.getOrNull()
                a.copy(installedVersion = installed?.let { it.versionName ?: "" })
            }
            backedLoaded = true
            backedStamp = stamp
            connectionProblem = ""
            backedProblem = if (problems.isEmpty()) "" else "${problems.size} backup(s) could not be read. ${problems.first()}"
            problems.forEach { JobHub.append("backed-up apps: $it\n") }
        } catch (e: Exception) {
            JobHub.append("error: could not read backed-up apps (${e.message})\n")
            connectionProblem = shortReason(e.message)
        }
        loadingBacked = false
    }
}

internal fun AppModel.startAppBackup() {
    if (JobHub.busy || selected.isEmpty()) return
    autoApps = selected.joinToString(",") // remembered for the daily reminder
    // Backing up exactly the (installed) apps of a tag counts as a backup of that tag.
    val installed = apps.map { it.pkg }.toSet()
    val exact = tagNames.filter { t ->
        val members = appTags[t].orEmpty().intersect(installed)
        members.isNotEmpty() && members == selected
    }
    launchAppBackup(selected, exact)
}

/** [snapTags] are put on the snapshot next to "android-apps" (the tags this run is a backup of). */
internal fun AppModel.launchAppBackup(pkgs: Set<String>, snapTags: List<String> = emptyList()) {
    if (JobHub.busy) return
    prepareJob("Backing up apps")
    // Without root only the APKs can be read, so only those are backed up.
    val root = useRoot
    val wantedComps = if (root) comps else setOf(Component.APK)
    val stop = forceStop && root
    thread {
        try {
            val err = preflight()
            if (err != null) {
                fail(err)
                return@thread
            }
            val source = apps.ifEmpty { AppBackup.listApps(ctx) }
            val chosen = source.filter { it.pkg in pkgs }
            if (chosen.isEmpty()) {
                fail("none of the selected apps are installed")
                return@thread
            }
            val gone = pkgs - chosen.map { it.pkg }.toSet()
            if (gone.isNotEmpty()) JobHub.append("\nskipped (not installed): ${gone.joinToString()}\n")
            if (snapTags.isNotEmpty()) JobHub.append("\ntagged backup: ${snapTags.joinToString()}\n")
            if (stop) {
                val vpn = Net.vpnPackages(ctx)
                val toStop = AppBackup.stoppable(chosen.map { it.pkg }, ctx.packageName, vpn)
                AppBackup.forceStop(toStop)
                JobHub.append("\nforce-stopped ${toStop.size} apps\n")
                val kept = chosen.map { it.pkg }.filter { it in vpn }
                if (kept.isNotEmpty()) JobHub.append("kept running (VPN, the backup uses its connection): ${kept.joinToString()}\n")
            }
            val all = AppBackup.plan(ctx, chosen, wantedComps)
            val ex = AppBackup.existing(all, root)
            JobHub.append("apps: ${chosen.size} selected, ${ex.size}/${all.size} paths exist\n")
            ctx.startForegroundService(
                Intent(ctx, BackupService::class.java)
                    .setAction(BackupService.ACTION_BACKUP_APPS)
                    .putExtra(BackupService.EXTRA_PATHS, ex.toTypedArray())
                    .putExtra(BackupService.EXTRA_TAGS, snapTags.toTypedArray())
            )
        } catch (e: Exception) {
            fail(e.message.orEmpty())
        }
    }
}

/** Restores exactly these apps (each from the backup named in [AppInfo.snapshot]) with the chosen components. */
internal fun AppModel.startRestoreAppList(apps: List<AppInfo>, title: String = "Restoring apps") {
    if (JobHub.busy || apps.isEmpty()) return
    prepareJob(title)
    JobHub.restoreApps = apps
    JobHub.restoreComps = comps
    startServiceAfterPreflight(BackupService.ACTION_RESTORE_APPS)
}

internal fun AppModel.startRestoreApps() {
    val snap = snapOverride.trim()
    startRestoreAppList(
        backed.filter { it.pkg in restoreSel }.map { if (snap.isNotEmpty()) it.copy(snapshot = snap) else it },
    )
}
