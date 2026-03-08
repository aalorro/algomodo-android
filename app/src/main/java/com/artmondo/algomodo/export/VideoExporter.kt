package com.artmondo.algomodo.export

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.view.Surface
import com.artmondo.algomodo.data.palettes.Palette
import com.artmondo.algomodo.generators.Generator
import com.artmondo.algomodo.generators.Quality
import java.io.File

object VideoExporter {

    fun export(
        context: Context,
        generator: Generator,
        params: Map<String, Any>,
        seed: Int,
        palette: Palette,
        quality: Quality,
        width: Int = 1080,
        height: Int = 1080,
        fps: Int = 30,
        durationSeconds: Int = 5,
        fileName: String,
        onProgress: (Float) -> Unit = {}
    ): Uri? {
        val tempFile = File(context.cacheDir, "$fileName.mp4")

        try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, 8_000_000)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }

            val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val inputSurface = codec.createInputSurface()
            codec.start()

            val muxer = MediaMuxer(tempFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var trackIndex = -1
            var muxerStarted = false

            val totalFrames = durationSeconds * fps
            val bufferInfo = MediaCodec.BufferInfo()

            // Offscreen bitmap for generator rendering
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val bitmapCanvas = Canvas(bitmap)

            for (frameIdx in 0 until totalFrames) {
                val time = frameIdx.toFloat() / fps

                // Render frame to bitmap
                bitmapCanvas.drawColor(android.graphics.Color.BLACK)
                generator.renderCanvas(bitmapCanvas, bitmap, params, seed, palette, quality, time)

                // Draw bitmap onto the encoder's input surface
                val surfaceCanvas = inputSurface.lockHardwareCanvas()
                surfaceCanvas.drawBitmap(bitmap, 0f, 0f, null)
                inputSurface.unlockCanvasAndPost(surfaceCanvas)

                // Drain output
                drainEncoder(codec, muxer, bufferInfo, trackIndex, muxerStarted).let {
                    trackIndex = it.first
                    muxerStarted = it.second
                }

                onProgress(frameIdx.toFloat() / totalFrames)
            }

            // Signal end of stream
            codec.signalEndOfInputStream()

            // Drain remaining
            drainEncoder(codec, muxer, bufferInfo, trackIndex, muxerStarted, drain = true)

            bitmap.recycle()
            codec.stop()
            codec.release()
            inputSurface.release()
            if (muxerStarted) {
                muxer.stop()
            }
            muxer.release()

            // Move to gallery
            return saveToGallery(context, tempFile, fileName)
        } catch (e: Exception) {
            android.util.Log.e("VideoExporter", "Export failed", e)
            tempFile.delete()
            return null
        }
    }

    private fun drainEncoder(
        codec: MediaCodec,
        muxer: MediaMuxer,
        bufferInfo: MediaCodec.BufferInfo,
        trackIdx: Int,
        muxerStarted: Boolean,
        drain: Boolean = false
    ): Pair<Int, Boolean> {
        var trackIndex = trackIdx
        var started = muxerStarted
        val timeout = if (drain) 10_000L else 0L

        while (true) {
            val outputBufIdx = codec.dequeueOutputBuffer(bufferInfo, timeout)
            when {
                outputBufIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    trackIndex = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    started = true
                }
                outputBufIdx >= 0 -> {
                    val outputBuf = codec.getOutputBuffer(outputBufIdx) ?: break
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        bufferInfo.size = 0
                    }
                    if (bufferInfo.size > 0 && started) {
                        outputBuf.position(bufferInfo.offset)
                        outputBuf.limit(bufferInfo.offset + bufferInfo.size)
                        muxer.writeSampleData(trackIndex, outputBuf, bufferInfo)
                    }
                    codec.releaseOutputBuffer(outputBufIdx, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
                else -> break
            }
        }
        return Pair(trackIndex, started)
    }

    private fun saveToGallery(context: Context, tempFile: File, fileName: String): Uri? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, "$fileName.mp4")
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/Algomodo")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            uri?.let {
                context.contentResolver.openOutputStream(it)?.use { os ->
                    tempFile.inputStream().use { input -> input.copyTo(os) }
                }
                values.clear()
                values.put(MediaStore.Video.Media.IS_PENDING, 0)
                context.contentResolver.update(it, values, null, null)
            }
            tempFile.delete()
            uri
        } else {
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                "Algomodo"
            )
            dir.mkdirs()
            val dest = File(dir, "$fileName.mp4")
            tempFile.copyTo(dest, overwrite = true)
            tempFile.delete()
            Uri.fromFile(dest)
        }
    }
}
