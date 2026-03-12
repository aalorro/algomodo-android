package com.artmondo.algomodo.generators.plotter

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import com.artmondo.algomodo.core.rng.SeededRNG
import com.artmondo.algomodo.core.rng.SimplexNoise
import com.artmondo.algomodo.data.palettes.Palette
import com.artmondo.algomodo.generators.Generator
import com.artmondo.algomodo.generators.ParamGroup
import com.artmondo.algomodo.generators.Parameter
import com.artmondo.algomodo.generators.Quality
import kotlin.math.*

/**
 * Circle packing generator.
 *
 * Fills the canvas with non-overlapping shapes grown to maximum radius,
 * biased by a noise density field. Uses a spatial hash grid for O(1)
 * collision queries.
 */
class PlotterCirclePackingGenerator : Generator {

    override val id = "plotter-circle-packing"
    override val family = "plotter"
    override val styleName = "Circle Packing"
    override val definition =
        "Fills the canvas with non-overlapping shapes grown to maximum radius, biased by a noise density field."
    override val algorithmNotes =
        "Candidate centres are sampled by rejection using a SimplexNoise density field. Each " +
        "accepted centre grows to the largest radius permitted before touching the canvas " +
        "boundary or an existing circle. A spatial-hash grid makes neighbourhood queries O(1). " +
        "Shape variants (squares, hexagons) use the same collision radius but different draw paths."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema = listOf(
        Parameter.NumberParam("Circle Count", "circleCount", ParamGroup.COMPOSITION, "Upper bound — algorithm also stops when canvas is packed", 500f, 5000f, 100f, 2500f),
        Parameter.NumberParam("Density Scale", "densityScale", ParamGroup.COMPOSITION, null, 0.3f, 6f, 0.1f, 2.0f),
        Parameter.NumberParam("Density Contrast", "densityContrast", ParamGroup.TEXTURE, "Controls color variation by noise density (does not affect circle size)", 0.5f, 4f, 0.25f, 0.8f),
        Parameter.SelectParam("Density Style", "densityStyle", ParamGroup.COMPOSITION, "Shape of the density field — fbm: smooth | ridged: sharp ridges | radial: center-focused | turbulent: creases", listOf("fbm", "ridged", "radial", "turbulent"), "fbm"),
        Parameter.NumberParam("Min Radius", "minRadius", ParamGroup.GEOMETRY, null, 1f, 20f, 1f, 4f),
        Parameter.NumberParam("Max Radius", "maxRadius", ParamGroup.GEOMETRY, null, 5f, 200f, 5f, 80f),
        Parameter.NumberParam("Circle Gap", "padding", ParamGroup.GEOMETRY, "Minimum gap between circle edges", 0f, 10f, 0.5f, 2f),
        Parameter.SelectParam("Shape", "shape", ParamGroup.GEOMETRY, "circles: round | squares: rotated rects | hexagons: 6-sided | mixed: random per element", listOf("circles", "squares", "hexagons", "mixed"), "circles"),
        Parameter.SelectParam("Fill Mode", "fillMode", ParamGroup.TEXTURE, null, listOf("filled", "outline", "filled+outline"), "filled"),
        Parameter.SelectParam("Inner Detail", "innerDetail", ParamGroup.TEXTURE, "Decorative detail drawn inside each shape — rings: concentric | spokes: radial lines | cross: X pattern | spiral: Archimedean spiral", listOf("none", "rings", "spokes", "cross", "spiral"), "none"),
        Parameter.SelectParam("Color Mode", "colorMode", ParamGroup.COLOR, null, listOf("palette-cycle", "by-size", "palette-density"), "palette-cycle"),
        Parameter.SelectParam("Background", "background", ParamGroup.COLOR, null, listOf("white", "cream", "dark"), "cream"),
        Parameter.NumberParam("Anim Speed", "animSpeed", ParamGroup.FLOW_MOTION, "Breathing/pulsing speed — 0 = static", 0f, 1f, 0.05f, 0.15f)
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "circleCount" to 2500f,
        "densityScale" to 2.0f,
        "densityContrast" to 0.8f,
        "densityStyle" to "fbm",
        "minRadius" to 4f,
        "maxRadius" to 80f,
        "padding" to 2f,
        "shape" to "circles",
        "fillMode" to "filled",
        "innerDetail" to "none",
        "colorMode" to "palette-cycle",
        "background" to "cream",
        "animSpeed" to 0.15f
    )

    companion object {
        private val BG = mapOf(
            "white" to Color.rgb(248, 248, 245),
            "cream" to Color.rgb(242, 234, 216),
            "dark"  to Color.rgb(14, 14, 14)
        )

        private const val SHAPE_CIRCLE = 0
        private const val SHAPE_SQUARE = 1
        private const val SHAPE_HEXAGON = 2

        private const val MAX_CONSECUTIVE_FAILURES = 600
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
        val w = bitmap.width.toFloat()
        val h = bitmap.height.toFloat()

        val rng = SeededRNG(seed)
        val noise = SimplexNoise(seed)

        val target = ((params["circleCount"] as? Number)?.toInt() ?: 2500).coerceAtLeast(1)
        val dScale = (params["densityScale"] as? Number)?.toFloat() ?: 2.0f
        val dContrast = (params["densityContrast"] as? Number)?.toFloat() ?: 0.8f
        val densityStyle = (params["densityStyle"] as? String) ?: "fbm"
        val pad = (params["padding"] as? Number)?.toFloat() ?: 2f
        val shapeType = (params["shape"] as? String) ?: "circles"
        val fillMode = (params["fillMode"] as? String) ?: "filled"
        val innerDetail = (params["innerDetail"] as? String) ?: "none"
        val colorMode = (params["colorMode"] as? String) ?: "palette-cycle"
        val background = (params["background"] as? String) ?: "cream"
        val isDark = background == "dark"
        val animSpeed = (params["animSpeed"] as? Number)?.toFloat() ?: 0.15f

        val sizeScale = min(w, h) / 1080f
        val minR = max(1f, ((params["minRadius"] as? Number)?.toFloat() ?: 4f) * sizeScale)
        val maxR = max(minR * 3f, ((params["maxRadius"] as? Number)?.toFloat() ?: 80f) * sizeScale)
        val scaledPad = pad * sizeScale

        // Background
        canvas.drawColor(BG[background] ?: BG["cream"]!!)

        // --- Spatial hash grid ---
        val cellSize = (maxR + scaledPad) * 2f
        val gw = (ceil(w / cellSize).toInt() + 1).coerceAtLeast(1)
        val gh = (ceil(h / cellSize).toInt() + 1).coerceAtLeast(1)
        val grid = Array(gw * gh) { mutableListOf<Int>() }

        // Packed circle data (struct of arrays for cache-friendliness)
        val cxArr = FloatArray(target)
        val cyArr = FloatArray(target)
        val crArr = FloatArray(target)
        val cdArr = FloatArray(target)  // density
        val ckArr = IntArray(target)    // shape kind
        val caArr = FloatArray(target)  // angle
        var count = 0

        // Density function
        fun density(x: Float, y: Float): Float {
            val nx = (x / w - 0.5f) * dScale + 5f
            val ny = (y / h - 0.5f) * dScale + 5f
            val n = when (densityStyle) {
                "ridged" -> {
                    val raw = noise.fbm(nx, ny, 4, 2f, 0.5f)
                    val ridge = 1f - abs(raw)
                    ridge * ridge
                }
                "turbulent" -> abs(noise.fbm(nx, ny, 4, 2f, 0.5f))
                "radial" -> {
                    val dx = x / w - 0.5f; val dy = y / h - 0.5f
                    val dist = sqrt(dx * dx + dy * dy) * 2f
                    val noiseVal = noise.fbm(nx, ny, 3, 2f, 0.5f) * 0.3f
                    max(0f, 1f - dist + noiseVal)
                }
                else -> noise.fbm(nx, ny, 4, 2f, 0.5f) * 0.5f + 0.5f
            }
            return max(0f, min(1f, n)).pow(dContrast)
        }

        // Find max radius at candidate position using spatial hash
        fun maxRadiusAt(cx: Float, cy: Float): Float {
            var r = min(min(cx, cy), min(w - cx, h - cy)).coerceAtMost(maxR)
            if (r < minR) return -1f

            val searchCells = ceil((maxR + scaledPad) / cellSize).toInt() + 1
            val gx = (cx / cellSize).toInt()
            val gy = (cy / cellSize).toInt()

            for (dy in -searchCells..searchCells) {
                for (dx in -searchCells..searchCells) {
                    val nx = gx + dx; val ny = gy + dy
                    if (nx < 0 || nx >= gw || ny < 0 || ny >= gh) continue
                    val cell = grid[ny * gw + nx]
                    for (ci in cell) {
                        val ddx = cx - cxArr[ci]; val ddy = cy - cyArr[ci]
                        val dist = sqrt(ddx * ddx + ddy * ddy)
                        val maxAllowed = dist - crArr[ci] - scaledPad
                        if (maxAllowed < r) r = maxAllowed
                    }
                }
            }
            return r
        }

        // --- Greedy fill ---
        var consecutiveFailures = 0
        while (count < target && consecutiveFailures < MAX_CONSECUTIVE_FAILURES) {
            val cx = rng.random() * w
            val cy = rng.random() * h

            val r = maxRadiusAt(cx, cy)
            if (r < minR) {
                consecutiveFailures++
                continue
            }

            val d = density(cx, cy)
            val kind = when (shapeType) {
                "squares" -> SHAPE_SQUARE
                "hexagons" -> SHAPE_HEXAGON
                "mixed" -> {
                    val pick = rng.random()
                    when {
                        pick < 0.4f -> SHAPE_CIRCLE
                        pick < 0.7f -> SHAPE_SQUARE
                        else -> SHAPE_HEXAGON
                    }
                }
                else -> SHAPE_CIRCLE
            }
            val angle = rng.random() * 2f * PI.toFloat()

            val i = count
            cxArr[i] = cx; cyArr[i] = cy; crArr[i] = r
            cdArr[i] = d; ckArr[i] = kind; caArr[i] = angle

            // Add to spatial grid
            val gridX = min(gw - 1, (cx / cellSize).toInt())
            val gridY = min(gh - 1, (cy / cellSize).toInt())
            grid[gridY * gw + gridX].add(i)

            count++
            consecutiveFailures = 0
        }

        // Sort by radius descending (large shapes drawn first)
        val indices = (0 until count).sortedByDescending { crArr[it] }.toIntArray()

        // --- Draw ---
        val paletteColors = palette.colorInts()
        val colorsRgb = Array(paletteColors.size) {
            intArrayOf(Color.red(paletteColors[it]), Color.green(paletteColors[it]), Color.blue(paletteColors[it]))
        }
        val numColors = colorsRgb.size
        val radiusRange = maxR - minR + 1e-6f

        val fillAlpha = if (isDark) 0.88f else 0.82f
        val strokeAlpha = if (isDark) 0.9f else 0.85f
        val detailAlpha = if (isDark) 0.45f else 0.35f
        val doFill = fillMode == "filled" || fillMode == "filled+outline"
        val doStroke = fillMode == "outline" || fillMode == "filled+outline"
        val breathe = animSpeed > 0f && time > 0f
        val detailThreshold = 8f * sizeScale

        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = sizeScale
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val detailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 0.6f * sizeScale
            strokeCap = Paint.Cap.ROUND
        }

        val hexPath = Path()

        for (order in 0 until count) {
            val i = indices[order]
            val cx = cxArr[i]; val cy = cyArr[i]; val r = crArr[i]
            val density = cdArr[i]; val kind = ckArr[i]; val angle = caArr[i]

            // Breathing animation
            var drawR = r
            if (breathe) {
                val phase = cx * 0.01f + cy * 0.013f + order * 0.3f
                val pulse = sin(time * animSpeed * 2f + phase) * 0.12f
                drawR = r * (1f + pulse)
            }

            // Color
            val cr: Int; val cg: Int; val cb: Int
            when (colorMode) {
                "by-size" -> {
                    val t = min(1f, (r - minR) / radiusRange)
                    val ci = t * (numColors - 1)
                    val i0 = ci.toInt(); val i1 = min(numColors - 1, i0 + 1)
                    val f = ci - i0
                    cr = (colorsRgb[i0][0] + (colorsRgb[i1][0] - colorsRgb[i0][0]) * f).toInt()
                    cg = (colorsRgb[i0][1] + (colorsRgb[i1][1] - colorsRgb[i0][1]) * f).toInt()
                    cb = (colorsRgb[i0][2] + (colorsRgb[i1][2] - colorsRgb[i0][2]) * f).toInt()
                }
                "palette-density" -> {
                    val ci = density * (numColors - 1)
                    val i0 = ci.toInt(); val i1 = min(numColors - 1, i0 + 1)
                    val f = ci - i0
                    cr = (colorsRgb[i0][0] + (colorsRgb[i1][0] - colorsRgb[i0][0]) * f).toInt()
                    cg = (colorsRgb[i0][1] + (colorsRgb[i1][1] - colorsRgb[i0][1]) * f).toInt()
                    cb = (colorsRgb[i0][2] + (colorsRgb[i1][2] - colorsRgb[i0][2]) * f).toInt()
                }
                else -> { // palette-cycle
                    val c = colorsRgb[order % numColors]
                    cr = c[0]; cg = c[1]; cb = c[2]
                }
            }

            val fillColor = Color.argb((fillAlpha * 255).toInt(), cr, cg, cb)
            val strokeColor = Color.argb((strokeAlpha * 255).toInt(), cr, cg, cb)

            canvas.save()
            canvas.translate(cx, cy)

            when (kind) {
                SHAPE_SQUARE -> {
                    canvas.rotate(angle * 180f / PI.toFloat())
                    val half = drawR * 0.85f
                    if (doFill) {
                        fillPaint.color = fillColor
                        canvas.drawRect(-half, -half, half, half, fillPaint)
                    }
                    if (doStroke) {
                        strokePaint.color = strokeColor
                        canvas.drawRect(-half, -half, half, half, strokePaint)
                    }
                }
                SHAPE_HEXAGON -> {
                    hexPath.reset()
                    for (v in 0 until 6) {
                        val a = angle + v * PI.toFloat() / 3f
                        val hx = cos(a) * drawR
                        val hy = sin(a) * drawR
                        if (v == 0) hexPath.moveTo(hx, hy) else hexPath.lineTo(hx, hy)
                    }
                    hexPath.close()
                    if (doFill) {
                        fillPaint.color = fillColor
                        canvas.drawPath(hexPath, fillPaint)
                    }
                    if (doStroke) {
                        strokePaint.color = strokeColor
                        canvas.drawPath(hexPath, strokePaint)
                    }
                }
                else -> { // circle
                    if (doFill) {
                        fillPaint.color = fillColor
                        canvas.drawCircle(0f, 0f, drawR, fillPaint)
                    }
                    if (doStroke) {
                        strokePaint.color = strokeColor
                        canvas.drawCircle(0f, 0f, drawR, strokePaint)
                    }
                }
            }

            // Inner detail
            if (innerDetail != "none" && drawR > detailThreshold) {
                detailPaint.color = Color.argb((detailAlpha * 255).toInt(), cr, cg, cb)

                when (innerDetail) {
                    "rings" -> {
                        val ringStep = max(3f, drawR * 0.25f)
                        var ri = ringStep
                        while (ri < drawR - 1f) {
                            canvas.drawCircle(0f, 0f, ri, detailPaint)
                            ri += ringStep
                        }
                    }
                    "spokes" -> {
                        val spokeCount = min(12, max(4, (drawR / 6f).toInt()))
                        for (si in 0 until spokeCount) {
                            val a = si.toFloat() / spokeCount * 2f * PI.toFloat()
                            canvas.drawLine(0f, 0f, cos(a) * (drawR - 1f), sin(a) * (drawR - 1f), detailPaint)
                        }
                    }
                    "cross" -> {
                        val cr2 = drawR * 0.75f
                        canvas.drawLine(-cr2, -cr2, cr2, cr2, detailPaint)
                        canvas.drawLine(cr2, -cr2, -cr2, cr2, detailPaint)
                    }
                    "spiral" -> {
                        val turns = max(2f, drawR / 8f)
                        val steps = (turns * 20f).toInt()
                        val spiralPath = Path()
                        for (si in 0..steps) {
                            val t = si.toFloat() / steps
                            val a = t * turns * 2f * PI.toFloat()
                            val sr = t * (drawR - 1f)
                            val sx = cos(a) * sr
                            val sy = sin(a) * sr
                            if (si == 0) spiralPath.moveTo(sx, sy) else spiralPath.lineTo(sx, sy)
                        }
                        canvas.drawPath(spiralPath, detailPaint)
                    }
                }
            }

            canvas.restore()
        }
    }

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float {
        val maxCircles = (params["circleCount"] as? Number)?.toInt() ?: 2500
        return (maxCircles / 5000f).coerceIn(0.2f, 1f)
    }
}
