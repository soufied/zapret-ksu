package dev.liquidglass.compose

import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.min

@Immutable
public sealed interface GlassShape {

    public data object Capsule : GlassShape

    public data object Circle : GlassShape

    public data class RoundedRectangle(public val cornerRadius: Dp) : GlassShape {
        init {
            require(cornerRadius >= 0.dp) { "cornerRadius must be >= 0, got $cornerRadius" }
        }
    }
}

internal fun GlassShape.resolveCornerRadiusPx(size: Size, density: Density): Float {
    val maxRadius = min(size.width, size.height) / 2f
    return when (this) {
        GlassShape.Capsule, GlassShape.Circle -> maxRadius
        is GlassShape.RoundedRectangle ->
            min(with(density) { cornerRadius.toPx() }, maxRadius)
    }
}
