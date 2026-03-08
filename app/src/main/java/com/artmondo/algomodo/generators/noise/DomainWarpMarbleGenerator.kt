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

class DomainWarpMarbleGenerator : Generator {

    override val id = "domain-warp-marble"
    override val family = "noise"
    override val styleName = "Domain Warp Marble"
    override val definition =
        "Marble-textured domain warp using sine waves perturbed by noise to create realistic veining."
    override val algorithmNotes =
        "Core pattern: sin(x * bands + turbulence * fbm(warped coords)). " +
        "Coordinates are warped by noise displacement (optionally double-warped). " +
        "Vein sharpness controls sine falloff. Turbulence mode uses abs(noise) for chaotic patterns. " +
        "Multiple animation modes: flow, drift, pulse."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema: List<Parameter> = listOf(
        Parameter.NumberParam("Scale", "scale", ParamGroup.COMPOSITION, "", 0.5f, 8f, 0.1f, 2.5f),
        Parameter.NumberParam("Warp Strength", "warpStrength", ParamGroup.COMPOSITION, "Coordinate displacement intensity", 0f, 3f, 0.05f, 1.2f),
        Parameter.NumberParam("Warp Scale", "warpScale", ParamGroup.COMPOSITION, "Frequency of the warp field", 0.5f, 6f, 0.1f, 2.0f),
        Parameter.NumberParam("Marble Bands", "bands", ParamGroup.GEOMETRY, "Sine-band striations", 1f, 20f, 1f, 6f),
        Parameter.NumberParam("Octaves", "octaves", ParamGroup.GEOMETRY, "", 1f, 8f, 1f, 5f),
        Parameter.NumberParam("Smoothness", "gain", ParamGroup.TEXTURE, "", 0.2f, 0.8f, 0.05f, 0.5f),
        Parameter.NumberParam("Vein Sharpness", "veinSharpness", ParamGroup.TEXTURE, ">1 = thinner veins, <1 = wider veins", 0.5f, 4.0f, 0.1f, 1.0f),
        Parameter.BooleanParam("Turbulence", "turbulence", ParamGroup.TEXTURE, "Use abs(noise) for chaotic patterns", false),
        Parameter.BooleanParam("Double Warp", "doubleWarp", ParamGroup.COMPOSITION, "Second warp pass for more complexity", true),
        Parameter.SelectParam("Anim Mode", "animMode", ParamGroup.FLOW_MOTION, "flow: veins morph | drift: translate | pulse: warp breathes", listOf("flow", "drift", "pulse"), "flow"),
        Parameter.NumberParam("Speed", "speed", ParamGroup.FLOW_MOTION, "Animation speed", 0.1f, 3.0f, 0.1f, 0.5f)
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "scale" to 2.5f, "warpStrength" to 1.2f, "warpScale" to 2.0f,
        "bands" to 6f, "octaves" to 5f, "gain" to 0.5f, "veinSharpness" to 1.0f,
        "turbulence" to false, "doubleWarp" to true,
        "animMode" to "flow", "speed" to 0.5f
    )

    override fun renderCanvas(
        canvas: Canvas, bitmap: Bitmap, params: Map<String, Any>,
        seed: Int, palette: Palette, quality: Quality, time: Float
    ) {
        val w = bitmap.width; val h = bitmap.height
        val scale = (params["scale"] as? Number)?.toFloat() ?: 2.5f
        val warpStrength = (params["warpStrength"] as? Number)?.toFloat() ?: 1.2f
        val warpScale = (params["warpScale"] as? Number)?.toFloat() ?: 2.0f
        val bands = (params["bands"] as? Number)?.toFloat() ?: 6f
        val octaves = (params["octaves"] as? Number)?.toInt() ?: 5
        val gain = (params["gain"] as? Number)?.toFloat() ?: 0.5f
        val veinSharpness = (params["veinSharpness"] as? Number)?.toFloat() ?: 1.0f
        val useTurbulence = (params["turbulence"] as? Boolean) ?: false
        val doubleWarp = (params["doubleWarp"] as? Boolean) ?: true
        val animMode = (params["animMode"] as? String) ?: "flow"
        val speed = (params["speed"] as? Number)?.toFloat() ?: 0.5f

        val noise = SimplexNoise(seed)
        val pixels = IntArray(w * h)
        val invScale = scale / w.toFloat()
        val warpInvScale = warpScale / w.toFloat()

        // Decorrelation offsets
        val warpOffX = 3.7f; val warpOffY = 7.1f
        val warpOff2X = 11.3f; val warpOff2Y = 2.8f

        // Animation offsets
        val flowTimeA = time * speed * 0.2f
        val flowTimeB = time * speed * 0.15f
        val effectiveWarp = if (animMode == "pulse")
            warpStrength * (1f + 0.4f * sin(time * speed * 0.7f)) else warpStrength

        val step = if (quality == Quality.DRAFT) 2 else 1

        for (py in 0 until h step step) {
            for (px in 0 until w step step) {
                var baseX = px * invScale
                var baseY = py * invScale

                // Base animation
                when (animMode) {
                    "drift" -> { baseX += time * speed * 0.25f; baseY += time * speed * 0.15f }
                    "flow" -> { baseX += time * speed * 0.08f; baseY += time * speed * 0.05f }
                    // pulse: handled via effectiveWarp
                    else -> { baseX += time * speed * 0.08f; baseY += time * speed * 0.05f }
                }

                // First domain warp
                val warpSrcX = px * warpInvScale
                val warpSrcY = py * warpInvScale
                val warp1X: Float; val warp1Y: Float
                if (animMode == "flow") {
                    warp1X = noise.fbm(warpSrcX + warpOffX + flowTimeA, warpSrcY + warpOffY + flowTimeB, octaves, 2f, gain) * effectiveWarp
                    warp1Y = noise.fbm(warpSrcX + warpOffY + flowTimeB, warpSrcY + warpOffX + flowTimeA, octaves, 2f, gain) * effectiveWarp
                } else {
                    warp1X = noise.fbm(warpSrcX + warpOffX, warpSrcY + warpOffY, octaves, 2f, gain) * effectiveWarp
                    warp1Y = noise.fbm(warpSrcX + warpOffY, warpSrcY + warpOffX, octaves, 2f, gain) * effectiveWarp
                }
                var wx = baseX + warp1X
                var wy = baseY + warp1Y

                // Optional second warp pass
                if (doubleWarp) {
                    val warp2X: Float; val warp2Y: Float
                    if (animMode == "flow") {
                        warp2X = noise.fbm(wx + warpOff2X + flowTimeB * 0.7f, wy + warpOff2Y + flowTimeA * 0.7f, 3, 2f, gain) * effectiveWarp * 0.5f
                        warp2Y = noise.fbm(wx + warpOff2Y + flowTimeA * 0.7f, wy + warpOff2X + flowTimeB * 0.7f, 3, 2f, gain) * effectiveWarp * 0.5f
                    } else {
                        warp2X = noise.fbm(wx + warpOff2X, wy + warpOff2Y, 3, 2f, gain) * effectiveWarp * 0.5f
                        warp2Y = noise.fbm(wx + warpOff2Y, wy + warpOff2X, 3, 2f, gain) * effectiveWarp * 0.5f
                    }
                    wx += warp2X; wy += warp2Y
                }

                // Noise perturbation
                val turb = if (useTurbulence)
                    noise.turbulence(wx, wy, octaves, 2f, gain)
                else
                    noise.fbm(wx, wy, octaves, 2f, gain)
                val turbScale = if (useTurbulence) turb * 2f else turb

                // Classic marble: sin(coordinate * bands + noise perturbation)
                val marble = sin((wx + wy) * bands + turbScale * PI.toFloat())

                // Normalise [-1, 1] to [0, 1] then apply vein sharpness
                var t = ((marble + 1f) * 0.5f).coerceIn(0f, 1f)
                if (veinSharpness != 1f) {
                    t = t.pow(veinSharpness)
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
        val doubleWarp = (params["doubleWarp"] as? Boolean) ?: true
        return if (doubleWarp) 0.7f else 0.5f
    }
}
