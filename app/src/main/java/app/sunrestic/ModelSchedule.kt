package app.sunrestic

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import kotlin.concurrent.thread

/** The daily backup: setting the alarm and running it. These are extensions of [AppModel], which holds the state. */

// ---- daily reminder -----------------------------------------------------

internal fun AppModel.applySchedule() {
    if (schedOn) Scheduler.schedule(ctx, schedHour.toIntOrNull() ?: 8, schedMinute.toIntOrNull() ?: 0) else Scheduler.cancel(ctx)
    healthTick++
}

/** Root shortcut: allow exact alarms and skip battery optimisation, then re-set the alarm. */
internal fun AppModel.fixReminderWithRoot() {
    thread {
        val out = Scheduler.fixWithRoot(ctx)
        Scheduler.rearm(ctx)
        healthTick++
        if (out.isNotEmpty()) JobHub.append("reminder fix: $out\n")
    }
}

/** The apps the apps task backs up right now: the tagged ones, or those of the last app backup. */
internal fun AppModel.scheduledApps(): Set<String> =
    if (schedAppMode == "tags") schedTags.flatMap { appTags[it].orEmpty() }.toSet()
    else autoApps.split(',').filter { it.isNotBlank() }.toSet()

/** Runs what the daily reminder is set up to back up: saved folders first, then the apps (tagged, or of the last app backup). */
internal fun AppModel.runScheduled() {
    if (JobHub.busy) return
    val appSet = scheduledApps()
    // A tagged run puts the tag names on the snapshot too, so it is easy to find later.
    val snapTags = if (schedAppMode == "tags") schedTags.sortedBy { it.lowercase() } else emptyList()
    val doFolders = schedFolders && folders.isNotEmpty()
    val doApps = schedApps && appSet.isNotEmpty()
    if (!doFolders && !doApps) {
        Scheduler.note(
            ctx,
            if (schedApps && schedAppMode == "tags") "Nothing to back up: the chosen tag has no apps"
            else "Nothing to back up: turn on a task in Schedules (a saved folder, or apps)",
        )
        toast(
            if (schedApps && schedAppMode == "tags") "Nothing to back up: pick a tag that has apps in Schedules."
            else "Nothing to back up: turn on a task in Schedules (and add a folder or back up apps once)."
        )
        return
    }
    if (doFolders) {
        if (doApps) JobHub.next = { launchAppBackup(appSet, snapTags) }
        startFolderBackup()
    } else {
        launchAppBackup(appSet, snapTags)
    }
}
