package app.sunrestic

import android.app.Activity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import kotlin.concurrent.thread

/** Password, fingerprint, root check and the app lifecycle (lock after a while in the background). These are extensions of [AppModel], which holds the state. */

// ---- password / fingerprint -------------------------------------------

internal fun AppModel.savePassword(activity: Activity) {
    if (password.isEmpty()) return
    Vault.save(activity, password) { _, msg ->
        vaultSaved = Vault.has(ctx)
        toast(msg)
    }
}

internal fun AppModel.unlockPassword(activity: Activity) {
    unlocking = true
    Vault.unlock(activity) { pw, msg ->
        unlocking = false
        if (pw != null) password = pw else pendingScheduled = false
        vaultSaved = Vault.has(ctx)
        toast(msg)
    }
}

/** Keeps the password on this phone without a fingerprint, so the scheduled backup can run on its own. */
internal fun AppModel.enableUnattended(): Boolean {
    if (password.isEmpty()) {
        toast("Unlock the repository first")
        return false
    }
    if (!Vault.saveUnattended(ctx, password)) {
        toast("Could not save the password for automatic backups")
        return false
    }
    unattendedSaved = true
    autoBackup = true
    if (!schedOn) {
        schedOn = true
        applySchedule()
    }
    return true
}

internal fun AppModel.disableUnattended() {
    Vault.clearUnattended(ctx)
    unattendedSaved = false
    autoBackup = false
}

internal fun AppModel.forgetPassword() {
    Vault.clear(ctx)
    vaultSaved = false
}

/** Drops the password from memory. Not while a job runs. */
internal fun AppModel.lock() {
    if (JobHub.busy) return
    password = ""
    unlockTried = true // don't ask for the fingerprint again right away
}

/** Asks su once whether root is granted, and for its version line (shown on Home like Swift Backup). */
internal fun AppModel.checkRoot() {
    if (rootChecking) return
    rootChecking = true
    thread {
        fun run(vararg cmd: String): String = runCatching {
            val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText().trim()
            p.waitFor()
            out
        }.getOrDefault("")
        rootInfo = run("su", "-v").lines().firstOrNull().orEmpty()
        rootGranted = run("su", "-c", "id -u").lines().lastOrNull()?.trim() == "0"
        rootChecking = false
    }
}

// ---- app lifecycle ------------------------------------------------------

internal fun AppModel.onBackgrounded() {
    backgroundedAt = System.currentTimeMillis()
}

/** After [lockMinutes] in the background the password is dropped from memory (only if it can be unlocked again). */
internal fun AppModel.onForegrounded() {
    val away = if (backgroundedAt == 0L) 0L else System.currentTimeMillis() - backgroundedAt
    if (lockMinutes > 0 && vaultSaved && password.isNotEmpty() && !JobHub.busy && away > lockMinutes * 60_000L) {
        password = ""
        unlockTried = false
    }
    foregroundTick++
    healthTick++
    // Android forgets alarms after an app update or force-stop, so set the reminder again every time the app opens.
    Scheduler.rearm(ctx)
}

/** An interrupted restore can leave apps disabled (pm disable-user). Turn them back on. */
internal fun AppModel.recoverDisabledApps() {
    val pending = prefs.getString("pendingEnable", "").orEmpty().split(',').filter { it.isNotBlank() }
    if (pending.isEmpty() || JobHub.busy || !useRoot) return
    thread {
        runCatching { runner.enablePackages(pending) }
        prefs.edit().remove("pendingEnable").apply()
        JobHub.append("\nre-enabled ${pending.size} apps left disabled by an interrupted restore\n")
    }
}
