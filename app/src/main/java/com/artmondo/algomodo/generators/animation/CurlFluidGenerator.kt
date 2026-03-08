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
 * Curl noise fluid simulation.
 *
 * Particles follow the curl of a 2D simplex noise potential field. The curl
 * (perpendicular to the noise gradient) produces divergence-free flow,
 * giving a realistic fluid appearance. Particles are rendered as flowing line
 * trails that accumulate density, producing a smooth fluid aesthetic.
 */
class CurlFluidGenerator : Generator {

    override val id = "curl-fluid"
    override val family = "animation"
    override val styleName = "Curl Fluid"
    override val definition = "Particles following curl noise flow — divergence-free fluid-like motion."
    override val algorithmNotes =
        "The curl of a 2D scalar field F is (dF/dy, -dF/dx). We compute this numerically " +
        "by sampling noise at small offsets: curl_x = (noise(x, y+eps) - noise(x, y-eps)) / (2*eps), " +
        "curl_y = -(noise(x+eps, y) - noise(x-eps, y)) / (2*eps). Particles are advected along " +
        "this field. Each particle's recent trail is drawn as a polyline with fading alpha. " +
        "Because curl fields are divergence-free, particles neither bunch nor disperse."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema = listOf(
        Parameter.NumberParam(
            name = "Particle Count",
            key = "particleCount",
            group = ParamGroup.COMPOSITION,
            help = null,
            min = 500f, max = 10000f, step = 500f, default = 4000f
        ),
        Parameter.NumberParam(
            name = "Noise Scale",
            key = "noiseScale",
            group = ParamGroup.COMPOSITION,
            help = "Spatial scale of the curl field",
            min = 0.2f, max = 5f, step = 0.1f, default = 1.2f
        ),
        Parameter.NumberParam(
            name = "Speed",
            key = "speed",
            group = ParamGroup.FLOW_MOTION,
            help = null,
            min = 0.5f, max = 8f, step = 0.25f, default = 3.0f
        ),
        Parameter.NumberParam(
            name = "Trail Decay",
            key = "trailDecay",
            group = ParamGroup.TEXTURE,
            help = "Trail fade rate (lower = longer)",
            min = 0.005f, max = 0.2f, step = 0.005f, default = 0.025f
        ),
        Parameter.NumberParam(
            name = "Evolution",
            key = "evolution",
            group = ParamGroup.FLOW_MOTION,
            help = "How fast the field evolves",
            min = 0f, max = 0.3f, step = 0.005f, default = 0.05f
        ),
        Parameter.NumberParam(
            name = "Line Width",
            key = "lineWidth",
            group = ParamGroup.GEOMETRY,
            help = null,
            min = 0.25f, max = 3f, step = 0.25f, default = 0.75f
        ),
        Parameter.SelectParam(
            name = "Color Mode",
            key = "colorMode",
            group = ParamGroup.COLOR,
            help = null,
            options = listOf("palette", "velocity", "position"),
            default = "palette"
        )
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "particleCount" to 4000f,
        "noiseScale" to 1.2f,
        "speed" to 3.0f,
        "trailDecay" to 0.025f,
        "evolution" to 0.05f,
        "lineWidth" to 0.75f,
        "colorMode" to "palette"
    )

    /**
     * Compute the curl of the noise field at (x, y) using central differences.
     */
    private fun curlNoise(
        noise: SimplexNoise,
        x: Float, y: Float,
        scale: Float,
        timeOffset: Float
    ): Pair<Float, Float> {
        val eps = 0.01f
        val nx = x * scale
        val ny = y * scale

        val dFdy = (noise.noise2D(nx, ny + eps + timeOffset) -
                noise.noise2D(nx, ny - eps + timeOffset)) / (2f * eps)
        val dFdx = (noise.noise2D(nx + eps, ny + timeOffset) -
                noise.noise2D(nx - eps, ny + timeOffset)) / (2f * eps)

        return Pair(dFdy, -dFdx)
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
        val w = bitmap.width.toFloat()
        val h = bitmap.height.toFloat()
        val dim = min(w, h)

        val particleCount = ((params["particleCount"] as? Number)?.toInt() ?: 4000).let {
            when (quality) {
                Quality.DRAFT -> (it * 0.3f).toInt()
                Quality.BALANCED -> it
                Quality.ULTRA -> (it * 1.5f).toInt()
            }
        }
        val noiseScale = (params["noiseScale"] as? Number)?.toFloat() ?: 1.2f
        val speed = (params["speed"] as? Number)?.toFloat() ?: 3f
        val evolution = (params["evolution"] as? Number)?.toFloat() ?: 0.05f
        val lineWidth = (params["lineWidth"] as? Number)?.toFloat() ?: 0.75f
        val colorMode = (params["colorMode"] as? String) ?: "palette"

        val rng = SeededRNG(seed)
        val noise = SimplexNoise(seed)
        val colors = palette.colorInts()

        // Dark background
        canvas.drawColor(Color.rgb(4, 4, 8))

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = lineWidth + 1f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        // Simulation parameters
        val dt = 0.016f
        // Trail length: how many recent positions to draw as a polyline
        val trailLen = when (quality) {
            Quality.DRAFT -> 30
            Quality.BALANCED -> 50
            Quality.ULTRA -> 70
        }
        // Total simulation steps from time=0 to now
        val totalSteps = (time / dt).toInt().coerceAtLeast(1)
        val timeOffset = time * evolution

        for (i in 0 until particleCount) {
            var px = rng.random() * w
            var py = rng.random() * h
            val colorIdx = i % colors.size
            val baseColor = colors[colorIdx]

            // Fast-forward to near the end, only keeping trail positions
            val drawStart = (totalSteps - trailLen).coerceAtLeast(0)

            // Skip steps quickly
            for (step in 0 until drawStart) {
                val nx = px / dim
                val ny = py / dim
                val stepTime = timeOffset + step * dt * evolution * 0.5f
                val (cx, cy) = curlNoise(noise, nx, ny, noiseScale, stepTime)
                px += cx * speed
                py += cy * speed
                if (px < 0) px += w; if (px >= w) px -= w
                if (py < 0) py += h; if (py >= h) py -= h
            }

            // Collect trail positions
            val trailX = FloatArray(trailLen)
            val trailY = FloatArray(trailLen)
            var trailCount = 0

            for (step in drawStart until totalSteps) {
                val nx = px / dim
                val ny = py / dim
                val stepTime = timeOffset + step * dt * evolution * 0.5f
                val (cx, cy) = curlNoise(noise, nx, ny, noiseScale, stepTime)
                px += cx * speed
                py += cy * speed
                if (px < 0) px += w; if (px >= w) px -= w
                if (py < 0) py += h; if (py >= h) py -= h

                if (trailCount < trailLen) {
                    trailX[trailCount] = px
                    trailY[trailCount] = py
                    trailCount++
                }
            }

            // Draw the trail as a polyline with fading alpha
            if (trailCount >= 2) {
                // Draw in segments for alpha gradient
                val segCount = minOf(4, trailCount - 1)
                val segSize = trailCount / segCount

                for (seg in 0 until segCount) {
                    val path = Path()
                    val startIdx = seg * segSize
                    val endIdx = if (seg == segCount - 1) trailCount else (seg + 1) * segSize + 1

                    path.moveTo(trailX[startIdx], trailY[startIdx])
                    var valid = true

                    for (k in startIdx + 1 until endIdx) {
                        // Skip large jumps (wrapping)
                        val dx = trailX[k] - trailX[k - 1]
                        val dy = trailY[k] - trailY[k - 1]
                        if (dx * dx + dy * dy > dim * dim * 0.04f) {
                            path.moveTo(trailX[k], trailY[k])
                        } else {
                            path.lineTo(trailX[k], trailY[k])
                        }
                    }

                    // Alpha increases toward the head of the trail
                    val alpha = (40 + (seg.toFloat() / segCount * 180).toInt()).coerceIn(40, 220)

                    val c = when (colorMode) {
                        "velocity" -> {
                            // Color based on local curl magnitude
                            val midIdx = (startIdx + endIdx) / 2
                            val nx = trailX[midIdx.coerceAtMost(trailCount - 1)] / dim
                            val ny = trailY[midIdx.coerceAtMost(trailCount - 1)] / dim
                            val (curlX, curlY) = curlNoise(noise, nx, ny, noiseScale, timeOffset)
                            val mag = sqrt(curlX * curlX + curlY * curlY).coerceIn(0f, 1f)
                            palette.lerpColor(mag)
                        }
                        "position" -> {
                            val midIdx = (startIdx + endIdx) / 2
                            val posVal = (trailX[midIdx.coerceAtMost(trailCount - 1)] / w +
                                    trailY[midIdx.coerceAtMost(trailCount - 1)] / h) * 0.5f
                            palette.lerpColor(posVal.coerceIn(0f, 1f))
                        }
                        else -> baseColor
                    }

                    paint.color = Color.argb(
                        alpha,
                        Color.red(c),
                        Color.green(c),
                        Color.blue(c)
                    )
                    canvas.drawPath(path, paint)
                }
            }
        }
    }

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float {
        val count = (params["particleCount"] as? Number)?.toFloat() ?: 4000f
        return (count / 8000f).coerceIn(0.1f, 1f)
    }
}
