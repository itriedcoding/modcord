package io.nebula.mobile

import io.nebula.mobile.core.SettingsStore
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Settings are a real file on disk, so these tests write and re-read real files. */
class SettingsTest {

    private fun tempFile(): File =
        File(Files.createTempDirectory("nebula-settings").toFile(), "settings.json")

    @Test
    fun defaultsHoldUntilChanged() {
        val store = SettingsStore(tempFile())
        assertNull(store.fontFamily)
        assertTrue(store.animations)
        assertTrue(store.notifyOnPatch)
        assertTrue(store.autoCheckUpdates)
        assertEquals(3, store.notificationImportance)
        assertEquals(0L, store.lastUpdateCheck)
    }

    @Test
    fun valuesSurviveANewInstance() {
        val file = tempFile()
        SettingsStore(file).apply {
            fontFamily = "serif"
            animations = false
            notifyOnPatch = false
            notificationImportance = 4
            autoCheckUpdates = false
            lastUpdateCheck = 1234L
            lastNotifiedUpdate = "v9.9.9"
        }
        val reloaded = SettingsStore(file)
        assertEquals("serif", reloaded.fontFamily)
        assertEquals(false, reloaded.animations)
        assertEquals(false, reloaded.notifyOnPatch)
        assertEquals(4, reloaded.notificationImportance)
        assertEquals(false, reloaded.autoCheckUpdates)
        assertEquals(1234L, reloaded.lastUpdateCheck)
        assertEquals("v9.9.9", reloaded.lastNotifiedUpdate)
    }

    @Test
    fun clampsImportanceAndKeepsKeysItDoesNotKnow() {
        val file = tempFile()
        val store = SettingsStore(file)
        store.notificationImportance = 9
        assertEquals(4, store.notificationImportance)
        store.notificationImportance = -3
        assertEquals(1, store.notificationImportance)

        store.replaceAll(JSONObject(store.snapshot().toString()).put("futureKey", "kept"))
        store.animations = false
        assertEquals("kept", SettingsStore(file).snapshot().optString("futureKey"))
    }
}
