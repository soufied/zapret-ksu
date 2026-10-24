package com.so.zapretksu.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.so.zapretksu.ZapretCtl
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun DnsScreen() {
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var providers by remember { mutableStateOf<List<ZapretCtl.Provider>>(emptyList()) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var results by remember { mutableStateOf<Map<String, ZapretCtl.DnsRow>>(emptyMap()) }
    var testing by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }
    var adding by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<ZapretCtl.Provider?>(null) }

    fun reload() {
        scope.launch {
            status = ZapretCtl.dnsStatus()
            providers = ZapretCtl.providers()
            selected = ZapretCtl.dump()["DOH_PROVIDERS"].orEmpty()
                .split(",", " ").filter { it.isNotBlank() }.toSet()
        }
    }

    LaunchedEffect(Unit) { reload() }

    fun act(label: String, block: suspend () -> ZapretCtl.Result) {
        scope.launch {
            busy = true
            val r = block()
            msg = "$label: ${if (r.ok) "done" else r.text.ifBlank { "failed" }}"
            status = ZapretCtl.dnsStatus()
            providers = ZapretCtl.providers()
            busy = false
        }
    }

    fun runTest(mode: String, ids: String, label: String) {
        scope.launch {
            testing = true
            val start = ZapretCtl.dnsJobStart(mode, ids)
            if (!start.ok || !start.text.contains("started")) {
                msg = start.text.ifBlank { "The provider test could not start" }
                testing = false
                return@launch
            }
            var done = false
            for (i in 0 until 600) {
                delay(1000)
                val job = ZapretCtl.dnsJobStatus()
                if (job.state == "done") {
                    results = job.rows.associateBy { it.id }
                    msg = "$label finished"
                    done = true
                    break
                }
                if (job.state == "error") {
                    msg = job.message.ifBlank { "The provider test failed; see Logs" }
                    done = true
                    break
                }
            }
            if (!done) msg = "The provider test is still running; check back later"
            testing = false
        }
    }

    val dohOn = status["doh_running"] == "1"
    val groupNames = (listOf("Public", "Geo", "Custom") + providers.map { it.group })
        .distinct()
        .filter { g -> providers.any { it.group == g } }

    Page {
        SectionCard("DNS proxy (DoH)") {
            ToggleRow(
                title = "Proxy",
                subtitle = if (dohOn) "Running on port ${status["doh_port"] ?: "5354"}" else "Off",
                checked = dohOn,
                enabled = !busy
            ) { on ->
                act(if (on) "Start" else "Stop") { if (on) ZapretCtl.dohStart() else ZapretCtl.dohStop() }
            }
            KeyValue("Redirect", if (status["doh_redirect"] == "1") "Active" else "Off")
            KeyValue("Queries", status["doh_queries"] ?: "0")
            KeyValue("Recent errors", status["doh_errors"] ?: "0")
            KeyValue("Binary", status["doh_binary"] ?: "-")
            KeyValue("Private DNS", status["private_dns_mode"] ?: "-")
            KeyValue("Private DNS host", status["private_dns_host"]?.ifBlank { "-" } ?: "-")
        }

        SectionCard("Tests and actions") {
            Text(
                "${selected.size} DoH selected. Tap a DoH row to select it; tap a DoT row to use it as Private DNS.",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ThemedButton(
                    label = "Test selected",
                    onClick = { runTest("test", selected.joinToString(","), "Test") },
                    modifier = Modifier.weight(1f),
                    enabled = !testing && selected.isNotEmpty(),
                    primary = false
                )
                ThemedButton(
                    label = "Test all",
                    onClick = { runTest("best3", "", "Test all") },
                    modifier = Modifier.weight(1f),
                    enabled = !testing,
                    primary = false
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ThemedButton(
                    label = "Use fastest",
                    onClick = {
                        scope.launch {
                            busy = true
                            val r = ZapretCtl.dnsFastest(selected.joinToString(","))
                            msg = r.lines.lastOrNull() ?: r.text
                            reload()
                            busy = false
                        }
                    },
                    modifier = Modifier.weight(1f),
                    enabled = !busy && selected.isNotEmpty(),
                    primary = false
                )
                ThemedButton(
                    label = "Private DNS off",
                    onClick = { act("Private DNS off") { ZapretCtl.dnsOff() } },
                    modifier = Modifier.weight(1f),
                    enabled = !busy,
                    primary = false
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ThemedButton(
                    label = "Save providers",
                    onClick = {
                        scope.launch {
                            val r = ZapretCtl.set("DOH_PROVIDERS", selected.joinToString(","))
                            msg = if (r.ok) "Providers saved" else r.text
                        }
                    },
                    modifier = Modifier.weight(1f),
                    primary = true
                )
                ThemedButton(
                    label = "Add server",
                    onClick = { adding = true },
                    modifier = Modifier.weight(1f),
                    enabled = !busy,
                    primary = false,
                    icon = Icons.Filled.Add
                )
            }
            results.values.firstOrNull { it.pick }?.let { r ->
                Text(
                    "Recommended: ${r.id} · ${r.ok}/${r.total} OK · ${r.status}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Msg(msg)
        }

        groupNames.forEach { group ->
            SectionCard(group) {
                providers.filter { it.group == group }.forEach { p ->
                    ProviderRow(
                        p = p,
                        r = results[p.id],
                        checked = p.id in selected,
                        enabled = !busy,
                        onClick = {
                            if (p.doh.isNotBlank()) {
                                selected = if (p.id in selected) selected - p.id else selected + p.id
                            } else {
                                act("Private DNS") { ZapretCtl.dnsApply(p.id) }
                            }
                        },
                        canDelete = group == "Custom",
                        onDelete = { deleting = p }
                    )
                }
            }
        }
    }

    if (adding) {
        AddServerDialog(
            onDismiss = { adding = false },
            onSave = { name, isDoh, input ->
                adding = false
                act("Add server") { ZapretCtl.dnsCustomAdd(name, isDoh, input) }
            }
        )
    }

    deleting?.let { p ->
        ConfirmDialog(
            title = "Delete ${p.label}?",
            text = "The server is removed from your custom list.",
            confirmLabel = "Delete",
            onConfirm = { act("Delete") { ZapretCtl.dnsCustomDelete(p.id) } },
            onDismiss = { deleting = null }
        )
    }
}

@Composable
private fun ProviderRow(
    p: ZapretCtl.Provider,
    r: ZapretCtl.DnsRow?,
    checked: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    canDelete: Boolean,
    onDelete: () -> Unit
) {
    val isDoh = p.doh.isNotBlank()
    val badge = if (isDoh) "DoH" else "DoT"
    val host = if (isDoh) p.doh.removePrefix("https://").substringBefore("/") else p.dot
    val detail = p.label.substringAfter(" · ", "").takeIf { it.isNotBlank() && it != badge } ?: host
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(listRowColor(checked && isDoh), shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(start = 12.dp, end = if (canDelete) 0.dp else 12.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                p.label.substringBefore(" · "),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                detail,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (r != null) {
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    "${r.ms} ms",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (r.pick) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                )
                Text(
                    if (r.pick) "best" else "${r.ok}/${r.total}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        ProtoBadge(isDoh)
        if (isDoh) Checkbox(checked = checked, onCheckedChange = null)
        if (canDelete) {
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "Delete server")
            }
        }
    }
}

@Composable
private fun ProtoBadge(doh: Boolean) {
    val c = if (doh) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary
    Text(
        if (doh) "DoH" else "DoT",
        style = MaterialTheme.typography.labelSmall,
        color = c,
        modifier = Modifier
            .clip(RoundedCornerShape(percent = 50))
            .background(c.copy(alpha = 0.16f))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    )
}

@Composable
private fun AddServerDialog(onDismiss: () -> Unit, onSave: (String, Boolean, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var proto by remember { mutableStateOf(0) }
    var input by remember { mutableStateOf("") }
    val isDoh = proto == 0
    val error = if (input.isBlank()) null else ZapretCtl.customError(name, isDoh, input)
    val glass = LocalAppearance.current.mode == ThemeMode.GLASS
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = if (glass) GlassTokens.Panel else AlertDialogDefaults.containerColor,
        title = { Text("Add server") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Segmented(listOf("DoH", "DoT"), proto) { proto = it }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    label = { Text(if (isDoh) "HTTPS URL" else "Hostname") },
                    placeholder = { Text(if (isDoh) "https://dns.example.net/dns-query" else "dns.example.net") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth()
                )
                error?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = input.isNotBlank() && error == null,
                onClick = { onSave(name.trim(), isDoh, input.trim()) }
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
