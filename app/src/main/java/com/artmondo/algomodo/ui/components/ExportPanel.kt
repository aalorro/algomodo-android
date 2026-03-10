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
import androidx.compose.ui.graphics.Color
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
    onVideoDurationChange: (Int) -> Unit,
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
                modifier = Modifier.fillMaxWidth(),
                color = Color(0xFF39FF14)
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
        HorizontalDivider()
        if (!isAnimating) {
            Text(
                "Start animation (play button) to access GIF & MP4 export options.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        } else {
            Text(
                "Animation",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )

            // Shared resolution
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

            // GIF options
            Text("GIF", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)

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
            Text("GIF duration only (max 8s)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

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

            Button(onClick = onExportGif, modifier = Modifier.fillMaxWidth()) {
                Text("Export GIF")
            }

            HorizontalDivider()

            // MP4 options
            Text("MP4", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)

            var showCustomDuration by remember { mutableStateOf(false) }
            val isCustom = exportState.videoDuration !in listOf(5, 15, 30)

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Duration:", style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(70.dp))
                listOf(5, 15, 30).forEach { dur ->
                    FilterChip(
                        selected = exportState.videoDuration == dur && !showCustomDuration,
                        onClick = {
                            showCustomDuration = false
                            onVideoDurationChange(dur)
                        },
                        label = { Text("${dur}s") }
                    )
                }
                FilterChip(
                    selected = showCustomDuration || isCustom,
                    onClick = { showCustomDuration = true },
                    label = { Text(if (isCustom && !showCustomDuration) "${exportState.videoDuration}s" else "Custom") }
                )
            }
            Text("MP4 duration only (max 60s)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            if (showCustomDuration) {
                var customText by remember {
                    mutableStateOf(TextFieldValue(
                        if (isCustom) exportState.videoDuration.toString() else "",
                        selection = TextRange(0, if (isCustom) exportState.videoDuration.toString().length else 0)
                    ))
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Spacer(Modifier.width(70.dp))
                    OutlinedTextField(
                        value = customText,
                        onValueChange = { newVal ->
                            val filtered = newVal.copy(text = newVal.text.filter { it.isDigit() }.take(2))
                            customText = filtered
                        },
                        label = { Text("1-60s") },
                        singleLine = true,
                        modifier = Modifier.width(90.dp)
                    )
                    TextButton(onClick = {
                        val secs = customText.text.toIntOrNull()?.coerceIn(1, 60) ?: 15
                        onVideoDurationChange(secs)
                        showCustomDuration = false
                    }) { Text("Set") }
                }
            }

            Button(onClick = onExportVideo, modifier = Modifier.fillMaxWidth()) {
                Text("Export MP4")
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
