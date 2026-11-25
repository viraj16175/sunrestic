package app.sunrestic

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.IsoFields

/** How many snapshots of each kind to keep: the newest few, plus one per day / week / month going back. */
data class RetentionPolicy(val last: Int = 3, val daily: Int = 0, val weekly: Int = 0, val monthly: Int = 0) {
    val summary: String
        get() = buildList {
            add("newest $last")
            if (daily > 0) add("$daily daily")
            if (weekly > 0) add("$weekly weekly")
            if (monthly > 0) add("$monthly monthly")
        }.joinToString(", ")
}

object Retention {
    /**
     * Which snapshots to keep. [items] are (id, time in ms), in any order.
     * Keeps the newest [RetentionPolicy.last], and the newest snapshot of each of the latest N days / weeks / months.
     */
    fun keep(items: List<Pair<String, Long>>, p: RetentionPolicy, zone: ZoneId = ZoneId.systemDefault()): Set<String> {
        val newestFirst = items.sortedByDescending { it.second }
        val keep = LinkedHashSet<String>()
        newestFirst.take(p.last.coerceAtLeast(0)).forEach { keep += it.first }

        fun bucket(limit: Int, key: (LocalDate) -> Any) {
            if (limit <= 0) return
            val seen = LinkedHashSet<Any>()
            for ((id, t) in newestFirst) {
                val k = key(Instant.ofEpochMilli(t).atZone(zone).toLocalDate())
                if (k in seen) continue // an earlier (newer) snapshot already stands for this day / week / month
                if (seen.size >= limit) break
                seen += k
                keep += id
            }
        }
        bucket(p.daily) { it }
        bucket(p.weekly) { it.get(IsoFields.WEEK_BASED_YEAR) to it.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR) }
        bucket(p.monthly) { it.year to it.monthValue }
        return keep
    }
}
