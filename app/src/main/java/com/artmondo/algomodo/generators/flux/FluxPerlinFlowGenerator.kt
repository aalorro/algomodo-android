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
 * Particles trace a 2D noise vector field producing smoky, organic ribbon-like
 * visuals with persistent trail accumulation.
 *
 * Three field types: curl (divergence-free via numerical curl of scalar FBM),
 * gradient (FBM gradient direction), and turbulent (FBM angle * TAU). Angles
 * are cached during the simulation step and reused for velocity color mode,
 * eliminating redundant sampleAngle calls (halves noise calls per frame).
 * Field type changes trigger canvas clear + trail reset for immediate visual
 * transition.
 *
 * Trail accumulation uses a retained bitmap: each frame draws a semi-transparent
 * background overlay (decay) then renders new trail segments on top. The retained
 * bitmap is then copied to the output canvas.
 */
class FluxPerlinFlowGenerator : Generator {

    override val id = "flux-perlin-flow"
    override val family = "flux"
    override val styleName = "Perlin Flow"
    override val definition =
        "Particles trace a 2D noise vector field producing smoky, organic ribbon-like " +
        "visuals with persistent trail accumulation"
    override val algorithmNotes =
        "Particles follow a noise-derived vector field. Three field types: curl (divergence-free " +
        "via numerical curl of scalar FBM), gradient (FBM gradient direction), and turbulent " +
        "(FBM angle x TAU). Angles cached during simulation step and reused for velocity color " +
        "mode -- eliminates redundant sampleAngle calls (halves noise calls per frame). Field " +
        "type changes detected and trigger canvas clear + trail reset. Trail accumulation via " +
        "retained bitmap with semi-transparent decay overlay. State keyed by seed + dimensions; " +
        "quality scales particle count."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema = listOf(
        Parameter.SelectParam(
            name = "Field Type",
            key = "fieldType",
            group = ParamGroup.COMPOSITION,
            help = "curl: divergence-free swirling field | gradient: follows noise gradient | turbulent: FBM-driven chaotic angles",
            options = listOf("curl", "gradient", "turbulent"),
            default = "curl"
        ),
        Parameter.NumberParam(
            name = "Noise Scale",
            key = "noiseScale",
            group = ParamGroup.GEOMETRY,
            help = "Spatial frequency of the noise field -- higher = tighter swirls",
            min = 0.5f, max = 6f, step = 0.1f, default = 2.5f
        ),
        Parameter.NumberParam(
            name = "Octaves",
            key = "octaves",
            group = ParamGroup.COMPOSITION,
            help = "FBM layers -- more octaves = finer detail in all field types",
            min = 1f, max = 4f, step = 1f, default = 2f
        ),
        Parameter.NumberParam(
            name = "Particles",
            key = "particleCount",
            group = ParamGroup.COMPOSITION,
            help = "Number of particles tracing the flow field",
            min = 200f, max = 5000f, step = 100f, default = 1500f
        ),
        Parameter.NumberParam(
            name = "Trail Length",
            key = "trailLength",
            group = ParamGroup.TEXTURE,
            help = "Number of positions remembered per particle for trail drawing",
            min = 5f, max = 60f, step = 1f, default = 20f
        ),
        Parameter.NumberParam(
            name = "Trail Width",
            key = "trailWidth",
            group = ParamGroup.TEXTURE,
            help = "Stroke width of trail polylines",
            min = 0.5f, max = 4f, step = 0.1f, default = 1.5f
        ),
        Parameter.NumberParam(
            name = "Decay",
            key = "decay",
            group = ParamGroup.TEXTURE,
            help = "Trail persistence -- higher = longer-lasting trails",
            min = 0.9f, max = 0.99f, step = 0.01f, default = 0.96f
        ),
        Parameter.SelectParam(
            name = "Color Mode",
            key = "colorMode",
            group = ParamGroup.COLOR,
            help = "velocity: hue from flow angle | age: gradient along trail | palette-cycle: noise-driven color at position",
            options = listOf("velocity", "age", "palette-cycle"),
            default = "velocity"
        ),
        Parameter.NumberParam(
            name = "Speed",
            key = "speed",
            group = ParamGroup.FLOW_MOTION,
            help = "Particle velocity through the flow field",
            min = 0.1f, max = 3f, step = 0.05f, default = 0.8f
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
        "fieldType" to "curl",
        "noiseScale" to 2.5f,
        "octaves" to 2f,
        "particleCount" to 1500f,
        "trailLength" to 20f,
        "trailWidth" to 1.5f,
        "decay" to 0.96f,
        "colorMode" to "velocity",
        "speed" to 0.8f,
        "reactivity" to 1.0f
    )

    // ── Cached state across frames ──────────────────────────────────────────
    private data class FlowState(
        val seed: Int,
        val w: Int,
        val h: Int,
        val particleCountBase: Int,
        val trailLen: Int,
        var fieldType: String,
        val px: FloatArray,
        val py: FloatArray,
        val trailX: FloatArray,      // [n * trailLen] ring buffer
        val trailY: FloatArray,
        val trailHead: IntArray,     // next write index per particle
        val trailCount: IntArray,    // how many trail entries are valid
        val noise: SimplexNoise,
        var warmupDone: Boolean,
        val pCI: IntArray,           // per-particle color index
        val angles: FloatArray,      // cached angles from simulation step
        var retainedBitmap: Bitmap   // accumulated trail bitmap
    )

    @Volatile private var state: FlowState? = null

    // FBM weight cache (max 4 octaves)
    private val fbmAmp = FloatArray(4)
    private val fbmFreq = FloatArray(4)

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

        // ── Parameters ──────────────────────────────────────────────────────
        val fieldType = (params["fieldType"] as? String) ?: "curl"
        var noiseScale = (params["noiseScale"] as? Number)?.toFloat() ?: 2.5f
        val octaves = ((params["octaves"] as? Number)?.toInt() ?: 2).coerceIn(1, 4)
        val particleCountBase = ((params["particleCount"] as? Number)?.toInt() ?: 1500).coerceAtLeast(200)
        val trailLength = ((params["trailLength"] as? Number)?.toInt() ?: 20).coerceIn(5, 60)
        val trailWidth = (params["trailWidth"] as? Number)?.toFloat() ?: 1.5f
        val decay = (params["decay"] as? Number)?.toFloat() ?: 0.96f
        val colorMode = (params["colorMode"] as? String) ?: "velocity"
        var speed = (params["speed"] as? Number)?.toFloat() ?: 0.8f
        val rx = (params["reactivity"] as? Number)?.toFloat() ?: 1.0f

        // Quality scales active particle count
        val qMul = when (quality) {
            Quality.DRAFT -> 0.3f
            Quality.BALANCED -> 0.6f
            Quality.ULTRA -> 1.0f
        }
        val activeN = max(50, (particleCountBase * qMul).roundToInt())

        // ── Audio reactivity ────────────────────────────────────────────────
        val audioAnalysis = params["_audioAnalysis"] as? AudioAnalysis
        val audioBass = (audioAnalysis?.getBass(time) ?: 0f) * rx
        val audioMid = (audioAnalysis?.getMid(time) ?: 0f) * rx
        speed *= (1f + audioBass * 2f)
        noiseScale *= (1f + audioMid * 0.5f)

        // ── State cache — keyed by seed + dimensions + particleCount + trailLength ──
        var st = state
        if (st == null || st.seed != seed || st.w != w || st.h != h ||
            st.particleCountBase != particleCountBase || st.trailLen != trailLength
        ) {
            // Recycle old retained bitmap
            st?.retainedBitmap?.recycle()

            val rng = SeededRNG(seed)
            val noiseSt = SimplexNoise(seed)
            val n = particleCountBase
            val px = FloatArray(n)
            val py = FloatArray(n)
            val trailX = FloatArray(n * trailLength)
            val trailY = FloatArray(n * trailLength)
            val trailHead = IntArray(n)
            val trailCount = IntArray(n)

            for (i in 0 until n) {
                px[i] = rng.random() * w
                py[i] = rng.random() * h
                val base = i * trailLength
                trailX[base] = px[i]
                trailY[base] = py[i]
                trailHead[i] = 1
                trailCount[i] = 1
            }

            val retained = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)

            st = FlowState(
                seed = seed,
                w = w,
                h = h,
                particleCountBase = particleCountBase,
                trailLen = trailLength,
                fieldType = fieldType,
                px = px,
                py = py,
                trailX = trailX,
                trailY = trailY,
                trailHead = trailHead,
                trailCount = trailCount,
                noise = noiseSt,
                warmupDone = false,
                pCI = IntArray(n),
                angles = FloatArray(n),
                retainedBitmap = retained
            )
            state = st
        }

        // ── Detect field type change -> clear canvas + reset trails ──────────
        if (st.warmupDone && st.fieldType != fieldType) {
            st.fieldType = fieldType
            // Clear retained bitmap
            val retainedCanvas = Canvas(st.retainedBitmap)
            val bgColor = darkenBgColor(palette)
            retainedCanvas.drawColor(bgColor)
            // Reset trail buffers -- particles keep positions but trails restart
            for (i in 0 until st.particleCountBase) {
                st.trailCount[i] = 1
                st.trailHead[i] = 1
                val base = i * st.trailLen
                st.trailX[base] = st.px[i]
                st.trailY[base] = st.py[i]
            }
        }

        // ── FBM setup ───────────────────────────────────────────────────────
        val noise = st.noise
        val maxOct = if (quality == Quality.DRAFT) min(octaves, 2) else octaves
        var fbmTotalInv: Float
        run {
            var amp = 1f
            var freq = 1f
            var total = 0f
            for (o in 0 until maxOct) {
                fbmAmp[o] = amp
                fbmFreq[o] = freq
                total += amp
                freq *= 2f
                amp *= 0.5f
            }
            fbmTotalInv = 1f / total
        }

        // ── Field evaluation ────────────────────────────────────────────────
        val eps = 0.01f
        val invW = 1f / w
        val invH = 1f / h
        val scl = w / 600f
        val fieldId = when (fieldType) {
            "curl" -> 0
            "gradient" -> 1
            else -> 2  // turbulent
        }

        // Inline FBM value computation
        fun fbmVal(fx: Float, fy: Float): Float {
            var v = 0f
            for (o in 0 until maxOct) {
                v += noise.noise2D(fx * fbmFreq[o], fy * fbmFreq[o]) * fbmAmp[o]
            }
            return v * fbmTotalInv
        }

        fun sampleAngle(px: Float, py: Float): Float {
            val nx = px * invW * noiseScale
            val ny = py * invH * noiseScale

            return when (fieldId) {
                0 -> {
                    // Curl: divergence-free via numerical curl of scalar FBM
                    val n0 = fbmVal(nx, ny)
                    val dnx = (fbmVal(nx + eps, ny) - n0) / eps
                    val dny = (fbmVal(nx, ny + eps) - n0) / eps
                    atan2(dny, -dnx)
                }
                1 -> {
                    // Gradient: follow FBM gradient direction
                    val gx = fbmVal(nx + eps, ny) - fbmVal(nx - eps, ny)
                    val gy = fbmVal(nx, ny + eps) - fbmVal(nx, ny - eps)
                    atan2(gy, gx)
                }
                else -> {
                    // Turbulent: FBM angle * TAU * 2
                    fbmVal(nx, ny) * TAU * 2f
                }
            }
        }

        // ── Simulation step -- caches angles for velocity color mode ────────
        fun stepN(count: Int) {
            val stPx = st.px
            val stPy = st.py
            val stTrailX = st.trailX
            val stTrailY = st.trailY
            val stTrailHead = st.trailHead
            val stTrailCount = st.trailCount
            val tl = st.trailLen
            val stAngles = st.angles
            val vel = speed * scl

            for (i in 0 until count) {
                val angle = sampleAngle(stPx[i], stPy[i])
                stAngles[i] = angle

                stPx[i] += cos(angle) * vel
                stPy[i] += sin(angle) * vel

                // Wrap at boundaries
                if (stPx[i] < 0f) stPx[i] += w
                else if (stPx[i] >= w) stPx[i] -= w
                if (stPy[i] < 0f) stPy[i] += h
                else if (stPy[i] >= h) stPy[i] -= h

                val base = i * tl
                val head = stTrailHead[i]
                stTrailX[base + head] = stPx[i]
                stTrailY[base + head] = stPy[i]
                stTrailHead[i] = (head + 1) % tl
                if (stTrailCount[i] < tl) stTrailCount[i]++
            }
        }

        // ── Retained bitmap canvas for trail accumulation ───────────────────
        val retainedCanvas = Canvas(st.retainedBitmap)

        // ── Warmup ──────────────────────────────────────────────────────────
        if (!st.warmupDone) {
            st.warmupDone = true
            st.fieldType = fieldType

            val bgColor = darkenBgColor(palette)
            retainedCanvas.drawColor(bgColor)

            // Run 20 warmup frames to develop flow patterns
            for (f in 0 until 20) {
                stepN(st.particleCountBase)
            }
        }

        // ── Background fade (decay) on retained bitmap ──────────────────────
        val bgRgb = darkenBgColor(palette)
        val bgR = Color.red(bgRgb)
        val bgG = Color.green(bgRgb)
        val bgB = Color.blue(bgRgb)
        val decayAlpha = ((1f - decay) * 255f).roundToInt().coerceIn(1, 255)
        val fadePaint = Paint().apply {
            color = Color.argb(decayAlpha, bgR, bgG, bgB)
            style = Paint.Style.FILL
        }
        retainedCanvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), fadePaint)

        // ── Advance simulation ──────────────────────────────────────────────
        stepN(activeN)

        // ── Palette colors ──────────────────────────────────────────────────
        val colorInts = palette.colorInts()
        val nColors = colorInts.size
        val nColorsM1 = nColors - 1

        // ── Pre-build color+alpha variants ──────────────────────────────────
        val ALPHA_BUCKETS = 3
        val alphaColors = Array(nColors) { ci ->
            val r = Color.red(colorInts[ci])
            val g = Color.green(colorInts[ci])
            val b = Color.blue(colorInts[ci])
            IntArray(ALPHA_BUCKETS) { ab ->
                val alpha = (0.12f + 0.68f * (ab.toFloat() / (ALPHA_BUCKETS - 1))) * 255f
                Color.argb(alpha.roundToInt().coerceIn(0, 255), r, g, b)
            }
        }

        // ── Draw trails on retained bitmap ──────────────────────────────────
        val stTrailX = st.trailX
        val stTrailY = st.trailY
        val stTrailHead = st.trailHead
        val stTrailCount = st.trailCount
        val tl = st.trailLen
        val stPCI = st.pCI
        val stAngles = st.angles
        val wrapThreshX = (w * 0.5f) * (w * 0.5f)
        val wrapThreshY = (h * 0.5f) * (h * 0.5f)

        val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = trailWidth * scl
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        when (colorMode) {
            "velocity" -> {
                // Use cached angles from simulation step
                for (i in 0 until activeN) {
                    if (stTrailCount[i] < 2) { stPCI[i] = -1; continue }
                    val normAngle = ((stAngles[i] % TAU) + TAU) % TAU
                    stPCI[i] = ((normAngle / TAU) * nColors).toInt() % nColors
                }

                for (ci in 0 until nColors) {
                    for (ab in 0 until ALPHA_BUCKETS) {
                        trailPaint.color = alphaColors[ci][ab]
                        val path = Path()
                        var hasSegments = false

                        for (i in 0 until activeN) {
                            if (stPCI[i] != ci) continue
                            val count = stTrailCount[i]

                            val segStart = (ab * count / ALPHA_BUCKETS)
                            val segEnd = ((ab + 1) * count / ALPHA_BUCKETS)
                            if (segEnd <= segStart) continue

                            val base = i * tl
                            val head = stTrailHead[i]

                            val startIdx = ((head - count + segStart) % tl + tl) % tl
                            var prevX = stTrailX[base + startIdx]
                            var prevY = stTrailY[base + startIdx]
                            path.moveTo(prevX, prevY)

                            for (j in segStart + 1 until segEnd) {
                                val idx = ((head - count + j) % tl + tl) % tl
                                val tx = stTrailX[base + idx]
                                val ty = stTrailY[base + idx]
                                val dx = tx - prevX
                                val dy = ty - prevY
                                if (dx * dx > wrapThreshX || dy * dy > wrapThreshY) {
                                    path.moveTo(tx, ty)
                                } else {
                                    path.lineTo(tx, ty)
                                }
                                prevX = tx
                                prevY = ty
                            }
                            hasSegments = true
                        }

                        if (hasSegments) {
                            retainedCanvas.drawPath(path, trailPaint)
                        }
                    }
                }
            }

            "age" -> {
                val ALPHA_LAYERS = 3

                for (ci in 0 until nColors) {
                    for (ab in 0 until ALPHA_LAYERS) {
                        trailPaint.color = alphaColors[ci][ab.coerceAtMost(ALPHA_BUCKETS - 1)]
                        val path = Path()
                        var hasSegments = false

                        val fracLow = max(ci.toFloat() / nColors, ab.toFloat() / ALPHA_LAYERS)
                        val fracHigh = min((ci + 1).toFloat() / nColors, (ab + 1).toFloat() / ALPHA_LAYERS)
                        if (fracLow >= fracHigh) continue

                        for (i in 0 until activeN) {
                            val count = stTrailCount[i]
                            if (count < 2) continue

                            val base = i * tl
                            val head = stTrailHead[i]
                            val countM1 = count - 1

                            val jStart = max(0, ceil(fracLow * countM1).toInt())
                            val jEnd = min(countM1, floor(fracHigh * countM1).toInt())
                            if (jEnd <= jStart) continue

                            val idx0 = ((head - count + jStart) % tl + tl) % tl
                            var prevX = stTrailX[base + idx0]
                            var prevY = stTrailY[base + idx0]
                            path.moveTo(prevX, prevY)

                            for (j in jStart + 1..jEnd) {
                                val idx = ((head - count + j) % tl + tl) % tl
                                val tx = stTrailX[base + idx]
                                val ty = stTrailY[base + idx]
                                val dx = tx - prevX
                                val dy = ty - prevY
                                if (dx * dx > wrapThreshX || dy * dy > wrapThreshY) {
                                    path.moveTo(tx, ty)
                                } else {
                                    path.lineTo(tx, ty)
                                }
                                prevX = tx
                                prevY = ty
                            }
                            hasSegments = true
                        }

                        if (hasSegments) {
                            retainedCanvas.drawPath(path, trailPaint)
                        }
                    }
                }
            }

            else -> {
                // palette-cycle: noise-driven color at particle position
                val stPx = st.px
                val stPy = st.py
                for (i in 0 until activeN) {
                    if (stTrailCount[i] < 2) { stPCI[i] = -1; continue }
                    val nx = stPx[i] * invW * noiseScale * 0.5f
                    val ny = stPy[i] * invH * noiseScale * 0.5f
                    val nv = (noise.noise2D(nx + 50f, ny + 50f) + 1f) * 0.5f
                    stPCI[i] = min(nColorsM1, (nv * nColors).toInt())
                }

                for (ci in 0 until nColors) {
                    for (ab in 0 until ALPHA_BUCKETS) {
                        trailPaint.color = alphaColors[ci][ab]
                        val path = Path()
                        var hasSegments = false

                        for (i in 0 until activeN) {
                            if (stPCI[i] != ci) continue
                            val count = stTrailCount[i]

                            val segStart = (ab * count / ALPHA_BUCKETS)
                            val segEnd = ((ab + 1) * count / ALPHA_BUCKETS)
                            if (segEnd <= segStart) continue

                            val base = i * tl
                            val head = stTrailHead[i]

                            val startIdx = ((head - count + segStart) % tl + tl) % tl
                            var prevX = stTrailX[base + startIdx]
                            var prevY = stTrailY[base + startIdx]
                            path.moveTo(prevX, prevY)

                            for (j in segStart + 1 until segEnd) {
                                val idx = ((head - count + j) % tl + tl) % tl
                                val tx = stTrailX[base + idx]
                                val ty = stTrailY[base + idx]
                                val dx = tx - prevX
                                val dy = ty - prevY
                                if (dx * dx > wrapThreshX || dy * dy > wrapThreshY) {
                                    path.moveTo(tx, ty)
                                } else {
                                    path.lineTo(tx, ty)
                                }
                                prevX = tx
                                prevY = ty
                            }
                            hasSegments = true
                        }

                        if (hasSegments) {
                            retainedCanvas.drawPath(path, trailPaint)
                        }
                    }
                }
            }
        }

        // ── Copy retained bitmap to output canvas ───────────────────────────
        canvas.drawBitmap(st.retainedBitmap, 0f, 0f, null)
    }

    /**
     * Darken the first palette color by shifting right 2 (divide by 4)
     * to produce a near-black tinted background, matching the web version.
     */
    private fun darkenBgColor(palette: Palette): Int {
        val colorInts = palette.colorInts()
        if (colorInts.isEmpty()) return Color.BLACK
        val bg = colorInts[0]
        val r = Color.red(bg) shr 2
        val g = Color.green(bg) shr 2
        val b = Color.blue(bg) shr 2
        return Color.rgb(r, g, b)
    }

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float {
        val pc = (params["particleCount"] as? Number)?.toFloat() ?: 1500f
        val tl = (params["trailLength"] as? Number)?.toFloat() ?: 20f
        val oct = (params["octaves"] as? Number)?.toFloat() ?: 2f
        val fieldType = (params["fieldType"] as? String) ?: "curl"
        val fieldMul = when (fieldType) {
            "gradient" -> 1.3f
            "curl" -> 1.2f
            else -> 1.0f
        }
        val base = pc * tl * 0.005f * fieldMul * (1f + oct * 0.3f) / 100f
        return when (quality) {
            Quality.DRAFT -> base * 0.3f
            Quality.BALANCED -> base * 0.6f
            Quality.ULTRA -> base
        }.coerceIn(0.1f, 1f)
    }

    companion object {
        private const val TAU = 2.0f * 3.1415926535897932f
    }
}
