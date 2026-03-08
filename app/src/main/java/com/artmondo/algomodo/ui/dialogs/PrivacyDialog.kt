package com.artmondo.algomodo.ui.dialogs

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun PrivacyDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Privacy Notice") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                PrivacySection("Data Privacy & Storage") {
                    "Algomodo is designed with your privacy in mind. All data processing happens entirely on your device — no information is sent to external servers."
                }
                PrivacySection("Local Storage") {
                    "Your settings, presets, and UI preferences are stored locally on your device. This data never leaves your device and is only used to enhance your user experience."
                }
                PrivacySection("Source Images") {
                    "If you upload images for processing, they are loaded into memory and processed locally. Images are not stored permanently and are discarded when you close the app."
                }
                PrivacySection("No Tracking") {
                    "We do not use analytics, cookies, or tracking scripts. Your usage patterns and artwork are completely private."
                }
                PrivacySection("Third-Party Libraries") {
                    "Algomodo uses open-source libraries such as Jetpack Compose, Hilt, and Room. These libraries are bundled within the app and do not communicate with external services."
                }
                PrivacySection("Questions?") {
                    "For more information about our privacy practices, please visit our GitHub repository: github.com/aalorro/algomodo"
                }
                Text(
                    "Last updated: March 2026",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

@Composable
private fun PrivacySection(title: String, content: () -> String) {
    Column {
        Text(title, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(2.dp))
        Text(content(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
