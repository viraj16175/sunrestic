package app.sunrestic

import org.junit.Assert.*
import org.junit.Test

class RunRulesTest {
    private val now = 1_000_000_000_000L
    private val min = 60_000L

    @Test fun unknownOrFutureTimesAreNeverRecent() {
        assertNull(RunRules.minutesSince(0, now))
        assertNull(RunRules.minutesSince(now + 2 * min, now)) // the "-2 min ago" case from the real phone
        assertFalse(RunRules.skipRetry(now + 2 * min, now))
        assertFalse(RunRules.skipReminder(now + 2 * min, now))
        assertFalse(RunRules.skipReminder(0, now))
    }

    @Test fun retryIsSkippedOnlyForARecentBackup() {
        assertTrue(RunRules.skipRetry(now - 10 * min, now))
        assertTrue(RunRules.skipRetry(now - 59 * min, now))
        assertFalse(RunRules.skipRetry(now - 61 * min, now))
    }

    @Test fun reminderStaysQuietForTwelveHours() {
        assertTrue(RunRules.skipReminder(now - 11 * 60 * min, now))
        assertFalse(RunRules.skipReminder(now - 13 * 60 * min, now))
        assertEquals(125L, RunRules.minutesSince(now - 125 * min, now))
    }
}

class StoppableTest {
    @Test fun neverStopsThisAppOrAVpn() {
        val pkgs = listOf("org.mozilla.firefox", "com.wireguard.android", "app.sunrestic", "org.videolan.vlc")
        val out = AppBackup.stoppable(pkgs, "app.sunrestic", setOf("com.wireguard.android"))
        assertEquals(listOf("org.mozilla.firefox", "org.videolan.vlc"), out)
    }

    @Test fun nothingToProtectStopsAll() {
        assertEquals(listOf("a", "b"), AppBackup.stoppable(listOf("a", "b"), "self", emptySet()))
    }
}

class RestoreDestinationsTest {
    @Test fun eachFolderGetsItsOwnSubfolder() {
        val d = restoreDestinations("/sdcard/Restored/", listOf("/sdcard/DCIM", "/sdcard/Documents/"))
        assertEquals(listOf("/sdcard/DCIM" to "/sdcard/Restored/DCIM", "/sdcard/Documents/" to "/sdcard/Restored/Documents"), d)
    }

    @Test fun sameNamesDoNotCollide() {
        val d = restoreDestinations("/t", listOf("/a/Pictures", "/b/Pictures", "/c/Pictures"))
        assertEquals(listOf("/t/Pictures", "/t/Pictures-2", "/t/Pictures-3"), d.map { it.second })
    }

    @Test fun rootFolderStillWorks() {
        assertEquals("/t/root", restoreDestinations("/t", listOf("/")).first().second)
    }
}

class HarmlessRestoreErrorTest {
    @Test fun knownSdcardNoiseIsHarmless() {
        assertTrue(isHarmlessRestoreError("failed to restore timestamp of /sdcard/DCIM: permission denied"))
        assertTrue(isHarmlessRestoreError("lchown /sdcard/DCIM/a.jpg: operation not permitted"))
    }

    @Test fun realProblemsAreNot() {
        assertFalse(isHarmlessRestoreError("failed to remove stale item: remove /sdcard: read-only file system"))
        assertFalse(isHarmlessRestoreError("write /sdcard/DCIM/a.jpg: no space left on device"))
        assertFalse(isHarmlessRestoreError("ciphertext verification failed"))
    }
}

class BackedFoldersTest {
    private fun snap(id: String, time: String, vararg paths: String, tag: String = "android-folders") =
        SnapInfo(id = id, time = time, tags = listOf(tag), pathList = paths.toList())

    @Test fun newestBackupOfEachFolderWins() {
        val list = backedFolders(
            listOf(
                snap("old", "2026-11-01T10:00:00+05:30", "/sdcard/DCIM", "/sdcard/Docs"),
                snap("new", "2026-11-20T10:00:00+05:30", "/sdcard/DCIM"),
            ),
        )
        assertEquals(listOf("/sdcard/DCIM" to "new", "/sdcard/Docs" to "old"), list.map { it.path to it.snapshotId })
    }

    @Test fun appSnapshotsAreIgnored() {
        val list = backedFolders(listOf(snap("a", "2026-11-01T10:00:00Z", "/data/data/x", tag = "android-apps")))
        assertTrue(list.isEmpty())
    }

    @Test fun sortedByPathIgnoringCase() {
        val list = backedFolders(listOf(snap("s", "2026-11-01T10:00:00Z", "/sdcard/b", "/sdcard/A", "/sdcard/c")))
        assertEquals(listOf("/sdcard/A", "/sdcard/b", "/sdcard/c"), list.map { it.path })
    }
}

class FolderIconTest {
    private fun n(path: String) = folderIcon(path).name

    @Test fun knownFoldersGetTheirOwnIcon() {
        assertEquals("Filled.CameraAlt", n("/sdcard/DCIM"))
        assertEquals("Filled.Image", n("/sdcard/Pictures"))
        assertEquals("Filled.Description", n("/sdcard/Documents/"))
        assertEquals("Filled.Download", n("/sdcard/Download"))
        assertEquals("Filled.Download", n("/storage/emulated/0/Downloads"))
        assertEquals("Filled.MusicNote", n("/sdcard/Music"))
        assertEquals("Filled.Movie", n("/sdcard/Movies"))
    }

    @Test fun caseDoesNotMatter() {
        assertEquals("Filled.CameraAlt", n("/sdcard/dcim"))
        assertEquals("Filled.Description", n("/sdcard/DOCUMENTS"))
    }

    @Test fun unknownFoldersStayFolders() {
        assertEquals("Filled.Folder", n("/sdcard/MyStuff"))
        assertEquals("Filled.Folder", n("/"))
    }
}

class PhoneKeyTest {
    @Test fun sameNumberInDifferentWritingsMatches() {
        assertEquals(digitsOnly("+91 98765 43210"), digitsOnly("098765-43210"))
        assertEquals("9876543210", digitsOnly("+91 98765 43210"))
    }

    @Test fun callKeyIgnoresFormattingButNotTimeOrType() {
        assertEquals(callKey("+91 98765 43210", 1000, 1), callKey("9876543210", 1000, 1))
        assertNotEquals(callKey("9876543210", 1000, 1), callKey("9876543210", 1001, 1))
        assertNotEquals(callKey("9876543210", 1000, 1), callKey("9876543210", 1000, 2))
    }

    @Test fun smsKeyTellsMessagesApartAndKeepsNamedSenders() {
        assertEquals(smsKey("+919876543210", 5, 1, "hi"), smsKey("9876543210", 5, 1, "hi"))
        assertNotEquals(smsKey("9876543210", 5, 1, "hi"), smsKey("9876543210", 5, 1, "hello"))
        assertNotEquals(smsKey("BANK", 5, 1, "x"), smsKey("SHOP", 5, 1, "x")) // no digits: the name is used
    }

    @Test fun contactKeyIgnoresPhoneOrderAndCase() {
        assertEquals(
            contactKey("Anna Lee", listOf("+91 98765 43210", "9123456789")),
            contactKey("anna lee ", listOf("9123456789", "098765-43210")),
        )
        assertNotEquals(contactKey("Anna", listOf("9876543210")), contactKey("Anna", listOf("9876543211")))
    }
}

class PhoneSnapshotTest {
    private fun snap(id: String, time: String, vararg tags: String) = SnapInfo(id = id, time = time, tags = tags.toList())

    @Test fun countComesFromTheTag() {
        assertEquals(312, snapshotCount(listOf("android-contacts", "android-count-312")))
        assertNull(snapshotCount(listOf("android-contacts")))
        assertNull(snapshotCount(listOf("android-count-abc")))
    }

    @Test fun kindIsRecognisedFromTags() {
        assertEquals(PhoneKind.CALLS, PhoneKind.of(listOf("android-calls", "android-count-5")))
        assertNull(PhoneKind.of(listOf("android-apps")))
    }

    @Test fun backupsAreNewestFirstAndOnlyOfThatKind() {
        val list = listOf(
            snap("a", "2026-11-01T10:00:00Z", "android-sms"),
            snap("b", "2026-11-20T10:00:00Z", "android-sms"),
            snap("c", "2026-11-25T10:00:00Z", "android-calls"),
        )
        assertEquals(listOf("b", "a"), phoneBackups(list, PhoneKind.SMS).map { it.id })
    }

    @Test fun choicesStartWithLatest() {
        val c = phoneChoices(listOf(snap("a", "2026-11-01T10:00:00+05:30", "android-sms", "android-count-9")), PhoneKind.SMS)
        assertEquals("latest", c.first().id)
        assertEquals("a", c[1].id)
        assertTrue(c[1].detail.contains("9 messages"))
    }
}
