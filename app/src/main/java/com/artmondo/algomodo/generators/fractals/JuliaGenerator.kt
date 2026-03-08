package com.artmondo.algomodo.generators.fractals

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import com.artmondo.algomodo.data.palettes.Palette
import com.artmondo.algomodo.generators.Generator
import com.artmondo.algomodo.generators.ParamGroup
import com.artmondo.algomodo.generators.Parameter
import com.artmondo.algomodo.generators.Quality
import kotlin.math.*

class JuliaGenerator : Generator {

    override val id = "fractal-julia"
    override val family = "fractals"
    override val styleName = "Julia Set"
    override val definition =
        "Julia set fractal for the quadratic map z -> z^2 + c, where c is a complex constant parameter."
    override val algorithmNotes =
        "Each pixel is the initial z value; we iterate z = z^2 + c and count iterations until " +
        "|z| > 2 or maxIterations is reached. Smooth coloring is applied using the normalized " +
        "iteration count. The c parameter is animated over time using sin/cos to trace a smooth " +
        "path through parameter space, creating morphing Julia sets."
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
        val baseCx = (params["cReal"] as? Number)?.toDouble() ?: -0.7
        val baseCy = (params["cImag"] as? Number)?.toDouble() ?: 0.27
        val zoom = (params["zoom"] as? Number)?.toFloat() ?: 1f
        val maxIter = (params["maxIterations"] as? Number)?.toInt() ?: 100
        val colorMode = (params["colorMode"] as? String) ?: "smooth"
        val colorCycles = (params["colorCycles"] as? Number)?.toFloat() ?: 3f
        val bandCount = (params["bandCount"] as? Number)?.toInt() ?: 8
        val speed = (params["speed"] as? Number)?.toFloat() ?: 0.5f

        // Animate c parameter: orbit around the base c value using lissajous path
        val orbitRadius = 0.12
        val cr = baseCx + orbitRadius * sin(time.toDouble() * speed * 0.4)
        val ci = baseCy + orbitRadius * cos(time.toDouble() * speed * 0.55)

        val aspect = w.toFloat() / h.toFloat()
        val rangeY = 3.0 / zoom
        val rangeX = rangeY * aspect

        val pixels = IntArray(w * h)
        val ln2 = ln(2.0)

        for (py in 0 until h) {
            for (px in 0 until w) {
                var zr = (px.toDouble() / w - 0.5) * rangeX
                var zi = (py.toDouble() / h - 0.5) * rangeY

                var iter = 0
                while (iter < maxIter && zr * zr + zi * zi <= 4.0) {
                    val tmp = zr * zr - zi * zi + cr
                    zi = 2.0 * zr * zi + ci
                    zr = tmp
                    iter++
                }

                val color = if (iter >= maxIter) {
                    // Inside — darkened palette
                    val dark = palette.lerpColor(0.5f)
                    Color.rgb(
                        (Color.red(dark) * 0.08f).toInt(),
                        (Color.green(dark) * 0.08f).toInt(),
                        (Color.blue(dark) * 0.08f).toInt()
                    )
                } else {
                    val mag2 = zr * zr + zi * zi
                    val logZn = ln(mag2) / 2.0
                    val nu = ln(logZn / ln2) / ln2
                    val smoothIter = (iter + 1 - nu).toFloat()

                    when (colorMode) {
                        "bands" -> {
                            // Stepped contour bands
                            val band = (smoothIter * colorCycles / maxIter * bandCount).toInt() % bandCount
                            val t = band.toFloat() / (bandCount - 1).coerceAtLeast(1)
                            palette.lerpColor(t)
                        }
                        else -> {
                            // Smooth continuous gradient
                            val t = ((smoothIter / maxIter * colorCycles) % 1.0).toFloat()
                            palette.lerpColor(t.coerceIn(0f, 1f))
                        }
                    }
                }

                pixels[py * w + px] = color
            }
        }

        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
        canvas.drawBitmap(bitmap, 0f, 0f, null)
    }

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float {
        val maxIter = (params["maxIterations"] as? Number)?.toInt() ?: 100
        return (maxIter / 500f).coerceIn(0.2f, 1f)
    }
}
