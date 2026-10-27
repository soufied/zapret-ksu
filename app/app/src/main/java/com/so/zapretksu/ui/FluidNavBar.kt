package com.so.zapretksu.ui
import androidx.compose.foundation.shape.RoundedCornerShape

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.so.zapretksu.icon
import dev.liquidglass.compose.GlassStyle
import dev.liquidglass.compose.LiquidGlassProviderState
import dev.liquidglass.compose.components.GlassBottomBar
import kotlin.math.abs
import kotlin.math.min

@Composable
fun FluidNavBar(
    route: Route,
    glassState: LiquidGlassProviderState?,
    onSelect: (Route) -> Unit,
) {
    val tabs = Route.TOP_LEVEL
    val index = tabs.indexOf(route)
    val slide = remember { Animatable(maxOf(index, 0).toFloat()) }
    val optimized = LocalAppearance.current.optimized
    LaunchedEffect(index) {
        if (index >= 0) {
            if (optimized) {
                slide.snapTo(index.toFloat())
            } else {
                slide.animateTo(
                    index.toFloat(),
                    zapretSpring<Float>(stiffness = Spring.StiffnessMediumLow, dampingRatio = Spring.DampingRatioLowBouncy)
                )
            }
        }
    }
    val glass = glassState != null

    if (glassState != null) {
        GlassBottomBar(
            state = glassState,
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            style = GlassStyle.IOS26Regular.tuned(LocalAppearance.current.tune),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
        ) {
            NavRow(tabs, index, slide, glass, onSelect)
        }
    } else {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainer,
            tonalElevation = 3.dp
        ) {
            NavigationBar(
                containerColor = Color.Transparent,
                tonalElevation = 0.dp,
                windowInsets = WindowInsets(0, 0, 0, 0)
            ) {
                tabs.forEach { r ->
                    NavigationBarItem(
                        selected = r == route,
                        onClick = { onSelect(r) },
                        icon = { Icon(r.icon(), contentDescription = null) },
                        label = { Text(r.title, maxLines = 1) },
                        alwaysShowLabel = true
                    )
                }
            }
        }
    }
}

@Composable
private fun NavRow(
    tabs: List<Route>,
    index: Int,
    slide: Animatable<Float, AnimationVector1D>,
    glass: Boolean,
    onSelect: (Route) -> Unit,
    modifier: Modifier = Modifier,
) {
    val highlight = MaterialTheme.colorScheme.secondaryContainer
    Row(
        modifier
            .fillMaxWidth()
            .drawBehind {
                val n = tabs.size
                if (n == 0) return@drawBehind
                val tabW = size.width / n
                val lens = 1f + 0.15f * min(1f, abs(slide.velocity) / 3f)
                val w = (tabW - 8.dp.toPx()) * lens
                val h = minOf(44.dp.toPx(), size.height)
                val cx = (slide.value + 0.5f) * tabW
                val topLeft = Offset(cx - w / 2f, (size.height - h) / 2f)
                val pill = Size(w, h)
                val corner = CornerRadius(h / 2f)
                if (glass) {
                    drawRoundRect(
                        brush = Brush.verticalGradient(
                            listOf(GlassTokens.FillTop, GlassTokens.FillBottom),
                            startY = topLeft.y,
                            endY = topLeft.y + h
                        ),
                        topLeft = topLeft,
                        size = pill,
                        cornerRadius = corner
                    )
                    drawRoundRect(
                        brush = Brush.linearGradient(
                            listOf(GlassTokens.RimHigh, GlassTokens.RimLow),
                            start = topLeft,
                            end = Offset(topLeft.x + w, topLeft.y + h)
                        ),
                        topLeft = topLeft,
                        size = pill,
                        cornerRadius = corner,
                        style = Stroke(width = 1.dp.toPx())
                    )
                } else {
                    drawRoundRect(color = highlight, topLeft = topLeft, size = pill, cornerRadius = corner)
                }
            }
    ) {
        tabs.forEachIndexed { i, route ->
            NavTab(route, i == index, glass) { onSelect(route) }
        }
    }
}

@Composable
private fun RowScope.NavTab(route: Route, selected: Boolean, glass: Boolean, onClick: () -> Unit) {
    val motion = rememberLiquidMotion(elasticity = 0.05f, maxStretch = 0.12f, wobbleDecay = 5f)
    val optimized = LocalAppearance.current.optimized
    val selectedTint = if (glass) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSecondaryContainer
    val tint by animateColorAsState(
        targetValue = if (selected) selectedTint else MaterialTheme.colorScheme.onSurfaceVariant,
        label = "navTint", animationSpec = zapretTween()
    )
    val lift by animateFloatAsState(
        targetValue = if (selected) 1.08f else 1f,
        animationSpec = zapretSpring(stiffness = Spring.StiffnessMediumLow, dampingRatio = Spring.DampingRatioLowBouncy),
        label = "navLift"
    )
    val source = remember { MutableInteractionSource() }

    Column(
        Modifier
            .weight(1f)
            .then(if (optimized) Modifier else Modifier.liquidMotion(motion))
            .tvFocus(RoundedCornerShape(16.dp))
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            route.icon(),
            contentDescription = route.title,
            tint = tint,
            modifier = Modifier
                .size(24.dp)
                .graphicsLayer { scaleX = lift; scaleY = lift }
        )
        Text(route.title, style = MaterialTheme.typography.labelMedium, color = tint, maxLines = 1)
    }
}
