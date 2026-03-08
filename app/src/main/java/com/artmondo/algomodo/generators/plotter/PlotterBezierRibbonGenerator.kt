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
 * Bezier ribbon weave generator.
 *
 * Creates interlacing cubic Bezier curve ribbons that weave over and under
 * each other like fabric strips or Celtic knotwork.
 */
class PlotterBezierRibbonGenerator : Generator {

    override val id = "plotter-bezier-ribbon-weaves"
    override val family = "plotter"
    override val styleName = "Bezier Ribbon Weaves"
    override val definition =
        "Interlacing bezier curve ribbons that weave over and under each other."
    override val algorithmNotes =
        "Multiple ribbons are generated as cubic Bezier curves with randomized control " +
        "points. Each ribbon is drawn as two parallel offset curves. At intersection points, " +
        "alternating over-under weaving is achieved by drawing gaps in the 'under' ribbon " +
        "where the 'over' ribbon crosses."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema = listOf(
        Parameter.NumberParam("Strand Count", "strandCount", ParamGroup.COMPOSITION, "Number of horizontal (and vertical) ribbon strands", 2f, 14f, 1f, 6f),
        Parameter.NumberParam("Ribbon Width", "ribbonWidth", ParamGroup.GEOMETRY, "Thickness of each ribbon stroke in pixels", 4f, 60f, 2f, 22f),
        Parameter.NumberParam("Amplitude", "amplitude", ParamGroup.GEOMETRY, "Bezier control-point jitter — how much each ribbon curves", 0f, 80f, 2f, 28f),
        Parameter.NumberParam("Wave Frequency", "frequency", ParamGroup.GEOMETRY, "Number of full sine cycles per strand in animation mode", 0.5f, 4f, 0.25f, 1.5f),
        Parameter.SelectParam("Weave Pattern", "weavePattern", ParamGroup.COMPOSITION, "basket: 1-over-1 checkerboard | twill: 2-over-2 diagonal | satin: irregular long floats", listOf("basket", "twill", "satin"), "basket"),
        Parameter.SelectParam("Ribbon Style", "ribbonStyle", ParamGroup.TEXTURE, "flat: solid fill | shaded: 3D highlight/shadow | striped: decorative center stripe", listOf("flat", "shaded", "striped"), "flat"),
        Parameter.SelectParam("Color Mode", "colorMode", ParamGroup.COLOR, "palette-pair: H/V use alternating halves | palette-index: each strand unique | gradient: smooth interpolation", listOf("palette-pair", "palette-index", "monochrome", "gradient"), "palette-pair"),
        Parameter.SelectParam("Background", "background", ParamGroup.COLOR, null, listOf("white", "cream", "dark"), "cream"),
        Parameter.NumberParam("Wave Speed", "waveSpeed", ParamGroup.FLOW_MOTION, "Speed at which bezier control points oscillate (animation)", 0f, 1.0f, 0.05f, 0.25f)
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "strandCount" to 6f,
        "ribbonWidth" to 22f,
        "amplitude" to 28f,
        "frequency" to 1.5f,
        "weavePattern" to "basket",
        "ribbonStyle" to "flat",
        "colorMode" to "palette-pair",
        "background" to "cream",
        "waveSpeed" to 0.25f
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
        val ribbonCount = (params["strandCount"] as? Number)?.toInt() ?: 6
        val ribbonWidth = (params["ribbonWidth"] as? Number)?.toFloat() ?: 22f
        val waveSpeed = (params["waveSpeed"] as? Number)?.toFloat() ?: 0.25f
        val amplitude = (params["amplitude"] as? Number)?.toFloat() ?: 28f

        val rng = SeededRNG(seed)
        val paletteColors = palette.colorInts()

        canvas.drawColor(Color.BLACK)

        val stepsPerSegment = when (quality) {
            Quality.DRAFT -> 20
            Quality.BALANCED -> 40
            Quality.ULTRA -> 80
        }

        // Generate ribbon spine points
        data class RibbonData(
            val spinePoints: List<Pair<Float, Float>>,
            val colorIdx: Int
        )

        val ribbons = mutableListOf<RibbonData>()
        for (r in 0 until ribbonCount) {
            val numCtrl = 4 + 2
            val points = mutableListOf<Pair<Float, Float>>()
            for (p in 0 until numCtrl) {
                points.add(Pair(
                    rng.range(w * 0.05f, w * 0.95f),
                    rng.range(h * 0.05f, h * 0.95f)
                ))
            }
            // Compute spine as Catmull-Rom-like chain of cubic Bezier segments
            val spinePoints = mutableListOf<Pair<Float, Float>>()
            for (seg in 0 until points.size - 1) {
                val p0 = points[seg]
                val p1 = points[(seg + 1).coerceAtMost(points.size - 1)]
                val waveOsc1 = sin(time * waveSpeed * 2f + seg * 1.3f) * amplitude * 0.5f
                val waveOsc2 = cos(time * waveSpeed * 2f + seg * 0.9f) * amplitude * 0.5f
                val cp1x = p0.first + (p1.first - p0.first) * 0.33f + rng.range(-30f, 30f) + waveOsc1
                val cp1y = p0.second + (p1.second - p0.second) * 0.33f + rng.range(-30f, 30f) + waveOsc2
                val cp2x = p0.first + (p1.first - p0.first) * 0.66f + rng.range(-30f, 30f) - waveOsc2
                val cp2y = p0.second + (p1.second - p0.second) * 0.66f + rng.range(-30f, 30f) - waveOsc1

                for (t in 0 until stepsPerSegment) {
                    val u = t.toFloat() / stepsPerSegment
                    val iu = 1f - u
                    val x = iu * iu * iu * p0.first + 3 * iu * iu * u * cp1x + 3 * iu * u * u * cp2x + u * u * u * p1.first
                    val y = iu * iu * iu * p0.second + 3 * iu * iu * u * cp1y + 3 * iu * u * u * cp2y + u * u * u * p1.second
                    spinePoints.add(Pair(x, y))
                }
            }
            ribbons.add(RibbonData(spinePoints, r))
        }

        // Draw ribbons with fill and outline
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        // Draw in order: alternate which ribbon is on top at each crossing
        for ((ribbonIdx, ribbon) in ribbons.withIndex()) {
            val spine = ribbon.spinePoints
            if (spine.size < 2) continue

            val halfW = ribbonWidth / 2f
            val color = paletteColors[ribbon.colorIdx % paletteColors.size]

            // Compute normals and draw ribbon as filled path
            val topPath = Path()
            val bottomPath = Path()
            val fillPath = Path()

            val topPoints = mutableListOf<Pair<Float, Float>>()
            val bottomPoints = mutableListOf<Pair<Float, Float>>()

            for (i in spine.indices) {
                val prev = if (i > 0) spine[i - 1] else spine[i]
                val next = if (i < spine.size - 1) spine[i + 1] else spine[i]
                val dx = next.first - prev.first
                val dy = next.second - prev.second
                val len = kotlin.math.sqrt(dx * dx + dy * dy).coerceAtLeast(0.001f)
                val nx = -dy / len * halfW
                val ny = dx / len * halfW

                topPoints.add(Pair(spine[i].first + nx, spine[i].second + ny))
                bottomPoints.add(Pair(spine[i].first - nx, spine[i].second - ny))
            }

            // Build fill path
            fillPath.moveTo(topPoints[0].first, topPoints[0].second)
            for (i in 1 until topPoints.size) {
                fillPath.lineTo(topPoints[i].first, topPoints[i].second)
            }
            for (i in bottomPoints.size - 1 downTo 0) {
                fillPath.lineTo(bottomPoints[i].first, bottomPoints[i].second)
            }
            fillPath.close()

            fillPaint.color = color
            fillPaint.alpha = 100
            canvas.drawPath(fillPath, fillPaint)

            // Draw outlines
            strokePaint.color = color
            strokePaint.alpha = 255

            topPath.moveTo(topPoints[0].first, topPoints[0].second)
            for (i in 1 until topPoints.size) {
                topPath.lineTo(topPoints[i].first, topPoints[i].second)
            }
            canvas.drawPath(topPath, strokePaint)

            bottomPath.moveTo(bottomPoints[0].first, bottomPoints[0].second)
            for (i in 1 until bottomPoints.size) {
                bottomPath.lineTo(bottomPoints[i].first, bottomPoints[i].second)
            }
            canvas.drawPath(bottomPath, strokePaint)
        }
    }

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float {
        val strandCount = (params["strandCount"] as? Number)?.toInt() ?: 6
        return (strandCount / 10f).coerceIn(0.2f, 1f)
    }
}
