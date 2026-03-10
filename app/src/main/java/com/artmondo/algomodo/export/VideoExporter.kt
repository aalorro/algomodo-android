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

            // Set up EGL so we can set presentation timestamps
            val eglHelper = EglHelper(inputSurface)

            codec.start()

            val muxer = MediaMuxer(tempFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var trackIndex = -1
            var muxerStarted = false

            val totalFrames = durationSeconds * fps
            val frameIntervalNs = 1_000_000_000L / fps
            val bufferInfo = MediaCodec.BufferInfo()

            // Offscreen bitmap for generator rendering
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val bitmapCanvas = Canvas(bitmap)

            // GL texture + renderer for uploading bitmap to EGL surface
            val glRenderer = GlBitmapRenderer(width, height)

            for (frameIdx in 0 until totalFrames) {
                val time = frameIdx.toFloat() / fps

                // Render frame to bitmap
                bitmapCanvas.drawColor(android.graphics.Color.BLACK)
                generator.renderCanvas(bitmapCanvas, bitmap, params, seed, palette, quality, time)

                // Draw bitmap via GL onto the EGL surface
                glRenderer.draw(bitmap)

                // Set exact presentation timestamp and swap
                val presentationTimeNs = frameIdx.toLong() * frameIntervalNs
                eglHelper.setPresentationTime(presentationTimeNs)
                eglHelper.swapBuffers()

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

            glRenderer.release()
            bitmap.recycle()
            codec.stop()
            codec.release()
            eglHelper.release()
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

    /**
     * Manages an EGL context bound to a Surface for setting presentation timestamps.
     */
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

    /**
     * Uploads a Bitmap to the current EGL surface via a GL texture.
     */
    private class GlBitmapRenderer(private val width: Int, private val height: Int) {
        private val texId: Int
        private val program: Int

        private val vertexBuffer = ByteBuffer.allocateDirect(64)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
                // x, y, u, v — fullscreen quad as triangle strip
                put(floatArrayOf(
                    -1f, -1f, 0f, 0f,
                    1f, -1f, 1f, 0f,
                    -1f, 1f, 0f, 1f,
                    1f, 1f, 1f, 1f
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
