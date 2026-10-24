package com.so.zapretksu.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.so.zapretksu.ZapretCtl
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val DEFAULT_PORT = "1443"
private const val HEX = "0123456789abcdef"

@Composable
fun TgProxyScreen() {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var status by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var log by remember { mutableStateOf<List<String>>(emptyList()) }
    var checkOutput by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    var portText by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }

    suspend fun refresh() {
        status = ZapretCtl.tgStatus()
        log = ZapretCtl.tgLog(60)
    }

    LaunchedEffect(Unit) {
        refresh()
        portText = status["tg_port"].orEmpty()
        while (true) {
            if (!busy) refresh()
            delay(4000)
        }
    }

    fun act(label: String, block: suspend () -> ZapretCtl.Result) {
        scope.launch {
            busy = true
            val r = block()
            msg = if (r.ok) "$label: done" else r.text.ifBlank { "$label failed" }
            refresh()
            busy = false
        }
    }

    val installed = status["tg_installed"] == "1"
    val running = status["tg_running"] == "1"
    val port = status["tg_port"].orEmpty().ifBlank { DEFAULT_PORT }
    val secret = status["tg_secret"].orEmpty()
    val lan = status["tg_lan"] == "1"
    val fallback = status["tg_default"] == "1"
    val host = status["tg_host"].orEmpty().ifBlank { "127.0.0.1" }
    val version = status["tg_version"].orEmpty()
    val link = if (secret.isNotBlank()) "tg://proxy?server=$host&port=$port&secret=dd$secret" else ""

    fun applySetting(label: String, key: String, value: String) {
        act(label) {
            val r = ZapretCtl.set(key, value)
            if (r.ok && running) ZapretCtl.tgStart() else r
        }
    }

    fun runCheck() {
        scope.launch {
            checking = true
            val r = ZapretCtl.tgCheck()
            checkOutput = r.text.ifBlank { "No output" }
            checking = false
        }
    }

    Page {
        AppCard(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PulseDot(running)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        when {
                            !installed -> "Not installed"
                            running -> "Running"
                            else -> "Stopped"
                        },
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        if (running) "$host:$port" else "Local Telegram proxy over WebSocket",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (version.isNotBlank()) {
                        Text(
                            "tg-ws-proxy $version",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    enabled = installed && !running && !busy,
                    onClick = { act("Start") { ZapretCtl.tgStart() } }
                ) { Text("Start") }
                OutlinedButton(
                    enabled = running && !busy,
                    onClick = { act("Stop") { ZapretCtl.tgStop() } }
                ) { Text("Stop") }
            }
            if (!installed && status.isNotEmpty()) {
                Text(
                    "tg-ws-proxy is not downloaded yet. Get it in Tools -> Updates -> tg-ws-proxy.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }

        AppCard(Modifier.fillMaxWidth()) {
            Text("Settings", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = portText,
                onValueChange = { portText = it.filter(Char::isDigit).take(5) },
                label = { Text("Port") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )
            FilledTonalButton(
                enabled = !busy && portText.isNotBlank() && portText != port,
                onClick = { applySetting("Port", "TG_PORT", portText) }
            ) { Text("Apply port") }
            ToggleRow(
                title = "Allow LAN devices",
                subtitle = "Listen on all interfaces so other devices on your Wi-Fi can use this proxy.",
                checked = lan,
                enabled = !busy
            ) { on -> applySetting("LAN access", "TG_LAN", if (on) "1" else "0") }
            ToggleRow(
                title = "Cloudflare fallback",
                subtitle = "Use the built-in Cloudflare domain list when direct Telegram WebSocket is blocked.",
                checked = fallback,
                enabled = !busy
            ) { on -> applySetting("Cloudflare fallback", "TG_DEFAULT_DOMAINS", if (on) "1" else "0") }
            Text("Secret", style = MaterialTheme.typography.labelLarge)
            Text(
                secret.ifBlank { "Generated on first start" },
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            OutlinedButton(
                enabled = !busy,
                onClick = { applySetting("New secret", "TG_SECRET", randomSecret()) }
            ) { Text("New secret") }
        }

        AppCard(Modifier.fillMaxWidth()) {
            Text("Telegram link", style = MaterialTheme.typography.titleMedium)
            Text(
                if (link.isBlank()) "Start the proxy once to generate a link." else link,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilledTonalButton(
                    enabled = link.isNotBlank(),
                    onClick = {
                        try {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(link)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        } catch (e: ActivityNotFoundException) {
                            msg = "No app can open tg:// links. Install Telegram first."
                        }
                    }
                ) { Text("Open in Telegram") }
                OutlinedButton(
                    enabled = link.isNotBlank(),
                    onClick = {
                        clipboard.setText(AnnotatedString(link))
                        msg = "Link copied"
                    }
                ) { Text("Copy") }
            }
        }

        AppCard(Modifier.fillMaxWidth()) {
            Text("Diagnostics", style = MaterialTheme.typography.titleMedium)
            Text(
                "Runs tg-ws-proxy --check against its upstreams and reports the result.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedButton(enabled = installed && !checking, onClick = { runCheck() }) {
                Text(if (checking) "Checking…" else "Run check")
            }
            checkOutput?.let { MonoBlock(it) }
        }

        AppCard(Modifier.fillMaxWidth()) {
            Text("Log", style = MaterialTheme.typography.titleMedium)
            MonoBlock(if (log.isEmpty()) "No output yet." else log.joinToString("\n"))
        }

        Msg(msg)
    }
}

@Composable
private fun MonoBlock(text: String) {
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(max = 220.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun PulseDot(active: Boolean) {
    val color = if (active) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.outline
    Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
        if (active && !LocalAppearance.current.optimized) {
            val pulse = rememberInfiniteTransition(label = "tgPulse")
            val pulseScale by pulse.animateFloat(
                initialValue = 0.6f,
                targetValue = 1.8f,
                animationSpec = infiniteRepeatable(tween(1400), RepeatMode.Restart),
                label = "tgPulseScale"
            )
            val pulseAlpha by pulse.animateFloat(
                initialValue = 0.5f,
                targetValue = 0f,
                animationSpec = infiniteRepeatable(tween(1400), RepeatMode.Restart),
                label = "tgPulseAlpha"
            )
            Box(
                Modifier
                    .size(12.dp)
                    .graphicsLayer {
                        scaleX = pulseScale
                        scaleY = pulseScale
                        alpha = pulseAlpha
                    }
                    .background(color, CircleShape)
            )
        }
        Box(Modifier.size(12.dp).background(color, CircleShape))
    }
}

private fun randomSecret(): String = buildString { repeat(32) { append(HEX.random()) } }
