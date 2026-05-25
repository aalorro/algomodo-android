package com.artmondo.algomodo.generators.flux

import android.graphics.Color
import android.opengl.GLES30
import com.artmondo.algomodo.audio.AudioAnalysis
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
 * GPU-rendered 2D metaballs. Renders the isosurface of N moving charge points
 * using per-pixel field summation in a fragment shader. Three colour modes
 * mirror the original CPU implementation (field gradient, nearest-ball,
 * banded), plus an exponential edge-glow falloff outside the isosurface.
 *
 * Ball base positions / orbit parameters are derived from the seed on the CPU
 * each frame (deterministic, cheap), then current positions are uploaded as
 * `uBalls[15]` (vec3: x, y, r²).
 */
class FluxMetaballs2dGenerator : GpuGenerator {

    override val id = "flux-metaballs-2d"
    override val family = "flux"
    override val styleName = "Metaballs 2D"
    override val definition =
        "Renders an isosurface of multiple moving charge points -- classic metaball / " +
        "blobby implicit-surface visualization using per-pixel field summation"
    override val algorithmNotes =
        "Per-frame ball positions computed CPU-side from seed + time, uploaded as a uniform " +
        "vec3[15] array. The fragment shader sums each ball's r²/(d²+1) contribution and " +
        "branches on color mode: field (smooth palette ramp on field/threshold ratio), " +
        "nearest (palette colour of closest ball above threshold), bands (quantised field)."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema = listOf(
        Parameter.NumberParam(
            name = "Ball Count",
            key = "ballCount",
            group = ParamGroup.COMPOSITION,
            help = null,
            min = 3f, max = 15f, step = 1f, default = 6f
        ),
        Parameter.NumberParam(
            name = "Ball Size",
            key = "ballSize",
            group = ParamGroup.GEOMETRY,
            help = null,
            min = 0.05f, max = 0.15f, step = 0.01f, default = 0.1f
        ),
        Parameter.NumberParam(
            name = "Threshold",
            key = "threshold",
            group = ParamGroup.TEXTURE,
            help = "Field threshold for the isosurface boundary -- lower = larger blobs",
            min = 0.5f, max = 2f, step = 0.05f, default = 1.0f
        ),
        Parameter.SelectParam(
            name = "Color Mode",
            key = "colorMode",
            group = ParamGroup.COLOR,
            help = "field: map field intensity to palette gradient | nearest: color of closest ball | bands: quantized field levels",
            options = listOf("field", "nearest", "bands"),
            default = "field"
        ),
        Parameter.NumberParam(
            name = "Edge Glow",
            key = "edgeGlow",
            group = ParamGroup.TEXTURE,
            help = "Exponential glow falloff near the isosurface boundary",
            min = 0f, max = 1f, step = 0.05f, default = 0.3f
        ),
        Parameter.NumberParam(
            name = "Speed",
            key = "speed",
            group = ParamGroup.FLOW_MOTION,
            help = "Orbital animation speed",
            min = 0.1f, max = 3f, step = 0.05f, default = 0.6f
        ),
        Parameter.NumberParam(
            name = "Audio Reactivity",
            key = "reactivity",
            group = ParamGroup.FLOW_MOTION,
            help = "Sensitivity to audio input (0 = none)",
            min = 0f, max = 2f, step = 0.1f, default = 1.0f
        )
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "ballCount" to 6f,
        "ballSize" to 0.1f,
        "threshold" to 1.0f,
        "colorMode" to "field",
        "edgeGlow" to 0.3f,
        "speed" to 0.6f,
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
        val w = width.toFloat()
        val h = height.toFloat()
        val minDim = min(w, h)

        val ballCount = (params["ballCount"] as? Number)?.toInt()?.coerceIn(3, MAX_BALLS) ?: 6
        val threshold = (params["threshold"] as? Number)?.toFloat() ?: 1.0f
        val colorMode = (params["colorMode"] as? String) ?: "field"
        val edgeGlowAmt = ((params["edgeGlow"] as? Number)?.toFloat() ?: 0.3f).coerceIn(0f, 1f)
        val spd = (params["speed"] as? Number)?.toFloat() ?: 0.6f
        val rx = (params["reactivity"] as? Number)?.toFloat() ?: 1.0f

        val audioAnalysis = params["_audioAnalysis"] as? AudioAnalysis
        val audioBass = (audioAnalysis?.getBass(time) ?: 0f) * rx
        val audioMid = (audioAnalysis?.getMid(time) ?: 0f) * rx

        val ballSize = ((params["ballSize"] as? Number)?.toFloat() ?: 0.1f) * (1f + audioBass * 0.5f)
        val effectiveSpeed = spd * (1f + audioMid * 0.8f)

        val cmId = when (colorMode) {
            "field" -> 0
            "nearest" -> 1
            "bands" -> 2
            else -> 0
        }

        // Derive base positions / orbit params from seed (deterministic, cheap)
        val rng = SeededRNG(seed)
        val basePosX = FloatArray(MAX_BALLS)
        val basePosY = FloatArray(MAX_BALLS)
        val orbitRad = FloatArray(MAX_BALLS)
        val orbitSpd = FloatArray(MAX_BALLS)
        val orbitPhs = FloatArray(MAX_BALLS)
        for (i in 0 until MAX_BALLS) {
            basePosX[i] = rng.random() * w
            basePosY[i] = rng.random() * h
            orbitRad[i] = (0.05f + rng.random() * 0.15f) * minDim
            orbitSpd[i] = (0.5f + rng.random() * 1.5f) * if (rng.random() < 0.5f) 1f else -1f
            orbitPhs[i] = rng.random() * 2f * PI.toFloat()
        }

        // Compute live positions for this frame
        val ballRadius = ballSize * minDim
        val ballR2 = ballRadius * ballRadius
        val ballsData = FloatArray(MAX_BALLS * 3)
        for (i in 0 until MAX_BALLS) {
            val phase = orbitPhs[i] + time * effectiveSpeed * orbitSpd[i]
            ballsData[i * 3] = basePosX[i] + cos(phase) * orbitRad[i]
            ballsData[i * 3 + 1] = basePosY[i] + sin(phase) * orbitRad[i]
            ballsData[i * 3 + 2] = ballR2
        }

        // Per-ball colours (nearest mode); pull from palette.colorInts
        val palColors = palette.colorInts()
        val ballColors = FloatArray(MAX_BALLS * 3)
        val nc = palColors.size
        for (i in 0 until MAX_BALLS) {
            val c = palColors[i % nc]
            ballColors[i * 3]     = Color.red(c) / 255f
            ballColors[i * 3 + 1] = Color.green(c) / 255f
            ballColors[i * 3 + 2] = Color.blue(c) / 255f
        }

        val glowFalloff = 3.0f + (1f - edgeGlowAmt) * 7.0f
        val doGlow = edgeGlowAmt > 0.01f

        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uBallCount"), ballCount)
        GLES30.glUniform3fv(GLES30.glGetUniformLocation(programId, "uBalls"), MAX_BALLS, ballsData, 0)
        GLES30.glUniform3fv(GLES30.glGetUniformLocation(programId, "uBallColors"), MAX_BALLS, ballColors, 0)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uThreshold"), threshold)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uColorMode"), cmId)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uGlowFalloff"), glowFalloff)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uDoGlow"), if (doGlow) 1 else 0)
    }

    override fun fragmentShaderSource(): String = """#version 300 es
        precision highp float;

        uniform vec2 uResolution;
        uniform float uTime;
        uniform vec3 uAudio;
        ${PaletteUniform.GLSL_HELPERS}

        const int MAX_BALLS = $MAX_BALLS;
        uniform int   uBallCount;
        uniform vec3  uBalls[MAX_BALLS];       // x, y, r²
        uniform vec3  uBallColors[MAX_BALLS];
        uniform float uThreshold;
        uniform int   uColorMode;              // 0 = field, 1 = nearest, 2 = bands
        uniform float uGlowFalloff;
        uniform int   uDoGlow;

        out vec4 fragColor;

        void main() {
            vec2 p = gl_FragCoord.xy;
            float field = 0.0;
            float nearestDistSq = 1e20;
            int   nearestIdx = 0;

            for (int i = 0; i < MAX_BALLS; i++) {
                if (i >= uBallCount) break;
                vec3 b = uBalls[i];
                vec2 d = p - b.xy;
                float distSq = dot(d, d);
                field += b.z / (distSq + 1.0);
                if (distSq < nearestDistSq) {
                    nearestDistSq = distSq;
                    nearestIdx = i;
                }
            }

            float invThreshold = 1.0 / uThreshold;
            vec3 col = vec3(0.0);

            if (field >= uThreshold) {
                if (uColorMode == 1) {
                    // Nearest ball colour
                    // GLSL ES 3.0 doesn't allow dynamic indexing of uniform arrays with
                    // a varying index reliably across drivers — do a linear scan.
                    for (int i = 0; i < MAX_BALLS; i++) {
                        if (i == nearestIdx) {
                            col = uBallColors[i];
                            break;
                        }
                    }
                } else {
                    float t = clamp((field - uThreshold) * invThreshold, 0.0, 1.0);
                    if (uColorMode == 2) {
                        // Bands: quantise to 6 levels
                        float bc = 6.0;
                        t = floor(t * bc) / bc;
                    }
                    col = palette_color(t);
                }
            } else if (uDoGlow == 1) {
                float ratio = clamp(field * invThreshold, 0.0, 1.0);
                float intensity = exp(-uGlowFalloff * (1.0 - ratio));
                if (intensity > 0.01) {
                    // Glow tint pulls from the lower half of the palette
                    vec3 base = palette_color(intensity * 0.5);
                    col = base * intensity;
                }
            }

            fragColor = vec4(col, 1.0);
        }
    """

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float {
        val balls = (params["ballCount"] as? Number)?.toFloat() ?: 6f
        return (balls / 15f * 0.3f).coerceIn(0.05f, 0.4f)
    }

    companion object {
        private const val MAX_BALLS = 15
    }
}
