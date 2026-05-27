package com.artmondo.algomodo.generators.animation

import android.opengl.GLES30
import com.artmondo.algomodo.core.rng.SeededRNG
import com.artmondo.algomodo.data.palettes.Palette
import com.artmondo.algomodo.generators.GpuGenerator
import com.artmondo.algomodo.generators.ParamGroup
import com.artmondo.algomodo.generators.Parameter
import com.artmondo.algomodo.generators.Quality
import com.artmondo.algomodo.rendering.gl.PaletteUniform
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * GPU-rendered circular / spiral / plane wave interference. Up to 10 wave
 * sources whose animated positions and per-source parameters are computed
 * CPU-side from the seed and uploaded as uniform arrays; the fragment
 * shader sums each source's contribution at every pixel.
 *
 * Ported from the original CPU implementation; id, parameter schema and
 * defaults are preserved so existing presets continue to load.
 */
class WaveInterferenceGenerator : GpuGenerator {

    override val id = "wave-interference"
    override val family = "animation"
    override val styleName = "Wave Interference"
    override val definition = "Circular waves from random sources producing animated interference patterns mapped to the palette."
    override val algorithmNotes =
        "Per-pixel fragment shader on the GPU. Source positions, phases, drift orbits and per-source " +
        "wave types are scattered from the seed CPU-side, then animated each frame and uploaded as " +
        "uniform arrays. For each pixel the shader sums sin(2\u03c0r/\u03bb \u2212 \u03c9t + \u03c6) * exp(\u2212\u03b4r) " +
        "across all sources (circular / spiral with atan2 swirl / plane with directional projection) " +
        "and maps the normalised result through palette / bichrome / phase colour modes."
    override val supportsVector = false
    override val supportsAnimation = true

    companion object {
        private const val MAX_SOURCES = 10
    }

    override val parameterSchema = listOf(
        Parameter.NumberParam(
            name = "Sources", key = "waveCount", group = ParamGroup.COMPOSITION,
            help = "Number of wave emission sources",
            min = 2f, max = 10f, step = 1f, default = 4f
        ),
        Parameter.SelectParam(
            name = "Wave Type", key = "waveType", group = ParamGroup.COMPOSITION,
            help = "circular: radial rings \u00b7 spiral: twisted rings \u00b7 plane: directional beam \u00b7 mixed: one of each per source",
            options = listOf("circular", "spiral", "plane", "mixed"), default = "circular"
        ),
        Parameter.NumberParam(
            name = "Frequency", key = "frequency", group = ParamGroup.GEOMETRY,
            help = "Spatial frequency of the waves",
            min = 0.5f, max = 6f, step = 0.5f, default = 2f
        ),
        Parameter.NumberParam(
            name = "Spiral Arms", key = "spiralArms", group = ParamGroup.GEOMETRY,
            help = "Twist multiplier per spiral source (spiral / mixed mode)",
            min = 1f, max = 8f, step = 1f, default = 2f
        ),
        Parameter.NumberParam(
            name = "Speed", key = "speed", group = ParamGroup.FLOW_MOTION,
            help = "Wave propagation speed",
            min = 0.1f, max = 3f, step = 0.1f, default = 1f
        ),
        Parameter.NumberParam(
            name = "Source Drift", key = "sourceMotion", group = ParamGroup.FLOW_MOTION,
            help = "Sources orbit their seeded positions \u2014 0 = fully static",
            min = 0f, max = 1f, step = 0.05f, default = 0.3f
        ),
        Parameter.NumberParam(
            name = "Damping", key = "damping", group = ParamGroup.TEXTURE,
            help = "Amplitude decay with distance from source",
            min = 0f, max = 1f, step = 0.05f, default = 0.3f
        ),
        Parameter.NumberParam(
            name = "Contrast", key = "contrast", group = ParamGroup.TEXTURE,
            help = "Fringe sharpness \u2014 higher pushes toward hard-edged bands",
            min = 0.3f, max = 3f, step = 0.1f, default = 1.2f
        ),
        Parameter.SelectParam(
            name = "Color Mode", key = "colorMode", group = ParamGroup.COLOR,
            help = "palette: smooth gradient \u00b7 bichrome: two-tone fringes \u00b7 phase: wavefront edges highlighted",
            options = listOf("palette", "bichrome", "phase"), default = "palette"
        )
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "waveCount" to 4f, "waveType" to "circular", "frequency" to 2f,
        "spiralArms" to 2f, "speed" to 1f, "sourceMotion" to 0.3f,
        "damping" to 0.3f, "contrast" to 1.2f, "colorMode" to "palette"
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
        val w = width
        val h = height
        val dim = min(w, h).toFloat()

        val sourceCount = ((params["waveCount"] as? Number)?.toInt() ?: 4).coerceIn(1, MAX_SOURCES)
        val waveType = (params["waveType"] as? String) ?: "circular"
        val frequency = (params["frequency"] as? Number)?.toFloat() ?: 2f
        val spiralArms = (params["spiralArms"] as? Number)?.toFloat() ?: 2f
        val speed = (params["speed"] as? Number)?.toFloat() ?: 1f
        val sourceMotion = (params["sourceMotion"] as? Number)?.toFloat() ?: 0.3f
        val decay = (params["damping"] as? Number)?.toFloat() ?: 0.3f
        val contrast = (params["contrast"] as? Number)?.toFloat() ?: 1.2f
        val colorMode = (params["colorMode"] as? String) ?: "palette"

        val rng = SeededRNG(seed)
        val wavelength = dim / (frequency * 4f)
        val twoPiOverLambda = 2f * PI.toFloat() / wavelength
        val timePhase = speed * time * 4f
        val normalizedDecay = decay * 3f / dim
        val margin = dim * 0.15f

        // Scatter sources from seed (mirror CPU)
        val srcPos = FloatArray(MAX_SOURCES * 2)
        val srcExtra = FloatArray(MAX_SOURCES * 4) // phase, planeCos, planeSin, _padding
        val srcType = IntArray(MAX_SOURCES)
        for (i in 0 until sourceCount) {
            val baseX = margin + rng.random() * (w - 2 * margin)
            val baseY = margin + rng.random() * (h - 2 * margin)
            val phase = rng.random() * 2f * PI.toFloat()
            val driftAngle = rng.random() * 2f * PI.toFloat()
            val driftRadius = dim * 0.05f + rng.random() * dim * 0.1f
            val driftFreq = 0.3f + rng.random() * 0.7f
            val planeAngle = rng.random() * 2f * PI.toFloat()
            val type = when (waveType) {
                "circular" -> 0; "spiral" -> 1; "plane" -> 2; "mixed" -> i % 3
                else -> 0
            }

            val orbitPhase = driftAngle + time * driftFreq * 2f
            val sx = baseX + cos(orbitPhase) * driftRadius * sourceMotion
            val sy = baseY + sin(orbitPhase) * driftRadius * sourceMotion
            srcPos[i * 2] = sx
            srcPos[i * 2 + 1] = sy
            srcExtra[i * 4] = phase
            srcExtra[i * 4 + 1] = cos(planeAngle)
            srcExtra[i * 4 + 2] = sin(planeAngle)
            srcType[i] = type
        }

        // Fill remaining slots (unused indices) with safe defaults
        for (i in sourceCount until MAX_SOURCES) {
            srcPos[i * 2] = 0f; srcPos[i * 2 + 1] = 0f
            srcExtra[i * 4] = 0f; srcExtra[i * 4 + 1] = 1f; srcExtra[i * 4 + 2] = 0f
            srcType[i] = 0
        }

        val colorId = when (colorMode) {
            "palette" -> 0; "bichrome" -> 1; "phase" -> 2
            else -> 0
        }

        val maxExpected = sourceCount * 0.5f
        val invMaxExpected = 1f / maxExpected

        GLES30.glUniform2fv(GLES30.glGetUniformLocation(programId, "uSrcPos"), MAX_SOURCES, srcPos, 0)
        GLES30.glUniform4fv(GLES30.glGetUniformLocation(programId, "uSrcExtra"), MAX_SOURCES, srcExtra, 0)
        GLES30.glUniform1iv(GLES30.glGetUniformLocation(programId, "uSrcType"), MAX_SOURCES, srcType, 0)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uSourceCount"), sourceCount)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uTwoPiOverLambda"), twoPiOverLambda)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uTimePhase"), timePhase)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uNormDecay"), normalizedDecay)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uSpiralArms"), spiralArms)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uInvMaxExpected"), invMaxExpected)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uContrast"), contrast)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uColorMode"), colorId)
    }

    override fun fragmentShaderSource(): String = """#version 300 es
        precision highp float;

        uniform vec2 uResolution;
        uniform float uTime;
        uniform vec3 uAudio;
        ${PaletteUniform.GLSL_HELPERS}

        const int MAX_SOURCES = $MAX_SOURCES;
        uniform vec2  uSrcPos[MAX_SOURCES];      // animated screen-space position
        uniform vec4  uSrcExtra[MAX_SOURCES];    // x=phase, y=planeCos, z=planeSin, w=pad
        uniform int   uSrcType[MAX_SOURCES];     // 0 circular, 1 spiral, 2 plane
        uniform int   uSourceCount;
        uniform float uTwoPiOverLambda;
        uniform float uTimePhase;
        uniform float uNormDecay;
        uniform float uSpiralArms;
        uniform float uInvMaxExpected;
        uniform float uContrast;
        uniform int   uColorMode;                // 0 palette, 1 bichrome, 2 phase

        out vec4 fragColor;

        void main() {
            // Match CPU pixel coordinates exactly (gl_FragCoord is top-down here).
            vec2 frag = gl_FragCoord.xy;

            float total = 0.0;
            for (int s = 0; s < MAX_SOURCES; s++) {
                if (s >= uSourceCount) break;

                vec2 d = frag - uSrcPos[s];
                float phase = uSrcExtra[s].x;
                int type = uSrcType[s];

                if (type == 0) {
                    // Circular
                    float r = length(d);
                    total += sin(uTwoPiOverLambda * r - uTimePhase + phase) * exp(-uNormDecay * r);
                } else if (type == 1) {
                    // Spiral: phase modulated by atan2 angle
                    float r = length(d);
                    float a = atan(d.y, d.x);
                    total += sin(uTwoPiOverLambda * r - uTimePhase + phase + a * uSpiralArms)
                          * exp(-uNormDecay * r);
                } else {
                    // Plane: directional projection
                    float planeCos = uSrcExtra[s].y;
                    float planeSin = uSrcExtra[s].z;
                    float proj = d.x * planeCos + d.y * planeSin;
                    float r = abs(proj);
                    total += sin(uTwoPiOverLambda * proj - uTimePhase + phase) * exp(-uNormDecay * r);
                }
            }

            float norm = clamp(total * uInvMaxExpected * uContrast, -1.0, 1.0);
            float mapped = norm * 0.5 + 0.5;

            vec3 col;
            if (uColorMode == 1) {
                // Bichrome
                col = (mapped > 0.5)
                    ? palette_color(0.85)
                    : palette_color(0.15);
            } else if (uColorMode == 2) {
                // Phase: brightness scaled by edge proximity
                float edginess = abs(norm);
                vec3 base = palette_color(clamp(mapped, 0.0, 1.0));
                float bright = edginess * 0.6 + 0.4;
                col = base * bright;
            } else {
                col = palette_color(clamp(mapped, 0.0, 1.0));
            }

            fragColor = vec4(col, 1.0);
        }
    """

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float {
        val sources = (params["waveCount"] as? Number)?.toFloat() ?: 4f
        return (sources / 25f).coerceIn(0.1f, 0.4f)
    }
}
