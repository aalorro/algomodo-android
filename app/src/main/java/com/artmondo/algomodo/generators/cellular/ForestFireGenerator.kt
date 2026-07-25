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
        private const val BURN1 = 2 // burning phase start; values >= BURN1 are all burning
    }

    override val parameterSchema = listOf(
        Parameter.NumberParam("Grid Size", "gridSize", ParamGroup.COMPOSITION, null, 32f, 256f, 16f, 128f),
        Parameter.NumberParam("Initial Tree Density", "initialDensity", ParamGroup.COMPOSITION, null, 0.1f, 1f, 0.05f, 0.7f),
        Parameter.NumberParam("Growth Rate (p)", "growthProb", ParamGroup.TEXTURE, "Probability an empty cell grows a tree each step", 0.001f, 0.05f, 0.001f, 0.01f),
        Parameter.NumberParam("Lightning Rate (f)", "lightningProb", ParamGroup.TEXTURE, "Probability a tree spontaneously ignites each step", 0.0001f, 0.003f, 0.0001f, 0.0005f),
        Parameter.NumberParam("Steps / Frame", "stepsPerFrame", ParamGroup.FLOW_MOTION, null, 1f, 10f, 1f, 3f),
        Parameter.SelectParam("Pattern", "pattern", ParamGroup.COMPOSITION, "classic: 4-neighbor 1-step | inferno: 8-neighbor + ember jump | wind: directional sweep | smolder: slow spread, long burn", listOf("classic", "inferno", "wind", "smolder"), "classic"),
        Parameter.SelectParam("Color Mode", "colorMode", ParamGroup.COLOR, "classic: dark/green/fire | palette: from palette | heatmap/infrared/neon/ember: dynamic with burn trails", listOf("classic", "palette", "heatmap", "infrared", "neon", "ember"), "classic")
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "gridSize" to 128f,
        "initialDensity" to 0.7f,
        "growthProb" to 0.01f,
        "lightningProb" to 0.0005f,
        "stepsPerFrame" to 3f,
        "pattern" to "classic",
        "colorMode" to "classic"
    )

    // --- Burn duration per pattern ---

    private fun burnDuration(pattern: String): Int = when (pattern) {
        "inferno" -> 2
        "smolder" -> 5
        "wind" -> 2
        else -> 1 // classic
    }

    // --- Cell query helpers ---

    private fun isBurning(grid: IntArray, cx: Int, cy: Int, size: Int): Boolean {
        if (cx < 0 || cx >= size || cy < 0 || cy >= size) return false
        return grid[cy * size + cx] >= BURN1
    }

    // --- Pattern step functions ---

    /** Classic: 4-neighbor von Neumann, 1-step burn (original Drossel-Schwabl). */
    private fun stepClassic(
        grid: IntArray, next: IntArray, burnAge: IntArray, size: Int,
        p: Float, f: Float, rng: SeededRNG
    ) {
        for (y in 0 until size) {
            for (x in 0 until size) {
                val i = y * size + x
                val s = grid[i]
                if (s >= BURN1) {
                    next[i] = EMPTY
                    burnAge[i] = (burnAge[i] + 1).coerceAtMost(255)
                } else if (s == TREE) {
                    val fire = isBurning(grid, x, y - 1, size) ||
                            isBurning(grid, x, y + 1, size) ||
                            isBurning(grid, x - 1, y, size) ||
                            isBurning(grid, x + 1, y, size)
                    if (fire || rng.random() < f) {
                        next[i] = BURN1; burnAge[i] = 1
                    } else {
                        next[i] = TREE
                    }
                } else { // EMPTY
                    if (rng.random() < p) {
                        next[i] = TREE; burnAge[i] = 0
                    } else {
                        next[i] = EMPTY
                        if (burnAge[i] in 1..254) burnAge[i]++
                    }
                }
            }
        }
    }

    /** Inferno: 8-neighbor Moore + range-2 ember jump (40%), 2-step burn. Explosive clusters. */
    private fun stepInferno(
        grid: IntArray, next: IntArray, burnAge: IntArray, size: Int,
        p: Float, f: Float, rng: SeededRNG
    ) {
        val maxBurn = BURN1 + burnDuration("inferno") - 1
        for (y in 0 until size) {
            for (x in 0 until size) {
                val i = y * size + x
                val s = grid[i]
                if (s >= BURN1) {
                    next[i] = if (s < maxBurn) s + 1 else EMPTY
                    burnAge[i] = (burnAge[i] + 1).coerceAtMost(255)
                } else if (s == TREE) {
                    var fire = false
                    // 8 Moore neighbors
                    outer@ for (dy in -1..1) {
                        for (dx in -1..1) {
                            if (dx == 0 && dy == 0) continue
                            if (isBurning(grid, x + dx, y + dy, size)) { fire = true; break@outer }
                        }
                    }
                    // Range-2 ember jump
                    if (!fire) {
                        val jumpDx = intArrayOf(0, 0, -2, 2)
                        val jumpDy = intArrayOf(-2, 2, 0, 0)
                        for (d in 0..3) {
                            if (isBurning(grid, x + jumpDx[d], y + jumpDy[d], size) && rng.random() < 0.4f) {
                                fire = true; break
                            }
                        }
                    }
                    if (fire || rng.random() < f) {
                        next[i] = BURN1; burnAge[i] = 1
                    } else {
                        next[i] = TREE
                    }
                } else {
                    if (rng.random() < p) {
                        next[i] = TREE; burnAge[i] = 0
                    } else {
                        next[i] = EMPTY
                        if (burnAge[i] in 1..254) burnAge[i]++
                    }
                }
            }
        }
    }

    /** Wind: directional bias (right & down). Fire guaranteed downwind, nearly blocked upwind. */
    private fun stepWind(
        grid: IntArray, next: IntArray, burnAge: IntArray, size: Int,
        p: Float, f: Float, rng: SeededRNG
    ) {
        val maxBurn = BURN1 + burnDuration("wind") - 1
        for (y in 0 until size) {
            for (x in 0 until size) {
                val i = y * size + x
                val s = grid[i]
                if (s >= BURN1) {
                    next[i] = if (s < maxBurn) s + 1 else EMPTY
                    burnAge[i] = (burnAge[i] + 1).coerceAtMost(255)
                } else if (s == TREE) {
                    var fire = false
                    // Downwind: fire to the left or above spreads easily
                    if (isBurning(grid, x - 1, y, size)) fire = true
                    if (!fire && isBurning(grid, x, y - 1, size)) fire = true
                    if (!fire && isBurning(grid, x - 1, y - 1, size)) fire = true
                    // Downwind long-range jump (2 cells)
                    if (!fire && isBurning(grid, x - 2, y, size) && rng.random() < 0.5f) fire = true
                    if (!fire && isBurning(grid, x, y - 2, size) && rng.random() < 0.5f) fire = true
                    // Upwind: very hard to spread back against wind
                    if (!fire && isBurning(grid, x + 1, y, size) && rng.random() < 0.08f) fire = true
                    if (!fire && isBurning(grid, x, y + 1, size) && rng.random() < 0.08f) fire = true

                    if (fire || rng.random() < f) {
                        next[i] = BURN1; burnAge[i] = 1
                    } else {
                        next[i] = TREE
                    }
                } else {
                    if (rng.random() < p) {
                        next[i] = TREE; burnAge[i] = 0
                    } else {
                        next[i] = EMPTY
                        if (burnAge[i] in 1..254) burnAge[i]++
                    }
                }
            }
        }
    }

    /** Smolder: 4-neighbor, 60% spread probability per neighbor, 5-step burn. Slow persistent patches. */
    private fun stepSmolder(
        grid: IntArray, next: IntArray, burnAge: IntArray, size: Int,
        p: Float, f: Float, rng: SeededRNG
    ) {
        val maxBurn = BURN1 + burnDuration("smolder") - 1
        for (y in 0 until size) {
            for (x in 0 until size) {
                val i = y * size + x
                val s = grid[i]
                if (s >= BURN1) {
                    next[i] = if (s < maxBurn) s + 1 else EMPTY
                    burnAge[i] = (burnAge[i] + 1).coerceAtMost(255)
                } else if (s == TREE) {
                    var fire = false
                    // Cardinal neighbors only, each with 60% chance
                    val nx = intArrayOf(x, x, x - 1, x + 1)
                    val ny = intArrayOf(y - 1, y + 1, y, y)
                    for (d in 0..3) {
                        if (isBurning(grid, nx[d], ny[d], size) && rng.random() < 0.6f) {
                            fire = true; break
                        }
                    }
                    if (fire || rng.random() < f) {
                        next[i] = BURN1; burnAge[i] = 1
                    } else {
                        next[i] = TREE
                    }
                } else {
                    if (rng.random() < p) {
                        next[i] = TREE; burnAge[i] = 0
                    } else {
                        next[i] = EMPTY
                        if (burnAge[i] in 1..254) burnAge[i]++
                    }
                }
            }
        }
    }

    /** Dispatch one simulation step to the appropriate pattern function. */
    private fun stepFF(
        grid: IntArray, next: IntArray, burnAge: IntArray, size: Int,
        p: Float, f: Float, rng: SeededRNG, pattern: String
    ) {
        when (pattern) {
            "inferno" -> stepInferno(grid, next, burnAge, size, p, f, rng)
            "wind" -> stepWind(grid, next, burnAge, size, p, f, rng)
            "smolder" -> stepSmolder(grid, next, burnAge, size, p, f, rng)
            else -> stepClassic(grid, next, burnAge, size, p, f, rng)
        }
        // Copy next → grid
        System.arraycopy(next, 0, grid, 0, grid.size)
    }

    // --- Color helpers ---

    private fun lerpColor(ar: Int, ag: Int, ab: Int, br: Int, bg: Int, bb: Int, t: Float): Int {
        val r = (ar + (br - ar) * t).toInt()
        val g = (ag + (bg - ag) * t).toInt()
        val b = (ab + (bb - ab) * t).toInt()
        return Color.rgb(r, g, b)
    }

    private fun burnPhase(state: Int, pattern: String): Float {
        if (state < BURN1) return 0f
        val dur = burnDuration(pattern)
        return if (dur <= 1) 0f else (state - BURN1).toFloat() / (dur - 1)
    }

    private fun colorClassic(state: Int): Int = when {
        state >= BURN1 -> Color.rgb(220, 72, 12)
        state == TREE -> Color.rgb(38, 150, 38)
        else -> Color.rgb(12, 14, 12)
    }

    private fun colorPalette(state: Int, paletteColors: List<Int>): Int = when {
        state >= BURN1 -> paletteColors[paletteColors.size - 1]
        state == TREE -> paletteColors[paletteColors.size / 2]
        else -> paletteColors[0]
    }

    private fun colorHeatmap(state: Int, bp: Float, age: Int): Int = when {
        state == TREE -> Color.rgb(18, 60, 18)
        state >= BURN1 -> lerpColor(255, 255, 80, 180, 30, 0, bp)
        age > 0 -> {
            val t = (age / 30f).coerceAtMost(1f)
            lerpColor(80, 20, 5, 8, 8, 8, t)
        }
        else -> Color.rgb(8, 8, 8)
    }

    private fun colorInfrared(state: Int, bp: Float, age: Int): Int = when {
        state == TREE -> Color.rgb(20, 10, 60)
        state >= BURN1 -> lerpColor(255, 240, 120, 200, 20, 60, bp)
        age > 0 -> {
            val t = (age / 25f).coerceAtMost(1f)
            lerpColor(100, 10, 40, 10, 5, 30, t)
        }
        else -> Color.rgb(10, 5, 30)
    }

    private fun colorNeon(state: Int, bp: Float, age: Int): Int = when {
        state == TREE -> Color.rgb(0, 255, 80)
        state >= BURN1 -> lerpColor(255, 255, 0, 255, 0, 80, bp)
        age > 0 -> {
            val t = (age / 15f).coerceAtMost(1f)
            lerpColor(60, 0, 30, 5, 5, 10, t)
        }
        else -> Color.rgb(5, 5, 10)
    }

    private fun colorEmber(state: Int, bp: Float, age: Int): Int = when {
        state == TREE -> Color.rgb(30, 50, 20)
        state >= BURN1 -> lerpColor(255, 180, 40, 140, 40, 10, bp)
        age > 0 -> {
            val t = (age / 40f).coerceAtMost(1f)
            lerpColor(60, 15, 5, 10, 8, 6, t)
        }
        else -> Color.rgb(10, 8, 6)
    }

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
        val pattern = (params["pattern"] as? String) ?: "classic"
        val colorMode = (params["colorMode"] as? String) ?: "classic"

        val w = bitmap.width
        val h = bitmap.height
        val totalCells = gridSize * gridSize

        // Warmup: ensure visible dynamics even in static mode (time≈0)
        val warmup = (200f / stepsPerFrame).toInt().coerceAtLeast(80)
        val steps = warmup + (time * stepsPerFrame).toInt()

        // Initialize from seed
        val rng = SeededRNG(seed)
        val grid = IntArray(totalCells) {
            if (rng.random() < initialDensity) TREE else EMPTY
        }
        val next = IntArray(totalCells)
        val burnAge = IntArray(totalCells)

        // Evolve
        for (s in 0 until steps) {
            stepFF(grid, next, burnAge, gridSize, pGrow, pBurn, rng, pattern)
        }

        // Render
        val cellW = w.toFloat() / gridSize
        val cellH = h.toFloat() / gridSize
        val pixels = IntArray(w * h)
        val paletteColors = palette.colorInts()

        // Determine colors for simple modes up front to avoid per-pixel dispatch overhead
        for (py in 0 until h) {
            val gy = (py / cellH).toInt().coerceAtMost(gridSize - 1)
            for (px in 0 until w) {
                val gx = (px / cellW).toInt().coerceAtMost(gridSize - 1)
                val idx = gy * gridSize + gx
                val state = grid[idx]
                val age = burnAge[idx]
                val bp = burnPhase(state, pattern)

                pixels[py * w + px] = when (colorMode) {
                    "palette" -> colorPalette(state, paletteColors)
                    "heatmap" -> colorHeatmap(state, bp, age)
                    "infrared" -> colorInfrared(state, bp, age)
                    "neon" -> colorNeon(state, bp, age)
                    "ember" -> colorEmber(state, bp, age)
                    else -> colorClassic(state)
                }
            }
        }

        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
        canvas.drawBitmap(bitmap, 0f, 0f, null)
    }
}
