package app.sunrestic

import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import org.json.JSONArray
import java.io.File
import kotlin.concurrent.thread

/** Folders, phone data and "Restore everything". These are extensions of [AppModel], which holds the state. */

// ---- restore wizard -----------------------------------------------------

/** Ticks [paths] in the folder restore list and opens it, ready for "Restore options". */
internal fun AppModel.openFolderRestoreWith(paths: Set<String>) {
    folderRestoreSel = paths
    foldersMode = 1
    page = Page.FOLDERS
}

internal fun AppModel.addFolder(path: String) {
    val p = path.trim().trimEnd('/')
    if (p.isEmpty() || !p.startsWith("/") || p in folders) return
    folders = folders + p
    folderSel = folderSel + p
    foldersRaw = JSONArray(folders).toString()
}

internal fun AppModel.removeFolder(path: String) {
    folders = folders - path
    folderSel = folderSel - path
    foldersRaw = JSONArray(folders).toString()
}

/** Backs up the saved folders, or only [only] when given (the ticked ones). The scheduled backup always passes null. */
internal fun AppModel.startFolderBackup(only: Set<String>? = null) {
    if (JobHub.busy || (if (only == null) folders else folders.filter { it in only }).isEmpty()) return
    if (!folderAccess) {
        Scheduler.note(ctx, "Folders were not backed up: allow All files access first (Back up folders page)")
        toast("Allow All files access first (Back up folders page)")
        return
    }
    prepareJob("Backing up folders")
    val list = if (only == null) folders else folders.filter { it in only }
    val root = useRoot
    thread {
        try {
            val err = preflight()
            if (err != null) {
                fail(err)
                return@thread
            }
            val ex = if (root) AppBackup.existing(list) else list.filter { File(it).exists() }
            val missing = list.filter { it !in ex }
            if (missing.isNotEmpty()) JobHub.append("\nskipped (not found): ${missing.joinToString()}\n")
            if (ex.isEmpty()) {
                fail("none of the folders exist")
                return@thread
            }
            ctx.startForegroundService(
                Intent(ctx, BackupService::class.java)
                    .setAction(BackupService.ACTION_BACKUP_FOLDERS)
                    .putExtra(BackupService.EXTRA_PATHS, ex.toTypedArray())
            )
        } catch (e: Exception) {
            fail(e.message.orEmpty())
        }
    }
}

/** Restores the folders ticked on the folders page, each from the newest backup that holds it. */
internal fun AppModel.startFolderRestoreItems(items: List<BackedFolder>, target: String, overwrite: String) {
    if (JobHub.busy || items.isEmpty()) return
    JobHub.restoreOverwrite = overwrite
    JobHub.restoreFolderItems = items.map { it.snapshotId to it.path }
    startJob(BackupService.ACTION_RESTORE, target, null)
}

/** Restores folders. [target] "/" = back to where they came from, else a folder of the user's choice. */
internal fun AppModel.startFolderRestore(snapId: String?, target: String, overwrite: String) {
    if (JobHub.busy) return
    JobHub.restoreOverwrite = overwrite
    startJob(BackupService.ACTION_RESTORE, target, snapId)
}

/** Backs up the chosen kinds of phone data (contacts, call log, messages). */
internal fun AppModel.startPhoneBackup(kinds: List<PhoneKind>) {
    if (JobHub.busy || kinds.isEmpty()) return
    prepareJob("Backing up contacts and messages")
    JobHub.phoneKinds = kinds
    startServiceAfterPreflight(BackupService.ACTION_BACKUP_PHONE)
}

/** Restores the chosen kinds, each from "latest" or a snapshot ID. Messages need this app to be the default messaging app. */
internal fun AppModel.startPhoneRestore(what: Map<PhoneKind, String>) {
    if (JobHub.busy || what.isEmpty()) return
    prepareJob("Restoring contacts and messages")
    JobHub.phoneSnaps = what
    startServiceAfterPreflight(BackupService.ACTION_RESTORE_PHONE)
}

/**
 * Restores folders and apps one after the other (folders first). Each part comes from the backups it was given, so
 * "latest" and "one chosen checkpoint" are decided by the caller.
 */
internal fun AppModel.restoreEverything(
    apps: List<AppInfo>, folders: List<BackedFolder>, target: String, overwrite: String,
    phone: Map<PhoneKind, String> = emptyMap(),
) {
    if (JobHub.busy || (apps.isEmpty() && folders.isEmpty() && phone.isEmpty())) return
    // Folders first, then apps, then contacts / calls / messages. Each step starts the next when it succeeds.
    val steps = ArrayList<() -> Unit>()
    if (folders.isNotEmpty()) steps += { startFolderRestoreItems(folders, target, overwrite) }
    if (apps.isNotEmpty()) steps += { startRestoreAppList(apps, "Restoring apps") }
    if (phone.isNotEmpty()) steps += { startPhoneRestore(phone) }
    fun run(i: Int) {
        if (i >= steps.size) return
        if (i + 1 < steps.size) JobHub.next = { run(i + 1) }
        steps[i]()
    }
    run(0)
}
