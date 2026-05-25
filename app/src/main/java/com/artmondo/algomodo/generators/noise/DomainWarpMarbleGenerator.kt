package com.artmondo.algomodo.generators.noise

import android.opengl.GLES30
import com.artmondo.algomodo.data.palettes.Palette
import com.artmondo.algomodo.generators.GpuGenerator
import com.artmondo.algomodo.generators.ParamGroup
import com.artmondo.algomodo.generators.Parameter
import com.artmondo.algomodo.generators.Quality
import com.artmondo.algomodo.rendering.gl.NoiseGlsl
import com.artmondo.algomodo.rendering.gl.PaletteUniform

/**
 * GPU port. Marble pattern = sin((wx + wy) * bands + turb * PI) where wx/wy
 * is the doubly-domain-warped coordinate. Optional turbulence readout for
 * chaotic variation.
 */
class DomainWarpMarbleGenerator : GpuGenerator {

    override val id = "domain-warp-marble"
    override val family = "noise"
    override val styleName = "Domain Warp Marble"
    override val definition =
        "Marble-textured domain warp using sine waves perturbed by noise to create realistic veining."
    override val algorithmNotes =
        "GPU shader. Core pattern: sin((wx + wy) * bands + turb * π) where the coordinate has " +
        "been domain-warped (optionally twice) by fbm noise. Turbulence mode uses abs(noise) " +
        "for chaotic variation. Vein sharpness pow()s the final palette parameter."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema: List<Parameter> = listOf(
        Parameter.NumberParam("Scale", "scale", ParamGroup.COMPOSITION, "", 0.5f, 8f, 0.1f, 2.5f),
        Parameter.NumberParam("Warp Strength", "warpStrength", ParamGroup.COMPOSITION, "Coordinate displacement intensity", 0f, 3f, 0.05f, 1.2f),
        Parameter.NumberParam("Warp Scale", "warpScale", ParamGroup.COMPOSITION, "Frequency of the warp field", 0.5f, 6f, 0.1f, 2.0f),
        Parameter.NumberParam("Marble Bands", "bands", ParamGroup.GEOMETRY, "Sine-band striations", 1f, 20f, 1f, 6f),
        Parameter.NumberParam("Octaves", "octaves", ParamGroup.GEOMETRY, "", 1f, 8f, 1f, 5f),
        Parameter.NumberParam("Smoothness", "gain", ParamGroup.TEXTURE, "", 0.2f, 0.8f, 0.05f, 0.5f),
        Parameter.NumberParam("Vein Sharpness", "veinSharpness", ParamGroup.TEXTURE, ">1 = thinner veins, <1 = wider veins", 0.5f, 4.0f, 0.1f, 1.0f),
        Parameter.BooleanParam("Turbulence", "turbulence", ParamGroup.TEXTURE, "Use abs(noise) for chaotic patterns", false),
        Parameter.BooleanParam("Double Warp", "doubleWarp", ParamGroup.COMPOSITION, "Second warp pass for more complexity", true),
        Parameter.SelectParam("Anim Mode", "animMode", ParamGroup.FLOW_MOTION, "flow: veins morph | drift: translate | pulse: warp breathes", listOf("flow", "drift", "pulse"), "flow"),
        Parameter.NumberParam("Speed", "speed", ParamGroup.FLOW_MOTION, "Animation speed", 0.1f, 3.0f, 0.1f, 0.5f)
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "scale" to 2.5f, "warpStrength" to 1.2f, "warpScale" to 2.0f,
        "bands" to 6f, "octaves" to 5f, "gain" to 0.5f, "veinSharpness" to 1.0f,
        "turbulence" to false, "doubleWarp" to true,
        "animMode" to "flow", "speed" to 0.5f
    )

    override fun bindUniforms(
        programId: Int, params: Map<String, Any>, seed: Int, palette: Palette,
        quality: Quality, time: Float, width: Int, height: Int
    ) {
        val scale = (params["scale"] as? Number)?.toFloat() ?: 2.5f
        val warpStrength = (params["warpStrength"] as? Number)?.toFloat() ?: 1.2f
        val warpScale = (params["warpScale"] as? Number)?.toFloat() ?: 2.0f
        val bands = (params["bands"] as? Number)?.toFloat() ?: 6f
        val octaves = ((params["octaves"] as? Number)?.toInt() ?: 5).coerceIn(1, 10)
        val gain = (params["gain"] as? Number)?.toFloat() ?: 0.5f
        val veinSharpness = (params["veinSharpness"] as? Number)?.toFloat() ?: 1.0f
        val useTurbulence = (params["turbulence"] as? Boolean) ?: false
        val doubleWarp = (params["doubleWarp"] as? Boolean) ?: true
        val animMode = (params["animMode"] as? String) ?: "flow"
        val speed = (params["speed"] as? Number)?.toFloat() ?: 0.5f

        val animModeId = when (animMode) { "drift" -> 1; "pulse" -> 2; else -> 0 } // flow=0
        val (seedOffX, seedOffY) = NoiseGlsl.seedToOffset(seed)

        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uScale"), scale)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uWarpStrength"), warpStrength)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uWarpScale"), warpScale)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uBands"), bands)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uOctaves"), octaves)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uGain"), gain)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uVeinSharpness"), veinSharpness)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uTurbulence"), if (useTurbulence) 1 else 0)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uDoubleWarp"), if (doubleWarp) 1 else 0)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uAnimMode"), animModeId)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uSpeed"), speed)
        GLES30.glUniform2f(GLES30.glGetUniformLocation(programId, "uSeedOffset"), seedOffX, seedOffY)
    }

    override fun fragmentShaderSource(): String = """#version 300 es
        precision highp float;

        uniform vec2 uResolution;
        uniform float uTime;
        uniform vec3 uAudio;
        ${PaletteUniform.GLSL_HELPERS}
        ${NoiseGlsl.GLSL_HELPERS}

        uniform float uScale;
        uniform float uWarpStrength;
        uniform float uWarpScale;
        uniform float uBands;
        uniform int uOctaves;
        uniform float uGain;
        uniform float uVeinSharpness;
        uniform int uTurbulence;     // bool
        uniform int uDoubleWarp;     // bool
        uniform int uAnimMode;       // 0 flow, 1 drift, 2 pulse
        uniform float uSpeed;
        uniform vec2 uSeedOffset;

        out vec4 fragColor;

        const float PI = 3.14159265359;
        const vec2 W_OFF1A = vec2(3.7, 7.1);
        const vec2 W_OFF1B = vec2(7.1, 3.7);
        const vec2 W_OFF2A = vec2(11.3, 2.8);
        const vec2 W_OFF2B = vec2(2.8, 11.3);

        void main() {
            float invScale = uScale / uResolution.x;
            float warpInvScale = uWarpScale / uResolution.x;
            vec2 base = gl_FragCoord.xy * invScale;
            vec2 warpSrc = gl_FragCoord.xy * warpInvScale;

            // Base animation (mirrors CPU)
            if (uAnimMode == 1) {
                base.x += uTime * uSpeed * 0.25;
                base.y += uTime * uSpeed * 0.15;
            } else {
                // flow and pulse both use the slow drift
                base.x += uTime * uSpeed * 0.08;
                base.y += uTime * uSpeed * 0.05;
            }

            base += uSeedOffset;
            warpSrc += uSeedOffset;

            // Pulse modulates warp strength
            float effWarp = uWarpStrength;
            if (uAnimMode == 2) {
                effWarp *= 1.0 + 0.4 * sin(uTime * uSpeed * 0.7);
            }

            float flowTimeA = uTime * uSpeed * 0.2;
            float flowTimeB = uTime * uSpeed * 0.15;

            // First warp pass
            float w1x, w1y;
            if (uAnimMode == 0) {
                w1x = fbm(warpSrc + W_OFF1A + vec2(flowTimeA, flowTimeB), uOctaves, 2.0, uGain) * effWarp;
                w1y = fbm(warpSrc + W_OFF1B + vec2(flowTimeB, flowTimeA), uOctaves, 2.0, uGain) * effWarp;
            } else {
                w1x = fbm(warpSrc + W_OFF1A, uOctaves, 2.0, uGain) * effWarp;
                w1y = fbm(warpSrc + W_OFF1B, uOctaves, 2.0, uGain) * effWarp;
            }
            vec2 w = base + vec2(w1x, w1y);

            // Optional second warp pass
            if (uDoubleWarp == 1) {
                float w2x, w2y;
                if (uAnimMode == 0) {
                    w2x = fbm(w + W_OFF2A + vec2(flowTimeB * 0.7, flowTimeA * 0.7), 3, 2.0, uGain) * effWarp * 0.5;
                    w2y = fbm(w + W_OFF2B + vec2(flowTimeA * 0.7, flowTimeB * 0.7), 3, 2.0, uGain) * effWarp * 0.5;
                } else {
                    w2x = fbm(w + W_OFF2A, 3, 2.0, uGain) * effWarp * 0.5;
                    w2y = fbm(w + W_OFF2B, 3, 2.0, uGain) * effWarp * 0.5;
                }
                w += vec2(w2x, w2y);
            }

            // Noise perturbation
            float turb = (uTurbulence == 1)
                ? turbulenceNoise(w, uOctaves, 2.0, uGain)
                : fbm(w, uOctaves, 2.0, uGain);
            float turbScale = (uTurbulence == 1) ? turb * 2.0 : turb;

            // Classic marble: sin((wx + wy) * bands + turbScale * PI)
            float marble = sin((w.x + w.y) * uBands + turbScale * PI);

            float t = clamp((marble + 1.0) * 0.5, 0.0, 1.0);
            if (uVeinSharpness != 1.0) t = pow(t, uVeinSharpness);

            vec3 col = palette_color(t);
            fragColor = vec4(col, 1.0);
        }
    """
}
