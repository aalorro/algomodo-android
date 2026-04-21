package com.artmondo.algomodo.generators.cellular

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import com.artmondo.algomodo.data.palettes.Palette
import com.artmondo.algomodo.generators.Generator
import com.artmondo.algomodo.generators.ParamGroup
import com.artmondo.algomodo.generators.Parameter
import com.artmondo.algomodo.generators.Quality
import kotlin.math.ln

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

    // ---- Simulation cache: avoids re-running all topple cycles from scratch each frame ----
    private class SimState(
        val gridSize: Int,
        val seed: Int,
        val dropSite: String,
        val dropRate: Int,
        val hasToppleCount: Boolean,
        val grid: IntArray,
        val toppleCount: IntArray?,
        var stepsDone: Int
    )

    @Volatile private var cachedSim: SimState? = null
    @Volatile private var reusePixels: IntArray? = null
    @Volatile private var reuseGridColors: IntArray? = null
    @Volatile private var reuseStack: IntArray? = null

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
        val baseSteps = ((totalGrains.toFloat() / dropRate.coerceAtLeast(1)) * (time * 0.1f + 1f)).toInt()
        val targetSteps = when (quality) {
            Quality.DRAFT -> (baseSteps * 0.15f).toInt().coerceAtLeast(1)
            else -> baseSteps
        }
        val totalCells = gridSize * gridSize
        val cx = gridSize / 2
        val cy = gridSize / 2
        val needToppleHistory = colorMode == "topple-count"
        val needAvalanche = colorMode == "avalanche"
        val lastIdx = gridSize - 1

        // ---- Get or create simulation state (cache across frames) ----
        val sim = cachedSim?.takeIf {
            it.gridSize == gridSize && it.seed == seed &&
                it.dropSite == dropSite && it.dropRate == dropRate &&
                it.hasToppleCount == needToppleHistory &&
                it.stepsDone <= targetSteps
        } ?: SimState(
            gridSize, seed, dropSite, dropRate, needToppleHistory,
            IntArray(totalCells),
            if (needToppleHistory) IntArray(totalCells) else null,
            0
        )
        cachedSim = sim

        val grid = sim.grid
        val toppleCount = sim.toppleCount

        // Pre-compute drop positions
        val dropIndices: IntArray = when (dropSite) {
            "multi" -> {
                val q = gridSize / 4
                intArrayOf(q * gridSize + q, q * gridSize + 3 * q, 3 * q * gridSize + q, 3 * q * gridSize + 3 * q)
            }
            "drift" -> intArrayOf()
            else -> {
                val dx = (cx + (seed % 3) - 1).coerceIn(0, lastIdx)
                val dy = (cy + ((seed / 3) % 3) - 1).coerceIn(0, lastIdx)
                intArrayOf(dy * gridSize + dx)
            }
        }

        // Reusable stack for toppling
        val stackSize = 3 * maxTopples + dropRate + 16
        val rs = reuseStack
        val stack = if (rs != null && rs.size >= stackSize) rs else IntArray(stackSize).also { reuseStack = it }

        // ---- Advance simulation from stepsDone to targetSteps ----
        val recentlyToppled = if (needAvalanche) BooleanArray(totalCells) else null

        for (step in sim.stepsDone until targetSteps) {
            var stackTop = 0
            val isLastStep = step == targetSteps - 1

            val driftIdx = if (dropSite == "drift") {
                val angle = step * 0.05f
                val r = gridSize / 6f
                val dx = (cx + r * kotlin.math.cos(angle.toDouble())).toInt().coerceIn(0, lastIdx)
                val dy = (cy + r * kotlin.math.sin(angle.toDouble())).toInt().coerceIn(0, lastIdx)
                dy * gridSize + dx
            } else -1

            // Drop grains and seed the stack
            for (d in 0 until dropRate) {
                val dIdx = if (driftIdx >= 0) driftIdx else dropIndices[d % dropIndices.size]
                grid[dIdx]++
                if (grid[dIdx] >= threshold) {
                    stack[stackTop++] = dIdx
                }
            }

            // Topple using stack — O(topples) instead of O(gridSize²) per pass
            var budget = maxTopples
            while (stackTop > 0 && budget > 0) {
                val idx = stack[--stackTop]
                if (grid[idx] < threshold) continue
                grid[idx] -= threshold
                toppleCount?.let { it[idx]++ }
                if (isLastStep) recentlyToppled?.let { it[idx] = true }
                budget--

                val x = idx % gridSize
                val y = idx / gridSize
                if (x > 0) { val n = idx - 1; grid[n]++; if (grid[n] >= threshold) stack[stackTop++] = n }
                if (x < lastIdx) { val n = idx + 1; grid[n]++; if (grid[n] >= threshold) stack[stackTop++] = n }
                if (y > 0) { val n = idx - gridSize; grid[n]++; if (grid[n] >= threshold) stack[stackTop++] = n }
                if (y < lastIdx) { val n = idx + gridSize; grid[n]++; if (grid[n] >= threshold) stack[stackTop++] = n }
            }
        }
        sim.stepsDone = targetSteps

        // ---- Phase 1: Compute color per grid cell ----
        val gridColors: IntArray
        val rgc = reuseGridColors
        if (rgc != null && rgc.size >= totalCells) {
            gridColors = rgc
        } else {
            gridColors = IntArray(totalCells)
            reuseGridColors = gridColors
        }

        when (colorMode) {
            "fractal" -> {
                val lut = IntArray(threshold) { i ->
                    palette.lerpColor(i.toFloat() / (threshold - 1).coerceAtLeast(1))
                }
                for (i in 0 until totalCells) {
                    gridColors[i] = lut[grid[i].coerceIn(0, threshold - 1)]
                }
            }
            "topple-count" -> {
                val tc = toppleCount!!
                val maxTc = tc.max().coerceAtLeast(1)
                val logMax = ln(maxTc.toFloat() + 1f)
                val palLut = IntArray(256) { i -> palette.lerpColor(i / 255f) }
                for (i in 0 until totalCells) {
                    val v = tc[i]
                    gridColors[i] = if (v == 0) Color.BLACK
                    else palLut[(ln(v.toFloat() + 1f) / logMax * 255f).toInt().coerceIn(0, 255)]
                }
            }
            "avalanche" -> {
                val rt = recentlyToppled!!
                val brightColor = palette.lerpColor(1f)
                val grainLut = IntArray(threshold) { i ->
                    if (i == 0) Color.BLACK
                    else palette.lerpColor(i.toFloat() / (threshold - 1) * 0.5f)
                }
                for (i in 0 until totalCells) {
                    gridColors[i] = if (rt[i]) brightColor
                    else grainLut[grid[i].coerceIn(0, threshold - 1)]
                }
            }
            else /* grain-count */ -> {
                val lut = IntArray(threshold) { i ->
                    if (i == 0) Color.BLACK else palette.lerpColor(i.toFloat() / (threshold - 1))
                }
                for (i in 0 until totalCells) {
                    gridColors[i] = lut[grid[i].coerceIn(0, threshold - 1)]
                }
            }
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

        val xMap = IntArray(w) { (it * gridSize / w).coerceAtMost(lastIdx) }
        for (py in 0 until h) {
            val gy = (py * gridSize / h).coerceAtMost(lastIdx)
            val gridRow = gy * gridSize
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
        val totalGrains = (params["totalGrains"] as? Number)?.toInt() ?: 100000
        return (gridSize * gridSize.toLong() * totalGrains / 500_000_000f).coerceIn(0.1f, 1f)
    }
}
