package com.kevxngg.audiomax

import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Quick Settings tile: turns Dolby on or off instantly. */
class DolbyTile : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        Thread {
            val on = runCatching { Live.effect()?.dsOn }.getOrNull()
            update(on)
        }.start()
    }

    override fun onClick() {
        super.onClick()
        Thread {
            val fx = Live.effect()
            val now = runCatching { fx?.dsOn }.getOrNull()
            if (fx == null || now == null) { update(null); return@Thread }
            val target = !now
            runCatching { fx.dsOn = target }
            update(target)
            // Save the state so it survives a reboot (without cutting the audio)
            runCatching {
                val store = DolbyStore(applicationContext)
                val s = store.load()
                store.save(s.copy(dolbyOn = target), restart = false)
            }
        }.start()
    }

    private fun update(on: Boolean?) {
        val t = qsTile ?: return
        t.state = when (on) {
            true -> Tile.STATE_ACTIVE
            false -> Tile.STATE_INACTIVE
            null -> Tile.STATE_UNAVAILABLE
        }
        t.label = getString(R.string.app_name)
        t.subtitle = getString(
            when (on) { true -> R.string.on; false -> R.string.off; null -> R.string.tile_no_root }
        )
        t.icon = Icon.createWithResource(this, R.drawable.ic_stat)
        t.updateTile()
    }
}
