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
 * GPU port of the FBM terrain visualizer. Smooth/ridged/terraced styles,
 * domain warping at independent scale, contrast remap, and an optional
 * center-biased gradient color mode.
 */
class FbmTerrainGenerator : GpuGenerator {

    override val id = "fbm-terrain"
    override val family = "noise"
    override val styleName = "FBM Terrain"
    override val definition =
        "Fractal Brownian Motion terrain visualization that maps layered noise to palette colors like a topographic height map."
    override val algorithmNotes =
        "GPU shader. Multi-octave FBM noise per pixel with domain warping. " +
        "'ridged' mode uses (1 - |noise|)^2 for mountain ridges. " +
        "'terraced' mode quantises into elevation steps. " +
        "Contrast scales the value range. Color modes: height (full palette), gradient (center-biased)."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema: List<Parameter> = listOf(
        Parameter.NumberParam("Scale", "scale", ParamGroup.COMPOSITION, "Base frequency of noise", 0.2f, 10f, 0.1f, 2f),
        Parameter.NumberParam("Octaves", "octaves", ParamGroup.COMPOSITION, "Number of noise layers", 1f, 8f, 1f, 4f),
        Parameter.NumberParam("Lacunarity", "lacunarity", ParamGroup.GEOMETRY, "Frequency multiplier per octave", 1.5f, 3.5f, 0.1f, 2.0f),
        Parameter.NumberParam("Gain", "gain", ParamGroup.GEOMETRY, "Amplitude multiplier per octave", 0.2f, 0.9f, 0.05f, 0.5f),
        Parameter.NumberParam("Warp Strength", "warpStrength", ParamGroup.COMPOSITION, "Domain warping intensity", 0f, 2f, 0.1f, 0.5f),
        Parameter.NumberParam("Warp Scale", "warpScale", ParamGroup.COMPOSITION, "Size of warping pattern", 0.2f, 10f, 0.1f, 2f),
        Parameter.SelectParam("Anim Mode", "animMode", ParamGroup.FLOW_MOTION, "drift | rotate | pulse (zoom breath)", listOf("drift", "rotate", "pulse"), "drift"),
        Parameter.NumberParam("Speed", "speed", ParamGroup.FLOW_MOTION, "Animation speed", 0.1f, 3.0f, 0.1f, 0.5f),
        Parameter.SelectParam("Style", "style", ParamGroup.GEOMETRY, "smooth | ridged | terraced", listOf("smooth", "ridged", "terraced"), "smooth"),
        Parameter.NumberParam("Terrace Levels", "terraceLevels", ParamGroup.GEOMETRY, "Height steps (terraced mode)", 4f, 20f, 1f, 8f),
        Parameter.NumberParam("Contrast", "contrast", ParamGroup.TEXTURE, "Increase or decrease variation", 0.5f, 2f, 0.1f, 1f),
        Parameter.SelectParam("Color Mode", "colorMode", ParamGroup.COLOR, "height: full palette | gradient: center-biased", listOf("height", "gradient"), "height")
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "scale" to 2f, "octaves" to 4f, "lacunarity" to 2.0f, "gain" to 0.5f,
        "warpStrength" to 0.5f, "warpScale" to 2f, "style" to "smooth",
        "terraceLevels" to 8f, "contrast" to 1f, "colorMode" to "height",
        "animMode" to "drift", "speed" to 0.5f
    )

    override fun bindUniforms(
        programId: Int, params: Map<String, Any>, seed: Int, palette: Palette,
        quality: Quality, time: Float, width: Int, height: Int
    ) {
        val scale = (params["scale"] as? Number)?.toFloat() ?: 2f
        val octaves = ((params["octaves"] as? Number)?.toInt() ?: 4).coerceIn(1, 10)
        val lacunarity = (params["lacunarity"] as? Number)?.toFloat() ?: 2f
        val gain = (params["gain"] as? Number)?.toFloat() ?: 0.5f
        val warpStrength = (params["warpStrength"] as? Number)?.toFloat() ?: 0.5f
        val warpScale = (params["warpScale"] as? Number)?.toFloat() ?: 2f
        val style = (params["style"] as? String) ?: "smooth"
        val terraceLevels = ((params["terraceLevels"] as? Number)?.toInt() ?: 8).coerceAtLeast(2)
        val contrast = (params["contrast"] as? Number)?.toFloat() ?: 1f
        val colorMode = (params["colorMode"] as? String) ?: "height"
        val animMode = (params["animMode"] as? String) ?: "drift"
        val speed = (params["speed"] as? Number)?.toFloat() ?: 0.5f

        val styleId = when (style) { "ridged" -> 1; "terraced" -> 2; else -> 0 }
        val colorModeId = if (colorMode == "gradient") 1 else 0
        val animModeId = when (animMode) { "rotate" -> 1; "pulse" -> 2; else -> 0 }
        val (seedOffX, seedOffY) = NoiseGlsl.seedToOffset(seed)

        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uScale"), scale)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uOctaves"), octaves)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uLacunarity"), lacunarity)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uGain"), gain)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uWarpStrength"), warpStrength)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uWarpScale"), warpScale)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uStyle"), styleId)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uTerraceLevels"), terraceLevels)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uContrast"), contrast)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uColorMode"), colorModeId)
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
        uniform float uWarpStrength;
        uniform float uWarpScale;
        uniform int uStyle;          // 0 smooth, 1 ridged, 2 terraced
        uniform int uTerraceLevels;
        uniform float uContrast;
        uniform int uColorMode;      // 0 height, 1 gradient
        uniform int uAnimMode;       // 0 drift, 1 rotate, 2 pulse
        uniform float uSpeed;
        uniform vec2 uSeedOffset;

        out vec4 fragColor;

        void main() {
            float invScale = uScale / uResolution.x;
            float warpInvScale = uWarpScale / uResolution.x;
            vec2 p = gl_FragCoord.xy * invScale;
            vec2 c = vec2(uResolution.x * 0.5, uResolution.y * 0.5) * invScale;
            vec2 warpSrc = gl_FragCoord.xy * warpInvScale;

            // Animation
            if (uAnimMode == 1) {
                vec2 d = p - c;
                float ang = uTime * uSpeed * 0.3;
                float cs = cos(ang); float sn = sin(ang);
                p = c + vec2(d.x * cs - d.y * sn, d.x * sn + d.y * cs);
            } else if (uAnimMode == 2) {
                float s = 1.0 + 0.2 * sin(uTime * uSpeed);
                p = c + (p - c) * s;
                p.x += uTime * uSpeed * 0.1;
            } else {
                p.x += uTime * uSpeed * 0.3;
                p.y += uTime * uSpeed * 0.2;
            }

            p += uSeedOffset;
            warpSrc += uSeedOffset;

            // Domain warp (independent warpScale, 3-octave fbm)
            if (uWarpStrength > 0.0) {
                float wx = fbm(warpSrc + vec2(5.2, 1.3), 3, uLacunarity, uGain);
                float wy = fbm(warpSrc + vec2(1.7, 9.2), 3, uLacunarity, uGain);
                p += vec2(wx, wy) * uWarpStrength;
            }

            // Style sampling
            float value;
            if (uStyle == 1) {
                value = ridgedNoise(p, uOctaves, uLacunarity, uGain);
                value = clamp(value, 0.0, 1.0);
            } else {
                value = fbm(p, uOctaves, uLacunarity, uGain);
                value = clamp((value + 1.0) * 0.5, 0.0, 1.0);
            }

            // Contrast
            if (uContrast != 1.0) {
                value = clamp((value - 0.5) * uContrast + 0.5, 0.0, 1.0);
            }

            // Terracing — only applies in terraced style (CPU branches on style==terraced)
            if (uStyle == 2) {
                float tl = float(uTerraceLevels);
                float denom = max(tl - 1.0, 1.0);
                value = clamp(floor(value * tl) / denom, 0.0, 1.0);
            }

            // Colour mode
            float t = value;
            if (uColorMode == 1) {
                float centered = (value - 0.5) * 2.0;
                t = clamp((centered * abs(centered) + 1.0) * 0.5, 0.0, 1.0);
            }

            fragColor = vec4(palette_color(t), 1.0);
        }
    """
}
