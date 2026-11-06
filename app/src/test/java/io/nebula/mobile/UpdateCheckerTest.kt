package io.nebula.mobile

import io.nebula.mobile.core.Hashes
import io.nebula.mobile.update.UpdateChecker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {

    private val releaseJson = """
        {
          "tag_name": "v1.5.0",
          "name": "Nebula 1.5.0",
          "body": "Notes here",
          "assets": [
            {"name": "nebula-1.5.0.apk.sha256", "browser_download_url": "https://example.test/sha", "size": 65},
            {"name": "nebula-1.5.0.apk", "browser_download_url": "https://example.test/apk", "size": 6238384}
          ]
        }
    """.trimIndent()

    @Test
    fun parsesTheRealGitHubReleaseShape() {
        val info = UpdateChecker.parseRelease(releaseJson)
        assertEquals("v1.5.0", info.tag)
        assertEquals("Nebula 1.5.0", info.name)
        assertEquals("https://example.test/apk", info.apkUrl)
        assertEquals(6238384L, info.apkSize)
        assertEquals("https://example.test/sha", info.checksumUrl)
        assertEquals("Notes here", info.notes)
    }

    @Test
    fun refusesAReleaseWithoutAnApk() {
        val error = runCatching { UpdateChecker.parseRelease("""{"tag_name":"v2","assets":[]}""") }.exceptionOrNull()
        assertTrue("got: ${error?.message}", error is IllegalStateException)
        assertTrue(error!!.message!!.contains("no .apk asset"))
    }

    @Test
    fun comparesVersionsNumerically() {
        assertTrue(UpdateChecker.isNewer("v1.10.0", "1.9.9"))
        assertTrue(UpdateChecker.isNewer("1.5.0", "1.4.0"))
        assertFalse(UpdateChecker.isNewer("1.4.0", "1.4.0"))
        assertFalse(UpdateChecker.isNewer("v1.3.9", "1.4.0"))
        assertFalse(UpdateChecker.isNewer("not a version", "1.4.0"))
    }

    @Test
    fun readsTheChecksumSidecarAndHashesBytes() {
        val sidecar = "ca4880aadcb0bfa4a4ced2651cb74e16acfb31673c02846c1fc3e114282616a5  nebula-1.5.0.apk\n"
        assertEquals(
            "ca4880aadcb0bfa4a4ced2651cb74e16acfb31673c02846c1fc3e114282616a5",
            UpdateChecker.shaFromSidecar(sidecar),
        )
        assertNull(UpdateChecker.shaFromSidecar("no checksum here"))
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            Hashes.sha256(ByteArray(0)),
        )
    }
}
