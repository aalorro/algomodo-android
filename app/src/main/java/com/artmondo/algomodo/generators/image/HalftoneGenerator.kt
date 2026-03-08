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
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Halftone pattern generator from a source image.
 *
 * Converts the source image into a halftone pattern where dot size
 * is proportional to local brightness.
 */
class HalftoneGenerator : Generator {

    override val id = "halftone"
    override val family = "image"
    override val styleName = "Halftone"
    override val definition =
        "Convert a source image to a halftone dot pattern based on local brightness."
    override val algorithmNotes =
        "The source image is sampled on a rotated grid. At each sample point, the average " +
        "luminance of the surrounding cell determines the dot size: darker areas get larger " +
        "dots. Shapes include circles, squares, and parallel lines. In 'colored' mode, " +
        "separate CMYK-like halftone layers are composited."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema = listOf(
        Parameter.NumberParam("Dot Size", "dotSize", ParamGroup.GEOMETRY, "Maximum halftone element radius in pixels", 2f, 24f, 0.5f, 8f),
        Parameter.NumberParam("Angle", "angle", ParamGroup.GEOMETRY, "Grid rotation in degrees", 0f, 90f, 1f, 45f),
        Parameter.SelectParam("Shape", "shape", ParamGroup.TEXTURE, "Halftone element shape — circle: round dots | square: square blocks | line: parallel lines | diamond: rotated squares | cross: plus signs", listOf("circle", "square", "line", "diamond", "cross"), "circle"),
        Parameter.BooleanParam("Colored", "colored", ParamGroup.COLOR, "Use source image colours per dot", true),
        Parameter.SelectParam("Color Mode", "colorMode", ParamGroup.COLOR, "source: original colours | cmyk: four-layer CMYK separation | palette: map through palette", listOf("source", "cmyk", "palette"), "source"),
        Parameter.SelectParam("Background", "background", ParamGroup.COLOR, null, listOf("white", "black", "cream"), "white"),
        Parameter.BooleanParam("Invert", "invert", ParamGroup.COLOR, "Invert — large dots in bright areas instead of dark", false),
        Parameter.NumberParam("Speed", "speed", ParamGroup.FLOW_MOTION, "Animation speed — dot sizes pulse over time", 0f, 2f, 0.1f, 0f)
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "dotSize" to 8f,
        "angle" to 45f,
        "shape" to "circle",
        "colored" to true,
        "colorMode" to "source",
        "background" to "white",
        "invert" to false,
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
        val dotSize = (params["dotSize"] as? Number)?.toFloat() ?: 8f
        val speed = (params["speed"] as? Number)?.toFloat() ?: 0f
        val baseAngle = (params["angle"] as? Number)?.toFloat() ?: 45f
        val angleDeg = if (speed > 0f && time > 0f) baseAngle + time * speed * 15f else baseAngle
        val shape = (params["shape"] as? String) ?: "circle"
        val colored = params["colored"] as? Boolean ?: true

        val source = params["_sourceImage"] as? Bitmap
        if (source == null) {
            drawPlaceholder(canvas, bitmap)
            return
        }

        val scaled = Bitmap.createScaledBitmap(source, w.toInt(), h.toInt(), true)

        canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }

        val angleRad = angleDeg * PI.toFloat() / 180f
        val cosA = cos(angleRad)
        val sinA = sin(angleRad)
        val gridStep = dotSize * 1.2f
        val diagonal = sqrt(w * w + h * h)
        val halfDiag = diagonal / 2f
        val cx = w / 2f
        val cy = h / 2f

        var gy = -halfDiag
        while (gy <= halfDiag) {
            var gx = -halfDiag
            while (gx <= halfDiag) {
                val px = cx + gx * cosA - gy * sinA
                val py = cy + gx * sinA + gy * cosA

                // Sample source at this point
                val sx = px.toInt().coerceIn(0, scaled.width - 1)
                val sy = py.toInt().coerceIn(0, scaled.height - 1)

                if (px >= -dotSize && px <= w + dotSize && py >= -dotSize && py <= h + dotSize) {
                    val pixel = scaled.getPixel(sx, sy)
                    val r = Color.red(pixel)
                    val g = Color.green(pixel)
                    val b = Color.blue(pixel)
                    val luma = (0.299f * r + 0.587f * g + 0.114f * b) / 255f

                    // Invert: darker = bigger dot
                    val dotR = dotSize * (1f - luma) * 0.5f

                    if (dotR > 0.2f) {
                        if (colored) {
                            paint.color = Color.rgb(r, g, b)
                        } else {
                            val gray = (luma * 255).toInt().coerceIn(0, 255)
                            paint.color = Color.rgb(0, 0, 0)
                        }

                        when (shape) {
                            "square" -> canvas.drawRect(px - dotR, py - dotR, px + dotR, py + dotR, paint)
                            "line" -> {
                                val lineLen = gridStep * 0.9f
                                paint.strokeWidth = dotR
                                paint.style = Paint.Style.STROKE
                                canvas.drawLine(px - lineLen / 2f, py, px + lineLen / 2f, py, paint)
                                paint.style = Paint.Style.FILL
                            }
                            else -> canvas.drawCircle(px, py, dotR, paint)
                        }
                    }
                }

                gx += gridStep
            }
            gy += gridStep
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
        val dotSize = (params["dotSize"] as? Number)?.toFloat() ?: 8f
        return (8f / dotSize).coerceIn(0.2f, 1f)
    }
}
