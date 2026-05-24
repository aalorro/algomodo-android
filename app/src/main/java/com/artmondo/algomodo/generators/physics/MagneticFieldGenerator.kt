package com.artmondo.algomodo.generators.physics

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import com.artmondo.algomodo.audio.AudioAnalysis
import com.artmondo.algomodo.core.rng.SeededRNG
import com.artmondo.algomodo.data.palettes.Palette
import com.artmondo.algomodo.generators.Generator
import com.artmondo.algomodo.generators.ParamGroup
import com.artmondo.algomodo.generators.Parameter
import com.artmondo.algomodo.generators.Quality
import kotlin.math.*

class MagneticFieldGenerator : Generator {

    override val id = "physics-magnetic-field"
    override val family = "physics"
    override val styleName = "Magnetic Field Lines"
    override val definition =
        "Visualizing dipole or multi-pole magnetic fields as flowing streamlines \u2014 " +
        "field lines traced from positive poles curve toward negative poles, revealing the " +
        "invisible force structure"
    override val algorithmNotes =
        "Monopole field model: B(r) = q\u00b7r\u0302/|r|\u00b2 with softening to avoid singularities. " +
        "Field lines traced via Euler integration from seed points placed evenly around positive poles. " +
        "Lines terminate at negative poles (within proximity threshold) or at canvas boundaries. " +
        "Pole configurations: dipole (classic N/S pair), quadrupole (alternating square), ring (hexagonal ring + center), random (balanced charges). " +
        "Animation: poles orbit around base positions. Particle mode: persistent particles flow along B direction, respawn at positive poles."

    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema = listOf(
        Parameter.SelectParam(
            name = "Pole Layout", key = "poleConfig", group = ParamGroup.COMPOSITION,
            help = "dipole: N/S pair | quadrupole: 4-pole pattern | random: scattered | ring: ring of poles",
            options = listOf("dipole", "quadrupole", "random", "ring"),
            default = "dipole"
        ),
        Parameter.NumberParam(
            name = "Line Count", key = "lineCount", group = ParamGroup.GEOMETRY,
            help = "Number of field lines traced (also particle count in particle mode)",
            min = 20f, max = 200f, step = 5f, default = 80f
        ),
        Parameter.NumberParam(
            name = "Line Steps", key = "lineSteps", group = ParamGroup.GEOMETRY,
            help = "Maximum integration steps per field line",
            min = 50f, max = 500f, step = 25f, default = 200f
        ),
        Parameter.NumberParam(
            name = "Pole Strength", key = "poleStrength", group = ParamGroup.FLOW_MOTION,
            help = "Magnetic charge magnitude",
            min = 0.5f, max = 5f, step = 0.25f, default = 2f
        ),
        Parameter.SelectParam(
            name = "Render Style", key = "style", group = ParamGroup.COMPOSITION,
            help = "streamlines: traced curves | arrows: direction grid | particles: flowing dots | glow: additive lines",
            options = listOf("streamlines", "arrows", "particles", "glow"),
            default = "streamlines"
        ),
        Parameter.SelectParam(
            name = "Color Mode", key = "colorMode", group = ParamGroup.COLOR,
            help = "palette-cycle: by line index | field-strength: by B magnitude | direction: by B angle",
            options = listOf("palette-cycle", "field-strength", "direction"),
            default = "palette-cycle"
        ),
        Parameter.NumberParam(
            name = "Line Width", key = "lineWidth", group = ParamGroup.GEOMETRY,
            help = null,
            min = 0.5f, max = 4f, step = 0.25f, default = 1.5f
        ),
        Parameter.NumberParam(
            name = "Anim Speed", key = "animSpeed", group = ParamGroup.FLOW_MOTION,
            help = "Pole orbit speed \u2014 0 for static poles",
            min = 0f, max = 2f, step = 0.1f, default = 0.5f
        ),
        Parameter.NumberParam(
            name = "Steps / Frame", key = "stepsPerFrame", group = ParamGroup.FLOW_MOTION,
            help = null,
            min = 1f, max = 4f, step = 1f, default = 1f
        ),
        Parameter.NumberParam(
            name = "Audio Reactivity", key = "reactivity", group = ParamGroup.FLOW_MOTION,
            help = null,
            min = 0f, max = 2f, step = 0.1f, default = 0f
        )
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "poleConfig" to "dipole",
        "lineCount" to 80f,
        "lineSteps" to 200f,
        "poleStrength" to 2f,
        "style" to "streamlines",
        "colorMode" to "palette-cycle",
        "lineWidth" to 1.5f,
        "animSpeed" to 0.5f,
        "stepsPerFrame" to 1f,
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
        val wf = w.toFloat()
        val hf = h.toFloat()
        val wD = wf.toDouble()
        val hD = hf.toDouble()
        val scale = wf / 600f

        // ── Parameters ──────────────────────────────────────────────────────
        val poleConfig = ps(params, "poleConfig", "dipole")
        var lineCount = max(8, pi(params, "lineCount", 80))
        val lineSteps = max(20, pi(params, "lineSteps", 200))
        var poleStrength = pf(params, "poleStrength", 2f)
        val style = ps(params, "style", "streamlines")
        val colorMode = ps(params, "colorMode", "palette-cycle")
        val lineWidth = pf(params, "lineWidth", 1.5f)
        var animSpeed = pf(params, "animSpeed", 0.5f)
        val spf = max(1, pi(params, "stepsPerFrame", 1))

        // Quality scaling
        val qMul = when (quality) {
            Quality.DRAFT -> 0.6f
            Quality.ULTRA -> 1.4f
            else -> 1f
        }
        lineCount = (lineCount * qMul).roundToInt()

        // ── Audio reactivity ────────────────────────────────────────────────
        val rx = pf(params, "reactivity", 0f)
        val audio = params["_audioAnalysis"] as? AudioAnalysis
        val audioBass = (audio?.getBass(time) ?: 0f) * rx
        val audioMid = (audio?.getMid(time) ?: 0f) * rx
        val audioHigh = (audio?.getHigh(time) ?: 0f) * rx
        val audioEnergy = (audioBass + audioMid + audioHigh) / 3f

        poleStrength += audioBass * 1.5f
        animSpeed += audioEnergy * 0.5f

        // Scale pole strength to canvas
        val str = (poleStrength * wf * 50f).toDouble()

        // ── Palette ─────────────────────────────────────────────────────────
        val colorInts = palette.colorInts()
        val nColors = colorInts.size
        val palR = IntArray(nColors) { Color.red(colorInts[it]) }
        val palG = IntArray(nColors) { Color.green(colorInts[it]) }
        val palB = IntArray(nColors) { Color.blue(colorInts[it]) }

        // ── State cache ─────────────────────────────────────────────────────
        val key = "$seed|$poleConfig|$w|$h"
        var st: MagFieldState
        synchronized(Companion) {
            val cur = _state
            if (cur == null || cur.key != key) {
                st = initPoles(poleConfig, wD, hD, str, seed)
                st.key = key
                _state = st
            } else {
                st = cur
            }
            // Re-apply strength to existing charges (preserve sign)
            for (i in 0 until st.nPoles) {
                val sign = if (st.charge[i] >= 0.0) 1.0 else -1.0
                // Preserve magnitude scaling for ring center (which was -1.5x str)
                // We can't recover ring multiplier perfectly here, but for the dominant
                // poles this matches the TS quirk (the |c|/|c| = 1 trick yields baseStr).
                st.charge[i] = sign * str
            }
        }

        ensureLineBuf(lineSteps)
        val stepSize = wD / lineSteps

        // ── Animation update ───────────────────────────────────────────────
        synchronized(Companion) {
            if (time > 0f) {
                for (s in 0 until spf) {
                    updatePoles(st, animSpeed.toDouble(), wD, hD)
                    if (style == "particles") {
                        advanceParticles(st, wD, hD, stepSize)
                    }
                    st.step++
                }
            } else {
                updatePoles(st, 0.0, wD, hD)
                if (style == "particles" && st.step == 0) {
                    for (f in 0 until 60) {
                        advanceParticles(st, wD, hD, stepSize)
                        st.step++
                    }
                }
            }
        }

        // ── Render ──────────────────────────────────────────────────────────
        canvas.drawColor(Color.rgb(10, 10, 10))

        val poleX = st.poleX
        val poleY = st.poleY
        val charge = st.charge
        val nPoles = st.nPoles

        // Generate seed points around positive poles
        var posCount = 0
        for (p in 0 until nPoles) if (charge[p] > 0.0) posCount++
        val posCountEff = if (posCount == 0) nPoles else posCount
        val useAllPoles = posCount == 0

        val linesPerPole = max(4, (lineCount.toFloat() / posCountEff.toFloat()).roundToInt())
        val seedR = min(wD, hD) * 0.015

        // Collect seed points
        val seedXList = ArrayList<Double>(linesPerPole * posCountEff)
        val seedYList = ArrayList<Double>(linesPerPole * posCountEff)
        for (p in 0 until nPoles) {
            if (!useAllPoles && charge[p] <= 0.0) continue
            for (k in 0 until linesPerPole) {
                val a = (k.toDouble() / linesPerPole) * PI * 2.0
                seedXList.add(poleX[p] + cos(a) * seedR)
                seedYList.add(poleY[p] + sin(a) * seedR)
            }
        }

        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.style = Paint.Style.FILL }
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        if (style == "streamlines" || style == "glow") {
            val isGlow = style == "glow"
            strokePaint.strokeWidth = lineWidth * scale * (if (isGlow) 2.5f else 1f)
            if (isGlow) {
                strokePaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
            }

            for (li in seedXList.indices) {
                val len = traceLine(
                    seedXList[li], seedYList[li],
                    poleX, poleY, charge, nPoles, lineSteps, stepSize, wD, hD
                )
                if (len < 2) continue

                val lx = _lineX!!
                val ly = _lineY!!

                if (colorMode == "palette-cycle") {
                    val idx = ((li % nColors) + nColors) % nColors
                    val r = palR[idx]; val g = palG[idx]; val b = palB[idx]
                    val alpha = if (isGlow) 0.2f else 0.7f
                    val a = (alpha * 255f).toInt().coerceIn(0, 255)
                    strokePaint.color = Color.argb(a, r, g, b)
                    val path = Path()
                    path.moveTo(lx[0].toFloat(), ly[0].toFloat())
                    for (s in 1 until len) {
                        path.lineTo(lx[s].toFloat(), ly[s].toFloat())
                    }
                    canvas.drawPath(path, strokePaint)
                } else {
                    val alpha = if (isGlow) 0.15f else 0.6f
                    val a = (alpha * 255f).toInt().coerceIn(0, 255)
                    for (s in 0 until len - 1) {
                        val t: Float
                        val (bx, by) = fieldAt(lx[s], ly[s], poleX, poleY, charge, nPoles)
                        t = if (colorMode == "field-strength") {
                            min(1.0, sqrt(bx * bx + by * by) * 0.0001).toFloat()
                        } else {
                            // direction
                            ((atan2(by, bx) / PI + 1.0) * 0.5).toFloat()
                        }
                        val c = lerpColor(palR, palG, palB, nColors, t)
                        strokePaint.color = Color.argb(a, c[0], c[1], c[2])
                        canvas.drawLine(
                            lx[s].toFloat(), ly[s].toFloat(),
                            lx[s + 1].toFloat(), ly[s + 1].toFloat(),
                            strokePaint
                        )
                    }
                }
            }
            if (isGlow) strokePaint.xfermode = null

        } else if (style == "arrows") {
            val gridN = sqrt(lineCount.toDouble() * 1.5).roundToInt().coerceAtLeast(2)
            val cellW = wD / gridN
            val cellH = hD / gridN
            val arrowLen = min(cellW, cellH) * 0.35
            strokePaint.strokeWidth = max(1f, lineWidth * scale * 0.8f)
            strokePaint.strokeCap = Paint.Cap.ROUND

            for (gy in 0 until gridN) {
                for (gx in 0 until gridN) {
                    val cx = (gx + 0.5) * cellW
                    val cy = (gy + 0.5) * cellH
                    val (bx, by) = fieldAt(cx, cy, poleX, poleY, charge, nPoles)
                    val mag = sqrt(bx * bx + by * by)
                    if (mag < 1e-8) continue

                    val nx = bx / mag
                    val ny = by / mag
                    val len = arrowLen * min(1.0, mag * 0.0002)
                    val tx = cx + nx * len
                    val ty = cy + ny * len

                    val t: Float = when (colorMode) {
                        "field-strength" -> min(1.0, mag * 0.0001).toFloat()
                        "direction" -> ((atan2(by, bx) / PI + 1.0) * 0.5).toFloat()
                        else -> {
                            val divisor = max(1, nColors - 1).toFloat()
                            (((gx + gy * gridN) % nColors).toFloat()) / divisor
                        }
                    }
                    val c = lerpColor(palR, palG, palB, nColors, t)
                    strokePaint.color = Color.argb(255, c[0], c[1], c[2])

                    canvas.drawLine(cx.toFloat(), cy.toFloat(), tx.toFloat(), ty.toFloat(), strokePaint)

                    // Arrowhead — two lines from tip
                    val headLen = len * 0.3
                    val px = -ny
                    val py = nx
                    val h1x = tx - nx * headLen + px * headLen * 0.4
                    val h1y = ty - ny * headLen + py * headLen * 0.4
                    val h2x = tx - nx * headLen - px * headLen * 0.4
                    val h2y = ty - ny * headLen - py * headLen * 0.4
                    canvas.drawLine(tx.toFloat(), ty.toFloat(), h1x.toFloat(), h1y.toFloat(), strokePaint)
                    canvas.drawLine(tx.toFloat(), ty.toFloat(), h2x.toFloat(), h2y.toFloat(), strokePaint)
                }
            }

        } else if (style == "particles") {
            for (i in 0 until st.nParts) {
                val life = st.partLife[i]
                val alpha = min(1.0, life / 20.0) * max(0.0, 1.0 - life / 300.0)
                if (alpha < 0.01) continue

                val t: Float = when (colorMode) {
                    "field-strength" -> {
                        val (bx, by) = fieldAt(st.partX[i], st.partY[i], poleX, poleY, charge, nPoles)
                        min(1.0, sqrt(bx * bx + by * by) * 0.0001).toFloat()
                    }
                    "direction" -> {
                        val (bx, by) = fieldAt(st.partX[i], st.partY[i], poleX, poleY, charge, nPoles)
                        ((atan2(by, bx) / PI + 1.0) * 0.5).toFloat()
                    }
                    else -> {
                        val divisor = max(1, nColors - 1).toFloat()
                        ((i % nColors).toFloat()) / divisor
                    }
                }
                val c = lerpColor(palR, palG, palB, nColors, t)
                val a = (alpha * 255.0).toInt().coerceIn(0, 255)
                val r = ((1.5 + alpha * 2.0) * scale).toFloat()
                fillPaint.color = Color.argb(a, c[0], c[1], c[2])
                canvas.drawCircle(st.partX[i].toFloat(), st.partY[i].toFloat(), r, fillPaint)
            }
        }

        // ── Draw pole markers ───────────────────────────────────────────────
        val poleR = max(4f, 6f * scale)
        for (p in 0 until nPoles) {
            val isPos = charge[p] > 0.0
            fillPaint.color = if (isPos) Color.rgb(0xff, 0x44, 0x44) else Color.rgb(0x44, 0x88, 0xff)
            canvas.drawCircle(poleX[p].toFloat(), poleY[p].toFloat(), poleR, fillPaint)

            strokePaint.color = Color.WHITE
            strokePaint.strokeWidth = max(1f, 1.5f * scale)
            strokePaint.xfermode = null
            val sym = poleR * 0.55f
            val cx = poleX[p].toFloat()
            val cy = poleY[p].toFloat()
            canvas.drawLine(cx - sym, cy, cx + sym, cy, strokePaint)
            if (isPos) {
                canvas.drawLine(cx, cy - sym, cx, cy + sym, strokePaint)
            }
        }
    }

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float {
        val lines = pf(params, "lineCount", 80f)
        val steps = pf(params, "lineSteps", 200f)
        return (lines * steps * 0.005f / 200f).coerceIn(0.1f, 1f)
    }

    // ── Field computation ──────────────────────────────────────────────────
    private fun fieldAt(
        fx: Double, fy: Double,
        poleX: DoubleArray, poleY: DoubleArray, charge: DoubleArray, nPoles: Int
    ): Pair<Double, Double> {
        var bx = 0.0
        var by = 0.0
        for (i in 0 until nPoles) {
            val dx = fx - poleX[i]
            val dy = fy - poleY[i]
            val rSq = dx * dx + dy * dy + 1.0 // softening
            val r = sqrt(rSq)
            val inv = charge[i] / (rSq * r)
            bx += dx * inv
            by += dy * inv
        }
        return Pair(bx, by)
    }

    // ── Trace a single field line into reusable buffer ─────────────────────
    private fun traceLine(
        startX: Double, startY: Double,
        poleX: DoubleArray, poleY: DoubleArray, charge: DoubleArray, nPoles: Int,
        maxSteps: Int, stepSize: Double, w: Double, h: Double
    ): Int {
        val lx = _lineX!!
        val ly = _lineY!!
        var fx = startX
        var fy = startY
        var len = 0

        for (s in 0 until maxSteps) {
            lx[len] = fx; ly[len] = fy; len++

            val (bx, by) = fieldAt(fx, fy, poleX, poleY, charge, nPoles)
            val mag = sqrt(bx * bx + by * by)
            if (mag < 1e-6) break

            val inv = stepSize / mag
            fx += bx * inv
            fy += by * inv

            if (fx < -20.0 || fx > w + 20.0 || fy < -20.0 || fy > h + 20.0) break

            val termR = stepSize * 3.0
            val termRSq = termR * termR
            var terminated = false
            for (p in 0 until nPoles) {
                if (charge[p] >= 0.0) continue
                val dx = fx - poleX[p]
                val dy = fy - poleY[p]
                if (dx * dx + dy * dy < termRSq) {
                    if (len < lx.size) {
                        lx[len] = poleX[p]
                        ly[len] = poleY[p]
                        len++
                    }
                    terminated = true
                    break
                }
            }
            if (terminated) return len
        }
        return len
    }

    // ── Update pole positions for animation ────────────────────────────────
    private fun updatePoles(st: MagFieldState, animSpeed: Double, w: Double, h: Double) {
        val basePX = st.basePX
        val basePY = st.basePY
        val poleX = st.poleX
        val poleY = st.poleY
        val nPoles = st.nPoles
        val step = st.step
        if (animSpeed <= 0.0) {
            for (i in 0 until nPoles) {
                poleX[i] = basePX[i]
                poleY[i] = basePY[i]
            }
            return
        }
        val orbitR = min(w, h) * 0.025 * animSpeed
        val t = step * 0.015 * animSpeed
        for (i in 0 until nPoles) {
            val phase = t + i * PI * 2.0 / nPoles
            poleX[i] = basePX[i] + sin(phase) * orbitR
            poleY[i] = basePY[i] + cos(phase * 0.7) * orbitR
        }
    }

    private fun advanceParticles(st: MagFieldState, w: Double, h: Double, stepSize: Double) {
        val poleX = st.poleX
        val poleY = st.poleY
        val charge = st.charge
        val nPoles = st.nPoles
        for (i in 0 until st.nParts) {
            val (bx, by) = fieldAt(st.partX[i], st.partY[i], poleX, poleY, charge, nPoles)
            val mag = sqrt(bx * bx + by * by)
            if (mag > 1e-6) {
                val inv = (stepSize * 1.5) / mag
                st.partX[i] += bx * inv
                st.partY[i] += by * inv
            }
            st.partLife[i] += 1.0
            val px = st.partX[i]
            val py = st.partY[i]
            var dead = px < -10.0 || px > w + 10.0 || py < -10.0 || py > h + 10.0 || st.partLife[i] > 300.0
            if (!dead) {
                val termSq = stepSize * stepSize * 9.0
                for (p in 0 until nPoles) {
                    if (charge[p] >= 0.0) continue
                    val dx = px - poleX[p]
                    val dy = py - poleY[p]
                    if (dx * dx + dy * dy < termSq) {
                        dead = true
                        break
                    }
                }
            }
            if (dead) respawnParticle(st, i, w, h)
        }
    }

    private fun respawnParticle(st: MagFieldState, i: Int, w: Double, h: Double) {
        val poleX = st.poleX
        val poleY = st.poleY
        val charge = st.charge
        val nPoles = st.nPoles
        val rng = st.rng
        var posCount = 0
        for (p in 0 until nPoles) if (charge[p] > 0.0) posCount++
        if (posCount == 0) {
            st.partX[i] = rng.randomDouble() * w
            st.partY[i] = rng.randomDouble() * h
        } else {
            var pick = (rng.randomDouble() * posCount).toInt()
            var piIdx = 0
            for (p in 0 until nPoles) {
                if (charge[p] > 0.0) {
                    if (pick == 0) { piIdx = p; break }
                    pick--
                }
            }
            val a = rng.randomDouble() * PI * 2.0
            val r = min(w, h) * (0.015 + rng.randomDouble() * 0.02)
            st.partX[i] = poleX[piIdx] + cos(a) * r
            st.partY[i] = poleY[piIdx] + sin(a) * r
        }
        st.partLife[i] = 0.0
    }

    // ── Pole initialization ────────────────────────────────────────────────
    private fun initPoles(
        config: String, w: Double, h: Double, strength: Double, seed: Int
    ): MagFieldState {
        val rng = SeededRNG(seed)
        val nPoles: Int
        val bpx: DoubleArray
        val bpy: DoubleArray
        val ch: DoubleArray

        when (config) {
            "quadrupole" -> {
                nPoles = 4
                bpx = doubleArrayOf(w * 0.35, w * 0.65, w * 0.65, w * 0.35)
                bpy = doubleArrayOf(h * 0.35, h * 0.35, h * 0.65, h * 0.65)
                ch = doubleArrayOf(strength, -strength, strength, -strength)
            }
            "ring" -> {
                nPoles = 7 // 6 ring + 1 center
                bpx = DoubleArray(nPoles)
                bpy = DoubleArray(nPoles)
                ch = DoubleArray(nPoles)
                bpx[0] = w * 0.5; bpy[0] = h * 0.5; ch[0] = -strength * 1.5
                val ringR = min(w, h) * 0.28
                for (i in 0 until 6) {
                    val a = (i / 6.0) * PI * 2.0
                    bpx[1 + i] = w * 0.5 + cos(a) * ringR
                    bpy[1 + i] = h * 0.5 + sin(a) * ringR
                    ch[1 + i] = strength
                }
            }
            "random" -> {
                nPoles = 5
                bpx = DoubleArray(nPoles)
                bpy = DoubleArray(nPoles)
                ch = DoubleArray(nPoles)
                for (i in 0 until nPoles) {
                    bpx[i] = w * 0.15 + rng.randomDouble() * w * 0.7
                    bpy[i] = h * 0.15 + rng.randomDouble() * h * 0.7
                    ch[i] = (if (i % 2 == 0) 1.0 else -1.0) * (0.5 + rng.randomDouble()) * strength
                }
            }
            else -> {
                // dipole
                nPoles = 2
                bpx = doubleArrayOf(w * 0.32, w * 0.68)
                bpy = doubleArrayOf(h * 0.5, h * 0.5)
                ch = doubleArrayOf(strength, -strength)
            }
        }

        val nParts = 200
        val st = MagFieldState(
            key = "",
            nPoles = nPoles,
            basePX = bpx, basePY = bpy, charge = ch,
            poleX = bpx.copyOf(), poleY = bpy.copyOf(),
            partX = DoubleArray(nParts),
            partY = DoubleArray(nParts),
            partLife = DoubleArray(nParts),
            nParts = nParts,
            step = 0,
            rng = rng
        )
        for (i in 0 until nParts) respawnParticle(st, i, w, h)
        return st
    }

    private fun ensureLineBuf(maxSteps: Int) {
        // +1 to allow termination point append
        val needed = maxSteps + 2
        synchronized(Companion) {
            if (_lineCap < needed) {
                _lineX = DoubleArray(needed)
                _lineY = DoubleArray(needed)
                _lineCap = needed
            }
        }
    }

    // ── Color interpolation helper ─────────────────────────────────────────
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

    // ── Simulation state ───────────────────────────────────────────────────
    private class MagFieldState(
        var key: String,
        val nPoles: Int,
        val basePX: DoubleArray, val basePY: DoubleArray, val charge: DoubleArray,
        val poleX: DoubleArray, val poleY: DoubleArray,
        val partX: DoubleArray, val partY: DoubleArray, val partLife: DoubleArray,
        val nParts: Int,
        var step: Int,
        val rng: SeededRNG
    )

    companion object {
        @Volatile
        private var _state: MagFieldState? = null

        @Volatile
        private var _lineX: DoubleArray? = null

        @Volatile
        private var _lineY: DoubleArray? = null

        @Volatile
        private var _lineCap: Int = 0
    }
}
