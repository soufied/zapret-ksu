package com.so.zapretksu.tile

import android.service.quicksettings.TileService
import android.widget.Toast
import com.so.zapretksu.ZapretCtl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

abstract class BaseTileService : TileService() {

    protected val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    protected fun showFailure(r: ZapretCtl.Result) {
        Toast.makeText(
            this,
            r.text.ifBlank { "Failed. Is root granted to zapret-ksu?" },
            Toast.LENGTH_SHORT
        ).show()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
