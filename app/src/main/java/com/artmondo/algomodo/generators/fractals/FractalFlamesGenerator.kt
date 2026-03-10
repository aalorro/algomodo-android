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

class FractalFlamesGenerator : Generator {

    override val id = "fractal-flames"
    override val family = "fractals"
    override val styleName = "Fractal Flames"
    override val definition =
        "Fractal flames — an extension of IFS fractals using nonlinear variation functions to produce organic, flame-like structures."
    override val algorithmNotes =
        "Uses the chaos game with 2-4 affine transforms, each followed by a nonlinear variation " +
        "(sinusoidal, spherical, swirl, horseshoe, handkerchief, or disc). Points are accumulated " +
        "into a histogram and rendered with log-density tone mapping, producing the characteristic " +
        "soft glow of fractal flames. Color is blended per-transform via palette interpolation."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema: List<Parameter> = listOf(
        Parameter.SelectParam(
            name = "Variation",
            key = "variation",
            group = ParamGroup.COMPOSITION,
            help = "Primary nonlinear variation applied to each transform",
            options = listOf("sinusoidal", "spherical", "swirl", "horseshoe", "handkerchief", "disc"),
            default = "sinusoidal"
        ),
        Parameter.NumberParam("Transforms", "transforms", ParamGroup.COMPOSITION, "Number of affine transforms in the system", 2f, 4f, 1f, 3f),
        Parameter.NumberParam("Iterations", "iterations", ParamGroup.COMPOSITION, "Total sample points — more = smoother, denser image", 100000f, 500000f, 50000f, 300000f),
        Parameter.NumberParam("Gamma", "gamma", ParamGroup.COLOR, "Tone-mapping gamma — lower = brighter midtones", 1f, 5f, 0.5f, 2.5f),
        Parameter.NumberParam("Zoom", "zoom", ParamGroup.COMPOSITION, "Zoom level into the flame", 0.5f, 3f, 0.5f, 1f),
        Parameter.NumberParam("Speed", "speed", ParamGroup.FLOW_MOTION, "Animation speed — smoothly morphs transform coefficients", 0.1f, 3.0f, 0.1f, 0.5f)
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "variation" to "sinusoidal",
        "transforms" to 3f,
        "iterations" to 300000f,
        "gamma" to 2.5f,
        "zoom" to 1f,
        "speed" to 0.5f
    )

    private fun applyVariation(variation: String, x: Double, y: Double): DoubleArray {
        val r2 = x * x + y * y
        val r = sqrt(r2)
        val theta = atan2(y, x)
        return when (variation) {
            "sinusoidal" -> doubleArrayOf(sin(x), sin(y))
            "spherical" -> {
                val s = 1.0 / (r2 + 1e-10)
                doubleArrayOf(x * s, y * s)
            }
            "swirl" -> {
                val sr = sin(r2)
                val cr = cos(r2)
                doubleArrayOf(x * sr - y * cr, x * cr + y * sr)
            }
            "horseshoe" -> {
                val inv = 1.0 / (r + 1e-10)
                doubleArrayOf((x - y) * (x + y) * inv, 2.0 * x * y * inv)
            }
            "handkerchief" -> {
                doubleArrayOf(r * sin(theta + r), r * cos(theta - r))
            }
            "disc" -> {
                val f = theta / PI
                doubleArrayOf(f * sin(PI * r), f * cos(PI * r))
            }
            else -> doubleArrayOf(sin(x), sin(y))
        }
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
        val variation = (params["variation"] as? String) ?: "sinusoidal"
        val numTransforms = (params["transforms"] as? Number)?.toInt() ?: 3
        val iterations = (params["iterations"] as? Number)?.toInt() ?: 300000
        val gamma = (params["gamma"] as? Number)?.toDouble() ?: 2.5
        val zoom = (params["zoom"] as? Number)?.toFloat() ?: 1f
        val speed = (params["speed"] as? Number)?.toFloat() ?: 0.5f

        val scaledIterations = when (quality) {
            Quality.DRAFT -> iterations / 3
            Quality.BALANCED -> iterations
            Quality.ULTRA -> iterations * 2
        }

        val anim = time * speed * 0.15f

        // Generate seed-deterministic affine transforms
        val rng = SeededRNG(seed)
        data class FlameTransform(
            val a: Double, val b: Double, val c: Double, val d: Double,
            val e: Double, val f: Double, val colorIdx: Float
        )

        val transforms = Array(numTransforms) { i ->
            val angle = rng.range(0f, 2f * PI.toFloat()) + anim * (i + 1) * 0.3f
            val scale = rng.range(0.3f, 0.7f)
            val ca = cos(angle.toDouble()) * scale
            val sa = sin(angle.toDouble()) * scale
            FlameTransform(
                a = ca, b = -sa, c = sa, d = ca,
                e = rng.range(-0.5f, 0.5f).toDouble(),
                f = rng.range(-0.5f, 0.5f).toDouble(),
                colorIdx = i.toFloat() / (numTransforms - 1).coerceAtLeast(1)
            )
        }

        // Histogram accumulation
        val histogram = IntArray(w * h)
        val colorAccR = FloatArray(w * h)
        val colorAccG = FloatArray(w * h)
        val colorAccB = FloatArray(w * h)

        val iterRng = SeededRNG(seed + 1)
        var x = iterRng.range(-1f, 1f).toDouble()
        var y = iterRng.range(-1f, 1f).toDouble()
        var curColor = 0.5f

        val skip = 20
        val viewScale = 1.5 / zoom

        for (i in 0 until scaledIterations) {
            // Pick a random transform
            val tIdx = iterRng.integer(0, numTransforms - 1)
            val t = transforms[tIdx]

            // Apply affine
            val ax = t.a * x + t.b * y + t.e
            val ay = t.c * x + t.d * y + t.f

            // Apply variation
            val v = applyVariation(variation, ax, ay)
            x = v[0]
            y = v[1]

            // Blend color
            curColor = (curColor + t.colorIdx) * 0.5f

            if (i < skip) continue

            // Map to pixel
            val sx = ((x / viewScale + 0.5) * w).toInt()
            val sy = ((y / viewScale + 0.5) * h).toInt()

            if (sx in 0 until w && sy in 0 until h) {
                val idx = sy * w + sx
                histogram[idx]++
                val pc = palette.lerpColor(curColor)
                colorAccR[idx] += Color.red(pc) / 255f
                colorAccG[idx] += Color.green(pc) / 255f
                colorAccB[idx] += Color.blue(pc) / 255f
            }
        }

        // Find max density for log tone mapping
        var maxDensity = 1
        for (d in histogram) {
            if (d > maxDensity) maxDensity = d
        }
        val logMax = ln(maxDensity.toFloat() + 1f)
        val invGamma = 1.0 / gamma

        val pixels = IntArray(w * h)
        for (i in pixels.indices) {
            if (histogram[i] > 0) {
                val count = histogram[i].toFloat()
                val alpha = (ln(count + 1f) / logMax).toDouble().pow(invGamma).toFloat()
                val r = ((colorAccR[i] / count) * alpha * 255f).toInt().coerceIn(0, 255)
                val g = ((colorAccG[i] / count) * alpha * 255f).toInt().coerceIn(0, 255)
                val b = ((colorAccB[i] / count) * alpha * 255f).toInt().coerceIn(0, 255)
                pixels[i] = Color.rgb(r, g, b)
            }
        }

        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
        canvas.drawBitmap(bitmap, 0f, 0f, null)
    }

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float {
        val iterations = (params["iterations"] as? Number)?.toInt() ?: 300000
        return (iterations / 500000f).coerceIn(0.1f, 1f)
    }
}
