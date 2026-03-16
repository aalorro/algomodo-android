package com.artmondo.algomodo.generators.procedural

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

class FieldParticleGenerator : Generator {

    override val id = "procedural-field-particle"
    override val family = "procedural"
    override val styleName = "Field + Particle Motion"
    override val definition =
        "Vector field visualization with particles tracing flow lines through curl noise, attractors, vortices, or dipole fields."
    override val algorithmNotes =
        "Defines a vector field (curl of noise, point attractors, tangential vortices, or two-pole dipole). " +
        "Particles are seeded from deterministic positions and integrated forward through the field. " +
        "Trails are drawn as polylines with fading alpha. Color maps to speed, direction, age, or fixed palette."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema = listOf(
        Parameter.NumberParam("Particles", "particleCount", ParamGroup.COMPOSITION,
            "Number of flow particles", 200f, 5000f, 100f, 1500f),
        Parameter.SelectParam("Field Type", "fieldType", ParamGroup.COMPOSITION,
            "curl: noise curl | attractor: point pulls | vortex: tangential | dipole: two-pole field",
            listOf("curl", "attractor", "vortex", "dipole"), "curl"),
        Parameter.NumberParam("Trail Length", "trailLength", ParamGroup.GEOMETRY,
            "Integration steps per trail", 10f, 200f, 10f, 80f),
        Parameter.NumberParam("Field Strength", "fieldStrength", ParamGroup.FLOW_MOTION,
            "Velocity multiplier", 0.1f, 3f, 0.1f, 1.0f),
        Parameter.NumberParam("Line Width", "lineWidth", ParamGroup.TEXTURE,
            "Stroke width of trails", 0.5f, 3f, 0.25f, 1.0f),
        Parameter.SelectParam("Color Mode", "colorMode", ParamGroup.COLOR,
            "velocity: speed → color | direction: angle → color | age: trail position → color | palette: fixed",
            listOf("velocity", "direction", "age", "palette"), "velocity"),
        Parameter.NumberParam("Speed", "speed", ParamGroup.FLOW_MOTION,
            "Animation speed", 0.1f, 3f, 0.1f, 1.0f)
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "particleCount" to 1500f, "fieldType" to "curl", "trailLength" to 80f,
        "fieldStrength" to 1.0f, "lineWidth" to 1.0f, "colorMode" to "velocity", "speed" to 1.0f
    )

    override fun renderCanvas(
        canvas: Canvas, bitmap: Bitmap, params: Map<String, Any>,
        seed: Int, palette: Palette, quality: Quality, time: Float
    ) {
        val w = bitmap.width.toFloat(); val h = bitmap.height.toFloat()
        val rng = SeededRNG(seed)
        val minDim = min(w, h)
        val TAU = PI.toFloat() * 2f

        val pCount = (params["particleCount"] as? Number)?.toInt()?.coerceAtLeast(50) ?: 1500
        val fieldType = (params["fieldType"] as? String) ?: "curl"
        val trailLen = (params["trailLength"] as? Number)?.toInt()?.coerceAtLeast(5) ?: 80
        val baseFStr = (params["fieldStrength"] as? Number)?.toFloat() ?: 1.0f
        val lw = (params["lineWidth"] as? Number)?.toFloat() ?: 1.0f
        val colorMode = (params["colorMode"] as? String) ?: "velocity"
        val spd = (params["speed"] as? Number)?.toFloat() ?: 1.0f
        val t = time * spd

        val audioAnalysis = params["_audioAnalysis"] as? AudioAnalysis
        val audioBass = audioAnalysis?.getBass(time) ?: 0f
        val audioHigh = audioAnalysis?.getHigh(time) ?: 0f

        val qualityMult = when (quality) { Quality.DRAFT -> 0.5f; Quality.ULTRA -> 1.0f; else -> 0.75f }
        val actualCount = (pCount * qualityMult).toInt()

        val colors = palette.colorInts()
        val nC = colors.size
        val vn = ValueNoise(seed)

        // Background
        canvas.drawColor(Color.rgb(10, 10, 10))

        val maxVel = minDim * 0.025f
        val maxVelSq = maxVel * maxVel
        val noiseScale = 3.0f / minDim
        val fStr = baseFStr * (1f + audioBass * 2.5f)
        val curlMag = fStr * 6f
        val curlTimeX = t * 0.01f; val curlTimeY = t * 0.007f

        // Build sin/cos LUT
        val LUT_SIZE = 1024; val LUT_MASK = LUT_SIZE - 1
        val COS_LUT = FloatArray(LUT_SIZE); val SIN_LUT = FloatArray(LUT_SIZE)
        for (i in 0 until LUT_SIZE) {
            val a = (i.toFloat() / LUT_SIZE) * TAU
            COS_LUT[i] = cos(a); SIN_LUT[i] = sin(a)
        }

        // Centers for vortex/attractor/dipole
        val centersX = mutableListOf<Float>(); val centersY = mutableListOf<Float>()
        val centersStr = mutableListOf<Float>()

        if (fieldType == "dipole") {
            val sep = rng.range(minDim * 0.12f, minDim * 0.22f)
            val angle = rng.range(0f, TAU)
            val cosA = cos(angle); val sinA = sin(angle)
            centersX.add(w * 0.5f + cosA * sep); centersX.add(w * 0.5f - cosA * sep)
            centersY.add(h * 0.5f + sinA * sep); centersY.add(h * 0.5f - sinA * sep)
            centersStr.add(1.5f); centersStr.add(-1.5f)
        } else if (fieldType == "vortex" || fieldType == "attractor") {
            val nCenters = rng.integer(2, 4)
            for (i in 0 until nCenters) {
                centersX.add(rng.range(w * 0.2f, w * 0.8f))
                centersY.add(rng.range(h * 0.2f, h * 0.8f))
                centersStr.add(rng.range(0.5f, 2.0f))
            }
        }

        val attractFStr = fStr * 50000f
        val vortexFStr = fStr * 200f
        val dipoleFStr = fStr * 1.2e8f

        var outVx = 0f; var outVy = 0f
        fun computeVelocity(px: Float, py: Float) {
            when (fieldType) {
                "curl" -> {
                    val noiseVal = vn.noise(px * noiseScale + curlTimeX, py * noiseScale + curlTimeY)
                    val idx = ((noiseVal * 0.5f + 0.5f) * LUT_SIZE).toInt() and LUT_MASK
                    outVx = COS_LUT[idx] * curlMag; outVy = SIN_LUT[idx] * curlMag
                }
                "attractor" -> {
                    outVx = 0f; outVy = 0f
                    for (c in centersX.indices) {
                        val dx = centersX[c] - px; val dy = centersY[c] - py
                        val f = centersStr[c] * attractFStr / (dx * dx + dy * dy + 1000f)
                        outVx += -dy * f * 0.7f + dx * f * 0.3f
                        outVy += dx * f * 0.7f + dy * f * 0.3f
                    }
                }
                "vortex" -> {
                    outVx = 0f; outVy = 0f
                    for (c in centersX.indices) {
                        val dx = px - centersX[c]; val dy = py - centersY[c]
                        val f = centersStr[c] * vortexFStr / (dx * dx + dy * dy + 100f)
                        outVx += -dy * f; outVy += dx * f
                    }
                }
                else -> { // dipole
                    outVx = 0f; outVy = 0f
                    for (c in centersX.indices) {
                        val dx = px - centersX[c]; val dy = py - centersY[c]
                        val r2 = dx * dx + dy * dy + 200f
                        val f = centersStr[c] * dipoleFStr / (r2 * r2)
                        outVx += (-dy * 0.5f + dx * 0.5f) * f
                        outVy += (dx * 0.5f + dy * 0.5f) * f
                    }
                }
            }
        }

        // Draw field lines for curl and dipole
        if (fieldType == "curl" || fieldType == "dipole") {
            val spacing = when (quality) { Quality.DRAFT -> 50; Quality.ULTRA -> 25; else -> 35 }
            val lineLen = spacing * 0.35f
            val fieldPaint = Paint(Paint.ANTI_ALIAS_FLAG)
            fieldPaint.style = Paint.Style.STROKE
            fieldPaint.strokeWidth = 0.5f
            val gc = colors[0]
            fieldPaint.color = Color.argb(25, Color.red(gc), Color.green(gc), Color.blue(gc))

            val path = Path()
            var gy = spacing * 0.5f
            while (gy < h) {
                var gx = spacing * 0.5f
                while (gx < w) {
                    computeVelocity(gx, gy)
                    val mag = sqrt(outVx * outVx + outVy * outVy)
                    if (mag > 0.001f) {
                        val sc = lineLen / mag * 0.5f
                        val dx = outVx * sc; val dy = outVy * sc
                        path.moveTo(gx - dx, gy - dy); path.lineTo(gx + dx, gy + dy)
                    }
                    gx += spacing
                }
                gy += spacing
            }
            canvas.drawPath(path, fieldPaint)
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
        paint.strokeJoin = Paint.Join.BEVEL
        val dt = 0.5f
        val phaseOffset = t * 0.5f

        val trailX = FloatArray(trailLen + 1); val trailY = FloatArray(trailLen + 1)
        val trailWrap = BooleanArray(trailLen)
        val trailSpeed = if (colorMode == "velocity") FloatArray(trailLen) else null

        for (i in 0 until actualCount) {
            val driftAngle = vn.noise(startX[i] * 0.001f + phaseOffset, startY[i] * 0.001f) * TAU
            val driftMag = minDim * 0.1f
            var px = startX[i] + cos(driftAngle) * driftMag
            var py = startY[i] + sin(driftAngle) * driftMag
            trailX[0] = px; trailY[0] = py

            for (s in 0 until trailLen) {
                computeVelocity(px, py)
                trailSpeed?.set(s, sqrt(outVx * outVx + outVy * outVy))

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

            // Draw trail
            val bands = 4
            val segsPerBand = (trailLen + bands - 1) / bands

            for (band in 0 until bands) {
                val sStart = band * segsPerBand
                val sEnd = min((band + 1) * segsPerBand, trailLen)
                if (sStart >= trailLen) break

                val midAge = ((sStart + sEnd) * 0.5f) / trailLen
                val baseAlpha = 0.7f + audioHigh * 0.3f
                val alpha = ((1f - midAge) * baseAlpha * 255f).toInt().coerceIn(0, 255)
                if (alpha < 5) break

                // Determine color for this band
                val c: Int = when (colorMode) {
                    "velocity" -> {
                        val speed = trailSpeed?.get(min(sStart, trailLen - 1)) ?: 0f
                        val ci = min(nC - 1, (min(1f, speed * 0.3f) * (nC - 1)).toInt())
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
    }

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float {
        val count = (params["particleCount"] as? Number)?.toFloat() ?: 1500f
        val trail = (params["trailLength"] as? Number)?.toFloat() ?: 80f
        return (count * trail / 400000f).coerceIn(0.3f, 1f)
    }
}
