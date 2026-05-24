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

class CausticPoolGenerator : Generator {

    override val id = "shader-caustic-pool"
    override val family = "shader"
    override val styleName = "Caustic Pool"
    override val definition =
        "Volumetric water caustics simulation with refraction patterns on a pool floor."
    override val algorithmNotes =
        "Ray from camera hits animated water surface (sum of radial sine waves). Refraction through surface hits pool floor. " +
        "Caustic brightness computed from wave normal convergence. Exponential fog for depth."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema = listOf(
        Parameter.NumberParam("Camera Dist", "cameraDistance", ParamGroup.COMPOSITION,
            "Distance of camera from origin", 1f, 12f, 0.5f, 4f),
        Parameter.NumberParam("FOV", "fov", ParamGroup.COMPOSITION,
            "Field of view in degrees", 20f, 120f, 5f, 40f),
        Parameter.NumberParam("Camera Angle", "cameraAngle", ParamGroup.COMPOSITION,
            "Horizontal camera orbit angle", 0f, 360f, 5f, 30f),
        Parameter.NumberParam("Camera Height", "cameraHeight", ParamGroup.COMPOSITION,
            "Vertical camera position", 0.5f, 5f, 0.1f, 2.5f),
        Parameter.NumberParam("Light Angle", "lightAngle", ParamGroup.TEXTURE,
            "Horizontal light direction", 0f, 360f, 5f, 45f),
        Parameter.NumberParam("Light Height", "lightHeight", ParamGroup.TEXTURE,
            "Vertical light direction", 0.5f, 3f, 0.1f, 1.5f),
        Parameter.NumberParam("Exposure", "exposure", ParamGroup.TEXTURE,
            "Tone mapping exposure", 0.5f, 3f, 0.1f, 1.4f),
        Parameter.NumberParam("Speed", "speed", ParamGroup.FLOW_MOTION,
            "Animation speed", 0.1f, 2f, 0.1f, 0.5f),
        Parameter.NumberParam("Wave Count", "waveCount", ParamGroup.GEOMETRY,
            "Number of radial wave sources", 2f, 8f, 1f, 4f),
        Parameter.NumberParam("Wave Frequency", "waveFrequency", ParamGroup.GEOMETRY,
            "Spatial frequency of waves", 1f, 6f, 0.5f, 3f),
        Parameter.NumberParam("Wave Amplitude", "waveAmplitude", ParamGroup.GEOMETRY,
            "Height of waves", 0.01f, 0.15f, 0.01f, 0.05f),
        Parameter.NumberParam("Depth", "poolDepth", ParamGroup.GEOMETRY,
            "Pool depth below water surface", 0.5f, 4f, 0.25f, 1.5f),
        Parameter.SelectParam("Floor Style", "floorStyle", ParamGroup.TEXTURE,
            "Pattern on the pool floor",
            listOf("Tiles", "Marble", "Mosaic", "Sand"), "Tiles")
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "cameraDistance" to 4f, "fov" to 40f, "cameraAngle" to 30f, "cameraHeight" to 2.5f,
        "lightAngle" to 45f, "lightHeight" to 1.5f, "exposure" to 1.4f, "speed" to 0.5f,
        "waveCount" to 4f, "waveFrequency" to 3f, "waveAmplitude" to 0.05f, "poolDepth" to 1.5f,
        "floorStyle" to "Tiles"
    )

    override fun renderCanvas(
        canvas: Canvas, bitmap: Bitmap, params: Map<String, Any>,
        seed: Int, palette: Palette, quality: Quality, time: Float
    ) {
        val w = bitmap.width; val h = bitmap.height

        // Light generator — use higher resolution than generic "medium"
        val animating = time > 0f
        val cfg = when (quality) {
            Quality.DRAFT -> if (animating) RenderConfig(0.45f, 64, 0.002f, 50f)
                             else RenderConfig(0.55f, 80, 0.001f, 50f)
            Quality.BALANCED -> if (animating) RenderConfig(0.60f, 80, 0.001f, 50f)
                                else RenderConfig(0.75f, 80, 0.001f, 50f)
            Quality.ULTRA -> if (animating) RenderConfig(0.70f, 128, 0.0005f, 60f)
                             else RenderConfig(0.90f, 128, 0.0005f, 60f)
        }
        val renderW = (w * cfg.scale).toInt().coerceAtLeast(100)
        val renderH = (h * cfg.scale).toInt().coerceAtLeast(100)

        val camDist = extractFloat(params, "cameraDistance", 4f)
        val fov = extractFloat(params, "fov", 40f)
        val camAngle = extractFloat(params, "cameraAngle", 30f)
        val camHeight = extractFloat(params, "cameraHeight", 2.5f)
        val lightAngle = extractFloat(params, "lightAngle", 45f)
        val lightHeight = extractFloat(params, "lightHeight", 1.5f)
        val exposure = extractFloat(params, "exposure", 1.4f)
        val spd = extractFloat(params, "speed", 0.5f)
        val waveCount = extractFloat(params, "waveCount", 4f).toInt().coerceIn(2, 8)
        val waveFreq = extractFloat(params, "waveFrequency", 3f)
        val waveAmp = extractFloat(params, "waveAmplitude", 0.05f)
        val poolDepth = extractFloat(params, "poolDepth", 1.5f)
        val floorStyle = extractString(params, "floorStyle", "Tiles")
        val t = time * spd

        val rng = SeededRNG(seed)
        val waveSrcX = FloatArray(waveCount)
        val waveSrcZ = FloatArray(waveCount)
        val wavePhase = FloatArray(waveCount)
        for (i in 0 until waveCount) {
            waveSrcX[i] = rng.range(-3f, 3f)
            waveSrcZ[i] = rng.range(-3f, 3f)
            wavePhase[i] = rng.randomAngle()
        }

        // Pre-compute time-dependent phase offsets (constant for all pixels)
        val waveTimePhase = FloatArray(waveCount) { i -> t * 3f - wavePhase[i] }

        val ro = FloatArray(3)
        cameraFromParams(camDist, camAngle, camHeight, ro)
        val cam = buildCamera(ro[0], ro[1], ro[2], 0f, -0.5f, 0f, fov, renderW.toFloat() / renderH)

        val colors = palette.colorInts()
        val nColors = colors.size

        // Pre-extract color components to avoid per-pixel Color.red/green/blue calls
        val colR = IntArray(nColors) { Color.red(colors[it]) }
        val colG = IntArray(nColors) { Color.green(colors[it]) }
        val colB = IntArray(nColors) { Color.blue(colors[it]) }

        val waterIOR = 1.33f
        val eta = 1f / waterIOR
        val floorY = -poolDepth

        // Pre-compute Fresnel R0 for water
        val r0w = ((1f - waterIOR) / (1f + waterIOR)).let { it * it }

        // Pre-compute water tint from palette[0]
        val tintR = colR[0] / 255f * 0.3f
        val tintG = colG[0] / 255f * 0.3f + 0.1f
        val tintB = colB[0] / 255f * 0.3f + 0.15f

        // Floor style enum to avoid string comparison in hot loop
        val styleId = when (floorStyle) { "Marble" -> 1; "Mosaic" -> 2; "Sand" -> 3; else -> 0 }

        val pixels = IntArray(renderW * renderH)
        val invW = 2f / renderW; val invH = 2f / renderH

        // Stencil epsilon: unified for normal + caustic (saves 3 waterHeight calls per pixel)
        val eps = 0.03f
        val invEps = 1f / eps
        val invEps2 = 1f / (eps * eps)

        renderMultithreaded(renderW, renderH, pixels) { y0, y1, rd, normal, tm, _ ->
            val refr = FloatArray(3)
            for (py in y0 until y1) {
                val v = 1f - py * invH
                for (px in 0 until renderW) {
                    val u = px * invW - 1f
                    getRayDir(rd, u, v, cam)

                    // Intersect ray with water plane at y=0
                    if (abs(rd[1]) < 0.0001f || ro[1] * rd[1] > 0f) {
                        // Sky (ray parallel to water or pointing away)
                        val skyT = rd[1] * 0.5f + 0.5f
                        toneMapACES(tm, 0.1f + skyT * 0.2f, 0.12f + skyT * 0.3f, 0.2f + skyT * 0.5f, exposure)
                        pixels[py * renderW + px] = Color.rgb(tm[0].toInt().coerceIn(0, 255), tm[1].toInt().coerceIn(0, 255), tm[2].toInt().coerceIn(0, 255))
                        continue
                    }

                    val waterHit = -ro[1] / rd[1]
                    val hitX = ro[0] + rd[0] * waterHit
                    val hitZ = ro[2] + rd[2] * waterHit

                    // 5-point stencil: compute normal AND caustic from same samples
                    var hc = 0f; var hPx = 0f; var hNx = 0f; var hPz = 0f; var hNz = 0f
                    for (i in 0 until waveCount) {
                        val sx = waveSrcX[i]; val sz = waveSrcZ[i]
                        val tp = waveTimePhase[i]

                        // Center
                        val dcx = hitX - sx; val dcz = hitZ - sz
                        val distC = sqrt(dcx * dcx + dcz * dcz)
                        val attC = waveAmp / (1f + distC * 0.3f)
                        hc += sin(distC * waveFreq - tp) * attC

                        // +X
                        val dpxx = dcx + eps
                        val distPx = sqrt(dpxx * dpxx + dcz * dcz)
                        hPx += sin(distPx * waveFreq - tp) * waveAmp / (1f + distPx * 0.3f)

                        // -X
                        val dnxx = dcx - eps
                        val distNx = sqrt(dnxx * dnxx + dcz * dcz)
                        hNx += sin(distNx * waveFreq - tp) * waveAmp / (1f + distNx * 0.3f)

                        // +Z
                        val dpzz = dcz + eps
                        val distPz = sqrt(dcx * dcx + dpzz * dpzz)
                        hPz += sin(distPz * waveFreq - tp) * waveAmp / (1f + distPz * 0.3f)

                        // -Z
                        val dnzz = dcz - eps
                        val distNz = sqrt(dcx * dcx + dnzz * dnzz)
                        hNz += sin(distNz * waveFreq - tp) * waveAmp / (1f + distNz * 0.3f)
                    }

                    // Central-difference normal (more accurate than forward-diff)
                    val dhdx = (hPx - hNx) * 0.5f * invEps
                    val dhdz = (hPz - hNz) * 0.5f * invEps
                    val nLen = sqrt(dhdx * dhdx + 1f + dhdz * dhdz)
                    val invNLen = 1f / nLen
                    normal[0] = -dhdx * invNLen; normal[1] = invNLen; normal[2] = -dhdz * invNLen

                    var r = 0f; var g = 0f; var b = 0f

                    // Refract through water
                    val NdotI = rd[0] * normal[0] + rd[1] * normal[1] + rd[2] * normal[2]
                    val k = 1f - eta * eta * (1f - NdotI * NdotI)
                    if (k >= 0f) {
                        val sq = sqrt(k)
                        refr[0] = eta * rd[0] - (eta * NdotI + sq) * normal[0]
                        refr[1] = eta * rd[1] - (eta * NdotI + sq) * normal[1]
                        refr[2] = eta * rd[2] - (eta * NdotI + sq) * normal[2]

                        val toFloor = (floorY - hc) / refr[1]
                        if (toFloor > 0f) {
                            val floorX = hitX + refr[0] * toFloor
                            val floorZ = hitZ + refr[2] * toFloor

                            // Caustic from Laplacian (reuses stencil samples)
                            val laplacian = abs((hPx - 2f * hc + hNx) * invEps2 + (hPz - 2f * hc + hNz) * invEps2)
                            val caustic = (laplacian * 2f).coerceAtMost(3f)

                            // Floor color
                            val baseR: Float; val baseG: Float; val baseB: Float
                            when (styleId) {
                                1 -> { // Marble
                                    val vein = sin(floorX * 3f + floorZ * 2f +
                                        sin(floorX * 5f) * 0.5f + sin(floorZ * 4f) * 0.3f) * 0.5f + 0.5f
                                    val ci = (vein * (nColors - 1)).toInt().coerceIn(0, nColors - 1)
                                    val ci2 = (ci + 1).coerceAtMost(nColors - 1)
                                    val frac = vein * (nColors - 1) - ci
                                    baseR = (colR[ci] + (colR[ci2] - colR[ci]) * frac) / 255f
                                    baseG = (colG[ci] + (colG[ci2] - colG[ci]) * frac) / 255f
                                    baseB = (colB[ci] + (colB[ci2] - colB[ci]) * frac) / 255f
                                }
                                2 -> { // Mosaic
                                    val sx = floorX * 1.5f; val sz = floorZ * 1.5f
                                    val cellX = floor(sx); val cellZ = floor(sz)
                                    val fx = sx - cellX; val fz = sz - cellZ
                                    var minDist = 10f; var cellId = 0
                                    for (di in -1..1) for (dj in -1..1) {
                                        val cx = (cellX + di).toInt(); val cz = (cellZ + dj).toInt()
                                        val hv = hash3(cx, 0, cz)
                                        val px2 = di + hv * 0.8f - fx
                                        val pz2 = dj + hash3(cz, 0, cx) * 0.8f - fz
                                        val d2 = px2 * px2 + pz2 * pz2
                                        if (d2 < minDist) { minDist = d2; cellId = (hv * 1000f).toInt() }
                                    }
                                    val ci = abs(cellId) % nColors
                                    val edge = (sqrt(minDist) * 3f).coerceAtMost(1f)
                                    baseR = colR[ci] / 255f * edge
                                    baseG = colG[ci] / 255f * edge
                                    baseB = colB[ci] / 255f * edge
                                }
                                3 -> { // Sand
                                    val n1 = hash3((floorX * 10f).toInt(), 0, (floorZ * 10f).toInt())
                                    val n2 = hash3((floorX * 3f).toInt(), 1, (floorZ * 3f).toInt())
                                    val blend = n1 * 0.4f + n2 * 0.6f
                                    val ci = (blend * (nColors - 1)).toInt().coerceIn(0, nColors - 1)
                                    val sandBright = 0.7f + n1 * 0.3f
                                    baseR = colR[ci] / 255f * sandBright
                                    baseG = colG[ci] / 255f * sandBright
                                    baseB = colB[ci] / 255f * sandBright
                                }
                                else -> { // Tiles
                                    val tileX = ((floor(floorX * 2f).toInt() + 1000) % nColors)
                                    val tileZ = ((floor(floorZ * 2f).toInt() + 1000) % nColors)
                                    val ci = abs(tileX + tileZ) % nColors
                                    baseR = colR[ci] / 255f
                                    baseG = colG[ci] / 255f
                                    baseB = colB[ci] / 255f
                                }
                            }

                            val brightness = 0.3f + caustic * 1.5f
                            val fog = exp(-abs(toFloor) * 0.3f)
                            val invFog = 1f - fog

                            r = baseR * brightness * fog + tintR * invFog
                            g = baseG * brightness * fog + tintG * invFog
                            b = baseB * brightness * fog + tintB * invFog
                        }
                    }

                    // Fresnel (inlined Schlick, avoids pow(5) — use x²×x²×x)
                    val cosI = abs(NdotI)
                    val oneMinusC = 1f - cosI
                    val omc2 = oneMinusC * oneMinusC
                    val fres = (r0w + (1f - r0w) * omc2 * omc2 * oneMinusC) * 0.4f
                    r += fres * 0.3f; g += fres * 0.35f; b += fres * 0.4f

                    toneMapACES(tm, r, g, b, exposure)
                    pixels[py * renderW + px] = Color.rgb(tm[0].toInt().coerceIn(0, 255), tm[1].toInt().coerceIn(0, 255), tm[2].toInt().coerceIn(0, 255))
                }
            }
        }

        renderToCanvas(canvas, bitmap, renderW, renderH, pixels)
    }

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float {
        val waves = extractFloat(params, "waveCount", 4f).toInt()
        return (0.3f + waves * 0.04f).coerceIn(0.3f, 0.7f)
    }
}
