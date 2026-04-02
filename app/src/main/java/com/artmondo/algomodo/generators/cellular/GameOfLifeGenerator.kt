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

class GameOfLifeGenerator : Generator {

    override val id = "game-of-life"
    override val family = "cellular"
    override val styleName = "Game of Life"
    override val definition = "Conway's Game of Life: a zero-player cellular automaton where cells live or die based on neighbor count."
    override val algorithmNotes = "Standard B3/S23 rule set on a 2D grid. Cells with 3 neighbors are born; cells with 2 or 3 neighbors survive. Optional edge wrapping via toroidal topology."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema = listOf(
        Parameter.NumberParam("Grid Size", "gridSize", ParamGroup.COMPOSITION, "Width/height of cell grid", 16f, 512f, 16f, 128f),
        Parameter.NumberParam("Initial Density", "density", ParamGroup.COMPOSITION, "Proportion of cells alive at start", 0.05f, 1f, 0.05f, 0.3f),
        Parameter.NumberParam("Iterations (static)", "iterations", ParamGroup.COMPOSITION, "Simulation steps for the static (non-animated) render", 1f, 500f, 1f, 100f),
        Parameter.SelectParam("Rule Set", "ruleSet", ParamGroup.COMPOSITION, "conway: B3/S23 (classic) | highlife: B36/S23 (replicators) | day-night: B3678/S34678 (day/night symmetry) | seeds: B2/S (explosive) | maze: B3/S12345 (grows mazes) | morley: B368/S245 (complex gliders)", listOf("conway", "highlife", "day-night", "seeds", "maze", "morley"), "conway"),
        Parameter.BooleanParam("Wrap Edges", "wrapEdges", ParamGroup.GEOMETRY, "Torus topology — edges wrap around", true),
        Parameter.NumberParam("Perturb Rate", "perturbRate", ParamGroup.FLOW_MOTION, "Fraction of cells randomly flipped each frame — prevents stagnation and continuously seeds new activity into stable regions", 0f, 0.02f, 0.001f, 0.0f),
        Parameter.NumberParam("Steps / Frame", "stepsPerFrame", ParamGroup.FLOW_MOTION, "GoL steps per animation frame — higher = faster simulation", 1f, 10f, 1f, 1f),
        Parameter.SelectParam("Color Mode", "colorMode", ParamGroup.COLOR, "binary: two-colour | age: alive cells coloured by longevity | trails: dying cells leave a fading afterimage | entropy: cells coloured by local neighbourhood density — reveals activity gradients", listOf("binary", "age", "trails", "entropy"), "binary")
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "gridSize" to 128f,
        "density" to 0.3f,
        "iterations" to 100f,
        "ruleSet" to "conway",
        "wrapEdges" to true,
        "perturbRate" to 0.0f,
        "stepsPerFrame" to 1f,
        "colorMode" to "binary"
    )

    // Birth/Survival rules for each rule set
    private fun getRules(ruleSet: String): Pair<BooleanArray, BooleanArray> {
        val birth = BooleanArray(9)
        val survive = BooleanArray(9)
        when (ruleSet) {
            "conway" -> { // B3/S23
                birth[3] = true
                survive[2] = true; survive[3] = true
            }
            "highlife" -> { // B36/S23
                birth[3] = true; birth[6] = true
                survive[2] = true; survive[3] = true
            }
            "day-night" -> { // B3678/S34678
                birth[3] = true; birth[6] = true; birth[7] = true; birth[8] = true
                survive[3] = true; survive[4] = true; survive[6] = true; survive[7] = true; survive[8] = true
            }
            "seeds" -> { // B2/S
                birth[2] = true
            }
            "maze" -> { // B3/S12345
                birth[3] = true
                survive[1] = true; survive[2] = true; survive[3] = true; survive[4] = true; survive[5] = true
            }
            "morley" -> { // B368/S245
                birth[3] = true; birth[6] = true; birth[8] = true
                survive[2] = true; survive[4] = true; survive[5] = true
            }
            else -> {
                birth[3] = true
                survive[2] = true; survive[3] = true
            }
        }
        return Pair(birth, survive)
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
        val density = (params["density"] as? Number)?.toFloat() ?: 0.3f
        val iterations = (params["iterations"] as? Number)?.toInt() ?: 100
        val ruleSet = (params["ruleSet"] as? String) ?: "conway"
        val stepsPerFrame = (params["stepsPerFrame"] as? Number)?.toFloat() ?: 1f
        val wrapEdges = params["wrapEdges"] as? Boolean ?: true
        val perturbRate = (params["perturbRate"] as? Number)?.toFloat() ?: 0f
        val colorMode = (params["colorMode"] as? String) ?: "binary"

        val w = bitmap.width
        val h = bitmap.height
        val totalCells = gridSize * gridSize

        // Step count: animation uses time * stepsPerFrame * 30fps, static uses iterations
        val steps = if (time > 0.01f) (time * stepsPerFrame * 30f).toInt() else iterations

        val (birthRule, surviveRule) = getRules(ruleSet)

        // Initialize grid from seed
        val rng = SeededRNG(seed)
        var grid = BooleanArray(totalCells)
        for (i in grid.indices) {
            grid[i] = rng.random() < density
        }

        // Age: consecutive steps alive. Trail: countdown after death. Changes: total flips.
        val age = IntArray(totalCells)
        val trail = IntArray(totalCells)
        val maxTrail = 30
        val changeCount = IntArray(totalCells)

        for (i in grid.indices) {
            if (grid[i]) age[i] = 1
        }

        // Separate RNG for perturbation so initial state is deterministic
        val perturbRng = SeededRNG(seed xor 0x5A5A5A5A.toInt())

        // Evolve
        for (s in 0 until steps) {
            val next = BooleanArray(totalCells)

            for (y in 0 until gridSize) {
                for (x in 0 until gridSize) {
                    var neighbors = 0
                    for (dy in -1..1) {
                        for (dx in -1..1) {
                            if (dx == 0 && dy == 0) continue
                            val nx: Int
                            val ny: Int
                            if (wrapEdges) {
                                nx = (x + dx + gridSize) % gridSize
                                ny = (y + dy + gridSize) % gridSize
                            } else {
                                nx = x + dx
                                ny = y + dy
                                if (nx < 0 || nx >= gridSize || ny < 0 || ny >= gridSize) continue
                            }
                            if (grid[ny * gridSize + nx]) neighbors++
                        }
                    }
                    val idx = y * gridSize + x
                    val alive = grid[idx]
                    next[idx] = if (alive) surviveRule[neighbors] else birthRule[neighbors]
                }
            }

            // Perturbation: flip random cells to prevent stagnation
            if (perturbRate > 0f) {
                for (i in next.indices) {
                    if (perturbRng.random() < perturbRate) {
                        next[i] = !next[i]
                    }
                }
            }

            // Update tracking arrays
            for (i in grid.indices) {
                val wasAlive = grid[i]
                val isAlive = next[i]
                if (isAlive) {
                    age[i]++
                    trail[i] = maxTrail
                } else {
                    if (wasAlive) trail[i] = maxTrail  // just died
                    age[i] = 0
                    if (trail[i] > 0) trail[i]--
                }
                if (wasAlive != isAlive) changeCount[i]++
            }

            grid = next
        }

        // Render
        val cellW = w.toFloat() / gridSize
        val cellH = h.toFloat() / gridSize
        val pixels = IntArray(w * h)
        val paletteColors = palette.colorInts()
        val maxAge = age.max().coerceAtLeast(1)
        val maxChanges = changeCount.max().coerceAtLeast(1)

        for (py in 0 until h) {
            val gy = (py / cellH).toInt().coerceAtMost(gridSize - 1)
            for (px in 0 until w) {
                val gx = (px / cellW).toInt().coerceAtMost(gridSize - 1)
                val idx = gy * gridSize + gx
                val alive = grid[idx]

                pixels[py * w + px] = when (colorMode) {
                    "age" -> {
                        if (alive) {
                            val ageFrac = (age[idx].toFloat() / maxAge).coerceIn(0f, 1f)
                            palette.lerpColor(ageFrac)
                        } else Color.BLACK
                    }
                    "trails" -> {
                        if (alive) {
                            val ageFrac = (age[idx].toFloat() / maxAge).coerceIn(0f, 1f)
                            palette.lerpColor(ageFrac)
                        } else if (trail[idx] > 0) {
                            val fade = trail[idx].toFloat() / maxTrail
                            val base = palette.lerpColor(0.5f)
                            Color.rgb(
                                (Color.red(base) * fade * 0.5f).toInt(),
                                (Color.green(base) * fade * 0.5f).toInt(),
                                (Color.blue(base) * fade * 0.5f).toInt()
                            )
                        } else Color.BLACK
                    }
                    "entropy" -> {
                        val frac = (changeCount[idx].toFloat() / maxChanges).coerceIn(0f, 1f)
                        if (alive) {
                            palette.lerpColor(frac)
                        } else if (changeCount[idx] > 0) {
                            val base = palette.lerpColor(frac)
                            Color.rgb(
                                (Color.red(base) * 0.2f).toInt(),
                                (Color.green(base) * 0.2f).toInt(),
                                (Color.blue(base) * 0.2f).toInt()
                            )
                        } else Color.BLACK
                    }
                    else -> { // binary
                        if (alive) paletteColors[(gx + gy) % paletteColors.size]
                        else Color.BLACK
                    }
                }
            }
        }

        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
        canvas.drawBitmap(bitmap, 0f, 0f, null)
    }
}
