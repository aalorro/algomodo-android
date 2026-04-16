package com.artmondo.algomodo.generators.flux

import android.graphics.*
import com.artmondo.algomodo.audio.AudioAnalysis
import com.artmondo.algomodo.core.rng.SeededRNG
import com.artmondo.algomodo.core.rng.SimplexNoise
import com.artmondo.algomodo.data.palettes.Palette
import com.artmondo.algomodo.generators.Generator
import com.artmondo.algomodo.generators.ParamGroup
import com.artmondo.algomodo.generators.Parameter
import com.artmondo.algomodo.generators.Quality
import kotlin.math.*

/**
 * Emitters spawn particles that leave persistent, fading trails with controllable
 * curvature, thickness, and color mapping along lifetime.
 *
 * SoA (Structure of Arrays) layout with FloatArrays for cache locality. Cached
 * SimplexNoise + palette across frames. Trails drawn as 2-segment polylines
 * (head half bright+thick, tail half dim+thin). Audio: bass -> speed + trail width
 * pulse, mid -> curvature, high -> brightness flash, energy -> spawn burst. Quality
 * scaling: draft 0.4x, balanced 0.7x particles. Speed scaled by canvas size.
 *
 * Symmetry modes mirror the trail rendering via canvas transforms (2-way: horizontal
 * mirror, 4-way: quad mirror). Three color modes map palette via lifetime gradient,
 * velocity magnitude, or per-emitter index.
 */
class FluxTrailSystemGenerator : Generator {

    override val id = "flux-trail-system"
    override val family = "flux"
    override val styleName = "Trail System"
    override val definition =
        "Emitters spawn particles that leave persistent, fading trails with controllable " +
        "curvature, thickness, and color mapping along lifetime"
    override val algorithmNotes =
        "SoA layout with FloatArrays. Cached SimplexNoise + palette across frames. " +
        "Trails drawn as 2-segment polylines (head half bright+thick, tail half dim+thin). " +
        "Audio: bass->speed+trailWidth pulse, mid->curvature, high->brightness flash, " +
        "energy->spawn burst. Quality scaling: draft 0.4x, balanced 0.7x particles. " +
        "Speed scaled by canvas size. Symmetry via canvas transforms."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema = listOf(
        Parameter.NumberParam(
            name = "Emitters",
            key = "emitterCount",
            group = ParamGroup.COMPOSITION,
            help = "Number of particle emitters orbiting the canvas",
            min = 1f, max = 8f, step = 1f, default = 3f
        ),
        Parameter.NumberParam(
            name = "Particles / Emitter",
            key = "particlesPerEmitter",
            group = ParamGroup.COMPOSITION,
            help = "Particles spawned per emitter -- more = denser trails",
            min = 20f, max = 200f, step = 10f, default = 80f
        ),
        Parameter.NumberParam(
            name = "Trail Length",
            key = "trailLength",
            group = ParamGroup.TEXTURE,
            help = "Number of positions remembered per particle for trail drawing",
            min = 20f, max = 100f, step = 5f, default = 50f
        ),
        Parameter.NumberParam(
            name = "Trail Width",
            key = "trailWidth",
            group = ParamGroup.TEXTURE,
            help = "Stroke width of trail polylines",
            min = 0.5f, max = 5f, step = 0.1f, default = 2.0f
        ),
        Parameter.NumberParam(
            name = "Curvature",
            key = "curvature",
            group = ParamGroup.GEOMETRY,
            help = "How strongly noise-driven angular rotation is applied to velocity",
            min = 0f, max = 2f, step = 0.1f, default = 0.8f
        ),
        Parameter.SelectParam(
            name = "Symmetry",
            key = "symmetry",
            group = ParamGroup.COMPOSITION,
            help = "none: freeform | 2-way: horizontal mirror | 4-way: quad mirror",
            options = listOf("none", "2-way", "4-way"),
            default = "none"
        ),
        Parameter.SelectParam(
            name = "Color Mode",
            key = "colorMode",
            group = ParamGroup.COLOR,
            help = "lifetime: gradient along life | speed: velocity magnitude | emitter: per-emitter color",
            options = listOf("lifetime", "speed", "emitter"),
            default = "lifetime"
        ),
        Parameter.NumberParam(
            name = "Decay",
            key = "decay",
            group = ParamGroup.TEXTURE,
            help = "Trail persistence -- higher = longer-lasting trails",
            min = 0.9f, max = 0.99f, step = 0.01f, default = 0.95f
        ),
        Parameter.NumberParam(
            name = "Speed",
            key = "speed",
            group = ParamGroup.FLOW_MOTION,
            help = "Particle velocity through the flow field",
            min = 0.1f, max = 3f, step = 0.05f, default = 0.7f
        ),
        Parameter.NumberParam(
            name = "Audio Reactivity",
            key = "reactivity",
            group = ParamGroup.FLOW_MOTION,
            help = "Sensitivity to audio input (0 = none)",
            min = 0f, max = 2f, step = 0.1f, default = 1.0f
        )
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "emitterCount" to 3f,
        "particlesPerEmitter" to 80f,
        "trailLength" to 50f,
        "trailWidth" to 2.0f,
        "curvature" to 0.8f,
        "symmetry" to "none",
        "colorMode" to "lifetime",
        "decay" to 0.95f,
        "speed" to 0.7f,
        "reactivity" to 1.0f
    )

    // ── Cached state across frames ──────────────────────────────────────────
    private data class TrailState(
        val seed: Int,
        val w: Int,
        val h: Int,
        val emitterCount: Int,
        val scaledPPE: Int,
        val totalParticles: Int,
        val trailLen: Int,
        // SoA particle arrays
        val px: FloatArray,
        val py: FloatArray,
        val vx: FloatArray,
        val vy: FloatArray,
        val age: FloatArray,
        val emitterIdx: IntArray,
        // Emitter base positions (seeded)
        val emitterX: FloatArray,
        val emitterY: FloatArray,
        // Trail ring buffer: flat [particle * trailLen + offset]
        val trailX: FloatArray,
        val trailY: FloatArray,
        val trailHead: IntArray,
        val trailCount: IntArray,
        // Noise
        val simplex: SimplexNoise,
        // Retained bitmap for trail accumulation
        var retainedBitmap: Bitmap,
        var warmupDone: Boolean,
        var frameIdx: Int
    )

    // Dimension-keyed state cache — concurrent preview + export renders at different
    // sizes can coexist with separate states instead of racing on a single shared state.
    // Evicted entries are not eagerly recycled — letting GC reclaim the bitmaps
    // avoids "recycled bitmap" crashes when a thread still holds a reference.
    private val stateLock = Any()
    private val stateCache = object : LinkedHashMap<String, TrailState>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, TrailState>?): Boolean =
            size > MAX_STATE_ENTRIES
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
        val minDim = min(w, h)

        // ── Parameters ──────────────────────────────────────────────────────
        val emitterCount = ((params["emitterCount"] as? Number)?.toInt() ?: 3).coerceIn(1, 8)
        val particlesPerEmitter = ((params["particlesPerEmitter"] as? Number)?.toInt() ?: 80).coerceAtLeast(1)
        val trailLen = ((params["trailLength"] as? Number)?.toInt() ?: 50).coerceAtLeast(2)
        val colorMode = (params["colorMode"] as? String) ?: "lifetime"
        val symmetry = (params["symmetry"] as? String) ?: "none"
        val symId = when (symmetry) {
            "4-way" -> 4
            "2-way" -> 2
            else -> 1
        }
        val decay = (params["decay"] as? Number)?.toFloat() ?: 0.95f
        val rx = (params["reactivity"] as? Number)?.toFloat() ?: 1.0f

        // ── Audio reactivity ────────────────────────────────────────────────
        val audioAnalysis = params["_audioAnalysis"] as? AudioAnalysis
        val audioBass = (audioAnalysis?.getBass(time) ?: 0f) * rx
        val audioMid = (audioAnalysis?.getMid(time) ?: 0f) * rx
        val audioHigh = (audioAnalysis?.getHigh(time) ?: 0f) * rx
        val audioEnergy = ((audioBass + audioMid + audioHigh) / 3f) * rx

        // Bass -> speed burst + trail width pulse
        val speedParam = ((params["speed"] as? Number)?.toFloat() ?: 0.7f) * (1f + audioBass * 2.0f)
        // Mid -> curvature increase
        val curvatureParam = ((params["curvature"] as? Number)?.toFloat() ?: 0.8f) * (1f + audioMid * 1.5f)
        // Bass -> trail width pulse
        val trailWidth = ((params["trailWidth"] as? Number)?.toFloat() ?: 2.0f) * (1f + audioBass * 0.8f)

        val speedPx = speedParam * minDim * 0.004f
        val ageRate = 0.005f * (1f + audioEnergy * 0.5f)

        // Quality scaling
        val qMul = when (quality) {
            Quality.DRAFT -> 0.4f
            Quality.BALANCED -> 0.7f
            Quality.ULTRA -> 1.0f
        }
        val scaledPPE = max(1, (particlesPerEmitter * qMul).roundToInt())
        val totalParticles = emitterCount * scaledPPE

        // ── Noise/curvature setup ───────────────────────────────────────────
        val noiseScale = 3.0f / minDim
        val blend = min(1f, curvatureParam * 0.5f)
        val invBlend = 1f - blend
        val halfW = w * 0.5f

        // ── State cache — keyed by seed + dimensions + emitterCount + scaledPPE + trailLen ──
        val stateKey = "$seed:$w:$h:$emitterCount:$scaledPPE:$trailLen"
        var st: TrailState? = synchronized(stateLock) { stateCache[stateKey] }
        if (st == null) {
            val rng = SeededRNG(seed)
            val simplex = SimplexNoise(seed)

            val emitterX = FloatArray(emitterCount)
            val emitterY = FloatArray(emitterCount)
            for (e in 0 until emitterCount) {
                emitterX[e] = w * (0.15f + rng.random() * 0.7f)
                emitterY[e] = h * (0.15f + rng.random() * 0.7f)
            }

            val px = FloatArray(totalParticles)
            val py = FloatArray(totalParticles)
            val vx = FloatArray(totalParticles)
            val vy = FloatArray(totalParticles)
            val age = FloatArray(totalParticles)
            val emIdx = IntArray(totalParticles)

            val trailTotal = totalParticles * trailLen
            val trailX = FloatArray(trailTotal)
            val trailY = FloatArray(trailTotal)
            val trailHead = IntArray(totalParticles)
            val trailCount = IntArray(totalParticles)

            for (e in 0 until emitterCount) {
                for (p in 0 until scaledPPE) {
                    val i = e * scaledPPE + p
                    emIdx[i] = e
                    px[i] = emitterX[e] + (rng.random() - 0.5f) * 20f
                    py[i] = emitterY[e] + (rng.random() - 0.5f) * 20f
                    age[i] = rng.random()

                    val angle = rng.random() * TAU
                    val spd = 0.5f + rng.random() * 1.5f
                    vx[i] = cos(angle) * spd
                    vy[i] = sin(angle) * spd

                    val tBase = i * trailLen
                    trailX[tBase] = px[i]
                    trailY[tBase] = py[i]
                    trailHead[i] = 0
                    trailCount[i] = 1
                }
            }

            val retained = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)

            st = TrailState(
                seed = seed,
                w = w,
                h = h,
                emitterCount = emitterCount,
                scaledPPE = scaledPPE,
                totalParticles = totalParticles,
                trailLen = trailLen,
                px = px, py = py,
                vx = vx, vy = vy,
                age = age,
                emitterIdx = emIdx,
                emitterX = emitterX,
                emitterY = emitterY,
                trailX = trailX,
                trailY = trailY,
                trailHead = trailHead,
                trailCount = trailCount,
                simplex = simplex,
                retainedBitmap = retained,
                warmupDone = false,
                frameIdx = 0
            )
            synchronized(stateLock) { stateCache[stateKey] = st }
        }

        val n = st.totalParticles
        val spx = st.px
        val spy = st.py
        val svx = st.vx
        val svy = st.vy
        val sAge = st.age
        val sEmitterIdx = st.emitterIdx
        val sEmitterX = st.emitterX
        val sEmitterY = st.emitterY
        val sTX = st.trailX
        val sTY = st.trailY
        val sTH = st.trailHead
        val sTC = st.trailCount
        val tLen = st.trailLen
        val noise = st.simplex

        // ── Background color (darkened first palette color) ─────────────────
        val bgColor = darkenBgColor(palette)
        val bgR = Color.red(bgColor)
        val bgG = Color.green(bgColor)
        val bgB = Color.blue(bgColor)
        val decayAlpha = ((1f - decay) * 255f).roundToInt().coerceIn(1, 255)

        val fadePaint = Paint().apply {
            color = Color.argb(decayAlpha, bgR, bgG, bgB)
            style = Paint.Style.FILL
        }
        val solidBgPaint = Paint().apply {
            color = bgColor
            style = Paint.Style.FILL
        }

        // ── Palette colors ──────────────────────────────────────────────────
        val colorInts = palette.colorInts()
        val nColors = colorInts.size
        val nCm1 = nColors - 1

        // Pre-extract RGB components for fast interpolation
        val palR = IntArray(nColors) { Color.red(colorInts[it]) }
        val palG = IntArray(nColors) { Color.green(colorInts[it]) }
        val palB = IntArray(nColors) { Color.blue(colorInts[it]) }

        // ── Step function: advance all particles one frame ──────────────────
        fun stepOnce() {
            val rng = SeededRNG(seed + st.frameIdx * 7 + 1)
            st.frameIdx++

            for (i in 0 until n) {
                val nx = spx[i] * noiseScale
                val ny = spy[i] * noiseScale
                val curlAngle = noise.noise2D(nx, ny) * PI.toFloat() * curvatureParam

                val cs = cos(curlAngle)
                val sn = sin(curlAngle)
                val bvx = svx[i]
                val bvy = svy[i]
                svx[i] = bvx * invBlend + (bvx * cs - bvy * sn) * blend
                svy[i] = bvy * invBlend + (bvx * sn + bvy * cs) * blend

                val vMag = sqrt(svx[i] * svx[i] + svy[i] * svy[i])
                if (vMag > 0.001f) {
                    val s = speedPx / vMag
                    svx[i] *= s
                    svy[i] *= s
                }

                spx[i] += svx[i]
                spy[i] += svy[i]
                sAge[i] += ageRate

                // Respawn if age >= 1
                if (sAge[i] >= 1f) {
                    val eIdx = sEmitterIdx[i]
                    spx[i] = sEmitterX[eIdx] + (rng.random() - 0.5f) * 20f
                    spy[i] = sEmitterY[eIdx] + (rng.random() - 0.5f) * 20f
                    sAge[i] = 0f
                    val angle = rng.random() * TAU
                    val spd = 0.5f + rng.random() * 1.5f
                    svx[i] = cos(angle) * spd
                    svy[i] = sin(angle) * spd
                    val tBase = i * tLen
                    sTX[tBase] = spx[i]
                    sTY[tBase] = spy[i]
                    sTH[i] = 0
                    sTC[i] = 1
                    continue
                }

                // Wrap at boundaries
                if (spx[i] < 0f) spx[i] += w
                else if (spx[i] >= w) spx[i] -= w
                if (spy[i] < 0f) spy[i] += h
                else if (spy[i] >= h) spy[i] -= h

                // Append to trail ring buffer
                val tBase = i * tLen
                val newHead = (sTH[i] + 1) % tLen
                sTX[tBase + newHead] = spx[i]
                sTY[tBase + newHead] = spy[i]
                sTH[i] = newHead
                if (sTC[i] < tLen) sTC[i]++
            }
        }

        // ── Draw trails for one pass (no symmetry transform) ────────────────
        fun drawTrailsOnce(rc: Canvas) {
            val headAlpha = (0.75f + audioHigh * 0.25f).coerceIn(0f, 1f)
            val tailAlpha = 0.25f
            val headWidth = trailWidth
            val tailWidth = trailWidth * 0.35f

            val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }

            // Two segments: 0=head (bright+thick), 1=tail (dim+thin)
            for (seg in 0..1) {
                val alpha = if (seg == 0) headAlpha else tailAlpha
                val lw = if (seg == 0) headWidth else tailWidth
                if (lw < 0.1f) continue

                trailPaint.strokeWidth = lw
                val alphaInt = (alpha * 255f).roundToInt().coerceIn(0, 255)

                for (e in 0 until st.emitterCount) {
                    val emColorIdx = e % nColors
                    val emR = palR[emColorIdx]
                    val emG = palG[emColorIdx]
                    val emB = palB[emColorIdx]

                    val startI = e * st.scaledPPE
                    val endI = min(startI + st.scaledPPE, n)

                    for (i in startI until endI) {
                        val tc = sTC[i]
                        if (tc < 4) continue

                        val th = sTH[i]
                        val tBase = i * tLen

                        // Determine color based on mode
                        val cr: Int
                        val cg: Int
                        val cb: Int

                        when (colorMode) {
                            "emitter" -> {
                                cr = emR; cg = emG; cb = emB
                            }
                            "speed" -> {
                                val t = min(1f, sqrt(svx[i] * svx[i] + svy[i] * svy[i]) / (speedPx * 2f))
                                val s = t * nCm1
                                val i0 = s.toInt().coerceAtMost(nCm1)
                                val i1 = min(nCm1, i0 + 1)
                                val f = s - i0
                                cr = (palR[i0] + (palR[i1] - palR[i0]) * f).toInt()
                                cg = (palG[i0] + (palG[i1] - palG[i0]) * f).toInt()
                                cb = (palB[i0] + (palB[i1] - palB[i0]) * f).toInt()
                            }
                            else -> {
                                // lifetime
                                val t = sAge[i]
                                val s = t * nCm1
                                val i0 = s.toInt().coerceAtMost(nCm1)
                                val i1 = min(nCm1, i0 + 1)
                                val f = s - i0
                                cr = (palR[i0] + (palR[i1] - palR[i0]) * f).toInt()
                                cg = (palG[i0] + (palG[i1] - palG[i0]) * f).toInt()
                                cb = (palB[i0] + (palB[i1] - palB[i0]) * f).toInt()
                            }
                        }

                        trailPaint.color = Color.argb(
                            alphaInt,
                            cr.coerceIn(0, 255),
                            cg.coerceIn(0, 255),
                            cb.coerceIn(0, 255)
                        )

                        // Determine segment range (head half vs tail half)
                        val half = tc shr 1
                        val jStart = if (seg == 0) 0 else half
                        val jEnd = if (seg == 0) half else tc - 1
                        if (jEnd - jStart < 1) continue

                        val path = Path()
                        val firstIdx = tBase + ((th - jStart + tLen) % tLen)
                        path.moveTo(sTX[firstIdx], sTY[firstIdx])

                        for (j in jStart + 1..jEnd) {
                            val idx = tBase + ((th - j + tLen) % tLen)
                            val tx = sTX[idx]
                            val ty = sTY[idx]
                            val prevI = tBase + ((th - j + 1 + tLen) % tLen)
                            val dx = tx - sTX[prevI]
                            val dy = ty - sTY[prevI]
                            if (dx * dx + dy * dy > halfW * halfW) {
                                path.moveTo(tx, ty)
                            } else {
                                path.lineTo(tx, ty)
                            }
                        }

                        rc.drawPath(path, trailPaint)
                    }
                }
            }
        }

        // ── Draw trails with symmetry mirrors ───────────────────────────────
        fun drawTrails(rc: Canvas) {
            // Original pass
            drawTrailsOnce(rc)

            if (symId >= 2) {
                // Horizontal mirror: flip x, keep y
                rc.save()
                rc.scale(-1f, 1f, w * 0.5f, 0f)
                drawTrailsOnce(rc)
                rc.restore()
            }

            if (symId >= 4) {
                // Vertical mirror: keep x, flip y
                rc.save()
                rc.scale(1f, -1f, 0f, h * 0.5f)
                drawTrailsOnce(rc)
                rc.restore()

                // Both axes mirror (diagonal)
                rc.save()
                rc.scale(-1f, -1f, w * 0.5f, h * 0.5f)
                drawTrailsOnce(rc)
                rc.restore()
            }
        }

        // ── Retained bitmap canvas for trail accumulation ───────────────────
        val retainedCanvas = Canvas(st.retainedBitmap)

        // ── First render: warmup ────────────────────────────────────────────
        if (!st.warmupDone) {
            retainedCanvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), solidBgPaint)

            for (ww in 0 until 20) {
                retainedCanvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), fadePaint)
                stepOnce()
                drawTrails(retainedCanvas)
            }
            st.warmupDone = true
        } else {
            // Normal frame: decay + step + draw
            retainedCanvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), fadePaint)
            stepOnce()
            drawTrails(retainedCanvas)
        }

        // ── Copy retained bitmap to output canvas ───────────────────────────
        canvas.drawBitmap(st.retainedBitmap, 0f, 0f, null)

        // ── High -> brightness flash overlay ────────────────────────────────
        if (audioHigh > 0.15f) {
            // Use PorterDuff.Mode.ADD for "lighter" composite (matches web globalCompositeOperation = 'lighter')
            val addPaint = Paint().apply {
                color = Color.WHITE
                alpha = (audioHigh * 0.12f * 255f).roundToInt().coerceIn(0, 255)
                xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
            }
            canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), addPaint)
        }
    }

    /**
     * Darken the first palette color by subtracting 60 from each channel (clamped to 0),
     * matching the web version's background computation.
     */
    private fun darkenBgColor(palette: Palette): Int {
        val colorInts = palette.colorInts()
        if (colorInts.isEmpty()) return Color.BLACK
        val bg = colorInts[0]
        val r = max(0, Color.red(bg) - 60)
        val g = max(0, Color.green(bg) - 60)
        val b = max(0, Color.blue(bg) - 60)
        return Color.rgb(r, g, b)
    }

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float {
        val emitters = (params["emitterCount"] as? Number)?.toFloat() ?: 3f
        val ppe = (params["particlesPerEmitter"] as? Number)?.toFloat() ?: 80f
        val trail = (params["trailLength"] as? Number)?.toFloat() ?: 50f
        val base = (emitters * ppe * trail * 0.1f + 100f) / 1000f
        return when (quality) {
            Quality.DRAFT -> base * 0.4f
            Quality.BALANCED -> base * 0.7f
            Quality.ULTRA -> base
        }.coerceIn(0.1f, 1f)
    }

    companion object {
        private const val PI = 3.1415926535897932f
        private const val TAU = 2.0f * PI
        private const val MAX_STATE_ENTRIES = 3
    }
}
