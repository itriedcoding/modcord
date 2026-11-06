package io.nebula.mobile

import io.nebula.mobile.patch.ApkSource
import io.nebula.mobile.patch.InstalledDiscord
import io.nebula.mobile.patch.PatchException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Real file handling for the installed-APK path. Package lookup itself needs a device
 * and is therefore not asserted here - it is reported as unverified instead.
 */
class InstalledDiscordTest {

    @Test
    fun copiesAnInstalledApkByteForByte() {
        val source = File.createTempFile("installed-base", ".apk")
        val bytes = ByteArray(200_000) { index -> ((index * 31) % 256).toByte() }
        source.writeBytes(bytes)
        val target = File(Files.createTempDirectory("nebula-installed").toFile(), "copied.apk")

        ApkSource.copy(source.absolutePath, target)

        assertTrue(target.exists())
        assertEquals(bytes.size.toLong(), target.length())
        assertArrayEquals(bytes, target.readBytes())
    }

    @Test
    fun refusesAMissingInstalledApkAndNamesThePath() {
        val missing = File(Files.createTempDirectory("nebula-missing").toFile(), "base.apk")
        try {
            ApkSource.copy(missing.absolutePath, File(missing.parentFile, "out.apk"))
            fail("copying a missing APK should be refused")
        } catch (e: PatchException) {
            assertTrue("message should name the path, was: ${e.message}", e.message!!.contains(missing.absolutePath))
            assertTrue(e.message!!.contains("Pick the APK file instead"))
        }
    }

    @Test
    fun refusesATinyFileThatCannotBeAnApk() {
        val small = File.createTempFile("tiny", ".apk")
        small.writeBytes(ByteArray(10))
        try {
            ApkSource.copy(small.absolutePath, File(small.parentFile, "tiny-out.apk"))
            fail("a 10 byte file should be refused")
        } catch (e: PatchException) {
            assertTrue(e.message!!.contains("cannot be a real APK"))
        }
    }

    @Test
    fun describesSingleAndSplitInstalls() {
        val single = InstalledDiscord(
            packageName = "com.discord",
            label = "Discord",
            versionName = "265.15",
            versionCode = 2651502L,
            apkPath = "/data/app/com.discord-1/base.apk",
            splitPaths = emptyList(),
        )
        val split = single.copy(
            splitPaths = listOf("/data/app/split_config.arm64_v8a.apk", "/data/app/split_config.en.apk"),
        )

        assertEquals("Discord 265.15 (2651502) · single APK", single.summary())
        assertTrue(!single.isSplitInstall)
        assertTrue(split.isSplitInstall)
        assertTrue(split.summary().contains("3 APK splits"))
    }
}
