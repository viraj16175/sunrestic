package app.sunrestic

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.LocalOffer
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.produceState
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One daily time. At that time the phone either backs up by itself or reminds you; the rest of the page says what and whether it will work. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ScheduleScreen(m: AppModel) {
    val cs = MaterialTheme.colorScheme
    val byTags = m.schedAppMode == "tags"
    val tagNames = m.tagNames
    val pickedTags = m.schedTags
    val appCount = m.scheduledApps().size
    val willRun = (m.schedFolders && m.folders.isNotEmpty()) || (m.schedApps && appCount > 0)

    val ctx = LocalContext.current
    val auto = m.autoBackup && m.unattendedSaved
    val health = remember(m.healthTick, m.resumed) { Scheduler.health(ctx) }
    val allGood = health.notifications && health.exactAlarms && health.unrestricted
    val next = remember(m.healthTick, m.schedOn, m.schedHour) { Scheduler.nextReminderAt(ctx) }
    val hour = (m.schedHour.toIntOrNull() ?: 8).coerceIn(0, 23)
    val minute = (m.schedMinute.toIntOrNull() ?: 0).coerceIn(0, 59)
    val timeText = Scheduler.timeLabel(ctx, hour, minute) // in the phone's own style, 12 h with AM/PM or 24 h
    var picker by remember { mutableStateOf(false) }
    val steps by produceState(Scheduler.recentNotes(ctx), m.healthTick) {
        while (true) {
            value = Scheduler.recentNotes(ctx)
            delay(1500)
        }
    }
    // Only MIUI / HyperOS delete alarms and need "Autostart". Custom ROMs on Xiaomi hardware (crDroid, LineageOS) do not.
    val xiaomi = remember {
        runCatching { ctx.packageManager.getPackageInfo("com.miui.securitycenter", 0) }.isSuccess
    }

    ScreenScroll {
        SectionCard(
            "Daily backup",
            icon = Icons.Filled.Alarm,
            subtitle = "One time every day. You choose whether the phone starts the backup by itself or only reminds you.",
        ) {
            SwitchRow(
                title = "Back up every day",
                subtitle = if (m.schedOn) "Every day at $timeText" else "Off",
                checked = m.schedOn,
                onChange = {
                    m.schedOn = it
                    m.applySchedule()
                },
            )
            if (m.schedOn) {
                // 1. when: tap the time to set it on a clock; the buttons move it by one hour
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        Modifier.weight(1f).clip(MaterialTheme.shapes.medium).clickable { picker = true }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        IconBadge(Icons.Filled.Schedule, size = 36.dp)
                        Column(Modifier.weight(1f)) {
                            CardLabel("Time (tap to set)")
                            Text(timeText, style = MaterialTheme.typography.titleLarge)
                        }
                    }
                    IconButton(onClick = { m.schedHour = ((hour + 23) % 24).toString(); m.applySchedule() }) {
                        Icon(Icons.Filled.Remove, "One hour earlier")
                    }
                    IconButton(onClick = { m.schedHour = ((hour + 1) % 24).toString(); m.applySchedule() }) {
                        Icon(Icons.Filled.Add, "One hour later")
                    }
                }

                // 2. how
                CardLabel("What happens at $timeText?")
                ChoiceCard(
                    icon = Icons.Filled.Autorenew,
                    title = "The phone backs up by itself",
                    subtitle = "Starts even when the app is closed. No fingerprint needed.",
                    selected = auto,
                ) { if (!auto) m.enableUnattended() }
                ChoiceCard(
                    icon = Icons.Filled.NotificationsActive,
                    title = "The phone only reminds me",
                    subtitle = "Shows a notification. Tap it and touch the fingerprint sensor to start.",
                    selected = !auto,
                ) { if (auto) m.disableUnattended() }

                if (auto) {
                    SwitchRow(
                        title = "Only while charging",
                        subtitle = "If it is not charging, it tries again every 30 minutes for 6 hours",
                        checked = m.autoCharging,
                        onChange = { m.autoCharging = it },
                    )
                    Text(
                        if (m.wifiOnly && m.remote) "Wi-Fi only is on (Repo tab, Options): it waits for Wi-Fi too."
                        else "It also uses mobile data. Turn on Wi-Fi only in the Repo tab, Options, to avoid that.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = cs.onSurfaceVariant,
                    )
                    PillButton("Update saved password", Modifier.fillMaxWidth(), enabled = m.password.isNotEmpty()) { m.enableUnattended() }
                    Text(
                        "For this, the repository password is kept on the phone without a fingerprint, protected only by the " +
                            "Android Keystore. Choose \"only reminds me\" to delete it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant,
                    )
                }
            }
        }

        if (m.schedOn) {
            SectionCard(
                "Status",
                icon = Icons.Filled.Info,
                subtitle = "When the next backup runs and what the last runs did",
            ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                CardLabel("Next backup")
                Text(
                    if (next <= 0L) "Not scheduled yet" else SimpleDateFormat("EEE d MMM, HH:mm", Locale.getDefault()).format(Date(next)),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                CardLabel("What happened (latest steps)")
                Text(
                    if (steps.isEmpty()) "Nothing yet. Tap Try it now, or the alarm test below."
                    else steps.takeLast(6).joinToString("\n"),
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                PillButton("Try it now", Modifier.weight(1f), strong = true, icon = Icons.Filled.PlayArrow, enabled = !JobHub.busy) {
                    if (auto) {
                        kotlin.concurrent.thread { Scheduler.runAutomatic(ctx.applicationContext, now = true) }
                    } else if (m.ready) {
                        m.runScheduled()
                    } else {
                        Toast.makeText(ctx, "Unlock the repository first", Toast.LENGTH_SHORT).show()
                    }
                }
                if (!auto) {
                    PillButton("Test reminder", Modifier.weight(1f), icon = Icons.Filled.NotificationsActive) {
                        Scheduler.showReminder(ctx)
                        Toast.makeText(ctx, "Reminder sent. Pull down the notification shade and tap it.", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            PillButton("Test the alarm (fires in 1 minute)", Modifier.fillMaxWidth(), icon = Icons.Filled.Alarm) {
                val at = Scheduler.scheduleTest(ctx)
                m.healthTick++
                Toast.makeText(
                    ctx,
                    "Alarm set for " + android.text.format.DateFormat.getTimeFormat(ctx).format(Date(at)) +
                        ". Leave the app (use Home, don't swipe it away) and wait.",
                    Toast.LENGTH_LONG,
                ).show()
            }
            }
        }

        if (m.schedOn && xiaomi) {
            NoticeCard(
                icon = Icons.Filled.Warning,
                title = "Xiaomi / POCO phone: allow Autostart",
                text = "Without Autostart, HyperOS deletes the daily alarm when you swipe the app away from recent apps, " +
                    "and blocks it from starting in the background. Turn Autostart ON for this app, set Battery to " +
                    "\"No restrictions\", and don't swipe the app away.",
                container = cs.tertiaryContainer,
                content = cs.onTertiaryContainer,
            )
            PillButton("Open Autostart settings", Modifier.fillMaxWidth(), icon = Icons.Filled.Settings) {
                val tries = listOf(
                    Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")),
                    Intent("miui.intent.action.OP_AUTO_START").addCategory(Intent.CATEGORY_DEFAULT),
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")),
                )
                val opened = tries.any { runCatching { ctx.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess }
                if (!opened) Toast.makeText(ctx, "Open Settings → Apps → this app → Autostart", Toast.LENGTH_LONG).show()
            }
        }

        if (m.schedOn && !allGood) {
            NoticeCard(
                icon = Icons.Filled.Warning,
                title = "Android may delay or block your daily backup",
                text = "Tap the red rows under \"Will it start on time?\" at the bottom of this page and allow each one. " +
                    "No root needed." + if (m.useRoot) " (With root there is also a one-tap shortcut below.)" else "",
                container = cs.errorContainer,
                content = cs.onErrorContainer,
            )
            if (m.useRoot) PillButton("Optional shortcut: fix both with root", Modifier.fillMaxWidth()) { m.fixReminderWithRoot() }
        }

        if (m.schedOn && !willRun) {
            NoticeCard(
                icon = Icons.Filled.Warning,
                title = "Nothing will be backed up",
                text = "Turn on a task below. Folders need at least one saved folder; " +
                    "apps need a tag that has apps, or one app backup first.",
                container = cs.errorContainer,
                content = cs.onErrorContainer,
            )
        }

        SectionLabel("What to back up")
        Group(
            {
                Box(Modifier.padding(horizontal = 18.dp, vertical = 8.dp)) {
                    SwitchRow(
                        title = "Back up my saved folders",
                        subtitle = when (m.folders.size) {
                            0 -> "No saved folders yet"
                            1 -> "1 folder"
                            else -> "${m.folders.size} folders"
                        },
                        checked = m.schedFolders,
                        onChange = { m.schedFolders = it },
                    )
                }
            },
            {
                Column(Modifier.padding(horizontal = 18.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SwitchRow(
                        title = "Back up apps",
                        subtitle = when {
                            byTags && tagNames.isEmpty() -> "No tags yet"
                            byTags && pickedTags.isEmpty() -> "Pick at least one tag"
                            byTags -> "$appCount ${if (appCount == 1) "app" else "apps"} tagged " +
                                pickedTags.sortedBy { it.lowercase() }.joinToString()
                            appCount == 0 -> "Do an app backup once first"
                            else -> "$appCount ${if (appCount == 1) "app" else "apps"} from the last app backup"
                        },
                        checked = m.schedApps,
                        onChange = { m.schedApps = it },
                    )
                    if (m.schedApps) {
                        CardLabel("Which apps")
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            ChoiceChip("Tagged apps", selected = byTags, icon = Icons.Filled.LocalOffer) { m.schedAppMode = "tags" }
                            ChoiceChip("Last app backup", selected = !byTags) { m.schedAppMode = "last" }
                        }
                        if (byTags && tagNames.isEmpty()) {
                            Text(
                                "Open Apps, tick the apps you want, tap Tags and give them a tag. Then pick that tag here.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = cs.onSurfaceVariant,
                            )
                            PillButton("Open apps") {
                                m.appsMode = 0
                                m.page = Page.APPS
                            }
                        } else if (byTags) {
                            CardLabel("Tags to back up")
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                tagNames.forEach { t ->
                                    val on = t in pickedTags
                                    ChoiceChip(
                                        "$t · ${m.appTags[t].orEmpty().size}",
                                        selected = on,
                                        icon = if (on) Icons.Filled.Check else null,
                                    ) { m.toggleSchedTag(t) }
                                }
                            }
                        }
                    }
                }
            },
        )

        SectionLabel("Will it start on time?", icon = Icons.Filled.NotificationsActive)
        ReminderStatus(m)
    }

    if (picker) {
        TimeDialog(
            hour = hour,
            minute = minute,
            is24 = android.text.format.DateFormat.is24HourFormat(ctx),
            onDismiss = { picker = false },
        ) { h, min ->
            m.schedHour = h.toString()
            m.schedMinute = min.toString()
            m.applySchedule()
            picker = false
        }
    }
}

/** Android's own clock for choosing the backup time, in 12 h (AM/PM) or 24 h like the rest of the phone. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeDialog(hour: Int, minute: Int, is24: Boolean, onDismiss: () -> Unit, onSave: (Int, Int) -> Unit) {
    val state = rememberTimePickerState(initialHour = hour, initialMinute = minute, is24Hour = is24)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Backup time") },
        text = { TimePicker(state = state) },
        confirmButton = { TextButton(onClick = { onSave(state.hour, state.minute) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    )
}

/** Shows what can stop the reminder, with a tap to fix each one, and a button that sends a real test reminder. */
@Composable
private fun ReminderStatus(m: AppModel) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val good = LocalExtra.current.good
    val health = remember(m.healthTick, m.resumed) { Scheduler.health(ctx) }
    val next = remember(m.healthTick, m.schedOn, m.schedHour) { Scheduler.nextReminderAt(ctx) }
    val allGood = health.notifications && health.exactAlarms && health.unrestricted

    fun open(vararg tries: Intent) {
        for (i in tries) {
            val ok = runCatching { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
            if (ok) return
        }
        Toast.makeText(ctx, "Could not open that settings page", Toast.LENGTH_SHORT).show()
    }
    val pkgUri = Uri.parse("package:${ctx.packageName}")
    val details = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri)

    Group(
        {
            StatusLine(
                "Notifications",
                if (health.notifications) "Allowed" else "Blocked: notifications can't show",
                health.notifications, good,
            ) {
                open(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName),
                    details,
                )
            }
        },
        if (BuildConfig.PLAY) null else ({
            StatusLine(
                "Exact alarms",
                if (health.exactAlarms) "Allowed" else "Not allowed: the backup may start late or not at all",
                health.exactAlarms, good,
            ) { open(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkgUri), details) }
        }),
        {
            StatusLine(
                "Battery",
                if (health.unrestricted) "Unrestricted" else "Optimised: Android may delay it",
                health.unrestricted, good,
            ) {
                if (BuildConfig.PLAY) {
                    open(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS), details) // the list; no special permission
                } else {
                    open(
                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkgUri), // one-tap system dialog
                        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
                        details,
                    )
                }
            }
        },
        {
            Column(Modifier.padding(horizontal = 18.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                CardLabel("Next reminder")
                Text(
                    if (!m.schedOn || next <= 0L) "Not scheduled"
                    else SimpleDateFormat("EEE d MMM, HH:mm", Locale.getDefault()).format(Date(next)),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        },
        {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PillButton("Send test reminder", Modifier.weight(1f)) {
                        Scheduler.showReminder(ctx)
                        Toast.makeText(ctx, "Reminder sent. Pull down the shade and tap it.", Toast.LENGTH_SHORT).show()
                    }
                    if (m.useRoot && !allGood) {
                        PillButton("Fix with root", Modifier.weight(1f), strong = true) { m.fixReminderWithRoot() }
                    }
                }
                Text(
                    if (allGood) "All set. If it still does not show, send a test reminder to check the notification."
                    else "Tap a row to open its setting. With root, \"Fix with root\" allows exact alarms and removes battery limits in one go.",
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant,
                )
            }
        },
    )
}

@Composable
private fun StatusLine(title: String, value: String, ok: Boolean, good: Color, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        StatusDot(if (ok) good else cs.error)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(value, style = MaterialTheme.typography.bodyMedium, color = if (ok) cs.onSurfaceVariant else cs.error)
        }
        if (!ok) Icon(Icons.Filled.ChevronRight, null, tint = cs.onSurfaceVariant)
    }
}
