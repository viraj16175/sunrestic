package app.sunrestic

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import kotlin.concurrent.thread

class BackupService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastPercent = -1
    @Volatile private var latestStartId = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        when (intent?.action) {
            ACTION_CANCEL -> JobHub.cancel()
            ACTION_AUTO -> {
                Scheduler.note(this, "Backup service started")
                autoRun(startId, intent.getBooleanExtra(EXTRA_NOW, false))
            }
            ACTION_BACKUP, ACTION_RESTORE, ACTION_BACKUP_APPS, ACTION_RESTORE_APPS, ACTION_BACKUP_FOLDERS,
            ACTION_BACKUP_PHONE, ACTION_RESTORE_PHONE -> start(
                intent.action!!,
                intent.getStringExtra(EXTRA_PATH).orEmpty(),
                intent.getStringArrayExtra(EXTRA_PATHS)?.toList().orEmpty(),
                intent.getStringExtra(EXTRA_SNAPSHOT).orEmpty().ifBlank { "latest" },
                intent.getStringArrayExtra(EXTRA_TAGS)?.toList().orEmpty(),
            )
        }
        return START_NOT_STICKY
    }

    @Volatile private var jobRunning = false

    private fun charging(): Boolean = getSystemService(android.os.BatteryManager::class.java).isCharging

    private fun onWifi(): Boolean = Net.onWifi(this)

    private fun alert(title: String, text: String) {
        getSystemService(NotificationManager::class.java).notify(
            DONE_ID,
            Notification.Builder(this, ALERTS)
                .setSmallIcon(R.drawable.ic_stat_backup)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setContentIntent(openApp())
                .setAutoCancel(true)
                .build(),
        )
    }

    private var autoLock: PowerManager.WakeLock? = null
    @Volatile private var holdsStartLock = false

    private fun releaseAutoLock() {
        autoLock?.let { if (it.isHeld) it.release() }
        autoLock = null
    }

    /**
     * Ends this start command. While a backup is running in this service it must keep running, so then nothing is
     * stopped: the job stops the service itself when it ends.
     */
    private fun giveUp(startId: Int) {
        releaseAutoLock()
        if (JobHub.busy) return
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf(startId)
    }

    /**
     * The backup that starts by itself at the chosen time. Uses the saved unattended password, waits (and tries again
     * every 30 minutes for 6 hours) until the phone is charging / on Wi-Fi when those are required, then runs the
     * same tasks as "Run tasks now".
     */
    private fun autoRun(startId: Int, force: Boolean) {
        createChannels()
        foreground(notification("Scheduled backup", if (JobHub.busy) "A backup is already running" else "Getting ready…", null))
        // Keep the CPU awake while the connection is checked (the real job takes its own lock when it starts).
        autoLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "restic:auto").apply { acquire(5 * 60 * 1000L) }
        thread {
            try {
                val sp = getSharedPreferences("settings", MODE_PRIVATE)
                if (!autoStarting.compareAndSet(false, true)) {
                    Scheduler.note(this, "Skipped: an automatic backup is already starting")
                    return@thread giveUp(startId)
                }
                holdsStartLock = true
                if (JobHub.busy) {
                    Scheduler.note(this, "Skipped: another backup is already running")
                    return@thread giveUp(startId)
                }
                val password = Vault.loadUnattended(this)
                if (password == null) {
                    Scheduler.note(this, "Skipped: the saved password could not be read. Turn automatic backups off and on again.")
                    alert("Scheduled backup skipped", "The saved password could not be read. Open the app and turn automatic backups off and on again.")
                    return@thread giveUp(startId)
                }
                val now = System.currentTimeMillis()
                val until = sp.getLong("autoUntil", 0L).let { if (it > now) it else now + 6 * 60 * 60 * 1000L }
                sp.edit().putLong("autoUntil", until).apply()
                val m = AppModel(application)
                m.password = password
                // The server has to answer too. A VPN such as WireGuard may need a moment, or may be off at that hour.
                val serverProblem = if (force) null else m.serverProblem()
                val wait = if (force) null else when {
                    sp.getBoolean("autoCharging", true) && !charging() -> "the phone is not charging"
                    sp.getBoolean("wifiOnly", false) && sp.getString("backend", "sftp") != "local" && !onWifi() -> "the phone is not on Wi-Fi"
                    serverProblem != null -> "the server can't be reached ($serverProblem). Is the VPN connected?"
                    else -> null
                }
                if (wait != null) {
                    if (now + 30 * 60 * 1000L < until) {
                        Scheduler.scheduleRetry(this, now + 30 * 60 * 1000L)
                        Scheduler.note(this, "Waiting: $wait. Trying again in 30 minutes")
                    } else {
                        sp.edit().remove("autoUntil").apply()
                        Scheduler.note(this, "Skipped: $wait for 6 hours")
                        alert("Scheduled backup skipped", "It could not run for 6 hours because $wait.")
                    }
                    return@thread giveUp(startId)
                }
                sp.edit().remove("autoUntil").apply()

                JobHub.autoRun = true
                m.runScheduled()
                if (!JobHub.busy) {
                    // runScheduled did not start anything (nothing to back up); it has written the reason itself.
                    JobHub.autoRun = false
                    return@thread giveUp(startId)
                }
                // runScheduled starts the real job a moment later (after the connection check). Wait for that hand-over.
                val end = System.currentTimeMillis() + 180_000
                while (!jobRunning && JobHub.busy && System.currentTimeMillis() < end) Thread.sleep(300)
                if (!jobRunning) {
                    if (JobHub.outcome == "failed") {
                        val why = JobHub.outcomeText.ifEmpty { "Open the app and check the log" }
                        Scheduler.note(this, "Failed to start: $why")
                        alert("Scheduled backup failed", why)
                    }
                    JobHub.autoRun = false
                    giveUp(startId)
                }
            } catch (e: Exception) {
                Scheduler.note(this, "Failed: ${e.message}")
                alert("Scheduled backup failed", e.message.orEmpty())
                giveUp(startId)
            } finally {
                if (holdsStartLock) autoStarting.set(false)
            }
        }
    }

    private fun start(action: String, path: String, paths: List<String>, snapshot: String, tags: List<String>) {
        val title = if (action == ACTION_RESTORE || action == ACTION_RESTORE_APPS || action == ACTION_RESTORE_PHONE) "Restoring" else "Backing up"
        createChannels()
        foreground(notification(title, "Starting…", null))

        val c = JobHub.config
        val runner = JobHub.runner
        if (c == null || runner == null) {
            JobHub.outcome = "failed"
            JobHub.outcomeText = "The connection settings are missing. Open the app and try again."
            JobHub.busy = false
            JobHub.next = null
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        jobRunning = true
        releaseAutoLock()
        lastPercent = -1
        JobHub.progress = null
        JobHub.cancelled = false
        JobHub.resetRun()
        val stamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date())
        JobHub.append("\n[$stamp] $ $title $path\n")
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "restic:job")
            .apply { acquire(12 * 60 * 60 * 1000L) }

        thread {
            var ok = false
            var verifyFailed = false
            var detail = ""
            val isRestore = action == ACTION_RESTORE || action == ACTION_RESTORE_APPS || action == ACTION_RESTORE_PHONE
            val sp = getSharedPreferences("settings", MODE_PRIVATE)
            val nm = getSystemService(NotificationManager::class.java)
            try {
                val onEvent: (ResticEvent) -> Unit = { e ->
                    JobHub.handle(e)
                    if (e is ResticEvent.Status) {
                        val pct = (e.percent * 100).toInt()
                        if (pct != lastPercent) {
                            lastPercent = pct
                            val text = "$pct% · ${fmtBytes(e.bytesDone)} / ${fmtBytes(e.totalBytes)} ${fmtEta(e.secondsRemaining)}"
                            nm.notify(NOTIF_ID, notification(title, text, pct))
                        }
                    }
                }

                // If the process dies mid-restore, the app re-enables these packages on its next start.
                if (action == ACTION_RESTORE_APPS) {
                    sp.edit().putString("pendingEnable", JobHub.restoreApps.joinToString(",") { it.pkg }).commit()
                }
                val code = try {
                    when (action) {
                        ACTION_BACKUP -> runner.backup(c, path, onEvent)
                        ACTION_BACKUP_FOLDERS -> runner.backupPaths(c, paths, listOf("android-folders"), emptyList(), onEvent)
                        ACTION_BACKUP_APPS -> runner.backupPaths(c, paths, listOf("android-apps") + tags, AppBackup.EXCLUDES, onEvent)
                        ACTION_RESTORE_APPS -> runner.restoreApps(c, JobHub.restoreApps, JobHub.restoreComps, onEvent)
                        ACTION_BACKUP_PHONE -> runner.backupPhone(c, JobHub.phoneKinds, onEvent)
                        ACTION_RESTORE_PHONE -> {
                            var worst = 0
                            for ((kind, snapId) in JobHub.phoneSnaps) {
                                val r = runner.restorePhone(c, kind, snapId, onEvent)
                                if (r != 0) worst = r
                            }
                            worst
                        }
                        else -> {
                            val picked = JobHub.restoreFolderItems
                            JobHub.restoreFolderItems = emptyList()
                            if (picked.isNotEmpty()) runner.restoreFolderItems(c, picked, path, JobHub.restoreOverwrite, onEvent)
                            else runner.restoreFolders(c, path, snapshot, JobHub.restoreOverwrite, onEvent)
                        }
                    }
                } finally {
                    if (action == ACTION_RESTORE_APPS) sp.edit().remove("pendingEnable").commit()
                }
                ok = code == 0
                JobHub.append(if (JobHub.cancelled) "[cancelled]\n" else "[exit $code]\n")

                // A stopped job can leave a lock in the repository. Clear it now, so the next backup or check is not blocked.
                if (JobHub.cancelled) runCatching { runner.unlockStale(c) }

                // The backup itself is done and saved: record that first, whatever the optional check says afterwards.
                if (ok && !isRestore) {
                    val now = System.currentTimeMillis()
                    sp.edit().putLong("lastBackupAt", now).apply()
                    JobHub.lastSuccess = now
                    runCatching { BackupWidget.refresh(this) }
                }

                if (ok && !isRestore && !JobHub.cancelled && sp.getBoolean("verifyAfter", false)) {
                    nm.notify(NOTIF_ID, notification(title, "Verifying repository…", null))
                    JobHub.append("verifying repository…\n")
                    val v = runner.checkCode(c, null) { JobHub.append(it + "\n") }
                    if (v == 0) {
                        JobHub.append("verification ok\n")
                    } else {
                        verifyFailed = true
                        detail = "The backup is saved, but the repository check failed. Tap Verify on Home, or open the log."
                    }
                }
            } catch (e: Exception) {
                // Stopping a job closes its output while it is being read; that is not an error to report.
                if (!JobHub.cancelled) {
                    JobHub.append("error: ${e.message}\n")
                    detail = e.message.orEmpty()
                }
            }

            JobHub.progress = null
            val nextJob = if (ok) JobHub.next else null
            JobHub.next = null
            if (nextJob == null) {
                JobHub.outcomeText = when {
                    JobHub.cancelled -> "Stopped before it finished"
                    ok && verifyFailed -> detail
                    ok -> JobHub.lastSummary.ifEmpty { "Nothing went wrong" }
                    else -> detail.ifEmpty { JobHub.firstError }.ifEmpty { "Open the log to see why" }
                }.take(300)
                JobHub.outcome = when {
                    JobHub.cancelled -> "cancelled"
                    ok && verifyFailed -> "warning"
                    ok -> "done"
                    else -> "failed"
                }
            }
            if (nextJob == null && JobHub.autoRun) {
                JobHub.autoRun = false
                Scheduler.note(
                    this,
                    when (JobHub.outcome) {
                        "done" -> "Finished"; "warning" -> "Saved, but the check failed"; "cancelled" -> "Cancelled"; else -> "Failed"
                    } + ": " + JobHub.outcomeText,
                )
            }
            JobHub.dataVersion++
            JobHub.busy = false
            wakeLock?.let { if (it.isHeld) it.release() }
            // With a follow-up job (folders, then apps) this service stays in the foreground until the next job has
            // started. Stopping first would leave the app in the background, where Android refuses to start it.
            if (nextJob == null) stopForeground(STOP_FOREGROUND_REMOVE)

            if (nextJob == null) {
                val result = when {
                    JobHub.cancelled -> "cancelled"
                    ok -> "finished"
                    else -> "failed"
                }
                val text = when {
                    JobHub.cancelled -> "Stopped before it finished"
                    ok && verifyFailed -> detail
                    ok -> JobHub.lastSummary.ifEmpty { "Done" }
                    else -> detail.ifEmpty { JobHub.firstError }.ifEmpty { "Open the app and check the log" }
                }.take(300)
                nm.notify(DONE_ID, finishedNotification(title, result, text, (ok || JobHub.cancelled) && !verifyFailed))
            }

            if (nextJob == null) stopSelf(latestStartId) else nextJob.invoke()
        }
    }

    private fun foreground(n: Notification) {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Backup jobs", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(ALERTS, "Backup problems", NotificationManager.IMPORTANCE_HIGH))
    }

    private fun openApp() = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
    )

    private fun notification(title: String, text: String, percent: Int?): Notification {
        val cancel = PendingIntent.getService(
            this, 0, Intent(this, BackupService::class.java).setAction(ACTION_CANCEL), PendingIntent.FLAG_IMMUTABLE,
        )
        val b = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_backup)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openApp())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", cancel)
        if (percent != null) b.setProgress(100, percent, false) else b.setProgress(0, 0, true)
        return b.build()
    }

    private fun finishedNotification(title: String, result: String, text: String, good: Boolean): Notification =
        Notification.Builder(this, if (good) CHANNEL else ALERTS)
            .setSmallIcon(R.drawable.ic_stat_backup)
            .setContentTitle("$title $result")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(openApp())
            .setAutoCancel(true)
            .build()

    companion object {
        const val ACTION_BACKUP = "app.sunrestic.BACKUP"
        const val ACTION_RESTORE = "app.sunrestic.RESTORE"
        const val ACTION_CANCEL = "app.sunrestic.CANCEL"
        /** Started by the alarm when automatic backups are on: runs the scheduled tasks without the app being open. */
        const val ACTION_AUTO = "app.sunrestic.AUTO"
        /** With ACTION_AUTO: start now, ignoring the charging / Wi-Fi conditions (the user asked for it). */
        const val EXTRA_NOW = "now"
        const val EXTRA_PATH = "path"
        const val ACTION_BACKUP_APPS = "app.sunrestic.BACKUP_APPS"
        const val ACTION_RESTORE_APPS = "app.sunrestic.RESTORE_APPS"
        const val ACTION_BACKUP_FOLDERS = "app.sunrestic.BACKUP_FOLDERS"
        const val ACTION_BACKUP_PHONE = "app.sunrestic.BACKUP_PHONE"
        const val ACTION_RESTORE_PHONE = "app.sunrestic.RESTORE_PHONE"
        const val EXTRA_PATHS = "paths"
        /** App backup only: the user's tag names this run is a backup of; they go on the snapshot. */
        const val EXTRA_TAGS = "tags"
        /** Folder restore only: snapshot ID to restore. Missing = the newest folder snapshot. */
        const val EXTRA_SNAPSHOT = "snapshot"
        /** Several alarms can arrive together; only one of them may start the automatic backup. */
        private val autoStarting = java.util.concurrent.atomic.AtomicBoolean(false)
        private const val CHANNEL = "jobs"
        private const val ALERTS = "alerts"
        private const val NOTIF_ID = 1
        private const val DONE_ID = 2
    }
}
