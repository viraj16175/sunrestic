package app.sunrestic

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Full-screen page that opens when a backup or restore starts: live progress while it runs,
 * then the result. Hiding it never stops the job; the banner on other pages brings it back.
 */
@Composable
fun JobScreen(m: AppModel) {
    val cs = MaterialTheme.colorScheme
    val x = LocalExtra.current
    // Not busy and no result recorded: the job is over, whatever ended it.
    val outcome = JobHub.outcome ?: if (!JobHub.busy) "done" else null
    val running = outcome == null
    val p = JobHub.progress
    val tail = remember(JobHub.log) { JobHub.log.lines().filter { it.isNotBlank() }.takeLast(6) }

    Surface(Modifier.fillMaxSize(), color = cs.background) {
        Column(Modifier.systemBarsPadding().padding(horizontal = 20.dp, vertical = 8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { JobHub.screenOpen = false }) {
                    Icon(if (running) Icons.Filled.KeyboardArrowDown else Icons.Filled.Close, if (running) "Hide" else "Close")
                }
                Text(
                    if (running) "Runs in the background if you hide this" else "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = cs.onSurfaceVariant,
                )
            }

            Column(
                Modifier.weight(1f).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                if (running) {
                    Box(contentAlignment = Alignment.Center) {
                        if (p != null) {
                            CircularProgressIndicator(
                                progress = { p.percent.toFloat().coerceIn(0f, 1f) },
                                modifier = Modifier.size(176.dp),
                                strokeWidth = 10.dp,
                                color = cs.primary,
                                trackColor = cs.surfaceContainerHighest,
                                strokeCap = androidx.compose.ui.graphics.StrokeCap.Round,
                            )
                            Text(
                                "${(p.percent * 100).toInt()}%",
                                style = MaterialTheme.typography.displaySmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                        } else {
                            CircularProgressIndicator(
                                modifier = Modifier.size(176.dp),
                                strokeWidth = 10.dp,
                                color = cs.primary,
                                trackColor = cs.surfaceContainerHighest,
                                strokeCap = androidx.compose.ui.graphics.StrokeCap.Round,
                            )
                            Text("…", style = MaterialTheme.typography.displaySmall, color = cs.onSurfaceVariant)
                        }
                    }
                    Spacer(Modifier.height(24.dp))
                    Text(JobHub.title.ifEmpty { "Working" }, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (p != null) "${fmtBytes(p.bytesDone)} of ${fmtBytes(p.totalBytes)}" else "Getting ready…",
                        style = MaterialTheme.typography.bodyLarge,
                        color = cs.onSurfaceVariant,
                    )
                    if (p != null) {
                        val eta = fmtEta(p.secondsRemaining)
                        Text(
                            "${p.filesDone} of ${p.totalFiles} files" + if (eta.isNotEmpty()) " · $eta" else "",
                            style = MaterialTheme.typography.bodyMedium,
                            color = cs.onSurfaceVariant,
                        )
                    }
                } else {
                    val (icon, tint, heading) = when (outcome) {
                        "done" -> Triple(Icons.Filled.CheckCircle, x.good, "All done")
                        "warning" -> Triple(Icons.Filled.Warning, cs.tertiary, "Saved, but the check failed")
                        "cancelled" -> Triple(Icons.Filled.StopCircle, cs.onSurfaceVariant, "Cancelled")
                        else -> Triple(Icons.Filled.Error, cs.error, "Something went wrong")
                    }
                    Icon(icon, null, Modifier.size(96.dp), tint = tint)
                    Spacer(Modifier.height(20.dp))
                    Text(heading, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    Text(JobHub.title, style = MaterialTheme.typography.bodyLarge, color = cs.onSurfaceVariant)
                    if (JobHub.outcomeText.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            JobHub.outcomeText,
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                            color = if (outcome == "failed") cs.error else cs.onSurface,
                            modifier = Modifier.padding(horizontal = 12.dp),
                        )
                    }
                    if (JobHub.restoredSms) {
                        Spacer(Modifier.height(14.dp))
                        SmsResetNotice()
                    }
                }
            }

            if (tail.isNotEmpty()) {
                Surface(shape = MaterialTheme.shapes.large, color = cs.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        CardLabel("Latest")
                        tail.forEach {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                color = cs.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (running) {
                    PillButton("Log", Modifier.weight(1f)) { m.showLog = true }
                    DangerPill("Cancel", Icons.Filled.Close, Modifier.weight(1f)) { JobHub.cancel() }
                } else {
                    PillButton("View log", Modifier.weight(1f)) { m.showLog = true }
                    PillButton("Done", Modifier.weight(1f), strong = true) { JobHub.screenOpen = false }
                }
            }
        }
    }
}
