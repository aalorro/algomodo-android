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
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

class LissajousGenerator : Generator {

    override val id = "lissajous"
    override val family = "geometry"
    override val styleName = "Lissajous Curves"
    override val definition =
        "Lissajous figures and harmonograph patterns from parametric sinusoidal equations with optional damping."
    override val algorithmNotes =
        "Samples the parametric equations x = sin(freqX*t + phase), y = sin(freqY*t) at many " +
        "points along t in [0, 2*pi*N]. An optional exponential decay factor e^(-decay*t) " +
        "simulates a damped pendulum (harmonograph). Multiple layers with phase offsets create " +
        "complex interlocking patterns."
    override val supportsVector = true
    override val supportsAnimation = true

    override val parameterSchema: List<Parameter> = listOf(
        Parameter.NumberParam(
            name = "X Frequency",
            key = "ax",
            group = ParamGroup.GEOMETRY,
            help = "Frequency of the X oscillation \u2014 the ratio ax:ay determines the Lissajous figure shape",
            min = 1f, max = 20f, step = 1f, default = 5f
        ),
        Parameter.NumberParam(
            name = "Y Frequency",
            key = "ay",
            group = ParamGroup.GEOMETRY,
            help = "Frequency of the Y oscillation",
            min = 1f, max = 20f, step = 1f, default = 4f
        ),
        Parameter.NumberParam(
            name = "Phase",
            key = "phase",
            group = ParamGroup.GEOMETRY,
            help = "Phase offset between X and Y \u2014 sweeps through the full family of related curves; \u03c0/2 gives the classic ellipse/Lissajous form",
            min = 0f, max = 6.28f, step = 0.1f, default = 1.57f
        ),
        Parameter.NumberParam(
            name = "Decay",
            key = "decay",
            group = ParamGroup.GEOMETRY,
            help = "Harmonograph damping \u2014 exponential amplitude decay over time. 0 = closed Lissajous figure. >0 = inward-spiraling harmonograph.",
            min = 0f, max = 0.5f, step = 0.01f, default = 0f
        ),
        Parameter.NumberParam(
            name = "Layers",
            key = "layers",
            group = ParamGroup.COMPOSITION,
            help = "Overlapping curves; each successive layer is offset by \u03c0/layers in phase",
            min = 1f, max = 6f, step = 1f, default = 1f
        ),
        Parameter.NumberParam(
            name = "Samples",
            key = "samples",
            group = ParamGroup.COMPOSITION,
            help = "Number of curve samples \u2014 increase for high-frequency ratios or slow decay",
            min = 100f, max = 10000f, step = 100f, default = 5000f
        ),
        Parameter.NumberParam(
            name = "Line Thickness",
            key = "thickness",
            group = ParamGroup.TEXTURE,
            help = null,
            min = 0.5f, max = 5f, step = 0.5f, default = 2f
        ),
        Parameter.NumberParam(
            name = "Speed",
            key = "speed",
            group = ParamGroup.FLOW_MOTION,
            help = "Phase sweep speed \u2014 animates the Lissajous shape morphing",
            min = 0.05f, max = 2f, step = 0.05f, default = 0.5f
        )
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "ax" to 5f,
        "ay" to 4f,
        "phase" to 1.57f,
        "decay" to 0f,
        "layers" to 1f,
        "samples" to 5000f,
        "thickness" to 2f,
        "speed" to 0.5f
    )

    private fun generatePoints(
        freqX: Float, freqY: Float, phase: Float, decay: Float,
        cx: Float, cy: Float, amplitude: Float, samples: Int
    ): List<Pair<Float, Float>> {
        val points = mutableListOf<Pair<Float, Float>>()
        val totalT = 2f * PI.toFloat() * 40f

        for (i in 0..samples) {
            val t = totalT * i / samples
            val damp = exp(-decay * t)
            val x = cx + amplitude * sin(freqX * t + phase) * damp
            val y = cy + amplitude * sin(freqY * t) * damp
            points.add(x to y)
        }
        return points
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
        val freqX = (params["ax"] as? Number)?.toFloat() ?: 5f
        val freqY = (params["ay"] as? Number)?.toFloat() ?: 4f
        val basePhase = (params["phase"] as? Number)?.toFloat() ?: 1.57f
        val decay = (params["decay"] as? Number)?.toFloat() ?: 0f
        val layers = (params["layers"] as? Number)?.toInt() ?: 1
        val baseSamples = (params["samples"] as? Number)?.toInt() ?: 5000
        val strokeWidth = (params["thickness"] as? Number)?.toFloat() ?: 2f
        val speed = (params["speed"] as? Number)?.toFloat() ?: 0.5f

        // Animate by sweeping phase, controlled by speed
        val phaseAnim = time * speed * 0.2f

        canvas.drawColor(Color.BLACK)

        val cx = w / 2f
        val cy = h / 2f
        val amplitude = min(w, h) * 0.4f

        // Scale samples by quality
        val samples = when (quality) {
            Quality.DRAFT -> (baseSamples / 2).coerceAtLeast(500)
            Quality.BALANCED -> baseSamples
            Quality.ULTRA -> (baseSamples * 1.5f).toInt()
        }

        val paint = Paint().apply {
            style = Paint.Style.STROKE
            this.strokeWidth = strokeWidth
            isAntiAlias = quality != Quality.DRAFT
            strokeCap = Paint.Cap.ROUND
        }

        val paletteColors = palette.colorInts()

        for (layer in 0 until layers) {
            // Each layer offset by pi/layers in phase
            val layerPhase = basePhase + phaseAnim + layer * PI.toFloat() / layers

            val points = generatePoints(freqX, freqY, layerPhase, decay, cx, cy, amplitude, samples)

            // Draw in colored segments
            val segmentSize = (samples / (paletteColors.size * 8)).coerceAtLeast(10)

            for (i in 0 until points.size - 1 step segmentSize) {
                val end = (i + segmentSize + 1).coerceAtMost(points.size)
                val t = i.toFloat() / points.size

                // Color: offset per layer
                val colorT = ((t + layer.toFloat() / layers) % 1f)
                paint.color = palette.lerpColor(colorT)

                // Fade alpha for decayed tail
                if (decay > 0f) {
                    val alpha = (255 * exp(-decay * (2f * PI.toFloat() * 40f * i / samples))).toInt().coerceIn(30, 255)
                    paint.alpha = alpha
                } else {
                    paint.alpha = if (layers > 1) (200 + 55 / layers).coerceAtMost(255) else 255
                }

                val path = Path()
                path.moveTo(points[i].first, points[i].second)
                for (j in i + 1 until end) {
                    path.lineTo(points[j].first, points[j].second)
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
        val freqX = (params["ax"] as? Number)?.toFloat() ?: 5f
        val freqY = (params["ay"] as? Number)?.toFloat() ?: 4f
        val basePhase = (params["phase"] as? Number)?.toFloat() ?: 1.57f
        val decay = (params["decay"] as? Number)?.toFloat() ?: 0f
        val layers = (params["layers"] as? Number)?.toInt() ?: 1
        val baseSamples = (params["samples"] as? Number)?.toInt() ?: 5000
        val strokeWidth = (params["thickness"] as? Number)?.toFloat() ?: 2f

        val cx = w / 2f
        val cy = h / 2f
        val amplitude = min(w, h) * 0.4f
        val paths = mutableListOf<SvgPath>()

        for (layer in 0 until layers) {
            val layerPhase = basePhase + layer * PI.toFloat() / layers
            val points = generatePoints(freqX, freqY, layerPhase, decay, cx, cy, amplitude, baseSamples)
            val paletteColors = palette.colorInts()
            val segmentSize = (baseSamples / (paletteColors.size * 6)).coerceAtLeast(20)

            for (i in 0 until points.size - 1 step segmentSize) {
                val end = (i + segmentSize + 1).coerceAtMost(points.size)
                val t = ((i.toFloat() / points.size + layer.toFloat() / layers) % 1f)
                val color = palette.lerpColor(t)
                val hexColor = String.format("#%06X", 0xFFFFFF and color)

                val sb = StringBuilder()
                sb.append(SvgBuilder.moveTo(points[i].first, points[i].second))
                for (j in i + 1 until end) {
                    sb.append(" ").append(SvgBuilder.lineTo(points[j].first, points[j].second))
                }

                val alpha = if (decay > 0f) {
                    exp(-decay * (2f * PI.toFloat() * 40f * i / baseSamples)).coerceIn(0.1f, 1f)
                } else 1f

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
