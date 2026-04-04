package com.artmondo.algomodo.generators.fractals

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import com.artmondo.algomodo.core.rng.SeededRNG
import com.artmondo.algomodo.data.palettes.Palette
import com.artmondo.algomodo.generators.Generator
import com.artmondo.algomodo.generators.ParamGroup
import com.artmondo.algomodo.generators.Parameter
import com.artmondo.algomodo.generators.Quality
import kotlin.math.*

class MandelbrotGenerator : Generator {

    override val id = "fractal-mandelbrot"
    override val family = "fractals"
    override val styleName = "Mandelbrot Set"
    override val definition =
        "The classic Mandelbrot set fractal rendered with the escape-time algorithm, mapping iteration counts to palette colors."
    override val algorithmNotes =
        "For each pixel, iterates z = z^2 + c where c is the complex coordinate. " +
        "Pixels that don't escape after maxIterations are considered inside the set (colored black). " +
        "Three coloring styles: smooth (classic), stripe (fine-detail patterns via orbit statistics), " +
        "and glow (boundary edge glow via distance estimation). Animation zooms exponentially " +
        "toward a seed-selected boundary region with gentle rotation."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema: List<Parameter> = listOf(
        Parameter.SelectParam("Style", "style", ParamGroup.COMPOSITION, "smooth: classic gradient | stripe: fine detail patterns | glow: boundary edge glow", listOf("smooth", "stripe", "glow"), "stripe"),
        Parameter.NumberParam("Center X", "centerX", ParamGroup.COMPOSITION, "Real-axis center of the view", -1.5f, 0.5f, 0.05f, -0.5f),
        Parameter.NumberParam("Center Y", "centerY", ParamGroup.COMPOSITION, "Imaginary-axis center of the view", -1f, 1f, 0.05f, 0f),
        Parameter.NumberParam("Zoom", "zoom", ParamGroup.COMPOSITION, "Zoom level — higher values zoom deeper into the fractal", 0.5f, 4f, 0.5f, 1f),
        Parameter.NumberParam("Max Iterations", "maxIterations", ParamGroup.COMPOSITION, "Higher = more detail in boundary regions but slower", 32f, 256f, 16f, 100f),
        Parameter.NumberParam("Color Cycles", "colorCycles", ParamGroup.COLOR, "How many times the palette repeats across the iteration range", 1f, 8f, 1f, 3f),
        Parameter.NumberParam("Speed", "speed", ParamGroup.FLOW_MOTION, "Animation speed — exponential zoom into fractal boundary with rotation", 0.1f, 3.0f, 0.1f, 0.5f)
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "style" to "stripe",
        "centerX" to -0.5f,
        "centerY" to 0f,
        "zoom" to 1f,
        "maxIterations" to 100f,
        "colorCycles" to 3f,
        "speed" to 0.5f
    )

    // Interesting zoom targets on the Mandelbrot boundary
    private val zoomTargets = arrayOf(
        doubleArrayOf(-0.7435669, 0.1314023),    // Seahorse valley
        doubleArrayOf(-0.1011, 0.9563),           // Elephant valley
        doubleArrayOf(-1.25066, 0.02012),          // Needle tip
        doubleArrayOf(-0.7463, 0.1102),            // Double spiral
        doubleArrayOf(0.001643721971153, 0.822467633298876), // Deep zoom
        doubleArrayOf(-0.16, 1.0405),              // Julia island
        doubleArrayOf(-1.7497591451, 0.0),         // Tail spike
        doubleArrayOf(-0.749, 0.1),                // Classic seahorse
        doubleArrayOf(-0.5621, 0.6427),            // Mini Mandelbrot
        doubleArrayOf(0.2501, 0.0),                // Period boundary
        doubleArrayOf(-0.1528, 1.0397),            // Branching filament
        doubleArrayOf(-0.925, 0.266)               // Spiral arm
    )

    override fun renderCanvas(
        canvas: Canvas,
        bitmap: Bitmap,
        params: Map<String, Any>,
        seed: Int,
        palette: Palette,
        quality: Quality,
        time: Float
    ) {
        val w = bitmap.width
        val h = bitmap.height
        val style = (params["style"] as? String) ?: "stripe"
        val baseCenterX = (params["centerX"] as? Number)?.toDouble() ?: -0.5
        val baseCenterY = (params["centerY"] as? Number)?.toDouble() ?: 0.0
        val baseZoom = (params["zoom"] as? Number)?.toDouble() ?: 1.0
        val maxIter = (params["maxIterations"] as? Number)?.toInt() ?: 100
        val colorCycles = (params["colorCycles"] as? Number)?.toFloat() ?: 3f
        val speed = (params["speed"] as? Number)?.toFloat() ?: 0.5f

        val scaledMaxIter = when (quality) {
            Quality.DRAFT -> (maxIter / 2).coerceAtLeast(16)
            Quality.BALANCED -> maxIter
            Quality.ULTRA -> (maxIter * 1.5f).toInt()
        }

        val isAnim = time > 0f

        // Pick a zoom target from seed
        val rng = SeededRNG(seed)
        val target = zoomTargets[rng.integer(0, zoomTargets.size - 1)]

        // ---- Animation: exponential zoom + rotation + smooth pan ----
        val animCenterX: Double
        val animCenterY: Double
        val animZoom: Double
        val cosRot: Double
        val sinRot: Double

        if (isAnim) {
            val t = time.toDouble() * speed
            // Exponential zoom — accelerating dive into the fractal
            animZoom = baseZoom * exp(t * 0.3)
            // S-curve eased pan toward target
            val lerpT = (1.0 - 1.0 / (1.0 + t * 0.15)).coerceIn(0.0, 0.95)
            animCenterX = baseCenterX + (target[0] - baseCenterX) * lerpT
            animCenterY = baseCenterY + (target[1] - baseCenterY) * lerpT
            // Gentle rotation
            val rotAngle = t * 0.04
            cosRot = cos(rotAngle)
            sinRot = sin(rotAngle)
        } else {
            animCenterX = baseCenterX
            animCenterY = baseCenterY
            animZoom = baseZoom
            cosRot = 1.0
            sinRot = 0.0
        }

        val aspect = w.toDouble() / h.toDouble()
        val rangeY = 2.6 / animZoom
        val rangeX = rangeY * aspect

        // Large escape radius for smoother coloring and accurate distance estimation
        val escapeR2 = 65536.0 // escape radius = 256
        val ln2 = ln(2.0)
        val lnEscR = ln(256.0)

        val pixels = IntArray(w * h)

        val lutSize = 256
        val lutMax = lutSize - 1
        val paletteLut = IntArray(lutSize) { palette.lerpColor(it.toFloat() / lutMax) }
        val darkBase = palette.lerpColor(0.0f)
        val insideColor = Color.rgb(
            (Color.red(darkBase) * 0.1f).toInt(),
            (Color.green(darkBase) * 0.1f).toInt(),
            (Color.blue(darkBase) * 0.1f).toInt()
        )

        val timeShift = time * speed * 0.02f
        val invW = 1.0 / w
        val invH = 1.0 / h

        val styleIdx = when (style) {
            "stripe" -> 1
            "glow" -> 2
            else -> 0
        }
        val pixelSize = rangeX / w

        val cores = Runtime.getRuntime().availableProcessors().coerceIn(2, 8)
        val threads = Array(cores) { t ->
            Thread {
                val y0 = t * h / cores
                val y1 = (t + 1) * h / cores
                for (py in y0 until y1) {
                    val rawY = (py * invH - 0.5) * rangeY
                    for (px in 0 until w) {
                        val rawX = (px * invW - 0.5) * rangeX
                        val cr = animCenterX + rawX * cosRot - rawY * sinRot
                        val ci = animCenterY + rawX * sinRot + rawY * cosRot

                        // --- Cardioid and period-2 bulb skip ---
                        // Skips ~35% of pixels that are guaranteed inside the set.
                        val crm = cr - 0.25
                        val ci2 = ci * ci
                        val q = crm * crm + ci2
                        if (q * (q + crm) <= 0.25 * ci2) {
                            pixels[py * w + px] = insideColor
                            continue
                        }
                        val crp = cr + 1.0
                        if (crp * crp + ci2 <= 0.0625) {
                            pixels[py * w + px] = insideColor
                            continue
                        }

                        var zr = 0.0
                        var zi = 0.0
                        var iter = 0

                        // Style-specific iteration loops avoid per-iteration branching
                        when (styleIdx) {
                            1 -> {
                                // ---- STRIPE: fine-detail patterns via orbit statistics ----
                                var stripeSum = 0.0
                                var lastStripeVal = 0.0
                                var refZr = 0.0; var refZi = 0.0
                                var period = 1; var pCount = 0

                                while (iter < scaledMaxIter) {
                                    val zr2 = zr * zr
                                    val zi2 = zi * zi
                                    val zmag2 = zr2 + zi2
                                    if (zmag2 > escapeR2) break

                                    // Cheap stripe proxy using zi²/|z|² (reuses zmag2)
                                    lastStripeVal = zi2 / (zmag2 + 1e-30)
                                    stripeSum += lastStripeVal

                                    val tmp = zr2 - zi2 + cr
                                    zi = 2.0 * zr * zi + ci
                                    zr = tmp
                                    iter++

                                    // Brent's periodicity detection
                                    val dr = zr - refZr; val di = zi - refZi
                                    if (dr * dr + di * di < 1e-20) {
                                        iter = scaledMaxIter; break
                                    }
                                    if (++pCount >= period) {
                                        refZr = zr; refZi = zi; pCount = 0
                                        period = (period shl 1).coerceAtMost(512)
                                    }
                                }

                                pixels[py * w + px] = if (iter >= scaledMaxIter) {
                                    insideColor
                                } else {
                                    val zmag = sqrt(zr * zr + zi * zi)
                                    val smoothIter = iter + 1.0 - ln(ln(zmag) / lnEscR) / ln2
                                    val frac = smoothIter - floor(smoothIter)
                                    // Smooth stripe interpolation between iter and iter-1
                                    val avg1 = stripeSum / iter
                                    val avg2 = if (iter > 1) (stripeSum - lastStripeVal) / (iter - 1) else avg1
                                    val stripe = avg2 + (avg1 - avg2) * frac

                                    val baseT = smoothIter / scaledMaxIter * colorCycles
                                    val modulated = baseT + stripe * 0.6
                                    val rawT = ((modulated + timeShift) % 1.0 + 1.0) % 1.0
                                    paletteLut[(rawT * lutMax).toInt().coerceIn(0, lutMax)]
                                }
                            }
                            2 -> {
                                // ---- GLOW: boundary edge glow via distance estimation ----
                                var dzr = 0.0; var dzi = 0.0
                                var refZr = 0.0; var refZi = 0.0
                                var period = 1; var pCount = 0

                                while (iter < scaledMaxIter) {
                                    val zr2 = zr * zr
                                    val zi2 = zi * zi
                                    if (zr2 + zi2 > escapeR2) break

                                    // Derivative: dz = 2*z*dz + 1
                                    val ndr = 2.0 * (zr * dzr - zi * dzi) + 1.0
                                    val ndi = 2.0 * (zr * dzi + zi * dzr)
                                    dzr = ndr; dzi = ndi

                                    val tmp = zr2 - zi2 + cr
                                    zi = 2.0 * zr * zi + ci
                                    zr = tmp
                                    iter++

                                    val dr = zr - refZr; val di = zi - refZi
                                    if (dr * dr + di * di < 1e-20) {
                                        iter = scaledMaxIter; break
                                    }
                                    if (++pCount >= period) {
                                        refZr = zr; refZi = zi; pCount = 0
                                        period = (period shl 1).coerceAtMost(512)
                                    }
                                }

                                pixels[py * w + px] = if (iter >= scaledMaxIter) {
                                    insideColor
                                } else {
                                    val zmag = sqrt(zr * zr + zi * zi)
                                    val dzmag = sqrt(dzr * dzr + dzi * dzi)
                                    val dist = 2.0 * zmag * ln(zmag) / dzmag
                                    // Map distance to glow: bright at boundary, fading outward
                                    val glow = (dist / pixelSize * 0.5).coerceIn(0.0, 1.5)
                                    val brightness = glow.pow(0.35)

                                    val smoothIter = iter + 1.0 - ln(ln(zmag) / lnEscR) / ln2
                                    val rawT = ((smoothIter / scaledMaxIter * colorCycles + timeShift) % 1.0 + 1.0) % 1.0
                                    val base = paletteLut[(rawT * lutMax).toInt().coerceIn(0, lutMax)]

                                    val r = ((base shr 16 and 0xFF) * brightness).toInt().coerceIn(0, 255)
                                    val g = ((base shr 8 and 0xFF) * brightness).toInt().coerceIn(0, 255)
                                    val b = ((base and 0xFF) * brightness).toInt().coerceIn(0, 255)
                                    (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                                }
                            }
                            else -> {
                                // ---- SMOOTH: classic gradient (fastest) ----
                                var refZr = 0.0; var refZi = 0.0
                                var period = 1; var pCount = 0

                                while (iter < scaledMaxIter && zr * zr + zi * zi <= escapeR2) {
                                    val tmp = zr * zr - zi * zi + cr
                                    zi = 2.0 * zr * zi + ci
                                    zr = tmp
                                    iter++

                                    val dr = zr - refZr; val di = zi - refZi
                                    if (dr * dr + di * di < 1e-20) {
                                        iter = scaledMaxIter; break
                                    }
                                    if (++pCount >= period) {
                                        refZr = zr; refZi = zi; pCount = 0
                                        period = (period shl 1).coerceAtMost(512)
                                    }
                                }

                                pixels[py * w + px] = if (iter >= scaledMaxIter) {
                                    insideColor
                                } else {
                                    val zmag = sqrt(zr * zr + zi * zi)
                                    val smoothIter = iter + 1.0 - ln(ln(zmag) / lnEscR) / ln2
                                    val rawT = ((smoothIter / scaledMaxIter * colorCycles + timeShift) % 1.0 + 1.0) % 1.0
                                    paletteLut[(rawT * lutMax).toInt().coerceIn(0, lutMax)]
                                }
                            }
                        }
                    }
                }
            }.also { it.start() }
        }
        threads.forEach { it.join() }

        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
        canvas.drawBitmap(bitmap, 0f, 0f, null)
    }

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float {
        val maxIter = (params["maxIterations"] as? Number)?.toInt() ?: 100
        val style = (params["style"] as? String) ?: "stripe"
        val styleMul = when (style) { "glow" -> 1.5f; "stripe" -> 1.2f; else -> 1f }
        return (maxIter * styleMul / 500f).coerceIn(0.2f, 1f)
    }
}
