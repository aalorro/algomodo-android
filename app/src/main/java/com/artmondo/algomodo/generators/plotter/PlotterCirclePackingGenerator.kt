package com.artmondo.algomodo.generators.plotter

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import com.artmondo.algomodo.audio.AudioAnalysis
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
 * Greedy fill with non-overlapping shapes grown to maximum radius, biased
 * by a noise density field. Uses a spatial hash grid for O(1) collision
 * queries and a 64×64 density grid (instead of per-candidate FBM eval).
 * Packing result is cached across animation frames.
 */
class PlotterCirclePackingGenerator : Generator {

    override val id = "plotter-circle-packing"
    override val family = "plotter"
    override val styleName = "Circle Packing"
    override val definition =
        "Fills the canvas with non-overlapping shapes grown to maximum radius, biased by a noise density field."
    override val algorithmNotes =
        "Candidate centres are sampled by rejection using a SimplexNoise density field. Density drives " +
        "both placement probability and local maximum radius. Each accepted centre grows to the largest " +
        "radius permitted before touching the canvas boundary or an existing circle. A spatial-hash grid " +
        "makes neighbourhood queries O(1). Packing is cached across animation frames so only the breathing " +
        "pulse and colour reassignment runs per frame."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema = listOf(
        Parameter.NumberParam("Circle Count", "circleCount", ParamGroup.COMPOSITION,
            "Upper bound — algorithm also stops when canvas is packed", 500f, 5000f, 100f, 2500f),
        Parameter.NumberParam("Density Scale", "densityScale", ParamGroup.COMPOSITION,
            null, 0.3f, 6f, 0.1f, 2.0f),
        Parameter.NumberParam("Density Contrast", "densityContrast", ParamGroup.TEXTURE,
            "Sharpens the density field — drives both placement clustering and circle size variation",
            0.5f, 4f, 0.25f, 0.8f),
        Parameter.SelectParam("Density Style", "densityStyle", ParamGroup.COMPOSITION,
            "Shape of the density field — fbm: smooth | ridged: sharp ridges | radial: center-focused | turbulent: creases",
            listOf("fbm", "ridged", "radial", "turbulent"), "fbm"),
        Parameter.NumberParam("Min Radius", "minRadius", ParamGroup.GEOMETRY,
            null, 1f, 20f, 1f, 4f),
        Parameter.NumberParam("Max Radius", "maxRadius", ParamGroup.GEOMETRY,
            null, 5f, 200f, 5f, 80f),
        Parameter.NumberParam("Circle Gap", "padding", ParamGroup.GEOMETRY,
            "Minimum gap between circle edges", 0f, 10f, 0.5f, 2f),
        Parameter.SelectParam("Shape", "shape", ParamGroup.GEOMETRY,
            "circles: round | squares: rotated rects | hexagons: 6-sided | mixed: random per element",
            listOf("circles", "squares", "hexagons", "mixed"), "circles"),
        Parameter.SelectParam("Fill Mode", "fillMode", ParamGroup.TEXTURE,
            null, listOf("filled", "outline", "filled+outline"), "filled"),
        Parameter.SelectParam("Inner Detail", "innerDetail", ParamGroup.TEXTURE,
            "Decorative detail drawn inside each shape — rings: concentric | spokes: radial lines | cross: X pattern | spiral: Archimedean spiral",
            listOf("none", "rings", "spokes", "cross", "spiral"), "none"),
        Parameter.SelectParam("Color Mode", "colorMode", ParamGroup.COLOR,
            null, listOf("palette-cycle", "by-size", "palette-density"), "palette-cycle"),
        Parameter.SelectParam("Background", "background", ParamGroup.COLOR,
            null, listOf("white", "cream", "dark"), "cream"),
        Parameter.NumberParam("Anim Speed", "animSpeed", ParamGroup.FLOW_MOTION,
            "Breathing/pulsing speed — 0 = static", 0f, 1f, 0.05f, 0.15f),
        Parameter.NumberParam("Reactivity", "reactivity", ParamGroup.FLOW_MOTION,
            "Audio reactivity (0 = off)", 0f, 2f, 0.1f, 0f)
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
        "animSpeed" to 0.15f,
        "reactivity" to 0f
    )

    companion object {
        private val BG = mapOf(
            "white" to Color.rgb(248, 248, 245),
            "cream" to Color.rgb(242, 234, 216),
            "dark"  to Color.rgb(14, 14, 14)
        )

        private const val SHAPE_CIRCLE: Byte = 0
        private const val SHAPE_SQUARE: Byte = 1
        private const val SHAPE_HEXAGON: Byte = 2

        private const val MAX_CONSECUTIVE_FAILURES = 600
        private const val DENS_RES = 64
    }

    // --- Cached packing across frames (per instance) ---
    @Volatile private var cachedKey: String = ""
    @Volatile private var cachedCount: Int = 0
    @Volatile private var cxArr: FloatArray = FloatArray(0)
    @Volatile private var cyArr: FloatArray = FloatArray(0)
    @Volatile private var crArr: FloatArray = FloatArray(0)
    @Volatile private var cdArr: FloatArray = FloatArray(0)
    @Volatile private var ckArr: ByteArray = ByteArray(0)
    @Volatile private var caArr: FloatArray = FloatArray(0)

    // Density grid (reused)
    @Volatile private var densGrid: FloatArray = FloatArray(DENS_RES * DENS_RES)

    private fun ensureCapacity(n: Int) {
        if (cxArr.size < n) {
            cxArr = FloatArray(n); cyArr = FloatArray(n); crArr = FloatArray(n)
            cdArr = FloatArray(n); ckArr = ByteArray(n); caArr = FloatArray(n)
        }
    }

    private fun buildDensityGrid(
        noise: SimplexNoise, dScale: Float, dContrast: Float, densityStyle: String
    ) {
        val g = densGrid
        for (jj in 0 until DENS_RES) {
            val py = (jj + 0.5f) / DENS_RES
            val row = jj * DENS_RES
            for (ii in 0 until DENS_RES) {
                val px = (ii + 0.5f) / DENS_RES
                val nx = (px - 0.5f) * dScale + 5f
                val ny = (py - 0.5f) * dScale + 5f
                val n = when (densityStyle) {
                    "ridged" -> {
                        val raw = noise.fbm(nx, ny, 4, 2f, 0.5f)
                        val ridge = 1f - abs(raw)
                        ridge * ridge
                    }
                    "turbulent" -> abs(noise.fbm(nx, ny, 4, 2f, 0.5f))
                    "radial" -> {
                        val ddx = px - 0.5f; val ddy = py - 0.5f
                        val dist = sqrt(ddx * ddx + ddy * ddy) * 2f
                        max(0f, 1f - dist + noise.fbm(nx, ny, 3, 2f, 0.5f) * 0.3f)
                    }
                    else -> noise.fbm(nx, ny, 4, 2f, 0.5f) * 0.5f + 0.5f
                }
                g[row + ii] = max(0f, min(1f, n)).pow(dContrast)
            }
        }
    }

    private fun sampleDensity(x: Float, y: Float, w: Float, h: Float): Float {
        val gx = (x / w) * DENS_RES - 0.5f
        val gy = (y / h) * DENS_RES - 0.5f
        val i0 = gx.toInt().coerceIn(0, DENS_RES - 2)
        val j0 = gy.toInt().coerceIn(0, DENS_RES - 2)
        val fx = gx - i0
        val fy = gy - j0
        val r0 = j0 * DENS_RES
        val r1 = (j0 + 1) * DENS_RES
        val g = densGrid
        return (1f - fy) * ((1f - fx) * g[r0 + i0] + fx * g[r0 + i0 + 1]) +
                fy * ((1f - fx) * g[r1 + i0] + fx * g[r1 + i0 + 1])
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

        val rx = (params["reactivity"] as? Number)?.toFloat() ?: 0f
        val audioAnalysis = params["_audioAnalysis"] as? AudioAnalysis
        val audioBass = (audioAnalysis?.getBass(time) ?: 0f) * rx

        val sizeScale = min(w, h) / 1080f
        val minR = max(1f, ((params["minRadius"] as? Number)?.toFloat() ?: 4f) * sizeScale)
        var maxR = max(minR * 3f, ((params["maxRadius"] as? Number)?.toFloat() ?: 80f) * sizeScale)
        maxR *= (1f + audioBass * 0.5f)
        val scaledPad = pad * sizeScale

        // --- Background ---
        canvas.drawColor(BG[background] ?: BG["cream"]!!)

        // ============================================================
        // PACKING — cached across frames keyed by deterministic params
        // ============================================================
        val packKey = "$seed|$target|$minR|$maxR|$scaledPad|$dScale|$dContrast|$densityStyle|$shapeType|$w|$h"

        if (cachedKey != packKey) {
            ensureCapacity(target)
            val rng = SeededRNG(seed)
            val noise = SimplexNoise(seed)

            // Pre-compute density grid (4096 fbm calls instead of thousands per loop)
            buildDensityGrid(noise, dScale, dContrast, densityStyle)

            // Spatial hash grid
            val cellSize = (maxR + scaledPad) * 2f
            val gw = (ceil(w / cellSize).toInt() + 1).coerceAtLeast(1)
            val gh = (ceil(h / cellSize).toInt() + 1).coerceAtLeast(1)
            val grid = Array(gw * gh) { mutableListOf<Int>() }

            var count = 0
            var fails = 0

            while (count < target && fails < MAX_CONSECUTIVE_FAILURES) {
                val cx = rng.random() * w
                val cy = rng.random() * h

                // Bilinear density lookup (fast)
                val density = sampleDensity(cx, cy, w, h)

                // Density-driven rejection sampling
                if (rng.random() > 0.15f + density * 0.85f) {
                    fails++; continue
                }

                // Edge limit, density-modulated local max radius
                var r = min(min(cx, cy), min(w - cx, h - cy))
                val localMaxR = minR + (maxR - minR) * (0.2f + density * 0.8f)
                if (r > localMaxR) r = localMaxR
                if (r < minR) { fails++; continue }

                // Neighbor search via spatial hash
                val searchCells = ceil((maxR + scaledPad) / cellSize).toInt() + 1
                val gcx = (cx / cellSize).toInt()
                val gcy = (cy / cellSize).toInt()

                for (dy in -searchCells..searchCells) {
                    val ny = gcy + dy
                    if (ny < 0 || ny >= gh) continue
                    for (dx in -searchCells..searchCells) {
                        val nx = gcx + dx
                        if (nx < 0 || nx >= gw) continue
                        val cell = grid[ny * gw + nx]
                        for (ci in cell) {
                            val ddx = cx - cxArr[ci]
                            val ddy = cy - cyArr[ci]
                            val dSq = ddx * ddx + ddy * ddy
                            val bound = r + crArr[ci] + scaledPad
                            if (dSq < bound * bound) {
                                val dist = sqrt(dSq)
                                val allowed = dist - crArr[ci] - scaledPad
                                if (allowed < r) r = allowed
                            }
                        }
                    }
                }

                if (r < minR) { fails++; continue }

                // Store
                cxArr[count] = cx
                cyArr[count] = cy
                crArr[count] = r
                cdArr[count] = density
                caArr[count] = rng.random() * 2f * PI.toFloat()
                ckArr[count] = when (shapeType) {
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

                // Add to spatial grid
                val gxi = min(gw - 1, (cx / cellSize).toInt())
                val gyi = min(gh - 1, (cy / cellSize).toInt())
                grid[gyi * gw + gxi].add(count)

                count++
                fails = 0
            }

            // Sort indices by radius descending — large shapes drawn first
            // We permute the SoA arrays in place.
            val idx = (0 until count).sortedByDescending { crArr[it] }.toIntArray()
            val tx = FloatArray(count); val ty = FloatArray(count); val tr = FloatArray(count)
            val td = FloatArray(count); val ta = FloatArray(count); val tk = ByteArray(count)
            for (i in 0 until count) {
                val j = idx[i]
                tx[i] = cxArr[j]; ty[i] = cyArr[j]; tr[i] = crArr[j]
                td[i] = cdArr[j]; ta[i] = caArr[j]; tk[i] = ckArr[j]
            }
            for (i in 0 until count) {
                cxArr[i] = tx[i]; cyArr[i] = ty[i]; crArr[i] = tr[i]
                cdArr[i] = td[i]; caArr[i] = ta[i]; ckArr[i] = tk[i]
            }

            cachedCount = count
            cachedKey = packKey
        }

        val count = cachedCount
        if (count == 0) return

        // ============================================================
        // DRAW — runs every frame; packing is cached
        // ============================================================
        val paletteColors = palette.colorInts()
        val numColors = paletteColors.size
        val colorsR = IntArray(numColors); val colorsG = IntArray(numColors); val colorsB = IntArray(numColors)
        for (i in 0 until numColors) {
            colorsR[i] = Color.red(paletteColors[i])
            colorsG[i] = Color.green(paletteColors[i])
            colorsB[i] = Color.blue(paletteColors[i])
        }

        val radiusRange = maxR - minR + 1e-6f

        val fillAlpha = if (isDark) 0.88f else 0.82f
        val strokeAlpha = if (isDark) 0.9f else 0.85f
        val detailAlpha = if (isDark) 0.7f else 0.6f
        val doFill = fillMode == "filled" || fillMode == "filled+outline"
        val doStroke = fillMode == "outline" || fillMode == "filled+outline"
        val breathe = animSpeed > 0f && time > 0f
        val detailThreshold = 8f * sizeScale

        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = max(1f, sizeScale)
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val detailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = max(0.8f, 0.8f * sizeScale)
            strokeCap = Paint.Cap.ROUND
        }

        val hexPath = Path()
        val spiralPath = Path()

        for (i in 0 until count) {
            val cx = cxArr[i]; val cy = cyArr[i]; val r = crArr[i]
            val density = cdArr[i]; val kind = ckArr[i]; val angle = caArr[i]

            // Breathing animation
            var drawR = r
            if (breathe) {
                val phase = cx * 0.01f + cy * 0.013f + i * 0.3f
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
                    cr = (colorsR[i0] + (colorsR[i1] - colorsR[i0]) * f).toInt()
                    cg = (colorsG[i0] + (colorsG[i1] - colorsG[i0]) * f).toInt()
                    cb = (colorsB[i0] + (colorsB[i1] - colorsB[i0]) * f).toInt()
                }
                "palette-density" -> {
                    val ci = density * (numColors - 1)
                    val i0 = ci.toInt(); val i1 = min(numColors - 1, i0 + 1)
                    val f = ci - i0
                    cr = (colorsR[i0] + (colorsR[i1] - colorsR[i0]) * f).toInt()
                    cg = (colorsG[i0] + (colorsG[i1] - colorsG[i0]) * f).toInt()
                    cb = (colorsB[i0] + (colorsB[i1] - colorsB[i0]) * f).toInt()
                }
                else -> { // palette-cycle
                    val k = i % numColors
                    cr = colorsR[k]; cg = colorsG[k]; cb = colorsB[k]
                }
            }

            val fillColor = Color.argb((fillAlpha * 255f).toInt(), cr, cg, cb)
            val strokeColor = Color.argb((strokeAlpha * 255f).toInt(), cr, cg, cb)

            // Detail color: contrast against fill — darken on light bg, lighten on dark bg
            val dr = if (isDark) min(255, cr + 100) else max(0, (cr * 0.35f).toInt())
            val dg = if (isDark) min(255, cg + 100) else max(0, (cg * 0.35f).toInt())
            val db = if (isDark) min(255, cb + 100) else max(0, (cb * 0.35f).toInt())
            val detailColor = Color.argb((detailAlpha * 255f).toInt(), dr, dg, db)

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
                detailPaint.color = detailColor

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
                        val invSteps = 1f / steps
                        spiralPath.reset()
                        for (si in 0..steps) {
                            val t = si * invSteps
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
