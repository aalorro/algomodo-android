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
import java.util.Arrays
import kotlin.math.sin

class GameOfLifeGenerator : Generator {

    override val id = "game-of-life"
    override val family = "cellular"
    override val styleName = "Game of Life"
    override val definition = "Conway's Game of Life: a zero-player cellular automaton where cells live or die based on neighbor count."
    override val algorithmNotes = "Standard B3/S23 rule set on a 2D grid. Cells with 3 neighbors are born; cells with 2 or 3 neighbors survive. Optional edge wrapping via toroidal topology."
    override val supportsVector = false
    override val supportsAnimation = true

    companion object {
        // Incremental simulation cache — avoids recomputing from step 0 each frame
        private var cKey = 0L
        private var cStep = 0
        private var cGridA: BooleanArray? = null
        private var cGridB: BooleanArray? = null
        private var cAge: IntArray? = null
        private var cTrail: IntArray? = null
        private var cChange: IntArray? = null
        private var cMaxAge = 1
        private var cOnA = true
    }

    override val parameterSchema = listOf(
        Parameter.NumberParam("Grid Size", "gridSize", ParamGroup.COMPOSITION, "Width/height of cell grid", 16f, 512f, 16f, 128f),
        Parameter.NumberParam("Initial Density", "density", ParamGroup.COMPOSITION, "Proportion of cells alive at start", 0.05f, 1f, 0.05f, 0.3f),
        Parameter.NumberParam("Iterations (static)", "iterations", ParamGroup.COMPOSITION, "Simulation steps for the static (non-animated) render", 1f, 500f, 1f, 100f),
        Parameter.SelectParam("Rule Set", "ruleSet", ParamGroup.COMPOSITION, "conway: B3/S23 (classic) | highlife: B36/S23 (replicators) | day-night: B3678/S34678 (day/night symmetry) | seeds: B2/S (explosive) | maze: B3/S12345 (grows mazes) | morley: B368/S245 (complex gliders)", listOf("conway", "highlife", "day-night", "seeds", "maze", "morley"), "conway"),
        Parameter.BooleanParam("Wrap Edges", "wrapEdges", ParamGroup.GEOMETRY, "Torus topology — edges wrap around", true),
        Parameter.NumberParam("Perturb Rate", "perturbRate", ParamGroup.FLOW_MOTION, "Fraction of cells randomly flipped each frame — prevents stagnation and continuously seeds new activity into stable regions", 0f, 0.02f, 0.001f, 0.0f),
        Parameter.NumberParam("Steps / Frame", "stepsPerFrame", ParamGroup.FLOW_MOTION, "GoL steps per animation frame — higher = faster simulation", 1f, 10f, 1f, 1f),
        Parameter.SelectParam("Color Mode", "colorMode", ParamGroup.COLOR, "binary: two-colour | age: alive cells coloured by longevity | trails: dying cells leave a fading afterimage | entropy: cells coloured by local neighbourhood density | neon: cyberpunk glow with pulsing cells | retro: pixel-art with grid lines and checkerboard | heatmap: neighbor density heat gradient", listOf("binary", "age", "trails", "entropy", "neon", "retro", "heatmap"), "binary")
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

    private fun getRules(ruleSet: String): Pair<BooleanArray, BooleanArray> {
        val birth = BooleanArray(9)
        val survive = BooleanArray(9)
        when (ruleSet) {
            "conway" -> { birth[3] = true; survive[2] = true; survive[3] = true }
            "highlife" -> { birth[3] = true; birth[6] = true; survive[2] = true; survive[3] = true }
            "day-night" -> {
                birth[3] = true; birth[6] = true; birth[7] = true; birth[8] = true
                survive[3] = true; survive[4] = true; survive[6] = true; survive[7] = true; survive[8] = true
            }
            "seeds" -> { birth[2] = true }
            "maze" -> { birth[3] = true; survive[1] = true; survive[2] = true; survive[3] = true; survive[4] = true; survive[5] = true }
            "morley" -> { birth[3] = true; birth[6] = true; birth[8] = true; survive[2] = true; survive[4] = true; survive[5] = true }
            else -> { birth[3] = true; survive[2] = true; survive[3] = true }
        }
        return Pair(birth, survive)
    }

    private fun borderCell(
        grid: BooleanArray, x: Int, y: Int, sz: Int, wrap: Boolean,
        birth: BooleanArray, survive: BooleanArray
    ): Boolean {
        var n = 0
        for (dy in -1..1) {
            for (dx in -1..1) {
                if (dx == 0 && dy == 0) continue
                val nx: Int; val ny: Int
                if (wrap) {
                    nx = (x + dx + sz) % sz; ny = (y + dy + sz) % sz
                } else {
                    nx = x + dx; ny = y + dy
                    if (nx < 0 || nx >= sz || ny < 0 || ny >= sz) continue
                }
                if (grid[ny * sz + nx]) n++
            }
        }
        return if (grid[y * sz + x]) survive[n] else birth[n]
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
        val targetSteps = if (time > 0.01f) (time * stepsPerFrame * 30f).toInt() else iterations
        val maxTrail = 30

        val (birthRule, surviveRule) = getRules(ruleSet)

        // Cache key from all simulation-affecting params
        val key = seed.toLong() * 31 + gridSize * 37L + (density * 1000).toInt() * 41L +
                ruleSet.hashCode() * 43L + (if (wrapEdges) 1L else 0L) * 47 +
                (perturbRate * 10000).toInt() * 53L

        val needsInit = key != cKey || targetSteps < cStep ||
                cGridA == null || cGridA!!.size != totalCells

        val cur: BooleanArray
        val nxt: BooleanArray
        val age: IntArray
        val trail: IntArray
        val changeCount: IntArray
        var maxAge: Int
        val startStep: Int

        if (needsInit) {
            val rng = SeededRNG(seed)
            val a = BooleanArray(totalCells)
            val b = BooleanArray(totalCells)
            val ag = IntArray(totalCells)
            val tr = IntArray(totalCells)
            val ch = IntArray(totalCells)
            for (i in a.indices) {
                a[i] = rng.random() < density
                if (a[i]) ag[i] = 1
            }
            cur = a; nxt = b; age = ag; trail = tr; changeCount = ch
            maxAge = 1; startStep = 0
            cKey = key; cGridA = a; cGridB = b; cAge = ag; cTrail = tr; cChange = ch
            cMaxAge = 1; cOnA = true
        } else {
            cur = if (cOnA) cGridA!! else cGridB!!
            nxt = if (cOnA) cGridB!! else cGridA!!
            age = cAge!!; trail = cTrail!!; changeCount = cChange!!
            maxAge = cMaxAge; startStep = cStep
        }

        // --- Simulate from startStep to targetSteps ---
        var curBuf = cur
        var nxtBuf = nxt
        for (s in startStep until targetSteps) {
            // Interior cells — unrolled 8-neighbor, no bounds check
            for (y in 1 until gridSize - 1) {
                val rb = y * gridSize
                for (x in 1 until gridSize - 1) {
                    val idx = rb + x
                    var n = 0
                    if (curBuf[idx - gridSize - 1]) n++
                    if (curBuf[idx - gridSize]) n++
                    if (curBuf[idx - gridSize + 1]) n++
                    if (curBuf[idx - 1]) n++
                    if (curBuf[idx + 1]) n++
                    if (curBuf[idx + gridSize - 1]) n++
                    if (curBuf[idx + gridSize]) n++
                    if (curBuf[idx + gridSize + 1]) n++
                    nxtBuf[idx] = if (curBuf[idx]) surviveRule[n] else birthRule[n]
                }
            }
            // Border rows (top and bottom)
            for (y in intArrayOf(0, gridSize - 1)) {
                for (x in 0 until gridSize) {
                    nxtBuf[y * gridSize + x] = borderCell(curBuf, x, y, gridSize, wrapEdges, birthRule, surviveRule)
                }
            }
            // Border columns (left and right, excluding corners)
            for (y in 1 until gridSize - 1) {
                nxtBuf[y * gridSize] = borderCell(curBuf, 0, y, gridSize, wrapEdges, birthRule, surviveRule)
                nxtBuf[y * gridSize + gridSize - 1] = borderCell(curBuf, gridSize - 1, y, gridSize, wrapEdges, birthRule, surviveRule)
            }

            // Perturbation: pick random cells to flip (O(numFlips) not O(totalCells))
            if (perturbRate > 0f) {
                val pRng = SeededRNG(seed xor 0x5A5A5A5A.toInt() xor s)
                val numFlips = (totalCells * perturbRate).toInt().coerceAtLeast(1)
                for (j in 0 until numFlips) {
                    val idx = pRng.integer(0, totalCells - 1)
                    nxtBuf[idx] = !nxtBuf[idx]
                }
            }

            // Update tracking arrays
            for (i in curBuf.indices) {
                val wasAlive = curBuf[i]
                val isAlive = nxtBuf[i]
                if (isAlive) {
                    age[i]++
                    if (age[i] > maxAge) maxAge = age[i]
                    trail[i] = maxTrail
                } else {
                    if (wasAlive) trail[i] = maxTrail
                    age[i] = 0
                    if (trail[i] > 0) trail[i]--
                }
                if (wasAlive != isAlive) changeCount[i]++
            }

            // Swap double-buffers
            val tmp = curBuf; curBuf = nxtBuf; nxtBuf = tmp
        }

        // Save cache
        cOnA = curBuf === cGridA
        cMaxAge = maxAge; cStep = targetSteps

        // --- Render: compute cell colors, then block-fill pixels ---
        val cellColors = IntArray(totalCells)
        val paletteLut = palette.buildLut(256)
        val paletteColors = palette.colorInts()
        val invMaxAge = 255f / maxAge.coerceAtLeast(1)

        when (colorMode) {
            "binary" -> {
                for (i in 0 until totalCells) {
                    cellColors[i] = if (curBuf[i]) paletteColors[(i % gridSize + i / gridSize) % paletteColors.size]
                    else Color.BLACK
                }
            }
            "age" -> {
                for (i in 0 until totalCells) {
                    cellColors[i] = if (curBuf[i]) paletteLut[(age[i] * invMaxAge).toInt().coerceIn(0, 255)]
                    else Color.BLACK
                }
            }
            "trails" -> {
                val trailBase = paletteLut[128]
                val tR = Color.red(trailBase); val tG = Color.green(trailBase); val tB = Color.blue(trailBase)
                for (i in 0 until totalCells) {
                    cellColors[i] = if (curBuf[i]) {
                        paletteLut[(age[i] * invMaxAge).toInt().coerceIn(0, 255)]
                    } else if (trail[i] > 0) {
                        val f = trail[i].toFloat() / maxTrail * 0.5f
                        Color.rgb((tR * f).toInt(), (tG * f).toInt(), (tB * f).toInt())
                    } else Color.BLACK
                }
            }
            "entropy" -> {
                val maxChanges = changeCount.max().coerceAtLeast(1)
                val invMaxChanges = 255f / maxChanges
                for (i in 0 until totalCells) {
                    val frac = (changeCount[i] * invMaxChanges).toInt().coerceIn(0, 255)
                    cellColors[i] = if (curBuf[i]) {
                        paletteLut[frac]
                    } else if (changeCount[i] > 0) {
                        val c = paletteLut[frac]
                        Color.rgb(Color.red(c) shr 2, Color.green(c) shr 2, Color.blue(c) shr 2)
                    } else Color.BLACK
                }
            }
            "neon" -> {
                val pulse = (sin(time * 3f) * 0.15f + 0.85f).coerceIn(0.7f, 1f)
                val bgColor = Color.rgb(3, 2, 8)
                for (i in 0 until totalCells) {
                    cellColors[i] = if (curBuf[i]) {
                        val c = paletteLut[(age[i] * invMaxAge).toInt().coerceIn(0, 255)]
                        val r = (Color.red(c) * pulse).toInt().coerceIn(0, 255)
                        val g = (Color.green(c) * pulse).toInt().coerceIn(0, 255)
                        val b = (Color.blue(c) * pulse).toInt().coerceIn(0, 255)
                        Color.rgb(r, g, b)
                    } else if (trail[i] > 0) {
                        val fade = trail[i].toFloat() / maxTrail * 0.35f
                        val c = paletteLut[128]
                        Color.rgb(
                            (Color.red(c) * fade).toInt().coerceIn(0, 255),
                            (Color.green(c) * fade).toInt().coerceIn(0, 255),
                            (Color.blue(c) * fade).toInt().coerceIn(0, 255)
                        )
                    } else bgColor
                }
            }
            "retro" -> {
                val bgDark = Color.rgb(18, 18, 28)
                val bgLight = Color.rgb(24, 24, 34)
                for (i in 0 until totalCells) {
                    val gx = i % gridSize; val gy = i / gridSize
                    cellColors[i] = if (curBuf[i]) {
                        paletteColors[(gx + gy) % paletteColors.size]
                    } else {
                        if ((gx + gy) % 2 == 0) bgLight else bgDark
                    }
                }
            }
            "heatmap" -> {
                // Compute neighbor counts from final grid state
                val nbr = IntArray(totalCells)
                for (y in 1 until gridSize - 1) {
                    val rb = y * gridSize
                    for (x in 1 until gridSize - 1) {
                        val idx = rb + x
                        var n = 0
                        if (curBuf[idx - gridSize - 1]) n++
                        if (curBuf[idx - gridSize]) n++
                        if (curBuf[idx - gridSize + 1]) n++
                        if (curBuf[idx - 1]) n++
                        if (curBuf[idx + 1]) n++
                        if (curBuf[idx + gridSize - 1]) n++
                        if (curBuf[idx + gridSize]) n++
                        if (curBuf[idx + gridSize + 1]) n++
                        nbr[idx] = n
                    }
                }
                // Border neighbor counts
                for (y in intArrayOf(0, gridSize - 1)) {
                    for (x in 0 until gridSize) {
                        nbr[y * gridSize + x] = countNeighbors(curBuf, x, y, gridSize, wrapEdges)
                    }
                }
                for (y in 1 until gridSize - 1) {
                    nbr[y * gridSize] = countNeighbors(curBuf, 0, y, gridSize, wrapEdges)
                    nbr[y * gridSize + gridSize - 1] = countNeighbors(curBuf, gridSize - 1, y, gridSize, wrapEdges)
                }
                for (i in 0 until totalCells) {
                    val t = (nbr[i] * 255 / 8).coerceIn(0, 255)
                    val heat = paletteLut[t]
                    cellColors[i] = if (curBuf[i]) heat
                    else if (nbr[i] > 0) Color.rgb(Color.red(heat) shr 2, Color.green(heat) shr 2, Color.blue(heat) shr 2)
                    else Color.BLACK
                }
            }
            else -> {
                for (i in 0 until totalCells) {
                    cellColors[i] = if (curBuf[i]) paletteColors[(i % gridSize + i / gridSize) % paletteColors.size]
                    else Color.BLACK
                }
            }
        }

        // Block-fill pixels from cell colors (avoids per-pixel division)
        val pixels = IntArray(w * h)
        for (gy in 0 until gridSize) {
            val py0 = gy * h / gridSize
            val py1 = (gy + 1) * h / gridSize
            val gyOff = gy * gridSize
            for (gx in 0 until gridSize) {
                val px0 = gx * w / gridSize
                val px1 = (gx + 1) * w / gridSize
                val color = cellColors[gyOff + gx]
                for (py in py0 until py1) {
                    Arrays.fill(pixels, py * w + px0, py * w + px1, color)
                }
            }
        }

        // Retro grid line overlay
        if (colorMode == "retro") {
            val lineColor = Color.rgb(50, 50, 65)
            for (gy in 0..gridSize) {
                val py = (gy * h / gridSize).coerceAtMost(h - 1)
                Arrays.fill(pixels, py * w, py * w + w, lineColor)
            }
            for (gx in 0..gridSize) {
                val px = (gx * w / gridSize).coerceAtMost(w - 1)
                for (py in 0 until h) {
                    pixels[py * w + px] = lineColor
                }
            }
        }

        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
        canvas.drawBitmap(bitmap, 0f, 0f, null)
    }

    private fun countNeighbors(grid: BooleanArray, x: Int, y: Int, sz: Int, wrap: Boolean): Int {
        var n = 0
        for (dy in -1..1) {
            for (dx in -1..1) {
                if (dx == 0 && dy == 0) continue
                val nx: Int; val ny: Int
                if (wrap) {
                    nx = (x + dx + sz) % sz; ny = (y + dy + sz) % sz
                } else {
                    nx = x + dx; ny = y + dy
                    if (nx < 0 || nx >= sz || ny < 0 || ny >= sz) continue
                }
                if (grid[ny * sz + nx]) n++
            }
        }
        return n
    }

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float {
        val gridSize = (params["gridSize"] as? Number)?.toInt() ?: 128
        return (gridSize / 256f).coerceIn(0.2f, 1f)
    }
}
