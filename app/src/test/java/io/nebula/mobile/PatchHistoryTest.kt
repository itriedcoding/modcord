package io.nebula.mobile

import io.nebula.mobile.core.PatchHistory
import io.nebula.mobile.core.PatchRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** The history is a real JSON file on disk, so these tests read and write real files. */
class PatchHistoryTest {

    private fun tempFile(): File =
        File(Files.createTempDirectory("nebula-history").toFile(), "patches.json")

    @Test
    fun recordsNewestFirstAndPersistsToDisk() {
        val file = tempFile()
        val history = PatchHistory(file)
        assertNull(history.latest())

        history.add(PatchRecord("patched-a.apk", "a".repeat(64), 1000, 111, 2, 12, 1L))
        history.add(PatchRecord("patched-b.apk", "b".repeat(64), 2000, 222, 3, 24, 2L))
        assertTrue("history file written", file.exists())

        // A second instance proves the data came from the file, not from memory.
        val reloaded = PatchHistory(file)
        assertEquals(2, reloaded.read().size)
        assertEquals("patched-b.apk", reloaded.latest()?.file)
        assertEquals(222L, reloaded.latest()?.discordVersionCode)
        assertEquals(24, reloaded.latest()?.themeTokens)
        assertEquals(2000L, reloaded.latest()?.sizeBytes)
    }

    @Test
    fun skipsDuplicateBuildsAndRespectsTheLimit() {
        val file = tempFile()
        val history = PatchHistory(file)
        val record = PatchRecord("same.apk", "c".repeat(64), 10, 1, 0, 0, 0)
        history.add(record)
        history.add(record)
        assertEquals("the same build is only recorded once", 1, history.read().size)

        for (index in 0 until 5) {
            history.add(
                PatchRecord("build-$index.apk", "%064d".format(index), 10, 1, 0, 0, index.toLong()),
                limit = 3,
            )
        }
        assertEquals(3, history.read().size)
        assertEquals("build-4.apk", history.latest()?.file)
    }

    @Test
    fun survivesACorruptHistoryFile() {
        val file = tempFile()
        file.writeText("{not json at all")
        assertEquals(0, PatchHistory(file).read().size)
    }
}
