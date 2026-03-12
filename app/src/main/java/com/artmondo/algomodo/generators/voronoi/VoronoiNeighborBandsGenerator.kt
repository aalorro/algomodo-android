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

/**
 * Voronoi neighbour-count band generator.
 *
 * Colours each Voronoi cell by how many neighbouring cells it has. The neighbour
 * count is estimated by scanning the cell boundary pixels and counting distinct
 * adjacent cell IDs. A coloured band per neighbour count is mapped through the palette.
 */
class VoronoiNeighborBandsGenerator : Generator {

    override val id = "voronoi-neighbor-bands"
    override val family = "voronoi"
    override val styleName = "Voronoi Neighbor Bands"
    override val definition =
        "Voronoi cells coloured by their number of neighbours, creating distinct bands that highlight the topology of the tessellation."
    override val algorithmNotes =
        "A Voronoi cell map is computed. For each cell, all adjacent cell IDs found in a 1-pixel " +
        "border scan are collected; the count of distinct neighbours determines the colour band. " +
        "bandWidth widens the colour distinction between counts. Edges can be overlaid. " +
        "Animation displaces seed points via simplex noise."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema = listOf(
        Parameter.NumberParam("Cell Count", "cellCount", ParamGroup.COMPOSITION, "", 5f, 150f, 5f, 35f),
        Parameter.NumberParam("Band Count", "bandCount", ParamGroup.COMPOSITION, "Number of concentric neighbor rings around each cell — each ring gets the next palette color", 1f, 12f, 1f, 4f),
        Parameter.SelectParam("Band Mode", "bandMode", ParamGroup.TEXTURE, "flat = solid color per ring; gradient = smooth blend between rings; alternating = rings flip between two palette ends", listOf("flat", "gradient", "alternating"), "flat"),
        Parameter.NumberParam("Border Width", "borderWidth", ParamGroup.GEOMETRY, "", 0f, 4f, 0.5f, 1f),
        Parameter.SelectParam("Distance Metric", "distanceMetric", ParamGroup.GEOMETRY, "", listOf("Euclidean", "Manhattan", "Chebyshev"), "Euclidean"),
        Parameter.BooleanParam("Lloyd Relaxed", "relaxed", ParamGroup.GEOMETRY, "", false),
        Parameter.NumberParam("Anim Speed", "animSpeed", ParamGroup.FLOW_MOTION, "", 0f, 2f, 0.05f, 0.4f),
        Parameter.NumberParam("Anim Amplitude", "animAmp", ParamGroup.FLOW_MOTION, "Drift distance as a fraction of average cell size", 0f, 1f, 0.05f, 0.2f)
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "cellCount" to 35f,
        "bandCount" to 4f,
        "bandMode" to "flat",
        "borderWidth" to 1f,
        "distanceMetric" to "Euclidean",
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
        val numPoints = (params["cellCount"] as? Number)?.toInt() ?: 35
        val bandCount = (params["bandCount"] as? Number)?.toInt() ?: 4
        val bandMode = (params["bandMode"] as? String) ?: "flat"
        val borderWidth = (params["borderWidth"] as? Number)?.toFloat() ?: 1f
        val showEdges = borderWidth > 0f
        val distanceMetric = (params["distanceMetric"] as? String) ?: "Euclidean"
        val relaxed = params["relaxed"] as? Boolean ?: false
        val animSpeed = (params["animSpeed"] as? Number)?.toFloat() ?: 0.4f
        val animAmp = (params["animAmp"] as? Number)?.toFloat() ?: 0.2f

        val rng = SeededRNG(seed)
        val noise = SimplexNoise(seed)

        val px = FloatArray(numPoints)
        val py = FloatArray(numPoints)
        for (i in 0 until numPoints) {
            px[i] = rng.random() * w
            py[i] = rng.random() * h
        }

        // Lloyd relaxation for more uniform cells
        if (relaxed) {
            val relaxStep = 4
            for (pass in 0 until 3) {
                val sumX = FloatArray(numPoints)
                val sumY = FloatArray(numPoints)
                val count = IntArray(numPoints)
                for (sy in 0 until h step relaxStep) {
                    for (sx in 0 until w step relaxStep) {
                        val nearest = findNearestWithMetric(sx.toFloat(), sy.toFloat(), px, py, numPoints, distanceMetric)
                        sumX[nearest] += sx.toFloat()
                        sumY[nearest] += sy.toFloat()
                        count[nearest]++
                    }
                }
                for (i in 0 until numPoints) {
                    if (count[i] > 0) {
                        px[i] = sumX[i] / count[i]
                        py[i] = sumY[i] / count[i]
                    }
                }
            }
        }

        // Animate
        if (time > 0f) {
            val speed = animSpeed / 0.4f
            val amp = animAmp / 0.2f
            for (i in 0 until numPoints) {
                px[i] += noise.noise2D(i * 0.3f + 60f, time * 0.15f * speed) * w * 0.04f * amp
                py[i] += noise.noise2D(i * 0.3f + 160f, time * 0.15f * speed) * h * 0.04f * amp
                px[i] = px[i].coerceIn(0f, w.toFloat() - 1f)
                py[i] = py[i].coerceIn(0f, h.toFloat() - 1f)
            }
        }

        // Build cell assignment map at a lower resolution for speed
        val mapStep = when (quality) {
            Quality.DRAFT -> 3
            Quality.BALANCED -> 2
            Quality.ULTRA -> 1
        }
        val mw = (w + mapStep - 1) / mapStep
        val mh = (h + mapStep - 1) / mapStep
        val cellMap = IntArray(mw * mh)

        for (row in 0 until mh) {
            for (col in 0 until mw) {
                val rx = (col * mapStep).toFloat()
                val ry = (row * mapStep).toFloat()
                cellMap[row * mw + col] = findNearestWithMetric(rx, ry, px, py, numPoints, distanceMetric)
            }
        }

        // Count neighbours per cell by scanning the cell map for border transitions
        val neighbours = Array(numPoints) { mutableSetOf<Int>() }
        for (row in 0 until mh) {
            for (col in 0 until mw) {
                val c = cellMap[row * mw + col]
                if (col + 1 < mw) {
                    val right = cellMap[row * mw + col + 1]
                    if (right != c) {
                        neighbours[c].add(right)
                        neighbours[right].add(c)
                    }
                }
                if (row + 1 < mh) {
                    val below = cellMap[(row + 1) * mw + col]
                    if (below != c) {
                        neighbours[c].add(below)
                        neighbours[below].add(c)
                    }
                }
            }
        }

        // Map neighbour counts to palette
        val neighborCounts = IntArray(numPoints) { neighbours[it].size }
        val maxNeighbors = neighborCounts.maxOrNull()?.coerceAtLeast(1) ?: 1
        val minNeighbors = neighborCounts.minOrNull() ?: 0

        // Render full resolution
        val pixels = IntArray(w * h)

        val renderStep = when (quality) {
            Quality.DRAFT -> 2
            Quality.BALANCED -> 1
            Quality.ULTRA -> 1
        }

        for (row in 0 until h step renderStep) {
            for (col in 0 until w step renderStep) {
                val nearest = findNearestWithMetric(col.toFloat(), row.toFloat(), px, py, numPoints, distanceMetric)
                val nc = neighborCounts[nearest]

                // Check if on edge
                var onEdge = false
                if (showEdges) {
                    for (d in 1..borderWidth.toInt().coerceAtLeast(1)) {
                        if (col + d < w) {
                            val adj = findNearestWithMetric((col + d).toFloat(), row.toFloat(), px, py, numPoints, distanceMetric)
                            if (adj != nearest) { onEdge = true; break }
                        }
                        if (row + d < h) {
                            val adj = findNearestWithMetric(col.toFloat(), (row + d).toFloat(), px, py, numPoints, distanceMetric)
                            if (adj != nearest) { onEdge = true; break }
                        }
                    }
                }

                val color = if (onEdge) {
                    Color.BLACK
                } else {
                    val colors = palette.colorInts()
                    val range = (maxNeighbors - minNeighbors).coerceAtLeast(1)
                    val t = (nc - minNeighbors).toFloat() / range
                    when (bandMode) {
                        "gradient" -> palette.lerpColor(t)
                        "alternating" -> {
                            // Alternate between first and last palette colors based on neighbor band
                            val bandIdx = ((nc - minNeighbors) % bandCount)
                            if (bandIdx % 2 == 0) colors.first() else colors.last()
                        }
                        else -> {
                            // "flat" - discrete color per neighbor band
                            val bandIdx = ((nc - minNeighbors) % bandCount)
                            colors[bandIdx % colors.size]
                        }
                    }
                }

                if (renderStep == 1) {
                    pixels[row * w + col] = color
                } else {
                    for (dy in 0 until renderStep) {
                        for (dx in 0 until renderStep) {
                            val fx = col + dx
                            val fy = row + dy
                            if (fx < w && fy < h) pixels[fy * w + fx] = color
                        }
                    }
                }
            }
        }

        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
        canvas.drawBitmap(bitmap, 0f, 0f, null)
    }

    private fun computeDistance(dx: Float, dy: Float, metric: String): Float = when (metric) {
        "Manhattan" -> kotlin.math.abs(dx) + kotlin.math.abs(dy)
        "Chebyshev" -> maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy))
        else -> dx * dx + dy * dy // Euclidean (squared for comparison)
    }

    private fun findNearestWithMetric(
        x: Float, y: Float,
        px: FloatArray, py: FloatArray,
        numPoints: Int,
        metric: String
    ): Int {
        var bestDist = Float.MAX_VALUE
        var bestIdx = 0
        for (i in 0 until numPoints) {
            val dx = x - px[i]
            val dy = y - py[i]
            val d = computeDistance(dx, dy, metric)
            if (d < bestDist) {
                bestDist = d
                bestIdx = i
            }
        }
        return bestIdx
    }

    private fun findNearest(
        x: Float, y: Float,
        px: FloatArray, py: FloatArray,
        numPoints: Int
    ): Int {
        var bestDist = Float.MAX_VALUE
        var bestIdx = 0
        for (i in 0 until numPoints) {
            val dx = x - px[i]
            val dy = y - py[i]
            val d = dx * dx + dy * dy
            if (d < bestDist) {
                bestDist = d
                bestIdx = i
            }
        }
        return bestIdx
    }

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float {
        val n = (params["cellCount"] as? Number)?.toFloat() ?: 35f
        return (n / 150f).coerceIn(0.3f, 1f)
    }
}
