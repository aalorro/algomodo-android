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
 * Data moshing / glitch generator.
 *
 * Corrupts a source image by shifting, sorting, or corrupting rectangular
 * blocks of pixel data to produce a glitch-art aesthetic.
 */
class DataMoshGenerator : Generator {

    override val id = "data-mosh"
    override val family = "image"
    override val styleName = "Data Mosh"
    override val definition =
        "Glitch art by shifting, sorting, or corrupting blocks of image data."
    override val algorithmNotes =
        "The source image is divided into rectangular blocks. In 'shift' mode, blocks are " +
        "horizontally displaced by a random offset. In 'sort' mode, pixel rows within each " +
        "block are sorted by brightness. In 'corrupt' mode, random pixel values are XOR'd " +
        "with noise to simulate data corruption."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema = listOf(
        Parameter.NumberParam("Intensity", "intensity", ParamGroup.TEXTURE, "Glitch intensity — higher means more corruption", 0.05f, 1f, 0.05f, 0.5f),
        Parameter.NumberParam("Block Size", "blockSize", ParamGroup.GEOMETRY, "Pixel block size for corruption regions", 4f, 64f, 2f, 16f),
        Parameter.SelectParam("Mode", "mode", ParamGroup.TEXTURE, "shift: horizontal row displacement | sort: brightness-sort rows | corrupt: XOR noise | bloom: colour bleed | repeat: block duplication", listOf("shift", "sort", "corrupt", "bloom", "repeat"), "shift"),
        Parameter.NumberParam("Region Count", "regionCount", ParamGroup.COMPOSITION, "Number of distinct glitch regions", 1f, 30f, 1f, 8f),
        Parameter.BooleanParam("Channel Separate", "channelSeparate", ParamGroup.COLOR, "Apply glitch independently to R, G, B channels", false),
        Parameter.SelectParam("Direction", "direction", ParamGroup.GEOMETRY, "horizontal: row-based | vertical: column-based | both: grid blocks", listOf("horizontal", "vertical", "both"), "horizontal"),
        Parameter.NumberParam("Speed", "speed", ParamGroup.FLOW_MOTION, "Animation speed — glitch regions shift over time", 0f, 3f, 0.1f, 0.5f)
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "intensity" to 0.5f,
        "blockSize" to 16f,
        "mode" to "shift",
        "regionCount" to 8f,
        "channelSeparate" to false,
        "direction" to "horizontal",
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
        val blockSize = (params["blockSize"] as? Number)?.toInt() ?: 16
        val mode = (params["mode"] as? String) ?: "shift"

        val source = params["_sourceImage"] as? Bitmap
        if (source == null) {
            drawPlaceholder(canvas, bitmap)
            return
        }

        val speed = (params["speed"] as? Number)?.toFloat() ?: 0.5f
        val animSeed = if (speed > 0f && time > 0f) seed + (time * speed * 10f).toInt() else seed
        val rng = SeededRNG(animSeed)
        val scaled = Bitmap.createScaledBitmap(source, w, h, true)
        val pixels = IntArray(w * h)
        scaled.getPixels(pixels, 0, w, 0, 0, w, h)

        when (mode) {
            "shift" -> {
                val maxShift = (w * intensity * 0.3f).toInt().coerceAtLeast(1)
                var y = 0
                while (y < h) {
                    val bh = rng.integer(blockSize / 2, blockSize * 2).coerceAtMost(h - y)
                    if (rng.random() < intensity) {
                        val shift = rng.integer(-maxShift, maxShift)
                        for (row in y until (y + bh).coerceAtMost(h)) {
                            val rowStart = row * w
                            val temp = pixels.copyOfRange(rowStart, rowStart + w)
                            for (x in 0 until w) {
                                val sx = ((x - shift) % w + w) % w
                                pixels[rowStart + x] = temp[sx]
                            }
                        }
                    }
                    y += bh
                }
            }
            "sort" -> {
                val threshold = (intensity * 255).toInt()
                for (y in 0 until h) {
                    val rowStart = y * w
                    var x = 0
                    while (x < w) {
                        // Find span of pixels above threshold
                        val start = x
                        while (x < w) {
                            val pixel = pixels[rowStart + x]
                            val luma = (0.299f * Color.red(pixel) + 0.587f * Color.green(pixel) + 0.114f * Color.blue(pixel))
                            if (luma < threshold) break
                            x++
                        }
                        if (x > start + 1) {
                            val span = pixels.copyOfRange(rowStart + start, rowStart + x)
                            span.sortedArray().also { sorted ->
                                System.arraycopy(sorted, 0, span, 0, sorted.size)
                            }
                            System.arraycopy(span, 0, pixels, rowStart + start, span.size)
                        }
                        x++
                    }
                }
            }
            "corrupt" -> {
                val numCorruptions = (w * h * intensity * 0.001f).toInt().coerceIn(1, 10000)
                for (i in 0 until numCorruptions) {
                    val bx = rng.integer(0, w - blockSize)
                    val by = rng.integer(0, h - blockSize)
                    val xorVal = rng.integer(0, 0xFFFFFF)

                    for (dy in 0 until blockSize.coerceAtMost(h - by)) {
                        for (dx in 0 until blockSize.coerceAtMost(w - bx)) {
                            val idx = (by + dy) * w + (bx + dx)
                            if (rng.random() < intensity) {
                                pixels[idx] = pixels[idx] xor xorVal or (0xFF shl 24)
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

    private fun drawPlaceholder(canvas: Canvas, bitmap: Bitmap) {
        canvas.drawColor(Color.DKGRAY)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textSize = 24f; textAlign = Paint.Align.CENTER
        }
        canvas.drawText("No source image", bitmap.width / 2f, bitmap.height / 2f, paint)
    }

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float = 0.4f
}
