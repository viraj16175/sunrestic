package app.sunrestic

import org.junit.Assert.*
import org.junit.Test

class ResticJsonTest {
    @Test fun backupStatus() {
        val e = ResticJson.parse(
            """{"message_type":"status","percent_done":0.5,"total_files":10,"files_done":5,"total_bytes":2000,"bytes_done":1000,"seconds_elapsed":10}"""
        ) as ResticEvent.Status
        assertEquals(0.5, e.percent, 1e-9)
        assertEquals(5L, e.filesDone)
        assertEquals(1000L, e.bytesDone)
        assertEquals(10L, e.secondsRemaining) // 10s * (1-0.5)/0.5
    }

    @Test fun restoreStatusUsesRestoredFields() {
        val e = ResticJson.parse(
            """{"message_type":"status","files_restored":3,"total_files":6,"bytes_restored":50,"total_bytes":100}"""
        ) as ResticEvent.Status
        assertEquals(0.5, e.percent, 1e-9)
        assertEquals(3L, e.filesDone)
        assertEquals(50L, e.bytesDone)
    }

    @Test fun backupSummary() {
        val e = ResticJson.parse(
            """{"message_type":"summary","snapshot_id":"abcdef12","files_new":1,"files_changed":2,"data_added":300,"total_files_processed":9}"""
        ) as ResticEvent.Summary
        assertEquals("abcdef12", e.snapshotId)
        assertEquals(2L, e.filesChanged)
    }

    @Test fun restoreSummaryHasNoSnapshotId() {
        val e = ResticJson.parse("""{"message_type":"summary","files_restored":4,"bytes_restored":8,"seconds_elapsed":1.5}""")
        assertTrue(e is ResticEvent.RestoreSummary)
    }

    @Test fun errorAndGarbage() {
        val err = ResticJson.parse("""{"message_type":"error","error":{"message":"boom"}}""") as ResticEvent.Error
        assertEquals("boom", err.message)
        assertTrue(ResticJson.parse("not json at all") is ResticEvent.Other)
        assertTrue(ResticJson.parse("{broken") is ResticEvent.Other)
    }

    @Test fun etaNotGuessedTooEarly() {
        val e = ResticJson.parse("""{"message_type":"status","percent_done":0.01,"seconds_elapsed":5,"total_bytes":10}""") as ResticEvent.Status
        assertNull(e.secondsRemaining)
    }
}

class FormatTest {
    @Test fun bytes() {
        assertEquals("0 B", fmtBytes(0))
        assertEquals("1.0 KB", fmtBytes(1024))
        assertEquals("1.5 MB", fmtBytes(1536 * 1024))
    }

    @Test fun gb() {
        assertEquals("12 B", fmtGb(12))
        assertEquals("1.00 GB", fmtGb(1024L * 1024 * 1024))
    }

    @Test fun eta() {
        assertEquals("", fmtEta(null))
        assertEquals("", fmtEta(0))
        assertEquals("30s left", fmtEta(30))
        assertEquals("2 min 5s left", fmtEta(125))
    }

    @Test fun snapshotTime() {
        assertEquals("Nov 3, 2026 · 08:12", fmtSnapTime("2026-11-03T08:12:45+05:30"))
        assertEquals("garbage", fmtSnapTime("garbage"))
    }

    @Test fun resticTimeParsing() {
        val a = parseResticTime("2026-11-03T08:12:45.123456789+05:30")
        val b = parseResticTime("2026-11-03T02:42:45Z")
        assertNotNull(a)
        assertEquals(a, b)
        assertNull(parseResticTime("nope"))
        assertEquals("", relativeTime("nope"))
    }
}

class TagsTest {
    @Test fun parsesSavedTags() {
        val m = parseTags("""{"Daily":["a.b","c.d"],"Games":[]}""")
        assertEquals(setOf("a.b", "c.d"), m["Daily"])
        assertEquals(emptySet<String>(), m["Games"])
    }

    @Test fun badInputGivesEmpty() {
        assertTrue(parseTags("").isEmpty())
        assertTrue(parseTags("[1,2]").isEmpty())
    }
}

class SnapshotKindTest {
    private fun snap(vararg tags: String) = SnapInfo(id = "x", time = "", tags = tags.toList())

    @Test fun kinds() {
        assertEquals("apps", snapKind(snap("android-apps", "Daily")))
        assertEquals("folders", snapKind(snap("android-folders")))
        assertEquals("paths", snapKind(snap("android-test")))
    }
}

class BackendUrlTest {
    private fun cfg(backend: String, endpoint: String = "", bucket: String = "") = Config(
        host = "h.example", port = "22", user = "u", repoPath = "/srv/r", keyPath = "", password = "p", dns = "",
        useRoot = false, remote = backend != "local", localRepo = "/sdcard/repo",
        backend = backend, endpoint = endpoint, bucket = bucket, accessKey = "AK", secretKey = "SK",
    )

    @Test fun sftp() = assertEquals("sftp:u@h.example:/srv/r", repoUrlFor(cfg("sftp")))
    @Test fun local() = assertEquals("/sdcard/repo", repoUrlFor(cfg("local")))
    @Test fun s3JoinsEndpointAndBucket() {
        assertEquals("s3:https://s3.example.com/bucket/phone", repoUrlFor(cfg("s3", "https://s3.example.com/", "/bucket/phone/")))
    }
    @Test fun rest() = assertEquals("rest:https://b.example:8000/phone", repoUrlFor(cfg("rest", " https://b.example:8000/phone ")))
    @Test fun b2() = assertEquals("b2:bucket:phone", repoUrlFor(cfg("b2", bucket = "bucket:phone")))
    @Test fun secretsNeverInUrl() {
        for (b in listOf("s3", "rest", "b2")) {
            val url = repoUrlFor(cfg(b, "https://x.example", "bk"))
            assertFalse(url.contains("SK")); assertFalse(url.contains("AK")); assertFalse(url.contains("p@"))
        }
    }
    @Test fun oldConfigStillMeansSftpOrLocal() {
        val c = Config("h", "22", "u", "/r", "", "p", "", false, true, "")
        assertEquals("sftp", c.backend)
        assertEquals("local", Config("h", "22", "u", "/r", "", "p", "", false, false, "/x").backend)
    }
}
