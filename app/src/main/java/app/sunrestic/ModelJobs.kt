package app.sunrestic

import android.content.Intent
import kotlin.concurrent.thread

/** Marks a job as started, shows its progress page and keeps the settings the background service will read. */
internal fun AppModel.prepareJob(title: String) {
    JobHub.busy = true
    beginJob(title)
    JobHub.config = config()
}

/** Checks that the server answers (off the main thread), then starts the background service with [action]. */
internal fun AppModel.startServiceAfterPreflight(action: String) {
    thread {
        val err = preflight()
        if (err != null) {
            fail(err)
            return@thread
        }
        try {
            ctx.startForegroundService(Intent(ctx, BackupService::class.java).setAction(action))
        } catch (e: Exception) {
            fail(e.message.orEmpty())
        }
    }
}
