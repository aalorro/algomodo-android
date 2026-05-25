package com.artmondo.algomodo.generators.fractals

import android.opengl.GLES30
import com.artmondo.algomodo.data.palettes.Palette
import com.artmondo.algomodo.generators.GpuGenerator
import com.artmondo.algomodo.generators.ParamGroup
import com.artmondo.algomodo.generators.Parameter
import com.artmondo.algomodo.generators.Quality
import com.artmondo.algomodo.rendering.gl.PaletteUniform
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * GPU port. Lyapunov stability map. Sequence pattern (max length 6) uploaded
 * as int array. Per-pixel warmup + measurement done in fragment shader using
 * built-in log(). The CPU's fastLn shortcut isn't needed on GPU.
 */
class LyapunovGenerator : GpuGenerator {

    override val id = "fractal-lyapunov"
    override val family = "fractals"
    override val styleName = "Lyapunov Fractal"
    override val definition =
        "Stability map of alternating logistic maps — the Lyapunov exponent at each (a,b) point reveals striking organic stripe patterns at the boundary between order and chaos."
    override val algorithmNotes =
        "GPU fragment shader. Iterates logistic map x = r*x*(1-x) with r alternating per sequence " +
        "(AB / AABB / ...). Accumulates λ = (1/N) Σ log|r(1-2x)|. λ<0 → stable (palette), " +
        "λ>0 → chaotic (dark in stability mode, complementary palette in symmetric)."
    override val supportsVector = false
    override val supportsAnimation = true

    companion object {
        private val SEQUENCES = mapOf(
            "AB"     to intArrayOf(0, 1),
            "AABB"   to intArrayOf(0, 0, 1, 1),
            "ABAB"   to intArrayOf(0, 1, 0, 1),
            "ABBA"   to intArrayOf(0, 1, 1, 0),
            "AABAB"  to intArrayOf(0, 0, 1, 0, 1),
            "ABBAAB" to intArrayOf(0, 1, 1, 0, 0, 1),
        )
        private val TARGETS = arrayOf(
            doubleArrayOf(3.2, 3.4),
            doubleArrayOf(3.0, 3.5),
            doubleArrayOf(2.8, 3.8),
            doubleArrayOf(3.4, 3.2),
            doubleArrayOf(2.6, 3.0),
        )
        private const val MAX_SEQ_LEN = 6
        private const val LAMBDA_MAX = 2.0f
    }

    override val parameterSchema: List<Parameter> = listOf(
        Parameter.SelectParam("Sequence", "sequence", ParamGroup.COMPOSITION,
            "Alternation pattern for logistic map parameters a and b",
            listOf("AB", "AABB", "ABAB", "ABBA", "AABAB", "ABBAAB"), "AB"),
        Parameter.NumberParam("Center A", "centerA", ParamGroup.COMPOSITION,
            "Center of the viewport along the A parameter axis",
            0f, 4f, 0.05f, 3.0f),
        Parameter.NumberParam("Center B", "centerB", ParamGroup.COMPOSITION,
            "Center of the viewport along the B parameter axis",
            0f, 4f, 0.05f, 3.0f),
        Parameter.NumberParam("Zoom", "zoom", ParamGroup.COMPOSITION,
            "Zoom into the fractal", 0.5f, 8f, 0.5f, 2f),
        Parameter.NumberParam("Warmup", "warmup", ParamGroup.GEOMETRY,
            "Transient iterations before measuring", 50f, 500f, 50f, 100f),
        Parameter.NumberParam("Iterations", "iterations", ParamGroup.GEOMETRY,
            "Measurement iterations for Lyapunov exponent", 50f, 500f, 50f, 200f),
        Parameter.SelectParam("Color Mode", "colorMode", ParamGroup.COLOR,
            "stability: palette for stable, dark for chaotic | magnitude: signed gradient | symmetric: palette for both",
            listOf("stability", "magnitude", "symmetric"), "symmetric"),
        Parameter.NumberParam("Color Cycles", "colorCycles", ParamGroup.COLOR,
            "How many times the palette repeats across the exponent range",
            1f, 8f, 1f, 2f),
        Parameter.NumberParam("Speed", "speed", ParamGroup.FLOW_MOTION,
            "Animation drift speed through parameter space",
            0.1f, 3.0f, 0.1f, 0.5f),
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "sequence" to "AB",
        "centerA" to 3.0f,
        "centerB" to 3.0f,
        "zoom" to 2f,
        "warmup" to 100f,
        "iterations" to 200f,
        "colorMode" to "symmetric",
        "colorCycles" to 2f,
        "speed" to 0.5f,
    )

    override fun bindUniforms(
        programId: Int, params: Map<String, Any>, seed: Int, palette: Palette,
        quality: Quality, time: Float, width: Int, height: Int
    ) {
        val isAnim = time > 0f
        val seqKey = (params["sequence"] as? String) ?: "AB"
        val seq = SEQUENCES[seqKey] ?: SEQUENCES["AB"]!!
        val seqLen = seq.size

        var cA = (params["centerA"] as? Number)?.toDouble() ?: 3.0
        var cB = (params["centerB"] as? Number)?.toDouble() ?: 3.0
        var viewZoom = (params["zoom"] as? Number)?.toDouble() ?: 2.0
        val baseWarmup = max(30, (params["warmup"] as? Number)?.toInt() ?: 100)
        val baseIter = max(30, (params["iterations"] as? Number)?.toInt() ?: 200)
        val colorMode = (params["colorMode"] as? String) ?: "symmetric"
        val colorCycles = max(1, (params["colorCycles"] as? Number)?.toInt() ?: 2)
        val spd = (params["speed"] as? Number)?.toDouble() ?: 0.5

        val warmup = if (isAnim) max(16, baseWarmup / 4)
            else if (quality == Quality.ULTRA) baseWarmup
            else max(16, baseWarmup / 2)
        val iterations = if (isAnim) max(16, baseIter / 4)
            else if (quality == Quality.DRAFT) max(16, baseIter / 2)
            else baseIter

        if (isAnim) {
            val target = TARGETS[abs(seed) % TARGETS.size]
            val drift = time * spd * 0.06
            cA += sin(drift) * 0.4 + (target[0] - cA) * sin(drift * 0.3) * 0.2
            cB += cos(drift * 0.8) * 0.4 + (target[1] - cB) * cos(drift * 0.25) * 0.2
            viewZoom *= (1.0 + sin(time * spd * 0.05) * 0.3)
        }

        val viewSize = min(4.0, 4.0 / viewZoom)
        var aMin = cA - viewSize * 0.5; var aMax = aMin + viewSize
        var bMin = cB - viewSize * 0.5; var bMax = bMin + viewSize
        if (aMin < 0) { aMax -= aMin; aMin = 0.0 }
        if (aMax > 4) { aMin -= (aMax - 4); aMax = 4.0 }
        if (bMin < 0) { bMax -= bMin; bMin = 0.0 }
        if (bMax > 4) { bMin -= (bMax - 4); bMax = 4.0 }
        aMin = max(0.0, aMin); aMax = min(4.0, aMax)
        bMin = max(0.0, bMin); bMax = min(4.0, bMax)

        val colorModeId = when (colorMode) {
            "stability" -> 0; "magnitude" -> 1; else -> 2
        }

        // Pack sequence into int[MAX_SEQ_LEN]
        val seqPacked = IntArray(MAX_SEQ_LEN)
        for (i in 0 until min(seqLen, MAX_SEQ_LEN)) seqPacked[i] = seq[i]

        GLES30.glUniform2f(GLES30.glGetUniformLocation(programId, "uAMin"), aMin.toFloat(), aMax.toFloat())
        GLES30.glUniform2f(GLES30.glGetUniformLocation(programId, "uBMin"), bMin.toFloat(), bMax.toFloat())
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uWarmup"), warmup)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uIterations"), iterations)
        GLES30.glUniform1iv(GLES30.glGetUniformLocation(programId, "uSeq"), MAX_SEQ_LEN, seqPacked, 0)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uSeqLen"), seqLen)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uColorMode"), colorModeId)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uColorCycles"), colorCycles.toFloat())
    }

    override fun fragmentShaderSource(): String = """#version 300 es
        precision highp float;

        uniform vec2 uResolution;
        uniform float uTime;
        uniform vec3 uAudio;
        ${PaletteUniform.GLSL_HELPERS}

        #define MAX_SEQ 6

        uniform vec2 uAMin;   // x=aMin, y=aMax
        uniform vec2 uBMin;   // x=bMin, y=bMax
        uniform int uWarmup;
        uniform int uIterations;
        uniform int uSeq[MAX_SEQ];
        uniform int uSeqLen;
        uniform int uColorMode;     // 0 stability, 1 magnitude, 2 symmetric
        uniform float uColorCycles;

        const float LAMBDA_MAX = 2.0;

        out vec4 fragColor;

        void main() {
            // Y inverted: top = bMax. uv.y=0 at bottom of bitmap.
            vec2 uv = gl_FragCoord.xy / uResolution.xy;
            float a = uAMin.x + uv.x * (uAMin.y - uAMin.x);
            float b = uBMin.y - uv.y * (uBMin.y - uBMin.x);

            float xn = 0.5;
            bool diverged = false;
            int seqIdx = 0;

            // Warmup
            for (int i = 0; i < 1024; i++) {
                if (i >= uWarmup) break;
                float r = (uSeq[seqIdx] == 0) ? a : b;
                xn = r * xn * (1.0 - xn);
                seqIdx++;
                if (seqIdx >= uSeqLen) seqIdx = 0;
                if ((i & 7) == 7 && (xn < -0.01 || xn > 1.01 || isnan(xn))) {
                    diverged = true; break;
                }
            }

            float lambda;
            if (diverged) {
                lambda = LAMBDA_MAX;
            } else {
                float lyapSum = 0.0;
                int measured = 0;
                for (int i = 0; i < 1024; i++) {
                    if (i >= uIterations) break;
                    float r = (uSeq[seqIdx] == 0) ? a : b;
                    float rxn = r * xn;
                    xn = rxn * (1.0 - xn);
                    seqIdx++;
                    if (seqIdx >= uSeqLen) seqIdx = 0;
                    float deriv = r - 2.0 * rxn;
                    float ad = abs(deriv);
                    lyapSum += (ad > 1e-12) ? log(ad) : -30.0;
                    measured++;
                    if ((i & 7) == 7 && (xn < -0.01 || xn > 1.01 || isnan(xn))) {
                        lyapSum += 30.0 * float(uIterations - i - 1);
                        break;
                    }
                }
                lambda = lyapSum / float(uIterations);
            }

            float invLambdaMax = 1.0 / LAMBDA_MAX;
            vec3 col;

            if (uColorMode == 0) {
                if (lambda < 0.0) {
                    float t = fract(min(1.0, -lambda * invLambdaMax) * uColorCycles);
                    col = palette_color(t);
                } else {
                    col = vec3(0.0);
                }
            } else if (uColorMode == 1) {
                float t = clamp((lambda * invLambdaMax + 1.0) * 0.5, 0.0, 1.0);
                col = palette_color(fract(t * uColorCycles));
            } else {
                // Symmetric: complementary swap of palette channels for chaotic side
                if (lambda < 0.0) {
                    float t = fract(min(1.0, -lambda * invLambdaMax) * uColorCycles);
                    col = palette_color(t);
                } else {
                    float t = fract(min(1.0, lambda * invLambdaMax) * uColorCycles);
                    vec3 c = palette_color(t);
                    col = c.brg;     // rotate channels for visual contrast
                }
            }
            fragColor = vec4(col, 1.0);
        }
    """
}
