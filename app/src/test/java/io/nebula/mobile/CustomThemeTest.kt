package io.nebula.mobile

import io.nebula.mobile.core.MetaParser
import io.nebula.mobile.theme.CustomTheme
import io.nebula.mobile.theme.ThemeTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomThemeTest {

    @Test
    fun buildsAThemeThatParsesAndResolvesLikeAnImportedOne() {
        val css = CustomTheme.build("My Test Theme", "#a855f7", "#0b0716", "#ece6ff")
        val meta = MetaParser.parse(css)
        assertEquals("My Test Theme", meta?.name)
        assertEquals("1.0.0", meta?.version)
        assertEquals("You", meta?.author)

        val tokens = ThemeTokens.resolve(MetaParser.stripMeta(css))
        assertEquals("#0B0716", tokens["backgroundPrimary"])
        assertEquals("#A855F7", tokens["brandExperiment"])
        assertEquals("#A855F7", tokens["brand"])
        assertEquals("#ECE6FF", tokens["textNormal"])
        assertNotEquals("derived secondary must differ", tokens["backgroundPrimary"], tokens["backgroundSecondary"])
    }

    @Test
    fun rejectsColoursThatAreNotSixDigitHex() {
        val error = runCatching { CustomTheme.build("Bad", "purple", "#000000", "#ffffff") }.exceptionOrNull()
        assertTrue("got: ${error?.message}", error is IllegalArgumentException)
        assertTrue(error!!.message!!.contains("accent"))
        assertTrue(runCatching { CustomTheme.normalize("#12", "background") }.isFailure)
    }

    @Test
    fun blankNamesFallBackAndMixInterpolates() {
        val css = CustomTheme.build("   ", "#ffffff", "#000000", "#ffffff")
        assertEquals("My Theme", MetaParser.parse(css)?.name)
        assertEquals("#7F7F7F", CustomTheme.mix("#000000", "#FFFFFF", 0.5))
        assertEquals("#000000", CustomTheme.mix("#000000", "#FFFFFF", 0.0))
        assertEquals("#FFFFFF", CustomTheme.mix("#000000", "#FFFFFF", 1.0))
    }
}
