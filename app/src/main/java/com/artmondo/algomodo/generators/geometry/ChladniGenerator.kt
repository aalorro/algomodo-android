package com.artmondo.algomodo.generators.geometry

import android.opengl.GLES30
import com.artmondo.algomodo.data.palettes.Palette
import com.artmondo.algomodo.generators.GpuGenerator
import com.artmondo.algomodo.generators.ParamGroup
import com.artmondo.algomodo.generators.Parameter
import com.artmondo.algomodo.generators.Quality
import com.artmondo.algomodo.rendering.gl.PaletteUniform
import kotlin.math.max
import kotlin.math.sin

/**
 * GPU-rendered Chladni vibration patterns. Evaluates one of four formulas
 * (square / circular / sum / product) per pixel and maps the field via four
 * colour modes (nodal / amplitude / phase / signed).
 *
 * Ported from the original CPU implementation; id, parameter schema and
 * defaults are preserved so existing presets continue to load.
 */
class ChladniGenerator : GpuGenerator {

    override val id = "chladni"
    override val family = "geometry"
    override val styleName = "Chladni Patterns"
    override val definition =
        "Chladni vibration figures showing nodal patterns of a vibrating plate, mapped to palette colors."
    override val algorithmNotes =
        "Per-pixel fragment shader on the GPU. Evaluates the Chladni equation cos(m\u00b7\u03c0\u00b7x)\u00b7cos(n\u00b7\u03c0\u00b7y) " +
        "\u2212 cos(n\u00b7\u03c0\u00b7x)\u00b7cos(m\u00b7\u03c0\u00b7y) (or one of three variants) for each pixel where (x,y) " +
        "is normalised to [-1,1]. Colour modes: nodal lines, amplitude fill, signed S-curve, or phase."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema = listOf(
        Parameter.NumberParam("M Frequency", "m", ParamGroup.GEOMETRY,
            "Horizontal mode number \u2014 together with N determines the resonant mode shape",
            1f, 12f, 1f, 3f),
        Parameter.NumberParam("N Frequency", "n", ParamGroup.GEOMETRY,
            "Vertical mode number", 1f, 12f, 1f, 5f),
        Parameter.NumberParam("Line Width", "tolerance", ParamGroup.GEOMETRY,
            "Threshold around zero \u2014 wider = thicker nodal lines",
            0.005f, 0.12f, 0.005f, 0.025f),
        Parameter.SelectParam("Formula", "formula", ParamGroup.COMPOSITION,
            "square: classic rectangular plate | circular: circular membrane, Bessel-like rings | sum: additive superposition | product: multiplicative coupling",
            listOf("square", "circular", "sum", "product"), "square"),
        Parameter.NumberParam("Beat Mix", "beatMix", ParamGroup.COMPOSITION,
            "Blend between mode (m, n) and mode (n, m) with a time-oscillating weight. 0 = pure (m,n) mode. 1 = full beat oscillation.",
            0f, 1f, 0.05f, 0f),
        Parameter.SelectParam("Color Mode", "colorMode", ParamGroup.COLOR,
            "nodal: only nodal lines lit | amplitude: full field filled by wave amplitude | phase: positive and negative regions | signed: smooth tanh S-curve",
            listOf("nodal", "amplitude", "phase", "signed"), "nodal"),
        Parameter.NumberParam("Speed", "speed", ParamGroup.FLOW_MOTION,
            "Phase evolution speed \u2014 animates nodal line morphing",
            0.05f, 3f, 0.05f, 0.5f)
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "m" to 3f, "n" to 5f, "tolerance" to 0.025f,
        "formula" to "square", "beatMix" to 0f,
        "colorMode" to "nodal", "speed" to 0.5f
    )

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
        val m = max(1, ((params["m"] as? Number)?.toInt() ?: 3))
        val n = max(1, ((params["n"] as? Number)?.toInt() ?: 5))
        val tolerance = max(0.001f, (params["tolerance"] as? Number)?.toFloat() ?: 0.025f)
        val formula = (params["formula"] as? String) ?: "square"
        val beatMix = ((params["beatMix"] as? Number)?.toFloat() ?: 0f).coerceIn(0f, 1f)
        val colorMode = (params["colorMode"] as? String) ?: "nodal"
        val speed = (params["speed"] as? Number)?.toFloat() ?: 0.5f
        val phase = time * speed

        val formulaId = when (formula) {
            "square" -> 0; "circular" -> 1; "sum" -> 2; "product" -> 3
            else -> 0
        }
        val colorId = when (colorMode) {
            "nodal" -> 0; "amplitude" -> 1; "phase" -> 2; "signed" -> 3
            else -> 0
        }

        val doBeat = beatMix > 0f && m != n
        val beatW = if (doBeat) beatMix * (0.5f + 0.5f * sin(phase * 0.8f)) else 0f

        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uM"), m)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uN"), n)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uTolerance"), tolerance)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uFormulaId"), formulaId)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uColorMode"), colorId)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uBeatW"), beatW)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uPhase"), phase)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uDoBeat"), if (doBeat) 1 else 0)
    }

    override fun fragmentShaderSource(): String = """#version 300 es
        precision highp float;

        uniform vec2 uResolution;
        uniform float uTime;
        uniform vec3 uAudio;
        ${PaletteUniform.GLSL_HELPERS}

        uniform int   uM;
        uniform int   uN;
        uniform float uTolerance;
        uniform int   uFormulaId;   // 0 square, 1 circular, 2 sum, 3 product
        uniform int   uColorMode;   // 0 nodal, 1 amplitude, 2 phase, 3 signed
        uniform float uBeatW;
        uniform float uPhase;
        uniform int   uDoBeat;

        out vec4 fragColor;

        const float PI = 3.14159265359;

        float chladniValue(float xx, float yy, float mpi, float npi, float fm, float fn, float ph) {
            // SQUARE: cos(nπx+φ)·cos(mπy) − cos(mπx+φ)·cos(nπy)
            //   m==n case: cos(nπx+φ)·cos(mπy) + cos(nπx)·cos(nπy+φ)
            if (uFormulaId == 0) {
                if (uM == uN) {
                    float A = cos(npi * xx + ph) * cos(mpi * yy);
                    float B = cos(npi * xx)      * cos(npi * yy + ph);
                    return A + B;
                }
                float v = cos(npi * xx + ph) * cos(mpi * yy)
                        - cos(mpi * xx + ph) * cos(npi * yy);
                if (uDoBeat == 1) v *= (1.0 - 2.0 * uBeatW);
                return v;
            }
            // CIRCULAR: cos(mπr+φ)·cos(n·θ+0.7φ), beat-blended with swapped
            if (uFormulaId == 1) {
                float r = sqrt(xx * xx + yy * yy);
                float theta = atan(yy, xx);
                float p7 = ph * 0.7;
                float v = cos(mpi * r + ph) * cos(fn * theta + p7);
                if (uDoBeat == 1) {
                    float v2 = cos(npi * r + ph) * cos(fm * theta + p7);
                    v = (1.0 - uBeatW) * v + uBeatW * v2;
                }
                return v;
            }
            // SUM: cos(mπx+φ)·cos(nπy) + cos(nπx+φ)·cos(mπy)
            if (uFormulaId == 2) {
                return cos(mpi * xx + ph) * cos(npi * yy)
                     + cos(npi * xx + ph) * cos(mpi * yy);
            }
            // PRODUCT: cos(mπx+φ)·cos(nπx) · cos(nπy)·cos(mπy+0.6φ), beat-swap
            float p6 = ph * 0.6;
            float v = cos(mpi * xx + ph) * cos(npi * xx)
                    * cos(npi * yy)      * cos(mpi * yy + p6);
            if (uDoBeat == 1) {
                float v2 = cos(npi * xx + ph) * cos(mpi * xx)
                         * cos(mpi * yy)      * cos(npi * yy + p6);
                v = (1.0 - uBeatW) * v + uBeatW * v2;
            }
            return v;
        }

        void main() {
            // Normalise to [-1, 1] across the bitmap independently per axis
            // (matches the CPU implementation: xx = px * (2/w) - 1)
            vec2 uv = (gl_FragCoord.xy / uResolution) * 2.0 - 1.0;
            float xx = uv.x;
            float yy = uv.y;

            float fm = float(uM);
            float fn = float(uN);
            float mpi = fm * PI;
            float npi = fn * PI;

            float v = chladniValue(xx, yy, mpi, npi, fm, fn, uPhase);

            float invTol   = 1.0 / uTolerance;
            float invTol15 = 1.0 / (uTolerance * 1.5);
            float av = abs(v);

            vec3 col;
            if (uColorMode == 0) {
                // NODAL: only thin band around zero lit, dimmed by distance
                if (av >= uTolerance) {
                    col = vec3(0.039);
                } else {
                    float bright = 1.0 - av * invTol;
                    float t = clamp(v * invTol * 0.5 + 0.5, 0.0, 1.0);
                    col = palette_color(t) * bright;
                }
            } else if (uColorMode == 1) {
                // AMPLITUDE: full fill, dimmed near nodal lines
                float t = clamp(v * 0.5 + 0.5, 0.0, 1.0);
                vec3 base = palette_color(t);
                if (av < uTolerance) {
                    float dim = 0.25 + 0.75 * av * invTol;
                    col = base * dim;
                } else {
                    col = base;
                }
            } else if (uColorMode == 2) {
                // PHASE: lutHi for positive, lutLo for negative, faded near zero
                vec3 hi = palette_color(1.0);
                vec3 lo = palette_color(0.0);
                vec3 base = (v > 0.0) ? hi : lo;
                if (av < uTolerance) {
                    float fade = av * invTol * 0.5;
                    col = base * fade;
                } else {
                    col = base;
                }
            } else {
                // SIGNED: smooth tanh-like S-curve
                float tx = v * invTol15;
                float t = clamp((tx / (1.0 + abs(tx))) * 0.5 + 0.5, 0.0, 1.0);
                col = palette_color(t);
            }

            fragColor = vec4(col, 1.0);
        }
    """

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float = 0.18f
}
