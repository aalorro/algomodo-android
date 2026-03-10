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
import kotlin.math.ln
import kotlin.math.sin

class MandelbrotGenerator : Generator {

    override val id = "fractal-mandelbrot"
    override val family = "fractals"
    override val styleName = "Mandelbrot Set"
    override val definition =
        "The classic Mandelbrot set fractal rendered with the escape-time algorithm, mapping iteration counts to palette colors."
    override val algorithmNotes =
        "For each pixel, iterates z = z^2 + c where c is the complex coordinate. " +
        "Pixels that don't escape after maxIterations are considered inside the set (colored black). " +
        "Smooth coloring uses a normalized iteration count to eliminate banding artifacts. " +
        "Animation zooms toward a seed-selected interesting region of the boundary."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema: List<Parameter> = listOf(
        Parameter.NumberParam("Center X", "centerX", ParamGroup.COMPOSITION, "Real-axis center of the view", -1.5f, 0.5f, 0.05f, -0.5f),
        Parameter.NumberParam("Center Y", "centerY", ParamGroup.COMPOSITION, "Imaginary-axis center of the view", -1f, 1f, 0.05f, 0f),
        Parameter.NumberParam("Zoom", "zoom", ParamGroup.COMPOSITION, "Zoom level — higher values zoom deeper into the fractal", 0.5f, 4f, 0.5f, 1f),
        Parameter.NumberParam("Max Iterations", "maxIterations", ParamGroup.COMPOSITION, "Higher = more detail in boundary regions but slower", 32f, 256f, 16f, 100f),
        Parameter.NumberParam("Color Cycles", "colorCycles", ParamGroup.COLOR, "How many times the palette repeats across the iteration range", 1f, 8f, 1f, 3f),
        Parameter.NumberParam("Speed", "speed", ParamGroup.FLOW_MOTION, "Animation zoom speed", 0.1f, 3.0f, 0.1f, 0.5f)
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
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
        doubleArrayOf(-0.749, 0.1)                 // Classic seahorse
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
        val baseCenterX = (params["centerX"] as? Number)?.toDouble() ?: -0.5
        val baseCenterY = (params["centerY"] as? Number)?.toDouble() ?: 0.0
        val baseZoom = (params["zoom"] as? Number)?.toFloat() ?: 1f
        val maxIter = (params["maxIterations"] as? Number)?.toInt() ?: 100
        val colorCycles = (params["colorCycles"] as? Number)?.toFloat() ?: 3f
        val speed = (params["speed"] as? Number)?.toFloat() ?: 0.5f

        val scaledMaxIter = when (quality) {
            Quality.DRAFT -> (maxIter / 2).coerceAtLeast(16)
            Quality.BALANCED -> maxIter
            Quality.ULTRA -> (maxIter * 1.5f).toInt()
        }

        // Pick a zoom target from seed
        val rng = SeededRNG(seed)
        val target = zoomTargets[rng.integer(0, zoomTargets.size - 1)]

        // Animate: smoothly zoom toward target, lerping center
        val zoomFactor = baseZoom * (1f + time * speed * 0.5f)
        val lerpT = (1.0 - 1.0 / (1.0 + time * speed * 0.1)).coerceIn(0.0, 0.95)
        val centerX = baseCenterX + (target[0] - baseCenterX) * lerpT
        val centerY = baseCenterY + (target[1] - baseCenterY) * lerpT

        val aspect = w.toDouble() / h.toDouble()
        val rangeY = 2.0 / zoomFactor
        val rangeX = rangeY * aspect

        val pixels = IntArray(w * h)
        val ln2 = ln(2.0)

        // Precompute palette LUT to avoid per-pixel string parsing
        val lutSize = 256
        val paletteLut = IntArray(lutSize) { palette.lerpColor(it.toFloat() / (lutSize - 1)) }
        val darkBase = palette.lerpColor(0.0f)
        val insideColor = Color.rgb(
            (Color.red(darkBase) * 0.1f).toInt(),
            (Color.green(darkBase) * 0.1f).toInt(),
            (Color.blue(darkBase) * 0.1f).toInt()
        )

        val timeShift = time * speed * 0.02f
        val invW = 1.0 / w
        val invH = 1.0 / h

        // Parallel row processing
        val cores = Runtime.getRuntime().availableProcessors().coerceIn(2, 8)
        val threads = Array(cores) { t ->
            Thread {
                val y0 = t * h / cores
                val y1 = (t + 1) * h / cores
                for (py in y0 until y1) {
                    val ciBase = centerY + (py * invH - 0.5) * rangeY
                    for (px in 0 until w) {
                        val cr = centerX + (px * invW - 0.5) * rangeX
                        val ci = ciBase

                        var zr = 0.0
                        var zi = 0.0
                        var iter = 0

                        while (iter < scaledMaxIter && zr * zr + zi * zi <= 4.0) {
                            val tmp = zr * zr - zi * zi + cr
                            zi = 2.0 * zr * zi + ci
                            zr = tmp
                            iter++
                        }

                        val color = if (iter >= scaledMaxIter) {
                            insideColor
                        } else {
                            val mag2 = zr * zr + zi * zi
                            val logZn = ln(mag2) / 2.0
                            val nu = ln(logZn / ln2) / ln2
                            val smoothIter = iter + 1 - nu
                            val rawT = ((smoothIter / scaledMaxIter * colorCycles) % 1.0).toFloat()
                            val shifted = ((rawT + timeShift) % 1f + 1f) % 1f
                            paletteLut[(shifted * (lutSize - 1)).toInt().coerceIn(0, lutSize - 1)]
                        }

                        pixels[py * w + px] = color
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
        return (maxIter / 500f).coerceIn(0.2f, 1f)
    }
}
