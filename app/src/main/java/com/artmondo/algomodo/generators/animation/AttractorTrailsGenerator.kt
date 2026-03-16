package com.artmondo.algomodo.generators.animation

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import com.artmondo.algomodo.core.rng.SeededRNG
import com.artmondo.algomodo.data.palettes.Palette
import com.artmondo.algomodo.generators.Generator
import com.artmondo.algomodo.generators.ParamGroup
import com.artmondo.algomodo.generators.Parameter
import com.artmondo.algomodo.generators.Quality
import kotlin.math.*

class AttractorTrailsGenerator : Generator {

    override val id = "attractor-trails"
    override val family = "animation"
    override val styleName = "Attractor Trails"
    override val definition = "Particles tracing strange attractor trajectories, projected to 2D with coloured trails."
    override val algorithmNotes =
        "2D iterative map attractors (Clifford, De Jong, etc.) are iterated many thousands of " +
        "times. Each iteration produces a new (x,y) point which is mapped to a pixel. A density " +
        "histogram accumulates hit counts per pixel. The histogram is tone-mapped using " +
        "log(1 + count * brightness) and mapped to palette.lerpColor(). Attractor parameters " +
        "oscillate sinusoidally over time at seeded frequencies for animation."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema = listOf(
        Parameter.SelectParam(
            name = "Attractor Type",
            key = "attractorType",
            group = ParamGroup.COMPOSITION,
            help = "clifford / dejong / bedhead: classic sine-based maps · svensson: flame-like variant · tinkerbell: quadratic map with different topology",
            options = listOf("clifford", "dejong", "bedhead", "svensson", "tinkerbell"),
            default = "clifford"
        ),
        Parameter.NumberParam(
            name = "Iterations (×1000)",
            key = "iterations",
            group = ParamGroup.COMPOSITION,
            help = "More iterations = denser histogram; lower values improve animation frame-rate",
            min = 100f, max = 2000f, step = 100f, default = 800f
        ),
        Parameter.NumberParam(
            name = "Brightness",
            key = "brightness",
            group = ParamGroup.TEXTURE,
            help = "Log tone-map exponent — higher lifts dim regions brighter but clips peaks",
            min = 0.5f, max = 4f, step = 0.1f, default = 1.5f
        ),
        Parameter.SelectParam(
            name = "Color Mode",
            key = "colorMode",
            group = ParamGroup.COLOR,
            help = "density: brightness→palette gradient · velocity: local speed · angle: movement direction · multi: overlapping offset layers, each in a distinct palette color",
            options = listOf("density", "velocity", "angle", "multi"),
            default = "density"
        ),
        Parameter.NumberParam(
            name = "Color Shift",
            key = "colorShift",
            group = ParamGroup.COLOR,
            help = "Slide the palette lookup slowly over time — animates colour bands without changing the attractor shape",
            min = 0f, max = 1f, step = 0.05f, default = 0f
        ),
        Parameter.NumberParam(
            name = "Point Size",
            key = "pointSize",
            group = ParamGroup.GEOMETRY,
            help = null,
            min = 1f, max = 4f, step = 1f, default = 1f
        ),
        Parameter.NumberParam(
            name = "Drift Speed",
            key = "driftSpeed",
            group = ParamGroup.FLOW_MOTION,
            help = "How fast the attractor parameters oscillate over time — each at a distinct seeded frequency",
            min = 0f, max = 1.0f, step = 0.05f, default = 0.2f
        ),
        Parameter.NumberParam(
            name = "Drift Amplitude",
            key = "driftAmp",
            group = ParamGroup.FLOW_MOTION,
            help = "Maximum ±offset on each parameter during animation — larger = more extreme morphing",
            min = 0f, max = 0.5f, step = 0.02f, default = 0.15f
        )
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "attractorType" to "clifford",
        "iterations" to 800f,
        "brightness" to 1.5f,
        "colorMode" to "density",
        "colorShift" to 0f,
        "pointSize" to 1f,
        "driftSpeed" to 0.2f,
        "driftAmp" to 0.15f
    )

    // ── Fast sin/cos lookup table ──
    // 8192 entries gives max error ~0.0004 — invisible for pixel rendering.
    // Eliminates ~2M+ Math.sin() calls per frame (the main bottleneck on ARM).
    private companion object {
        const val TABLE_BITS = 13
        const val TABLE_SIZE = 1 shl TABLE_BITS          // 8192
        const val TABLE_MASK = TABLE_SIZE - 1
        val TWO_PI = (2.0 * PI).toFloat()
        val INV_TWO_PI_TABLE = (TABLE_SIZE / (2.0 * PI)).toFloat()
        val QUARTER_TABLE = TABLE_SIZE / 4                 // cos offset

        const val TYPE_CLIFFORD = 0
        const val TYPE_DEJONG = 1
        const val TYPE_BEDHEAD = 2
        const val TYPE_SVENSSON = 3
        const val TYPE_TINKERBELL = 4
        const val PALETTE_LUT_SIZE = 256
        const val BG_COLOR = 0xFF040408.toInt()

        val SIN_TABLE = FloatArray(TABLE_SIZE) {
            sin(it.toDouble() / TABLE_SIZE * 2.0 * PI).toFloat()
        }

        fun fsin(x: Float): Float {
            // Map x to table index. Handles negative values via (% + mask).
            val i = (x * INV_TWO_PI_TABLE).toInt()
            return SIN_TABLE[(i % TABLE_SIZE + TABLE_SIZE) and TABLE_MASK]
        }

        fun fcos(x: Float): Float {
            val i = (x * INV_TWO_PI_TABLE).toInt() + QUARTER_TABLE
            return SIN_TABLE[(i % TABLE_SIZE + TABLE_SIZE) and TABLE_MASK]
        }
    }

    // Reusable buffers
    private var histogram: IntArray? = null
    private var pixels: IntArray? = null
    private var auxFloat: FloatArray? = null
    private var lastBufSize = 0
    private var renderBitmap: Bitmap? = null

    private fun ensureBuffers(size: Int, needAux: Boolean) {
        if (size != lastBufSize) {
            histogram = IntArray(size)
            pixels = IntArray(size)
            auxFloat = if (needAux) FloatArray(size) else null
            lastBufSize = size
        } else {
            histogram!!.fill(0)
            if (needAux) {
                if (auxFloat == null) auxFloat = FloatArray(size) else auxFloat!!.fill(0f)
            }
        }
    }

    private fun ensureRenderBitmap(rw: Int, rh: Int): Bitmap {
        val existing = renderBitmap
        if (existing != null && existing.width == rw && existing.height == rh) return existing
        val bmp = Bitmap.createBitmap(rw, rh, Bitmap.Config.ARGB_8888)
        renderBitmap = bmp
        return bmp
    }

    private fun typeId(type: String): Int = when (type) {
        "clifford" -> TYPE_CLIFFORD
        "dejong" -> TYPE_DEJONG
        "bedhead" -> TYPE_BEDHEAD
        "svensson" -> TYPE_SVENSSON
        "tinkerbell" -> TYPE_TINKERBELL
        else -> TYPE_CLIFFORD
    }

    private fun baseParams(type: Int, rng: SeededRNG): FloatArray = when (type) {
        TYPE_CLIFFORD -> floatArrayOf(
            -1.4f + rng.random().toFloat() * 0.2f, 1.6f + rng.random().toFloat() * 0.2f,
            1.0f + rng.random().toFloat() * 0.2f, 0.7f + rng.random().toFloat() * 0.2f
        )
        TYPE_DEJONG -> floatArrayOf(
            -2.0f + rng.random().toFloat() * 0.3f, 2.0f + rng.random().toFloat() * 0.3f,
            -1.2f + rng.random().toFloat() * 0.3f, 2.0f + rng.random().toFloat() * 0.3f
        )
        TYPE_BEDHEAD -> floatArrayOf(
            -0.81f + rng.random().toFloat() * 0.1f, -0.92f + rng.random().toFloat() * 0.1f,
            0.0f, 0.0f
        )
        TYPE_SVENSSON -> floatArrayOf(
            1.4f + rng.random().toFloat() * 0.2f, 1.56f + rng.random().toFloat() * 0.2f,
            1.4f + rng.random().toFloat() * 0.2f, -6.56f + rng.random().toFloat() * 0.2f
        )
        TYPE_TINKERBELL -> floatArrayOf(
            0.9f + rng.random().toFloat() * 0.05f, -0.6013f + rng.random().toFloat() * 0.05f,
            2.0f + rng.random().toFloat() * 0.05f, 0.5f + rng.random().toFloat() * 0.05f
        )
        else -> floatArrayOf(-1.4f, 1.6f, 1.0f, 0.7f)
    }

    private fun coordRange(type: Int): Float = when (type) {
        TYPE_CLIFFORD -> 2.8f
        TYPE_DEJONG -> 2.5f
        TYPE_BEDHEAD -> 3.5f
        TYPE_SVENSSON -> 3.0f
        TYPE_TINKERBELL -> 2.5f
        else -> 3.0f
    }

    private fun buildPaletteLut(palette: Palette, shift: Float): IntArray {
        val lut = IntArray(PALETTE_LUT_SIZE)
        for (i in 0 until PALETTE_LUT_SIZE) {
            val t = ((i.toFloat() / (PALETTE_LUT_SIZE - 1) + shift) % 1f + 1f) % 1f
            lut[i] = palette.lerpColor(t)
        }
        return lut
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
        val fullW = bitmap.width
        val fullH = bitmap.height

        val type = typeId((params["attractorType"] as? String) ?: "clifford")
        val iterationsK = ((params["iterations"] as? Number)?.toInt() ?: 800)
        val brightness = (params["brightness"] as? Number)?.toFloat() ?: 1.5f
        val colorMode = (params["colorMode"] as? String) ?: "density"
        val colorShift = (params["colorShift"] as? Number)?.toFloat() ?: 0f
        val pointRadius = (params["pointSize"] as? Number)?.toFloat()?.let { (it - 1f) / 2f } ?: 0f
        val driftSpeed = (params["driftSpeed"] as? Number)?.toFloat() ?: 0.2f
        val driftAmp = (params["driftAmp"] as? Number)?.toFloat() ?: 0.15f

        // Resolution scaling: render at lower res during animation, full res for export
        val resScale = when (quality) {
            Quality.DRAFT -> 0.4f
            Quality.BALANCED -> 0.55f
            Quality.ULTRA -> 1f
        }
        val rw = (fullW * resScale).toInt().coerceAtLeast(200)
        val rh = (fullH * resScale).toInt().coerceAtLeast(200)
        val renderDim = min(rw, rh)

        // Iteration count scaled with resolution
        val totalIterations = when (quality) {
            Quality.DRAFT -> (iterationsK * 200)       // 200 per K (was 300)
            Quality.BALANCED -> (iterationsK * 500)    // 500 per K (was 700)
            Quality.ULTRA -> (iterationsK * 1000)
        }

        val rng = SeededRNG(seed)
        val bp = baseParams(type, rng)
        val phases = FloatArray(4) { rng.random().toFloat() * TWO_PI }
        val freqs = FloatArray(4) { 0.3f + rng.random().toFloat() * 0.7f }

        val tp = time * driftSpeed
        val a = bp[0] + driftAmp * fsin(tp * freqs[0] * TWO_PI + phases[0])
        val b = bp[1] + driftAmp * fsin(tp * freqs[1] * TWO_PI + phases[1])
        val c = bp[2] + driftAmp * fsin(tp * freqs[2] * TWO_PI + phases[2])
        val d = bp[3] + driftAmp * fsin(tp * freqs[3] * TWO_PI + phases[3])

        val range = coordRange(type)
        val cx = rw * 0.5f
        val cy = rh * 0.5f
        val scale = renderDim / (2f * range)
        val bufSize = rw * rh

        val needAux = colorMode == "angle" || colorMode == "velocity"
        ensureBuffers(bufSize, needAux)
        val hist = histogram!!
        val pix = pixels!!
        val aux = auxFloat

        // Splat precomputation
        val splatOffsets: IntArray?
        val splatCount: Int
        if (pointRadius > 0f) {
            val ir = ceil(pointRadius).toInt()
            val r2 = pointRadius * pointRadius
            val offsets = mutableListOf<Int>()
            for (dy in -ir..ir) for (dx in -ir..ir) {
                if (dx == 0 && dy == 0) continue
                if (dx * dx + dy * dy <= r2) { offsets.add(dx); offsets.add(dy) }
            }
            splatOffsets = offsets.toIntArray()
            splatCount = offsets.size / 2
        } else {
            splatOffsets = null; splatCount = 0
        }

        // ── Warmup ──
        var x = 0.5f; var y = 0.5f
        for (i in 0 until 200) {
            iterateInline(type, x, y, a, b, c, d) { nx, ny -> x = nx; y = ny }
            if (x.isNaN() || x.isInfinite()) { x = 0.1f; y = 0.1f }
        }

        // ── Main iteration (type-specialized, zero-allocation, fast trig) ──
        when {
            colorMode == "multi" -> { /* skip main loop — multi does its own */ }
            colorMode == "angle" -> iterateWithAngle(type, x, y, a, b, c, d, totalIterations, cx, cy, scale, rw, rh, hist, aux!!, splatOffsets, splatCount)
            colorMode == "velocity" -> iterateWithVelocity(type, x, y, a, b, c, d, totalIterations, cx, cy, scale, rw, rh, hist, aux!!, splatOffsets, splatCount)
            else -> iterateDensity(type, x, y, a, b, c, d, totalIterations, cx, cy, scale, rw, rh, hist, splatOffsets, splatCount)
        }

        // ── Tone mapping ──
        val paletteShift = colorShift + time * colorShift * 0.5f

        if (colorMode == "multi") {
            renderMulti(type, a, b, c, d, cx, cy, scale, rw, rh, totalIterations,
                brightness, paletteShift, palette, pix)
        } else {
            toneMap(hist, aux, colorMode, brightness, paletteShift, palette, pix, bufSize)
        }

        // ── Output: upscale if needed ──
        if (rw == fullW && rh == fullH) {
            bitmap.setPixels(pix, 0, rw, 0, 0, rw, rh)
            canvas.drawBitmap(bitmap, 0f, 0f, null)
        } else {
            val renderBmp = ensureRenderBitmap(rw, rh)
            renderBmp.setPixels(pix, 0, rw, 0, 0, rw, rh)
            val srcRect = Rect(0, 0, rw, rh)
            val dstRect = RectF(0f, 0f, fullW.toFloat(), fullH.toFloat())
            val p = Paint(Paint.FILTER_BITMAP_FLAG)
            canvas.drawBitmap(renderBmp, srcRect, dstRect, p)
        }
    }

    // ── Type-specialized iteration loops ──
    // Each loop inlines the attractor math directly to avoid when-dispatch per iteration.

    private fun iterateDensity(
        type: Int, startX: Float, startY: Float,
        a: Float, b: Float, c: Float, d: Float,
        iterations: Int, cx: Float, cy: Float, scale: Float,
        w: Int, h: Int, hist: IntArray,
        splatOffsets: IntArray?, splatCount: Int
    ) {
        var x = startX; var y = startY
        when (type) {
            TYPE_CLIFFORD -> for (i in 0 until iterations) {
                val nx = fsin(a * y) + c * fcos(a * x)
                val ny = fsin(b * x) + d * fcos(b * y)
                if (nx.isNaN() || nx.isInfinite()) { x = 0.1f; y = 0.1f; continue }
                x = nx; y = ny
                plotPoint(x, y, cx, cy, scale, w, h, hist, splatOffsets, splatCount)
            }
            TYPE_DEJONG -> for (i in 0 until iterations) {
                val nx = fsin(a * y) - fcos(b * x)
                val ny = fsin(c * x) - fcos(d * y)
                if (nx.isNaN() || nx.isInfinite()) { x = 0.1f; y = 0.1f; continue }
                x = nx; y = ny
                plotPoint(x, y, cx, cy, scale, w, h, hist, splatOffsets, splatCount)
            }
            TYPE_BEDHEAD -> for (i in 0 until iterations) {
                val nx = fsin(x * y / b) + fcos(a * x - y)
                val ny = x + fsin(y) / b
                if (nx.isNaN() || nx.isInfinite()) { x = 0.1f; y = 0.1f; continue }
                x = nx; y = ny
                plotPoint(x, y, cx, cy, scale, w, h, hist, splatOffsets, splatCount)
            }
            TYPE_SVENSSON -> for (i in 0 until iterations) {
                val nx = d * fsin(a * x) - fsin(b * y)
                val ny = c * fcos(a * x) + fcos(b * y)
                if (nx.isNaN() || nx.isInfinite()) { x = 0.1f; y = 0.1f; continue }
                x = nx; y = ny
                plotPoint(x, y, cx, cy, scale, w, h, hist, splatOffsets, splatCount)
            }
            TYPE_TINKERBELL -> for (i in 0 until iterations) {
                val nx = x * x - y * y + a * x + b * y
                val ny = 2f * x * y + c * x + d * y
                if (nx.isNaN() || nx.isInfinite()) { x = 0.1f; y = 0.1f; continue }
                x = nx; y = ny
                plotPoint(x, y, cx, cy, scale, w, h, hist, splatOffsets, splatCount)
            }
        }
    }

    private fun iterateWithAngle(
        type: Int, startX: Float, startY: Float,
        a: Float, b: Float, c: Float, d: Float,
        iterations: Int, cx: Float, cy: Float, scale: Float,
        w: Int, h: Int, hist: IntArray, aux: FloatArray,
        splatOffsets: IntArray?, splatCount: Int
    ) {
        var x = startX; var y = startY; var prevX = x; var prevY = y
        for (i in 0 until iterations) {
            iterateInline(type, x, y, a, b, c, d) { nx, ny ->
                if (nx.isNaN() || nx.isInfinite()) { x = 0.1f; y = 0.1f; prevX = x; prevY = y; return@iterateInline }
                prevX = x; prevY = y; x = nx; y = ny
                val px = (cx + x * scale).toInt(); val py = (cy + y * scale).toInt()
                if (px in 0 until w && py in 0 until h) {
                    val idx = py * w + px; hist[idx]++
                    aux[idx] = atan2(y - prevY, x - prevX)
                    splatHist(px, py, w, h, hist, splatOffsets, splatCount)
                }
            }
        }
    }

    private fun iterateWithVelocity(
        type: Int, startX: Float, startY: Float,
        a: Float, b: Float, c: Float, d: Float,
        iterations: Int, cx: Float, cy: Float, scale: Float,
        w: Int, h: Int, hist: IntArray, aux: FloatArray,
        splatOffsets: IntArray?, splatCount: Int
    ) {
        var x = startX; var y = startY; var prevX = x; var prevY = y
        for (i in 0 until iterations) {
            iterateInline(type, x, y, a, b, c, d) { nx, ny ->
                if (nx.isNaN() || nx.isInfinite()) { x = 0.1f; y = 0.1f; prevX = x; prevY = y; return@iterateInline }
                prevX = x; prevY = y; x = nx; y = ny
                val px = (cx + x * scale).toInt(); val py = (cy + y * scale).toInt()
                if (px in 0 until w && py in 0 until h) {
                    val idx = py * w + px; hist[idx]++
                    val vx = x - prevX; val vy = y - prevY
                    aux[idx] = sqrt(vx * vx + vy * vy)
                    splatHist(px, py, w, h, hist, splatOffsets, splatCount)
                }
            }
        }
    }

    private inline fun iterateInline(
        type: Int, x: Float, y: Float,
        a: Float, b: Float, c: Float, d: Float,
        block: (Float, Float) -> Unit
    ) {
        when (type) {
            TYPE_CLIFFORD -> block(fsin(a * y) + c * fcos(a * x), fsin(b * x) + d * fcos(b * y))
            TYPE_DEJONG -> block(fsin(a * y) - fcos(b * x), fsin(c * x) - fcos(d * y))
            TYPE_BEDHEAD -> block(fsin(x * y / b) + fcos(a * x - y), x + fsin(y) / b)
            TYPE_SVENSSON -> block(d * fsin(a * x) - fsin(b * y), c * fcos(a * x) + fcos(b * y))
            TYPE_TINKERBELL -> block(x * x - y * y + a * x + b * y, 2f * x * y + c * x + d * y)
            else -> block(fsin(a * y) + c * fcos(a * x), fsin(b * x) + d * fcos(b * y))
        }
    }

    private fun plotPoint(
        x: Float, y: Float, cx: Float, cy: Float, scale: Float,
        w: Int, h: Int, hist: IntArray,
        splatOffsets: IntArray?, splatCount: Int
    ) {
        val px = (cx + x * scale).toInt()
        val py = (cy + y * scale).toInt()
        if (px in 0 until w && py in 0 until h) {
            hist[py * w + px]++
            splatHist(px, py, w, h, hist, splatOffsets, splatCount)
        }
    }

    private fun splatHist(px: Int, py: Int, w: Int, h: Int, hist: IntArray, offsets: IntArray?, count: Int) {
        if (offsets == null) return
        var si = 0
        while (si < count) {
            val sx = px + offsets[si * 2]
            val sy = py + offsets[si * 2 + 1]
            if (sx in 0 until w && sy in 0 until h) hist[sy * w + sx]++
            si++
        }
    }

    // ── Tone mapping ──

    private fun toneMap(
        hist: IntArray, aux: FloatArray?, colorMode: String,
        brightness: Float, paletteShift: Float,
        palette: Palette, pix: IntArray, bufSize: Int
    ) {
        val palLut = buildPaletteLut(palette, 0f)
        var maxCount = 1
        for (v in hist) if (v > maxCount) maxCount = v
        val invLogMax = 1f / ln(1f + maxCount * brightness)
        val twoPiInv = 1f / TWO_PI
        val lutMax = (PALETTE_LUT_SIZE - 1).toFloat()

        for (j in 0 until bufSize) {
            val count = hist[j]
            if (count > 0) {
                val intensity = (ln(1f + count * brightness) * invLogMax).coerceIn(0f, 1f)
                val palVal = when (colorMode) {
                    "angle" -> ((aux!![j] * twoPiInv + 0.5f + paletteShift) % 1f + 1f) % 1f
                    "velocity" -> ((aux!![j] * 2f + paletteShift) % 1f + 1f) % 1f
                    else -> ((intensity + paletteShift) % 1f + 1f) % 1f
                }
                val lutIdx = (palVal * lutMax).toInt().coerceIn(0, PALETTE_LUT_SIZE - 1)
                val base = palLut[lutIdx]
                val r = ((base shr 16 and 0xFF) * intensity).toInt()
                val g = ((base shr 8 and 0xFF) * intensity).toInt()
                val b2 = ((base and 0xFF) * intensity).toInt()
                pix[j] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b2
            } else {
                pix[j] = BG_COLOR
            }
        }
    }

    // ── Multi-layer rendering ──

    private fun renderMulti(
        type: Int, a: Float, b: Float, c: Float, d: Float,
        cx: Float, cy: Float, scale: Float, w: Int, h: Int,
        totalIterations: Int, brightness: Float, paletteShift: Float,
        palette: Palette, pix: IntArray
    ) {
        val bufSize = w * h
        val colors = palette.colorInts()
        val layerCount = minOf(colors.size, 4)
        val layerR = IntArray(bufSize)
        val layerG = IntArray(bufSize)
        val layerB = IntArray(bufSize)
        val layerHist = IntArray(bufSize)

        for (layer in 0 until layerCount) {
            layerHist.fill(0)
            val la = a + layer * 0.05f
            val lb = b + layer * 0.03f

            var lx = 0.5f + layer * 0.1f
            var ly = 0.5f + layer * 0.1f
            for (wi in 0 until 200) {
                iterateInline(type, lx, ly, la, lb, c, d) { nx, ny -> lx = nx; ly = ny }
                if (lx.isNaN() || lx.isInfinite()) { lx = 0.1f; ly = 0.1f }
            }

            val layerIter = totalIterations / layerCount
            for (i in 0 until layerIter) {
                iterateInline(type, lx, ly, la, lb, c, d) { nx, ny ->
                    if (nx.isNaN() || nx.isInfinite()) { lx = 0.1f; ly = 0.1f; return@iterateInline }
                    lx = nx; ly = ny
                    val lpx = (cx + lx * scale).toInt()
                    val lpy = (cy + ly * scale).toInt()
                    if (lpx in 0 until w && lpy in 0 until h) layerHist[lpy * w + lpx]++
                }
            }

            var layerMax = 1
            for (v in layerHist) if (v > layerMax) layerMax = v
            val invLLogMax = 1f / ln(1f + layerMax * brightness)

            val shiftedIdx = ((layer + (paletteShift * colors.size).toInt()) % colors.size + colors.size) % colors.size
            val baseColor = colors[shiftedIdx]
            val cr = baseColor shr 16 and 0xFF
            val cg = baseColor shr 8 and 0xFF
            val cb = baseColor and 0xFF

            for (j in 0 until bufSize) {
                val count = layerHist[j]
                if (count > 0) {
                    val intensity = (ln(1f + count * brightness) * invLLogMax).coerceIn(0f, 1f)
                    layerR[j] += (cr * intensity).toInt()
                    layerG[j] += (cg * intensity).toInt()
                    layerB[j] += (cb * intensity).toInt()
                }
            }
        }

        for (j in 0 until bufSize) {
            val r = layerR[j]; val g = layerG[j]; val b2 = layerB[j]
            pix[j] = if (r > 0 || g > 0 || b2 > 0)
                (0xFF shl 24) or (min(r, 255) shl 16) or (min(g, 255) shl 8) or min(b2, 255)
            else BG_COLOR
        }
    }

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float {
        val iterations = (params["iterations"] as? Number)?.toFloat() ?: 800f
        return when (quality) {
            Quality.DRAFT -> iterations * 0.2f / 2000f
            Quality.BALANCED -> iterations * 0.5f / 2000f
            Quality.ULTRA -> iterations / 2000f
        }.coerceIn(0.1f, 1f)
    }
}
