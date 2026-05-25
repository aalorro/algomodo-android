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
 * GPU port. Multibrot z -> f(z)^d + c with variants and a Julia-set toggle.
 *
 * Variants (web-multibrot-style):
 *  - Standard: z' = z^d + c
 *  - Burning:  z' = (|Re(z)| + i|Im(z)|)^d + c   (Burning-Ship generalisation)
 *  - Tricorn:  z' = conj(z)^d + c                (Mandelbar / Multicorn)
 *
 * Julia mode swaps the iteration so the per-pixel coordinate becomes z₀ and
 * the c parameter becomes a uniform (animated as a small orbit, like
 * [JuliaGenerator]) — disables the boundary-dive animation that targets
 * Mandelbrot-like lobes.
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
        "Generalization of the Mandelbrot set to arbitrary powers: z = z^d + c, producing d-1 fold symmetry. " +
        "Supports Julia mode and Burning / Tricorn variants."
    override val algorithmNotes =
        "GPU fragment shader. Iterates z = f(z)^d + c where f applies the chosen variant transform (identity, " +
        "abs, or complex conjugate). Integer powers use repeated complex multiplication; fractional powers use " +
        "polar form. Animation dives toward a seeded lobe in Mandelbrot mode, or orbits the Julia c value in " +
        "Julia mode."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema: List<Parameter> = listOf(
        Parameter.NumberParam("Power", "power", ParamGroup.COMPOSITION, "Exponent d — integer values give clean symmetry, fractional values create novel shapes", 2f, 8f, 0.5f, 3f),
        Parameter.SelectParam("Variant", "variant", ParamGroup.COMPOSITION, "standard: z^d+c | burning: |z|^d+c (Burning Ship-style) | tricorn: conj(z)^d+c (Mandelbar)", listOf("standard", "burning", "tricorn"), "standard"),
        Parameter.BooleanParam("Julia Mode", "juliaMode", ParamGroup.COMPOSITION, "Render the Julia set of this formula using the fixed c below", false),
        Parameter.NumberParam("Julia C Real", "juliaCx", ParamGroup.COMPOSITION, "Real part of the Julia c parameter (Julia mode only)", -2f, 2f, 0.05f, -0.4f),
        Parameter.NumberParam("Julia C Imag", "juliaCy", ParamGroup.COMPOSITION, "Imaginary part of the Julia c parameter (Julia mode only)", -2f, 2f, 0.05f, 0.6f),
        Parameter.NumberParam("Center X", "centerX", ParamGroup.COMPOSITION, "Real-axis center of the view", -2f, 2f, 0.05f, 0f),
        Parameter.NumberParam("Center Y", "centerY", ParamGroup.COMPOSITION, "Imaginary-axis center of the view", -2f, 2f, 0.05f, 0f),
        Parameter.NumberParam("Zoom", "zoom", ParamGroup.COMPOSITION, "Zoom level into the fractal", 0.5f, 4f, 0.5f, 1f),
        Parameter.NumberParam("Rotation", "rotation", ParamGroup.GEOMETRY, "Manual rotation of the view in degrees", 0f, 360f, 5f, 0f),
        Parameter.NumberParam("Max Iterations", "maxIterations", ParamGroup.COMPOSITION, "Higher = more detail in boundary regions but slower", 32f, 256f, 16f, 100f),
        Parameter.SelectParam("Color Mode", "colorMode", ParamGroup.COLOR, "smooth: continuous gradient | bands: stepped contours", listOf("smooth", "bands"), "smooth"),
        Parameter.NumberParam("Color Cycles", "colorCycles", ParamGroup.COLOR, "How many times the palette repeats across the iteration range", 1f, 8f, 1f, 3f),
        Parameter.NumberParam("Band Count", "bandCount", ParamGroup.COLOR, "Number of color bands (bands mode only)", 2f, 24f, 1f, 8f),
        Parameter.NumberParam("Color Shift", "colorShift", ParamGroup.COLOR, "Phase offset into the palette (0..1)", 0f, 1f, 0.02f, 0f),
        Parameter.NumberParam("Speed", "speed", ParamGroup.FLOW_MOTION, "Animation speed — zooms into fractal boundary with rotation, or orbits Julia c", 0.1f, 3.0f, 0.1f, 0.5f)
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "power" to 3f,
        "variant" to "standard",
        "juliaMode" to false,
        "juliaCx" to -0.4f,
        "juliaCy" to 0.6f,
        "centerX" to 0f,
        "centerY" to 0f,
        "zoom" to 1f,
        "rotation" to 0f,
        "maxIterations" to 100f,
        "colorMode" to "smooth",
        "colorCycles" to 3f,
        "bandCount" to 8f,
        "colorShift" to 0f,
        "speed" to 0.5f
    )

    override fun bindUniforms(
        programId: Int, params: Map<String, Any>, seed: Int, palette: Palette,
        quality: Quality, time: Float, width: Int, height: Int
    ) {
        val basePower = (params["power"] as? Number)?.toDouble() ?: 3.0
        val variant = (params["variant"] as? String) ?: "standard"
        val juliaMode = (params["juliaMode"] as? Boolean) ?: false
        val juliaCx = (params["juliaCx"] as? Number)?.toDouble() ?: -0.4
        val juliaCy = (params["juliaCy"] as? Number)?.toDouble() ?: 0.6
        val centerX = (params["centerX"] as? Number)?.toDouble() ?: 0.0
        val centerY = (params["centerY"] as? Number)?.toDouble() ?: 0.0
        val zoom = (params["zoom"] as? Number)?.toDouble() ?: 1.0
        val rotationDeg = (params["rotation"] as? Number)?.toDouble() ?: 0.0
        val maxIter = (params["maxIterations"] as? Number)?.toInt() ?: 100
        val colorMode = (params["colorMode"] as? String) ?: "smooth"
        val colorCycles = (params["colorCycles"] as? Number)?.toFloat() ?: 3f
        val bandCount = ((params["bandCount"] as? Number)?.toInt() ?: 8).coerceAtLeast(2)
        val colorShift = (params["colorShift"] as? Number)?.toFloat() ?: 0f
        val speed = (params["speed"] as? Number)?.toFloat() ?: 0.5f

        val scaledMaxIter = when (quality) {
            Quality.DRAFT -> (maxIter / 2).coerceAtLeast(16)
            Quality.BALANCED -> maxIter
            Quality.ULTRA -> (maxIter * 1.5f).toInt()
        }

        val variantId = when (variant) { "burning" -> 1; "tricorn" -> 2; else -> 0 }
        val isAnim = time > 0f
        // During animation, snap to nearest integer power (matches CPU behaviour
        // — fast path only).
        val power = if (isAnim) basePower.roundToInt().toDouble().coerceAtLeast(2.0) else basePower
        val intPower = power.roundToInt()
        val useIntegerPath = abs(power - intPower) < 0.01 && intPower >= 2

        val escapeR = 2.0.pow(1.0 / (power - 1)).coerceAtLeast(2.0)

        // ── Animation ──
        // Julia mode: orbit the Julia c value (no boundary dive).
        // Mandelbrot mode: dive toward a seeded lobe with gentle rotation.
        val animCenterX: Double; val animCenterY: Double; val animZoom: Double
        val animRotRad: Double
        val animCx: Double; val animCy: Double
        if (isAnim) {
            val t = time.toDouble() * speed
            if (juliaMode) {
                val orbitR = 0.12
                animCx = juliaCx + orbitR * sin(t * 0.4)
                animCy = juliaCy + orbitR * cos(t * 0.55)
                animCenterX = centerX; animCenterY = centerY; animZoom = zoom
                animRotRad = t * 0.05
            } else {
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
                animCx = juliaCx; animCy = juliaCy
                animRotRad = t * 0.05
            }
        } else {
            animCenterX = centerX; animCenterY = centerY; animZoom = zoom
            animRotRad = 0.0
            animCx = juliaCx; animCy = juliaCy
        }

        val totalRot = Math.toRadians(rotationDeg) + animRotRad
        val cosRot = cos(totalRot); val sinRot = sin(totalRot)

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
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uVariant"), variantId)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uJuliaMode"), if (juliaMode) 1 else 0)
        GLES30.glUniform2f(GLES30.glGetUniformLocation(programId, "uJuliaC"), animCx.toFloat(), animCy.toFloat())
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uBands"), if (colorMode == "bands") 1 else 0)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uBandCount"), bandCount)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uColorShift"), colorShift)
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
        uniform int uVariant;     // 0 standard, 1 burning, 2 tricorn
        uniform int uJuliaMode;   // 0 mandelbrot, 1 julia
        uniform vec2 uJuliaC;
        uniform int uBands;       // 0 smooth, 1 banded
        uniform int uBandCount;
        uniform float uColorShift;

        out vec4 fragColor;

        // Variant-dependent pre-iteration transform on z. Applied before z is
        // raised to the power d. Standard = identity; burning = |Re|+i|Im|;
        // tricorn = complex conjugate.
        vec2 variantTransform(vec2 z) {
            if (uVariant == 1) return vec2(abs(z.x), abs(z.y));
            if (uVariant == 2) return vec2(z.x, -z.y);
            return z;
        }

        void main() {
            vec2 uv = gl_FragCoord.xy / uResolution.xy;
            float rawX = (uv.x - 0.5) * uRange.x;
            float rawY = (uv.y - 0.5) * uRange.y;
            float pixelR = uCenter.x + rawX * uRot.x - rawY * uRot.y;
            float pixelI = uCenter.y + rawX * uRot.y + rawY * uRot.x;

            // Mandelbrot: c = pixel, z0 = 0. Julia: c = uJuliaC, z0 = pixel.
            float zr, zi, cr, ci;
            if (uJuliaMode == 1) {
                zr = pixelR; zi = pixelI;
                cr = uJuliaC.x; ci = uJuliaC.y;
            } else {
                zr = 0.0; zi = 0.0;
                cr = pixelR; ci = pixelI;
            }

            int iter = 0;
            float mag2 = 0.0;

            if (uIntPower > 0) {
                for (int i = 0; i < 512; i++) {
                    if (i >= uMaxIter) break;
                    mag2 = zr * zr + zi * zi;
                    if (mag2 > uEscapeR2) break;
                    vec2 zt = variantTransform(vec2(zr, zi));
                    // z = zt^uIntPower via repeated complex multiplication
                    float pr = zt.x;
                    float pi = zt.y;
                    for (int k = 1; k < 8; k++) {
                        if (k >= uIntPower) break;
                        float nr = pr * zt.x - pi * zt.y;
                        float ni = pr * zt.y + pi * zt.x;
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
                    vec2 zt = variantTransform(vec2(zr, zi));
                    float r = sqrt(zt.x * zt.x + zt.y * zt.y);
                    float theta = atan(zt.y, zt.x);
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
                float t;
                if (uBands == 1) {
                    // Quantise the smooth iter count into a fixed number of bands.
                    float bandF = float(uBandCount);
                    t = floor(smoothIter / maxIterF * uColorCycles * bandF) / bandF;
                    t = fract(t);
                } else {
                    t = fract(smoothIter / maxIterF * uColorCycles);
                }
                float shifted = fract(t + uTimeShift + uColorShift + 1.0);
                col = palette_color(shifted);
            }
            fragColor = vec4(col, 1.0);
        }
    """
}
