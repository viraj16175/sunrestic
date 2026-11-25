package app.sunrestic

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Exporting and importing the app settings and the SSH key. These are extensions of [AppModel], which holds the state. */

/** Copies a key file picked with the system file picker into the app's private storage. */
/** Copies a picked key into private storage under a temporary name and returns its path; nothing is applied yet. */
internal fun AppModel.importKeyFile(uri: Uri): String? = try {
    val dst = File(ctx.filesDir, "ssh_key_pending")
    val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("cannot read file")
    val fixed = if (bytes.isNotEmpty() && bytes.last() != '\n'.code.toByte()) bytes + '\n'.code.toByte() else bytes
    dst.writeBytes(fixed)
    dst.setReadable(false, false)
    dst.setWritable(false, false)
    dst.setReadable(true, true)
    dst.setWritable(true, true)
    dst.absolutePath
} catch (e: Exception) {
    toast("Could not import key: ${e.message}")
    null
}

// ---- settings export / import -------------------------------------------

/** Everything except secrets (password, keys): handy for setting up a second phone or after a wipe. */
internal fun AppModel.exportSettings(): String {
    val o = JSONObject()
    fun s(k: String, v: String) { o.put(k, v) }
    s("backend", backend); s("host", host); s("port", port); s("user", user); s("repoPath", repoPath)
    s("localRepo", localRepo); s("endpoint", endpoint); s("bucket", bucket); s("dns", dns)
    o.put("useRoot", useRoot); o.put("wifiOnly", wifiOnly); o.put("verifyAfter", verifyAfter); o.put("forceStop", forceStop)
    o.put("schedOn", schedOn); s("schedHour", schedHour); s("schedMinute", schedMinute); o.put("schedFolders", schedFolders); o.put("schedApps", schedApps)
    s("schedAppMode", schedAppMode); s("schedTags", schedTags.joinToString(","))
    s("keepLast", keepLast); s("keepDaily", keepDaily); s("keepWeekly", keepWeekly); s("keepMonthly", keepMonthly)
    o.put("folders", JSONArray(folders))
    val t = JSONObject()
    for ((k, v) in appTags) t.put(k, JSONArray(v.sorted()))
    o.put("tags", t)
    return JSONObject().put("app", "sunrestic").put("version", 1).put("settings", o).toString(2)
}

/** Applies a file made by [exportSettings]. Returns false when it is not one. */
internal fun AppModel.importSettings(text: String): Boolean {
    val o = runCatching { JSONObject(text) }.getOrNull()?.takeIf { it.optString("app") == "sunrestic" }?.optJSONObject("settings")
        ?: return false
    fun str(k: String, f: (String) -> Unit) { if (o.has(k)) f(o.optString(k)) }
    fun bool(k: String, f: (Boolean) -> Unit) { if (o.has(k)) f(o.optBoolean(k)) }
    str("backend") { backend = it }; str("host") { host = it }; str("port") { port = it }; str("user") { user = it }
    str("repoPath") { repoPath = it }; str("localRepo") { localRepo = it }; str("endpoint") { endpoint = it }
    str("bucket") { bucket = it }; str("dns") { dns = it }
    bool("useRoot") { useRoot = it }; bool("wifiOnly") { wifiOnly = it }; bool("verifyAfter") { verifyAfter = it }
    bool("forceStop") { forceStop = it }
    bool("schedOn") { schedOn = it }; str("schedHour") { schedHour = it }; str("schedMinute") { schedMinute = it }; bool("schedFolders") { schedFolders = it }
    bool("schedApps") { schedApps = it }; str("schedAppMode") { schedAppMode = it }; str("schedTags") { schedTagsRaw = it }
    str("keepLast") { keepLast = it }; str("keepDaily") { keepDaily = it }; str("keepWeekly") { keepWeekly = it }
    str("keepMonthly") { keepMonthly = it }
    o.optJSONArray("folders")?.let { a ->
        foldersRaw = a.toString()
        folders = parseFolders(foldersRaw)
    }
    o.optJSONObject("tags")?.let { t ->
        val merged = LinkedHashMap<String, Set<String>>(appTags)
        for ((k, v) in parseTags(t.toString())) merged[k] = merged[k].orEmpty() + v
        saveTags(merged)
    }
    applySchedule()
    return true
}
