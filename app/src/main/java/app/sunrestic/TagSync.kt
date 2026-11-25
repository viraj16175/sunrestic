package app.sunrestic

import org.json.JSONArray
import org.json.JSONObject

/** The tag file kept in the repository, and the decision of what to do when this phone and the repository differ. */
object TagSync {
    data class Remote(val updated: Long, val tags: Map<String, Set<String>>)

    sealed interface Plan {
        /** Both sides already match. */
        data object Nothing : Plan
        /** Take the repository's tags (this phone has no unsaved changes). */
        data class Adopt(val tags: Map<String, Set<String>>, val updated: Long) : Plan
        /** Write [tags] to the repository; [merged] when both sides had changed and were combined. */
        data class Push(val tags: Map<String, Set<String>>, val merged: Boolean) : Plan
    }

    /**
     * [local] tags here, [dirty] = changed here and not yet saved to the repository, [base] = the version of the
     * repository's file this phone last matched, [remote] = the repository's file now (null = there is none).
     */
    fun plan(local: Map<String, Set<String>>, dirty: Boolean, base: Long, remote: Remote?): Plan = when {
        remote == null -> if (local.isNotEmpty() || dirty) Plan.Push(local, false) else Plan.Nothing
        !dirty ->
            if (remote.updated > base) Plan.Adopt(remote.tags, remote.updated)
            else if (remote.tags != local) Plan.Push(local, false)
            else Plan.Nothing
        remote.updated > base -> Plan.Push(merge(remote.tags, local), true) // both changed: keep everything from both
        else -> Plan.Push(local, false)
    }

    fun merge(a: Map<String, Set<String>>, b: Map<String, Set<String>>): Map<String, Set<String>> {
        val out = LinkedHashMap<String, Set<String>>()
        for (k in a.keys + b.keys) out[k] = a[k].orEmpty() + b[k].orEmpty()
        return out
    }

    fun serialize(map: Map<String, Set<String>>, updated: Long): String {
        val o = JSONObject()
        for ((k, v) in map) o.put(k, JSONArray(v.sorted()))
        return JSONObject().put("version", 1).put("updated", updated).put("tags", o).toString()
    }

    /** Null when [text] is not a tag file. */
    fun parse(text: String): Remote? = runCatching {
        val o = JSONObject(text)
        val tags = o.getJSONObject("tags")
        Remote(o.optLong("updated"), parseTags(tags.toString()))
    }.getOrNull()
}
