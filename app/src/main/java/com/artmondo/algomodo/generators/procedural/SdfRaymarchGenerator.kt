package com.artmondo.algomodo.generators.procedural

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import com.artmondo.algomodo.core.rng.SeededRNG
import com.artmondo.algomodo.data.palettes.Palette
import com.artmondo.algomodo.generators.Generator
import com.artmondo.algomodo.generators.ParamGroup
import com.artmondo.algomodo.generators.Parameter
import com.artmondo.algomodo.generators.Quality
import kotlin.math.*

class SdfRaymarchGenerator : Generator {

    override val id = "procedural-sdf-raymarch"
    override val family = "procedural"
    override val styleName = "SDF Raymarch"
    override val definition =
        "2D signed-distance-field rendering with smooth boolean operations, glow halos, and distance-band contours."
    override val algorithmNotes =
        "Generates SDF primitives (circles, rounded boxes) with seeded positions and sizes. " +
        "Primitives orbit their base positions over time. Per-pixel: combined SDF is evaluated via smooth union/subtract. " +
        "Interior is palette-colored by nearest primitive; exterior shows exponential glow and sinusoidal distance banding."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema = listOf(
        Parameter.SelectParam("Scene", "sceneType", ParamGroup.COMPOSITION,
            "spheres: circles | boxes: rounded rects | blend: mixed | fractal: recursive self-similar",
            listOf("spheres", "boxes", "blend", "fractal"), "spheres"),
        Parameter.NumberParam("Complexity", "complexity", ParamGroup.COMPOSITION,
            "Number of SDF primitives", 1f, 8f, 1f, 4f),
        Parameter.NumberParam("Glow", "glowIntensity", ParamGroup.TEXTURE,
            "Glow halo intensity around shapes", 0f, 1f, 0.05f, 0.5f),
        Parameter.NumberParam("Band Width", "bandWidth", ParamGroup.TEXTURE,
            "Distance-based contour band width (0 = off)", 0f, 1f, 0.05f, 0.3f),
        Parameter.NumberParam("Smooth Blend", "smoothBlend", ParamGroup.GEOMETRY,
            "Smooth union/subtract blending factor", 0f, 1f, 0.05f, 0.3f),
        Parameter.NumberParam("Rotation Speed", "rotationSpeed", ParamGroup.FLOW_MOTION,
            "Primitive orbit speed", 0f, 2f, 0.1f, 0.5f),
        Parameter.NumberParam("Speed", "speed", ParamGroup.FLOW_MOTION,
            "Global animation speed", 0.1f, 2f, 0.1f, 0.5f)
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "sceneType" to "spheres", "complexity" to 4f, "glowIntensity" to 0.5f,
        "bandWidth" to 0.3f, "smoothBlend" to 0.3f, "rotationSpeed" to 0.5f, "speed" to 0.5f
    )

    override fun renderCanvas(
        canvas: Canvas, bitmap: Bitmap, params: Map<String, Any>,
        seed: Int, palette: Palette, quality: Quality, time: Float
    ) {
        val w = bitmap.width; val h = bitmap.height
        val rng = SeededRNG(seed)
        val minDim = min(w, h)
        val step = when (quality) { Quality.DRAFT -> 4; Quality.ULTRA -> 1; else -> 2 }

        val sceneType = (params["sceneType"] as? String) ?: "spheres"
        val complexity = (params["complexity"] as? Number)?.toInt()?.coerceAtLeast(1) ?: 4
        val glowIntensity = (params["glowIntensity"] as? Number)?.toFloat() ?: 0.5f
        val bandWidth = (params["bandWidth"] as? Number)?.toFloat() ?: 0.3f
        val smoothK = ((params["smoothBlend"] as? Number)?.toFloat() ?: 0.3f) * minDim * 0.15f
        val useSmooth = smoothK >= 0.001f
        val rotSpeed = (params["rotationSpeed"] as? Number)?.toFloat() ?: 0.5f
        val spd = (params["speed"] as? Number)?.toFloat() ?: 0.5f
        val t = time * spd

        val colors = palette.colorInts()
        val nC = colors.size

        // Generate primitives (SoA flat arrays)
        val addDepth0 = if (sceneType == "fractal") 2 else 1
        val maxPrims = complexity * addDepth0 + if (sceneType == "fractal") min(3, complexity) else 0

        val primType = IntArray(maxPrims)     // 0=circle, 1=roundbox
        val primCx = FloatArray(maxPrims)
        val primCy = FloatArray(maxPrims)
        val primR = FloatArray(maxPrims)
        val primHW = FloatArray(maxPrims)
        val primHH = FloatArray(maxPrims)
        val primRR = FloatArray(maxPrims)
        val primOrbitR = FloatArray(maxPrims)
        val primOrbitSpd = FloatArray(maxPrims)
        val primOrbitPhase = FloatArray(maxPrims)
        val primColorIdx = IntArray(maxPrims)
        val primOp = IntArray(maxPrims)       // 0=union, 1=subtract

        var primCount = 0

        fun addPrim(depth: Int) {
            val count = if (depth == 0) complexity else min(3, complexity)
            val sizeScale = if (depth == 0) 1f else 0.4f
            for (i in 0 until count) {
                val isCircle = sceneType == "spheres" || (sceneType == "blend" && rng.random() > 0.5f)
                val idx = primCount++
                primType[idx] = if (isCircle) 0 else 1
                primCx[idx] = rng.range(0.2f, 0.8f) * w
                primCy[idx] = rng.range(0.2f, 0.8f) * h
                primR[idx] = rng.range(0.06f, 0.18f) * minDim * sizeScale
                val hw = rng.range(0.05f, 0.16f) * minDim * sizeScale
                val hh = rng.range(0.05f, 0.16f) * minDim * sizeScale
                val rr = rng.range(0.01f, 0.04f) * minDim * sizeScale
                primHW[idx] = hw - rr
                primHH[idx] = hh - rr
                primRR[idx] = rr
                primOrbitR[idx] = rng.range(0.02f, 0.1f) * minDim * sizeScale
                primOrbitSpd[idx] = rng.range(0.5f, 2.0f) * if (rng.random() > 0.5f) 1f else -1f
                primOrbitPhase[idx] = rng.randomAngle()
                primColorIdx[idx] = rng.integer(0, nC - 1)
                primOp[idx] = if (i == 0 || rng.random() > 0.3f) 0 else 1
            }
        }

        addPrim(0)
        if (sceneType == "fractal") addPrim(1)

        // Precompute animated positions
        val animCx = FloatArray(primCount)
        val animCy = FloatArray(primCount)
        for (i in 0 until primCount) {
            val a = t * rotSpeed * primOrbitSpd[i] + primOrbitPhase[i]
            animCx[i] = primCx[i] + cos(a) * primOrbitR[i]
            animCy[i] = primCy[i] + sin(a) * primOrbitR[i]
        }

        // Shading constants
        val shadeDiv = 1f / (minDim * 0.05f)
        val glowDiv = -1f / (minDim * 0.06f)
        val bandScale = if (bandWidth > 0.01f) PI.toFloat() / (bandWidth * minDim * 0.05f) else 0f
        val hasGlow = glowIntensity > 0f
        val hasBand = bandScale > 0f

        val pixels = IntArray(w * h)

        for (py in 0 until h step step) {
            for (px in 0 until w step step) {
                var d = Float.MAX_VALUE
                var closest = 0

                for (i in 0 until primCount) {
                    val di: Float
                    if (primType[i] == 0) {
                        val dx = px - animCx[i]; val dy = py - animCy[i]
                        di = sqrt(dx * dx + dy * dy) - primR[i]
                    } else {
                        val dx = abs(px - animCx[i]) - primHW[i]
                        val dy = abs(py - animCy[i]) - primHH[i]
                        val dx0 = if (dx > 0f) dx else 0f
                        val dy0 = if (dy > 0f) dy else 0f
                        di = sqrt(dx0 * dx0 + dy0 * dy0) +
                            (if (dx > dy) (if (dy > 0f) 0f else dy) else (if (dx > 0f) 0f else dx)) - primRR[i]
                    }

                    if (i == 0) {
                        d = di
                    } else if (primOp[i] == 1) {
                        // smooth subtract
                        if (useSmooth) {
                            val h2 = (0.5f - 0.5f * (d + di) / smoothK).coerceIn(0f, 1f)
                            val nd = d * (1f - h2) + (-di) * h2 + smoothK * h2 * (1f - h2)
                            if (nd != d) closest = i
                            d = nd
                        } else {
                            val nd = if (d > -di) d else -di
                            if (nd != d) closest = i
                            d = nd
                        }
                    } else {
                        if (di < d) closest = i
                        if (useSmooth) {
                            val h2 = (0.5f + 0.5f * (di - d) / smoothK).coerceIn(0f, 1f)
                            d = d * h2 + di * (1f - h2) - smoothK * h2 * (1f - h2)
                        } else {
                            if (di < d) d = di
                        }
                    }
                }

                val r: Float; val g: Float; val b: Float
                if (d < 0f) {
                    val c = colors[primColorIdx[closest]]
                    val shade = 0.7f + 0.3f * min(1f, -d * shadeDiv)
                    r = Color.red(c) * shade
                    g = Color.green(c) * shade
                    b = Color.blue(c) * shade
                } else {
                    val glow = if (hasGlow) exp(d * glowDiv) * glowIntensity else 0f
                    val band = if (hasBand) (sin(d * bandScale) * 0.5f + 0.5f) * 0.35f else 0f
                    val v = glow + band
                    val c = colors[primColorIdx[closest] % nC]
                    r = Color.red(c) * v
                    g = Color.green(c) * v
                    b = Color.blue(c) * v
                }

                val ri = r.toInt().coerceIn(0, 255)
                val gi = g.toInt().coerceIn(0, 255)
                val bi = b.toInt().coerceIn(0, 255)
                val pixel = Color.rgb(ri, gi, bi)

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
        val complexity = (params["complexity"] as? Number)?.toInt() ?: 4
        val isFractal = (params["sceneType"] as? String) == "fractal"
        return ((complexity * 0.1f + if (isFractal) 0.2f else 0f) + 0.2f).coerceIn(0.3f, 1f)
    }
}
