package app.sunrestic

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.concurrent.thread

internal fun parseFolders(raw: String): List<String> = runCatching {
    val a = JSONArray(raw)
    (0 until a.length()).map { a.getString(it) }
}.getOrDefault(emptyList())

internal fun parseComps(raw: String): Set<Component> =
    raw.split(',').mapNotNull { n -> Component.values().firstOrNull { it.name == n } }.toSet()

/** Saved tags: tag name -> packages carrying it. */
internal fun parseTags(raw: String): Map<String, Set<String>> = runCatching {
    val o = JSONObject(raw)
    val out = LinkedHashMap<String, Set<String>>()
    for (k in o.keys()) {
        val a = o.getJSONArray(k)
        out[k] = (0 until a.length()).map { a.getString(it) }.toSet()
    }
    out
}.getOrDefault(emptyMap())

/** The connection settings as one unit, so they can be edited together and saved with one tap. */
data class RepoDraft(
    val backend: String,
    val host: String = "",
    val port: String = "22",
    val user: String = "",
    val repoPath: String = "",
    val keyPath: String = "",
    val localRepo: String = "",
    val endpoint: String = "",
    val bucket: String = "",
    val accessKey: String = "",
    val secretKey: String = "",
) {
    /** What is still missing, in plain words; empty when the repository can be used. */
    val missing: List<String>
        get() = when (backend) {
            "local" -> listOfNotNull("Folder".takeIf { localRepo.isBlank() })
            "s3" -> listOfNotNull(
                "Endpoint".takeIf { endpoint.isBlank() }, "Bucket".takeIf { bucket.isBlank() },
                "Access key".takeIf { accessKey.isBlank() }, "Secret key".takeIf { secretKey.isBlank() },
            )
            "rest" -> listOfNotNull("Server address".takeIf { endpoint.isBlank() })
            "b2" -> listOfNotNull(
                "Bucket".takeIf { bucket.isBlank() }, "Account ID".takeIf { accessKey.isBlank() },
                "Application key".takeIf { secretKey.isBlank() },
            )
            else -> listOfNotNull(
                "Host".takeIf { host.isBlank() }, "User".takeIf { user.isBlank() },
                "Repo path".takeIf { repoPath.isBlank() }, "SSH key".takeIf { keyPath.isBlank() },
            )
        }
    val complete: Boolean get() = missing.isEmpty()
}

/** All UI state and actions. Survives rotation; most settings are also saved to disk. */
class AppModel(app: Application) : AndroidViewModel(app) {
    internal val ctx: Application get() = getApplication()
    internal val prefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE)
    val runner = ResticRunner(app)
    /** Separate runner for read-only lookups on the snapshot pages, so they never touch a running job's process. */
    internal val reader = ResticRunner(app)

    init {
        // Older versions only knew "remote on/off". Write the backend once, so the service and receivers read the same value.
        if (!prefs.contains("backend")) {
            prefs.edit().putString("backend", if (prefs.getBoolean("remote", true)) "sftp" else "local").apply()
        }
        JobHub.runner = runner
        JobHub.logFile = File(app.filesDir, "restic-log.txt")
        JobHub.lastSuccess = prefs.getLong("lastBackupAt", 0L).coerceAtMost(System.currentTimeMillis())
    }

    // navigation / dialogs
    var screen by mutableStateOf(Screen.HOME)
    /** A full-screen page shown over the tabs (no bottom bar), or null. */
    var page by mutableStateOf<Page?>(null)
    var openSnap by mutableStateOf<SnapInfo?>(null)
    var showLog by mutableStateOf(false)
    var confirm by mutableStateOf<Pair<String, () -> Unit>?>(null)

    // appearance
    var amoled by PersistedBool(prefs, "amoled", false)

    // repository settings (saved, except the password). Nothing is pre-filled: the user sets them up.
    /** "sftp", "local", "s3", "rest" or "b2". Older versions only had a remote on/off switch. */
    var backend by PersistedString(prefs, "backend", if (prefs.getBoolean("remote", true)) "sftp" else "local")
    val remote: Boolean get() = backend != "local"
    val backendLabel: String
        get() = when (backend) { "sftp" -> "SFTP"; "local" -> "Local"; "s3" -> "S3"; "rest" -> "REST"; else -> "B2" }
    var endpoint by PersistedString(prefs, "endpoint", "")
    var bucket by PersistedString(prefs, "bucket", "")
    var accessKey by PersistedString(prefs, "accessKey", "")
    var secretKey by PersistedString(prefs, "secretKey", "")
    var host by PersistedString(prefs, "host", "")
    var port by PersistedString(prefs, "port", "22")
    var user by PersistedString(prefs, "user", "")
    var repoPath by PersistedString(prefs, "repoPath", "")
    var keyPath by PersistedString(prefs, "keyPath", "")
    var localRepo by PersistedString(prefs, "localRepo", "")
    var dns by PersistedString(prefs, "dns", "1.1.1.1:53")
    var useRoot by PersistedBool(prefs, "useRoot", true)
    var wifiOnly by PersistedBool(prefs, "wifiOnly", false)
    var verifyAfter by PersistedBool(prefs, "verifyAfter", false) // also read by BackupService

    // password: only in memory here; the encrypted copy lives in Vault
    var password by mutableStateOf("")
    var vaultSaved by mutableStateOf(Vault.has(app))
    var unlockTried by mutableStateOf(false)
    var unlocking by mutableStateOf(false)

    // daily reminder
    var schedOn by PersistedBool(prefs, "schedOn", false) // also read by Scheduler receivers
    var schedHour by PersistedString(prefs, "schedHour", "8")
    var schedMinute by PersistedString(prefs, "schedMinute", "0")
    var schedFolders by PersistedBool(prefs, "schedFolders", true)
    var schedApps by PersistedBool(prefs, "schedApps", false)
    var autoApps by PersistedString(prefs, "autoApps", "") // apps of the last app backup, comma separated
    /** Which apps the apps task backs up: "last" = those of the last app backup, "tags" = the apps carrying [schedTags]. */
    var schedAppMode by PersistedString(prefs, "schedAppMode", "last")
    internal var schedTagsRaw by PersistedString(prefs, "schedTags", "") // tag names, comma separated
    /** Back up by itself at the chosen time (needs the unattended password). Also read by the service and receivers. */
    var autoBackup by PersistedBool(prefs, "autoBackup", false)
    var autoCharging by PersistedBool(prefs, "autoCharging", true)
    var unattendedSaved by mutableStateOf(Vault.hasUnattended(app))
        internal set
    var pendingScheduled by mutableStateOf(false)
    var foregroundTick by mutableStateOf(0)
    /** True only while the screen is in the foreground and interactive; the fingerprint prompt needs that. */
    var resumed by mutableStateOf(false)
    /** Bumped when the Schedules page should re-read notification / alarm / battery status. */
    var healthTick by mutableStateOf(0)
    internal var backgroundedAt = 0L

    // folders (saved). Kept as real state so screens don't re-parse JSON on every recomposition.
    var restoreTarget by PersistedString(prefs, "restoreTarget", "")
    internal var foldersRaw by PersistedString(prefs, "folders", "[]")
    var folders by mutableStateOf(parseFolders(foldersRaw))
        internal set

    /** Why the repository could not be read just now ("" = fine). Shown on Home and above empty lists, never as "no backups". */
    var connectionProblem by mutableStateOf("")
        internal set

    /** Turns a long restic/ssh error into one short line a person can act on. */
    internal fun shortReason(raw: String?): String {
        val t = raw.orEmpty().trim()
        return when {
            "wrong password" in t.lowercase() || "no key" in t.lowercase() -> "Wrong repository password"
            "locked" in t.lowercase() -> "The repository is locked by another job. Settings, Unlock repository"
            "cannot reach" in t || "timed out" in t || "no route" in t.lowercase() || "connection refused" in t.lowercase() ->
                "Can't reach the server. Is the VPN (WireGuard) connected?"
            "unable to open config" in t || "does not exist" in t -> "No repository here yet, or the path is wrong"
            t.isBlank() -> "Could not read the repository"
            else -> t.lines().first().take(140)
        }
    }

    /** Try reading the repository again after a problem. */
    fun retryConnection() {
        connectionProblem = ""
        snapListStamp = null
        backedStamp = null
        loadSnapshotList()
        loadBacked()
    }

    // snapshots
    var keepLast by PersistedString(prefs, "keepLast", "3")
    var keepDaily by PersistedString(prefs, "keepDaily", "0")
    var keepWeekly by PersistedString(prefs, "keepWeekly", "0")
    var keepMonthly by PersistedString(prefs, "keepMonthly", "0")

    val retention: RetentionPolicy?
        get() {
            val last = keepLast.toIntOrNull() ?: return null
            return RetentionPolicy(
                last.coerceAtLeast(1),
                (keepDaily.toIntOrNull() ?: 0).coerceAtLeast(0),
                (keepWeekly.toIntOrNull() ?: 0).coerceAtLeast(0),
                (keepMonthly.toIntOrNull() ?: 0).coerceAtLeast(0),
            )
        }
    var snapList by mutableStateOf<List<SnapInfo>>(emptyList()) // newest first
    var loadingSnaps by mutableStateOf(false)
    /** Apps inside each android-apps snapshot, by snapshot ID (filled in after the list loads). */
    var snapApps by mutableStateOf<Map<String, List<AppInfo>>>(emptyMap())
    var loadingSnapApps by mutableStateOf(false)
    /** Why the last app list could not be read (empty when all went fine). */
    var snapAppsProblem by mutableStateOf("")
        internal set

    // snapshot details page
    var detailSizes by mutableStateOf<Map<String, Long>?>(null)
    var detailError by mutableStateOf("")
    var loadingDetail by mutableStateOf(false)
    var detailSel by mutableStateOf(setOf<String>())

    // root status shown on Home
    var rootGranted by mutableStateOf<Boolean?>(null)
    var rootInfo by mutableStateOf("")
    internal var rootChecking = false

    // apps
    var appsMode by mutableStateOf(0) // 0 = back up, 1 = restore
    var appQuery by mutableStateOf("")
    var appFilter by mutableStateOf(AppFilter.ALL)
    var forceStop by PersistedBool(prefs, "forceStop", true)
    internal var compsRaw by PersistedString(prefs, "comps", "APK,DATA,DEVICE_DATA")
    internal var compsState by mutableStateOf(parseComps(compsRaw))
    var comps: Set<Component>
        get() = compsState
        set(v) {
            compsState = v
            compsRaw = v.joinToString(",") { it.name }
        }
    // tags: the user's own labels on apps ("Daily", "Games"); a task can back up just the tagged apps
    internal var tagsRaw by PersistedString(prefs, "appTags", "{}")
    var appTags by mutableStateOf(parseTags(tagsRaw))
        internal set
    /** Apps page: only show apps carrying this tag ("" = off). */
    var tagFilter by mutableStateOf("")
    var apps by mutableStateOf<List<AppInfo>>(emptyList())
    var selected by mutableStateOf(setOf<String>())
    var loadingApps by mutableStateOf(false)
    var loadDone by mutableStateOf(0)
    var loadTotal by mutableStateOf(0)
    var backed by mutableStateOf<List<AppInfo>>(emptyList())
    var restoreSel by mutableStateOf(setOf<String>())
    var loadingBacked by mutableStateOf(false)
    /** True once the backed-up app list was read at least once (so "no backup" is known, not just unknown). */
    var backedLoaded by mutableStateOf(false)
    /** Why the backed-up app list is incomplete, or empty when it is fine. */
    var backedProblem by mutableStateOf("")
        internal set
    var snapOverride by mutableStateOf("")

    /** True once the repository location is filled in. */
    val repoConfigured: Boolean get() = repoDraft().complete

    fun repoDraft() = RepoDraft(backend, host, port, user, repoPath, keyPath, localRepo, endpoint, bucket, accessKey, secretKey)

    /**
     * Saves edited connection settings in one go. Everything that was read from the old repository (snapshots, backed-up
     * apps, size, tag sync) is dropped, so the new settings are used straight away, without restarting the app.
     */
    fun applyRepository(edited: RepoDraft) {
        val before = repoDraft()
        var d = edited
        val pending = File(ctx.filesDir, "ssh_key_pending")
        if (d.keyPath == pending.absolutePath && pending.exists()) {
            val dst = File(ctx.filesDir, "ssh_key")
            if (pending.renameTo(dst)) d = d.copy(keyPath = dst.absolutePath)
        }
        backend = d.backend; host = d.host.trim(); port = d.port.trim().ifEmpty { "22" }; user = d.user.trim()
        repoPath = d.repoPath.trim(); keyPath = d.keyPath.trim(); localRepo = d.localRepo.trim()
        endpoint = d.endpoint.trim(); bucket = d.bucket.trim(); accessKey = d.accessKey.trim(); secretKey = d.secretKey.trim()
        if (repoDraft() != before) onRepositoryChanged()
    }

    /** Sets (and optionally remembers with the fingerprint) the repository password. */
    fun applyPassword(pw: String, remember: Boolean, activity: Activity?) {
        if (pw.isEmpty()) return
        val changed = pw != password
        password = pw
        if (remember && activity != null) savePassword(activity)
        if (changed) onRepositoryChanged()
    }

    /** Forgets everything that was learned from the previous repository or password. */
    internal fun onRepositoryChanged() {
        // The last backup time belongs to the repository too.
        JobHub.lastSuccess = 0L
        prefs.edit().remove("lastBackupAt").apply()
        lastBackupCheckedStamp = -1
        snapList = emptyList(); snapApps = emptyMap(); snapListStamp = null
        backed = emptyList(); backedLoaded = false; backedStamp = null
        restoreSel = emptySet(); historyPkg = null
        detailSizes = null; detailError = ""
        repoBytes = null; repoBytesStamp = -1L; repoBytesAt = 0L
        lastCheckAt = 0L; lastCheckOk = true
        prefs.edit().remove("repoBytes").remove("lastCheckAt").remove("lastCheckOk").apply()
        // The tag file belongs to the repository: start fresh against the new one, keeping what is on this phone.
        tagsBase = 0L
        tagsDirty = appTags.isNotEmpty()
        tagsPulled = false
        tagSync = ""
        syncTagsIfNeeded()
        refreshRepoBytesIfStale()
    }

    /** Repository is set up and the password is available. */
    val ready: Boolean get() = repoConfigured && password.isNotEmpty()

    val repoSummary: String
        get() = when (backend) {
            "local" -> localRepo.trim()
            "s3" -> "s3:${endpoint.trim().trimEnd('/')}/${bucket.trim().trim('/')}"
            "rest" -> "rest:${endpoint.trim()}"
            "b2" -> "b2:${bucket.trim()}"
            else -> "sftp:${user.trim()}@${host.trim()}:${repoPath.trim()}"
        }

    fun config() = Config(
        host.trim(), port.trim(), user.trim(), repoPath.trim(), keyPath.trim(), password, dns.trim(), useRoot, remote, localRepo.trim(),
        backend, endpoint.trim(), bucket.trim(), accessKey.trim(), secretKey.trim(),
    )

    /** Safe from any thread (background jobs have no looper of their own). */
    internal fun toast(msg: String) {
        android.os.Handler(android.os.Looper.getMainLooper()).post { Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show() }
    }

    /** A backup or restore is starting. The progress page only opens when the app is on screen. */
    internal fun beginJob(title: String) = JobHub.begin(title, open = resumed)

    /** Ends a failed start: logs the reason, frees the job lock and drops any chained job. */
    internal fun fail(msg: String) {
        JobHub.append("error: $msg\n")
        JobHub.next = null
        JobHub.busy = false
        if (JobHub.screenJob) {
            JobHub.outcome = "failed"
            JobHub.outcomeText = msg
        }
        if (!JobHub.screenOpen && resumed) showLog = true
        // A job that could not start (for example the apps part after a folder backup) must not leave the service
        // sitting in the foreground. If no service is running this does nothing.
        runCatching { ctx.stopService(Intent(ctx, BackupService::class.java)) }
    }

    /** Minutes in the background before the password is dropped (0 = never). */
    var lockMinutes by PersistedInt(prefs, "lockMinutes", 5)

    // ---- network / key ----------------------------------------------------------

    /** Runs on a worker thread. Returns a message when the job should not start. */
    /** Null when the server answers, otherwise why not. Used before an automatic backup. */
    fun serverProblem(): String? = runner.reachable(config())

    internal fun preflight(): String? {
        if (remote && wifiOnly && !Net.onWifi(ctx)) return "Wi-Fi only is on, and the phone is not on Wi-Fi"
        return runner.reachable(config())
    }

    // ---- tags ---------------------------------------------------------------

    /** Tag names, A to Z. */
    val tagNames: List<String> get() = appTags.keys.sortedBy { it.lowercase() }

    /** The tags the apps task backs up (only ones that still exist). */
    val schedTags: Set<String>
        get() = schedTagsRaw.split(',').filter { it.isNotBlank() && it in appTags }.toSet()

    // ---- per-app history ----------------------------------------------------

    /** The app whose versions are shown in the history sheet (null = closed). */
    var historyPkg by mutableStateOf<String?>(null)

    // ---- last backup, from the repository -------------------------------------

    internal var lastBackupCheckedStamp = -1
    internal var lastBackupSyncing = false

    // ---- self check ----------------------------------------------------------

    var checks by mutableStateOf<List<CheckItem>>(emptyList())
        internal set
    var checking by mutableStateOf(false)
        internal set

    // ---- health -------------------------------------------------------------

    var lastCheckAt by mutableStateOf(prefs.getLong("lastCheckAt", 0L))
        internal set
    var lastCheckOk by mutableStateOf(prefs.getBoolean("lastCheckOk", true))
        internal set

    // ---- size estimate -------------------------------------------------------

    /** Bytes the selected apps hold on this phone (before restic removes duplicates), null while unknown. */
    var estimateBytes by mutableStateOf<Long?>(null)
        internal set

    // ---- tags in the repository ------------------------------------------------

    /** "" = nothing to say, otherwise "syncing", "synced" or "failed". */
    var tagSync by mutableStateOf("")
        internal set
    /** Tags changed on this phone and not yet copied to the repository. */
    var tagsDirty by PersistedBool(prefs, "tagsDirty", false)
        internal set
    internal var tagsUpdated: Long
        get() = prefs.getLong("tagsUpdated", 0L)
        set(v) { prefs.edit().putLong("tagsUpdated", v).apply() }
    /** The version of the repository's tag file this phone last matched. */
    internal var tagsBase: Long
        get() = prefs.getLong("tagsBase", 0L)
        set(v) { prefs.edit().putLong("tagsBase", v).apply() }
    internal var tagsPulled = false
    internal var syncingTags = false

    // ---- renaming a tag on the snapshots that already carry it ------------------

    /** Renames still to be done in the repository, as old>new pairs. Kept on disk, so they survive a restart. */
    internal var pendingRenamesRaw by PersistedString(prefs, "pendingTagRenames", "[]")
    internal var renamesBlocked = false

    // ---- folders ------------------------------------------------------------

    /** 0 = back up phone data, 1 = restore it (contacts, call log, messages). */
    var phoneMode by mutableStateOf(0)
    var phoneSel by mutableStateOf(PhoneKind.values().toSet())
    var phoneRestoreSel by mutableStateOf(PhoneKind.values().toSet())

    /** 0 = back up folders, 1 = restore folders (the folders page, like [appsMode]). */
    var foldersMode by mutableStateOf(0)
    /** Saved folders ticked for the next manual backup. */
    var folderSel by mutableStateOf(setOf<String>())
    /** Folders ticked for restore (their original paths). */
    var folderRestoreSel by mutableStateOf(setOf<String>())

    /** True when folders can be reached: with root, or without it once "All files access" is allowed. */
    val folderAccess: Boolean
        get() {
            foregroundTick // re-read when the app comes back from the settings screen
            return useRoot || Storage.hasAllFiles()
        }

    // ---- jobs ---------------------------------------------------------------

    /** Runs a short restic/ssh command on a thread and prints its output to the log. */
    fun go(label: String, openLog: Boolean = true, block: (Config) -> String) {
        if (JobHub.busy) return
        JobHub.busy = true
        JobHub.screenJob = false // not a backup or restore: the banner opens the log, not the progress page
        val c = config()
        JobHub.config = c
        JobHub.append("\n$ $label\n")
        if (openLog) showLog = true
        thread {
            val result = try { block(c) } catch (e: Exception) { "error: ${e.message}" }
            JobHub.append(result + "\n")
            JobHub.busy = false
        }
    }

    /** [snapshot] is only used by folder restores: a snapshot ID, or null for the newest folders snapshot. */
    fun startJob(action: String, path: String, snapshot: String? = null) {
        if (JobHub.busy) return
        prepareJob(if (action == BackupService.ACTION_RESTORE) "Restoring folders" else "Backing up")
        thread {
            val err = preflight()
            if (err != null) {
                fail(err)
                return@thread
            }
            try {
                val i = Intent(ctx, BackupService::class.java).setAction(action).putExtra(BackupService.EXTRA_PATH, path)
                if (snapshot != null) i.putExtra(BackupService.EXTRA_SNAPSHOT, snapshot)
                ctx.startForegroundService(i)
            } catch (e: Exception) {
                fail("could not start service: ${e.message}")
            }
        }
    }

    // ---- snapshots ----------------------------------------------------------

    /** [JobHub.lastSuccess] at the last successful listing; a newer backup means the list is out of date. */
    internal var snapListStamp: Long? = null

    /** How much space the repository uses (null = not read yet). Kept from the last read, so Home shows it at once. */
    var repoBytes by mutableStateOf<Long?>(prefs.getLong("repoBytes", -1L).takeIf { it >= 0 })
    internal var repoBytesStamp: Long = prefs.getLong("repoBytesStamp", -1L)
    internal var repoBytesAt: Long = prefs.getLong("repoBytesAt", 0L)
    internal var loadingRepoBytes = false

    /** [JobHub.lastSuccess] when the backed-up list was last read; null = never, or a snapshot was deleted since. */
    internal var backedStamp: Long? = null
}
