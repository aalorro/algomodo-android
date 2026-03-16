package com.artmondo.algomodo.generators.procedural

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import com.artmondo.algomodo.data.palettes.Palette
import com.artmondo.algomodo.generators.Generator
import com.artmondo.algomodo.generators.ParamGroup
import com.artmondo.algomodo.generators.Parameter
import com.artmondo.algomodo.generators.Quality
import kotlin.math.*

class WarpGenerator : Generator {

    override val id = "warp"
    override val family = "procedural"
    override val styleName = "Warp"
    override val definition =
        "Coordinate-warp visual effects — spiral, tunnel, ripple, and kaleidoscope modes with chromatic aberration and multi-layer domain warping."
    override val algorithmNotes =
        "Converts pixel coordinates to polar space and applies mode-specific warps: spiral arms twist angle by radius, " +
        "ripple pulses concentric rings outward, tunnel maps depth via inverse radius for infinite zoom, " +
        "kaleidoscope mirrors angular segments. Multiple domain-warp layers displace coordinates through value noise."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema = listOf(
        Parameter.SelectParam("Warp Mode", "warpMode", ParamGroup.COMPOSITION,
            "spiral: swirling arms | ripple: pulsing rings | tunnel: infinite zoom | kaleidoscope: mirrored folds",
            listOf("spiral", "ripple", "tunnel", "kaleidoscope"), "spiral"),
        Parameter.NumberParam("Warp Strength", "warpStrength", ParamGroup.GEOMETRY,
            "Intensity of coordinate warping", 0.1f, 5f, 0.1f, 2.0f),
        Parameter.NumberParam("Warp Layers", "layers", ParamGroup.COMPOSITION,
            "Domain-warp passes — more = richer organic flow", 1f, 5f, 1f, 3f),
        Parameter.NumberParam("Symmetry", "symmetry", ParamGroup.GEOMETRY,
            "Kaleidoscopic mirror folds (1 = off)", 1f, 12f, 1f, 1f),
        Parameter.NumberParam("Chromatic Shift", "chromaticShift", ParamGroup.COLOR,
            "RGB channel offset for prismatic color splitting", 0f, 1f, 0.05f, 0.15f),
        Parameter.NumberParam("Zoom", "zoom", ParamGroup.GEOMETRY,
            "Zoom into the warp field", 0.3f, 4f, 0.1f, 1.0f),
        Parameter.NumberParam("Speed", "speed", ParamGroup.FLOW_MOTION,
            "Animation drift speed", 0.1f, 3f, 0.05f, 0.7f)
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "warpMode" to "spiral", "warpStrength" to 2.0f, "layers" to 3f,
        "symmetry" to 1f, "chromaticShift" to 0.15f, "zoom" to 1.0f, "speed" to 0.7f
    )

    override fun renderCanvas(
        canvas: Canvas, bitmap: Bitmap, params: Map<String, Any>,
        seed: Int, palette: Palette, quality: Quality, time: Float
    ) {
        val w = bitmap.width; val h = bitmap.height
        val step = when (quality) { Quality.DRAFT -> 4; Quality.ULTRA -> 1; else -> 2 }

        val mode = (params["warpMode"] as? String) ?: "spiral"
        val warpStr = (params["warpStrength"] as? Number)?.toFloat() ?: 2.0f
        val nLayers = (params["layers"] as? Number)?.toInt()?.coerceAtLeast(1) ?: 3
        val sym = (params["symmetry"] as? Number)?.toInt()?.coerceAtLeast(1) ?: 1
        val ca = (params["chromaticShift"] as? Number)?.toFloat() ?: 0.15f
        val zoomVal = (params["zoom"] as? Number)?.toFloat() ?: 1.0f
        val spd = (params["speed"] as? Number)?.toFloat() ?: 0.7f

        val t = time * spd
        val vn = ValueNoise(seed)

        // Precomputed palette ramp
        val lut = palette.buildLut(256)
        val rampR = IntArray(256); val rampG = IntArray(256); val rampB = IntArray(256)
        for (i in 0 until 256) {
            rampR[i] = Color.red(lut[i]); rampG[i] = Color.green(lut[i]); rampB[i] = Color.blue(lut[i])
        }

        val halfW = w * 0.5f; val halfH = h * 0.5f
        val invScale = zoomVal / (min(w, h) * 0.5f)
        val TAU = PI.toFloat() * 2f
        val segAngle = if (sym > 1) TAU / sym else 0f
        val caScale = ca * 0.6f
        val doCa = ca > 0.01f

        val modeId = when (mode) { "spiral" -> 0; "ripple" -> 1; "tunnel" -> 2; else -> 3 }

        val cosRot = cos(t * 0.2f); val sinRot = sin(t * 0.2f)

        val maxLayers = nLayers.coerceAtMost(when (quality) { Quality.DRAFT -> 1; Quality.ULTRA -> nLayers; else -> 2 })
        val layerFreq = FloatArray(maxLayers)
        val layerAmp = FloatArray(maxLayers)
        val layerOff = FloatArray(maxLayers)
        val layerT = FloatArray(maxLayers)
        for (l in 0 until maxLayers) {
            layerFreq[l] = 1.5f + l * 0.8f
            layerAmp[l] = 0.4f / (1f + l * 0.5f)
            layerOff[l] = l * 13.7f
            layerT[l] = t * (0.08f + l * 0.02f)
        }

        val pixels = IntArray(w * h)

        for (py in 0 until h step step) {
            val cyRaw = (py - halfH) * invScale

            for (px in 0 until w step step) {
                val cxRaw = (px - halfW) * invScale
                val radSq = cxRaw * cxRaw + cyRaw * cyRaw
                val rad = sqrt(radSq)

                var ang = 0f
                if (sym > 1 || modeId == 0 || modeId == 2) {
                    ang = atan2(cyRaw, cxRaw)
                    if (sym > 1) {
                        ang = ((ang % TAU) + TAU) % TAU
                        val seg = (ang / segAngle).toInt()
                        ang -= seg * segAngle
                        if (seg and 1 == 1) ang = segAngle - ang
                    }
                }

                var u: Float; var v: Float
                when (modeId) {
                    0 -> { // spiral
                        u = rad * 2f
                        v = ang + rad * warpStr + t
                    }
                    1 -> { // ripple
                        val disp = sin(rad * 6f - t * 3f) * warpStr * 0.3f
                        val invR = if (rad > 0.001f) disp / rad else 0f
                        if (sym > 1) {
                            val cx2 = rad * cos(ang); val cy2 = rad * sin(ang)
                            u = cx2 + cx2 * invR; v = cy2 + cy2 * invR
                        } else {
                            u = cxRaw + cxRaw * invR; v = cyRaw + cyRaw * invR
                        }
                    }
                    2 -> { // tunnel
                        u = ang / TAU * 3f
                        v = 0.5f / (rad + 0.01f) + t * 2f
                    }
                    else -> { // kaleidoscope
                        var cx2 = cxRaw; var cy2 = cyRaw
                        if (sym > 1) {
                            cx2 = rad * cos(ang); cy2 = rad * sin(ang)
                        }
                        u = cx2 * cosRot - cy2 * sinRot + sin(rad * 3f - t) * warpStr * 0.5f
                        v = cx2 * sinRot + cy2 * cosRot + cos(rad * 2f + t * 0.7f) * warpStr * 0.5f
                    }
                }

                // Domain-warp layers
                for (l in 0 until maxLayers) {
                    val f = layerFreq[l]; val am = layerAmp[l]
                    val off = layerOff[l]; val lt = layerT[l]
                    val du = vn.noise(u * f + off, v * f + lt) * am
                    val dv = vn.noise(u * f + off + 31.7f, v * f + lt + off) * am
                    u += du; v += dv
                }

                // Color: 2-octave FBM via value noise
                val u2 = u * 2f; val v2 = v * 2f
                val n1 = vn.noise(u2, v2)
                val n2 = vn.noise(u2 * 2f, v2 * 2f)
                var valG = (n1 + 0.5f * n2) * 0.5f + 0.5f
                valG = valG.coerceIn(0f, 1f)

                val rr: Int; val gg: Int; val bb: Int
                if (doCa) {
                    val shift = n2 * caScale
                    val valR = (valG + shift).coerceIn(0f, 1f)
                    val valB = (valG - shift).coerceIn(0f, 1f)
                    val idxR = (valR * 255f).toInt()
                    val idxG = (valG * 255f).toInt()
                    val idxB = (valB * 255f).toInt()
                    rr = rampR[idxR]; gg = rampG[idxG]; bb = rampB[idxB]
                } else {
                    val idx = (valG * 255f).toInt()
                    rr = rampR[idx]; gg = rampG[idx]; bb = rampB[idx]
                }

                val pixel = Color.rgb(rr, gg, bb)

                if (step == 1) {
                    pixels[py * w + px] = pixel
                } else {
                    val maxDy = min(step, h - py)
                    val maxDx = min(step, w - px)
                    for (dy in 0 until maxDy) {
                        val rowBase = (py + dy) * w + px
                        for (dx in 0 until maxDx) {
                            pixels[rowBase + dx] = pixel
                        }
                    }
                }
            }
        }

        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
        canvas.drawBitmap(bitmap, 0f, 0f, null)
    }

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float {
        val layers = (params["layers"] as? Number)?.toInt() ?: 3
        val ca = (params["chromaticShift"] as? Number)?.toFloat() ?: 0.15f
        val caMult = if (ca > 0.01f) 1.1f else 1f
        return (layers * 0.15f * caMult + 0.25f).coerceIn(0.3f, 1f)
    }
}
