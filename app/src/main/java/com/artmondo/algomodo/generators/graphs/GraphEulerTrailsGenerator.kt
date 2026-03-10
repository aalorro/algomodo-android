package com.artmondo.algomodo.generators.graphs

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
import com.artmondo.algomodo.rendering.SvgBuilder
import com.artmondo.algomodo.rendering.SvgPath
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

class GraphEulerTrailsGenerator : Generator {

    override val id = "graph-euler-trails"
    override val family = "graphs"
    override val styleName = "Euler Trails"
    override val definition =
        "Visualisation of Eulerian paths through graphs — continuous trails traversing every edge exactly once, producing intricate looping patterns."
    override val algorithmNotes =
        "A graph is generated and Eulerianized by adding parallel edges to make all vertex degrees even. " +
        "Hierholzer's algorithm finds an Euler circuit. The trail is rendered as a coloured path."
    override val supportsVector = true
    override val supportsAnimation = true

    override val parameterSchema = listOf(
        Parameter.NumberParam("Vertex Count", "vertexCount", ParamGroup.COMPOSITION, null, 6f, 40f, 1f, 15f),
        Parameter.SelectParam("Graph Type", "graphType", ParamGroup.COMPOSITION, null, listOf("random-dense", "grid", "wheel", "petersen", "complete", "prism"), "random-dense"),
        Parameter.NumberParam("Edge Density", "edgeDensity", ParamGroup.GEOMETRY, "Fraction of possible edges (random-dense only)", 0.3f, 1f, 0.05f, 0.6f),
        Parameter.NumberParam("Trail Width", "trailWidth", ParamGroup.GEOMETRY, null, 1f, 10f, 0.5f, 4f),
        Parameter.BooleanParam("Taper Trail", "taperTrail", ParamGroup.TEXTURE, "Trail width decreases along path", false),
        Parameter.BooleanParam("Show Vertices", "showVertices", ParamGroup.GEOMETRY, null, true),
        Parameter.NumberParam("Vertex Size", "vertexSize", ParamGroup.GEOMETRY, null, 3f, 16f, 1f, 8f),
        Parameter.SelectParam("Color Mode", "colorMode", ParamGroup.COLOR, null, listOf("path-progress", "edge-index", "vertex-color", "constant"), "path-progress"),
        Parameter.SelectParam("Layout", "layout", ParamGroup.COMPOSITION, null, listOf("force-directed", "circular", "random", "grid"), "force-directed"),
        Parameter.SelectParam("Animation", "animMode", ParamGroup.FLOW_MOTION, null, listOf("none", "trace", "pulse", "fade-in"), "trace"),
        Parameter.NumberParam("Speed", "speed", ParamGroup.FLOW_MOTION, null, 0.05f, 1f, 0.05f, 0.3f)
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "vertexCount" to 15f,
        "graphType" to "random-dense",
        "edgeDensity" to 0.6f,
        "trailWidth" to 4f,
        "taperTrail" to false,
        "showVertices" to true,
        "vertexSize" to 8f,
        "colorMode" to "path-progress",
        "layout" to "force-directed",
        "animMode" to "trace",
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
        canvas.drawColor(Color.BLACK)

        val vertexCount = ((params["vertexCount"] as? Number)?.toInt() ?: 15).coerceIn(3, 40)
        val graphType = (params["graphType"] as? String) ?: "random-dense"
        val edgeDensity = (params["edgeDensity"] as? Number)?.toFloat() ?: 0.6f
        val trailWidth = (params["trailWidth"] as? Number)?.toFloat() ?: 4f
        val taperTrail = (params["taperTrail"] as? Boolean) ?: false
        val showVertices = (params["showVertices"] as? Boolean) ?: true
        val vertexSize = (params["vertexSize"] as? Number)?.toFloat() ?: 8f
        val colorMode = (params["colorMode"] as? String) ?: "path-progress"
        val layout = (params["layout"] as? String) ?: "force-directed"
        val animMode = (params["animMode"] as? String) ?: "trace"
        val speed = (params["speed"] as? Number)?.toFloat() ?: 0.3f

        val colors = palette.colorInts()
        if (colors.isEmpty()) return

        val n = if (graphType == "petersen") 10 else vertexCount

        // Layout vertices with dedicated RNG so they always render
        val layoutRng = SeededRNG(seed xor 0x7F3A)
        val margin = w * 0.12f
        val vx = FloatArray(n)
        val vy = FloatArray(n)
        layoutVertices(layoutRng, vx, vy, n, w, h, margin, layout)

        // Build graph and find Euler trail
        val trail = buildAndSolve(SeededRNG(seed), n, graphType, edgeDensity)

        // Draw trail edges
        if (trail.size >= 2) {
            val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }

            val totalEdges = trail.size - 1
            val visibleEdges = when (animMode) {
                "trace" -> (time * speed * totalEdges * 0.5f).toInt().coerceIn(0, totalEdges)
                else -> totalEdges
            }
            val pulsePhase = if (animMode == "pulse") (time * speed * 2f) % 1f else 0f

            for (i in 0 until visibleEdges) {
                val from = trail[i]
                val to = trail[i + 1]
                if (from !in 0 until n || to !in 0 until n) continue
                val t = i.toFloat() / totalEdges

                val baseColor = when (colorMode) {
                    "path-progress" -> palette.lerpColor(t)
                    "edge-index" -> colors[i % colors.size]
                    "vertex-color" -> colors[from % colors.size]
                    "constant" -> colors[0]
                    else -> palette.lerpColor(t)
                }

                var alpha = 255
                if (animMode == "fade-in") {
                    alpha = ((time * speed * totalEdges * 0.5f - i).coerceIn(0f, 1f) * 255).toInt().coerceIn(20, 255)
                }
                if (animMode == "pulse") {
                    alpha = ((1f - abs(t - pulsePhase) * 5f).coerceIn(0.3f, 1f) * 255).toInt()
                }

                linePaint.color = Color.argb(alpha, Color.red(baseColor), Color.green(baseColor), Color.blue(baseColor))
                linePaint.strokeWidth = if (taperTrail) trailWidth * (1f - t * 0.8f) else trailWidth
                canvas.drawLine(vx[from], vy[from], vx[to], vy[to], linePaint)
            }
        }

        // Always draw vertices
        if (showVertices) {
            val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
            val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; strokeWidth = 1.5f; color = Color.WHITE
            }
            for (i in 0 until n) {
                dotPaint.color = colors[i % colors.size]
                canvas.drawCircle(vx[i], vy[i], vertexSize, dotPaint)
                canvas.drawCircle(vx[i], vy[i], vertexSize, outlinePaint)
            }
        }
    }

    override fun renderVector(
        params: Map<String, Any>,
        seed: Int,
        palette: Palette
    ): List<SvgPath> {
        val w = 1080f; val h = 1080f
        val vertexCount = ((params["vertexCount"] as? Number)?.toInt() ?: 15).coerceIn(3, 40)
        val graphType = (params["graphType"] as? String) ?: "random-dense"
        val edgeDensity = (params["edgeDensity"] as? Number)?.toFloat() ?: 0.6f
        val trailWidth = (params["trailWidth"] as? Number)?.toFloat() ?: 4f
        val showVertices = (params["showVertices"] as? Boolean) ?: true
        val vertexSize = (params["vertexSize"] as? Number)?.toFloat() ?: 8f
        val layout = (params["layout"] as? String) ?: "force-directed"

        val colors = palette.colorInts()
        val n = if (graphType == "petersen") 10 else vertexCount

        val layoutRng = SeededRNG(seed xor 0x7F3A)
        val margin = w * 0.12f
        val vx = FloatArray(n); val vy = FloatArray(n)
        layoutVertices(layoutRng, vx, vy, n, w, h, margin, layout)

        val trail = buildAndSolve(SeededRNG(seed), n, graphType, edgeDensity)
        val paths = mutableListOf<SvgPath>()

        if (trail.size >= 2) {
            val totalEdges = trail.size - 1
            for (i in 0 until totalEdges) {
                val from = trail[i]; val to = trail[i + 1]
                if (from !in 0 until n || to !in 0 until n) continue
                val t = i.toFloat() / totalEdges
                val colorInt = palette.lerpColor(t)
                val hex = String.format("#%06X", 0xFFFFFF and colorInt)
                val d = "${SvgBuilder.moveTo(vx[from], vy[from])} ${SvgBuilder.lineTo(vx[to], vy[to])}"
                paths.add(SvgPath(d = d, stroke = hex, strokeWidth = trailWidth))
            }
        }

        if (showVertices) {
            for (i in 0 until n) {
                val hex = String.format("#%06X", 0xFFFFFF and colors[i % colors.size])
                paths.add(SvgPath(d = SvgBuilder.circle(vx[i], vy[i], vertexSize), fill = hex, stroke = "#FFFFFF", strokeWidth = 1.5f))
            }
        }
        return paths
    }

    /**
     * Builds the graph, ensures connectivity and even degrees, finds Euler trail.
     * Returns the trail as a list of vertex indices, or empty on failure.
     */
    private fun buildAndSolve(rng: SeededRNG, n: Int, graphType: String, density: Float): List<Int> {
        // Build adjacency using a Map (matches web version's Map<number, number[]>)
        val adj = HashMap<Int, MutableList<Int>>(n)
        for (i in 0 until n) adj[i] = mutableListOf()

        // Generate edges based on graph type
        when (graphType) {
            "complete" -> {
                for (i in 0 until n) for (j in i + 1 until n) addEdge(adj, i, j)
            }
            "grid" -> {
                val cols = sqrt(n.toFloat()).toInt().coerceIn(2, n)
                for (r in 0 until n) {
                    val c = r % cols
                    if (c + 1 < cols && r + 1 < n) addEdge(adj, r, r + 1)
                    if (r + cols < n) addEdge(adj, r, r + cols)
                }
            }
            "wheel" -> {
                for (i in 1 until n) {
                    addEdge(adj, 0, i)
                    addEdge(adj, i, if (i + 1 < n) i + 1 else 1)
                }
            }
            "petersen" -> {
                for (i in 0 until 5) {
                    addEdge(adj, i, (i + 1) % 5)
                    addEdge(adj, i, i + 5)
                    addEdge(adj, i + 5, (i + 2) % 5 + 5)
                }
            }
            "prism" -> {
                val half = (n / 2).coerceAtLeast(2)
                for (i in 0 until half) {
                    val j = (i + 1) % half
                    addEdge(adj, i, j)
                    if (i + half < n && j + half < n) addEdge(adj, i + half, j + half)
                    if (i + half < n) addEdge(adj, i, i + half)
                }
            }
            else -> {
                for (i in 0 until n) for (j in i + 1 until n) {
                    if (rng.random() < density) addEdge(adj, i, j)
                }
            }
        }

        // Ensure connectivity via BFS from vertex 0
        val visited = BooleanArray(n)
        val queue = ArrayDeque<Int>()
        visited[0] = true
        queue.add(0)
        while (queue.isNotEmpty()) {
            val v = queue.removeFirst()
            for (u in adj[v]!!) {
                if (u in 0 until n && !visited[u]) { visited[u] = true; queue.add(u) }
            }
        }
        for (i in 1 until n) {
            if (!visited[i]) {
                addEdge(adj, 0, i)
                visited[i] = true
                queue.add(i)
                while (queue.isNotEmpty()) {
                    val v = queue.removeFirst()
                    for (u in adj[v]!!) {
                        if (u in 0 until n && !visited[u]) { visited[u] = true; queue.add(u) }
                    }
                }
            }
        }

        // Eulerianize: pair up odd-degree vertices and add parallel edges
        val oddVerts = mutableListOf<Int>()
        for (i in 0 until n) {
            if (adj[i]!!.size % 2 != 0) oddVerts.add(i)
        }
        rng.shuffle(oddVerts)
        var idx = 0
        while (idx + 1 < oddVerts.size) {
            addEdge(adj, oddVerts[idx], oddVerts[idx + 1])
            idx += 2
        }

        // Hierholzer's algorithm — direct port from web version
        return findEulerTrail(n, adj)
    }

    private fun addEdge(adj: HashMap<Int, MutableList<Int>>, u: Int, v: Int) {
        adj[u]!!.add(v)
        adj[v]!!.add(u)
    }

    /**
     * Hierholzer's algorithm to find an Euler circuit/trail.
     * Direct port from the web (TypeScript) version.
     */
    private fun findEulerTrail(n: Int, adj: HashMap<Int, MutableList<Int>>): List<Int> {
        // Find start vertex: prefer odd-degree, else any vertex with edges
        var start = -1
        for (i in 0 until n) {
            val edges = adj[i] ?: continue
            if (edges.isEmpty()) continue
            if (edges.size % 2 == 1) { start = i; break }
            if (start == -1) start = i
        }
        if (start == -1) return emptyList()

        // Deep copy adjacency for consumption
        val local = HashMap<Int, MutableList<Int>>(n)
        for ((k, v) in adj) local[k] = v.toMutableList()

        val stack = mutableListOf(start)
        val trail = mutableListOf<Int>()

        while (stack.isNotEmpty()) {
            val v = stack.last()
            val edges = local[v]
            if (edges != null && edges.isNotEmpty()) {
                val u = edges.removeAt(edges.lastIndex)
                // Remove the reverse edge
                val uEdges = local[u]
                if (uEdges != null) {
                    val ri = uEdges.indexOf(v)
                    if (ri != -1) uEdges.removeAt(ri)
                }
                stack.add(u)
            } else {
                trail.add(stack.removeAt(stack.lastIndex))
            }
        }

        return trail
    }

    // ── Vertex layout ──────────────────────────────────────────────────────

    private fun layoutVertices(
        rng: SeededRNG, vx: FloatArray, vy: FloatArray,
        n: Int, w: Float, h: Float, margin: Float, layout: String
    ) {
        when (layout) {
            "circular" -> {
                val cx = w / 2f; val cy = h / 2f
                val r = min(w, h) / 2f - margin
                for (i in 0 until n) {
                    val angle = 2f * PI.toFloat() * i / n
                    vx[i] = cx + cos(angle) * r
                    vy[i] = cy + sin(angle) * r
                }
            }
            "grid" -> {
                val cols = sqrt(n.toFloat()).toInt().coerceAtLeast(2)
                val rows = ((n + cols - 1) / cols).coerceAtLeast(2)
                val cellW = (w - margin * 2) / (cols - 1).coerceAtLeast(1)
                val cellH = (h - margin * 2) / (rows - 1).coerceAtLeast(1)
                for (i in 0 until n) {
                    vx[i] = margin + (i % cols) * cellW
                    vy[i] = margin + (i / cols) * cellH
                }
            }
            "random" -> {
                for (i in 0 until n) {
                    vx[i] = rng.range(margin, w - margin)
                    vy[i] = rng.range(margin, h - margin)
                }
            }
            else -> { // force-directed
                for (i in 0 until n) {
                    vx[i] = rng.range(margin, w - margin)
                    vy[i] = rng.range(margin, h - margin)
                }
                val k = sqrt((w - margin * 2) * (h - margin * 2) / n.coerceAtLeast(1).toFloat()).coerceAtLeast(1f)

                for (iter in 0 until 50) {
                    val cooling = 1f - iter / 50f
                    val dx = FloatArray(n); val dy = FloatArray(n)
                    for (i in 0 until n) for (j in i + 1 until n) {
                        val ddx = vx[i] - vx[j]; val ddy = vy[i] - vy[j]
                        val dist = sqrt(ddx * ddx + ddy * ddy).coerceAtLeast(1f)
                        val force = (k * k / dist).coerceAtMost(k * 5f)
                        val nx = ddx / dist; val ny = ddy / dist
                        dx[i] += nx * force; dy[i] += ny * force
                        dx[j] -= nx * force; dy[j] -= ny * force
                    }
                    val maxDisp = k * cooling.coerceAtLeast(0.05f)
                    for (i in 0 until n) {
                        val len = sqrt(dx[i] * dx[i] + dy[i] * dy[i]).coerceAtLeast(1f)
                        val s = min(len, maxDisp) / len
                        vx[i] = (vx[i] + dx[i] * s).coerceIn(margin, w - margin)
                        vy[i] = (vy[i] + dy[i] * s).coerceIn(margin, h - margin)
                    }
                }
            }
        }
    }

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float {
        val n = (params["vertexCount"] as? Number)?.toFloat() ?: 15f
        return (n / 20f).coerceIn(0.2f, 1f)
    }
}
