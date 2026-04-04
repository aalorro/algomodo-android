package com.artmondo.algomodo.generators.procedural

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

class ParticleAdvectionGenerator : Generator {

    override val id = "procedural-particle-advection"
    override val family = "procedural"
    override val styleName = "Particle Advection"
    override val definition =
        "Particles advected through time-varying velocity fields — curl noise, gradient flow, orbital motion, and turbulent chaos."
    override val algorithmNotes =
        "Seeds particles deterministically and integrates them through a 2D velocity field. " +
        "Curl and gradient modes use angle-based flow (1 noise call + sin/cos). " +
        "Orbital mode adds tangential velocity around seeded attractors. " +
        "Turbulent mode layers two noise frequencies for chaotic advection. " +
        "Trails are drawn with additive blending for luminous accumulation."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema = listOf(
        Parameter.SelectParam("Field Mode", "fieldMode", ParamGroup.COMPOSITION,
            "curl: divergence-free smoke | gradient: flow | orbital: circling | turbulent: chaotic",
            listOf("curl", "gradient", "orbital", "turbulent"), "curl"),
        Parameter.NumberParam("Particles", "particleCount", ParamGroup.COMPOSITION,
            "Number of advected particles", 800f, 5000f, 100f, 2500f),
        Parameter.NumberParam("Trail Length", "trailLength", ParamGroup.FLOW_MOTION,
            "Integration steps per trail", 20f, 200f, 10f, 80f),
        Parameter.NumberParam("Field Scale", "fieldScale", ParamGroup.GEOMETRY,
            "Spatial frequency of velocity field", 0.5f, 6f, 0.1f, 2.0f),
        Parameter.NumberParam("Field Strength", "fieldStrength", ParamGroup.FLOW_MOTION,
            "Velocity magnitude multiplier", 1.0f, 5f, 0.1f, 2.5f),
        Parameter.NumberParam("Line Width", "lineWidth", ParamGroup.TEXTURE,
            "Stroke width of particle trails", 0.5f, 4f, 0.25f, 1.5f),
        Parameter.NumberParam("Fade Rate", "fadeRate", ParamGroup.TEXTURE,
            "How quickly trail segments fade with age", 0.01f, 0.3f, 0.01f, 0.05f),
        Parameter.SelectParam("Color Mode", "colorMode", ParamGroup.COLOR,
            "speed: velocity → color | direction: angle → color | age: position → color | palette: fixed",
            listOf("speed", "direction", "age", "palette"), "speed"),
        Parameter.NumberParam("Speed", "speed", ParamGroup.FLOW_MOTION,
            "Animation drift speed", 0.1f, 3f, 0.05f, 0.7f),
        Parameter.NumberParam("Reactivity", "reactivity", ParamGroup.FLOW_MOTION,
            "Audio reactivity strength", 0f, 2f, 0.1f, 1.0f)
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "fieldMode" to "curl", "particleCount" to 2500f, "trailLength" to 80f,
        "fieldScale" to 2.0f, "fieldStrength" to 2.5f, "lineWidth" to 1.5f,
        "fadeRate" to 0.05f, "colorMode" to "speed", "speed" to 0.7f,
        "reactivity" to 1.0f
    )

    // ---- Trig LUT: ~10x faster than Math.sin/cos ----
    private companion object {
        const val LUT_SIZE = 4096
        const val LUT_MASK = LUT_SIZE - 1
        val INV_2PI_LUT = LUT_SIZE / (2f * PI.toFloat())
        val SIN_LUT = FloatArray(LUT_SIZE) { sin(it * 2.0 * PI / LUT_SIZE).toFloat() }
        val COS_LUT = FloatArray(LUT_SIZE) { cos(it * 2.0 * PI / LUT_SIZE).toFloat() }
        const val COLOR_BUCKETS = 32
    }

    private fun fSin(x: Float): Float = SIN_LUT[((x * INV_2PI_LUT).toInt() + 65536) and LUT_MASK]
    private fun fCos(x: Float): Float = COS_LUT[((x * INV_2PI_LUT).toInt() + 65536) and LUT_MASK]

    // Reusable arrays
    @Volatile private var reuseAllX: FloatArray? = null
    @Volatile private var reuseAllY: FloatArray? = null
    @Volatile private var reuseAllWrap: BooleanArray? = null
    @Volatile private var reuseLineBuffer: FloatArray? = null

    override fun renderCanvas(
        canvas: Canvas, bitmap: Bitmap, params: Map<String, Any>,
        seed: Int, palette: Palette, quality: Quality, time: Float
    ) {
        val w = bitmap.width.toFloat(); val h = bitmap.height.toFloat()
        val rng = SeededRNG(seed)
        val minDim = min(w, h)
        val TAU = PI.toFloat() * 2f

        val fieldMode = (params["fieldMode"] as? String) ?: "curl"
        val pCount = (params["particleCount"] as? Number)?.toInt()?.coerceAtLeast(800) ?: 2500
        val trailLen = (params["trailLength"] as? Number)?.toInt()?.coerceAtLeast(20) ?: 80
        val fScale = (params["fieldScale"] as? Number)?.toFloat() ?: 2.0f
        val baseFStr = (params["fieldStrength"] as? Number)?.toFloat()?.coerceAtLeast(1f) ?: 2.5f
        val lw = (params["lineWidth"] as? Number)?.toFloat()?.coerceAtLeast(0.5f) ?: 1.5f
        val fadeRate = (params["fadeRate"] as? Number)?.toFloat() ?: 0.05f
        val colorMode = (params["colorMode"] as? String) ?: "speed"
        val spd = (params["speed"] as? Number)?.toFloat() ?: 0.7f

        val rx = (params["reactivity"] as? Number)?.toFloat() ?: 1.0f
        val audioAnalysis = params["_audioAnalysis"] as? AudioAnalysis
        val audioBass = (audioAnalysis?.getBass(time) ?: 0f) * rx
        val audioMid = (audioAnalysis?.getMid(time) ?: 0f) * rx
        val audioHigh = (audioAnalysis?.getHigh(time) ?: 0f) * rx

        val t = time * spd
        val fStr = baseFStr * (1f + audioBass * 2f)
        val effScale = fScale * (1f + audioMid * 0.3f)

        val qualityMult = when (quality) { Quality.DRAFT -> 0.5f; Quality.ULTRA -> 1.0f; else -> 0.75f }
        val actualCount = (pCount * qualityMult).toInt()

        val colors = palette.colorInts()
        val nC = colors.size
        val vn = ValueNoise(seed)

        canvas.drawColor(Color.rgb(8, 8, 16))

        val noiseScale = effScale / minDim
        val velMag = fStr * 6f
        val maxVel = minDim * 0.03f
        val maxVelSq = maxVel * maxVel
        val dt = 0.6f

        val modeId = when (fieldMode) { "curl" -> 0; "gradient" -> 1; "orbital" -> 2; else -> 3 }

        val tNx = t * 0.03f; val tNy = t * 0.02f

        // Orbital mode attractors
        var nOrb = 0
        var orbCx: FloatArray? = null; var orbCy: FloatArray? = null; var orbStr: FloatArray? = null
        if (modeId == 2) {
            nOrb = rng.integer(2, 5)
            orbCx = FloatArray(nOrb); orbCy = FloatArray(nOrb); orbStr = FloatArray(nOrb)
            for (i in 0 until nOrb) {
                orbCx[i] = rng.range(w * 0.15f, w * 0.85f)
                orbCy[i] = rng.range(h * 0.15f, h * 0.85f)
                orbStr[i] = rng.range(0.5f, 2.0f) * if (rng.random() > 0.5f) 1f else -1f
            }
        }

        val turbScale = noiseScale * 3f
        val turbMag = velMag * 0.4f
        val orbFStr = fStr * 200f
        val orbNoiseMag = velMag * 0.3f
        val invMaxVelSqScaled = 1f / (maxVelSq * 0.64f)

        // Seed particles
        val startX = FloatArray(actualCount); val startY = FloatArray(actualCount)
        val pColorIdx = IntArray(actualCount)
        for (i in 0 until actualCount) {
            startX[i] = rng.range(0f, w); startY[i] = rng.range(0f, h)
            pColorIdx[i] = rng.integer(0, nC - 1)
        }

        val phaseOffset = t * 0.4f
        val fadeMult = 1f + fadeRate * 2f
        val invTrailLen = 1f / trailLen

        // ---- Phase 1: Compute all trails into flat SoA arrays ----
        val trailStride = trailLen + 1
        val totalTrailPts = actualCount * trailStride
        val totalTrailSegs = actualCount * trailLen

        val allX: FloatArray
        val rax = reuseAllX
        if (rax != null && rax.size >= totalTrailPts) { allX = rax } else {
            allX = FloatArray(totalTrailPts); reuseAllX = allX
        }
        val allY: FloatArray
        val ray = reuseAllY
        if (ray != null && ray.size >= totalTrailPts) { allY = ray } else {
            allY = FloatArray(totalTrailPts); reuseAllY = allY
        }
        val allWrap: BooleanArray
        val raw = reuseAllWrap
        if (raw != null && raw.size >= totalTrailSegs) { allWrap = raw } else {
            allWrap = BooleanArray(totalTrailSegs); reuseAllWrap = allWrap
        }

        // Per-particle color keys for batching (quantized to COLOR_BUCKETS per band)
        val colorKeys = IntArray(actualCount)
        // Speed data for "speed" color mode
        val bandSpeedSq = if (colorMode == "speed") FloatArray(actualCount) else null

        for (i in 0 until actualCount) {
            val base = i * trailStride
            val wBase = i * trailLen

            val driftAngle = vn.noise(startX[i] * 0.001f + phaseOffset, startY[i] * 0.001f) * TAU
            val driftMag = minDim * 0.08f
            var px = startX[i] + fCos(driftAngle) * driftMag
            var py = startY[i] + fSin(driftAngle) * driftMag
            allX[base] = px; allY[base] = py

            for (s in 0 until trailLen) {
                // ---- Inline velocity computation (no closure overhead) ----
                val nx = px * noiseScale + tNx
                val ny = py * noiseScale + tNy
                var vx: Float; var vy: Float

                when (modeId) {
                    0 -> { // curl
                        val angle = vn.noise(nx, ny) * TAU
                        vx = fCos(angle) * velMag; vy = fSin(angle) * velMag
                    }
                    1 -> { // gradient
                        val angle = vn.noise(nx, ny) * TAU + 1.5708f
                        vx = fCos(angle) * velMag; vy = fSin(angle) * velMag
                    }
                    2 -> { // orbital
                        vx = 0f; vy = 0f
                        for (c in 0 until nOrb) {
                            val dx = px - orbCx!![c]; val dy = py - orbCy!![c]
                            val invR2 = 1f / (dx * dx + dy * dy + 100f)
                            val f = orbStr!![c] * orbFStr * invR2
                            vx += -dy * f + dx * f * 0.15f
                            vy += dx * f + dy * f * 0.15f
                        }
                        val angle = vn.noise(nx, ny) * TAU
                        vx += fCos(angle) * orbNoiseMag
                        vy += fSin(angle) * orbNoiseMag
                    }
                    else -> { // turbulent
                        val angle1 = vn.noise(nx, ny) * TAU
                        val cosA = fCos(angle1); val sinA = fSin(angle1)
                        vx = cosA * velMag; vy = sinA * velMag
                        val angle2 = vn.noise(px * turbScale + tNx * 2f, py * turbScale + tNy * 2f) * TAU
                        vx += fCos(angle2) * turbMag; vy += fSin(angle2) * turbMag
                        vx += sinA * turbMag * 0.3f; vy -= cosA * turbMag * 0.3f
                    }
                }

                // Clamp velocity
                val magSq = vx * vx + vy * vy
                if (magSq > maxVelSq) {
                    val scale = maxVel / sqrt(magSq)
                    vx *= scale; vy *= scale
                }

                // Store speed for first step (used by speed color mode)
                if (s == 0) bandSpeedSq?.set(i, vx * vx + vy * vy)

                // Advance + wrap
                val newPx = px + vx * dt; val newPy = py + vy * dt
                var wrapped = false
                if (newPx < 0f) { px = newPx + w; wrapped = true }
                else if (newPx > w) { px = newPx - w; wrapped = true }
                else px = newPx
                if (newPy < 0f) { py = newPy + h; wrapped = true }
                else if (newPy > h) { py = newPy - h; wrapped = true }
                else py = newPy

                allWrap[wBase + s] = wrapped
                allX[base + s + 1] = px; allY[base + s + 1] = py
            }
        }

        // ---- Phase 2: Batched drawing with quantized color buckets ----
        // Instead of 10K drawPath calls (2500 particles × 4 bands, each with Path allocation),
        // group segments by (band, color_bucket) → max 4 × 32 = 128 drawLines calls.
        val isAnim = time > 0f
        val paint = Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = lw
            strokeCap = Paint.Cap.BUTT
            strokeJoin = Paint.Join.MITER
            xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
            isAntiAlias = !isAnim
        }

        val bands = 4
        val segsPerBand = (trailLen + bands - 1) / bands
        val baseAlpha = 0.85f + audioHigh * 0.15f

        // Pre-allocate line buffer (max segments: all particles × segsPerBand × 4 floats)
        val maxLineFloats = actualCount * segsPerBand * 4
        val lineBuffer: FloatArray
        val rlb = reuseLineBuffer
        if (rlb != null && rlb.size >= maxLineFloats) { lineBuffer = rlb } else {
            lineBuffer = FloatArray(maxLineFloats); reuseLineBuffer = lineBuffer
        }

        // Build palette LUT for color quantization
        val palLutSize = COLOR_BUCKETS
        val palLutMax = palLutSize - 1
        val palLut = IntArray(palLutSize) { palette.lerpColor(it.toFloat() / palLutMax) }

        for (band in 0 until bands) {
            val sStart = band * segsPerBand
            val sEnd = min((band + 1) * segsPerBand, trailLen)
            if (sStart >= trailLen) break

            val midAge = ((sStart + sEnd) * 0.5f) * invTrailLen
            val alpha = ((1f - midAge * fadeMult) * baseAlpha * 255f).toInt().coerceIn(0, 255)
            if (alpha < 5) break

            // Compute color key per particle for this band
            when (colorMode) {
                "speed" -> {
                    for (i in 0 until actualCount) {
                        val speedSq = bandSpeedSq?.get(i) ?: 0f
                        colorKeys[i] = min(palLutMax, (min(1f, speedSq * invMaxVelSqScaled) * palLutMax).toInt())
                    }
                }
                "direction" -> {
                    val TAUf = TAU
                    for (i in 0 until actualCount) {
                        val base = i * trailStride
                        val sn = min(sStart + 1, trailLen)
                        val ddx = allX[base + sn] - allX[base + sStart]
                        val ddy = allY[base + sn] - allY[base + sStart]
                        val ang = (atan2(ddy, ddx) + PI.toFloat()) / TAUf
                        colorKeys[i] = (ang * palLutMax).toInt().coerceIn(0, palLutMax)
                    }
                }
                "age" -> {
                    // All particles have the same color in this band
                    val ck = (midAge * palLutMax).toInt().coerceIn(0, palLutMax)
                    for (i in 0 until actualCount) colorKeys[i] = ck
                }
                else /* palette */ -> {
                    for (i in 0 until actualCount) {
                        colorKeys[i] = (pColorIdx[i].toFloat() / max(1, nC - 1) * palLutMax).toInt().coerceIn(0, palLutMax)
                    }
                }
            }

            // Draw each color bucket: collect segments → one drawLines call per bucket
            for (ck in 0 until palLutSize) {
                var lineIdx = 0
                for (i in 0 until actualCount) {
                    if (colorKeys[i] != ck) continue
                    val base = i * trailStride
                    val wBase = i * trailLen
                    for (s in sStart until sEnd) {
                        if (!allWrap[wBase + s]) {
                            lineBuffer[lineIdx++] = allX[base + s]
                            lineBuffer[lineIdx++] = allY[base + s]
                            lineBuffer[lineIdx++] = allX[base + s + 1]
                            lineBuffer[lineIdx++] = allY[base + s + 1]
                        }
                    }
                }
                if (lineIdx > 0) {
                    val c = palLut[ck]
                    paint.color = Color.argb(alpha, Color.red(c), Color.green(c), Color.blue(c))
                    canvas.drawLines(lineBuffer, 0, lineIdx, paint)
                }
            }
        }

        paint.xfermode = null
    }

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float {
        val count = (params["particleCount"] as? Number)?.toFloat() ?: 2500f
        val trail = (params["trailLength"] as? Number)?.toFloat() ?: 80f
        return (count * trail / 400000f).coerceIn(0.3f, 1f)
    }
}
