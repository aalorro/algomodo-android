package com.artmondo.algomodo.generators.pixelart

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect

/**
 * Shared helpers for Pixel Art family generators.
 *
 * The web TS implementations render to a small ImageData(sz, sz), then upscale
 * via `ctx.drawImage(offscreen, 0, 0, W, H)` with `imageSmoothingEnabled = false`.
 *
 * The Android equivalent: build an IntArray of `sz*sz` ARGB ints, set into a
 * small bitmap, then `canvas.drawBitmap(small, srcRect, dstRect, nearestPaint)`.
 */
internal object PixelArtUtil {
    val nearestPaint = Paint().apply { isFilterBitmap = false; isAntiAlias = false; isDither = false }

    fun hexToRgb(hex: String): IntArray {
        val n = hex.removePrefix("#").toLong(16).toInt()
        return intArrayOf((n ushr 16) and 0xFF, (n ushr 8) and 0xFF, n and 0xFF)
    }

    /** Pre-compute palette as [R,G,B] triplets. */
    fun paletteRgb(palette: com.artmondo.algomodo.data.palettes.Palette): Array<IntArray> {
        return Array(palette.colors.size) { hexToRgb(palette.colors[it]) }
    }

    /** Pack a small (sz x sz) IntArray (already ARGB) into the full bitmap with nearest-neighbor scaling. */
    fun blitNearest(canvas: Canvas, bitmap: Bitmap, smallPixels: IntArray, sz: Int) {
        val small = Bitmap.createBitmap(sz, sz, Bitmap.Config.ARGB_8888)
        small.setPixels(smallPixels, 0, sz, 0, 0, sz, sz)
        canvas.drawBitmap(small, Rect(0, 0, sz, sz), Rect(0, 0, bitmap.width, bitmap.height), nearestPaint)
        small.recycle()
    }

    /** Pack a small (w x h) IntArray into the full bitmap with nearest-neighbor scaling. */
    fun blitNearest(canvas: Canvas, bitmap: Bitmap, smallPixels: IntArray, sw: Int, sh: Int) {
        val small = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)
        small.setPixels(smallPixels, 0, sw, 0, 0, sw, sh)
        canvas.drawBitmap(small, Rect(0, 0, sw, sh), Rect(0, 0, bitmap.width, bitmap.height), nearestPaint)
        small.recycle()
    }

    fun rgb(r: Int, g: Int, b: Int): Int =
        Color.rgb(r.coerceIn(0, 255), g.coerceIn(0, 255), b.coerceIn(0, 255))

    /** Get param as float with default. */
    fun pf(p: Map<String, Any>, k: String, default: Float): Float =
        (p[k] as? Number)?.toFloat() ?: default

    fun pi(p: Map<String, Any>, k: String, default: Int): Int =
        (p[k] as? Number)?.toInt() ?: default

    fun pb(p: Map<String, Any>, k: String, default: Boolean): Boolean =
        (p[k] as? Boolean) ?: default

    fun ps(p: Map<String, Any>, k: String, default: String): String =
        (p[k] as? String) ?: default
}
