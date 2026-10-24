package com.so.zapretksu.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.so.zapretksu.ZapretCtl
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class LogLevel(val label: String) {
    ALL("All"), ERROR("Errors"), WARN("Warnings"), INFO("Info")
}

private data class LogEntry(
    val id: Int,
    val time: String,
    val tag: String,
    val level: String,
    val text: String,
    val raw: String
)

private val LOG_LINE = Regex("""^(\S+ \S+) \[(\w+)\] (\w+): (.*)$""")
private val TIME_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

private fun parseLine(id: Int, line: String): LogEntry {
    val m = LOG_LINE.find(line)
    return if (m != null) {
        LogEntry(
            id = id,
            time = m.groupValues[1].substringAfter(' '),
            tag = m.groupValues[2],
            level = m.groupValues[3].uppercase(),
            text = m.groupValues[4],
            raw = line
        )
    } else {
        LogEntry(id = id, time = "", tag = "", level = "", text = line, raw = line)
    }
}

private fun LogEntry.isIn(want: LogLevel): Boolean = when (want) {
    LogLevel.ALL -> true
    LogLevel.ERROR -> level == "ERROR"
    LogLevel.WARN -> level == "WARN"
    LogLevel.INFO -> level != "ERROR" && level != "WARN"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsScreen() {
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var entries by remember { mutableStateOf<List<LogEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var refreshedAt by remember { mutableStateOf("") }
    var level by remember { mutableStateOf(LogLevel.ALL) }
    var query by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf(setOf<Int>()) }
    var note by remember { mutableStateOf("") }

    fun reload() {
        scope.launch {
            loading = true
            val lines = ZapretCtl.log()
            entries = withContext(Dispatchers.Default) {
                lines.mapIndexed { i, l -> parseLine(i, l) }.asReversed()
            }
            expanded = emptySet()
            refreshedAt = LocalTime.now().format(TIME_FMT)
            loading = false
        }
    }

    LaunchedEffect(Unit) { reload() }

    LaunchedEffect(note) {
        if (note.isNotEmpty()) {
            delay(1800)
            note = ""
        }
    }

    val counts = remember(entries) {
        LogLevel.values().associateWith { l -> entries.count { it.isIn(l) } }
    }
    val shown = remember(entries, level, query) {
        val q = query.trim()
        entries.filter { it.isIn(level) && (q.isEmpty() || it.raw.contains(q, ignoreCase = true)) }
    }
    val cs = MaterialTheme.colorScheme

    Column(
        Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        AppCard(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "${shown.size} of ${entries.size} entries",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            if (refreshedAt.isEmpty()) "Not loaded yet" else "Refreshed $refreshedAt",
                            style = MaterialTheme.typography.labelSmall,
                            color = cs.onSurfaceVariant
                        )
                        if (loading) CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp)
                    }
                }
                ThemedButton(label = "Refresh", onClick = { reload() }, primary = false)
                Spacer(Modifier.width(6.dp))
                ThemedButton(
                    label = "Clear",
                    onClick = {
                        scope.launch {
                            ZapretCtl.clearLog()
                            reload()
                        }
                    },
                    primary = false
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                CountLabel("Errors", counts.getValue(LogLevel.ERROR), cs.error)
                CountLabel("Warnings", counts.getValue(LogLevel.WARN), cs.tertiary)
                CountLabel("Info", counts.getValue(LogLevel.INFO), cs.onSurfaceVariant)
            }
            Msg(note)
        }

        Segmented(
            options = LogLevel.values().map { it.label },
            selected = level.ordinal,
            onSelect = { level = LogLevel.values()[it] }
        )

        val clearQuery: @Composable () -> Unit = {
            IconButton(onClick = { query = "" }) {
                Icon(Icons.Filled.Close, contentDescription = "Clear filter")
            }
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Filter lines") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = if (query.isEmpty()) null else clearQuery,
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Box(Modifier.fillMaxWidth().weight(1f)) {
            AppCard(Modifier.fillMaxSize()) {
                when {
                    loading && entries.isEmpty() -> Placeholder("Loading log…", showProgress = true)
                    entries.isEmpty() -> Placeholder("No log entries yet.")
                    shown.isEmpty() -> Placeholder("No lines match this filter.")
                    else -> LazyColumn(
                        Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        items(shown) { e ->
                            LogRow(
                                e = e,
                                expanded = e.id in expanded,
                                onToggle = {
                                    expanded = if (e.id in expanded) expanded - e.id else expanded + e.id
                                },
                                onCopy = {
                                    clipboard.setText(AnnotatedString(e.raw))
                                    note = "Line copied"
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CountLabel(label: String, count: Int, color: Color) {
    Text("$label $count", style = MaterialTheme.typography.labelLarge, color = color)
}

@Composable
private fun Placeholder(text: String, showProgress: Boolean = false) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (showProgress) CircularProgressIndicator()
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LogRow(e: LogEntry, expanded: Boolean, onToggle: () -> Unit, onCopy: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val levelColor = when (e.level) {
        "ERROR" -> cs.error
        "WARN" -> cs.tertiary
        else -> cs.onSurfaceVariant
    }
    var overflow by remember(e.id) { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onToggle, onLongClick = onCopy)
            .padding(vertical = 6.dp)
    ) {
        if (e.time.isNotBlank() || e.tag.isNotBlank() || e.level.isNotBlank()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (e.time.isNotBlank()) {
                    Text(
                        e.time,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = cs.onSurfaceVariant
                    )
                }
                if (e.tag.isNotBlank()) {
                    Text(
                        e.tag,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = cs.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.weight(1f))
                if (e.level.isNotBlank()) {
                    Text(e.level, style = MaterialTheme.typography.labelSmall, color = levelColor)
                }
            }
        }
        Text(
            e.text,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = if (e.level == "ERROR" || e.level == "WARN") levelColor else cs.onSurface,
            maxLines = if (expanded) Int.MAX_VALUE else 2,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { if (!expanded) overflow = it.hasVisualOverflow }
        )
        if (overflow || expanded) {
            Text(
                if (expanded) "Show less" else "Show more",
                style = MaterialTheme.typography.labelSmall,
                color = cs.primary
            )
        }
    }
}
