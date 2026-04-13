package com.artmondo.algomodo.generators.flux

import android.graphics.*
import com.artmondo.algomodo.audio.AudioAnalysis
import com.artmondo.algomodo.core.rng.SeededRNG
import com.artmondo.algomodo.data.palettes.Palette
import com.artmondo.algomodo.generators.Generator
import com.artmondo.algomodo.generators.ParamGroup
import com.artmondo.algomodo.generators.Parameter
import com.artmondo.algomodo.generators.Quality
import kotlin.math.*

/**
 * Multiple sine waves with different frequencies, amplitudes, and phases
 * summed and rendered as filled layers -- like a Fourier decomposition visualiser.
 *
 * N waves with harmonically spaced frequencies and decaying amplitudes.
 * Three fill modes: layered (filled from curve to bottom), outline (stroked
 * polylines), gradient (bands between consecutive waves).  Waves drawn
 * back-to-front (lowest frequency first).  Each wave has a seeded phase
 * offset.  Audio: bass modulates amplitude, mid modulates base frequency.
 */
class FluxWaveformStackerGenerator : Generator {

    override val id = "flux-waveform-stacker"
    override val family = "flux"
    override val styleName = "Waveform Stacker"
    override val definition =
        "Multiple sine waves with different frequencies, amplitudes, and phases " +
        "summed and rendered as filled layers -- like a Fourier decomposition visualiser"
    override val algorithmNotes =
        "N waves with harmonically spaced frequencies and decaying amplitudes. " +
        "Each wave computes y = centerY + amplitude * minDim * 0.3 * sin(freq * x * TAU / w + phase + time * speed * (1 + i * 0.2)). " +
        "Three fill modes: layered (filled from curve to bottom with alpha), outline (stroked polylines), " +
        "and gradient (filled bands between consecutive waves). " +
        "Waves drawn back-to-front: lowest frequency / largest amplitude first. " +
        "Audio: bass modulates amplitude, mid modulates frequency."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema = listOf(
        Parameter.NumberParam(
            name = "Wave Count",
            key = "waveCount",
            group = ParamGroup.COMPOSITION,
            help = "Number of sine waves stacked together",
            min = 3f, max = 12f, step = 1f, default = 6f
        ),
        Parameter.SelectParam(
            name = "Fill Mode",
            key = "fillMode",
            group = ParamGroup.COMPOSITION,
            help = "layered: filled waves from curve to bottom | outline: stroked polylines only | gradient: filled bands between consecutive waves",
            options = listOf("layered", "outline", "gradient"),
            default = "layered"
        ),
        Parameter.NumberParam(
            name = "Base Frequency",
            key = "baseFrequency",
            group = ParamGroup.GEOMETRY,
            help = "Frequency of the lowest harmonic wave",
            min = 0.5f, max = 5f, step = 0.1f, default = 1.5f
        ),
        Parameter.NumberParam(
            name = "Amplitude",
            key = "amplitude",
            group = ParamGroup.GEOMETRY,
            help = "Maximum vertical displacement of the waves",
            min = 0.1f, max = 1f, step = 0.05f, default = 0.5f
        ),
        Parameter.NumberParam(
            name = "Harmonic Spread",
            key = "harmonicSpread",
            group = ParamGroup.GEOMETRY,
            help = "How much frequency increases across the wave stack",
            min = 0.5f, max = 3f, step = 0.1f, default = 1.5f
        ),
        Parameter.NumberParam(
            name = "Speed",
            key = "speed",
            group = ParamGroup.FLOW_MOTION,
            help = "Animation speed of wave oscillation",
            min = 0.1f, max = 3f, step = 0.05f, default = 0.6f
        ),
        Parameter.NumberParam(
            name = "Audio Reactivity",
            key = "reactivity",
            group = ParamGroup.FLOW_MOTION,
            help = "Sensitivity to audio input (0 = none)",
            min = 0f, max = 2f, step = 0.1f, default = 1.0f
        )
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "waveCount" to 6f,
        "fillMode" to "layered",
        "baseFrequency" to 1.5f,
        "amplitude" to 0.5f,
        "harmonicSpread" to 1.5f,
        "speed" to 0.6f,
        "reactivity" to 1.0f
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
        val minDim = min(w, h).toFloat()
        val centerY = h * 0.5f

        // ── Read params ──────────────────────────────────────────────────
        val waveCount = ((params["waveCount"] as? Number)?.toInt() ?: 6).coerceIn(1, 12)
        val fillMode = (params["fillMode"] as? String) ?: "layered"
        val baseFrequency = (params["baseFrequency"] as? Number)?.toFloat() ?: 1.5f
        val amplitudeParam = (params["amplitude"] as? Number)?.toFloat() ?: 0.5f
        val harmonicSpread = (params["harmonicSpread"] as? Number)?.toFloat() ?: 1.5f
        val spd = (params["speed"] as? Number)?.toFloat() ?: 0.6f
        val rx = (params["reactivity"] as? Number)?.toFloat() ?: 1.0f

        // ── Audio reactivity ─────────────────────────────────────────────
        val audioAnalysis = params["_audioAnalysis"] as? AudioAnalysis
        val audioBass = (audioAnalysis?.getBass(time) ?: 0f) * rx
        val audioMid = (audioAnalysis?.getMid(time) ?: 0f) * rx

        // Bass modulates amplitude, mid modulates frequency
        val amplitude = amplitudeParam * (1f + audioBass * 1.5f)
        val effectiveBaseFreq = baseFrequency * (1f + audioMid * 1.0f)

        // ── Seed-based wave phases ───────────────────────────────────────
        val rng = SeededRNG(seed)

        val waveFreqs = FloatArray(waveCount)
        val waveAmps = FloatArray(waveCount)
        val wavePhases = FloatArray(waveCount)

        for (i in 0 until waveCount) {
            waveFreqs[i] = effectiveBaseFreq * (1f + i * harmonicSpread / waveCount)
            waveAmps[i] = amplitude / (1f + i * 0.3f)
            wavePhases[i] = rng.range(0f, TAU)
        }

        // ── Pre-compute palette colors ───────────────────────────────────
        val colorInts = palette.colorInts()
        val numColors = colorInts.size

        // ── Compute per-wave y-values across canvas width ────────────────
        val waveYArrays = Array(waveCount) { i ->
            val yArr = FloatArray(w)
            val freq = waveFreqs[i]
            val amp = waveAmps[i] * minDim * 0.3f
            val phase = wavePhases[i]
            val timeOffset = time * spd * (1f + i * 0.2f)

            for (x in 0 until w) {
                yArr[x] = centerY + amp * sin(freq * x * TAU / w + phase + timeOffset)
            }
            yArr
        }

        // ── Fill background ──────────────────────────────────────────────
        canvas.drawColor(0xFF0A0A0A.toInt())

        // ── Paint objects ────────────────────────────────────────────────
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // ── Helper: get wave color with alpha ────────────────────────────
        fun waveColor(i: Int, alpha: Int): Int {
            val base = colorInts[i % numColors]
            return Color.argb(alpha, Color.red(base), Color.green(base), Color.blue(base))
        }

        // ── Render based on fill mode ────────────────────────────────────
        // Draw order: back wave (index 0 = lowest freq, largest amp) first, front wave last.
        when (fillMode) {
            "layered" -> {
                paint.style = Paint.Style.FILL
                for (i in 0 until waveCount) {
                    val yArr = waveYArrays[i]
                    val path = Path()
                    path.moveTo(0f, yArr[0])

                    for (x in 1 until w) {
                        path.lineTo(x.toFloat(), yArr[x])
                    }

                    // Close path along the bottom of the canvas
                    path.lineTo((w - 1).toFloat(), h.toFloat())
                    path.lineTo(0f, h.toFloat())
                    path.close()

                    paint.color = waveColor(i, 179) // alpha ~0.7 * 255
                    canvas.drawPath(path, paint)
                }
            }

            "outline" -> {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 2f * (minDim / 500f) // scale stroke with canvas size

                for (i in 0 until waveCount) {
                    val yArr = waveYArrays[i]
                    val path = Path()
                    path.moveTo(0f, yArr[0])

                    for (x in 1 until w) {
                        path.lineTo(x.toFloat(), yArr[x])
                    }

                    paint.color = waveColor(i, 255)
                    canvas.drawPath(path, paint)
                }
            }

            "gradient" -> {
                paint.style = Paint.Style.FILL

                // Fill between consecutive waves with palette color gradient
                for (i in 0 until waveCount - 1) {
                    val yArrTop = waveYArrays[i]
                    val yArrBot = waveYArrays[i + 1]

                    val path = Path()
                    // Trace top wave left to right
                    path.moveTo(0f, yArrTop[0])
                    for (x in 1 until w) {
                        path.lineTo(x.toFloat(), yArrTop[x])
                    }
                    // Trace bottom wave right to left
                    for (x in w - 1 downTo 0) {
                        path.lineTo(x.toFloat(), yArrBot[x])
                    }
                    path.close()

                    // Create a vertical gradient between the two palette colors
                    val ci0 = colorInts[i % numColors]
                    val ci1 = colorInts[(i + 1) % numColors]

                    // Approximate the vertical midpoints for gradient direction
                    val midYTop = yArrTop[w / 2]
                    val midYBot = yArrBot[w / 2]

                    val shader = LinearGradient(
                        0f, midYTop, 0f, midYBot,
                        Color.argb(204, Color.red(ci0), Color.green(ci0), Color.blue(ci0)),
                        Color.argb(204, Color.red(ci1), Color.green(ci1), Color.blue(ci1)),
                        Shader.TileMode.CLAMP
                    )
                    paint.shader = shader
                    canvas.drawPath(path, paint)
                    paint.shader = null
                }

                // Fill from last wave to bottom of canvas
                if (waveCount > 0) {
                    val lastIdx = waveCount - 1
                    val yArrLast = waveYArrays[lastIdx]

                    val path = Path()
                    path.moveTo(0f, yArrLast[0])
                    for (x in 1 until w) {
                        path.lineTo(x.toFloat(), yArrLast[x])
                    }
                    path.lineTo((w - 1).toFloat(), h.toFloat())
                    path.lineTo(0f, h.toFloat())
                    path.close()

                    paint.color = waveColor(lastIdx, 153) // alpha ~0.6 * 255
                    canvas.drawPath(path, paint)
                }

                // Fill from top of canvas to first wave
                if (waveCount > 0) {
                    val yArrFirst = waveYArrays[0]

                    val path = Path()
                    path.moveTo(0f, 0f)
                    path.lineTo((w - 1).toFloat(), 0f)
                    // Trace first wave right to left
                    for (x in w - 1 downTo 0) {
                        path.lineTo(x.toFloat(), yArrFirst[x])
                    }
                    path.close()

                    paint.color = waveColor(0, 102) // alpha ~0.4 * 255
                    canvas.drawPath(path, paint)
                }
            }
        }
    }

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float {
        val waves = (params["waveCount"] as? Number)?.toFloat() ?: 6f
        val isGradient = if ((params["fillMode"] as? String) == "gradient") 1.2f else 1f
        val base = (waves * 30f * isGradient + 80f) / 500f // normalize to ~0..1 range
        return base.coerceIn(0.1f, 1f)
    }

    companion object {
        private const val TAU = 2.0f * 3.1415926535897932f
    }
}
