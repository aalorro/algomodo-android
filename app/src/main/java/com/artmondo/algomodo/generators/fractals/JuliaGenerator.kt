package com.artmondo.algomodo.generators.fractals

import android.opengl.GLES30
import com.artmondo.algomodo.data.palettes.Palette
import com.artmondo.algomodo.generators.GpuGenerator
import com.artmondo.algomodo.generators.ParamGroup
import com.artmondo.algomodo.generators.Parameter
import com.artmondo.algomodo.generators.Quality
import com.artmondo.algomodo.rendering.gl.PaletteUniform
import kotlin.math.cos
import kotlin.math.sin

/**
 * GPU port. Julia set z -> z² + c. The c parameter orbits CPU-side and is
 * uploaded each frame. Iteration + smooth/bands colouring done shader-side.
 */
class JuliaGenerator : GpuGenerator {

    override val id = "fractal-julia"
    override val family = "fractals"
    override val styleName = "Julia Set"
    override val definition =
        "Julia set fractal for the quadratic map z -> z^2 + c, where c is a complex constant parameter."
    override val algorithmNotes =
        "GPU fragment shader. Each pixel is the initial z; iterates z = z² + c until |z|>2 or max iter. " +
        "c orbits CPU-side via Lissajous path. Smooth colouring via normalised iteration count. " +
        "Bands mode quantises smoothIter into uBandCount steps."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema: List<Parameter> = listOf(
        Parameter.NumberParam("C Real", "cReal", ParamGroup.COMPOSITION, "Real part of the constant c", -1.5f, 1.5f, 0.01f, -0.7f),
        Parameter.NumberParam("C Imaginary", "cImag", ParamGroup.COMPOSITION, "Imaginary part of the constant c", -1.5f, 1.5f, 0.01f, 0.27f),
        Parameter.NumberParam("Zoom", "zoom", ParamGroup.COMPOSITION, "Zoom level into the Julia set", 0.5f, 5f, 0.5f, 1f),
        Parameter.NumberParam("Max Iterations", "maxIterations", ParamGroup.COMPOSITION, "Higher = more detail but slower", 32f, 256f, 16f, 100f),
        Parameter.SelectParam("Color Mode", "colorMode", ParamGroup.COLOR, "smooth: continuous gradient | bands: stepped contours", listOf("smooth", "bands"), "smooth"),
        Parameter.NumberParam("Color Cycles", "colorCycles", ParamGroup.COLOR, "How many times the palette repeats across the iteration range", 1f, 10f, 1f, 3f),
        Parameter.NumberParam("Band Count", "bandCount", ParamGroup.COLOR, "Number of color bands (bands mode only)", 2f, 24f, 1f, 8f),
        Parameter.NumberParam("Speed", "speed", ParamGroup.FLOW_MOTION, "Speed of the c-parameter orbit animation", 0.1f, 3.0f, 0.1f, 0.5f)
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "cReal" to -0.7f,
        "cImag" to 0.27f,
        "zoom" to 1f,
        "maxIterations" to 100f,
        "colorMode" to "smooth",
        "colorCycles" to 3f,
        "bandCount" to 8f,
        "speed" to 0.5f
    )

    override fun bindUniforms(
        programId: Int, params: Map<String, Any>, seed: Int, palette: Palette,
        quality: Quality, time: Float, width: Int, height: Int
    ) {
        val baseCx = (params["cReal"] as? Number)?.toDouble() ?: -0.7
        val baseCy = (params["cImag"] as? Number)?.toDouble() ?: 0.27
        val zoom = (params["zoom"] as? Number)?.toFloat() ?: 1f
        val maxIter = (params["maxIterations"] as? Number)?.toInt() ?: 100
        val colorMode = (params["colorMode"] as? String) ?: "smooth"
        val colorCycles = (params["colorCycles"] as? Number)?.toFloat() ?: 3f
        val bandCount = (params["bandCount"] as? Number)?.toInt() ?: 8
        val speed = (params["speed"] as? Number)?.toFloat() ?: 0.5f

        val scaledMaxIter = when (quality) {
            Quality.DRAFT -> (maxIter / 2).coerceAtLeast(16)
            Quality.BALANCED -> maxIter
            Quality.ULTRA -> (maxIter * 1.5f).toInt()
        }

        val orbitRadius = 0.12
        val cr = (baseCx + orbitRadius * sin(time.toDouble() * speed * 0.4)).toFloat()
        val ci = (baseCy + orbitRadius * cos(time.toDouble() * speed * 0.55)).toFloat()

        val aspect = width.toFloat() / height.toFloat()
        val rangeY = 3.0f / zoom
        val rangeX = rangeY * aspect

        GLES30.glUniform2f(GLES30.glGetUniformLocation(programId, "uC"), cr, ci)
        GLES30.glUniform2f(GLES30.glGetUniformLocation(programId, "uRange"), rangeX, rangeY)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uMaxIter"), scaledMaxIter)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uStyle"), if (colorMode == "bands") 1 else 0)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uColorCycles"), colorCycles)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uBandCount"), bandCount.coerceAtLeast(2))
    }

    override fun fragmentShaderSource(): String = """#version 300 es
        precision highp float;

        uniform vec2 uResolution;
        uniform float uTime;
        uniform vec3 uAudio;
        ${PaletteUniform.GLSL_HELPERS}

        uniform vec2 uC;
        uniform vec2 uRange;
        uniform int uMaxIter;
        uniform int uStyle;       // 0 smooth, 1 bands
        uniform float uColorCycles;
        uniform int uBandCount;

        out vec4 fragColor;

        void main() {
            vec2 uv = gl_FragCoord.xy / uResolution.xy;
            float zr = (uv.x - 0.5) * uRange.x;
            float zi = (uv.y - 0.5) * uRange.y;

            const float ln2 = 0.6931472;
            int iter = 0;
            float mag2 = 0.0;
            for (int i = 0; i < 512; i++) {
                if (i >= uMaxIter) break;
                mag2 = zr * zr + zi * zi;
                if (mag2 > 4.0) break;
                float tmp = zr * zr - zi * zi + uC.x;
                zi = 2.0 * zr * zi + uC.y;
                zr = tmp;
                iter++;
            }

            vec3 col;
            if (iter >= uMaxIter) {
                col = palette_color(0.5) * 0.08;
            } else {
                float logZn = log(max(mag2, 1.001)) * 0.5;
                float nu = log(logZn / ln2) / ln2;
                float smoothIter = float(iter) + 1.0 - nu;
                float maxIterF = float(uMaxIter);
                if (uStyle == 1) {
                    float bc = float(uBandCount);
                    float bandIdx = floor(mod(smoothIter * uColorCycles / maxIterF * bc, bc));
                    float t = bandIdx / max(bc - 1.0, 1.0);
                    col = palette_color(t);
                } else {
                    float t = fract(smoothIter / maxIterF * uColorCycles);
                    col = palette_color(t);
                }
            }
            fragColor = vec4(col, 1.0);
        }
    """
}
