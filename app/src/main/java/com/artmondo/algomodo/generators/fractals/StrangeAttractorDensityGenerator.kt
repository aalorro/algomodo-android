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

        // Generate attractor parameters from seed
        val rng = SeededRNG(seed)
        val anim = time * speed * 0.1f

        val pa = rng.range(-2.5f, 2.5f) + sin(anim * 0.7f) * 0.15f
        val pb = rng.range(-2.5f, 2.5f) + cos(anim * 0.9f) * 0.15f
        val pc = rng.range(-2.5f, 2.5f) + sin(anim * 1.1f) * 0.15f
        val pd = rng.range(-2.5f, 2.5f) + cos(anim * 1.3f) * 0.15f

        val a = pa.toDouble()
        val b = pb.toDouble()
        val c = pc.toDouble()
        val d = pd.toDouble()

        // Iterate the attractor and collect points
        var x = 0.1
        var y = 0.1
        val bound = 1e6

        val pointsX = FloatArray(scaledIterations)
        val pointsY = FloatArray(scaledIterations)
        val pointVel = FloatArray(scaledIterations)
        val pointAngle = FloatArray(scaledIterations)
        val pointValid = BooleanArray(scaledIterations)

        for (i in 0 until scaledIterations) {
            val nx: Double
            val ny: Double

            when (attractor) {
                "clifford" -> {
                    nx = sin(a * y) + c * cos(a * x)
                    ny = sin(b * x) + d * cos(b * y)
                }
                "dejong" -> {
                    nx = sin(a * y) - cos(b * x)
                    ny = sin(c * x) - cos(d * y)
                }
                "svensson" -> {
                    nx = d * sin(a * x) - sin(b * y)
                    ny = c * cos(a * x) + cos(b * y)
                }
                "bedhead" -> {
                    val bSafe = if (abs(b) < 0.01) 0.01 else b
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
                val dx = (nx - x).toFloat()
                val dy = (ny - y).toFloat()
                pointVel[i] = sqrt(dx * dx + dy * dy)
                pointAngle[i] = ((atan2(dy, dx) / PI.toFloat()) + 1f) * 0.5f
                x = nx
                y = ny
            } else {
                // Reset to avoid permanent divergence
                x = 0.1
                y = 0.1
            }

            pointsX[i] = x.toFloat()
            pointsY[i] = y.toFloat()
            pointValid[i] = valid
        }

        // Compute bounds (skip warmup, only valid finite points)
        val skip = 100.coerceAtMost(scaledIterations)
        var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (i in skip until scaledIterations) {
            if (!pointValid[i]) continue
            val px = pointsX[i]
            val py = pointsY[i]
            if (!px.isFinite() || !py.isFinite()) continue
            if (px < minX) minX = px
            if (px > maxX) maxX = px
            if (py < minY) minY = py
            if (py > maxY) maxY = py
        }

        // If bounds are invalid, nothing to render
        if (!minX.isFinite() || !maxX.isFinite() || minX >= maxX) {
            bitmap.setPixels(IntArray(w * h), 0, w, 0, 0, w, h)
            canvas.drawBitmap(bitmap, 0f, 0f, null)
            return
        }

        val rangeX = (maxX - minX).coerceAtLeast(0.001f)
        val rangeY = (maxY - minY).coerceAtLeast(0.001f)
        val margin = 0.05f

        // Accumulate histogram
        val histogram = IntArray(w * h)
        val colorAcc = FloatArray(w * h) // for velocity/angle coloring

        for (i in skip until scaledIterations) {
            if (!pointValid[i]) continue
            val sx = ((pointsX[i] - minX) / rangeX * (1f - 2f * margin) + margin) * w
            val sy = (1f - ((pointsY[i] - minY) / rangeY * (1f - 2f * margin) + margin)) * h
            val px = sx.toInt()
            val py = sy.toInt()

            if (px in 0 until w && py in 0 until h) {
                val idx = py * w + px
                histogram[idx]++
                when (colorMode) {
                    "velocity" -> colorAcc[idx] += pointVel[i]
                    "angle" -> colorAcc[idx] += pointAngle[i]
                }
            }
        }

        // Render with log-density tone mapping
        var maxDensity = 1
        for (d in histogram) {
            if (d > maxDensity) maxDensity = d
        }
        val logMax = ln(maxDensity.toFloat() + 1f)
        val invGamma = 1.0 / gamma

        val pixels = IntArray(w * h)
        for (i in pixels.indices) {
            if (histogram[i] > 0) {
                val alpha = (ln(histogram[i].toFloat() + 1f) / logMax).toDouble().pow(invGamma).toFloat()

                val t = when (colorMode) {
                    "velocity" -> {
                        val avgVel = colorAcc[i] / histogram[i]
                        (avgVel * 2f).coerceIn(0f, 1f)
                    }
                    "angle" -> {
                        (colorAcc[i] / histogram[i]).coerceIn(0f, 1f)
                    }
                    else -> alpha // density
                }

                val baseColor = palette.lerpColor(t)
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
