package com.artmondo.algomodo.generators.plotter

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import com.artmondo.algomodo.core.rng.SeededRNG
import com.artmondo.algomodo.data.palettes.Palette
import com.artmondo.algomodo.generators.Generator
import com.artmondo.algomodo.generators.ParamGroup
import com.artmondo.algomodo.generators.Parameter
import com.artmondo.algomodo.generators.Quality
import kotlin.math.*

class SpaceFillingCurveGenerator : Generator {

    override val id = "plotter-space-filling-curve"
    override val family = "plotter"
    override val styleName = "Space-Filling Curve"
    override val definition =
        "Renders Hilbert, Peano, or Gosper curves that densely fill the canvas with a single continuous path."
    override val algorithmNotes =
        "Hilbert curve uses iterative bit-manipulation to map 1D index to 2D coordinates. " +
        "Peano curve uses recursive 9-fold subdivision. Gosper/Dragon/Sierpinski/Moore use " +
        "L-system rewriting with turtle graphics. Points are normalized to [0,1] then scaled " +
        "to canvas with padding. Color is mapped along the path length using palette interpolation."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema = listOf(
        Parameter.SelectParam("Curve Type", "curveType", ParamGroup.COMPOSITION,
            "hilbert: right-angle grid filler | peano: 9-fold recursive | gosper: hex flowsnake | dragon: folding fractal | sierpinski: triangular arrowhead | moore: closed hilbert loop",
            listOf("hilbert", "peano", "gosper", "dragon", "sierpinski", "moore"), "hilbert"),
        Parameter.NumberParam("Order", "order", ParamGroup.COMPOSITION,
            "Recursion depth — fractional values partially fill the next level", 2f, 7f, 0.5f, 5f),
        Parameter.NumberParam("Thickness", "thickness", ParamGroup.TEXTURE,
            "Line width as fraction of grid cell", 0.1f, 1f, 0.05f, 0.6f),
        Parameter.SelectParam("Color Mode", "colorMode", ParamGroup.COLOR,
            "gradient: smooth palette along path | segments: alternating bands | solid: single color | rainbow: HSL sweep",
            listOf("gradient", "segments", "solid", "rainbow"), "gradient"),
        Parameter.SelectParam("Path", "path", ParamGroup.GEOMETRY,
            "linear: straight segments | rounded: smooth bezier | stepped: orthogonal staircase",
            listOf("linear", "rounded", "stepped"), "linear"),
        Parameter.SelectParam("Direction", "direction", ParamGroup.GEOMETRY,
            "normal | reverse | mirror-x | mirror-y",
            listOf("normal", "reverse", "mirror-x", "mirror-y"), "normal"),
        Parameter.SelectParam("Line Style", "lineStyle", ParamGroup.TEXTURE,
            "solid: continuous | dashed | dotted",
            listOf("solid", "dashed", "dotted"), "solid"),
        Parameter.NumberParam("Padding", "padding", ParamGroup.COMPOSITION,
            "Canvas edge spacing as fraction", 0f, 0.15f, 0.01f, 0.05f),
        Parameter.NumberParam("Jitter", "jitter", ParamGroup.GEOMETRY,
            "Randomly displace points for a hand-drawn feel", 0f, 1f, 0.05f, 0f),
        Parameter.NumberParam("Anim Speed", "animSpeed", ParamGroup.FLOW_MOTION,
            "Animation speed", 0.1f, 3f, 0.1f, 1f),
        Parameter.NumberParam("Anim Amplitude", "animAmp", ParamGroup.FLOW_MOTION,
            "Wave distortion intensity", 0f, 2f, 0.1f, 0.5f)
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "curveType" to "hilbert",
        "order" to 5f,
        "thickness" to 0.6f,
        "colorMode" to "gradient",
        "path" to "linear",
        "direction" to "normal",
        "lineStyle" to "solid",
        "padding" to 0.05f,
        "jitter" to 0f,
        "animSpeed" to 1f,
        "animAmp" to 0.5f
    )

    // ── Curve point cache ───────────────────────────────────────────────────
    @Volatile private var cachedKey = ""
    @Volatile private var cachedXs = FloatArray(0)
    @Volatile private var cachedYs = FloatArray(0)

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

        val curveType = (params["curveType"] as? String) ?: "hilbert"
        var order = (params["order"] as? Number)?.toFloat() ?: 5f
        val thickness = (params["thickness"] as? Number)?.toFloat() ?: 0.6f
        val colorMode = (params["colorMode"] as? String) ?: "gradient"
        val pathMode = (params["path"] as? String) ?: "linear"
        val direction = (params["direction"] as? String) ?: "normal"
        val lineStyle = (params["lineStyle"] as? String) ?: "solid"
        val padding = (params["padding"] as? Number)?.toFloat() ?: 0.05f
        val jitter = (params["jitter"] as? Number)?.toFloat() ?: 0f
        val animSpeed = (params["animSpeed"] as? Number)?.toFloat() ?: 1f
        val animAmp = (params["animAmp"] as? Number)?.toFloat() ?: 0.5f

        // Quality cap to avoid huge point counts in DRAFT
        if (quality == Quality.DRAFT) {
            order = order.coerceAtMost(4.5f)
        }

        val palColors = palette.colorInts().toIntArray()
        val minDim = minOf(w, h)

        // Background: very dark tint of first palette color
        val c0 = palColors[0]
        canvas.drawColor(Color.rgb(
            (Color.red(c0) * 0.08f).toInt(),
            (Color.green(c0) * 0.08f).toInt(),
            (Color.blue(c0) * 0.08f).toInt()
        ))

        // ── Get curve points (cached) ───────────────────────────────────────
        val (rawXs, rawYs) = getCurvePoints(curveType, order)
        var total = rawXs.size
        if (total < 2) return

        // Apply direction transform (work on copies)
        val xs: FloatArray
        val ys: FloatArray
        when (direction) {
            "reverse" -> {
                xs = FloatArray(total) { rawXs[total - 1 - it] }
                ys = FloatArray(total) { rawYs[total - 1 - it] }
            }
            "mirror-x" -> {
                xs = FloatArray(total) { 1f - rawXs[it] }
                ys = rawYs.copyOf()
            }
            "mirror-y" -> {
                xs = rawXs.copyOf()
                ys = FloatArray(total) { 1f - rawYs[it] }
            }
            else -> {
                xs = rawXs
                ys = rawYs
            }
        }

        // ── Map to canvas coordinates ───────────────────────────────────────
        val pad = padding * minDim
        val drawW = w - pad * 2
        val drawH = h - pad * 2
        val scale = minOf(drawW, drawH)
        val offX = pad + (drawW - scale) * 0.5f
        val offY = pad + (drawH - scale) * 0.5f

        // Grid spacing for thickness/jitter calibration
        val intOrd = ceil(order).toInt()
        val gridN = when (curveType) {
            "hilbert", "moore" -> (1 shl intOrd).toFloat()
            "peano" -> 3f.pow(intOrd.coerceAtMost(3))
            else -> maxOf(4f, sqrt(total.toFloat()))
        }
        val segSpacing = scale / gridN
        val lineWidth = maxOf(0.5f, segSpacing * thickness)
        val jitterAmt = jitter * segSpacing * 0.5f

        // Animation
        val t = time * animSpeed
        var drawCount = total
        if (time > 0f) {
            val progress = ((t * 0.15f) % 1.2f).coerceAtMost(1f)
            val eased = if (progress < 1f) 1f - (1f - progress).pow(3) else 1f
            drawCount = maxOf(2, ceil(eased * total).toInt())
        }
        val waveAmt = animAmp * segSpacing * 0.6f

        // Jitter RNG
        val jRng = SeededRNG(seed xor 0xCAFE)

        // ── Pre-compute screen positions ────────────────────────────────────
        val screenX = FloatArray(drawCount)
        val screenY = FloatArray(drawCount)
        for (i in 0 until drawCount) {
            var px = offX + xs[i] * scale
            var py = offY + ys[i] * scale
            if (jitterAmt > 0f) {
                px += (jRng.randomDouble().toFloat() - 0.5f) * 2f * jitterAmt
                py += (jRng.randomDouble().toFloat() - 0.5f) * 2f * jitterAmt
            }
            if (waveAmt > 0f) {
                val phase = i * 0.15f
                px += sin(t * 1.3f + phase) * waveAmt
                py += cos(t * 0.9f + phase * 1.3f) * waveAmt
            }
            screenX[i] = px
            screenY[i] = py
        }

        // ── Setup paint ─────────────────────────────────────────────────────
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = lineWidth
            strokeCap = if (lineStyle == "dotted") Paint.Cap.ROUND else Paint.Cap.BUTT
            strokeJoin = Paint.Join.ROUND
        }
        when (lineStyle) {
            "dashed" -> {
                val dash = maxOf(4f, lineWidth * 6f)
                paint.pathEffect = DashPathEffect(floatArrayOf(dash, dash * 0.6f), 0f)
            }
            "dotted" -> {
                paint.pathEffect = DashPathEffect(floatArrayOf(lineWidth, lineWidth * 3f), 0f)
            }
        }

        // ── Draw ────────────────────────────────────────────────────────────
        if (colorMode == "solid") {
            paint.color = palColors[minOf(1, palColors.size - 1)]
            val path = buildPath(screenX, screenY, drawCount, pathMode)
            canvas.drawPath(path, paint)
        } else {
            drawColoredCurve(canvas, screenX, screenY, drawCount, total, pathMode,
                colorMode, palColors, palette, paint)
        }
    }

    // ── Path construction helpers ───────────────────────────────────────────

    private fun buildPath(sx: FloatArray, sy: FloatArray, count: Int, pathMode: String): Path {
        val path = Path()
        path.moveTo(sx[0], sy[0])
        for (i in 1 until count) {
            addSegment(path, sx[i - 1], sy[i - 1], sx[i], sy[i], pathMode)
        }
        if (pathMode == "rounded" && count > 1) path.lineTo(sx[count - 1], sy[count - 1])
        return path
    }

    private fun addSegment(path: Path, prevX: Float, prevY: Float, curX: Float, curY: Float, pathMode: String) {
        when (pathMode) {
            "rounded" -> {
                val mx = (prevX + curX) * 0.5f
                val my = (prevY + curY) * 0.5f
                path.quadTo(prevX, prevY, mx, my)
            }
            "stepped" -> {
                path.lineTo(curX, prevY)
                path.lineTo(curX, curY)
            }
            else -> path.lineTo(curX, curY)
        }
    }

    private fun drawColoredCurve(
        canvas: Canvas, sx: FloatArray, sy: FloatArray, drawCount: Int, total: Int,
        pathMode: String, colorMode: String, palColors: IntArray, palette: Palette, paint: Paint
    ) {
        val segLen = if (colorMode == "segments")
            maxOf(4, total / (palColors.size * 4)) else 1

        val path = Path()
        var batchColor = computeSegColor(0, total, colorMode, palColors, palette, segLen)
        paint.color = batchColor
        path.moveTo(sx[0], sy[0])

        for (i in 1 until drawCount) {
            val color = computeSegColor(i, total, colorMode, palColors, palette, segLen)
            if (color != batchColor) {
                // Flush current batch
                if (pathMode == "rounded") path.lineTo(sx[i - 1], sy[i - 1])
                canvas.drawPath(path, paint)
                batchColor = color
                paint.color = color
                path.reset()
                path.moveTo(sx[i - 1], sy[i - 1])
            }
            addSegment(path, sx[i - 1], sy[i - 1], sx[i], sy[i], pathMode)
        }
        if (pathMode == "rounded" && drawCount > 1) path.lineTo(sx[drawCount - 1], sy[drawCount - 1])
        canvas.drawPath(path, paint)
    }

    private fun computeSegColor(
        i: Int, total: Int, colorMode: String, palColors: IntArray, palette: Palette, segLen: Int
    ): Int {
        val frac = i.toFloat() / maxOf(1, total - 1)
        return when (colorMode) {
            "rainbow" -> {
                // HSL sweep quantized to reduce draw calls
                val hue = ((frac * 360f / 5f).toInt() * 5).toFloat()
                Color.HSVToColor(floatArrayOf(hue, 0.85f, 0.75f))
            }
            "segments" -> palColors[(i / segLen) % palColors.size]
            else -> palette.lerpColor(frac) // gradient
        }
    }

    // ── Curve generation ────────────────────────────────────────────────────

    private fun getCurvePoints(curveType: String, order: Float): Pair<FloatArray, FloatArray> {
        val key = "$curveType|$order"
        if (key == cachedKey) return Pair(cachedXs, cachedYs)

        val intOrder = ceil(order).toInt()
        val frac = order - floor(order)
        val lsysMax = 5

        var (fullXs, fullYs) = when (curveType) {
            "hilbert" -> hilbertPoints(intOrder.coerceAtMost(7))
            "peano" -> peanoPoints(intOrder.coerceAtMost(3))
            "gosper" -> gosperPoints(intOrder.coerceAtMost(lsysMax))
            "dragon" -> dragonPoints((intOrder + 5).coerceAtMost(16))
            "sierpinski" -> sierpinskiPoints((intOrder + 3).coerceAtMost(10))
            "moore" -> moorePoints(intOrder.coerceAtMost(7))
            else -> hilbertPoints(intOrder.coerceAtMost(7))
        }

        // Fractional order: truncate to a portion of the full curve
        if (frac > 0.01f && frac < 0.99f) {
            val count = maxOf(2, (fullXs.size * frac).roundToInt())
            fullXs = fullXs.copyOf(count)
            fullYs = fullYs.copyOf(count)
        }

        cachedKey = key
        cachedXs = fullXs
        cachedYs = fullYs
        return Pair(fullXs, fullYs)
    }

    // ── Hilbert curve — iterative bit-manipulation ──────────────────────────

    private fun hilbertPoints(order: Int): Pair<FloatArray, FloatArray> {
        val n = 1 shl order
        val total = n * n
        val xs = FloatArray(total)
        val ys = FloatArray(total)
        val nm1 = maxOf(1f, (n - 1).toFloat())

        for (i in 0 until total) {
            var x = 0; var y = 0; var t = i
            var s = 1
            while (s < n) {
                val rx = (t and 2) shr 1
                val ry = (t and 1) xor rx
                if (ry == 0) {
                    if (rx == 1) { x = s - 1 - x; y = s - 1 - y }
                    val tmp = x; x = y; y = tmp
                }
                x += s * rx; y += s * ry
                t = t shr 2; s = s shl 1
            }
            xs[i] = x / nm1; ys[i] = y / nm1
        }
        return Pair(xs, ys)
    }

    // ── Peano curve — iterative 9-fold subdivision ──────────────────────────

    private fun peanoPoints(order: Int): Pair<FloatArray, FloatArray> {
        val n = 3.0.pow(order).toInt()
        val total = n * n
        val xs = FloatArray(total)
        val ys = FloatArray(total)
        val nm1 = maxOf(1f, (n - 1).toFloat())

        for (i in 0 until total) {
            var x = 0; var y = 0; var t = i
            var flipX = false; var flipY = false
            var s = 1
            while (s < n) {
                val rx = (t / 3) % 3
                val ry = t % 3
                x += (if (flipX) 2 - rx else rx) * s
                y += (if (flipY) 2 - ry else ry) * s
                if (rx == 1) flipY = !flipY
                if (ry == 1) flipX = !flipX
                t /= 9; s *= 3
            }
            xs[i] = x / nm1; ys[i] = y / nm1
        }
        return Pair(xs, ys)
    }

    // ── L-system based curves ───────────────────────────────────────────────

    private fun lsystemExpand(axiom: String, rules: Map<Char, String>, iterations: Int): String {
        var str = axiom
        for (i in 0 until iterations) {
            val sb = StringBuilder(str.length * 4)
            for (c in str) sb.append(rules[c] ?: c.toString())
            str = sb.toString()
        }
        return str
    }

    private fun turtleWalk(instructions: String, drawChars: String, turnAngle: Double): Pair<FloatArray, FloatArray> {
        var count = 1
        for (c in instructions) if (c in drawChars) count++
        val xs = FloatArray(count)
        val ys = FloatArray(count)
        var x = 0.0; var y = 0.0; var angle = 0.0
        var idx = 0
        xs[0] = 0f; ys[0] = 0f; idx++
        for (c in instructions) {
            when {
                c in drawChars -> {
                    x += cos(angle); y += sin(angle)
                    xs[idx] = x.toFloat(); ys[idx] = y.toFloat(); idx++
                }
                c == '+' -> angle += turnAngle
                c == '-' -> angle -= turnAngle
            }
        }
        normalizePoints(xs, ys)
        return Pair(xs, ys)
    }

    private fun normalizePoints(xs: FloatArray, ys: FloatArray) {
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (i in xs.indices) {
            if (xs[i] < minX) minX = xs[i]; if (xs[i] > maxX) maxX = xs[i]
            if (ys[i] < minY) minY = ys[i]; if (ys[i] > maxY) maxY = ys[i]
        }
        val rx = maxOf(1f, maxX - minX)
        val ry = maxOf(1f, maxY - minY)
        for (i in xs.indices) {
            xs[i] = (xs[i] - minX) / rx
            ys[i] = (ys[i] - minY) / ry
        }
    }

    private fun dragonPoints(order: Int): Pair<FloatArray, FloatArray> {
        val str = lsystemExpand("FX", mapOf('X' to "X+YF+", 'Y' to "-FX-Y"), order)
        return turtleWalk(str, "F", PI / 2)
    }

    private fun sierpinskiPoints(order: Int): Pair<FloatArray, FloatArray> {
        val axiom = if (order % 2 == 0) "A" else "B"
        val str = lsystemExpand(axiom, mapOf('A' to "B-A-B", 'B' to "A+B+A"), order)
        return turtleWalk(str, "AB", PI / 3)
    }

    private fun moorePoints(order: Int): Pair<FloatArray, FloatArray> {
        val str = lsystemExpand("LFL+F+LFL", mapOf('L' to "-RF+LFL+FR-", 'R' to "+LF-RFR-FL+"), order)
        return turtleWalk(str, "F", PI / 2)
    }

    private fun gosperPoints(order: Int): Pair<FloatArray, FloatArray> {
        val str = lsystemExpand("A", mapOf('A' to "A-B--B+A++AA+B-", 'B' to "+A-BB--B-A++A+B"), order)
        return turtleWalk(str, "AB", PI / 3)
    }

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float {
        val curveType = (params["curveType"] as? String) ?: "hilbert"
        val order = (params["order"] as? Number)?.toInt() ?: 5
        val pts = when (curveType) {
            "hilbert" -> 4.0.pow(order)
            "peano" -> 9.0.pow(order.coerceAtMost(3))
            "gosper" -> 7.0.pow(order.coerceAtMost(5))
            "dragon" -> 2.0.pow((order + 5).coerceAtMost(16))
            "sierpinski" -> 3.0.pow((order + 3).coerceAtMost(10))
            "moore" -> 4.0.pow(order.coerceAtMost(7))
            else -> 4.0.pow(order)
        }
        return (pts * 0.005).toFloat().coerceIn(0.1f, 1f)
    }
}
