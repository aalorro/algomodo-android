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
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * GPU port. Multibrot z -> z^d + c. Animation (boundary dive + rotation)
 * is computed CPU-side and uploaded as uniforms. Iteration done shader-side.
 *
 * Integer power path uses repeated complex multiplication; fractional power
 * path uses polar form (sin/cos/log). Both share one iteration loop with a
 * uniform branch.
 */
class MultibrotGenerator : GpuGenerator {

    override val id = "fractal-multibrot"
    override val family = "fractals"
    override val styleName = "Multibrot"
    override val definition =
        "Generalization of the Mandelbrot set to arbitrary powers: z = z^d + c, producing d-1 fold symmetry."
    override val algorithmNotes =
        "GPU fragment shader. Iterates z = z^d + c. Integer powers use repeated complex multiplication; " +
        "fractional powers use polar form. Animation dives toward a seeded lobe at angle 2πk/(d-1) with " +
        "gentle rotation, all computed CPU-side."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema: List<Parameter> = listOf(
        Parameter.NumberParam("Power", "power", ParamGroup.COMPOSITION, "Exponent d — integer values give clean symmetry, fractional values create novel shapes", 2f, 8f, 0.5f, 3f),
        Parameter.NumberParam("Center X", "centerX", ParamGroup.COMPOSITION, "Real-axis center of the view", -2f, 2f, 0.05f, 0f),
        Parameter.NumberParam("Center Y", "centerY", ParamGroup.COMPOSITION, "Imaginary-axis center of the view", -2f, 2f, 0.05f, 0f),
        Parameter.NumberParam("Zoom", "zoom", ParamGroup.COMPOSITION, "Zoom level into the fractal", 0.5f, 4f, 0.5f, 1f),
        Parameter.NumberParam("Max Iterations", "maxIterations", ParamGroup.COMPOSITION, "Higher = more detail in boundary regions but slower", 32f, 256f, 16f, 100f),
        Parameter.NumberParam("Color Cycles", "colorCycles", ParamGroup.COLOR, "How many times the palette repeats across the iteration range", 1f, 8f, 1f, 3f),
        Parameter.NumberParam("Speed", "speed", ParamGroup.FLOW_MOTION, "Animation speed — zooms into fractal boundary with rotation", 0.1f, 3.0f, 0.1f, 0.5f)
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "power" to 3f,
        "centerX" to 0f,
        "centerY" to 0f,
        "zoom" to 1f,
        "maxIterations" to 100f,
        "colorCycles" to 3f,
        "speed" to 0.5f
    )

    override fun bindUniforms(
        programId: Int, params: Map<String, Any>, seed: Int, palette: Palette,
        quality: Quality, time: Float, width: Int, height: Int
    ) {
        val basePower = (params["power"] as? Number)?.toDouble() ?: 3.0
        val centerX = (params["centerX"] as? Number)?.toDouble() ?: 0.0
        val centerY = (params["centerY"] as? Number)?.toDouble() ?: 0.0
        val zoom = (params["zoom"] as? Number)?.toDouble() ?: 1.0
        val maxIter = (params["maxIterations"] as? Number)?.toInt() ?: 100
        val colorCycles = (params["colorCycles"] as? Number)?.toFloat() ?: 3f
        val speed = (params["speed"] as? Number)?.toFloat() ?: 0.5f

        val scaledMaxIter = when (quality) {
            Quality.DRAFT -> (maxIter / 2).coerceAtLeast(16)
            Quality.BALANCED -> maxIter
            Quality.ULTRA -> (maxIter * 1.5f).toInt()
        }

        val isAnim = time > 0f
        // During animation, snap to nearest integer power (matches CPU behaviour
        // — fast path only).
        val power = if (isAnim) basePower.roundToInt().toDouble().coerceAtLeast(2.0) else basePower
        val intPower = power.roundToInt()
        val useIntegerPath = abs(power - intPower) < 0.01 && intPower >= 2

        val escapeR = 2.0.pow(1.0 / (power - 1)).coerceAtLeast(2.0)

        val animCenterX: Double; val animCenterY: Double; val animZoom: Double
        val cosRot: Double; val sinRot: Double
        if (isAnim) {
            val t = time.toDouble() * speed
            val rng = java.util.Random(seed.toLong())
            val numLobes = (intPower - 1).coerceAtLeast(1)
            val targetLobe = rng.nextInt(numLobes)
            val angleOffset = (rng.nextDouble() - 0.5) * 0.4
            val targetAngle = 2.0 * Math.PI * targetLobe / numLobes + angleOffset
            val boundaryR = escapeR * (0.82 + rng.nextDouble() * 0.12)
            val targetX = boundaryR * cos(targetAngle)
            val targetY = boundaryR * sin(targetAngle)
            animZoom = zoom * (1.0 + t * 0.5)
            val lerpT = (1.0 - 1.0 / (1.0 + t * 0.15)).coerceIn(0.0, 0.95)
            animCenterX = centerX + (targetX - centerX) * lerpT
            animCenterY = centerY + (targetY - centerY) * lerpT
            val rotAngle = t * 0.05
            cosRot = cos(rotAngle); sinRot = sin(rotAngle)
        } else {
            animCenterX = centerX; animCenterY = centerY; animZoom = zoom
            cosRot = 1.0; sinRot = 0.0
        }

        val aspect = width.toFloat() / height.toFloat()
        val rangeY = (3.0 / animZoom).toFloat()
        val rangeX = rangeY * aspect
        val timeShift = time * speed * 0.02f

        GLES30.glUniform2f(GLES30.glGetUniformLocation(programId, "uCenter"), animCenterX.toFloat(), animCenterY.toFloat())
        GLES30.glUniform2f(GLES30.glGetUniformLocation(programId, "uRange"), rangeX, rangeY)
        GLES30.glUniform2f(GLES30.glGetUniformLocation(programId, "uRot"), cosRot.toFloat(), sinRot.toFloat())
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uMaxIter"), scaledMaxIter)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uPower"), power.toFloat())
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uIntPower"), if (useIntegerPath) intPower else 0)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uEscapeR2"), (escapeR * escapeR).toFloat())
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uLnPower"), kotlin.math.ln(power).toFloat())
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
        uniform vec2 uRot;        // cos, sin
        uniform int uMaxIter;
        uniform float uPower;
        uniform int uIntPower;    // >0 = integer fast path, 0 = polar
        uniform float uEscapeR2;
        uniform float uLnPower;
        uniform float uColorCycles;
        uniform float uTimeShift;

        out vec4 fragColor;

        void main() {
            vec2 uv = gl_FragCoord.xy / uResolution.xy;
            float rawX = (uv.x - 0.5) * uRange.x;
            float rawY = (uv.y - 0.5) * uRange.y;
            float cr = uCenter.x + rawX * uRot.x - rawY * uRot.y;
            float ci = uCenter.y + rawX * uRot.y + rawY * uRot.x;

            float zr = 0.0;
            float zi = 0.0;
            int iter = 0;
            float mag2 = 0.0;

            if (uIntPower > 0) {
                for (int i = 0; i < 512; i++) {
                    if (i >= uMaxIter) break;
                    mag2 = zr * zr + zi * zi;
                    if (mag2 > uEscapeR2) break;
                    // z = z^uIntPower via repeated complex multiplication
                    float pr = zr;
                    float pi = zi;
                    for (int k = 1; k < 8; k++) {
                        if (k >= uIntPower) break;
                        float nr = pr * zr - pi * zi;
                        float ni = pr * zi + pi * zr;
                        pr = nr; pi = ni;
                    }
                    zr = pr + cr;
                    zi = pi + ci;
                    iter++;
                }
            } else {
                for (int i = 0; i < 512; i++) {
                    if (i >= uMaxIter) break;
                    mag2 = zr * zr + zi * zi;
                    if (mag2 > uEscapeR2) break;
                    float r = sqrt(mag2);
                    float theta = atan(zi, zr);
                    float rD = pow(r, uPower);
                    float dTheta = uPower * theta;
                    zr = rD * cos(dTheta) + cr;
                    zi = rD * sin(dTheta) + ci;
                    iter++;
                }
            }

            vec3 col;
            if (iter >= uMaxIter) {
                col = palette_color(0.0) * 0.1;
            } else {
                float mag = sqrt(max(mag2, 1.001));
                float smoothIter = float(iter) + 1.0 - log(log(mag)) / uLnPower;
                float maxIterF = float(uMaxIter);
                float rawT = fract(smoothIter / maxIterF * uColorCycles);
                float shifted = fract(rawT + uTimeShift + 1.0);
                col = palette_color(shifted);
            }
            fragColor = vec4(col, 1.0);
        }
    """
}
