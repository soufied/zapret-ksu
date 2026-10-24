package dev.liquidglass.compose

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Immutable
public class GlassRefraction(
    public val height: Dp = 12.dp,
    public val amount: Dp = 16.dp,
) {
    init {
        require(height >= 0.dp) { "refraction height must be >= 0, got $height" }
    }

    override fun equals(other: Any?): Boolean =
        other is GlassRefraction && other.height == height && other.amount == amount

    override fun hashCode(): Int = 31 * height.hashCode() + amount.hashCode()

    override fun toString(): String = "GlassRefraction(height=$height, amount=$amount)"

    public companion object {
        public val Default: GlassRefraction = GlassRefraction()

        public val None: GlassRefraction = GlassRefraction(height = 0.dp, amount = 0.dp)
    }
}

@Immutable
public class GlassHighlight(
    public val width: Dp = 2.5.dp,
    public val alpha: Float = 0.55f,
    public val lightAngleDegrees: Float = 245f,
) {
    init {
        require(width >= 0.dp) { "highlight width must be >= 0, got $width" }
        require(alpha in 0f..1f) { "highlight alpha must be in 0..1, got $alpha" }
    }

    override fun equals(other: Any?): Boolean = other is GlassHighlight &&
        other.width == width && other.alpha == alpha &&
        other.lightAngleDegrees == lightAngleDegrees

    override fun hashCode(): Int {
        var result = width.hashCode()
        result = 31 * result + alpha.hashCode()
        result = 31 * result + lightAngleDegrees.hashCode()
        return result
    }

    override fun toString(): String =
        "GlassHighlight(width=$width, alpha=$alpha, lightAngleDegrees=$lightAngleDegrees)"

    public companion object {
        public val Default: GlassHighlight = GlassHighlight()

        public val None: GlassHighlight = GlassHighlight(alpha = 0f)
    }
}

@Immutable
public class GlassStyle(
    public val shape: GlassShape = GlassShape.Capsule,
    public val blurRadius: Dp = 20.dp,
    public val refraction: GlassRefraction = GlassRefraction.Default,
    public val saturation: Float = 1.5f,
    public val tint: Color = Color.Unspecified,
    public val highlight: GlassHighlight = GlassHighlight.Default,
    public val noiseAlpha: Float = 0.015f,
    public val chromaticAberration: Float = 0f,
    public val isInteractive: Boolean = false,
    public val fallbackScrim: Color = Color.Unspecified,
) {
    init {
        require(blurRadius >= 0.dp) { "blurRadius must be >= 0, got $blurRadius" }
        require(saturation >= 0f) { "saturation must be >= 0, got $saturation" }
        require(noiseAlpha in 0f..1f) { "noiseAlpha must be in 0..1, got $noiseAlpha" }
        require(chromaticAberration in 0f..1f) {
            "chromaticAberration must be in 0..1, got $chromaticAberration"
        }
    }

    public fun tinted(tint: Color): GlassStyle = copy(tint = tint)

    public fun interactive(enabled: Boolean = true): GlassStyle = copy(isInteractive = enabled)

    public fun copy(
        shape: GlassShape = this.shape,
        blurRadius: Dp = this.blurRadius,
        refraction: GlassRefraction = this.refraction,
        saturation: Float = this.saturation,
        tint: Color = this.tint,
        highlight: GlassHighlight = this.highlight,
        noiseAlpha: Float = this.noiseAlpha,
        chromaticAberration: Float = this.chromaticAberration,
        isInteractive: Boolean = this.isInteractive,
        fallbackScrim: Color = this.fallbackScrim,
    ): GlassStyle = GlassStyle(
        shape = shape,
        blurRadius = blurRadius,
        refraction = refraction,
        saturation = saturation,
        tint = tint,
        highlight = highlight,
        noiseAlpha = noiseAlpha,
        chromaticAberration = chromaticAberration,
        isInteractive = isInteractive,
        fallbackScrim = fallbackScrim,
    )

    override fun equals(other: Any?): Boolean = other is GlassStyle &&
        other.shape == shape &&
        other.blurRadius == blurRadius &&
        other.refraction == refraction &&
        other.saturation == saturation &&
        other.tint == tint &&
        other.highlight == highlight &&
        other.noiseAlpha == noiseAlpha &&
        other.chromaticAberration == chromaticAberration &&
        other.isInteractive == isInteractive &&
        other.fallbackScrim == fallbackScrim

    override fun hashCode(): Int {
        var result = shape.hashCode()
        result = 31 * result + blurRadius.hashCode()
        result = 31 * result + refraction.hashCode()
        result = 31 * result + saturation.hashCode()
        result = 31 * result + tint.hashCode()
        result = 31 * result + highlight.hashCode()
        result = 31 * result + noiseAlpha.hashCode()
        result = 31 * result + chromaticAberration.hashCode()
        result = 31 * result + isInteractive.hashCode()
        result = 31 * result + fallbackScrim.hashCode()
        return result
    }

    override fun toString(): String = "GlassStyle(shape=$shape, blurRadius=$blurRadius, " +
        "refraction=$refraction, saturation=$saturation, tint=$tint, highlight=$highlight, " +
        "noiseAlpha=$noiseAlpha, chromaticAberration=$chromaticAberration, " +
        "isInteractive=$isInteractive, fallbackScrim=$fallbackScrim)"

    public companion object {
        public val Regular: GlassStyle = GlassStyle()

        public val Clear: GlassStyle = GlassStyle(
            blurRadius = 6.dp,
            saturation = 1.2f,
            noiseAlpha = 0.01f,
        )

        public val IOS26Regular: GlassStyle = GlassStyle(
            blurRadius = 9.dp,
            refraction = GlassRefraction(height = 16.dp, amount = 26.dp),
            saturation = 1.25f,
            tint = Color(0x0FFFFFFF),
            highlight = GlassHighlight(width = 1.dp, alpha = 0.9f, lightAngleDegrees = 250f),
            noiseAlpha = 0.006f,
            chromaticAberration = 0.3f,
        )

        public val IOS26Clear: GlassStyle = IOS26Regular.copy(
            blurRadius = 3.dp,
            refraction = GlassRefraction(height = 14.dp, amount = 22.dp),
            saturation = 1.1f,
            tint = Color.Unspecified,
        )

        public fun iOS26Prominent(tint: Color): GlassStyle = IOS26Regular.copy(
            tint = tint,
            highlight = GlassHighlight(width = 1.dp, alpha = 1f, lightAngleDegrees = 250f),
        )

        public fun prominent(tint: Color): GlassStyle = Regular.copy(
            tint = tint,
            highlight = GlassHighlight(width = 2.5.dp, alpha = 0.7f),
        )
    }
}
