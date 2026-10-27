package com.so.zapretksu.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.window.DialogProperties
import com.so.zapretksu.Gate

@Composable
fun GateDialog(gate: Gate?, checking: Boolean, onRetry: () -> Unit) {
    val title = if (gate == Gate.NoModule) "zapret module not installed" else "Root access required"
    val body = if (gate == Gate.NoModule) {
        "zapret-ksu needs its KernelSU module to control zapret.\n\n" +
            "Flash zapret-ksu-module.zip in KernelSU (or KernelSU-Next), reboot the device, then tap Retry."
    } else {
        "zapret-ksu needs root to control the zapret module.\n\n" +
            "Open KernelSU (or KernelSU-Next), allow zapret-ksu under Superuser, then tap Retry.\n\n" +
            "The app stays locked until root is granted."
    }
    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = {
            Button(onClick = onRetry, enabled = !checking) {
                Text(if (checking) "Checking…" else "Retry")
            }
        }
    )
}
