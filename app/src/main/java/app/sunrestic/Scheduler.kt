package app.sunrestic

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import java.util.Calendar
import kotlin.concurrent.thread

/** What can stop the daily backup or its reminder from running on time. */
data class ReminderHealth(
    val notifications: Boolean,
    val exactAlarms: Boolean,
    val unrestricted: Boolean,
)

/** Daily "time to back up" reminder. Tapping it opens the app, asks for the fingerprint and starts the backup. */
object Scheduler {
    const val EXTRA_RUN = "run_scheduled"
    private const val CHANNEL = "reminder"
    private const val NOTIF_ID = 3

    private fun alarmIntent(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
        ctx, 10, Intent(ctx, ReminderReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /**
     * Sets one alarm for [at]. With the "alarms & reminders" permission it is an exact alarm. Without it, a plain alarm
     * would be delayed by Android (often by many minutes), so the alarm-clock type is used instead: it needs no
     * permission, is exact even in battery saving, and shows a small alarm icon in the status bar.
     */
    private fun setAlarm(ctx: Context, at: Long, pi: PendingIntent) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        try {
            if (canScheduleExact(ctx)) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } else {
                val show = PendingIntent.getActivity(
                    ctx, 17, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
                am.setAlarmClock(AlarmManager.AlarmClockInfo(at, show), pi)
            }
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    /** A second, separate alarm for "try again in a while" (the daily alarm keeps its own slot). */
    fun scheduleRetry(ctx: Context, at: Long) {
        setAlarm(
            ctx, at,
            PendingIntent.getBroadcast(
                ctx, 12, Intent(ctx, ReminderReceiver::class.java).putExtra("retry", true),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
    }

    /** A one-off alarm that behaves like the daily one, a minute from now, to check the whole chain without waiting. */
    fun scheduleTest(ctx: Context, inMillis: Long = 60_000L): Long {
        val at = System.currentTimeMillis() + inMillis
        setAlarm(
            ctx, at,
            PendingIntent.getBroadcast(
                ctx, 16, Intent(ctx, ReminderReceiver::class.java).putExtra("test", true),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
        note(ctx, "Test alarm set for " + android.text.format.DateFormat.getTimeFormat(ctx).format(java.util.Date(at)))
        return at
    }

    /** The time of day in the phone's own style (12 h with AM/PM, or 24 h). */
    fun timeLabel(ctx: Context, hour: Int, minute: Int): String {
        val c = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, hour.coerceIn(0, 23)); set(Calendar.MINUTE, minute.coerceIn(0, 59)) }
        return android.text.format.DateFormat.getTimeFormat(ctx).format(c.time)
    }

    /** True when the phone backs up by itself (the user turned it on and the unattended password is there). */
    fun automatic(ctx: Context): Boolean =
        prefs(ctx).getBoolean("autoBackup", false) && Vault.hasUnattended(ctx)

    /** Remembers what the automatic backup did last and why, so it can be shown instead of guessed. */
    fun note(ctx: Context, text: String) {
        val now = System.currentTimeMillis()
        val line = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date(now)) + "  " + text
        val sp = prefs(ctx)
        val log = (sp.getString("autoLog", "").orEmpty().lines().filter { it.isNotBlank() } + line).takeLast(12)
        sp.edit().putString("autoLog", log.joinToString("\n")).apply()
    }

    /** The last few steps of scheduled runs, oldest first, each with its time. */
    fun recentNotes(ctx: Context): List<String> = prefs(ctx).getString("autoLog", "").orEmpty().lines().filter { it.isNotBlank() }

    /**
     * Starts the unattended backup. Some Android versions refuse to start a foreground service from the background
     * (for example when exact alarms or battery are restricted). With root the system is then asked to start it, which
     * is always allowed; without root the tap-to-start reminder is shown instead. Call from a background thread.
     */
    fun runAutomatic(ctx: Context, now: Boolean = false) {
        note(ctx, "Starting…")
        try {
            ctx.startForegroundService(
                Intent(ctx, BackupService::class.java).setAction(BackupService.ACTION_AUTO).putExtra(BackupService.EXTRA_NOW, now)
            )
            return
        } catch (e: Exception) {
            // fall through to the fallbacks below
        }
        if (prefs(ctx).getBoolean("useRoot", true)) {
            val started = runCatching {
                val cmd = "am start-foreground-service -n ${ctx.packageName}/${BackupService::class.java.name} " +
                    "-a ${BackupService.ACTION_AUTO}" + if (now) " --ez ${BackupService.EXTRA_NOW} true" else ""
                val p = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
                val out = p.inputStream.bufferedReader().readText()
                p.waitFor() == 0 && !out.contains("Error", ignoreCase = true)
            }.getOrDefault(false)
            if (started) {
                note(ctx, "Android blocked a normal background start, so it was started through root")
                return
            }
        }
        note(ctx, "Android blocked the background start, so you got the reminder instead. Allow exact alarms and unrestricted battery under Reminder status.")
        showReminder(ctx)
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** Next time [hour]:[minute] happens, strictly after now (24 h clock). */
    fun nextTime(hour: Int, minute: Int = 0): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, hour.coerceIn(0, 23))
        set(Calendar.MINUTE, minute.coerceIn(0, 59))
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
        if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
    }.timeInMillis

    /** When the next reminder is due (ms), or 0 if none is set. */
    fun nextReminderAt(ctx: Context): Long = prefs(ctx).getLong("nextReminderAt", 0L)

    fun canScheduleExact(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 31 || ctx.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    /**
     * Sets the alarm for the next [hour]:00. Uses an exact alarm when Android allows it (it is not delayed by
     * Doze), otherwise the best inexact one. Safe to call again and again: it replaces the pending alarm.
     */
    fun schedule(ctx: Context, hour: Int, minute: Int = 0) {
        ensureChannel(ctx)
        val at = nextTime(hour, minute)
        val before = prefs(ctx).getLong("nextReminderAt", 0L)
        setAlarm(ctx, at, alarmIntent(ctx))
        prefs(ctx).edit().putLong("nextReminderAt", at).apply()
        if (at != before) {
            note(ctx, "Next backup set for " + java.text.SimpleDateFormat("EEE d MMM", java.util.Locale.getDefault()).format(java.util.Date(at)) + ", " +
                android.text.format.DateFormat.getTimeFormat(ctx).format(java.util.Date(at)))
        }
    }

    /** Android drops alarms when the app is updated or force-stopped, so the app calls this whenever it opens. */
    fun rearm(ctx: Context) {
        val sp = prefs(ctx)
        if (sp.getBoolean("schedOn", false)) {
            schedule(ctx, sp.getString("schedHour", "8")?.toIntOrNull() ?: 8, sp.getString("schedMinute", "0")?.toIntOrNull() ?: 0)
        }
    }

    fun cancel(ctx: Context) {
        ctx.getSystemService(AlarmManager::class.java).cancel(alarmIntent(ctx))
        prefs(ctx).edit().putLong("nextReminderAt", 0L).apply()
    }

    private fun ensureChannel(ctx: Context) {
        ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Daily backup reminder", NotificationManager.IMPORTANCE_HIGH)
        )
    }

    fun health(ctx: Context): ReminderHealth {
        ensureChannel(ctx)
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val channelOn = nm.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
        return ReminderHealth(
            notifications = nm.areNotificationsEnabled() && channelOn,
            // The Play build has no exact-alarm permission and always uses the alarm-clock type, which needs none.
            exactAlarms = BuildConfig.PLAY || canScheduleExact(ctx),
            unrestricted = ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName),
        )
    }

    /**
     * Root shortcut for the two settings that matter: allow exact alarms and keep the app out of battery
     * optimisation. Returns what the shell printed (empty when all went fine).
     */
    fun fixWithRoot(ctx: Context): String = runCatching {
        val pkg = ctx.packageName
        val p = ProcessBuilder(
            "su", "-c",
            "appops set $pkg SCHEDULE_EXACT_ALARM allow; cmd deviceidle whitelist +$pkg",
        ).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText().trim()
        p.waitFor()
        out
    }.getOrElse { "root is not available: ${it.message}" }

    fun showReminder(ctx: Context) {
        ensureChannel(ctx)
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val open = PendingIntent.getActivity(
            ctx, 11,
            Intent(ctx, MainActivity::class.java)
                .putExtra(EXTRA_RUN, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        nm.notify(
            NOTIF_ID,
            android.app.Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_backup)
                .setContentTitle("Time to back up")
                .setContentText("Tap, touch the fingerprint sensor, and the backup starts.")
                .setCategory(android.app.Notification.CATEGORY_REMINDER)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build(),
        )
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val sp = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val retry = intent.getBooleanExtra("retry", false)
        val test = intent.getBooleanExtra("test", false)
        Scheduler.note(ctx, if (test) "Test alarm received" else if (retry) "Retry alarm received" else "Daily alarm received")

        // The one-off test alarm does what the daily one would, but ignores "already backed up" rules.
        if (test) {
            if (Scheduler.automatic(ctx)) {
                val pending = goAsync()
                thread { try { Scheduler.runAutomatic(ctx, now = true) } finally { pending.finish() } }
            } else {
                Scheduler.showReminder(ctx)
                Scheduler.note(ctx, "Reminder shown (the phone only reminds you)")
            }
            return
        }

        if (!sp.getBoolean("schedOn", false)) {
            Scheduler.note(ctx, "Ignored: Back up every day is switched off")
            return
        }
        val hour = sp.getString("schedHour", "8")?.toIntOrNull() ?: 8
        val minute = sp.getString("schedMinute", "0")?.toIntOrNull() ?: 0
        if (!retry) Scheduler.schedule(ctx, hour, minute) // next day
        val last = sp.getLong("lastBackupAt", 0L)
        val now = System.currentTimeMillis()
        if (Scheduler.automatic(ctx)) {
            // The daily alarm always runs the backup (it is cheap: only changes are sent). Only a retry, after waiting for
            // charging or Wi-Fi, is skipped when a backup was saved in the meantime.
            if (retry && RunRules.skipRetry(last, now)) {
                Scheduler.note(ctx, "Retry skipped: a backup was saved ${RunRules.minutesSince(last, now)} min ago")
                return
            }
            val pending = goAsync()
            thread {
                try { Scheduler.runAutomatic(ctx) } finally { pending.finish() }
            }
            return
        }
        // Tap-to-start reminder: no nagging if a backup already succeeded in the last 12 hours.
        if (RunRules.skipReminder(last, now)) {
            Scheduler.note(ctx, "No reminder: you already backed up ${RunRules.minutesSince(last, now)!! / 60} h ago")
            return
        }
        if (!retry) {
            Scheduler.showReminder(ctx)
            Scheduler.note(ctx, "Reminder shown")
        }
    }
}

/** After a reboot or an app update Android has forgotten the alarm, so set it again (and catch up if it was missed). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val sp = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
        if (!sp.getBoolean("schedOn", false)) return
        val hour = sp.getString("schedHour", "8")?.toIntOrNull() ?: 8
        val minute = sp.getString("schedMinute", "0")?.toIntOrNull() ?: 0
        // Phone was off at reminder time: remind now if it is within 4 hours of the backup time and nothing was backed up.
        val now = Calendar.getInstance()
        val due = (now.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, minute); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val missed = now.timeInMillis in due..(due + 4 * 60 * 60 * 1000L)
        Scheduler.schedule(ctx, hour, minute)
        val last = sp.getLong("lastBackupAt", 0L)
        if (missed && !RunRules.skipReminder(last, System.currentTimeMillis())) {
            if (Scheduler.automatic(ctx)) {
                Scheduler.note(ctx, "The phone was off at backup time; running now")
                val pending = goAsync()
                thread {
                    try { Scheduler.runAutomatic(ctx) } finally { pending.finish() }
                }
            } else {
                Scheduler.showReminder(ctx)
            }
        }
    }
}

/**
 * The daily alarm is stored as one fixed moment. When the phone's clock or time zone changes, that moment no longer
 * matches "every day at 9:00", so it is worked out again.
 */
class TimeChangedReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val sp = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
        if (!sp.getBoolean("schedOn", false)) return
        Scheduler.note(ctx, "The phone's clock or time zone changed; daily alarm set again")
        Scheduler.rearm(ctx)
    }
}
