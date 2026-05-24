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

        val animating = time > 0f
        val cfg = when (quality) {
            Quality.DRAFT -> if (animating) RenderConfig(0.40f, 64, 0.002f, 50f)
                             else RenderConfig(0.55f, 80, 0.001f, 50f)
            Quality.BALANCED -> if (animating) RenderConfig(0.50f, 80, 0.001f, 50f)
                                else RenderConfig(0.75f, 80, 0.001f, 50f)
            Quality.ULTRA -> if (animating) RenderConfig(0.65f, 128, 0.0005f, 60f)
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
        val waveTimePhase = FloatArray(waveCount) { i -> t * 3f - wavePhase[i] }

        val ro = FloatArray(3)
        cameraFromParams(camDist, camAngle, camHeight, ro)
        val cam = buildCamera(ro[0], ro[1], ro[2], 0f, -0.5f, 0f, fov, renderW.toFloat() / renderH)

        val colors = palette.colorInts()
        val nColors = colors.size
        val colR = IntArray(nColors) { Color.red(colors[it]) }
        val colG = IntArray(nColors) { Color.green(colors[it]) }
        val colB = IntArray(nColors) { Color.blue(colors[it]) }

        val waterIOR = 1.33f
        val eta = 1f / waterIOR
        val floorY = -poolDepth
        val r0w = ((1f - waterIOR) / (1f + waterIOR)).let { it * it }
        val tintR = colR[0] / 255f * 0.3f
        val tintG = colG[0] / 255f * 0.3f + 0.1f
        val tintB = colB[0] / 255f * 0.3f + 0.15f
        val styleId = when (floorStyle) { "Marble" -> 1; "Mosaic" -> 2; "Sand" -> 3; else -> 0 }

        // ─── Pre-compute caustic grid ───────────────────────────────────────
        // Compute visible water area by projecting screen corners to y=0 plane
        val rd0 = FloatArray(3); val rd1 = FloatArray(3)
        val rd2 = FloatArray(3); val rd3 = FloatArray(3)
        getRayDir(rd0, -1f, 1f - 2f, cam)  // bottom-left
        getRayDir(rd1, 1f, 1f - 2f, cam)   // bottom-right
        getRayDir(rd2, -1f, 1f, cam)        // top-left
        getRayDir(rd3, 1f, 1f, cam)         // top-right

        // Find water plane intersections to determine world-space bounds
        var minWX = Float.MAX_VALUE; var maxWX = -Float.MAX_VALUE
        var minWZ = Float.MAX_VALUE; var maxWZ = -Float.MAX_VALUE
        for (corner in arrayOf(rd0, rd1, rd2, rd3)) {
            if (abs(corner[1]) > 0.0001f) {
                val tHit = -ro[1] / corner[1]
                if (tHit > 0f && tHit < 50f) {
                    val wx = ro[0] + corner[0] * tHit
                    val wz = ro[2] + corner[2] * tHit
                    if (wx < minWX) minWX = wx; if (wx > maxWX) maxWX = wx
                    if (wz < minWZ) minWZ = wz; if (wz > maxWZ) maxWZ = wz
                }
            }
        }
        // Clamp to reasonable bounds
        minWX = minWX.coerceIn(-15f, 15f); maxWX = maxWX.coerceIn(-15f, 15f)
        minWZ = minWZ.coerceIn(-15f, 15f); maxWZ = maxWZ.coerceIn(-15f, 15f)
        // Add margin for normal stencil
        minWX -= 0.1f; minWZ -= 0.1f; maxWX += 0.1f; maxWZ += 0.1f

        // Grid resolution — enough to sample wave pattern without aliasing
        val gridSize = if (animating) 80 else 128
        val gridW = gridSize; val gridH = gridSize
        val gridTotal = gridW * gridH
        val cellW = (maxWX - minWX) / gridW
        val cellH = (maxWZ - minWZ) / gridH

        // Grid stores: height, dh/dx, dh/dz, caustic (4 floats per cell)
        val gridHeight = FloatArray(gridTotal)
        val gridDhdx = FloatArray(gridTotal)
        val gridDhdz = FloatArray(gridTotal)
        val gridCaustic = FloatArray(gridTotal)

        // Fill grid (multi-threaded)
        val eps = 0.04f
        val invEps = 1f / eps
        val invEps2 = 1f / (eps * eps)
        val gridCores = Runtime.getRuntime().availableProcessors().coerceIn(2, 8)
        val gridThreads = Array(gridCores) { tc ->
            Thread {
                val gy0 = tc * gridH / gridCores
                val gy1 = (tc + 1) * gridH / gridCores
                for (gy in gy0 until gy1) {
                    val wz = minWZ + (gy + 0.5f) * cellH
                    for (gx in 0 until gridW) {
                        val wx = minWX + (gx + 0.5f) * cellW
                        val idx = gy * gridW + gx

                        // 5-point stencil
                        var hc = 0f; var hPx = 0f; var hNx = 0f; var hPz = 0f; var hNz = 0f
                        for (i in 0 until waveCount) {
                            val sx = waveSrcX[i]; val sz = waveSrcZ[i]
                            val tp = waveTimePhase[i]
                            val dcx = wx - sx; val dcz = wz - sz

                            val distC = sqrt(dcx * dcx + dcz * dcz)
                            hc += sin(distC * waveFreq - tp) * waveAmp / (1f + distC * 0.3f)

                            val dpxx = dcx + eps
                            hPx += sin(sqrt(dpxx * dpxx + dcz * dcz) * waveFreq - tp) * waveAmp / (1f + sqrt(dpxx * dpxx + dcz * dcz) * 0.3f)

                            val dnxx = dcx - eps
                            hNx += sin(sqrt(dnxx * dnxx + dcz * dcz) * waveFreq - tp) * waveAmp / (1f + sqrt(dnxx * dnxx + dcz * dcz) * 0.3f)

                            val dpzz = dcz + eps
                            hPz += sin(sqrt(dcx * dcx + dpzz * dpzz) * waveFreq - tp) * waveAmp / (1f + sqrt(dcx * dcx + dpzz * dpzz) * 0.3f)

                            val dnzz = dcz - eps
                            hNz += sin(sqrt(dcx * dcx + dnzz * dnzz) * waveFreq - tp) * waveAmp / (1f + sqrt(dcx * dcx + dnzz * dnzz) * 0.3f)
                        }

                        gridHeight[idx] = hc
                        gridDhdx[idx] = (hPx - hNx) * 0.5f * invEps
                        gridDhdz[idx] = (hPz - hNz) * 0.5f * invEps
                        gridCaustic[idx] = (abs((hPx - 2f * hc + hNx) * invEps2 + (hPz - 2f * hc + hNz) * invEps2) * 2f).coerceAtMost(3f)
                    }
                }
            }.also { it.start() }
        }
        gridThreads.forEach { it.join() }

        // ─── Pixel rendering (cheap: just grid lookup + floor color) ────────
        val pixels = IntArray(renderW * renderH)
        val invW = 2f / renderW; val invH = 2f / renderH
        val invCellW = 1f / cellW; val invCellH = 1f / cellH

        renderMultithreaded(renderW, renderH, pixels) { y0, y1, rd, normal, tm, _ ->
            val refr = FloatArray(3)
            for (py in y0 until y1) {
                val v = 1f - py * invH
                for (px in 0 until renderW) {
                    val u = px * invW - 1f
                    getRayDir(rd, u, v, cam)

                    if (abs(rd[1]) < 0.0001f || ro[1] * rd[1] > 0f) {
                        val skyT = rd[1] * 0.5f + 0.5f
                        toneMapACES(tm, 0.1f + skyT * 0.2f, 0.12f + skyT * 0.3f, 0.2f + skyT * 0.5f, exposure)
                        pixels[py * renderW + px] = Color.rgb(tm[0].toInt().coerceIn(0, 255), tm[1].toInt().coerceIn(0, 255), tm[2].toInt().coerceIn(0, 255))
                        continue
                    }

                    val waterHit = -ro[1] / rd[1]
                    val hitX = ro[0] + rd[0] * waterHit
                    val hitZ = ro[2] + rd[2] * waterHit

                    // Bilinear grid lookup
                    val gfx = (hitX - minWX) * invCellW - 0.5f
                    val gfz = (hitZ - minWZ) * invCellH - 0.5f
                    val gix = gfx.toInt().coerceIn(0, gridW - 2)
                    val giz = gfz.toInt().coerceIn(0, gridH - 2)
                    val fx = (gfx - gix).coerceIn(0f, 1f)
                    val fz = (gfz - giz).coerceIn(0f, 1f)

                    val i00 = giz * gridW + gix; val i10 = i00 + 1
                    val i01 = i00 + gridW; val i11 = i01 + 1

                    val hc = gridHeight[i00] * (1f - fx) * (1f - fz) + gridHeight[i10] * fx * (1f - fz) +
                             gridHeight[i01] * (1f - fx) * fz + gridHeight[i11] * fx * fz
                    val dhdx = gridDhdx[i00] * (1f - fx) * (1f - fz) + gridDhdx[i10] * fx * (1f - fz) +
                               gridDhdx[i01] * (1f - fx) * fz + gridDhdx[i11] * fx * fz
                    val dhdz = gridDhdz[i00] * (1f - fx) * (1f - fz) + gridDhdz[i10] * fx * (1f - fz) +
                               gridDhdz[i01] * (1f - fx) * fz + gridDhdz[i11] * fx * fz
                    val caustic = gridCaustic[i00] * (1f - fx) * (1f - fz) + gridCaustic[i10] * fx * (1f - fz) +
                                  gridCaustic[i01] * (1f - fx) * fz + gridCaustic[i11] * fx * fz

                    // Normal from interpolated gradient
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
                                    val ffx = sx - cellX; val ffz = sz - cellZ
                                    var minDist = 10f; var cellId = 0
                                    for (di in -1..1) for (dj in -1..1) {
                                        val cx = (cellX + di).toInt(); val cz = (cellZ + dj).toInt()
                                        val hv = hash3(cx, 0, cz)
                                        val px2 = di + hv * 0.8f - ffx
                                        val pz2 = dj + hash3(cz, 0, cx) * 0.8f - ffz
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

                    // Fresnel
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
