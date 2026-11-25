package app.sunrestic

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Names Restoid uses for the parts of an app backup. */
fun Component.longLabel(): String = when (this) {
    Component.APK -> "APK"
    Component.DATA -> "Data"
    Component.DEVICE_DATA -> "Device Protected Data"
    Component.EXT_DATA -> "External Data"
    Component.MEDIA -> "Media"
    Component.OBB -> "OBB"
}

/** Restoid's "Snapshot Details": ID card, then every app (or folder) in the snapshot, and a Restore button. */
@Composable
fun SnapshotDetailsPage(m: AppModel) {
    val s = m.openSnap
    if (s == null) {
        LaunchedEffect(Unit) { m.closePage() }
        return
    }
    val cs = MaterialTheme.colorScheme
    val canRun = !JobHub.busy && m.ready
    val isApps = snapKind(s) == "apps"
    val phoneKind = PhoneKind.of(s.tags)
    val starter = rememberPhoneStarter(m)
    val apps = m.snapApps[s.id]
    val sizes = m.detailSizes
    var sheet by remember { mutableStateOf(false) }

    if (sheet) {
        if (phoneKind != null) PhoneRestoreOneSheet(m, phoneKind, s.id, starter) { sheet = false }
        else if (isApps) RestoreAppsSheet(m, s) { sheet = false } else RestoreFolderSheet(m, s) { sheet = false }
    }

    PageFrame(
        m,
        topBar = {
            SwiftTopBar("Snapshot", "${s.id.take(8)} · ${fmtSnapTime(s.time)}", onBack = { m.closePage() }) {
                IconButton(
                    onClick = {
                        m.confirm = "Delete the ${snapKind(s)} snapshot ${s.id.take(8)} from ${fmtSnapTime(s.time)}? " +
                            "If it holds the only backup of an app, that app can no longer be restored." to {
                            m.deleteSnapshot(s.id)
                            m.closePage()
                        }
                    },
                    enabled = canRun,
                ) { Icon(Icons.Filled.Delete, "Delete snapshot", tint = cs.error, modifier = Modifier.size(22.dp)) }
            }
        },
        fab = {
            val count = if (isApps) apps.orEmpty().count { it.pkg in m.detailSel } else 1
            BigFab("Restore", Icons.Filled.Restore, enabled = canRun && count > 0) { sheet = true }
        },
    ) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 14.dp, top = 2.dp, end = 14.dp, bottom = 84.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item { IdCard(s) }

            if (phoneKind != null) {
                item {
                    val n = snapshotCount(s.tags)
                    Surface(shape = MaterialTheme.shapes.large, color = cs.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
                        Row(
                            Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            IconBadge(phoneIcon(phoneKind), size = 48.dp)
                            Column {
                                Text(phoneKind.label, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    if (n != null) "$n ${phoneWord(phoneKind, n)} in this backup" else "A backup of your ${phoneKind.label.lowercase()}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = cs.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            } else if (isApps) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SectionLabel("Backed up apps · ${apps?.size ?: "…"}", Modifier.weight(1f))
                        if (apps != null && apps.isNotEmpty()) {
                            val all = apps.all { it.pkg in m.detailSel }
                            TextButton(onClick = {
                                m.detailSel = if (all) emptySet() else apps.map { it.pkg }.toSet()
                            }) { Text(if (all) "None" else "All") }
                        }
                    }
                }
                if (apps == null) {
                    item { SkeletonList(rows = 4, modifier = Modifier.padding(horizontal = 0.dp)) }
                } else {
                    val n = apps.size
                    itemsIndexed(apps, key = { _, a -> a.pkg }) { i, a ->
                        Box(Modifier.clip(MaterialTheme.shapes.large)) {
                            SnapshotAppRow(
                                label = a.label,
                                pkg = a.pkg,
                                line = appLine(a, sizes),
                                chips = appChips(a, sizes),
                                checked = a.pkg in m.detailSel,
                            ) {
                                m.detailSel = if (a.pkg in m.detailSel) m.detailSel - a.pkg else m.detailSel + a.pkg
                            }
                        }
                    }
                }
            } else {
                item {
                    SectionLabel("Backed up folders · ${s.pathList.size}")
                }
                val n = s.pathList.size
                itemsIndexed(s.pathList, key = { _, p -> p }) { i, p ->
                    Surface(
                        shape = MaterialTheme.shapes.large,
                        color = cs.surfaceContainerLow,
                        border = cardBorder(),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            Modifier.padding(start = 16.dp, top = 12.dp, end = 14.dp, bottom = 14.dp),
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            IconBadge(Icons.Filled.Folder, size = 48.dp)
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    p.substringAfterLast('/').ifEmpty { p },
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(p, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                                Text(
                                    "Size: " + sizeText(sizes, listOf(p)),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = cs.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }

            if (m.detailError.isNotEmpty()) {
                item {
                    Text(
                        m.detailError,
                        style = MaterialTheme.typography.bodyMedium,
                        color = cs.onSurfaceVariant,
                        modifier = Modifier.padding(start = 8.dp, top = 12.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun IdCard(s: SnapInfo) {
    val cs = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(20.dp), color = cs.surfaceContainerLow, border = cardBorder(), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                CardLabel("Snapshot")
                Text(s.id, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(fmtSnapTime(s.time), style = MaterialTheme.typography.bodyMedium)
                if (s.totalBytes > 0 || s.files > 0) {
                    val parts = buildList {
                        if (s.totalBytes > 0) add("Size: ${fmtBytes(s.totalBytes)}")
                        if (s.files > 0) add("Files: ${s.files}")
                        if (s.dataAdded > 0) add("Added: ${fmtBytes(s.dataAdded)}")
                    }
                    Text(parts.joinToString(" - "), style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                }
            }
        }
    }
}

private fun sizeText(sizes: Map<String, Long>?, roots: List<String>): String {
    if (sizes == null) return "…"
    val known = roots.mapNotNull { sizes[it.trimEnd('/')] }
    return if (known.isEmpty()) "—" else fmtBytes(known.sum())
}

private fun appLine(a: AppInfo, sizes: Map<String, Long>?): String {
    val roots = Component.values().flatMap { it.paths(a) }
    val v = a.version.ifBlank { "?" }
    return "Version: $v - Size: ${sizeText(sizes, roots)}"
}

/** Which parts this snapshot really holds for the app (from the file list once loaded, else from the manifest). */
private fun appChips(a: AppInfo, sizes: Map<String, Long>?): List<String> {
    val comps = if (sizes != null) {
        Component.values().filter { c -> c.paths(a).any { sizes.containsKey(it.trimEnd('/')) } }
    } else {
        Component.values().filter { it.name in a.components }
    }
    return comps.map { it.longLabel() } + if (a.perms.isNotEmpty()) listOf("Permissions") else emptyList()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RestoreAppsSheet(m: AppModel, s: SnapInfo, onDismiss: () -> Unit) {
    val count = m.snapApps[s.id].orEmpty().count { it.pkg in m.detailSel }
    SheetFrame(onDismiss, scrollable = false) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(if (count == 1) "Restore 1 app" else "Restore $count apps", style = MaterialTheme.typography.titleLarge)
            Text(
                "From snapshot ${s.id.take(8)} · ${fmtSnapTime(s.time)}",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        ComponentPicker(m)
        NoticeCard(
            icon = Icons.Filled.Warning,
            title = "Your current data is kept as a safety copy",
            text = "The backup is read in full first. Then the app’s current data is moved aside and the backup takes its place. If anything goes wrong, your current data is put back.",
        )
        PillButton("Restore", Modifier.fillMaxWidth(), strong = true, enabled = !JobHub.busy && m.ready && m.comps.isNotEmpty() && count > 0 && (m.useRoot || !BuildConfig.PLAY)) {
            onDismiss()
            m.startRestoreFromSnapshot(s.id)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RestoreFolderSheet(m: AppModel, s: SnapInfo, onDismiss: () -> Unit) {
    SheetFrame(onDismiss) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Restore this snapshot", style = MaterialTheme.typography.titleLarge)
            Text(
                "${s.id.take(8)} · ${fmtSnapTime(s.time)}",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        FolderRestorePanel(
            m,
            originals = s.pathList,
            enabled = !JobHub.busy && m.ready && m.folderAccess,
            actionLabel = "Restore",
        ) { target, overwrite ->
            onDismiss()
            m.startFolderRestore(s.id, target, overwrite)
        }
    }
}
