package dev.liquidglass.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import dev.liquidglass.core.GlassRenderTier

@Stable
public class LiquidGlassProviderState {

    public var requestedTier: GlassRenderTier? by mutableStateOf(null)

    internal var contentLayer: GraphicsLayer? by mutableStateOf(null)

    internal var coordinates: LayoutCoordinates? = null
        private set

    internal var positionTick: Int by mutableIntStateOf(0)
        private set

    internal fun onProviderPositioned(coordinates: LayoutCoordinates) {
        this.coordinates = coordinates
        positionTick++
    }

    internal fun onProviderDetached() {
        coordinates = null
        contentLayer = null
    }
}

@Composable
public fun rememberLiquidGlassProviderState(): LiquidGlassProviderState =
    remember { LiquidGlassProviderState() }
