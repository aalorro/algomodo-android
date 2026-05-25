package com.artmondo.algomodo.generators.voronoi

import android.graphics.Color
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
import kotlin.math.sqrt

/**
 * GPU port. Weighted Voronoi — additive (d-w), multiplicative (d/w via
 * d²*invW²), or power (d²-w²). Per-cell colours and weights uploaded as
 * uniform arrays. Edge detection in shader via second-vs-best distance gap.
 *
 * Distance metric param is declared but ignored (matches original CPU
 * behaviour — all modes use Euclidean math internally).
 */
class VoronoiWeightedGenerator : GpuGenerator {

    override val id = "voronoi-weighted"
    override val family = "voronoi"
    override val styleName = "Voronoi Weighted"
    override val definition =
        "Weighted (power) Voronoi diagram where each seed point has a random weight that biases distance, creating cells of varying sizes like soap bubbles."
    override val algorithmNotes =
        "GPU shader. Each seed has a random weight from [1 - spread, 1 + spread] * avgCellSize. " +
        "Additive: d - w; Multiplicative: d² * (1/w)² (compare in squared space, sqrt only for the edge gap); " +
        "Power: d² - w². Per-cell colours (index / weight) computed CPU-side and uploaded as a vec3 uniform array."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema = listOf(
        Parameter.NumberParam("Cell Count", "cellCount", ParamGroup.COMPOSITION, "", 5f, 150f, 5f, 40f),
        Parameter.NumberParam("Weight Spread", "weightSpread", ParamGroup.GEOMETRY, "Variance in site weights — 0 = uniform (standard Voronoi), 1 = maximum size variation", 0f, 1f, 0.05f, 0.7f),
        Parameter.SelectParam("Weight Mode", "weightMode", ParamGroup.GEOMETRY, "additive: d-w (shifts boundary); multiplicative: d/w (scales cells); power: d²-w² (organic bulge)", listOf("additive", "multiplicative", "power"), "additive"),
        Parameter.NumberParam("Border Width", "borderWidth", ParamGroup.GEOMETRY, "", 0f, 5f, 0.5f, 1f),
        Parameter.SelectParam("Color Mode", "colorMode", ParamGroup.COLOR, "By Weight: larger-weighted sites use later palette colors", listOf("By Index", "By Weight", "By Distance"), "By Index"),
        Parameter.SelectParam("Distance Metric", "distanceMetric", ParamGroup.GEOMETRY, "", listOf("Euclidean", "Manhattan", "Chebyshev"), "Euclidean"),
        Parameter.NumberParam("Anim Speed", "animSpeed", ParamGroup.FLOW_MOTION, "", 0f, 2f, 0.05f, 0.4f),
        Parameter.NumberParam("Anim Amplitude", "animAmp", ParamGroup.FLOW_MOTION, "Drift distance as a fraction of average cell size", 0f, 1f, 0.05f, 0.2f)
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "cellCount" to 40f,
        "weightSpread" to 0.7f,
        "weightMode" to "additive",
        "borderWidth" to 1f,
        "colorMode" to "By Index",
        "distanceMetric" to "Euclidean",
        "animSpeed" to 0.4f,
        "animAmp" to 0.2f
    )

    override fun bindUniforms(
        programId: Int, params: Map<String, Any>, seed: Int, palette: Palette,
        quality: Quality, time: Float, width: Int, height: Int
    ) {
        val numPoints = ((params["cellCount"] as? Number)?.toInt() ?: 40)
            .coerceIn(1, VoronoiGlsl.MAX_POINTS)
        val weightVariance = (params["weightSpread"] as? Number)?.toFloat() ?: 0.7f
        val weightMode = (params["weightMode"] as? String) ?: "additive"
        val borderWidth = (params["borderWidth"] as? Number)?.toFloat() ?: 1f
        val colorMode = (params["colorMode"] as? String) ?: "By Index"
        val animSpeed = (params["animSpeed"] as? Number)?.toFloat() ?: 0.4f
        val animAmp = (params["animAmp"] as? Number)?.toFloat() ?: 0.2f

        val weightModeId = when (weightMode) { "power" -> 2; "multiplicative" -> 1; else -> 0 }
        val colorModeId = when (colorMode) { "By Weight" -> 1; "By Distance" -> 2; else -> 0 }

        val rng = SeededRNG(seed)
        val px = FloatArray(numPoints); val py = FloatArray(numPoints)
        val weights = FloatArray(numPoints)
        val avgCellSize = sqrt((width.toFloat() * height.toFloat()) / numPoints)

        for (i in 0 until numPoints) {
            px[i] = rng.random() * width
            py[i] = rng.random() * height
            weights[i] = avgCellSize * (1f + (rng.random() - 0.5f) * weightVariance)
        }

        if (time > 0f) {
            val noise = SimplexNoise(seed)
            val speed = animSpeed / 0.4f; val amp = animAmp / 0.2f
            val wf = width.toFloat() - 1f; val hf = height.toFloat() - 1f
            for (i in 0 until numPoints) {
                px[i] = (px[i] + noise.noise2D(i * 0.3f + 90f, time * 0.12f * speed) * width * 0.03f * amp).coerceIn(0f, wf)
                py[i] = (py[i] + noise.noise2D(i * 0.3f + 190f, time * 0.12f * speed) * height * 0.03f * amp).coerceIn(0f, hf)
                weights[i] *= 1f + noise.noise2D(i * 0.5f + 300f, time * 0.1f) * 0.1f
            }
        }

        // Pre-compute per-cell colour. Distance mode is computed shader-side.
        val cellCols = FloatArray(VoronoiGlsl.MAX_POINTS * 3)
        val colorInts = palette.colorInts()
        when (colorModeId) {
            1 -> {
                var minW = Float.MAX_VALUE; var maxW = -Float.MAX_VALUE
                for (i in 0 until numPoints) {
                    if (weights[i] < minW) minW = weights[i]
                    if (weights[i] > maxW) maxW = weights[i]
                }
                val invRange = 1f / (maxW - minW).coerceAtLeast(0.001f)
                val lut = palette.buildLut(256)
                for (i in 0 until numPoints) {
                    val t = ((weights[i] - minW) * invRange).coerceIn(0f, 1f)
                    val c = lut[(t * 255f).toInt().coerceIn(0, 255)]
                    cellCols[i * 3] = Color.red(c) / 255f
                    cellCols[i * 3 + 1] = Color.green(c) / 255f
                    cellCols[i * 3 + 2] = Color.blue(c) / 255f
                }
            }
            else -> {
                // By Index (0) and By Distance (2) both seed cellCols with palette-index colour;
                // shader overrides for distance mode.
                for (i in 0 until numPoints) {
                    val c = colorInts[i % colorInts.size]
                    cellCols[i * 3] = Color.red(c) / 255f
                    cellCols[i * 3 + 1] = Color.green(c) / 255f
                    cellCols[i * 3 + 2] = Color.blue(c) / 255f
                }
            }
        }

        val packed = VoronoiGlsl.packPoints(px, py, numPoints)
        // Weights padded to MAX_POINTS for uniform upload.
        val packedWeights = FloatArray(VoronoiGlsl.MAX_POINTS)
        for (i in 0 until numPoints) packedWeights[i] = weights[i]

        GLES30.glUniform2fv(GLES30.glGetUniformLocation(programId, "uPoints"),
            VoronoiGlsl.MAX_POINTS, packed, 0)
        GLES30.glUniform1fv(GLES30.glGetUniformLocation(programId, "uWeights"),
            VoronoiGlsl.MAX_POINTS, packedWeights, 0)
        GLES30.glUniform3fv(GLES30.glGetUniformLocation(programId, "uCellColors"),
            VoronoiGlsl.MAX_POINTS, cellCols, 0)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uPointCount"), numPoints)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uMetric"), 0)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uWeightMode"), weightModeId)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uColorMode"), colorModeId)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uBorderWidth"), borderWidth)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uInvAvgCellSize"), 1f / avgCellSize)
    }

    override fun fragmentShaderSource(): String = """#version 300 es
        precision highp float;

        uniform vec2 uResolution;
        uniform float uTime;
        uniform vec3 uAudio;
        ${PaletteUniform.GLSL_HELPERS}
        ${VoronoiGlsl.GLSL_HELPERS}

        uniform float uWeights[VORONOI_MAX_POINTS];
        uniform vec3 uCellColors[VORONOI_MAX_POINTS];
        uniform int uWeightMode;       // 0 additive, 1 multiplicative, 2 power
        uniform int uColorMode;        // 0 index, 1 weight, 2 distance
        uniform float uBorderWidth;
        uniform float uInvAvgCellSize;

        out vec4 fragColor;

        // Weighted F1/F2 scan. The "distance" units depend on weight mode:
        // - additive:       linear distance shifted by -w
        // - multiplicative: squared distance scaled by (1/w)^2 (kept squared)
        // - power:          squared distance shifted by -w^2
        // For edge detection we convert to linear when needed.
        vec4 weightedScan(vec2 p) {
            float bestD = 1e30; float secondD = 1e30;
            float bestIdx = 0.0;
            for (int i = 0; i < VORONOI_MAX_POINTS; i++) {
                if (i >= uPointCount) break;
                vec2 d = p - uPoints[i];
                float w = uWeights[i];
                float distSq = dot(d, d);
                float dval;
                if (uWeightMode == 0) {
                    dval = sqrt(distSq) - w;
                } else if (uWeightMode == 1) {
                    float invW = 1.0 / max(w, 0.01);
                    dval = distSq * invW * invW;
                } else {
                    dval = distSq - w * w;
                }
                if (dval < bestD) {
                    secondD = bestD;
                    bestD = dval; bestIdx = float(i);
                } else if (dval < secondD) {
                    secondD = dval;
                }
            }
            return vec4(bestD, secondD, bestIdx, 0.0);
        }

        void main() {
            vec2 p = gl_FragCoord.xy;
            vec4 f = weightedScan(p);

            // For border test we need the gap in linear pixel units.
            // additive: dval is already pixels — gap is in pixels.
            // multiplicative: dval = (dist/w)^2, so sqrt(dval) = dist/w (unitless).
            //                 Scale back to pixels by multiplying by avgCellSize = 1/uInvAvgCellSize.
            // power: dval is squared pixels — gap is in squared pixels (existing behaviour).
            float gap;
            if (uWeightMode == 1) {
                gap = (sqrt(f.y) - sqrt(f.x)) / uInvAvgCellSize;
            } else {
                gap = f.y - f.x;
            }

            vec3 col;
            if (uBorderWidth > 0.0 && gap < uBorderWidth * 2.0) {
                col = vec3(0.0);
            } else {
                int idx = int(f.z + 0.5);
                if (uColorMode == 2) {
                    // Distance mode: t = dist(pixel, nearest cell) / avgCellSize
                    vec2 d = p - uPoints[idx];
                    float dist = length(d);
                    float t = clamp(dist * uInvAvgCellSize, 0.0, 1.0);
                    col = palette_color(t);
                } else {
                    col = uCellColors[idx];
                }
            }
            fragColor = vec4(col, 1.0);
        }
    """
}
