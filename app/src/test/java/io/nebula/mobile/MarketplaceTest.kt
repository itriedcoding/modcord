package io.nebula.mobile

import io.nebula.mobile.core.AddonType
import io.nebula.mobile.core.Hashes
import io.nebula.mobile.market.Marketplace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarketplaceTest {

    private fun index(sha: String) = """
        {"version": 1, "addons": [
          {"name": "Session Timer", "type": "plugins", "version": "1.0.0", "author": "Nebula",
           "description": "Counts session time.",
           "path": "app/src/main/assets/addons/plugins/session-timer.plugin.js",
           "sha256": "$sha"}
        ]}
    """.trimIndent()

    @Test
    fun parsesPathsIntoRawUrlsAndTypes() {
        val entries = Marketplace.parseIndex(index("a".repeat(64)))
        assertEquals(1, entries.size)
        val entry = entries.single()
        assertEquals("session-timer", entry.id)
        assertEquals(AddonType.PLUGIN, entry.type)
        assertEquals("1.0.0", entry.version)
        assertTrue(entry.url.startsWith(Marketplace.RAW_BASE))
        assertTrue(entry.url.endsWith("session-timer.plugin.js"))
    }

    @Test
    fun rejectsEntriesWithoutAValidChecksum() {
        val error = runCatching { Marketplace.parseIndex(index("nope")) }.exceptionOrNull()
        assertTrue("got: ${error?.message}", error is IllegalArgumentException)
        assertTrue(error!!.message!!.contains("sha256"))
    }

    @Test
    fun verifiesDownloadedBytesAgainstTheChecksum() {
        val source = "/**\n * @name Session Timer\n */\nmodule.exports = {};"
        val bytes = source.toByteArray()
        val entry = Marketplace.parseIndex(index(Hashes.sha256(bytes))).single()
        assertTrue(Marketplace.verify(entry, bytes))
        assertFalse(
            "tampered bytes must not verify",
            Marketplace.verify(entry, source.replace("Session", "Hijacked").toByteArray()),
        )
    }
}
