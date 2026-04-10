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
            "Time multiplier for all motion — 0 = static", 0f, 2f, 0.05f, 0.4f),
        Parameter.SelectParam("Pulse Mode", "pulseMode", ParamGroup.FLOW_MOTION,
            "breathe: uniform pulse | wave: travelling x-wave | radial: ripple from center | swirl: angular wave",
            listOf("breathe", "wave", "radial", "swirl"), "radial"),
        Parameter.NumberParam("Pulse Amount", "pulseAmount", ParamGroup.FLOW_MOTION,
            "Intensity of size oscillation", 0f, 0.6f, 0.05f, 0.25f),
        Parameter.NumberParam("Spin Speed", "spinSpeed", ParamGroup.FLOW_MOTION,
            "Rotation speed of squares & hexagons (rad/s)", 0f, 3f, 0.1f, 0.5f),
        Parameter.NumberParam("Color Shift", "colorShift", ParamGroup.FLOW_MOTION,
            "Cycles colors through the palette over time", 0f, 2f, 0.05f, 0.2f),
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
        "animSpeed" to 0.4f,
        "pulseMode" to "radial",
        "pulseAmount" to 0.25f,
        "spinSpeed" to 0.5f,
        "colorShift" to 0.2f,
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
        private const val MAX_CACHE_ENTRIES = 4
    }

    /**
     * Immutable snapshot of one packing result. Sharing instances of this class
     * across threads is safe because the arrays are never mutated after build.
     */
    private class PackingData(
        val count: Int,
        val cx: FloatArray,
        val cy: FloatArray,
        val cr: FloatArray,
        val cd: FloatArray,
        val ca: FloatArray,
        val ck: ByteArray
    )

    // Thread-safe LRU cache: lets the live canvas thread and export thread keep
    // their own packings (they use different bitmap dimensions) without trampling
    // each other's state. Each entry is immutable after insert.
    private val cacheLock = Any()
    private val packingCache = object : LinkedHashMap<String, PackingData>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, PackingData>?): Boolean =
            size > MAX_CACHE_ENTRIES
    }

    private fun getOrBuildPacking(
        key: String, target: Int, w: Float, h: Float,
        minR: Float, maxR: Float, scaledPad: Float,
        dScale: Float, dContrast: Float, densityStyle: String,
        shapeType: String, seed: Int
    ): PackingData {
        synchronized(cacheLock) {
            packingCache[key]?.let { return it }
        }
        val built = buildPacking(
            target, w, h, minR, maxR, scaledPad,
            dScale, dContrast, densityStyle, shapeType, seed
        )
        synchronized(cacheLock) {
            packingCache[key] = built
        }
        return built
    }

    private fun buildDensityGrid(
        grid: FloatArray, noise: SimplexNoise,
        dScale: Float, dContrast: Float, densityStyle: String
    ) {
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
                grid[row + ii] = max(0f, min(1f, n)).pow(dContrast)
            }
        }
    }

    private fun sampleDensity(grid: FloatArray, x: Float, y: Float, w: Float, h: Float): Float {
        val gx = (x / w) * DENS_RES - 0.5f
        val gy = (y / h) * DENS_RES - 0.5f
        val i0 = gx.toInt().coerceIn(0, DENS_RES - 2)
        val j0 = gy.toInt().coerceIn(0, DENS_RES - 2)
        val fx = gx - i0
        val fy = gy - j0
        val r0 = j0 * DENS_RES
        val r1 = (j0 + 1) * DENS_RES
        return (1f - fy) * ((1f - fx) * grid[r0 + i0] + fx * grid[r0 + i0 + 1]) +
                fy * ((1f - fx) * grid[r1 + i0] + fx * grid[r1 + i0 + 1])
    }

    private fun buildPacking(
        target: Int, w: Float, h: Float,
        minR: Float, maxR: Float, scaledPad: Float,
        dScale: Float, dContrast: Float, densityStyle: String,
        shapeType: String, seed: Int
    ): PackingData {
        val rng = SeededRNG(seed)
        val noise = SimplexNoise(seed)

        // Local density grid — must be local for thread safety
        val densGrid = FloatArray(DENS_RES * DENS_RES)
        buildDensityGrid(densGrid, noise, dScale, dContrast, densityStyle)

        // Spatial hash grid
        val cellSize = (maxR + scaledPad) * 2f
        val gw = (ceil(w / cellSize).toInt() + 1).coerceAtLeast(1)
        val gh = (ceil(h / cellSize).toInt() + 1).coerceAtLeast(1)
        val grid = Array(gw * gh) { mutableListOf<Int>() }

        // Working arrays — sized to upper bound, trimmed at the end
        val cx = FloatArray(target)
        val cy = FloatArray(target)
        val cr = FloatArray(target)
        val cd = FloatArray(target)
        val ca = FloatArray(target)
        val ck = ByteArray(target)

        var count = 0
        var fails = 0

        while (count < target && fails < MAX_CONSECUTIVE_FAILURES) {
            val px = rng.random() * w
            val py = rng.random() * h

            // Bilinear density lookup (fast)
            val density = sampleDensity(densGrid, px, py, w, h)

            // Density-driven rejection sampling
            if (rng.random() > 0.15f + density * 0.85f) {
                fails++; continue
            }

            // Edge limit, density-modulated local max radius
            var r = min(min(px, py), min(w - px, h - py))
            val localMaxR = minR + (maxR - minR) * (0.2f + density * 0.8f)
            if (r > localMaxR) r = localMaxR
            if (r < minR) { fails++; continue }

            // Neighbor search via spatial hash
            val searchCells = ceil((maxR + scaledPad) / cellSize).toInt() + 1
            val gcx = (px / cellSize).toInt()
            val gcy = (py / cellSize).toInt()

            for (dy in -searchCells..searchCells) {
                val ny = gcy + dy
                if (ny < 0 || ny >= gh) continue
                for (dx in -searchCells..searchCells) {
                    val nx = gcx + dx
                    if (nx < 0 || nx >= gw) continue
                    val cell = grid[ny * gw + nx]
                    for (ci in cell) {
                        val ddx = px - cx[ci]
                        val ddy = py - cy[ci]
                        val dSq = ddx * ddx + ddy * ddy
                        val bound = r + cr[ci] + scaledPad
                        if (dSq < bound * bound) {
                            val dist = sqrt(dSq)
                            val allowed = dist - cr[ci] - scaledPad
                            if (allowed < r) r = allowed
                        }
                    }
                }
            }

            if (r < minR) { fails++; continue }

            // Store
            cx[count] = px
            cy[count] = py
            cr[count] = r
            cd[count] = density
            ca[count] = rng.random() * 2f * PI.toFloat()
            ck[count] = when (shapeType) {
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
            val gxi = min(gw - 1, (px / cellSize).toInt())
            val gyi = min(gh - 1, (py / cellSize).toInt())
            grid[gyi * gw + gxi].add(count)

            count++
            fails = 0
        }

        // Sort indices by radius descending — large shapes drawn first.
        // Trim arrays to actual count so the snapshot uses no extra memory.
        val idx = (0 until count).sortedByDescending { cr[it] }.toIntArray()
        val ox = FloatArray(count); val oy = FloatArray(count); val orr = FloatArray(count)
        val od = FloatArray(count); val oa = FloatArray(count); val ok = ByteArray(count)
        for (i in 0 until count) {
            val j = idx[i]
            ox[i] = cx[j]; oy[i] = cy[j]; orr[i] = cr[j]
            od[i] = cd[j]; oa[i] = ca[j]; ok[i] = ck[j]
        }

        return PackingData(count, ox, oy, orr, od, oa, ok)
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
        val animSpeed = (params["animSpeed"] as? Number)?.toFloat() ?: 0.4f
        val pulseMode = (params["pulseMode"] as? String) ?: "radial"
        val pulseAmount = (params["pulseAmount"] as? Number)?.toFloat() ?: 0.25f
        val spinSpeed = (params["spinSpeed"] as? Number)?.toFloat() ?: 0.5f
        val colorShift = (params["colorShift"] as? Number)?.toFloat() ?: 0.2f

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
        // PACKING — cached across frames keyed by deterministic params.
        // The cache returns an immutable snapshot, so the live canvas thread
        // and the export thread can render concurrently without trampling
        // each other (they normally use different bitmap dimensions).
        // ============================================================
        val packKey = "$seed|$target|$minR|$maxR|$scaledPad|$dScale|$dContrast|$densityStyle|$shapeType|$w|$h"
        val data = getOrBuildPacking(
            packKey, target, w, h, minR, maxR, scaledPad,
            dScale, dContrast, densityStyle, shapeType, seed
        )
        val count = data.count
        if (count == 0) return

        // Local aliases — these point at immutable arrays inside the snapshot
        val dataCx = data.cx; val dataCy = data.cy; val dataCr = data.cr
        val dataCd = data.cd; val dataCa = data.ca; val dataCk = data.ck

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
        val animating = animSpeed > 0f && time > 0f
        val pulseActive = animating && pulseAmount > 0.001f
        val spinActive = animating && spinSpeed > 0.001f
        val colorCycleActive = animating && colorShift > 0.001f
        val detailThreshold = 8f * sizeScale

        // Pre-compute time-derived constants
        val tp = time * animSpeed * 2f
        val tSpin = time * animSpeed * spinSpeed
        val colorOff = time * animSpeed * colorShift
        val cxc = w * 0.5f; val cyc = h * 0.5f
        // Spatial-frequency constant: scale with canvas so waves look the same at any resolution
        val sFreq = 8f / min(w, h)

        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        // In filled+outline mode the outline must be thicker to be visible against the fill
        val outlineThickness = if (fillMode == "filled+outline") max(1.8f, sizeScale * 1.8f) else max(1f, sizeScale)
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = outlineThickness
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val outlineNeedsContrast = fillMode == "filled+outline"
        val detailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = max(0.8f, 0.8f * sizeScale)
            strokeCap = Paint.Cap.ROUND
        }

        val hexPath = Path()
        val spiralPath = Path()

        for (i in 0 until count) {
            val cx = dataCx[i]; val cy = dataCy[i]; val r = dataCr[i]
            val density = dataCd[i]; val kind = dataCk[i]; val baseAngle = dataCa[i]

            // ── Pulse animation ──
            var drawR = r
            if (pulseActive) {
                val pulse = when (pulseMode) {
                    "wave" -> {
                        // Strong travelling X-wave (~3 vertical bands across canvas)
                        sin(tp * 1.5f - cx * sFreq * 3f)
                    }
                    "radial" -> {
                        // Expanding concentric rings (~4 visible rings)
                        val dx = cx - cxc; val dy = cy - cyc
                        val dist = sqrt(dx * dx + dy * dy)
                        sin(tp * 1.5f - dist * sFreq * 4f)
                    }
                    "swirl" -> {
                        // 4 rotating spiral arms
                        val ang = atan2(cy - cyc, cx - cxc)
                        val dx = cx - cxc; val dy = cy - cyc
                        val dist = sqrt(dx * dx + dy * dy)
                        sin(tp + ang * 4f - dist * sFreq * 2f)
                    }
                    else -> {
                        // breathe — chaotic per-circle independent shimmer
                        sin(tp + i * 0.7f)
                    }
                }
                drawR = r * (1f + pulse * pulseAmount)
                if (drawR < 0.5f) drawR = 0.5f
            }

            // ── Shape rotation ──
            val angle = if (spinActive) baseAngle + tSpin else baseAngle

            // ── Color (with optional palette cycling) ──
            val cr: Int; val cg: Int; val cb: Int
            when (colorMode) {
                "by-size" -> {
                    var t = min(1f, (r - minR) / radiusRange)
                    if (colorCycleActive) {
                        t += colorOff
                        t -= floor(t)
                    }
                    val ci = t * (numColors - 1)
                    val i0 = ci.toInt(); val i1 = min(numColors - 1, i0 + 1)
                    val f = ci - i0
                    cr = (colorsR[i0] + (colorsR[i1] - colorsR[i0]) * f).toInt()
                    cg = (colorsG[i0] + (colorsG[i1] - colorsG[i0]) * f).toInt()
                    cb = (colorsB[i0] + (colorsB[i1] - colorsB[i0]) * f).toInt()
                }
                "palette-density" -> {
                    var t = density
                    if (colorCycleActive) {
                        t += colorOff
                        t -= floor(t)
                    }
                    val ci = t * (numColors - 1)
                    val i0 = ci.toInt(); val i1 = min(numColors - 1, i0 + 1)
                    val f = ci - i0
                    cr = (colorsR[i0] + (colorsR[i1] - colorsR[i0]) * f).toInt()
                    cg = (colorsG[i0] + (colorsG[i1] - colorsG[i0]) * f).toInt()
                    cb = (colorsB[i0] + (colorsB[i1] - colorsB[i0]) * f).toInt()
                }
                else -> { // palette-cycle
                    val shift = if (colorCycleActive) (colorOff * numColors).toInt() else 0
                    val k = ((i + shift) % numColors + numColors) % numColors
                    cr = colorsR[k]; cg = colorsG[k]; cb = colorsB[k]
                }
            }

            val fillColor = Color.argb((fillAlpha * 255f).toInt(), cr, cg, cb)
            // In filled+outline mode the outline needs contrast against the fill,
            // otherwise it's invisible. In pure outline mode, use the original color.
            val strokeColor = if (outlineNeedsContrast) {
                val sr = if (isDark) min(255, cr + 110) else max(0, (cr * 0.25f).toInt())
                val sg = if (isDark) min(255, cg + 110) else max(0, (cg * 0.25f).toInt())
                val sb = if (isDark) min(255, cb + 110) else max(0, (cb * 0.25f).toInt())
                Color.argb(255, sr, sg, sb)
            } else {
                Color.argb((strokeAlpha * 255f).toInt(), cr, cg, cb)
            }

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

            // Inner detail (spin with the shape)
            if (innerDetail != "none" && drawR > detailThreshold) {
                detailPaint.color = detailColor

                // Apply spin to detail too — circles look lifeless without it
                val needsDetailRotate = spinActive && (kind == SHAPE_CIRCLE || innerDetail == "spokes" || innerDetail == "cross" || innerDetail == "spiral")
                if (needsDetailRotate) canvas.rotate(tSpin * 180f / PI.toFloat())

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
