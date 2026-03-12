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
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Fractured / multi-level Voronoi generator.
 *
 * Creates a hierarchical Voronoi pattern where each cell is subdivided by
 * a secondary (and optionally tertiary) set of seed points, producing a
 * shattered-glass or fractured-rock appearance.
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

        // Read ALL parameters from the params map
        val numPoints = (params["shardCount"] as? Number)?.toInt() ?: 25
        val fractureCount = (params["fractureCount"] as? Number)?.toInt() ?: 60
        val crackWidth = (params["crackWidth"] as? Number)?.toFloat() ?: 1.5f
        val fractureWidth = (params["fractureWidth"] as? Number)?.toFloat() ?: 0.8f
        val shadeStrength = (params["shadeStrength"] as? Number)?.toFloat() ?: 0.5f
        val colorMode = (params["colorMode"] as? String) ?: "palette-cycle"
        val distanceMetric = (params["distanceMetric"] as? String) ?: "Euclidean"
        val animSpeed = (params["animSpeed"] as? Number)?.toFloat() ?: 0.3f
        val animAmp = (params["animAmp"] as? Number)?.toFloat() ?: 0.15f

        val fractureLevels = 2
        val irregularity = 0.5f

        // Pre-compute distance metric function ID to avoid string comparison in inner loop
        val metricId = when (distanceMetric.lowercase()) {
            "manhattan" -> 1
            "chebyshev" -> 2
            else -> 0 // Euclidean (squared)
        }

        val noise = SimplexNoise(seed)

        // Generate point sets for each fracture level
        data class PointSet(
            val px: FloatArray,
            val py: FloatArray,
            val count: Int,
            val grid: Array<MutableList<Int>>,
            val gridCols: Int,
            val gridRows: Int,
            val cellW: Float,
            val cellH: Float
        )

        val levels = mutableListOf<PointSet>()

        for (level in 0 until fractureLevels) {
            val rng = SeededRNG(seed + level * 7919)
            val count = if (level == 0) numPoints else fractureCount.coerceAtMost(500)

            val lpx = FloatArray(count)
            val lpy = FloatArray(count)

            if (level == 0) {
                for (i in 0 until count) {
                    lpx[i] = rng.random() * w
                    lpy[i] = rng.random() * h
                }
            } else {
                // Sub-level points: create a jittered grid with irregularity
                val cols = sqrt(count.toFloat()).toInt().coerceAtLeast(1)
                val rows = (count + cols - 1) / cols
                val stepW = w.toFloat() / cols
                val stepH = h.toFloat() / rows
                var idx = 0
                for (r in 0 until rows) {
                    for (c in 0 until cols) {
                        if (idx >= count) break
                        val baseX = (c + 0.5f) * stepW
                        val baseY = (r + 0.5f) * stepH
                        lpx[idx] = baseX + (rng.random() - 0.5f) * stepW * irregularity
                        lpy[idx] = baseY + (rng.random() - 0.5f) * stepH * irregularity
                        lpx[idx] = lpx[idx].coerceIn(0f, w.toFloat() - 1f)
                        lpy[idx] = lpy[idx].coerceIn(0f, h.toFloat() - 1f)
                        idx++
                    }
                }
            }

            // Animate using animSpeed and animAmp parameters
            if (time > 0f && animSpeed > 0f && animAmp > 0f) {
                val speed = animSpeed / (level + 1)
                val amp = animAmp * 0.15f // Scale amplitude relative to canvas size
                for (i in 0 until count) {
                    lpx[i] += noise.noise2D(i * 0.2f + level * 100f, time * speed) * w * amp
                    lpy[i] += noise.noise2D(i * 0.2f + level * 100f + 500f, time * speed) * h * amp
                    lpx[i] = lpx[i].coerceIn(0f, w.toFloat() - 1f)
                    lpy[i] = lpy[i].coerceIn(0f, h.toFloat() - 1f)
                }
            }

            // Build grid lookup
            val gridCols = (sqrt(count.toFloat()) * 1.5f).toInt().coerceAtLeast(1)
            val gridRows = gridCols
            val cellW = w.toFloat() / gridCols
            val cellH = h.toFloat() / gridRows
            val grid = Array(gridRows * gridCols) { mutableListOf<Int>() }
            for (i in 0 until count) {
                val gc = (lpx[i] / cellW).toInt().coerceIn(0, gridCols - 1)
                val gr = (lpy[i] / cellH).toInt().coerceIn(0, gridRows - 1)
                grid[gr * gridCols + gc].add(i)
            }

            levels.add(PointSet(lpx, lpy, count, grid, gridCols, gridRows, cellW, cellH))
        }

        val pixels = IntArray(w * h)
        val colors = palette.colorInts()

        // Pre-compute a fixed shading direction (top-left light source)
        val lightDirX = 0.7071f // normalized (1,1) / sqrt(2)
        val lightDirY = 0.7071f

        val step = when (quality) {
            Quality.DRAFT -> 2
            Quality.BALANCED -> 1
            Quality.ULTRA -> 1
        }

        for (row in 0 until h step step) {
            for (col in 0 until w step step) {
                val x = col.toFloat()
                val y = row.toFloat()

                // Find nearest and second-nearest for each level using the selected metric
                var combinedIndex = 0
                var isCrack = false
                var isFracture = false
                var primaryIdx = 0

                for (level in levels.indices) {
                    val ps = levels[level]
                    val result = findNearest2InGrid(
                        x, y,
                        ps.px, ps.py, ps.count,
                        ps.grid, ps.gridCols, ps.gridRows, ps.cellW, ps.cellH,
                        metricId
                    )
                    val nearestIdx = result[0].toInt()
                    val bestDist = result[1]
                    val secondDist = result[2]

                    combinedIndex = combinedIndex * 7 + nearestIdx

                    // Edge detection: compare gap between 1st and 2nd nearest distances
                    val gap = secondDist - bestDist
                    if (level == 0) {
                        primaryIdx = nearestIdx
                        // Crack threshold scales with crackWidth
                        if (crackWidth > 0f && gap < crackWidth * 3f) {
                            isCrack = true
                        }
                    } else {
                        // Fracture threshold scales with fractureWidth
                        if (fractureWidth > 0f && gap < fractureWidth * 2f) {
                            isFracture = true
                        }
                    }
                }

                // Determine base color based on colorMode
                val baseColor: Int = when (colorMode) {
                    "palette-gradient" -> {
                        // Map the combined index as a continuous gradient across the palette
                        val t = if (levels[0].count > 1) {
                            (primaryIdx.toFloat() / (levels[0].count - 1).toFloat())
                        } else 0f
                        palette.lerpColor(t)
                    }
                    "monochrome" -> {
                        // Use a single palette color, vary brightness by cell index
                        val baseC = colors[0]
                        val br = 0.5f + 0.5f * ((combinedIndex * 4973) and 0xFF) / 255f
                        val r = (Color.red(baseC) * br).toInt().coerceIn(0, 255)
                        val g = (Color.green(baseC) * br).toInt().coerceIn(0, 255)
                        val b = (Color.blue(baseC) * br).toInt().coerceIn(0, 255)
                        Color.rgb(r, g, b)
                    }
                    else -> {
                        // "palette-cycle" - original behavior: cycle through palette by combined index
                        val colorIdx = (combinedIndex and 0x7FFFFFFF) % colors.size
                        colors[colorIdx]
                    }
                }

                // Apply shadeStrength: directional shading to simulate 3D shard tilt
                val color: Int = if (shadeStrength > 0f) {
                    // Compute a pseudo-normal based on offset from nearest primary cell center
                    val ps0 = levels[0]
                    val cx = ps0.px[primaryIdx]
                    val cy = ps0.py[primaryIdx]
                    val offX = x - cx
                    val offY = y - cy
                    // Normalize offset relative to approximate cell radius
                    val cellRadius = sqrt(w.toFloat() * h.toFloat() / numPoints.toFloat()) * 0.5f
                    val nx = (offX / cellRadius).coerceIn(-1f, 1f)
                    val ny = (offY / cellRadius).coerceIn(-1f, 1f)
                    // Dot product with light direction gives shading factor
                    val dot = nx * lightDirX + ny * lightDirY // range ~[-1, 1]
                    val shade = 1f + dot * shadeStrength * 0.5f // range roughly [0.75, 1.25]
                    val r = (Color.red(baseColor) * shade).toInt().coerceIn(0, 255)
                    val g = (Color.green(baseColor) * shade).toInt().coerceIn(0, 255)
                    val b = (Color.blue(baseColor) * shade).toInt().coerceIn(0, 255)
                    Color.rgb(r, g, b)
                } else {
                    baseColor
                }

                // Apply crack and fracture lines on top: cracks are darker/black, fractures are subtler
                val finalColor: Int = when {
                    isCrack -> Color.BLACK
                    isFracture -> {
                        // Darken the color for fracture lines
                        val r = (Color.red(color) * 0.4f).toInt()
                        val g = (Color.green(color) * 0.4f).toInt()
                        val b = (Color.blue(color) * 0.4f).toInt()
                        Color.rgb(r, g, b)
                    }
                    else -> color
                }

                if (step == 1) {
                    pixels[row * w + col] = finalColor
                } else {
                    for (dy in 0 until step) {
                        for (dx in 0 until step) {
                            val fx = col + dx
                            val fy = row + dy
                            if (fx < w && fy < h) pixels[fy * w + fx] = finalColor
                        }
                    }
                }
            }
        }

        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
        canvas.drawBitmap(bitmap, 0f, 0f, null)
    }

    /**
     * Find nearest and second-nearest point in the grid, returning a 3-element array:
     * [nearestIndex, bestDist, secondBestDist].
     *
     * @param metricId 0=Euclidean(squared), 1=Manhattan, 2=Chebyshev
     */
    private fun findNearest2InGrid(
        x: Float, y: Float,
        px: FloatArray, py: FloatArray, count: Int,
        grid: Array<MutableList<Int>>,
        gridCols: Int, gridRows: Int,
        cellW: Float, cellH: Float,
        metricId: Int
    ): FloatArray {
        val gc = (x / cellW).toInt().coerceIn(0, gridCols - 1)
        val gr = (y / cellH).toInt().coerceIn(0, gridRows - 1)

        var bestDist = Float.MAX_VALUE
        var secondDist = Float.MAX_VALUE
        var bestIdx = 0

        for (dr in -2..2) {
            for (dc in -2..2) {
                val ngr = gr + dr
                val ngc = gc + dc
                if (ngr < 0 || ngr >= gridRows || ngc < 0 || ngc >= gridCols) continue
                for (pi in grid[ngr * gridCols + ngc]) {
                    val dx = x - px[pi]
                    val dy = y - py[pi]
                    val d = when (metricId) {
                        1 -> abs(dx) + abs(dy)         // Manhattan
                        2 -> max(abs(dx), abs(dy))     // Chebyshev
                        else -> dx * dx + dy * dy      // Euclidean (squared)
                    }
                    if (d < bestDist) {
                        secondDist = bestDist
                        bestDist = d
                        bestIdx = pi
                    } else if (d < secondDist) {
                        secondDist = d
                    }
                }
            }
        }

        return floatArrayOf(bestIdx.toFloat(), bestDist, secondDist)
    }

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float {
        val n = (params["shardCount"] as? Number)?.toFloat() ?: 25f
        val fc = (params["fractureCount"] as? Number)?.toFloat() ?: 60f
        return ((n + fc) / 200f).coerceIn(0.3f, 1f)
    }
}
