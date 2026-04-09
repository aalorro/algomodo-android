package com.artmondo.algomodo.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.artmondo.algomodo.data.palettes.CuratedPalettes
import com.artmondo.algomodo.data.palettes.CustomPaletteHelper
import com.artmondo.algomodo.data.palettes.Palette
import com.artmondo.algomodo.ui.theme.AccentAmber

@Composable
fun PaletteSelector(
    selectedPalette: Palette,
    onSelectPalette: (Palette) -> Unit,
    modifier: Modifier = Modifier,
    isLocked: Boolean = false,
    onToggleLock: (() -> Unit)? = null,
    customPalettes: List<Palette> = emptyList(),
    onAddCustomPalette: (() -> Unit)? = null,
    onDeleteCustomPalette: ((String) -> Unit)? = null
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 16.dp, bottom = 4.dp)
        ) {
            Text(
                text = "Color Palette",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (onToggleLock != null) {
                IconButton(
                    onClick = onToggleLock,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = if (isLocked) Icons.Filled.Lock else Icons.Filled.LockOpen,
                        contentDescription = if (isLocked) "Palette locked" else "Palette unlocked",
                        tint = if (isLocked) AccentAmber else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(horizontal = 16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            items(CuratedPalettes.all) { palette ->
                PaletteChip(
                    palette = palette,
                    isSelected = palette.name == selectedPalette.name,
                    onClick = { onSelectPalette(palette) }
                )
            }
            items(customPalettes) { palette ->
                PaletteChipWithDelete(
                    palette = palette,
                    isSelected = palette.name == selectedPalette.name,
                    onClick = { onSelectPalette(palette) },
                    onDelete = if (onDeleteCustomPalette != null) {
                        { onDeleteCustomPalette(palette.name) }
                    } else null
                )
            }
            if (onAddCustomPalette != null && customPalettes.size < 5) {
                item {
                    AddPaletteChip(onClick = onAddCustomPalette)
                }
            }
            item {
                val displayPalette = if (selectedPalette.name == "Random") selectedPalette else CuratedPalettes.randomPlaceholder
                PaletteChip(
                    palette = displayPalette,
                    isSelected = selectedPalette.name == "Random",
                    onClick = { onSelectPalette(CuratedPalettes.random()) }
                )
            }
        }
    }
}

@Composable
fun HorizontalPaletteStrip(
    selectedPalette: Palette,
    onSelectPalette: (Palette) -> Unit,
    modifier: Modifier = Modifier,
    isLocked: Boolean = false,
    onToggleLock: (() -> Unit)? = null,
    customPalettes: List<Palette> = emptyList(),
    onAddCustomPalette: (() -> Unit)? = null,
    onDeleteCustomPalette: ((String) -> Unit)? = null
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (onToggleLock != null) {
            IconButton(
                onClick = onToggleLock,
                modifier = Modifier.size(32.dp).padding(start = 4.dp)
            ) {
                Icon(
                    imageVector = if (isLocked) Icons.Filled.Lock else Icons.Filled.LockOpen,
                    contentDescription = if (isLocked) "Palette locked" else "Palette unlocked",
                    tint = if (isLocked) AccentAmber else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
        LazyRow(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            items(CuratedPalettes.all) { palette ->
                PaletteChip(
                    palette = palette,
                    isSelected = palette.name == selectedPalette.name,
                    onClick = { onSelectPalette(palette) }
                )
            }
            items(customPalettes) { palette ->
                PaletteChipWithDelete(
                    palette = palette,
                    isSelected = palette.name == selectedPalette.name,
                    onClick = { onSelectPalette(palette) },
                    onDelete = if (onDeleteCustomPalette != null) {
                        { onDeleteCustomPalette(palette.name) }
                    } else null
                )
            }
            if (onAddCustomPalette != null && customPalettes.size < 5) {
                item {
                    AddPaletteChip(onClick = onAddCustomPalette)
                }
            }
            item {
                val displayPalette = if (selectedPalette.name == "Random") selectedPalette else CuratedPalettes.randomPlaceholder
                PaletteChip(
                    palette = displayPalette,
                    isSelected = selectedPalette.name == "Random",
                    onClick = { onSelectPalette(CuratedPalettes.random()) }
                )
            }
        }
    }
}

@Composable
fun VerticalPaletteSelector(
    selectedPalette: Palette,
    onSelectPalette: (Palette) -> Unit,
    modifier: Modifier = Modifier,
    isLocked: Boolean = false,
    onToggleLock: (() -> Unit)? = null,
    customPalettes: List<Palette> = emptyList(),
    onAddCustomPalette: (() -> Unit)? = null,
    onDeleteCustomPalette: ((String) -> Unit)? = null
) {
    Column(
        modifier = modifier.background(MaterialTheme.colorScheme.surface.copy(alpha = 0.5f)),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (onToggleLock != null) {
            IconButton(
                onClick = onToggleLock,
                modifier = Modifier.size(28.dp).padding(top = 4.dp)
            ) {
                Icon(
                    imageVector = if (isLocked) Icons.Filled.Lock else Icons.Filled.LockOpen,
                    contentDescription = if (isLocked) "Palette locked" else "Palette unlocked",
                    tint = if (isLocked) AccentAmber else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(4.dp),
            contentPadding = PaddingValues(vertical = 4.dp, horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            items(CuratedPalettes.all) { palette ->
                VerticalPaletteChip(
                    palette = palette,
                    isSelected = palette.name == selectedPalette.name,
                    onClick = { onSelectPalette(palette) }
                )
            }
            items(customPalettes) { palette ->
                VerticalPaletteChipWithDelete(
                    palette = palette,
                    isSelected = palette.name == selectedPalette.name,
                    onClick = { onSelectPalette(palette) },
                    onDelete = if (onDeleteCustomPalette != null) {
                        { onDeleteCustomPalette(palette.name) }
                    } else null
                )
            }
            if (onAddCustomPalette != null && customPalettes.size < 5) {
                item {
                    VerticalAddPaletteChip(onClick = onAddCustomPalette)
                }
            }
            item {
                val displayPalette = if (selectedPalette.name == "Random") selectedPalette else CuratedPalettes.randomPlaceholder
                VerticalPaletteChip(
                    palette = displayPalette,
                    isSelected = selectedPalette.name == "Random",
                    onClick = { onSelectPalette(CuratedPalettes.random()) }
                )
            }
        }
    }
}

// ── Chip composables ──

@Composable
private fun VerticalPaletteChip(
    palette: Palette,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val borderModifier = if (isSelected) {
        Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(6.dp))
    } else {
        Modifier.border(1.dp, MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp))
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(borderModifier)
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        palette.colors.take(5).forEach { colorHex ->
            Box(
                modifier = Modifier
                    .weight(1f)
                    .aspectRatio(1f)
                    .clip(CircleShape)
                    .background(Color(android.graphics.Color.parseColor(colorHex)))
            )
        }
    }
}

@Composable
private fun VerticalPaletteChipWithDelete(
    palette: Palette,
    isSelected: Boolean,
    onClick: () -> Unit,
    onDelete: (() -> Unit)?
) {
    var showDelete by remember { mutableStateOf(false) }

    val borderModifier = if (isSelected) {
        Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(6.dp))
    } else {
        Modifier.border(1.dp, MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp))
    }

    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(borderModifier)
                .clip(RoundedCornerShape(6.dp))
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = { if (onDelete != null) showDelete = true }
                )
                .padding(horizontal = 6.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            palette.colors.take(5).forEach { colorHex ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .aspectRatio(1f)
                        .clip(CircleShape)
                        .background(Color(android.graphics.Color.parseColor(colorHex)))
                )
            }
        }

        if (showDelete && onDelete != null) {
            IconButton(
                onClick = {
                    showDelete = false
                    onDelete()
                },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(20.dp)
                    .background(MaterialTheme.colorScheme.error, CircleShape)
            ) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "Delete palette",
                    tint = MaterialTheme.colorScheme.onError,
                    modifier = Modifier.size(12.dp)
                )
            }
        }
    }
}

@Composable
private fun VerticalAddPaletteChip(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp))
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Filled.Add,
            contentDescription = "Add custom palette",
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(16.dp)
        )
    }
}

@Composable
private fun PaletteChip(
    palette: Palette,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val borderModifier = if (isSelected) {
        Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp))
    } else {
        Modifier.border(1.dp, MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
    }

    Column(
        modifier = Modifier
            .then(borderModifier)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            palette.colors.forEach { colorHex ->
                Box(
                    modifier = Modifier
                        .size(16.dp)
                        .clip(CircleShape)
                        .background(Color(android.graphics.Color.parseColor(colorHex)))
                )
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = palette.name,
            fontSize = 10.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun PaletteChipWithDelete(
    palette: Palette,
    isSelected: Boolean,
    onClick: () -> Unit,
    onDelete: (() -> Unit)?
) {
    var showDelete by remember { mutableStateOf(false) }

    val borderModifier = if (isSelected) {
        Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp))
    } else {
        Modifier.border(1.dp, MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
    }

    Box {
        Column(
            modifier = Modifier
                .then(borderModifier)
                .clip(RoundedCornerShape(8.dp))
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = { if (onDelete != null) showDelete = true }
                )
                .padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                palette.colors.forEach { colorHex ->
                    Box(
                        modifier = Modifier
                            .size(16.dp)
                            .clip(CircleShape)
                            .background(Color(android.graphics.Color.parseColor(colorHex)))
                    )
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = palette.name,
                fontSize = 10.sp,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        if (showDelete && onDelete != null) {
            IconButton(
                onClick = {
                    showDelete = false
                    onDelete()
                },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(20.dp)
                    .background(MaterialTheme.colorScheme.error, CircleShape)
            ) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "Delete palette",
                    tint = MaterialTheme.colorScheme.onError,
                    modifier = Modifier.size(12.dp)
                )
            }
        }
    }
}

@Composable
private fun AddPaletteChip(onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .border(1.dp, MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier.size(16.dp),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Filled.Add,
                contentDescription = "Add custom palette",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp)
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "New",
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

// ── Custom Palette Dialog ──

@Composable
fun CustomPaletteDialog(
    existingPalettes: List<Palette>,
    onDismiss: () -> Unit,
    onSave: (Palette) -> Unit
) {
    val initialColors = remember { CuratedPalettes.random().colors }
    val defaultName = remember { CustomPaletteHelper.nextDefaultName(existingPalettes) }

    var name by remember { mutableStateOf(defaultName) }
    var colors by remember { mutableStateOf(initialColors.toMutableList()) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New Custom Palette") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(20) },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                // Color preview row
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    colors.forEach { colorHex ->
                        val parsed = try {
                            Color(android.graphics.Color.parseColor(colorHex))
                        } catch (_: Exception) {
                            Color.Gray
                        }
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .aspectRatio(1f)
                                .clip(CircleShape)
                                .background(parsed)
                                .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                        )
                    }
                }

                // Color hex inputs
                colors.forEachIndexed { index, colorHex ->
                    OutlinedTextField(
                        value = colorHex,
                        onValueChange = { newVal ->
                            val filtered = if (newVal.startsWith("#")) newVal else "#$newVal"
                            if (filtered.matches(Regex("^#[0-9A-Fa-f]{0,6}$"))) {
                                colors = colors.toMutableList().also { it[index] = filtered }
                            }
                        },
                        label = { Text("Color ${index + 1}") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = MaterialTheme.typography.bodySmall
                    )
                }

                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val trimmedName = name.trim()
                    if (trimmedName.isBlank()) {
                        error = "Name cannot be empty"
                        return@TextButton
                    }
                    val invalidColor = colors.any { !it.matches(Regex("^#[0-9A-Fa-f]{6}$")) }
                    if (invalidColor) {
                        error = "All colors must be valid 6-digit hex (e.g. #FF006E)"
                        return@TextButton
                    }
                    val nameConflict = existingPalettes.any { it.name == trimmedName } ||
                        CuratedPalettes.all.any { it.name == trimmedName }
                    if (nameConflict) {
                        error = "A palette with this name already exists"
                        return@TextButton
                    }
                    onSave(Palette(trimmedName, colors.toList()))
                }
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
