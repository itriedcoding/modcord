package io.nebula.mobile

import io.nebula.mobile.core.AddonType
import io.nebula.mobile.core.InstalledAddon
import io.nebula.mobile.core.NebulaLog

data class AddonRow(
    val addon: InstalledAddon,
    val enabled: Boolean,
    val running: Boolean,
    val error: String?,
)

/** Coordinates the addon store, the JS runtime and persisted state. */
class PluginManager(private val app: NebulaApp) {

    private val errors = HashMap<String, String>()

    fun rows(type: AddonType): List<AddonRow> = app.store.list(type).map { addon ->
        AddonRow(
            addon = addon,
            enabled = app.state.isEnabled(type, addon.id),
            running = type == AddonType.PLUGIN && app.host.isRunning(addon.id),
            error = errors[addon.id],
        )
    }

    fun row(pluginId: String): AddonRow? = rows(AddonType.PLUGIN).firstOrNull { it.addon.id == pluginId }

    fun setEnabled(pluginId: String, enabled: Boolean, onDone: ((Result<Unit>) -> Unit)? = null) {
        val addon = app.store.find(AddonType.PLUGIN, pluginId)
        if (addon == null) {
            onDone?.invoke(Result.failure(IllegalArgumentException("Unknown plugin: $pluginId")))
            return
        }
        app.state.setEnabled(AddonType.PLUGIN, pluginId, enabled)
        if (!enabled) {
            errors.remove(pluginId)
            app.host.stop(pluginId).whenComplete { _, _ ->
                app.notifyAddonsChanged()
                onDone?.invoke(Result.success(Unit))
            }
            return
        }
        val source = try {
            app.store.read(addon)
        } catch (e: Exception) {
            val message = "Cannot read ${addon.file.name}: ${e.message}"
            errors[pluginId] = message
            NebulaLog.error("manager", message)
            app.notifyAddonsChanged()
            onDone?.invoke(Result.failure(RuntimeException(message)))
            return
        }
        app.host.start(addon.id, addon.name, addon.meta, source).whenComplete { result, throwable ->
            if (throwable == null && result.isSuccess) {
                errors.remove(pluginId)
            } else {
                val message = result.exceptionOrNull()?.message ?: throwable?.message ?: "Unknown error"
                errors[pluginId] = message
                NebulaLog.error("manager", "${addon.name} failed: $message")
            }
            app.notifyAddonsChanged()
            onDone?.invoke(result)
        }
    }

    fun setThemeActive(themeId: String?) {
        app.state.activeTheme = themeId
        if (themeId != null) {
            app.state.setEnabled(AddonType.THEME, themeId, true)
        }
        app.onThemeChanged()
        app.store.notifyChanged()
    }

    fun recordError(pluginId: String, message: String?) {
        if (message == null) errors.remove(pluginId) else errors[pluginId] = message
    }

    /** Saves a value written by the native settings UI and notifies the plugin. */
    fun writeSetting(pluginId: String, key: String, value: Any?) {
        app.state.putSetting(pluginId, key, value)
        app.host.notifySettingsChanged(pluginId)
    }

    fun forget(pluginId: String) {
        errors.remove(pluginId)
    }

    companion object {
        fun subtitle(addon: InstalledAddon): String = "${addon.version} · ${addon.author}"
    }
}
