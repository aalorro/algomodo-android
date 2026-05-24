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

class GlassGardenGenerator : Generator {

    override val id = "shader-glass-garden"
    override val family = "shader"
    override val styleName = "Glass Garden"
    override val definition =
        "Refractive and reflective glass surfaces with Fresnel blending and multi-bounce ray tracing."
    override val algorithmNotes =
        "Standard ray march for primary hit. Compute Fresnel blend ratio. Spawn reflected and refracted rays. " +
        "Beer's law absorption for tinted glass. Up to 4 bounce recursion. Materials: Glass, TintedGlass, Diamond, Liquid."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema = listOf(
        Parameter.NumberParam("Camera Dist", "cameraDistance", ParamGroup.COMPOSITION,
            "Distance of camera from origin", 1f, 12f, 0.5f, 5f),
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
        Parameter.SelectParam("Material", "material", ParamGroup.TEXTURE,
            "Glass material type",
            listOf("Glass", "TintedGlass", "Diamond", "Liquid"), "Glass"),
        Parameter.NumberParam("Bounces", "maxBounces", ParamGroup.GEOMETRY,
            "Maximum ray bounce count", 1f, 4f, 1f, 2f),
        Parameter.NumberParam("Object Count", "objectCount", ParamGroup.COMPOSITION,
            "Number of glass objects", 2f, 6f, 1f, 3f)
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "cameraDistance" to 5f, "fov" to 60f, "cameraAngle" to 0f, "cameraHeight" to 0.5f,
        "lightAngle" to 45f, "lightHeight" to 0.8f, "exposure" to 1.2f, "speed" to 0.5f,
        "material" to "Glass", "maxBounces" to 2f, "objectCount" to 3f
    )

    override fun renderCanvas(
        canvas: Canvas, bitmap: Bitmap, params: Map<String, Any>,
        seed: Int, palette: Palette, quality: Quality, time: Float
    ) {
        val w = bitmap.width; val h = bitmap.height
        val cfg = getQualityConfig(quality, "heavy", time > 0f)
        val renderW = (w * cfg.scale).toInt().coerceAtLeast(60)
        val renderH = (h * cfg.scale).toInt().coerceAtLeast(60)

        val camDist = extractFloat(params, "cameraDistance", 5f)
        val fov = extractFloat(params, "fov", 60f)
        val camAngle = extractFloat(params, "cameraAngle", 0f)
        val camHeight = extractFloat(params, "cameraHeight", 0.5f)
        val lightAngle = extractFloat(params, "lightAngle", 45f)
        val lightHeight = extractFloat(params, "lightHeight", 0.8f)
        val exposure = extractFloat(params, "exposure", 1.2f)
        val spd = extractFloat(params, "speed", 0.5f)
        val material = extractString(params, "material", "Glass")
        val maxBounces = extractFloat(params, "maxBounces", 2f).toInt().coerceIn(1, 4)
        val objCount = extractFloat(params, "objectCount", 3f).toInt().coerceIn(2, 6)
        val t = time * spd

        val ior = when (material) {
            "Diamond" -> 2.42f; "Liquid" -> 1.33f; else -> 1.5f
        }
        val absorption = when (material) {
            "TintedGlass" -> 0.6f; "Liquid" -> 0.8f; "Diamond" -> 0.1f; else -> 0f
        }

        val rng = SeededRNG(seed)
        val objX = FloatArray(objCount)
        val objY = FloatArray(objCount)
        val objZ = FloatArray(objCount)
        val objR = FloatArray(objCount)
        for (i in 0 until objCount) {
            objX[i] = rng.range(-1.5f, 1.5f)
            objY[i] = rng.range(-0.5f, 1f)
            objZ[i] = rng.range(-1.5f, 1.5f)
            objR[i] = rng.range(0.5f, 1f)
        }

        val ro = FloatArray(3)
        cameraFromParams(camDist, camAngle + t * 8f, camHeight, ro)
        val cam = buildCamera(ro[0], ro[1], ro[2], 0f, 0.3f, 0f, fov, renderW.toFloat() / renderH)
        val ld = FloatArray(3)
        lightDirFromParams(lightAngle, lightHeight, ld)

        val colors = palette.colorInts()
        val nColors = colors.size
        val noise = Noise3D(seed)

        val sceneSDF: SceneSDF = { px, py, pz ->
            var d = sdSphere(px - objX[0], py - objY[0], pz - objZ[0], objR[0])
            for (i in 1 until objCount) {
                d = opSmoothUnion(d, sdSphere(px - objX[i], py - objY[i], pz - objZ[i], objR[i]), 0.3f)
            }
            // Ground plane
            val ground = py + 1.5f
            minOf(d, ground)
        }

        val pixels = IntArray(renderW * renderH)
        val invW = 2f / renderW; val invH = 2f / renderH

        renderMultithreaded(renderW, renderH, pixels) { y0, y1, rd, normal, tm, mr ->
            val refl = FloatArray(3)
            val refr = FloatArray(3)
            val sky = FloatArray(3)

            for (py in y0 until y1) {
                val v = 1f - py * invH
                for (px in 0 until renderW) {
                    val u = px * invW - 1f
                    getRayDir(rd, u, v, cam)

                    var accR = 0f; var accG = 0f; var accB = 0f
                    var curRoX = ro[0]; var curRoY = ro[1]; var curRoZ = ro[2]
                    var curRdX = rd[0]; var curRdY = rd[1]; var curRdZ = rd[2]
                    var attenuation = 1f

                    for (bounce in 0..maxBounces) {
                        rayMarch(mr, curRoX, curRoY, curRoZ, curRdX, curRdY, curRdZ, sceneSDF, cfg.maxSteps, cfg.epsilon, cfg.maxDist)

                        if (!mr.hit) {
                            // Background: noise-based environment
                            val bgN = noise.fbm3D(curRdX * 3f + t * 0.1f, curRdY * 3f, curRdZ * 3f, 3) * 0.5f + 0.5f
                            val bgCI = (bgN * (nColors - 1)).toInt().coerceIn(0, nColors - 1)
                            val skyBright = 0.3f + curRdY * 0.2f
                            accR += Color.red(colors[bgCI]) / 255f * skyBright * attenuation
                            accG += Color.green(colors[bgCI]) / 255f * skyBright * attenuation
                            accB += Color.blue(colors[bgCI]) / 255f * skyBright * attenuation
                            break
                        }

                        calcNormal(normal, mr.px, mr.py, mr.pz, sceneSDF, cfg.epsilon * 2f)

                        // Check if we hit ground or glass
                        val isGround = mr.py < -1.4f

                        if (isGround) {
                            // Ground: checkerboard
                            val check = ((floor(mr.px * 2f).toInt() + floor(mr.pz * 2f).toInt()) and 1) == 0
                            val groundColor = if (check) 0.4f else 0.15f
                            val vx = curRoX - mr.px; val vy = curRoY - mr.py; val vz = curRoZ - mr.pz
                            val vLen = vec3Length(vx, vy, vz).let { if (it == 0f) 1f else it }
                            val shade = phongShade(normal[0], normal[1], normal[2], vx / vLen, vy / vLen, vz / vLen, ld[0], ld[1], ld[2], 0.2f, 0.6f, 0.2f, 20f)
                            accR += groundColor * shade * attenuation
                            accG += groundColor * shade * attenuation
                            accB += groundColor * shade * attenuation
                            break
                        }

                        // Glass surface
                        val cosI = -(curRdX * normal[0] + curRdY * normal[1] + curRdZ * normal[2])
                        val fres = fresnelSchlick(abs(cosI), ior)

                        if (fres > 0.5f || bounce == maxBounces) {
                            // Reflect
                            reflectVec(refl, curRdX, curRdY, curRdZ, normal[0], normal[1], normal[2])
                            curRoX = mr.px + refl[0] * 0.01f
                            curRoY = mr.py + refl[1] * 0.01f
                            curRoZ = mr.pz + refl[2] * 0.01f
                            curRdX = refl[0]; curRdY = refl[1]; curRdZ = refl[2]

                            // Add specular highlight
                            val specH = phongShade(normal[0], normal[1], normal[2], -curRdX, -curRdY, -curRdZ, ld[0], ld[1], ld[2], 0f, 0f, 0.8f, 80f)
                            accR += specH * attenuation * 0.3f
                            accG += specH * attenuation * 0.3f
                            accB += specH * attenuation * 0.3f
                        } else {
                            // Refract
                            val eta = 1f / ior
                            if (refractVec(refr, curRdX, curRdY, curRdZ, normal[0], normal[1], normal[2], eta)) {
                                curRoX = mr.px + refr[0] * 0.01f
                                curRoY = mr.py + refr[1] * 0.01f
                                curRoZ = mr.pz + refr[2] * 0.01f
                                curRdX = refr[0]; curRdY = refr[1]; curRdZ = refr[2]
                            } else {
                                // Total internal reflection
                                reflectVec(refl, curRdX, curRdY, curRdZ, normal[0], normal[1], normal[2])
                                curRoX = mr.px + refl[0] * 0.01f
                                curRoY = mr.py + refl[1] * 0.01f
                                curRoZ = mr.pz + refl[2] * 0.01f
                                curRdX = refl[0]; curRdY = refl[1]; curRdZ = refl[2]
                            }
                        }

                        // Beer's law absorption
                        if (absorption > 0f) {
                            attenuation *= exp(-absorption * mr.t * 0.5f)
                        }
                        attenuation *= 0.85f
                    }

                    toneMapACES(tm, accR, accG, accB, exposure)
                    pixels[py * renderW + px] = Color.rgb(tm[0].toInt().coerceIn(0, 255), tm[1].toInt().coerceIn(0, 255), tm[2].toInt().coerceIn(0, 255))
                }
            }
        }

        renderToCanvas(canvas, bitmap, renderW, renderH, pixels)
    }

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float {
        val bounces = extractFloat(params, "maxBounces", 2f).toInt()
        return (0.5f + bounces * 0.15f).coerceIn(0.5f, 1f)
    }
}
