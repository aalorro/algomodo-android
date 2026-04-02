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

        // Apply sweep: oscillate p around the base value using time
        val probability = (baseP + sweepAmp * sin(time * sweepSpeed * 2f * Math.PI.toFloat()))
            .coerceIn(0.01f, 1f)

        val w = bitmap.width
        val h = bitmap.height
        val totalCells = gridSize * gridSize

        val rng = SeededRNG(seed)
        val noise = SimplexNoise(seed)

        // Generate per-cell random value blended with simplex noise
        // This value is in [0, 1) and is used for both site and invasion modes
        val cellValue = FloatArray(totalCells)
        for (cy in 0 until gridSize) {
            for (cx in 0 until gridSize) {
                val idx = cy * gridSize + cx
                val randVal = rng.random()
                // Simplex noise returns [-1, 1]; remap to [0, 1]
                val nx = cx.toFloat() / gridSize * noiseScale
                val ny = cy.toFloat() / gridSize * noiseScale
                val noiseVal = (noise.noise2D(nx, ny) + 1f) * 0.5f
                // Blend: noiseMix=0 → pure random, noiseMix=1 → pure noise
                cellValue[idx] = randVal * (1f - noiseMix) + noiseVal * noiseMix
            }
        }

        val dx4 = intArrayOf(0, 1, 0, -1)
        val dy4 = intArrayOf(-1, 0, 1, 0)

        // Cluster label array: -1 = not open / not assigned
        val clusterLabel = IntArray(totalCells) { -1 }

        if (mode == "site") {
            // Site percolation: cell is open if its blended value < probability
            val open = BooleanArray(totalCells) { cellValue[it] < probability }

            // Union-Find to label connected clusters
            var nextCluster = 0
            val queue = ArrayDeque<Int>()

            for (startIdx in 0 until totalCells) {
                if (open[startIdx] && clusterLabel[startIdx] == -1) {
                    val clusterId = nextCluster++
                    clusterLabel[startIdx] = clusterId
                    queue.add(startIdx)

                    while (queue.isNotEmpty()) {
                        val idx = queue.removeFirst()
                        val cx = idx % gridSize
                        val cy = idx / gridSize

                        for (d in 0..3) {
                            val nx = cx + dx4[d]
                            val ny = cy + dy4[d]
                            if (nx in 0 until gridSize && ny in 0 until gridSize) {
                                val nIdx = ny * gridSize + nx
                                if (open[nIdx] && clusterLabel[nIdx] == -1) {
                                    clusterLabel[nIdx] = clusterId
                                    queue.add(nIdx)
                                }
                            }
                        }
                    }
                }
            }

            // Compute cluster sizes
            val clusterSizes = IntArray(nextCluster)
            for (idx in 0 until totalCells) {
                val c = clusterLabel[idx]
                if (c >= 0) clusterSizes[c]++
            }

            // Find the spanning cluster: clusters that touch both top and bottom rows
            val touchesTop = BooleanArray(nextCluster)
            val touchesBottom = BooleanArray(nextCluster)
            for (x in 0 until gridSize) {
                val topC = clusterLabel[x]
                if (topC >= 0) touchesTop[topC] = true
                val bottomC = clusterLabel[(gridSize - 1) * gridSize + x]
                if (bottomC >= 0) touchesBottom[bottomC] = true
            }
            val isSpanning = BooleanArray(nextCluster) { touchesTop[it] && touchesBottom[it] }

            // Find max cluster size for log scaling
            val maxSize = clusterSizes.maxOrNull()?.coerceAtLeast(1) ?: 1

            // Render
            renderPixels(
                bitmap, canvas, w, h, gridSize, totalCells,
                clusterLabel, clusterSizes, isSpanning,
                maxSize, showSpanning, colorMode, palette
            )
        } else {
            // Invasion percolation: BFS from seed points, opening cells in order
            // of ascending cellValue (resistance). Opens exactly (probability * totalCells) cells.
            val cellsToOpen = (probability * totalCells).toInt().coerceIn(1, totalCells)

            // Pick invasion seed positions spread across the grid
            val seedRng = SeededRNG(seed + 7919) // separate stream for seed placement
            val seedPositions = mutableListOf<Int>()
            val seedCount = invasionSeedCount.coerceIn(1, 12)
            for (i in 0 until seedCount) {
                val sx = seedRng.integer(0, gridSize - 1)
                val sy = seedRng.integer(0, gridSize - 1)
                seedPositions.add(sy * gridSize + sx)
            }

            // Priority queue: sort candidate cells by cellValue (resistance)
            // Each entry: (cellValue, cellIndex, clusterIndex)
            val opened = BooleanArray(totalCells)
            var nextCluster = 0

            // Use a sorted structure: simple approach with a mutable list sorted on insertion
            // For performance, use a TreeMap-like approach with a priority queue
            val frontier = java.util.PriorityQueue<Long>(totalCells / 4 + 16,
                compareBy { Float.fromBits((it ushr 32).toInt()) })

            // Encode: upper 32 bits = float bits of cellValue, lower 32 bits = cell index
            fun encode(value: Float, idx: Int): Long {
                return (value.toBits().toLong() shl 32) or (idx.toLong() and 0xFFFFFFFFL)
            }
            fun decodeIdx(packed: Long): Int = (packed and 0xFFFFFFFFL).toInt()

            // Initialize each seed as its own cluster
            for (seedIdx in seedPositions) {
                if (!opened[seedIdx]) {
                    opened[seedIdx] = true
                    clusterLabel[seedIdx] = nextCluster++

                    // Add neighbors to frontier
                    val cx = seedIdx % gridSize
                    val cy = seedIdx / gridSize
                    for (d in 0..3) {
                        val nx = cx + dx4[d]
                        val ny = cy + dy4[d]
                        if (nx in 0 until gridSize && ny in 0 until gridSize) {
                            val nIdx = ny * gridSize + nx
                            if (!opened[nIdx]) {
                                frontier.add(encode(cellValue[nIdx], nIdx))
                            }
                        }
                    }
                }
            }

            // Grow: open cells with lowest resistance first
            var openedCount = seedPositions.count { opened[it] } // seeds already opened
            while (openedCount < cellsToOpen && frontier.isNotEmpty()) {
                val packed = frontier.poll() ?: break
                val idx = decodeIdx(packed)
                if (opened[idx]) continue // already opened by another path

                opened[idx] = true
                openedCount++

                // Assign to the cluster of an already-opened neighbor
                val cx = idx % gridSize
                val cy = idx / gridSize
                for (d in 0..3) {
                    val nx = cx + dx4[d]
                    val ny = cy + dy4[d]
                    if (nx in 0 until gridSize && ny in 0 until gridSize) {
                        val nIdx = ny * gridSize + nx
                        if (opened[nIdx] && clusterLabel[nIdx] >= 0 && clusterLabel[idx] == -1) {
                            clusterLabel[idx] = clusterLabel[nIdx]
                        }
                    }
                }
                // If still unlabeled (shouldn't happen normally), assign new cluster
                if (clusterLabel[idx] == -1) {
                    clusterLabel[idx] = nextCluster++
                }

                // Add unopened neighbors to frontier
                for (d in 0..3) {
                    val nx = cx + dx4[d]
                    val ny = cy + dy4[d]
                    if (nx in 0 until gridSize && ny in 0 until gridSize) {
                        val nIdx = ny * gridSize + nx
                        if (!opened[nIdx]) {
                            frontier.add(encode(cellValue[nIdx], nIdx))
                        }
                    }
                }
            }

            // Compute cluster sizes
            val clusterCount = nextCluster.coerceAtLeast(1)
            val clusterSizes = IntArray(clusterCount)
            for (idx in 0 until totalCells) {
                val c = clusterLabel[idx]
                if (c >= 0) clusterSizes[c]++
            }

            // Find spanning clusters
            val touchesTop = BooleanArray(clusterCount)
            val touchesBottom = BooleanArray(clusterCount)
            for (x in 0 until gridSize) {
                val topC = clusterLabel[x]
                if (topC >= 0) touchesTop[topC] = true
                val bottomC = clusterLabel[(gridSize - 1) * gridSize + x]
                if (bottomC >= 0) touchesBottom[bottomC] = true
            }
            val isSpanning = BooleanArray(clusterCount) { touchesTop[it] && touchesBottom[it] }

            val maxSize = clusterSizes.maxOrNull()?.coerceAtLeast(1) ?: 1

            // Render
            renderPixels(
                bitmap, canvas, w, h, gridSize, totalCells,
                clusterLabel, clusterSizes, isSpanning,
                maxSize, showSpanning, colorMode, palette
            )
        }
    }

    /**
     * Shared pixel rendering for both site and invasion modes.
     * Colors each cell based on [colorMode], optionally highlighting spanning clusters.
     */
    private fun renderPixels(
        bitmap: Bitmap,
        canvas: Canvas,
        w: Int,
        h: Int,
        gridSize: Int,
        totalCells: Int,
        clusterLabel: IntArray,
        clusterSizes: IntArray,
        isSpanning: BooleanArray,
        maxSize: Int,
        showSpanning: Boolean,
        colorMode: String,
        palette: Palette
    ) {
        val cellW = w.toFloat() / gridSize
        val cellH = h.toFloat() / gridSize
        val pixels = IntArray(w * h)
        val paletteColors = palette.colorInts()
        val numPaletteColors = paletteColors.size
        val logMax = ln(maxSize.toFloat() + 1f)

        for (py in 0 until h) {
            val gy = (py / cellH).toInt().coerceAtMost(gridSize - 1)
            for (px in 0 until w) {
                val gx = (px / cellW).toInt().coerceAtMost(gridSize - 1)
                val idx = gy * gridSize + gx
                val cluster = clusterLabel[idx]

                if (cluster < 0) {
                    // Blocked / unopened cell
                    pixels[py * w + px] = Color.BLACK
                } else if (showSpanning && isSpanning[cluster]) {
                    // Spanning cluster highlighted: bright white-tinted palette color
                    val baseColor = when (colorMode) {
                        "cluster-size" -> {
                            val t = ln(clusterSizes[cluster].toFloat() + 1f) / logMax
                            palette.lerpColor(t)
                        }
                        "cluster-id" -> paletteColors[cluster % numPaletteColors]
                        else -> paletteColors[0] // monochrome
                    }
                    // Brighten toward white to highlight the spanning cluster
                    val r = ((Color.red(baseColor) + 255) / 2).coerceAtMost(255)
                    val g = ((Color.green(baseColor) + 255) / 2).coerceAtMost(255)
                    val b = ((Color.blue(baseColor) + 255) / 2).coerceAtMost(255)
                    pixels[py * w + px] = Color.rgb(r, g, b)
                } else {
                    // Normal open cell: color by colorMode
                    pixels[py * w + px] = when (colorMode) {
                        "cluster-size" -> {
                            // Log-scaled palette mapping by cluster area
                            val t = ln(clusterSizes[cluster].toFloat() + 1f) / logMax
                            palette.lerpColor(t)
                        }
                        "cluster-id" -> {
                            // Each cluster gets a distinct palette color by index
                            paletteColors[cluster % numPaletteColors]
                        }
                        else -> {
                            // Monochrome: flat single palette color
                            paletteColors[0]
                        }
                    }
                }
            }
        }

        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
        canvas.drawBitmap(bitmap, 0f, 0f, null)
    }
}
