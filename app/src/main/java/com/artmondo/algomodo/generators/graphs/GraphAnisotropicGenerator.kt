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
 * Anisotropic graph generator.
 *
 * Defines a directional field across the canvas using simplex noise. Points
 * are displaced along the local field direction, then Delaunay-triangulated.
 * Edges are rendered with width and colour proportional to their alignment
 * with the field, producing a flow-aligned mesh aesthetic.
 */
class GraphAnisotropicGenerator : Generator {

    override val id = "graph-anisotropic"
    override val family = "graphs"
    override val styleName = "Anisotropic"
    override val definition =
        "Directional-field-driven mesh where points are displaced along a noise-based flow field, creating anisotropic triangulations with visible grain."
    override val algorithmNotes =
        "A simplex noise field defines a preferred angle at each canvas position. Seed points are displaced along " +
        "their local field direction by the anisotropy parameter. Bowyer-Watson Delaunay triangulation is computed " +
        "on the warped points. Edge stroke width and colour vary with alignment to the local field: aligned edges " +
        "appear thinner/brighter, perpendicular edges thicker/darker. Optional field-line overlay visualises the " +
        "underlying directional field."
    override val supportsVector = true
    override val supportsAnimation = true

    override val parameterSchema = listOf(
        Parameter.NumberParam("Point Count", "pointCount", ParamGroup.COMPOSITION, null, 40f, 500f, 10f, 150f),
        Parameter.NumberParam("Field Frequency", "fieldFreq", ParamGroup.TEXTURE, "Spatial frequency of the directional field", 0.5f, 6f, 0.5f, 2f),
        Parameter.NumberParam("Anisotropy", "anisotropy", ParamGroup.GEOMETRY, "Strength of directional stretching", 0f, 2f, 0.1f, 0.8f),
        Parameter.NumberParam("Field Angle", "fieldAngle", ParamGroup.COMPOSITION, "Global rotation of the field in degrees", 0f, 360f, 15f, 0f),
        Parameter.BooleanParam("Show Field Lines", "showFieldLines", ParamGroup.TEXTURE, "Overlay directional field streamlines", false),
        Parameter.NumberParam("Edge Width", "edgeWidth", ParamGroup.GEOMETRY, null, 0.5f, 4f, 0.5f, 1.5f),
        Parameter.BooleanParam("Fill Triangles", "fillTriangles", ParamGroup.TEXTURE, null, true),
        Parameter.SelectParam("Color Mode", "colorMode", ParamGroup.COLOR, null, listOf("alignment", "edge-weight", "field-angle", "depth"), "alignment"),
        Parameter.SelectParam("Distribution", "distribution", ParamGroup.COMPOSITION, null, listOf("random", "grid-jittered", "poisson"), "random"),
        Parameter.SelectParam("Animation", "animMode", ParamGroup.FLOW_MOTION, null, listOf("none", "field-rotate", "flow", "breathe"), "flow"),
        Parameter.NumberParam("Speed", "speed", ParamGroup.FLOW_MOTION, null, 0.05f, 1f, 0.05f, 0.2f)
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "pointCount" to 150f,
        "fieldFreq" to 2f,
        "anisotropy" to 0.8f,
        "fieldAngle" to 0f,
        "showFieldLines" to false,
        "edgeWidth" to 1.5f,
        "fillTriangles" to true,
        "colorMode" to "alignment",
        "distribution" to "random",
        "animMode" to "flow",
        "speed" to 0.2f
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
        val n = qualityScale((params["pointCount"] as? Number)?.toInt() ?: 150, quality)
        val fieldFreq = (params["fieldFreq"] as? Number)?.toFloat() ?: 2f
        val anisotropy = (params["anisotropy"] as? Number)?.toFloat() ?: 0.8f
        var fieldAngle = (params["fieldAngle"] as? Number)?.toFloat() ?: 0f
        val showFieldLines = (params["showFieldLines"] as? Boolean) ?: false
        val edgeWidth = (params["edgeWidth"] as? Number)?.toFloat() ?: 1.5f
        val fillTriangles = (params["fillTriangles"] as? Boolean) ?: true
        val colorMode = (params["colorMode"] as? String) ?: "alignment"
        val distribution = (params["distribution"] as? String) ?: "random"
        val animMode = (params["animMode"] as? String) ?: "flow"
        val speed = (params["speed"] as? Number)?.toFloat() ?: 0.2f

        val rng = SeededRNG(seed)
        val noise = SimplexNoise(seed)
        val colors = palette.colorInts()

        // Animate field rotation
        if (animMode == "field-rotate") fieldAngle += time * speed * 60f
        val fieldAngleRad = fieldAngle * PI.toFloat() / 180f

        // Animate anisotropy
        val effectiveAnisotropy = if (animMode == "breathe") {
            anisotropy * (0.5f + 0.5f * sin(time * speed * 3f))
        } else anisotropy

        val margin = w * 0.06f

        // Generate base points
        val basePx = FloatArray(n)
        val basePy = FloatArray(n)
        generatePoints(rng, basePx, basePy, n, w, h, margin, distribution)

        // Displace points along field direction
        val px = FloatArray(n)
        val py = FloatArray(n)
        val cellSpacing = sqrt(w * h / n.coerceAtLeast(1).toFloat())

        for (i in 0 until n) {
            val fx = basePx[i] / w * fieldFreq
            val fy = basePy[i] / h * fieldFreq
            val theta = noise.noise2D(fx, fy) * PI.toFloat() + fieldAngleRad

            // Flow animation: move points along field
            var flowX = 0f; var flowY = 0f
            if (animMode == "flow" && time > 0f) {
                flowX = cos(theta) * time * speed * cellSpacing * 0.5f
                flowY = sin(theta) * time * speed * cellSpacing * 0.5f
            }

            px[i] = (basePx[i] + cos(theta) * effectiveAnisotropy * cellSpacing * 0.5f + flowX).coerceIn(0f, w)
            py[i] = (basePy[i] + sin(theta) * effectiveAnisotropy * cellSpacing * 0.5f + flowY).coerceIn(0f, h)
        }

        // Bowyer-Watson Delaunay
        val pxExt = FloatArray(n + 3); val pyExt = FloatArray(n + 3)
        System.arraycopy(px, 0, pxExt, 0, n)
        System.arraycopy(py, 0, pyExt, 0, n)
        val m = maxOf(w, h) * 3f
        pxExt[n] = -m; pyExt[n] = -m
        pxExt[n + 1] = w / 2f; pyExt[n + 1] = h + m * 2f
        pxExt[n + 2] = w + m * 2f; pyExt[n + 2] = -m
        val triangles = bowyerWatson(pxExt, pyExt, n)

        // Extract edges with alignment info
        data class EdgeInfo(val i: Int, val j: Int, val alignment: Float, val length: Float)
        val edgeSet = mutableSetOf<Long>()
        val edgeInfos = mutableListOf<EdgeInfo>()
        for (tri in triangles) {
            for ((a, b) in listOf(Pair(tri.a, tri.b), Pair(tri.b, tri.c), Pair(tri.c, tri.a))) {
                val key = packEdge(minOf(a, b), maxOf(a, b))
                if (edgeSet.add(key)) {
                    val dx = px[b] - px[a]; val dy = py[b] - py[a]
                    val edgeAngle = atan2(dy, dx)
                    val midFx = ((px[a] + px[b]) / 2f) / w * fieldFreq
                    val midFy = ((py[a] + py[b]) / 2f) / h * fieldFreq
                    val localField = noise.noise2D(midFx, midFy) * PI.toFloat() + fieldAngleRad
                    val alignment = kotlin.math.abs(cos(edgeAngle - localField))
                    val length = sqrt(dx * dx + dy * dy)
                    edgeInfos.add(EdgeInfo(a, b, alignment, length))
                }
            }
        }

        var maxLen = 0f
        for (e in edgeInfos) if (e.length > maxLen) maxLen = e.length
        if (maxLen < 1f) maxLen = 1f

        canvas.drawColor(Color.BLACK)

        // Draw field lines if enabled
        if (showFieldLines) {
            val fieldPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 0.5f
                color = Color.argb(40, 255, 255, 255)
            }
            val step = w / 30f
            var fy = step / 2f
            while (fy < h) {
                var fx = step / 2f
                while (fx < w) {
                    val nfx = fx / w * fieldFreq
                    val nfy = fy / h * fieldFreq
                    val theta = noise.noise2D(nfx, nfy) * PI.toFloat() + fieldAngleRad
                    val len = step * 0.4f
                    canvas.drawLine(fx - cos(theta) * len, fy - sin(theta) * len,
                                   fx + cos(theta) * len, fy + sin(theta) * len, fieldPaint)
                    fx += step
                }
                fy += step
            }
        }

        // Fill triangles
        if (fillTriangles) {
            val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
            for (tri in triangles) {
                val tcx = (px[tri.a] + px[tri.b] + px[tri.c]) / 3f
                val tcy = (py[tri.a] + py[tri.b] + py[tri.c]) / 3f
                val midFx = tcx / w * fieldFreq; val midFy = tcy / h * fieldFreq
                val localField = noise.noise2D(midFx, midFy) * PI.toFloat() + fieldAngleRad
                val t = ((localField / PI.toFloat() + 1f) / 2f).coerceIn(0f, 1f)
                val baseColor = palette.lerpColor(t)
                fillPaint.color = Color.argb(60, Color.red(baseColor), Color.green(baseColor), Color.blue(baseColor))
                val path = Path().apply {
                    moveTo(px[tri.a], py[tri.a])
                    lineTo(px[tri.b], py[tri.b])
                    lineTo(px[tri.c], py[tri.c])
                    close()
                }
                canvas.drawPath(path, fillPaint)
            }
        }

        // Draw edges
        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }
        for (e in edgeInfos) {
            val t = when (colorMode) {
                "alignment" -> e.alignment
                "edge-weight" -> (e.length / maxLen).coerceIn(0f, 1f)
                "field-angle" -> {
                    val midFx = ((px[e.i] + px[e.j]) / 2f) / w * fieldFreq
                    val midFy = ((py[e.i] + py[e.j]) / 2f) / h * fieldFreq
                    val localField = noise.noise2D(midFx, midFy) * PI.toFloat()
                    ((localField / PI.toFloat() + 1f) / 2f).coerceIn(0f, 1f)
                }
                "depth" -> {
                    val midX = (px[e.i] + px[e.j]) / 2f
                    val midY = (py[e.i] + py[e.j]) / 2f
                    val d = sqrt((midX - w / 2f).let { it * it } + (midY - h / 2f).let { it * it })
                    (d / (sqrt(w * w + h * h) / 2f)).coerceIn(0f, 1f)
                }
                else -> e.alignment
            }
            linePaint.color = palette.lerpColor(t)
            linePaint.strokeWidth = edgeWidth * (0.3f + 0.7f * (1f - e.alignment))
            canvas.drawLine(px[e.i], py[e.i], px[e.j], py[e.j], linePaint)
        }

        // Draw nodes
        val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.WHITE
        }
        val dotSize = edgeWidth * 0.8f
        for (i in 0 until n) {
            canvas.drawCircle(px[i], py[i], dotSize, dotPaint)
        }
    }

    override fun renderVector(
        params: Map<String, Any>,
        seed: Int,
        palette: Palette
    ): List<SvgPath> {
        val w = 1080f; val h = 1080f
        val n = (params["pointCount"] as? Number)?.toInt() ?: 150
        val fieldFreq = (params["fieldFreq"] as? Number)?.toFloat() ?: 2f
        val anisotropy = (params["anisotropy"] as? Number)?.toFloat() ?: 0.8f
        val fieldAngle = (params["fieldAngle"] as? Number)?.toFloat() ?: 0f
        val edgeWidth = (params["edgeWidth"] as? Number)?.toFloat() ?: 1.5f
        val distribution = (params["distribution"] as? String) ?: "random"

        val rng = SeededRNG(seed)
        val noise = SimplexNoise(seed)
        val margin = w * 0.06f
        val fieldAngleRad = fieldAngle * PI.toFloat() / 180f

        val basePx = FloatArray(n); val basePy = FloatArray(n)
        generatePoints(rng, basePx, basePy, n, w, h, margin, distribution)

        val px = FloatArray(n); val py = FloatArray(n)
        val cellSpacing = sqrt(w * h / n.coerceAtLeast(1).toFloat())
        for (i in 0 until n) {
            val fx = basePx[i] / w * fieldFreq; val fy = basePy[i] / h * fieldFreq
            val theta = noise.noise2D(fx, fy) * PI.toFloat() + fieldAngleRad
            px[i] = (basePx[i] + cos(theta) * anisotropy * cellSpacing * 0.5f).coerceIn(0f, w)
            py[i] = (basePy[i] + sin(theta) * anisotropy * cellSpacing * 0.5f).coerceIn(0f, h)
        }

        val pxExt = FloatArray(n + 3); val pyExt = FloatArray(n + 3)
        System.arraycopy(px, 0, pxExt, 0, n); System.arraycopy(py, 0, pyExt, 0, n)
        val m = maxOf(w, h) * 3f
        pxExt[n] = -m; pyExt[n] = -m; pxExt[n + 1] = w / 2f; pyExt[n + 1] = h + m * 2f; pxExt[n + 2] = w + m * 2f; pyExt[n + 2] = -m
        val triangles = bowyerWatson(pxExt, pyExt, n)

        val edgeSet = mutableSetOf<Long>()
        val paths = mutableListOf<SvgPath>()

        for (tri in triangles) {
            for ((a, b) in listOf(Pair(tri.a, tri.b), Pair(tri.b, tri.c), Pair(tri.c, tri.a))) {
                val key = packEdge(minOf(a, b), maxOf(a, b))
                if (edgeSet.add(key)) {
                    val dx = px[b] - px[a]; val dy = py[b] - py[a]
                    val edgeAngle = atan2(dy, dx)
                    val midFx = ((px[a] + px[b]) / 2f) / w * fieldFreq
                    val midFy = ((py[a] + py[b]) / 2f) / h * fieldFreq
                    val localField = noise.noise2D(midFx, midFy) * PI.toFloat() + fieldAngleRad
                    val alignment = kotlin.math.abs(cos(edgeAngle - localField))
                    val colorInt = palette.lerpColor(alignment)
                    val hex = String.format("#%06X", 0xFFFFFF and colorInt)
                    val sw = edgeWidth * (0.3f + 0.7f * (1f - alignment))
                    val d = "${SvgBuilder.moveTo(px[a], py[a])} ${SvgBuilder.lineTo(px[b], py[b])}"
                    paths.add(SvgPath(d = d, stroke = hex, strokeWidth = sw))
                }
            }
        }
        return paths
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
            "poisson" -> {
                // Simple rejection-based poisson disc approximation
                val r = sqrt(w * h / n.toFloat()) * 0.8f
                var placed = 0
                var attempts = 0
                while (placed < n && attempts < n * 30) {
                    val cx = rng.range(margin, w - margin)
                    val cy = rng.range(margin, h - margin)
                    var tooClose = false
                    for (j in 0 until placed) {
                        val dx = px[j] - cx; val dy = py[j] - cy
                        if (dx * dx + dy * dy < r * r * 0.5f) { tooClose = true; break }
                    }
                    if (!tooClose) { px[placed] = cx; py[placed] = cy; placed++ }
                    attempts++
                }
                for (i in placed until n) { px[i] = rng.range(margin, w - margin); py[i] = rng.range(margin, h - margin) }
            }
            else -> {
                for (i in 0 until n) { px[i] = rng.range(margin, w - margin); py[i] = rng.range(margin, h - margin) }
            }
        }
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
        val n = (params["pointCount"] as? Number)?.toFloat() ?: 150f
        return (n / 250f).coerceIn(0.2f, 1f)
    }
}
