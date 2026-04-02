package com.artmondo.algomodo.generators.geometry

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import com.artmondo.algomodo.data.palettes.Palette
import com.artmondo.algomodo.generators.Generator
import com.artmondo.algomodo.generators.ParamGroup
import com.artmondo.algomodo.generators.Parameter
import com.artmondo.algomodo.generators.Quality
import com.artmondo.algomodo.rendering.SvgBuilder
import com.artmondo.algomodo.rendering.SvgPath
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.sin

class LissajousGenerator : Generator {

    override val id = "lissajous"
    override val family = "geometry"
    override val styleName = "Lissajous Curves"
    override val definition =
        "Lissajous figures and harmonograph patterns from parametric sinusoidal equations with optional damping."
    override val algorithmNotes =
        "x(t) = sin(fx·t + φ)·e^(−δt), y(t) = sin(fy·t)·e^(−δt). With δ=0 this is the classic " +
        "closed Lissajous figure — one full period is 2π/gcd(fx,fy). With δ>0 the amplitude decays " +
        "exponentially, tracing the inward spiral of a harmonograph; the curve is sampled until " +
        "amplitude reaches ~1% of start. Colour sweeps through the palette making the spiral visible."
    override val supportsVector = true
    override val supportsAnimation = true

    override val parameterSchema: List<Parameter> = listOf(
        Parameter.NumberParam(
            name = "X Frequency", key = "ax", group = ParamGroup.GEOMETRY,
            help = "Frequency of the X oscillation \u2014 the ratio ax:ay determines the Lissajous figure shape",
            min = 1f, max = 20f, step = 1f, default = 5f
        ),
        Parameter.NumberParam(
            name = "Y Frequency", key = "ay", group = ParamGroup.GEOMETRY,
            help = "Frequency of the Y oscillation",
            min = 1f, max = 20f, step = 1f, default = 4f
        ),
        Parameter.NumberParam(
            name = "Phase", key = "phase", group = ParamGroup.GEOMETRY,
            help = "Phase offset between X and Y \u2014 sweeps through the full family of related curves; \u03c0/2 gives the classic ellipse/Lissajous form",
            min = 0f, max = 6.28f, step = 0.1f, default = 1.57f
        ),
        Parameter.NumberParam(
            name = "Decay", key = "decay", group = ParamGroup.GEOMETRY,
            help = "Harmonograph damping \u2014 exponential amplitude decay over time. 0 = closed Lissajous figure. >0 = inward-spiraling harmonograph.",
            min = 0f, max = 0.5f, step = 0.01f, default = 0f
        ),
        Parameter.NumberParam(
            name = "Layers", key = "layers", group = ParamGroup.COMPOSITION,
            help = "Overlapping curves; each successive layer is offset by \u03c0/layers in phase",
            min = 1f, max = 6f, step = 1f, default = 1f
        ),
        Parameter.NumberParam(
            name = "Samples", key = "samples", group = ParamGroup.COMPOSITION,
            help = "Number of curve samples \u2014 increase for high-frequency ratios or slow decay",
            min = 100f, max = 10000f, step = 100f, default = 5000f
        ),
        Parameter.NumberParam(
            name = "Line Thickness", key = "thickness", group = ParamGroup.TEXTURE,
            help = null, min = 0.5f, max = 5f, step = 0.5f, default = 2f
        ),
        Parameter.NumberParam(
            name = "Speed", key = "speed", group = ParamGroup.FLOW_MOTION,
            help = "Phase sweep speed \u2014 animates the Lissajous shape morphing",
            min = 0.05f, max = 2f, step = 0.05f, default = 0.5f
        )
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "ax" to 5f, "ay" to 4f, "phase" to 1.57f, "decay" to 0f,
        "layers" to 1f, "samples" to 5000f, "thickness" to 2f, "speed" to 0.5f
    )

    /** GCD for computing the natural period of integer-frequency Lissajous curves. */
    private fun gcd(a: Int, b: Int): Int {
        var x = abs(a); var y = abs(b)
        while (y != 0) { val t = y; y = x % y; x = t }
        return max(1, x)
    }

    /** Compute tMax: one full period for closed curves, or until amplitude < 1% for decay. */
    private fun computeTMax(fx: Int, fy: Int, decay: Float): Float {
        val period = (2f * PI.toFloat()) / gcd(fx, fy)
        return if (decay > 0f) {
            min(period * 8f, ln(100f) / max(decay, 1e-6f))
        } else {
            period
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
        val w = bitmap.width.toFloat()
        val h = bitmap.height.toFloat()
        val fx = max(1, ((params["ax"] as? Number)?.toFloat() ?: 5f).toInt())
        val fy = max(1, ((params["ay"] as? Number)?.toFloat() ?: 4f).toInt())
        val basePhase = (params["phase"] as? Number)?.toFloat() ?: 1.57f
        val decay = max(0f, (params["decay"] as? Number)?.toFloat() ?: 0f)
        val layers = max(1, ((params["layers"] as? Number)?.toFloat() ?: 1f).toInt())
        val baseSamples = max(100, ((params["samples"] as? Number)?.toFloat() ?: 5000f).toInt())
        val strokeWidth = (params["thickness"] as? Number)?.toFloat() ?: 2f
        val speed = (params["speed"] as? Number)?.toFloat() ?: 0.5f

        val animPhase = basePhase + time * speed

        canvas.drawColor(Color.BLACK)

        val cx = w / 2f
        val cy = h / 2f
        val scale = min(w, h) * 0.44f

        val samples = when (quality) {
            Quality.DRAFT -> (baseSamples / 2).coerceAtLeast(500)
            Quality.BALANCED -> baseSamples
            Quality.ULTRA -> (baseSamples * 1.5f).toInt()
        }

        val tMax = computeTMax(fx, fy, decay)

        val paint = Paint().apply {
            style = Paint.Style.STROKE
            this.strokeWidth = strokeWidth
            isAntiAlias = quality != Quality.DRAFT
            strokeCap = Paint.Cap.ROUND
        }

        // 80 color segments for smooth palette interpolation (matching web version)
        val nSeg = 80

        for (layer in 0 until layers) {
            val ph = animPhase + (layer.toFloat() / layers) * PI.toFloat()

            for (seg in 0 until nSeg) {
                // Amplitude at start of segment for decay dimming
                val t0 = (seg.toFloat() / nSeg) * tMax
                val amp = exp(-decay * t0)

                // Color position along curve — slight offset per layer
                val ct = ((seg.toFloat() / nSeg) + (layer.toFloat() / max(layers, 1)) * 0.35f) % 1f
                paint.color = palette.lerpColor(ct)
                paint.alpha = if (decay > 0f) {
                    (255 * max(0.08f, amp)).toInt().coerceIn(20, 255)
                } else {
                    255
                }

                val iStart = floor((seg.toFloat() / nSeg) * samples).toInt()
                val iEnd = ceil(((seg + 1).toFloat() / nSeg) * samples).toInt()

                val path = Path()
                for (i in iStart..iEnd) {
                    val t = (i.toFloat() / samples) * tMax
                    val env = exp(-decay * t)
                    val x = cx + sin(fx * t + ph) * scale * env
                    val y = cy + sin(fy * t) * scale * env
                    if (i == iStart) path.moveTo(x, y) else path.lineTo(x, y)
                }
                canvas.drawPath(path, paint)
            }
        }
    }

    override fun renderVector(
        params: Map<String, Any>,
        seed: Int,
        palette: Palette
    ): List<SvgPath> {
        val w = 1080f
        val h = 1080f
        val fx = max(1, ((params["ax"] as? Number)?.toFloat() ?: 5f).toInt())
        val fy = max(1, ((params["ay"] as? Number)?.toFloat() ?: 4f).toInt())
        val basePhase = (params["phase"] as? Number)?.toFloat() ?: 1.57f
        val decay = max(0f, (params["decay"] as? Number)?.toFloat() ?: 0f)
        val layers = max(1, ((params["layers"] as? Number)?.toFloat() ?: 1f).toInt())
        val baseSamples = max(100, ((params["samples"] as? Number)?.toFloat() ?: 5000f).toInt())
        val strokeWidth = (params["thickness"] as? Number)?.toFloat() ?: 2f

        val cx = w / 2f
        val cy = h / 2f
        val scale = min(w, h) * 0.44f
        val tMax = computeTMax(fx, fy, decay)
        val paths = mutableListOf<SvgPath>()

        val nSeg = 80

        for (layer in 0 until layers) {
            val ph = basePhase + (layer.toFloat() / layers) * PI.toFloat()

            for (seg in 0 until nSeg) {
                val ct = ((seg.toFloat() / nSeg) + (layer.toFloat() / max(layers, 1)) * 0.35f) % 1f
                val color = palette.lerpColor(ct)
                val hexColor = String.format("#%06X", 0xFFFFFF and color)

                val t0 = (seg.toFloat() / nSeg) * tMax
                val alpha = if (decay > 0f) {
                    exp(-decay * t0).coerceIn(0.08f, 1f)
                } else 1f

                val iStart = floor((seg.toFloat() / nSeg) * baseSamples).toInt()
                val iEnd = ceil(((seg + 1).toFloat() / nSeg) * baseSamples).toInt()

                val sb = StringBuilder()
                for (i in iStart..iEnd) {
                    val t = (i.toFloat() / baseSamples) * tMax
                    val env = exp(-decay * t)
                    val x = cx + sin(fx * t + ph) * scale * env
                    val y = cy + sin(fy * t) * scale * env
                    if (i == iStart) {
                        sb.append(SvgBuilder.moveTo(x, y))
                    } else {
                        sb.append(" ").append(SvgBuilder.lineTo(x, y))
                    }
                }

                paths.add(SvgPath(d = sb.toString(), stroke = hexColor, strokeWidth = strokeWidth, opacity = alpha))
            }
        }

        return paths
    }

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float {
        val layers = (params["layers"] as? Number)?.toInt() ?: 1
        val samples = (params["samples"] as? Number)?.toInt() ?: 5000
        return (layers * samples / 30000f).coerceIn(0.2f, 1f)
    }
}
