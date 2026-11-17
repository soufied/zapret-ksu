package com.so.zapretksu.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.so.zapretksu.ZapretCtl
import kotlinx.coroutines.launch

private val TETHER_KEYS = listOf(
    "TETHER_ENABLED",
    "TETHER_MODE",
    "TETHER_IFACES",
    "TETHER_VPN_IFACE",
    "TETHER_KILLSWITCH",
    "TETHER_NOPROV",
    "TETHER_OFFLOAD_OFF",
    "TETHER_TTL",
    "TETHER_QUIC_YT"
)

private val ROUTE_OPTIONS = listOf("Direct", "Through VPN")

private fun diagTitle(feature: String): String = when (feature) {
    "tethering" -> "Tethering"
    "forwarding" -> "IP forwarding"
    "vpn-routing" -> "VPN routing"
    "vpn-nat" -> "NAT through VPN"
    "kill-switch" -> "Kill-switch"
    "vpn-interface" -> "VPN interface"
    "ttl" -> "Client TTL"
    "quic-google" -> "QUIC block (Google)"
    "noprovisioning" -> "Carrier provisioning"
    "offload" -> "Hardware offload"
    "bpf-offload" -> "BPF offload"
    "ip-rule" -> "Policy route"
    "route-check" -> "Route for clients"
    "forward-hook" -> "FORWARD order"
    "vpn-mtu" -> "VPN MTU"
    "app-quic" -> "QUIC block (apps)"
    "interfaces" -> "Tethering interfaces"
    "interfaces-absent" -> "Absent interfaces"
    "watcher" -> "Watcher"
    "rules" -> "Rules"
    else -> feature
}

@Composable
private fun DiagRow(item: ZapretCtl.DiagItem) {
    val color = when (item.status) {
        "ok" -> MaterialTheme.colorScheme.primary
        "warn" -> MaterialTheme.colorScheme.tertiary
        "fail" -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.outline
    }
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            Modifier
                .padding(top = 6.dp)
                .size(10.dp)
                .background(color, CircleShape)
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(diagTitle(item.feature), style = MaterialTheme.typography.bodyMedium)
            Text(
                item.text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun rulesText(st: Map<String, String>): String = when (st["tether_rules"]) {
    "full" -> "Clients leave through the VPN tunnel"
    "block" -> "Clients are blocked until the VPN route is up"
    "direct" -> "Clients leave directly"
    "missing" -> "The rules were removed by the system. Save to apply them again"
    else -> "Off"
}

@Composable
fun TetherScreen() {
    val scope = rememberCoroutineScope()
    var original by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var draft by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var status by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var netIfaces by remember { mutableStateOf<List<String>>(emptyList()) }
    var diag by remember { mutableStateOf<List<ZapretCtl.DiagItem>?>(null) }
    var diagBusy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }

    fun runDiagnostic() {
        scope.launch {
            diagBusy = true
            diag = ZapretCtl.tetherDiagnose()
            diagBusy = false
        }
    }

    fun reload() {
        scope.launch {
            original = ZapretCtl.dump().filterKeys { it in TETHER_KEYS }
            draft = original
            status = ZapretCtl.tetherStatus()
            netIfaces = ZapretCtl.tetherInterfaces()
        }
    }

    fun refreshStatus() {
        scope.launch { status = ZapretCtl.tetherStatus() }
    }

    LaunchedEffect(Unit) { reload() }

    fun set(key: String, value: String) {
        draft = draft + (key to value)
    }

    fun flag(key: String): Boolean = draft[key] == "1"

    val dirty = draft != original
    val vpn = draft["TETHER_MODE"] == "vpn"
    val ttl = draft["TETHER_TTL"].orEmpty()
    val ttlNum = ttl.toIntOrNull()
    val ttlBad = ttlNum == null || ttlNum > 255

    fun save() {
        scope.launch {
            if (ttlBad) {
                msg = "TTL must be a number from 0 to 255"
                return@launch
            }
            val errors = mutableListOf<String>()
            for ((key, value) in draft) {
                if (original[key] == value) continue
                val r = ZapretCtl.set(key, value)
                if (!r.ok) errors += "$key: ${r.text}"
            }
            if (errors.isNotEmpty()) {
                msg = errors.joinToString("\n")
                return@launch
            }
            val applied = ZapretCtl.tetherApply()
            msg = if (applied.ok) "Saved and applied" else applied.text
            reload()
        }
    }

    Page {
        if (dirty) {
            AppCard(Modifier.fillMaxWidth()) {
                Text(
                    "Unsaved changes",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = { draft = original }) { Text("Discard") }
                    Button(onClick = { save() }) { Text("Save and apply") }
                }
            }
        }
        Msg(msg)

        AppCard(Modifier.fillMaxWidth()) {
            Text("Status", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            Text(rulesText(status), style = MaterialTheme.typography.bodyMedium)
            val hasRoute = status["tether_vpn_table"].orEmpty().let { it.isNotEmpty() && it != "none" }
            Text(
                "VPN route: " + if (hasRoute) "found" else "not found",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "IP forwarding: " + if (status["tether_ip_forward"] == "1") "on" else "off",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = { refreshStatus() }
            ) { Text("Refresh status") }
            FilledTonalButton(
                modifier = Modifier.fillMaxWidth(),
                enabled = !diagBusy,
                onClick = { runDiagnostic() }
            ) { Text(if (diagBusy) "Checking…" else "Diagnostic") }
        }

        diag?.let { items ->
            val broken = items.filter { it.status == "fail" || it.status == "warn" }
            AppCard(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Diagnostic", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    Text(
                        "${items.count { it.status == "ok" }} ok · ${items.count { it.status == "warn" }} warn · ${items.count { it.status == "fail" }} fail",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (broken.isEmpty()) {
                    Text(
                        "Everything checked works on this device.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                } else {
                    Text("Not working or limited", style = MaterialTheme.typography.labelLarge)
                    broken.forEach { DiagRow(it) }
                }
                HorizontalDivider()
                Text("All checks", style = MaterialTheme.typography.labelLarge)
                items.forEach { DiagRow(it) }
            }
        }

        AppCard(Modifier.fillMaxWidth()) {
            Text("Tethering", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            ToggleRow(
                title = "VPN Tethering",
                subtitle = "Wi-Fi hotspot and USB tethering clients follow the rules below",
                checked = flag("TETHER_ENABLED"),
                enabled = true
            ) { set("TETHER_ENABLED", if (it) "1" else "0") }

            HorizontalDivider()

            Text("Route clients", style = MaterialTheme.typography.bodyMedium)
            Segmented(
                options = ROUTE_OPTIONS,
                selected = if (vpn) 1 else 0
            ) { set("TETHER_MODE", if (it == 1) "vpn" else "direct") }
            Text(
                if (vpn) {
                    "Client traffic leaves through the VPN tunnel (NAT into the tunnel interface)."
                } else {
                    "Client traffic leaves directly through the upstream network."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (vpn) {
                ToggleRow(
                    title = "Kill-switch",
                    subtitle = "If the tunnel or the rules are missing, client traffic is dropped instead of leaving with the real IP",
                    checked = flag("TETHER_KILLSWITCH"),
                    enabled = true
                ) { set("TETHER_KILLSWITCH", if (it) "1" else "0") }

                Text("VPN interface", style = MaterialTheme.typography.bodyMedium)
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val vpnChoices = (listOf("tun+") + netIfaces).distinct()
                    vpnChoices.forEach { o ->
                        FilterChip(
                            selected = draft["TETHER_VPN_IFACE"] == o,
                            onClick = { set("TETHER_VPN_IFACE", o) },
                            label = { Text(o) }
                        )
                    }
                }
                OutlinedTextField(
                    value = draft["TETHER_VPN_IFACE"].orEmpty(),
                    onValueChange = { set("TETHER_VPN_IFACE", it) },
                    label = { Text("Custom name") },
                    supportingText = { Text("tun+ matches any tun interface (default)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            OutlinedTextField(
                value = draft["TETHER_IFACES"].orEmpty(),
                onValueChange = { set("TETHER_IFACES", it) },
                label = { Text("Tethering interfaces") },
                supportingText = { Text("Comma-separated. Default: ap0,swlan0,wlan1,rndis0,usb0,ncm0") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }

        AppCard(Modifier.fillMaxWidth()) {
            Text("Hiding", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            ToggleRow(
                title = "Skip carrier provisioning",
                subtitle = "Sets net.tethering.noprovisioning. Restart the Wi-Fi hotspot or USB tethering after saving: the setting is read when tethering starts.",
                checked = flag("TETHER_NOPROV"),
                enabled = true
            ) { set("TETHER_NOPROV", if (it) "1" else "0") }

            HorizontalDivider()

            ToggleRow(
                title = "Disable hardware offload",
                subtitle = "Sends tethered traffic through the kernel firewall so the kill-switch and TTL apply. Restart the hotspot after saving. Can lower speed.",
                checked = flag("TETHER_OFFLOAD_OFF"),
                enabled = true
            ) { set("TETHER_OFFLOAD_OFF", if (it) "1" else "0") }

            HorizontalDivider()

            OutlinedTextField(
                value = ttl,
                onValueChange = { set("TETHER_TTL", it.filter(Char::isDigit).take(3)) },
                label = { Text("Client TTL") },
                supportingText = {
                    Text(if (ttlBad) "Enter a number from 0 to 255" else "64 is the default. 0 turns it off.")
                },
                isError = ttlBad,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )

            HorizontalDivider()

            ToggleRow(
                title = "Block QUIC to Google for clients",
                subtitle = "Tethered clients' UDP 443 to Google's IPv4 ranges is refused, so YouTube falls back to TCP",
                checked = flag("TETHER_QUIC_YT"),
                enabled = true
            ) { set("TETHER_QUIC_YT", if (it) "1" else "0") }
            Text(
                "Google ranges loaded: " + (status["tether_yt_ranges"] ?: "0"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    scope.launch {
                        val r = ZapretCtl.tetherYoutubeRefresh()
                        msg = if (r.ok) "Google ranges updated" else r.text
                        refreshStatus()
                    }
                }
            ) { Text("Refresh Google ranges") }
        }
    }
}
