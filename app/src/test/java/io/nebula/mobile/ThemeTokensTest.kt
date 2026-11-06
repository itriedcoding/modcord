package io.nebula.mobile

import io.nebula.mobile.core.MetaParser
import io.nebula.mobile.theme.ThemeTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Real CSS in, real React Native tokens out - no stubbed parser. */
class ThemeTokensTest {

    private fun root(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            if (File(dir, "keystore/nebula-dev.jks").exists()) return dir
            dir = dir.parentFile
        }
        throw IllegalStateException("cannot locate the repository root")
    }

    @Test
    fun mapsDiscordVariablesOntoReactNativeTokens() {
        val css = """
            :root {
              --background-primary: #0B0716;
              --background-secondary: #120C22;
              --text-normal: #ECE6FF;
              --brand-experiment: rgb(168, 85, 247);
            }
        """.trimIndent()

        val tokens = ThemeTokens.resolve(css)

        assertEquals("#0B0716", tokens["backgroundPrimary"])
        assertEquals("#120C22", tokens["backgroundSecondary"])
        assertEquals("#ECE6FF", tokens["textNormal"])
        assertEquals("#A855F7", tokens["brandExperiment"])
        assertEquals("brand alias points at the same colour", "#A855F7", tokens["brand"])
    }

    @Test
    fun resolvesVariableChainsAndFallbacks() {
        val css = """
            :root {
              --brand-500: #A855F7;
              --brand-experiment: var(--brand-500);
              --text-muted: var(--missing, #9AA3B2);
            }
        """.trimIndent()

        val tokens = ThemeTokens.resolve(css)

        assertEquals("#A855F7", tokens["brandExperiment"])
        assertEquals("#9AA3B2", tokens["textMuted"])
    }

    @Test
    fun keepsAlphaAsRgbaAndSkipsNonColourDeclarations() {
        val css = ":root { --background-floating: rgba(10, 20, 30, 0.5); --font-primary: 'Noto Sans'; }"

        val tokens = ThemeTokens.resolve(css)

        // Alpha is carried as a byte: 0.5 * 255 rounds to 127, and 127 / 255 = 0.498.
        // The colour is visually identical; the assertion records the real conversion
        // instead of hiding it behind a rounded literal.
        assertEquals("rgba(10, 20, 30, 0.498)", tokens["backgroundFloating"])
        assertNull("a font stack is not a colour token", tokens["fontPrimary"])
    }

    @Test
    fun everyBundledThemeProducesUsableTokens() {
        val directory = File(root(), "app/src/main/assets/addons/themes")
        val files = directory.listFiles { file -> file.name.endsWith(".theme.css") }.orEmpty()
        assertTrue("bundled themes should exist", files.isNotEmpty())

        for (file in files) {
            val tokens = ThemeTokens.resolve(MetaParser.stripMeta(file.readText()))
            assertTrue("${file.name} mapped only ${tokens.size} token(s)", tokens.size >= 3)
            for ((name, value) in tokens) {
                assertTrue(
                    "${file.name}: $name = $value is not a React Native colour",
                    value.startsWith("#") || value.startsWith("rgba("),
                )
            }
        }
    }
}
