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
 * GPU port of the ridged multifractal noise generator.
 * Per-octave: signal = (offset - |noise|)^2 * weight, weight cascades by gain
 * so bright ridges sharpen subsequent octaves. Sharpness is applied to the
 * final normalised value via pow().
 */
class NoiseRidgedGenerator : GpuGenerator {

    override val id = "noise-ridged"
    override val family = "noise"
    override val styleName = "Ridged Multifractal"
    override val definition =
        "Ridged multifractal noise that creates sharp, ridge-like mountain terrain patterns."
    override val algorithmNotes =
        "GPU shader. For each octave: signal = (offset - |noise|)^2, accumulated with " +
        "a gain-cascade weight so bright ridges enhance the next octave. " +
        "Sharpness post-applies a pow() curve. Ridge offset, domain warping, and multiple " +
        "color/animation modes supported."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema: List<Parameter> = listOf(
        Parameter.NumberParam("Scale", "scale", ParamGroup.COMPOSITION, "", 0.5f, 10f, 0.5f, 2f),
        Parameter.NumberParam("Octaves", "octaves", ParamGroup.COMPOSITION, "Number of ridged octaves", 1f, 10f, 1f, 6f),
        Parameter.NumberParam("Lacunarity", "lacunarity", ParamGroup.GEOMETRY, "", 1.5f, 4.0f, 0.1f, 2.0f),
        Parameter.NumberParam("Gain", "gain", ParamGroup.GEOMETRY, "Amplitude weight per octave and cascade strength", 0.1f, 0.9f, 0.05f, 0.5f),
        Parameter.NumberParam("Ridge Offset", "offset", ParamGroup.TEXTURE, "Ridge height offset — 1.0 = sharp peaks; lower = softer", 0.5f, 1.5f, 0.05f, 1.0f),
        Parameter.NumberParam("Sharpness", "sharpness", ParamGroup.TEXTURE, "Exponent on ridge signal — higher = knife-edge ridges", 1.0f, 5.0f, 0.5f, 2.0f),
        Parameter.NumberParam("Warp Amount", "warpAmount", ParamGroup.COMPOSITION, "Domain warping — 0 = off", 0f, 2f, 0.1f, 0f),
        Parameter.SelectParam("Color Mode", "colorMode", ParamGroup.COLOR, "palette | bands | peaks (ridges lit, valleys dark)", listOf("palette", "bands", "peaks"), "palette"),
        Parameter.NumberParam("Band Count", "bandCount", ParamGroup.COLOR, "Contour bands (bands mode)", 2f, 24f, 1f, 8f),
        Parameter.SelectParam("Anim Mode", "animMode", ParamGroup.FLOW_MOTION, "drift | rotate | sculpt (ridge offset oscillates)", listOf("drift", "rotate", "sculpt"), "drift"),
        Parameter.NumberParam("Speed", "speed", ParamGroup.FLOW_MOTION, "", 0.1f, 3.0f, 0.1f, 0.5f)
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "scale" to 2f, "octaves" to 6f, "lacunarity" to 2.0f, "gain" to 0.5f,
        "offset" to 1.0f, "sharpness" to 2.0f, "warpAmount" to 0f,
        "colorMode" to "palette", "bandCount" to 8f,
        "animMode" to "drift", "speed" to 0.5f
    )

    override fun bindUniforms(
        programId: Int, params: Map<String, Any>, seed: Int, palette: Palette,
        quality: Quality, time: Float, width: Int, height: Int
    ) {
        val scale = (params["scale"] as? Number)?.toFloat() ?: 2f
        val octaves = ((params["octaves"] as? Number)?.toInt() ?: 6).coerceIn(1, 10)
        val lacunarity = (params["lacunarity"] as? Number)?.toFloat() ?: 2f
        val gain = (params["gain"] as? Number)?.toFloat() ?: 0.5f
        val offset = (params["offset"] as? Number)?.toFloat() ?: 1.0f
        val sharpness = (params["sharpness"] as? Number)?.toFloat() ?: 2.0f
        val warpAmount = (params["warpAmount"] as? Number)?.toFloat() ?: 0f
        val colorMode = (params["colorMode"] as? String) ?: "palette"
        val bandCount = ((params["bandCount"] as? Number)?.toInt() ?: 8).coerceAtLeast(1)
        val animMode = (params["animMode"] as? String) ?: "drift"
        val speed = (params["speed"] as? Number)?.toFloat() ?: 0.5f

        val colorModeId = when (colorMode) { "bands" -> 1; "peaks" -> 2; else -> 0 }
        val animModeId = when (animMode) { "rotate" -> 1; "sculpt" -> 2; else -> 0 }
        val (seedOffX, seedOffY) = NoiseGlsl.seedToOffset(seed)

        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uScale"), scale)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uOctaves"), octaves)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uLacunarity"), lacunarity)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uGain"), gain)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uOffset"), offset)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uSharpness"), sharpness)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uWarpAmount"), warpAmount)
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
        uniform float uLacunarity;
        uniform float uGain;
        uniform float uOffset;
        uniform float uSharpness;
        uniform float uWarpAmount;
        uniform int uColorMode;   // 0 = palette, 1 = bands, 2 = peaks
        uniform int uBandCount;
        uniform int uAnimMode;    // 0 = drift, 1 = rotate, 2 = sculpt
        uniform float uSpeed;
        uniform vec2 uSeedOffset;

        out vec4 fragColor;

        void main() {
            float invScale = uScale / uResolution.x;
            vec2 p = gl_FragCoord.xy * invScale;
            vec2 c = vec2(uResolution.x * 0.5, uResolution.y * 0.5) * invScale;

            // Sculpt mode: oscillate effective ridge offset over time
            float effOffset = uOffset;
            if (uAnimMode == 2) {
                effOffset += 0.3 * sin(uTime * uSpeed * 0.8);
            }

            // Animation
            if (uAnimMode == 1) {
                vec2 d = p - c;
                float ang = uTime * uSpeed * 0.3;
                float cs = cos(ang); float sn = sin(ang);
                p = c + vec2(d.x * cs - d.y * sn, d.x * sn + d.y * cs);
            } else {
                // drift and sculpt both pan
                p.x += uTime * uSpeed * 0.3;
                p.y += uTime * uSpeed * 0.2;
            }

            p += uSeedOffset;

            // Domain warp
            if (uWarpAmount > 0.0) {
                float wx = fbm(p + vec2(5.2, 1.3), 3, uLacunarity, uGain);
                float wy = fbm(p + vec2(1.7, 9.2), 3, uLacunarity, uGain);
                p += vec2(wx, wy) * uWarpAmount;
            }

            // Ridged multifractal with offset + gain cascade
            float value = 0.0;
            float amplitude = 1.0;
            float frequency = 1.0;
            float weight = 1.0;
            float maxValue = 0.0;
            for (int i = 0; i < 10; i++) {
                if (i >= uOctaves) break;
                float n = snoise(p * frequency);
                float signal = effOffset - abs(n);
                signal = signal * signal;
                signal *= weight;
                weight = clamp(signal * uGain, 0.0, 1.0);
                value += signal * amplitude;
                maxValue += amplitude;
                amplitude *= uGain;
                frequency *= uLacunarity;
            }

            float t = clamp(value / max(maxValue, 0.0001), 0.0, 1.0);
            if (uSharpness != 1.0) t = pow(t, uSharpness);

            vec3 col;
            if (uColorMode == 1) {
                // Bands
                float bc = float(uBandCount);
                float band = clamp(floor(t * bc), 0.0, bc - 1.0);
                float denom = max(bc - 1.0, 1.0);
                col = palette_color(band / denom);
            } else if (uColorMode == 2) {
                // Peaks — valleys darken aggressively
                col = palette_color(t * t);
            } else {
                col = palette_color(t);
            }

            fragColor = vec4(col, 1.0);
        }
    """
}
