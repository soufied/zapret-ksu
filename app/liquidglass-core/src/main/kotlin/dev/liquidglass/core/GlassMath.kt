package dev.liquidglass.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

public object GlassMath {

    public const val EPSILON: Float = 1e-4f

    public const val GRADIENT_STEP: Float = 1f

    public const val EMPTY_SCENE_DISTANCE: Float = 1_000_000f

    public fun sdRoundedBox(
        px: Float,
        py: Float,
        halfWidth: Float,
        halfHeight: Float,
        cornerRadius: Float,
    ): Float {
        val qx = abs(px) - halfWidth + cornerRadius
        val qy = abs(py) - halfHeight + cornerRadius
        val outsideX = max(qx, 0f)
        val outsideY = max(qy, 0f)
        val outside = sqrt(outsideX * outsideX + outsideY * outsideY)
        return min(max(qx, qy), 0f) + outside - cornerRadius
    }

    public fun smoothMin(a: Float, b: Float, smoothing: Float): Float {
        val k = max(smoothing, EPSILON)
        val h = (0.5f + 0.5f * (b - a) / k).coerceIn(0f, 1f)
        return lerp(b, a, h) - k * h * (1f - h)
    }

    public fun sceneSd(
        px: Float,
        py: Float,
        shapes: FloatArray,
        cornerRadii: FloatArray,
        mergeSmoothing: Float,
    ): Float {
        require(shapes.size == GlassUniforms.MAX_SHAPES * GlassUniforms.SHAPE_FLOATS) {
            "shapes must hold ${GlassUniforms.MAX_SHAPES} packed shapes"
        }
        require(cornerRadii.size == GlassUniforms.MAX_SHAPES) {
            "cornerRadii must hold ${GlassUniforms.MAX_SHAPES} entries"
        }
        var d = EMPTY_SCENE_DISTANCE
        for (i in 0 until GlassUniforms.MAX_SHAPES) {
            val base = i * GlassUniforms.SHAPE_FLOATS
            val di = sdRoundedBox(
                px = px - shapes[base],
                py = py - shapes[base + 1],
                halfWidth = shapes[base + 2],
                halfHeight = shapes[base + 3],
                cornerRadius = cornerRadii[i],
            )
            d = smoothMin(d, di, mergeSmoothing)
        }
        return d
    }

    public fun sceneNormal(
        px: Float,
        py: Float,
        shapes: FloatArray,
        cornerRadii: FloatArray,
        mergeSmoothing: Float,
    ): FloatArray {
        val e = GRADIENT_STEP
        val gx = sceneSd(px + e, py, shapes, cornerRadii, mergeSmoothing) -
            sceneSd(px - e, py, shapes, cornerRadii, mergeSmoothing)
        val gy = sceneSd(px, py + e, shapes, cornerRadii, mergeSmoothing) -
            sceneSd(px, py - e, shapes, cornerRadii, mergeSmoothing)
        val len = sqrt(gx * gx + gy * gy)
        if (len < EPSILON) return floatArrayOf(0f, 0f)
        return floatArrayOf(gx / len, gy / len)
    }

    public fun lensProfile(edgeDistance: Float, refractionHeight: Float): Float {
        if (refractionHeight <= 0f || edgeDistance >= refractionHeight) return 0f
        val x = 1f - edgeDistance / max(refractionHeight, EPSILON)
        return 1f - sqrt(max(1f - x * x, 0f))
    }

    private fun lerp(from: Float, to: Float, fraction: Float): Float =
        from + (to - from) * fraction
}
