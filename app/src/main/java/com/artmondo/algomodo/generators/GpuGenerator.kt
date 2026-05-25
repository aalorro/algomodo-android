package com.artmondo.algomodo.generators

import android.graphics.Bitmap
import android.graphics.Canvas
import com.artmondo.algomodo.data.palettes.Palette
import com.artmondo.algomodo.rendering.gl.GpuShaderRunner

/**
 * Generator that renders via an OpenGL ES 3.0 fragment shader instead of CPU code.
 *
 * Implementations provide the fragment shader source plus a [bindUniforms]
 * callback that uploads any custom parameters. The base interface handles
 * fullscreen-quad setup, palette/audio/time/resolution uniforms, FBO render
 * target, and glReadPixels into the caller's Bitmap — so every existing call
 * site of [Generator.renderCanvas] (live preview, animation, PNG/JPG/GIF/MP4
 * export, PostFX pipeline) works unchanged.
 *
 * Each shader source MUST:
 *  - start with `#version 300 es`
 *  - declare `precision highp float;` (or `mediump`)
 *  - include the standard built-in uniforms it uses:
 *      uniform vec2 uResolution;  // pixels
 *      uniform float uTime;       // seconds
 *      uniform vec3 uAudio;       // bass, mid, high in 0..~1
 *      uniform vec3 uPalette[5];  // palette colours, 0..1 per channel
 *  - declare its output as `out vec4 fragColor;`
 *
 * The helper snippet [com.artmondo.algomodo.rendering.gl.PaletteUniform.GLSL_HELPERS]
 * can be concatenated into the shader to get a ready-made `palette_color(float)`
 * function plus the `uPalette[5]` declaration.
 */
interface GpuGenerator : Generator {

    /** Full GLSL fragment shader source, including version directive and main(). */
    fun fragmentShaderSource(): String

    /**
     * Bind any custom uniforms beyond the built-ins ([uResolution], [uTime],
     * [uAudio], [uPalette]). The runner has already called glUseProgram, so
     * implementations just need to glGetUniformLocation + glUniformXxx for
     * their own params.
     */
    fun bindUniforms(
        programId: Int,
        params: Map<String, Any>,
        seed: Int,
        palette: Palette,
        quality: Quality,
        time: Float,
        width: Int,
        height: Int
    )

    /**
     * Default implementation dispatches to the per-thread [GpuShaderRunner],
     * which renders into the bitmap via an offscreen FBO + glReadPixels. The
     * provided [canvas] is ignored — the runner writes pixels directly into
     * [bitmap], and PostFXProcessor (run by the caller) operates on it as usual.
     */
    override fun renderCanvas(
        canvas: Canvas,
        bitmap: Bitmap,
        params: Map<String, Any>,
        seed: Int,
        palette: Palette,
        quality: Quality,
        time: Float
    ) {
        GpuShaderRunner.forCurrentThread().render(
            generator = this,
            bitmap = bitmap,
            params = params,
            seed = seed,
            palette = palette,
            quality = quality,
            time = time
        )
    }

    /** Shader generators are inherently raster — no SVG path. */
    override fun renderVector(params: Map<String, Any>, seed: Int, palette: Palette) = null
}
