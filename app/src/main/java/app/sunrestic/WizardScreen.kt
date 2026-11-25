package app.sunrestic

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.LocalOffer
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp

/**
 * "Restore a phone": connect to the repository, get the tags back, then pick what to restore.
 * Each step shows where you are, so you can leave and come back.
 */
@Composable
fun RestoreWizardPage(m: AppModel) {
    val cs = MaterialTheme.colorScheme
    val x = LocalExtra.current
    val activity = androidx.compose.ui.platform.LocalContext.current.findActivity()
    LaunchedEffect(m.ready) {
        if (m.ready) {
            m.syncTagsIfNeeded()
            m.refreshBackedIfStale()
            m.refreshSnapshotsIfStale()
        }
    }
    val backedFolderList = androidx.compose.runtime.remember(m.snapList) { backedFolders(m.snapList) }
    val connected = m.ready
    val installedOnly = m.backed
    val notInstalled = installedOnly.filter { it.installedVersion == null }.map { it.pkg }.toSet()
    val tagsWithApps = m.tagNames.map { t -> t to installedOnly.filter { it.pkg in m.appTags[t].orEmpty() }.map { it.pkg }.toSet() }
        .filter { it.second.isNotEmpty() }

    PageFrame(
        m,
        topBar = {
            SwiftTopBar(
                title = "Restore a phone",
                subtitle = "Get your apps and folders back",
                onBack = { m.closePage() },
            )
        },
    ) {
        ScreenScroll {
            // 1
            Step(
                number = 1,
                title = "Connect to your repository",
                done = connected,
                text = when {
                    !m.repoConfigured -> "Enter where your backups are stored (server, bucket or folder) and your repository password."
                    m.password.isEmpty() -> "Unlock the repository with your fingerprint or password."
                    else -> "Connected to ${m.repoSummary}"
                },
            ) {
                if (!m.repoConfigured || (m.password.isEmpty() && !m.vaultSaved)) {
                    PillButton("Open repository settings", Modifier.fillMaxWidth(), strong = true, icon = Icons.Filled.Cloud) {
                        m.closePage()
                        m.screen = Screen.REPO
                    }
                } else if (m.password.isEmpty() && activity != null) {
                    PillButton("Unlock", Modifier.fillMaxWidth(), strong = true, icon = Icons.Filled.Lock) { m.unlockPassword(activity) }
                }
            }

            // 2
            Step(
                number = 2,
                title = "Bring your tags back",
                done = connected && m.tagSync == "synced",
                enabled = connected,
                text = when {
                    !connected -> "Waiting for step 1."
                    m.tagSync == "syncing" -> "Reading your tags from the repository…"
                    m.tagSync == "failed" -> "Could not read the tags. Try again."
                    m.tagNames.isEmpty() -> "This repository has no tags yet. You can still restore every backed-up app."
                    else -> "${m.tagNames.size} tags are back on this phone."
                },
            ) {
                if (connected && m.tagSync == "failed") PillButton("Try again", Modifier.fillMaxWidth()) { m.syncTagsNow() }
            }

            // 3
            Step(
                number = 3,
                title = "Restore your apps",
                done = false,
                enabled = connected,
                text = when {
                    !connected -> "Waiting for step 1."
                    m.loadingBacked && installedOnly.isEmpty() -> "Reading the backed-up apps…"
                    installedOnly.isEmpty() -> "No backed-up apps found in this repository."
                    else -> "${installedOnly.size} apps are backed up. Pick a group, then check the list and restore."
                },
            ) {
                if (connected && installedOnly.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (notInstalled.isNotEmpty()) {
                            ChoiceRow(Icons.Filled.PhoneAndroid, "Apps missing on this phone", "${notInstalled.size} apps") {
                                m.openRestoreWith(notInstalled)
                            }
                        }
                        tagsWithApps.forEach { (t, pkgs) ->
                            ChoiceRow(Icons.Filled.LocalOffer, t, "${pkgs.size} apps") { m.openRestoreWith(pkgs) }
                        }
                        ChoiceRow(Icons.Filled.Android, "Everything", "${installedOnly.size} apps") {
                            m.openRestoreWith(installedOnly.map { it.pkg }.toSet())
                        }
                    }
                }
            }

            // 4
            Step(
                number = 4,
                title = "Restore your folders",
                done = false,
                enabled = connected,
                text = when {
                    !connected -> "Waiting for step 1."
                    m.loadingSnaps && backedFolderList.isEmpty() -> "Reading the backed-up folders…"
                    m.connectionProblem.isNotEmpty() && backedFolderList.isEmpty() -> m.connectionProblem
                    backedFolderList.isEmpty() -> "No folders backed up in this repository."
                    else -> "${backedFolderList.size} folders are backed up. You choose where they go: back where they came from, or into another folder."
                },
            ) {
                if (connected && backedFolderList.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ChoiceRow(Icons.Filled.Folder, "All folders", "${backedFolderList.size} folders") {
                            m.openFolderRestoreWith(backedFolderList.map { it.path }.toSet())
                        }
                        ChoiceRow(Icons.Filled.FolderOpen, "Let me choose", "Pick the folders one by one") {
                            m.openFolderRestoreWith(emptySet())
                        }
                    }
                }
            }

            // 5
            RestoreEverythingStep(m, connected, backedFolderList)
        }
    }
}

@Composable
private fun Step(
    number: Int,
    title: String,
    done: Boolean,
    text: String,
    enabled: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Surface(
        shape = MaterialTheme.shapes.large,
        color = cs.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().alpha(if (enabled) 1f else 0.55f),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (done) {
                    IconBadge(Icons.Filled.CheckCircle, size = 32.dp)
                } else {
                    Surface(shape = androidx.compose.foundation.shape.CircleShape, color = cs.surfaceContainerHigh, modifier = Modifier.size(32.dp)) {
                        Box(contentAlignment = Alignment.Center) { Text("$number", style = MaterialTheme.typography.titleMedium) }
                    }
                }
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (enabled) cs.onSurface else cs.onSurfaceVariant,
                )
            }
            Text(text, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
            content()
        }
    }
}

@Composable
private fun ChoiceRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, sub: String, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(onClick = onClick, shape = MaterialTheme.shapes.medium, color = cs.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        ItemRow(
            title = title,
            subtitle = sub,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            leading = { IconBadge(icon, size = 36.dp) },
        )
    }
}

/** One checkpoint to restore from, shown in the picker. */
private data class PickOption(val id: String?, val title: String, val detail: String)

/**
 * The last step: restore apps and folders together. For each kind you choose the latest backups, or one backup
 * from a list (a checkpoint you want to go back to), then everything chosen is restored in one go.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun RestoreEverythingStep(m: AppModel, connected: Boolean, latestFolders: List<BackedFolder>) {
    val cs = MaterialTheme.colorScheme
    val self = androidx.compose.ui.platform.LocalContext.current.packageName
    var includeApps by remember { mutableStateOf(true) }
    var includeFolders by remember { mutableStateOf(true) }
    var appsFrom by remember { mutableStateOf<String?>(null) }    // null = the latest backup of each app
    var foldersFrom by remember { mutableStateOf<String?>(null) } // null = the latest backup of each folder
    var picking by remember { mutableStateOf("") }                // "apps" or "folders" while the list is open
    val place = remember { PlacementState(m.restoreTarget) }
    val starter = rememberPhoneStarter(m)
    val includePhone = remember { mutableStateMapOf<PhoneKind, Boolean>() }
    val phoneFrom = remember { mutableStateMapOf<PhoneKind, String>() }
    var pickingPhone by remember { mutableStateOf<PhoneKind?>(null) }
    val phoneKinds = PhoneKind.values().filter { phoneBackups(m.snapList, it).isNotEmpty() }
    val phoneWhat: Map<PhoneKind, String> = phoneKinds.filter { includePhone[it] != false }.associateWith { phoneFrom[it] ?: "latest" }

    val appSnaps = m.snapList.filter { "android-apps" in it.tags }
    val folderSnaps = m.snapList.filter { "android-folders" in it.tags }

    val af = appsFrom
    val ff = foldersFrom
    val apps: List<AppInfo> = if (af == null) m.backed
    else m.snapApps[af].orEmpty().filter { it.pkg != self }.map { it.copy(snapshot = af) }
    val folderItems: List<BackedFolder> = if (ff == null) latestFolders
    else folderSnaps.firstOrNull { it.id == ff }?.let { sn -> sn.pathList.map { BackedFolder(it, sn.id, sn.time) } }.orEmpty()

    fun whenOf(id: String?, list: List<SnapInfo>) =
        if (id == null) "the latest backups" else list.firstOrNull { it.id == id }?.let { fmtSnapTime(it.time) } ?: id.take(8)

    val canRestoreApps = m.useRoot || !BuildConfig.PLAY // the Play build can only restore apps with root
    val useApps = canRestoreApps && includeApps && apps.isNotEmpty()
    val useFolders = includeFolders && folderItems.isNotEmpty()
    val usePhone = phoneWhat.isNotEmpty()
    val anything = useApps || useFolders || usePhone

    pickingPhone?.let { k ->
        SnapshotPicker("Restore ${k.label.lowercase()} from…", phoneChoices(m.snapList, k), phoneFrom[k] ?: "latest", { phoneFrom[k] = it }) { pickingPhone = null }
    }

    if (picking.isNotEmpty()) {
        val isApps = picking == "apps"
        val options = buildList {
            add(PickOption(null, "Latest", if (isApps) "The newest backup of each app" else "The newest backup of each folder"))
            (if (isApps) appSnaps else folderSnaps).forEach { s ->
                val tags = s.tags.filter { !it.startsWith("android-") }.joinToString().let { if (it.isEmpty()) "" else "  ·  $it" }
                val count = if (isApps) (m.snapApps[s.id]?.size?.let { "$it apps" } ?: "reading…") else "${s.pathList.size} folders"
                add(PickOption(s.id, fmtSnapTime(s.time), "$count$tags  ·  ${s.id.take(8)}"))
            }
        }
        val current = if (isApps) appsFrom else foldersFrom
        @OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
        SheetFrame({ picking = "" }, gap = 8.dp, scrollable = false) {
            Text(if (isApps) "Restore apps from…" else "Restore folders from…", style = MaterialTheme.typography.titleLarge)
            Text("Pick the latest, or one backup to go back to.", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
            androidx.compose.foundation.lazy.LazyColumn(Modifier.heightIn(max = 460.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(options.size) { i ->
                    val o = options[i]
                    PickRow(o.title, o.detail, o.id == current) {
                        if (isApps) appsFrom = o.id else foldersFrom = o.id
                        picking = ""
                    }
                }
            }
        }
    }

    Step(
        number = 5,
        title = "Restore everything",
        done = false,
        enabled = connected,
        text = if (!connected) "Waiting for step 1."
        else "Restore your apps and folders together: from the latest backups, or from one backup you choose to go back to.",
    ) {
        if (!connected) return@Step
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // apps
            if (!canRestoreApps) {
                Text(
                    "Apps: restoring them needs root in this version.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = cs.onSurfaceVariant,
                )
            } else SwitchRow(
                title = "Apps",
                subtitle = if (apps.isEmpty()) (if (af != null && m.snapApps[af] == null) "Reading that backup…" else "None to restore")
                else "${apps.size} apps from ${whenOf(appsFrom, appSnaps)}",
                checked = includeApps,
                icon = Icons.Filled.Android,
                onChange = { includeApps = it },
            )
            if (includeApps && canRestoreApps) {
                PillButton("From: " + whenOf(appsFrom, appSnaps) + "  (change)", Modifier.fillMaxWidth(), enabled = appSnaps.isNotEmpty()) { picking = "apps" }
                if (m.useRoot) ComponentPicker(m)
            }
            HorizontalDivider(color = cs.outlineVariant)
            // folders
            SwitchRow(
                title = "Folders",
                subtitle = if (folderItems.isEmpty()) "None to restore" else "${folderItems.size} folders from ${whenOf(foldersFrom, folderSnaps)}",
                checked = includeFolders,
                icon = Icons.Filled.Folder,
                onChange = { includeFolders = it },
            )
            if (includeFolders) {
                PillButton("From: " + whenOf(foldersFrom, folderSnaps) + "  (change)", Modifier.fillMaxWidth(), enabled = folderSnaps.isNotEmpty()) { picking = "folders" }
                FolderPlacementChoice(m, folderItems.map { it.path }, place)
            }
            HorizontalDivider(color = cs.outlineVariant)
            // contacts, calls, messages
            Text("Contacts, calls & messages", style = MaterialTheme.typography.titleSmall)
            if (phoneKinds.isEmpty()) {
                Text("None backed up in this repository.", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
            }
            phoneKinds.forEach { k ->
                val on = includePhone[k] != false
                SwitchRow(
                    title = k.label,
                    subtitle = "From " + (phoneFrom[k] ?: "latest").let { id ->
                        if (id == "latest") "the latest backup" else phoneBackups(m.snapList, k).firstOrNull { it.id == id }?.let { fmtSnapTime(it.time) } ?: id.take(8)
                    },
                    checked = on,
                    icon = phoneIcon(k),
                    onChange = { includePhone[k] = it },
                )
                if (on) PillButton("Choose another backup", Modifier.fillMaxWidth()) { pickingPhone = k }
            }
            if (PhoneKind.SMS in phoneWhat) {
                NoticeCard(Icons.Filled.Warning, "Messages", SMS_NOTICE, container = cs.tertiaryContainer, content = cs.onTertiaryContainer)
            }
            PillButton(
                "Restore everything",
                Modifier.fillMaxWidth(),
                strong = true,
                enabled = anything && !JobHub.busy && m.ready && (!useFolders || (m.folderAccess && place.complete(m.folderAccess))) && (!useApps || m.comps.isNotEmpty() || !m.useRoot),
            ) {
                val target = place.target(m.folderAccess)
                val where = if (target == "/") "back where they came from (${place.overwriteText()})" else "into $target"
                m.confirm = buildString {
                    append("Restore everything selected?\n")
                    if (useApps) append("\nApps: ${apps.size} from ${whenOf(appsFrom, appSnaps)}. The data each app has now is kept as a safety copy first.")
                    if (useFolders) append("\nFolders: ${folderItems.size} from ${whenOf(foldersFrom, folderSnaps)}, $where.")
                    if (usePhone) append("\nContacts, calls & messages: ${phoneWhat.keys.joinToString { it.label.lowercase() }}. Only what is missing is added.")
                    append("\n\nFolders are restored first, then apps, then contacts, calls and messages.")
                } to {
                    val go = {
                        m.restoreEverything(
                            if (useApps) apps else emptyList(),
                            if (useFolders) folderItems else emptyList(),
                            target,
                            place.rule(m.folderAccess),
                            phoneWhat,
                        )
                    }
                    // Permissions (and, for messages, the default-app switch) have to be sorted out before the first job starts.
                    if (usePhone) starter.restoreThen(phoneWhat, go) else go()
                }
            }
        }
    }
}
