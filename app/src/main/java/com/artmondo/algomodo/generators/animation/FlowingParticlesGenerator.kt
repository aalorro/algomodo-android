package com.artmondo.algomodo.generators.animation

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import com.artmondo.algomodo.core.rng.SeededRNG
import com.artmondo.algomodo.core.rng.SimplexNoise
import com.artmondo.algomodo.data.palettes.Palette
import com.artmondo.algomodo.generators.Generator
import com.artmondo.algomodo.generators.ParamGroup
import com.artmondo.algomodo.generators.Parameter
import com.artmondo.algomodo.generators.Quality
import kotlin.math.*

/**
 * Particles flowing through a divergence-free curl-noise vector field.
 *
 * A precomputed 72×72 grid stores sin/cos of curl angles from 2-octave FBM
 * simplex noise. Each particle samples the grid via bilinear interpolation,
 * giving O(1) flow direction lookups. Trails come from offscreen accumulation
 * with semi-transparent fading.
 *
 * 6 flow patterns (flow, swirl, split, gravity, pulse-wave, highway),
 * 5 color modes (angle, speed, zone, stripes, pulse), turbulence jitter,
 * and rhythmic pulse modulation.
 */
class FlowingParticlesGenerator : Generator {

    override val id = "flowing-particles"
    override val family = "animation"
    override val styleName = "Flowing Particles"
    override val definition = "Animated particles flowing through a divergence-free curl-noise vector field."
    override val algorithmNotes =
        "Curl noise (divergence-free) ensures particles circulate uniformly without clustering " +
        "at FBM convergence sinks. A precomputed 72×72 grid makes per-frame sampling O(1). " +
        "Trails are rendered via offscreen accumulation with semi-transparent overlay fading. " +
        "Patterns add swirl, split, gravity, pulse-wave, and highway behaviors."
    override val supportsVector = false
    override val supportsAnimation = true

    companion object {
        private const val GRID = 72
        private const val PATTERN_FLOW = 0
        private const val PATTERN_SWIRL = 1
        private const val PATTERN_SPLIT = 2
        private const val PATTERN_GRAVITY = 3
        private const val PATTERN_PULSE_WAVE = 4
        private const val PATTERN_HIGHWAY = 5
        private const val COLOR_ANGLE = 0
        private const val COLOR_SPEED = 1
        private const val COLOR_ZONE = 2
        private const val COLOR_STRIPES = 3
        private const val COLOR_PULSE = 4
        private const val WARMUP = 60
    }

    override val parameterSchema = listOf(
        Parameter.NumberParam(
            name = "Particles", key = "particleCount", group = ParamGroup.COMPOSITION,
            help = "Number of flowing particles",
            min = 100f, max = 5000f, step = 100f, default = 2000f
        ),
        Parameter.NumberParam(
            name = "Attractors", key = "attractorCount", group = ParamGroup.COMPOSITION,
            help = "Glowing bodies that orbit and warp the flow field",
            min = 0f, max = 6f, step = 1f, default = 0f
        ),
        Parameter.NumberParam(
            name = "Flow Scale", key = "flowScale", group = ParamGroup.GEOMETRY,
            help = "Size of flow field patterns",
            min = 0.5f, max = 5f, step = 0.5f, default = 2f
        ),
        Parameter.SelectParam(
            name = "Shape", key = "objectType", group = ParamGroup.GEOMETRY,
            help = "Shape rendered for each particle",
            options = listOf("circle", "square", "triangle", "line", "mixed"),
            default = "circle"
        ),
        Parameter.NumberParam(
            name = "Speed", key = "flowSpeed", group = ParamGroup.FLOW_MOTION,
            help = "Particle flow speed",
            min = 0.5f, max = 5f, step = 0.5f, default = 2f
        ),
        Parameter.NumberParam(
            name = "Size", key = "particleSize", group = ParamGroup.TEXTURE,
            help = "Base particle size",
            min = 0.5f, max = 10f, step = 0.5f, default = 3f
        ),
        Parameter.NumberParam(
            name = "Size Variance", key = "sizeVariance", group = ParamGroup.TEXTURE,
            help = "Random variation in individual particle sizes",
            min = 0f, max = 1f, step = 0.1f, default = 0.3f
        ),
        Parameter.NumberParam(
            name = "Trail", key = "trailLength", group = ParamGroup.TEXTURE,
            help = "Motion blur amount (0 = none, 1 = permanent)",
            min = 0f, max = 1f, step = 0.1f, default = 0.5f
        ),
        Parameter.SelectParam(
            name = "Pattern", key = "pattern", group = ParamGroup.FLOW_MOTION,
            help = "flow: pure curl | swirl: spiral toward center | split: diverge from center | gravity: fall with drift | pulse-wave: radial waves | highway: alternating lanes",
            options = listOf("flow", "swirl", "split", "gravity", "pulse-wave", "highway"),
            default = "flow"
        ),
        Parameter.SelectParam(
            name = "Color Mode", key = "colorMode", group = ParamGroup.COLOR,
            help = "angle: flow direction | speed: velocity magnitude | zone: spatial grid | stripes: diagonal bands | pulse: time-cycling",
            options = listOf("angle", "speed", "zone", "stripes", "pulse"),
            default = "angle"
        ),
        Parameter.NumberParam(
            name = "Turbulence", key = "turbulence", group = ParamGroup.FLOW_MOTION,
            help = "Adds chaotic jitter to flow",
            min = 0f, max = 2f, step = 0.1f, default = 0f
        ),
        Parameter.NumberParam(
            name = "Pulse", key = "pulse", group = ParamGroup.FLOW_MOTION,
            help = "Rhythmic size/opacity pulsing",
            min = 0f, max = 1f, step = 0.1f, default = 0f
        )
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "particleCount" to 2000f,
        "attractorCount" to 0f,
        "flowScale" to 2f,
        "objectType" to "circle",
        "flowSpeed" to 2f,
        "particleSize" to 3f,
        "sizeVariance" to 0.3f,
        "trailLength" to 0.5f,
        "pattern" to "flow",
        "colorMode" to "angle",
        "turbulence" to 0f,
        "pulse" to 0f
    )

    // ---- Curl-noise flow field cache ----
    private var fieldSinA: FloatArray? = null
    private var fieldCosA: FloatArray? = null
    private var fieldSeed = -1
    private var fieldScale = -1f

    // ---- Particle state cache ----
    @Volatile private var simCache: SimCache? = null

    // ---- Attractor data cache ----
    private var attrData: AttrData? = null
    private var attrCacheSeed = -1
    private var attrCacheCount = -1

    // ---- Offscreen accumulation bitmap ----
    @Volatile private var offBitmap: Bitmap? = null
    private var offW = 0
    private var offH = 0

    // Reusable Path for shape drawing
    private val shapePath = Path()

    private class SimCache(
        val seed: Int,
        val count: Int,
        val objectType: String,
        val sizeVariance: Float,
        val w: Float, val h: Float,
        var stepCount: Int,
        val px: FloatArray,
        val py: FloatArray,
        val vx: FloatArray,
        val vy: FloatArray,
        val shape: IntArray,
        val sizeMult: FloatArray
    )

    private class AttrData(
        val count: Int,
        val baseX: FloatArray,
        val baseY: FloatArray,
        val orbitR: FloatArray,
        val orbitSpeed: FloatArray,
        val phase: FloatArray,
        val strength: FloatArray,
        val radius: FloatArray,
        val colorIdx: IntArray,
        val curX: FloatArray,
        val curY: FloatArray
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
        val w = bitmap.width.toFloat()
        val h = bitmap.height.toFloat()
        val wi = bitmap.width
        val hi = bitmap.height

        val count = ((params["particleCount"] as? Number)?.toInt() ?: 2000).let {
            when (quality) {
                Quality.DRAFT -> (it * 0.4f).toInt()
                Quality.BALANCED -> it
                Quality.ULTRA -> (it * 1.5f).toInt()
            }
        }
        val flowScale = (params["flowScale"] as? Number)?.toFloat() ?: 2f
        val flowSpeed = (params["flowSpeed"] as? Number)?.toFloat() ?: 2f
        val particleSize = (params["particleSize"] as? Number)?.toFloat() ?: 3f
        val sizeVariance = (params["sizeVariance"] as? Number)?.toFloat() ?: 0.3f
        val trailLength = (params["trailLength"] as? Number)?.toFloat() ?: 0.5f
        val attractorCount = (params["attractorCount"] as? Number)?.toInt() ?: 0
        val objectType = (params["objectType"] as? String) ?: "circle"
        val pattern = (params["pattern"] as? String) ?: "flow"
        val colorMode = (params["colorMode"] as? String) ?: "angle"
        val turbulence = (params["turbulence"] as? Number)?.toFloat() ?: 0f
        val pulse = (params["pulse"] as? Number)?.toFloat() ?: 0f

        val patternId = when (pattern) {
            "swirl" -> PATTERN_SWIRL; "split" -> PATTERN_SPLIT
            "gravity" -> PATTERN_GRAVITY; "pulse-wave" -> PATTERN_PULSE_WAVE
            "highway" -> PATTERN_HIGHWAY; else -> PATTERN_FLOW
        }
        val colorId = when (colorMode) {
            "speed" -> COLOR_SPEED; "zone" -> COLOR_ZONE
            "stripes" -> COLOR_STRIPES; "pulse" -> COLOR_PULSE
            else -> COLOR_ANGLE
        }
        val fixedShape = when (objectType) {
            "circle" -> 0; "square" -> 1; "triangle" -> 2; "line" -> 3; else -> -1
        }

        val noise = SimplexNoise(seed)
        val colors = palette.colorInts()
        val nColors = colors.size
        val baseSpeed = flowSpeed * 0.5f
        val halfW = w * 0.5f
        val halfH = h * 0.5f
        val dt = 0.016f
        val totalSteps = (time / dt).toInt().coerceAtLeast(1)
        val fadeAlpha = ((1f - trailLength) * 255f).toInt().coerceIn(0, 255)

        // ---- Build/cache curl-noise flow field ----
        if (fieldSeed != seed || fieldScale != flowScale) {
            buildFlowField(noise, flowScale)
            fieldSeed = seed; fieldScale = flowScale
        }
        val sinA = fieldSinA!!
        val cosA = fieldCosA!!

        // ---- Build/cache attractor data ----
        val attr: AttrData
        if (attrCacheSeed != seed || attrCacheCount != attractorCount) {
            attr = buildAttractors(seed, attractorCount, w, h, min(w, h), nColors)
            attrData = attr; attrCacheSeed = seed; attrCacheCount = attractorCount
        } else {
            attr = attrData!!
        }

        // ---- Offscreen bitmap management ----
        var off = offBitmap
        var needClear = false
        if (off == null || offW != wi || offH != hi) {
            off?.recycle()
            off = Bitmap.createBitmap(wi, hi, Bitmap.Config.ARGB_8888)
            offBitmap = off; offW = wi; offH = hi
            needClear = true
        }
        val oc = Canvas(off)

        // ---- Drawing paint ----
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }

        // ---- Resolve particle state ----
        val cached = simCache
        val sim: SimCache

        if (!needClear && cached != null &&
            cached.seed == seed && cached.count == count &&
            cached.objectType == objectType &&
            cached.sizeVariance == sizeVariance &&
            cached.w == w && cached.h == h &&
            totalSteps >= cached.stepCount &&
            totalSteps - cached.stepCount < 120
        ) {
            // ---- Incremental update ----
            sim = cached
            val stepsNeeded = totalSteps - sim.stepCount
            for (s in 0 until stepsNeeded) {
                val t = (sim.stepCount + s) * dt
                if (fadeAlpha > 0) oc.drawColor(Color.argb(fadeAlpha, 0, 0, 0))
                advanceOneStep(sim, t, baseSpeed, patternId, turbulence, noise,
                    halfW, halfH, w, h, sinA, cosA, attr)
                drawAllParticles(oc, sim, paint, shapePath, colors, nColors, colorId,
                    particleSize, pulse, t, w, h, baseSpeed)
            }
            sim.stepCount = totalSteps
        } else {
            // ---- Cold start ----
            sim = SimCache(
                seed = seed, count = count,
                objectType = objectType, sizeVariance = sizeVariance,
                w = w, h = h, stepCount = 0,
                px = FloatArray(count), py = FloatArray(count),
                vx = FloatArray(count), vy = FloatArray(count),
                shape = IntArray(count), sizeMult = FloatArray(count)
            )

            // Grid+jitter initialization for uniform coverage
            val rng = SeededRNG(seed)
            val cols = ceil(sqrt(count.toFloat() * (w / h))).toInt().coerceAtLeast(1)
            val rows = ceil(count.toFloat() / cols).toInt().coerceAtLeast(1)
            val cellW = w / cols
            val cellH = h / rows
            for (i in 0 until count) {
                val col = i % cols
                val row = i / cols
                sim.px[i] = col * cellW + rng.random() * cellW
                sim.py[i] = row * cellH + rng.random() * cellH
                sim.shape[i] = if (fixedShape >= 0) fixedShape else (rng.random() * 4f).toInt().coerceIn(0, 3)
                sim.sizeMult[i] = 1f - sizeVariance * 0.5f + rng.random() * sizeVariance
            }

            // Coarse skip to near the warmup window (no drawing)
            val warmupStart = (totalSteps - WARMUP).coerceAtLeast(0)
            for (s in 0 until warmupStart) {
                advanceOneStep(sim, s * dt, baseSpeed, patternId, turbulence, noise,
                    halfW, halfH, w, h, sinA, cosA, attr)
            }

            // Clear offscreen and draw warmup frames
            off.eraseColor(Color.BLACK)
            for (s in warmupStart until totalSteps) {
                val t = s * dt
                if (fadeAlpha > 0) oc.drawColor(Color.argb(fadeAlpha, 0, 0, 0))
                advanceOneStep(sim, t, baseSpeed, patternId, turbulence, noise,
                    halfW, halfH, w, h, sinA, cosA, attr)
                drawAllParticles(oc, sim, paint, shapePath, colors, nColors, colorId,
                    particleSize, pulse, t, w, h, baseSpeed)
            }
            sim.stepCount = totalSteps
        }

        simCache = sim

        // ---- Copy offscreen to output canvas ----
        canvas.drawBitmap(off, 0f, 0f, null)

        // ---- Draw attractor glows on top (not accumulated in offscreen) ----
        if (attr.count > 0) {
            updateAttractorPositions(attr, totalSteps * dt)
            drawAttractorGlows(canvas, paint, attr, colors, min(w, h))
        }
    }

    // ──────────────────────────── Flow field ────────────────────────────

    private fun fbm2(noise: SimplexNoise, x: Float, y: Float): Float =
        noise.noise2D(x, y) + 0.5f * noise.noise2D(x * 2f, y * 2f)

    private fun buildFlowField(noise: SimplexNoise, flowScale: Float) {
        val n = GRID * GRID
        val sa = FloatArray(n)
        val ca = FloatArray(n)
        val eps = 0.008f
        val gm1 = (GRID - 1).toFloat()
        for (gy in 0 until GRID) {
            for (gx in 0 until GRID) {
                val nx = (gx / gm1 - 0.5f) * flowScale + 5f
                val ny = (gy / gm1 - 0.5f) * flowScale + 5f
                val n0 = fbm2(noise, nx, ny)
                val n1 = fbm2(noise, nx + eps, ny)
                val n2 = fbm2(noise, nx, ny + eps)
                val dnx = (n1 - n0) / eps
                val dny = (n2 - n0) / eps
                val a = atan2(dny, -dnx)
                val i = gy * GRID + gx
                sa[i] = sin(a)
                ca[i] = cos(a)
            }
        }
        fieldSinA = sa; fieldCosA = ca
    }

    private fun sampleAngle(
        sinA: FloatArray, cosA: FloatArray,
        px: Float, py: Float, w: Float, h: Float, timeDrift: Float
    ): Float {
        val gxf = (px / w) * (GRID - 1)
        val gyf = (py / h) * (GRID - 1)
        val gx0 = gxf.toInt().coerceIn(0, GRID - 2)
        val gy0 = gyf.toInt().coerceIn(0, GRID - 2)
        val fx = gxf - gx0; val fy = gyf - gy0
        val omfx = 1f - fx; val omfy = 1f - fy
        val i00 = gy0 * GRID + gx0; val i10 = i00 + 1
        val i01 = i00 + GRID; val i11 = i01 + 1
        val s = omfy * (omfx * sinA[i00] + fx * sinA[i10]) + fy * (omfx * sinA[i01] + fx * sinA[i11])
        val c = omfy * (omfx * cosA[i00] + fx * cosA[i10]) + fy * (omfx * cosA[i01] + fx * cosA[i11])
        return atan2(s, c) + timeDrift
    }

    // ──────────────────────────── Attractors ────────────────────────────

    private fun buildAttractors(
        seed: Int, count: Int, w: Float, h: Float, dim: Float, nColors: Int
    ): AttrData {
        val rng = SeededRNG(seed + 99991)
        val data = AttrData(
            count = count,
            baseX = FloatArray(count), baseY = FloatArray(count),
            orbitR = FloatArray(count), orbitSpeed = FloatArray(count),
            phase = FloatArray(count), strength = FloatArray(count),
            radius = FloatArray(count), colorIdx = IntArray(count),
            curX = FloatArray(count), curY = FloatArray(count)
        )
        for (i in 0 until count) {
            data.baseX[i] = w * (0.2f + rng.random() * 0.6f)
            data.baseY[i] = h * (0.2f + rng.random() * 0.6f)
            data.orbitR[i] = dim * (0.04f + rng.random() * 0.10f)
            data.orbitSpeed[i] = (0.3f + rng.random() * 0.8f) * if (rng.random() > 0.5f) 1f else -1f
            data.phase[i] = rng.random() * 2f * PI.toFloat()
            data.strength[i] = 0.6f + rng.random() * 1.6f
            data.radius[i] = dim * (0.1f + rng.random() * 0.12f)
            data.colorIdx[i] = (rng.random() * nColors).toInt().coerceIn(0, nColors - 1)
        }
        return data
    }

    private fun updateAttractorPositions(attr: AttrData, time: Float) {
        for (i in 0 until attr.count) {
            val p = attr.phase[i] + time * attr.orbitSpeed[i] * 0.01f
            attr.curX[i] = attr.baseX[i] + cos(p) * attr.orbitR[i]
            attr.curY[i] = attr.baseY[i] + sin(p) * attr.orbitR[i]
        }
    }

    private fun drawAttractorGlows(
        canvas: Canvas, paint: Paint, attr: AttrData, colors: List<Int>, dim: Float
    ) {
        paint.style = Paint.Style.FILL
        for (i in 0 until attr.count) {
            val c = colors[attr.colorIdx[i] % colors.size]
            val cr = Color.red(c); val cg = Color.green(c); val cb = Color.blue(c)
            for (ring in 4 downTo 1) {
                paint.color = Color.argb((14 * ring).coerceAtMost(255), cr, cg, cb)
                canvas.drawCircle(attr.curX[i], attr.curY[i], attr.radius[i] * ring * 0.35f, paint)
            }
            paint.color = Color.argb(234, 255, 255, 255)
            canvas.drawCircle(attr.curX[i], attr.curY[i], attr.radius[i] * 0.12f, paint)
        }
    }

    // ──────────────────────────── Particle simulation ────────────────────

    private fun advanceOneStep(
        sim: SimCache, stepTime: Float, baseSpeed: Float,
        patternId: Int, turbulence: Float, noise: SimplexNoise,
        halfW: Float, halfH: Float, w: Float, h: Float,
        sinA: FloatArray, cosA: FloatArray, attr: AttrData
    ) {
        val timeDrift = stepTime * 0.1f
        if (attr.count > 0) updateAttractorPositions(attr, stepTime)

        for (i in 0 until sim.count) {
            val angle = sampleAngle(sinA, cosA, sim.px[i], sim.py[i], w, h, timeDrift)
            var vx = cos(angle) * baseSpeed
            var vy = sin(angle) * baseSpeed

            when (patternId) {
                PATTERN_SWIRL -> {
                    val dx = sim.px[i] - halfW; val dy = sim.py[i] - halfH
                    val dist = sqrt(dx * dx + dy * dy) + 1f
                    val str = baseSpeed * 0.6f / (1f + dist * 0.001f)
                    vx += -dy / dist * str
                    vy += dx / dist * str
                }
                PATTERN_SPLIT -> {
                    val dy = sim.py[i] - halfH
                    vy += if (dy > 0) baseSpeed * 0.4f else -baseSpeed * 0.4f
                    vx *= 1.3f
                }
                PATTERN_GRAVITY -> {
                    vy += baseSpeed * 0.5f
                    vx += noise.noise2D(sim.px[i] * 0.003f, stepTime * 0.02f) * baseSpeed * 0.8f
                }
                PATTERN_PULSE_WAVE -> {
                    val dx = sim.px[i] - halfW; val dy = sim.py[i] - halfH
                    val dist = sqrt(dx * dx + dy * dy) + 1f
                    val wave = sin(dist * 0.02f - stepTime * 0.08f) * baseSpeed * 0.7f
                    vx += (dx / dist) * wave
                    vy += (dy / dist) * wave
                }
                PATTERN_HIGHWAY -> {
                    val lane = (sim.py[i] / h * 6f).toInt()
                    val dir = if (lane % 2 == 0) 1f else -1f
                    vx = baseSpeed * 1.8f * dir
                    vy *= 0.3f
                    vy += noise.noise2D(
                        sim.px[i] * 0.005f,
                        sim.py[i] * 0.01f + stepTime * 0.01f
                    ) * baseSpeed * 0.4f
                }
            }

            if (turbulence > 0f) {
                val tn = stepTime * 0.05f
                vx += noise.noise2D(sim.px[i] * 0.008f + tn, sim.py[i] * 0.008f) * baseSpeed * turbulence
                vy += noise.noise2D(sim.px[i] * 0.008f, sim.py[i] * 0.008f + tn) * baseSpeed * turbulence
            }

            sim.vx[i] = vx
            sim.vy[i] = vy

            for (a in 0 until attr.count) {
                val dx = attr.curX[a] - sim.px[i]
                val dy = attr.curY[a] - sim.py[i]
                val dist = sqrt(dx * dx + dy * dy)
                if (dist < attr.radius[a] && dist > 1f) {
                    val force = attr.strength[a] * (1f - dist / attr.radius[a]) * baseSpeed * 0.4f
                    sim.vx[i] += (dx / dist) * force
                    sim.vy[i] += (dy / dist) * force
                }
            }

            sim.px[i] += sim.vx[i]
            sim.py[i] += sim.vy[i]

            if (sim.px[i] < 0f) sim.px[i] += w; if (sim.px[i] >= w) sim.px[i] -= w
            if (sim.py[i] < 0f) sim.py[i] += h; if (sim.py[i] >= h) sim.py[i] -= h
        }
    }

    // ──────────────────────────── Particle rendering ────────────────────

    private fun drawAllParticles(
        oc: Canvas, sim: SimCache, paint: Paint, path: Path,
        colors: List<Int>, nColors: Int, colorId: Int,
        particleSize: Float, pulse: Float, stepTime: Float,
        w: Float, h: Float, baseSpeed: Float
    ) {
        val twoPi = 2f * PI.toFloat()
        for (i in 0 until sim.count) {
            val colorIdx = when (colorId) {
                COLOR_SPEED -> {
                    val spd = sqrt(sim.vx[i] * sim.vx[i] + sim.vy[i] * sim.vy[i])
                    ((spd / (baseSpeed * 3f)) * nColors).toInt().coerceIn(0, nColors - 1)
                }
                COLOR_ZONE -> {
                    val zx = (sim.px[i] / w * 3f).toInt()
                    val zy = (sim.py[i] / h * 3f).toInt()
                    ((zx + zy * 3) % nColors + nColors) % nColors
                }
                COLOR_STRIPES -> {
                    val stripe = ((sim.px[i] + sim.py[i]) * 0.01f + stepTime * 0.02f).toInt()
                    ((stripe % nColors) + nColors) % nColors
                }
                COLOR_PULSE -> {
                    val pc = (sin(stepTime * 0.05f + sim.px[i] * 0.005f) + 1f) * 0.5f
                    (pc * nColors).toInt() % nColors
                }
                else -> { // angle
                    val a = atan2(sim.vy[i], sim.vx[i])
                    val norm = ((a % twoPi) + twoPi) % twoPi
                    ((norm / twoPi) * nColors).toInt() % nColors
                }
            }

            var sz = particleSize * sim.sizeMult[i]
            var alpha = 255
            if (pulse > 0f) {
                val pf = 0.5f + 0.5f * sin(stepTime * 0.06f + sim.px[i] * 0.003f + sim.py[i] * 0.003f)
                sz *= 1f - pulse * 0.5f + pulse * pf
                alpha = ((1f - pulse * 0.4f + pulse * 0.4f * pf) * 255f).toInt()
            }

            val c = colors[colorIdx.coerceIn(0, nColors - 1)]
            val color = Color.argb(alpha, Color.red(c), Color.green(c), Color.blue(c))
            val flowAngle = atan2(sim.vy[i], sim.vx[i])

            drawShape(oc, paint, path, sim.px[i], sim.py[i], sz, flowAngle, sim.shape[i], color)
        }
    }

    private fun drawShape(
        canvas: Canvas, paint: Paint, path: Path,
        x: Float, y: Float, size: Float, angle: Float,
        shapeIdx: Int, color: Int
    ) {
        paint.color = color
        when (shapeIdx) {
            0 -> { // circle
                paint.style = Paint.Style.FILL
                canvas.drawCircle(x, y, size, paint)
            }
            1 -> { // square rotated to face velocity
                paint.style = Paint.Style.FILL
                val a = angle + PI.toFloat() * 0.25f
                val cosA = cos(a); val sinA = sin(a)
                path.reset()
                path.moveTo(x + cosA * size - sinA * (-size), y + sinA * size + cosA * (-size))
                path.lineTo(x + cosA * size - sinA * size, y + sinA * size + cosA * size)
                path.lineTo(x + cosA * (-size) - sinA * size, y + sinA * (-size) + cosA * size)
                path.lineTo(x + cosA * (-size) - sinA * (-size), y + sinA * (-size) + cosA * (-size))
                path.close()
                canvas.drawPath(path, paint)
            }
            2 -> { // triangle pointing in flow direction
                paint.style = Paint.Style.FILL
                val cosA = cos(angle); val sinA = sin(angle)
                val tipLen = size * 1.8f; val backLen = size; val hw = size * 0.9f
                path.reset()
                path.moveTo(x + cosA * tipLen, y + sinA * tipLen)
                path.lineTo(x - cosA * backLen + sinA * hw, y - sinA * backLen - cosA * hw)
                path.lineTo(x - cosA * backLen - sinA * hw, y - sinA * backLen + cosA * hw)
                path.close()
                canvas.drawPath(path, paint)
            }
            3 -> { // line segment aligned to velocity
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = (size * 0.45f).coerceAtLeast(0.5f)
                paint.strokeCap = Paint.Cap.ROUND
                val dx = cos(angle) * size * 2f
                val dy = sin(angle) * size * 2f
                canvas.drawLine(x - dx, y - dy, x + dx, y + dy, paint)
            }
        }
    }

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float {
        val count = (params["particleCount"] as? Number)?.toFloat() ?: 2000f
        val attractors = (params["attractorCount"] as? Number)?.toFloat() ?: 0f
        return ((count * 2f + attractors * 50f) / 10000f).coerceIn(0.2f, 1f)
    }
}
