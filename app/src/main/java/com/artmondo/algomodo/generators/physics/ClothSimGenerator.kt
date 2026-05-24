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

class ClothSimGenerator : Generator {

    override val id = "physics-cloth-sim"
    override val family = "physics"
    override val styleName = "Cloth Simulation"
    override val definition =
        "Spring-mass grid where nodes connected by structural, shear, and bend springs " +
        "deform under gravity and oscillating wind \u2014 pin constraints hold anchor " +
        "points while the fabric billows and drapes"
    override val algorithmNotes =
        "Verlet integration for position-based dynamics: x' = x + (x \u2212 prevX) \u00d7 " +
        "damping + forces. Springs: structural (horizontal/vertical neighbours), shear " +
        "(diagonals), bend (skip-one). Constraint relaxation iterates springs multiple " +
        "times per step, adjusting endpoints toward rest length with configurable " +
        "stiffness. Pin modes: top-edge (curtain), corners (hammock), top-corners (flag), " +
        "center (suspended). Wind is sinusoidal: amplitude \u00d7 sin(time \u00d7 frequency " +
        "+ row \u00d7 0.5). Rendering: mesh (lines + dots), filled (quad cells as two " +
        "triangles), wireframe (lines only), stress (spring strain mapped to palette). " +
        "Audio reactivity: bass \u2192 gravity pulse, mid \u2192 wind gust, energy \u2192 " +
        "shake non-pinned nodes."

    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema = listOf(
        Parameter.NumberParam(
            name = "Grid Width", key = "gridWidth",
            group = ParamGroup.COMPOSITION, help = null,
            min = 8f, max = 40f, step = 2f, default = 20f
        ),
        Parameter.NumberParam(
            name = "Grid Height", key = "gridHeight",
            group = ParamGroup.COMPOSITION, help = null,
            min = 8f, max = 40f, step = 2f, default = 20f
        ),
        Parameter.SelectParam(
            name = "Style", key = "style",
            group = ParamGroup.COMPOSITION,
            help = "mesh: springs + nodes | filled: coloured quad cells | wireframe: springs only | stress: strain-coloured springs",
            options = listOf("mesh", "filled", "wireframe", "stress"),
            default = "mesh"
        ),
        Parameter.SelectParam(
            name = "Pin Mode", key = "pinMode",
            group = ParamGroup.GEOMETRY,
            help = "Which nodes are fixed in place",
            options = listOf("top-edge", "corners", "top-corners", "center"),
            default = "top-edge"
        ),
        Parameter.NumberParam(
            name = "Stiffness", key = "stiffness",
            group = ParamGroup.FLOW_MOTION,
            help = "Spring stiffness \u2014 higher = more rigid cloth",
            min = 0.1f, max = 1.0f, step = 0.05f, default = 0.5f
        ),
        Parameter.NumberParam(
            name = "Damping", key = "damping",
            group = ParamGroup.FLOW_MOTION, help = null,
            min = 0.9f, max = 0.999f, step = 0.005f, default = 0.98f
        ),
        Parameter.NumberParam(
            name = "Gravity", key = "gravity",
            group = ParamGroup.FLOW_MOTION, help = null,
            min = 0f, max = 2f, step = 0.05f, default = 0.5f
        ),
        Parameter.NumberParam(
            name = "Wind", key = "wind",
            group = ParamGroup.FLOW_MOTION,
            help = "Oscillating horizontal wind force",
            min = 0f, max = 2f, step = 0.1f, default = 0.3f
        ),
        Parameter.NumberParam(
            name = "Wind Freq", key = "windFrequency",
            group = ParamGroup.FLOW_MOTION, help = null,
            min = 0.5f, max = 4f, step = 0.5f, default = 1f
        ),
        Parameter.NumberParam(
            name = "Rest Length", key = "restLength",
            group = ParamGroup.GEOMETRY,
            help = "Spring rest length multiplier \u2014 affects cloth density",
            min = 0.5f, max = 2f, step = 0.1f, default = 1f
        ),
        Parameter.NumberParam(
            name = "Node Size", key = "nodeSize",
            group = ParamGroup.GEOMETRY, help = null,
            min = 0f, max = 8f, step = 1f, default = 3f
        ),
        Parameter.SelectParam(
            name = "Color Mode", key = "colorMode",
            group = ParamGroup.COLOR,
            help = "stress: spring strain \u2192 palette | depth: vertical position | palette: grid position cycle | uniform: single colour",
            options = listOf("stress", "depth", "palette", "uniform"),
            default = "stress"
        ),
        Parameter.NumberParam(
            name = "Steps / Frame", key = "stepsPerFrame",
            group = ParamGroup.FLOW_MOTION, help = null,
            min = 1f, max = 8f, step = 1f, default = 4f
        ),
        Parameter.NumberParam(
            name = "Audio Reactivity", key = "reactivity",
            group = ParamGroup.FLOW_MOTION, help = null,
            min = 0f, max = 2f, step = 0.1f, default = 0f
        )
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "gridWidth" to 20f,
        "gridHeight" to 20f,
        "style" to "mesh",
        "pinMode" to "top-edge",
        "stiffness" to 0.5f,
        "damping" to 0.98f,
        "gravity" to 0.5f,
        "wind" to 0.3f,
        "windFrequency" to 1f,
        "restLength" to 1f,
        "nodeSize" to 3f,
        "colorMode" to "stress",
        "stepsPerFrame" to 4f,
        "reactivity" to 0f
    )

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

        val cols = max(3, pi(params, "gridWidth", 20))
        val rows = max(3, pi(params, "gridHeight", 20))
        val style = ps(params, "style", "mesh")
        val pinMode = ps(params, "pinMode", "top-edge")
        val stiffness = pf(params, "stiffness", 0.5f)
        val dampingVal = pf(params, "damping", 0.98f)
        var gravity = pf(params, "gravity", 0.5f)
        var windAmp = pf(params, "wind", 0.3f)
        val windFreq = pf(params, "windFrequency", 1f)
        val restLen = pf(params, "restLength", 1f)
        var nodeSize = pf(params, "nodeSize", 3f)
        val colorMode = ps(params, "colorMode", "stress")
        val spf = max(1, pi(params, "stepsPerFrame", 4))

        // Audio reactivity
        val rx = pf(params, "reactivity", 0f)
        val audio = params["_audioAnalysis"] as? AudioAnalysis
        val audioBass = (audio?.getBass(time) ?: 0f) * rx
        val audioMid = (audio?.getMid(time) ?: 0f) * rx
        val audioHigh = (audio?.getHigh(time) ?: 0f) * rx
        val audioEnergy = (audioBass + audioMid + audioHigh) / 3f

        gravity += audioBass * 0.3f
        windAmp += audioMid * 0.5f
        nodeSize *= (1f + audioBass * 0.2f)

        val scale = wf / 600f
        val gScaled = (gravity * scale).toDouble()
        val constraintIters = max(2, (stiffness * 8f).roundToInt())

        // Palette colors → [r,g,b] per stop
        val colorInts = palette.colorInts()
        val nColors = colorInts.size
        val palR = IntArray(nColors) { Color.red(colorInts[it]) }
        val palG = IntArray(nColors) { Color.green(colorInts[it]) }
        val palB = IntArray(nColors) { Color.blue(colorInts[it]) }

        // Cache key
        val key = "$seed|$cols|$rows|$pinMode|$restLen|$w|$h"

        var cl: ClothState
        synchronized(Companion) {
            val existing = _cloth
            if (existing == null || existing.key != key) {
                cl = initCloth(cols, rows, wf, hf, restLen, pinMode)
                cl.key = key
                _cloth = cl
            } else {
                cl = existing
            }
        }

        // Background
        canvas.drawColor(Color.rgb(10, 10, 10))

        // Static warmup
        if (time <= 0f && cl.step == 0) {
            for (f in 0 until 120) {
                val wt = f * 0.02
                val wX = sin(wt * windFreq) * windAmp * scale
                stepCloth(cl, wf, hf, gScaled, wX, stiffness.toDouble(), dampingVal.toDouble(), constraintIters)
            }
        }

        // Animation
        if (time > 0f) {
            for (s in 0 until spf) {
                val wX = sin(time.toDouble() * windFreq + cl.step * 0.05) * windAmp * scale
                // Audio energy → shake (uses java.util.Random.nextDouble via Math.random — NOT seeded, mirrors TS)
                val shakeX = audioEnergy * (Math.random() - 0.5) * 4.0 * scale
                val shakeY = audioEnergy * (Math.random() - 0.5) * 4.0 * scale
                stepCloth(
                    cl, wf, hf,
                    gScaled + shakeY,
                    wX + shakeX,
                    stiffness.toDouble(),
                    dampingVal.toDouble(),
                    constraintIters
                )
            }
        }

        // ── Render ──────────────────────────────────────────────────────────
        val x = cl.x
        val y = cl.y
        val pinned = cl.pinned
        val springs = cl.springs
        val springCount = cl.springCount
        val springA = cl.springA
        val springB = cl.springB
        val springRest = cl.springRest

        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        when (style) {
            "filled" -> {
                // Fill quads as triangles (the TS code draws a single quad path)
                paint.style = Paint.Style.FILL
                val firstRest = if (springCount > 0) springRest[0] else 1.0
                val firstRestSafe = if (firstRest != 0.0) firstRest else 1.0
                val path = Path()
                for (r in 0 until rows - 1) {
                    for (c in 0 until cols - 1) {
                        val tl = r * cols + c
                        val tr = tl + 1
                        val bl = tl + cols
                        val br = bl + 1

                        val color: Int = when (colorMode) {
                            "depth" -> {
                                val avgY = (y[tl] + y[tr] + y[bl] + y[br]) / 4.0
                                lerpColor(palR, palG, palB, (avgY / h).toFloat())
                            }
                            "palette" -> {
                                val idx = ((r + c) % nColors + nColors) % nColors
                                Color.rgb(palR[idx], palG[idx], palB[idx])
                            }
                            "uniform" -> Color.rgb(palR[0], palG[0], palB[0])
                            else -> {
                                // stress — average strain of edges (matches TS: uses springs[0].rest)
                                val strain1 = abs(dist(x[tl], y[tl], x[tr], y[tr]) - firstRest) / firstRestSafe
                                val strain2 = abs(dist(x[tl], y[tl], x[bl], y[bl]) - firstRest) / firstRestSafe
                                val avgStrain = min(1.0, (strain1 + strain2) * 3.0)
                                lerpColor(palR, palG, palB, avgStrain.toFloat())
                            }
                        }

                        paint.color = color
                        path.reset()
                        path.moveTo(x[tl].toFloat(), y[tl].toFloat())
                        path.lineTo(x[tr].toFloat(), y[tr].toFloat())
                        path.lineTo(x[br].toFloat(), y[br].toFloat())
                        path.lineTo(x[bl].toFloat(), y[bl].toFloat())
                        path.close()
                        canvas.drawPath(path, paint)
                    }
                }
            }

            "stress" -> {
                paint.style = Paint.Style.STROKE
                paint.strokeCap = Paint.Cap.ROUND
                for (s in 0 until springCount) {
                    val a = springA[s]
                    val b = springB[s]
                    val rest = springRest[s]
                    val d = dist(x[a], y[a], x[b], y[b])
                    val restSafe = if (rest * 0.5 != 0.0) rest * 0.5 else 1.0
                    val strain = min(1.0, abs(d - rest) / restSafe).toFloat()
                    paint.strokeWidth = max(1f, 2.5f * scale * (0.5f + strain))
                    paint.color = lerpColor(palR, palG, palB, strain)
                    canvas.drawLine(
                        x[a].toFloat(), y[a].toFloat(),
                        x[b].toFloat(), y[b].toFloat(),
                        paint
                    )
                }
            }

            else -> {
                // mesh or wireframe — draw structural + shear springs (skip bend)
                val structCount = (cols - 1) * rows + cols * (rows - 1) +
                    (cols - 1) * (rows - 1) * 2
                val drawCount = min(springCount, structCount)

                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 1.5f * scale
                paint.strokeCap = Paint.Cap.ROUND

                for (s in 0 until drawCount) {
                    val a = springA[s]
                    val b = springB[s]
                    val rest = springRest[s]
                    val color: Int = when (colorMode) {
                        "depth" -> {
                            val avgY = (y[a] + y[b]) / 2.0
                            lerpColor(palR, palG, palB, (avgY / h).toFloat())
                        }
                        "palette" -> {
                            val idx = ((s % nColors) + nColors) % nColors
                            Color.rgb(palR[idx], palG[idx], palB[idx])
                        }
                        "uniform" -> Color.rgb(palR[0], palG[0], palB[0])
                        else -> {
                            // stress
                            val d = dist(x[a], y[a], x[b], y[b])
                            val restSafe = if (rest * 0.5 != 0.0) rest * 0.5 else 1.0
                            val strain = min(1.0, abs(d - rest) / restSafe).toFloat()
                            lerpColor(palR, palG, palB, strain)
                        }
                    }
                    paint.color = color
                    canvas.drawLine(
                        x[a].toFloat(), y[a].toFloat(),
                        x[b].toFloat(), y[b].toFloat(),
                        paint
                    )
                }

                // Draw nodes (mesh only)
                if (style == "mesh" && nodeSize > 0f) {
                    val nr = nodeSize * 0.5f * scale
                    paint.style = Paint.Style.FILL
                    for (i in 0 until cl.nodeCount) {
                        if (pinned[i].toInt() != 0) {
                            paint.color = Color.WHITE
                            canvas.drawCircle(x[i].toFloat(), y[i].toFloat(), nr * 1.5f, paint)
                        } else {
                            val color: Int = when (colorMode) {
                                "depth" -> lerpColor(palR, palG, palB, (y[i] / h).toFloat())
                                "palette" -> {
                                    val rr = i / cols
                                    val cc = i % cols
                                    val idx = (((rr + cc) % nColors) + nColors) % nColors
                                    Color.rgb(palR[idx], palG[idx], palB[idx])
                                }
                                else -> Color.rgb(palR[0], palG[0], palB[0])
                            }
                            paint.color = color
                            canvas.drawCircle(x[i].toFloat(), y[i].toFloat(), nr, paint)
                        }
                    }
                }

                // Suppress unused-variable warning for springs reference
                @Suppress("UNUSED_EXPRESSION") springs
            }
        }
    }

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float {
        val c = pf(params, "gridWidth", 20f)
        val r = pf(params, "gridHeight", 20f)
        return ((c * r * 10f) / 4000f).coerceIn(0.05f, 1f)
    }

    // ── Cloth state & helpers ───────────────────────────────────────────────
    private class ClothState(
        var key: String,
        val cols: Int,
        val rows: Int,
        val nodeCount: Int,
        val x: DoubleArray,
        val y: DoubleArray,
        val prevX: DoubleArray,
        val prevY: DoubleArray,
        val pinned: ByteArray,
        val restX: DoubleArray,
        val restY: DoubleArray,
        val springCount: Int,
        val springA: IntArray,
        val springB: IntArray,
        val springRest: DoubleArray,
        var step: Int,
        // legacy reference (unused; parallel arrays drive everything)
        val springs: Any? = null
    )

    private fun dist(ax: Double, ay: Double, bx: Double, by: Double): Double {
        val dx = bx - ax
        val dy = by - ay
        return sqrt(dx * dx + dy * dy)
    }

    private fun initCloth(
        cols: Int, rows: Int, w: Float, h: Float,
        restLenMul: Float, pinMode: String
    ): ClothState {
        val nodeCount = cols * rows
        val spacing = (min(w * 0.8 / (cols - 1), h * 0.7 / (rows - 1)) * restLenMul).toDouble()
        val startX = (w - spacing * (cols - 1)) / 2.0
        val startY = (h * 0.08).toDouble()

        val x = DoubleArray(nodeCount)
        val y = DoubleArray(nodeCount)
        val prevX = DoubleArray(nodeCount)
        val prevY = DoubleArray(nodeCount)
        val restX = DoubleArray(nodeCount)
        val restY = DoubleArray(nodeCount)
        val pinned = ByteArray(nodeCount)

        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val idx = r * cols + c
                val px = startX + c * spacing
                val py = startY + r * spacing
                x[idx] = px; y[idx] = py
                prevX[idx] = px; prevY[idx] = py
                restX[idx] = px; restY[idx] = py
            }
        }

        // Pin constraints
        when (pinMode) {
            "top-edge" -> for (c in 0 until cols) pinned[c] = 1
            "corners" -> {
                pinned[0] = 1
                pinned[cols - 1] = 1
                pinned[(rows - 1) * cols] = 1
                pinned[(rows - 1) * cols + cols - 1] = 1
            }
            "top-corners" -> {
                pinned[0] = 1
                pinned[cols - 1] = 1
            }
            "center" -> {
                val cr = rows / 2
                val cc = cols / 2
                pinned[cr * cols + cc] = 1
                if (cc > 0) pinned[cr * cols + cc - 1] = 1
                if (cc < cols - 1) pinned[cr * cols + cc + 1] = 1
            }
        }

        // Build springs in the exact same order as the TS source
        // Capacity: structural-right + structural-down + shear-dr + shear-dl + bend-right + bend-down
        val cap = (cols - 1) * rows +
                  cols * (rows - 1) +
                  (cols - 1) * (rows - 1) +
                  (cols - 1) * (rows - 1) +
                  (cols - 2).coerceAtLeast(0) * rows +
                  cols * (rows - 2).coerceAtLeast(0)
        val springA = IntArray(cap)
        val springB = IntArray(cap)
        val springRest = DoubleArray(cap)
        var sIdx = 0

        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val idx = r * cols + c
                // Structural — right
                if (c < cols - 1) {
                    val b = idx + 1
                    springA[sIdx] = idx; springB[sIdx] = b
                    springRest[sIdx] = dist(x[idx], y[idx], x[b], y[b])
                    sIdx++
                }
                // Structural — down
                if (r < rows - 1) {
                    val b = idx + cols
                    springA[sIdx] = idx; springB[sIdx] = b
                    springRest[sIdx] = dist(x[idx], y[idx], x[b], y[b])
                    sIdx++
                }
                // Shear — diagonal down-right
                if (c < cols - 1 && r < rows - 1) {
                    val b = idx + cols + 1
                    springA[sIdx] = idx; springB[sIdx] = b
                    springRest[sIdx] = dist(x[idx], y[idx], x[b], y[b])
                    sIdx++
                }
                // Shear — diagonal down-left
                if (c > 0 && r < rows - 1) {
                    val b = idx + cols - 1
                    springA[sIdx] = idx; springB[sIdx] = b
                    springRest[sIdx] = dist(x[idx], y[idx], x[b], y[b])
                    sIdx++
                }
                // Bend — skip-one right
                if (c < cols - 2) {
                    val b = idx + 2
                    springA[sIdx] = idx; springB[sIdx] = b
                    springRest[sIdx] = dist(x[idx], y[idx], x[b], y[b])
                    sIdx++
                }
                // Bend — skip-one down
                if (r < rows - 2) {
                    val b = idx + cols * 2
                    springA[sIdx] = idx; springB[sIdx] = b
                    springRest[sIdx] = dist(x[idx], y[idx], x[b], y[b])
                    sIdx++
                }
            }
        }

        return ClothState(
            key = "", cols = cols, rows = rows, nodeCount = nodeCount,
            x = x, y = y, prevX = prevX, prevY = prevY,
            pinned = pinned, restX = restX, restY = restY,
            springCount = sIdx,
            springA = springA, springB = springB, springRest = springRest,
            step = 0
        )
    }

    private fun stepCloth(
        cl: ClothState, w: Float, h: Float,
        gravity: Double, windX: Double,
        stiffness: Double, damping: Double,
        constraintIters: Int
    ) {
        val nodeCount = cl.nodeCount
        val x = cl.x
        val y = cl.y
        val prevX = cl.prevX
        val prevY = cl.prevY
        val pinned = cl.pinned
        val springCount = cl.springCount
        val springA = cl.springA
        val springB = cl.springB
        val springRest = cl.springRest

        // Verlet integration with damping
        for (i in 0 until nodeCount) {
            if (pinned[i].toInt() != 0) continue
            val tmpX = x[i]
            val tmpY = y[i]
            val dx = (x[i] - prevX[i]) * damping
            val dy = (y[i] - prevY[i]) * damping
            x[i] += dx + windX
            y[i] += dy + gravity
            prevX[i] = tmpX
            prevY[i] = tmpY
        }

        // Constraint satisfaction (relaxation)
        for (iter in 0 until constraintIters) {
            for (s in 0 until springCount) {
                val a = springA[s]
                val b = springB[s]
                val rest = springRest[s]
                val dx = x[b] - x[a]
                val dy = y[b] - y[a]
                var d = sqrt(dx * dx + dy * dy)
                if (d == 0.0) d = 0.0001
                val diff = (d - rest) / d * stiffness * 0.5
                val ox = dx * diff
                val oy = dy * diff

                if (pinned[a].toInt() == 0) { x[a] += ox; y[a] += oy }
                if (pinned[b].toInt() == 0) { x[b] -= ox; y[b] -= oy }
            }
        }

        // Keep within canvas bounds (floor)
        for (i in 0 until nodeCount) {
            if (pinned[i].toInt() != 0) {
                x[i] = cl.restX[i]
                y[i] = cl.restY[i]
                continue
            }
            if (y[i] > h - 5) y[i] = (h - 5).toDouble()
            if (x[i] < 5) x[i] = 5.0
            if (x[i] > w - 5) x[i] = (w - 5).toDouble()
        }

        cl.step++
    }

    private fun lerpColor(palR: IntArray, palG: IntArray, palB: IntArray, t: Float): Int {
        val n = palR.size
        if (n == 0) return Color.BLACK
        if (n == 1) return Color.rgb(palR[0], palG[0], palB[0])
        val s = t.coerceIn(0f, 1f) * (n - 1)
        val i0 = s.toInt().coerceAtMost(n - 1)
        val i1 = min(n - 1, i0 + 1)
        val f = s - i0
        val r = (palR[i0] + (palR[i1] - palR[i0]) * f).toInt().coerceIn(0, 255)
        val g = (palG[i0] + (palG[i1] - palG[i0]) * f).toInt().coerceIn(0, 255)
        val b = (palB[i0] + (palB[i1] - palB[i0]) * f).toInt().coerceIn(0, 255)
        return Color.rgb(r, g, b)
    }

    companion object {
        @Volatile
        private var _cloth: ClothState? = null
    }
}
