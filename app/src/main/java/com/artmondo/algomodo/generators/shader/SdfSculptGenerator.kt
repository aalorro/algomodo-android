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

class SdfSculptGenerator : Generator {

    override val id = "shader-sdf-sculpt"
    override val family = "shader"
    override val styleName = "SDF Sculpt"
    override val definition =
        "Fractal sculptures with Phong lighting, soft shadows, and Fresnel rim glow."
    override val algorithmNotes =
        "Four fractal modes (Menger, Sierpinski, Mandelbox, Kaleidoscopic) rendered via SDF ray marching. " +
        "Orbit trap coloring, roughness-controlled lighting, animated Y+X rotation, ambient occlusion, rim glow."
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
        Parameter.SelectParam("Fractal", "fractal", ParamGroup.GEOMETRY,
            "Fractal sculpture type",
            listOf("Menger", "Sierpinski", "Mandelbox", "Kaleidoscopic"), "Menger"),
        Parameter.NumberParam("Iterations", "iterations", ParamGroup.GEOMETRY,
            "Fractal iteration depth", 2f, 12f, 1f, 5f),
        Parameter.NumberParam("Fold Scale", "foldScale", ParamGroup.GEOMETRY,
            "Scale factor per fold iteration", 1.5f, 3.5f, 0.1f, 2.5f),
        Parameter.NumberParam("Rim Glow", "rimGlow", ParamGroup.TEXTURE,
            "Fresnel rim glow intensity", 0f, 2f, 0.1f, 0.6f),
        Parameter.NumberParam("Roughness", "roughness", ParamGroup.TEXTURE,
            "Surface roughness", 0.1f, 1f, 0.05f, 0.35f)
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "cameraDistance" to 4f, "fov" to 60f, "cameraAngle" to 0f, "cameraHeight" to 0.5f,
        "lightAngle" to 45f, "lightHeight" to 0.8f, "exposure" to 1.2f, "speed" to 0.5f,
        "fractal" to "Menger", "iterations" to 5f, "foldScale" to 2.5f,
        "rimGlow" to 0.6f, "roughness" to 0.35f
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
        val fractal = extractString(params, "fractal", "Menger")
        val iters = extractFloat(params, "iterations", 5f).toInt().coerceIn(2, 12)
        val foldScale = extractFloat(params, "foldScale", 2.5f)
        val rimGlow = extractFloat(params, "rimGlow", 0.6f)
        val roughness = extractFloat(params, "roughness", 0.35f)
        val t = time * spd

        val rng = SeededRNG(seed)
        val rotPhaseY = rng.randomAngle()
        val rotPhaseX = rng.randomAngle()
        val rotY = t * 0.3f + rotPhaseY
        val rotX = t * 0.15f + rotPhaseX
        val cosY = cos(rotY); val sinY = sin(rotY)
        val cosX = cos(rotX); val sinX = sin(rotX)

        val ro = FloatArray(3)
        cameraFromParams(camDist, camAngle, camHeight, ro)
        val cam = buildCamera(ro[0], ro[1], ro[2], 0f, 0f, 0f, fov, renderW.toFloat() / renderH)
        val ld = FloatArray(3)
        lightDirFromParams(lightAngle, lightHeight, ld)

        val colors = palette.colorInts()
        val nColors = colors.size
        val shininess = 10f + (1f - roughness) * 90f
        val specAmt = (1f - roughness) * 0.7f

        val sceneSDF: SceneSDF = { px, py, pz ->
            // Rotate
            var x = px * cosY + pz * sinY
            var y = py
            var z = -px * sinY + pz * cosY
            val ry = y * cosX - z * sinX
            val rz = y * sinX + z * cosX
            y = ry; z = rz

            when (fractal) {
                "Sierpinski" -> deSierpinski(x, y, z, iters, foldScale)
                "Mandelbox" -> deMandelbox(x, y, z, iters, foldScale)
                "Kaleidoscopic" -> deKaleidoscopic(x, y, z, iters, foldScale)
                else -> deMenger(x, y, z, iters, foldScale)
            }
        }

        val topR = 0.04f; val topG = 0.04f; val topB = 0.1f
        val botR = 0.1f; val botG = 0.07f; val botB = 0.12f

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
                        val nvx = vx / vLen; val nvy = vy / vLen; val nvz = vz / vLen

                        // Orbit trap coloring
                        val trapVal = fractalTrap(mr.px, mr.py, mr.pz, fractal, iters, foldScale, cosY, sinY, cosX, sinX)
                        val colorT = trapVal.coerceIn(0f, 1f)
                        val ci = (colorT * (nColors - 1)).toInt().coerceIn(0, nColors - 1)
                        val cr = Color.red(colors[ci]) / 255f
                        val cg = Color.green(colors[ci]) / 255f
                        val cb = Color.blue(colors[ci]) / 255f

                        val shade = phongShade(normal[0], normal[1], normal[2], nvx, nvy, nvz, ld[0], ld[1], ld[2], 0.12f, 0.6f, specAmt, shininess)
                        val ao = ambientOcclusion(mr.px, mr.py, mr.pz, normal[0], normal[1], normal[2], sceneSDF, 5)

                        val NdotV = maxOf(0f, normal[0] * nvx + normal[1] * nvy + normal[2] * nvz)
                        val fresnel = (1f - NdotV).pow(3) * rimGlow
                        val rimC = colors[nColors / 2]

                        val r = cr * shade * ao + Color.red(rimC) / 255f * fresnel
                        val g = cg * shade * ao + Color.green(rimC) / 255f * fresnel
                        val b = cb * shade * ao + Color.blue(rimC) / 255f * fresnel

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

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float {
        val iters = extractFloat(params, "iterations", 5f).toInt()
        return (0.4f + iters * 0.05f).coerceIn(0.4f, 1f)
    }

    companion object {
        fun deMenger(x: Float, y: Float, z: Float, iters: Int, scale: Float): Float {
            var px = x; var py = y; var pz = z
            var d = sdBox(px, py, pz, 1f, 1f, 1f)
            var s = 1f
            for (i in 0 until iters) {
                val a = ((px * s + 1f) % 2f) - 1f
                val b = ((py * s + 1f) % 2f) - 1f
                val c = ((pz * s + 1f) % 2f) - 1f
                s *= scale
                val rx = (1f - abs(a) * 3f); val ry = (1f - abs(b) * 3f); val rz = (1f - abs(c) * 3f)
                val crossD = maxOf(rx, ry, rz) / s
                d = maxOf(d, -crossD)
            }
            return d
        }

        fun deSierpinski(x: Float, y: Float, z: Float, iters: Int, scale: Float): Float {
            var px = x; var py = y; var pz = z
            for (i in 0 until iters) {
                // Tetrahedral fold
                if (px + py < 0f) { val t = -py; py = -px; px = t }
                if (px + pz < 0f) { val t = -pz; pz = -px; px = t }
                if (py + pz < 0f) { val t = -pz; pz = -py; py = t }
                px = px * scale - (scale - 1f)
                py = py * scale - (scale - 1f)
                pz = pz * scale - (scale - 1f)
            }
            return (sqrt(px * px + py * py + pz * pz) - 1.5f) * scale.pow(-iters.toFloat())
        }

        fun deMandelbox(x: Float, y: Float, z: Float, iters: Int, scale: Float): Float {
            var px = x; var py = y; var pz = z
            var dr = 1f
            val foldLimit = 1f; val fixedR2 = 1f; val minR2 = 0.25f

            for (i in 0 until iters) {
                // Box fold
                px = px.coerceIn(-foldLimit, foldLimit) * 2f - px
                py = py.coerceIn(-foldLimit, foldLimit) * 2f - py
                pz = pz.coerceIn(-foldLimit, foldLimit) * 2f - pz
                // Sphere fold
                val r2 = px * px + py * py + pz * pz
                if (r2 < minR2) {
                    val t = fixedR2 / minR2
                    px *= t; py *= t; pz *= t; dr *= t
                } else if (r2 < fixedR2) {
                    val t = fixedR2 / r2
                    px *= t; py *= t; pz *= t; dr *= t
                }
                px = px * scale + x; py = py * scale + y; pz = pz * scale + z
                dr = dr * abs(scale) + 1f
            }
            return sqrt(px * px + py * py + pz * pz) / abs(dr)
        }

        fun deKaleidoscopic(x: Float, y: Float, z: Float, iters: Int, scale: Float): Float {
            var px = x; var py = y; var pz = z
            for (i in 0 until iters) {
                px = abs(px); py = abs(py); pz = abs(pz)
                // Sort descending
                if (px < py) { val t = px; px = py; py = t }
                if (px < pz) { val t = px; px = pz; pz = t }
                if (py < pz) { val t = py; py = pz; pz = t }
                px = px * scale - (scale - 1f) * 0.5f
                py = py * scale - (scale - 1f) * 0.5f
                pz = pz * scale - (scale - 1f) * 0.5f
            }
            return sdBox(px, py, pz, 0.5f, 0.5f, 0.5f) * scale.pow(-iters.toFloat())
        }

        fun fractalTrap(
            px: Float, py: Float, pz: Float,
            fractal: String, iters: Int, scale: Float,
            cosY: Float, sinY: Float, cosX: Float, sinX: Float
        ): Float {
            // Rotate like in SDF
            var x = px * cosY + pz * sinY
            var y = py
            var z = -px * sinY + pz * cosY
            val ry = y * cosX - z * sinX
            val rz = y * sinX + z * cosX
            y = ry; z = rz

            var minR2 = Float.MAX_VALUE
            when (fractal) {
                "Sierpinski" -> {
                    for (i in 0 until iters) {
                        if (x + y < 0f) { val t = -y; y = -x; x = t }
                        if (x + z < 0f) { val t = -z; z = -x; x = t }
                        if (y + z < 0f) { val t = -z; z = -y; y = t }
                        x = x * scale - (scale - 1f)
                        y = y * scale - (scale - 1f)
                        z = z * scale - (scale - 1f)
                        minR2 = minOf(minR2, x * x + y * y + z * z)
                    }
                }
                else -> {
                    for (i in 0 until iters) {
                        x = abs(x); y = abs(y); z = abs(z)
                        if (x < y) { val t = x; x = y; y = t }
                        if (x < z) { val t = x; x = z; z = t }
                        if (y < z) { val t = y; y = z; z = t }
                        x = x * scale - (scale - 1f) * 0.5f
                        y = y * scale - (scale - 1f) * 0.5f
                        z = z * scale - (scale - 1f) * 0.5f
                        minR2 = minOf(minR2, x * x + y * y + z * z)
                    }
                }
            }
            return sqrt(minR2) * 0.3f
        }
    }
}
