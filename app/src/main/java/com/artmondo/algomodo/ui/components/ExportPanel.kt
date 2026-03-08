package com.artmondo.algomodo.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.artmondo.algomodo.viewmodel.ExportUiState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ExportPanel(
    exportState: ExportUiState,
    isAnimating: Boolean,
    supportsVector: Boolean,
    onExportPng: () -> Unit,
    onExportJpg: () -> Unit,
    onExportSvg: () -> Unit,
    onExportGif: () -> Unit,
    onExportVideo: () -> Unit,
    onExportRecipe: (String) -> Unit,
    onImportRecipe: () -> Unit,
    onExportPresets: () -> Unit,
    onImportPresets: () -> Unit,
    onGifDurationChange: (Int) -> Unit,
    onGifResolutionChange: (Int) -> Unit,
    onGifBoomerangChange: (Boolean) -> Unit,
    onGifEndlessChange: (Boolean) -> Unit,
    generatorStyleName: String = "",
    modifier: Modifier = Modifier
) {
    var showRecipeDialog by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (exportState.isExporting) {
            LinearProgressIndicator(
                progress = { exportState.exportProgress },
                modifier = Modifier.fillMaxWidth()
            )
            Text("Exporting...", style = MaterialTheme.typography.bodySmall)
            return
        }

        exportState.error?.let { error ->
            Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        // Still Image section
        Text(
            "Still Image",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onExportPng, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.Image, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("PNG")
            }
            Button(onClick = onExportJpg, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.Image, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("JPG")
            }
            if (supportsVector) {
                Button(onClick = onExportSvg, modifier = Modifier.weight(1f)) {
                    Text("SVG")
                }
            }
        }

        // Animation section
        if (isAnimating) {
            HorizontalDivider()
            Text(
                "Animation",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )

            // GIF options
            Text("GIF Options", style = MaterialTheme.typography.bodySmall)

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Duration:", style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(70.dp))
                listOf(3, 5, 8).forEach { dur ->
                    FilterChip(
                        selected = exportState.gifDuration == dur,
                        onClick = { onGifDurationChange(dur) },
                        label = { Text("${dur}s") }
                    )
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Size:", style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(70.dp))
                listOf(600, 800, 1000).forEach { res ->
                    FilterChip(
                        selected = exportState.gifResolution == res,
                        onClick = { onGifResolutionChange(res) },
                        label = { Text("${res}px") }
                    )
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = exportState.gifBoomerang,
                        onCheckedChange = { onGifBoomerangChange(it) }
                    )
                    Text("Boomerang", style = MaterialTheme.typography.bodySmall)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = exportState.gifEndless,
                        onCheckedChange = { onGifEndlessChange(it) }
                    )
                    Text("Loop", style = MaterialTheme.typography.bodySmall)
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onExportGif, modifier = Modifier.weight(1f)) {
                    Text("Export GIF")
                }
                Button(onClick = onExportVideo, modifier = Modifier.weight(1f)) {
                    Text("Export MP4")
                }
            }
        }

        // Data export
        HorizontalDivider()
        Text(
            "Data",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { showRecipeDialog = true }, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.Upload, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("Export Recipe")
            }
            OutlinedButton(onClick = onImportRecipe, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("Import Recipe")
            }
        }

        // Presets export/import
        HorizontalDivider()
        Text(
            "Presets",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onExportPresets, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.Upload, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("Export Presets")
            }
            OutlinedButton(onClick = onImportPresets, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("Import Presets")
            }
        }
    }

    // Recipe filename dialog
    if (showRecipeDialog) {
        val defaultName = remember {
            val timestamp = SimpleDateFormat("HHmmss", Locale.US).format(Date())
            val stylePart = if (generatorStyleName.isNotBlank()) {
                generatorStyleName.replace(" ", "-").lowercase(Locale.US)
            } else "recipe"
            "$stylePart-json-$timestamp.json"
        }
        var fileNameField by remember {
            mutableStateOf(TextFieldValue(defaultName, selection = TextRange(0, defaultName.length - 5)))
        }
        AlertDialog(
            onDismissRequest = { showRecipeDialog = false },
            title = { Text("Export Recipe") },
            text = {
                OutlinedTextField(
                    value = fileNameField,
                    onValueChange = { fileNameField = it.copy(text = it.text.take(80)) },
                    label = { Text("File name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (fileNameField.text.isNotBlank()) {
                            val name = fileNameField.text.trim()
                            onExportRecipe(if (name.endsWith(".json")) name else "$name.json")
                            showRecipeDialog = false
                        }
                    }
                ) { Text("Export") }
            },
            dismissButton = {
                TextButton(onClick = { showRecipeDialog = false }) { Text("Cancel") }
            }
        )
    }
}
