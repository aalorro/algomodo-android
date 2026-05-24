package com.artmondo.algomodo.generators.shader

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import com.artmondo.algomodo.data.palettes.Palette
import com.artmondo.algomodo.generators.Generator
import com.artmondo.algomodo.generators.ParamGroup
import com.artmondo.algomodo.generators.Parameter
import com.artmondo.algomodo.generators.Quality
import kotlin.math.*

class ApollonianSpheresGenerator : Generator {

    override val id = "shader-apollonian-spheres"
    override val family = "shader"
    override val styleName = "Apollonian Spheres"
    override val definition =
        "Infinite recursive sphere packing via inversive geometry with orbit trap coloring."
    override val algorithmNotes =
        "Tetrahedral folding (abs + sort descending) plus sphere inversion at each iteration. " +
        "Accumulated scale gives distance estimate. Orbit trap (minimum squared radius) drives palette coloring. " +
        "Fresnel rim glow on silhouette edges."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema = listOf(
        Parameter.NumberParam("Camera Dist", "cameraDistance", ParamGroup.COMPOSITION,
            "Distance of camera from origin", 1f, 12f, 0.5f, 4f),
        Parameter.NumberParam("FOV", "fov", ParamGroup.COMPOSITION,
            "Field of view in degrees", 20f, 120f, 5f, 40f),
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
        Parameter.SelectParam("Packing", "packingStyle", ParamGroup.COMPOSITION,
            "Packing geometry preset",
            listOf("Classic", "Dense", "Lacy", "Cathedral"), "Classic"),
        Parameter.NumberParam("Iterations", "iterations", ParamGroup.GEOMETRY,
            "Fractal iteration count", 2f, 16f, 1f, 8f),
        Parameter.NumberParam("Sphere Size", "sphereSize", ParamGroup.GEOMETRY,
            "Radius of packed spheres", 0.05f, 0.8f, 0.05f, 0.3f),
        Parameter.NumberParam("Rim Glow", "rimGlow", ParamGroup.TEXTURE,
            "Fresnel rim glow intensity", 0f, 2f, 0.1f, 0.5f),
        Parameter.NumberParam("Roughness", "roughness", ParamGroup.TEXTURE,
            "Surface roughness (lower = shinier)", 0.1f, 1f, 0.05f, 0.4f)
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "cameraDistance" to 4f, "fov" to 40f, "cameraAngle" to 0f, "cameraHeight" to 0.5f,
        "lightAngle" to 45f, "lightHeight" to 0.8f, "exposure" to 1.2f, "speed" to 0.5f,
        "packingStyle" to "Classic", "iterations" to 8f, "sphereSize" to 0.3f, "rimGlow" to 0.5f, "roughness" to 0.4f
    )

    override fun renderCanvas(
        canvas: Canvas, bitmap: Bitmap, params: Map<String, Any>,
        seed: Int, palette: Palette, quality: Quality, time: Float
    ) {
        val w = bitmap.width; val h = bitmap.height
        val animating = time > 0f

        // Higher resolution + fewer steps: bounding sphere makes steps cheap
        val cfg = when (quality) {
            Quality.DRAFT -> if (animating) RenderConfig(0.40f, 40, 0.004f, 25f)
                             else RenderConfig(0.50f, 64, 0.002f, 40f)
            Quality.BALANCED -> if (animating) RenderConfig(0.55f, 48, 0.003f, 28f)
                                else RenderConfig(0.70f, 80, 0.001f, 50f)
            Quality.ULTRA -> if (animating) RenderConfig(0.65f, 60, 0.002f, 35f)
                             else RenderConfig(0.85f, 128, 0.0005f, 60f)
        }
        val renderW = (w * cfg.scale).toInt().coerceAtLeast(100)
        val renderH = (h * cfg.scale).toInt().coerceAtLeast(100)

        val camDist = extractFloat(params, "cameraDistance", 4f)
        val fov = extractFloat(params, "fov", 40f)
        val camAngle = extractFloat(params, "cameraAngle", 0f)
        val camHeight = extractFloat(params, "cameraHeight", 0.5f)
        val lightAngle = extractFloat(params, "lightAngle", 45f)
        val lightHeight = extractFloat(params, "lightHeight", 0.8f)
        val exposure = extractFloat(params, "exposure", 1.2f)
        val spd = extractFloat(params, "speed", 0.5f)
        val packing = extractString(params, "packingStyle", "Classic")
        val iters = extractFloat(params, "iterations", 8f).toInt().coerceIn(2, 16)
        val renderIters = if (animating) iters.coerceAtMost(6) else iters
        val sphereR = extractFloat(params, "sphereSize", 0.3f)
        val rimGlow = extractFloat(params, "rimGlow", 0.5f)
        val roughness = extractFloat(params, "roughness", 0.4f)
        val t = time * spd

        // Packing presets
        val ox: Float; val oy: Float; val oz: Float; val invR2: Float
        when (packing) {
            "Dense" -> { ox = 1.1f; oy = 1.1f; oz = 1.1f; invR2 = 1.2f }
            "Lacy" -> { ox = 1.0f; oy = 1.5f; oz = 1.0f; invR2 = 1.5f }
            "Cathedral" -> { ox = 1.2f; oy = 0.8f; oz = 1.2f; invR2 = 1.0f }
            else -> { ox = 1.0f; oy = 1.0f; oz = 1.0f; invR2 = 1.0f }
        }

        // Bounding sphere radius for early-out (fractal fits within this)
        val boundR = 3.5f

        val rotY = t * 0.3f
        val cosRot = cos(rotY); val sinRot = sin(rotY)

        val ro = FloatArray(3)
        cameraFromParams(camDist, camAngle + t * 10f, camHeight, ro)
        val cam = buildCamera(ro[0], ro[1], ro[2], 0f, 0f, 0f, fov, renderW.toFloat() / renderH)
        val ld = FloatArray(3)
        lightDirFromParams(lightAngle, lightHeight, ld)

        val colors = palette.colorInts()
        val nColors = colors.size

        // Sky colors from palette (darkened) — fixes always-purple background
        val lastC = colors[nColors - 1]
        val firstC = colors[0]
        val skyTopR = Color.red(lastC) / 255f * 0.15f
        val skyTopG = Color.green(lastC) / 255f * 0.15f
        val skyTopB = Color.blue(lastC) / 255f * 0.15f
        val skyBotR = Color.red(firstC) / 255f * 0.2f
        val skyBotG = Color.green(firstC) / 255f * 0.2f
        val skyBotB = Color.blue(firstC) / 255f * 0.2f

        // Pre-extract color components
        val colR = IntArray(nColors) { Color.red(colors[it]) }
        val colG = IntArray(nColors) { Color.green(colors[it]) }
        val colB = IntArray(nColors) { Color.blue(colors[it]) }
        val rimR = colR[0] / 255f
        val rimG = colG[0] / 255f
        val rimB = colB[0] / 255f

        val pixels = IntArray(renderW * renderH)
        val invW = 2f / renderW; val invH = 2f / renderH
        val shininess = 10f + (1f - roughness) * 90f
        val specAmt = (1f - roughness) * 0.8f
        val maxSteps = cfg.maxSteps
        val epsilon = cfg.epsilon
        val maxDist = cfg.maxDist
        val normalEps = if (animating) epsilon * 5f else epsilon * 2.5f

        renderMultithreaded(renderW, renderH, pixels) { y0, y1, rd, normal, tm, _ ->
            val deResult = FloatArray(2)

            for (py in y0 until y1) {
                val v = 1f - py * invH
                for (px in 0 until renderW) {
                    val u = px * invW - 1f
                    getRayDir(rd, u, v, cam)

                    // Ray-sphere intersection for bounding volume (skip rays that miss entirely)
                    // Sphere at origin with radius boundR
                    val b = ro[0] * rd[0] + ro[1] * rd[1] + ro[2] * rd[2]
                    val c = ro[0] * ro[0] + ro[1] * ro[1] + ro[2] * ro[2] - boundR * boundR
                    val disc = b * b - c

                    if (disc < 0f) {
                        // Ray misses bounding sphere entirely — sky
                        val skyT = rd[1] * 0.5f + 0.5f
                        toneMapACES(tm,
                            skyBotR + (skyTopR - skyBotR) * skyT,
                            skyBotG + (skyTopG - skyBotG) * skyT,
                            skyBotB + (skyTopB - skyBotB) * skyT, exposure)
                        pixels[py * renderW + px] = Color.rgb(tm[0].toInt().coerceIn(0, 255), tm[1].toInt().coerceIn(0, 255), tm[2].toInt().coerceIn(0, 255))
                        continue
                    }

                    // Start march at bounding sphere entry (or 0 if inside)
                    val sqrtDisc = sqrt(disc)
                    val tEntry = maxOf(0f, -b - sqrtDisc)

                    var marchT = tEntry
                    var hit = false
                    var hitX = 0f; var hitY = 0f; var hitZ = 0f
                    var i = 0
                    while (i < maxSteps) {
                        hitX = ro[0] + rd[0] * marchT
                        hitY = ro[1] + rd[1] * marchT
                        hitZ = ro[2] + rd[2] * marchT
                        val d = apollonianDE(hitX, hitY, hitZ, renderIters, ox, oy, oz, invR2, sphereR, cosRot, sinRot)
                        if (d < epsilon) { hit = true; break }
                        marchT += d * 1.4f
                        if (marchT > maxDist) break
                        i++
                    }

                    if (hit) {
                        // Get trap from center point, then forward-diff normal from 3 offset points
                        apollonianDETrap(deResult, hitX, hitY, hitZ, renderIters, ox, oy, oz, invR2, sphereR, cosRot, sinRot)
                        val nc = deResult[0]
                        val trap = deResult[1]

                        val nx = apollonianDE(hitX + normalEps, hitY, hitZ, renderIters, ox, oy, oz, invR2, sphereR, cosRot, sinRot) - nc
                        val ny = apollonianDE(hitX, hitY + normalEps, hitZ, renderIters, ox, oy, oz, invR2, sphereR, cosRot, sinRot) - nc
                        val nz = apollonianDE(hitX, hitY, hitZ + normalEps, renderIters, ox, oy, oz, invR2, sphereR, cosRot, sinRot) - nc
                        val nLen = sqrt(nx * nx + ny * ny + nz * nz).let { if (it == 0f) 1f else it }
                        normal[0] = nx / nLen; normal[1] = ny / nLen; normal[2] = nz / nLen

                        val vx = ro[0] - hitX; val vy = ro[1] - hitY; val vz = ro[2] - hitZ
                        val vLen = sqrt(vx * vx + vy * vy + vz * vz).let { if (it == 0f) 1f else it }
                        val nvx = vx / vLen; val nvy = vy / vLen; val nvz = vz / vLen

                        // Color from trap (uses pre-extracted components)
                        val colorT = trap.coerceIn(0f, 1f)
                        val ci = (colorT * (nColors - 1)).toInt().coerceIn(0, nColors - 1)
                        val c1i = (ci + 1).coerceAtMost(nColors - 1)
                        val frac = colorT * (nColors - 1) - ci
                        val cr = (colR[ci] + (colR[c1i] - colR[ci]) * frac) / 255f
                        val cg = (colG[ci] + (colG[c1i] - colG[ci]) * frac) / 255f
                        val cb = (colB[ci] + (colB[c1i] - colB[ci]) * frac) / 255f

                        val shade = phongShade(normal[0], normal[1], normal[2], nvx, nvy, nvz, ld[0], ld[1], ld[2], 0.15f, 0.6f, specAmt, shininess)

                        // Cheap step-based AO approximation (no extra SDF evals)
                        val ao = (1f - (i.toFloat() / maxSteps) * 0.4f).coerceIn(0.6f, 1f)

                        // Fresnel rim glow (inlined pow(3) = x*x*x)
                        val NdotV = maxOf(0f, normal[0] * nvx + normal[1] * nvy + normal[2] * nvz)
                        val oneMinusNdV = 1f - NdotV
                        val fresnel = oneMinusNdV * oneMinusNdV * oneMinusNdV * rimGlow

                        val r = cr * shade * ao + rimR * fresnel
                        val g = cg * shade * ao + rimG * fresnel
                        val b = cb * shade * ao + rimB * fresnel

                        toneMapACES(tm, r, g, b, exposure)
                        pixels[py * renderW + px] = Color.rgb(tm[0].toInt().coerceIn(0, 255), tm[1].toInt().coerceIn(0, 255), tm[2].toInt().coerceIn(0, 255))
                    } else {
                        // Sky gradient from palette colors
                        val skyT = rd[1] * 0.5f + 0.5f
                        toneMapACES(tm,
                            skyBotR + (skyTopR - skyBotR) * skyT,
                            skyBotG + (skyTopG - skyBotG) * skyT,
                            skyBotB + (skyTopB - skyBotB) * skyT, exposure)
                        pixels[py * renderW + px] = Color.rgb(tm[0].toInt().coerceIn(0, 255), tm[1].toInt().coerceIn(0, 255), tm[2].toInt().coerceIn(0, 255))
                    }
                }
            }
        }

        renderToCanvas(canvas, bitmap, renderW, renderH, pixels)
    }

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float {
        val iters = extractFloat(params, "iterations", 8f).toInt()
        return (0.4f + iters * 0.04f).coerceIn(0.4f, 1f)
    }

    companion object {
        fun apollonianDE(
            px: Float, py: Float, pz: Float,
            iters: Int, ox: Float, oy: Float, oz: Float,
            invR2: Float, sphereR: Float,
            cosRot: Float, sinRot: Float
        ): Float {
            var x = px * cosRot + pz * sinRot
            var y = py
            var z = -px * sinRot + pz * cosRot
            var scale = 1f

            for (i in 0 until iters) {
                x = abs(x); y = abs(y); z = abs(z)
                if (x < y) { val t = x; x = y; y = t }
                if (x < z) { val t = x; x = z; z = t }
                if (y < z) { val t = y; y = z; z = t }
                x -= ox; y -= oy; z -= oz
                val r2 = x * x + y * y + z * z
                if (r2 < invR2) {
                    val k = invR2 / r2
                    x *= k; y *= k; z *= k
                    scale *= k
                }
                x += ox; y += oy; z += oz
            }
            return (sqrt(x * x + y * y + z * z) - sphereR) / scale
        }

        /**
         * Combined DE + orbit trap in a single pass.
         * out[0] = distance estimate, out[1] = trap value (0..1)
         */
        fun apollonianDETrap(
            out: FloatArray,
            px: Float, py: Float, pz: Float,
            iters: Int, ox: Float, oy: Float, oz: Float,
            invR2: Float, sphereR: Float,
            cosRot: Float, sinRot: Float
        ) {
            var x = px * cosRot + pz * sinRot
            var y = py
            var z = -px * sinRot + pz * cosRot
            var scale = 1f
            var minR2 = Float.MAX_VALUE

            for (i in 0 until iters) {
                x = abs(x); y = abs(y); z = abs(z)
                if (x < y) { val t = x; x = y; y = t }
                if (x < z) { val t = x; x = z; z = t }
                if (y < z) { val t = y; y = z; z = t }
                x -= ox; y -= oy; z -= oz
                val r2 = x * x + y * y + z * z
                if (r2 < minR2) minR2 = r2
                if (r2 < invR2) {
                    val k = invR2 / r2
                    x *= k; y *= k; z *= k
                    scale *= k
                }
                x += ox; y += oy; z += oz
            }
            out[0] = (sqrt(x * x + y * y + z * z) - sphereR) / scale
            out[1] = sqrt(minR2) * 0.5f
        }
    }
}
