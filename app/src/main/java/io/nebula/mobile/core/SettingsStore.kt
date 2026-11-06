package io.nebula.mobile.core

import org.json.JSONObject
import java.io.File

/**
 * Typed, persisted app settings.
 *
 * This is the app's local settings storage: a single JSON file written atomically
 * (temp file + rename), so a crash mid-write cannot leave half a document behind.
 * Unknown keys are preserved on purpose, because [Backup] restores documents that may
 * have been written by a newer build.
 */
class SettingsStore(private val file: File) {

    private val lock = Any()
    private var root: JSONObject = load()

    private fun load(): JSONObject = try {
        if (file.exists()) JSONObject(file.readText()) else JSONObject()
    } catch (e: Exception) {
        NebulaLog.warn("settings", "Ignoring unreadable settings file: ${e.message}")
        JSONObject()
    }

    private fun persist() {
        try {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(root.toString(2))
            if (!tmp.renameTo(file)) {
                file.writeText(root.toString(2))
                tmp.delete()
            }
        } catch (e: Exception) {
            NebulaLog.error("settings", "Unable to save settings: ${e.message}")
        }
    }

    /** A detached copy, used by the backup writer and by settings tests. */
    fun snapshot(): JSONObject = synchronized(lock) { JSONObject(root.toString()) }

    fun replaceAll(json: JSONObject) = synchronized(lock) {
        root = JSONObject(json.toString())
        persist()
    }

    /** System font family name, or null for the platform default. */
    var fontFamily: String?
        get() = synchronized(lock) { root.optString("fontFamily").takeIf { it.isNotBlank() } }
        set(value) = synchronized(lock) {
            if (value.isNullOrBlank()) root.remove("fontFamily") else root.put("fontFamily", value)
            persist()
        }

    var animations: Boolean
        get() = synchronized(lock) { root.optBoolean("animations", true) }
        set(value) = synchronized(lock) {
            root.put("animations", value)
            persist()
        }

    var notifyOnPatch: Boolean
        get() = synchronized(lock) { root.optBoolean("notifyOnPatch", true) }
        set(value) = synchronized(lock) {
            root.put("notifyOnPatch", value)
            persist()
        }

    /** NotificationChannel importance, clamped to [1, 4] (min, low, default, high). */
    var notificationImportance: Int
        get() = synchronized(lock) { root.optInt("notificationImportance", 3).coerceIn(1, 4) }
        set(value) = synchronized(lock) {
            root.put("notificationImportance", value.coerceIn(1, 4))
            persist()
        }

    var autoCheckUpdates: Boolean
        get() = synchronized(lock) { root.optBoolean("autoCheckUpdates", true) }
        set(value) = synchronized(lock) {
            root.put("autoCheckUpdates", value)
            persist()
        }

    var lastUpdateCheck: Long
        get() = synchronized(lock) { root.optLong("lastUpdateCheck", 0L) }
        set(value) = synchronized(lock) {
            root.put("lastUpdateCheck", value)
            persist()
        }

    /** Remote tag last reported to the user, so one release is only announced once. */
    var lastNotifiedUpdate: String?
        get() = synchronized(lock) { root.optString("lastNotifiedUpdate").takeIf { it.isNotBlank() } }
        set(value) = synchronized(lock) {
            if (value.isNullOrBlank()) root.remove("lastNotifiedUpdate") else root.put("lastNotifiedUpdate", value)
            persist()
        }
}
