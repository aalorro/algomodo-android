package com.artmondo.algomodo.generators.cellular

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import com.artmondo.algomodo.core.rng.SeededRNG
import com.artmondo.algomodo.data.palettes.Palette
import com.artmondo.algomodo.generators.Generator
import com.artmondo.algomodo.generators.ParamGroup
import com.artmondo.algomodo.generators.Parameter
import com.artmondo.algomodo.generators.Quality

class EdenGrowthGenerator : Generator {

    override val id = "cellular-eden-growth"
    override val family = "cellular"
    override val styleName = "Eden Growth"
    override val definition = "Eden model: a stochastic growth model where occupied cells expand at their boundary, producing rough, compact clusters."
    override val algorithmNotes = "Starting from seed cells, the boundary of the occupied region is computed each step. Each boundary-adjacent empty cell is filled with the given growth probability. The growth order is tracked for palette coloring, producing radial gradient-like patterns."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema = listOf(
        Parameter.NumberParam("Grid Size", "gridSize", ParamGroup.COMPOSITION, null, 32f, 256f, 16f, 128f),
        Parameter.NumberParam("Seed Count", "seedCount", ParamGroup.COMPOSITION, "Number of competing seed clusters — each grows simultaneously and is shown in a different palette color when colorMode is \"team\"", 1f, 6f, 1f, 1f),
        Parameter.NumberParam("Target Fill (%)", "targetFill", ParamGroup.COMPOSITION, "Percentage of grid to fill before stopping (static render)", 5f, 100f, 5f, 85f),
        Parameter.NumberParam("Cells / Frame", "cellsPerFrame", ParamGroup.FLOW_MOTION, "New cells grown per animation frame", 1f, 500f, 10f, 80f),
        Parameter.SelectParam("Connectivity", "connectivity", ParamGroup.GEOMETRY, "4-connected: cardinal directions only | 8-connected: diagonals included, producing rounder, denser clusters", listOf("4", "8"), "4"),
        Parameter.SelectParam("Color Mode", "colorMode", ParamGroup.COLOR, "birth-order: smooth palette gradient | concentric: quantized rings | team: competing seeds in distinct colors | monochrome: flat fill", listOf("birth-order", "concentric", "team", "monochrome"), "birth-order")
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "gridSize" to 128f,
        "seedCount" to 1f,
        "targetFill" to 85f,
        "cellsPerFrame" to 80f,
        "connectivity" to "4",
        "colorMode" to "birth-order"
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
        val gridSize = (params["gridSize"] as? Number)?.toInt() ?: 128
        val numSeeds = (params["seedCount"] as? Number)?.toInt() ?: 1
        val targetFill = (params["targetFill"] as? Number)?.toFloat() ?: 85f
        val cellsPerFrame = (params["cellsPerFrame"] as? Number)?.toFloat() ?: 80f
        val connectivity = (params["connectivity"] as? String) ?: "4"
        val colorMode = (params["colorMode"] as? String) ?: "birth-order"

        val w = bitmap.width
        val h = bitmap.height
        val totalCells = gridSize * gridSize
        val targetCells = (totalCells * targetFill / 100f).toInt()

        // Direction offsets based on connectivity
        val dx: IntArray
        val dy: IntArray
        if (connectivity == "8") {
            dx = intArrayOf(0, 1, 1, 1, 0, -1, -1, -1)
            dy = intArrayOf(-1, -1, 0, 1, 1, 1, 0, -1)
        } else {
            dx = intArrayOf(0, 1, 0, -1)
            dy = intArrayOf(-1, 0, 1, 0)
        }
        val numDirs = dx.size

        // Step count: animation uses time * cellsPerFrame, static fills to targetFill
        val maxSteps = if (time > 0.01f) (time * cellsPerFrame).toInt() else targetCells

        val rng = SeededRNG(seed)

        val occupied = BooleanArray(totalCells)
        val growthOrder = IntArray(totalCells) { -1 }
        val ownerTeam = IntArray(totalCells) { -1 }  // which seed cluster owns each cell
        var orderCount = 0

        val cx = gridSize / 2
        val cy = gridSize / 2

        // Place seed points
        for (s in 0 until numSeeds) {
            val sx = if (numSeeds == 1) cx else {
                val angle = 2.0 * Math.PI * s / numSeeds + rng.random() * 0.3
                (cx + (gridSize * 0.2) * Math.cos(angle)).toInt().coerceIn(0, gridSize - 1)
            }
            val sy = if (numSeeds == 1) cy else {
                val angle = 2.0 * Math.PI * s / numSeeds + rng.random() * 0.3
                (cy + (gridSize * 0.2) * Math.sin(angle)).toInt().coerceIn(0, gridSize - 1)
            }
            val idx = sy * gridSize + sx
            if (!occupied[idx]) {
                occupied[idx] = true
                growthOrder[idx] = orderCount++
                ownerTeam[idx] = s
            }
        }

        // Boundary set: empty cells adjacent to at least one occupied cell
        // Store which team(s) could claim each boundary cell
        val boundary = mutableMapOf<Int, Int>() // idx -> team that touches it (-1 if multiple)

        fun addBoundaryNeighbors(cellIdx: Int, team: Int) {
            val cellX = cellIdx % gridSize
            val cellY = cellIdx / gridSize
            for (d in 0 until numDirs) {
                val nx = cellX + dx[d]
                val ny = cellY + dy[d]
                if (nx in 0 until gridSize && ny in 0 until gridSize) {
                    val nIdx = ny * gridSize + nx
                    if (!occupied[nIdx]) {
                        val existing = boundary[nIdx]
                        if (existing == null) {
                            boundary[nIdx] = team
                        } else if (existing != team) {
                            boundary[nIdx] = -1 // contested
                        }
                    }
                }
            }
        }

        // Initialize boundary from seed cells
        for (y in 0 until gridSize) {
            for (x in 0 until gridSize) {
                val idx = y * gridSize + x
                if (occupied[idx]) {
                    addBoundaryNeighbors(idx, ownerTeam[idx])
                }
            }
        }

        // Grow
        for (step in 0 until maxSteps) {
            if (boundary.isEmpty()) break
            if (orderCount >= targetCells && time <= 0.01f) break

            // Pick a random boundary cell to fill
            val entries = boundary.entries.toList()
            val pick = entries[rng.integer(0, entries.size - 1)]
            val bIdx = pick.key
            val bTeam = pick.value

            occupied[bIdx] = true
            growthOrder[bIdx] = orderCount++

            // Assign team: if contested, pick from adjacent occupied teams
            if (bTeam >= 0) {
                ownerTeam[bIdx] = bTeam
            } else {
                // Find adjacent teams and pick one randomly
                val bx = bIdx % gridSize
                val by = bIdx / gridSize
                val adjTeams = mutableListOf<Int>()
                for (d in 0 until numDirs) {
                    val nx = bx + dx[d]
                    val ny = by + dy[d]
                    if (nx in 0 until gridSize && ny in 0 until gridSize) {
                        val nIdx = ny * gridSize + nx
                        if (occupied[nIdx] && ownerTeam[nIdx] >= 0) {
                            adjTeams.add(ownerTeam[nIdx])
                        }
                    }
                }
                ownerTeam[bIdx] = if (adjTeams.isNotEmpty()) {
                    adjTeams[rng.integer(0, adjTeams.size - 1)]
                } else 0
            }

            boundary.remove(bIdx)
            addBoundaryNeighbors(bIdx, ownerTeam[bIdx])
        }

        // Render
        val pixels = IntArray(w * h)
        val cellW = w.toFloat() / gridSize
        val cellH = h.toFloat() / gridSize
        val maxOrder = orderCount.coerceAtLeast(1)
        val paletteColors = palette.colorInts()
        val concentricBands = 12

        for (py in 0 until h) {
            val gy = (py / cellH).toInt().coerceAtMost(gridSize - 1)
            for (px in 0 until w) {
                val gx = (px / cellW).toInt().coerceAtMost(gridSize - 1)
                val idx = gy * gridSize + gx

                pixels[py * w + px] = if (occupied[idx]) {
                    when (colorMode) {
                        "concentric" -> {
                            // Quantize birth order into bands
                            val t = growthOrder[idx].toFloat() / maxOrder
                            val band = (t * concentricBands).toInt().coerceIn(0, concentricBands - 1)
                            val bandT = band.toFloat() / (concentricBands - 1)
                            palette.lerpColor(bandT)
                        }
                        "team" -> {
                            // Each seed cluster gets a distinct palette color
                            val team = ownerTeam[idx].coerceAtLeast(0)
                            if (paletteColors.size > 1) {
                                paletteColors[team % paletteColors.size]
                            } else {
                                palette.lerpColor(team.toFloat() / numSeeds.coerceAtLeast(1))
                            }
                        }
                        "monochrome" -> {
                            paletteColors[0]
                        }
                        else -> { // birth-order
                            val t = growthOrder[idx].toFloat() / maxOrder
                            palette.lerpColor(t)
                        }
                    }
                } else {
                    Color.BLACK
                }
            }
        }

        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
        canvas.drawBitmap(bitmap, 0f, 0f, null)
    }
}
