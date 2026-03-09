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
import com.artmondo.algomodo.generators.Generator
import com.artmondo.algomodo.generators.Quality
import java.io.*
import java.util.Arrays

object GifExporter {

    fun export(
        context: Context,
        generator: Generator,
        params: Map<String, Any>,
        seed: Int,
        palette: Palette,
        quality: Quality,
        resolution: Int,
        durationSeconds: Int,
        fps: Int,
        boomerang: Boolean,
        endless: Boolean,
        fileName: String,
        onProgress: (Float) -> Unit = {}
    ): Uri? {
        val totalFrames = durationSeconds * fps
        val frameDelay = 1000 / fps
        val pixelCount = resolution * resolution

        // Reuse a single Bitmap + Canvas across all frames
        val bitmap = Bitmap.createBitmap(resolution, resolution, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val reusablePixels = IntArray(pixelCount)

        // For boomerang we need to store pixel data of middle frames
        val boomerangFrames: MutableList<IntArray>? =
            if (boomerang && totalFrames > 2) mutableListOf() else null

        val baos = ByteArrayOutputStream()
        val encoder = AnimatedGifEncoder()
        encoder.start(baos)
        encoder.setRepeat(if (boomerang || endless) 0 else -1)
        encoder.setDelay(frameDelay)

        val totalOutputFrames = if (boomerang && totalFrames > 2)
            totalFrames + (totalFrames - 2) else totalFrames

        // Render and encode forward frames
        for (i in 0 until totalFrames) {
            val time = i.toFloat() / fps
            canvas.drawColor(android.graphics.Color.BLACK)
            generator.renderCanvas(canvas, bitmap, params, seed, palette, quality, time)
            bitmap.getPixels(reusablePixels, 0, resolution, 0, 0, resolution, resolution)

            // Store pixel data for boomerang (excluding first and last frame)
            if (boomerangFrames != null && i > 0 && i < totalFrames - 1) {
                boomerangFrames.add(reusablePixels.copyOf())
            }

            encoder.addFrame(bitmap, reusablePixels)
            onProgress(i.toFloat() / totalOutputFrames * 0.9f)
        }

        // Encode reversed boomerang frames from stored pixel data
        if (boomerangFrames != null) {
            for (i in boomerangFrames.size - 1 downTo 0) {
                val px = boomerangFrames[i]
                bitmap.setPixels(px, 0, resolution, 0, 0, resolution, resolution)
                encoder.addFrame(bitmap, px)
                val progress = (totalFrames + (boomerangFrames.size - 1 - i)).toFloat()
                onProgress(progress / totalOutputFrames * 0.9f)
            }
        }

        encoder.finish()
        bitmap.recycle()

        onProgress(0.95f)
        val data = baos.toByteArray()
        return saveGif(context, data, fileName)
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
 * Key optimizations vs naive HashMap-based approach:
 * - IntArray(32768) for color frequency counting (direct indexing, no hashing/boxing)
 * - IntArray(32768) for color→palette cache (persists across frames)
 * - Global palette built once from first frame, reused for all frames
 * - IntArray-based open-addressing hash table for LZW (no HashMap boxing)
 * - All buffers pre-allocated and reused across frames (zero GC pressure)
 */
class AnimatedGifEncoder {
    private var width = 0
    private var height = 0
    private var repeat = -1
    private var delay = 0 // centiseconds
    private var started = false
    private var out: OutputStream? = null
    private var firstFrame = true
    private var sizeSet = false

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

    // --- LZW encoder buffers (pre-allocated, reused across frames) ---
    private val lzwKeys = IntArray(LZW_HASH_SIZE)
    private val lzwVals = IntArray(LZW_HASH_SIZE)
    private val lzwBlock = ByteArray(255)

    companion object {
        private const val LZW_HASH_SIZE = 5003
    }

    fun setDelay(ms: Int) { delay = ms / 10 }
    fun setRepeat(iter: Int) { repeat = iter }

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
        colorToIndex.fill(-1)
        return true
    }

    fun addFrame(im: Bitmap): Boolean = addFrame(im, null)

    fun addFrame(im: Bitmap, existingPixels: IntArray?): Boolean {
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
            }
            writeGraphicCtrlExt()
            writeImageDesc()
            writePixelsLZW()
            firstFrame = false
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
     * LZW-compress indexed pixels and write to output stream.
     * Uses pre-allocated IntArray open-addressing hash table instead of HashMap.
     */
    private fun writePixelsLZW() {
        val os = out ?: return
        val pixels = indexedPixels ?: return
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

        if (pixels.isEmpty()) {
            emitCode(eoiCode)
            if (bitCount > 0) block[blockIdx++] = (bitBuffer and 0xFF).toByte()
            flushBlock()
            os.write(0)
            return
        }

        var prefix = pixels[0].toInt() and 0xFF

        for (i in 1 until pixels.size) {
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

    private fun writeGraphicCtrlExt() {
        out?.write(0x21) // extension
        out?.write(0xF9) // GCE label
        out?.write(4)    // data block size
        out?.write(0)    // packed: disposal=0, no user input, no transparency
        writeShort(delay)
        out?.write(0)    // transparent color index
        out?.write(0)    // block terminator
    }

    private fun writeImageDesc() {
        out?.write(0x2C) // image separator
        writeShort(0)    // left
        writeShort(0)    // top
        writeShort(width)
        writeShort(height)
        out?.write(0)    // no local color table — use global
    }

    private fun writeShort(value: Int) {
        out?.write(value and 0xFF)
        out?.write((value shr 8) and 0xFF)
    }
}
