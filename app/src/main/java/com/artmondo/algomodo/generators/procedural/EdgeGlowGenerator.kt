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
 * GPU-rendered neon edge-detection on noise fields. Four detection modes
 * (contour, gradient, ridge, circuit) drive glow intensity multiplying a
 * palette colour sampled from the underlying FBM value.
 *
 * Ported from the original CPU implementation; id, parameter schema and
 * defaults are preserved. Noise visuals are close but not bit-identical
 * (Ashima/Gustavson `snoise` vs the CPU `ValueNoise`).
 */
class EdgeGlowGenerator : GpuGenerator {

    override val id = "procedural-edge-glow"
    override val family = "procedural"
    override val styleName = "Edge + Glow"
    override val definition =
        "Neon edge detection on noise fields \u2014 glowing contour lines, gradient edges, ridges, and circuit-board step patterns."
    override val algorithmNotes =
        "Per-pixel fragment shader on the GPU. Evaluates multi-octave simplex FBM and detects " +
        "edges via four methods: contour finds iso-lines at regular intervals; gradient computes " +
        "finite differences; ridge uses Laplacian for curvature peaks; circuit detects quantized " +
        "step boundaries. Edge strength is converted to glow brightness via a power+exp falloff."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema = listOf(
        Parameter.SelectParam("Edge Mode", "edgeMode", ParamGroup.COMPOSITION,
            "contour: iso-line bands | gradient: edge detection | ridge: curvature | circuit: quantized steps",
            listOf("contour", "gradient", "ridge", "circuit"), "contour"),
        Parameter.NumberParam("Noise Scale", "noiseScale", ParamGroup.GEOMETRY,
            "Size of the noise field", 0.5f, 8f, 0.1f, 3.0f),
        Parameter.NumberParam("Edge Width", "edgeWidth", ParamGroup.TEXTURE,
            "Sharpness of edge lines", 0.5f, 5f, 0.1f, 1.5f),
        Parameter.NumberParam("Glow Radius", "glowRadius", ParamGroup.TEXTURE,
            "Soft glow falloff distance", 0f, 1f, 0.05f, 0.5f),
        Parameter.NumberParam("Glow Intensity", "glowIntensity", ParamGroup.TEXTURE,
            "Brightness multiplier for glow", 0.1f, 2f, 0.05f, 0.8f),
        Parameter.NumberParam("Octaves", "octaves", ParamGroup.COMPOSITION,
            "FBM layers", 1f, 4f, 1f, 2f),
        Parameter.NumberParam("Quantize", "quantize", ParamGroup.TEXTURE,
            "Number of contour bands", 2f, 16f, 1f, 6f),
        Parameter.NumberParam("Speed", "speed", ParamGroup.FLOW_MOTION,
            "Animation drift speed", 0.1f, 3f, 0.05f, 0.4f),
        Parameter.NumberParam("Reactivity", "reactivity", ParamGroup.FLOW_MOTION,
            "Audio reactivity strength", 0f, 2f, 0.1f, 1.0f)
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "edgeMode" to "contour", "noiseScale" to 3.0f, "edgeWidth" to 1.5f,
        "glowRadius" to 0.5f, "glowIntensity" to 0.8f, "octaves" to 2f,
        "quantize" to 6f, "speed" to 0.4f, "reactivity" to 1.0f
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
        val mode = (params["edgeMode"] as? String) ?: "contour"
        val scl = (params["noiseScale"] as? Number)?.toFloat() ?: 3.0f
        val edgeW = (params["edgeWidth"] as? Number)?.toFloat() ?: 1.5f
        val glowR = (params["glowRadius"] as? Number)?.toFloat() ?: 0.5f
        val glowI = (params["glowIntensity"] as? Number)?.toFloat() ?: 0.8f
        val octaves = ((params["octaves"] as? Number)?.toInt() ?: 2).coerceIn(1, 4)
        val quantize = ((params["quantize"] as? Number)?.toInt() ?: 6).coerceAtLeast(2)
        val speed = (params["speed"] as? Number)?.toFloat() ?: 0.4f
        val reactivity = (params["reactivity"] as? Number)?.toFloat() ?: 1.0f

        val modeId = when (mode) {
            "contour" -> 0
            "gradient" -> 1
            "ridge" -> 2
            else -> 3
        }

        val (seedOffX, seedOffY) = NoiseGlsl.seedToOffset(seed)

        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uModeId"), modeId)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uNoiseScale"), scl)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uEdgeWidth"), edgeW)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uGlowRadius"), glowR)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uGlowIntensity"), glowI)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uOctaves"), octaves)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uQuantize"), quantize)
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
        uniform float uNoiseScale;
        uniform float uEdgeWidth;
        uniform float uGlowRadius;
        uniform float uGlowIntensity;
        uniform int   uOctaves;
        uniform int   uQuantize;
        uniform float uSpeed;
        uniform float uReactivity;
        uniform vec2  uSeedOffset;

        out vec4 fragColor;

        void main() {
            float w = uResolution.x;
            float h = uResolution.y;
            float minDim = min(w, h);
            float invDim2 = 2.0 / minDim;
            float halfW = w * 0.5;
            float halfH = h * 0.5;

            // Audio reactivity
            float audioBass = uAudio.x * uReactivity;
            float audioHigh = uAudio.z * uReactivity;
            float effGlow  = uGlowIntensity * (1.0 + audioBass * 1.5);
            float effEdgeW = uEdgeWidth     * (1.0 + audioHigh * 0.5);

            float t = uTime * uSpeed;
            float tOff = t * 0.15;

            vec2 frag = gl_FragCoord.xy;
            float u = (frag.x - halfW) * invDim2;
            float v = (frag.y - halfH) * invDim2;

            float nx = u * uNoiseScale + uSeedOffset.x;
            float ny = v * uNoiseScale + tOff + uSeedOffset.y;

            float n = fbm(vec2(nx, ny), uOctaves, 2.0, 0.5);

            float pixelStep = uNoiseScale * invDim2;
            float gradEps  = pixelStep * effEdgeW * 0.4;
            float ridgeEps = pixelStep * effEdgeW * 1.7;
            float ridgeScale = 1.0 / max(0.0001, ridgeEps * ridgeEps);
            float inv2GradEps = 1.0 / (gradEps * 2.0);
            float circuitEps = 0.06;

            float Qf = float(uQuantize);
            float invQ = 1.0 / Qf;
            float contourPow = max(0.5, 4.0 - effEdgeW * 0.7);

            float edge = 0.0;
            if (uModeId == 0) {
                // CONTOUR
                float band = n * Qf;
                float frac = band - floor(band);
                float e = 1.0 - abs(frac * 2.0 - 1.0);
                edge = pow(e, contourPow);
            }
            else if (uModeId == 1) {
                // GRADIENT
                float gxd = (fbm(vec2(nx + gradEps, ny), uOctaves, 2.0, 0.5)
                           - fbm(vec2(nx - gradEps, ny), uOctaves, 2.0, 0.5)) * inv2GradEps;
                float gyd = (fbm(vec2(nx, ny + gradEps), uOctaves, 2.0, 0.5)
                           - fbm(vec2(nx, ny - gradEps), uOctaves, 2.0, 0.5)) * inv2GradEps;
                float e = min(1.0, sqrt(gxd * gxd + gyd * gyd));
                e = floor(e * Qf + 0.5) * invQ;
                edge = e * e * (3.0 - 2.0 * e);
            }
            else if (uModeId == 2) {
                // RIDGE (Laplacian)
                float nPx = fbm(vec2(nx + ridgeEps, ny), uOctaves, 2.0, 0.5);
                float nMx = fbm(vec2(nx - ridgeEps, ny), uOctaves, 2.0, 0.5);
                float nPy = fbm(vec2(nx, ny + ridgeEps), uOctaves, 2.0, 0.5);
                float nMy = fbm(vec2(nx, ny - ridgeEps), uOctaves, 2.0, 0.5);
                float laplacian = nPx + nMx + nPy + nMy - 4.0 * n;
                float e = min(1.0, abs(laplacian) * ridgeScale);
                e = floor(e * Qf + 0.5) * invQ;
                edge = e * e * (3.0 - 2.0 * e);
            }
            else {
                // CIRCUIT (quantized step detection)
                float nRight = snoise(vec2(nx + circuitEps, ny));
                float nDown  = snoise(vec2(nx, ny + circuitEps));
                float q1 = floor((n      * 0.5 + 0.5) * Qf);
                float q2 = floor((nRight * 0.5 + 0.5) * Qf);
                float q3 = floor((nDown  * 0.5 + 0.5) * Qf);
                edge = (q1 != q2 || q1 != q3) ? 1.0 : 0.0;
            }

            // Brightness from edge value
            float glowPow = max(0.6, 3.0 / max(0.3, effEdgeW));
            bool  hasGlowR = uGlowRadius > 0.01;
            float glowFalloff = hasGlowR ? (3.0 / uGlowRadius) : 200.0;
            float softMul = hasGlowR ? (0.3 + uGlowRadius * 1.7) : 0.0;
            float ambientGlow = hasGlowR
                ? min(1.0, exp(-glowFalloff) * effGlow * (0.02 + uGlowRadius * 0.2))
                : 0.0;

            float brightness;
            if (edge > 0.004) {
                float sharp = pow(edge, glowPow);
                float soft  = hasGlowR ? exp(-((1.0 - edge) * glowFalloff)) * softMul : 0.0;
                brightness = min(1.0, (sharp + soft) * effGlow);
            } else {
                brightness = ambientGlow;
            }

            float colorVal = clamp(n * 0.5 + 0.5, 0.0, 1.0);
            vec3 col = palette_color(colorVal) * brightness;
            fragColor = vec4(col, 1.0);
        }
    """

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float {
        val oct = (params["octaves"] as? Number)?.toInt() ?: 2
        val mode = (params["edgeMode"] as? String) ?: "contour"
        val modeMult = when (mode) { "gradient", "ridge" -> 1.5f; "circuit" -> 1.3f; else -> 1f }
        return (oct * 0.07f * modeMult + 0.1f).coerceIn(0.15f, 0.5f)
    }
}
