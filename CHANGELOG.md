# Changelog

All notable changes to Algomodo will be documented in this file.

## [1.3.3] - Android

### Improvements
- Enhanced Lissajous generator with improved parameter handling
- Enhanced Plotter Streamlines generator with improved streamline rendering logic
- Added 4 new color palettes: Rainbow, Contrast, Earth, and Nature (total: 14)
- Optimized GIF export memory usage by avoiding storage of all boomerang frames in RAM

## [1.3.1] - Android

### New Generators (3)
- Added 3 new text generators: Glyphs (procedural abstract symbols), Naive Handwriting (childlike hand-drawn letters), Procedural Cursive (connected flowing script)
- Total generators: 107 across 10 families (up from 104)

### Improvements
- Parameter space expanded to over 9.5 million unique configurations (up from 9.4M)

## [1.3.0] - Android

### New Generators (12)
- Added 5 new fractal generators: Burning Ship, Fractal Flames, Multibrot, Orbit Traps, Strange Attractor Density
- Added 7 new graph generators: Geodesic, Constrained Delaunay, Anisotropic, Euler Trails, K-Nearest Neighbor, Gabriel Graph, Planar Graph
- Total generators: 104 across 10 families (up from 93)

### New Features
- Canvas touch gestures (enable in Settings > Touch Interaction):
  - Tap to expand canvas to full screen width, tap again to restore
  - Swipe right to randomize parameters
  - Swipe left to undo / return to previous render
  - Swipe up to trigger Surprise Me
  - Swipe down to undo / return to previous render
- Generator search bar for quick filtering by name or family
- Clear canvas button

### Improvements
- Parameter space expanded to over 9.4 million unique configurations (up from 7.6M)

## [1.2.0] - Android

### New Features
- Added in-app Report Bug dialog (Info menu → Report Bug) with name, email, and description fields, submitted via Formspree

### Improvements
- Enhanced GIF export performance with optimized color quantization and LZW encoding
- Smoother tab transitions using scrollToPage
- Improved bitmap handling in StaticCanvas to avoid blank flashes and ensure proper recycling

## [1.1.0] - Android

### Generator Improvements
- Rewrote 5 fractal generators: Mandelbrot (smooth coloring, interior detection), Julia (orbit traps, smooth iteration), Newton (multi-equation solver), IFS Barnsley (all 9 presets, flame/height/density coloring), Recursive Subdivision (true quad splits, noise coloring)
- Rewrote 9 geometry generators: Spirograph (epitrochoid mode, gradient coloring), Lissajous (multi-layer, harmonograph damping), Rosettes (real rose curves, bloom/morph animation), Chladni (4 formulas, 4 color modes, beat mixing), L-System (progressive growth animation, 7 presets, taper), Truchet Tiles (fixed — was completely broken), Moire (dots/radial patterns, 3 color modes), Islamic Patterns (star {n/k} polygons, girih lines, double-line ribbons), Superformula (layers, rotational copies, gradient coloring)
- Improved 5 animation generators: AttractorTrails, CurlFluid, FlowingParticles, Orbital, WaveInterference
- Improved 3 cellular automata: Game of Life, Eden Growth, Turing Patterns
- Fixed blurry Kaleidoscope and Plasma generators

### Bug Fixes
- Fixed image loading for all 14 image family generators
- Fixed image export/save (images now included in PNG/JPG/SVG output)
- Fixed RELOAD button not working for animations
- Fixed Truchet tiles rendering nothing (variant name mismatch)
- Fixed L-System presets not matching (case mismatch)

### New Features
- Show Original button to toggle between source and processed image
- All generator parameters now fully wired (previously many params were defined but unused)
- Animation support added to 32 additional generators

## [1.0.0] - Android

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
