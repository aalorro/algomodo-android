package com.artmondo.algomodo.generators.animation

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
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

    // Attractor type constants — avoids string comparison in hot loop
    private companion object {
        const val TYPE_CLIFFORD = 0
        const val TYPE_DEJONG = 1
        const val TYPE_BEDHEAD = 2
        const val TYPE_SVENSSON = 3
        const val TYPE_TINKERBELL = 4
        const val PALETTE_LUT_SIZE = 256
        const val BG_COLOR = 0xFF040408.toInt()
    }

    // Reusable buffers — avoids allocating large arrays every frame
    private var histogram: IntArray? = null
    private var pixels: IntArray? = null
    private var auxFloat: FloatArray? = null
    private var lastBufferSize = 0

    private fun ensureBuffers(size: Int, needAux: Boolean) {
        if (size != lastBufferSize) {
            histogram = IntArray(size)
            pixels = IntArray(size)
            auxFloat = if (needAux) FloatArray(size) else null
            lastBufferSize = size
        } else {
            histogram!!.fill(0)
            pixels!!.fill(0)
            if (needAux) {
                if (auxFloat == null || auxFloat!!.size != size) auxFloat = FloatArray(size)
                else auxFloat!!.fill(0f)
            }
        }
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

    /** Build a 256-entry palette lookup table to avoid per-pixel lerpColor calls. */
    private fun buildPaletteLut(palette: Palette, shift: Float): IntArray {
        val lut = IntArray(PALETTE_LUT_SIZE)
        for (i in 0 until PALETTE_LUT_SIZE) {
            val t = ((i.toFloat() / (PALETTE_LUT_SIZE - 1) + shift) % 1f + 1f) % 1f
            lut[i] = palette.lerpColor(t)
        }
        return lut
    }

    /**
     * Inline attractor iteration — returns new x,y via the passed FloatArray to avoid
     * Pair allocation in the hot loop. out[0] = nx, out[1] = ny.
     */
    private inline fun iterate(
        type: Int, x: Float, y: Float,
        a: Float, b: Float, c: Float, d: Float,
        out: FloatArray
    ) {
        when (type) {
            TYPE_CLIFFORD -> {
                out[0] = sin(a * y) + c * cos(a * x)
                out[1] = sin(b * x) + d * cos(b * y)
            }
            TYPE_DEJONG -> {
                out[0] = sin(a * y) - cos(b * x)
                out[1] = sin(c * x) - cos(d * y)
            }
            TYPE_BEDHEAD -> {
                out[0] = sin(x * y / b) + cos(a * x - y)
                out[1] = x + sin(y) / b
            }
            TYPE_SVENSSON -> {
                out[0] = d * sin(a * x) - sin(b * y)
                out[1] = c * cos(a * x) + cos(b * y)
            }
            TYPE_TINKERBELL -> {
                out[0] = x * x - y * y + a * x + b * y
                out[1] = 2f * x * y + c * x + d * y
            }
            else -> {
                out[0] = sin(a * y) + c * cos(a * x)
                out[1] = sin(b * x) + d * cos(b * y)
            }
        }
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
        val dim = min(w, h)
        val bufSize = w * h

        val type = typeId((params["attractorType"] as? String) ?: "clifford")
        val iterationsK = ((params["iterations"] as? Number)?.toInt() ?: 800).let {
            when (quality) {
                Quality.DRAFT -> (it * 0.3f).toInt()
                Quality.BALANCED -> (it * 0.7f).toInt()
                Quality.ULTRA -> it
            }
        }
        val totalIterations = iterationsK * 1000
        val brightness = (params["brightness"] as? Number)?.toFloat() ?: 1.5f
        val colorMode = (params["colorMode"] as? String) ?: "density"
        val colorShift = (params["colorShift"] as? Number)?.toFloat() ?: 0f
        val pointRadius = (params["pointSize"] as? Number)?.toFloat()?.let { (it - 1f) / 2f } ?: 0f
        val driftSpeed = (params["driftSpeed"] as? Number)?.toFloat() ?: 0.2f
        val driftAmp = (params["driftAmp"] as? Number)?.toFloat() ?: 0.15f

        val rng = SeededRNG(seed)

        val bp = baseParams(type, rng)
        val phases = FloatArray(4) { rng.random().toFloat() * (2f * PI.toFloat()) }
        val freqs = FloatArray(4) { 0.3f + rng.random().toFloat() * 0.7f }

        val tp = time * driftSpeed
        val a = bp[0] + driftAmp * sin(tp * freqs[0] * 2f * PI.toFloat() + phases[0])
        val b = bp[1] + driftAmp * sin(tp * freqs[1] * 2f * PI.toFloat() + phases[1])
        val c = bp[2] + driftAmp * sin(tp * freqs[2] * 2f * PI.toFloat() + phases[2])
        val d = bp[3] + driftAmp * sin(tp * freqs[3] * 2f * PI.toFloat() + phases[3])

        val range = coordRange(type)
        val cx = w * 0.5f
        val cy = h * 0.5f
        val scale = dim / (2f * range)

        val needAux = colorMode == "angle" || colorMode == "velocity"
        ensureBuffers(bufSize, needAux)
        val hist = histogram!!
        val pix = pixels!!
        val aux = auxFloat

        // Reusable output array for inline iteration — zero allocation
        val out = FloatArray(2)

        // Splat precomputation
        val splatOffsets: IntArray?
        val splatCount: Int
        if (pointRadius > 0f) {
            val ir = ceil(pointRadius).toInt()
            val r2 = pointRadius * pointRadius
            val offsets = mutableListOf<Int>()
            for (dy in -ir..ir) {
                for (dx in -ir..ir) {
                    if (dx == 0 && dy == 0) continue
                    if (dx * dx + dy * dy <= r2) {
                        offsets.add(dx)
                        offsets.add(dy)
                    }
                }
            }
            splatOffsets = offsets.toIntArray()
            splatCount = offsets.size / 2
        } else {
            splatOffsets = null
            splatCount = 0
        }

        // -- Main iteration loop (hot path) --
        var x = 0.5f
        var y = 0.5f

        // Warmup
        for (i in 0 until 200) {
            iterate(type, x, y, a, b, c, d, out)
            x = out[0]; y = out[1]
            if (x.isNaN() || x.isInfinite()) { x = 0.1f; y = 0.1f }
        }

        if (colorMode == "angle") {
            // Angle mode — track movement direction
            var prevX = x; var prevY = y
            for (i in 0 until totalIterations) {
                iterate(type, x, y, a, b, c, d, out)
                val nx = out[0]; val ny = out[1]
                if (nx.isNaN() || nx.isInfinite() || ny.isNaN() || ny.isInfinite()) {
                    x = 0.1f; y = 0.1f; prevX = x; prevY = y; continue
                }
                prevX = x; prevY = y; x = nx; y = ny
                val px = (cx + x * scale).toInt()
                val py = (cy + y * scale).toInt()
                if (px in 0 until w && py in 0 until h) {
                    val idx = py * w + px
                    hist[idx]++
                    aux!![idx] = atan2(y - prevY, x - prevX)
                    if (splatOffsets != null) {
                        var si = 0
                        while (si < splatCount) {
                            val sx = px + splatOffsets[si * 2]
                            val sy = py + splatOffsets[si * 2 + 1]
                            if (sx in 0 until w && sy in 0 until h) hist[sy * w + sx]++
                            si++
                        }
                    }
                }
            }
        } else if (colorMode == "velocity") {
            // Velocity mode — track speed
            var prevX = x; var prevY = y
            for (i in 0 until totalIterations) {
                iterate(type, x, y, a, b, c, d, out)
                val nx = out[0]; val ny = out[1]
                if (nx.isNaN() || nx.isInfinite() || ny.isNaN() || ny.isInfinite()) {
                    x = 0.1f; y = 0.1f; prevX = x; prevY = y; continue
                }
                prevX = x; prevY = y; x = nx; y = ny
                val px = (cx + x * scale).toInt()
                val py = (cy + y * scale).toInt()
                if (px in 0 until w && py in 0 until h) {
                    val idx = py * w + px
                    hist[idx]++
                    val vx = x - prevX; val vy = y - prevY
                    aux!![idx] = sqrt(vx * vx + vy * vy)
                    if (splatOffsets != null) {
                        var si = 0
                        while (si < splatCount) {
                            val sx = px + splatOffsets[si * 2]
                            val sy = py + splatOffsets[si * 2 + 1]
                            if (sx in 0 until w && sy in 0 until h) hist[sy * w + sx]++
                            si++
                        }
                    }
                }
            }
        } else if (colorMode != "multi") {
            // Density mode — fastest path, no aux tracking
            for (i in 0 until totalIterations) {
                iterate(type, x, y, a, b, c, d, out)
                val nx = out[0]; val ny = out[1]
                if (nx.isNaN() || nx.isInfinite() || ny.isNaN() || ny.isInfinite()) {
                    x = 0.1f; y = 0.1f; continue
                }
                x = nx; y = ny
                val px = (cx + x * scale).toInt()
                val py = (cy + y * scale).toInt()
                if (px in 0 until w && py in 0 until h) {
                    val idx = py * w + px
                    hist[idx]++
                    if (splatOffsets != null) {
                        var si = 0
                        while (si < splatCount) {
                            val sx = px + splatOffsets[si * 2]
                            val sy = py + splatOffsets[si * 2 + 1]
                            if (sx in 0 until w && sy in 0 until h) hist[sy * w + sx]++
                            si++
                        }
                    }
                }
            }
        }

        // -- Tone mapping --
        val paletteShift = colorShift + time * colorShift * 0.5f

        if (colorMode == "multi") {
            renderMulti(type, a, b, c, d, cx, cy, scale, w, h, totalIterations,
                brightness, paletteShift, palette, pix, out)
        } else {
            // Build palette LUT + log LUT
            val palLut = buildPaletteLut(palette, 0f)

            var maxCount = 1
            for (v in hist) if (v > maxCount) maxCount = v
            val logMax = ln(1f + maxCount * brightness)
            val invLogMax = 1f / logMax

            val twoPiInv = 1f / (2f * PI.toFloat())
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
                    val baseColor = palLut[lutIdx]
                    val r = ((baseColor shr 16 and 0xFF) * intensity).toInt()
                    val g = ((baseColor shr 8 and 0xFF) * intensity).toInt()
                    val b2 = ((baseColor and 0xFF) * intensity).toInt()
                    pix[j] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b2
                } else {
                    pix[j] = BG_COLOR
                }
            }
        }

        bitmap.setPixels(pix, 0, w, 0, 0, w, h)
        canvas.drawBitmap(bitmap, 0f, 0f, null)
    }

    /** Multi-layer rendering — each layer uses a different palette color. */
    private fun renderMulti(
        type: Int, a: Float, b: Float, c: Float, d: Float,
        cx: Float, cy: Float, scale: Float, w: Int, h: Int,
        totalIterations: Int, brightness: Float, paletteShift: Float,
        palette: Palette, pix: IntArray, out: FloatArray
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
                iterate(type, lx, ly, la, lb, c, d, out)
                lx = out[0]; ly = out[1]
                if (lx.isNaN() || lx.isInfinite()) { lx = 0.1f; ly = 0.1f }
            }

            val layerIter = totalIterations / layerCount
            for (i in 0 until layerIter) {
                iterate(type, lx, ly, la, lb, c, d, out)
                val nx = out[0]; val ny = out[1]
                if (nx.isNaN() || nx.isInfinite() || ny.isNaN() || ny.isInfinite()) {
                    lx = 0.1f; ly = 0.1f; continue
                }
                lx = nx; ly = ny
                val lpx = (cx + lx * scale).toInt()
                val lpy = (cy + ly * scale).toInt()
                if (lpx in 0 until w && lpy in 0 until h) {
                    layerHist[lpy * w + lpx]++
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
            Quality.DRAFT -> iterations * 0.3f / 2000f
            Quality.BALANCED -> iterations * 0.7f / 2000f
            Quality.ULTRA -> iterations / 2000f
        }.coerceIn(0.1f, 1f)
    }
}
