package com.artmondo.algomodo.generators.procedural

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import com.artmondo.algomodo.audio.AudioAnalysis
import com.artmondo.algomodo.core.rng.SeededRNG
import com.artmondo.algomodo.data.palettes.Palette
import com.artmondo.algomodo.generators.Generator
import com.artmondo.algomodo.generators.ParamGroup
import com.artmondo.algomodo.generators.Parameter
import com.artmondo.algomodo.generators.Quality
import kotlin.math.*

class AudioReactiveGenerator : Generator {

    override val id = "procedural-audio-reactive"
    override val family = "procedural"
    override val styleName = "Audio-Reactive"
    override val definition =
        "Simulated audio-reactive visualization with synthesized spectrum, beat detection, and multiple display styles."
    override val algorithmNotes =
        "Generates a deterministic fake audio spectrum from layered sine waves seeded by the RNG. " +
        "Beat detection is simulated via a sharp periodic pulse. Four visualization styles: " +
        "vertical frequency bars, radial spoke burst, concentric amplitude rings, and continuous waveform."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema = listOf(
        Parameter.NumberParam("Bands", "bandCount", ParamGroup.COMPOSITION,
            "Frequency band count", 8f, 128f, 8f, 32f),
        Parameter.SelectParam("Style", "style", ParamGroup.COMPOSITION,
            "bars: vertical EQ | radial: spoke burst | rings: concentric | waveform: continuous wave",
            listOf("bars", "radial", "rings", "waveform"), "bars"),
        Parameter.NumberParam("Reactivity", "reactivity", ParamGroup.FLOW_MOTION,
            "Amplitude scaling factor", 0.1f, 2f, 0.1f, 1.0f),
        Parameter.NumberParam("Beat Rate", "beatRate", ParamGroup.FLOW_MOTION,
            "Simulated beat frequency (Hz)", 0.5f, 4f, 0.25f, 1.5f),
        Parameter.NumberParam("Smoothing", "smoothing", ParamGroup.TEXTURE,
            "Temporal smoothing of spectrum", 0f, 0.95f, 0.05f, 0.5f),
        Parameter.BooleanParam("Symmetry", "symmetry", ParamGroup.GEOMETRY,
            "Mirror the visualization", false),
        Parameter.NumberParam("Speed", "speed", ParamGroup.FLOW_MOTION,
            "Overall animation speed", 0.25f, 3f, 0.25f, 1f)
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "bandCount" to 32f, "style" to "bars", "reactivity" to 1.0f,
        "beatRate" to 1.5f, "smoothing" to 0.5f, "symmetry" to false, "speed" to 1f
    )

    private fun synthesizeSpectrum(
        bandCount: Int, seed: Int, time: Float,
        reactivity: Float, smoothing: Float, beatRate: Float
    ): FloatArray {
        val rng = SeededRNG(seed)
        val amplitudes = FloatArray(bandCount)
        val HARMONICS = 4
        val TAU = PI.toFloat() * 2f

        val phases = FloatArray(bandCount * HARMONICS)
        val amps = FloatArray(bandCount * HARMONICS)
        val freqs = FloatArray(bandCount * HARMONICS)
        for (i in 0 until bandCount * HARMONICS) {
            phases[i] = rng.random() * TAU
            amps[i] = rng.range(0.15f, 1.0f)
            freqs[i] = rng.range(0.3f, 2.5f)
        }

        for (i in 0 until bandCount) {
            var v = 0f
            val freqBias = (i + 1f) / bandCount
            for (k in 0 until HARMONICS) {
                val idx = i * HARMONICS + k
                v += amps[idx] * sin(time * freqs[idx] + phases[idx] + freqBias * 3f)
            }
            v = abs(v) / HARMONICS

            if (smoothing > 0f) {
                val dt = 0.016f
                var smoothed = v
                for (s in 1..3) {
                    val ts = time - s * dt
                    var vs = 0f
                    for (k in 0 until HARMONICS) {
                        val idx = i * HARMONICS + k
                        vs += amps[idx] * sin(ts * freqs[idx] + phases[idx] + freqBias * 3f)
                    }
                    vs = abs(vs) / HARMONICS
                    smoothed += vs * smoothing
                }
                v = smoothed / (1f + 3f * smoothing)
            }

            amplitudes[i] = v * reactivity
        }

        val beat = max(0f, sin(time * beatRate * PI.toFloat())).pow(8f)
        for (i in 0 until bandCount) {
            amplitudes[i] *= (1f + beat * 1.8f)
            amplitudes[i] = min(1.5f, amplitudes[i])
        }

        return amplitudes
    }

    override fun renderCanvas(
        canvas: Canvas, bitmap: Bitmap, params: Map<String, Any>,
        seed: Int, palette: Palette, quality: Quality, time: Float
    ) {
        val w = bitmap.width.toFloat(); val h = bitmap.height.toFloat()
        val cx = w / 2f; val cy = h / 2f
        val minDim = min(w, h)
        val TAU = PI.toFloat() * 2f

        val bandCount = (params["bandCount"] as? Number)?.toInt()?.coerceAtLeast(4) ?: 32
        val style = (params["style"] as? String) ?: "bars"
        val reactivity = (params["reactivity"] as? Number)?.toFloat() ?: 1.0f
        val beatRate = (params["beatRate"] as? Number)?.toFloat() ?: 1.5f
        val smoothing = (params["smoothing"] as? Number)?.toFloat() ?: 0.5f
        val doSymmetry = (params["symmetry"] as? Boolean) ?: false
        val spd = (params["speed"] as? Number)?.toFloat() ?: 1f
        val t = time * spd

        val colors = palette.colorInts()
        val nC = colors.size

        // Audio analysis: use real audio data when available, otherwise synthesize
        val audioAnalysis = params["_audioAnalysis"] as? AudioAnalysis
        val audioBass = audioAnalysis?.getBass(time) ?: 0f

        val beat: Float
        val spectrum: FloatArray

        if (audioAnalysis != null) {
            beat = min(1f, audioBass * 3f).pow(4f)
            val realSpectrum = audioAnalysis.getSpectrum(time)
            spectrum = FloatArray(bandCount) { i ->
                val srcIdx = (i.toFloat() / bandCount * realSpectrum.size).toInt()
                    .coerceIn(0, realSpectrum.size - 1)
                min(1.5f, (realSpectrum.getOrElse(srcIdx) { 0f }) * reactivity * (1f + beat * 1.5f))
            }
        } else {
            beat = max(0f, sin(t * beatRate * PI.toFloat())).pow(8f)
            spectrum = synthesizeSpectrum(bandCount, seed, t, reactivity, smoothing, beatRate)
        }

        // Background
        val bgBright = (8 + beat * 12f).toInt()
        canvas.drawColor(Color.rgb(bgBright, bgBright, bgBright + 2))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        when (style) {
            "bars" -> {
                val barW = w / (if (doSymmetry) bandCount * 2f else bandCount.toFloat())
                val gap = max(1f, barW * 0.1f)

                for (i in 0 until bandCount) {
                    val amp = spectrum[i]
                    val barH = amp * h * 0.75f
                    val ci = (i.toFloat() / bandCount * (nC - 1)).toInt()
                    val c = colors[ci]

                    paint.style = Paint.Style.FILL
                    paint.color = Color.argb(230, Color.red(c), Color.green(c), Color.blue(c))
                    val x = i * barW
                    canvas.drawRect(x + gap / 2f, h - barH, x + barW - gap / 2f, h, paint)

                    // Glow cap
                    paint.color = Color.argb(180,
                        min(255, Color.red(c) + 80), min(255, Color.green(c) + 80), min(255, Color.blue(c) + 80))
                    canvas.drawRect(x + gap / 2f, h - barH, x + barW - gap / 2f,
                        h - barH + max(2f, minDim * 0.005f), paint)

                    if (doSymmetry) {
                        paint.color = Color.argb(128, Color.red(c), Color.green(c), Color.blue(c))
                        canvas.drawRect(x + gap / 2f, 0f, x + barW - gap / 2f, barH * 0.6f, paint)
                    }
                }
            }
            "radial" -> {
                val maxR = minDim * 0.42f
                paint.style = Paint.Style.STROKE
                paint.strokeCap = Paint.Cap.ROUND

                for (i in 0 until bandCount) {
                    val amp = spectrum[i]
                    val ci = (i.toFloat() / bandCount * (nC - 1)).toInt()
                    val c = colors[ci]
                    val len = amp * maxR
                    val spokeCount = if (doSymmetry) bandCount * 2 else bandCount

                    fun drawSpoke(angle: Float) {
                        val innerR = minDim * 0.05f
                        val x1 = cx + cos(angle) * innerR; val y1 = cy + sin(angle) * innerR
                        val x2 = cx + cos(angle) * (innerR + len)
                        val y2 = cy + sin(angle) * (innerR + len)

                        paint.color = Color.argb(217, Color.red(c), Color.green(c), Color.blue(c))
                        paint.strokeWidth = max(2f, (TAU * innerR) / spokeCount * 0.6f)
                        canvas.drawLine(x1, y1, x2, y2, paint)
                    }

                    val angle = (i.toFloat() / bandCount) * TAU - PI.toFloat() / 2f
                    drawSpoke(angle)
                    if (doSymmetry) drawSpoke(angle + PI.toFloat())
                }
            }
            "rings" -> {
                val maxR = minDim * 0.45f
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = max(2f, minDim * 0.004f)

                for (i in 0 until bandCount) {
                    val amp = spectrum[i]
                    val baseR = ((i + 1f) / (bandCount + 1f)) * maxR
                    val ci = (i.toFloat() / bandCount * (nC - 1)).toInt()
                    val c = colors[ci]

                    paint.color = Color.argb(((0.4f + amp * 0.5f) * 255f).toInt().coerceIn(0, 255),
                        Color.red(c), Color.green(c), Color.blue(c))

                    val path = Path()
                    val segments = 120
                    for (s in 0..segments) {
                        val a = (s.toFloat() / segments) * TAU
                        val wobble = amp * minDim * 0.03f * sin(a * (i % 5 + 2) + t * 2f)
                        val r = baseR + wobble
                        val px2 = cx + cos(a) * r; val py2 = cy + sin(a) * r
                        if (s == 0) path.moveTo(px2, py2) else path.lineTo(px2, py2)
                    }
                    path.close()
                    canvas.drawPath(path, paint)
                }
            }
            else -> { // waveform
                val waveCount = if (doSymmetry) 2 else 1
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = max(2f, minDim * 0.004f)
                paint.strokeCap = Paint.Cap.ROUND

                for (wv in 0 until waveCount) {
                    val yOff = if (wv == 0) 1f else -1f
                    val path = Path()
                    var first = true

                    var px2 = 0f
                    while (px2 < w) {
                        val xNorm = px2 / w
                        var y = 0f
                        val maxBands = min(bandCount, 32)
                        for (i in 0 until maxBands) {
                            val freq = (i + 1f) * 0.5f
                            y += spectrum[i] * sin(xNorm * freq * TAU + t * (i * 0.2f + 1f))
                        }
                        y = y / maxBands * h * 0.35f
                        val py2 = cy + y * yOff

                        if (first) { path.moveTo(px2, py2); first = false }
                        else path.lineTo(px2, py2)
                        px2 += 2f
                    }

                    val gradColors = IntArray(nC) { Color.argb(217, Color.red(colors[it]), Color.green(colors[it]), Color.blue(colors[it])) }
                    val positions = FloatArray(nC) { it.toFloat() / (nC - 1) }
                    paint.shader = LinearGradient(0f, 0f, w, 0f, gradColors, positions, Shader.TileMode.CLAMP)
                    canvas.drawPath(path, paint)
                    paint.shader = null
                }
            }
        }
    }

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float {
        val bands = (params["bandCount"] as? Number)?.toInt() ?: 32
        return (bands / 128f).coerceIn(0.2f, 0.8f)
    }
}
