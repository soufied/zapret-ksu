package com.so.zapretksu.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.so.zapretksu.MirrorConfig
import com.so.zapretksu.ZapretCtl
import com.so.zapretksu.update.AppUpdatePrefs
import com.so.zapretksu.update.UpdatePhase
import kotlinx.coroutines.launch

private val GITHUB_MIRROR_RE = Regex("^https://[A-Za-z0-9.-]+(/[A-Za-z0-9._~-]+)*/$")
private val ZAPRET_MIRROR_RE = Regex("^https://[A-Za-z0-9.-]+(/[A-Za-z0-9._~-]+)*$")

private fun withScheme(raw: String): String {
    val t = raw.trim()
    return if (t.contains("://")) t else "https://$t"
}

private fun normalizeGithub(raw: String): String? {
    val t = withScheme(raw).let { if (it.endsWith("/")) it else "$it/" }
    return t.takeIf { GITHUB_MIRROR_RE.matches(it) }
}

private fun normalizeZapret(raw: String): String? {
    val t = withScheme(raw).trimEnd('/')
    return t.takeIf { ZAPRET_MIRROR_RE.matches(it) }
}

@Composable
fun DownloadScreen() {
    val scope = rememberCoroutineScope()
    var mirrors by remember { mutableStateOf<MirrorConfig?>(null) }
    var error by remember { mutableStateOf("") }

    fun commit(block: suspend () -> ZapretCtl.Result) {
        scope.launch {
            val result = block()
            error = if (result.ok) "" else result.text.ifBlank { "Could not save the change" }
            mirrors = ZapretCtl.mirrorsGet()
        }
    }

    LaunchedEffect(Unit) { mirrors = ZapretCtl.mirrorsGet() }

    val current = mirrors
    Page {
        SectionCard("Mirrors") {
            if (current == null) {
                Text("Loading…", style = MaterialTheme.typography.bodyMedium)
            } else {
                ToggleRow(
                    title = "Use mirrors",
                    subtitle = "Try mirror servers when GitHub or zapret.moe is unreachable. Dead mirrors are skipped.",
                    checked = current.enabled,
                    enabled = true,
                    onChange = { on -> commit { ZapretCtl.mirrorsEnabled(on) } }
                )
            }
        }

        if (current != null) {
            MirrorCard(
                title = "GitHub mirrors",
                hint = "Prefix mirrors such as https://gh-proxy.com/ are placed before a GitHub URL.",
                items = current.github,
                defaults = MirrorConfig.DEFAULT_GITHUB,
                github = true
            ) { list -> commit { ZapretCtl.mirrorsSave("github", list) } }

            MirrorCard(
                title = "zapret.moe mirrors",
                hint = "Hosts that replace git.zapret.moe, such as https://git2.zapret.moe.",
                items = current.zapret,
                defaults = MirrorConfig.DEFAULT_ZAPRET,
                github = false
            ) { list -> commit { ZapretCtl.mirrorsSave("zapret", list) } }
        }

        if (error.isNotBlank()) {
            Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }

        AppUpdatesCard()
    }
}

private data class MirrorDialog(val index: Int?, val value: String)

@Composable
private fun MirrorCard(
    title: String,
    hint: String,
    items: List<String>,
    defaults: List<String>,
    github: Boolean,
    onSave: (List<String>) -> Unit
) {
    var dialog by remember { mutableStateOf<MirrorDialog?>(null) }
    SectionCard(title) {
        Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (items.isEmpty()) {
            Text(
                "No mirrors. Add one below.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        items.forEachIndexed { index, url ->
            MirrorRow(url) { dialog = MirrorDialog(index, url) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { dialog = MirrorDialog(null, "") }) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Text(" Add")
            }
            TextButton(onClick = { onSave(defaults) }) { Text("Reset") }
        }
    }

    dialog?.let { d ->
        MirrorEditDialog(
            title = (if (d.index == null) "Add " else "Edit ") + title.removeSuffix("s").lowercase(),
            initial = d.value,
            github = github,
            canRemove = d.index != null,
            onDismiss = { dialog = null },
            onConfirm = { value ->
                val next = if (d.index == null) {
                    items + value
                } else {
                    items.mapIndexed { i, s -> if (i == d.index) value else s }
                }
                onSave(next.distinct())
                dialog = null
            },
            onRemove = {
                onSave(items.filterIndexed { i, _ -> i != d.index })
                dialog = null
            }
        )
    }
}

@Composable
private fun MirrorRow(url: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .tvFocus(shape)
            .background(listRowColor(), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            url,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Icon(Icons.Filled.Edit, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun MirrorEditDialog(
    title: String,
    initial: String,
    github: Boolean,
    canRemove: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    onRemove: () -> Unit
) {
    var value by remember { mutableStateOf(initial) }
    val normalized = if (github) normalizeGithub(value) else normalizeZapret(value)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                isError = value.isNotBlank() && normalized == null,
                label = { Text(if (github) "GitHub mirror URL" else "zapret.moe mirror URL") },
                supportingText = {
                    Text(if (github) "Example: https://gh-proxy.com/" else "Example: https://git2.zapret.moe")
                },
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            Button(onClick = { normalized?.let(onConfirm) }, enabled = normalized != null) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (canRemove) TextButton(onClick = onRemove) { Text("Remove") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    )
}

@Composable
private fun AppUpdatesCard() {
    val context = LocalContext.current
    val updates = LocalAppUpdate.current
    val phase by updates.phase.collectAsState()
    val scope = rememberCoroutineScope()
    var onLaunch by remember { mutableStateOf(AppUpdatePrefs.checkOnLaunch(context)) }
    val busy = phase == UpdatePhase.Checking
    SectionCard("App updates") {
        ToggleRow(
            title = "Check on launch",
            subtitle = "Ask when a newer release or pre-release is available on GitHub",
            checked = onLaunch,
            enabled = true,
            onChange = { on ->
                onLaunch = on
                AppUpdatePrefs.setCheckOnLaunch(context, on)
            }
        )
        val status = updateStatus(phase)
        if (status.isNotBlank()) {
            Text(
                status,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Button(
            onClick = { scope.launch { updates.check(context, manual = true) } },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (busy) "Checking…" else "Check for updates")
        }
    }
}

private fun updateStatus(phase: UpdatePhase): String = when (phase) {
    UpdatePhase.Checking -> "Checking GitHub…"
    is UpdatePhase.UpToDate -> "You have the latest version (${phase.version})."
    is UpdatePhase.CheckFailed -> "Could not check for updates: ${phase.message}"
    else -> ""
}
