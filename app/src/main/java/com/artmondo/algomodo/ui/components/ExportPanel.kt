package com.artmondo.algomodo.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
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
    isAudioLoaded: Boolean = false,
    audioFileName: String? = null,
    audioDurationSec: Float = 0f,
    isAudioPlaying: Boolean = false,
    audioSliderPosition: Float = 0f,
    onLoadAudio: () -> Unit = {},
    onClearAudio: () -> Unit = {},
    onAudioPlayPause: () -> Unit = {},
    onAudioSeek: (Float) -> Unit = {},
    onAudioSeekFinished: () -> Unit = {},
    onAudioSeekStarted: () -> Unit = {},
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
    onVideoStartChange: (Int) -> Unit,
    onVideoEndChange: (Int) -> Unit,
    generatorStyleName: String = "",
    modifier: Modifier = Modifier
) {
    val focusManager = LocalFocusManager.current
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
                Text("PNG")
            }
            Button(onClick = onExportJpg, modifier = Modifier.weight(1f)) {
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
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Size:", style = MaterialTheme.typography.bodySmall)
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
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Duration:", style = MaterialTheme.typography.bodySmall)
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
            Text(
                if (isAudioLoaded) "MP4 + Audio" else "MP4",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary
            )

            var startText by remember { mutableStateOf(exportState.videoStartSec.toString()) }
            var endText by remember { mutableStateOf(exportState.videoEndSec.toString()) }

            // Compute displayed duration from the committed state
            val displayDuration = (exportState.videoEndSec - exportState.videoStartSec).coerceAtLeast(1)

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = startText,
                    onValueChange = { startText = it.filter { c -> c.isDigit() }.take(4) },
                    label = { Text("Start (s)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = {
                        val v = startText.toIntOrNull()?.coerceAtLeast(0) ?: 0
                        startText = v.toString()
                        onVideoStartChange(v)
                        focusManager.clearFocus()
                    }),
                    modifier = Modifier
                        .weight(1f)
                        .onFocusChanged { focus ->
                            if (!focus.isFocused) {
                                val v = startText.toIntOrNull()?.coerceAtLeast(0) ?: 0
                                startText = v.toString()
                                onVideoStartChange(v)
                            }
                        }
                )
                OutlinedTextField(
                    value = endText,
                    onValueChange = { endText = it.filter { c -> c.isDigit() }.take(4) },
                    label = { Text("End (s)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = {
                        val v = endText.toIntOrNull()?.coerceAtLeast(1) ?: 30
                        endText = v.toString()
                        onVideoEndChange(v)
                        focusManager.clearFocus()
                    }),
                    modifier = Modifier
                        .weight(1f)
                        .onFocusChanged { focus ->
                            if (!focus.isFocused) {
                                val v = endText.toIntOrNull()?.coerceAtLeast(1) ?: 30
                                endText = v.toString()
                                onVideoEndChange(v)
                            }
                        }
                )
            }
            Text(
                "Duration: ${displayDuration}s",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Button(onClick = onExportVideo, modifier = Modifier.fillMaxWidth()) {
                if (isAudioLoaded) {
                    Icon(Icons.Filled.MusicNote, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                }
                Text(if (isAudioLoaded) "Export MP4 + Audio" else "Export MP4")
            }
        }

        // Audio Reactivity section
        HorizontalDivider()
        Text(
            "Audio Reactivity",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary
        )

        if (!isAudioLoaded) {
            Button(onClick = onLoadAudio, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.MusicNote, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("Load Audio")
            }
            Text(
                "Load an audio file to enable audio reactivity in procedural generators.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            // Audio file name
            if (audioFileName != null) {
                Text(
                    audioFileName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
            }

            // Timer display
            val currentSec = (audioSliderPosition * audioDurationSec).toInt()
            val totalSec = audioDurationSec.toInt()
            val timeStr = "%d:%02d / %d:%02d".format(
                currentSec / 60, currentSec % 60,
                totalSec / 60, totalSec % 60
            )

            Text(timeStr, style = MaterialTheme.typography.bodyMedium)

            // Seekable slider
            Slider(
                value = audioSliderPosition,
                onValueChange = {
                    onAudioSeekStarted()
                    onAudioSeek(it)
                },
                onValueChangeFinished = {
                    onAudioSeekFinished()
                },
                modifier = Modifier.fillMaxWidth()
            )

            // Play/Pause + Clear buttons
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onAudioPlayPause,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        if (isAudioPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(if (isAudioPlaying) "Pause" else "Play")
                }
                OutlinedButton(
                    onClick = onClearAudio,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Filled.Clear, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Clear")
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
