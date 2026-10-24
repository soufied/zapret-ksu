package dev.liquidglass.core

import kotlin.math.max
import kotlin.math.min

public data class PackedGlassShape(
    val centerX: Float,
    val centerY: Float,
    val halfWidth: Float,
    val halfHeight: Float,
    val cornerRadius: Float,
) {
    init {
        require(halfWidth >= 0f && halfHeight >= 0f) {
            "Shape half extents must be >= 0, got $halfWidth x $halfHeight"
        }
        require(cornerRadius >= 0f) { "Corner radius must be >= 0, got $cornerRadius" }
    }
}

public object GlassShapePacker {

    public fun packShapes(shapes: List<PackedGlassShape>): FloatArray {
        val packed = FloatArray(GlassUniforms.MAX_SHAPES * GlassUniforms.SHAPE_FLOATS)
        for (i in 0 until GlassUniforms.MAX_SHAPES) {
            val base = i * GlassUniforms.SHAPE_FLOATS
            val shape = shapes.getOrNull(i)
            if (shape == null) {
                packed[base] = GlassUniforms.SENTINEL_CENTER
                packed[base + 1] = GlassUniforms.SENTINEL_CENTER
                packed[base + 2] = 1f
                packed[base + 3] = 1f
            } else {
                packed[base] = shape.centerX
                packed[base + 1] = shape.centerY
                packed[base + 2] = shape.halfWidth
                packed[base + 3] = shape.halfHeight
            }
        }
        return packed
    }

    public fun packCornerRadii(shapes: List<PackedGlassShape>): FloatArray {
        val packed = FloatArray(GlassUniforms.MAX_SHAPES)
        for (i in 0 until GlassUniforms.MAX_SHAPES) {
            val shape = shapes.getOrNull(i) ?: continue
            val maxRadius = max(min(shape.halfWidth, shape.halfHeight), 0f)
            packed[i] = min(shape.cornerRadius, maxRadius)
        }
        return packed
    }
}
