package com.so.zapretksu.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.window.DialogProperties

@Composable
fun RootGate(checking: Boolean, onRetry: () -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        title = { Text("Root access required") },
        text = {
            Text(
                "zapret-ksu needs root to control the zapret module.\n\n" +
                    "Open KernelSU (or KernelSU-Next), allow zapret-ksu under Superuser, " +
                    "then tap Retry.\n\nThe app stays locked until root is granted."
            )
        },
        confirmButton = {
            Button(onClick = onRetry, enabled = !checking) {
                Text(if (checking) "Checking…" else "Retry")
            }
        }
    )
}
