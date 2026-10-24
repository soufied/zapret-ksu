package dev.liquidglass.compose

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import dev.liquidglass.core.GlassRenderTier

public val LocalLiquidGlassTier: ProvidableCompositionLocal<GlassRenderTier?> =
    compositionLocalOf { null }
