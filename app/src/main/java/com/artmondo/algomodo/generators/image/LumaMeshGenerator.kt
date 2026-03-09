package com.artmondo.algomodo.generators.image

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
import com.artmondo.algomodo.rendering.SvgPath

/**
 * Luminance-based mesh generator.
 *
 * Creates a regular grid mesh where each vertex is displaced by the local
 * brightness of the source image, producing a 3D-like terrain effect.
 */
class LumaMeshGenerator : Generator {

    override val id = "luma-mesh"
    override val family = "image"
    override val styleName = "Luminance Mesh"
    override val definition =
        "Grid mesh displaced by source image brightness to create a terrain effect."
    override val algorithmNotes =
        "A regular grid covers the canvas. At each vertex, the corresponding pixel in the " +
        "source image is sampled for luminance. The vertex is then displaced vertically by " +
        "luminance * displacement. Grid quads are drawn either as outlines (wireframe) or " +
        "as filled polygons coloured by the palette."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema = listOf(
        Parameter.NumberParam("Grid Size", "gridSize", ParamGroup.GEOMETRY, "Grid cell size in pixels — smaller gives more detail", 3f, 40f, 1f, 15f),
        Parameter.NumberParam("Displacement", "displacement", ParamGroup.GEOMETRY, "Maximum vertical displacement of vertices based on luminance", 2f, 80f, 2f, 20f),
        Parameter.NumberParam("Line Width", "lineWidth", ParamGroup.TEXTURE, "Stroke width for wireframe lines", 0.3f, 4f, 0.1f, 1f),
        Parameter.BooleanParam("Show Fill", "showFill", ParamGroup.COLOR, "Fill mesh faces with interpolated colour", true),
        Parameter.BooleanParam("Show Wireframe", "showWireframe", ParamGroup.TEXTURE, "Draw wireframe grid lines over faces", true),
        Parameter.SelectParam("Color Mode", "colorMode", ParamGroup.COLOR, "source: original image colours | palette: map luminance through palette | grayscale: monochrome height map", listOf("source", "palette", "grayscale"), "palette"),
        Parameter.NumberParam("Perspective", "perspective", ParamGroup.GEOMETRY, "Isometric tilt angle in degrees — 0 = top-down, 45 = angled", 0f, 60f, 5f, 0f),
        Parameter.NumberParam("Speed", "speed", ParamGroup.FLOW_MOTION, "Animation speed — mesh displacement oscillates over time", 0f, 2f, 0.1f, 0f)
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "gridSize" to 15f,
        "displacement" to 20f,
        "lineWidth" to 1f,
        "showFill" to true,
        "showWireframe" to true,
        "colorMode" to "palette",
        "perspective" to 0f,
        "speed" to 0f
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
        val gridSize = (params["gridSize"] as? Number)?.toFloat() ?: 15f
        val speed = (params["speed"] as? Number)?.toFloat() ?: 0f
        val baseDisplacement = (params["displacement"] as? Number)?.toFloat() ?: 20f
        val displacement = if (speed > 0f && time > 0f) baseDisplacement * (1f + kotlin.math.sin(time * speed * 2f) * 0.3f) else baseDisplacement
        val lineWidth = (params["lineWidth"] as? Number)?.toFloat() ?: 1f
        val showFill = params["showFill"] as? Boolean ?: true

        val source = params["_sourceImage"] as? Bitmap
        if (source == null) {
            drawPlaceholder(canvas, bitmap)
            return
        }

        val scaled = if (source.width == w.toInt() && source.height == h.toInt()) source else Bitmap.createScaledBitmap(source, w.toInt(), h.toInt(), true)

        canvas.drawColor(Color.BLACK)

        val cols = (w / gridSize).toInt() + 1
        val rows = (h / gridSize).toInt() + 1

        // Sample luminance at grid points
        val lumaGrid = Array(rows) { row ->
            FloatArray(cols) { col ->
                val sx = (col * gridSize).toInt().coerceIn(0, scaled.width - 1)
                val sy = (row * gridSize).toInt().coerceIn(0, scaled.height - 1)
                val pixel = scaled.getPixel(sx, sy)
                (0.299f * Color.red(pixel) + 0.587f * Color.green(pixel) + 0.114f * Color.blue(pixel)) / 255f
            }
        }

        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = lineWidth
        }

        // Draw grid quads from back to front (top to bottom gives basic depth)
        for (row in 0 until rows - 1) {
            for (col in 0 until cols - 1) {
                val x0 = col * gridSize
                val x1 = (col + 1) * gridSize
                val y00 = row * gridSize - lumaGrid[row][col] * displacement
                val y10 = row * gridSize - lumaGrid[row][col + 1] * displacement
                val y01 = (row + 1) * gridSize - lumaGrid[row + 1][col] * displacement
                val y11 = (row + 1) * gridSize - lumaGrid[row + 1][col + 1] * displacement

                val avgLuma = (lumaGrid[row][col] + lumaGrid[row][col + 1] +
                        lumaGrid[row + 1][col] + lumaGrid[row + 1][col + 1]) / 4f

                val path = Path()
                path.moveTo(x0, y00)
                path.lineTo(x1, y10)
                path.lineTo(x1, y11)
                path.lineTo(x0, y01)
                path.close()

                if (showFill) {
                    fillPaint.color = palette.lerpColor(avgLuma)
                    fillPaint.alpha = 200
                    canvas.drawPath(path, fillPaint)
                }

                strokePaint.color = palette.lerpColor(avgLuma)
                strokePaint.alpha = 100
                canvas.drawPath(path, strokePaint)
            }
        }

        if (scaled !== source) scaled.recycle()
    }

    private fun drawPlaceholder(canvas: Canvas, bitmap: Bitmap) {
        canvas.drawColor(Color.DKGRAY)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textSize = 24f; textAlign = Paint.Align.CENTER
        }
        canvas.drawText("No source image", bitmap.width / 2f, bitmap.height / 2f, paint)
    }

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float {
        val gridSize = (params["gridSize"] as? Number)?.toFloat() ?: 15f
        return (15f / gridSize).coerceIn(0.2f, 1f)
    }
}
