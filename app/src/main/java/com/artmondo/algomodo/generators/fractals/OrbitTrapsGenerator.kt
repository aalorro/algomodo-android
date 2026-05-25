package com.artmondo.algomodo.generators.fractals

import android.opengl.GLES30
import com.artmondo.algomodo.data.palettes.Palette
import com.artmondo.algomodo.generators.GpuGenerator
import com.artmondo.algomodo.generators.ParamGroup
import com.artmondo.algomodo.generators.Parameter
import com.artmondo.algomodo.generators.Quality
import com.artmondo.algomodo.rendering.gl.PaletteUniform
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * GPU port. Orbit-trap Mandelbrot — 8 trap shapes, 5 colour modes, 4 movement
 * modes. Animation (centre/zoom/rotation/trap-size modulation) computed
 * CPU-side. Per-pixel iteration + trap-distance + colouring done shader-side.
 *
 * Periodicity detection dropped (uniform branching is more expensive on GPU
 * than the rare interior-pixel saving).
 */
class OrbitTrapsGenerator : GpuGenerator {

    override val id = "fractal-orbit-traps"
    override val family = "fractals"
    override val styleName = "Orbit Traps"
    override val definition =
        "Orbit trap fractal — Mandelbrot iteration colored by proximity of orbit points to geometric trap shapes."
    override val algorithmNotes =
        "GPU fragment shader. Mandelbrot iteration measures minimum orbit distance to a trap shape " +
        "(point / circle / cross / ring / square / stalk / line / spiral). Colour modes blend distance, " +
        "angle, iteration, and layered glow. Animation pans toward seed-picked hotspots."
    override val supportsVector = false
    override val supportsAnimation = true

    companion object {
        private val ZOOM_TARGETS = arrayOf(
            doubleArrayOf(-0.7435669, 0.1314023),
            doubleArrayOf(-0.1011, 0.9563),
            doubleArrayOf(-1.25, 0.0),
            doubleArrayOf(-0.16, 1.0405),
            doubleArrayOf(0.285, 0.01),
            doubleArrayOf(-0.745, 0.113),
        )
    }

    override val parameterSchema: List<Parameter> = listOf(
        Parameter.SelectParam("Trap Shape", "trapShape", ParamGroup.COMPOSITION,
            "Geometric shape used as the orbit trap",
            listOf("point", "circle", "cross", "ring", "square", "stalk", "line", "spiral"), "stalk"),
        Parameter.NumberParam("Trap Size", "trapSize", ParamGroup.GEOMETRY,
            "Size of the trap shape", 0.05f, 2f, 0.05f, 0.5f),
        Parameter.NumberParam("Center X", "centerX", ParamGroup.COMPOSITION,
            "Real-axis center of the view", -2f, 2f, 0.05f, -0.5f),
        Parameter.NumberParam("Center Y", "centerY", ParamGroup.COMPOSITION,
            "Imaginary-axis center of the view", -2f, 2f, 0.05f, 0f),
        Parameter.NumberParam("Zoom", "zoom", ParamGroup.COMPOSITION,
            "Zoom level", 0.5f, 20f, 0.1f, 1f),
        Parameter.NumberParam("Max Iterations", "maxIterations", ParamGroup.COMPOSITION,
            "Maximum escape-time iterations", 32f, 256f, 8f, 80f),
        Parameter.SelectParam("Color Mode", "colorMode", ParamGroup.COLOR,
            "Coloring method",
            listOf("distance", "angle", "iteration", "composite", "layered"), "layered"),
        Parameter.SelectParam("Movement", "movement", ParamGroup.FLOW_MOTION,
            "Animation camera mode",
            listOf("dive", "drift", "pulse", "orbit"), "dive"),
        Parameter.NumberParam("Speed", "speed", ParamGroup.FLOW_MOTION,
            "Animation speed", 0.1f, 3.0f, 0.1f, 0.5f),
        Parameter.NumberParam("Duration", "duration", ParamGroup.FLOW_MOTION,
            "Zoom cycle length in seconds", 1f, 30f, 1f, 10f),
        Parameter.NumberParam("Zoom Depth", "zoomDepth", ParamGroup.FLOW_MOTION,
            "How deep the zoom travels before looping", 10f, 10000f, 10f, 60f),
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "trapShape" to "stalk",
        "trapSize" to 0.5f,
        "centerX" to -0.5f,
        "centerY" to 0f,
        "zoom" to 1f,
        "maxIterations" to 80f,
        "colorMode" to "layered",
        "movement" to "dive",
        "speed" to 0.5f,
        "duration" to 10f,
        "zoomDepth" to 60f,
    )

    override fun bindUniforms(
        programId: Int, params: Map<String, Any>, seed: Int, palette: Palette,
        quality: Quality, time: Float, width: Int, height: Int
    ) {
        val trapShape = (params["trapShape"] as? String) ?: "stalk"
        var trapSize = (params["trapSize"] as? Number)?.toDouble() ?: 0.5
        val baseCx = (params["centerX"] as? Number)?.toDouble() ?: -0.5
        val baseCy = (params["centerY"] as? Number)?.toDouble() ?: 0.0
        val zoomParam = (params["zoom"] as? Number)?.toDouble() ?: 1.0
        val baseMaxIter = (params["maxIterations"] as? Number)?.toInt() ?: 80
        val colorMode = (params["colorMode"] as? String) ?: "layered"
        val movement = (params["movement"] as? String) ?: "dive"
        val speed = (params["speed"] as? Number)?.toDouble() ?: 0.5
        val duration = (params["duration"] as? Number)?.toDouble() ?: 10.0
        val zoomDepth = (params["zoomDepth"] as? Number)?.toDouble() ?: 60.0

        val isAnim = time > 0f
        val t = time.toDouble()

        val maxIter = when (quality) {
            Quality.DRAFT -> (baseMaxIter / 2).coerceAtLeast(24)
            Quality.BALANCED -> if (isAnim) baseMaxIter.coerceAtMost(48) else baseMaxIter
            Quality.ULTRA -> if (isAnim) baseMaxIter.coerceAtMost(96) else (baseMaxIter * 2).coerceAtMost(256)
        }

        val trapId = when (trapShape) {
            "point" -> 0; "circle" -> 1; "cross" -> 2; "ring" -> 3
            "square" -> 4; "stalk" -> 5; "line" -> 6; "spiral" -> 7; else -> 5
        }
        val colorModeId = when (colorMode) {
            "distance" -> 0; "angle" -> 1; "iteration" -> 2; "composite" -> 3
            "layered" -> 4; else -> 4
        }

        val target = ZOOM_TARGETS[abs(seed) % ZOOM_TARGETS.size]
        var cx: Double; var cy: Double; var viewZoom: Double
        var trapRotation = 0.0

        if (isAnim) {
            when (movement) {
                "dive" -> {
                    trapSize += sin(t * speed * 0.5) * 0.2 + sin(t * speed * 0.17) * 0.08
                    val cycleTime = t % duration
                    viewZoom = zoomParam * zoomDepth.pow(cycleTime / duration)
                    val lerp = 1.0 - 1.0 / viewZoom
                    cx = baseCx + (target[0] - baseCx) * lerp
                    cy = baseCy + (target[1] - baseCy) * lerp
                    trapRotation = t * speed * 0.3
                }
                "drift" -> {
                    trapSize += sin(t * speed * 0.3) * 0.12
                    viewZoom = zoomParam
                    val range = 1.5 / viewZoom
                    cx = baseCx + sin(t * speed * 0.13) * range
                    cy = baseCy + sin(t * speed * 0.09) * range * 0.7
                    trapRotation = t * speed * 0.15
                }
                "pulse" -> {
                    trapSize += sin(t * speed * 0.4) * 0.4 + sin(t * speed * 0.23) * 0.15
                    viewZoom = zoomParam; cx = baseCx; cy = baseCy
                    trapRotation = sin(t * speed * 0.25) * 1.5
                }
                "orbit" -> {
                    trapSize += sin(t * speed * 0.35) * 0.1
                    viewZoom = zoomParam
                    val orbitRadius = 0.8 / viewZoom
                    cx = baseCx + cos(t * speed * 0.2) * orbitRadius
                    cy = baseCy + sin(t * speed * 0.2) * orbitRadius
                    trapRotation = t * speed * 0.3
                }
                else -> { viewZoom = zoomParam; cx = baseCx; cy = baseCy }
            }
        } else {
            cx = baseCx; cy = baseCy; viewZoom = zoomParam
        }

        val cosRot = cos(trapRotation).toFloat()
        val sinRot = sin(trapRotation).toFloat()

        val minDim = minOf(width, height).toDouble()
        val pixelSize = (3.0 / (viewZoom * minDim)).toFloat()

        GLES30.glUniform2f(GLES30.glGetUniformLocation(programId, "uCenter"), cx.toFloat(), cy.toFloat())
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uPixelSize"), pixelSize)
        GLES30.glUniform2f(GLES30.glGetUniformLocation(programId, "uRot"), cosRot, sinRot)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uTrap"), trapId)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uTrapSize"), trapSize.toFloat())
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uColorMode"), colorModeId)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uMaxIter"), maxIter)
    }

    override fun fragmentShaderSource(): String = """#version 300 es
        precision highp float;

        uniform vec2 uResolution;
        uniform float uTime;
        uniform vec3 uAudio;
        ${PaletteUniform.GLSL_HELPERS}

        uniform vec2 uCenter;
        uniform float uPixelSize;
        uniform vec2 uRot;
        uniform int uTrap;
        uniform float uTrapSize;
        uniform int uColorMode;     // 0 dist, 1 angle, 2 iter, 3 composite, 4 layered
        uniform int uMaxIter;

        const float BAILOUT_SQ = 64.0;
        const float PI_F = 3.1415927;
        const float TWO_PI_F = 6.2831853;

        out vec4 fragColor;

        float trapDist(vec2 z) {
            if (uTrap == 0) return length(z - vec2(uTrapSize, 0.0));
            if (uTrap == 1) return abs(length(z) - uTrapSize);
            if (uTrap == 2) return min(abs(z.x), abs(z.y));
            if (uTrap == 3) {
                float r = length(z);
                float ringD = mod(r, uTrapSize);
                return min(ringD, uTrapSize - ringD);
            }
            if (uTrap == 4) return abs(max(abs(z.x), abs(z.y)) - uTrapSize);
            if (uTrap == 5) return z.x > 0.0 ? abs(z.y) : 1e8;
            if (uTrap == 6) return abs(z.x - z.y) * 0.7071;
            // Spiral
            float r = length(z);
            float theta = atan(z.y, z.x);
            float ad = abs(mod(r - uTrapSize * (theta + PI_F) / TWO_PI_F, uTrapSize));
            return min(ad, uTrapSize - ad);
        }

        void main() {
            vec2 c = uCenter + (gl_FragCoord.xy - uResolution.xy * 0.5) * uPixelSize;
            vec2 z = vec2(0.0);
            int iter = 0;
            float minDist = 1e30;
            vec2 minTZ = vec2(0.0);
            int minIter = 0;
            float sumInvDist = 0.0;

            // Cardioid / period-2 bulb skip
            float q = (c.x - 0.25) * (c.x - 0.25) + c.y * c.y;
            bool inside = (q * (q + (c.x - 0.25)) < 0.25 * c.y * c.y) ||
                          ((c.x + 1.0) * (c.x + 1.0) + c.y * c.y < 0.0625);
            if (inside) iter = uMaxIter;

            for (int i = 0; i < 512; i++) {
                if (i >= uMaxIter) break;
                if (dot(z, z) > BAILOUT_SQ) break;
                float nzr = z.x * z.x - z.y * z.y + c.x;
                z.y = 2.0 * z.x * z.y + c.y;
                z.x = nzr;
                iter++;

                vec2 tZ = vec2(z.x * uRot.x - z.y * uRot.y, z.x * uRot.y + z.y * uRot.x);
                float d = trapDist(tZ);

                if (uColorMode == 4) sumInvDist += 1.0 / (1.0 + d * d * 10.0);
                if (d < minDist) {
                    minDist = d;
                    minTZ = tZ;
                    minIter = iter;
                }
            }

            float invMaxIter = 1.0 / float(uMaxIter);
            float invTrapNorm = 1.0 / (uTrapSize + 0.5);
            float rawDist = minDist * invTrapNorm;
            float distT = 1.0 - exp(-rawDist * 3.0);

            vec3 col;
            if (iter >= uMaxIter) {
                if (uColorMode == 4 && sumInvDist > 0.0) {
                    float intT = min(sumInvDist / (float(uMaxIter) * 0.1), 1.0);
                    col = palette_color(intT * 0.5) * 0.15;
                } else {
                    col = palette_color(distT) * 0.2;
                }
            } else {
                if (uColorMode == 0) {
                    col = palette_color(distT);
                } else if (uColorMode == 1) {
                    float angleT = fract((atan(minTZ.y, minTZ.x) / PI_F + 1.0) * 0.5);
                    col = palette_color(angleT);
                    col *= 0.85 + 0.15 * (1.0 - distT);
                } else if (uColorMode == 2) {
                    float iterT = float(minIter) * invMaxIter;
                    col = palette_color(iterT);
                    col *= 0.8 + 0.2 * (1.0 - distT);
                } else if (uColorMode == 3) {
                    float angleT = fract((atan(minTZ.y, minTZ.x) / PI_F + 1.0) * 0.5);
                    float iterT = float(minIter) * invMaxIter;
                    float blendT = fract(angleT * 0.6 + iterT * 0.4);
                    col = palette_color(blendT);
                    col *= 0.75 + 0.25 * (1.0 - distT);
                } else {
                    float angleT = fract((atan(minTZ.y, minTZ.x) / PI_F + 1.0) * 0.5);
                    float layerT = iter > 0 ? min(sumInvDist / (float(iter) * 0.15), 1.0) : 0.0;
                    float hueT = fract(angleT * 0.5 + layerT * 0.5);
                    col = palette_color(hueT);
                    float layerGlow = min(layerT * 1.5, 1.0);
                    float edgeDim = 0.7 + 0.3 * (1.0 - distT);
                    float finalBright = (layerGlow * 0.7 + edgeDim * 0.3) * 1.3;
                    col *= finalBright;
                }

                if (minDist < 0.03) {
                    float glow = (0.03 - minDist) * 33.33;
                    float gs = glow * glow * (uColorMode == 0 || uColorMode == 4 ? 0.7 : 0.5);
                    col = mix(col, vec3(1.0), gs);
                }
            }
            fragColor = vec4(clamp(col, 0.0, 1.0), 1.0);
        }
    """
}
