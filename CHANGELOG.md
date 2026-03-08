# Changelog

All notable changes to Algomodo will be documented in this file.

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
