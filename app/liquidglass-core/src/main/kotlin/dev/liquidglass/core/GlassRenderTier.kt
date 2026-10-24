package dev.liquidglass.core

public enum class GlassRenderTier {

    SHADER,

    BLUR,

    SCRIM;

    public companion object {

        public const val MIN_API_SHADER: Int = 33

        public const val MIN_API_BLUR: Int = 31

        public fun select(apiLevel: Int, requested: GlassRenderTier? = null): GlassRenderTier {
            val available = when {
                apiLevel >= MIN_API_SHADER -> SHADER
                apiLevel >= MIN_API_BLUR -> BLUR
                else -> SCRIM
            }
            if (requested == null) return available
            return if (requested.ordinal > available.ordinal) requested else available
        }
    }
}
