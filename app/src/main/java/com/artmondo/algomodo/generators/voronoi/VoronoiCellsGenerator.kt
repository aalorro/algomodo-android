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
import kotlin.math.atan2
import kotlin.math.sqrt

class VoronoiCellsGenerator : Generator {

    override val id = "voronoi-cells"
    override val family = "voronoi"
    override val styleName = "Voronoi Cells"
    override val definition =
        "Classic Voronoi diagram where the plane is partitioned into cells around scattered seed points, each coloured by palette index."
    override val algorithmNotes =
        "Seed points are placed via SeededRNG. For each pixel the nearest point is found using a grid-accelerated " +
        "spatial lookup. Supports euclidean, manhattan, and chebyshev distance metrics. Edges are detected by " +
        "checking if the closest cell differs from the second-closest within edgeWidth. Animation displaces seed " +
        "points over time using simplex noise."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema = listOf(
        Parameter.NumberParam("Cell Count", "cellCount", ParamGroup.COMPOSITION, "", 5f, 200f, 1f, 40f),
        Parameter.SelectParam("Distance Metric", "distanceMetric", ParamGroup.GEOMETRY, "", listOf("Euclidean", "Manhattan", "Chebyshev"), "Euclidean"),
        Parameter.NumberParam("Border Width", "borderWidth", ParamGroup.GEOMETRY, "", 0f, 5f, 0.5f, 1f),
        Parameter.SelectParam("Color Mode", "colorMode", ParamGroup.COLOR, "", listOf("By Index", "By Distance", "By Angle"), "By Index"),
        Parameter.BooleanParam("Relaxed", "relaxed", ParamGroup.GEOMETRY, "Apply Lloyd relaxation for more uniform cells", false),
        Parameter.NumberParam("Anim Speed", "animSpeed", ParamGroup.FLOW_MOTION, "", 0f, 2f, 0.05f, 0.4f),
        Parameter.NumberParam("Anim Amplitude", "animAmp", ParamGroup.FLOW_MOTION, "Drift distance as a fraction of average cell size", 0f, 1f, 0.05f, 0.2f)
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "cellCount" to 40f,
        "distanceMetric" to "Euclidean",
        "borderWidth" to 1f,
        "colorMode" to "By Index",
        "relaxed" to false,
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
        val numPoints = (params["cellCount"] as? Number)?.toInt() ?: 40
        if (numPoints <= 0) { canvas.drawColor(Color.BLACK); return }
        val edgeWidth = (params["borderWidth"] as? Number)?.toFloat() ?: 1f
        val showEdges = edgeWidth > 0f
        val metric = (params["distanceMetric"] as? String) ?: "Euclidean"
        val colorMode = (params["colorMode"] as? String) ?: "By Index"
        val relaxed = params["relaxed"] as? Boolean ?: false
        val animSpeed = (params["animSpeed"] as? Number)?.toFloat() ?: 0.4f
        val animAmp = (params["animAmp"] as? Number)?.toFloat() ?: 0.2f

        val metricId = when (metric.lowercase()) {
            "manhattan" -> 1; "chebyshev" -> 2; else -> 0
        }
        val isEuclidean = metricId == 0

        val rng = SeededRNG(seed)
        val noise = SimplexNoise(seed)
        val px = FloatArray(numPoints)
        val py = FloatArray(numPoints)
        for (i in 0 until numPoints) {
            px[i] = rng.random() * w
            py[i] = rng.random() * h
        }

        if (relaxed) {
            val relaxStep = 4
            for (pass in 0 until 3) {
                val sumX = FloatArray(numPoints)
                val sumY = FloatArray(numPoints)
                val count = IntArray(numPoints)
                for (sy in 0 until h step relaxStep) {
                    val syf = sy.toFloat()
                    for (sx in 0 until w step relaxStep) {
                        val sxf = sx.toFloat()
                        var bd = Float.MAX_VALUE; var bi = 0
                        for (i in 0 until numPoints) {
                            val ddx = sxf - px[i]; val ddy = syf - py[i]
                            val dd = if (isEuclidean) ddx * ddx + ddy * ddy
                                     else if (metricId == 1) abs(ddx) + abs(ddy)
                                     else maxOf(abs(ddx), abs(ddy))
                            if (dd < bd) { bd = dd; bi = i }
                        }
                        sumX[bi] += sxf; sumY[bi] += syf; count[bi]++
                    }
                }
                for (i in 0 until numPoints) {
                    if (count[i] > 0) { px[i] = sumX[i] / count[i]; py[i] = sumY[i] / count[i] }
                }
            }
        }

        if (time > 0f) {
            val speed = animSpeed / 0.4f; val amp = animAmp / 0.2f
            val wf = w.toFloat(); val hf = h.toFloat()
            for (i in 0 until numPoints) {
                px[i] = (px[i] + noise.noise2D(i * 0.3f + 100f, time * 0.2f * speed) * wf * 0.05f * amp).coerceIn(0f, wf - 1f)
                py[i] = (py[i] + noise.noise2D(i * 0.3f + 200f, time * 0.2f * speed) * hf * 0.05f * amp).coerceIn(0f, hf - 1f)
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
        val colorModeId = when (colorMode) { "By Distance" -> 1; "By Angle" -> 2; else -> 0 }
        val lut = if (colorModeId != 0) palette.buildLut(256) else null
        val pixels = IntArray(w * h)
        val step = when (quality) { Quality.DRAFT -> 2; else -> 1 }

        if (isEuclidean && !showEdges && colorModeId == 0) {
            // ── FAST PATH: Euclidean + By Index + no edges (default config) ──
            // No secondDist tracking, no sqrt, no string comparison
            for (row in 0 until h step step) {
                val y = row.toFloat()
                val rowOff = row * w
                val gy = minOf((y * invGridSize).toInt(), grM1)
                val cyMin = maxOf(gy - 1, 0); val cyMax = minOf(gy + 1, grM1)
                for (col in 0 until w step step) {
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
                    if (step == 1) {
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
            val halfW = w / 2f; val halfH = h / 2f
            val invTwoPi = 1f / (2f * Math.PI.toFloat())
            val edgeThresh = edgeWidth * 2f
            val edgeThreshSq = edgeThresh * edgeThresh
            for (row in 0 until h step step) {
                val y = row.toFloat()
                val rowOff = row * w
                val gy = minOf((y * invGridSize).toInt(), grM1)
                val cyMin = maxOf(gy - 1, 0); val cyMax = minOf(gy + 1, grM1)
                for (col in 0 until w step step) {
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
                    // Edge detection: 1 sqrt instead of 2 for Euclidean
                    val sqrtBest = if (isEuclidean && (showEdges || colorModeId == 1)) sqrt(bestDist) else 0f
                    val isEdge = showEdges && if (isEuclidean)
                        secondDist < bestDist + edgeThreshSq + 2f * edgeThresh * sqrtBest
                    else (secondDist - bestDist) < edgeThresh
                    val color = if (isEdge) Color.BLACK
                    else when (colorModeId) {
                        1 -> {
                            val dist = if (isEuclidean) sqrtBest else bestDist
                            lut!![(minOf(dist / avgCellSize, 1f) * 255f).toInt().coerceIn(0, 255)]
                        }
                        2 -> {
                            val t = ((atan2(py[bestIdx] - halfH, px[bestIdx] - halfW) + Math.PI.toFloat()) * invTwoPi).coerceIn(0f, 1f)
                            lut!![(t * 255f).toInt().coerceIn(0, 255)]
                        }
                        else -> colors[bestIdx % colorsSize]
                    }
                    if (step == 1) {
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
    }

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float {
        val n = (params["cellCount"] as? Number)?.toFloat() ?: 40f
        return (n / 200f).coerceIn(0.2f, 1f)
    }
}
