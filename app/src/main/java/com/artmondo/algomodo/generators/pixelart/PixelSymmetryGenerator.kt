package com.artmondo.algomodo.generators.pixelart

import android.opengl.GLES30
import com.artmondo.algomodo.data.palettes.Palette
import com.artmondo.algomodo.generators.GpuGenerator
import com.artmondo.algomodo.generators.ParamGroup
import com.artmondo.algomodo.generators.Parameter
import com.artmondo.algomodo.generators.Quality
import com.artmondo.algomodo.rendering.gl.NoiseGlsl
import com.artmondo.algomodo.rendering.gl.PaletteUniform

/**
 * GPU-rendered pixel-art symmetry. Fills a grid section with simplex-noise
 * masked pixels, then mirrors (bilateral / quad) or rotationally folds
 * (radial-4/6/8) the result.
 *
 * Ported from the original CPU implementation; id, parameter schema and
 * defaults are preserved. The bilateral/quad colour pick switched from
 * per-pixel `rng.integer(...)` to a snoise-derived index for GPU
 * determinism, matching the algorithm the radial mode already used. Noise
 * visuals are close but not bit-identical to CPU (Ashima/Gustavson `snoise`
 * vs `SimplexNoise.kt`).
 */
class PixelSymmetryGenerator : GpuGenerator {

    override val id = "pixel-symmetry"
    override val family = "pixel-art"
    override val styleName = "Pixel Symmetry"
    override val definition =
        "Noise-filled pixels mirrored or rotated via bilateral, quad, or n-fold radial symmetry \u2014 producing sprite-like or mandala-like figures."
    override val algorithmNotes =
        "Per-pixel fragment shader on the GPU. Quantises fragment coords to a low-resolution grid, " +
        "folds them into the canonical source region for the chosen symmetry, then samples simplex " +
        "noise to decide whether the cell is filled and which palette colour it takes."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema = listOf(
        Parameter.SelectParam("Symmetry", "symmetryType", ParamGroup.COMPOSITION,
            "Type of symmetry applied",
            listOf("bilateral", "quad", "radial-4", "radial-6", "radial-8"), "quad"),
        Parameter.NumberParam("Grid Size", "gridSize", ParamGroup.GEOMETRY,
            "Pixel grid resolution", 32f, 128f, 8f, 64f),
        Parameter.NumberParam("Fill Density", "fillDensity", ParamGroup.COMPOSITION,
            "Fraction of pixels filled before symmetry", 0.1f, 0.9f, 0.05f, 0.5f),
        Parameter.NumberParam("Noise Scale", "noiseScale", ParamGroup.TEXTURE,
            "Scale of noise pattern for fill", 1f, 10f, 0.5f, 4f),
        Parameter.NumberParam("Anim Speed", "animSpeed", ParamGroup.FLOW_MOTION,
            "Animation speed multiplier", 0.1f, 3f, 0.1f, 1f),
        Parameter.NumberParam("Reactivity", "reactivity", ParamGroup.FLOW_MOTION,
            "Audio reactivity strength", 0f, 2f, 0.1f, 0f)
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "symmetryType" to "quad", "gridSize" to 64f, "fillDensity" to 0.5f,
        "noiseScale" to 4f, "animSpeed" to 1f, "reactivity" to 0f
    )

    override fun bindUniforms(
        programId: Int,
        params: Map<String, Any>,
        seed: Int,
        palette: Palette,
        quality: Quality,
        time: Float,
        width: Int,
        height: Int
    ) {
        val sz = ((params["gridSize"] as? Number)?.toInt() ?: 64).coerceIn(16, 128)
        val symmetry = (params["symmetryType"] as? String) ?: "quad"
        val density = (params["fillDensity"] as? Number)?.toFloat() ?: 0.5f
        val noiseScale = (params["noiseScale"] as? Number)?.toFloat() ?: 4f
        val speed = (params["animSpeed"] as? Number)?.toFloat() ?: 1f
        val animTime = time * speed

        val symId = when (symmetry) {
            "bilateral" -> 0
            "quad" -> 1
            "radial-4" -> 2
            "radial-6" -> 3
            "radial-8" -> 4
            else -> 1
        }
        val folds = when (symmetry) {
            "radial-4" -> 4
            "radial-6" -> 6
            "radial-8" -> 8
            else -> 0
        }
        val ncPalette = palette.colors.size.coerceAtMost(5)
        // Seed-derived coordinate offset so different seeds give different layouts.
        val seedOffX = ((seed * 374761393).toInt() and 0xFFFF) / 65536f * 100f
        val seedOffY = ((seed * 668265263).toInt() and 0xFFFF) / 65536f * 100f

        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uGrid"), sz)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uSymId"), symId)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uFolds"), folds)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uDensity"), density)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uNoiseScale"), noiseScale)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uAnimTime"), animTime)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uPaletteCount"), ncPalette)
        GLES30.glUniform2f(GLES30.glGetUniformLocation(programId, "uSeedOffset"), seedOffX, seedOffY)
    }

    override fun fragmentShaderSource(): String = """#version 300 es
        precision highp float;

        uniform vec2 uResolution;
        uniform float uTime;
        uniform vec3 uAudio;
        ${PaletteUniform.GLSL_HELPERS}
        ${NoiseGlsl.GLSL_HELPERS}

        uniform int   uGrid;
        uniform int   uSymId;       // 0 bilateral, 1 quad, 2 radial-4, 3 radial-6, 4 radial-8
        uniform int   uFolds;       // 0 for bilateral/quad, else 4/6/8
        uniform float uDensity;
        uniform float uNoiseScale;
        uniform float uAnimTime;
        uniform int   uPaletteCount;
        uniform vec2  uSeedOffset;

        out vec4 fragColor;

        const float PI = 3.14159265359;
        const float TAU = 6.28318530718;

        void main() {
            int sz = uGrid;
            vec2 cell = floor(gl_FragCoord.xy / uResolution * float(sz));
            int x = int(cell.x);
            int y = int(cell.y);

            // Fold (x, y) into the canonical source region.
            float srcX; float srcY;
            if (uSymId == 0) {
                // bilateral: mirror across vertical axis
                int sx = (x < (sz - 1 - x)) ? x : (sz - 1 - x);
                srcX = float(sx); srcY = float(y);
            } else if (uSymId == 1) {
                // quad: mirror across both axes
                int sx = (x < (sz - 1 - x)) ? x : (sz - 1 - x);
                int sy = (y < (sz - 1 - y)) ? y : (sz - 1 - y);
                srcX = float(sx); srcY = float(sy);
            } else {
                // radial: fold angle into a single segment, then unfold back to cartesian
                float cx = float(sz) * 0.5;
                float cy = float(sz) * 0.5;
                float dx = float(x) - cx; float dy = float(y) - cy;
                float ang = atan(dy, dx);
                if (ang < 0.0) ang += TAU;
                float r = sqrt(dx * dx + dy * dy);
                float sliceAngle = TAU / float(uFolds);
                float folded = mod(ang, sliceAngle);
                if (folded > sliceAngle * 0.5) folded = sliceAngle - folded;
                srcX = cx + cos(folded) * r;
                srcY = cy + sin(folded) * r;
                // outside the inscribed disc -> background
                if (r >= float(sz) * 0.48) {
                    fragColor = vec4(uPalette[0], 1.0);
                    return;
                }
            }

            float snx = srcX / float(sz) * uNoiseScale + uSeedOffset.x;
            float sny = srcY / float(sz) * uNoiseScale + uSeedOffset.y;
            float nv = snoise(vec2(snx + uAnimTime * 0.3, sny));
            float threshold = (nv + 1.0) * 0.5;

            if (threshold < uDensity) {
                int ncP = max(uPaletteCount, 2);
                int ci;
                if (uSymId <= 1) {
                    // bilateral/quad: pick a colour via a second noise sample so the result
                    // is deterministic per-cell (CPU uses an RNG order we cannot reproduce
                    // on GPU).
                    float colorNoise = snoise(vec2(snx * 1.7 + 31.7, sny * 1.7 + 17.3));
                    int t = int(abs(colorNoise * 100.0));
                    ci = 1 + (t % (ncP - 1));
                } else {
                    int t = int(abs(nv * 100.0));
                    ci = 1 + (t % (ncP - 1));
                }
                ci = clamp(ci, 0, 4);
                fragColor = vec4(uPalette[ci], 1.0);
            } else {
                fragColor = vec4(uPalette[0], 1.0);
            }
        }
    """

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float = 0.12f
}
