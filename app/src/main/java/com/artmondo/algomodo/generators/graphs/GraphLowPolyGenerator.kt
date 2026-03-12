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
        val distribution = (params["distribution"] as? String) ?: "jittered-grid"
        val noiseScale = (params["noiseScale"] as? Number)?.toFloat() ?: 3f
        val noiseOctaves = (params["noiseOctaves"] as? Number)?.toInt() ?: 4
        val elevationContrast = (params["elevationContrast"] as? Number)?.toFloat() ?: 1.5f
        val lightAngle = (params["lightAngle"] as? Number)?.toFloat() ?: 135f
        val lightIntensity = (params["lightIntensity"] as? Number)?.toFloat() ?: 0.4f
        val showEdges = params["showEdges"] as? Boolean ?: false
        val strokeWidth = (params["edgeWidth"] as? Number)?.toFloat() ?: 0.5f
        val colorMode = (params["colorMode"] as? String) ?: "elevation"
        val animSpeed = (params["animSpeed"] as? Number)?.toFloat() ?: 0.15f
        val vertexDrift = (params["vertexDrift"] as? Number)?.toFloat() ?: 0.15f

        val rng = SeededRNG(seed)
        val noise = SimplexNoise(seed)
        val colors = palette.colorInts()

        // Light direction vector
        val lightRad = Math.toRadians(lightAngle.toDouble()).toFloat()
        val lightDx = cos(lightRad)
        val lightDy = sin(lightRad)

        // Generate points based on distribution mode
        val allPoints = mutableListOf<Pair<Float, Float>>()

        when (distribution) {
            "random" -> {
                for (i in 0 until numPoints) {
                    allPoints.add(Pair(rng.random() * w, rng.random() * h))
                }
            }
            "fibonacci" -> {
                val goldenAngle = (PI * (3.0 - sqrt(5.0))).toFloat()
                for (i in 0 until numPoints) {
                    val t = i.toFloat() / numPoints
                    val r = sqrt(t) * minOf(w, h) * 0.5f
                    val theta = i * goldenAngle
                    allPoints.add(Pair(
                        (w / 2f + r * cos(theta)).coerceIn(0f, w),
                        (h / 2f + r * sin(theta)).coerceIn(0f, h)
                    ))
                }
            }
            "poisson-disc" -> {
                // Simplified Poisson-disc: random with minimum distance rejection
                val minDist = sqrt(w * h / numPoints) * 0.7f
                var attempts = 0
                while (allPoints.size < numPoints && attempts < numPoints * 20) {
                    val px = rng.random() * w
                    val py = rng.random() * h
                    val tooClose = allPoints.any { (ox, oy) ->
                        val dx = ox - px; val dy = oy - py
                        dx * dx + dy * dy < minDist * minDist
                    }
                    if (!tooClose) allPoints.add(Pair(px, py))
                    attempts++
                }
            }
            else -> { // "jittered-grid"
                val cols = sqrt(numPoints.toFloat() * w / h).toInt().coerceAtLeast(3)
                val rows = (numPoints / cols).coerceAtLeast(3)
                val cellW = w / (cols - 1)
                val cellH = h / (rows - 1)
                for (r in 0 until rows) {
                    for (c in 0 until cols) {
                        val edgeFactor = if (r == 0 || r == rows - 1 || c == 0 || c == cols - 1) 0.2f else 1f
                        val jx = (rng.random() - 0.5f) * cellW * noiseScale * edgeFactor
                        val jy = (rng.random() - 0.5f) * cellH * noiseScale * edgeFactor
                        val px = (c * cellW + jx).coerceIn(0f, w)
                        val py = (r * cellH + jy).coerceIn(0f, h)
                        allPoints.add(Pair(px, py))
                    }
                }
            }
        }

        // Animate vertex drift
        if (time > 0f && animSpeed > 0f) {
            for (i in allPoints.indices) {
                val (px, py) = allPoints[i]
                val avgCell = sqrt(w * h / allPoints.size.coerceAtLeast(1))
                val driftX = noise.noise2D(i * 0.3f + 10f, time * animSpeed) * avgCell * vertexDrift
                val driftY = noise.noise2D(i * 0.3f + 110f, time * animSpeed) * avgCell * vertexDrift
                allPoints[i] = Pair(
                    (px + driftX).coerceIn(0f, w),
                    (py + driftY).coerceIn(0f, h)
                )
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

        // Compute elevation at each point using multi-octave noise
        val elevation = FloatArray(n)
        val noiseFreq = noiseScale / w * 4f
        for (i in 0 until n) {
            var elev = 0f
            var amp = 1f
            var freq = noiseFreq
            for (oct in 0 until noiseOctaves) {
                elev += noise.noise2D(pointsX[i] * freq + seed * 0.1f, pointsY[i] * freq) * amp
                amp *= 0.5f
                freq *= 2f
            }
            elevation[i] = (elev * elevationContrast).coerceIn(-1f, 1f)
        }

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
            val avgElev = (elevation[tri.a] + elevation[tri.b] + elevation[tri.c]) / 3f

            // Compute face normal for lighting (using elevation as Z)
            val ux = pointsX[tri.b] - pointsX[tri.a]
            val uy = pointsY[tri.b] - pointsY[tri.a]
            val uz = (elevation[tri.b] - elevation[tri.a]) * w * 0.3f
            val vx = pointsX[tri.c] - pointsX[tri.a]
            val vy = pointsY[tri.c] - pointsY[tri.a]
            val vz = (elevation[tri.c] - elevation[tri.a]) * w * 0.3f
            val nx = uy * vz - uz * vy
            val ny = uz * vx - ux * vz
            val nLen = sqrt(nx * nx + ny * ny + 1f)
            val dot = (nx / nLen * lightDx + ny / nLen * lightDy).coerceIn(-1f, 1f)
            val shade = 1f + dot * lightIntensity

            // Compute slope (steepness) for slope color mode
            val slopeVal = sqrt(nx * nx + ny * ny) / nLen

            val baseColor = when (colorMode) {
                "slope" -> {
                    palette.lerpColor(slopeVal.coerceIn(0f, 1f))
                }
                "aspect" -> {
                    // Color by face orientation angle
                    val angle = ((kotlin.math.atan2(ny, nx) + PI.toFloat()) / (2f * PI.toFloat()))
                    palette.lerpColor(angle.coerceIn(0f, 1f))
                }
                "palette-flat" -> {
                    colors[(tri.a * 7 + tri.b * 13 + tri.c * 23).let { if (it < 0) -it else it } % colors.size]
                }
                else -> { // "elevation"
                    val t = ((avgElev + 1f) / 2f).coerceIn(0f, 1f)
                    palette.lerpColor(t)
                }
            }

            // Apply lighting shade to color
            val r = (Color.red(baseColor) * shade).toInt().coerceIn(0, 255)
            val g = (Color.green(baseColor) * shade).toInt().coerceIn(0, 255)
            val b = (Color.blue(baseColor) * shade).toInt().coerceIn(0, 255)
            fillPaint.color = Color.rgb(r, g, b)

            val path = Path().apply {
                moveTo(pointsX[tri.a], pointsY[tri.a])
                lineTo(pointsX[tri.b], pointsY[tri.b])
                lineTo(pointsX[tri.c], pointsY[tri.c])
                close()
            }

            canvas.drawPath(path, fillPaint)
            if (showEdges) {
                canvas.drawPath(path, strokePaint)
            }
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
