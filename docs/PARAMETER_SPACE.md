# Algomodo Parameter Space Analysis

Total number of distinct visual outcomes possible across all generators, given the following constraints:

- **Seed**: excluded (not counted as a variable)
- **NumberParam**: 2 outcomes (ON / OFF)
- **BooleanParam**: 2 outcomes (ON / OFF)
- **SelectParam**: number of declared options
- **TextParam**: excluded (Text family custom characters removed)
- **Image input**: fixed to 1 image (Image family)
- **Palettes**: 10 curated palettes (multiplier on every generator)
- **PostFX**: excluded (grain, vignette, dither, posterize are a separate pipeline)

---

## Grand Total

### 9,437,160 distinct parameter configurations

---

## Results by Family

| Family | Generators | Outcomes | Share |
|--------|-----------|----------|-------|
| Plotter | 14 | 6,931,200 | 73.45% |
| Graphs | 11 | 1,770,240 | 18.76% |
| Voronoi | 11 | 295,040 | 3.13% |
| Noise | 7 | 268,800 | 2.85% |
| Image | 14 | 64,480 | 0.68% |
| Animation | 8 | 38,400 | 0.41% |
| Geometry | 10 | 23,520 | 0.25% |
| Fractals | 10 | 18,800 | 0.20% |
| Text | 5 | 14,560 | 0.15% |
| Cellular | 14 | 12,120 | 0.13% |

---

## Top 10 Generators by Outcome Count

| Rank | Generator | Family | Outcomes |
|------|-----------|--------|----------|
| 1 | plotter-circle-packing | plotter | 2,764,800 |
| 2 | stippling | plotter | 1,228,800 |
| 3 | plotter-scribble-shading | plotter | 983,040 |
| 4 | plotter-tsp | plotter | 983,040 |
| 5 | graph-euler-trails | graphs | 491,520 |
| 6 | graph-knn | graphs | 409,600 |
| 7 | graph-constrained-delaunay | graphs | 204,800 |
| 8 | graph-planar | graphs | 204,800 |
| 9 | plotter-meander-maze | plotter | 184,320 |
| 10 | voronoi-depth | voronoi | 184,320 |

---

## Bottom 10 Generators by Outcome Count

| Rank | Generator | Family | Outcomes |
|------|-----------|--------|----------|
| 95 | fractal-strange-attractor | fractals | 960 |
| 96 | fractal-burning-ship | fractals | 640 |
| 97 | cyclic-ca | cellular | 640 |
| 98 | ising-model | cellular | 480 |
| 99 | elementary-ca | cellular | 480 |
| 100 | forest-fire | cellular | 480 |
| 101 | dla | cellular | 480 |
| 102 | brians-brain | cellular | 480 |
| 103 | percolation | cellular | 480 |
| 104 | game-of-life | cellular | 480 |

---

## Breakdown by Generator

### Animation (8 generators — 38,400 outcomes)

| Generator | Select | Number | Boolean | Formula | Outcomes |
|-----------|--------|--------|---------|---------|----------|
| flow-field-ink | 3×3×3 | 2^6 | — | 64 × 27 × 10 | 17,280 |
| attractor-trails | 5×3 | 2^5 | — | 32 × 15 × 10 | 4,800 |
| wave-interference | 3 | 2^5 | 2 | 64 × 3 × 10 | 1,920 |
| curl-fluid | 3×3 | 2^6 | — | 64 × 9 × 10 | 5,760 |
| flowing-particles | 3×2 | 2^6 | — | 64 × 6 × 10 | 3,840 |
| orbital | 3 | 2^5 | 2 | 64 × 3 × 10 | 1,920 |
| kaleidoscope | 3 | 2^6 | — | 64 × 3 × 10 | 1,920 |
| plasma-feedback | 3 | 2^5 | — | 32 × 3 × 10 | 960 |

### Cellular (14 generators — 12,120 outcomes)

| Generator | Select | Number | Boolean | Formula | Outcomes |
|-----------|--------|--------|---------|---------|----------|
| sandpile | 3×3 | 2^3 | — | 8 × 9 × 10 | 720 |
| reaction-diffusion | 5×3 | 2^5 | — | 32 × 15 × 10 | 4,800 |
| ising-model | 3 | 2^4 | — | 16 × 3 × 10 | 480 |
| elementary-ca | 3 | 2^3 | 2 | 16 × 3 × 10 | 480 |
| forest-fire | 3 | 2^4 | — | 16 × 3 × 10 | 480 |
| cellular-age-trails | 3×2 | 2^4 | — | 16 × 6 × 10 | 960 |
| crystal-growth | 3 | 2^5 | — | 32 × 3 × 10 | 960 |
| dla | 3 | 2^4 | — | 16 × 3 × 10 | 480 |
| brians-brain | 3 | 2^3 | 2 | 16 × 3 × 10 | 480 |
| cyclic-ca | 2×2 | 2^4 | — | 16 × 4 × 10 | 640 |
| percolation | 3 | 2^3 | 2 | 16 × 3 × 10 | 480 |
| game-of-life | 3 | 2^3 | 2 | 16 × 3 × 10 | 480 |
| eden-growth | 3×3 | 2^3 | — | 8 × 9 × 10 | 720 |
| turing-patterns | 3 | 2^5 | — | 32 × 3 × 10 | 960 |

### Fractals (10 generators — 18,800 outcomes)

| Generator | Select | Number | Boolean | Formula | Outcomes |
|-----------|--------|--------|---------|---------|----------|
| mandelbrot | 4 | 2^5 | 2 | 64 × 4 × 10 | 2,560 |
| julia | 4 | 2^5 | — | 32 × 4 × 10 | 1,280 |
| newton | 4×3 | 2^4 | — | 16 × 12 × 10 | 1,920 |
| ifs-barnsley | 5×3 | 2^3 | — | 8 × 15 × 10 | 1,200 |
| recursive-subdivision | 4×3 | 2^3 | 2 | 16 × 12 × 10 | 1,920 |
| fractal-orbit-traps | 2×4 | 2^6 | — | 64 × 8 × 10 | 5,120 |
| fractal-flames | 6 | 2^5 | — | 32 × 6 × 10 | 1,920 |
| fractal-multibrot | — | 2^7 | — | 128 × 1 × 10 | 1,280 |
| fractal-strange-attractor | 4×3 | 2^3 | — | 8 × 12 × 10 | 960 |
| fractal-burning-ship | — | 2^6 | — | 64 × 1 × 10 | 640 |

### Geometry (10 generators — 23,520 outcomes)

| Generator | Select | Number | Boolean | Formula | Outcomes |
|-----------|--------|--------|---------|---------|----------|
| mst-web | 3 | 2^5 | 2 | 64 × 3 × 10 | 1,920 |
| truchet | 4×3 | 2^3 | 2 | 16 × 12 × 10 | 1,920 |
| l-system | 6×3 | 2^4 | — | 16 × 18 × 10 | 2,880 |
| chladni | 3 | 2^5 | — | 32 × 3 × 10 | 960 |
| rosettes | 3 | 2^5 | 2 | 64 × 3 × 10 | 1,920 |
| lissajous | 3 | 2^6 | — | 64 × 3 × 10 | 1,920 |
| spirograph | 3 | 2^5 | — | 32 × 3 × 10 | 960 |
| moire | 3 | 2^5 | — | 32 × 3 × 10 | 960 |
| islamic-patterns | 5×3 | 2^3 | 2 | 16 × 15 × 10 | 2,400 |
| superformula | 3 | 2^8 | — | 256 × 3 × 10 | 7,680 |

### Graphs (11 generators — 1,770,240 outcomes)

| Generator | Select | Number | Boolean | Formula | Outcomes |
|-----------|--------|--------|---------|---------|----------|
| graph-ecosystems | 3 | 2^5 | 2 | 64 × 3 × 10 | 1,920 |
| graph-low-poly | 4 | 2^4 | 2 | 32 × 4 × 10 | 1,280 |
| graph-steiner-networks | 3 | 2^4 | 2 | 32 × 3 × 10 | 960 |
| graph-tessellations | 5×3 | 2^4 | 2 | 32 × 15 × 10 | 4,800 |
| graph-euler-trails | 6×4×4×4 | 2^5 | 2^2 | 128 × 384 × 10 | 491,520 |
| graph-knn | 5×4×4 | 2^7 | 2^2 | 512 × 80 × 10 | 409,600 |
| graph-constrained-delaunay | 5×4×4 | 2^6 | 2^2 | 256 × 80 × 10 | 204,800 |
| graph-planar | 5×4×4 | 2^6 | 2^2 | 256 × 80 × 10 | 204,800 |
| graph-geodesic | 4×2×4 | 2^7 | 2^2 | 512 × 32 × 10 | 163,840 |
| graph-gabriel | 4×4×4 | 2^5 | 2^3 | 256 × 64 × 10 | 163,840 |
| graph-anisotropic | 4×3×4 | 2^6 | 2^2 | 256 × 48 × 10 | 122,880 |

### Image (14 generators — 64,480 outcomes)

Fixed to 1 input image. Image selection is not a variable.

| Generator | Select | Number | Boolean | Formula | Outcomes |
|-----------|--------|--------|---------|---------|----------|
| pixel-sort | 4×6 | 2^4 | 2 | 32 × 24 × 10 | 7,680 |
| data-mosh | 5×3 | 2^3 | 2 | 16 × 15 × 10 | 2,400 |
| lino-cut | 4×3×3 | 2^4 | — | 16 × 36 × 10 | 5,760 |
| glitch-transform | 4 | 2^6 | 2 | 128 × 4 × 10 | 5,120 |
| distance-field | 3 | 2^4 | 2×2 | 64 × 3 × 10 | 1,920 |
| dither-image | 4×3 | 2^3 | 2 | 16 × 12 × 10 | 1,920 |
| edge-detect | 4×3 | 2^3 | 2×2 | 32 × 12 × 10 | 3,840 |
| convolution | 5 | 2^4 | 2×2 | 64 × 5 × 10 | 3,200 |
| optical-flow | 3 | 2^6 | 2 | 128 × 3 × 10 | 3,840 |
| mosaic | 5×4×3 | 2^3 | 2 | 16 × 60 × 10 | 9,600 |
| luma-mesh | 3 | 2^5 | 2×2 | 128 × 3 × 10 | 3,840 |
| halftone | 5×3×3 | 2^3 | 2×2 | 32 × 45 × 10 | 14,400 |
| feedback-loop | 3 | 2^8 | — | 256 × 3 × 10 | 7,680 |
| ascii-art | 5×3 | 2^3 | 2×2 | 32 × 15 × 10 | 4,800 |

### Noise (7 generators — 268,800 outcomes)

| Generator | Select | Number | Boolean | Formula | Outcomes |
|-----------|--------|--------|---------|---------|----------|
| noise-fbm | 2×3 | 2^8 | — | 256 × 6 × 10 | 15,360 |
| noise-turbulence | 3×3 | 2^9 | — | 512 × 9 × 10 | 46,080 |
| noise-ridged | 3×3 | 2^9 | — | 512 × 9 × 10 | 46,080 |
| noise-simplex-field | 3×2×2 | 2^5 | — | 32 × 12 × 10 | 3,840 |
| fbm-terrain | 3×3×2 | 2^9 | — | 512 × 18 × 10 | 92,160 |
| noise-domain-warp | 3×3×2×3 | 2^6 | — | 64 × 54 × 10 | 34,560 |
| domain-warp-marble | 3 | 2^8 | 2×2 | 1024 × 3 × 10 | 30,720 |

### Plotter (14 generators — 6,931,200 outcomes)

| Generator | Select | Number | Boolean | Formula | Outcomes |
|-----------|--------|--------|---------|---------|----------|
| plotter-contour-topo | 3×3 | 2^7 | — | 128 × 9 × 10 | 11,520 |
| plotter-phyllotaxis | 4×4×5×3 | 2^5 | 2 | 64 × 240 × 10 | 153,600 |
| stippling | 5×4×4×3 | 2^9 | — | 512 × 240 × 10 | 1,228,800 |
| plotter-guilloche | 4×4×3 | 2^8 | — | 256 × 48 × 10 | 122,880 |
| plotter-tsp | 4×4×4×3 | 2^8 | 2 | 512 × 192 × 10 | 983,040 |
| plotter-contour-lines | 3×3×2 | 2^6 | 2 | 128 × 18 × 10 | 23,040 |
| plotter-streamlines | 3×3×3 | 2^7 | — | 128 × 27 × 10 | 34,560 |
| plotter-scribble-shading | 4×4×4×3 | 2^8 | 2 | 512 × 192 × 10 | 983,040 |
| plotter-circle-packing | 4×4×3×5×3×3 | 2^7 | — | 128 × 2160 × 10 | 2,764,800 |
| plotter-offset-paths | 6×3×3 | 2^7 | 2 | 256 × 54 × 10 | 138,240 |
| plotter-bezier-ribbon-weaves | 3×3×4×3 | 2^5 | — | 32 × 108 × 10 | 34,560 |
| plotter-halftone-dots | 3×4×4×3 | 2^6 | — | 64 × 144 × 10 | 92,160 |
| hatching | 5×3 | 2^9 | 2 | 1024 × 15 × 10 | 153,600 |
| plotter-meander-maze | 2×4×3×4×3 | 2^4 | 2×2 | 64 × 288 × 10 | 184,320 |

### Text (5 generators — 14,560 outcomes)

Custom text (TextParam) excluded from all generators.

| Generator | Select | Number | Boolean | Formula | Outcomes |
|-----------|--------|--------|---------|---------|----------|
| text-grid | 5×3 | 2^4 | — | 16 × 15 × 10 | 2,400 |
| text-matrix | 5 | 2^5 | — | 32 × 5 × 10 | 1,600 |
| text-concrete | 4×5 | 2^4 | — | 16 × 20 × 10 | 3,200 |
| text-poem | 4×4 | 2^4 | — | 16 × 16 × 10 | 2,560 |
| text-rewrite | 5×4×3 | 2^3 | — | 8 × 60 × 10 | 4,800 |

### Voronoi (11 generators — 295,040 outcomes)

| Generator | Select | Number | Boolean | Formula | Outcomes |
|-----------|--------|--------|---------|---------|----------|
| delaunay-mesh | 4 | 2^6 | 2 | 128 × 4 × 10 | 5,120 |
| voronoi-contours | 3×3×3 | 2^5 | 2 | 64 × 27 × 10 | 17,280 |
| voronoi-crackle | 4×4×3 | 2^4 | — | 16 × 48 × 10 | 7,680 |
| voronoi-depth | 3×3 | 2^11 | — | 2048 × 9 × 10 | 184,320 |
| voronoi-ridges | 3×3 | 2^7 | — | 128 × 9 × 10 | 11,520 |
| voronoi-weighted | 3×3×3 | 2^5 | — | 32 × 27 × 10 | 8,640 |
| voronoi-mosaic | 4×3×3×3 | 2^4 | 2 | 32 × 108 × 10 | 34,560 |
| voronoi-neighbor-bands | 3×3 | 2^5 | 2 | 64 × 9 × 10 | 5,760 |
| centroidal-voronoi | 3×3 | 2^5 | 2 | 64 × 9 × 10 | 5,760 |
| voronoi-fractured | 3×3 | 2^7 | — | 128 × 9 × 10 | 11,520 |
| voronoi-cells | 3×3 | 2^4 | 2 | 32 × 9 × 10 | 2,880 |

---

## Methodology

Each generator's parameter space is computed as:

```
outcomes = (product of all SelectParam option counts)
         × (2 ^ number_of_NumberParams)
         × (2 ^ number_of_BooleanParams)
         × 10 palettes
```

The grand total is the **sum** across all 104 generators, since each generator is a distinct algorithm producing a fundamentally different class of output. Choosing a generator is itself a branch in the outcome tree.

### What is excluded

| Factor | Reason |
|--------|--------|
| Seed | Removed per user specification |
| PostFX (grain, vignette, dither, posterize) | Separate rendering pipeline, not generator params |
| Custom text (TextParam) | Removed from Text family per user specification |
| Image selection | Fixed to 1 image per user specification |
| Canvas resolution / aspect ratio | Runtime setting, not a generator parameter |

### What would change the number

| If you added... | Multiplier |
|-----------------|-----------|
| PostFX (4 effects × ON/OFF) | ×16 → ~151.0M |
| Seed (assume 2^32 range) | ×4,294,967,296 → ~40.5 quadrillion |
| Continuous NumberParam range (100 steps) | Astronomically large |
