package app.sunrestic

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight

/**
 * Folders, built like the Apps page: an overview card, one card per folder with a tick box, a floating options button,
 * and a sheet that asks the last questions. One mode backs folders up, the other restores them.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FoldersPage(m: AppModel) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val restore = m.foldersMode == 1
    val busy = JobHub.busy
    var manual by remember { mutableStateOf(false) }
    var manualPath by remember { mutableStateOf("") }
    var moreMenu by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            val path = treeUriToPath(uri)
            if (path != null) m.addFolder(path)
            else Toast.makeText(ctx, "Can't map that folder to a path. Use Type a path.", Toast.LENGTH_LONG).show()
        }
    }

    // What the repository holds, and every saved folder ticked the first time the page opens.
    LaunchedEffect(m.ready, m.foldersMode, JobHub.dataVersion) { if (m.ready) m.refreshSnapshotsIfStale() }
    LaunchedEffect(m.folders) { if (m.folderSel.isEmpty() && m.folders.isNotEmpty()) m.folderSel = m.folders.toSet() }

    val backed = remember(m.snapList) { backedFolders(m.snapList) }
    val backedMap = remember(backed) { backed.associateBy { it.path } }
    val paths: List<String> = if (restore) backed.map { it.path } else m.folders
    val sel = if (restore) m.folderRestoreSel else m.folderSel
    val allSelected = paths.isNotEmpty() && paths.all { it in sel }
    val lastBackup = remember(m.snapList) { m.snapList.filter { "android-folders" in it.tags }.maxOfOrNull { it.time }.orEmpty() }

    fun setSel(v: Set<String>) { if (restore) m.folderRestoreSel = v else m.folderSel = v }

    if (manual) {
        AlertDialog(
            onDismissRequest = { manual = false },
            icon = { Icon(Icons.Filled.Folder, null) },
            title = { Text("Add folder path") },
            text = { Field("Absolute path", manualPath, placeholder = "/sdcard/…", icon = Icons.Filled.Folder) { manualPath = it } },
            confirmButton = {
                TextButton(onClick = {
                    m.addFolder(manualPath)
                    manualPath = ""
                    manual = false
                }) { Text("Add") }
            },
            dismissButton = { TextButton(onClick = { manual = false }) { Text("Cancel") } },
            containerColor = cs.surfaceContainerLow,
        )
    }
    if (sheet) {
        if (restore) FolderRestoreSheet(m, backed.filter { it.path in sel }) { sheet = false }
        else FolderBackupSheet(m, m.folders.filter { it in sel }) { sheet = false }
    }

    PageFrame(
        m,
        topBar = {
            SwiftTopBar(
                title = if (restore) "Restore folders" else "Back up folders",
                subtitle = "${paths.count { it in sel }}/${paths.size} selected",
                onBack = { m.closePage() },
            ) {
                if (!restore) {
                    IconButton(onClick = { picker.launch(null) }) { Icon(Icons.Filled.Add, "Add folder", Modifier.size(22.dp)) }
                }
                IconButton(onClick = { setSel(if (allSelected) sel - paths.toSet() else sel + paths) }) {
                    Icon(if (allSelected) Icons.Filled.CheckBox else Icons.Filled.CheckBoxOutlineBlank, "Select all", Modifier.size(22.dp))
                }
                Box {
                    IconButton(onClick = { moreMenu = true }) { Icon(Icons.Filled.MoreVert, "More", Modifier.size(22.dp)) }
                    DropdownMenu(expanded = moreMenu, onDismissRequest = { moreMenu = false }) {
                        DropdownMenuItem(
                            text = { Text(if (restore) "Back up instead" else "Restore instead") },
                            onClick = { m.foldersMode = if (restore) 0 else 1; moreMenu = false },
                        )
                        if (!restore) {
                            DropdownMenuItem(text = { Text("Type a path…") }, onClick = { manual = true; moreMenu = false })
                        }
                        DropdownMenuItem(text = { Text("Reload list") }, enabled = m.ready && !busy, onClick = { m.retryConnection(); moreMenu = false })
                        DropdownMenuItem(text = { Text("Select none") }, onClick = { setSel(emptySet()); moreMenu = false })
                    }
                }
            }
        },
        fab = {
            if (sel.any { it in paths }) {
                BigFab(if (restore) "Restore options" else "Backup options", Icons.Filled.Edit, enabled = !busy) { sheet = true }
            }
        },
    ) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 2.dp, bottom = 84.dp)) {
            item(key = "overview") {
                FoldersOverview(
                    restore = restore,
                    saved = m.folders.size,
                    inRepo = backed.size,
                    last = lastBackup,
                    ready = m.ready,
                    onAdd = { picker.launch(null) },
                    onType = { manual = true },
                )
            }
            if (!m.folderAccess) {
                item(key = "access") {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        NoticeCard(
                            Icons.Filled.Warning,
                            "Allow all files access",
                            "Without root, Android only lets this app reach your folders if you allow \"All files access\". " +
                                "It is used only to back up and restore the folders you choose here.",
                        )
                        PillButton("Open settings", Modifier.fillMaxWidth(), strong = true, icon = Icons.Filled.Folder) { Storage.openSettings(ctx) }
                    }
                }
            }
            if (m.ready && m.connectionProblem.isNotEmpty() && !m.loadingSnaps) {
                item(key = "problem") {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        NoticeCard(Icons.Filled.Warning, "Can't read the repository", m.connectionProblem)
                        PillButton("Try again", Modifier.fillMaxWidth(), strong = true, icon = Icons.Filled.Refresh) { m.retryConnection() }
                    }
                }
            }
            if (paths.isEmpty()) {
                item(key = "empty") {
                    when {
                        restore && !m.ready -> EmptyState(Icons.Filled.Restore, "Unlock first", "Unlock the repository to see the folders it holds.", Modifier.fillMaxWidth())
                        restore && m.loadingSnaps -> SkeletonList(rows = 3)
                        restore && m.connectionProblem.isEmpty() -> EmptyState(
                            Icons.Filled.Folder, "No folders backed up yet", "Back up some folders first. They are listed here.", Modifier.fillMaxWidth(),
                        )
                        !restore -> EmptyState(
                            Icons.Filled.Folder, "No folders yet", "Add the folders you want to back up.", Modifier.fillMaxWidth(),
                            action = { PillButton("Add folder", strong = true, icon = Icons.Filled.Add) { picker.launch(null) } },
                        )
                    }
                }
            }
            items(paths, key = { (if (restore) "r:" else "b:") + it }) { path ->
                val bf = backedMap[path]
                val status = when {
                    restore -> "Backed up " + relativeTime(bf?.time.orEmpty()).ifEmpty { "earlier" } + " · " + (bf?.time.orEmpty().take(10))
                    !m.ready -> "Unlock to see the backup status"
                    m.loadingSnaps && m.snapList.isEmpty() -> "Checking backups…"
                    m.connectionProblem.isNotEmpty() -> "Backup status unknown"
                    bf != null -> "Last backup " + relativeTime(bf.time).ifEmpty { "earlier" } + " · " + bf.time.take(10)
                    else -> "No backup yet"
                }
                FolderCard(
                    path = path,
                    status = status,
                    warn = !restore && m.ready && m.connectionProblem.isEmpty() && !m.loadingSnaps && bf == null,
                    checked = path in sel,
                    onLongClick = if (restore) null else ({
                        m.confirm = "Remove \"$path\" from the list? Its backups stay in the repository." to { m.removeFolder(path) }
                    }),
                ) { setSel(if (path in sel) sel - path else sel + path) }
            }
        }
    }
}

/** The general overview at the top: what is saved, what the repository holds, when the last folder backup ran. */
@Composable
private fun FoldersOverview(
    restore: Boolean, saved: Int, inRepo: Int, last: String, ready: Boolean, onAdd: () -> Unit, onType: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Surface(
        shape = MaterialTheme.shapes.large,
        color = cs.primaryContainer,
        contentColor = cs.onPrimaryContainer,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 5.dp),
    ) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(if (restore) "In the repository" else "Saved folders", style = MaterialTheme.typography.labelLarge)
                Text(
                    if (restore) {
                        (if (inRepo == 1) "1 folder" else "$inRepo folders") +
                            if (last.isNotEmpty()) "  ·  newest backup " + relativeTime(last).ifEmpty { last.take(10) } else ""
                    } else {
                        (if (saved == 1) "1 folder" else "$saved folders") + "  ·  " +
                            if (!ready) "unlock to see the last backup"
                            else if (last.isNotEmpty()) "last backup " + relativeTime(last).ifEmpty { last.take(10) } else "no backup yet"
                    },
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    if (restore) "Tick the folders to bring back, then tap Restore options."
                    else "Tick the folders to back up now. Hold a folder to remove it from the list.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (!restore) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PillButton("Add folder", Modifier.weight(1f), strong = true, icon = Icons.Filled.Add, onClick = onAdd)
                    PillButton("Type a path", Modifier.weight(1f), onClick = onType)
                }
            }
        }
    }
}

/** One folder, drawn like an app card: icon, where it lives, its name, how it stands, and a tick box. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderCard(path: String, status: String, warn: Boolean, checked: Boolean, onLongClick: (() -> Unit)?, onToggle: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(
        color = cs.surfaceContainerLow,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 5.dp),
    ) {
        Row(
            Modifier
                .semantics(mergeDescendants = true) { stateDescription = if (checked) "Selected" else "Not selected" }
                .combinedClickable(onClick = onToggle, onLongClick = onLongClick, role = Role.Checkbox)
                .padding(start = 14.dp, top = 12.dp, end = 16.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            IconBadge(folderIcon(path), size = 44.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(
                    path.substringBeforeLast('/').ifEmpty { "/" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = cs.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    path.substringAfterLast('/').ifEmpty { path },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    status,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (warn) cs.error else cs.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            CheckSquare(checked)
        }
    }
}

/** The last questions before a folder backup, like the apps' "Backup options" sheet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FolderBackupSheet(m: AppModel, chosen: List<String>, onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    SheetFrame(onDismiss) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(if (chosen.size == 1) "Back up 1 folder" else "Back up ${chosen.size} folders", style = MaterialTheme.typography.titleLarge)
            Text("Only what changed since the last backup is sent.", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
        }
        Surface(shape = MaterialTheme.shapes.medium, color = cs.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                chosen.forEach {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(folderIcon(it), null, Modifier.size(20.dp), tint = cs.onSurfaceVariant)
                        Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        if (!m.ready) {
            Text("Set up and unlock the repository first.", style = MaterialTheme.typography.bodyMedium, color = cs.error)
        }
        PillButton("Start backup", Modifier.fillMaxWidth(), strong = true, enabled = !JobHub.busy && m.ready && chosen.isNotEmpty() && m.folderAccess) {
            onDismiss()
            m.startFolderBackup(chosen.toSet())
        }
    }
}

/** Where the ticked folders go: back to where they came from, or into any folder you pick. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FolderRestoreSheet(m: AppModel, chosen: List<BackedFolder>, onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    SheetFrame(onDismiss) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(if (chosen.size == 1) "Restore 1 folder" else "Restore ${chosen.size} folders", style = MaterialTheme.typography.titleLarge)
            Text("Each folder comes from its newest backup.", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
        }
        FolderRestorePanel(
            m,
            originals = chosen.map { it.path },
            enabled = !JobHub.busy && m.ready && chosen.isNotEmpty() && m.folderAccess,
            actionLabel = "Restore",
        ) { target, overwrite ->
            onDismiss()
            m.startFolderRestoreItems(chosen, target, overwrite)
        }
    }
}
