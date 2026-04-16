package com.artmondo.algomodo.generators.animation

import android.graphics.Bitmap
import android.graphics.Canvas
import com.artmondo.algomodo.core.rng.SeededRNG
import com.artmondo.algomodo.core.rng.SimplexNoise
import com.artmondo.algomodo.data.palettes.Palette
import com.artmondo.algomodo.generators.Generator
import com.artmondo.algomodo.generators.ParamGroup
import com.artmondo.algomodo.generators.Parameter
import com.artmondo.algomodo.generators.Quality
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.math.*

/**
 * Kaleidoscopic animation built from noise with multiple pattern modes.
 *
 * Optimised with trig LUTs, combined value→colour LUT, segment symmetry
 * exploitation, cached screen-to-polar mapping, multi-threaded rendering,
 * and decoupled pattern / colour passes so that iridescent colour mode
 * (which has no segment rotational symmetry on the colour) still only
 * computes the expensive pattern values once per segment.
 */
class KaleidoscopeGenerator : Generator {

    override val id = "kaleidoscope"
    override val family = "animation"
    override val styleName = "Kaleidoscope"
    override val definition = "Kaleidoscopic animation created by folding noise across radial segments."
    override val algorithmNotes =
        "For each pixel, compute polar coordinates (r, theta) relative to centre. Fold theta " +
        "into one segment: theta_folded = abs(mod(theta, segmentAngle) - segmentAngle/2). " +
        "Pattern modes: geometric, organic, crystalline, mandala, fractal, starburst, mosaic, " +
        "interference, electric, lace, psychedelic. " +
        "Contrast sharpens transitions. Color modes shift the palette lookup by radius or angle+time."
    override val supportsVector = false
    override val supportsAnimation = true

    companion object {
        private const val LUT_SIZE = 4096
        private const val LUT_MASK = LUT_SIZE - 1
        private val INV_2PI_LUT = LUT_SIZE / (2.0 * PI).toFloat()
        private val SIN_LUT = FloatArray(LUT_SIZE) { sin(it * 2.0 * PI / LUT_SIZE).toFloat() }
        private val COS_LUT = FloatArray(LUT_SIZE) { cos(it * 2.0 * PI / LUT_SIZE).toFloat() }

        private val THREAD_COUNT = Runtime.getRuntime().availableProcessors().coerceIn(2, 8)
        private val executor = Executors.newFixedThreadPool(THREAD_COUNT)

        // Pattern ids for fast integer dispatch in the inner loop.
        private const val PAT_GEOMETRIC = 0
        private const val PAT_ORGANIC = 1
        private const val PAT_CRYSTALLINE = 2
        private const val PAT_MANDALA = 3
        private const val PAT_FRACTAL = 4
        private const val PAT_STARBURST = 5
        private const val PAT_MOSAIC = 6
        private const val PAT_INTERFERENCE = 7
        private const val PAT_ELECTRIC = 8
        private const val PAT_LACE = 9
        private const val PAT_PSYCHEDELIC = 10

        private fun patternId(name: String): Int = when (name) {
            "geometric" -> PAT_GEOMETRIC
            "organic" -> PAT_ORGANIC
            "crystalline" -> PAT_CRYSTALLINE
            "mandala" -> PAT_MANDALA
            "fractal" -> PAT_FRACTAL
            "starburst" -> PAT_STARBURST
            "mosaic" -> PAT_MOSAIC
            "interference" -> PAT_INTERFERENCE
            "electric" -> PAT_ELECTRIC
            "lace" -> PAT_LACE
            "psychedelic" -> PAT_PSYCHEDELIC
            else -> PAT_GEOMETRIC
        }

        private fun sinLut(x: Float): Float = SIN_LUT[(x * INV_2PI_LUT).toInt() and LUT_MASK]
        private fun cosLut(x: Float): Float = COS_LUT[(x * INV_2PI_LUT).toInt() and LUT_MASK]

        /** Fast atan2 approximation (~5× faster than Math.atan2, max error ~0.01 rad). */
        private fun fastAtan2(y: Float, x: Float): Float {
            if (x == 0f && y == 0f) return 0f
            val ax = abs(x); val ay = abs(y)
            val mn = min(ax, ay); val mx = max(ax, ay)
            val a = mn / mx
            val s = a * a
            var r = ((-0.0464964749f * s + 0.15931422f) * s - 0.327622764f) * s * a + a
            if (ay > ax) r = PI.toFloat() / 2f - r
            if (x < 0f) r = PI.toFloat() - r
            if (y < 0f) r = -r
            return r
        }
    }

    /**
     * Cached screen-to-polar mapping. Eliminates sqrt + atan2 for all frames
     * after the first render at a given bitmap size. Thread-safe via immutable snapshot.
     */
    private data class ScreenMapping(
        val w: Int, val h: Int, val radSteps: Int, val angSteps: Int,
        val ri0: ShortArray, val ai0: ShortArray,
        val fr: ByteArray, val fa: ByteArray,
        val outOfBounds: BooleanArray
    )

    @Volatile private var cachedMapping: ScreenMapping? = null

    // Cache SimplexNoise instances by seed to avoid rebuilding 512-entry permutation tables
    // per thread per frame. Accessed from multiple threads (live preview + export) so
    // guarded by a lock; values are effectively immutable after construction.
    private val noiseCacheLock = Any()
    private val noiseCache = object : LinkedHashMap<Int, SimplexNoise>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, SimplexNoise>?): Boolean =
            size > 8
    }

    private fun getNoise(seed: Int): SimplexNoise = synchronized(noiseCacheLock) {
        noiseCache.getOrPut(seed) { SimplexNoise(seed) }
    }

    private fun getOrBuildMapping(
        w: Int, h: Int, radSteps: Int, angSteps: Int
    ): ScreenMapping {
        val cached = cachedMapping
        if (cached != null && cached.w == w && cached.h == h &&
            cached.radSteps == radSteps && cached.angSteps == angSteps) {
            return cached
        }

        val size = w * h
        val ri0 = ShortArray(size)
        val ai0 = ShortArray(size)
        val fr = ByteArray(size)
        val fa = ByteArray(size)
        val outOfBounds = BooleanArray(size)

        val cx = w / 2f
        val cy = h / 2f
        val dim = min(w, h).toFloat()
        val invDim2 = 2f / dim
        val maxR = sqrt(2f)
        val rScale = (radSteps - 1) / maxR
        val aScale = angSteps / (2f * PI.toFloat())
        val radLimit = radSteps - 1
        val piF = PI.toFloat()

        val latch = CountDownLatch(THREAD_COUNT)
        for (t in 0 until THREAD_COUNT) {
            executor.execute {
                val pyStart = t * h / THREAD_COUNT
                val pyEnd = (t + 1) * h / THREAD_COUNT
                for (py in pyStart until pyEnd) {
                    val rawDy = (py - cy) * invDim2
                    val rawDy2 = rawDy * rawDy
                    val rowOff = py * w
                    for (px in 0 until w) {
                        val idx = rowOff + px
                        val rawDx = (px - cx) * invDim2
                        val rf = sqrt(rawDx * rawDx + rawDy2) * rScale
                        val ri = rf.toInt()
                        if (ri >= radLimit) {
                            outOfBounds[idx] = true
                            continue
                        }
                        val theta = fastAtan2(rawDy, rawDx)
                        val af = (theta + piF) * aScale

                        ri0[idx] = ri.toShort()
                        ai0[idx] = (af.toInt() % angSteps).toShort()
                        fr[idx] = ((rf - ri) * 255f).toInt().coerceIn(0, 255).toByte()
                        fa[idx] = ((af - af.toInt()) * 255f).toInt().coerceIn(0, 255).toByte()
                    }
                }
                latch.countDown()
            }
        }
        latch.await()

        val mapping = ScreenMapping(w, h, radSteps, angSteps, ri0, ai0, fr, fa, outOfBounds)
        cachedMapping = mapping
        return mapping
    }

    override val parameterSchema = listOf(
        Parameter.NumberParam(
            name = "Segments",
            key = "segments",
            group = ParamGroup.COMPOSITION,
            help = "Number of mirror segments — must be ≥ 3",
            min = 3f, max = 24f, step = 1f, default = 8f
        ),
        Parameter.SelectParam(
            name = "Pattern",
            key = "pattern",
            group = ParamGroup.COMPOSITION,
            help = "geometric: rings × spokes · organic: noise bands · crystalline: facets · mandala: sacred geometry · fractal: domain warping · starburst: radial bursts · mosaic: tiled cells · interference: wave superposition · electric: plasma arcs · lace: delicate filigree · psychedelic: warped spiral bands",
            options = listOf("geometric", "organic", "crystalline", "mandala", "fractal", "starburst", "mosaic", "interference", "electric", "lace", "psychedelic"),
            default = "geometric"
        ),
        Parameter.NumberParam(
            name = "Speed",
            key = "speed",
            group = ParamGroup.FLOW_MOTION,
            help = "Rotation and evolution speed",
            min = 0.1f, max = 3f, step = 0.1f, default = 1f
        ),
        Parameter.NumberParam(
            name = "Scale",
            key = "scale",
            group = ParamGroup.GEOMETRY,
            help = "Spatial zoom of the pattern",
            min = 0.5f, max = 5f, step = 0.5f, default = 2f
        ),
        Parameter.NumberParam(
            name = "Complexity",
            key = "complexity",
            group = ParamGroup.GEOMETRY,
            help = "Number of concentric bands / detail rings",
            min = 1f, max = 8f, step = 1f, default = 3f
        ),
        Parameter.NumberParam(
            name = "Detail",
            key = "detail",
            group = ParamGroup.GEOMETRY,
            help = "Fine-grain detail overlay — adds high-frequency harmonics on top of the base pattern",
            min = 1f, max = 5f, step = 1f, default = 2f
        ),
        Parameter.SelectParam(
            name = "Color Mode",
            key = "colorMode",
            group = ParamGroup.COLOR,
            help = "palette: value → gradient · depth: radius shifts hue · iridescent: angle + time chromatic shimmer",
            options = listOf("palette", "depth", "iridescent"),
            default = "palette"
        ),
        Parameter.NumberParam(
            name = "Contrast",
            key = "thickness",
            group = ParamGroup.TEXTURE,
            help = "Edge sharpness — higher pushes patterns toward hard transitions",
            min = 0.3f, max = 3f, step = 0.1f, default = 1.2f
        ),
        Parameter.NumberParam(
            name = "Sharpness",
            key = "sharpness",
            group = ParamGroup.TEXTURE,
            help = "Crispness of pattern transitions — higher values produce harder, more defined edges",
            min = 0.5f, max = 5f, step = 0.5f, default = 2f
        )
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "segments" to 8f,
        "pattern" to "geometric",
        "speed" to 1f,
        "scale" to 2f,
        "complexity" to 3f,
        "detail" to 2f,
        "colorMode" to "palette",
        "thickness" to 1.2f,
        "sharpness" to 2f
    )

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

        val segments = (params["segments"] as? Number)?.toInt() ?: 8
        val patternName = (params["pattern"] as? String) ?: "geometric"
        val pat = patternId(patternName)
        val complexity = (params["complexity"] as? Number)?.toInt() ?: 3
        val detail = (params["detail"] as? Number)?.toInt() ?: 2
        val rotationSpeed = (params["speed"] as? Number)?.toFloat() ?: 1f
        val zoom = (params["scale"] as? Number)?.toFloat() ?: 2f
        val colorMode = (params["colorMode"] as? String) ?: "palette"
        val contrast = (params["thickness"] as? Number)?.toFloat() ?: 1.2f
        val sharpness = (params["sharpness"] as? Number)?.toFloat() ?: 2f

        val rng = SeededRNG(seed)
        val segAngle = (2.0 * PI / segments).toFloat()
        val halfSeg = segAngle / 2f
        val rotation = time * rotationSpeed * 0.5f

        val offsetX = rng.random() * 100f
        val offsetY = rng.random() * 100f

        val twoPi = 2f * PI.toFloat()

        // --- Pre-compute combined value→color LUT ---
        val paletteLut = palette.buildLut(256)
        val invContrast = 1f / contrast
        val tanhSharp = tanh(sharpness)
        val combinedLut = IntArray(1024) { i ->
            val raw = i / 1023f * 2f - 1f
            val steep = tanh(raw * sharpness) / tanhSharp
            val sign = if (steep >= 0f) 1f else -1f
            val sharpened = if (contrast > 1f) sign * abs(steep).pow(invContrast)
            else steep * contrast
            (((sharpened * 0.5f + 0.5f).coerceIn(0f, 1f)) * 1023f).toInt()
        }

        val powLut2 = FloatArray(256) { i -> (i / 255f).pow(sharpness * 2f) }
        val powLut3 = FloatArray(256) { i -> (i / 255f).pow(sharpness * 3f) }
        val powLutS = FloatArray(256) { i -> (i / 255f).pow(sharpness) }

        val iriTimeShift = sinLut(time * rotationSpeed * 0.5f) * 0.1f

        // --- Polar buffer dimensions ---
        // angSteps is adjusted to be an exact multiple of segments so that
        // ai_full % segAngSteps maps cleanly back to a segment-local angle for
        // iridescent mode's per-angle colour lookup.
        val radSteps = when (quality) {
            Quality.DRAFT -> 250
            Quality.BALANCED -> 400
            Quality.ULTRA -> 600
        }
        val baseAngSteps = when (quality) {
            Quality.DRAFT -> 450
            Quality.BALANCED -> 720
            Quality.ULTRA -> 1080
        }
        val segAngSteps = (baseAngSteps / segments).coerceAtLeast(1)
        val angSteps = segAngSteps * segments
        val maxR = sqrt(2f)

        val isIridescent = colorMode == "iridescent"
        val isDepth = colorMode == "depth"

        // --- Per-radius LUTs ---
        val vignetteLut = FloatArray(radSteps) { ri ->
            val r = ri.toFloat() / (radSteps - 1) * maxR
            (1f - (r * 0.4f).coerceAtMost(0.7f)).coerceAtLeast(0.3f)
        }
        val rLut = FloatArray(radSteps) { ri -> ri.toFloat() / (radSteps - 1) * maxR }

        // --- Per-angle LUTs (segment-local: [0, segAngSteps)) ---
        // segTheta is in [0, halfSeg]; its sin/cos drive the pattern sx/sy.
        val segThetaLut = FloatArray(segAngSteps)
        val segThetaCosLut = FloatArray(segAngSteps)
        val segThetaSinLut = FloatArray(segAngSteps)
        for (ai in 0 until segAngSteps) {
            val theta = ai.toFloat() / angSteps * twoPi
            val thetaFolded = ((theta + rotation) % twoPi + twoPi) % twoPi
            var segTheta = thetaFolded % segAngle
            if (segTheta > halfSeg) segTheta = segAngle - segTheta
            segThetaLut[ai] = segTheta
            segThetaCosLut[ai] = cosLut(segTheta)
            segThetaSinLut[ai] = sinLut(segTheta)
        }

        // Iridescent-only: per-full-angle palette shift LUT.
        val iriShiftLut: FloatArray? = if (isIridescent) FloatArray(angSteps) { ai ->
            val theta = ai.toFloat() / angSteps * twoPi
            val thetaFolded = ((theta + rotation) % twoPi + twoPi) % twoPi
            (thetaFolded / twoPi) * 0.3f + iriTimeShift
        } else null

        // --- Pattern-specific hoisted constants ---
        // Many patterns have per-harmonic/per-octave/per-source values that do
        // not depend on (r, segTheta). Compute them once here.
        val c = complexity
        val ccap5 = c.coerceAtMost(5)
        val ccap6 = c.coerceAtMost(6)

        // Mandala hoist
        val mandalaRFreq = FloatArray(c) { idx -> val harm = idx + 1; harm * 2f * PI.toFloat() * zoom }
        val mandalaTimeR = FloatArray(c) { idx -> val harm = idx + 1; time * rotationSpeed * 0.3f * harm }
        val mandalaAFreq = FloatArray(c) { idx -> val harm = idx + 1; harm * segments / 2f }
        val mandalaTimeA = FloatArray(c) { idx -> val harm = idx + 1; time * 0.2f * harm }
        val mandalaInvHarm = FloatArray(c) { idx -> 1f / (idx + 1).toFloat() }
        val mandalaPetalFreq = segments * 0.5f

        // Interference hoist
        val interfSrcX = FloatArray(ccap6)
        val interfSrcY = FloatArray(ccap6)
        val interfDistFreq = c * 8f
        val interfTimeShift = time * rotationSpeed * 3f
        for (src in 0 until ccap6) {
            val srcAngle = src * twoPi / c + time * rotationSpeed * 0.2f
            val srcR = 0.3f + src * 0.15f
            interfSrcX[src] = cosLut(srcAngle) * srcR * zoom
            interfSrcY[src] = sinLut(srcAngle) * srcR * zoom
        }

        // Organic hoist
        val organicTimeA = FloatArray(c) { idx -> time * 0.08f * (idx + 1) }
        val organicTimeB = FloatArray(c) { idx -> time * 0.06f * (idx + 1) }
        // freq grows by *2.1 per octave, amp by *0.5
        val organicFreq = FloatArray(c).also {
            var f = 1f
            for (i in 0 until c) { it[i] = f; f *= 2.1f }
        }
        val organicAmp = FloatArray(c).also {
            var a = 1f
            for (i in 0 until c) { it[i] = a; a *= 0.5f }
        }

        // Electric hoist
        val electricTimeA = FloatArray(ccap6) { idx -> time * 0.05f * (idx + 1) }
        val electricFreq = FloatArray(ccap6).also {
            var f = 1f
            for (i in 0 until ccap6) { it[i] = f; f *= 2.2f }
        }
        val electricAmp = FloatArray(ccap6).also {
            var a = 1f
            for (i in 0 until ccap6) { it[i] = a; a *= 0.5f }
        }

        // Fractal hoist
        val fractalTimeA = FloatArray(ccap5) { idx -> time * 0.05f * (idx + 1) }
        val fractalTimeB = FloatArray(ccap5) { idx -> time * 0.04f * (idx + 1) }
        val fractalTimeC = time * 0.03f

        // Geometric hoist
        val ringFreq = c * 3f
        val spokeFreq = c * 2f
        val geometricRingTime = time * rotationSpeed * 2f
        val geometricSpokeTime = time * 0.5f
        val geometricRadialTime = time * rotationSpeed * 3f

        // Starburst hoist
        val starburstSpokeCount = segments.toFloat() * c * 0.5f
        val starburstPulseTime = time * rotationSpeed * 4f
        val starburstGlowTime = time * rotationSpeed * 2f

        // Mosaic hoist
        val mosaicRadialBands = c * 3f + 2f
        val mosaicAngularBands = segments * c * 2f
        val mosaicTimeR = time * 0.08f
        val mosaicTimeA = time * 0.06f

        // Crystalline hoist
        val crystallineCellScale = zoom * 3f
        val crystallineTimeA = time * 0.1f
        val crystallineTimeB = time * 0.12f
        val crystallineTimeC = time * rotationSpeed
        val crystallineLevels = c * 2f + 2f
        val crystallineRingFreq = c * 5f

        // Lace hoist
        val lceCount = c.coerceAtMost(5)
        val laceRFreq = FloatArray(lceCount) { idx -> val layer = idx + 1; zoom * layer * 4f }
        val laceTimeR = FloatArray(lceCount) { idx -> val layer = idx + 1; time * rotationSpeed * 0.3f * layer }
        val laceAFreq = FloatArray(lceCount) { idx -> val layer = idx + 1; layer * 0.5f }
        val laceTimeA = FloatArray(lceCount) { idx -> val layer = idx + 1; time * 0.15f * layer }
        val laceInvLayer = FloatArray(lceCount) { idx -> 1f / (idx + 1f) }

        // Psychedelic hoist
        val psyBandFreq = zoom * c * 4f
        val psySpiralTime = time * rotationSpeed * 2f
        val psyWarpTimeA = time * 0.15f
        val psyWarpTimeB = time * 0.12f
        val psyMorphTimeA = time * 0.05f

        // Detail overlay hoist
        val detailFreq = detail * 4f
        val detailAmp = 0.05f * detail
        val detailTimeA = time * 0.1f
        val detailTimeB = time * 0.08f

        val noise = getNoise(seed)

        // --- Phase 1: compute pattern values at segment-local resolution ---
        val valueBuffer = FloatArray(radSteps * segAngSteps)

        val latch1 = CountDownLatch(THREAD_COUNT)
        for (t in 0 until THREAD_COUNT) {
            executor.execute {
                val riStart = t * radSteps / THREAD_COUNT
                val riEnd = (t + 1) * radSteps / THREAD_COUNT

                for (ri in riStart until riEnd) {
                    val r = rLut[ri]
                    val rowOff = ri * segAngSteps

                    for (ai in 0 until segAngSteps) {
                        val segTheta = segThetaLut[ai]
                        val cosSeg = segThetaCosLut[ai]
                        val sinSeg = segThetaSinLut[ai]
                        val sx = r * cosSeg * zoom
                        val sy = r * sinSeg * zoom

                        var value: Float = when (pat) {
                            PAT_GEOMETRIC -> {
                                val rings = sinLut(r * ringFreq * PI.toFloat() + geometricRingTime)
                                val spokes = sinLut(segTheta * spokeFreq * segments.toFloat() + geometricSpokeTime)
                                val nMod = noise.noise2D(sx * 2f + offsetX + time * 0.15f, sy * 2f + offsetY)
                                val radialWave = sinLut(r * zoom * 8f - geometricRadialTime)
                                rings * 0.4f + spokes * 0.25f + radialWave * 0.2f + nMod * 0.15f
                            }
                            PAT_ORGANIC -> {
                                val warpX = noise.noise2D(sx * 1.5f + offsetX + time * 0.12f, sy * 1.5f + offsetY) * 0.5f
                                val warpY = noise.noise2D(sx * 1.5f + offsetX + 50f, sy * 1.5f + offsetY + time * 0.1f) * 0.5f
                                val wsx = sx + warpX
                                val wsy = sy + warpY
                                var v = 0f
                                for (oct in 0 until c) {
                                    val f = organicFreq[oct]
                                    v += noise.noise2D(
                                        wsx * f * 2f + offsetX + organicTimeA[oct],
                                        wsy * f * 2f + offsetY + organicTimeB[oct]
                                    ) * organicAmp[oct]
                                }
                                v + sinLut(r * zoom * 4f + time * 0.8f) * 0.3f
                            }
                            PAT_CRYSTALLINE -> {
                                val nsx = sx * crystallineCellScale + offsetX + crystallineTimeA
                                val nsy = sy * crystallineCellScale + offsetY + crystallineTimeB
                                val n1 = noise.noise2D(nsx, nsy)
                                val n2 = noise.noise2D(nsx * 2.3f + 30f, nsy * 2.3f + crystallineTimeB)
                                val n3 = sinLut(r * crystallineRingFreq + n1 * 3f + crystallineTimeC)
                                val raw = n1 * 0.5f + n2 * 0.3f + n3 * 0.2f
                                (raw * crystallineLevels).toInt().toFloat() / crystallineLevels
                            }
                            PAT_MANDALA -> {
                                var v = 0f
                                for (harm in 0 until c) {
                                    val radial = cosLut(r * mandalaRFreq[harm] + mandalaTimeR[harm])
                                    val angular = sinLut(segTheta * mandalaAFreq[harm] + mandalaTimeA[harm])
                                    v += radial * angular * mandalaInvHarm[harm]
                                }
                                val petal = abs(cosLut(segTheta * mandalaPetalFreq)).pow(0.5f)
                                v * 0.7f + petal * sinLut(r * zoom * 6f + time * rotationSpeed) * 0.3f
                            }
                            PAT_FRACTAL -> {
                                var fx = sx * 2f + offsetX
                                var fy = sy * 2f + offsetY
                                var v = 0f
                                var amp = 1f
                                for (iter in 0 until ccap5) {
                                    val n = noise.noise2D(fx + fractalTimeA[iter], fy + fractalTimeB[iter])
                                    v += n * amp
                                    fx += n * 1.5f
                                    fy += noise.noise2D(fy + 77f, fx + fractalTimeC) * 1.5f
                                    amp *= 0.6f
                                }
                                v
                            }
                            PAT_STARBURST -> {
                                val spoke = abs(cosLut(segTheta * starburstSpokeCount))
                                val sharpSpoke = powLut2[(spoke * 255f).toInt().coerceIn(0, 255)]
                                val radialPulse = sinLut(r * zoom * 10f - starburstPulseTime)
                                val radialEnvelope = (1f - r * 0.5f).coerceAtLeast(0f)
                                val burst = sharpSpoke * (0.6f + radialPulse * 0.4f) * radialEnvelope
                                val glow = exp(-r * 2f) * sinLut(starburstGlowTime + segTheta * segments) * 0.3f
                                burst + glow
                            }
                            PAT_MOSAIC -> {
                                val rCell = floor(r * zoom * mosaicRadialBands) / mosaicRadialBands
                                val aCell = floor(segTheta / segAngle * mosaicAngularBands) / mosaicAngularBands
                                val cellNoise = noise.noise2D(
                                    rCell * 10f + offsetX + mosaicTimeR,
                                    aCell * 10f + offsetY + mosaicTimeA
                                )
                                val rFrac = (r * zoom * mosaicRadialBands) % 1f
                                val aFrac = (segTheta / segAngle * mosaicAngularBands) % 1f
                                val edgeDist = min(min(rFrac, 1f - rFrac), min(aFrac, 1f - aFrac))
                                val edge = (edgeDist * sharpness * 5f).coerceAtMost(1f)
                                cellNoise * edge
                            }
                            PAT_INTERFERENCE -> {
                                var v = 0f
                                for (src in 0 until ccap6) {
                                    val dx = sx - interfSrcX[src]
                                    val dy = sy - interfSrcY[src]
                                    val dist = sqrt(dx * dx + dy * dy)
                                    v += sinLut(dist * interfDistFreq - interfTimeShift) / (1f + dist * 2f)
                                }
                                v
                            }
                            PAT_ELECTRIC -> {
                                val ex = sx * 3f + offsetX + time * 0.12f
                                val ey = sy * 3f + offsetY + time * 0.1f
                                var v = 0f
                                for (oct in 0 until ccap6) {
                                    val f = electricFreq[oct]
                                    val n = noise.noise2D(ex * f, ey * f + electricTimeA[oct])
                                    v += abs(n) * electricAmp[oct]
                                }
                                val ridged = 1f - v * 0.7f
                                val arc = abs(sinLut(segTheta * segments * 2f + r * zoom * 5f + starburstGlowTime))
                                val arcIdx = (arc * 255f).toInt().coerceIn(0, 255)
                                ridged * 0.6f + powLutS[arcIdx] * 0.4f
                            }
                            PAT_LACE -> {
                                val thetaN = segTheta * segments
                                var v = 0f
                                for (layer in 0 until lceCount) {
                                    val rWave = sinLut(r * laceRFreq[layer] + laceTimeR[layer])
                                    val aWave = cosLut(thetaN * laceAFreq[layer] + laceTimeA[layer])
                                    val thread = abs(rWave * aWave)
                                    val invThread = (1f - thread).coerceIn(0f, 1f)
                                    val invIdx = (invThread * 255f).toInt()
                                    v += powLut3[invIdx] * laceInvLayer[layer]
                                }
                                v * 0.6f
                            }
                            PAT_PSYCHEDELIC -> {
                                val spiralAngle = segTheta * segments + r * zoom * 8f
                                val warp = noise.noise2D(sx * 2f + offsetX + psyWarpTimeA, sy * 2f + offsetY + psyWarpTimeB)
                                val spiral = sinLut(spiralAngle + warp * c * 3f - psySpiralTime)
                                val bands = sinLut(r * psyBandFreq + spiral * 2f + time * rotationSpeed)
                                val morph = noise.noise2D(sx * 0.5f + psyMorphTimeA, sy * 0.5f + offsetY) * 0.4f
                                spiral * 0.4f + bands * 0.4f + morph * 0.2f
                            }
                            else -> noise.noise2D(sx + detailTimeA, sy + detailTimeB)
                        }

                        if (detail > 1) {
                            value += noise.noise2D(
                                sx * detailFreq + offsetX + 200f + detailTimeA,
                                sy * detailFreq + offsetY + 200f + detailTimeB
                            ) * detailAmp
                        }

                        valueBuffer[rowOff + ai] = value
                    }
                }
                latch1.countDown()
            }
        }
        latch1.await()

        // --- Phase 2: apply colour to polar buffer ---
        val polarBuffer = IntArray(radSteps * angSteps)

        if (isIridescent) {
            // Colour depends on full-angle thetaFolded, so iterate full angSteps
            // but reuse the segment-local pattern values.
            val shiftLut = iriShiftLut!!
            val latch2 = CountDownLatch(THREAD_COUNT)
            for (t in 0 until THREAD_COUNT) {
                executor.execute {
                    val riStart = t * radSteps / THREAD_COUNT
                    val riEnd = (t + 1) * radSteps / THREAD_COUNT

                    for (ri in riStart until riEnd) {
                        val vignette = vignetteLut[ri]
                        val valRowOff = ri * segAngSteps
                        val colRowOff = ri * angSteps

                        for (ai in 0 until angSteps) {
                            val aiLocal = ai % segAngSteps
                            val value = valueBuffer[valRowOff + aiLocal]

                            val lutIdx = ((value * 0.5f + 0.5f).coerceIn(0f, 1f) * 1023f).toInt()
                            val normInt = combinedLut[lutIdx]
                            val norm = normInt / 1023f

                            val palVal = ((norm + shiftLut[ai]) % 1f + 1f) % 1f
                            val baseColor = paletteLut[(palVal * 255f).toInt().coerceIn(0, 255)]
                            val red = ((baseColor shr 16 and 0xFF) * vignette).toInt()
                            val green = ((baseColor shr 8 and 0xFF) * vignette).toInt()
                            val blue = ((baseColor and 0xFF) * vignette).toInt()

                            polarBuffer[colRowOff + ai] = (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
                        }
                    }
                    latch2.countDown()
                }
            }
            latch2.await()
        } else {
            // Non-iridescent: colour doesn't depend on thetaFolded. Apply colour at
            // segment-local resolution and replicate rows across segments.
            val latch2 = CountDownLatch(THREAD_COUNT)
            for (t in 0 until THREAD_COUNT) {
                executor.execute {
                    val riStart = t * radSteps / THREAD_COUNT
                    val riEnd = (t + 1) * radSteps / THREAD_COUNT

                    for (ri in riStart until riEnd) {
                        val r = rLut[ri]
                        val vignette = vignetteLut[ri]
                        val valRowOff = ri * segAngSteps
                        val colRowOff = ri * angSteps
                        val depthShift = if (isDepth) r * 0.4f else 0f

                        for (ai in 0 until segAngSteps) {
                            val value = valueBuffer[valRowOff + ai]

                            val lutIdx = ((value * 0.5f + 0.5f).coerceIn(0f, 1f) * 1023f).toInt()
                            val normInt = combinedLut[lutIdx]
                            val norm = normInt / 1023f

                            val palVal = if (isDepth) ((norm + depthShift) % 1f + 1f) % 1f else norm
                            val baseColor = paletteLut[(palVal * 255f).toInt().coerceIn(0, 255)]
                            val red = ((baseColor shr 16 and 0xFF) * vignette).toInt()
                            val green = ((baseColor shr 8 and 0xFF) * vignette).toInt()
                            val blue = ((baseColor and 0xFF) * vignette).toInt()

                            polarBuffer[colRowOff + ai] = (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
                        }

                        // Replicate segment-local colours across remaining segments.
                        for (seg in 1 until segments) {
                            val dstOffset = seg * segAngSteps
                            System.arraycopy(
                                polarBuffer, colRowOff,
                                polarBuffer, colRowOff + dstOffset,
                                segAngSteps
                            )
                        }
                    }
                    latch2.countDown()
                }
            }
            latch2.await()
        }

        // --- Screen fill using cached mapping ---
        val mapping = getOrBuildMapping(w, h, radSteps, angSteps)
        val pixels = IntArray(w * h)

        val mRi0 = mapping.ri0
        val mAi0 = mapping.ai0
        val mFr = mapping.fr
        val mFa = mapping.fa
        val mOob = mapping.outOfBounds

        val latch3 = CountDownLatch(THREAD_COUNT)
        for (t in 0 until THREAD_COUNT) {
            executor.execute {
                val pxStart = t * pixels.size / THREAD_COUNT
                val pxEnd = (t + 1) * pixels.size / THREAD_COUNT

                for (idx in pxStart until pxEnd) {
                    if (mOob[idx]) {
                        pixels[idx] = 0xFF000000.toInt()
                        continue
                    }

                    val ri0 = mRi0[idx].toInt() and 0xFFFF
                    val ai0 = mAi0[idx].toInt() and 0xFFFF
                    val ai1 = if (ai0 + 1 >= angSteps) 0 else ai0 + 1
                    val ri1 = ri0 + 1

                    val frI = mFr[idx].toInt() and 0xFF
                    val faI = mFa[idx].toInt() and 0xFF
                    val invFrI = 255 - frI
                    val invFaI = 255 - faI

                    val w00 = invFrI * invFaI
                    val w10 = frI * invFaI
                    val w01 = invFrI * faI
                    val w11 = frI * faI

                    val c00 = polarBuffer[ri0 * angSteps + ai0]
                    val c10 = polarBuffer[ri1 * angSteps + ai0]
                    val c01 = polarBuffer[ri0 * angSteps + ai1]
                    val c11 = polarBuffer[ri1 * angSteps + ai1]

                    val red = ((c00 shr 16 and 0xFF) * w00 + (c10 shr 16 and 0xFF) * w10 +
                            (c01 shr 16 and 0xFF) * w01 + (c11 shr 16 and 0xFF) * w11) / 65025
                    val green = ((c00 shr 8 and 0xFF) * w00 + (c10 shr 8 and 0xFF) * w10 +
                            (c01 shr 8 and 0xFF) * w01 + (c11 shr 8 and 0xFF) * w11) / 65025
                    val blue = ((c00 and 0xFF) * w00 + (c10 and 0xFF) * w10 +
                            (c01 and 0xFF) * w01 + (c11 and 0xFF) * w11) / 65025

                    pixels[idx] = (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
                }
                latch3.countDown()
            }
        }
        latch3.await()

        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
        canvas.drawBitmap(bitmap, 0f, 0f, null)
    }

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float {
        val complexity = (params["complexity"] as? Number)?.toFloat() ?: 3f
        return when (quality) {
            Quality.DRAFT -> complexity / 20f
            Quality.BALANCED -> complexity / 10f
            Quality.ULTRA -> complexity / 7f
        }.coerceIn(0.1f, 1f)
    }
}
