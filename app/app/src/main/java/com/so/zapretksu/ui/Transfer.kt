package com.so.zapretksu.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

fun stamp(): String = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))

fun Context.stagingDir(name: String): File = File(cacheDir, name).apply { mkdirs() }

suspend fun Context.displayName(uri: Uri): String = withContext(Dispatchers.IO) {
    runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "file"
}

suspend fun Context.stageUri(uri: Uri, dir: String, name: String): File? = withContext(Dispatchers.IO) {
    runCatching {
        val input = contentResolver.openInputStream(uri) ?: return@runCatching null
        val out = File(stagingDir(dir), name)
        input.use { i -> out.outputStream().use { o -> i.copyTo(o) } }
        out
    }.getOrNull()
}

suspend fun Context.writeUri(uri: Uri, bytes: ByteArray): Boolean = withContext(Dispatchers.IO) {
    runCatching {
        contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) } != null
    }.getOrDefault(false)
}

suspend fun readStaged(file: File): ByteArray? = withContext(Dispatchers.IO) {
    try {
        file.readBytes()
    } catch (e: Exception) {
        null
    } finally {
        file.delete()
    }
}
