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
        val scale = (params["scale"] as? Number)?.toInt()?.coerceAtLeast(1) ?: 1
        val contrast = (params["contrast"] as? Number)?.toFloat() ?: 1f
        val invertOutput = params["invert"] as? Boolean ?: false
        val speed = (params["speed"] as? Number)?.toFloat() ?: 0f
        val timeBias = if (speed > 0f && time > 0f) kotlin.math.sin(time * speed * 2f) * 20f else 0f

        val source = params["_sourceImage"] as? Bitmap
        if (source == null) {
            drawPlaceholder(canvas, bitmap)
            return
        }

        // Scale: downscale for chunky retro look, then we'll upscale at the end
        val workW = (w / scale).coerceAtLeast(1)
        val workH = (h / scale).coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(
            if (source.width == w && source.height == h) source else Bitmap.createScaledBitmap(source, w, h, true),
            workW, workH, true
        )
        val srcPixels = IntArray(workW * workH)
        scaled.getPixels(srcPixels, 0, workW, 0, 0, workW, workH)

        val paletteColors = palette.colorInts()

        // Convert to float luminance array with contrast and invert
        val luma = FloatArray(workW * workH) { i ->
            val p = srcPixels[i]
            var v = 0.299f * Color.red(p) + 0.587f * Color.green(p) + 0.114f * Color.blue(p)
            // Apply contrast
            v = (v - 128f) * contrast + 128f
            // Apply invert
            if (invertOutput) v = 255f - v
            (v + timeBias).coerceIn(0f, 255f)
        }

        // Also keep R, G, B channels for palette mode
        val rCh = FloatArray(workW * workH) {
            var v = Color.red(srcPixels[it]).toFloat()
            v = (v - 128f) * contrast + 128f
            if (invertOutput) v = 255f - v
            v.coerceIn(0f, 255f)
        }
        val gCh = FloatArray(workW * workH) {
            var v = Color.green(srcPixels[it]).toFloat()
            v = (v - 128f) * contrast + 128f
            if (invertOutput) v = 255f - v
            v.coerceIn(0f, 255f)
        }
        val bCh = FloatArray(workW * workH) {
            var v = Color.blue(srcPixels[it]).toFloat()
            v = (v - 128f) * contrast + 128f
            if (invertOutput) v = 255f - v
            v.coerceIn(0f, 255f)
        }

        when (algorithm) {
            "floyd-steinberg" -> ditherFloydSteinberg(luma, workW, workH, levels)
            "atkinson" -> ditherAtkinson(luma, workW, workH, levels)
            "ordered", "bayer" -> ditherOrdered(luma, workW, workH, levels)
        }

        if (colorMode == "palette" || colorMode == "rgb") {
            when (algorithm) {
                "floyd-steinberg" -> {
                    ditherFloydSteinberg(rCh, workW, workH, levels)
                    ditherFloydSteinberg(gCh, workW, workH, levels)
                    ditherFloydSteinberg(bCh, workW, workH, levels)
                }
                "atkinson" -> {
                    ditherAtkinson(rCh, workW, workH, levels)
                    ditherAtkinson(gCh, workW, workH, levels)
                    ditherAtkinson(bCh, workW, workH, levels)
                }
                else -> {
                    ditherOrdered(rCh, workW, workH, levels)
                    ditherOrdered(gCh, workW, workH, levels)
                    ditherOrdered(bCh, workW, workH, levels)
                }
            }
        }

        // Build output pixels at work resolution
        val workPixels = IntArray(workW * workH)
        for (i in 0 until workW * workH) {
            workPixels[i] = when (colorMode) {
                "bw" -> {
                    val v = luma[i].toInt().coerceIn(0, 255)
                    Color.rgb(v, v, v)
                }
                "rgb" -> {
                    val r = rCh[i].toInt().coerceIn(0, 255)
                    val g = gCh[i].toInt().coerceIn(0, 255)
                    val b = bCh[i].toInt().coerceIn(0, 255)
                    Color.rgb(r, g, b)
                }
                else -> { // "palette"
                    val r = rCh[i].toInt().coerceIn(0, 255)
                    val g = gCh[i].toInt().coerceIn(0, 255)
                    val b = bCh[i].toInt().coerceIn(0, 255)
                    val t = (0.299f * r + 0.587f * g + 0.114f * b) / 255f
                    palette.lerpColor(t)
                }
            }
        }

        // Upscale back to full resolution if scale > 1
        if (scale > 1) {
            val workBmp = Bitmap.createBitmap(workW, workH, Bitmap.Config.ARGB_8888)
            workBmp.setPixels(workPixels, 0, workW, 0, 0, workW, workH)
            val upscaled = Bitmap.createScaledBitmap(workBmp, w, h, false) // nearest-neighbor for chunky look
            val upPixels = IntArray(w * h)
            upscaled.getPixels(upPixels, 0, w, 0, 0, w, h)
            bitmap.setPixels(upPixels, 0, w, 0, 0, w, h)
            workBmp.recycle()
            if (upscaled !== workBmp) upscaled.recycle()
        } else {
            bitmap.setPixels(workPixels, 0, w, 0, 0, w, h)
        }
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
