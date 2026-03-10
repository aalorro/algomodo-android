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
import kotlin.math.sqrt

/**
 * Gabriel graph generator.
 *
 * For each pair of points (p, q), an edge exists if and only if no other
 * point lies inside the diametral circle whose diameter is the segment pq.
 * The Gabriel graph is a subgraph of the Delaunay triangulation and a
 * supergraph of the minimum spanning tree. Optimised by first computing
 * the Delaunay triangulation and testing the Gabriel condition only on
 * Delaunay edges.
 */
class GraphGabrielGraphGenerator : Generator {

    override val id = "graph-gabriel"
    override val family = "graphs"
    override val styleName = "Gabriel Graph"
    override val definition =
        "Proximity graph where an edge connects two points only if no third point lies within their diametral circle, creating elegant sparse networks."
    override val algorithmNotes =
        "Points are scattered with SeededRNG. A Bowyer-Watson Delaunay triangulation is computed first as a superset. " +
        "For each Delaunay edge (p,q), the midpoint and squared radius (dist²/4) are computed, and the edge is " +
        "kept only if no other point falls inside the circle. Optional overlays show the diametral circles, the " +
        "full Delaunay for comparison, or the MST subset. Animation can build edges by length or drift points."
    override val supportsVector = true
    override val supportsAnimation = true

    override val parameterSchema = listOf(
        Parameter.NumberParam("Point Count", "pointCount", ParamGroup.COMPOSITION, null, 15f, 300f, 5f, 80f),
        Parameter.SelectParam("Distribution", "distribution", ParamGroup.COMPOSITION, null, listOf("random", "grid-jittered", "clustered", "gaussian"), "random"),
        Parameter.BooleanParam("Show Diametral Circles", "showCircles", ParamGroup.TEXTURE, "Overlay diametral circles for edges", false),
        Parameter.NumberParam("Circle Opacity", "circleOpacity", ParamGroup.TEXTURE, null, 0.05f, 0.5f, 0.05f, 0.15f),
        Parameter.NumberParam("Node Size", "nodeSize", ParamGroup.GEOMETRY, null, 2f, 14f, 1f, 6f),
        Parameter.NumberParam("Edge Width", "edgeWidth", ParamGroup.GEOMETRY, null, 0.5f, 4f, 0.5f, 2f),
        Parameter.BooleanParam("Show MST", "showMST", ParamGroup.GEOMETRY, "Highlight MST subset in thicker strokes", false),
        Parameter.BooleanParam("Show Delaunay", "showDelaunay", ParamGroup.GEOMETRY, "Show full Delaunay edges (faint) for comparison", false),
        Parameter.SelectParam("Color Mode", "colorMode", ParamGroup.COLOR, null, listOf("edge-length", "region", "degree", "random"), "edge-length"),
        Parameter.SelectParam("Animation", "animMode", ParamGroup.FLOW_MOTION, null, listOf("none", "build", "drift", "highlight-circles"), "build"),
        Parameter.NumberParam("Speed", "speed", ParamGroup.FLOW_MOTION, null, 0.05f, 1f, 0.05f, 0.25f)
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "pointCount" to 80f,
        "distribution" to "random",
        "showCircles" to false,
        "circleOpacity" to 0.15f,
        "nodeSize" to 6f,
        "edgeWidth" to 2f,
        "showMST" to false,
        "showDelaunay" to false,
        "colorMode" to "edge-length",
        "animMode" to "build",
        "speed" to 0.25f
    )

    private data class Tri(val a: Int, val b: Int, val c: Int)

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
        val n = qualityScale((params["pointCount"] as? Number)?.toInt() ?: 80, quality)
        val distribution = (params["distribution"] as? String) ?: "random"
        val showCircles = (params["showCircles"] as? Boolean) ?: false
        val circleOpacity = (params["circleOpacity"] as? Number)?.toFloat() ?: 0.15f
        val nodeSize = (params["nodeSize"] as? Number)?.toFloat() ?: 6f
        val edgeWidth = (params["edgeWidth"] as? Number)?.toFloat() ?: 2f
        val showMST = (params["showMST"] as? Boolean) ?: false
        val showDelaunay = (params["showDelaunay"] as? Boolean) ?: false
        val colorMode = (params["colorMode"] as? String) ?: "edge-length"
        val animMode = (params["animMode"] as? String) ?: "build"
        val speed = (params["speed"] as? Number)?.toFloat() ?: 0.25f

        val rng = SeededRNG(seed)
        val noise = SimplexNoise(seed)
        val colors = palette.colorInts()

        val margin = w * 0.08f
        val px = FloatArray(n)
        val py = FloatArray(n)
        generatePoints(rng, px, py, n, w, h, margin, distribution)

        if (animMode == "drift" && time > 0f) {
            for (i in 0 until n) {
                px[i] += noise.noise2D(i * 0.4f + 30f, time * speed * 0.3f) * w * 0.04f
                py[i] += noise.noise2D(i * 0.4f + 130f, time * speed * 0.3f) * h * 0.04f
                px[i] = px[i].coerceIn(margin, w - margin)
                py[i] = py[i].coerceIn(margin, h - margin)
            }
        }

        // Compute Delaunay
        val pxExt = FloatArray(n + 3)
        val pyExt = FloatArray(n + 3)
        System.arraycopy(px, 0, pxExt, 0, n)
        System.arraycopy(py, 0, pyExt, 0, n)
        val m = maxOf(w, h) * 3f
        pxExt[n] = -m; pyExt[n] = -m
        pxExt[n + 1] = w / 2f; pyExt[n + 1] = h + m * 2f
        pxExt[n + 2] = w + m * 2f; pyExt[n + 2] = -m

        val triangles = bowyerWatson(pxExt, pyExt, n)

        // Extract unique Delaunay edges
        val delaunayEdges = mutableSetOf<Long>()
        for (tri in triangles) {
            delaunayEdges.add(packEdge(minOf(tri.a, tri.b), maxOf(tri.a, tri.b)))
            delaunayEdges.add(packEdge(minOf(tri.b, tri.c), maxOf(tri.b, tri.c)))
            delaunayEdges.add(packEdge(minOf(tri.c, tri.a), maxOf(tri.c, tri.a)))
        }
        val delaunayList = delaunayEdges.map { Pair(((it shr 16) and 0xFFFF).toInt(), (it and 0xFFFF).toInt()) }

        // Gabriel filter
        val gabrielEdges = delaunayList.filter { (i, j) ->
            isGabrielEdge(px, py, n, i, j)
        }

        // Sort by edge length for build animation
        val sortedGabriel = gabrielEdges.sortedBy { (i, j) ->
            val dx = px[i] - px[j]; val dy = py[i] - py[j]; dx * dx + dy * dy
        }

        var maxLen = 0f
        for ((i, j) in sortedGabriel) {
            val dx = px[i] - px[j]; val dy = py[i] - py[j]
            val d = sqrt(dx * dx + dy * dy)
            if (d > maxLen) maxLen = d
        }
        if (maxLen < 1f) maxLen = 1f

        val degree = IntArray(n)
        for ((i, j) in sortedGabriel) { degree[i]++; degree[j]++ }
        val maxDegree = degree.max().coerceAtLeast(1)

        // Determine visible edges for animation
        val visibleCount = when (animMode) {
            "build" -> ((time * speed * sortedGabriel.size * 0.5f).toInt()).coerceIn(0, sortedGabriel.size)
            else -> sortedGabriel.size
        }

        canvas.drawColor(Color.BLACK)

        // Draw Delaunay background if requested
        if (showDelaunay) {
            val faintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 0.5f
                color = Color.argb(30, 255, 255, 255)
            }
            for ((i, j) in delaunayList) {
                canvas.drawLine(px[i], py[i], px[j], py[j], faintPaint)
            }
        }

        // Draw Gabriel edges
        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = edgeWidth
            strokeCap = Paint.Cap.ROUND
        }
        val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 0.5f
        }

        val highlightIdx = if (animMode == "highlight-circles") {
            ((time * speed * 3f).toInt() % sortedGabriel.size.coerceAtLeast(1))
        } else -1

        for ((idx, edge) in sortedGabriel.withIndex()) {
            if (idx >= visibleCount) break
            val (i, j) = edge
            val dx = px[i] - px[j]; val dy = py[i] - py[j]
            val dist = sqrt(dx * dx + dy * dy)

            linePaint.color = when (colorMode) {
                "edge-length" -> palette.lerpColor((dist / maxLen).coerceIn(0f, 1f))
                "region" -> {
                    val mx = (px[i] + px[j]) / 2f; val my = (py[i] + py[j]) / 2f
                    val angle = kotlin.math.atan2(my - h / 2f, mx - w / 2f)
                    palette.lerpColor(((angle / (2f * kotlin.math.PI.toFloat())) + 0.5f).coerceIn(0f, 1f))
                }
                "degree" -> palette.lerpColor(degree[i].toFloat() / maxDegree)
                "random" -> colors[(i * 7 + j * 13) % colors.size]
                else -> palette.lerpColor(idx.toFloat() / sortedGabriel.size.coerceAtLeast(1))
            }
            canvas.drawLine(px[i], py[i], px[j], py[j], linePaint)

            if (showCircles || idx == highlightIdx) {
                val mx = (px[i] + px[j]) / 2f
                val my = (py[i] + py[j]) / 2f
                val r = dist / 2f
                val alpha = if (idx == highlightIdx) 180 else (circleOpacity * 255).toInt().coerceIn(5, 128)
                val c = linePaint.color
                circlePaint.color = Color.argb(alpha, Color.red(c), Color.green(c), Color.blue(c))
                canvas.drawCircle(mx, my, r, circlePaint)
            }
        }

        // Draw MST overlay
        if (showMST) {
            val mstEdges = computeMST(px, py, n, sortedGabriel)
            val mstPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = edgeWidth * 2f
                strokeCap = Paint.Cap.ROUND
                color = Color.WHITE
            }
            for ((i, j) in mstEdges) {
                canvas.drawLine(px[i], py[i], px[j], py[j], mstPaint)
            }
        }

        // Draw nodes
        val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        for (i in 0 until n) {
            dotPaint.color = when (colorMode) {
                "degree" -> palette.lerpColor(degree[i].toFloat() / maxDegree)
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
        val n = (params["pointCount"] as? Number)?.toInt() ?: 80
        val nodeSize = (params["nodeSize"] as? Number)?.toFloat() ?: 6f
        val edgeWidth = (params["edgeWidth"] as? Number)?.toFloat() ?: 2f
        val colorMode = (params["colorMode"] as? String) ?: "edge-length"
        val distribution = (params["distribution"] as? String) ?: "random"

        val rng = SeededRNG(seed)
        val colors = palette.colorInts()
        val margin = w * 0.08f
        val px = FloatArray(n); val py = FloatArray(n)
        generatePoints(rng, px, py, n, w, h, margin, distribution)

        val pxExt = FloatArray(n + 3); val pyExt = FloatArray(n + 3)
        System.arraycopy(px, 0, pxExt, 0, n)
        System.arraycopy(py, 0, pyExt, 0, n)
        val m = maxOf(w, h) * 3f
        pxExt[n] = -m; pyExt[n] = -m
        pxExt[n + 1] = w / 2f; pyExt[n + 1] = h + m * 2f
        pxExt[n + 2] = w + m * 2f; pyExt[n + 2] = -m

        val triangles = bowyerWatson(pxExt, pyExt, n)
        val edgeSet = mutableSetOf<Long>()
        for (tri in triangles) {
            edgeSet.add(packEdge(minOf(tri.a, tri.b), maxOf(tri.a, tri.b)))
            edgeSet.add(packEdge(minOf(tri.b, tri.c), maxOf(tri.b, tri.c)))
            edgeSet.add(packEdge(minOf(tri.c, tri.a), maxOf(tri.c, tri.a)))
        }
        val delaunayList = edgeSet.map { Pair(((it shr 16) and 0xFFFF).toInt(), (it and 0xFFFF).toInt()) }
        val gabrielEdges = delaunayList.filter { (i, j) -> isGabrielEdge(px, py, n, i, j) }

        var maxLen = 0f
        for ((i, j) in gabrielEdges) {
            val dx = px[i] - px[j]; val dy = py[i] - py[j]
            val d = sqrt(dx * dx + dy * dy)
            if (d > maxLen) maxLen = d
        }
        if (maxLen < 1f) maxLen = 1f

        val paths = mutableListOf<SvgPath>()
        for ((idx, edge) in gabrielEdges.withIndex()) {
            val (i, j) = edge
            val dx = px[i] - px[j]; val dy = py[i] - py[j]
            val dist = sqrt(dx * dx + dy * dy)
            val colorInt = when (colorMode) {
                "edge-length" -> palette.lerpColor((dist / maxLen).coerceIn(0f, 1f))
                else -> palette.lerpColor(idx.toFloat() / gabrielEdges.size.coerceAtLeast(1))
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

    private fun isGabrielEdge(px: FloatArray, py: FloatArray, n: Int, i: Int, j: Int): Boolean {
        val mx = (px[i] + px[j]) / 2f
        val my = (py[i] + py[j]) / 2f
        val dx = px[i] - px[j]; val dy = py[i] - py[j]
        val rSq = (dx * dx + dy * dy) / 4f
        for (k in 0 until n) {
            if (k == i || k == j) continue
            val dkx = px[k] - mx; val dky = py[k] - my
            if (dkx * dkx + dky * dky < rSq) return false
        }
        return true
    }

    private fun generatePoints(
        rng: SeededRNG, px: FloatArray, py: FloatArray,
        n: Int, w: Float, h: Float, margin: Float, distribution: String
    ) {
        when (distribution) {
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
                        idx++
                    }
                }
                for (i in idx until n) { px[i] = rng.range(margin, w - margin); py[i] = rng.range(margin, h - margin) }
            }
            "clustered" -> {
                val clusters = 4
                val cx = FloatArray(clusters) { rng.range(margin * 2, w - margin * 2) }
                val cy = FloatArray(clusters) { rng.range(margin * 2, h - margin * 2) }
                val spread = w * 0.12f
                for (i in 0 until n) {
                    val c = i % clusters
                    px[i] = (cx[c] + (rng.random() - 0.5f) * spread * 2f).coerceIn(margin, w - margin)
                    py[i] = (cy[c] + (rng.random() - 0.5f) * spread * 2f).coerceIn(margin, h - margin)
                }
            }
            "gaussian" -> {
                for (i in 0 until n) {
                    // Box-Muller transform for gaussian
                    val u1 = rng.random().coerceAtLeast(0.001f)
                    val u2 = rng.random()
                    val z = sqrt(-2f * kotlin.math.ln(u1)) * kotlin.math.cos(2f * kotlin.math.PI.toFloat() * u2)
                    px[i] = (w / 2f + z * w * 0.15f).coerceIn(margin, w - margin)
                    val v1 = rng.random().coerceAtLeast(0.001f)
                    val v2 = rng.random()
                    val z2 = sqrt(-2f * kotlin.math.ln(v1)) * kotlin.math.cos(2f * kotlin.math.PI.toFloat() * v2)
                    py[i] = (h / 2f + z2 * h * 0.15f).coerceIn(margin, h - margin)
                }
            }
            else -> {
                for (i in 0 until n) {
                    px[i] = rng.range(margin, w - margin)
                    py[i] = rng.range(margin, h - margin)
                }
            }
        }
    }

    private fun computeMST(px: FloatArray, py: FloatArray, n: Int, edges: List<Pair<Int, Int>>): List<Pair<Int, Int>> {
        if (edges.isEmpty()) return emptyList()
        data class Edge(val a: Int, val b: Int, val w: Float)
        val sorted = edges.map { (i, j) ->
            val dx = px[i] - px[j]; val dy = py[i] - py[j]
            Edge(i, j, sqrt(dx * dx + dy * dy))
        }.sortedBy { it.w }

        val parent = IntArray(n) { it }
        fun find(x: Int): Int { var r = x; while (parent[r] != r) r = parent[r]; var c = x; while (c != r) { val next = parent[c]; parent[c] = r; c = next }; return r }
        fun union(a: Int, b: Int): Boolean { val ra = find(a); val rb = find(b); if (ra == rb) return false; parent[ra] = rb; return true }

        val result = mutableListOf<Pair<Int, Int>>()
        for (e in sorted) {
            if (union(e.a, e.b)) {
                result.add(Pair(e.a, e.b))
                if (result.size == n - 1) break
            }
        }
        return result
    }

    private fun bowyerWatson(px: FloatArray, py: FloatArray, n: Int): List<Tri> {
        val triangles = mutableListOf(Tri(n, n + 1, n + 2))
        for (p in 0 until n) {
            val bad = mutableListOf<Tri>()
            for (tri in triangles) {
                if (inCircumcircle(px, py, tri.a, tri.b, tri.c, px[p], py[p])) bad.add(tri)
            }
            val edges = mutableListOf<Pair<Int, Int>>()
            for (tri in bad) {
                val te = listOf(Pair(tri.a, tri.b), Pair(tri.b, tri.c), Pair(tri.c, tri.a))
                for (edge in te) {
                    val shared = bad.any { other -> other !== tri && hasEdge(other, edge.first, edge.second) }
                    if (!shared) edges.add(edge)
                }
            }
            triangles.removeAll(bad)
            for (edge in edges) triangles.add(Tri(edge.first, edge.second, p))
        }
        triangles.removeAll { it.a >= n || it.b >= n || it.c >= n }
        return triangles
    }

    private fun hasEdge(tri: Tri, a: Int, b: Int): Boolean {
        val v = listOf(tri.a, tri.b, tri.c); return a in v && b in v
    }

    private fun inCircumcircle(px: FloatArray, py: FloatArray, a: Int, b: Int, c: Int, dx: Float, dy: Float): Boolean {
        val ax = px[a] - dx; val ay = py[a] - dy
        val bx = px[b] - dx; val by = py[b] - dy
        val cx = px[c] - dx; val cy = py[c] - dy
        val det = ax * (by * (cx * cx + cy * cy) - cy * (bx * bx + by * by)) -
                  ay * (bx * (cx * cx + cy * cy) - cx * (bx * bx + by * by)) +
                  (ax * ax + ay * ay) * (bx * cy - by * cx)
        val cross = (px[b] - px[a]) * (py[c] - py[a]) - (py[b] - py[a]) * (px[c] - px[a])
        return if (cross > 0) det > 0 else det < 0
    }

    private fun packEdge(a: Int, b: Int): Long = (a.toLong() shl 16) or b.toLong()

    private fun qualityScale(base: Int, quality: Quality): Int = when (quality) {
        Quality.DRAFT -> (base * 0.5f).toInt().coerceAtLeast(10)
        Quality.BALANCED -> base
        Quality.ULTRA -> (base * 1.5f).toInt()
    }

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float {
        val n = (params["pointCount"] as? Number)?.toFloat() ?: 80f
        return (n / 150f).coerceIn(0.2f, 1f)
    }
}
