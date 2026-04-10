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

        val maxOct = nOct.coerceAtMost(when (quality) { Quality.DRAFT -> 1; Quality.ULTRA -> nOct; else -> 2 })

        // Palette ramp
        val lut = palette.buildLut(256)
        val rampR = IntArray(256); val rampG = IntArray(256); val rampB = IntArray(256)
        for (i in 0 until 256) {
            rampR[i] = Color.red(lut[i]); rampG[i] = Color.green(lut[i]); rampB[i] = Color.blue(lut[i])
        }

        val halfW = w * 0.5f; val halfH = h * 0.5f
        val invDim2 = 2f / min(w, h)
        val circuitEps = 0.06f

        // Adaptive eps based on pixel step (matches web version)
        val pixelStep = scl * invDim2
        val gradEps = pixelStep * 0.6f
        val ridgeEps = pixelStep * 2.5f
        val ridgeScale = 1f / max(0.0001f, ridgeEps * ridgeEps)
        val inv2GradEps = 1f / (gradEps * 2f)

        // Brightness LUT (matches web version formula)
        val glowPow = max(1.2f, 2f / max(0.3f, effEdgeW))
        val hasGlowR = glowR > 0.01f
        val glowFalloff = if (hasGlowR) 4f / glowR else 100f
        val ambientGlow = if (hasGlowR) exp(-glowFalloff) * effGlow * 0.08f else 0f

        val brightnessLUT = FloatArray(256)
        for (i in 0 until 256) {
            val edge = i / 255f
            if (edge > 0.004f) {
                val sharp = edge.pow(glowPow)
                val soft = if (hasGlowR) exp(-((1f - edge) * glowFalloff)) * 0.25f else 0f
                brightnessLUT[i] = ((sharp + soft) * effGlow).coerceAtMost(1f)
            } else {
                brightnessLUT[i] = ambientGlow.coerceAtMost(1f)
            }
        }

        val tOff = t * 0.15f

        // Bilinear upscale path for gradient/ridge at step > 1
        val useUpscale = (modeId == 1 || modeId == 2) && step > 1
        val cols = ceil(w.toFloat() / step).toInt()
        val rows = ceil(h.toFloat() / step).toInt()

        if (useUpscale) {
            // Render to small buffer, then bilinear upscale
            val smallPixels = IntArray(cols * rows)

            for (gy in 0 until rows) {
                val py = gy * step
                val v = (py - halfH) * invDim2
                for (gx in 0 until cols) {
                    val px = gx * step
                    val u = (px - halfW) * invDim2
                    val nx = u * scl; val ny = v * scl + tOff
                    val n = vn.fbm(nx, ny, maxOct)

                    var edge: Float
                    if (modeId == 1) { // gradient — FBM derivatives + double smoothstep
                        val gxd = (vn.fbm(nx + gradEps, ny, maxOct) - vn.fbm(nx - gradEps, ny, maxOct)) * inv2GradEps
                        val gyd = (vn.fbm(nx, ny + gradEps, maxOct) - vn.fbm(nx, ny - gradEps, maxOct)) * inv2GradEps
                        edge = sqrt(gxd * gxd + gyd * gyd).coerceAtMost(1f)
                        edge = edge * edge * (3f - 2f * edge) // smoothstep 1
                        edge = edge * edge * (3f - 2f * edge) // smoothstep 2
                    } else { // ridge — FBM Laplacian + double smoothstep
                        val nPx = vn.fbm(nx + ridgeEps, ny, maxOct)
                        val nMx = vn.fbm(nx - ridgeEps, ny, maxOct)
                        val nPy = vn.fbm(nx, ny + ridgeEps, maxOct)
                        val nMy = vn.fbm(nx, ny - ridgeEps, maxOct)
                        val laplacian = nPx + nMx + nPy + nMy - 4f * n
                        edge = (abs(laplacian) * ridgeScale).coerceAtMost(1f)
                        edge = edge * edge * (3f - 2f * edge)
                        edge = edge * edge * (3f - 2f * edge)
                    }

                    val edgeIdx = (edge * 255f).toInt().coerceIn(0, 255)
                    val brightness = brightnessLUT[edgeIdx]
                    var colorVal = (n * 0.5f + 0.5f).coerceIn(0f, 1f)
                    val idx = (colorVal * 255f).toInt()
                    val rr = (rampR[idx] * brightness).toInt().coerceIn(0, 255)
                    val gg = (rampG[idx] * brightness).toInt().coerceIn(0, 255)
                    val bb = (rampB[idx] * brightness).toInt().coerceIn(0, 255)
                    smallPixels[gy * cols + gx] = Color.rgb(rr, gg, bb)
                }
            }

            // Bilinear upscale: render to small bitmap, scale up with filtering
            val smallBmp = Bitmap.createBitmap(cols, rows, Bitmap.Config.ARGB_8888)
            smallBmp.setPixels(smallPixels, 0, cols, 0, 0, cols, rows)
            val scaled = Bitmap.createScaledBitmap(smallBmp, w, h, true)
            scaled.copyPixelsToBuffer(java.nio.IntBuffer.wrap(IntArray(w * h).also { scaled.getPixels(it, 0, w, 0, 0, w, h) }))
            val scaledPixels = IntArray(w * h)
            scaled.getPixels(scaledPixels, 0, w, 0, 0, w, h)
            bitmap.setPixels(scaledPixels, 0, w, 0, 0, w, h)
            smallBmp.recycle()
            scaled.recycle()
        } else {
            // Direct pixel-loop for contour, circuit, or step=1
            val pixels = IntArray(w * h)

            for (py in 0 until h step step) {
                val v = (py - halfH) * invDim2
                for (px in 0 until w step step) {
                    val u = (px - halfW) * invDim2
                    val nx = u * scl; val ny = v * scl + tOff
                    val n = vn.fbm(nx, ny, maxOct)

                    var edge: Float
                    when (modeId) {
                        0 -> { // contour
                            val band = n * Q
                            val frac = band - floor(band)
                            edge = 1f - abs(frac * 2f - 1f)
                            edge = edge * edge * edge
                        }
                        1 -> { // gradient (step=1 path)
                            val gxd = (vn.fbm(nx + gradEps, ny, maxOct) - vn.fbm(nx - gradEps, ny, maxOct)) * inv2GradEps
                            val gyd = (vn.fbm(nx, ny + gradEps, maxOct) - vn.fbm(nx, ny - gradEps, maxOct)) * inv2GradEps
                            edge = sqrt(gxd * gxd + gyd * gyd).coerceAtMost(1f)
                            edge = edge * edge * (3f - 2f * edge)
                            edge = edge * edge * (3f - 2f * edge)
                        }
                        2 -> { // ridge (step=1 path)
                            val nPx = vn.fbm(nx + ridgeEps, ny, maxOct)
                            val nMx = vn.fbm(nx - ridgeEps, ny, maxOct)
                            val nPy = vn.fbm(nx, ny + ridgeEps, maxOct)
                            val nMy = vn.fbm(nx, ny - ridgeEps, maxOct)
                            val laplacian = nPx + nMx + nPy + nMy - 4f * n
                            edge = (abs(laplacian) * ridgeScale).coerceAtMost(1f)
                            edge = edge * edge * (3f - 2f * edge)
                            edge = edge * edge * (3f - 2f * edge)
                        }
                        else -> { // circuit
                            val nRight = vn.noise(nx + circuitEps, ny)
                            val nDown = vn.noise(nx, ny + circuitEps)
                            val q1 = ((n * 0.5f + 0.5f) * Q).toInt()
                            val q2 = ((nRight * 0.5f + 0.5f) * Q).toInt()
                            val q3 = ((nDown * 0.5f + 0.5f) * Q).toInt()
                            edge = if (q1 != q2 || q1 != q3) 1f else 0f
                        }
                    }

                    val edgeIdx = (edge * 255f).toInt().coerceIn(0, 255)
                    val brightness = brightnessLUT[edgeIdx]
                    var colorVal = (n * 0.5f + 0.5f).coerceIn(0f, 1f)
                    val idx = (colorVal * 255f).toInt()
                    val rr = (rampR[idx] * brightness).toInt().coerceIn(0, 255)
                    val gg = (rampG[idx] * brightness).toInt().coerceIn(0, 255)
                    val bb = (rampB[idx] * brightness).toInt().coerceIn(0, 255)
                    val pixel = Color.rgb(rr, gg, bb)

                    if (step == 1) {
                        pixels[py * w + px] = pixel
                    } else {
                        val maxDy = min(step, h - py)
                        val maxDx = min(step, w - px)
                        for (dy in 0 until maxDy) {
                            val rowBase = (py + dy) * w + px
                            for (dx in 0 until maxDx) pixels[rowBase + dx] = pixel
                        }
                    }
                }
            }

            bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
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
