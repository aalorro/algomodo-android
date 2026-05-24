package com.artmondo.algomodo.generators.pixelart

import android.graphics.Bitmap
import android.graphics.Canvas
import com.artmondo.algomodo.core.rng.SeededRNG
import com.artmondo.algomodo.core.rng.SimplexNoise
import com.artmondo.algomodo.data.palettes.Palette
import com.artmondo.algomodo.generators.Generator
import com.artmondo.algomodo.generators.ParamGroup
import com.artmondo.algomodo.generators.Parameter
import com.artmondo.algomodo.generators.Quality
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.abs

class PixelSymmetryGenerator : Generator {
    override val id = "pixel-symmetry"
    override val family = "pixel-art"
    override val styleName = "Pixel Symmetry"
    override val definition = "Noise-filled pixels mirrored or rotated via bilateral, quad, or n-fold radial symmetry — producing sprite-like or mandala-like figures."
    override val algorithmNotes = "Fills one section of a coarse grid with noise-driven colored pixels, then mirrors (bilateral/quad) or rotationally folds (radial n-fold) to produce symmetric patterns. Animation shifts the noise field over time."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema = listOf(
        Parameter.SelectParam("Symmetry", "symmetryType", ParamGroup.COMPOSITION, "Type of symmetry applied", listOf("bilateral", "quad", "radial-4", "radial-6", "radial-8"), "quad"),
        Parameter.NumberParam("Grid Size", "gridSize", ParamGroup.GEOMETRY, "Pixel grid resolution", 32f, 128f, 8f, 64f),
        Parameter.NumberParam("Fill Density", "fillDensity", ParamGroup.COMPOSITION, "Fraction of pixels filled before symmetry", 0.1f, 0.9f, 0.05f, 0.5f),
        Parameter.NumberParam("Noise Scale", "noiseScale", ParamGroup.TEXTURE, "Scale of noise pattern for fill", 1f, 10f, 0.5f, 4f),
        Parameter.NumberParam("Anim Speed", "animSpeed", ParamGroup.FLOW_MOTION, "Animation speed multiplier", 0.1f, 3f, 0.1f, 1f),
        Parameter.NumberParam("Reactivity", "reactivity", ParamGroup.FLOW_MOTION, "Audio reactivity strength", 0f, 2f, 0.1f, 0f)
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "symmetryType" to "quad", "gridSize" to 64f, "fillDensity" to 0.5f,
        "noiseScale" to 4f, "animSpeed" to 1f, "reactivity" to 0f
    )

    override fun renderCanvas(
        canvas: Canvas, bitmap: Bitmap, params: Map<String, Any>, seed: Int,
        palette: Palette, quality: Quality, time: Float
    ) {
        val sz = PixelArtUtil.pi(params, "gridSize", 64).coerceIn(16, 128)
        val symmetry = PixelArtUtil.ps(params, "symmetryType", "quad")
        val density = PixelArtUtil.pf(params, "fillDensity", 0.5f)
        val noiseScale = PixelArtUtil.pf(params, "noiseScale", 4f)
        val speed = PixelArtUtil.pf(params, "animSpeed", 1f)

        val rng = SeededRNG(seed)
        val noise = SimplexNoise(seed)
        val animTime = time * speed

        val colors = PixelArtUtil.paletteRgb(palette)
        val nc = colors.size
        val bg = colors[0]

        val source = ByteArray(sz * sz) { (-1).toByte() }

        val cx = sz * 0.5f; val cy = sz * 0.5f

        if (symmetry == "bilateral" || symmetry == "quad") {
            val xEnd = ceil(sz / 2.0).toInt()
            val yEnd = if (symmetry == "quad") ceil(sz / 2.0).toInt() else sz
            for (y in 0 until yEnd) {
                for (x in 0 until xEnd) {
                    val nv = noise.noise2D(x.toFloat() / sz * noiseScale + animTime * 0.3f, y.toFloat() / sz * noiseScale)
                    val threshold = (nv + 1f) * 0.5f
                    if (threshold < density) {
                        val ci = 1 + rng.integer(0, nc - 2)
                        source[y * sz + x] = ci.toByte()
                    }
                }
            }
            for (y in 0 until sz) {
                for (x in 0 until sz) {
                    val sy = if (symmetry == "quad") min(y, sz - 1 - y) else y
                    val sx = min(x, sz - 1 - x)
                    val si = sy * sz + sx
                    if (source[si].toInt() >= 0) source[y * sz + x] = source[si]
                }
            }
        } else {
            val folds = if (symmetry == "radial-4") 4 else if (symmetry == "radial-6") 6 else 8
            val sliceAngle = (2.0 * PI / folds).toFloat()
            for (y in 0 until sz) {
                for (x in 0 until sz) {
                    val dx = x - cx; val dy = y - cy
                    var angle = atan2(dy, dx)
                    if (angle < 0) angle += (2.0 * PI).toFloat()
                    val r = sqrt(dx * dx + dy * dy)
                    var foldedAngle = angle % sliceAngle
                    if (foldedAngle > sliceAngle / 2f) foldedAngle = sliceAngle - foldedAngle
                    val srcX = cx + cos(foldedAngle) * r
                    val srcY = cy + sin(foldedAngle) * r
                    val snx = srcX / sz * noiseScale
                    val sny = srcY / sz * noiseScale
                    val nv = noise.noise2D(snx + animTime * 0.3f, sny)
                    val threshold = (nv + 1f) * 0.5f
                    if (threshold < density && r < sz * 0.48f) {
                        val ci = 1 + (abs((nv * 100).toInt()) % (nc - 1))
                        source[y * sz + x] = ci.toByte()
                    }
                }
            }
        }

        val pixels = IntArray(sz * sz)
        for (i in 0 until sz * sz) {
            val s = source[i].toInt()
            if (s < 0) pixels[i] = PixelArtUtil.rgb(bg[0], bg[1], bg[2])
            else { val c = colors[s % nc]; pixels[i] = PixelArtUtil.rgb(c[0], c[1], c[2]) }
        }
        PixelArtUtil.blitNearest(canvas, bitmap, pixels, sz)
    }
}
