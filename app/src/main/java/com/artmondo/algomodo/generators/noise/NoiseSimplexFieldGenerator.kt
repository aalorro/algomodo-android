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
 * GPU port. The CPU version pre-built a 1024-entry LUT collapsing
 * style transform + bands + palette lookup. On GPU that LUT is unnecessary —
 * the same math runs branch-free per pixel at full speed. Style transforms
 * (smooth/ridged/turbulent/billow/veins) are applied to the raw fbm value
 * directly in the shader.
 */
class NoiseSimplexFieldGenerator : GpuGenerator {

    override val id = "noise-simplex-field"
    override val family = "noise"
    override val styleName = "Simplex Noise Field"
    override val definition =
        "Multi-octave simplex noise rendered as colored pixels with a family of noise styles."
    override val algorithmNotes =
        "GPU shader. Samples multi-octave simplex noise per pixel. Style selects the transform: " +
        "smooth fbm, ridged crests (1 - |fbm|)^2, turbulent folds |fbm|, billowing puffs, or " +
        "thin vein-like negative-space lines. Bands quantises into discrete contours."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema: List<Parameter> = listOf(
        Parameter.NumberParam("Scale", "scale", ParamGroup.COMPOSITION,
            "Noise frequency", 0.5f, 12f, 0.5f, 3f),
        Parameter.NumberParam("Octaves", "octaves", ParamGroup.COMPOSITION,
            "Noise layers — more = finer detail", 1f, 6f, 1f, 4f),
        Parameter.SelectParam("Style", "style", ParamGroup.GEOMETRY,
            "smooth: classic fbm | ridged: sharp crests | turbulent: folded creases | billow: puffy clouds | veins: thin negative-space lines",
            listOf("smooth", "ridged", "turbulent", "billow", "veins"), "ridged"),
        Parameter.NumberParam("Warp Amount", "warpAmount", ParamGroup.COMPOSITION,
            "Domain warping for organic distortion", 0f, 2f, 0.1f, 0f),
        Parameter.SelectParam("Color Mode", "colorMode", ParamGroup.COLOR,
            "palette: smooth gradient | bands: hard contour steps",
            listOf("palette", "bands"), "palette"),
        Parameter.NumberParam("Band Count", "bandCount", ParamGroup.COLOR,
            "Number of contour bands (bands mode)", 2f, 24f, 1f, 6f),
        Parameter.SelectParam("Anim Mode", "animMode", ParamGroup.FLOW_MOTION,
            "drift: pan through field | rotate: spin around center | pulse: wobble back and forth",
            listOf("drift", "rotate", "pulse"), "drift"),
        Parameter.NumberParam("Speed", "speed", ParamGroup.FLOW_MOTION,
            "", 0.1f, 3.0f, 0.1f, 0.5f)
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "scale" to 3f, "octaves" to 4f, "style" to "ridged", "warpAmount" to 0f,
        "colorMode" to "palette", "bandCount" to 6f, "animMode" to "drift", "speed" to 0.5f
    )

    override fun bindUniforms(
        programId: Int, params: Map<String, Any>, seed: Int, palette: Palette,
        quality: Quality, time: Float, width: Int, height: Int
    ) {
        val scale = (params["scale"] as? Number)?.toFloat() ?: 3f
        val octaves = ((params["octaves"] as? Number)?.toInt() ?: 4).coerceIn(1, 6)
        val style = (params["style"] as? String) ?: "ridged"
        val warpAmount = (params["warpAmount"] as? Number)?.toFloat() ?: 0f
        val colorMode = (params["colorMode"] as? String) ?: "palette"
        val bandCount = ((params["bandCount"] as? Number)?.toInt() ?: 6).coerceAtLeast(2)
        val animMode = (params["animMode"] as? String) ?: "drift"
        val speed = (params["speed"] as? Number)?.toFloat() ?: 0.5f

        val styleId = when (style) {
            "ridged" -> 1; "turbulent" -> 2; "billow" -> 3; "veins" -> 4
            else -> 0 // smooth
        }
        val colorModeId = if (colorMode == "bands") 1 else 0
        val animModeId = when (animMode) { "rotate" -> 1; "pulse" -> 2; else -> 0 }
        val (seedOffX, seedOffY) = NoiseGlsl.seedToOffset(seed)

        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uScale"), scale)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uOctaves"), octaves)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uStyle"), styleId)
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
        uniform int uStyle;       // 0 smooth, 1 ridged, 2 turbulent, 3 billow, 4 veins
        uniform float uWarpAmount;
        uniform int uColorMode;   // 0 palette, 1 bands
        uniform int uBandCount;
        uniform int uAnimMode;    // 0 drift, 1 rotate, 2 pulse
        uniform float uSpeed;
        uniform vec2 uSeedOffset;

        out vec4 fragColor;

        void main() {
            float invScale = uScale / uResolution.x;
            vec2 p = gl_FragCoord.xy * invScale;
            vec2 c = vec2(uResolution.x * 0.5, uResolution.y * 0.5) * invScale;

            // Animation
            if (uAnimMode == 1) {
                vec2 d = p - c;
                float ang = uTime * uSpeed * 0.5;
                float cs = cos(ang); float sn = sin(ang);
                p = c + vec2(d.x * cs - d.y * sn, d.x * sn + d.y * cs);
            } else if (uAnimMode == 2) {
                // pulse — additive sin/cos shift (CPU does not centre the pulse here)
                p.x += sin(uTime * uSpeed) * 0.9;
                p.y += cos(uTime * uSpeed * 1.3) * 0.9;
            } else {
                p.x += uTime * uSpeed * 0.35;
                p.y += uTime * uSpeed * 0.2;
            }

            p += uSeedOffset;

            // Domain warp (3-octave default in CPU)
            if (uWarpAmount > 0.0) {
                float wx = fbm(p + vec2(5.2, 1.3), 3, 2.0, 0.5);
                float wy = fbm(p + vec2(1.7, 9.2), 3, 2.0, 0.5);
                p += vec2(wx, wy) * uWarpAmount;
            }

            // Raw noise — single sample for octaves==1 to match CPU shortcut
            float raw = (uOctaves <= 1) ? snoise(p) : fbm(p, uOctaves, 2.0, 0.5);

            // Style transform (matches buildStyleColorLut)
            float tt;
            if (uStyle == 1) {
                float r = 1.0 - abs(raw);
                tt = r * r;
            } else if (uStyle == 2) {
                tt = abs(raw);
            } else if (uStyle == 3) {
                float b = abs(raw);
                tt = b * (2.0 - b);
            } else if (uStyle == 4) {
                float v = abs(raw);
                tt = 1.0 - v / (0.05 + v);
            } else {
                tt = (raw + 1.0) * 0.5;
            }
            float t = clamp(tt, 0.0, 1.0);

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
