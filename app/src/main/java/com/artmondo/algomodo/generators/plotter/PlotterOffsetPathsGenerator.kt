package com.artmondo.algomodo.generators.plotter

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import com.artmondo.algomodo.core.rng.SeededRNG
import com.artmondo.algomodo.core.rng.SimplexNoise
import com.artmondo.algomodo.data.palettes.Palette
import com.artmondo.algomodo.generators.Generator
import com.artmondo.algomodo.generators.ParamGroup
import com.artmondo.algomodo.generators.Parameter
import com.artmondo.algomodo.generators.Quality
import kotlin.math.*

/**
 * Concentric offset path generator using signed distance fields.
 *
 * Draws concentric iso-distance rings around randomly placed seed shapes.
 * Uses a per-pixel signed-distance field to locate ring boundaries, with
 * FBM noise wobble for a hand-drawn look.
 */
class PlotterOffsetPathsGenerator : Generator {

    override val id = "plotter-offset-paths"
    override val family = "plotter"
    override val styleName = "Offset Paths"
    override val definition =
        "Draws concentric iso-distance rings around randomly placed seed shapes, using a per-pixel signed-distance field to locate ring boundaries."
    override val algorithmNotes =
        "Seed shapes (circles, rectangles, triangles, stars, or noise-warped blobs) are placed " +
        "with a jittered grid. For every pixel the global SDF is evaluated as the union of all " +
        "shape SDFs, then perturbed with FBM noise for a hand-drawn look. Ring boundaries are " +
        "detected where the perturbed SDF crosses multiples of the spacing value."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema = listOf(
        Parameter.NumberParam("Ring Count", "ringCount", ParamGroup.COMPOSITION, "Number of concentric offset rings around each seed shape", 4f, 40f, 1f, 16f),
        Parameter.NumberParam("Ring Spacing", "ringSpacing", ParamGroup.GEOMETRY, "Pixel gap between successive rings", 4f, 40f, 1f, 14f),
        Parameter.NumberParam("Shape Count", "shapeCount", ParamGroup.COMPOSITION, "Number of seed shapes to offset around", 1f, 12f, 1f, 4f),
        Parameter.SelectParam("Shape Type", "shapeType", ParamGroup.COMPOSITION, null, listOf("circles", "rectangles", "mixed", "blobs", "triangles", "stars"), "circles"),
        Parameter.NumberParam("Line Width", "lineWidth", ParamGroup.GEOMETRY, null, 0.25f, 3f, 0.25f, 0.8f),
        Parameter.NumberParam("Wobble", "wobble", ParamGroup.TEXTURE, "Noise-based perturbation of the SDF surface — gives hand-drawn character", 0f, 6f, 0.25f, 1.0f),
        Parameter.NumberParam("Wobble Scale", "wobbleScale", ParamGroup.TEXTURE, "Spatial frequency of the wobble noise", 0.5f, 6f, 0.25f, 2.0f),
        Parameter.BooleanParam("Fill Bands", "fillBands", ParamGroup.TEXTURE, "Fill the space between rings with color for a topographic map look", false),
        Parameter.SelectParam("Color Mode", "colorMode", ParamGroup.COLOR, "palette-rings: each ring cycles through palette | elevation: ramps by ring depth | alternating: two-color flip", listOf("palette-rings", "elevation", "alternating"), "palette-rings"),
        Parameter.SelectParam("Background", "background", ParamGroup.COLOR, null, listOf("white", "cream", "dark"), "cream"),
        Parameter.NumberParam("Anim Speed", "animSpeed", ParamGroup.FLOW_MOTION, "Speed of wobble field drift — 0 = static", 0f, 1f, 0.05f, 0.1f)
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "ringCount" to 16f,
        "ringSpacing" to 14f,
        "shapeCount" to 4f,
        "shapeType" to "circles",
        "lineWidth" to 0.8f,
        "wobble" to 1.0f,
        "wobbleScale" to 2.0f,
        "fillBands" to false,
        "colorMode" to "palette-rings",
        "background" to "cream",
        "animSpeed" to 0.1f
    )

    companion object {
        private val BG = mapOf(
            "white" to intArrayOf(248, 248, 245),
            "cream" to intArrayOf(242, 234, 216),
            "dark"  to intArrayOf(14, 14, 14)
        )

        private const val TYPE_CIRCLE = 0
        private const val TYPE_RECT = 1
        private const val TYPE_TRIANGLE = 2
        private const val TYPE_STAR = 3
    }

    // Shape struct stored as flat arrays for cache-friendly access
    private class ShapeData(count: Int) {
        val type = IntArray(count)
        val cx = FloatArray(count)
        val cy = FloatArray(count)
        val r = FloatArray(count)
        val hw = FloatArray(count)
        val hh = FloatArray(count)
        // Pre-cached trig for rotation
        val cosR = FloatArray(count)
        val sinR = FloatArray(count)
        // Bounding circle for early-out: center + max possible SDF radius
        val boundR = FloatArray(count)
        // Star inner radius
        val innerR = FloatArray(count)
        var size = 0
    }

    override fun renderCanvas(
        canvas: Canvas,
        bitmap: Bitmap,
        params: Map<String, Any>,
        seed: Int,
        palette: Palette,
        quality: Quality,
        time: Float
    ) {
        val w = bitmap.width
        val h = bitmap.height
        val wf = w.toFloat()
        val hf = h.toFloat()

        val rng = SeededRNG(seed)
        val noise = SimplexNoise(seed)

        val ringCount = ((params["ringCount"] as? Number)?.toInt() ?: 16).coerceAtLeast(1)
        val spacing = ((params["ringSpacing"] as? Number)?.toFloat() ?: 14f).coerceAtLeast(2f)
        val shapeCount = ((params["shapeCount"] as? Number)?.toInt() ?: 4).coerceAtLeast(1)
        val shapeType = (params["shapeType"] as? String) ?: "circles"
        val wobble = (params["wobble"] as? Number)?.toFloat() ?: 1.0f
        val wobbleScale = (params["wobbleScale"] as? Number)?.toFloat() ?: 2.0f
        val fillBands = (params["fillBands"] as? Boolean) ?: false
        val lineWidth = (params["lineWidth"] as? Number)?.toFloat() ?: 0.8f
        val colorMode = (params["colorMode"] as? String) ?: "palette-rings"
        val background = (params["background"] as? String) ?: "cream"
        val isDark = background == "dark"
        val animSpeed = (params["animSpeed"] as? Number)?.toFloat() ?: 0.1f
        val tOff = time * animSpeed * 0.3f

        val bg = BG[background] ?: BG["cream"]!!
        val bgR = bg[0]; val bgG = bg[1]; val bgB = bg[2]
        val bgColor = Color.rgb(bgR, bgG, bgB)

        val halfLW = lineWidth * 0.5f
        val edgeThreshold = halfLW + 1f
        val globalAlpha = if (isDark) 0.88f else 0.82f
        val maxDist = ringCount * spacing
        // Max wobble displacement to expand bounding checks
        val wobbleMult = if (shapeType == "blobs") 0.7f else 0.35f
        val maxWobbleDisp = if (wobble > 0f) wobble * spacing * wobbleMult * 1.2f else 0f

        // --- Place seed shapes ---
        val cols = ceil(sqrt(shapeCount.toFloat() * (wf / hf))).toInt().coerceAtLeast(1)
        val rows = ceil(shapeCount.toFloat() / cols).toInt().coerceAtLeast(1)
        val cw = wf / cols
        val ch = hf / rows
        val minDim = min(wf, hf)

        val shapes = ShapeData(shapeCount)
        for (row in 0 until rows) {
            if (shapes.size >= shapeCount) break
            for (col in 0 until cols) {
                if (shapes.size >= shapeCount) break
                val i = shapes.size
                shapes.cx[i] = (col + 0.2f + rng.range(0f, 0.6f)) * cw
                shapes.cy[i] = (row + 0.2f + rng.range(0f, 0.6f)) * ch
                val baseR = (0.12f + rng.range(0f, 0.14f)) * minDim
                val rot = rng.range(0f, 2f * PI.toFloat())
                shapes.cosR[i] = cos(-rot)
                shapes.sinR[i] = sin(-rot)
                shapes.r[i] = baseR

                shapes.type[i] = when (shapeType) {
                    "circles", "blobs" -> TYPE_CIRCLE
                    "rectangles" -> TYPE_RECT
                    "triangles" -> TYPE_TRIANGLE
                    "stars" -> TYPE_STAR
                    else -> { // mixed
                        val pick = rng.range(0f, 1f)
                        when {
                            pick < 0.25f -> TYPE_CIRCLE
                            pick < 0.45f -> TYPE_RECT
                            pick < 0.65f -> TYPE_TRIANGLE
                            pick < 0.85f -> TYPE_STAR
                            else -> TYPE_CIRCLE
                        }
                    }
                }

                val aspect = 0.6f + rng.range(0f, 0.8f)
                shapes.hw[i] = baseR * aspect
                shapes.hh[i] = baseR / aspect
                shapes.innerR[i] = baseR * 0.38f
                // Bounding circle: largest extent + maxDist + wobble margin
                val maxExtent = when (shapes.type[i]) {
                    TYPE_RECT -> sqrt(shapes.hw[i] * shapes.hw[i] + shapes.hh[i] * shapes.hh[i])
                    else -> baseR
                }
                shapes.boundR[i] = maxExtent + maxDist + maxWobbleDisp
                shapes.size++
            }
        }

        val numShapes = shapes.size

        // --- Pre-compute ring color LUT as packed ARGB ints ---
        val paletteColors = palette.colorInts()
        val colorsRgb = Array(paletteColors.size) {
            intArrayOf(Color.red(paletteColors[it]), Color.green(paletteColors[it]), Color.blue(paletteColors[it]))
        }
        // Store r,g,b per ring for blending
        val ringR = IntArray(ringCount)
        val ringG = IntArray(ringCount)
        val ringB = IntArray(ringCount)
        when (colorMode) {
            "alternating" -> {
                val c0 = colorsRgb[0]
                val c1 = colorsRgb[colorsRgb.size - 1]
                for (ri in 0 until ringCount) {
                    val c = if (ri % 2 == 0) c0 else c1
                    ringR[ri] = c[0]; ringG[ri] = c[1]; ringB[ri] = c[2]
                }
            }
            "elevation" -> {
                val maxRi = max(1, ringCount - 1).toFloat()
                for (ri in 0 until ringCount) {
                    val t = ri / maxRi
                    val ci = t * (colorsRgb.size - 1)
                    val i0 = floor(ci).toInt()
                    val i1 = min(colorsRgb.size - 1, i0 + 1)
                    val f = ci - i0
                    ringR[ri] = (colorsRgb[i0][0] + (colorsRgb[i1][0] - colorsRgb[i0][0]) * f).toInt().coerceIn(0, 255)
                    ringG[ri] = (colorsRgb[i0][1] + (colorsRgb[i1][1] - colorsRgb[i0][1]) * f).toInt().coerceIn(0, 255)
                    ringB[ri] = (colorsRgb[i0][2] + (colorsRgb[i1][2] - colorsRgb[i0][2]) * f).toInt().coerceIn(0, 255)
                }
            }
            else -> { // palette-rings
                for (ri in 0 until ringCount) {
                    val c = colorsRgb[ri % colorsRgb.size]
                    ringR[ri] = c[0]; ringG[ri] = c[1]; ringB[ri] = c[2]
                }
            }
        }

        // --- Downsampled noise grid for wobble (biggest perf win) ---
        // Noise is spatially smooth, so sample every Nth pixel and bilinear-interpolate
        val noiseGrid: FloatArray?
        val ngStep: Int
        val ngW: Int
        val ngH: Int
        if (wobble > 0f) {
            ngStep = when (quality) {
                Quality.DRAFT -> 8
                Quality.BALANCED -> 4
                Quality.ULTRA -> 2
            }
            ngW = (w + ngStep - 1) / ngStep + 1
            ngH = (h + ngStep - 1) / ngStep + 1
            noiseGrid = FloatArray(ngW * ngH)
            val invW = wobbleScale / wf
            val invH = wobbleScale / hf
            val noiseWobble = wobble * spacing * wobbleMult
            for (gy in 0 until ngH) {
                val pyf = (gy * ngStep).toFloat()
                val ny = pyf * invH + tOff * 0.7f
                val rowOff = gy * ngW
                for (gx in 0 until ngW) {
                    val pxf = (gx * ngStep).toFloat()
                    val nx = pxf * invW + tOff
                    noiseGrid[rowOff + gx] = noise.fbm(nx, ny, 3, 2f, 0.5f) * noiseWobble
                }
            }
        } else {
            noiseGrid = null
            ngStep = 1
            ngW = 0
            ngH = 0
        }

        // --- Per-pixel rendering ---
        val pixels = IntArray(w * h)
        pixels.fill(bgColor)

        // Pre-extract shape arrays for tight inner loop
        val sType = shapes.type
        val sCx = shapes.cx; val sCy = shapes.cy
        val sR = shapes.r; val sHw = shapes.hw; val sHh = shapes.hh
        val sCosR = shapes.cosR; val sSinR = shapes.sinR
        val sBoundR = shapes.boundR; val sInnerR = shapes.innerR

        val invStep = 1f / ngStep

        for (py in 0 until h) {
            val pyf = py.toFloat()
            val pixelRowOff = py * w
            for (px in 0 until w) {
                val pxf = px.toFloat()

                // Evaluate union SDF across all shapes with bounding check
                var d = Float.MAX_VALUE
                for (si in 0 until numShapes) {
                    // Bounding circle early-out
                    val dx = pxf - sCx[si]
                    val dy = pyf - sCy[si]
                    val distSq = dx * dx + dy * dy
                    val br = sBoundR[si]
                    if (distSq > br * br) continue

                    val raw = when (sType[si]) {
                        TYPE_RECT -> {
                            val qx = abs(dx) - sHw[si]
                            val qy = abs(dy) - sHh[si]
                            val ox = max(qx, 0f); val oy = max(qy, 0f)
                            sqrt(ox * ox + oy * oy) + min(max(qx, qy), 0f)
                        }
                        TYPE_TRIANGLE -> sdPolyInline(dx, dy, sR[si], 3, sCosR[si], sSinR[si])
                        TYPE_STAR -> {
                            val rx = dx * sCosR[si] - dy * sSinR[si]
                            val ry = dx * sSinR[si] + dy * sCosR[si]
                            val angle = atan2(ry, rx)
                            val sector = 1.2566371f // 2*PI/5
                            val halfSector = 0.6283185f
                            val a = ((angle % sector) + sector) % sector - halfSector
                            val dist = sqrt(rx * rx + ry * ry)
                            val t = abs(a) / halfSector
                            dist - (sR[si] * (1f - t) + sInnerR[si] * t)
                        }
                        else -> { // circle
                            sqrt(distSq) - sR[si]
                        }
                    }
                    if (raw < d) d = raw
                }

                // Apply wobble via bilinear interpolation of noise grid
                if (noiseGrid != null) {
                    val gx = pxf * invStep
                    val gy = pyf * invStep
                    val gx0 = gx.toInt().coerceIn(0, ngW - 2)
                    val gy0 = gy.toInt().coerceIn(0, ngH - 2)
                    val fx = gx - gx0
                    val fy = gy - gy0
                    val rowOff0 = gy0 * ngW
                    val rowOff1 = rowOff0 + ngW
                    val n00 = noiseGrid[rowOff0 + gx0]
                    val n10 = noiseGrid[rowOff0 + gx0 + 1]
                    val n01 = noiseGrid[rowOff1 + gx0]
                    val n11 = noiseGrid[rowOff1 + gx0 + 1]
                    d += n00 + (n10 - n00) * fx + (n01 - n00) * fy + (n00 - n10 - n01 + n11) * fx * fy
                }

                if (d < 0f || d >= maxDist) continue

                val ringIdx = (d / spacing).toInt()
                if (ringIdx >= ringCount) continue
                val frac = (d - ringIdx * spacing) / spacing
                val distFromEdge = min(frac, 1f - frac) * spacing

                val cr = ringR[ringIdx]; val cg = ringG[ringIdx]; val cb = ringB[ringIdx]

                if (fillBands) {
                    val bandAlpha = globalAlpha * 0.5f
                    val a01 = if (distFromEdge < edgeThreshold) {
                        val lineAlpha = edgeThreshold - distFromEdge
                        bandAlpha + (globalAlpha - bandAlpha) * (if (lineAlpha > 1f) 1f else lineAlpha)
                    } else bandAlpha
                    val inv = 1f - a01
                    pixels[pixelRowOff + px] = Color.rgb(
                        (bgR * inv + cr * a01).toInt(),
                        (bgG * inv + cg * a01).toInt(),
                        (bgB * inv + cb * a01).toInt()
                    )
                } else {
                    if (distFromEdge > edgeThreshold) continue
                    val a01 = (edgeThreshold - distFromEdge).coerceAtMost(1f) * globalAlpha
                    val inv = 1f - a01
                    pixels[pixelRowOff + px] = Color.rgb(
                        (bgR * inv + cr * a01).toInt(),
                        (bgG * inv + cg * a01).toInt(),
                        (bgB * inv + cb * a01).toInt()
                    )
                }
            }
        }

        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
    }

    /** Inline regular polygon SDF — avoids function call overhead in hot loop */
    private fun sdPolyInline(
        dx: Float, dy: Float, r: Float, n: Int,
        cosR: Float, sinR: Float
    ): Float {
        val rx = dx * cosR - dy * sinR
        val ry = dx * sinR + dy * cosR
        val angle = atan2(ry, rx)
        val sector = (2.0 * PI / n).toFloat()
        val halfSector = sector / 2f
        val a = ((angle % sector) + sector) % sector - halfSector
        val dist = sqrt(rx * rx + ry * ry)
        return dist * cos(a) - r * cos(halfSector)
    }

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float {
        val shapes = (params["shapeCount"] as? Number)?.toInt() ?: 4
        return (shapes * 0.25f).coerceIn(0.2f, 1f)
    }
}
