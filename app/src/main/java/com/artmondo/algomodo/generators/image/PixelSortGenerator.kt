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

/**
 * Pixel sorting generator.
 *
 * Sorts contiguous spans of pixels along horizontal, vertical, or diagonal
 * axes by brightness, hue, or saturation, creating dramatic streak effects.
 */
class PixelSortGenerator : Generator {

    override val id = "pixel-sort"
    override val family = "image"
    override val styleName = "Pixel Sort"
    override val definition =
        "Sort pixel spans by brightness, hue, or saturation within threshold-defined ranges."
    override val algorithmNotes =
        "For each row (or column/diagonal), pixels are scanned to find contiguous spans where " +
        "brightness exceeds the threshold. Within each span, pixels are sorted by the chosen " +
        "criterion (brightness, hue, or saturation). The intensity parameter controls what " +
        "fraction of spans are actually sorted, allowing partial effects."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema = listOf(
        Parameter.SelectParam("Direction", "direction", ParamGroup.GEOMETRY, "Sort direction — horizontal: row streaks | vertical: column drips | diagonal: 45-degree streaks | radial: circular sweeps", listOf("horizontal", "vertical", "diagonal", "radial"), "horizontal"),
        Parameter.NumberParam("Lower Threshold", "lowerThreshold", ParamGroup.TEXTURE, "Brightness below which pixels start a sortable span", 10f, 200f, 5f, 30f),
        Parameter.NumberParam("Upper Threshold", "upperThreshold", ParamGroup.TEXTURE, "Brightness above which pixels end a sortable span", 50f, 255f, 5f, 200f),
        Parameter.SelectParam("Sort By", "sortBy", ParamGroup.COLOR, "Sorting criterion within each span — brightness | hue | saturation | red | green | blue", listOf("brightness", "hue", "saturation", "red", "green", "blue"), "brightness"),
        Parameter.BooleanParam("Reverse", "reverse", ParamGroup.TEXTURE, "Reverse sort order within spans", false),
        Parameter.NumberParam("Intensity", "intensity", ParamGroup.TEXTURE, "Fraction of detected spans to actually sort — lower gives a subtler effect", 0.05f, 1f, 0.05f, 0.5f),
        Parameter.NumberParam("Max Span", "maxSpan", ParamGroup.GEOMETRY, "Maximum span length in pixels — caps streak length", 10f, 1000f, 10f, 500f),
        Parameter.NumberParam("Speed", "speed", ParamGroup.FLOW_MOTION, "Animation speed — thresholds shift over time", 0f, 3f, 0.1f, 0.5f)
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "direction" to "horizontal",
        "lowerThreshold" to 30f,
        "upperThreshold" to 200f,
        "sortBy" to "brightness",
        "reverse" to false,
        "intensity" to 0.5f,
        "maxSpan" to 500f,
        "speed" to 0.5f
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
        val direction = (params["direction"] as? String) ?: "horizontal"
        val speed = (params["speed"] as? Number)?.toFloat() ?: 0.5f
        val baseThreshold = (params["threshold"] as? Number)?.toFloat() ?: 80f
        val timeShift = if (speed > 0f && time > 0f) kotlin.math.sin(time * speed * 2f) * 40f else 0f
        val threshold = (baseThreshold + timeShift).coerceIn(10f, 245f)
        val sortBy = (params["sortBy"] as? String) ?: "brightness"
        val intensity = (params["intensity"] as? Number)?.toFloat() ?: 0.5f

        val source = params["_sourceImage"] as? Bitmap
        if (source == null) {
            drawPlaceholder(canvas, bitmap)
            return
        }

        val rng = SeededRNG(seed)
        val scaled = Bitmap.createScaledBitmap(source, w, h, true)
        val pixels = IntArray(w * h)
        scaled.getPixels(pixels, 0, w, 0, 0, w, h)

        fun luma(pixel: Int): Float {
            return 0.299f * Color.red(pixel) + 0.587f * Color.green(pixel) + 0.114f * Color.blue(pixel)
        }

        when (direction) {
            "horizontal" -> {
                for (y in 0 until h) {
                    var x = 0
                    while (x < w) {
                        // Find span start (above threshold)
                        while (x < w && luma(pixels[y * w + x]) < threshold) x++
                        val start = x
                        while (x < w && luma(pixels[y * w + x]) >= threshold) x++
                        val end = x

                        if (end > start + 1 && rng.random() < intensity) {
                            val span = pixels.copyOfRange(y * w + start, y * w + end)
                            sortSpan(span, sortBy)
                            System.arraycopy(span, 0, pixels, y * w + start, span.size)
                        }
                    }
                }
            }
            "vertical" -> {
                for (x in 0 until w) {
                    var y = 0
                    while (y < h) {
                        while (y < h && luma(pixels[y * w + x]) < threshold) y++
                        val start = y
                        while (y < h && luma(pixels[y * w + x]) >= threshold) y++
                        val end = y

                        if (end > start + 1 && rng.random() < intensity) {
                            val span = IntArray(end - start) { pixels[(start + it) * w + x] }
                            sortSpan(span, sortBy)
                            for (i in span.indices) {
                                pixels[(start + i) * w + x] = span[i]
                            }
                        }
                    }
                }
            }
            "diagonal" -> {
                // Sort along 45-degree diagonals
                for (d in 0 until w + h - 1) {
                    val diagPixels = mutableListOf<Pair<Int, Int>>() // (index in pixels array, sort position)
                    val startX = if (d < h) 0 else d - h + 1
                    val startY = if (d < h) d else h - 1
                    var dx = startX
                    var dy = startY
                    while (dx < w && dy >= 0) {
                        diagPixels.add(Pair(dy * w + dx, diagPixels.size))
                        dx++
                        dy--
                    }

                    if (diagPixels.size < 2) continue

                    var i = 0
                    while (i < diagPixels.size) {
                        while (i < diagPixels.size && luma(pixels[diagPixels[i].first]) < threshold) i++
                        val start = i
                        while (i < diagPixels.size && luma(pixels[diagPixels[i].first]) >= threshold) i++
                        val end = i

                        if (end > start + 1 && rng.random() < intensity) {
                            val span = IntArray(end - start) { pixels[diagPixels[start + it].first] }
                            sortSpan(span, sortBy)
                            for (j in span.indices) {
                                pixels[diagPixels[start + j].first] = span[j]
                            }
                        }
                    }
                }
            }
        }

        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
        canvas.drawBitmap(bitmap, 0f, 0f, null)
        if (scaled !== source) scaled.recycle()
    }

    private fun sortSpan(arr: IntArray, sortBy: String) {
        val hsv = FloatArray(3)
        val comparator = Comparator<Int> { a, b ->
            val ka = when (sortBy) {
                "hue" -> { Color.colorToHSV(a, hsv); hsv[0] }
                "saturation" -> { Color.colorToHSV(a, hsv); hsv[1] }
                else -> 0.299f * Color.red(a) + 0.587f * Color.green(a) + 0.114f * Color.blue(a)
            }
            val kb = when (sortBy) {
                "hue" -> { Color.colorToHSV(b, hsv); hsv[0] }
                "saturation" -> { Color.colorToHSV(b, hsv); hsv[1] }
                else -> 0.299f * Color.red(b) + 0.587f * Color.green(b) + 0.114f * Color.blue(b)
            }
            ka.compareTo(kb)
        }

        val boxed = arr.toTypedArray()
        boxed.sortWith(comparator)
        for (i in arr.indices) arr[i] = boxed[i]
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
