package com.artmondo.algomodo.generators.procedural

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import com.artmondo.algomodo.audio.AudioAnalysis
import com.artmondo.algomodo.data.palettes.Palette
import com.artmondo.algomodo.generators.Generator
import com.artmondo.algomodo.generators.ParamGroup
import com.artmondo.algomodo.generators.Parameter
import com.artmondo.algomodo.generators.Quality
import kotlin.math.*

class DisplacementGenerator : Generator {

    override val id = "procedural-displacement"
    override val family = "procedural"
    override val styleName = "Displacement"
    override val definition =
        "Noise-driven UV displacement mapping — pixels are offset through vector fields to create organic distortion, fracture, radial ripple, and wave effects."
    override val algorithmNotes =
        "Computes a 2D displacement vector per pixel from multi-octave value noise (FBM). " +
        "Flow mode applies smooth continuous displacement; fracture quantizes noise; " +
        "radial scales by distance for ripples; wave wraps through sinusoids. " +
        "Chromatic aberration offsets RGB channels along the displacement gradient."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema = listOf(
        Parameter.SelectParam("Mode", "mode", ParamGroup.COMPOSITION,
            "flow: smooth | fracture: hard blocks | radial: ripples | wave: banded distortion",
            listOf("flow", "fracture", "radial", "wave"), "flow"),
        Parameter.NumberParam("Strength", "strength", ParamGroup.GEOMETRY,
            "How far pixels are displaced", 0.01f, 0.5f, 0.01f, 0.15f),
        Parameter.NumberParam("Scale", "scale", ParamGroup.GEOMETRY,
            "Size of the displacement noise field", 0.5f, 8f, 0.1f, 2.0f),
        Parameter.NumberParam("Octaves", "octaves", ParamGroup.COMPOSITION,
            "FBM layers — more = finer detail", 1f, 5f, 1f, 3f),
        Parameter.NumberParam("Distortion", "distortion", ParamGroup.TEXTURE,
            "Secondary domain warp before displacement lookup", 0f, 1f, 0.05f, 0.3f),
        Parameter.NumberParam("Chromatic Shift", "chromaticShift", ParamGroup.COLOR,
            "RGB channel offset for color splitting", 0f, 1f, 0.05f, 0.1f),
        Parameter.NumberParam("Speed", "speed", ParamGroup.FLOW_MOTION,
            "Animation drift speed", 0.1f, 3f, 0.05f, 0.5f),
        Parameter.NumberParam("Reactivity", "reactivity", ParamGroup.FLOW_MOTION,
            "Audio reactivity strength", 0f, 2f, 0.1f, 1.0f)
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "mode" to "flow", "strength" to 0.15f, "scale" to 2.0f, "octaves" to 3f,
        "distortion" to 0.3f, "chromaticShift" to 0.1f, "speed" to 0.5f,
        "reactivity" to 1.0f
    )

    override fun renderCanvas(
        canvas: Canvas, bitmap: Bitmap, params: Map<String, Any>,
        seed: Int, palette: Palette, quality: Quality, time: Float
    ) {
        val w = bitmap.width; val h = bitmap.height
        val step = when (quality) { Quality.DRAFT -> 4; Quality.ULTRA -> 1; else -> 2 }

        val mode = (params["mode"] as? String) ?: "flow"
        val str = (params["strength"] as? Number)?.toFloat() ?: 0.15f
        val scl = (params["scale"] as? Number)?.toFloat() ?: 2.0f
        val nOct = (params["octaves"] as? Number)?.toInt()?.coerceAtLeast(1) ?: 3
        val dist = (params["distortion"] as? Number)?.toFloat() ?: 0.3f
        val ca = (params["chromaticShift"] as? Number)?.toFloat() ?: 0.1f
        val spd = (params["speed"] as? Number)?.toFloat() ?: 0.5f

        val rx = (params["reactivity"] as? Number)?.toFloat() ?: 1.0f
        val audioAnalysis = params["_audioAnalysis"] as? AudioAnalysis
        val audioBass = (audioAnalysis?.getBass(time) ?: 0f) * rx
        val audioMid = (audioAnalysis?.getMid(time) ?: 0f) * rx
        val effStr = str * (1f + audioBass * 3f)
        val effScl = scl * (1f + audioMid * 0.5f)

        val t = time * spd
        val modeId = when (mode) { "flow" -> 0; "fracture" -> 1; "radial" -> 2; else -> 3 }
        val vn = ValueNoise(seed)

        // Palette ramp
        val lut = palette.buildLut(256)
        val rampR = IntArray(256); val rampG = IntArray(256); val rampB = IntArray(256)
        for (i in 0 until 256) {
            rampR[i] = Color.red(lut[i]); rampG[i] = Color.green(lut[i]); rampB[i] = Color.blue(lut[i])
        }

        val halfW = w * 0.5f; val halfH = h * 0.5f
        val invDim2 = 2f / min(w, h)
        val caScale = ca * 0.5f
        val doCa = ca > 0.01f
        val doDist = dist > 0.01f
        val warpAmt = dist * 0.5f

        val maxOct = nOct.coerceAtMost(when (quality) { Quality.DRAFT -> 1; Quality.ULTRA -> nOct; else -> 2 })

        val TAU = PI.toFloat() * 2f
        val fractQ = 4f + dist * 8f; val fractInvQ = 1f / fractQ
        val radialFreq = TAU * (2f + dist * 6f)
        val radialTimeOff = t * 3f
        val waveFreq = 3f + dist * 10f
        val waveT1 = t * 2f; val waveT2 = t * 1.5f
        val colorScl = effScl * 2f

        val tOff1x = t * 0.1f; val tOff1y = t * 0.07f
        val tOff2x = t * 0.07f; val tOff2y = t * 0.1f
        val distNScl = effScl * 2.5f

        val pixels = IntArray(w * h)

        for (py in 0 until h step step) {
            val v = (py - halfH) * invDim2
            for (px in 0 until w step step) {
                val u = (px - halfW) * invDim2

                val nx = u * effScl; val ny = v * effScl
                val n1 = vn.fbm(nx + tOff1x, ny + tOff1y, maxOct)
                val n2 = vn.fbm(nx + 31.7f + tOff2x, ny + 17.3f + tOff2y, maxOct)

                var dx: Float; var dy: Float
                when (modeId) {
                    0 -> { dx = n1; dy = n2 }
                    1 -> {
                        dx = (n1 * fractQ).toInt() * fractInvQ
                        dy = (n2 * fractQ).toInt() * fractInvQ
                    }
                    2 -> {
                        val rad = sqrt(u * u + v * v)
                        val radMod = sin(rad * radialFreq - radialTimeOff) * rad
                        dx = n1 * radMod; dy = n2 * radMod
                    }
                    else -> {
                        dx = sin(n1 * waveFreq + waveT1) * 0.5f
                        dy = cos(n2 * waveFreq + waveT2) * 0.5f
                    }
                }

                var wu = u + dx * effStr
                var wv = v + dy * effStr

                if (doDist) {
                    val dnx = nx * distNScl; val dny = ny * distNScl
                    wu += vn.noise(dnx + 50f, dny) * warpAmt
                    wv += vn.noise(dnx, dny + 50f) * warpAmt
                }

                val cn1 = vn.noise(wu * colorScl, wv * colorScl)
                var valG = (cn1 + 0.5f * n2) * 0.5f + 0.5f
                valG = valG.coerceIn(0f, 1f)

                val rr: Int; val gg: Int; val bb: Int
                if (doCa) {
                    val shift = n2 * caScale
                    val valR = (valG + shift).coerceIn(0f, 1f)
                    val valB = (valG - shift).coerceIn(0f, 1f)
                    rr = rampR[(valR * 255f).toInt()]
                    gg = rampG[(valG * 255f).toInt()]
                    bb = rampB[(valB * 255f).toInt()]
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
                    for (ddy in 0 until maxDy) {
                        val rowBase = (py + ddy) * w + px
                        for (ddx in 0 until maxDx) pixels[rowBase + ddx] = pixel
                    }
                }
            }
        }

        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
        canvas.drawBitmap(bitmap, 0f, 0f, null)
    }

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float {
        val oct = (params["octaves"] as? Number)?.toInt() ?: 3
        val ca = (params["chromaticShift"] as? Number)?.toFloat() ?: 0.1f
        val caMult = if (ca > 0.01f) 1.1f else 1f
        return (oct * 0.15f * caMult + 0.2f).coerceIn(0.3f, 1f)
    }
}
