package com.artmondo.algomodo.generators.animation

import android.opengl.GLES30
import com.artmondo.algomodo.core.rng.SeededRNG
import com.artmondo.algomodo.data.palettes.Palette
import com.artmondo.algomodo.generators.GpuGenerator
import com.artmondo.algomodo.generators.ParamGroup
import com.artmondo.algomodo.generators.Parameter
import com.artmondo.algomodo.generators.Quality
import com.artmondo.algomodo.rendering.gl.PaletteUniform
import kotlin.math.PI
import kotlin.math.sin

/**
 * GPU-rendered animated plasma with double domain-warp, twist, contrast
 * sharpening and four blend modes. Per-layer frequencies, phases and
 * amplitudes are derived from the seed CPU-side and uploaded as uniform
 * arrays; the shader sums the layered sin/cos interference per pixel.
 *
 * Ported from the original CPU implementation; id, parameter schema and
 * defaults are preserved so existing presets continue to load.
 */
class PlasmaFeedbackGenerator : GpuGenerator {

    override val id = "plasma-feedback"
    override val family = "animation"
    override val styleName = "Plasma Feedback"
    override val definition = "Animated plasma effect built from layered sine-wave interference, mapped to the palette."
    override val algorithmNotes =
        "Per-pixel fragment shader on the GPU. Applies double domain-warp (two pairs of sine-driven " +
        "coordinate displacements) followed by up to 5 layered sin/cos interference octaves with " +
        "optional per-layer twist rotation. Contrast applies a power-curve sharpening; four blend " +
        "modes (smooth, additive, bands, ripple) transform the final value before palette mapping."
    override val supportsVector = false
    override val supportsAnimation = true

    companion object { private const val MAX_LAYERS = 5 }

    override val parameterSchema = listOf(
        Parameter.NumberParam(
            name = "Scale", key = "scale", group = ParamGroup.COMPOSITION, help = null,
            min = 0.5f, max = 6f, step = 0.1f, default = 2.0f
        ),
        Parameter.NumberParam(
            name = "Layers", key = "layers", group = ParamGroup.COMPOSITION,
            help = "Number of overlapping noise octaves \u2014 each successive layer doubles the frequency and halves the amplitude",
            min = 1f, max = 5f, step = 1f, default = 3f
        ),
        Parameter.NumberParam(
            name = "Warp", key = "warp", group = ParamGroup.COMPOSITION,
            help = "Double domain-warp intensity \u2014 displaces sample coordinates twice using independent noise fields, creating deep turbulent feedback-like structure. 0 = plain FBM. 1\u20132 = rich folded plasma.",
            min = 0f, max = 2f, step = 0.1f, default = 0.8f
        ),
        Parameter.NumberParam(
            name = "Twist", key = "twist", group = ParamGroup.COMPOSITION,
            help = "Rotational warp \u2014 a noise-driven rotation angle is applied to each layer's sample coordinates, producing swirling helical and spiral structures. 0 = no rotation.",
            min = 0f, max = 1.5f, step = 0.05f, default = 0f
        ),
        Parameter.NumberParam(
            name = "Contrast", key = "contrast", group = ParamGroup.TEXTURE, help = null,
            min = 0.5f, max = 3f, step = 0.1f, default = 1.4f
        ),
        Parameter.NumberParam(
            name = "Pulse", key = "pulse", group = ParamGroup.FLOW_MOTION,
            help = "Scale breathing \u2014 oscillates the global zoom as scale \u00d7 (1 + pulse \u00d7 sin(t))",
            min = 0f, max = 1f, step = 0.05f, default = 0f
        ),
        Parameter.NumberParam(
            name = "Speed", key = "speed", group = ParamGroup.FLOW_MOTION, help = null,
            min = 0.1f, max = 3f, step = 0.05f, default = 0.6f
        ),
        Parameter.SelectParam(
            name = "Blend Mode", key = "blend", group = ParamGroup.COLOR,
            help = "smooth: maps [\u22121,1] linearly to [0,1] | additive: abs(v) | bands: sin(v\u00b74\u03c0 + t) | ripple: two-frequency interference",
            options = listOf("smooth", "additive", "bands", "ripple"), default = "smooth"
        )
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "scale" to 2.0f, "speed" to 0.6f, "layers" to 3f, "contrast" to 1.4f,
        "warp" to 0.8f, "twist" to 0f, "pulse" to 0f, "blend" to "smooth"
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
        val layers = ((params["layers"] as? Number)?.toInt() ?: 3).coerceIn(1, MAX_LAYERS)
        val speed = (params["speed"] as? Number)?.toFloat() ?: 0.6f
        val baseZoom = (params["scale"] as? Number)?.toFloat() ?: 2.0f
        val warp = (params["warp"] as? Number)?.toFloat() ?: 0.8f
        val twist = (params["twist"] as? Number)?.toFloat() ?: 0f
        val contrast = (params["contrast"] as? Number)?.toFloat() ?: 1.4f
        val pulse = (params["pulse"] as? Number)?.toFloat() ?: 0f
        val blend = (params["blend"] as? String) ?: "smooth"

        val rng = SeededRNG(seed)
        val zoom = baseZoom * (1f + pulse * sin(time * speed * 0.7f))
        val t = time * speed

        val twoPi = 2f * PI.toFloat()

        // Per-layer params (mirror CPU order: layer 0 first)
        val freqs = FloatArray(MAX_LAYERS)
        val phaseX = FloatArray(MAX_LAYERS)
        val phaseY = FloatArray(MAX_LAYERS)
        val amps = FloatArray(MAX_LAYERS)
        val angleOffsets = FloatArray(MAX_LAYERS)
        for (i in 0 until layers) {
            freqs[i] = (i + 1).toFloat() * 0.8f * zoom
            phaseX[i] = rng.random() * twoPi
            phaseY[i] = rng.random() * twoPi
            amps[i] = 1f / (i + 1).toFloat()
            angleOffsets[i] = rng.random() * twoPi
        }
        // Seeded warp frequencies (after layer params, matching CPU rng order)
        val warpF1 = 2.3f + rng.random() * 1.5f
        val warpF2 = 1.7f + rng.random() * 1.5f
        val warpF3 = 3.1f + rng.random() * 1.5f
        val warpF4 = 2.7f + rng.random() * 1.5f
        val warpPhase1 = rng.random() * twoPi
        val warpPhase2 = rng.random() * twoPi

        val blendId = when (blend) {
            "smooth" -> 0; "additive" -> 1; "bands" -> 2; "ripple" -> 3
            else -> 0
        }

        GLES30.glUniform1fv(GLES30.glGetUniformLocation(programId, "uFreqs"), MAX_LAYERS, freqs, 0)
        GLES30.glUniform1fv(GLES30.glGetUniformLocation(programId, "uPhaseX"), MAX_LAYERS, phaseX, 0)
        GLES30.glUniform1fv(GLES30.glGetUniformLocation(programId, "uPhaseY"), MAX_LAYERS, phaseY, 0)
        GLES30.glUniform1fv(GLES30.glGetUniformLocation(programId, "uAmps"), MAX_LAYERS, amps, 0)
        GLES30.glUniform1fv(GLES30.glGetUniformLocation(programId, "uAngleOffsets"), MAX_LAYERS, angleOffsets, 0)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uLayers"), layers)
        GLES30.glUniform4f(GLES30.glGetUniformLocation(programId, "uWarpFreqs"), warpF1, warpF2, warpF3, warpF4)
        GLES30.glUniform2f(GLES30.glGetUniformLocation(programId, "uWarpPhases"), warpPhase1, warpPhase2)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uWarp"), warp)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uTwist"), twist)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uContrast"), contrast)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uT"), t)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uBlend"), blendId)
    }

    override fun fragmentShaderSource(): String = """#version 300 es
        precision highp float;

        uniform vec2 uResolution;
        uniform float uTime;
        uniform vec3 uAudio;
        ${PaletteUniform.GLSL_HELPERS}

        const int MAX_LAYERS = $MAX_LAYERS;
        uniform float uFreqs[MAX_LAYERS];
        uniform float uPhaseX[MAX_LAYERS];
        uniform float uPhaseY[MAX_LAYERS];
        uniform float uAmps[MAX_LAYERS];
        uniform float uAngleOffsets[MAX_LAYERS];
        uniform int   uLayers;
        uniform vec4  uWarpFreqs;     // warpF1..F4
        uniform vec2  uWarpPhases;    // warpPhase1, warpPhase2
        uniform float uWarp;
        uniform float uTwist;
        uniform float uContrast;
        uniform float uT;
        uniform int   uBlend;         // 0 smooth, 1 additive, 2 bands, 3 ripple

        out vec4 fragColor;

        const float PI = 3.14159265359;
        const float TAU = 6.28318530718;

        void main() {
            // CPU code: baseX = px / dim, baseY = py / dim (min(w,h))
            float dim = min(uResolution.x, uResolution.y);
            vec2 frag = gl_FragCoord.xy;
            float baseX = frag.x / dim;
            float baseY = frag.y / dim;

            float nx = baseX;
            float ny = baseY;

            // Double domain warp
            if (uWarp > 0.0) {
                float dx1 = sin(ny * uWarpFreqs.x * TAU + uT * 1.3 + uWarpPhases.x) * uWarp * 0.3;
                float dy1 = cos(nx * uWarpFreqs.y * TAU + uT * 1.1 + uWarpPhases.y) * uWarp * 0.3;
                float dx2 = sin((ny + dy1) * uWarpFreqs.z * TAU + uT * 0.7) * uWarp * 0.2;
                float dy2 = cos((nx + dx1) * uWarpFreqs.w * TAU + uT * 0.9) * uWarp * 0.2;
                nx += dx1 + dx2;
                ny += dy1 + dy2;
            }

            // Accumulate layers
            float value = 0.0;
            float maxAmp = 0.0;
            for (int i = 0; i < MAX_LAYERS; i++) {
                if (i >= uLayers) break;
                float f = uFreqs[i];
                float lx = nx * f;
                float ly = ny * f;

                if (uTwist > 0.0) {
                    float twistAngle = sin((nx + ny) * 3.0 + uT * 0.5 * float(i + 1) + uAngleOffsets[i])
                                       * uTwist * PI;
                    float cA = cos(twistAngle);
                    float sA = sin(twistAngle);
                    float rlx = lx * cA - ly * sA;
                    float rly = lx * sA + ly * cA;
                    lx = rlx; ly = rly;
                }

                float fi = float(i + 1);
                float tsX = uT * fi * 0.3;
                float tsY = uT * fi * 0.27;
                float sinPart = sin(lx * TAU + tsX + uPhaseX[i]);
                float cosPart = cos(ly * TAU + tsY + uPhaseY[i]);
                float cross = sin((lx + ly) * PI + uT * 0.2 * fi);
                float dist = length(vec2(nx - 0.5, ny - 0.5));
                float radial = sin(dist * f * TAU * 0.8 - uT * fi * 0.15);

                value += uAmps[i] * (sinPart * cosPart + cross * 0.4 + radial * 0.2);
                maxAmp += uAmps[i] * 1.6;
            }

            float normalized = clamp(value / maxAmp, -1.0, 1.0);

            // Contrast (power-curve sharpening, preserve sign)
            float invContrast = 1.0 / uContrast;
            float contrasted = normalized;
            if (uContrast != 1.0) {
                float s = (normalized >= 0.0) ? 1.0 : -1.0;
                contrasted = s * pow(abs(normalized), invContrast);
            }

            // Blend mode
            float blended;
            if (uBlend == 1) {
                blended = abs(contrasted);
            } else if (uBlend == 2) {
                blended = sin(contrasted * 4.0 * PI + uT * 1.5) * 0.5 + 0.5;
            } else if (uBlend == 3) {
                float r1 = sin(contrasted * 6.0 * PI + uT);
                float r2 = sin(contrasted * 10.0 * PI - uT * 1.3);
                blended = (r1 * 0.6 + r2 * 0.4) * 0.5 + 0.5;
            } else {
                blended = contrasted * 0.5 + 0.5;
            }

            vec3 col = palette_color(clamp(blended, 0.0, 1.0));
            fragColor = vec4(col, 1.0);
        }
    """

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float {
        val layers = (params["layers"] as? Number)?.toFloat() ?: 3f
        val warp = (params["warp"] as? Number)?.toFloat() ?: 0.8f
        return (layers * (1f + warp * 0.5f) / 25f).coerceIn(0.1f, 0.5f)
    }
}
