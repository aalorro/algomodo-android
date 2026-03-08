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

        val frames = mutableListOf<Bitmap>()

        // Render frames
        for (i in 0 until totalFrames) {
            val time = i.toFloat() / fps
            val bitmap = Bitmap.createBitmap(resolution, resolution, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            generator.renderCanvas(canvas, bitmap, params, seed, palette, quality, time)
            frames.add(bitmap)
            onProgress(i.toFloat() / totalFrames * 0.7f)
        }

        // If boomerang, append reversed frames (excluding first and last)
        if (boomerang && frames.size > 2) {
            for (i in frames.size - 2 downTo 1) {
                frames.add(frames[i].copy(frames[i].config!!, false))
            }
        }

        // Encode GIF
        val baos = ByteArrayOutputStream()
        val encoder = AnimatedGifEncoder()
        encoder.start(baos)
        encoder.setRepeat(if (boomerang || endless) 0 else -1)
        encoder.setDelay(frameDelay)

        for ((idx, frame) in frames.withIndex()) {
            encoder.addFrame(frame)
            onProgress(0.7f + (idx.toFloat() / frames.size) * 0.3f)
        }
        encoder.finish()

        frames.forEach { it.recycle() }

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
 * Animated GIF encoder with proper LZW compression and color quantization.
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
    private var colorTab: ByteArray? = null
    private var indexedPixels: ByteArray? = null

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
        return true
    }

    fun addFrame(im: Bitmap): Boolean {
        if (!started) return false
        try {
            if (!sizeSet) {
                width = im.width
                height = im.height
                sizeSet = true
            }
            val pixels = extractPixels(im)
            quantizePixels(pixels)
            if (firstFrame) {
                writeLSD()
                writePalette()
                if (repeat >= 0) writeNetscapeExt()
            }
            writeGraphicCtrlExt()
            writeImageDesc()
            if (!firstFrame) writePalette()
            writePixels()
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

    private fun quantizePixels(pixels: IntArray) {
        val nPix = pixels.size

        // Count color frequencies using 15-bit quantization (5 bits per channel)
        val colorFreq = HashMap<Int, Int>(minOf(nPix, 32768))
        for (c in pixels) {
            val r = ((c shr 16) and 0xFF) shr 3
            val g = ((c shr 8) and 0xFF) shr 3
            val b = (c and 0xFF) shr 3
            val key = (r shl 10) or (g shl 5) or b
            colorFreq[key] = (colorFreq[key] ?: 0) + 1
        }

        // Pick top 256 most frequent colors
        val topColors = colorFreq.entries
            .sortedByDescending { it.value }
            .take(256)
            .map { it.key }

        // Build GIF color table (expand 5-bit back to 8-bit)
        colorTab = ByteArray(256 * 3)
        for ((idx, color) in topColors.withIndex()) {
            val r = ((color shr 10) and 0x1F) * 255 / 31
            val g = ((color shr 5) and 0x1F) * 255 / 31
            val b = (color and 0x1F) * 255 / 31
            colorTab!![idx * 3] = r.toByte()
            colorTab!![idx * 3 + 1] = g.toByte()
            colorTab!![idx * 3 + 2] = b.toByte()
        }

        // Build reverse lookup for quantized colors that are in the palette
        val colorToIndex = HashMap<Int, Int>(topColors.size * 2)
        for ((idx, color) in topColors.withIndex()) {
            colorToIndex[color] = idx
        }

        // Map each pixel to nearest palette index
        indexedPixels = ByteArray(nPix)
        for (i in pixels.indices) {
            val c = pixels[i]
            val r = ((c shr 16) and 0xFF) shr 3
            val g = ((c shr 8) and 0xFF) shr 3
            val b = (c and 0xFF) shr 3
            val key = (r shl 10) or (g shl 5) or b

            val cached = colorToIndex[key]
            if (cached != null) {
                indexedPixels!![i] = cached.toByte()
            } else {
                // Find nearest color in palette
                var bestIdx = 0
                var bestDist = Int.MAX_VALUE
                for ((pIdx, pColor) in topColors.withIndex()) {
                    val pr = (pColor shr 10) and 0x1F
                    val pg = (pColor shr 5) and 0x1F
                    val pb = pColor and 0x1F
                    val dist = (r - pr) * (r - pr) + (g - pg) * (g - pg) + (b - pb) * (b - pb)
                    if (dist < bestDist) {
                        bestDist = dist
                        bestIdx = pIdx
                    }
                }
                colorToIndex[key] = bestIdx
                indexedPixels!![i] = bestIdx.toByte()
            }
        }
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
        out?.write(colorTab!!, 0, colorTab!!.size)
        // Pad to 256 entries if needed
        val pad = 256 * 3 - colorTab!!.size
        for (i in 0 until pad) out?.write(0)
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
        if (firstFrame) {
            out?.write(0) // no local color table (use global)
        } else {
            out?.write(0x87) // local color table flag=1, size=7 (256 colors)
        }
    }

    private fun writePixels() {
        LZWEncoder(indexedPixels!!, 8).encode(out!!)
    }

    private fun writeShort(value: Int) {
        out?.write(value and 0xFF)
        out?.write((value shr 8) and 0xFF)
    }
}

/**
 * Proper LZW encoder for GIF image data.
 */
class LZWEncoder(
    private val pixels: ByteArray,
    private val colorDepth: Int
) {
    fun encode(os: OutputStream) {
        val minCodeSize = maxOf(2, colorDepth)
        os.write(minCodeSize) // write LZW minimum code size

        val clearCode = 1 shl minCodeSize
        val eoiCode = clearCode + 1
        val maxTableSize = 4096

        var codeSize = minCodeSize + 1
        var nextCode = eoiCode + 1

        // String table: maps (prefix_code, suffix_byte) -> new_code
        // Key = (prefix << 8) | suffix — safe because suffix is 0-255
        // and prefix < 4096, so max key = (4095 << 8)|255 = 1,048,575
        var table = HashMap<Int, Int>(5003)

        // Bit packing into sub-blocks
        var bitBuffer = 0
        var bitCount = 0
        val block = ByteArray(255)
        var blockIdx = 0

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
            table.clear()
            nextCode = eoiCode + 1
            codeSize = minCodeSize + 1
        }

        // Begin with clear code
        emitCode(clearCode)

        if (pixels.isEmpty()) {
            emitCode(eoiCode)
            if (bitCount > 0) {
                block[blockIdx++] = (bitBuffer and 0xFF).toByte()
            }
            flushBlock()
            os.write(0)
            return
        }

        var prefix = pixels[0].toInt() and 0xFF

        for (i in 1 until pixels.size) {
            val suffix = pixels[i].toInt() and 0xFF
            val key = (prefix shl 8) or suffix

            val existing = table[key]
            if (existing != null) {
                prefix = existing
            } else {
                emitCode(prefix)

                if (nextCode < maxTableSize) {
                    table[key] = nextCode
                    // Check if we need to increase code size AFTER adding
                    if (nextCode >= (1 shl codeSize) && codeSize < 12) {
                        codeSize++
                    }
                    nextCode++
                } else {
                    // Table full — emit clear code and reset
                    emitCode(clearCode)
                    resetTable()
                }

                prefix = suffix
            }
        }

        // Emit the final prefix code
        emitCode(prefix)
        emitCode(eoiCode)

        // Flush remaining bits
        if (bitCount > 0) {
            block[blockIdx++] = (bitBuffer and 0xFF).toByte()
        }
        flushBlock()
        os.write(0) // block terminator
    }
}
