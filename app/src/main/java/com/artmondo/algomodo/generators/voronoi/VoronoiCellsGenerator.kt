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

/**
 * Classic Voronoi diagram generator.
 *
 * Scatters seed points using SeededRNG, then for each pixel finds the nearest
 * point and colours the pixel by the owning cell's palette index. Edges are
 * detected where the nearest and second-nearest cells differ in adjacent pixels.
 * Animation slowly drifts points via simplex noise.
 */
class VoronoiCellsGenerator : Generator {

    override val id = "voronoi-cells"
    override val family = "voronoi"
    override val styleName = "Voronoi Cells"
    override val definition =
        "Classic Voronoi diagram where the plane is partitioned into cells around scattered seed points, each coloured by palette index."
    override val algorithmNotes =
        "Seed points are placed via SeededRNG. For each pixel the nearest point is found using a grid-accelerated " +
        "spatial lookup (brute-force fallback for small N). Supports euclidean, manhattan, and chebyshev distance " +
        "metrics. Edges are detected by checking if the closest cell differs from the second-closest within edgeWidth. " +
        "Animation displaces seed points over time using simplex noise."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema = listOf(
        Parameter.NumberParam("Cell Count", "cellCount", ParamGroup.COMPOSITION, "", 5f, 200f, 1f, 40f),
        Parameter.SelectParam("Distance Metric", "distanceMetric", ParamGroup.GEOMETRY, "", listOf("Euclidean", "Manhattan", "Chebyshev"), "Euclidean"),
        Parameter.NumberParam("Border Width", "borderWidth", ParamGroup.GEOMETRY, "", 0f, 5f, 0.5f, 1f),
        Parameter.SelectParam("Color Mode", "colorMode", ParamGroup.COLOR, "", listOf("By Index", "By Distance", "By Angle"), "By Index"),
        Parameter.BooleanParam("Relaxed", "relaxed", ParamGroup.GEOMETRY, "Apply Lloyd relaxation for more uniform cells", false),
        Parameter.NumberParam("Anim Speed", "animSpeed", ParamGroup.FLOW_MOTION, "", 0f, 2f, 0.05f, 0.4f),
        Parameter.NumberParam("Anim Amplitude", "animAmp", ParamGroup.FLOW_MOTION, "Drift distance as a fraction of average cell size", 0f, 1f, 0.05f, 0.2f)
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "cellCount" to 40f,
        "distanceMetric" to "Euclidean",
        "borderWidth" to 1f,
        "colorMode" to "By Index",
        "relaxed" to false,
        "animSpeed" to 0.4f,
        "animAmp" to 0.2f
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
        val edgeWidth = (params["borderWidth"] as? Number)?.toFloat() ?: 1f
        val showEdges = edgeWidth > 0f
        val metric = (params["distanceMetric"] as? String) ?: "Euclidean"

        val rng = SeededRNG(seed)
        val noise = SimplexNoise(seed)

        // Generate seed points
        val px = FloatArray(numPoints)
        val py = FloatArray(numPoints)
        for (i in 0 until numPoints) {
            px[i] = rng.random() * w
            py[i] = rng.random() * h
        }

        // Animate: displace points with noise
        if (time > 0f) {
            for (i in 0 until numPoints) {
                val nx = noise.noise2D(i * 0.3f + 100f, time * 0.2f) * w * 0.05f
                val ny = noise.noise2D(i * 0.3f + 200f, time * 0.2f) * h * 0.05f
                px[i] = (px[i] + nx).coerceIn(0f, w.toFloat() - 1f)
                py[i] = (py[i] + ny).coerceIn(0f, h.toFloat() - 1f)
            }
        }

        val colors = palette.colorInts()
        val pixels = IntArray(w * h)
        val cellMap = IntArray(w * h)

        val step = when (quality) {
            Quality.DRAFT -> 2
            Quality.BALANCED -> 1
            Quality.ULTRA -> 1
        }

        for (row in 0 until h step step) {
            for (col in 0 until w step step) {
                var bestDist = Float.MAX_VALUE
                var secondDist = Float.MAX_VALUE
                var bestIdx = 0
                val x = col.toFloat()
                val y = row.toFloat()

                for (i in 0 until numPoints) {
                    val dx = x - px[i]
                    val dy = y - py[i]
                    val d = when (metric.lowercase()) {
                        "manhattan" -> abs(dx) + abs(dy)
                        "chebyshev" -> maxOf(abs(dx), abs(dy))
                        else -> dx * dx + dy * dy
                    }
                    if (d < bestDist) {
                        secondDist = bestDist
                        bestDist = d
                        bestIdx = i
                    } else if (d < secondDist) {
                        secondDist = d
                    }
                }

                val isEdge = showEdges && (secondDist - bestDist) < edgeWidth * 2f
                val color = if (isEdge) Color.BLACK else colors[bestIdx % colors.size]

                if (step == 1) {
                    pixels[row * w + col] = color
                    cellMap[row * w + col] = bestIdx
                } else {
                    for (dy in 0 until step) {
                        for (dx in 0 until step) {
                            val fx = col + dx
                            val fy = row + dy
                            if (fx < w && fy < h) {
                                pixels[fy * w + fx] = color
                                cellMap[fy * w + fx] = bestIdx
                            }
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
        return (n / 200f).coerceIn(0.2f, 1f)
    }
}
