package app.sunrestic

import app.sunrestic.TagSync.Plan
import app.sunrestic.TagSync.Remote
import org.junit.Assert.*
import org.junit.Test

class TagSyncTest {
    private val daily = mapOf("Daily" to setOf("a", "b"))
    private val games = mapOf("Games" to setOf("g"))

    @Test fun nothingAnywhereMeansNothingToDo() {
        assertEquals(Plan.Nothing, TagSync.plan(emptyMap(), false, 0, null))
    }

    @Test fun firstPushWhenRepositoryHasNoTagFile() {
        assertEquals(Plan.Push(daily, false), TagSync.plan(daily, false, 0, null))
    }

    @Test fun deletingEveryTagIsStillPushed() {
        assertEquals(Plan.Push(emptyMap(), false), TagSync.plan(emptyMap(), true, 5, null))
    }

    @Test fun wipedPhoneGetsTagsBack() {
        val plan = TagSync.plan(emptyMap(), false, 0, Remote(100, daily))
        assertEquals(Plan.Adopt(daily, 100), plan)
    }

    @Test fun newerRepositoryWinsWhenNothingChangedHere() {
        assertEquals(Plan.Adopt(games, 200), TagSync.plan(daily, false, 100, Remote(200, games)))
    }

    @Test fun alreadyInStepDoesNothing() {
        assertEquals(Plan.Nothing, TagSync.plan(daily, false, 100, Remote(100, daily)))
    }

    @Test fun localDriftIsPushedWhenRepositoryIsNotNewer() {
        assertEquals(Plan.Push(daily, false), TagSync.plan(daily, false, 100, Remote(100, games)))
    }

    @Test fun editsHereWinWhenRepositoryHasNotMoved() {
        assertEquals(Plan.Push(daily, false), TagSync.plan(daily, true, 100, Remote(100, games)))
    }

    @Test fun bothChangedMeansMergeNotOverwrite() {
        val plan = TagSync.plan(daily, true, 100, Remote(200, mapOf("Daily" to setOf("c"), "Games" to setOf("g"))))
        plan as Plan.Push
        assertTrue(plan.merged)
        assertEquals(setOf("a", "b", "c"), plan.tags["Daily"])
        assertEquals(setOf("g"), plan.tags["Games"])
    }

    @Test fun mergeKeepsEverything() {
        val m = TagSync.merge(daily, mapOf("Daily" to setOf("z"), "Games" to setOf("g")))
        assertEquals(setOf("a", "b", "z"), m["Daily"])
        assertEquals(2, m.size)
    }

    @Test fun fileRoundTrip() {
        val text = TagSync.serialize(daily + games, 1234)
        val back = TagSync.parse(text)!!
        assertEquals(1234L, back.updated)
        assertEquals(daily + games, back.tags)
    }

    @Test fun unreadableFilesAreNotTreatedAsEmptyTags() {
        assertNull(TagSync.parse("garbage"))
        assertNull(TagSync.parse("""{"version":1,"updated":5}"""))
    }
}
