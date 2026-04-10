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

class NoiseSimplexFieldGenerator : Generator {

    override val id = "noise-simplex-field"
    override val family = "noise"
    override val styleName = "Simplex Noise Field"
    override val definition =
        "Multi-octave simplex noise rendered as colored pixels with a family of noise styles."
    override val algorithmNotes =
        "Samples multi-octave simplex noise at every pixel. Style selects the noise transform: " +
        "smooth fbm, ridged crests (1 - |fbm|)^2, turbulent folds |fbm|, billowing puffs, " +
        "or thin vein-like negative-space lines. Optional domain warping adds organic distortion. " +
        "Color mode supports a smooth palette gradient or hard contour bands. Three animation " +
        "modes: drift (panning), rotate (spin), or pulse (wobble)."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema: List<Parameter> = listOf(
        Parameter.NumberParam("Scale", "scale", ParamGroup.COMPOSITION,
            "Noise frequency", 0.5f, 12f, 0.5f, 3f),
        Parameter.NumberParam("Octaves", "octaves", ParamGroup.COMPOSITION,
            "Noise layers — more = finer detail", 1f, 6f, 1f, 4f),
        Parameter.SelectParam("Style", "style", ParamGroup.GEOMETRY,
            "smooth: classic fbm | ridged: sharp crests | turbulent: folded creases | billow: puffy clouds | veins: thin negative-space lines",
            listOf("smooth", "ridged", "turbulent", "billow", "veins"), "ridged"),
        Parameter.NumberParam("Warp Amount", "warpAmount", ParamGroup.COMPOSITION,
            "Domain warping for organic distortion", 0f, 2f, 0.1f, 0f),
        Parameter.SelectParam("Color Mode", "colorMode", ParamGroup.COLOR,
            "palette: smooth gradient | bands: hard contour steps",
            listOf("palette", "bands"), "palette"),
        Parameter.NumberParam("Band Count", "bandCount", ParamGroup.COLOR,
            "Number of contour bands (bands mode)", 2f, 24f, 1f, 6f),
        Parameter.SelectParam("Anim Mode", "animMode", ParamGroup.FLOW_MOTION,
            "drift: pan through field | rotate: spin around center | pulse: wobble back and forth",
            listOf("drift", "rotate", "pulse"), "drift"),
        Parameter.NumberParam("Speed", "speed", ParamGroup.FLOW_MOTION,
            "", 0.1f, 3.0f, 0.1f, 0.5f)
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "scale" to 3f, "octaves" to 4f, "style" to "ridged", "warpAmount" to 0f,
        "colorMode" to "palette", "bandCount" to 6f, "animMode" to "drift", "speed" to 0.5f
    )

    override fun renderCanvas(
        canvas: Canvas, bitmap: Bitmap, params: Map<String, Any>,
        seed: Int, palette: Palette, quality: Quality, time: Float
    ) {
        val w = bitmap.width; val h = bitmap.height
        val scale = (params["scale"] as? Number)?.toFloat() ?: 3f
        val octaves = ((params["octaves"] as? Number)?.toInt() ?: 4).coerceIn(1, 6)
        val style = (params["style"] as? String) ?: "ridged"
        val warpAmount = (params["warpAmount"] as? Number)?.toFloat() ?: 0f
        val colorMode = (params["colorMode"] as? String) ?: "palette"
        val bandCount = ((params["bandCount"] as? Number)?.toInt() ?: 6).coerceAtLeast(2)
        val animMode = (params["animMode"] as? String) ?: "drift"
        val speed = (params["speed"] as? Number)?.toFloat() ?: 0.5f

        val noise = SimplexNoise(seed)
        val invScale = scale / w.toFloat()
        val centerX = w * 0.5f * invScale
        val centerY = h * 0.5f * invScale

        val styleId = when (style) {
            "ridged" -> 1
            "turbulent" -> 2
            "billow" -> 3
            "veins" -> 4
            else -> 0  // smooth
        }
        val animId = when (animMode) {
            "rotate" -> 1
            "pulse" -> 2
            else -> 0  // drift
        }

        // Per-frame anim transform constants
        val driftX = time * speed * 0.35f
        val driftY = time * speed * 0.2f
        val rotAngle = time * speed * 0.5f
        val rotSin = sin(rotAngle); val rotCos = cos(rotAngle)
        val pulsePhase = time * speed
        val pulseShiftX = sin(pulsePhase) * 0.9f
        val pulseShiftY = cos(pulsePhase * 1.3f) * 0.9f

        // Pre-built palette LUT for fast color mapping
        val lut = palette.buildLut(256)
        val invBandDenom = 1f / (bandCount - 1f)
        val isBands = colorMode == "bands"
        val warpActive = warpAmount > 0f

        val step = if (quality == Quality.DRAFT) 2 else 1
        val pixels = IntArray(w * h)

        for (py in 0 until h step step) {
            val baseNy = py * invScale
            for (px in 0 until w step step) {
                val baseNx = px * invScale

                // Animation transform
                var nx: Float; var ny: Float
                when (animId) {
                    1 -> { // rotate around noise-space center
                        val dx = baseNx - centerX
                        val dy = baseNy - centerY
                        nx = centerX + dx * rotCos - dy * rotSin
                        ny = centerY + dx * rotSin + dy * rotCos
                    }
                    2 -> { // pulse — coordinate wobble
                        nx = baseNx + pulseShiftX
                        ny = baseNy + pulseShiftY
                    }
                    else -> { // drift
                        nx = baseNx + driftX
                        ny = baseNy + driftY
                    }
                }

                // Optional domain warping
                if (warpActive) {
                    val wx = noise.fbm(nx + 5.2f, ny + 1.3f, 3)
                    val wy = noise.fbm(nx + 1.7f, ny + 9.2f, 3)
                    nx += wx * warpAmount; ny += wy * warpAmount
                }

                // Multi-octave fbm in roughly [-1, 1]
                val raw = if (octaves <= 1) noise.noise2D(nx, ny) else noise.fbm(nx, ny, octaves)

                // Style transform → t in [0, 1]
                val t: Float = when (styleId) {
                    1 -> { // ridged: 1 - |raw|, squared for sharper crests
                        val r = 1f - abs(raw)
                        (r * r).coerceIn(0f, 1f)
                    }
                    2 -> abs(raw).coerceIn(0f, 1f)  // turbulent
                    3 -> { // billow: |raw| pushed toward rounded puffs via dome curve
                        val b = abs(raw)
                        (b * (2f - b)).coerceIn(0f, 1f)
                    }
                    4 -> { // veins: 1 - smooth-floor(|raw|), bright thin valleys
                        val v = abs(raw)
                        (1f - v / (0.05f + v)).coerceIn(0f, 1f)
                    }
                    else -> ((raw + 1f) * 0.5f).coerceIn(0f, 1f)  // smooth fbm
                }

                // Color mapping
                val mt = if (isBands) {
                    val band = floor(t * bandCount).toInt().coerceIn(0, bandCount - 1)
                    band * invBandDenom
                } else t
                val color = lut[(mt * 255f + 0.5f).toInt().coerceIn(0, 255)]

                if (step == 1) {
                    pixels[py * w + px] = color
                } else {
                    val maxDy = min(step, h - py)
                    val maxDx = min(step, w - px)
                    for (dy in 0 until maxDy) {
                        val rowBase = (py + dy) * w + px
                        for (dx in 0 until maxDx) pixels[rowBase + dx] = color
                    }
                }
            }
        }

        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
        canvas.drawBitmap(bitmap, 0f, 0f, null)
    }

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float {
        val octaves = (params["octaves"] as? Number)?.toInt() ?: 4
        val warp = (params["warpAmount"] as? Number)?.toFloat() ?: 0f
        return (0.3f + octaves * 0.1f + warp * 0.15f).coerceIn(0.3f, 1f)
    }
}
