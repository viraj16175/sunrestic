package app.sunrestic

import org.junit.Assert.assertEquals
import org.junit.Test

class ManifestPathTest {
    private val own = "/data/user/0/app.sunrestic/files/app-manifest/manifest.json"

    @Test fun usesThePathTheSnapshotRecorded() {
        val paths = listOf("/data/data/org.mozilla.firefox", "/data/user/0/app.resticbackup/files/app-manifest/manifest.json")
        assertEquals("/data/user/0/app.resticbackup/files/app-manifest/manifest.json", manifestPathIn(paths, own))
    }

    @Test fun fallsBackToThisAppsPathWhenTheSnapshotListsNone() {
        assertEquals(own, manifestPathIn(listOf("/sdcard/DCIM"), own))
        assertEquals(own, manifestPathIn(emptyList(), own))
    }
}
