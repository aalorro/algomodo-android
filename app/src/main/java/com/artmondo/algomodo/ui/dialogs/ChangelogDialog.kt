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
v1.8.2 (Android)
New Features:
- Favorites: star any generator to add it to the yellow Favorites tab, pinned before Animation. Persists across sessions.
- Kaleidoscope: new Symmetry parameter (none, 2-way, 4-way, 8-way) overlays cartesian mirror axes on the radial segments for new geometric effects.

Improvements:
- Kaleidoscope: major optimization for iridescent color mode (~segments× speedup) via decoupled pattern/color passes, integer pattern dispatch, cached SimplexNoise, precomputed per-harmonic constants, and angle LUTs
- Flux generators (Perlin Flow, Trail System, Pixel Sort Feedback): dimension-keyed state caching eliminates recycled-bitmap crashes when bitmap size changes between preview and export
- Help dialog: new "Favorites vs Presets" section explaining the difference

Bug Fixes:
- Spirograph & Superformula: fixed 3D perspective artifacts in export via near-plane clipping

v1.8.1 (Android)
Improvements:
- Kaleidoscope: new pattern modes (marble, fractal, geometric), detail/sharpness params, multi-threaded rendering
- GIF export: delta frame encoding with sub-rect support for smaller files
- Floating presets button: horizontal dragging and tap gesture
- Improved thumbnail rendering
- Updated help text for MP4 export and generator navigation

Bug Fixes:
- Fixed Feedback Loop generator not rendering (bloom self-draw fix, state cache invalidation)

v1.8.0 (Android)
New Generators (17):
- New Flux family: 12 audio-reactive generators (Perlin Flow, Simplex Warp, Feedback Loop, Pixel Sort Feedback, Metaballs 2D, Wireframe Terrain, Waveform Stacker, Signal Rings, Displacement Map, Domain Repetition, Instanced Scatter, Trail System)
- Harmonics, Gravity Packing, Rubik's Permutation (geometry)
- Sprott Quadratic (animation), Text Code (text)
- Total: 134 generators across 12 families

New Features:
- Flux family: 12 TouchDesigner-inspired audio-reactive generators
- Custom color palettes: create up to 5 named palettes
- Floating presets button: accessible from all tabs, collapsible overlay
- 8K resolution export
- 3D perspective for Spirograph and Superformula
- Preset saved notification bubble
- Aspect ratio cropping when loading images
- New splash screen logo

Improvements:
- Orbital: redesigned with comets, ion tails, pulsing star coronas, twinkling star field, trinary mode, speed-reactive glow
- Simplex Noise Field: 5 noise transforms, ~10× faster via combined LUT + multi-threading, new pulse anim mode
- Circle Packing: thread-safe cache, pulse mode, spin speed, color shift, audio reactivity
- Phyllotaxis: new canvas fill, divergence, glow, depth fade params
- Edge Glow: improved edge detection with adaptive pixel step
- Voronoi Crackle: concave distance metric
- Displacement: improved rendering performance
- Video export: adaptive bitrate based on quality
- Presets moved to floating overlay for more parameter space

Bug Fixes:
- Fixed Circle Packing export turbulence (thread race condition)
- Fixed Simplex Noise Field broken parameters (Anim Mode, Color Mode, Band Count, Octaves)

v1.7.0 (Android)
Performance:
- Optimized 10 generators with simulation caching (Ising, Particle Advection, Sandpile, Percolation, Reaction Diffusion, Cyclic CA, Crystal Growth, CurlFluid, Strange Attractor, Game of Life)
- Two-phase rendering: instant draft preview + full quality
- Mandelbrot/Multibrot: adaptive resolution with exponential zoom

v1.6.1 (Android)
Performance:
- Optimized 10 generators with simulation caching (Ising, Particle Advection, Sandpile, Percolation, Reaction Diffusion, Cyclic CA, Crystal Growth, CurlFluid, Strange Attractor, Game of Life)
- Two-phase rendering: instant draft preview + full quality
- Mandelbrot/Multibrot: adaptive resolution with exponential zoom

Improvements:
- Palette locking (preserve palette during randomize)
- Reaction Diffusion presets now produce distinct patterns (spots, stripes, worms, maze, etc.)
- Crystal Growth animation shows real-time growth from seed
- Cyclic CA: adaptive threshold for consistent animation
- Game of Life: new color modes
- Text Matrix: adjustable font size
- Bright palette subset

Bug Fixes:
- Fixed Reaction Diffusion presets all looking identical
- Fixed Cyclic CA Von Neumann mode not animating
- Fixed Crystal Growth animation showing no growth
- Fixed Multibrot freeze during animation
- Total: 118 generators

v1.6.0 (Android)
New Features:
- Aspect ratio support across all generators and UI (square, landscape, portrait)
- Generator label overlay on expanded canvas

Improvements:
- Steiner Networks: subtree coloring and optimized color mode calculations
- Gabriel Graph: smooth sweeping highlight-circles animation with pulsing glow
- Gabriel Graph: Delaunay overlay shows only non-Gabriel edges with improved visibility
- Improved canvas layout for all orientations
- Cleaner splash screen (app logo only)

Bug Fixes:
- Fixed highlight-circles animation in Gabriel Graph
- Fixed Show Delaunay toggle in Gabriel Graph
- Fixed expanded canvas aspect ratio sizing
- Fixed animation canvas on aspect ratio change

v1.5.2 (Android)
New Generator:
- Pythagoras Tree (fractals) — total: 117 generators

Performance:
- Optimized all 11 Voronoi generators (spatial grids, metric-split paths, sqrt elimination)

Improvements:
- Render debounce for smoother parameter changes
- Export panel keyboard improvements
- Smarter animation parameter randomization

v1.5.1 (Android)
Improvements:
- Rewrote IFS/Barnsley (histogram rendering, lean/curl/spread, symmetry, tone mapping)
- Optimized Kaleidoscope, Plasma Feedback, Wave Interference, Fractal Flames, L-System, DLA
- Moved Recursive Subdivision to Geometry family

v1.5.0 (Android)
New Generators (9):
- 9 new procedural generators (new family): Audio Reactive, Displacement, Edge Glow, Feedback Systems, Field Particle, Instanced Geometry, Particle Advection, SDF Raymarch, Warp
- Total: 116 generators across 11 families

New Features:
- Audio reactivity for procedural generators
- Video export with audio support and adjustable start/end times
- Parameter buttons replace dropdowns for one-tap selection

Improvements:
- Rewrote DLA generator (circle-jump optimization, rotational symmetry, background glow)
- Rewrote Ecosystems generator (multi-species food chain, 5 visual styles)
- Enhanced Newton fractal, SDF Raymarch, and AttractorTrails generators
- Fixed Anisotropic generator anisotropy parameter

v1.4.0 (Android)
New Features:
- Progressive reveal animation for Maze/Meander generator
- Random color palette generation
- Animated "Surprise Me" button with gold shimmer

Performance:
- Optimized rendering across all 107 generators (cached palette colors, single-pass PostFX)
- Pre-computed color lookup tables for pixel-loop generators
- Bulk pixel reads replace per-pixel JNI calls

Improvements:
- Instant canvas transitions on Surprise Me
- Milestone progress bar for long renders (25%, 60%, 90%)
- Surprise Me always picks active options, avoids extreme values
- Enhanced Voronoi and graph generators
- Animation snapshot time for accurate exports

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
