package com.so.zapretksu.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.so.zapretksu.ZapretCtl
import kotlinx.coroutines.launch

private enum class CompState(val label: String) {
    NotInstalled("Not installed"),
    UpdateAvailable("Update available"),
    UpToDate("Up to date"),
    Installed("Installed")
}

private data class Component(
    val target: String,
    val title: String,
    val note: String,
    val icon: ImageVector,
    val installed: String,
    val latest: String
) {
    val state: CompState
        get() = when {
            installed.isBlank() -> CompState.NotInstalled
            latest.isBlank() || latest == "unknown" -> CompState.Installed
            latest == installed -> CompState.UpToDate
            else -> CompState.UpdateAvailable
        }
}

@Composable
fun ComponentsScreen() {
    val scope = rememberCoroutineScope()
    var installed by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var latest by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var checking by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }

    val snap = LocalSnapshot.current?.value
    val update = snap?.update.orEmpty()
    val running = update["update_state"] == "running"
    val target = update["update_target"].orEmpty()
    val step = update["update_step"].orEmpty()
    val lastMessage = update["update_message"].orEmpty()

    fun check() {
        scope.launch {
            checking = true
            latest = ZapretCtl.latestVersions()
            checking = false
        }
    }

    LaunchedEffect(Unit) { check() }
    LaunchedEffect(running) { installed = ZapretCtl.installedVersions() }

    val components = listOf(
        Component("nfqws2", "zapret 2 engine", "nfqws2, the main engine", Icons.Filled.Build,
            installed["installed_nfqws2"].orEmpty(), latest["latest_nfqws2"].orEmpty()),
        Component("nfqws1", "zapret 1 engine", "nfqws, the legacy engine", Icons.Filled.Build,
            installed["installed_nfqws1"].orEmpty(), latest["latest_nfqws1"].orEmpty()),
        Component("presets", "zapret 2 presets", "Preset files for nfqws2", Icons.Filled.Edit,
            installed["installed_presets"].orEmpty(), ""),
        Component("strategies", "zapret 1 strategies", "Strategy files for nfqws", Icons.Filled.Edit,
            installed["installed_strategies"].orEmpty(), latest["latest_strategies"].orEmpty()),
        Component("dnscrypt", "dnscrypt-proxy", "Encrypted DNS (DoH) proxy", Icons.Filled.Lock,
            installed["installed_dnscrypt"].orEmpty(), latest["latest_dnscrypt"].orEmpty()),
        Component("tgws", "tg-ws-proxy", "Telegram WebSocket proxy", Icons.AutoMirrored.Filled.Send,
            installed["installed_tgws"].orEmpty(), latest["latest_tgws"].orEmpty())
    )
    val updates = components.count { it.state == CompState.UpdateAvailable }
    val missing = components.count { it.state == CompState.NotInstalled }

    Page {
        AppCard(Modifier.fillMaxWidth()) {
            Text("Components", style = MaterialTheme.typography.titleLarge)
            Text(
                when {
                    checking -> "Checking GitHub…"
                    latest.isEmpty() -> "Tap Check to look for updates"
                    updates == 0 && missing == 0 -> "Everything is up to date"
                    else -> listOfNotNull(
                        if (updates > 0) "$updates update(s) available" else null,
                        if (missing > 0) "$missing not installed" else null
                    ).joinToString(" · ")
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(enabled = !checking && !running, onClick = { check() }) {
                    Text(if (checking) "Checking…" else "Check")
                }
                FilledTonalButton(
                    enabled = !running,
                    onClick = { scope.launch { msg = ZapretCtl.update("all").text } }
                ) { Text("Update all") }
            }
        }

        if (running) {
            AppCard(Modifier.fillMaxWidth()) {
                Text("Updating $target", style = MaterialTheme.typography.titleSmall)
                LinearProgressIndicator(Modifier.fillMaxWidth())
                if (step.isNotBlank()) {
                    Text(step, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                OutlinedButton(
                    onClick = { scope.launch { msg = ZapretCtl.updateCancel().text } },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Cancel update") }
            }
        } else if (lastMessage.isNotBlank()) {
            Text(
                "Last run: $lastMessage",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        components.forEach { c ->
            ComponentRow(c, enabled = !running) {
                scope.launch { msg = ZapretCtl.update(c.target).text }
            }
        }
        Msg(msg)
    }
}

@Composable
private fun ComponentRow(c: Component, enabled: Boolean, onAction: () -> Unit) {
    val state = c.state
    val tone = when (state) {
        CompState.NotInstalled -> MaterialTheme.colorScheme.error
        CompState.UpdateAvailable -> MaterialTheme.colorScheme.primary
        CompState.UpToDate -> MaterialTheme.colorScheme.tertiary
        CompState.Installed -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val actionLabel = when (state) {
        CompState.NotInstalled -> "Install"
        CompState.UpdateAvailable, CompState.Installed -> "Update"
        CompState.UpToDate -> "Reinstall"
    }
    AppCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                Modifier
                    .size(44.dp)
                    .background(tone.copy(alpha = 0.14f), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(c.icon, contentDescription = null, tint = tone)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(c.title, style = MaterialTheme.typography.titleMedium)
                Text(
                    c.note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(state.label, style = MaterialTheme.typography.labelMedium, color = tone)
        }
        Text(
            versionLine(c),
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (state == CompState.UpToDate) {
            OutlinedButton(enabled = enabled, onClick = onAction, modifier = Modifier.fillMaxWidth()) {
                Text(actionLabel)
            }
        } else {
            Button(enabled = enabled, onClick = onAction, modifier = Modifier.fillMaxWidth()) {
                Text(actionLabel)
            }
        }
    }
}

private fun versionLine(c: Component): String {
    val inst = c.installed.ifBlank { "-" }
    return if (c.latest.isBlank()) "installed $inst" else "installed $inst · latest ${c.latest}"
}
