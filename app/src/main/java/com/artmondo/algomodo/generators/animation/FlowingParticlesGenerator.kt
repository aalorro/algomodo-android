package com.artmondo.algomodo.generators.animation

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import com.artmondo.algomodo.core.rng.SeededRNG
import com.artmondo.algomodo.core.rng.SimplexNoise
import com.artmondo.algomodo.data.palettes.Palette
import com.artmondo.algomodo.generators.Generator
import com.artmondo.algomodo.generators.ParamGroup
import com.artmondo.algomodo.generators.Parameter
import com.artmondo.algomodo.generators.Quality
import kotlin.math.*

/**
 * Particles flowing through a simplex noise vector field.
 *
 * Each particle is deterministically seeded, then advected through a 2D noise field
 * whose angle is derived from SimplexNoise. Trails are drawn as polylines fading
 * from opaque to transparent, giving the look of ink being carried by wind.
 */
class FlowingParticlesGenerator : Generator {

    override val id = "flowing-particles"
    override val family = "animation"
    override val styleName = "Flowing Particles"
    override val definition = "Particles flowing through a simplex noise vector field, leaving fading trails."
    override val algorithmNotes =
        "Particles are initialized from the seed, then each frame they advance by " +
        "following the gradient angle given by noise2D(x * noiseScale, y * noiseScale + time). " +
        "A trail buffer stores recent positions; trails are rendered as polylines " +
        "with alpha fading from head to tail."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema = listOf(
        Parameter.NumberParam(
            name = "Particles",
            key = "particleCount",
            group = ParamGroup.COMPOSITION,
            help = "Number of flowing particles",
            min = 100f, max = 5000f, step = 100f, default = 2000f
        ),
        Parameter.NumberParam(
            name = "Attractors",
            key = "attractorCount",
            group = ParamGroup.COMPOSITION,
            help = "Glowing bodies that orbit and warp the flow field",
            min = 0f, max = 6f, step = 1f, default = 0f
        ),
        Parameter.NumberParam(
            name = "Flow Scale",
            key = "flowScale",
            group = ParamGroup.GEOMETRY,
            help = "Size of flow field patterns",
            min = 0.5f, max = 5f, step = 0.5f, default = 2f
        ),
        Parameter.SelectParam(
            name = "Shape",
            key = "objectType",
            group = ParamGroup.GEOMETRY,
            help = "Shape rendered for each particle",
            options = listOf("circle", "square", "triangle", "line", "mixed"),
            default = "circle"
        ),
        Parameter.NumberParam(
            name = "Speed",
            key = "flowSpeed",
            group = ParamGroup.FLOW_MOTION,
            help = "Particle flow speed",
            min = 0.5f, max = 5f, step = 0.5f, default = 2f
        ),
        Parameter.NumberParam(
            name = "Size",
            key = "particleSize",
            group = ParamGroup.TEXTURE,
            help = "Base particle size",
            min = 0.5f, max = 10f, step = 0.5f, default = 3f
        ),
        Parameter.NumberParam(
            name = "Size Variance",
            key = "sizeVariance",
            group = ParamGroup.TEXTURE,
            help = "Random variation in individual particle sizes",
            min = 0f, max = 1f, step = 0.1f, default = 0.3f
        ),
        Parameter.NumberParam(
            name = "Trail",
            key = "trailLength",
            group = ParamGroup.TEXTURE,
            help = "Motion blur amount",
            min = 0f, max = 1f, step = 0.1f, default = 0.5f
        )
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "particleCount" to 2000f,
        "attractorCount" to 0f,
        "flowScale" to 2f,
        "objectType" to "circle",
        "flowSpeed" to 2f,
        "particleSize" to 3f,
        "sizeVariance" to 0.3f,
        "trailLength" to 0.5f
    )

    override fun renderCanvas(
        canvas: Canvas,
        bitmap: Bitmap,
        params: Map<String, Any>,
        seed: Int,
        palette: Palette,
        quality: Quality,
        time: Float
    ) {
        val w = bitmap.width.toFloat()
        val h = bitmap.height.toFloat()
        val dim = min(w, h)

        val count = ((params["particleCount"] as? Number)?.toInt() ?: 2000).let {
            when (quality) {
                Quality.DRAFT -> (it * 0.4f).toInt()
                Quality.BALANCED -> it
                Quality.ULTRA -> (it * 1.5f).toInt()
            }
        }
        val speed = (params["flowSpeed"] as? Number)?.toFloat() ?: 2f
        val noiseScale = (params["flowScale"] as? Number)?.toFloat() ?: 2f
        val trailFraction = (params["trailLength"] as? Number)?.toFloat() ?: 0.5f
        val trailLen = (trailFraction * 60).toInt().coerceAtLeast(5)
        val particleSize = (params["particleSize"] as? Number)?.toFloat() ?: 3f
        val sizeVariance = (params["sizeVariance"] as? Number)?.toFloat() ?: 0.3f
        val attractorCount = (params["attractorCount"] as? Number)?.toInt() ?: 0
        val objectType = (params["objectType"] as? String) ?: "circle"

        val rng = SeededRNG(seed)
        val noise = SimplexNoise(seed)
        val colors = palette.colorInts()

        // Dark background from first palette color
        val bg = colors[0]
        canvas.drawColor(
            Color.rgb(
                (Color.red(bg) * 0.12f).toInt(),
                (Color.green(bg) * 0.12f).toInt(),
                (Color.blue(bg) * 0.12f).toInt()
            )
        )

        // Generate attractors (orbiting bodies that warp the flow)
        val attractorBaseX = FloatArray(attractorCount)
        val attractorBaseY = FloatArray(attractorCount)
        val attractorOrbitR = FloatArray(attractorCount)
        val attractorOrbitFreq = FloatArray(attractorCount)
        val attractorPhase = FloatArray(attractorCount)
        val attractorStrength = FloatArray(attractorCount)

        for (a in 0 until attractorCount) {
            attractorBaseX[a] = w * 0.2f + rng.random() * w * 0.6f
            attractorBaseY[a] = h * 0.2f + rng.random() * h * 0.6f
            attractorOrbitR[a] = dim * 0.05f + rng.random() * dim * 0.15f
            attractorOrbitFreq[a] = 0.2f + rng.random() * 0.6f
            attractorPhase[a] = rng.random() * 2f * PI.toFloat()
            attractorStrength[a] = 30f + rng.random() * 50f
        }

        // Current attractor positions
        val attractorX = FloatArray(attractorCount)
        val attractorY = FloatArray(attractorCount)
        for (a in 0 until attractorCount) {
            val phase = attractorPhase[a] + time * attractorOrbitFreq[a] * 2f
            attractorX[a] = attractorBaseX[a] + cos(phase) * attractorOrbitR[a]
            attractorY[a] = attractorBaseY[a] + sin(phase) * attractorOrbitR[a]
        }

        val dt = 0.016f
        val totalSteps = (time / dt).toInt().coerceAtLeast(1)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = particleSize
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }

        for (i in 0 until count) {
            val startX = rng.random() * w
            val startY = rng.random() * h
            val colorIdx = rng.integer(0, colors.size - 1)
            val pSize = particleSize * (1f - sizeVariance + rng.random() * sizeVariance * 2f)
            // Per-particle shape for "mixed" mode
            val shapeIdx = rng.integer(0, 3)

            var px = startX
            var py = startY

            val trailX = FloatArray(trailLen)
            val trailY = FloatArray(trailLen)
            var trailHead = 0
            var trailCount = 0

            for (step in 0 until totalSteps) {
                val nx = px / w * noiseScale
                val ny = py / h * noiseScale
                var angle = noise.noise2D(nx, ny + step * dt * 0.1f) * PI.toFloat() * 2f

                // Attractor influence: add a pull toward each attractor
                for (a in 0 until attractorCount) {
                    val dx = attractorX[a] - px
                    val dy = attractorY[a] - py
                    val distSq = dx * dx + dy * dy + 100f
                    val pull = attractorStrength[a] / distSq
                    angle += atan2(dy, dx) * pull
                }

                px += cos(angle) * speed
                py += sin(angle) * speed

                if (px < 0) px += w; if (px >= w) px -= w
                if (py < 0) py += h; if (py >= h) py -= h

                trailX[trailHead] = px
                trailY[trailHead] = py
                trailHead = (trailHead + 1) % trailLen
                if (trailCount < trailLen) trailCount++
            }

            val baseColor = colors[colorIdx % colors.size]

            // Draw based on object type
            val useShape = when (objectType) {
                "mixed" -> shapeIdx
                "circle" -> 0
                "square" -> 1
                "triangle" -> 2
                "line" -> 3
                else -> 0
            }

            if (useShape == 3 || trailFraction > 0.1f) {
                // Draw trail as polyline
                if (trailCount >= 2) {
                    // Draw in segments for alpha gradient
                    val segCount = minOf(3, trailCount - 1)
                    val segSize = maxOf(1, trailCount / segCount)

                    for (seg in 0 until segCount) {
                        val path = Path()
                        val startIdx = seg * segSize
                        val endIdx = if (seg == segCount - 1) trailCount else (seg + 1) * segSize + 1

                        val oldest = if (trailCount < trailLen) 0 else trailHead
                        val idx0 = (oldest + startIdx) % trailLen
                        path.moveTo(trailX[idx0], trailY[idx0])

                        for (k in startIdx + 1 until endIdx) {
                            val idx = (oldest + k) % trailLen
                            val prevIdx = (oldest + k - 1) % trailLen
                            val dx = trailX[idx] - trailX[prevIdx]
                            val dy = trailY[idx] - trailY[prevIdx]
                            if (dx * dx + dy * dy > (w * 0.25f) * (w * 0.25f)) {
                                path.moveTo(trailX[idx], trailY[idx])
                            } else {
                                path.lineTo(trailX[idx], trailY[idx])
                            }
                        }

                        val alpha = (50 + (seg.toFloat() / segCount * 180).toInt()).coerceIn(50, 230)
                        paint.strokeWidth = pSize * (0.5f + seg.toFloat() / segCount * 0.5f)
                        paint.color = Color.argb(alpha, Color.red(baseColor), Color.green(baseColor), Color.blue(baseColor))
                        canvas.drawPath(path, paint)
                    }
                }
            }

            // Draw head shape
            if (trailCount > 0 && useShape != 3) {
                val headIdx = (trailHead - 1 + trailLen) % trailLen
                val hx = trailX[headIdx]
                val hy = trailY[headIdx]
                fillPaint.color = Color.argb(220, Color.red(baseColor), Color.green(baseColor), Color.blue(baseColor))

                when (useShape) {
                    0 -> canvas.drawCircle(hx, hy, pSize * 0.5f, fillPaint)
                    1 -> canvas.drawRect(
                        hx - pSize * 0.4f, hy - pSize * 0.4f,
                        hx + pSize * 0.4f, hy + pSize * 0.4f, fillPaint
                    )
                    2 -> {
                        val triPath = Path()
                        triPath.moveTo(hx, hy - pSize * 0.5f)
                        triPath.lineTo(hx - pSize * 0.43f, hy + pSize * 0.25f)
                        triPath.lineTo(hx + pSize * 0.43f, hy + pSize * 0.25f)
                        triPath.close()
                        canvas.drawPath(triPath, fillPaint)
                    }
                }
            }
        }

        // Draw attractor glows
        if (attractorCount > 0) {
            val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
            }
            for (a in 0 until attractorCount) {
                val ac = colors[(a + 1) % colors.size]
                // Outer glow
                glowPaint.color = Color.argb(30, Color.red(ac), Color.green(ac), Color.blue(ac))
                canvas.drawCircle(attractorX[a], attractorY[a], dim * 0.04f, glowPaint)
                // Inner glow
                glowPaint.color = Color.argb(80, Color.red(ac), Color.green(ac), Color.blue(ac))
                canvas.drawCircle(attractorX[a], attractorY[a], dim * 0.015f, glowPaint)
                // Core
                glowPaint.color = Color.argb(200, 255, 255, 255)
                canvas.drawCircle(attractorX[a], attractorY[a], dim * 0.005f, glowPaint)
            }
        }
    }

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float {
        val count = (params["particleCount"] as? Number)?.toFloat() ?: 2000f
        return (count / 5000f).coerceIn(0.2f, 1f)
    }
}
