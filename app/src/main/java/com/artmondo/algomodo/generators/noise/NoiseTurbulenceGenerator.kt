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
 * GPU port. Per-octave: |noise| accumulated with optional erosion weighting
 * (each octave's weight depends on the previous octave's value). Optional
 * per-octave time drift for the "churn" boiling effect.
 */
class NoiseTurbulenceGenerator : GpuGenerator {

    override val id = "noise-turbulence"
    override val family = "noise"
    override val styleName = "Turbulence Noise"
    override val definition =
        "Turbulence noise created by summing absolute-value noise octaves, producing billowy, smoke-like patterns."
    override val algorithmNotes =
        "GPU shader. Each octave: |noise(x*freq, y*freq)|, accumulated with decreasing " +
        "amplitude. Erosion weights each octave by the previous one. Power curve shapes " +
        "contrast. Churn mode adds independent per-octave time drift."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema: List<Parameter> = listOf(
        Parameter.NumberParam("Scale", "scale", ParamGroup.COMPOSITION, "", 0.5f, 10f, 0.5f, 2.5f),
        Parameter.NumberParam("Octaves", "octaves", ParamGroup.COMPOSITION, "Number of absolute-value octaves", 1f, 10f, 1f, 6f),
        Parameter.NumberParam("Lacunarity", "lacunarity", ParamGroup.GEOMETRY, "", 1.5f, 4.0f, 0.1f, 2.0f),
        Parameter.NumberParam("Gain", "gain", ParamGroup.GEOMETRY, "", 0.2f, 0.8f, 0.05f, 0.5f),
        Parameter.NumberParam("Power", "power", ParamGroup.TEXTURE, "Gamma curve on turbulence output", 0.3f, 4.0f, 0.1f, 1.0f),
        Parameter.NumberParam("Warp Amount", "warpAmount", ParamGroup.COMPOSITION, "Domain warping — 0 = off", 0f, 2f, 0.1f, 0f),
        Parameter.NumberParam("Erosion", "erosion", ParamGroup.TEXTURE, "Weight each octave by the previous — creases erode into valleys", 0f, 1.0f, 0.1f, 0f),
        Parameter.SelectParam("Color Mode", "colorMode", ParamGroup.COLOR, "palette | bands | heat (fire map)", listOf("palette", "bands", "heat"), "palette"),
        Parameter.NumberParam("Band Count", "bandCount", ParamGroup.COLOR, "Contour bands (bands mode)", 2f, 24f, 1f, 6f),
        Parameter.SelectParam("Anim Mode", "animMode", ParamGroup.FLOW_MOTION, "drift | rotate | churn (boiling effect)", listOf("drift", "rotate", "churn"), "drift"),
        Parameter.NumberParam("Speed", "speed", ParamGroup.FLOW_MOTION, "", 0.1f, 3.0f, 0.1f, 0.5f)
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "scale" to 2.5f, "octaves" to 6f, "lacunarity" to 2.0f, "gain" to 0.5f,
        "power" to 1.0f, "warpAmount" to 0f, "erosion" to 0f,
        "colorMode" to "palette", "bandCount" to 6f,
        "animMode" to "drift", "speed" to 0.5f
    )

    override fun bindUniforms(
        programId: Int, params: Map<String, Any>, seed: Int, palette: Palette,
        quality: Quality, time: Float, width: Int, height: Int
    ) {
        val scale = (params["scale"] as? Number)?.toFloat() ?: 2.5f
        val octaves = ((params["octaves"] as? Number)?.toInt() ?: 6).coerceIn(1, 10)
        val lacunarity = (params["lacunarity"] as? Number)?.toFloat() ?: 2.0f
        val gain = (params["gain"] as? Number)?.toFloat() ?: 0.5f
        val power = (params["power"] as? Number)?.toFloat() ?: 1.0f
        val warpAmount = (params["warpAmount"] as? Number)?.toFloat() ?: 0f
        val erosion = (params["erosion"] as? Number)?.toFloat() ?: 0f
        val colorMode = (params["colorMode"] as? String) ?: "palette"
        val bandCount = ((params["bandCount"] as? Number)?.toInt() ?: 6).coerceAtLeast(1)
        val animMode = (params["animMode"] as? String) ?: "drift"
        val speed = (params["speed"] as? Number)?.toFloat() ?: 0.5f

        // "heat" mode is a label only — same palette mapping as palette mode.
        val colorModeId = if (colorMode == "bands") 1 else 0
        val animModeId = when (animMode) { "rotate" -> 1; "churn" -> 2; else -> 0 }
        val (seedOffX, seedOffY) = NoiseGlsl.seedToOffset(seed)

        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uScale"), scale)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uOctaves"), octaves)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uLacunarity"), lacunarity)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uGain"), gain)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uPower"), power)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uWarpAmount"), warpAmount)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uErosion"), erosion)
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
        uniform float uPower;
        uniform float uWarpAmount;
        uniform float uErosion;
        uniform int uColorMode;   // 0 = palette, 1 = bands
        uniform int uBandCount;
        uniform int uAnimMode;    // 0 = drift, 1 = rotate, 2 = churn
        uniform float uSpeed;
        uniform vec2 uSeedOffset;

        out vec4 fragColor;

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
            } else {
                // drift and churn both add a base drift
                p.x += uTime * uSpeed * 0.35;
                p.y += uTime * uSpeed * 0.2;
            }

            p += uSeedOffset;

            // Domain warp
            if (uWarpAmount > 0.0) {
                float wx = fbm(p + vec2(5.2, 1.3), 3, uLacunarity, uGain);
                float wy = fbm(p + vec2(1.7, 9.2), 3, uLacunarity, uGain);
                p += vec2(wx, wy) * uWarpAmount;
            }

            // Turbulence with erosion + optional per-octave churn drift
            float value = 0.0;
            float amplitude = 1.0;
            float frequency = 1.0;
            float maxValue = 0.0;
            float prevOctave = 1.0;
            for (int i = 0; i < 10; i++) {
                if (i >= uOctaves) break;
                vec2 sp = p * frequency;
                if (uAnimMode == 2) {
                    float fi = float(i + 1);
                    sp.x += uTime * uSpeed * 0.2 * fi * 0.7;
                    sp.y += uTime * uSpeed * 0.15 * fi * 0.5;
                }
                float n = abs(snoise(sp));
                float weight = (uErosion > 0.0)
                    ? amplitude * (1.0 - uErosion + uErosion * prevOctave)
                    : amplitude;
                value += weight * n;
                maxValue += weight;
                prevOctave = n;
                amplitude *= uGain;
                frequency *= uLacunarity;
            }

            float t = clamp(value / max(maxValue, 0.0001), 0.0, 1.0);
            if (uPower != 1.0) t = pow(t, uPower);

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
