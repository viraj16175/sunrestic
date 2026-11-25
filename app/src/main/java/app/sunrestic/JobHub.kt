package app.sunrestic

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/** State shared between the UI and BackupService (same process). */
object JobHub {
    /** What the log sheet shows. Updated at most ~6 times per second, whatever the job prints. */
    var log by mutableStateOf("")
    var busy by mutableStateOf(false)
    var progress by mutableStateOf<ResticEvent.Status?>(null)

    /** Goes up every time a backup or restore ends, however it ended. Lists of repository data reload when it changes. */
    var dataVersion by mutableStateOf(0)

    /** The full-screen progress page: shown while it is true. Hiding it never stops the job. */
    var screenOpen by mutableStateOf(false)
    var title by mutableStateOf("")

    /** null while running, then "done", "failed" or "cancelled". */
    var outcome by mutableStateOf<String?>(null)
    var outcomeText by mutableStateOf("")

    /** The running job was started by the automatic backup (its result is written to the "last run" note). */
    @Volatile var autoRun = false

    /** True while the running job is a backup or restore (the progress page belongs to those, not to prune or check). */
    @Volatile var screenJob = false

    /** A backup or restore is starting. [open] = show the progress page (only when someone is looking at the app). */
    fun begin(title: String, open: Boolean = true) {
        restoredSms = false
        this.title = title
        outcome = null
        outcomeText = ""
        screenJob = true
        screenOpen = open
    }

    /** Time (ms) of the last successful backup; 0 = none yet. */
    var lastSuccess by mutableStateOf(0L)

    @Volatile var config: Config? = null
    @Volatile var runner: ResticRunner? = null
    @Volatile var cancelled = false
    @Volatile var restoreApps: List<AppInfo> = emptyList()
    @Volatile var restoreComps: Set<Component> = emptySet()
    /** Folder restore only: restic's rule for files that already exist ("if-changed", "if-newer", "never"). */
    @Volatile var restoreOverwrite: String = "if-changed"
    /** Phone data jobs: which kinds, and for a restore which backup each comes from ("latest" or a snapshot ID). */
    @Volatile var phoneKinds: List<PhoneKind> = emptyList()
    @Volatile var phoneSnaps: Map<PhoneKind, String> = emptyMap()
    /** Set after messages were restored: the user should switch the default messaging app back. */
    @Volatile var restoredSms = false

    /** Folder restore only: the chosen (snapshot ID, original path) pairs. Empty = the whole newest / given snapshot. */
    @Volatile var restoreFolderItems: List<Pair<String, String>> = emptyList()

    /** Short result of the current run, for the finished notification. */
    @Volatile var lastSummary = ""
    @Volatile var firstError = ""

    /** Started by the service after the current job succeeded (used to chain folders -> apps). */
    @Volatile var next: (() -> Unit)? = null

    /** Full history on disk. The on-screen [log] stays short and starts empty on every launch. */
    @Volatile var logFile: File? = null

    private const val SCREEN_LOG_MAX = 20_000

    private val ui = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "log-io").apply { isDaemon = true } }
    private val pending = StringBuilder()
    private var flushQueued = false

    private val flush = Runnable {
        val chunk = synchronized(pending) {
            flushQueued = false
            pending.toString().also { pending.setLength(0) }
        }
        if (chunk.isNotEmpty()) log = (log + chunk).takeLast(SCREEN_LOG_MAX)
    }

    fun resetRun() {
        lastSummary = ""
        firstError = ""
    }

    /** Safe to call from any thread, as often as you like: the screen is refreshed in batches and the file is written in the background. */
    fun append(s: String) {
        if ("failed to restore timestamp" in s || "lchown /sdcard" in s || "\"exit_error\"" in s) return
        val post = synchronized(pending) {
            pending.append(s)
            val first = !flushQueued
            if (first) flushQueued = true
            first
        }
        if (post) ui.postDelayed(flush, 150)
        io.execute { persist(s) }
    }

    private fun persist(s: String) {
        synchronized(this) {
            val f = logFile ?: return
            runCatching {
                if (f.length() > 512 * 1024) f.writeText(f.readText().takeLast(256 * 1024))
                f.appendText(s)
            }
        }
    }

    fun savedLog(): String {
        runCatching { io.submit {}.get() } // let queued writes land first
        return synchronized(this) { runCatching { logFile?.readText().orEmpty() }.getOrDefault("") }
    }

    fun handle(e: ResticEvent) {
        when (e) {
            is ResticEvent.Status -> progress = e
            is ResticEvent.Summary -> {
                lastSummary = "${e.filesNew} new, ${e.filesChanged} changed, ${fmtBytes(e.dataAdded)} added"
                append(
                    "snapshot ${e.snapshotId.take(8)}: ${e.filesNew} new, ${e.filesChanged} changed, " +
                        "${fmtBytes(e.dataAdded)} added, ${e.totalFiles} files\n"
                )
            }
            is ResticEvent.RestoreSummary -> append(
                "restored ${e.filesRestored} files, ${fmtBytes(e.bytesRestored)} in ${e.seconds.toInt()}s\n"
            )
            is ResticEvent.Error -> {
                if (firstError.isEmpty()) firstError = e.message
                append("error: ${e.message}\n")
            }
            is ResticEvent.Other -> append(e.raw + "\n")
        }
    }

    fun cancel() {
        cancelled = true
        val c = config ?: return
        val r = runner ?: return
        thread { r.cancel(c) }
    }
}
