package com.so.zapretksu.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector2D
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.TwoWayConverter
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalViewConfiguration
import dev.liquidglass.compose.GlassHighlight
import dev.liquidglass.compose.GlassRefraction
import dev.liquidglass.compose.GlassStyle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln

private const val WOBBLE_MS = 900
private const val WOBBLE_CYCLES = 2.5f
private const val SPEED_REF = 1000f
private const val SHINE_REF = 2000f
private const val ROTATION_MAX = 4f
private const val PRESS_SQUISH = 0.035f
private const val RELEASE_VELOCITY_MAX = 4000f

private val OffsetConverter = TwoWayConverter<Offset, AnimationVector2D>(
    convertToVector = { AnimationVector2D(it.x, it.y) },
    convertFromVector = { Offset(it.v1, it.v2) }
)

private fun wobbleProgress(fraction: Float, decay: Float): Float {
    fun g(x: Float) = exp(-decay * x) * cos(2f * PI.toFloat() * WOBBLE_CYCLES * x)
    return (1f - g(fraction)) / (1f - g(1f))
}

@Stable
class LiquidMotion internal constructor(
    val elasticity: Float,
    val maxStretch: Float,
    val wobbleDecay: Float,
    val reboundStiffness: Float,
    val reboundDamping: Float,
    private val scope: CoroutineScope,
) {
    internal val offset = Animatable(Offset.Zero, OffsetConverter)
    internal val press = Animatable(0f)
    internal val wobble = Animatable(0f)
    private val wobbleEasing = Easing { wobbleProgress(it, wobbleDecay) }

    internal var stretch by mutableFloatStateOf(0f)
    internal var wobbleAmp by mutableFloatStateOf(0f)
    internal var wobbleRot by mutableFloatStateOf(0f)
    internal var axis by mutableFloatStateOf(0.5f)
    internal var originX by mutableFloatStateOf(0.5f)
    internal var originY by mutableFloatStateOf(0.5f)
    internal var dragVx by mutableFloatStateOf(0f)
    internal var dragVy by mutableFloatStateOf(0f)

    private var releaseJob: Job? = null

    internal fun onDown(pos: Offset, width: Float, height: Float) {
        releaseJob?.cancel()
        releaseJob = null
        if (width > 0f) originX = (pos.x / width).coerceIn(0f, 1f)
        if (height > 0f) originY = (pos.y / height).coerceIn(0f, 1f)
        stretch = 0f
        wobbleAmp = 0f
        dragVx = 0f
        dragVy = 0f
        scope.launch { press.animateTo(1f, zapretTween(durationMillis = 90)) }
    }

    internal fun drag(delta: Offset, velocity: Offset) {
        val speed = velocity.getDistance()
        stretch = (speed / SPEED_REF * elasticity).coerceAtMost(maxStretch)
        val sq = velocity.x * velocity.x + velocity.y * velocity.y
        axis = if (sq > 0f) velocity.x * velocity.x / sq else 0.5f
        dragVx = velocity.x
        dragVy = velocity.y
        scope.launch(start = CoroutineStart.UNDISPATCHED) { offset.snapTo(offset.value + delta) }
    }

    internal fun release(velocity: Offset) {
        val amp = stretch
        val rot = (velocity.x / SHINE_REF).coerceIn(-1f, 1f) * ROTATION_MAX
        val launchVelocity = Offset(
            velocity.x.coerceIn(-RELEASE_VELOCITY_MAX, RELEASE_VELOCITY_MAX),
            velocity.y.coerceIn(-RELEASE_VELOCITY_MAX, RELEASE_VELOCITY_MAX)
        )
        stretch = 0f
        dragVx = 0f
        dragVy = 0f
        releaseJob?.cancel()
        releaseJob = scope.launch {
            wobble.snapTo(1f)
            wobbleAmp = amp
            wobbleRot = rot
            launch { press.animateTo(0f, zapretSpring<Float>(stiffness = Spring.StiffnessMedium)) }
            launch {
                offset.animateTo(
                    Offset.Zero,
                    zapretSpring<Offset>(dampingRatio = reboundDamping, stiffness = reboundStiffness),
                    initialVelocity = launchVelocity
                )
            }
            wobble.animateTo(0f, zapretTween(durationMillis = WOBBLE_MS, easing = wobbleEasing))
        }
    }

    internal fun cancel() {
        stretch = 0f
        dragVx = 0f
        dragVy = 0f
        releaseJob?.cancel()
        releaseJob = null
        scope.launch { press.animateTo(0f, zapretSpring<Float>(stiffness = Spring.StiffnessMedium)) }
    }

    internal fun glassStyleFor(base: GlassStyle): GlassStyle {
        val v = if (dragVx != 0f || dragVy != 0f) Offset(dragVx, dragVy) else offset.velocity
        val speed = (v.getDistance() / SHINE_REF).coerceIn(0f, 1f)
        val shift = (v.x / SHINE_REF).coerceIn(-1f, 1f)
        return base.copy(
            refraction = GlassRefraction(
                height = base.refraction.height,
                amount = base.refraction.amount * (1f + 0.6f * speed)
            ),
            highlight = GlassHighlight(
                width = base.highlight.width,
                alpha = (base.highlight.alpha * (0.8f + 0.4f * speed)).coerceIn(0f, 1f),
                lightAngleDegrees = base.highlight.lightAngleDegrees - 40f * shift
            )
        )
    }
}

@Composable
fun rememberLiquidMotion(
    elasticity: Float = 0.08f,
    maxStretch: Float = 0.18f,
    wobbleDecay: Float = 4.5f,
    reboundStiffness: Float = Spring.StiffnessMediumLow,
    reboundDamping: Float = Spring.DampingRatioLowBouncy,
): LiquidMotion {
    val scope = rememberCoroutineScope()
    return remember(elasticity, maxStretch, wobbleDecay, reboundStiffness, reboundDamping) {
        LiquidMotion(elasticity, maxStretch, wobbleDecay, reboundStiffness, reboundDamping, scope)
    }
}

@Composable
fun Modifier.liquidMotion(
    motion: LiquidMotion,
    consumeDrag: Boolean = true,
    grabDelayMillis: Long? = null
): Modifier {
    val slop = LocalViewConfiguration.current.touchSlop
    return this
        .pointerInput(motion, slop, consumeDrag, grabDelayMillis) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                motion.onDown(
                    down.position,
                    this@pointerInput.size.width.toFloat(),
                    this@pointerInput.size.height.toFloat()
                )
                val tracker = VelocityTracker()
                tracker.addPosition(down.uptimeMillis, down.position)
                var prev = down.position
                var dragging = false
                if (grabDelayMillis != null) {
                    var outcome = 0
                    withTimeoutOrNull(grabDelayMillis) {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id }
                            if (change == null || !change.pressed) {
                                outcome = 1
                                break
                            }
                            if ((change.position - down.position).getDistance() > slop) {
                                outcome = 2
                                break
                            }
                        }
                    }
                    when (outcome) {
                        1 -> {
                            motion.release(Offset.Zero)
                            return@awaitEachGesture
                        }
                        2 -> {
                            motion.cancel()
                            return@awaitEachGesture
                        }
                    }
                    dragging = true
                }
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) break
                    tracker.addPosition(change.uptimeMillis, change.position)
                    if (!dragging && (change.position - down.position).getDistance() > slop) {
                        dragging = true
                        if (!consumeDrag) {
                            motion.cancel()
                            return@awaitEachGesture
                        }
                    }
                    if (dragging) {
                        change.consume()
                        val v = tracker.calculateVelocity()
                        motion.drag(change.position - prev, Offset(v.x, v.y))
                        prev = change.position
                    }
                }
                val v = tracker.calculateVelocity()
                motion.release(if (dragging) Offset(v.x, v.y) else Offset.Zero)
            }
        }
        .graphicsLayer {
            val squish = 1f - PRESS_SQUISH * motion.press.value
            val s = (motion.stretch + motion.wobbleAmp * motion.wobble.value)
                .coerceIn(-motion.maxStretch, motion.maxStretch)
            val k = ln(1f + s)
            val a = 2f * motion.axis - 1f
            scaleX = exp(k * a) / squish
            scaleY = exp(-k * a) * squish
            rotationZ = motion.wobbleRot * motion.wobble.value
            translationX = motion.offset.value.x
            translationY = motion.offset.value.y
            transformOrigin = TransformOrigin(motion.originX, motion.originY)
        }
}
