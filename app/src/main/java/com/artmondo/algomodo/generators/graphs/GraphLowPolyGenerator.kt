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
 * Low-poly style generator.
 *
 * Scatters jittered points in a grid, computes a Delaunay triangulation, and
 * fills each triangle with an interpolated colour. Produces the popular
 * low-polygon art style. Supports SVG vector output.
 */
class GraphLowPolyGenerator : Generator {

    override val id = "graph-low-poly"
    override val family = "graphs"
    override val styleName = "Low Poly"
    override val definition =
        "Low-polygon style art where jittered grid points are triangulated and each triangle is filled with an interpolated colour."
    override val algorithmNotes =
        "A regular grid of points is created and jittered by a random amount controlled by the jitter parameter. " +
        "Corner points are placed at the edges. Bowyer-Watson Delaunay triangulation computes the mesh. " +
        "Each triangle's centroid determines its colour from the palette via position, random, or gradient mode. " +
        "Animation drifts the jitter via simplex noise. SVG output produces polygon path elements."
    override val supportsVector = true
    override val supportsAnimation = true

    override val parameterSchema = listOf(
        Parameter.NumberParam("Point Count", "pointCount", ParamGroup.COMPOSITION, null, 20f, 500f, 10f, 150f),
        Parameter.SelectParam("Distribution", "distribution", ParamGroup.COMPOSITION, null, listOf("jittered-grid", "random", "poisson-disc", "fibonacci"), "jittered-grid"),
        Parameter.NumberParam("Noise Scale", "noiseScale", ParamGroup.TEXTURE, "Spatial frequency of elevation noise", 0.5f, 8f, 0.5f, 3f),
        Parameter.NumberParam("Noise Octaves", "noiseOctaves", ParamGroup.TEXTURE, null, 1f, 6f, 1f, 4f),
        Parameter.NumberParam("Elevation Contrast", "elevationContrast", ParamGroup.TEXTURE, null, 0.5f, 3f, 0.25f, 1.5f),
        Parameter.NumberParam("Light Angle", "lightAngle", ParamGroup.TEXTURE, null, 0f, 360f, 15f, 135f),
        Parameter.NumberParam("Light Intensity", "lightIntensity", ParamGroup.TEXTURE, null, 0f, 1f, 0.05f, 0.4f),
        Parameter.BooleanParam("Show Edges", "showEdges", ParamGroup.GEOMETRY, null, false),
        Parameter.NumberParam("Edge Width", "edgeWidth", ParamGroup.GEOMETRY, null, 0.5f, 3f, 0.5f, 0.5f),
        Parameter.SelectParam("Color Mode", "colorMode", ParamGroup.COLOR, null, listOf("elevation", "slope", "aspect", "palette-flat"), "elevation"),
        Parameter.NumberParam("Anim Speed", "animSpeed", ParamGroup.FLOW_MOTION, "Speed of noise evolution (0 = static)", 0f, 1f, 0.05f, 0.15f),
        Parameter.NumberParam("Vertex Drift", "vertexDrift", ParamGroup.FLOW_MOTION, null, 0f, 0.5f, 0.05f, 0.15f)
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "pointCount" to 150f,
        "distribution" to "jittered-grid",
        "noiseScale" to 3f,
        "noiseOctaves" to 4f,
        "elevationContrast" to 1.5f,
        "lightAngle" to 135f,
        "lightIntensity" to 0.4f,
        "showEdges" to false,
        "edgeWidth" to 0.5f,
        "colorMode" to "elevation",
        "animSpeed" to 0.15f,
        "vertexDrift" to 0.15f
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
        val numPoints = (params["pointCount"] as? Number)?.toInt() ?: 150
        val jitter = (params["noiseScale"] as? Number)?.toFloat() ?: 3f
        val strokeWidth = (params["edgeWidth"] as? Number)?.toFloat() ?: 0.5f
        val colorMode = (params["colorMode"] as? String) ?: "elevation"

        val rng = SeededRNG(seed)
        val noise = SimplexNoise(seed)
        val colors = palette.colorInts()

        // Generate jittered grid points
        val cols = sqrt(numPoints.toFloat() * w / h).toInt().coerceAtLeast(3)
        val rows = (numPoints / cols).coerceAtLeast(3)
        val cellW = w / (cols - 1)
        val cellH = h / (rows - 1)

        val allPoints = mutableListOf<Pair<Float, Float>>()

        for (r in 0 until rows) {
            for (c in 0 until cols) {
                var px = c * cellW
                var py = r * cellH

                // Don't jitter corner/edge points too much
                val edgeFactor = if (r == 0 || r == rows - 1 || c == 0 || c == cols - 1) 0.2f else 1f

                val jx = (rng.random() - 0.5f) * cellW * jitter * edgeFactor
                val jy = (rng.random() - 0.5f) * cellH * jitter * edgeFactor

                // Animate jitter
                if (time > 0f) {
                    val ni = allPoints.size
                    px += noise.noise2D(ni * 0.3f + 10f, time * 0.2f) * cellW * 0.15f * edgeFactor
                    py += noise.noise2D(ni * 0.3f + 110f, time * 0.2f) * cellH * 0.15f * edgeFactor
                }

                px = (px + jx).coerceIn(0f, w)
                py = (py + jy).coerceIn(0f, h)
                allPoints.add(Pair(px, py))
            }
        }

        val n = allPoints.size
        val pointsX = FloatArray(n + 3)
        val pointsY = FloatArray(n + 3)
        for (i in 0 until n) {
            pointsX[i] = allPoints[i].first
            pointsY[i] = allPoints[i].second
        }

        // Super-triangle
        val margin = maxOf(w, h) * 3f
        pointsX[n] = -margin
        pointsY[n] = -margin
        pointsX[n + 1] = w / 2f
        pointsY[n + 1] = h + margin * 2f
        pointsX[n + 2] = w + margin * 2f
        pointsY[n + 2] = -margin

        val triangles = bowyerWatson(pointsX, pointsY, n)

        // Render
        canvas.drawColor(Color.BLACK)
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            this.strokeWidth = strokeWidth
            color = Color.argb(80, 0, 0, 0)
        }

        val diagonal = sqrt(w * w + h * h)

        for (tri in triangles) {
            val cx = (pointsX[tri.a] + pointsX[tri.b] + pointsX[tri.c]) / 3f
            val cy = (pointsY[tri.a] + pointsY[tri.b] + pointsY[tri.c]) / 3f

            fillPaint.color = when (colorMode) {
                "random" -> colors[(tri.a * 7 + tri.b * 13 + tri.c * 23) % colors.size]
                "gradient" -> palette.lerpColor(cy / h)
                else -> { // position
                    val t = (sqrt(cx * cx + cy * cy) / diagonal).coerceIn(0f, 1f)
                    palette.lerpColor(t)
                }
            }

            val path = Path().apply {
                moveTo(pointsX[tri.a], pointsY[tri.a])
                lineTo(pointsX[tri.b], pointsY[tri.b])
                lineTo(pointsX[tri.c], pointsY[tri.c])
                close()
            }

            canvas.drawPath(path, fillPaint)
            canvas.drawPath(path, strokePaint)
        }
    }

    override fun renderVector(
        params: Map<String, Any>,
        seed: Int,
        palette: Palette
    ): List<SvgPath> {
        val w = 1080f
        val h = 1080f
        val numPoints = (params["pointCount"] as? Number)?.toInt() ?: 150
        val jitter = (params["noiseScale"] as? Number)?.toFloat() ?: 3f
        val strokeWidth = (params["edgeWidth"] as? Number)?.toFloat() ?: 0.5f
        val colorMode = (params["colorMode"] as? String) ?: "elevation"

        val rng = SeededRNG(seed)
        val colors = palette.colorInts()

        val cols = sqrt(numPoints.toFloat() * w / h).toInt().coerceAtLeast(3)
        val rows = (numPoints / cols).coerceAtLeast(3)
        val cellW = w / (cols - 1)
        val cellH = h / (rows - 1)

        val allPoints = mutableListOf<Pair<Float, Float>>()
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val edgeFactor = if (r == 0 || r == rows - 1 || c == 0 || c == cols - 1) 0.2f else 1f
                val px = (c * cellW + (rng.random() - 0.5f) * cellW * jitter * edgeFactor).coerceIn(0f, w)
                val py = (r * cellH + (rng.random() - 0.5f) * cellH * jitter * edgeFactor).coerceIn(0f, h)
                allPoints.add(Pair(px, py))
            }
        }

        val n = allPoints.size
        val pointsX = FloatArray(n + 3)
        val pointsY = FloatArray(n + 3)
        for (i in 0 until n) {
            pointsX[i] = allPoints[i].first
            pointsY[i] = allPoints[i].second
        }

        val margin = maxOf(w, h) * 3f
        pointsX[n] = -margin; pointsY[n] = -margin
        pointsX[n + 1] = w / 2f; pointsY[n + 1] = h + margin * 2f
        pointsX[n + 2] = w + margin * 2f; pointsY[n + 2] = -margin

        val triangles = bowyerWatson(pointsX, pointsY, n)
        val paths = mutableListOf<SvgPath>()
        val diagonal = sqrt(w * w + h * h)

        for (tri in triangles) {
            val cx = (pointsX[tri.a] + pointsX[tri.b] + pointsX[tri.c]) / 3f
            val cy = (pointsY[tri.a] + pointsY[tri.b] + pointsY[tri.c]) / 3f

            val colorInt = when (colorMode) {
                "random" -> colors[(tri.a * 7 + tri.b * 13 + tri.c * 23) % colors.size]
                "gradient" -> palette.lerpColor(cy / h)
                else -> {
                    val t = (sqrt(cx * cx + cy * cy) / diagonal).coerceIn(0f, 1f)
                    palette.lerpColor(t)
                }
            }

            val d = SvgBuilder.polygon(listOf(
                Pair(pointsX[tri.a], pointsY[tri.a]),
                Pair(pointsX[tri.b], pointsY[tri.b]),
                Pair(pointsX[tri.c], pointsY[tri.c])
            ))

            paths.add(SvgPath(
                d = d,
                fill = String.format("#%06X", 0xFFFFFF and colorInt),
                stroke = "#00000050",
                strokeWidth = strokeWidth
            ))
        }

        return paths
    }

    private fun bowyerWatson(px: FloatArray, py: FloatArray, n: Int): List<Tri> {
        val triangles = mutableListOf(Tri(n, n + 1, n + 2))

        for (p in 0 until n) {
            val badTriangles = mutableListOf<Tri>()
            for (tri in triangles) {
                if (inCircumcircle(px, py, tri.a, tri.b, tri.c, px[p], py[p])) {
                    badTriangles.add(tri)
                }
            }

            val edges = mutableListOf<Pair<Int, Int>>()
            for (tri in badTriangles) {
                val triEdges = listOf(
                    Pair(tri.a, tri.b), Pair(tri.b, tri.c), Pair(tri.c, tri.a)
                )
                for (edge in triEdges) {
                    val shared = badTriangles.any { other ->
                        other !== tri && hasEdge(other, edge.first, edge.second)
                    }
                    if (!shared) edges.add(edge)
                }
            }

            triangles.removeAll(badTriangles)
            for (edge in edges) {
                triangles.add(Tri(edge.first, edge.second, p))
            }
        }

        triangles.removeAll { it.a >= n || it.b >= n || it.c >= n }
        return triangles
    }

    private fun hasEdge(tri: Tri, a: Int, b: Int): Boolean {
        val v = listOf(tri.a, tri.b, tri.c)
        return a in v && b in v
    }

    private fun inCircumcircle(
        px: FloatArray, py: FloatArray,
        a: Int, b: Int, c: Int,
        dx: Float, dy: Float
    ): Boolean {
        val ax = px[a] - dx; val ay = py[a] - dy
        val bx = px[b] - dx; val by = py[b] - dy
        val cx = px[c] - dx; val cy = py[c] - dy

        val det = ax * (by * (cx * cx + cy * cy) - cy * (bx * bx + by * by)) -
                  ay * (bx * (cx * cx + cy * cy) - cx * (bx * bx + by * by)) +
                  (ax * ax + ay * ay) * (bx * cy - by * cx)

        val cross = (px[b] - px[a]) * (py[c] - py[a]) - (py[b] - py[a]) * (px[c] - px[a])
        return if (cross > 0) det > 0 else det < 0
    }

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float {
        val n = (params["pointCount"] as? Number)?.toFloat() ?: 150f
        return (n / 300f).coerceIn(0.2f, 1f)
    }
}
