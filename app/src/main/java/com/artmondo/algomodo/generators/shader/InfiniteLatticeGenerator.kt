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

class InfiniteLatticeGenerator : Generator {

    override val id = "shader-infinite-lattice"
    override val family = "shader"
    override val styleName = "Infinite Lattice"
    override val definition =
        "Procedurally tiled 3D lattice structures using domain repetition."
    override val algorithmNotes =
        "Domain repetition (opRepeat) on 1-3 axes of a single SDF primitive creates infinite lattice structures. " +
        "10 shape presets. Hash-based per-cell variation for the MixedHashed mode. Exponential fog hides far clipping."
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
        Parameter.SelectParam("Shape", "latticeShape", ParamGroup.GEOMETRY,
            "Lattice element shape",
            listOf("Sphere", "Box", "Pillar", "Cross", "Capsule", "Torus", "Octahedron", "Wireframe", "Pyramid", "MixedHashed"),
            "Sphere"),
        Parameter.NumberParam("Cell Size", "cellSize", ParamGroup.GEOMETRY,
            "Spacing between repeated elements", 1.5f, 6f, 0.5f, 3f),
        Parameter.SelectParam("Repeat Axes", "repeatAxes", ParamGroup.GEOMETRY,
            "Which axes to repeat along",
            listOf("XYZ", "XZ", "XY", "X"), "XYZ"),
        Parameter.NumberParam("Fog", "fog", ParamGroup.TEXTURE,
            "Exponential fog density", 0f, 0.3f, 0.01f, 0.08f)
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "cameraDistance" to 4f, "fov" to 60f, "cameraAngle" to 0f, "cameraHeight" to 0.5f,
        "lightAngle" to 45f, "lightHeight" to 0.8f, "exposure" to 1.2f, "speed" to 0.5f,
        "latticeShape" to "Sphere", "cellSize" to 3f, "repeatAxes" to "XYZ", "fog" to 0.08f
    )

    override fun renderCanvas(
        canvas: Canvas, bitmap: Bitmap, params: Map<String, Any>,
        seed: Int, palette: Palette, quality: Quality, time: Float
    ) {
        val w = bitmap.width; val h = bitmap.height
        val cfg = getQualityConfig(quality, "light", time > 0f)
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
        val shape = extractString(params, "latticeShape", "Sphere")
        val cellSize = extractFloat(params, "cellSize", 3f)
        val axes = extractString(params, "repeatAxes", "XYZ")
        val fogDensity = extractFloat(params, "fog", 0.08f)
        val t = time * spd

        val rng = SeededRNG(seed)
        val rotPhase = rng.randomAngle()

        // Camera drifts forward during animation
        val driftZ = t * 2f
        val ro = FloatArray(3)
        cameraFromParams(camDist, camAngle, camHeight, ro)
        ro[2] += driftZ
        val cam = buildCamera(ro[0], ro[1], ro[2], ro[0], ro[1], ro[2] - 1f, fov, renderW.toFloat() / renderH)
        val ld = FloatArray(3)
        lightDirFromParams(lightAngle, lightHeight, ld)

        val colors = palette.colorInts()
        val nColors = colors.size

        val shapeId = when (shape) {
            "Box" -> 1; "Pillar" -> 2; "Cross" -> 3; "Capsule" -> 4
            "Torus" -> 5; "Octahedron" -> 6; "Wireframe" -> 7; "Pyramid" -> 8; "MixedHashed" -> 9
            else -> 0
        }
        val repX = axes.contains('X'); val repY = axes.contains('Y'); val repZ = axes.contains('Z')
        val halfCell = cellSize * 0.5f
        val elemSize = cellSize * 0.2f

        val sceneSDF: SceneSDF = { px, py, pz ->
            var qx = px; var qy = py; var qz = pz
            if (repX) qx = opRepeat(qx, cellSize)
            if (repY) qy = opRepeat(qy, cellSize)
            if (repZ) qz = opRepeat(qz, cellSize)

            val sid = if (shapeId == 9) {
                // MixedHashed: pick shape per cell
                val cx = floor(px / cellSize).toInt()
                val cy = floor(py / cellSize).toInt()
                val cz = floor(pz / cellSize).toInt()
                (((cx * 374761393 + cy * 668265263 + cz * 1274126177) ushr 16) % 8)
            } else shapeId

            when (sid) {
                1 -> sdBox(qx, qy, qz, elemSize, elemSize, elemSize)
                2 -> sdCylinder(qx, qy, qz, elemSize * 0.6f, elemSize * 2f)
                3 -> {
                    val d1 = sdBox(qx, qy, qz, elemSize * 2f, elemSize * 0.3f, elemSize * 0.3f)
                    val d2 = sdBox(qx, qy, qz, elemSize * 0.3f, elemSize * 2f, elemSize * 0.3f)
                    val d3 = sdBox(qx, qy, qz, elemSize * 0.3f, elemSize * 0.3f, elemSize * 2f)
                    minOf(d1, d2, d3)
                }
                4 -> sdCapsule(qx, qy, qz, 0f, -elemSize, 0f, 0f, elemSize, 0f, elemSize * 0.4f)
                5 -> sdTorus(qx, qy, qz, elemSize, elemSize * 0.3f)
                6 -> sdOctahedron(qx, qy, qz, elemSize)
                7 -> {
                    // Wireframe box: box - slightly smaller box
                    val outer = sdBox(qx, qy, qz, elemSize, elemSize, elemSize)
                    val inner = sdBox(qx, qy, qz, elemSize * 0.8f, elemSize * 0.8f, elemSize * 0.8f)
                    maxOf(outer, -inner)
                }
                8 -> sdPyramid(qx, qy - elemSize, qz, elemSize * 2f)
                else -> sdSphere(qx, qy, qz, elemSize)
            }
        }

        // Sky colors
        val topColor = if (nColors > 1) colors[1] else Color.rgb(10, 10, 30)
        val botColor = if (nColors > 2) colors[2] else Color.rgb(30, 20, 40)
        val topR = Color.red(topColor) / 255f; val topG = Color.green(topColor) / 255f; val topB = Color.blue(topColor) / 255f
        val botR = Color.red(botColor) / 255f; val botG = Color.green(botColor) / 255f; val botB = Color.blue(botColor) / 255f

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

                        // Color from position
                        val colorT = (sin(mr.px * 0.5f + mr.pz * 0.3f) * 0.5f + 0.5f).coerceIn(0f, 1f)
                        val ci = (colorT * (nColors - 1)).toInt().coerceIn(0, nColors - 1)
                        val cr = Color.red(colors[ci]) / 255f
                        val cg = Color.green(colors[ci]) / 255f
                        val cb = Color.blue(colors[ci]) / 255f

                        val shade = phongShade(normal[0], normal[1], normal[2], vx / vLen, vy / vLen, vz / vLen, ld[0], ld[1], ld[2], 0.15f, 0.7f, 0.4f, 30f)

                        // Fog
                        val fogAmt = 1f - exp(-mr.t * fogDensity)
                        val r = cr * shade * (1f - fogAmt)
                        val g = cg * shade * (1f - fogAmt)
                        val b = cb * shade * (1f - fogAmt)

                        toneMapACES(tm, r, g, b, exposure)
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

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float = 0.4f
}
