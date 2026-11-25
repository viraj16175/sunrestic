package app.sunrestic

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import java.io.File
import kotlin.concurrent.thread

/** The "Check everything" report and the repository health check. These are extensions of [AppModel], which holds the state. */

/** Opens the self check page and runs it again from scratch. */
internal fun AppModel.runSelfCheckFresh() {
    checks = emptyList()
    page = Page.SELFCHECK
}

/** Tests everything the app depends on and lists what is fine and what is not. Changes nothing. */
internal fun AppModel.runSelfCheck() {
    if (checking) return
    checking = true
    checks = emptyList()
    thread {
        val out = ArrayList<CheckItem>()
        fun add(title: String, level: Int, detail: String) {
            out += CheckItem(title, level, detail)
            checks = out.toList()
        }
        try {
            // --- the repository
            val missing = repoDraft().missing
            add("Repository settings", if (missing.isEmpty()) 0 else 2, if (missing.isEmpty()) repoSummary else "Missing: ${missing.joinToString()}")
            add(
                "Repository unlocked", if (ready) 0 else 2,
                if (ready) "The password is in memory" else "Locked. Use the fingerprint or the lock icon at the top",
            )
            if (!ready) {
                add("Not checked yet", 1, "The server, the snapshots, the app list and the tags need the password. Unlock, and they are checked right away.")
            }
            if (ready) {
                val c = config()
                val problem = runner.reachable(c)
                add("Server reachable", if (problem == null) 0 else 2, problem ?: if (remote) "The server answers" else "Local folder")
                if (problem == null) {
                    val snaps = runCatching { reader.listSnapshots(c) }
                    if (snaps.isSuccess) {
                        val list = snaps.getOrThrow()
                        val newest = list.maxOfOrNull { parseResticTime(it.time) ?: 0L } ?: 0L
                        add("Repository readable", 0, "${list.size} snapshots" + if (newest > 0) ", newest ${agoText(newest)}" else "")
                        val problems = ArrayList<String>()
                        val apps = runCatching { reader.readManifest(c) { problems += it } }
                        if (apps.isSuccess) {
                            val n = apps.getOrThrow().size
                            add(
                                "Backed-up apps", if (n > 0) 0 else 1,
                                (if (n > 0) "$n apps can be restored" else "No app backup found yet") +
                                    (if (problems.isNotEmpty()) ". ${problems.first()}" else ""),
                            )
                        } else {
                            add("Backed-up apps", 2, shortReason(apps.exceptionOrNull()?.message))
                        }
                    } else {
                        add("Repository readable", 2, shortReason(snaps.exceptionOrNull()?.message))
                    }
                }
                add(
                    "Tags in the repository",
                    when (tagSync) { "synced" -> 0; "failed" -> 2; else -> 1 },
                    when (tagSync) {
                        "synced" -> "${tagNames.size} tags, in step with the repository"
                        "failed" -> "Could not sync the tags. Open the tag sheet, Sync now"
                        "syncing" -> "Syncing…"
                        else -> "Not synced yet in this session"
                    },
                )
            }

            // --- this phone
            if (useRoot) {
                val root = runCatching {
                    val p = ProcessBuilder("su", "-c", "id -u").redirectErrorStream(true).start()
                    val t = p.inputStream.bufferedReader().readText().trim()
                    p.waitFor()
                    t == "0"
                }.getOrDefault(false)
                add("Root", if (root) 0 else 2, if (root) "Granted" else "Not granted, but Run as root is on")
            } else {
                add("Root", 0, "Not used (Run as root is off, apps are saved as APK files only)")
            }
            add(
                "Folder access", if (folderAccess) 0 else 1,
                when {
                    useRoot -> "Through root"
                    Storage.hasAllFiles() -> "All files access is allowed"
                    else -> "Folders can't be backed up without root until you allow All files access (Back up folders page)"
                },
            )
            val h = Scheduler.health(ctx)
            add("Notifications", if (h.notifications) 0 else 2, if (h.notifications) "Allowed" else "Blocked: no progress or result notices")
            add(
                "Battery", if (h.unrestricted) 0 else 1,
                if (h.unrestricted) "Unrestricted" else "Android may delay background work. Schedules, Will it start on time?",
            )
            val vpns = Net.vpnPackages(ctx)
            val mine = (selected + scheduledApps()).filter { it in vpns }
            add(
                "VPN", 0,
                (if (Net.vpnActive(ctx)) "A VPN is connected. " else "No VPN is connected. ") +
                    if (mine.isNotEmpty()) "${mine.joinToString()} is never stopped during a backup." else "",
            )

            // --- contacts, calls, messages (only informational: asked when the feature is used)
            for (k in PhoneKind.values()) {
                val n = PhoneData.liveCount(ctx, k)
                add(k.label, 0, if (n != null) "Allowed: $n ${phoneWord(k, n)} on this phone" else "Permission not given yet, asked when you back it up")
            }

            // --- the daily backup
            if (schedOn) {
                val next = Scheduler.nextReminderAt(ctx)
                add(
                    "Daily alarm", if (next > System.currentTimeMillis()) 0 else 2,
                    if (next > System.currentTimeMillis()) "Set for " + java.text.SimpleDateFormat("EEE d MMM, HH:mm", java.util.Locale.getDefault()).format(java.util.Date(next))
                    else "Not set. Open Schedules and set the time again",
                )
                add(
                    "Alarm type", if (h.exactAlarms) 0 else 1,
                    when {
                        BuildConfig.PLAY -> "The alarm-clock type is used (a small alarm icon shows in the status bar)"
                        h.exactAlarms -> "Exact alarms are allowed"
                        else -> "Exact alarms are off, so the alarm-clock type is used (works, shows a small alarm icon)"
                    },
                )
                val auto = autoBackup && unattendedSaved
                if (auto) {
                    val ok = Vault.loadUnattended(ctx) != null
                    add("Password for automatic backups", if (ok) 0 else 2, if (ok) "Saved and readable" else "Could not be read. Choose reminder mode, then automatic again")
                } else {
                    add("Mode", 0, "The phone only reminds you")
                }
                val folderOk = schedFolders && folders.isNotEmpty()
                val appCount = scheduledApps().size
                val appsOk = schedApps && appCount > 0
                add(
                    "What gets backed up", if (folderOk || appsOk) 0 else 2,
                    if (folderOk || appsOk) listOfNotNull(
                        if (folderOk) "${folders.size} folder(s)" else null,
                        if (appsOk) "$appCount app(s)" else null,
                    ).joinToString(" and ") else "Nothing is switched on under What to back up",
                )
            } else {
                add("Daily backup", 0, "Switched off")
            }
            if (folders.isNotEmpty() && folderAccess) {
                val ex = runCatching { if (useRoot) AppBackup.existing(folders) else folders.filter { File(it).exists() } }.getOrDefault(emptyList())
                val gone = folders.filter { it !in ex }
                add("Saved folders exist", if (gone.isEmpty()) 0 else 1, if (gone.isEmpty()) "All ${folders.size} found" else "Not found: ${gone.joinToString()}")
            }
            add(
                "Last backup", if (JobHub.lastSuccess > 0) 0 else 1,
                if (JobHub.lastSuccess > 0) agoText(JobHub.lastSuccess) else "No backup yet",
            )
        } catch (e: Exception) {
            add("The check itself", 2, e.message.orEmpty())
        } finally {
            checking = false
        }
    }
}

/** Reads the repository (a random [subset] of the stored data, like "5%", or just the structure) and remembers the result. */
internal fun AppModel.runCheck(subset: String?) {
    go(if (subset == null) "restic check" else "restic check --read-data-subset=$subset") { c ->
        JobHub.cancelled = false
        val r = runner.checkText(c, subset)
        if (!JobHub.cancelled) { // a check you stopped says nothing about the repository
            val ok = r.trimEnd().endsWith("[exit 0]")
            lastCheckAt = System.currentTimeMillis()
            lastCheckOk = ok
            prefs.edit().putLong("lastCheckAt", lastCheckAt).putBoolean("lastCheckOk", ok).apply()
        }
        r
    }
}
