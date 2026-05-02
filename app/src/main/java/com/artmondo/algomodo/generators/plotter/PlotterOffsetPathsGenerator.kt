package com.artmondo.algomodo.generators.plotter

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
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
 * Supports 4 composition modes, 4 line styles, 5 movement modes, 5 color modes,
 * and optional 3D Phong-lit relief.
 */
class PlotterOffsetPathsGenerator : Generator {

    override val id = "plotter-offset-paths"
    override val family = "plotter"
    override val styleName = "Offset Paths"
    override val definition =
        "Draws concentric iso-distance rings around randomly placed seed shapes, using a per-pixel signed-distance field to locate ring boundaries."
    override val algorithmNotes =
        "Seed shapes (circles, rectangles, triangles, stars, hexagons, or noise-warped blobs) are placed " +
        "with a jittered grid. For every pixel the global SDF is evaluated with the chosen composition mode " +
        "(union, smooth-union, xor, difference), then perturbed with FBM noise for a hand-drawn look. " +
        "Ring boundaries are detected where the perturbed SDF crosses multiples of the spacing value. " +
        "Supports dashed, tapered, and double-line styles, plus optional 3D Phong-lit relief."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema = listOf(
        Parameter.SelectParam("Composition", "composition", ParamGroup.COMPOSITION,
            "union: merge shapes | smooth-union: organic melt | xor: cut overlap | difference: interference rings",
            listOf("union", "smooth-union", "xor", "difference"), "union"),
        Parameter.NumberParam("Ring Count", "ringCount", ParamGroup.COMPOSITION,
            "Number of concentric offset rings around each seed shape", 4f, 40f, 1f, 16f),
        Parameter.NumberParam("Ring Spacing", "ringSpacing", ParamGroup.GEOMETRY,
            "Pixel gap between successive rings", 4f, 40f, 1f, 14f),
        Parameter.NumberParam("Shape Count", "shapeCount", ParamGroup.COMPOSITION,
            "Number of seed shapes to offset around", 1f, 12f, 1f, 4f),
        Parameter.SelectParam("Shape Type", "shapeType", ParamGroup.COMPOSITION, null,
            listOf("circles", "rectangles", "mixed", "blobs", "triangles", "stars", "hexagons"), "circles"),
        Parameter.NumberParam("Zoom", "zoom", ParamGroup.GEOMETRY,
            "Camera zoom — values below 1 zoom out", 0.2f, 2f, 0.05f, 1f),
        Parameter.NumberParam("Line Width", "lineWidth", ParamGroup.GEOMETRY, null,
            0.25f, 3f, 0.25f, 0.8f),
        Parameter.NumberParam("Wobble", "wobble", ParamGroup.TEXTURE,
            "Noise-based perturbation of the SDF surface — gives hand-drawn character",
            0f, 6f, 0.25f, 1.0f),
        Parameter.NumberParam("Wobble Scale", "wobbleScale", ParamGroup.TEXTURE,
            "Spatial frequency of the wobble noise", 0.5f, 6f, 0.25f, 2.0f),
        Parameter.SelectParam("Style", "style", ParamGroup.TEXTURE,
            "solid: continuous | dashed: angular dash | tapered: calligraphic | double-line: twin parallel",
            listOf("solid", "dashed", "tapered", "double-line"), "solid"),
        Parameter.BooleanParam("Fill Bands", "fillBands", ParamGroup.TEXTURE,
            "Fill the space between rings with color for a topographic map look", false),
        Parameter.BooleanParam("3D Relief", "render3D", ParamGroup.TEXTURE,
            "Apply Phong-lit 3D relief using SDF distance as height map", false),
        Parameter.SelectParam("Color Mode", "colorMode", ParamGroup.COLOR,
            "palette-rings: cycle palette | elevation: ramp by depth | alternating: two-color flip | radial: angle from center | monochrome: single color",
            listOf("palette-rings", "elevation", "alternating", "radial", "monochrome"), "palette-rings"),
        Parameter.SelectParam("Background", "background", ParamGroup.COLOR, null,
            listOf("white", "cream", "dark"), "cream"),
        Parameter.SelectParam("Movement", "movement", ParamGroup.FLOW_MOTION,
            "drift: float along random angles | orbit: rotate around center | breathe: pulse size | scatter: radial oscillation",
            listOf("none", "drift", "orbit", "breathe", "scatter"), "none"),
        Parameter.NumberParam("Anim Speed", "animSpeed", ParamGroup.FLOW_MOTION,
            "Speed of wobble field drift and movement — 0 = static", 0f, 1f, 0.05f, 0.1f)
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "composition" to "union",
        "ringCount" to 16f,
        "ringSpacing" to 14f,
        "shapeCount" to 4f,
        "shapeType" to "circles",
        "zoom" to 1f,
        "lineWidth" to 0.8f,
        "wobble" to 1.0f,
        "wobbleScale" to 2.0f,
        "style" to "solid",
        "fillBands" to false,
        "render3D" to false,
        "colorMode" to "palette-rings",
        "background" to "cream",
        "movement" to "none",
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
        private const val TYPE_HEXAGON = 4

        // Composition mode IDs
        private const val COMP_UNION = 0
        private const val COMP_SMOOTH = 1
        private const val COMP_XOR = 2
        private const val COMP_DIFF = 3

        // Style IDs
        private const val STYLE_SOLID = 0
        private const val STYLE_DASHED = 1
        private const val STYLE_TAPERED = 2
        private const val STYLE_DOUBLE = 3

        // Move IDs
        private const val MOVE_NONE = 0
        private const val MOVE_DRIFT = 1
        private const val MOVE_ORBIT = 2
        private const val MOVE_BREATHE = 3
        private const val MOVE_SCATTER = 4

        // Triangle constants (Quilez formulation)
        private const val SQRT3 = 1.7320508075688772f
        private const val CIRCUM = 0.8660254037844386f // sqrt(3)/2

        // Star5 precomputed constants (Quilez sdStar5)
        private const val STAR_RF = 0.38f
        private const val STAR_K1X = 0.809016994375f
        private const val STAR_K1Y = -0.587785252292f
        private const val STAR_BAX = STAR_RF * 0.587785252292f
        private const val STAR_BAY = STAR_RF * 0.809016994375f - 1f
        private const val STAR_BASQ = STAR_BAX * STAR_BAX + STAR_BAY * STAR_BAY
    }

    // Shape SoA data
    private class ShapeData(count: Int) {
        val type = IntArray(count)
        val cx = FloatArray(count)
        val cy = FloatArray(count)
        val r = FloatArray(count)
        val hw = FloatArray(count)
        val hh = FloatArray(count)
        val cosR = FloatArray(count)
        val sinR = FloatArray(count)
        val boundR = FloatArray(count)
        // Base positions for movement
        val baseCx = FloatArray(count)
        val baseCy = FloatArray(count)
        val baseR = FloatArray(count)
        val baseHw = FloatArray(count)
        val baseHh = FloatArray(count)
        val phase = FloatArray(count)
        var size = 0
    }

    private val filterPaint = Paint(Paint.FILTER_BITMAP_FLAG)

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

        val composition = (params["composition"] as? String) ?: "union"
        val ringCount = ((params["ringCount"] as? Number)?.toInt() ?: 16).coerceAtLeast(1)
        val spacing = ((params["ringSpacing"] as? Number)?.toFloat() ?: 14f).coerceAtLeast(2f)
        val shapeCount = ((params["shapeCount"] as? Number)?.toInt() ?: 4).coerceIn(1, 12)
        val shapeType = (params["shapeType"] as? String) ?: "circles"
        val zoom = ((params["zoom"] as? Number)?.toFloat() ?: 1f).coerceAtLeast(0.2f)
        val wobble = (params["wobble"] as? Number)?.toFloat() ?: 1.0f
        val wobbleScale = (params["wobbleScale"] as? Number)?.toFloat() ?: 2.0f
        val fillBands = (params["fillBands"] as? Boolean) ?: false
        val render3D = (params["render3D"] as? Boolean) ?: false
        val lineWidth = (params["lineWidth"] as? Number)?.toFloat() ?: 0.8f
        val style = (params["style"] as? String) ?: "solid"
        val colorMode = (params["colorMode"] as? String) ?: "palette-rings"
        val background = (params["background"] as? String) ?: "cream"
        val movement = (params["movement"] as? String) ?: "none"
        val isDark = background == "dark"
        val animSpeed = (params["animSpeed"] as? Number)?.toFloat() ?: 0.1f
        val tOff = time * animSpeed * 0.3f
        val moveT = time * animSpeed

        val compMode = when (composition) {
            "smooth-union" -> COMP_SMOOTH
            "xor" -> COMP_XOR
            "difference" -> COMP_DIFF
            else -> COMP_UNION
        }
        val styleId = when (style) {
            "dashed" -> STYLE_DASHED
            "tapered" -> STYLE_TAPERED
            "double-line" -> STYLE_DOUBLE
            else -> STYLE_SOLID
        }
        val moveId = when (movement) {
            "drift" -> MOVE_DRIFT
            "orbit" -> MOVE_ORBIT
            "breathe" -> MOVE_BREATHE
            "scatter" -> MOVE_SCATTER
            else -> MOVE_NONE
        }

        val bg = BG[background] ?: BG["cream"]!!
        val bgR = bg[0]; val bgG = bg[1]; val bgB = bg[2]
        val bgColor = Color.rgb(bgR, bgG, bgB)

        val baseHalfLW = lineWidth * 0.5f
        val globalAlpha = if (isDark) 0.88f else 0.82f
        val maxDist = ringCount * spacing
        val smoothK = maxDist * 0.4f
        val wobbleMult = if (shapeType == "blobs") 0.7f else 0.35f
        val wobbleFactor = wobble * spacing * wobbleMult
        val wobbleBound = wobbleFactor * 1.5f
        val invSpacing = 1f / spacing
        val invZoom = 1f / zoom
        val halfW = wf * 0.5f
        val halfH = hf * 0.5f
        val doubleDelta = spacing * 0.15f
        val isRadial = colorMode == "radial"
        val bandBase = globalAlpha * 0.5f
        val bandExtra = globalAlpha - bandBase
        val invPI2 = 1f / (PI.toFloat() * 2f)
        val invMaxDist = 1f / maxDist

        // Half-res during animation
        val isAnim = time > 0f
        val halfRes = isAnim || quality == Quality.DRAFT
        val rw = if (halfRes) ((w + 1) shr 1) else w
        val rh = if (halfRes) ((h + 1) shr 1) else h
        val scX = wf / rw
        val scY = hf / rh

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
                shapes.cx[i] = (col + 0.25f + rng.range(0f, 0.5f)) * cw
                shapes.cy[i] = (row + 0.25f + rng.range(0f, 0.5f)) * ch
                val baseR = (0.15f + rng.range(0f, 0.15f)) * minDim
                val rot = rng.range(0f, 2f * PI.toFloat())
                shapes.cosR[i] = cos(-rot)
                shapes.sinR[i] = sin(-rot)
                shapes.r[i] = baseR
                val aspect = 0.6f + rng.range(0f, 0.8f)
                shapes.hw[i] = baseR * aspect
                shapes.hh[i] = baseR / aspect
                shapes.phase[i] = rng.range(0f, 2f * PI.toFloat())

                shapes.type[i] = when (shapeType) {
                    "circles", "blobs" -> TYPE_CIRCLE
                    "rectangles" -> TYPE_RECT
                    "triangles" -> TYPE_TRIANGLE
                    "stars" -> TYPE_STAR
                    "hexagons" -> TYPE_HEXAGON
                    else -> { // mixed
                        val pick = rng.range(0f, 1f)
                        when {
                            pick < 0.2f -> TYPE_CIRCLE
                            pick < 0.36f -> TYPE_RECT
                            pick < 0.52f -> TYPE_TRIANGLE
                            pick < 0.68f -> TYPE_STAR
                            pick < 0.84f -> TYPE_HEXAGON
                            else -> TYPE_CIRCLE
                        }
                    }
                }

                // Store base positions for movement
                shapes.baseCx[i] = shapes.cx[i]
                shapes.baseCy[i] = shapes.cy[i]
                shapes.baseR[i] = baseR
                shapes.baseHw[i] = shapes.hw[i]
                shapes.baseHh[i] = shapes.hh[i]
                shapes.size++
            }
        }

        val numShapes = shapes.size

        // --- Apply movement ---
        if (moveId != MOVE_NONE) {
            val amp = minDim * 0.08f
            for (i in 0 until numShapes) {
                shapes.cx[i] = shapes.baseCx[i]
                shapes.cy[i] = shapes.baseCy[i]
                shapes.r[i] = shapes.baseR[i]
                shapes.hw[i] = shapes.baseHw[i]
                shapes.hh[i] = shapes.baseHh[i]
                val ph = shapes.phase[i]
                when (moveId) {
                    MOVE_DRIFT -> {
                        shapes.cx[i] += cos(ph) * amp * sin(moveT + ph)
                        shapes.cy[i] += sin(ph) * amp * sin(moveT * 0.7f + ph)
                    }
                    MOVE_ORBIT -> {
                        val dx = shapes.baseCx[i] - halfW
                        val dy = shapes.baseCy[i] - halfH
                        val oR = sqrt(dx * dx + dy * dy)
                        val oA = atan2(dy, dx) + moveT * 0.5f
                        shapes.cx[i] = halfW + oR * cos(oA)
                        shapes.cy[i] = halfH + oR * sin(oA)
                    }
                    MOVE_BREATHE -> {
                        val s = 1f + 0.3f * sin(moveT * 2f + ph)
                        shapes.r[i] *= s
                        shapes.hw[i] *= s
                        shapes.hh[i] *= s
                    }
                    MOVE_SCATTER -> {
                        val dx = shapes.baseCx[i] - halfW
                        val dy = shapes.baseCy[i] - halfH
                        val dist = sqrt(dx * dx + dy * dy).coerceAtLeast(1f)
                        val push = amp * sin(moveT + ph)
                        shapes.cx[i] += (dx / dist) * push
                        shapes.cy[i] += (dy / dist) * push
                    }
                }
            }
        }

        // --- Compute bounding radii ---
        for (i in 0 until numShapes) {
            val maxExtent = when (shapes.type[i]) {
                TYPE_RECT -> sqrt(shapes.hw[i] * shapes.hw[i] + shapes.hh[i] * shapes.hh[i])
                else -> shapes.r[i]
            }
            shapes.boundR[i] = maxExtent + maxDist + wobbleBound
        }

        // --- Ring color LUTs ---
        val paletteColors = palette.colorInts()
        val colorsRgb = Array(paletteColors.size) {
            intArrayOf(Color.red(paletteColors[it]), Color.green(paletteColors[it]), Color.blue(paletteColors[it]))
        }
        val nColors = colorsRgb.size
        val ringDR = FloatArray(ringCount)
        val ringDG = FloatArray(ringCount)
        val ringDB = FloatArray(ringCount)
        val fillLut = IntArray(ringCount)

        when (colorMode) {
            "monochrome" -> {
                val c = colorsRgb[0]
                for (ri in 0 until ringCount) {
                    ringDR[ri] = (c[0] - bgR).toFloat()
                    ringDG[ri] = (c[1] - bgG).toFloat()
                    ringDB[ri] = (c[2] - bgB).toFloat()
                    val a = globalAlpha * 0.5f
                    fillLut[ri] = Color.rgb(
                        (bgR + ringDR[ri] * a).toInt(),
                        (bgG + ringDG[ri] * a).toInt(),
                        (bgB + ringDB[ri] * a).toInt()
                    )
                }
            }
            "alternating" -> {
                val c0 = colorsRgb[0]
                val c1 = colorsRgb[nColors - 1]
                for (ri in 0 until ringCount) {
                    val c = if (ri % 2 == 0) c0 else c1
                    ringDR[ri] = (c[0] - bgR).toFloat()
                    ringDG[ri] = (c[1] - bgG).toFloat()
                    ringDB[ri] = (c[2] - bgB).toFloat()
                    val a = globalAlpha * 0.5f
                    fillLut[ri] = Color.rgb(
                        (bgR + ringDR[ri] * a).toInt().coerceIn(0, 255),
                        (bgG + ringDG[ri] * a).toInt().coerceIn(0, 255),
                        (bgB + ringDB[ri] * a).toInt().coerceIn(0, 255)
                    )
                }
            }
            "elevation" -> {
                val maxRi = max(1, ringCount - 1).toFloat()
                for (ri in 0 until ringCount) {
                    val t = ri / maxRi
                    val ci = t * (nColors - 1)
                    val i0 = floor(ci).toInt()
                    val i1 = min(nColors - 1, i0 + 1)
                    val f = ci - i0
                    val cr = (colorsRgb[i0][0] + (colorsRgb[i1][0] - colorsRgb[i0][0]) * f).toInt().coerceIn(0, 255)
                    val cg = (colorsRgb[i0][1] + (colorsRgb[i1][1] - colorsRgb[i0][1]) * f).toInt().coerceIn(0, 255)
                    val cb = (colorsRgb[i0][2] + (colorsRgb[i1][2] - colorsRgb[i0][2]) * f).toInt().coerceIn(0, 255)
                    ringDR[ri] = (cr - bgR).toFloat()
                    ringDG[ri] = (cg - bgG).toFloat()
                    ringDB[ri] = (cb - bgB).toFloat()
                    val a = globalAlpha * 0.5f
                    fillLut[ri] = Color.rgb(
                        (bgR + ringDR[ri] * a).toInt().coerceIn(0, 255),
                        (bgG + ringDG[ri] * a).toInt().coerceIn(0, 255),
                        (bgB + ringDB[ri] * a).toInt().coerceIn(0, 255)
                    )
                }
            }
            else -> { // palette-rings (radial handled per-pixel)
                for (ri in 0 until ringCount) {
                    val c = colorsRgb[ri % nColors]
                    ringDR[ri] = (c[0] - bgR).toFloat()
                    ringDG[ri] = (c[1] - bgG).toFloat()
                    ringDB[ri] = (c[2] - bgB).toFloat()
                    val a = globalAlpha * 0.5f
                    fillLut[ri] = Color.rgb(
                        (bgR + ringDR[ri] * a).toInt().coerceIn(0, 255),
                        (bgG + ringDG[ri] * a).toInt().coerceIn(0, 255),
                        (bgB + ringDB[ri] * a).toInt().coerceIn(0, 255)
                    )
                }
            }
        }

        // --- Pre-compute noise grid ---
        val noiseGrid: FloatArray?
        val ngStep: Int
        val ngW: Int
        val ngH: Int
        if (wobble > 0f) {
            ngStep = when {
                halfRes -> 8
                quality == Quality.DRAFT -> 8
                quality == Quality.BALANCED -> 4
                else -> 2
            }
            ngW = (rw + ngStep - 1) / ngStep + 1
            ngH = (rh + ngStep - 1) / ngStep + 1
            noiseGrid = FloatArray(ngW * ngH)
            val invW = wobbleScale / rw
            val invH = wobbleScale / rh
            for (gy in 0 until ngH) {
                val pyf = (gy * ngStep).toFloat()
                val ny = pyf * invH + tOff * 0.7f
                val rowOff = gy * ngW
                for (gx in 0 until ngW) {
                    val pxf = (gx * ngStep).toFloat()
                    val nx = pxf * invW + tOff
                    noiseGrid[rowOff + gx] = noise.fbm(nx, ny, 3, 2f, 0.5f)
                }
            }
        } else {
            noiseGrid = null
            ngStep = 1
            ngW = 0
            ngH = 0
        }

        // --- Per-pixel rendering ---
        val pixels = IntArray(rw * rh)
        pixels.fill(bgColor)

        // Height buffer for 3D relief
        val hBuf = if (render3D) FloatArray(rw * rh) else null

        // Pre-extract shape arrays
        val sType = shapes.type
        val sCx = shapes.cx; val sCy = shapes.cy
        val sR = shapes.r; val sHw = shapes.hw; val sHh = shapes.hh
        val sCosR = shapes.cosR; val sSinR = shapes.sinR
        val sBoundR = shapes.boundR

        val invStep = 1f / ngStep

        for (py in 0 until rh) {
            val cy = halfH + (py * scY - halfH) * invZoom
            val pixelRowOff = py * rw

            // Noise row pre-compute
            val ngy = if (noiseGrid != null) py.toFloat() * invStep else 0f
            val nj0 = if (noiseGrid != null) ngy.toInt().coerceIn(0, ngH - 2) else 0
            val nfy = if (noiseGrid != null) ngy - nj0 else 0f
            val nr0 = nj0 * ngW
            val nr1 = nr0 + ngW

            for (px in 0 until rw) {
                val cx = halfW + (px * scX - halfW) * invZoom

                // Evaluate SDF with composition mode
                var d = Float.MAX_VALUE
                var d2 = if (compMode == COMP_XOR) -Float.MAX_VALUE else Float.MAX_VALUE
                var nearSi = 0
                for (si in 0 until numShapes) {
                    val dx = cx - sCx[si]
                    val dy = cy - sCy[si]
                    val distSq = dx * dx + dy * dy

                    // Bounding check (only for union — other modes need all shapes)
                    if (compMode == COMP_UNION) {
                        val br = sBoundR[si]
                        if (distSq > br * br) continue
                    }

                    // Rotate to shape-local space
                    val lx = dx * sCosR[si] - dy * sSinR[si]
                    val ly = dx * sSinR[si] + dy * sCosR[si]

                    val raw = when (sType[si]) {
                        TYPE_RECT -> sdBox(lx, ly, sHw[si], sHh[si])
                        TYPE_TRIANGLE -> sdTriangle(lx, ly, sR[si] * CIRCUM)
                        TYPE_STAR -> sdStar5(lx, ly, sR[si])
                        TYPE_HEXAGON -> sdHexagon(lx, ly, sR[si] * CIRCUM)
                        else -> sqrt(distSq) - sR[si] // circle (no rotation needed)
                    }

                    when (compMode) {
                        COMP_XOR -> {
                            if (raw < d) { nearSi = si; d = raw }
                            if (raw > d2) d2 = raw
                        }
                        COMP_DIFF -> {
                            if (raw < d) { d2 = d; d = raw; nearSi = si }
                            else if (raw < d2) d2 = raw
                        }
                        COMP_SMOOTH -> {
                            if (raw < d) nearSi = si
                            d = smin(d, raw, smoothK)
                        }
                        else -> { // union
                            if (raw < d) { d = raw; nearSi = si }
                        }
                    }
                }

                // Composition post-processing
                when (compMode) {
                    COMP_XOR -> d = max(d, -d2)
                    COMP_DIFF -> d = abs(d - d2)
                }

                // Coarse bounds check before noise
                if (d < -wobbleBound) {
                    if (hBuf != null) hBuf[pixelRowOff + px] = 1f
                    continue
                }
                if (d >= maxDist + wobbleBound) continue

                // Apply wobble via bilinear interpolation
                if (noiseGrid != null) {
                    val ngx = px.toFloat() * invStep
                    val ni0 = ngx.toInt().coerceIn(0, ngW - 2)
                    val nfx = ngx - ni0
                    val n00 = noiseGrid[nr0 + ni0]
                    val n10 = noiseGrid[nr0 + ni0 + 1]
                    val n01 = noiseGrid[nr1 + ni0]
                    val n11 = noiseGrid[nr1 + ni0 + 1]
                    val wn = (1f - nfy) * ((1f - nfx) * n00 + nfx * n10) +
                             nfy * ((1f - nfx) * n01 + nfx * n11)
                    d += wn * wobbleFactor
                }

                // Store height for 3D relief
                if (hBuf != null) {
                    if (d < 0f) {
                        hBuf[pixelRowOff + px] = 1f
                    } else if (d < maxDist) {
                        val ringPhase = (d * invSpacing) % 1f
                        val tri = 1f - 2f * abs(ringPhase - 0.5f)
                        hBuf[pixelRowOff + px] = (1f - d * invMaxDist) + tri * 0.15f
                    }
                }

                // Final bounds
                if (d < 0f || d >= maxDist) continue

                // Ring detection
                val ringIdxF = d * invSpacing
                val ringIdx = ringIdxF.toInt()
                if (ringIdx >= ringCount) continue
                val frac = ringIdxF - ringIdx
                var distFromEdge = (if (frac < 0.5f) frac else 1f - frac) * spacing

                // Style effects
                var effHalfLW = baseHalfLW
                when (styleId) {
                    STYLE_DOUBLE -> {
                        distFromEdge = abs(distFromEdge - doubleDelta)
                        effHalfLW = baseHalfLW * 0.5f
                    }
                    STYLE_DASHED, STYLE_TAPERED -> {
                        val angle = atan2(cy - sCy[nearSi], cx - sCx[nearSi])
                        if (styleId == STYLE_DASHED) {
                            if (sin(angle * 6f + ringIdx * 0.8f) < 0f) continue
                        } else { // tapered
                            effHalfLW = baseHalfLW * (0.3f + 0.7f * (0.5f + 0.5f * sin(angle + ringIdx * 0.3f)))
                        }
                    }
                }

                // Color lookup
                val dr: Float; val dg: Float; val db: Float
                var fillPx: Int
                if (isRadial) {
                    val ca = atan2(cy - halfH, cx - halfW)
                    val t = (ca + PI.toFloat()) * invPI2
                    val ci = (t * nColors).toInt() % nColors
                    val c = colorsRgb[ci]
                    dr = (c[0] - bgR).toFloat()
                    dg = (c[1] - bgG).toFloat()
                    db = (c[2] - bgB).toFloat()
                    val fa = globalAlpha * 0.5f
                    fillPx = Color.rgb(
                        (bgR + dr * fa).toInt().coerceIn(0, 255),
                        (bgG + dg * fa).toInt().coerceIn(0, 255),
                        (bgB + db * fa).toInt().coerceIn(0, 255)
                    )
                } else {
                    dr = ringDR[ringIdx]; dg = ringDG[ringIdx]; db = ringDB[ringIdx]
                    fillPx = fillLut[ringIdx]
                }

                val edgeThreshold = effHalfLW + 1f
                if (fillBands) {
                    if (distFromEdge < edgeThreshold) {
                        val lineAlpha = min(edgeThreshold - distFromEdge, 1f)
                        val fa = bandBase + bandExtra * lineAlpha
                        pixels[pixelRowOff + px] = Color.rgb(
                            (bgR + dr * fa).toInt().coerceIn(0, 255),
                            (bgG + dg * fa).toInt().coerceIn(0, 255),
                            (bgB + db * fa).toInt().coerceIn(0, 255)
                        )
                    } else {
                        pixels[pixelRowOff + px] = fillPx
                    }
                } else {
                    if (distFromEdge > edgeThreshold) continue
                    val alpha = min(edgeThreshold - distFromEdge, 1f) * globalAlpha
                    pixels[pixelRowOff + px] = Color.rgb(
                        (bgR + dr * alpha).toInt().coerceIn(0, 255),
                        (bgG + dg * alpha).toInt().coerceIn(0, 255),
                        (bgB + db * alpha).toInt().coerceIn(0, 255)
                    )
                }
            }
        }

        // --- 3D relief post-pass (Phong lighting) ---
        if (render3D && hBuf != null) {
            val hs = 25f
            val nlx = -0.508f; val nly = -0.609f; val nlz = 0.609f
            val ambient = 0.3f
            for (py in 0 until rh) {
                for (px in 0 until rw) {
                    val idx = py * rw + px
                    val hv = hBuf[idx]
                    val hr = if (px + 1 < rw) hBuf[idx + 1] else hv
                    val hd = if (py + 1 < rh) hBuf[idx + rw] else hv
                    val dhdx = (hr - hv) * hs
                    val dhdy = (hd - hv) * hs
                    val nLen = 1f / sqrt(dhdx * dhdx + dhdy * dhdy + 1f)
                    val nx = -dhdx * nLen
                    val ny = -dhdy * nLen
                    val nz = nLen
                    val dotNL = nx * nlx + ny * nly + nz * nlz
                    val brightness = ambient + max(0f, dotNL) * (1f - ambient)
                    val rz = 2f * dotNL * nz - nlz
                    val spec255 = if (rz > 0f) 0.25f * rz.pow(16f) * 255f else 0f

                    val c = pixels[idx]
                    val cr = min(255f, (Color.red(c) * brightness + spec255)).toInt()
                    val cg = min(255f, (Color.green(c) * brightness + spec255)).toInt()
                    val cb = min(255f, (Color.blue(c) * brightness + spec255)).toInt()
                    pixels[idx] = Color.rgb(cr, cg, cb)
                }
            }
        }

        // --- Output ---
        if (halfRes) {
            val smallBmp = Bitmap.createBitmap(rw, rh, Bitmap.Config.ARGB_8888)
            smallBmp.setPixels(pixels, 0, rw, 0, 0, rw, rh)
            canvas.drawBitmap(smallBmp, null, Rect(0, 0, w, h), filterPaint)
            smallBmp.recycle()
        } else {
            bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
            canvas.drawBitmap(bitmap, 0f, 0f, null)
        }
    }

    // ── Quilez SDF primitives ──

    private fun sdBox(px: Float, py: Float, hw: Float, hh: Float): Float {
        val qx = abs(px) - hw
        val qy = abs(py) - hh
        val ox = max(qx, 0f); val oy = max(qy, 0f)
        return sqrt(ox * ox + oy * oy) + min(max(qx, qy), 0f)
    }

    private fun sdTriangle(px: Float, py: Float, r: Float): Float {
        var qx = abs(px) - r
        var qy = py + r / SQRT3
        if (qx + SQRT3 * qy > 0f) {
            val tx = (qx - SQRT3 * qy) * 0.5f
            qy = (-SQRT3 * qx - qy) * 0.5f
            qx = tx
        }
        qx -= qx.coerceIn(-2f * r, 0f)
        val len = sqrt(qx * qx + qy * qy)
        return if (qy > 0f) -len else len
    }

    private fun sdHexagon(px: Float, py: Float, r: Float): Float {
        var qx = abs(px)
        var qy = abs(py)
        val dot = 2f * min(-0.866025404f * qx + 0.5f * qy, 0f)
        qx -= dot * -0.866025404f
        qy -= dot * 0.5f
        qx -= qx.coerceIn(-0.577350269f * r, 0.577350269f * r)
        qy -= r
        val len = sqrt(qx * qx + qy * qy)
        return if (qy >= 0f) len else -len
    }

    private fun sdStar5(px: Float, py: Float, r: Float): Float {
        var qx = abs(px)
        var qy = py
        val d1 = STAR_K1X * qx + STAR_K1Y * qy
        if (d1 > 0f) { qx -= 2f * d1 * STAR_K1X; qy -= 2f * d1 * STAR_K1Y }
        val d2 = -STAR_K1X * qx + STAR_K1Y * qy
        if (d2 > 0f) { qx += 2f * d2 * STAR_K1X; qy -= 2f * d2 * STAR_K1Y }
        qx = abs(qx)
        qy -= r
        val h = ((qx * STAR_BAX + qy * STAR_BAY) / STAR_BASQ).coerceIn(0f, r)
        val ex = qx - STAR_BAX * h
        val ey = qy - STAR_BAY * h
        val len = sqrt(ex * ex + ey * ey)
        val cross = qy * STAR_BAX - qx * STAR_BAY
        return if (cross >= 0f) len else -len
    }

    private fun smin(a: Float, b: Float, k: Float): Float {
        val h = max(k - abs(a - b), 0f) / k
        return min(a, b) - h * h * k * 0.25f
    }

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float {
        val shapes = (params["shapeCount"] as? Number)?.toInt() ?: 4
        return (shapes * 0.25f).coerceIn(0.2f, 1f)
    }
}
