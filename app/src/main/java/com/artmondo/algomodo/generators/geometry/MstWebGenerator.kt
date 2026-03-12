package com.artmondo.algomodo.generators.geometry

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.artmondo.algomodo.core.rng.SeededRNG
import com.artmondo.algomodo.data.palettes.Palette
import com.artmondo.algomodo.generators.Generator
import com.artmondo.algomodo.generators.ParamGroup
import com.artmondo.algomodo.generators.Parameter
import com.artmondo.algomodo.generators.Quality

/**
 * Minimum Spanning Tree web generator.
 *
 * Generates random points on the canvas and computes their Minimum Spanning Tree
 * using Prim's algorithm. The resulting tree edges form an organic web-like structure.
 * Points are colored from the palette and edges inherit their endpoint colors.
 * Animation slowly drifts the points over time.
 */
class MstWebGenerator : Generator {

    override val id = "mst-web"
    override val family = "geometry"
    override val styleName = "MST Web"
    override val definition =
        "An organic web structure formed by the Minimum Spanning Tree of randomly placed points."
    override val algorithmNotes =
        "Generates N random points using the seeded RNG, then computes the Minimum Spanning Tree " +
        "using Prim's algorithm (greedy nearest-neighbor expansion). Each edge is drawn as a line " +
        "between connected points. Points are optionally drawn as circles. Animation applies a " +
        "slow drift to each point's position using sinusoidal offsets."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema: List<Parameter> = listOf(
        Parameter.NumberParam(
            name = "Point Count",
            key = "pointCount",
            group = ParamGroup.COMPOSITION,
            help = null,
            min = 10f, max = 600f, step = 10f, default = 400f
        ),
        Parameter.NumberParam(
            name = "Prune %",
            key = "prunePercent",
            group = ParamGroup.COMPOSITION,
            help = "Remove the longest X% of MST edges \u2014 breaks the tree into isolated organic subtree clusters",
            min = 0f, max = 70f, step = 5f, default = 0f
        ),
        Parameter.NumberParam(
            name = "Node Size",
            key = "nodeSize",
            group = ParamGroup.GEOMETRY,
            help = null,
            min = 0f, max = 30f, step = 1f, default = 8f
        ),
        Parameter.NumberParam(
            name = "Edge Width",
            key = "edgeWidth",
            group = ParamGroup.GEOMETRY,
            help = null,
            min = 1f, max = 8f, step = 0.5f, default = 4f
        ),
        Parameter.SelectParam(
            name = "Distribution",
            key = "distribution",
            group = ParamGroup.COMPOSITION,
            help = "uniform: random scatter | gaussian: centre-weighted density | clustered: tight local groups | ring: annular band | fibonacci: phyllotaxis golden-angle spiral",
            options = listOf("uniform", "gaussian", "clustered", "ring", "fibonacci"),
            default = "uniform"
        ),
        Parameter.SelectParam(
            name = "Color Mode",
            key = "colorMode",
            group = ParamGroup.COLOR,
            help = "palette-cycle: edge colour by node indices | edge-length: short\u2192long mapped to palette | depth: MST growth order | radial: edge midpoint distance from centre",
            options = listOf("palette-cycle", "edge-length", "depth", "radial"),
            default = "palette-cycle"
        ),
        Parameter.SelectParam(
            name = "Background",
            key = "background",
            group = ParamGroup.COLOR,
            help = null,
            options = listOf("dark", "light"),
            default = "dark"
        ),
        Parameter.NumberParam(
            name = "Drift",
            key = "drift",
            group = ParamGroup.FLOW_MOTION,
            help = "Node drift amplitude in pixels (animated only)",
            min = 0f, max = 40f, step = 1f, default = 12f
        ),
        Parameter.NumberParam(
            name = "Drift Speed",
            key = "driftSpeed",
            group = ParamGroup.FLOW_MOTION,
            help = null,
            min = 0.02f, max = 0.5f, step = 0.02f, default = 0.1f
        )
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "pointCount" to 400f,
        "prunePercent" to 0f,
        "nodeSize" to 8f,
        "edgeWidth" to 4f,
        "distribution" to "uniform",
        "colorMode" to "palette-cycle",
        "background" to "dark",
        "drift" to 12f,
        "driftSpeed" to 0.1f
    )

    /**
     * Prim's algorithm for Minimum Spanning Tree.
     * Returns list of edges as (index_a, index_b).
     */
    private fun primMST(
        xs: FloatArray, ys: FloatArray, n: Int
    ): List<Pair<Int, Int>> {
        if (n <= 1) return emptyList()

        val inMST = BooleanArray(n)
        val minDist = FloatArray(n) { Float.MAX_VALUE }
        val minEdge = IntArray(n) { -1 }
        val edges = mutableListOf<Pair<Int, Int>>()

        // Start from vertex 0
        inMST[0] = true
        for (j in 1 until n) {
            val dx = xs[0] - xs[j]
            val dy = ys[0] - ys[j]
            minDist[j] = dx * dx + dy * dy // Use squared distance
            minEdge[j] = 0
        }

        for (step in 0 until n - 1) {
            // Find the closest non-MST vertex
            var bestIdx = -1
            var bestDist = Float.MAX_VALUE
            for (j in 0 until n) {
                if (!inMST[j] && minDist[j] < bestDist) {
                    bestDist = minDist[j]
                    bestIdx = j
                }
            }

            if (bestIdx == -1) break

            inMST[bestIdx] = true
            edges.add(minEdge[bestIdx] to bestIdx)

            // Update distances
            for (j in 0 until n) {
                if (!inMST[j]) {
                    val dx = xs[bestIdx] - xs[j]
                    val dy = ys[bestIdx] - ys[j]
                    val dist = dx * dx + dy * dy
                    if (dist < minDist[j]) {
                        minDist[j] = dist
                        minEdge[j] = bestIdx
                    }
                }
            }
        }

        return edges
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
        val numPoints = (params["pointCount"] as? Number)?.toInt() ?: 400
        val prunePercent = (params["prunePercent"] as? Number)?.toFloat() ?: 0f
        val lineWidth = (params["edgeWidth"] as? Number)?.toFloat() ?: 4f
        val pointSize = (params["nodeSize"] as? Number)?.toFloat() ?: 8f
        val distribution = (params["distribution"] as? String) ?: "uniform"
        val colorMode = (params["colorMode"] as? String) ?: "palette-cycle"
        val background = (params["background"] as? String) ?: "dark"
        val drift = (params["drift"] as? Number)?.toFloat() ?: 12f
        val driftSpeed = (params["driftSpeed"] as? Number)?.toFloat() ?: 0.1f

        canvas.drawColor(if (background == "light") Color.rgb(240, 240, 235) else Color.BLACK)

        val rng = SeededRNG(seed)
        val margin = w * 0.05f

        // Generate base positions based on distribution
        val baseXs = FloatArray(numPoints)
        val baseYs = FloatArray(numPoints)
        when (distribution) {
            "gaussian" -> {
                for (i in 0 until numPoints) {
                    // Box-Muller approximation via averaging randoms
                    val gx = ((rng.random() + rng.random() + rng.random()) / 3f - 0.5f) * 2f
                    val gy = ((rng.random() + rng.random() + rng.random()) / 3f - 0.5f) * 2f
                    baseXs[i] = (w / 2f + gx * w * 0.35f).coerceIn(margin, w - margin)
                    baseYs[i] = (h / 2f + gy * h * 0.35f).coerceIn(margin, h - margin)
                }
            }
            "clustered" -> {
                val numClusters = (numPoints / 8).coerceIn(3, 20)
                val clusterX = FloatArray(numClusters) { rng.range(margin, w - margin) }
                val clusterY = FloatArray(numClusters) { rng.range(margin, h - margin) }
                for (i in 0 until numPoints) {
                    val ci = i % numClusters
                    baseXs[i] = (clusterX[ci] + (rng.random() - 0.5f) * w * 0.15f).coerceIn(margin, w - margin)
                    baseYs[i] = (clusterY[ci] + (rng.random() - 0.5f) * h * 0.15f).coerceIn(margin, h - margin)
                }
            }
            "ring" -> {
                val cx = w / 2f; val cy = h / 2f
                val radius = kotlin.math.min(w, h) * 0.35f
                for (i in 0 until numPoints) {
                    val angle = rng.random() * 2f * kotlin.math.PI.toFloat()
                    val r = radius * (0.7f + rng.random() * 0.3f)
                    baseXs[i] = (cx + r * kotlin.math.cos(angle)).coerceIn(margin, w - margin)
                    baseYs[i] = (cy + r * kotlin.math.sin(angle)).coerceIn(margin, h - margin)
                }
            }
            "fibonacci" -> {
                val goldenAngle = (kotlin.math.PI * (3.0 - kotlin.math.sqrt(5.0))).toFloat()
                val cx = w / 2f; val cy = h / 2f
                for (i in 0 until numPoints) {
                    val t = i.toFloat() / numPoints
                    val r = kotlin.math.sqrt(t) * kotlin.math.min(w, h) * 0.42f
                    val theta = i * goldenAngle
                    baseXs[i] = (cx + r * kotlin.math.cos(theta)).coerceIn(margin, w - margin)
                    baseYs[i] = (cy + r * kotlin.math.sin(theta)).coerceIn(margin, h - margin)
                }
            }
            else -> { // "uniform"
                for (i in 0 until numPoints) {
                    baseXs[i] = rng.range(margin, w - margin)
                    baseYs[i] = rng.range(margin, h - margin)
                }
            }
        }

        // Animate: drift points with sinusoidal offsets using drift/driftSpeed params
        val xs = FloatArray(numPoints)
        val ys = FloatArray(numPoints)
        for (i in 0 until numPoints) {
            val phase = i * 0.37f
            xs[i] = baseXs[i] + kotlin.math.sin(time * driftSpeed * 3f + phase) * drift
            ys[i] = baseYs[i] + kotlin.math.cos(time * driftSpeed * 2.5f + phase * 1.3f) * drift
        }

        // Compute MST
        var edges = primMST(xs, ys, numPoints)

        // Prune: remove the longest X% of edges
        if (prunePercent > 0f && edges.isNotEmpty()) {
            val edgesWithLength = edges.map { (a, b) ->
                val dx = xs[a] - xs[b]
                val dy = ys[a] - ys[b]
                Triple(a, b, dx * dx + dy * dy)
            }.sortedBy { it.third }
            val keepCount = ((edges.size * (1f - prunePercent / 100f)).toInt()).coerceAtLeast(1)
            edges = edgesWithLength.take(keepCount).map { Pair(it.first, it.second) }
        }

        val paletteColors = palette.colorInts()

        // Compute max edge length for edge-length color mode
        val maxEdgeLen = if (colorMode == "edge-length" && edges.isNotEmpty()) {
            edges.maxOf { (a, b) ->
                val dx = xs[a] - xs[b]; val dy = ys[a] - ys[b]
                kotlin.math.sqrt(dx * dx + dy * dy)
            }
        } else 1f

        val linePaint = Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = lineWidth
            isAntiAlias = quality != Quality.DRAFT
            strokeCap = Paint.Cap.ROUND
        }

        val pointPaint = Paint().apply {
            style = Paint.Style.FILL
            isAntiAlias = quality != Quality.DRAFT
        }

        // Draw edges with colorMode
        for ((idx, edge) in edges.withIndex()) {
            val (a, b) = edge
            linePaint.color = when (colorMode) {
                "edge-length" -> {
                    val dx = xs[a] - xs[b]; val dy = ys[a] - ys[b]
                    val len = kotlin.math.sqrt(dx * dx + dy * dy)
                    palette.lerpColor((len / maxEdgeLen).coerceIn(0f, 1f))
                }
                "depth" -> {
                    palette.lerpColor(idx.toFloat() / edges.size.coerceAtLeast(1))
                }
                "radial" -> {
                    val mx = (xs[a] + xs[b]) / 2f
                    val my = (ys[a] + ys[b]) / 2f
                    val dx = mx - w / 2f; val dy = my - h / 2f
                    val t = kotlin.math.sqrt(dx * dx + dy * dy) / (w * 0.6f)
                    palette.lerpColor(t.coerceIn(0f, 1f))
                }
                else -> { // "palette-cycle"
                    palette.lerpColor(a.toFloat() / numPoints)
                }
            }
            canvas.drawLine(xs[a], ys[a], xs[b], ys[b], linePaint)
        }

        // Draw points
        if (pointSize > 0f) {
            for (i in 0 until numPoints) {
                pointPaint.color = paletteColors[i % paletteColors.size]
                canvas.drawCircle(xs[i], ys[i], pointSize, pointPaint)
            }
        }
    }

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float {
        val numPoints = (params["pointCount"] as? Number)?.toInt() ?: 400
        // Prim's is O(n^2)
        return (numPoints * numPoints / 400000f).coerceIn(0.1f, 1f)
    }
}
