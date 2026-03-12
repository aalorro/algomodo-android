package com.artmondo.algomodo.ui.dialogs

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun ChangelogDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Changelog") },
        text = {
            Text(
                text = """
v1.3.4 (Android)
Improvements:
- Rewrote 9 plotter generators to fully wire all declared parameters
- Optimized Guilloche animation performance
- Fixed Contour Lines rendering
- Fixed Streamlines spiral effect
- Improved Export tab layout for small phone screens
- Touch interaction now enabled by default
- GIF endless loop now enabled by default

v1.3.3 (Android)
Improvements:
- Enhanced Lissajous generator with improved parameter handling
- Enhanced Plotter Streamlines generator with improved streamline rendering logic
- Added 4 new color palettes: Rainbow, Contrast, Earth, and Nature (total: 14)
- Optimized GIF export memory usage by avoiding storage of all boomerang frames in RAM

v1.3.1 (Android)
New Generators (3):
- 3 new text generators: Glyphs (procedural abstract symbols), Naive Handwriting (childlike hand-drawn letters), Procedural Cursive (connected flowing script)
- Total: 107 generators across 10 families

Improvements:
- Parameter space expanded to over 9.5 million unique configurations

v1.3.0 (Android)
New Generators (12):
- 5 new fractal generators: Burning Ship, Fractal Flames, Multibrot, Orbit Traps, Strange Attractor Density
- 7 new graph generators: Geodesic, Constrained Delaunay, Anisotropic, Euler Trails, K-Nearest Neighbor, Gabriel Graph, Planar Graph
- Total: 104 generators across 10 families

New Features:
- Canvas touch gestures (Settings > Touch Interaction): tap to expand, swipe right to randomize, swipe left to undo, swipe up for Surprise Me, swipe down to undo
- Generator search bar for quick filtering
- Clear canvas button

Improvements:
- Parameter space expanded to over 9.4 million unique configurations

v1.2.0 (Android)
New Features:
- Added in-app Report Bug dialog (Info menu → Report Bug) with name, email, and description fields, submitted via Formspree

Improvements:
- Enhanced GIF export performance with optimized color quantization and LZW encoding
- Smoother tab transitions using scrollToPage
- Improved bitmap handling in StaticCanvas to avoid blank flashes and ensure proper recycling

v1.1.0 (Android)
Generator Improvements:
- Rewrote 5 fractal generators: Mandelbrot (smooth coloring, interior detection), Julia (orbit traps, smooth iteration), Newton (multi-equation solver), IFS Barnsley (all 9 presets, flame/height/density coloring), Recursive Subdivision (true quad splits, noise coloring)
- Rewrote 9 geometry generators: Spirograph (epitrochoid mode, gradient coloring), Lissajous (multi-layer, harmonograph damping), Rosettes (real rose curves, bloom/morph animation), Chladni (4 formulas, 4 color modes, beat mixing), L-System (progressive growth animation, 7 presets, taper), Truchet Tiles (fixed — was completely broken), Moire (dots/radial patterns, 3 color modes), Islamic Patterns (star {n/k} polygons, girih lines, double-line ribbons), Superformula (layers, rotational copies, gradient coloring)
- Improved 5 animation generators: AttractorTrails, CurlFluid, FlowingParticles, Orbital, WaveInterference
- Improved 3 cellular automata: Game of Life, Eden Growth, Turing Patterns
- Fixed blurry Kaleidoscope and Plasma generators

Bug Fixes:
- Fixed image loading for all 14 image family generators
- Fixed image export/save (images now included in PNG/JPG/SVG output)
- Fixed RELOAD button not working for animations
- Fixed Truchet tiles rendering nothing (variant name mismatch)
- Fixed L-System presets not matching (case mismatch)

New Features:
- Show Original button to toggle between source and processed image
- All generator parameters now fully wired (previously many params were defined but unused)
- Animation support added to 32 additional generators

v1.0.0 (Android)
- Initial Android release
- All 93 generators across 10 families
- Full animation support with SurfaceView
- Export to PNG, JPG, SVG, GIF, MP4
- 10 curated color palettes
- Save/load presets with Room database
- Recipe import/export (cross-platform)
- Post-processing effects: grain, vignette, dither, posterize
- Dark/light theme support
- Material 3 design
- Undo/redo history (50 steps)
- Touch interaction support
- Gallery/camera source image loading
                """.trimIndent(),
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 300.dp)
                    .verticalScroll(rememberScrollState())
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}
