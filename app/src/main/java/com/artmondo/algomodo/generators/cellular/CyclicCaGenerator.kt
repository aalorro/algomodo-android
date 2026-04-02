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

class CyclicCaGenerator : Generator {

    override val id = "cellular-cyclic-ca"
    override val family = "cellular"
    override val styleName = "Cyclic CA"
    override val definition = "Cyclic cellular automaton where states cycle in order, producing rotating spiral and turbulent patterns."
    override val algorithmNotes = "Each cell has a state from 0 to N-1. A cell advances to the next state (modulo N) if at least 'threshold' of its Moore neighbors are in that next state. This creates spiraling waves as states cascade through the grid cyclically."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema = listOf(
        Parameter.NumberParam("Grid Size", "gridSize", ParamGroup.COMPOSITION, null, 32f, 256f, 16f, 128f),
        Parameter.NumberParam("States (K)", "states", ParamGroup.COMPOSITION, "Number of distinct cell states — higher K makes larger, slower spirals", 3f, 16f, 1f, 8f),
        Parameter.NumberParam("Threshold", "threshold", ParamGroup.TEXTURE, "Minimum neighbours in the successor state required for a cell to advance — threshold 1 → waves, 2 → spirals", 1f, 4f, 1f, 2f),
        Parameter.SelectParam("Neighbourhood", "neighborhood", ParamGroup.GEOMETRY, "moore: 8 neighbours | vonneumann: 4 neighbours (up/down/left/right)", listOf("moore", "vonneumann"), "moore"),
        Parameter.NumberParam("Warmup Steps", "warmupSteps", ParamGroup.COMPOSITION, "Steps computed before the static render is shown", 0f, 500f, 10f, 100f),
        Parameter.NumberParam("Steps / Frame", "stepsPerFrame", ParamGroup.FLOW_MOTION, null, 1f, 10f, 1f, 2f)
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "gridSize" to 128f,
        "states" to 8f,
        "threshold" to 2f,
        "neighborhood" to "moore",
        "warmupSteps" to 100f,
        "stepsPerFrame" to 2f
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
        val numStates = (params["states"] as? Number)?.toInt() ?: 8
        val gridSize = (params["gridSize"] as? Number)?.toInt() ?: 150
        val threshold = (params["threshold"] as? Number)?.toInt() ?: 1
        val neighborhood = (params["neighborhood"] as? String) ?: "moore"
        val warmupSteps = (params["warmupSteps"] as? Number)?.toInt() ?: 100
        val stepsPerSecond = (params["stepsPerFrame"] as? Number)?.toFloat() ?: 2f

        val w = bitmap.width
        val h = bitmap.height
        val steps = warmupSteps + (time * stepsPerSecond).toInt()
        val totalCells = gridSize * gridSize

        // Initialize from seed
        val rng = SeededRNG(seed)
        var grid = IntArray(totalCells) { rng.integer(0, numStates - 1) }

        // Evolve
        for (s in 0 until steps) {
            val next = IntArray(totalCells)
            for (y in 0 until gridSize) {
                for (x in 0 until gridSize) {
                    val idx = y * gridSize + x
                    val currentState = grid[idx]
                    val nextState = (currentState + 1) % numStates

                    var count = 0
                    if (neighborhood == "vonneumann") {
                        // Von Neumann: 4 orthogonal neighbors only
                        val dirs = arrayOf(intArrayOf(0, -1), intArrayOf(0, 1), intArrayOf(-1, 0), intArrayOf(1, 0))
                        for (d in dirs) {
                            val nx = (x + d[0] + gridSize) % gridSize
                            val ny = (y + d[1] + gridSize) % gridSize
                            if (grid[ny * gridSize + nx] == nextState) count++
                        }
                    } else {
                        // Moore: 8 neighbors
                        for (dy in -1..1) {
                            for (dx in -1..1) {
                                if (dx == 0 && dy == 0) continue
                                val nx = (x + dx + gridSize) % gridSize
                                val ny = (y + dy + gridSize) % gridSize
                                if (grid[ny * gridSize + nx] == nextState) count++
                            }
                        }
                    }

                    next[idx] = if (count >= threshold) nextState else currentState
                }
            }
            grid = next
        }

        // Render
        val cellW = w.toFloat() / gridSize
        val cellH = h.toFloat() / gridSize
        val pixels = IntArray(w * h)

        for (py in 0 until h) {
            val gy = (py / cellH).toInt().coerceAtMost(gridSize - 1)
            for (px in 0 until w) {
                val gx = (px / cellW).toInt().coerceAtMost(gridSize - 1)
                val state = grid[gy * gridSize + gx]
                val t = state.toFloat() / (numStates - 1).coerceAtLeast(1)
                pixels[py * w + px] = palette.lerpColor(t)
            }
        }

        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
        canvas.drawBitmap(bitmap, 0f, 0f, null)
    }
}
