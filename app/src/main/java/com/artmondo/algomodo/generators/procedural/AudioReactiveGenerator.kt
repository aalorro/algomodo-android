package com.artmondo.algomodo.generators.procedural

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
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
        "Beat detection is simulated via a sharp periodic pulse. Eight visualization styles: " +
        "vertical bars, radial spokes, concentric rings, waveform, circular EQ, terrain, dot scatter, and spectrum heatmap."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema = listOf(
        Parameter.NumberParam("Bands", "bandCount", ParamGroup.COMPOSITION,
            "Frequency band count", 8f, 128f, 8f, 32f),
        Parameter.SelectParam("Style", "style", ParamGroup.COMPOSITION,
            "bars | radial | rings | waveform | circular | terrain | dots | spectrum",
            listOf("bars", "radial", "rings", "waveform", "circular", "terrain", "dots", "spectrum"), "bars"),
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

        val colors = palette.colorInts().toIntArray()
        val nC = colors.size

        val audioAnalysis = params["_audioAnalysis"] as? AudioAnalysis
        val audioBass = (audioAnalysis?.getBass(time) ?: 0f) * reactivity

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
            "bars" -> drawBars(canvas, paint, spectrum, colors, nC, bandCount, w, h, minDim, doSymmetry, beat)
            "radial" -> drawRadial(canvas, paint, spectrum, colors, nC, bandCount, cx, cy, minDim, TAU, doSymmetry)
            "rings" -> drawRings(canvas, paint, spectrum, colors, nC, bandCount, cx, cy, minDim, TAU, t)
            "waveform" -> drawWaveform(canvas, paint, spectrum, colors, nC, bandCount, w, h, cy, TAU, t, doSymmetry)
            "circular" -> drawCircular(canvas, paint, spectrum, colors, nC, bandCount, cx, cy, minDim, TAU, doSymmetry, beat)
            "terrain" -> drawTerrain(canvas, paint, spectrum, colors, nC, bandCount, w, h, TAU, t)
            "dots" -> drawDots(canvas, paint, spectrum, colors, nC, bandCount, w, h, cx, cy, minDim, TAU, t, seed, doSymmetry)
            "spectrum" -> drawSpectrum(canvas, paint, spectrum, colors, nC, bandCount, w, h, beat, t)
        }
    }

    // ── Style: Bars ──
    private fun drawBars(
        canvas: Canvas, paint: Paint, spectrum: FloatArray, colors: IntArray, nC: Int,
        bandCount: Int, w: Float, h: Float, minDim: Float, doSymmetry: Boolean, beat: Float
    ) {
        val barW = w / (if (doSymmetry) bandCount * 2f else bandCount.toFloat())
        val gap = max(1f, barW * 0.1f)

        for (i in 0 until bandCount) {
            val amp = spectrum[i]
            val barH = amp * h * 0.75f
            val ci = (i.toFloat() / bandCount * (nC - 1)).toInt()
            val c = colors[ci]

            // Gradient fill
            val x = i * barW
            val topY = h - barH
            paint.style = Paint.Style.FILL
            paint.shader = LinearGradient(
                x, h, x, topY,
                Color.argb(200, Color.red(c), Color.green(c), Color.blue(c)),
                Color.argb(255, min(255, Color.red(c) + 50), min(255, Color.green(c) + 50), min(255, Color.blue(c) + 50)),
                Shader.TileMode.CLAMP
            )
            canvas.drawRect(x + gap / 2f, topY, x + barW - gap / 2f, h, paint)
            paint.shader = null

            // Glow cap
            paint.color = Color.argb(200,
                min(255, Color.red(c) + 80), min(255, Color.green(c) + 80), min(255, Color.blue(c) + 80))
            canvas.drawRect(x + gap / 2f, topY, x + barW - gap / 2f,
                topY + max(2f, minDim * 0.006f), paint)

            // Beat flash on peaks
            if (beat > 0.3f && amp > 0.5f) {
                paint.color = Color.argb((beat * 60f).toInt().coerceIn(0, 255), 255, 255, 255)
                canvas.drawRect(x + gap / 2f, topY, x + barW - gap / 2f, topY + barH * 0.05f, paint)
            }

            // Reflection
            if (doSymmetry) {
                paint.shader = LinearGradient(
                    x, 0f, x, barH * 0.5f,
                    Color.argb(100, Color.red(c), Color.green(c), Color.blue(c)),
                    Color.argb(0, Color.red(c), Color.green(c), Color.blue(c)),
                    Shader.TileMode.CLAMP
                )
                canvas.drawRect(x + gap / 2f, 0f, x + barW - gap / 2f, barH * 0.5f, paint)
                paint.shader = null
            }
        }
    }

    // ── Style: Radial ──
    private fun drawRadial(
        canvas: Canvas, paint: Paint, spectrum: FloatArray, colors: IntArray, nC: Int,
        bandCount: Int, cx: Float, cy: Float, minDim: Float, TAU: Float, doSymmetry: Boolean
    ) {
        val maxR = minDim * 0.42f
        val innerR = minDim * 0.05f
        val spokeCount = if (doSymmetry) bandCount * 2 else bandCount
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND

        // Glow circles behind
        paint.style = Paint.Style.FILL
        for (i in 0 until bandCount) {
            val amp = spectrum[i]
            if (amp < 0.15f) continue
            val ci = (i.toFloat() / bandCount * (nC - 1)).toInt()
            val c = colors[ci]
            val angle = (i.toFloat() / bandCount) * TAU - PI.toFloat() / 2f
            val len = amp * maxR
            val gx = cx + cos(angle) * (innerR + len * 0.5f)
            val gy = cy + sin(angle) * (innerR + len * 0.5f)
            val glowR = len * 0.15f
            paint.shader = RadialGradient(gx, gy, max(1f, glowR),
                Color.argb(60, Color.red(c), Color.green(c), Color.blue(c)),
                Color.argb(0, Color.red(c), Color.green(c), Color.blue(c)),
                Shader.TileMode.CLAMP)
            canvas.drawCircle(gx, gy, glowR, paint)
        }
        paint.shader = null

        // Spokes
        paint.style = Paint.Style.STROKE
        for (i in 0 until bandCount) {
            val amp = spectrum[i]
            val ci = (i.toFloat() / bandCount * (nC - 1)).toInt()
            val c = colors[ci]
            val len = amp * maxR
            paint.strokeWidth = max(2f, (TAU * innerR) / spokeCount * 0.6f)

            fun drawSpoke(angle: Float) {
                val x1 = cx + cos(angle) * innerR; val y1 = cy + sin(angle) * innerR
                val x2 = cx + cos(angle) * (innerR + len)
                val y2 = cy + sin(angle) * (innerR + len)
                paint.color = Color.argb(217, Color.red(c), Color.green(c), Color.blue(c))
                canvas.drawLine(x1, y1, x2, y2, paint)
            }

            val angle = (i.toFloat() / bandCount) * TAU - PI.toFloat() / 2f
            drawSpoke(angle)
            if (doSymmetry) drawSpoke(angle + PI.toFloat())
        }
    }

    // ── Style: Rings ──
    private fun drawRings(
        canvas: Canvas, paint: Paint, spectrum: FloatArray, colors: IntArray, nC: Int,
        bandCount: Int, cx: Float, cy: Float, minDim: Float, TAU: Float, t: Float
    ) {
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

    // ── Style: Waveform ──
    private fun drawWaveform(
        canvas: Canvas, paint: Paint, spectrum: FloatArray, colors: IntArray, nC: Int,
        bandCount: Int, w: Float, h: Float, cy: Float, TAU: Float, t: Float, doSymmetry: Boolean
    ) {
        val waveCount = if (doSymmetry) 2 else 1
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = max(2f, min(w, h) * 0.004f)
        paint.strokeCap = Paint.Cap.ROUND

        for (wv in 0 until waveCount) {
            val yOff = if (wv == 0) 1f else -1f
            val path = Path()
            var first = true
            var px2 = 0f
            val maxBands = min(bandCount, 32)
            while (px2 < w) {
                val xNorm = px2 / w
                var y = 0f
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

    // ── Style: Circular EQ ──
    private fun drawCircular(
        canvas: Canvas, paint: Paint, spectrum: FloatArray, colors: IntArray, nC: Int,
        bandCount: Int, cx: Float, cy: Float, minDim: Float, TAU: Float, doSymmetry: Boolean, beat: Float
    ) {
        val innerR = minDim * 0.12f
        val maxBarH = minDim * 0.32f
        val totalBands = if (doSymmetry) bandCount else bandCount
        val angleStep = TAU / totalBands

        // Inner circle glow
        paint.style = Paint.Style.FILL
        val glowR = innerR * (1f + beat * 0.3f)
        paint.shader = RadialGradient(cx, cy, max(1f, glowR),
            Color.argb((40 + beat * 60f).toInt().coerceIn(0, 100), 255, 255, 255),
            Color.argb(0, 255, 255, 255), Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, glowR, paint)
        paint.shader = null

        // Inner circle outline
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = max(1f, minDim * 0.002f)
        paint.color = Color.argb(100, 255, 255, 255)
        canvas.drawCircle(cx, cy, innerR, paint)

        // Bars arranged in circle
        paint.style = Paint.Style.FILL
        val barAngleWidth = angleStep * 0.7f

        for (i in 0 until totalBands) {
            val srcIdx = if (doSymmetry) {
                if (i < bandCount / 2) bandCount / 2 - 1 - i else i - bandCount / 2
            } else i
            val amp = spectrum[srcIdx.coerceIn(0, bandCount - 1)]
            val barH = amp * maxBarH
            val ci = (srcIdx.toFloat() / bandCount * (nC - 1)).toInt().coerceIn(0, nC - 1)
            val c = colors[ci]

            val angle = i * angleStep - PI.toFloat() / 2f
            val path = Path()
            val cosA1 = cos(angle - barAngleWidth / 2f)
            val sinA1 = sin(angle - barAngleWidth / 2f)
            val cosA2 = cos(angle + barAngleWidth / 2f)
            val sinA2 = sin(angle + barAngleWidth / 2f)

            path.moveTo(cx + cosA1 * innerR, cy + sinA1 * innerR)
            path.lineTo(cx + cosA1 * (innerR + barH), cy + sinA1 * (innerR + barH))
            path.lineTo(cx + cosA2 * (innerR + barH), cy + sinA2 * (innerR + barH))
            path.lineTo(cx + cosA2 * innerR, cy + sinA2 * innerR)
            path.close()

            paint.color = Color.argb(230, Color.red(c), Color.green(c), Color.blue(c))
            canvas.drawPath(path, paint)

            // Bright cap
            if (barH > 2f) {
                paint.color = Color.argb(180,
                    min(255, Color.red(c) + 80), min(255, Color.green(c) + 80), min(255, Color.blue(c) + 80))
                val capStart = innerR + barH - max(2f, minDim * 0.004f)
                val capPath = Path()
                capPath.moveTo(cx + cosA1 * capStart, cy + sinA1 * capStart)
                capPath.lineTo(cx + cosA1 * (innerR + barH), cy + sinA1 * (innerR + barH))
                capPath.lineTo(cx + cosA2 * (innerR + barH), cy + sinA2 * (innerR + barH))
                capPath.lineTo(cx + cosA2 * capStart, cy + sinA2 * capStart)
                capPath.close()
                canvas.drawPath(capPath, paint)
            }
        }
    }

    // ── Style: Terrain ──
    private fun drawTerrain(
        canvas: Canvas, paint: Paint, spectrum: FloatArray, colors: IntArray, nC: Int,
        bandCount: Int, w: Float, h: Float, TAU: Float, t: Float
    ) {
        val layers = min(bandCount, 24)
        val layerH = h / (layers + 2f)

        for (layer in layers - 1 downTo 0) {
            val ci = (layer.toFloat() / layers * (nC - 1)).toInt().coerceIn(0, nC - 1)
            val c = colors[ci]
            val baseY = h - (layer + 1) * layerH
            val amp = spectrum[layer.coerceIn(0, bandCount - 1)]

            // Darker version for fill
            val darken = 0.3f + layer.toFloat() / layers * 0.7f
            val fillR = (Color.red(c) * darken).toInt().coerceIn(0, 255)
            val fillG = (Color.green(c) * darken).toInt().coerceIn(0, 255)
            val fillB = (Color.blue(c) * darken).toInt().coerceIn(0, 255)

            val path = Path()
            path.moveTo(0f, h)
            path.lineTo(0f, baseY)

            var px = 0f
            while (px <= w) {
                val xNorm = px / w
                var y = 0f
                // Sum a few frequencies for this layer's mountain shape
                for (f in 0 until min(8, bandCount)) {
                    val freq = (f + 1f) * (0.3f + layer * 0.15f)
                    val layerSpectrum = spectrum[(layer + f).coerceIn(0, bandCount - 1)]
                    y += layerSpectrum * sin(xNorm * freq * TAU + t * (f * 0.15f + 0.5f) + layer * 1.3f)
                }
                y = y / 8f * layerH * 2.5f * (0.5f + amp)
                path.lineTo(px, baseY - abs(y))
                px += 3f
            }

            path.lineTo(w, baseY)
            path.lineTo(w, h)
            path.close()

            // Filled layer
            paint.style = Paint.Style.FILL
            paint.color = Color.argb(220, fillR, fillG, fillB)
            canvas.drawPath(path, paint)

            // Bright edge on top
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = max(1f, min(w, h) * 0.002f)
            paint.color = Color.argb(150,
                min(255, Color.red(c) + 40), min(255, Color.green(c) + 40), min(255, Color.blue(c) + 40))
            // Redraw just the mountain part (not the bottom)
            val edgePath = Path()
            edgePath.moveTo(0f, baseY)
            px = 0f
            while (px <= w) {
                val xNorm = px / w
                var y = 0f
                for (f in 0 until min(8, bandCount)) {
                    val freq = (f + 1f) * (0.3f + layer * 0.15f)
                    val layerSpectrum = spectrum[(layer + f).coerceIn(0, bandCount - 1)]
                    y += layerSpectrum * sin(xNorm * freq * TAU + t * (f * 0.15f + 0.5f) + layer * 1.3f)
                }
                y = y / 8f * layerH * 2.5f * (0.5f + amp)
                edgePath.lineTo(px, baseY - abs(y))
                px += 3f
            }
            canvas.drawPath(edgePath, paint)
        }
    }

    // ── Style: Dots ──
    private fun drawDots(
        canvas: Canvas, paint: Paint, spectrum: FloatArray, colors: IntArray, nC: Int,
        bandCount: Int, w: Float, h: Float, cx: Float, cy: Float, minDim: Float,
        TAU: Float, t: Float, seed: Int, doSymmetry: Boolean
    ) {
        val rng = SeededRNG(seed xor 0x7A3B)
        val dotsPerBand = 6
        paint.style = Paint.Style.FILL

        for (i in 0 until bandCount) {
            val amp = spectrum[i]
            val ci = (i.toFloat() / bandCount * (nC - 1)).toInt()
            val c = colors[ci]
            val bandAngle = (i.toFloat() / bandCount) * TAU

            for (d in 0 until dotsPerBand) {
                val baseR = rng.range(0.05f, 0.4f) * minDim
                val angleOff = rng.range(-0.3f, 0.3f)
                val sizeBase = rng.range(0.005f, 0.02f) * minDim

                val r = baseR * (0.4f + amp * 0.8f)
                val angle = bandAngle + angleOff + t * rng.range(-0.3f, 0.3f)
                val dotX = cx + cos(angle) * r
                val dotY = cy + sin(angle) * r
                val dotR = sizeBase * (0.3f + amp * 1.5f)

                val alpha = (amp * 220f + 30f).toInt().coerceIn(0, 255)
                paint.color = Color.argb(alpha, Color.red(c), Color.green(c), Color.blue(c))
                canvas.drawCircle(dotX, dotY, dotR, paint)

                // Outer glow
                if (amp > 0.3f) {
                    paint.color = Color.argb((amp * 40f).toInt().coerceIn(0, 80),
                        Color.red(c), Color.green(c), Color.blue(c))
                    canvas.drawCircle(dotX, dotY, dotR * 2.5f, paint)
                }

                if (doSymmetry) {
                    paint.color = Color.argb(alpha / 2, Color.red(c), Color.green(c), Color.blue(c))
                    canvas.drawCircle(cx - (dotX - cx), cy - (dotY - cy), dotR * 0.8f, paint)
                }
            }
        }
    }

    // ── Style: Spectrum Heatmap ──
    private fun drawSpectrum(
        canvas: Canvas, paint: Paint, spectrum: FloatArray, colors: IntArray, nC: Int,
        bandCount: Int, w: Float, h: Float, beat: Float, t: Float
    ) {
        paint.style = Paint.Style.FILL
        val cellW = w / bandCount
        val rows = 12
        val cellH = h / rows

        for (i in 0 until bandCount) {
            val amp = spectrum[i]
            val ci = (i.toFloat() / bandCount * (nC - 1)).toInt().coerceIn(0, nC - 1)
            val c = colors[ci]
            val x = i * cellW

            for (row in 0 until rows) {
                // Each row shows a different "time slice" of the spectrum
                val rowPhase = row.toFloat() / rows
                val rowAmp = amp * (1f - rowPhase * 0.7f) *
                    (0.5f + 0.5f * sin(t * 2f + i * 0.2f + row * 0.5f))

                val intensity = rowAmp.coerceIn(0f, 1f)
                val alpha = (intensity * 255f).toInt().coerceIn(0, 255)
                val bright = 0.3f + intensity * 0.7f

                paint.color = Color.argb(
                    alpha,
                    (Color.red(c) * bright).toInt().coerceIn(0, 255),
                    (Color.green(c) * bright).toInt().coerceIn(0, 255),
                    (Color.blue(c) * bright).toInt().coerceIn(0, 255)
                )

                val y = h - (row + 1) * cellH
                val rect = RectF(x + 1f, y + 1f, x + cellW - 1f, y + cellH - 1f)
                canvas.drawRoundRect(rect, 2f, 2f, paint)
            }
        }

        // Beat flash overlay
        if (beat > 0.4f) {
            paint.color = Color.argb((beat * 25f).toInt().coerceIn(0, 40), 255, 255, 255)
            canvas.drawRect(0f, 0f, w, h, paint)
        }
    }

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float {
        val bands = (params["bandCount"] as? Number)?.toInt() ?: 32
        val style = (params["style"] as? String) ?: "bars"
        val base = bands / 128f
        val styleMult = when (style) {
            "terrain" -> 1.5f
            "dots" -> 1.3f
            "spectrum" -> 1.2f
            else -> 1f
        }
        return (base * styleMult).coerceIn(0.2f, 0.9f)
    }
}
