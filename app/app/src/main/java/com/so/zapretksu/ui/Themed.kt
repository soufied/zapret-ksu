package com.so.zapretksu.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import dev.liquidglass.compose.LiquidGlassProviderState
import dev.liquidglass.compose.components.GlassButton
import dev.liquidglass.compose.components.GlassSurface

@Composable
fun isGlassActive(): Boolean =
    LocalAppearance.current.mode == ThemeMode.GLASS && LocalGlassState.current != null

private val TileShape = 20.dp
private val ButtonPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp)
private val ChipPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
private val FlatGlassFill = Color.White.copy(alpha = 0.06f)
private val FlatAccentFill = Color(0x148FD3FF)
private val FlatPillRadius = 22.dp

@Composable
private fun FlatGlassPill(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color,
    padding: PaddingValues,
    content: @Composable RowScope.() -> Unit
) {
    val shape = RoundedCornerShape(FlatPillRadius)
    Row(
        modifier
            .defaultMinSize(minHeight = 44.dp)
            .clip(shape)
            .glassPanel(FlatPillRadius, LocalAppearance.current.tune.tint)
            .background(tint, shape)
            .glassRim(FlatPillRadius)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(padding),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
        content = content
    )
}

@Composable
fun ThemedButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    primary: Boolean = true,
    icon: ImageVector? = null
) {
    val glassState = LocalGlassState.current
    if (isGlassActive() && glassState != null) {
        val glassContent: @Composable RowScope.() -> Unit = {
            if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
            }
            Text(
                label,
                maxLines = 1,
                color = if (enabled) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            )
        }
        if (LocalFlatGlass.current) {
            FlatGlassPill(
                onClick = onClick,
                modifier = modifier,
                enabled = enabled,
                tint = if (primary) GlassTokens.Accent.copy(alpha = 0.30f) else FlatGlassFill,
                padding = ButtonPadding,
                content = glassContent
            )
        } else {
            val base = GlassPillStyle.tuned(LocalAppearance.current.tune).interactive()
            val style = if (primary) base.copy(tint = GlassTokens.Accent.copy(alpha = 0.30f)) else base
            GlassButton(
                onClick = onClick,
                state = glassState,
                modifier = modifier,
                style = style,
                enabled = enabled,
                contentPadding = ButtonPadding,
                content = glassContent
            )
        }
    } else {
        val content: @Composable RowScope.() -> Unit = {
            if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
            }
            Text(label, maxLines = 1)
        }
        if (primary) {
            Button(onClick = onClick, modifier = modifier, enabled = enabled, content = content)
        } else {
            FilledTonalButton(onClick = onClick, modifier = modifier, enabled = enabled, content = content)
        }
    }
}

@Composable
fun ThemedChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val glassState = LocalGlassState.current
    if (isGlassActive() && glassState != null) {
        val glassContent: @Composable RowScope.() -> Unit = {
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                color = if (selected) GlassTokens.Accent else MaterialTheme.colorScheme.onSurface,
                maxLines = 1
            )
        }
        if (LocalFlatGlass.current) {
            FlatGlassPill(
                onClick = onClick,
                enabled = true,
                tint = if (selected) GlassTokens.Accent.copy(alpha = 0.34f) else FlatGlassFill,
                padding = ChipPadding,
                content = glassContent
            )
        } else {
            val base = GlassPillStyle.tuned(LocalAppearance.current.tune).interactive()
            val style = if (selected) base.copy(tint = GlassTokens.Accent.copy(alpha = 0.34f)) else base
            GlassButton(
                onClick = onClick,
                state = glassState,
                style = style,
                contentPadding = ChipPadding,
                content = glassContent
            )
        }
    } else {
        FilterChip(selected = selected, onClick = onClick, label = { Text(label, maxLines = 1) })
    }
}

@Composable
fun SelectTile(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable ColumnScope.() -> Unit
) {
    val glassState = LocalGlassState.current
    if (isGlassActive() && glassState != null) {
        val shape = RoundedCornerShape(TileShape)
        val tint = if (selected) GlassTokens.Accent.copy(alpha = 0.22f) else Color.Transparent
        Column(
            modifier
                .glassPanel(TileShape, LocalAppearance.current.tune.tint)
                .glassRim(TileShape)
                .background(tint, shape)
                .clickable(enabled = enabled, onClick = onClick)
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            content = content
        )
    } else {
        Card(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier,
            shape = RoundedCornerShape(TileShape),
            colors = CardDefaults.cardColors(
                containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceContainerHigh
            )
        ) {
            Column(
                Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                content = content
            )
        }
    }
}

@Composable
fun AccentCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val glassState = LocalGlassState.current
    if (isGlassActive() && LocalFlatGlass.current) {
        val shape = RoundedCornerShape(GlassTokens.HeroRadius)
        Column(
            modifier
                .fillMaxWidth()
                .glassPanel(GlassTokens.HeroRadius, LocalAppearance.current.tune.tint)
                .background(FlatAccentFill, shape)
                .glassRim(GlassTokens.HeroRadius)
                .padding(GlassTokens.CardPadding),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = content
        )
    } else if (isGlassActive() && glassState != null) {
        GlassSurface(
            state = glassState,
            modifier = modifier.fillMaxWidth().glassRim(GlassTokens.HeroRadius),
            style = GlassHeroStyle.tuned(LocalAppearance.current.tune),
            contentAlignment = Alignment.TopStart
        ) {
            Column(
                Modifier.fillMaxWidth().padding(GlassTokens.CardPadding),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                content = content
            )
        }
    } else {
        Card(
            modifier = modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.extraLarge,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        ) {
            Column(
                Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                content = content
            )
        }
    }
}

@Composable
fun AppSnackbar(data: SnackbarData) {
    val glassState = LocalGlassState.current
    if (isGlassActive() && glassState != null) {
        GlassSnackbar(data, glassState)
    } else {
        Snackbar(
            snackbarData = data,
            shape = MaterialTheme.shapes.large,
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            contentColor = MaterialTheme.colorScheme.onSurface,
            actionContentColor = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
private fun GlassSnackbar(data: SnackbarData, state: LiquidGlassProviderState) {
    val visuals = data.visuals
    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        GlassSurface(
            state = state,
            modifier = Modifier.fillMaxWidth().glassRim(GlassTokens.CardRadius),
            style = GlassCardStyle.tuned(LocalAppearance.current.tune).copy(tint = GlassTokens.PanelTint),
            contentAlignment = Alignment.CenterStart
        ) {
            Row(
                Modifier.fillMaxWidth().padding(start = 18.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    visuals.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                visuals.actionLabel?.let { label ->
                    TextButton(onClick = { data.performAction() }) {
                        Text(label, color = MaterialTheme.colorScheme.primary)
                    }
                }
                if (visuals.withDismissAction) {
                    IconButton(onClick = { data.dismiss() }) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = "Dismiss",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}
