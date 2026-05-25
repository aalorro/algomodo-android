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
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * GPU port. F2-F1 distance field, quantised into bands and palette-mapped.
 *
 * The CPU version sampled 50 random pixels to find `maxEdgeDist` for
 * normalisation; we do the same on the CPU in [bindUniforms] and upload the
 * inverse as a uniform. Contour lines (CPU post-process scan of bandMap) are
 * replaced by an analytic shader test using `fwidth(bandFloat)`: wherever the
 * screen-space distance to the nearest band boundary is below the contour
 * width we darken the pixel by 4x — matching the CPU `color >> 2` behaviour.
 */
class VoronoiContoursGenerator : GpuGenerator {

    override val id = "voronoi-contours"
    override val family = "voronoi"
    override val styleName = "Voronoi Contours"
    override val definition =
        "Voronoi contour bands where the distance field between cells is quantised into coloured bands, producing a topographic map effect."
    override val algorithmNotes =
        "GPU shader. Per pixel computes F1/F2 voronoi distances, takes (F2-F1) normalised by a CPU-sampled max, " +
        "quantises into N bands and maps to palette. Smooth mode soft-blends the first 1/N of each band. " +
        "Contour lines are drawn analytically: pixels whose screen-space distance to a band boundary " +
        "(via fwidth) is below the line width are darkened to 25% intensity."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema = listOf(
        Parameter.NumberParam("Cell Count", "cellCount", ParamGroup.COMPOSITION, "", 3f, 120f, 3f, 25f),
        Parameter.NumberParam("Bands per Cell", "bandCount", ParamGroup.COMPOSITION, "Number of concentric contour rings around each seed", 2f, 20f, 1f, 8f),
        Parameter.SelectParam("Band Mode", "bandMode", ParamGroup.TEXTURE, "hard = crisp contour lines; smooth = continuous gradient; stepped = quantised levels", listOf("hard", "smooth", "stepped"), "hard"),
        Parameter.NumberParam("Contour Line", "contourLineWidth", ParamGroup.GEOMETRY, "Width of the dark contour boundary (hard/stepped modes)", 0f, 6f, 0.5f, 1f),
        Parameter.SelectParam("Color Mode", "colorMode", ParamGroup.COLOR, "", listOf("palette-cycle", "palette-gradient", "monochrome"), "palette-cycle"),
        Parameter.SelectParam("Distance Metric", "distanceMetric", ParamGroup.GEOMETRY, "", listOf("Euclidean", "Manhattan", "Chebyshev"), "Euclidean"),
        Parameter.NumberParam("Anim Speed", "animSpeed", ParamGroup.FLOW_MOTION, "", 0f, 2f, 0.05f, 0.4f),
        Parameter.NumberParam("Anim Amplitude", "animAmp", ParamGroup.FLOW_MOTION, "Drift distance as a fraction of average cell size", 0f, 1f, 0.05f, 0.2f)
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "cellCount" to 25f, "bandCount" to 8f, "bandMode" to "hard", "contourLineWidth" to 1f,
        "colorMode" to "palette-cycle", "distanceMetric" to "Euclidean",
        "animSpeed" to 0.4f, "animAmp" to 0.2f
    )

    override fun bindUniforms(
        programId: Int, params: Map<String, Any>, seed: Int, palette: Palette,
        quality: Quality, time: Float, width: Int, height: Int
    ) {
        val numPoints = ((params["cellCount"] as? Number)?.toInt() ?: 25)
            .coerceIn(1, VoronoiGlsl.MAX_POINTS)
        val bands = ((params["bandCount"] as? Number)?.toInt() ?: 8).coerceAtLeast(2)
        val bandMode = (params["bandMode"] as? String) ?: "hard"
        val contourLineWidth = (params["contourLineWidth"] as? Number)?.toFloat() ?: 1f
        val colorMode = (params["colorMode"] as? String) ?: "palette-cycle"
        val metric = (params["distanceMetric"] as? String) ?: "Euclidean"
        val animSpeed = (params["animSpeed"] as? Number)?.toFloat() ?: 0.4f
        val animAmp = (params["animAmp"] as? Number)?.toFloat() ?: 0.2f

        val metricId = VoronoiGlsl.metricId(metric)
        val isEuclidean = metricId == 0
        val bandModeId = when (bandMode) { "smooth" -> 1; "stepped" -> 2; else -> 0 }
        val colorModeId = when (colorMode) { "palette-gradient" -> 1; "monochrome" -> 2; else -> 0 }

        val rng = SeededRNG(seed)
        val px = FloatArray(numPoints); val py = FloatArray(numPoints)
        VoronoiGlsl.scatterPoints(px, py, numPoints, width, height, rng)
        if (time > 0f && animAmp > 0f) {
            val noise = SimplexNoise(seed)
            val avgCell = sqrt((width.toFloat() * height.toFloat()) / numPoints)
            for (i in 0 until numPoints) {
                px[i] = (px[i] + noise.noise2D(i * 0.3f + 30f, time * 0.18f * animSpeed) * avgCell * animAmp)
                    .coerceIn(0f, width - 1f)
                py[i] = (py[i] + noise.noise2D(i * 0.3f + 130f, time * 0.18f * animSpeed) * avgCell * animAmp)
                    .coerceIn(0f, height - 1f)
            }
        }

        // Sample 50 random pixels to estimate max F2-F1 (matches CPU normalisation).
        val sampleRng = SeededRNG(seed)
        // Re-seed via SeededRNG copy so the 50-sample stream is deterministic & matches CPU.
        for (i in 0 until numPoints * 2) sampleRng.random()
        var maxEdgeDist = 1f
        for (s in 0 until 50) {
            val sx = sampleRng.random() * width
            val sy = sampleRng.random() * height
            var sf1 = Float.MAX_VALUE; var sf2 = Float.MAX_VALUE
            for (i in 0 until numPoints) {
                val dx = sx - px[i]; val dy = sy - py[i]
                val d = when {
                    isEuclidean -> sqrt(dx * dx + dy * dy)
                    metricId == 1 -> abs(dx) + abs(dy)
                    else -> max(abs(dx), abs(dy))
                }
                if (d < sf1) { sf2 = sf1; sf1 = d } else if (d < sf2) { sf2 = d }
            }
            val ed = sf2 - sf1
            if (ed > maxEdgeDist) maxEdgeDist = ed
        }
        val invMaxEdge = 1f / maxEdgeDist

        val packed = VoronoiGlsl.packPoints(px, py, numPoints)

        GLES30.glUniform2fv(GLES30.glGetUniformLocation(programId, "uPoints"),
            VoronoiGlsl.MAX_POINTS, packed, 0)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uPointCount"), numPoints)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uMetric"), metricId)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uBands"), bands)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uBandMode"), bandModeId)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uContourLineWidth"), contourLineWidth)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uColorMode"), colorModeId)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uInvMaxEdge"), invMaxEdge)
    }

    override fun fragmentShaderSource(): String = """#version 300 es
        precision highp float;

        uniform vec2 uResolution;
        uniform float uTime;
        uniform vec3 uAudio;
        ${PaletteUniform.GLSL_HELPERS}
        ${VoronoiGlsl.GLSL_HELPERS}

        uniform int uBands;
        uniform int uBandMode;          // 0 hard, 1 smooth, 2 stepped
        uniform float uContourLineWidth;
        uniform int uColorMode;         // 0 palette-cycle, 1 palette-gradient, 2 monochrome
        uniform float uInvMaxEdge;

        out vec4 fragColor;

        void main() {
            vec2 p = gl_FragCoord.xy;
            vec4 f = voronoiF1F2(p);
            float f1Lin = (uMetric == 0) ? sqrt(f.x) : f.x;
            float f2Lin = (uMetric == 0) ? sqrt(f.y) : f.y;

            float normalized = clamp((f2Lin - f1Lin) * uInvMaxEdge, 0.0, 1.0);
            float bandFloat = normalized * float(uBands);
            int bandI = min(int(bandFloat), uBands - 1);
            float bandsM1 = max(float(uBands - 1), 1.0);
            float invBands = 1.0 / float(uBands);

            // Compute the base colour for this band.
            vec3 col;
            if (uBandMode == 1) {
                // smooth: partial gradient in first 1/N of each band, then flat.
                float frac = bandFloat - float(bandI);
                float smoothFrac = (frac < invBands) ? (frac * float(uBands)) : 1.0;
                float base = float(bandI) / bandsM1;
                float t = clamp(base + (float(bandI + 1) / bandsM1 - base) * smoothFrac * 0.3, 0.0, 1.0);
                if (uColorMode == 1) col = palette_color(t);
                else if (uColorMode == 2) col = vec3(t);
                else col = palette_color(mod(float(bandI), 5.0) / 4.0);
            } else {
                // hard / stepped — flat per band
                float t = clamp(float(bandI) / bandsM1, 0.0, 1.0);
                if (uColorMode == 1) col = palette_color(t);
                else if (uColorMode == 2) col = vec3(t);
                else col = palette_color(mod(float(bandI), 5.0) / 4.0);
            }

            // Analytic contour lines: pixels whose screen-space distance to the
            // nearest integer band boundary (in band-units) is below the line
            // width get darkened. Replaces the CPU bandMap post-process.
            if (uContourLineWidth > 0.0 && uBandMode != 1) {
                float bf = bandFloat;
                float distToBoundary = min(fract(bf), 1.0 - fract(bf));
                float bandWidthPx = max(fwidth(bf), 1e-5);
                float pxDist = distToBoundary / bandWidthPx;
                if (pxDist < uContourLineWidth) col *= 0.25;
            }

            fragColor = vec4(col, 1.0);
        }
    """
}
