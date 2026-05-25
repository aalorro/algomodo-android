package com.artmondo.algomodo.generators.fractals

import android.opengl.GLES30
import com.artmondo.algomodo.core.rng.SeededRNG
import com.artmondo.algomodo.data.palettes.Palette
import com.artmondo.algomodo.generators.GpuGenerator
import com.artmondo.algomodo.generators.ParamGroup
import com.artmondo.algomodo.generators.Parameter
import com.artmondo.algomodo.generators.Quality
import com.artmondo.algomodo.rendering.gl.PaletteUniform

/**
 * GPU port. Burning Ship z = (|Re z| + i|Im z|)² + c. Animation pans toward
 * one of six known boundary hotspots, computed CPU-side.
 */
class BurningShipGenerator : GpuGenerator {

    override val id = "fractal-burning-ship"
    override val family = "fractals"
    override val styleName = "Burning Ship"
    override val definition =
        "The Burning Ship fractal — a variant of the Mandelbrot set using absolute values before squaring, producing an asymmetric ship-like shape."
    override val algorithmNotes =
        "GPU fragment shader. Iterates z = (|Re z| + i|Im z|)² + c. Animation pans toward " +
        "a seed-picked boundary hotspot while zooming. Smooth colouring via normalised iteration count."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema: List<Parameter> = listOf(
        Parameter.NumberParam("Center X", "centerX", ParamGroup.COMPOSITION, "Real-axis center of the view", -2.5f, 1.5f, 0.05f, -0.5f),
        Parameter.NumberParam("Center Y", "centerY", ParamGroup.COMPOSITION, "Imaginary-axis center (ship is upside-down)", -2f, 1f, 0.05f, -0.5f),
        Parameter.NumberParam("Zoom", "zoom", ParamGroup.COMPOSITION, "Zoom level into the fractal", 0.5f, 4f, 0.5f, 1f),
        Parameter.NumberParam("Max Iterations", "maxIterations", ParamGroup.COMPOSITION, "Higher = more detail in boundary regions but slower", 32f, 256f, 16f, 100f),
        Parameter.NumberParam("Color Cycles", "colorCycles", ParamGroup.COLOR, "How many times the palette repeats across the iteration range", 1f, 8f, 1f, 3f),
        Parameter.NumberParam("Speed", "speed", ParamGroup.FLOW_MOTION, "Animation zoom speed", 0.1f, 3.0f, 0.1f, 0.5f)
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "centerX" to -0.5f,
        "centerY" to -0.5f,
        "zoom" to 1f,
        "maxIterations" to 100f,
        "colorCycles" to 3f,
        "speed" to 0.5f
    )

    private val zoomTargets = arrayOf(
        doubleArrayOf(-1.762, -0.028),
        doubleArrayOf(-0.155, -1.035),
        doubleArrayOf(-1.478, -0.0325),
        doubleArrayOf(-0.505, -0.562),
        doubleArrayOf(-1.756, -0.0175),
        doubleArrayOf(-0.593, -0.668)
    )

    override fun bindUniforms(
        programId: Int, params: Map<String, Any>, seed: Int, palette: Palette,
        quality: Quality, time: Float, width: Int, height: Int
    ) {
        val baseCx = (params["centerX"] as? Number)?.toDouble() ?: -0.5
        val baseCy = (params["centerY"] as? Number)?.toDouble() ?: -0.5
        val baseZoom = (params["zoom"] as? Number)?.toFloat() ?: 1f
        val maxIter = (params["maxIterations"] as? Number)?.toInt() ?: 100
        val colorCycles = (params["colorCycles"] as? Number)?.toFloat() ?: 3f
        val speed = (params["speed"] as? Number)?.toFloat() ?: 0.5f

        val scaledMaxIter = when (quality) {
            Quality.DRAFT -> (maxIter / 2).coerceAtLeast(16)
            Quality.BALANCED -> maxIter
            Quality.ULTRA -> (maxIter * 1.5f).toInt()
        }

        val rng = SeededRNG(seed)
        val target = zoomTargets[rng.integer(0, zoomTargets.size - 1)]
        val zoomFactor = baseZoom * (1f + time * speed * 0.5f)
        val lerpT = (1.0 - 1.0 / (1.0 + time * speed * 0.1)).coerceIn(0.0, 0.95)
        val centerX = baseCx + (target[0] - baseCx) * lerpT
        val centerY = baseCy + (target[1] - baseCy) * lerpT

        val aspect = width.toFloat() / height.toFloat()
        val rangeY = 3.0f / zoomFactor
        val rangeX = rangeY * aspect
        val timeShift = time * speed * 0.02f

        GLES30.glUniform2f(GLES30.glGetUniformLocation(programId, "uCenter"), centerX.toFloat(), centerY.toFloat())
        GLES30.glUniform2f(GLES30.glGetUniformLocation(programId, "uRange"), rangeX, rangeY)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uMaxIter"), scaledMaxIter)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uColorCycles"), colorCycles)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uTimeShift"), timeShift)
    }

    override fun fragmentShaderSource(): String = """#version 300 es
        precision highp float;

        uniform vec2 uResolution;
        uniform float uTime;
        uniform vec3 uAudio;
        ${PaletteUniform.GLSL_HELPERS}

        uniform vec2 uCenter;
        uniform vec2 uRange;
        uniform int uMaxIter;
        uniform float uColorCycles;
        uniform float uTimeShift;

        out vec4 fragColor;

        void main() {
            vec2 uv = gl_FragCoord.xy / uResolution.xy;
            float cr = uCenter.x + (uv.x - 0.5) * uRange.x;
            float ci = uCenter.y + (uv.y - 0.5) * uRange.y;

            const float ln2 = 0.6931472;
            float zr = 0.0; float zi = 0.0;
            int iter = 0;
            float mag2 = 0.0;
            for (int i = 0; i < 512; i++) {
                if (i >= uMaxIter) break;
                mag2 = zr * zr + zi * zi;
                if (mag2 > 4.0) break;
                float tmp = zr * zr - zi * zi + cr;
                zi = 2.0 * abs(zr) * abs(zi) + ci;
                zr = tmp;
                iter++;
            }

            vec3 col;
            if (iter >= uMaxIter) {
                col = palette_color(0.0) * 0.1;
            } else {
                float logZn = log(max(mag2, 1.001)) * 0.5;
                float nu = log(logZn / ln2) / ln2;
                float smoothIter = float(iter) + 1.0 - nu;
                float maxIterF = float(uMaxIter);
                float rawT = fract(smoothIter / maxIterF * uColorCycles);
                float shifted = fract(rawT + uTimeShift + 1.0);
                col = palette_color(shifted);
            }
            fragColor = vec4(col, 1.0);
        }
    """
}
