package io.nebula.mobile

import io.nebula.mobile.patch.ApkIdentity
import io.nebula.mobile.patch.ApkSigning
import io.nebula.mobile.patch.BundlePatcher
import io.nebula.mobile.patch.DiscordPatcher
import io.nebula.mobile.patch.PatchAddon
import io.nebula.mobile.patch.PatchException
import io.nebula.mobile.patch.PatchRequest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * End-to-end tests for the Discord APK patcher. They operate on real ZIP containers,
 * real compression, real alignment and a real APK signature - nothing is mocked.
 */
class PatchPipelineTest {

    private val bundlePath = "assets/index.android.bundle"

    private val sampleBundle = """
        var __BUNDLE_START_TIME__ = global.nativePerformanceNow ? global.nativePerformanceNow() : Date.now();
        (function (global) {
          "use strict";
          var modules = {};
          function __r(id) {
            var module = modules[id];
            if (!module) throw new Error("Requiring unknown module " + id);
            if (module.isInitialized) return module.publicModule.exports;
            module.isInitialized = true;
            module.publicModule.exports = module.factory(global, __r, id, module.publicModule, module.publicModule.exports, module.dependencyMap, undefined);
            return module.publicModule.exports;
          }
          global.__r = __r;
          global.__d = function (factory, id, deps) {
            modules[id] = { factory: factory, dependencyMap: deps, publicModule: { exports: {} }, isInitialized: false };
          };
        })(global);
        __d(function (g, r, i, a, m, e, d) { m.exports = { version: "sample" }; }, 0, [], "main");
        __r(0);
    """.trimIndent()

    /* ------------------------------------------------------------------ */

    @Test
    fun patchesBundleAndProducesVerifiableSignature() {
        val apk = fixture(sampleBundle.toByteArray())
        val output = Files.createTempDirectory("nebula-patch-out").toFile()
        val plugin = """
            /**
             * @name Hello World
             * @version 1.0.0
             */
            module.exports = class HelloWorld {
              start() { this.started = true; }
              stop() { this.started = false; }
            };
        """.trimIndent()

        val report = DiscordPatcher.patch(
            PatchRequest(
                apk = apk,
                outputDirectory = output,
                addons = listOf(PatchAddon("hello-world", "Hello World", "1.0.0", "plugin", plugin)),
                loaderJs = loaderSource(),
                identity = identity(),
                nebulaVersion = "1.0.0",
            ),
        )

        assertTrue("output APK exists", report.output.exists())
        assertTrue("output APK name", report.output.name.endsWith(".apk"))
        assertTrue("signature verifies", report.signature.verified)
        assertEquals("exactly one signer", 1, report.signature.signerCount)
        assertTrue("v2 signature", report.signature.schemeV2)
        assertTrue("v3 signature", report.signature.schemeV3)
        assertTrue("v1 signature", report.signature.schemeV1)
        assertEquals(64, report.sha256.length)
        assertEquals(bundlePath, report.bundleEntry)
        assertTrue("bundle grew by the loader", report.bundleBytesAfter > report.bundleBytesBefore)
        assertTrue("old signature dropped", report.rewrite.droppedEntries.any { it.endsWith("CERT.SF") })

        ZipFile(report.output).use { zip ->
            val patched = zip.getInputStream(zip.getEntry(bundlePath)!!).use { it.bufferedReader().readText() }
            assertTrue("loader injected", patched.contains(BundlePatcher.LOADER_BEGIN))
            assertTrue("loader terminated", patched.contains(BundlePatcher.LOADER_END))
            assertTrue("payload embedded", patched.contains("__nebula_payload"))
            assertTrue("plugin source embedded", patched.contains("module.exports = class HelloWorld"))
            assertTrue("original bundle preserved", patched.contains("__BUNDLE_START_TIME__"))
            assertTrue("metro runtime preserved", patched.contains("global.__r = __r;"))

            // The old signer is gone; the manifest that remains is the one apksig generated
            // for the rebuilt container, so it must name the patched bundle.
            assertNull("old SF dropped", zip.getEntry("META-INF/CERT.SF"))
            assertNull("old RSA dropped", zip.getEntry("META-INF/CERT.RSA"))
            val jarManifest = zip.getInputStream(zip.getEntry("META-INF/MANIFEST.MF")!!)
                .use { it.bufferedReader().readText() }
            assertTrue(
                "v1 manifest was regenerated for the patched bundle, saw: ${jarManifest.take(160)}",
                jarManifest.contains("Name: $bundlePath"),
            )

            assertEquals(
                "untouched entry preserved",
                "keep me",
                zip.getInputStream(zip.getEntry("res/raw/keep.txt")!!).use { it.bufferedReader().readText() },
            )
            assertArrayEquals(
                "dex preserved",
                ByteArray(4096) { (it % 251).toByte() },
                zip.getInputStream(zip.getEntry("classes.dex")!!).use { it.readBytes() },
            )
            assertArrayEquals(
                "native library preserved",
                ByteArray(8192) { (it % 97).toByte() },
                zip.getInputStream(zip.getEntry("lib/arm64-v8a/libreactnativejni.so")!!).use { it.readBytes() },
            )
        }

        val entries = localHeaders(report.output)
        val native = entries.first { it.name.endsWith(".so") }
        assertEquals("native library stays stored", 0, native.method)
        assertEquals("native library is page aligned", 0L, native.dataOffset % 4096L)
        assertEquals("resources.arsc stays stored", 0, entries.first { it.name == "resources.arsc" }.method)
        assertEquals("resources.arsc is 4-byte aligned", 0L, entries.first { it.name == "resources.arsc" }.dataOffset % 4L)
        assertEquals("classes.dex is 4-byte aligned", 0L, entries.first { it.name == "classes.dex" }.dataOffset % 4L)
    }

    @Test
    fun patchedBundleIsValidJavaScript() {
        val node = nodeBinary() ?: run {
            println("node is not installed here; skipping the JavaScript syntax check")
            return
        }
        val apk = fixture(sampleBundle.toByteArray())
        val output = Files.createTempDirectory("nebula-syntax-out").toFile()
        val report = DiscordPatcher.patch(
            PatchRequest(
                apk = apk,
                outputDirectory = output,
                addons = listOf(PatchAddon("accent", "Accent", "1.0.0", "theme", ":root { --x: 1 }")),
                loaderJs = loaderSource(),
                identity = identity(),
                nebulaVersion = "1.0.0",
            ),
        )

        val extracted = File(output, "bundle.js")
        ZipFile(report.output).use { zip ->
            extracted.writeBytes(zip.getInputStream(zip.getEntry(bundlePath)!!).use { it.readBytes() })
        }
        val process = ProcessBuilder(node, "--check", extracted.absolutePath)
            .redirectErrorStream(true)
            .start()
        val outputText = process.inputStream.bufferedReader().use { it.readText() }
        val exit = process.waitFor()
        assertEquals("node --check rejected the patched bundle:\n$outputText", 0, exit)
    }

    @Test
    fun refusesHermesBytecodeInsteadOfFakingAPatch() {
        val hermes = ByteArray(256).also {
            it[0] = 0xC6.toByte()
            it[1] = 0x1F.toByte()
            it[2] = 0xBC.toByte()
            it[3] = 0x03.toByte()
        }
        val error = patchFailure(fixture(hermes))
        assertTrue("expected a Hermes explanation, got: ${error.message}", error.message!!.contains("Hermes"))
    }

    @Test
    fun refusesApkWithoutABundle() {
        val error = patchFailure(fixture(ByteArray(0), includeBundle = false))
        assertTrue("expected a missing-bundle explanation, got: ${error.message}", error.message!!.contains("no React Native bundle"))
    }

    /* ------------------------------------------------------------------ */

    private fun patchFailure(apk: File): PatchException {
        val output = Files.createTempDirectory("nebula-fail-out").toFile()
        return try {
            DiscordPatcher.patch(
                PatchRequest(apk, output, emptyList(), loaderSource(), identity(), "1.0.0"),
            )
            fail("patching should have been refused")
            throw IllegalStateException("unreachable")
        } catch (e: PatchException) {
            e
        }
    }

    private fun identity(): ApkIdentity = ApkSigning.identityFromKeystore(
        File(root(), "keystore/nebula-dev.jks"),
        "nebula".toCharArray(),
        "nebula",
        "nebula".toCharArray(),
    )

    private fun loaderSource(): String = File(root(), "app/src/main/assets/patcher/nebula-payload.js").readText()

    private fun root(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            if (File(dir, "keystore/nebula-dev.jks").exists()) return dir
            dir = dir.parentFile
        }
        throw IllegalStateException("cannot locate the repository root from ${System.getProperty("user.dir")}")
    }

    private fun nodeBinary(): String? {
        val path = System.getenv("PATH") ?: return null
        return path.split(File.pathSeparator)
            .mapNotNull { File(it, "node").takeIf { candidate -> candidate.canExecute() } }
            .firstOrNull()
            ?.absolutePath
    }

    private fun fixture(bundle: ByteArray, includeBundle: Boolean = true): File {
        val apk = File.createTempFile("discord-fixture", ".apk")
        ZipOutputStream(BufferedOutputStream(FileOutputStream(apk))).use { zip ->
            fun put(name: String, bytes: ByteArray, stored: Boolean) {
                val entry = ZipEntry(name)
                if (stored) {
                    entry.method = ZipEntry.STORED
                    entry.size = bytes.size.toLong()
                    entry.compressedSize = bytes.size.toLong()
                    val crc = CRC32()
                    crc.update(bytes)
                    entry.crc = crc.value
                }
                zip.putNextEntry(entry)
                zip.write(bytes)
                zip.closeEntry()
            }
            put("AndroidManifest.xml", "<manifest package=\"com.discord\"/>".toByteArray(), false)
            put("classes.dex", ByteArray(4096) { (it % 251).toByte() }, true)
            put("resources.arsc", ByteArray(2048) { 3 }, true)
            put("lib/arm64-v8a/libreactnativejni.so", ByteArray(8192) { (it % 97).toByte() }, true)
            if (includeBundle) put(bundlePath, bundle, false)
            put("res/raw/keep.txt", "keep me".toByteArray(), false)
            put("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\n".toByteArray(), false)
            put("META-INF/CERT.SF", "Signature-Version: 1.0\n".toByteArray(), false)
            put("META-INF/CERT.RSA", ByteArray(64) { 1 }, false)
        }
        return apk
    }

    private data class LocalEntry(val name: String, val method: Int, val dataOffset: Long)

    /** Walks the local file headers so entry alignment can be checked independently. */
    private fun localHeaders(apk: File): List<LocalEntry> {
        val bytes = apk.readBytes()
        val entries = ArrayList<LocalEntry>()
        var position = 0L
        while (position + 30 <= bytes.size) {
            val base = position.toInt()
            if (le32(bytes, base) != 0x04034b50L) break
            val method = le16(bytes, base + 8)
            val compressedSize = le32(bytes, base + 18)
            val nameLength = le16(bytes, base + 26)
            val extraLength = le16(bytes, base + 28)
            val name = String(bytes, base + 30, nameLength, Charsets.UTF_8)
            val dataOffset = position + 30 + nameLength + extraLength
            entries.add(LocalEntry(name, method, dataOffset))
            position = dataOffset + compressedSize
        }
        assertTrue("zip local headers must be walkable", entries.isNotEmpty())
        return entries
    }

    private fun le16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    private fun le32(bytes: ByteArray, offset: Int): Long =
        (bytes[offset].toLong() and 0xFF) or
            ((bytes[offset + 1].toLong() and 0xFF) shl 8) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 3].toLong() and 0xFF) shl 24)
}
