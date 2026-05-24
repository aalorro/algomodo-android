package com.artmondo.algomodo.generators.geometry

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import com.artmondo.algomodo.data.palettes.Palette
import com.artmondo.algomodo.generators.Generator
import com.artmondo.algomodo.generators.ParamGroup
import com.artmondo.algomodo.generators.Parameter
import com.artmondo.algomodo.generators.Quality
import com.artmondo.algomodo.rendering.SvgBuilder
import com.artmondo.algomodo.rendering.SvgPath
import kotlin.math.*

class LissajousGenerator : Generator {

    override val id = "lissajous"
    override val family = "geometry"
    override val styleName = "Lissajous Curves"
    override val definition =
        "Lissajous figures, harmonograph spirals, and pattern variants — two sinusoidal oscillations at different frequencies with optional decay, phase modulation, harmonic overtones, or radial starburst shaping."
    override val algorithmNotes =
        "Classic: x(t)=sin(fx·t+φ)·e^(−δt), y(t)=sin(fy·t)·e^(−δt). Modulated: phase wobbles with sin(modFreq·t) for non-repeating evolution. " +
        "Compound: adds harmonic overtones sin((2fx+1)·t) for richer figures. Starburst: radial cos(k·t) amplitude creates star/petal shapes. " +
        "Five visual styles from clean solid to neon bloom."
    override val supportsVector = true
    override val supportsAnimation = true

    override val parameterSchema: List<Parameter> = listOf(
        Parameter.NumberParam("X Frequency", "ax", ParamGroup.GEOMETRY,
            "Frequency of the X oscillation — the ratio ax:ay determines the Lissajous figure shape",
            1f, 20f, 1f, 5f),
        Parameter.NumberParam("Y Frequency", "ay", ParamGroup.GEOMETRY,
            "Frequency of the Y oscillation",
            1f, 20f, 1f, 4f),
        Parameter.NumberParam("Phase", "phase", ParamGroup.GEOMETRY,
            "Phase offset between X and Y — π/2 gives the classic ellipse/Lissajous form",
            0f, 6.28f, 0.1f, 1.57f),
        Parameter.NumberParam("Decay", "decay", ParamGroup.GEOMETRY,
            "Harmonograph damping — 0 = closed Lissajous, >0 = inward-spiraling harmonograph",
            0f, 0.5f, 0.01f, 0f),
        Parameter.SelectParam("Pattern", "pattern", ParamGroup.GEOMETRY,
            "classic: standard sin/sin with shimmer | modulated: evolving phase wobble | compound: harmonic overtones | starburst: radial star shapes",
            listOf("classic", "modulated", "compound", "starburst"), "classic"),
        Parameter.NumberParam("Layers", "layers", ParamGroup.COMPOSITION,
            "Overlapping curves; each offset by π/layers in phase",
            1f, 6f, 1f, 1f),
        Parameter.NumberParam("Samples", "samples", ParamGroup.COMPOSITION,
            "Number of curve samples",
            100f, 10000f, 100f, 5000f),
        Parameter.SelectParam("Style", "style", ParamGroup.COLOR,
            "flow: glow + gradient | solid: clean stroke | rainbow: HSL cycling | neon: bloom | dotted: dots along curve",
            listOf("flow", "solid", "rainbow", "neon", "dotted"), "flow"),
        Parameter.NumberParam("Line Thickness", "thickness", ParamGroup.TEXTURE,
            null, 0.5f, 5f, 0.5f, 2f),
        Parameter.NumberParam("Speed", "speed", ParamGroup.FLOW_MOTION,
            "Phase sweep speed — animates the shape morphing",
            0.05f, 2f, 0.05f, 0.5f),
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "ax" to 5f, "ay" to 4f, "phase" to 1.57f, "decay" to 0f,
        "pattern" to "classic", "layers" to 1f, "samples" to 5000f,
        "style" to "flow", "thickness" to 2f, "speed" to 0.5f,
    )

    private fun gcd(a: Int, b: Int): Int {
        var x = abs(a); var y = abs(b)
        while (y != 0) { val t = y; y = x % y; x = t }
        return max(1, x)
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
        val pattern = (params["pattern"] as? String) ?: "classic"
        val layers = max(1, ((params["layers"] as? Number)?.toFloat() ?: 1f).toInt())
        val baseSamples = max(100, ((params["samples"] as? Number)?.toFloat() ?: 5000f).toInt())
        val sw = (params["thickness"] as? Number)?.toFloat() ?: 2f
        val speed = (params["speed"] as? Number)?.toFloat() ?: 0.5f
        val style = (params["style"] as? String) ?: "flow"

        val isAnim = time > 0f
        val cx = w / 2f
        val cy = h / 2f
        val baseScale = min(w, h) * 0.44f

        // Dynamic modulations for organic, alive feel
        val breathe = 1f + 0.05f * sin(time * 0.7f)
        val scale = baseScale * breathe
        // Micro-wobble: irrational frequency perturbation for organic drift
        val fxEff = fx + 0.02f * sin(time * 0.31f)
        val fyEff = fy + 0.02f * sin(time * 0.43f)
        // Per-layer rotation base rate
        val globalRot = time * 0.08f

        val animPhase = basePhase + time * speed

        val period = (2f * PI.toFloat()) / gcd(fx, fy)
        val baseTMax = if (decay > 0f) {
            min(period * 8f, ln(100f) / max(decay, 1e-6f))
        } else period
        // Modulated pattern needs longer traversal
        val tMax = if (pattern == "modulated") max(baseTMax, period * 3f) else baseTMax

        // Harmonic perturbation frequencies (3rd-pendulum shimmer)
        val pertFreqX = (fx + fy).toFloat()
        val pertFreqY = (abs(fx - fy) + 1).toFloat()
        val pertPhX = time * 1.7f
        val pertPhY = time * 1.3f

        // Pattern constants
        val compoundScale = 1f / 1.35f
        val starburstK = max(2, ((fx + fy) * 0.5f).roundToInt()).toFloat()

        val samples = when (quality) {
            Quality.DRAFT -> (baseSamples / 2).coerceAtLeast(500)
            Quality.BALANCED -> baseSamples
            Quality.ULTRA -> (baseSamples * 1.5f).toInt()
        }
        val nSeg = if (quality == Quality.DRAFT) 15 else if (isAnim) 30 else 80
        val taperLen = if (decay > 0f) max(5, (samples * 0.04f).toInt()) else 0

        canvas.drawColor(Color.BLACK)

        val colors = palette.colors.map { Color.parseColor(it) }.toIntArray()
        val nColors = colors.size

        // Pre-allocate curve buffers
        val totalPts = samples + 1
        val curveX = FloatArray(totalPts)
        val curveY = FloatArray(totalPts)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        for (layer in 0 until layers) {
            val ph = animPhase + (layer.toFloat() / layers) * PI.toFloat()
            val layerRot = globalRot * (1f + layer * 0.25f)
            val cosR = cos(layerRot)
            val sinR = sin(layerRot)
            val layerScale = scale * (1f - layer * 0.015f)
            val layerColorOff = (layer.toFloat() / max(layers, 1)) * 0.35f

            // ── Compute curve points into buffer ──
            for (i in 0..samples) {
                val tt = (i.toFloat() / samples) * tMax
                val env = if (decay > 0f) exp(-decay * tt) else 1f
                val rawX: Float; val rawY: Float

                when (pattern) {
                    "modulated" -> {
                        val phModX = PI.toFloat() * 0.8f * sin(0.5f * tt)
                        val phModY = PI.toFloat() * 0.5f * sin(0.35f * tt + 0.5f)
                        rawX = sin(fxEff * tt + ph + phModX) * layerScale * env
                        rawY = sin(fyEff * tt + phModY) * layerScale * env
                    }
                    "compound" -> {
                        val xPrimary = sin(fxEff * tt + ph)
                        val xOvertone = 0.35f * sin((fxEff * 2 + 1) * tt)
                        val yPrimary = sin(fyEff * tt)
                        val yOvertone = 0.35f * sin((fyEff * 2 - 1) * tt)
                        rawX = (xPrimary + xOvertone + sin(pertFreqX * tt + pertPhX) * 0.015f) * layerScale * compoundScale * env
                        rawY = (yPrimary + yOvertone + sin(pertFreqY * tt + pertPhY) * 0.015f) * layerScale * compoundScale * env
                    }
                    "starburst" -> {
                        val radialMod = 0.5f + 0.5f * cos(tt * starburstK)
                        rawX = sin(fxEff * tt + ph) * layerScale * env * radialMod
                        rawY = sin(fyEff * tt) * layerScale * env * radialMod
                    }
                    else -> {
                        // classic with harmonic perturbation
                        rawX = (sin(fxEff * tt + ph) + sin(pertFreqX * tt + pertPhX) * 0.025f) * layerScale * env
                        rawY = (sin(fyEff * tt) + sin(pertFreqY * tt + pertPhY) * 0.025f) * layerScale * env
                    }
                }

                curveX[i] = cx + rawX * cosR - rawY * sinR
                curveY[i] = cy + rawX * sinR + rawY * cosR
            }

            // ── Draw based on style ──
            when (style) {
                "flow" -> drawFlowStyle(canvas, curveX, curveY, samples, nSeg,
                    paint, sw, decay, tMax, time, layerColorOff, colors, nColors, taperLen)
                "solid" -> drawSolidStyle(canvas, curveX, curveY, samples,
                    paint, sw, colors[layer % nColors], taperLen)
                "rainbow" -> drawRainbowStyle(canvas, curveX, curveY, samples, nSeg,
                    paint, sw, layer, layers, taperLen)
                "neon" -> drawNeonStyle(canvas, curveX, curveY, samples,
                    paint, sw, colors[layer % nColors], isAnim)
                "dotted" -> drawDottedStyle(canvas, curveX, curveY, samples,
                    sw, colors[layer % nColors], taperLen)
                else -> drawFlowStyle(canvas, curveX, curveY, samples, nSeg,
                    paint, sw, decay, tMax, time, layerColorOff, colors, nColors, taperLen)
            }
        }
    }

    // ── Flow style: glow underlay + gradient segments with thickness modulation ──

    private fun drawFlowStyle(
        canvas: Canvas, curveX: FloatArray, curveY: FloatArray,
        samples: Int, nSeg: Int, paint: Paint, sw: Float,
        decay: Float, tMax: Float, time: Float, layerColorOff: Float,
        colors: IntArray, nColors: Int, taperLen: Int,
    ) {
        // Glow pass: wide, dim, additive-blended underlay
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
        paint.strokeWidth = sw * 4f
        val glowT = (time * 0.12f + layerColorOff) % 1f
        val glowColor = lerpColorInt(colors, nColors, glowT)
        paint.color = glowColor
        paint.alpha = 50 // ~0.2 * 255

        val glowStep = max(1, samples / 1200)
        val glowPath = Path()
        glowPath.moveTo(curveX[0], curveY[0])
        var i = glowStep
        while (i <= samples) { glowPath.lineTo(curveX[i], curveY[i]); i += glowStep }
        canvas.drawPath(glowPath, paint)
        paint.xfermode = null

        // Core pass: segmented with flowing color gradient + thickness modulation
        for (seg in 0 until nSeg) {
            val segT = seg.toFloat() / nSeg
            val amp = if (decay > 0f) exp(-decay * segT * tMax) else 1f

            val ct = (segT + time * 0.12f + layerColorOff) % 1f
            paint.color = lerpColorInt(colors, nColors, ct)
            paint.alpha = if (decay > 0f) (255 * max(0.08f, amp)).toInt().coerceIn(20, 255) else 255

            // Dynamic thickness: undulates along curve and with time
            val thickMod = if (decay > 0f) {
                0.5f + amp * 0.7f
            } else {
                0.8f + 0.3f * sin(segT * PI.toFloat() * 3f + time * 2f)
            }
            paint.strokeWidth = sw * thickMod

            val iStart = floor(segT * samples).toInt()
            val iEnd = ceil(((seg + 1).toFloat() / nSeg) * samples).toInt().coerceAtMost(samples)

            val path = Path()
            path.moveTo(curveX[iStart], curveY[iStart])
            for (j in iStart + 1..iEnd) path.lineTo(curveX[j], curveY[j])
            canvas.drawPath(path, paint)
        }
    }

    // ── Solid style: single color, clean stroke ──

    private fun drawSolidStyle(
        canvas: Canvas, curveX: FloatArray, curveY: FloatArray,
        samples: Int, paint: Paint, sw: Float, color: Int, taperLen: Int,
    ) {
        paint.color = color
        paint.strokeWidth = sw
        paint.xfermode = null

        if (taperLen > 0) {
            drawTaperedPath(canvas, curveX, curveY, samples, paint, taperLen)
        } else {
            val path = Path()
            path.moveTo(curveX[0], curveY[0])
            for (i in 1..samples) path.lineTo(curveX[i], curveY[i])
            paint.alpha = 255
            canvas.drawPath(path, paint)
        }
    }

    // ── Rainbow style: HSL hue cycling ──

    private fun drawRainbowStyle(
        canvas: Canvas, curveX: FloatArray, curveY: FloatArray,
        samples: Int, nSeg: Int, paint: Paint, sw: Float,
        layer: Int, layers: Int, taperLen: Int,
    ) {
        paint.strokeWidth = sw
        paint.xfermode = null
        val hsv = floatArrayOf(0f, 0.85f, 0.70f)

        for (seg in 0 until nSeg) {
            val iStart = (seg * samples / nSeg)
            val iEnd = min(samples, ((seg + 1) * samples / nSeg))
            hsv[0] = (((seg.toFloat() / nSeg + layer.toFloat() / layers) * 360f) % 360f)
            paint.color = Color.HSVToColor(hsv)

            if (taperLen > 0) {
                val midPt = (iStart + iEnd) / 2
                paint.alpha = (taperAlpha(midPt, samples, taperLen) * 255).toInt().coerceIn(0, 255)
            } else {
                paint.alpha = 255
            }

            val path = Path()
            path.moveTo(curveX[iStart], curveY[iStart])
            for (i in iStart + 1..iEnd) path.lineTo(curveX[i], curveY[i])
            canvas.drawPath(path, paint)
        }
    }

    // ── Neon style: multi-pass bloom (no shadow blur on Android) ──

    private fun drawNeonStyle(
        canvas: Canvas, curveX: FloatArray, curveY: FloatArray,
        samples: Int, paint: Paint, sw: Float, color: Int, isAnim: Boolean,
    ) {
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
        val r = Color.red(color); val g = Color.green(color); val b = Color.blue(color)

        // Subsample for glow (fine detail hidden by bloom)
        val step = if (isAnim) max(1, samples / 1500) else max(1, samples / 3000)

        // Outer glow: very wide, very dim
        paint.strokeWidth = sw * 6f
        paint.color = Color.argb(30, r, g, b)
        var path = Path()
        path.moveTo(curveX[0], curveY[0])
        var i = step; while (i <= samples) { path.lineTo(curveX[i], curveY[i]); i += step }
        canvas.drawPath(path, paint)

        // Mid glow: medium width, medium brightness
        paint.strokeWidth = sw * 3f
        paint.color = Color.argb(70, r, g, b)
        path = Path()
        path.moveTo(curveX[0], curveY[0])
        i = step; while (i <= samples) { path.lineTo(curveX[i], curveY[i]); i += step }
        canvas.drawPath(path, paint)

        // Core: narrow, bright, boosted color
        paint.strokeWidth = sw * 1.5f
        paint.color = Color.argb(220,
            min(255, r + 100), min(255, g + 100), min(255, b + 100))
        path = Path()
        path.moveTo(curveX[0], curveY[0])
        i = step; while (i <= samples) { path.lineTo(curveX[i], curveY[i]); i += step }
        canvas.drawPath(path, paint)

        paint.xfermode = null
    }

    // ── Dotted style: circles along curve ──

    private fun drawDottedStyle(
        canvas: Canvas, curveX: FloatArray, curveY: FloatArray,
        samples: Int, sw: Float, color: Int, taperLen: Int,
    ) {
        val dotR = sw * 1.2f
        val dotStep = max(1, samples / 200)
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            this.color = color
        }

        var i = 0
        while (i <= samples) {
            if (taperLen > 0) {
                fillPaint.alpha = (taperAlpha(i, samples, taperLen) * 255).toInt().coerceIn(0, 255)
            }
            canvas.drawCircle(curveX[i], curveY[i], dotR, fillPaint)
            i += dotStep
        }
    }

    // ── Helpers ──

    private fun taperAlpha(pos: Int, total: Int, taperLen: Int): Float {
        if (taperLen <= 0) return 1f
        if (pos < taperLen) return pos.toFloat() / taperLen
        if (pos > total - taperLen) return (total - pos).toFloat() / taperLen
        return 1f
    }

    private fun drawTaperedPath(
        canvas: Canvas, curveX: FloatArray, curveY: FloatArray,
        samples: Int, paint: Paint, taperLen: Int,
    ) {
        val segs = 8
        // Fade-in
        for (s in 0 until segs) {
            val i0 = (s * taperLen / segs)
            val i1 = min(taperLen, ((s + 1) * taperLen / segs))
            paint.alpha = ((s + 0.5f) / segs * 255).toInt().coerceIn(0, 255)
            val path = Path()
            path.moveTo(curveX[i0], curveY[i0])
            for (i in i0 + 1..i1) path.lineTo(curveX[i], curveY[i])
            canvas.drawPath(path, paint)
        }
        // Middle
        val mEnd = samples - taperLen
        if (mEnd > taperLen) {
            paint.alpha = 255
            val path = Path()
            path.moveTo(curveX[taperLen], curveY[taperLen])
            for (i in taperLen + 1..mEnd) path.lineTo(curveX[i], curveY[i])
            canvas.drawPath(path, paint)
        }
        // Fade-out
        for (s in 0 until segs) {
            val i0 = mEnd + (s * taperLen / segs)
            val i1 = min(samples, mEnd + ((s + 1) * taperLen / segs))
            paint.alpha = ((1f - (s + 0.5f) / segs) * 255).toInt().coerceIn(0, 255)
            val path = Path()
            path.moveTo(curveX[i0], curveY[i0])
            for (i in i0 + 1..i1) path.lineTo(curveX[i], curveY[i])
            canvas.drawPath(path, paint)
        }
    }

    private fun lerpColorInt(colors: IntArray, n: Int, t: Float): Int {
        val ci = t.coerceIn(0f, 1f) * (n - 1)
        val i0 = ci.toInt().coerceIn(0, n - 1)
        val i1 = min(n - 1, i0 + 1)
        val f = ci - i0
        val c0 = colors[i0]; val c1 = colors[i1]
        val r = ((Color.red(c0) + (Color.red(c1) - Color.red(c0)) * f)).toInt()
        val g = ((Color.green(c0) + (Color.green(c1) - Color.green(c0)) * f)).toInt()
        val b = ((Color.blue(c0) + (Color.blue(c1) - Color.blue(c0)) * f)).toInt()
        return Color.rgb(r, g, b)
    }

    // ── SVG rendering ──

    override fun renderVector(
        params: Map<String, Any>,
        seed: Int,
        palette: Palette
    ): List<SvgPath> {
        val w = 1080f; val h = 1080f
        val fx = max(1, ((params["ax"] as? Number)?.toFloat() ?: 5f).toInt())
        val fy = max(1, ((params["ay"] as? Number)?.toFloat() ?: 4f).toInt())
        val basePhase = (params["phase"] as? Number)?.toFloat() ?: 1.57f
        val decay = max(0f, (params["decay"] as? Number)?.toFloat() ?: 0f)
        val pattern = (params["pattern"] as? String) ?: "classic"
        val layers = max(1, ((params["layers"] as? Number)?.toFloat() ?: 1f).toInt())
        val baseSamples = max(100, ((params["samples"] as? Number)?.toFloat() ?: 5000f).toInt())
        val strokeWidth = (params["thickness"] as? Number)?.toFloat() ?: 2f

        val cxSvg = w / 2f; val cySvg = h / 2f
        val scale = min(w, h) * 0.44f
        val period = (2f * PI.toFloat()) / gcd(fx, fy)
        val baseTMax = if (decay > 0f) min(period * 8f, ln(100f) / max(decay, 1e-6f)) else period
        val tMax = if (pattern == "modulated") max(baseTMax, period * 3f) else baseTMax
        val compoundNorm = 1f / 1.35f
        val starburstK = max(2, ((fx + fy) * 0.5f).roundToInt()).toFloat()

        val paths = mutableListOf<SvgPath>()
        val colors = palette.colors.map { Color.parseColor(it) }.toIntArray()

        for (layer in 0 until layers) {
            val ph = basePhase + (layer.toFloat() / layers) * PI.toFloat()
            val hexColor = palette.colors[layer % palette.colors.size]

            val sb = StringBuilder()
            for (i in 0..baseSamples) {
                val tt = (i.toFloat() / baseSamples) * tMax
                val env = exp(-decay * tt)
                val rawX: Float; val rawY: Float

                when (pattern) {
                    "modulated" -> {
                        val phModX = PI.toFloat() * 0.8f * sin(0.5f * tt)
                        val phModY = PI.toFloat() * 0.5f * sin(0.35f * tt + 0.5f)
                        rawX = sin(fx * tt + ph + phModX) * scale * env
                        rawY = sin(fy * tt + phModY) * scale * env
                    }
                    "compound" -> {
                        rawX = (sin(fx * tt + ph) + 0.35f * sin((fx * 2 + 1) * tt)) * scale * compoundNorm * env
                        rawY = (sin(fy * tt) + 0.35f * sin((fy * 2 - 1) * tt)) * scale * compoundNorm * env
                    }
                    "starburst" -> {
                        val rm = 0.5f + 0.5f * cos(tt * starburstK)
                        rawX = sin(fx * tt + ph) * scale * env * rm
                        rawY = sin(fy * tt) * scale * env * rm
                    }
                    else -> {
                        rawX = sin(fx * tt + ph) * scale * env
                        rawY = sin(fy * tt) * scale * env
                    }
                }

                val x = cxSvg + rawX; val y = cySvg + rawY
                if (i == 0) sb.append(SvgBuilder.moveTo(x, y))
                else sb.append(" ").append(SvgBuilder.lineTo(x, y))
            }

            val alpha = if (decay > 0f) exp(-decay * 0f).coerceIn(0.08f, 1f) else 1f
            paths.add(SvgPath(d = sb.toString(), stroke = hexColor, strokeWidth = strokeWidth, opacity = alpha))
        }
        return paths
    }

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float {
        val layers = (params["layers"] as? Number)?.toInt() ?: 1
        val samples = (params["samples"] as? Number)?.toInt() ?: 5000
        val styleMul = if ((params["style"] as? String) == "neon") 1.5f else 1f
        return (layers * samples / 30000f * styleMul).coerceIn(0.2f, 1f)
    }
}
