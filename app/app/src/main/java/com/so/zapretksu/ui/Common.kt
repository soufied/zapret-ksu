package com.so.zapretksu.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.TextButton

import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Card
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.liquidglass.compose.GlassHighlight
import dev.liquidglass.compose.GlassRefraction
import dev.liquidglass.compose.GlassShape
import dev.liquidglass.compose.GlassStyle

val GlassCardStyle: GlassStyle = GlassStyle(
    shape = GlassShape.RoundedRectangle(GlassTokens.CardRadius),
    blurRadius = 10.dp,
    refraction = GlassRefraction(height = 16.dp, amount = 26.dp),
    saturation = 1.25f,
    tint = Color(0x0FFFFFFF),
    highlight = GlassHighlight(width = 1.dp, alpha = 0.9f, lightAngleDegrees = 250f),
    noiseAlpha = 0.006f,
    chromaticAberration = 0.3f
)

val GlassHeroStyle: GlassStyle = GlassStyle(
    shape = GlassShape.RoundedRectangle(GlassTokens.HeroRadius),
    blurRadius = 12.dp,
    refraction = GlassRefraction(height = 22.dp, amount = 34.dp),
    saturation = 1.3f,
    tint = Color(0x148FD3FF),
    highlight = GlassHighlight(width = 1.25.dp, alpha = 1f, lightAngleDegrees = 250f),
    noiseAlpha = 0.006f,
    chromaticAberration = 0.45f
)

val GlassPillStyle: GlassStyle = GlassStyle(
    shape = GlassShape.Capsule,
    blurRadius = 8.dp,
    refraction = GlassRefraction(height = 12.dp, amount = 16.dp),
    saturation = 1.25f,
    tint = Color(0x0FFFFFFF),
    highlight = GlassHighlight(width = 1.dp, alpha = 0.9f, lightAngleDegrees = 250f),
    noiseAlpha = 0.006f,
    chromaticAberration = 0.25f
)

fun Modifier.glassRim(radius: Dp): Modifier = drawWithContent {
    drawContent()
    drawRoundRect(
        brush = Brush.linearGradient(
            colors = listOf(GlassTokens.RimHigh, GlassTokens.RimMid, GlassTokens.RimLow),
            start = Offset.Zero,
            end = Offset(size.width, size.height)
        ),
        cornerRadius = CornerRadius(radius.toPx()),
        style = Stroke(width = 1.dp.toPx())
    )
}

fun Modifier.glassSheen(radius: Dp): Modifier = drawWithContent {
    drawContent()
    drawRoundRect(
        brush = Brush.linearGradient(
            colors = listOf(GlassTokens.Sheen, Color.Transparent),
            start = Offset.Zero,
            end = Offset(size.width * 0.55f, size.height * 0.55f)
        ),
        cornerRadius = CornerRadius(radius.toPx())
    )
}

fun Modifier.glassPanel(radius: Dp, strength: Float = 1f): Modifier = this.background(
    Brush.verticalGradient(
        listOf(Color.White.copy(alpha = 0.10f * strength), Color.White.copy(alpha = 0.04f * strength))
    ),
    RoundedCornerShape(radius)
)

@Composable
fun listRowColor(selected: Boolean = false): Color =
    if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow

fun GlassStyle.tuned(t: GlassTune): GlassStyle = copy(
    blurRadius = blurRadius * t.blur,
    tint = if (tint != Color.Unspecified) tint.copy(alpha = (tint.alpha * t.tint).coerceIn(0f, 1f)) else tint,
    highlight = GlassHighlight(
        width = highlight.width,
        alpha = (highlight.alpha * t.rim).coerceIn(0f, 1f),
        lightAngleDegrees = highlight.lightAngleDegrees
    )
)

@Composable
fun Page(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        content = content
    )
}

val LocalPanelMotion = compositionLocalOf { false }

@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val glass = LocalAppearance.current.mode == ThemeMode.GLASS
    val glassState = LocalGlassState.current
    if (glass && glassState != null) {
        val panelMotion = rememberLiquidMotion()
        Column(
            modifier
                .then(
                    if (LocalPanelMotion.current && !LocalAppearance.current.optimized) {
                        Modifier.liquidMotion(panelMotion, grabDelayMillis = 250L)
                    } else {
                        Modifier
                    }
                )
                .glassPanel(GlassTokens.CardRadius, LocalAppearance.current.tune.tint)
                .glassRim(GlassTokens.CardRadius)
                .then(if (onClick != null) Modifier.tvFocus(RoundedCornerShape(GlassTokens.CardRadius)).clickable(onClick = onClick) else Modifier)
                .padding(GlassTokens.CardPadding),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = content
        )
    } else if (onClick != null) {
        Card(
            onClick = onClick,
            modifier = modifier,
            shape = Md3CardShape,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
        ) {
            Column(
                Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                content = content
            )
        }
    } else {
        Card(
            modifier = modifier,
            shape = Md3CardShape,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
        ) {
            Column(
                Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                content = content
            )
        }
    }
}

@Composable
fun SectionCard(title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    if (title != null && LocalAppearance.current.mode == ThemeMode.MD3) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 4.dp)
            )
            AppCard(Modifier.fillMaxWidth(), content = content)
        }
    } else {
        AppCard(Modifier.fillMaxWidth()) {
            if (title != null) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            content()
        }
    }
}

@Composable
fun KeyValue(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun ToggleRow(title: String, subtitle: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    if (LocalAppearance.current.mode == ThemeMode.MD3) {
        val supporting: (@Composable () -> Unit)? = if (subtitle.isNotBlank()) {
            { Text(subtitle) }
        } else {
            null
        }
        ListItem(
            headlineContent = { Text(title, style = MaterialTheme.typography.titleMedium) },
            supportingContent = supporting,
            trailingContent = { Switch(checked = checked, onCheckedChange = onChange, enabled = enabled) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier.fillMaxWidth()
        )
        return
    }
    val subtitleColor by animateColorAsState(
        targetValue = if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        label = "toggleSubtitle", animationSpec = zapretTween()
    )
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            if (subtitle.isNotBlank()) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = subtitleColor)
            }
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Segmented(options: List<String>, selected: Int, enabled: Boolean = true, onSelect: (Int) -> Unit) {
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, label ->
            SegmentedButton(
                selected = index == selected,
                onClick = { onSelect(index) },
                enabled = enabled,
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                label = { Text(label) }
            )
        }
    }
}

@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val glass = LocalAppearance.current.mode == ThemeMode.GLASS
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = if (glass) GlassTokens.Panel else AlertDialogDefaults.containerColor,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                onConfirm()
            }) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
fun Msg(text: String) {
    if (text.isNotEmpty()) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
fun StatusPill(text: String, color: Color) {
    val animated by animateColorAsState(targetValue = color, label = "pill", animationSpec = zapretTween())
    Box(
        Modifier
            .clip(RoundedCornerShape(percent = 50))
            .background(animated.copy(alpha = 0.18f), RoundedCornerShape(percent = 50))
            .border(1.dp, animated.copy(alpha = 0.5f), RoundedCornerShape(percent = 50))
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = animated)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StrategyPicker(value: String, options: List<String>, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val shape = RoundedCornerShape(GlassTokens.TileRadius)
    val glass = LocalAppearance.current.mode == ThemeMode.GLASS

    OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
        Text(
            value.ifBlank { "Choose strategy" },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
    }

    if (open) {
        ModalBottomSheet(
            onDismissRequest = {
                open = false
                query = ""
            },
            sheetState = sheetState,
            containerColor = if (glass) GlassTokens.Panel else BottomSheetDefaults.ContainerColor
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Strategy", style = MaterialTheme.typography.titleLarge)
                Text(
                    "${options.size} available",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Search strategies") },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    modifier = Modifier.fillMaxWidth()
                )
                val shown = remember(options, query) {
                    options.filter { query.isBlank() || it.contains(query, ignoreCase = true) }
                }
                LazyColumn(
                    Modifier.fillMaxWidth().heightIn(max = 460.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(shown, key = { it }) { o ->
                        val on = o == value
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(shape)
                                .background(
                                    listRowColor(on),
                                    shape
                                )
                                .tvFocus(shape)
                                .clickable {
                                    onPick(o)
                                    open = false
                                    query = ""
                                }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                o,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            if (on) {
                                Icon(
                                    Icons.Filled.Check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
