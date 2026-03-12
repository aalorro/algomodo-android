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

class ForestFireGenerator : Generator {

    override val id = "cellular-forest-fire"
    override val family = "cellular"
    override val styleName = "Forest Fire"
    override val definition = "Forest fire model: a stochastic cellular automaton simulating tree growth, lightning strikes, and fire spread."
    override val algorithmNotes = "Three states: Empty, Tree, Burning. Empty cells grow trees with probability pGrow. Trees catch fire if a neighbor is burning. Trees spontaneously ignite with probability pBurn (lightning). Burning cells become empty next step. Produces self-organized critical behavior."
    override val supportsVector = false
    override val supportsAnimation = true

    companion object {
        private const val EMPTY = 0
        private const val TREE = 1
        private const val BURNING = 2
    }

    override val parameterSchema = listOf(
        Parameter.NumberParam("Grid Size", "gridSize", ParamGroup.COMPOSITION, null, 32f, 256f, 16f, 128f),
        Parameter.NumberParam("Initial Tree Density", "initialDensity", ParamGroup.COMPOSITION, null, 0.1f, 1f, 0.05f, 0.7f),
        Parameter.NumberParam("Growth Rate (p)", "growthProb", ParamGroup.TEXTURE, "Probability an empty cell grows a tree each step", 0.001f, 0.05f, 0.001f, 0.01f),
        Parameter.NumberParam("Lightning Rate (f)", "lightningProb", ParamGroup.TEXTURE, "Probability a tree spontaneously ignites each step", 0.0001f, 0.003f, 0.0001f, 0.0005f),
        Parameter.NumberParam("Steps / Frame", "stepsPerFrame", ParamGroup.FLOW_MOTION, null, 1f, 10f, 1f, 3f),
        Parameter.SelectParam("Color Mode", "colorMode", ParamGroup.COLOR, "classic: dark / forest-green / orange-red | palette: first / mid / last palette colours", listOf("classic", "palette"), "classic")
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "gridSize" to 128f,
        "initialDensity" to 0.7f,
        "growthProb" to 0.01f,
        "lightningProb" to 0.0005f,
        "stepsPerFrame" to 3f,
        "colorMode" to "classic"
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
        val initialDensity = (params["initialDensity"] as? Number)?.toFloat() ?: 0.7f
        val pGrow = (params["growthProb"] as? Number)?.toFloat() ?: 0.01f
        val pBurn = (params["lightningProb"] as? Number)?.toFloat() ?: 0.0005f
        val stepsPerFrame = (params["stepsPerFrame"] as? Number)?.toFloat() ?: 3f
        val colorMode = (params["colorMode"] as? String) ?: "classic"

        val w = bitmap.width
        val h = bitmap.height
        val steps = (time * stepsPerFrame).toInt()
        val totalCells = gridSize * gridSize

        // Initialize from seed: start with a partially forested grid
        val rng = SeededRNG(seed)
        var grid = IntArray(totalCells) {
            if (rng.random() < initialDensity) TREE else EMPTY
        }

        // Evolve
        for (s in 0 until steps) {
            val next = IntArray(totalCells)
            for (y in 0 until gridSize) {
                for (x in 0 until gridSize) {
                    val idx = y * gridSize + x
                    when (grid[idx]) {
                        BURNING -> next[idx] = EMPTY

                        TREE -> {
                            // Check if any neighbor is burning
                            var neighborBurning = false
                            for (dy in -1..1) {
                                for (dx in -1..1) {
                                    if (dx == 0 && dy == 0) continue
                                    val nx = (x + dx + gridSize) % gridSize
                                    val ny = (y + dy + gridSize) % gridSize
                                    if (grid[ny * gridSize + nx] == BURNING) {
                                        neighborBurning = true
                                    }
                                }
                            }
                            next[idx] = if (neighborBurning || rng.random() < pBurn) BURNING else TREE
                        }

                        EMPTY -> {
                            next[idx] = if (rng.random() < pGrow) TREE else EMPTY
                        }
                    }
                }
            }
            grid = next
        }

        // Render
        val cellW = w.toFloat() / gridSize
        val cellH = h.toFloat() / gridSize
        val pixels = IntArray(w * h)
        val paletteColors = palette.colorInts()

        // Determine colors based on colorMode
        val treeColor: Int
        val fireColor: Int
        val emptyColor: Int
        when (colorMode) {
            "palette" -> {
                // first / mid / last palette colours for EMPTY / BURNING / TREE
                treeColor = paletteColors[paletteColors.size - 1]
                fireColor = paletteColors[paletteColors.size / 2]
                emptyColor = paletteColors[0]
            }
            else /* classic */ -> {
                treeColor = Color.rgb(34, 139, 34)   // forest green
                fireColor = Color.rgb(255, 69, 0)     // orange-red
                emptyColor = Color.rgb(30, 20, 10)    // dark brown
            }
        }

        for (py in 0 until h) {
            val gy = (py / cellH).toInt().coerceAtMost(gridSize - 1)
            for (px in 0 until w) {
                val gx = (px / cellW).toInt().coerceAtMost(gridSize - 1)
                val idx = gy * gridSize + gx
                pixels[py * w + px] = when (grid[idx]) {
                    TREE -> treeColor
                    BURNING -> fireColor
                    else -> emptyColor
                }
            }
        }

        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
        canvas.drawBitmap(bitmap, 0f, 0f, null)
    }
}
