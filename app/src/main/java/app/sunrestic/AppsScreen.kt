package app.sunrestic

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.LocalOffer
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

enum class AppFilter(val label: String) {
    ALL("All"), SELECTED("Selected"), BACKED("Backed up"), LAST("In last backup"), NOT_BACKED("No backup yet"), DIFFERS("Version differs"), MISSING("Not installed"),
}

/** Swift Backup's "BACKUP ALL APPS" page: round back button, counter, icon actions, app cards, floating options button. */
@Composable
fun AppsPage(m: AppModel) {
    LaunchedEffect(Unit) { if (m.apps.isEmpty()) m.loadApps() }
    // The repository's app list gives the "BACKUP" tags and "Last backup" lines, and is the restore list.
    // Read again after a backup finished or a snapshot was deleted, so those lines are never out of date.
    LaunchedEffect(m.ready, m.appsMode, JobHub.busy, JobHub.dataVersion) { m.refreshBackedIfStale() }

    val restore = m.appsMode == 1
    val all = if (restore) m.backed else m.apps
    val sel = if (restore) m.restoreSel else m.selected
    val loading = m.loadingBacked || (!restore && m.loadingApps)
    val busy = JobHub.busy
    var sheet by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(m.appQuery.isNotEmpty()) }
    var filterMenu by remember { mutableStateOf(false) }
    var moreMenu by remember { mutableStateOf(false) }
    var tagSheet by remember { mutableStateOf(false) }

    val filters = if (restore) {
        listOf(AppFilter.ALL, AppFilter.SELECTED, AppFilter.DIFFERS, AppFilter.MISSING)
    } else {
        listOf(AppFilter.ALL, AppFilter.SELECTED, AppFilter.BACKED, AppFilter.LAST, AppFilter.NOT_BACKED)
    }
    val filter = if (m.appFilter in filters) m.appFilter else AppFilter.ALL

    val backedMap = remember(m.backed) { m.backed.associateBy { it.pkg } }
    val selKey = if (filter == AppFilter.SELECTED) sel else null
    // Tags: which tags each app carries, how many listed apps each tag has, and the tag being filtered on.
    val tagNames = m.tagNames
    val pkgTags: Map<String, List<String>> = remember(m.appTags) {
        val out = HashMap<String, MutableList<String>>()
        for (t in tagNames) for (p in m.appTags[t].orEmpty()) out.getOrPut(p) { ArrayList() }.add(t)
        out
    }
    val tagCounts = remember(all, m.appTags) {
        m.appTags.mapValues { (_, members) -> all.count { it.pkg in members } }
    }
    val tagMembers = if (m.tagFilter.isEmpty()) null else m.appTags[m.tagFilter].orEmpty()
    // The apps saved by the most recent app backup (every app's newest copy lives in some snapshot; take the newest one).
    val lastApps = remember(m.backed) {
        val newest = m.backed.groupBy { it.snapshot }.maxByOrNull { (_, v) -> v.maxOf { it.backedUpAt } }
        newest?.value.orEmpty()
    }
    val lastPkgs = remember(lastApps) { lastApps.map { it.pkg }.toSet() }
    val shown = remember(all, m.appQuery, filter, selKey, backedMap, tagMembers, lastPkgs) {
        val q = m.appQuery.trim()
        all.filter { a ->
            val inst = a.installedVersion
            (tagMembers == null || a.pkg in tagMembers) &&
                (q.isEmpty() || a.label.contains(q, ignoreCase = true) || a.pkg.contains(q, ignoreCase = true)) &&
                when (filter) {
                    AppFilter.ALL -> true
                    AppFilter.SELECTED -> a.pkg in sel
                    AppFilter.BACKED -> a.pkg in backedMap
                    AppFilter.LAST -> a.pkg in lastPkgs
                    AppFilter.NOT_BACKED -> a.pkg !in backedMap
                    AppFilter.DIFFERS -> inst != null && a.version.isNotBlank() && inst.isNotBlank() && inst != a.version
                    AppFilter.MISSING -> inst == null
                }
        }
    }
    val allShownSelected = shown.isNotEmpty() && shown.all { it.pkg in sel }

    if (sheet) AppActionSheet(m, restore, sel) { sheet = false }
    if (tagSheet) TagSheet(m, sel) { tagSheet = false }
    m.historyPkg?.let { pkg -> AppHistorySheet(m, pkg, all.firstOrNull { it.pkg == pkg }?.label ?: pkg) { m.historyPkg = null } }

    PageFrame(
        m,
        topBar = {
            SwiftTopBar(
                title = if (restore) "Restore apps" else "Back up apps",
                subtitle = "${sel.size}/${all.size} selected",
                onBack = { m.closePage() },
            ) {
                IconButton(onClick = {
                    searching = !searching
                    if (!searching) m.appQuery = ""
                }) { Icon(Icons.Filled.Search, "Search", Modifier.size(22.dp)) }
                Box {
                    IconButton(onClick = { filterMenu = true }) {
                        Icon(
                            Icons.Filled.FilterList,
                            "Filter",
                            Modifier.size(22.dp),
                            tint = if (filter != AppFilter.ALL) MaterialTheme.colorScheme.primary else LocalContentColor.current,
                        )
                    }
                    DropdownMenu(expanded = filterMenu, onDismissRequest = { filterMenu = false }) {
                        filters.forEach { f ->
                            DropdownMenuItem(
                                text = { Text(f.label) },
                                onClick = {
                                    m.appFilter = f
                                    filterMenu = false
                                },
                                trailingIcon = if (f == filter) ({ Icon(Icons.Filled.CheckBox, null) }) else null,
                            )
                        }
                    }
                }
                IconButton(onClick = {
                    val keys = shown.map { it.pkg }
                    m.setAppSelection(restore, if (allShownSelected) sel - keys.toSet() else sel + keys)
                }) {
                    Icon(
                        if (allShownSelected) Icons.Filled.CheckBox else Icons.Filled.CheckBoxOutlineBlank,
                        "Select all",
                        Modifier.size(22.dp),
                    )
                }
                Box {
                    IconButton(onClick = { moreMenu = true }) { Icon(Icons.Filled.MoreVert, "More", Modifier.size(22.dp)) }
                    DropdownMenu(expanded = moreMenu, onDismissRequest = { moreMenu = false }) {
                        DropdownMenuItem(
                            text = { Text(if (restore) "Back up instead" else "Restore instead") },
                            onClick = {
                                m.appsMode = if (restore) 0 else 1
                                moreMenu = false
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Reload list") },
                            enabled = !loading && !busy,
                            onClick = {
                                if (restore) m.loadBacked() else m.loadApps()
                                moreMenu = false
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Tag selected apps") },
                            enabled = sel.isNotEmpty(),
                            onClick = {
                                tagSheet = true
                                moreMenu = false
                            },
                        )
                        if (!restore) {
                            val lastApps = m.autoApps.split(',').filter { it.isNotBlank() }.toSet()
                            DropdownMenuItem(
                                text = { Text("Select the apps of the last backup") },
                                enabled = lastApps.isNotEmpty(),
                                onClick = {
                                    m.setAppSelection(false, lastApps.intersect(all.map { it.pkg }.toSet()))
                                    moreMenu = false
                                },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("Select none") },
                            onClick = {
                                m.setAppSelection(restore, emptySet())
                                moreMenu = false
                            },
                        )
                    }
                }
            }
        },
        fab = {
            if (sel.isNotEmpty()) {
                BigFab(if (restore) "Restore options" else "Backup options", Icons.Filled.Edit, enabled = !busy) { sheet = true }
            }
        },
    ) {
        Column(Modifier.fillMaxSize()) {
            if (searching) SearchBox(m)

            if (!restore && m.backedProblem.isNotEmpty()) {
                NoticeCard(
                    icon = Icons.Filled.Warning,
                    title = "Some backups could not be read",
                    text = m.backedProblem + " Open the log for details.",
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
                )
            }

            if (restore && all.isNotEmpty()) {
                Text(
                    "Hold an app to see its versions and restore an older one.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 22.dp, vertical = 2.dp),
                )
            }

            // "Tags" opens the tag sheet; each tag chip shows only its apps (then "select all" ticks exactly those).
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ChoiceChip(
                    if (tagNames.isEmpty()) "Add tags" else "Tags",
                    selected = false,
                    icon = Icons.Filled.LocalOffer,
                ) { tagSheet = true }
                tagNames.forEach { t ->
                    val on = m.tagFilter == t
                    ChoiceChip("$t · ${tagCounts[t] ?: 0}", selected = on) { m.tagFilter = if (on) "" else t }
                }
                if (filter != AppFilter.ALL) {
                    ChoiceChip("${filter.label} · ${shown.size}", selected = true, icon = Icons.Filled.Close) {
                        m.appFilter = AppFilter.ALL
                    }
                }
            }

            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    all.isEmpty() && loading -> Column {
                        Text(
                            if (restore) "Reading backed-up apps from the repository…"
                            else if (m.loadTotal > 0) "Reading apps… ${m.loadDone}/${m.loadTotal}" else "Reading apps…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 22.dp, vertical = 6.dp),
                        )
                        SkeletonList(rows = 7)
                    }

                    all.isEmpty() -> EmptyState(
                        icon = if (restore) Icons.Filled.Restore else Icons.Filled.Android,
                        title = if (restore) "No backed-up apps loaded" else "No apps loaded",
                        text = if (restore) {
                            if (m.ready) "Backed-up apps are read from your repository." else "Set up and unlock the repository first."
                        } else "Tap below to read the apps installed on this phone.",
                        modifier = Modifier.align(Alignment.Center),
                        action = {
                            PillButton(
                                if (restore) "Load from repository" else "Load apps",
                                enabled = !busy && (!restore || m.ready),
                            ) { if (restore) m.loadBacked() else m.loadApps() }
                        },
                    )

                    shown.isEmpty() -> EmptyState(
                        icon = Icons.Filled.Search,
                        title = "No matches",
                        text = when {
                            m.appQuery.isNotBlank() -> "Nothing found for \"${m.appQuery.trim()}\"."
                            tagMembers != null && filter == AppFilter.ALL ->
                                "No app here carries \"${m.tagFilter}\" yet. Tick apps, then tap Tags to put it on them."
                            else -> "No app fits this filter."
                        },
                        modifier = Modifier.align(Alignment.Center),
                    )

                    else -> Column {
                        // A reload keeps the list on screen and only shows a thin bar.
                        if (loading) {
                            LinearProgressIndicator(
                                Modifier.fillMaxWidth().padding(horizontal = 14.dp).clip(CircleShape),
                                color = MaterialTheme.colorScheme.primary,
                                trackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            )
                        }
                        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 2.dp, bottom = 84.dp)) {
                            if (!restore && lastApps.isNotEmpty() && m.appQuery.isBlank() && filter != AppFilter.LAST) {
                                item(key = "last-backup") {
                                    LastBackupCard(
                                        apps = lastApps,
                                        onShow = { m.appFilter = AppFilter.LAST },
                                        onSelect = { m.setAppSelection(false, sel + lastPkgs.intersect(all.map { it.pkg }.toSet())) },
                                    )
                                }
                            }
                            items(shown, key = { it.pkg }) { a ->
                                val mine = pkgTags[a.pkg].orEmpty()
                                val info = if (restore) restoreInfo(a, mine) else backupInfo(a, backedMap[a.pkg], m.backedLoaded && !m.loadingBacked, mine)
                                val click = remember(a.pkg, restore) { { m.toggleApp(a.pkg, restore) } }
                                AppCard(
                                    label = a.label,
                                    pkg = a.pkg,
                                    status = info.status,
                                    warn = info.warn,
                                    tags = info.tags,
                                    checked = a.pkg in sel,
                                    onLongClick = if (m.ready) ({ m.historyPkg = a.pkg }) else null,
                                    onToggle = click,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Where the last backup's apps are shown: when it was made, which apps, and shortcuts to see or tick them. */
@Composable
private fun LastBackupCard(apps: List<AppInfo>, onShow: () -> Unit, onSelect: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val time = apps.maxOfOrNull { it.backedUpAt }.orEmpty()
    Surface(
        shape = MaterialTheme.shapes.large,
        color = cs.primaryContainer,
        contentColor = cs.onPrimaryContainer,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 5.dp),
    ) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Last backup", style = MaterialTheme.typography.labelLarge)
                Text(
                    "$time  ·  ${if (apps.size == 1) "1 app" else "${apps.size} apps"}",
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            IconStrip(apps)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton("Show them", Modifier.weight(1f), onClick = onShow)
                PillButton("Tick them", Modifier.weight(1f), strong = true, onClick = onSelect)
            }
        }
    }
}

private data class CardInfo(val status: String, val warn: Boolean, val tags: List<AppTag>)

/** The user's tags as card tags: the first two, then "+N" so the row never runs off the card. */
private fun userTags(mine: List<String>): List<AppTag> =
    mine.take(2).map { AppTag(it, AppTag.PURPLE) } +
        (if (mine.size > 2) listOf(AppTag("+${mine.size - 2}", AppTag.PURPLE)) else emptyList())

/** Back-up list: when it was last backed up (Swift's "No backup on device"), plus tags. */
private fun backupInfo(a: AppInfo, b: AppInfo?, backedKnown: Boolean, mine: List<String>): CardInfo {
    if (b == null) {
        return CardInfo(
            if (backedKnown) "No backup in repository" else if (a.version.isBlank()) "Installed" else "Version ${a.version}",
            false,
            userTags(mine),
        )
    }
    val tags = buildList {
        add(AppTag("Backed up", AppTag.BLUE))
        if (a.version.isNotBlank() && b.version.isNotBlank() && a.version != b.version) add(AppTag("Updated", AppTag.BROWN))
        addAll(userTags(mine))
    }
    return CardInfo("Last backup ${b.backedUpAt}", false, tags)
}

/** Restore list: when it was backed up, and whether it is installed / the same version. */
private fun restoreInfo(a: AppInfo, mine: List<String>): CardInfo {
    val installed = a.installedVersion
    val own = userTags(mine)
    return when {
        installed == null -> CardInfo("Backed up ${a.backedUpAt}", false, listOf(AppTag("Not installed", AppTag.RED)) + own)
        a.version.isNotBlank() && installed.isNotBlank() && installed != a.version ->
            CardInfo("Installed v$installed, backup v${a.version}", true, listOf(AppTag("Version differs", AppTag.BROWN)) + own)
        else -> CardInfo("Backed up ${a.backedUpAt}", false, own)
    }
}

/**
 * Tags: make one, put it on the ticked apps (or take it off), delete it.
 * A schedule can then back up only the apps of the tags you pick there.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TagSheet(m: AppModel, sel: Set<String>, onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    var name by remember { mutableStateOf("") }
    var renaming by remember { mutableStateOf<String?>(null) }
    var renameText by remember { mutableStateOf("") }
    val names = m.tagNames

    renaming?.let { old ->
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Rename tag") },
            text = { Field("Name", renameText) { renameText = it } },
            confirmButton = {
                TextButton(enabled = renameText.isNotBlank(), onClick = {
                    m.renameTag(old, renameText)
                    renaming = null
                }) { Text("Rename") }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } },
            containerColor = cs.surfaceContainerLow,
        )
    }

    fun add() {
        val t = m.addTag(name) ?: return
        if (sel.isNotEmpty()) m.setTag(t, sel, true)
        name = ""
    }

    SheetFrame(onDismiss) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Tags", style = MaterialTheme.typography.titleLarge)
            Text(
                when {
                    sel.isEmpty() -> "Tick apps in the list first, then come back here to tag them. " +
                        "A schedule can back up only the apps of the tags you choose."
                    sel.size == 1 -> "Tap a tag to put it on the selected app, or take it off."
                    else -> "Tap a tag to put it on the ${sel.size} selected apps, or take it off."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = cs.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    when {
                        !m.ready -> "Unlock the repository to keep tags in it."
                        m.tagSync == "syncing" -> "Syncing with the repository…"
                        m.tagSync == "failed" -> "Could not reach the repository. Tags stay on this phone and are retried."
                        m.tagsDirty -> "Waiting to be saved to the repository…"
                        m.tagSync == "synced" -> "Saved in the repository. A new phone gets these tags back."
                        else -> ""
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (m.tagSync == "failed") cs.error else cs.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (m.ready && m.tagSync != "syncing") TextButton(onClick = { m.syncTagsNow() }) { Text("Sync now") }
            }
        }

        if (names.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                names.forEachIndexed { i, t ->
                    val members = m.appTags[t].orEmpty()
                    val have = sel.count { it in members }
                    val all = sel.isNotEmpty() && have == sel.size
                    Surface(shape = groupShape(i, names.size), color = cs.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable(enabled = sel.isNotEmpty()) { m.setTag(t, sel, !all) }
                                .padding(start = 16.dp, top = 6.dp, end = 4.dp, bottom = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            if (sel.isNotEmpty()) CheckSquare(all)
                            Column(Modifier.weight(1f)) {
                                Text(t, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    (if (members.size == 1) "1 app" else "${members.size} apps") +
                                        if (have in 1 until sel.size) " · on $have of the ${sel.size} selected" else "",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = cs.onSurfaceVariant,
                                )
                            }
                            IconButton(onClick = {
                                renameText = t
                                renaming = t
                            }) { Icon(Icons.Filled.Edit, "Rename tag $t", Modifier.size(20.dp), tint = cs.onSurfaceVariant) }
                            IconButton(onClick = {
                                m.confirm = "Delete the tag \"$t\"? The apps and their backups are not touched." to { m.deleteTag(t) }
                            }) { Icon(Icons.Filled.Delete, "Delete tag $t", Modifier.size(20.dp), tint = cs.onSurfaceVariant) }
                        }
                    }
                }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Field(
                "New tag",
                name,
                Modifier.weight(1f),
                placeholder = "Daily, Games, Banking…",
                icon = Icons.Filled.LocalOffer,
            ) { name = it }
            PillButton(if (sel.isEmpty()) "Add" else "Add & tag", strong = true, enabled = name.isNotBlank()) { add() }
        }
    }
}

@Composable
private fun SearchBox(m: AppModel) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    TextField(
        value = m.appQuery,
        onValueChange = { m.appQuery = it },
        placeholder = { Text("Search apps") },
        leadingIcon = { Icon(Icons.Filled.Search, null) },
        trailingIcon = if (m.appQuery.isNotEmpty()) ({
            IconButton(onClick = { m.appQuery = "" }) { Icon(Icons.Filled.Close, "Clear") }
        }) else null,
        singleLine = true,
        shape = CircleShape,
        colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
            errorIndicatorColor = Color.Transparent,
        ),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp).focusRequester(focus),
    )
}

/** All saved versions of one app: when, which version, what was saved, and a restore button for each. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppHistorySheet(m: AppModel, pkg: String, label: String, onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    LaunchedEffect(pkg, m.ready) { m.refreshSnapshotsIfStale() }
    val versions = m.historyOf(pkg)
    val reading = m.loadingSnaps || m.loadingSnapApps
    SheetFrame(onDismiss) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            AppIcon(pkg, label, 44.dp)
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.titleLarge, maxLines = 1)
                Text(pkg, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, maxLines = 1)
            }
        }
        ComponentPicker(m)
        CardLabel(if (versions.size == 1) "1 saved version" else "${versions.size} saved versions")
        if (versions.isEmpty()) {
            Text(
                if (reading) "Reading the snapshots…" else "No snapshot holds this app yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = cs.onSurfaceVariant,
            )
        }
        versions.forEachIndexed { i, (snap, info) ->
            val older = versions.getOrNull(i + 1)?.second
            val changed = older != null && older.version.isNotBlank() && info.version.isNotBlank() && older.version != info.version
            Surface(shape = MaterialTheme.shapes.medium, color = cs.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
                Row(
                    Modifier.padding(start = 14.dp, top = 10.dp, end = 8.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(fmtSnapTime(snap.time), style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Version ${info.version.ifBlank { "?" }}" +
                                (if (i == 0) " · newest" else "") +
                                (if (changed) " · updated from ${older!!.version}" else ""),
                            style = MaterialTheme.typography.bodyMedium,
                            color = cs.onSurfaceVariant,
                        )
                        val parts = info.components.mapNotNull { n -> Component.values().firstOrNull { it.name == n }?.longLabel() }
                        if (parts.isNotEmpty()) {
                            Text(parts.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                        }
                    }
                    TextButton(
                        enabled = !JobHub.busy && m.ready && m.comps.isNotEmpty(),
                        onClick = {
                            onDismiss()
                            m.restoreVersion(snap.id, pkg)
                        },
                    ) { Text("Restore") }
                }
            }
        }
    }
}

/** Which parts of each app are included. Shared by the back-up, restore and snapshot sheets. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ComponentPicker(m: AppModel) {
    val comps = m.comps
    val cs = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CardLabel("What to include")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Component.values().forEach { comp ->
                FilterChip(
                    selected = comp in comps,
                    onClick = { m.comps = if (comp in comps) comps - comp else comps + comp },
                    label = { Text(comp.longLabel()) },
                    shape = CircleShape,
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = cs.primaryContainer,
                        selectedLabelColor = cs.onPrimaryContainer,
                        selectedLeadingIconColor = cs.onPrimaryContainer,
                        containerColor = cs.surfaceContainerHigh,
                    ),
                    border = null,
                )
            }
        }
    }
}

/**
 * Tag every selected app at once: tap a tag to put it on all of them (tap again to take it off),
 * or type a new tag and it is created and put on the whole selection.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagPicker(m: AppModel, sel: Set<String>) {
    val cs = MaterialTheme.colorScheme
    var name by remember { mutableStateOf("") }
    val names = m.tagNames
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CardLabel(if (sel.size == 1) "Tag this app" else "Tag ${sel.size} selected apps")
        if (names.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                names.forEach { t ->
                    val members = m.appTags[t].orEmpty()
                    val have = sel.count { it in members }
                    val all = have == sel.size
                    FilterChip(
                        selected = all,
                        onClick = { m.setTag(t, sel, !all) },
                        label = { Text(if (have in 1 until sel.size) "$t · $have/${sel.size}" else t) },
                        leadingIcon = { Icon(Icons.Filled.LocalOffer, null, Modifier.size(16.dp)) },
                        shape = CircleShape,
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = cs.primaryContainer,
                            selectedLabelColor = cs.onPrimaryContainer,
                            selectedLeadingIconColor = cs.onPrimaryContainer,
                            containerColor = cs.surfaceContainerHigh,
                        ),
                        border = null,
                    )
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Field("New tag", name, Modifier.weight(1f), placeholder = "Daily, Games, Banking…", icon = Icons.Filled.LocalOffer) { name = it }
            PillButton("Add & tag", enabled = name.isNotBlank()) {
                val t = m.addTag(name)
                if (t != null) {
                    m.setTag(t, sel, true)
                    name = ""
                }
            }
        }
    }
}

/** The options that used to crowd the screen: components, force-stop, snapshot ID. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppActionSheet(m: AppModel, restore: Boolean, sel: Set<String>, onDismiss: () -> Unit) {
    val count = sel.size
    SheetFrame(onDismiss) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                if (restore) "Restore $count apps" else "Back up $count apps",
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                "Choose what is included for each app",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (m.useRoot) {
            ComponentPicker(m)
        } else {
            NoticeCard(
                icon = Icons.Filled.Warning,
                title = if (restore && BuildConfig.PLAY) "Restoring apps needs root" else "Without root: APKs only",
                text = if (restore && BuildConfig.PLAY) "In this version, apps can only be restored with root. Turn on Run as root in the Repo tab, Options."
                else if (restore) "The apps are reinstalled through Android's installer and you confirm each one. App data is not restored."
                else "Only the app files (APKs) are saved. App data needs root. Turn on Run as root in Repository, Options.",
                container = MaterialTheme.colorScheme.surfaceContainerHigh,
                content = MaterialTheme.colorScheme.onSurface,
            )
        }

        if (!restore && m.useRoot) {
            LaunchedEffect(sel, m.comps) { m.estimateSelected(sel, m.comps) }
            Text(
                m.estimateBytes?.let { "About ${fmtBytes(it)} to read. Less is uploaded: restic skips what the repository already has." }
                    ?: "Measuring size…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        TagPicker(m, sel)

        if (restore) {
            Field("Snapshot ID (empty = newest that has the app)", m.snapOverride) { m.snapOverride = it }
            NoticeCard(
                icon = Icons.Filled.Warning,
                title = "Your current data is kept as a safety copy",
                text = "The backup is read in full first. Then the app’s current data is moved aside and the backup takes its place. If anything goes wrong, your current data is put back.",
            )
        } else {
            SwitchRow(
                title = "Force-stop apps first",
                subtitle = "Prevents files changing during the backup",
                checked = m.forceStop,
                onChange = { m.forceStop = it },
            )
        }

        if (!m.ready) {
            Text(
                "Set up and unlock the repository first.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }

        PillButton(
            if (restore) "Restore" else "Start backup",
            Modifier.fillMaxWidth(),
            strong = true,
            enabled = !JobHub.busy && m.ready && (m.comps.isNotEmpty() || !m.useRoot) && !(BuildConfig.PLAY && restore && !m.useRoot),
        ) {
            onDismiss()
            if (restore) m.startRestoreApps() else m.startAppBackup()
        }
    }
}
