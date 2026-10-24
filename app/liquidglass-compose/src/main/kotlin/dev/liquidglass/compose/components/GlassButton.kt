package dev.liquidglass.compose.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.liquidglass.compose.GlassShape
import dev.liquidglass.compose.GlassStyle
import dev.liquidglass.compose.LiquidGlassProviderState
import dev.liquidglass.compose.liquidGlass

private val InteractiveRegularStyle: GlassStyle = GlassStyle.Regular.interactive()

private val InteractiveCircleStyle: GlassStyle =
    GlassStyle.Regular.copy(shape = GlassShape.Circle).interactive()

@Composable
public fun GlassButton(
    onClick: () -> Unit,
    state: LiquidGlassProviderState,
    modifier: Modifier = Modifier,
    style: GlassStyle = InteractiveRegularStyle,
    enabled: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = modifier
            .defaultMinSize(minHeight = 44.dp)
            .liquidGlass(state, style)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(contentPadding),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@Composable
public fun GlassIconButton(
    onClick: () -> Unit,
    state: LiquidGlassProviderState,
    modifier: Modifier = Modifier,
    style: GlassStyle = InteractiveCircleStyle,
    enabled: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier
            .size(48.dp)
            .liquidGlass(state, style)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
        content = content,
    )
}
