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

class NoiseDomainWarpGenerator : Generator {

    override val id = "noise-domain-warp"
    override val family = "noise"
    override val styleName = "Domain Warp Noise"
    override val definition =
        "Iterative domain warping that distorts noise coordinates with noise, producing swirling, organic patterns."
    override val algorithmNotes =
        "For each pixel: start with base coordinate, then for each warp iteration evaluate FBM noise " +
        "and use the result to offset x/y by warp strength. Final value supports smooth, ridged, or " +
        "turbulent readout. Band quantization and multiple animation modes supported."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema: List<Parameter> = listOf(
        Parameter.NumberParam("Scale", "scale", ParamGroup.COMPOSITION, "", 0.5f, 8f, 0.5f, 2.0f),
        Parameter.NumberParam("Octaves", "octaves", ParamGroup.COMPOSITION, "Octaves for final readout noise", 1f, 8f, 1f, 5f),
        Parameter.NumberParam("Warp Strength", "warpStrength", ParamGroup.COMPOSITION, "How far coordinates are displaced", 0.0f, 4.0f, 0.1f, 1.5f),
        Parameter.NumberParam("Warp Octaves", "warpOctaves", ParamGroup.GEOMETRY, "Complexity of the warp field", 1f, 6f, 1f, 3f),
        Parameter.SelectParam("Iterations", "iterations", ParamGroup.GEOMETRY, "1: single warp | 2: double | 3: triple", listOf("1", "2", "3"), "1"),
        Parameter.SelectParam("Readout Style", "readoutStyle", ParamGroup.TEXTURE, "smooth | ridged | turbulent", listOf("smooth", "ridged", "turbulent"), "smooth"),
        Parameter.SelectParam("Color Mode", "colorMode", ParamGroup.COLOR, "", listOf("palette", "bands"), "palette"),
        Parameter.NumberParam("Band Count", "bandCount", ParamGroup.COLOR, "Contour bands (bands mode)", 2f, 24f, 1f, 8f),
        Parameter.SelectParam("Anim Mode", "animMode", ParamGroup.FLOW_MOTION, "drift | rotate | flow (warp morphs independently)", listOf("drift", "rotate", "flow"), "flow"),
        Parameter.NumberParam("Speed", "speed", ParamGroup.FLOW_MOTION, "", 0.1f, 3.0f, 0.1f, 0.5f)
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "scale" to 2.0f, "octaves" to 5f, "warpStrength" to 1.5f,
        "warpOctaves" to 3f, "iterations" to "1", "readoutStyle" to "smooth",
        "colorMode" to "palette", "bandCount" to 8f, "animMode" to "flow", "speed" to 0.5f
    )

    override fun renderCanvas(
        canvas: Canvas, bitmap: Bitmap, params: Map<String, Any>,
        seed: Int, palette: Palette, quality: Quality, time: Float
    ) {
        val w = bitmap.width; val h = bitmap.height
        val scale = (params["scale"] as? Number)?.toFloat() ?: 2.0f
        val octaves = (params["octaves"] as? Number)?.toInt() ?: 5
        val warpStrength = (params["warpStrength"] as? Number)?.toFloat() ?: 1.5f
        val warpOctaves = (params["warpOctaves"] as? Number)?.toInt() ?: 3
        val iterations = ((params["iterations"] as? String) ?: "1").toIntOrNull() ?: 1
        val readoutStyle = (params["readoutStyle"] as? String) ?: "smooth"
        val colorMode = (params["colorMode"] as? String) ?: "palette"
        val bandCount = (params["bandCount"] as? Number)?.toInt() ?: 8
        val animMode = (params["animMode"] as? String) ?: "flow"
        val speed = (params["speed"] as? Number)?.toFloat() ?: 0.5f

        val noise = SimplexNoise(seed)
        val pixels = IntArray(w * h)
        val invScale = scale / w.toFloat()
        val cxCenter = w / 2f * invScale; val cyCenter = h / 2f * invScale

        // Decorrelation offsets for warp channels
        val offsetAx = 1.7f; val offsetAy = 9.2f
        val offsetBx = 5.3f; val offsetBy = 1.3f

        // Flow mode: warp field gets its own time offset
        val flowTimeX = time * speed * 0.2f
        val flowTimeY = time * speed * 0.15f

        val step = if (quality == Quality.DRAFT) 2 else 1

        for (py in 0 until h step step) {
            for (px in 0 until w step step) {
                var cx = px * invScale
                var cy = py * invScale

                // Base animation
                when (animMode) {
                    "drift" -> { cx += time * speed * 0.3f; cy += time * speed * 0.2f }
                    "rotate" -> {
                        val dx = cx - cxCenter; val dy = cy - cyCenter
                        val angle = time * speed * 0.3f
                        cx = cxCenter + dx * cos(angle) - dy * sin(angle)
                        cy = cyCenter + dx * sin(angle) + dy * cos(angle)
                    }
                    "flow" -> { cx += time * speed * 0.1f; cy += time * speed * 0.07f }
                }

                // Iterative domain warping
                for (i in 0 until iterations) {
                    val warpX = if (animMode == "flow")
                        noise.fbm(cx + offsetAx + flowTimeX * (i + 1), cy + offsetAy + flowTimeY * (i + 1), warpOctaves)
                    else
                        noise.fbm(cx + offsetAx, cy + offsetAy, warpOctaves)
                    val warpY = if (animMode == "flow")
                        noise.fbm(cx + offsetBx + flowTimeY * (i + 1), cy + offsetBy + flowTimeX * (i + 1), warpOctaves)
                    else
                        noise.fbm(cx + offsetBx, cy + offsetBy, warpOctaves)
                    cx += warpX * warpStrength
                    cy += warpY * warpStrength
                }

                // Final readout at warped coordinate
                val value = when (readoutStyle) {
                    "ridged" -> noise.ridged(cx, cy, octaves)
                    "turbulent" -> noise.turbulence(cx, cy, octaves)
                    else -> noise.fbm(cx, cy, octaves)
                }

                var t = when (readoutStyle) {
                    "ridged", "turbulent" -> value.coerceIn(0f, 1f)
                    else -> ((value + 1f) * 0.5f).coerceIn(0f, 1f)
                }

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
        val iterations = ((params["iterations"] as? String) ?: "1").toIntOrNull() ?: 1
        val octaves = (params["octaves"] as? Number)?.toInt() ?: 5
        return ((iterations * octaves) / 20f).coerceIn(0.3f, 1f)
    }
}
