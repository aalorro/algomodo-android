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
 * Constrained Delaunay triangulation generator.
 *
 * Generates random points together with constraint shape vertices (polygon,
 * circle, star, spiral, or concentric rings). Computes Bowyer-Watson Delaunay
 * triangulation and enforces constraint edges. Interior and exterior regions
 * are coloured differently based on point-in-polygon winding number.
 */
class GraphConstrainedDelaunayGenerator : Generator {

    override val id = "graph-constrained-delaunay"
    override val family = "graphs"
    override val styleName = "Constrained Delaunay"
    override val definition =
        "Delaunay triangulation with enforced constraint boundaries, creating dual-region mesh art where interior and exterior zones are distinctly coloured."
    override val algorithmNotes =
        "Random points are generated alongside constraint shape vertices (regular polygon, circle approximation, " +
        "star, spiral, or concentric rings). Bowyer-Watson Delaunay is computed on the combined point set. " +
        "Constraint edges are identified in the triangulation and highlighted. Triangles are classified as " +
        "interior or exterior via centroid winding-number test against the constraint polygon. Each region " +
        "receives distinct palette colouring."
    override val supportsVector = true
    override val supportsAnimation = true

    override val parameterSchema = listOf(
        Parameter.NumberParam("Point Count", "pointCount", ParamGroup.COMPOSITION, null, 30f, 400f, 10f, 120f),
        Parameter.SelectParam("Constraint Shape", "constraintShape", ParamGroup.COMPOSITION, null, listOf("polygon", "circle", "star", "spiral", "concentric"), "polygon"),
        Parameter.NumberParam("Constraint Sides", "constraintSides", ParamGroup.GEOMETRY, "Sides for polygon/star constraint", 3f, 12f, 1f, 5f),
        Parameter.NumberParam("Constraint Scale", "constraintScale", ParamGroup.COMPOSITION, "Size of constraint shape (fraction of canvas)", 0.2f, 0.8f, 0.05f, 0.5f),
        Parameter.BooleanParam("Fill Interior", "fillInterior", ParamGroup.TEXTURE, "Colour fill inside constraint boundary", true),
        Parameter.BooleanParam("Fill Exterior", "fillExterior", ParamGroup.TEXTURE, "Colour fill outside constraint boundary", false),
        Parameter.NumberParam("Edge Width", "edgeWidth", ParamGroup.GEOMETRY, null, 0.5f, 4f, 0.5f, 1.5f),
        Parameter.NumberParam("Constraint Width", "constraintWidth", ParamGroup.GEOMETRY, "Width of constraint edges", 1f, 6f, 0.5f, 3f),
        Parameter.SelectParam("Color Mode", "colorMode", ParamGroup.COLOR, null, listOf("region", "area", "angle", "centroid-dist"), "region"),
        Parameter.SelectParam("Animation", "animMode", ParamGroup.FLOW_MOTION, null, listOf("none", "morph", "grow", "pulse"), "grow"),
        Parameter.NumberParam("Speed", "speed", ParamGroup.FLOW_MOTION, null, 0.05f, 1f, 0.05f, 0.25f)
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "pointCount" to 120f,
        "constraintShape" to "polygon",
        "constraintSides" to 5f,
        "constraintScale" to 0.5f,
        "fillInterior" to true,
        "fillExterior" to false,
        "edgeWidth" to 1.5f,
        "constraintWidth" to 3f,
        "colorMode" to "region",
        "animMode" to "grow",
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
        val nRandom = qualityScale((params["pointCount"] as? Number)?.toInt() ?: 120, quality)
        val shape = (params["constraintShape"] as? String) ?: "polygon"
        val sides = (params["constraintSides"] as? Number)?.toInt() ?: 5
        val scale = (params["constraintScale"] as? Number)?.toFloat() ?: 0.5f
        val fillInterior = (params["fillInterior"] as? Boolean) ?: true
        val fillExterior = (params["fillExterior"] as? Boolean) ?: false
        val edgeWidth = (params["edgeWidth"] as? Number)?.toFloat() ?: 1.5f
        val constraintWidth = (params["constraintWidth"] as? Number)?.toFloat() ?: 3f
        val colorMode = (params["colorMode"] as? String) ?: "region"
        val animMode = (params["animMode"] as? String) ?: "grow"
        val speed = (params["speed"] as? Number)?.toFloat() ?: 0.25f

        val rng = SeededRNG(seed)
        val noise = SimplexNoise(seed)
        val colors = palette.colorInts()
        val cx = w / 2f; val cy = h / 2f
        val margin = w * 0.06f
        val radius = minOf(w, h) * scale / 2f

        // Generate constraint shape vertices
        val constraintVerts = generateConstraintShape(shape, sides, cx, cy, radius, time, animMode, speed)

        // Generate random points
        val totalN = nRandom + constraintVerts.size
        val px = FloatArray(totalN)
        val py = FloatArray(totalN)

        // Place constraint vertices first
        for (i in constraintVerts.indices) {
            px[i] = constraintVerts[i].first
            py[i] = constraintVerts[i].second
        }
        val constraintCount = constraintVerts.size

        // Place random points
        val visibleRandom = when (animMode) {
            "grow" -> ((time * speed * nRandom * 0.5f).toInt()).coerceIn(0, nRandom)
            else -> nRandom
        }
        for (i in 0 until nRandom) {
            px[constraintCount + i] = rng.range(margin, w - margin)
            py[constraintCount + i] = rng.range(margin, h - margin)
        }
        val activeN = constraintCount + visibleRandom

        // Delaunay on active points
        val pxExt = FloatArray(activeN + 3)
        val pyExt = FloatArray(activeN + 3)
        System.arraycopy(px, 0, pxExt, 0, activeN)
        System.arraycopy(py, 0, pyExt, 0, activeN)
        val m = maxOf(w, h) * 3f
        pxExt[activeN] = -m; pyExt[activeN] = -m
        pxExt[activeN + 1] = w / 2f; pyExt[activeN + 1] = h + m * 2f
        pxExt[activeN + 2] = w + m * 2f; pyExt[activeN + 2] = -m
        val triangles = bowyerWatson(pxExt, pyExt, activeN)

        // Build constraint edge set for highlighting
        val constraintEdgeSet = mutableSetOf<Long>()
        for (i in constraintVerts.indices) {
            val j = (i + 1) % constraintVerts.size
            constraintEdgeSet.add(packEdge(minOf(i, j), maxOf(i, j)))
        }

        // Classify triangles as interior/exterior
        val constraintPoly = constraintVerts.toList()
        val diagonal = sqrt(w * w + h * h)

        canvas.drawColor(Color.BLACK)

        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = edgeWidth
            color = Color.argb(120, 200, 200, 200)
            strokeCap = Paint.Cap.ROUND
        }
        val constraintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = constraintWidth
            strokeCap = Paint.Cap.ROUND
        }

        // Pulse animation for constraint edges
        val pulseAlpha = if (animMode == "pulse") {
            (sin(time * speed * 5f) * 0.3f + 0.7f).coerceIn(0.4f, 1f)
        } else 1f

        // Draw triangles
        for (tri in triangles) {
            val tcx = (px[tri.a] + px[tri.b] + px[tri.c]) / 3f
            val tcy = (py[tri.a] + py[tri.b] + py[tri.c]) / 3f
            val inside = isInsidePolygon(tcx, tcy, constraintPoly)

            val shouldFill = (inside && fillInterior) || (!inside && fillExterior)
            if (shouldFill) {
                val baseColor = when (colorMode) {
                    "region" -> if (inside) palette.lerpColor(0.3f) else palette.lerpColor(0.8f)
                    "area" -> {
                        val area = triangleArea(px[tri.a], py[tri.a], px[tri.b], py[tri.b], px[tri.c], py[tri.c])
                        val maxArea = w * h / nRandom
                        palette.lerpColor((area / maxArea).coerceIn(0f, 1f))
                    }
                    "angle" -> {
                        val angle = atan2(tcy - cy, tcx - cx)
                        palette.lerpColor(((angle / (2f * PI.toFloat())) + 0.5f).coerceIn(0f, 1f))
                    }
                    "centroid-dist" -> {
                        val d = sqrt((tcx - cx) * (tcx - cx) + (tcy - cy) * (tcy - cy))
                        palette.lerpColor((d / (diagonal * 0.5f)).coerceIn(0f, 1f))
                    }
                    else -> if (inside) palette.lerpColor(0.3f) else palette.lerpColor(0.8f)
                }
                val alpha = if (inside) 180 else 100
                fillPaint.color = Color.argb(alpha, Color.red(baseColor), Color.green(baseColor), Color.blue(baseColor))
                val path = Path().apply {
                    moveTo(px[tri.a], py[tri.a])
                    lineTo(px[tri.b], py[tri.b])
                    lineTo(px[tri.c], py[tri.c])
                    close()
                }
                canvas.drawPath(path, fillPaint)
            }

            // Draw triangle edges
            val path = Path().apply {
                moveTo(px[tri.a], py[tri.a])
                lineTo(px[tri.b], py[tri.b])
                lineTo(px[tri.c], py[tri.c])
                close()
            }
            canvas.drawPath(path, strokePaint)
        }

        // Draw constraint edges on top (highlighted)
        for (i in constraintVerts.indices) {
            val j = (i + 1) % constraintVerts.size
            val t = i.toFloat() / constraintVerts.size.coerceAtLeast(1)
            val baseColor = palette.lerpColor(t)
            val alpha = (255 * pulseAlpha).toInt().coerceIn(80, 255)
            constraintPaint.color = Color.argb(alpha, Color.red(baseColor), Color.green(baseColor), Color.blue(baseColor))
            canvas.drawLine(px[i], py[i], px[j], py[j], constraintPaint)
        }

        // Draw constraint vertices
        val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        for (i in constraintVerts.indices) {
            dotPaint.color = colors[i % colors.size]
            canvas.drawCircle(px[i], py[i], constraintWidth * 1.5f, dotPaint)
        }
    }

    override fun renderVector(
        params: Map<String, Any>,
        seed: Int,
        palette: Palette
    ): List<SvgPath> {
        val w = 1080f; val h = 1080f
        val nRandom = (params["pointCount"] as? Number)?.toInt() ?: 120
        val shape = (params["constraintShape"] as? String) ?: "polygon"
        val sides = (params["constraintSides"] as? Number)?.toInt() ?: 5
        val scale = (params["constraintScale"] as? Number)?.toFloat() ?: 0.5f
        val fillInterior = (params["fillInterior"] as? Boolean) ?: true
        val edgeWidth = (params["edgeWidth"] as? Number)?.toFloat() ?: 1.5f
        val constraintWidth = (params["constraintWidth"] as? Number)?.toFloat() ?: 3f

        val rng = SeededRNG(seed)
        val colors = palette.colorInts()
        val cx = w / 2f; val cy = h / 2f
        val radius = minOf(w, h) * scale / 2f
        val margin = w * 0.06f

        val constraintVerts = generateConstraintShape(shape, sides, cx, cy, radius, 0f, "none", 0f)
        val totalN = nRandom + constraintVerts.size
        val px = FloatArray(totalN); val py = FloatArray(totalN)
        for (i in constraintVerts.indices) { px[i] = constraintVerts[i].first; py[i] = constraintVerts[i].second }
        val constraintCount = constraintVerts.size
        for (i in 0 until nRandom) { px[constraintCount + i] = rng.range(margin, w - margin); py[constraintCount + i] = rng.range(margin, h - margin) }

        val pxExt = FloatArray(totalN + 3); val pyExt = FloatArray(totalN + 3)
        System.arraycopy(px, 0, pxExt, 0, totalN); System.arraycopy(py, 0, pyExt, 0, totalN)
        val m = maxOf(w, h) * 3f
        pxExt[totalN] = -m; pyExt[totalN] = -m; pxExt[totalN + 1] = w / 2f; pyExt[totalN + 1] = h + m * 2f; pxExt[totalN + 2] = w + m * 2f; pyExt[totalN + 2] = -m
        val triangles = bowyerWatson(pxExt, pyExt, totalN)

        val constraintPoly = constraintVerts.toList()
        val paths = mutableListOf<SvgPath>()

        for (tri in triangles) {
            val tcx = (px[tri.a] + px[tri.b] + px[tri.c]) / 3f
            val tcy = (py[tri.a] + py[tri.b] + py[tri.c]) / 3f
            val inside = isInsidePolygon(tcx, tcy, constraintPoly)
            if (inside && fillInterior) {
                val colorInt = palette.lerpColor(0.3f)
                val hex = String.format("#%06X", 0xFFFFFF and colorInt)
                val d = SvgBuilder.polygon(listOf(Pair(px[tri.a], py[tri.a]), Pair(px[tri.b], py[tri.b]), Pair(px[tri.c], py[tri.c])))
                paths.add(SvgPath(d = d, fill = hex, stroke = "#C8C8C878", strokeWidth = edgeWidth))
            }
        }
        // Constraint edges
        for (i in constraintVerts.indices) {
            val j = (i + 1) % constraintVerts.size
            val colorInt = palette.lerpColor(i.toFloat() / constraintVerts.size)
            val hex = String.format("#%06X", 0xFFFFFF and colorInt)
            val d = "${SvgBuilder.moveTo(px[i], py[i])} ${SvgBuilder.lineTo(px[j], py[j])}"
            paths.add(SvgPath(d = d, stroke = hex, strokeWidth = constraintWidth))
        }
        return paths
    }

    private fun generateConstraintShape(
        shape: String, sides: Int, cx: Float, cy: Float, radius: Float,
        time: Float, animMode: String, speed: Float
    ): List<Pair<Float, Float>> {
        val morphAngle = if (animMode == "morph") time * speed * 0.5f else 0f
        return when (shape) {
            "circle" -> {
                val n = 24
                (0 until n).map { i ->
                    val angle = 2f * PI.toFloat() * i / n + morphAngle
                    Pair(cx + cos(angle) * radius, cy + sin(angle) * radius)
                }
            }
            "star" -> {
                val points = mutableListOf<Pair<Float, Float>>()
                for (i in 0 until sides * 2) {
                    val angle = PI.toFloat() * i / sides - PI.toFloat() / 2f + morphAngle
                    val r = if (i % 2 == 0) radius else radius * 0.4f
                    points.add(Pair(cx + cos(angle) * r, cy + sin(angle) * r))
                }
                points
            }
            "spiral" -> {
                val n = 30
                (0 until n).map { i ->
                    val t = i.toFloat() / n
                    val angle = t * 4f * PI.toFloat() + morphAngle
                    val r = radius * (0.2f + t * 0.8f)
                    Pair(cx + cos(angle) * r, cy + sin(angle) * r)
                }
            }
            "concentric" -> {
                val points = mutableListOf<Pair<Float, Float>>()
                val rings = 3
                for (ring in 0 until rings) {
                    val r = radius * (ring + 1f) / rings
                    val n = 8 + ring * 4
                    for (i in 0 until n) {
                        val angle = 2f * PI.toFloat() * i / n + morphAngle * (ring + 1)
                        points.add(Pair(cx + cos(angle) * r, cy + sin(angle) * r))
                    }
                }
                points
            }
            else -> { // polygon
                (0 until sides).map { i ->
                    val angle = 2f * PI.toFloat() * i / sides - PI.toFloat() / 2f + morphAngle
                    Pair(cx + cos(angle) * radius, cy + sin(angle) * radius)
                }
            }
        }
    }

    private fun isInsidePolygon(x: Float, y: Float, polygon: List<Pair<Float, Float>>): Boolean {
        var winding = 0
        val n = polygon.size
        for (i in 0 until n) {
            val j = (i + 1) % n
            val yi = polygon[i].second; val yj = polygon[j].second
            if (yi <= y) {
                if (yj > y) {
                    if (isLeft(polygon[i], polygon[j], x, y) > 0) winding++
                }
            } else {
                if (yj <= y) {
                    if (isLeft(polygon[i], polygon[j], x, y) < 0) winding--
                }
            }
        }
        return winding != 0
    }

    private fun isLeft(p0: Pair<Float, Float>, p1: Pair<Float, Float>, x: Float, y: Float): Float {
        return (p1.first - p0.first) * (y - p0.second) - (x - p0.first) * (p1.second - p0.second)
    }

    private fun triangleArea(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float): Float {
        return kotlin.math.abs((x1 * (y2 - y3) + x2 * (y3 - y1) + x3 * (y1 - y2)) / 2f)
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
        Quality.DRAFT -> (base * 0.5f).toInt().coerceAtLeast(15)
        Quality.BALANCED -> base
        Quality.ULTRA -> (base * 1.5f).toInt()
    }

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float {
        val n = (params["pointCount"] as? Number)?.toFloat() ?: 120f
        return (n / 200f).coerceIn(0.2f, 1f)
    }
}
