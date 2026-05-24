package com.artmondo.algomodo.generators.pixelart

import android.graphics.Bitmap
import android.graphics.Canvas
import com.artmondo.algomodo.data.palettes.Palette
import com.artmondo.algomodo.generators.Generator
import com.artmondo.algomodo.generators.ParamGroup
import com.artmondo.algomodo.generators.Parameter
import com.artmondo.algomodo.generators.Quality
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

class PixelDitherGenerator : Generator {
    override val id = "pixel-dither"
    override val family = "pixel-art"
    override val styleName = "Pixel Dither"
    override val definition = "A smooth gradient or mathematical function rendered at low resolution with ordered Bayer dithering, creating intricate moiré-like pixel textures."
    override val algorithmNotes = "Evaluates a source function (sine waves, radial gradient, Mandelbrot, plasma) per pixel, then applies ordered dithering with a Bayer matrix (2x2, 4x4, or 8x8) to quantize into 2–4 palette colors. The interplay between math and dither pattern creates complex textures."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema = listOf(
        Parameter.SelectParam("Source Function", "sourceFunction", ParamGroup.COMPOSITION, "Mathematical function to dither", listOf("sine-waves", "radial", "mandelbrot", "diagonal", "plasma"), "plasma"),
        Parameter.SelectParam("Bayer Matrix", "bayerSize", ParamGroup.TEXTURE, "Dither pattern size (2x2, 4x4, 8x8)", listOf("2", "4", "8"), "4"),
        Parameter.NumberParam("Color Levels", "numColors", ParamGroup.COLOR, "Number of output colors for dithering", 2f, 4f, 1f, 2f),
        Parameter.NumberParam("Grid Size", "gridSize", ParamGroup.GEOMETRY, "Pixel grid resolution", 32f, 128f, 8f, 64f),
        Parameter.NumberParam("Contrast", "contrast", ParamGroup.COLOR, "Contrast adjustment before dithering", 0.5f, 3f, 0.1f, 1.2f),
        Parameter.NumberParam("Anim Speed", "animSpeed", ParamGroup.FLOW_MOTION, "Animation speed multiplier", 0.1f, 3f, 0.1f, 1f),
        Parameter.NumberParam("Reactivity", "reactivity", ParamGroup.FLOW_MOTION, "Audio reactivity strength", 0f, 2f, 0.1f, 0f)
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "sourceFunction" to "plasma", "bayerSize" to "4", "numColors" to 2f,
        "gridSize" to 64f, "contrast" to 1.2f, "animSpeed" to 1f, "reactivity" to 0f
    )

    companion object {
        private val BAYER2 = arrayOf(intArrayOf(0, 2), intArrayOf(3, 1))
        private val BAYER4 = arrayOf(
            intArrayOf(0, 8, 2, 10),
            intArrayOf(12, 4, 14, 6),
            intArrayOf(3, 11, 1, 9),
            intArrayOf(15, 7, 13, 5)
        )
        private val BAYER8: Array<IntArray> = run {
            val m = Array(8) { IntArray(8) }
            for (y in 0 until 8) {
                for (x in 0 until 8) {
                    val b2x = x shr 2; val b2y = y shr 2
                    val b4x = (x shr 1) and 1; val b4y = (y shr 1) and 1
                    val b1x = x and 1; val b1y = y and 1
                    m[y][x] = BAYER2[b2y][b2x] * 16 + BAYER2[b4y][b4x] * 4 + BAYER2[b1y][b1x]
                }
            }
            m
        }
    }

    private fun bayer(size: Int): Triple<Array<IntArray>, Int, Float> = when (size) {
        2 -> Triple(BAYER2, 2, 4f)
        4 -> Triple(BAYER4, 4, 16f)
        else -> Triple(BAYER8, 8, 64f)
    }

    private fun evalSource(fn: String, x: Int, y: Int, sz: Int, time: Float, contrast: Float): Float {
        val nx = x.toFloat() / sz; val ny = y.toFloat() / sz
        var v: Float
        when (fn) {
            "sine-waves" -> {
                v = (sin(nx * 6.28f * 3f + time) + sin(ny * 6.28f * 2f + time * 0.7f) + sin((nx + ny) * 6.28f * 1.5f)) / 3f
                v = v * 0.5f + 0.5f
            }
            "radial" -> {
                val dx = nx - 0.5f; val dy = ny - 0.5f
                val r = sqrt(dx * dx + dy * dy) * 2f
                v = (sin(r * 6.28f * 2f + time) + 1f) * 0.5f
            }
            "mandelbrot" -> {
                val targetX = -0.75f; val targetY = 0.1f
                val cycle = time * 0.15f
                val zoomT = (sin(cycle) + 1f) * 0.5f
                val zoom = 0.02f.pow(zoomT)
                val panX = targetX + sin(cycle * 0.7f) * 0.3f * zoom
                val panY = targetY + cos(cycle * 0.9f) * 0.3f * zoom
                val cx = (nx - 0.5f) * 3.5f * zoom + panX
                val cy = (ny - 0.5f) * 3.5f * zoom + panY
                var zr = 0f; var zi = 0f; var iter = 0
                val maxIter = 32 + ((1f - zoom) * 64f).toInt()
                while (zr * zr + zi * zi < 4f && iter < maxIter) {
                    val tmp = zr * zr - zi * zi + cx
                    zi = 2f * zr * zi + cy
                    zr = tmp
                    iter++
                }
                v = iter.toFloat() / maxIter
            }
            "diagonal" -> v = (sin((nx + ny) * 6.28f * 4f + time * 0.5f) + 1f) * 0.5f
            "plasma" -> {
                v = (sin(nx * 10f + time) + sin(ny * 10f + time * 0.6f) +
                     sin((nx + ny) * 7f + time * 0.3f) + sin(sqrt(nx * nx + ny * ny) * 10f)) / 4f
                v = v * 0.5f + 0.5f
            }
            else -> v = nx
        }
        v = (v - 0.5f) * contrast + 0.5f
        return v.coerceIn(0f, 1f)
    }

    override fun renderCanvas(
        canvas: Canvas, bitmap: Bitmap, params: Map<String, Any>, seed: Int,
        palette: Palette, quality: Quality, time: Float
    ) {
        val sz = PixelArtUtil.pi(params, "gridSize", 64).coerceIn(16, 128)
        val sourceFn = PixelArtUtil.ps(params, "sourceFunction", "plasma")
        val bayerSize = (PixelArtUtil.ps(params, "bayerSize", "4")).toIntOrNull() ?: 4
        val numColors = PixelArtUtil.pi(params, "numColors", 2).coerceIn(2, 4)
        val contrast = PixelArtUtil.pf(params, "contrast", 1.2f)
        val speed = PixelArtUtil.pf(params, "animSpeed", 1f)

        val animTime = time * speed
        val (matrix, n, maxV) = bayer(bayerSize)

        val colors = PixelArtUtil.paletteRgb(palette)
        val nc = colors.size

        val colorIndices = IntArray(numColors) { i ->
            val t = if (numColors <= 1) 0f else i.toFloat() / (numColors - 1)
            min(nc - 1, (t * (nc - 1)).toInt())
        }

        val pixels = IntArray(sz * sz)
        for (y in 0 until sz) {
            for (x in 0 until sz) {
                val v = evalSource(sourceFn, x, y, sz, animTime, contrast)
                val threshold = matrix[y % n][x % n] / maxV
                val scaled = v * (numColors - 1)
                val low = scaled.toInt()
                val high = min(numColors - 1, low + 1)
                val frac = scaled - low
                val chosen = if (frac > threshold) high else low
                val ci = colorIndices[chosen]
                val c = colors[ci]
                pixels[y * sz + x] = PixelArtUtil.rgb(c[0], c[1], c[2])
            }
        }
        PixelArtUtil.blitNearest(canvas, bitmap, pixels, sz)
    }
}
