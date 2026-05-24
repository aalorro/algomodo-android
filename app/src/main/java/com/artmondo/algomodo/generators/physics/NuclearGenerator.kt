package com.artmondo.algomodo.generators.physics

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
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

/**
 * Particles undergo nuclear-style fission and fusion — heavy particles split into
 * branching daughter cascades, small particles merge under gravity into bright cores,
 * leaving glowing flash rings and decaying trails on a fading dark canvas.
 */
class NuclearGenerator : Generator {

    override val id = "physics-nuclear"
    override val family = "physics"
    override val styleName = "Nuclear"
    override val definition =
        "Particles undergo nuclear-style fission and fusion — heavy particles split into " +
        "branching daughter cascades, small particles merge under gravity into bright cores, " +
        "leaving glowing flash rings and decaying trails on a fading dark canvas"
    override val algorithmNotes =
        "Fixed-capacity particle pool where each particle carries mass and velocity. Three modes: " +
        "fission (a single heavy seed cascades into daughters when mass exceeds threshold; fragments " +
        "fly off in a fan around the parent's velocity with kinetic energy converted from ~15% of " +
        "parent mass), fusion (many small particles drift inward under a softened gravity well; pairs " +
        "within a mass-scaled capture radius merge by averaging position, summing mass, and conserving " +
        "momentum), and combined (fusion runs until a particle reaches critical mass, then is forcibly " +
        "fissioned, creating a breathing stellar cycle). Each fusion or fission event spawns a circular " +
        "flash ring that expands and fades over ~1 second. The canvas itself is not cleared between " +
        "frames — instead a low-alpha black rect fades existing pixels, so trails and flash rings " +
        "accumulate and slowly decay. Rendering uses globalCompositeOperation=lighter for additive bloom: " +
        "each particle is drawn as a 3-layer halo + bloom + brightened core. Trail width and color scale " +
        "with the square root of mass (heavier = thicker, warmer end of palette). Audio: bass → fission " +
        "rate boost, mid → gravity boost."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema = listOf(
        Parameter.SelectParam(
            name = "Mode", key = "mode", group = ParamGroup.COMPOSITION,
            help = "combined: fusion → critical mass → fission → repeat | fission: branching cascade from one heavy seed | fusion: small particles merge into bright cores",
            options = listOf("combined", "fission", "fusion"),
            default = "combined"
        ),
        Parameter.NumberParam(
            name = "Seed Mass", key = "seedMass", group = ParamGroup.COMPOSITION,
            help = "Initial mass of the fission seed particle",
            min = 4f, max = 30f, step = 1f, default = 14f
        ),
        Parameter.NumberParam(
            name = "Initial Particles", key = "initialCount", group = ParamGroup.COMPOSITION,
            help = "Number of small particles for fusion / combined modes",
            min = 20f, max = 200f, step = 10f, default = 90f
        ),
        Parameter.NumberParam(
            name = "Fission Threshold", key = "splitMassThreshold", group = ParamGroup.COMPOSITION,
            help = "Minimum mass required for spontaneous fission",
            min = 1f, max = 10f, step = 0.5f, default = 3f
        ),
        Parameter.NumberParam(
            name = "Fission Rate", key = "splitProbability", group = ParamGroup.FLOW_MOTION,
            help = "Per-frame fission chance for eligible particles (scales with mass)",
            min = 0f, max = 0.2f, step = 0.005f, default = 0.04f
        ),
        Parameter.NumberParam(
            name = "Critical Mass", key = "criticalMass", group = ParamGroup.COMPOSITION,
            help = "In combined mode, mass at which a particle definitely fissions",
            min = 8f, max = 50f, step = 1f, default = 20f
        ),
        Parameter.NumberParam(
            name = "Capture Radius", key = "captureRadius", group = ParamGroup.FLOW_MOTION,
            help = "Distance at which two particles fuse (scales with combined mass)",
            min = 4f, max = 30f, step = 1f, default = 10f
        ),
        Parameter.NumberParam(
            name = "Gravity Well", key = "centerAttract", group = ParamGroup.FLOW_MOTION,
            help = "Inward pull toward canvas center (fusion/combined modes)",
            min = 0f, max = 3f, step = 0.1f, default = 0.7f
        ),
        Parameter.NumberParam(
            name = "Drag", key = "decayRate", group = ParamGroup.FLOW_MOTION,
            help = "Velocity decay per frame",
            min = 0f, max = 0.05f, step = 0.005f, default = 0.012f
        ),
        Parameter.NumberParam(
            name = "Trail Length", key = "trailLength", group = ParamGroup.TEXTURE,
            help = "Number of past positions per particle trail",
            min = 5f, max = 50f, step = 1f, default = 24f
        ),
        Parameter.NumberParam(
            name = "Canvas Fade", key = "trailFade", group = ParamGroup.TEXTURE,
            help = "How fast the canvas fades each frame — lower = more persistent accumulation",
            min = 0f, max = 0.2f, step = 0.005f, default = 0.05f
        ),
        Parameter.NumberParam(
            name = "Particle Scale", key = "particleScale", group = ParamGroup.GEOMETRY,
            help = null,
            min = 0.5f, max = 3f, step = 0.1f, default = 1.0f
        ),
        Parameter.NumberParam(
            name = "Energy", key = "energy", group = ParamGroup.FLOW_MOTION,
            help = "Overall energy level — controls simulation speed, thermal jitter, and fission violence",
            min = 0.5f, max = 3f, step = 0.1f, default = 1.5f
        ),
        Parameter.SelectParam(
            name = "Color Mode", key = "colorMode", group = ParamGroup.COLOR,
            help = "mass: warm-to-cool gradient by particle mass | palette: random palette index per particle",
            options = listOf("mass", "palette"),
            default = "mass"
        ),
        Parameter.NumberParam(
            name = "Audio Reactivity", key = "reactivity", group = ParamGroup.FLOW_MOTION,
            help = null,
            min = 0f, max = 2f, step = 0.1f, default = 0f
        )
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "mode" to "combined",
        "seedMass" to 14f,
        "initialCount" to 90f,
        "splitMassThreshold" to 3f,
        "splitProbability" to 0.04f,
        "criticalMass" to 20f,
        "captureRadius" to 10f,
        "centerAttract" to 0.7f,
        "decayRate" to 0.012f,
        "trailLength" to 24f,
        "trailFade" to 0.05f,
        "particleScale" to 1.0f,
        "energy" to 1.5f,
        "colorMode" to "mass",
        "reactivity" to 0f
    )

    // ── Param helpers ───────────────────────────────────────────────────────
    private fun pf(p: Map<String, Any>, k: String, default: Float): Float =
        (p[k] as? Number)?.toFloat() ?: default
    private fun pi(p: Map<String, Any>, k: String, default: Int): Int =
        (p[k] as? Number)?.toInt() ?: default
    private fun ps(p: Map<String, Any>, k: String, default: String): String =
        (p[k] as? String) ?: default

    // ── Nuclear simulation state ────────────────────────────────────────────
    private class NuclearState(
        var key: String,
        val cap: Int,
        // Particle SoA
        val x: DoubleArray,
        val y: DoubleArray,
        val vx: DoubleArray,
        val vy: DoubleArray,
        val mass: DoubleArray,
        val alive: ByteArray,
        val colorIdx: IntArray,
        // Per-particle trail circular buffer
        val trailX: DoubleArray,
        val trailY: DoubleArray,
        val trailHead: IntArray,
        val trailCount: IntArray,
        val trailLen: Int,
        // Flash event circular buffer
        val flashX: DoubleArray,
        val flashY: DoubleArray,
        val flashAge: DoubleArray,
        val flashMass: DoubleArray,
        var flashHead: Int,
        var flashFill: Int,
        // Pool
        var count: Int,
        var nextSlot: Int,
        val rng: SeededRNG,
        var step: Int,
        var needsClear: Boolean,
        val initialCount: Int
    )

    companion object {
        private const val MAX_PARTICLES = 600
        private const val FLASH_CAP = 96

        @Volatile
        private var _ns: NuclearState? = null

        // Reusable scratch buffer for fission candidate snapshot — avoids per-frame allocation
        private val _fissionCandidates = IntArray(MAX_PARTICLES)

        // Cached palette → RGB conversion (keyed by palette colors list reference)
        @Volatile
        private var _cachedPaletteRef: Any? = null
        @Volatile
        private var _cachedColors: Array<IntArray> = emptyArray()
    }

    private fun findSlot(state: NuclearState): Int {
        val cap = state.cap
        val alive = state.alive
        for (i in 0 until cap) {
            val idx = (state.nextSlot + i) % cap
            if (alive[idx].toInt() == 0) {
                state.nextSlot = (idx + 1) % cap
                return idx
            }
        }
        // Pool full — recycle the lowest-mass particle
        var minIdx = 0
        var minMass = Double.POSITIVE_INFINITY
        for (i in 0 until cap) {
            if (state.mass[i] < minMass) {
                minMass = state.mass[i]; minIdx = i
            }
        }
        return minIdx
    }

    private fun spawnParticle(
        state: NuclearState,
        x: Double, y: Double,
        vx: Double, vy: Double,
        mass: Double, colorIdx: Int
    ): Int {
        val idx = findSlot(state)
        if (state.alive[idx].toInt() == 0) state.count++
        state.x[idx] = x
        state.y[idx] = y
        state.vx[idx] = vx
        state.vy[idx] = vy
        state.mass[idx] = mass
        state.alive[idx] = 1
        state.colorIdx[idx] = colorIdx
        val tBase = idx * state.trailLen
        state.trailX[tBase] = x
        state.trailY[tBase] = y
        state.trailHead[idx] = 0
        state.trailCount[idx] = 1
        return idx
    }

    private fun killParticle(state: NuclearState, idx: Int) {
        if (state.alive[idx].toInt() != 0) {
            state.alive[idx] = 0
            state.count--
        }
    }

    private fun addFlash(state: NuclearState, x: Double, y: Double, mass: Double) {
        val idx = state.flashHead
        state.flashX[idx] = x
        state.flashY[idx] = y
        state.flashAge[idx] = 0.0
        state.flashMass[idx] = mass
        state.flashHead = (state.flashHead + 1) % FLASH_CAP
        if (state.flashFill < FLASH_CAP) state.flashFill++
    }

    // ── Fission: split a particle into 2-3 daughters ────────────────────────
    private fun splitParticle(state: NuclearState, idx: Int, energyMult: Double) {
        val rng = state.rng
        val px = state.x[idx]; val py = state.y[idx]
        val pvx = state.vx[idx]; val pvy = state.vy[idx]
        val pmass = state.mass[idx]
        val pcolor = state.colorIdx[idx]

        // Heavier parents prefer 3 fragments
        val nFrag = if (pmass > 8.0 && rng.random() < 0.55f) 3 else 2

        // Random mass shares totalling ~85% of parent (15% mass→energy)
        val shares = DoubleArray(nFrag)
        var total = 0.0
        for (i in 0 until nFrag) {
            shares[i] = 0.4 + rng.random().toDouble() * 0.6
            total += shares[i]
        }
        val massBudget = pmass * 0.85
        val releasedEnergy = pmass * 0.55 * energyMult

        addFlash(state, px, py, pmass)
        killParticle(state, idx)

        // Base direction = parent velocity (random fallback if static)
        val pSpeed2 = pvx * pvx + pvy * pvy
        val baseAngle = if (pSpeed2 > 0.0001) atan2(pvy, pvx) else rng.random().toDouble() * PI * 2.0

        for (i in 0 until nFrag) {
            val fragMass = (shares[i] / total) * massBudget
            val fanOffset = ((i + 0.5) / nFrag - 0.5) * PI * (1.2 + energyMult * 0.5) + (rng.random() - 0.5f) * 0.6
            val angle = baseAngle + fanOffset
            val fragKE = (releasedEnergy / nFrag) * (0.7 + rng.random().toDouble() * 0.6)
            val speed = sqrt((2.0 * fragKE) / fragMass)
            val fvx = pvx * 0.4 + cos(angle) * speed
            val fvy = pvy * 0.4 + sin(angle) * speed
            spawnParticle(state, px, py, fvx, fvy, fragMass, pcolor)
        }
    }

    // ── Fusion: merge two particles, conserving momentum ────────────────────
    private fun fuseParticles(state: NuclearState, i: Int, j: Int) {
        val m1 = state.mass[i]; val m2 = state.mass[j]
        val total = m1 + m2
        val px = (state.x[i] * m1 + state.x[j] * m2) / total
        val py = (state.y[i] * m1 + state.y[j] * m2) / total
        val vx = (state.vx[i] * m1 + state.vx[j] * m2) / total
        val vy = (state.vy[i] * m1 + state.vy[j] * m2) / total
        // Heavier parent's color wins
        val colorIdx = if (m1 >= m2) state.colorIdx[i] else state.colorIdx[j]

        state.x[i] = px
        state.y[i] = py
        state.vx[i] = vx
        state.vy[i] = vy
        state.mass[i] = total
        state.colorIdx[i] = colorIdx
        val tBase = i * state.trailLen
        state.trailX[tBase] = px
        state.trailY[tBase] = py
        state.trailHead[i] = 0
        state.trailCount[i] = 1

        killParticle(state, j)
        addFlash(state, px, py, total)
    }

    // ── Initialization ──────────────────────────────────────────────────────
    private fun initState(
        cap: Int, trailLen: Int, seed: Int,
        w: Int, h: Int, mode: String,
        seedMass: Double, initialCount: Int
    ): NuclearState {
        val state = NuclearState(
            key = "", cap = cap,
            x = DoubleArray(cap),
            y = DoubleArray(cap),
            vx = DoubleArray(cap),
            vy = DoubleArray(cap),
            mass = DoubleArray(cap),
            alive = ByteArray(cap),
            colorIdx = IntArray(cap),
            trailX = DoubleArray(cap * trailLen),
            trailY = DoubleArray(cap * trailLen),
            trailHead = IntArray(cap),
            trailCount = IntArray(cap),
            trailLen = trailLen,
            flashX = DoubleArray(FLASH_CAP),
            flashY = DoubleArray(FLASH_CAP),
            flashAge = DoubleArray(FLASH_CAP),
            flashMass = DoubleArray(FLASH_CAP),
            flashHead = 0, flashFill = 0,
            count = 0, nextSlot = 0,
            rng = SeededRNG(seed),
            step = 0,
            needsClear = true,
            initialCount = initialCount
        )

        val cx = w / 2.0
        val cy = h / 2.0

        if (mode == "fission") {
            val ang = state.rng.random().toDouble() * PI * 2.0
            spawnParticle(state, cx, cy, cos(ang) * 0.4, sin(ang) * 0.4, seedMass, 0)
        } else {
            // Fusion or combined: scatter many small particles in an annulus around center
            for (i in 0 until initialCount) {
                val angle = state.rng.random().toDouble() * PI * 2.0
                val r = min(w, h) * (0.18 + state.rng.random().toDouble() * 0.32)
                val px = cx + cos(angle) * r
                val py = cy + sin(angle) * r
                val m = 0.6 + state.rng.random().toDouble() * 0.9
                val vAngle = angle + PI / 2.0 + (state.rng.random() - 0.5f) * 0.8
                val vMag = 0.4 + state.rng.random().toDouble() * 0.6
                spawnParticle(state, px, py, cos(vAngle) * vMag, sin(vAngle) * vMag, m, i % 8)
            }
        }
        return state
    }

    // ── Simulation step ─────────────────────────────────────────────────────
    private fun stepSimulation(
        state: NuclearState,
        mode: String,
        splitProb: Double,
        splitMassThreshold: Double,
        centerAttract: Double,
        captureRadius: Double,
        criticalMass: Double,
        decayRate: Double,
        energy: Double,
        cx: Double, cy: Double,
        w: Int, h: Int
    ) {
        val cap = state.cap
        val rng = state.rng
        val drag = 1.0 - decayRate
        val doFission = mode == "fission" || mode == "combined"
        val doFusion = mode == "fusion" || mode == "combined"
        val doCombined = mode == "combined"
        val wantsAttract = doFusion && centerAttract > 0.0
        val jitter = energy * 0.2

        val x = state.x; val y = state.y
        val vx = state.vx; val vy = state.vy
        val mass = state.mass
        val alive = state.alive
        val trailX = state.trailX; val trailY = state.trailY
        val trailHead = state.trailHead; val trailCount = state.trailCount
        val trailLen = state.trailLen

        // 1. Forces, thermal jitter, and integration
        for (i in 0 until cap) {
            if (alive[i].toInt() == 0) continue

            if (wantsAttract) {
                val dx = cx - x[i]
                val dy = cy - y[i]
                val d2 = dx * dx + dy * dy + 400.0
                val inv = centerAttract / sqrt(d2)
                vx[i] += dx * inv
                vy[i] += dy * inv
            }

            vx[i] += (rng.random() - 0.5f) * jitter
            vy[i] += (rng.random() - 0.5f) * jitter

            vx[i] *= drag
            vy[i] *= drag

            x[i] += vx[i]
            y[i] += vy[i]

            // Soft bounce off canvas edges
            if (x[i] < 8.0) { x[i] = 8.0; vx[i] = abs(vx[i]) * 0.5 }
            else if (x[i] > w - 8.0) { x[i] = w - 8.0; vx[i] = -abs(vx[i]) * 0.5 }
            if (y[i] < 8.0) { y[i] = 8.0; vy[i] = abs(vy[i]) * 0.5 }
            else if (y[i] > h - 8.0) { y[i] = h - 8.0; vy[i] = -abs(vy[i]) * 0.5 }

            // Trail update
            val tBase = i * trailLen
            val head = (trailHead[i] + 1) % trailLen
            trailX[tBase + head] = x[i]
            trailY[tBase + head] = y[i]
            trailHead[i] = head
            if (trailCount[i] < trailLen) trailCount[i]++
        }

        // 2. Random fission events
        if (doFission) {
            var nCand = 0
            for (i in 0 until cap) {
                if (alive[i].toInt() != 0 && mass[i] >= splitMassThreshold) {
                    _fissionCandidates[nCand++] = i
                }
            }
            val invThresh = 1.0 / splitMassThreshold
            for (k in 0 until nCand) {
                val idx = _fissionCandidates[k]
                if (rng.random() < splitProb * (mass[idx] * invThresh)) {
                    splitParticle(state, idx, energy)
                }
            }
        }

        // 3. Combined mode: forced fission when reaching critical mass
        if (doCombined) {
            for (i in 0 until cap) {
                if (alive[i].toInt() != 0 && mass[i] >= criticalMass) {
                    splitParticle(state, i, energy)
                }
            }
        }

        // 4. Fusion + short-range repulsion (O(n²))
        if (doFusion) {
            val repulseRadSq = captureRadius * captureRadius * 9.0
            for (i in 0 until cap) {
                if (alive[i].toInt() == 0) continue
                val xi = x[i]; val yi = y[i]
                val radI = captureRadius + mass[i] * 0.4
                for (j in i + 1 until cap) {
                    if (alive[j].toInt() == 0) continue
                    val dx = xi - x[j]
                    val dy = yi - y[j]
                    val d2 = dx * dx + dy * dy
                    val radSum = radI + mass[j] * 0.4
                    if (d2 < radSum * radSum) {
                        fuseParticles(state, i, j)
                    } else if (d2 < repulseRadSq && d2 > 1.0) {
                        val inv = energy * 0.3 / d2
                        vx[i] += dx * inv
                        vy[i] += dy * inv
                        vx[j] -= dx * inv
                        vy[j] -= dy * inv
                    }
                }
            }
        }

        // 5. Particle replenishment
        val minPop = (state.initialCount * 0.35).toInt()
        if (state.count < minPop && state.count < cap * 0.8) {
            val numSpawn = min(5, minPop - state.count + 1)
            for (s in 0 until numSpawn) {
                val angle = rng.random().toDouble() * PI * 2.0
                val r = min(w, h) * (0.12 + rng.random().toDouble() * 0.3)
                val px = cx + cos(angle) * r
                val py = cy + sin(angle) * r
                val m = 0.5 + rng.random().toDouble() * 1.2
                val vAngle = angle + PI * 0.5 + (rng.random() - 0.5f) * 0.8
                val vMag = (0.3 + rng.random().toDouble() * 0.5) * energy
                spawnParticle(state, px, py, cos(vAngle) * vMag, sin(vAngle) * vMag, m, rng.integer(0, 7))
            }
        }

        // 6. Age existing flashes
        val flashAge = state.flashAge
        for (f in 0 until state.flashFill) {
            if (flashAge[f] <= 60.0) flashAge[f] += 1.0
        }

        state.step++
    }

    // ── Color helpers ───────────────────────────────────────────────────────
    private fun lerpColor(colors: Array<IntArray>, t: Float): IntArray {
        val s = t.coerceIn(0f, 1f) * (colors.size - 1)
        val i0 = s.toInt()
        val i1 = min(colors.size - 1, i0 + 1)
        val f = s - i0
        return intArrayOf(
            (colors[i0][0] + (colors[i1][0] - colors[i0][0]) * f).toInt(),
            (colors[i0][1] + (colors[i1][1] - colors[i0][1]) * f).toInt(),
            (colors[i0][2] + (colors[i1][2] - colors[i0][2]) * f).toInt()
        )
    }

    private fun lerpColorD(colors: Array<IntArray>, t: Double): IntArray {
        val s = t.coerceIn(0.0, 1.0) * (colors.size - 1)
        val i0 = s.toInt()
        val i1 = min(colors.size - 1, i0 + 1)
        val f = s - i0
        return intArrayOf(
            (colors[i0][0] + (colors[i1][0] - colors[i0][0]) * f).toInt(),
            (colors[i0][1] + (colors[i1][1] - colors[i0][1]) * f).toInt(),
            (colors[i0][2] + (colors[i1][2] - colors[i0][2]) * f).toInt()
        )
    }

    // ── Render ──────────────────────────────────────────────────────────────
    private fun renderState(
        canvas: Canvas,
        state: NuclearState,
        colors: Array<IntArray>,
        trailFade: Float,
        particleScale: Float,
        colorMode: String,
        w: Int, h: Int
    ) {
        // Background fade — accumulates trails and flash glow
        val fadePaint = Paint()
        if (state.needsClear) {
            canvas.drawColor(Color.rgb(5, 5, 16))
            state.needsClear = false
        } else {
            val fadeAlpha = (trailFade * 255f).toInt().coerceIn(0, 255)
            fadePaint.color = Color.argb(fadeAlpha, 5, 5, 16)
            fadePaint.style = Paint.Style.FILL
            canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), fadePaint)
        }

        // Additive bloom paints
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
        }
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
        }

        val nColors = colors.size
        val massNorm = 22.0
        val usePaletteColor = colorMode == "palette"

        val x = state.x; val y = state.y
        val mass = state.mass
        val alive = state.alive
        val colorIdx = state.colorIdx
        val trailX = state.trailX; val trailY = state.trailY
        val trailHead = state.trailHead; val trailCount = state.trailCount
        val trailLen = state.trailLen
        val cap = state.cap

        // 1. Flash rings — expanding circles from fusion/fission events
        val maxFlashAge = 60.0
        val flashAge = state.flashAge
        val flashMass = state.flashMass
        val flashX = state.flashX; val flashY = state.flashY
        for (f in 0 until state.flashFill) {
            val age = flashAge[f]
            if (age < 0.0 || age > maxFlashAge) continue
            val t = age / maxFlashAge
            val fmass = flashMass[f]
            val radius = (4.0 + fmass * 1.2) * (0.4 + t * 1.8)
            val alpha = (1.0 - t) * 0.55
            val massT = min(1.0, fmass / massNorm)
            val rgb = lerpColorD(colors, massT)
            val alphaInt = (alpha * 255.0).toInt().coerceIn(0, 255)
            strokePaint.color = Color.argb(alphaInt, rgb[0].coerceIn(0, 255), rgb[1].coerceIn(0, 255), rgb[2].coerceIn(0, 255))
            strokePaint.strokeWidth = ((1.0 + fmass * 0.18) * (1.0 - t * 0.6)).toFloat().coerceAtLeast(0.1f)
            canvas.drawCircle(flashX[f].toFloat(), flashY[f].toFloat(), radius.toFloat(), strokePaint)
        }

        // 2. Particles — trails + 3-layer additive bloom core
        for (i in 0 until cap) {
            if (alive[i].toInt() == 0) continue
            val m = mass[i]
            val massT = min(1.0, m / massNorm)

            val rgb: IntArray = if (usePaletteColor) {
                colors[((colorIdx[i] % nColors) + nColors) % nColors]
            } else {
                lerpColorD(colors, massT)
            }
            val r = rgb[0]; val g = rgb[1]; val b = rgb[2]

            val baseRad = ((1.0 + sqrt(m) * 1.4) * particleScale).toFloat()
            val px = x[i].toFloat(); val py = y[i].toFloat()

            // Trail (walking decrement avoids modulo per segment)
            val tc = trailCount[i]
            if (tc >= 2) {
                val tBase = i * trailLen
                var cur = trailHead[i]
                for (j in 0 until tc - 1) {
                    var prev = cur - 1
                    if (prev < 0) prev += trailLen
                    val segLife = 1f - j.toFloat() / tc.toFloat()
                    val segAlpha = segLife * 0.55f
                    val segAlphaInt = (segAlpha * 255f).toInt().coerceIn(0, 255)
                    strokePaint.color = Color.argb(segAlphaInt, r.coerceIn(0, 255), g.coerceIn(0, 255), b.coerceIn(0, 255))
                    strokePaint.strokeWidth = (baseRad * segLife * 0.85f).coerceAtLeast(0.1f)
                    canvas.drawLine(
                        trailX[tBase + cur].toFloat(), trailY[tBase + cur].toFloat(),
                        trailX[tBase + prev].toFloat(), trailY[tBase + prev].toFloat(),
                        strokePaint
                    )
                    cur = prev
                }
            }

            val rC = r.coerceIn(0, 255)
            val gC = g.coerceIn(0, 255)
            val bC = b.coerceIn(0, 255)

            // Wide halo
            fillPaint.color = Color.argb((0.10f * 255f).toInt(), rC, gC, bC)
            canvas.drawCircle(px, py, baseRad * 3.2f, fillPaint)
            // Mid bloom
            fillPaint.color = Color.argb((0.25f * 255f).toInt(), rC, gC, bC)
            canvas.drawCircle(px, py, baseRad * 1.6f, fillPaint)
            // Bright core (shifted toward white)
            val cr = min(255, r + 90)
            val cg = min(255, g + 90)
            val cb = min(255, b + 90)
            fillPaint.color = Color.argb((0.85f * 255f).toInt(), cr, cg, cb)
            canvas.drawCircle(px, py, baseRad * 0.7f, fillPaint)
        }
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
        val mode = ps(params, "mode", "combined")
        val seedMass = pf(params, "seedMass", 14f).toDouble()
        val initialCount = pi(params, "initialCount", 90)
        val splitMassThreshold = pf(params, "splitMassThreshold", 3f).toDouble()
        var splitProbability = pf(params, "splitProbability", 0.04f).toDouble()
        val criticalMass = pf(params, "criticalMass", 20f).toDouble()
        val captureRadius = pf(params, "captureRadius", 10f).toDouble()
        var centerAttract = pf(params, "centerAttract", 0.7f).toDouble()
        val decayRate = pf(params, "decayRate", 0.012f).toDouble()
        val trailLen = max(2, pi(params, "trailLength", 24))
        val trailFade = pf(params, "trailFade", 0.05f)
        val particleScale = pf(params, "particleScale", 1.0f)
        val colorMode = ps(params, "colorMode", "mass")
        val energy = pf(params, "energy", 1.5f).toDouble()

        // Audio reactivity
        val rx = pf(params, "reactivity", 0f)
        val audio = params["_audioAnalysis"] as? AudioAnalysis
        val audioBass = (audio?.getBass(time) ?: 0f) * rx
        val audioMid = (audio?.getMid(time) ?: 0f) * rx
        splitProbability += audioBass * 0.05
        centerAttract += audioMid * 0.5

        // Cache palette → RGB conversion (keyed by palette.colors reference)
        val paletteColors = palette.colors
        var colors: Array<IntArray>
        synchronized(NuclearGenerator::class.java) {
            if (_cachedPaletteRef !== paletteColors) {
                _cachedPaletteRef = paletteColors
                _cachedColors = Array(paletteColors.size) { i ->
                    val c = Color.parseColor(paletteColors[i])
                    intArrayOf(Color.red(c), Color.green(c), Color.blue(c))
                }
            }
            colors = _cachedColors
        }
        if (colors.isEmpty()) {
            colors = arrayOf(intArrayOf(255, 255, 255))
        }

        val scale = w / 800.0
        val captureScaled = captureRadius * scale
        val cx = w / 2.0
        val cy = h / 2.0

        // Cache key — reset state when physics params change
        val key = "$seed|$trailLen|$mode|$w|$h|$seedMass|$initialCount|$splitMassThreshold|" +
            "${"%.3f".format(splitProbability)}|$criticalMass|$captureRadius|${"%.1f".format(centerAttract)}|$decayRate"

        val state: NuclearState
        synchronized(NuclearGenerator::class.java) {
            var s = _ns
            if (s == null || s.key != key) {
                s = initState(MAX_PARTICLES, trailLen, seed, w, h, mode, seedMass, initialCount)
                s.key = key
                _ns = s
            }
            state = s
        }

        synchronized(state) {
            // Static warmup if state is fresh and we're displaying a still frame
            if (time <= 0f && state.step == 0) {
                val warmup = when (mode) {
                    "fission" -> 50
                    "fusion" -> 240
                    else -> 200
                }
                for (f in 0 until warmup) {
                    stepSimulation(
                        state, mode, splitProbability, splitMassThreshold,
                        centerAttract * scale, captureScaled, criticalMass,
                        decayRate, energy, cx, cy, w, h
                    )
                }
            }

            // Animation steps — multiple sub-steps for more active simulation
            if (time > 0f) {
                val simSteps = max(1, (energy * 1.5).roundToInt())
                for (s2 in 0 until simSteps) {
                    stepSimulation(
                        state, mode, splitProbability, splitMassThreshold,
                        centerAttract * scale, captureScaled, criticalMass,
                        decayRate, energy, cx, cy, w, h
                    )
                }
            }

            renderState(canvas, state, colors, trailFade, particleScale, colorMode, w, h)
        }
    }

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float {
        val trailLen = pf(params, "trailLength", 24f)
        return (MAX_PARTICLES * trailLen * 0.15f / 1000f).coerceIn(0.1f, 1f)
    }
}
