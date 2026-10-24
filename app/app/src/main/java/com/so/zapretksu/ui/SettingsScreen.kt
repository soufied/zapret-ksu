package com.so.zapretksu.ui

import kotlin.math.roundToInt
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.Slider
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SliderDefaults
import androidx.compose.foundation.border
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.material.icons.filled.BlurOn
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.TextButton
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import com.so.zapretksu.ZapretCtl
import java.io.File
import kotlinx.coroutines.launch

private sealed interface SField {
    val key: String
    val label: String
}

private class SBool(override val key: String, override val label: String) : SField
private class SText(override val key: String, override val label: String) : SField
private class SIface(override val key: String, override val label: String) : SField
private class SChoice(override val key: String, override val label: String, val options: List<String>) : SField
private class SStrategy(override val key: String, override val label: String, val engineKey: String) : SField
private class SSmartText(override val key: String, override val label: String, val hint: String) : SField

private class SBoolFor(
    override val key: String,
    override val label: String,
    val engineKey: String,
    val engine: String,
    val hint: String
) : SField

private fun fieldVisible(f: SField, draft: Map<String, String>): Boolean =
    when (f) {
        is SBoolFor -> (draft[f.engineKey] ?: "nfqws2") == f.engine
        is SSmartText -> (draft["ENGINE"] ?: "nfqws2") == "nfqws1" && draft["SMART_MODE"] == "1"
        else -> true
    }

private val IFACE_PRESETS = listOf("any", "wlan0", "rmnet_data+", "ccmni+")

private val GROUPS: List<Pair<String, List<SField>>> = listOf(
    "Engine" to listOf(
        SChoice("ENGINE", "Engine", listOf("nfqws2", "nfqws1")),
        SStrategy("STRATEGY", "Strategy", "ENGINE"),
        SBoolFor("SMART_MODE", "Smart Mode", "ENGINE", "nfqws1", "Failing domains are added to the smart list automatically"),
        SSmartText("SMART_FAILS", "Failures before adding", "1 to 20 failures"),
        SSmartText("SMART_WINDOW", "Failure window (seconds)", "Failures must happen within this many seconds")
    ),
    "Network" to listOf(
        SChoice("BACKEND", "Firewall backend", listOf("auto", "nft", "iptables")),
        SIface("IFACE", "Interface"),
        SText("TCP_PORTS", "TCP ports"),
        SText("UDP_PORTS", "UDP ports"),
        SText("NFQWS_UID", "NFQWS UID (user:group)")
    ),
    "Options" to listOf(
        SBool("QUIC_ENABLED", "QUIC bypass"),
        SBool("GAME_FILTER_TCP", "Game filter (TCP)"),
        SBool("GAME_FILTER_UDP", "Game filter (UDP)"),
        SBool("BYPASS_PRIVATE", "Bypass private networks"),
        SBool("BYPASS_VPN", "Bypass VPN"),
        SBool("CONNTRACK_ACCT", "Conntrack accounting"),
        SBool("BACKUP_LISTS", "Back up lists"),
        SBool("LOG_ENABLED", "Logging")
    ),
    "Startup" to listOf(
        SBool("AUTO_START", "Start engine after boot"),
        SBool("AUTO_NETWORK", "Auto network profile"),
        SBool("AUTO_NETWORK_START", "Start auto profile after boot"),
        SBool("AUTO_STOP_NONE", "Stop when no connection"),
        SBool("DOH_AUTO_START", "Start DoH after boot"),
        SBool("TG_AUTO_START", "Start TG WS Proxy after boot")
    ),
    "Network profiles" to listOf(
        SIface("WIFI_IFACE", "Wi-Fi interface"),
        SChoice("WIFI_ENGINE", "Wi-Fi engine", listOf("nfqws2", "nfqws1")),
        SStrategy("WIFI_STRATEGY", "Wi-Fi strategy", "WIFI_ENGINE"),
        SIface("MOBILE_IFACE", "Mobile interface"),
        SChoice("MOBILE_ENGINE", "Mobile engine", listOf("nfqws2", "nfqws1")),
        SStrategy("MOBILE_STRATEGY", "Mobile strategy", "MOBILE_ENGINE")
    )
)

private val GROUP_ICONS: Map<String, ImageVector> = mapOf(
    "Engine" to Icons.Filled.Bolt,
    "Network" to Icons.Filled.Wifi,
    "Options" to Icons.Filled.Tune,
    "Startup" to Icons.Filled.PowerSettingsNew,
    "Network profiles" to Icons.Filled.SwapHoriz
)

private const val ZAPRET1_TITLE = "Zapret 1 options"
private const val BACKUP_TITLE = "Backup"
private const val APPEARANCE_TITLE = "Appearance"

private val ALL_FIELDS: List<SField> = GROUPS.flatMap { it.second }

@Composable
fun SettingsScreen(onOpen: (Route) -> Unit) {
    val scope = rememberCoroutineScope()
    val appearance = LocalAppearance.current
    var original by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var draft by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var strategies by remember { mutableStateOf<Map<String, List<String>>>(emptyMap()) }
    var interfaces by remember { mutableStateOf<List<String>>(emptyList()) }
    var openPage by remember { mutableStateOf<String?>(null) }
    var msg by remember { mutableStateOf("") }

    fun reload() {
        scope.launch {
            val all = ZapretCtl.dump()
            original = all.filterKeys { k -> ALL_FIELDS.any { it.key == k } }
            draft = original
            strategies = mapOf(
                "nfqws1" to ZapretCtl.strategies("nfqws1"),
                "nfqws2" to ZapretCtl.strategies("nfqws2")
            )
            interfaces = ZapretCtl.interfaces()
        }
    }

    LaunchedEffect(Unit) { reload() }

    BackHandler(enabled = openPage != null) { openPage = null }

    fun set(key: String, value: String) {
        draft = draft + (key to value)
    }

    val ifaceOptions = remember(interfaces) { (IFACE_PRESETS + interfaces).distinct() }
    val dirty = ALL_FIELDS.any { draft[it.key] != null && draft[it.key] != original[it.key] }

    val saveAll: () -> Unit = {
        scope.launch {
            val saved = mutableMapOf<String, String>()
            val errors = mutableListOf<String>()
            for (f in ALL_FIELDS) {
                val v = draft[f.key] ?: continue
                if (v == original[f.key]) continue
                val r = ZapretCtl.set(f.key, v)
                if (r.ok) saved[f.key] = v else errors += "${f.key}: ${r.text}"
            }
            original = original + saved
            msg = if (errors.isEmpty()) "Saved ${saved.size} setting(s)" else errors.joinToString("\n")
        }
    }

    val page = openPage
    if (page == null) {
        Page {
            if (dirty) UnsavedBanner(onSave = saveAll, onDiscard = { draft = original })
            Msg(msg)

            AppCard(Modifier.fillMaxWidth()) {
                SettingsRow(
                    Icons.Filled.Palette,
                    "Appearance",
                    appearance.mode.label
                ) { openPage = APPEARANCE_TITLE }
            }

            AppCard(Modifier.fillMaxWidth()) {
                Text("Configuration", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                GROUPS.forEachIndexed { i, (title, fields) ->
                    if (i > 0) HorizontalDivider()
                    SettingsRow(GROUP_ICONS.getValue(title), title, summaryFor(fields, draft)) { openPage = title }
                }
                HorizontalDivider()
                SettingsRow(
                    Icons.Filled.Science,
                    ZAPRET1_TITLE,
                    "IPset mode, fake packets"
                ) { openPage = ZAPRET1_TITLE }
                HorizontalDivider()
                SettingsRow(
                    Icons.Filled.Backup,
                    BACKUP_TITLE,
                    "Save or restore settings, lists and presets"
                ) { openPage = BACKUP_TITLE }
            }

            AppCard(Modifier.fillMaxWidth()) {
                SettingsRow(Icons.Filled.Info, "About", "Version, credits and links") { onOpen(Route.About) }
            }
        }
    } else {
        Page {
            SubPageHeader(
                title = page,
                dirty = dirty,
                onBack = { openPage = null },
                onSave = saveAll
            )
            Msg(msg)

            when (page) {
                APPEARANCE_TITLE -> AppearanceEditor()
                ZAPRET1_TITLE -> AppCard(Modifier.fillMaxWidth()) { Zapret1Options() }
                BACKUP_TITLE -> AppCard(Modifier.fillMaxWidth()) { BackupSection(onImported = { reload() }) }
                else -> {
                    val fields = GROUPS.firstOrNull { it.first == page }?.second.orEmpty().filter { fieldVisible(it, draft) }
                    AppCard(Modifier.fillMaxWidth()) {
                        fields.forEachIndexed { i, f ->
                            if (i > 0) HorizontalDivider()
                            FieldEditor(f, draft, ifaceOptions, strategies) { key, value -> set(key, value) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun UnsavedBanner(onSave: () -> Unit, onDiscard: () -> Unit) {
    AppCard(Modifier.fillMaxWidth()) {
        Text(
            "Unsaved changes",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            "Save applies all changed settings at once.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onDiscard) { Text("Discard") }
            Button(onClick = onSave) { Text("Save") }
        }
    }
}

@Composable
private fun SubPageHeader(title: String, dirty: Boolean, onBack: () -> Unit, onSave: () -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to settings")
        }
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.weight(1f)
        )
        if (dirty) {
            Button(onClick = onSave) { Text("Save") }
        }
    }
}

@Composable
private fun SettingsRow(icon: ImageVector, title: String, summary: String, onClick: () -> Unit) {
    val supporting: (@Composable () -> Unit)? = if (summary.isNotBlank()) {
        { Text(summary, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    } else {
        null
    }
    ListItem(
        headlineContent = { Text(title, style = MaterialTheme.typography.titleMedium) },
        supportingContent = supporting,
        leadingContent = {
            Box(
                Modifier
                    .size(40.dp)
                    .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
            }
        },
        trailingContent = {
            Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
    )
}

@Composable
private fun SmartModeStatus() {
    var st by remember { mutableStateOf(emptyMap<String, String>()) }
    LaunchedEffect(Unit) { st = ZapretCtl.smartStatus() }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            "While the engine runs, domains it detects as blocked are added to list-general-user-smart.txt automatically.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (st["smart_exists"] == "1") {
            Text(
                "Entries in the smart list: " + st["smart_count"].orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun FieldEditor(
    f: SField,
    draft: Map<String, String>,
    ifaceOptions: List<String>,
    strategies: Map<String, List<String>>,
    set: (String, String) -> Unit
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (f) {
            is SBoolFor -> {
                ToggleRow(
                    title = f.label,
                    subtitle = f.hint,
                    checked = draft[f.key] == "1",
                    enabled = true
                ) { set(f.key, if (it) "1" else "0") }
                if (draft[f.key] == "1") SmartModeStatus()
            }

            is SBool -> ToggleRow(
                title = f.label,
                subtitle = "",
                checked = draft[f.key] == "1",
                enabled = true
            ) { set(f.key, if (it) "1" else "0") }

            is SSmartText -> OutlinedTextField(
                value = draft[f.key].orEmpty(),
                onValueChange = { set(f.key, it) },
                label = { Text(f.label) },
                supportingText = { Text(f.hint) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            is SText -> OutlinedTextField(
                value = draft[f.key].orEmpty(),
                onValueChange = { set(f.key, it) },
                label = { Text(f.label) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            is SIface -> {
                Text(f.label, style = MaterialTheme.typography.bodyMedium)
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ifaceOptions.forEach { o ->
                        FilterChip(
                            selected = draft[f.key] == o,
                            onClick = { set(f.key, o) },
                            label = { Text(o) }
                        )
                    }
                }
                OutlinedTextField(
                    value = draft[f.key].orEmpty(),
                    onValueChange = { set(f.key, it) },
                    label = { Text("Custom name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            is SChoice -> {
                Text(f.label, style = MaterialTheme.typography.bodyMedium)
                Segmented(
                    options = f.options,
                    selected = f.options.indexOf(draft[f.key].orEmpty())
                ) { set(f.key, f.options[it]) }
            }

            is SStrategy -> {
                val eng = draft[f.engineKey] ?: "nfqws2"
                Text(f.label, style = MaterialTheme.typography.bodyMedium)
                StrategyPicker(
                    value = draft[f.key].orEmpty(),
                    options = strategies[eng].orEmpty()
                ) { set(f.key, it) }
            }
        }
    }
}

@Composable
private fun Zapret1Options() {
    val scope = rememberCoroutineScope()
    var ipset by remember { mutableStateOf("") }
    var fakeList by remember { mutableStateOf<List<String>>(emptyList()) }
    var fakeDiscord by remember { mutableStateOf("") }
    var fakeGame by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf("") }
    val ipsetModes = listOf("none", "any", "loaded", "custom")

    LaunchedEffect(Unit) {
        ipset = ZapretCtl.ipsetGet()
        fakeList = ZapretCtl.fakesList()
        val f = ZapretCtl.fakesGet()
        fakeDiscord = f["discord"].orEmpty()
        fakeGame = f["game"].orEmpty()
    }

    Text("IPset mode (applies at once)", style = MaterialTheme.typography.bodyMedium)
    Segmented(options = ipsetModes, selected = ipsetModes.indexOf(ipset)) { i ->
        val m = ipsetModes[i]
        scope.launch {
            val r = ZapretCtl.ipsetSet(m)
            if (r.ok) {
                ipset = m
                msg = "ipset mode: $m"
            } else {
                msg = r.text
            }
        }
    }

    HorizontalDivider()

    Text("Fake packets", style = MaterialTheme.typography.bodyMedium)
    if (fakeList.isEmpty()) {
        Text("No fake packet files installed.", style = MaterialTheme.typography.bodySmall)
    } else {
        Text("Discord", style = MaterialTheme.typography.labelLarge)
        StrategyPicker(value = fakeDiscord, options = fakeList) { fakeDiscord = it }
        Text("Game", style = MaterialTheme.typography.labelLarge)
        StrategyPicker(value = fakeGame, options = fakeList) { fakeGame = it }
        FilledTonalButton(onClick = {
            scope.launch {
                val r = ZapretCtl.fakesSet(fakeDiscord, fakeGame)
                msg = if (r.ok) "Fake packets replaced. Restart the firewall to use them." else r.text
            }
        }) { Text("Apply fake packets") }
    }

    Msg(msg)
}

@Composable
private fun BackupSection(onImported: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var busy by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<ByteArray?>(null) }
    var msg by remember { mutableStateOf("") }

    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/gzip")
    ) { uri ->
        val bytes = pending
        pending = null
        if (uri == null || bytes == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            msg = if (context.writeUri(uri, bytes)) "Settings saved to the chosen location" else "Could not write the backup"
            busy = false
        }
    }

    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            val staged = context.stageUri(uri, "import", "backup.tar.gz")
            if (staged == null) {
                msg = "Could not read the selected file"
            } else {
                val r = ZapretCtl.backupImport(staged.absolutePath)
                staged.delete()
                msg = if (r.ok) (r.lines.firstOrNull() ?: "Settings restored") else r.text
                if (r.ok) onImported()
            }
            busy = false
        }
    }

    Text(
        "Backups include settings, lists and presets.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    FilledTonalButton(
        enabled = !busy,
        modifier = Modifier.fillMaxWidth(),
        onClick = {
            scope.launch {
                busy = true
                val name = "zapret-ksu-backup-${stamp()}.tar.gz"
                val file = File(context.stagingDir("export"), name)
                val r = ZapretCtl.backupExport(file.absolutePath)
                if (r.ok) {
                    val bytes = readStaged(file)
                    if (bytes != null) {
                        pending = bytes
                        saveLauncher.launch(name)
                    } else {
                        msg = "Could not read the backup"
                    }
                } else {
                    msg = r.text
                }
                busy = false
            }
        }
    ) { Text("Save settings to…") }

    OutlinedButton(
        enabled = !busy,
        modifier = Modifier.fillMaxWidth(),
        onClick = { restoreLauncher.launch(arrayOf("*/*")) }
    ) {
        Text("Restore from file…")
    }

    Msg(msg)
}

private fun summaryFor(fields: List<SField>, draft: Map<String, String>): String {
    val values = fields.filter { it !is SBool }.map { draft[it.key].orEmpty().ifBlank { "-" } }
    val bools = fields.filterIsInstance<SBool>()
    val on = bools.count { draft[it.key] == "1" }
    val parts = values + (if (bools.isNotEmpty()) listOf("$on of ${bools.size} on") else emptyList())
    return parts.joinToString(" · ")
}

@Composable
private fun GlassTuneSlider(label: String, value: Float, onChange: (Float) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                "${(value * 100).roundToInt()}%",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Slider(value = value, onValueChange = onChange, valueRange = 0.5f..1.5f)
    }
}

@Composable
private fun AppearanceEditor() {
    val appearance = LocalAppearance.current

    AppCard(Modifier.fillMaxWidth()) {
        Text("Style", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        ThemeMode.values().forEach { mode ->
            ThemeOptionRow(mode, selected = appearance.mode == mode) { appearance.set(mode) }
        }
        ToggleRow(
            title = "Optimization",
            subtitle = "Turns off animations and heavy blur for slower phones",
            checked = appearance.optimized,
            enabled = true,
            onChange = { appearance.setOptimized(it) }
        )
    }

    if (appearance.mode == ThemeMode.MD3) {
        val wallpaperOn = appearance.wallpaperColors && dynamicColorSupported
        AppCard(Modifier.fillMaxWidth()) {
            Text("Colors", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            ListItem(
                headlineContent = { Text("Material You") },
                supportingContent = {
                    Text(
                        if (dynamicColorSupported) {
                            "Use colors from your wallpaper"
                        } else {
                            "Needs Android 12 or newer"
                        }
                    )
                },
                leadingContent = { Icon(Icons.Filled.Palette, contentDescription = null) },
                trailingContent = {
                    Switch(
                        checked = wallpaperOn,
                        enabled = dynamicColorSupported,
                        onCheckedChange = { appearance.setWallpaperColors(it) }
                    )
                },
                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                modifier = Modifier.fillMaxWidth()
            )
            if (!wallpaperOn) {
                SeedPicker(seed = appearance.seed, onChange = appearance.setSeed)
            }
            ColorPreview()
        }
    }

    if (appearance.mode == ThemeMode.GLASS) {
        AppCard(Modifier.fillMaxWidth()) {
            Text("Glass", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            GlassTuneSlider("Blur", appearance.tune.blur) {
                appearance.setTune(appearance.tune.copy(blur = it))
            }
            GlassTuneSlider("Tint", appearance.tune.tint) {
                appearance.setTune(appearance.tune.copy(tint = it))
            }
            GlassTuneSlider("Rim light", appearance.tune.rim) {
                appearance.setTune(appearance.tune.copy(rim = it))
            }
            OutlinedButton(
                onClick = { appearance.setTune(GlassTune()) },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Reset glass") }
        }
    }
}

@Composable
private fun ThemeOptionRow(mode: ThemeMode, selected: Boolean, onSelect: () -> Unit) {
    val (icon, description) = when (mode) {
        ThemeMode.MD3 -> Icons.Filled.Palette to "Material You surfaces and colors"
        ThemeMode.GLASS -> Icons.Filled.BlurOn to "Translucent surfaces with blur and refraction"
    }
    val shape = RoundedCornerShape(GlassTokens.CardRadius)
    ListItem(
        headlineContent = { Text(mode.label) },
        supportingContent = { Text(description) },
        leadingContent = { Icon(icon, contentDescription = null) },
        trailingContent = { RadioButton(selected = selected, onClick = null) },
        colors = ListItemDefaults.colors(
            containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
    )
}

@Composable
private fun ColorPreview() {
    val swatches = listOf(
        MaterialTheme.colorScheme.primary,
        MaterialTheme.colorScheme.secondary,
        MaterialTheme.colorScheme.tertiary,
        MaterialTheme.colorScheme.primaryContainer,
        MaterialTheme.colorScheme.surfaceContainerHigh
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        swatches.forEach { c ->
            Box(
                Modifier
                    .size(40.dp)
                    .background(c, CircleShape)
            )
        }
    }
}

private val SEED_PRESETS: List<Int> = listOf(
    0xFF0060AA.toInt(),
    0xFF006C4C.toInt(),
    0xFF6750A4.toInt(),
    0xFFB3261E.toInt(),
    0xFFA04100.toInt(),
    0xFF984061.toInt(),
    0xFF386A20.toInt(),
    0xFF545F71.toInt()
)

@Composable
private fun SeedPicker(seed: Int, onChange: (Int) -> Unit) {
    val hsv = remember(seed) { FloatArray(3).also { android.graphics.Color.colorToHSV(seed, it) } }
    fun fromHsv(h: Float, sat: Float, v: Float): Int =
        android.graphics.Color.HSVToColor(floatArrayOf(h, sat, v))

    Text("Seed color", style = MaterialTheme.typography.bodyMedium)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        SEED_PRESETS.forEach { c ->
            val selected = c == seed
            Box(
                Modifier
                    .size(34.dp)
                    .border(
                        width = if (selected) 3.dp else 0.dp,
                        color = MaterialTheme.colorScheme.onSurface,
                        shape = CircleShape
                    )
                    .padding(3.dp)
                    .background(Color(c), CircleShape)
                    .clickable { onChange(c) }
            )
        }
    }

    val swatch = Color(seed)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("Hue", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
        Text("${hsv[0].roundToInt()}°", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    }
    Slider(
        value = hsv[0],
        onValueChange = { onChange(fromHsv(it, hsv[1], hsv[2])) },
        valueRange = 0f..360f,
        colors = SliderDefaults.colors(thumbColor = swatch, activeTrackColor = swatch)
    )
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("Saturation", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
        Text("${(hsv[1] * 100).roundToInt()}%", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    }
    Slider(
        value = hsv[1],
        onValueChange = { onChange(fromHsv(hsv[0], it, hsv[2])) },
        colors = SliderDefaults.colors(thumbColor = swatch, activeTrackColor = swatch)
    )
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("Brightness", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
        Text("${(hsv[2] * 100).roundToInt()}%", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    }
    Slider(
        value = hsv[2],
        onValueChange = { onChange(fromHsv(hsv[0], hsv[1], it)) },
        colors = SliderDefaults.colors(thumbColor = swatch, activeTrackColor = swatch)
    )
    Text(
        "#%06X".format(seed and 0xFFFFFF),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}
