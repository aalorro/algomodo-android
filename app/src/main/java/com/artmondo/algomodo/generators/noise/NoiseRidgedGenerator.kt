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

class NoiseRidgedGenerator : Generator {

    override val id = "noise-ridged"
    override val family = "noise"
    override val styleName = "Ridged Multifractal"
    override val definition =
        "Ridged multifractal noise that creates sharp, ridge-like mountain terrain patterns."
    override val algorithmNotes =
        "For each octave: signal = (offset - |noise|)^sharpness, accumulated with gain cascade. " +
        "Ridge offset, sharpness, domain warping, and multiple color/animation modes supported."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema: List<Parameter> = listOf(
        Parameter.NumberParam("Scale", "scale", ParamGroup.COMPOSITION, "", 0.5f, 10f, 0.5f, 2f),
        Parameter.NumberParam("Octaves", "octaves", ParamGroup.COMPOSITION, "Number of ridged octaves", 1f, 10f, 1f, 6f),
        Parameter.NumberParam("Lacunarity", "lacunarity", ParamGroup.GEOMETRY, "", 1.5f, 4.0f, 0.1f, 2.0f),
        Parameter.NumberParam("Gain", "gain", ParamGroup.GEOMETRY, "Amplitude weight per octave and cascade strength", 0.1f, 0.9f, 0.05f, 0.5f),
        Parameter.NumberParam("Ridge Offset", "offset", ParamGroup.TEXTURE, "Ridge height offset — 1.0 = sharp peaks; lower = softer", 0.5f, 1.5f, 0.05f, 1.0f),
        Parameter.NumberParam("Sharpness", "sharpness", ParamGroup.TEXTURE, "Exponent on ridge signal — higher = knife-edge ridges", 1.0f, 5.0f, 0.5f, 2.0f),
        Parameter.NumberParam("Warp Amount", "warpAmount", ParamGroup.COMPOSITION, "Domain warping — 0 = off", 0f, 2f, 0.1f, 0f),
        Parameter.SelectParam("Color Mode", "colorMode", ParamGroup.COLOR, "palette | bands | peaks (ridges lit, valleys dark)", listOf("palette", "bands", "peaks"), "palette"),
        Parameter.NumberParam("Band Count", "bandCount", ParamGroup.COLOR, "Contour bands (bands mode)", 2f, 24f, 1f, 8f),
        Parameter.SelectParam("Anim Mode", "animMode", ParamGroup.FLOW_MOTION, "drift | rotate | sculpt (ridge offset oscillates)", listOf("drift", "rotate", "sculpt"), "drift"),
        Parameter.NumberParam("Speed", "speed", ParamGroup.FLOW_MOTION, "", 0.1f, 3.0f, 0.1f, 0.5f)
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "scale" to 2f, "octaves" to 6f, "lacunarity" to 2.0f, "gain" to 0.5f,
        "offset" to 1.0f, "sharpness" to 2.0f, "warpAmount" to 0f,
        "colorMode" to "palette", "bandCount" to 8f,
        "animMode" to "drift", "speed" to 0.5f
    )

    override fun renderCanvas(
        canvas: Canvas, bitmap: Bitmap, params: Map<String, Any>,
        seed: Int, palette: Palette, quality: Quality, time: Float
    ) {
        val w = bitmap.width; val h = bitmap.height
        val scale = (params["scale"] as? Number)?.toFloat() ?: 2f
        val octaves = (params["octaves"] as? Number)?.toInt() ?: 6
        val lacunarity = (params["lacunarity"] as? Number)?.toFloat() ?: 2f
        val gain = (params["gain"] as? Number)?.toFloat() ?: 0.5f
        val offset = (params["offset"] as? Number)?.toFloat() ?: 1.0f
        val sharpness = (params["sharpness"] as? Number)?.toFloat() ?: 2.0f
        val warpAmount = (params["warpAmount"] as? Number)?.toFloat() ?: 0f
        val colorMode = (params["colorMode"] as? String) ?: "palette"
        val bandCount = (params["bandCount"] as? Number)?.toInt() ?: 8
        val animMode = (params["animMode"] as? String) ?: "drift"
        val speed = (params["speed"] as? Number)?.toFloat() ?: 0.5f

        val noise = SimplexNoise(seed)
        val pixels = IntArray(w * h)
        val invScale = scale / w.toFloat()
        val cx = w / 2f * invScale; val cy = h / 2f * invScale

        // Sculpt mode: oscillate ridge offset over time
        val effectiveOffset = if (animMode == "sculpt")
            offset + 0.3f * sin(time * speed * 0.8f) else offset

        val step = if (quality == Quality.DRAFT) 2 else 1

        for (py in 0 until h step step) {
            for (px in 0 until w step step) {
                var nx = px * invScale
                var ny = py * invScale

                // Animation
                when (animMode) {
                    "drift", "sculpt" -> { nx += time * speed * 0.3f; ny += time * speed * 0.2f }
                    "rotate" -> {
                        val dx = nx - cx; val dy = ny - cy
                        val angle = time * speed * 0.3f
                        nx = cx + dx * cos(angle) - dy * sin(angle)
                        ny = cy + dx * sin(angle) + dy * cos(angle)
                    }
                }

                // Domain warp
                if (warpAmount > 0f) {
                    val wx = noise.fbm(nx + 5.2f, ny + 1.3f, 3, lacunarity, gain)
                    val wy = noise.fbm(nx + 1.7f, ny + 9.2f, 3, lacunarity, gain)
                    nx += wx * warpAmount; ny += wy * warpAmount
                }

                // Ridged multifractal with offset and gain cascade
                var value = 0f
                var amplitude = 1f
                var frequency = 1f
                var weight = 1f
                var maxValue = 0f

                for (i in 0 until octaves) {
                    val n = noise.noise2D(nx * frequency, ny * frequency)
                    var signal = effectiveOffset - abs(n)
                    signal = signal * signal // square for ridge sharpness
                    signal *= weight
                    weight = (signal * gain).coerceIn(0f, 1f) // cascade: bright ridges enhance next octave
                    value += signal * amplitude
                    maxValue += amplitude
                    amplitude *= gain
                    frequency *= lacunarity
                }

                var t = (value / maxValue).coerceIn(0f, 1f)
                if (sharpness != 1f) t = t.pow(sharpness)

                val color = when (colorMode) {
                    "bands" -> {
                        val band = (t * bandCount).toInt().coerceIn(0, bandCount - 1)
                        palette.lerpColor(band.toFloat() / (bandCount - 1).coerceAtLeast(1))
                    }
                    "peaks" -> {
                        // Ridges bright with palette, valleys fade to black
                        val dimmed = t * t // darken valleys more aggressively
                        palette.lerpColor(dimmed)
                    }
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

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float {
        val octaves = (params["octaves"] as? Number)?.toInt() ?: 6
        return (octaves / 8f).coerceIn(0.3f, 1f)
    }
}
