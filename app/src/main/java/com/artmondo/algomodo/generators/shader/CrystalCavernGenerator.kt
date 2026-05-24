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

class CrystalCavernGenerator : Generator {

    override val id = "shader-crystal-cavern"
    override val family = "shader"
    override val styleName = "Crystal Cavern"
    override val definition =
        "Crystal formations via 3D Voronoi cell structure combined with fractal domain operations."
    override val algorithmNotes =
        "3D Voronoi distance field creates crystal-like cell boundaries. Combined with optional fractal " +
        "folding for complex geometry. Per-cell color variation from cell ID. Fresnel rim glow on edges."
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
        Parameter.NumberParam("Cell Scale", "cellScale", ParamGroup.GEOMETRY,
            "Size of Voronoi cells", 1f, 4f, 0.25f, 2f),
        Parameter.NumberParam("Crystal Sharpness", "sharpness", ParamGroup.GEOMETRY,
            "Sharpness of crystal facets", 0.1f, 2f, 0.1f, 0.8f),
        Parameter.NumberParam("Rim Glow", "rimGlow", ParamGroup.TEXTURE,
            "Fresnel rim glow intensity", 0f, 2f, 0.1f, 0.7f),
        Parameter.NumberParam("Roughness", "roughness", ParamGroup.TEXTURE,
            "Surface roughness", 0.1f, 1f, 0.05f, 0.25f)
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "cameraDistance" to 4f, "fov" to 60f, "cameraAngle" to 0f, "cameraHeight" to 0.5f,
        "lightAngle" to 45f, "lightHeight" to 0.8f, "exposure" to 1.2f, "speed" to 0.5f,
        "cellScale" to 2f, "sharpness" to 0.8f, "rimGlow" to 0.7f, "roughness" to 0.25f
    )

    override fun renderCanvas(
        canvas: Canvas, bitmap: Bitmap, params: Map<String, Any>,
        seed: Int, palette: Palette, quality: Quality, time: Float
    ) {
        val w = bitmap.width; val h = bitmap.height
        val animating = time > 0f

        // Higher resolution, fewer steps — bounding sphere handles misses cheaply
        val cfg = when (quality) {
            Quality.DRAFT -> if (animating) RenderConfig(0.38f, 40, 0.004f, 25f)
                             else RenderConfig(0.50f, 64, 0.002f, 40f)
            Quality.BALANCED -> if (animating) RenderConfig(0.50f, 48, 0.003f, 28f)
                                else RenderConfig(0.68f, 80, 0.001f, 50f)
            Quality.ULTRA -> if (animating) RenderConfig(0.62f, 60, 0.002f, 35f)
                             else RenderConfig(0.85f, 128, 0.0005f, 60f)
        }
        val renderW = (w * cfg.scale).toInt().coerceAtLeast(100)
        val renderH = (h * cfg.scale).toInt().coerceAtLeast(100)

        val camDist = extractFloat(params, "cameraDistance", 4f)
        val fov = extractFloat(params, "fov", 60f)
        val camAngle = extractFloat(params, "cameraAngle", 0f)
        val camHeight = extractFloat(params, "cameraHeight", 0.5f)
        val lightAngle = extractFloat(params, "lightAngle", 45f)
        val lightHeight = extractFloat(params, "lightHeight", 0.8f)
        val exposure = extractFloat(params, "exposure", 1.2f)
        val spd = extractFloat(params, "speed", 0.5f)
        val cellScale = extractFloat(params, "cellScale", 2f)
        val sharpness = extractFloat(params, "sharpness", 0.8f)
        val rimGlow = extractFloat(params, "rimGlow", 0.7f)
        val roughness = extractFloat(params, "roughness", 0.25f)
        val t = time * spd

        val ro = FloatArray(3)
        cameraFromParams(camDist, camAngle + t * 10f, camHeight, ro)
        val cam = buildCamera(ro[0], ro[1], ro[2], 0f, 0f, 0f, fov, renderW.toFloat() / renderH)
        val ld = FloatArray(3)
        lightDirFromParams(lightAngle, lightHeight, ld)

        val colors = palette.colorInts()
        val nColors = colors.size
        val colR = IntArray(nColors) { Color.red(colors[it]) }
        val colG = IntArray(nColors) { Color.green(colors[it]) }
        val colB = IntArray(nColors) { Color.blue(colors[it]) }
        val shininess = 10f + (1f - roughness) * 120f
        val specAmt = (1f - roughness) * 0.9f

        // Palette-derived sky
        val lastC = colors[nColors - 1]; val firstC = colors[0]
        val skyTopR = Color.red(lastC) / 255f * 0.12f
        val skyTopG = Color.green(lastC) / 255f * 0.12f
        val skyTopB = Color.blue(lastC) / 255f * 0.12f
        val skyBotR = Color.red(firstC) / 255f * 0.15f
        val skyBotG = Color.green(firstC) / 255f * 0.15f
        val skyBotB = Color.blue(firstC) / 255f * 0.15f

        val boundR = 2.5f
        val invCellScale = 1f / cellScale
        val halfCellScale = cellScale * 0.5f
        val maxSteps = cfg.maxSteps
        val epsilon = cfg.epsilon
        val maxDist = cfg.maxDist
        val normalEps = if (animating) epsilon * 5f else epsilon * 2.5f
        val seedConst = seed * 1013904223

        val pixels = IntArray(renderW * renderH)
        val invW = 2f / renderW; val invH = 2f / renderH

        renderMultithreaded(renderW, renderH, pixels) { y0, y1, rd, normal, tm, _ ->
            for (py in y0 until y1) {
                val v = 1f - py * invH
                for (px in 0 until renderW) {
                    val u = px * invW - 1f
                    getRayDir(rd, u, v, cam)

                    // Bounding sphere early-out
                    val bDot = ro[0] * rd[0] + ro[1] * rd[1] + ro[2] * rd[2]
                    val cDist = ro[0] * ro[0] + ro[1] * ro[1] + ro[2] * ro[2] - boundR * boundR
                    val disc = bDot * bDot - cDist
                    if (disc < 0f) {
                        val skyT = rd[1] * 0.5f + 0.5f
                        toneMapACES(tm, skyBotR + (skyTopR - skyBotR) * skyT, skyBotG + (skyTopG - skyBotG) * skyT, skyBotB + (skyTopB - skyBotB) * skyT, exposure)
                        pixels[py * renderW + px] = Color.rgb(tm[0].toInt().coerceIn(0, 255), tm[1].toInt().coerceIn(0, 255), tm[2].toInt().coerceIn(0, 255))
                        continue
                    }

                    val tEntry = maxOf(0f, -bDot - sqrt(disc))
                    var marchT = tEntry
                    var hit = false
                    var hitX = 0f; var hitY = 0f; var hitZ = 0f
                    var stepsTaken = 0

                    while (stepsTaken < maxSteps) {
                        hitX = ro[0] + rd[0] * marchT
                        hitY = ro[1] + rd[1] * marchT
                        hitZ = ro[2] + rd[2] * marchT
                        val d = crystalSDF(hitX, hitY, hitZ, invCellScale, sharpness, halfCellScale, seedConst)
                        if (d < epsilon) { hit = true; break }
                        marchT += d * 1.3f
                        if (marchT > maxDist) break
                        stepsTaken++
                    }

                    if (hit) {
                        // Forward-diff normal + get cellId from center evaluation
                        val ncResult = crystalSDFWithCell(hitX, hitY, hitZ, invCellScale, sharpness, halfCellScale, seedConst)
                        val nc = ncResult[0]; val cellId = ncResult[1].toInt()
                        val nx = crystalSDF(hitX + normalEps, hitY, hitZ, invCellScale, sharpness, halfCellScale, seedConst) - nc
                        val ny = crystalSDF(hitX, hitY + normalEps, hitZ, invCellScale, sharpness, halfCellScale, seedConst) - nc
                        val nz = crystalSDF(hitX, hitY, hitZ + normalEps, invCellScale, sharpness, halfCellScale, seedConst) - nc
                        val nLen = sqrt(nx * nx + ny * ny + nz * nz).let { if (it == 0f) 1f else it }
                        normal[0] = nx / nLen; normal[1] = ny / nLen; normal[2] = nz / nLen

                        val vx = ro[0] - hitX; val vy = ro[1] - hitY; val vz = ro[2] - hitZ
                        val vLen = sqrt(vx * vx + vy * vy + vz * vz).let { if (it == 0f) 1f else it }
                        val nvx = vx / vLen; val nvy = vy / vLen; val nvz = vz / vLen

                        // Color from cell ID
                        val ci = (abs(cellId) % nColors)
                        val cr = colR[ci] / 255f
                        val cg = colG[ci] / 255f
                        val cb = colB[ci] / 255f

                        val shade = phongShade(normal[0], normal[1], normal[2], nvx, nvy, nvz, ld[0], ld[1], ld[2], 0.1f, 0.55f, specAmt, shininess)
                        // Step-count AO (free)
                        val ao = (1f - (stepsTaken.toFloat() / maxSteps) * 0.5f).coerceIn(0.5f, 1f)

                        // Rim glow
                        val NdotV = maxOf(0f, normal[0] * nvx + normal[1] * nvy + normal[2] * nvz)
                        val oneMinusNdV = 1f - NdotV
                        val fresnel = oneMinusNdV * oneMinusNdV * oneMinusNdV * rimGlow
                        val rimIdx = (ci + nColors / 2) % nColors

                        val r = cr * shade * ao + colR[rimIdx] / 255f * fresnel
                        val g = cg * shade * ao + colG[rimIdx] / 255f * fresnel
                        val b = cb * shade * ao + colB[rimIdx] / 255f * fresnel

                        toneMapACES(tm, r, g, b, exposure)
                        pixels[py * renderW + px] = Color.rgb(tm[0].toInt().coerceIn(0, 255), tm[1].toInt().coerceIn(0, 255), tm[2].toInt().coerceIn(0, 255))
                    } else {
                        val skyT = rd[1] * 0.5f + 0.5f
                        toneMapACES(tm, skyBotR + (skyTopR - skyBotR) * skyT, skyBotG + (skyTopG - skyBotG) * skyT, skyBotB + (skyTopB - skyBotB) * skyT, exposure)
                        pixels[py * renderW + px] = Color.rgb(tm[0].toInt().coerceIn(0, 255), tm[1].toInt().coerceIn(0, 255), tm[2].toInt().coerceIn(0, 255))
                    }
                }
            }
        }

        renderToCanvas(canvas, bitmap, renderW, renderH, pixels)
    }

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float = 0.6f

    companion object {
        // Zero-allocation hash returning one component at a time
        private fun hashX(ix: Int, iy: Int, iz: Int, seedC: Int): Float {
            val h = ((ix * 374761393 + iy * 668265263 + iz * 1274126177 + seedC) xor 0x5bd1e995) * 1103515245
            return ((h ushr 8) and 0xFF) / 255f
        }
        private fun hashY(ix: Int, iy: Int, iz: Int, seedC: Int): Float {
            val h = (((ix * 374761393 + iy * 668265263 + iz * 1274126177 + seedC) xor 0x5bd1e995) * 1103515245 xor 0x27d4eb2d) * 1103515245
            return ((h ushr 8) and 0xFF) / 255f
        }
        private fun hashZ(ix: Int, iy: Int, iz: Int, seedC: Int): Float {
            val h = (((ix * 374761393 + iy * 668265263 + iz * 1274126177 + seedC) xor 0x85ebca6b.toInt()) * 1103515245 xor 0xc2b2ae35.toInt()) * 1103515245
            return ((h ushr 8) and 0xFF) / 255f
        }

        // Fast SDF without allocation
        fun crystalSDF(px: Float, py: Float, pz: Float, invCellScale: Float, sharpness: Float, halfCellScale: Float, seedC: Int): Float {
            val sx = px * invCellScale; val sy = py * invCellScale; val sz = pz * invCellScale
            val ix = floor(sx).toInt(); val iy = floor(sy).toInt(); val iz = floor(sz).toInt()
            val fx = sx - ix; val fy = sy - iy; val fz = sz - iz

            var d1sq = Float.MAX_VALUE; var d2sq = Float.MAX_VALUE

            for (ddx in -1..1) for (ddy in -1..1) for (ddz in -1..1) {
                val cx = ix + ddx; val cy = iy + ddy; val cz = iz + ddz
                val vx = ddx + hashX(cx, cy, cz, seedC) - fx
                val vy = ddy + hashY(cx, cy, cz, seedC) - fy
                val vz = ddz + hashZ(cx, cy, cz, seedC) - fz
                val distSq = vx * vx + vy * vy + vz * vz
                if (distSq < d1sq) { d2sq = d1sq; d1sq = distSq }
                else if (distSq < d2sq) { d2sq = distSq }
            }

            // Use sqrt only on final d1/d2 (not per-neighbor)
            val crystal = (sqrt(d2sq) - sqrt(d1sq)) * sharpness - 0.1f
            val bound = sqrt(px * px + py * py + pz * pz) - 2f
            return maxOf(crystal, bound) * halfCellScale
        }

        // SDF + cellId in one pass (avoids separate voronoiCellId search)
        fun crystalSDFWithCell(px: Float, py: Float, pz: Float, invCellScale: Float, sharpness: Float, halfCellScale: Float, seedC: Int): FloatArray {
            val sx = px * invCellScale; val sy = py * invCellScale; val sz = pz * invCellScale
            val ix = floor(sx).toInt(); val iy = floor(sy).toInt(); val iz = floor(sz).toInt()
            val fx = sx - ix; val fy = sy - iy; val fz = sz - iz

            var d1sq = Float.MAX_VALUE; var d2sq = Float.MAX_VALUE
            var bestCx = 0; var bestCy = 0; var bestCz = 0

            for (ddx in -1..1) for (ddy in -1..1) for (ddz in -1..1) {
                val cx = ix + ddx; val cy = iy + ddy; val cz = iz + ddz
                val vx = ddx + hashX(cx, cy, cz, seedC) - fx
                val vy = ddy + hashY(cx, cy, cz, seedC) - fy
                val vz = ddz + hashZ(cx, cy, cz, seedC) - fz
                val distSq = vx * vx + vy * vy + vz * vz
                if (distSq < d1sq) {
                    d2sq = d1sq; d1sq = distSq
                    bestCx = cx; bestCy = cy; bestCz = cz
                } else if (distSq < d2sq) { d2sq = distSq }
            }

            val crystal = (sqrt(d2sq) - sqrt(d1sq)) * sharpness - 0.1f
            val bound = sqrt(px * px + py * py + pz * pz) - 2f
            val sdf = maxOf(crystal, bound) * halfCellScale
            val cellId = abs(bestCx * 374761393 + bestCy * 668265263 + bestCz * 1274126177 + seedC).toFloat()
            return floatArrayOf(sdf, cellId)
        }
    }
}
