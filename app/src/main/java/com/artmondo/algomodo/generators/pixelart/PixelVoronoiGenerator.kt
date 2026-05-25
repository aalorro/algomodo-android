package com.artmondo.algomodo.generators.pixelart

import android.opengl.GLES30
import com.artmondo.algomodo.core.rng.SeededRNG
import com.artmondo.algomodo.data.palettes.Palette
import com.artmondo.algomodo.generators.GpuGenerator
import com.artmondo.algomodo.generators.ParamGroup
import com.artmondo.algomodo.generators.Parameter
import com.artmondo.algomodo.generators.Quality
import com.artmondo.algomodo.rendering.gl.PaletteUniform

/**
 * GPU-rendered pixel-art Voronoi. Up to 64 seed points are scattered on a
 * low-resolution grid and animated by bouncing off the edges; each grid cell
 * takes the colour of its nearest seed under the chosen metric (Euclidean /
 * Manhattan / Chebyshev). Optional border cells where the two closest seeds
 * are nearly equidistant.
 *
 * Ported from the original CPU implementation; id, parameter schema and
 * defaults are preserved. The bouncing animation state is held in a
 * companion cache keyed by (seed, gridSize, numSeeds) and advanced in
 * `bindUniforms` with the same per-frame `dt` as the CPU path.
 */
class PixelVoronoiGenerator : GpuGenerator {

    override val id = "pixel-voronoi"
    override val family = "pixel-art"
    override val styleName = "Pixel Voronoi"
    override val definition =
        "Voronoi diagram on a coarse pixel grid \u2014 a chunky stained-glass or territory-map look with optional cell borders."
    override val algorithmNotes =
        "Per-pixel fragment shader on the GPU. Up to 64 seeds (positions packed in a uniform array) " +
        "are evaluated per fragment under Euclidean / Manhattan / Chebyshev metrics; the cell takes " +
        "the colour of its nearest seed. Borders are drawn where the two closest distances are nearly " +
        "equal. Seeds bounce off the edges, advanced per-frame CPU-side."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema = listOf(
        Parameter.NumberParam("Number of Seeds", "numSeeds", ParamGroup.COMPOSITION,
            "Number of Voronoi seed points", 4f, 64f, 1f, 12f),
        Parameter.NumberParam("Grid Size", "gridSize", ParamGroup.GEOMETRY,
            "Pixel grid resolution", 32f, 128f, 8f, 64f),
        Parameter.BooleanParam("Show Borders", "showBorders", ParamGroup.GEOMETRY,
            "Draw cell borders in a darker shade", true),
        Parameter.NumberParam("Border Width", "borderWidth", ParamGroup.GEOMETRY,
            "Thickness of cell borders", 1f, 4f, 1f, 1f),
        Parameter.SelectParam("Distance Metric", "metric", ParamGroup.COMPOSITION,
            "Distance function for nearest-seed lookup",
            listOf("euclidean", "manhattan", "chebyshev"), "euclidean"),
        Parameter.NumberParam("Drift Amount", "driftAmount", ParamGroup.FLOW_MOTION,
            "How much seeds move during animation", 0f, 5f, 0.5f, 1f),
        Parameter.NumberParam("Anim Speed", "animSpeed", ParamGroup.FLOW_MOTION,
            "Animation speed multiplier", 0.1f, 3f, 0.1f, 1f),
        Parameter.NumberParam("Reactivity", "reactivity", ParamGroup.FLOW_MOTION,
            "Audio reactivity strength", 0f, 2f, 0.1f, 0f)
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "numSeeds" to 12f, "gridSize" to 64f, "showBorders" to true, "borderWidth" to 1f,
        "metric" to "euclidean", "driftAmount" to 1f, "animSpeed" to 1f, "reactivity" to 0f
    )

    companion object {
        private const val MAX_SEEDS = 64

        @Volatile private var animKey: String = ""
        private var sx: DoubleArray = DoubleArray(0)
        private var sy: DoubleArray = DoubleArray(0)
        private var vx: DoubleArray = DoubleArray(0)
        private var vy: DoubleArray = DoubleArray(0)
    }

    override fun bindUniforms(
        programId: Int,
        params: Map<String, Any>,
        seed: Int,
        palette: Palette,
        quality: Quality,
        time: Float,
        width: Int,
        height: Int
    ) {
        val sz = ((params["gridSize"] as? Number)?.toInt() ?: 64).coerceIn(16, 128)
        val numSeeds = ((params["numSeeds"] as? Number)?.toInt() ?: 12).coerceIn(2, MAX_SEEDS)
        val metric = (params["metric"] as? String) ?: "euclidean"
        val showBorders = (params["showBorders"] as? Boolean) ?: true
        val borderWidth = ((params["borderWidth"] as? Number)?.toInt() ?: 1).coerceIn(1, 4)
        val drift = (params["driftAmount"] as? Number)?.toFloat() ?: 1f
        val speed = (params["animSpeed"] as? Number)?.toFloat() ?: 1f

        // Resolve seed positions (matches CPU companion-cache animation model).
        val key = "$seed|$sz|$numSeeds"
        val posX: DoubleArray
        val posY: DoubleArray
        if (time == 0f) {
            val rng = SeededRNG(seed)
            posX = DoubleArray(numSeeds); posY = DoubleArray(numSeeds)
            for (i in 0 until numSeeds) {
                posX[i] = rng.random().toDouble() * sz
                posY[i] = rng.random().toDouble() * sz
                // discard velocity bits to keep RNG sequence aligned with CPU
                rng.random(); rng.random()
            }
        } else {
            synchronized(Companion) {
                if (animKey != key || sx.size != numSeeds) {
                    val rng = SeededRNG(seed)
                    sx = DoubleArray(numSeeds); sy = DoubleArray(numSeeds)
                    vx = DoubleArray(numSeeds); vy = DoubleArray(numSeeds)
                    for (i in 0 until numSeeds) {
                        sx[i] = rng.random().toDouble() * sz
                        sy[i] = rng.random().toDouble() * sz
                        vx[i] = (rng.random() - 0.5).toDouble() * 2.0
                        vy[i] = (rng.random() - 0.5).toDouble() * 2.0
                    }
                    animKey = key
                }
                val dt = speed * 0.016 * drift
                for (i in 0 until numSeeds) {
                    sx[i] += vx[i] * dt
                    sy[i] += vy[i] * dt
                    if (sx[i] < 0) { sx[i] = 0.0; vx[i] *= -1 }
                    if (sx[i] >= sz) { sx[i] = sz - 0.01; vx[i] *= -1 }
                    if (sy[i] < 0) { sy[i] = 0.0; vy[i] *= -1 }
                    if (sy[i] >= sz) { sy[i] = sz - 0.01; vy[i] *= -1 }
                }
                posX = sx.copyOf(); posY = sy.copyOf()
            }
        }

        // Pack into vec2[MAX_SEEDS] float array (xy interleaved).
        val packed = FloatArray(MAX_SEEDS * 2)
        for (i in 0 until numSeeds) {
            packed[i * 2]     = posX[i].toFloat()
            packed[i * 2 + 1] = posY[i].toFloat()
        }

        val metricId = when (metric) {
            "manhattan" -> 1
            "chebyshev" -> 2
            else -> 0
        }
        val ncPalette = palette.colors.size.coerceAtMost(5)
        val borderThreshold = if (metric == "euclidean") (borderWidth * borderWidth * 4f) else (borderWidth * 2f)

        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uGrid"), sz)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uNumSeeds"), numSeeds)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uMetric"), metricId)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uShowBorders"), if (showBorders) 1 else 0)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uBorderThresh"), borderThreshold)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uPaletteCount"), ncPalette)
        GLES30.glUniform2fv(GLES30.glGetUniformLocation(programId, "uSeeds"), MAX_SEEDS, packed, 0)
    }

    override fun fragmentShaderSource(): String = """#version 300 es
        precision highp float;

        uniform vec2 uResolution;
        uniform float uTime;
        uniform vec3 uAudio;
        ${PaletteUniform.GLSL_HELPERS}

        uniform int   uGrid;
        uniform int   uNumSeeds;
        uniform int   uMetric;       // 0 euclidean (squared), 1 manhattan, 2 chebyshev
        uniform int   uShowBorders;
        uniform float uBorderThresh;
        uniform int   uPaletteCount;
        uniform vec2  uSeeds[64];

        out vec4 fragColor;

        float seedDist(vec2 p, vec2 s) {
            vec2 d = p - s;
            if (uMetric == 1) return abs(d.x) + abs(d.y);
            if (uMetric == 2) return max(abs(d.x), abs(d.y));
            return d.x * d.x + d.y * d.y;     // euclidean squared, matches CPU
        }

        void main() {
            int sz = uGrid;
            vec2 cell = floor(gl_FragCoord.xy / uResolution * float(sz));
            vec2 p = cell;

            float minD = 1.0e20;
            float minD2 = 1.0e20;
            int nearest = 0;
            for (int i = 0; i < 64; i++) {
                if (i >= uNumSeeds) break;
                float dd = seedDist(p, uSeeds[i]);
                if (dd < minD) { minD2 = minD; minD = dd; nearest = i; }
                else if (dd < minD2) { minD2 = dd; }
            }

            if (uShowBorders == 1 && (minD2 - minD) < uBorderThresh) {
                vec3 c0 = uPalette[0] * 0.3;
                fragColor = vec4(c0, 1.0);
                return;
            }

            int ncP = max(uPaletteCount, 1);
            int idx = nearest - (nearest / ncP) * ncP;   // nearest % ncP without %
            idx = clamp(idx, 0, 4);
            fragColor = vec4(uPalette[idx], 1.0);
        }
    """

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float {
        val n = (params["numSeeds"] as? Number)?.toInt() ?: 12
        return (0.1f + n * 0.005f).coerceIn(0.12f, 0.4f)
    }
}
