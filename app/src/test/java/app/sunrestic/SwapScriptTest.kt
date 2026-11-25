package app.sunrestic

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Runs the real swap_data.sh with a real shell on temporary folders. */
class SwapScriptTest {
    private val script = File("src/main/res/raw/swap_data.sh")

    private fun run(live: File, stage: File, undo: File): String {
        val p = ProcessBuilder("sh", script.absolutePath, live.absolutePath, stage.absolutePath, undo.absolutePath)
            .redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor()
        return out
    }

    private fun dir(): File = Files.createTempDirectory("swap").toFile().also { it.deleteOnExit() }
    private fun File.put(rel: String, text: String) = File(this, rel).also { it.parentFile.mkdirs(); it.writeText(text) }

    @Test fun replacesAndKeepsSafetyCopy() {
        val root = dir(); val live = File(root, "live").apply { mkdirs() }; val stage = File(root, "stage").apply { mkdirs() }
        val undo = File(root, "undo")
        live.put("db.sqlite", "new-ish"); live.put("db.sqlite-wal", "stale wal"); live.put("only-now.txt", "mine")
        live.put("sub dir/file with spaces.txt", "spaces"); live.put(".hidden", "dot"); live.put("cache/c.tmp", "cache"); live.put("code_cache/x", "cc")
        stage.put("db.sqlite", "from backup"); stage.put(".hidden-backup", "d2"); stage.put("prefs/a.xml", "p")

        val out = run(live, stage, undo)

        assertTrue(out, out.contains("SWAP_OK"))
        // restored state is exactly the backup (no stale wal, no leftovers)
        assertEquals("from backup", File(live, "db.sqlite").readText())
        assertEquals("p", File(live, "prefs/a.xml").readText())
        assertEquals("d2", File(live, ".hidden-backup").readText())
        assertFalse(File(live, "db.sqlite-wal").exists())
        assertFalse(File(live, "only-now.txt").exists())
        assertFalse(File(live, ".hidden").exists())
        // android's cache folders stay
        assertEquals("cache", File(live, "cache/c.tmp").readText())
        assertEquals("cc", File(live, "code_cache/x").readText())
        // the old data, including odd names, is kept for undo
        assertEquals("new-ish", File(undo, "db.sqlite").readText())
        assertEquals("stale wal", File(undo, "db.sqlite-wal").readText())
        assertEquals("mine", File(undo, "only-now.txt").readText())
        assertEquals("spaces", File(undo, "sub dir/file with spaces.txt").readText())
        assertEquals("dot", File(undo, ".hidden").readText())
        assertFalse(File(undo, "cache").exists())
        // the stage is emptied
        assertEquals(0, stage.list()!!.size)
    }

    @Test fun missingFoldersChangeNothing() {
        val root = dir(); val live = File(root, "live").apply { mkdirs() }
        live.put("keep.txt", "k")
        val out = run(live, File(root, "no-stage"), File(root, "undo"))
        assertTrue(out, out.contains("SWAP_FAILED_NO_DIR"))
        assertEquals("k", File(live, "keep.txt").readText())
    }

    @Test fun failureMovingOldFilesPutsEverythingBack() {
        assumeTrue("a read-only folder only blocks non-root users", System.getProperty("user.name") != "root")
        val root = dir(); val live = File(root, "live").apply { mkdirs() }; val stage = File(root, "stage").apply { mkdirs() }
        val undo = File(root, "undo")
        live.put("a.txt", "A"); live.put("b.txt", "B")
        stage.put("a.txt", "from backup")
        live.setWritable(false) // renaming files out of a read-only folder fails
        try {
            val out = run(live, stage, undo)
            assertTrue(out, out.contains("SWAP_FAILED_OLD"))
            assertEquals("A", File(live, "a.txt").readText())
            assertEquals("B", File(live, "b.txt").readText())
            assertEquals("from backup", File(stage, "a.txt").readText()) // staged copy untouched
        } finally {
            live.setWritable(true)
        }
    }
}
