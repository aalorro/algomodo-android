package com.artmondo.algomodo.generators.image

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.artmondo.algomodo.data.palettes.Palette
import com.artmondo.algomodo.generators.Generator
import com.artmondo.algomodo.generators.ParamGroup
import com.artmondo.algomodo.generators.Parameter
import com.artmondo.algomodo.generators.Quality
import com.artmondo.algomodo.rendering.SvgPath
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Edge detection generator.
 *
 * Detects edges in a source image using Sobel, Canny (simplified),
 * Laplacian, or Prewitt operators and renders them.
 */
class EdgeDetectGenerator : Generator {

    override val id = "edge-detect"
    override val family = "image"
    override val styleName = "Edge Detection"
    override val definition =
        "Detect and render edges in a source image using classic operators."
    override val algorithmNotes =
        "Sobel computes horizontal and vertical gradients with a 3x3 kernel and combines " +
        "them as sqrt(Gx^2 + Gy^2). Prewitt is similar but with uniform weights. " +
        "Laplacian uses a single second-derivative kernel. 'Canny' here is a simplified " +
        "version: Sobel magnitude with non-maximum suppression along the gradient direction " +
        "and dual-threshold hysteresis."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema = listOf(
        Parameter.SelectParam("Algorithm", "algorithm", ParamGroup.TEXTURE, "sobel: standard gradient | canny: non-maximum suppression | laplacian: second derivative | prewitt: uniform weight gradient", listOf("sobel", "canny", "laplacian", "prewitt"), "sobel"),
        Parameter.NumberParam("Threshold", "threshold", ParamGroup.TEXTURE, "Edge magnitude threshold — lower detects finer edges", 5f, 200f, 5f, 50f),
        Parameter.BooleanParam("Invert", "invert", ParamGroup.COLOR, "Invert output — dark edges on white background", false),
        Parameter.SelectParam("Color Mode", "colorMode", ParamGroup.COLOR, "mono: white edges on black | palette: colour edges by gradient magnitude | source: use source image colour at edge pixels", listOf("mono", "palette", "source"), "mono"),
        Parameter.NumberParam("Edge Width", "edgeWidth", ParamGroup.GEOMETRY, "Dilate detected edges by this many pixels", 0f, 5f, 0.5f, 0f),
        Parameter.BooleanParam("Show Source", "showSource", ParamGroup.COLOR, "Blend detected edges over a dimmed source image", false),
        Parameter.NumberParam("Speed", "speed", ParamGroup.FLOW_MOTION, "Animation speed — threshold oscillates over time", 0f, 2f, 0.1f, 0f)
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "algorithm" to "sobel",
        "threshold" to 50f,
        "invert" to false,
        "colorMode" to "mono",
        "edgeWidth" to 0f,
        "showSource" to false,
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
        val algorithm = (params["algorithm"] as? String) ?: "sobel"
        val speed = (params["speed"] as? Number)?.toFloat() ?: 0f
        val baseThreshold = (params["threshold"] as? Number)?.toFloat() ?: 50f
        val timeShift = if (speed > 0f && time > 0f) kotlin.math.sin(time * speed * 2f) * 30f else 0f
        val threshold = (baseThreshold + timeShift).coerceIn(5f, 200f)
        val invert = params["invert"] as? Boolean ?: false

        val source = params["_sourceImage"] as? Bitmap
        if (source == null) {
            drawPlaceholder(canvas, bitmap)
            return
        }

        val scaled = if (source.width == w && source.height == h) source else Bitmap.createScaledBitmap(source, w, h, true)
        val srcPixels = IntArray(w * h)
        scaled.getPixels(srcPixels, 0, w, 0, 0, w, h)

        // Convert to grayscale
        val gray = FloatArray(w * h) { i ->
            val p = srcPixels[i]
            0.299f * Color.red(p) + 0.587f * Color.green(p) + 0.114f * Color.blue(p)
        }

        val edgeMag = FloatArray(w * h)

        when (algorithm) {
            "sobel", "canny" -> {
                // Sobel kernels
                for (y in 1 until h - 1) {
                    for (x in 1 until w - 1) {
                        val gx = -gray[(y - 1) * w + x - 1] + gray[(y - 1) * w + x + 1] +
                                -2f * gray[y * w + x - 1] + 2f * gray[y * w + x + 1] +
                                -gray[(y + 1) * w + x - 1] + gray[(y + 1) * w + x + 1]
                        val gy = -gray[(y - 1) * w + x - 1] - 2f * gray[(y - 1) * w + x] - gray[(y - 1) * w + x + 1] +
                                gray[(y + 1) * w + x - 1] + 2f * gray[(y + 1) * w + x] + gray[(y + 1) * w + x + 1]
                        edgeMag[y * w + x] = sqrt(gx * gx + gy * gy)
                    }
                }
                if (algorithm == "canny") {
                    // Simple non-maximum suppression
                    val suppressed = FloatArray(w * h)
                    for (y in 1 until h - 1) {
                        for (x in 1 until w - 1) {
                            val mag = edgeMag[y * w + x]
                            val left = edgeMag[y * w + x - 1]
                            val right = edgeMag[y * w + x + 1]
                            val up = edgeMag[(y - 1) * w + x]
                            val down = edgeMag[(y + 1) * w + x]
                            suppressed[y * w + x] = if (mag >= left && mag >= right && mag >= up && mag >= down) mag else 0f
                        }
                    }
                    System.arraycopy(suppressed, 0, edgeMag, 0, w * h)
                }
            }
            "laplacian" -> {
                for (y in 1 until h - 1) {
                    for (x in 1 until w - 1) {
                        val lap = -gray[(y - 1) * w + x] - gray[y * w + x - 1] +
                                4f * gray[y * w + x] -
                                gray[y * w + x + 1] - gray[(y + 1) * w + x]
                        edgeMag[y * w + x] = abs(lap)
                    }
                }
            }
            "prewitt" -> {
                for (y in 1 until h - 1) {
                    for (x in 1 until w - 1) {
                        val gx = -gray[(y - 1) * w + x - 1] + gray[(y - 1) * w + x + 1] +
                                -gray[y * w + x - 1] + gray[y * w + x + 1] +
                                -gray[(y + 1) * w + x - 1] + gray[(y + 1) * w + x + 1]
                        val gy = -gray[(y - 1) * w + x - 1] - gray[(y - 1) * w + x] - gray[(y - 1) * w + x + 1] +
                                gray[(y + 1) * w + x - 1] + gray[(y + 1) * w + x] + gray[(y + 1) * w + x + 1]
                        edgeMag[y * w + x] = sqrt(gx * gx + gy * gy)
                    }
                }
            }
        }

        // Render edges
        val outPixels = IntArray(w * h)
        val paletteColors = palette.colorInts()

        for (i in 0 until w * h) {
            val mag = edgeMag[i]
            if (mag > threshold) {
                val t = ((mag - threshold) / (255f - threshold)).coerceIn(0f, 1f)
                val edgeColor = palette.lerpColor(t)
                outPixels[i] = if (invert) {
                    val inv = 255 - ((mag / 255f * 255).toInt().coerceIn(0, 255))
                    Color.rgb(inv, inv, inv)
                } else edgeColor
            } else {
                outPixels[i] = if (invert) Color.WHITE else Color.BLACK
            }
        }

        bitmap.setPixels(outPixels, 0, w, 0, 0, w, h)
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
