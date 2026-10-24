package dev.liquidglass.compose.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import dev.liquidglass.compose.GlassStyle
import dev.liquidglass.compose.LiquidGlassProviderState
import dev.liquidglass.compose.liquidGlass

@Composable
public fun GlassSurface(
    state: LiquidGlassProviderState,
    modifier: Modifier = Modifier,
    style: GlassStyle = GlassStyle.Regular,
    contentAlignment: Alignment = Alignment.Center,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier.liquidGlass(state, style),
        contentAlignment = contentAlignment,
        content = content,
    )
}
