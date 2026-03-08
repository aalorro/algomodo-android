package com.artmondo.algomodo.generators.image

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import com.artmondo.algomodo.data.palettes.Palette
import com.artmondo.algomodo.generators.Generator
import com.artmondo.algomodo.generators.ParamGroup
import com.artmondo.algomodo.generators.Parameter
import com.artmondo.algomodo.generators.Quality
import com.artmondo.algomodo.rendering.SvgPath
import android.graphics.Paint

/**
 * Convolution filter generator.
 *
 * Applies a 3x3 convolution kernel to a source image for effects such as
 * sharpening, embossing, blurring, edge outlining, and ridge detection.
 */
class ConvolutionGenerator : Generator {

    override val id = "convolution"
    override val family = "image"
    override val styleName = "Convolution Filter"
    override val definition =
        "Apply a convolution matrix (sharpen, emboss, blur, outline, ridge) to a source image."
    override val algorithmNotes =
        "Each output pixel is the weighted sum of its 3x3 neighbourhood in the source, " +
        "with weights given by the selected kernel. The strength parameter blends the " +
        "filtered result with the original. Boundary pixels use clamped sampling."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema = listOf(
        Parameter.SelectParam("Kernel", "kernel", ParamGroup.TEXTURE, "Convolution kernel — sharpen: increase detail | emboss: raised surface | blur: soften | outline: edge highlight | ridge: ridge detection", listOf("sharpen", "emboss", "blur", "outline", "ridge"), "sharpen"),
        Parameter.NumberParam("Strength", "strength", ParamGroup.TEXTURE, "Filter strength — higher values amplify the kernel effect", 0.1f, 5f, 0.1f, 1f),
        Parameter.NumberParam("Passes", "passes", ParamGroup.TEXTURE, "Number of times to apply the kernel", 1f, 5f, 1f, 1f),
        Parameter.BooleanParam("Preserve Color", "preserveColor", ParamGroup.COLOR, "Keep original hue/saturation and only filter luminance", false),
        Parameter.BooleanParam("Invert", "invert", ParamGroup.COLOR, "Invert the filtered output", false),
        Parameter.NumberParam("Mix", "mix", ParamGroup.COLOR, "Blend between original (0) and filtered (1)", 0f, 1f, 0.05f, 1f),
        Parameter.NumberParam("Speed", "speed", ParamGroup.FLOW_MOTION, "Animation speed — kernel strength oscillates over time", 0f, 2f, 0.1f, 0f)
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "kernel" to "sharpen",
        "strength" to 1f,
        "passes" to 1f,
        "preserveColor" to false,
        "invert" to false,
        "mix" to 1f,
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
        val kernelName = (params["kernel"] as? String) ?: "sharpen"
        val speed = (params["speed"] as? Number)?.toFloat() ?: 0f
        val baseStrength = (params["strength"] as? Number)?.toFloat() ?: 1f
        val strength = if (speed > 0f && time > 0f) baseStrength * (1f + kotlin.math.sin(time * speed * 2f) * 0.5f) else baseStrength

        val source = params["_sourceImage"] as? Bitmap
        if (source == null) {
            drawPlaceholder(canvas, bitmap)
            return
        }

        val kernel = when (kernelName) {
            "sharpen" -> floatArrayOf(0f, -1f, 0f, -1f, 5f, -1f, 0f, -1f, 0f)
            "emboss" -> floatArrayOf(-2f, -1f, 0f, -1f, 1f, 1f, 0f, 1f, 2f)
            "blur" -> floatArrayOf(1f / 9, 1f / 9, 1f / 9, 1f / 9, 1f / 9, 1f / 9, 1f / 9, 1f / 9, 1f / 9)
            "outline" -> floatArrayOf(-1f, -1f, -1f, -1f, 8f, -1f, -1f, -1f, -1f)
            "ridge" -> floatArrayOf(-1f, -1f, -1f, -1f, 9f, -1f, -1f, -1f, -1f)
            else -> floatArrayOf(0f, -1f, 0f, -1f, 5f, -1f, 0f, -1f, 0f)
        }

        val scaled = Bitmap.createScaledBitmap(source, w, h, true)
        val srcPixels = IntArray(w * h)
        scaled.getPixels(srcPixels, 0, w, 0, 0, w, h)
        val outPixels = IntArray(w * h)

        val step = when (quality) {
            Quality.DRAFT -> 2
            Quality.BALANCED -> 1
            Quality.ULTRA -> 1
        }

        for (y in 0 until h step step) {
            for (x in 0 until w step step) {
                var rAcc = 0f; var gAcc = 0f; var bAcc = 0f
                var ki = 0
                for (ky in -1..1) {
                    for (kx in -1..1) {
                        val sx = (x + kx).coerceIn(0, w - 1)
                        val sy = (y + ky).coerceIn(0, h - 1)
                        val pixel = srcPixels[sy * w + sx]
                        val kv = kernel[ki] * strength
                        rAcc += Color.red(pixel) * kv
                        gAcc += Color.green(pixel) * kv
                        bAcc += Color.blue(pixel) * kv
                        ki++
                    }
                }

                // For non-unity kernels, blend with original
                val origPixel = srcPixels[y * w + x]
                val blendFactor = if (kernelName == "blur") 1f else strength.coerceAtMost(1f)
                val invBlend = 1f - blendFactor + blendFactor

                val r = rAcc.toInt().coerceIn(0, 255)
                val g = gAcc.toInt().coerceIn(0, 255)
                val b = bAcc.toInt().coerceIn(0, 255)
                val color = Color.rgb(r, g, b)

                if (step == 1) {
                    outPixels[y * w + x] = color
                } else {
                    for (dy in 0 until step) {
                        for (dx in 0 until step) {
                            val fx = x + dx; val fy = y + dy
                            if (fx < w && fy < h) outPixels[fy * w + fx] = color
                        }
                    }
                }
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

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float = 0.4f
}
