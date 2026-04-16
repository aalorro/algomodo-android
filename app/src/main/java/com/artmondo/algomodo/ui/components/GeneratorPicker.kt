package com.artmondo.algomodo.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.artmondo.algomodo.core.registry.GeneratorRegistry
import com.artmondo.algomodo.generators.Generator
import com.artmondo.algomodo.viewmodel.FAVORITES_FAMILY_ID

// Accent colors for the Favorites tab — amber, matches the palette lock indicator.
private val FavoriteAccentUnselected = Color(0xFFFFF59D) // light amber
private val FavoriteAccentSelected = Color(0xFFFFC107)   // amber 500
private val FavoriteStarColor = Color(0xFFFFC107)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GeneratorPicker(
    selectedGeneratorId: String?,
    selectedFamilyId: String,
    favoriteGeneratorIds: Set<String>,
    onSelectGenerator: (Generator) -> Unit,
    onSelectFamily: (String) -> Unit,
    onToggleFavorite: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val families = remember { GeneratorRegistry.allFamilies() }
    val generators = remember(selectedFamilyId, favoriteGeneratorIds) {
        if (selectedFamilyId == FAVORITES_FAMILY_ID) {
            GeneratorRegistry.allGenerators().filter { it.id in favoriteGeneratorIds }
        } else {
            GeneratorRegistry.generatorsInFamily(selectedFamilyId)
        }
    }

    val familyListState = rememberLazyListState()
    val generatorListState = rememberLazyListState()

    // Auto-scroll family row to selected family
    LaunchedEffect(selectedFamilyId) {
        val familyIndex = if (selectedFamilyId == FAVORITES_FAMILY_ID) {
            0 // Favorites chip is always at index 0
        } else {
            // +1 offset because Favorites chip is at index 0
            families.indexOfFirst { it.id == selectedFamilyId }.let { if (it >= 0) it + 1 else -1 }
        }
        if (familyIndex >= 0) {
            familyListState.animateScrollToItem(familyIndex)
        }
    }

    // Auto-scroll generator list to selected generator
    LaunchedEffect(selectedGeneratorId, selectedFamilyId) {
        val genIndex = generators.indexOfFirst { it.id == selectedGeneratorId }
        if (genIndex >= 0) {
            generatorListState.animateScrollToItem(genIndex)
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        // Family chips
        Text(
            text = "Families",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp)
        )

        LazyRow(
            state = familyListState,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(horizontal = 16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            // Favorites chip — always first, yellow-accented
            item(key = FAVORITES_FAMILY_ID) {
                val favSelected = selectedFamilyId == FAVORITES_FAMILY_ID
                FilterChip(
                    selected = favSelected,
                    onClick = { onSelectFamily(FAVORITES_FAMILY_ID) },
                    label = {
                        Text(
                            "Favorites (${favoriteGeneratorIds.size})",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    },
                    leadingIcon = {
                        Icon(
                            Icons.Filled.Star,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = Color.Black
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        containerColor = FavoriteAccentUnselected,
                        labelColor = Color.Black,
                        iconColor = Color.Black,
                        selectedContainerColor = FavoriteAccentSelected,
                        selectedLabelColor = Color.Black,
                        selectedLeadingIconColor = Color.Black
                    ),
                    border = FilterChipDefaults.filterChipBorder(
                        enabled = true,
                        selected = favSelected,
                        borderColor = Color(0xFFFFB300),
                        selectedBorderColor = Color(0xFFF57F17),
                        borderWidth = 1.dp,
                        selectedBorderWidth = 2.dp
                    )
                )
            }

            items(families, key = { it.id }) { family ->
                FilterChip(
                    selected = family.id == selectedFamilyId,
                    onClick = { onSelectFamily(family.id) },
                    label = {
                        Text(
                            "${family.displayName} (${family.generators.size})",
                            fontSize = 12.sp
                        )
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Generator list header
        Text(
            text = if (selectedFamilyId == FAVORITES_FAMILY_ID) "Favorite Generators" else "Generators",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, bottom = 4.dp)
        )

        if (selectedFamilyId == FAVORITES_FAMILY_ID && generators.isEmpty()) {
            Text(
                text = "No favorites yet. Tap the star next to any generator to add it here.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
            )
        } else {
            LazyColumn(
                state = generatorListState,
                verticalArrangement = Arrangement.spacedBy(4.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(generators, key = { it.id }) { gen ->
                    GeneratorItem(
                        generator = gen,
                        isSelected = gen.id == selectedGeneratorId,
                        isFavorite = gen.id in favoriteGeneratorIds,
                        onClick = { onSelectGenerator(gen) },
                        onToggleFavorite = { onToggleFavorite(gen.id) }
                    )
                }
            }
        }
    }
}

@Composable
private fun GeneratorItem(
    generator: Generator,
    isSelected: Boolean,
    isFavorite: Boolean,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit
) {
    val bgColor = if (isSelected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(bgColor)
            .clickable(onClick = onClick)
            .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = generator.styleName,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = generator.definition,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }

        if (generator.supportsVector) {
            SuggestionChip(
                onClick = {},
                label = { Text("SVG", fontSize = 10.sp) },
                modifier = Modifier.height(24.dp)
            )
        }

        IconButton(
            onClick = onToggleFavorite,
            modifier = Modifier.size(36.dp)
        ) {
            Icon(
                imageVector = if (isFavorite) Icons.Filled.Star else Icons.Filled.StarBorder,
                contentDescription = if (isFavorite) "Remove from favorites" else "Add to favorites",
                tint = if (isFavorite) FavoriteStarColor else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}
