package com.artmondo.algomodo.generators.animation

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import com.artmondo.algomodo.core.rng.SeededRNG
import com.artmondo.algomodo.data.palettes.Palette
import com.artmondo.algomodo.generators.Generator
import com.artmondo.algomodo.generators.ParamGroup
import com.artmondo.algomodo.generators.Parameter
import com.artmondo.algomodo.generators.Quality
import kotlin.math.*

/**
 * Overlapping circular wave sources producing interference patterns.
 *
 * Point sources are placed at random seed positions. Each emits a circular wave
 * whose displacement is sin(2*PI*r/wavelength - speed*time) * amplitude * exp(-decay*r).
 * The waves from all sources are summed at each pixel and the resulting value is
 * mapped to the palette, producing classic constructive/destructive interference
 * fringes that animate over time.
 */
class WaveInterferenceGenerator : Generator {

    override val id = "wave-interference"
    override val family = "animation"
    override val styleName = "Wave Interference"
    override val definition = "Circular waves from random sources producing animated interference patterns mapped to the palette."
    override val algorithmNotes =
        "For each pixel and each source i at position (sx_i, sy_i): " +
        "r_i = dist(pixel, source_i); displacement_i = sin(2*PI*r_i/wavelength - speed*time + phase_i) " +
        "* amplitude * exp(-decay * r_i / dim). Total displacement = sum of all displacement_i. " +
        "The value is normalised and mapped to palette.lerpColor(). The exponential decay " +
        "prevents distant sources from dominating."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema = listOf(
        Parameter.NumberParam(
            name = "Sources",
            key = "waveCount",
            group = ParamGroup.COMPOSITION,
            help = "Number of wave emission sources",
            min = 2f, max = 10f, step = 1f, default = 4f
        ),
        Parameter.SelectParam(
            name = "Wave Type",
            key = "waveType",
            group = ParamGroup.COMPOSITION,
            help = "circular: radial rings · spiral: twisted rings · plane: directional beam · mixed: one of each per source",
            options = listOf("circular", "spiral", "plane", "mixed"),
            default = "circular"
        ),
        Parameter.NumberParam(
            name = "Frequency",
            key = "frequency",
            group = ParamGroup.GEOMETRY,
            help = "Spatial frequency of the waves",
            min = 0.5f, max = 6f, step = 0.5f, default = 2f
        ),
        Parameter.NumberParam(
            name = "Spiral Arms",
            key = "spiralArms",
            group = ParamGroup.GEOMETRY,
            help = "Twist multiplier per spiral source (spiral / mixed mode)",
            min = 1f, max = 8f, step = 1f, default = 2f
        ),
        Parameter.NumberParam(
            name = "Speed",
            key = "speed",
            group = ParamGroup.FLOW_MOTION,
            help = "Wave propagation speed",
            min = 0.1f, max = 3f, step = 0.1f, default = 1f
        ),
        Parameter.NumberParam(
            name = "Source Drift",
            key = "sourceMotion",
            group = ParamGroup.FLOW_MOTION,
            help = "Sources orbit their seeded positions — 0 = fully static",
            min = 0f, max = 1f, step = 0.05f, default = 0.3f
        ),
        Parameter.NumberParam(
            name = "Damping",
            key = "damping",
            group = ParamGroup.TEXTURE,
            help = "Amplitude decay with distance from source",
            min = 0f, max = 1f, step = 0.05f, default = 0.3f
        ),
        Parameter.NumberParam(
            name = "Contrast",
            key = "contrast",
            group = ParamGroup.TEXTURE,
            help = "Fringe sharpness — higher pushes toward hard-edged bands",
            min = 0.3f, max = 3f, step = 0.1f, default = 1.2f
        ),
        Parameter.SelectParam(
            name = "Color Mode",
            key = "colorMode",
            group = ParamGroup.COLOR,
            help = "palette: smooth gradient · bichrome: two-tone fringes · phase: wavefront edges highlighted",
            options = listOf("palette", "bichrome", "phase"),
            default = "palette"
        )
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "waveCount" to 4f,
        "waveType" to "circular",
        "frequency" to 2f,
        "spiralArms" to 2f,
        "speed" to 1f,
        "sourceMotion" to 0.3f,
        "damping" to 0.3f,
        "contrast" to 1.2f,
        "colorMode" to "palette"
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
        val w = bitmap.width
        val h = bitmap.height
        val dim = min(w, h).toFloat()

        val sourceCount = (params["waveCount"] as? Number)?.toInt() ?: 4
        val waveType = (params["waveType"] as? String) ?: "circular"
        val frequency = (params["frequency"] as? Number)?.toFloat() ?: 2f
        val spiralArms = (params["spiralArms"] as? Number)?.toFloat() ?: 2f
        val speed = (params["speed"] as? Number)?.toFloat() ?: 1f
        val sourceMotion = (params["sourceMotion"] as? Number)?.toFloat() ?: 0.3f
        val decay = (params["damping"] as? Number)?.toFloat() ?: 0.3f
        val contrast = (params["contrast"] as? Number)?.toFloat() ?: 1.2f
        val colorMode = (params["colorMode"] as? String) ?: "palette"
        val amplitude = 1f

        val rng = SeededRNG(seed)

        val step = when (quality) {
            Quality.DRAFT -> 2
            Quality.BALANCED -> 1
            Quality.ULTRA -> 1
        }

        // Wavelength in pixels — scaled by canvas size so patterns look consistent
        val wavelength = dim / (frequency * 4f)

        // Generate source positions, phase offsets, and drift parameters from seed
        val baseSrcX = FloatArray(sourceCount)
        val baseSrcY = FloatArray(sourceCount)
        val srcPhase = FloatArray(sourceCount)
        val driftAngle = FloatArray(sourceCount)   // orbit angle offset
        val driftRadius = FloatArray(sourceCount)   // orbit radius
        val driftFreq = FloatArray(sourceCount)     // orbit speed
        // Per-source wave type for "mixed" mode
        val srcWaveType = IntArray(sourceCount)     // 0=circular, 1=spiral, 2=plane
        val planeAngle = FloatArray(sourceCount)    // direction for plane waves
        val margin = dim * 0.15f

        for (i in 0 until sourceCount) {
            baseSrcX[i] = margin + rng.random() * (w - 2 * margin)
            baseSrcY[i] = margin + rng.random() * (h - 2 * margin)
            srcPhase[i] = rng.random() * 2f * PI.toFloat()
            driftAngle[i] = rng.random() * 2f * PI.toFloat()
            driftRadius[i] = dim * 0.05f + rng.random() * dim * 0.1f
            driftFreq[i] = 0.3f + rng.random() * 0.7f
            planeAngle[i] = rng.random() * 2f * PI.toFloat()
            srcWaveType[i] = when (waveType) {
                "circular" -> 0
                "spiral" -> 1
                "plane" -> 2
                "mixed" -> i % 3
                else -> 0
            }
        }

        // Compute animated source positions
        val srcX = FloatArray(sourceCount)
        val srcY = FloatArray(sourceCount)
        for (i in 0 until sourceCount) {
            val orbitPhase = driftAngle[i] + time * driftFreq[i] * 2f
            srcX[i] = baseSrcX[i] + cos(orbitPhase) * driftRadius[i] * sourceMotion
            srcY[i] = baseSrcY[i] + sin(orbitPhase) * driftRadius[i] * sourceMotion
        }

        // Pre-compute constants
        val twoPiOverLambda = 2f * PI.toFloat() / wavelength
        val timePhase = speed * time * 4f
        // Decay normalized by canvas dimension so waves propagate across the full canvas
        val normalizedDecay = decay * 3f / dim

        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        for (py in 0 until h step step) {
            for (px in 0 until w step step) {
                var totalDisplacement = 0f

                for (s in 0 until sourceCount) {
                    val dx = px.toFloat() - srcX[s]
                    val dy = py.toFloat() - srcY[s]
                    val r = sqrt(dx * dx + dy * dy)

                    val waveVal: Float = when (srcWaveType[s]) {
                        1 -> {
                            // Spiral wave: add angle-dependent phase
                            val angle = atan2(dy, dx)
                            sin(twoPiOverLambda * r - timePhase + srcPhase[s] + angle * spiralArms)
                        }
                        2 -> {
                            // Plane wave: displacement depends on projection along direction
                            val proj = dx * cos(planeAngle[s]) + dy * sin(planeAngle[s])
                            sin(twoPiOverLambda * proj - timePhase + srcPhase[s])
                        }
                        else -> {
                            // Circular wave
                            sin(twoPiOverLambda * r - timePhase + srcPhase[s])
                        }
                    }

                    val damped = waveVal * amplitude * exp(-normalizedDecay * r)
                    totalDisplacement += damped
                }

                // Normalize
                val maxExpected = sourceCount * amplitude * 0.5f
                var norm = totalDisplacement / maxExpected

                // Apply contrast
                norm = (norm * contrast).coerceIn(-1f, 1f)

                // Map to [0, 1]
                val mapped = norm * 0.5f + 0.5f

                val color = when (colorMode) {
                    "bichrome" -> {
                        // Two-tone: snap to 0 or 1
                        if (mapped > 0.5f) palette.lerpColor(0.85f) else palette.lerpColor(0.15f)
                    }
                    "phase" -> {
                        // Highlight wavefront edges using derivative
                        val edginess = abs(norm)
                        val palVal = mapped
                        val baseColor = palette.lerpColor(palVal)
                        val bright = (edginess * 0.6f + 0.4f)
                        val r2 = (Color.red(baseColor) * bright).toInt().coerceIn(0, 255)
                        val g2 = (Color.green(baseColor) * bright).toInt().coerceIn(0, 255)
                        val b2 = (Color.blue(baseColor) * bright).toInt().coerceIn(0, 255)
                        Color.rgb(r2, g2, b2)
                    }
                    else -> palette.lerpColor(mapped.coerceIn(0f, 1f))
                }

                // Fill block
                for (bdy in 0 until step) {
                    val row = py + bdy
                    if (row >= h) break
                    for (bdx in 0 until step) {
                        val col = px + bdx
                        if (col >= w) break
                        pixels[row * w + col] = color
                    }
                }
            }
        }

        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
        canvas.drawBitmap(bitmap, 0f, 0f, null)
    }

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float {
        val sources = (params["waveCount"] as? Number)?.toFloat() ?: 4f
        return when (quality) {
            Quality.DRAFT -> sources / 16f
            Quality.BALANCED -> sources / 8f
            Quality.ULTRA -> sources / 6f
        }.coerceIn(0.1f, 1f)
    }
}
