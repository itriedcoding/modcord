package io.nebula.mobile

import android.app.Application
import android.content.Context
import android.util.Log
import io.nebula.mobile.android.AndroidBridge
import io.nebula.mobile.core.AddonStore
import io.nebula.mobile.core.AddonType
import io.nebula.mobile.core.InstalledAddon
import io.nebula.mobile.core.NebulaLog
import io.nebula.mobile.core.StateStore
import io.nebula.mobile.js.BdApiAssets
import io.nebula.mobile.js.PluginHost
import io.nebula.mobile.theme.Colors
import io.nebula.mobile.theme.Palette
import io.nebula.mobile.theme.ThemeEngine
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Single owned instance of everything the app needs: addon storage, persisted
 * state, the JavaScript runtime, the active theme and the plugin manager.
 */
class NebulaApp(context: Context) {

    val store = AddonStore(File(context.filesDir, "addons"))
    val state = StateStore(File(context.filesDir, "state.json"))
    private val themeEngine = ThemeEngine()
    private val bridge = AndroidBridge(context, this)
    val host = PluginHost(
        bridge,
        BdApiAssets(
            prelude = readAsset(context, "bdapi/prelude.js"),
            bdApi = readAsset(context, "bdapi/bdapi.js"),
            bootstrap = readAsset(context, "bdapi/bootstrap.js"),
            teardown = readAsset(context, "bdapi/teardown.js"),
        ),
    )
    val plugins = PluginManager(this)

    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private val addonListeners = CopyOnWriteArrayList<() -> Unit>()
    private val paletteListeners = CopyOnWriteArrayList<(Palette) -> Unit>()

    @Volatile
    private var cachedPalette: Palette? = null

    init {
        NebulaLog.sink = { entry ->
            val priority = when (entry.level) {
                NebulaLog.Level.ERROR -> Log.ERROR
                NebulaLog.Level.WARN -> Log.WARN
                NebulaLog.Level.DEBUG -> Log.DEBUG
                else -> Log.INFO
            }
            Log.println(priority, "Nebula/${entry.scope}", entry.message)
        }
        store.addChangeListener { notifyAddonsChanged() }
        seedBundledAddons(context)
        restoreEnabledPlugins()
        NebulaLog.info("app", "Nebula ${BuildConfig.VERSION_NAME} started")
    }

    /* ---------------------------------------------------------------- */
    /* Palette                                                          */
    /* ---------------------------------------------------------------- */

    fun palette(): Palette {
        cachedPalette?.let { return it }
        val computed = computePalette()
        cachedPalette = computed
        return computed
    }

    private fun computePalette(): Palette {
        val activeTheme = state.activeTheme?.let { id -> store.find(AddonType.THEME, id) }
        var palette = themeEngine.paletteFor(activeTheme)
        val accent = state.accentOverride?.let { Colors.parse(it) }
        if (accent != null) {
            palette = palette.copy(
                accent = accent,
                onAccent = if (Palette.luminance(accent) > 0.55) 0xFF11131A.toInt() else 0xFFFFFFFF.toInt(),
            )
        }
        return palette
    }

    fun paletteForTheme(themeId: String?): Palette =
        themeEngine.paletteFor(themeId?.let { store.find(AddonType.THEME, it) })

    fun activeThemeId(): String? = state.activeTheme

    fun setAccentOverride(hex: String?): Boolean {
        if (hex != null && Colors.parse(hex) == null) {
            NebulaLog.warn("theme", "Ignoring invalid accent colour: $hex")
            return false
        }
        state.accentOverride = hex
        invalidatePalette()
        return true
    }

    fun onThemeChanged() = invalidatePalette()

    private fun invalidatePalette() {
        cachedPalette = null
        val palette = palette()
        main.post { paletteListeners.forEach { listener -> listener(palette) } }
    }

    /* ---------------------------------------------------------------- */
    /* Addons                                                           */
    /* ---------------------------------------------------------------- */

    fun importAddon(type: AddonType, source: String, fallbackName: String? = null): InstalledAddon {
        val addon = store.install(type, source, fallbackName)
        NebulaLog.info("store", "Installed ${type.dirName} addon ${addon.name} (${addon.version})")
        return addon
    }

    fun setPluginEnabled(pluginId: String, enabled: Boolean) {
        plugins.setEnabled(pluginId, enabled)
    }

    fun deleteAddon(addon: InstalledAddon) {
        if (addon.type == AddonType.PLUGIN) {
            host.stop(addon.id)
        } else if (state.activeTheme == addon.id) {
            state.activeTheme = null
            invalidatePalette()
        }
        state.setEnabled(addon.type, addon.id, false)
        store.delete(addon)
        plugins.forget(addon.id)
    }

    fun onRuntimeChanged(pluginId: String, running: Boolean, error: String?) {
        // A plugin that just started successfully has no error to report any more.
        plugins.recordError(pluginId, if (running) null else error)
        notifyAddonsChanged()
    }

    /* ---------------------------------------------------------------- */
    /* Listeners                                                        */
    /* ---------------------------------------------------------------- */

    fun addAddonListener(listener: () -> Unit) = addonListeners.add(listener)

    fun removeAddonListener(listener: () -> Unit) = addonListeners.remove(listener)

    fun notifyAddonsChanged() {
        main.post { addonListeners.forEach { it() } }
    }

    fun addPaletteListener(listener: (Palette) -> Unit) = paletteListeners.add(listener)

    fun removePaletteListener(listener: (Palette) -> Unit) = paletteListeners.remove(listener)

    /* ---------------------------------------------------------------- */
    /* Bootstrapping                                                    */
    /* ---------------------------------------------------------------- */

    private fun seedBundledAddons(context: Context) {
        if (state.seeded) return
        try {
            store.seedIfEmpty(
                {
                    mapOf(
                        AddonType.PLUGIN to listAssets(context, "addons/plugins"),
                        AddonType.THEME to listAssets(context, "addons/themes"),
                    )
                },
                alreadySeeded = false,
            )
            state.seeded = true
        } catch (e: Exception) {
            NebulaLog.warn("app", "Unable to seed bundled addons: ${e.message}")
        }
    }

    private fun restoreEnabledPlugins() {
        val enabled = store.list(AddonType.PLUGIN).filter { state.isEnabled(AddonType.PLUGIN, it.id) }
        for (addon in enabled) {
            plugins.setEnabled(addon.id, true)
        }
    }

    private fun listAssets(context: Context, directory: String): List<String> {
        val names = context.assets.list(directory) ?: return emptyList()
        return names
            .filter { it.endsWith(".js") || it.endsWith(".css") }
            .mapNotNull { name -> readAssetOrNull(context, "$directory/$name") }
    }

    fun shutdown() {
        host.dispose()
    }

    companion object {
        fun readAsset(context: Context, path: String): String =
            context.assets.open(path).bufferedReader().use { it.readText() }

        private fun readAssetOrNull(context: Context, path: String): String? = try {
            readAsset(context, path)
        } catch (e: Exception) {
            NebulaLog.warn("app", "Missing bundled asset $path")
            null
        }
    }
}

/** Global accessor for the process-wide [NebulaApp]. */
object Nebula {

    @Volatile
    private var instance: NebulaApp? = null

    fun init(context: Context) {
        if (instance != null) return
        synchronized(this) {
            if (instance == null) instance = NebulaApp(context.applicationContext)
        }
    }

    fun get(): NebulaApp = instance
        ?: throw IllegalStateException("Nebula has not been initialised yet")
}

class NebulaApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Nebula.init(this)
    }
}
