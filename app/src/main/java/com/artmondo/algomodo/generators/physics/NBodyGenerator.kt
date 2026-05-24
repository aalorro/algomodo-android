package com.artmondo.algomodo.generators.physics

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import com.artmondo.algomodo.audio.AudioAnalysis
import com.artmondo.algomodo.core.rng.SeededRNG
import com.artmondo.algomodo.data.palettes.Palette
import com.artmondo.algomodo.generators.Generator
import com.artmondo.algomodo.generators.ParamGroup
import com.artmondo.algomodo.generators.Parameter
import com.artmondo.algomodo.generators.Quality
import kotlin.math.*

/**
 * Port of the TypeScript `physics/n-body` generator to Kotlin.
 *
 * Newtonian n-body gravitational simulation: each body accumulates pairwise
 * gravitational force, integrated via leapfrog (kick-drift). Trails are stored
 * in per-body circular buffers; multiple render styles (trails / dots / glow /
 * network) and color modes (mass / speed / index) are supported.
 */
class NBodyGenerator : Generator {

    override val id = "physics-n-body"
    override val family = "physics"
    override val styleName = "N-Body Gravity"
    override val definition =
        "Newtonian n-body gravitational simulation — orbital trails, binary stars, central attractors, and chaotic multi-body interactions with optional collision merging"
    override val algorithmNotes =
        "Each body accumulates gravitational force from every other body via F = G·m1·m2 / (d² + ε²)^(3/2) " +
        "with softening epsilon to prevent singularities. Leapfrog integration updates velocities then positions. " +
        "Optional merge on close approach conserves momentum and combines mass. " +
        "Trails stored in per-body circular buffers with alpha fade. " +
        "Init modes: central (massive attractor + Keplerian orbiters), binary (two stars + debris), " +
        "tangential (ring orbits around center), random (chaotic)."

    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema = listOf(
        Parameter.NumberParam(
            name = "Bodies", key = "bodies", group = ParamGroup.COMPOSITION,
            help = "Number of gravitational bodies",
            min = 3f, max = 500f, step = 1f, default = 80f
        ),
        Parameter.SelectParam(
            name = "Init Mode", key = "initMode", group = ParamGroup.COMPOSITION,
            help = "central: sun + orbiters | binary: two stars | tangential: ring orbits | random: chaos",
            options = listOf("central", "binary", "tangential", "random"),
            default = "central"
        ),
        Parameter.NumberParam(
            name = "Gravity (G)", key = "gravity", group = ParamGroup.FLOW_MOTION,
            help = "Gravitational constant — higher = stronger pull",
            min = 0.01f, max = 2f, step = 0.01f, default = 0.5f
        ),
        Parameter.NumberParam(
            name = "Softening", key = "softening", group = ParamGroup.FLOW_MOTION,
            help = "Softening epsilon — prevents singularities at close range",
            min = 1f, max = 50f, step = 1f, default = 10f
        ),
        Parameter.NumberParam(
            name = "Central Mass", key = "centralMass", group = ParamGroup.COMPOSITION,
            help = "Mass of the central attractor (central/binary modes)",
            min = 10f, max = 500f, step = 5f, default = 100f
        ),
        Parameter.NumberParam(
            name = "Mass Min", key = "massMin", group = ParamGroup.COMPOSITION,
            help = "Minimum mass for orbiter bodies",
            min = 0.1f, max = 10f, step = 0.1f, default = 0.5f
        ),
        Parameter.NumberParam(
            name = "Mass Max", key = "massMax", group = ParamGroup.COMPOSITION,
            help = "Maximum mass for orbiter bodies",
            min = 1f, max = 30f, step = 0.5f, default = 5f
        ),
        Parameter.NumberParam(
            name = "Trail Length", key = "trailLength", group = ParamGroup.TEXTURE,
            help = "Length of orbital trail per body",
            min = 10f, max = 300f, step = 5f, default = 120f
        ),
        Parameter.SelectParam(
            name = "Style", key = "style", group = ParamGroup.TEXTURE,
            help = "trails: orbital paths | dots: solid circles | glow: soft halos | network: connection lines",
            options = listOf("trails", "dots", "glow", "network"),
            default = "trails"
        ),
        Parameter.SelectParam(
            name = "Color By", key = "colorBy", group = ParamGroup.COLOR,
            help = "mass: heavier = later palette | speed: faster = later palette | index: by body ID",
            options = listOf("mass", "speed", "index"),
            default = "mass"
        ),
        Parameter.BooleanParam(
            name = "Merge", key = "merge", group = ParamGroup.FLOW_MOTION,
            help = "Bodies merge on collision (conserves momentum)",
            default = false
        ),
        Parameter.NumberParam(
            name = "Steps/Frame", key = "stepsPerFrame", group = ParamGroup.FLOW_MOTION,
            help = "Simulation steps per rendered frame",
            min = 1f, max = 8f, step = 1f, default = 3f
        ),
        Parameter.NumberParam(
            name = "Audio Reactivity", key = "reactivity", group = ParamGroup.FLOW_MOTION,
            help = "Sensitivity to audio input (0 = none)",
            min = 0f, max = 2f, step = 0.1f, default = 0f
        )
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "bodies" to 80f,
        "initMode" to "central",
        "gravity" to 0.5f,
        "softening" to 10f,
        "centralMass" to 100f,
        "massMin" to 0.5f,
        "massMax" to 5f,
        "trailLength" to 120f,
        "style" to "trails",
        "colorBy" to "mass",
        "merge" to false,
        "stepsPerFrame" to 3f,
        "reactivity" to 0f
    )

    // ── Param helpers ───────────────────────────────────────────────────────
    private fun pf(p: Map<String, Any>, k: String, default: Float): Float =
        (p[k] as? Number)?.toFloat() ?: default

    private fun pi(p: Map<String, Any>, k: String, default: Int): Int =
        (p[k] as? Number)?.toInt() ?: default

    private fun ps(p: Map<String, Any>, k: String, default: String): String =
        (p[k] as? String) ?: default

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

        val n0 = max(3, pi(params, "bodies", 80))
        val qMul = when (quality) {
            Quality.DRAFT -> 0.5f
            Quality.ULTRA -> 1.5f
            Quality.BALANCED -> 1f
        }
        val n = max(3, (n0 * qMul).roundToInt())
        val initMode = ps(params, "initMode", "central")
        val G = pf(params, "gravity", 0.5f).toDouble()
        val eps = pf(params, "softening", 10f).toDouble()
        val centralMass = pf(params, "centralMass", 100f).toDouble()
        val massMin = pf(params, "massMin", 0.5f).toDouble()
        val massMaxRaw = pf(params, "massMax", 5f).toDouble()
        val massMax = max(massMin, massMaxRaw)
        val trailLength = max(10, pi(params, "trailLength", 120))
        val style = ps(params, "style", "trails")
        val colorBy = ps(params, "colorBy", "mass")
        val merge = (params["merge"] as? Boolean) ?: false
        val spf = max(1, pi(params, "stepsPerFrame", 3))

        // ── Audio ───────────────────────────────────────────────────────────
        val rx = pf(params, "reactivity", 0f)
        val audio = params["_audioAnalysis"] as? AudioAnalysis
        val audioBass = (audio?.getBass(time) ?: 0f) * rx
        val audioMid = (audio?.getMid(time) ?: 0f) * rx
        val audioHigh = (audio?.getHigh(time) ?: 0f) * rx
        val audioEnergy = (audioBass + audioMid + audioHigh) / 3f

        val effG = G * (1.0 + audioBass * 1.5)
        val dt = 0.4

        // ── State cache ─────────────────────────────────────────────────────
        val key = "$seed|$n|$initMode|$centralMass|$massMin|$massMax|$trailLength|$w|$h"
        val s: NBodyState = synchronized(Companion) {
            val existing = cachedState
            if (existing != null && existing.key == key) {
                existing
            } else {
                val fresh = initState(
                    n, w, h, trailLength, seed,
                    massMin, massMax, initMode, centralMass
                )
                fresh.key = key
                cachedState = fresh
                fresh
            }
        }

        // ── Warmup (static frame) ───────────────────────────────────────────
        if (time <= 0f && s.step == 0) {
            for (f in 0 until 60) {
                stepSim(s, effG, eps, dt, merge, w, h)
            }
        }

        // ── Animation step ──────────────────────────────────────────────────
        if (time > 0f) {
            for (si in 0 until spf) {
                stepSim(s, effG, eps, dt, merge, w, h)
            }
        }

        // ── Render ──────────────────────────────────────────────────────────
        canvas.drawColor(Color.rgb(5, 5, 5))

        val colorInts = palette.colorInts()
        val nColors = colorInts.size
        val palR = IntArray(nColors) { Color.red(colorInts[it]) }
        val palG = IntArray(nColors) { Color.green(colorInts[it]) }
        val palB = IntArray(nColors) { Color.blue(colorInts[it]) }

        val tl = s.trailLen

        // Pre-compute max mass / max speed
        var maxMass = 0.0
        var maxSpeedSq = 0.0
        for (i in 0 until n) {
            if (s.alive[i].toInt() == 0) continue
            if (s.mass[i] > maxMass) maxMass = s.mass[i]
            val spdSq = s.vx[i] * s.vx[i] + s.vy[i] * s.vy[i]
            if (spdSq > maxSpeedSq) maxSpeedSq = spdSq
        }
        var maxSpeed = sqrt(maxSpeedSq)
        if (maxMass < 1.0) maxMass = 1.0
        if (maxSpeed < 0.01) maxSpeed = 1.0

        // Reusable RGB output for color lookup
        val rgbOut = IntArray(3)

        // ── Draw trails (batched by alpha bucket) ───────────────────────────
        if (style == "trails" || style == "glow") {
            val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
            }
            val BUCKETS = 5

            for (i in 0 until n) {
                if (s.alive[i].toInt() == 0) continue
                val tc = s.trailCount[i]
                if (tc < 2) continue

                val t: Float = when (colorBy) {
                    "mass" -> (s.mass[i] / maxMass).toFloat()
                    "speed" -> {
                        val spd = sqrt(s.vx[i] * s.vx[i] + s.vy[i] * s.vy[i])
                        (spd / maxSpeed).toFloat()
                    }
                    else -> i.toFloat() / max(1, n - 1).toFloat()
                }
                lerpColor(palR, palG, palB, nColors, t, rgbOut)
                val cr = rgbOut[0]; val cg = rgbOut[1]; val cb = rgbOut[2]

                val bodySize = max(1.5, sqrt(s.mass[i]) * 1.2)
                val th = s.trailHead[i]
                val base = i * tl

                // Glow pass — wide translucent trail in one batched path per bucket
                if (style == "glow") {
                    for (b in 0 until BUCKETS) {
                        val bStart = floor((b * tc).toDouble() / BUCKETS).toInt()
                        val bEnd = floor(((b + 1) * tc).toDouble() / BUCKETS).toInt()
                        if (bEnd - bStart < 2) continue
                        val midLife = 1.0 - (bStart + bEnd).toDouble() / (2.0 * (tc - 1))
                        val alphaInt = (midLife * 0.1 * 255.0).roundToInt().coerceIn(0, 255)
                        trailPaint.color = Color.argb(alphaInt, cr, cg, cb)
                        trailPaint.strokeWidth = (bodySize * 4.0 * midLife).toFloat()

                        val path = Path()
                        for (j in bStart until bEnd - 1) {
                            val idx0 = base + ((th - j + tl) % tl)
                            val idx1 = base + ((th - j - 1 + tl) % tl)
                            path.moveTo(s.trailX[idx0].toFloat(), s.trailY[idx0].toFloat())
                            path.lineTo(s.trailX[idx1].toFloat(), s.trailY[idx1].toFloat())
                        }
                        canvas.drawPath(path, trailPaint)
                    }
                }

                // Main trail — batched into alpha buckets
                for (b in 0 until BUCKETS) {
                    val bStart = floor((b * tc).toDouble() / BUCKETS).toInt()
                    val bEnd = floor(((b + 1) * tc).toDouble() / BUCKETS).toInt()
                    if (bEnd - bStart < 2) continue
                    val midLife = 1.0 - (bStart + bEnd).toDouble() / (2.0 * (tc - 1))
                    val alpha = midLife * 0.7
                    val alphaInt = (alpha * 255.0).roundToInt().coerceIn(0, 255)
                    trailPaint.color = Color.argb(alphaInt, cr, cg, cb)
                    val widthMul = if (style == "glow") 1.5 else 1.0
                    trailPaint.strokeWidth = max(0.5, bodySize * midLife * widthMul).toFloat()

                    val path = Path()
                    for (j in bStart until bEnd - 1) {
                        val idx0 = base + ((th - j + tl) % tl)
                        val idx1 = base + ((th - j - 1 + tl) % tl)
                        path.moveTo(s.trailX[idx0].toFloat(), s.trailY[idx0].toFloat())
                        path.lineTo(s.trailX[idx1].toFloat(), s.trailY[idx1].toFloat())
                    }
                    canvas.drawPath(path, trailPaint)
                }
            }
        }

        // ── Draw network connections (batched) ──────────────────────────────
        if (style == "network") {
            val maxDist = min(w, h) * 0.15
            val maxDistSq = maxDist * maxDist
            val netPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
            }

            val NET_BUCKETS = 4
            for (b in 0 until NET_BUCKETS) {
                val alphaLo = b.toDouble() / NET_BUCKETS
                val alphaHi = (b + 1).toDouble() / NET_BUCKETS
                val midAlpha = (alphaLo + alphaHi) * 0.5 * 0.4
                // Mid-palette color for this bucket
                val tBucket = ((b + 0.5) / NET_BUCKETS).toFloat()
                lerpColor(palR, palG, palB, nColors, tBucket, rgbOut)
                val cr = rgbOut[0]; val cg = rgbOut[1]; val cb = rgbOut[2]
                val alphaInt = (midAlpha * 255.0).roundToInt().coerceIn(0, 255)
                netPaint.color = Color.argb(alphaInt, cr, cg, cb)
                netPaint.strokeWidth = (0.5 + midAlpha * 2.0).toFloat()

                val path = Path()
                var hasSegments = false

                for (i in 0 until n) {
                    if (s.alive[i].toInt() == 0) continue
                    for (j in i + 1 until n) {
                        if (s.alive[j].toInt() == 0) continue
                        val dx = s.x[j] - s.x[i]
                        val dy = s.y[j] - s.y[i]
                        val dSq = dx * dx + dy * dy
                        if (dSq > maxDistSq) continue
                        val normDist = sqrt(dSq) / maxDist
                        val alphaVal = 1.0 - normDist
                        if (alphaVal >= alphaLo && alphaVal < alphaHi) {
                            path.moveTo(s.x[i].toFloat(), s.y[i].toFloat())
                            path.lineTo(s.x[j].toFloat(), s.y[j].toFloat())
                            hasSegments = true
                        }
                    }
                }
                if (hasSegments) canvas.drawPath(path, netPaint)
            }
        }

        // ── Draw bodies ─────────────────────────────────────────────────────
        val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.style = Paint.Style.FILL
        }

        for (i in 0 until n) {
            if (s.alive[i].toInt() == 0) continue

            val t: Float = when (colorBy) {
                "mass" -> (s.mass[i] / maxMass).toFloat()
                "speed" -> {
                    val spd = sqrt(s.vx[i] * s.vx[i] + s.vy[i] * s.vy[i])
                    (spd / maxSpeed).toFloat()
                }
                else -> i.toFloat() / max(1, n - 1).toFloat()
            }
            lerpColor(palR, palG, palB, nColors, t, rgbOut)
            val cr = rgbOut[0]; val cg = rgbOut[1]; val cb = rgbOut[2]

            val bodySize = max(1.5, sqrt(s.mass[i]) * 1.2) * (1.0 + audioEnergy * 0.5)
            val cx = s.x[i].toFloat()
            val cy = s.y[i].toFloat()

            if (style == "glow") {
                bodyPaint.color = Color.argb((0.06f * 255f).roundToInt(), cr, cg, cb)
                canvas.drawCircle(cx, cy, (bodySize * 4.0).toFloat(), bodyPaint)
                bodyPaint.color = Color.argb((0.15f * 255f).roundToInt(), cr, cg, cb)
                canvas.drawCircle(cx, cy, (bodySize * 2.5).toFloat(), bodyPaint)
                bodyPaint.color = Color.argb((0.4f * 255f).roundToInt(), cr, cg, cb)
                canvas.drawCircle(cx, cy, (bodySize * 1.3).toFloat(), bodyPaint)
            }

            // Solid core
            bodyPaint.color = Color.rgb(cr, cg, cb)
            canvas.drawCircle(cx, cy, bodySize.toFloat(), bodyPaint)
        }
    }

    // ── Simulation state init ───────────────────────────────────────────────

    private fun initState(
        n: Int, w: Int, h: Int, trailLen: Int, seed: Int,
        massMin: Double, massMax: Double, initMode: String, centralMass: Double
    ): NBodyState {
        val rng = SeededRNG(seed)
        val x = DoubleArray(n)
        val y = DoubleArray(n)
        val vx = DoubleArray(n)
        val vy = DoubleArray(n)
        val mass = DoubleArray(n)
        val alive = ByteArray(n)

        val cx = w / 2.0
        val cy = h / 2.0
        val maxR = min(w, h) * 0.35

        when (initMode) {
            "binary" -> {
                val sep = maxR * 0.4
                val totalM = centralMass * 2.0
                val orbV = sqrt(centralMass * 0.5 / sep) * 0.7
                x[0] = cx - sep; y[0] = cy
                vx[0] = 0.0; vy[0] = -orbV
                mass[0] = centralMass; alive[0] = 1
                x[1] = cx + sep; y[1] = cy
                vx[1] = 0.0; vy[1] = orbV
                mass[1] = centralMass; alive[1] = 1
                for (i in 2 until n) {
                    val angle = rng.randomDouble() * PI * 2.0
                    val r = maxR * (0.3 + rng.randomDouble() * 0.7)
                    x[i] = cx + cos(angle) * r
                    y[i] = cy + sin(angle) * r
                    val orbSpeed = sqrt(totalM * 0.5 / max(r, 10.0))
                    vx[i] = -sin(angle) * orbSpeed * (0.7 + rng.randomDouble() * 0.6)
                    vy[i] = cos(angle) * orbSpeed * (0.7 + rng.randomDouble() * 0.6)
                    mass[i] = massMin + rng.randomDouble() * (massMax - massMin)
                    alive[i] = 1
                }
            }
            "central" -> {
                x[0] = cx; y[0] = cy
                vx[0] = 0.0; vy[0] = 0.0
                mass[0] = centralMass; alive[0] = 1
                for (i in 1 until n) {
                    val angle = rng.randomDouble() * PI * 2.0
                    val r = maxR * (0.15 + rng.randomDouble() * 0.85)
                    x[i] = cx + cos(angle) * r
                    y[i] = cy + sin(angle) * r
                    val orbSpeed = sqrt(centralMass * 0.5 / max(r, 10.0))
                    vx[i] = -sin(angle) * orbSpeed * (0.8 + rng.randomDouble() * 0.4)
                    vy[i] = cos(angle) * orbSpeed * (0.8 + rng.randomDouble() * 0.4)
                    mass[i] = massMin + rng.randomDouble() * (massMax - massMin)
                    alive[i] = 1
                }
            }
            "tangential" -> {
                for (i in 0 until n) {
                    val angle = rng.randomDouble() * PI * 2.0
                    val r = maxR * (0.1 + rng.randomDouble() * 0.9)
                    x[i] = cx + cos(angle) * r
                    y[i] = cy + sin(angle) * r
                    val speed = 0.5 + rng.randomDouble() * 2.0
                    vx[i] = -sin(angle) * speed
                    vy[i] = cos(angle) * speed
                    mass[i] = massMin + rng.randomDouble() * (massMax - massMin)
                    alive[i] = 1
                }
            }
            else -> {
                for (i in 0 until n) {
                    x[i] = cx + (rng.randomDouble() - 0.5) * maxR * 2.0
                    y[i] = cy + (rng.randomDouble() - 0.5) * maxR * 2.0
                    vx[i] = (rng.randomDouble() - 0.5) * 2.0
                    vy[i] = (rng.randomDouble() - 0.5) * 2.0
                    mass[i] = massMin + rng.randomDouble() * (massMax - massMin)
                    alive[i] = 1
                }
            }
        }

        val trailX = DoubleArray(n * trailLen)
        val trailY = DoubleArray(n * trailLen)
        val trailHead = IntArray(n)
        val trailCount = IntArray(n)
        for (i in 0 until n) {
            val base = i * trailLen
            trailX[base] = x[i]
            trailY[base] = y[i]
            trailHead[i] = 0
            trailCount[i] = 1
        }

        return NBodyState(
            key = "",
            n = n,
            x = x, y = y,
            vx = vx, vy = vy,
            mass = mass,
            alive = alive,
            ax = DoubleArray(n),
            ay = DoubleArray(n),
            trailX = trailX,
            trailY = trailY,
            trailLen = trailLen,
            trailHead = trailHead,
            trailCount = trailCount,
            step = 0
        )
    }

    // ── Leapfrog integration step ───────────────────────────────────────────

    private fun stepSim(
        s: NBodyState, G: Double, eps: Double, dt: Double, merge: Boolean,
        w: Int, h: Int
    ) {
        val n = s.n
        val x = s.x
        val y = s.y
        val vx = s.vx
        val vy = s.vy
        val mass = s.mass
        val alive = s.alive
        val ax = s.ax
        val ay = s.ay
        val epsSq = eps * eps

        // Zero acceleration scratch buffers (reused)
        for (i in 0 until n) { ax[i] = 0.0; ay[i] = 0.0 }

        for (i in 0 until n) {
            if (alive[i].toInt() == 0) continue
            val xi = x[i]; val yi = y[i]; val mi = mass[i]
            for (j in i + 1 until n) {
                if (alive[j].toInt() == 0) continue
                val dx = x[j] - xi
                val dy = y[j] - yi
                val distSq = dx * dx + dy * dy + epsSq
                val invDist = 1.0 / sqrt(distSq)
                val invDistCube = invDist * invDist * invDist
                val force = G * invDistCube

                val mj = mass[j]
                val fxI = force * mj * dx
                val fyI = force * mj * dy
                ax[i] += fxI
                ay[i] += fyI
                ax[j] -= force * mi * dx
                ay[j] -= force * mi * dy

                // Merge on close approach
                if (merge && distSq - epsSq < (mi + mj) * 0.5) {
                    val totalMass = mi + mj
                    val invTotal = 1.0 / totalMass
                    vx[i] = (vx[i] * mi + vx[j] * mj) * invTotal
                    vy[i] = (vy[i] * mi + vy[j] * mj) * invTotal
                    x[i] = (xi * mi + x[j] * mj) * invTotal
                    y[i] = (yi * mi + y[j] * mj) * invTotal
                    mass[i] = totalMass
                    alive[j] = 0
                }
            }
        }

        // Leapfrog: kick-drift
        val maxSpeed = 15.0
        val maxSpeedSq = maxSpeed * maxSpeed
        val margin = 20.0
        for (i in 0 until n) {
            if (alive[i].toInt() == 0) continue
            vx[i] += ax[i] * dt
            vy[i] += ay[i] * dt

            // Clamp velocity
            val spdSq = vx[i] * vx[i] + vy[i] * vy[i]
            if (spdSq > maxSpeedSq) {
                val sc = maxSpeed / sqrt(spdSq)
                vx[i] *= sc
                vy[i] *= sc
            }

            x[i] += vx[i] * dt
            y[i] += vy[i] * dt

            // Soft boundary
            if (x[i] < margin) { x[i] = margin; vx[i] = abs(vx[i]) * 0.5 }
            if (x[i] > w - margin) { x[i] = w - margin; vx[i] = -abs(vx[i]) * 0.5 }
            if (y[i] < margin) { y[i] = margin; vy[i] = abs(vy[i]) * 0.5 }
            if (y[i] > h - margin) { y[i] = h - margin; vy[i] = -abs(vy[i]) * 0.5 }

            // Record trail
            val tl = s.trailLen
            val head = (s.trailHead[i] + 1) % tl
            val base = i * tl
            s.trailX[base + head] = x[i]
            s.trailY[base + head] = y[i]
            s.trailHead[i] = head
            if (s.trailCount[i] < tl) s.trailCount[i]++
        }

        s.step++
    }

    // ── Color helpers ───────────────────────────────────────────────────────

    private fun lerpColor(
        palR: IntArray, palG: IntArray, palB: IntArray, nColors: Int,
        t: Float, out: IntArray
    ) {
        val tt = t.coerceIn(0f, 1f)
        val s = tt * (nColors - 1)
        val i0 = s.toInt().coerceAtMost(nColors - 1)
        val i1 = min(nColors - 1, i0 + 1)
        val f = s - i0
        out[0] = (palR[i0] + (palR[i1] - palR[i0]) * f).toInt()
        out[1] = (palG[i0] + (palG[i1] - palG[i0]) * f).toInt()
        out[2] = (palB[i0] + (palB[i1] - palB[i0]) * f).toInt()
    }

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float {
        val n = pf(params, "bodies", 80f)
        val raw = (n * n * 0.1f + n * 50f) / 1000f
        return when (quality) {
            Quality.DRAFT -> raw * 0.5f
            Quality.BALANCED -> raw
            Quality.ULTRA -> raw * 1.5f
        }.coerceIn(0.1f, 1f)
    }

    // ── Cached simulation state (singleton, guarded by Companion lock) ──────

    private data class NBodyState(
        var key: String,
        val n: Int,
        val x: DoubleArray, val y: DoubleArray,
        val vx: DoubleArray, val vy: DoubleArray,
        val mass: DoubleArray,
        val alive: ByteArray,
        // Reusable acceleration scratch buffers
        val ax: DoubleArray, val ay: DoubleArray,
        // Trail circular buffers
        val trailX: DoubleArray, val trailY: DoubleArray,
        val trailLen: Int,
        val trailHead: IntArray,
        val trailCount: IntArray,
        var step: Int
    )

    companion object {
        @Volatile
        private var cachedState: NBodyState? = null
    }
}
