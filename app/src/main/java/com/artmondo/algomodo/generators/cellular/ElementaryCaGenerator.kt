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
        Parameter.NumberParam("Rule A (0-255)", "rule", ParamGroup.COMPOSITION, "Primary Wolfram rule — notable: 30 (chaos/RNG), 90 (Sierpinski), 110 (Turing-complete), 184 (traffic flow). In 5-cell mode only bits 0-5 matter (rules 0-63).", 0f, 255f, 1f, 30f),
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
        val ruleNum = (params["rule"] as? Number)?.toInt() ?: 30
        val gridSize = (params["gridSize"] as? Number)?.toInt() ?: 256
        val initialCondition = params["initialCondition"] as? String ?: "single-center"

        val w = bitmap.width
        val h = bitmap.height

        // Calculate cell size from grid size
        val cellSize = (w / gridSize).coerceAtLeast(1)
        val cols = w / cellSize
        val visibleRows = h / cellSize
        // Scroll offset based on time
        val scrollOffset = (time * 30).toInt()
        val totalRows = visibleRows + scrollOffset

        // Initialize first row
        val rng = SeededRNG(seed)
        var currentRow = BooleanArray(cols)
        if (initialCondition == "single-center") {
            currentRow[cols / 2] = true
        } else {
            for (i in 0 until cols) {
                currentRow[i] = rng.boolean(0.5f)
            }
        }

        // Precompute rule table
        val ruleTable = BooleanArray(8)
        for (i in 0..7) {
            ruleTable[i] = (ruleNum shr i) and 1 == 1
        }

        // Compute all rows up to visible area
        val rowsData = Array(totalRows) { BooleanArray(cols) }
        rowsData[0] = currentRow.copyOf()

        for (row in 1 until totalRows) {
            val prev = rowsData[row - 1]
            val next = BooleanArray(cols)
            for (x in 0 until cols) {
                val left = if (x > 0) prev[x - 1] else prev[cols - 1]
                val center = prev[x]
                val right = if (x < cols - 1) prev[x + 1] else prev[0]
                val pattern = (if (left) 4 else 0) or (if (center) 2 else 0) or (if (right) 1 else 0)
                next[x] = ruleTable[pattern]
            }
            rowsData[row] = next
        }

        // Render visible rows
        val pixels = IntArray(w * h)
        val paletteColors = palette.colorInts()

        for (visRow in 0 until visibleRows) {
            val dataRow = visRow + scrollOffset
            if (dataRow < 0 || dataRow >= totalRows) continue
            val row = rowsData[dataRow]

            for (col in 0 until cols) {
                val color = if (row[col]) {
                    paletteColors[dataRow % paletteColors.size]
                } else {
                    Color.BLACK
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
