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
import kotlin.math.sqrt

/**
 * Weighted Voronoi generator.
 *
 * Each seed point has a random weight that biases its distance computation,
 * producing cells of varying sizes. Points with larger weights "claim" more
 * territory, creating organic, bubble-like patterns.
 */
class VoronoiWeightedGenerator : Generator {

    override val id = "voronoi-weighted"
    override val family = "voronoi"
    override val styleName = "Voronoi Weighted"
    override val definition =
        "Weighted (power) Voronoi diagram where each seed point has a random weight that biases distance, creating cells of varying sizes like soap bubbles."
    override val algorithmNotes =
        "Each seed point is assigned a random weight from [1 - variance, 1 + variance]. " +
        "In 'euclidean' mode the weighted distance is sqrt(dx^2+dy^2) - weight, so heavier points claim " +
        "more area. In 'power' mode the power distance d^2 - w^2 is used, producing circular cell boundaries. " +
        "Animation slowly oscillates weights and drifts points via noise."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema = listOf(
        Parameter.NumberParam("Cell Count", "cellCount", ParamGroup.COMPOSITION, "", 5f, 150f, 5f, 40f),
        Parameter.NumberParam("Weight Spread", "weightSpread", ParamGroup.GEOMETRY, "Variance in site weights — 0 = uniform (standard Voronoi), 1 = maximum size variation", 0f, 1f, 0.05f, 0.7f),
        Parameter.SelectParam("Weight Mode", "weightMode", ParamGroup.GEOMETRY, "additive: d-w (shifts boundary); multiplicative: d/w (scales cells); power: d^(1/w) (organic bulge)", listOf("additive", "multiplicative", "power"), "additive"),
        Parameter.NumberParam("Border Width", "borderWidth", ParamGroup.GEOMETRY, "", 0f, 5f, 0.5f, 1f),
        Parameter.SelectParam("Color Mode", "colorMode", ParamGroup.COLOR, "By Weight: larger-weighted sites use later palette colors", listOf("By Index", "By Weight", "By Distance"), "By Index"),
        Parameter.SelectParam("Distance Metric", "distanceMetric", ParamGroup.GEOMETRY, "", listOf("Euclidean", "Manhattan", "Chebyshev"), "Euclidean"),
        Parameter.NumberParam("Anim Speed", "animSpeed", ParamGroup.FLOW_MOTION, "", 0f, 2f, 0.05f, 0.4f),
        Parameter.NumberParam("Anim Amplitude", "animAmp", ParamGroup.FLOW_MOTION, "Drift distance as a fraction of average cell size", 0f, 1f, 0.05f, 0.2f)
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "cellCount" to 40f,
        "weightSpread" to 0.7f,
        "weightMode" to "additive",
        "borderWidth" to 1f,
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
        val numPoints = (params["cellCount"] as? Number)?.toInt() ?: 40
        val weightVariance = (params["weightSpread"] as? Number)?.toFloat() ?: 0.7f
        val metric = (params["distanceMetric"] as? String) ?: "Euclidean"
        val weightMode = (params["weightMode"] as? String) ?: "additive"

        val rng = SeededRNG(seed)
        val noise = SimplexNoise(seed)

        val px = FloatArray(numPoints)
        val py = FloatArray(numPoints)
        val weights = FloatArray(numPoints)
        val avgCellSize = sqrt((w.toFloat() * h.toFloat()) / numPoints)

        for (i in 0 until numPoints) {
            px[i] = rng.random() * w
            py[i] = rng.random() * h
            weights[i] = avgCellSize * (1f + (rng.random() - 0.5f) * weightVariance)
        }

        // Animate
        if (time > 0f) {
            for (i in 0 until numPoints) {
                px[i] += noise.noise2D(i * 0.3f + 90f, time * 0.12f) * w * 0.03f
                py[i] += noise.noise2D(i * 0.3f + 190f, time * 0.12f) * h * 0.03f
                px[i] = px[i].coerceIn(0f, w.toFloat() - 1f)
                py[i] = py[i].coerceIn(0f, h.toFloat() - 1f)
                // Oscillate weights
                weights[i] *= 1f + noise.noise2D(i * 0.5f + 300f, time * 0.1f) * 0.1f
            }
        }

        val colors = palette.colorInts()
        val pixels = IntArray(w * h)

        val step = when (quality) {
            Quality.DRAFT -> 2
            Quality.BALANCED -> 1
            Quality.ULTRA -> 1
        }

        for (row in 0 until h step step) {
            for (col in 0 until w step step) {
                // Simple brute-force - always correct for small point counts
                var bestDist = Float.MAX_VALUE
                var secondDist = Float.MAX_VALUE
                var bestIdx = 0
                val x = col.toFloat()
                val y = row.toFloat()

                for (i in 0 until numPoints) {
                    val dx = x - px[i]
                    val dy = y - py[i]
                    val d = when (weightMode) {
                        "power" -> (dx * dx + dy * dy) - weights[i] * weights[i]
                        "multiplicative" -> sqrt(dx * dx + dy * dy) / weights[i].coerceAtLeast(0.01f)
                        else -> sqrt(dx * dx + dy * dy) - weights[i]
                    }
                    if (d < bestDist) {
                        secondDist = bestDist
                        bestDist = d
                        bestIdx = i
                    } else if (d < secondDist) {
                        secondDist = d
                    }
                }

                // Detect edges
                val isEdge = (secondDist - bestDist) < 2f
                val color = if (isEdge) {
                    Color.BLACK
                } else {
                    colors[bestIdx % colors.size]
                }

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
