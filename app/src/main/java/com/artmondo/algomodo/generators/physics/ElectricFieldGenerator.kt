package com.artmondo.algomodo.generators.physics

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.Typeface
import android.opengl.GLES30
import com.artmondo.algomodo.audio.AudioAnalysis
import com.artmondo.algomodo.core.rng.SeededRNG
import com.artmondo.algomodo.data.palettes.Palette
import com.artmondo.algomodo.generators.GpuGenerator
import com.artmondo.algomodo.generators.ParamGroup
import com.artmondo.algomodo.generators.Parameter
import com.artmondo.algomodo.generators.Quality
import com.artmondo.algomodo.rendering.gl.GpuShaderRunner
import com.artmondo.algomodo.rendering.gl.PaletteUniform
import kotlin.math.*

class ElectricFieldGenerator : GpuGenerator {

    override val id = "physics-electric-field"
    override val family = "physics"
    override val styleName = "Electric Field"
    override val definition =
        "Point charges generate Coulomb electric fields — tracer particles and field lines follow E = kq/r², " +
        "revealing the invisible force landscape of positive and negative charges"
    override val algorithmNotes =
        "Electric field computed as E(p) = kΣ q_i·(p−r_i)/|p−r_i|³ with distance clamping to avoid singularities. " +
        "Field lines traced via Euler integration of normalized E with fixed step size, starting from rings around each charge. " +
        "Positive charges emit lines outward; negative charges traced in reverse (−E direction). " +
        "Equipotential mode computes V = kΣ q_i/|r_i| on a ¼-resolution grid, maps to palette via Uint32Array ABGR pixel writes, then upscales. " +
        "Particles mode: tracers follow E direction with limited lifetime, respawn on absorption or edge exit. " +
        "Charge layouts: random (seeded), ring (alternating polarity), dipole, quadrupole. " +
        "Animation moves charges via orbit/oscillate/drift patterns; dirty flag triggers field line recomputation only when needed."

    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema = listOf(
        Parameter.SelectParam(
            name = "Style", key = "style", group = ParamGroup.COMPOSITION,
            help = "fieldLines: traced curves | equipotential: color map of V | particles: flowing tracers | combined: field lines + charges",
            options = listOf("fieldLines", "equipotential", "particles", "combined"),
            default = "fieldLines"
        ),
        Parameter.NumberParam(
            name = "Charges", key = "chargeCount", group = ParamGroup.COMPOSITION,
            help = "Number of point charges placed on the canvas",
            min = 2f, max = 15f, step = 1f, default = 5f
        ),
        Parameter.SelectParam(
            name = "Layout", key = "chargeLayout", group = ParamGroup.COMPOSITION,
            help = "random: seeded positions | ring: circular arrangement | dipole: two-charge pair | quadrupole: four-charge square",
            options = listOf("random", "ring", "dipole", "quadrupole"),
            default = "random"
        ),
        Parameter.NumberParam(
            name = "Tracers", key = "tracerCount", group = ParamGroup.GEOMETRY,
            help = "Number of field line starting points or flowing particles",
            min = 50f, max = 500f, step = 25f, default = 200f
        ),
        Parameter.NumberParam(
            name = "Trace Steps", key = "traceSteps", group = ParamGroup.GEOMETRY,
            help = "Maximum integration steps per field line",
            min = 100f, max = 800f, step = 50f, default = 400f
        ),
        Parameter.NumberParam(
            name = "Field Strength", key = "fieldStrength", group = ParamGroup.FLOW_MOTION,
            help = "Coulomb constant multiplier — controls field intensity",
            min = 0.5f, max = 5f, step = 0.25f, default = 2f
        ),
        Parameter.SelectParam(
            name = "Charge Motion", key = "chargeMotion", group = ParamGroup.FLOW_MOTION,
            help = "pulse: magnitudes breathe in place | orbit: around center | oscillate: back and forth | drift: random bounce",
            options = listOf("pulse", "orbit", "oscillate", "drift"),
            default = "pulse"
        ),
        Parameter.NumberParam(
            name = "Motion Speed", key = "motionSpeed", group = ParamGroup.FLOW_MOTION,
            help = "Speed of charge motion in animated modes",
            min = 0.1f, max = 2f, step = 0.1f, default = 0.5f
        ),
        Parameter.SelectParam(
            name = "Color Mode", key = "colorMode", group = ParamGroup.COLOR,
            help = "palette: cycle colors | polarity: +/- coloring | potential: map V to palette | direction: field angle to hue",
            options = listOf("palette", "polarity", "potential", "direction"),
            default = "palette"
        ),
        Parameter.NumberParam(
            name = "Line Width", key = "lineWidth", group = ParamGroup.GEOMETRY,
            help = null,
            min = 0.5f, max = 3f, step = 0.5f, default = 1f
        ),
        Parameter.NumberParam(
            name = "Steps / Frame", key = "stepsPerFrame", group = ParamGroup.FLOW_MOTION,
            help = null,
            min = 1f, max = 6f, step = 1f, default = 2f
        ),
        Parameter.NumberParam(
            name = "Audio Reactivity", key = "reactivity", group = ParamGroup.FLOW_MOTION,
            help = null,
            min = 0f, max = 2f, step = 0.1f, default = 0f
        )
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "style" to "fieldLines",
        "chargeCount" to 5f,
        "chargeLayout" to "random",
        "tracerCount" to 200f,
        "traceSteps" to 400f,
        "fieldStrength" to 2f,
        "chargeMotion" to "pulse",
        "motionSpeed" to 0.5f,
        "colorMode" to "palette",
        "lineWidth" to 1f,
        "stepsPerFrame" to 2f,
        "reactivity" to 0f
    )

    // ── Param helpers ───────────────────────────────────────────────────────
    private fun pf(p: Map<String, Any>, k: String, default: Float): Float =
        (p[k] as? Number)?.toFloat() ?: default

    private fun pi(p: Map<String, Any>, k: String, default: Int): Int =
        (p[k] as? Number)?.toInt() ?: default

    private fun ps(p: Map<String, Any>, k: String, default: String): String =
        (p[k] as? String) ?: default

    // ── Cached state ────────────────────────────────────────────────────────
    private class FieldState(
        var key: String,
        val nCharges: Int,
        val chargeX: DoubleArray,
        val chargeY: DoubleArray,
        val chargeQ: DoubleArray,
        val initX: DoubleArray,
        val initY: DoubleArray,
        val initQ: DoubleArray,
        val driftDx: DoubleArray,
        val driftDy: DoubleArray,
        var tracerX: DoubleArray,
        var tracerY: DoubleArray,
        var tracerAge: DoubleArray,
        var tracerMaxAge: DoubleArray,
        var nTracers: Int,
        val lineX: DoubleArray,
        val lineY: DoubleArray,
        val lineCount: IntArray,
        var nLines: Int,
        val maxLinePoints: Int,
        var potGrid: DoubleArray,
        var potW: Int,
        var potH: Int,
        val paletteLut: IntArray,
        var eqPixels: IntArray?,
        var eqBitmap: Bitmap?,
        var step: Int,
        val rng: SeededRNG,
        var dirty: Boolean,
        // GPU equipotential normalization (potential range absMax in V units).
        var potAbsMax: Double = 1.0
    )

    companion object {
        @Volatile
        private var state: FieldState? = null

        private const val MIN_DIST_SQ = 25.0
        private const val K_COULOMB = 100.0
        private const val ABSORB_DIST_SQ = 144.0
        private const val FIELD_LINE_STEP = 3.0
        private const val MAX_GPU_CHARGES = 16
    }

    private fun makeState(
        nCharges: Int, nTracers: Int, maxLines: Int, maxLinePoints: Int, seed: Int
    ): FieldState {
        return FieldState(
            key = "",
            nCharges = nCharges,
            chargeX = DoubleArray(nCharges),
            chargeY = DoubleArray(nCharges),
            chargeQ = DoubleArray(nCharges),
            initX = DoubleArray(nCharges),
            initY = DoubleArray(nCharges),
            initQ = DoubleArray(nCharges),
            driftDx = DoubleArray(nCharges),
            driftDy = DoubleArray(nCharges),
            tracerX = DoubleArray(nTracers),
            tracerY = DoubleArray(nTracers),
            tracerAge = DoubleArray(nTracers),
            tracerMaxAge = DoubleArray(nTracers),
            nTracers = nTracers,
            lineX = DoubleArray(maxLines * maxLinePoints),
            lineY = DoubleArray(maxLines * maxLinePoints),
            lineCount = IntArray(maxLines),
            nLines = 0,
            maxLinePoints = maxLinePoints,
            potGrid = DoubleArray(0),
            potW = 0, potH = 0,
            paletteLut = IntArray(256),
            eqPixels = null,
            eqBitmap = null,
            step = 0,
            rng = SeededRNG(seed),
            dirty = true
        )
    }

    // ── Charge layout init ──────────────────────────────────────────────────
    private fun initCharges(st: FieldState, layout: String, w: Double, h: Double) {
        val rng = st.rng
        val nCharges = st.nCharges
        val chargeX = st.chargeX
        val chargeY = st.chargeY
        val chargeQ = st.chargeQ
        val cx = w * 0.5
        val cy = h * 0.5
        val margin = min(w, h) * 0.15

        when (layout) {
            "dipole" -> {
                val sep = min(w, h) * 0.3
                val nc = min(2, nCharges)
                chargeX[0] = cx - sep; chargeY[0] = cy; chargeQ[0] = 2.0
                if (nc > 1) {
                    chargeX[1] = cx + sep; chargeY[1] = cy; chargeQ[1] = -2.0
                }
                for (i in nc until nCharges) {
                    chargeX[i] = cx; chargeY[i] = cy; chargeQ[i] = 0.0
                }
            }
            "quadrupole" -> {
                val sep = min(w, h) * 0.22
                val nc = min(4, nCharges)
                val offsets = arrayOf(
                    intArrayOf(-1, -1), intArrayOf(1, -1), intArrayOf(1, 1), intArrayOf(-1, 1)
                )
                val signs = intArrayOf(1, -1, 1, -1)
                for (i in 0 until nc) {
                    chargeX[i] = cx + offsets[i][0] * sep
                    chargeY[i] = cy + offsets[i][1] * sep
                    chargeQ[i] = signs[i] * (1.5 + rng.randomDouble() * 0.5)
                }
                for (i in nc until nCharges) {
                    chargeX[i] = cx; chargeY[i] = cy; chargeQ[i] = 0.0
                }
            }
            "ring" -> {
                val radius = min(w, h) * 0.3
                for (i in 0 until nCharges) {
                    val a = (i.toDouble() / nCharges) * PI * 2.0
                    chargeX[i] = cx + cos(a) * radius
                    chargeY[i] = cy + sin(a) * radius
                    chargeQ[i] = (if (i % 2 == 0) 1.0 else -1.0) * (1.0 + rng.randomDouble())
                }
            }
            else -> {
                // random
                for (i in 0 until nCharges) {
                    chargeX[i] = margin + rng.randomDouble() * (w - 2.0 * margin)
                    chargeY[i] = margin + rng.randomDouble() * (h - 2.0 * margin)
                    chargeQ[i] = (if (i % 2 == 0) 1.0 else -1.0) * (0.5 + rng.randomDouble() * 2.0)
                }
            }
        }

        // Save initial positions and charge magnitudes (used by orbit/oscillate/pulse)
        for (i in 0 until nCharges) {
            st.initX[i] = chargeX[i]
            st.initY[i] = chargeY[i]
            st.initQ[i] = chargeQ[i]
        }

        // Init drift directions
        for (i in 0 until nCharges) {
            val a = rng.randomDouble() * PI * 2.0
            st.driftDx[i] = cos(a)
            st.driftDy[i] = sin(a)
        }
    }

    // ── Field at point ──────────────────────────────────────────────────────
    private fun computeField(
        px: Double, py: Double,
        chargeX: DoubleArray, chargeY: DoubleArray, chargeQ: DoubleArray,
        nCharges: Int, k: Double, out: DoubleArray
    ) {
        var ex = 0.0
        var ey = 0.0
        for (i in 0 until nCharges) {
            if (chargeQ[i] == 0.0) continue
            val dx = px - chargeX[i]
            val dy = py - chargeY[i]
            var distSq = dx * dx + dy * dy
            if (distSq < MIN_DIST_SQ) distSq = MIN_DIST_SQ
            val dist = sqrt(distSq)
            val distCubed = distSq * dist
            val f = k * chargeQ[i] / distCubed
            ex += f * dx
            ey += f * dy
        }
        out[0] = ex
        out[1] = ey
    }

    // ── Potential at point ──────────────────────────────────────────────────
    private fun computePotential(
        px: Double, py: Double,
        chargeX: DoubleArray, chargeY: DoubleArray, chargeQ: DoubleArray,
        nCharges: Int, k: Double
    ): Double {
        var v = 0.0
        for (i in 0 until nCharges) {
            if (chargeQ[i] == 0.0) continue
            val dx = px - chargeX[i]
            val dy = py - chargeY[i]
            var distSq = dx * dx + dy * dy
            if (distSq < MIN_DIST_SQ) distSq = MIN_DIST_SQ
            v += k * chargeQ[i] / sqrt(distSq)
        }
        return v
    }

    // ── Trace field lines ───────────────────────────────────────────────────
    private fun traceFieldLines(
        st: FieldState, w: Double, h: Double,
        tracerCount: Int, traceSteps: Int, fieldStrength: Double
    ) {
        val chargeX = st.chargeX
        val chargeY = st.chargeY
        val chargeQ = st.chargeQ
        val nCharges = st.nCharges
        val lineX = st.lineX
        val lineY = st.lineY
        val lineCount = st.lineCount
        val maxLinePoints = st.maxLinePoints
        val rng = st.rng
        val k = K_COULOMB * fieldStrength
        val step = FIELD_LINE_STEP
        var lineIdx = 0
        val maxLines = lineCount.size

        val posCharges = ArrayList<Int>()
        val negCharges = ArrayList<Int>()
        for (i in 0 until nCharges) {
            if (chargeQ[i] > 0) posCharges.add(i)
            else if (chargeQ[i] < 0) negCharges.add(i)
        }

        val linesPerPos = if (posCharges.size > 0)
            max(4, ((tracerCount * 0.6) / posCharges.size).toInt())
        else 0
        val linesPerNeg = if (negCharges.size > 0)
            max(4, ((tracerCount * 0.4) / negCharges.size).toInt())
        else 0

        val efOut = DoubleArray(2)

        // Trace from positive charges
        var ci = 0
        while (ci < posCharges.size && lineIdx < maxLines) {
            val qi = posCharges[ci]
            val startR = 8.0 + abs(chargeQ[qi]) * 3.0
            var t = 0
            while (t < linesPerPos && lineIdx < maxLines) {
                val angle = (t.toDouble() / linesPerPos) * PI * 2.0 + rng.randomDouble() * 0.1
                var lx = chargeX[qi] + cos(angle) * startR
                var ly = chargeY[qi] + sin(angle) * startR
                val base = lineIdx * maxLinePoints
                var pts = 0

                var s = 0
                while (s < traceSteps && pts < maxLinePoints) {
                    if (lx < 0 || lx > w || ly < 0 || ly > h) break
                    lineX[base + pts] = lx
                    lineY[base + pts] = ly
                    pts++

                    var absorbed = false
                    for (j in 0 until nCharges) {
                        if (chargeQ[j] >= 0) continue
                        val dx = lx - chargeX[j]
                        val dy = ly - chargeY[j]
                        if (dx * dx + dy * dy < ABSORB_DIST_SQ) { absorbed = true; break }
                    }
                    if (absorbed) break

                    computeField(lx, ly, chargeX, chargeY, chargeQ, nCharges, k, efOut)
                    val ex = efOut[0]
                    val ey = efOut[1]
                    val mag = sqrt(ex * ex + ey * ey)
                    if (mag < 1e-10) break
                    lx += (ex / mag) * step
                    ly += (ey / mag) * step
                    s++
                }
                lineCount[lineIdx] = pts
                lineIdx++
                t++
            }
            ci++
        }

        // Trace from negative charges (reverse direction)
        ci = 0
        while (ci < negCharges.size && lineIdx < maxLines) {
            val qi = negCharges[ci]
            val startR = 8.0 + abs(chargeQ[qi]) * 3.0
            var t = 0
            while (t < linesPerNeg && lineIdx < maxLines) {
                val angle = (t.toDouble() / linesPerNeg) * PI * 2.0 + rng.randomDouble() * 0.1
                var lx = chargeX[qi] + cos(angle) * startR
                var ly = chargeY[qi] + sin(angle) * startR
                val base = lineIdx * maxLinePoints
                var pts = 0

                var s = 0
                while (s < traceSteps && pts < maxLinePoints) {
                    if (lx < 0 || lx > w || ly < 0 || ly > h) break
                    lineX[base + pts] = lx
                    lineY[base + pts] = ly
                    pts++

                    var absorbed = false
                    for (j in 0 until nCharges) {
                        if (chargeQ[j] <= 0) continue
                        val dx = lx - chargeX[j]
                        val dy = ly - chargeY[j]
                        if (dx * dx + dy * dy < ABSORB_DIST_SQ) { absorbed = true; break }
                    }
                    if (absorbed) break

                    computeField(lx, ly, chargeX, chargeY, chargeQ, nCharges, k, efOut)
                    val ex = efOut[0]
                    val ey = efOut[1]
                    val mag = sqrt(ex * ex + ey * ey)
                    if (mag < 1e-10) break
                    lx -= (ex / mag) * step
                    ly -= (ey / mag) * step
                    s++
                }
                lineCount[lineIdx] = pts
                lineIdx++
                t++
            }
            ci++
        }

        st.nLines = lineIdx
        st.dirty = false
    }

    // ── Tracer init ─────────────────────────────────────────────────────────
    private fun initTracers(st: FieldState, w: Double, h: Double) {
        val rng = st.rng
        for (i in 0 until st.nTracers) {
            st.tracerX[i] = rng.randomDouble() * w
            st.tracerY[i] = rng.randomDouble() * h
            st.tracerAge[i] = 0.0
            st.tracerMaxAge[i] = 60.0 + rng.randomDouble() * 200.0
        }
    }

    // ── Step tracers ────────────────────────────────────────────────────────
    private fun stepTracers(st: FieldState, w: Double, h: Double, fieldStrength: Double, speed: Double) {
        val rng = st.rng
        val tracerX = st.tracerX
        val tracerY = st.tracerY
        val tracerAge = st.tracerAge
        val tracerMaxAge = st.tracerMaxAge
        val nTracers = st.nTracers
        val chargeX = st.chargeX
        val chargeY = st.chargeY
        val chargeQ = st.chargeQ
        val nCharges = st.nCharges
        val k = K_COULOMB * fieldStrength
        val efOut = DoubleArray(2)

        for (i in 0 until nTracers) {
            tracerAge[i] += 1.0
            if (tracerAge[i] > tracerMaxAge[i] || tracerX[i] < 0 || tracerX[i] > w
                || tracerY[i] < 0 || tracerY[i] > h) {
                tracerX[i] = rng.randomDouble() * w
                tracerY[i] = rng.randomDouble() * h
                tracerAge[i] = 0.0
                tracerMaxAge[i] = 60.0 + rng.randomDouble() * 200.0
                continue
            }

            var absorbed = false
            for (j in 0 until nCharges) {
                if (chargeQ[j] == 0.0) continue
                val dx = tracerX[i] - chargeX[j]
                val dy = tracerY[i] - chargeY[j]
                if (dx * dx + dy * dy < ABSORB_DIST_SQ) { absorbed = true; break }
            }
            if (absorbed) {
                tracerX[i] = rng.randomDouble() * w
                tracerY[i] = rng.randomDouble() * h
                tracerAge[i] = 0.0
                continue
            }

            computeField(tracerX[i], tracerY[i], chargeX, chargeY, chargeQ, nCharges, k, efOut)
            val ex = efOut[0]
            val ey = efOut[1]
            val mag = sqrt(ex * ex + ey * ey)
            if (mag > 1e-10) {
                val s = speed * 2.0
                tracerX[i] += (ex / mag) * s
                tracerY[i] += (ey / mag) * s
            }
        }
    }

    // ── Update charge positions ─────────────────────────────────────────────
    private fun updateCharges(
        st: FieldState, chargeMotion: String, motionSpeed: Double, w: Double, h: Double
    ) {
        val chargeX = st.chargeX
        val chargeY = st.chargeY
        val chargeQ = st.chargeQ
        val initX = st.initX
        val initY = st.initY
        val driftDx = st.driftDx
        val driftDy = st.driftDy
        val nCharges = st.nCharges
        val t = st.step * 0.02 * motionSpeed
        val cx = w * 0.5
        val cy = h * 0.5
        val margin = 30.0

        val initQ = st.initQ

        for (i in 0 until nCharges) {
            if (initQ[i] == 0.0) continue

            when (chargeMotion) {
                "pulse" -> {
                    // Magnitudes breathe between 40% and 100% of original |q|,
                    // staggered per-charge so the array looks alive rather than
                    // synchronously throbbing. Positions stay fixed at init.
                    val phase = t * 2.0 + i * 0.7
                    val env = 0.4 + 0.6 * (0.5 + 0.5 * sin(phase))
                    chargeX[i] = initX[i]
                    chargeY[i] = initY[i]
                    chargeQ[i] = initQ[i] * env
                }
                "orbit" -> {
                    val dx = initX[i] - cx
                    val dy = initY[i] - cy
                    val r = sqrt(dx * dx + dy * dy)
                    val baseAngle = atan2(dy, dx)
                    val orbSpeed = 0.3 * motionSpeed / max(1.0, r * 0.005)
                    val angle = baseAngle + t * orbSpeed * (if (i % 2 == 0) 1.0 else -1.0)
                    chargeX[i] = cx + cos(angle) * r
                    chargeY[i] = cy + sin(angle) * r
                }
                "oscillate" -> {
                    val amp = min(w, h) * 0.08 * motionSpeed
                    val phaseX = t * 1.5 + i * 1.7
                    val phaseY = t * 1.1 + i * 2.3
                    chargeX[i] = initX[i] + sin(phaseX) * amp
                    chargeY[i] = initY[i] + cos(phaseY) * amp
                }
                "drift" -> {
                    val driftSpeed = motionSpeed * 1.5
                    chargeX[i] += driftDx[i] * driftSpeed
                    chargeY[i] += driftDy[i] * driftSpeed
                    if (chargeX[i] < margin || chargeX[i] > w - margin) {
                        driftDx[i] = -driftDx[i]
                        chargeX[i] = max(margin, min(w - margin, chargeX[i]))
                    }
                    if (chargeY[i] < margin || chargeY[i] > h - margin) {
                        driftDy[i] = -driftDy[i]
                        chargeY[i] = max(margin, min(h - margin, chargeY[i]))
                    }
                }
                // static: no-op
            }
        }
        if (chargeMotion != "static") st.dirty = true
    }

    // ── Build palette LUT (256 entries, ARGB ints) ──────────────────────────
    private fun buildPaletteLut(colors: Array<IntArray>, lut: IntArray) {
        val nc = colors.size
        for (i in 0 until 256) {
            val tv = i / 255f
            val s = tv * (nc - 1)
            val i0 = s.toInt().coerceAtMost(nc - 1)
            val i1 = min(nc - 1, i0 + 1)
            val f = s - i0
            val r = (colors[i0][0] + (colors[i1][0] - colors[i0][0]) * f).toInt()
            val g = (colors[i0][1] + (colors[i1][1] - colors[i0][1]) * f).toInt()
            val b = (colors[i0][2] + (colors[i1][2] - colors[i0][2]) * f).toInt()
            lut[i] = Color.argb(255, r.coerceIn(0, 255), g.coerceIn(0, 255), b.coerceIn(0, 255))
        }
    }

    // ── Compute equipotential normalization range ───────────────────────────
    // Used by the GPU equipotential path: scans a coarse grid to find absMax
    // of V across the canvas, so the shader can normalise V into [0,1] using
    // the same convention as the CPU `computePotentialGrid` (V is symmetric
    // around 0 if charges sum near zero, hence absMax * 2 = full range).
    private fun computePotentialAbsMax(st: FieldState, w: Double, h: Double, fieldStrength: Double): Double {
        val gridScale = 8.0
        val gw = (w / gridScale).toInt().coerceAtLeast(8)
        val gh = (h / gridScale).toInt().coerceAtLeast(8)
        val chargeX = st.chargeX
        val chargeY = st.chargeY
        val chargeQ = st.chargeQ
        val nCharges = st.nCharges
        val k = K_COULOMB * fieldStrength
        var vMin = Double.POSITIVE_INFINITY
        var vMax = Double.NEGATIVE_INFINITY
        for (gy in 0 until gh) {
            val py = (gy + 0.5) * gridScale
            for (gx in 0 until gw) {
                val px = (gx + 0.5) * gridScale
                val v = computePotential(px, py, chargeX, chargeY, chargeQ, nCharges, k)
                if (v < vMin) vMin = v
                if (v > vMax) vMax = v
            }
        }
        val absMax = max(abs(vMin), abs(vMax))
        return if (absMax > 1e-10) absMax else 1.0
    }

    // ── Compute equipotential grid ──────────────────────────────────────────
    private fun computePotentialGrid(st: FieldState, w: Double, h: Double, fieldStrength: Double) {
        val gridScale = 4.0
        val gw = (w / gridScale).toInt()
        val gh = (h / gridScale).toInt()

        if (st.potW != gw || st.potH != gh) {
            st.potGrid = DoubleArray(gw * gh)
            st.potW = gw
            st.potH = gh
        }

        val chargeX = st.chargeX
        val chargeY = st.chargeY
        val chargeQ = st.chargeQ
        val nCharges = st.nCharges
        val potGrid = st.potGrid
        val k = K_COULOMB * fieldStrength
        var vMin = Double.POSITIVE_INFINITY
        var vMax = Double.NEGATIVE_INFINITY

        for (gy in 0 until gh) {
            val py = (gy + 0.5) * gridScale
            for (gx in 0 until gw) {
                val px = (gx + 0.5) * gridScale
                val v = computePotential(px, py, chargeX, chargeY, chargeQ, nCharges, k)
                potGrid[gy * gw + gx] = v
                if (v < vMin) vMin = v
                if (v > vMax) vMax = v
            }
        }

        val absMax = max(abs(vMin), abs(vMax))
        val range = absMax * 2.0
        if (range > 1e-10) {
            val invRange = 1.0 / range
            val n = gw * gh
            for (i in 0 until n) {
                potGrid[i] = max(0.0, min(1.0, (potGrid[i] + absMax) * invRange))
            }
        }
    }

    // ── Render equipotential ────────────────────────────────────────────────
    private fun renderEquipotential(canvas: Canvas, st: FieldState, w: Double, h: Double) {
        val potGrid = st.potGrid
        val potW = st.potW
        val potH = st.potH
        val paletteLut = st.paletteLut
        if (potW == 0 || potH == 0) return

        val needed = potW * potH
        var pixels = st.eqPixels
        if (pixels == null || pixels.size != needed) {
            pixels = IntArray(needed)
            st.eqPixels = pixels
        }
        var small = st.eqBitmap
        if (small == null || small.width != potW || small.height != potH) {
            small?.recycle()
            small = Bitmap.createBitmap(potW, potH, Bitmap.Config.ARGB_8888)
            st.eqBitmap = small
        }

        for (i in 0 until needed) {
            var lutIdx = (potGrid[i] * 255.0 + 0.5).toInt()
            if (lutIdx < 0) lutIdx = 0 else if (lutIdx > 255) lutIdx = 255
            pixels[i] = paletteLut[lutIdx]
        }

        small.setPixels(pixels, 0, potW, 0, 0, potW, potH)
        val filterPaint = Paint().apply { isFilterBitmap = true }
        canvas.drawBitmap(
            small,
            Rect(0, 0, potW, potH),
            Rect(0, 0, w.toInt(), h.toInt()),
            filterPaint
        )
    }

    // ── Draw charges ────────────────────────────────────────────────────────
    private fun drawCharges(canvas: Canvas, st: FieldState, colors: Array<IntArray>, scale: Float) {
        val chargeX = st.chargeX
        val chargeY = st.chargeY
        val chargeQ = st.chargeQ
        val nCharges = st.nCharges
        val nc = colors.size

        val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.WHITE
        }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }

        for (i in 0 until nCharges) {
            if (chargeQ[i] == 0.0) continue
            val isPos = chargeQ[i] > 0
            val absQ = abs(chargeQ[i])
            val r = ((6.0 + absQ * 4.0) * scale).toFloat()

            val ci = if (isPos) 0 else nc - 1
            val cr = colors[ci][0]
            val cg = colors[ci][1]
            val cb = colors[ci][2]

            val cxf = chargeX[i].toFloat()
            val cyf = chargeY[i].toFloat()

            // Glow
            glowPaint.color = Color.argb(38, cr, cg, cb) // 0.15 * 255 ≈ 38
            canvas.drawCircle(cxf, cyf, r * 2f, glowPaint)

            // Filled circle
            fillPaint.color = Color.rgb(cr, cg, cb)
            canvas.drawCircle(cxf, cyf, r, fillPaint)

            // Stroke
            strokePaint.strokeWidth = max(1f, 1.5f * scale)
            canvas.drawCircle(cxf, cyf, r, strokePaint)

            // + / - symbol
            textPaint.textSize = max(10f, ((10.0 + absQ * 3.0) * scale).toFloat())
            val symbol = if (isPos) "+" else "\u2212"
            val fm = textPaint.fontMetrics
            val baselineY = cyf - (fm.ascent + fm.descent) / 2f
            canvas.drawText(symbol, cxf, baselineY, textPaint)
        }
    }

    // ── Render field lines ──────────────────────────────────────────────────
    private fun renderFieldLines(
        canvas: Canvas, st: FieldState, colors: Array<IntArray>,
        colorMode: String, fieldStrength: Double, lineWidth: Float, scale: Float
    ) {
        val lineX = st.lineX
        val lineY = st.lineY
        val lineCount = st.lineCount
        val nLines = st.nLines
        val maxLinePoints = st.maxLinePoints
        val chargeX = st.chargeX
        val chargeY = st.chargeY
        val chargeQ = st.chargeQ
        val nCharges = st.nCharges
        val nc = colors.size
        val k = K_COULOMB * fieldStrength

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        val efOut = DoubleArray(2)

        for (li in 0 until nLines) {
            val pts = lineCount[li]
            if (pts < 2) continue
            val base = li * maxLinePoints

            when (colorMode) {
                "palette" -> {
                    val ci = li % nc
                    val cr = colors[ci][0]; val cg = colors[ci][1]; val cb = colors[ci][2]
                    paint.color = Color.argb(179, cr, cg, cb) // 0.7 * 255 ≈ 179
                    paint.strokeWidth = lineWidth * scale
                    val path = Path()
                    path.moveTo(lineX[base].toFloat(), lineY[base].toFloat())
                    for (p in 1 until pts) {
                        path.lineTo(lineX[base + p].toFloat(), lineY[base + p].toFloat())
                    }
                    canvas.drawPath(path, paint)
                }
                "polarity" -> {
                    val sx = lineX[base]
                    val sy = lineY[base]
                    var nearestQ = 0.0
                    var nearestDist = Double.POSITIVE_INFINITY
                    for (j in 0 until nCharges) {
                        val dx = sx - chargeX[j]
                        val dy = sy - chargeY[j]
                        val d = dx * dx + dy * dy
                        if (d < nearestDist) { nearestDist = d; nearestQ = chargeQ[j] }
                    }
                    // Math.random() jitter — non-seeded per spec
                    val ci = if (nearestQ > 0)
                        floor(Math.random() * ceil(nc / 2.0)).toInt()
                    else
                        (floor(nc / 2.0) + floor(Math.random() * floor(nc / 2.0))).toInt()
                    val ciClamped = ci.coerceIn(0, nc - 1)
                    val cr = colors[ciClamped][0]; val cg = colors[ciClamped][1]; val cb = colors[ciClamped][2]
                    paint.color = Color.argb(179, cr, cg, cb)
                    paint.strokeWidth = lineWidth * scale
                    val path = Path()
                    path.moveTo(lineX[base].toFloat(), lineY[base].toFloat())
                    for (p in 1 until pts) {
                        path.lineTo(lineX[base + p].toFloat(), lineY[base + p].toFloat())
                    }
                    canvas.drawPath(path, paint)
                }
                "potential", "direction" -> {
                    paint.strokeWidth = lineWidth * scale
                    for (p in 0 until pts - 1) {
                        val px = lineX[base + p]
                        val py = lineY[base + p]
                        val tv: Double = if (colorMode == "potential") {
                            val v = computePotential(px, py, chargeX, chargeY, chargeQ, nCharges, k)
                            (atan(v * 0.01) / PI) + 0.5
                        } else {
                            computeField(px, py, chargeX, chargeY, chargeQ, nCharges, k, efOut)
                            (atan2(efOut[1], efOut[0]) / (PI * 2.0)) + 0.5
                        }
                        val ci = ((tv * (nc - 1) + 0.5).toInt()).coerceIn(0, nc - 1)
                        val cr = colors[ci][0]; val cg = colors[ci][1]; val cb = colors[ci][2]
                        paint.color = Color.argb(179, cr, cg, cb)
                        canvas.drawLine(
                            px.toFloat(), py.toFloat(),
                            lineX[base + p + 1].toFloat(), lineY[base + p + 1].toFloat(),
                            paint
                        )
                    }
                }
            }
        }
    }

    // ── Render tracers ──────────────────────────────────────────────────────
    private fun renderTracers(
        canvas: Canvas, st: FieldState, colors: Array<IntArray>,
        colorMode: String, fieldStrength: Double, scale: Float,
        w: Double, h: Double
    ) {
        val tracerX = st.tracerX
        val tracerY = st.tracerY
        val tracerAge = st.tracerAge
        val tracerMaxAge = st.tracerMaxAge
        val nTracers = st.nTracers
        val chargeX = st.chargeX
        val chargeY = st.chargeY
        val chargeQ = st.chargeQ
        val nCharges = st.nCharges
        val nc = colors.size
        val k = K_COULOMB * fieldStrength
        val r = max(1f, 2f * scale)

        val buckets = Array(nc) { ArrayList<Int>() }
        val efOut = DoubleArray(2)

        for (i in 0 until nTracers) {
            val px = tracerX[i]
            val py = tracerY[i]
            if (px < 0 || px > w || py < 0 || py > h) continue

            val ci: Int = when (colorMode) {
                "palette" -> i % nc
                "polarity" -> {
                    var nearestQ = 0.0
                    var nearestDist = Double.POSITIVE_INFINITY
                    for (j in 0 until nCharges) {
                        val dx = px - chargeX[j]
                        val dy = py - chargeY[j]
                        val d = dx * dx + dy * dy
                        if (d < nearestDist) { nearestDist = d; nearestQ = chargeQ[j] }
                    }
                    if (nearestQ > 0) 0 else nc - 1
                }
                "potential" -> {
                    val v = computePotential(px, py, chargeX, chargeY, chargeQ, nCharges, k)
                    val tv = (atan(v * 0.01) / PI) + 0.5
                    ((tv * (nc - 1) + 0.5).toInt()).coerceIn(0, nc - 1)
                }
                else -> {
                    // direction
                    computeField(px, py, chargeX, chargeY, chargeQ, nCharges, k, efOut)
                    val tv = (atan2(efOut[1], efOut[0]) / (PI * 2.0)) + 0.5
                    ((tv * (nc - 1) + 0.5).toInt()).coerceIn(0, nc - 1)
                }
            }
            buckets[ci].add(i)
        }

        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        for (ci in 0 until nc) {
            val bucket = buckets[ci]
            if (bucket.isEmpty()) continue
            val cr = colors[ci][0]; val cg = colors[ci][1]; val cb = colors[ci][2]
            fillPaint.color = Color.argb(153, cr, cg, cb) // 0.6 * 255 ≈ 153

            for (idx in bucket) {
                val fade = 1.0 - tracerAge[idx] / tracerMaxAge[idx]
                val pr = (r * max(0.3, fade)).toFloat()
                canvas.drawCircle(tracerX[idx].toFloat(), tracerY[idx].toFloat(), pr, fillPaint)
            }
        }
    }

    // ── Main render ─────────────────────────────────────────────────────────
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
        val scale = (bitmap.width / 600f)

        val style = ps(params, "style", "fieldLines")
        val chargeCount = pi(params, "chargeCount", 5).coerceIn(2, 15)
        val chargeLayout = ps(params, "chargeLayout", "random")
        var tracerCount = max(50, pi(params, "tracerCount", 200))
        val traceSteps = max(100, pi(params, "traceSteps", 400))
        var fieldStrength = pf(params, "fieldStrength", 2f).toDouble()
        val chargeMotion = ps(params, "chargeMotion", "static")
        var motionSpeed = pf(params, "motionSpeed", 0.5f).toDouble()
        val colorMode = ps(params, "colorMode", "palette")
        val lineWidth = pf(params, "lineWidth", 1f)
        val spf = max(1, pi(params, "stepsPerFrame", 2))

        // Quality scaling
        val qMul = when (quality) {
            Quality.DRAFT -> 0.5f
            Quality.ULTRA -> 1.5f
            else -> 1f
        }
        // Apply TS-style tracer scaling per quality
        tracerCount = when (quality) {
            Quality.DRAFT -> max(50, (tracerCount * 0.5f).toInt())
            Quality.ULTRA -> min(600, (tracerCount * 1.5f).toInt())
            else -> tracerCount
        }

        // Audio reactivity
        val rx = pf(params, "reactivity", 0f)
        val audio = params["_audioAnalysis"] as? AudioAnalysis
        val audioBass = (audio?.getBass(time) ?: 0f) * rx
        val audioHigh = (audio?.getHigh(time) ?: 0f) * rx
        val audioEnergy = ((audioBass + (audio?.getMid(time) ?: 0f) * rx + audioHigh) / 3f)
        fieldStrength += audioBass * 1.5f
        motionSpeed += audioEnergy * 0.5f

        val maxLines = min(600, tracerCount)
        val maxLinePoints = traceSteps

        // ── State cache key ────────────────────────────────────────────────
        val key = "$seed|$chargeCount|$chargeLayout|$tracerCount|${bitmap.width}|${bitmap.height}"
        var st: FieldState
        synchronized(Companion) {
            val cur = state
            if (cur == null || cur.key != key) {
                val newSt = makeState(
                    chargeCount,
                    if (style == "particles") tracerCount else 0,
                    maxLines,
                    maxLinePoints,
                    seed
                )
                newSt.key = key
                initCharges(newSt, chargeLayout, w, h)

                if (style == "particles") {
                    if (newSt.nTracers != tracerCount) {
                        newSt.tracerX = DoubleArray(tracerCount)
                        newSt.tracerY = DoubleArray(tracerCount)
                        newSt.tracerAge = DoubleArray(tracerCount)
                        newSt.tracerMaxAge = DoubleArray(tracerCount)
                        newSt.nTracers = tracerCount
                    }
                    initTracers(newSt, w, h)
                }
                state = newSt
                st = newSt
            } else {
                st = cur
            }

            // Ensure tracers allocated if mode is particles but state was cached otherwise
            if (style == "particles" && st.nTracers == 0) {
                st.tracerX = DoubleArray(tracerCount)
                st.tracerY = DoubleArray(tracerCount)
                st.tracerAge = DoubleArray(tracerCount)
                st.tracerMaxAge = DoubleArray(tracerCount)
                st.nTracers = tracerCount
                initTracers(st, w, h)
            }
        }

        // Build palette data
        val colorInts = palette.colorInts()
        val colors = Array(colorInts.size) { idx ->
            val c = colorInts[idx]
            intArrayOf(Color.red(c), Color.green(c), Color.blue(c))
        }

        synchronized(Companion) {
            buildPaletteLut(colors, st.paletteLut)

            // ── Warmup ─────────────────────────────────────────────────────
            if (time <= 0f && st.step == 0) {
                if (chargeMotion != "static") {
                    for (f in 0 until 30) {
                        updateCharges(st, chargeMotion, motionSpeed, w, h)
                        if (style == "particles") {
                            stepTracers(st, w, h, fieldStrength, motionSpeed)
                        }
                        st.step++
                    }
                }
                if (st.dirty && (style == "fieldLines" || style == "combined")) {
                    traceFieldLines(st, w, h, tracerCount, traceSteps, fieldStrength)
                }
            }

            // ── Animation steps ────────────────────────────────────────────
            if (time > 0f) {
                for (s in 0 until spf) {
                    updateCharges(st, chargeMotion, motionSpeed, w, h)
                    if (style == "particles") {
                        stepTracers(st, w, h, fieldStrength, motionSpeed)
                    }
                    st.step++
                }
                if (st.dirty && (style == "fieldLines" || style == "combined")) {
                    traceFieldLines(st, w, h, tracerCount, traceSteps, fieldStrength)
                }
            }

            // ── Render ─────────────────────────────────────────────────────
            // Background fill (skipped for combined/equipotential — those overwrite
            // every pixel via the GPU shader anyway).
            if (style == "particles") {
                // Semi-transparent overlay for trail effect (rgba(10,10,10,0.12))
                val overlayPaint = Paint().apply { color = Color.argb(31, 10, 10, 10) }
                canvas.drawRect(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat(), overlayPaint)
            } else if (style != "equipotential" && style != "combined") {
                canvas.drawColor(Color.rgb(10, 10, 10))
            }

            when (style) {
                "equipotential" -> {
                    // Hybrid GPU path: compute potential normalisation range CPU-side
                    // (coarse scan), then render the full-resolution potential field
                    // via a fragment shader, finally overlay charges with Canvas draws.
                    st.potAbsMax = computePotentialAbsMax(st, w, h, fieldStrength)
                    GpuShaderRunner.forCurrentThread().render(
                        generator = this@ElectricFieldGenerator,
                        bitmap = bitmap,
                        params = params,
                        seed = seed,
                        palette = palette,
                        quality = quality,
                        time = time
                    )
                    drawCharges(canvas, st, colors, scale)
                }
                "particles" -> {
                    renderTracers(canvas, st, colors, colorMode, fieldStrength, scale, w, h)
                    drawCharges(canvas, st, colors, scale)
                }
                "fieldLines" -> {
                    if (st.dirty || st.nLines == 0) {
                        traceFieldLines(st, w, h, tracerCount, traceSteps, fieldStrength)
                    }
                    renderFieldLines(canvas, st, colors, colorMode, fieldStrength, lineWidth, scale)
                    drawCharges(canvas, st, colors, scale)
                }
                else -> {
                    // combined: equipotential heatmap (GPU) underneath +
                    // field lines + charges on top
                    st.potAbsMax = computePotentialAbsMax(st, w, h, fieldStrength)
                    GpuShaderRunner.forCurrentThread().render(
                        generator = this@ElectricFieldGenerator,
                        bitmap = bitmap,
                        params = params,
                        seed = seed,
                        palette = palette,
                        quality = quality,
                        time = time
                    )
                    if (st.dirty || st.nLines == 0) {
                        traceFieldLines(st, w, h, tracerCount, traceSteps, fieldStrength)
                    }
                    renderFieldLines(canvas, st, colors, colorMode, fieldStrength, lineWidth, scale)
                    drawCharges(canvas, st, colors, scale)
                }
            }
        }

        // qMul is referenced (used for forward-compat scaling); avoid unused-var warnings
        @Suppress("UNUSED_EXPRESSION") qMul
    }

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float {
        val charges = pf(params, "chargeCount", 5f)
        val tracers = pf(params, "tracerCount", 200f)
        val steps = pf(params, "traceSteps", 400f)
        return (charges * tracers * steps * 0.0001f / 1000f).coerceIn(0.1f, 1f)
    }

    // ── GPU equipotential fragment shader ───────────────────────────────────
    // Hybrid pattern: only the "equipotential" style routes through the GPU
    // (everything else stays on CPU). `bindUniforms` reads from the cached
    // FieldState in the companion which `renderCanvas` has already populated
    // and animated this frame; the shader evaluates V(p) per fragment.

    override fun fragmentShaderSource(): String = """#version 300 es
        precision highp float;

        uniform vec2 uResolution;
        uniform float uTime;
        uniform vec3 uAudio;
        ${PaletteUniform.GLSL_HELPERS}

        uniform int   uChargeCount;
        // xyzw = (x, y, q, _unused). Up to MAX_GPU_CHARGES = 16.
        uniform vec4  uCharges[16];
        uniform float uK;              // K_COULOMB * fieldStrength
        uniform float uAbsMax;         // normalisation: V mapped to [-absMax, +absMax]
        uniform float uMinDistSq;      // singularity clamp (matches CPU MIN_DIST_SQ)

        out vec4 fragColor;

        void main() {
            // gl_FragCoord origin is bottom-left in default GL; the runner uses
            // a Y-flipped vertex shader so gl_FragCoord matches bitmap (top-left)
            // orientation. Charge positions are in bitmap pixel space.
            vec2 p = gl_FragCoord.xy;

            float v = 0.0;
            for (int i = 0; i < 16; i++) {
                if (i >= uChargeCount) break;
                vec4 c = uCharges[i];
                if (c.z == 0.0) continue;
                vec2 d = p - c.xy;
                float distSq = dot(d, d);
                if (distSq < uMinDistSq) distSq = uMinDistSq;
                v += uK * c.z / sqrt(distSq);
            }

            float t = clamp((v + uAbsMax) / (2.0 * uAbsMax), 0.0, 1.0);
            vec3 col = palette_color(t);
            fragColor = vec4(col, 1.0);
        }
    """

    override fun bindUniforms(
        programId: Int,
        params: Map<String, Any>,
        seed: Int,
        palette: Palette,
        quality: Quality,
        time: Float,
        width: Int,
        height: Int
    ) {
        val st = state ?: return
        var fieldStrength = pf(params, "fieldStrength", 2f).toDouble()
        // Match the audio-bass boost applied in the CPU branch so values agree
        val rx = pf(params, "reactivity", 0f)
        val audio = params["_audioAnalysis"] as? AudioAnalysis
        val audioBass = (audio?.getBass(time) ?: 0f) * rx
        fieldStrength += audioBass * 1.5f
        val k = K_COULOMB * fieldStrength

        val nCharges = st.nCharges.coerceAtMost(MAX_GPU_CHARGES)
        val packed = FloatArray(MAX_GPU_CHARGES * 4)
        for (i in 0 until nCharges) {
            packed[i * 4]     = st.chargeX[i].toFloat()
            packed[i * 4 + 1] = st.chargeY[i].toFloat()
            packed[i * 4 + 2] = st.chargeQ[i].toFloat()
            packed[i * 4 + 3] = 0f
        }

        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uChargeCount"), nCharges)
        GLES30.glUniform4fv(GLES30.glGetUniformLocation(programId, "uCharges"),
            MAX_GPU_CHARGES, packed, 0)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uK"), k.toFloat())
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uAbsMax"), st.potAbsMax.toFloat())
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uMinDistSq"), MIN_DIST_SQ.toFloat())
    }
}
