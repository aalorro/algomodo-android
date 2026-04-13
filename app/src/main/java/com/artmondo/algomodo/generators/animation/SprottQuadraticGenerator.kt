package com.artmondo.algomodo.generators.animation

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import com.artmondo.algomodo.audio.AudioAnalysis
import com.artmondo.algomodo.core.rng.SeededRNG
import com.artmondo.algomodo.data.palettes.Palette
import com.artmondo.algomodo.generators.Generator
import com.artmondo.algomodo.generators.ParamGroup
import com.artmondo.algomodo.generators.Parameter
import com.artmondo.algomodo.generators.Quality
import java.util.Arrays
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Sprott Quadratic — chaotic 2D iterated map with morphing trails.
 *
 * Port of web animation/sprott-quadratic.ts. 12 coefficients describe a
 * quadratic map `x' = a0 + a1·x + a2·x² + a3·xy + a4·y + a5·y²`,
 * `y' = a6 + a7·x + a8·x² + a9·xy + a10·y + a11·y²`. Multiple particles
 * trace the attractor concurrently, leaving a persistent trail buffer
 * which fades each frame. Coefficients drift sinusoidally over time so
 * the shape morphs continuously.
 */
class SprottQuadraticGenerator : Generator {

    override val id = "animation-sprott-quadratic"
    override val family = "animation"
    override val styleName = "Sprott Quadratic"
    override val definition =
        "Chaotic quadratic map attractor with real-time morphing trails."
    override val algorithmNotes =
        "12 coefficients define a quadratic 2D iterated map. Coefficients are found by scored random search " +
        "(capped at 250 attempts) combining phase-space spread with a largest-Lyapunov-exponent bonus via " +
        "Wolf tangent-vector renormalization; the best-scored candidate is kept, with early-exit on strong " +
        "chaos. Fallback is a perturbed known attractor. A pool of particles traces the attractor each frame, " +
        "writing additively-accumulating pixels into a persistent trail buffer that fades by (1 - trailLength). " +
        "Coefficients drift sinusoidally at 12 seeded frequencies so the attractor shape morphs continuously. " +
        "Color modes: palette (angle-based via palette LUT), velocity (speed-based), particle (fixed color per " +
        "particle), monochrome. Audio bass modulates drift amplitude, brightness, and palette rotation."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema = listOf(
        Parameter.NumberParam(
            name = "Particles", key = "particles", group = ParamGroup.COMPOSITION,
            help = "Number of concurrent trajectories traced each frame",
            min = 20f, max = 400f, step = 10f, default = 220f
        ),
        Parameter.NumberParam(
            name = "Steps/Frame", key = "stepsPerFrame", group = ParamGroup.COMPOSITION,
            help = "Iterations per particle per frame — more = longer trails per frame",
            min = 50f, max = 500f, step = 50f, default = 300f
        ),
        Parameter.NumberParam(
            name = "Trail Length", key = "trailLength", group = ParamGroup.TEXTURE,
            help = "Trail persistence — higher = longer trails (1.0 = infinite)",
            min = 0.8f, max = 1.0f, step = 0.01f, default = 0.98f
        ),
        Parameter.NumberParam(
            name = "Drift Speed", key = "driftSpeed", group = ParamGroup.FLOW_MOTION,
            help = "How fast attractor coefficients morph over time",
            min = 0.05f, max = 1.0f, step = 0.05f, default = 0.30f
        ),
        Parameter.NumberParam(
            name = "Drift Amp", key = "driftAmp", group = ParamGroup.FLOW_MOTION,
            help = "How much coefficients shift — higher = more dramatic shape changes",
            min = 0.01f, max = 0.3f, step = 0.01f, default = 0.14f
        ),
        Parameter.SelectParam(
            name = "Color Mode", key = "colorMode", group = ParamGroup.COLOR,
            help = "palette: by position | velocity: by speed | particle: each particle a color | monochrome: white",
            options = listOf("palette", "velocity", "particle", "monochrome"),
            default = "palette"
        ),
        Parameter.NumberParam(
            name = "Anim Speed", key = "animSpeed", group = ParamGroup.FLOW_MOTION,
            help = "Playback speed multiplier for time-based drift",
            min = 0.5f, max = 3.0f, step = 0.1f, default = 1.0f
        ),
        Parameter.NumberParam(
            name = "Anim Amplitude", key = "animAmp", group = ParamGroup.FLOW_MOTION,
            help = "Multiplier on drift amplitude",
            min = 0.1f, max = 2.0f, step = 0.1f, default = 1.0f
        ),
        Parameter.NumberParam(
            name = "Audio Reactivity", key = "reactivity", group = ParamGroup.FLOW_MOTION,
            help = "How strongly audio input modulates the visuals (0 = off)",
            min = 0f, max = 2f, step = 0.1f, default = 1.0f
        )
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "particles" to 220f,
        "stepsPerFrame" to 300f,
        "trailLength" to 0.98f,
        "driftSpeed" to 0.30f,
        "driftAmp" to 0.14f,
        "colorMode" to "palette",
        "animSpeed" to 1.0f,
        "animAmp" to 1.0f,
        "reactivity" to 1.0f
    )

    private companion object {
        const val LUT_SIZE = 256
        const val BG_COLOR = 0xFF000000.toInt()
        const val MAX_COEFF_TRIES = 250

        // Color mode int codes (parsed once in renderCanvas).
        const val CM_PALETTE = 0
        const val CM_VELOCITY = 1
        const val CM_PARTICLE = 2
        const val CM_MONO = 3

        // Known chaotic Sprott quadratic attractors — used as fallback if
        // random search fails to find valid coefficients.
        val KNOWN_ATTRACTORS: Array<DoubleArray> = arrayOf(
            doubleArrayOf(1.0, 0.0, -1.4, 0.0, 1.0, 0.0, 0.0, 0.3, 0.0, 0.0, 0.0, 0.0),
            doubleArrayOf(0.9, -0.8, 0.5, 0.1, 0.3, -0.2, 0.1, 0.4, -0.3, -0.7, 0.2, 0.1),
            doubleArrayOf(-0.1, 0.5, -1.1, 0.3, 0.7, 0.1, 0.3, -0.6, 0.2, -0.9, 0.4, -0.2),
            doubleArrayOf(0.4, -1.1, 0.2, 0.5, -0.3, 0.1, -0.2, 0.7, -0.5, 0.3, -1.0, 0.1),
            doubleArrayOf(-0.3, 0.7, -0.8, 0.4, 0.6, -0.1, 0.5, -0.4, 0.3, -0.6, 0.8, -0.3),
            doubleArrayOf(0.6, -0.5, 0.9, -0.2, 0.4, 0.3, -0.4, 0.8, -0.6, 0.5, -0.3, 0.2)
        )

        // Per-coefficient independent drift frequencies.
        val FREQS = doubleArrayOf(
            0.37, 0.53, 0.29, 0.61, 0.43, 0.31,
            0.47, 0.59, 0.41, 0.67, 0.23, 0.51
        )

        fun colorModeCode(s: String): Int = when (s) {
            "palette" -> CM_PALETTE
            "velocity" -> CM_VELOCITY
            "particle" -> CM_PARTICLE
            else -> CM_MONO
        }

        /**
         * Fast atan2 → [0, 256) index approximation. Nvidia-style cubic
         * approximation with max error ~0.01 rad, well below the 256-bucket
         * quantization floor. ~10x faster than Math.atan2 at runtime.
         */
        fun angleIndex256(dy: Double, dx: Double): Int {
            val absY = (if (dy < 0) -dy else dy) + 1e-12
            val angle = if (dx >= 0) {
                val r = (dx - absY) / (dx + absY)
                0.1963 * r * r * r - 0.9817 * r + PI * 0.25
            } else {
                val r = (dx + absY) / (absY - dx)
                0.1963 * r * r * r - 0.9817 * r + PI * 0.75
            }
            val signed = if (dy < 0) -angle else angle
            // Map [-PI, PI] → [0, 256).
            return ((signed / PI + 1.0) * 127.5).toInt() and 255
        }
    }

    /**
     * Persistent per-seed state. Holds particle positions, the pre-computed
     * coefficient base + bounds + phases, and the RGBA trail pixel buffer
     * that carries visual history across frames. Also caches per-frame
     * scratch buffers (drifted coeffs, palette LUT, particle-color LUT)
     * to avoid per-frame GC pressure.
     */
    private class SprottState(
        val seed: Int,
        val w: Int,
        val h: Int,
        val count: Int,
        val baseCoeffs: DoubleArray,     // size 12
        val bounds: DoubleArray,         // [minX, maxX, minY, maxY]
        val phases: DoubleArray,         // size 12 — per-coefficient drift offsets
        val px: DoubleArray,
        val py: DoubleArray,
        val pColor: FloatArray,
        val pixels: IntArray,            // w*h trail buffer
        val recoveryRng: SeededRNG,      // deterministic divergence recovery
        var initialized: Boolean
    ) {
        // Reused per-frame.
        val driftedCoeffs: DoubleArray = DoubleArray(12)
        val particleColors: IntArray = IntArray(count)

        // Palette LUT cache — rebuilt only when palette identity changes.
        var cachedPalette: Palette? = null
        var cachedLut: IntArray? = null
    }

    // Single-slot cache; follows CurlFluidGenerator convention.
    @Volatile private var stateCache: SprottState? = null

    // ── Iterate the quadratic map, tracking bounds after a warmup period ──
    // Returns null if the trajectory diverges.
    private fun iterateBounds(a: DoubleArray, iters: Int, warmup: Int): DoubleArray? {
        var x = 0.05; var y = 0.05
        var minX = Double.POSITIVE_INFINITY; var maxX = Double.NEGATIVE_INFINITY
        var minY = Double.POSITIVE_INFINITY; var maxY = Double.NEGATIVE_INFINITY
        val a0 = a[0]; val a1 = a[1]; val a2 = a[2]; val a3 = a[3]; val a4 = a[4]; val a5 = a[5]
        val a6 = a[6]; val a7 = a[7]; val a8 = a[8]; val a9 = a[9]; val a10 = a[10]; val a11 = a[11]
        for (i in 0 until iters) {
            val xn = a0 + a1 * x + a2 * x * x + a3 * x * y + a4 * y + a5 * y * y
            val yn = a6 + a7 * x + a8 * x * x + a9 * x * y + a10 * y + a11 * y * y
            // Magnitude check catches both overflow and NaN propagation
            // (NaN comparisons are false so it escapes, but the next iter propagates).
            // `!(a < b)` catches NaN (NaN-compares are false) and overflow.
            if (!(xn * xn + yn * yn < 1e8)) return null
            x = xn; y = yn
            if (i > warmup) {
                if (x < minX) minX = x; if (x > maxX) maxX = x
                if (y < minY) minY = y; if (y > maxY) maxY = y
            }
        }
        if (!minX.isFinite()) return null
        return doubleArrayOf(minX, maxX, minY, maxY)
    }

    private fun findBounds(a: DoubleArray): DoubleArray {
        val b = iterateBounds(a, iters = 5000, warmup = 100) ?: return doubleArrayOf(-2.0, 2.0, -2.0, 2.0)
        val px = (b[1] - b[0]) * 0.1
        val py = (b[3] - b[2]) * 0.1
        return doubleArrayOf(b[0] - px, b[1] + px, b[2] - py, b[3] + py)
    }

    /**
     * Estimate the largest Lyapunov exponent via tangent-vector
     * renormalization (Wolf algorithm). Positive values → chaos;
     * zero → periodic orbit; negative → fixed point / decay.
     * Returns Double.NEGATIVE_INFINITY if the trajectory diverges.
     */
    private fun lyapunovExponent(a: DoubleArray): Double {
        val a0 = a[0]; val a1 = a[1]; val a2 = a[2]; val a3 = a[3]; val a4 = a[4]; val a5 = a[5]
        val a6 = a[6]; val a7 = a[7]; val a8 = a[8]; val a9 = a[9]; val a10 = a[10]; val a11 = a[11]
        var x = 0.05; var y = 0.05
        // Warmup onto the attractor.
        for (i in 0 until 200) {
            val xn = a0 + a1 * x + a2 * x * x + a3 * x * y + a4 * y + a5 * y * y
            val yn = a6 + a7 * x + a8 * x * x + a9 * x * y + a10 * y + a11 * y * y
            if (!(xn * xn + yn * yn < 1e8)) return Double.NEGATIVE_INFINITY
            x = xn; y = yn
        }
        var dx = 1e-8; var dy = 0.0
        var lyapSum = 0.0
        val n = 600
        for (i in 0 until n) {
            // Jacobian rows: (∂x'/∂x, ∂x'/∂y) and (∂y'/∂x, ∂y'/∂y).
            val j00 = a1 + 2.0 * a2 * x + a3 * y
            val j01 = a3 * x + a4 + 2.0 * a5 * y
            val j10 = a7 + 2.0 * a8 * x + a9 * y
            val j11 = a9 * x + a10 + 2.0 * a11 * y
            val ndx = j00 * dx + j01 * dy
            val ndy = j10 * dx + j11 * dy
            val xn = a0 + a1 * x + a2 * x * x + a3 * x * y + a4 * y + a5 * y * y
            val yn = a6 + a7 * x + a8 * x * x + a9 * x * y + a10 * y + a11 * y * y
            if (!(xn * xn + yn * yn < 1e8)) return Double.NEGATIVE_INFINITY
            x = xn; y = yn
            val mag = kotlin.math.sqrt(ndx * ndx + ndy * ndy)
            if (mag < 1e-300 || mag.isNaN()) return Double.NEGATIVE_INFINITY
            lyapSum += kotlin.math.ln(mag / 1e-8)
            // Renormalize tangent vector back to length 1e-8.
            val k = 1e-8 / mag
            dx = ndx * k; dy = ndy * k
        }
        return lyapSum / n
    }

    /**
     * Score candidate coefficients. Combines bounded spread with a
     * Lyapunov bonus so we prefer visibly-chaotic attractors with wide
     * coverage. Non-chaotic or degenerate candidates return < 0.
     */
    private fun scoreCoeffs(a: DoubleArray): Double {
        val b = iterateBounds(a, iters = 2000, warmup = 50) ?: return -1.0
        val spreadX = b[1] - b[0]
        val spreadY = b[3] - b[2]
        // Require moderate extent in both dimensions.
        if (spreadX < 0.5 || spreadY < 0.5) return -1.0
        val lyap = lyapunovExponent(a)
        if (lyap <= 0.01) return -1.0  // periodic / fixed / diverging
        // Reward balanced aspect ratio (not a line).
        val aspect = min(spreadX, spreadY) / max(spreadX, spreadY)
        return (spreadX + spreadY) * aspect * (1.0 + lyap * 8.0)
    }

    // ── Find valid coefficients ────────────────────────────────────
    // Searches for a chaotic attractor with good visual spread. Tracks
    // the best-scored candidate as it goes and early-exits on a great
    // score. Falls back to a perturbed known attractor if nothing good
    // is found — guaranteed to be interesting.
    private fun findCoeffs(seed: Int): DoubleArray {
        val rng = SeededRNG(seed)
        val c = DoubleArray(12)
        var bestScore = -1.0
        var best: DoubleArray? = null
        for (i in 0 until MAX_COEFF_TRIES) {
            for (j in 0 until 12) {
                // Round to 1 decimal to match web impl's coefficient granularity.
                c[j] = kotlin.math.round((rng.randomDouble() * 2.4 - 1.2) * 10.0) / 10.0
            }
            val score = scoreCoeffs(c)
            if (score > bestScore) {
                bestScore = score
                best = c.copyOf()
                if (bestScore > 4.0) break  // strong attractor — stop early
            }
        }
        if (best != null && bestScore > 1.0) return best
        // Fallback: pick a known attractor and perturb it deterministically
        // so each seed still yields a distinct shape.
        val idx = ((seed % KNOWN_ATTRACTORS.size) + KNOWN_ATTRACTORS.size) % KNOWN_ATTRACTORS.size
        val base = KNOWN_ATTRACTORS[idx].copyOf()
        val jitter = SeededRNG(seed + 999)
        for (j in 0 until 12) {
            val delta = kotlin.math.round((jitter.randomDouble() * 0.12 - 0.06) * 10.0) / 10.0
            base[j] = (base[j] + delta).coerceIn(-1.2, 1.2)
        }
        // Only accept perturbation if still chaotic; else revert.
        return if (scoreCoeffs(base) > 0.5) base else KNOWN_ATTRACTORS[idx].copyOf()
    }

    private fun getState(seed: Int, w: Int, h: Int, count: Int): SprottState {
        val cached = stateCache
        if (cached != null && cached.seed == seed && cached.w == w && cached.h == h && cached.count == count) {
            return cached
        }

        val baseCoeffs = findCoeffs(seed)
        val bounds = findBounds(baseCoeffs)
        val rng = SeededRNG(seed + 333)

        val phases = DoubleArray(12) { rng.randomDouble() * PI * 2.0 }

        val px = DoubleArray(count)
        val py = DoubleArray(count)
        val pColor = FloatArray(count)
        for (i in 0 until count) {
            px[i] = rng.randomDouble() - 0.5
            py[i] = rng.randomDouble() - 0.5
            pColor[i] = i.toFloat() / count
        }

        val pixels = IntArray(w * h)
        Arrays.fill(pixels, BG_COLOR)

        val state = SprottState(
            seed, w, h, count,
            baseCoeffs, bounds, phases,
            px, py, pColor,
            pixels,
            recoveryRng = SeededRNG(seed + 777),
            initialized = false
        )
        stateCache = state
        return state
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
        if (w == 0 || h == 0) return

        // Quality-based scaling — reduces particle/step load during DRAFT
        // live-preview without affecting full-resolution exports.
        val qualityMul = when (quality) {
            Quality.DRAFT -> 0.7f
            Quality.BALANCED -> 1f
            Quality.ULTRA -> 1f
        }
        val count = (((params["particles"] as? Number)?.toFloat() ?: 220f) * qualityMul)
            .toInt().coerceIn(20, 400)
        val stepsPerFrame = (((params["stepsPerFrame"] as? Number)?.toFloat() ?: 300f) * qualityMul)
            .toInt().coerceIn(50, 500)

        val trailLen = ((params["trailLength"] as? Number)?.toFloat() ?: 0.98f).coerceIn(0.8f, 1.0f)
        val driftSpeed = (params["driftSpeed"] as? Number)?.toDouble() ?: 0.30
        val animAmp = (params["animAmp"] as? Number)?.toDouble() ?: 1.0
        val driftAmp = ((params["driftAmp"] as? Number)?.toDouble() ?: 0.14) * animAmp
        val colorMode = colorModeCode((params["colorMode"] as? String) ?: "palette")
        val speed = (params["animSpeed"] as? Number)?.toDouble() ?: 1.0

        val rx = (params["reactivity"] as? Number)?.toFloat() ?: 1.0f
        val audioAnalysis = params["_audioAnalysis"] as? AudioAnalysis
        val bass = ((audioAnalysis?.getBass(time) ?: 0f) * rx).toDouble()
        val energy = ((audioAnalysis?.getMid(time) ?: 0f) * rx).toDouble()

        canvas.drawColor(Color.BLACK)

        val s = getState(seed, w, h, count)

        // Compute drifted coefficients into a reusable buffer (no per-frame alloc).
        val a = s.driftedCoeffs
        val tDrift = time.toDouble() * speed
        val amp = driftAmp * (1.0 + bass * 0.5)
        for (i in 0 until 12) {
            a[i] = s.baseCoeffs[i] + amp * sin(tDrift * driftSpeed * FREQS[i] + s.phases[i])
        }

        val bounds = s.bounds
        val cx = (bounds[0] + bounds[1]) * 0.5
        val cy = (bounds[2] + bounds[3]) * 0.5
        val extent = max(bounds[1] - bounds[0], bounds[3] - bounds[2])
        val scale = min(w, h) * 0.9 / extent
        val halfW = w * 0.5
        val halfH = h * 0.5

        // Empty palette is unusable by any palette-dependent color mode.
        // Guard once here so downstream LUT build + lerpColor are always safe.
        if (colorMode != CM_MONO && palette.colorInts().isEmpty()) return

        // Palette-dependent caches. Rebuild both palLut and particleColors on
        // palette identity change — kept in sync so switching color modes
        // mid-animation doesn't trigger an extra rebuild.
        if (s.cachedPalette !== palette && colorMode != CM_MONO) {
            s.cachedLut = palette.buildLut(LUT_SIZE)
            for (p in 0 until count) {
                s.particleColors[p] = palette.lerpColor(s.pColor[p])
            }
            s.cachedPalette = palette
        }
        // Non-null for palette/velocity modes after the empty-palette guard above.
        val palLut: IntArray? = s.cachedLut

        // Color phase rotates palette over time; bass nudges it.
        val colorPhase = (((tDrift * 22.0) + bass * 40.0).toInt()) and 255
        // Bass-driven brightness boost — peaks pulse the trails.
        val brightBoost = (1.0 + bass * 0.6 + energy * 0.2).coerceIn(1.0, 1.6)
        // Fixed-point boost: boost256 ∈ [256, 410].
        val boost256 = (brightBoost * 256.0).toInt()

        // Initialize on first frame: warmup + prime trails.
        if (!s.initialized) {
            s.initialized = true
            // Bounded warmup (16000/count ∈ [40,80]) — caps first-frame cost.
            val warmupIters = (16000 / count).coerceIn(40, 80)
            warmupParticles(s, a, warmupIters)

            // Prime the trail buffer with 2 frames. Original web used 4 but
            // each frame already runs count × stepsPerFrame iterations; 2 is
            // plenty to avoid a black first frame while keeping init cheap.
            // Uses base boost (256) with no bass modulation — brightness
            // scales up once real audio-driven boost kicks in next frame.
            for (f in 0 until 2) {
                fadePixels(s.pixels, trailLen)
                renderDispatch(s, a, stepsPerFrame, w, h, cx, cy, scale, halfW, halfH,
                    palLut, colorMode, 0, 256)
            }
        }

        // Trail fade.
        fadePixels(s.pixels, trailLen)

        renderDispatch(s, a, stepsPerFrame, w, h, cx, cy, scale, halfW, halfH,
            palLut, colorMode, colorPhase, boost256)

        // Blit persistent trail buffer to the output bitmap.
        bitmap.setPixels(s.pixels, 0, w, 0, 0, w, h)
        canvas.drawBitmap(bitmap, 0f, 0f, null)
    }

    // ── Dispatch to a specialized inner loop per color mode ─────────
    private fun renderDispatch(
        s: SprottState, a: DoubleArray, steps: Int,
        w: Int, h: Int, cx: Double, cy: Double, scale: Double,
        halfW: Double, halfH: Double,
        palLut: IntArray?, colorMode: Int, colorPhase: Int, boost256: Int
    ) {
        when (colorMode) {
            CM_PALETTE -> renderPalette(s, a, steps, w, h, cx, cy, scale, halfW, halfH, palLut!!, colorPhase, boost256)
            CM_VELOCITY -> renderVelocity(s, a, steps, w, h, cx, cy, scale, halfW, halfH, palLut!!, colorPhase, boost256)
            CM_PARTICLE -> renderParticle(s, a, steps, w, h, cx, cy, scale, halfW, halfH, boost256)
            else -> renderMono(s, a, steps, w, h, cx, cy, scale, halfW, halfH, boost256)
        }
    }

    // ── Fade all trail pixels by trailLen (integer multiply, BG fast-path) ──
    private fun fadePixels(pix: IntArray, trailLen: Float) {
        // Scale each RGB channel by trailLen. Use integer multiply: mul/256.
        val mul = (trailLen * 256f).toInt().coerceIn(0, 256)
        if (mul >= 256) return  // no fade (trailLen == 1)
        var i = 0
        val n = pix.size
        while (i < n) {
            val c = pix[i]
            // Fast-path: already-black pixels (typical for attractor background)
            // fade to themselves. Skips ~80% of pixels on sparse trail frames.
            if (c == BG_COLOR) { i++; continue }
            val r = (((c ushr 16) and 0xFF) * mul) ushr 8
            val g = (((c ushr 8) and 0xFF) * mul) ushr 8
            val b = ((c and 0xFF) * mul) ushr 8
            pix[i] = BG_COLOR or (r shl 16) or (g shl 8) or b
            i++
        }
    }

    // ── Warmup: settle particles onto the attractor before first render ──
    private fun warmupParticles(s: SprottState, a: DoubleArray, iters: Int) {
        val a0 = a[0]; val a1 = a[1]; val a2 = a[2]; val a3 = a[3]; val a4 = a[4]; val a5 = a[5]
        val a6 = a[6]; val a7 = a[7]; val a8 = a[8]; val a9 = a[9]; val a10 = a[10]; val a11 = a[11]
        val pxArr = s.px; val pyArr = s.py
        val rng = s.recoveryRng
        for (p in 0 until s.count) {
            var x = pxArr[p]; var y = pyArr[p]
            for (j in 0 until iters) {
                val xx = x * x; val xy = x * y; val yy = y * y
                val xn = a0 + a1 * x + a2 * xx + a3 * xy + a4 * y + a5 * yy
                val yn = a6 + a7 * x + a8 * xx + a9 * xy + a10 * y + a11 * yy
                if (!(xn * xn + yn * yn < 1e8)) {
                    x = (rng.randomDouble() - 0.5) * 0.2
                    y = (rng.randomDouble() - 0.5) * 0.2
                    continue
                }
                x = xn; y = yn
            }
            pxArr[p] = x; pyArr[p] = y
        }
    }

    // ── Additive-blend one pixel with saturating clamp ───────────────
    // Each hit adds (src * boost256 / 512) to the existing value so
    // repeated hits accumulate visibly (unlike a lerp which saturates
    // toward the source color). A single hit reaches ~50% on black;
    // 2-3 hits saturate bright regions — makes attractor dense spots
    // genuinely glow rather than flatten to a constant tint.
    private fun blendPixel(pix: IntArray, idx: Int, cr: Int, cg: Int, cb: Int, boost256: Int) {
        val old = pix[idx]
        val or = (old ushr 16) and 0xFF
        val og = (old ushr 8) and 0xFF
        val ob = old and 0xFF
        val addR = (cr * boost256) ushr 9
        val addG = (cg * boost256) ushr 9
        val addB = (cb * boost256) ushr 9
        var nr = or + addR
        var ng = og + addG
        var nb = ob + addB
        if (nr > 255) nr = 255
        if (ng > 255) ng = 255
        if (nb > 255) nb = 255
        pix[idx] = BG_COLOR or (nr shl 16) or (ng shl 8) or nb
    }

    // ── Palette color mode: angle-based LUT lookup per step ────────
    private fun renderPalette(
        s: SprottState, a: DoubleArray, steps: Int,
        w: Int, h: Int, cx: Double, cy: Double, scale: Double,
        halfW: Double, halfH: Double,
        palLut: IntArray, colorPhase: Int, boost256: Int
    ) {
        val pix = s.pixels
        val count = s.count
        val pxArr = s.px; val pyArr = s.py
        val a0 = a[0]; val a1 = a[1]; val a2 = a[2]; val a3 = a[3]; val a4 = a[4]; val a5 = a[5]
        val a6 = a[6]; val a7 = a[7]; val a8 = a[8]; val a9 = a[9]; val a10 = a[10]; val a11 = a[11]
        val rng = s.recoveryRng

        for (p in 0 until count) {
            var x = pxArr[p]; var y = pyArr[p]
            var diverged = false
            for (j in 0 until steps) {
                val xx = x * x; val xy = x * y; val yy = y * y
                val xn = a0 + a1 * x + a2 * xx + a3 * xy + a4 * y + a5 * yy
                val yn = a6 + a7 * x + a8 * xx + a9 * xy + a10 * y + a11 * yy
                if (!(xn * xn + yn * yn < 1e8)) { diverged = true; break }

                val li = (angleIndex256(yn - cy, xn - cx) + colorPhase) and 255
                val c = palLut[li]

                x = xn; y = yn
                val sx = ((x - cx) * scale + halfW).toInt()
                val sy = ((y - cy) * scale + halfH).toInt()
                if (sx in 0 until w && sy in 0 until h) {
                    blendPixel(pix, sy * w + sx,
                        (c ushr 16) and 0xFF, (c ushr 8) and 0xFF, c and 0xFF, boost256)
                }
            }
            if (diverged) {
                pxArr[p] = (rng.randomDouble() - 0.5) * 0.2
                pyArr[p] = (rng.randomDouble() - 0.5) * 0.2
            } else {
                pxArr[p] = x; pyArr[p] = y
            }
        }
    }

    // ── Velocity color mode: speed-based LUT lookup ─────────────────
    private fun renderVelocity(
        s: SprottState, a: DoubleArray, steps: Int,
        w: Int, h: Int, cx: Double, cy: Double, scale: Double,
        halfW: Double, halfH: Double,
        palLut: IntArray, colorPhase: Int, boost256: Int
    ) {
        val pix = s.pixels
        val count = s.count
        val pxArr = s.px; val pyArr = s.py
        val a0 = a[0]; val a1 = a[1]; val a2 = a[2]; val a3 = a[3]; val a4 = a[4]; val a5 = a[5]
        val a6 = a[6]; val a7 = a[7]; val a8 = a[8]; val a9 = a[9]; val a10 = a[10]; val a11 = a[11]
        val rng = s.recoveryRng

        for (p in 0 until count) {
            var x = pxArr[p]; var y = pyArr[p]
            var diverged = false
            for (j in 0 until steps) {
                val xx = x * x; val xy = x * y; val yy = y * y
                val xn = a0 + a1 * x + a2 * xx + a3 * xy + a4 * y + a5 * yy
                val yn = a6 + a7 * x + a8 * xx + a9 * xy + a10 * y + a11 * yy
                if (!(xn * xn + yn * yn < 1e8)) { diverged = true; break }

                // Use squared-speed directly — immediately quantized to 256 bins,
                // so we can skip sqrt and rescale the coefficient.
                val vx = xn - x; val vy = yn - y
                val sp2 = vx * vx + vy * vy
                // Original: spd * 512 where spd = sqrt(sp2). We map sp2 so that
                // visually-similar speeds end up in nearby bins. Empirical coeff.
                val li = ((sp2 * 2048.0).toInt() + colorPhase) and 255
                val c = palLut[li]

                x = xn; y = yn
                val sx = ((x - cx) * scale + halfW).toInt()
                val sy = ((y - cy) * scale + halfH).toInt()
                if (sx in 0 until w && sy in 0 until h) {
                    blendPixel(pix, sy * w + sx,
                        (c ushr 16) and 0xFF, (c ushr 8) and 0xFF, c and 0xFF, boost256)
                }
            }
            if (diverged) {
                pxArr[p] = (rng.randomDouble() - 0.5) * 0.2
                pyArr[p] = (rng.randomDouble() - 0.5) * 0.2
            } else {
                pxArr[p] = x; pyArr[p] = y
            }
        }
    }

    // ── Particle color mode: each particle has a fixed color ────────
    private fun renderParticle(
        s: SprottState, a: DoubleArray, steps: Int,
        w: Int, h: Int, cx: Double, cy: Double, scale: Double,
        halfW: Double, halfH: Double,
        boost256: Int
    ) {
        val pix = s.pixels
        val count = s.count
        val pxArr = s.px; val pyArr = s.py
        val a0 = a[0]; val a1 = a[1]; val a2 = a[2]; val a3 = a[3]; val a4 = a[4]; val a5 = a[5]
        val a6 = a[6]; val a7 = a[7]; val a8 = a[8]; val a9 = a[9]; val a10 = a[10]; val a11 = a[11]
        val pCols = s.particleColors
        val rng = s.recoveryRng

        for (p in 0 until count) {
            var x = pxArr[p]; var y = pyArr[p]
            val c = pCols[p]
            // Hoist additive deltas out of the per-step loop — particle color
            // and boost are invariant across all of this particle's steps.
            val addR = (((c ushr 16) and 0xFF) * boost256) ushr 9
            val addG = (((c ushr 8) and 0xFF) * boost256) ushr 9
            val addB = ((c and 0xFF) * boost256) ushr 9
            var diverged = false
            for (j in 0 until steps) {
                val xx = x * x; val xy = x * y; val yy = y * y
                val xn = a0 + a1 * x + a2 * xx + a3 * xy + a4 * y + a5 * yy
                val yn = a6 + a7 * x + a8 * xx + a9 * xy + a10 * y + a11 * yy
                if (!(xn * xn + yn * yn < 1e8)) { diverged = true; break }

                x = xn; y = yn
                val sx = ((x - cx) * scale + halfW).toInt()
                val sy = ((y - cy) * scale + halfH).toInt()
                if (sx in 0 until w && sy in 0 until h) {
                    val idx = sy * w + sx
                    val old = pix[idx]
                    var nr = ((old ushr 16) and 0xFF) + addR
                    var ng = ((old ushr 8) and 0xFF) + addG
                    var nb = (old and 0xFF) + addB
                    if (nr > 255) nr = 255
                    if (ng > 255) ng = 255
                    if (nb > 255) nb = 255
                    pix[idx] = BG_COLOR or (nr shl 16) or (ng shl 8) or nb
                }
            }
            if (diverged) {
                pxArr[p] = (rng.randomDouble() - 0.5) * 0.2
                pyArr[p] = (rng.randomDouble() - 0.5) * 0.2
            } else {
                pxArr[p] = x; pyArr[p] = y
            }
        }
    }

    // ── Monochrome color mode: fixed off-white ──────────────────────
    private fun renderMono(
        s: SprottState, a: DoubleArray, steps: Int,
        w: Int, h: Int, cx: Double, cy: Double, scale: Double,
        halfW: Double, halfH: Double,
        boost256: Int
    ) {
        val pix = s.pixels
        val count = s.count
        val pxArr = s.px; val pyArr = s.py
        val a0 = a[0]; val a1 = a[1]; val a2 = a[2]; val a3 = a[3]; val a4 = a[4]; val a5 = a[5]
        val a6 = a[6]; val a7 = a[7]; val a8 = a[8]; val a9 = a[9]; val a10 = a[10]; val a11 = a[11]
        val rng = s.recoveryRng

        // Fixed color (220,220,220) → all three channels share one add-delta.
        // Hoisted once per frame since boost256 is constant.
        val add = (220 * boost256) ushr 9

        for (p in 0 until count) {
            var x = pxArr[p]; var y = pyArr[p]
            var diverged = false
            for (j in 0 until steps) {
                val xx = x * x; val xy = x * y; val yy = y * y
                val xn = a0 + a1 * x + a2 * xx + a3 * xy + a4 * y + a5 * yy
                val yn = a6 + a7 * x + a8 * xx + a9 * xy + a10 * y + a11 * yy
                if (!(xn * xn + yn * yn < 1e8)) { diverged = true; break }

                x = xn; y = yn
                val sx = ((x - cx) * scale + halfW).toInt()
                val sy = ((y - cy) * scale + halfH).toInt()
                if (sx in 0 until w && sy in 0 until h) {
                    val idx = sy * w + sx
                    val old = pix[idx]
                    var nr = ((old ushr 16) and 0xFF) + add
                    var ng = ((old ushr 8) and 0xFF) + add
                    var nb = (old and 0xFF) + add
                    if (nr > 255) nr = 255
                    if (ng > 255) ng = 255
                    if (nb > 255) nb = 255
                    pix[idx] = BG_COLOR or (nr shl 16) or (ng shl 8) or nb
                }
            }
            if (diverged) {
                pxArr[p] = (rng.randomDouble() - 0.5) * 0.2
                pyArr[p] = (rng.randomDouble() - 0.5) * 0.2
            } else {
                pxArr[p] = x; pyArr[p] = y
            }
        }
    }

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float {
        val count = (params["particles"] as? Number)?.toFloat() ?: 220f
        val steps = (params["stepsPerFrame"] as? Number)?.toFloat() ?: 300f
        val qMul = when (quality) {
            Quality.DRAFT -> 0.25f
            Quality.BALANCED -> 1f
            Quality.ULTRA -> 1f
        }
        return (count * steps * qMul / 60000f).coerceIn(0.1f, 1f)
    }
}
