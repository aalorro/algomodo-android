package com.artmondo.algomodo.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.artmondo.algomodo.data.palettes.CuratedPalettes
import com.artmondo.algomodo.data.palettes.Palette

@Composable
fun PaletteSelector(
    selectedPalette: Palette,
    onSelectPalette: (Palette) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = "Color Palette",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, bottom = 4.dp)
        )

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
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier.background(MaterialTheme.colorScheme.surface.copy(alpha = 0.5f)),
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
