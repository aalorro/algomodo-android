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
import kotlin.math.sqrt

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
        if (numPoints <= 0) { canvas.drawColor(Color.BLACK); return }
        val bandCount = (params["bandCount"] as? Number)?.toInt() ?: 4
        val bandMode = (params["bandMode"] as? String) ?: "flat"
        val borderWidth = (params["borderWidth"] as? Number)?.toFloat() ?: 1f
        val showEdges = borderWidth > 0f
        val distanceMetric = (params["distanceMetric"] as? String) ?: "Euclidean"
        val relaxed = params["relaxed"] as? Boolean ?: false
        val animSpeed = (params["animSpeed"] as? Number)?.toFloat() ?: 0.4f
        val animAmp = (params["animAmp"] as? Number)?.toFloat() ?: 0.2f

        val metricId = when (distanceMetric.lowercase()) {
            "manhattan" -> 1; "chebyshev" -> 2; else -> 0
        }
        val isEuclidean = metricId == 0
        val bandModeId = when (bandMode) { "gradient" -> 1; "alternating" -> 2; else -> 0 }

        val rng = SeededRNG(seed)
        val noise = SimplexNoise(seed)

        val px = FloatArray(numPoints)
        val py = FloatArray(numPoints)
        for (i in 0 until numPoints) {
            px[i] = rng.random() * w
            py[i] = rng.random() * h
        }

        // Lloyd relaxation using spatial grid
        if (relaxed) {
            val relaxStep = 4
            for (pass in 0 until 3) {
                val rGs = (maxOf(w, h).toFloat() / sqrt(numPoints.toFloat())).coerceAtLeast(8f)
                val rInv = 1f / rGs
                val rGc = (w * rInv).toInt() + 1
                val rGr = (h * rInv).toInt() + 1
                val rGcM1 = rGc - 1; val rGrM1 = rGr - 1
                val rGh = IntArray(rGc * rGr) { -1 }
                val rGn = IntArray(numPoints) { -1 }
                for (i in 0 until numPoints) {
                    val gx = minOf((px[i] * rInv).toInt(), rGcM1)
                    val gy = minOf((py[i] * rInv).toInt(), rGrM1)
                    val cell = gy * rGc + gx
                    rGn[i] = rGh[cell]; rGh[cell] = i
                }
                val sumX = FloatArray(numPoints)
                val sumY = FloatArray(numPoints)
                val count = IntArray(numPoints)
                for (sy in 0 until h step relaxStep) {
                    val yf = sy.toFloat()
                    val gy = minOf((yf * rInv).toInt(), rGrM1)
                    val cyMin = maxOf(gy - 1, 0); val cyMax = minOf(gy + 1, rGrM1)
                    for (sx in 0 until w step relaxStep) {
                        val xf = sx.toFloat()
                        val gx = minOf((xf * rInv).toInt(), rGcM1)
                        val cxMin = maxOf(gx - 1, 0); val cxMax = minOf(gx + 1, rGcM1)
                        var bd = Float.MAX_VALUE; var bi = 0
                        for (cy in cyMin..cyMax) {
                            val ro = cy * rGc
                            for (cx in cxMin..cxMax) {
                                var ii = rGh[ro + cx]
                                while (ii >= 0) {
                                    val ddx = xf - px[ii]; val ddy = yf - py[ii]
                                    val d = when (metricId) {
                                        1 -> { val ax = if (ddx < 0f) -ddx else ddx; val ay = if (ddy < 0f) -ddy else ddy; ax + ay }
                                        2 -> { val ax = if (ddx < 0f) -ddx else ddx; val ay = if (ddy < 0f) -ddy else ddy; if (ax > ay) ax else ay }
                                        else -> ddx * ddx + ddy * ddy
                                    }
                                    if (d < bd) { bd = d; bi = ii }
                                    ii = rGn[ii]
                                }
                            }
                        }
                        sumX[bi] += xf; sumY[bi] += yf; count[bi]++
                    }
                }
                for (i in 0 until numPoints) {
                    if (count[i] > 0) { px[i] = sumX[i] / count[i]; py[i] = sumY[i] / count[i] }
                }
            }
        }

        // Animate
        if (time > 0f) {
            val speed = animSpeed / 0.4f; val amp = animAmp / 0.2f
            val wf = w.toFloat(); val hf = h.toFloat()
            for (i in 0 until numPoints) {
                px[i] = (px[i] + noise.noise2D(i * 0.3f + 60f, time * 0.15f * speed) * wf * 0.04f * amp).coerceIn(0f, wf - 1f)
                py[i] = (py[i] + noise.noise2D(i * 0.3f + 160f, time * 0.15f * speed) * hf * 0.04f * amp).coerceIn(0f, hf - 1f)
            }
        }

        // ── Spatial grid (linked-list) ──
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

        // Build cell assignment map at reduced resolution using grid search
        val mapStep = when (quality) {
            Quality.DRAFT -> 3; Quality.BALANCED -> 2; Quality.ULTRA -> 1
        }
        val mw = (w + mapStep - 1) / mapStep
        val mh = (h + mapStep - 1) / mapStep
        val cellMap = IntArray(mw * mh)

        if (isEuclidean) {
            for (row in 0 until mh) {
                val ry = (row * mapStep).toFloat()
                val gy = minOf((ry * invGridSize).toInt(), grM1)
                val cyMin = maxOf(gy - 1, 0); val cyMax = minOf(gy + 1, grM1)
                val mapRowOff = row * mw
                for (col in 0 until mw) {
                    val rx = (col * mapStep).toFloat()
                    val gx = minOf((rx * invGridSize).toInt(), gcM1)
                    val cxMin = maxOf(gx - 1, 0); val cxMax = minOf(gx + 1, gcM1)
                    var bd = Float.MAX_VALUE; var bi = 0
                    for (cy in cyMin..cyMax) {
                        val ro = cy * gridCols
                        for (cx in cxMin..cxMax) {
                            var ii = gridHeads[ro + cx]
                            while (ii >= 0) {
                                val dx = rx - px[ii]; val dy = ry - py[ii]
                                val d = dx * dx + dy * dy
                                if (d < bd) { bd = d; bi = ii }
                                ii = gridNext[ii]
                            }
                        }
                    }
                    cellMap[mapRowOff + col] = bi
                }
            }
        } else if (metricId == 1) {
            for (row in 0 until mh) {
                val ry = (row * mapStep).toFloat()
                val gy = minOf((ry * invGridSize).toInt(), grM1)
                val cyMin = maxOf(gy - 1, 0); val cyMax = minOf(gy + 1, grM1)
                val mapRowOff = row * mw
                for (col in 0 until mw) {
                    val rx = (col * mapStep).toFloat()
                    val gx = minOf((rx * invGridSize).toInt(), gcM1)
                    val cxMin = maxOf(gx - 1, 0); val cxMax = minOf(gx + 1, gcM1)
                    var bd = Float.MAX_VALUE; var bi = 0
                    for (cy in cyMin..cyMax) {
                        val ro = cy * gridCols
                        for (cx in cxMin..cxMax) {
                            var ii = gridHeads[ro + cx]
                            while (ii >= 0) {
                                val dx = rx - px[ii]; val dy = ry - py[ii]
                                val adx = if (dx < 0f) -dx else dx
                                val ady = if (dy < 0f) -dy else dy
                                val d = adx + ady
                                if (d < bd) { bd = d; bi = ii }
                                ii = gridNext[ii]
                            }
                        }
                    }
                    cellMap[mapRowOff + col] = bi
                }
            }
        } else {
            for (row in 0 until mh) {
                val ry = (row * mapStep).toFloat()
                val gy = minOf((ry * invGridSize).toInt(), grM1)
                val cyMin = maxOf(gy - 1, 0); val cyMax = minOf(gy + 1, grM1)
                val mapRowOff = row * mw
                for (col in 0 until mw) {
                    val rx = (col * mapStep).toFloat()
                    val gx = minOf((rx * invGridSize).toInt(), gcM1)
                    val cxMin = maxOf(gx - 1, 0); val cxMax = minOf(gx + 1, gcM1)
                    var bd = Float.MAX_VALUE; var bi = 0
                    for (cy in cyMin..cyMax) {
                        val ro = cy * gridCols
                        for (cx in cxMin..cxMax) {
                            var ii = gridHeads[ro + cx]
                            while (ii >= 0) {
                                val dx = rx - px[ii]; val dy = ry - py[ii]
                                val adx = if (dx < 0f) -dx else dx
                                val ady = if (dy < 0f) -dy else dy
                                val d = if (adx > ady) adx else ady
                                if (d < bd) { bd = d; bi = ii }
                                ii = gridNext[ii]
                            }
                        }
                    }
                    cellMap[mapRowOff + col] = bi
                }
            }
        }

        // Count neighbours per cell by scanning the cell map for border transitions
        val neighbours = Array(numPoints) { mutableSetOf<Int>() }
        for (row in 0 until mh) {
            val mapRowOff = row * mw
            for (col in 0 until mw) {
                val c = cellMap[mapRowOff + col]
                if (col + 1 < mw) {
                    val right = cellMap[mapRowOff + col + 1]
                    if (right != c) { neighbours[c].add(right); neighbours[right].add(c) }
                }
                if (row + 1 < mh) {
                    val below = cellMap[(row + 1) * mw + col]
                    if (below != c) { neighbours[c].add(below); neighbours[below].add(c) }
                }
            }
        }

        val neighborCounts = IntArray(numPoints) { neighbours[it].size }
        val maxNeighbors = neighborCounts.maxOrNull()?.coerceAtLeast(1) ?: 1
        val minNeighbors = neighborCounts.minOrNull() ?: 0
        val range = (maxNeighbors - minNeighbors).coerceAtLeast(1)

        // Pre-compute cell colors
        val colors = palette.colorInts()
        val colorsSize = colors.size
        val lut = if (bandModeId == 1) palette.buildLut(256) else null
        val cellColors = IntArray(numPoints)
        for (i in 0 until numPoints) {
            val nc = neighborCounts[i]
            cellColors[i] = when (bandModeId) {
                1 -> { val t = (nc - minNeighbors).toFloat() / range; lut!![(t * 255f).toInt().coerceIn(0, 255)] }
                2 -> { val bandIdx = (nc - minNeighbors) % bandCount; if (bandIdx % 2 == 0) colors.first() else colors.last() }
                else -> { val bandIdx = (nc - minNeighbors) % bandCount; colors[bandIdx % colorsSize] }
            }
        }

        val pixels = IntArray(w * h)
        val renderStep = when (quality) { Quality.DRAFT -> 2; else -> 1 }

        if (isEuclidean && !showEdges) {
            // ── EUCLIDEAN NO-EDGE FAST PATH ──
            for (row in 0 until h step renderStep) {
                val y = row.toFloat()
                val rowOff = row * w
                val gy = minOf((y * invGridSize).toInt(), grM1)
                val cyMin = maxOf(gy - 1, 0); val cyMax = minOf(gy + 1, grM1)
                for (col in 0 until w step renderStep) {
                    val x = col.toFloat()
                    val gx = minOf((x * invGridSize).toInt(), gcM1)
                    val cxMin = maxOf(gx - 1, 0); val cxMax = minOf(gx + 1, gcM1)
                    var bd = Float.MAX_VALUE; var bi = 0
                    for (cy in cyMin..cyMax) {
                        val ro = cy * gridCols
                        for (cx in cxMin..cxMax) {
                            var ii = gridHeads[ro + cx]
                            while (ii >= 0) {
                                val dx = x - px[ii]; val dy = y - py[ii]
                                val d = dx * dx + dy * dy
                                if (d < bd) { bd = d; bi = ii }
                                ii = gridNext[ii]
                            }
                        }
                    }
                    val color = cellColors[bi]
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
        } else if (isEuclidean) {
            // ── EUCLIDEAN WITH EDGES ──
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
                    var f1 = Float.MAX_VALUE; var f2 = Float.MAX_VALUE; var bi = 0
                    for (cy in cyMin..cyMax) {
                        val ro = cy * gridCols
                        for (cx in cxMin..cxMax) {
                            var ii = gridHeads[ro + cx]
                            while (ii >= 0) {
                                val dx = x - px[ii]; val dy = y - py[ii]
                                val d = dx * dx + dy * dy
                                if (d < f1) { f2 = f1; f1 = d; bi = ii }
                                else if (d < f2) { f2 = d }
                                ii = gridNext[ii]
                            }
                        }
                    }
                    val sqrtF1 = sqrt(f1)
                    val isEdge = f2 < f1 + edgeThreshSq + 2f * edgeThresh * sqrtF1
                    val color = if (isEdge) Color.BLACK else cellColors[bi]
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
        } else if (metricId == 1) {
            // ── MANHATTAN PATH: inline abs, early exit on |dx| ──
            val edgeThresh = borderWidth * 2f
            for (row in 0 until h step renderStep) {
                val y = row.toFloat()
                val rowOff = row * w
                val gy = minOf((y * invGridSize).toInt(), grM1)
                val cyMin = maxOf(gy - 1, 0); val cyMax = minOf(gy + 1, grM1)
                for (col in 0 until w step renderStep) {
                    val x = col.toFloat()
                    val gx = minOf((x * invGridSize).toInt(), gcM1)
                    val cxMin = maxOf(gx - 1, 0); val cxMax = minOf(gx + 1, gcM1)
                    var f1 = Float.MAX_VALUE; var f2 = Float.MAX_VALUE; var bi = 0
                    for (cy in cyMin..cyMax) {
                        val ro = cy * gridCols
                        for (cx in cxMin..cxMax) {
                            var ii = gridHeads[ro + cx]
                            while (ii >= 0) {
                                val dx = x - px[ii]; val dy = y - py[ii]
                                val adx = if (dx < 0f) -dx else dx
                                if (adx < f1) {
                                    val ady = if (dy < 0f) -dy else dy
                                    val d = adx + ady
                                    if (d < f1) { f2 = f1; f1 = d; bi = ii }
                                    else if (d < f2) { f2 = d }
                                }
                                ii = gridNext[ii]
                            }
                        }
                    }
                    val isEdge = showEdges && (f2 - f1) < edgeThresh
                    val color = if (isEdge) Color.BLACK else cellColors[bi]
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
            // ── CHEBYSHEV PATH: inline abs/max, early exit on |dx| ──
            val edgeThresh = borderWidth * 2f
            for (row in 0 until h step renderStep) {
                val y = row.toFloat()
                val rowOff = row * w
                val gy = minOf((y * invGridSize).toInt(), grM1)
                val cyMin = maxOf(gy - 1, 0); val cyMax = minOf(gy + 1, grM1)
                for (col in 0 until w step renderStep) {
                    val x = col.toFloat()
                    val gx = minOf((x * invGridSize).toInt(), gcM1)
                    val cxMin = maxOf(gx - 1, 0); val cxMax = minOf(gx + 1, gcM1)
                    var f1 = Float.MAX_VALUE; var f2 = Float.MAX_VALUE; var bi = 0
                    for (cy in cyMin..cyMax) {
                        val ro = cy * gridCols
                        for (cx in cxMin..cxMax) {
                            var ii = gridHeads[ro + cx]
                            while (ii >= 0) {
                                val dx = x - px[ii]; val dy = y - py[ii]
                                val adx = if (dx < 0f) -dx else dx
                                if (adx < f1) {
                                    val ady = if (dy < 0f) -dy else dy
                                    val d = if (adx > ady) adx else ady
                                    if (d < f1) { f2 = f1; f1 = d; bi = ii }
                                    else if (d < f2) { f2 = d }
                                }
                                ii = gridNext[ii]
                            }
                        }
                    }
                    val isEdge = showEdges && (f2 - f1) < edgeThresh
                    val color = if (isEdge) Color.BLACK else cellColors[bi]
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
    }

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float {
        val n = (params["cellCount"] as? Number)?.toFloat() ?: 35f
        return (n / 150f).coerceIn(0.3f, 1f)
    }
}
