package com.so.zapretksu.tile

import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.widget.Toast
import com.so.zapretksu.R
import com.so.zapretksu.ZapretCtl
import kotlinx.coroutines.launch

class TgTileService : BaseTileService() {

    override fun onStartListening() {
        super.onStartListening()
        refresh()
    }

    override fun onClick() {
        scope.launch {
            val tile = qsTile ?: return@launch
            val status = ZapretCtl.tgStatus()
            if (status["tg_installed"] != "1") {
                Toast.makeText(
                    this@TgTileService,
                    "Download tg-ws-proxy in Tools -> Updates first",
                    Toast.LENGTH_LONG
                ).show()
                refresh()
                return@launch
            }
            tile.state = Tile.STATE_UNAVAILABLE
            tile.updateTile()

            val running = status["tg_running"] == "1"
            val r = if (running) ZapretCtl.tgStop() else ZapretCtl.tgStart()
            if (!r.ok) showFailure(r)
            refresh()
        }
    }

    private fun refresh() {
        scope.launch {
            val status = ZapretCtl.tgStatus()
            val installed = status["tg_installed"] == "1"
            val running = status["tg_running"] == "1"
            qsTile?.let { tile ->
                tile.icon = Icon.createWithResource(this@TgTileService, R.drawable.ic_qs_tg)
                tile.state = when {
                    !installed -> Tile.STATE_UNAVAILABLE
                    running -> Tile.STATE_ACTIVE
                    else -> Tile.STATE_INACTIVE
                }
                tile.subtitle = when {
                    !installed -> "Not installed"
                    running -> "On"
                    else -> "Off"
                }
                tile.updateTile()
            }
        }
    }
}
