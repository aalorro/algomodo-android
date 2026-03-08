package com.artmondo.algomodo.rendering

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.sqrt
import kotlin.random.Random

data class PostFXSettings(
    val grain: Float = 0f,
    val vignette: Float = 0f,
    val dither: Int = 0,
    val posterize: Int = 0
)

object PostFXProcessor {

    fun apply(bitmap: Bitmap, postFX: PostFXSettings) {
        if (postFX.grain <= 0f && postFX.vignette <= 0f && postFX.dither < 2 && postFX.posterize < 1) return

        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        if (postFX.grain > 0f) applyGrain(pixels, postFX.grain, w, h)
        if (postFX.vignette > 0f) applyVignette(pixels, postFX.vignette, w, h)
        if (postFX.dither >= 2) applyDither(pixels, postFX.dither)
        if (postFX.posterize >= 1) applyPosterize(pixels, postFX.posterize)

        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
    }

    private fun applyGrain(pixels: IntArray, amount: Float, width: Int, height: Int) {
        // Fast LCG RNG for grain noise
        var lcg = 12345L
        for (i in pixels.indices) {
            lcg = (lcg * 1103515245L + 12345L) and 0x7FFFFFFFL
            val noise = ((lcg.toFloat() / 0x7FFFFFFFL) - 0.5f) * amount * 255f * 2f
            val c = pixels[i]
            val r = (Color.red(c) + noise).toInt().coerceIn(0, 255)
            val g = (Color.green(c) + noise).toInt().coerceIn(0, 255)
            val b = (Color.blue(c) + noise).toInt().coerceIn(0, 255)
            pixels[i] = Color.argb(Color.alpha(c), r, g, b)
        }
    }

    private fun applyVignette(pixels: IntArray, amount: Float, width: Int, height: Int) {
        val cx = width / 2f
        val cy = height / 2f
        val maxDist = sqrt(cx * cx + cy * cy)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val dx = (x - cx) / cx
                val dy = (y - cy) / cy
                val dist = sqrt(dx * dx + dy * dy)
                val factor = 1f - (dist * dist * amount * 0.5f).coerceIn(0f, 1f)
                val i = y * width + x
                val c = pixels[i]
                val r = (Color.red(c) * factor).toInt().coerceIn(0, 255)
                val g = (Color.green(c) * factor).toInt().coerceIn(0, 255)
                val b = (Color.blue(c) * factor).toInt().coerceIn(0, 255)
                pixels[i] = Color.argb(Color.alpha(c), r, g, b)
            }
        }
    }

    private fun applyDither(pixels: IntArray, levels: Int) {
        if (levels < 2) return
        val step = 255f / (levels - 1)
        for (i in pixels.indices) {
            val c = pixels[i]
            val r = (Math.round(Color.red(c) / step) * step).toInt().coerceIn(0, 255)
            val g = (Math.round(Color.green(c) / step) * step).toInt().coerceIn(0, 255)
            val b = (Math.round(Color.blue(c) / step) * step).toInt().coerceIn(0, 255)
            pixels[i] = Color.argb(Color.alpha(c), r, g, b)
        }
    }

    private fun applyPosterize(pixels: IntArray, bits: Int) {
        if (bits < 1 || bits > 7) return
        val mask = (0xFF shl (8 - bits)) and 0xFF
        for (i in pixels.indices) {
            val c = pixels[i]
            val r = Color.red(c) and mask
            val g = Color.green(c) and mask
            val b = Color.blue(c) and mask
            pixels[i] = Color.argb(Color.alpha(c), r, g, b)
        }
    }
}
