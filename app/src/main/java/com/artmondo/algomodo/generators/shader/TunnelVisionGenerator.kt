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

class TunnelVisionGenerator : Generator {

    override val id = "shader-tunnel-vision"
    override val family = "shader"
    override val styleName = "Tunnel Vision"
    override val definition =
        "Flying through an SDF tube with procedural wall textures and sinuous path warping."
    override val algorithmNotes =
        "Camera flies along +z through a tube whose cross-section is a 2D distance function on p.xy. " +
        "Path warping: p.xy += sin(p.z * freq) * amplitude creates sinuous tunnels. Wall textures sampled via hit point."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema = listOf(
        Parameter.SelectParam("Cross Section", "crossSection", ParamGroup.GEOMETRY,
            "Tunnel cross-section shape",
            listOf("Circle", "Square", "Hexagon", "Star", "Gear", "Flower"), "Circle"),
        Parameter.SelectParam("Wall Texture", "wallTexture", ParamGroup.TEXTURE,
            "Procedural pattern on tunnel walls",
            listOf("stripes", "grid", "voronoi", "noise"), "grid"),
        Parameter.NumberParam("FOV", "fov", ParamGroup.COMPOSITION,
            "Field of view in degrees", 20f, 120f, 5f, 60f),
        Parameter.NumberParam("Light Angle", "lightAngle", ParamGroup.TEXTURE,
            "Horizontal light direction", 0f, 360f, 5f, 45f),
        Parameter.NumberParam("Light Height", "lightHeight", ParamGroup.TEXTURE,
            "Vertical light direction", 0.1f, 2f, 0.1f, 0.8f),
        Parameter.NumberParam("Exposure", "exposure", ParamGroup.TEXTURE,
            "Tone mapping exposure", 0.5f, 3f, 0.1f, 1.2f),
        Parameter.NumberParam("Speed", "speed", ParamGroup.FLOW_MOTION,
            "Animation speed", 0.1f, 2f, 0.1f, 0.5f),
        Parameter.NumberParam("Warp Amp", "warpAmplitude", ParamGroup.GEOMETRY,
            "How much the tunnel path curves", 0f, 2f, 0.1f, 0.8f),
        Parameter.NumberParam("Warp Freq", "warpFrequency", ParamGroup.GEOMETRY,
            "Spatial frequency of tunnel curves", 0.5f, 3f, 0.25f, 1f)
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "crossSection" to "Circle", "wallTexture" to "grid",
        "fov" to 60f, "lightAngle" to 45f, "lightHeight" to 0.8f,
        "exposure" to 1.2f, "speed" to 0.5f,
        "warpAmplitude" to 0.8f, "warpFrequency" to 1f
    )

    override fun renderCanvas(
        canvas: Canvas, bitmap: Bitmap, params: Map<String, Any>,
        seed: Int, palette: Palette, quality: Quality, time: Float
    ) {
        val w = bitmap.width; val h = bitmap.height
        val cfg = getQualityConfig(quality, "medium", time > 0f)
        val renderW = (w * cfg.scale).toInt().coerceAtLeast(60)
        val renderH = (h * cfg.scale).toInt().coerceAtLeast(60)

        val section = extractString(params, "crossSection", "Circle")
        val texType = extractString(params, "wallTexture", "grid")
        val fov = extractFloat(params, "fov", 60f)
        val lightAngle = extractFloat(params, "lightAngle", 45f)
        val lightHeight = extractFloat(params, "lightHeight", 0.8f)
        val exposure = extractFloat(params, "exposure", 1.2f)
        val spd = extractFloat(params, "speed", 0.5f)
        val warpAmp = extractFloat(params, "warpAmplitude", 0.8f)
        val warpFreq = extractFloat(params, "warpFrequency", 1f)
        val t = time * spd

        val rng = SeededRNG(seed)
        val f1 = rng.range(0.3f, 0.8f) * warpFreq
        val f2 = rng.range(0.3f, 0.8f) * warpFreq
        val tunnelR = 1.5f

        // Camera along z
        val camZ = t * 3f
        val pathX = sin(camZ * f1) * warpAmp
        val pathY = cos(camZ * f2) * warpAmp * 0.5f
        val lookZ = camZ + 2f
        val lookX = sin(lookZ * f1) * warpAmp
        val lookY = cos(lookZ * f2) * warpAmp * 0.5f

        val roX = pathX; val roY = pathY; val roZ = camZ
        val cam = buildCamera(roX, roY, roZ, lookX, lookY, lookZ, fov, renderW.toFloat() / renderH)
        val ld = FloatArray(3)
        lightDirFromParams(lightAngle, lightHeight, ld)

        val colors = palette.colorInts()
        val nColors = colors.size

        val noise = Noise3D(seed)

        val sceneSDF: SceneSDF = { px, py, pz ->
            val wpx = px - sin(pz * f1) * warpAmp
            val wpy = py - cos(pz * f2) * warpAmp * 0.5f
            -csEval(wpx, wpy, tunnelR, section)
        }

        val pixels = IntArray(renderW * renderH)
        val invW = 2f / renderW; val invH = 2f / renderH
        val fogFactor = 0.06f

        renderMultithreaded(renderW, renderH, pixels) { y0, y1, rd, normal, tm, mr ->
            for (py in y0 until y1) {
                val v = 1f - py * invH
                for (px in 0 until renderW) {
                    val u = px * invW - 1f
                    getRayDir(rd, u, v, cam)
                    rayMarch(mr, roX, roY, roZ, rd[0], rd[1], rd[2], sceneSDF, cfg.maxSteps, cfg.epsilon, cfg.maxDist)

                    if (mr.hit) {
                        calcNormal(normal, mr.px, mr.py, mr.pz, sceneSDF, cfg.epsilon * 2f)
                        val vx = roX - mr.px; val vy = roY - mr.py; val vz = roZ - mr.pz
                        val vLen = vec3Length(vx, vy, vz).let { if (it == 0f) 1f else it }
                        val shade = phongShade(normal[0], normal[1], normal[2], vx / vLen, vy / vLen, vz / vLen, ld[0], ld[1], ld[2], 0.2f, 0.6f, 0.3f, 25f)

                        val wt = wallTex(mr.px, mr.py, mr.pz, texType, noise)
                        val texBright = wt[0]; val texColor = wt[1]

                        val ci = (abs(texColor) * (nColors - 1)).toInt().coerceIn(0, nColors - 1)
                        val c1i = (ci + 1).coerceAtMost(nColors - 1)
                        val frac = abs(texColor) * (nColors - 1) - ci
                        val cr = (Color.red(colors[ci]) + (Color.red(colors[c1i]) - Color.red(colors[ci])) * frac) / 255f
                        val cg = (Color.green(colors[ci]) + (Color.green(colors[c1i]) - Color.green(colors[ci])) * frac) / 255f
                        val cb = (Color.blue(colors[ci]) + (Color.blue(colors[c1i]) - Color.blue(colors[ci])) * frac) / 255f

                        val fogAmt = 1f - exp(-mr.t * fogFactor)
                        val brightness = shade * texBright
                        val r = cr * brightness * (1f - fogAmt)
                        val g = cg * brightness * (1f - fogAmt)
                        val b = cb * brightness * (1f - fogAmt)

                        toneMapACES(tm, r, g, b, exposure)
                        pixels[py * renderW + px] = Color.rgb(tm[0].toInt().coerceIn(0, 255), tm[1].toInt().coerceIn(0, 255), tm[2].toInt().coerceIn(0, 255))
                    } else {
                        pixels[py * renderW + px] = Color.rgb(5, 5, 5)
                    }
                }
            }
        }

        renderToCanvas(canvas, bitmap, renderW, renderH, pixels)
    }

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float {
        val tex = extractString(params, "wallTexture", "grid")
        return if (tex == "voronoi") 0.65f else 0.5f
    }

    companion object {
        private fun csCircle(x: Float, y: Float, r: Float): Float = sqrt(x * x + y * y) - r

        private fun csSquare(x: Float, y: Float, r: Float): Float {
            return maxOf(abs(x), abs(y)) - r
        }

        private fun csHexagon(x: Float, y: Float, r: Float): Float {
            return maxOf(abs(x) * 0.866025f + abs(y) * 0.5f, abs(y)) - r
        }

        private fun csStar(x: Float, y: Float, r: Float): Float {
            val a = atan2(y, x)
            val wave = cos(a * 5f) * 0.3f + 0.7f
            return sqrt(x * x + y * y) - r * wave
        }

        private fun csGear(x: Float, y: Float, r: Float): Float {
            val a = atan2(y, x)
            val wave = if (cos(a * 8f) > 0f) 0f else 0.15f
            return sqrt(x * x + y * y) - r + wave
        }

        private fun csFlower(x: Float, y: Float, r: Float): Float {
            val a = atan2(y, x)
            val wave = cos(a * 6f) * 0.25f + 0.75f
            return sqrt(x * x + y * y) - r * wave
        }

        fun csEval(x: Float, y: Float, r: Float, section: String): Float {
            return when (section) {
                "Square" -> csSquare(x, y, r)
                "Hexagon" -> csHexagon(x, y, r)
                "Star" -> csStar(x, y, r)
                "Gear" -> csGear(x, y, r)
                "Flower" -> csFlower(x, y, r)
                else -> csCircle(x, y, r)
            }
        }

        fun wallTex(px: Float, py: Float, pz: Float, texType: String, noise: Noise3D): FloatArray {
            return when (texType) {
                "stripes" -> {
                    val s = sin(pz * 5f) * 0.5f + 0.5f
                    floatArrayOf(0.5f + s * 0.5f, (pz * 0.3f) % 1f)
                }
                "grid" -> {
                    val gx = if (abs(sin(px * 8f)) < 0.05f) 0.3f else 1f
                    val gy = if (abs(sin(py * 8f)) < 0.05f) 0.3f else 1f
                    val gz = if (abs(sin(pz * 8f)) < 0.05f) 0.3f else 1f
                    floatArrayOf(minOf(gx, gy, gz), ((px + pz) * 0.2f) % 1f)
                }
                "voronoi" -> {
                    val fx = floor(px * 3f); val fy = floor(py * 3f); val fz = floor(pz * 3f)
                    val dx = (px * 3f - fx) - 0.5f; val dy = (py * 3f - fy) - 0.5f; val dz = (pz * 3f - fz) - 0.5f
                    val d = sqrt(dx * dx + dy * dy + dz * dz)
                    floatArrayOf(0.5f + d * 0.8f, d)
                }
                else -> { // noise
                    val n = noise.fbm3D(px * 2f, py * 2f, pz * 2f, 3) * 0.5f + 0.5f
                    floatArrayOf(0.4f + n * 0.6f, n)
                }
            }
        }
    }
}
