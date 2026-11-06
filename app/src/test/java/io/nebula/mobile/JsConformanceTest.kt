package io.nebula.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Runs `tools/js-runtime-test.mjs`, which loads the exact JavaScript assets the
 * APK ships (`bdapi/prelude.js`, `bdapi/bdapi.js`, `bdapi/bootstrap.js`,
 * `bdapi/teardown.js`) plus every bundled plugin, in a real JavaScript engine,
 * against the same host contract `AndroidBridge` implements.
 *
 * Requires Node.js 18+ on PATH (it is only needed for tests, not for building
 * or running the app).
 */
class JsConformanceTest {

    @Test
    fun `javascript runtime assets conform to the host contract`() {
        val script = File("../tools/js-runtime-test.mjs")
        assertTrue("Missing conformance script at ${script.absolutePath}", script.exists())

        val process = try {
            ProcessBuilder("node", script.absolutePath)
                .directory(File(".."))
                .redirectErrorStream(true)
                .start()
        } catch (e: Exception) {
            throw AssertionError("Node.js is required to run the JavaScript conformance tests: ${e.message}")
        }

        val output = process.inputStream.bufferedReader().use { it.readText() }
        val finished = process.waitFor(180, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            throw AssertionError("JavaScript conformance tests timed out\n$output")
        }
        assertEquals("JavaScript conformance tests failed\n$output", 0, process.exitValue())
        assertTrue("Conformance suite did not report results\n$output", output.contains("passed,"))

        println(output)
    }
}
