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
                    "Algomodo is an algorithmic art generator that runs entirely on your device. Nothing is uploaded — all processing happens locally.\n\n" +
                    "Choose a family from the Generators tab, then pick a style to generate art. There are 134 generators across 12 families: Animation, Cellular, Flux, Fractals, Geometry, Graphs, Image, Noise, Plotter, Procedural, Text, and Voronoi."
                }
                HelpSection("What is a Seed?") {
                    "A seed is a number that controls the randomness in your artwork. The same seed with the same parameters and palette will always produce the exact same result, every time. This means you can share a seed number with someone else and they will see the identical artwork.\n\n" +
                    "• The seed is shown at the top of the Params tab.\n" +
                    "• Lock the seed (tap the lock icon) to keep the same seed while randomizing other settings. This lets you explore different parameter combinations on the same underlying pattern.\n" +
                    "• Randomize Seed generates a new seed, giving you a completely fresh starting point."
                }
                HelpSection("SURPRISE ME vs RAND vs RELOAD") {
                    "SURPRISE ME: Picks a completely random generator from any family, with random parameters, a random palette, and a new seed. Use this when you want to discover something entirely new.\n\n" +
                    "RAND: Keeps the current generator but randomizes all unlocked parameters and generates a new seed. Use this to explore different variations of the same style. Any locked parameters stay the same.\n\n" +
                    "RELOAD: Re-renders the artwork from scratch with the exact same settings. Use this to replay an animation that has reached a stop state or to force a fresh redraw after PostFX changes."
                }
                HelpSection("The Four Tabs") {
                    "Generators: Browse 12 families, select from 134 generators. Swipe left and right through the family tabs to reveal the generators in each family. Use the search bar to quickly filter by name or family.\n\n" +
                    "Params: The main control panel. Every generator has its own set of parameters — sliders for numbers, chips for modes, toggles for booleans, and text fields for custom input. Lock individual parameters with the lock icon to keep them fixed during randomization.\n\n" +
                    "Export: Download your artwork as PNG, JPG, SVG, GIF, or MP4. Export/import recipes that save every setting so the artwork can be recreated exactly. Export/import presets to share between devices or with the web app.\n\n" +
                    "Settings: Theme (light/dark), quality (Draft/Balanced/High), animation FPS, touch interaction, and PostFX (Grain, Vignette, Dither, Posterize)."
                }
                HelpSection("Image Upload") {
                    "The Image family of generators transforms your photos into algorithmic art. Unlike other families that generate patterns from scratch, Image generators need a source image to work with.\n\n" +
                    "Tap 'Load Image' to pick from your gallery or camera. Once loaded, the image feeds into generators like Pixel Sort, Mosaic, Halftone, ASCII Art, Dither, Lino Cut, Edge Detect, and more. Use 'Show Original' to toggle between the source and processed output. Tap 'Clear Image' to remove it. The image is held in memory only — never saved or uploaded."
                }
                HelpSection("Text Generators") {
                    "The Text family creates typographic art from characters and words. Each generator has an optional custom text field — type your own characters, words, or sentences. Leave it empty to use the default random content. In the Poem Layout generator, separate lines with the | character."
                }
                HelpSection("Audio Reactivity") {
                    "The Flux and Procedural families are fully audio-reactive. The Flux family contains 12 TouchDesigner-inspired generators: Perlin Flow, Simplex Warp, Feedback Loop, Pixel Sort Feedback, Metaballs 2D, Wireframe Terrain, Waveform Stacker, Signal Rings, Displacement Map, Domain Repetition, Instanced Scatter, and Trail System. The Procedural family contains 9 audio-reactive generators: Feedback Systems, Warp, Field + Particle Motion, Instanced Geometry, Audio-Reactive, SDF Raymarch, Displacement, Edge + Glow, and Particle Advection. Some generators in the Plotter, Graphs, and Geometry families also support audio reactivity to a certain extent.\n\n" +
                    "To use audio reactivity, load an audio file (MP3, WAV, OGG) via the audio section. When animation is playing, the audio plays in sync and the generators react to bass, mid, and high frequency energy in real time.\n\n" +
                    "• Use the Audio Reactivity slider (in Flow/Motion) to control how strongly the generator responds to the audio.\n" +
                    "• The seek slider lets you scrub to any point in the track.\n" +
                    "• Audio always starts from the beginning when you press Animate."
                }
                HelpSection("Animation") {
                    "Many generators support animation — particles flow, cells evolve, fractals morph, and patterns transform over time. Toggle animation with the play button. Adjust FPS in Settings.\n\n" +
                    "Some generators (especially Cellular like Game of Life, Reaction Diffusion, and Crystal Growth) are designed to be viewed as animations. If a static render looks sparse, try enabling animation to see the full effect."
                }
                HelpSection("Fractal Generators") {
                    "Some fractal generators (like Multibrot and Orbit Traps) use an animated zoom that slowly drifts toward interesting areas along the fractal boundary. The fractal shape may temporarily move toward the edge of the canvas or appear to shrink before cycling back. This is normal — the animation follows a repeating cycle.\n\n" +
                    "Strange Attractors work differently — they build up detail by plotting millions of individual points. On first load, the image may appear faint or sparse. Give it a moment to accumulate enough points. Changing the seed or tapping Surprise Me will generate a completely different attractor shape."
                }
                HelpSection("GIF & Video Export") {
                    "Enable animation first, then go to the Export tab.\n\n" +
                    "GIF: Duration up to 8 seconds (3s/5s/8s). Resolution options: 600px (fastest), 800px, or 1000px. Supports boomerang (ping-pong) and endless loop modes.\n\n" +
                    "MP4: Set start and end times (in seconds) to control the duration. Renders offscreen at full speed. When an audio file is loaded, the MP4 includes the audio track synced to the chosen time range. Audio-reactive generators will respond to the audio during export."
                }
                HelpSection("Undo & Redo") {
                    "Up to 50 steps. Undo/redo buttons on the canvas bar. Each parameter change, seed change, generator change, and randomization creates a history entry."
                }
                HelpSection("Color Palettes") {
                    "14 curated palettes with 5 colors each, plus a Random palette that generates 5 new random colors each time. Random colors use golden-angle hue spacing for visually diverse combinations.\n\n" +
                    "Lock the palette (tap the lock icon) to keep your colors while randomizing other settings. When unlocked, both Surprise Me and Rand will pick a new palette automatically.\n\n" +
                    "Custom palettes: Create up to 5 custom palettes with your own hex colors. Custom palettes appear alongside the curated list and persist across sessions."
                }
                HelpSection("Presets") {
                    "Save your favorite generator + parameter + palette combos as presets. Tap the floating Presets button at the bottom of the screen (available on all tabs) to open the presets panel. You can drag the button horizontally to reposition it so it doesn't block parameter controls. Tap the + button to save the current state, or tap any preset thumbnail to load it. Long-press a preset thumbnail to delete it. Tap outside the panel to dismiss it.\n\n" +
                    "Export Presets: Go to Export tab > 'Export Presets' to save all presets as a plain text (.txt) file. The format is compatible with the Algomodo web app — presets can be shared between platforms.\n\n" +
                    "Import Presets: Go to Export tab > 'Import Presets' to load a presets .txt file. Both the web app format and legacy JSON format are supported. Imported presets are added alongside existing ones."
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
                    "• The SAVE button on the canvas is a quick export at 1080x1080. The Export tab gives you more control: multiple formats, custom resolutions up to 8K, duration settings, and loop options.\n" +
                    "• Lock a palette, then use SURPRISE ME to see random generators in your favorite colors.\n" +
                    "• Export recipes to save and share exact artwork configurations.\n" +
                    "• PostFX (Grain, Vignette, Dither, Posterize) work with every generator — try stacking them.\n" +
                    "• SVG export is available for vector-compatible generators (Plotter, Geometry, some others).\n" +
                    "• Quality (Draft/Balanced/High) controls how many iterations, particles, or detail passes a generator performs. Draft is fast but coarse; High is rich in detail but takes longer.\n" +
                    "• Canvas looks blank after randomizing? Just tap SURPRISE ME or RAND again. This can happen when randomized palette colors are dark on a dark background, or parameters land in a faint combination."
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
