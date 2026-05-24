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

class MetaballCluster3dGenerator : Generator {

    override val id = "shader-metaball-cluster-3d"
    override val family = "shader"
    override val styleName = "Metaball Cluster 3D"
    override val definition =
        "Soft blobs that merge organically in 3D, rendered via smooth-minimum ray marching."
    override val algorithmNotes =
        "Places N spheres at seeded positions with slow circular orbits. sceneSDF combines all sphere distances " +
        "via polynomial smooth union. Normal-to-color matcap-style shading with ambient occlusion produces organic, jewel-like results."
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
        Parameter.NumberParam("Balls", "ballCount", ParamGroup.COMPOSITION,
            "Number of metaballs", 4f, 25f, 1f, 6f),
        Parameter.NumberParam("Smoothness", "smoothness", ParamGroup.GEOMETRY,
            "Merge smoothness between balls", 0.5f, 3f, 0.25f, 1.5f),
        Parameter.NumberParam("Motion Radius", "motionRadius", ParamGroup.FLOW_MOTION,
            "Orbital motion radius", 0.5f, 3f, 0.25f, 1.5f)
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "cameraDistance" to 4f, "fov" to 60f, "cameraAngle" to 0f, "cameraHeight" to 0.5f,
        "lightAngle" to 45f, "lightHeight" to 0.8f, "exposure" to 1.2f, "speed" to 0.5f,
        "ballCount" to 6f, "smoothness" to 1.5f, "motionRadius" to 1.5f
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
        val ballCount = extractFloat(params, "ballCount", 6f).toInt().coerceIn(2, 25)
        val smoothK = extractFloat(params, "smoothness", 1.5f)
        val motionR = extractFloat(params, "motionRadius", 1.5f)
        val t = time * spd

        val rng = SeededRNG(seed)

        // Generate ball properties
        val basePosX = FloatArray(ballCount)
        val basePosY = FloatArray(ballCount)
        val basePosZ = FloatArray(ballCount)
        val radii = FloatArray(ballCount)
        val orbitSpd = FloatArray(ballCount)
        val orbitPhase = FloatArray(ballCount)
        val orbitAxisX = FloatArray(ballCount)
        val orbitAxisY = FloatArray(ballCount)
        val orbitAxisZ = FloatArray(ballCount)

        for (i in 0 until ballCount) {
            basePosX[i] = rng.range(-1.5f, 1.5f)
            basePosY[i] = rng.range(-1.0f, 1.0f)
            basePosZ[i] = rng.range(-1.5f, 1.5f)
            radii[i] = rng.range(0.3f, 0.7f)
            orbitSpd[i] = rng.range(0.3f, 1.2f) * if (rng.random() > 0.5f) 1f else -1f
            orbitPhase[i] = rng.randomAngle()
            val ax = rng.range(-1f, 1f); val ay = rng.range(-1f, 1f); val az = rng.range(-1f, 1f)
            val aLen = sqrt(ax * ax + ay * ay + az * az).let { if (it == 0f) 1f else it }
            orbitAxisX[i] = ax / aLen; orbitAxisY[i] = ay / aLen; orbitAxisZ[i] = az / aLen
        }

        // Animated positions
        val animX = FloatArray(ballCount)
        val animY = FloatArray(ballCount)
        val animZ = FloatArray(ballCount)
        for (i in 0 until ballCount) {
            val a = t * orbitSpd[i] + orbitPhase[i]
            val ca = cos(a) * motionR * 0.3f
            val sa = sin(a) * motionR * 0.3f
            animX[i] = basePosX[i] + orbitAxisX[i] * ca
            animY[i] = basePosY[i] + orbitAxisY[i] * ca + orbitAxisZ[i] * sa * 0.5f
            animZ[i] = basePosZ[i] + orbitAxisZ[i] * ca + orbitAxisX[i] * sa * 0.5f
        }

        // Camera & light
        val ro = FloatArray(3)
        cameraFromParams(camDist, camAngle, camHeight, ro)
        val cam = buildCamera(ro[0], ro[1], ro[2], 0f, 0f, 0f, fov, renderW.toFloat() / renderH)
        val ld = FloatArray(3)
        lightDirFromParams(lightAngle, lightHeight, ld)

        // Palette colors
        val colors = palette.colorInts()
        val nColors = colors.size

        // Scene SDF
        val sceneSDF: SceneSDF = { px, py, pz ->
            var d = sdSphere(px - animX[0], py - animY[0], pz - animZ[0], radii[0])
            for (i in 1 until ballCount) {
                val di = sdSphere(px - animX[i], py - animY[i], pz - animZ[i], radii[i])
                d = opSmoothUnion(d, di, smoothK)
            }
            d
        }

        // Sky colors from palette
        val topColor = if (nColors > 2) colors[2] else Color.rgb(15, 15, 30)
        val botColor = if (nColors > 3) colors[3] else Color.rgb(40, 30, 50)
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

                        // Matcap coloring from normal
                        val nu = normal[0] * 0.5f + 0.5f
                        val nv = normal[1] * 0.5f + 0.5f
                        val colorT = (nu + nv) * 0.5f
                        val ci = colorT * (nColors - 1)
                        val c0 = ci.toInt().coerceIn(0, nColors - 1)
                        val c1 = (c0 + 1).coerceAtMost(nColors - 1)
                        val cf = ci - c0
                        val sr = (Color.red(colors[c0]) + (Color.red(colors[c1]) - Color.red(colors[c0])) * cf) / 255f
                        val sg = (Color.green(colors[c0]) + (Color.green(colors[c1]) - Color.green(colors[c0])) * cf) / 255f
                        val sb = (Color.blue(colors[c0]) + (Color.blue(colors[c1]) - Color.blue(colors[c0])) * cf) / 255f

                        val shade = phongShade(
                            normal[0], normal[1], normal[2],
                            vx / vLen, vy / vLen, vz / vLen,
                            ld[0], ld[1], ld[2],
                            0.2f, 0.65f, 0.5f, 40f
                        )
                        val ao = ambientOcclusion(mr.px, mr.py, mr.pz, normal[0], normal[1], normal[2], sceneSDF, 5)
                        val brightness = shade * ao

                        toneMapACES(tm, sr * brightness, sg * brightness, sb * brightness, exposure)
                        pixels[py * renderW + px] = Color.rgb(
                            tm[0].toInt().coerceIn(0, 255),
                            tm[1].toInt().coerceIn(0, 255),
                            tm[2].toInt().coerceIn(0, 255)
                        )
                    } else {
                        skyGradient(sky, rd[1], topR, topG, topB, botR, botG, botB)
                        toneMapACES(tm, sky[0], sky[1], sky[2], exposure)
                        pixels[py * renderW + px] = Color.rgb(
                            tm[0].toInt().coerceIn(0, 255),
                            tm[1].toInt().coerceIn(0, 255),
                            tm[2].toInt().coerceIn(0, 255)
                        )
                    }
                }
            }
        }

        renderToCanvas(canvas, bitmap, renderW, renderH, pixels)
    }

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float {
        val balls = extractFloat(params, "ballCount", 6f).toInt()
        return (0.3f + balls * 0.03f).coerceIn(0.3f, 1f)
    }
}
