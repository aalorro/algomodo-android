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

class TwistedFormsGenerator : Generator {

    override val id = "shader-twisted-forms"
    override val family = "shader"
    override val styleName = "Twisted Forms"
    override val definition =
        "Domain-twisted SDF geometries producing organic sculptural shapes."
    override val algorithmNotes =
        "Apply domain deformation (Twist/Bend/NoiseDisplace/Ripple/TwistAndBend) to input point, " +
        "then evaluate base SDF (Box/Cylinder/Torus/Capsule/Octahedron). Distance halved to compensate for Lipschitz bound breaking."
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
        Parameter.SelectParam("Deformation", "deformation", ParamGroup.GEOMETRY,
            "Domain deformation type",
            listOf("Twist", "Bend", "NoiseDisplace", "Ripple", "TwistAndBend"), "Twist"),
        Parameter.SelectParam("Base Shape", "baseShape", ParamGroup.GEOMETRY,
            "Base SDF primitive",
            listOf("Box", "Cylinder", "Torus", "Capsule", "Octahedron"), "Torus"),
        Parameter.NumberParam("Deform Strength", "deformStrength", ParamGroup.GEOMETRY,
            "Deformation intensity", 0.5f, 5f, 0.25f, 2f),
        Parameter.NumberParam("Deform Freq", "deformFrequency", ParamGroup.GEOMETRY,
            "Spatial frequency of deformation", 0.5f, 4f, 0.25f, 1.5f)
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "cameraDistance" to 4f, "fov" to 60f, "cameraAngle" to 0f, "cameraHeight" to 0.5f,
        "lightAngle" to 45f, "lightHeight" to 0.8f, "exposure" to 1.2f, "speed" to 0.5f,
        "deformation" to "Twist", "baseShape" to "Torus",
        "deformStrength" to 2f, "deformFrequency" to 1.5f
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
        val deformType = extractString(params, "deformation", "Twist")
        val baseShape = extractString(params, "baseShape", "Torus")
        val deformStr = extractFloat(params, "deformStrength", 2f)
        val deformFreq = extractFloat(params, "deformFrequency", 1.5f)
        val t = time * spd

        val noise = Noise3D(seed)
        val animRate = deformStr + sin(t) * 0.5f

        val ro = FloatArray(3)
        cameraFromParams(camDist, camAngle, camHeight, ro)
        val cam = buildCamera(ro[0], ro[1], ro[2], 0f, 0f, 0f, fov, renderW.toFloat() / renderH)
        val ld = FloatArray(3)
        lightDirFromParams(lightAngle, lightHeight, ld)

        val colors = palette.colorInts()
        val nColors = colors.size

        val sceneSDF: SceneSDF = { px, py, pz ->
            // Apply deformation
            var qx = px; var qy = py; var qz = pz
            when (deformType) {
                "Twist" -> {
                    val a = qy * animRate; val c = cos(a); val s = sin(a)
                    val nx = qx * c - qz * s; val nz = qx * s + qz * c
                    qx = nx; qz = nz
                }
                "Bend" -> {
                    val a = qx * animRate * 0.5f; val c = cos(a); val s = sin(a)
                    val nx = qx * c - qy * s; val ny = qx * s + qy * c
                    qx = nx; qy = ny
                }
                "NoiseDisplace" -> {
                    val n = noise.fbm3D(px * deformFreq + t * 0.2f, py * deformFreq, pz * deformFreq, 3)
                    qx += n * deformStr * 0.2f
                    qy += noise.noise3D(py * deformFreq + 17f, pz * deformFreq, px * deformFreq + t * 0.2f) * deformStr * 0.2f
                    qz += noise.noise3D(pz * deformFreq + 31f, px * deformFreq + t * 0.2f, py * deformFreq) * deformStr * 0.2f
                }
                "Ripple" -> {
                    val dist = sqrt(qx * qx + qz * qz)
                    val ripple = sin(dist * deformFreq * 3f - t * 2f) * deformStr * 0.1f
                    qy += ripple
                }
                "TwistAndBend" -> {
                    // Twist
                    val a1 = qy * animRate * 0.5f; val c1 = cos(a1); val s1 = sin(a1)
                    val tx = qx * c1 - qz * s1; val tz = qx * s1 + qz * c1
                    qx = tx; qz = tz
                    // Bend
                    val a2 = qx * animRate * 0.3f; val c2 = cos(a2); val s2 = sin(a2)
                    val bx = qx * c2 - qy * s2; val by = qx * s2 + qy * c2
                    qx = bx; qy = by
                }
            }

            // Evaluate base shape
            val d = when (baseShape) {
                "Box" -> sdBox(qx, qy, qz, 0.8f, 0.8f, 0.8f)
                "Cylinder" -> sdCylinder(qx, qy, qz, 0.6f, 1.2f)
                "Capsule" -> sdCapsule(qx, qy, qz, 0f, -1f, 0f, 0f, 1f, 0f, 0.4f)
                "Octahedron" -> sdOctahedron(qx, qy, qz, 1f)
                else -> sdTorus(qx, qy, qz, 1f, 0.35f)
            }
            // Halve distance to compensate for domain deformation
            d * 0.5f
        }

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

                        // Color from normal matcap
                        val colorT = (normal[0] * 0.5f + 0.5f + normal[1] * 0.3f).coerceIn(0f, 1f)
                        val ci = (colorT * (nColors - 1)).toInt().coerceIn(0, nColors - 1)
                        val cr = Color.red(colors[ci]) / 255f
                        val cg = Color.green(colors[ci]) / 255f
                        val cb = Color.blue(colors[ci]) / 255f

                        val shade = phongShade(normal[0], normal[1], normal[2], vx / vLen, vy / vLen, vz / vLen, ld[0], ld[1], ld[2], 0.15f, 0.65f, 0.5f, 35f)
                        val ao = ambientOcclusion(mr.px, mr.py, mr.pz, normal[0], normal[1], normal[2], sceneSDF, 5)

                        toneMapACES(tm, cr * shade * ao, cg * shade * ao, cb * shade * ao, exposure)
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
        val deform = extractString(params, "deformation", "Twist")
        return if (deform == "NoiseDisplace") 0.7f else 0.5f
    }
}
