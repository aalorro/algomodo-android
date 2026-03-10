package com.artmondo.algomodo.generators.graphs

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import com.artmondo.algomodo.core.rng.SeededRNG
import com.artmondo.algomodo.core.rng.SimplexNoise
import com.artmondo.algomodo.data.palettes.Palette
import com.artmondo.algomodo.generators.Generator
import com.artmondo.algomodo.generators.ParamGroup
import com.artmondo.algomodo.generators.Parameter
import com.artmondo.algomodo.generators.Quality
import com.artmondo.algomodo.rendering.SvgBuilder
import com.artmondo.algomodo.rendering.SvgPath
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * K-Nearest Neighbor graph generator.
 *
 * Scatters points using a chosen distribution and connects each point to its
 * K nearest neighbors, producing web-like proximity graphs. Supports directed
 * and symmetric edge modes, clustered distributions, and animated K growth.
 */
class GraphKNearestNeighborGenerator : Generator {

    override val id = "graph-knn"
    override val family = "graphs"
    override val styleName = "K-Nearest Neighbor"
    override val definition =
        "Proximity graph connecting each point to its K closest neighbors, revealing cluster structure and spatial density patterns."
    override val algorithmNotes =
        "Points are scattered with SeededRNG using the chosen distribution. For each point, all pairwise distances " +
        "are computed and the K nearest neighbors are selected. In symmetric mode an edge exists if either endpoint " +
        "considers the other a neighbor. In directed mode arrowheads indicate direction. Colour encodes distance, " +
        "cluster membership, vertex degree, or radial position. Animation can grow K from 1 over time."
    override val supportsVector = true
    override val supportsAnimation = true

    override val parameterSchema = listOf(
        Parameter.NumberParam("Point Count", "pointCount", ParamGroup.COMPOSITION, null, 20f, 400f, 10f, 100f),
        Parameter.NumberParam("K Neighbors", "kNeighbors", ParamGroup.GEOMETRY, "Number of nearest neighbors to connect", 1f, 15f, 1f, 3f),
        Parameter.SelectParam("Distribution", "distribution", ParamGroup.COMPOSITION, null, listOf("random", "clustered", "grid-jittered", "spiral", "concentric"), "random"),
        Parameter.NumberParam("Cluster Count", "clusterCount", ParamGroup.COMPOSITION, "Number of clusters (for clustered mode)", 2f, 10f, 1f, 4f),
        Parameter.BooleanParam("Directed", "directed", ParamGroup.GEOMETRY, "Show edge direction with arrows", false),
        Parameter.BooleanParam("Symmetric", "symmetric", ParamGroup.GEOMETRY, "Include edge if either point is K-nearest of the other", true),
        Parameter.NumberParam("Node Size", "nodeSize", ParamGroup.GEOMETRY, null, 2f, 14f, 1f, 5f),
        Parameter.NumberParam("Edge Width", "edgeWidth", ParamGroup.GEOMETRY, null, 0.5f, 4f, 0.5f, 1.5f),
        Parameter.NumberParam("Edge Opacity", "edgeOpacity", ParamGroup.TEXTURE, null, 0.1f, 1f, 0.05f, 0.5f),
        Parameter.SelectParam("Color Mode", "colorMode", ParamGroup.COLOR, null, listOf("distance", "cluster", "degree", "radial"), "distance"),
        Parameter.SelectParam("Animation", "animMode", ParamGroup.FLOW_MOTION, null, listOf("none", "grow-k", "drift", "pulse"), "grow-k"),
        Parameter.NumberParam("Speed", "speed", ParamGroup.FLOW_MOTION, null, 0.05f, 1f, 0.05f, 0.3f)
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "pointCount" to 100f,
        "kNeighbors" to 3f,
        "distribution" to "random",
        "clusterCount" to 4f,
        "directed" to false,
        "symmetric" to true,
        "nodeSize" to 5f,
        "edgeWidth" to 1.5f,
        "edgeOpacity" to 0.5f,
        "colorMode" to "distance",
        "animMode" to "grow-k",
        "speed" to 0.3f
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
        val w = bitmap.width.toFloat()
        val h = bitmap.height.toFloat()
        val n = qualityScale((params["pointCount"] as? Number)?.toInt() ?: 100, quality)
        val kTarget = (params["kNeighbors"] as? Number)?.toInt() ?: 3
        val distribution = (params["distribution"] as? String) ?: "random"
        val clusterCount = (params["clusterCount"] as? Number)?.toInt() ?: 4
        val directed = (params["directed"] as? Boolean) ?: false
        val symmetric = (params["symmetric"] as? Boolean) ?: true
        val nodeSize = (params["nodeSize"] as? Number)?.toFloat() ?: 5f
        val edgeWidth = (params["edgeWidth"] as? Number)?.toFloat() ?: 1.5f
        val edgeOpacity = (params["edgeOpacity"] as? Number)?.toFloat() ?: 0.5f
        val colorMode = (params["colorMode"] as? String) ?: "distance"
        val animMode = (params["animMode"] as? String) ?: "grow-k"
        val speed = (params["speed"] as? Number)?.toFloat() ?: 0.3f

        val rng = SeededRNG(seed)
        val noise = SimplexNoise(seed)
        val colors = palette.colorInts()

        val margin = w * 0.08f
        val px = FloatArray(n)
        val py = FloatArray(n)
        val clusterIds = IntArray(n)
        generatePoints(rng, px, py, clusterIds, n, w, h, margin, distribution, clusterCount)

        // Animate drift
        if (animMode == "drift" && time > 0f) {
            for (i in 0 until n) {
                px[i] += noise.noise2D(i * 0.5f + 50f, time * speed * 0.3f) * w * 0.04f
                py[i] += noise.noise2D(i * 0.5f + 150f, time * speed * 0.3f) * h * 0.04f
                px[i] = px[i].coerceIn(margin, w - margin)
                py[i] = py[i].coerceIn(margin, h - margin)
            }
        }

        // Compute effective K for animation
        val k = when (animMode) {
            "grow-k" -> {
                val progress = (time * speed).coerceIn(0f, 1f)
                (1 + (kTarget - 1) * progress).toInt().coerceIn(1, kTarget)
            }
            else -> kTarget
        }.coerceAtMost(n - 1)

        // Compute KNN edges
        val edges = computeKnnEdges(px, py, n, k, symmetric)

        // Find max distance for normalization
        var maxDist = 0f
        for ((i, j) in edges) {
            val dx = px[i] - px[j]; val dy = py[i] - py[j]
            val d = sqrt(dx * dx + dy * dy)
            if (d > maxDist) maxDist = d
        }
        if (maxDist < 1f) maxDist = 1f

        // Compute degrees
        val degree = IntArray(n)
        for ((i, j) in edges) { degree[i]++; degree[j]++ }
        val maxDegree = degree.max().coerceAtLeast(1)
        val cx = w / 2f; val cy = h / 2f
        val maxRadial = sqrt(cx * cx + cy * cy).coerceAtLeast(1f)

        // Render
        canvas.drawColor(Color.BLACK)

        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = edgeWidth
            strokeCap = Paint.Cap.ROUND
        }
        val alphaInt = (edgeOpacity * 255).toInt().coerceIn(10, 255)

        // Pulse animation
        val pulsePhase = if (animMode == "pulse") time * speed * 2f else 0f

        for ((idx, edge) in edges.withIndex()) {
            val (i, j) = edge
            val dx = px[i] - px[j]; val dy = py[i] - py[j]
            val dist = sqrt(dx * dx + dy * dy)

            val baseColor = when (colorMode) {
                "distance" -> palette.lerpColor((dist / maxDist).coerceIn(0f, 1f))
                "cluster" -> colors[clusterIds[i] % colors.size]
                "degree" -> palette.lerpColor(degree[i].toFloat() / maxDegree)
                "radial" -> {
                    val mx = (px[i] + px[j]) / 2f
                    val my = (py[i] + py[j]) / 2f
                    val r = sqrt((mx - cx) * (mx - cx) + (my - cy) * (my - cy))
                    palette.lerpColor((r / maxRadial).coerceIn(0f, 1f))
                }
                else -> palette.lerpColor(idx.toFloat() / edges.size.coerceAtLeast(1))
            }

            var alpha = alphaInt
            if (animMode == "pulse") {
                val edgeT = idx.toFloat() / edges.size.coerceAtLeast(1)
                val pulse = (1f - kotlin.math.abs(edgeT - (pulsePhase % 1f)) * 5f).coerceIn(0.2f, 1f)
                alpha = (alpha * pulse).toInt().coerceIn(10, 255)
            }

            linePaint.color = Color.argb(alpha, Color.red(baseColor), Color.green(baseColor), Color.blue(baseColor))
            canvas.drawLine(px[i], py[i], px[j], py[j], linePaint)

            if (directed) {
                drawArrowhead(canvas, px[i], py[i], px[j], py[j], edgeWidth * 3f, linePaint)
            }
        }

        // Draw nodes
        val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        for (i in 0 until n) {
            dotPaint.color = when (colorMode) {
                "cluster" -> colors[clusterIds[i] % colors.size]
                "degree" -> palette.lerpColor(degree[i].toFloat() / maxDegree)
                "radial" -> {
                    val r = sqrt((px[i] - cx) * (px[i] - cx) + (py[i] - cy) * (py[i] - cy))
                    palette.lerpColor((r / maxRadial).coerceIn(0f, 1f))
                }
                else -> colors[i % colors.size]
            }
            canvas.drawCircle(px[i], py[i], nodeSize, dotPaint)
        }
    }

    override fun renderVector(
        params: Map<String, Any>,
        seed: Int,
        palette: Palette
    ): List<SvgPath> {
        val w = 1080f; val h = 1080f
        val n = (params["pointCount"] as? Number)?.toInt() ?: 100
        val k = ((params["kNeighbors"] as? Number)?.toInt() ?: 3).coerceAtMost(n - 1)
        val distribution = (params["distribution"] as? String) ?: "random"
        val clusterCount = (params["clusterCount"] as? Number)?.toInt() ?: 4
        val symmetric = (params["symmetric"] as? Boolean) ?: true
        val nodeSize = (params["nodeSize"] as? Number)?.toFloat() ?: 5f
        val edgeWidth = (params["edgeWidth"] as? Number)?.toFloat() ?: 1.5f
        val colorMode = (params["colorMode"] as? String) ?: "distance"

        val rng = SeededRNG(seed)
        val colors = palette.colorInts()
        val margin = w * 0.08f
        val px = FloatArray(n); val py = FloatArray(n); val clusterIds = IntArray(n)
        generatePoints(rng, px, py, clusterIds, n, w, h, margin, distribution, clusterCount)

        val edges = computeKnnEdges(px, py, n, k, symmetric)
        var maxDist = 0f
        for ((i, j) in edges) {
            val dx = px[i] - px[j]; val dy = py[i] - py[j]
            val d = sqrt(dx * dx + dy * dy)
            if (d > maxDist) maxDist = d
        }
        if (maxDist < 1f) maxDist = 1f

        val paths = mutableListOf<SvgPath>()

        for ((idx, edge) in edges.withIndex()) {
            val (i, j) = edge
            val dx = px[i] - px[j]; val dy = py[i] - py[j]
            val dist = sqrt(dx * dx + dy * dy)
            val colorInt = when (colorMode) {
                "distance" -> palette.lerpColor((dist / maxDist).coerceIn(0f, 1f))
                "cluster" -> colors[clusterIds[i] % colors.size]
                else -> palette.lerpColor(idx.toFloat() / edges.size.coerceAtLeast(1))
            }
            val hex = String.format("#%06X", 0xFFFFFF and colorInt)
            val d = "${SvgBuilder.moveTo(px[i], py[i])} ${SvgBuilder.lineTo(px[j], py[j])}"
            paths.add(SvgPath(d = d, stroke = hex, strokeWidth = edgeWidth))
        }

        for (i in 0 until n) {
            val hex = String.format("#%06X", 0xFFFFFF and colors[i % colors.size])
            paths.add(SvgPath(d = SvgBuilder.circle(px[i], py[i], nodeSize), fill = hex))
        }

        return paths
    }

    private fun generatePoints(
        rng: SeededRNG, px: FloatArray, py: FloatArray, clusterIds: IntArray,
        n: Int, w: Float, h: Float, margin: Float, distribution: String, clusterCount: Int
    ) {
        when (distribution) {
            "clustered" -> {
                val cxArr = FloatArray(clusterCount) { rng.range(margin * 2, w - margin * 2) }
                val cyArr = FloatArray(clusterCount) { rng.range(margin * 2, h - margin * 2) }
                val spread = w * 0.12f
                for (i in 0 until n) {
                    val c = i % clusterCount
                    clusterIds[i] = c
                    px[i] = (cxArr[c] + (rng.random() - 0.5f) * spread * 2f).coerceIn(margin, w - margin)
                    py[i] = (cyArr[c] + (rng.random() - 0.5f) * spread * 2f).coerceIn(margin, h - margin)
                }
            }
            "grid-jittered" -> {
                val cols = sqrt(n.toFloat() * w / h).toInt().coerceAtLeast(3)
                val rows = (n / cols).coerceAtLeast(3)
                val cellW = (w - margin * 2) / (cols - 1)
                val cellH = (h - margin * 2) / (rows - 1)
                var idx = 0
                for (r in 0 until rows) {
                    for (c in 0 until cols) {
                        if (idx >= n) break
                        px[idx] = (margin + c * cellW + (rng.random() - 0.5f) * cellW * 0.6f).coerceIn(margin, w - margin)
                        py[idx] = (margin + r * cellH + (rng.random() - 0.5f) * cellH * 0.6f).coerceIn(margin, h - margin)
                        clusterIds[idx] = (r + c) % 5
                        idx++
                    }
                }
                for (i in idx until n) {
                    px[i] = rng.range(margin, w - margin)
                    py[i] = rng.range(margin, h - margin)
                }
            }
            "spiral" -> {
                for (i in 0 until n) {
                    val t = i.toFloat() / n
                    val angle = t * 6f * PI.toFloat()
                    val r = t * (w / 2f - margin)
                    px[i] = (w / 2f + cos(angle) * r).coerceIn(margin, w - margin)
                    py[i] = (h / 2f + sin(angle) * r).coerceIn(margin, h - margin)
                    clusterIds[i] = (i * 5 / n)
                }
            }
            "concentric" -> {
                val rings = 5
                for (i in 0 until n) {
                    val ring = i % rings
                    val r = (ring + 1f) / rings * (w / 2f - margin)
                    val angle = rng.random() * 2f * PI.toFloat()
                    px[i] = (w / 2f + cos(angle) * r).coerceIn(margin, w - margin)
                    py[i] = (h / 2f + sin(angle) * r).coerceIn(margin, h - margin)
                    clusterIds[i] = ring
                }
            }
            else -> { // random
                for (i in 0 until n) {
                    px[i] = rng.range(margin, w - margin)
                    py[i] = rng.range(margin, h - margin)
                    clusterIds[i] = (i * 5 / n)
                }
            }
        }
    }

    private fun computeKnnEdges(px: FloatArray, py: FloatArray, n: Int, k: Int, symmetric: Boolean): List<Pair<Int, Int>> {
        val edgeSet = mutableSetOf<Long>()
        val distances = FloatArray(n)
        val indices = IntArray(n) { it }

        for (i in 0 until n) {
            for (j in 0 until n) {
                val dx = px[i] - px[j]; val dy = py[i] - py[j]
                distances[j] = dx * dx + dy * dy
            }
            // Partial sort to find k nearest (simple selection)
            val sorted = indices.copyOf()
            sorted.sortedArrayBy(distances, n)

            var count = 0
            for (s in 0 until n) {
                if (sorted[s] == i) continue
                val j = sorted[s]
                val key = if (symmetric) packEdge(minOf(i, j), maxOf(i, j)) else packEdge(i, j)
                edgeSet.add(key)
                count++
                if (count >= k) break
            }
        }

        return edgeSet.map { Pair(((it shr 16) and 0xFFFF).toInt(), (it and 0xFFFF).toInt()) }
    }

    private fun IntArray.sortedArrayBy(keys: FloatArray, n: Int) {
        // Simple insertion sort on indices by key - fine for moderate n
        for (i in 1 until n) {
            val v = this[i]
            val kv = keys[v]
            var j = i - 1
            while (j >= 0 && keys[this[j]] > kv) {
                this[j + 1] = this[j]
                j--
            }
            this[j + 1] = v
        }
    }

    private fun packEdge(a: Int, b: Int): Long = (a.toLong() shl 16) or b.toLong()

    private fun drawArrowhead(canvas: Canvas, x1: Float, y1: Float, x2: Float, y2: Float, size: Float, paint: Paint) {
        val angle = atan2(y2 - y1, x2 - x1)
        val tipX = x2 - cos(angle) * size * 0.5f
        val tipY = y2 - sin(angle) * size * 0.5f
        val leftX = tipX - cos(angle - 0.4f) * size
        val leftY = tipY - sin(angle - 0.4f) * size
        val rightX = tipX - cos(angle + 0.4f) * size
        val rightY = tipY - sin(angle + 0.4f) * size

        val prevStyle = paint.style
        paint.style = Paint.Style.FILL
        val path = Path().apply {
            moveTo(tipX, tipY)
            lineTo(leftX, leftY)
            lineTo(rightX, rightY)
            close()
        }
        canvas.drawPath(path, paint)
        paint.style = prevStyle
    }

    private fun qualityScale(base: Int, quality: Quality): Int = when (quality) {
        Quality.DRAFT -> (base * 0.5f).toInt().coerceAtLeast(10)
        Quality.BALANCED -> base
        Quality.ULTRA -> (base * 1.5f).toInt()
    }

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float {
        val n = (params["pointCount"] as? Number)?.toFloat() ?: 100f
        return (n * n / 40000f).coerceIn(0.2f, 1f)
    }
}
