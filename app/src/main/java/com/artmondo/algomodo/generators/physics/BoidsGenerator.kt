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

class BoidsGenerator : Generator {

    override val id = "physics-boids"
    override val family = "physics"
    override val styleName = "Boids / Flocking"
    override val definition =
        "Craig Reynolds' classic flocking model \u2014 each boid steers by three local rules: " +
        "separation (avoid crowding), alignment (match neighbours' velocity), and cohesion " +
        "(steer toward flock centre)"
    override val algorithmNotes =
        "For each boid, find all neighbours within perceptionRadius. Separation: average " +
        "repulsion vector from neighbours within 35% of perception range. Alignment: steer " +
        "toward average velocity of visible neighbours. Cohesion: steer toward centre of mass. " +
        "Forces are weighted by user-tunable parameters. Speed is clamped to [0.3\u00d7maxSpeed, " +
        "maxSpeed]. Optional predator chases nearest boid; boids flee within 2\u00d7 perception " +
        "radius. Boundary modes: toroidal wrap, elastic bounce, or soft force-based steering. " +
        "Trail history in per-boid circular buffers. Rendering: dots, heading-oriented triangles " +
        "(arrows), fading trails, or neighbour-connection network."

    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema = listOf(
        Parameter.NumberParam(
            name = "Flock Size", key = "flockSize", group = ParamGroup.COMPOSITION,
            help = null,
            min = 30f, max = 500f, step = 10f, default = 200f
        ),
        Parameter.SelectParam(
            name = "Style", key = "style", group = ParamGroup.COMPOSITION,
            help = "dots: circles | arrows: heading-oriented triangles | trails: fading tails | network: neighbor connections",
            options = listOf("dots", "arrows", "trails", "network"),
            default = "arrows"
        ),
        Parameter.NumberParam(
            name = "Separation", key = "separation", group = ParamGroup.FLOW_MOTION,
            help = "Avoidance force from nearby flock-mates",
            min = 0f, max = 5f, step = 0.1f, default = 1.5f
        ),
        Parameter.NumberParam(
            name = "Alignment", key = "alignment", group = ParamGroup.FLOW_MOTION,
            help = "Steer toward average velocity of neighbours",
            min = 0f, max = 5f, step = 0.1f, default = 1.0f
        ),
        Parameter.NumberParam(
            name = "Cohesion", key = "cohesion", group = ParamGroup.FLOW_MOTION,
            help = "Steer toward centre of mass of neighbours",
            min = 0f, max = 5f, step = 0.1f, default = 1.0f
        ),
        Parameter.NumberParam(
            name = "Perception", key = "perceptionRadius", group = ParamGroup.GEOMETRY,
            help = "How far each boid sees neighbours",
            min = 30f, max = 200f, step = 10f, default = 80f
        ),
        Parameter.NumberParam(
            name = "Max Speed", key = "maxSpeed", group = ParamGroup.FLOW_MOTION,
            help = null,
            min = 1f, max = 8f, step = 0.5f, default = 3f
        ),
        Parameter.NumberParam(
            name = "Boid Size", key = "nodeSize", group = ParamGroup.GEOMETRY,
            help = null,
            min = 2f, max = 16f, step = 1f, default = 5f
        ),
        Parameter.NumberParam(
            name = "Trail Length", key = "trailLength", group = ParamGroup.TEXTURE,
            help = null,
            min = 5f, max = 50f, step = 5f, default = 20f
        ),
        Parameter.BooleanParam(
            name = "Predator", key = "predator", group = ParamGroup.COMPOSITION,
            help = "Add a predator that chases the flock \u2014 boids flee within double perception radius",
            default = false
        ),
        Parameter.SelectParam(
            name = "Boundary", key = "boundary", group = ParamGroup.GEOMETRY,
            help = "wrap: toroidal | bounce: reflect | steer: soft edge avoidance",
            options = listOf("wrap", "bounce", "steer"),
            default = "wrap"
        ),
        Parameter.SelectParam(
            name = "Color Mode", key = "colorMode", group = ParamGroup.COLOR,
            help = "heading: velocity angle \u2192 palette | speed: |v| \u2192 palette | group: spatial clusters | palette: index cycle",
            options = listOf("heading", "speed", "group", "palette"),
            default = "heading"
        ),
        Parameter.NumberParam(
            name = "Steps / Frame", key = "stepsPerFrame", group = ParamGroup.FLOW_MOTION,
            help = null,
            min = 1f, max = 4f, step = 1f, default = 2f
        ),
        Parameter.NumberParam(
            name = "Audio Reactivity", key = "reactivity", group = ParamGroup.FLOW_MOTION,
            help = null,
            min = 0f, max = 2f, step = 0.1f, default = 0f
        )
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "flockSize" to 200f,
        "style" to "arrows",
        "separation" to 1.5f,
        "alignment" to 1.0f,
        "cohesion" to 1.0f,
        "perceptionRadius" to 80f,
        "maxSpeed" to 3f,
        "nodeSize" to 5f,
        "trailLength" to 20f,
        "predator" to false,
        "boundary" to "wrap",
        "colorMode" to "heading",
        "stepsPerFrame" to 2f,
        "reactivity" to 0f
    )

    // ── Param helpers ───────────────────────────────────────────────────────
    private fun pf(p: Map<String, Any>, k: String, default: Float): Float =
        (p[k] as? Number)?.toFloat() ?: default
    private fun pi(p: Map<String, Any>, k: String, default: Int): Int =
        (p[k] as? Number)?.toInt() ?: default
    private fun pb(p: Map<String, Any>, k: String, default: Boolean): Boolean =
        (p[k] as? Boolean) ?: default
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
        val wf = w.toFloat()
        val hf = h.toFloat()

        // ── Parameters ──────────────────────────────────────────────────────
        val nParam = max(5, pi(params, "flockSize", 200))
        val style = ps(params, "style", "arrows")
        var sepW = pf(params, "separation", 1.5f)
        var aliW = pf(params, "alignment", 1.0f)
        var cohW = pf(params, "cohesion", 1.0f)
        val percRad = pf(params, "perceptionRadius", 80f) * (wf / 600f)
        var maxSpd = pf(params, "maxSpeed", 3f) * (wf / 600f)
        var nodeSize = pf(params, "nodeSize", 5f)
        val trailLen = max(2, pi(params, "trailLength", 20))
        val hasPredator = pb(params, "predator", false)
        val boundary = ps(params, "boundary", "wrap")
        val colorMode = ps(params, "colorMode", "heading")
        val spf = max(1, pi(params, "stepsPerFrame", 2))

        // ── Audio reactivity ────────────────────────────────────────────────
        val rx = pf(params, "reactivity", 0f)
        val audio = params["_audioAnalysis"] as? AudioAnalysis
        val audioBass = (audio?.getBass(time) ?: 0f) * rx
        val audioMid = (audio?.getMid(time) ?: 0f) * rx
        val audioHigh = (audio?.getHigh(time) ?: 0f) * rx
        val audioEnergy = (audioBass + audioMid + audioHigh) / 3f

        cohW += audioBass
        maxSpd *= (1f + audioMid * 0.3f)
        sepW += audioEnergy * 2f
        nodeSize *= (1f + audioBass * 0.3f)

        // ── Quality scaling ─────────────────────────────────────────────────
        val qMul = when (quality) {
            Quality.DRAFT -> 0.5f
            Quality.ULTRA -> 1.5f
            else -> 1f
        }
        val scaledN = (nParam * qMul).roundToInt()
        val predSpd = maxSpd * 0.85f

        // ── Palette ─────────────────────────────────────────────────────────
        val colorInts = palette.colorInts()
        val nColors = colorInts.size
        val palR = IntArray(nColors) { Color.red(colorInts[it]) }
        val palG = IntArray(nColors) { Color.green(colorInts[it]) }
        val palB = IntArray(nColors) { Color.blue(colorInts[it]) }
        val scale = wf / 600f

        // ── State cache ─────────────────────────────────────────────────────
        val key = "$seed|$scaledN|$trailLen|$w|$h"
        var bs: BoidState
        synchronized(Companion) {
            val cur = _bs
            if (cur == null || cur.key != key) {
                bs = initBoids(scaledN, wf, hf, trailLen, seed)
                bs.key = key
                _bs = bs
            } else {
                bs = cur
            }
        }

        // ── Background ──────────────────────────────────────────────────────
        canvas.drawColor(Color.rgb(10, 10, 10))

        // ── Static warmup ───────────────────────────────────────────────────
        synchronized(Companion) {
            if (time <= 0f && bs.step == 0) {
                repeat(60) {
                    stepBoids(bs, wf, hf, sepW, aliW, cohW, percRad, maxSpd, boundary, hasPredator, predSpd)
                }
            }

            // ── Animation ───────────────────────────────────────────────────
            if (time > 0f) {
                repeat(spf) {
                    stepBoids(bs, wf, hf, sepW, aliW, cohW, percRad, maxSpd, boundary, hasPredator, predSpd)
                }
            }
        }

        // ── Render ──────────────────────────────────────────────────────────
        val x = bs.x; val y = bs.y
        val vx = bs.vx; val vy = bs.vy
        val trailX = bs.trailX; val trailY = bs.trailY
        val trailHead = bs.trailHead; val trailCount = bs.trailCount
        val tLen = bs.trailLen
        val n = bs.n
        val twoPi = (PI * 2.0).toFloat()

        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.style = Paint.Style.FILL }
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }

        for (i in 0 until n) {
            // Determine color
            var r = 0; var g = 0; var b = 0
            when (colorMode) {
                "speed" -> {
                    val spd = sqrt(vx[i] * vx[i] + vy[i] * vy[i]).toFloat()
                    val t = if (maxSpd > 0f) spd / maxSpd else 0f
                    val c = lerpColor(palR, palG, palB, nColors, t)
                    r = c[0]; g = c[1]; b = c[2]
                }
                "group" -> {
                    val gx = ((x[i] / wf * 3f).toInt()) % 3
                    val gy = ((y[i] / hf * 3f).toInt()) % 3
                    val idx = ((gx + gy * 3) % nColors + nColors) % nColors
                    r = palR[idx]; g = palG[idx]; b = palB[idx]
                }
                "palette" -> {
                    val idx = ((i % nColors) + nColors) % nColors
                    r = palR[idx]; g = palG[idx]; b = palB[idx]
                }
                else -> {
                    // heading
                    val heading = ((atan2(vy[i], vx[i]) + PI) / (2.0 * PI)).toFloat()
                    val c = lerpColor(palR, palG, palB, nColors, heading)
                    r = c[0]; g = c[1]; b = c[2]
                }
            }

            when (style) {
                "trails" -> {
                    val tc = trailCount[i]
                    if (tc < 2) continue
                    val th = trailHead[i]
                    val tBase = i * tLen
                    strokePaint.strokeWidth = nodeSize * 0.4f * scale
                    for (j in 0 until tc - 1) {
                        val idx0 = tBase + ((th - j + tLen) % tLen)
                        val idx1 = tBase + ((th - j - 1 + tLen) % tLen)
                        val alpha = (1f - j.toFloat() / tc.toFloat()) * 0.7f
                        val a = (alpha * 255f).toInt().coerceIn(0, 255)
                        strokePaint.color = Color.argb(a, r, g, b)
                        canvas.drawLine(
                            trailX[idx0].toFloat(), trailY[idx0].toFloat(),
                            trailX[idx1].toFloat(), trailY[idx1].toFloat(),
                            strokePaint
                        )
                    }
                }

                "network" -> {
                    val connDist = percRad * 0.8f
                    val connSq = connDist * connDist
                    strokePaint.strokeWidth = 0.5f * scale
                    val xi = x[i]; val yi = y[i]
                    for (j in i + 1 until n) {
                        val dx = x[j] - xi
                        val dy = y[j] - yi
                        val dSq = dx * dx + dy * dy
                        if (dSq < connSq) {
                            val alpha = (1f - (dSq / connSq).toFloat()) * 0.3f
                            val a = (alpha * 255f).toInt().coerceIn(0, 255)
                            strokePaint.color = Color.argb(a, r, g, b)
                            canvas.drawLine(
                                xi.toFloat(), yi.toFloat(),
                                x[j].toFloat(), y[j].toFloat(),
                                strokePaint
                            )
                        }
                    }
                    // Dot
                    fillPaint.color = Color.rgb(r, g, b)
                    canvas.drawCircle(xi.toFloat(), yi.toFloat(), nodeSize * 0.4f * scale, fillPaint)
                }

                "arrows" -> {
                    val heading = atan2(vy[i], vx[i])
                    val sz = nodeSize * scale * 0.7f
                    fillPaint.color = Color.rgb(r, g, b)
                    val xi = x[i].toFloat(); val yi = y[i].toFloat()
                    val path = Path()
                    path.moveTo(
                        xi + (cos(heading) * sz).toFloat(),
                        yi + (sin(heading) * sz).toFloat()
                    )
                    path.lineTo(
                        xi + (cos(heading + 2.5) * sz * 0.6).toFloat(),
                        yi + (sin(heading + 2.5) * sz * 0.6).toFloat()
                    )
                    path.lineTo(
                        xi + (cos(heading - 2.5) * sz * 0.6).toFloat(),
                        yi + (sin(heading - 2.5) * sz * 0.6).toFloat()
                    )
                    path.close()
                    canvas.drawPath(path, fillPaint)
                }

                else -> {
                    // dots
                    fillPaint.color = Color.rgb(r, g, b)
                    canvas.drawCircle(
                        x[i].toFloat(), y[i].toFloat(),
                        nodeSize * 0.5f * scale, fillPaint
                    )
                }
            }
        }

        // ── Draw predator ───────────────────────────────────────────────────
        if (hasPredator) {
            val ph = atan2(bs.pvy, bs.pvx)
            val sz = nodeSize * scale * 1.5f
            fillPaint.color = Color.rgb(0xff, 0x33, 0x33)
            val pxF = bs.px.toFloat(); val pyF = bs.py.toFloat()
            val path = Path()
            path.moveTo(
                pxF + (cos(ph) * sz).toFloat(),
                pyF + (sin(ph) * sz).toFloat()
            )
            path.lineTo(
                pxF + (cos(ph + 2.3) * sz * 0.7).toFloat(),
                pyF + (sin(ph + 2.3) * sz * 0.7).toFloat()
            )
            path.lineTo(
                pxF + (cos(ph - 2.3) * sz * 0.7).toFloat(),
                pyF + (sin(ph - 2.3) * sz * 0.7).toFloat()
            )
            path.close()
            canvas.drawPath(path, fillPaint)
        }
    }

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float {
        val n = pf(params, "flockSize", 200f)
        return (n * n * 0.01f / 1000f).coerceIn(0.1f, 1f)
    }

    // ── Color interpolation helper ──────────────────────────────────────────
    private fun lerpColor(
        palR: IntArray, palG: IntArray, palB: IntArray, nColors: Int, t: Float
    ): IntArray {
        val tt = t.coerceIn(0f, 1f)
        val s = tt * (nColors - 1)
        val i0 = s.toInt().coerceAtMost(nColors - 1)
        val i1 = min(nColors - 1, i0 + 1)
        val f = s - i0
        return intArrayOf(
            (palR[i0] + (palR[i1] - palR[i0]) * f).toInt(),
            (palG[i0] + (palG[i1] - palG[i0]) * f).toInt(),
            (palB[i0] + (palB[i1] - palB[i0]) * f).toInt()
        )
    }

    // ── Simulation state ────────────────────────────────────────────────────
    private class BoidState(
        var key: String,
        val n: Int,
        val x: DoubleArray, val y: DoubleArray,
        val vx: DoubleArray, val vy: DoubleArray,
        val trailX: DoubleArray, val trailY: DoubleArray,
        val trailLen: Int,
        val trailHead: IntArray,
        val trailCount: IntArray,
        var px: Double, var py: Double,
        var pvx: Double, var pvy: Double,
        var step: Int
    )

    private fun initBoids(n: Int, w: Float, h: Float, trailLen: Int, seed: Int): BoidState {
        val rng = SeededRNG(seed)
        val x = DoubleArray(n)
        val y = DoubleArray(n)
        val vx = DoubleArray(n)
        val vy = DoubleArray(n)

        for (i in 0 until n) {
            x[i] = w * 0.1 + rng.randomDouble() * w * 0.8
            y[i] = h * 0.1 + rng.randomDouble() * h * 0.8
            val a = rng.randomDouble() * PI * 2.0
            val s = 1.0 + rng.randomDouble() * 2.0
            vx[i] = cos(a) * s
            vy[i] = sin(a) * s
        }

        val tTotal = n * trailLen
        val trailX = DoubleArray(tTotal)
        val trailY = DoubleArray(tTotal)
        for (i in 0 until n) {
            val base = i * trailLen
            trailX[base] = x[i]
            trailY[base] = y[i]
        }

        val trailCount = IntArray(n) { 1 }

        return BoidState(
            key = "",
            n = n,
            x = x, y = y,
            vx = vx, vy = vy,
            trailX = trailX, trailY = trailY,
            trailLen = trailLen,
            trailHead = IntArray(n),
            trailCount = trailCount,
            px = (w / 2f).toDouble(),
            py = (h / 2f).toDouble(),
            pvx = 2.0, pvy = 1.0,
            step = 0
        )
    }

    private fun stepBoids(
        bs: BoidState, w: Float, h: Float,
        sepW: Float, aliW: Float, cohW: Float,
        percRad: Float, maxSpd: Float,
        boundary: String, hasPredator: Boolean,
        predSpd: Float
    ) {
        val n = bs.n
        val x = bs.x; val y = bs.y
        val vx = bs.vx; val vy = bs.vy
        val trailX = bs.trailX; val trailY = bs.trailY
        val trailLen = bs.trailLen
        val trailHead = bs.trailHead; val trailCount = bs.trailCount

        val percSq = (percRad * percRad).toDouble()
        val sepDist = percRad * 0.35
        val sepSq = sepDist * sepDist
        val wD = w.toDouble()
        val hD = h.toDouble()
        val maxSpdD = maxSpd.toDouble()
        val sepWD = sepW.toDouble()
        val aliWD = aliW.toDouble()
        val cohWD = cohW.toDouble()
        val percRadD = percRad.toDouble()

        for (i in 0 until n) {
            var sepX = 0.0; var sepY = 0.0; var sepN = 0
            var aliVx = 0.0; var aliVy = 0.0; var aliN = 0
            var cohX = 0.0; var cohY = 0.0; var cohN = 0

            val xi = x[i]; val yi = y[i]

            for (j in 0 until n) {
                if (i == j) continue
                val dx = x[j] - xi
                val dy = y[j] - yi
                val dSq = dx * dx + dy * dy
                if (dSq > percSq) continue

                // Separation (close range)
                if (dSq < sepSq && dSq > 0.0) {
                    val d = sqrt(dSq)
                    sepX -= dx / d
                    sepY -= dy / d
                    sepN++
                }

                // Alignment
                aliVx += vx[j]; aliVy += vy[j]; aliN++

                // Cohesion
                cohX += x[j]; cohY += y[j]; cohN++
            }

            var fx = 0.0
            var fy = 0.0

            // Separation
            if (sepN > 0) {
                fx += (sepX / sepN) * sepWD
                fy += (sepY / sepN) * sepWD
            }

            // Alignment — steer toward average heading
            if (aliN > 0) {
                val avgVx = aliVx / aliN
                val avgVy = aliVy / aliN
                fx += (avgVx - vx[i]) * aliWD * 0.1
                fy += (avgVy - vy[i]) * aliWD * 0.1
            }

            // Cohesion — steer toward center of mass
            if (cohN > 0) {
                val avgX = cohX / cohN
                val avgY = cohY / cohN
                fx += (avgX - xi) * cohWD * 0.002
                fy += (avgY - yi) * cohWD * 0.002
            }

            // Predator avoidance
            if (hasPredator) {
                val pdx = bs.px - xi
                val pdy = bs.py - yi
                val pdSq = pdx * pdx + pdy * pdy
                val fleeR = percRadD * 2.0
                if (pdSq < fleeR * fleeR && pdSq > 0.0) {
                    val pd = sqrt(pdSq)
                    fx -= (pdx / pd) * 3.0
                    fy -= (pdy / pd) * 3.0
                }
            }

            // Boundary steer
            if (boundary == "steer") {
                val margin = percRadD
                if (xi < margin) fx += (margin - xi) * 0.05
                if (xi > wD - margin) fx -= (xi - (wD - margin)) * 0.05
                if (yi < margin) fy += (margin - yi) * 0.05
                if (yi > hD - margin) fy -= (yi - (hD - margin)) * 0.05
            }

            // Apply forces
            vx[i] += fx
            vy[i] += fy

            // Clamp speed
            val spd = sqrt(vx[i] * vx[i] + vy[i] * vy[i])
            if (spd > maxSpdD) {
                vx[i] = (vx[i] / spd) * maxSpdD
                vy[i] = (vy[i] / spd) * maxSpdD
            }
            // Minimum speed
            if (spd < maxSpdD * 0.3) {
                val denom = if (spd == 0.0) 1.0 else spd
                vx[i] = (vx[i] / denom) * maxSpdD * 0.3
                vy[i] = (vy[i] / denom) * maxSpdD * 0.3
            }

            // Integrate
            x[i] += vx[i]
            y[i] += vy[i]

            // Boundary enforcement
            when (boundary) {
                "wrap" -> {
                    if (x[i] < 0.0) x[i] += wD else if (x[i] > wD) x[i] -= wD
                    if (y[i] < 0.0) y[i] += hD else if (y[i] > hD) y[i] -= hD
                }
                "bounce" -> {
                    if (x[i] < 0.0) { x[i] = 0.0; vx[i] = abs(vx[i]) }
                    if (x[i] > wD) { x[i] = wD; vx[i] = -abs(vx[i]) }
                    if (y[i] < 0.0) { y[i] = 0.0; vy[i] = abs(vy[i]) }
                    if (y[i] > hD) { y[i] = hD; vy[i] = -abs(vy[i]) }
                }
                // 'steer' handled via forces
            }

            // Record trail
            val tBase = i * trailLen
            val head = (trailHead[i] + 1) % trailLen
            trailX[tBase + head] = x[i]
            trailY[tBase + head] = y[i]
            trailHead[i] = head
            if (trailCount[i] < trailLen) trailCount[i]++
        }

        // Update predator (chases nearest boid)
        if (hasPredator) {
            var nearDist = Double.POSITIVE_INFINITY
            var nearIdx = 0
            for (i in 0 until n) {
                val dx = x[i] - bs.px
                val dy = y[i] - bs.py
                val dSq = dx * dx + dy * dy
                if (dSq < nearDist) {
                    nearDist = dSq
                    nearIdx = i
                }
            }
            val dx = x[nearIdx] - bs.px
            val dy = y[nearIdx] - bs.py
            val dRaw = sqrt(dx * dx + dy * dy)
            val d = if (dRaw == 0.0) 1.0 else dRaw
            bs.pvx += (dx / d) * 0.2
            bs.pvy += (dy / d) * 0.2
            val psd = sqrt(bs.pvx * bs.pvx + bs.pvy * bs.pvy)
            if (psd > predSpd) {
                bs.pvx = (bs.pvx / psd) * predSpd
                bs.pvy = (bs.pvy / psd) * predSpd
            }
            bs.px += bs.pvx
            bs.py += bs.pvy
            // Wrap predator
            if (bs.px < 0.0) bs.px += wD else if (bs.px > wD) bs.px -= wD
            if (bs.py < 0.0) bs.py += hD else if (bs.py > hD) bs.py -= hD
        }

        bs.step++
    }

    companion object {
        @Volatile
        private var _bs: BoidState? = null
    }
}
