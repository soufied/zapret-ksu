package com.so.zapretksu.tile

import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import com.so.zapretksu.R
import com.so.zapretksu.ZapretCtl
import kotlinx.coroutines.launch

class DnsTileService : BaseTileService() {

    override fun onStartListening() {
        super.onStartListening()
        refresh()
    }

    override fun onClick() {
        scope.launch {
            val tile = qsTile ?: return@launch
            tile.state = Tile.STATE_UNAVAILABLE
            tile.updateTile()

            val running = ZapretCtl.dnsStatus()["doh_running"] == "1"
            val r = if (running) ZapretCtl.dohStop() else ZapretCtl.dohStart()
            if (!r.ok) showFailure(r)
            refresh()
        }
    }

    private fun refresh() {
        scope.launch {
            val running = ZapretCtl.dnsStatus()["doh_running"] == "1"
            qsTile?.let { tile ->
                tile.icon = Icon.createWithResource(this@DnsTileService, R.drawable.ic_qs_dns)
                tile.state = if (running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
                tile.updateTile()
            }
        }
    }
}
