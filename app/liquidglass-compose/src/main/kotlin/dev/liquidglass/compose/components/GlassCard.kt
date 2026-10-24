package dev.liquidglass.compose.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.liquidglass.compose.GlassShape
import dev.liquidglass.compose.GlassStyle
import dev.liquidglass.compose.LiquidGlassProviderState
import dev.liquidglass.compose.liquidGlass

private val CardShape = GlassShape.RoundedRectangle(cornerRadius = 24.dp)

private val CardStyle: GlassStyle = GlassStyle.Regular.copy(shape = CardShape)

@Composable
public fun GlassCard(
    state: LiquidGlassProviderState,
    modifier: Modifier = Modifier,
    style: GlassStyle = CardStyle,
    contentPadding: PaddingValues = PaddingValues(20.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .liquidGlass(state, style)
            .padding(contentPadding),
        content = content,
    )
}
