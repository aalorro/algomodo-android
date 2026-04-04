package com.artmondo.algomodo.generators.fractals

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

class StrangeAttractorDensityGenerator : Generator {

    override val id = "fractal-strange-attractor"
    override val family = "fractals"
    override val styleName = "Strange Attractor Density"
    override val definition =
        "Density plot of strange attractors — chaotic iterated maps rendered as histograms with log tone mapping."
    override val algorithmNotes =
        "Iterates a 2D chaotic map (Clifford, De Jong, Svensson, or Bedhead) millions of times, " +
        "accumulating point visits into a histogram. The density is rendered with logarithmic tone " +
        "mapping and gamma correction, revealing the intricate fractal structure of the attractor. " +
        "Parameters a, b, c, d are generated from the seed for deterministic variety."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema: List<Parameter> = listOf(
        Parameter.SelectParam(
            name = "Attractor",
            key = "attractor",
            group = ParamGroup.COMPOSITION,
            help = "Type of 2D strange attractor to render",
            options = listOf("clifford", "dejong", "svensson", "bedhead"),
            default = "clifford"
        ),
        Parameter.NumberParam("Iterations", "iterations", ParamGroup.COMPOSITION, "Total sample points — more = smoother rendering", 100000f, 1000000f, 100000f, 500000f),
        Parameter.NumberParam("Gamma", "gamma", ParamGroup.COLOR, "Tone-mapping gamma — lower = brighter midtones", 1f, 5f, 0.5f, 2.5f),
        Parameter.SelectParam(
            name = "Color Mode",
            key = "colorMode",
            group = ParamGroup.COLOR,
            help = "density: by point count | velocity: by step size | angle: by trajectory direction",
            options = listOf("density", "velocity", "angle"),
            default = "density"
        ),
        Parameter.NumberParam("Speed", "speed", ParamGroup.FLOW_MOTION, "Animation speed — smoothly morphs attractor parameters", 0.1f, 3.0f, 0.1f, 0.5f)
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "attractor" to "clifford",
        "iterations" to 500000f,
        "gamma" to 2.5f,
        "colorMode" to "density",
        "speed" to 0.5f
    )

    private companion object {
        const val ATT_CLIFFORD = 0
        const val ATT_DEJONG = 1
        const val ATT_SVENSSON = 2
        const val ATT_BEDHEAD = 3

        // Trig lookup tables — replaces sin/cos calls (~10x faster)
        const val LUT_SIZE = 4096
        const val LUT_MASK = LUT_SIZE - 1
        val INV_2PI_LUT = LUT_SIZE / (2f * PI.toFloat())
        val SIN_LUT = FloatArray(LUT_SIZE) { sin(it * 2.0 * PI / LUT_SIZE).toFloat() }
        val COS_LUT = FloatArray(LUT_SIZE) { cos(it * 2.0 * PI / LUT_SIZE).toFloat() }
    }

    // Reusable arrays — eliminates per-frame GC pressure
    @Volatile private var reuseHistogram: IntArray? = null
    @Volatile private var reuseColorAcc: FloatArray? = null
    @Volatile private var reusePixels: IntArray? = null

    private fun fSin(x: Float): Float = SIN_LUT[(x * INV_2PI_LUT).toInt() and LUT_MASK]
    private fun fCos(x: Float): Float = COS_LUT[(x * INV_2PI_LUT).toInt() and LUT_MASK]

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
        val attractor = (params["attractor"] as? String) ?: "clifford"
        val iterations = (params["iterations"] as? Number)?.toInt() ?: 500000
        val gamma = (params["gamma"] as? Number)?.toFloat() ?: 2.5f
        val colorMode = (params["colorMode"] as? String) ?: "density"
        val speed = (params["speed"] as? Number)?.toFloat() ?: 0.5f

        val isAnim = time > 0f

        val scaledIterations = when {
            isAnim && quality == Quality.DRAFT -> iterations / 5
            isAnim -> iterations * 2 / 5
            quality == Quality.DRAFT -> iterations / 3
            quality == Quality.ULTRA -> iterations * 2
            else -> iterations
        }

        // Adaptive resolution during animation
        val renderW = if (isAnim) (w * 3 / 5).coerceAtLeast(w / 2) else w
        val renderH = if (isAnim) (h * 3 / 5).coerceAtLeast(h / 2) else h
        val histSz = renderW * renderH

        val rng = SeededRNG(seed)
        val anim = time * speed * 0.1f

        val a = rng.range(-2.5f, 2.5f) + sin(anim * 0.7f) * 0.15f
        val b = rng.range(-2.5f, 2.5f) + cos(anim * 0.9f) * 0.15f
        val c = rng.range(-2.5f, 2.5f) + sin(anim * 1.1f) * 0.15f
        val d = rng.range(-2.5f, 2.5f) + cos(anim * 1.3f) * 0.15f

        val attIdx = when (attractor) {
            "clifford" -> ATT_CLIFFORD; "dejong" -> ATT_DEJONG
            "svensson" -> ATT_SVENSSON; "bedhead" -> ATT_BEDHEAD
            else -> ATT_CLIFFORD
        }
        val bSafe = if (attIdx == ATT_BEDHEAD && abs(b) < 0.01f) 0.01f else b
        val trackExtra = colorMode == "velocity" || colorMode == "angle"

        // Reuse histogram array
        val histogram: IntArray
        val rh = reuseHistogram
        if (rh != null && rh.size >= histSz) {
            histogram = rh
            java.util.Arrays.fill(histogram, 0, histSz, 0)
        } else {
            histogram = IntArray(histSz)
            reuseHistogram = histogram
        }

        val colorAcc: FloatArray?
        if (trackExtra) {
            val rc = reuseColorAcc
            if (rc != null && rc.size >= histSz) {
                colorAcc = rc
                java.util.Arrays.fill(colorAcc, 0, histSz, 0f)
            } else {
                colorAcc = FloatArray(histSz)
                reuseColorAcc = colorAcc
            }
        } else {
            colorAcc = null
        }

        // ---- Phase 1: Bounds estimation (quick pass, LUT trig) ----
        val warmup = 100
        val boundsIter = 5000.coerceAtMost(scaledIterations / 5)
        var bx = 0.1f; var by = 0.1f
        var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE

        for (i in 0 until warmup + boundsIter) {
            val nx: Float; val ny: Float
            when (attIdx) {
                ATT_CLIFFORD -> {
                    nx = fSin(a * by) + c * fCos(a * bx)
                    ny = fSin(b * bx) + d * fCos(b * by)
                }
                ATT_DEJONG -> {
                    nx = fSin(a * by) - fCos(b * bx)
                    ny = fSin(c * bx) - fCos(d * by)
                }
                ATT_SVENSSON -> {
                    nx = d * fSin(a * bx) - fSin(b * by)
                    ny = c * fCos(a * bx) + fCos(b * by)
                }
                else -> {
                    nx = fSin(bx * by / bSafe) * by + fCos(a * bx - by)
                    ny = bx + fSin(by) / bSafe
                }
            }
            if (nx.isFinite() && ny.isFinite() && abs(nx) < 1e6f && abs(ny) < 1e6f) {
                if (i >= warmup) {
                    if (nx < minX) minX = nx; if (nx > maxX) maxX = nx
                    if (ny < minY) minY = ny; if (ny > maxY) maxY = ny
                }
                bx = nx; by = ny
            } else {
                bx = 0.1f; by = 0.1f
            }
        }

        val padX = (maxX - minX) * 0.1f
        val padY = (maxY - minY) * 0.1f
        minX -= padX; maxX += padX; minY -= padY; maxY += padY

        if (!minX.isFinite() || !maxX.isFinite() || maxX <= minX || maxY <= minY) {
            bitmap.setPixels(IntArray(w * h), 0, w, 0, 0, w, h)
            canvas.drawBitmap(bitmap, 0f, 0f, null)
            return
        }

        val invRangeX = (renderW - 1f) / (maxX - minX)
        val invRangeY = (renderH - 1f) / (maxY - minY)
        val rHm1 = renderH - 1

        // ---- Phase 2: Multi-threaded histogram accumulation ----
        // Attractor is ergodic — parallel trajectories from different starts converge.
        // Shared histogram with benign races (occasional lost increment is invisible).
        val mainIter = (scaledIterations - warmup - boundsIter).coerceAtLeast(0)
        val cores = Runtime.getRuntime().availableProcessors().coerceIn(2, 8)
        val perThread = mainIter / cores
        val SL = SIN_LUT
        val CL = COS_LUT
        val INV = INV_2PI_LUT
        val MASK = LUT_MASK

        val threads = Array(cores) { t ->
            Thread {
                var tx = 0.1f + t * 0.017f
                var ty = 0.1f + t * 0.013f

                // Per-thread warmup onto the attractor
                for (i in 0 until warmup) {
                    val nx: Float; val ny: Float
                    when (attIdx) {
                        ATT_CLIFFORD -> { nx = fSin(a * ty) + c * fCos(a * tx); ny = fSin(b * tx) + d * fCos(b * ty) }
                        ATT_DEJONG -> { nx = fSin(a * ty) - fCos(b * tx); ny = fSin(c * tx) - fCos(d * ty) }
                        ATT_SVENSSON -> { nx = d * fSin(a * tx) - fSin(b * ty); ny = c * fCos(a * tx) + fCos(b * ty) }
                        else -> { nx = fSin(tx * ty / bSafe) * ty + fCos(a * tx - ty); ny = tx + fSin(ty) / bSafe }
                    }
                    if (nx.isFinite() && ny.isFinite() && abs(nx) < 1e6f && abs(ny) < 1e6f) {
                        tx = nx; ty = ny
                    } else { tx = 0.1f; ty = 0.1f }
                }

                // Main histogram loop — attractor-specific inlined paths avoid per-iteration branching
                if (!trackExtra) {
                    // DENSITY MODE: fully inlined LUT, no tracking overhead
                    when (attIdx) {
                        ATT_CLIFFORD -> {
                            for (i in 0 until perThread) {
                                val nx = SL[(a * ty * INV).toInt() and MASK] + c * CL[(a * tx * INV).toInt() and MASK]
                                val ny = SL[(b * tx * INV).toInt() and MASK] + d * CL[(b * ty * INV).toInt() and MASK]
                                tx = nx; ty = ny
                                val px = ((nx - minX) * invRangeX).toInt()
                                val py = rHm1 - ((ny - minY) * invRangeY).toInt()
                                if (px in 0 until renderW && py in 0 until renderH) histogram[py * renderW + px]++
                            }
                        }
                        ATT_DEJONG -> {
                            for (i in 0 until perThread) {
                                val nx = SL[(a * ty * INV).toInt() and MASK] - CL[(b * tx * INV).toInt() and MASK]
                                val ny = SL[(c * tx * INV).toInt() and MASK] - CL[(d * ty * INV).toInt() and MASK]
                                tx = nx; ty = ny
                                val px = ((nx - minX) * invRangeX).toInt()
                                val py = rHm1 - ((ny - minY) * invRangeY).toInt()
                                if (px in 0 until renderW && py in 0 until renderH) histogram[py * renderW + px]++
                            }
                        }
                        ATT_SVENSSON -> {
                            for (i in 0 until perThread) {
                                val nx = d * SL[(a * tx * INV).toInt() and MASK] - SL[(b * ty * INV).toInt() and MASK]
                                val ny = c * CL[(a * tx * INV).toInt() and MASK] + CL[(b * ty * INV).toInt() and MASK]
                                tx = nx; ty = ny
                                val px = ((nx - minX) * invRangeX).toInt()
                                val py = rHm1 - ((ny - minY) * invRangeY).toInt()
                                if (px in 0 until renderW && py in 0 until renderH) histogram[py * renderW + px]++
                            }
                        }
                        else -> {
                            // Bedhead — can diverge, needs validity check
                            for (i in 0 until perThread) {
                                val nx = SL[(tx * ty / bSafe * INV).toInt() and MASK] * ty + CL[((a * tx - ty) * INV).toInt() and MASK]
                                val ny = tx + SL[(ty * INV).toInt() and MASK] / bSafe
                                if (nx.isFinite() && ny.isFinite() && abs(nx) < 1e6f && abs(ny) < 1e6f) {
                                    tx = nx; ty = ny
                                    val px = ((nx - minX) * invRangeX).toInt()
                                    val py = rHm1 - ((ny - minY) * invRangeY).toInt()
                                    if (px in 0 until renderW && py in 0 until renderH) histogram[py * renderW + px]++
                                } else { tx = 0.1f + t * 0.017f; ty = 0.1f + t * 0.013f }
                            }
                        }
                    }
                } else {
                    // VELOCITY / ANGLE MODE — generic loop with tracking
                    for (i in 0 until perThread) {
                        val nx: Float; val ny: Float
                        when (attIdx) {
                            ATT_CLIFFORD -> { nx = fSin(a * ty) + c * fCos(a * tx); ny = fSin(b * tx) + d * fCos(b * ty) }
                            ATT_DEJONG -> { nx = fSin(a * ty) - fCos(b * tx); ny = fSin(c * tx) - fCos(d * ty) }
                            ATT_SVENSSON -> { nx = d * fSin(a * tx) - fSin(b * ty); ny = c * fCos(a * tx) + fCos(b * ty) }
                            else -> { nx = fSin(tx * ty / bSafe) * ty + fCos(a * tx - ty); ny = tx + fSin(ty) / bSafe }
                        }
                        if (nx.isFinite() && ny.isFinite() && abs(nx) < 1e6f && abs(ny) < 1e6f) {
                            val dx = nx - tx; val dy = ny - ty
                            tx = nx; ty = ny
                            val px = ((nx - minX) * invRangeX).toInt()
                            val py = rHm1 - ((ny - minY) * invRangeY).toInt()
                            if (px in 0 until renderW && py in 0 until renderH) {
                                val idx = py * renderW + px
                                histogram[idx]++
                                when (colorMode) {
                                    "velocity" -> colorAcc!![idx] += sqrt(dx * dx + dy * dy)
                                    "angle" -> colorAcc!![idx] += ((atan2(dy, dx) / PI.toFloat()) + 1f) * 0.5f
                                }
                            }
                        } else { tx = 0.1f + t * 0.017f; ty = 0.1f + t * 0.013f }
                    }
                }
            }.also { it.start() }
        }
        threads.forEach { it.join() }

        // ---- Phase 3: Tone mapping with density-to-alpha LUT ----
        var maxDensity = 1
        for (dd in histogram) if (dd > maxDensity) maxDensity = dd
        val logMax = ln(maxDensity.toFloat() + 1f)
        val invGamma = 1f / gamma

        // Pre-compute density → alpha LUT (avoids per-pixel ln + pow)
        val toneLutSize = (maxDensity + 1).coerceAtMost(4096)
        val toneStep = if (maxDensity >= toneLutSize) maxDensity.toFloat() / (toneLutSize - 1) else 1f
        val toneLut = FloatArray(toneLutSize) { i ->
            val density = if (maxDensity >= toneLutSize) (i * toneStep).toInt() else i
            (ln(density.toFloat() + 1f) / logMax).pow(invGamma)
        }

        val lutSize = 256
        val paletteLut = IntArray(lutSize) { palette.lerpColor(it.toFloat() / (lutSize - 1)) }

        // Reuse pixel array
        val renderPixels: IntArray
        val rp = reusePixels
        if (rp != null && rp.size >= histSz) {
            renderPixels = rp
        } else {
            renderPixels = IntArray(histSz)
            reusePixels = renderPixels
        }

        for (i in 0 until histSz) {
            val density = histogram[i]
            if (density > 0) {
                // Density → alpha via LUT
                val toneIdx = if (maxDensity >= toneLutSize) (density / toneStep).toInt().coerceIn(0, toneLutSize - 1) else density.coerceAtMost(toneLutSize - 1)
                val alpha = toneLut[toneIdx]

                val t = when (colorMode) {
                    "velocity" -> (colorAcc!![i] / density * 2f).coerceIn(0f, 1f)
                    "angle" -> (colorAcc!![i] / density).coerceIn(0f, 1f)
                    else -> alpha
                }

                val palIdx = (t * (lutSize - 1)).toInt().coerceIn(0, lutSize - 1)
                val baseColor = paletteLut[palIdx]
                val a255 = (alpha * 255f).toInt()
                val r = ((baseColor shr 16 and 0xFF) * a255 + 128) shr 8
                val g = ((baseColor shr 8 and 0xFF) * a255 + 128) shr 8
                val bv = ((baseColor and 0xFF) * a255 + 128) shr 8
                renderPixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or bv
            } else {
                renderPixels[i] = 0xFF000000.toInt()
            }
        }

        // Upscale to full bitmap if rendered at reduced resolution
        if (renderW < w) {
            val pixels = IntArray(w * h)
            val xMap = IntArray(w) { (it * renderW / w).coerceAtMost(renderW - 1) }
            for (py in 0 until h) {
                val sy = (py * renderH / h).coerceAtMost(renderH - 1)
                val srcOff = sy * renderW
                val dstOff = py * w
                for (px in 0 until w) {
                    pixels[dstOff + px] = renderPixels[srcOff + xMap[px]]
                }
            }
            bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
        } else {
            bitmap.setPixels(renderPixels, 0, w, 0, 0, w, h)
        }
        canvas.drawBitmap(bitmap, 0f, 0f, null)
    }

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float {
        val iterations = (params["iterations"] as? Number)?.toInt() ?: 500000
        return (iterations / 1000000f).coerceIn(0.1f, 1f)
    }
}
