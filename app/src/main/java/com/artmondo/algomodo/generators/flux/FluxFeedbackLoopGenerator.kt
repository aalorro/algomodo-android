package com.artmondo.algomodo.generators.flux

import android.graphics.*
import com.artmondo.algomodo.audio.AudioAnalysis
import com.artmondo.algomodo.core.rng.SeededRNG
import com.artmondo.algomodo.data.palettes.Palette
import com.artmondo.algomodo.generators.Generator
import com.artmondo.algomodo.generators.ParamGroup
import com.artmondo.algomodo.generators.Parameter
import com.artmondo.algomodo.generators.Quality
import kotlin.math.*

/**
 * Analog video feedback -- multiple seed emitters on Lissajous/spiral/figure-8
 * orbits, with oscillating scale+rotation that breathes and swirls.
 *
 * Dual-buffer feedback: each frame reads from the previous buffer (drawn
 * scaled/rotated via Matrix for the feedback transform), overlays new seed
 * shapes, then swaps. Oscillating feedback scale and rotation create
 * breathing/swirling dynamics. Glow halos via oversized alpha shapes.
 * Initial burst with concentric rings and radial rays, plus 12 warmup
 * iterations to develop structure before the first visible frame.
 */
class FluxFeedbackLoopGenerator : Generator {

    override val id = "flux-feedback-loop"
    override val family = "flux"
    override val styleName = "Feedback Loop"
    override val definition =
        "Analog video feedback -- multiple seed emitters on Lissajous/spiral/figure-8 " +
        "orbits, with oscillating scale+rotation that breathes and swirls"
    override val algorithmNotes =
        "Dual Bitmap buffer swap with alternating read/write (no redundant copy). " +
        "Oscillating feedback scale and rotation create breathing/swirling dynamics. " +
        "Multiple emitters on parametric orbits (Lissajous, spiral, figure-8) with " +
        "per-emitter seeded frequencies. Hue shift via ColorMatrix rotation. " +
        "Glow halos via oversized alpha shapes. Bloom pass via additive self-composite."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema = listOf(
        Parameter.SelectParam(
            name = "Seed Shape",
            key = "seedShape",
            group = ParamGroup.COMPOSITION,
            help = null,
            options = listOf("circle", "square", "triangle", "cross", "ring", "star"),
            default = "circle"
        ),
        Parameter.NumberParam(
            name = "Emitters",
            key = "emitters",
            group = ParamGroup.COMPOSITION,
            help = "Number of seed emitters -- each follows its own orbit path",
            min = 1f, max = 5f, step = 1f, default = 3f
        ),
        Parameter.SelectParam(
            name = "Orbit",
            key = "orbitPattern",
            group = ParamGroup.COMPOSITION,
            help = "lissajous: complex curves | circular: simple rings | spiral: expanding | figure8: infinity loop",
            options = listOf("lissajous", "circular", "spiral", "figure8"),
            default = "lissajous"
        ),
        Parameter.NumberParam(
            name = "Iterations",
            key = "iterations",
            group = ParamGroup.COMPOSITION,
            help = "Feedback passes per frame -- more passes produce denser, faster-evolving trails",
            min = 1f, max = 5f, step = 1f, default = 3f
        ),
        Parameter.NumberParam(
            name = "Feedback Scale",
            key = "feedbackScale",
            group = ParamGroup.GEOMETRY,
            help = null,
            min = 0.9f, max = 1.1f, step = 0.005f, default = 1.02f
        ),
        Parameter.NumberParam(
            name = "Feedback Rotation",
            key = "feedbackRotation",
            group = ParamGroup.GEOMETRY,
            help = null,
            min = -15f, max = 15f, step = 0.5f, default = 2.0f
        ),
        Parameter.NumberParam(
            name = "Seed Size",
            key = "seedSize",
            group = ParamGroup.GEOMETRY,
            help = null,
            min = 0.02f, max = 0.3f, step = 0.01f, default = 0.08f
        ),
        Parameter.NumberParam(
            name = "Feedback Decay",
            key = "feedbackDecay",
            group = ParamGroup.TEXTURE,
            help = null,
            min = 0.85f, max = 0.99f, step = 0.01f, default = 0.95f
        ),
        Parameter.NumberParam(
            name = "Hue Shift",
            key = "hueShift",
            group = ParamGroup.COLOR,
            help = null,
            min = 0f, max = 30f, step = 1f, default = 5f
        ),
        Parameter.NumberParam(
            name = "Speed",
            key = "speed",
            group = ParamGroup.FLOW_MOTION,
            help = null,
            min = 0.1f, max = 3f, step = 0.05f, default = 0.5f
        ),
        Parameter.NumberParam(
            name = "Audio Reactivity",
            key = "reactivity",
            group = ParamGroup.FLOW_MOTION,
            help = "Sensitivity to audio input (0 = none)",
            min = 0f, max = 2f, step = 0.1f, default = 1.0f
        )
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "seedShape" to "circle",
        "emitters" to 3f,
        "orbitPattern" to "lissajous",
        "iterations" to 3f,
        "feedbackScale" to 1.02f,
        "feedbackRotation" to 2.0f,
        "seedSize" to 0.08f,
        "feedbackDecay" to 0.95f,
        "hueShift" to 5f,
        "speed" to 0.5f,
        "reactivity" to 1.0f
    )

    // ── Stateful feedback buffers ───────────────────────────────────────────

    private data class FeedbackState(
        val seed: Int,
        val w: Int,
        val h: Int,
        val bufferA: Bitmap,
        val bufferB: Bitmap,
        var useA: Boolean,             // which buffer is "current" (latest result)
        val emitterFreqA: FloatArray,
        val emitterFreqB: FloatArray,
        val emitterPhase: FloatArray,
        val emitterOrbitR: FloatArray,
        var initialized: Boolean       // warmup done?
    )

    @Volatile private var state: FeedbackState? = null

    // Reusable paints
    private val feedbackPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val decayPaint = Paint()
    private val shapePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val bloomPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val matrix = Matrix()
    private val shapePath = Path()

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
        val cx = w * 0.5f
        val cy = h * 0.5f
        val minDim = min(w, h).toFloat()

        // ── Parameters ──────────────────────────────────────────────────────
        val seedShape = (params["seedShape"] as? String) ?: "circle"
        val emitterCount = ((params["emitters"] as? Number)?.toInt() ?: 3).coerceIn(1, 5)
        val orbitPattern = (params["orbitPattern"] as? String) ?: "lissajous"
        val patternId = when (orbitPattern) {
            "lissajous" -> 0; "circular" -> 1; "spiral" -> 2; else -> 3
        }
        val feedbackDecay = (params["feedbackDecay"] as? Number)?.toFloat() ?: 0.95f
        val iterations = ((params["iterations"] as? Number)?.toInt() ?: 3).coerceIn(1, 5)
        val speed = (params["speed"] as? Number)?.toFloat() ?: 0.5f
        val colors = palette.colors
        val nColors = colors.size
        val colorInts = palette.colorInts()
        val t = time * speed

        // ── Audio reactivity ────────────────────────────────────────────────
        val rx = (params["reactivity"] as? Number)?.toFloat() ?: 1.0f
        val audioAnalysis = params["_audioAnalysis"] as? AudioAnalysis
        val audioBass = (audioAnalysis?.getBass(time) ?: 0f) * rx
        val audioMid = (audioAnalysis?.getMid(time) ?: 0f) * rx
        val audioHigh = (audioAnalysis?.getHigh(time) ?: 0f) * rx
        val audioEnergy = ((audioBass + audioMid + audioHigh) / 3f)

        // Bass -> pulse scale + seed size
        val baseScale = ((params["feedbackScale"] as? Number)?.toFloat() ?: 1.02f) *
            (1f + audioBass * 0.15f)
        // Mid -> modulate rotation
        val baseRotDeg = ((params["feedbackRotation"] as? Number)?.toFloat() ?: 2.0f) +
            audioMid * 10f
        // High -> hue shift speed
        val hueShift = ((params["hueShift"] as? Number)?.toFloat() ?: 5f) +
            audioHigh * 15f
        // Energy -> seed size pulse
        val baseSeedSize = ((params["seedSize"] as? Number)?.toFloat() ?: 0.08f) * minDim *
            (1f + audioBass * 0.5f + audioEnergy * 0.3f)
        val doHue = hueShift > 0.5f

        // ── Oscillating feedback -- key to dynamism ─────────────────────────
        val breathScale = baseScale * (1f + 0.03f * sin(t * 2.5f))
        val breathRotDeg = baseRotDeg * (1f + 0.4f * sin(t * 1.3f))

        // ── Decay alpha ─────────────────────────────────────────────────────
        val decayAlpha = ((1f - feedbackDecay) * (1f + audioEnergy * 0.3f) * 255f)
            .roundToInt().coerceIn(1, 255)

        // ── Hue shift ColorMatrix ───────────────────────────────────────────
        val hueColorMatrix: ColorMatrix? = if (doHue) {
            ColorMatrix().apply {
                setRotate(0, hueShift)
                val tmp = ColorMatrix()
                tmp.setRotate(1, hueShift)
                postConcat(tmp)
                tmp.setRotate(2, hueShift)
                postConcat(tmp)
            }
        } else null

        // ── Ensure buffers (state cache) ────────────────────────────────────
        var st = state
        if (st == null || st.seed != seed || st.w != w || st.h != h) {
            // Recycle old bitmaps
            st?.bufferA?.recycle()
            st?.bufferB?.recycle()

            val rng = SeededRNG(seed)
            val freqA = FloatArray(emitterCount)
            val freqB = FloatArray(emitterCount)
            val phase = FloatArray(emitterCount)
            val orbitR = FloatArray(emitterCount)
            for (e in 0 until emitterCount) {
                freqA[e] = rng.range(0.8f, 2.5f)
                freqB[e] = rng.range(1.2f, 3.0f)
                phase[e] = rng.range(0f, TAU)
                orbitR[e] = minDim * rng.range(0.1f, 0.3f)
            }

            val bufA = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val bufB = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)

            st = FeedbackState(
                seed = seed, w = w, h = h,
                bufferA = bufA, bufferB = bufB,
                useA = true,
                emitterFreqA = freqA, emitterFreqB = freqB,
                emitterPhase = phase, emitterOrbitR = orbitR,
                initialized = false
            )
            state = st

            // ── Draw initial burst on bufferA ───────────────────────────────
            val canA = Canvas(bufA)
            canA.drawColor(Color.BLACK)

            // Radial rays
            val rays = 8 + rng.integer(0, 12)
            linePaint.strokeCap = Paint.Cap.ROUND
            for (i in 0 until rays) {
                val angle = (i.toFloat() / rays) * TAU + rng.range(-0.15f, 0.15f)
                val len = rng.range(minDim * 0.12f, minDim * 0.35f)
                linePaint.color = colorInts[i % nColors]
                linePaint.strokeWidth = rng.range(minDim * 0.006f, minDim * 0.02f)
                canA.drawLine(cx, cy, cx + cos(angle) * len, cy + sin(angle) * len, linePaint)
            }

            // Concentric colored rings (radial gradients)
            for (r in 3 downTo 0) {
                val radius = minDim * (0.04f + r * 0.035f)
                val cInt = colorInts[r % nColors]
                val cR = Color.red(cInt)
                val cG = Color.green(cInt)
                val cB = Color.blue(cInt)
                val shader = RadialGradient(
                    cx, cy, radius,
                    Color.rgb(cR, cG, cB),
                    Color.argb(0, cR, cG, cB),
                    Shader.TileMode.CLAMP
                )
                shapePaint.shader = shader
                shapePaint.style = Paint.Style.FILL
                canA.drawCircle(cx, cy, radius, shapePaint)
            }
            shapePaint.shader = null

            // ── Warmup: 12 feedback iterations to develop structure ─────────
            val cosR0 = breathScale * cos(breathRotDeg * DEG_TO_RAD)
            val sinR0 = breathScale * sin(breathRotDeg * DEG_TO_RAD)

            decayPaint.color = Color.argb(decayAlpha, 0, 0, 0)
            decayPaint.style = Paint.Style.FILL

            if (doHue && hueColorMatrix != null) {
                feedbackPaint.colorFilter = ColorMatrixColorFilter(hueColorMatrix)
            } else {
                feedbackPaint.colorFilter = null
            }

            for (p in 0 until 12) {
                val wt = p * 0.2f
                val canB = Canvas(st.bufferB)
                canB.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

                // Feedback transform: draw bufferA onto bufferB with scale+rotate
                matrix.reset()
                matrix.postTranslate(-cx, -cy)
                matrix.postScale(breathScale, breathScale)
                matrix.postRotate(breathRotDeg)
                matrix.postTranslate(cx, cy)
                canB.drawBitmap(st.bufferA, matrix, feedbackPaint)

                // Decay overlay
                canB.drawRect(0f, 0f, w.toFloat(), h.toFloat(), decayPaint)

                // Emitters during warmup
                for (e in 0 until emitterCount) {
                    val (sx, sy) = emitterPos(
                        patternId, wt, st.emitterFreqA[e], st.emitterFreqB[e],
                        st.emitterPhase[e], st.emitterOrbitR[e], cx, cy
                    )
                    val ci = e % nColors
                    val sz = baseSeedSize * (0.8f + 0.4f * sin(wt + e))
                    drawSeed(canB, seedShape, sx, sy, sz, colorInts[ci], wt * 0.5f)
                }

                // Copy B -> A for next warmup iteration
                val canACopy = Canvas(st.bufferA)
                canACopy.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
                canACopy.drawBitmap(st.bufferB, 0f, 0f, null)
            }

            st.initialized = true
            st.useA = true // bufferA has the latest warmup result
        }

        // ── Main feedback loop -- alternating buffers ────────────────────────
        // src = buffer with latest result, dst = buffer to render into
        var srcBmp = if (st.useA) st.bufferA else st.bufferB
        var dstBmp = if (st.useA) st.bufferB else st.bufferA

        val breathRot = breathRotDeg * DEG_TO_RAD
        // Secondary counter-rotation for odd iterations
        val breathRotDeg2 = -breathRotDeg * 0.6f

        decayPaint.color = Color.argb(decayAlpha, 0, 0, 0)
        decayPaint.style = Paint.Style.FILL

        if (doHue && hueColorMatrix != null) {
            feedbackPaint.colorFilter = ColorMatrixColorFilter(hueColorMatrix)
        } else {
            feedbackPaint.colorFilter = null
        }

        for (i in 0 until iterations) {
            val iterT = t + i * 0.07f
            val useAlt = i % 2 == 1
            val rotDeg = if (useAlt) breathRotDeg2 else breathRotDeg

            val dstCanvas = Canvas(dstBmp)
            dstCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

            // Feedback transform with hue rotation
            matrix.reset()
            matrix.postTranslate(-cx, -cy)
            matrix.postScale(breathScale, breathScale)
            matrix.postRotate(rotDeg)
            matrix.postTranslate(cx, cy)
            dstCanvas.drawBitmap(srcBmp, matrix, feedbackPaint)

            // Decay
            dstCanvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), decayPaint)

            // ── Draw emitters ───────────────────────────────────────────────
            for (e in 0 until emitterCount) {
                val fA = st.emitterFreqA[e]
                val fB = st.emitterFreqB[e]
                val ph = st.emitterPhase[e]
                val oR = st.emitterOrbitR[e] * (1f + audioEnergy * 0.15f)
                val (sx, sy) = emitterPos(patternId, iterT, fA, fB, ph, oR, cx, cy)

                // Size pulses per emitter at different rates
                val sz = baseSeedSize * (0.7f + 0.5f * sin(iterT * 3f + e * 1.7f))
                val ci = ((e + floor(iterT * 1.5f).toInt()) % nColors + nColors) % nColors
                val rot = iterT * (0.5f + e * 0.3f)

                drawSeed(dstCanvas, seedShape, sx, sy, sz, colorInts[ci], rot)

                // Connecting line between consecutive emitters
                if (e > 0) {
                    val (px, py) = emitterPos(
                        patternId, iterT,
                        st.emitterFreqA[e - 1], st.emitterFreqB[e - 1],
                        st.emitterPhase[e - 1],
                        st.emitterOrbitR[e - 1] * (1f + audioEnergy * 0.15f),
                        cx, cy
                    )
                    linePaint.color = colorInts[ci]
                    linePaint.alpha = ((0.15f + audioBass * 0.2f) * 255f)
                        .roundToInt().coerceIn(0, 255)
                    linePaint.strokeWidth = sz * 0.3f
                    dstCanvas.drawLine(px, py, sx, sy, linePaint)
                }
            }

            // Swap src/dst
            val tmp = srcBmp
            srcBmp = dstBmp
            dstBmp = tmp
        }

        // After loop, srcBmp has the latest result
        // Track which buffer is current
        st.useA = (srcBmp === st.bufferA)

        // ── Bloom pass -- additive self-composite for glow ──────────────────
        val bloom = (0.08f + audioEnergy * 0.15f).coerceIn(0f, 1f)
        val srcCanvas = Canvas(srcBmp)
        bloomPaint.alpha = (bloom * 255f).roundToInt().coerceIn(0, 255)
        bloomPaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
        srcCanvas.drawBitmap(srcBmp, 0f, 0f, bloomPaint)
        bloomPaint.xfermode = null
        bloomPaint.alpha = 255

        // ── Copy result to output canvas ────────────────────────────────────
        canvas.drawBitmap(srcBmp, 0f, 0f, null)
    }

    // ── Emitter position on parametric orbit ────────────────────────────────

    private fun emitterPos(
        pattern: Int, t: Float, freqA: Float, freqB: Float,
        phase: Float, orbitR: Float, cx: Float, cy: Float
    ): Pair<Float, Float> {
        return when (pattern) {
            0 -> {
                // Lissajous
                Pair(
                    cx + cos(t * freqA + phase) * orbitR,
                    cy + sin(t * freqB + phase) * orbitR
                )
            }
            1 -> {
                // Circular
                Pair(
                    cx + cos(t * freqA + phase) * orbitR,
                    cy + sin(t * freqA + phase) * orbitR
                )
            }
            2 -> {
                // Spiral -- expanding/contracting radius
                val r = orbitR * (0.5f + 0.5f * sin(t * 0.3f + phase))
                Pair(
                    cx + cos(t * freqA + phase) * r,
                    cy + sin(t * freqA + phase) * r
                )
            }
            else -> {
                // Figure-8
                val a = t * freqA + phase
                Pair(
                    cx + sin(a) * orbitR,
                    cy + sin(a * 2f) * orbitR * 0.5f
                )
            }
        }
    }

    // ── Seed shape drawing ──────────────────────────────────────────────────

    private fun drawSeed(
        canvas: Canvas, shape: String,
        x: Float, y: Float, sz: Float,
        color: Int, rotation: Float
    ) {
        val cR = Color.red(color)
        val cG = Color.green(color)
        val cB = Color.blue(color)
        val glowColor = Color.argb(102, cR, cG, cB) // ~0.4 * 255

        canvas.save()
        canvas.translate(x, y)
        canvas.rotate(rotation * RAD_TO_DEG)

        // Glow halo (oversized, semi-transparent)
        shapePaint.style = Paint.Style.FILL
        shapePaint.color = glowColor
        shapePaint.shader = null
        drawShape(canvas, shape, 0f, 0f, sz * 1.6f)

        // Core shape (full opacity)
        shapePaint.color = color
        drawShape(canvas, shape, 0f, 0f, sz)

        canvas.restore()
    }

    private fun drawShape(canvas: Canvas, shape: String, x: Float, y: Float, sz: Float) {
        when (shape) {
            "circle" -> {
                canvas.drawCircle(x, y, sz, shapePaint)
            }
            "square" -> {
                canvas.drawRect(x - sz, y - sz, x + sz, y + sz, shapePaint)
            }
            "triangle" -> {
                shapePath.reset()
                shapePath.moveTo(x, y - sz)
                shapePath.lineTo(x - sz * 0.866f, y + sz * 0.5f)
                shapePath.lineTo(x + sz * 0.866f, y + sz * 0.5f)
                shapePath.close()
                canvas.drawPath(shapePath, shapePaint)
            }
            "ring" -> {
                shapePath.reset()
                shapePath.addCircle(x, y, sz, Path.Direction.CCW)
                shapePath.addCircle(x, y, sz * 0.5f, Path.Direction.CW)
                canvas.drawPath(shapePath, shapePaint)
            }
            "star" -> {
                shapePath.reset()
                for (i in 0 until 5) {
                    val a1 = (i.toFloat() / 5f) * TAU - PI.toFloat() / 2f
                    val a2 = ((i + 0.5f) / 5f) * TAU - PI.toFloat() / 2f
                    if (i == 0) {
                        shapePath.moveTo(x + cos(a1) * sz, y + sin(a1) * sz)
                    } else {
                        shapePath.lineTo(x + cos(a1) * sz, y + sin(a1) * sz)
                    }
                    shapePath.lineTo(x + cos(a2) * sz * 0.4f, y + sin(a2) * sz * 0.4f)
                }
                shapePath.close()
                canvas.drawPath(shapePath, shapePaint)
            }
            else -> {
                // cross
                val t = sz * 0.35f
                canvas.drawRect(x - sz, y - t, x + sz, y + t, shapePaint)
                canvas.drawRect(x - t, y - sz, x + t, y + sz, shapePaint)
            }
        }
    }

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float {
        val iterations = ((params["iterations"] as? Number)?.toInt() ?: 3).coerceIn(1, 5)
        val emitters = ((params["emitters"] as? Number)?.toInt() ?: 3).coerceIn(1, 5)
        val hueShift = (params["hueShift"] as? Number)?.toFloat() ?: 5f
        val base = if (hueShift > 0f) 80f else 40f
        val cost = (200f + iterations * (base + emitters * 10f)) / 500f
        return cost.coerceIn(0.1f, 1f)
    }

    companion object {
        private const val TAU = 2.0f * 3.1415926535897932f
        private const val DEG_TO_RAD = 3.1415926535897932f / 180f
        private const val RAD_TO_DEG = 180f / 3.1415926535897932f
    }
}
