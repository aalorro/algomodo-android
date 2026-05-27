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
 * GPU-rendered displacement-map generator. Per-pixel FBM (simplex) displacement
 * field offsets each sample's lookup coordinates; four modes (flow / fracture /
 * radial / wave) produce different colour-band schemes and warp shapes.
 *
 * Ported from the original CPU implementation; id, parameter schema and
 * defaults are preserved so existing presets continue to load. Noise visuals
 * are very close but not bit-identical (Ashima/Gustavson `snoise` vs the
 * CPU `ValueNoise`).
 */
class DisplacementGenerator : GpuGenerator {

    override val id = "procedural-displacement"
    override val family = "procedural"
    override val styleName = "Displacement"
    override val definition =
        "Noise-driven UV displacement mapping \u2014 pixels are offset through vector fields to create organic distortion, fracture, radial ripple, and wave effects."
    override val algorithmNotes =
        "Per-pixel fragment shader on the GPU. FBM simplex noise produces (du, dv) displacement; " +
        "four modes select colour-band schemes: flow (banded value-noise palette ramp), " +
        "fracture (cell-based hash + bright edge cracks), radial (warped-radius ripples with " +
        "per-ring colour bands), wave (dual-axis sinusoid bands). Chromatic aberration shifts " +
        "the palette index for R/B channels."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema = listOf(
        Parameter.SelectParam("Mode", "mode", ParamGroup.COMPOSITION,
            "flow: smooth | fracture: hard blocks | radial: ripples | wave: banded distortion",
            listOf("flow", "fracture", "radial", "wave"), "flow"),
        Parameter.NumberParam("Strength", "strength", ParamGroup.GEOMETRY,
            "How far pixels are displaced", 0.01f, 0.5f, 0.01f, 0.15f),
        Parameter.NumberParam("Scale", "scale", ParamGroup.GEOMETRY,
            "Size of the displacement noise field", 0.5f, 8f, 0.1f, 2.0f),
        Parameter.NumberParam("Octaves", "octaves", ParamGroup.COMPOSITION,
            "FBM layers \u2014 more = finer detail", 1f, 5f, 1f, 3f),
        Parameter.NumberParam("Distortion", "distortion", ParamGroup.TEXTURE,
            "Secondary domain warp before displacement lookup", 0f, 1f, 0.05f, 0.3f),
        Parameter.NumberParam("Chromatic Shift", "chromaticShift", ParamGroup.COLOR,
            "RGB channel offset for color splitting", 0f, 1f, 0.05f, 0.1f),
        Parameter.NumberParam("Speed", "speed", ParamGroup.FLOW_MOTION,
            "Animation drift speed", 0.1f, 3f, 0.05f, 0.5f),
        Parameter.NumberParam("Reactivity", "reactivity", ParamGroup.FLOW_MOTION,
            "Audio reactivity strength", 0f, 2f, 0.1f, 1.0f)
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "mode" to "flow", "strength" to 0.15f, "scale" to 2.0f, "octaves" to 3f,
        "distortion" to 0.3f, "chromaticShift" to 0.1f, "speed" to 0.5f,
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
        val mode = (params["mode"] as? String) ?: "flow"
        val strength = (params["strength"] as? Number)?.toFloat() ?: 0.15f
        val scale = (params["scale"] as? Number)?.toFloat() ?: 2.0f
        val octaves = ((params["octaves"] as? Number)?.toInt() ?: 3).coerceIn(1, 5)
        val distortion = (params["distortion"] as? Number)?.toFloat() ?: 0.3f
        val chromaticShift = (params["chromaticShift"] as? Number)?.toFloat() ?: 0.1f
        val speed = (params["speed"] as? Number)?.toFloat() ?: 0.5f
        val reactivity = (params["reactivity"] as? Number)?.toFloat() ?: 1.0f

        val nC = palette.colorInts().size

        val modeId = when (mode) {
            "flow" -> 0
            "fracture" -> 1
            "radial" -> 2
            else -> 3
        }

        val (seedOffX, seedOffY) = NoiseGlsl.seedToOffset(seed)

        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uModeId"), modeId)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uStrength"), strength)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uScale"), scale)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uOctaves"), octaves)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uDistortion"), distortion)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uChromaticShift"), chromaticShift)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uSpeed"), speed)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uReactivity"), reactivity)
        GLES30.glUniform2f(GLES30.glGetUniformLocation(programId, "uSeedOffset"), seedOffX, seedOffY)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uColorCount"), nC)
    }

    override fun fragmentShaderSource(): String = """#version 300 es
        precision highp float;

        uniform vec2 uResolution;
        uniform float uTime;
        uniform vec3 uAudio;
        ${PaletteUniform.GLSL_HELPERS}
        ${NoiseGlsl.GLSL_HELPERS}

        uniform int   uModeId;
        uniform float uStrength;
        uniform float uScale;
        uniform int   uOctaves;
        uniform float uDistortion;
        uniform float uChromaticShift;
        uniform float uSpeed;
        uniform float uReactivity;
        uniform vec2  uSeedOffset;
        uniform int   uColorCount;

        out vec4 fragColor;

        const float TAU = 6.28318530718;

        // Sample palette with chromatic-aberration offset.
        // Returns vec3(R, G, B) where R/B sample shifted palette positions.
        vec3 emitColor(float colorVal, vec2 warped, bool doCa, float caScale, float colorScl) {
            if (!doCa) {
                return palette_color(colorVal);
            }
            float shift = snoise(warped * colorScl + vec2(7.7, 3.3)) * caScale;
            float rVal = clamp(colorVal + shift, 0.0, 1.0);
            float gVal = clamp(colorVal,         0.0, 1.0);
            float bVal = clamp(colorVal - shift, 0.0, 1.0);
            vec3 cr = palette_color(rVal);
            vec3 cg = palette_color(gVal);
            vec3 cb = palette_color(bVal);
            return vec3(cr.r, cg.g, cb.b);
        }

        void main() {
            float w = uResolution.x;
            float h = uResolution.y;
            float minDim = min(w, h);
            float invDim2 = 2.0 / minDim;
            float halfW = w * 0.5;
            float halfH = h * 0.5;

            // Audio reactivity
            float audioBass = uAudio.x * uReactivity;
            float audioMid  = uAudio.y * uReactivity;
            float effStr = uStrength * (1.0 + audioBass * 3.0);
            float effScl = uScale    * (1.0 + audioMid  * 0.5);

            float t = uTime * uSpeed;

            // Centred coords (mirrors CPU u, v).
            vec2 frag = gl_FragCoord.xy;
            float u = (frag.x - halfW) * invDim2;
            float v = (frag.y - halfH) * invDim2;

            float nx = u * effScl + uSeedOffset.x;
            float ny = v * effScl + uSeedOffset.y;

            bool  doCa = uChromaticShift > 0.01;
            float caScale = uChromaticShift * 0.5;
            bool  doDist = uDistortion > 0.01;
            float warpAmt = uDistortion * 0.5;
            float distNScl = effScl * 2.5;
            float colorScl = effScl * 2.0;
            float dist2 = uDistortion * 2.0;

            float nCf = max(1.0, float(uColorCount));
            float nCm1f = max(1.0, nCf - 1.0);

            float colorVal = 0.0;
            vec2  warped = vec2(u, v);

            if (uModeId == 0) {
                // ── FLOW ───────────────────────────────────────────────
                float tOff1x = t * 0.1; float tOff1y = t * 0.07;
                float tOff2x = t * 0.07; float tOff2y = t * 0.1;
                float n1 = fbm(vec2(nx + tOff1x, ny + tOff1y), uOctaves, 2.0, 0.5);
                float n2 = fbm(vec2(nx + 31.7 + tOff2x, ny + 17.3 + tOff2y), uOctaves, 2.0, 0.5);
                float wu = u + n1 * effStr;
                float wv = v + n2 * effStr;
                if (doDist) {
                    wu += snoise(vec2(nx * distNScl + 50.0, ny * distNScl)) * warpAmt;
                    wv += snoise(vec2(nx * distNScl,        ny * distNScl + 50.0)) * warpAmt;
                }
                float cn = snoise(vec2(wu * colorScl, wv * colorScl)) * 0.5 + 0.5;
                float flowBandF = floor(cn * nCf * 2.0);
                float modBand = mod(flowBandF, nCf);
                colorVal = clamp(modBand / nCm1f, 0.0, 1.0);
                warped = vec2(wu, wv);
            }
            else if (uModeId == 1) {
                // ── FRACTURE ───────────────────────────────────────────
                float fractCells = 3.0 + uDistortion * 12.0;
                float tSinHalf = sin(t * 0.5);
                float tCosHalf = cos(t * 0.5);
                float wN1 = fbm(vec2(nx + 50.0, ny + 50.0), uOctaves, 2.0, 0.5);
                float wN2 = fbm(vec2(nx + 80.0, ny + 80.0), uOctaves, 2.0, 0.5);
                float warpU = u + wN1 * effStr * 0.5;
                float warpV = v + wN2 * effStr * 0.5;
                float cellXf = floor((warpU + 1.0) * fractCells);
                float cellYf = floor((warpV + 1.0) * fractCells);
                float ch1 = snoise(vec2(cellXf * 1.37 + 0.5, cellYf * 2.71 + 0.5));
                float ch2 = snoise(vec2(cellXf * 2.71 + 0.5, cellYf * 1.37 + 0.5));
                float cellDx = ch1 * effStr * (tSinHalf * cos(ch1 * TAU) + tCosHalf * sin(ch1 * TAU));
                float cellDy = ch2 * effStr * (tCosHalf * cos(ch2 * TAU) - tSinHalf * sin(ch2 * TAU));
                float wu = u + cellDx;
                float wv = v + cellDy;
                float fracU = (u + 1.0) * fractCells - cellXf;
                float fracV = (v + 1.0) * fractCells - cellYf;
                float edgeDist = min(min(fracU, 1.0 - fracU), min(fracV, 1.0 - fracV));
                float cellColor = ch1 * 0.5 + 0.5;
                float edgeBright = step(edgeDist, 0.08);
                colorVal = clamp(cellColor * (1.0 - edgeBright) + edgeBright, 0.0, 1.0);
                warped = vec2(wu, wv);
            }
            else if (uModeId == 2) {
                // ── RADIAL ─────────────────────────────────────────────
                float radialFreq = TAU * (3.0 + uDistortion * 8.0);
                float radialTime = t * 2.5;
                float radialFreqOverTAU = radialFreq / TAU;
                float radialTimeColor = radialTime * 0.1;
                float nWarp  = fbm(vec2(nx + 70.0, ny + 70.0), uOctaves, 2.0, 0.5);
                float angWarp = snoise(vec2(nx * 2.0 + 40.0, ny * 2.0 + 40.0)) * 0.8;
                float rad = sqrt(u * u + v * v);
                float warpedRad = rad + nWarp * 0.15;
                float ripple = sin(warpedRad * radialFreq - radialTime);
                float dr = ripple * effStr * (1.0 + nWarp * dist2);
                float invRad = (rad > 0.001) ? (1.0 / rad) : 0.0;
                float wu = u + u * invRad * dr;
                float wv = v + v * invRad * dr;
                if (doDist) {
                    wu += snoise(vec2(nx * distNScl + 50.0, ny * distNScl)) * warpAmt;
                    wv += snoise(vec2(nx * distNScl,        ny * distNScl + 50.0)) * warpAmt;
                }
                float ang = atan(v, u);
                float colorRad = rad + nWarp * 0.2;
                float colorAng = ang + angWarp;
                float ringBandF = floor(colorRad * radialFreqOverTAU - radialTimeColor);
                float perRingN = snoise(vec2(ringBandF * 0.73 + 5.0, colorAng * 1.5 + ringBandF * 0.4)) * 0.5 + 0.5;
                float colorBandF = floor(perRingN * nCf);
                float colorBandMod = mod(colorBandF, nCf);
                if (colorBandMod < 0.0) colorBandMod += nCf;
                colorVal = clamp(colorBandMod / nCm1f, 0.0, 1.0);
                warped = vec2(wu, wv);
            }
            else {
                // ── WAVE ───────────────────────────────────────────────
                float waveFreq = 4.0 + uDistortion * 14.0;
                float waveFreq07 = waveFreq * 0.7;
                float waveTx = t * 1.8;
                float waveTy = t * 1.3;
                float nWarp  = fbm(vec2(nx + 90.0, ny + 90.0), uOctaves, 2.0, 0.5);
                float nWarp2 = snoise(vec2(nx * 2.0 + 30.0, ny * 2.0 + 30.0));
                float phaseV = v * waveFreq    + waveTx + nWarp  * 1.5;
                float phaseU = u * waveFreq07 + waveTy + nWarp2 * 1.5;
                float waveX = sin(phaseV) * effStr * (1.0 + nWarp * uDistortion);
                float waveY = sin(phaseU) * effStr * 0.6 * (1.0 + nWarp * uDistortion);
                float wu = u + waveX;
                float wv = v + waveY;
                if (doDist) {
                    wu += snoise(vec2(nx * distNScl + 50.0, ny * distNScl)) * warpAmt;
                    wv += snoise(vec2(nx * distNScl,        ny * distNScl + 50.0)) * warpAmt;
                }
                float hBand = floor(phaseV);
                float vBand = floor(phaseU);
                float bandSum = mod(hBand + vBand, nCf);
                if (bandSum < 0.0) bandSum += nCf;
                colorVal = clamp(bandSum / nCm1f, 0.0, 1.0);
                warped = vec2(wu, wv);
            }

            vec3 col = emitColor(colorVal, warped, doCa, caScale, colorScl);
            fragColor = vec4(col, 1.0);
        }
    """

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float {
        val oct = (params["octaves"] as? Number)?.toInt() ?: 3
        val mode = (params["mode"] as? String) ?: "flow"
        val modeMult = if (mode == "radial") 1.2f else 1f
        return (oct * 0.05f * modeMult + 0.05f).coerceIn(0.1f, 0.4f)
    }
}
