package com.artmondo.algomodo.generators.image

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import com.artmondo.algomodo.data.palettes.Palette
import com.artmondo.algomodo.generators.Generator
import com.artmondo.algomodo.generators.ParamGroup
import com.artmondo.algomodo.generators.Parameter
import com.artmondo.algomodo.generators.Quality
import com.artmondo.algomodo.rendering.SvgPath

/**
 * Video feedback loop generator.
 *
 * Simulates a camera pointed at its own monitor. The image is repeatedly
 * scaled, rotated, and blended with itself to create fractal-like feedback
 * patterns.
 */
class FeedbackLoopGenerator : Generator {

    override val id = "feedback-loop"
    override val family = "image"
    override val styleName = "Feedback Loop"
    override val definition =
        "Iterative zoom-rotate-blend feedback producing fractal-like patterns."
    override val algorithmNotes =
        "Starting from the source image, each iteration: (1) scales by the 'scale' factor, " +
        "(2) rotates by 'rotation' degrees, (3) multiplies brightness, and (4) alpha-blends " +
        "the transformed result back onto the canvas. Multiple iterations create the " +
        "characteristic infinite-tunnel / fractal look of video feedback."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema = listOf(
        Parameter.NumberParam("Iterations", "iterations", ParamGroup.COMPOSITION, "Number of feedback iterations", 5f, 60f, 1f, 20f),
        Parameter.NumberParam("Scale", "scale", ParamGroup.GEOMETRY, "Zoom factor per iteration — <1 zooms in, >1 zooms out", 0.85f, 1.15f, 0.005f, 0.98f),
        Parameter.NumberParam("Rotation", "rotation", ParamGroup.GEOMETRY, "Rotation per iteration in degrees", -15f, 15f, 0.5f, 2f),
        Parameter.NumberParam("X Offset", "xOffset", ParamGroup.GEOMETRY, "Horizontal shift per iteration in pixels", -20f, 20f, 1f, 0f),
        Parameter.NumberParam("Y Offset", "yOffset", ParamGroup.GEOMETRY, "Vertical shift per iteration in pixels", -20f, 20f, 1f, 0f),
        Parameter.NumberParam("Brightness", "brightness", ParamGroup.COLOR, "Brightness decay per iteration — <1 fades, >1 brightens", 0.85f, 1.15f, 0.01f, 0.99f),
        Parameter.NumberParam("Blend", "blend", ParamGroup.COLOR, "Alpha blend factor per iteration", 0.3f, 0.99f, 0.01f, 0.9f),
        Parameter.SelectParam("Color Shift", "colorShift", ParamGroup.COLOR, "none: no colour change | hue-rotate: shift hue each iteration | channel-swap: cycle RGB channels", listOf("none", "hue-rotate", "channel-swap"), "none"),
        Parameter.NumberParam("Speed", "speed", ParamGroup.FLOW_MOTION, "Animation speed — rotation and scale evolve over time", 0f, 2f, 0.1f, 0.3f)
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "iterations" to 20f,
        "scale" to 0.98f,
        "rotation" to 2f,
        "xOffset" to 0f,
        "yOffset" to 0f,
        "brightness" to 0.99f,
        "blend" to 0.9f,
        "colorShift" to "none",
        "speed" to 0.3f
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
        val scale = (params["scale"] as? Number)?.toFloat() ?: 0.98f
        val speed = (params["speed"] as? Number)?.toFloat() ?: 0.3f
        val baseRotation = (params["rotation"] as? Number)?.toFloat() ?: 2f
        val rotation = if (speed > 0f && time > 0f) baseRotation + kotlin.math.sin(time * speed) * 3f else baseRotation
        val brightnessDecay = (params["brightness"] as? Number)?.toFloat() ?: 0.99f
        val blend = (params["blend"] as? Number)?.toFloat() ?: 0.9f

        val source = params["_sourceImage"] as? Bitmap
        if (source == null) {
            drawPlaceholder(canvas, bitmap)
            return
        }

        val iterations = when (quality) {
            Quality.DRAFT -> 15
            Quality.BALANCED -> 30
            Quality.ULTRA -> 60
        }

        val scaled = Bitmap.createScaledBitmap(source, w, h, true)

        // Work with a mutable copy
        val current = scaled.copy(Bitmap.Config.ARGB_8888, true)
        val tempBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val tempCanvas = Canvas(tempBitmap)

        val blendPaint = Paint().apply {
            alpha = (blend * 255).toInt()
            isFilterBitmap = true
        }

        val matrix = Matrix()
        val cx = w / 2f
        val cy = h / 2f

        for (iter in 0 until iterations) {
            // Copy current to temp with transform
            tempCanvas.drawColor(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)

            matrix.reset()
            matrix.postTranslate(-cx, -cy)
            matrix.postScale(scale, scale)
            matrix.postRotate(rotation)
            matrix.postTranslate(cx, cy)

            tempCanvas.drawBitmap(current, matrix, blendPaint)

            // Blend temp back into current with brightness decay
            val currentPixels = IntArray(w * h)
            val tempPixels = IntArray(w * h)
            current.getPixels(currentPixels, 0, w, 0, 0, w, h)
            tempBitmap.getPixels(tempPixels, 0, w, 0, 0, w, h)

            for (i in 0 until w * h) {
                val src = currentPixels[i]
                val dst = tempPixels[i]
                val srcA = Color.alpha(dst) / 255f

                val r = ((Color.red(src) * (1f - srcA * blend) + Color.red(dst) * srcA * blend) * brightnessDecay).toInt().coerceIn(0, 255)
                val g = ((Color.green(src) * (1f - srcA * blend) + Color.green(dst) * srcA * blend) * brightnessDecay).toInt().coerceIn(0, 255)
                val b = ((Color.blue(src) * (1f - srcA * blend) + Color.blue(dst) * srcA * blend) * brightnessDecay).toInt().coerceIn(0, 255)

                currentPixels[i] = Color.rgb(r, g, b)
            }

            current.setPixels(currentPixels, 0, w, 0, 0, w, h)
        }

        // Draw final result
        canvas.drawBitmap(current, 0f, 0f, null)

        // Copy to output bitmap
        val finalPixels = IntArray(w * h)
        current.getPixels(finalPixels, 0, w, 0, 0, w, h)
        bitmap.setPixels(finalPixels, 0, w, 0, 0, w, h)

        current.recycle()
        tempBitmap.recycle()
        if (scaled !== source) scaled.recycle()
    }

    private fun drawPlaceholder(canvas: Canvas, bitmap: Bitmap) {
        canvas.drawColor(Color.DKGRAY)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textSize = 24f; textAlign = Paint.Align.CENTER
        }
        canvas.drawText("No source image", bitmap.width / 2f, bitmap.height / 2f, paint)
    }

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float = 0.8f
}
