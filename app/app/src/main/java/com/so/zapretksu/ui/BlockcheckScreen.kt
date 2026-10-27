package com.so.zapretksu.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.so.zapretksu.ZapretCtl
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

private val ENGINE_KEYS = listOf("config", "nfqws2", "nfqws1")
private val ENGINE_LABELS = listOf("Config", "nfqws2", "nfqws1")
private val DNS_KEYS = listOf("auto", "doh", "dot")
private val DNS_LABELS = listOf("Auto", "DoH", "DoT")
private val PER_LIST_VALUES = listOf(1, 3, 5, 10)

private data class BcRow(
    val name: String,
    val score: Int,
    val max: Int,
    val http: String,
    val tls12: String,
    val tls13: String,
    val quic: String,
    val avgMs: Int,
    val note: String,
    val domOk: Int,
    val domTotal: Int,
    val dns: String,
    val domains: List<String>,
    val rating: Int
) {
    val isBaseline: Boolean get() = name.startsWith("__baseline")
    val usable: Boolean get() = !isBaseline && note.isBlank() && score > 0
    val fraction: Float get() = rating / 1000f
    val title: String
        get() = when (name) {
            "__baseline__" -> "No bypass · plain DNS"
            "__baseline_enc__" -> "No bypass · ${dnsName(dns)}"
            else -> name.substringBeforeLast('.')
        }
}

private fun parseBcRow(line: String): BcRow? {
    val p = line.split("|")
    if (p.size < 12) return null
    return BcRow(
        name = p[0],
        score = p[1].toIntOrNull() ?: 0,
        max = p[2].toIntOrNull() ?: 0,
        http = p[3],
        tls12 = p[4],
        tls13 = p[5],
        quic = p[6],
        avgMs = p[7].toIntOrNull() ?: 0,
        note = p[8],
        domOk = p[9].toIntOrNull() ?: 0,
        domTotal = p[10].toIntOrNull() ?: 0,
        dns = p[11],
        domains = p.getOrNull(12).orEmpty().split(",").map { it.trim() }.filter { it.isNotEmpty() },
        rating = p.getOrNull(13)?.toIntOrNull() ?: 0
    )
}

private fun dnsName(mode: String): String = when (mode) {
    "doh" -> "DoH"
    "dot" -> "DoT"
    else -> "plain"
}

private fun clock(sec: Int): String = "%d:%02d".format(sec / 60, sec % 60)

private fun resultMeta(row: BcRow): String =
    "${row.score}/${row.max} checks · Domains ${row.domOk}/${row.domTotal} · DNS ${dnsName(row.dns)}" +
        if (row.avgMs > 0) " · ${row.avgMs} ms" else ""

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BlockcheckScreen() {
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val snap = LocalSnapshot.current?.value

    var presetAll by remember { mutableStateOf(true) }
    var picked by remember { mutableStateOf<Set<String>>(emptySet()) }
    var serviceList by remember { mutableStateOf<List<ZapretCtl.BcService>>(emptyList()) }
    var services by remember { mutableStateOf<Set<String>>(emptySet()) }
    var presetNames by remember { mutableStateOf<List<String>>(emptyList()) }
    var engine by remember { mutableStateOf("config") }
    var resolvedEngine by remember { mutableStateOf("nfqws2") }
    var dns by remember { mutableStateOf("auto") }
    var perList by remember { mutableStateOf(5) }
    var report by remember { mutableStateOf<List<String>>(emptyList()) }
    var showReport by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }

    LaunchedEffect(engine) {
        val e = if (engine == "config") ZapretCtl.dump()["ENGINE"].orEmpty().ifBlank { "nfqws2" } else engine
        resolvedEngine = e
        presetNames = ZapretCtl.strategies(e)
        picked = emptySet()
        serviceList = ZapretCtl.bcServices()
    }

    val bc = snap?.blockcheck.orEmpty()
    val state = bc["bc_state"] ?: "idle"
    val running = state == "running"
    val done = bc["bc_done"]?.toIntOrNull() ?: 0
    val total = bc["bc_total"]?.toIntOrNull() ?: 0
    val elapsed = bc["bc_elapsed"]?.toIntOrNull() ?: 0
    val runEngine = bc["bc_engine"].orEmpty()
    val message = bc["bc_message"].orEmpty()
    val rows = remember(snap) { snap?.results.orEmpty().mapNotNull { parseBcRow(it) } }
    val best = remember(rows) { rows.firstOrNull { it.usable } }
    val fraction = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
    val chosenCount = if (presetAll) presetNames.size else picked.size
    val canStart = !running && presetNames.isNotEmpty() && (presetAll || picked.isNotEmpty())

    val stateLabel = when (state) {
        "running" -> "Running"
        "done" -> "Finished"
        "error" -> "Error"
        "cancelled" -> "Cancelled"
        else -> "Idle"
    }
    val stateColor = when (state) {
        "running" -> MaterialTheme.colorScheme.primary
        "done" -> MaterialTheme.colorScheme.tertiary
        "error" -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Page {
        SectionCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Blockcheck", style = MaterialTheme.typography.titleMedium)
                    Text(
                        when {
                            running && message.isNotBlank() -> message
                            running -> "Testing presets"
                            message.isNotBlank() -> message
                            else -> "Finds the preset that gets your blocked domains through."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                StatusPill(stateLabel, stateColor)
            }

            if (running) {
                if (total > 0) {
                    LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                Text(
                    "Your engine stays stopped until the run ends.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile("Progress", if (total > 0) "$done / $total" else "-", Modifier.weight(1f))
                StatTile("Elapsed", clock(elapsed), Modifier.weight(1f))
                StatTile("Engine", runEngine.ifBlank { "-" }, Modifier.weight(1f))
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    enabled = canStart,
                    onClick = {
                        scope.launch {
                            val spec = if (presetAll) "all" else picked.joinToString(",")
                            val svc = if (resolvedEngine == "nfqws1") services.sorted().joinToString(",") else ""
                            val r = ZapretCtl.bcStart(spec, dns, if (engine == "config") "" else engine, svc, perList)
                            msg = if (r.ok) "Blockcheck started" else r.text.ifBlank { "Blockcheck could not start" }
                        }
                    }
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Start")
                }
                OutlinedButton(
                    enabled = running,
                    onClick = { scope.launch { msg = ZapretCtl.bcCancel().text } }
                ) {
                    Icon(Icons.Filled.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Cancel")
                }
            }
            Msg(msg)
        }

        SectionCard("Settings") {
            FieldLabel("Presets to test")
            Segmented(
                options = listOf("All", "Choose"),
                selected = if (presetAll) 0 else 1,
                enabled = !running
            ) { presetAll = it == 0 }
            if (!presetAll) {
                PresetPicker(presetNames, picked, enabled = !running) { picked = it }
            }

            FieldLabel("Engine")
            Segmented(
                options = ENGINE_LABELS,
                selected = ENGINE_KEYS.indexOf(engine),
                enabled = !running
            ) { engine = ENGINE_KEYS[it] }

            FieldLabel("DNS during the run")
            Segmented(
                options = DNS_LABELS,
                selected = DNS_KEYS.indexOf(dns),
                enabled = !running
            ) { dns = DNS_KEYS[it] }

            if (resolvedEngine == "nfqws2") {
                FieldLabel("Domains per hostlist (zapret 2)")
                Segmented(
                    options = PER_LIST_VALUES.map { it.toString() },
                    selected = PER_LIST_VALUES.indexOf(perList),
                    enabled = !running
                ) { perList = PER_LIST_VALUES[it] }
            }

            if (resolvedEngine == "nfqws1" && serviceList.isNotEmpty()) {
                FieldLabel("Services to test (zapret 1)")
                ServicePicker(serviceList, services, enabled = !running) { services = it }
            }

            val summary = when {
                presetNames.isEmpty() -> "No presets are installed. Update components first."
                !presetAll && picked.isEmpty() -> "Pick at least one preset to start."
                resolvedEngine == "nfqws1" && services.isNotEmpty() ->
                    "Tests ${services.size} selected " + (if (services.size == 1) "service" else "services") +
                        " against $chosenCount " + (if (chosenCount == 1) "strategy" else "strategies") +
                        " and adds their domains to the zapret 1 user list."
                resolvedEngine == "nfqws1" ->
                    "Tests the default domains against $chosenCount " +
                        (if (chosenCount == 1) "strategy" else "strategies") + " on zapret 1. Select services to test them instead."
                else -> "Tests each preset with $perList random " + (if (perList == 1) "domain" else "domains") +
                    " from each of its hostlists, $chosenCount " + (if (chosenCount == 1) "preset" else "presets") +
                    " on zapret 2. Presets that name none use the default domains."
            }
            Text(
                summary,
                style = MaterialTheme.typography.bodySmall,
                color = if (presetNames.isEmpty()) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        ResultsSection(
            rows = rows,
            best = best,
            running = running,
            zapret2 = runEngine != "nfqws1",
            onUse = { row ->
                scope.launch {
                    val eng = runEngine.ifBlank { "nfqws2" }
                    val r = ZapretCtl.useCandidate(eng, row.name)
                    msg = if (r.ok) "Selected $eng: ${row.name}. Start the engine to use it." else r.text
                }
            },
            onCopy = {
                scope.launch {
                    val lines = ZapretCtl.bcReport()
                    if (lines.isEmpty()) {
                        msg = "No report yet"
                    } else {
                        clipboard.setText(AnnotatedString(lines.joinToString("\n")))
                        msg = "Report copied to the clipboard"
                    }
                }
            },
            showReport = showReport,
            onToggleReport = {
                if (showReport) {
                    showReport = false
                } else {
                    scope.launch {
                        report = ZapretCtl.bcReport()
                        showReport = report.isNotEmpty()
                        if (!showReport) msg = "No report yet"
                    }
                }
            }
        )

        if (showReport && report.isNotEmpty()) {
            SectionCard("Report") {
                SelectionContainer {
                    Text(
                        report.joinToString("\n"),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier
            .background(listRowColor(), RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ResultsSection(
    rows: List<BcRow>,
    best: BcRow?,
    running: Boolean,
    zapret2: Boolean,
    onUse: (BcRow) -> Unit,
    onCopy: () -> Unit,
    showReport: Boolean,
    onToggleReport: () -> Unit
) {
    val presets = rows.filter { !it.isBaseline }
    val baselines = rows.filter { it.isBaseline }
    val usableCount = presets.count { it.usable }

    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(
            "Results",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f)
        )
        if (presets.isNotEmpty()) {
            Text(
                "${presets.size} tested · $usableCount usable",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    if (presets.isNotEmpty() && zapret2) {
        Text(
            "Rating: share of tested domains that got through, adjusted for how many were tested. " +
                "More domains tested ranks higher at the same share.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    if (presets.isEmpty()) {
        AppCard(Modifier.fillMaxWidth()) {
            Text(
                if (running) "Waiting for the first result."
                else "No results yet. Start a run to see which preset works best.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    } else {
        best?.let { row ->
            var open by remember(row.name) { mutableStateOf(false) }
            AccentCard {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().tvFocus(RoundedCornerShape(16.dp)).clickable { open = !open }
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            "Best result",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            row.title,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Text(
                        "${(row.fraction * 100).roundToInt()}%",
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                LinearProgressIndicator(
                    progress = { row.fraction },
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
                )
                ProbeRow(row)
                TestedDomains(row, open) { open = !open }
                Text(
                    resultMeta(row),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                ThemedButton(
                    label = "Use this preset",
                    onClick = { onUse(row) },
                    enabled = !running,
                    primary = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        presets.forEachIndexed { index, row ->
            if (row !== best) {
                key(row.name) {
                    ResultCard(row, rank = index + 1, running = running, onUse = { onUse(row) })
                }
            }
        }
    }

    if (baselines.isNotEmpty()) {
        Text(
            "Without bypass",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        baselines.forEach { row ->
            key(row.name) {
                var open by remember { mutableStateOf(false) }
                AppCard(Modifier.fillMaxWidth()) {
                    Text(row.title, style = MaterialTheme.typography.titleSmall)
                    ProbeRow(row)
                    TestedDomains(row, open) { open = !open }
                    Text(
                        "Domains ${row.domOk}/${row.domTotal}" +
                            if (row.avgMs > 0) " · ${row.avgMs} ms" else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        ThemedButton(label = "Copy report", onClick = onCopy, primary = false, icon = Icons.Filled.ContentCopy)
        ThemedButton(
            label = if (showReport) "Hide report" else "Show report",
            onClick = onToggleReport,
            primary = false
        )
    }
}

@Composable
private fun ResultCard(row: BcRow, rank: Int, running: Boolean, onUse: () -> Unit) {
    val accent = if (row.usable) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    var open by remember { mutableStateOf(false) }
    AppCard(Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth().tvFocus(RoundedCornerShape(16.dp)).clickable { open = !open }
        ) {
            Text(
                "#$rank",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                row.title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text(
                "${(row.fraction * 100).roundToInt()}%",
                style = MaterialTheme.typography.titleSmall,
                color = accent
            )
        }
        LinearProgressIndicator(
            progress = { row.fraction },
            modifier = Modifier.fillMaxWidth(),
            color = accent,
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
        )
        ProbeRow(row)
        TestedDomains(row, open) { open = !open }
        Text(
            resultMeta(row),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (row.note.isNotBlank()) {
            Text(row.note, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
        }
        if (row.usable) {
            ThemedButton(
                label = "Use this preset",
                onClick = onUse,
                enabled = !running,
                primary = false,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun ProbeRow(row: BcRow) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        ProbeCell("HTTP", row.http, Modifier.weight(1f))
        ProbeCell("TLS 1.2", row.tls12, Modifier.weight(1f))
        ProbeCell("TLS 1.3", row.tls13, Modifier.weight(1f))
        ProbeCell("QUIC", row.quic, Modifier.weight(1f))
    }
}

@Composable
private fun TestedDomains(row: BcRow, open: Boolean, onToggle: () -> Unit) {
    if (row.domains.isEmpty()) return
    val count = row.domains.size
    Column(
        Modifier
            .fillMaxWidth()
            .background(listRowColor(), RoundedCornerShape(12.dp))
            .tvFocus(RoundedCornerShape(12.dp))
            .clickable(onClick = onToggle)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Tested on $count " + (if (count == 1) "domain" else "domains"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            Text(
                if (open) "Hide" else "Show",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
        if (open) {
            row.domains.forEach { domain ->
                Text(
                    domain,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

@Composable
private fun ProbeCell(label: String, value: String, modifier: Modifier = Modifier) {
    val na = value == "n/a" || value.isBlank()
    Column(
        modifier
            .background(listRowColor(), RoundedCornerShape(12.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        Text(
            if (na) "—" else value,
            style = MaterialTheme.typography.titleSmall,
            color = if (na) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            maxLines = 1
        )
    }
}

@Composable
private fun ServicePicker(
    services: List<ZapretCtl.BcService>,
    picked: Set<String>,
    enabled: Boolean,
    onChange: (Set<String>) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "${picked.size} of ${services.size} selected",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        TextButton(enabled = enabled, onClick = { onChange(emptySet()) }) { Text("None") }
    }
    services.forEach { svc ->
        val on = svc.key in picked
        val shape = RoundedCornerShape(14.dp)
        Row(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(listRowColor(on), shape)
                .tvFocus(shape)
                .clickable(enabled = enabled) { onChange(if (on) picked - svc.key else picked + svc.key) }
                .padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    svc.label,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    svc.domains.take(3).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Checkbox(checked = on, onCheckedChange = null, enabled = enabled)
        }
    }
}

@Composable
private fun PresetPicker(names: List<String>, picked: Set<String>, enabled: Boolean, onChange: (Set<String>) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "${picked.size} of ${names.size} selected",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        TextButton(enabled = enabled, onClick = { onChange(names.toSet()) }) { Text("All") }
        TextButton(enabled = enabled, onClick = { onChange(emptySet()) }) { Text("None") }
    }
    names.forEach { name ->
        val on = name in picked
        val shape = RoundedCornerShape(14.dp)
        Row(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(listRowColor(on), shape)
                .tvFocus(shape)
                .clickable(enabled = enabled) { onChange(if (on) picked - name else picked + name) }
                .padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Checkbox(checked = on, onCheckedChange = null, enabled = enabled)
        }
    }
}
