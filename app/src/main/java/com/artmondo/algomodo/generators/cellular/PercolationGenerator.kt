package com.artmondo.algomodo.generators.cellular

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import com.artmondo.algomodo.core.rng.SeededRNG
import com.artmondo.algomodo.core.rng.SimplexNoise
import com.artmondo.algomodo.data.palettes.Palette
import com.artmondo.algomodo.generators.Generator
import com.artmondo.algomodo.generators.ParamGroup
import com.artmondo.algomodo.generators.Parameter
import com.artmondo.algomodo.generators.Quality
import kotlin.math.ln
import kotlin.math.sin

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
        Parameter.NumberParam("Occupancy p", "occupancyP", ParamGroup.COMPOSITION, "Open probability / swept fraction — critical threshold p_c ≈ 0.593 for square lattice; invasion mode opens exactly p*N cells", 0.05f, 1f, 0.01f, 0.593f),
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

    // ---- Caches: reuse allocations across frames ----
    private class CellValueCache(
        val gridSize: Int,
        val seed: Int,
        val noiseMix: Float,
        val noiseScale: Float,
        val cellValue: FloatArray
    )

    @Volatile private var cachedCellValue: CellValueCache? = null
    @Volatile private var reuseClusterLabel: IntArray? = null
    @Volatile private var reusePixels: IntArray? = null
    @Volatile private var reuseGridColors: IntArray? = null
    @Volatile private var reuseBfsQueue: IntArray? = null
    @Volatile private var reuseOpen: BooleanArray? = null

    override fun renderCanvas(
        canvas: Canvas,
        bitmap: Bitmap,
        params: Map<String, Any>,
        seed: Int,
        palette: Palette,
        quality: Quality,
        time: Float
    ) {
        val baseP = (params["occupancyP"] as? Number)?.toFloat() ?: 0.593f
        val gridSize = (params["gridSize"] as? Number)?.toInt() ?: 128
        val mode = params["percolationMode"] as? String ?: "site"
        val invasionSeedCount = (params["invasionSeeds"] as? Number)?.toInt() ?: 4
        val noiseMix = (params["noiseMix"] as? Number)?.toFloat() ?: 0.3f
        val noiseScale = (params["noiseScale"] as? Number)?.toFloat() ?: 6f
        val showSpanning = (params["showSpanning"] as? String ?: "on") == "on"
        val sweepSpeed = (params["sweepSpeed"] as? Number)?.toFloat() ?: 0.5f
        val sweepAmp = (params["sweepAmp"] as? Number)?.toFloat() ?: 0.2f
        val colorMode = params["colorMode"] as? String ?: "cluster-size"

        val probability = (baseP + sweepAmp * sin(time * sweepSpeed * 2f * Math.PI.toFloat()))
            .coerceIn(0.01f, 1f)

        val w = bitmap.width
        val h = bitmap.height
        val totalCells = gridSize * gridSize

        // ---- Cache cellValue array (depends only on seed, gridSize, noiseMix, noiseScale) ----
        val cvc = cachedCellValue
        val cellValue: FloatArray
        if (cvc != null && cvc.gridSize == gridSize && cvc.seed == seed &&
            cvc.noiseMix == noiseMix && cvc.noiseScale == noiseScale
        ) {
            cellValue = cvc.cellValue
        } else {
            val rng = SeededRNG(seed)
            val noise = SimplexNoise(seed)
            cellValue = FloatArray(totalCells)
            for (cy in 0 until gridSize) {
                for (cx in 0 until gridSize) {
                    val idx = cy * gridSize + cx
                    val randVal = rng.random()
                    val nx = cx.toFloat() / gridSize * noiseScale
                    val ny = cy.toFloat() / gridSize * noiseScale
                    val noiseVal = (noise.noise2D(nx, ny) + 1f) * 0.5f
                    cellValue[idx] = randVal * (1f - noiseMix) + noiseVal * noiseMix
                }
            }
            cachedCellValue = CellValueCache(gridSize, seed, noiseMix, noiseScale, cellValue)
        }

        // ---- Reusable cluster label array ----
        val rcl = reuseClusterLabel
        val clusterLabel: IntArray
        if (rcl != null && rcl.size >= totalCells) {
            clusterLabel = rcl
            java.util.Arrays.fill(clusterLabel, 0, totalCells, -1)
        } else {
            clusterLabel = IntArray(totalCells) { -1 }
            reuseClusterLabel = clusterLabel
        }

        var nextCluster = 0
        val clusterSizes: IntArray
        val isSpanning: BooleanArray

        if (mode == "site") {
            // ---- Site percolation with IntArray BFS queue ----
            val ro = reuseOpen
            val open: BooleanArray
            if (ro != null && ro.size >= totalCells) {
                open = ro
            } else {
                open = BooleanArray(totalCells)
                reuseOpen = open
            }
            for (i in 0 until totalCells) {
                open[i] = cellValue[i] < probability
            }

            // IntArray-based BFS queue (faster than ArrayDeque)
            val rbq = reuseBfsQueue
            val queue: IntArray
            if (rbq != null && rbq.size >= totalCells) {
                queue = rbq
            } else {
                queue = IntArray(totalCells)
                reuseBfsQueue = queue
            }

            for (startIdx in 0 until totalCells) {
                if (open[startIdx] && clusterLabel[startIdx] == -1) {
                    val clusterId = nextCluster++
                    clusterLabel[startIdx] = clusterId
                    var qHead = 0
                    var qTail = 0
                    queue[qTail++] = startIdx

                    while (qHead < qTail) {
                        val idx = queue[qHead++]
                        val cx = idx % gridSize
                        val cy = idx / gridSize

                        // Up
                        if (cy > 0) {
                            val nIdx = idx - gridSize
                            if (open[nIdx] && clusterLabel[nIdx] == -1) {
                                clusterLabel[nIdx] = clusterId
                                queue[qTail++] = nIdx
                            }
                        }
                        // Right
                        if (cx < gridSize - 1) {
                            val nIdx = idx + 1
                            if (open[nIdx] && clusterLabel[nIdx] == -1) {
                                clusterLabel[nIdx] = clusterId
                                queue[qTail++] = nIdx
                            }
                        }
                        // Down
                        if (cy < gridSize - 1) {
                            val nIdx = idx + gridSize
                            if (open[nIdx] && clusterLabel[nIdx] == -1) {
                                clusterLabel[nIdx] = clusterId
                                queue[qTail++] = nIdx
                            }
                        }
                        // Left
                        if (cx > 0) {
                            val nIdx = idx - 1
                            if (open[nIdx] && clusterLabel[nIdx] == -1) {
                                clusterLabel[nIdx] = clusterId
                                queue[qTail++] = nIdx
                            }
                        }
                    }
                }
            }

            clusterSizes = IntArray(nextCluster)
            for (idx in 0 until totalCells) {
                val c = clusterLabel[idx]
                if (c >= 0) clusterSizes[c]++
            }

            val touchesTop = BooleanArray(nextCluster)
            val touchesBottom = BooleanArray(nextCluster)
            for (x in 0 until gridSize) {
                val topC = clusterLabel[x]
                if (topC >= 0) touchesTop[topC] = true
                val bottomC = clusterLabel[(gridSize - 1) * gridSize + x]
                if (bottomC >= 0) touchesBottom[bottomC] = true
            }
            isSpanning = BooleanArray(nextCluster) { touchesTop[it] && touchesBottom[it] }
        } else {
            // ---- Invasion percolation ----
            val cellsToOpen = (probability * totalCells).toInt().coerceIn(1, totalCells)
            val seedRng = SeededRNG(seed + 7919)
            val seedCount = invasionSeedCount.coerceIn(1, 12)
            val opened = BooleanArray(totalCells)

            val frontier = java.util.PriorityQueue<Long>(totalCells / 4 + 16,
                compareBy { Float.fromBits((it ushr 32).toInt()) })

            for (i in 0 until seedCount) {
                val sx = seedRng.integer(0, gridSize - 1)
                val sy = seedRng.integer(0, gridSize - 1)
                val seedIdx = sy * gridSize + sx
                if (!opened[seedIdx]) {
                    opened[seedIdx] = true
                    clusterLabel[seedIdx] = nextCluster++
                    val scx = seedIdx % gridSize
                    val scy = seedIdx / gridSize
                    if (scy > 0) { val nIdx = seedIdx - gridSize; if (!opened[nIdx]) frontier.add(encode(cellValue[nIdx], nIdx)) }
                    if (scx < gridSize - 1) { val nIdx = seedIdx + 1; if (!opened[nIdx]) frontier.add(encode(cellValue[nIdx], nIdx)) }
                    if (scy < gridSize - 1) { val nIdx = seedIdx + gridSize; if (!opened[nIdx]) frontier.add(encode(cellValue[nIdx], nIdx)) }
                    if (scx > 0) { val nIdx = seedIdx - 1; if (!opened[nIdx]) frontier.add(encode(cellValue[nIdx], nIdx)) }
                }
            }

            var openedCount = nextCluster
            while (openedCount < cellsToOpen && frontier.isNotEmpty()) {
                val packed = frontier.poll() ?: break
                val idx = (packed and 0xFFFFFFFFL).toInt()
                if (opened[idx]) continue

                opened[idx] = true
                openedCount++

                val cx = idx % gridSize
                val cy = idx / gridSize
                // Assign to cluster of an opened neighbor
                if (cy > 0) { val nIdx = idx - gridSize; if (opened[nIdx] && clusterLabel[nIdx] >= 0 && clusterLabel[idx] == -1) clusterLabel[idx] = clusterLabel[nIdx] }
                if (cx < gridSize - 1) { val nIdx = idx + 1; if (opened[nIdx] && clusterLabel[nIdx] >= 0 && clusterLabel[idx] == -1) clusterLabel[idx] = clusterLabel[nIdx] }
                if (cy < gridSize - 1) { val nIdx = idx + gridSize; if (opened[nIdx] && clusterLabel[nIdx] >= 0 && clusterLabel[idx] == -1) clusterLabel[idx] = clusterLabel[nIdx] }
                if (cx > 0) { val nIdx = idx - 1; if (opened[nIdx] && clusterLabel[nIdx] >= 0 && clusterLabel[idx] == -1) clusterLabel[idx] = clusterLabel[nIdx] }
                if (clusterLabel[idx] == -1) clusterLabel[idx] = nextCluster++

                // Add unopened neighbors to frontier
                if (cy > 0) { val nIdx = idx - gridSize; if (!opened[nIdx]) frontier.add(encode(cellValue[nIdx], nIdx)) }
                if (cx < gridSize - 1) { val nIdx = idx + 1; if (!opened[nIdx]) frontier.add(encode(cellValue[nIdx], nIdx)) }
                if (cy < gridSize - 1) { val nIdx = idx + gridSize; if (!opened[nIdx]) frontier.add(encode(cellValue[nIdx], nIdx)) }
                if (cx > 0) { val nIdx = idx - 1; if (!opened[nIdx]) frontier.add(encode(cellValue[nIdx], nIdx)) }
            }

            val clusterCount = nextCluster.coerceAtLeast(1)
            clusterSizes = IntArray(clusterCount)
            for (idx in 0 until totalCells) {
                val c = clusterLabel[idx]
                if (c >= 0) clusterSizes[c]++
            }

            val touchesTop = BooleanArray(clusterCount)
            val touchesBottom = BooleanArray(clusterCount)
            for (x in 0 until gridSize) {
                val topC = clusterLabel[x]
                if (topC >= 0) touchesTop[topC] = true
                val bottomC = clusterLabel[(gridSize - 1) * gridSize + x]
                if (bottomC >= 0) touchesBottom[bottomC] = true
            }
            isSpanning = BooleanArray(clusterCount) { touchesTop[it] && touchesBottom[it] }
        }

        // ---- Phase 1: Compute color per grid cell ----
        val maxSize = clusterSizes.maxOrNull()?.coerceAtLeast(1) ?: 1
        val logMax = ln(maxSize.toFloat() + 1f)
        val paletteColors = palette.colorInts()
        val numPaletteColors = paletteColors.size

        val gridColors: IntArray
        val rgc = reuseGridColors
        if (rgc != null && rgc.size >= totalCells) {
            gridColors = rgc
        } else {
            gridColors = IntArray(totalCells)
            reuseGridColors = gridColors
        }

        // Pre-compute palette LUT for cluster-size mode (avoids per-cell lerpColor)
        val palLutSize = 256
        val palLutMax = palLutSize - 1
        val palLut = if (colorMode == "cluster-size") {
            IntArray(palLutSize) { palette.lerpColor(it.toFloat() / palLutMax) }
        } else null

        // Pre-compute per-cluster colors to avoid repeated work for large clusters
        val clusterColor = IntArray(clusterSizes.size)
        val clusterSpanColor = if (showSpanning) IntArray(clusterSizes.size) else null
        for (c in clusterSizes.indices) {
            val base = when (colorMode) {
                "cluster-size" -> {
                    val t = ln(clusterSizes[c].toFloat() + 1f) / logMax
                    palLut!![(t * palLutMax).toInt().coerceIn(0, palLutMax)]
                }
                "cluster-id" -> paletteColors[c % numPaletteColors]
                else -> paletteColors[0]
            }
            clusterColor[c] = base
            if (showSpanning && isSpanning[c]) {
                clusterSpanColor!![c] = Color.rgb(
                    ((Color.red(base) + 255) / 2).coerceAtMost(255),
                    ((Color.green(base) + 255) / 2).coerceAtMost(255),
                    ((Color.blue(base) + 255) / 2).coerceAtMost(255)
                )
            }
        }

        for (i in 0 until totalCells) {
            val cluster = clusterLabel[i]
            gridColors[i] = if (cluster < 0) {
                Color.BLACK
            } else if (showSpanning && isSpanning[cluster]) {
                clusterSpanColor!![cluster]
            } else {
                clusterColor[cluster]
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

        val xMap = IntArray(w) { (it * gridSize / w).coerceAtMost(gridSize - 1) }
        for (py in 0 until h) {
            val gy = (py * gridSize / h).coerceAtMost(gridSize - 1)
            val gridRow = gy * gridSize
            val pixRow = py * w
            for (px in 0 until w) {
                pixels[pixRow + px] = gridColors[gridRow + xMap[px]]
            }
        }

        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
        canvas.drawBitmap(bitmap, 0f, 0f, null)
    }

    private fun encode(value: Float, idx: Int): Long {
        return (value.toBits().toLong() shl 32) or (idx.toLong() and 0xFFFFFFFFL)
    }

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float {
        val gridSize = (params["gridSize"] as? Number)?.toInt() ?: 128
        return (gridSize * gridSize / 16384f).coerceIn(0.1f, 1f)
    }
}
