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
 * with semi-transparent fading. Particles respawn at random positions when
 * they exceed their max age, keeping the canvas populated.
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
            min = 100f, max = 2500f, step = 100f, default = 2000f
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

    // ---- Dimension-keyed render state cache (thread-safe for concurrent live + export) ----
    private val stateCache = object : LinkedHashMap<Long, RenderState>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, RenderState>?): Boolean {
            if (size > 3) { eldest?.value?.offBitmap?.recycle(); return true }
            return false
        }
    }

    private class RenderState(
        var sim: SimCache? = null,
        var offBitmap: Bitmap? = null
    )

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
        val sizeMult: FloatArray,
        val age: IntArray,             // per-particle step age
        val maxAge: IntArray           // respawn after this many steps
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

        // ---- Dimension-keyed render state (safe for concurrent live + export) ----
        val dimKey = (wi.toLong() shl 32) or hi.toLong()
        val state: RenderState
        synchronized(stateCache) {
            state = stateCache.getOrPut(dimKey) { RenderState() }
        }

        var off = state.offBitmap
        var needClear = false
        if (off == null || off.isRecycled || off.width != wi || off.height != hi) {
            off?.recycle()
            off = Bitmap.createBitmap(wi, hi, Bitmap.Config.ARGB_8888)
            state.offBitmap = off
            needClear = true
        }
        val oc = Canvas(off)

        // ---- Drawing paint and path (local — Path is not thread-safe) ----
        val paint = Paint().apply { style = Paint.Style.FILL }
        val path = Path()

        // ---- Resolve particle state ----
        val cached = state.sim
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
                    halfW, halfH, w, h, sinA, cosA, fixedShape, sizeVariance, nColors)
                drawAllParticles(oc, sim, paint, path, colors, nColors, colorId,
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
                shape = IntArray(count), sizeMult = FloatArray(count),
                age = IntArray(count), maxAge = IntArray(count)
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
                sim.maxAge[i] = 200 + (rng.random() * 400f).toInt()
            }

            // Coarse skip to near the warmup window (no drawing)
            val warmupStart = (totalSteps - WARMUP).coerceAtLeast(0)
            for (s in 0 until warmupStart) {
                advanceOneStep(sim, s * dt, baseSpeed, patternId, turbulence, noise,
                    halfW, halfH, w, h, sinA, cosA, fixedShape, sizeVariance, nColors)
            }

            // Clear offscreen and draw warmup frames
            off.eraseColor(Color.BLACK)
            for (s in warmupStart until totalSteps) {
                val t = s * dt
                if (fadeAlpha > 0) oc.drawColor(Color.argb(fadeAlpha, 0, 0, 0))
                advanceOneStep(sim, t, baseSpeed, patternId, turbulence, noise,
                    halfW, halfH, w, h, sinA, cosA, fixedShape, sizeVariance, nColors)
                drawAllParticles(oc, sim, paint, path, colors, nColors, colorId,
                    particleSize, pulse, t, w, h, baseSpeed)
            }
            sim.stepCount = totalSteps
        }

        state.sim = sim

        // ---- Copy offscreen to output canvas ----
        canvas.drawBitmap(off, 0f, 0f, null)
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

    private inline fun sampleAngle(
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

    // ──────────────────────────── Particle simulation ────────────────────

    private fun advanceOneStep(
        sim: SimCache, stepTime: Float, baseSpeed: Float,
        patternId: Int, turbulence: Float, noise: SimplexNoise,
        halfW: Float, halfH: Float, w: Float, h: Float,
        sinA: FloatArray, cosA: FloatArray,
        fixedShape: Int, sizeVariance: Float, nColors: Int
    ) {
        val timeDrift = stepTime * 0.1f

        for (i in 0 until sim.count) {
            val angle = sampleAngle(sinA, cosA, sim.px[i], sim.py[i], w, h, timeDrift)
            var vx = cos(angle) * baseSpeed
            var vy = sin(angle) * baseSpeed

            // Pattern forces override curl flow (curl becomes texture, not primary)
            when (patternId) {
                PATTERN_SWIRL -> {
                    val dx = sim.px[i] - halfW; val dy = sim.py[i] - halfH
                    val dist = sqrt(dx * dx + dy * dy).coerceAtLeast(1f)
                    val spd = baseSpeed * 1.5f
                    // 80% tangential (CCW) + 20% inward pull = spiral
                    vx = (-dy / dist * 0.8f - dx / dist * 0.2f) * spd + vx * 0.2f
                    vy = (dx / dist * 0.8f - dy / dist * 0.2f) * spd + vy * 0.2f
                }
                PATTERN_SPLIT -> {
                    val dy = sim.py[i] - halfH
                    val sign = if (dy >= 0f) 1f else -1f
                    // Strong vertical divergence from center
                    vy = sign * baseSpeed * 1.5f + vy * 0.15f
                    vx *= 0.3f // curl provides horizontal variation
                }
                PATTERN_GRAVITY -> {
                    // Strong downward cascade with horizontal noise drift
                    vx = noise.noise2D(sim.px[i] * 0.003f, stepTime * 0.02f) *
                            baseSpeed * 0.8f + vx * 0.15f
                    vy = baseSpeed * 2f + vy * 0.1f
                }
                PATTERN_PULSE_WAVE -> {
                    val dx = sim.px[i] - halfW; val dy = sim.py[i] - halfH
                    val dist = sqrt(dx * dx + dy * dy).coerceAtLeast(1f)
                    val wave = sin(dist * 0.025f - stepTime * 0.12f) * baseSpeed * 2f
                    val nx = dx / dist; val ny = dy / dist
                    // Radial waves: push out then pull in
                    vx = nx * wave + vx * 0.15f
                    vy = ny * wave + vy * 0.15f
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
            sim.px[i] += vx
            sim.py[i] += vy

            // Toroidal wrap
            if (sim.px[i] < 0f) sim.px[i] += w; if (sim.px[i] >= w) sim.px[i] -= w
            if (sim.py[i] < 0f) sim.py[i] += h; if (sim.py[i] >= h) sim.py[i] -= h

            sim.age[i]++

            // Respawn when max age exceeded — keeps canvas uniformly populated
            if (sim.age[i] > sim.maxAge[i]) {
                sim.px[i] = (Math.random() * w).toFloat()
                sim.py[i] = (Math.random() * h).toFloat()
                sim.vx[i] = 0f; sim.vy[i] = 0f
                sim.age[i] = 0
                sim.maxAge[i] = 200 + (Math.random() * 400).toInt()
                sim.shape[i] = if (fixedShape >= 0) fixedShape else (Math.random() * 4).toInt().coerceIn(0, 3)
                sim.sizeMult[i] = 1f - sizeVariance * 0.5f + (Math.random() * sizeVariance).toFloat()
            }
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
        paint.style = Paint.Style.FILL  // All shapes use FILL (lines are filled rects)
        val allCircles = sim.objectType == "circle"
        val allLines = sim.objectType == "line"

        for (i in 0 until sim.count) {
            if (sim.age[i] < 2) continue // skip freshly respawned

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
            val color = if (alpha == 255) c or 0xFF000000.toInt()
                        else Color.argb(alpha, Color.red(c), Color.green(c), Color.blue(c))

            if (allCircles) {
                paint.color = color
                oc.drawCircle(sim.px[i], sim.py[i], sz, paint)
            } else if (allLines) {
                // Fast path: filled rectangles instead of stroked lines
                paint.color = color
                val angle = atan2(sim.vy[i], sim.vx[i])
                val cosA = cos(angle); val sinA = sin(angle)
                val hl = sz * 2f
                val hw = (sz * 0.22f).coerceAtLeast(0.25f)
                path.reset()
                path.moveTo(sim.px[i] - cosA * hl + sinA * hw, sim.py[i] - sinA * hl - cosA * hw)
                path.lineTo(sim.px[i] + cosA * hl + sinA * hw, sim.py[i] + sinA * hl - cosA * hw)
                path.lineTo(sim.px[i] + cosA * hl - sinA * hw, sim.py[i] + sinA * hl + cosA * hw)
                path.lineTo(sim.px[i] - cosA * hl - sinA * hw, sim.py[i] - sinA * hl + cosA * hw)
                path.close()
                oc.drawPath(path, paint)
            } else {
                val flowAngle = atan2(sim.vy[i], sim.vx[i])
                drawShape(oc, paint, path, sim.px[i], sim.py[i], sz, flowAngle, sim.shape[i], color)
            }
        }
    }

    private fun drawShape(
        canvas: Canvas, paint: Paint, path: Path,
        x: Float, y: Float, size: Float, angle: Float,
        shapeIdx: Int, color: Int
    ) {
        paint.color = color
        when (shapeIdx) {
            0 -> canvas.drawCircle(x, y, size, paint)
            1 -> {
                val a = angle + PI.toFloat() * 0.25f
                val cosA = cos(a); val sinA = sin(a)
                path.reset()
                path.moveTo(x + cosA * size + sinA * size, y + sinA * size - cosA * size)
                path.lineTo(x + cosA * size - sinA * size, y + sinA * size + cosA * size)
                path.lineTo(x - cosA * size - sinA * size, y - sinA * size + cosA * size)
                path.lineTo(x - cosA * size + sinA * size, y - sinA * size - cosA * size)
                path.close()
                canvas.drawPath(path, paint)
            }
            2 -> {
                val cosA = cos(angle); val sinA = sin(angle)
                val tipLen = size * 1.8f; val backLen = size; val hw = size * 0.9f
                path.reset()
                path.moveTo(x + cosA * tipLen, y + sinA * tipLen)
                path.lineTo(x - cosA * backLen + sinA * hw, y - sinA * backLen - cosA * hw)
                path.lineTo(x - cosA * backLen - sinA * hw, y - sinA * backLen + cosA * hw)
                path.close()
                canvas.drawPath(path, paint)
            }
            3 -> {
                // Filled rectangle instead of stroked line — eliminates STROKE/ROUND cap overhead
                val cosA = cos(angle); val sinA = sin(angle)
                val hl = size * 2f
                val hw = (size * 0.22f).coerceAtLeast(0.25f)
                path.reset()
                path.moveTo(x - cosA * hl + sinA * hw, y - sinA * hl - cosA * hw)
                path.lineTo(x + cosA * hl + sinA * hw, y + sinA * hl - cosA * hw)
                path.lineTo(x + cosA * hl - sinA * hw, y + sinA * hl + cosA * hw)
                path.lineTo(x - cosA * hl - sinA * hw, y - sinA * hl + cosA * hw)
                path.close()
                canvas.drawPath(path, paint)
            }
        }
    }

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float {
        val count = (params["particleCount"] as? Number)?.toFloat() ?: 2000f
        return (count / 5000f).coerceIn(0.2f, 1f)
    }
}
