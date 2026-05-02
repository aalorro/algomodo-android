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

        private const val COMP_UNION = 0
        private const val COMP_SMOOTH = 1
        private const val COMP_XOR = 2
        private const val COMP_DIFF = 3

        private const val STYLE_SOLID = 0
        private const val STYLE_DASHED = 1
        private const val STYLE_TAPERED = 2
        private const val STYLE_DOUBLE = 3

        private const val MOVE_NONE = 0
        private const val MOVE_DRIFT = 1
        private const val MOVE_ORBIT = 2
        private const val MOVE_BREATHE = 3
        private const val MOVE_SCATTER = 4

        private const val SQRT3 = 1.7320508075688772f
        private const val CIRCUM = 0.8660254037844386f

        private const val STAR_RF = 0.38f
        private const val STAR_K1X = 0.809016994375f
        private const val STAR_K1Y = -0.587785252292f
        private const val STAR_BAX = STAR_RF * 0.587785252292f
        private const val STAR_BAY = STAR_RF * 0.809016994375f - 1f
        private const val STAR_BASQ = STAR_BAX * STAR_BAX + STAR_BAY * STAR_BAY

        // Inline color packing — avoids Color.rgb() method call overhead in hot loop
        private const val ALPHA_MASK = 0xFF shl 24

        @Suppress("NOTHING_TO_INLINE")
        private inline fun packRgb(r: Int, g: Int, b: Int): Int =
            ALPHA_MASK or (r shl 16) or (g shl 8) or b

        // Fast pow16 via repeated squaring (4 muls instead of general pow)
        @Suppress("NOTHING_TO_INLINE")
        private inline fun pow16(x: Float): Float {
            val x2 = x * x; val x4 = x2 * x2; val x8 = x4 * x4; return x8 * x8
        }

        // Fast clamp to [0, 255]
        @Suppress("NOTHING_TO_INLINE")
        private inline fun clamp255(v: Float): Int {
            val i = v.toInt()
            return if (i < 0) 0 else if (i > 255) 255 else i
        }

        // Radial angle LUT size (256 entries covers 360°)
        private const val ANGLE_LUT_SIZE = 256
        private const val ANGLE_LUT_MASK = ANGLE_LUT_SIZE - 1
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
        val boundRSq = FloatArray(count) // squared for fast distSq comparison
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
        val bgColor = packRgb(bgR, bgG, bgB)
        val bgRf = bgR.toFloat(); val bgGf = bgG.toFloat(); val bgBf = bgB.toFloat()

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
        val invMaxDist = 1f / maxDist
        val needsAngle = styleId == STYLE_DASHED || styleId == STYLE_TAPERED

        // Adaptive resolution: 3/8 for animation, 1/2 for draft static
        val isAnim = time > 0f
        val rw: Int; val rh: Int
        if (isAnim) {
            rw = (w * 3 / 8).coerceAtLeast(w / 3)
            rh = (h * 3 / 8).coerceAtLeast(h / 3)
        } else if (quality == Quality.DRAFT) {
            rw = (w + 1) shr 1
            rh = (h + 1) shr 1
        } else {
            rw = w
            rh = h
        }
        val scX = wf / rw
        val scY = hf / rh
        val halfRes = rw < w

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
                    else -> {
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

        // --- Compute bounding radii squared (avoids sqrt in per-pixel check) ---
        for (i in 0 until numShapes) {
            val maxExtent = when (shapes.type[i]) {
                TYPE_RECT -> sqrt(shapes.hw[i] * shapes.hw[i] + shapes.hh[i] * shapes.hh[i])
                else -> shapes.r[i]
            }
            val br = maxExtent + maxDist + wobbleBound
            shapes.boundRSq[i] = br * br
        }

        // --- Ring color LUTs (pre-blended fill as packed int) ---
        val paletteColors = palette.colorInts()
        val colorsRgb = Array(paletteColors.size) {
            intArrayOf(Color.red(paletteColors[it]), Color.green(paletteColors[it]), Color.blue(paletteColors[it]))
        }
        val nColors = colorsRgb.size
        val ringDR = FloatArray(ringCount)
        val ringDG = FloatArray(ringCount)
        val ringDB = FloatArray(ringCount)
        val fillLut = IntArray(ringCount)

        for (ri in 0 until ringCount) {
            val cr: Int; val cg: Int; val cb: Int
            when (colorMode) {
                "monochrome" -> {
                    cr = colorsRgb[0][0]; cg = colorsRgb[0][1]; cb = colorsRgb[0][2]
                }
                "alternating" -> {
                    val c = if (ri % 2 == 0) colorsRgb[0] else colorsRgb[nColors - 1]
                    cr = c[0]; cg = c[1]; cb = c[2]
                }
                "elevation" -> {
                    val maxRi = max(1, ringCount - 1).toFloat()
                    val t = ri / maxRi
                    val ci = t * (nColors - 1)
                    val i0 = ci.toInt()
                    val i1 = min(nColors - 1, i0 + 1)
                    val f = ci - i0
                    cr = (colorsRgb[i0][0] + (colorsRgb[i1][0] - colorsRgb[i0][0]) * f).toInt()
                    cg = (colorsRgb[i0][1] + (colorsRgb[i1][1] - colorsRgb[i0][1]) * f).toInt()
                    cb = (colorsRgb[i0][2] + (colorsRgb[i1][2] - colorsRgb[i0][2]) * f).toInt()
                }
                else -> { // palette-rings
                    val c = colorsRgb[ri % nColors]
                    cr = c[0]; cg = c[1]; cb = c[2]
                }
            }
            ringDR[ri] = (cr - bgR).toFloat()
            ringDG[ri] = (cg - bgG).toFloat()
            ringDB[ri] = (cb - bgB).toFloat()
            val a = globalAlpha * 0.5f
            fillLut[ri] = packRgb(
                clamp255(bgRf + ringDR[ri] * a),
                clamp255(bgGf + ringDG[ri] * a),
                clamp255(bgBf + ringDB[ri] * a)
            )
        }

        // --- Pre-compute radial color LUT (avoids per-pixel atan2 for radial mode) ---
        val radialDR: FloatArray?
        val radialDG: FloatArray?
        val radialDB: FloatArray?
        val radialFill: IntArray?
        if (isRadial) {
            radialDR = FloatArray(ANGLE_LUT_SIZE)
            radialDG = FloatArray(ANGLE_LUT_SIZE)
            radialDB = FloatArray(ANGLE_LUT_SIZE)
            radialFill = IntArray(ANGLE_LUT_SIZE)
            for (ai in 0 until ANGLE_LUT_SIZE) {
                val t = ai.toFloat() / ANGLE_LUT_SIZE
                val ci = (t * nColors).toInt() % nColors
                val c = colorsRgb[ci]
                val dr = (c[0] - bgR).toFloat()
                val dg = (c[1] - bgG).toFloat()
                val db = (c[2] - bgB).toFloat()
                radialDR[ai] = dr; radialDG[ai] = dg; radialDB[ai] = db
                val fa = globalAlpha * 0.5f
                radialFill[ai] = packRgb(
                    clamp255(bgRf + dr * fa),
                    clamp255(bgGf + dg * fa),
                    clamp255(bgBf + db * fa)
                )
            }
        } else {
            radialDR = null; radialDG = null; radialDB = null; radialFill = null
        }

        // --- Pre-compute noise grid ---
        val noiseGrid: FloatArray?
        val ngW: Int; val ngH: Int; val invStep: Float
        if (wobble > 0f) {
            val ngStep = if (isAnim || quality == Quality.DRAFT) 8 else if (quality == Quality.BALANCED) 4 else 2
            ngW = (rw + ngStep - 1) / ngStep + 1
            ngH = (rh + ngStep - 1) / ngStep + 1
            noiseGrid = FloatArray(ngW * ngH)
            invStep = 1f / ngStep
            val invW = wobbleScale / rw
            val invH = wobbleScale / rh
            for (gy in 0 until ngH) {
                val pyf = (gy * ngStep).toFloat()
                val ny = pyf * invH + tOff * 0.7f
                val rowOff = gy * ngW
                for (gx in 0 until ngW) {
                    val pxf = (gx * ngStep).toFloat()
                    noiseGrid[rowOff + gx] = noise.fbm(pxf * invW + tOff, ny, 3, 2f, 0.5f)
                }
            }
        } else {
            noiseGrid = null; ngW = 0; ngH = 0; invStep = 1f
        }

        // --- Multi-threaded per-pixel rendering ---
        val pixels = IntArray(rw * rh)
        pixels.fill(bgColor)
        val hBuf = if (render3D) FloatArray(rw * rh) else null

        // Extract shape arrays for tight inner loop
        val sType = shapes.type
        val sCx = shapes.cx; val sCy = shapes.cy
        val sR = shapes.r; val sHw = shapes.hw; val sHh = shapes.hh
        val sCosR = shapes.cosR; val sSinR = shapes.sinR
        val sBoundRSq = shapes.boundRSq

        val cores = Runtime.getRuntime().availableProcessors().coerceIn(2, 8)
        val threads = Array(cores) { threadIdx ->
            Thread {
                val y0 = threadIdx * rh / cores
                val y1 = (threadIdx + 1) * rh / cores

                for (py in y0 until y1) {
                    val cy = halfH + (py * scY - halfH) * invZoom
                    val pixelRowOff = py * rw

                    // Noise row pre-compute
                    val ngy = if (noiseGrid != null) py.toFloat() * invStep else 0f
                    val nj0 = if (noiseGrid != null) ngy.toInt().coerceIn(0, ngH - 2) else 0
                    val nfy = if (noiseGrid != null) ngy - nj0 else 0f
                    val nfy1 = 1f - nfy
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

                            // Bounding check (squared — no sqrt)
                            if (distSq > sBoundRSq[si]) continue

                            // Rotate to shape-local space
                            val lx = dx * sCosR[si] - dy * sSinR[si]
                            val ly = dx * sSinR[si] + dy * sCosR[si]

                            val raw = when (sType[si]) {
                                TYPE_RECT -> {
                                    val qx = abs(lx) - sHw[si]
                                    val qy = abs(ly) - sHh[si]
                                    val ox = if (qx > 0f) qx else 0f
                                    val oy = if (qy > 0f) qy else 0f
                                    sqrt(ox * ox + oy * oy) + if (qx > qy) min(qx, 0f) else min(qy, 0f)
                                }
                                TYPE_TRIANGLE -> sdTriangle(lx, ly, sR[si] * CIRCUM)
                                TYPE_STAR -> sdStar5(lx, ly, sR[si])
                                TYPE_HEXAGON -> sdHexagon(lx, ly, sR[si] * CIRCUM)
                                else -> sqrt(distSq) - sR[si]
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
                                    val absDiff = abs(d - raw)
                                    if (absDiff < smoothK) {
                                        val hh = (smoothK - absDiff) / smoothK
                                        d = min(d, raw) - hh * hh * smoothK * 0.25f
                                    } else {
                                        d = min(d, raw)
                                    }
                                }
                                else -> {
                                    if (raw < d) { d = raw; nearSi = si }
                                }
                            }
                        }

                        // Composition post-processing
                        if (compMode == COMP_XOR) d = max(d, -d2)
                        else if (compMode == COMP_DIFF) d = abs(d - d2)

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
                            val nfx1 = 1f - nfx
                            d += (nfy1 * (nfx1 * noiseGrid[nr0 + ni0] + nfx * noiseGrid[nr0 + ni0 + 1]) +
                                  nfy  * (nfx1 * noiseGrid[nr1 + ni0] + nfx * noiseGrid[nr1 + ni0 + 1])) * wobbleFactor
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

                        if (d < 0f || d >= maxDist) continue

                        // Ring detection
                        val ringIdxF = d * invSpacing
                        val ringIdx = ringIdxF.toInt()
                        if (ringIdx >= ringCount) continue
                        val frac = ringIdxF - ringIdx
                        var distFromEdge = (if (frac < 0.5f) frac else 1f - frac) * spacing

                        // Style effects
                        var effHalfLW = baseHalfLW
                        if (styleId == STYLE_DOUBLE) {
                            distFromEdge = abs(distFromEdge - doubleDelta)
                            effHalfLW = baseHalfLW * 0.5f
                        } else if (needsAngle) {
                            val angle = atan2(cy - sCy[nearSi], cx - sCx[nearSi])
                            if (styleId == STYLE_DASHED) {
                                if (sin(angle * 6f + ringIdx * 0.8f) < 0f) continue
                            } else {
                                effHalfLW = baseHalfLW * (0.3f + 0.7f * (0.5f + 0.5f * sin(angle + ringIdx * 0.3f)))
                            }
                        }

                        val edgeThreshold = effHalfLW + 1f

                        // Color lookup
                        val dr: Float; val dg: Float; val db: Float
                        val fillPx: Int
                        if (radialDR != null) {
                            // Radial: use angle LUT (cheap integer ops instead of atan2)
                            val adx = cx - halfW; val ady = cy - halfH
                            val angle = atan2(ady, adx)
                            val ai = (((angle + PI.toFloat()) * (ANGLE_LUT_SIZE / (2f * PI.toFloat()))).toInt()) and ANGLE_LUT_MASK
                            dr = radialDR[ai]; dg = radialDG!![ai]; db = radialDB!![ai]
                            fillPx = radialFill!![ai]
                        } else {
                            dr = ringDR[ringIdx]; dg = ringDG[ringIdx]; db = ringDB[ringIdx]
                            fillPx = fillLut[ringIdx]
                        }

                        if (fillBands) {
                            if (distFromEdge < edgeThreshold) {
                                val lineAlpha = if (edgeThreshold - distFromEdge < 1f) edgeThreshold - distFromEdge else 1f
                                val fa = bandBase + bandExtra * lineAlpha
                                pixels[pixelRowOff + px] = packRgb(
                                    clamp255(bgRf + dr * fa),
                                    clamp255(bgGf + dg * fa),
                                    clamp255(bgBf + db * fa)
                                )
                            } else {
                                pixels[pixelRowOff + px] = fillPx
                            }
                        } else {
                            if (distFromEdge > edgeThreshold) continue
                            val raw = edgeThreshold - distFromEdge
                            val alpha = (if (raw < 1f) raw else 1f) * globalAlpha
                            pixels[pixelRowOff + px] = packRgb(
                                clamp255(bgRf + dr * alpha),
                                clamp255(bgGf + dg * alpha),
                                clamp255(bgBf + db * alpha)
                            )
                        }
                    }
                }
            }.also { it.start() }
        }
        threads.forEach { it.join() }

        // --- 3D relief post-pass (Phong lighting) — also multi-threaded ---
        if (render3D && hBuf != null) {
            val hs = 25f
            val nlx = -0.508f; val nly = -0.609f; val nlz = 0.609f
            val ambient = 0.3f
            val diffScale = 1f - ambient
            val threads3D = Array(cores) { threadIdx ->
                Thread {
                    val y0 = threadIdx * rh / cores
                    val y1 = (threadIdx + 1) * rh / cores
                    for (py in y0 until y1) {
                        val rowOff = py * rw
                        val nextRow = if (py + 1 < rh) rowOff + rw else rowOff
                        for (px in 0 until rw) {
                            val idx = rowOff + px
                            val hv = hBuf[idx]
                            val hr = if (px + 1 < rw) hBuf[idx + 1] else hv
                            val hd = hBuf[nextRow + px]
                            val dhdx = (hr - hv) * hs
                            val dhdy = (hd - hv) * hs
                            val nLen = 1f / sqrt(dhdx * dhdx + dhdy * dhdy + 1f)
                            val nx = -dhdx * nLen
                            val ny = -dhdy * nLen
                            val nz = nLen
                            val dotNL = nx * nlx + ny * nly + nz * nlz
                            val brightness = ambient + (if (dotNL > 0f) dotNL else 0f) * diffScale
                            val rz = 2f * dotNL * nz - nlz
                            val spec255 = if (rz > 0f) 0.25f * pow16(rz) * 255f else 0f

                            val c = pixels[idx]
                            pixels[idx] = packRgb(
                                min(255, ((c ushr 16 and 0xFF) * brightness + spec255).toInt()),
                                min(255, ((c ushr 8 and 0xFF) * brightness + spec255).toInt()),
                                min(255, ((c and 0xFF) * brightness + spec255).toInt())
                            )
                        }
                    }
                }.also { it.start() }
            }
            threads3D.forEach { it.join() }
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

    // ── Quilez SDF primitives (inlined where possible, private for JIT) ──

    private fun sdTriangle(px: Float, py: Float, r: Float): Float {
        var qx = abs(px) - r
        var qy = py + r / SQRT3
        if (qx + SQRT3 * qy > 0f) {
            val tx = (qx - SQRT3 * qy) * 0.5f
            qy = (-SQRT3 * qx - qy) * 0.5f
            qx = tx
        }
        val clamp = if (qx < -2f * r) -2f * r else if (qx > 0f) 0f else qx
        qx -= clamp
        val len = sqrt(qx * qx + qy * qy)
        return if (qy > 0f) -len else len
    }

    private fun sdHexagon(px: Float, py: Float, r: Float): Float {
        var qx = abs(px)
        var qy = abs(py)
        val dot = 2f * min(-0.866025404f * qx + 0.5f * qy, 0f)
        qx -= dot * -0.866025404f
        qy -= dot * 0.5f
        val cl = 0.577350269f * r
        qx -= if (qx < -cl) -cl else if (qx > cl) cl else qx
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
        val rawH = (qx * STAR_BAX + qy * STAR_BAY) / STAR_BASQ
        val h = if (rawH < 0f) 0f else if (rawH > r) r else rawH
        val ex = qx - STAR_BAX * h
        val ey = qy - STAR_BAY * h
        val len = sqrt(ex * ex + ey * ey)
        val cross = qy * STAR_BAX - qx * STAR_BAY
        return if (cross >= 0f) len else -len
    }

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float {
        val shapes = (params["shapeCount"] as? Number)?.toInt() ?: 4
        return (shapes * 0.25f).coerceIn(0.2f, 1f)
    }
}
