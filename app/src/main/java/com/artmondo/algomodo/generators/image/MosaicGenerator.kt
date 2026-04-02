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
 * Mosaic tile generator.
 *
 * Divides a source image into tiles of configurable shape (square, circle,
 * hexagon) and renders each tile with the average colour of the underlying
 * image region, separated by grout lines.
 */
class MosaicGenerator : Generator {

    override val id = "mosaic"
    override val family = "image"
    override val styleName = "Mosaic"
    override val definition =
        "Mosaic tiles that average source image colours in each tile region."
    override val algorithmNotes =
        "The source image is divided into a grid. For each tile, the average RGB colour " +
        "of all pixels in the region is computed. The tile is then drawn with that colour " +
        "as a square, circle, or hexagon, with a configurable gap (grout) between tiles."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema = listOf(
        Parameter.NumberParam("Tile Size", "tileSize", ParamGroup.GEOMETRY, "Tile size in pixels — smaller gives more detail", 3f, 50f, 1f, 15f),
        Parameter.NumberParam("Gap", "gap", ParamGroup.GEOMETRY, "Grout width between tiles in pixels", 0f, 6f, 0.5f, 1f),
        Parameter.SelectParam("Shape", "shape", ParamGroup.TEXTURE, "Tile shape — square: rectangular grid | circle: round tiles | hexagon: honeycomb | diamond: rotated squares | triangle: alternating triangles", listOf("square", "circle", "hexagon", "diamond", "triangle"), "square"),
        Parameter.SelectParam("Grout Color", "groutColor", ParamGroup.COLOR, "black: dark grout | white: light grout | dark-gray: neutral | palette-dark: from palette", listOf("black", "white", "dark-gray", "palette-dark"), "dark-gray"),
        Parameter.NumberParam("Jitter", "jitter", ParamGroup.TEXTURE, "Random offset for tile positions — 0 = perfectly aligned", 0f, 1f, 0.1f, 0f),
        Parameter.BooleanParam("Outline", "outline", ParamGroup.TEXTURE, "Draw a thin outline around each tile", false),
        Parameter.SelectParam("Color Mode", "colorMode", ParamGroup.COLOR, "source: original image colour | palette: quantise to palette | average: single averaged colour per tile", listOf("source", "palette", "average"), "source"),
        Parameter.NumberParam("Speed", "speed", ParamGroup.FLOW_MOTION, "Animation speed — tile colours shift over time", 0f, 2f, 0.1f, 0f)
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "tileSize" to 15f,
        "gap" to 1f,
        "shape" to "square",
        "groutColor" to "dark-gray",
        "jitter" to 0f,
        "outline" to false,
        "colorMode" to "source",
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
        val w = bitmap.width
        val h = bitmap.height
        val tileSize = (params["tileSize"] as? Number)?.toInt() ?: 15
        val gap = (params["gap"] as? Number)?.toFloat() ?: 1f
        val shape = (params["shape"] as? String) ?: "square"
        val groutColorMode = (params["groutColor"] as? String) ?: "dark-gray"
        val jitter = (params["jitter"] as? Number)?.toFloat() ?: 0f
        val outline = params["outline"] as? Boolean ?: false
        val colorMode = (params["colorMode"] as? String) ?: "source"
        val speed = (params["speed"] as? Number)?.toFloat() ?: 0f
        val sampleShift = if (speed > 0f && time > 0f) (kotlin.math.sin(time * speed * 2f) * tileSize * 0.5f).toInt() else 0

        val source = params["_sourceImage"] as? Bitmap
        if (source == null) {
            drawPlaceholder(canvas, bitmap)
            return
        }

        val scaled = if (source.width == w && source.height == h) source else Bitmap.createScaledBitmap(source, w, h, true)
        val srcPixels = IntArray(w * h)
        scaled.getPixels(srcPixels, 0, w, 0, 0, w, h)

        val paletteColors = palette.colorInts()
        val groutColor = when (groutColorMode) {
            "black" -> Color.BLACK
            "white" -> Color.WHITE
            "palette-dark" -> paletteColors.minByOrNull {
                0.299f * Color.red(it) + 0.587f * Color.green(it) + 0.114f * Color.blue(it)
            } ?: Color.DKGRAY
            else -> Color.DKGRAY
        }
        canvas.drawColor(groutColor)

        val rng = com.artmondo.algomodo.core.rng.SeededRNG(seed)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
        val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1f
            color = groutColor
        }

        if (shape == "hexagon") {
            drawHexMosaic(canvas, paint, srcPixels, w, h, tileSize, gap)
        } else {
            val cols = w / tileSize
            val rows = h / tileSize

            for (row in 0 until rows) {
                for (col in 0 until cols) {
                    // Average colour in tile
                    var rSum = 0L; var gSum = 0L; var bSum = 0L; var count = 0

                    for (dy in 0 until tileSize) {
                        for (dx in 0 until tileSize) {
                            val px = ((col * tileSize + dx + sampleShift) % w + w) % w
                            val py = row * tileSize + dy
                            if (px < w && py < h) {
                                val pixel = srcPixels[py * w + px]
                                rSum += Color.red(pixel)
                                gSum += Color.green(pixel)
                                bSum += Color.blue(pixel)
                                count++
                            }
                        }
                    }

                    if (count == 0) continue
                    val avgR = (rSum / count).toInt()
                    val avgG = (gSum / count).toInt()
                    val avgB = (bSum / count).toInt()
                    val avgColor = Color.rgb(avgR, avgG, avgB)

                    // Apply colorMode
                    paint.color = when (colorMode) {
                        "palette" -> {
                            val luma = (0.299f * avgR + 0.587f * avgG + 0.114f * avgB) / 255f
                            palette.lerpColor(luma)
                        }
                        "average" -> avgColor
                        else -> avgColor // "source"
                    }

                    // Apply jitter offset
                    val jitterX = if (jitter > 0f) (rng.random() - 0.5f) * tileSize * jitter else 0f
                    val jitterY = if (jitter > 0f) (rng.random() - 0.5f) * tileSize * jitter else 0f
                    val cx = col * tileSize + tileSize / 2f + jitterX
                    val cy = row * tileSize + tileSize / 2f + jitterY
                    val halfGap = gap / 2f

                    when (shape) {
                        "circle" -> {
                            val radius = tileSize / 2f - halfGap
                            canvas.drawCircle(cx, cy, radius, paint)
                            if (outline) canvas.drawCircle(cx, cy, radius, outlinePaint)
                        }
                        "diamond" -> {
                            val r = tileSize / 2f - halfGap
                            val path = Path()
                            path.moveTo(cx, cy - r)
                            path.lineTo(cx + r, cy)
                            path.lineTo(cx, cy + r)
                            path.lineTo(cx - r, cy)
                            path.close()
                            canvas.drawPath(path, paint)
                            if (outline) canvas.drawPath(path, outlinePaint)
                        }
                        "triangle" -> {
                            val r = tileSize / 2f - halfGap
                            val path = Path()
                            val flip = (row + col) % 2 == 0
                            if (flip) {
                                path.moveTo(cx, cy - r)
                                path.lineTo(cx + r, cy + r)
                                path.lineTo(cx - r, cy + r)
                            } else {
                                path.moveTo(cx - r, cy - r)
                                path.lineTo(cx + r, cy - r)
                                path.lineTo(cx, cy + r)
                            }
                            path.close()
                            canvas.drawPath(path, paint)
                            if (outline) canvas.drawPath(path, outlinePaint)
                        }
                        else -> { // square
                            val left = col * tileSize + halfGap + jitterX
                            val top = row * tileSize + halfGap + jitterY
                            val right = (col + 1) * tileSize - halfGap + jitterX
                            val bottom = (row + 1) * tileSize - halfGap + jitterY
                            canvas.drawRect(left, top, right, bottom, paint)
                            if (outline) canvas.drawRect(left, top, right, bottom, outlinePaint)
                        }
                    }
                }
            }
        }

        if (scaled !== source) scaled.recycle()
    }

    private fun drawHexMosaic(canvas: Canvas, paint: Paint, pixels: IntArray,
                               w: Int, h: Int, tileSize: Int, gap: Float) {
        val hexR = tileSize / 2f
        val hexW = hexR * 2f
        val hexH = hexR * sqrt(3f)
        val halfGap = gap / 2f

        var row = 0
        var cy = hexH / 2f
        while (cy < h + hexH) {
            val offset = if (row % 2 == 0) 0f else hexR * 1.5f
            var cx = offset
            while (cx < w + hexW) {
                // Average colour in hex region (approximate with bounding box)
                var rSum = 0L; var gSum = 0L; var bSum = 0L; var count = 0
                val sx = (cx - hexR).toInt().coerceAtLeast(0)
                val sy = (cy - hexH / 2f).toInt().coerceAtLeast(0)
                val ex = (cx + hexR).toInt().coerceAtMost(w - 1)
                val ey = (cy + hexH / 2f).toInt().coerceAtMost(h - 1)

                for (py in sy..ey) {
                    for (px in sx..ex) {
                        val pixel = pixels[py * w + px]
                        rSum += Color.red(pixel)
                        gSum += Color.green(pixel)
                        bSum += Color.blue(pixel)
                        count++
                    }
                }

                if (count > 0) {
                    paint.color = Color.rgb(
                        (rSum / count).toInt(),
                        (gSum / count).toInt(),
                        (bSum / count).toInt()
                    )

                    val r = hexR - halfGap
                    val path = Path()
                    for (i in 0..5) {
                        val angle = PI.toFloat() / 3f * i - PI.toFloat() / 6f
                        val px = cx + r * cos(angle)
                        val py = cy + r * sin(angle)
                        if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
                    }
                    path.close()
                    canvas.drawPath(path, paint)
                }

                cx += hexR * 3f
            }
            cy += hexH / 2f
            row++
        }
    }

    private fun drawPlaceholder(canvas: Canvas, bitmap: Bitmap) {
        canvas.drawColor(Color.DKGRAY)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textSize = 24f; textAlign = Paint.Align.CENTER
        }
        canvas.drawText("No source image", bitmap.width / 2f, bitmap.height / 2f, paint)
    }

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float {
        val tileSize = (params["tileSize"] as? Number)?.toInt() ?: 15
        return (15f / tileSize).coerceIn(0.1f, 1f)
    }
}
