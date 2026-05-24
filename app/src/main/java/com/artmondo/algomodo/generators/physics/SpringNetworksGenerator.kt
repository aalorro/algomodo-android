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

class SpringNetworksGenerator : Generator {

    override val id = "physics-spring-networks"
    override val family = "physics"
    override val styleName = "Spring Networks"
    override val definition =
        "Trusses, meshes, bridges, and webs of particles connected by distance constraints — elastic structures driven by Verlet integration"
    override val algorithmNotes =
        "Verlet integration: x_new = 2·x − x_prev + a·dt², with per-step damping multiplier. " +
        "Springs enforced via Jakobsen-style distance constraints iterated N times per step for stability. " +
        "Four structures: truss (triangulated beam with W-pattern diagonal bracing), grid (rectangular mesh pinned at top), bridge (suspension cable + deck), web (radial spider web). " +
        "Pinned nodes act as fixed anchors. Forces: gravity (downward) and wind (sinusoidal horizontal). " +
        "Stress rendering colors springs by stretch ratio relative to rest length."

    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema = listOf(
        Parameter.SelectParam(
            name = "Structure", key = "initMode",
            group = ParamGroup.COMPOSITION,
            help = "truss: triangulated beam | grid: rectangular mesh | bridge: suspension | web: spider web",
            options = listOf("truss", "grid", "bridge", "web"),
            default = "grid"
        ),
        Parameter.NumberParam(
            name = "Node Count", key = "nodeCount",
            group = ParamGroup.COMPOSITION,
            help = "Nodes per dimension — total node count depends on structure type",
            min = 8f, max = 40f, step = 1f, default = 18f
        ),
        Parameter.NumberParam(
            name = "Gravity", key = "gravity",
            group = ParamGroup.FLOW_MOTION, help = null,
            min = 0f, max = 2f, step = 0.05f, default = 0.5f
        ),
        Parameter.NumberParam(
            name = "Wind", key = "wind",
            group = ParamGroup.FLOW_MOTION,
            help = "Horizontal wind turbulence",
            min = 0f, max = 1f, step = 0.05f, default = 0.15f
        ),
        Parameter.NumberParam(
            name = "Stiffness", key = "stiffness",
            group = ParamGroup.FLOW_MOTION,
            help = "Constraint solver iterations per step — higher = stiffer springs",
            min = 1f, max = 10f, step = 1f, default = 5f
        ),
        Parameter.NumberParam(
            name = "Damping", key = "damping",
            group = ParamGroup.FLOW_MOTION, help = null,
            min = 0.9f, max = 1.0f, step = 0.005f, default = 0.98f
        ),
        Parameter.SelectParam(
            name = "Force Mode", key = "forceMode",
            group = ParamGroup.FLOW_MOTION,
            help = "calm: steady forces | wave: travelling ripple | shake: periodic impulses | breathe: pulsing anchors | chaotic: multi-frequency mayhem",
            options = listOf("calm", "wave", "shake", "breathe", "chaotic"),
            default = "wave"
        ),
        Parameter.NumberParam(
            name = "Turbulence", key = "turbulence",
            group = ParamGroup.FLOW_MOTION,
            help = "Strength of dynamic perturbations and anchor oscillation",
            min = 0f, max = 3f, step = 0.1f, default = 1.2f
        ),
        Parameter.SelectParam(
            name = "Render Style", key = "style",
            group = ParamGroup.COMPOSITION,
            help = "mesh: springs + nodes | nodes: nodes only | stress: color by deformation | glow: additive bloom | trails: motion blur",
            options = listOf("mesh", "nodes", "stress", "glow", "trails"),
            default = "mesh"
        ),
        Parameter.SelectParam(
            name = "Color Mode", key = "colorMode",
            group = ParamGroup.COLOR,
            help = "depth: y-position | speed: velocity | index: cycle palette",
            options = listOf("depth", "speed", "index"),
            default = "depth"
        ),
        Parameter.NumberParam(
            name = "Node Size", key = "nodeSize",
            group = ParamGroup.GEOMETRY, help = null,
            min = 1f, max = 8f, step = 0.5f, default = 3f
        ),
        Parameter.NumberParam(
            name = "Steps / Frame", key = "stepsPerFrame",
            group = ParamGroup.FLOW_MOTION, help = null,
            min = 1f, max = 6f, step = 1f, default = 2f
        ),
        Parameter.NumberParam(
            name = "Audio Reactivity", key = "reactivity",
            group = ParamGroup.FLOW_MOTION, help = null,
            min = 0f, max = 2f, step = 0.1f, default = 0f
        )
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "initMode" to "grid",
        "nodeCount" to 18f,
        "gravity" to 0.5f,
        "wind" to 0.15f,
        "stiffness" to 5f,
        "damping" to 0.98f,
        "forceMode" to "wave",
        "turbulence" to 1.2f,
        "style" to "mesh",
        "colorMode" to "depth",
        "nodeSize" to 3f,
        "stepsPerFrame" to 2f,
        "reactivity" to 0f
    )

    // ── Param helpers ──────────────────────────────────────────────────────
    private fun pf(p: Map<String, Any>, k: String, default: Float): Float =
        (p[k] as? Number)?.toFloat() ?: default

    private fun pi(p: Map<String, Any>, k: String, default: Int): Int =
        (p[k] as? Number)?.toInt() ?: default

    @Suppress("unused")
    private fun pb(p: Map<String, Any>, k: String, default: Boolean): Boolean =
        (p[k] as? Boolean) ?: default

    private fun ps(p: Map<String, Any>, k: String, default: String): String =
        (p[k] as? String) ?: default

    // ── Cached state ──────────────────────────────────────────────────────
    private class SpringState(
        var key: String,
        val n: Int,
        val x: DoubleArray,
        val y: DoubleArray,
        val px: DoubleArray,
        val py: DoubleArray,
        val ix: DoubleArray,
        val iy: DoubleArray,
        val pinned: ByteArray,
        val cA: IntArray,
        val cB: IntArray,
        val cRest: DoubleArray,
        var nc: Int,
        var step: Int,
        val rng: SeededRNG
    )

    companion object {
        @Volatile
        private var _state: SpringState? = null
    }

    private fun makeState(n: Int, maxC: Int, seed: Int): SpringState {
        return SpringState(
            key = "", n = n,
            x = DoubleArray(n), y = DoubleArray(n),
            px = DoubleArray(n), py = DoubleArray(n),
            ix = DoubleArray(n), iy = DoubleArray(n),
            pinned = ByteArray(n),
            cA = IntArray(maxC), cB = IntArray(maxC),
            cRest = DoubleArray(maxC), nc = 0,
            step = 0, rng = SeededRNG(seed)
        )
    }

    private fun addC(st: SpringState, a: Int, b: Int, rest: Double) {
        val i = st.nc
        st.cA[i] = a
        st.cB[i] = b
        st.cRest[i] = rest
        st.nc = i + 1
    }

    private fun saveInitialPositions(st: SpringState) {
        for (i in 0 until st.n) {
            st.ix[i] = st.x[i]
            st.iy[i] = st.y[i]
        }
    }

    // ── Init: Truss ──────────────────────────────────────────────────────
    private fun initTruss(nc: Int, w: Double, h: Double, seed: Int): SpringState {
        val bays = max(3, nc)
        val n = (bays + 1) * 2
        val maxC = n * 4
        val st = makeState(n, maxC, seed)

        val beamLen = w * 0.8
        val beamH = h * 0.15
        val sx = w * 0.1
        val cy = h * 0.25
        val bayLen = beamLen / bays

        // Top chord nodes: indices 0..bays
        for (i in 0..bays) {
            val xv = sx + i * bayLen
            val yv = cy - beamH / 2
            st.x[i] = xv; st.px[i] = xv
            st.y[i] = yv; st.py[i] = yv
        }
        // Bottom chord nodes: indices bays+1..2*bays+1
        for (i in 0..bays) {
            val idx = bays + 1 + i
            val xv = sx + i * bayLen
            val yv = cy + beamH / 2
            st.x[idx] = xv; st.px[idx] = xv
            st.y[idx] = yv; st.py[idx] = yv
        }

        // Pin left end (top + bottom) and right end (top + bottom)
        st.pinned[0] = 1
        st.pinned[bays + 1] = 1
        st.pinned[bays] = 1
        st.pinned[2 * bays + 1] = 1

        // Top chord springs
        for (i in 0 until bays) addC(st, i, i + 1, bayLen)
        // Bottom chord springs
        for (i in 0 until bays) addC(st, bays + 1 + i, bays + 2 + i, bayLen)
        // Vertical posts
        for (i in 0..bays) addC(st, i, bays + 1 + i, beamH)
        // Diagonal bracing (alternating W-pattern)
        val diag = sqrt(bayLen * bayLen + beamH * beamH)
        for (i in 0 until bays) {
            if (i % 2 == 0) {
                addC(st, i, bays + 2 + i, diag)
            } else {
                addC(st, i + 1, bays + 1 + i, diag)
            }
        }

        saveInitialPositions(st)
        return st
    }

    // ── Init: Grid ────────────────────────────────────────────────────────
    private fun initGrid(nc: Int, w: Double, h: Double, seed: Int): SpringState {
        val cols = nc
        val rows = max(3, (nc * 0.6).roundToInt())
        val n = cols * rows
        val maxC = 3 * cols * rows + cols
        val st = makeState(n, maxC, seed)

        val spX = (w * 0.7) / max(1, cols - 1)
        val spY = (h * 0.6) / max(1, rows - 1)
        val sx = w * 0.15
        val sy = h * 0.08

        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val idx = r * cols + c
                val xv = sx + c * spX
                val yv = sy + r * spY
                st.x[idx] = xv; st.px[idx] = xv
                st.y[idx] = yv; st.py[idx] = yv
            }
        }
        // Pin top row
        for (c in 0 until cols) st.pinned[c] = 1

        // Horizontal
        for (r in 0 until rows) {
            for (c in 0 until cols - 1) {
                addC(st, r * cols + c, r * cols + c + 1, spX)
            }
        }
        // Vertical
        for (r in 0 until rows - 1) {
            for (c in 0 until cols) {
                addC(st, r * cols + c, (r + 1) * cols + c, spY)
            }
        }
        // Diagonal (structural)
        val diag = sqrt(spX * spX + spY * spY)
        for (r in 0 until rows - 1) {
            for (c in 0 until cols - 1) {
                addC(st, r * cols + c, (r + 1) * cols + c + 1, diag)
            }
        }
        saveInitialPositions(st)
        return st
    }

    // ── Init: Bridge ──────────────────────────────────────────────────────
    private fun initBridge(nc: Int, w: Double, h: Double, seed: Int): SpringState {
        val half = max(4, (nc / 2.0).roundToInt())
        val n = half * 2 + 2 // 2 towers + cable + deck
        val maxC = n * 4
        val st = makeState(n, maxC, seed)

        val spacing = (w * 0.7) / (half + 1)
        val sx = w * 0.15
        val towerY = h * 0.15
        val deckY = h * 0.55

        // Towers
        st.x[0] = sx; st.px[0] = sx; st.y[0] = towerY; st.py[0] = towerY; st.pinned[0] = 1
        val t1x = sx + (half + 1) * spacing
        st.x[1] = t1x; st.px[1] = t1x; st.y[1] = towerY; st.py[1] = towerY; st.pinned[1] = 1

        // Cable nodes (index 2 .. half+1)
        for (i in 0 until half) {
            val idx = 2 + i
            val xv = sx + (i + 1) * spacing
            val yv = towerY + h * 0.08
            st.x[idx] = xv; st.px[idx] = xv
            st.y[idx] = yv; st.py[idx] = yv
        }
        // Deck nodes (index half+2 .. 2*half+1)
        for (i in 0 until half) {
            val idx = 2 + half + i
            val xv = sx + (i + 1) * spacing
            val yv = deckY
            st.x[idx] = xv; st.px[idx] = xv
            st.y[idx] = yv; st.py[idx] = yv
        }

        // Cable chain: tower0 → cable → tower1
        addC(st, 0, 2, spacing)
        for (i in 0 until half - 1) addC(st, 2 + i, 2 + i + 1, spacing)
        addC(st, 2 + half - 1, 1, spacing)

        // Deck chain
        for (i in 0 until half - 1) addC(st, 2 + half + i, 2 + half + i + 1, spacing)

        // Vertical links cable → deck
        val vertDist = deckY - towerY - h * 0.08
        val stepInc = max(1, half / 6)
        var i = 0
        while (i < half) {
            addC(st, 2 + i, 2 + half + i, vertDist)
            i += stepInc
        }
        // Always connect first and last
        addC(st, 2, 2 + half, vertDist)
        addC(st, 2 + half - 1, 2 + 2 * half - 1, vertDist)
        saveInitialPositions(st)
        return st
    }

    // ── Init: Web ─────────────────────────────────────────────────────────
    private fun initWeb(nc: Int, w: Double, h: Double, seed: Int): SpringState {
        val spokes = max(6, nc)
        val rings = max(3, nc / 3)
        val n = 1 + spokes * rings
        val maxC = 2 * spokes * rings + spokes + 10
        val st = makeState(n, maxC, seed)

        val cx = w / 2
        val cy = h / 2
        val maxR = min(w, h) * 0.4

        // Center
        st.x[0] = cx; st.px[0] = cx; st.y[0] = cy; st.py[0] = cy
        st.pinned[0] = 1

        // Ring nodes
        for (r in 0 until rings) {
            val rad = maxR * (r + 1) / rings
            for (s in 0 until spokes) {
                val a = (s.toDouble() / spokes) * PI * 2
                val idx = 1 + r * spokes + s
                val xv = cx + cos(a) * rad
                val yv = cy + sin(a) * rad
                st.x[idx] = xv; st.px[idx] = xv
                st.y[idx] = yv; st.py[idx] = yv
            }
        }
        // Pin outer ring
        for (s in 0 until spokes) st.pinned[1 + (rings - 1) * spokes + s] = 1

        // Radial: center → ring 0
        val r0 = maxR / rings
        for (s in 0 until spokes) addC(st, 0, 1 + s, r0)
        // Radial: ring k → ring k+1
        for (r in 0 until rings - 1) {
            for (s in 0 until spokes) {
                addC(st, 1 + r * spokes + s, 1 + (r + 1) * spokes + s, r0)
            }
        }
        // Circumferential
        for (r in 0 until rings) {
            val rad = maxR * (r + 1) / rings
            val arc = 2 * PI * rad / spokes
            for (s in 0 until spokes) {
                addC(st, 1 + r * spokes + s, 1 + r * spokes + (s + 1) % spokes, arc)
            }
        }
        saveInitialPositions(st)
        return st
    }

    // ── Simulation step ──────────────────────────────────────────────────
    private fun stepSim(
        st: SpringState,
        gravity: Double,
        wind: Double,
        damping: Double,
        iterations: Int,
        w: Double,
        h: Double,
        forceMode: String,
        turbulence: Double
    ) {
        val n = st.n
        val x = st.x; val y = st.y
        val px = st.px; val py = st.py
        val ix = st.ix; val iy = st.iy
        val pinned = st.pinned
        val cA = st.cA; val cB = st.cB; val cRest = st.cRest
        val nc = st.nc
        val step = st.step
        val rng = st.rng

        val gForce = gravity * w * 0.0008
        val wAmp = wind * w * 0.0006
        val t = step * 0.02 // time factor

        // ── Animate pinned nodes ─────────────────────────────────────────
        val anchorAmp = turbulence * w * 0.015
        for (i in 0 until n) {
            if (pinned[i].toInt() == 0) continue
            when (forceMode) {
                "wave" -> {
                    val phase = ix[i] * 0.006 + t * 2.5
                    x[i] = ix[i] + sin(phase) * anchorAmp
                    y[i] = iy[i] + cos(phase * 0.7) * anchorAmp * 0.5
                }
                "breathe" -> {
                    val cx = w * 0.5
                    val cy = h * 0.5
                    val dx = ix[i] - cx
                    val dy = iy[i] - cy
                    val pulse = sin(t * 1.8) * turbulence * 0.08
                    x[i] = ix[i] + dx * pulse
                    y[i] = iy[i] + dy * pulse
                }
                "chaotic" -> {
                    val seed1 = i * 1.7 + 0.3
                    val seed2 = i * 2.3 + 1.1
                    x[i] = ix[i] + (sin(t * 1.5 + seed1) + sin(t * 3.7 + seed1 * 2) * 0.4) * anchorAmp
                    y[i] = iy[i] + (sin(t * 2.1 + seed2) + cos(t * 4.3 + seed2 * 2) * 0.3) * anchorAmp * 0.6
                }
                "shake" -> {
                    val shakePhase = sin(t * 5 + i * 0.9)
                    val envelope = max(0.0, shakePhase) * shakePhase
                    x[i] = ix[i] + envelope * anchorAmp * sin(i * 3.1 + t * 8)
                    y[i] = iy[i] + envelope * anchorAmp * 0.4 * cos(i * 2.7 + t * 6)
                }
                // "calm" — leave at initial positions (already set)
            }
            px[i] = x[i]
            py[i] = y[i]
        }

        // ── Verlet integration with richer wind ──────────────────────────
        for (i in 0 until n) {
            if (pinned[i].toInt() != 0) continue
            val vx = (x[i] - px[i]) * damping
            val vy = (y[i] - py[i]) * damping
            px[i] = x[i]
            py[i] = y[i]

            // Multi-frequency wind: 3 layers for natural turbulence
            val windBase = sin(t * 2 + y[i] * 0.008 + i * 0.3)
            val windMid = sin(t * 5.3 + x[i] * 0.005 + y[i] * 0.003) * 0.4
            val windHigh = sin(t * 11.7 + i * 1.1) * 0.15
            val windF = wAmp * (windBase + windMid + windHigh)

            // Vertical turbulence (updrafts/downdrafts)
            val vertTurb = turbulence * w * 0.0003 * sin(t * 3.1 + x[i] * 0.007)

            x[i] += vx + windF
            y[i] += vy + gForce + vertTurb
        }

        // ── Periodic impulse kicks on random free nodes ──────────────────
        if (forceMode == "shake" || forceMode == "chaotic") {
            val impulseInterval = if (forceMode == "chaotic") 8 else 15
            if (step % impulseInterval == 0 && n > 0) {
                val kickCount = max(1, (n * 0.15).toInt())
                val kickForce = turbulence * w * 0.006
                for (k in 0 until kickCount) {
                    val target = (rng.random() * n).toInt().coerceIn(0, n - 1)
                    if (pinned[target].toInt() != 0) continue
                    val angle = rng.random() * PI * 2
                    val force = kickForce * (0.5 + rng.random() * 0.5)
                    x[target] += cos(angle) * force
                    y[target] += sin(angle) * force
                }
            }
        }

        // ── Wave force on free nodes (propagating ripple) ────────────────
        if (forceMode == "wave") {
            val waveAmp = turbulence * w * 0.0008
            for (i in 0 until n) {
                if (pinned[i].toInt() != 0) continue
                val phase = x[i] * 0.008 + y[i] * 0.004 - t * 3
                y[i] += sin(phase) * waveAmp
                x[i] += cos(phase * 1.3) * waveAmp * 0.5
            }
        }

        // ── Constraint solving ───────────────────────────────────────────
        for (iter in 0 until iterations) {
            for (c in 0 until nc) {
                val a = cA[c]
                val b = cB[c]
                val dx = x[b] - x[a]
                val dy = y[b] - y[a]
                val dist = sqrt(dx * dx + dy * dy)
                if (dist < 0.001) continue
                val diff = (dist - cRest[c]) / dist * 0.5
                val ox = dx * diff
                val oy = dy * diff
                if (pinned[a].toInt() == 0) {
                    x[a] += ox
                    y[a] += oy
                }
                if (pinned[b].toInt() == 0) {
                    x[b] -= ox
                    y[b] -= oy
                }
            }
        }

        // Soft bounds
        for (i in 0 until n) {
            if (pinned[i].toInt() != 0) continue
            if (y[i] > h * 0.98) { y[i] = h * 0.98; py[i] = y[i] }
            if (y[i] < h * 0.01) { y[i] = h * 0.01; py[i] = y[i] }
            if (x[i] < w * 0.01) { x[i] = w * 0.01; px[i] = x[i] }
            if (x[i] > w * 0.99) { x[i] = w * 0.99; px[i] = x[i] }
        }

        st.step++
    }

    // ── Color helpers ────────────────────────────────────────────────────
    private fun lerpRgb(palR: IntArray, palG: IntArray, palB: IntArray, t: Float): IntArray {
        val nColors = palR.size
        val s = t.coerceIn(0f, 1f) * (nColors - 1)
        val i0 = s.toInt().coerceAtMost(nColors - 1)
        val i1 = min(nColors - 1, i0 + 1)
        val f = s - i0
        val r = (palR[i0] + (palR[i1] - palR[i0]) * f).toInt().coerceIn(0, 255)
        val g = (palG[i0] + (palG[i1] - palG[i0]) * f).toInt().coerceIn(0, 255)
        val b = (palB[i0] + (palB[i1] - palB[i0]) * f).toInt().coerceIn(0, 255)
        return intArrayOf(r, g, b)
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
        val w = bitmap.width.toDouble()
        val h = bitmap.height.toDouble()
        val wF = w.toFloat()
        val hF = h.toFloat()
        val scale = w / 600.0
        val scaleF = scale.toFloat()
        val qMul = when (quality) {
            Quality.DRAFT -> 0.5
            Quality.ULTRA -> 1.5
            else -> 1.0
        }

        val initMode = ps(params, "initMode", "grid")
        val nodeCount = max(4, (pi(params, "nodeCount", 18) * qMul).roundToInt())
        var gravity = pf(params, "gravity", 0.5f).toDouble()
        var wind = pf(params, "wind", 0.15f).toDouble()
        val stiffness = max(1, pi(params, "stiffness", 5))
        val damping = pf(params, "damping", 0.98f).toDouble()
        val forceMode = ps(params, "forceMode", "wave")
        val turbulence = pf(params, "turbulence", 1.2f).toDouble()
        val style = ps(params, "style", "mesh")
        val colorMode = ps(params, "colorMode", "depth")
        val nodeSize = pf(params, "nodeSize", 3f)
        val spf = max(1, pi(params, "stepsPerFrame", 2))

        // Audio reactivity
        val rx = pf(params, "reactivity", 0f)
        val audio = params["_audioAnalysis"] as? AudioAnalysis
        val audioBass = (audio?.getBass(time) ?: 0f) * rx
        val audioMid = (audio?.getMid(time) ?: 0f) * rx
        @Suppress("UNUSED_VARIABLE")
        val audioHigh = (audio?.getHigh(time) ?: 0f) * rx
        gravity += audioBass * 0.5
        wind += audioMid * 0.3

        // ── State cache ──────────────────────────────────────────────────
        val key = "$seed|$initMode|$nodeCount|${bitmap.width}|${bitmap.height}"
        var st: SpringState
        synchronized(Companion) {
            val cur = _state
            if (cur == null || cur.key != key) {
                val newSt = when (initMode) {
                    "truss" -> initTruss(nodeCount, w, h, seed)
                    "bridge" -> initBridge(nodeCount, w, h, seed)
                    "web" -> initWeb(nodeCount, w, h, seed)
                    else -> initGrid(nodeCount, w, h, seed)
                }
                newSt.key = key
                _state = newSt
                st = newSt
            } else {
                st = cur
            }
        }

        // ── Warmup ───────────────────────────────────────────────────────
        if (time <= 0f && st.step == 0) {
            for (f in 0 until 60) {
                stepSim(st, gravity, wind, damping, stiffness, w, h, forceMode, turbulence)
            }
        }

        // ── Animation steps ──────────────────────────────────────────────
        if (time > 0f) {
            for (s in 0 until spf) {
                stepSim(st, gravity, wind, damping, stiffness, w, h, forceMode, turbulence)
            }
        }

        // ── Render ───────────────────────────────────────────────────────
        if (style == "trails") {
            // Semi-transparent overlay for motion blur (rgba(10,10,10,0.15))
            val fadePaint = Paint().apply {
                color = Color.argb((0.15f * 255f).roundToInt(), 10, 10, 10)
                this.style = Paint.Style.FILL
            }
            canvas.drawRect(0f, 0f, wF, hF, fadePaint)
        } else {
            canvas.drawColor(Color.rgb(10, 10, 10))
        }

        // Palette colors
        val colorInts = palette.colorInts()
        val nColors = colorInts.size
        val palR = IntArray(nColors) { Color.red(colorInts[it]) }
        val palG = IntArray(nColors) { Color.green(colorInts[it]) }
        val palB = IntArray(nColors) { Color.blue(colorInts[it]) }

        val n = st.n
        val x = st.x; val y = st.y
        val px = st.px; val py = st.py
        val pinned = st.pinned
        val cA = st.cA; val cB = st.cB; val cRest = st.cRest
        val nc = st.nc
        val ns = nodeSize * scaleF

        // "lighter" composite is approximated with PorterDuff.Mode.ADD when applicable.
        // We achieve glow/trails additive blending per-paint with xfermode.
        val useAdditive = (style == "glow" || style == "trails")

        // ── Draw springs ─────────────────────────────────────────────────
        if (style != "nodes") {
            if (style == "stress") {
                // Bucket by strain for batching
                val buckets = 12
                for (b in 0 until buckets) {
                    val bt = b.toFloat() / (buckets - 1)
                    val rgb = lerpRgb(palR, palG, palB, bt)
                    val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        this.style = Paint.Style.STROKE
                        strokeCap = Paint.Cap.ROUND
                        strokeJoin = Paint.Join.ROUND
                        color = Color.rgb(rgb[0], rgb[1], rgb[2])
                        strokeWidth = max(1f, (0.8f + bt * 2f) * scaleF)
                    }
                    val path = Path()
                    for (c in 0 until nc) {
                        val a = cA[c]
                        val bi = cB[c]
                        val dx = x[bi] - x[a]
                        val dy = y[bi] - y[a]
                        val dist = sqrt(dx * dx + dy * dy)
                        val strain = abs(dist - cRest[c]) / max(0.1, cRest[c])
                        val sb = min(
                            buckets - 1,
                            (min(1.0, strain * 3) * (buckets - 1) + 0.5).toInt()
                        )
                        if (sb == b) {
                            path.moveTo(x[a].toFloat(), y[a].toFloat())
                            path.lineTo(x[bi].toFloat(), y[bi].toFloat())
                        }
                    }
                    canvas.drawPath(path, strokePaint)
                }
            } else if (style == "trails") {
                // Springs colored by velocity of endpoints, brighter when fast
                for (ci in 0 until nColors) {
                    val cr = palR[ci]
                    val cg = palG[ci]
                    val cb = palB[ci]
                    val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        this.style = Paint.Style.STROKE
                        strokeCap = Paint.Cap.ROUND
                        strokeJoin = Paint.Join.ROUND
                        color = Color.argb((0.6f * 255f).roundToInt(), cr, cg, cb)
                        strokeWidth = max(1f, 2f * scaleF)
                        if (useAdditive) {
                            xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.ADD)
                        }
                    }
                    val path = Path()
                    for (c in 0 until nc) {
                        if (c % nColors != ci) continue
                        path.moveTo(x[cA[c]].toFloat(), y[cA[c]].toFloat())
                        path.lineTo(x[cB[c]].toFloat(), y[cB[c]].toFloat())
                    }
                    canvas.drawPath(path, strokePaint)
                }
            } else {
                // Batch all springs by palette color
                for (ci in 0 until nColors) {
                    val cr = palR[ci]
                    val cg = palG[ci]
                    val cb = palB[ci]
                    val alpha = if (style == "glow") 0.3f else 0.55f
                    val lw = max(1f, (if (style == "glow") 3f else 1.5f) * scaleF)
                    val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        this.style = Paint.Style.STROKE
                        strokeCap = Paint.Cap.ROUND
                        strokeJoin = Paint.Join.ROUND
                        color = Color.argb((alpha * 255f).roundToInt(), cr, cg, cb)
                        strokeWidth = lw
                        if (useAdditive) {
                            xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.ADD)
                        }
                    }
                    val path = Path()
                    for (c in 0 until nc) {
                        if (c % nColors != ci) continue
                        path.moveTo(x[cA[c]].toFloat(), y[cA[c]].toFloat())
                        path.lineTo(x[cB[c]].toFloat(), y[cB[c]].toFloat())
                    }
                    canvas.drawPath(path, strokePaint)
                }
            }
        }

        // ── Draw nodes ───────────────────────────────────────────────────
        val nodePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.style = Paint.Style.FILL
        }
        val bloomPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.style = Paint.Style.FILL
            if (useAdditive) {
                xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.ADD)
            }
        }

        for (i in 0 until n) {
            // Compute velocity magnitude for dynamic sizing/coloring
            val dvx = x[i] - px[i]
            val dvy = y[i] - py[i]
            val speed = sqrt(dvx * dvx + dvy * dvy)

            val ct: Float = when (colorMode) {
                "depth" -> (y[i] / h).toFloat()
                "speed" -> min(1.0, speed / (8.0 * scale)).toFloat()
                else -> i.toFloat() / max(1, n - 1).toFloat()
            }

            val rgb: IntArray
            if (pinned[i].toInt() != 0) {
                rgb = intArrayOf(255, 255, 255)
                nodePaint.color = Color.WHITE
            } else {
                rgb = lerpRgb(palR, palG, palB, ct)
                nodePaint.color = Color.rgb(rgb[0], rgb[1], rgb[2])
            }

            // Velocity-responsive node size (nodes grow when moving fast)
            val speedBoost = min(1.8, 1.0 + speed / (6.0 * scale))
            val r = if (pinned[i].toInt() != 0) ns * 1.3f else (ns * speedBoost).toFloat()
            canvas.drawCircle(x[i].toFloat(), y[i].toFloat(), r, nodePaint)

            if ((style == "glow" || style == "trails") && pinned[i].toInt() == 0) {
                // Bloom layer — larger when faster
                val bloomR = (r * (2.0 + speed / (4.0 * scale))).toFloat()
                val bloomA = min(0.2, 0.05 + speed / (20.0 * scale)).toFloat()
                bloomPaint.color = Color.argb(
                    (bloomA * 255f).roundToInt().coerceIn(0, 255),
                    rgb[0], rgb[1], rgb[2]
                )
                canvas.drawCircle(x[i].toFloat(), y[i].toFloat(), bloomR, bloomPaint)
            }
        }
    }

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float {
        val nc = pf(params, "nodeCount", 18f)
        val stiff = pf(params, "stiffness", 5f)
        return (nc * nc * stiff * 0.01f / 1000f).coerceIn(0.1f, 1f)
    }
}
