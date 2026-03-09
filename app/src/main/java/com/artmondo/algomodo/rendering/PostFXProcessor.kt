package com.artmondo.algomodo.rendering

import android.graphics.Bitmap

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
        val noiseScale = amount * 255f * 2f
        var lcg = 12345L
        for (i in pixels.indices) {
            lcg = (lcg * 1103515245L + 12345L) and 0x7FFFFFFFL
            val noise = ((lcg.toFloat() / 0x7FFFFFFFL) - 0.5f) * noiseScale
            val c = pixels[i]
            val a = c ushr 24
            val r = (((c shr 16) and 0xFF) + noise).toInt().coerceIn(0, 255)
            val g = (((c shr 8) and 0xFF) + noise).toInt().coerceIn(0, 255)
            val b = ((c and 0xFF) + noise).toInt().coerceIn(0, 255)
            pixels[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
        }
    }

    private fun applyVignette(pixels: IntArray, amount: Float, width: Int, height: Int) {
        val cx = width / 2f
        val cy = height / 2f
        val invCx = 1f / cx
        val invCy = 1f / cy
        val halfAmount = amount * 0.5f
        for (y in 0 until height) {
            val dy = (y - cy) * invCy
            val dy2 = dy * dy
            for (x in 0 until width) {
                val dx = (x - cx) * invCx
                val distSq = dx * dx + dy2
                val factor = 1f - (distSq * halfAmount).coerceIn(0f, 1f)
                val i = y * width + x
                val c = pixels[i]
                val a = c ushr 24
                val r = (((c shr 16) and 0xFF) * factor).toInt().coerceIn(0, 255)
                val g = (((c shr 8) and 0xFF) * factor).toInt().coerceIn(0, 255)
                val b = ((c and 0xFF) * factor).toInt().coerceIn(0, 255)
                pixels[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
    }

    private fun applyDither(pixels: IntArray, levels: Int) {
        if (levels < 2) return
        val step = 255f / (levels - 1)
        for (i in pixels.indices) {
            val c = pixels[i]
            val a = c ushr 24
            val r = (Math.round(((c shr 16) and 0xFF) / step) * step).toInt().coerceIn(0, 255)
            val g = (Math.round(((c shr 8) and 0xFF) / step) * step).toInt().coerceIn(0, 255)
            val b = (Math.round((c and 0xFF) / step) * step).toInt().coerceIn(0, 255)
            pixels[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
        }
    }

    private fun applyPosterize(pixels: IntArray, bits: Int) {
        if (bits < 1 || bits > 7) return
        val mask = (0xFF shl (8 - bits)) and 0xFF
        for (i in pixels.indices) {
            val c = pixels[i]
            val a = c ushr 24
            val r = (c shr 16) and mask
            val g = (c shr 8) and mask
            val b = c and mask
            pixels[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
        }
    }
}
