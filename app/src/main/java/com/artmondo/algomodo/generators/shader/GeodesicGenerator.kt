package com.artmondo.algomodo.generators.shader

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import com.artmondo.algomodo.core.rng.SeededRNG
import com.artmondo.algomodo.data.palettes.Palette
import com.artmondo.algomodo.generators.Generator
import com.artmondo.algomodo.generators.ParamGroup
import com.artmondo.algomodo.generators.Parameter
import com.artmondo.algomodo.generators.Quality
import kotlin.math.*

class GeodesicGenerator : Generator {

    override val id = "shader-geodesic-3d"
    override val family = "shader"
    override val styleName = "Geodesic 3D"
    override val definition =
        "Geodesic sphere wireframe rendered via distance field from subdivided icosahedron edges."
    override val algorithmNotes =
        "Builds icosahedron, subdivides faces N times. SDF is distance to nearest edge segment of the geodesic mesh. " +
        "Distance-based palette coloring creates gradient effects on the wireframe."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema = listOf(
        Parameter.NumberParam("Camera Dist", "cameraDistance", ParamGroup.COMPOSITION,
            "Distance of camera from origin", 1f, 12f, 0.5f, 4f),
        Parameter.NumberParam("FOV", "fov", ParamGroup.COMPOSITION,
            "Field of view in degrees", 20f, 120f, 5f, 60f),
        Parameter.NumberParam("Camera Angle", "cameraAngle", ParamGroup.COMPOSITION,
            "Horizontal camera orbit angle", 0f, 360f, 5f, 0f),
        Parameter.NumberParam("Camera Height", "cameraHeight", ParamGroup.COMPOSITION,
            "Vertical camera position", -2f, 3f, 0.1f, 0.5f),
        Parameter.NumberParam("Light Angle", "lightAngle", ParamGroup.TEXTURE,
            "Horizontal light direction", 0f, 360f, 5f, 45f),
        Parameter.NumberParam("Light Height", "lightHeight", ParamGroup.TEXTURE,
            "Vertical light direction", 0.1f, 2f, 0.1f, 0.8f),
        Parameter.NumberParam("Exposure", "exposure", ParamGroup.TEXTURE,
            "Tone mapping exposure", 0.5f, 3f, 0.1f, 1.2f),
        Parameter.NumberParam("Speed", "speed", ParamGroup.FLOW_MOTION,
            "Animation speed", 0.1f, 2f, 0.1f, 0.5f),
        Parameter.NumberParam("Subdivisions", "subdivisions", ParamGroup.GEOMETRY,
            "Geodesic subdivision level (0-3)", 0f, 3f, 1f, 2f),
        Parameter.NumberParam("Wire Thickness", "wireThickness", ParamGroup.GEOMETRY,
            "Wireframe edge thickness", 0.01f, 0.1f, 0.005f, 0.03f),
        Parameter.NumberParam("Radius", "radius", ParamGroup.GEOMETRY,
            "Sphere radius", 0.5f, 2f, 0.1f, 1.2f)
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "cameraDistance" to 4f, "fov" to 60f, "cameraAngle" to 0f, "cameraHeight" to 0.5f,
        "lightAngle" to 45f, "lightHeight" to 0.8f, "exposure" to 1.2f, "speed" to 0.5f,
        "subdivisions" to 2f, "wireThickness" to 0.03f, "radius" to 1.2f
    )

    override fun renderCanvas(
        canvas: Canvas, bitmap: Bitmap, params: Map<String, Any>,
        seed: Int, palette: Palette, quality: Quality, time: Float
    ) {
        val w = bitmap.width; val h = bitmap.height
        val cfg = getQualityConfig(quality, "medium", time > 0f)
        val renderW = (w * cfg.scale).toInt().coerceAtLeast(60)
        val renderH = (h * cfg.scale).toInt().coerceAtLeast(60)

        val camDist = extractFloat(params, "cameraDistance", 4f)
        val fov = extractFloat(params, "fov", 60f)
        val camAngle = extractFloat(params, "cameraAngle", 0f)
        val camHeight = extractFloat(params, "cameraHeight", 0.5f)
        val lightAngle = extractFloat(params, "lightAngle", 45f)
        val lightHeight = extractFloat(params, "lightHeight", 0.8f)
        val exposure = extractFloat(params, "exposure", 1.2f)
        val spd = extractFloat(params, "speed", 0.5f)
        val subdivs = extractFloat(params, "subdivisions", 2f).toInt().coerceIn(0, 3)
        val wireThick = extractFloat(params, "wireThickness", 0.03f)
        val radius = extractFloat(params, "radius", 1.2f)
        val t = time * spd

        // Build geodesic mesh edges
        val edges = buildGeodesicEdges(subdivs, radius)

        // Animated rotation
        val rotY = t * 0.4f
        val cosR = cos(rotY); val sinR = sin(rotY)
        val rotX = t * 0.2f
        val cosRX = cos(rotX); val sinRX = sin(rotX)

        val ro = FloatArray(3)
        cameraFromParams(camDist, camAngle, camHeight, ro)
        val cam = buildCamera(ro[0], ro[1], ro[2], 0f, 0f, 0f, fov, renderW.toFloat() / renderH)
        val ld = FloatArray(3)
        lightDirFromParams(lightAngle, lightHeight, ld)

        val colors = palette.colorInts()
        val nColors = colors.size

        // SDF: distance to nearest edge (with rotation)
        val sceneSDF: SceneSDF = { px, py, pz ->
            // Rotate point inversely
            val rx = px * cosR - pz * sinR
            val rz = px * sinR + pz * cosR
            val ry2 = py * cosRX - rz * sinRX
            val rz2 = py * sinRX + rz * cosRX
            distToEdges(rx, ry2, rz2, edges) - wireThick
        }

        val topR = 0.03f; val topG = 0.04f; val topB = 0.1f
        val botR = 0.06f; val botG = 0.04f; val botB = 0.08f

        val pixels = IntArray(renderW * renderH)
        val invW = 2f / renderW; val invH = 2f / renderH

        renderMultithreaded(renderW, renderH, pixels) { y0, y1, rd, normal, tm, mr ->
            val sky = FloatArray(3)
            for (py in y0 until y1) {
                val v = 1f - py * invH
                for (px in 0 until renderW) {
                    val u = px * invW - 1f
                    getRayDir(rd, u, v, cam)
                    rayMarch(mr, ro[0], ro[1], ro[2], rd[0], rd[1], rd[2], sceneSDF, cfg.maxSteps, cfg.epsilon, cfg.maxDist)

                    if (mr.hit) {
                        calcNormal(normal, mr.px, mr.py, mr.pz, sceneSDF, cfg.epsilon * 2f)
                        val vx = ro[0] - mr.px; val vy = ro[1] - mr.py; val vz = ro[2] - mr.pz
                        val vLen = vec3Length(vx, vy, vz).let { if (it == 0f) 1f else it }

                        // Color from position on sphere
                        val colorT = (atan2(mr.pz, mr.px) / PI.toFloat() * 0.5f + 0.5f + mr.py * 0.3f).coerceIn(0f, 1f)
                        val ci = (colorT * (nColors - 1)).toInt().coerceIn(0, nColors - 1)
                        val cr = Color.red(colors[ci]) / 255f
                        val cg = Color.green(colors[ci]) / 255f
                        val cb = Color.blue(colors[ci]) / 255f

                        val shade = phongShade(normal[0], normal[1], normal[2], vx / vLen, vy / vLen, vz / vLen, ld[0], ld[1], ld[2], 0.15f, 0.6f, 0.5f, 40f)

                        toneMapACES(tm, cr * shade, cg * shade, cb * shade, exposure)
                        pixels[py * renderW + px] = Color.rgb(tm[0].toInt().coerceIn(0, 255), tm[1].toInt().coerceIn(0, 255), tm[2].toInt().coerceIn(0, 255))
                    } else {
                        skyGradient(sky, rd[1], topR, topG, topB, botR, botG, botB)
                        toneMapACES(tm, sky[0], sky[1], sky[2], exposure)
                        pixels[py * renderW + px] = Color.rgb(tm[0].toInt().coerceIn(0, 255), tm[1].toInt().coerceIn(0, 255), tm[2].toInt().coerceIn(0, 255))
                    }
                }
            }
        }

        renderToCanvas(canvas, bitmap, renderW, renderH, pixels)
    }

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float {
        val subdivs = extractFloat(params, "subdivisions", 2f).toInt()
        return (0.4f + subdivs * 0.15f).coerceIn(0.4f, 0.9f)
    }

    companion object {
        // Edge: 6 floats [ax, ay, az, bx, by, bz]
        fun buildGeodesicEdges(subdivs: Int, radius: Float): FloatArray {
            // Icosahedron vertices
            val phi = (1f + sqrt(5f)) / 2f
            val verts = mutableListOf<FloatArray>()
            val baseVerts = arrayOf(
                floatArrayOf(-1f, phi, 0f), floatArrayOf(1f, phi, 0f),
                floatArrayOf(-1f, -phi, 0f), floatArrayOf(1f, -phi, 0f),
                floatArrayOf(0f, -1f, phi), floatArrayOf(0f, 1f, phi),
                floatArrayOf(0f, -1f, -phi), floatArrayOf(0f, 1f, -phi),
                floatArrayOf(phi, 0f, -1f), floatArrayOf(phi, 0f, 1f),
                floatArrayOf(-phi, 0f, -1f), floatArrayOf(-phi, 0f, 1f)
            )
            // Normalize to radius
            for (v in baseVerts) {
                val len = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
                verts.add(floatArrayOf(v[0] / len * radius, v[1] / len * radius, v[2] / len * radius))
            }

            // Icosahedron faces (20 triangles)
            var faces = mutableListOf(
                intArrayOf(0,11,5), intArrayOf(0,5,1), intArrayOf(0,1,7), intArrayOf(0,7,10), intArrayOf(0,10,11),
                intArrayOf(1,5,9), intArrayOf(5,11,4), intArrayOf(11,10,2), intArrayOf(10,7,6), intArrayOf(7,1,8),
                intArrayOf(3,9,4), intArrayOf(3,4,2), intArrayOf(3,2,6), intArrayOf(3,6,8), intArrayOf(3,8,9),
                intArrayOf(4,9,5), intArrayOf(2,4,11), intArrayOf(6,2,10), intArrayOf(8,6,7), intArrayOf(9,8,1)
            )

            // Subdivide
            for (s in 0 until subdivs) {
                val midCache = HashMap<Long, Int>()
                val newFaces = mutableListOf<IntArray>()

                fun midpoint(a: Int, b: Int): Int {
                    val key = if (a < b) a.toLong() * 100000 + b else b.toLong() * 100000 + a
                    midCache[key]?.let { return it }
                    val va = verts[a]; val vb = verts[b]
                    val mx = (va[0] + vb[0]) * 0.5f
                    val my = (va[1] + vb[1]) * 0.5f
                    val mz = (va[2] + vb[2]) * 0.5f
                    val len = sqrt(mx * mx + my * my + mz * mz)
                    val idx = verts.size
                    verts.add(floatArrayOf(mx / len * radius, my / len * radius, mz / len * radius))
                    midCache[key] = idx
                    return idx
                }

                for (face in faces) {
                    val a = face[0]; val b = face[1]; val c = face[2]
                    val ab = midpoint(a, b); val bc = midpoint(b, c); val ca = midpoint(c, a)
                    newFaces.add(intArrayOf(a, ab, ca))
                    newFaces.add(intArrayOf(b, bc, ab))
                    newFaces.add(intArrayOf(c, ca, bc))
                    newFaces.add(intArrayOf(ab, bc, ca))
                }
                faces = newFaces
            }

            // Collect unique edges
            val edgeSet = HashSet<Long>()
            for (face in faces) {
                for (i in 0 until 3) {
                    val a = face[i]; val b = face[(i + 1) % 3]
                    val key = if (a < b) a.toLong() * 100000 + b else b.toLong() * 100000 + a
                    edgeSet.add(key)
                }
            }

            // Pack into float array [ax,ay,az, bx,by,bz, ...]
            val result = FloatArray(edgeSet.size * 6)
            var idx = 0
            for (key in edgeSet) {
                val a = (key / 100000).toInt(); val b = (key % 100000).toInt()
                val va = verts[a]; val vb = verts[b]
                result[idx++] = va[0]; result[idx++] = va[1]; result[idx++] = va[2]
                result[idx++] = vb[0]; result[idx++] = vb[1]; result[idx++] = vb[2]
            }
            return result
        }

        fun distToEdges(px: Float, py: Float, pz: Float, edges: FloatArray): Float {
            var minDist = Float.MAX_VALUE
            val numEdges = edges.size / 6
            for (i in 0 until numEdges) {
                val off = i * 6
                val ax = edges[off]; val ay = edges[off + 1]; val az = edges[off + 2]
                val bx = edges[off + 3]; val by = edges[off + 4]; val bz = edges[off + 5]
                val d = distToSegment(px, py, pz, ax, ay, az, bx, by, bz)
                if (d < minDist) minDist = d
            }
            return minDist
        }

        private fun distToSegment(
            px: Float, py: Float, pz: Float,
            ax: Float, ay: Float, az: Float,
            bx: Float, by: Float, bz: Float
        ): Float {
            val abx = bx - ax; val aby = by - ay; val abz = bz - az
            val apx = px - ax; val apy = py - ay; val apz = pz - az
            val abLenSq = abx * abx + aby * aby + abz * abz
            val t = ((apx * abx + apy * aby + apz * abz) / (if (abLenSq == 0f) 1f else abLenSq)).coerceIn(0f, 1f)
            val cx = ax + t * abx - px; val cy = ay + t * aby - py; val cz = az + t * abz - pz
            return sqrt(cx * cx + cy * cy + cz * cz)
        }
    }
}
