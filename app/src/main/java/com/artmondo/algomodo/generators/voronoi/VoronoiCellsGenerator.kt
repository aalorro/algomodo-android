package com.artmondo.algomodo.generators.voronoi

import android.opengl.GLES30
import com.artmondo.algomodo.core.rng.SeededRNG
import com.artmondo.algomodo.core.rng.SimplexNoise
import com.artmondo.algomodo.data.palettes.Palette
import com.artmondo.algomodo.generators.GpuGenerator
import com.artmondo.algomodo.generators.ParamGroup
import com.artmondo.algomodo.generators.Parameter
import com.artmondo.algomodo.generators.Quality
import com.artmondo.algomodo.rendering.gl.PaletteUniform
import com.artmondo.algomodo.rendering.gl.VoronoiGlsl
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * GPU port. Classic Voronoi: each pixel takes the colour of the cell whose
 * seed point it lies closest to under the chosen metric. Edges are drawn
 * where the gap between the nearest and second-nearest distances is small.
 *
 * Point placement (and simplex-noise animation drift) is computed CPU-side
 * in [bindUniforms] and uploaded as a vec2 array.
 * The fragment shader does a brute-force linear scan per pixel.
 *
 * Patterns: Random, Jittered Grid, Hex Grid, Square Grid, Phyllotaxis, Rings.
 * Colour modes: By Index (exact palette indexing — crisp), By Distance, By
 * Angle, Contour Rings (distance bands within each cell), Bipolar.
 */
class VoronoiCellsGenerator : GpuGenerator {

    override val id = "voronoi-cells"
    override val family = "voronoi"
    override val styleName = "Voronoi Cells"
    override val definition =
        "Classic Voronoi diagram where the plane is partitioned into cells around scattered seed points, each coloured by palette index."
    override val algorithmNotes =
        "GPU shader. Seed points (random, grid, hex, phyllotaxis or rings) and simplex-noise animation are " +
        "computed on the CPU and uploaded as a vec2 uniform array. Per pixel the fragment shader linearly " +
        "scans all points for the nearest and second-nearest, then maps to palette by index (exact, no " +
        "gradient mixing), distance, angle, contour rings, or bipolar split. Borders are analytically " +
        "antialiased with fwidth()."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema = listOf(
        Parameter.NumberParam("Cell Count", "cellCount", ParamGroup.COMPOSITION, "", 5f, 200f, 1f, 40f),
        Parameter.SelectParam("Pattern", "pattern", ParamGroup.COMPOSITION,
            "Seed point placement layout",
            listOf("Random", "Jittered Grid", "Hex Grid", "Square Grid", "Phyllotaxis", "Rings"),
            "Random"),
        Parameter.SelectParam("Distance Metric", "distanceMetric", ParamGroup.GEOMETRY, "", listOf("Euclidean", "Manhattan", "Chebyshev"), "Euclidean"),
        Parameter.NumberParam("Border Width", "borderWidth", ParamGroup.GEOMETRY, "", 0f, 5f, 0.5f, 1f),
        Parameter.SelectParam("Color Mode", "colorMode", ParamGroup.COLOR, "",
            listOf("By Index", "By Distance", "By Angle", "Contour Rings", "Bipolar"),
            "By Index"),
        Parameter.NumberParam("Anim Speed", "animSpeed", ParamGroup.FLOW_MOTION, "", 0f, 2f, 0.05f, 0.4f),
        Parameter.NumberParam("Anim Amplitude", "animAmp", ParamGroup.FLOW_MOTION, "Drift distance as a fraction of average cell size", 0f, 1f, 0.05f, 0.2f)
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "cellCount" to 40f,
        "pattern" to "Random",
        "distanceMetric" to "Euclidean",
        "borderWidth" to 1f,
        "colorMode" to "By Index",
        "animSpeed" to 0.4f,
        "animAmp" to 0.2f
    )

    override fun bindUniforms(
        programId: Int, params: Map<String, Any>, seed: Int, palette: Palette,
        quality: Quality, time: Float, width: Int, height: Int
    ) {
        val numPoints = ((params["cellCount"] as? Number)?.toInt() ?: 40)
            .coerceIn(1, VoronoiGlsl.MAX_POINTS)
        val edgeWidth = (params["borderWidth"] as? Number)?.toFloat() ?: 1f
        val metric = (params["distanceMetric"] as? String) ?: "Euclidean"
        val colorMode = (params["colorMode"] as? String) ?: "By Index"
        val pattern = (params["pattern"] as? String) ?: "Random"
        val animSpeed = (params["animSpeed"] as? Number)?.toFloat() ?: 0.4f
        val animAmp = (params["animAmp"] as? Number)?.toFloat() ?: 0.2f

        val metricId = VoronoiGlsl.metricId(metric)
        val colorModeId = when (colorMode) {
            "By Distance" -> 1
            "By Angle" -> 2
            "Contour Rings" -> 3
            "Bipolar" -> 4
            else -> 0
        }

        val rng = SeededRNG(seed)
        val px = FloatArray(numPoints); val py = FloatArray(numPoints)
        scatterByPattern(pattern, px, py, numPoints, width, height, rng)
        VoronoiGlsl.animatePoints(px, py, numPoints, width, height,
            SimplexNoise(seed), time, animSpeed, animAmp)

        val packed = VoronoiGlsl.packPoints(px, py, numPoints)

        GLES30.glUniform2fv(GLES30.glGetUniformLocation(programId, "uPoints"),
            VoronoiGlsl.MAX_POINTS, packed, 0)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uPointCount"), numPoints)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uMetric"), metricId)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uEdgeWidth"), edgeWidth)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uColorMode"), colorModeId)
    }

    /** Dispatch seed placement to the requested layout. */
    private fun scatterByPattern(
        pattern: String, px: FloatArray, py: FloatArray, count: Int,
        w: Int, h: Int, rng: SeededRNG
    ) {
        when (pattern) {
            "Jittered Grid" -> scatterJitteredGrid(px, py, count, w, h, rng)
            "Hex Grid" -> scatterHexGrid(px, py, count, w, h)
            "Square Grid" -> scatterSquareGrid(px, py, count, w, h)
            "Phyllotaxis" -> scatterPhyllotaxis(px, py, count, w, h)
            "Rings" -> scatterRings(px, py, count, w, h)
            else -> VoronoiGlsl.scatterPoints(px, py, count, w, h, rng)
        }
    }

    private fun scatterSquareGrid(px: FloatArray, py: FloatArray, count: Int, w: Int, h: Int) {
        val cols = max(1, sqrt(count * w.toDouble() / h.toDouble()).toInt())
        val rows = max(1, (count + cols - 1) / cols)
        val cellW = w.toFloat() / cols
        val cellH = h.toFloat() / rows
        var i = 0
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                if (i >= count) return
                px[i] = (c + 0.5f) * cellW
                py[i] = (r + 0.5f) * cellH
                i++
            }
        }
    }

    private fun scatterJitteredGrid(px: FloatArray, py: FloatArray, count: Int, w: Int, h: Int, rng: SeededRNG) {
        val cols = max(1, sqrt(count * w.toDouble() / h.toDouble()).toInt())
        val rows = max(1, (count + cols - 1) / cols)
        val cellW = w.toFloat() / cols
        val cellH = h.toFloat() / rows
        val jitter = 0.45f // half-cell max displacement -> blue-noise-ish coverage
        var i = 0
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                if (i >= count) return
                val jx = (rng.random() - 0.5f) * 2f * jitter
                val jy = (rng.random() - 0.5f) * 2f * jitter
                px[i] = ((c + 0.5f + jx) * cellW).coerceIn(0f, w - 1f)
                py[i] = ((r + 0.5f + jy) * cellH).coerceIn(0f, h - 1f)
                i++
            }
        }
    }

    private fun scatterHexGrid(px: FloatArray, py: FloatArray, count: Int, w: Int, h: Int) {
        // Hexagonal lattice: rows offset horizontally by half-cell on odd rows.
        // Spacing chosen so that final point count ~= requested count.
        val area = w.toDouble() * h.toDouble()
        // Hex packing: each point occupies ~ s^2 * sqrt(3)/2 area.
        val s = sqrt(area / (count * sqrt(3.0) / 2.0)).toFloat()
        val rowH = s * sqrt(3.0).toFloat() / 2f
        val rows = max(1, (h / rowH).toInt() + 1)
        val cols = max(1, (w / s).toInt() + 1)
        var i = 0
        for (r in 0 until rows) {
            val offX = if (r and 1 == 1) s * 0.5f else 0f
            for (c in 0 until cols) {
                if (i >= count) return
                val x = (c + 0.5f) * s + offX
                val y = (r + 0.5f) * rowH
                if (x in 0f..(w - 1f) && y in 0f..(h - 1f)) {
                    px[i] = x; py[i] = y; i++
                }
            }
        }
        // If we underfilled (boundary clipping), pack remaining points along center.
        while (i < count) {
            px[i] = w * 0.5f; py[i] = h * 0.5f; i++
        }
    }

    private fun scatterPhyllotaxis(px: FloatArray, py: FloatArray, count: Int, w: Int, h: Int) {
        // Golden-angle spiral. r grows as sqrt(i) -> uniform density.
        val cx = w * 0.5f
        val cy = h * 0.5f
        val maxR = min(w, h) * 0.48f
        val goldenAngle = PI * (3.0 - sqrt(5.0)) // ~2.39996
        for (i in 0 until count) {
            val t = (i + 0.5) / count
            val r = (maxR * sqrt(t)).toFloat()
            val a = (i * goldenAngle).toFloat()
            px[i] = (cx + r * cos(a)).coerceIn(0f, w - 1f)
            py[i] = (cy + r * sin(a)).coerceIn(0f, h - 1f)
        }
    }

    private fun scatterRings(px: FloatArray, py: FloatArray, count: Int, w: Int, h: Int) {
        // Concentric rings; ring i has ~ (2i+1) points so total ~= rings^2.
        val cx = w * 0.5f
        val cy = h * 0.5f
        val maxR = min(w, h) * 0.48f
        val ringCount = max(1, sqrt(count.toDouble()).toInt())
        var idx = 0
        for (r in 0 until ringCount) {
            val ringR = maxR * (r + 1f) / ringCount
            val pointsThisRing = if (r == 0) 1 else (2 * r * 3).coerceAtMost(count - idx)
            for (p in 0 until pointsThisRing) {
                if (idx >= count) return
                val a = (p.toFloat() / pointsThisRing) * 2f * PI.toFloat() +
                        r * 0.3f // small offset so rings don't align radially
                px[idx] = (cx + ringR * cos(a.toDouble())).toFloat().coerceIn(0f, w - 1f)
                py[idx] = (cy + ringR * sin(a.toDouble())).toFloat().coerceIn(0f, h - 1f)
                idx++
            }
        }
        // Pack any unfilled slots at the center.
        while (idx < count) {
            px[idx] = cx; py[idx] = cy; idx++
        }
    }

    override fun fragmentShaderSource(): String = """#version 300 es
        precision highp float;

        uniform vec2 uResolution;
        uniform float uTime;
        uniform vec3 uAudio;
        ${PaletteUniform.GLSL_HELPERS}
        ${VoronoiGlsl.GLSL_HELPERS}

        uniform float uEdgeWidth;
        uniform int uColorMode;  // 0 index, 1 distance, 2 angle, 3 rings, 4 bipolar

        out vec4 fragColor;

        const float PI = 3.14159265359;
        const float INV_TWO_PI = 0.15915494309;

        // Exact palette indexing (no gradient mix) — crispier than palette_color.
        vec3 palette_exact(int idx) {
            int i = idx - (idx / 5) * 5;
            if (i < 0) i += 5;
            if (i == 0) return uPalette[0];
            if (i == 1) return uPalette[1];
            if (i == 2) return uPalette[2];
            if (i == 3) return uPalette[3];
            return uPalette[4];
        }

        void main() {
            vec2 p = gl_FragCoord.xy;
            vec4 f = voronoiF1F2(p);
            // f.x/f.y are SQUARED for euclidean, LINEAR for manhattan/chebyshev.
            float f1Lin = (uMetric == 0) ? sqrt(f.x) : f.x;
            float f2Lin = (uMetric == 0) ? sqrt(f.y) : f.y;

            int idx = int(f.z + 0.5);
            float avgCellSize = sqrt(uResolution.x * uResolution.y / max(float(uPointCount), 1.0));

            // Compute cell colour first, then composite border with fwidth() AA for crispness.
            vec3 cellCol;
            if (uColorMode == 1) {
                // By Distance — normalise against avg cell size
                float t = clamp(f1Lin / avgCellSize, 0.0, 1.0);
                cellCol = palette_color(t);
            } else if (uColorMode == 2) {
                // By Angle — angle of the seed point relative to canvas center
                vec2 sp = uPoints[idx];
                vec2 d = sp - uResolution * 0.5;
                float t = clamp((atan(d.y, d.x) + PI) * INV_TWO_PI, 0.0, 1.0);
                cellCol = palette_color(t);
            } else if (uColorMode == 3) {
                // Contour Rings — distance bands inside each cell.
                vec3 base = palette_exact(idx);
                float bandWidth = avgCellSize / 6.0;
                float band = mod(f1Lin / bandWidth, 2.0);
                float aaB = fwidth(f1Lin / bandWidth);
                float blend = smoothstep(1.0 - aaB, 1.0 + aaB, band);
                cellCol = mix(base, base * 0.55, blend);
            } else if (uColorMode == 4) {
                // Bipolar — split cells two ways by index parity.
                cellCol = ((idx & 1) == 0) ? uPalette[0] : uPalette[3];
            } else {
                // By Index — exact palette, no interpolation. Crisp cell colours.
                cellCol = palette_exact(idx);
            }

            // Analytic anti-aliased border. f2-f1 -> ~0 at boundaries; smoothstep
            // across one screen-pixel of derivative -> ~1 sub-pixel transition.
            vec3 col = cellCol;
            if (uEdgeWidth > 0.0) {
                float diff = f2Lin - f1Lin;
                float thresh = uEdgeWidth * 2.0;
                float aa = max(fwidth(diff), 0.5);
                float t = smoothstep(thresh - aa, thresh + aa, diff);
                col = mix(vec3(0.0), cellCol, t);
            }

            fragColor = vec4(col, 1.0);
        }
    """
}
