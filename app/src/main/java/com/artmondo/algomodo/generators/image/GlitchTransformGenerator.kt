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
 * Glitch transform generator.
 *
 * Applies horizontal slice displacement, RGB channel separation, and
 * optional scan lines to create a digital glitch aesthetic.
 */
class GlitchTransformGenerator : Generator {

    override val id = "glitch-transform"
    override val family = "image"
    override val styleName = "Glitch Transform"
    override val definition =
        "Horizontal slice displacement with RGB channel separation for a glitch look."
    override val algorithmNotes =
        "The source image is divided into horizontal slices of random height. Each slice " +
        "is horizontally shifted by a random amount proportional to intensity. RGB channels " +
        "are then separated by the channelShift amount. Optional scan-line darkening adds " +
        "alternating dark rows for a CRT aesthetic."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema = listOf(
        Parameter.NumberParam("Intensity", "intensity", ParamGroup.TEXTURE, "Overall glitch intensity — higher means more distortion", 0.05f, 1f, 0.05f, 0.5f),
        Parameter.NumberParam("Slice Height", "sliceHeight", ParamGroup.GEOMETRY, "Height of each horizontal displacement slice in pixels", 1f, 80f, 1f, 10f),
        Parameter.NumberParam("Channel Shift", "channelShift", ParamGroup.COLOR, "RGB channel separation offset in pixels", 0f, 40f, 1f, 5f),
        Parameter.BooleanParam("Scan Lines", "scanLines", ParamGroup.TEXTURE, "Add CRT scan-line darkening on alternating rows", true),
        Parameter.NumberParam("Scan Line Opacity", "scanLineOpacity", ParamGroup.TEXTURE, "Darkness of scan lines — 0 = invisible, 1 = fully dark", 0.1f, 1f, 0.05f, 0.4f),
        Parameter.SelectParam("Distortion", "distortion", ParamGroup.TEXTURE, "none: shift only | wave: sinusoidal warp | block: rectangular displacement | noise: per-pixel offset", listOf("none", "wave", "block", "noise"), "none"),
        Parameter.NumberParam("Jitter", "jitter", ParamGroup.TEXTURE, "Random per-pixel colour jitter", 0f, 50f, 1f, 0f),
        Parameter.NumberParam("Speed", "speed", ParamGroup.FLOW_MOTION, "Animation speed — glitch slices re-randomize over time", 0f, 3f, 0.1f, 0.5f)
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "intensity" to 0.5f,
        "sliceHeight" to 10f,
        "channelShift" to 5f,
        "scanLines" to true,
        "scanLineOpacity" to 0.4f,
        "distortion" to "none",
        "jitter" to 0f,
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
        val intensity = (params["intensity"] as? Number)?.toFloat() ?: 0.5f
        val sliceHeight = (params["sliceHeight"] as? Number)?.toInt() ?: 10
        val channelShift = (params["channelShift"] as? Number)?.toInt() ?: 5
        val scanLines = params["scanLines"] as? Boolean ?: true

        val source = params["_sourceImage"] as? Bitmap
        if (source == null) {
            drawPlaceholder(canvas, bitmap)
            return
        }

        val speed = (params["speed"] as? Number)?.toFloat() ?: 0.5f
        val animSeed = if (speed > 0f && time > 0f) seed + (time * speed * 10f).toInt() else seed
        val rng = SeededRNG(animSeed)
        val scaled = Bitmap.createScaledBitmap(source, w, h, true)
        val srcPixels = IntArray(w * h)
        scaled.getPixels(srcPixels, 0, w, 0, 0, w, h)
        val outPixels = IntArray(w * h)

        // Step 1: Horizontal slice displacement
        val displaced = srcPixels.copyOf()
        val maxShift = (w * intensity * 0.2f).toInt().coerceAtLeast(1)

        var y = 0
        while (y < h) {
            val sh = rng.integer(sliceHeight / 2, sliceHeight * 2).coerceAtMost(h - y)
            val shift = if (rng.random() < intensity) rng.integer(-maxShift, maxShift) else 0

            for (row in y until (y + sh).coerceAtMost(h)) {
                for (x in 0 until w) {
                    val sx = ((x - shift) % w + w) % w
                    displaced[row * w + x] = srcPixels[row * w + sx]
                }
            }
            y += sh
        }

        // Step 2: RGB channel separation
        for (i in 0 until w * h) {
            val py = i / w
            val px = i % w

            val rIdx = (py * w + ((px + channelShift) % w)).coerceIn(0, w * h - 1)
            val gIdx = i
            val bIdx = (py * w + ((px - channelShift + w) % w)).coerceIn(0, w * h - 1)

            val r = Color.red(displaced[rIdx])
            val g = Color.green(displaced[gIdx])
            val b = Color.blue(displaced[bIdx])

            outPixels[i] = Color.rgb(r, g, b)
        }

        // Step 3: Scan lines
        if (scanLines) {
            for (py in 0 until h) {
                if (py % 2 == 0) continue
                for (px in 0 until w) {
                    val idx = py * w + px
                    val p = outPixels[idx]
                    val r = (Color.red(p) * 0.6f).toInt()
                    val g = (Color.green(p) * 0.6f).toInt()
                    val b = (Color.blue(p) * 0.6f).toInt()
                    outPixels[idx] = Color.rgb(r, g, b)
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
