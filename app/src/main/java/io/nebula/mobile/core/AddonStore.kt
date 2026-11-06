package io.nebula.mobile.core

import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

enum class AddonType(val dirName: String, val extension: String) {
    PLUGIN("plugins", ".plugin.js"),
    THEME("themes", ".theme.css"),
}

data class InstalledAddon(
    val type: AddonType,
    val id: String,
    val file: File,
    val meta: AddonMeta?,
) {
    val name: String get() = meta?.name ?: id
    val version: String get() = meta?.version ?: "0.0.0"
    val author: String get() = meta?.displayAuthor ?: "Unknown"
    val description: String get() = meta?.description ?: ""
}

/**
 * On-disk store for plugins, themes and per-plugin data files.
 *
 * Layout:
 * ```
 * files/addons/plugins/<id>.plugin.js
 * files/addons/themes/<id>.theme.css
 * files/addons/data/<pluginId>/<name>.json
 * ```
 */
class AddonStore(root: File) {

    private val root: File = root
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    fun dirFor(type: AddonType): File = File(root, type.dirName)

    fun dataDir(pluginId: String): File = File(File(root, "data"), pluginId)

    fun ensureDirs() {
        dirFor(AddonType.PLUGIN).mkdirs()
        dirFor(AddonType.THEME).mkdirs()
    }

    fun addChangeListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun notifyChanged() {
        listeners.forEach { it() }
    }

    fun list(type: AddonType): List<InstalledAddon> {
        val dir = dirFor(type)
        if (!dir.exists()) return emptyList()
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(type.extension) } ?: return emptyList()
        return files
            .map { file ->
                val id = file.name.removeSuffix(type.extension)
                val meta = try {
                    MetaParser.parse(file.readText())
                } catch (e: Exception) {
                    null
                }
                InstalledAddon(type, id, file, meta)
            }
            .sortedBy { it.name.lowercase() }
    }

    fun find(type: AddonType, id: String): InstalledAddon? = list(type).firstOrNull { it.id == id }

    fun read(addon: InstalledAddon): String = addon.file.readText()

    /**
     * Installs an addon from its raw source. The META header decides the canonical id,
     * so re-installing an updated version replaces the previous file.
     */
    fun install(type: AddonType, source: String, fallbackName: String? = null): InstalledAddon {
        val meta = MetaParser.parse(source)
            ?: throw IllegalArgumentException(
                "Missing BetterDiscord META header. Add a /** @name ... */ block at the top of the file.",
            )
        val id = slug(meta.name.ifBlank { fallbackName ?: "addon" })
        ensureDirs()
        val target = File(dirFor(type), id + type.extension)
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeText(normalize(source))
        if (target.exists() && !target.delete()) {
            tmp.delete()
            throw IllegalStateException("Unable to replace ${target.name}")
        }
        if (!tmp.renameTo(target)) {
            target.writeText(normalize(source))
            tmp.delete()
        }
        notifyChanged()
        return InstalledAddon(type, id, target, meta)
    }

    fun delete(addon: InstalledAddon): Boolean {
        val removed = addon.file.delete()
        if (addon.type == AddonType.PLUGIN) {
            File(root, "data/${addon.id}").deleteRecursively()
        }
        notifyChanged()
        return removed
    }

    fun dataFile(pluginId: String, name: String): File {
        val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "data.json" }
        val dir = dataDir(pluginId)
        dir.mkdirs()
        return File(dir, if (safe.endsWith(".json")) safe else "$safe.json")
    }

    fun readData(pluginId: String, name: String): String {
        val file = dataFile(pluginId, name)
        return if (file.exists()) file.readText() else ""
    }

    fun writeData(pluginId: String, name: String, json: String): Boolean = try {
        val file = dataFile(pluginId, name)
        file.writeText(json)
        true
    } catch (e: Exception) {
        NebulaLog.error("store", "Unable to write data for $pluginId: ${e.message}")
        false
    }

    fun deleteData(pluginId: String, name: String): Boolean = try {
        dataFile(pluginId, name).delete()
        true
    } catch (e: Exception) {
        false
    }

    /** Copies bundled sample addons into user storage the first time the app runs. */
    fun seedIfEmpty(seed: () -> Map<AddonType, List<String>>, alreadySeeded: Boolean) {
        if (alreadySeeded) return
        ensureDirs()
        val bundled = seed()
        for ((type, sources) in bundled) {
            for (source in sources) {
                try {
                    val meta = MetaParser.parse(source) ?: continue
                    val id = slug(meta.name)
                    val target = File(dirFor(type), id + type.extension)
                    if (!target.exists()) {
                        target.writeText(normalize(source))
                    }
                } catch (e: Exception) {
                    NebulaLog.warn("store", "Skipped bundled ${type.dirName} addon: ${e.message}")
                }
            }
        }
        notifyChanged()
    }

    private fun normalize(source: String): String =
        if (source.endsWith("\n")) source else source + "\n"

    companion object {
        fun slug(value: String): String {
            val slug = value.lowercase()
                .replace(Regex("[^a-z0-9]+"), "-")
                .trim('-')
            return slug.ifBlank { "addon" }
        }
    }
}
