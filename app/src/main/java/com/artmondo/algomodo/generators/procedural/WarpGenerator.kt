package com.artmondo.algomodo.generators.procedural

import android.opengl.GLES30
import com.artmondo.algomodo.data.palettes.Palette
import com.artmondo.algomodo.generators.GpuGenerator
import com.artmondo.algomodo.generators.ParamGroup
import com.artmondo.algomodo.generators.Parameter
import com.artmondo.algomodo.generators.Quality
import com.artmondo.algomodo.rendering.gl.NoiseGlsl
import com.artmondo.algomodo.rendering.gl.PaletteUniform

/**
 * GPU-rendered polar-coordinate warp generator. Four modes (spiral / ripple /
 * tunnel / kaleidoscope) apply mode-specific warps in polar space, followed by
 * a multi-layer domain-warp via simplex noise. Chromatic aberration shifts the
 * palette index for R/B channels.
 *
 * Ported from the original CPU implementation; id, parameter schema and
 * defaults are preserved. Noise visuals are close but not bit-identical
 * (Ashima/Gustavson `snoise` vs the CPU `ValueNoise`).
 */
class WarpGenerator : GpuGenerator {

    override val id = "warp"
    override val family = "procedural"
    override val styleName = "Warp"
    override val definition =
        "Coordinate-warp visual effects \u2014 spiral, tunnel, ripple, and kaleidoscope modes with chromatic aberration and multi-layer domain warping."
    override val algorithmNotes =
        "Per-pixel fragment shader on the GPU. Converts pixel coordinates to polar space and " +
        "applies mode-specific warps: spiral arms twist angle by radius, ripple pulses concentric " +
        "rings outward, tunnel maps depth via inverse radius for infinite zoom, kaleidoscope " +
        "mirrors angular segments. Up to 5 domain-warp layers displace coordinates through " +
        "simplex noise."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema = listOf(
        Parameter.SelectParam("Warp Mode", "warpMode", ParamGroup.COMPOSITION,
            "spiral: swirling arms | ripple: pulsing rings | tunnel: infinite zoom | kaleidoscope: mirrored folds",
            listOf("spiral", "ripple", "tunnel", "kaleidoscope"), "spiral"),
        Parameter.NumberParam("Warp Strength", "warpStrength", ParamGroup.GEOMETRY,
            "Intensity of coordinate warping", 0.1f, 5f, 0.1f, 2.0f),
        Parameter.NumberParam("Warp Layers", "layers", ParamGroup.COMPOSITION,
            "Domain-warp passes \u2014 more = richer organic flow", 1f, 5f, 1f, 3f),
        Parameter.NumberParam("Symmetry", "symmetry", ParamGroup.GEOMETRY,
            "Kaleidoscopic mirror folds (1 = off)", 1f, 12f, 1f, 1f),
        Parameter.NumberParam("Chromatic Shift", "chromaticShift", ParamGroup.COLOR,
            "RGB channel offset for prismatic color splitting", 0f, 1f, 0.05f, 0.15f),
        Parameter.NumberParam("Zoom", "zoom", ParamGroup.GEOMETRY,
            "Zoom into the warp field", 0.3f, 4f, 0.1f, 1.0f),
        Parameter.NumberParam("Speed", "speed", ParamGroup.FLOW_MOTION,
            "Animation drift speed", 0.1f, 3f, 0.05f, 0.7f),
        Parameter.NumberParam("Reactivity", "reactivity", ParamGroup.FLOW_MOTION,
            "Audio reactivity strength", 0f, 2f, 0.1f, 1.0f)
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "warpMode" to "spiral", "warpStrength" to 2.0f, "layers" to 3f,
        "symmetry" to 1f, "chromaticShift" to 0.15f, "zoom" to 1.0f, "speed" to 0.7f,
        "reactivity" to 1.0f
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
        val mode = (params["warpMode"] as? String) ?: "spiral"
        val warpStr = (params["warpStrength"] as? Number)?.toFloat() ?: 2.0f
        val layers = ((params["layers"] as? Number)?.toInt() ?: 3).coerceIn(1, 5)
        val sym = ((params["symmetry"] as? Number)?.toInt() ?: 1).coerceAtLeast(1)
        val ca = (params["chromaticShift"] as? Number)?.toFloat() ?: 0.15f
        val zoom = (params["zoom"] as? Number)?.toFloat() ?: 1.0f
        val speed = (params["speed"] as? Number)?.toFloat() ?: 0.7f
        val reactivity = (params["reactivity"] as? Number)?.toFloat() ?: 1.0f

        val modeId = when (mode) {
            "spiral" -> 0
            "ripple" -> 1
            "tunnel" -> 2
            else -> 3
        }

        val (seedOffX, seedOffY) = NoiseGlsl.seedToOffset(seed)

        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uModeId"), modeId)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uWarpStr"), warpStr)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uLayers"), layers)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uSymmetry"), sym)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uChromaticShift"), ca)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uZoom"), zoom)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uSpeed"), speed)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uReactivity"), reactivity)
        GLES30.glUniform2f(GLES30.glGetUniformLocation(programId, "uSeedOffset"), seedOffX, seedOffY)
    }

    override fun fragmentShaderSource(): String = """#version 300 es
        precision highp float;

        uniform vec2 uResolution;
        uniform float uTime;
        uniform vec3 uAudio;
        ${PaletteUniform.GLSL_HELPERS}
        ${NoiseGlsl.GLSL_HELPERS}

        uniform int   uModeId;
        uniform float uWarpStr;
        uniform int   uLayers;
        uniform int   uSymmetry;
        uniform float uChromaticShift;
        uniform float uZoom;
        uniform float uSpeed;
        uniform float uReactivity;
        uniform vec2  uSeedOffset;

        out vec4 fragColor;

        const float TAU = 6.28318530718;
        const int   MAX_LAYERS = 5;

        void main() {
            float w = uResolution.x;
            float h = uResolution.y;
            float minDim = min(w, h);
            float halfW = w * 0.5;
            float halfH = h * 0.5;
            float invScale = uZoom / (minDim * 0.5);

            // Audio reactivity
            float audioBass = uAudio.x * uReactivity;
            float audioMid  = uAudio.y * uReactivity;
            float warpAudio  = 1.0 + audioBass * 2.0;
            float rippleFreq = 6.0 + audioMid * 4.0;

            float t = uTime * uSpeed;
            float cosRot = cos(t * 0.2);
            float sinRot = sin(t * 0.2);

            float segAngle = (uSymmetry > 1) ? (TAU / float(uSymmetry)) : 0.0;
            bool  doCa = uChromaticShift > 0.01;
            float caScale = uChromaticShift * 0.6;

            vec2 frag = gl_FragCoord.xy;
            float cxRaw = (frag.x - halfW) * invScale;
            float cyRaw = (frag.y - halfH) * invScale;
            float radSq = cxRaw * cxRaw + cyRaw * cyRaw;
            float rad = sqrt(radSq);

            float ang = 0.0;
            if (uSymmetry > 1 || uModeId == 0 || uModeId == 2) {
                ang = atan(cyRaw, cxRaw);
                if (uSymmetry > 1) {
                    ang = mod(mod(ang, TAU) + TAU, TAU);
                    float segF = floor(ang / segAngle);
                    ang -= segF * segAngle;
                    // mirror odd segments
                    if (mod(segF, 2.0) >= 1.0) ang = segAngle - ang;
                }
            }

            float u; float v;
            if (uModeId == 0) {
                // SPIRAL
                u = rad * 2.0;
                v = ang + rad * uWarpStr * warpAudio + t;
            }
            else if (uModeId == 1) {
                // RIPPLE
                float disp = sin(rad * rippleFreq - t * 3.0) * uWarpStr * 0.3 * warpAudio;
                float invR = (rad > 0.001) ? (disp / rad) : 0.0;
                if (uSymmetry > 1) {
                    float cx2 = rad * cos(ang); float cy2 = rad * sin(ang);
                    u = cx2 + cx2 * invR; v = cy2 + cy2 * invR;
                } else {
                    u = cxRaw + cxRaw * invR; v = cyRaw + cyRaw * invR;
                }
            }
            else if (uModeId == 2) {
                // TUNNEL
                u = ang / TAU * 3.0;
                v = 0.5 / (rad + 0.01) + t * 2.0;
            }
            else {
                // KALEIDOSCOPE
                float cx2 = cxRaw; float cy2 = cyRaw;
                if (uSymmetry > 1) {
                    cx2 = rad * cos(ang);
                    cy2 = rad * sin(ang);
                }
                u = cx2 * cosRot - cy2 * sinRot + sin(rad * 3.0 - t)         * uWarpStr * 0.5;
                v = cx2 * sinRot + cy2 * cosRot + cos(rad * 2.0 + t * 0.7)   * uWarpStr * 0.5;
            }

            // Apply seed offset for determinism across seeds
            u += uSeedOffset.x * 0.01;
            v += uSeedOffset.y * 0.01;

            // Domain-warp layers (loop bounded by const, gated by uLayers)
            for (int l = 0; l < MAX_LAYERS; l++) {
                if (l >= uLayers) break;
                float fl  = 1.5 + float(l) * 0.8;
                float am  = 0.4 / (1.0 + float(l) * 0.5);
                float off = float(l) * 13.7;
                float lt  = t * (0.08 + float(l) * 0.02);
                float du = snoise(vec2(u * fl + off,        v * fl + lt)) * am;
                float dv = snoise(vec2(u * fl + off + 31.7, v * fl + lt + off)) * am;
                u += du; v += dv;
            }

            // Color: 2-octave noise
            float n1 = snoise(vec2(u * 2.0, v * 2.0));
            float n2 = snoise(vec2(u * 4.0, v * 4.0));
            float valG = clamp((n1 + 0.5 * n2) * 0.5 + 0.5, 0.0, 1.0);

            vec3 col;
            if (doCa) {
                float shift = n2 * caScale;
                float valR = clamp(valG + shift, 0.0, 1.0);
                float valB = clamp(valG - shift, 0.0, 1.0);
                vec3 cr = palette_color(valR);
                vec3 cg = palette_color(valG);
                vec3 cb = palette_color(valB);
                col = vec3(cr.r, cg.g, cb.b);
            } else {
                col = palette_color(valG);
            }
            fragColor = vec4(col, 1.0);
        }
    """

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float {
        val layers = (params["layers"] as? Number)?.toInt() ?: 3
        val ca = (params["chromaticShift"] as? Number)?.toFloat() ?: 0.15f
        val caMult = if (ca > 0.01f) 1.1f else 1f
        return (layers * 0.08f * caMult + 0.1f).coerceIn(0.15f, 0.5f)
    }
}
