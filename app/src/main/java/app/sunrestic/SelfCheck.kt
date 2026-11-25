package app.sunrestic

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One line of the self check. [level]: 0 = fine, 1 = worth a look, 2 = broken. */
data class CheckItem(val title: String, val level: Int, val detail: String)

/** The report as plain text, for pasting into a chat. */
fun checkReport(items: List<CheckItem>): String = buildString {
    appendLine("Sunrestic self check, " + SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date()))
    for (i in items) appendLine((if (i.level == 0) "[ok]   " else if (i.level == 1) "[warn] " else "[FAIL] ") + i.title + ": " + i.detail)
}

/** Reads and tests; never changes anything and never starts a backup. */
@Composable
fun SelfCheckPage(m: AppModel) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val x = LocalExtra.current
    val activity = ctx.findActivity()
    LaunchedEffect(Unit) { if (m.checks.isEmpty() && !m.checking) m.runSelfCheck() }
    // Unlocked while this page is open: the repository checks that were skipped can run now.
    LaunchedEffect(m.ready) {
        if (m.ready && !m.checking && m.checks.any { it.title == "Repository unlocked" && it.level == 2 }) m.runSelfCheck()
    }
    val fails = m.checks.count { it.level == 2 }
    val warns = m.checks.count { it.level == 1 }

    PageFrame(
        m,
        topBar = {
            SwiftTopBar(
                title = "Check everything",
                subtitle = when {
                    m.checking -> "Checking…"
                    fails > 0 -> "$fails problem(s), $warns to look at"
                    warns > 0 -> "Works, $warns to look at"
                    else -> "Everything checked is fine"
                },
                onBack = { m.closePage() },
            )
        },
    ) {
        ScreenScroll {
            m.checks.forEach { c ->
                val (icon, container, tint) = when (c.level) {
                    0 -> Triple(Icons.Filled.CheckCircle, cs.primaryContainer, cs.onPrimaryContainer)
                    1 -> Triple(Icons.Filled.Warning, cs.tertiaryContainer, cs.onTertiaryContainer)
                    else -> Triple(Icons.Filled.Error, cs.errorContainer, cs.onErrorContainer)
                }
                Surface(shape = MaterialTheme.shapes.large, color = cs.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        IconBadge(icon, container = container, tint = tint, size = 36.dp)
                        Column(Modifier.weight(1f)) {
                            Text(c.title, style = MaterialTheme.typography.titleMedium)
                            Text(
                                c.detail,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (c.level == 2) cs.error else cs.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            if (m.checking) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            if (!m.ready && m.repoConfigured && !m.checking) {
                if (m.vaultSaved && activity != null) {
                    PillButton("Unlock with fingerprint", Modifier.fillMaxWidth(), strong = true, enabled = !m.unlocking) {
                        m.unlockPassword(activity)
                    }
                } else {
                    Text(
                        "Enter the repository password in the Repo tab to unlock it.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = cs.onSurfaceVariant,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                PillButton("Run again", Modifier.weight(1f), strong = true, enabled = !m.checking) { m.runSelfCheck() }
                PillButton("Copy report", Modifier.weight(1f), enabled = m.checks.isNotEmpty()) {
                    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("report", checkReport(m.checks)))
                    Toast.makeText(ctx, "Report copied", Toast.LENGTH_SHORT).show()
                }
            }
            Text(
                "This only looks. It never starts a backup or changes anything.",
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
            )
        }
    }
}
