package dev.liquidglass.compose

import androidx.compose.animation.core.Animatable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.PointerInputModifierNode
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.LocalGraphicsContext
import androidx.compose.ui.unit.IntSize
import dev.liquidglass.compose.internal.GlassMotion
import dev.liquidglass.compose.internal.GlassPainter
import dev.liquidglass.compose.internal.resolveRenderTier
import dev.liquidglass.core.GlassRenderTier
import dev.liquidglass.core.PackedGlassShape
import kotlinx.coroutines.launch

public fun Modifier.liquidGlass(
    state: LiquidGlassProviderState,
    style: GlassStyle = GlassStyle.Regular,
): Modifier = this then LiquidGlassElement(state, { style }, style)

public fun Modifier.liquidGlass(
    state: LiquidGlassProviderState,
    styleProvider: () -> GlassStyle,
): Modifier = this then LiquidGlassElement(state, styleProvider, null)

private class LiquidGlassElement(
    private val state: LiquidGlassProviderState,
    private val styleProvider: () -> GlassStyle,
    private val staticStyle: GlassStyle?,
) : ModifierNodeElement<LiquidGlassNode>() {

    override fun create(): LiquidGlassNode = LiquidGlassNode(state, styleProvider)

    override fun update(node: LiquidGlassNode) {
        node.update(state, styleProvider)
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "liquidGlass"
        properties["state"] = state
        properties["style"] = staticStyle
    }

    override fun equals(other: Any?): Boolean = other is LiquidGlassElement &&
        other.state === state &&
        other.staticStyle == staticStyle &&
        (staticStyle != null || other.styleProvider === styleProvider)

    override fun hashCode(): Int =
        31 * (31 * System.identityHashCode(state) + (staticStyle?.hashCode() ?: 0)) +
            System.identityHashCode(styleProvider)
}

private class LiquidGlassNode(
    private var state: LiquidGlassProviderState,
    private var styleProvider: () -> GlassStyle,
) : Modifier.Node(),
    DrawModifierNode,
    GlobalPositionAwareModifierNode,
    PointerInputModifierNode,
    CompositionLocalConsumerModifierNode {

    private val painter = GlassPainter()
    private var glassLayer: GraphicsLayer? = null
    private var consumerCoordinates: LayoutCoordinates? = null
    private var lastPosition: Offset = Offset.Unspecified
    private val pressProgress = Animatable(0f)
    private var pressPoint = Offset.Zero

    fun update(newState: LiquidGlassProviderState, newProvider: () -> GlassStyle) {
        var changed = false
        if (state !== newState) {
            state = newState
            changed = true
        }
        if (styleProvider !== newProvider) {
            styleProvider = newProvider
            changed = true
        }
        if (!styleProvider().isInteractive && pressProgress.targetValue != 0f) {
            releasePress()
        }
        if (changed) invalidateDraw()
    }

    override fun onAttach() {
        glassLayer = currentValueOf(LocalGraphicsContext).createGraphicsLayer()
    }

    override fun onDetach() {
        glassLayer?.let { currentValueOf(LocalGraphicsContext).releaseGraphicsLayer(it) }
        glassLayer = null
        consumerCoordinates = null
    }

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        consumerCoordinates = coordinates
        val position = positionInProvider()
        if (position != lastPosition) {
            lastPosition = position
            invalidateDraw()
        }
    }

    override fun ContentDrawScope.draw() {
        if (size.width < 1f || size.height < 1f) {
            drawContent()
            return
        }
        state.positionTick
        val providerLayer = state.contentLayer
        val layer = glassLayer
        val position = positionInProvider()
        val press = pressProgress.value

        val style = styleProvider()
        val drawGlassAndContent: ContentDrawScope.() -> Unit = {
            if (providerLayer != null && layer != null && position.isSpecified) {
                val cornerRadius = style.shape.resolveCornerRadiusPx(size, this)
                painter.drawScene(
                    scope = this,
                    tier = resolveTier(),
                    providerLayer = providerLayer,
                    positionInProvider = position,
                    glassLayer = layer,
                    shapes = listOf(
                        PackedGlassShape(
                            centerX = size.width / 2f,
                            centerY = size.height / 2f,
                            halfWidth = size.width / 2f,
                            halfHeight = size.height / 2f,
                            cornerRadius = cornerRadius,
                        ),
                    ),
                    mergeSmoothingPx = 0f,
                    style = style,
                    pressAmount = press,
                    pressPoint = pressPoint,
                )
            }
            drawContent()
        }

        if (press > 0f) {
            scale(scale = 1f + GlassMotion.PRESS_SCALE_MAX * press) {
                this@draw.drawGlassAndContent()
            }
        } else {
            this.drawGlassAndContent()
        }
    }

    override fun onPointerEvent(
        pointerEvent: PointerEvent,
        pass: PointerEventPass,
        bounds: IntSize,
    ) {
        if (!styleProvider().isInteractive || pass != PointerEventPass.Initial) return
        when (pointerEvent.type) {
            PointerEventType.Press -> {
                pointerEvent.changes.firstOrNull()?.let { pressPoint = it.position }
                coroutineScope.launch { pressProgress.animateTo(1f, GlassMotion.PressIn) }
            }
            PointerEventType.Release -> {
                if (pointerEvent.changes.none { it.pressed }) releasePress()
            }
        }
    }

    override fun onCancelPointerInput() {
        if (styleProvider().isInteractive) releasePress()
    }

    private fun releasePress() {
        coroutineScope.launch { pressProgress.animateTo(0f, GlassMotion.Release) }
    }

    private fun resolveTier(): GlassRenderTier =
        resolveRenderTier(currentValueOf(LocalLiquidGlassTier), state.requestedTier)

    private fun positionInProvider(): Offset {
        val provider = state.coordinates ?: return Offset.Unspecified
        val consumer = consumerCoordinates ?: return Offset.Unspecified
        if (!provider.isAttached || !consumer.isAttached) return Offset.Unspecified
        return try {
            provider.localPositionOf(consumer, Offset.Zero)
        } catch (e: IllegalArgumentException) {
            Offset.Unspecified
        } catch (e: IllegalStateException) {
            Offset.Unspecified
        }
    }
}
