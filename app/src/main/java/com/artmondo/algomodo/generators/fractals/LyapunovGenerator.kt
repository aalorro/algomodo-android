package com.artmondo.algomodo.generators.fractals

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import com.artmondo.algomodo.data.palettes.Palette
import com.artmondo.algomodo.generators.Generator
import com.artmondo.algomodo.generators.ParamGroup
import com.artmondo.algomodo.generators.Parameter
import com.artmondo.algomodo.generators.Quality
import kotlin.math.*

class LyapunovGenerator : Generator {

    override val id = "fractal-lyapunov"
    override val family = "fractals"
    override val styleName = "Lyapunov Fractal"
    override val definition =
        "Stability map of alternating logistic maps — the Lyapunov exponent at each (a,b) point reveals striking organic stripe patterns at the boundary between order and chaos."
    override val algorithmNotes =
        "For each pixel mapped to (a,b) in logistic map parameter space, iterates x = r*x*(1-x) where r alternates between a and b according to a sequence string (e.g. \"AB\", \"AABB\"). " +
        "After warmup iterations, accumulates the Lyapunov exponent lambda = (1/N)*sum(log|r*(1-2x)|). " +
        "lambda < 0 indicates stable periodic orbits (coloured by palette), lambda > 0 indicates chaos (dark), and lambda ~ 0 marks the fractal boundary."
    override val supportsVector = false
    override val supportsAnimation = true

    companion object {
        private val SEQUENCES = mapOf(
            "AB"     to intArrayOf(0, 1),
            "AABB"   to intArrayOf(0, 0, 1, 1),
            "ABAB"   to intArrayOf(0, 1, 0, 1),
            "ABBA"   to intArrayOf(0, 1, 1, 0),
            "AABAB"  to intArrayOf(0, 0, 1, 0, 1),
            "ABBAAB" to intArrayOf(0, 1, 1, 0, 0, 1),
        )

        private val TARGETS = arrayOf(
            doubleArrayOf(3.2, 3.4),
            doubleArrayOf(3.0, 3.5),
            doubleArrayOf(2.8, 3.8),
            doubleArrayOf(3.4, 3.2),
            doubleArrayOf(2.6, 3.0),
        )

        private const val LAMBDA_MAX = 2.0
        private const val LUT_SIZE = 1024
        private const val LUT_MAX = LUT_SIZE - 1

        /** Fast ln() via IEEE 754 bit decomposition — ~3% max error, fine for visualization. */
        @JvmStatic
        private fun fastLn(x: Double): Double {
            val bits = java.lang.Double.doubleToRawLongBits(x)
            val exp = ((bits ushr 52) and 0x7FFL) - 1023L
            val mantissaBits = (bits and 0x000FFFFFFFFFFFFFL) or 0x3FF0000000000000L
            val m = java.lang.Double.longBitsToDouble(mantissaBits) - 1.0
            return (exp + m * (1.4426950408889634 - m * 0.7213475204444817)) * 0.6931471805599453
        }
    }

    override val parameterSchema: List<Parameter> = listOf(
        Parameter.SelectParam("Sequence", "sequence", ParamGroup.COMPOSITION,
            "Alternation pattern for logistic map parameters a and b",
            listOf("AB", "AABB", "ABAB", "ABBA", "AABAB", "ABBAAB"), "AB"),
        Parameter.NumberParam("Center A", "centerA", ParamGroup.COMPOSITION,
            "Center of the viewport along the A parameter axis",
            0f, 4f, 0.05f, 3.0f),
        Parameter.NumberParam("Center B", "centerB", ParamGroup.COMPOSITION,
            "Center of the viewport along the B parameter axis",
            0f, 4f, 0.05f, 3.0f),
        Parameter.NumberParam("Zoom", "zoom", ParamGroup.COMPOSITION,
            "Zoom into the fractal", 0.5f, 8f, 0.5f, 2f),
        Parameter.NumberParam("Warmup", "warmup", ParamGroup.GEOMETRY,
            "Transient iterations before measuring", 50f, 500f, 50f, 100f),
        Parameter.NumberParam("Iterations", "iterations", ParamGroup.GEOMETRY,
            "Measurement iterations for Lyapunov exponent", 50f, 500f, 50f, 200f),
        Parameter.SelectParam("Color Mode", "colorMode", ParamGroup.COLOR,
            "stability: palette for stable, dark for chaotic | magnitude: signed gradient | symmetric: palette for both",
            listOf("stability", "magnitude", "symmetric"), "symmetric"),
        Parameter.NumberParam("Color Cycles", "colorCycles", ParamGroup.COLOR,
            "How many times the palette repeats across the exponent range",
            1f, 8f, 1f, 2f),
        Parameter.NumberParam("Speed", "speed", ParamGroup.FLOW_MOTION,
            "Animation drift speed through parameter space",
            0.1f, 3.0f, 0.1f, 0.5f),
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "sequence" to "AB",
        "centerA" to 3.0f,
        "centerB" to 3.0f,
        "zoom" to 2f,
        "warmup" to 100f,
        "iterations" to 200f,
        "colorMode" to "symmetric",
        "colorCycles" to 2f,
        "speed" to 0.5f,
    )

    private val filterPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    // Reusable buffers — avoids per-frame allocation / GC pressure
    @Volatile private var renderBuf: IntArray? = null
    @Volatile private var smallBmp: Bitmap? = null
    private var smallW = 0
    private var smallH = 0

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
        val isAnim = time > 0f

        val seqKey = (params["sequence"] as? String) ?: "AB"
        val seq = SEQUENCES[seqKey] ?: SEQUENCES["AB"]!!
        val seqLen = seq.size
        val isAB = seqLen == 2 && seq[0] == 0 && seq[1] == 1

        var cA = (params["centerA"] as? Number)?.toDouble() ?: 3.0
        var cB = (params["centerB"] as? Number)?.toDouble() ?: 3.0
        var viewZoom = (params["zoom"] as? Number)?.toDouble() ?: 2.0
        val baseWarmup = max(30, (params["warmup"] as? Number)?.toInt() ?: 100)
        val baseIter = max(30, (params["iterations"] as? Number)?.toInt() ?: 200)
        val colorMode = (params["colorMode"] as? String) ?: "symmetric"
        val colorCycles = max(1, (params["colorCycles"] as? Number)?.toInt() ?: 2)
        val spd = (params["speed"] as? Number)?.toDouble() ?: 0.5

        // Quality scaling
        val warmup = if (isAnim) max(16, baseWarmup / 4)
            else if (quality == Quality.ULTRA) baseWarmup
            else max(16, baseWarmup / 2)
        val iterations = if (isAnim) max(16, baseIter / 4)
            else if (quality == Quality.DRAFT) max(16, baseIter / 2)
            else baseIter

        // Animation: drift through parameter space
        if (isAnim) {
            val target = TARGETS[abs(seed) % TARGETS.size]
            val drift = time * spd * 0.06
            cA += sin(drift) * 0.4 + (target[0] - cA) * sin(drift * 0.3) * 0.2
            cB += cos(drift * 0.8) * 0.4 + (target[1] - cB) * cos(drift * 0.25) * 0.2
            viewZoom *= (1.0 + sin(time * spd * 0.05) * 0.3)
        }

        // Viewport clamped to [0,4]x[0,4]
        val viewSize = min(4.0, 4.0 / viewZoom)
        var aMin = cA - viewSize * 0.5
        var aMax = aMin + viewSize
        var bMin = cB - viewSize * 0.5
        var bMax = bMin + viewSize
        if (aMin < 0) { aMax -= aMin; aMin = 0.0 }
        if (aMax > 4) { aMin -= (aMax - 4); aMax = 4.0 }
        if (bMin < 0) { bMax -= bMin; bMin = 0.0 }
        if (bMax > 4) { bMin -= (bMax - 4); bMax = 4.0 }
        aMin = max(0.0, aMin); aMax = min(4.0, aMax)
        bMin = max(0.0, bMin); bMax = min(4.0, bMax)

        // Animation: reduced resolution, upscaled. Static non-DRAFT: 1.5× SSAA for smooth edges.
        val renderW: Int
        val renderH: Int
        if (isAnim) {
            renderW = (w * 4 / 5).coerceAtLeast(w / 2)
            renderH = (h * 4 / 5).coerceAtLeast(h / 2)
        } else if (quality == Quality.DRAFT) {
            renderW = w; renderH = h
        } else {
            renderW = w * 3 / 2; renderH = h * 3 / 2
        }

        val pxSizeX = (aMax - aMin) / renderW
        val pxSizeY = (bMax - bMin) / renderH
        val startA = aMin
        val startB = bMax // B axis inverted (top = bMax)

        // Build color LUTs
        val stableLUT = IntArray(LUT_SIZE)
        val chaoticLUT = IntArray(LUT_SIZE)
        val lutMax = LUT_MAX
        for (i in 0 until LUT_SIZE) {
            val t = ((i.toFloat() / LUT_SIZE) * colorCycles) % 1f
            val baseColor = palette.lerpColor(t)
            stableLUT[i] = baseColor

            // Complementary: rotate RGB channels (R→B, G→R, B→G)
            val cr = baseColor and 0xFF           // blue channel
            val cg = (baseColor shr 16) and 0xFF  // red channel
            val cb = (baseColor shr 8) and 0xFF   // green channel
            chaoticLUT[i] = (0xFF shl 24) or (cr shl 16) or (cg shl 8) or cb
        }
        val black = (0xFF shl 24) // black with full alpha

        val invLambdaMax = 1.0 / LAMBDA_MAX
        val invIter = 1.0 / iterations
        val totalIter = warmup + iterations
        val earlyThreshold = LAMBDA_MAX * iterations * 1.5

        // Pre-tile sequence for generic path
        val seqTiled = if (!isAB) IntArray(totalIter) { seq[it % seqLen] } else null

        val totalPx = renderW * renderH
        var renderPixels = renderBuf
        if (renderPixels == null || renderPixels.size < totalPx) {
            renderPixels = IntArray(totalPx)
            renderBuf = renderPixels
        }

        val isStability = colorMode == "stability"
        val isMagnitude = colorMode == "magnitude"

        val cores = Runtime.getRuntime().availableProcessors().coerceIn(2, 8)
        val threads = Array(cores) { threadIdx ->
            Thread {
                val y0 = threadIdx * renderH / cores
                val y1 = (threadIdx + 1) * renderH / cores

                for (gy in y0 until y1) {
                    val bVal = startB - gy * pxSizeY
                    val rowOff = gy * renderW

                    for (gx in 0 until renderW) {
                        val a = startA + gx * pxSizeX
                        var xn = 0.5
                        val lambda: Double

                        if (isAB) {
                            // ═══ AB FAST PATH: loop unrolled by 2 ═══
                            var diverged = false
                            val wp = warmup shr 1
                            for (i in 0 until wp) {
                                xn = a * xn * (1.0 - xn)
                                xn = bVal * xn * (1.0 - xn)
                                if (i and 3 == 3 && (xn < -0.01 || xn > 1.01 || xn.isNaN())) {
                                    diverged = true; break
                                }
                            }
                            if (!diverged && warmup and 1 != 0) xn = a * xn * (1.0 - xn)

                            if (diverged) {
                                lambda = LAMBDA_MAX
                            } else {
                                var lyapSum = 0.0
                                val mp = iterations shr 1
                                var earlyOut = false
                                for (p in 0 until mp) {
                                    val rxn0 = a * xn
                                    xn = rxn0 * (1.0 - xn)
                                    val d0 = a - 2.0 * rxn0
                                    val ad0 = abs(d0)
                                    lyapSum += if (ad0 > 1e-12) fastLn(ad0) else -30.0

                                    val rxn1 = bVal * xn
                                    xn = rxn1 * (1.0 - xn)
                                    val d1 = bVal - 2.0 * rxn1
                                    val ad1 = abs(d1)
                                    lyapSum += if (ad1 > 1e-12) fastLn(ad1) else -30.0

                                    if (p and 7 == 7) {
                                        if (xn < -0.01 || xn > 1.01 || xn.isNaN()) {
                                            lyapSum += 30.0 * (iterations - (p + 1) * 2)
                                            earlyOut = true; break
                                        }
                                        if (lyapSum > earlyThreshold || lyapSum < -earlyThreshold) break
                                    }
                                }
                                if (!earlyOut && iterations and 1 != 0) {
                                    val rxn = a * xn
                                    xn = rxn * (1.0 - xn)
                                    val d = a - 2.0 * rxn
                                    val ad = abs(d)
                                    lyapSum += if (ad > 1e-12) fastLn(ad) else -30.0
                                }
                                lambda = lyapSum * invIter
                            }
                        } else {
                            // ═══ GENERIC PATH for non-AB sequences ═══
                            val st = seqTiled!!
                            var diverged = false
                            for (i in 0 until warmup) {
                                val r = if (st[i] == 0) a else bVal
                                xn = r * xn * (1.0 - xn)
                                if (i and 7 == 7 && (xn < -0.01 || xn > 1.01 || xn.isNaN())) {
                                    diverged = true; break
                                }
                            }

                            if (diverged) {
                                lambda = LAMBDA_MAX
                            } else {
                                var lyapSum = 0.0
                                for (i in warmup until totalIter) {
                                    val r = if (st[i] == 0) a else bVal
                                    val rxn = r * xn
                                    xn = rxn * (1.0 - xn)
                                    if (i and 7 == 7 && (xn < -0.01 || xn > 1.01 || xn.isNaN())) {
                                        lyapSum += 30.0 * (totalIter - i)
                                        break
                                    }
                                    val deriv = r - 2.0 * rxn
                                    val ad = abs(deriv)
                                    lyapSum += if (ad > 1e-12) fastLn(ad) else -30.0
                                    if (i and 15 == 15 && (lyapSum > earlyThreshold || lyapSum < -earlyThreshold)) break
                                }
                                lambda = lyapSum * invIter
                            }
                        }

                        // Color mapping via LUT
                        val px32: Int = if (isStability) {
                            if (lambda < 0.0) {
                                stableLUT[(min(1.0, -lambda * invLambdaMax) * lutMax).toInt().coerceIn(0, lutMax)]
                            } else {
                                black
                            }
                        } else if (isMagnitude) {
                            val t = min(1.0, max(0.0, (lambda * invLambdaMax + 1.0) * 0.5))
                            stableLUT[(t * lutMax).toInt().coerceIn(0, lutMax)]
                        } else {
                            // Symmetric
                            if (lambda < 0.0) {
                                stableLUT[(min(1.0, -lambda * invLambdaMax) * lutMax).toInt().coerceIn(0, lutMax)]
                            } else {
                                chaoticLUT[(min(1.0, lambda * invLambdaMax) * lutMax).toInt().coerceIn(0, lutMax)]
                            }
                        }

                        renderPixels[rowOff + gx] = px32
                    }
                }
            }.also { it.start() }
        }
        threads.forEach { it.join() }

        // Bilinear scale (up for animation, down for SSAA), or direct copy
        if (renderW != w || renderH != h) {
            var sb = smallBmp
            if (sb == null || smallW != renderW || smallH != renderH) {
                sb?.recycle()
                sb = Bitmap.createBitmap(renderW, renderH, Bitmap.Config.ARGB_8888)
                smallBmp = sb; smallW = renderW; smallH = renderH
            }
            sb.setPixels(renderPixels, 0, renderW, 0, 0, renderW, renderH)
            canvas.drawBitmap(sb, null, Rect(0, 0, w, h), filterPaint)
        } else {
            bitmap.setPixels(renderPixels, 0, w, 0, 0, w, h)
            canvas.drawBitmap(bitmap, 0f, 0f, null)
        }
    }

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float {
        val warmup = (params["warmup"] as? Number)?.toInt() ?: 100
        val iterations = (params["iterations"] as? Number)?.toInt() ?: 200
        return ((warmup + iterations) * 0.5f / 350f).coerceIn(0.2f, 1f)
    }
}
