package com.artmondo.algomodo.ui.dialogs

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@Composable
fun DonationDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var selectedAmount by remember { mutableStateOf(10) }
    var isMonthly by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Support Algomodo") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    "Algomodo is free and open source. If you enjoy it, consider supporting its development!",
                    style = MaterialTheme.typography.bodyMedium
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Type", style = MaterialTheme.typography.bodyMedium)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("One-time", style = MaterialTheme.typography.bodySmall)
                        Switch(checked = isMonthly, onCheckedChange = { isMonthly = it })
                        Text("Monthly", style = MaterialTheme.typography.bodySmall)
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(5, 10, 15, 20).forEach { amount ->
                        FilterChip(
                            selected = selectedAmount == amount,
                            onClick = { selectedAmount = amount },
                            label = { Text("$$amount") },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                val url = "https://paypal.me/artmondo/$selectedAmount"
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                context.startActivity(intent)
                onDismiss()
            }) {
                Text("Donate $$selectedAmount${if (isMonthly) "/mo" else ""}")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Maybe later") }
        }
    )
}
