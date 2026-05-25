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
 * GPU port. Iterative domain warp: start with a coordinate, evaluate FBM
 * noise on offset versions of it, use the result as a displacement, then
 * do a final readout (smooth fbm | ridged | turbulent).
 *
 * Default lacunarity/gain match SimplexNoise.fbm defaults (2.0 / 0.5) since
 * the CPU code calls `noise.fbm(x, y, octaves)` without explicit values.
 */
class NoiseDomainWarpGenerator : GpuGenerator {

    override val id = "noise-domain-warp"
    override val family = "noise"
    override val styleName = "Domain Warp Noise"
    override val definition =
        "Iterative domain warping that distorts noise coordinates with noise, producing swirling, organic patterns."
    override val algorithmNotes =
        "GPU shader. For each pixel: start with base coord, then for each warp iteration evaluate " +
        "FBM noise and use the result to offset x/y by warp strength. Final value supports " +
        "smooth, ridged, or turbulent readout. Band quantization and multiple animation modes supported."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema: List<Parameter> = listOf(
        Parameter.NumberParam("Scale", "scale", ParamGroup.COMPOSITION, "", 0.5f, 8f, 0.5f, 2.0f),
        Parameter.NumberParam("Octaves", "octaves", ParamGroup.COMPOSITION, "Octaves for final readout noise", 1f, 8f, 1f, 5f),
        Parameter.NumberParam("Warp Strength", "warpStrength", ParamGroup.COMPOSITION, "How far coordinates are displaced", 0.0f, 4.0f, 0.1f, 1.5f),
        Parameter.NumberParam("Warp Octaves", "warpOctaves", ParamGroup.GEOMETRY, "Complexity of the warp field", 1f, 6f, 1f, 3f),
        Parameter.SelectParam("Iterations", "iterations", ParamGroup.GEOMETRY, "1: single warp | 2: double | 3: triple", listOf("1", "2", "3"), "1"),
        Parameter.SelectParam("Readout Style", "readoutStyle", ParamGroup.TEXTURE, "smooth | ridged | turbulent", listOf("smooth", "ridged", "turbulent"), "smooth"),
        Parameter.SelectParam("Color Mode", "colorMode", ParamGroup.COLOR, "", listOf("palette", "bands"), "palette"),
        Parameter.NumberParam("Band Count", "bandCount", ParamGroup.COLOR, "Contour bands (bands mode)", 2f, 24f, 1f, 8f),
        Parameter.SelectParam("Anim Mode", "animMode", ParamGroup.FLOW_MOTION, "drift | rotate | flow (warp morphs independently)", listOf("drift", "rotate", "flow"), "flow"),
        Parameter.NumberParam("Speed", "speed", ParamGroup.FLOW_MOTION, "", 0.1f, 3.0f, 0.1f, 0.5f)
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "scale" to 2.0f, "octaves" to 5f, "warpStrength" to 1.5f,
        "warpOctaves" to 3f, "iterations" to "1", "readoutStyle" to "smooth",
        "colorMode" to "palette", "bandCount" to 8f, "animMode" to "flow", "speed" to 0.5f
    )

    override fun bindUniforms(
        programId: Int, params: Map<String, Any>, seed: Int, palette: Palette,
        quality: Quality, time: Float, width: Int, height: Int
    ) {
        val scale = (params["scale"] as? Number)?.toFloat() ?: 2.0f
        val octaves = ((params["octaves"] as? Number)?.toInt() ?: 5).coerceIn(1, 10)
        val warpStrength = (params["warpStrength"] as? Number)?.toFloat() ?: 1.5f
        val warpOctaves = ((params["warpOctaves"] as? Number)?.toInt() ?: 3).coerceIn(1, 10)
        val iterations = (((params["iterations"] as? String) ?: "1").toIntOrNull() ?: 1).coerceIn(1, 3)
        val readoutStyle = (params["readoutStyle"] as? String) ?: "smooth"
        val colorMode = (params["colorMode"] as? String) ?: "palette"
        val bandCount = ((params["bandCount"] as? Number)?.toInt() ?: 8).coerceAtLeast(1)
        val animMode = (params["animMode"] as? String) ?: "flow"
        val speed = (params["speed"] as? Number)?.toFloat() ?: 0.5f

        val readoutId = when (readoutStyle) { "ridged" -> 1; "turbulent" -> 2; else -> 0 }
        val colorModeId = if (colorMode == "bands") 1 else 0
        val animModeId = when (animMode) { "rotate" -> 1; "flow" -> 2; else -> 0 }
        val (seedOffX, seedOffY) = NoiseGlsl.seedToOffset(seed)

        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uScale"), scale)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uOctaves"), octaves)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uWarpStrength"), warpStrength)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uWarpOctaves"), warpOctaves)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uIterations"), iterations)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uReadoutStyle"), readoutId)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uColorMode"), colorModeId)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uBandCount"), bandCount)
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
        uniform int uOctaves;
        uniform float uWarpStrength;
        uniform int uWarpOctaves;
        uniform int uIterations;     // 1..3
        uniform int uReadoutStyle;   // 0 = smooth, 1 = ridged, 2 = turbulent
        uniform int uColorMode;      // 0 = palette, 1 = bands
        uniform int uBandCount;
        uniform int uAnimMode;       // 0 = drift, 1 = rotate, 2 = flow
        uniform float uSpeed;
        uniform vec2 uSeedOffset;

        out vec4 fragColor;

        const vec2 OFF_A = vec2(1.7, 9.2);
        const vec2 OFF_B = vec2(5.3, 1.3);

        void main() {
            float invScale = uScale / uResolution.x;
            vec2 p = gl_FragCoord.xy * invScale;
            vec2 c = vec2(uResolution.x * 0.5, uResolution.y * 0.5) * invScale;

            // Base animation
            if (uAnimMode == 1) {
                vec2 d = p - c;
                float ang = uTime * uSpeed * 0.3;
                float cs = cos(ang); float sn = sin(ang);
                p = c + vec2(d.x * cs - d.y * sn, d.x * sn + d.y * cs);
            } else if (uAnimMode == 0) {
                p.x += uTime * uSpeed * 0.3;
                p.y += uTime * uSpeed * 0.2;
            } else {
                // flow
                p.x += uTime * uSpeed * 0.1;
                p.y += uTime * uSpeed * 0.07;
            }

            p += uSeedOffset;

            // Iterative domain warping. CPU uses default lacunarity=2, gain=0.5.
            float flowTimeX = uTime * uSpeed * 0.2;
            float flowTimeY = uTime * uSpeed * 0.15;
            for (int i = 0; i < 3; i++) {
                if (i >= uIterations) break;
                float fi = float(i + 1);
                vec2 inA, inB;
                if (uAnimMode == 2) {
                    inA = p + OFF_A + vec2(flowTimeX * fi, flowTimeY * fi);
                    inB = p + OFF_B + vec2(flowTimeY * fi, flowTimeX * fi);
                } else {
                    inA = p + OFF_A;
                    inB = p + OFF_B;
                }
                float warpX = fbm(inA, uWarpOctaves, 2.0, 0.5);
                float warpY = fbm(inB, uWarpOctaves, 2.0, 0.5);
                p += vec2(warpX, warpY) * uWarpStrength;
            }

            // Final readout at warped coordinate
            float value;
            float t;
            if (uReadoutStyle == 1) {
                value = ridgedNoise(p, uOctaves, 2.0, 0.5);
                t = clamp(value, 0.0, 1.0);
            } else if (uReadoutStyle == 2) {
                value = turbulenceNoise(p, uOctaves, 2.0, 0.5);
                t = clamp(value, 0.0, 1.0);
            } else {
                value = fbm(p, uOctaves, 2.0, 0.5);
                t = clamp((value + 1.0) * 0.5, 0.0, 1.0);
            }

            vec3 col;
            if (uColorMode == 1) {
                float bc = float(uBandCount);
                float band = clamp(floor(t * bc), 0.0, bc - 1.0);
                float denom = max(bc - 1.0, 1.0);
                col = palette_color(band / denom);
            } else {
                col = palette_color(t);
            }

            fragColor = vec4(col, 1.0);
        }
    """
}
