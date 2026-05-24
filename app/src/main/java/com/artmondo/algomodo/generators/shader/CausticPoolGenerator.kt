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
            "Distance of camera from origin", 1f, 12f, 0.5f, 5f),
        Parameter.NumberParam("FOV", "fov", ParamGroup.COMPOSITION,
            "Field of view in degrees", 20f, 120f, 5f, 60f),
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
            "Pool depth below water surface", 0.5f, 4f, 0.25f, 1.5f)
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "cameraDistance" to 5f, "fov" to 60f, "cameraAngle" to 30f, "cameraHeight" to 2.5f,
        "lightAngle" to 45f, "lightHeight" to 1.5f, "exposure" to 1.4f, "speed" to 0.5f,
        "waveCount" to 4f, "waveFrequency" to 3f, "waveAmplitude" to 0.05f, "poolDepth" to 1.5f
    )

    override fun renderCanvas(
        canvas: Canvas, bitmap: Bitmap, params: Map<String, Any>,
        seed: Int, palette: Palette, quality: Quality, time: Float
    ) {
        val w = bitmap.width; val h = bitmap.height
        val cfg = getQualityConfig(quality, "medium", time > 0f)
        val renderW = (w * cfg.scale).toInt().coerceAtLeast(60)
        val renderH = (h * cfg.scale).toInt().coerceAtLeast(60)

        val camDist = extractFloat(params, "cameraDistance", 5f)
        val fov = extractFloat(params, "fov", 60f)
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
        val t = time * spd

        val rng = SeededRNG(seed)
        // Wave source positions
        val waveSrcX = FloatArray(waveCount)
        val waveSrcZ = FloatArray(waveCount)
        val wavePhase = FloatArray(waveCount)
        for (i in 0 until waveCount) {
            waveSrcX[i] = rng.range(-3f, 3f)
            waveSrcZ[i] = rng.range(-3f, 3f)
            wavePhase[i] = rng.randomAngle()
        }

        val ro = FloatArray(3)
        cameraFromParams(camDist, camAngle, camHeight, ro)
        val cam = buildCamera(ro[0], ro[1], ro[2], 0f, -0.5f, 0f, fov, renderW.toFloat() / renderH)
        val ld = FloatArray(3)
        lightDirFromParams(lightAngle, lightHeight, ld)

        val colors = palette.colorInts()
        val nColors = colors.size

        val waterIOR = 1.33f
        val floorY = -poolDepth

        val pixels = IntArray(renderW * renderH)
        val invW = 2f / renderW; val invH = 2f / renderH

        renderMultithreaded(renderW, renderH, pixels) { y0, y1, rd, normal, tm, mr ->
            val refr = FloatArray(3)
            for (py in y0 until y1) {
                val v = 1f - py * invH
                for (px in 0 until renderW) {
                    val u = px * invW - 1f
                    getRayDir(rd, u, v, cam)

                    // Intersect ray with water plane at y=0
                    val waterHit = if (abs(rd[1]) > 0.0001f) -ro[1] / rd[1] else -1f

                    var r = 0f; var g = 0f; var b = 0f

                    if (waterHit > 0f) {
                        val hitX = ro[0] + rd[0] * waterHit
                        val hitZ = ro[2] + rd[2] * waterHit

                        // Water surface normal from wave gradient
                        val eps = 0.01f
                        val hc = waterHeight(hitX, hitZ, t, waveCount, waveSrcX, waveSrcZ, wavePhase, waveFreq, waveAmp)
                        val hx = waterHeight(hitX + eps, hitZ, t, waveCount, waveSrcX, waveSrcZ, wavePhase, waveFreq, waveAmp)
                        val hz = waterHeight(hitX, hitZ + eps, t, waveCount, waveSrcX, waveSrcZ, wavePhase, waveFreq, waveAmp)
                        val dhdx = (hx - hc) / eps
                        val dhdz = (hz - hc) / eps
                        val nLen = sqrt(dhdx * dhdx + 1f + dhdz * dhdz)
                        normal[0] = -dhdx / nLen; normal[1] = 1f / nLen; normal[2] = -dhdz / nLen

                        // Refract through water
                        val eta = 1f / waterIOR
                        if (refractVec(refr, rd[0], rd[1], rd[2], normal[0], normal[1], normal[2], eta)) {
                            // Trace to floor
                            val waterY = hc
                            val toFloor = (floorY - waterY) / refr[1]
                            if (toFloor > 0f) {
                                val floorX = hitX + refr[0] * toFloor
                                val floorZ = hitZ + refr[2] * toFloor

                                // Caustic: sample neighboring rays for convergence
                                val caustic = computeCaustic(hitX, hitZ, t, floorY, waterIOR,
                                    waveCount, waveSrcX, waveSrcZ, wavePhase, waveFreq, waveAmp)

                                // Floor pattern: tile coloring
                                val tileX = ((floor(floorX * 2f).toInt() + 1000) % nColors)
                                val tileZ = ((floor(floorZ * 2f).toInt() + 1000) % nColors)
                                val ci = abs(tileX + tileZ) % nColors
                                val baseR = Color.red(colors[ci]) / 255f
                                val baseG = Color.green(colors[ci]) / 255f
                                val baseB = Color.blue(colors[ci]) / 255f

                                val brightness = 0.3f + caustic * 1.5f
                                val fogDist = abs(toFloor)
                                val fog = exp(-fogDist * 0.3f)

                                // Water tint
                                val tintCI = colors[0]
                                val tintR = Color.red(tintCI) / 255f * 0.3f
                                val tintG = Color.green(tintCI) / 255f * 0.3f + 0.1f
                                val tintB = Color.blue(tintCI) / 255f * 0.3f + 0.15f

                                r = (baseR * brightness * fog + tintR * (1f - fog))
                                g = (baseG * brightness * fog + tintG * (1f - fog))
                                b = (baseB * brightness * fog + tintB * (1f - fog))
                            }
                        }

                        // Fresnel surface reflection
                        val cosI = abs(rd[0] * normal[0] + rd[1] * normal[1] + rd[2] * normal[2])
                        val fres = fresnelSchlick(cosI, waterIOR) * 0.4f
                        // Sky reflection
                        r += fres * 0.3f; g += fres * 0.35f; b += fres * 0.4f
                    } else {
                        // Sky
                        val skyT = rd[1] * 0.5f + 0.5f
                        r = 0.1f + skyT * 0.2f
                        g = 0.12f + skyT * 0.3f
                        b = 0.2f + skyT * 0.5f
                    }

                    toneMapACES(tm, r, g, b, exposure)
                    pixels[py * renderW + px] = Color.rgb(tm[0].toInt().coerceIn(0, 255), tm[1].toInt().coerceIn(0, 255), tm[2].toInt().coerceIn(0, 255))
                }
            }
        }

        renderToCanvas(canvas, bitmap, renderW, renderH, pixels)
    }

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float {
        val waves = extractFloat(params, "waveCount", 4f).toInt()
        return (0.4f + waves * 0.05f).coerceIn(0.4f, 0.8f)
    }

    companion object {
        fun waterHeight(
            x: Float, z: Float, t: Float,
            waveCount: Int, srcX: FloatArray, srcZ: FloatArray,
            phase: FloatArray, freq: Float, amp: Float
        ): Float {
            var h = 0f
            for (i in 0 until waveCount) {
                val dx = x - srcX[i]; val dz = z - srcZ[i]
                val dist = sqrt(dx * dx + dz * dz)
                h += sin(dist * freq - t * 3f + phase[i]) * amp / (1f + dist * 0.3f)
            }
            return h
        }

        fun computeCaustic(
            x: Float, z: Float, t: Float, floorY: Float, ior: Float,
            waveCount: Int, srcX: FloatArray, srcZ: FloatArray,
            phase: FloatArray, freq: Float, amp: Float
        ): Float {
            // Approximate caustic intensity from curvature of refracted ray bundle
            val eps = 0.05f
            val h00 = waterHeight(x, z, t, waveCount, srcX, srcZ, phase, freq, amp)
            val h10 = waterHeight(x + eps, z, t, waveCount, srcX, srcZ, phase, freq, amp)
            val h01 = waterHeight(x, z + eps, t, waveCount, srcX, srcZ, phase, freq, amp)
            val h11 = waterHeight(x + eps, z + eps, t, waveCount, srcX, srcZ, phase, freq, amp)

            // Second derivatives approximate convergence
            val d2x = (h10 - 2f * h00 + waterHeight(x - eps, z, t, waveCount, srcX, srcZ, phase, freq, amp)) / (eps * eps)
            val d2z = (h01 - 2f * h00 + waterHeight(x, z - eps, t, waveCount, srcX, srcZ, phase, freq, amp)) / (eps * eps)

            // Laplacian gives caustic focusing
            val laplacian = abs(d2x + d2z)
            return (laplacian * 2f).coerceIn(0f, 3f)
        }
    }
}
