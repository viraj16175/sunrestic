package app.sunrestic

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.LocalOffer
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

/** "2026-11-03T08:12:45+05:30" -> "Nov 3, 2026 · 08:12" */
fun fmtSnapTime(t: String): String = runCatching {
    val month = t.substring(5, 7).toInt()
    val day = t.substring(8, 10).toInt()
    "${MONTHS[month - 1]} $day, ${t.substring(0, 4)} · ${t.substring(11, 16)}"
}.getOrDefault(t.take(16))

/** What kind of snapshot this is, for headings. */
fun snapKind(s: SnapInfo): String = when {
    "android-apps" in s.tags -> "apps"
    "android-folders" in s.tags -> "folders"
    PhoneKind.of(s.tags) != null -> PhoneKind.of(s.tags)!!.name.lowercase() // "contacts", "calls", "sms" or "wallpaper"
    else -> "paths"
}

/** Restoid's home: "Snapshots (N)" and one card per snapshot with the icons of the apps inside. */
@Composable
fun SnapshotsScreen(m: AppModel) {
    val canRun = !JobHub.busy && m.ready

    // List once, and again after a backup finished (a backup adds a snapshot). The refresh button forces it.
    LaunchedEffect(m.ready, JobHub.busy) { m.refreshSnapshotsIfStale() }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 14.dp, top = 2.dp, end = 14.dp, bottom = 84.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (!m.ready) {
                item {
                    NoticeCard(
                        icon = Icons.Filled.Storage,
                        title = "Repository not ready",
                        text = "Set up and unlock the repository to see your snapshots.",
                        container = MaterialTheme.colorScheme.surfaceContainerLow,
                        content = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
            }

            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionLabel("Snapshots · ${m.snapList.size}", Modifier.weight(1f))
                    IconButton(onClick = { m.loadSnapshotList() }, enabled = canRun && !m.loadingSnaps) {
                        Icon(Icons.Filled.Refresh, "Refresh", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            if (m.loadingSnaps || m.loadingSnapApps) {
                item {
                    LinearProgressIndicator(
                        Modifier.fillMaxWidth().padding(bottom = 6.dp).clip(CircleShape),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    )
                }
            }

            if (!m.loadingSnapApps && m.snapAppsProblem.isNotEmpty()) {
                item {
                    NoticeCard(
                        icon = Icons.Filled.Warning,
                        title = "Could not read some app lists",
                        text = m.snapAppsProblem,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }
            }
            if (m.loadingSnapApps) {
                item {
                    val total = m.snapList.count { "android-apps" in it.tags }
                    val done = m.snapList.count { "android-apps" in it.tags && it.id in m.snapApps }
                    Text(
                        "Reading what each backup holds · $done of $total (only the first time on this phone)",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 6.dp, bottom = 4.dp),
                    )
                }
            }

            if (m.ready && m.connectionProblem.isNotEmpty() && !m.loadingSnaps) {
                item {
                    NoticeCard(
                        icon = Icons.Filled.Warning,
                        title = "Can't read the repository",
                        text = m.connectionProblem,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }
                item { PillButton("Try again", Modifier.fillMaxWidth(), strong = true, icon = Icons.Filled.Refresh) { m.retryConnection() } }
            }

            if (m.snapList.isEmpty() && m.loadingSnaps) {
                item { SkeletonList(rows = 3, modifier = Modifier.padding(horizontal = 0.dp)) }
            } else if (m.snapList.isEmpty() && m.ready && m.connectionProblem.isEmpty()) {
                item {
                    EmptyState(
                        icon = Icons.Filled.History,
                        title = "No snapshots yet",
                        text = "Back up some apps or folders and they show up here.",
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            items(m.snapList, key = { it.id }) { s ->
                SnapshotListCard(
                    s = s,
                    apps = m.snapApps[s.id],
                    reading = m.loadingSnaps || m.loadingSnapApps,
                ) { m.openSnapshot(s) }
            }
        }

        BigFab(
            "Back up now",
            Icons.Filled.PlayArrow,
            modifier = Modifier.align(Alignment.BottomEnd).padding(14.dp),
            enabled = canRun,
        ) { m.runScheduled() }
    }
}

@Composable
private fun SnapshotListCard(
    s: SnapInfo,
    apps: List<AppInfo>?,
    reading: Boolean,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val kind = snapKind(s)
    val rel = relativeTime(s.time)
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        color = cs.surfaceContainerLow,
        border = cardBorder(),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    CardLabel(
                        when (kind) {
                            "apps" -> if (apps == null) "Apps" else "Apps · ${apps.size}"
                            "folders" -> "Folders · ${s.pathList.size.coerceAtLeast(s.paths)}"
                            "contacts", "calls", "sms", "wallpaper" -> PhoneKind.of(s.tags)!!.label + (snapshotCount(s.tags)?.let { " · $it" } ?: "")
                            else -> "Paths · ${s.pathList.size.coerceAtLeast(s.paths)}"
                        },
                    )
                    Text(s.id.take(8), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                }
                Text(
                    if (rel.isEmpty()) s.time.take(10) else rel,
                    style = MaterialTheme.typography.bodyMedium,
                    color = cs.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            // The user's own tags, when this snapshot is a backup of tagged apps.
            val userTags = s.tags.filter { !it.startsWith("android-") }
            if (userTags.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    userTags.take(4).forEach { Pill(it, icon = Icons.Filled.LocalOffer) }
                }
            }
            when (kind) {
                "apps" -> if (apps == null) {
                    Text(
                        if (reading) "Reading the app list…" else "Could not read the app list. Tap refresh to try again.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = cs.onSurfaceVariant,
                    )
                } else {
                    IconStrip(apps)
                }
                "contacts", "calls", "sms", "wallpaper" -> {
                    val k = PhoneKind.of(s.tags)!!
                    val n = snapshotCount(s.tags)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconBadge(phoneIcon(k), size = 36.dp)
                        Text(
                            if (n != null) "$n ${phoneWord(k, n)}" else k.label,
                            style = MaterialTheme.typography.bodyMedium,
                            color = cs.onSurfaceVariant,
                        )
                    }
                }
                else -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (s.pathList.isNotEmpty()) FolderStrip(s.pathList) else IconBadge(Icons.Filled.Folder, size = 36.dp)
                    Text(
                        s.pathList.joinToString("  ·  ") { it.substringAfterLast('/').ifEmpty { it } }.ifEmpty { "—" },
                        style = MaterialTheme.typography.bodyMedium,
                        color = cs.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** As many round app icons as fit on one line; the rest become a "+N" circle. */
@Composable
internal fun IconStrip(apps: List<AppInfo>) {
    val size = 36.dp
    val gap = 8.dp
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val fit = ((maxWidth + gap) / (size + gap)).toInt().coerceAtLeast(1)
        val showMore = apps.size > fit
        val shown = if (showMore) apps.take(fit - 1) else apps
        Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
            shown.forEach { a -> AppIcon(a.pkg, a.label, size) }
            if (showMore) {
                Box(
                    Modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHighest),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "+${apps.size - shown.size}",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
