package app.sunrestic

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.RemoteViews

/** Opens the app and starts the scheduled backup (unlock, then run), or runs it right away when automatic backups are on. */
private fun openAndRun(ctx: Context): Intent =
    Intent(ctx, MainActivity::class.java)
        .putExtra(Scheduler.EXTRA_RUN, true)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

/** "Back up" in the quick settings shade. */
class BackupTileService : TileService() {
    override fun onStartListening() {
        qsTile?.apply {
            state = Tile.STATE_INACTIVE
            label = "Back up now"
            subtitle = if (Scheduler.automatic(this@BackupTileService)) "Starts at once" else "Opens the app"
            updateTile()
        }
    }

    @Suppress("DEPRECATION")
    @android.annotation.SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        if (Scheduler.automatic(this)) {
            kotlin.concurrent.thread { Scheduler.runAutomatic(applicationContext, now = true) }
            return
        }
        val i = openAndRun(this)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 13, i, PendingIntent.FLAG_IMMUTABLE))
        } else {
            startActivityAndCollapse(i)
        }
    }
}

/** Home-screen widget: one tap to back up, with the time of the last backup. */
class BackupWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        ids.forEach { mgr.updateAppWidget(it, views(ctx)) }
    }

    companion object {
        private fun views(ctx: Context): RemoteViews {
            val v = RemoteViews(ctx.packageName, R.layout.widget_backup)
            val last = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).getLong("lastBackupAt", 0L)
            v.setTextViewText(R.id.widget_sub, if (last <= 0L) "No backup yet" else "Last: " + agoText(last))
            val tap = if (Scheduler.automatic(ctx)) {
                PendingIntent.getForegroundService(
                    ctx, 14,
                    Intent(ctx, BackupService::class.java).setAction(BackupService.ACTION_AUTO).putExtra(BackupService.EXTRA_NOW, true),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            } else {
                PendingIntent.getActivity(ctx, 15, openAndRun(ctx), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            }
            v.setOnClickPendingIntent(R.id.widget_root, tap)
            return v
        }

        fun refresh(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, BackupWidget::class.java))
            ids.forEach { mgr.updateAppWidget(it, views(ctx)) }
        }
    }
}
