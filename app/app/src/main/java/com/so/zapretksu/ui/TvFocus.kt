package com.so.zapretksu.ui

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

val LocalTelevision = compositionLocalOf { false }

fun isTelevision(context: Context): Boolean {
    val uiMode = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
    return uiMode?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
}

fun Modifier.tvFocus(shape: Shape): Modifier = composed {
    val tv = LocalTelevision.current
    var focused by remember { mutableStateOf(false) }
    val ring = if (tv && focused) {
        Modifier.border(BorderStroke(3.dp, MaterialTheme.colorScheme.primary), shape)
    } else {
        Modifier
    }
    this.onFocusChanged { focused = it.hasFocus }.then(ring)
}
