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
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Random planar graph generator.
 *
 * Generates vertices, computes Delaunay triangulation (always planar), then
 * randomly removes edges to target density. Faces are extracted by walking
 * edges counterclockwise and coloured using a greedy graph-colouring algorithm
 * inspired by the four-colour theorem. Optional dual graph overlay.
 */
class GraphPlanarGenerator : Generator {

    override val id = "graph-planar"
    override val family = "graphs"
    override val styleName = "Planar Graph"
    override val definition =
        "Random planar graph with no crossing edges, featuring face colouring inspired by the four-colour theorem and optional dual graph overlay."
    override val algorithmNotes =
        "Vertices are placed with the chosen layout and Bowyer-Watson Delaunay triangulation ensures planarity. " +
        "Edges are randomly removed to reach the target density while maintaining connectivity. Faces are extracted " +
        "by walking the half-edge structure counterclockwise. A greedy graph-colouring algorithm assigns palette " +
        "colours to faces such that no two adjacent faces share a colour. The dual graph connects face centroids " +
        "through shared edges."
    override val supportsVector = true
    override val supportsAnimation = true

    override val parameterSchema = listOf(
        Parameter.NumberParam("Vertex Count", "vertexCount", ParamGroup.COMPOSITION, null, 10f, 300f, 5f, 60f),
        Parameter.SelectParam("Layout", "layout", ParamGroup.COMPOSITION, null, listOf("random", "organic", "grid-jittered", "concentric", "spiral"), "organic"),
        Parameter.NumberParam("Edge Density", "edgeDensity", ParamGroup.GEOMETRY, "Fraction of Delaunay edges to keep (1.0 = maximal)", 0.3f, 1f, 0.05f, 0.7f),
        Parameter.BooleanParam("Color Faces", "colorFaces", ParamGroup.TEXTURE, "Fill graph faces with palette colours", true),
        Parameter.NumberParam("Face Opacity", "faceOpacity", ParamGroup.TEXTURE, null, 0.2f, 1f, 0.05f, 0.7f),
        Parameter.NumberParam("Edge Width", "edgeWidth", ParamGroup.GEOMETRY, null, 0.5f, 5f, 0.5f, 2f),
        Parameter.NumberParam("Vertex Size", "vertexSize", ParamGroup.GEOMETRY, null, 0f, 12f, 1f, 5f),
        Parameter.BooleanParam("Show Dual", "showDual", ParamGroup.GEOMETRY, "Overlay dual graph (face adjacency)", false),
        Parameter.SelectParam("Color Mode", "colorMode", ParamGroup.COLOR, null, listOf("four-color", "palette-cycle", "centroid-radial", "random"), "four-color"),
        Parameter.SelectParam("Animation", "animMode", ParamGroup.FLOW_MOTION, null, listOf("none", "edge-grow", "face-fill", "spring"), "face-fill"),
        Parameter.NumberParam("Speed", "speed", ParamGroup.FLOW_MOTION, null, 0.05f, 1f, 0.05f, 0.3f)
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "vertexCount" to 60f,
        "layout" to "organic",
        "edgeDensity" to 0.7f,
        "colorFaces" to true,
        "faceOpacity" to 0.7f,
        "edgeWidth" to 2f,
        "vertexSize" to 5f,
        "showDual" to false,
        "colorMode" to "four-color",
        "animMode" to "face-fill",
        "speed" to 0.3f
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
        val n = qualityScale((params["vertexCount"] as? Number)?.toInt() ?: 60, quality)
        val layout = (params["layout"] as? String) ?: "organic"
        val edgeDensity = (params["edgeDensity"] as? Number)?.toFloat() ?: 0.7f
        val colorFaces = (params["colorFaces"] as? Boolean) ?: true
        val faceOpacity = (params["faceOpacity"] as? Number)?.toFloat() ?: 0.7f
        val edgeWidth = (params["edgeWidth"] as? Number)?.toFloat() ?: 2f
        val vertexSize = (params["vertexSize"] as? Number)?.toFloat() ?: 5f
        val showDual = (params["showDual"] as? Boolean) ?: false
        val colorMode = (params["colorMode"] as? String) ?: "four-color"
        val animMode = (params["animMode"] as? String) ?: "face-fill"
        val speed = (params["speed"] as? Number)?.toFloat() ?: 0.3f

        val rng = SeededRNG(seed)
        val noise = SimplexNoise(seed)
        val colors = palette.colorInts()
        val margin = w * 0.1f

        // Generate and layout vertices
        val px = FloatArray(n); val py = FloatArray(n)
        layoutVertices(rng, px, py, n, w, h, margin, layout)

        // Spring animation
        if (animMode == "spring" && time > 0f) {
            for (i in 0 until n) {
                val phase = i * 0.3f + time * speed * 3f
                px[i] += sin(phase) * 3f
                py[i] += cos(phase * 1.3f) * 3f
                px[i] = px[i].coerceIn(margin * 0.5f, w - margin * 0.5f)
                py[i] = py[i].coerceIn(margin * 0.5f, h - margin * 0.5f)
            }
        }

        // Delaunay triangulation
        val pxExt = FloatArray(n + 3); val pyExt = FloatArray(n + 3)
        System.arraycopy(px, 0, pxExt, 0, n)
        System.arraycopy(py, 0, pyExt, 0, n)
        val m = maxOf(w, h) * 3f
        pxExt[n] = -m; pyExt[n] = -m
        pxExt[n + 1] = w / 2f; pyExt[n + 1] = h + m * 2f
        pxExt[n + 2] = w + m * 2f; pyExt[n + 2] = -m
        val triangles = bowyerWatson(pxExt, pyExt, n)

        // Extract unique edges and randomly thin
        val allEdges = mutableSetOf<Long>()
        for (tri in triangles) {
            allEdges.add(packEdge(minOf(tri.a, tri.b), maxOf(tri.a, tri.b)))
            allEdges.add(packEdge(minOf(tri.b, tri.c), maxOf(tri.b, tri.c)))
            allEdges.add(packEdge(minOf(tri.c, tri.a), maxOf(tri.c, tri.a)))
        }
        val edgeList = allEdges.map { Pair(((it shr 16) and 0xFFFF).toInt(), (it and 0xFFFF).toInt()) }.toMutableList()

        // Remove edges to target density while keeping graph connected
        val targetEdges = (edgeList.size * edgeDensity).toInt().coerceAtLeast(n - 1)
        if (edgeList.size > targetEdges) {
            // Shuffle edges for random removal
            for (i in edgeList.indices) {
                val j = (rng.random() * edgeList.size).toInt().coerceIn(0, edgeList.lastIndex)
                val tmp = edgeList[i]; edgeList[i] = edgeList[j]; edgeList[j] = tmp
            }
            val kept = mutableListOf<Pair<Int, Int>>()
            val removed = mutableListOf<Pair<Int, Int>>()
            // First, compute MST to guarantee connectivity
            val mstEdges = computeMST(px, py, n, edgeList).toSet()
            for (e in edgeList) {
                if (mstEdges.contains(e) || mstEdges.contains(Pair(e.second, e.first))) {
                    kept.add(e) // MST edges must stay
                } else {
                    removed.add(e)
                }
            }
            // Add non-MST edges up to target
            val remaining = targetEdges - kept.size
            for (i in 0 until min(remaining, removed.size)) {
                kept.add(removed[i])
            }
            edgeList.clear()
            edgeList.addAll(kept)
        }

        // Extract faces from triangles that still have all 3 edges present
        val edgeSet = edgeList.map { packEdge(minOf(it.first, it.second), maxOf(it.first, it.second)) }.toSet()
        val faces = mutableListOf<List<Int>>()
        for (tri in triangles) {
            val e1 = packEdge(minOf(tri.a, tri.b), maxOf(tri.a, tri.b))
            val e2 = packEdge(minOf(tri.b, tri.c), maxOf(tri.b, tri.c))
            val e3 = packEdge(minOf(tri.c, tri.a), maxOf(tri.c, tri.a))
            if (e1 in edgeSet && e2 in edgeSet && e3 in edgeSet) {
                faces.add(listOf(tri.a, tri.b, tri.c))
            }
        }

        // Graph-colour faces (greedy)
        val faceColors = IntArray(faces.size)
        val faceAdj = buildFaceAdjacency(faces)
        for (i in faces.indices) {
            val usedColors = mutableSetOf<Int>()
            for (adj in (faceAdj[i] ?: emptyList())) {
                if (adj < i) usedColors.add(faceColors[adj])
            }
            var c = 0
            while (c in usedColors) c++
            faceColors[i] = c
        }

        // Compute face centroids for dual graph and animation ordering
        val faceCx = FloatArray(faces.size)
        val faceCy = FloatArray(faces.size)
        for (i in faces.indices) {
            var sx = 0f; var sy = 0f
            for (v in faces[i]) { sx += px[v]; sy += py[v] }
            faceCx[i] = sx / faces[i].size
            faceCy[i] = sy / faces[i].size
        }

        val visibleFaces = when (animMode) {
            "face-fill" -> ((time * speed * faces.size * 0.5f).toInt()).coerceIn(0, faces.size)
            else -> faces.size
        }

        canvas.drawColor(Color.BLACK)

        // Draw faces
        if (colorFaces) {
            val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
            val alpha = (faceOpacity * 255).toInt().coerceIn(10, 255)
            for (i in 0 until visibleFaces) {
                val face = faces[i]
                val baseColor = when (colorMode) {
                    "four-color" -> colors[faceColors[i] % colors.size]
                    "palette-cycle" -> colors[i % colors.size]
                    "centroid-radial" -> {
                        val d = sqrt((faceCx[i] - w / 2f).let { it * it } + (faceCy[i] - h / 2f).let { it * it })
                        palette.lerpColor((d / (sqrt(w * w + h * h) / 2f)).coerceIn(0f, 1f))
                    }
                    "random" -> colors[(face[0] * 7 + face[1] * 13 + face[2] * 23) % colors.size]
                    else -> colors[faceColors[i] % colors.size]
                }
                fillPaint.color = Color.argb(alpha, Color.red(baseColor), Color.green(baseColor), Color.blue(baseColor))
                val path = Path().apply {
                    moveTo(px[face[0]], py[face[0]])
                    for (v in 1 until face.size) lineTo(px[face[v]], py[face[v]])
                    close()
                }
                canvas.drawPath(path, fillPaint)
            }
        }

        // Draw edges
        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = edgeWidth
            strokeCap = Paint.Cap.ROUND
            color = Color.argb(200, 220, 220, 220)
        }

        val visibleEdges = when (animMode) {
            "edge-grow" -> ((time * speed * edgeList.size * 0.5f).toInt()).coerceIn(0, edgeList.size)
            else -> edgeList.size
        }
        for (i in 0 until visibleEdges) {
            val (a, b) = edgeList[i]
            canvas.drawLine(px[a], py[a], px[b], py[b], linePaint)
        }

        // Draw dual graph
        if (showDual) {
            val dualPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 1f
                color = Color.argb(120, 255, 200, 50)
            }
            val dualDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = Color.argb(180, 255, 200, 50)
            }
            for (i in faces.indices) {
                for (j in (faceAdj[i] ?: emptyList())) {
                    if (j > i) {
                        canvas.drawLine(faceCx[i], faceCy[i], faceCx[j], faceCy[j], dualPaint)
                    }
                }
                canvas.drawCircle(faceCx[i], faceCy[i], 3f, dualDotPaint)
            }
        }

        // Draw vertices
        if (vertexSize > 0f) {
            val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Color.WHITE }
            for (i in 0 until n) canvas.drawCircle(px[i], py[i], vertexSize, dotPaint)
        }
    }

    override fun renderVector(
        params: Map<String, Any>,
        seed: Int,
        palette: Palette
    ): List<SvgPath> {
        val w = 1080f; val h = 1080f
        val n = (params["vertexCount"] as? Number)?.toInt() ?: 60
        val layout = (params["layout"] as? String) ?: "organic"
        val edgeDensity = (params["edgeDensity"] as? Number)?.toFloat() ?: 0.7f
        val colorFaces = (params["colorFaces"] as? Boolean) ?: true
        val edgeWidth = (params["edgeWidth"] as? Number)?.toFloat() ?: 2f
        val vertexSize = (params["vertexSize"] as? Number)?.toFloat() ?: 5f
        val colorMode = (params["colorMode"] as? String) ?: "four-color"

        val rng = SeededRNG(seed)
        val colors = palette.colorInts()
        val margin = w * 0.1f
        val px = FloatArray(n); val py = FloatArray(n)
        layoutVertices(rng, px, py, n, w, h, margin, layout)

        val pxExt = FloatArray(n + 3); val pyExt = FloatArray(n + 3)
        System.arraycopy(px, 0, pxExt, 0, n); System.arraycopy(py, 0, pyExt, 0, n)
        val m = maxOf(w, h) * 3f
        pxExt[n] = -m; pyExt[n] = -m; pxExt[n + 1] = w / 2f; pyExt[n + 1] = h + m * 2f; pxExt[n + 2] = w + m * 2f; pyExt[n + 2] = -m
        val triangles = bowyerWatson(pxExt, pyExt, n)

        val allEdges = mutableSetOf<Long>()
        for (tri in triangles) {
            allEdges.add(packEdge(minOf(tri.a, tri.b), maxOf(tri.a, tri.b)))
            allEdges.add(packEdge(minOf(tri.b, tri.c), maxOf(tri.b, tri.c)))
            allEdges.add(packEdge(minOf(tri.c, tri.a), maxOf(tri.c, tri.a)))
        }
        val edgeList = allEdges.map { Pair(((it shr 16) and 0xFFFF).toInt(), (it and 0xFFFF).toInt()) }.toMutableList()

        val targetEdges = (edgeList.size * edgeDensity).toInt().coerceAtLeast(n - 1)
        if (edgeList.size > targetEdges) {
            for (i in edgeList.indices) {
                val j = (rng.random() * edgeList.size).toInt().coerceIn(0, edgeList.lastIndex)
                val tmp = edgeList[i]; edgeList[i] = edgeList[j]; edgeList[j] = tmp
            }
            val mstEdges = computeMST(px, py, n, edgeList).toSet()
            val kept = mutableListOf<Pair<Int, Int>>()
            val removed = mutableListOf<Pair<Int, Int>>()
            for (e in edgeList) {
                if (mstEdges.contains(e) || mstEdges.contains(Pair(e.second, e.first))) kept.add(e) else removed.add(e)
            }
            val rem = targetEdges - kept.size
            for (i in 0 until min(rem, removed.size)) kept.add(removed[i])
            edgeList.clear(); edgeList.addAll(kept)
        }

        val edgeSet = edgeList.map { packEdge(minOf(it.first, it.second), maxOf(it.first, it.second)) }.toSet()
        val faces = mutableListOf<List<Int>>()
        for (tri in triangles) {
            val e1 = packEdge(minOf(tri.a, tri.b), maxOf(tri.a, tri.b))
            val e2 = packEdge(minOf(tri.b, tri.c), maxOf(tri.b, tri.c))
            val e3 = packEdge(minOf(tri.c, tri.a), maxOf(tri.c, tri.a))
            if (e1 in edgeSet && e2 in edgeSet && e3 in edgeSet) faces.add(listOf(tri.a, tri.b, tri.c))
        }

        val faceColors = IntArray(faces.size)
        val faceAdj = buildFaceAdjacency(faces)
        for (i in faces.indices) {
            val used = mutableSetOf<Int>()
            for (adj in (faceAdj[i] ?: emptyList())) { if (adj < i) used.add(faceColors[adj]) }
            var c = 0; while (c in used) c++; faceColors[i] = c
        }

        val paths = mutableListOf<SvgPath>()
        if (colorFaces) {
            for (i in faces.indices) {
                val face = faces[i]
                val colorInt = when (colorMode) {
                    "four-color" -> colors[faceColors[i] % colors.size]
                    else -> colors[i % colors.size]
                }
                val hex = String.format("#%06X", 0xFFFFFF and colorInt)
                val d = SvgBuilder.polygon(face.map { Pair(px[it], py[it]) })
                paths.add(SvgPath(d = d, fill = hex, stroke = "#DCDCDC", strokeWidth = edgeWidth))
            }
        }
        for ((a, b) in edgeList) {
            val d = "${SvgBuilder.moveTo(px[a], py[a])} ${SvgBuilder.lineTo(px[b], py[b])}"
            paths.add(SvgPath(d = d, stroke = "#DCDCDCC8", strokeWidth = edgeWidth))
        }
        if (vertexSize > 0f) {
            for (i in 0 until n) paths.add(SvgPath(d = SvgBuilder.circle(px[i], py[i], vertexSize), fill = "#FFFFFF"))
        }
        return paths
    }

    private fun buildFaceAdjacency(faces: List<List<Int>>): Map<Int, List<Int>> {
        val edgeToFace = mutableMapOf<Long, MutableList<Int>>()
        for (i in faces.indices) {
            val f = faces[i]
            for (j in f.indices) {
                val a = f[j]; val b = f[(j + 1) % f.size]
                val key = packEdge(minOf(a, b), maxOf(a, b))
                edgeToFace.getOrPut(key) { mutableListOf() }.add(i)
            }
        }
        val adj = mutableMapOf<Int, MutableList<Int>>()
        for ((_, faceIds) in edgeToFace) {
            if (faceIds.size == 2) {
                adj.getOrPut(faceIds[0]) { mutableListOf() }.add(faceIds[1])
                adj.getOrPut(faceIds[1]) { mutableListOf() }.add(faceIds[0])
            }
        }
        return adj
    }

    private fun layoutVertices(
        rng: SeededRNG, px: FloatArray, py: FloatArray,
        n: Int, w: Float, h: Float, margin: Float, layout: String
    ) {
        when (layout) {
            "grid-jittered" -> {
                val cols = sqrt(n.toFloat() * w / h).toInt().coerceAtLeast(3)
                val rows = (n / cols).coerceAtLeast(3)
                val cellW = (w - margin * 2) / (cols - 1)
                val cellH = (h - margin * 2) / (rows - 1)
                var idx = 0
                for (r in 0 until rows) for (c in 0 until cols) {
                    if (idx >= n) break
                    px[idx] = (margin + c * cellW + (rng.random() - 0.5f) * cellW * 0.5f).coerceIn(margin, w - margin)
                    py[idx] = (margin + r * cellH + (rng.random() - 0.5f) * cellH * 0.5f).coerceIn(margin, h - margin)
                    idx++
                }
                for (i in idx until n) { px[i] = rng.range(margin, w - margin); py[i] = rng.range(margin, h - margin) }
            }
            "concentric" -> {
                val rings = 5
                for (i in 0 until n) {
                    val ring = i % rings
                    val r = (ring + 1f) / rings * (min(w, h) / 2f - margin)
                    val angle = rng.random() * 2f * PI.toFloat()
                    px[i] = (w / 2f + cos(angle) * r).coerceIn(margin, w - margin)
                    py[i] = (h / 2f + sin(angle) * r).coerceIn(margin, h - margin)
                }
            }
            "spiral" -> {
                for (i in 0 until n) {
                    val t = i.toFloat() / n
                    val angle = t * 6f * PI.toFloat()
                    val r = t * (min(w, h) / 2f - margin)
                    px[i] = (w / 2f + cos(angle) * r).coerceIn(margin, w - margin)
                    py[i] = (h / 2f + sin(angle) * r).coerceIn(margin, h - margin)
                }
            }
            "organic" -> {
                // Random + force-directed relaxation
                for (i in 0 until n) { px[i] = rng.range(margin, w - margin); py[i] = rng.range(margin, h - margin) }
                val area = (w - margin * 2) * (h - margin * 2)
                val k = sqrt(area / n.coerceAtLeast(1).toFloat())
                for (iter in 0 until 60) {
                    val cooling = 1f - iter / 60f
                    val dx = FloatArray(n); val dy = FloatArray(n)
                    for (i in 0 until n) for (j in i + 1 until n) {
                        val ddx = px[i] - px[j]; val ddy = py[i] - py[j]
                        val dist = sqrt(ddx * ddx + ddy * ddy).coerceAtLeast(1f)
                        val force = k * k / dist
                        val fx = ddx / dist * force; val fy = ddy / dist * force
                        dx[i] += fx; dy[i] += fy; dx[j] -= fx; dy[j] -= fy
                    }
                    val maxDisp = k * cooling
                    for (i in 0 until n) {
                        val dist = sqrt(dx[i] * dx[i] + dy[i] * dy[i]).coerceAtLeast(1f)
                        val scale = min(dist, maxDisp) / dist
                        px[i] = (px[i] + dx[i] * scale).coerceIn(margin, w - margin)
                        py[i] = (py[i] + dy[i] * scale).coerceIn(margin, h - margin)
                    }
                }
            }
            else -> { // random
                for (i in 0 until n) { px[i] = rng.range(margin, w - margin); py[i] = rng.range(margin, h - margin) }
            }
        }
    }

    private fun computeMST(px: FloatArray, py: FloatArray, n: Int, edges: List<Pair<Int, Int>>): List<Pair<Int, Int>> {
        if (edges.isEmpty()) return emptyList()
        data class WEdge(val a: Int, val b: Int, val w: Float)
        val sorted = edges.map { (i, j) ->
            val dx = px[i] - px[j]; val dy = py[i] - py[j]
            WEdge(i, j, sqrt(dx * dx + dy * dy))
        }.sortedBy { it.w }
        val parent = IntArray(n) { it }
        fun find(x: Int): Int { var r = x; while (parent[r] != r) r = parent[r]; var c = x; while (c != r) { val nxt = parent[c]; parent[c] = r; c = nxt }; return r }
        fun union(a: Int, b: Int): Boolean { val ra = find(a); val rb = find(b); if (ra == rb) return false; parent[ra] = rb; return true }
        val result = mutableListOf<Pair<Int, Int>>()
        for (e in sorted) {
            if (union(e.a, e.b)) { result.add(Pair(e.a, e.b)); if (result.size == n - 1) break }
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
        Quality.DRAFT -> (base * 0.5f).toInt().coerceAtLeast(8)
        Quality.BALANCED -> base
        Quality.ULTRA -> (base * 1.5f).toInt()
    }

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float {
        val n = (params["vertexCount"] as? Number)?.toFloat() ?: 60f
        return (n / 150f).coerceIn(0.2f, 1f)
    }
}
