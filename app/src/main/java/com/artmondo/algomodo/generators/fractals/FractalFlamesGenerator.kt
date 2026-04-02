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

    // Variation index constants to avoid string comparison in hot loop
    private companion object {
        const val VAR_SINUSOIDAL = 0
        const val VAR_SPHERICAL = 1
        const val VAR_SWIRL = 2
        const val VAR_HORSESHOE = 3
        const val VAR_HANDKERCHIEF = 4
        const val VAR_DISC = 5
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

        val rng = SeededRNG(seed)
        val ta = DoubleArray(numTransforms)
        val tb = DoubleArray(numTransforms)
        val tc = DoubleArray(numTransforms)
        val td = DoubleArray(numTransforms)
        val te = DoubleArray(numTransforms)
        val tf = DoubleArray(numTransforms)
        val tColorIdx = FloatArray(numTransforms)

        for (i in 0 until numTransforms) {
            val angle = rng.range(0f, 2f * PI.toFloat()) + anim * (i + 1) * 0.3f
            val scale = rng.range(0.3f, 0.7f)
            val ca = cos(angle.toDouble()) * scale
            val sa = sin(angle.toDouble()) * scale
            ta[i] = ca; tb[i] = -sa; tc[i] = sa; td[i] = ca
            te[i] = rng.range(-0.5f, 0.5f).toDouble()
            tf[i] = rng.range(-0.5f, 0.5f).toDouble()
            tColorIdx[i] = i.toFloat() / (numTransforms - 1).coerceAtLeast(1)
        }

        val lutSize = 256
        val lutR = IntArray(lutSize)
        val lutG = IntArray(lutSize)
        val lutB = IntArray(lutSize)
        for (i in 0 until lutSize) {
            val c = palette.lerpColor(i.toFloat() / (lutSize - 1))
            lutR[i] = Color.red(c)
            lutG[i] = Color.green(c)
            lutB[i] = Color.blue(c)
        }

        val varIdx = when (variation) {
            "sinusoidal" -> VAR_SINUSOIDAL; "spherical" -> VAR_SPHERICAL
            "swirl" -> VAR_SWIRL; "horseshoe" -> VAR_HORSESHOE
            "handkerchief" -> VAR_HANDKERCHIEF; "disc" -> VAR_DISC
            else -> VAR_SINUSOIDAL
        }

        val skip = 50
        val invGamma = 1.0 / gamma
        val halfW = w / 2.0
        val halfH = h / 2.0

        // --- Bounding box pre-pass (matching web reference) ---
        val preRng = SeededRNG(seed + 1)
        var preX = preRng.range(-1f, 1f).toDouble()
        var preY = preRng.range(-1f, 1f).toDouble()
        var bMinX = Double.MAX_VALUE; var bMaxX = -Double.MAX_VALUE
        var bMinY = Double.MAX_VALUE; var bMaxY = -Double.MAX_VALUE

        for (i in 0 until 5000) {
            val tIdx = preRng.integer(0, numTransforms - 1)
            val ax = ta[tIdx] * preX + tb[tIdx] * preY + te[tIdx]
            val ay = tc[tIdx] * preX + td[tIdx] * preY + tf[tIdx]

            when (varIdx) {
                VAR_SINUSOIDAL -> { preX = sin(ax); preY = sin(ay) }
                VAR_SPHERICAL -> { val s = 1.0 / (ax * ax + ay * ay + 1e-10); preX = ax * s; preY = ay * s }
                VAR_SWIRL -> { val r2 = ax * ax + ay * ay; val sr = sin(r2); val cr = cos(r2); preX = ax * sr - ay * cr; preY = ax * cr + ay * sr }
                VAR_HORSESHOE -> { val inv = 1.0 / (sqrt(ax * ax + ay * ay) + 1e-10); preX = (ax - ay) * (ax + ay) * inv; preY = 2.0 * ax * ay * inv }
                VAR_HANDKERCHIEF -> { val r = sqrt(ax * ax + ay * ay); val theta = atan2(ay, ax); preX = r * sin(theta + r); preY = r * cos(theta - r) }
                VAR_DISC -> { val r = sqrt(ax * ax + ay * ay); val f = atan2(ay, ax) / PI; preX = f * sin(PI * r); preY = f * cos(PI * r) }
            }

            if (i >= skip && preX.isFinite() && preY.isFinite()) {
                if (preX < bMinX) bMinX = preX; if (preX > bMaxX) bMaxX = preX
                if (preY < bMinY) bMinY = preY; if (preY > bMaxY) bMaxY = preY
            }
        }

        // Fallback if bounds are degenerate
        if (bMinX >= bMaxX || bMinY >= bMaxY) {
            bMinX = -1.5; bMaxX = 1.5; bMinY = -1.5; bMaxY = 1.5
        }

        // Compute view mapping: map flame bounds to canvas with padding
        val pad = 0.08
        val rangeX = (bMaxX - bMinX) * (1 + pad)
        val rangeY = (bMaxY - bMinY) * (1 + pad)
        val centerX = (bMinX + bMaxX) / 2.0
        val centerY = (bMinY + bMaxY) / 2.0
        // Use the larger ratio so the entire flame fits; zoom multiplies density
        val scale = maxOf(rangeX / w, rangeY / h)
        val invScale = zoom / scale

        // --- Main chaos game ---
        val histogram = IntArray(w * h)
        val colorAccR = FloatArray(w * h)
        val colorAccG = FloatArray(w * h)
        val colorAccB = FloatArray(w * h)
        val touched = IntArray(w * h)
        var touchedCount = 0

        val iterRng = SeededRNG(seed + 1)
        var x = iterRng.range(-1f, 1f).toDouble()
        var y = iterRng.range(-1f, 1f).toDouble()
        var curColor = 0.5f

        for (i in 0 until scaledIterations) {
            val tIdx = iterRng.integer(0, numTransforms - 1)
            val ax = ta[tIdx] * x + tb[tIdx] * y + te[tIdx]
            val ay = tc[tIdx] * x + td[tIdx] * y + tf[tIdx]

            when (varIdx) {
                VAR_SINUSOIDAL -> { x = sin(ax); y = sin(ay) }
                VAR_SPHERICAL -> { val s = 1.0 / (ax * ax + ay * ay + 1e-10); x = ax * s; y = ay * s }
                VAR_SWIRL -> { val r2 = ax * ax + ay * ay; val sr = sin(r2); val cr = cos(r2); x = ax * sr - ay * cr; y = ax * cr + ay * sr }
                VAR_HORSESHOE -> { val inv = 1.0 / (sqrt(ax * ax + ay * ay) + 1e-10); x = (ax - ay) * (ax + ay) * inv; y = 2.0 * ax * ay * inv }
                VAR_HANDKERCHIEF -> { val r = sqrt(ax * ax + ay * ay); val theta = atan2(ay, ax); x = r * sin(theta + r); y = r * cos(theta - r) }
                VAR_DISC -> { val r = sqrt(ax * ax + ay * ay); val f = atan2(ay, ax) / PI; x = f * sin(PI * r); y = f * cos(PI * r) }
            }

            curColor = (curColor + tColorIdx[tIdx]) * 0.5f

            if (i < skip) continue

            // Map flame coords to pixel coords: center on canvas, scale to fit
            val sx = ((x - centerX) * invScale + halfW).toInt()
            val sy = ((y - centerY) * invScale + halfH).toInt()

            if (sx in 0 until w && sy in 0 until h) {
                val idx = sy * w + sx
                if (histogram[idx] == 0) touched[touchedCount++] = idx
                histogram[idx]++
                val li = (curColor * (lutSize - 1)).toInt().coerceIn(0, lutSize - 1)
                colorAccR[idx] += lutR[li] / 255f
                colorAccG[idx] += lutG[li] / 255f
                colorAccB[idx] += lutB[li] / 255f
            }
        }

        // --- Tone mapping LUT ---
        var maxDensity = 1
        for (d in histogram) { if (d > maxDensity) maxDensity = d }
        val logMax = ln(maxDensity.toFloat() + 1f)

        val toneLut = FloatArray(maxDensity + 1) { count ->
            if (count == 0) 0f
            else (ln(count.toFloat() + 1f) / logMax).toDouble().pow(invGamma).toFloat()
        }

        // --- Sparse pixel rendering ---
        val pixels = IntArray(w * h)
        for (t in 0 until touchedCount) {
            val i = touched[t]
            val count = histogram[i].toFloat()
            val alpha = toneLut[histogram[i]]
            val r = ((colorAccR[i] / count) * alpha * 255f).toInt().coerceIn(0, 255)
            val g = ((colorAccG[i] / count) * alpha * 255f).toInt().coerceIn(0, 255)
            val b = ((colorAccB[i] / count) * alpha * 255f).toInt().coerceIn(0, 255)
            pixels[i] = Color.rgb(r, g, b)
        }

        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
        canvas.drawBitmap(bitmap, 0f, 0f, null)
    }

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float {
        val iterations = (params["iterations"] as? Number)?.toInt() ?: 300000
        return (iterations / 500000f).coerceIn(0.1f, 1f)
    }
}
