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

/**
 * Dithering generator.
 *
 * Applies various dithering algorithms to reduce a source image to a
 * limited number of tonal levels, creating halftone-like patterns.
 */
class DitherImageGenerator : Generator {

    override val id = "dither-image"
    override val family = "image"
    override val styleName = "Dithering"
    override val definition =
        "Apply dithering algorithms to reduce image to limited tonal levels."
    override val algorithmNotes =
        "Floyd-Steinberg distributes quantisation error to neighbouring pixels. " +
        "Atkinson diffuses 6/8 of the error for a lighter look. Ordered dithering uses " +
        "a Bayer threshold matrix. 'bw' mode reduces to black and white; 'palette' mode " +
        "maps each pixel to the nearest palette colour."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema = listOf(
        Parameter.SelectParam("Algorithm", "algorithm", ParamGroup.TEXTURE, "floyd-steinberg: classic error diffusion | atkinson: lighter Apple Mac style | ordered: Bayer threshold matrix | random: stochastic noise dither", listOf("floyd-steinberg", "atkinson", "ordered", "random"), "floyd-steinberg"),
        Parameter.NumberParam("Levels", "levels", ParamGroup.COLOR, "Number of quantisation levels per channel", 2f, 16f, 1f, 2f),
        Parameter.SelectParam("Color Mode", "colorMode", ParamGroup.COLOR, "bw: black and white | palette: map to palette colours | rgb: dither each channel independently", listOf("bw", "palette", "rgb"), "bw"),
        Parameter.NumberParam("Scale", "scale", ParamGroup.GEOMETRY, "Pixel scale — upscale for a chunky retro look", 1f, 8f, 1f, 1f),
        Parameter.NumberParam("Contrast", "contrast", ParamGroup.TEXTURE, "Boost contrast before dithering", 0.5f, 3f, 0.1f, 1f),
        Parameter.BooleanParam("Invert", "invert", ParamGroup.COLOR, "Invert luminance before dithering", false),
        Parameter.NumberParam("Speed", "speed", ParamGroup.FLOW_MOTION, "Animation speed — threshold noise shifts over time", 0f, 2f, 0.1f, 0f)
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "algorithm" to "floyd-steinberg",
        "levels" to 2f,
        "colorMode" to "bw",
        "scale" to 1f,
        "contrast" to 1f,
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
        val w = bitmap.width
        val h = bitmap.height
        val algorithm = (params["algorithm"] as? String) ?: "floyd-steinberg"
        val levels = (params["levels"] as? Number)?.toInt() ?: 2
        val colorMode = (params["colorMode"] as? String) ?: "bw"
        val speed = (params["speed"] as? Number)?.toFloat() ?: 0f
        val timeBias = if (speed > 0f && time > 0f) kotlin.math.sin(time * speed * 2f) * 20f else 0f

        val source = params["_sourceImage"] as? Bitmap
        if (source == null) {
            drawPlaceholder(canvas, bitmap)
            return
        }

        val scaled = Bitmap.createScaledBitmap(source, w, h, true)
        val srcPixels = IntArray(w * h)
        scaled.getPixels(srcPixels, 0, w, 0, 0, w, h)

        val paletteColors = palette.colorInts()

        // Convert to float luminance array
        val luma = FloatArray(w * h) { i ->
            val p = srcPixels[i]
            (0.299f * Color.red(p) + 0.587f * Color.green(p) + 0.114f * Color.blue(p) + timeBias).coerceIn(0f, 255f)
        }

        // Also keep R, G, B channels for palette mode
        val rCh = FloatArray(w * h) { Color.red(srcPixels[it]).toFloat() }
        val gCh = FloatArray(w * h) { Color.green(srcPixels[it]).toFloat() }
        val bCh = FloatArray(w * h) { Color.blue(srcPixels[it]).toFloat() }

        when (algorithm) {
            "floyd-steinberg" -> ditherFloydSteinberg(luma, w, h, levels)
            "atkinson" -> ditherAtkinson(luma, w, h, levels)
            "ordered", "bayer" -> ditherOrdered(luma, w, h, levels)
        }

        if (colorMode == "palette") {
            when (algorithm) {
                "floyd-steinberg" -> {
                    ditherFloydSteinberg(rCh, w, h, levels)
                    ditherFloydSteinberg(gCh, w, h, levels)
                    ditherFloydSteinberg(bCh, w, h, levels)
                }
                "atkinson" -> {
                    ditherAtkinson(rCh, w, h, levels)
                    ditherAtkinson(gCh, w, h, levels)
                    ditherAtkinson(bCh, w, h, levels)
                }
                else -> {
                    ditherOrdered(rCh, w, h, levels)
                    ditherOrdered(gCh, w, h, levels)
                    ditherOrdered(bCh, w, h, levels)
                }
            }
        }

        // Build output pixels
        val outPixels = IntArray(w * h)
        for (i in 0 until w * h) {
            if (colorMode == "bw") {
                val v = luma[i].toInt().coerceIn(0, 255)
                outPixels[i] = Color.rgb(v, v, v)
            } else {
                val r = rCh[i].toInt().coerceIn(0, 255)
                val g = gCh[i].toInt().coerceIn(0, 255)
                val b = bCh[i].toInt().coerceIn(0, 255)
                // Map to nearest palette color
                val t = (0.299f * r + 0.587f * g + 0.114f * b) / 255f
                outPixels[i] = palette.lerpColor(t)
            }
        }

        bitmap.setPixels(outPixels, 0, w, 0, 0, w, h)
        if (scaled !== source) scaled.recycle()
    }

    private fun quantize(value: Float, levels: Int): Float {
        val step = 255f / (levels - 1).coerceAtLeast(1)
        return (kotlin.math.round(value / step) * step).coerceIn(0f, 255f)
    }

    private fun ditherFloydSteinberg(data: FloatArray, w: Int, h: Int, levels: Int) {
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                val old = data[i]
                val new = quantize(old, levels)
                data[i] = new
                val err = old - new

                if (x + 1 < w) data[i + 1] += err * 7f / 16f
                if (y + 1 < h) {
                    if (x > 0) data[(y + 1) * w + x - 1] += err * 3f / 16f
                    data[(y + 1) * w + x] += err * 5f / 16f
                    if (x + 1 < w) data[(y + 1) * w + x + 1] += err * 1f / 16f
                }
            }
        }
    }

    private fun ditherAtkinson(data: FloatArray, w: Int, h: Int, levels: Int) {
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                val old = data[i]
                val new = quantize(old, levels)
                data[i] = new
                val err = (old - new) / 8f

                if (x + 1 < w) data[i + 1] += err
                if (x + 2 < w) data[i + 2] += err
                if (y + 1 < h) {
                    if (x > 0) data[(y + 1) * w + x - 1] += err
                    data[(y + 1) * w + x] += err
                    if (x + 1 < w) data[(y + 1) * w + x + 1] += err
                }
                if (y + 2 < h) {
                    data[(y + 2) * w + x] += err
                }
            }
        }
    }

    private fun ditherOrdered(data: FloatArray, w: Int, h: Int, levels: Int) {
        val bayer4 = arrayOf(
            intArrayOf(0, 8, 2, 10),
            intArrayOf(12, 4, 14, 6),
            intArrayOf(3, 11, 1, 9),
            intArrayOf(15, 7, 13, 5)
        )

        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                val threshold = bayer4[y % 4][x % 4] / 16f - 0.5f
                val adjusted = data[i] + threshold * (256f / levels)
                data[i] = quantize(adjusted, levels)
            }
        }
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
