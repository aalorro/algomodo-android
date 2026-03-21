package com.artmondo.algomodo.generators.voronoi

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import com.artmondo.algomodo.core.rng.SeededRNG
import com.artmondo.algomodo.core.rng.SimplexNoise
import com.artmondo.algomodo.data.palettes.Palette
import com.artmondo.algomodo.generators.Generator
import com.artmondo.algomodo.generators.ParamGroup
import com.artmondo.algomodo.generators.Parameter
import com.artmondo.algomodo.generators.Quality
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Fractured / multi-level Voronoi generator.
 *
 * Creates a hierarchical Voronoi pattern where each cell is subdivided by
 * a secondary set of seed points, producing a shattered-glass or
 * fractured-rock appearance.
 */
class VoronoiFracturedGenerator : Generator {

    override val id = "voronoi-fractured"
    override val family = "voronoi"
    override val styleName = "Voronoi Fractured"
    override val definition =
        "Multi-level fractured Voronoi where each cell is recursively subdivided by secondary Voronoi seeds, creating shattered-glass patterns."
    override val algorithmNotes =
        "A primary set of Voronoi seed points defines large cells. For each fracture level an additional " +
        "set of more densely scattered points produces smaller sub-cells. The final colour is derived by " +
        "combining the owning cell index at each level. Irregularity controls how much the sub-points " +
        "deviate from a regular grid. Animation slowly displaces all point sets via noise."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema = listOf(
        Parameter.NumberParam("Shard Count", "shardCount", ParamGroup.COMPOSITION, "Number of primary fracture regions", 5f, 100f, 5f, 25f),
        Parameter.NumberParam("Fracture Lines", "fractureCount", ParamGroup.COMPOSITION, "Secondary crack density within each shard", 10f, 200f, 10f, 60f),
        Parameter.NumberParam("Crack Width", "crackWidth", ParamGroup.GEOMETRY, "Width of primary shard boundaries", 0.5f, 6f, 0.5f, 1.5f),
        Parameter.NumberParam("Fracture Width", "fractureWidth", ParamGroup.GEOMETRY, "Width of secondary fracture lines within shards", 0.2f, 3f, 0.1f, 0.8f),
        Parameter.NumberParam("Shard Shading", "shadeStrength", ParamGroup.TEXTURE, "Directional brightness gradient within each shard to suggest 3D tilt", 0f, 1f, 0.05f, 0.5f),
        Parameter.SelectParam("Color Mode", "colorMode", ParamGroup.COLOR, "", listOf("palette-cycle", "palette-gradient", "monochrome"), "palette-cycle"),
        Parameter.SelectParam("Distance Metric", "distanceMetric", ParamGroup.GEOMETRY, "", listOf("Euclidean", "Manhattan", "Chebyshev"), "Euclidean"),
        Parameter.NumberParam("Anim Speed", "animSpeed", ParamGroup.FLOW_MOTION, "", 0f, 2f, 0.05f, 0.3f),
        Parameter.NumberParam("Anim Amplitude", "animAmp", ParamGroup.FLOW_MOTION, "Drift distance as a fraction of average cell size", 0f, 1f, 0.05f, 0.15f)
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "shardCount" to 25f,
        "fractureCount" to 60f,
        "crackWidth" to 1.5f,
        "fractureWidth" to 0.8f,
        "shadeStrength" to 0.5f,
        "colorMode" to "palette-cycle",
        "distanceMetric" to "Euclidean",
        "animSpeed" to 0.3f,
        "animAmp" to 0.15f
    )

    override fun renderCanvas(
        canvas: Canvas,
        bitmap: Bitmap,
        params: Map<String, Any>,
        seed: Int,
        palette: Palette,
        quality: Quality,
        time: Float
    ) {
        val w = bitmap.width
        val h = bitmap.height
        val wf = w.toFloat()
        val hf = h.toFloat()
        val numPoints = (params["shardCount"] as? Number)?.toInt() ?: 25
        if (numPoints <= 0) { canvas.drawColor(Color.BLACK); return }
        val fractureCount = ((params["fractureCount"] as? Number)?.toInt() ?: 60).coerceAtMost(500)
        val crackWidth = (params["crackWidth"] as? Number)?.toFloat() ?: 1.5f
        val fractureWidth = (params["fractureWidth"] as? Number)?.toFloat() ?: 0.8f
        val shadeStrength = (params["shadeStrength"] as? Number)?.toFloat() ?: 0.5f
        val colorMode = (params["colorMode"] as? String) ?: "palette-cycle"
        val distanceMetric = (params["distanceMetric"] as? String) ?: "Euclidean"
        val animSpeed = (params["animSpeed"] as? Number)?.toFloat() ?: 0.3f
        val animAmp = (params["animAmp"] as? Number)?.toFloat() ?: 0.15f

        val metricId = when (distanceMetric.lowercase()) {
            "manhattan" -> 1; "chebyshev" -> 2; else -> 0
        }
        val isEuclidean = metricId == 0
        val colorModeId = when (colorMode) { "palette-gradient" -> 1; "monochrome" -> 2; else -> 0 }

        val noise = SimplexNoise(seed)

        // ── Level 0: Primary shards ──
        val rng0 = SeededRNG(seed)
        val px0 = FloatArray(numPoints)
        val py0 = FloatArray(numPoints)
        for (i in 0 until numPoints) {
            px0[i] = rng0.random() * w
            py0[i] = rng0.random() * h
        }

        // ── Level 1: Fracture lines (jittered grid) ──
        val rng1 = SeededRNG(seed + 7919)
        val px1 = FloatArray(fractureCount)
        val py1 = FloatArray(fractureCount)
        val cols1 = sqrt(fractureCount.toFloat()).toInt().coerceAtLeast(1)
        val rows1 = (fractureCount + cols1 - 1) / cols1
        val stepW = wf / cols1
        val stepH = hf / rows1
        var fi = 0
        for (r in 0 until rows1) {
            for (c in 0 until cols1) {
                if (fi >= fractureCount) break
                px1[fi] = ((c + 0.5f) * stepW + (rng1.random() - 0.5f) * stepW * 0.5f).coerceIn(0f, wf - 1f)
                py1[fi] = ((r + 0.5f) * stepH + (rng1.random() - 0.5f) * stepH * 0.5f).coerceIn(0f, hf - 1f)
                fi++
            }
        }

        // Animate
        if (time > 0f && animSpeed > 0f && animAmp > 0f) {
            val amp = animAmp * 0.15f
            for (i in 0 until numPoints) {
                px0[i] = (px0[i] + noise.noise2D(i * 0.2f, time * animSpeed) * wf * amp).coerceIn(0f, wf - 1f)
                py0[i] = (py0[i] + noise.noise2D(i * 0.2f + 500f, time * animSpeed) * hf * amp).coerceIn(0f, hf - 1f)
            }
            val speed1 = animSpeed * 0.5f
            for (i in 0 until fractureCount) {
                px1[i] = (px1[i] + noise.noise2D(i * 0.2f + 100f, time * speed1) * wf * amp).coerceIn(0f, wf - 1f)
                py1[i] = (py1[i] + noise.noise2D(i * 0.2f + 600f, time * speed1) * hf * amp).coerceIn(0f, hf - 1f)
            }
        }

        // ── Spatial grid for level 0 (linked-list) ──
        val gs0 = (maxOf(w, h).toFloat() / sqrt(numPoints.toFloat())).coerceAtLeast(8f)
        val invGs0 = 1f / gs0
        val gc0 = (w * invGs0).toInt() + 1
        val gr0 = (h * invGs0).toInt() + 1
        val gc0M1 = gc0 - 1; val gr0M1 = gr0 - 1
        val gh0 = IntArray(gc0 * gr0) { -1 }
        val gn0 = IntArray(numPoints) { -1 }
        for (i in 0 until numPoints) {
            val gx = minOf((px0[i] * invGs0).toInt(), gc0M1)
            val gy = minOf((py0[i] * invGs0).toInt(), gr0M1)
            val cell = gy * gc0 + gx
            gn0[i] = gh0[cell]; gh0[cell] = i
        }

        // ── Spatial grid for level 1 (linked-list) ──
        val gs1 = (maxOf(w, h).toFloat() / sqrt(fractureCount.toFloat())).coerceAtLeast(8f)
        val invGs1 = 1f / gs1
        val gc1 = (w * invGs1).toInt() + 1
        val gr1 = (h * invGs1).toInt() + 1
        val gc1M1 = gc1 - 1; val gr1M1 = gr1 - 1
        val gh1 = IntArray(gc1 * gr1) { -1 }
        val gn1 = IntArray(fractureCount) { -1 }
        for (i in 0 until fractureCount) {
            val gx = minOf((px1[i] * invGs1).toInt(), gc1M1)
            val gy = minOf((py1[i] * invGs1).toInt(), gr1M1)
            val cell = gy * gc1 + gx
            gn1[i] = gh1[cell]; gh1[cell] = i
        }

        // Pre-compute constants
        val colors = palette.colorInts()
        val colorsSize = colors.size
        val lut = if (colorModeId == 1) palette.buildLut(256) else null
        val lightDirX = 0.7071f; val lightDirY = 0.7071f
        val cellRadius = sqrt(wf * hf / numPoints.toFloat()) * 0.5f
        val invCellRadius = 1f / cellRadius
        val crackThresh = crackWidth * 3f
        val fractureThresh = fractureWidth * 2f

        val pixels = IntArray(w * h)
        val step = when (quality) { Quality.DRAFT -> 2; else -> 1 }

        if (isEuclidean) {
            // ── EUCLIDEAN FAST PATH: no metric branch in inner loops ──
            for (row in 0 until h step step) {
                val y = row.toFloat()
                val rowOff = row * w
                val gy0v = minOf((y * invGs0).toInt(), gr0M1)
                val cyMin0 = maxOf(gy0v - 1, 0); val cyMax0 = minOf(gy0v + 1, gr0M1)
                val gy1v = minOf((y * invGs1).toInt(), gr1M1)
                val cyMin1 = maxOf(gy1v - 1, 0); val cyMax1 = minOf(gy1v + 1, gr1M1)
                for (col in 0 until w step step) {
                    val x = col.toFloat()
                    // Level 0: find F1, F2 in primary grid
                    val gx0v = minOf((x * invGs0).toInt(), gc0M1)
                    val cxMin0 = maxOf(gx0v - 1, 0); val cxMax0 = minOf(gx0v + 1, gc0M1)
                    var f1a = Float.MAX_VALUE; var f2a = Float.MAX_VALUE; var near0 = 0
                    for (cy in cyMin0..cyMax0) {
                        val ro = cy * gc0
                        for (cx in cxMin0..cxMax0) {
                            var ii = gh0[ro + cx]
                            while (ii >= 0) {
                                val dx = x - px0[ii]; val dy = y - py0[ii]
                                val d = dx * dx + dy * dy
                                if (d < f1a) { f2a = f1a; f1a = d; near0 = ii }
                                else if (d < f2a) { f2a = d }
                                ii = gn0[ii]
                            }
                        }
                    }
                    // Level 1: find F1, F2 in fracture grid
                    val gx1v = minOf((x * invGs1).toInt(), gc1M1)
                    val cxMin1 = maxOf(gx1v - 1, 0); val cxMax1 = minOf(gx1v + 1, gc1M1)
                    var f1b = Float.MAX_VALUE; var f2b = Float.MAX_VALUE; var near1 = 0
                    for (cy in cyMin1..cyMax1) {
                        val ro = cy * gc1
                        for (cx in cxMin1..cxMax1) {
                            var ii = gh1[ro + cx]
                            while (ii >= 0) {
                                val dx = x - px1[ii]; val dy = y - py1[ii]
                                val d = dx * dx + dy * dy
                                if (d < f1b) { f2b = f1b; f1b = d; near1 = ii }
                                else if (d < f2b) { f2b = d }
                                ii = gn1[ii]
                            }
                        }
                    }

                    // Edge detection (squared distance gap — matches original behavior)
                    val isCrack = crackWidth > 0f && (f2a - f1a) < crackThresh
                    val isFracture = fractureWidth > 0f && (f2b - f1b) < fractureThresh

                    val combinedIndex = near0 * 7 + near1
                    val baseColor = when (colorModeId) {
                        1 -> {
                            val t = if (numPoints > 1) near0.toFloat() / (numPoints - 1).toFloat() else 0f
                            lut!![(t * 255f).toInt().coerceIn(0, 255)]
                        }
                        2 -> {
                            val baseC = colors[0]
                            val br = 0.5f + 0.5f * ((combinedIndex * 4973) and 0xFF) / 255f
                            Color.rgb((Color.red(baseC) * br).toInt().coerceIn(0, 255),
                                (Color.green(baseC) * br).toInt().coerceIn(0, 255),
                                (Color.blue(baseC) * br).toInt().coerceIn(0, 255))
                        }
                        else -> colors[(combinedIndex and 0x7FFFFFFF) % colorsSize]
                    }

                    val color = if (shadeStrength > 0f) {
                        val nx = ((x - px0[near0]) * invCellRadius).coerceIn(-1f, 1f)
                        val ny = ((y - py0[near0]) * invCellRadius).coerceIn(-1f, 1f)
                        val shade = 1f + (nx * lightDirX + ny * lightDirY) * shadeStrength * 0.5f
                        Color.rgb((Color.red(baseColor) * shade).toInt().coerceIn(0, 255),
                            (Color.green(baseColor) * shade).toInt().coerceIn(0, 255),
                            (Color.blue(baseColor) * shade).toInt().coerceIn(0, 255))
                    } else baseColor

                    val finalColor = when {
                        isCrack -> Color.BLACK
                        isFracture -> Color.rgb((Color.red(color) * 0.4f).toInt(),
                            (Color.green(color) * 0.4f).toInt(),
                            (Color.blue(color) * 0.4f).toInt())
                        else -> color
                    }

                    if (step == 1) {
                        pixels[rowOff + col] = finalColor
                    } else {
                        val i0 = rowOff + col
                        pixels[i0] = finalColor
                        if (col + 1 < w) pixels[i0 + 1] = finalColor
                        if (row + 1 < h) { pixels[i0 + w] = finalColor; if (col + 1 < w) pixels[i0 + w + 1] = finalColor }
                    }
                }
            }
        } else {
            // ── NON-EUCLIDEAN PATH ──
            for (row in 0 until h step step) {
                val y = row.toFloat()
                val rowOff = row * w
                val gy0v = minOf((y * invGs0).toInt(), gr0M1)
                val cyMin0 = maxOf(gy0v - 1, 0); val cyMax0 = minOf(gy0v + 1, gr0M1)
                val gy1v = minOf((y * invGs1).toInt(), gr1M1)
                val cyMin1 = maxOf(gy1v - 1, 0); val cyMax1 = minOf(gy1v + 1, gr1M1)
                for (col in 0 until w step step) {
                    val x = col.toFloat()
                    val gx0v = minOf((x * invGs0).toInt(), gc0M1)
                    val cxMin0 = maxOf(gx0v - 1, 0); val cxMax0 = minOf(gx0v + 1, gc0M1)
                    var f1a = Float.MAX_VALUE; var f2a = Float.MAX_VALUE; var near0 = 0
                    for (cy in cyMin0..cyMax0) {
                        val ro = cy * gc0
                        for (cx in cxMin0..cxMax0) {
                            var ii = gh0[ro + cx]
                            while (ii >= 0) {
                                val dx = x - px0[ii]; val dy = y - py0[ii]
                                val d = if (metricId == 1) abs(dx) + abs(dy) else maxOf(abs(dx), abs(dy))
                                if (d < f1a) { f2a = f1a; f1a = d; near0 = ii }
                                else if (d < f2a) { f2a = d }
                                ii = gn0[ii]
                            }
                        }
                    }

                    val gx1v = minOf((x * invGs1).toInt(), gc1M1)
                    val cxMin1 = maxOf(gx1v - 1, 0); val cxMax1 = minOf(gx1v + 1, gc1M1)
                    var f1b = Float.MAX_VALUE; var f2b = Float.MAX_VALUE; var near1 = 0
                    for (cy in cyMin1..cyMax1) {
                        val ro = cy * gc1
                        for (cx in cxMin1..cxMax1) {
                            var ii = gh1[ro + cx]
                            while (ii >= 0) {
                                val dx = x - px1[ii]; val dy = y - py1[ii]
                                val d = if (metricId == 1) abs(dx) + abs(dy) else maxOf(abs(dx), abs(dy))
                                if (d < f1b) { f2b = f1b; f1b = d; near1 = ii }
                                else if (d < f2b) { f2b = d }
                                ii = gn1[ii]
                            }
                        }
                    }

                    val isCrack = crackWidth > 0f && (f2a - f1a) < crackThresh
                    val isFracture = fractureWidth > 0f && (f2b - f1b) < fractureThresh
                    val combinedIndex = near0 * 7 + near1

                    val baseColor = when (colorModeId) {
                        1 -> {
                            val t = if (numPoints > 1) near0.toFloat() / (numPoints - 1).toFloat() else 0f
                            lut!![(t * 255f).toInt().coerceIn(0, 255)]
                        }
                        2 -> {
                            val baseC = colors[0]
                            val br = 0.5f + 0.5f * ((combinedIndex * 4973) and 0xFF) / 255f
                            Color.rgb((Color.red(baseC) * br).toInt().coerceIn(0, 255),
                                (Color.green(baseC) * br).toInt().coerceIn(0, 255),
                                (Color.blue(baseC) * br).toInt().coerceIn(0, 255))
                        }
                        else -> colors[(combinedIndex and 0x7FFFFFFF) % colorsSize]
                    }

                    val color = if (shadeStrength > 0f) {
                        val nx = ((x - px0[near0]) * invCellRadius).coerceIn(-1f, 1f)
                        val ny = ((y - py0[near0]) * invCellRadius).coerceIn(-1f, 1f)
                        val shade = 1f + (nx * lightDirX + ny * lightDirY) * shadeStrength * 0.5f
                        Color.rgb((Color.red(baseColor) * shade).toInt().coerceIn(0, 255),
                            (Color.green(baseColor) * shade).toInt().coerceIn(0, 255),
                            (Color.blue(baseColor) * shade).toInt().coerceIn(0, 255))
                    } else baseColor

                    val finalColor = when {
                        isCrack -> Color.BLACK
                        isFracture -> Color.rgb((Color.red(color) * 0.4f).toInt(),
                            (Color.green(color) * 0.4f).toInt(),
                            (Color.blue(color) * 0.4f).toInt())
                        else -> color
                    }

                    if (step == 1) {
                        pixels[rowOff + col] = finalColor
                    } else {
                        val i0 = rowOff + col
                        pixels[i0] = finalColor
                        if (col + 1 < w) pixels[i0 + 1] = finalColor
                        if (row + 1 < h) { pixels[i0 + w] = finalColor; if (col + 1 < w) pixels[i0 + w + 1] = finalColor }
                    }
                }
            }
        }

        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
        canvas.drawBitmap(bitmap, 0f, 0f, null)
    }

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float {
        val n = (params["shardCount"] as? Number)?.toFloat() ?: 25f
        val fc = (params["fractureCount"] as? Number)?.toFloat() ?: 60f
        return ((n + fc) / 200f).coerceIn(0.3f, 1f)
    }
}
