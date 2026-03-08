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

class NoiseFbmGenerator : Generator {

    override val id = "noise-fbm"
    override val family = "noise"
    override val styleName = "FBM Noise"
    override val definition =
        "Pure Fractal Brownian Motion noise rendered pixel-by-pixel with configurable colour mapping."
    override val algorithmNotes =
        "Each pixel is evaluated as multi-octave FBM simplex noise. " +
        "'palette' mode smoothly interpolates through the palette. " +
        "'bands' mode quantises the noise into discrete contour bands. " +
        "Domain warping, power curve, and multiple animation modes are supported."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema: List<Parameter> = listOf(
        Parameter.NumberParam("Scale", "scale", ParamGroup.COMPOSITION, "", 0.5f, 10f, 0.5f, 2f),
        Parameter.NumberParam("Octaves", "octaves", ParamGroup.COMPOSITION, "Number of noise layers summed", 1f, 10f, 1f, 6f),
        Parameter.NumberParam("Lacunarity", "lacunarity", ParamGroup.GEOMETRY, "Frequency multiplier per octave", 1.5f, 4.0f, 0.1f, 2.0f),
        Parameter.NumberParam("Gain", "gain", ParamGroup.GEOMETRY, "Amplitude multiplier per octave", 0.2f, 0.8f, 0.05f, 0.5f),
        Parameter.NumberParam("Warp Amount", "warpAmount", ParamGroup.COMPOSITION, "Domain warping for organic distortion", 0f, 2f, 0.1f, 0f),
        Parameter.NumberParam("Power", "power", ParamGroup.TEXTURE, "Gamma curve — >1 darkens lows, <1 lifts shadows", 0.3f, 4.0f, 0.1f, 1.0f),
        Parameter.SelectParam("Color Mode", "colorMode", ParamGroup.COLOR, "", listOf("palette", "bands"), "palette"),
        Parameter.NumberParam("Band Count", "bandCount", ParamGroup.COLOR, "Number of contour bands (bands mode)", 2f, 24f, 1f, 8f),
        Parameter.SelectParam("Anim Mode", "animMode", ParamGroup.FLOW_MOTION, "drift: pan | rotate: spin | pulse: oscillate scale", listOf("drift", "rotate", "pulse"), "drift"),
        Parameter.NumberParam("Speed", "speed", ParamGroup.FLOW_MOTION, "", 0.1f, 3.0f, 0.1f, 0.5f)
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "scale" to 2f, "octaves" to 6f, "lacunarity" to 2.0f, "gain" to 0.5f,
        "warpAmount" to 0f, "power" to 1.0f, "colorMode" to "palette",
        "bandCount" to 8f, "animMode" to "drift", "speed" to 0.5f
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
        val warpAmount = (params["warpAmount"] as? Number)?.toFloat() ?: 0f
        val power = (params["power"] as? Number)?.toFloat() ?: 1.0f
        val colorMode = (params["colorMode"] as? String) ?: "palette"
        val bandCount = (params["bandCount"] as? Number)?.toInt() ?: 8
        val animMode = (params["animMode"] as? String) ?: "drift"
        val speed = (params["speed"] as? Number)?.toFloat() ?: 0.5f

        val noise = SimplexNoise(seed)
        val pixels = IntArray(w * h)
        val invScale = scale / w.toFloat()
        val cx = w / 2f * invScale; val cy = h / 2f * invScale

        val step = if (quality == Quality.DRAFT) 2 else 1

        for (py in 0 until h step step) {
            for (px in 0 until w step step) {
                var nx = px * invScale
                var ny = py * invScale

                // Animation
                when (animMode) {
                    "drift" -> { nx += time * speed * 0.4f; ny += time * speed * 0.25f }
                    "rotate" -> {
                        val dx = nx - cx; val dy = ny - cy
                        val angle = time * speed * 0.3f
                        val cosA = cos(angle); val sinA = sin(angle)
                        nx = cx + dx * cosA - dy * sinA
                        ny = cy + dx * sinA + dy * cosA
                    }
                    "pulse" -> {
                        val s = 1f + 0.2f * sin(time * speed)
                        nx = cx + (nx - cx) * s; ny = cy + (ny - cy) * s
                        nx += time * speed * 0.1f
                    }
                }

                // Domain warp
                if (warpAmount > 0f) {
                    val wx = noise.fbm(nx + 5.2f, ny + 1.3f, 3, lacunarity, gain)
                    val wy = noise.fbm(nx + 1.7f, ny + 9.2f, 3, lacunarity, gain)
                    nx += wx * warpAmount; ny += wy * warpAmount
                }

                var value = noise.fbm(nx, ny, octaves, lacunarity, gain)
                var t = ((value + 1f) * 0.5f).coerceIn(0f, 1f)

                // Power curve
                if (power != 1f) t = t.pow(power)

                // Color mapping
                val color = when (colorMode) {
                    "bands" -> {
                        val band = (t * bandCount).toInt().coerceIn(0, bandCount - 1)
                        palette.lerpColor(band.toFloat() / (bandCount - 1).coerceAtLeast(1))
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
        return (octaves / 10f).coerceIn(0.3f, 1f)
    }
}
