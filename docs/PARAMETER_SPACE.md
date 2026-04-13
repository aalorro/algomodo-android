# Algomodo Parameter Space Analysis

Total number of distinct visual outcomes possible across all generators, given the following constraints:

- **Seed**: excluded (not counted as a variable)
- **NumberParam**: 2 outcomes (ON / OFF)
- **BooleanParam**: 2 outcomes (ON / OFF)
- **SelectParam**: number of declared options
- **ColorParam**: 2 outcomes (treated analogously to NumberParam)
- **TextParam**: excluded (Text family custom characters removed)
- **Internal parameters** (keys starting with `_`, e.g. `_audioAnalysis`): excluded
- **Image input**: fixed to 1 image (Image family)
- **Palettes**: 14 curated palettes (multiplier on every generator). User-created custom palettes (up to 5) are excluded.
- **PostFX**: excluded (grain, vignette, dither, posterize are a separate pipeline)

---

## Grand Total

### 301,148,288 distinct parameter configurations

---

## Results by Family

| Family | Generators | Outcomes | Share |
|--------|-----------|----------|-------|
| Plotter | 14 | 257,924,352 | 85.65% |
| Geometry | 12 | 33,744,480 | 11.21% |
| Graphs | 11 | 4,379,648 | 1.45% |
| Cellular | 14 | 2,915,136 | 0.97% |
| Fractals | 10 | 661,248 | 0.22% |
| Voronoi | 11 | 413,056 | 0.14% |
| Noise | 7 | 384,384 | 0.13% |
| Animation | 8 | 277,312 | 0.09% |
| Procedural | 9 | 159,488 | 0.05% |
| Image | 14 | 144,704 | 0.05% |
| Text | 8 | 144,480 | 0.05% |

---

## Top 10 Generators by Outcome Count

| Rank | Generator | Family | Outcomes |
|------|-----------|--------|----------|
| 1 | plotter-circle-packing | plotter | 247,726,080 |
| 2 | geo-superformula | geometry | 33,030,144 |
| 3 | plotter-phyllotaxis | plotter | 3,440,640 |
| 4 | cellular-dla | cellular | 1,720,320 |
| 5 | stippling | plotter | 1,720,320 |
| 6 | plotter-scribble-shading | plotter | 1,376,256 |
| 7 | plotter-tsp | plotter | 1,376,256 |
| 8 | graph-steiner-networks | graphs | 860,160 |
| 9 | plotter-meander-maze | plotter | 774,144 |
| 10 | graph-tessellations | graphs | 716,800 |

---

## Bottom 10 Generators by Outcome Count

| Rank | Generator | Family | Outcomes |
|------|-----------|--------|----------|
| 109 | fractal-mandelbrot | fractals | 2,688 |
| 110 | distance-field | image | 2,688 |
| 111 | fractal-recursive-subdivision | geometry | 2,016 |
| 112 | cellular-eden-growth | cellular | 1,792 |
| 113 | fractal-multibrot | fractals | 1,792 |
| 114 | fractal-strange-attractor | fractals | 1,344 |
| 115 | cellular-cyclic-ca | cellular | 896 |
| 116 | cellular-forest-fire | cellular | 896 |
| 117 | fractal-burning-ship | fractals | 896 |
| 118 | cellular-brians-brain | cellular | 448 |

---

## Breakdown by Generator

### Animation (8 generators — 277,312 outcomes)

| Generator | Select | Number | Boolean | Formula | Outcomes |
|-----------|--------|--------|---------|---------|----------|
| orbital | 3 | 2^12 | — | 4096 × 3 × 14 | 172,032 |
| flow-field-ink | 4×3 | 2^8 | — | 256 × 12 × 14 | 43,008 |
| wave-interference | 4×3 | 2^7 | — | 128 × 12 × 14 | 21,504 |
| attractor-trails | 5×4 | 2^6 | — | 64 × 20 × 14 | 17,920 |
| flowing-particles | 5 | 2^7 | — | 128 × 5 × 14 | 8,960 |
| plasma-feedback | 4 | 2^7 | — | 128 × 4 × 14 | 7,168 |
| kaleidoscope | 3×3 | 2^5 | — | 32 × 9 × 14 | 4,032 |
| curl-fluid | 3 | 2^6 | — | 64 × 3 × 14 | 2,688 |

### Cellular (14 generators — 2,915,136 outcomes)

| Generator | Select | Number | Boolean | Formula | Outcomes |
|-----------|--------|--------|---------|---------|----------|
| cellular-dla | 4×6×5 | 2^9 | 2 | 1024 × 120 × 14 | 1,720,320 |
| cellular-turing-patterns | 2×2×2 | 2^12 | — | 4096 × 8 × 14 | 458,752 |
| reaction-diffusion | 9×3×4 | 2^8 | — | 256 × 108 × 14 | 387,072 |
| cellular-crystal-growth | 4×4 | 2^10 | — | 1024 × 16 × 14 | 229,376 |
| game-of-life | 6×7 | 2^5 | 2 | 64 × 42 × 14 | 37,632 |
| cellular-elementary-ca | 4×2×3×3 | 2^5 | — | 32 × 72 × 14 | 32,256 |
| cellular-percolation | 2×2×3 | 2^7 | — | 128 × 12 × 14 | 21,504 |
| cellular-age-trails | 5×2 | 2^7 | — | 128 × 10 × 14 | 17,920 |
| cellular-ising-model | 4×2 | 2^5 | — | 32 × 8 × 14 | 3,584 |
| cellular-sandpile | 3×4 | 2^4 | — | 16 × 12 × 14 | 2,688 |
| cellular-eden-growth | 2×4 | 2^4 | — | 16 × 8 × 14 | 1,792 |
| cellular-cyclic-ca | 2 | 2^5 | — | 32 × 2 × 14 | 896 |
| cellular-forest-fire | 2 | 2^5 | — | 32 × 2 × 14 | 896 |
| cellular-brians-brain | 2 | 2^4 | — | 16 × 2 × 14 | 448 |

### Fractals (10 generators — 661,248 outcomes)

| Generator | Select | Number | Boolean | Formula | Outcomes |
|-----------|--------|--------|---------|---------|----------|
| fractal-pythagoras-tree | 5×5×3×4 | 2^7 | — | 128 × 300 × 14 | 537,600 |
| fractal-ifs-barnsley | 9×3 | 2^8 | — | 256 × 27 × 14 | 96,768 |
| fractal-orbit-traps | 2×4 | 2^6 | — | 64 × 8 × 14 | 7,168 |
| fractal-newton | 5×3 | 2^5 | — | 32 × 15 × 14 | 6,720 |
| fractal-julia | 2 | 2^7 | — | 128 × 2 × 14 | 3,584 |
| fractal-flames | 6 | 2^5 | — | 32 × 6 × 14 | 2,688 |
| fractal-mandelbrot | 3 | 2^6 | — | 64 × 3 × 14 | 2,688 |
| fractal-multibrot | — | 2^7 | — | 128 × 1 × 14 | 1,792 |
| fractal-strange-attractor | 4×3 | 2^3 | — | 8 × 12 × 14 | 1,344 |
| fractal-burning-ship | — | 2^6 | — | 64 × 1 × 14 | 896 |

### Geometry (12 generators — 33,744,480 outcomes)

| Generator | Select | Number | Boolean | Formula | Outcomes |
|-----------|--------|--------|---------|---------|----------|
| geo-superformula | 3×2×3 | 2^16 | 2 | 131072 × 18 × 14 | 33,030,144 |
| geo-islamic | 3×4×5 | 2^6 | 2^3 | 512 × 60 × 14 | 430,080 |
| lsystem | 7×3×3 | 2^6 | 2 | 128 × 63 × 14 | 112,896 |
| geo-harmonics | 3 | 2^9 | 2 | 1024 × 3 × 14 | 43,008 |
| mst-web | 5×4×2 | 2^6 | — | 64 × 40 × 14 | 35,840 |
| geo-truchet | 4×4×3×3 | 2^4 | — | 16 × 144 × 14 | 32,256 |
| spirograph | 2×2×2 | 2^8 | — | 256 × 8 × 14 | 28,672 |
| geo-rosettes | 3 | 2^8 | — | 256 × 3 × 14 | 10,752 |
| geo-moire | 4×3×3 | 2^4 | — | 16 × 36 × 14 | 8,064 |
| chladni | 4×4 | 2^5 | — | 32 × 16 × 14 | 7,168 |
| lissajous | — | 2^8 | — | 256 × 1 × 14 | 3,584 |
| fractal-recursive-subdivision | 3×3 | 2^4 | — | 16 × 9 × 14 | 2,016 |

### Graphs (11 generators — 4,379,648 outcomes)

| Generator | Select | Number | Boolean | Formula | Outcomes |
|-----------|--------|--------|---------|---------|----------|
| graph-steiner-networks | 5×4×3×4 | 2^6 | 2^2 | 256 × 240 × 14 | 860,160 |
| graph-tessellations | 5×4×5×4×4 | 2^5 | — | 32 × 1600 × 14 | 716,800 |
| graph-euler-trails | 6×4×4×4 | 2^5 | 2^2 | 128 × 384 × 14 | 688,128 |
| graph-knn | 5×4×4 | 2^7 | 2^2 | 512 × 80 × 14 | 573,440 |
| graph-constrained-delaunay | 5×4×4 | 2^6 | 2^2 | 256 × 80 × 14 | 286,720 |
| graph-planar | 5×4×4 | 2^6 | 2^2 | 256 × 80 × 14 | 286,720 |
| graph-gabriel | 4×4×4 | 2^5 | 2^3 | 256 × 64 × 14 | 229,376 |
| graph-geodesic | 4×2×4 | 2^7 | 2^2 | 512 × 32 × 14 | 229,376 |
| graph-low-poly | 4×4 | 2^9 | 2 | 1024 × 16 × 14 | 229,376 |
| graph-anisotropic | 4×3×4 | 2^6 | 2^2 | 256 × 48 × 14 | 172,032 |
| graph-ecosystems | 5×3 | 2^9 | — | 512 × 15 × 14 | 107,520 |

### Image (14 generators — 144,704 outcomes)

Fixed to 1 input image. Image selection is not a variable.

| Generator | Select | Number | Boolean | Formula | Outcomes |
|-----------|--------|--------|---------|---------|----------|
| mosaic | 5×4×3 | 2^4 | 2 | 32 × 60 × 14 | 26,880 |
| pixel-sort | 4×6 | 2^5 | 2 | 64 × 24 × 14 | 21,504 |
| halftone | 5×3×3 | 2^3 | 2^2 | 32 × 45 × 14 | 20,160 |
| lino-cut | 4×3×3 | 2^5 | — | 32 × 36 × 14 | 16,128 |
| feedback-loop | 3 | 2^8 | — | 256 × 3 × 14 | 10,752 |
| glitch-transform | 4 | 2^6 | 2 | 128 × 4 × 14 | 7,168 |
| ascii-art | 5×3 | 2^3 | 2^2 | 32 × 15 × 14 | 6,720 |
| data-mosh | 5×3 | 2^4 | 2 | 32 × 15 × 14 | 6,720 |
| dither-image | 4×3 | 2^4 | 2 | 32 × 12 × 14 | 5,376 |
| edge-detect | 4×3 | 2^3 | 2^2 | 32 × 12 × 14 | 5,376 |
| luma-mesh | 3 | 2^5 | 2^2 | 128 × 3 × 14 | 5,376 |
| optical-flow | 3 | 2^6 | 2 | 128 × 3 × 14 | 5,376 |
| convolution | 5 | 2^4 | 2^2 | 64 × 5 × 14 | 4,480 |
| distance-field | 3 | 2^4 | 2^2 | 64 × 3 × 14 | 2,688 |

### Noise (7 generators — 384,384 outcomes)

| Generator | Select | Number | Boolean | Formula | Outcomes |
|-----------|--------|--------|---------|---------|----------|
| fbm-terrain | 3×3×2 | 2^9 | — | 512 × 18 × 14 | 129,024 |
| noise-ridged | 3×3 | 2^9 | — | 512 × 9 × 14 | 64,512 |
| noise-turbulence | 3×3 | 2^9 | — | 512 × 9 × 14 | 64,512 |
| noise-domain-warp | 3×3×2×3 | 2^6 | — | 64 × 54 × 14 | 48,384 |
| domain-warp-marble | 3 | 2^8 | 2^2 | 1024 × 3 × 14 | 43,008 |
| noise-fbm | 2×3 | 2^8 | — | 256 × 6 × 14 | 21,504 |
| noise-simplex-field | 5×2×3 | 2^5 | — | 32 × 30 × 14 | 13,440 |

### Plotter (14 generators — 257,924,352 outcomes)

| Generator | Select | Number | Boolean | Formula | Outcomes |
|-----------|--------|--------|---------|---------|----------|
| plotter-circle-packing | 4×4×3×5×3×3×4 | 2^11 | — | 2048 × 8640 × 14 | 247,726,080 |
| plotter-phyllotaxis | 4×4×5×3 | 2^9 | 2 | 1024 × 240 × 14 | 3,440,640 |
| stippling | 5×4×4×3 | 2^9 | — | 512 × 240 × 14 | 1,720,320 |
| plotter-scribble-shading | 4×4×4×3 | 2^8 | 2 | 512 × 192 × 14 | 1,376,256 |
| plotter-tsp | 4×4×4×3 | 2^8 | 2 | 512 × 192 × 14 | 1,376,256 |
| plotter-meander-maze | 2×4×3×4×3×3 | 2^4 | 2^2 | 64 × 864 × 14 | 774,144 |
| plotter-bezier-ribbon-weaves | 5×5×5×3 | 2^7 | — | 128 × 375 × 14 | 672,000 |
| hatching | 5×3 | 2^9 | 2 | 1024 × 15 × 14 | 215,040 |
| plotter-offset-paths | 6×3×3 | 2^7 | 2 | 256 × 54 × 14 | 193,536 |
| plotter-guilloche | 4×4×3 | 2^8 | — | 256 × 48 × 14 | 172,032 |
| plotter-halftone-dots | 3×4×4×3 | 2^6 | — | 64 × 144 × 14 | 129,024 |
| plotter-contour-lines | 3×3×2 | 2^7 | 2 | 256 × 18 × 14 | 64,512 |
| plotter-streamlines | 3×3×3 | 2^7 | — | 128 × 27 × 14 | 48,384 |
| plotter-contour-topo | 3×3 | 2^7 | — | 128 × 9 × 14 | 16,128 |

### Procedural (9 generators — 159,488 outcomes)

| Generator | Select | Number | Boolean | Formula | Outcomes |
|-----------|--------|--------|---------|---------|----------|
| procedural-particle-advection | 4×4 | 2^8 | — | 256 × 16 × 14 | 57,344 |
| procedural-instanced-geometry | 5×4×3 | 2^5 | — | 32 × 60 × 14 | 26,880 |
| procedural-sdf-raymarch | 6 | 2^8 | — | 256 × 6 × 14 | 21,504 |
| procedural-edge-glow | 4 | 2^8 | — | 256 × 4 × 14 | 14,336 |
| procedural-field-particle | 4×4 | 2^6 | — | 64 × 16 × 14 | 14,336 |
| procedural-audio-reactive | 8 | 2^5 | 2 | 64 × 8 × 14 | 7,168 |
| procedural-displacement | 4 | 2^7 | — | 128 × 4 × 14 | 7,168 |
| warp | 4 | 2^7 | — | 128 × 4 × 14 | 7,168 |
| procedural-feedback-systems | 4 | 2^6 | — | 64 × 4 × 14 | 3,584 |

### Text (8 generators — 144,480 outcomes)

Custom text (TextParam) excluded from all generators.

| Generator | Select | Number | Boolean | Formula | Outcomes |
|-----------|--------|--------|---------|---------|----------|
| text-glyphs | 4×4×4 | 2^5 | 2 | 64 × 64 × 14 | 57,344 |
| text-procedural-cursive | 3×4×4 | 2^5 | 2 | 64 × 48 × 14 | 43,008 |
| text-naive-handwriting | 3×4×4 | 2^4 | 2 | 32 × 48 × 14 | 21,504 |
| text-rewrite | 5×4×3 | 2^3 | — | 8 × 60 × 14 | 6,720 |
| text-concrete | 4×5 | 2^4 | — | 16 × 20 × 14 | 4,480 |
| text-matrix | 5 | 2^6 | — | 64 × 5 × 14 | 4,480 |
| text-poem | 4×4 | 2^4 | — | 16 × 16 × 14 | 3,584 |
| text-grid | 5×3 | 2^4 | — | 16 × 15 × 14 | 3,360 |

### Voronoi (11 generators — 413,056 outcomes)

| Generator | Select | Number | Boolean | Formula | Outcomes |
|-----------|--------|--------|---------|---------|----------|
| voronoi-depth | 3×3 | 2^11 | — | 2048 × 9 × 14 | 258,048 |
| voronoi-mosaic | 4×3×3×3 | 2^4 | 2 | 32 × 108 × 14 | 48,384 |
| voronoi-contours | 3×3×3 | 2^5 | 2 | 64 × 27 × 14 | 24,192 |
| voronoi-fractured | 3×3 | 2^7 | — | 128 × 9 × 14 | 16,128 |
| voronoi-ridges | 3×3 | 2^7 | — | 128 × 9 × 14 | 16,128 |
| voronoi-weighted | 3×3×3 | 2^5 | — | 32 × 27 × 14 | 12,096 |
| voronoi-crackle | 4×4×3 | 2^4 | — | 16 × 48 × 14 | 10,752 |
| centroidal-voronoi | 3×3 | 2^5 | 2 | 64 × 9 × 14 | 8,064 |
| voronoi-neighbor-bands | 3×3 | 2^5 | 2 | 64 × 9 × 14 | 8,064 |
| delaunay-mesh | 4 | 2^6 | 2 | 128 × 4 × 14 | 7,168 |
| voronoi-cells | 3×3 | 2^4 | 2 | 32 × 9 × 14 | 4,032 |

---

## Methodology

Each generator's parameter space is computed as:

```
outcomes = (product of all SelectParam option counts)
         × (2 ^ number_of_NumberParams)
         × (2 ^ number_of_BooleanParams)
         × (2 ^ number_of_ColorParams)
         × 14 palettes
```

The grand total is the **sum** across all 118 generators, since each generator is a distinct algorithm producing a fundamentally different class of output. Choosing a generator is itself a branch in the outcome tree.

ColorParams are treated analogously to NumberParams (2 outcomes each — ON/OFF). Parameters whose key begins with an underscore (e.g. `_audioAnalysis`) are internal-only and excluded. TextParams are also excluded per existing methodology.

### What is excluded

| Factor | Reason |
|--------|--------|
| Seed | Removed per user specification |
| PostFX (grain, vignette, dither, posterize) | Separate rendering pipeline, not generator params |
| Custom text (TextParam) | Removed from Text family per user specification |
| Custom user palettes (up to 5) | User-created, not part of the curated baseline |
| Internal params (`_audioAnalysis`, etc.) | Injected by the runtime, not user-facing |
| Image selection | Fixed to 1 image per user specification |
| Canvas resolution / aspect ratio | Runtime setting, not a generator parameter |

### What would change the number

| If you added... | Multiplier |
|-----------------|-----------|
| PostFX (4 effects × ON/OFF) | ×16 |
| Seed (assume 2^32 range) | ×4,294,967,296 |
| Continuous NumberParam range (100 steps) | Astronomically large |
