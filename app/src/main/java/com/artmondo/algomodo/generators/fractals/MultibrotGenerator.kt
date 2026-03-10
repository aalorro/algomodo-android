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

class MultibrotGenerator : Generator {

    override val id = "fractal-multibrot"
    override val family = "fractals"
    override val styleName = "Multibrot"
    override val definition =
        "Generalization of the Mandelbrot set to arbitrary powers: z = z^d + c, producing d-1 fold symmetry."
    override val algorithmNotes =
        "For each pixel as complex c, iterates z = z^d + c where d is the exponent parameter. " +
        "d=2 gives the classic Mandelbrot; d=3 gives 2-fold symmetry; higher powers produce " +
        "increasingly star-shaped fractals with d-1 lobes. Smooth coloring uses normalized iteration " +
        "count and the escape radius is adjusted for the power."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema: List<Parameter> = listOf(
        Parameter.NumberParam("Power", "power", ParamGroup.COMPOSITION, "Exponent d — integer values give clean symmetry, fractional values create novel shapes", 2f, 8f, 0.5f, 3f),
        Parameter.NumberParam("Center X", "centerX", ParamGroup.COMPOSITION, "Real-axis center of the view", -2f, 2f, 0.05f, 0f),
        Parameter.NumberParam("Center Y", "centerY", ParamGroup.COMPOSITION, "Imaginary-axis center of the view", -2f, 2f, 0.05f, 0f),
        Parameter.NumberParam("Zoom", "zoom", ParamGroup.COMPOSITION, "Zoom level into the fractal", 0.5f, 4f, 0.5f, 1f),
        Parameter.NumberParam("Max Iterations", "maxIterations", ParamGroup.COMPOSITION, "Higher = more detail in boundary regions but slower", 32f, 256f, 16f, 100f),
        Parameter.NumberParam("Color Cycles", "colorCycles", ParamGroup.COLOR, "How many times the palette repeats across the iteration range", 1f, 8f, 1f, 3f),
        Parameter.NumberParam("Speed", "speed", ParamGroup.FLOW_MOTION, "Animation speed — power oscillates gently over time", 0.1f, 3.0f, 0.1f, 0.5f)
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
        val basePower = (params["power"] as? Number)?.toDouble() ?: 3.0
        val centerX = (params["centerX"] as? Number)?.toDouble() ?: 0.0
        val centerY = (params["centerY"] as? Number)?.toDouble() ?: 0.0
        val zoom = (params["zoom"] as? Number)?.toFloat() ?: 1f
        val maxIter = (params["maxIterations"] as? Number)?.toInt() ?: 100
        val colorCycles = (params["colorCycles"] as? Number)?.toFloat() ?: 3f
        val speed = (params["speed"] as? Number)?.toFloat() ?: 0.5f

        // Animate power with a gentle oscillation
        val power = basePower + sin(time.toDouble() * speed * 0.3) * 0.3

        val aspect = w.toDouble() / h.toDouble()
        val rangeY = 3.0 / zoom
        val rangeX = rangeY * aspect

        // Escape radius depends on power — must be > 1 for convergence
        val escapeR = 2.0.pow(1.0 / (power - 1)).coerceAtLeast(2.0)
        val escapeR2 = escapeR * escapeR

        val pixels = IntArray(w * h)
        val lnPower = ln(power)

        for (py in 0 until h) {
            for (px in 0 until w) {
                val cr = centerX + (px.toDouble() / w - 0.5) * rangeX
                val ci = centerY + (py.toDouble() / h - 0.5) * rangeY

                var zr = 0.0
                var zi = 0.0
                var iter = 0

                while (iter < maxIter && zr * zr + zi * zi <= escapeR2) {
                    // z^d in polar form: r^d * (cos(d*theta) + i*sin(d*theta))
                    val r = sqrt(zr * zr + zi * zi)
                    val theta = atan2(zi, zr)
                    val rD = r.pow(power)
                    val dTheta = power * theta
                    zr = rD * cos(dTheta) + cr
                    zi = rD * sin(dTheta) + ci
                    iter++
                }

                val color = if (iter >= maxIter) {
                    val dark = palette.lerpColor(0.0f)
                    Color.rgb(
                        (Color.red(dark) * 0.1f).toInt(),
                        (Color.green(dark) * 0.1f).toInt(),
                        (Color.blue(dark) * 0.1f).toInt()
                    )
                } else {
                    // Smooth coloring for arbitrary power
                    val mag = sqrt(zr * zr + zi * zi)
                    val smoothIter = iter + 1 - ln(ln(mag)) / lnPower
                    val t = ((smoothIter / maxIter * colorCycles) % 1.0).toFloat()
                    val shifted = ((t + time * speed * 0.02f) % 1f + 1f) % 1f
                    palette.lerpColor(shifted)
                }

                pixels[py * w + px] = color
            }
        }

        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
        canvas.drawBitmap(bitmap, 0f, 0f, null)
    }

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float {
        val maxIter = (params["maxIterations"] as? Number)?.toInt() ?: 100
        val power = (params["power"] as? Number)?.toFloat() ?: 3f
        return (maxIter * power / 1000f).coerceIn(0.2f, 1f)
    }
}
