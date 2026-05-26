# Algomodo

**Algorithmic Art Generator for Android**

Create stunning algorithmic art using pure mathematics — noise functions, cellular automata, fractals, Voronoi diagrams, geometry, graph theory, image processing, and procedural systems. 100% local, no servers, no tracking.

## Features

- **177 generators** across 15 families (animation, cellular automata, flux, fractals, geometry, graphs, image processing, noise, physics, pixel art, plotter, procedural, shader, text, Voronoi)
- **Over 9.5 million unique parameter configurations** — before accounting for seeds, which multiply the space into the trillions
- **Deterministic rendering** — same seed and parameters always produce the same output
- **Animation support** — play, export as GIF or MP4
- **Image processing** — load photos from gallery/camera and apply generative effects
- **22 curated color palettes** with gradient interpolation, plus up to 5 user-created custom palettes
- **Post-processing effects** — grain, vignette, dither, posterize
- **Export** — PNG, JPG, SVG, GIF, MP4
- **Presets** — save, load, and share configurations
- **Recipe system** — JSON-based sharing of complete render configs
- **Touch gestures** — tap to expand canvas, swipe to randomize/undo/surprise
- **Undo/redo** — 50-step history
- **Dark/light theme** — Material 3 design
- **Fully offline** — all processing happens on device

## Generator Families

| Family | Count | Examples |
|--------|-------|---------|
| Animation | 9 | Attractor Trails, Curl Fluid, Kaleidoscope, Plasma Feedback, Sprott Quadratic |
| Cellular Automata | 14 | Game of Life, Reaction Diffusion, Turing Patterns, DLA |
| Flux | 12 | Perlin Flow, Simplex Warp, Feedback Loop, Pixel Sort Feedback, Metaballs 2D, Wireframe Terrain |
| Fractals | 10 | Mandelbrot, Julia, Burning Ship, Fractal Flames, Multibrot, Orbit Traps, Pythagoras Tree, Strange Attractors |
| Geometry | 15 | Islamic Patterns, L-System, Spirograph, Truchet Tiles, Harmonics, Gravity Packing, Rubik's Permutation |
| Graphs | 11 | Geodesic, Constrained Delaunay, Anisotropic, Euler Trails, K-Nearest Neighbor, Gabriel, Planar |
| Image Processing | 14 | Pixel Sort, Halftone, ASCII Art, Glitch Transform |
| Noise | 7 | Simplex Field, FBM Terrain, Domain Warp, Ridged Noise |
| Physics | 12 | Boids, Cloth Sim, Fluid Dynamics, N-Body, Electric/Magnetic Field, Pendulum Systems, Spring Networks, Wave Propagation |
| Pixel Art | 12 | Pixel Voronoi, Pixel Maze, Pixel Terrain, Pixel City, Pixel Portraits, Pixel Flow Field |
| Plotter | 15 | Circle Packing, Stippling, Contour Lines, Guilloche |
| Procedural | 9 | Audio Reactive, SDF Raymarch, Particle Advection, Feedback Systems, Warp |
| Shader | 13 | Mandelbulb, Apollonian Spheres, Caustic Pool, Crystal Cavern, Geodesic, Glass Garden, Gray-Scott 3D, Tunnel Vision, SDF Sculpt |
| Text | 9 | Concrete Poetry, Matrix, Glyphs, Naive Handwriting, Procedural Cursive, Poem Layout, Text Code |
| Voronoi | 11 | Voronoi Cells, Delaunay Mesh, Crackle, Weighted Voronoi |

## How It Works

**Seed** — A number that drives all randomness. Same seed + same parameters = identical output every time.

**Core controls:**
- **Surprise Me** — random generator, parameters, palette, and seed
- **Rand** — randomize parameters and seed for the current generator
- **Reload** — re-render with current settings (useful after changing post-FX)

**Tabs:**
1. **Generators** — browse and select from 177 generators across 15 families
2. **Params** — adjust parameters, lock individual params to preserve during randomization
3. **Export** — save images, export/import recipes
4. **Settings** — theme, quality, FPS, post-processing

## Building

Requires Android Studio with SDK 36 and JDK 17.

```bash
# Debug build
./gradlew assembleDebug

# Release build
./gradlew assembleRelease

# Run tests
./gradlew test
```

## Tech Stack

- Kotlin 2.2.10
- Jetpack Compose with Material 3
- Hilt for dependency injection
- Room for preset storage
- DataStore for preferences
- KSP for annotation processing
- Coil for image loading
- Kotlin Serialization for recipes

## Requirements

- Android 8.0+ (API 26)
- Camera permission (optional, for image generators)

## Privacy

All data stays on your device. No analytics, no tracking, no external servers. Source images are processed in memory and never stored or uploaded.

## License

See [LICENSE](LICENSE) for details.

## Links

- Website: [algomodo.work](https://algomodo.work)
- GitHub: [github.com/aalorro/algomodo](https://github.com/aalorro/algomodo)
