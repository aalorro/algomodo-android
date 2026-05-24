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

class MandelbulbGenerator : Generator {

    override val id = "shader-mandelbulb"
    override val family = "shader"
    override val styleName = "Mandelbulb"
    override val definition =
        "3D fractal rendering with multiple formulas and orbit trap coloring."
    override val algorithmNotes =
        "Six distance estimators: Mandelbulb (spherical coordinates power iteration), MandelbulbVar (animated power), " +
        "Mandelbox (box+sphere folding), MengerSponge (recursive folding), Sierpinski3D (tetrahedral), " +
        "KaleidoscopicIFS (sorted abs reflections). Orbit trap modes: point, line, plane."
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
        Parameter.SelectParam("Formula", "formula", ParamGroup.GEOMETRY,
            "3D fractal formula",
            listOf("Mandelbulb", "MandelbulbVar", "Mandelbox", "MengerSponge", "Sierpinski3D", "KaleidoscopicIFS"),
            "Mandelbulb"),
        Parameter.NumberParam("Power", "power", ParamGroup.GEOMETRY,
            "Mandelbulb power exponent", 2f, 12f, 0.5f, 8f),
        Parameter.NumberParam("Iterations", "fractalIterations", ParamGroup.GEOMETRY,
            "Fractal iteration count", 4f, 20f, 1f, 10f),
        Parameter.SelectParam("Orbit Trap", "orbitTrap", ParamGroup.COLOR,
            "Orbit trap coloring mode",
            listOf("none", "point", "line", "plane"), "point"),
        Parameter.NumberParam("AO Strength", "aoStrength", ParamGroup.TEXTURE,
            "Ambient occlusion intensity", 0f, 1f, 0.1f, 0.6f)
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "cameraDistance" to 5f, "fov" to 60f, "cameraAngle" to 0f, "cameraHeight" to 0.5f,
        "lightAngle" to 45f, "lightHeight" to 0.8f, "exposure" to 1.2f, "speed" to 0.5f,
        "formula" to "Mandelbulb", "power" to 8f, "fractalIterations" to 10f,
        "orbitTrap" to "point", "aoStrength" to 0.6f
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
        val formula = extractString(params, "formula", "Mandelbulb")
        val power = extractFloat(params, "power", 8f)
        val iters = extractFloat(params, "fractalIterations", 10f).toInt().coerceIn(4, 20)
        val trapMode = extractString(params, "orbitTrap", "point")
        val aoStr = extractFloat(params, "aoStrength", 0.6f)
        val t = time * spd

        // Animated power for MandelbulbVar
        val animPower = if (formula == "MandelbulbVar") power + sin(t) * 2f else power

        val ro = FloatArray(3)
        cameraFromParams(camDist, camAngle + t * 8f, camHeight, ro)
        val cam = buildCamera(ro[0], ro[1], ro[2], 0f, 0f, 0f, fov, renderW.toFloat() / renderH)
        val ld = FloatArray(3)
        lightDirFromParams(lightAngle, lightHeight, ld)

        val colors = palette.colorInts()
        val nColors = colors.size

        val sceneSDF: SceneSDF = { px, py, pz ->
            when (formula) {
                "Mandelbox" -> SdfSculptGenerator.deMandelbox(px, py, pz, iters, 2f)
                "MengerSponge" -> SdfSculptGenerator.deMenger(px, py, pz, iters, 3f)
                "Sierpinski3D" -> SdfSculptGenerator.deSierpinski(px, py, pz, iters, 2f)
                "KaleidoscopicIFS" -> SdfSculptGenerator.deKaleidoscopic(px, py, pz, iters, 2f)
                else -> deMandelbulb(px, py, pz, iters, animPower)
            }
        }

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

                        // Orbit trap coloring
                        val trapVal = when (trapMode) {
                            "point" -> orbitTrapPoint(mr.px, mr.py, mr.pz, iters, animPower, formula)
                            "line" -> orbitTrapLine(mr.px, mr.py, mr.pz, iters, animPower, formula)
                            "plane" -> orbitTrapPlane(mr.px, mr.py, mr.pz, iters, animPower, formula)
                            else -> (normal[0] * 0.5f + 0.5f + normal[1] * 0.3f).coerceIn(0f, 1f)
                        }

                        val colorT = trapVal.coerceIn(0f, 1f)
                        val ci = (colorT * (nColors - 1)).toInt().coerceIn(0, nColors - 1)
                        val c1i = (ci + 1).coerceAtMost(nColors - 1)
                        val frac = colorT * (nColors - 1) - ci
                        val cr = (Color.red(colors[ci]) + (Color.red(colors[c1i]) - Color.red(colors[ci])) * frac) / 255f
                        val cg = (Color.green(colors[ci]) + (Color.green(colors[c1i]) - Color.green(colors[ci])) * frac) / 255f
                        val cb = (Color.blue(colors[ci]) + (Color.blue(colors[c1i]) - Color.blue(colors[ci])) * frac) / 255f

                        val shade = phongShade(normal[0], normal[1], normal[2], vx / vLen, vy / vLen, vz / vLen, ld[0], ld[1], ld[2], 0.15f, 0.65f, 0.4f, 40f)
                        val ao = ambientOcclusion(mr.px, mr.py, mr.pz, normal[0], normal[1], normal[2], sceneSDF, 5)
                        val finalAo = 1f - (1f - ao) * aoStr

                        toneMapACES(tm, cr * shade * finalAo, cg * shade * finalAo, cb * shade * finalAo, exposure)
                        pixels[py * renderW + px] = Color.rgb(tm[0].toInt().coerceIn(0, 255), tm[1].toInt().coerceIn(0, 255), tm[2].toInt().coerceIn(0, 255))
                    } else {
                        skyGradient(sky, rd[1], 0.03f, 0.03f, 0.08f, 0.08f, 0.05f, 0.12f)
                        toneMapACES(tm, sky[0], sky[1], sky[2], exposure)
                        pixels[py * renderW + px] = Color.rgb(tm[0].toInt().coerceIn(0, 255), tm[1].toInt().coerceIn(0, 255), tm[2].toInt().coerceIn(0, 255))
                    }
                }
            }
        }

        renderToCanvas(canvas, bitmap, renderW, renderH, pixels)
    }

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float {
        val iters = extractFloat(params, "fractalIterations", 10f).toInt()
        return (0.5f + iters * 0.04f).coerceIn(0.5f, 1f)
    }

    companion object {
        fun deMandelbulb(px: Float, py: Float, pz: Float, iters: Int, power: Float): Float {
            var x = px; var y = py; var z = pz
            var dr = 1f; var r = 0f

            for (i in 0 until iters) {
                r = sqrt(x * x + y * y + z * z)
                if (r > 2f) break

                // Spherical coordinates
                val theta = acos(z / r)
                val phi = atan2(y, x)
                dr = r.pow(power - 1f) * power * dr + 1f

                val zr = r.pow(power)
                val newTheta = theta * power
                val newPhi = phi * power

                x = zr * sin(newTheta) * cos(newPhi) + px
                y = zr * sin(newTheta) * sin(newPhi) + py
                z = zr * cos(newTheta) + pz
            }
            return 0.5f * ln(r) * r / dr
        }

        private fun orbitTrapPoint(px: Float, py: Float, pz: Float, iters: Int, power: Float, formula: String): Float {
            if (formula == "Mandelbulb" || formula == "MandelbulbVar") {
                var x = px; var y = py; var z = pz
                var minDist = Float.MAX_VALUE
                for (i in 0 until iters) {
                    val r = sqrt(x * x + y * y + z * z)
                    if (r > 2f) break
                    minDist = minOf(minDist, r)
                    val theta = acos(z / r); val phi = atan2(y, x)
                    val zr = r.pow(power)
                    x = zr * sin(theta * power) * cos(phi * power) + px
                    y = zr * sin(theta * power) * sin(phi * power) + py
                    z = zr * cos(theta * power) + pz
                }
                return minDist * 0.5f
            }
            return (abs(px) + abs(py) + abs(pz)) * 0.2f
        }

        private fun orbitTrapLine(px: Float, py: Float, pz: Float, iters: Int, power: Float, formula: String): Float {
            if (formula == "Mandelbulb" || formula == "MandelbulbVar") {
                var x = px; var y = py; var z = pz
                var minDist = Float.MAX_VALUE
                for (i in 0 until iters) {
                    val r = sqrt(x * x + y * y + z * z)
                    if (r > 2f) break
                    // Distance to Y axis
                    minDist = minOf(minDist, sqrt(x * x + z * z))
                    val theta = acos(z / r); val phi = atan2(y, x)
                    val zr = r.pow(power)
                    x = zr * sin(theta * power) * cos(phi * power) + px
                    y = zr * sin(theta * power) * sin(phi * power) + py
                    z = zr * cos(theta * power) + pz
                }
                return minDist * 0.4f
            }
            return sqrt(px * px + pz * pz) * 0.3f
        }

        private fun orbitTrapPlane(px: Float, py: Float, pz: Float, iters: Int, power: Float, formula: String): Float {
            if (formula == "Mandelbulb" || formula == "MandelbulbVar") {
                var x = px; var y = py; var z = pz
                var minDist = Float.MAX_VALUE
                for (i in 0 until iters) {
                    val r = sqrt(x * x + y * y + z * z)
                    if (r > 2f) break
                    // Distance to XZ plane
                    minDist = minOf(minDist, abs(y))
                    val theta = acos(z / r); val phi = atan2(y, x)
                    val zr = r.pow(power)
                    x = zr * sin(theta * power) * cos(phi * power) + px
                    y = zr * sin(theta * power) * sin(phi * power) + py
                    z = zr * cos(theta * power) + pz
                }
                return minDist * 0.6f
            }
            return abs(py) * 0.4f
        }
    }
}
