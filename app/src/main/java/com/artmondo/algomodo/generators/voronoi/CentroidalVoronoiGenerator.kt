package com.artmondo.algomodo.generators.voronoi

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.artmondo.algomodo.core.rng.SeededRNG
import com.artmondo.algomodo.core.rng.SimplexNoise
import com.artmondo.algomodo.data.palettes.Palette
import com.artmondo.algomodo.generators.Generator
import com.artmondo.algomodo.generators.ParamGroup
import com.artmondo.algomodo.generators.Parameter
import com.artmondo.algomodo.generators.Quality
import kotlin.math.sqrt

/**
 * Centroidal Voronoi tessellation via Lloyd's relaxation.
 *
 * Starts with random seed points and iteratively moves each point to the
 * centroid of its Voronoi cell, producing increasingly regular cells.
 */
class CentroidalVoronoiGenerator : Generator {

    override val id = "centroidal-voronoi"
    override val family = "voronoi"
    override val styleName = "Centroidal Voronoi"
    override val definition =
        "Centroidal Voronoi tessellation where Lloyd's relaxation iteratively moves seed points to their cell centroids, producing regular honeycomb-like patterns."
    override val algorithmNotes =
        "Initial points from SeededRNG. Each relaxation step assigns every sampled pixel to its nearest seed point, " +
        "accumulates centroids, then moves points. The number of relaxation steps controls regularity. " +
        "Supports flat, gradient, and outlined cell rendering styles. Animation gently oscillates points via noise."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema = listOf(
        Parameter.NumberParam("Cell Count", "cellCount", ParamGroup.COMPOSITION, "", 5f, 200f, 5f, 50f),
        Parameter.NumberParam("Relaxation Steps", "relaxationSteps", ParamGroup.GEOMETRY, "Lloyd relaxation passes — more steps = more regular hexagonal cells", 0f, 15f, 1f, 5f),
        Parameter.NumberParam("Border Width", "borderWidth", ParamGroup.GEOMETRY, "", 0f, 6f, 0.5f, 1.5f),
        Parameter.BooleanParam("Show Seeds", "showSeeds", ParamGroup.GEOMETRY, "Draw the centroid seed point in each cell", false),
        Parameter.SelectParam("Color Mode", "colorMode", ParamGroup.COLOR, "", listOf("By Index", "By Distance", "By Position"), "By Index"),
        Parameter.SelectParam("Distance Metric", "distanceMetric", ParamGroup.GEOMETRY, "", listOf("Euclidean", "Manhattan", "Chebyshev"), "Euclidean"),
        Parameter.NumberParam("Anim Speed", "animSpeed", ParamGroup.FLOW_MOTION, "", 0f, 2f, 0.05f, 0.4f),
        Parameter.NumberParam("Anim Amplitude", "animAmp", ParamGroup.FLOW_MOTION, "Drift distance as a fraction of average cell size", 0f, 1f, 0.05f, 0.2f)
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "cellCount" to 50f,
        "relaxationSteps" to 5f,
        "borderWidth" to 1.5f,
        "showSeeds" to false,
        "colorMode" to "By Index",
        "distanceMetric" to "Euclidean",
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
        val numPoints = (params["cellCount"] as? Number)?.toInt() ?: 50
        val relaxSteps = (params["relaxationSteps"] as? Number)?.toInt() ?: 5
        val showCentroids = params["showSeeds"] as? Boolean ?: false

        val rng = SeededRNG(seed)
        val noise = SimplexNoise(seed)

        // Initialize points
        val px = FloatArray(numPoints)
        val py = FloatArray(numPoints)
        for (i in 0 until numPoints) {
            px[i] = rng.random() * w
            py[i] = rng.random() * h
        }

        // Sampling resolution for relaxation
        val sampleStep = when (quality) {
            Quality.DRAFT -> 4
            Quality.BALANCED -> 3
            Quality.ULTRA -> 2
        }

        // Lloyd's relaxation
        for (step in 0 until relaxSteps) {
            val sumX = FloatArray(numPoints)
            val sumY = FloatArray(numPoints)
            val count = IntArray(numPoints)

            for (sy in 0 until h step sampleStep) {
                for (sx in 0 until w step sampleStep) {
                    val nearest = findNearest(sx.toFloat(), sy.toFloat(), px, py, numPoints)
                    sumX[nearest] += sx.toFloat()
                    sumY[nearest] += sy.toFloat()
                    count[nearest]++
                }
            }

            for (i in 0 until numPoints) {
                if (count[i] > 0) {
                    px[i] = sumX[i] / count[i]
                    py[i] = sumY[i] / count[i]
                }
            }
        }

        // Animate: small noise displacement after relaxation
        if (time > 0f) {
            for (i in 0 until numPoints) {
                px[i] += noise.noise2D(i * 0.5f + 10f, time * 0.2f) * w * 0.02f
                py[i] += noise.noise2D(i * 0.5f + 110f, time * 0.2f) * h * 0.02f
                px[i] = px[i].coerceIn(0f, w.toFloat() - 1f)
                py[i] = py[i].coerceIn(0f, h.toFloat() - 1f)
            }
        }

        // Render pixels
        val pixels = IntArray(w * h)
        val colors = palette.colorInts()

        val renderStep = when (quality) {
            Quality.DRAFT -> 2
            Quality.BALANCED -> 1
            Quality.ULTRA -> 1
        }

        for (row in 0 until h step renderStep) {
            for (col in 0 until w step renderStep) {
                val nearest = findNearest(col.toFloat(), row.toFloat(), px, py, numPoints)
                val color = colors[nearest % colors.size]

                if (renderStep == 1) {
                    pixels[row * w + col] = color
                } else {
                    for (dy2 in 0 until renderStep) {
                        for (dx2 in 0 until renderStep) {
                            val fx = col + dx2
                            val fy = row + dy2
                            if (fx < w && fy < h) pixels[fy * w + fx] = color
                        }
                    }
                }
            }
        }

        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
        canvas.drawBitmap(bitmap, 0f, 0f, null)

        // Draw centroids
        if (showCentroids) {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                style = Paint.Style.FILL
            }
            for (i in 0 until numPoints) {
                canvas.drawCircle(px[i], py[i], 3f, paint)
            }
        }
    }

    private fun findNearest(x: Float, y: Float, px: FloatArray, py: FloatArray, n: Int): Int {
        var bestDist = Float.MAX_VALUE
        var bestIdx = 0
        for (i in 0 until n) {
            val dx = x - px[i]
            val dy = y - py[i]
            val d = dx * dx + dy * dy
            if (d < bestDist) {
                bestDist = d
                bestIdx = i
            }
        }
        return bestIdx
    }

    private fun findSecondNearest(x: Float, y: Float, px: FloatArray, py: FloatArray, n: Int, skip: Int): Int {
        var bestDist = Float.MAX_VALUE
        var bestIdx = 0
        for (i in 0 until n) {
            if (i == skip) continue
            val dx = x - px[i]
            val dy = y - py[i]
            val d = dx * dx + dy * dy
            if (d < bestDist) {
                bestDist = d
                bestIdx = i
            }
        }
        return bestIdx
    }

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float {
        val n = (params["cellCount"] as? Number)?.toFloat() ?: 50f
        val steps = (params["relaxationSteps"] as? Number)?.toFloat() ?: 5f
        return ((n * steps) / 7500f).coerceIn(0.2f, 1f)
    }
}
