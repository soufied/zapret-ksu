package com.so.zapretksu.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import com.so.zapretksu.ZapretCtl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.io.IOException

data class Release(
    val tag: String,
    val version: String,
    val prerelease: Boolean,
    val apkName: String,
    val apkUrl: String,
    val apkSize: Long
)

sealed interface UpdatePhase {
    data object Idle : UpdatePhase
    data object Checking : UpdatePhase
    data class UpToDate(val version: String) : UpdatePhase
    data class CheckFailed(val message: String) : UpdatePhase
    data class Available(val release: Release, val installed: String) : UpdatePhase
    data class Downloading(val release: Release, val progress: Float) : UpdatePhase
    data class ReadyToInstall(val release: Release, val file: File) : UpdatePhase
    data class DownloadFailed(val release: Release, val message: String) : UpdatePhase
}

object AppUpdatePrefs {
    private const val FILE = "app_update"
    private const val KEY_CHECK_ON_LAUNCH = "check_on_launch"

    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun checkOnLaunch(context: Context): Boolean = prefs(context).getBoolean(KEY_CHECK_ON_LAUNCH, true)

    fun setCheckOnLaunch(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_CHECK_ON_LAUNCH, on).apply()
    }
}

object VersionOrder {
    fun isNewer(remote: String, local: String): Boolean {
        val (remoteNums, remotePre) = parse(remote)
        val (localNums, localPre) = parse(local)
        val size = maxOf(remoteNums.size, localNums.size)
        for (i in 0 until size) {
            val a = remoteNums.getOrElse(i) { 0 }
            val b = localNums.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return when {
            remotePre.isEmpty() -> localPre.isNotEmpty()
            localPre.isEmpty() -> false
            else -> remotePre > localPre
        }
    }

    private fun parse(version: String): Pair<List<Int>, String> {
        val clean = version.trim().trimStart('v', 'V')
        val core = clean.substringBefore('+').substringBefore('-')
        val pre = clean.substringBefore('+').substringAfter('-', "")
        val nums = core.split('.').map { part -> part.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }
        return nums to pre
    }
}

object AppUpdater {
    private val VERSION_RE = Regex("\\d+(\\.\\d+)+(-[0-9A-Za-z.]+)?")

    suspend fun latest(): Release? = withContext(Dispatchers.IO) {
        parseReleases(ZapretCtl.appReleases())
    }

    private fun parseReleases(json: String): Release? {
        val list = JSONArray(json)
        for (i in 0 until list.length()) {
            val item = list.getJSONObject(i)
            if (item.optBoolean("draft")) continue
            val tag = item.optString("tag_name")
            val assets = item.optJSONArray("assets") ?: continue
            for (j in 0 until assets.length()) {
                val asset = assets.getJSONObject(j)
                val name = asset.optString("name")
                if (name.endsWith(".apk", ignoreCase = true)) {
                    return Release(
                        tag = tag,
                        version = VERSION_RE.find(tag)?.value ?: tag.trimStart('v', 'V'),
                        prerelease = item.optBoolean("prerelease"),
                        apkName = name,
                        apkUrl = asset.optString("browser_download_url"),
                        apkSize = asset.optLong("size")
                    )
                }
            }
        }
        return null
    }

    suspend fun download(context: Context, release: Release, onProgress: (Float) -> Unit): File =
        withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val target = File(dir, release.apkName)
            val start = ZapretCtl.appFetchStart(
                release.apkUrl,
                target.absolutePath,
                context.applicationInfo.uid,
                release.apkSize
            )
            if (!start.ok) throw IOException(start.text.ifBlank { "could not start the download in the module" })
            while (true) {
                delay(500)
                val status = ZapretCtl.appFetchStatus()
                val bytes = status["app_bytes"]?.toLongOrNull() ?: 0L
                val total = status["app_total"]?.toLongOrNull()?.takeIf { it > 0L } ?: release.apkSize
                if (total > 0L) onProgress((bytes.toFloat() / total).coerceIn(0f, 1f))
                when (status["app_state"]) {
                    "done" -> break
                    "error" -> throw IOException(status["app_message"].orEmpty().ifBlank { "download failed" })
                }
            }
            if (!isOwnPackage(context, target)) {
                target.delete()
                throw IOException("file is not a zapret-ksu package")
            }
            target
        }

    private fun isOwnPackage(context: Context, file: File): Boolean {
        val info = context.packageManager.getPackageArchiveInfo(file.absolutePath, 0) ?: return false
        return info.packageName == context.packageName
    }

    fun install(context: Context, file: File) {
        if (!context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

private fun installedVersion(context: Context): String =
    runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }
        .getOrNull()
        .orEmpty()
        .ifBlank { "unknown" }

class AppUpdateState {
    val phase = MutableStateFlow<UpdatePhase>(UpdatePhase.Idle)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    suspend fun check(context: Context, manual: Boolean) {
        phase.value = UpdatePhase.Checking
        val installed = installedVersion(context)
        phase.value = try {
            val release = AppUpdater.latest()
            if (release != null && VersionOrder.isNewer(release.version, installed)) {
                UpdatePhase.Available(release, installed)
            } else {
                idleOrUpToDate(manual, installed)
            }
        } catch (e: Exception) {
            if (manual) UpdatePhase.CheckFailed(e.message ?: "unknown error") else UpdatePhase.Idle
        }
    }

    private fun idleOrUpToDate(manual: Boolean, installed: String): UpdatePhase =
        if (manual) UpdatePhase.UpToDate(installed) else UpdatePhase.Idle

    fun download(context: Context, release: Release) {
        val app = context.applicationContext
        scope.launch {
            phase.value = UpdatePhase.Downloading(release, 0f)
            phase.value = try {
                val file = AppUpdater.download(app, release) { progress ->
                    phase.value = UpdatePhase.Downloading(release, progress)
                }
                UpdatePhase.ReadyToInstall(release, file)
            } catch (e: Exception) {
                UpdatePhase.DownloadFailed(release, e.message ?: "download failed")
            }
        }
    }

    fun dismiss() {
        phase.value = UpdatePhase.Idle
    }
}
