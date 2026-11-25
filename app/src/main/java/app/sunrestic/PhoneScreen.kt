package app.sunrestic

import android.app.role.RoleManager
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

fun phoneIcon(k: PhoneKind): ImageVector = when (k) {
    PhoneKind.CONTACTS -> Icons.Filled.Contacts
    PhoneKind.CALLS -> Icons.Filled.Call
    PhoneKind.SMS -> Icons.Filled.Sms
    PhoneKind.WALLPAPER -> Icons.Filled.Wallpaper
}

/** What the entries of this kind are called. */
fun phoneWord(k: PhoneKind, n: Int) = when (k) {
    PhoneKind.CONTACTS -> if (n == 1) "contact" else "contacts"
    PhoneKind.CALLS -> if (n == 1) "call" else "calls"
    PhoneKind.SMS -> if (n == 1) "message" else "messages"
    PhoneKind.WALLPAPER -> if (n == 1) "wallpaper" else "wallpapers"
}

/** The backups of one kind in the repository, newest first. */
fun phoneBackups(snaps: List<SnapInfo>, k: PhoneKind): List<SnapInfo> =
    snaps.filter { k.tag in it.tags }.sortedByDescending { it.time }

/** How many entries a phone data snapshot holds, from its "android-count-N" tag. */
fun snapshotCount(tags: List<String>): Int? =
    tags.firstOrNull { it.startsWith("android-count-") }?.removePrefix("android-count-")?.toIntOrNull()

/** Starts phone data jobs after the permissions (and, for messages, the default-app switch) are sorted out. */
class PhoneStarter(
    val backup: (List<PhoneKind>) -> Unit,
    val restore: (Map<PhoneKind, String>) -> Unit,
    /** Sorts out permissions and the default-app switch for [what], then runs [go] instead of the plain phone restore. */
    val restoreThen: (Map<PhoneKind, String>, () -> Unit) -> Unit,
)

/**
 * Asks for the permissions a job needs, and for restoring messages asks Android to make this app the default
 * messaging app, then starts the job. If Android refuses, a dialog explains what to allow.
 */
@Composable
fun rememberPhoneStarter(m: AppModel): PhoneStarter {
    val ctx = LocalContext.current
    var pendingBackup by remember { mutableStateOf<List<PhoneKind>?>(null) }
    var pendingRestore by remember { mutableStateOf<Map<PhoneKind, String>?>(null) }
    var askedPerm by remember { mutableStateOf(false) }
    var askedRole by remember { mutableStateOf(false) }
    var blocked by remember { mutableStateOf("") }
    var blockedFiles by remember { mutableStateOf(false) } // the block is about All files access, not about permissions
    var readyStep by remember { mutableStateOf<(() -> Unit)?>(null) }
    val stepRef = remember { mutableStateOf({}) }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { stepRef.value() }
    val roleLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { stepRef.value() }

    fun stop(msg: String, allFiles: Boolean = false) {
        readyStep = null
        blocked = msg
        blockedFiles = allFiles
        pendingBackup = null
        pendingRestore = null
    }

    fun step() {
        val b = pendingBackup
        val r = pendingRestore
        val kinds: Collection<PhoneKind> = b ?: r?.keys ?: return
        if (b != null && PhoneKind.WALLPAPER in b && !Storage.hasAllFiles()) {
            stop("Reading the wallpaper needs \"All files access\". Tap Open settings, switch it on for Sunrestic, then come back and try again.", allFiles = true)
            return
        }
        val missing = if (b != null) PhoneData.missingForBackup(ctx, kinds) else PhoneData.missingForRestore(ctx, kinds)
        if (missing.isNotEmpty()) {
            if (askedPerm) {
                stop("Android did not give this app the permission to " + (if (b != null) "read" else "read and write") + " " +
                    kinds.joinToString { it.label.lowercase() } + ".")
                return
            }
            askedPerm = true
            permLauncher.launch(missing.toTypedArray())
            return
        }
        if (r != null && PhoneKind.SMS in r.keys && !PhoneData.isDefaultSms(ctx)) {
            val rm = ctx.getSystemService(RoleManager::class.java)
            if (askedRole) {
                stop("The app was not set as the default messaging app, so Android does not let it write messages.")
                return
            }
            if (rm == null || !rm.isRoleAvailable(RoleManager.ROLE_SMS)) {
                stop("This phone does not let apps become the default messaging app.")
                return
            }
            askedRole = true
            roleLauncher.launch(rm.createRequestRoleIntent(RoleManager.ROLE_SMS))
            return
        }
        pendingBackup = null
        pendingRestore = null
        val go = readyStep
        readyStep = null
        if (go != null) go() else if (b != null) m.startPhoneBackup(b.toList()) else m.startPhoneRestore(r!!)
    }
    stepRef.value = { step() }

    if (blocked.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { blocked = "" },
            icon = { Icon(Icons.Filled.Warning, null) },
            title = { Text(if (blockedFiles) "All files access needed" else "Permission needed") },
            text = {
                if (blockedFiles) Text(blocked) else Text(
                    blocked + "\n\nOn Android 13 and newer, an app installed from outside the Play Store is not allowed to " +
                        "get the messages and call log permissions until you allow it: Settings → Apps → Sunrestic → " +
                        "⋮ (top right) → Allow restricted settings. Then try again.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val files = blockedFiles
                    blocked = ""
                    if (files) { Storage.openSettings(ctx); return@TextButton }
                    runCatching {
                        ctx.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                }) { Text(if (blockedFiles) "Open settings" else "Open app settings") }
            },
            dismissButton = { TextButton(onClick = { blocked = "" }) { Text("Close") } },
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        )
    }

    return remember {
        PhoneStarter(
            backup = { k -> askedPerm = false; askedRole = false; pendingRestore = null; readyStep = null; pendingBackup = k; stepRef.value() },
            restore = { w -> askedPerm = false; askedRole = false; pendingBackup = null; readyStep = null; pendingRestore = w; stepRef.value() },
            restoreThen = { w, go -> askedPerm = false; askedRole = false; pendingBackup = null; readyStep = go; pendingRestore = w; stepRef.value() },
        )
    }
}

/** What to say before restoring messages: this app must be the default messaging app for a moment. */
const val SMS_NOTICE = "To put messages back, Android requires this app to be your default messaging app for a moment. " +
    "Texts that arrive meanwhile are saved normally. When it is done, switch your usual messaging app back in " +
    "Settings → Apps → Default apps → SMS app."

/** One checkpoint to pick from. [id] "latest" means the newest. */
data class PickChoice(val id: String, val title: String, val detail: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SnapshotPicker(title: String, choices: List<PickChoice>, current: String, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    SheetFrame(onDismiss, gap = 8.dp, scrollable = false) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text("Pick the latest, or one backup to go back to.", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
        LazyColumn(Modifier.heightIn(max = 460.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(choices.size) { i ->
                val o = choices[i]
                PickRow(o.title, o.detail, o.id == current) { onPick(o.id); onDismiss() }
            }
        }
    }
}

/** The choices for one kind: Latest, then each backup with its date and number of entries. */
fun phoneChoices(snaps: List<SnapInfo>, k: PhoneKind): List<PickChoice> =
    listOf(PickChoice("latest", "Latest", "The newest ${k.label.lowercase()} backup")) +
        phoneBackups(snaps, k).map {
            val n = snapshotCount(it.tags)
            PickChoice(it.id, fmtSnapTime(it.time), (if (n != null) "$n ${phoneWord(k, n)}  ·  " else "") + it.id.take(8))
        }

/** Contacts, call log and messages, built like the Folders page: overview card, one card per kind, options sheet. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PhoneDataPage(m: AppModel) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val restore = m.phoneMode == 1
    val busy = JobHub.busy
    var sheet by remember { mutableStateOf(false) }
    var moreMenu by remember { mutableStateOf(false) }
    val starter = rememberPhoneStarter(m)

    LaunchedEffect(m.ready, m.phoneMode, JobHub.dataVersion) { if (m.ready) m.refreshSnapshotsIfStale() }
    val kinds = PhoneKind.values().toList()
    val sel = if (restore) m.phoneRestoreSel else m.phoneSel
    fun setSel(v: Set<PhoneKind>) { if (restore) m.phoneRestoreSel = v else m.phoneSel = v }
    val allSelected = kinds.all { it in sel }
    val latest = remember(m.snapList) { kinds.associateWith { phoneBackups(m.snapList, it).firstOrNull() } }

    if (sheet) {
        if (restore) PhoneRestoreSheet(m, sel.toList(), starter) { sheet = false }
        else PhoneBackupSheet(m, sel.toList(), starter) { sheet = false }
    }

    PageFrame(
        m,
        topBar = {
            SwiftTopBar(
                title = if (restore) "Restore phone data" else "Back up phone data",
                subtitle = "${sel.size}/${kinds.size} selected",
                onBack = { m.closePage() },
            ) {
                IconButton(onClick = { setSel(if (allSelected) emptySet() else kinds.toSet()) }) {
                    Icon(if (allSelected) Icons.Filled.CheckBox else Icons.Filled.CheckBoxOutlineBlank, "Select all", Modifier.size(22.dp))
                }
                Box {
                    IconButton(onClick = { moreMenu = true }) { Icon(Icons.Filled.MoreVert, "More", Modifier.size(22.dp)) }
                    DropdownMenu(expanded = moreMenu, onDismissRequest = { moreMenu = false }) {
                        DropdownMenuItem(
                            text = { Text(if (restore) "Back up instead" else "Restore instead") },
                            onClick = { m.phoneMode = if (restore) 0 else 1; moreMenu = false },
                        )
                        DropdownMenuItem(text = { Text("Reload list") }, enabled = m.ready && !busy, onClick = { m.retryConnection(); moreMenu = false })
                        DropdownMenuItem(text = { Text("Select none") }, onClick = { setSel(emptySet()); moreMenu = false })
                    }
                }
            }
        },
        fab = {
            if (sel.isNotEmpty()) BigFab(if (restore) "Restore options" else "Backup options", Icons.Filled.Edit, enabled = !busy) { sheet = true }
        },
    ) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 2.dp, bottom = 84.dp)) {
            item(key = "overview") {
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = cs.primaryContainer,
                    contentColor = cs.onPrimaryContainer,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 5.dp),
                ) {
                    Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(if (restore) "In the repository" else "On this phone", style = MaterialTheme.typography.labelLarge)
                        val newest = latest.values.filterNotNull().maxOfOrNull { it.time }.orEmpty()
                        Text(
                            if (restore) {
                                val have = latest.values.count { it != null }
                                (if (have == 1) "1 kind of data" else "$have kinds of data") +
                                    if (newest.isNotEmpty()) "  ·  newest backup " + relativeTime(newest).ifEmpty { newest.take(10) } else ""
                            } else {
                                "Contacts, call log, messages and wallpaper" + if (newest.isNotEmpty()) "  ·  last backup " + relativeTime(newest).ifEmpty { newest.take(10) } else ""
                            },
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            if (restore) "Tick what to bring back, then tap Restore options. Nothing is deleted: only what is missing is added."
                            else "Tick what to back up. Android asks for each permission the first time. No root needed.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
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
            items(kinds.size, key = { (if (restore) "r:" else "b:") + kinds[it].name }) { i ->
                val k = kinds[i]
                val snap = latest[k]
                val n = snap?.let { snapshotCount(it.tags) }
                val live = if (restore) null else remember(k, JobHub.dataVersion, m.foregroundTick) { PhoneData.liveCount(ctx, k) }
                val status = when {
                    restore -> when {
                        snap == null -> if (m.ready) "No backup yet" else "Unlock to see the backups"
                        else -> "Backed up " + relativeTime(snap.time).ifEmpty { "earlier" } + (if (n != null) " · $n ${phoneWord(k, n)}" else "")
                    }
                    else -> (if (live != null) "$live ${phoneWord(k, live)} on this phone" else if (k == PhoneKind.WALLPAPER) "Needs All files access (Back up folders page)" else "Permission needed, asked when you start") +
                        (if (snap != null) "\nLast backup " + relativeTime(snap.time).ifEmpty { "earlier" } else if (m.ready) "\nNo backup yet" else "")
                }
                PhoneCard(k, status, checked = k in sel) { setSel(if (k in sel) sel - k else sel + k) }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PhoneCard(kind: PhoneKind, status: String, checked: Boolean, onToggle: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(
        color = cs.surfaceContainerLow,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 5.dp),
    ) {
        Row(
            Modifier
                .semantics(mergeDescendants = true) { stateDescription = if (checked) "Selected" else "Not selected" }
                .combinedClickable(onClick = onToggle, role = Role.Checkbox)
                .padding(start = 14.dp, top = 12.dp, end = 16.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            IconBadge(phoneIcon(kind), size = 44.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(kind.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(status, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
            }
            CheckSquare(checked)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PhoneBackupSheet(m: AppModel, chosen: List<PhoneKind>, starter: PhoneStarter, onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    SheetFrame(onDismiss) {
        Text("Back up ${chosen.joinToString { it.label.lowercase() }}", style = MaterialTheme.typography.titleLarge)
        Text(
            "Each kind is saved as its own encrypted backup in the repository. Messages are text messages (SMS). " +
                "Picture messages (MMS) are not included yet.",
            style = MaterialTheme.typography.bodyMedium,
            color = cs.onSurfaceVariant,
        )
        if (!m.ready) Text("Set up and unlock the repository first.", style = MaterialTheme.typography.bodyMedium, color = cs.error)
        PillButton("Start backup", Modifier.fillMaxWidth(), strong = true, enabled = !JobHub.busy && m.ready && chosen.isNotEmpty()) {
            onDismiss()
            starter.backup(chosen)
        }
    }
}

/** Which backup each ticked kind comes from, then the restore. Messages ask for the default-app switch first. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PhoneRestoreSheet(m: AppModel, chosen: List<PhoneKind>, starter: PhoneStarter, onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val from = remember { mutableStateMapOf<PhoneKind, String>() }
    var picking by remember { mutableStateOf<PhoneKind?>(null) }
    val available = chosen.filter { phoneBackups(m.snapList, it).isNotEmpty() }
    picking?.let { k ->
        SnapshotPicker("Restore ${k.label.lowercase()} from…", phoneChoices(m.snapList, k), from[k] ?: "latest", { from[k] = it }) { picking = null }
    }
    SheetFrame(onDismiss) {
        Text("Restore ${chosen.joinToString { it.label.lowercase() }}", style = MaterialTheme.typography.titleLarge)
        Text(
            "Only what is missing is added. Entries the phone already has are skipped, and nothing is deleted.",
            style = MaterialTheme.typography.bodyMedium,
            color = cs.onSurfaceVariant,
        )
        chosen.forEach { k ->
            val has = phoneBackups(m.snapList, k).isNotEmpty()
            Surface(shape = MaterialTheme.shapes.medium, color = cs.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
                Row(
                    Modifier.padding(start = 14.dp, top = 10.dp, end = 8.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(phoneIcon(k), null, Modifier.size(22.dp))
                    Column(Modifier.weight(1f)) {
                        Text(k.label, style = MaterialTheme.typography.titleSmall)
                        Text(
                            if (!has) "No backup in the repository"
                            else "From: " + ((from[k] ?: "latest").let { id ->
                                if (id == "latest") "the latest backup" else phoneBackups(m.snapList, k).firstOrNull { it.id == id }?.let { fmtSnapTime(it.time) } ?: id.take(8)
                            }),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (has) cs.onSurfaceVariant else cs.error,
                        )
                    }
                    TextButton(onClick = { picking = k }, enabled = has) { Text("Change") }
                }
            }
        }
        if (PhoneKind.SMS in chosen) {
            NoticeCard(Icons.Filled.Warning, "Messages", SMS_NOTICE, container = cs.tertiaryContainer, content = cs.onTertiaryContainer)
        }
        PillButton("Restore", Modifier.fillMaxWidth(), strong = true, enabled = !JobHub.busy && m.ready && available.isNotEmpty()) {
            val what = available.associateWith { from[it] ?: "latest" }
            onDismiss()
            starter.restore(what)
        }
    }
}

/** Restores one kind from one given backup (used from the Snapshots tab). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhoneRestoreOneSheet(m: AppModel, kind: PhoneKind, snapId: String, starter: PhoneStarter, onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    SheetFrame(onDismiss) {
        Text("Restore ${kind.label.lowercase()}", style = MaterialTheme.typography.titleLarge)
        Text(
            "Only what is missing is added. Entries the phone already has are skipped, and nothing is deleted.",
            style = MaterialTheme.typography.bodyMedium,
            color = cs.onSurfaceVariant,
        )
        if (kind == PhoneKind.SMS) NoticeCard(Icons.Filled.Warning, "Messages", SMS_NOTICE, container = cs.tertiaryContainer, content = cs.onTertiaryContainer)
        PillButton("Restore", Modifier.fillMaxWidth(), strong = true, enabled = !JobHub.busy && m.ready) {
            onDismiss()
            starter.restore(mapOf(kind to snapId))
        }
    }
}

/** Shown after messages were restored: reminds the user to switch their usual messaging app back. */
@Composable
fun SmsResetNotice() {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    Column(Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        NoticeCard(
            Icons.Filled.Warning,
            "Switch your messaging app back",
            "This app is still the default messaging app. Choose your usual one again, or new texts will not show there.",
            container = cs.tertiaryContainer,
            content = cs.onTertiaryContainer,
        )
        PillButton("Open default apps", Modifier.fillMaxWidth(), strong = true) {
            runCatching {
                ctx.startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }
}
