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
        val steps = ((totalGrains.toFloat() / dropRate.coerceAtLeast(1)) * (time * 0.1f + 1f)).toInt()
        val totalCells = gridSize * gridSize
        val cx = gridSize / 2
        val cy = gridSize / 2

        val grid = IntArray(totalCells)
        val needToppleHistory = colorMode == "topple-count"
        val needAvalanche = colorMode == "avalanche"
        val toppleCount = if (needToppleHistory) IntArray(totalCells) else null
        val recentlyToppled = if (needAvalanche) BooleanArray(totalCells) else null

        // Pre-compute flat-index drop positions for non-drift modes
        val dropIndices: IntArray = when (dropSite) {
            "multi" -> {
                val q = gridSize / 4
                intArrayOf(
                    q * gridSize + q,
                    q * gridSize + 3 * q,
                    3 * q * gridSize + q,
                    3 * q * gridSize + 3 * q
                )
            }
            "drift" -> intArrayOf() // computed per-step
            else -> {
                val dx = (cx + (seed % 3) - 1).coerceIn(0, gridSize - 1)
                val dy = (cy + ((seed / 3) % 3) - 1).coerceIn(0, gridSize - 1)
                intArrayOf(dy * gridSize + dx)
            }
        }

        // Stack-based toppling: only process cells that actually need it
        // Max stack growth: dropRate + 3 * maxTopples (each topple pushes <=4, pops 1)
        val stack = IntArray(3 * maxTopples + dropRate + 16)
        var stackTop: Int
        val lastIdx = gridSize - 1

        for (step in 0 until steps) {
            stackTop = 0
            val isLastStep = step == steps - 1

            // Compute drop index for drift mode (once per step, not per grain)
            val driftIdx = if (dropSite == "drift") {
                val angle = step * 0.05f
                val r = gridSize / 6f
                val dx = (cx + r * kotlin.math.cos(angle.toDouble())).toInt().coerceIn(0, lastIdx)
                val dy = (cy + r * kotlin.math.sin(angle.toDouble())).toInt().coerceIn(0, lastIdx)
                dy * gridSize + dx
            } else -1

            // Drop grains and seed the stack with any cells that reach threshold
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

        // Render: block-fill pixels per grid cell with pre-computed color LUTs
        val pixels = IntArray(w * h)

        when (colorMode) {
            "fractal" -> {
                val lut = IntArray(threshold) { i ->
                    palette.lerpColor(i.toFloat() / (threshold - 1).coerceAtLeast(1))
                }
                blockFill(pixels, grid, gridSize, w, h, threshold) { lut[it] }
            }
            "topple-count" -> {
                val tc = toppleCount!!
                val maxTc = tc.max().coerceAtLeast(1)
                val logMax = ln(maxTc.toFloat() + 1f)
                // 256-entry palette LUT to avoid per-cell lerpColor
                val palLut = IntArray(256) { i -> palette.lerpColor(i / 255f) }
                blockFillIndexed(pixels, gridSize, w, h) { idx ->
                    val v = tc[idx]
                    if (v == 0) Color.BLACK
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
                blockFillIndexed(pixels, gridSize, w, h) { idx ->
                    if (rt[idx]) brightColor
                    else grainLut[grid[idx].coerceIn(0, threshold - 1)]
                }
            }
            else /* grain-count */ -> {
                val lut = IntArray(threshold) { i ->
                    if (i == 0) Color.BLACK else palette.lerpColor(i.toFloat() / (threshold - 1))
                }
                blockFill(pixels, grid, gridSize, w, h, threshold) { lut[it] }
            }
        }

        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
        canvas.drawBitmap(bitmap, 0f, 0f, null)
    }

    /** Fill pixel blocks using only the grain value (0..threshold-1). */
    private inline fun blockFill(
        pixels: IntArray, grid: IntArray, gridSize: Int,
        w: Int, h: Int, threshold: Int,
        colorOf: (Int) -> Int
    ) {
        for (gy in 0 until gridSize) {
            val pyStart = gy * h / gridSize
            val pyEnd = (gy + 1) * h / gridSize
            val rowBase = gy * gridSize
            for (gx in 0 until gridSize) {
                val color = colorOf(grid[rowBase + gx].coerceIn(0, threshold - 1))
                val pxStart = gx * w / gridSize
                val pxEnd = (gx + 1) * w / gridSize
                for (py in pyStart until pyEnd) {
                    java.util.Arrays.fill(pixels, py * w + pxStart, py * w + pxEnd, color)
                }
            }
        }
    }

    /** Fill pixel blocks using the flat grid index for full flexibility. */
    private inline fun blockFillIndexed(
        pixels: IntArray, gridSize: Int,
        w: Int, h: Int,
        colorOf: (Int) -> Int
    ) {
        for (gy in 0 until gridSize) {
            val pyStart = gy * h / gridSize
            val pyEnd = (gy + 1) * h / gridSize
            val rowBase = gy * gridSize
            for (gx in 0 until gridSize) {
                val color = colorOf(rowBase + gx)
                val pxStart = gx * w / gridSize
                val pxEnd = (gx + 1) * w / gridSize
                for (py in pyStart until pyEnd) {
                    java.util.Arrays.fill(pixels, py * w + pxStart, py * w + pxEnd, color)
                }
            }
        }
    }
}
