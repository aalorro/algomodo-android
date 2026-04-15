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

class EdgeGlowGenerator : Generator {

    override val id = "procedural-edge-glow"
    override val family = "procedural"
    override val styleName = "Edge + Glow"
    override val definition =
        "Neon edge detection on noise fields — glowing contour lines, gradient edges, ridges, and circuit-board step patterns."
    override val algorithmNotes =
        "Evaluates multi-octave value noise per pixel and detects edges via four methods: " +
        "contour finds iso-lines at regular intervals; gradient computes finite differences; " +
        "ridge uses Laplacian for curvature peaks; circuit detects quantized step boundaries. " +
        "Edge strength is converted to glow brightness via a precomputed 256-entry power LUT."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema = listOf(
        Parameter.SelectParam("Edge Mode", "edgeMode", ParamGroup.COMPOSITION,
            "contour: iso-line bands | gradient: edge detection | ridge: curvature | circuit: quantized steps",
            listOf("contour", "gradient", "ridge", "circuit"), "contour"),
        Parameter.NumberParam("Noise Scale", "noiseScale", ParamGroup.GEOMETRY,
            "Size of the noise field", 0.5f, 8f, 0.1f, 3.0f),
        Parameter.NumberParam("Edge Width", "edgeWidth", ParamGroup.TEXTURE,
            "Sharpness of edge lines", 0.5f, 5f, 0.1f, 1.5f),
        Parameter.NumberParam("Glow Radius", "glowRadius", ParamGroup.TEXTURE,
            "Soft glow falloff distance", 0f, 1f, 0.05f, 0.5f),
        Parameter.NumberParam("Glow Intensity", "glowIntensity", ParamGroup.TEXTURE,
            "Brightness multiplier for glow", 0.1f, 2f, 0.05f, 0.8f),
        Parameter.NumberParam("Octaves", "octaves", ParamGroup.COMPOSITION,
            "FBM layers", 1f, 4f, 1f, 2f),
        Parameter.NumberParam("Quantize", "quantize", ParamGroup.TEXTURE,
            "Number of contour bands", 2f, 16f, 1f, 6f),
        Parameter.NumberParam("Speed", "speed", ParamGroup.FLOW_MOTION,
            "Animation drift speed", 0.1f, 3f, 0.05f, 0.4f),
        Parameter.NumberParam("Reactivity", "reactivity", ParamGroup.FLOW_MOTION,
            "Audio reactivity strength", 0f, 2f, 0.1f, 1.0f)
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "edgeMode" to "contour", "noiseScale" to 3.0f, "edgeWidth" to 1.5f,
        "glowRadius" to 0.5f, "glowIntensity" to 0.8f, "octaves" to 2f,
        "quantize" to 6f, "speed" to 0.4f, "reactivity" to 1.0f
    )

    override fun renderCanvas(
        canvas: Canvas, bitmap: Bitmap, params: Map<String, Any>,
        seed: Int, palette: Palette, quality: Quality, time: Float
    ) {
        val w = bitmap.width; val h = bitmap.height
        val step = when (quality) { Quality.DRAFT -> 4; Quality.ULTRA -> 1; else -> 2 }

        val mode = (params["edgeMode"] as? String) ?: "contour"
        val scl = (params["noiseScale"] as? Number)?.toFloat() ?: 3.0f
        val edgeW = (params["edgeWidth"] as? Number)?.toFloat() ?: 1.5f
        val glowR = (params["glowRadius"] as? Number)?.toFloat() ?: 0.5f
        val glowI = (params["glowIntensity"] as? Number)?.toFloat() ?: 0.8f
        val nOct = (params["octaves"] as? Number)?.toInt()?.coerceAtLeast(1) ?: 2
        val Q = (params["quantize"] as? Number)?.toInt()?.coerceAtLeast(2) ?: 6
        val spd = (params["speed"] as? Number)?.toFloat() ?: 0.4f

        val rx = (params["reactivity"] as? Number)?.toFloat() ?: 1.0f
        val audioAnalysis = params["_audioAnalysis"] as? AudioAnalysis
        val audioBass = (audioAnalysis?.getBass(time) ?: 0f) * rx
        val audioHigh = (audioAnalysis?.getHigh(time) ?: 0f) * rx
        val effGlow = glowI * (1f + audioBass * 1.5f)
        val effEdgeW = edgeW * (1f + audioHigh * 0.5f)

        val t = time * spd
        val modeId = when (mode) { "contour" -> 0; "gradient" -> 1; "ridge" -> 2; else -> 3 }
        val vn = ValueNoise(seed)

        // Respect user octave setting; only cap DRAFT to avoid wasted work on downsampled output
        val maxOct = if (quality == Quality.DRAFT) nOct.coerceAtMost(2) else nOct

        // Palette ramp
        val lut = palette.buildLut(256)
        val rampR = IntArray(256); val rampG = IntArray(256); val rampB = IntArray(256)
        for (i in 0 until 256) {
            rampR[i] = Color.red(lut[i]); rampG[i] = Color.green(lut[i]); rampB[i] = Color.blue(lut[i])
        }

        val halfW = w * 0.5f; val halfH = h * 0.5f
        val invDim2 = 2f / min(w, h)
        val circuitEps = 0.06f

        // edgeWidth scales sampling distance for gradient/ridge spatial thickness
        val pixelStep = scl * invDim2
        val gradEps = pixelStep * effEdgeW * 0.4f
        val ridgeEps = pixelStep * effEdgeW * 1.7f
        val ridgeScale = 1f / max(0.0001f, ridgeEps * ridgeEps)
        val inv2GradEps = 1f / (gradEps * 2f)

        // Contour: edgeWidth controls line thickness via power exponent
        val contourPow = max(0.5f, 4.0f - effEdgeW * 0.7f)

        // Brightness LUT (matching web version)
        val glowPow = max(0.6f, 3.0f / max(0.3f, effEdgeW))
        val hasGlowR = glowR > 0.01f
        val glowFalloff = if (hasGlowR) 3f / glowR else 200f
        val softMul = if (hasGlowR) 0.3f + glowR * 1.7f else 0f
        val ambientGlow = if (hasGlowR) min(1f, exp(-glowFalloff) * effGlow * (0.02f + glowR * 0.2f)) else 0f

        val brightnessLUT = FloatArray(256)
        for (i in 0 until 256) {
            val edgeVal = i / 255f
            if (edgeVal > 0.004f) {
                val sharp = edgeVal.pow(glowPow)
                val soft = if (hasGlowR) exp(-((1f - edgeVal) * glowFalloff)) * softMul else 0f
                brightnessLUT[i] = ((sharp + soft) * effGlow).coerceAtMost(1f)
            } else {
                brightnessLUT[i] = ambientGlow
            }
        }

        val Qf = Q.toFloat()
        val invQ = 1f / Qf
        val tOff = t * 0.15f
        val cols = ceil(w.toFloat() / step).toInt()
        val rows = ceil(h.toFloat() / step).toInt()
        val smallPixels = IntArray(cols * rows)

        // Unified render loop (all modes rendered to small buffer)
        for (gy in 0 until rows) {
            val py = gy * step
            val v = (py - halfH) * invDim2
            for (gx in 0 until cols) {
                val px = gx * step
                val u = (px - halfW) * invDim2
                val nx = u * scl; val ny = v * scl + tOff
                val n = vn.fbm(nx, ny, maxOct)

                val edge: Float = when (modeId) {
                    0 -> { // contour — bright bands between iso-lines
                        val band = n * Qf
                        val frac = band - floor(band)
                        val e = 1f - abs(frac * 2f - 1f)
                        e.pow(contourPow)
                    }
                    1 -> { // gradient — eps scaled by edgeWidth
                        val gxd = (vn.fbm(nx + gradEps, ny, maxOct) - vn.fbm(nx - gradEps, ny, maxOct)) * inv2GradEps
                        val gyd = (vn.fbm(nx, ny + gradEps, maxOct) - vn.fbm(nx, ny - gradEps, maxOct)) * inv2GradEps
                        var e = sqrt(gxd * gxd + gyd * gyd).coerceAtMost(1f)
                        e = round(e * Qf) * invQ
                        e * e * (3f - 2f * e)
                    }
                    2 -> { // ridge — eps scaled by edgeWidth
                        val nPx = vn.fbm(nx + ridgeEps, ny, maxOct)
                        val nMx = vn.fbm(nx - ridgeEps, ny, maxOct)
                        val nPy = vn.fbm(nx, ny + ridgeEps, maxOct)
                        val nMy = vn.fbm(nx, ny - ridgeEps, maxOct)
                        val laplacian = nPx + nMx + nPy + nMy - 4f * n
                        var e = (abs(laplacian) * ridgeScale).coerceAtMost(1f)
                        e = round(e * Qf) * invQ
                        e * e * (3f - 2f * e)
                    }
                    else -> { // circuit
                        val nRight = vn.noise(nx + circuitEps, ny)
                        val nDown = vn.noise(nx, ny + circuitEps)
                        val q1 = ((n * 0.5f + 0.5f) * Qf).toInt()
                        val q2 = ((nRight * 0.5f + 0.5f) * Qf).toInt()
                        val q3 = ((nDown * 0.5f + 0.5f) * Qf).toInt()
                        if (q1 != q2 || q1 != q3) 1f else 0f
                    }
                }

                val edgeIdx = (edge * 255f).toInt().coerceIn(0, 255)
                val brightness = brightnessLUT[edgeIdx]
                val colorVal = (n * 0.5f + 0.5f).coerceIn(0f, 1f)
                val idx = (colorVal * 255f).toInt()
                val rr = (rampR[idx] * brightness).toInt().coerceIn(0, 255)
                val gg = (rampG[idx] * brightness).toInt().coerceIn(0, 255)
                val bb = (rampB[idx] * brightness).toInt().coerceIn(0, 255)
                smallPixels[gy * cols + gx] = Color.rgb(rr, gg, bb)
            }
        }

        // Bilinear upscale for all modes when step > 1
        if (step > 1) {
            val smallBmp = Bitmap.createBitmap(cols, rows, Bitmap.Config.ARGB_8888)
            smallBmp.setPixels(smallPixels, 0, cols, 0, 0, cols, rows)
            val scaled = Bitmap.createScaledBitmap(smallBmp, w, h, true)
            val pixels = IntArray(w * h)
            scaled.getPixels(pixels, 0, w, 0, 0, w, h)
            bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
            smallBmp.recycle()
            scaled.recycle()
        } else {
            bitmap.setPixels(smallPixels, 0, w, 0, 0, w, h)
        }

        canvas.drawBitmap(bitmap, 0f, 0f, null)
    }

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float {
        val oct = (params["octaves"] as? Number)?.toInt() ?: 2
        val mode = (params["edgeMode"] as? String) ?: "contour"
        val modeMult = when (mode) { "gradient", "ridge" -> 1.5f; "circuit" -> 1.3f; else -> 1f }
        return (oct * 0.12f * modeMult + 0.2f).coerceIn(0.3f, 1f)
    }
}
