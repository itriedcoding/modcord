package io.nebula.mobile.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MetaParserTest {

    @Test
    fun `parses a jsdoc meta header`() {
        val source = """
            /**
             * @name ExamplePlugin
             * @author Someone
             * @description Does a thing.
             * @version 2.1.0
             * @website https://example.com
             * @source https://github.com/example/plugin
             */
            module.exports = class Example {};
        """.trimIndent()

        val meta = MetaParser.parse(source)
        assertNotNull(meta)
        assertEquals("ExamplePlugin", meta!!.name)
        assertEquals("Someone", meta.author)
        assertEquals("Does a thing.", meta.description)
        assertEquals("2.1.0", meta.version)
        assertEquals("https://example.com", meta.website)
        assertEquals("https://github.com/example/plugin", meta.source)
    }

    @Test
    fun `multi line descriptions are preserved`() {
        val source = """
            /**
             * @name Multi
             * @author A
             * @description First line.
             * Second line.
             * @version 1.0.0
             */
            module.exports = class Multi {};
        """.trimIndent()
        val meta = MetaParser.parse(source)!!
        assertEquals("First line.\nSecond line.", meta.description)
    }

    @Test
    fun `parses the legacy json header`() {
        val source = """//META{"name":"OldPlugin","author":"Ancient","description":"Legacy","version":"0.0.1"}*//"""
        val meta = MetaParser.parse(source)
        assertNotNull(meta)
        assertEquals("OldPlugin", meta!!.name)
        assertEquals("Ancient", meta.author)
    }

    @Test
    fun `rejects files without a name`() {
        assertNull(MetaParser.parse("console.log('hello');"))
        assertNull(MetaParser.parse("/** @author nobody */\nconsole.log(1);"))
    }

    @Test
    fun `detects plugin and theme sources`() {
        val plugin = "/** @name P */\nmodule.exports = class P { start() {} stop() {} };"
        val theme = "/** @name T */\n:root { --background-primary: #000; }"
        assertTrue(MetaParser.isPluginSource(plugin))
        assertFalse(MetaParser.isPluginSource(theme))
        assertTrue(MetaParser.isThemeSource(theme))
        assertFalse(MetaParser.isThemeSource(plugin))
    }

    @Test
    fun `every bundled addon has a valid header`() {
        val dirs = listOf(
            File("src/main/assets/addons/plugins"),
            File("src/main/assets/addons/themes"),
        )
        for (dir in dirs) {
            val files = dir.listFiles() ?: error("missing ${dir.path}")
            assertTrue("no bundled files in ${dir.path}", files.isNotEmpty())
            for (file in files) {
                val meta = MetaParser.parse(file.readText())
                assertNotNull("${file.name} has no parsable META header", meta)
                assertTrue("${file.name} has an empty name", meta!!.name.isNotBlank())
            }
        }
    }

    @Test
    fun `slug is stable and filesystem safe`() {
        assertEquals("example-plugin", AddonStore.slug("Example Plugin"))
        assertEquals("a-b-c", AddonStore.slug("  A  B  C  "))
        assertEquals("addon", AddonStore.slug("!!!"))
    }
}
