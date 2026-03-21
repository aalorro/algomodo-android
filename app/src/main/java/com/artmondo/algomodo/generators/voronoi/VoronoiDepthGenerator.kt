package com.artmondo.algomodo.generators.voronoi

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import com.artmondo.algomodo.core.rng.SeededRNG
import com.artmondo.algomodo.core.rng.SimplexNoise
import com.artmondo.algomodo.data.palettes.Palette
import com.artmondo.algomodo.generators.Generator
import com.artmondo.algomodo.generators.ParamGroup
import com.artmondo.algomodo.generators.Parameter
import com.artmondo.algomodo.generators.Quality
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 3D-styled Voronoi depth generator.
 *
 * Adds a faux-3D shading effect to Voronoi cells. Each cell is treated as a
 * raised plateau; the direction from the pixel to the cell centre is used as
 * a surface normal, and a directional light produces highlight/shadow shading.
 */
class VoronoiDepthGenerator : Generator {

    override val id = "voronoi-depth"
    override val family = "voronoi"
    override val styleName = "Voronoi Depth"
    override val definition =
        "3D-styled Voronoi cells with faux lighting and depth shading, making each cell appear as a raised plateau."
    override val algorithmNotes =
        "Each pixel is assigned to the nearest Voronoi seed. The vector from pixel to cell centre is treated " +
        "as a surface normal. A directional light source at the given angle computes a dot-product shading term " +
        "that modulates the cell's base palette colour. The depth parameter scales the normal magnitude, " +
        "controlling the apparent extrusion height. Animation rotates the light angle over time."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema = listOf(
        Parameter.NumberParam("Cell Count", "cellCount", ParamGroup.COMPOSITION, "", 5f, 150f, 5f, 40f),
        Parameter.NumberParam("Relaxation", "relaxationSteps", ParamGroup.GEOMETRY, "Lloyd relaxation passes for more even cell distribution", 0f, 8f, 1f, 3f),
        Parameter.NumberParam("Tilt Amount", "tiltAmount", ParamGroup.GEOMETRY, "How far cell normals deviate from vertical (0 = flat, 1 = maximum tilt)", 0f, 1f, 0.05f, 0.65f),
        Parameter.NumberParam("Light Angle", "lightAngle", ParamGroup.GEOMETRY, "Horizontal direction of the light source in degrees (0=right, 90=down, 180=left, 270=up)", 0f, 360f, 5f, 225f),
        Parameter.NumberParam("Light Elevation", "lightElevation", ParamGroup.GEOMETRY, "Vertical elevation of the light above the horizon in degrees", 5f, 85f, 5f, 50f),
        Parameter.NumberParam("Ambient", "ambient", ParamGroup.COLOR, "Minimum brightness in shadowed areas", 0f, 0.6f, 0.05f, 0.15f),
        Parameter.NumberParam("Specular", "specular", ParamGroup.COLOR, "Brightness of specular highlight on facing facets", 0f, 1f, 0.05f, 0.45f),
        Parameter.NumberParam("Shininess", "shininess", ParamGroup.TEXTURE, "Specular highlight sharpness — higher = smaller, harder glint", 2f, 64f, 2f, 12f),
        Parameter.NumberParam("Border Width", "borderWidth", ParamGroup.GEOMETRY, "", 0f, 4f, 0.5f, 1f),
        Parameter.SelectParam("Color Mode", "colorMode", ParamGroup.COLOR, "By Normal-Z: palette maps to how steeply each cell faces the viewer", listOf("By Index", "By Position", "By Normal-Z"), "By Index"),
        Parameter.SelectParam("Distance Metric", "distanceMetric", ParamGroup.GEOMETRY, "", listOf("Euclidean", "Manhattan", "Chebyshev"), "Euclidean"),
        Parameter.NumberParam("Anim Speed", "animSpeed", ParamGroup.FLOW_MOTION, "Controls both light rotation speed and site drift speed", 0f, 2f, 0.05f, 0.5f),
        Parameter.NumberParam("Site Drift", "animAmp", ParamGroup.FLOW_MOTION, "0 = only light rotates; >0 = cells also drift", 0f, 1f, 0.05f, 0f)
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "cellCount" to 40f,
        "relaxationSteps" to 3f,
        "tiltAmount" to 0.65f,
        "lightAngle" to 225f,
        "lightElevation" to 50f,
        "ambient" to 0.15f,
        "specular" to 0.45f,
        "shininess" to 12f,
        "borderWidth" to 1f,
        "colorMode" to "By Index",
        "distanceMetric" to "Euclidean",
        "animSpeed" to 0.5f,
        "animAmp" to 0f
    )

    override fun renderCanvas(
        canvas: Canvas,
        bitmap: Bitmap,
        params: Map<String, Any>,
        seed: Int,
        palette: Palette,
        quality: Quality,
        time: Float
    ) {
        val w = bitmap.width
        val h = bitmap.height
        val numPoints = (params["cellCount"] as? Number)?.toInt() ?: 40
        if (numPoints <= 0) { canvas.drawColor(Color.BLACK); return }
        val relaxationSteps = (params["relaxationSteps"] as? Number)?.toInt() ?: 3
        val depth = (params["tiltAmount"] as? Number)?.toFloat() ?: 0.65f
        val lightAngleDeg = (params["lightAngle"] as? Number)?.toFloat() ?: 225f
        val lightElevationDeg = (params["lightElevation"] as? Number)?.toFloat() ?: 50f
        val ambient = (params["ambient"] as? Number)?.toFloat() ?: 0.15f
        val specular = (params["specular"] as? Number)?.toFloat() ?: 0.45f
        val shininess = (params["shininess"] as? Number)?.toFloat() ?: 12f
        val borderWidth = (params["borderWidth"] as? Number)?.toFloat() ?: 1f
        val showBorders = borderWidth > 0f
        val colorMode = (params["colorMode"] as? String) ?: "By Index"
        val distanceMetric = (params["distanceMetric"] as? String) ?: "Euclidean"
        val animSpeed = (params["animSpeed"] as? Number)?.toFloat() ?: 0.5f
        val animAmp = (params["animAmp"] as? Number)?.toFloat() ?: 0f

        val metricId = when (distanceMetric.lowercase()) {
            "manhattan" -> 1; "chebyshev" -> 2; else -> 0
        }
        val isEuclidean = metricId == 0
        val colorModeId = when (colorMode) { "By Position" -> 1; "By Normal-Z" -> 2; else -> 0 }

        val rng = SeededRNG(seed)
        val noise = SimplexNoise(seed)

        val px = FloatArray(numPoints)
        val py = FloatArray(numPoints)
        for (i in 0 until numPoints) {
            px[i] = rng.random() * w
            py[i] = rng.random() * h
        }

        // Lloyd relaxation
        if (relaxationSteps > 0) {
            val relaxSampleStep = when (quality) { Quality.DRAFT -> 4; Quality.BALANCED -> 3; Quality.ULTRA -> 2 }
            for (step in 0 until relaxationSteps) {
                val sumX = FloatArray(numPoints)
                val sumY = FloatArray(numPoints)
                val count = IntArray(numPoints)
                for (sy in 0 until h step relaxSampleStep) {
                    val yf = sy.toFloat()
                    for (sx in 0 until w step relaxSampleStep) {
                        val xf = sx.toFloat()
                        var bd = Float.MAX_VALUE; var bi = 0
                        for (i in 0 until numPoints) {
                            val ddx = xf - px[i]; val ddy = yf - py[i]
                            val d = if (isEuclidean) ddx * ddx + ddy * ddy
                                    else if (metricId == 1) abs(ddx) + abs(ddy)
                                    else maxOf(abs(ddx), abs(ddy))
                            if (d < bd) { bd = d; bi = i }
                        }
                        sumX[bi] += xf; sumY[bi] += yf; count[bi]++
                    }
                }
                for (i in 0 until numPoints) {
                    if (count[i] > 0) { px[i] = sumX[i] / count[i]; py[i] = sumY[i] / count[i] }
                }
            }
        }

        // Animate points
        if (time > 0f && animAmp > 0f) {
            val wf = w.toFloat(); val hf = h.toFloat()
            for (i in 0 until numPoints) {
                px[i] = (px[i] + noise.noise2D(i * 0.35f + 20f, time * 0.12f * animSpeed) * wf * 0.03f * animAmp).coerceIn(0f, wf - 1f)
                py[i] = (py[i] + noise.noise2D(i * 0.35f + 120f, time * 0.12f * animSpeed) * hf * 0.03f * animAmp).coerceIn(0f, hf - 1f)
            }
        }

        // ── Spatial grid (3×3 search window) ──
        val gridSize = (maxOf(w, h).toFloat() / sqrt(numPoints.toFloat())).coerceAtLeast(8f)
        val invGridSize = 1f / gridSize
        val gridCols = (w * invGridSize).toInt() + 1
        val gridRows = (h * invGridSize).toInt() + 1
        val gcM1 = gridCols - 1; val grM1 = gridRows - 1
        val gridHeads = IntArray(gridCols * gridRows) { -1 }
        val gridNext = IntArray(numPoints) { -1 }
        for (i in 0 until numPoints) {
            val gx = minOf((px[i] * invGridSize).toInt(), gcM1)
            val gy = minOf((py[i] * invGridSize).toInt(), grM1)
            val cell = gy * gridCols + gx
            gridNext[i] = gridHeads[cell]; gridHeads[cell] = i
        }

        // Light direction (animated angle)
        val lightAngle = Math.toRadians((lightAngleDeg + time * 20f * animSpeed).toDouble()).toFloat()
        val elevationRad = Math.toRadians(lightElevationDeg.toDouble()).toFloat()
        val cosElev = cos(elevationRad)
        val sinElev = sin(elevationRad)
        val lightX = cos(lightAngle) * cosElev
        val lightY = sin(lightAngle) * cosElev
        val lightZ = sinElev

        // Pre-compute half-vector (constant per frame — was wastefully computed per pixel)
        val halfLen = sqrt(lightX * lightX + lightY * lightY + (lightZ + 1f) * (lightZ + 1f))
        val hx: Float; val hy: Float; val hz: Float
        if (halfLen > 0.001f) {
            hx = lightX / halfLen; hy = lightY / halfLen; hz = (lightZ + 1f) / halfLen
        } else {
            hx = 0f; hy = 0f; hz = 1f
        }

        // Specular LUT — replaces per-pixel pow(specDot, shininess)
        val specLut = FloatArray(256) { i -> specular * (i / 255f).pow(shininess) }

        val avgRadius = sqrt((w.toFloat() * h.toFloat()) / numPoints) * 0.5f
        val edgeThresh = borderWidth * 2f

        val colors = palette.colorInts()
        val colorsSize = colors.size
        val lut = if (colorModeId != 0) palette.buildLut(256) else null
        val pixels = IntArray(w * h)
        val step = when (quality) { Quality.DRAFT -> 2; else -> 1 }
        val oneMinusAmbient = 1f - ambient

        for (row in 0 until h step step) {
            val y = row.toFloat()
            val rowOff = row * w
            val gy = minOf((y * invGridSize).toInt(), grM1)
            val cyMin = maxOf(gy - 1, 0); val cyMax = minOf(gy + 1, grM1)
            for (col in 0 until w step step) {
                val x = col.toFloat()
                val gx = minOf((x * invGridSize).toInt(), gcM1)
                val cxMin = maxOf(gx - 1, 0); val cxMax = minOf(gx + 1, gcM1)
                var bestDist = Float.MAX_VALUE; var secondDist = Float.MAX_VALUE; var bestIdx = 0
                for (cy in cyMin..cyMax) {
                    val ro = cy * gridCols
                    for (cx in cxMin..cxMax) {
                        var idx = gridHeads[ro + cx]
                        while (idx >= 0) {
                            val dx = x - px[idx]; val dy = y - py[idx]
                            val d = if (isEuclidean) dx * dx + dy * dy
                                    else if (metricId == 1) abs(dx) + abs(dy)
                                    else maxOf(abs(dx), abs(dy))
                            if (d < bestDist) { secondDist = bestDist; bestDist = d; bestIdx = idx }
                            else if (d < secondDist) { secondDist = d }
                            idx = gridNext[idx]
                        }
                    }
                }

                // Border detection
                if (showBorders && (secondDist - bestDist) < edgeThresh) {
                    if (step == 1) {
                        pixels[rowOff + col] = Color.BLACK
                    } else {
                        val i0 = rowOff + col
                        pixels[i0] = Color.BLACK
                        if (col + 1 < w) pixels[i0 + 1] = Color.BLACK
                        if (row + 1 < h) { pixels[i0 + w] = Color.BLACK; if (col + 1 < w) pixels[i0 + w + 1] = Color.BLACK }
                    }
                    continue
                }

                // Surface normal from pixel to cell centre
                val toCentreX = px[bestIdx] - col
                val toCentreY = py[bestIdx] - row
                val dist = sqrt(toCentreX * toCentreX + toCentreY * toCentreY)

                val nx: Float; val ny: Float
                if (dist > 0.001f) { nx = toCentreX / dist; ny = toCentreY / dist }
                else { nx = 0f; ny = 0f }

                val edgeFactor = (1f - (dist / avgRadius).coerceIn(0f, 1f))
                val normalStrength = edgeFactor * depth
                val snx = nx * normalStrength
                val sny = ny * normalStrength
                val snz = sqrt((1f - normalStrength * normalStrength).coerceAtLeast(0f))

                // Diffuse
                val diffuseDot = (snx * lightX + sny * lightY + snz * lightZ).coerceIn(0f, 1f)

                // Specular via LUT
                val specDot = (snx * hx + sny * hy + snz * hz).coerceIn(0f, 1f)
                val specTerm = specLut[(specDot * 255f).toInt().coerceAtMost(255)]

                val shading = (ambient + oneMinusAmbient * diffuseDot).coerceIn(0f, 1f)

                // Base color
                val baseColor = when (colorModeId) {
                    1 -> { // By Position
                        val t = ((px[bestIdx] / w + py[bestIdx] / h) * 0.5f).coerceIn(0f, 1f)
                        lut!![(t * 255f).toInt().coerceIn(0, 255)]
                    }
                    2 -> lut!![(snz.coerceIn(0f, 1f) * 255f).toInt().coerceIn(0, 255)] // By Normal-Z
                    else -> colors[bestIdx % colorsSize]
                }

                val br = (Color.red(baseColor) * shading + 255f * specTerm).toInt().coerceIn(0, 255)
                val bg = (Color.green(baseColor) * shading + 255f * specTerm).toInt().coerceIn(0, 255)
                val bb = (Color.blue(baseColor) * shading + 255f * specTerm).toInt().coerceIn(0, 255)
                val color = Color.rgb(br, bg, bb)

                if (step == 1) {
                    pixels[rowOff + col] = color
                } else {
                    val i0 = rowOff + col
                    pixels[i0] = color
                    if (col + 1 < w) pixels[i0 + 1] = color
                    if (row + 1 < h) { pixels[i0 + w] = color; if (col + 1 < w) pixels[i0 + w + 1] = color }
                }
            }
        }

        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
        canvas.drawBitmap(bitmap, 0f, 0f, null)
    }

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float {
        val n = (params["cellCount"] as? Number)?.toFloat() ?: 40f
        return (n / 100f).coerceIn(0.2f, 1f)
    }
}
