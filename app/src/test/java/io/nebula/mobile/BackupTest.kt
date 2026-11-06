package io.nebula.mobile

import io.nebula.mobile.core.AddonType
import io.nebula.mobile.core.Backup
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupTest {

    private val settings = JSONObject().put("animations", false).put("fontFamily", "serif")
    private val state = JSONObject().put("activeTheme", "midnightpurple")
    private val addons = listOf(
        Backup.AddonSnapshot(
            "plugins",
            "hello-world",
            "HelloWorld",
            "1.0.0",
            "/**\n * @name HelloWorld\n */\nmodule.exports = {};",
        ),
        Backup.AddonSnapshot(
            "themes",
            "midnightpurple",
            "MidnightPurple",
            "1.0.0",
            "/**\n * @name MidnightPurple\n */\n:root { --background-primary: #0b0716; }",
        ),
    )

    @Test
    fun roundTripsSettingsStateAndAddonSources() {
        val text = Backup.encode(settings, state, addons, createdAt = 42L, createdWith = "1.5.0")
        val bundle = Backup.decode(text)
        assertEquals(false, bundle.settings.getBoolean("animations"))
        assertEquals("serif", bundle.settings.getString("fontFamily"))
        assertEquals("midnightpurple", bundle.state.getString("activeTheme"))
        assertEquals(2, bundle.addons.size)
        assertEquals("1.5.0", bundle.createdWith)
        assertEquals(42L, bundle.createdAt)
        assertTrue(bundle.addons.first().source.contains("@name HelloWorld"))
    }

    @Test
    fun refusesFilesThatAreNotBackups() {
        val error = runCatching { Backup.decode("{\"kind\":\"something-else\"}") }.exceptionOrNull()
        assertTrue("got: ${error?.message}", error is IllegalArgumentException)
        assertTrue(error!!.message!!.contains("not a Nebula backup"))
    }

    @Test
    fun refusesNewerFormatsAndInvalidJson() {
        val newer = Backup.encode(settings, state, addons, 0L, "9.0.0").replace("\"format\": 1", "\"format\": 99")
        val error = runCatching { Backup.decode(newer) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException && error.message!!.contains("newer Nebula"))
        assertTrue(runCatching { Backup.decode("not json") }.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun skipsEntriesWithoutSourceAndMapsTypes() {
        val text = Backup.encode(settings, state, listOf(Backup.AddonSnapshot("themes", "x", "X", "1", "")), 0L, "1.5.0")
        assertEquals(0, Backup.decode(text).addons.size)
        assertEquals(
            AddonType.THEME,
            Backup.typeOf(Backup.AddonSnapshot("themes", "x", "X", "1", "/**  */")),
        )
    }
}
