package com.so.zapretksu.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.so.zapretksu.ZapretCtl
import java.io.File
import kotlinx.coroutines.launch

private data class EditorRoot(val key: String, val label: String, val ext: String, val hint: String)

private val EDITOR_ROOTS = listOf(
    EditorRoot("lists1", "Zapret 1", "txt", "Hosts for the zapret 1 engine"),
    EditorRoot("lists2", "Zapret 2", "txt", "Hosts for the zapret 2 engine"),
    EditorRoot("strat2", "Presets", "txt", "Zapret 2 preset files"),
    EditorRoot("strat1", "Strategies", "bat", "Zapret 1 strategy files")
)

private const val ARCHIVE_SUFFIX = ".tar.gz"

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ListsScreen() {
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    var rootIndex by remember { mutableStateOf(0) }
    var files by remember { mutableStateOf<List<String>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var query by remember { mutableStateOf("") }
    var newName by remember { mutableStateOf("") }
    var openName by remember { mutableStateOf<String?>(null) }
    var content by remember { mutableStateOf("") }
    var original by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }
    var pendingExport by remember { mutableStateOf<ByteArray?>(null) }
    var services by remember { mutableStateOf<List<ZapretCtl.ListService>>(emptyList()) }
    var domainInput by remember { mutableStateOf("") }
    val root = EDITOR_ROOTS[rootIndex]

    fun reload() {
        scope.launch {
            loading = true
            files = ZapretCtl.fileList(root.key)
            services = if (root.key == "lists1") ZapretCtl.listServices() else emptyList()
            loading = false
        }
    }

    LaunchedEffect(rootIndex) {
        openName = null
        msg = ""
        reload()
    }

    fun open(name: String) {
        scope.launch {
            busy = true
            val text = ZapretCtl.fileRead(root.key, name)
            if (text != null) {
                openName = name
                content = text
                original = text
                msg = ""
            } else {
                msg = "Could not open $name"
            }
            busy = false
        }
    }

    fun save() {
        val name = openName ?: return
        scope.launch {
            busy = true
            val r = ZapretCtl.fileSave(root.key, name, content)
            if (r.ok) {
                original = content
                msg = "Saved $name"
            } else {
                msg = r.text.ifBlank { "Save failed" }
            }
            busy = false
        }
    }

    fun toggleService(service: ZapretCtl.ListService, on: Boolean) {
        scope.launch {
            busy = true
            val r = ZapretCtl.setListService(service.key, on)
            msg = if (r.ok) {
                val verb = if (on) "added to" else "removed from"
                "${service.label} $verb list-general-user.txt. Restart the engine to apply."
            } else {
                r.text.ifBlank { "Could not update ${service.label}" }
            }
            services = ZapretCtl.listServices()
            files = ZapretCtl.fileList(root.key)
            busy = false
        }
    }

    fun addDomain() {
        val domain = domainInput.trim()
        if (domain.isEmpty()) return
        scope.launch {
            busy = true
            val r = ZapretCtl.addListDomain(domain)
            if (r.ok) {
                domainInput = ""
                msg = if (r.text.contains("already")) r.text else "Added $domain. Restart the engine to apply."
            } else {
                msg = r.text.ifBlank { "Could not add $domain" }
            }
            services = ZapretCtl.listServices()
            busy = false
        }
    }

    fun createFile() {
        val file = newName.trim().let { if (it.endsWith(".${root.ext}")) it else "$it.${root.ext}" }
        scope.launch {
            val r = ZapretCtl.fileCreate(root.key, file)
            if (r.ok) {
                newName = ""
                reload()
                open(file)
            } else {
                msg = r.text
            }
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        val bytes = pendingExport
        pendingExport = null
        if (uri == null || bytes == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            msg = if (context.writeUri(uri, bytes)) "Exported" else "Export failed"
            busy = false
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            var imported = 0
            val errors = mutableListOf<String>()
            uris.forEachIndexed { i, uri ->
                val label = context.displayName(uri)
                val staged = context.stageUri(uri, "import", "in-$i")
                if (staged == null) {
                    errors += "$label: could not read the file"
                } else {
                    val r = if (label.endsWith(ARCHIVE_SUFFIX)) {
                        ZapretCtl.fileImportArchive(root.key, staged.absolutePath)
                    } else {
                        ZapretCtl.fileImport(root.key, staged.absolutePath, label)
                    }
                    if (r.ok) {
                        imported++
                    } else {
                        errors += r.text.ifBlank { "$label: import failed" }
                    }
                    staged.delete()
                }
            }
            reload()
            msg = if (errors.isEmpty()) "Imported $imported file(s)" else errors.joinToString("\n")
            busy = false
        }
    }

    fun exportOpen() {
        val name = openName ?: return
        pendingExport = original.toByteArray(Charsets.UTF_8)
        exportLauncher.launch(name)
    }

    fun exportAll() {
        scope.launch {
            busy = true
            val name = "${root.key}-${stamp()}$ARCHIVE_SUFFIX"
            val dest = File(context.stagingDir("export"), name)
            val r = ZapretCtl.fileExportArchive(root.key, dest.absolutePath)
            if (r.ok) {
                val bytes = readStaged(dest)
                if (bytes != null) {
                    pendingExport = bytes
                    exportLauncher.launch(name)
                } else {
                    msg = "Could not read the archive"
                }
            } else {
                msg = r.text
            }
            busy = false
        }
    }

    val dirty = openName != null && content != original
    val name = openName
    val shown = remember(files, query) { files.filter { query.isBlank() || it.contains(query, ignoreCase = true) } }

    if (name == null) {
        val onCount = services.count { it.on }

        LazyColumn(
            Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    EDITOR_ROOTS.forEachIndexed { i, r ->
                        ThemedChip(label = r.label, selected = i == rootIndex) { rootIndex = i }
                    }
                }
            }

            item {
                Text(
                    root.hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (root.key == "lists1" && services.isNotEmpty()) {
                item {
                    AppCard(Modifier.fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text("Services", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "$onCount of ${services.size} on",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Text(
                            "Tap a service to add its domains to list-general-user.txt. " +
                                "Turning it off removes those domains from that file.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        services.chunked(2).forEach { pair ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                pair.forEach { service ->
                                    ServiceTile(
                                        service = service,
                                        enabled = !busy,
                                        modifier = Modifier.weight(1f)
                                    ) { toggleService(service, !service.on) }
                                }
                                if (pair.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }

                item {
                    AppCard(Modifier.fillMaxWidth()) {
                        Text("Custom domain", style = MaterialTheme.typography.titleSmall)
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = domainInput,
                                onValueChange = { domainInput = it },
                                placeholder = { Text("example.com") },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            ThemedButton(
                                label = "Add",
                                onClick = { addDomain() },
                                enabled = domainInput.isNotBlank() && !busy,
                                primary = true,
                                icon = Icons.Filled.Add
                            )
                        }
                        Text(
                            "Remove custom domains by editing the file below.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            item {
                AppCard(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Files",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            "${files.size}",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text("Filter files") },
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = newName,
                            onValueChange = { newName = it },
                            placeholder = { Text("New file name") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        ThemedButton(
                            label = "Create",
                            onClick = { createFile() },
                            enabled = newName.isNotBlank() && !busy,
                            primary = true
                        )
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ThemedButton(
                            label = "Import",
                            onClick = { importLauncher.launch(arrayOf("*/*")) },
                            enabled = !busy,
                            primary = false,
                            modifier = Modifier.weight(1f)
                        )
                        ThemedButton(
                            label = "Export all",
                            onClick = { exportAll() },
                            enabled = !busy && files.isNotEmpty(),
                            primary = false,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            when {
                loading -> item {
                    Text(
                        "Loading…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                files.isEmpty() -> item {
                    Text(
                        "No files in this folder yet. Create one above or import a file.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                shown.isEmpty() -> item {
                    Text(
                        "No file matches \"$query\".",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            items(shown, key = { it }) { file ->
                AppCard(Modifier.fillMaxWidth(), onClick = if (busy) null else ({ open(file) })) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(Icons.Filled.Edit, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Text(
                            file,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            item { Msg(msg) }
        }
    } else {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            AppCard(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = {
                        if (dirty) {
                            msg = "Save or revert before leaving"
                        } else {
                            openName = null
                            msg = ""
                        }
                    }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            name,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            "${root.label} · ${root.hint}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (dirty) StatusPill("Unsaved", MaterialTheme.colorScheme.primary)
                }
            }

            OutlinedTextField(
                value = content,
                onValueChange = { content = it },
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.fillMaxWidth().weight(1f)
            )

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ThemedButton(label = "Save", onClick = { save() }, enabled = dirty && !busy, primary = true)
                ThemedButton(label = "Revert", onClick = { content = original }, enabled = dirty && !busy, primary = false)
                ThemedButton(label = "Export", onClick = { exportOpen() }, enabled = !dirty && !busy, primary = false)
            }
            Msg(msg)
        }
    }
}

@Composable
private fun ServiceTile(
    service: ZapretCtl.ListService,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    SelectTile(selected = service.on, onClick = onClick, modifier = modifier, enabled = enabled) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                service.label,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (service.on) {
                Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            }
        }
        Text(
            "${service.domains.size} domains",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            if (service.on) "On" else "Off",
            style = MaterialTheme.typography.labelLarge,
            color = if (service.on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
