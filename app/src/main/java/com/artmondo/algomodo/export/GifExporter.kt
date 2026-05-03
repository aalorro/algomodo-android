package com.artmondo.algomodo.export

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.artmondo.algomodo.data.palettes.Palette
import com.artmondo.algomodo.generators.AspectRatio
import com.artmondo.algomodo.generators.Generator
import com.artmondo.algomodo.generators.Quality
import java.io.*
import java.util.Arrays
import java.util.concurrent.atomic.AtomicBoolean

object GifExporter {

    class ExportCancelledException : Exception("Export cancelled")

    fun export(
        context: Context,
        generator: Generator,
        params: Map<String, Any>,
        seed: Int,
        palette: Palette,
        quality: Quality,
        resolution: Int,
        aspectRatio: AspectRatio = AspectRatio.SQUARE,
        durationSeconds: Int,
        fps: Int,
        boomerang: Boolean,
        endless: Boolean,
        fileName: String,
        onProgress: (Float) -> Unit = {},
        cancelled: AtomicBoolean = AtomicBoolean(false)
    ): Uri? {
        val totalFrames = durationSeconds * fps
        val frameDelay = 1000 / fps
        val bmpWidth = aspectRatio.width(resolution)
        val bmpHeight = aspectRatio.height(resolution)
        val pixelCount = bmpWidth * bmpHeight

        // Reuse a single Bitmap + Canvas across all frames
        val bitmap = Bitmap.createBitmap(bmpWidth, bmpHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val reusablePixels = IntArray(pixelCount)

        val doBoomerang = boomerang && totalFrames > 2

        val baos = ByteArrayOutputStream()
        val encoder = AnimatedGifEncoder()
        encoder.start(baos)
        // 0 = loop forever, -1 = omit Netscape extension (play once)
        encoder.setRepeat(if (boomerang || endless) 0 else -1)
        encoder.setDelay(frameDelay)
        encoder.setLooping(boomerang || endless)

        val totalOutputFrames = if (doBoomerang)
            totalFrames + (totalFrames - 2) else totalFrames

        try {
            // Render and encode forward frames
            // Offset time by a tiny epsilon so frame 0 is never exactly 0.
            // Generators use `time > 0f` to detect animation mode — without this,
            // frame 0 renders as a static image (full res, full iterations, no
            // animation effects), causing a quality/color mismatch with all
            // subsequent frames and a wrong GIF palette.
            for (i in 0 until totalFrames) {
                if (cancelled.get()) throw ExportCancelledException()
                val time = i.toFloat() / fps + 1e-4f
                canvas.drawColor(android.graphics.Color.BLACK)
                generator.renderCanvas(canvas, bitmap, params, seed, palette, quality, time)
                bitmap.getPixels(reusablePixels, 0, bmpWidth, 0, 0, bmpWidth, bmpHeight)

                val isLast = !doBoomerang && i == totalFrames - 1
                encoder.addFrame(bitmap, reusablePixels, isLast)
                onProgress(i.toFloat() / totalOutputFrames * 0.9f)
            }

            // Re-render frames in reverse for boomerang (avoids storing all frames in RAM)
            if (doBoomerang) {
                for (i in (totalFrames - 2) downTo 1) {
                    if (cancelled.get()) throw ExportCancelledException()
                    val time = i.toFloat() / fps + 1e-4f
                    canvas.drawColor(android.graphics.Color.BLACK)
                    generator.renderCanvas(canvas, bitmap, params, seed, palette, quality, time)
                    bitmap.getPixels(reusablePixels, 0, bmpWidth, 0, 0, bmpWidth, bmpHeight)

                    val isLast = i == 1
                    encoder.addFrame(bitmap, reusablePixels, isLast)
                    val progress = (totalFrames + (totalFrames - 2 - i)).toFloat()
                    onProgress(progress / totalOutputFrames * 0.9f)
                }
            }

            encoder.finish()
            bitmap.recycle()

            onProgress(0.95f)
            val data = baos.toByteArray()
            return saveGif(context, data, fileName)
        } catch (e: ExportCancelledException) {
            bitmap.recycle()
            throw e
        }
    }

    private fun saveGif(context: Context, data: ByteArray, fileName: String): Uri? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "$fileName.gif")
                put(MediaStore.Images.Media.MIME_TYPE, "image/gif")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Algomodo")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            uri?.let {
                context.contentResolver.openOutputStream(it)?.use { os ->
                    os.write(data)
                }
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                context.contentResolver.update(it, values, null, null)
            }
            uri
        } else {
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                "Algomodo"
            )
            dir.mkdirs()
            val file = File(dir, "$fileName.gif")
            FileOutputStream(file).use { it.write(data) }
            Uri.fromFile(file)
        }
    }
}

/**
 * Animated GIF encoder optimized for multi-frame animation export.
 *
 * Key optimizations:
 * - IntArray(32768) for color frequency counting (direct indexing, no hashing/boxing)
 * - IntArray(32768) for color→palette cache (persists across frames)
 * - Global palette built once from first frame, reused for all frames
 * - IntArray-based open-addressing hash table for LZW (no HashMap boxing)
 * - All buffers pre-allocated and reused across frames (zero GC pressure)
 * - Frame differencing: unchanged pixels marked transparent, LZW compresses long
 *   transparent runs extremely efficiently (often 30-60% smaller files)
 * - Sub-rect cropping: only the bounding box of changed pixels is encoded per frame,
 *   reducing LZW input size dramatically for localized animations
 * - Adaptive fallback: if >95% of pixels changed, encodes full frame (delta overhead
 *   not worthwhile for near-total frame changes)
 */
class AnimatedGifEncoder {
    private var width = 0
    private var height = 0
    private var repeat = -1
    private var delay = 0 // centiseconds
    private var looping = false // whether the GIF should loop seamlessly
    private var started = false
    private var out: OutputStream? = null
    private var firstFrame = true
    private var sizeSet = false
    private var frameCount = 0

    // --- Quantization buffers (pre-allocated, reused across frames) ---

    // Frequency count for each 15-bit color (5 bits per R/G/B channel)
    private val colorFreq = IntArray(32768)

    // Maps 15-bit color → palette index; -1 = not yet mapped.
    // Persists across frames since the palette is global.
    private val colorToIndex = IntArray(32768)

    // The 256-color palette as 15-bit color values
    private val paletteColors = IntArray(256)
    private var paletteSize = 0

    // GIF RGB palette bytes (256 entries × 3 bytes)
    private val colorTab = ByteArray(768)

    // Per-pixel palette indices for current frame
    private var indexedPixels: ByteArray? = null

    // Temporary buffer for sorting colors by frequency
    private val sortBuf = LongArray(32768)

    // Whether the global palette has been computed
    private var paletteBuilt = false

    // --- Frame differencing buffers ---

    // Previous frame's palette indices for delta computation
    private var prevIndexedPixels: ByteArray? = null

    // Reusable buffer for sub-rect delta pixels
    private var subRectPixels: ByteArray? = null

    // Tracks which palette indices are used by changed pixels
    private val usedByChanged = BooleanArray(256)

    // --- LZW encoder buffers (pre-allocated, reused across frames) ---
    private val lzwKeys = IntArray(LZW_HASH_SIZE)
    private val lzwVals = IntArray(LZW_HASH_SIZE)
    private val lzwBlock = ByteArray(255)

    companion object {
        private const val LZW_HASH_SIZE = 5003
        // If more than 95% of pixels changed, skip delta optimization
        private const val DELTA_THRESHOLD = 0.95f
    }

    fun setDelay(ms: Int) { delay = ms / 10 }
    fun setRepeat(iter: Int) { repeat = iter }
    fun setLooping(loop: Boolean) { looping = loop }

    fun start(os: OutputStream): Boolean {
        out = os
        try {
            writeString("GIF89a")
        } catch (e: IOException) {
            return false
        }
        started = true
        firstFrame = true
        sizeSet = false
        paletteBuilt = false
        frameCount = 0
        prevIndexedPixels = null
        colorToIndex.fill(-1)
        return true
    }

    fun addFrame(im: Bitmap): Boolean = addFrame(im, null, false)

    fun addFrame(im: Bitmap, existingPixels: IntArray?, isLastFrame: Boolean = false): Boolean {
        if (!started) return false
        try {
            if (!sizeSet) {
                width = im.width
                height = im.height
                sizeSet = true
            }
            val pixels = existingPixels ?: extractPixels(im)

            // Build palette once from first frame, reuse for all subsequent frames
            if (!paletteBuilt) {
                buildPalette(pixels)
                paletteBuilt = true
            }
            mapPixelsToPalette(pixels)

            if (firstFrame) {
                writeLSD()
                writePalette()
                if (repeat >= 0) writeNetscapeExt()
                // First frame: encode full, disposal=1 (do not dispose)
                writeGraphicCtrlExt(-1, disposal = 1)
                writeImageDesc(0, 0, width, height)
                writePixelsLZW(indexedPixels!!, indexedPixels!!.size)
                savePrevFrame()
                firstFrame = false
            } else if (isLastFrame && looping) {
                // Last frame of a looping GIF: use disposal=2 (restore to background)
                // so the canvas is clean when looping back to frame 0.
                // Write as full frame to avoid delta artifacts at loop boundary.
                writeGraphicCtrlExt(-1, disposal = 2)
                writeImageDesc(0, 0, width, height)
                writePixelsLZW(indexedPixels!!, indexedPixels!!.size)
                savePrevFrame()
            } else {
                writeDeltaFrame()
            }
        } catch (e: IOException) {
            return false
        }
        return true
    }

    fun finish(): Boolean {
        if (!started) return false
        try {
            out?.write(0x3b) // GIF trailer
            out?.flush()
        } catch (e: IOException) {
            return false
        }
        started = false
        return true
    }

    /**
     * Encode a delta frame: only changed pixels are written, unchanged pixels
     * are marked transparent. The bounding rect of changes is used as the
     * sub-image region to minimize LZW input size.
     */
    private fun writeDeltaFrame() {
        val ip = indexedPixels!!
        val prev = prevIndexedPixels

        if (prev == null) {
            // No previous frame to diff against — full frame
            writeGraphicCtrlExt(-1)
            writeImageDesc(0, 0, width, height)
            writePixelsLZW(ip, ip.size)
            savePrevFrame()
            return
        }

        // Find bounding rect of changed pixels
        val totalPixels = width * height
        var minX = width
        var maxX = -1
        var minY = height
        var maxY = -1
        var changedCount = 0

        for (y in 0 until height) {
            val rowOff = y * width
            for (x in 0 until width) {
                val i = rowOff + x
                if (ip[i] != prev[i]) {
                    changedCount++
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }

        // No changes — write minimal 1×1 transparent frame to preserve delay timing
        if (maxX < 0) {
            writeGraphicCtrlExt(0)
            writeImageDesc(0, 0, 1, 1)
            val singlePixel = byteArrayOf(0)
            writePixelsLZW(singlePixel, 1)
            // Don't update prevIndexedPixels — frame is identical
            return
        }

        // If nearly all pixels changed, skip delta (overhead not worth it)
        if (changedCount.toFloat() / totalPixels > DELTA_THRESHOLD) {
            writeGraphicCtrlExt(-1)
            writeImageDesc(0, 0, width, height)
            writePixelsLZW(ip, ip.size)
            savePrevFrame()
            return
        }

        // Find a palette index not used by any changed pixel → transparent index
        usedByChanged.fill(false)
        for (y in minY..maxY) {
            val rowOff = y * width
            for (x in minX..maxX) {
                val i = rowOff + x
                if (ip[i] != prev[i]) {
                    usedByChanged[ip[i].toInt() and 0xFF] = true
                }
            }
        }
        var transparentIdx = -1
        for (k in 255 downTo 0) {
            if (!usedByChanged[k]) {
                transparentIdx = k
                break
            }
        }

        if (transparentIdx < 0) {
            // All 256 indices used by changed pixels (extremely rare) — full frame
            writeGraphicCtrlExt(-1)
            writeImageDesc(0, 0, width, height)
            writePixelsLZW(ip, ip.size)
            savePrevFrame()
            return
        }

        // Build sub-rect delta pixels: unchanged → transparent, changed → actual
        val rectW = maxX - minX + 1
        val rectH = maxY - minY + 1
        val subSize = rectW * rectH
        if (subRectPixels == null || subRectPixels!!.size < subSize) {
            subRectPixels = ByteArray(subSize)
        }
        val sub = subRectPixels!!
        val tByte = transparentIdx.toByte()

        for (y in minY..maxY) {
            val srcRowOff = y * width + minX
            val dstRowOff = (y - minY) * rectW
            for (x in 0 until rectW) {
                val srcI = srcRowOff + x
                sub[dstRowOff + x] = if (ip[srcI] == prev[srcI]) tByte else ip[srcI]
            }
        }

        // Write delta frame with transparency and sub-rect positioning
        writeGraphicCtrlExt(transparentIdx)
        writeImageDesc(minX, minY, rectW, rectH)
        writePixelsLZW(sub, subSize)
        savePrevFrame()
    }

    /** Copy current indexed pixels to prevIndexedPixels for next frame's delta. */
    private fun savePrevFrame() {
        val ip = indexedPixels ?: return
        val size = ip.size
        if (prevIndexedPixels == null || prevIndexedPixels!!.size != size) {
            prevIndexedPixels = ByteArray(size)
        }
        System.arraycopy(ip, 0, prevIndexedPixels!!, 0, size)
    }

    private fun extractPixels(image: Bitmap): IntArray {
        val pix = IntArray(image.width * image.height)
        image.getPixels(pix, 0, image.width, 0, 0, image.width, image.height)
        return pix
    }

    /**
     * Build the 256-color palette from pixel data using frequency analysis.
     * Called once for the first frame. Uses IntArray for O(1) frequency counting.
     */
    private fun buildPalette(pixels: IntArray) {
        // Count frequencies using direct array indexing (no hashing or boxing)
        colorFreq.fill(0)
        for (c in pixels) {
            val key = (((c ushr 19) and 0x1F) shl 10) or
                      (((c ushr 11) and 0x1F) shl 5) or
                       ((c ushr 3) and 0x1F)
            colorFreq[key]++
        }

        // Collect non-zero entries as packed (freq, colorKey) longs for sorting
        var nonZeroCount = 0
        for (i in 0 until 32768) {
            if (colorFreq[i] > 0) {
                sortBuf[nonZeroCount] = (colorFreq[i].toLong() shl 32) or i.toLong()
                nonZeroCount++
            }
        }

        // Sort ascending by frequency; top-256 are at the end
        Arrays.sort(sortBuf, 0, nonZeroCount)

        paletteSize = minOf(nonZeroCount, 256)
        for (k in 0 until paletteSize) {
            paletteColors[k] = (sortBuf[nonZeroCount - 1 - k] and 0xFFFFL).toInt()
        }

        // Build GIF RGB color table (expand 5-bit channels back to 8-bit)
        for (i in 0 until paletteSize) {
            val color = paletteColors[i]
            colorTab[i * 3]     = (((color shr 10) and 0x1F) * 255 / 31).toByte()
            colorTab[i * 3 + 1] = (((color shr 5) and 0x1F) * 255 / 31).toByte()
            colorTab[i * 3 + 2] = ((color and 0x1F) * 255 / 31).toByte()
        }
        // Zero remaining palette entries
        for (i in paletteSize * 3 until 768) colorTab[i] = 0

        // Seed color→index cache with exact palette matches
        colorToIndex.fill(-1)
        for (i in 0 until paletteSize) {
            colorToIndex[paletteColors[i]] = i
        }
    }

    /**
     * Map each pixel to its nearest palette index.
     * Uses IntArray cache for O(1) lookups on repeated 15-bit colors.
     * Cache persists across frames since the palette is global.
     */
    private fun mapPixelsToPalette(pixels: IntArray) {
        val nPix = pixels.size
        if (indexedPixels == null || indexedPixels!!.size != nPix) {
            indexedPixels = ByteArray(nPix)
        }
        val ip = indexedPixels!!
        val pc = paletteColors
        val ps = paletteSize
        val cache = colorToIndex

        for (i in pixels.indices) {
            val c = pixels[i]
            val r = (c ushr 19) and 0x1F
            val g = (c ushr 11) and 0x1F
            val b = (c ushr 3) and 0x1F
            val key = (r shl 10) or (g shl 5) or b

            val cached = cache[key]
            if (cached >= 0) {
                ip[i] = cached.toByte()
            } else {
                // Nearest-color search through palette
                var bestIdx = 0
                var bestDist = Int.MAX_VALUE
                for (pIdx in 0 until ps) {
                    val pColor = pc[pIdx]
                    val pr = (pColor shr 10) and 0x1F
                    val pg = (pColor shr 5) and 0x1F
                    val pb = pColor and 0x1F
                    val dr = r - pr
                    val dg = g - pg
                    val db = b - pb
                    val dist = dr * dr + dg * dg + db * db
                    if (dist < bestDist) {
                        bestDist = dist
                        bestIdx = pIdx
                    }
                }
                cache[key] = bestIdx
                ip[i] = bestIdx.toByte()
            }
        }
    }

    /**
     * LZW-compress pixel data and write to output stream.
     * Uses pre-allocated IntArray open-addressing hash table instead of HashMap.
     * Accepts arbitrary pixel arrays to support sub-rect encoding.
     */
    private fun writePixelsLZW(pixels: ByteArray, count: Int) {
        val os = out ?: return
        val minCodeSize = 8
        os.write(minCodeSize)

        val clearCode = 1 shl minCodeSize   // 256
        val eoiCode = clearCode + 1         // 257
        val maxTableSize = 4096

        var codeSize = minCodeSize + 1
        var nextCode = eoiCode + 1

        // Reset LZW hash table
        lzwVals.fill(-1)

        var bitBuffer = 0
        var bitCount = 0
        var blockIdx = 0
        val block = lzwBlock

        fun flushBlock() {
            if (blockIdx > 0) {
                os.write(blockIdx)
                os.write(block, 0, blockIdx)
                blockIdx = 0
            }
        }

        fun emitCode(code: Int) {
            bitBuffer = bitBuffer or (code shl bitCount)
            bitCount += codeSize
            while (bitCount >= 8) {
                block[blockIdx++] = (bitBuffer and 0xFF).toByte()
                bitBuffer = bitBuffer ushr 8
                bitCount -= 8
                if (blockIdx >= 254) flushBlock()
            }
        }

        fun resetTable() {
            lzwVals.fill(-1)
            nextCode = eoiCode + 1
            codeSize = minCodeSize + 1
        }

        emitCode(clearCode)

        if (count == 0) {
            emitCode(eoiCode)
            if (bitCount > 0) block[blockIdx++] = (bitBuffer and 0xFF).toByte()
            flushBlock()
            os.write(0)
            return
        }

        var prefix = pixels[0].toInt() and 0xFF

        for (i in 1 until count) {
            val suffix = pixels[i].toInt() and 0xFF
            val key = (prefix shl 8) or suffix

            // Open-addressing hash lookup
            var h = key % LZW_HASH_SIZE
            var found = false
            while (true) {
                val v = lzwVals[h]
                if (v == -1) break        // empty slot
                if (lzwKeys[h] == key) {  // match
                    prefix = v
                    found = true
                    break
                }
                h++
                if (h >= LZW_HASH_SIZE) h = 0
            }

            if (!found) {
                emitCode(prefix)
                if (nextCode < maxTableSize) {
                    // h still points to the empty slot from lookup
                    lzwKeys[h] = key
                    lzwVals[h] = nextCode
                    if (nextCode >= (1 shl codeSize) && codeSize < 12) {
                        codeSize++
                    }
                    nextCode++
                } else {
                    emitCode(clearCode)
                    resetTable()
                }
                prefix = suffix
            }
        }

        emitCode(prefix)
        emitCode(eoiCode)
        if (bitCount > 0) block[blockIdx++] = (bitBuffer and 0xFF).toByte()
        flushBlock()
        os.write(0) // block terminator
    }

    private fun writeString(s: String) {
        for (c in s) out?.write(c.code)
    }

    private fun writeLSD() {
        writeShort(width)
        writeShort(height)
        out?.write(0xF7) // GCT flag=1, color res=7, sort=0, GCT size=7 (256 colors)
        out?.write(0) // background color index
        out?.write(0) // pixel aspect ratio
    }

    private fun writePalette() {
        out?.write(colorTab, 0, 768)
    }

    private fun writeNetscapeExt() {
        out?.write(0x21) // extension
        out?.write(0xFF) // app extension label
        out?.write(11)   // block size
        writeString("NETSCAPE2.0")
        out?.write(3)    // sub-block size
        out?.write(1)    // loop sub-block id
        writeShort(repeat)
        out?.write(0)    // block terminator
    }

    /**
     * Write Graphic Control Extension.
     * @param transparentIdx palette index for transparency, or -1 for no transparency.
     * @param disposal disposal method: 1=do not dispose, 2=restore to background.
     */
    private fun writeGraphicCtrlExt(transparentIdx: Int, disposal: Int = 1) {
        out?.write(0x21) // extension
        out?.write(0xF9) // GCE label
        out?.write(4)    // data block size
        // packed: disposal (bits 4-2), user input=0 (bit 1),
        // transparent flag (bit 0) = 1 if transparentIdx >= 0
        val transparentFlag = if (transparentIdx >= 0) 1 else 0
        val packed = (disposal shl 2) or transparentFlag
        out?.write(packed)
        writeShort(delay)
        out?.write(if (transparentIdx >= 0) transparentIdx else 0)
        out?.write(0)    // block terminator
    }

    /**
     * Write Image Descriptor with sub-rect positioning.
     */
    private fun writeImageDesc(left: Int, top: Int, w: Int, h: Int) {
        out?.write(0x2C) // image separator
        writeShort(left)
        writeShort(top)
        writeShort(w)
        writeShort(h)
        out?.write(0)    // no local color table — use global
    }

    private fun writeShort(value: Int) {
        out?.write(value and 0xFF)
        out?.write((value shr 8) and 0xFF)
    }
}
