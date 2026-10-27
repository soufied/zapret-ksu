package com.so.zapretksu.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.so.zapretksu.update.AppUpdatePrefs
import com.so.zapretksu.update.AppUpdateState
import com.so.zapretksu.update.AppUpdater
import com.so.zapretksu.update.Release
import com.so.zapretksu.update.UpdatePhase

val LocalAppUpdate = staticCompositionLocalOf<AppUpdateState> { error("AppUpdateState is not provided") }

@Composable
fun AppUpdateDialog(state: AppUpdateState) {
    val context = LocalContext.current
    val phase by state.phase.collectAsState()
    when (val p = phase) {
        is UpdatePhase.Available -> OfferDialog(
            release = p.release,
            installed = p.installed,
            onYes = { state.download(context, p.release) },
            onLater = { state.dismiss() },
            onNever = {
                AppUpdatePrefs.setCheckOnLaunch(context, false)
                state.dismiss()
            }
        )
        is UpdatePhase.Downloading -> ProgressDialog(release = p.release, progress = p.progress)
        is UpdatePhase.ReadyToInstall -> ReadyDialog(
            release = p.release,
            onInstall = { AppUpdater.install(context, p.file) },
            onLater = { state.dismiss() }
        )
        is UpdatePhase.DownloadFailed -> FailedDialog(
            release = p.release,
            message = p.message,
            onRetry = { state.download(context, p.release) },
            onClose = { state.dismiss() }
        )
        else -> Unit
    }
}

@Composable
private fun OfferDialog(
    release: Release,
    installed: String,
    onYes: () -> Unit,
    onLater: () -> Unit,
    onNever: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onLater,
        title = { Text("Update available") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ReleaseHeader(release)
                Text(
                    "You have $installed installed. Download and install the new version now?",
                    style = MaterialTheme.typography.bodyMedium
                )
                TextButton(onClick = onNever, modifier = Modifier.align(Alignment.Start)) {
                    Text("Don't ask anymore")
                }
            }
        },
        confirmButton = { Button(onClick = onYes) { Text("Yeah") } },
        dismissButton = { TextButton(onClick = onLater) { Text("Not yet") } }
    )
}

@Composable
private fun ProgressDialog(release: Release, progress: Float) {
    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        title = { Text("Downloading update") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ReleaseHeader(release)
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                Text("${(progress * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { Button(onClick = {}, enabled = false) { Text("Downloading…") } }
    )
}

@Composable
private fun ReadyDialog(release: Release, onInstall: () -> Unit, onLater: () -> Unit) {
    AlertDialog(
        onDismissRequest = onLater,
        title = { Text("Ready to install") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ReleaseHeader(release)
                Text(
                    "The new version is downloaded. Tap Install to open the Android installer.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        },
        confirmButton = { Button(onClick = onInstall) { Text("Install") } },
        dismissButton = { TextButton(onClick = onLater) { Text("Later") } }
    )
}

@Composable
private fun FailedDialog(release: Release, message: String, onRetry: () -> Unit, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Download failed") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ReleaseHeader(release)
                Text(message, style = MaterialTheme.typography.bodyMedium)
            }
        },
        confirmButton = { Button(onClick = onRetry) { Text("Retry") } },
        dismissButton = { TextButton(onClick = onClose) { Text("Close") } }
    )
}

@Composable
private fun ReleaseHeader(release: Release) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "zapret KSU ${release.version}",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        ReleaseBadge(release.prerelease)
    }
}

@Composable
private fun ReleaseBadge(prerelease: Boolean) {
    val color = if (prerelease) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary
    Box(
        Modifier
            .background(color.copy(alpha = 0.16f), RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 3.dp)
    ) {
        Text(
            if (prerelease) "Pre-release" else "Release",
            style = MaterialTheme.typography.labelMedium,
            color = color
        )
    }
}
