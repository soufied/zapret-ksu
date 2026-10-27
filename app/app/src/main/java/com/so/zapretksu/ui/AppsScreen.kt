package com.so.zapretksu.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.clickable
import android.content.Context
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.so.zapretksu.ZapretCtl
import com.so.zapretksu.ZapretCtl.AppEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object AppIconCache {
    private val cache = LruCache<String, ImageBitmap>(160)

    fun get(packageName: String): ImageBitmap? = cache.get(packageName)

    fun load(context: Context, packageName: String): ImageBitmap? {
        cache.get(packageName)?.let { return it }
        return try {
            val bitmap = context.packageManager
                .getApplicationIcon(packageName)
                .toBitmap(width = 96, height = 96)
                .asImageBitmap()
            cache.put(packageName, bitmap)
            bitmap
        } catch (e: Exception) {
            null
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppsScreen() {
    val scope = rememberCoroutineScope()
    var apps by remember { mutableStateOf<List<AppEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var mode by remember { mutableStateOf("disabled") }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var query by remember { mutableStateOf("") }
    var onlyUser by remember { mutableStateOf(true) }
    var msg by remember { mutableStateOf("") }

    fun reload() {
        scope.launch {
            loading = true
            val d = ZapretCtl.dump()
            val m = d["APP_MODE"].orEmpty()
            mode = if (m.isBlank()) "disabled" else m
            selected = d["APP_LIST"].orEmpty().split(",").filter { it.isNotBlank() }.toSet()
            apps = ZapretCtl.appsList()
            loading = false
        }
    }

    LaunchedEffect(Unit) { reload() }

    val modes = listOf("disabled", "include", "exclude")
    val shown = remember(apps, onlyUser, query) {
        apps.filter { a ->
            (!onlyUser || a.user) &&
                (query.isBlank() || a.label.contains(query, true) || a.pkg.contains(query, true))
        }
    }

    Column(
        Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(18.dp))
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    "Filter",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    when (mode) {
                        "include" -> "Only selected apps use zapret"
                        "exclude" -> "Selected apps skip zapret"
                        else -> "Off"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Segmented(options = listOf("Off", "Include", "Exclude"), selected = modes.indexOf(mode).coerceAtLeast(0)) {
                mode = modes[it]
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                FilledTonalButton(
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                    onClick = {
                        scope.launch {
                            val r1 = ZapretCtl.set("APP_MODE", mode)
                            val r2 = ZapretCtl.set("APP_LIST", selected.joinToString(","))
                            msg = if (r1.ok && r2.ok) "Saved" else "${r1.text} ${r2.text}".trim()
                        }
                    }
                ) { Text("Save", maxLines = 1) }
                OutlinedButton(
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                    onClick = { reload() }
                ) { Text("Reload", maxLines = 1) }
                Spacer(Modifier.weight(1f))
                if (msg.isNotBlank()) {
                    Text(msg, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Search apps") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FilterChip(
                selected = onlyUser,
                onClick = { onlyUser = !onlyUser },
                label = { Text("User apps") }
            )
            Text(
                "${selected.size} selected",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (loading) {
            Text("Loading apps…", style = MaterialTheme.typography.bodyMedium)
        } else if (shown.isEmpty()) {
            Text("No apps match.", style = MaterialTheme.typography.bodyMedium)
        }

        LazyColumn(
            Modifier.fillMaxWidth().weight(1f),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(shown, key = { it.pkg + it.uid }) { app ->
                val checked = app.pkg in selected || app.uid in selected
                AppRow(app = app, checked = checked) {
                    selected = if (checked) selected - app.pkg - app.uid else selected + app.pkg
                }
            }
        }
    }
}

@Composable
private fun AppRow(app: AppEntry, checked: Boolean, onToggle: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(listRowColor(checked), shape)
            .tvFocus(shape)
            .clickable(onClick = onToggle)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        AppIconTile(app.pkg)
        Column(Modifier.weight(1f)) {
            Text(
                app.label,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                app.pkg,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Checkbox(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun AppIconTile(packageName: String) {
    val context = LocalContext.current
    val icon by produceState<ImageBitmap?>(
        initialValue = AppIconCache.get(packageName),
        key1 = packageName
    ) {
        if (value == null) {
            value = withContext(Dispatchers.IO) { AppIconCache.load(context, packageName) }
        }
    }
    val shape = RoundedCornerShape(GlassTokens.TileRadius)
    Box(
        Modifier
            .size(46.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest, shape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape),
        contentAlignment = Alignment.Center
    ) {
        val bitmap = icon
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                modifier = Modifier.fillMaxSize().padding(5.dp)
            )
        } else {
            Icon(
                Icons.Filled.Android,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(26.dp)
            )
        }
    }
}
