package com.so.zapretksu.ui

import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Close
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.IconButton
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.so.zapretksu.ZapretCtl
import dev.liquidglass.compose.GlassStyle
import dev.liquidglass.compose.liquidGlass
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun HomeScreen() {
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var dns by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var tg by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var busy by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<Feedback?>(null) }
    LaunchedEffect(feedback) {
        if (feedback?.ok == true) {
            delay(4000)
            feedback = null
        }
    }
    var confirmRestore by remember { mutableStateOf(false) }
    var wlRunning by remember { mutableStateOf(false) }
    var wl by remember { mutableStateOf<ZapretCtl.WhitelistReport?>(null) }

    suspend fun refresh() {
        status = ZapretCtl.status()
        dns = ZapretCtl.dnsStatus()
        tg = ZapretCtl.tgStatus()
    }

    LaunchedEffect(Unit) {
        while (true) {
            if (!busy) refresh()
            delay(4000)
        }
    }

    fun act(label: String, block: suspend () -> ZapretCtl.Result) {
        scope.launch {
            busy = true
            val r = block()
            feedback = Feedback(label = label, ok = r.ok, detail = r.text.trim())
            refresh()
            busy = false
        }
    }

    val running = status["nfqws_running"] == "1"
    val dohOn = dns["doh_running"] == "1"
    val dohInstalled = dns["doh_binary"] == "yes"
    val tgInstalled = tg["tg_installed"] == "1"
    val tgOn = tg["tg_running"] == "1"

    CompositionLocalProvider(LocalPanelMotion provides true) {
    Page {
        feedback?.let { f -> FeedbackBanner(f) { feedback = null } }
        HeroSurface(running = running) {
            HeroContent(running = running, busy = busy, status = status) { on ->
                act(if (on) "Start" else "Stop") {
                    if (on) ZapretCtl.start() else ZapretCtl.stop()
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MetricCard("Engine", status["engine"] ?: "-", Modifier.weight(1f))
            MetricCard(
                "Firewall",
                if (status["firewall_active"] == "1") "Active" else "Off",
                Modifier.weight(1f)
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (dohInstalled) MetricCard("DNS proxy", if (dohOn) "On" else "Off", Modifier.weight(1f))
            if (tgInstalled) MetricCard("TG proxy", if (tgOn) "On" else "Off", Modifier.weight(1f))
            MetricCard("Auto profile", status["auto_profile"]?.ifBlank { "off" } ?: "off", Modifier.weight(1f))
        }

        if (dohInstalled) SectionCard("Encrypted DNS") {
            ToggleRow(
                title = "DNS proxy (DoH)",
                subtitle = if (dohOn) "Running on port ${dns["doh_port"] ?: "5354"}" else "Off",
                checked = dohOn,
                enabled = !busy
            ) { on ->
                act(if (on) "DNS proxy start" else "DNS proxy stop") {
                    if (on) ZapretCtl.dohStart() else ZapretCtl.dohStop()
                }
            }
            KeyValue("Private DNS", dns["private_dns_mode"] ?: "-")
        }

        if (tgInstalled) SectionCard("TG WS Proxy") {
            ToggleRow(
                title = "Proxy",
                subtitle = if (tgOn) "Running on ${tg["tg_host"] ?: "127.0.0.1"}:${tg["tg_port"] ?: "1443"}" else "Off",
                checked = tgOn,
                enabled = !busy
            ) { on ->
                act(if (on) "TG proxy start" else "TG proxy stop") {
                    if (on) ZapretCtl.tgStart() else ZapretCtl.tgStop()
                }
            }
            KeyValue("Version", tg["tg_version"]?.ifBlank { "-" } ?: "-")
        }

        SectionCard("Actions") {
            FilledTonalButton(
                enabled = !busy,
                onClick = { act("Restart") { ZapretCtl.restart() } },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Restart", maxLines = 1) }
            OutlinedButton(
                enabled = !busy,
                onClick = { confirmRestore = true },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Restore network", maxLines = 1) }
            OutlinedButton(
                enabled = !busy && !wlRunning,
                onClick = {
                    scope.launch {
                        wlRunning = true
                        wl = ZapretCtl.whitelistCheck()
                        wlRunning = false
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (wlRunning) "Checking..." else "Check whitelists", maxLines = 1) }
            Text(
                "Stops zapret and removes its firewall rules, so normal networking returns.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        AnimatedVisibility(visible = wlRunning || wl != null, enter = zapretEnter(), exit = zapretExit()) {
            WhitelistCard(wl, wlRunning)
        }

        SectionCard("Details") {
            KeyValue("Module", status["module_version"] ?: "-")
            KeyValue("Strategy", status["strategy"] ?: "-")
            KeyValue("Active engine", status["active_engine"]?.ifBlank { "-" } ?: "-")
            KeyValue("Firewall backend", status["firewall_backend"]?.ifBlank { "-" } ?: "-")
        }

    }
    }

    if (confirmRestore) {
        ConfirmDialog(
            title = "Restore network?",
            text = "Stops zapret and removes its firewall rules. Your normal connection returns.",
            confirmLabel = "Restore",
            onConfirm = { act("Restore network") { ZapretCtl.restoreNetwork() } },
            onDismiss = { confirmRestore = false }
        )
    }
}

@Composable
private fun WhitelistCard(report: ZapretCtl.WhitelistReport?, running: Boolean) {
    val verdict = if (running) null else report?.verdict
    val headline = when (verdict) {
        "no_internet" -> "No internet access"
        "whitelist" -> "Whitelist detected"
        "ru_unreachable" -> "Russian sites unreachable"
        "partial" -> "Partial access"
        "open" -> "No whitelist"
        "error" -> "Check failed"
        else -> "Checking"
    }
    val detail = when (verdict) {
        "no_internet" -> "None of the six sites answered. Check the connection, or restore the network from Actions."
        "whitelist" -> "Only Russian sites answer. Global services are blocked."
        "ru_unreachable" -> "Global sites answer, but the Russian ones do not."
        "partial" -> "Some sites answer and some do not. Run the check again."
        "open" -> "All six sites answer. The connection is open."
        "error" -> "The check could not run. Make sure the module is installed."
        else -> "Probing six sites in parallel"
    }
    val accent = when (verdict) {
        "open" -> MaterialTheme.colorScheme.primary
        "whitelist", "partial" -> MaterialTheme.colorScheme.tertiary
        null -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.error
    }
    val color by animateColorAsState(accent, zapretTween(400), label = "whitelistAccent")
    val pulse by if (LocalAppearance.current.optimized) {
        remember { mutableStateOf(1f) }
    } else {
        rememberInfiniteTransition(label = "whitelistPulse").animateFloat(
            initialValue = 0.35f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse),
            label = "whitelistPulse"
        )
    }
    SectionCard("Whitelist check") {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                Modifier
                    .size(12.dp)
                    .alpha(if (verdict == null) pulse else 1f)
                    .clip(CircleShape)
                    .background(color)
            )
            Text(headline, style = MaterialTheme.typography.titleMedium, color = color)
        }
        Text(
            detail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (verdict != null && report != null && report.sites.isNotEmpty()) {
            Text(
                if (report.engineOn) "Zapret is on, so results include its bypass." else "Zapret is off, so results show the network as it is.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            report.sites.forEachIndexed { i, site -> WhitelistSiteRow(site, i) }
        }
    }
}

@Composable
private fun WhitelistSiteRow(site: ZapretCtl.WhitelistSite, index: Int) {
    var shown by remember(site.host) { mutableStateOf(false) }
    LaunchedEffect(site.host) {
        delay(index * 120L)
        shown = true
    }
    AnimatedVisibility(
        visible = shown,
        exit = zapretExit(),
        enter = fadeIn(zapretTween(300)) + slideInVertically(zapretTween(300)) { it / 2 }
    ) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                if (site.ru) "RU" else "GLOBAL",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(56.dp)
            )
            Text(
                site.host,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val c by animateColorAsState(
                if (site.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                zapretTween(300),
                label = "whitelistSite"
            )
            Text(
                if (site.ok) "${site.ms} ms" else "no answer",
                style = MaterialTheme.typography.labelLarge,
                color = c
            )
        }
    }
}

@Composable
private fun HeroSurface(running: Boolean, content: @Composable ColumnScope.() -> Unit) {
    val glass = LocalAppearance.current.mode == ThemeMode.GLASS
    val glassState = LocalGlassState.current
    if (glass && glassState != null) {
        val motion = rememberLiquidMotion()
        val heroStyle = GlassHeroStyle.tuned(LocalAppearance.current.tune)
        val provider: () -> GlassStyle = remember(motion, heroStyle) { { motion.glassStyleFor(heroStyle) } }
        val optimized = LocalAppearance.current.optimized
        Column(
            Modifier
                .fillMaxWidth()
                .then(if (optimized) Modifier else Modifier.liquidMotion(motion, grabDelayMillis = 250L))
                .liquidGlass(glassState, provider)
                .glassRim(GlassTokens.HeroRadius)
                .glassSheen(GlassTokens.HeroRadius)
                .padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            content = content
        )
    } else {
        val container by animateColorAsState(
            targetValue = if (running) MaterialTheme.colorScheme.tertiaryContainer
            else MaterialTheme.colorScheme.surfaceContainerHigh,
            animationSpec = zapretTween(durationMillis = 400),
            label = "heroContainer"
        )
        Card(
            Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.extraLarge,
            colors = CardDefaults.cardColors(containerColor = container)
        ) {
            Column(
                Modifier.padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                content = content
            )
        }
    }
}

@Composable
private fun HeroContent(
    running: Boolean,
    busy: Boolean,
    status: Map<String, String>,
    onToggle: (Boolean) -> Unit
) {
    val accent by animateColorAsState(
        targetValue = if (running) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
        label = "heroAccent", animationSpec = zapretTween()
    )
    val md3 = LocalAppearance.current.mode == ThemeMode.MD3
    val onHero = MaterialTheme.colorScheme.onTertiaryContainer
    val kicker = if (md3 && running) onHero else MaterialTheme.colorScheme.primary
    val headline = if (md3) (if (running) onHero else MaterialTheme.colorScheme.onSurface) else accent
    val subInk = if (md3 && running) onHero.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        StatusOrb(
            running = running,
            modifier = Modifier
                .size(116.dp)
                .clip(CircleShape)
                .tvFocus(CircleShape)
                .clickable(enabled = !busy) { onToggle(!running) }
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "zapret KSU",
                style = MaterialTheme.typography.labelLarge,
                color = kicker
            )
            Text(
                if (running) "Active" else "Idle",
                style = MaterialTheme.typography.headlineMedium,
                color = headline
            )
            Text(
                "${status["engine"] ?: "-"} · ${status["strategy"] ?: "-"}",
                style = MaterialTheme.typography.bodyMedium,
                color = subInk,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            StatusPill(if (running) "Engine running" else "Engine stopped", accent)
        }
    }
    Text(
        "Tap the orb to turn bypass ${if (running) "off" else "on"}.",
        style = MaterialTheme.typography.bodySmall,
        color = subInk
    )
}

@Composable
private fun StatusOrb(running: Boolean, modifier: Modifier) {
    val optimized = LocalAppearance.current.optimized
    val pulseState = if (optimized) {
        null
    } else {
        rememberInfiniteTransition(label = "orb").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1800, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "pulse"
        )
    }
    val color by animateColorAsState(
        targetValue = if (running) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = zapretTween(durationMillis = 500),
        label = "orbColor"
    )
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val pulse = pulseState?.value ?: 0f
            val outer = size.minDimension / 2f
            if (running) {
                drawCircle(
                    color = color.copy(alpha = 0.4f * (1f - pulse)),
                    radius = outer * (0.6f + 0.4f * pulse)
                )
            }
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(color.copy(alpha = 0.95f), color.copy(alpha = 0.22f)),
                    radius = outer * 0.8f
                ),
                radius = outer * 0.8f
            )
            drawCircle(
                color = color.copy(alpha = 0.7f),
                radius = outer * 0.8f,
                style = Stroke(width = 2.dp.toPx())
            )
        }
        Icon(
            Icons.Filled.PowerSettingsNew,
            contentDescription = null,
            tint = if (running) MaterialTheme.colorScheme.onTertiary else MaterialTheme.colorScheme.surface,
            modifier = Modifier.size(40.dp)
        )
    }
}

@Composable
private fun MetricCard(label: String, value: String, modifier: Modifier) {
    AppCard(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private data class Feedback(val label: String, val ok: Boolean, val detail: String, val at: Long = System.nanoTime())

@Composable
private fun FeedbackBanner(feedback: Feedback, onDismiss: () -> Unit) {
    var expanded by remember(feedback.at) { mutableStateOf(false) }
    val tint = if (feedback.ok) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error
    AppCard(Modifier.fillMaxWidth(), onClick = { expanded = !expanded }) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(
                if (feedback.ok) Icons.Filled.CheckCircle else Icons.Filled.Error,
                contentDescription = null,
                tint = tint
            )
            Column(Modifier.weight(1f)) {
                Text(
                    if (feedback.ok) "${feedback.label} done" else "${feedback.label} failed",
                    style = MaterialTheme.typography.titleSmall,
                    color = tint
                )
                if (!feedback.ok) {
                    Text(
                        feedback.detail.lineSequence().firstOrNull().orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            IconButton(onClick = onDismiss) {
                Icon(Icons.Filled.Close, contentDescription = "Dismiss")
            }
        }
        if (expanded && feedback.detail.isNotBlank()) {
            Text(
                feedback.detail.lines().take(12).joinToString("\n"),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
