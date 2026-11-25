package app.sunrestic

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * Where each saved folder goes when it is restored into another folder: its own subfolder named after it, so several
 * folders (or two folders with the same name) are never mixed together. [paths] are the original absolute paths.
 */
internal fun restoreDestinations(target: String, paths: List<String>): List<Pair<String, String>> {
    val base = target.trimEnd('/')
    val used = HashMap<String, Int>()
    return paths.map { p ->
        val name = p.trimEnd('/').substringAfterLast('/').ifEmpty { "root" }
        val n = (used[name] ?: 0) + 1
        used[name] = n
        p to base + "/" + (if (n == 1) name else "$name-$n")
    }
}

/** A folder that exists in the repository: where it was saved from, and the newest backup that holds it. */
data class BackedFolder(val path: String, val snapshotId: String, val time: String)

/** Every folder found in the folders snapshots, each with its newest backup, sorted by path. */
fun backedFolders(snaps: List<SnapInfo>): List<BackedFolder> {
    val seen = LinkedHashMap<String, BackedFolder>()
    for (s in snaps.filter { "android-folders" in it.tags }.sortedByDescending { it.time }) {
        for (p in s.pathList) seen.getOrPut(p) { BackedFolder(p, s.id, s.time) }
    }
    return seen.values.sortedBy { it.path.lowercase() }
}

/** How restic treats a file that is already there when the backup has the same file. */
private val OVERWRITE = listOf(
    "if-changed" to "Replace files that differ",
    "if-newer" to "Keep newer files",
    "never" to "Never overwrite",
)

/** What the user chose for where folders go. Kept apart from the screen, so several screens can share the choice. */
class PlacementState(initialCustom: String) {
    var original by mutableStateOf(true) // the default is always the original place
    var overwrite by mutableStateOf("if-changed")
    var custom by mutableStateOf(initialCustom)

    /** The restic target: "/" means each folder goes back to its own place. */
    fun target(useRoot: Boolean): String = if (original && useRoot) "/" else custom.trim()
    fun rule(useRoot: Boolean): String = if (original && useRoot) overwrite else "if-changed"
    fun complete(useRoot: Boolean): Boolean = (original && useRoot) || custom.isNotBlank()
    fun overwriteText(): String = OVERWRITE.first { it.first == overwrite }.second
}

/** The two ways to place folders, with the overwrite rule or the target folder. No button: the caller adds its own. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FolderPlacementChoice(m: AppModel, originals: List<String>, st: PlacementState) {
    val ctx = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            val path = treeUriToPath(uri)
            if (path != null) st.custom = path
            else Toast.makeText(ctx, "Can't map that folder to a path. Type it instead.", Toast.LENGTH_LONG).show()
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ChoiceCard(
            icon = Icons.Filled.Restore,
            title = "Back to where it came from",
            subtitle = (if (originals.isEmpty()) "The folders this backup was saved from" else originals.joinToString("\n")) +
                if (!m.folderAccess) "\nNeeds root, or All files access (allow it on the Back up folders page)." else "",
            selected = st.original && m.folderAccess,
            enabled = m.folderAccess,
        ) { st.original = true }
        ChoiceCard(
            icon = Icons.Filled.FolderOpen,
            title = "Into another folder",
            subtitle = "Each saved folder goes into its own subfolder there. Nothing gets mixed.",
            selected = !st.original || !m.folderAccess,
        ) { st.original = false }

        if (st.original && m.folderAccess) {
            CardLabel("If a file is already there")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OVERWRITE.forEach { (v, label) -> ChoiceChip(label, selected = st.overwrite == v) { st.overwrite = v } }
            }
        } else {
            Field(
                "Folder to restore into",
                st.custom,
                placeholder = "Choose or type a folder",
                icon = Icons.Filled.Folder,
                trailing = { IconButton(onClick = { picker.launch(null) }) { Icon(Icons.Filled.FolderOpen, "Choose folder") } },
            ) { st.custom = it }
        }
    }
}

/**
 * Restoring folders: by default back to the place they were saved from; optionally into any other folder.
 * [originals] are the paths the snapshot was made from (empty = not known yet).
 */
@Composable
fun FolderRestorePanel(
    m: AppModel,
    originals: List<String>,
    enabled: Boolean,
    actionLabel: String,
    onGo: (target: String, overwrite: String) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val st = remember { PlacementState(m.restoreTarget) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        FolderPlacementChoice(m, originals, st)
        PillButton(actionLabel, Modifier.fillMaxWidth(), strong = true, enabled = enabled && st.complete(m.folderAccess)) {
            if (st.original && m.folderAccess) {
                val what = if (originals.isEmpty()) "the folders this backup was saved from" else originals.joinToString("\n")
                m.confirm = "Restore into the original place?\n\n$what\n\nFiles that are in the backup are put back there. Rule: ${st.overwriteText()}." to {
                    onGo("/", st.overwrite)
                }
            } else {
                m.restoreTarget = st.custom.trim()
                onGo(st.custom.trim(), "if-changed")
            }
        }
        Text(
            if (st.original && m.folderAccess) "Files that are not in the backup are left alone." else "Nothing outside that folder is touched.",
            style = MaterialTheme.typography.bodySmall,
            color = cs.onSurfaceVariant,
        )
    }
}

