package com.artmondo.algomodo.generators.graphs

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import com.artmondo.algomodo.data.palettes.Palette
import com.artmondo.algomodo.generators.Generator
import com.artmondo.algomodo.generators.ParamGroup
import com.artmondo.algomodo.generators.Parameter
import com.artmondo.algomodo.generators.Quality
import com.artmondo.algomodo.rendering.SvgBuilder
import com.artmondo.algomodo.rendering.SvgPath
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Geodesic dome/sphere generator.
 *
 * Starts from an icosahedron (12 vertices, 20 faces) and recursively subdivides
 * each triangular face, projecting new vertices onto the unit sphere. The result
 * is projected to 2D using orthographic or stereographic projection, with optional
 * back-face culling and painter's algorithm depth sorting.
 */
class GraphGeodesicGenerator : Generator {

    override val id = "graph-geodesic"
    override val family = "graphs"
    override val styleName = "Geodesic"
    override val definition =
        "Icosahedron-based geodesic sphere subdivision projected to 2D, creating intricate triangulated dome patterns."
    override val algorithmNotes =
        "An icosahedron's 12 vertices and 20 triangular faces are recursively subdivided. Each edge midpoint " +
        "is projected onto the unit sphere. The subdivision level V produces 20×4^V triangles. A 3D rotation " +
        "matrix is applied, then orthographic or stereographic projection flattens to 2D. Back-face culling " +
        "removes rear-facing triangles. Painter's algorithm sorts front-to-back for correct occlusion."
    override val supportsVector = true
    override val supportsAnimation = true

    override val parameterSchema = listOf(
        Parameter.NumberParam("Subdivision", "subdivLevel", ParamGroup.GEOMETRY, "Geodesic frequency (1-5)", 1f, 5f, 1f, 3f),
        Parameter.NumberParam("Radius", "radius", ParamGroup.COMPOSITION, "Sphere radius as fraction of canvas", 0.2f, 0.48f, 0.02f, 0.38f),
        Parameter.NumberParam("Rotate X", "rotateX", ParamGroup.COMPOSITION, "Viewing angle X in degrees", 0f, 360f, 5f, 30f),
        Parameter.NumberParam("Rotate Y", "rotateY", ParamGroup.COMPOSITION, "Viewing angle Y in degrees", 0f, 360f, 5f, 45f),
        Parameter.BooleanParam("Fill Triangles", "fillTriangles", ParamGroup.TEXTURE, null, true),
        Parameter.NumberParam("Fill Opacity", "fillOpacity", ParamGroup.TEXTURE, null, 0.1f, 1f, 0.05f, 0.6f),
        Parameter.NumberParam("Edge Width", "edgeWidth", ParamGroup.GEOMETRY, null, 0.5f, 4f, 0.5f, 1.5f),
        Parameter.BooleanParam("Back Face Cull", "backFaceCull", ParamGroup.GEOMETRY, "Hide triangles facing away", true),
        Parameter.SelectParam("Color Mode", "colorMode", ParamGroup.COLOR, null, listOf("depth", "face-index", "latitude", "radial"), "depth"),
        Parameter.SelectParam("Projection", "projection", ParamGroup.GEOMETRY, null, listOf("orthographic", "stereographic"), "orthographic"),
        Parameter.SelectParam("Animation", "animMode", ParamGroup.FLOW_MOTION, null, listOf("none", "rotate", "breathe", "explode"), "rotate"),
        Parameter.NumberParam("Speed", "speed", ParamGroup.FLOW_MOTION, null, 0.05f, 1f, 0.05f, 0.3f)
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "subdivLevel" to 3f,
        "radius" to 0.38f,
        "rotateX" to 30f,
        "rotateY" to 45f,
        "fillTriangles" to true,
        "fillOpacity" to 0.6f,
        "edgeWidth" to 1.5f,
        "backFaceCull" to true,
        "colorMode" to "depth",
        "projection" to "orthographic",
        "animMode" to "rotate",
        "speed" to 0.3f
    )

    private data class Vec3(val x: Float, val y: Float, val z: Float) {
        fun normalized(): Vec3 {
            val len = sqrt(x * x + y * y + z * z)
            return if (len > 0f) Vec3(x / len, y / len, z / len) else this
        }
        fun mid(other: Vec3) = Vec3((x + other.x) / 2f, (y + other.y) / 2f, (z + other.z) / 2f)
    }

    private data class Face(val a: Int, val b: Int, val c: Int)

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
        val subdivLevel = qualityClampSubdiv((params["subdivLevel"] as? Number)?.toInt() ?: 3, quality)
        val radiusFrac = (params["radius"] as? Number)?.toFloat() ?: 0.38f
        var rotX = (params["rotateX"] as? Number)?.toFloat() ?: 30f
        var rotY = (params["rotateY"] as? Number)?.toFloat() ?: 45f
        val fillTriangles = (params["fillTriangles"] as? Boolean) ?: true
        val fillOpacity = (params["fillOpacity"] as? Number)?.toFloat() ?: 0.6f
        val edgeWidth = (params["edgeWidth"] as? Number)?.toFloat() ?: 1.5f
        val backFaceCull = (params["backFaceCull"] as? Boolean) ?: true
        val colorMode = (params["colorMode"] as? String) ?: "depth"
        val projection = (params["projection"] as? String) ?: "orthographic"
        val animMode = (params["animMode"] as? String) ?: "rotate"
        val speed = (params["speed"] as? Number)?.toFloat() ?: 0.3f

        val radius = minOf(w, h) * radiusFrac

        // Animation
        when (animMode) {
            "rotate" -> rotY += time * speed * 60f
            "breathe" -> {} // handled at radius level below
        }
        val animRadius = if (animMode == "breathe") {
            radius * (1f + 0.1f * sin(time * speed * 3f))
        } else radius
        val explodeT = if (animMode == "explode") (time * speed * 0.5f).coerceIn(0f, 1f) else 0f

        // Build geodesic sphere
        val (verts, faces) = buildGeodesicSphere(subdivLevel)

        // Rotate all vertices
        val radX = rotX * PI.toFloat() / 180f
        val radY = rotY * PI.toFloat() / 180f
        val rotated = Array(verts.size) { i -> rotateXY(verts[i], radX, radY) }

        // Project and render
        canvas.drawColor(Color.BLACK)

        val cx = w / 2f; val cy = h / 2f

        // Compute screen coordinates
        val sx = FloatArray(rotated.size)
        val sy = FloatArray(rotated.size)
        val sz = FloatArray(rotated.size)
        for (i in rotated.indices) {
            val v = rotated[i]
            sz[i] = v.z
            when (projection) {
                "stereographic" -> {
                    val scale = if (v.z < 0.99f) 1f / (1f - v.z) else 10f
                    sx[i] = cx + v.x * animRadius * scale * 0.5f
                    sy[i] = cy + v.y * animRadius * scale * 0.5f
                }
                else -> { // orthographic
                    sx[i] = cx + v.x * animRadius
                    sy[i] = cy + v.y * animRadius
                }
            }
        }

        // Sort faces by average Z (painter's algorithm, back to front)
        val faceOrder = faces.indices.sortedBy {
            val f = faces[it]
            (sz[f.a] + sz[f.b] + sz[f.c]) / 3f
        }

        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = edgeWidth
            color = Color.argb(200, 255, 255, 255)
        }

        val colors = palette.colorInts()

        for (fi in faceOrder) {
            val f = faces[fi]
            val va = rotated[f.a]; val vb = rotated[f.b]; val vc = rotated[f.c]

            // Normal for back-face culling (in rotated space, camera looks along -Z)
            val e1x = vb.x - va.x; val e1y = vb.y - va.y; val e1z = vb.z - va.z
            val e2x = vc.x - va.x; val e2y = vc.y - va.y; val e2z = vc.z - va.z
            val nz = e1x * e2y - e1y * e2x // z-component of cross product
            if (backFaceCull && nz <= 0f) continue

            // Explode: push triangle outward from center
            var fsx = floatArrayOf(sx[f.a], sx[f.b], sx[f.c])
            var fsy = floatArrayOf(sy[f.a], sy[f.b], sy[f.c])
            if (explodeT > 0f) {
                val fcx = (va.x + vb.x + vc.x) / 3f
                val fcy = (va.y + vb.y + vc.y) / 3f
                val fcz = (va.z + vb.z + vc.z) / 3f
                val pushDist = explodeT * animRadius * 0.5f
                val len = sqrt(fcx * fcx + fcy * fcy + fcz * fcz).coerceAtLeast(0.01f)
                val ox = fcx / len * pushDist
                val oy = fcy / len * pushDist
                fsx = floatArrayOf(sx[f.a] + ox, sx[f.b] + ox, sx[f.c] + ox)
                fsy = floatArrayOf(sy[f.a] + oy, sy[f.b] + oy, sy[f.c] + oy)
            }

            val path = Path().apply {
                moveTo(fsx[0], fsy[0])
                lineTo(fsx[1], fsy[1])
                lineTo(fsx[2], fsy[2])
                close()
            }

            if (fillTriangles) {
                val avgZ = (sz[f.a] + sz[f.b] + sz[f.c]) / 3f
                val t = when (colorMode) {
                    "depth" -> ((avgZ + 1f) / 2f).coerceIn(0f, 1f)
                    "face-index" -> (fi.toFloat() / faces.size.coerceAtLeast(1)).coerceIn(0f, 1f)
                    "latitude" -> {
                        val avgY = (va.y + vb.y + vc.y) / 3f
                        ((avgY + 1f) / 2f).coerceIn(0f, 1f)
                    }
                    "radial" -> {
                        val avgR = sqrt(((va.x + vb.x + vc.x) / 3f).let { it * it } +
                                       ((va.y + vb.y + vc.y) / 3f).let { it * it })
                        avgR.coerceIn(0f, 1f)
                    }
                    else -> ((avgZ + 1f) / 2f).coerceIn(0f, 1f)
                }
                val baseColor = palette.lerpColor(t)
                // Modulate brightness by face normal Z for shading
                val shade = (nz * 0.4f + 0.6f).coerceIn(0.3f, 1f)
                val alpha = (fillOpacity * 255).toInt().coerceIn(10, 255)
                fillPaint.color = Color.argb(alpha,
                    (Color.red(baseColor) * shade).toInt().coerceIn(0, 255),
                    (Color.green(baseColor) * shade).toInt().coerceIn(0, 255),
                    (Color.blue(baseColor) * shade).toInt().coerceIn(0, 255))
                canvas.drawPath(path, fillPaint)
            }

            canvas.drawPath(path, strokePaint)
        }
    }

    override fun renderVector(
        params: Map<String, Any>,
        seed: Int,
        palette: Palette
    ): List<SvgPath> {
        val w = 1080f; val h = 1080f
        val subdivLevel = (params["subdivLevel"] as? Number)?.toInt() ?: 3
        val radiusFrac = (params["radius"] as? Number)?.toFloat() ?: 0.38f
        val rotX = (params["rotateX"] as? Number)?.toFloat() ?: 30f
        val rotY = (params["rotateY"] as? Number)?.toFloat() ?: 45f
        val fillTriangles = (params["fillTriangles"] as? Boolean) ?: true
        val edgeWidth = (params["edgeWidth"] as? Number)?.toFloat() ?: 1.5f
        val backFaceCull = (params["backFaceCull"] as? Boolean) ?: true
        val colorMode = (params["colorMode"] as? String) ?: "depth"

        val radius = minOf(w, h) * radiusFrac
        val (verts, faces) = buildGeodesicSphere(subdivLevel)
        val radX = rotX * PI.toFloat() / 180f
        val radY = rotY * PI.toFloat() / 180f
        val rotated = Array(verts.size) { i -> rotateXY(verts[i], radX, radY) }

        val cx = w / 2f; val cy = h / 2f
        val sx = FloatArray(rotated.size) { cx + rotated[it].x * radius }
        val sy = FloatArray(rotated.size) { cy + rotated[it].y * radius }
        val sz = FloatArray(rotated.size) { rotated[it].z }

        val faceOrder = faces.indices.sortedBy { val f = faces[it]; (sz[f.a] + sz[f.b] + sz[f.c]) / 3f }
        val paths = mutableListOf<SvgPath>()

        for (fi in faceOrder) {
            val f = faces[fi]
            val va = rotated[f.a]; val vb = rotated[f.b]; val vc = rotated[f.c]
            val e1x = vb.x - va.x; val e1y = vb.y - va.y
            val e2x = vc.x - va.x; val e2y = vc.y - va.y
            val nz = e1x * e2y - e1y * e2x
            if (backFaceCull && nz <= 0f) continue

            val avgZ = (sz[f.a] + sz[f.b] + sz[f.c]) / 3f
            val t = ((avgZ + 1f) / 2f).coerceIn(0f, 1f)
            val colorInt = palette.lerpColor(t)
            val shade = (nz * 0.4f + 0.6f).coerceIn(0.3f, 1f)
            val fill = if (fillTriangles) {
                String.format("#%02X%02X%02X",
                    (Color.red(colorInt) * shade).toInt().coerceIn(0, 255),
                    (Color.green(colorInt) * shade).toInt().coerceIn(0, 255),
                    (Color.blue(colorInt) * shade).toInt().coerceIn(0, 255))
            } else "none"

            val d = SvgBuilder.polygon(listOf(
                Pair(sx[f.a], sy[f.a]),
                Pair(sx[f.b], sy[f.b]),
                Pair(sx[f.c], sy[f.c])
            ))
            paths.add(SvgPath(d = d, fill = fill, stroke = "#FFFFFFC8", strokeWidth = edgeWidth))
        }
        return paths
    }

    private fun buildGeodesicSphere(subdivLevel: Int): Pair<List<Vec3>, List<Face>> {
        val phi = (1f + sqrt(5f)) / 2f

        val baseVerts = mutableListOf(
            Vec3(-1f, phi, 0f).normalized(), Vec3(1f, phi, 0f).normalized(),
            Vec3(-1f, -phi, 0f).normalized(), Vec3(1f, -phi, 0f).normalized(),
            Vec3(0f, -1f, phi).normalized(), Vec3(0f, 1f, phi).normalized(),
            Vec3(0f, -1f, -phi).normalized(), Vec3(0f, 1f, -phi).normalized(),
            Vec3(phi, 0f, -1f).normalized(), Vec3(phi, 0f, 1f).normalized(),
            Vec3(-phi, 0f, -1f).normalized(), Vec3(-phi, 0f, 1f).normalized()
        )

        var verts = baseVerts.toMutableList()
        var faces = mutableListOf(
            Face(0, 11, 5), Face(0, 5, 1), Face(0, 1, 7), Face(0, 7, 10), Face(0, 10, 11),
            Face(1, 5, 9), Face(5, 11, 4), Face(11, 10, 2), Face(10, 7, 6), Face(7, 1, 8),
            Face(3, 9, 4), Face(3, 4, 2), Face(3, 2, 6), Face(3, 6, 8), Face(3, 8, 9),
            Face(4, 9, 5), Face(2, 4, 11), Face(6, 2, 10), Face(8, 6, 7), Face(9, 8, 1)
        )

        // Subdivide
        for (level in 0 until subdivLevel) {
            val midCache = mutableMapOf<Long, Int>()
            val newFaces = mutableListOf<Face>()

            fun getMidpoint(a: Int, b: Int): Int {
                val key = if (a < b) (a.toLong() shl 20) or b.toLong() else (b.toLong() shl 20) or a.toLong()
                midCache[key]?.let { return it }
                val mid = verts[a].mid(verts[b]).normalized()
                val idx = verts.size
                verts.add(mid)
                midCache[key] = idx
                return idx
            }

            for (f in faces) {
                val ab = getMidpoint(f.a, f.b)
                val bc = getMidpoint(f.b, f.c)
                val ca = getMidpoint(f.c, f.a)
                newFaces.add(Face(f.a, ab, ca))
                newFaces.add(Face(f.b, bc, ab))
                newFaces.add(Face(f.c, ca, bc))
                newFaces.add(Face(ab, bc, ca))
            }
            faces = newFaces
        }

        return Pair(verts, faces)
    }

    private fun rotateXY(v: Vec3, rx: Float, ry: Float): Vec3 {
        // Rotate around X axis
        val cosX = cos(rx); val sinX = sin(rx)
        val y1 = v.y * cosX - v.z * sinX
        val z1 = v.y * sinX + v.z * cosX
        // Rotate around Y axis
        val cosY = cos(ry); val sinY = sin(ry)
        val x2 = v.x * cosY + z1 * sinY
        val z2 = -v.x * sinY + z1 * cosY
        return Vec3(x2, y1, z2)
    }

    private fun qualityClampSubdiv(base: Int, quality: Quality): Int = when (quality) {
        Quality.DRAFT -> (base - 1).coerceAtLeast(1)
        Quality.BALANCED -> base
        Quality.ULTRA -> (base + 1).coerceAtMost(5)
    }

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float {
        val level = (params["subdivLevel"] as? Number)?.toFloat() ?: 3f
        // 20 * 4^level triangles
        var count = 20f
        for (i in 0 until level.toInt()) count *= 4f
        return (count / 20000f).coerceIn(0.2f, 1f)
    }
}
