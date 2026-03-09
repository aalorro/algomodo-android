package com.artmondo.algomodo.ui.dialogs

import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

@Composable
fun ReportBugDialog(onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var isSending by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val appVersion = remember {
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
        } catch (_: Exception) {
            "unknown"
        }
    }

    AlertDialog(
        onDismissRequest = { if (!isSending) onDismiss() },
        title = { Text("Report Bug") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name *") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isSending
                )
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text("Email (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isSending
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Describe the bug *") },
                    minLines = 5,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isSending
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    isSending = true
                    scope.launch {
                        val success = submitBugReport(name, email, description, appVersion)
                        if (success) {
                            Toast.makeText(context, "Bug report sent!", Toast.LENGTH_SHORT).show()
                            onDismiss()
                        } else {
                            Toast.makeText(context, "Failed to send. Please try again.", Toast.LENGTH_SHORT).show()
                            isSending = false
                        }
                    }
                },
                enabled = name.isNotBlank() && description.isNotBlank() && !isSending
            ) {
                if (isSending) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text("Submit")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isSending) {
                Text("Cancel")
            }
        }
    )
}

private suspend fun submitBugReport(
    name: String,
    email: String,
    description: String,
    appVersion: String
): Boolean = withContext(Dispatchers.IO) {
    try {
        val url = URL("https://formspree.io/f/xwvrvboe")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Accept", "application/json")
        conn.doOutput = true
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000

        val deviceInfo = "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})"
        val json = buildJsonBody(name, email, description, appVersion, deviceInfo)

        OutputStreamWriter(conn.outputStream).use { it.write(json) }

        val code = conn.responseCode
        conn.disconnect()
        code in 200..299
    } catch (_: Exception) {
        false
    }
}

private fun buildJsonBody(
    name: String,
    email: String,
    description: String,
    appVersion: String,
    deviceInfo: String
): String {
    fun escape(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")
    return buildString {
        append("{")
        append("\"name\":\"${escape(name)}\",")
        if (email.isNotBlank()) append("\"email\":\"${escape(email)}\",")
        append("\"description\":\"${escape(description)}\",")
        append("\"app_version\":\"${escape(appVersion)}\",")
        append("\"device_info\":\"${escape(deviceInfo)}\"")
        append("}")
    }
}
