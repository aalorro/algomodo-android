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

class FbmTerrainGenerator : Generator {

    override val id = "fbm-terrain"
    override val family = "noise"
    override val styleName = "FBM Terrain"
    override val definition =
        "Fractal Brownian Motion terrain visualization that maps layered noise to palette colors like a topographic height map."
    override val algorithmNotes =
        "Multi-octave FBM noise per pixel with domain warping. " +
        "'ridged' mode inverts abs(noise) for mountain ridges. " +
        "'terraced' mode quantises into elevation steps. " +
        "Contrast scales the value range. Color modes: height (full palette), gradient (center-biased)."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema: List<Parameter> = listOf(
        Parameter.NumberParam("Scale", "scale", ParamGroup.COMPOSITION, "Base frequency of noise", 0.2f, 10f, 0.1f, 2f),
        Parameter.NumberParam("Octaves", "octaves", ParamGroup.COMPOSITION, "Number of noise layers", 1f, 8f, 1f, 4f),
        Parameter.NumberParam("Lacunarity", "lacunarity", ParamGroup.GEOMETRY, "Frequency multiplier per octave", 1.5f, 3.5f, 0.1f, 2.0f),
        Parameter.NumberParam("Gain", "gain", ParamGroup.GEOMETRY, "Amplitude multiplier per octave", 0.2f, 0.9f, 0.05f, 0.5f),
        Parameter.NumberParam("Warp Strength", "warpStrength", ParamGroup.COMPOSITION, "Domain warping intensity", 0f, 2f, 0.1f, 0.5f),
        Parameter.NumberParam("Warp Scale", "warpScale", ParamGroup.COMPOSITION, "Size of warping pattern", 0.2f, 10f, 0.1f, 2f),
        Parameter.SelectParam("Anim Mode", "animMode", ParamGroup.FLOW_MOTION, "drift | rotate | pulse (zoom breath)", listOf("drift", "rotate", "pulse"), "drift"),
        Parameter.NumberParam("Speed", "speed", ParamGroup.FLOW_MOTION, "Animation speed", 0.1f, 3.0f, 0.1f, 0.5f),
        Parameter.SelectParam("Style", "style", ParamGroup.GEOMETRY, "smooth | ridged | terraced", listOf("smooth", "ridged", "terraced"), "smooth"),
        Parameter.NumberParam("Terrace Levels", "terraceLevels", ParamGroup.GEOMETRY, "Height steps (terraced mode)", 4f, 20f, 1f, 8f),
        Parameter.NumberParam("Contrast", "contrast", ParamGroup.TEXTURE, "Increase or decrease variation", 0.5f, 2f, 0.1f, 1f),
        Parameter.SelectParam("Color Mode", "colorMode", ParamGroup.COLOR, "height: full palette | gradient: center-biased", listOf("height", "gradient"), "height")
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "scale" to 2f, "octaves" to 4f, "lacunarity" to 2.0f, "gain" to 0.5f,
        "warpStrength" to 0.5f, "warpScale" to 2f, "style" to "smooth",
        "terraceLevels" to 8f, "contrast" to 1f, "colorMode" to "height",
        "animMode" to "drift", "speed" to 0.5f
    )

    override fun renderCanvas(
        canvas: Canvas, bitmap: Bitmap, params: Map<String, Any>,
        seed: Int, palette: Palette, quality: Quality, time: Float
    ) {
        val w = bitmap.width; val h = bitmap.height
        val scale = (params["scale"] as? Number)?.toFloat() ?: 2f
        val octaves = (params["octaves"] as? Number)?.toInt() ?: 4
        val lacunarity = (params["lacunarity"] as? Number)?.toFloat() ?: 2f
        val gain = (params["gain"] as? Number)?.toFloat() ?: 0.5f
        val warpStrength = (params["warpStrength"] as? Number)?.toFloat() ?: 0.5f
        val warpScale = (params["warpScale"] as? Number)?.toFloat() ?: 2f
        val style = (params["style"] as? String) ?: "smooth"
        val terraceLevels = (params["terraceLevels"] as? Number)?.toInt() ?: 8
        val contrast = (params["contrast"] as? Number)?.toFloat() ?: 1f
        val colorMode = (params["colorMode"] as? String) ?: "height"
        val animMode = (params["animMode"] as? String) ?: "drift"
        val speed = (params["speed"] as? Number)?.toFloat() ?: 0.5f

        val noise = SimplexNoise(seed)
        val pixels = IntArray(w * h)
        val invScale = scale / w.toFloat()
        val warpInvScale = warpScale / w.toFloat()
        val cx = w / 2f * invScale; val cy = h / 2f * invScale

        val step = if (quality == Quality.DRAFT) 2 else 1

        for (py in 0 until h step step) {
            for (px in 0 until w step step) {
                var nx = px * invScale
                var ny = py * invScale

                // Animation
                when (animMode) {
                    "drift" -> { nx += time * speed * 0.3f; ny += time * speed * 0.2f }
                    "rotate" -> {
                        val dx = nx - cx; val dy = ny - cy
                        val angle = time * speed * 0.3f
                        nx = cx + dx * cos(angle) - dy * sin(angle)
                        ny = cy + dx * sin(angle) + dy * cos(angle)
                    }
                    "pulse" -> {
                        val s = 1f + 0.2f * sin(time * speed)
                        nx = cx + (nx - cx) * s; ny = cy + (ny - cy) * s
                        nx += time * speed * 0.1f
                    }
                }

                // Domain warp
                if (warpStrength > 0f) {
                    val wx = noise.fbm(px * warpInvScale + 5.2f, py * warpInvScale + 1.3f, 3, lacunarity, gain)
                    val wy = noise.fbm(px * warpInvScale + 1.7f, py * warpInvScale + 9.2f, 3, lacunarity, gain)
                    nx += wx * warpStrength; ny += wy * warpStrength
                }

                var value = when (style) {
                    "ridged" -> noise.ridged(nx, ny, octaves, lacunarity, gain)
                    else -> noise.fbm(nx, ny, octaves, lacunarity, gain)
                }

                // Normalise
                value = when (style) {
                    "ridged" -> value.coerceIn(0f, 1f)
                    else -> ((value + 1f) * 0.5f).coerceIn(0f, 1f)
                }

                // Contrast
                if (contrast != 1f) {
                    value = ((value - 0.5f) * contrast + 0.5f).coerceIn(0f, 1f)
                }

                // Terracing
                if (style == "terraced") {
                    value = (floor(value * terraceLevels) / (terraceLevels - 1).coerceAtLeast(1)).coerceIn(0f, 1f)
                }

                // Color mode
                val t = when (colorMode) {
                    "gradient" -> {
                        // Center-biased: remap so midtones spread wider
                        val centered = (value - 0.5f) * 2f // [-1, 1]
                        ((centered * abs(centered) + 1f) * 0.5f).coerceIn(0f, 1f)
                    }
                    else -> value
                }

                val color = palette.lerpColor(t)

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
        val octaves = (params["octaves"] as? Number)?.toInt() ?: 4
        return (octaves / 8f).coerceIn(0.3f, 1f)
    }
}
