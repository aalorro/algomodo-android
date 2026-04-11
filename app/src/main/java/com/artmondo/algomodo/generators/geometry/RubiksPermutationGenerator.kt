package com.artmondo.algomodo.generators.geometry

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.artmondo.algomodo.audio.AudioAnalysis
import com.artmondo.algomodo.core.rng.SeededRNG
import com.artmondo.algomodo.data.palettes.Palette
import com.artmondo.algomodo.generators.Generator
import com.artmondo.algomodo.generators.ParamGroup
import com.artmondo.algomodo.generators.Parameter
import com.artmondo.algomodo.generators.Quality
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Rubik's Permutation — NxN grid of colored cells permuted by Rubik's
 * Cube-style moves (row shifts, column shifts, outer-ring rotations).
 *
 * Port of web rubiks-permutation.ts. State is stored as an IntArray
 * mapping each grid cell to its original index. Each move cyclically
 * permutes a strip of `stripWidth` cells by one position.
 */
class RubiksPermutationGenerator : Generator {

    override val id = "rubiks-permutation"
    override val family = "geometry"
    override val styleName = "Rubik's Permutation"
    override val definition =
        "A grid of color cells permuted by Rubik's Cube-style moves — row/column shifts and " +
        "outer-ring rotations cyclically displace strips, producing layered, woven, and braided color patterns."
    override val algorithmNotes =
        "NxN grid is initialized from a palette via one of four modes (faces partition, horizontal gradient, " +
        "diagonal gradient, or radial rings). A seeded sequence of K moves is generated; each move is one of 6 " +
        "types — row shift L/R, column shift U/D, outer ring rotation CW/CCW. Each move cyclically permutes a " +
        "strip of stripWidth cells by 1 position. Trail modes: final (current state), displacement (saturation " +
        "by move count), cycles (DFS on permutation, color by cycle length), parity (even/odd cycle lengths), " +
        "overlay (blend 4 snapshot states). Animation: progressive applies one move per tick; cycle reverses at K."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema = listOf(
        Parameter.NumberParam(
            "Grid Size", "gridN", ParamGroup.GEOMETRY,
            "NxN cell grid resolution",
            6f, 60f, 1f, 24f
        ),
        Parameter.NumberParam(
            "Move Count", "numMoves", ParamGroup.COMPOSITION,
            "Number of cube-style permutation moves to apply",
            1f, 300f, 1f, 40f
        ),
        Parameter.NumberParam(
            "Strip Width", "stripWidth", ParamGroup.GEOMETRY,
            "Number of cells per row/column band — 1 is classic, larger values produce blockier patterns",
            1f, 5f, 1f, 1f
        ),
        Parameter.SelectParam(
            "Init Pattern", "initMode", ParamGroup.COMPOSITION,
            "faces: 6-region cube-net partition | gradient-h: horizontal palette gradient | gradient-d: diagonal palette gradient | rings: radial palette gradient",
            listOf("faces", "gradient-h", "gradient-d", "rings"),
            "faces"
        ),
        Parameter.SelectParam(
            "Move Style", "moveStyle", ParamGroup.COMPOSITION,
            "random: seeded random move sequence | algorithm: deterministic LFRUDB-like sequence cycling through all 6 move types",
            listOf("random", "algorithm"),
            "random"
        ),
        Parameter.SelectParam(
            "Trail Mode", "trailMode", ParamGroup.COLOR,
            "final: end state | displacement: brighter where moved more | cycles: color by permutation cycle length | parity: even/odd cycle lengths in contrasting hues | overlay: blend 4 intermediate snapshots",
            listOf("final", "displacement", "cycles", "parity", "overlay"),
            "final"
        ),
        Parameter.NumberParam(
            "Cell Gap", "cellGap", ParamGroup.GEOMETRY,
            "Pixel gap between cells for grout-line effect",
            0f, 5f, 0.5f, 0.5f
        ),
        Parameter.SelectParam(
            "Anim Mode", "animMode", ParamGroup.FLOW_MOTION,
            "progressive: apply moves one-by-one over time then loop | cycle: forward then reverse",
            listOf("progressive", "cycle"),
            "progressive"
        ),
        Parameter.NumberParam(
            "Speed", "speed", ParamGroup.FLOW_MOTION,
            "Moves applied per second when animated",
            0.1f, 10f, 0.1f, 2f
        ),
        Parameter.NumberParam(
            "Audio Reactivity", "reactivity", ParamGroup.FLOW_MOTION,
            "How strongly audio bass accelerates the permutation animation",
            0f, 2f, 0.1f, 0f
        )
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "gridN" to 24f,
        "numMoves" to 40f,
        "stripWidth" to 1f,
        "initMode" to "faces",
        "moveStyle" to "random",
        "trailMode" to "final",
        "cellGap" to 0.5f,
        "animMode" to "progressive",
        "speed" to 2f,
        "reactivity" to 0f
    )

    private companion object {
        // Move types
        const val MOVE_ROW_LEFT = 0
        const val MOVE_ROW_RIGHT = 1
        const val MOVE_COL_UP = 2
        const val MOVE_COL_DOWN = 3
        const val MOVE_RING_CW = 4
        const val MOVE_RING_CCW = 5

        const val LUT_SIZE = 256
    }

    /**
     * Move descriptor stored as two parallel IntArrays (SoA) plus a constant
     * stripWidth. Each move uses the same stripWidth, so a per-move array
     * is wasteful.
     */
    private class MoveList(val types: IntArray, val indices: IntArray, val stripWidth: Int) {
        val size: Int get() = types.size
    }

    // ── Move generation ─────────────────────────────────────────────
    private fun generateMoves(
        seed: Int, count: Int, n: Int, stripWidth: Int, mode: String
    ): MoveList {
        val rng = SeededRNG(seed)
        val types = IntArray(count)
        val indices = IntArray(count)
        val halfN = max(1, n / 2)

        if (mode == "algorithm") {
            // Deterministic LFRUDB-like sequence: cycles through 6 move types,
            // strip index advances with a coprime stride to spread coverage.
            for (i in 0 until count) {
                val t = i % 6
                types[i] = t
                indices[i] = if (t == MOVE_RING_CW || t == MOVE_RING_CCW) {
                    (i * 3 + 1) % halfN
                } else {
                    (i * 7 + 3) % n
                }
            }
        } else {
            for (i in 0 until count) {
                val t = rng.integer(0, 5)
                types[i] = t
                indices[i] = if (t == MOVE_RING_CW || t == MOVE_RING_CCW) {
                    rng.integer(0, halfN - 1)
                } else {
                    rng.integer(0, n - 1)
                }
            }
        }
        return MoveList(types, indices, stripWidth)
    }

    // ── Apply one move to the permutation in place ──────────────────
    private fun applyMove(
        perm: IntArray, n: Int,
        type: Int, idx: Int, width: Int,
        displaced: IntArray?
    ) {
        when (type) {
            MOVE_ROW_LEFT, MOVE_ROW_RIGHT -> {
                for (dy in 0 until width) {
                    val row = (idx + dy) % n
                    val base = row * n
                    if (type == MOVE_ROW_LEFT) {
                        val tmp = perm[base]
                        System.arraycopy(perm, base + 1, perm, base, n - 1)
                        perm[base + n - 1] = tmp
                    } else {
                        val tmp = perm[base + n - 1]
                        System.arraycopy(perm, base, perm, base + 1, n - 1)
                        perm[base] = tmp
                    }
                    if (displaced != null) {
                        for (j in 0 until n) displaced[base + j]++
                    }
                }
            }
            MOVE_COL_UP, MOVE_COL_DOWN -> {
                for (dx in 0 until width) {
                    val col = (idx + dx) % n
                    if (type == MOVE_COL_UP) {
                        val tmp = perm[col]
                        for (i in 0 until n - 1) perm[i * n + col] = perm[(i + 1) * n + col]
                        perm[(n - 1) * n + col] = tmp
                    } else {
                        val tmp = perm[(n - 1) * n + col]
                        for (i in n - 1 downTo 1) perm[i * n + col] = perm[(i - 1) * n + col]
                        perm[col] = tmp
                    }
                    if (displaced != null) {
                        for (i in 0 until n) displaced[i * n + col]++
                    }
                }
            }
            MOVE_RING_CW, MOVE_RING_CCW -> {
                val halfN = n / 2
                if (halfN == 0) return
                val r = idx % halfN
                val top = r
                val left = r
                val bottom = n - 1 - r
                val right = n - 1 - r
                if (top >= bottom || left >= right) return

                // Build perimeter index list (clockwise from top-left)
                val perimLen = 2 * (right - left) + 2 * (bottom - top)
                val perim = IntArray(perimLen)
                var k = 0
                for (j in left..right) perim[k++] = top * n + j
                for (i in (top + 1)..bottom) perim[k++] = i * n + right
                for (j in (right - 1) downTo left) perim[k++] = bottom * n + j
                for (i in (bottom - 1) downTo (top + 1)) perim[k++] = i * n + left

                if (type == MOVE_RING_CW) {
                    val last = perm[perim[perimLen - 1]]
                    for (kk in perimLen - 1 downTo 1) perm[perim[kk]] = perm[perim[kk - 1]]
                    perm[perim[0]] = last
                } else {
                    val first = perm[perim[0]]
                    for (kk in 0 until perimLen - 1) perm[perim[kk]] = perm[perim[kk + 1]]
                    perm[perim[perimLen - 1]] = first
                }
                if (displaced != null) {
                    for (kk in 0 until perimLen) displaced[perim[kk]]++
                }
            }
        }
    }

    // ── Initial cell color (packed RGB Int) ─────────────────────────
    private fun initColor(
        initMode: String, x: Int, y: Int, n: Int,
        paletteInts: IntArray, lut: IntArray
    ): Int {
        val lutMax = lut.size - 1
        return when (initMode) {
            "faces" -> {
                // Partition into 6 regions in a 3x2 layout, like an unfolded cube net.
                val rx = min(2, (x.toFloat() / n * 3f).toInt())
                val ry = min(1, (y.toFloat() / n * 2f).toInt())
                paletteInts[(ry * 3 + rx) % paletteInts.size]
            }
            "gradient-h" ->
                lut[((x.toFloat() / max(1, n - 1)) * lutMax).toInt().coerceIn(0, lutMax)]
            "gradient-d" ->
                lut[(((x + y).toFloat() / max(1, 2 * (n - 1))) * lutMax).toInt().coerceIn(0, lutMax)]
            "rings" -> {
                val c = (n - 1) / 2f
                val dx = x - c
                val dy = y - c
                val maxR = sqrt(2.0).toFloat() * c
                val t = sqrt(dx * dx + dy * dy) / max(1f, maxR)
                lut[(t * lutMax).toInt().coerceIn(0, lutMax)]
            }
            else -> paletteInts[0]
        }
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
        val w = bitmap.width.toFloat()
        val h = bitmap.height.toFloat()
        if (w == 0f || h == 0f) return

        val n = ((params["gridN"] as? Number)?.toInt() ?: 24).coerceIn(2, 60)
        val k = ((params["numMoves"] as? Number)?.toInt() ?: 40).coerceIn(1, 300)
        val stripWidth = ((params["stripWidth"] as? Number)?.toInt() ?: 1).coerceIn(1, 5)
        val initMode = (params["initMode"] as? String) ?: "faces"
        val moveStyle = (params["moveStyle"] as? String) ?: "random"
        val trailMode = (params["trailMode"] as? String) ?: "final"
        val cellGap = max(0f, (params["cellGap"] as? Number)?.toFloat() ?: 0.5f)
        val anim = (params["animMode"] as? String) ?: "progressive"
        val speed = (params["speed"] as? Number)?.toFloat() ?: 2f

        val rx = (params["reactivity"] as? Number)?.toFloat() ?: 0f
        val audioAnalysis = params["_audioAnalysis"] as? AudioAnalysis
        val audioBass = (audioAnalysis?.getBass(time) ?: 0f) * rx

        canvas.drawColor(Color.BLACK)

        val cellCount = n * n

        // Raw palette color ints (for discrete indexing — matches web `palette[i]`).
        val paletteInts = palette.colorInts().toIntArray()
        if (paletteInts.isEmpty()) return
        // Pre-built gradient LUT — amortizes lerpColor across all cells in gradient modes.
        val paletteLut = palette.buildLut(LUT_SIZE)

        // Initial color cache: indexed by linear cell position.
        val initColors = IntArray(cellCount)
        for (y in 0 until n) {
            val rowBase = y * n
            for (x in 0 until n) {
                initColors[rowBase + x] = initColor(initMode, x, y, n, paletteInts, paletteLut)
            }
        }

        // Generate move sequence (deterministic per seed).
        val moves = generateMoves(seed, k, n, stripWidth, moveStyle)

        // Determine how many moves to apply (animation).
        var movesToApply = k
        if (time > 0f) {
            if (anim == "progressive") {
                val t = (time * speed * (1f + audioBass)) % (k + 4f)
                movesToApply = floor(t).toInt().coerceIn(0, k)
            } else if (anim == "cycle") {
                val period = 2f * k
                val t = (time * speed * (1f + audioBass)) % period
                // t ∈ [0, 2k), so result is naturally in [0, k].
                movesToApply = if (t < k) floor(t).toInt() else k - floor(t - k).toInt()
            }
        }

        // Initialize permutation as identity.
        val perm = IntArray(cellCount) { it }

        val trackDisp = trailMode == "displacement"
        val displaced = if (trackDisp) IntArray(cellCount) else null

        for (m in 0 until movesToApply) {
            applyMove(perm, n, moves.types[m], moves.indices[m], moves.stripWidth, displaced)
        }

        val cellW = w / n
        val cellH = h / n
        val cellWFill = cellW - cellGap
        val cellHFill = cellH - cellGap

        val paint = Paint().apply {
            style = Paint.Style.FILL
            isAntiAlias = false
        }

        when (trailMode) {
            "overlay" -> drawOverlay(
                canvas, paint, n, cellCount, initColors, moves, movesToApply,
                cellW, cellH, cellWFill, cellHFill
            )
            "cycles" -> drawCycles(
                canvas, paint, n, cellCount, perm, palette,
                cellW, cellH, cellWFill, cellHFill
            )
            "parity" -> drawParity(
                canvas, paint, n, cellCount, perm, paletteInts,
                cellW, cellH, cellWFill, cellHFill
            )
            "displacement" -> drawDisplacement(
                canvas, paint, n, cellCount, perm, displaced!!, initColors,
                cellW, cellH, cellWFill, cellHFill
            )
            else -> drawFinal(canvas, paint, n, perm, initColors, cellW, cellH, cellWFill, cellHFill)
        }
    }

    // ── Render: final state (default) ───────────────────────────────
    private fun drawFinal(
        canvas: Canvas, paint: Paint, n: Int, perm: IntArray, initColors: IntArray,
        cellW: Float, cellH: Float, cellWFill: Float, cellHFill: Float
    ) {
        for (y in 0 until n) {
            val rowBase = y * n
            val yPx = y * cellH
            val yBot = yPx + cellHFill
            for (x in 0 until n) {
                paint.color = initColors[perm[rowBase + x]]
                val xPx = x * cellW
                canvas.drawRect(xPx, yPx, xPx + cellWFill, yBot, paint)
            }
        }
    }

    // ── Render: displacement (tint by move count) ───────────────────
    private fun drawDisplacement(
        canvas: Canvas, paint: Paint, n: Int, cellCount: Int,
        perm: IntArray, displaced: IntArray, initColors: IntArray,
        cellW: Float, cellH: Float, cellWFill: Float, cellHFill: Float
    ) {
        var maxD = 1
        for (i in 0 until cellCount) if (displaced[i] > maxD) maxD = displaced[i]
        val invMaxD = 1f / maxD

        for (y in 0 until n) {
            val rowBase = y * n
            val yPx = y * cellH
            val yBot = yPx + cellHFill
            for (x in 0 until n) {
                val i = rowBase + x
                val base = initColors[perm[i]]
                val kMul = 0.3f + 0.7f * (displaced[i] * invMaxD)
                val r = (((base ushr 16) and 0xFF) * kMul).toInt()
                val g = (((base ushr 8) and 0xFF) * kMul).toInt()
                val b = ((base and 0xFF) * kMul).toInt()
                paint.color = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                val xPx = x * cellW
                canvas.drawRect(xPx, yPx, xPx + cellWFill, yBot, paint)
            }
        }
    }

    // ── DFS on perm to compute cycle length for each cell ───────────
    private fun computeCycleLengths(perm: IntArray, cellCount: Int): IntArray {
        val visited = BooleanArray(cellCount)
        val cycleLen = IntArray(cellCount)
        for (i in 0 until cellCount) {
            if (visited[i]) continue
            var j = i
            var len = 0
            while (!visited[j]) {
                visited[j] = true
                j = perm[j]
                len++
            }
            // Back-fill cycle length for each member of this cycle.
            var kk = i
            while (cycleLen[kk] == 0) {
                cycleLen[kk] = len
                kk = perm[kk]
            }
        }
        return cycleLen
    }

    // ── Render: cycles (color by permutation cycle length) ─────────
    private fun drawCycles(
        canvas: Canvas, paint: Paint, n: Int, cellCount: Int,
        perm: IntArray, palette: Palette,
        cellW: Float, cellH: Float, cellWFill: Float, cellHFill: Float
    ) {
        val cycleLen = computeCycleLengths(perm, cellCount)
        var maxLen = 1
        for (i in 0 until cellCount) if (cycleLen[i] > maxLen) maxLen = cycleLen[i]

        // Pre-compute palette LUT indexed by cycle length — one lerp per length
        // instead of per cell.
        val cycleLut = IntArray(maxLen + 1) { palette.lerpColor(it.toFloat() / maxLen) }

        for (y in 0 until n) {
            val rowBase = y * n
            val yPx = y * cellH
            val yBot = yPx + cellHFill
            for (x in 0 until n) {
                paint.color = cycleLut[cycleLen[rowBase + x]]
                val xPx = x * cellW
                canvas.drawRect(xPx, yPx, xPx + cellWFill, yBot, paint)
            }
        }
    }

    // ── Render: parity (even/odd cycle lengths in contrasting hues) ─
    private fun drawParity(
        canvas: Canvas, paint: Paint, n: Int, cellCount: Int,
        perm: IntArray, paletteInts: IntArray,
        cellW: Float, cellH: Float, cellWFill: Float, cellHFill: Float
    ) {
        val cycleLen = computeCycleLengths(perm, cellCount)
        val parityEven = paletteInts[0]
        val parityOdd = paletteInts[min(paletteInts.size - 1, 2)]

        for (y in 0 until n) {
            val rowBase = y * n
            val yPx = y * cellH
            val yBot = yPx + cellHFill
            for (x in 0 until n) {
                paint.color = if ((cycleLen[rowBase + x] and 1) == 0) parityEven else parityOdd
                val xPx = x * cellW
                canvas.drawRect(xPx, yPx, xPx + cellWFill, yBot, paint)
            }
        }
    }

    // ── Render: overlay (blend 4 snapshots) ─────────────────────────
    private fun drawOverlay(
        canvas: Canvas, paint: Paint, n: Int, cellCount: Int,
        initColors: IntArray, moves: MoveList, movesToApply: Int,
        cellW: Float, cellH: Float, cellWFill: Float, cellHFill: Float
    ) {
        val passes = 4
        val alphaBits = ((255 / passes) shl 24)

        val tmp = IntArray(cellCount)
        for (p in 1..passes) {
            val target = (movesToApply * p) / passes
            for (i in 0 until cellCount) tmp[i] = i
            for (m in 0 until target) {
                applyMove(tmp, n, moves.types[m], moves.indices[m], moves.stripWidth, null)
            }
            for (y in 0 until n) {
                val rowBase = y * n
                val yPx = y * cellH
                val yBot = yPx + cellHFill
                for (x in 0 until n) {
                    val base = initColors[tmp[rowBase + x]]
                    paint.color = (base and 0x00FFFFFF) or alphaBits
                    val xPx = x * cellW
                    canvas.drawRect(xPx, yPx, xPx + cellWFill, yBot, paint)
                }
            }
        }
    }

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float {
        val n = (params["gridN"] as? Number)?.toInt() ?: 24
        val k = (params["numMoves"] as? Number)?.toInt() ?: 40
        return ((n * n * 8 + k * n) / 30000f).coerceIn(0.1f, 1f)
    }
}
