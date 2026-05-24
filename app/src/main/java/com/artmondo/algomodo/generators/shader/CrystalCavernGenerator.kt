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
        val shininess = 10f + (1f - roughness) * 120f
        val specAmt = (1f - roughness) * 0.9f

        // Voronoi-based crystal SDF
        val sceneSDF: SceneSDF = { px, py, pz ->
            voronoiCrystalSDF(px, py, pz, cellScale, sharpness, seed)
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
                        val nvx = vx / vLen; val nvy = vy / vLen; val nvz = vz / vLen

                        // Color from cell position hash
                        val cellId = voronoiCellId(mr.px, mr.py, mr.pz, cellScale, seed)
                        val colorT = (cellId % nColors).toFloat() / nColors
                        val ci = (colorT * (nColors - 1)).toInt().coerceIn(0, nColors - 1)
                        val cr = Color.red(colors[ci]) / 255f
                        val cg = Color.green(colors[ci]) / 255f
                        val cb = Color.blue(colors[ci]) / 255f

                        val shade = phongShade(normal[0], normal[1], normal[2], nvx, nvy, nvz, ld[0], ld[1], ld[2], 0.1f, 0.55f, specAmt, shininess)
                        val ao = ambientOcclusion(mr.px, mr.py, mr.pz, normal[0], normal[1], normal[2], sceneSDF, 4)

                        // Rim glow
                        val NdotV = maxOf(0f, normal[0] * nvx + normal[1] * nvy + normal[2] * nvz)
                        val fresnel = (1f - NdotV).pow(3) * rimGlow
                        val rimC = colors[(ci + nColors / 2) % nColors]

                        val r = cr * shade * ao + Color.red(rimC) / 255f * fresnel
                        val g = cg * shade * ao + Color.green(rimC) / 255f * fresnel
                        val b = cb * shade * ao + Color.blue(rimC) / 255f * fresnel

                        toneMapACES(tm, r, g, b, exposure)
                        pixels[py * renderW + px] = Color.rgb(tm[0].toInt().coerceIn(0, 255), tm[1].toInt().coerceIn(0, 255), tm[2].toInt().coerceIn(0, 255))
                    } else {
                        skyGradient(sky, rd[1], 0.03f, 0.02f, 0.08f, 0.06f, 0.04f, 0.1f)
                        toneMapACES(tm, sky[0], sky[1], sky[2], exposure)
                        pixels[py * renderW + px] = Color.rgb(tm[0].toInt().coerceIn(0, 255), tm[1].toInt().coerceIn(0, 255), tm[2].toInt().coerceIn(0, 255))
                    }
                }
            }
        }

        renderToCanvas(canvas, bitmap, renderW, renderH, pixels)
    }

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float = 0.6f

    companion object {
        private fun hashCell(ix: Int, iy: Int, iz: Int, seed: Int): FloatArray {
            var h = ix * 374761393 + iy * 668265263 + iz * 1274126177 + seed * 1013904223
            h = ((h xor (h ushr 13)) * 1103515245)
            val x = ((h ushr 0) and 0xFF) / 255f
            h = ((h xor (h ushr 17)) * 1103515245)
            val y = ((h ushr 0) and 0xFF) / 255f
            h = ((h xor (h ushr 11)) * 1103515245)
            val z = ((h ushr 0) and 0xFF) / 255f
            return floatArrayOf(x, y, z)
        }

        fun voronoiCrystalSDF(px: Float, py: Float, pz: Float, cellScale: Float, sharpness: Float, seed: Int): Float {
            val sx = px / cellScale; val sy = py / cellScale; val sz = pz / cellScale
            val ix = floor(sx).toInt(); val iy = floor(sy).toInt(); val iz = floor(sz).toInt()
            val fx = sx - ix; val fy = sy - iy; val fz = sz - iz

            var d1 = Float.MAX_VALUE; var d2 = Float.MAX_VALUE

            for (dx in -1..1) for (dy in -1..1) for (dz in -1..1) {
                val offset = hashCell(ix + dx, iy + dy, iz + dz, seed)
                val vx = dx.toFloat() + offset[0] - fx
                val vy = dy.toFloat() + offset[1] - fy
                val vz = dz.toFloat() + offset[2] - fz
                val dist = sqrt(vx * vx + vy * vy + vz * vz)
                if (dist < d1) { d2 = d1; d1 = dist }
                else if (dist < d2) { d2 = dist }
            }

            // Crystal SDF: ridge distance (d2-d1) forms crystal edges, bounded by sphere
            val crystal = (d2 - d1) * sharpness - 0.1f
            val bound = vec3Length(px, py, pz) - 2f
            return maxOf(crystal, bound) * cellScale * 0.5f
        }

        fun voronoiCellId(px: Float, py: Float, pz: Float, cellScale: Float, seed: Int): Int {
            val sx = px / cellScale; val sy = py / cellScale; val sz = pz / cellScale
            val ix = floor(sx).toInt(); val iy = floor(sy).toInt(); val iz = floor(sz).toInt()
            val fx = sx - ix; val fy = sy - iy; val fz = sz - iz

            var minDist = Float.MAX_VALUE
            var bestIx = 0; var bestIy = 0; var bestIz = 0

            for (dx in -1..1) for (dy in -1..1) for (dz in -1..1) {
                val offset = hashCell(ix + dx, iy + dy, iz + dz, seed)
                val vx = dx.toFloat() + offset[0] - fx
                val vy = dy.toFloat() + offset[1] - fy
                val vz = dz.toFloat() + offset[2] - fz
                val dist = vx * vx + vy * vy + vz * vz
                if (dist < minDist) {
                    minDist = dist
                    bestIx = ix + dx; bestIy = iy + dy; bestIz = iz + dz
                }
            }
            return abs(bestIx * 374761393 + bestIy * 668265263 + bestIz * 1274126177 + seed)
        }
    }
}
