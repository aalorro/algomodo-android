package com.artmondo.algomodo.generators.image

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.artmondo.algomodo.core.rng.SeededRNG
import com.artmondo.algomodo.data.palettes.Palette
import com.artmondo.algomodo.generators.Generator
import com.artmondo.algomodo.generators.ParamGroup
import com.artmondo.algomodo.generators.Parameter
import com.artmondo.algomodo.generators.Quality
import com.artmondo.algomodo.rendering.SvgPath
import kotlin.math.PI
import kotlin.math.sin

/**
 * Linocut print effect generator.
 *
 * Converts a source image into a high-contrast linocut-style print with
 * configurable hatching patterns for mid-tones.
 */
class LinoCutGenerator : Generator {

    override val id = "lino-cut"
    override val family = "image"
    override val styleName = "Linocut Print"
    override val definition =
        "High-contrast linocut-style rendering with hatching for mid-tone regions."
    override val algorithmNotes =
        "The source image is converted to grayscale. Pixels above the threshold are rendered " +
        "as white (paper). Pixels below half the threshold are solid black (ink). Mid-tone " +
        "regions between these values are rendered with hatching lines whose style is chosen " +
        "by the style parameter: horizontal parallel lines, cross-hatching, or organic " +
        "wood-grain curves."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema = listOf(
        Parameter.NumberParam("Threshold", "threshold", ParamGroup.TEXTURE, "Brightness threshold for ink — lower prints more area as solid ink", 30f, 220f, 5f, 128f),
        Parameter.NumberParam("Line Width", "lineWidth", ParamGroup.TEXTURE, "Hatching line width in pixels", 0.5f, 6f, 0.25f, 2f),
        Parameter.NumberParam("Line Spacing", "lineSpacing", ParamGroup.GEOMETRY, "Gap between hatching lines in pixels", 2f, 16f, 1f, 6f),
        Parameter.SelectParam("Style", "style", ParamGroup.TEXTURE, "Hatching pattern for mid-tones — horizontal: parallel lines | cross-hatch: crossed lines | wood-grain: organic wavy curves | stipple: dot shading", listOf("horizontal", "cross-hatch", "wood-grain", "stipple"), "horizontal"),
        Parameter.NumberParam("Contrast", "contrast", ParamGroup.TEXTURE, "Boost contrast before thresholding", 0.5f, 3f, 0.1f, 1f),
        Parameter.SelectParam("Ink Color", "inkColor", ParamGroup.COLOR, "black: traditional | palette-first: use first palette colour | palette-dark: use darkest palette colour", listOf("black", "palette-first", "palette-dark"), "black"),
        Parameter.SelectParam("Paper Color", "paperColor", ParamGroup.COLOR, "white: clean paper | cream: warm tinted | palette-last: use last palette colour", listOf("white", "cream", "palette-last"), "white"),
        Parameter.NumberParam("Speed", "speed", ParamGroup.FLOW_MOTION, "Animation speed — threshold sweeps over time", 0f, 2f, 0.1f, 0f)
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "threshold" to 128f,
        "lineWidth" to 2f,
        "lineSpacing" to 6f,
        "style" to "horizontal",
        "contrast" to 1f,
        "inkColor" to "black",
        "paperColor" to "white",
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
        val speed = (params["speed"] as? Number)?.toFloat() ?: 0f
        val baseThreshold = (params["threshold"] as? Number)?.toFloat() ?: 128f
        val timeShift = if (speed > 0f && time > 0f) sin(time * speed * 2f) * 30f else 0f
        val threshold = (baseThreshold + timeShift).coerceIn(30f, 220f)
        val lineWidth = (params["lineWidth"] as? Number)?.toFloat() ?: 2f
        val style = (params["style"] as? String) ?: "horizontal"

        val source = params["_sourceImage"] as? Bitmap
        if (source == null) {
            drawPlaceholder(canvas, bitmap)
            return
        }

        val rng = SeededRNG(seed)
        val paletteColors = palette.colorInts()
        val inkColor = paletteColors[0]
        val paperColor = paletteColors[paletteColors.size - 1]

        val scaled = Bitmap.createScaledBitmap(source, w, h, true)
        val srcPixels = IntArray(w * h)
        scaled.getPixels(srcPixels, 0, w, 0, 0, w, h)

        // Convert to grayscale luminance
        val gray = FloatArray(w * h) { i ->
            val p = srcPixels[i]
            0.299f * Color.red(p) + 0.587f * Color.green(p) + 0.114f * Color.blue(p)
        }

        // Base layer: threshold to black or white
        val outPixels = IntArray(w * h)
        val halfThreshold = threshold * 0.5f

        for (i in 0 until w * h) {
            outPixels[i] = if (gray[i] >= threshold) paperColor else inkColor
        }

        bitmap.setPixels(outPixels, 0, w, 0, 0, w, h)

        // Draw hatching for mid-tones
        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = inkColor
            strokeWidth = lineWidth
            this.style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }

        val spacing = (lineWidth * 3f).coerceAtLeast(3f).toInt()

        when (style) {
            "horizontal" -> {
                for (y in 0 until h step spacing) {
                    var inMidtone = false
                    var startX = 0
                    for (x in 0 until w) {
                        val luma = gray[y * w + x]
                        val isMid = luma >= halfThreshold && luma < threshold
                        if (isMid && !inMidtone) {
                            startX = x
                            inMidtone = true
                        } else if (!isMid && inMidtone) {
                            canvas.drawLine(startX.toFloat(), y.toFloat(), x.toFloat(), y.toFloat(), linePaint)
                            inMidtone = false
                        }
                    }
                    if (inMidtone) {
                        canvas.drawLine(startX.toFloat(), y.toFloat(), w.toFloat(), y.toFloat(), linePaint)
                    }
                }
            }
            "cross-hatch" -> {
                // Horizontal pass
                for (y in 0 until h step spacing) {
                    for (x in 0 until w) {
                        val luma = gray[y * w + x]
                        if (luma >= halfThreshold && luma < threshold) {
                            outPixels[y * w + x] = inkColor
                        }
                    }
                }
                // Vertical pass
                for (x in 0 until w step spacing) {
                    for (y in 0 until h) {
                        val luma = gray[y * w + x]
                        if (luma >= halfThreshold && luma < threshold) {
                            outPixels[y * w + x] = inkColor
                        }
                    }
                }
                bitmap.setPixels(outPixels, 0, w, 0, 0, w, h)
            }
            "wood-grain" -> {
                for (y in 0 until h step spacing) {
                    val path = android.graphics.Path()
                    var first = true
                    for (x in 0 until w) {
                        val luma = gray[y * w + x]
                        if (luma >= halfThreshold && luma < threshold) {
                            val waveY = y + sin(x * 0.05f + y * 0.02f) * lineWidth * 2f
                            if (first) {
                                path.moveTo(x.toFloat(), waveY)
                                first = false
                            } else {
                                path.lineTo(x.toFloat(), waveY)
                            }
                        } else {
                            first = true
                        }
                    }
                    canvas.drawPath(path, linePaint)
                }
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

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float = 0.5f
}
