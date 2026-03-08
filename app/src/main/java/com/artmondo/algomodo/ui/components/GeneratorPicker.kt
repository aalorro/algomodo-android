package com.artmondo.algomodo.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.artmondo.algomodo.core.registry.GeneratorRegistry
import com.artmondo.algomodo.generators.Generator
import com.artmondo.algomodo.generators.GeneratorFamily

@Composable
fun GeneratorPicker(
    selectedGeneratorId: String?,
    selectedFamilyId: String,
    onSelectGenerator: (Generator) -> Unit,
    onSelectFamily: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val families = remember { GeneratorRegistry.allFamilies() }
    val generators = remember(selectedFamilyId) { GeneratorRegistry.generatorsInFamily(selectedFamilyId) }

    val familyListState = rememberLazyListState()
    val generatorListState = rememberLazyListState()

    // Auto-scroll family row to selected family
    LaunchedEffect(selectedFamilyId) {
        val familyIndex = families.indexOfFirst { it.id == selectedFamilyId }
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
            items(families) { family ->
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

        // Generator list
        Text(
            text = "Generators",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, bottom = 4.dp)
        )

        LazyColumn(
            state = generatorListState,
            verticalArrangement = Arrangement.spacedBy(4.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            items(generators) { gen ->
                GeneratorItem(
                    generator = gen,
                    isSelected = gen.id == selectedGeneratorId,
                    onClick = { onSelectGenerator(gen) }
                )
            }
        }
    }
}

@Composable
private fun GeneratorItem(
    generator: Generator,
    isSelected: Boolean,
    onClick: () -> Unit
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
            .padding(horizontal = 12.dp, vertical = 10.dp),
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
    }
}
