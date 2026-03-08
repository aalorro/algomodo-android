package com.artmondo.algomodo.generators.noise

import android.graphics.Bitmap
import android.graphics.Canvas
import com.artmondo.algomodo.core.rng.SimplexNoise
import com.artmondo.algomodo.data.palettes.Palette
import com.artmondo.algomodo.generators.Generator
import com.artmondo.algomodo.generators.ParamGroup
import com.artmondo.algomodo.generators.Parameter
import com.artmondo.algomodo.generators.Quality
import kotlin.math.*

class NoiseTurbulenceGenerator : Generator {

    override val id = "noise-turbulence"
    override val family = "noise"
    override val styleName = "Turbulence Noise"
    override val definition =
        "Turbulence noise created by summing absolute-value noise octaves, producing billowy, smoke-like patterns."
    override val algorithmNotes =
        "Each octave evaluates |noise(x * freq, y * freq)| and accumulates with decreasing " +
        "amplitude. Erosion weights each octave by the previous. Power curve shapes contrast. " +
        "Heat colormap and band quantization supported."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema: List<Parameter> = listOf(
        Parameter.NumberParam("Scale", "scale", ParamGroup.COMPOSITION, "", 0.5f, 10f, 0.5f, 2.5f),
        Parameter.NumberParam("Octaves", "octaves", ParamGroup.COMPOSITION, "Number of absolute-value octaves", 1f, 10f, 1f, 6f),
        Parameter.NumberParam("Lacunarity", "lacunarity", ParamGroup.GEOMETRY, "", 1.5f, 4.0f, 0.1f, 2.0f),
        Parameter.NumberParam("Gain", "gain", ParamGroup.GEOMETRY, "", 0.2f, 0.8f, 0.05f, 0.5f),
        Parameter.NumberParam("Power", "power", ParamGroup.TEXTURE, "Gamma curve on turbulence output", 0.3f, 4.0f, 0.1f, 1.0f),
        Parameter.NumberParam("Warp Amount", "warpAmount", ParamGroup.COMPOSITION, "Domain warping — 0 = off", 0f, 2f, 0.1f, 0f),
        Parameter.NumberParam("Erosion", "erosion", ParamGroup.TEXTURE, "Weight each octave by the previous — creases erode into valleys", 0f, 1.0f, 0.1f, 0f),
        Parameter.SelectParam("Color Mode", "colorMode", ParamGroup.COLOR, "palette | bands | heat (fire map)", listOf("palette", "bands", "heat"), "palette"),
        Parameter.NumberParam("Band Count", "bandCount", ParamGroup.COLOR, "Contour bands (bands mode)", 2f, 24f, 1f, 6f),
        Parameter.SelectParam("Anim Mode", "animMode", ParamGroup.FLOW_MOTION, "drift | rotate | churn (boiling effect)", listOf("drift", "rotate", "churn"), "drift"),
        Parameter.NumberParam("Speed", "speed", ParamGroup.FLOW_MOTION, "", 0.1f, 3.0f, 0.1f, 0.5f)
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "scale" to 2.5f, "octaves" to 6f, "lacunarity" to 2.0f, "gain" to 0.5f,
        "power" to 1.0f, "warpAmount" to 0f, "erosion" to 0f,
        "colorMode" to "palette", "bandCount" to 6f,
        "animMode" to "drift", "speed" to 0.5f
    )

    override fun renderCanvas(
        canvas: Canvas, bitmap: Bitmap, params: Map<String, Any>,
        seed: Int, palette: Palette, quality: Quality, time: Float
    ) {
        val w = bitmap.width; val h = bitmap.height
        val scale = (params["scale"] as? Number)?.toFloat() ?: 2.5f
        val octaves = (params["octaves"] as? Number)?.toInt() ?: 6
        val lacunarity = (params["lacunarity"] as? Number)?.toFloat() ?: 2.0f
        val gain = (params["gain"] as? Number)?.toFloat() ?: 0.5f
        val power = (params["power"] as? Number)?.toFloat() ?: 1.0f
        val warpAmount = (params["warpAmount"] as? Number)?.toFloat() ?: 0f
        val erosion = (params["erosion"] as? Number)?.toFloat() ?: 0f
        val colorMode = (params["colorMode"] as? String) ?: "palette"
        val bandCount = (params["bandCount"] as? Number)?.toInt() ?: 6
        val animMode = (params["animMode"] as? String) ?: "drift"
        val speed = (params["speed"] as? Number)?.toFloat() ?: 0.5f

        val noise = SimplexNoise(seed)
        val pixels = IntArray(w * h)
        val invScale = scale / w.toFloat()
        val cx = w / 2f * invScale; val cy = h / 2f * invScale
        val isChurn = animMode == "churn"

        val step = if (quality == Quality.DRAFT) 2 else 1

        for (py in 0 until h step step) {
            for (px in 0 until w step step) {
                var nx = px * invScale
                var ny = py * invScale

                // Animation
                when (animMode) {
                    "drift" -> { nx += time * speed * 0.35f; ny += time * speed * 0.2f }
                    "rotate" -> {
                        val dx = nx - cx; val dy = ny - cy
                        val angle = time * speed * 0.3f
                        nx = cx + dx * cos(angle) - dy * sin(angle)
                        ny = cy + dx * sin(angle) + dy * cos(angle)
                    }
                    // "churn" handled per-octave below
                    else -> { nx += time * speed * 0.35f; ny += time * speed * 0.2f }
                }

                // Domain warp
                if (warpAmount > 0f) {
                    val wx = noise.fbm(nx + 5.2f, ny + 1.3f, 3, lacunarity, gain)
                    val wy = noise.fbm(nx + 1.7f, ny + 9.2f, 3, lacunarity, gain)
                    nx += wx * warpAmount; ny += wy * warpAmount
                }

                // Turbulence with erosion and optional churn
                var value = 0f
                var amplitude = 1f
                var frequency = 1f
                var maxValue = 0f
                var prevOctave = 1f

                for (i in 0 until octaves) {
                    var sx = nx * frequency; var sy = ny * frequency
                    if (isChurn) {
                        // Each octave drifts at its own rate
                        sx += time * speed * 0.2f * (i + 1) * 0.7f
                        sy += time * speed * 0.15f * (i + 1) * 0.5f
                    }
                    val n = abs(noise.noise2D(sx, sy))
                    val weight = if (erosion > 0f) amplitude * (1f - erosion + erosion * prevOctave) else amplitude
                    value += weight * n
                    maxValue += weight
                    prevOctave = n
                    amplitude *= gain
                    frequency *= lacunarity
                }

                var t = (value / maxValue).coerceIn(0f, 1f)
                if (power != 1f) t = t.pow(power)

                val color = when (colorMode) {
                    "bands" -> {
                        val band = (t * bandCount).toInt().coerceIn(0, bandCount - 1)
                        palette.lerpColor(band.toFloat() / (bandCount - 1).coerceAtLeast(1))
                    }
                    "heat" -> heatColor(t)
                    else -> palette.lerpColor(t)
                }

                if (step == 1) {
                    pixels[py * w + px] = color
                } else {
                    for (dy in 0 until step) for (dx in 0 until step) {
                        val fx = px + dx; val fy = py + dy
                        if (fx < w && fy < h) pixels[fy * w + fx] = color
                    }
                }
            }
        }

        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
        canvas.drawBitmap(bitmap, 0f, 0f, null)
    }

    private fun heatColor(t: Float): Int {
        // Black → dark red → red → orange → yellow → white
        val r: Int; val g: Int; val b: Int
        when {
            t < 0.2f -> { val s = t / 0.2f; r = (s * 128).toInt(); g = 0; b = 0 }
            t < 0.4f -> { val s = (t - 0.2f) / 0.2f; r = 128 + (s * 127).toInt(); g = 0; b = 0 }
            t < 0.6f -> { val s = (t - 0.4f) / 0.2f; r = 255; g = (s * 165).toInt(); b = 0 }
            t < 0.8f -> { val s = (t - 0.6f) / 0.2f; r = 255; g = 165 + (s * 90).toInt(); b = 0 }
            else -> { val s = (t - 0.8f) / 0.2f; r = 255; g = 255; b = (s * 255).toInt() }
        }
        return (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)
    }

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float {
        val octaves = (params["octaves"] as? Number)?.toInt() ?: 6
        return (octaves / 8f).coerceIn(0.3f, 1f)
    }
}
