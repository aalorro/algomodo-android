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
import kotlin.math.max
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

        // Read ALL parameters from the params map
        val numPoints = (params["cellCount"] as? Number)?.toInt() ?: 40
        val relaxationSteps = (params["relaxationSteps"] as? Number)?.toInt() ?: 3
        val depth = (params["tiltAmount"] as? Number)?.toFloat() ?: 0.65f
        val lightAngleDeg = (params["lightAngle"] as? Number)?.toFloat() ?: 225f
        val lightElevationDeg = (params["lightElevation"] as? Number)?.toFloat() ?: 50f
        val ambient = (params["ambient"] as? Number)?.toFloat() ?: 0.15f
        val specular = (params["specular"] as? Number)?.toFloat() ?: 0.45f
        val shininess = (params["shininess"] as? Number)?.toFloat() ?: 12f
        val borderWidth = (params["borderWidth"] as? Number)?.toFloat() ?: 1f
        val colorMode = (params["colorMode"] as? String) ?: "By Index"
        val distanceMetric = (params["distanceMetric"] as? String) ?: "Euclidean"
        val animSpeed = (params["animSpeed"] as? Number)?.toFloat() ?: 0.5f
        val animAmp = (params["animAmp"] as? Number)?.toFloat() ?: 0f

        val rng = SeededRNG(seed)
        val noise = SimplexNoise(seed)

        val px = FloatArray(numPoints)
        val py = FloatArray(numPoints)
        for (i in 0 until numPoints) {
            px[i] = rng.random() * w
            py[i] = rng.random() * h
        }

        // --- Lloyd's relaxation ---
        if (relaxationSteps > 0) {
            val relaxSampleStep = when (quality) {
                Quality.DRAFT -> 4
                Quality.BALANCED -> 3
                Quality.ULTRA -> 2
            }
            for (step in 0 until relaxationSteps) {
                val sumX = FloatArray(numPoints)
                val sumY = FloatArray(numPoints)
                val count = IntArray(numPoints)

                for (sy in 0 until h step relaxSampleStep) {
                    for (sx in 0 until w step relaxSampleStep) {
                        var bestDist = Float.MAX_VALUE
                        var bestIdx = 0
                        val xf = sx.toFloat()
                        val yf = sy.toFloat()
                        for (i in 0 until numPoints) {
                            val ddx = xf - px[i]
                            val ddy = yf - py[i]
                            val d = ddx * ddx + ddy * ddy
                            if (d < bestDist) {
                                bestDist = d
                                bestIdx = i
                            }
                        }
                        sumX[bestIdx] += xf
                        sumY[bestIdx] += yf
                        count[bestIdx]++
                    }
                }

                for (i in 0 until numPoints) {
                    if (count[i] > 0) {
                        px[i] = sumX[i] / count[i]
                        py[i] = sumY[i] / count[i]
                    }
                }
            }
        }

        // --- Animate points using animSpeed and animAmp ---
        if (time > 0f && animAmp > 0f) {
            for (i in 0 until numPoints) {
                px[i] += noise.noise2D(i * 0.35f + 20f, time * 0.12f * animSpeed) * w * 0.03f * animAmp
                py[i] += noise.noise2D(i * 0.35f + 120f, time * 0.12f * animSpeed) * h * 0.03f * animAmp
                px[i] = px[i].coerceIn(0f, w.toFloat() - 1f)
                py[i] = py[i].coerceIn(0f, h.toFloat() - 1f)
            }
        }

        // --- Animate light angle using animSpeed ---
        val lightAngle = Math.toRadians((lightAngleDeg + time * 20f * animSpeed).toDouble()).toFloat()

        // --- 3D light direction from lightAngle and lightElevation ---
        val elevationRad = Math.toRadians(lightElevationDeg.toDouble()).toFloat()
        val cosElev = cos(elevationRad)
        val sinElev = sin(elevationRad)
        val lightX = cos(lightAngle) * cosElev
        val lightY = sin(lightAngle) * cosElev
        val lightZ = sinElev

        // Compute average cell radius for normalization
        val avgRadius = sqrt((w.toFloat() * h.toFloat()) / numPoints) * 0.5f

        // Border threshold: use squared metric distance for Euclidean, raw for others
        val showBorders = borderWidth > 0f

        val pixels = IntArray(w * h)
        val colors = palette.colorInts()

        val step = when (quality) {
            Quality.DRAFT -> 2
            Quality.BALANCED -> 1
            Quality.ULTRA -> 1
        }

        for (row in 0 until h step step) {
            for (col in 0 until w step step) {
                // --- Find nearest cell using the chosen distance metric ---
                var bestDist = Float.MAX_VALUE
                var secondDist = Float.MAX_VALUE
                var bestIdx = 0
                val xf = col.toFloat()
                val yf = row.toFloat()
                for (i in 0 until numPoints) {
                    val ddx = xf - px[i]
                    val ddy = yf - py[i]
                    val d = when (distanceMetric) {
                        "Manhattan" -> abs(ddx) + abs(ddy)
                        "Chebyshev" -> max(abs(ddx), abs(ddy))
                        else -> ddx * ddx + ddy * ddy // Euclidean (squared)
                    }
                    if (d < bestDist) {
                        secondDist = bestDist
                        bestDist = d
                        bestIdx = i
                    } else if (d < secondDist) {
                        secondDist = d
                    }
                }

                // --- Border detection ---
                if (showBorders && (secondDist - bestDist) < borderWidth * 2f) {
                    val color = Color.BLACK
                    if (step == 1) {
                        pixels[row * w + col] = color
                    } else {
                        for (bdy in 0 until step) {
                            for (bdx in 0 until step) {
                                val fx = col + bdx
                                val fy = row + bdy
                                if (fx < w && fy < h) pixels[fy * w + fx] = color
                            }
                        }
                    }
                    continue
                }

                // --- Compute surface normal from pixel to cell centre ---
                val toCentreX = px[bestIdx] - col
                val toCentreY = py[bestIdx] - row
                val dist = sqrt(toCentreX * toCentreX + toCentreY * toCentreY)

                val nx: Float
                val ny: Float
                if (dist > 0.001f) {
                    nx = toCentreX / dist
                    ny = toCentreY / dist
                } else {
                    nx = 0f
                    ny = 0f
                }

                // Edge falloff: pixels near edges have stronger normals
                val edgeFactor = (1f - (dist / avgRadius).coerceIn(0f, 1f))
                val normalStrength = edgeFactor * depth

                // Surface normal in 3D: the XY components are the tilt, Z points "up"
                val snx = nx * normalStrength
                val sny = ny * normalStrength
                val snz = sqrt((1f - normalStrength * normalStrength).coerceAtLeast(0f))

                // --- Diffuse lighting (dot product with 3D light) ---
                val diffuseDot = (snx * lightX + sny * lightY + snz * lightZ).coerceIn(0f, 1f)

                // --- Specular lighting (Blinn-Phong: half-vector between light and view) ---
                // View direction is straight down: (0, 0, 1)
                val halfLen = sqrt(lightX * lightX + lightY * lightY + (lightZ + 1f) * (lightZ + 1f))
                val hx: Float
                val hy: Float
                val hz: Float
                if (halfLen > 0.001f) {
                    hx = lightX / halfLen
                    hy = lightY / halfLen
                    hz = (lightZ + 1f) / halfLen
                } else {
                    hx = 0f
                    hy = 0f
                    hz = 1f
                }
                val specDot = (snx * hx + sny * hy + snz * hz).coerceIn(0f, 1f)
                val specTerm = specular * specDot.pow(shininess)

                // --- Total shading: ambient + diffuse + specular ---
                val shading = (ambient + (1f - ambient) * diffuseDot).coerceIn(0f, 1f)

                // --- Color mode selection ---
                val baseColor = when (colorMode) {
                    "By Position" -> {
                        // Map cell centroid position to palette via normalized position
                        val t = ((px[bestIdx] / w + py[bestIdx] / h) * 0.5f).coerceIn(0f, 1f)
                        palette.lerpColor(t)
                    }
                    "By Normal-Z" -> {
                        // Map the Z component of the surface normal to palette
                        // snz ranges from ~0 (steep edge) to 1 (flat centre)
                        palette.lerpColor(snz)
                    }
                    else -> {
                        // "By Index" — original behaviour
                        colors[bestIdx % colors.size]
                    }
                }

                // Apply diffuse shading and add specular highlight
                val br = (Color.red(baseColor) * shading + 255f * specTerm).toInt().coerceIn(0, 255)
                val bg = (Color.green(baseColor) * shading + 255f * specTerm).toInt().coerceIn(0, 255)
                val bb = (Color.blue(baseColor) * shading + 255f * specTerm).toInt().coerceIn(0, 255)
                val color = Color.rgb(br, bg, bb)

                if (step == 1) {
                    pixels[row * w + col] = color
                } else {
                    for (dy in 0 until step) {
                        for (dx in 0 until step) {
                            val fx = col + dx
                            val fy = row + dy
                            if (fx < w && fy < h) pixels[fy * w + fx] = color
                        }
                    }
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
