package app.sunrestic

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneOffset

class RetentionTest {
    private val utc = ZoneOffset.UTC
    private fun at(y: Int, mo: Int, d: Int, h: Int = 12) = LocalDateTime.of(y, mo, d, h, 0).toInstant(utc).toEpochMilli()
    private fun keep(items: List<Pair<String, Long>>, p: RetentionPolicy) = Retention.keep(items, p, utc)

    @Test fun keepsNewestN() {
        val items = listOf("a" to at(2026, 1, 1), "b" to at(2026, 1, 2), "c" to at(2026, 1, 3), "d" to at(2026, 1, 4))
        assertEquals(setOf("c", "d"), keep(items, RetentionPolicy(last = 2)))
    }

    @Test fun orderOfInputDoesNotMatter() {
        val items = listOf("d" to at(2026, 1, 4), "a" to at(2026, 1, 1), "c" to at(2026, 1, 3), "b" to at(2026, 1, 2))
        assertEquals(setOf("c", "d"), keep(items, RetentionPolicy(last = 2)))
    }

    @Test fun zeroLastKeepsNothingExtra() {
        assertEquals(emptySet<String>(), keep(listOf("a" to at(2026, 1, 1)), RetentionPolicy(last = 0)))
    }

    @Test fun dailyKeepsNewestOfEachDay() {
        val items = listOf(
            "d1am" to at(2026, 3, 1, 8), "d1pm" to at(2026, 3, 1, 20),
            "d2am" to at(2026, 3, 2, 8), "d2pm" to at(2026, 3, 2, 20),
            "d3" to at(2026, 3, 3, 9),
        )
        // newest 1 = d3; daily 2 = newest snapshot of the latest two days = d3 and d2pm
        assertEquals(setOf("d3", "d2pm"), keep(items, RetentionPolicy(last = 1, daily = 2)))
    }

    @Test fun weeklyAndMonthly() {
        val items = listOf(
            "jan" to at(2026, 1, 15), "feb1" to at(2026, 2, 3), "feb2" to at(2026, 2, 20), "mar" to at(2026, 3, 10),
        )
        assertEquals(setOf("mar", "feb2"), keep(items, RetentionPolicy(last = 0, monthly = 2)))
        assertEquals(setOf("mar", "feb2", "feb1"), keep(items, RetentionPolicy(last = 0, weekly = 3)))
    }

    @Test fun policiesCombine() {
        val items = (1..10).map { "s$it" to at(2026, 5, it) }
        val kept = keep(items, RetentionPolicy(last = 2, daily = 4))
        assertEquals(setOf("s10", "s9", "s8", "s7"), kept)
    }

    @Test fun summaryText() {
        assertEquals("newest 3", RetentionPolicy().summary)
        assertEquals("newest 5, 7 daily, 4 weekly, 6 monthly", RetentionPolicy(5, 7, 4, 6).summary)
    }
}
