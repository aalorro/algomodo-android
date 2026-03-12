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
        val hasGrain = postFX.grain > 0f
        val hasVignette = postFX.vignette > 0f
        val hasDither = postFX.dither >= 2
        val hasPosterize = postFX.posterize >= 1
        if (!hasGrain && !hasVignette && !hasDither && !hasPosterize) return

        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        // Pre-compute constants
        val grainScale = if (hasGrain) postFX.grain * 255f * 2f else 0f
        var lcg = 12345L

        val cx = w / 2f
        val cy = h / 2f
        val invCx = if (hasVignette) 1f / cx else 0f
        val invCy = if (hasVignette) 1f / cy else 0f
        val halfVigAmount = if (hasVignette) postFX.vignette * 0.5f else 0f

        val ditherStep = if (hasDither) 255f / (postFX.dither - 1) else 0f
        val ditherHalf = ditherStep * 0.5f

        val postMask = if (hasPosterize && postFX.posterize in 1..7)
            (0xFF shl (8 - postFX.posterize)) and 0xFF else 0

        // Single pass over all pixels
        for (y in 0 until h) {
            val dy2 = if (hasVignette) {
                val dy = (y - cy) * invCy; dy * dy
            } else 0f

            for (x in 0 until w) {
                val idx = y * w + x
                var c = pixels[idx]
                val a = c ushr 24
                var r = (c shr 16) and 0xFF
                var g = (c shr 8) and 0xFF
                var b = c and 0xFF

                // Grain
                if (hasGrain) {
                    lcg = (lcg * 1103515245L + 12345L) and 0x7FFFFFFFL
                    val noise = ((lcg.toFloat() / 0x7FFFFFFFL) - 0.5f) * grainScale
                    r = (r + noise).toInt().coerceIn(0, 255)
                    g = (g + noise).toInt().coerceIn(0, 255)
                    b = (b + noise).toInt().coerceIn(0, 255)
                }

                // Vignette
                if (hasVignette) {
                    val dx = (x - cx) * invCx
                    val distSq = dx * dx + dy2
                    val factor = 1f - (distSq * halfVigAmount).coerceIn(0f, 1f)
                    r = (r * factor).toInt().coerceIn(0, 255)
                    g = (g * factor).toInt().coerceIn(0, 255)
                    b = (b * factor).toInt().coerceIn(0, 255)
                }

                // Dither (integer rounding — avoids Math.round JNI call)
                if (hasDither) {
                    r = (((r / ditherStep + 0.5f).toInt()) * ditherStep).toInt().coerceIn(0, 255)
                    g = (((g / ditherStep + 0.5f).toInt()) * ditherStep).toInt().coerceIn(0, 255)
                    b = (((b / ditherStep + 0.5f).toInt()) * ditherStep).toInt().coerceIn(0, 255)
                }

                // Posterize
                if (hasPosterize) {
                    r = r and postMask
                    g = g and postMask
                    b = b and postMask
                }

                pixels[idx] = (a shl 24) or (r shl 16) or (g shl 8) or b
            }
        }

        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
    }
}
