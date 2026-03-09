package com.artmondo.algomodo.generators.image

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.artmondo.algomodo.core.rng.SimplexNoise
import com.artmondo.algomodo.data.palettes.Palette
import com.artmondo.algomodo.generators.Generator
import com.artmondo.algomodo.generators.ParamGroup
import com.artmondo.algomodo.generators.Parameter
import com.artmondo.algomodo.generators.Quality
import com.artmondo.algomodo.rendering.SvgPath
import kotlin.math.sqrt

/**
 * Optical flow visualization generator.
 *
 * Computes luminance gradients from the source image and draws flow lines
 * showing the gradient direction at each grid point.
 */
class OpticalFlowGenerator : Generator {

    override val id = "optical-flow"
    override val family = "image"
    override val styleName = "Optical Flow"
    override val definition =
        "Luminance-gradient flow lines drawn over the source image."
    override val algorithmNotes =
        "The source image is converted to grayscale. At each grid point, horizontal and " +
        "vertical luminance gradients (Sobel-like) are computed. A short line segment is " +
        "drawn in the gradient direction, with length proportional to gradient magnitude. " +
        "Noise scale modulates an additional simplex noise perturbation for visual interest."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema = listOf(
        Parameter.NumberParam("Grid Size", "gridSize", ParamGroup.GEOMETRY, "Spacing between flow sample points in pixels", 3f, 30f, 1f, 10f),
        Parameter.NumberParam("Line Length", "lineLength", ParamGroup.GEOMETRY, "Maximum flow line length in pixels", 2f, 50f, 1f, 15f),
        Parameter.NumberParam("Noise Scale", "noiseScale", ParamGroup.TEXTURE, "Simplex noise perturbation frequency — higher adds more turbulence", 0.5f, 15f, 0.5f, 3f),
        Parameter.NumberParam("Line Width", "lineWidth", ParamGroup.TEXTURE, "Stroke width of flow lines", 0.3f, 5f, 0.1f, 1f),
        Parameter.SelectParam("Color Mode", "colorMode", ParamGroup.COLOR, "source: colour from image at each point | palette: colour by gradient magnitude | mono: single colour flow lines", listOf("source", "palette", "mono"), "palette"),
        Parameter.NumberParam("Background Dim", "backgroundDim", ParamGroup.COLOR, "Brightness of the background source image — 0 = black, 1 = full brightness", 0f, 1f, 0.05f, 0.3f),
        Parameter.BooleanParam("Arrows", "arrows", ParamGroup.TEXTURE, "Draw arrowheads at line ends to show flow direction", false),
        Parameter.NumberParam("Speed", "speed", ParamGroup.FLOW_MOTION, "Animation speed — noise perturbation drifts over time", 0f, 2f, 0.1f, 0.2f)
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "gridSize" to 10f,
        "lineLength" to 15f,
        "noiseScale" to 3f,
        "lineWidth" to 1f,
        "colorMode" to "palette",
        "backgroundDim" to 0.3f,
        "arrows" to false,
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
        val w = bitmap.width
        val h = bitmap.height
        val gridSize = (params["gridSize"] as? Number)?.toInt() ?: 10
        val lineLength = (params["lineLength"] as? Number)?.toFloat() ?: 15f
        val noiseScale = (params["noiseScale"] as? Number)?.toFloat() ?: 3f
        val lineWidth = (params["lineWidth"] as? Number)?.toFloat() ?: 1f
        val speed = (params["speed"] as? Number)?.toFloat() ?: 0.2f

        val source = params["_sourceImage"] as? Bitmap
        if (source == null) {
            drawPlaceholder(canvas, bitmap)
            return
        }

        val noise = SimplexNoise(seed)
        val scaled = if (source.width == w && source.height == h) source else Bitmap.createScaledBitmap(source, w, h, true)
        val srcPixels = IntArray(w * h)
        scaled.getPixels(srcPixels, 0, w, 0, 0, w, h)

        // Draw the source image dimmed as background
        val bgPixels = IntArray(w * h)
        for (i in 0 until w * h) {
            val p = srcPixels[i]
            bgPixels[i] = Color.rgb(
                (Color.red(p) * 0.3f).toInt(),
                (Color.green(p) * 0.3f).toInt(),
                (Color.blue(p) * 0.3f).toInt()
            )
        }
        bitmap.setPixels(bgPixels, 0, w, 0, 0, w, h)

        // Convert to grayscale
        val gray = FloatArray(w * h) { i ->
            val p = srcPixels[i]
            (0.299f * Color.red(p) + 0.587f * Color.green(p) + 0.114f * Color.blue(p)) / 255f
        }

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = lineWidth
            strokeCap = Paint.Cap.ROUND
        }

        val paletteColors = palette.colorInts()

        // Compute gradients and draw flow lines
        var idx = 0
        var gy = gridSize
        while (gy < h - gridSize) {
            var gx = gridSize
            while (gx < w - gridSize) {
                // Sobel-like gradient
                val dxVal = gray[gy * w + gx + 1] - gray[gy * w + gx - 1]
                val dyVal = gray[(gy + 1) * w + gx] - gray[(gy - 1) * w + gx]

                // Add noise perturbation
                val timeOff = time * speed
                val noiseDx = noise.noise2D(gx.toFloat() / w * noiseScale + timeOff, gy.toFloat() / h * noiseScale) * 0.2f
                val noiseDy = noise.noise2D(gx.toFloat() / w * noiseScale + 100f, gy.toFloat() / h * noiseScale + timeOff) * 0.2f

                val flowDx = dxVal + noiseDx
                val flowDy = dyVal + noiseDy
                val mag = sqrt(flowDx * flowDx + flowDy * flowDy)

                if (mag > 0.001f) {
                    val normDx = flowDx / mag
                    val normDy = flowDy / mag
                    val len = lineLength * mag.coerceAtMost(1f)

                    val x1 = gx - normDx * len * 0.5f
                    val y1 = gy - normDy * len * 0.5f
                    val x2 = gx + normDx * len * 0.5f
                    val y2 = gy + normDy * len * 0.5f

                    val t = gray[gy * w + gx]
                    paint.color = palette.lerpColor(t)
                    paint.alpha = (150 + mag * 300).toInt().coerceAtMost(255)

                    canvas.drawLine(x1, y1, x2, y2, paint)
                }

                idx++
                gx += gridSize
            }
            gy += gridSize
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
        val gridSize = (params["gridSize"] as? Number)?.toInt() ?: 10
        return (10f / gridSize).coerceIn(0.2f, 1f)
    }
}
