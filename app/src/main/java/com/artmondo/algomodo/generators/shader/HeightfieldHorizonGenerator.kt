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

class HeightfieldHorizonGenerator : Generator {

    override val id = "shader-heightfield-horizon"
    override val family = "shader"
    override val styleName = "Heightfield Horizon"
    override val definition =
        "Terrain rendering with FBM noise heightfield, biome presets, and atmospheric fog."
    override val algorithmNotes =
        "Ray marches along terrain by stepping forward and checking if ray goes below terrain height (FBM noise). " +
        "Binary search refinement at intersection. Biome-specific noise profiles and coloring. Exponential fog + sky gradient."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema = listOf(
        Parameter.NumberParam("Camera Height", "cameraHeight", ParamGroup.COMPOSITION,
            "Camera elevation above terrain", 1f, 10f, 0.5f, 3f),
        Parameter.NumberParam("FOV", "fov", ParamGroup.COMPOSITION,
            "Field of view in degrees", 20f, 120f, 5f, 70f),
        Parameter.NumberParam("Camera Angle", "cameraAngle", ParamGroup.COMPOSITION,
            "Horizontal look direction", 0f, 360f, 5f, 0f),
        Parameter.NumberParam("Light Angle", "lightAngle", ParamGroup.TEXTURE,
            "Sun horizontal direction", 0f, 360f, 5f, 60f),
        Parameter.NumberParam("Light Height", "lightHeight", ParamGroup.TEXTURE,
            "Sun elevation", 0.1f, 2f, 0.1f, 0.6f),
        Parameter.NumberParam("Exposure", "exposure", ParamGroup.TEXTURE,
            "Tone mapping exposure", 0.5f, 3f, 0.1f, 1.3f),
        Parameter.NumberParam("Speed", "speed", ParamGroup.FLOW_MOTION,
            "Animation speed (camera drift)", 0.1f, 2f, 0.1f, 0.3f),
        Parameter.SelectParam("Biome", "biome", ParamGroup.COMPOSITION,
            "Terrain biome type",
            listOf("Mountains", "Dunes", "AlienPlateaus", "Archipelago"), "Mountains"),
        Parameter.NumberParam("Terrain Scale", "terrainScale", ParamGroup.GEOMETRY,
            "Horizontal terrain scale", 0.5f, 4f, 0.25f, 1.5f),
        Parameter.NumberParam("Terrain Height", "terrainHeight", ParamGroup.GEOMETRY,
            "Vertical terrain scale", 0.5f, 4f, 0.25f, 1.5f),
        Parameter.NumberParam("Fog", "fog", ParamGroup.TEXTURE,
            "Atmospheric fog density", 0f, 0.1f, 0.005f, 0.03f)
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "cameraHeight" to 3f, "fov" to 70f, "cameraAngle" to 0f,
        "lightAngle" to 60f, "lightHeight" to 0.6f, "exposure" to 1.3f, "speed" to 0.3f,
        "biome" to "Mountains", "terrainScale" to 1.5f, "terrainHeight" to 1.5f, "fog" to 0.03f
    )

    override fun renderCanvas(
        canvas: Canvas, bitmap: Bitmap, params: Map<String, Any>,
        seed: Int, palette: Palette, quality: Quality, time: Float
    ) {
        val w = bitmap.width; val h = bitmap.height
        val cfg = getQualityConfig(quality, "medium", time > 0f)
        val renderW = (w * cfg.scale).toInt().coerceAtLeast(60)
        val renderH = (h * cfg.scale).toInt().coerceAtLeast(60)

        val camH = extractFloat(params, "cameraHeight", 3f)
        val fov = extractFloat(params, "fov", 70f)
        val camAngle = extractFloat(params, "cameraAngle", 0f)
        val lightAngle = extractFloat(params, "lightAngle", 60f)
        val lightHeight = extractFloat(params, "lightHeight", 0.6f)
        val exposure = extractFloat(params, "exposure", 1.3f)
        val spd = extractFloat(params, "speed", 0.3f)
        val biome = extractString(params, "biome", "Mountains")
        val terrainScale = extractFloat(params, "terrainScale", 1.5f)
        val terrainH = extractFloat(params, "terrainHeight", 1.5f)
        val fogDensity = extractFloat(params, "fog", 0.03f)
        val t = time * spd

        val noise = Noise3D(seed)

        // Camera drifts forward
        val driftX = sin(camAngle * PI.toFloat() / 180f) * t * 5f
        val driftZ = cos(camAngle * PI.toFloat() / 180f) * t * 5f
        val roX = driftX; val roY = camH; val roZ = driftZ

        val lookAngle = camAngle * PI.toFloat() / 180f
        val tgtX = roX + sin(lookAngle) * 10f
        val tgtZ = roZ + cos(lookAngle) * 10f
        val cam = buildCamera(roX, roY, roZ, tgtX, roY - 1f, tgtZ, fov, renderW.toFloat() / renderH)
        val ld = FloatArray(3)
        lightDirFromParams(lightAngle, lightHeight, ld)

        val colors = palette.colorInts()
        val nColors = colors.size

        // Terrain height function
        val terrainFn: (Float, Float) -> Float = { x, z ->
            val sx = x / terrainScale; val sz = z / terrainScale
            when (biome) {
                "Dunes" -> {
                    val n = noise.fbm3D(sx * 0.5f, 0f, sz * 0.5f, 4) * 0.5f + 0.5f
                    val ridges = abs(sin(sx * 2f + n * 3f)) * 0.5f
                    (n * 0.6f + ridges * 0.4f) * terrainH
                }
                "AlienPlateaus" -> {
                    val n = noise.fbm3D(sx * 0.8f, 0f, sz * 0.8f, 3)
                    val plateau = (n * 3f).coerceIn(-1f, 1f) * 0.5f + 0.5f
                    plateau * terrainH
                }
                "Archipelago" -> {
                    val n = noise.fbm3D(sx * 0.6f, 0f, sz * 0.6f, 5)
                    val height = (n - 0.1f).coerceAtLeast(0f) * terrainH * 2f
                    height - 0.3f
                }
                else -> { // Mountains
                    val n = noise.fbm3D(sx * 0.7f, 0f, sz * 0.7f, 6, 2.1f, 0.48f)
                    abs(n) * terrainH * 1.5f
                }
            }
        }

        // Sky colors from palette
        val skyTop = if (nColors > 1) colors[1] else Color.rgb(50, 80, 150)
        val skyBot = if (nColors > 2) colors[2] else Color.rgb(150, 120, 80)
        val fogColor = colors[0]

        val pixels = IntArray(renderW * renderH)
        val invW = 2f / renderW; val invH = 2f / renderH
        val maxDist = 80f
        val maxSteps = when (quality) { Quality.DRAFT -> 60; Quality.ULTRA -> 150; else -> 100 }

        renderMultithreaded(renderW, renderH, pixels) { y0, y1, rd, normal, tm, mr ->
            for (py in y0 until y1) {
                val v = 1f - py * invH
                for (px in 0 until renderW) {
                    val u = px * invW - 1f
                    getRayDir(rd, u, v, cam)

                    // Heightfield ray march
                    var hit = false
                    var marchT = 0.1f
                    var prevH = roY
                    var hitX = 0f; var hitY = 0f; var hitZ = 0f

                    for (step in 0 until maxSteps) {
                        val sx = roX + rd[0] * marchT
                        val sy = roY + rd[1] * marchT
                        val sz = roZ + rd[2] * marchT

                        val terrainY = terrainFn(sx, sz)
                        if (sy < terrainY) {
                            // Binary search refinement
                            var lo = marchT - (marchT * 0.1f).coerceAtLeast(0.1f)
                            var hi = marchT
                            for (r in 0 until 5) {
                                val mid = (lo + hi) * 0.5f
                                val my = roY + rd[1] * mid
                                val mh = terrainFn(roX + rd[0] * mid, roZ + rd[2] * mid)
                                if (my < mh) hi = mid else lo = mid
                            }
                            marchT = (lo + hi) * 0.5f
                            hitX = roX + rd[0] * marchT
                            hitY = roY + rd[1] * marchT
                            hitZ = roZ + rd[2] * marchT
                            hit = true
                            break
                        }

                        // Adaptive step size
                        val gap = sy - terrainY
                        marchT += maxOf(gap * 0.5f, 0.2f)
                        if (marchT > maxDist) break
                    }

                    if (hit) {
                        // Normal from terrain gradient
                        val eps = 0.1f
                        val hc = terrainFn(hitX, hitZ)
                        val hx = terrainFn(hitX + eps, hitZ)
                        val hz = terrainFn(hitX, hitZ + eps)
                        val nx = -(hx - hc) / eps
                        val nz = -(hz - hc) / eps
                        val nLen = sqrt(nx * nx + 1f + nz * nz)
                        normal[0] = nx / nLen; normal[1] = 1f / nLen; normal[2] = nz / nLen

                        // Color from height + slope
                        val slope = 1f - normal[1]
                        val heightNorm = (hitY / (terrainH * 2f)).coerceIn(0f, 1f)
                        val colorT = (heightNorm * 0.7f + slope * 0.3f).coerceIn(0f, 1f)
                        val ci = (colorT * (nColors - 1)).toInt().coerceIn(0, nColors - 1)
                        val cr = Color.red(colors[ci]) / 255f
                        val cg = Color.green(colors[ci]) / 255f
                        val cb = Color.blue(colors[ci]) / 255f

                        val shade = phongShade(normal[0], normal[1], normal[2], -rd[0], -rd[1], -rd[2], ld[0], ld[1], ld[2], 0.2f, 0.65f, 0.15f, 20f)

                        // Fog
                        val fogAmt = 1f - exp(-marchT * fogDensity)
                        val fogR = Color.red(fogColor) / 255f * 0.5f
                        val fogG = Color.green(fogColor) / 255f * 0.5f
                        val fogB = Color.blue(fogColor) / 255f * 0.5f

                        val rr = cr * shade * (1f - fogAmt) + fogR * fogAmt
                        val gg = cg * shade * (1f - fogAmt) + fogG * fogAmt
                        val bb = cb * shade * (1f - fogAmt) + fogB * fogAmt

                        toneMapACES(tm, rr, gg, bb, exposure)
                        pixels[py * renderW + px] = Color.rgb(tm[0].toInt().coerceIn(0, 255), tm[1].toInt().coerceIn(0, 255), tm[2].toInt().coerceIn(0, 255))
                    } else {
                        // Sky gradient
                        val skyT = (rd[1] * 0.5f + 0.5f).coerceIn(0f, 1f)
                        val sr = Color.red(skyBot) / 255f + (Color.red(skyTop) / 255f - Color.red(skyBot) / 255f) * skyT
                        val sg = Color.green(skyBot) / 255f + (Color.green(skyTop) / 255f - Color.green(skyBot) / 255f) * skyT
                        val sb = Color.blue(skyBot) / 255f + (Color.blue(skyTop) / 255f - Color.blue(skyBot) / 255f) * skyT
                        toneMapACES(tm, sr, sg, sb, exposure)
                        pixels[py * renderW + px] = Color.rgb(tm[0].toInt().coerceIn(0, 255), tm[1].toInt().coerceIn(0, 255), tm[2].toInt().coerceIn(0, 255))
                    }
                }
            }
        }

        renderToCanvas(canvas, bitmap, renderW, renderH, pixels)
    }

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float = 0.7f
}
