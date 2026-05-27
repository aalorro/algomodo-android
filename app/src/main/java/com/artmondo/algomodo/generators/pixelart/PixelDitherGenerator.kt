package com.artmondo.algomodo.generators.pixelart

import android.opengl.GLES30
import com.artmondo.algomodo.data.palettes.Palette
import com.artmondo.algomodo.generators.GpuGenerator
import com.artmondo.algomodo.generators.ParamGroup
import com.artmondo.algomodo.generators.Parameter
import com.artmondo.algomodo.generators.Quality
import com.artmondo.algomodo.rendering.gl.PaletteUniform

/**
 * GPU-rendered pixel-art dither. Evaluates a source function (sine-waves /
 * radial / mandelbrot / diagonal / plasma) on a low-resolution grid, then
 * applies ordered Bayer dithering to quantize into 2-4 palette colours.
 *
 * Ported from the original CPU implementation; id, parameter schema and
 * defaults are preserved. The grid is reproduced by snapping `gl_FragCoord`
 * to cell centres before the formula evaluation.
 */
class PixelDitherGenerator : GpuGenerator {

    override val id = "pixel-dither"
    override val family = "pixel-art"
    override val styleName = "Pixel Dither"
    override val definition =
        "A smooth gradient or mathematical function rendered at low resolution with ordered Bayer dithering, creating intricate moir\u00e9-like pixel textures."
    override val algorithmNotes =
        "Per-pixel fragment shader on the GPU. Snaps fragment coordinates to a low-resolution grid, " +
        "evaluates the source function (sine waves, radial gradient, Mandelbrot, plasma) per cell, " +
        "then applies ordered dithering with a 2\u00d72/4\u00d74/8\u00d78 Bayer matrix to quantize into " +
        "2\u20134 palette colours."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema = listOf(
        Parameter.SelectParam("Source Function", "sourceFunction", ParamGroup.COMPOSITION,
            "Mathematical function to dither",
            listOf("sine-waves", "radial", "mandelbrot", "diagonal", "plasma"), "plasma"),
        Parameter.SelectParam("Bayer Matrix", "bayerSize", ParamGroup.TEXTURE,
            "Dither pattern size (2x2, 4x4, 8x8)",
            listOf("2", "4", "8"), "4"),
        Parameter.NumberParam("Color Levels", "numColors", ParamGroup.COLOR,
            "Number of output colors for dithering", 2f, 4f, 1f, 2f),
        Parameter.NumberParam("Grid Size", "gridSize", ParamGroup.GEOMETRY,
            "Pixel grid resolution", 32f, 128f, 8f, 64f),
        Parameter.NumberParam("Contrast", "contrast", ParamGroup.COLOR,
            "Contrast adjustment before dithering", 0.5f, 3f, 0.1f, 1.2f),
        Parameter.NumberParam("Anim Speed", "animSpeed", ParamGroup.FLOW_MOTION,
            "Animation speed multiplier", 0.1f, 3f, 0.1f, 1f),
        Parameter.NumberParam("Reactivity", "reactivity", ParamGroup.FLOW_MOTION,
            "Audio reactivity strength", 0f, 2f, 0.1f, 0f)
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "sourceFunction" to "plasma", "bayerSize" to "4", "numColors" to 2f,
        "gridSize" to 64f, "contrast" to 1.2f, "animSpeed" to 1f, "reactivity" to 0f
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
        val sourceFn = (params["sourceFunction"] as? String) ?: "plasma"
        val bayerSize = ((params["bayerSize"] as? String)?.toIntOrNull()) ?: 4
        val numColors = ((params["numColors"] as? Number)?.toInt() ?: 2).coerceIn(2, 4)
        val contrast = (params["contrast"] as? Number)?.toFloat() ?: 1.2f
        val speed = (params["animSpeed"] as? Number)?.toFloat() ?: 1f
        val animTime = time * speed

        val fnId = when (sourceFn) {
            "sine-waves" -> 0; "radial" -> 1; "mandelbrot" -> 2
            "diagonal" -> 3; "plasma" -> 4
            else -> 4
        }
        val ncPalette = palette.colors.size.coerceAtMost(5)

        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uGrid"), sz)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uFnId"), fnId)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uBayerSize"), bayerSize)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uNumColors"), numColors)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uPaletteCount"), ncPalette)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uContrast"), contrast)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uAnimTime"), animTime)
    }

    override fun fragmentShaderSource(): String = """#version 300 es
        precision highp float;

        uniform vec2 uResolution;
        uniform float uTime;
        uniform vec3 uAudio;
        ${PaletteUniform.GLSL_HELPERS}

        uniform int   uGrid;
        uniform int   uFnId;       // 0 sine-waves, 1 radial, 2 mandelbrot, 3 diagonal, 4 plasma
        uniform int   uBayerSize;  // 2, 4, 8
        uniform int   uNumColors;  // 2..4
        uniform int   uPaletteCount;
        uniform float uContrast;
        uniform float uAnimTime;

        out vec4 fragColor;

        const float TAU = 6.28318530718;

        // 8x8 Bayer matrix derived from 2x2 building blocks (matches CPU init).
        // Stored linearly row-major.
        // We compute on the fly to avoid a 64-entry const array.
        float bayer2(int x, int y) {
            // 2x2 Bayer: [[0,2],[3,1]]
            int v = (y == 0) ? ((x == 0) ? 0 : 2)
                             : ((x == 0) ? 3 : 1);
            return float(v);
        }

        float sampleBayer(int x, int y, int n) {
            if (n == 2) {
                return bayer2(x & 1, y & 1) / 4.0;
            } else if (n == 4) {
                // Bayer4 derived: BAYER2[y>>1][x>>1]*4 + BAYER2[y&1][x&1]
                int b2x = (x >> 1) & 1; int b2y = (y >> 1) & 1;
                int b1x = x & 1;        int b1y = y & 1;
                float v = bayer2(b2x, b2y) * 4.0 + bayer2(b1x, b1y);
                return v / 16.0;
            } else {
                int b2x = (x >> 2) & 1; int b2y = (y >> 2) & 1;
                int b4x = (x >> 1) & 1; int b4y = (y >> 1) & 1;
                int b1x = x & 1;        int b1y = y & 1;
                float v = bayer2(b2x, b2y) * 16.0
                        + bayer2(b4x, b4y) * 4.0
                        + bayer2(b1x, b1y);
                return v / 64.0;
            }
        }

        float evalSource(int fn, float nx, float ny, float t, float contrast) {
            float v;
            if (fn == 0) {
                // sine-waves
                v = (sin(nx * TAU * 3.0 + t)
                   + sin(ny * TAU * 2.0 + t * 0.7)
                   + sin((nx + ny) * TAU * 1.5)) / 3.0;
                v = v * 0.5 + 0.5;
            } else if (fn == 1) {
                // radial
                float dx = nx - 0.5; float dy = ny - 0.5;
                float r = sqrt(dx * dx + dy * dy) * 2.0;
                v = (sin(r * TAU * 2.0 + t) + 1.0) * 0.5;
            } else if (fn == 2) {
                // mandelbrot
                float targetX = -0.75; float targetY = 0.1;
                float cycle = t * 0.15;
                float zoomT = (sin(cycle) + 1.0) * 0.5;
                float zoom = pow(0.02, zoomT);
                float panX = targetX + sin(cycle * 0.7) * 0.3 * zoom;
                float panY = targetY + cos(cycle * 0.9) * 0.3 * zoom;
                float cx = (nx - 0.5) * 3.5 * zoom + panX;
                float cy = (ny - 0.5) * 3.5 * zoom + panY;
                float zr = 0.0; float zi = 0.0;
                int iter = 0;
                int maxIter = 32 + int((1.0 - zoom) * 64.0);
                for (int i = 0; i < 96; i++) {
                    if (i >= maxIter) break;
                    if (zr * zr + zi * zi >= 4.0) break;
                    float tmp = zr * zr - zi * zi + cx;
                    zi = 2.0 * zr * zi + cy;
                    zr = tmp;
                    iter = i + 1;
                }
                v = float(iter) / float(maxIter);
            } else if (fn == 3) {
                // diagonal
                v = (sin((nx + ny) * TAU * 4.0 + t * 0.5) + 1.0) * 0.5;
            } else {
                // plasma
                v = (sin(nx * 10.0 + t)
                   + sin(ny * 10.0 + t * 0.6)
                   + sin((nx + ny) * 7.0 + t * 0.3)
                   + sin(sqrt(nx * nx + ny * ny) * 10.0)) / 4.0;
                v = v * 0.5 + 0.5;
            }
            v = (v - 0.5) * contrast + 0.5;
            return clamp(v, 0.0, 1.0);
        }

        void main() {
            // Snap to grid cell (top-left of cell)
            vec2 cell = floor(gl_FragCoord.xy / uResolution * float(uGrid));
            int x = int(cell.x);
            int y = int(cell.y);
            float nx = float(x) / float(uGrid);
            float ny = float(y) / float(uGrid);

            float v = evalSource(uFnId, nx, ny, uAnimTime, uContrast);
            float threshold = sampleBayer(x, y, uBayerSize);

            float scaled = v * float(uNumColors - 1);
            int low = int(floor(scaled));
            int high = min(uNumColors - 1, low + 1);
            float frac = scaled - float(low);
            int chosen = (frac > threshold) ? high : low;

            // Map chosen [0..uNumColors-1] across palette [0..uPaletteCount-1]
            int ncP = max(uPaletteCount, 1);
            float t01 = (uNumColors <= 1) ? 0.0 : float(chosen) / float(uNumColors - 1);
            int pIdx = int(t01 * float(ncP - 1));
            pIdx = clamp(pIdx, 0, 4);
            vec3 col = uPalette[pIdx];
            fragColor = vec4(col, 1.0);
        }
    """

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float {
        val fn = (params["sourceFunction"] as? String) ?: "plasma"
        return if (fn == "mandelbrot") 0.22f else 0.1f
    }
}
