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
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sin

class PercolationGenerator : Generator {

    override val id = "cellular-percolation"
    override val family = "cellular"
    override val styleName = "Percolation"
    override val definition =
        "Site, bond, directed, and invasion percolation on a square lattice \u2014 " +
        "noise-correlated values create organic cluster shapes, bond mode creates mosaic patterns " +
        "at p_c = 0.5, directed mode cascades waterfall networks, and invasion mode produces fractal drainage networks."
    override val algorithmNotes =
        "Site: each cell independently open with probability p (p_c \u2248 0.593). " +
        "Bond: all sites present, bonds between adjacent pairs open with probability p (p_c = 0.5, Kesten 1980). " +
        "Directed: cascade from top row \u2014 each row activates only if open AND has an active parent above (with horizontal wrap). " +
        "Invasion: priority-queue BFS from seeds always opening lowest-resistance neighbor. " +
        "Animations: sweep (sinusoidal p), growth (linear p ramp), morph (traveling wave through site values at fixed p)."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema = listOf(
        Parameter.NumberParam("Grid Size", "gridSize", ParamGroup.COMPOSITION, null, 32f, 256f, 16f, 128f),
        Parameter.SelectParam("Mode", "percolationMode", ParamGroup.COMPOSITION,
            "site: cells open with probability p | bond: all sites present, bonds open with probability p (p_c=0.5) | directed: top-down cascade (waterfall) | invasion: fractal BFS flooding from seeds",
            listOf("site", "bond", "directed", "invasion"), "site"),
        Parameter.NumberParam("Occupancy p", "occupancyP", ParamGroup.COMPOSITION,
            "Open probability \u2014 site p_c \u2248 0.593, bond p_c = 0.5 (Kesten 1980); invasion opens exactly p\u00b7N cells",
            0.05f, 1f, 0.01f, 0.593f),
        Parameter.NumberParam("Invasion Seeds", "invasionSeeds", ParamGroup.COMPOSITION,
            "Number of seed points from which invasion floods outward (invasion mode only)",
            1f, 12f, 1f, 4f),
        Parameter.NumberParam("Noise Mix", "noiseMix", ParamGroup.TEXTURE,
            "0 = purely random site values \u2192 standard fractal percolation | 1 = fully correlated noise \u2192 geologic / organic blob shapes",
            0f, 1f, 0.05f, 0.3f),
        Parameter.NumberParam("Noise Scale", "noiseScale", ParamGroup.TEXTURE,
            "Spatial frequency of correlated noise \u2014 lower = large geologic blobs, higher = fine-grained texture",
            1f, 20f, 1f, 6f),
        Parameter.SelectParam("Show Spanning", "showSpanning", ParamGroup.COLOR,
            "Highlight in white the cluster that bridges top edge to bottom edge \u2014 marks the percolating backbone",
            listOf("on", "off"), "on"),
        Parameter.NumberParam("Sweep Speed", "sweepSpeed", ParamGroup.FLOW_MOTION,
            "How fast p oscillates across the critical threshold in animation mode",
            0.1f, 3.0f, 0.1f, 0.5f),
        Parameter.NumberParam("Sweep Amplitude", "sweepAmp", ParamGroup.FLOW_MOTION,
            "How far p swings above and below the base value in sweep animation",
            0.05f, 0.4f, 0.05f, 0.2f),
        Parameter.SelectParam("Animation", "animPattern", ParamGroup.FLOW_MOTION,
            "sweep: sinusoidal p oscillation | growth: p grows linearly 0 \u2192 target then loops | morph: diagonal traveling wave through the site-value landscape at fixed p \u2014 clusters dissolve and re-form fluidly",
            listOf("sweep", "growth", "morph"), "sweep"),
        Parameter.SelectParam("Color Mode", "colorMode", ParamGroup.COLOR,
            "cluster-size: log-scaled palette by area | cluster-id: distinct color per cluster | depth: BFS depth from cluster root | resistance: site values heatmap | boundary: fractal edges | monochrome: flat",
            listOf("cluster-size", "cluster-id", "depth", "resistance", "boundary", "monochrome"), "cluster-size")
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
        "animPattern" to "sweep",
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
    @Volatile private var reuseMorphedValues: FloatArray? = null

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
        val animPattern = params["animPattern"] as? String ?: "sweep"
        val colorMode = params["colorMode"] as? String ?: "cluster-size"

        val twoPi = 2f * Math.PI.toFloat()
        val phase = time * sweepSpeed * twoPi
        val probability = (when {
            time == 0f -> baseP
            animPattern == "growth" -> {
                val tNorm = (phase % twoPi) / twoPi
                tNorm * baseP
            }
            animPattern == "morph" -> baseP   // morph keeps p fixed; landscape drifts instead
            else -> baseP + sweepAmp * sin(phase)
        }).coerceIn(0.01f, 1f)

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

        // ---- Morph animation: diagonal traveling wave through site values ----
        // Keeps p fixed but shifts the underlying landscape so different cells
        // cross the threshold each frame, dissolving and re-forming clusters.
        val workingValues: FloatArray = if (animPattern == "morph" && time > 0f) {
            val rmv = reuseMorphedValues
            val wv = if (rmv != null && rmv.size >= totalCells) rmv
                    else FloatArray(totalCells).also { reuseMorphedValues = it }
            val morphAmp = sweepAmp * 0.5f
            for (cy in 0 until gridSize) {
                val sp = (cy * 0.18f)            // diagonal wave: y component
                val rowStart = cy * gridSize
                for (cx in 0 until gridSize) {
                    val spatial = sp + cx * 0.18f
                    val delta = morphAmp * sin(phase + spatial)
                    val v = cellValue[rowStart + cx] + delta
                    wv[rowStart + cx] = if (v < 0f) 0f else if (v > 1f) 1f else v
                }
            }
            wv
        } else cellValue

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

        // ---- Open array (which sites are present) ----
        val ro = reuseOpen
        val open: BooleanArray
        if (ro != null && ro.size >= totalCells) {
            open = ro
            java.util.Arrays.fill(open, 0, totalCells, false)
        } else {
            open = BooleanArray(totalCells)
            reuseOpen = open
        }

        // ---- BFS queue scratch ----
        val rbq = reuseBfsQueue
        val queue: IntArray
        if (rbq != null && rbq.size >= totalCells) {
            queue = rbq
        } else {
            queue = IntArray(totalCells)
            reuseBfsQueue = queue
        }

        var nextCluster = 0

        when (mode) {
            "bond" -> {
                // All sites present; cluster BFS traverses only open bonds.
                java.util.Arrays.fill(open, 0, totalCells, true)
                nextCluster = clusterByBondBFS(open, workingValues, gridSize, probability, clusterLabel, queue)
            }
            "directed" -> {
                runDirectedPercolation(workingValues, gridSize, probability, seed, open)
                nextCluster = clusterByOccupancyBFS(open, gridSize, clusterLabel, queue)
            }
            "invasion" -> {
                val cellsToOpen = (probability * totalCells).toInt().coerceIn(1, totalCells)
                nextCluster = runInvasion(
                    workingValues, gridSize, invasionSeedCount.coerceIn(1, 12),
                    seed, cellsToOpen, open, clusterLabel
                )
            }
            else -> {
                // site
                for (i in 0 until totalCells) open[i] = workingValues[i] < probability
                nextCluster = clusterByOccupancyBFS(open, gridSize, clusterLabel, queue)
            }
        }

        // Spanning detection (uses pre-mask clusters so cascade reveal stays consistent)
        val clusterCount = nextCluster.coerceAtLeast(1)
        val clusterSizes = IntArray(clusterCount)
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
        val isSpanning = BooleanArray(clusterCount) { touchesTop[it] && touchesBottom[it] }

        // Compute depth lazily (toroidal BFS within each cluster from first cell)
        var depths: IntArray? = null
        var maxDepth = 1
        if (colorMode == "depth") {
            val (d, md) = computeDepths(open, clusterLabel, gridSize, queue)
            depths = d
            maxDepth = md
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

        val palLutSize = 256
        val palLutMax = palLutSize - 1
        val palLut = if (colorMode == "cluster-size" || colorMode == "depth" || colorMode == "resistance") {
            IntArray(palLutSize) { palette.lerpColor(it.toFloat() / palLutMax) }
        } else null

        // Pre-compute per-cluster colors (used by cluster-size / cluster-id)
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
                // Use pure white as the spanning marker (matches web reference).
                clusterSpanColor!![c] = Color.WHITE
            }
        }

        val emptyColor = Color.rgb(10, 10, 10)
        val borderHi = paletteColors[paletteColors.size - 1]
        val borderLo = paletteColors[0]

        for (i in 0 until totalCells) {
            val cluster = clusterLabel[i]
            gridColors[i] = when (colorMode) {
                "resistance" -> {
                    val t = workingValues[i].coerceIn(0f, 1f)
                    val base = palLut!![(t * palLutMax).toInt().coerceIn(0, palLutMax)]
                    if (open[i]) base else dimColor(base, 0.15f)
                }
                else -> {
                    if (!open[i] || cluster < 0) {
                        emptyColor
                    } else if (showSpanning && isSpanning[cluster]) {
                        clusterSpanColor!![cluster]
                    } else {
                        when (colorMode) {
                            "depth" -> {
                                val d = depths?.get(i) ?: 0
                                val t = if (maxDepth > 0) (d.toFloat() / maxDepth).coerceIn(0f, 1f) else 0f
                                palLut!![(t * palLutMax).toInt().coerceIn(0, palLutMax)]
                            }
                            "boundary" -> {
                                if (isBoundaryCell(i, gridSize, open, clusterLabel)) borderHi else borderLo
                            }
                            "monochrome" -> paletteColors[paletteColors.size - 1]
                            else -> clusterColor[cluster]   // cluster-size / cluster-id
                        }
                    }
                }
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

    // ---------------------------------------------------------------------
    // BFS clustering by occupancy (4-neighbor, no wrap) — site / directed /
    // invasion modes all reuse this once `open[]` is populated. Returns
    // number of clusters discovered.
    // ---------------------------------------------------------------------
    private fun clusterByOccupancyBFS(
        open: BooleanArray, gridSize: Int,
        clusterLabel: IntArray, queue: IntArray
    ): Int {
        val totalCells = gridSize * gridSize
        var nextCluster = 0
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
                    if (cy > 0) {
                        val nIdx = idx - gridSize
                        if (open[nIdx] && clusterLabel[nIdx] == -1) {
                            clusterLabel[nIdx] = clusterId; queue[qTail++] = nIdx
                        }
                    }
                    if (cx < gridSize - 1) {
                        val nIdx = idx + 1
                        if (open[nIdx] && clusterLabel[nIdx] == -1) {
                            clusterLabel[nIdx] = clusterId; queue[qTail++] = nIdx
                        }
                    }
                    if (cy < gridSize - 1) {
                        val nIdx = idx + gridSize
                        if (open[nIdx] && clusterLabel[nIdx] == -1) {
                            clusterLabel[nIdx] = clusterId; queue[qTail++] = nIdx
                        }
                    }
                    if (cx > 0) {
                        val nIdx = idx - 1
                        if (open[nIdx] && clusterLabel[nIdx] == -1) {
                            clusterLabel[nIdx] = clusterId; queue[qTail++] = nIdx
                        }
                    }
                }
            }
        }
        return nextCluster
    }

    // ---------------------------------------------------------------------
    // BFS clustering by bond openness (4-neighbor, no wrap). All sites are
    // present; bonds open with probability p, using decorrelated hashes of
    // adjacent cellValues so the bond pattern is stable per (seed, grid).
    // ---------------------------------------------------------------------
    private fun clusterByBondBFS(
        open: BooleanArray, cellValue: FloatArray, gridSize: Int, p: Float,
        clusterLabel: IntArray, queue: IntArray
    ): Int {
        val totalCells = gridSize * gridSize
        var nextCluster = 0
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
                    // Up bond: vertical hash (up*11.7 + down*7.3)
                    if (cy > 0) {
                        val nIdx = idx - gridSize
                        if (clusterLabel[nIdx] == -1 && verticalBondOpen(cellValue, nIdx, idx, p)) {
                            clusterLabel[nIdx] = clusterId; queue[qTail++] = nIdx
                        }
                    }
                    // Right bond: horizontal hash (left*7.3 + right*11.7)
                    if (cx < gridSize - 1) {
                        val nIdx = idx + 1
                        if (clusterLabel[nIdx] == -1 && horizontalBondOpen(cellValue, idx, nIdx, p)) {
                            clusterLabel[nIdx] = clusterId; queue[qTail++] = nIdx
                        }
                    }
                    // Down bond
                    if (cy < gridSize - 1) {
                        val nIdx = idx + gridSize
                        if (clusterLabel[nIdx] == -1 && verticalBondOpen(cellValue, idx, nIdx, p)) {
                            clusterLabel[nIdx] = clusterId; queue[qTail++] = nIdx
                        }
                    }
                    // Left bond
                    if (cx > 0) {
                        val nIdx = idx - 1
                        if (clusterLabel[nIdx] == -1 && horizontalBondOpen(cellValue, nIdx, idx, p)) {
                            clusterLabel[nIdx] = clusterId; queue[qTail++] = nIdx
                        }
                    }
                }
            }
        }
        return nextCluster
    }

    private fun horizontalBondOpen(cv: FloatArray, leftIdx: Int, rightIdx: Int, p: Float): Boolean {
        val v = cv[leftIdx] * 7.3f + cv[rightIdx] * 11.7f
        return (v - floor(v)) < p
    }

    private fun verticalBondOpen(cv: FloatArray, upIdx: Int, downIdx: Int, p: Float): Boolean {
        val v = cv[upIdx] * 11.7f + cv[downIdx] * 7.3f
        return (v - floor(v)) < p
    }

    // ---------------------------------------------------------------------
    // Directed (top-down) percolation. Row 0: independently open. Row y > 0:
    // open only if cellValue < p AND at least one of three parents above is
    // open (with horizontal wrap so edge cells get full 3 parents). Re-seed
    // when cascade is dying to keep waterfall alive at any p.
    // ---------------------------------------------------------------------
    private fun runDirectedPercolation(
        cellValue: FloatArray, gridSize: Int, p: Float, seed: Int, open: BooleanArray
    ) {
        java.util.Arrays.fill(open, 0, gridSize * gridSize, false)
        val rng = SeededRNG(seed xor 0xD1EC)
        // Row 0
        for (x in 0 until gridSize) {
            if (cellValue[x] < p) open[x] = true
        }
        val minActive = kotlin.math.max(3, (gridSize * 0.04f).toInt())
        for (y in 1 until gridSize) {
            var activeCount = 0
            val prev = (y - 1) * gridSize
            val row = y * gridSize
            for (x in 0 until gridSize) {
                if (cellValue[row + x] >= p) continue
                val xLeft = (x - 1 + gridSize) % gridSize
                val xRight = (x + 1) % gridSize
                if (open[prev + x] || open[prev + xLeft] || open[prev + xRight]) {
                    open[row + x] = true
                    activeCount++
                }
            }
            if (activeCount < minActive) {
                var added = activeCount
                while (added < minActive) {
                    val x = (rng.random() * gridSize).toInt().coerceIn(0, gridSize - 1)
                    if (!open[row + x]) {
                        open[row + x] = true
                        added++
                    } else {
                        // already open; skip without infinite loop
                        added++
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------------
    // Invasion percolation: min-heap BFS from `numSeeds` seeds, always opens
    // the lowest-resistance frontier site. Assigns cluster ids during growth.
    // ---------------------------------------------------------------------
    private fun runInvasion(
        cellValue: FloatArray, gridSize: Int, numSeeds: Int, seed: Int,
        cellsToOpen: Int, open: BooleanArray, clusterLabel: IntArray
    ): Int {
        val totalCells = gridSize * gridSize
        java.util.Arrays.fill(open, 0, totalCells, false)
        val seedRng = SeededRNG(seed + 7919)
        val frontier = java.util.PriorityQueue<Long>(totalCells / 4 + 16,
            compareBy { Float.fromBits((it ushr 32).toInt()) })

        var nextCluster = 0
        for (i in 0 until numSeeds) {
            val sx = seedRng.integer(0, gridSize - 1)
            val sy = seedRng.integer(0, gridSize - 1)
            val seedIdx = sy * gridSize + sx
            if (!open[seedIdx]) {
                open[seedIdx] = true
                clusterLabel[seedIdx] = nextCluster++
                pushNeighbors(seedIdx, gridSize, cellValue, open, frontier)
            }
        }

        var openedCount = nextCluster
        while (openedCount < cellsToOpen && frontier.isNotEmpty()) {
            val packed = frontier.poll() ?: break
            val idx = (packed and 0xFFFFFFFFL).toInt()
            if (open[idx]) continue
            open[idx] = true
            openedCount++

            val cx = idx % gridSize
            val cy = idx / gridSize
            // Inherit cluster from an opened neighbor
            if (cy > 0) { val n = idx - gridSize; if (open[n] && clusterLabel[n] >= 0 && clusterLabel[idx] == -1) clusterLabel[idx] = clusterLabel[n] }
            if (cx < gridSize - 1) { val n = idx + 1; if (open[n] && clusterLabel[n] >= 0 && clusterLabel[idx] == -1) clusterLabel[idx] = clusterLabel[n] }
            if (cy < gridSize - 1) { val n = idx + gridSize; if (open[n] && clusterLabel[n] >= 0 && clusterLabel[idx] == -1) clusterLabel[idx] = clusterLabel[n] }
            if (cx > 0) { val n = idx - 1; if (open[n] && clusterLabel[n] >= 0 && clusterLabel[idx] == -1) clusterLabel[idx] = clusterLabel[n] }
            if (clusterLabel[idx] == -1) clusterLabel[idx] = nextCluster++

            pushNeighbors(idx, gridSize, cellValue, open, frontier)
        }
        return nextCluster
    }

    private fun pushNeighbors(
        idx: Int, gridSize: Int, cellValue: FloatArray,
        open: BooleanArray, frontier: java.util.PriorityQueue<Long>
    ) {
        val cx = idx % gridSize
        val cy = idx / gridSize
        if (cy > 0) { val n = idx - gridSize; if (!open[n]) frontier.add(encode(cellValue[n], n)) }
        if (cx < gridSize - 1) { val n = idx + 1; if (!open[n]) frontier.add(encode(cellValue[n], n)) }
        if (cy < gridSize - 1) { val n = idx + gridSize; if (!open[n]) frontier.add(encode(cellValue[n], n)) }
        if (cx > 0) { val n = idx - 1; if (!open[n]) frontier.add(encode(cellValue[n], n)) }
    }

    // ---------------------------------------------------------------------
    // Depth color mode: BFS depth from the first cell of each cluster using
    // toroidal 4-neighbor wrap (matches web reference). Returns depths array
    // and the maximum depth (clamped to >= 1).
    // ---------------------------------------------------------------------
    private fun computeDepths(
        open: BooleanArray, clusterLabel: IntArray, gridSize: Int, queue: IntArray
    ): Pair<IntArray, Int> {
        val totalCells = gridSize * gridSize
        val depths = IntArray(totalCells) { -1 }
        val seen = HashSet<Int>()
        var head = 0
        var tail = 0
        for (i in 0 until totalCells) {
            if (open[i] && clusterLabel[i] >= 0 && !seen.contains(clusterLabel[i])) {
                seen.add(clusterLabel[i])
                depths[i] = 0
                queue[tail++] = i
            }
        }
        var maxDepth = 0
        while (head < tail) {
            val idx = queue[head++]
            val x = idx % gridSize
            val y = idx / gridSize
            val d = depths[idx] + 1
            val lbl = clusterLabel[idx]
            val nE = y * gridSize + (x + 1) % gridSize
            val nW = y * gridSize + (x - 1 + gridSize) % gridSize
            val nS = ((y + 1) % gridSize) * gridSize + x
            val nN = ((y - 1 + gridSize) % gridSize) * gridSize + x
            if (open[nE] && clusterLabel[nE] == lbl && depths[nE] == -1) {
                depths[nE] = d; if (d > maxDepth) maxDepth = d; queue[tail++] = nE
            }
            if (open[nW] && clusterLabel[nW] == lbl && depths[nW] == -1) {
                depths[nW] = d; if (d > maxDepth) maxDepth = d; queue[tail++] = nW
            }
            if (open[nS] && clusterLabel[nS] == lbl && depths[nS] == -1) {
                depths[nS] = d; if (d > maxDepth) maxDepth = d; queue[tail++] = nS
            }
            if (open[nN] && clusterLabel[nN] == lbl && depths[nN] == -1) {
                depths[nN] = d; if (d > maxDepth) maxDepth = d; queue[tail++] = nN
            }
        }
        return Pair(depths, if (maxDepth == 0) 1 else maxDepth)
    }

    private fun isBoundaryCell(
        idx: Int, gridSize: Int, open: BooleanArray, clusterLabel: IntArray
    ): Boolean {
        val x = idx % gridSize
        val y = idx / gridSize
        val lbl = clusterLabel[idx]
        val nE = y * gridSize + (x + 1) % gridSize
        val nW = y * gridSize + (x - 1 + gridSize) % gridSize
        val nS = ((y + 1) % gridSize) * gridSize + x
        val nN = ((y - 1 + gridSize) % gridSize) * gridSize + x
        return !open[nE] || !open[nW] || !open[nS] || !open[nN] ||
            clusterLabel[nE] != lbl || clusterLabel[nW] != lbl ||
            clusterLabel[nS] != lbl || clusterLabel[nN] != lbl
    }

    private fun dimColor(c: Int, factor: Float): Int {
        val r = (Color.red(c) * factor).toInt().coerceIn(0, 255)
        val g = (Color.green(c) * factor).toInt().coerceIn(0, 255)
        val b = (Color.blue(c) * factor).toInt().coerceIn(0, 255)
        return Color.rgb(r, g, b)
    }

    private fun encode(value: Float, idx: Int): Long {
        return (value.toBits().toLong() shl 32) or (idx.toLong() and 0xFFFFFFFFL)
    }

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float {
        val gridSize = (params["gridSize"] as? Number)?.toInt() ?: 128
        val mode = params["percolationMode"] as? String ?: "site"
        val cm = params["colorMode"] as? String ?: "cluster-size"
        var modeScale = 1f
        if (mode == "invasion") modeScale = 3f
        else if (mode == "bond") modeScale = 1.5f
        if (cm == "depth") modeScale += 0.5f
        return (gridSize * gridSize * modeScale / 16384f).coerceIn(0.1f, 1f)
    }
}
