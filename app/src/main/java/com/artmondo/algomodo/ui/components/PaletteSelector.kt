package com.artmondo.algomodo.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.artmondo.algomodo.data.palettes.CuratedPalettes
import com.artmondo.algomodo.data.palettes.CustomPaletteHelper
import com.artmondo.algomodo.data.palettes.Palette
import com.artmondo.algomodo.ui.theme.AccentAmber

private val AddButtonColor = Color(0xFF39FF14)

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
            if (onAddCustomPalette != null && customPalettes.size < 5) {
                IconButton(
                    onClick = onAddCustomPalette,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        Icons.Filled.Add,
                        contentDescription = "Add custom palette",
                        tint = AddButtonColor,
                        modifier = Modifier.size(20.dp)
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
            item {
                val displayPalette = if (selectedPalette.name == "RAND 5") selectedPalette else CuratedPalettes.randomPlaceholder
                PaletteChip(
                    palette = displayPalette,
                    isSelected = selectedPalette.name == "RAND 5",
                    onClick = { onSelectPalette(CuratedPalettes.random()) }
                )
            }
            item {
                val displayPalette = if (selectedPalette.name == "RAND 8") selectedPalette else CuratedPalettes.random8Placeholder
                PaletteChip(
                    palette = displayPalette,
                    isSelected = selectedPalette.name == "RAND 8",
                    onClick = { onSelectPalette(CuratedPalettes.random8()) }
                )
            }
            item {
                val displayPalette = if (selectedPalette.name == "RAND 10") selectedPalette else CuratedPalettes.random10Placeholder
                PaletteChip(
                    palette = displayPalette,
                    isSelected = selectedPalette.name == "RAND 10",
                    onClick = { onSelectPalette(CuratedPalettes.random10()) }
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
        if (onAddCustomPalette != null && customPalettes.size < 5) {
            IconButton(
                onClick = onAddCustomPalette,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    Icons.Filled.Add,
                    contentDescription = "Add custom palette",
                    tint = AddButtonColor,
                    modifier = Modifier.size(20.dp)
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
            item {
                val displayPalette = if (selectedPalette.name == "RAND 5") selectedPalette else CuratedPalettes.randomPlaceholder
                PaletteChip(
                    palette = displayPalette,
                    isSelected = selectedPalette.name == "RAND 5",
                    onClick = { onSelectPalette(CuratedPalettes.random()) }
                )
            }
            item {
                val displayPalette = if (selectedPalette.name == "RAND 8") selectedPalette else CuratedPalettes.random8Placeholder
                PaletteChip(
                    palette = displayPalette,
                    isSelected = selectedPalette.name == "RAND 8",
                    onClick = { onSelectPalette(CuratedPalettes.random8()) }
                )
            }
            item {
                val displayPalette = if (selectedPalette.name == "RAND 10") selectedPalette else CuratedPalettes.random10Placeholder
                PaletteChip(
                    palette = displayPalette,
                    isSelected = selectedPalette.name == "RAND 10",
                    onClick = { onSelectPalette(CuratedPalettes.random10()) }
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
        if (onAddCustomPalette != null && customPalettes.size < 5) {
            IconButton(
                onClick = onAddCustomPalette,
                modifier = Modifier.size(28.dp)
            ) {
                Icon(
                    Icons.Filled.Add,
                    contentDescription = "Add custom palette",
                    tint = AddButtonColor,
                    modifier = Modifier.size(18.dp)
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
            item {
                val displayPalette = if (selectedPalette.name == "RAND 5") selectedPalette else CuratedPalettes.randomPlaceholder
                VerticalPaletteChip(
                    palette = displayPalette,
                    isSelected = selectedPalette.name == "RAND 5",
                    onClick = { onSelectPalette(CuratedPalettes.random()) }
                )
            }
            item {
                val displayPalette = if (selectedPalette.name == "RAND 8") selectedPalette else CuratedPalettes.random8Placeholder
                VerticalPaletteChip(
                    palette = displayPalette,
                    isSelected = selectedPalette.name == "RAND 8",
                    onClick = { onSelectPalette(CuratedPalettes.random8()) }
                )
            }
            item {
                val displayPalette = if (selectedPalette.name == "RAND 10") selectedPalette else CuratedPalettes.random10Placeholder
                VerticalPaletteChip(
                    palette = displayPalette,
                    isSelected = selectedPalette.name == "RAND 10",
                    onClick = { onSelectPalette(CuratedPalettes.random10()) }
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

// ── HSV helpers ──

private fun hexToHsv(hex: String): FloatArray {
    val hsv = FloatArray(3)
    try {
        android.graphics.Color.colorToHSV(android.graphics.Color.parseColor(hex), hsv)
    } catch (_: Exception) {
        hsv[0] = 0f; hsv[1] = 0f; hsv[2] = 0.5f
    }
    return hsv
}

private fun hsvToHex(h: Float, s: Float, v: Float): String {
    val color = android.graphics.Color.HSVToColor(floatArrayOf(h, s, v))
    return "#%02X%02X%02X".format(
        android.graphics.Color.red(color),
        android.graphics.Color.green(color),
        android.graphics.Color.blue(color)
    )
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
    var selectedColorIndex by remember { mutableStateOf(0) }

    val currentHex = colors[selectedColorIndex]
    val hsv = remember(currentHex) { hexToHsv(currentHex) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New Custom Palette") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(20) },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                // Tappable color circles — tap to select which color to edit
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    colors.forEachIndexed { index, colorHex ->
                        val parsed = try {
                            Color(android.graphics.Color.parseColor(colorHex))
                        } catch (_: Exception) {
                            Color.Gray
                        }
                        val isSelected = index == selectedColorIndex
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .aspectRatio(1f)
                                .clip(CircleShape)
                                .background(parsed)
                                .border(
                                    if (isSelected) 3.dp else 1.dp,
                                    if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                    CircleShape
                                )
                                .clickable { selectedColorIndex = index },
                            contentAlignment = Alignment.Center
                        ) {
                            if (isSelected) {
                                Text(
                                    "${index + 1}",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                            }
                        }
                    }
                }

                // Hue slider with rainbow gradient
                Text(
                    "Hue",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Box(modifier = Modifier.fillMaxWidth().height(36.dp)) {
                    Canvas(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(20.dp)
                            .align(Alignment.Center)
                            .clip(RoundedCornerShape(4.dp))
                    ) {
                        drawRect(
                            brush = Brush.linearGradient(
                                listOf(
                                    Color.Red, Color.Yellow, Color.Green,
                                    Color.Cyan, Color.Blue, Color.Magenta, Color.Red
                                )
                            )
                        )
                    }
                    Slider(
                        value = hsv[0] / 360f,
                        onValueChange = { h ->
                            colors = colors.toMutableList().also {
                                it[selectedColorIndex] = hsvToHex(h * 360f, hsv[1], hsv[2])
                            }
                        },
                        colors = SliderDefaults.colors(
                            thumbColor = Color.White,
                            activeTrackColor = Color.Transparent,
                            inactiveTrackColor = Color.Transparent,
                            activeTickColor = Color.Transparent,
                            inactiveTickColor = Color.Transparent
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // Saturation slider
                Text(
                    "Saturation",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Box(modifier = Modifier.fillMaxWidth().height(36.dp)) {
                    Canvas(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(20.dp)
                            .align(Alignment.Center)
                            .clip(RoundedCornerShape(4.dp))
                    ) {
                        drawRect(
                            brush = Brush.linearGradient(
                                listOf(
                                    Color.hsv(hsv[0], 0f, hsv[2]),
                                    Color.hsv(hsv[0], 1f, hsv[2])
                                )
                            )
                        )
                    }
                    Slider(
                        value = hsv[1],
                        onValueChange = { s ->
                            colors = colors.toMutableList().also {
                                it[selectedColorIndex] = hsvToHex(hsv[0], s, hsv[2])
                            }
                        },
                        colors = SliderDefaults.colors(
                            thumbColor = Color.White,
                            activeTrackColor = Color.Transparent,
                            inactiveTrackColor = Color.Transparent,
                            activeTickColor = Color.Transparent,
                            inactiveTickColor = Color.Transparent
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // Brightness slider
                Text(
                    "Brightness",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Box(modifier = Modifier.fillMaxWidth().height(36.dp)) {
                    Canvas(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(20.dp)
                            .align(Alignment.Center)
                            .clip(RoundedCornerShape(4.dp))
                    ) {
                        drawRect(
                            brush = Brush.linearGradient(
                                listOf(
                                    Color.hsv(hsv[0], hsv[1], 0f),
                                    Color.hsv(hsv[0], hsv[1], 1f)
                                )
                            )
                        )
                    }
                    Slider(
                        value = hsv[2],
                        onValueChange = { v ->
                            colors = colors.toMutableList().also {
                                it[selectedColorIndex] = hsvToHex(hsv[0], hsv[1], v)
                            }
                        },
                        colors = SliderDefaults.colors(
                            thumbColor = Color.White,
                            activeTrackColor = Color.Transparent,
                            inactiveTrackColor = Color.Transparent,
                            activeTickColor = Color.Transparent,
                            inactiveTickColor = Color.Transparent
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // Hex input for the selected color
                OutlinedTextField(
                    value = currentHex,
                    onValueChange = { newVal ->
                        val filtered = if (newVal.startsWith("#")) newVal else "#$newVal"
                        if (filtered.matches(Regex("^#[0-9A-Fa-f]{0,6}$"))) {
                            colors = colors.toMutableList().also { it[selectedColorIndex] = filtered }
                        }
                    },
                    label = { Text("Color ${selectedColorIndex + 1} Hex") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodySmall
                )

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
