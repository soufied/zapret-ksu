package dev.liquidglass.core

public object GlassUniforms {

    public const val MAX_SHAPES: Int = 8

    public const val SHAPE_FLOATS: Int = 4

    public const val SENTINEL_CENTER: Float = -1_000_000f

    public const val CONTENT: String = "content"

    public const val SHAPES: String = "shapes"

    public const val SHAPE_RADII: String = "shapeRadii"

    public const val MERGE_SMOOTHING: String = "mergeK"

    public const val REFRACTION_HEIGHT: String = "refractionHeight"

    public const val REFRACTION_AMOUNT: String = "refractionAmount"

    public const val CHROMATIC_ABERRATION: String = "chromaticAberration"

    public const val TINT: String = "tint"

    public const val NOISE_ALPHA: String = "noiseAlpha"

    public const val LIGHT_DIRECTION: String = "lightDir"

    public const val HIGHLIGHT_ALPHA: String = "highlightAlpha"

    public const val HIGHLIGHT_WIDTH: String = "highlightWidth"

    public const val PRESS_AMOUNT: String = "pressAmount"

    public const val PRESS_POINT: String = "pressPoint"

    public val ALL_FLOAT_UNIFORMS: List<String> = listOf(
        SHAPES,
        SHAPE_RADII,
        MERGE_SMOOTHING,
        REFRACTION_HEIGHT,
        REFRACTION_AMOUNT,
        CHROMATIC_ABERRATION,
        TINT,
        NOISE_ALPHA,
        LIGHT_DIRECTION,
        HIGHLIGHT_ALPHA,
        HIGHLIGHT_WIDTH,
        PRESS_AMOUNT,
        PRESS_POINT,
    )
}
