package com.artmondo.algomodo.generators.cellular

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import com.artmondo.algomodo.data.palettes.Palette
import com.artmondo.algomodo.generators.Generator
import com.artmondo.algomodo.generators.ParamGroup
import com.artmondo.algomodo.generators.Parameter
import com.artmondo.algomodo.generators.Quality

class SandpileGenerator : Generator {

    override val id = "cellular-sandpile"
    override val family = "cellular"
    override val styleName = "Sandpile"
    override val definition = "Abelian sandpile model: grains of sand pile up and topple, producing self-organized fractal patterns."
    override val algorithmNotes = "Grains are dropped at the center of the grid. When a cell's count reaches the threshold, it topples: it loses 'threshold' grains and each of its 4 orthogonal neighbors gains one. Toppling cascades create fractal boundary patterns. The grain count modulo the threshold maps to palette colors."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema = listOf(
        Parameter.NumberParam("Grid Size", "gridSize", ParamGroup.COMPOSITION, null, 32f, 256f, 16f, 128f),
        Parameter.NumberParam("Total Grains (static)", "totalGrains", ParamGroup.COMPOSITION, "Grains dropped before static render", 1000f, 500000f, 1000f, 100000f),
        Parameter.NumberParam("Grains / Frame", "grainsPerFrame", ParamGroup.FLOW_MOTION, "Grains added per animation frame", 1f, 200f, 5f, 20f),
        Parameter.NumberParam("Max Topples / Frame", "maxTopples", ParamGroup.FLOW_MOTION, "Cap on toppling per frame — prevents frame drops; pattern will catch up over time", 100f, 100000f, 100f, 5000f),
        Parameter.SelectParam("Drop Site", "dropSite", ParamGroup.GEOMETRY, "center: classic self-similar pattern | multi: 4 sites at quarter positions | drift: drop site orbits slowly", listOf("center", "multi", "drift"), "center"),
        Parameter.SelectParam("Color Mode", "colorMode", ParamGroup.COLOR, "grain-count: 4-level by grains | fractal: full palette across grain levels | topple-count: log-scale by topple history | avalanche: recently toppled cells glow", listOf("grain-count", "fractal", "topple-count", "avalanche"), "grain-count")
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "gridSize" to 128f,
        "totalGrains" to 100000f,
        "grainsPerFrame" to 20f,
        "maxTopples" to 5000f,
        "dropSite" to "center",
        "colorMode" to "grain-count"
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
        val dropRate = (params["grainsPerFrame"] as? Number)?.toInt() ?: 20
        val gridSize = (params["gridSize"] as? Number)?.toInt() ?: 128
        val totalGrains = (params["totalGrains"] as? Number)?.toInt() ?: 100000
        val maxTopples = (params["maxTopples"] as? Number)?.toInt() ?: 5000
        val dropSite = (params["dropSite"] as? String) ?: "center"
        val colorMode = (params["colorMode"] as? String) ?: "grain-count"
        val threshold = 4

        val w = bitmap.width
        val h = bitmap.height
        // Use totalGrains to scale the simulation; each step drops dropRate grains
        val steps = ((totalGrains.toFloat() / dropRate.coerceAtLeast(1)) * (time * 0.1f + 1f)).toInt()
        val totalCells = gridSize * gridSize
        val cx = gridSize / 2
        val cy = gridSize / 2

        // Initialize empty grid
        val grid = IntArray(totalCells)
        // Track topple counts for topple-count / avalanche color modes
        val toppleCount = IntArray(totalCells)
        val recentlyToppled = BooleanArray(totalCells)

        // Compute drop positions based on dropSite mode
        val dropPositions: List<Pair<Int, Int>> = when (dropSite) {
            "multi" -> {
                val q = gridSize / 4
                listOf(Pair(q, q), Pair(3 * q, q), Pair(q, 3 * q), Pair(3 * q, 3 * q))
            }
            "drift" -> {
                // Single drop site that orbits around center
                listOf(Pair(cx, cy)) // will be overridden per-step below
            }
            else /* center */ -> {
                listOf(Pair(cx + (seed % 3) - 1, cy + ((seed / 3) % 3) - 1))
            }
        }

        val dx4 = intArrayOf(0, 1, 0, -1)
        val dy4 = intArrayOf(-1, 0, 1, 0)

        // Simulate: each step, drop grains then topple until stable
        for (step in 0 until steps) {
            // Reset recently-toppled tracker each step
            for (i in recentlyToppled.indices) recentlyToppled[i] = false

            // Drop grains at the appropriate site(s)
            for (d in 0 until dropRate) {
                val (dropX, dropY) = if (dropSite == "drift") {
                    // Orbiting drop site
                    val angle = step.toFloat() * 0.05f
                    val r = gridSize / 6f
                    val dx = (cx + r * kotlin.math.cos(angle)).toInt().coerceIn(0, gridSize - 1)
                    val dy = (cy + r * kotlin.math.sin(angle)).toInt().coerceIn(0, gridSize - 1)
                    Pair(dx, dy)
                } else {
                    dropPositions[d % dropPositions.size]
                }
                val dIdx = dropY.coerceIn(0, gridSize - 1) * gridSize + dropX.coerceIn(0, gridSize - 1)
                grid[dIdx] += 1
            }

            // Topple until stable, capped by maxTopples
            var unstable = true
            var topplesBudget = maxTopples
            while (unstable && topplesBudget > 0) {
                unstable = false
                for (y in 0 until gridSize) {
                    for (x in 0 until gridSize) {
                        val idx = y * gridSize + x
                        if (grid[idx] >= threshold) {
                            grid[idx] -= threshold
                            toppleCount[idx]++
                            recentlyToppled[idx] = true
                            unstable = true
                            topplesBudget--
                            if (topplesBudget <= 0) break
                            for (dir in 0..3) {
                                val nx = x + dx4[dir]
                                val ny = y + dy4[dir]
                                if (nx in 0 until gridSize && ny in 0 until gridSize) {
                                    grid[ny * gridSize + nx] += 1
                                }
                            }
                        }
                    }
                    if (topplesBudget <= 0) break
                }
            }
        }

        // Render: map cells to colors based on colorMode
        val cellW = w.toFloat() / gridSize
        val cellH = h.toFloat() / gridSize
        val pixels = IntArray(w * h)
        // Find max topple count for normalization
        val maxTopple = toppleCount.maxOrNull()?.coerceAtLeast(1) ?: 1

        for (py in 0 until h) {
            val gy = (py / cellH).toInt().coerceAtMost(gridSize - 1)
            for (px in 0 until w) {
                val gx = (px / cellW).toInt().coerceAtMost(gridSize - 1)
                val idx = gy * gridSize + gx
                val grains = grid[idx]

                pixels[py * w + px] = when (colorMode) {
                    "fractal" -> {
                        // Full palette gradient across grain levels
                        val t = (grains.toFloat() / (threshold - 1).coerceAtLeast(1)).coerceIn(0f, 1f)
                        palette.lerpColor(t)
                    }
                    "topple-count" -> {
                        // Log-scale color by cumulative topple history
                        if (toppleCount[idx] == 0) Color.BLACK
                        else {
                            val t = (kotlin.math.ln(toppleCount[idx].toFloat() + 1f) / kotlin.math.ln(maxTopple.toFloat() + 1f)).coerceIn(0f, 1f)
                            palette.lerpColor(t)
                        }
                    }
                    "avalanche" -> {
                        // Recently toppled cells glow, others show grain count
                        if (recentlyToppled[idx]) {
                            palette.lerpColor(1f) // brightest palette color
                        } else if (grains == 0) {
                            Color.BLACK
                        } else {
                            val t = (grains.toFloat() / (threshold - 1).coerceAtLeast(1)).coerceIn(0f, 1f)
                            palette.lerpColor(t * 0.5f) // dimmer for non-active cells
                        }
                    }
                    else /* grain-count */ -> {
                        // 4-level by grain count (original behavior)
                        val t = (grains.toFloat() / (threshold - 1).coerceAtLeast(1)).coerceIn(0f, 1f)
                        if (grains == 0) Color.BLACK else palette.lerpColor(t)
                    }
                }
            }
        }

        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
        canvas.drawBitmap(bitmap, 0f, 0f, null)
    }
}
