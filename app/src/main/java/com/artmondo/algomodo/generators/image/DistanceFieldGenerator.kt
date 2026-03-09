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
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Distance field generator.
 *
 * Computes an approximate signed distance field from the edges of a
 * thresholded source image and renders it as a smooth gradient.
 */
class DistanceFieldGenerator : Generator {

    override val id = "distance-field"
    override val family = "image"
    override val styleName = "Distance Field"
    override val definition =
        "Signed distance field computed from the edges of a thresholded source image."
    override val algorithmNotes =
        "The source image is converted to a binary image using the threshold. An approximate " +
        "distance transform is computed by two-pass scanning (top-left to bottom-right, then " +
        "reverse). The resulting distances are normalised to [0,1] within the spread range " +
        "and mapped through the palette. Invert swaps inside/outside."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema = listOf(
        Parameter.NumberParam("Threshold", "threshold", ParamGroup.TEXTURE, "Binary threshold for edge detection (0-255)", 0f, 255f, 5f, 128f),
        Parameter.NumberParam("Spread", "spread", ParamGroup.GEOMETRY, "Distance spread in pixels — how far the gradient extends from edges", 2f, 80f, 2f, 20f),
        Parameter.BooleanParam("Invert", "invert", ParamGroup.COLOR, "Swap inside and outside regions", false),
        Parameter.SelectParam("Color Mode", "colorMode", ParamGroup.COLOR, "palette: use palette gradient | grayscale: black-to-white | heat: warm colour ramp", listOf("palette", "grayscale", "heat"), "palette"),
        Parameter.NumberParam("Edge Width", "edgeWidth", ParamGroup.GEOMETRY, "Width of the sharp edge band in pixels", 0f, 8f, 0.5f, 1f),
        Parameter.BooleanParam("Show Contours", "showContours", ParamGroup.TEXTURE, "Draw iso-distance contour lines", false),
        Parameter.NumberParam("Speed", "speed", ParamGroup.FLOW_MOTION, "Animation speed — threshold oscillates over time", 0f, 2f, 0.1f, 0f)
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "threshold" to 128f,
        "spread" to 20f,
        "invert" to false,
        "colorMode" to "palette",
        "edgeWidth" to 1f,
        "showContours" to false,
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
        val timeShift = if (speed > 0f && time > 0f) kotlin.math.sin(time * speed * 2f) * 40f else 0f
        val threshold = (baseThreshold + timeShift).toInt().coerceIn(0, 255)
        val spread = (params["spread"] as? Number)?.toFloat() ?: 20f
        val invert = params["invert"] as? Boolean ?: false

        val source = params["_sourceImage"] as? Bitmap
        if (source == null) {
            drawPlaceholder(canvas, bitmap)
            return
        }

        val scaled = if (source.width == w && source.height == h) source else Bitmap.createScaledBitmap(source, w, h, true)
        val srcPixels = IntArray(w * h)
        scaled.getPixels(srcPixels, 0, w, 0, 0, w, h)

        // Convert to binary
        val binary = BooleanArray(w * h)
        for (i in srcPixels.indices) {
            val p = srcPixels[i]
            val luma = (0.299f * Color.red(p) + 0.587f * Color.green(p) + 0.114f * Color.blue(p)).toInt()
            binary[i] = if (invert) luma < threshold else luma >= threshold
        }

        // Approximate distance transform (Chamfer 3-4)
        val INF = (w + h).toFloat()
        val dist = FloatArray(w * h) { if (binary[it]) 0f else INF }

        // Forward pass
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                if (x > 0) dist[i] = min(dist[i], dist[i - 1] + 1f)
                if (y > 0) dist[i] = min(dist[i], dist[(y - 1) * w + x] + 1f)
                if (x > 0 && y > 0) dist[i] = min(dist[i], dist[(y - 1) * w + x - 1] + 1.414f)
                if (x < w - 1 && y > 0) dist[i] = min(dist[i], dist[(y - 1) * w + x + 1] + 1.414f)
            }
        }

        // Backward pass
        for (y in h - 1 downTo 0) {
            for (x in w - 1 downTo 0) {
                val i = y * w + x
                if (x < w - 1) dist[i] = min(dist[i], dist[i + 1] + 1f)
                if (y < h - 1) dist[i] = min(dist[i], dist[(y + 1) * w + x] + 1f)
                if (x < w - 1 && y < h - 1) dist[i] = min(dist[i], dist[(y + 1) * w + x + 1] + 1.414f)
                if (x > 0 && y < h - 1) dist[i] = min(dist[i], dist[(y + 1) * w + x - 1] + 1.414f)
            }
        }

        // Also compute distance from outside to inside (for signed field)
        val distInside = FloatArray(w * h) { if (!binary[it]) 0f else INF }
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                if (x > 0) distInside[i] = min(distInside[i], distInside[i - 1] + 1f)
                if (y > 0) distInside[i] = min(distInside[i], distInside[(y - 1) * w + x] + 1f)
                if (x > 0 && y > 0) distInside[i] = min(distInside[i], distInside[(y - 1) * w + x - 1] + 1.414f)
                if (x < w - 1 && y > 0) distInside[i] = min(distInside[i], distInside[(y - 1) * w + x + 1] + 1.414f)
            }
        }
        for (y in h - 1 downTo 0) {
            for (x in w - 1 downTo 0) {
                val i = y * w + x
                if (x < w - 1) distInside[i] = min(distInside[i], distInside[i + 1] + 1f)
                if (y < h - 1) distInside[i] = min(distInside[i], distInside[(y + 1) * w + x] + 1f)
                if (x < w - 1 && y < h - 1) distInside[i] = min(distInside[i], distInside[(y + 1) * w + x + 1] + 1.414f)
                if (x > 0 && y < h - 1) distInside[i] = min(distInside[i], distInside[(y + 1) * w + x - 1] + 1.414f)
            }
        }

        // Render SDF
        val outPixels = IntArray(w * h)
        for (i in 0 until w * h) {
            val sdf = if (binary[i]) -distInside[i] else dist[i]
            val t = ((sdf / spread) * 0.5f + 0.5f).coerceIn(0f, 1f)
            outPixels[i] = palette.lerpColor(t)
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

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float = 0.6f
}
