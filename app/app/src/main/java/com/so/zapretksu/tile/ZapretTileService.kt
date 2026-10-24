package com.so.zapretksu.tile

import android.service.quicksettings.Tile
import com.so.zapretksu.ZapretCtl
import kotlinx.coroutines.launch

class ZapretTileService : BaseTileService() {

    override fun onStartListening() {
        super.onStartListening()
        refresh()
    }

    override fun onClick() {
        scope.launch {
            val tile = qsTile ?: return@launch
            tile.state = Tile.STATE_UNAVAILABLE
            tile.updateTile()

            val running = ZapretCtl.status()["nfqws_running"] == "1"
            val r = if (running) ZapretCtl.stop() else ZapretCtl.start()
            if (!r.ok) showFailure(r)
            refresh()
        }
    }

    private fun refresh() {
        scope.launch {
            val running = ZapretCtl.status()["nfqws_running"] == "1"
            qsTile?.let { tile ->
                tile.state = if (running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
                tile.updateTile()
            }
        }
    }
}
