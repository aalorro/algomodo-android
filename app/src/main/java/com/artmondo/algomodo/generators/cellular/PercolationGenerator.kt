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

class PercolationGenerator : Generator {

    override val id = "cellular-percolation"
    override val family = "cellular"
    override val styleName = "Percolation"
    override val definition = "Bond/site percolation model showing how connectivity emerges at a critical probability threshold."
    override val algorithmNotes = "In site percolation, each cell is open with probability p. In bond percolation, each edge between cells is open with probability p. A flood-fill from the top row reveals the connected cluster. The time parameter controls gradual reveal of the percolation cluster from top to bottom."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema = listOf(
        Parameter.NumberParam("Grid Size", "gridSize", ParamGroup.COMPOSITION, null, 32f, 256f, 16f, 128f),
        Parameter.SelectParam("Mode", "percolationMode", ParamGroup.COMPOSITION, "site: each cell independently open with probability p | invasion: fractal BFS flooding from seeds in resistance order — creates branching drainage networks", listOf("site", "invasion"), "site"),
        Parameter.NumberParam("Occupancy p", "occupancyP", ParamGroup.COMPOSITION, "Open probability / swept fraction — critical threshold p_c ≈ 0.593 for square lattice; invasion mode opens exactly p*N cells", 0f, 1f, 0.01f, 0.593f),
        Parameter.NumberParam("Invasion Seeds", "invasionSeeds", ParamGroup.COMPOSITION, "Number of seed points from which invasion floods outward (invasion mode only)", 1f, 12f, 1f, 4f),
        Parameter.NumberParam("Noise Mix", "noiseMix", ParamGroup.TEXTURE, "0 = purely random site values → standard fractal percolation | 1 = fully correlated noise → geologic / organic blob shapes", 0f, 1f, 0.05f, 0.3f),
        Parameter.NumberParam("Noise Scale", "noiseScale", ParamGroup.TEXTURE, "Spatial frequency of correlated noise — lower = large geologic blobs, higher = fine-grained texture", 1f, 20f, 1f, 6f),
        Parameter.SelectParam("Show Spanning", "showSpanning", ParamGroup.COLOR, "Highlight in white the cluster that bridges top edge to bottom edge — marks the percolating backbone", listOf("on", "off"), "on"),
        Parameter.NumberParam("Sweep Speed", "sweepSpeed", ParamGroup.FLOW_MOTION, "How fast p oscillates across the critical threshold in animation mode", 0.1f, 3.0f, 0.1f, 0.5f),
        Parameter.NumberParam("Sweep Amplitude", "sweepAmp", ParamGroup.FLOW_MOTION, "How far p swings above and below the base value in animation mode", 0.05f, 0.4f, 0.05f, 0.2f),
        Parameter.SelectParam("Color Mode", "colorMode", ParamGroup.COLOR, "cluster-size: log-scaled palette by cluster area | cluster-id: each cluster a distinct palette color | monochrome: flat", listOf("cluster-size", "cluster-id", "monochrome"), "cluster-size")
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "gridSize" to 128f,
        "percolationMode" to "site",
        "occupancyP" to 0.593f,
        "invasionSeeds" to 4f,
        "noiseMix" to 0.3f,
        "noiseScale" to 6f,
        "showSpanning" to "on",
        "sweepSpeed" to 0.5f,
        "sweepAmp" to 0.2f,
        "colorMode" to "cluster-size"
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
        val probability = (params["occupancyP"] as? Number)?.toFloat() ?: 0.593f
        val gridSize = (params["gridSize"] as? Number)?.toInt() ?: 128
        val mode = params["percolationMode"] as? String ?: "site"

        val w = bitmap.width
        val h = bitmap.height
        val revealedRows = gridSize
        val totalCells = gridSize * gridSize

        val rng = SeededRNG(seed)

        if (mode == "site") {
            // Site percolation
            val open = BooleanArray(totalCells) { rng.random() < probability }

            // Flood fill from top row
            val connected = BooleanArray(totalCells)
            val queue = ArrayDeque<Int>()

            // Start from open cells in top row
            for (x in 0 until gridSize) {
                if (open[x]) {
                    connected[x] = true
                    queue.add(x)
                }
            }

            val dx4 = intArrayOf(0, 1, 0, -1)
            val dy4 = intArrayOf(-1, 0, 1, 0)

            while (queue.isNotEmpty()) {
                val idx = queue.removeFirst()
                val cx = idx % gridSize
                val cy = idx / gridSize

                for (d in 0..3) {
                    val nx = cx + dx4[d]
                    val ny = cy + dy4[d]
                    if (nx in 0 until gridSize && ny in 0 until gridSize) {
                        val nIdx = ny * gridSize + nx
                        if (open[nIdx] && !connected[nIdx]) {
                            connected[nIdx] = true
                            queue.add(nIdx)
                        }
                    }
                }
            }

            // Render
            val cellW = w.toFloat() / gridSize
            val cellH = h.toFloat() / gridSize
            val pixels = IntArray(w * h)
            val paletteColors = palette.colorInts()

            for (py in 0 until h) {
                val gy = (py / cellH).toInt().coerceAtMost(gridSize - 1)
                for (px in 0 until w) {
                    val gx = (px / cellW).toInt().coerceAtMost(gridSize - 1)
                    val idx = gy * gridSize + gx

                    pixels[py * w + px] = if (gy > revealedRows) {
                        // Not yet revealed
                        Color.DKGRAY
                    } else if (connected[idx]) {
                        // Connected cluster
                        val t = gy.toFloat() / gridSize
                        palette.lerpColor(t)
                    } else if (open[idx]) {
                        // Open but not connected
                        val base = paletteColors[paletteColors.size - 1]
                        Color.argb(80, Color.red(base), Color.green(base), Color.blue(base))
                    } else {
                        // Blocked cell
                        Color.BLACK
                    }
                }
            }

            bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
            canvas.drawBitmap(bitmap, 0f, 0f, null)
        } else {
            // Bond percolation
            // Horizontal bonds: gridSize * (gridSize-1) for each row
            // Vertical bonds: (gridSize-1) * gridSize for each column
            val hBond = Array(gridSize) { BooleanArray(gridSize - 1) }
            val vBond = Array(gridSize - 1) { BooleanArray(gridSize) }

            for (y in 0 until gridSize) {
                for (x in 0 until gridSize - 1) {
                    hBond[y][x] = rng.random() < probability
                }
            }
            for (y in 0 until gridSize - 1) {
                for (x in 0 until gridSize) {
                    vBond[y][x] = rng.random() < probability
                }
            }

            // Flood fill from top row through bonds
            val connected = BooleanArray(totalCells)
            val queue = ArrayDeque<Int>()

            for (x in 0 until gridSize) {
                connected[x] = true
                queue.add(x)
            }

            while (queue.isNotEmpty()) {
                val idx = queue.removeFirst()
                val cx = idx % gridSize
                val cy = idx / gridSize

                // Right
                if (cx < gridSize - 1 && hBond[cy][cx]) {
                    val nIdx = cy * gridSize + cx + 1
                    if (!connected[nIdx]) { connected[nIdx] = true; queue.add(nIdx) }
                }
                // Left
                if (cx > 0 && hBond[cy][cx - 1]) {
                    val nIdx = cy * gridSize + cx - 1
                    if (!connected[nIdx]) { connected[nIdx] = true; queue.add(nIdx) }
                }
                // Down
                if (cy < gridSize - 1 && vBond[cy][cx]) {
                    val nIdx = (cy + 1) * gridSize + cx
                    if (!connected[nIdx]) { connected[nIdx] = true; queue.add(nIdx) }
                }
                // Up
                if (cy > 0 && vBond[cy - 1][cx]) {
                    val nIdx = (cy - 1) * gridSize + cx
                    if (!connected[nIdx]) { connected[nIdx] = true; queue.add(nIdx) }
                }
            }

            // Render
            val cellW = w.toFloat() / gridSize
            val cellH = h.toFloat() / gridSize
            val pixels = IntArray(w * h)

            for (py in 0 until h) {
                val gy = (py / cellH).toInt().coerceAtMost(gridSize - 1)
                for (px in 0 until w) {
                    val gx = (px / cellW).toInt().coerceAtMost(gridSize - 1)
                    val idx = gy * gridSize + gx

                    pixels[py * w + px] = if (gy > revealedRows) {
                        Color.DKGRAY
                    } else if (connected[idx]) {
                        val t = gy.toFloat() / gridSize
                        palette.lerpColor(t)
                    } else {
                        Color.BLACK
                    }
                }
            }

            bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
            canvas.drawBitmap(bitmap, 0f, 0f, null)
        }
    }
}
