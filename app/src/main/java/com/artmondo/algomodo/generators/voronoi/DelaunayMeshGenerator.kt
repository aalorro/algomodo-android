package com.artmondo.algomodo.generators.voronoi

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
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Delaunay triangulation generator.
 *
 * Scatters random points, computes a Delaunay triangulation using an incremental
 * algorithm, and renders filled/stroked triangles coloured by area or position.
 * Animation drifts points via simplex noise.
 */
class DelaunayMeshGenerator : Generator {

    override val id = "delaunay-mesh"
    override val family = "voronoi"
    override val styleName = "Delaunay Mesh"
    override val definition =
        "Delaunay triangulation that partitions scattered points into triangles where no point lies inside any triangle's circumcircle."
    override val algorithmNotes =
        "Points are generated with SeededRNG. A Bowyer-Watson incremental insertion algorithm computes the Delaunay " +
        "triangulation. Each triangle is coloured based on its centroid position mapped to the palette. " +
        "Animation displaces points over time via simplex noise."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema = listOf(
        Parameter.NumberParam("Point Count", "pointCount", ParamGroup.COMPOSITION, "", 6f, 300f, 6f, 80f),
        Parameter.SelectParam("Color Mode", "colorMode", ParamGroup.COLOR, "", listOf("by-position", "by-area", "palette-cycle", "gradient-y"), "by-position"),
        Parameter.BooleanParam("Show Edges", "showEdges", ParamGroup.GEOMETRY, "", true),
        Parameter.NumberParam("Edge Width", "edgeWidth", ParamGroup.GEOMETRY, "", 0.5f, 4f, 0.5f, 1f),
        Parameter.NumberParam("Edge Opacity", "edgeOpacity", ParamGroup.COLOR, "", 0f, 1f, 0.05f, 0.5f),
        Parameter.NumberParam("Color Jitter", "jitter", ParamGroup.TEXTURE, "Random brightness variation per triangle", 0f, 0.4f, 0.02f, 0.1f),
        Parameter.NumberParam("Anim Speed", "animSpeed", ParamGroup.FLOW_MOTION, "", 0f, 2f, 0.05f, 0.4f),
        Parameter.NumberParam("Anim Amplitude", "animAmp", ParamGroup.FLOW_MOTION, "Drift distance as a fraction of average cell size", 0f, 1f, 0.05f, 0.2f)
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "pointCount" to 80f,
        "colorMode" to "by-position",
        "showEdges" to true,
        "edgeWidth" to 1f,
        "edgeOpacity" to 0.5f,
        "jitter" to 0.1f,
        "animSpeed" to 0.4f,
        "animAmp" to 0.2f
    )

    // Triangle represented by three point indices
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
        val numPoints = (params["pointCount"] as? Number)?.toInt() ?: 80
        val colorMode = (params["colorMode"] as? String) ?: "by-position"
        val showTriangles = params["showEdges"] as? Boolean ?: true
        val fillTriangles = true
        val lineWidth = (params["edgeWidth"] as? Number)?.toFloat() ?: 1f
        val edgeOpacity = (params["edgeOpacity"] as? Number)?.toFloat() ?: 0.5f
        val jitter = (params["jitter"] as? Number)?.toFloat() ?: 0.1f
        val animSpeed = (params["animSpeed"] as? Number)?.toFloat() ?: 0.4f
        val animAmp = (params["animAmp"] as? Number)?.toFloat() ?: 0.2f

        val rng = SeededRNG(seed)
        val noise = SimplexNoise(seed)

        // Generate points
        val pointsX = FloatArray(numPoints + 3) // +3 for super-triangle
        val pointsY = FloatArray(numPoints + 3)
        for (i in 0 until numPoints) {
            pointsX[i] = rng.range(w * 0.05f, w * 0.95f)
            pointsY[i] = rng.range(h * 0.05f, h * 0.95f)
        }

        // Animate points
        if (time > 0f) {
            val speed = animSpeed / 0.4f  // normalize around default
            val amp = animAmp / 0.2f      // normalize around default
            for (i in 0 until numPoints) {
                pointsX[i] += noise.noise2D(i * 0.4f + 50f, time * 0.15f * speed) * w * 0.04f * amp
                pointsY[i] += noise.noise2D(i * 0.4f + 150f, time * 0.15f * speed) * h * 0.04f * amp
                pointsX[i] = pointsX[i].coerceIn(0f, w)
                pointsY[i] = pointsY[i].coerceIn(0f, h)
            }
        }

        // Super-triangle that encloses all points
        val margin = maxOf(w, h) * 3f
        pointsX[numPoints] = -margin
        pointsY[numPoints] = -margin
        pointsX[numPoints + 1] = w / 2f
        pointsY[numPoints + 1] = h + margin * 2f
        pointsX[numPoints + 2] = w + margin * 2f
        pointsY[numPoints + 2] = -margin

        // Bowyer-Watson
        val triangles = mutableListOf(Tri(numPoints, numPoints + 1, numPoints + 2))

        for (p in 0 until numPoints) {
            val badTriangles = mutableListOf<Tri>()
            for (tri in triangles) {
                if (inCircumcircle(pointsX, pointsY, tri.a, tri.b, tri.c, pointsX[p], pointsY[p])) {
                    badTriangles.add(tri)
                }
            }

            // Find boundary polygon of the hole
            val edges = mutableListOf<Pair<Int, Int>>()
            for (tri in badTriangles) {
                val triEdges = listOf(
                    Pair(tri.a, tri.b), Pair(tri.b, tri.c), Pair(tri.c, tri.a)
                )
                for (edge in triEdges) {
                    val shared = badTriangles.any { other ->
                        other !== tri && hasEdge(other, edge.first, edge.second)
                    }
                    if (!shared) {
                        edges.add(edge)
                    }
                }
            }

            triangles.removeAll(badTriangles)

            for (edge in edges) {
                triangles.add(Tri(edge.first, edge.second, p))
            }
        }

        // Remove triangles that reference super-triangle vertices
        triangles.removeAll { it.a >= numPoints || it.b >= numPoints || it.c >= numPoints }

        // Render
        canvas.drawColor(Color.BLACK)
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
        val edgeAlpha = (edgeOpacity * 255f).toInt().coerceIn(0, 255)
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = lineWidth
            color = Color.argb(edgeAlpha, 0, 0, 0)
        }

        val colors = palette.colorInts()
        val diagonal = sqrt(w * w + h * h)

        val jitterRng = SeededRNG(seed + 999)
        for ((triIdx, tri) in triangles.withIndex()) {
            val cx = (pointsX[tri.a] + pointsX[tri.b] + pointsX[tri.c]) / 3f
            val cy = (pointsY[tri.a] + pointsY[tri.b] + pointsY[tri.c]) / 3f

            val path = Path().apply {
                moveTo(pointsX[tri.a], pointsY[tri.a])
                lineTo(pointsX[tri.b], pointsY[tri.b])
                lineTo(pointsX[tri.c], pointsY[tri.c])
                close()
            }

            if (fillTriangles) {
                // Compute area for by-area mode
                val area = abs(
                    (pointsX[tri.b] - pointsX[tri.a]) * (pointsY[tri.c] - pointsY[tri.a]) -
                    (pointsX[tri.c] - pointsX[tri.a]) * (pointsY[tri.b] - pointsY[tri.a])
                ) / 2f
                val maxArea = w * h / numPoints.coerceAtLeast(1).toFloat()

                val t = when (colorMode) {
                    "by-area" -> (area / maxArea).coerceIn(0f, 1f)
                    "palette-cycle" -> (triIdx.toFloat() / triangles.size.coerceAtLeast(1)).coerceIn(0f, 1f)
                    "gradient-y" -> (cy / h).coerceIn(0f, 1f)
                    else -> (sqrt(cx * cx + cy * cy) / diagonal).coerceIn(0f, 1f) // "by-position"
                }

                var baseColor = palette.lerpColor(t)
                // Apply jitter: random brightness variation per triangle
                if (jitter > 0f) {
                    val jitterVal = 1f + (jitterRng.random() - 0.5f) * 2f * jitter
                    val r = (Color.red(baseColor) * jitterVal).toInt().coerceIn(0, 255)
                    val g = (Color.green(baseColor) * jitterVal).toInt().coerceIn(0, 255)
                    val b = (Color.blue(baseColor) * jitterVal).toInt().coerceIn(0, 255)
                    baseColor = Color.rgb(r, g, b)
                }
                fillPaint.color = baseColor
                canvas.drawPath(path, fillPaint)
            }

            if (showTriangles) {
                canvas.drawPath(path, strokePaint)
            }
        }
    }

    private fun hasEdge(tri: Tri, a: Int, b: Int): Boolean {
        val verts = intArrayOf(tri.a, tri.b, tri.c)
        return (a in verts.toList() && b in verts.toList()) &&
                !(a == tri.a && b == tri.b && false) // just check membership
                && ((a == tri.a && b == tri.b) || (a == tri.b && b == tri.c) ||
                (a == tri.c && b == tri.a) || (a == tri.b && b == tri.a) ||
                (a == tri.c && b == tri.b) || (a == tri.a && b == tri.c))
    }

    private fun inCircumcircle(
        px: FloatArray, py: FloatArray,
        a: Int, b: Int, c: Int,
        dx: Float, dy: Float
    ): Boolean {
        val ax = px[a] - dx
        val ay = py[a] - dy
        val bx = px[b] - dx
        val by = py[b] - dy
        val cx = px[c] - dx
        val cy = py[c] - dy

        val det = ax * (by * (cx * cx + cy * cy) - cy * (bx * bx + by * by)) -
                  ay * (bx * (cx * cx + cy * cy) - cx * (bx * bx + by * by)) +
                  (ax * ax + ay * ay) * (bx * cy - by * cx)

        // Ensure consistent winding
        val cross = (px[b] - px[a]) * (py[c] - py[a]) - (py[b] - py[a]) * (px[c] - px[a])
        return if (cross > 0) det > 0 else det < 0
    }

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float {
        val n = (params["pointCount"] as? Number)?.toFloat() ?: 80f
        return (n / 200f).coerceIn(0.2f, 1f)
    }
}
