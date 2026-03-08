package com.artmondo.algomodo.ui.components

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.artmondo.algomodo.data.db.PresetEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun PresetsPanel(
    presets: List<PresetEntity>,
    onSavePreset: (String) -> Unit,
    onLoadPreset: (PresetEntity) -> Unit,
    onDeletePreset: (String) -> Unit,
    generatorStyleName: String = "",
    modifier: Modifier = Modifier
) {
    var showSaveDialog by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        // Header row with label
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Presets",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }

        Spacer(Modifier.height(4.dp))

        // Horizontal scrolling row: save button + preset thumbnails
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Top
        ) {
            // Save button as first item
            item {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.width(56.dp)
                ) {
                    IconButton(
                        onClick = { showSaveDialog = true },
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .border(1.5.dp, MaterialTheme.colorScheme.primary, CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer, CircleShape)
                    ) {
                        Icon(
                            Icons.Filled.Add,
                            contentDescription = "Save preset",
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "Save",
                        fontSize = 9.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
            }

            // Preset thumbnails
            items(presets) { preset ->
                PresetThumbnail(
                    preset = preset,
                    onTap = { onLoadPreset(preset) },
                    onLongPress = { showDeleteConfirm = preset.id }
                )
            }
        }
    }

    // Save dialog
    if (showSaveDialog) {
        val defaultName = remember {
            val timestamp = SimpleDateFormat("HHmmss", Locale.US).format(Date())
            if (generatorStyleName.isNotBlank()) "$generatorStyleName-$timestamp" else timestamp
        }
        var presetNameField by remember {
            mutableStateOf(TextFieldValue(defaultName, selection = TextRange(0, defaultName.length)))
        }
        AlertDialog(
            onDismissRequest = { showSaveDialog = false },
            title = { Text("Save Preset") },
            text = {
                OutlinedTextField(
                    value = presetNameField,
                    onValueChange = { presetNameField = it.copy(text = it.text.take(50)) },
                    label = { Text("Preset name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (presetNameField.text.isNotBlank()) {
                            onSavePreset(presetNameField.text.trim())
                            showSaveDialog = false
                        }
                    }
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showSaveDialog = false }) { Text("Cancel") }
            }
        )
    }

    // Delete confirmation
    showDeleteConfirm?.let { presetId ->
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = null },
            title = { Text("Delete Preset") },
            text = { Text("Are you sure you want to delete this preset?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeletePreset(presetId)
                        showDeleteConfirm = null
                    }
                ) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = null }) { Text("Cancel") }
            }
        )
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun PresetThumbnail(
    preset: PresetEntity,
    onTap: () -> Unit,
    onLongPress: () -> Unit
) {
    val thumbnailBitmap = remember(preset.id, preset.thumbnail) {
        preset.thumbnail?.let {
            try {
                BitmapFactory.decodeByteArray(it, 0, it.size)
            } catch (e: Exception) {
                null
            }
        }
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(56.dp)
            .combinedClickable(
                onClick = onTap,
                onLongClick = onLongPress
            )
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .border(1.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f), CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            if (thumbnailBitmap != null) {
                Image(
                    bitmap = thumbnailBitmap.asImageBitmap(),
                    contentDescription = preset.name,
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(CircleShape),
                    contentScale = ContentScale.Crop
                )
            } else {
                // Fallback: show first letter
                Text(
                    text = preset.name.take(1).uppercase(),
                    fontSize = 18.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.height(2.dp))
        Text(
            text = preset.name,
            fontSize = 9.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
