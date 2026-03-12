package com.artmondo.algomodo.generators.graphs

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.artmondo.algomodo.core.rng.SeededRNG
import com.artmondo.algomodo.core.rng.SimplexNoise
import com.artmondo.algomodo.data.palettes.Palette
import com.artmondo.algomodo.generators.Generator
import com.artmondo.algomodo.generators.ParamGroup
import com.artmondo.algomodo.generators.Parameter
import com.artmondo.algomodo.generators.Quality
import com.artmondo.algomodo.rendering.SvgBuilder
import com.artmondo.algomodo.rendering.SvgPath
import kotlin.math.ceil
import kotlin.math.sqrt

/**
 * Steiner tree network generator.
 *
 * Computes an approximate Steiner minimum tree connecting a set of random
 * terminal points with near-minimum total edge length. Uses an iterative
 * Steiner-point insertion heuristic on top of a minimum spanning tree.
 * Supports SVG vector output.
 */
class GraphSteinerNetworksGenerator : Generator {

    override val id = "graph-steiner-networks"
    override val family = "graphs"
    override val styleName = "Steiner Networks"
    override val definition =
        "Approximate Steiner tree connecting random terminal points with near-minimum total network length, producing elegant branching network art."
    override val algorithmNotes =
        "Terminal points are placed with SeededRNG. A minimum spanning tree (Kruskal's) is computed first. " +
        "Then an iterative heuristic inserts candidate Steiner points at triangle Fermat points and re-computes " +
        "the MST, keeping only improvements. The result is rendered as coloured line segments with optional " +
        "terminal markers. Animation slowly drifts terminals via noise, recalculating the tree each frame."
    override val supportsVector = true
    override val supportsAnimation = true

    override val parameterSchema = listOf(
        Parameter.NumberParam("Terminal Points", "terminalCount", ParamGroup.COMPOSITION, null, 3f, 60f, 1f, 12f),
        Parameter.SelectParam("Distribution", "distribution", ParamGroup.COMPOSITION, null, listOf("random", "clustered", "ring", "grid", "fibonacci"), "random"),
        Parameter.NumberParam("Optimization Steps", "steinerIterations", ParamGroup.GEOMETRY, "Steiner point refinement iterations (0 = MST only)", 0f, 200f, 10f, 80f),
        Parameter.BooleanParam("Show MST", "showMST", ParamGroup.GEOMETRY, "Show original MST as reference", true),
        Parameter.BooleanParam("Show Territory", "showVoronoi", ParamGroup.TEXTURE, "Shade Voronoi cells around terminals", false),
        Parameter.NumberParam("Node Size", "nodeSize", ParamGroup.GEOMETRY, null, 3f, 20f, 1f, 8f),
        Parameter.NumberParam("Edge Width", "edgeWidth", ParamGroup.GEOMETRY, null, 1f, 8f, 0.5f, 3f),
        Parameter.NumberParam("Glow", "glowIntensity", ParamGroup.TEXTURE, null, 0f, 1f, 0.05f, 0.5f),
        Parameter.SelectParam("Color Mode", "colorMode", ParamGroup.COLOR, null, listOf("by-subtree", "by-depth", "edge-length", "radial"), "by-subtree"),
        Parameter.SelectParam("Background", "background", ParamGroup.COLOR, null, listOf("dark", "blueprint", "light"), "dark"),
        Parameter.SelectParam("Animation", "animMode", ParamGroup.FLOW_MOTION, null, listOf("none", "grow", "pulse", "drift"), "grow"),
        Parameter.NumberParam("Speed", "speed", ParamGroup.FLOW_MOTION, null, 0.05f, 1f, 0.05f, 0.3f)
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "terminalCount" to 12f,
        "distribution" to "random",
        "steinerIterations" to 80f,
        "showMST" to true,
        "showVoronoi" to false,
        "nodeSize" to 8f,
        "edgeWidth" to 3f,
        "glowIntensity" to 0.5f,
        "colorMode" to "by-subtree",
        "background" to "dark",
        "animMode" to "grow",
        "speed" to 0.3f
    )

    private data class Edge(val a: Int, val b: Int, val weight: Float)

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
        val numTerminals = (params["terminalCount"] as? Number)?.toInt() ?: 12
        val distribution = (params["distribution"] as? String) ?: "random"
        val steinerIterations = (params["steinerIterations"] as? Number)?.toInt() ?: 80
        val showMST = params["showMST"] as? Boolean ?: true
        val showVoronoi = params["showVoronoi"] as? Boolean ?: false
        val lineWidth = (params["edgeWidth"] as? Number)?.toFloat() ?: 3f
        val terminalSize = (params["nodeSize"] as? Number)?.toFloat() ?: 8f
        val glowIntensity = (params["glowIntensity"] as? Number)?.toFloat() ?: 0.5f
        val colorMode = (params["colorMode"] as? String) ?: "by-subtree"
        val background = (params["background"] as? String) ?: "dark"
        val animMode = (params["animMode"] as? String) ?: "grow"
        val speed = (params["speed"] as? Number)?.toFloat() ?: 0.3f

        val rng = SeededRNG(seed)
        val noise = SimplexNoise(seed)
        val colors = palette.colorInts()

        // Generate terminal points based on distribution
        val margin = w * 0.1f
        val termX = FloatArray(numTerminals)
        val termY = FloatArray(numTerminals)
        when (distribution) {
            "clustered" -> {
                val numClusters = (numTerminals / 3).coerceAtLeast(2)
                val clusterCx = FloatArray(numClusters) { rng.range(margin, w - margin) }
                val clusterCy = FloatArray(numClusters) { rng.range(margin, h - margin) }
                for (i in 0 until numTerminals) {
                    val ci = i % numClusters
                    termX[i] = (clusterCx[ci] + (rng.random() - 0.5f) * w * 0.15f).coerceIn(margin, w - margin)
                    termY[i] = (clusterCy[ci] + (rng.random() - 0.5f) * h * 0.15f).coerceIn(margin, h - margin)
                }
            }
            "ring" -> {
                val cx = w / 2f; val cy = h / 2f
                val radius = minOf(w, h) * 0.35f
                for (i in 0 until numTerminals) {
                    val angle = (2.0 * Math.PI * i / numTerminals).toFloat()
                    termX[i] = cx + radius * kotlin.math.cos(angle)
                    termY[i] = cy + radius * kotlin.math.sin(angle)
                }
            }
            "grid" -> {
                val cols = kotlin.math.ceil(sqrt(numTerminals.toFloat())).toInt()
                val rows = (numTerminals + cols - 1) / cols
                val stepX = (w - 2 * margin) / (cols - 1).coerceAtLeast(1)
                val stepY = (h - 2 * margin) / (rows - 1).coerceAtLeast(1)
                for (i in 0 until numTerminals) {
                    termX[i] = margin + (i % cols) * stepX
                    termY[i] = margin + (i / cols) * stepY
                }
            }
            "fibonacci" -> {
                val goldenAngle = (Math.PI * (3.0 - sqrt(5.0))).toFloat()
                val cx = w / 2f; val cy = h / 2f
                for (i in 0 until numTerminals) {
                    val t = i.toFloat() / numTerminals
                    val r = sqrt(t) * minOf(w, h) * 0.4f
                    val theta = i * goldenAngle
                    termX[i] = cx + r * kotlin.math.cos(theta)
                    termY[i] = cy + r * kotlin.math.sin(theta)
                }
            }
            else -> { // "random"
                for (i in 0 until numTerminals) {
                    termX[i] = rng.range(margin, w - margin)
                    termY[i] = rng.range(margin, h - margin)
                }
            }
        }

        // Animate terminals based on mode
        val animTime = time * speed
        when (animMode) {
            "drift" -> {
                for (i in 0 until numTerminals) {
                    termX[i] += noise.noise2D(i * 0.5f + 50f, animTime) * w * 0.05f
                    termY[i] += noise.noise2D(i * 0.5f + 150f, animTime) * h * 0.05f
                    termX[i] = termX[i].coerceIn(margin, w - margin)
                    termY[i] = termY[i].coerceIn(margin, h - margin)
                }
            }
            // "grow" and "pulse" handled during rendering
        }

        // Compute MST first (for showMST option)
        val mstEdges = computeMST(termX, termY, numTerminals)

        // Compute approximate Steiner tree with user-specified iterations
        val result = computeSteinerTree(termX, termY, numTerminals, steinerIterations)

        // Background
        when (background) {
            "blueprint" -> canvas.drawColor(Color.rgb(20, 40, 80))
            "light" -> canvas.drawColor(Color.rgb(240, 240, 235))
            else -> canvas.drawColor(Color.BLACK)
        }

        // Draw Voronoi territory shading
        if (showVoronoi && numTerminals > 0) {
            val voronoiPaint = Paint().apply { style = Paint.Style.FILL }
            val step = (w / 80f).toInt().coerceAtLeast(2)
            for (px in 0 until bitmap.width step step) {
                for (py in 0 until bitmap.height step step) {
                    var minDist = Float.MAX_VALUE
                    var closest = 0
                    for (i in 0 until numTerminals) {
                        val dx = px - result.px[i]
                        val dy = py - result.py[i]
                        val d = dx * dx + dy * dy
                        if (d < minDist) { minDist = d; closest = i }
                    }
                    voronoiPaint.color = Color.argb(25, Color.red(colors[closest % colors.size]),
                        Color.green(colors[closest % colors.size]), Color.blue(colors[closest % colors.size]))
                    canvas.drawRect(px.toFloat(), py.toFloat(), (px + step).toFloat(), (py + step).toFloat(), voronoiPaint)
                }
            }
        }

        // Compute edge depths for coloring
        val adjacency = buildAdjacency(result.edges, result.px.size)
        val depths = IntArray(result.px.size) { -1 }
        if (result.px.isNotEmpty()) {
            depths[0] = 0
            val queue = ArrayDeque<Int>()
            queue.add(0)
            while (queue.isNotEmpty()) {
                val node = queue.removeFirst()
                for (nb in adjacency[node] ?: emptyList()) {
                    if (depths[nb] < 0) {
                        depths[nb] = depths[node] + 1
                        queue.add(nb)
                    }
                }
            }
        }
        val maxDepth = depths.max().coerceAtLeast(1)

        // Grow animation: reveal edges progressively
        val growFraction = if (animMode == "grow") (animTime * 0.5f).coerceIn(0f, 1f) else 1f
        val visibleEdgeCount = (result.edges.size * growFraction).toInt()

        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = lineWidth
            strokeCap = Paint.Cap.ROUND
        }

        // Draw MST reference lines if enabled
        if (showMST && result.edges.isNotEmpty()) {
            val mstPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = lineWidth * 0.3f
                strokeCap = Paint.Cap.ROUND
                color = when (background) {
                    "light" -> Color.argb(40, 0, 0, 0)
                    else -> Color.argb(40, 255, 255, 255)
                }
            }
            for ((a, b) in mstEdges) {
                canvas.drawLine(termX[a], termY[a], termX[b], termY[b], mstPaint)
            }
        }

        // Draw Steiner edges with glow
        val edgeCount = result.edges.size
        for ((idx, edge) in result.edges.withIndex()) {
            if (idx >= visibleEdgeCount) break

            // Pulse animation: modulate alpha
            val pulseAlpha = if (animMode == "pulse") {
                val phase = kotlin.math.sin(animTime * 3f + idx * 0.5f) * 0.3f + 0.7f
                (phase * 255).toInt().coerceIn(80, 255)
            } else 255

            val edgeColor = when (colorMode) {
                "by-depth" -> {
                    val d = (depths[edge.first].coerceAtLeast(0) + depths[edge.second].coerceAtLeast(0)) / 2f
                    palette.lerpColor((d / maxDepth).coerceIn(0f, 1f))
                }
                "edge-length" -> {
                    val dx = result.px[edge.first] - result.px[edge.second]
                    val dy = result.py[edge.first] - result.py[edge.second]
                    val len = sqrt(dx * dx + dy * dy)
                    palette.lerpColor((len / (w * 0.5f)).coerceIn(0f, 1f))
                }
                "radial" -> {
                    val mx = (result.px[edge.first] + result.px[edge.second]) / 2f
                    val my = (result.py[edge.first] + result.py[edge.second]) / 2f
                    val dx = mx - w / 2f
                    val dy = my - h / 2f
                    val t = sqrt(dx * dx + dy * dy) / (w * 0.6f)
                    palette.lerpColor(t.coerceIn(0f, 1f))
                }
                else -> { // "by-subtree"
                    val t = idx.toFloat() / edgeCount.coerceAtLeast(1)
                    palette.lerpColor(t)
                }
            }

            // Glow layer
            if (glowIntensity > 0f) {
                linePaint.strokeWidth = lineWidth * (2f + glowIntensity * 3f)
                linePaint.color = Color.argb((glowIntensity * 60 * pulseAlpha / 255).toInt().coerceIn(5, 80),
                    Color.red(edgeColor), Color.green(edgeColor), Color.blue(edgeColor))
                canvas.drawLine(
                    result.px[edge.first], result.py[edge.first],
                    result.px[edge.second], result.py[edge.second],
                    linePaint
                )
            }

            // Main edge
            linePaint.strokeWidth = lineWidth
            linePaint.color = Color.argb(pulseAlpha, Color.red(edgeColor), Color.green(edgeColor), Color.blue(edgeColor))
            canvas.drawLine(
                result.px[edge.first], result.py[edge.first],
                result.px[edge.second], result.py[edge.second],
                linePaint
            )
        }

        val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }

        // Draw terminal points
        for (i in 0 until numTerminals) {
            dotPaint.color = colors[i % colors.size]
            canvas.drawCircle(result.px[i], result.py[i], terminalSize, dotPaint)
        }

        // Draw Steiner points smaller and white
        val steinerColor = when (background) { "light" -> Color.DKGRAY; else -> Color.WHITE }
        dotPaint.color = steinerColor
        for (i in numTerminals until result.px.size) {
            canvas.drawCircle(result.px[i], result.py[i], terminalSize * 0.5f, dotPaint)
        }
    }

    override fun renderVector(
        params: Map<String, Any>,
        seed: Int,
        palette: Palette
    ): List<SvgPath> {
        val w = 1080f
        val h = 1080f
        val numTerminals = (params["terminalCount"] as? Number)?.toInt() ?: 12
        val lineWidth = (params["edgeWidth"] as? Number)?.toFloat() ?: 3f
        val showTerminals = true // hardcoded (not in schema; always show)
        val terminalSize = (params["nodeSize"] as? Number)?.toFloat() ?: 8f

        val rng = SeededRNG(seed)
        val colors = palette.colorInts()

        val margin = w * 0.1f
        val termX = FloatArray(numTerminals)
        val termY = FloatArray(numTerminals)
        for (i in 0 until numTerminals) {
            termX[i] = rng.range(margin, w - margin)
            termY[i] = rng.range(margin, h - margin)
        }

        val result = computeSteinerTree(termX, termY, numTerminals)
        val paths = mutableListOf<SvgPath>()

        // Edges
        val edgeCount = result.edges.size
        for ((idx, edge) in result.edges.withIndex()) {
            val t = idx.toFloat() / edgeCount.coerceAtLeast(1)
            val colorInt = palette.lerpColor(t)
            val hex = String.format("#%06X", 0xFFFFFF and colorInt)
            val d = "${SvgBuilder.moveTo(result.px[edge.first], result.py[edge.first])} " +
                    "${SvgBuilder.lineTo(result.px[edge.second], result.py[edge.second])}"
            paths.add(SvgPath(d = d, stroke = hex, strokeWidth = lineWidth))
        }

        // Terminal markers
        if (showTerminals) {
            for (i in 0 until numTerminals) {
                val hex = String.format("#%06X", 0xFFFFFF and colors[i % colors.size])
                paths.add(SvgPath(
                    d = SvgBuilder.circle(result.px[i], result.py[i], terminalSize),
                    fill = hex
                ))
            }
            for (i in numTerminals until result.px.size) {
                paths.add(SvgPath(
                    d = SvgBuilder.circle(result.px[i], result.py[i], terminalSize * 0.5f),
                    fill = "#FFFFFF"
                ))
            }
        }

        return paths
    }

    private data class TreeResult(
        val px: FloatArray,
        val py: FloatArray,
        val edges: List<Pair<Int, Int>>
    )

    private fun computeSteinerTree(termX: FloatArray, termY: FloatArray, n: Int, maxIterations: Int = 80): TreeResult {
        if (n < 2) {
            return TreeResult(termX.copyOf(), termY.copyOf(), emptyList())
        }

        // Start with all terminal points
        val allX = termX.toMutableList()
        val allY = termY.toMutableList()

        // Compute MST with current points
        var mstEdges = computeMST(allX.toFloatArray(), allY.toFloatArray(), allX.size)
        var totalLength = mstLength(allX.toFloatArray(), allY.toFloatArray(), mstEdges)

        // Iterative Steiner point insertion
        val iters = (maxIterations / 16).coerceIn(1, 20)
        for (iteration in 0 until iters) {
            var improved = false

            // For each pair of adjacent edges sharing a node, try a Steiner point
            val adjacency = buildAdjacency(mstEdges, allX.size)

            for (node in adjacency.keys) {
                val neighbours = adjacency[node] ?: continue
                if (neighbours.size < 2) continue

                for (i in 0 until neighbours.size) {
                    for (j in i + 1 until neighbours.size) {
                        val a = neighbours[i]
                        val b = neighbours[j]

                        // Compute approximate Fermat point of triangle (a, node, b)
                        val fx = (allX[a] + allX[node] + allX[b]) / 3f
                        val fy = (allY[a] + allY[node] + allY[b]) / 3f

                        // Check if adding this point improves the tree
                        val testX = allX.toMutableList().apply { add(fx) }
                        val testY = allY.toMutableList().apply { add(fy) }
                        val testEdges = computeMST(testX.toFloatArray(), testY.toFloatArray(), testX.size)
                        val testLength = mstLength(testX.toFloatArray(), testY.toFloatArray(), testEdges)

                        if (testLength < totalLength * 0.99f) {
                            allX.add(fx)
                            allY.add(fy)
                            mstEdges = testEdges
                            totalLength = testLength
                            improved = true
                            break
                        }
                    }
                    if (improved) break
                }
                if (improved) break
            }

            if (!improved) break

            // Re-compute MST with all points
            mstEdges = computeMST(allX.toFloatArray(), allY.toFloatArray(), allX.size)
            totalLength = mstLength(allX.toFloatArray(), allY.toFloatArray(), mstEdges)
        }

        // Prune degree-1 Steiner points (non-terminal leaves)
        val finalEdges = mstEdges.toMutableList()
        var pruned = true
        while (pruned) {
            pruned = false
            val degree = IntArray(allX.size)
            for (edge in finalEdges) {
                degree[edge.first]++
                degree[edge.second]++
            }
            val toRemove = mutableListOf<Pair<Int, Int>>()
            for (edge in finalEdges) {
                if (edge.first >= n && degree[edge.first] <= 1) {
                    toRemove.add(edge); pruned = true
                } else if (edge.second >= n && degree[edge.second] <= 1) {
                    toRemove.add(edge); pruned = true
                }
            }
            finalEdges.removeAll(toRemove)
        }

        return TreeResult(allX.toFloatArray(), allY.toFloatArray(), finalEdges)
    }

    private fun computeMST(px: FloatArray, py: FloatArray, n: Int): List<Pair<Int, Int>> {
        if (n < 2) return emptyList()

        // Kruskal's algorithm with Union-Find
        val edges = mutableListOf<Edge>()
        for (i in 0 until n) {
            for (j in i + 1 until n) {
                val dx = px[i] - px[j]
                val dy = py[i] - py[j]
                edges.add(Edge(i, j, sqrt(dx * dx + dy * dy)))
            }
        }
        edges.sortBy { it.weight }

        val parent = IntArray(n) { it }
        val rank = IntArray(n)

        fun find(x: Int): Int {
            var root = x
            while (parent[root] != root) root = parent[root]
            var curr = x
            while (curr != root) {
                val next = parent[curr]
                parent[curr] = root
                curr = next
            }
            return root
        }

        fun union(a: Int, b: Int): Boolean {
            val ra = find(a); val rb = find(b)
            if (ra == rb) return false
            if (rank[ra] < rank[rb]) parent[ra] = rb
            else if (rank[ra] > rank[rb]) parent[rb] = ra
            else { parent[rb] = ra; rank[ra]++ }
            return true
        }

        val mstEdges = mutableListOf<Pair<Int, Int>>()
        for (edge in edges) {
            if (union(edge.a, edge.b)) {
                mstEdges.add(Pair(edge.a, edge.b))
                if (mstEdges.size == n - 1) break
            }
        }

        return mstEdges
    }

    private fun mstLength(px: FloatArray, py: FloatArray, edges: List<Pair<Int, Int>>): Float {
        var total = 0f
        for ((a, b) in edges) {
            val dx = px[a] - px[b]
            val dy = py[a] - py[b]
            total += sqrt(dx * dx + dy * dy)
        }
        return total
    }

    private fun buildAdjacency(edges: List<Pair<Int, Int>>, n: Int): Map<Int, List<Int>> {
        val adj = mutableMapOf<Int, MutableList<Int>>()
        for ((a, b) in edges) {
            adj.getOrPut(a) { mutableListOf() }.add(b)
            adj.getOrPut(b) { mutableListOf() }.add(a)
        }
        return adj
    }

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float {
        val n = (params["terminalCount"] as? Number)?.toFloat() ?: 12f
        return (n / 15f).coerceIn(0.2f, 1f)
    }
}
