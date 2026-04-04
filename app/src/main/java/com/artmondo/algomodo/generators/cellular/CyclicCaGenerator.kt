package com.artmondo.algomodo.generators.cellular

import android.graphics.Bitmap
import android.graphics.Canvas
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

    // ---- Simulation cache: avoids re-running all steps from scratch each frame ----
    private class SimState(
        val gridSize: Int,
        val seed: Int,
        val numStates: Int,
        val threshold: Int,
        val neighborhood: String,
        var grid: IntArray,
        var nextGrid: IntArray,
        var stepsDone: Int
    )

    @Volatile private var cachedSim: SimState? = null
    @Volatile private var reusePixels: IntArray? = null
    @Volatile private var reuseGridColors: IntArray? = null

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
        val gridSize = (params["gridSize"] as? Number)?.toInt() ?: 128
        val threshold = (params["threshold"] as? Number)?.toInt() ?: 2
        val neighborhood = (params["neighborhood"] as? String) ?: "moore"
        val warmupSteps = (params["warmupSteps"] as? Number)?.toInt() ?: 100
        val stepsPerSecond = (params["stepsPerFrame"] as? Number)?.toFloat() ?: 2f

        val w = bitmap.width
        val h = bitmap.height
        val targetSteps = warmupSteps + (time * stepsPerSecond).toInt()
        val totalCells = gridSize * gridSize
        val N = gridSize
        val Nm1 = N - 1

        // ---- Get or create simulation state (cache across frames) ----
        val sim = cachedSim?.takeIf {
            it.gridSize == gridSize && it.seed == seed &&
                it.numStates == numStates && it.threshold == threshold &&
                it.neighborhood == neighborhood &&
                it.stepsDone <= targetSteps
        } ?: run {
            val rng = SeededRNG(seed)
            SimState(
                gridSize, seed, numStates, threshold, neighborhood,
                IntArray(totalCells) { rng.integer(0, numStates - 1) },
                IntArray(totalCells),
                0
            )
        }
        cachedSim = sim

        // ---- Advance simulation from stepsDone to targetSteps ----
        var curGrid = sim.grid
        var nxtGrid = sim.nextGrid
        val isMoore = neighborhood == "moore"
        // Cap threshold to prevent frozen grids: expected matching neighbors = n/K.
        // Allow threshold up to (n/K + 1) which guarantees >10% cell advance rate.
        // Moore K=8 → cap 2, Moore K=3 → cap 3, VN K=8 → cap 1, VN K=3 → cap 2.
        val maxNeighbors = if (isMoore) 8 else 4
        val effectiveThreshold = threshold.coerceAtMost(
            (maxNeighbors / numStates + 1).coerceAtLeast(1)
        )

        for (s in sim.stepsDone until targetSteps) {
            for (y in 0 until N) {
                val rowOff = y * N
                val topOff = if (y > 0) (y - 1) * N else Nm1 * N
                val botOff = if (y < Nm1) (y + 1) * N else 0

                for (x in 0 until N) {
                    val idx = rowOff + x
                    val nextState = (curGrid[idx] + 1) % numStates
                    val left = if (x > 0) x - 1 else Nm1
                    val right = if (x < Nm1) x + 1 else 0

                    var count = 0
                    // 4 orthogonal neighbors (Von Neumann)
                    if (curGrid[topOff + x] == nextState) count++
                    if (curGrid[botOff + x] == nextState) count++
                    if (curGrid[rowOff + left] == nextState) count++
                    if (curGrid[rowOff + right] == nextState) count++

                    if (isMoore) {
                        // 4 diagonal neighbors
                        if (curGrid[topOff + left] == nextState) count++
                        if (curGrid[topOff + right] == nextState) count++
                        if (curGrid[botOff + left] == nextState) count++
                        if (curGrid[botOff + right] == nextState) count++
                    }

                    nxtGrid[idx] = if (count >= effectiveThreshold) nextState else curGrid[idx]
                }
            }
            // Swap buffers (no allocation, no copy)
            val tmp = curGrid; curGrid = nxtGrid; nxtGrid = tmp
        }
        sim.grid = curGrid
        sim.nextGrid = nxtGrid
        sim.stepsDone = targetSteps

        // ---- Phase 1: Compute color per grid cell using state LUT ----
        val stateLut = IntArray(numStates) { state ->
            palette.lerpColor(state.toFloat() / (numStates - 1).coerceAtLeast(1))
        }

        val gridColors: IntArray
        val rgc = reuseGridColors
        if (rgc != null && rgc.size >= totalCells) {
            gridColors = rgc
        } else {
            gridColors = IntArray(totalCells)
            reuseGridColors = gridColors
        }

        for (i in 0 until totalCells) {
            gridColors[i] = stateLut[curGrid[i]]
        }

        // ---- Phase 2: Expand grid colors to full pixel array ----
        val pixels: IntArray
        val rp = reusePixels
        val pixelCount = w * h
        if (rp != null && rp.size >= pixelCount) {
            pixels = rp
        } else {
            pixels = IntArray(pixelCount)
            reusePixels = pixels
        }

        val xMap = IntArray(w) { (it * N / w).coerceAtMost(Nm1) }
        for (py in 0 until h) {
            val gy = (py * N / h).coerceAtMost(Nm1)
            val gridRow = gy * N
            val pixRow = py * w
            for (px in 0 until w) {
                pixels[pixRow + px] = gridColors[gridRow + xMap[px]]
            }
        }

        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
        canvas.drawBitmap(bitmap, 0f, 0f, null)
    }

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float {
        val gridSize = (params["gridSize"] as? Number)?.toInt() ?: 128
        val warmupSteps = (params["warmupSteps"] as? Number)?.toInt() ?: 100
        return (gridSize * gridSize.toLong() * warmupSteps / 50_000_000f).coerceIn(0.1f, 1f)
    }
}
