package com.artmondo.algomodo.ui.dialogs

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun UseCasesDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Top 10 Use Cases", fontWeight = FontWeight.Bold)
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                UseCaseItem(1, "Album & Single Covers", "Generate unique artwork for music releases and EPs.")
                UseCaseItem(2, "Phone & Desktop Wallpapers", "Create custom backgrounds for your devices.")
                UseCaseItem(3, "Social Media Content", "Eye-catching posts, banners, and profile art.")
                UseCaseItem(4, "Poster & Print Design", "Wall art, decorative prints, and gallery pieces.")
                UseCaseItem(5, "Brand Identity & Logos", "Abstract visual elements for branding and identity.")
                UseCaseItem(6, "NFT & Digital Collectibles", "One-of-a-kind generative art pieces.")
                UseCaseItem(7, "Textile & Pattern Design", "Repeating patterns for fabrics and merchandise.")
                UseCaseItem(8, "Presentation Backgrounds", "Professional visual backdrops for slides and decks.")
                UseCaseItem(9, "Game & App Assets", "Textures, backgrounds, and UI elements for apps.")
                UseCaseItem(10, "Creative Exploration", "Learn generative art concepts hands-on.")
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

@Composable
private fun UseCaseItem(number: Int, title: String, description: String) {
    Column {
        Text(
            "$number. $title",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
