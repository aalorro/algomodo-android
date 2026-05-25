# Changelog

All notable changes to Algomodo will be documented in this file.

## [2.1.0] - Android

### GPU Shader Pipeline
- New `GpuGenerator` interface with offscreen EGL + FBO + glReadPixels backend. Every existing CPU call site (live preview, all five export paths, PostFX) works unchanged — the runner writes pixels straight into the caller's Bitmap.
- Compiled programs cached per shader-source hash. Thread-local context with save/restore for nesting inside another EGL context (used by video export).

### Restored Shader Family (13 generators)
- All 13 originally-CPU ray-marched 3D generators now reimplemented as GPU fragment shaders: Apollonian Spheres, Caustic Pool, Crystal Cavern, Geodesic, Glass Garden, Gray-Scott 3D, Heightfield Horizon, Infinite Lattice, Mandelbulb, Metaball Cluster 3D, SDF Sculpt, Tunnel Vision, Twisted Forms.

### GPU Family Ports
- **Noise** (7 generators): NoiseFbm, NoiseRidged, NoiseTurbulence, NoiseDomainWarp, NoiseSimplexField, DomainWarpMarble, FbmTerrain — ported to GPU shaders.
- **Voronoi** (10 generators): VoronoiCells, CentroidalVoronoi, VoronoiContours, VoronoiCrackle, VoronoiDepth, VoronoiMosaic, VoronoiWeighted, VoronoiFractured, VoronoiNeighborBands (hybrid CPU adjacency + GPU render), VoronoiRidges — ported to GPU shaders. DelaunayMesh remains CPU (vector path).
- **Escape-time Fractals** (8 generators): Mandelbrot, Julia, Multibrot, BurningShip, Newton, OrbitTraps, Lyapunov, FractalInterior — ported to GPU shaders. Smooth iteration colouring preserved.

### New Multibrot Features
- Variant selector (standard / burning / tricorn) for Mandelbrot-family transforms.
- Julia Mode toggle with Julia C Real/Imag controls — in Julia mode, animation orbits the C value.
- Manual Rotation parameter.
- Color Mode (smooth / bands) with Band Count and Color Shift palette phase offset.

### Improvements
- Total generator count now 177 across 15 families (Shader restored).
- Centroidal Voronoi: Lloyd relaxation now LRU-cached per (seed, count, size, metric) — runs once instead of every frame, eliminating the #1 cause of Voronoi FPS drops.
- Translucent center play-button overlay on paused canvas.

### Bug Fixes
- Apollonian Spheres blank render and palette-less background.
- Voronoi Weighted: multiplicative-weight mode no longer renders blank canvas.
- Voronoi Fractured: Fracture Width and Shard Shading now have visible effect.
- Voronoi Depth: most parameters now have visible effect (normal field was inverted).

## [2.0.0] - Android

### New Generator Families (2)
- **Pixel Art** (12 generators): low-resolution generators rendered with nearest-neighbor upscaling — Pixel Automata, Pixel City, Pixel Diffusion, Pixel Dither, Pixel Flow Field, Pixel Harmonograph, Pixel Maze, Pixel Portraits, Pixel Symmetry, Pixel Terrain, Pixel Voronoi, Pixel Worm
- **Physics** (12 generators): simulation-based generators — Boids, Brownian Motion, Cloth Sim, Electric Field, Fluid Dynamics, Magnetic Field, N-Body, Nuclear, Pendulum Systems, Gravity Packing (physics variant), Spring Networks, Wave Propagation

### Improvements
- Total generator count now 164 across 14 families
- CrystalCavernGenerator: zero-alloc hashing, bounding sphere early-out, step-count AO
- Apollonian Spheres and Caustic Pool: higher fps and resolution
- CausticPoolGenerator: unified stencil, floor styles, higher resolution

### Removed
- Shader family (13 CPU ray-marched 3D generators): could not achieve interactive fps on mobile

## [1.9.1] - Android

### New Generators (1)
- Helix (Procedural): DNA-inspired multi-strand helix with depth-sorted pseudo-3D, 5 variants (classic, particle, ribbon, zdna, supercoil), 4 color modes, breathing/mutation noise, and audio reactivity

### Improvements
- Generator selection now randomizes parameters, seed, and palette instead of always showing the same default render
- Parameter help text now displayed below each control in the Params tab
- GIF loop export: accumulation-based generators (Flowing Particles, Curl Fluid) pre-warm for seamless looping without visible reset at loop boundary

### Bug Fixes
- Fixed "trying to use a recycled bitmap" crash when exporting GIF/MP4 from Flowing Particles and Curl Fluid (thread-local state isolation prevents live canvas and export thread from sharing mutable bitmaps)
- Fixed keyboard appearing randomly — tapping anywhere outside a text field now dismisses the keyboard

## [1.9.0] - Android

### New Generators (5)
- Fractal Interior: interior coloring of Mandelbrot/Julia/Newton/Tricorn/Burning Ship fractals with orbit trap, period detection, multiplier, and interior distance estimation modes
- Lyapunov Fractal: stability map of alternating logistic maps with animated drift through parameter space, multiple sequence patterns, and smooth anti-aliased rendering
- Square Gasket: geometric Sierpinski-style square fractal
- Stipple Portrait: image-to-stipple conversion using weighted Voronoi relaxation
- Space Filling Curve: Hilbert, Moore, Peano, and other space-filling curve plotters

### Improvements
- Flowing Particles: complete rewrite with curl-noise flow field, 6 pattern modes (flow, swirl, split, gravity, pulse-wave, highway), 5 color modes, turbulence and pulse parameters
- Flowing Particles: particle respawning system keeps canvas uniformly populated
- Flowing Particles: line shape optimized (filled rectangles instead of stroked lines with round caps — eliminates 10x fps drop)
- Curl Fluid: rewritten with offscreen accumulation rendering matching web version behavior (persistent trails with semi-transparent fading)
- Curl Fluid: movement modes (curl, wave, spiral) now produce visibly distinct motion patterns
- Lyapunov Fractal: fast ln() approximation via IEEE 754 bit decomposition (~3x faster inner loop)
- Lyapunov Fractal: 1.5x supersampling for smooth fractal boundaries on static renders
- Fractal Interior: bilinear upscale replaces nearest-neighbor for smoother animation
- Mandelbrot: multiple animation movement styles
- Orbit Traps: new parameters for trap shapes, animation, and color modes
- Lissajous: new patterns, styles, and improved rendering
- Random palettes: support for RAND 5, RAND 8, and RAND 10 palette sizes
- Palette selection: custom palettes included in randomization
- Undo: state saved to redo stack even when generator is null
- Multi-threaded rendering with improved color packing across generators

### Bug Fixes
- Fixed glitchy GIF and MP4 exports: frame 0 no longer renders as static image (time epsilon ensures consistent animation mode across all exported frames)
- Fixed glitchy exports from concurrent rendering: eliminated shared mutable state (cancelled flag, pixel arrays) between live canvas and export threads on singleton generators
- Fixed Fractal Interior and Lyapunov coordinate mapping bug that stretched the viewport during animation
- Curl Fluid: particles no longer disappear from screen (respawn instead of wrap)
- Curl Fluid: max particle count capped at 4000 for stable fps

## [1.8.3] - Android

### Improvements
- App title updated to "Algomodo - Generative Art" for better Play Store discoverability
- Wireframe Terrain: scene-specific atmosphere — neon gets a perspective grid floor, starfield gets animated nebula color wash, void gets stark cubic fog falloff
- Wireframe Terrain: smoother animation — toned-down glitch shift, reduced eruption/pulse height spikes, vertical connectors rendered without glow blur

### Bug Fixes
- Wireframe Terrain: fixed chaotic animation and stray line artifacts caused by concurrent render threads corrupting shared height/projection buffers (thread-safe immutable snapshot caches)
- Wireframe Terrain: clamped terrain heights to prevent extreme perspective projection sending lines above the horizon
- Removed READ_MEDIA_IMAGES permission to comply with Google Play Photo and Video Permissions policy (app already uses Android Photo Picker which requires no permission)

## [1.8.2] - Android

### New Features
- Favorites: star/bookmark any generator from the list. A yellow Favorites tab pinned before Animation shows all favorited generators across families. Persists across sessions.
- Kaleidoscope: new Symmetry parameter (none, 2-way, 4-way, 8-way) overlays cartesian mirror axes on the radial segments, enabling new geometric compositions like quadrant or octant tilings.

### Improvements
- Kaleidoscope: major optimization for iridescent color mode — decoupled pattern computation from color application so iridescent no longer scales with segment count (~segments× speedup). Integer pattern dispatch, cached SimplexNoise per seed, precomputed per-harmonic/octave/source constants, and segTheta/iridescent-shift LUTs.
- Flux generators (Perlin Flow, Trail System, Pixel Sort Feedback): dimension-keyed state caching eliminates recycled-bitmap crashes when bitmap size changes between preview and export threads.
- Help dialog: new "Favorites vs Presets" section clarifies the difference between the two.

### Bug Fixes
- Spirograph & Superformula: fixed 3D perspective rendering artifacts in export via near-plane clipping and visibility management.

## [1.8.1] - Android

### Improvements
- Kaleidoscope: new pattern modes (marble, fractal, geometric), detail and sharpness parameters, multi-threaded rendering with improved LUTs
- GIF export: delta frame encoding with sub-rect support for smaller file sizes
- Floating presets button: horizontal dragging and tap gesture support
- Improved thumbnail rendering logic
- Updated help text for MP4 export and generator navigation

### Bug Fixes
- Fixed Feedback Loop generator not rendering (bloom self-draw undefined behavior on Android, state cache not invalidated on emitter count change)

## [1.8.0] - Android

### New Generators (17)
- New Flux family with 12 audio-reactive TouchDesigner-inspired generators: Perlin Flow, Simplex Warp, Feedback Loop, Pixel Sort Feedback, Metaballs 2D, Wireframe Terrain, Waveform Stacker, Signal Rings, Displacement Map, Domain Repetition, Instanced Scatter, and Trail System
- Added Harmonics generator (geometry family)
- Added Gravity Packing generator (geometry family) — dynamic shape stacking simulation
- Added Rubik's Permutation generator (geometry family) — NxN grid color permutations
- Added Sprott Quadratic generator (animation family) — chaotic 2D iterated map animations
- Added Text Code generator (text family) — procedural code generation with customizable rendering
- Total generators: 134 across 12 families

### New Features
- New Flux generator family: 12 audio-reactive generators inspired by TouchDesigner workflows
- Custom color palettes: create up to 5 named palettes with custom hex colors, persisted across sessions
- Floating presets button: accessible from all tabs via a gold button at the bottom-left corner with collapsible overlay panel
- 8K resolution export option
- 3D perspective mode for Spirograph generator (with adjustable tilt)
- 3D perspective mode for Superformula generator
- Preset saved confirmation bubble with auto-dismiss
- Aspect ratio cropping when loading source images (matches current canvas orientation)
- New splash screen logo

### Improvements
- Orbital: full redesign — comets with multi-layer ion tails pointing away from nearest star, pulsing star coronas with rotating radial rays, twinkling background star field, new trinary star configuration, speed-reactive body glow at perihelion, smoother quadratic-alpha trails, SoA acceleration buffers
- Simplex Noise Field: refactored Style parameter into 5 noise transforms (smooth, ridged, turbulent, billow, veins) — all pixel-rendered for consistent quality. ~10× performance via combined 1024-entry style/band/palette LUT, multi-threaded row partitioning, adaptive resolution rendering, and per-anim-mode specialized inner loops. New pulse animation mode
- Circle Packing: thread-safe LRU cache for packing data eliminates export turbulence; new pulse mode, pulse amount, spin speed, color shift parameters; audio reactivity; improved outline contrast in filled+outline mode
- Phyllotaxis: new canvas fill, divergence angle, glow, and depth fade parameters; improved color contrast handling
- Edge Glow: refactored edge detection with adaptive pixel step for gradient and ridge modes
- Displacement: improved rendering logic for clarity and performance
- Voronoi Crackle: new concave distance metric
- Curl Fluid: configurable width and height parameters for simulation control
- Stippling: contrast adjustment for improved visibility against background colors
- Sprott Quadratic: improved overflow handling and performance
- Video export: adaptive bitrate and resolution based on quality settings
- MainScreen layout: palette strip and action buttons grouped together for cleaner UI
- Presets moved from fixed Params tab position to floating collapsible overlay, freeing screen space for parameters

### Bug Fixes
- Fixed Circle Packing export turbulence/choppiness caused by thread race condition on shared cache arrays
- Fixed Simplex Noise Field parameters (Anim Mode, Color Mode, Band Count, Octaves) being effectively non-functional due to legacy line/circle render styles
- Fixed README generator count (was stale at 117)

## [1.6.1] - Android

### Performance
- Optimized 10 cellular/procedural generators with simulation caching — Ising Model, Particle Advection, Sandpile, Percolation, Reaction Diffusion, Cyclic CA, Crystal Growth, CurlFluid, Strange Attractor Density, Game of Life now run at smooth framerates instead of 2-4 fps
- Two-phase rendering pipeline: instant draft preview followed by full-quality render for responsive parameter changes
- Mandelbrot/Multibrot: adaptive resolution during animation with exponential zoom
- OrbitTraps: adaptive resolution for improved animation performance
- Strange Attractor Density: Lyapunov quality check, bilinear splatting, 3×3 histogram blur for smoother rendering

### Improvements
- Palette locking: tap the lock icon to preserve your palette during randomize/surprise-me
- Reaction Diffusion: sub-stepping for numerical stability — presets (spots, stripes, worms, maze, etc.) now produce visually distinct patterns
- Cyclic CA: adaptive threshold capping based on state count and neighborhood — consistent animation for all configurations
- Crystal Growth: animation now shows real-time growth from seed instead of pre-computed result
- Game of Life: new color modes and optimized simulation caching
- Text Matrix: adjustable font size parameter
- Curated bright palette subset for improved visibility on dark backgrounds
- Smarter parameter randomization: fill parameter always keeps default in randomize/surprise-me

### Bug Fixes
- Fixed Reaction Diffusion presets all producing identical patterns (numerical instability + excessive spatial variation)
- Fixed Cyclic CA Von Neumann mode not animating (threshold too high for 4-neighbor topology)
- Fixed Crystal Growth animation showing no growth (warmup steps were pre-computed before animation)
- Fixed Multibrot freeze during animation
- Total generators: 118 across 11 families

## [1.6.0] - Android

### New Features
- Aspect ratio support across all generators and UI (square, landscape, portrait)
- Generator label overlay on expanded canvas

### Improvements
- Steiner Networks: subtree coloring and optimized color mode calculations
- Gabriel Graph: smooth sweeping highlight-circles animation with pulsing glow
- Gabriel Graph: Delaunay overlay now shows only non-Gabriel edges with improved visibility
- Improved canvas layout and aspect ratio handling for all orientations
- Cleaner splash screen (app logo only)

### Bug Fixes
- Fixed highlight-circles animation in Gabriel Graph (was non-functional)
- Fixed Show Delaunay toggle in Gabriel Graph (was invisible at 12% opacity)
- Fixed expanded canvas layout with correct aspect ratio sizing
- Fixed animation canvas not recreating SurfaceView on aspect ratio change

## [1.5.2] - Android

### New Generators (1)
- Added Pythagoras Tree generator (fractals family)
- Total generators: 117 across 11 families (up from 116)

### Performance
- Optimized all 11 Voronoi generators with spatial grids, linked-list indexing, and metric-split render paths (Euclidean/Manhattan/Chebyshev)
- Voronoi Ridges: per-octave spatial grids, sharpness LUT, color LUTs, bilinear upscale for animation
- Voronoi Weighted: per-row dy² caching, prevBest coherence hint, no-border fast path, sqrt-free power/multiplicative modes
- Voronoi Crackle, Depth, Cells, Contours, Centroidal, Fractured, Mosaic, Neighbor Bands: inline abs, early-exit pruning, eliminated per-pixel allocations

### Improvements
- Added render debounce to StaticCanvas for smoother interaction during rapid parameter changes
- Export panel keyboard actions for video start/end time inputs
- Smarter animation parameter randomization (ensures non-zero speed values)
- Recursive Subdivision minimum depth set to 3

## [1.5.1] - Android

### Improvements
- Rewrote IFS/Barnsley generator: histogram-based rendering, shape modifiers (lean/curl/spread), 1-8 fold symmetry, bounding box pre-pass, divergence checking, log-density tone mapping
- Optimized Kaleidoscope with polar buffer pre-computation
- Optimized Plasma Feedback with coarse grid + bilinear upscale
- Optimized Wave Interference with per-source wave LUTs
- Optimized Fractal Flames with bounding box pre-pass, tone mapping LUT, and sparse rendering
- Optimized L-System with batched path drawing
- Optimized DLA with tighter spawn/kill radii and palette LUT
- Moved Recursive Subdivision from Fractals to Geometry family
- Parameter space expanded to over 9.5 million unique configurations

## [1.5.0] - Android

### New Generators (9)
- Added 9 new procedural generators (new family): Audio Reactive, Displacement, Edge Glow, Feedback Systems, Field Particle, Instanced Geometry, Particle Advection, SDF Raymarch, Warp
- Total generators: 116 across 11 families (up from 107 across 10)

### New Features
- Audio reactivity system for procedural generators with real-time frequency analysis
- Video export with audio support and adjustable start/end times
- Parameter selection buttons replace dropdowns for faster one-tap interaction

### Improvements
- Rewrote DLA generator with circle-jump optimization, rotational symmetry (1-6 fold), drift control, and background glow
- Rewrote Ecosystems generator with multi-species food chain (2-5 species) and 5 visual styles (dots, network, trails, glow, heatmap)
- Enhanced Newton fractal with multiple styles and improved parameter handling
- Enhanced SDF Raymarch with domain warping and new shapes
- Fixed Anisotropic generator anisotropy parameter having no visible effect
- Optimized AttractorTrails generator with fast trig lookup, reduced allocations, and constant-based comparisons

### Bug Fixes
- Fixed Newton generator root discovery stability (finite check for z magnitude)
- Fixed Hilt ViewModel import statement

## [1.4.0] - Android

### New Features
- Progressive reveal animation for Maze/Meander generator — walls and path segments draw incrementally over ~8 seconds, with solution path appearing only after full reveal
- Random color palette generation with golden-angle hue spacing
- Animated "Surprise Me" button with gold shimmer effect

### Performance
- Optimized rendering across all 107 generators by caching parsed palette colors (eliminates hex string parsing per pixel)
- Added `Palette.buildLut()` for pre-computed color lookup tables in pixel-loop generators
- PostFX single-pass processing — grain, vignette, dither, and posterize now apply in one pixel loop instead of four
- Replaced per-pixel `getPixel()` JNI calls with bulk `getPixels()` in blank detection

### Improvements
- Instant canvas transitions when switching generators via Surprise Me
- Milestone progress bar for long renders — shows 25%, 60%, 90% progress after 1 second
- Surprise Me now always picks active animation and feature options (never selects "none")
- Surprise Me avoids extreme parameter values for more balanced results
- Enhanced Voronoi generators with improved noise animation and distance metrics
- Enhanced graph generators with new features and optimizations
- Palette-based color interpolation refactored across generators
- Animation snapshot time capture for accurate exports
- Animation controls with tooltip guidance

## [1.3.4] - Android

### Improvements
- Rewrote 9 plotter generators to fully wire all declared parameters (Hatching, Bezier Ribbon, Contour Topo, Halftone Dots, Phyllotaxis, TSP, Stippling, Meander Maze, Contour Lines)
- Optimized Guilloche animation performance (eliminated GC pressure from Pair allocations, reused Path objects, pre-computed constants)
- Fixed Contour Lines rendering (bitmap self-overwrite bug)
- Fixed Streamlines spiral effect (restored curl-noise field structure)
- Improved Export tab layout for small phone screens
- Touch interaction now enabled by default
- GIF endless loop now enabled by default

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
