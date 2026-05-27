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
 * GPU port. F2-F1 crackle field with three metrics (euclidean / manhattan /
 * concave = Minkowski p=0.5). The shader uses its own scan over uPoints rather
 * than the shared `voronoiF1F2` so it can handle the Concave metric (which
 * isn't part of the shared `vmetric`).
 *
 * maxCrackle normalisation samples 50 random pixels on the CPU exactly like
 * the original; the inverse is uploaded as a uniform.
 */
class VoronoiCrackleGenerator : GpuGenerator {

    override val id = "voronoi-crackle"
    override val family = "voronoi"
    override val styleName = "Voronoi Crackle"
    override val definition =
        "Crackle texture derived from Voronoi F2-F1 distance, producing organic vein-like patterns similar to dried mud or stone cracks."
    override val algorithmNotes =
        "GPU shader. Crackle value = (F2-F1) / maxCrackle, normalised against a CPU-sampled max. " +
        "If below 0.15 the pixel takes the crack colour; otherwise the nearest cell's palette swatch is " +
        "darkened/lightened/gradient-shaded according to fill mode. Concave uses the Minkowski p=0.5 metric."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema = listOf(
        Parameter.NumberParam("Cell Count", "cellCount", ParamGroup.COMPOSITION, "", 5f, 250f, 5f, 80f),
        Parameter.NumberParam("Crack Width", "crackWidth", ParamGroup.GEOMETRY, "Thickness of crack lines", 0.5f, 8f, 0.5f, 2f),
        Parameter.SelectParam("Crack Color", "crackColor", ParamGroup.COLOR, "", listOf("black", "white", "palette-first", "palette-last"), "black"),
        Parameter.SelectParam("Fill Mode", "fillMode", ParamGroup.COLOR, "How cell interiors are colored", listOf("flat-dark", "flat-light", "gradient", "palette"), "gradient"),
        Parameter.SelectParam("Distance Metric", "distanceMetric", ParamGroup.GEOMETRY, "", listOf("Euclidean", "Manhattan", "Concave"), "Euclidean"),
        Parameter.NumberParam("Anim Speed", "animSpeed", ParamGroup.FLOW_MOTION, "", 0f, 2f, 0.05f, 0.4f),
        Parameter.NumberParam("Anim Amplitude", "animAmp", ParamGroup.FLOW_MOTION, "Drift distance as a fraction of average cell size", 0f, 1f, 0.05f, 0.2f)
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "cellCount" to 80f, "crackWidth" to 2f, "crackColor" to "black",
        "fillMode" to "gradient", "distanceMetric" to "Euclidean",
        "animSpeed" to 0.4f, "animAmp" to 0.2f
    )

    override fun bindUniforms(
        programId: Int, params: Map<String, Any>, seed: Int, palette: Palette,
        quality: Quality, time: Float, width: Int, height: Int
    ) {
        val numPoints = ((params["cellCount"] as? Number)?.toInt() ?: 80)
            .coerceIn(1, VoronoiGlsl.MAX_POINTS)
        val lineWidth = (params["crackWidth"] as? Number)?.toFloat() ?: 2f
        val crackColor = (params["crackColor"] as? String) ?: "black"
        val fillMode = (params["fillMode"] as? String) ?: "gradient"
        val metric = (params["distanceMetric"] as? String) ?: "Euclidean"
        val animSpeed = (params["animSpeed"] as? Number)?.toFloat() ?: 0.4f
        val animAmp = (params["animAmp"] as? Number)?.toFloat() ?: 0.2f

        // Map "Concave" to ID 3 here; the shared 0/1/2 are euclidean/manhattan/chebyshev.
        val metricId = when (metric.lowercase()) {
            "manhattan" -> 1; "concave" -> 3; else -> 0
        }
        val fillModeId = when (fillMode) { "flat-dark" -> 1; "flat-light" -> 2; "palette" -> 3; else -> 0 }

        val rng = SeededRNG(seed)
        val px = FloatArray(numPoints); val py = FloatArray(numPoints)
        VoronoiGlsl.scatterPoints(px, py, numPoints, width, height, rng)

        if (time > 0f && animAmp > 0f) {
            val noise = SimplexNoise(seed)
            val speed = animSpeed / 0.4f; val amp = animAmp / 0.2f
            val wf = width.toFloat(); val hf = height.toFloat()
            for (i in 0 until numPoints) {
                px[i] = (px[i] + noise.noise2D(i * 0.25f + 40f, time * 0.15f * speed) * wf * 0.03f * amp).coerceIn(0f, wf - 1f)
                py[i] = (py[i] + noise.noise2D(i * 0.25f + 140f, time * 0.15f * speed) * hf * 0.03f * amp).coerceIn(0f, hf - 1f)
            }
        }

        // Sample maxCrackle on the CPU (50 pixels, all metrics return linear distance here).
        val sampleRng = SeededRNG(seed)
        for (i in 0 until numPoints * 2) sampleRng.random()
        var maxCrackle = 1f
        for (s in 0 until 50) {
            val sx = sampleRng.random() * width
            val sy = sampleRng.random() * height
            var sf1 = Float.MAX_VALUE; var sf2 = Float.MAX_VALUE
            for (i in 0 until numPoints) {
                val dx = sx - px[i]; val dy = sy - py[i]
                val d = when (metricId) {
                    0 -> sqrt(dx * dx + dy * dy)
                    1 -> abs(dx) + abs(dy)
                    else -> sqrt(abs(dx)) + sqrt(abs(dy)) // concave (linear)
                }
                if (d < sf1) { sf2 = sf1; sf1 = d } else if (d < sf2) { sf2 = d }
            }
            val ed = sf2 - sf1
            if (ed > maxCrackle) maxCrackle = ed
        }
        val invMaxCrackle = 1f / maxCrackle

        val crackColorVec = floatArrayOf(0f, 0f, 0f)
        when (crackColor) {
            "white" -> { crackColorVec[0] = 1f; crackColorVec[1] = 1f; crackColorVec[2] = 1f }
            "palette-first" -> {
                val c = palette.colorInts().first()
                crackColorVec[0] = android.graphics.Color.red(c) / 255f
                crackColorVec[1] = android.graphics.Color.green(c) / 255f
                crackColorVec[2] = android.graphics.Color.blue(c) / 255f
            }
            "palette-last" -> {
                val c = palette.colorInts().last()
                crackColorVec[0] = android.graphics.Color.red(c) / 255f
                crackColorVec[1] = android.graphics.Color.green(c) / 255f
                crackColorVec[2] = android.graphics.Color.blue(c) / 255f
            }
            // black -> zeros
        }

        val packed = VoronoiGlsl.packPoints(px, py, numPoints)

        GLES30.glUniform2fv(GLES30.glGetUniformLocation(programId, "uPoints"),
            VoronoiGlsl.MAX_POINTS, packed, 0)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uPointCount"), numPoints)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uMetric"), metricId)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uLineScale"), max(lineWidth / 2f, 0.001f))
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uInvMaxCrackle"), invMaxCrackle)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uFillMode"), fillModeId)
        GLES30.glUniform3f(GLES30.glGetUniformLocation(programId, "uCrackColor"),
            crackColorVec[0], crackColorVec[1], crackColorVec[2])
    }

    override fun fragmentShaderSource(): String = """#version 300 es
        precision highp float;

        uniform vec2 uResolution;
        uniform float uTime;
        uniform vec3 uAudio;
        ${PaletteUniform.GLSL_HELPERS}
        ${VoronoiGlsl.GLSL_HELPERS}

        uniform float uLineScale;
        uniform float uInvMaxCrackle;
        uniform int uFillMode;       // 0 gradient, 1 flat-dark, 2 flat-light, 3 palette
        uniform vec3 uCrackColor;

        out vec4 fragColor;

        // Crackle-specific F1/F2 scan. Supports uMetric 0/1/3 (euclidean linear,
        // manhattan, concave). Concave = Minkowski p=0.5: sqrt(|dx|) + sqrt(|dy|).
        vec4 crackleF1F2(vec2 p) {
            float f1 = 1e30; float f2 = 1e30;
            float i1 = 0.0;
            for (int i = 0; i < VORONOI_MAX_POINTS; i++) {
                if (i >= uPointCount) break;
                vec2 d = p - uPoints[i];
                float dist;
                if (uMetric == 1)      dist = abs(d.x) + abs(d.y);
                else if (uMetric == 3) dist = sqrt(abs(d.x)) + sqrt(abs(d.y));
                else                   dist = length(d);   // euclidean linear
                if (dist < f1) {
                    f2 = f1;
                    f1 = dist; i1 = float(i);
                } else if (dist < f2) {
                    f2 = dist;
                }
            }
            return vec4(f1, f2, i1, 0.0);
        }

        void main() {
            vec4 f = crackleF1F2(gl_FragCoord.xy);
            float crackle = clamp((f.y - f.x) * uInvMaxCrackle / uLineScale, 0.0, 1.0);

            vec3 col;
            if (crackle < 0.15) {
                col = uCrackColor;
            } else {
                int idx = int(f.z + 0.5);
                vec3 baseCol = palette_color(mod(float(idx), 5.0) / 4.0);
                if (uFillMode == 1) col = baseCol * 0.3;
                else if (uFillMode == 2) col = clamp(baseCol * 0.85 + 0.15, 0.0, 1.0);
                else if (uFillMode == 3) col = baseCol;
                else col = baseCol * (1.0 - crackle * 0.85);  // gradient
            }
            fragColor = vec4(col, 1.0);
        }
    """
}
