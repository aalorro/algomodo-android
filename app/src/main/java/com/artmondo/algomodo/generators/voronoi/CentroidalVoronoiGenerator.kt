package com.artmondo.algomodo.generators.voronoi

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
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
 * Centroidal Voronoi tessellation via Lloyd's relaxation.
 *
 * Starts with random seed points and iteratively moves each point to the
 * centroid of its Voronoi cell, producing increasingly regular cells.
 */
class CentroidalVoronoiGenerator : Generator {

    override val id = "centroidal-voronoi"
    override val family = "voronoi"
    override val styleName = "Centroidal Voronoi"
    override val definition =
        "Centroidal Voronoi tessellation where Lloyd's relaxation iteratively moves seed points to their cell centroids, producing regular honeycomb-like patterns."
    override val algorithmNotes =
        "Initial points from SeededRNG. Each relaxation step assigns every sampled pixel to its nearest seed point, " +
        "accumulates centroids, then moves points. The number of relaxation steps controls regularity. " +
        "Supports flat, gradient, and outlined cell rendering styles. Animation gently oscillates points via noise."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema = listOf(
        Parameter.NumberParam("Cell Count", "cellCount", ParamGroup.COMPOSITION, "", 5f, 200f, 5f, 50f),
        Parameter.NumberParam("Relaxation Steps", "relaxationSteps", ParamGroup.GEOMETRY, "Lloyd relaxation passes — more steps = more regular hexagonal cells", 0f, 15f, 1f, 5f),
        Parameter.NumberParam("Border Width", "borderWidth", ParamGroup.GEOMETRY, "", 0f, 6f, 0.5f, 1.5f),
        Parameter.BooleanParam("Show Seeds", "showSeeds", ParamGroup.GEOMETRY, "Draw the centroid seed point in each cell", false),
        Parameter.SelectParam("Color Mode", "colorMode", ParamGroup.COLOR, "", listOf("By Index", "By Distance", "By Position"), "By Index"),
        Parameter.SelectParam("Distance Metric", "distanceMetric", ParamGroup.GEOMETRY, "", listOf("Euclidean", "Manhattan", "Chebyshev"), "Euclidean"),
        Parameter.NumberParam("Anim Speed", "animSpeed", ParamGroup.FLOW_MOTION, "", 0f, 2f, 0.05f, 0.4f),
        Parameter.NumberParam("Anim Amplitude", "animAmp", ParamGroup.FLOW_MOTION, "Drift distance as a fraction of average cell size", 0f, 1f, 0.05f, 0.2f)
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "cellCount" to 50f,
        "relaxationSteps" to 5f,
        "borderWidth" to 1.5f,
        "showSeeds" to false,
        "colorMode" to "By Index",
        "distanceMetric" to "Euclidean",
        "animSpeed" to 0.4f,
        "animAmp" to 0.2f
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
        val numPoints = (params["cellCount"] as? Number)?.toInt() ?: 50
        if (numPoints <= 0) { canvas.drawColor(Color.BLACK); return }
        val relaxSteps = (params["relaxationSteps"] as? Number)?.toInt() ?: 5
        val showCentroids = params["showSeeds"] as? Boolean ?: false
        val borderWidth = (params["borderWidth"] as? Number)?.toFloat() ?: 1.5f
        val showBorder = borderWidth > 0f
        val colorMode = (params["colorMode"] as? String) ?: "By Index"
        val distanceMetric = (params["distanceMetric"] as? String) ?: "Euclidean"
        val animSpeed = (params["animSpeed"] as? Number)?.toFloat() ?: 0.4f
        val animAmp = (params["animAmp"] as? Number)?.toFloat() ?: 0.2f

        val metricId = when (distanceMetric.lowercase()) {
            "manhattan" -> 1; "chebyshev" -> 2; else -> 0
        }
        val isEuclidean = metricId == 0
        val colorModeId = when (colorMode) { "By Distance" -> 1; "By Position" -> 2; else -> 0 }

        val rng = SeededRNG(seed)
        val noise = SimplexNoise(seed)
        val px = FloatArray(numPoints)
        val py = FloatArray(numPoints)
        for (i in 0 until numPoints) {
            px[i] = rng.random() * w
            py[i] = rng.random() * h
        }

        // Lloyd relaxation (brute-force at coarse sampling — acceptable for few passes)
        val sampleStep = when (quality) { Quality.DRAFT -> 4; Quality.BALANCED -> 3; Quality.ULTRA -> 2 }
        for (step in 0 until relaxSteps) {
            val sumX = FloatArray(numPoints)
            val sumY = FloatArray(numPoints)
            val count = IntArray(numPoints)
            for (sy in 0 until h step sampleStep) {
                val yf = sy.toFloat()
                for (sx in 0 until w step sampleStep) {
                    val xf = sx.toFloat()
                    var bd = Float.MAX_VALUE; var bi = 0
                    for (i in 0 until numPoints) {
                        val dx = xf - px[i]; val dy = yf - py[i]
                        val d = if (isEuclidean) dx * dx + dy * dy
                                else if (metricId == 1) abs(dx) + abs(dy)
                                else maxOf(abs(dx), abs(dy))
                        if (d < bd) { bd = d; bi = i }
                    }
                    sumX[bi] += xf; sumY[bi] += yf; count[bi]++
                }
            }
            for (i in 0 until numPoints) {
                if (count[i] > 0) { px[i] = sumX[i] / count[i]; py[i] = sumY[i] / count[i] }
            }
        }

        // Animate
        if (time > 0f) {
            val speed = animSpeed / 0.4f; val amp = animAmp / 0.2f
            val wf = w.toFloat(); val hf = h.toFloat()
            for (i in 0 until numPoints) {
                px[i] = (px[i] + noise.noise2D(i * 0.5f + 10f, time * 0.2f * speed) * wf * 0.02f * amp).coerceIn(0f, wf - 1f)
                py[i] = (py[i] + noise.noise2D(i * 0.5f + 110f, time * 0.2f * speed) * hf * 0.02f * amp).coerceIn(0f, hf - 1f)
            }
        }

        // ── Spatial grid (3×3 search window) ──
        val gridSize = (maxOf(w, h).toFloat() / sqrt(numPoints.toFloat())).coerceAtLeast(8f)
        val invGridSize = 1f / gridSize
        val gridCols = (w * invGridSize).toInt() + 1
        val gridRows = (h * invGridSize).toInt() + 1
        val gcM1 = gridCols - 1; val grM1 = gridRows - 1
        val gridHeads = IntArray(gridCols * gridRows) { -1 }
        val gridNext = IntArray(numPoints) { -1 }
        for (i in 0 until numPoints) {
            val gx = minOf((px[i] * invGridSize).toInt(), gcM1)
            val gy = minOf((py[i] * invGridSize).toInt(), grM1)
            val cell = gy * gridCols + gx
            gridNext[i] = gridHeads[cell]; gridHeads[cell] = i
        }

        val colors = palette.colorInts()
        val colorsSize = colors.size
        val lut = if (colorModeId != 0) palette.buildLut(256) else null
        val pixels = IntArray(w * h)
        val renderStep = when (quality) { Quality.DRAFT -> 2; else -> 1 }

        if (isEuclidean && !showBorder && colorModeId == 0) {
            // ── FAST PATH: Euclidean + By Index + no borders ──
            for (row in 0 until h step renderStep) {
                val y = row.toFloat()
                val rowOff = row * w
                val gy = minOf((y * invGridSize).toInt(), grM1)
                val cyMin = maxOf(gy - 1, 0); val cyMax = minOf(gy + 1, grM1)
                for (col in 0 until w step renderStep) {
                    val x = col.toFloat()
                    val gx = minOf((x * invGridSize).toInt(), gcM1)
                    val cxMin = maxOf(gx - 1, 0); val cxMax = minOf(gx + 1, gcM1)
                    var bestDist = Float.MAX_VALUE; var bestIdx = 0
                    for (cy in cyMin..cyMax) {
                        val ro = cy * gridCols
                        for (cx in cxMin..cxMax) {
                            var idx = gridHeads[ro + cx]
                            while (idx >= 0) {
                                val dx = x - px[idx]; val dy = y - py[idx]
                                val d = dx * dx + dy * dy
                                if (d < bestDist) { bestDist = d; bestIdx = idx }
                                idx = gridNext[idx]
                            }
                        }
                    }
                    val color = colors[bestIdx % colorsSize]
                    if (renderStep == 1) {
                        pixels[rowOff + col] = color
                    } else {
                        val i0 = rowOff + col
                        pixels[i0] = color
                        if (col + 1 < w) pixels[i0 + 1] = color
                        if (row + 1 < h) { pixels[i0 + w] = color; if (col + 1 < w) pixels[i0 + w + 1] = color }
                    }
                }
            }
        } else {
            // ── GENERAL PATH ──
            val avgCellSize = if (colorModeId == 1) sqrt((w.toFloat() * h.toFloat()) / numPoints) else 0f
            val diagonal = if (colorModeId == 2) sqrt((w * w + h * h).toFloat()) else 1f
            val edgeThresh = borderWidth * 2f
            val edgeThreshSq = edgeThresh * edgeThresh
            for (row in 0 until h step renderStep) {
                val y = row.toFloat()
                val rowOff = row * w
                val gy = minOf((y * invGridSize).toInt(), grM1)
                val cyMin = maxOf(gy - 1, 0); val cyMax = minOf(gy + 1, grM1)
                for (col in 0 until w step renderStep) {
                    val x = col.toFloat()
                    val gx = minOf((x * invGridSize).toInt(), gcM1)
                    val cxMin = maxOf(gx - 1, 0); val cxMax = minOf(gx + 1, gcM1)
                    var bestDist = Float.MAX_VALUE; var secondDist = Float.MAX_VALUE; var bestIdx = 0
                    for (cy in cyMin..cyMax) {
                        val ro = cy * gridCols
                        for (cx in cxMin..cxMax) {
                            var idx = gridHeads[ro + cx]
                            while (idx >= 0) {
                                val dx = x - px[idx]; val dy = y - py[idx]
                                val d = if (isEuclidean) dx * dx + dy * dy
                                        else if (metricId == 1) abs(dx) + abs(dy)
                                        else maxOf(abs(dx), abs(dy))
                                if (d < bestDist) { secondDist = bestDist; bestDist = d; bestIdx = idx }
                                else if (d < secondDist) { secondDist = d }
                                idx = gridNext[idx]
                            }
                        }
                    }
                    val sqrtBest = if (isEuclidean && (showBorder || colorModeId == 1)) sqrt(bestDist) else 0f
                    val isEdge = showBorder && if (isEuclidean)
                        secondDist < bestDist + edgeThreshSq + 2f * edgeThresh * sqrtBest
                    else (secondDist - bestDist) < edgeThresh
                    val color = if (isEdge) Color.BLACK
                    else when (colorModeId) {
                        1 -> {
                            val dist = if (isEuclidean) sqrtBest else bestDist
                            lut!![(minOf(dist / avgCellSize, 1f) * 255f).toInt().coerceIn(0, 255)]
                        }
                        2 -> {
                            val t = (sqrt(px[bestIdx] * px[bestIdx] + py[bestIdx] * py[bestIdx]) / diagonal).coerceIn(0f, 1f)
                            lut!![(t * 255f).toInt().coerceIn(0, 255)]
                        }
                        else -> colors[bestIdx % colorsSize]
                    }
                    if (renderStep == 1) {
                        pixels[rowOff + col] = color
                    } else {
                        val i0 = rowOff + col
                        pixels[i0] = color
                        if (col + 1 < w) pixels[i0 + 1] = color
                        if (row + 1 < h) { pixels[i0 + w] = color; if (col + 1 < w) pixels[i0 + w + 1] = color }
                    }
                }
            }
        }

        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
        canvas.drawBitmap(bitmap, 0f, 0f, null)

        if (showCentroids) {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                style = Paint.Style.FILL
            }
            for (i in 0 until numPoints) {
                canvas.drawCircle(px[i], py[i], 3f, paint)
            }
        }
    }

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float {
        val n = (params["cellCount"] as? Number)?.toFloat() ?: 50f
        val steps = (params["relaxationSteps"] as? Number)?.toFloat() ?: 5f
        return ((n * steps) / 7500f).coerceIn(0.2f, 1f)
    }
}
