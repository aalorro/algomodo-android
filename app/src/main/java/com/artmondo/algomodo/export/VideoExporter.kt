package com.artmondo.algomodo.export

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.opengl.*
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.view.Surface
import com.artmondo.algomodo.data.palettes.Palette
import com.artmondo.algomodo.generators.Generator
import com.artmondo.algomodo.generators.Quality
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

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
        timeOffsetSec: Float = 0f,
        audioUri: Uri? = null,
        audioStartSec: Float = 0f,
        fileName: String,
        onProgress: (Float) -> Unit = {}
    ): Uri? {
        val videoTempFile = File(context.cacheDir, "$fileName.mp4")

        // Copy audio to temp file immediately (before the long encode) to avoid
        // content URI permission expiry and to guarantee file-path access for muxing
        var audioTempFile: File? = null
        if (audioUri != null) {
            try {
                audioTempFile = File(context.cacheDir, "${fileName}_audio_src")
                context.contentResolver.openInputStream(audioUri)?.use { input ->
                    audioTempFile!!.outputStream().use { output -> input.copyTo(output) }
                }
            } catch (e: Exception) {
                android.util.Log.e("VideoExporter", "Failed to copy audio source", e)
                audioTempFile?.delete()
                audioTempFile = null
            }
        }

        try {
            // ── Pass 1: Encode video ──
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, 8_000_000)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }

            val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val inputSurface = codec.createInputSurface()

            val eglHelper = EglHelper(inputSurface)
            codec.start()

            val muxer = MediaMuxer(videoTempFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var trackIndex = -1
            var muxerStarted = false

            val totalFrames = durationSeconds * fps
            val frameIntervalNs = 1_000_000_000L / fps
            val bufferInfo = MediaCodec.BufferInfo()

            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val bitmapCanvas = Canvas(bitmap)
            val glRenderer = GlBitmapRenderer(width, height)

            for (frameIdx in 0 until totalFrames) {
                val time = timeOffsetSec + frameIdx.toFloat() / fps

                bitmapCanvas.drawColor(android.graphics.Color.BLACK)
                generator.renderCanvas(bitmapCanvas, bitmap, params, seed, palette, quality, time)

                glRenderer.draw(bitmap)

                val presentationTimeNs = frameIdx.toLong() * frameIntervalNs
                eglHelper.setPresentationTime(presentationTimeNs)
                eglHelper.swapBuffers()

                drainEncoder(codec, muxer, bufferInfo, trackIndex, muxerStarted).let {
                    trackIndex = it.first
                    muxerStarted = it.second
                }

                onProgress(frameIdx.toFloat() / totalFrames)
            }

            codec.signalEndOfInputStream()
            drainEncoder(codec, muxer, bufferInfo, trackIndex, muxerStarted, drain = true)

            glRenderer.release()
            bitmap.recycle()
            codec.stop()
            codec.release()
            eglHelper.release()
            inputSurface.release()
            if (muxerStarted) muxer.stop()
            muxer.release()

            // ── Pass 2: Mux audio if available ──
            val finalFile = if (audioTempFile != null && audioTempFile.exists()) {
                muxAudio(videoTempFile, audioTempFile, audioStartSec, durationSeconds) ?: videoTempFile
            } else {
                videoTempFile
            }

            val result = saveToGallery(context, finalFile, fileName)
            // Clean up temp files
            if (finalFile != videoTempFile) videoTempFile.delete()
            audioTempFile?.delete()
            return result
        } catch (e: Exception) {
            android.util.Log.e("VideoExporter", "Export failed", e)
            videoTempFile.delete()
            audioTempFile?.delete()
            return null
        }
    }

    /**
     * Mux audio track from [audioFile] into [videoFile], trimming audio to
     * [audioStartSec]..[audioStartSec + videoDurationSec].
     * Transcodes non-AAC audio to AAC first (MP4 muxer only supports AAC).
     * Returns the muxed file, or null on failure (caller falls back to video-only).
     */
    private fun muxAudio(
        videoFile: File,
        audioFile: File,
        audioStartSec: Float,
        videoDurationSec: Int
    ): File? {
        val muxedFile = File(videoFile.parent, videoFile.nameWithoutExtension + "_muxed.mp4")
        try {
            // First, prepare AAC audio (transcode if needed)
            val aacFile = File(videoFile.parent, videoFile.nameWithoutExtension + "_aac.m4a")
            val aacReady = prepareAacAudio(audioFile, aacFile, audioStartSec, videoDurationSec)
            if (!aacReady) {
                android.util.Log.e("VideoExporter", "Failed to prepare AAC audio")
                aacFile.delete()
                return null
            }

            // Extract video track from encoded video
            val videoExtractor = MediaExtractor()
            videoExtractor.setDataSource(videoFile.absolutePath)
            val videoTrackIdx = findTrack(videoExtractor, "video/")
            if (videoTrackIdx < 0) { videoExtractor.release(); aacFile.delete(); return null }
            videoExtractor.selectTrack(videoTrackIdx)
            val videoFormat = videoExtractor.getTrackFormat(videoTrackIdx)

            // Extract AAC audio track from transcoded file
            val audioExtractor = MediaExtractor()
            audioExtractor.setDataSource(aacFile.absolutePath)
            val audioTrackIdx = findTrack(audioExtractor, "audio/")
            if (audioTrackIdx < 0) {
                android.util.Log.e("VideoExporter", "No audio track in AAC file")
                videoExtractor.release(); audioExtractor.release(); aacFile.delete(); return null
            }
            audioExtractor.selectTrack(audioTrackIdx)
            val audioFormat = audioExtractor.getTrackFormat(audioTrackIdx)

            // Create muxer with both tracks
            val muxer = MediaMuxer(muxedFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val muxVideoTrack = muxer.addTrack(videoFormat)
            val muxAudioTrack = muxer.addTrack(audioFormat)
            muxer.start()

            val buf = ByteBuffer.allocate(1024 * 1024)
            val info = MediaCodec.BufferInfo()

            // Copy video samples
            while (true) {
                val size = videoExtractor.readSampleData(buf, 0)
                if (size < 0) break
                info.offset = 0
                info.size = size
                info.presentationTimeUs = videoExtractor.sampleTime
                info.flags = videoExtractor.sampleFlags
                muxer.writeSampleData(muxVideoTrack, buf, info)
                videoExtractor.advance()
            }

            // Copy audio samples (already trimmed by prepareAacAudio)
            while (true) {
                val size = audioExtractor.readSampleData(buf, 0)
                if (size < 0) break
                info.offset = 0
                info.size = size
                info.presentationTimeUs = audioExtractor.sampleTime
                info.flags = audioExtractor.sampleFlags
                muxer.writeSampleData(muxAudioTrack, buf, info)
                audioExtractor.advance()
            }

            muxer.stop()
            muxer.release()
            videoExtractor.release()
            audioExtractor.release()
            aacFile.delete()

            return muxedFile
        } catch (e: Exception) {
            android.util.Log.e("VideoExporter", "Audio muxing failed, falling back to video-only", e)
            muxedFile.delete()
            return null
        }
    }

    /**
     * Decode any audio format to PCM, then encode to AAC and write to [outputFile].
     * Trims to [startSec]..[startSec + durationSec].
     * If the source is already AAC, copies the relevant samples directly.
     */
    private fun prepareAacAudio(
        inputFile: File,
        outputFile: File,
        startSec: Float,
        durationSec: Int
    ): Boolean {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(inputFile.absolutePath)
        } catch (e: Exception) {
            android.util.Log.e("VideoExporter", "Can't read audio file", e)
            return false
        }

        val trackIdx = findTrack(extractor, "audio/")
        if (trackIdx < 0) {
            android.util.Log.e("VideoExporter", "No audio track found")
            extractor.release()
            return false
        }
        extractor.selectTrack(trackIdx)
        val srcFormat = extractor.getTrackFormat(trackIdx)
        val srcMime = srcFormat.getString(MediaFormat.KEY_MIME) ?: ""
        val sampleRate = srcFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val channelCount = srcFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        android.util.Log.d("VideoExporter", "Source audio: mime=$srcMime rate=$sampleRate ch=$channelCount")

        val startUs = (startSec * 1_000_000f).toLong()
        val endUs = startUs + durationSec * 1_000_000L

        // If already AAC, just remux the trimmed range into an M4A container
        if (srcMime == MediaFormat.MIMETYPE_AUDIO_AAC) {
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val muxTrack = muxer.addTrack(srcFormat)
            muxer.start()
            val buf = ByteBuffer.allocate(65536)
            val info = MediaCodec.BufferInfo()
            while (true) {
                val size = extractor.readSampleData(buf, 0)
                if (size < 0) break
                val ts = extractor.sampleTime
                val adjusted = ts - startUs
                if (adjusted < 0) { extractor.advance(); continue }
                if (adjusted > endUs - startUs) break
                info.offset = 0
                info.size = size
                info.presentationTimeUs = adjusted
                info.flags = extractor.sampleFlags
                muxer.writeSampleData(muxTrack, buf, info)
                extractor.advance()
            }
            muxer.stop()
            muxer.release()
            extractor.release()
            return true
        }

        // Non-AAC: transcode via decode → PCM → encode AAC
        extractor.seekTo(startUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

        // Set up decoder
        val decoder = MediaCodec.createDecoderByType(srcMime)
        decoder.configure(srcFormat, null, null, 0)
        decoder.start()

        // Set up AAC encoder
        val encFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channelCount).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        encoder.configure(encFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoder.start()

        // Output muxer for the AAC file
        val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var muxTrack = -1
        var muxerStarted = false

        val decInfo = MediaCodec.BufferInfo()
        val encInfo = MediaCodec.BufferInfo()
        var extractorDone = false
        var decoderDone = false
        var encoderDone = false
        val timeoutUs = 10_000L

        // Intermediate PCM buffer for feeding encoder
        var pendingPcm: ByteArray? = null
        var pcmOffset = 0
        var pcmTimestampUs = 0L
        val bytesPerFrame = 2 * channelCount // 16-bit PCM

        while (!encoderDone) {
            // Step 1: Feed extractor → decoder
            if (!extractorDone) {
                val inIdx = decoder.dequeueInputBuffer(timeoutUs)
                if (inIdx >= 0) {
                    val buf = decoder.getInputBuffer(inIdx)!!
                    val size = extractor.readSampleData(buf, 0)
                    if (size < 0 || extractor.sampleTime > endUs) {
                        decoder.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        extractorDone = true
                    } else {
                        val ts = extractor.sampleTime - startUs
                        decoder.queueInputBuffer(inIdx, 0, size, ts.coerceAtLeast(0), 0)
                        extractor.advance()
                    }
                }
            }

            // Step 2: Drain decoder → collect PCM → feed encoder
            if (!decoderDone) {
                val outIdx = decoder.dequeueOutputBuffer(decInfo, timeoutUs)
                if (outIdx >= 0) {
                    if (decInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        decoderDone = true
                        decoder.releaseOutputBuffer(outIdx, false)
                        // Signal EOS to encoder
                        val encInIdx = encoder.dequeueInputBuffer(timeoutUs)
                        if (encInIdx >= 0) {
                            encoder.queueInputBuffer(encInIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        }
                    } else if (decInfo.size > 0) {
                        val pcmBuf = decoder.getOutputBuffer(outIdx)!!
                        pcmBuf.position(decInfo.offset)
                        val chunk = ByteArray(decInfo.size)
                        pcmBuf.get(chunk)
                        decoder.releaseOutputBuffer(outIdx, false)

                        // Feed decoded PCM to encoder
                        var chunkOff = 0
                        while (chunkOff < chunk.size) {
                            val encInIdx = encoder.dequeueInputBuffer(timeoutUs)
                            if (encInIdx < 0) break
                            val encBuf = encoder.getInputBuffer(encInIdx)!!
                            encBuf.clear()
                            val toCopy = minOf(chunk.size - chunkOff, encBuf.remaining())
                            encBuf.put(chunk, chunkOff, toCopy)
                            val frameDurationUs = (toCopy.toLong() * 1_000_000L) / (bytesPerFrame * sampleRate)
                            encoder.queueInputBuffer(encInIdx, 0, toCopy, pcmTimestampUs, 0)
                            pcmTimestampUs += frameDurationUs
                            chunkOff += toCopy
                        }
                    } else {
                        decoder.releaseOutputBuffer(outIdx, false)
                    }
                }
            }

            // Step 3: Drain encoder → muxer
            val encOutIdx = encoder.dequeueOutputBuffer(encInfo, timeoutUs)
            when {
                encOutIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    muxTrack = muxer.addTrack(encoder.outputFormat)
                    muxer.start()
                    muxerStarted = true
                }
                encOutIdx >= 0 -> {
                    if (encInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        // skip codec config
                    } else if (encInfo.size > 0 && muxerStarted) {
                        val outBuf = encoder.getOutputBuffer(encOutIdx)!!
                        outBuf.position(encInfo.offset)
                        outBuf.limit(encInfo.offset + encInfo.size)
                        muxer.writeSampleData(muxTrack, outBuf, encInfo)
                    }
                    encoder.releaseOutputBuffer(encOutIdx, false)
                    if (encInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        encoderDone = true
                    }
                }
            }
        }

        decoder.stop(); decoder.release()
        encoder.stop(); encoder.release()
        extractor.release()
        if (muxerStarted) muxer.stop()
        muxer.release()

        return muxerStarted
    }

    private fun findTrack(extractor: MediaExtractor, mimePrefix: String): Int {
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith(mimePrefix)) return i
        }
        return -1
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

    private class EglHelper(surface: Surface) {
        private val eglDisplay: EGLDisplay
        private val eglContext: EGLContext
        private val eglSurface: EGLSurface

        init {
            eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            val version = IntArray(2)
            EGL14.eglInitialize(eglDisplay, version, 0, version, 1)

            val configAttribs = intArrayOf(
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
                EGL14.EGL_NONE
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val numConfigs = IntArray(1)
            EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, numConfigs, 0)

            val contextAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
            eglContext = EGL14.eglCreateContext(eglDisplay, configs[0]!!, EGL14.EGL_NO_CONTEXT, contextAttribs, 0)

            val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
            eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, configs[0]!!, surface, surfaceAttribs, 0)

            EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
        }

        fun setPresentationTime(nsecs: Long) {
            EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, nsecs)
        }

        fun swapBuffers() {
            EGL14.eglSwapBuffers(eglDisplay, eglSurface)
        }

        fun release() {
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(eglDisplay, eglSurface)
            EGL14.eglDestroyContext(eglDisplay, eglContext)
            EGL14.eglTerminate(eglDisplay)
        }
    }

    private class GlBitmapRenderer(private val width: Int, private val height: Int) {
        private val texId: Int
        private val program: Int

        private val vertexBuffer = ByteBuffer.allocateDirect(64)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
                put(floatArrayOf(
                    -1f, -1f, 0f, 1f,
                    1f, -1f, 1f, 1f,
                    -1f, 1f, 0f, 0f,
                    1f, 1f, 1f, 0f
                ))
                position(0)
            }

        init {
            val texIds = IntArray(1)
            GLES20.glGenTextures(1, texIds, 0)
            texId = texIds[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)

            val vs = compileShader(GLES20.GL_VERTEX_SHADER, """
                attribute vec4 aPos;
                attribute vec2 aUV;
                varying vec2 vUV;
                void main() {
                    gl_Position = vec4(aPos.xy, 0.0, 1.0);
                    vUV = aUV;
                }
            """.trimIndent())
            val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, """
                precision mediump float;
                varying vec2 vUV;
                uniform sampler2D uTex;
                void main() {
                    gl_FragColor = texture2D(uTex, vUV);
                }
            """.trimIndent())
            program = GLES20.glCreateProgram()
            GLES20.glAttachShader(program, vs)
            GLES20.glAttachShader(program, fs)
            GLES20.glLinkProgram(program)
        }

        fun draw(bitmap: Bitmap) {
            GLES20.glViewport(0, 0, width, height)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)

            GLES20.glUseProgram(program)
            val aPos = GLES20.glGetAttribLocation(program, "aPos")
            val aUV = GLES20.glGetAttribLocation(program, "aUV")

            vertexBuffer.position(0)
            GLES20.glEnableVertexAttribArray(aPos)
            GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 16, vertexBuffer)

            vertexBuffer.position(2)
            GLES20.glEnableVertexAttribArray(aUV)
            GLES20.glVertexAttribPointer(aUV, 2, GLES20.GL_FLOAT, false, 16, vertexBuffer)

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }

        fun release() {
            GLES20.glDeleteTextures(1, intArrayOf(texId), 0)
            GLES20.glDeleteProgram(program)
        }

        private fun compileShader(type: Int, source: String): Int {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)
            return shader
        }
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
