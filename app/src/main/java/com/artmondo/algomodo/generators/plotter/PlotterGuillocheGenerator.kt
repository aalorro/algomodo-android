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
import com.artmondo.algomodo.rendering.SvgPath
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Guilloche pattern generator.
 *
 * Creates intricate overlapping sinusoidal wave envelopes similar to the
 * security patterns found on banknotes and certificates.
 */
class PlotterGuillocheGenerator : Generator {

    override val id = "plotter-guilloche"
    override val family = "plotter"
    override val styleName = "Guilloche"
    override val definition =
        "Overlapping sinusoidal wave envelopes creating banknote-style security patterns."
    override val algorithmNotes =
        "Multiple sinusoidal waves with different frequencies and amplitudes are layered. " +
        "Each wave is traced as a smooth path from left to right, with the Y position " +
        "determined by summing several sine components with seeded phase offsets. " +
        "The resulting dense wave interference creates moiré-like guilloche textures."
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
        Parameter.NumberParam("Spin Speed", "spinSpeed", ParamGroup.FLOW_MOTION, "Rotation speed (rad/s). Even/odd rings spin in opposite directions.", 0f, 1.0f, 0.05f, 0.12f)
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
        "spinSpeed" to 0.12f
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
        val w = bitmap.width.toFloat()
        val h = bitmap.height.toFloat()
        val waves = (params["ringCount"] as? Number)?.toInt() ?: 5
        val amplitude = 15f
        val frequency = 3f
        val lineWidth = (params["lineWidth"] as? Number)?.toFloat() ?: 0.75f
        val spinSpeed = (params["spinSpeed"] as? Number)?.toFloat() ?: 0.12f

        val rng = SeededRNG(seed)
        val paletteColors = palette.colorInts()

        canvas.drawColor(Color.BLACK)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = lineWidth
            strokeCap = Paint.Cap.ROUND
        }

        val stepPx = when (quality) {
            Quality.DRAFT -> 3f
            Quality.BALANCED -> 1.5f
            Quality.ULTRA -> 0.75f
        }

        // Number of horizontal lines to fill the canvas
        val lineSpacing = amplitude * 0.6f
        val numLines = (h / lineSpacing).toInt() + 1

        for (lineIdx in 0 until numLines) {
            val baseY = lineIdx * lineSpacing
            // Alternate phase direction for even/odd lines
            val phaseDir = if (lineIdx % 2 == 0) 1f else -1f

            for (waveIdx in 0 until waves) {
                val phase = rng.range(0f, 2f * PI.toFloat())
                val freqMult = frequency * (1f + waveIdx * 0.3f)
                val ampMult = amplitude * (1f - waveIdx * 0.05f)
                val secondaryPhase = rng.range(0f, PI.toFloat())
                val secondaryFreq = freqMult * 2.7f
                val timePhase = time * spinSpeed * phaseDir

                paint.color = paletteColors[waveIdx % paletteColors.size]
                paint.alpha = 150 + (105 * waveIdx / waves.coerceAtLeast(1))

                val path = Path()
                var first = true
                var x = 0f
                while (x <= w) {
                    val t = x / w
                    val primary = sin(t * freqMult * 2f * PI.toFloat() + phase + timePhase)
                    val secondary = sin(t * secondaryFreq * 2f * PI.toFloat() + secondaryPhase + timePhase) * 0.3f
                    val envelope = sin(t * PI.toFloat()) // fade at edges
                    val y = baseY + ampMult * (primary + secondary) * envelope

                    if (first) {
                        path.moveTo(x, y)
                        first = false
                    } else {
                        path.lineTo(x, y)
                    }
                    x += stepPx
                }
                canvas.drawPath(path, paint)
            }
        }
    }

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float {
        val waves = (params["ringCount"] as? Number)?.toInt() ?: 5
        return (waves / 10f).coerceIn(0.2f, 1f)
    }
}
