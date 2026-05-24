package com.artmondo.algomodo.generators.physics

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import com.artmondo.algomodo.audio.AudioAnalysis
import com.artmondo.algomodo.core.rng.SeededRNG
import com.artmondo.algomodo.data.palettes.Palette
import com.artmondo.algomodo.generators.Generator
import com.artmondo.algomodo.generators.ParamGroup
import com.artmondo.algomodo.generators.Parameter
import com.artmondo.algomodo.generators.Quality
import kotlin.math.*

class BrownianMotionGenerator : Generator {

    override val id = "physics-brownian-motion"
    override val family = "physics"
    override val styleName = "Brownian Motion"
    override val definition =
        "Particles performing random walks — classic Brownian motion, Lévy flights, biased drifts, and spirals — rendered as fading trails, dots, networks, or heatmaps"
    override val algorithmNotes =
        "N particles each take random displacements per step. Classic: uniform angle + fixed step. " +
        "Lévy flight: step size drawn from power-law (u^{-1/α}, α=1.5) giving occasional large jumps. " +
        "Biased: adds constant drift vector each step. Spiral: accumulating angular momentum with noise. " +
        "Trail history stored as per-particle circular buffers in flat Float64Arrays. " +
        "Heatmap uses a coarse density grid with Uint32Array ABGR pixel writes and palette LUT. " +
        "Network mode uses spatial grid for O(n·k) neighbor lookup instead of O(n²). " +
        "Trail rendering batches into alpha buckets to minimize canvas state changes."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema = listOf(
        Parameter.SelectParam(
            name = "Walk Type",
            key = "walkType",
            group = ParamGroup.COMPOSITION,
            help = "classic: uniform random | levy: rare large jumps | biased: constant drift | spiral: angular momentum",
            options = listOf("classic", "levy", "biased", "spiral"),
            default = "classic"
        ),
        Parameter.SelectParam(
            name = "Render Style",
            key = "style",
            group = ParamGroup.COMPOSITION,
            help = "trails: fading paths | dots: current positions | network: nearby connections | heatmap: density grid",
            options = listOf("trails", "dots", "network", "heatmap"),
            default = "trails"
        ),
        Parameter.NumberParam(
            name = "Particles",
            key = "particleCount",
            group = ParamGroup.GEOMETRY,
            help = null,
            min = 50f, max = 500f, step = 10f, default = 150f
        ),
        Parameter.NumberParam(
            name = "Step Size",
            key = "stepSize",
            group = ParamGroup.FLOW_MOTION,
            help = null,
            min = 0.5f, max = 8f, step = 0.5f, default = 3f
        ),
        Parameter.NumberParam(
            name = "Trail Length",
            key = "trailLength",
            group = ParamGroup.GEOMETRY,
            help = null,
            min = 50f, max = 800f, step = 50f, default = 300f
        ),
        Parameter.NumberParam(
            name = "Drift",
            key = "drift",
            group = ParamGroup.FLOW_MOTION,
            help = "Constant drift force applied each step",
            min = 0f, max = 2f, step = 0.1f, default = 0f
        ),
        Parameter.NumberParam(
            name = "Drift Angle",
            key = "driftAngle",
            group = ParamGroup.FLOW_MOTION,
            help = "Direction of drift force in degrees (0=right, 90=down, 270=up)",
            min = 0f, max = 360f, step = 10f, default = 270f
        ),
        Parameter.SelectParam(
            name = "Color Mode",
            key = "colorMode",
            group = ParamGroup.COLOR,
            help = "age: trail age gradient | speed: velocity | index: particle palette cycle | depth: y-position",
            options = listOf("age", "speed", "index", "depth"),
            default = "age"
        ),
        Parameter.NumberParam(
            name = "Line Width",
            key = "lineWidth",
            group = ParamGroup.GEOMETRY,
            help = null,
            min = 0.5f, max = 4f, step = 0.5f, default = 1.5f
        ),
        Parameter.NumberParam(
            name = "Steps / Frame",
            key = "stepsPerFrame",
            group = ParamGroup.FLOW_MOTION,
            help = null,
            min = 1f, max = 8f, step = 1f, default = 3f
        ),
        Parameter.NumberParam(
            name = "Audio Reactivity",
            key = "reactivity",
            group = ParamGroup.FLOW_MOTION,
            help = null,
            min = 0f, max = 2f, step = 0.1f, default = 0f
        )
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "walkType" to "classic",
        "style" to "trails",
        "particleCount" to 150f,
        "stepSize" to 3f,
        "trailLength" to 300f,
        "drift" to 0f,
        "driftAngle" to 270f,
        "colorMode" to "age",
        "lineWidth" to 1.5f,
        "stepsPerFrame" to 3f,
        "reactivity" to 0f
    )

    // ── Param helpers ────────────────────────────────────────────────
    private fun pf(p: Map<String, Any>, k: String, default: Float): Float = (p[k] as? Number)?.toFloat() ?: default
    private fun pi(p: Map<String, Any>, k: String, default: Int): Int = (p[k] as? Number)?.toInt() ?: default
    private fun ps(p: Map<String, Any>, k: String, default: String): String = (p[k] as? String) ?: default

    // ── State ────────────────────────────────────────────────────────
    private class BrownianState(
        var key: String,
        val n: Int,
        val trailLen: Int,
        val x: DoubleArray,
        val y: DoubleArray,
        val trailX: DoubleArray,
        val trailY: DoubleArray,
        val trailHead: IntArray,
        val trailCount: IntArray,
        val angleAccum: DoubleArray,
        var step: Int,
        val rng: SeededRNG,
        var heatGrid: FloatArray?,
        var heatW: Int,
        var heatH: Int,
        var paletteLUT: IntArray?,
        var paletteLUTKey: String
    )

    companion object {
        @Volatile
        private var sharedState: BrownianState? = null
    }

    private fun makeState(n: Int, trailLen: Int, seed: Int): BrownianState {
        return BrownianState(
            key = "",
            n = n,
            trailLen = trailLen,
            x = DoubleArray(n),
            y = DoubleArray(n),
            trailX = DoubleArray(n * trailLen),
            trailY = DoubleArray(n * trailLen),
            trailHead = IntArray(n),
            trailCount = IntArray(n),
            angleAccum = DoubleArray(n),
            step = 0,
            rng = SeededRNG(seed),
            heatGrid = null,
            heatW = 0,
            heatH = 0,
            paletteLUT = null,
            paletteLUTKey = ""
        )
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
        val scale = w / 600f
        val qMul = when (quality) {
            Quality.DRAFT -> 0.5f
            Quality.ULTRA -> 1.5f
            else -> 1f
        }

        val walkType = ps(params, "walkType", "classic")
        val style = ps(params, "style", "trails")
        val particleCount = max(10, (pf(params, "particleCount", 150f) * qMul).roundToInt())
        var stepSize = pf(params, "stepSize", 3f)
        val trailLength = max(10, pi(params, "trailLength", 300))
        var drift = pf(params, "drift", 0f)
        val driftAngle = pf(params, "driftAngle", 270f)
        val colorMode = ps(params, "colorMode", "age")
        val lineWidth = pf(params, "lineWidth", 1.5f)
        val spf = max(1, pi(params, "stepsPerFrame", 3))

        // Audio reactivity
        val rx = pf(params, "reactivity", 0f)
        val audio = params["_audioAnalysis"] as? AudioAnalysis
        val audioBass = (audio?.getBass(time) ?: 0f) * rx
        val audioMid = (audio?.getMid(time) ?: 0f) * rx
        val audioHigh = (audio?.getHigh(time) ?: 0f) * rx
        val audioEnergy = (audioBass + audioMid + audioHigh) / 3f
        stepSize += audioBass * 3f
        drift += audioMid * 0.8f
        val extraSteps = min(6, (audioEnergy * 4f).toInt())

        // ── State cache ──────────────────────────────────────────────
        val key = "$seed|$particleCount|$trailLength|$w|$h"
        var st: BrownianState
        synchronized(Companion) {
            val existing = sharedState
            if (existing == null || existing.key != key) {
                val newSt = makeState(particleCount, trailLength, seed)
                for (i in 0 until particleCount) {
                    newSt.x[i] = newSt.rng.randomDouble() * w
                    newSt.y[i] = newSt.rng.randomDouble() * h
                    newSt.angleAccum[i] = newSt.rng.randomDouble() * Math.PI * 2.0
                    val base = i * trailLength
                    newSt.trailX[base] = newSt.x[i]
                    newSt.trailY[base] = newSt.y[i]
                    newSt.trailHead[i] = 1
                    newSt.trailCount[i] = 1
                }
                newSt.key = key
                sharedState = newSt
                st = newSt
            } else {
                st = existing
            }
        }

        // ── Warmup ───────────────────────────────────────────────────
        if (time <= 0f && st.step == 0) {
            synchronized(Companion) {
                for (f in 0 until 30) {
                    stepParticles(st, w, h, walkType, stepSize, drift, driftAngle, spf)
                }
            }
        }

        // ── Animation steps ──────────────────────────────────────────
        if (time > 0f) {
            synchronized(Companion) {
                stepParticles(st, w, h, walkType, stepSize, drift, driftAngle, spf + extraSteps)
            }
        }

        // ── Pre-compute palette colors ───────────────────────────────
        val colorInts = palette.colorInts()
        val nColors = colorInts.size
        val colorsR = IntArray(nColors) { Color.red(colorInts[it]) }
        val colorsG = IntArray(nColors) { Color.green(colorInts[it]) }
        val colorsB = IntArray(nColors) { Color.blue(colorInts[it]) }

        // ── Render ───────────────────────────────────────────────────
        if (style == "heatmap") {
            renderHeatmap(canvas, st, w, h, colorsR, colorsG, colorsB, nColors, palette)
        } else {
            canvas.drawColor(Color.rgb(10, 10, 10))
            when (style) {
                "trails" -> renderTrails(canvas, st, w, h, colorsR, colorsG, colorsB, nColors, colorMode, lineWidth * scale)
                "dots" -> renderDots(canvas, st, w, h, colorsR, colorsG, colorsB, nColors, colorMode, scale)
                "network" -> renderNetwork(canvas, st, w, h, colorsR, colorsG, colorsB, nColors, colorMode, lineWidth * scale, scale)
            }
        }
    }

    // ── Simulation step ──────────────────────────────────────────────
    private fun stepParticles(
        st: BrownianState,
        w: Int, h: Int,
        walkType: String,
        stepSize: Float,
        drift: Float,
        driftAngle: Float,
        stepsPerFrame: Int
    ) {
        val n = st.n
        val x = st.x
        val y = st.y
        val trailX = st.trailX
        val trailY = st.trailY
        val trailHead = st.trailHead
        val trailCount = st.trailCount
        val trailLen = st.trailLen
        val angleAccum = st.angleAccum
        val rng = st.rng

        val driftRad = driftAngle.toDouble() * Math.PI / 180.0
        val driftDx = drift.toDouble() * cos(driftRad)
        val driftDy = drift.toDouble() * sin(driftRad)
        val scale = w.toDouble() / 600.0

        for (s in 0 until stepsPerFrame) {
            for (i in 0 until n) {
                var dx = 0.0
                var dy = 0.0
                val ss = stepSize.toDouble() * scale

                when (walkType) {
                    "classic" -> {
                        val angle = rng.randomDouble() * Math.PI * 2.0
                        dx = cos(angle) * ss
                        dy = sin(angle) * ss
                    }
                    "levy" -> {
                        val angle = rng.randomDouble() * Math.PI * 2.0
                        val u = max(0.01, rng.randomDouble())
                        val levyStep = ss * u.pow(-1.0 / 1.5) * 0.3
                        val capped = min(levyStep, w * 0.15)
                        dx = cos(angle) * capped
                        dy = sin(angle) * capped
                    }
                    "biased" -> {
                        val angle = rng.randomDouble() * Math.PI * 2.0
                        dx = cos(angle) * ss + driftDx * scale
                        dy = sin(angle) * ss + driftDy * scale
                    }
                    "spiral" -> {
                        val turnRate = 0.15 + rng.randomDouble() * 0.1
                        angleAccum[i] += turnRate + (rng.randomDouble() - 0.5) * 0.4
                        dx = cos(angleAccum[i]) * ss
                        dy = sin(angleAccum[i]) * ss
                    }
                }

                if (walkType != "biased" && drift > 0f) {
                    dx += driftDx * scale * 0.3
                    dy += driftDy * scale * 0.3
                }

                x[i] += dx
                y[i] += dy

                // Toroidal wrapping
                if (x[i] < 0) x[i] += w
                else if (x[i] >= w) x[i] -= w
                if (y[i] < 0) y[i] += h
                else if (y[i] >= h) y[i] -= h

                val base = i * trailLen
                val head = trailHead[i]
                trailX[base + head] = x[i]
                trailY[base + head] = y[i]
                trailHead[i] = (head + 1) % trailLen
                if (trailCount[i] < trailLen) trailCount[i]++
            }
            st.step++
        }
    }

    // ── Build palette LUT (256 ARGB entries) ─────────────────────────
    private fun buildPaletteLUT(colorsR: IntArray, colorsG: IntArray, colorsB: IntArray, nColors: Int): IntArray {
        val lut = IntArray(256)
        for (i in 0 until 256) {
            val t = i / 255f
            val s = max(0f, min(1f, t)) * (nColors - 1)
            val i0 = s.toInt()
            val i1 = min(nColors - 1, i0 + 1)
            val f = s - i0
            val r = (colorsR[i0] + (colorsR[i1] - colorsR[i0]) * f).toInt()
            val g = (colorsG[i0] + (colorsG[i1] - colorsG[i0]) * f).toInt()
            val b = (colorsB[i0] + (colorsB[i1] - colorsB[i0]) * f).toInt()
            val a = max(20, min(255, (t * 230f + 25f).toInt()))
            lut[i] = Color.argb(a, r, g, b)
        }
        return lut
    }

    // ── Trail rendering (batched alpha buckets) ──────────────────────
    private fun renderTrails(
        canvas: Canvas,
        st: BrownianState,
        w: Int, h: Int,
        colorsR: IntArray, colorsG: IntArray, colorsB: IntArray,
        nColors: Int,
        colorMode: String,
        lw: Float
    ) {
        val n = st.n
        val y = st.y
        val trailX = st.trailX
        val trailY = st.trailY
        val trailHead = st.trailHead
        val trailCount = st.trailCount
        val trailLen = st.trailLen
        val BUCKETS = 6
        val alphaMin = 0.05f
        val alphaMax = 0.7f

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = lw
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        val wrapThresh = (w * 0.25).let { it * it }

        for (b in 0 until BUCKETS) {
            val bucketAlpha = alphaMin + (alphaMax - alphaMin) * (b / (BUCKETS - 1f))
            val alphaInt = (bucketAlpha * 255f).toInt().coerceIn(0, 255)

            for (ci in 0 until nColors) {
                paint.color = Color.argb(alphaInt, colorsR[ci], colorsG[ci], colorsB[ci])
                val path = Path()
                var hasContent = false

                for (i in 0 until n) {
                    val count = trailCount[i]
                    if (count < 2) continue

                    val particleCI = when (colorMode) {
                        "index" -> i % nColors
                        "depth" -> min(nColors - 1, ((y[i] / h) * nColors).toInt())
                        else -> i % nColors
                    }
                    if (particleCI != ci) continue

                    val base = i * trailLen
                    val head = trailHead[i]

                    val segStart = ((b.toFloat() / BUCKETS) * count).toInt()
                    val segEnd = (((b + 1).toFloat() / BUCKETS) * count).toInt()
                    if (segEnd <= segStart) continue

                    val startIdx = ((head - count + segStart) % trailLen + trailLen) % trailLen
                    var prevX = trailX[base + startIdx]
                    var prevY = trailY[base + startIdx]
                    path.moveTo(prevX.toFloat(), prevY.toFloat())
                    hasContent = true

                    for (j in segStart + 1 until segEnd) {
                        val idx = ((head - count + j) % trailLen + trailLen) % trailLen
                        val tx = trailX[base + idx]
                        val ty = trailY[base + idx]
                        val dx = tx - prevX
                        val dy = ty - prevY
                        if (dx * dx + dy * dy > wrapThresh) {
                            path.moveTo(tx.toFloat(), ty.toFloat())
                        } else {
                            path.lineTo(tx.toFloat(), ty.toFloat())
                        }
                        prevX = tx
                        prevY = ty
                    }
                }
                if (hasContent) canvas.drawPath(path, paint)
            }
        }
    }

    // ── Dots rendering ───────────────────────────────────────────────
    private fun renderDots(
        canvas: Canvas,
        st: BrownianState,
        w: Int, h: Int,
        colorsR: IntArray, colorsG: IntArray, colorsB: IntArray,
        nColors: Int,
        colorMode: String,
        scale: Float
    ) {
        val n = st.n
        val x = st.x
        val y = st.y
        val trailX = st.trailX
        val trailY = st.trailY
        val trailHead = st.trailHead
        val trailCount = st.trailCount
        val trailLen = st.trailLen

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
        val dotsAlpha = (0.85f * 255f).toInt().coerceIn(0, 255)

        for (ci in 0 until nColors) {
            paint.color = Color.argb(dotsAlpha, colorsR[ci], colorsG[ci], colorsB[ci])

            for (i in 0 until n) {
                val pci: Int = when (colorMode) {
                    "index" -> i % nColors
                    "depth" -> min(nColors - 1, ((y[i] / h) * nColors).toInt())
                    "speed" -> {
                        val count = trailCount[i]
                        var speed = 1.0
                        if (count >= 2) {
                            val head = trailHead[i]
                            val base = i * trailLen
                            val i0 = ((head - 1) % trailLen + trailLen) % trailLen
                            val i1 = ((head - 2) % trailLen + trailLen) % trailLen
                            val dx = trailX[base + i0] - trailX[base + i1]
                            val dy = trailY[base + i0] - trailY[base + i1]
                            speed = sqrt(dx * dx + dy * dy)
                        }
                        min(nColors - 1, (min(1.0, speed / (12.0 * scale)) * nColors).toInt())
                    }
                    else -> min(nColors - 1, ((trailCount[i].toDouble() / trailLen) * nColors).toInt())
                }
                if (pci != ci) continue

                var speed = 1.0
                val count = trailCount[i]
                if (count >= 2) {
                    val head = trailHead[i]
                    val base = i * trailLen
                    val idx0 = ((head - 1) % trailLen + trailLen) % trailLen
                    val idx1 = ((head - 2) % trailLen + trailLen) % trailLen
                    val dx = trailX[base + idx0] - trailX[base + idx1]
                    val dy = trailY[base + idx0] - trailY[base + idx1]
                    speed = sqrt(dx * dx + dy * dy)
                }
                val radius = ((1.5 + min(3.0, speed * 0.4)) * scale).toFloat()
                canvas.drawCircle(x[i].toFloat(), y[i].toFloat(), radius, paint)
            }
        }
    }

    // ── Network rendering (spatial grid) ─────────────────────────────
    private fun renderNetwork(
        canvas: Canvas,
        st: BrownianState,
        w: Int, h: Int,
        colorsR: IntArray, colorsG: IntArray, colorsB: IntArray,
        nColors: Int,
        colorMode: String,
        lw: Float,
        scale: Float
    ) {
        val n = st.n
        val x = st.x
        val y = st.y
        val threshold = 60.0 * scale
        val thresholdSq = threshold * threshold
        val cellSize = threshold

        val cols = max(1, ceil(w / cellSize).toInt())
        val rows = max(1, ceil(h / cellSize).toInt())
        val maxPerCell = 16
        val totalCells = cols * rows
        val grid = IntArray(totalCells * maxPerCell) { -1 }
        val counts = IntArray(totalCells)

        for (i in 0 until n) {
            val col = min(cols - 1, max(0, (x[i] / cellSize).toInt()))
            val row = min(rows - 1, max(0, (y[i] / cellSize).toInt()))
            val cellIdx = row * cols + col
            val c = counts[cellIdx]
            if (c < maxPerCell) {
                grid[cellIdx * maxPerCell + c] = i
                counts[cellIdx] = c + 1
            }
        }

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = lw * 0.6f
        }
        val lineAlpha = (0.25f * 255f).toInt().coerceIn(0, 255)

        for (ci in 0 until nColors) {
            paint.color = Color.argb(lineAlpha, colorsR[ci], colorsG[ci], colorsB[ci])
            val path = Path()
            var hasContent = false

            for (i in 0 until n) {
                if (i % nColors != ci) continue
                val ix = x[i]
                val iy = y[i]
                val col = min(cols - 1, max(0, (ix / cellSize).toInt()))
                val row = min(rows - 1, max(0, (iy / cellSize).toInt()))

                for (dr in -1..1) {
                    val nr = row + dr
                    if (nr < 0 || nr >= rows) continue
                    for (dc in -1..1) {
                        val nc = col + dc
                        if (nc < 0 || nc >= cols) continue
                        val cellIdx = nr * cols + nc
                        val cellCount = counts[cellIdx]
                        val cellBase = cellIdx * maxPerCell
                        for (k in 0 until cellCount) {
                            val j = grid[cellBase + k]
                            if (j <= i) continue
                            val dx = x[j] - ix
                            val dy = y[j] - iy
                            val distSq = dx * dx + dy * dy
                            if (distSq < thresholdSq) {
                                path.moveTo(ix.toFloat(), iy.toFloat())
                                path.lineTo(x[j].toFloat(), y[j].toFloat())
                                hasContent = true
                            }
                        }
                    }
                }
            }
            if (hasContent) canvas.drawPath(path, paint)
        }

        // Draw dots on top
        renderDots(canvas, st, w, h, colorsR, colorsG, colorsB, nColors, colorMode, scale)
    }

    // ── Heatmap rendering (pixel writes via small bitmap) ────────────
    private fun renderHeatmap(
        canvas: Canvas,
        st: BrownianState,
        w: Int, h: Int,
        colorsR: IntArray, colorsG: IntArray, colorsB: IntArray,
        nColors: Int,
        palette: Palette
    ) {
        val n = st.n
        val x = st.x
        val y = st.y

        val gw = max(1, w / 4)
        val gh = max(1, h / 4)
        val scaleX = gw.toFloat() / w
        val scaleY = gh.toFloat() / h

        if (st.heatGrid == null || st.heatW != gw || st.heatH != gh) {
            st.heatGrid = FloatArray(gw * gh)
            st.heatW = gw
            st.heatH = gh
        }
        val heatGrid = st.heatGrid!!

        // Decay
        val len = gw * gh
        for (i in 0 until len) {
            heatGrid[i] *= 0.97f
        }

        // Accumulate
        for (i in 0 until n) {
            val gx = min(gw - 1, max(0, (x[i] * scaleX).toInt()))
            val gy = min(gh - 1, max(0, (y[i] * scaleY).toInt()))
            heatGrid[gy * gw + gx] += 1f
        }

        // Palette LUT
        val palKey = palette.colorInts().joinToString(",")
        if (st.paletteLUT == null || st.paletteLUTKey != palKey) {
            st.paletteLUT = buildPaletteLUT(colorsR, colorsG, colorsB, nColors)
            st.paletteLUTKey = palKey
        }
        val lut = st.paletteLUT!!

        // Max for normalization
        var maxVal = 0f
        for (i in 0 until len) {
            if (heatGrid[i] > maxVal) maxVal = heatGrid[i]
        }
        val invMax = if (maxVal > 0f) 1f / maxVal else 0f

        val pixels = IntArray(len)
        val bgARGB = Color.argb(255, 10, 10, 10)
        for (i in 0 until len) {
            val v = heatGrid[i] * invMax
            if (v < 0.01f) {
                pixels[i] = bgARGB
            } else {
                val lutIdx = min(255, (sqrt(v) * 255f).toInt())
                pixels[i] = lut[lutIdx]
            }
        }

        val small = Bitmap.createBitmap(gw, gh, Bitmap.Config.ARGB_8888)
        small.setPixels(pixels, 0, gw, 0, 0, gw, gh)
        val filterPaint = Paint().apply { isFilterBitmap = true }
        canvas.drawBitmap(
            small,
            Rect(0, 0, gw, gh),
            Rect(0, 0, w, h),
            filterPaint
        )
        small.recycle()
    }

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float {
        val pc = pf(params, "particleCount", 150f)
        val tl = pf(params, "trailLength", 300f)
        val spf = pf(params, "stepsPerFrame", 3f)
        return (pc * tl * spf * 0.0001f / 100f).coerceIn(0.1f, 1f)
    }
}
