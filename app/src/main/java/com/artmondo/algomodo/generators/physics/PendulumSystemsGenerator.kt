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

class PendulumSystemsGenerator : Generator {

    override val id = "physics-pendulum-systems"
    override val family = "physics"
    override val styleName = "Pendulum Systems"
    override val definition =
        "Multiple coupled double or triple pendulums with varied parameters and pivot layouts, " +
        "driven by configurable energy injection modes — revealing chaotic divergence through glowing tip trajectories"
    override val algorithmNotes =
        "Lagrangian mechanics: each pendulum is a double (2-segment) or triple (3-segment) system " +
        "solved via 4th-order Runge-Kutta. Per-pendulum arm length and mass variation (±15%/±20%) adds " +
        "another axis of chaotic divergence beyond initial angle spread. Layout modes (ring/cascade/scatter/center) " +
        "place pivot points for varied compositions. Drive modes (wobble/kick/rotate) inject energy to sustain " +
        "dynamics indefinitely. Trail rendering uses glow effect (thick transparent + thin bright double-draw) " +
        "with 10-bucket age-based alpha fading. Web style connects all tips with distance-faded lines."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema = listOf(
        Parameter.SelectParam(
            name = "Layout",
            key = "layout",
            group = ParamGroup.COMPOSITION,
            help = "center: shared pivot | ring: pivots in a circle | cascade: diagonal staircase | scatter: random positions",
            options = listOf("center", "ring", "cascade", "scatter"),
            default = "ring"
        ),
        Parameter.SelectParam(
            name = "Drive",
            key = "driveMode",
            group = ParamGroup.FLOW_MOTION,
            help = "none: pure physics | kick: periodic impulse | wobble: oscillating torque | rotate: rotating force",
            options = listOf("none", "kick", "wobble", "rotate"),
            default = "wobble"
        ),
        Parameter.NumberParam(
            name = "Drive Strength",
            key = "driveStrength",
            group = ParamGroup.FLOW_MOTION,
            help = "How strongly the drive mode injects energy",
            min = 0.5f, max = 5f, step = 0.5f, default = 2f
        ),
        Parameter.SelectParam(
            name = "Type",
            key = "pendulumType",
            group = ParamGroup.COMPOSITION,
            help = "double: 2-segment chaotic pendulum | triple: 3-segment for deeper chaos",
            options = listOf("double", "triple"),
            default = "double"
        ),
        Parameter.NumberParam(
            name = "Count",
            key = "count",
            group = ParamGroup.COMPOSITION,
            help = "Number of pendulums with varied initial conditions and parameters",
            min = 2f, max = 30f, step = 1f, default = 12f
        ),
        Parameter.SelectParam(
            name = "Render Style",
            key = "style",
            group = ParamGroup.COMPOSITION,
            help = "trace: glowing trails | fade: motion-blur | arms: visible segments | rainbow: hue-cycling | web: connecting mesh | energy: velocity-responsive",
            options = listOf("trace", "fade", "arms", "rainbow", "web", "energy"),
            default = "trace"
        ),
        Parameter.NumberParam(
            name = "Arm Length 1",
            key = "length1",
            group = ParamGroup.GEOMETRY,
            help = "Base length of first segment (per-pendulum variation applied)",
            min = 0.1f, max = 0.5f, step = 0.02f, default = 0.3f
        ),
        Parameter.NumberParam(
            name = "Arm Length 2",
            key = "length2",
            group = ParamGroup.GEOMETRY,
            help = "Base length of second segment (per-pendulum variation applied)",
            min = 0.1f, max = 0.5f, step = 0.02f, default = 0.3f
        ),
        Parameter.NumberParam(
            name = "Gravity",
            key = "gravity",
            group = ParamGroup.FLOW_MOTION,
            help = null,
            min = 0.5f, max = 3f, step = 0.1f, default = 1.5f
        ),
        Parameter.NumberParam(
            name = "Damping",
            key = "damping",
            group = ParamGroup.FLOW_MOTION,
            help = "1.0 = no loss, <1.0 = gradual energy decay",
            min = 0.995f, max = 1.0f, step = 0.001f, default = 0.999f
        ),
        Parameter.NumberParam(
            name = "Spread",
            key = "spread",
            group = ParamGroup.GEOMETRY,
            help = "Angular spread between initial conditions — drives chaotic divergence",
            min = 0.01f, max = 0.5f, step = 0.01f, default = 0.12f
        ),
        Parameter.NumberParam(
            name = "Trail Length",
            key = "trailLength",
            group = ParamGroup.GEOMETRY,
            help = null,
            min = 200f, max = 2000f, step = 100f, default = 1000f
        ),
        Parameter.SelectParam(
            name = "Color Mode",
            key = "colorMode",
            group = ParamGroup.COLOR,
            help = "palette: use palette colors | hue: cycle hue per pendulum | velocity: color by angular speed",
            options = listOf("palette", "hue", "velocity"),
            default = "palette"
        ),
        Parameter.NumberParam(
            name = "Steps / Frame",
            key = "stepsPerFrame",
            group = ParamGroup.FLOW_MOTION,
            help = null,
            min = 2f, max = 16f, step = 1f, default = 8f
        ),
        Parameter.NumberParam(
            name = "Audio Reactivity",
            key = "reactivity",
            group = ParamGroup.FLOW_MOTION,
            help = null,
            min = 0f, max = 2f, step = 0.1f, default = 0f
        )
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "layout" to "ring",
        "driveMode" to "wobble",
        "driveStrength" to 2f,
        "pendulumType" to "double",
        "count" to 12f,
        "style" to "trace",
        "length1" to 0.3f,
        "length2" to 0.3f,
        "gravity" to 1.5f,
        "damping" to 0.999f,
        "spread" to 0.12f,
        "trailLength" to 1000f,
        "colorMode" to "palette",
        "stepsPerFrame" to 8f,
        "reactivity" to 0f
    )

    // ── Param helpers ─────────────────────────────────────────────────
    private fun pf(p: Map<String, Any>, k: String, default: Float): Float = (p[k] as? Number)?.toFloat() ?: default
    private fun pi(p: Map<String, Any>, k: String, default: Int): Int = (p[k] as? Number)?.toInt() ?: default
    private fun ps(p: Map<String, Any>, k: String, default: String): String = (p[k] as? String) ?: default

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
        val scale = w / 600f
        val dim = min(w, h)

        val layout = ps(params, "layout", "ring")
        val driveMode = ps(params, "driveMode", "wobble")
        val driveStr = pf(params, "driveStrength", 2f).toDouble()
        val pendulumType = ps(params, "pendulumType", "double")
        val isTriple = pendulumType == "triple"
        val count = pi(params, "count", 12).coerceIn(2, 30)
        val style = ps(params, "style", "trace")
        val baseL1 = (pf(params, "length1", 0.3f) * dim).toDouble()
        val baseL2 = (pf(params, "length2", 0.3f) * dim).toDouble()
        var gravity = pf(params, "gravity", 1.5f).toDouble()
        val damping = pf(params, "damping", 0.999f).toDouble()
        val spread = pf(params, "spread", 0.12f).toDouble()
        val trailLen = pi(params, "trailLength", 1000).coerceIn(200, 2000)
        val colorMode = ps(params, "colorMode", "palette")
        var spf = pi(params, "stepsPerFrame", 8).coerceIn(2, 16)

        val rx = pf(params, "reactivity", 0f)
        val audio = params["_audioAnalysis"] as? AudioAnalysis
        val audioBass = (audio?.getBass(time) ?: 0f) * rx
        val audioMid = (audio?.getMid(time) ?: 0f) * rx
        val audioHigh = (audio?.getHigh(time) ?: 0f) * rx
        val audioEnergy = (audioBass + audioMid + audioHigh) / 3f
        gravity += audioBass.toDouble() * 1.0
        spf += (audioEnergy * 4f).roundToInt()
        spf = spf.coerceIn(2, 64)

        val g = gravity * 9.81

        // ── State cache ──
        val key = "$seed|$pendulumType|$count|$trailLen|$layout|$w|$h"
        val st: PendulumState = synchronized(Companion) {
            var cached = _state
            if (cached == null || cached.key != key) {
                cached = makeState(count, trailLen, seed)
                cached.key = key
                initPendulums(cached, isTriple, spread, layout, w, h, baseL1, baseL2)
                _state = cached
            }
            cached
        }

        // ── Warmup (static render) ──
        if (time <= 0f && st.step == 0) {
            for (f in 0 until 120) {
                for (s in 0 until spf) {
                    stepPendulums(st, isTriple, g, damping, driveMode, driveStr)
                }
            }
        }

        // ── Animation ──
        if (time > 0f) {
            for (s in 0 until spf) {
                stepPendulums(st, isTriple, g, damping, driveMode, driveStr)
            }
        }

        // ── Background ──
        if (style == "fade") {
            val fadePaint = Paint().apply {
                color = Color.argb((0.06f * 255f + 0.5f).toInt(), 10, 10, 10)
                this.style = Paint.Style.FILL
            }
            canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), fadePaint)
        } else {
            canvas.drawColor(Color.rgb(10, 10, 10))
        }

        // Build palette RGB array
        val palColors = palette.colorInts()
        val nPal = palColors.size
        val palR = IntArray(nPal) { Color.red(palColors[it]) }
        val palG = IntArray(nPal) { Color.green(palColors[it]) }
        val palB = IntArray(nPal) { Color.blue(palColors[it]) }

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val lineW = max(0.8f, 1.2f * scale)
        val BUCKETS = 10

        // ── Compute current tip positions ──
        val tipX = DoubleArray(count)
        val tipY = DoubleArray(count)
        for (i in 0 until count) {
            var tx = st.pivotX[i] + st.pL1[i] * sin(st.theta1[i])
            var ty = st.pivotY[i] + st.pL1[i] * cos(st.theta1[i])
            tx += st.pL2[i] * sin(st.theta2[i])
            ty += st.pL2[i] * cos(st.theta2[i])
            if (isTriple) {
                tx += st.pL3[i] * sin(st.theta3[i])
                ty += st.pL3[i] * cos(st.theta3[i])
            }
            tipX[i] = tx
            tipY[i] = ty
        }

        // ── Draw trails ──
        if (style == "trace" || style == "fade") {
            for (i in 0 until count) {
                val col = getPendulumColor(i, count, colorMode, palR, palG, palB, st)
                drawTrailGlow(canvas, paint, st, i, col[0], col[1], col[2], lineW, BUCKETS)
            }
            // Bright tip dots
            for (i in 0 until count) {
                val col = getPendulumColor(i, count, colorMode, palR, palG, palB, st)
                paint.style = Paint.Style.FILL
                paint.color = Color.argb((0.95f * 255f + 0.5f).toInt().coerceIn(0, 255), col[0], col[1], col[2])
                canvas.drawCircle(tipX[i].toFloat(), tipY[i].toFloat(), lineW * 2.5f, paint)
            }

        } else if (style == "rainbow") {
            paint.style = Paint.Style.STROKE
            for (i in 0 until count) {
                val off = i * st.trailLen
                val tCount = st.trailCount[i]
                if (tCount < 3) continue
                val head = st.trailHead[i]
                val tLen = st.trailLen
                val baseHue = (i.toDouble() / count) * 360.0

                for (b in 0 until BUCKETS) {
                    val ageMin = b.toDouble() / BUCKETS
                    val ageMax = (b + 1).toDouble() / BUCKETS
                    val alpha = (ageMin + ageMax) * 0.5

                    var cr: Int
                    var cg: Int
                    var cb: Int

                    when (colorMode) {
                        "palette" -> {
                            val palT = (((i.toDouble() / count) + alpha * 0.6 + st.step * 0.002) % 1.0).let {
                                if (it < 0) it + 1.0 else it
                            }
                            val s = palT * (nPal - 1)
                            val pi0 = s.toInt().coerceIn(0, nPal - 1)
                            val pi1 = min(nPal - 1, pi0 + 1)
                            val f = s - pi0
                            cr = (palR[pi0] + (palR[pi1] - palR[pi0]) * f).toInt()
                            cg = (palG[pi0] + (palG[pi1] - palG[pi0]) * f).toInt()
                            cb = (palB[pi0] + (palB[pi1] - palB[pi0]) * f).toInt()
                        }
                        "velocity" -> {
                            val speed = sqrt(st.omega1[i] * st.omega1[i] + st.omega2[i] * st.omega2[i])
                            val vt = min(1.0, speed / 12.0)
                            val ci = min(nPal - 1, (vt * (nPal - 1)).toInt())
                            cr = palR[ci]; cg = palG[ci]; cb = palB[ci]
                        }
                        else -> {
                            // hue: HSL hue cycling
                            var hue = (baseHue + alpha * 200.0 + st.step * 0.5) % 360.0
                            if (hue < 0) hue += 360.0
                            val c2 = 0.9 * (1 - abs(2 * 0.55 - 1))
                            val x2 = c2 * (1 - abs(((hue / 60.0) % 2) - 1))
                            val m2 = 0.55 - c2 / 2
                            var rr = 0.0; var gg = 0.0; var bb = 0.0
                            if (hue < 60) { rr = c2; gg = x2 }
                            else if (hue < 120) { rr = x2; gg = c2 }
                            else if (hue < 180) { gg = c2; bb = x2 }
                            else if (hue < 240) { gg = x2; bb = c2 }
                            else if (hue < 300) { rr = x2; bb = c2 }
                            else { rr = c2; bb = x2 }
                            cr = ((rr + m2) * 255 + 0.5).toInt()
                            cg = ((gg + m2) * 255 + 0.5).toInt()
                            cb = ((bb + m2) * 255 + 0.5).toInt()
                        }
                    }

                    cr = cr.coerceIn(0, 255); cg = cg.coerceIn(0, 255); cb = cb.coerceIn(0, 255)

                    // Glow on newest
                    if (alpha > 0.6) {
                        paint.strokeWidth = lineW * 4f
                        paint.color = Color.argb((alpha * 0.12 * 255 + 0.5).toInt().coerceIn(0, 255), cr, cg, cb)
                        val path = Path()
                        var moved = false
                        for (j in 0 until tCount - 1) {
                            val age = j.toDouble() / (tCount - 1)
                            if (age < ageMin || age >= ageMax) continue
                            val idx0 = ((head - tCount + j) % tLen + tLen) % tLen
                            val idx1 = (idx0 + 1) % tLen
                            if (!moved) {
                                path.moveTo(st.trailX[off + idx0].toFloat(), st.trailY[off + idx0].toFloat())
                                moved = true
                            }
                            path.lineTo(st.trailX[off + idx1].toFloat(), st.trailY[off + idx1].toFloat())
                        }
                        if (moved) canvas.drawPath(path, paint)
                    }

                    paint.strokeWidth = lineW * (0.5f + alpha.toFloat() * 0.8f)
                    paint.color = Color.argb((alpha * 0.9 * 255 + 0.5).toInt().coerceIn(0, 255), cr, cg, cb)
                    val path2 = Path()
                    var moved2 = false
                    for (j in 0 until tCount - 1) {
                        val age = j.toDouble() / (tCount - 1)
                        if (age < ageMin || age >= ageMax) continue
                        val idx0 = ((head - tCount + j) % tLen + tLen) % tLen
                        val idx1 = (idx0 + 1) % tLen
                        if (!moved2) {
                            path2.moveTo(st.trailX[off + idx0].toFloat(), st.trailY[off + idx0].toFloat())
                            moved2 = true
                        }
                        path2.lineTo(st.trailX[off + idx1].toFloat(), st.trailY[off + idx1].toFloat())
                    }
                    if (moved2) canvas.drawPath(path2, paint)
                }
            }

        } else if (style == "web") {
            // Thin trails with 4 buckets
            paint.style = Paint.Style.STROKE
            for (i in 0 until count) {
                val col = getPendulumColor(i, count, colorMode, palR, palG, palB, st)
                val cr = col[0]; val cg = col[1]; val cb = col[2]
                val off = i * st.trailLen
                val tCount = st.trailCount[i]
                if (tCount < 3) continue
                val head = st.trailHead[i]
                val tLen = st.trailLen

                for (b in 0 until 4) {
                    val ageMin = b / 4.0
                    val ageMax = (b + 1) / 4.0
                    val alpha = (ageMin + ageMax) * 0.5
                    paint.strokeWidth = lineW * 0.6f
                    paint.color = Color.argb((alpha * 0.4 * 255 + 0.5).toInt().coerceIn(0, 255), cr, cg, cb)
                    val path = Path()
                    var moved = false
                    for (j in 0 until tCount - 1) {
                        val age = j.toDouble() / (tCount - 1)
                        if (age < ageMin || age >= ageMax) continue
                        val idx0 = ((head - tCount + j) % tLen + tLen) % tLen
                        val idx1 = (idx0 + 1) % tLen
                        if (!moved) {
                            path.moveTo(st.trailX[off + idx0].toFloat(), st.trailY[off + idx0].toFloat())
                            moved = true
                        }
                        path.lineTo(st.trailX[off + idx1].toFloat(), st.trailY[off + idx1].toFloat())
                    }
                    if (moved) canvas.drawPath(path, paint)
                }
            }

            // Connecting mesh
            val maxDist = dim * 0.6
            paint.style = Paint.Style.STROKE
            for (i in 0 until count) {
                for (j in i + 1 until count) {
                    val dx = tipX[j] - tipX[i]
                    val dy = tipY[j] - tipY[i]
                    val dist = sqrt(dx * dx + dy * dy)
                    if (dist > maxDist) continue
                    val alpha = (1 - dist / maxDist) * 0.6
                    val col = getPendulumColor((i + j) % count, count, colorMode, palR, palG, palB, st)
                    paint.color = Color.argb((alpha * 255 + 0.5).toInt().coerceIn(0, 255), col[0], col[1], col[2])
                    paint.strokeWidth = lineW * (1f + (1f - (dist / maxDist).toFloat()) * 2f)
                    canvas.drawLine(tipX[i].toFloat(), tipY[i].toFloat(), tipX[j].toFloat(), tipY[j].toFloat(), paint)
                }
            }

            // Bright tip dots + glow
            paint.style = Paint.Style.FILL
            for (i in 0 until count) {
                val col = getPendulumColor(i, count, colorMode, palR, palG, palB, st)
                paint.color = Color.argb((0.95f * 255f + 0.5f).toInt().coerceIn(0, 255), col[0], col[1], col[2])
                canvas.drawCircle(tipX[i].toFloat(), tipY[i].toFloat(), lineW * 3f, paint)
                paint.color = Color.argb((0.15f * 255f + 0.5f).toInt().coerceIn(0, 255), col[0], col[1], col[2])
                canvas.drawCircle(tipX[i].toFloat(), tipY[i].toFloat(), lineW * 8f, paint)
            }

        } else if (style == "energy") {
            paint.style = Paint.Style.STROKE
            for (i in 0 until count) {
                val off = i * st.trailLen
                val tCount = st.trailCount[i]
                if (tCount < 3) continue
                val head = st.trailHead[i]
                val tLen = st.trailLen
                val nPalLocal = nPal

                val baseCr: Int; val baseCg: Int; val baseCb: Int
                if (colorMode != "velocity") {
                    val col = getPendulumColor(i, count, colorMode, palR, palG, palB, st)
                    baseCr = col[0]; baseCg = col[1]; baseCb = col[2]
                } else {
                    baseCr = 0; baseCg = 0; baseCb = 0
                }

                val segStep = 3
                var j = 0
                while (j < tCount - segStep) {
                    val age = j.toDouble() / (tCount - 1)
                    val idx0 = ((head - tCount + j) % tLen + tLen) % tLen
                    val idx1 = ((head - tCount + j + segStep) % tLen + tLen) % tLen
                    val x0 = st.trailX[off + idx0]; val y0 = st.trailY[off + idx0]
                    val x1 = st.trailX[off + idx1]; val y1 = st.trailY[off + idx1]
                    val dx = x1 - x0; val dy = y1 - y0
                    val vel = sqrt(dx * dx + dy * dy) / segStep
                    val vt = min(1.0, vel / (dim * 0.02))

                    val cr: Int; val cg: Int; val cb: Int
                    if (colorMode == "velocity") {
                        val ci = min(nPalLocal - 1, (vt * (nPalLocal - 1)).toInt())
                        cr = palR[ci]; cg = palG[ci]; cb = palB[ci]
                    } else {
                        cr = baseCr; cg = baseCg; cb = baseCb
                    }
                    val alpha = age * 0.8 + 0.05
                    val lw = lineW * (0.5f + vt.toFloat() * 3f)

                    if (alpha > 0.3 && vt > 0.2) {
                        paint.strokeWidth = lw * 3f
                        paint.color = Color.argb((alpha * 0.12 * 255 + 0.5).toInt().coerceIn(0, 255), cr, cg, cb)
                        canvas.drawLine(x0.toFloat(), y0.toFloat(), x1.toFloat(), y1.toFloat(), paint)
                    }
                    paint.strokeWidth = lw
                    paint.color = Color.argb((alpha * 255 + 0.5).toInt().coerceIn(0, 255), cr, cg, cb)
                    canvas.drawLine(x0.toFloat(), y0.toFloat(), x1.toFloat(), y1.toFloat(), paint)

                    j += segStep
                }
            }

        } else {
            // arms: trail + segments + joints
            for (i in 0 until count) {
                val col = getPendulumColor(i, count, colorMode, palR, palG, palB, st)
                drawTrailGlow(canvas, paint, st, i, col[0], col[1], col[2], lineW * 0.7f, 6)
            }
        }

        // ── Arms overlay (for 'arms' style) ──
        if (style == "arms") {
            paint.style = Paint.Style.FILL
            paint.color = Color.WHITE
            for (i in 0 until count) {
                canvas.drawCircle(st.pivotX[i].toFloat(), st.pivotY[i].toFloat(), 3f * scale, paint)
            }

            for (i in 0 until count) {
                val col = getPendulumColor(i, count, colorMode, palR, palG, palB, st)
                val cr = col[0]; val cg = col[1]; val cb = col[2]
                val j1x = st.pivotX[i] + st.pL1[i] * sin(st.theta1[i])
                val j1y = st.pivotY[i] + st.pL1[i] * cos(st.theta1[i])
                val j2x = j1x + st.pL2[i] * sin(st.theta2[i])
                val j2y = j1y + st.pL2[i] * cos(st.theta2[i])

                // Arm glow
                paint.style = Paint.Style.STROKE
                paint.color = Color.argb((0.2f * 255f + 0.5f).toInt(), cr, cg, cb)
                paint.strokeWidth = max(3f, 5f * scale)
                canvas.drawLine(st.pivotX[i].toFloat(), st.pivotY[i].toFloat(), j1x.toFloat(), j1y.toFloat(), paint)
                canvas.drawLine(j1x.toFloat(), j1y.toFloat(), j2x.toFloat(), j2y.toFloat(), paint)
                if (isTriple) {
                    val tx = j2x + st.pL3[i] * sin(st.theta3[i])
                    val ty = j2y + st.pL3[i] * cos(st.theta3[i])
                    canvas.drawLine(j2x.toFloat(), j2y.toFloat(), tx.toFloat(), ty.toFloat(), paint)
                }

                // Arm core
                paint.color = Color.argb((0.85f * 255f + 0.5f).toInt(), cr, cg, cb)
                paint.strokeWidth = max(1.5f, 2.5f * scale)
                canvas.drawLine(st.pivotX[i].toFloat(), st.pivotY[i].toFloat(), j1x.toFloat(), j1y.toFloat(), paint)
                canvas.drawLine(j1x.toFloat(), j1y.toFloat(), j2x.toFloat(), j2y.toFloat(), paint)
                if (isTriple) {
                    val tx = j2x + st.pL3[i] * sin(st.theta3[i])
                    val ty = j2y + st.pL3[i] * cos(st.theta3[i])
                    canvas.drawLine(j2x.toFloat(), j2y.toFloat(), tx.toFloat(), ty.toFloat(), paint)
                }

                // Joints
                paint.style = Paint.Style.FILL
                paint.color = Color.argb((0.9f * 255f + 0.5f).toInt(), cr, cg, cb)
                canvas.drawCircle(j1x.toFloat(), j1y.toFloat(), 3f * scale, paint)
                canvas.drawCircle(j2x.toFloat(), j2y.toFloat(), 3f * scale, paint)
                if (isTriple) {
                    val tx = j2x + st.pL3[i] * sin(st.theta3[i])
                    val ty = j2y + st.pL3[i] * cos(st.theta3[i])
                    canvas.drawCircle(tx.toFloat(), ty.toFloat(), 2.5f * scale, paint)
                }
            }
        }
    }

    // ── Trail drawing helper (glow) ────────────────────────────────────
    private fun drawTrailGlow(
        canvas: Canvas, paint: Paint, st: PendulumState, i: Int,
        cr: Int, cg: Int, cb: Int, lineW: Float, buckets: Int
    ) {
        val off = i * st.trailLen
        val tCount = st.trailCount[i]
        if (tCount < 3) return
        val head = st.trailHead[i]
        val tLen = st.trailLen
        paint.style = Paint.Style.STROKE

        for (b in 0 until buckets) {
            val ageMin = b.toDouble() / buckets
            val ageMax = (b + 1).toDouble() / buckets
            val alpha = (ageMin + ageMax) * 0.5

            // Glow pass — newest 40% of trail
            if (alpha > 0.6) {
                paint.strokeWidth = lineW * 5f
                paint.color = Color.argb((alpha * 0.1 * 255 + 0.5).toInt().coerceIn(0, 255), cr, cg, cb)
                val path = Path()
                var moved = false
                for (j in 0 until tCount - 1) {
                    val age = j.toDouble() / (tCount - 1)
                    if (age < ageMin || age >= ageMax) continue
                    val idx0 = ((head - tCount + j) % tLen + tLen) % tLen
                    val idx1 = (idx0 + 1) % tLen
                    if (!moved) {
                        path.moveTo(st.trailX[off + idx0].toFloat(), st.trailY[off + idx0].toFloat())
                        moved = true
                    }
                    path.lineTo(st.trailX[off + idx1].toFloat(), st.trailY[off + idx1].toFloat())
                }
                if (moved) canvas.drawPath(path, paint)
            }

            // Core pass
            paint.strokeWidth = lineW * (0.5f + alpha.toFloat() * 0.8f)
            paint.color = Color.argb((alpha * 0.85 * 255 + 0.5).toInt().coerceIn(0, 255), cr, cg, cb)
            val path2 = Path()
            var moved2 = false
            for (j in 0 until tCount - 1) {
                val age = j.toDouble() / (tCount - 1)
                if (age < ageMin || age >= ageMax) continue
                val idx0 = ((head - tCount + j) % tLen + tLen) % tLen
                val idx1 = (idx0 + 1) % tLen
                if (!moved2) {
                    path2.moveTo(st.trailX[off + idx0].toFloat(), st.trailY[off + idx0].toFloat())
                    moved2 = true
                }
                path2.lineTo(st.trailX[off + idx1].toFloat(), st.trailY[off + idx1].toFloat())
            }
            if (moved2) canvas.drawPath(path2, paint)
        }
    }

    // ── Get pendulum color ─────────────────────────────────────────────
    private fun getPendulumColor(
        i: Int, count: Int, colorMode: String,
        palR: IntArray, palG: IntArray, palB: IntArray,
        st: PendulumState
    ): IntArray {
        if (colorMode == "hue") {
            val hue = (i.toDouble() / count) * 360.0
            val c = 0.7 * 255
            val x = c * (1 - abs(((hue / 60.0) % 2) - 1))
            val m = 0.15 * 255
            return when {
                hue < 60 -> intArrayOf((c + m).toInt(), (x + m).toInt(), m.toInt())
                hue < 120 -> intArrayOf((x + m).toInt(), (c + m).toInt(), m.toInt())
                hue < 180 -> intArrayOf(m.toInt(), (c + m).toInt(), (x + m).toInt())
                hue < 240 -> intArrayOf(m.toInt(), (x + m).toInt(), (c + m).toInt())
                hue < 300 -> intArrayOf((x + m).toInt(), m.toInt(), (c + m).toInt())
                else -> intArrayOf((c + m).toInt(), m.toInt(), (x + m).toInt())
            }
        }
        if (colorMode == "velocity") {
            val speed = sqrt(st.omega1[i] * st.omega1[i] + st.omega2[i] * st.omega2[i])
            val t = min(1.0, speed / 12.0)
            val ci = min(palR.size - 1, (t * (palR.size - 1)).toInt())
            return intArrayOf(palR[ci], palG[ci], palB[ci])
        }
        val ci = i % palR.size
        return intArrayOf(palR[ci], palG[ci], palB[ci])
    }

    // ── State class ────────────────────────────────────────────────────
    class PendulumState(count: Int, val trailLen: Int, seed: Int) {
        var key: String = ""
        val count: Int = count
        val theta1 = DoubleArray(count); val omega1 = DoubleArray(count)
        val theta2 = DoubleArray(count); val omega2 = DoubleArray(count)
        val theta3 = DoubleArray(count); val omega3 = DoubleArray(count)
        val pivotX = DoubleArray(count); val pivotY = DoubleArray(count)
        val pL1 = DoubleArray(count); val pL2 = DoubleArray(count); val pL3 = DoubleArray(count)
        val pM1 = DoubleArray(count); val pM2 = DoubleArray(count)
        val trailX = DoubleArray(count * trailLen)
        val trailY = DoubleArray(count * trailLen)
        val trailHead = IntArray(count)
        val trailCount = IntArray(count)
        var step: Int = 0
        val rng = SeededRNG(seed)
    }

    private fun makeState(count: Int, trailLen: Int, seed: Int): PendulumState =
        PendulumState(count, trailLen, seed)

    // ── Initialize ─────────────────────────────────────────────────────
    private fun initPendulums(
        st: PendulumState, isTriple: Boolean, spread: Double,
        layout: String, w: Int, h: Int,
        baseL1: Double, baseL2: Double
    ) {
        val count = st.count
        val rng = st.rng
        val dim = min(w, h).toDouble()
        val cx = w * 0.5
        val cy = h * 0.5

        var layoutExtent = 0.0
        if (layout == "ring") layoutExtent = dim * 0.12
        else if (layout == "cascade") layoutExtent = dim * 0.18
        else if (layout == "scatter") layoutExtent = dim * 0.15

        val maxReach = dim * 0.42 - layoutExtent
        val rawTotal = baseL1 + baseL2 + (if (isTriple) baseL2 * 0.7 else 0.0)
        val armScale = if (rawTotal > maxReach) maxReach / rawTotal else 1.0
        val effL1 = baseL1 * armScale
        val effL2 = baseL2 * armScale

        val totalLength = effL1 + effL2 + (if (isTriple) effL2 * 0.7 else 0.0)
        val gravityShift = totalLength * 0.2

        for (i in 0 until count) {
            val t = if (count > 1) i.toDouble() / (count - 1) else 0.5
            when (layout) {
                "ring" -> {
                    val angle = (i.toDouble() / count) * PI * 2 - PI * 0.5
                    st.pivotX[i] = cx + cos(angle) * layoutExtent
                    st.pivotY[i] = cy - gravityShift + sin(angle) * layoutExtent
                }
                "cascade" -> {
                    st.pivotX[i] = cx + (t - 0.5) * dim * 0.5
                    st.pivotY[i] = cy - gravityShift + (t - 0.5) * dim * 0.15
                }
                "scatter" -> {
                    st.pivotX[i] = cx + (rng.random() - 0.5) * dim * 0.3
                    st.pivotY[i] = cy - gravityShift + (rng.random() - 0.5) * dim * 0.2
                }
                else -> {
                    st.pivotX[i] = cx
                    st.pivotY[i] = cy - gravityShift
                }
            }
        }

        for (i in 0 until count) {
            st.pL1[i] = effL1 * (0.85 + rng.random() * 0.3)
            st.pL2[i] = effL2 * (0.85 + rng.random() * 0.3)
            st.pL3[i] = if (isTriple) st.pL2[i] * (0.6 + rng.random() * 0.2) else 0.0
            st.pM1[i] = 2.0 * (0.8 + rng.random() * 0.4)
            st.pM2[i] = 1.0 * (0.8 + rng.random() * 0.4)
        }

        val baseAngle = rng.range((PI * 0.3).toFloat(), (PI * 0.7).toFloat()).toDouble()
        val baseOmega = rng.range(1.5f, 4.0f).toDouble() * (if (rng.random() > 0.5f) 1 else -1)
        for (i in 0 until count) {
            val offset = (i - count / 2.0) * spread
            st.theta1[i] = baseAngle + offset + rng.range(-0.05f, 0.05f)
            st.theta2[i] = baseAngle * 0.7 + offset * 0.6 + rng.range(-0.08f, 0.08f)
            st.omega1[i] = baseOmega + rng.range(-1.5f, 1.5f)
            st.omega2[i] = baseOmega * 0.6 + rng.range(-2.0f, 2.0f)

            if (isTriple) {
                st.theta3[i] = baseAngle * 0.5 + offset * 0.3 + rng.range(-0.1f, 0.1f)
                st.omega3[i] = rng.range(-3.0f, 3.0f).toDouble()
            }

            st.trailHead[i] = 0
            st.trailCount[i] = 0
        }
    }

    // ── Double pendulum derivatives ────────────────────────────────────
    private fun doublePendulumDerivs(
        t1: Double, w1: Double, t2: Double, w2: Double,
        m1: Double, m2: Double, L1: Double, L2: Double, g: Double,
        out: DoubleArray
    ) {
        val dt = t1 - t2
        val sinDt = sin(dt)
        val cosDt = cos(dt)
        val M = m1 + m2
        val den1 = L1 * (2 * m1 + m2 - m2 * cos(2 * dt))
        val dw1 = (
            -g * (2 * m1 + m2) * sin(t1)
            - m2 * g * sin(t1 - 2 * t2)
            - 2 * sinDt * m2 * (w2 * w2 * L2 + w1 * w1 * L1 * cosDt)
        ) / den1
        val den2 = L2 * (2 * m1 + m2 - m2 * cos(2 * dt))
        val dw2 = (
            2 * sinDt * (
                w1 * w1 * L1 * M
                + g * M * cos(t1)
                + w2 * w2 * L2 * m2 * cosDt
            )
        ) / den2
        out[0] = w1; out[1] = dw1; out[2] = w2; out[3] = dw2
    }

    // ── Triple pendulum derivatives ────────────────────────────────────
    private fun triplePendulumDerivs(
        t1: Double, w1: Double, t2: Double, w2: Double, t3: Double, w3: Double,
        m: Double, L1: Double, L2: Double, L3: Double, g: Double,
        out: DoubleArray
    ) {
        val dt12 = t1 - t2
        val sin12 = sin(dt12)
        val cos12 = cos(dt12)
        val dt23 = t2 - t3
        val sin23 = sin(dt23)
        val cos23 = cos(dt23)
        val den1 = L1 * (3 - cos12 * cos12)
        val dw1 = (
            -g * 3 * sin(t1)
            - sin(dt12) * (w2 * w2 * L2 + w1 * w1 * L1 * cos12)
            - 0.5 * sin12 * w3 * w3 * L3 * 0.3
        ) / den1
        val den2 = L2 * (2 - 0.5 * cos12 * cos12 - 0.5 * cos23 * cos23)
        val dw2 = (
            -g * 2 * sin(t2)
            + sin12 * (w1 * w1 * L1 + g * cos(t1))
            - sin23 * (w3 * w3 * L3 + w2 * w2 * L2 * cos23) * 0.5
        ) / den2
        val den3 = L3 * (1 - 0.25 * cos23 * cos23)
        val dw3 = (
            -g * sin(t3)
            + sin23 * (w2 * w2 * L2 + g * cos(t2)) * 0.5
        ) / den3
        out[0] = w1; out[1] = dw1; out[2] = w2; out[3] = dw2; out[4] = w3; out[5] = dw3
    }

    // ── RK4 double ─────────────────────────────────────────────────────
    private fun rk4Double(
        t1: Double, w1: Double, t2: Double, w2: Double,
        m1: Double, m2: Double, L1: Double, L2: Double, g: Double,
        damping: Double, dt: Double,
        result: DoubleArray
    ) {
        val k1 = DoubleArray(4); val k2 = DoubleArray(4); val k3 = DoubleArray(4); val k4 = DoubleArray(4)
        doublePendulumDerivs(t1, w1, t2, w2, m1, m2, L1, L2, g, k1)
        doublePendulumDerivs(
            t1 + k1[0] * dt * 0.5, w1 + k1[1] * dt * 0.5,
            t2 + k1[2] * dt * 0.5, w2 + k1[3] * dt * 0.5, m1, m2, L1, L2, g, k2)
        doublePendulumDerivs(
            t1 + k2[0] * dt * 0.5, w1 + k2[1] * dt * 0.5,
            t2 + k2[2] * dt * 0.5, w2 + k2[3] * dt * 0.5, m1, m2, L1, L2, g, k3)
        doublePendulumDerivs(
            t1 + k3[0] * dt, w1 + k3[1] * dt,
            t2 + k3[2] * dt, w2 + k3[3] * dt, m1, m2, L1, L2, g, k4)
        result[0] = t1 + (dt / 6) * (k1[0] + 2 * k2[0] + 2 * k3[0] + k4[0])
        result[1] = (w1 + (dt / 6) * (k1[1] + 2 * k2[1] + 2 * k3[1] + k4[1])) * damping
        result[2] = t2 + (dt / 6) * (k1[2] + 2 * k2[2] + 2 * k3[2] + k4[2])
        result[3] = (w2 + (dt / 6) * (k1[3] + 2 * k2[3] + 2 * k3[3] + k4[3])) * damping
    }

    // ── RK4 triple ─────────────────────────────────────────────────────
    private fun rk4Triple(
        t1: Double, w1: Double, t2: Double, w2: Double, t3: Double, w3: Double,
        m: Double, L1: Double, L2: Double, L3: Double, g: Double,
        damping: Double, dt: Double,
        result: DoubleArray
    ) {
        val k1 = DoubleArray(6); val k2 = DoubleArray(6); val k3 = DoubleArray(6); val k4 = DoubleArray(6)
        triplePendulumDerivs(t1, w1, t2, w2, t3, w3, m, L1, L2, L3, g, k1)
        triplePendulumDerivs(
            t1 + k1[0] * dt * 0.5, w1 + k1[1] * dt * 0.5,
            t2 + k1[2] * dt * 0.5, w2 + k1[3] * dt * 0.5,
            t3 + k1[4] * dt * 0.5, w3 + k1[5] * dt * 0.5, m, L1, L2, L3, g, k2)
        triplePendulumDerivs(
            t1 + k2[0] * dt * 0.5, w1 + k2[1] * dt * 0.5,
            t2 + k2[2] * dt * 0.5, w2 + k2[3] * dt * 0.5,
            t3 + k2[4] * dt * 0.5, w3 + k2[5] * dt * 0.5, m, L1, L2, L3, g, k3)
        triplePendulumDerivs(
            t1 + k3[0] * dt, w1 + k3[1] * dt,
            t2 + k3[2] * dt, w2 + k3[3] * dt,
            t3 + k3[4] * dt, w3 + k3[5] * dt, m, L1, L2, L3, g, k4)
        result[0] = t1 + (dt / 6) * (k1[0] + 2 * k2[0] + 2 * k3[0] + k4[0])
        result[1] = (w1 + (dt / 6) * (k1[1] + 2 * k2[1] + 2 * k3[1] + k4[1])) * damping
        result[2] = t2 + (dt / 6) * (k1[2] + 2 * k2[2] + 2 * k3[2] + k4[2])
        result[3] = (w2 + (dt / 6) * (k1[3] + 2 * k2[3] + 2 * k3[3] + k4[3])) * damping
        result[4] = t3 + (dt / 6) * (k1[4] + 2 * k2[4] + 2 * k3[4] + k4[4])
        result[5] = (w3 + (dt / 6) * (k1[5] + 2 * k2[5] + 2 * k3[5] + k4[5])) * damping
    }

    // ── Step ───────────────────────────────────────────────────────────
    private fun stepPendulums(
        st: PendulumState, isTriple: Boolean,
        g: Double, damping: Double,
        driveMode: String, driveStrength: Double
    ) {
        val dt = 0.015
        val count = st.count
        val trailLen = st.trailLen
        val step = st.step

        val resD = DoubleArray(4)
        val resT = DoubleArray(6)

        for (i in 0 until count) {
            val L1 = st.pL1[i]; val L2 = st.pL2[i]; val L3 = st.pL3[i]
            val m1 = st.pM1[i]; val m2 = st.pM2[i]

            if (isTriple) {
                rk4Triple(
                    st.theta1[i], st.omega1[i], st.theta2[i], st.omega2[i],
                    st.theta3[i], st.omega3[i], m1, L1, L2, L3, g, damping, dt, resT)
                st.theta1[i] = resT[0]; st.omega1[i] = resT[1]
                st.theta2[i] = resT[2]; st.omega2[i] = resT[3]
                st.theta3[i] = resT[4]; st.omega3[i] = resT[5]
            } else {
                rk4Double(
                    st.theta1[i], st.omega1[i], st.theta2[i], st.omega2[i],
                    m1, m2, L1, L2, g, damping, dt, resD)
                st.theta1[i] = resD[0]; st.omega1[i] = resD[1]
                st.theta2[i] = resD[2]; st.omega2[i] = resD[3]
            }

            // Drive modes
            when (driveMode) {
                "wobble" -> {
                    val phase1 = step * 0.012 + i * 0.8
                    val phase2 = step * 0.009 + i * 1.2
                    st.omega1[i] += driveStrength * 0.025 * sin(phase1)
                    st.omega2[i] += driveStrength * 0.02 * sin(phase2)
                    if (isTriple) st.omega3[i] += driveStrength * 0.015 * cos(phase1 * 0.6)
                }
                "rotate" -> {
                    val dir = if (i % 2 == 0) 1 else -1
                    val bias = driveStrength * 0.015 * dir
                    val wobble = driveStrength * 0.008 * sin(step * 0.05 + i * 2.1)
                    st.omega1[i] += bias + wobble
                    st.omega2[i] += bias * 0.8 + wobble * 0.6
                    if (isTriple) st.omega3[i] += bias * 0.6 + wobble * 0.4
                }
                "kick" -> {
                    if (step % 40 == 0) {
                        val strength = driveStrength * 0.6
                        st.omega1[i] += (st.rng.random() - 0.5) * strength
                        st.omega2[i] += (st.rng.random() - 0.5) * strength
                        if (isTriple) st.omega3[i] += (st.rng.random() - 0.5) * strength * 0.8
                    }
                    if (step % 8 == 0) {
                        st.omega2[i] += (st.rng.random() - 0.5) * driveStrength * 0.06
                    }
                }
            }

            // Compute tip and store in trail
            var tipX = st.pivotX[i] + L1 * sin(st.theta1[i])
            var tipY = st.pivotY[i] + L1 * cos(st.theta1[i])
            tipX += L2 * sin(st.theta2[i])
            tipY += L2 * cos(st.theta2[i])
            if (isTriple) {
                tipX += L3 * sin(st.theta3[i])
                tipY += L3 * cos(st.theta3[i])
            }

            val off = i * trailLen
            val head = st.trailHead[i]
            st.trailX[off + head] = tipX
            st.trailY[off + head] = tipY
            st.trailHead[i] = (head + 1) % trailLen
            if (st.trailCount[i] < trailLen) st.trailCount[i]++
        }

        st.step++
    }

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float {
        val count = pf(params, "count", 12f)
        val spf = pf(params, "stepsPerFrame", 8f)
        val trailLen = pf(params, "trailLength", 1000f)
        val isTriple = if (ps(params, "pendulumType", "double") == "triple") 1.5f else 1f
        val raw = count * spf * isTriple * 2f + count * trailLen * 0.003f
        return (raw / 1000f).coerceIn(0.1f, 1f)
    }

    companion object {
        @Volatile
        private var _state: PendulumState? = null
    }
}
