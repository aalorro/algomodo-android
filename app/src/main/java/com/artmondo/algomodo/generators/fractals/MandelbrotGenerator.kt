package com.artmondo.algomodo.generators.fractals

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
import kotlin.math.exp
import kotlin.math.sin

/**
 * GPU port of the Mandelbrot set. The four "movement" animation modes are
 * still computed on the CPU (they only emit a centre / zoom / rotation per
 * frame) and uploaded as uniforms. Per-pixel iteration runs entirely in the
 * fragment shader.
 *
 * Precision: highp float gives ~7 digits, comfortably zooming to ~10^5
 * before pixel-scale precision loss. The dive animation `exp(t*0.3)` will
 * eventually exceed this, mirroring the CPU's own gradual precision drift
 * just at a slightly shallower depth.
 *
 * Periodicity (Brent) detection is dropped on GPU — interior pixels just
 * hit `maxIter` which is bounded at 256.
 */
class MandelbrotGenerator : GpuGenerator {

    override val id = "fractal-mandelbrot"
    override val family = "fractals"
    override val styleName = "Mandelbrot Set"
    override val definition =
        "The classic Mandelbrot set fractal rendered with the escape-time algorithm, mapping iteration counts to palette colors."
    override val algorithmNotes =
        "GPU shader. For each pixel iterates z = z² + c where c = animCenter + rotated(rawXY). " +
        "Cardioid and period-2 bulb tests early-exit ~35% of interior pixels. " +
        "Three styles: smooth (classic), stripe (orbit-statistic detail), glow (distance-estimate edge). " +
        "Four movements (dive/orbit/pulse/wander) computed CPU-side per frame and uploaded as uniforms."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema: List<Parameter> = listOf(
        Parameter.SelectParam("Style", "style", ParamGroup.COMPOSITION, "smooth: classic gradient | stripe: fine detail patterns | glow: boundary edge glow", listOf("smooth", "stripe", "glow"), "stripe"),
        Parameter.SelectParam("Movement", "movement", ParamGroup.FLOW_MOTION, "dive: zoom into boundary | orbit: circle the set | pulse: breathe in/out | wander: drift across regions", listOf("dive", "orbit", "pulse", "wander"), "dive"),
        Parameter.NumberParam("Center X", "centerX", ParamGroup.COMPOSITION, "Real-axis center of the view", -0.8f, -0.2f, 0.05f, -0.5f),
        Parameter.NumberParam("Center Y", "centerY", ParamGroup.COMPOSITION, "Imaginary-axis center of the view", -0.3f, 0.3f, 0.05f, 0f),
        Parameter.NumberParam("Zoom", "zoom", ParamGroup.COMPOSITION, "Zoom level — higher values zoom deeper into the fractal", 0.5f, 4f, 0.5f, 1f),
        Parameter.NumberParam("Max Iterations", "maxIterations", ParamGroup.COMPOSITION, "Higher = more detail in boundary regions but slower", 32f, 256f, 16f, 100f),
        Parameter.NumberParam("Color Cycles", "colorCycles", ParamGroup.COLOR, "How many times the palette repeats across the iteration range", 1f, 8f, 1f, 3f),
        Parameter.NumberParam("Speed", "speed", ParamGroup.FLOW_MOTION, "Animation speed", 0.1f, 3.0f, 0.1f, 0.5f)
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "style" to "stripe",
        "movement" to "dive",
        "centerX" to -0.5f,
        "centerY" to 0f,
        "zoom" to 1f,
        "maxIterations" to 100f,
        "colorCycles" to 3f,
        "speed" to 0.5f
    )

    private val zoomTargets = arrayOf(
        doubleArrayOf(-0.7435669, 0.1314023),
        doubleArrayOf(-0.1011, 0.9563),
        doubleArrayOf(-1.25066, 0.02012),
        doubleArrayOf(-0.7463, 0.1102),
        doubleArrayOf(0.001643721971153, 0.822467633298876),
        doubleArrayOf(-0.16, 1.0405),
        doubleArrayOf(-1.7497591451, 0.0),
        doubleArrayOf(-0.749, 0.1),
        doubleArrayOf(-0.5621, 0.6427),
        doubleArrayOf(0.2501, 0.0),
        doubleArrayOf(-0.1528, 1.0397),
        doubleArrayOf(-0.925, 0.266)
    )

    override fun bindUniforms(
        programId: Int, params: Map<String, Any>, seed: Int, palette: Palette,
        quality: Quality, time: Float, width: Int, height: Int
    ) {
        val style = (params["style"] as? String) ?: "stripe"
        val baseCenterX = (params["centerX"] as? Number)?.toDouble() ?: -0.5
        val baseCenterY = (params["centerY"] as? Number)?.toDouble() ?: 0.0
        val baseZoom = (params["zoom"] as? Number)?.toDouble() ?: 1.0
        val maxIter = (params["maxIterations"] as? Number)?.toInt() ?: 100
        val colorCycles = (params["colorCycles"] as? Number)?.toFloat() ?: 3f
        val speed = (params["speed"] as? Number)?.toFloat() ?: 0.5f
        val movement = (params["movement"] as? String) ?: "dive"

        val scaledMaxIter = when (quality) {
            Quality.DRAFT -> (maxIter / 2).coerceAtLeast(16)
            Quality.BALANCED -> maxIter
            Quality.ULTRA -> (maxIter * 1.5f).toInt()
        }

        val isAnim = time > 0f

        val rng = SeededRNG(seed)
        val target = zoomTargets[rng.integer(0, zoomTargets.size - 1)]

        val animCenterX: Double
        val animCenterY: Double
        val animZoom: Double
        val cosRot: Double
        val sinRot: Double

        if (isAnim) {
            val t = time.toDouble() * speed
            when (movement) {
                "orbit" -> {
                    val orbitRadius = 0.3 + rng.randomDouble() * 0.5
                    val startAngle = rng.randomDouble() * 2.0 * PI
                    val orbitSpeed = 0.15 + rng.randomDouble() * 0.1
                    val angle = startAngle + t * orbitSpeed
                    val orbitCx = -0.5 + orbitRadius * cos(angle) * 0.8
                    val orbitCy = orbitRadius * sin(angle) * 0.7
                    animCenterX = baseCenterX + (orbitCx - baseCenterX) * 0.7
                    animCenterY = baseCenterY + (orbitCy - baseCenterY) * 0.7
                    animZoom = baseZoom * (2.0 + 0.5 * sin(t * 0.1))
                    val rotAngle = t * 0.02
                    cosRot = cos(rotAngle); sinRot = sin(rotAngle)
                }
                "pulse" -> {
                    val breathDepth = 1.5 + rng.randomDouble() * 1.0
                    animZoom = baseZoom * (1.0 + breathDepth * (0.5 + 0.5 * sin(t * 0.25)))
                    val wobbleAmpX = 0.02 + rng.randomDouble() * 0.03
                    val wobbleAmpY = 0.02 + rng.randomDouble() * 0.03
                    val phaseX = rng.randomDouble() * 2.0 * PI
                    val phaseY = rng.randomDouble() * 2.0 * PI
                    animCenterX = baseCenterX + wobbleAmpX * sin(t * 0.3 + phaseX)
                    animCenterY = baseCenterY + wobbleAmpY * sin(t * 0.2 + phaseY)
                    val rotAngle = t * 0.015
                    cosRot = cos(rotAngle); sinRot = sin(rotAngle)
                }
                "wander" -> {
                    val freqX = (2 + rng.integer(0, 3)).toDouble()
                    val freqY = (3 + rng.integer(0, 3)).toDouble()
                    val phaseOffset = rng.randomDouble() * 2.0 * PI
                    val ampX = 0.3 + rng.randomDouble() * 0.2
                    val ampY = 0.25 + rng.randomDouble() * 0.15
                    val wanderSpeed = 0.08
                    animCenterX = baseCenterX + ampX * sin(freqX * t * wanderSpeed)
                    animCenterY = baseCenterY + ampY * sin(freqY * t * wanderSpeed + phaseOffset)
                    animZoom = baseZoom * (2.0 + 1.0 * sin(t * 0.05))
                    val rotAngle = t * 0.03
                    cosRot = cos(rotAngle); sinRot = sin(rotAngle)
                }
                else -> {
                    animZoom = baseZoom * exp(t * 0.3)
                    val lerpT = (1.0 - 1.0 / (1.0 + t * 0.15)).coerceIn(0.0, 0.95)
                    animCenterX = baseCenterX + (target[0] - baseCenterX) * lerpT
                    animCenterY = baseCenterY + (target[1] - baseCenterY) * lerpT
                    val rotAngle = t * 0.04
                    cosRot = cos(rotAngle); sinRot = sin(rotAngle)
                }
            }
        } else {
            animCenterX = baseCenterX; animCenterY = baseCenterY
            animZoom = baseZoom
            cosRot = 1.0; sinRot = 0.0
        }

        val aspect = width.toDouble() / height.toDouble()
        val rangeY = 2.6 / animZoom
        val rangeX = rangeY * aspect
        val pixelSize = rangeX / width
        val timeShift = time * speed * 0.02f
        val styleIdx = when (style) { "stripe" -> 1; "glow" -> 2; else -> 0 }

        GLES30.glUniform2f(GLES30.glGetUniformLocation(programId, "uCenter"),
            animCenterX.toFloat(), animCenterY.toFloat())
        GLES30.glUniform2f(GLES30.glGetUniformLocation(programId, "uRange"),
            rangeX.toFloat(), rangeY.toFloat())
        GLES30.glUniform2f(GLES30.glGetUniformLocation(programId, "uRot"),
            cosRot.toFloat(), sinRot.toFloat())
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uMaxIter"), scaledMaxIter)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uColorCycles"), colorCycles)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uTimeShift"), timeShift)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uStyle"), styleIdx)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uPixelSize"), pixelSize.toFloat())
    }

    override fun fragmentShaderSource(): String = """#version 300 es
        precision highp float;

        uniform vec2 uResolution;
        uniform float uTime;
        uniform vec3 uAudio;
        ${PaletteUniform.GLSL_HELPERS}

        uniform vec2 uCenter;
        uniform vec2 uRange;
        uniform vec2 uRot;       // (cos, sin)
        uniform int  uMaxIter;
        uniform float uColorCycles;
        uniform float uTimeShift;
        uniform int  uStyle;     // 0 smooth, 1 stripe, 2 glow
        uniform float uPixelSize;

        out vec4 fragColor;

        const float ESCAPE_R2  = 65536.0;
        const float LN_ESC_R   = 5.5451774; // ln(256)
        const float INV_LN2    = 1.4426950; // 1/ln(2)

        float cyclic(float v) { return fract(v); }

        void main() {
            // Map fragment to complex plane with rotation.
            vec2 uv = gl_FragCoord.xy / uResolution - 0.5;
            vec2 raw = uv * uRange;
            float cr = uCenter.x + raw.x * uRot.x - raw.y * uRot.y;
            float ci = uCenter.y + raw.x * uRot.y + raw.y * uRot.x;
            vec2 c = vec2(cr, ci);

            // Cardioid and period-2 bulb skip (~35% of pixels guaranteed inside).
            float crm = c.x - 0.25;
            float ci2 = c.y * c.y;
            float q = crm * crm + ci2;
            bool inside = (q * (q + crm) <= 0.25 * ci2);
            float crp = c.x + 1.0;
            if (!inside) inside = (crp * crp + ci2 <= 0.0625);

            vec3 col;
            if (inside) {
                col = palette_color(0.0) * 0.1;
            } else {
                float zr = 0.0; float zi = 0.0;
                int iter = 0;
                float stripeSum = 0.0; float lastStripeVal = 0.0;
                float dzr = 0.0; float dzi = 0.0;

                // Single iteration loop; per-style branches inside are cheap
                // (uStyle is uniform so they compile to predictable paths).
                for (int i = 0; i < 512; i++) {
                    if (i >= uMaxIter) break;
                    float zr2 = zr * zr;
                    float zi2 = zi * zi;
                    float zmag2 = zr2 + zi2;
                    if (zmag2 > ESCAPE_R2) break;

                    if (uStyle == 1) {
                        lastStripeVal = zi2 / (zmag2 + 1e-30);
                        stripeSum += lastStripeVal;
                    } else if (uStyle == 2) {
                        // Derivative dz = 2*z*dz + 1
                        float ndr = 2.0 * (zr * dzr - zi * dzi) + 1.0;
                        float ndi = 2.0 * (zr * dzi + zi * dzr);
                        dzr = ndr; dzi = ndi;
                    }

                    float tmp = zr2 - zi2 + c.x;
                    zi = 2.0 * zr * zi + c.y;
                    zr = tmp;
                    iter++;
                }

                if (iter >= uMaxIter) {
                    col = palette_color(0.0) * 0.1;
                } else {
                    float zmag = sqrt(zr * zr + zi * zi);
                    float smoothIter = float(iter) + 1.0 - log(log(zmag) / LN_ESC_R) * INV_LN2;

                    if (uStyle == 1) {
                        // Stripe
                        float frac = smoothIter - floor(smoothIter);
                        float avg1 = stripeSum / float(iter);
                        float avg2 = (iter > 1) ? ((stripeSum - lastStripeVal) / float(iter - 1)) : avg1;
                        float stripe = avg2 + (avg1 - avg2) * frac;
                        float baseT = smoothIter / float(uMaxIter) * uColorCycles;
                        float modulated = baseT + stripe * 0.6;
                        float rawT = cyclic(modulated + uTimeShift);
                        col = palette_color(rawT);
                    } else if (uStyle == 2) {
                        // Glow (distance estimate)
                        float dzmag = sqrt(dzr * dzr + dzi * dzi);
                        float dist = 2.0 * zmag * log(zmag) / max(dzmag, 1e-20);
                        float glow = clamp(dist / uPixelSize * 0.5, 0.0, 1.5);
                        float brightness = pow(glow, 0.35);
                        float rawT = cyclic(smoothIter / float(uMaxIter) * uColorCycles + uTimeShift);
                        col = clamp(palette_color(rawT) * brightness, 0.0, 1.0);
                    } else {
                        // Smooth
                        float rawT = cyclic(smoothIter / float(uMaxIter) * uColorCycles + uTimeShift);
                        col = palette_color(rawT);
                    }
                }
            }
            fragColor = vec4(col, 1.0);
        }
    """
}
