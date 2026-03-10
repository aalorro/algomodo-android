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

class StrangeAttractorDensityGenerator : Generator {

    override val id = "fractal-strange-attractor"
    override val family = "fractals"
    override val styleName = "Strange Attractor Density"
    override val definition =
        "Density plot of strange attractors — chaotic iterated maps rendered as histograms with log tone mapping."
    override val algorithmNotes =
        "Iterates a 2D chaotic map (Clifford, De Jong, Svensson, or Bedhead) millions of times, " +
        "accumulating point visits into a histogram. The density is rendered with logarithmic tone " +
        "mapping and gamma correction, revealing the intricate fractal structure of the attractor. " +
        "Parameters a, b, c, d are generated from the seed for deterministic variety."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema: List<Parameter> = listOf(
        Parameter.SelectParam(
            name = "Attractor",
            key = "attractor",
            group = ParamGroup.COMPOSITION,
            help = "Type of 2D strange attractor to render",
            options = listOf("clifford", "dejong", "svensson", "bedhead"),
            default = "clifford"
        ),
        Parameter.NumberParam("Iterations", "iterations", ParamGroup.COMPOSITION, "Total sample points — more = smoother rendering", 100000f, 1000000f, 100000f, 500000f),
        Parameter.NumberParam("Gamma", "gamma", ParamGroup.COLOR, "Tone-mapping gamma — lower = brighter midtones", 1f, 5f, 0.5f, 2.5f),
        Parameter.SelectParam(
            name = "Color Mode",
            key = "colorMode",
            group = ParamGroup.COLOR,
            help = "density: by point count | velocity: by step size | angle: by trajectory direction",
            options = listOf("density", "velocity", "angle"),
            default = "density"
        ),
        Parameter.NumberParam("Speed", "speed", ParamGroup.FLOW_MOTION, "Animation speed — smoothly morphs attractor parameters", 0.1f, 3.0f, 0.1f, 0.5f)
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "attractor" to "clifford",
        "iterations" to 500000f,
        "gamma" to 2.5f,
        "colorMode" to "density",
        "speed" to 0.5f
    )

    private companion object {
        const val ATT_CLIFFORD = 0
        const val ATT_DEJONG = 1
        const val ATT_SVENSSON = 2
        const val ATT_BEDHEAD = 3
    }

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
        val attractor = (params["attractor"] as? String) ?: "clifford"
        val iterations = (params["iterations"] as? Number)?.toInt() ?: 500000
        val gamma = (params["gamma"] as? Number)?.toDouble() ?: 2.5
        val colorMode = (params["colorMode"] as? String) ?: "density"
        val speed = (params["speed"] as? Number)?.toFloat() ?: 0.5f

        val scaledIterations = when (quality) {
            Quality.DRAFT -> iterations / 3
            Quality.BALANCED -> iterations
            Quality.ULTRA -> iterations * 2
        }

        val rng = SeededRNG(seed)
        val anim = time * speed * 0.1f

        val a = (rng.range(-2.5f, 2.5f) + sin(anim * 0.7f) * 0.15f).toDouble()
        val b = (rng.range(-2.5f, 2.5f) + cos(anim * 0.9f) * 0.15f).toDouble()
        val c = (rng.range(-2.5f, 2.5f) + sin(anim * 1.1f) * 0.15f).toDouble()
        val d = (rng.range(-2.5f, 2.5f) + cos(anim * 1.3f) * 0.15f).toDouble()

        val attIdx = when (attractor) {
            "clifford" -> ATT_CLIFFORD; "dejong" -> ATT_DEJONG
            "svensson" -> ATT_SVENSSON; "bedhead" -> ATT_BEDHEAD
            else -> ATT_CLIFFORD
        }

        val bound = 1e6
        val skip = 100.coerceAtMost(scaledIterations)
        val trackExtra = colorMode == "velocity" || colorMode == "angle"

        // Single-pass: iterate, compute running bounds, then accumulate histogram
        // Phase 1: iterate and compute bounds
        var x = 0.1
        var y = 0.1
        var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE

        // Store only post-warmup points to save memory
        val numPoints = scaledIterations - skip
        val pointsX = FloatArray(if (numPoints > 0) numPoints else 0)
        val pointsY = FloatArray(if (numPoints > 0) numPoints else 0)
        val pointVel = if (trackExtra) FloatArray(if (numPoints > 0) numPoints else 0) else null
        val pointAngle = if (trackExtra) FloatArray(if (numPoints > 0) numPoints else 0) else null

        val bSafe = if (attIdx == ATT_BEDHEAD && abs(b) < 0.01) 0.01 else b

        for (i in 0 until scaledIterations) {
            val nx: Double
            val ny: Double

            when (attIdx) {
                ATT_CLIFFORD -> {
                    nx = sin(a * y) + c * cos(a * x)
                    ny = sin(b * x) + d * cos(b * y)
                }
                ATT_DEJONG -> {
                    nx = sin(a * y) - cos(b * x)
                    ny = sin(c * x) - cos(d * y)
                }
                ATT_SVENSSON -> {
                    nx = d * sin(a * x) - sin(b * y)
                    ny = c * cos(a * x) + cos(b * y)
                }
                ATT_BEDHEAD -> {
                    nx = sin(x * y / bSafe) * y + cos(a * x - y)
                    ny = x + sin(y) / bSafe
                }
                else -> {
                    nx = sin(a * y) + c * cos(a * x)
                    ny = sin(b * x) + d * cos(b * y)
                }
            }

            val valid = nx.isFinite() && ny.isFinite() && abs(nx) < bound && abs(ny) < bound

            if (valid) {
                if (i >= skip) {
                    val pi = i - skip
                    val pxf = nx.toFloat()
                    val pyf = ny.toFloat()
                    pointsX[pi] = pxf
                    pointsY[pi] = pyf
                    if (pxf < minX) minX = pxf
                    if (pxf > maxX) maxX = pxf
                    if (pyf < minY) minY = pyf
                    if (pyf > maxY) maxY = pyf

                    if (trackExtra) {
                        val dx = (nx - x).toFloat()
                        val dy = (ny - y).toFloat()
                        pointVel!![pi] = sqrt(dx * dx + dy * dy)
                        pointAngle!![pi] = ((atan2(dy, dx) / PI.toFloat()) + 1f) * 0.5f
                    }
                }
                x = nx
                y = ny
            } else {
                x = 0.1
                y = 0.1
                if (i >= skip) {
                    val pi = i - skip
                    pointsX[pi] = Float.NaN
                    pointsY[pi] = Float.NaN
                }
            }
        }

        if (!minX.isFinite() || !maxX.isFinite() || minX >= maxX) {
            bitmap.setPixels(IntArray(w * h), 0, w, 0, 0, w, h)
            canvas.drawBitmap(bitmap, 0f, 0f, null)
            return
        }

        val rangeX = (maxX - minX).coerceAtLeast(0.001f)
        val rangeY = (maxY - minY).coerceAtLeast(0.001f)
        val margin = 0.05f
        val scaleX = (1f - 2f * margin) / rangeX * w
        val scaleY = (1f - 2f * margin) / rangeY * h
        val offsetX = margin * w - minX * scaleX + minX * (1f / rangeX) * (2f * margin) * w
        val offsetY = margin * h - minY * scaleY + minY * (1f / rangeY) * (2f * margin) * h

        // Phase 2: accumulate histogram
        val histogram = IntArray(w * h)
        val colorAcc = if (trackExtra) FloatArray(w * h) else null

        for (i in 0 until numPoints) {
            val pxf = pointsX[i]
            if (!pxf.isFinite()) continue
            val sx = ((pxf - minX) / rangeX * (1f - 2f * margin) + margin) * w
            val sy = (1f - ((pointsY[i] - minY) / rangeY * (1f - 2f * margin) + margin)) * h
            val px = sx.toInt()
            val py = sy.toInt()

            if (px in 0 until w && py in 0 until h) {
                val idx = py * w + px
                histogram[idx]++
                when (colorMode) {
                    "velocity" -> colorAcc!![idx] += pointVel!![i]
                    "angle" -> colorAcc!![idx] += pointAngle!![i]
                }
            }
        }

        // Phase 3: render with precomputed palette LUT
        var maxDensity = 1
        for (dd in histogram) {
            if (dd > maxDensity) maxDensity = dd
        }
        val logMax = ln(maxDensity.toFloat() + 1f)
        val invGamma = 1.0 / gamma

        val lutSize = 256
        val paletteLut = IntArray(lutSize) { palette.lerpColor(it.toFloat() / (lutSize - 1)) }

        val pixels = IntArray(w * h)
        for (i in pixels.indices) {
            if (histogram[i] > 0) {
                val alpha = (ln(histogram[i].toFloat() + 1f) / logMax).toDouble().pow(invGamma).toFloat()

                val t = when (colorMode) {
                    "velocity" -> (colorAcc!![i] / histogram[i] * 2f).coerceIn(0f, 1f)
                    "angle" -> (colorAcc!![i] / histogram[i]).coerceIn(0f, 1f)
                    else -> alpha
                }

                val lutIdx = (t * (lutSize - 1)).toInt().coerceIn(0, lutSize - 1)
                val baseColor = paletteLut[lutIdx]
                val r = (Color.red(baseColor) * alpha).toInt().coerceIn(0, 255)
                val g = (Color.green(baseColor) * alpha).toInt().coerceIn(0, 255)
                val b = (Color.blue(baseColor) * alpha).toInt().coerceIn(0, 255)
                pixels[i] = Color.rgb(r, g, b)
            }
        }

        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
        canvas.drawBitmap(bitmap, 0f, 0f, null)
    }

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float {
        val iterations = (params["iterations"] as? Number)?.toInt() ?: 500000
        return (iterations / 1000000f).coerceIn(0.1f, 1f)
    }
}
