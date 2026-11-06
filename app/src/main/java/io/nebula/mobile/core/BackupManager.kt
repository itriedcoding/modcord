package io.nebula.mobile.core

import io.nebula.mobile.BuildConfig
import io.nebula.mobile.NebulaApp

/** Reads the live app into a backup document and applies one back. */
object BackupManager {

    data class RestoreResult(
        val addonsInstalled: Int,
        val addonsSkipped: List<String>,
        val settingsApplied: Boolean,
    )

    fun export(app: NebulaApp): String {
        val snapshots = ArrayList<Backup.AddonSnapshot>()
        for (type in AddonType.values()) {
            for (addon in app.store.list(type)) {
                val source = try {
                    app.store.read(addon)
                } catch (e: Exception) {
                    NebulaLog.warn("backup", "Skipping ${addon.id}: ${e.message}")
                    continue
                }
                snapshots.add(
                    Backup.AddonSnapshot(
                        type = type.dirName,
                        id = addon.id,
                        name = addon.name,
                        version = addon.version,
                        source = source,
                    ),
                )
            }
        }
        return Backup.encode(
            settings = app.settings.snapshot(),
            state = app.state.snapshot(),
            addons = snapshots,
            createdAt = System.currentTimeMillis(),
            createdWith = BuildConfig.VERSION_NAME,
        )
    }

    fun restore(app: NebulaApp, text: String): RestoreResult {
        val bundle = Backup.decode(text)
        var installed = 0
        val skipped = ArrayList<String>()
        for (snapshot in bundle.addons) {
            val type = Backup.typeOf(snapshot)
            try {
                app.store.install(type, snapshot.source, snapshot.name)
                installed++
            } catch (e: Exception) {
                skipped.add("${snapshot.name}: ${e.message}")
                NebulaLog.warn("backup", "Could not restore ${snapshot.name}: ${e.message}")
            }
        }
        app.settings.replaceAll(bundle.settings)
        app.state.replaceAll(bundle.state)
        // Re-apply the restored state against what is actually installed now.
        for (type in AddonType.values()) {
            for (addon in app.store.list(type)) {
                if (!app.state.isEnabled(type, addon.id)) continue
                if (type == AddonType.PLUGIN) app.plugins.setEnabled(addon.id, true)
            }
        }
        app.onThemeChanged()
        app.store.notifyChanged()
        NebulaLog.info("backup", "Restored $installed addon(s), skipped ${skipped.size}")
        return RestoreResult(installed, skipped, settingsApplied = true)
    }
}
