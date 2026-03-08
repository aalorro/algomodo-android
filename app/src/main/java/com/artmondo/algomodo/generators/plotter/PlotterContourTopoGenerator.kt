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
 * Topographic map contour generator.
 *
 * Produces a filled topographic map with contour lines derived from simplex
 * noise. Optionally fills the bands between contours with palette colours and
 * labels contour elevations.
 */
class PlotterContourTopoGenerator : Generator {

    override val id = "plotter-contour-topo"
    override val family = "plotter"
    override val styleName = "Topographic Contours"
    override val definition =
        "Filled topographic map with contour lines from a simplex noise elevation field."
    override val algorithmNotes =
        "A simplex noise field generates an 'elevation' map. The canvas is first filled " +
        "pixel-by-pixel with palette colours mapped to elevation bands. Contour lines are " +
        "then traced at each level boundary using marching squares. Optional labels show " +
        "the elevation value along each contour."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema = listOf(
        Parameter.NumberParam("Contour Count", "contourCount", ParamGroup.COMPOSITION, "Number of evenly-spaced iso-level lines", 4f, 30f, 1f, 14f),
        Parameter.NumberParam("Terrain Scale", "noiseScale", ParamGroup.COMPOSITION, "Spatial scale of the height field", 0.5f, 6f, 0.1f, 2.5f),
        Parameter.NumberParam("Octaves", "octaves", ParamGroup.COMPOSITION, null, 1f, 7f, 1f, 5f),
        Parameter.NumberParam("Cell Size", "cellSize", ParamGroup.GEOMETRY, "Marching-squares grid resolution — smaller = finer lines, slower", 2f, 14f, 1f, 4f),
        Parameter.NumberParam("Line Width", "lineWidth", ParamGroup.GEOMETRY, null, 0.25f, 3f, 0.25f, 0.7f),
        Parameter.NumberParam("Wobble", "wobble", ParamGroup.TEXTURE, "Hand-drawn jitter applied to contour endpoints", 0f, 4f, 0.25f, 0.4f),
        Parameter.SelectParam("Color Mode", "colorMode", ParamGroup.COLOR, "elevation-palette: color ramps with altitude | alternating: toggles palette ends", listOf("elevation-palette", "alternating", "monochrome"), "elevation-palette"),
        Parameter.SelectParam("Background", "background", ParamGroup.COLOR, null, listOf("white", "cream", "dark"), "cream"),
        Parameter.NumberParam("Anim Speed", "animSpeed", ParamGroup.FLOW_MOTION, "Speed at which the terrain drifts over time (0 = static)", 0f, 1f, 0.05f, 0.1f)
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "contourCount" to 14f,
        "noiseScale" to 2.5f,
        "octaves" to 5f,
        "cellSize" to 4f,
        "lineWidth" to 0.7f,
        "wobble" to 0.4f,
        "colorMode" to "elevation-palette",
        "background" to "cream",
        "animSpeed" to 0.1f
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
        val levels = (params["contourCount"] as? Number)?.toInt() ?: 14
        val noiseScale = (params["noiseScale"] as? Number)?.toFloat() ?: 2.5f
        val fillBetween = true
        val labelContours = false

        val animSpeed = (params["animSpeed"] as? Number)?.toFloat() ?: 0.1f
        val timeOff = time * animSpeed

        val noise = SimplexNoise(seed)
        val paletteColors = palette.colorInts()

        // Build elevation field
        val step = when (quality) {
            Quality.DRAFT -> 2
            Quality.BALANCED -> 1
            Quality.ULTRA -> 1
        }
        val cols = w / step
        val rows = h / step
        val field = FloatArray(cols * rows)

        for (iy in 0 until rows) {
            for (ix in 0 until cols) {
                val nx = ix.toFloat() / cols * noiseScale
                val ny = iy.toFloat() / rows * noiseScale
                field[iy * cols + ix] = (noise.fbm(nx + timeOff, ny, 5) + 1f) * 0.5f
            }
        }

        // Fill between contours
        if (fillBetween) {
            val pixels = IntArray(w * h)
            for (py in 0 until h) {
                val fy = (py / step).coerceAtMost(rows - 1)
                for (px in 0 until w) {
                    val fx = (px / step).coerceAtMost(cols - 1)
                    val elev = field[fy * cols + fx]
                    val band = (elev * levels).toInt().coerceIn(0, levels - 1)
                    val t = band.toFloat() / (levels - 1).coerceAtLeast(1)
                    pixels[py * w + px] = palette.lerpColor(t)
                }
            }
            bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
            canvas.drawBitmap(bitmap, 0f, 0f, null)
        } else {
            canvas.drawColor(Color.BLACK)
        }

        // Draw contour lines
        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.5f
            color = if (fillBetween) Color.argb(180, 0, 0, 0) else Color.WHITE
            strokeCap = Paint.Cap.ROUND
        }

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 10f
            color = if (fillBetween) Color.argb(200, 0, 0, 0) else Color.WHITE
        }

        for (level in 0 until levels) {
            val threshold = (level + 1).toFloat() / (levels + 1)
            if (!fillBetween) {
                linePaint.color = paletteColors[level % paletteColors.size]
            }

            val path = Path()
            var labelPlaced = false

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

                    val x0 = (ix * step).toFloat()
                    val y0 = (iy * step).toFloat()
                    val x1 = ((ix + 1) * step).toFloat()
                    val y1 = ((iy + 1) * step).toFloat()

                    fun lerp(va: Float, vb: Float): Float {
                        return if (va != vb) ((threshold - va) / (vb - va)).coerceIn(0f, 1f) else 0.5f
                    }

                    val topX = x0 + (x1 - x0) * lerp(v0, v1)
                    val rightY = y0 + (y1 - y0) * lerp(v1, v2)
                    val bottomX = x0 + (x1 - x0) * lerp(v3, v2)
                    val leftY = y0 + (y1 - y0) * lerp(v0, v3)

                    fun seg(ax: Float, ay: Float, bx: Float, by: Float) {
                        path.moveTo(ax, ay)
                        path.lineTo(bx, by)

                        // Place label near the middle of the first segment
                        if (labelContours && !labelPlaced && ix > cols / 4 && ix < cols * 3 / 4) {
                            val labelText = "${(threshold * 100).toInt()}m"
                            canvas.drawText(labelText, (ax + bx) / 2f, (ay + by) / 2f - 3f, textPaint)
                            labelPlaced = true
                        }
                    }

                    when (caseIndex) {
                        1, 14 -> seg(x0, leftY, topX, y0)
                        2, 13 -> seg(topX, y0, x1, rightY)
                        3, 12 -> seg(x0, leftY, x1, rightY)
                        4, 11 -> seg(x1, rightY, bottomX, y1)
                        5 -> { seg(x0, leftY, topX, y0); seg(x1, rightY, bottomX, y1) }
                        6, 9 -> seg(topX, y0, bottomX, y1)
                        7, 8 -> seg(x0, leftY, bottomX, y1)
                        10 -> { seg(topX, y0, x1, rightY); seg(x0, leftY, bottomX, y1) }
                    }
                }
            }

            canvas.drawPath(path, linePaint)
        }
    }

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float {
        val levels = (params["contourCount"] as? Number)?.toInt() ?: 14
        return (levels / 30f).coerceIn(0.3f, 1f)
    }
}
