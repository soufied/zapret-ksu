package dev.liquidglass.compose.internal

import android.os.Build
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.unit.IntSize
import dev.liquidglass.compose.GlassStyle
import dev.liquidglass.core.GlassRenderTier
import dev.liquidglass.core.GlassShapePacker
import dev.liquidglass.core.GlassUniforms
import dev.liquidglass.core.PackedGlassShape
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

private const val DEFAULT_SCRIM_ALPHA = 0.35f
private const val LAYER_PADDING_PX = 4f

internal class GlassPainter {

    private var runtimeEffect: LiquidGlassRuntimeEffect? = null
    private var shaderUnavailable = false

    fun drawScene(
        scope: DrawScope,
        tier: GlassRenderTier,
        providerLayer: GraphicsLayer,
        positionInProvider: Offset,
        glassLayer: GraphicsLayer,
        shapes: List<PackedGlassShape>,
        mergeSmoothingPx: Float,
        style: GlassStyle,
        pressAmount: Float,
        pressPoint: Offset,
    ): Unit = with(scope) {
        if (shapes.isEmpty()) return
        val blurPx = style.blurRadius.toPx()
        val refractionAmountPx = style.refraction.amount.toPx()
        val inflate =
            ceil(blurPx * 2f + abs(refractionAmountPx) + mergeSmoothingPx * 0.5f) + LAYER_PADDING_PX
        val layerSize = IntSize(
            ceil(size.width + inflate * 2f).toInt(),
            ceil(size.height + inflate * 2f).toInt(),
        )
        if (layerSize.width <= 0 || layerSize.height <= 0) return

        glassLayer.record(
            density = this,
            layoutDirection = layoutDirection,
            size = layerSize,
        ) {
            translate(inflate - positionInProvider.x, inflate - positionInProvider.y) {
                drawLayer(providerLayer)
            }
        }

        val useShader = tier == GlassRenderTier.SHADER && !shaderUnavailable
        when {
            useShader -> try {
                drawShaderTier(
                    glassLayer, shapes, inflate, mergeSmoothingPx, style, blurPx,
                    refractionAmountPx, pressAmount, pressPoint,
                )
            } catch (e: RuntimeException) {
                shaderUnavailable = true
                drawBlurTier(glassLayer, shapes, inflate, style, blurPx)
            }
            tier == GlassRenderTier.SCRIM -> drawScrimTier(glassLayer, shapes, inflate, style)
            else -> drawBlurTier(glassLayer, shapes, inflate, style, blurPx)
        }
    }

    private fun DrawScope.drawShaderTier(
        glassLayer: GraphicsLayer,
        shapes: List<PackedGlassShape>,
        inflate: Float,
        mergeSmoothingPx: Float,
        style: GlassStyle,
        blurPx: Float,
        refractionAmountPx: Float,
        pressAmount: Float,
        pressPoint: Offset,
    ) {
        if (Build.VERSION.SDK_INT < 33) return
        val effect = runtimeEffect ?: LiquidGlassRuntimeEffect().also { runtimeEffect = it }
        val uniforms = buildUniforms(
            shapes, inflate, mergeSmoothingPx, style, refractionAmountPx,
            pressAmount, pressPoint,
        )
        glassLayer.renderEffect = effect
            .buildRenderEffect(uniforms, blurPx, style.saturation)
            .asComposeRenderEffect()
        translate(-inflate, -inflate) {
            drawLayer(glassLayer)
        }
    }

    private fun DrawScope.drawBlurTier(
        glassLayer: GraphicsLayer,
        shapes: List<PackedGlassShape>,
        inflate: Float,
        style: GlassStyle,
        blurPx: Float,
    ) {
        if (Build.VERSION.SDK_INT < 31) return
        glassLayer.renderEffect =
            buildBackdropPrepEffect(blurPx, style.saturation)?.asComposeRenderEffect()
        clipPath(buildUnionPath(shapes)) {
            translate(-inflate, -inflate) {
                drawLayer(glassLayer)
            }
            if (style.tint.isSpecified && style.tint.alpha > 0f) {
                drawRect(style.tint)
            }
        }
        drawRimHighlights(shapes, style)
    }

    private fun DrawScope.drawScrimTier(
        glassLayer: GraphicsLayer,
        shapes: List<PackedGlassShape>,
        inflate: Float,
        style: GlassStyle,
    ) {
        glassLayer.renderEffect = null
        val scrim = style.fallbackScrim.takeOrElse {
            if (style.tint.isSpecified && style.tint.alpha > 0f) {
                style.tint.copy(alpha = max(style.tint.alpha, DEFAULT_SCRIM_ALPHA))
            } else {
                Color.White.copy(alpha = DEFAULT_SCRIM_ALPHA)
            }
        }
        clipPath(buildUnionPath(shapes)) {
            translate(-inflate, -inflate) {
                drawLayer(glassLayer)
            }
            drawRect(scrim)
        }
        drawRimHighlights(shapes, style)
    }

    private fun DrawScope.buildUniforms(
        shapes: List<PackedGlassShape>,
        inflate: Float,
        mergeSmoothingPx: Float,
        style: GlassStyle,
        refractionAmountPx: Float,
        pressAmount: Float,
        pressPoint: Offset,
    ): GlassSceneUniforms {
        val packedShapes = GlassShapePacker.packShapes(shapes)
        for (i in 0 until minOf(shapes.size, GlassUniforms.MAX_SHAPES)) {
            val base = i * GlassUniforms.SHAPE_FLOATS
            packedShapes[base] += inflate
            packedShapes[base + 1] += inflate
        }
        val tint = if (style.tint.isSpecified) style.tint.convert(ColorSpaces.Srgb) else null
        val light = lightDirection(style)
        return GlassSceneUniforms(
            shapes = packedShapes,
            cornerRadii = GlassShapePacker.packCornerRadii(shapes),
            mergeSmoothing = mergeSmoothingPx,
            refractionHeightPx = style.refraction.height.toPx(),
            refractionAmountPx = refractionAmountPx,
            chromaticAberration = style.chromaticAberration,
            tintRed = tint?.red ?: 0f,
            tintGreen = tint?.green ?: 0f,
            tintBlue = tint?.blue ?: 0f,
            tintAlpha = tint?.alpha ?: 0f,
            noiseAlpha = style.noiseAlpha,
            lightDirX = light.x,
            lightDirY = light.y,
            highlightAlpha = style.highlight.alpha,
            highlightWidthPx = style.highlight.width.toPx(),
            pressAmount = pressAmount,
            pressX = pressPoint.x + inflate,
            pressY = pressPoint.y + inflate,
        )
    }

    private fun buildUnionPath(shapes: List<PackedGlassShape>): Path {
        val path = Path()
        for (shape in shapes) {
            path.addRoundRect(
                RoundRect(
                    rect = Rect(
                        offset = Offset(
                            shape.centerX - shape.halfWidth,
                            shape.centerY - shape.halfHeight,
                        ),
                        size = Size(shape.halfWidth * 2f, shape.halfHeight * 2f),
                    ),
                    cornerRadius = CornerRadius(shape.cornerRadius),
                ),
            )
        }
        return path
    }

    private fun DrawScope.drawRimHighlights(
        shapes: List<PackedGlassShape>,
        style: GlassStyle,
    ) {
        val highlight = style.highlight
        if (highlight.alpha <= 0f || highlight.width.value <= 0f) return
        val stroke = Stroke(width = highlight.width.toPx())
        val direction = lightDirection(style)
        val colors = listOf(
            Color.White.copy(alpha = highlight.alpha),
            Color.White.copy(alpha = highlight.alpha * 0.08f),
            Color.White.copy(alpha = highlight.alpha * 0.55f),
        )
        for (shape in shapes) {
            val center = Offset(shape.centerX, shape.centerY)
            val reach = max(shape.halfWidth, shape.halfHeight)
            val brush = Brush.linearGradient(
                colors = colors,
                start = center + direction * reach,
                end = center - direction * reach,
            )
            drawRoundRect(
                brush = brush,
                topLeft = Offset(
                    shape.centerX - shape.halfWidth,
                    shape.centerY - shape.halfHeight,
                ),
                size = Size(shape.halfWidth * 2f, shape.halfHeight * 2f),
                cornerRadius = CornerRadius(shape.cornerRadius),
                style = stroke,
            )
        }
    }
}

private fun lightDirection(style: GlassStyle): Offset {
    val angleRadians = Math.toRadians(style.highlight.lightAngleDegrees.toDouble())
    return Offset(cos(angleRadians).toFloat(), sin(angleRadians).toFloat())
}

internal fun resolveRenderTier(
    local: GlassRenderTier?,
    fromState: GlassRenderTier?,
): GlassRenderTier {
    val requested = when {
        local == null -> fromState
        fromState == null -> local
        local.ordinal >= fromState.ordinal -> local
        else -> fromState
    }
    return GlassRenderTier.select(Build.VERSION.SDK_INT, requested)
}
