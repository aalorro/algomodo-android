package com.artmondo.algomodo.generators.procedural

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

        // Background
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

        var outVx = 0f; var outVy = 0f
        fun computeVelocity(px: Float, py: Float) {
            val nx = px * noiseScale + tNx
            val ny = py * noiseScale + tNy

            when (modeId) {
                0 -> { // curl
                    val angle = vn.noise(nx, ny) * TAU
                    outVx = cos(angle) * velMag; outVy = sin(angle) * velMag
                }
                1 -> { // gradient
                    val angle = vn.noise(nx, ny) * TAU + 1.5708f
                    outVx = cos(angle) * velMag; outVy = sin(angle) * velMag
                }
                2 -> { // orbital
                    outVx = 0f; outVy = 0f
                    for (c in 0 until nOrb) {
                        val dx = px - orbCx!![c]; val dy = py - orbCy!![c]
                        val invR2 = 1f / (dx * dx + dy * dy + 100f)
                        val f = orbStr!![c] * orbFStr * invR2
                        outVx += -dy * f + dx * f * 0.15f
                        outVy += dx * f + dy * f * 0.15f
                    }
                    val angle = vn.noise(nx, ny) * TAU
                    outVx += cos(angle) * orbNoiseMag
                    outVy += sin(angle) * orbNoiseMag
                }
                else -> { // turbulent
                    val angle1 = vn.noise(nx, ny) * TAU
                    val cosA = cos(angle1); val sinA = sin(angle1)
                    outVx = cosA * velMag; outVy = sinA * velMag
                    val angle2 = vn.noise(px * turbScale + tNx * 2f, py * turbScale + tNy * 2f) * TAU
                    outVx += cos(angle2) * turbMag; outVy += sin(angle2) * turbMag
                    outVx += sinA * turbMag * 0.3f; outVy -= cosA * turbMag * 0.3f
                }
            }
        }

        // Seed particles
        val startX = FloatArray(actualCount); val startY = FloatArray(actualCount)
        val pColorIdx = IntArray(actualCount)
        for (i in 0 until actualCount) {
            startX[i] = rng.range(0f, w); startY[i] = rng.range(0f, h)
            pColorIdx[i] = rng.integer(0, nC - 1)
        }

        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = lw
        paint.strokeCap = Paint.Cap.BUTT
        paint.strokeJoin = Paint.Join.MITER
        // Additive blending
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)

        val phaseOffset = t * 0.4f
        val fadeMult = 1f + fadeRate * 2f
        val invTrailLen = 1f / trailLen

        val trailX = FloatArray(trailLen + 1); val trailY = FloatArray(trailLen + 1)
        val trailWrap = BooleanArray(trailLen)
        val trailSpeedSq = if (colorMode == "speed") FloatArray(trailLen) else null

        for (i in 0 until actualCount) {
            val driftAngle = vn.noise(startX[i] * 0.001f + phaseOffset, startY[i] * 0.001f) * TAU
            val driftMag = minDim * 0.08f
            var px = startX[i] + cos(driftAngle) * driftMag
            var py = startY[i] + sin(driftAngle) * driftMag
            trailX[0] = px; trailY[0] = py

            for (s in 0 until trailLen) {
                computeVelocity(px, py)
                trailSpeedSq?.set(s, outVx * outVx + outVy * outVy)

                val magSq = outVx * outVx + outVy * outVy
                if (magSq > maxVelSq) {
                    val scale = maxVel / sqrt(magSq)
                    outVx *= scale; outVy *= scale
                }

                val newPx = px + outVx * dt; val newPy = py + outVy * dt
                var wrapped = false
                if (newPx < 0f) { px = newPx + w; wrapped = true }
                else if (newPx > w) { px = newPx - w; wrapped = true }
                else px = newPx

                if (newPy < 0f) { py = newPy + h; wrapped = true }
                else if (newPy > h) { py = newPy - h; wrapped = true }
                else py = newPy

                trailWrap[s] = wrapped
                trailX[s + 1] = px; trailY[s + 1] = py
            }

            // Draw trail in bands
            val bands = 4
            val segsPerBand = (trailLen + bands - 1) / bands

            for (band in 0 until bands) {
                val sStart = band * segsPerBand
                val sEnd = min((band + 1) * segsPerBand, trailLen)
                if (sStart >= trailLen) break

                val midAge = ((sStart + sEnd) * 0.5f) * invTrailLen
                val baseAlpha = 0.85f + audioHigh * 0.15f
                val alpha = ((1f - midAge * fadeMult) * baseAlpha * 255f).toInt().coerceIn(0, 255)
                if (alpha < 5) break

                val c: Int = when (colorMode) {
                    "speed" -> {
                        val speedSq = trailSpeedSq?.get(min(sStart, trailLen - 1)) ?: 0f
                        val ci = min(nC - 1, (min(1f, speedSq * invMaxVelSqScaled) * (nC - 1)).toInt())
                        colors[ci]
                    }
                    "direction" -> {
                        val sn = min(sStart + 1, trailLen)
                        val ddx = trailX[sn] - trailX[sStart]
                        val ddy = trailY[sn] - trailY[sStart]
                        val ang = (atan2(ddy, ddx) + PI.toFloat()) / TAU
                        val ci = (ang * (nC - 1)).toInt().coerceIn(0, nC - 1)
                        colors[ci]
                    }
                    "age" -> {
                        val ci = (midAge * (nC - 1)).toInt().coerceIn(0, nC - 1)
                        colors[ci]
                    }
                    else -> colors[pColorIdx[i]]
                }

                paint.color = Color.argb(alpha, Color.red(c), Color.green(c), Color.blue(c))

                val path = Path()
                path.moveTo(trailX[sStart], trailY[sStart])
                for (s in sStart until sEnd) {
                    if (trailWrap[s]) {
                        canvas.drawPath(path, paint)
                        path.reset()
                        path.moveTo(trailX[s + 1], trailY[s + 1])
                    } else {
                        path.lineTo(trailX[s + 1], trailY[s + 1])
                    }
                }
                canvas.drawPath(path, paint)
            }
        }

        // Reset xfermode
        paint.xfermode = null
    }

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float {
        val count = (params["particleCount"] as? Number)?.toFloat() ?: 2500f
        val trail = (params["trailLength"] as? Number)?.toFloat() ?: 80f
        return (count * trail / 400000f).coerceIn(0.3f, 1f)
    }
}
