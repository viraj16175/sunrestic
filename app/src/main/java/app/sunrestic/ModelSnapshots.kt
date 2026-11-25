package app.sunrestic

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import kotlin.concurrent.thread

/** Snapshots: listing, opening, restoring one, deleting, pruning, per-app history, last backup. These are extensions of [AppModel], which holds the state. */

/** Every backup that holds [pkg], newest first, with what that backup saved of the app. */
internal fun AppModel.historyOf(pkg: String): List<Pair<SnapInfo, AppInfo>> =
    snapList.filter { "android-apps" in it.tags }.mapNotNull { s -> snapApps[s.id]?.firstOrNull { it.pkg == pkg }?.let { s to it } }

/** Restores one app from one specific snapshot, with the components currently chosen. */
internal fun AppModel.restoreVersion(snapId: String, pkg: String) {
    detailSel = setOf(pkg)
    startRestoreFromSnapshot(snapId)
}

/**
 * "Last backup" used to come only from this phone's own memory, so after clearing the app's data it said nothing was
 * protected even though the repository is full. This reads the newest snapshot's time from the repository instead.
 */
internal fun AppModel.syncLastBackupFromRepo() {
    if (!ready || JobHub.busy || lastBackupSyncing || lastBackupCheckedStamp == JobHub.dataVersion) return
    lastBackupSyncing = true
    val c = config()
    val stamp = JobHub.dataVersion
    thread {
        try {
            val newest = (reader.listSnapshots(c).maxOfOrNull { parseResticTime(it.time) ?: 0L } ?: 0L)
                .coerceAtMost(System.currentTimeMillis()) // a snapshot stamped ahead of this phone is not "later than now"
            lastBackupCheckedStamp = stamp
            if (newest > JobHub.lastSuccess) {
                JobHub.lastSuccess = newest
                prefs.edit().putLong("lastBackupAt", newest).apply()
                runCatching { BackupWidget.refresh(ctx) }
            }
        } catch (e: Exception) {
            // Leave what is shown as it is; the next change of state tries again.
        } finally {
            lastBackupSyncing = false
        }
    }
}

/**
 * Reads the repository size when it could have changed: after a finished backup, or when the saved value is older
 * than 6 hours. Reading it walks the repository index, so it is not repeated on every unlock.
 */
internal fun AppModel.refreshRepoBytesIfStale() {
    if (!ready || JobHub.busy || loadingRepoBytes) return
    val fresh = repoBytes != null && repoBytesStamp == JobHub.lastSuccess &&
        System.currentTimeMillis() - repoBytesAt < 6 * 60 * 60 * 1000L
    if (fresh) return
    loadingRepoBytes = true
    val c = config()
    val stamp = JobHub.lastSuccess
    thread {
        try {
            val bytes = reader.repoBytes(c)
            if (bytes != null) {
                repoBytes = bytes
                repoBytesStamp = stamp
                repoBytesAt = System.currentTimeMillis()
                prefs.edit().putLong("repoBytes", bytes).putLong("repoBytesStamp", stamp).putLong("repoBytesAt", repoBytesAt).apply()
            }
        } finally {
            loadingRepoBytes = false
        }
    }
}

/** Lists snapshots only when that has not happened yet or a backup finished since. Tab switches stay instant. */
internal fun AppModel.refreshSnapshotsIfStale() {
    if (snapListStamp != JobHub.dataVersion.toLong()) loadSnapshotList()
}

/** Reads the snapshot list (newest first). Skipped while a job runs, so it never touches that job's process. */
internal fun AppModel.loadSnapshotList() {
    if (loadingSnaps || !ready || JobHub.busy) return
    loadingSnaps = true
    val c = config()
    val stamp = JobHub.dataVersion.toLong()
    thread {
        try {
            snapList = reader.listSnapshots(c).reversed()
            snapListStamp = stamp
            connectionProblem = ""
        } catch (e: Exception) {
            JobHub.append("error: could not list snapshots (${e.message})\n")
            connectionProblem = shortReason(e.message)
        }
        loadingSnaps = false
        loadSnapApps(c)
    }
}

/** Reads which apps each apps snapshot holds, newest first, so the list can show their icons. */
internal fun AppModel.loadSnapApps(c: Config) {
    if (loadingSnapApps) return
    loadingSnapApps = true
    snapAppsProblem = ""
    try {
        // Newest first, three at a time: each read is its own restic run (connect, load the index, dump one file).
        val queue = java.util.concurrent.ConcurrentLinkedQueue(snapList.filter { "android-apps" in it.tags && it.id !in snapApps })
        val workers = (1..minOf(3, queue.size)).map {
            thread {
                while (true) {
                    val s = queue.poll() ?: break
                    val apps = runCatching { reader.snapshotApps(c, s) { why -> snapAppsProblem = "backup ${s.id.take(8)}: $why" } }.getOrNull() ?: continue
                    synchronized(this) { snapApps = snapApps + (s.id to apps.filter { it.pkg != ctx.packageName }) }
                }
            }
        }
        workers.forEach { it.join() }
    } finally {
        loadingSnapApps = false
    }
}

/** Opens the details page of [s] and loads its contents and sizes in the background. */
internal fun AppModel.openSnapshot(s: SnapInfo) {
    openSnap = s
    page = Page.SNAPSHOT
    detailSizes = null
    detailError = ""
    detailSel = snapApps[s.id]?.map { it.pkg }?.toSet() ?: emptySet()
    if (!ready) return
    loadingDetail = true
    val c = config()
    thread {
        try {
            val isApps = "android-apps" in s.tags
            val apps = if (isApps) {
                snapApps[s.id] ?: reader.snapshotApps(c, s)?.filter { it.pkg != ctx.packageName }?.also {
                    snapApps = snapApps + (s.id to it)
                }
            } else null
            if (isApps && detailSel.isEmpty() && apps != null && openSnap?.id == s.id) {
                detailSel = apps.map { it.pkg }.toSet()
            }
            val roots = apps?.flatMap { a -> Component.values().flatMap { it.paths(a) } } ?: s.pathList
            val sizes = reader.snapshotSizes(c, s.id, roots)
            if (openSnap?.id == s.id) detailSizes = sizes
        } catch (e: Exception) {
            JobHub.append("error: could not read snapshot ${s.id.take(8)} (${e.message})\n")
            if (openSnap?.id == s.id) detailError = "Sizes not available"
        }
        loadingDetail = false
    }
}

internal fun AppModel.closePage() {
    page = null
}

/** Restores the ticked apps of the open snapshot from exactly that snapshot. Uses the same restore code. */
internal fun AppModel.startRestoreFromSnapshot(snapId: String) {
    val apps = snapApps[snapId].orEmpty().filter { it.pkg in detailSel }
    if (JobHub.busy || apps.isEmpty()) return
    prepareJob("Restoring from snapshot")
    JobHub.restoreApps = apps.map { it.copy(snapshot = snapId) }
    JobHub.restoreComps = comps
    startServiceAfterPreflight(BackupService.ACTION_RESTORE_APPS)
}

internal fun AppModel.deleteSnapshot(id: String) {
    go("forget ${id.take(8)}") { cfg ->
        val r = runner.forgetIds(cfg, listOf(id))
        if (r.trimEnd().endsWith("[exit 0]")) {
            snapList = snapList.filter { it.id != id }
            snapApps = snapApps - id
            backedStamp = null // the "last backup" of its apps may now be an older snapshot
        }
        r
    }
}

/**
 * "Keep newest N", made safe: first works out which snapshots can go without taking away the only
 * backup of any app, then asks for confirmation with the exact count.
 */
internal fun AppModel.askPrune() {
    val policy = retention ?: return
    if (JobHub.busy) return
    JobHub.busy = true
    val c = config()
    JobHub.config = c
    JobHub.append("\n$ planning prune (keep ${policy.summary} of each kind)\n")
    thread {
        try {
            val plan = runner.planPrune(c, policy)
            JobHub.busy = false
            if (plan.delete.isEmpty()) {
                JobHub.append("nothing to delete\n")
                showLog = true
            } else {
                val ids = plan.delete.map { it.id }
                val idSet = ids.toSet()
                val kept = if (plan.protectedSnapshots > 0) {
                    "\n\n${plan.protectedSnapshots} older snapshot(s) are kept because they hold the newest backup of: " +
                        plan.protectedApps.joinToString().take(200) + "."
                } else ""
                confirm = "Delete ${plan.delete.size} old snapshot(s)? Kept for each kind: ${policy.summary}.$kept" to {
                    go("forget ${ids.size} snapshots") { cfg ->
                        val r = runner.forgetIds(cfg, ids)
                        if (r.trimEnd().endsWith("[exit 0]")) {
                            snapList = snapList.filter { it.id !in idSet }
                            snapApps = snapApps - idSet
                            backedStamp = null
                        }
                        r
                    }
                }
            }
        } catch (e: Exception) {
            JobHub.busy = false
            JobHub.append("error: could not plan the prune (${e.message}). Nothing was deleted.\n")
            showLog = true
        }
    }
}
