package com.artmondo.algomodo.rendering.gl

import android.graphics.Bitmap
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES30
import com.artmondo.algomodo.audio.AudioAnalysis
import com.artmondo.algomodo.data.palettes.Palette
import com.artmondo.algomodo.generators.GpuGenerator
import com.artmondo.algomodo.generators.Quality
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Per-thread offscreen GPU shader runner.
 *
 * Owns a private EGL context backed by a 1x1 pbuffer (the actual draw target
 * is a colour-texture-backed FBO sized to match the requested bitmap). Renders
 * a generator's fragment shader fullscreen, then reads pixels back into the
 * caller-provided Bitmap.
 *
 * Save/restore EGL state on every render so this can safely be invoked from
 * inside another EGL context (e.g. MediaCodec input surface used by
 * VideoExporter).
 *
 * Use [forCurrentThread] to obtain the per-thread instance — it is lazily
 * created and cached in a ThreadLocal.
 */
class GpuShaderRunner private constructor() {

    private val eglDisplay: EGLDisplay
    private val eglContext: EGLContext
    private val eglPbuffer: EGLSurface

    // FBO + colour texture, lazily resized to match the requested bitmap.
    private var fboId = 0
    private var texId = 0
    private var fboWidth = 0
    private var fboHeight = 0

    // Fullscreen-triangle vertex buffer (covers the viewport with no UVs needed —
    // the fragment shader uses gl_FragCoord directly).
    private val vertexBuffer = ByteBuffer.allocateDirect(6 * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            // A single triangle that covers the entire clip-space viewport,
            // including a Y flip so glReadPixels output matches bitmap orientation.
            put(floatArrayOf(
                -1f, 3f,
                -1f, -1f,
                3f, -1f
            ))
            position(0)
        }

    private val programCache = HashMap<Int, CompiledProgram>(8)

    private val readPixelBuf = ThreadLocal<ByteBuffer>()

    init {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(eglDisplay != EGL14.EGL_NO_DISPLAY) { "EGL get display failed" }
        val version = IntArray(2)
        check(EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) { "EGL init failed" }

        val configAttribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT or 0x40, // include ES3 bit
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        check(EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, numConfigs, 0)
                && numConfigs[0] > 0) { "EGL no config" }

        val contextAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE)
        eglContext = EGL14.eglCreateContext(eglDisplay, configs[0]!!, EGL14.EGL_NO_CONTEXT, contextAttribs, 0)
        check(eglContext != EGL14.EGL_NO_CONTEXT) { "EGL create context failed" }

        val pbufferAttribs = intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE)
        eglPbuffer = EGL14.eglCreatePbufferSurface(eglDisplay, configs[0]!!, pbufferAttribs, 0)
        check(eglPbuffer != EGL14.EGL_NO_SURFACE) { "EGL create pbuffer failed" }
    }

    /**
     * Render the given GpuGenerator's fragment shader into [bitmap].
     *
     * Saves the calling thread's current EGL context (if any), switches to this
     * runner's own context, renders, reads pixels back, then restores.
     */
    fun render(
        generator: GpuGenerator,
        bitmap: Bitmap,
        params: Map<String, Any>,
        seed: Int,
        palette: Palette,
        quality: Quality,
        time: Float
    ) {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0) return

        // Save any pre-existing EGL context the caller has bound.
        val priorDisplay = EGL14.eglGetCurrentDisplay()
        val priorContext = EGL14.eglGetCurrentContext()
        val priorDrawSurf = EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW)
        val priorReadSurf = EGL14.eglGetCurrentSurface(EGL14.EGL_READ)

        try {
            check(EGL14.eglMakeCurrent(eglDisplay, eglPbuffer, eglPbuffer, eglContext)) {
                "EGL make current failed"
            }

            ensureFbo(width, height)
            val program = getProgram(generator)

            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fboId)
            GLES30.glViewport(0, 0, width, height)
            GLES30.glClearColor(0f, 0f, 0f, 1f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)

            GLES30.glUseProgram(program.id)

            // Bind built-in uniforms
            bindBuiltins(program, width, height, time, palette, params)

            // Let the generator bind its custom uniforms
            generator.bindUniforms(program.id, params, seed, palette, quality, time, width, height)

            // Draw fullscreen triangle
            vertexBuffer.position(0)
            GLES30.glEnableVertexAttribArray(program.aPos)
            GLES30.glVertexAttribPointer(program.aPos, 2, GLES30.GL_FLOAT, false, 8, vertexBuffer)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
            GLES30.glDisableVertexAttribArray(program.aPos)

            // Read pixels back to bitmap
            val pixelByteCount = width * height * 4
            var buf = readPixelBuf.get()
            if (buf == null || buf.capacity() < pixelByteCount) {
                buf = ByteBuffer.allocateDirect(pixelByteCount).order(ByteOrder.nativeOrder())
                readPixelBuf.set(buf)
            }
            buf.position(0)
            GLES30.glReadPixels(0, 0, width, height, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf)
            buf.position(0)
            bitmap.copyPixelsFromBuffer(buf)

            // Unbind for cleanliness
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        } finally {
            // Restore the prior EGL state (or fully unbind if none was set).
            if (priorContext == EGL14.EGL_NO_CONTEXT || priorDisplay == EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            } else {
                EGL14.eglMakeCurrent(priorDisplay, priorDrawSurf, priorReadSurf, priorContext)
            }
        }
    }

    private fun bindBuiltins(
        program: CompiledProgram,
        width: Int,
        height: Int,
        time: Float,
        palette: Palette,
        params: Map<String, Any>
    ) {
        if (program.uResolution >= 0) {
            GLES30.glUniform2f(program.uResolution, width.toFloat(), height.toFloat())
        }
        if (program.uTime >= 0) {
            GLES30.glUniform1f(program.uTime, time)
        }
        if (program.uPalette >= 0) {
            val pf = PaletteUniform.toUniformFloats(palette)
            GLES30.glUniform3fv(program.uPalette, 5, pf, 0)
        }
        if (program.uAudio >= 0) {
            val audio = params["_audioAnalysis"] as? AudioAnalysis
            val bass: Float; val mid: Float; val high: Float
            if (audio != null) {
                bass = audio.getBass(time)
                mid = audio.getMid(time)
                high = audio.getHigh(time)
            } else {
                bass = 0f; mid = 0f; high = 0f
            }
            GLES30.glUniform3f(program.uAudio, bass, mid, high)
        }
    }

    private fun ensureFbo(width: Int, height: Int) {
        if (fboId != 0 && fboWidth == width && fboHeight == height) return

        if (fboId != 0) {
            GLES30.glDeleteFramebuffers(1, intArrayOf(fboId), 0)
            GLES30.glDeleteTextures(1, intArrayOf(texId), 0)
        }

        val tex = IntArray(1)
        GLES30.glGenTextures(1, tex, 0)
        texId = tex[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texId)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, width, height, 0,
            GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)

        val fb = IntArray(1)
        GLES30.glGenFramebuffers(1, fb, 0)
        fboId = fb[0]
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fboId)
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
            GLES30.GL_TEXTURE_2D, texId, 0)
        val status = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
        check(status == GLES30.GL_FRAMEBUFFER_COMPLETE) { "FBO incomplete: $status" }

        fboWidth = width
        fboHeight = height
    }

    private fun getProgram(generator: GpuGenerator): CompiledProgram {
        val fragment = generator.fragmentShaderSource()
        val key = fragment.hashCode()
        programCache[key]?.let { return it }

        val vs = compileShader(GLES30.GL_VERTEX_SHADER, VERTEX_SHADER)
        val fs = compileShader(GLES30.GL_FRAGMENT_SHADER, fragment)
        val pid = GLES30.glCreateProgram()
        GLES30.glAttachShader(pid, vs)
        GLES30.glAttachShader(pid, fs)
        GLES30.glLinkProgram(pid)
        val link = IntArray(1)
        GLES30.glGetProgramiv(pid, GLES30.GL_LINK_STATUS, link, 0)
        if (link[0] == 0) {
            val log = GLES30.glGetProgramInfoLog(pid)
            GLES30.glDeleteProgram(pid)
            error("GpuShaderRunner: program link failed: $log")
        }
        GLES30.glDeleteShader(vs)
        GLES30.glDeleteShader(fs)

        val compiled = CompiledProgram(
            id = pid,
            aPos = GLES30.glGetAttribLocation(pid, "aPos"),
            uResolution = GLES30.glGetUniformLocation(pid, "uResolution"),
            uTime = GLES30.glGetUniformLocation(pid, "uTime"),
            uPalette = GLES30.glGetUniformLocation(pid, "uPalette"),
            uAudio = GLES30.glGetUniformLocation(pid, "uAudio")
        )
        programCache[key] = compiled
        return compiled
    }

    private fun compileShader(type: Int, source: String): Int {
        val s = GLES30.glCreateShader(type)
        GLES30.glShaderSource(s, source)
        GLES30.glCompileShader(s)
        val status = IntArray(1)
        GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES30.glGetShaderInfoLog(s)
            GLES30.glDeleteShader(s)
            error("GpuShaderRunner: shader compile failed: $log\n--- source ---\n$source")
        }
        return s
    }

    private data class CompiledProgram(
        val id: Int,
        val aPos: Int,
        val uResolution: Int,
        val uTime: Int,
        val uPalette: Int,
        val uAudio: Int
    )

    companion object {
        private val threadLocal = ThreadLocal<GpuShaderRunner>()

        /**
         * Obtain the per-thread GpuShaderRunner, creating it on first use.
         * Caller must already be running on a thread where EGL can be initialised.
         */
        fun forCurrentThread(): GpuShaderRunner {
            threadLocal.get()?.let { return it }
            val r = GpuShaderRunner()
            threadLocal.set(r)
            return r
        }

        // Fullscreen triangle. Y is flipped so the fragment shader sees
        // gl_FragCoord.y growing top→bottom, matching Android Bitmap orientation
        // and avoiding an extra row-flip after glReadPixels.
        private const val VERTEX_SHADER = """#version 300 es
            in vec2 aPos;
            void main() {
                gl_Position = vec4(aPos.x, -aPos.y, 0.0, 1.0);
            }
        """
    }
}
