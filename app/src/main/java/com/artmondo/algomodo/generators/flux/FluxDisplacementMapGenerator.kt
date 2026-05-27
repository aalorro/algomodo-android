package com.artmondo.algomodo.generators.flux

import android.opengl.GLES30
import com.artmondo.algomodo.data.palettes.Palette
import com.artmondo.algomodo.generators.GpuGenerator
import com.artmondo.algomodo.generators.ParamGroup
import com.artmondo.algomodo.generators.Parameter
import com.artmondo.algomodo.generators.Quality
import com.artmondo.algomodo.rendering.gl.NoiseGlsl
import com.artmondo.algomodo.rendering.gl.PaletteUniform

/**
 * GPU-rendered displacement map. A base pattern (stripes / grid / checkerboard /
 * circles) is warped by FBM simplex noise used as a UV displacement field.
 *
 * Ported from the original CPU implementation; id, parameter schema and
 * defaults are preserved so existing presets continue to load. Noise visuals
 * are very close but not bit-identical (Ashima/Gustavson `snoise` vs the
 * CPU `ValueNoise`).
 */
class FluxDisplacementMapGenerator : GpuGenerator {

    override val id = "flux-displacement-map"
    override val family = "flux"
    override val styleName = "Displacement Map"
    override val definition =
        "A base pattern (stripes, grid, checkerboard, circles) is warped by a secondary noise texture " +
        "used as a UV displacement field \u2014 the noise offsets each pixel\u2019s sample position, bending and " +
        "folding the underlying pattern into organic distortions."
    override val algorithmNotes =
        "Pure per-pixel fragment shader on the GPU. Two FBM evaluations produce (du, dv) " +
        "displacement vector; the displaced sample position is fed through a binary pattern " +
        "function (stripes/grid/checkerboard/circles) and the 0/1 result indexes the palette."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema: List<Parameter> = listOf(
        Parameter.SelectParam(
            "Base Pattern", "basePattern", ParamGroup.COMPOSITION,
            "stripes: sharp alternating bands | grid: line lattice | checkerboard: alternating tiles | circles: concentric rings",
            listOf("stripes", "grid", "checkerboard", "circles"), "stripes"
        ),
        Parameter.NumberParam(
            "Displacement Scale", "displacementScale", ParamGroup.GEOMETRY,
            "How far pixels are displaced by the noise field \u2014 higher = more warped",
            0.1f, 2f, 0.05f, 0.5f
        ),
        Parameter.NumberParam(
            "Noise Scale", "noiseScale", ParamGroup.GEOMETRY,
            "Frequency of the displacement noise \u2014 lower = larger features",
            0.5f, 5f, 0.1f, 2.0f
        ),
        Parameter.NumberParam(
            "Pattern Scale", "patternScale", ParamGroup.GEOMETRY,
            "Frequency of the base pattern \u2014 higher = more repetitions",
            2f, 30f, 1f, 10f
        ),
        Parameter.NumberParam(
            "Octaves", "octaves", ParamGroup.COMPOSITION,
            "FBM layers for the displacement field \u2014 more = finer detail",
            1f, 4f, 1f, 2f
        ),
        Parameter.NumberParam(
            "Speed", "speed", ParamGroup.FLOW_MOTION,
            "Animation drift speed of the displacement field",
            0.1f, 3f, 0.05f, 0.4f
        ),
        Parameter.NumberParam(
            "Audio Reactivity", "reactivity", ParamGroup.FLOW_MOTION,
            "Sensitivity to audio input (0 = none)",
            0f, 2f, 0.1f, 1.0f
        )
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "basePattern" to "stripes",
        "displacementScale" to 0.5f,
        "noiseScale" to 2.0f,
        "patternScale" to 10f,
        "octaves" to 2f,
        "speed" to 0.4f,
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
        val basePattern = (params["basePattern"] as? String) ?: "stripes"
        val displacementScale = (params["displacementScale"] as? Number)?.toFloat() ?: 0.5f
        val noiseScale = (params["noiseScale"] as? Number)?.toFloat() ?: 2.0f
        val patternScale = (params["patternScale"] as? Number)?.toFloat() ?: 10f
        val octaves = ((params["octaves"] as? Number)?.toInt() ?: 2).coerceIn(1, 4)
        val speed = (params["speed"] as? Number)?.toFloat() ?: 0.4f
        val reactivity = (params["reactivity"] as? Number)?.toFloat() ?: 1.0f

        val patId = when (basePattern) {
            "stripes" -> 0
            "grid" -> 1
            "checkerboard" -> 2
            else -> 3
        }

        val (seedOffX, seedOffY) = NoiseGlsl.seedToOffset(seed)

        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uPatId"), patId)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uDisplacementScale"), displacementScale)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uNoiseScale"), noiseScale)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uPatternScale"), patternScale)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uOctaves"), octaves)
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

        uniform int   uPatId;
        uniform float uDisplacementScale;
        uniform float uNoiseScale;
        uniform float uPatternScale;
        uniform int   uOctaves;
        uniform float uSpeed;
        uniform float uReactivity;
        uniform vec2  uSeedOffset;

        out vec4 fragColor;

        const float GRID_LINE_W = 0.15;

        void main() {
            float w = uResolution.x;
            float h = uResolution.y;
            float minDim = min(w, h);
            float invMinDim = 1.0 / minDim;

            // Audio reactivity
            float audioBass = uAudio.x * uReactivity;
            float audioHigh = uAudio.z * uReactivity;
            float effDisplacement = uDisplacementScale * (1.0 + audioBass * 2.0);
            float effPatternScale = uPatternScale * (1.0 + audioHigh * 0.5);

            float t = uTime * uSpeed;
            float tOffY = t * 0.15;

            float noiseInv = uNoiseScale * invMinDim;
            float dispScaleMinDim = effDisplacement * minDim;
            float patOverMin = effPatternScale * invMinDim;

            // Pixel coordinate
            vec2 frag = gl_FragCoord.xy;
            float pxN = frag.x * noiseInv + uSeedOffset.x;
            float pyN = frag.y * noiseInv + tOffY + uSeedOffset.y;

            // FBM for du, dv with phase offset to decorrelate
            float du = fbm(vec2(pxN, pyN), uOctaves, 2.0, 0.5);
            float dv = fbm(vec2(pxN + 37.7, pyN + 19.3), uOctaves, 2.0, 0.5);

            // Displaced sample coordinates
            float sampleX = frag.x + du * dispScaleMinDim;
            float sampleY = frag.y + dv * dispScaleMinDim;

            float brightness;
            if (uPatId == 0) {
                // Stripes
                float band = sampleX * patOverMin;
                int intBand = int(floor(band));
                brightness = float(((intBand % 2) + 2) % 2);
            } else if (uPatId == 1) {
                // Grid lines
                float fx = sampleX * patOverMin;
                float fy = sampleY * patOverMin;
                float fracX = fx - floor(fx);
                float fracY = fy - floor(fy);
                bool onLineX = (fracX < GRID_LINE_W) || (fracX > (1.0 - GRID_LINE_W));
                bool onLineY = (fracY < GRID_LINE_W) || (fracY > (1.0 - GRID_LINE_W));
                brightness = (onLineX || onLineY) ? 1.0 : 0.0;
            } else if (uPatId == 2) {
                // Checkerboard
                int cx = int(floor(sampleX * patOverMin));
                int cy = int(floor(sampleY * patOverMin));
                brightness = float((((cx + cy) % 2) + 2) % 2);
            } else {
                // Concentric rings
                float dist = sqrt(sampleX * sampleX + sampleY * sampleY);
                float band = dist * patOverMin;
                int intBand = int(floor(band));
                brightness = float(((intBand % 2) + 2) % 2);
            }

            vec3 col = palette_color(brightness);
            fragColor = vec4(col, 1.0);
        }
    """

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float {
        val oct = (params["octaves"] as? Number)?.toInt() ?: 2
        return (oct * 0.05f + 0.05f).coerceIn(0.1f, 0.3f)
    }
}
