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

class GrayScott3dGenerator : Generator {

    override val id = "shader-gray-scott-3d"
    override val family = "shader"
    override val styleName = "Gray-Scott 3D"
    override val definition =
        "3D reaction-diffusion patterns (Gray-Scott model) ray-marched as an isosurface."
    override val algorithmNotes =
        "3D grid of U/V concentrations following Gray-Scott equations. Runs N simulation steps from seeded initial state. " +
        "V concentration is ray-marched as isosurface (V > threshold = inside). " +
        "Presets control feed/kill rates producing spots, stripes, worms, mitosis patterns."
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
        Parameter.SelectParam("Preset", "preset", ParamGroup.COMPOSITION,
            "Reaction-diffusion preset",
            listOf("spots", "stripes", "worms", "mitosis", "coral"), "spots"),
        Parameter.NumberParam("Sim Steps", "simSteps", ParamGroup.GEOMETRY,
            "Number of simulation iterations", 20f, 200f, 10f, 80f),
        Parameter.NumberParam("Threshold", "threshold", ParamGroup.GEOMETRY,
            "Isosurface threshold for V concentration", 0.1f, 0.6f, 0.05f, 0.25f)
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "cameraDistance" to 5f, "fov" to 60f, "cameraAngle" to 0f, "cameraHeight" to 0.5f,
        "lightAngle" to 45f, "lightHeight" to 0.8f, "exposure" to 1.2f, "speed" to 0.5f,
        "preset" to "spots", "simSteps" to 80f, "threshold" to 0.25f
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
        val preset = extractString(params, "preset", "spots")
        val simSteps = extractFloat(params, "simSteps", 80f).toInt().coerceIn(20, 200)
        val threshold = extractFloat(params, "threshold", 0.25f)
        val t = time * spd

        // Grid size (small for mobile performance)
        val gridSize = when (quality) {
            Quality.DRAFT -> 24; Quality.ULTRA -> 40; else -> 32
        }
        val gs = gridSize
        val totalCells = gs * gs * gs

        // Gray-Scott parameters from preset
        val (feedRate, killRate) = when (preset) {
            "stripes" -> 0.035f to 0.065f
            "worms" -> 0.078f to 0.061f
            "mitosis" -> 0.028f to 0.062f
            "coral" -> 0.055f to 0.062f
            else -> 0.037f to 0.06f // spots
        }

        // Simulate Gray-Scott
        val v = simulateGrayScott(gs, feedRate, killRate, simSteps, seed, (t * 10f).toInt())

        // Camera
        val ro = FloatArray(3)
        cameraFromParams(camDist, camAngle + t * 10f, camHeight, ro)
        val cam = buildCamera(ro[0], ro[1], ro[2], 0f, 0f, 0f, fov, renderW.toFloat() / renderH)
        val ld = FloatArray(3)
        lightDirFromParams(lightAngle, lightHeight, ld)

        val colors = palette.colorInts()
        val nColors = colors.size
        val halfSize = gs * 0.5f
        val invGs = 1f / gs

        // SDF from grid: trilinear interpolation of V field
        val sceneSDF: SceneSDF = { px, py, pz ->
            // Map world to grid coords
            val gx = (px + 1.5f) * gs / 3f
            val gy = (py + 1.5f) * gs / 3f
            val gz = (pz + 1.5f) * gs / 3f

            if (gx < 0f || gy < 0f || gz < 0f || gx >= gs - 1f || gy >= gs - 1f || gz >= gs - 1f) {
                0.5f // Outside grid
            } else {
                val ix = gx.toInt().coerceIn(0, gs - 2)
                val iy = gy.toInt().coerceIn(0, gs - 2)
                val iz = gz.toInt().coerceIn(0, gs - 2)
                val fx = gx - ix; val fy = gy - iy; val fz = gz - iz

                // Trilinear interpolation
                val c000 = v[ix + iy * gs + iz * gs * gs]
                val c100 = v[(ix + 1) + iy * gs + iz * gs * gs]
                val c010 = v[ix + (iy + 1) * gs + iz * gs * gs]
                val c110 = v[(ix + 1) + (iy + 1) * gs + iz * gs * gs]
                val c001 = v[ix + iy * gs + (iz + 1) * gs * gs]
                val c101 = v[(ix + 1) + iy * gs + (iz + 1) * gs * gs]
                val c011 = v[ix + (iy + 1) * gs + (iz + 1) * gs * gs]
                val c111 = v[(ix + 1) + (iy + 1) * gs + (iz + 1) * gs * gs]

                val c00 = c000 + (c100 - c000) * fx
                val c01 = c001 + (c101 - c001) * fx
                val c10 = c010 + (c110 - c010) * fx
                val c11 = c011 + (c111 - c011) * fx
                val c0 = c00 + (c10 - c00) * fy
                val c1 = c01 + (c11 - c01) * fy
                val interp = c0 + (c1 - c0) * fz

                // SDF: negative inside (where V > threshold)
                (threshold - interp) * 3f / gs
            }
        }

        val pixels = IntArray(renderW * renderH)
        val invW = 2f / renderW; val invH = 2f / renderH

        renderMultithreaded(renderW, renderH, pixels) { y0, y1, rd, normal, tm, mr ->
            val sky = FloatArray(3)
            for (py in y0 until y1) {
                val vv = 1f - py * invH
                for (px in 0 until renderW) {
                    val u = px * invW - 1f
                    getRayDir(rd, u, vv, cam)
                    rayMarch(mr, ro[0], ro[1], ro[2], rd[0], rd[1], rd[2], sceneSDF, cfg.maxSteps, cfg.epsilon * 2f, cfg.maxDist)

                    if (mr.hit) {
                        calcNormal(normal, mr.px, mr.py, mr.pz, sceneSDF, cfg.epsilon * 4f)
                        val vx = ro[0] - mr.px; val vy = ro[1] - mr.py; val vz = ro[2] - mr.pz
                        val vLen = vec3Length(vx, vy, vz).let { if (it == 0f) 1f else it }

                        val colorT = (normal[0] * 0.3f + normal[1] * 0.3f + mr.px * 0.2f + 0.5f).coerceIn(0f, 1f)
                        val ci = (colorT * (nColors - 1)).toInt().coerceIn(0, nColors - 1)
                        val cr = Color.red(colors[ci]) / 255f
                        val cg = Color.green(colors[ci]) / 255f
                        val cb = Color.blue(colors[ci]) / 255f

                        val shade = phongShade(normal[0], normal[1], normal[2], vx / vLen, vy / vLen, vz / vLen, ld[0], ld[1], ld[2], 0.2f, 0.6f, 0.3f, 25f)
                        val ao = ambientOcclusion(mr.px, mr.py, mr.pz, normal[0], normal[1], normal[2], sceneSDF, 4)

                        toneMapACES(tm, cr * shade * ao, cg * shade * ao, cb * shade * ao, exposure)
                        pixels[py * renderW + px] = Color.rgb(tm[0].toInt().coerceIn(0, 255), tm[1].toInt().coerceIn(0, 255), tm[2].toInt().coerceIn(0, 255))
                    } else {
                        skyGradient(sky, rd[1], 0.04f, 0.04f, 0.08f, 0.08f, 0.06f, 0.1f)
                        toneMapACES(tm, sky[0], sky[1], sky[2], exposure)
                        pixels[py * renderW + px] = Color.rgb(tm[0].toInt().coerceIn(0, 255), tm[1].toInt().coerceIn(0, 255), tm[2].toInt().coerceIn(0, 255))
                    }
                }
            }
        }

        renderToCanvas(canvas, bitmap, renderW, renderH, pixels)
    }

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float = 0.9f

    companion object {
        fun simulateGrayScott(gs: Int, feedRate: Float, killRate: Float, steps: Int, seed: Int, timeOffset: Int): FloatArray {
            val total = gs * gs * gs
            val u = FloatArray(total) { 1f }
            val v = FloatArray(total) { 0f }
            val uNext = FloatArray(total)
            val vNext = FloatArray(total)

            val rng = SeededRNG(seed + timeOffset)
            // Seed initial V blobs
            val numBlobs = 3 + rng.integer(0, 3)
            for (b in 0 until numBlobs) {
                val cx = rng.integer(gs / 4, 3 * gs / 4)
                val cy = rng.integer(gs / 4, 3 * gs / 4)
                val cz = rng.integer(gs / 4, 3 * gs / 4)
                val r = rng.integer(2, gs / 6)
                for (dx in -r..r) for (dy in -r..r) for (dz in -r..r) {
                    if (dx * dx + dy * dy + dz * dz <= r * r) {
                        val ix = (cx + dx).coerceIn(0, gs - 1)
                        val iy = (cy + dy).coerceIn(0, gs - 1)
                        val iz = (cz + dz).coerceIn(0, gs - 1)
                        val idx = ix + iy * gs + iz * gs * gs
                        u[idx] = 0.5f; v[idx] = 0.25f
                    }
                }
            }

            val du = 0.2f; val dv = 0.1f
            val dt = 1f

            for (step in 0 until steps) {
                for (iz in 1 until gs - 1) {
                    for (iy in 1 until gs - 1) {
                        for (ix in 1 until gs - 1) {
                            val idx = ix + iy * gs + iz * gs * gs
                            val uVal = u[idx]; val vVal = v[idx]

                            // 3D Laplacian (6-neighbor)
                            val lapU = u[idx + 1] + u[idx - 1] + u[idx + gs] + u[idx - gs] + u[idx + gs * gs] + u[idx - gs * gs] - 6f * uVal
                            val lapV = v[idx + 1] + v[idx - 1] + v[idx + gs] + v[idx - gs] + v[idx + gs * gs] + v[idx - gs * gs] - 6f * vVal

                            val uvv = uVal * vVal * vVal
                            uNext[idx] = uVal + (du * lapU - uvv + feedRate * (1f - uVal)) * dt
                            vNext[idx] = vVal + (dv * lapV + uvv - (feedRate + killRate) * vVal) * dt
                        }
                    }
                }
                // Swap
                System.arraycopy(uNext, 0, u, 0, total)
                System.arraycopy(vNext, 0, v, 0, total)
            }

            return v
        }
    }
}
