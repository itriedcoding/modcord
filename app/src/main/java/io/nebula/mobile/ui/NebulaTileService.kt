package io.nebula.mobile.ui

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import io.nebula.mobile.Nebula
import io.nebula.mobile.R
import io.nebula.mobile.core.AddonType

/**
 * Quick Settings tile: shows whether a patched build exists and opens Nebula when tapped.
 * Its state comes from the real patch history and the enabled-plugin count.
 */
class NebulaTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        val tile = qsTile ?: return
        val app = runCatching { Nebula.get() }.getOrNull()
        val patches = runCatching { app?.patchHistory?.read() }.getOrNull().orEmpty()
        val enabled = runCatching {
            app?.plugins?.rows(AddonType.PLUGIN)?.count { it.enabled }
        }.getOrNull() ?: 0
        tile.state = if (patches.isEmpty()) Tile.STATE_INACTIVE else Tile.STATE_ACTIVE
        tile.label = "Nebula"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = if (patches.isEmpty()) {
                "No patched build yet"
            } else {
                "${patches.size} build(s) · $enabled plugin(s)"
            }
        }
        tile.icon = Icon.createWithResource(this, R.drawable.ic_launcher_foreground)
        tile.updateTile()
    }

    override fun onClick() {
        super.onClick()
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pending = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
            startActivityAndCollapse(pending)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
