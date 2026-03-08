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
import kotlin.math.cos
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
        val depth = (params["tiltAmount"] as? Number)?.toFloat() ?: 0.65f
        val lightAngleDeg = (params["lightAngle"] as? Number)?.toFloat() ?: 225f

        val rng = SeededRNG(seed)
        val noise = SimplexNoise(seed)

        val px = FloatArray(numPoints)
        val py = FloatArray(numPoints)
        for (i in 0 until numPoints) {
            px[i] = rng.random() * w
            py[i] = rng.random() * h
        }

        // Animate points
        if (time > 0f) {
            for (i in 0 until numPoints) {
                px[i] += noise.noise2D(i * 0.35f + 20f, time * 0.12f) * w * 0.03f
                py[i] += noise.noise2D(i * 0.35f + 120f, time * 0.12f) * h * 0.03f
                px[i] = px[i].coerceIn(0f, w.toFloat() - 1f)
                py[i] = py[i].coerceIn(0f, h.toFloat() - 1f)
            }
        }

        // Animate light angle
        val lightAngle = Math.toRadians((lightAngleDeg + time * 20f).toDouble()).toFloat()
        val lightX = cos(lightAngle)
        val lightY = sin(lightAngle)

        // Compute average cell radius for normalization
        val avgRadius = sqrt((w.toFloat() * h.toFloat()) / numPoints) * 0.5f

        val pixels = IntArray(w * h)
        val colors = palette.colorInts()

        val step = when (quality) {
            Quality.DRAFT -> 2
            Quality.BALANCED -> 1
            Quality.ULTRA -> 1
        }

        for (row in 0 until h step step) {
            for (col in 0 until w step step) {
                // Simple brute-force - always correct for small point counts
                var bestDist = Float.MAX_VALUE
                var bestIdx = 0
                for (i in 0 until numPoints) {
                    val dx = col - px[i]
                    val dy = row - py[i]
                    val d = dx * dx + dy * dy
                    if (d < bestDist) {
                        bestDist = d
                        bestIdx = i
                    }
                }

                // Compute normal from pixel to cell centre
                val toCentreX = px[bestIdx] - col
                val toCentreY = py[bestIdx] - row
                val dist = sqrt(toCentreX * toCentreX + toCentreY * toCentreY)

                // Normalized direction as surface normal (pointing inward from edges)
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

                // Dot product with light direction
                val dotProduct = (nx * lightX + ny * lightY) * normalStrength
                val shading = (0.5f + dotProduct * 0.5f).coerceIn(0.1f, 1.0f)

                val baseColor = colors[bestIdx % colors.size]
                val r = (Color.red(baseColor) * shading).toInt().coerceIn(0, 255)
                val g = (Color.green(baseColor) * shading).toInt().coerceIn(0, 255)
                val b = (Color.blue(baseColor) * shading).toInt().coerceIn(0, 255)
                val color = Color.rgb(r, g, b)

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
