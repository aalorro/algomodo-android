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
    }

    // --- SDF primitives ---

    private fun sdCircle(px: Float, py: Float, cx: Float, cy: Float, r: Float): Float {
        val dx = px - cx; val dy = py - cy
        return sqrt(dx * dx + dy * dy) - r
    }

    private fun sdRect(px: Float, py: Float, cx: Float, cy: Float, hw: Float, hh: Float): Float {
        val qx = abs(px - cx) - hw
        val qy = abs(py - cy) - hh
        val ox = max(qx, 0f); val oy = max(qy, 0f)
        return sqrt(ox * ox + oy * oy) + min(max(qx, qy), 0f)
    }

    private fun sdRegularPoly(px: Float, py: Float, cx: Float, cy: Float, r: Float, n: Int, rot: Float): Float {
        val dx = px - cx; val dy = py - cy
        val cosR = cos(-rot); val sinR = sin(-rot)
        val rx = dx * cosR - dy * sinR
        val ry = dx * sinR + dy * cosR
        val angle = atan2(ry, rx)
        val sector = (2.0 * PI / n).toFloat()
        val halfSector = sector / 2f
        val a = ((angle % sector) + sector) % sector - halfSector
        val dist = sqrt(rx * rx + ry * ry)
        return dist * cos(a) - r * cos(halfSector)
    }

    private fun sdStar(px: Float, py: Float, cx: Float, cy: Float, outerR: Float, rot: Float): Float {
        val innerR = outerR * 0.38f
        val dx = px - cx; val dy = py - cy
        val cosR = cos(-rot); val sinR = sin(-rot)
        val rx = dx * cosR - dy * sinR
        val ry = dx * sinR + dy * cosR
        val angle = atan2(ry, rx)
        val sector = (2.0 * PI / 5.0).toFloat()
        val halfSector = sector / 2f
        val a = ((angle % sector) + sector) % sector - halfSector
        val dist = sqrt(rx * rx + ry * ry)
        val t = abs(a) / halfSector
        val edgeR = outerR * (1f - t) + innerR * t
        return dist - edgeR
    }

    // --- Shape data class ---

    private data class Shape(
        val type: String,
        val cx: Float, val cy: Float,
        val r: Float,
        val hw: Float, val hh: Float,
        val rot: Float
    )

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
        val bgColor = Color.rgb(bg[0], bg[1], bg[2])

        // Place seed shapes with jittered grid
        val cols = ceil(sqrt(shapeCount.toFloat() * (wf / hf))).toInt().coerceAtLeast(1)
        val rows = ceil(shapeCount.toFloat() / cols).toInt().coerceAtLeast(1)
        val cw = wf / cols
        val ch = hf / rows
        val minDim = min(wf, hf)

        val shapes = mutableListOf<Shape>()
        for (r in 0 until rows) {
            if (shapes.size >= shapeCount) break
            for (c in 0 until cols) {
                if (shapes.size >= shapeCount) break
                val cx = (c + 0.2f + rng.range(0f, 0.6f)) * cw
                val cy = (r + 0.2f + rng.range(0f, 0.6f)) * ch
                val baseR = (0.12f + rng.range(0f, 0.14f)) * minDim
                val rot = rng.range(0f, 2f * PI.toFloat())

                val type = when (shapeType) {
                    "circles" -> "circle"
                    "rectangles" -> "rect"
                    "triangles" -> "triangle"
                    "stars" -> "star"
                    "blobs" -> "circle" // blobs use circle SDF + extra wobble
                    else -> { // mixed
                        val pick = rng.range(0f, 1f)
                        when {
                            pick < 0.25f -> "circle"
                            pick < 0.45f -> "rect"
                            pick < 0.65f -> "triangle"
                            pick < 0.85f -> "star"
                            else -> "circle"
                        }
                    }
                }

                val aspect = 0.6f + rng.range(0f, 0.8f)
                shapes.add(Shape(type, cx, cy, baseR, baseR * aspect, baseR / aspect, rot))
            }
        }

        // Palette colors as RGB arrays
        val paletteColors = palette.colorInts()
        val colorsRgb = paletteColors.map { intArrayOf(Color.red(it), Color.green(it), Color.blue(it)) }

        val halfLW = lineWidth * 0.5f
        val globalAlpha = if (isDark) 0.88f else 0.82f
        val maxDist = ringCount * spacing

        // Pixel buffer
        val pixels = IntArray(w * h)
        val bgPixel = bgColor

        // Fill background
        pixels.fill(bgPixel)

        for (py in 0 until h) {
            val pyf = py.toFloat()
            for (px in 0 until w) {
                val pxf = px.toFloat()

                // Evaluate union SDF across all shapes
                var d = Float.MAX_VALUE
                for (s in shapes) {
                    val raw = when (s.type) {
                        "rect" -> sdRect(pxf, pyf, s.cx, s.cy, s.hw, s.hh)
                        "triangle" -> sdRegularPoly(pxf, pyf, s.cx, s.cy, s.r, 3, s.rot)
                        "star" -> sdStar(pxf, pyf, s.cx, s.cy, s.r, s.rot)
                        else -> sdCircle(pxf, pyf, s.cx, s.cy, s.r)
                    }
                    d = min(d, raw)
                }

                // Apply wobble
                if (wobble > 0f) {
                    val wn = noise.fbm(
                        pxf / wf * wobbleScale + tOff,
                        pyf / hf * wobbleScale + tOff * 0.7f,
                        3, 2f, 0.5f
                    )
                    val wobbleMult = if (shapeType == "blobs") 0.7f else 0.35f
                    d += wn * wobble * spacing * wobbleMult
                }

                if (d < 0f || d >= maxDist) continue

                val ringIdx = (d / spacing).toInt()
                val frac = (d % spacing) / spacing
                val distFromEdge = min(frac, 1f - frac) * spacing

                // Get ring color
                val (cr, cg, cb) = getRingColor(ringIdx, ringCount, colorsRgb, colorMode)

                if (fillBands) {
                    val bandAlpha = globalAlpha * 0.5f
                    var a01 = bandAlpha
                    if (distFromEdge < halfLW + 1f) {
                        val lineAlpha = max(0f, min(1f, halfLW + 1f - distFromEdge))
                        a01 = bandAlpha + (globalAlpha - bandAlpha) * lineAlpha
                    }
                    val rr = (bg[0] * (1f - a01) + cr * a01).toInt().coerceIn(0, 255)
                    val gg = (bg[1] * (1f - a01) + cg * a01).toInt().coerceIn(0, 255)
                    val bb = (bg[2] * (1f - a01) + cb * a01).toInt().coerceIn(0, 255)
                    pixels[py * w + px] = Color.rgb(rr, gg, bb)
                } else {
                    if (distFromEdge > halfLW + 1f) continue
                    val alpha = max(0f, min(1f, halfLW + 1f - distFromEdge))
                    val a01 = alpha * globalAlpha
                    val rr = (bg[0] * (1f - a01) + cr * a01).toInt().coerceIn(0, 255)
                    val gg = (bg[1] * (1f - a01) + cg * a01).toInt().coerceIn(0, 255)
                    val bb = (bg[2] * (1f - a01) + cb * a01).toInt().coerceIn(0, 255)
                    pixels[py * w + px] = Color.rgb(rr, gg, bb)
                }
            }
        }

        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
    }

    private fun getRingColor(
        ringIdx: Int, ringCount: Int,
        colors: List<IntArray>, colorMode: String
    ): Triple<Int, Int, Int> {
        return when (colorMode) {
            "alternating" -> {
                val c = if (ringIdx % 2 == 0) colors[0] else colors[colors.size - 1]
                Triple(c[0], c[1], c[2])
            }
            "elevation" -> {
                val t = ringIdx.toFloat() / max(1, ringCount - 1)
                val ci = t * (colors.size - 1)
                val i0 = floor(ci).toInt()
                val i1 = min(colors.size - 1, i0 + 1)
                val f = ci - i0
                Triple(
                    (colors[i0][0] + (colors[i1][0] - colors[i0][0]) * f).toInt().coerceIn(0, 255),
                    (colors[i0][1] + (colors[i1][1] - colors[i0][1]) * f).toInt().coerceIn(0, 255),
                    (colors[i0][2] + (colors[i1][2] - colors[i0][2]) * f).toInt().coerceIn(0, 255)
                )
            }
            else -> { // palette-rings
                val c = colors[ringIdx % colors.size]
                Triple(c[0], c[1], c[2])
            }
        }
    }

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float {
        val shapes = (params["shapeCount"] as? Number)?.toInt() ?: 4
        return (shapes * 0.25f).coerceIn(0.2f, 1f)
    }
}
