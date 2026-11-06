package io.nebula.mobile.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Persisted application state: which plugins are enabled, which theme is active,
 * per-plugin settings and the (optional) accent override installed by a plugin.
 */
class StateStore(private val file: File) {

    private val lock = Any()
    private var root: JSONObject = read()

    private fun read(): JSONObject = try {
        if (file.exists()) JSONObject(file.readText()) else JSONObject()
    } catch (e: Exception) {
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
            NebulaLog.error("state", "Unable to persist state: ${e.message}")
        }
    }

    fun isEnabled(type: AddonType, id: String): Boolean = synchronized(lock) {
        val key = if (type == AddonType.PLUGIN) "enabledPlugins" else "enabledThemes"
        val array = root.optJSONArray(key) ?: return false
        for (i in 0 until array.length()) {
            if (array.optString(i) == id) return true
        }
        false
    }

    fun setEnabled(type: AddonType, id: String, enabled: Boolean) = synchronized(lock) {
        val key = if (type == AddonType.PLUGIN) "enabledPlugins" else "enabledThemes"
        val current = root.optJSONArray(key) ?: JSONArray()
        val next = JSONArray()
        for (i in 0 until current.length()) {
            val value = current.optString(i)
            if (value != id) next.put(value)
        }
        if (enabled) next.put(id)
        root.put(key, next)
        persist()
    }

    var activeTheme: String?
        get() = synchronized(lock) { root.optString("activeTheme").takeIf { it.isNotBlank() } }
        set(value) = synchronized(lock) {
            if (value == null) root.remove("activeTheme") else root.put("activeTheme", value)
            persist()
        }

    var accentOverride: String?
        get() = synchronized(lock) { root.optString("accentOverride").takeIf { it.isNotBlank() } }
        set(value) = synchronized(lock) {
            if (value == null) root.remove("accentOverride") else root.put("accentOverride", value)
            persist()
        }

    var seeded: Boolean
        get() = synchronized(lock) { root.optBoolean("seeded", false) }
        set(value) = synchronized(lock) {
            root.put("seeded", value)
            persist()
        }

    fun settings(id: String): JSONObject = synchronized(lock) {
        root.optJSONObject("settings")?.optJSONObject(id) ?: JSONObject()
    }

    fun putSetting(id: String, key: String, value: Any?) = synchronized(lock) {
        val all = root.optJSONObject("settings") ?: JSONObject().also { root.put("settings", it) }
        val mine = all.optJSONObject(id) ?: JSONObject().also { all.put(id, it) }
        if (value == null) mine.remove(key) else mine.put(key, value)
        persist()
    }

    fun settingsSchema(id: String): String? = synchronized(lock) {
        root.optJSONObject("schemas")?.optString(id)?.takeIf { it.isNotBlank() }
    }

    fun putSettingsSchema(id: String, schemaJson: String) = synchronized(lock) {
        val all = root.optJSONObject("schemas") ?: JSONObject().also { root.put("schemas", it) }
        all.put(id, schemaJson)
        persist()
    }
}
