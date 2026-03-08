package com.artmondo.algomodo.generators.plotter

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import com.artmondo.algomodo.core.rng.SimplexNoise
import com.artmondo.algomodo.data.palettes.Palette
import com.artmondo.algomodo.generators.Generator
import com.artmondo.algomodo.generators.ParamGroup
import com.artmondo.algomodo.generators.Parameter
import com.artmondo.algomodo.generators.Quality
import com.artmondo.algomodo.rendering.SvgPath

/**
 * Contour line generator using marching squares on a noise field.
 *
 * Evaluates simplex noise on a grid and traces iso-value contour lines at
 * evenly spaced threshold levels. Optional smoothing interpolates edge
 * crossings for cleaner curves.
 */
class PlotterContourLinesGenerator : Generator {

    override val id = "plotter-contour-lines"
    override val family = "plotter"
    override val styleName = "Contour Lines"
    override val definition =
        "Iso-value contour lines extracted from a simplex noise field via marching squares."
    override val algorithmNotes =
        "A 2D simplex noise field is sampled on a regular grid. For each contour level, " +
        "marching squares identifies cells that straddle the threshold and linearly " +
        "interpolates the crossing point along each cell edge. Connected segments form " +
        "smooth contour polylines."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema = listOf(
        Parameter.NumberParam("Levels", "levels", ParamGroup.COMPOSITION, "Number of elevation bands — each filled with a palette color", 3f, 24f, 1f, 10f),
        Parameter.NumberParam("Scale", "scale", ParamGroup.COMPOSITION, null, 0.5f, 8f, 0.25f, 2.5f),
        Parameter.NumberParam("Octaves", "octaves", ParamGroup.COMPOSITION, null, 1f, 8f, 1f, 5f),
        Parameter.SelectParam("Field Type", "fieldType", ParamGroup.GEOMETRY, "fbm: smooth rolling hills | ridged: sharp mountain ridges | turbulence: plasma/fire topology", listOf("fbm", "ridged", "turbulence"), "fbm"),
        Parameter.NumberParam("Cell Size", "cellSize", ParamGroup.GEOMETRY, "Marching-squares grid resolution — smaller = smoother band edges", 2f, 12f, 1f, 4f),
        Parameter.BooleanParam("Show Lines", "showLines", ParamGroup.COLOR, "Draw thin contour lines on band boundaries", true),
        Parameter.NumberParam("Line Width", "lineWidth", ParamGroup.COLOR, null, 0.25f, 3f, 0.25f, 0.5f),
        Parameter.SelectParam("Line Color", "lineColor", ParamGroup.COLOR, "dark: near-black lines | light: near-white lines | palette: each contour matches its band color", listOf("dark", "light", "palette"), "dark"),
        Parameter.NumberParam("Fill Opacity", "fillOpacity", ParamGroup.COLOR, "Opacity of the filled elevation bands — reduce for a ghostly overlay effect", 0.1f, 1f, 0.05f, 1.0f),
        Parameter.SelectParam("Anim Mode", "animMode", ParamGroup.FLOW_MOTION, "drift: field translates over time, bands flow like a slow liquid", listOf("drift", "none"), "drift"),
        Parameter.NumberParam("Speed", "speed", ParamGroup.FLOW_MOTION, null, 0.05f, 2f, 0.05f, 0.2f)
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "levels" to 10f,
        "scale" to 2.5f,
        "octaves" to 5f,
        "fieldType" to "fbm",
        "cellSize" to 4f,
        "showLines" to true,
        "lineWidth" to 0.5f,
        "lineColor" to "dark",
        "fillOpacity" to 1.0f,
        "animMode" to "drift",
        "speed" to 0.2f
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
        val levels = (params["levels"] as? Number)?.toInt() ?: 10
        val noiseScale = (params["scale"] as? Number)?.toFloat() ?: 2.5f
        val lineWidth = (params["lineWidth"] as? Number)?.toFloat() ?: 0.5f
        val smooth = true
        val animMode = (params["animMode"] as? String) ?: "drift"
        val speed = (params["speed"] as? Number)?.toFloat() ?: 0.2f
        val driftOffset = if (animMode == "drift") time * speed else 0f

        val noise = SimplexNoise(seed)
        val paletteColors = palette.colorInts()

        canvas.drawColor(Color.BLACK)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = lineWidth
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        // Build noise field
        val resolution = when (quality) {
            Quality.DRAFT -> 3f
            Quality.BALANCED -> 2f
            Quality.ULTRA -> 1f
        }
        val cols = (w / resolution).toInt() + 1
        val rows = (h / resolution).toInt() + 1
        val field = FloatArray(cols * rows)

        for (iy in 0 until rows) {
            for (ix in 0 until cols) {
                val nx = ix.toFloat() / cols * noiseScale + driftOffset
                val ny = iy.toFloat() / rows * noiseScale + driftOffset
                field[iy * cols + ix] = (noise.fbm(nx, ny, 4) + 1f) * 0.5f
            }
        }

        // Marching squares for each contour level
        for (level in 0 until levels) {
            val threshold = (level + 1).toFloat() / (levels + 1)
            paint.color = paletteColors[level % paletteColors.size]

            val path = Path()

            for (iy in 0 until rows - 1) {
                for (ix in 0 until cols - 1) {
                    val v0 = field[iy * cols + ix]
                    val v1 = field[iy * cols + ix + 1]
                    val v2 = field[(iy + 1) * cols + ix + 1]
                    val v3 = field[(iy + 1) * cols + ix]

                    val b0 = if (v0 >= threshold) 1 else 0
                    val b1 = if (v1 >= threshold) 1 else 0
                    val b2 = if (v2 >= threshold) 1 else 0
                    val b3 = if (v3 >= threshold) 1 else 0
                    val caseIndex = b0 or (b1 shl 1) or (b2 shl 2) or (b3 shl 3)

                    if (caseIndex == 0 || caseIndex == 15) continue

                    val x0 = ix * resolution
                    val y0 = iy * resolution
                    val x1 = (ix + 1) * resolution
                    val y1 = (iy + 1) * resolution

                    fun lerp(va: Float, vb: Float): Float {
                        return if (smooth && va != vb) ((threshold - va) / (vb - va)).coerceIn(0f, 1f) else 0.5f
                    }

                    // Edge midpoints (or interpolated)
                    val topT = lerp(v0, v1)
                    val topX = x0 + (x1 - x0) * topT
                    val topY = y0

                    val rightT = lerp(v1, v2)
                    val rightX = x1
                    val rightY = y0 + (y1 - y0) * rightT

                    val bottomT = lerp(v3, v2)
                    val bottomX = x0 + (x1 - x0) * bottomT
                    val bottomY = y1

                    val leftT = lerp(v0, v3)
                    val leftX = x0
                    val leftY = y0 + (y1 - y0) * leftT

                    fun drawSegment(ax: Float, ay: Float, bx: Float, by: Float) {
                        path.moveTo(ax, ay)
                        path.lineTo(bx, by)
                    }

                    when (caseIndex) {
                        1, 14 -> drawSegment(leftX, leftY, topX, topY)
                        2, 13 -> drawSegment(topX, topY, rightX, rightY)
                        3, 12 -> drawSegment(leftX, leftY, rightX, rightY)
                        4, 11 -> drawSegment(rightX, rightY, bottomX, bottomY)
                        5 -> {
                            drawSegment(leftX, leftY, topX, topY)
                            drawSegment(rightX, rightY, bottomX, bottomY)
                        }
                        6, 9 -> drawSegment(topX, topY, bottomX, bottomY)
                        7, 8 -> drawSegment(leftX, leftY, bottomX, bottomY)
                        10 -> {
                            drawSegment(topX, topY, rightX, rightY)
                            drawSegment(leftX, leftY, bottomX, bottomY)
                        }
                    }
                }
            }

            canvas.drawPath(path, paint)
        }
    }

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float {
        val levels = (params["levels"] as? Number)?.toInt() ?: 12
        return (levels / 30f).coerceIn(0.2f, 1f)
    }
}
