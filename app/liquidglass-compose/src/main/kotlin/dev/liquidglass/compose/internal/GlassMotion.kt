package dev.liquidglass.compose.internal

import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring

internal object GlassMotion {

    internal const val PRESS_SCALE_MAX: Float = 0.025f

    internal const val MORPH_MIN_SCALE: Float = 0.4f

    internal val PressIn: SpringSpec<Float> = spring(dampingRatio = 0.9f, stiffness = 1200f)

    internal val Release: SpringSpec<Float> = spring(dampingRatio = 0.45f, stiffness = 280f)

    internal val Appear: SpringSpec<Float> = spring(dampingRatio = 0.6f, stiffness = 380f)

    internal val Disappear: SpringSpec<Float> = spring(dampingRatio = 1f, stiffness = 700f)
}
