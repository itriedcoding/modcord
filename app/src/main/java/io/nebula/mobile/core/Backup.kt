package io.nebula.mobile.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * Backup and restore, in a documented JSON format.
 *
 * A backup contains the settings document, the state document (enabled plugins, active
 * theme, per-plugin settings) and the full source of every installed addon, so a restore
 * works on a device that never had those addons. Encoding and decoding are pure functions
 * over strings, which is what the unit tests exercise; [BackupManager] is the thin part
 * that reads the live app and writes the files.
 */
object Backup {

    const val KIND = "nebula-backup"
    const val FORMAT = 1

    data class AddonSnapshot(
        val type: String,
        val id: String,
        val name: String,
        val version: String,
        val source: String,
    )

    data class Bundle(
        val settings: JSONObject,
        val state: JSONObject,
        val addons: List<AddonSnapshot>,
        val createdAt: Long,
        val createdWith: String,
    )

    fun encode(
        settings: JSONObject,
        state: JSONObject,
        addons: List<AddonSnapshot>,
        createdAt: Long,
        createdWith: String,
    ): String {
        val root = JSONObject()
        root.put("kind", KIND)
        root.put("format", FORMAT)
        root.put("createdAt", createdAt)
        root.put("createdWith", createdWith)
        root.put("settings", settings)
        root.put("state", state)
        val array = JSONArray()
        for (addon in addons) {
            array.put(
                JSONObject()
                    .put("type", addon.type)
                    .put("id", addon.id)
                    .put("name", addon.name)
                    .put("version", addon.version)
                    .put("source", addon.source),
            )
        }
        root.put("addons", array)
        return root.toString(2)
    }

    fun decode(text: String): Bundle {
        val root = try {
            JSONObject(text)
        } catch (e: Exception) {
            throw IllegalArgumentException("That file is not valid JSON: ${e.message}")
        }
        val kind = root.optString("kind")
        if (kind != KIND) {
            throw IllegalArgumentException("That file is not a Nebula backup (kind=\"$kind\").")
        }
        val format = root.optInt("format", 0)
        if (format > FORMAT) {
            throw IllegalArgumentException("This backup was written by a newer Nebula (format $format).")
        }
        val addons = ArrayList<AddonSnapshot>()
        val array = root.optJSONArray("addons") ?: JSONArray()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val source = item.optString("source")
            val id = item.optString("id")
            if (source.isBlank() || id.isBlank()) continue
            addons.add(
                AddonSnapshot(
                    type = item.optString("type", AddonType.PLUGIN.dirName),
                    id = id,
                    name = item.optString("name", id),
                    version = item.optString("version", "0.0.0"),
                    source = source,
                ),
            )
        }
        return Bundle(
            settings = root.optJSONObject("settings") ?: JSONObject(),
            state = root.optJSONObject("state") ?: JSONObject(),
            addons = addons,
            createdAt = root.optLong("createdAt", 0L),
            createdWith = root.optString("createdWith", "unknown"),
        )
    }

    fun typeOf(snapshot: AddonSnapshot): AddonType =
        if (snapshot.type == AddonType.THEME.dirName) AddonType.THEME else AddonType.PLUGIN
}
