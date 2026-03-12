package com.artmondo.algomodo.ui.dialogs

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun InstructionsDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("How to Use Algomodo") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                HelpSection("What is Algomodo?") {
                    "Algomodo generates art using pure mathematics. It has 107 generators across 10 families: noise, fractals, cellular automata, geometry, Voronoi, graphs, plotter, text, image processing, and animation. All processing runs 100% locally on your device."
                }
                HelpSection("What is a Seed?") {
                    "A seed is a number that determines the randomness of your art. The same seed + same parameters = exact same output every time. Tap the lock icon to preserve your seed across random operations."
                }
                HelpSection("SURPRISE ME vs RAND vs RELOAD") {
                    "SURPRISE ME: Picks a random generator, random parameters, random palette, and random seed. Complete creative reset.\n\n" +
                    "RAND: Randomizes parameters and seed for the current generator, respecting any locked parameters.\n\n" +
                    "RELOAD: Re-renders with the exact same settings. Useful after PostFX changes."
                }
                HelpSection("The Four Tabs") {
                    "Generators: Browse 10 families, select from 107 generators.\n\n" +
                    "Params: Adjust parameters for the current generator. Lock any parameter to preserve it during randomization.\n\n" +
                    "Export: Save as PNG, JPG, SVG, GIF, or MP4. Export/import recipes.\n\n" +
                    "Settings: Theme, quality, FPS, Post-processing effects."
                }
                HelpSection("Image Upload") {
                    "Image generators (14 total) require a source image. Tap 'Load Image' to pick from your gallery. Use 'Show Original' to toggle between the source image and processed output. Tap 'Clear Image' to remove it. The image is held in memory only — never saved or uploaded."
                }
                HelpSection("Text Generators") {
                    "Text generators accept custom text input. Use the pipe character | to create line breaks in Poem Layout. Glyphs creates procedural abstract symbols, Naive Handwriting renders childlike hand-drawn letters, and Procedural Cursive generates connected flowing script."
                }
                HelpSection("Animation") {
                    "Tap the play button to animate. Most generators support animation — fractals morph smoothly, geometry generators spin/bloom/morph, and cellular automata evolve their state over time. L-System animation shows progressive growth. Adjust FPS in Settings."
                }
                HelpSection("GIF & Video Export") {
                    "While animating, the Export tab shows animation options. Resolution (600/800/1000px) is shared between GIF and MP4.\n\n" +
                    "GIF: Duration up to 8 seconds (3s/5s/8s). Supports boomerang (ping-pong) and endless loop modes.\n\n" +
                    "MP4: Duration up to 60 seconds (5s/15s/30s or custom). Great for longer animations and social media sharing."
                }
                HelpSection("Undo & Redo") {
                    "Up to 50 steps. Undo/redo buttons on the canvas bar. Each parameter change, seed change, generator change, and randomization creates a history entry."
                }
                HelpSection("Color Palettes") {
                    "14 curated palettes with 5 colors each, plus a Random palette that generates 5 new random colors each time you tap it. Random colors use golden-angle hue spacing for visually diverse combinations. Lock the palette to preserve it during SURPRISE ME operations."
                }
                HelpSection("Presets") {
                    "Save your favorite generator + parameter + palette combos as presets. Each preset captures a circular thumbnail snapshot.\n\n" +
                    "Export Presets: Go to Export tab > 'Export Presets' to save all presets as a plain text (.txt) file. The format is compatible with the Algomodo web app — presets can be shared between platforms.\n\n" +
                    "Import Presets: Go to Export tab > 'Import Presets' to load a presets .txt file. Both the web app format and legacy JSON format are supported. Imported presets are added alongside existing ones.\n\n" +
                    "Long-press a preset thumbnail to delete it."
                }
                HelpSection("Generator Parameters") {
                    "Every parameter in the Params tab actively controls the output. Lock any parameter to preserve it during randomization. Parameters are grouped by category: Geometry, Composition, Color, Texture, and Flow/Motion."
                }
                HelpSection("Touch Gestures") {
                    "Enable in Settings > Touch Interaction.\n\n" +
                    "Tap: Expand canvas to full screen width. Tap again to restore.\n\n" +
                    "Swipe Right: Randomize parameters and seed.\n\n" +
                    "Swipe Left: Undo / return to previous render.\n\n" +
                    "Swipe Up: Trigger Surprise Me (new generator + params).\n\n" +
                    "Swipe Down: Undo / return to previous render."
                }
                HelpSection("Tips") {
                    "- Quick-save renders at 1080x1080\n" +
                    "- Lock palette + SURPRISE ME for themed exploration\n" +
                    "- Export recipes to share exact artworks\n" +
                    "- Stack PostFX: grain + vignette creates moody effects\n" +
                    "- Try different Color Modes on geometry generators for varied looks\n" +
                    "- Use Show Original to compare before/after on image generators"
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

@Composable
private fun HelpSection(title: String, content: () -> String) {
    Column {
        Text(title, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(2.dp))
        Text(content(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
