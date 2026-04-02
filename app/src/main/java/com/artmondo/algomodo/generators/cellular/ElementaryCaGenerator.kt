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

class ElementaryCaGenerator : Generator {

    override val id = "cellular-elementary-ca"
    override val family = "cellular"
    override val styleName = "Elementary CA"
    override val definition = "Wolfram elementary cellular automaton: a 1D CA computed row by row using an 8-bit rule number."
    override val algorithmNotes = "Each row is computed from the previous row by looking at 3-cell neighborhoods. The 8-bit rule number encodes the output for each of the 8 possible 3-cell patterns. Row 0 is initialized as a single center cell or random from seed. The time parameter controls vertical scroll offset."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema = listOf(
        Parameter.NumberParam("Grid Width", "gridSize", ParamGroup.COMPOSITION, "Cells per row; height = same number of generations displayed", 64f, 512f, 64f, 256f),
        Parameter.NumberParam("Rule A (1-255)", "rule", ParamGroup.COMPOSITION, "Primary Wolfram rule — notable: 30 (chaos/RNG), 90 (Sierpinski), 110 (Turing-complete), 184 (traffic flow). In 5-cell mode only bits 0-5 matter (rules 1-63).", 1f, 255f, 1f, 30f),
        Parameter.NumberParam("Rule B (0-255)", "ruleB", ParamGroup.COMPOSITION, "Secondary rule for blend mode — each generation is the bitwise combination of Rule A and Rule B outputs", 0f, 255f, 1f, 90f),
        Parameter.SelectParam("Blend Mode", "blendMode", ParamGroup.COMPOSITION, "none: only Rule A | xor: XOR of Rule A and B — creates complex interference/moire patterns | or: OR | and: AND", listOf("none", "xor", "or", "and"), "none"),
        Parameter.SelectParam("Neighbourhood", "neighborWidth", ParamGroup.GEOMETRY, "3-cell: standard Wolfram (left, centre, right) → 8 patterns, 256 rules | 5-cell totalistic: sum of 5 cells, 6 counts → 64 rules; produces denser, more complex patterns", listOf("3-cell", "5-cell"), "3-cell"),
        Parameter.SelectParam("Initial Condition", "initialCondition", ParamGroup.GEOMETRY, "single-center: one ON cell | two-center: three symmetric seeds — creates bilateral interference patterns | random: seeded random row", listOf("single-center", "two-center", "random"), "single-center"),
        Parameter.NumberParam("Mutation Rate", "mutationRate", ParamGroup.FLOW_MOTION, "Probability per generation that one rule bit randomly flips during animation — causes the pattern to slowly morph between different CA behaviours over time", 0f, 0.1f, 0.005f, 0.0f),
        Parameter.NumberParam("Steps / Frame", "stepsPerFrame", ParamGroup.FLOW_MOTION, "Rows added per animation frame — the spacetime diagram scrolls at this rate", 1f, 20f, 1f, 4f),
        Parameter.SelectParam("Color Mode", "colorMode", ParamGroup.COLOR, "binary: ON/OFF → last/first palette color | age: row age mapped to palette gradient | density: neighbourhood count (0-3) mapped to palette — reveals local activity structure", listOf("binary", "age", "density"), "binary")
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "gridSize" to 256f,
        "rule" to 30f,
        "ruleB" to 90f,
        "blendMode" to "none",
        "neighborWidth" to "3-cell",
        "initialCondition" to "single-center",
        "mutationRate" to 0.0f,
        "stepsPerFrame" to 4f,
        "colorMode" to "binary"
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
        // Read ALL parameters
        val ruleNum = (params["rule"] as? Number)?.toInt() ?: 30
        val ruleBNum = (params["ruleB"] as? Number)?.toInt() ?: 90
        val blendMode = params["blendMode"] as? String ?: "none"
        val neighborWidth = params["neighborWidth"] as? String ?: "3-cell"
        val gridSize = (params["gridSize"] as? Number)?.toInt() ?: 256
        val initialCondition = params["initialCondition"] as? String ?: "single-center"
        val mutationRate = (params["mutationRate"] as? Number)?.toFloat() ?: 0.0f
        val stepsPerFrame = (params["stepsPerFrame"] as? Number)?.toInt()?.coerceAtLeast(1) ?: 4
        val colorMode = params["colorMode"] as? String ?: "binary"

        val is5Cell = neighborWidth == "5-cell"

        val w = bitmap.width
        val h = bitmap.height

        // Calculate cell size from grid size
        val cellSize = (w / gridSize).coerceAtLeast(1)
        val cols = w / cellSize
        val visibleRows = h / cellSize
        // Scroll offset based on time, scaled by stepsPerFrame
        val scrollOffset = (time * 30 * stepsPerFrame).toInt()
        val totalRows = visibleRows + scrollOffset

        // Initialize first row
        val rng = SeededRNG(seed)
        val currentRow = BooleanArray(cols)
        when (initialCondition) {
            "single-center" -> {
                currentRow[cols / 2] = true
            }
            "two-center" -> {
                // Three symmetric seeds for bilateral interference patterns
                currentRow[cols / 2] = true
                if (cols / 4 >= 0 && cols / 4 < cols) currentRow[cols / 4] = true
                if (3 * cols / 4 >= 0 && 3 * cols / 4 < cols) currentRow[3 * cols / 4] = true
            }
            else -> { // "random"
                for (i in 0 until cols) {
                    currentRow[i] = rng.boolean(0.5f)
                }
            }
        }

        // Precompute rule tables for rule A and rule B
        // For 5-cell totalistic: 6 possible sums (0..5), use rule mod 64 (6 bits)
        // For 3-cell standard: 8 possible patterns (0..7), use full 8-bit rule
        val ruleEntries = if (is5Cell) 6 else 8
        val effectiveRuleA = if (is5Cell) ruleNum % 64 else ruleNum
        val effectiveRuleB = if (is5Cell) ruleBNum % 64 else ruleBNum

        // Mutable rule bits for mutation support
        var currentRuleA = effectiveRuleA
        var currentRuleB = effectiveRuleB

        // Separate RNG for mutation so it doesn't disturb the init RNG sequence
        val mutRng = SeededRNG(seed xor 0x7F3A)

        // Compute all rows up to visible area
        // Also track density (neighbor count) per cell for density color mode
        val rowsData = Array(totalRows) { BooleanArray(cols) }
        val densityData = Array(totalRows) { IntArray(cols) }
        rowsData[0] = currentRow.copyOf()

        for (row in 1 until totalRows) {
            // Apply mutation: each generation, there's mutationRate probability of flipping one rule bit
            if (mutationRate > 0f && mutRng.random() < mutationRate) {
                val bit = mutRng.integer(0, ruleEntries - 1)
                currentRuleA = currentRuleA xor (1 shl bit)
            }
            if (blendMode != "none" && mutationRate > 0f && mutRng.random() < mutationRate) {
                val bit = mutRng.integer(0, ruleEntries - 1)
                currentRuleB = currentRuleB xor (1 shl bit)
            }

            // Build rule tables from current (possibly mutated) rule values
            val ruleTableA = BooleanArray(ruleEntries)
            val ruleTableB = BooleanArray(ruleEntries)
            for (i in 0 until ruleEntries) {
                ruleTableA[i] = (currentRuleA shr i) and 1 == 1
                ruleTableB[i] = (currentRuleB shr i) and 1 == 1
            }

            val prev = rowsData[row - 1]
            val next = BooleanArray(cols)
            val density = IntArray(cols)

            for (x in 0 until cols) {
                val resultA: Boolean
                val resultB: Boolean
                val neighborCount: Int

                if (is5Cell) {
                    // 5-cell totalistic neighborhood: sum of 5 cells (x-2, x-1, x, x+1, x+2)
                    var sum = 0
                    for (dx in -2..2) {
                        val nx = ((x + dx) % cols + cols) % cols
                        if (prev[nx]) sum++
                    }
                    neighborCount = sum
                    resultA = ruleTableA[sum]
                    resultB = ruleTableB[sum]
                } else {
                    // Standard 3-cell neighborhood
                    val left = if (x > 0) prev[x - 1] else prev[cols - 1]
                    val center = prev[x]
                    val right = if (x < cols - 1) prev[x + 1] else prev[0]
                    val pattern = (if (left) 4 else 0) or (if (center) 2 else 0) or (if (right) 1 else 0)
                    // Count ON neighbors for density (include center)
                    neighborCount = (if (left) 1 else 0) + (if (center) 1 else 0) + (if (right) 1 else 0)
                    resultA = ruleTableA[pattern]
                    resultB = ruleTableB[pattern]
                }

                // Combine rule A and rule B based on blend mode
                next[x] = when (blendMode) {
                    "xor" -> resultA xor resultB
                    "or" -> resultA || resultB
                    "and" -> resultA && resultB
                    else -> resultA // "none" - only rule A
                }
                density[x] = neighborCount
            }
            rowsData[row] = next
            densityData[row] = density
        }

        // Render visible rows
        val pixels = IntArray(w * h)
        val paletteColors = palette.colorInts()

        for (visRow in 0 until visibleRows) {
            val dataRow = visRow + scrollOffset
            if (dataRow < 0 || dataRow >= totalRows) continue
            val row = rowsData[dataRow]

            for (col in 0 until cols) {
                val color: Int = when (colorMode) {
                    "age" -> {
                        // Map row age (position in grid) to palette gradient
                        val t = if (visibleRows > 1) visRow.toFloat() / (visibleRows - 1).toFloat() else 0f
                        if (row[col]) {
                            palette.lerpColor(t)
                        } else {
                            Color.BLACK
                        }
                    }
                    "density" -> {
                        // Map neighbour count to palette gradient
                        val maxDensity = if (is5Cell) 5 else 3
                        val d = densityData[dataRow][col]
                        val t = if (maxDensity > 0) d.toFloat() / maxDensity.toFloat() else 0f
                        if (row[col]) {
                            palette.lerpColor(t)
                        } else {
                            // OFF cells with some neighbours get a dimmed color
                            if (d > 0) {
                                val dimColor = palette.lerpColor(t)
                                val r = (Color.red(dimColor) * 0.15f).toInt()
                                val g = (Color.green(dimColor) * 0.15f).toInt()
                                val b = (Color.blue(dimColor) * 0.15f).toInt()
                                Color.rgb(r, g, b)
                            } else {
                                Color.BLACK
                            }
                        }
                    }
                    else -> { // "binary"
                        if (row[col]) {
                            paletteColors[dataRow % paletteColors.size]
                        } else {
                            Color.BLACK
                        }
                    }
                }

                // Fill cell pixels
                val startX = col * cellSize
                val startY = visRow * cellSize
                for (dy in 0 until cellSize) {
                    val py = startY + dy
                    if (py >= h) break
                    for (dx in 0 until cellSize) {
                        val px = startX + dx
                        if (px >= w) break
                        pixels[py * w + px] = color
                    }
                }
            }
        }

        // Fill any remaining pixels at the bottom
        val filledH = visibleRows * cellSize
        for (py in filledH until h) {
            for (px in 0 until w) {
                pixels[py * w + px] = Color.BLACK
            }
        }

        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
        canvas.drawBitmap(bitmap, 0f, 0f, null)
    }
}
