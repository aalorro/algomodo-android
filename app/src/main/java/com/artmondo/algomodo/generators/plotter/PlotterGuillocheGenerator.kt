package com.artmondo.algomodo.generators.plotter

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import com.artmondo.algomodo.core.rng.SeededRNG
import com.artmondo.algomodo.data.palettes.Palette
import com.artmondo.algomodo.generators.Generator
import com.artmondo.algomodo.generators.ParamGroup
import com.artmondo.algomodo.generators.Parameter
import com.artmondo.algomodo.generators.Quality
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin

/**
 * Guilloche pattern generator.
 *
 * Concentric parametric curve rings (hypotrochoid, epitrochoid, rose, lissajous)
 * producing the interference moiré of banknote security print.
 */
class PlotterGuillocheGenerator : Generator {

    override val id = "plotter-guilloche"
    override val family = "plotter"
    override val styleName = "Guilloche"
    override val definition =
        "Concentric parametric curve rings producing the interference moiré of banknote security print."
    override val algorithmNotes =
        "Multiple curve families (hypotrochoid, epitrochoid, rose, lissajous) are available. " +
        "Each ring can contain multiple phase-offset lines that weave together, creating " +
        "authentic guilloché density. Wave modulation adds sinusoidal radius breathing. " +
        "Successive rings have slightly different eccentricity for natural interference."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema = listOf(
        Parameter.NumberParam("Ring Count", "ringCount", ParamGroup.COMPOSITION, "Number of concentric guilloche rings", 1f, 12f, 1f, 5f),
        Parameter.NumberParam("Petals", "petals", ParamGroup.COMPOSITION, "Number of lobes per ring", 3f, 20f, 1f, 7f),
        Parameter.SelectParam("Curve Type", "curveType", ParamGroup.COMPOSITION, "hypotrochoid: inner rolling circle | epitrochoid: outer rolling | rose: polar petals | lissajous: frequency ratio", listOf("hypotrochoid", "epitrochoid", "rose", "lissajous"), "hypotrochoid"),
        Parameter.NumberParam("Lines Per Ring", "linesPerRing", ParamGroup.COMPOSITION, "Multiple phase-offset curves per ring — creates dense weave/moire", 1f, 6f, 1f, 1f),
        Parameter.NumberParam("Eccentricity", "eccentricity", ParamGroup.GEOMETRY, "Petal depth — 0 = circle, approaching 1 = sharp cusps", 0.1f, 0.98f, 0.02f, 0.65f),
        Parameter.NumberParam("Ring Spread", "ringSpread", ParamGroup.GEOMETRY, "Radial gap between successive rings (fraction of canvas half-size)", 0.03f, 0.2f, 0.01f, 0.09f),
        Parameter.NumberParam("Wave Modulation", "waveModulation", ParamGroup.GEOMETRY, "Sinusoidal radius modulation — adds undulating wave to each ring", 0f, 1f, 0.05f, 0f),
        Parameter.NumberParam("Line Width", "lineWidth", ParamGroup.TEXTURE, null, 0.25f, 3f, 0.25f, 0.75f),
        Parameter.SelectParam("Color Mode", "colorMode", ParamGroup.COLOR, "monochrome: single ink | palette-rings: one color per ring | interference: alternating | gradient-sweep: hue rotates along each curve", listOf("monochrome", "palette-rings", "interference", "gradient-sweep"), "palette-rings"),
        Parameter.SelectParam("Background", "background", ParamGroup.COLOR, null, listOf("white", "cream", "dark"), "cream"),
        Parameter.NumberParam("Spin Speed", "spinSpeed", ParamGroup.FLOW_MOTION, "Rotation speed (rad/s). Each ring spins at a different rate with alternating direction.", 0f, 3.0f, 0.05f, 0.5f)
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "ringCount" to 5f,
        "petals" to 7f,
        "curveType" to "hypotrochoid",
        "linesPerRing" to 1f,
        "eccentricity" to 0.65f,
        "ringSpread" to 0.09f,
        "waveModulation" to 0f,
        "lineWidth" to 0.75f,
        "colorMode" to "palette-rings",
        "background" to "cream",
        "spinSpeed" to 0.5f
    )

    companion object {
        private const val TWO_PI = 2f * Math.PI.toFloat()
        private const val PI_F = Math.PI.toFloat()

        // Curve type constants to avoid string comparison in hot loop
        private const val CURVE_HYPOTROCHOID = 0
        private const val CURVE_EPITROCHOID = 1
        private const val CURVE_ROSE = 2
        private const val CURVE_LISSAJOUS = 3

        private val BG = mapOf(
            "white" to Color.rgb(248, 248, 245),
            "cream" to Color.rgb(242, 234, 216),
            "dark"  to Color.rgb(14, 14, 14)
        )
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
        val rng = SeededRNG(seed)
        val paletteColors = palette.colorInts()

        val ringCount = ((params["ringCount"] as? Number)?.toInt() ?: 5).coerceAtLeast(1)
        val k = ((params["petals"] as? Number)?.toInt() ?: 7).coerceAtLeast(3)
        val eccBase = (params["eccentricity"] as? Number)?.toFloat() ?: 0.65f
        val spread = (params["ringSpread"] as? Number)?.toFloat() ?: 0.09f
        val spinSpeed = (params["spinSpeed"] as? Number)?.toFloat() ?: 0.12f
        val colorMode = (params["colorMode"] as? String) ?: "palette-rings"
        val curveTypeStr = (params["curveType"] as? String) ?: "hypotrochoid"
        val linesPerRing = ((params["linesPerRing"] as? Number)?.toInt() ?: 1).coerceIn(1, 6)
        val waveMod = (params["waveModulation"] as? Number)?.toFloat() ?: 0f
        val lineWidth = (params["lineWidth"] as? Number)?.toFloat() ?: 0.75f
        val background = (params["background"] as? String) ?: "cream"
        val isDark = background == "dark"

        // Resolve curve type to int constant once (avoid string comparison per point)
        val curveType = when (curveTypeStr) {
            "epitrochoid" -> CURVE_EPITROCHOID
            "rose" -> CURVE_ROSE
            "lissajous" -> CURVE_LISSAJOUS
            else -> CURVE_HYPOTROCHOID
        }

        // Background
        canvas.drawColor(BG[background] ?: BG["cream"]!!)

        val cxc = w / 2f
        val cyc = h / 2f
        val halfSize = min(w, h) * 0.48f

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = lineWidth
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        val steps = when (quality) {
            Quality.DRAFT -> 720
            Quality.BALANCED -> 1200
            Quality.ULTRA -> 2400
        }

        // Pre-allocate path and point buffer — avoids GC pressure from
        // creating new Path + Pair<Float,Float> objects every frame.
        val path = Path()
        val kf = k.toFloat()
        val invSteps = 1f / steps

        for (ri in 0 until ringCount) {
            val eccStatic = eccBase * (0.78f + ri * 0.06f + rng.range(0f, 0.08f))
            val ringRadiusBase = halfSize * (0.3f + ri * spread)

            // Alternating rotation direction; each ring spins at a different rate
            val direction = if (ri % 2 == 0) 1f else -1f
            val ringSpeedMult = 1f + ri * 0.18f
            val phase = time * spinSpeed * direction * ringSpeedMult

            // Breathing: radius oscillates per ring at offset frequencies
            val breathe = if (spinSpeed > 0f)
                1f + 0.04f * sin(time * (0.5f + ri * 0.12f) * TWO_PI)
            else 1f
            val ringRadius = ringRadiusBase * breathe

            // Eccentricity oscillation: petals gently grow/shrink over time
            val eccOsc = if (spinSpeed > 0f)
                0.06f * sin(time * 0.4f * TWO_PI + ri * 0.8f)
            else 0f
            val ecc = (eccStatic + eccOsc).coerceIn(0.1f, 0.98f)

            // Pre-compute per-ring curve constants
            val d = kf * ecc
            val curveScale: Float
            val lissPhase: Float
            when (curveType) {
                CURVE_EPITROCHOID -> {
                    curveScale = ringRadius / (k + 1 + d)
                    lissPhase = 0f
                }
                CURVE_HYPOTROCHOID -> {
                    curveScale = ringRadius / (kf + d)
                    lissPhase = 0f
                }
                CURVE_LISSAJOUS -> {
                    curveScale = ringRadius
                    lissPhase = ecc * PI_F
                }
                else -> { // ROSE
                    curveScale = ringRadius
                    lissPhase = 0f
                }
            }
            val waveFreq = k * 3
            val useWave = waveMod > 0f
            val waveFactor = waveMod * 0.3f

            for (li in 0 until linesPerRing) {
                val linePhase = phase + (li.toFloat() / linesPerRing) * TWO_PI / kf

                val baseAlpha = if (isDark) 0.85f else 0.80f
                val lineAlpha = if (linesPerRing > 1) baseAlpha * (0.5f + 0.5f / linesPerRing) else baseAlpha
                val alphaInt = (lineAlpha * 255).toInt().coerceIn(0, 255)

                if (colorMode == "gradient-sweep") {
                    drawGradientSweepInline(
                        canvas, paint, path, paletteColors, alphaInt,
                        steps, invSteps, linePhase, k, kf, curveType, d, curveScale,
                        lissPhase, ringRadius, useWave, waveFreq, waveFactor,
                        cxc, cyc
                    )
                } else {
                    val color = when (colorMode) {
                        "palette-rings" -> paletteColors[ri % paletteColors.size]
                        "interference" -> if (ri % 2 == 0) paletteColors[0] else paletteColors[paletteColors.size - 1]
                        else -> if (isDark) Color.rgb(220, 220, 220) else Color.rgb(30, 30, 30)
                    }
                    paint.color = color
                    paint.alpha = alphaInt

                    path.reset()
                    for (step in 0..steps) {
                        val t = step * invSteps * TWO_PI + linePhase
                        val px = curvePointX(t, curveType, k, kf, d, curveScale, lissPhase, ringRadius)
                        val py = curvePointY(t, curveType, k, kf, d, curveScale, lissPhase, ringRadius)
                        val wx: Float
                        val wy: Float
                        if (useWave) {
                            val wave = 1f + waveFactor * sin(waveFreq * t)
                            wx = px * wave
                            wy = py * wave
                        } else {
                            wx = px
                            wy = py
                        }
                        if (step == 0) path.moveTo(cxc + wx, cyc + wy)
                        else path.lineTo(cxc + wx, cyc + wy)
                    }
                    path.close()
                    canvas.drawPath(path, paint)
                }
            }
        }
    }

    // Inline X coordinate computation — avoids Pair allocation
    private fun curvePointX(
        t: Float, curveType: Int, k: Int, kf: Float, d: Float,
        s: Float, lissPhase: Float, ringRadius: Float
    ): Float = when (curveType) {
        CURVE_EPITROCHOID -> s * ((k + 1) * cos(t) - d * cos((k + 1) * t))
        CURVE_ROSE -> ringRadius * cos(kf * t) * cos(t)
        CURVE_LISSAJOUS -> s * sin(kf * t + lissPhase)
        else -> s * (kf * cos(t) + d * cos(kf * t)) // HYPOTROCHOID
    }

    private fun curvePointY(
        t: Float, curveType: Int, k: Int, kf: Float, d: Float,
        s: Float, lissPhase: Float, ringRadius: Float
    ): Float = when (curveType) {
        CURVE_EPITROCHOID -> s * ((k + 1) * sin(t) - d * sin((k + 1) * t))
        CURVE_ROSE -> ringRadius * cos(kf * t) * sin(t)
        CURVE_LISSAJOUS -> s * sin((kf + 1f) * t)
        else -> s * (kf * sin(t) - d * sin(kf * t)) // HYPOTROCHOID
    }

    private fun drawGradientSweepInline(
        canvas: Canvas, paint: Paint, path: Path,
        colors: List<Int>, alphaInt: Int,
        steps: Int, invSteps: Float, linePhase: Float,
        k: Int, kf: Float, curveType: Int, d: Float, curveScale: Float,
        lissPhase: Float, ringRadius: Float,
        useWave: Boolean, waveFreq: Int, waveFactor: Float,
        cxc: Float, cyc: Float
    ) {
        val segLen = (steps + 59) / 60
        var segStart = 0
        while (segStart < steps) {
            val segEnd = min(segStart + segLen + 1, steps)
            val t0 = segStart.toFloat() / steps
            val ci = t0 * (colors.size - 1)
            val i0 = floor(ci).toInt()
            val i1 = min(colors.size - 1, i0 + 1)
            val f = ci - i0

            val cr = ((Color.red(colors[i0]) + (Color.red(colors[i1]) - Color.red(colors[i0])) * f).toInt()).coerceIn(0, 255)
            val cg = ((Color.green(colors[i0]) + (Color.green(colors[i1]) - Color.green(colors[i0])) * f).toInt()).coerceIn(0, 255)
            val cb = ((Color.blue(colors[i0]) + (Color.blue(colors[i1]) - Color.blue(colors[i0])) * f).toInt()).coerceIn(0, 255)

            paint.color = Color.argb(alphaInt, cr, cg, cb)

            path.reset()
            for (step in segStart..segEnd) {
                val t = step * invSteps * TWO_PI + linePhase
                val px = curvePointX(t, curveType, k, kf, d, curveScale, lissPhase, ringRadius)
                val py = curvePointY(t, curveType, k, kf, d, curveScale, lissPhase, ringRadius)
                val wx: Float
                val wy: Float
                if (useWave) {
                    val wave = 1f + waveFactor * sin(waveFreq * t)
                    wx = px * wave
                    wy = py * wave
                } else {
                    wx = px
                    wy = py
                }
                if (step == segStart) path.moveTo(cxc + wx, cyc + wy)
                else path.lineTo(cxc + wx, cyc + wy)
            }
            canvas.drawPath(path, paint)
            segStart += segLen
        }
    }

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float {
        val rings = (params["ringCount"] as? Number)?.toInt() ?: 5
        val lines = (params["linesPerRing"] as? Number)?.toInt() ?: 1
        return (rings * lines / 10f).coerceIn(0.2f, 1f)
    }
}
