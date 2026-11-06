package io.nebula.mobile.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CssParserTest {

    @Test
    fun `parses declarations and skips comments`() {
        val css = """
            /* a leading comment with { braces } inside */
            :root {
                --background-primary: #101010;
                --text-normal: #ffffff; /* trailing */
            }
            .theme-dark .container { color: red; }
        """.trimIndent()

        val rules = CssParser.parse(css)
        assertEquals(2, rules.size)
        assertEquals(listOf(":root"), rules[0].selectors)
        assertEquals("#101010", rules[0].declarations["--background-primary"])
        assertEquals("#ffffff", rules[0].declarations["--text-normal"])
        assertEquals("red", rules[1].declarations["color"])
    }

    @Test
    fun `resolves var references including fallbacks`() {
        val css = """
            :root {
                --base: #123456;
                --alias: var(--base);
                --chained: var(--alias);
                --missing: var(--nope, #abcdef);
                --fallback-chain: var(--nope, var(--base));
            }
        """.trimIndent()

        val props = CssParser.resolveVariables(CssParser.customProperties(CssParser.parse(css)))
        assertEquals("#123456", props["--alias"])
        assertEquals("#123456", props["--chained"])
        assertEquals("#abcdef", props["--missing"])
        assertEquals("#123456", props["--fallback-chain"])
    }

    @Test
    fun `parses media blocks and selectors with commas`() {
        val css = """
            @media (max-width: 100px) {
                .a, .b { color: blue; }
            }
            #id > .child::before { content: "a;b"; }
        """.trimIndent()

        val rules = CssParser.parse(css)
        assertEquals(2, rules.size)
        assertEquals(listOf(".a", ".b"), rules[0].selectors)
        assertEquals("blue", rules[0].declarations["color"])
        assertEquals("\"a;b\"", rules[1].declarations["content"])
    }

    @Test
    fun `bundled themes resolve to usable palettes`() {
        val themes = File("src/main/assets/addons/themes").listFiles { file -> file.name.endsWith(".theme.css") }
        assertTrue("expected bundled themes", !themes.isNullOrEmpty())
        for (file in themes!!) {
            val rules = CssParser.parse(file.readText())
            val variables = CssParser.resolveVariables(CssParser.customProperties(rules))
            assertTrue("${file.name} declares no variables", variables.isNotEmpty())

            val palette = Palette.fromVariables(variables)
            val background = Palette.pick(variables, listOf("--background-primary"))
            assertNotNull("${file.name} has no --background-primary", background)
            assertEquals(background, palette.background)
            assertTrue("${file.name} produced a transparent background", palette.background ushr 24 == 0xFF)
        }
    }

    @Test
    fun `midnight purple palette maps to the expected colours`() {
        val source = File("src/main/assets/addons/themes/midnight-purple.theme.css").readText()
        val variables = CssParser.resolveVariables(CssParser.customProperties(CssParser.parse(source)))
        val palette = Palette.fromVariables(variables)
        assertEquals(0xFF0B0716.toInt(), palette.background)
        assertEquals(0xFF120C22.toInt(), palette.surface)
        assertEquals(0xFFA855F7.toInt(), palette.accent)
        assertTrue(palette.isDark)
    }

    @Test
    fun `solar light palette is detected as light`() {
        val source = File("src/main/assets/addons/themes/solar-light.theme.css").readText()
        val variables = CssParser.resolveVariables(CssParser.customProperties(CssParser.parse(source)))
        val palette = Palette.fromVariables(variables)
        assertEquals(0xFFD97706.toInt(), palette.accent)
        assertTrue("light theme should be light", !palette.isDark)
    }

    @Test
    fun `colours parse from hex rgb and hsl`() {
        assertEquals(0xFFFF0000.toInt(), Colors.parse("#ff0000"))
        assertEquals(0xFFFF0000.toInt(), Colors.parse("#f00"))
        assertEquals(0xFF00FF00.toInt(), Colors.parse("rgb(0, 255, 0)"))
        assertEquals(0x7F0000FF.toInt(), Colors.parse("rgba(0, 0, 255, 0.5)"))
        assertEquals(0xFFFF0000.toInt(), Colors.parse("hsl(0, 100%, 50%)"))
        assertEquals(null, Colors.parse("not-a-colour"))
    }
}
