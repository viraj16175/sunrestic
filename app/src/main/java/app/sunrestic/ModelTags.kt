package app.sunrestic

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import kotlin.concurrent.thread

/** Tags: names, the app lists they hold, sync with the repository and renaming on snapshots. These are extensions of [AppModel], which holds the state. */

internal fun AppModel.toggleSchedTag(tag: String) {
    val cur = schedTags
    schedTagsRaw = (if (tag in cur) cur - tag else cur + tag).joinToString(",")
}

/** Saves the tags on this phone only. */
internal fun AppModel.storeTags(map: Map<String, Set<String>>) {
    appTags = map
    val o = JSONObject()
    for ((k, v) in map) o.put(k, JSONArray(v.sorted()))
    tagsRaw = o.toString()
}

/** A change made here: save it, then copy it to the repository. */
internal fun AppModel.saveTags(map: Map<String, Set<String>>) {
    storeTags(map)
    editSeq++
    tagsUpdated = System.currentTimeMillis()
    tagsDirty = true
    syncTagsIfNeeded()
}

/** Counts edits made here, so a sync can tell whether something changed while it was talking to the repository. */
@Volatile private var editSeq = 0L

/**
 * Keeps the tags in step with the repository: reads them once after unlocking (a new or wiped phone gets them
 * back) and writes them whenever they changed here. If both sides changed, the two are merged.
 */
internal fun AppModel.syncTagsIfNeeded() {
    if (!ready || JobHub.busy || syncingTags || (tagsPulled && !tagsDirty)) return
    syncingTags = true
    tagSync = "syncing"
    val c = config()
    thread {
        var again = false
        try {
            val seq = editSeq
            val remote = reader.pullTags(c)?.let { TagSync.parse(it) ?: error("the saved tags file in the repository is unreadable") }
            when (val plan = TagSync.plan(appTags, tagsDirty, tagsBase, remote)) {
                is TagSync.Plan.Nothing -> Unit
                is TagSync.Plan.Adopt -> {
                    storeTags(plan.tags)
                    tagsUpdated = plan.updated
                    tagsBase = plan.updated
                }
                is TagSync.Plan.Push -> {
                    if (plan.merged) storeTags(plan.tags)
                    val stamp = maxOf(System.currentTimeMillis(), (remote?.updated ?: 0L) + 1)
                    if (!reader.pushTags(c, TagSync.serialize(plan.tags, stamp))) error("could not save the tags")
                    tagsBase = stamp
                    // Edited again while saving? Then the file is already out of date: stay dirty and go round again.
                    if (editSeq == seq) {
                        tagsUpdated = stamp
                        tagsDirty = false
                    } else {
                        again = true
                    }
                }
            }
            tagsPulled = true
            tagSync = "synced"
        } catch (e: Exception) {
            JobHub.append("tags: ${e.message}\n")
            tagSync = "failed"
        } finally {
            syncingTags = false
        }
        if (again) syncTagsIfNeeded()
    }
}

/** Try again now, also after an earlier failure. */
internal fun AppModel.syncTagsNow() {
    tagsPulled = false
    syncTagsIfNeeded()
}

/**
 * Creates a tag and returns its name, or the existing one that differs only in upper/lower case.
 * Null when the name is empty. Commas are dropped: restic and the saved list use them as separators.
 */
internal fun AppModel.cleanTagName(name: String): String? {
    val clean = name.replace(Regex("[,\\s]+"), " ").trim().take(24).trim()
    return if (clean.isEmpty() || clean.startsWith("android-")) null else clean
}

/** Renames a tag. If the new name is another existing tag, the two are merged. */
internal fun AppModel.renameTag(old: String, name: String): Boolean {
    val clean = cleanTagName(name) ?: return false
    val members = appTags[old] ?: return false
    if (clean == old) return true
    val target = appTags.keys.firstOrNull { it != old && it.equals(clean, ignoreCase = true) } ?: clean
    val map = LinkedHashMap(appTags)
    map.remove(old)
    map[target] = map[target].orEmpty() + members
    schedTagsRaw = schedTagsRaw.split(',').filter { it.isNotBlank() }.map { if (it == old) target else it }.distinct().joinToString(",")
    if (tagFilter == old) tagFilter = target
    saveTags(map)
    queueTagRename(old, target)
    return true
}

internal fun AppModel.pendingRenames(): List<Pair<String, String>> = runCatching {
    val a = JSONArray(pendingRenamesRaw)
    (0 until a.length()).map { a.getJSONArray(it).let { p -> p.getString(0) to p.getString(1) } }
}.getOrDefault(emptyList())

internal fun AppModel.savePendingRenames(list: List<Pair<String, String>>) {
    pendingRenamesRaw = JSONArray(list.map { JSONArray(listOf(it.first, it.second)) }).toString()
}

internal fun AppModel.queueTagRename(old: String, new: String) {
    if (old == new) return
    // A chain (A to B, then B to C) becomes A to C.
    val list = pendingRenames().map { if (it.second == old) it.first to new else it }.filter { it.first != it.second }
    savePendingRenames(if (list.any { it.first == old }) list else list + (old to new))
    renamesBlocked = false
    applyPendingTagRenames()
}

/**
 * Snapshots keep the tag name they were made with. After a rename here, give them the new name too, so the
 * snapshot list matches. Runs when the repository is unlocked and nothing else is running.
 */
internal fun AppModel.applyPendingTagRenames() {
    if (!ready || JobHub.busy || renamesBlocked) return
    val (old, new) = pendingRenames().firstOrNull() ?: return
    go("rename tag \"$old\" to \"$new\" on the snapshots", openLog = false) { c ->
        val r = runner.renameSnapshotTag(c, old, new)
        if (r.trimEnd().endsWith("[exit 0]")) {
            savePendingRenames(pendingRenames().filter { it.first != old })
            // The snapshots got new IDs: everything read from them is out of date.
            snapList = emptyList(); snapApps = emptyMap(); snapListStamp = null
            backedStamp = null
            JobHub.dataVersion++
        } else {
            renamesBlocked = true // do not retry in a loop; it is tried again after the next rename or restart
        }
        r
    }
}

internal fun AppModel.addTag(name: String): String? {
    val clean = cleanTagName(name) ?: return null
    appTags.keys.firstOrNull { it.equals(clean, ignoreCase = true) }?.let { return it }
    saveTags(appTags + (clean to emptySet()))
    return clean
}

/** Removes the tag everywhere. Apps and snapshots are not touched. */
internal fun AppModel.deleteTag(tag: String) {
    val wasScheduled = schedTags - tag
    saveTags(appTags - tag)
    schedTagsRaw = wasScheduled.joinToString(",")
    if (tagFilter == tag) tagFilter = ""
}

/** Puts [tag] on [pkgs], or takes it off them. */
internal fun AppModel.setTag(tag: String, pkgs: Set<String>, on: Boolean) {
    val cur = appTags[tag] ?: return
    saveTags(appTags + (tag to if (on) cur + pkgs else cur - pkgs))
}
