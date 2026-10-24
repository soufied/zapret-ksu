package com.so.zapretksu.ui

import androidx.compose.material.icons.filled.Edit
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.background

@Composable
fun ToolsScreen(onOpen: (Route) -> Unit) {
    Page {
        ToolCard(0, "Apps", "Choose which apps go through or around zapret.", Icons.Filled.Apps, Route.Apps, onOpen)
        ToolCard(1, "Updates", "Engines, presets, strategies and DNS providers.", Icons.Filled.Download, Route.Updates, onOpen)
        ToolCard(2, "Blockcheck", "Find the preset that unblocks your domains.", Icons.Filled.Search, Route.Blockcheck, onOpen)
        ToolCard(3, "DNS", "DoH proxy, provider tests and Private DNS.", Icons.Filled.Lock, Route.Dns, onOpen)
        ToolCard(4, "Lists", "Edit domain lists and strategy presets.", Icons.Filled.Edit, Route.Lists, onOpen)
        ToolCard(5, "TG WS Proxy", "Local Telegram proxy over WebSocket.", Icons.AutoMirrored.Filled.Send, Route.Tg, onOpen)
    }
}

@Composable
private fun ToolCard(
    index: Int,
    title: String,
    description: String,
    icon: ImageVector,
    route: Route,
    onOpen: (Route) -> Unit
) {
    AppCard(Modifier.fillMaxWidth().staggeredEntrance(index), onClick = { onOpen(route) }) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(
                Modifier
                    .size(48.dp)
                    .background(
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                        RoundedCornerShape(GlassTokens.TileRadius)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
