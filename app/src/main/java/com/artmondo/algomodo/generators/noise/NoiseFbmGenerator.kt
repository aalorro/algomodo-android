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
 * GPU-rendered FBM simplex noise. Pure pixel-parallel: every pixel evaluates
 * multi-octave simplex noise (with optional domain warp), maps through a
 * power curve, and looks up the palette in either smooth or banded mode.
 *
 * Ported from the original CPU implementation; param schema, defaults and
 * id are preserved so existing presets continue to load.
 */
class NoiseFbmGenerator : GpuGenerator {

    override val id = "noise-fbm"
    override val family = "noise"
    override val styleName = "FBM Noise"
    override val definition =
        "Pure Fractal Brownian Motion noise rendered pixel-by-pixel with configurable colour mapping."
    override val algorithmNotes =
        "Each pixel is evaluated as multi-octave FBM simplex noise on the GPU. " +
        "'palette' mode smoothly interpolates through the palette. " +
        "'bands' mode quantises the noise into discrete contour bands. " +
        "Domain warping, power curve, and multiple animation modes are supported."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema: List<Parameter> = listOf(
        Parameter.NumberParam("Scale", "scale", ParamGroup.COMPOSITION, "", 0.5f, 10f, 0.5f, 2f),
        Parameter.NumberParam("Octaves", "octaves", ParamGroup.COMPOSITION, "Number of noise layers summed", 1f, 10f, 1f, 6f),
        Parameter.NumberParam("Lacunarity", "lacunarity", ParamGroup.GEOMETRY, "Frequency multiplier per octave", 1.5f, 4.0f, 0.1f, 2.0f),
        Parameter.NumberParam("Gain", "gain", ParamGroup.GEOMETRY, "Amplitude multiplier per octave", 0.2f, 0.8f, 0.05f, 0.5f),
        Parameter.NumberParam("Warp Amount", "warpAmount", ParamGroup.COMPOSITION, "Domain warping for organic distortion", 0f, 2f, 0.1f, 0f),
        Parameter.NumberParam("Power", "power", ParamGroup.TEXTURE, "Gamma curve — >1 darkens lows, <1 lifts shadows", 0.3f, 4.0f, 0.1f, 1.0f),
        Parameter.SelectParam("Color Mode", "colorMode", ParamGroup.COLOR, "", listOf("palette", "bands"), "palette"),
        Parameter.NumberParam("Band Count", "bandCount", ParamGroup.COLOR, "Number of contour bands (bands mode)", 2f, 24f, 1f, 8f),
        Parameter.SelectParam("Anim Mode", "animMode", ParamGroup.FLOW_MOTION, "drift: pan | rotate: spin | pulse: oscillate scale", listOf("drift", "rotate", "pulse"), "drift"),
        Parameter.NumberParam("Speed", "speed", ParamGroup.FLOW_MOTION, "", 0.1f, 3.0f, 0.1f, 0.5f)
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "scale" to 2f, "octaves" to 6f, "lacunarity" to 2.0f, "gain" to 0.5f,
        "warpAmount" to 0f, "power" to 1.0f, "colorMode" to "palette",
        "bandCount" to 8f, "animMode" to "drift", "speed" to 0.5f
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
        val scale = (params["scale"] as? Number)?.toFloat() ?: 2f
        val octaves = ((params["octaves"] as? Number)?.toInt() ?: 6).coerceIn(1, 10)
        val lacunarity = (params["lacunarity"] as? Number)?.toFloat() ?: 2f
        val gain = (params["gain"] as? Number)?.toFloat() ?: 0.5f
        val warpAmount = (params["warpAmount"] as? Number)?.toFloat() ?: 0f
        val power = (params["power"] as? Number)?.toFloat() ?: 1.0f
        val colorMode = (params["colorMode"] as? String) ?: "palette"
        val bandCount = ((params["bandCount"] as? Number)?.toInt() ?: 8).coerceAtLeast(1)
        val animMode = (params["animMode"] as? String) ?: "drift"
        val speed = (params["speed"] as? Number)?.toFloat() ?: 0.5f

        val animModeId = when (animMode) {
            "rotate" -> 1
            "pulse" -> 2
            else -> 0 // drift
        }
        val colorModeId = if (colorMode == "bands") 1 else 0

        val (seedOffX, seedOffY) = NoiseGlsl.seedToOffset(seed)

        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uScale"), scale)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uOctaves"), octaves)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uLacunarity"), lacunarity)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uGain"), gain)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uWarpAmount"), warpAmount)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uPower"), power)
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
        uniform float uWarpAmount;
        uniform float uPower;
        uniform int uColorMode;   // 0 = palette (smooth), 1 = bands
        uniform int uBandCount;
        uniform int uAnimMode;    // 0 = drift, 1 = rotate, 2 = pulse
        uniform float uSpeed;
        uniform vec2 uSeedOffset;

        out vec4 fragColor;

        void main() {
            // Mirror CPU coordinate convention:
            //   invScale = scale / w
            //   nx = px * invScale, ny = py * invScale
            //   cx = w/2 * invScale, cy = h/2 * invScale
            float invScale = uScale / uResolution.x;
            vec2 p = gl_FragCoord.xy * invScale;
            vec2 c = vec2(uResolution.x * 0.5, uResolution.y * 0.5) * invScale;

            // Animation
            if (uAnimMode == 0) {
                // drift
                p.x += uTime * uSpeed * 0.4;
                p.y += uTime * uSpeed * 0.25;
            } else if (uAnimMode == 1) {
                // rotate around centre
                vec2 d = p - c;
                float ang = uTime * uSpeed * 0.3;
                float cs = cos(ang);
                float sn = sin(ang);
                p = c + vec2(d.x * cs - d.y * sn, d.x * sn + d.y * cs);
            } else {
                // pulse — oscillating scale around centre, slow drift
                float s = 1.0 + 0.2 * sin(uTime * uSpeed);
                p = c + (p - c) * s;
                p.x += uTime * uSpeed * 0.1;
            }

            // Seed offset moves the noise field — different seeds, different patterns.
            p += uSeedOffset;

            // Domain warp (matches CPU: 3-octave fbm of offset coords)
            if (uWarpAmount > 0.0) {
                float wx = fbm(p + vec2(5.2, 1.3), 3, uLacunarity, uGain);
                float wy = fbm(p + vec2(1.7, 9.2), 3, uLacunarity, uGain);
                p += vec2(wx, wy) * uWarpAmount;
            }

            float value = fbm(p, uOctaves, uLacunarity, uGain);
            float t = clamp((value + 1.0) * 0.5, 0.0, 1.0);

            // Power curve
            if (uPower != 1.0) {
                t = pow(t, uPower);
            }

            // Colour mapping
            vec3 col;
            if (uColorMode == 1) {
                // Bands
                float bc = float(uBandCount);
                float band = floor(t * bc);
                band = clamp(band, 0.0, bc - 1.0);
                float denom = max(bc - 1.0, 1.0);
                col = palette_color(band / denom);
            } else {
                col = palette_color(t);
            }

            fragColor = vec4(col, 1.0);
        }
    """
}
