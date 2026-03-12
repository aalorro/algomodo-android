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
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Voronoi crackle texture generator.
 *
 * Uses the F2 - F1 distance (the difference between the second-nearest and
 * nearest Voronoi seed distances) to create organic crackle patterns. Low
 * F2-F1 values produce bright crackle lines; high values produce dark interiors.
 */
class VoronoiCrackleGenerator : Generator {

    override val id = "voronoi-crackle"
    override val family = "voronoi"
    override val styleName = "Voronoi Crackle"
    override val definition =
        "Crackle texture derived from Voronoi F2-F1 distance, producing organic vein-like patterns similar to dried mud or stone cracks."
    override val algorithmNotes =
        "For each pixel the two nearest seed point distances F1 and F2 are found. " +
        "The crackle value is (F2 - F1) raised to an intensity power, emphasising narrow edges. " +
        "Low values (near cell boundaries) are mapped to bright palette colours; high values to dark tones. " +
        "lineWidth controls the visual thickness by scaling the crackle field. Animation drifts seeds via noise."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema = listOf(
        Parameter.NumberParam("Cell Count", "cellCount", ParamGroup.COMPOSITION, "", 5f, 300f, 5f, 80f),
        Parameter.NumberParam("Crack Width", "crackWidth", ParamGroup.GEOMETRY, "Thickness of crack lines", 0.5f, 8f, 0.5f, 2f),
        Parameter.SelectParam("Crack Color", "crackColor", ParamGroup.COLOR, "", listOf("black", "white", "palette-first", "palette-last"), "black"),
        Parameter.SelectParam("Fill Mode", "fillMode", ParamGroup.COLOR, "How cell interiors are colored", listOf("flat-dark", "flat-light", "gradient", "palette"), "gradient"),
        Parameter.SelectParam("Distance Metric", "distanceMetric", ParamGroup.GEOMETRY, "", listOf("Euclidean", "Manhattan", "Chebyshev"), "Euclidean"),
        Parameter.NumberParam("Anim Speed", "animSpeed", ParamGroup.FLOW_MOTION, "", 0f, 2f, 0.05f, 0.4f),
        Parameter.NumberParam("Anim Amplitude", "animAmp", ParamGroup.FLOW_MOTION, "Drift distance as a fraction of average cell size", 0f, 1f, 0.05f, 0.2f)
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "cellCount" to 80f,
        "crackWidth" to 2f,
        "crackColor" to "black",
        "fillMode" to "gradient",
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
        val numPoints = (params["cellCount"] as? Number)?.toInt() ?: 80
        val intensity = 1f
        val lineWidth = (params["crackWidth"] as? Number)?.toFloat() ?: 2f
        val crackColor = (params["crackColor"] as? String) ?: "black"
        val fillMode = (params["fillMode"] as? String) ?: "gradient"
        val distanceMetric = (params["distanceMetric"] as? String) ?: "Euclidean"
        val animSpeed = (params["animSpeed"] as? Number)?.toFloat() ?: 0.4f
        val animAmp = (params["animAmp"] as? Number)?.toFloat() ?: 0.2f

        val rng = SeededRNG(seed)
        val noise = SimplexNoise(seed)

        val px = FloatArray(numPoints)
        val py = FloatArray(numPoints)
        for (i in 0 until numPoints) {
            px[i] = rng.random() * w
            py[i] = rng.random() * h
        }

        // Animate
        if (time > 0f) {
            val speed = animSpeed / 0.4f
            val amp = animAmp / 0.2f
            for (i in 0 until numPoints) {
                px[i] += noise.noise2D(i * 0.25f + 40f, time * 0.15f * speed) * w * 0.03f * amp
                py[i] += noise.noise2D(i * 0.25f + 140f, time * 0.15f * speed) * h * 0.03f * amp
                px[i] = px[i].coerceIn(0f, w.toFloat() - 1f)
                py[i] = py[i].coerceIn(0f, h.toFloat() - 1f)
            }
        }

        val pixels = IntArray(w * h)
        val colors = palette.colorInts()

        // Determine crack line color
        val crackColorInt = when (crackColor) {
            "white" -> Color.WHITE
            "palette-first" -> colors.firstOrNull() ?: Color.BLACK
            "palette-last" -> colors.lastOrNull() ?: Color.BLACK
            else -> Color.BLACK  // "black"
        }

        // Sample max edge distance for normalization
        var maxCrackle = 1f
        for (s in 0 until 100) {
            val sx = rng.random() * w
            val sy = rng.random() * h
            val (f1, f2) = findF1F2BruteForce(sx, sy, px, py, numPoints, distanceMetric)
            val c = (f2 - f1)
            if (c > maxCrackle) maxCrackle = c
        }

        val lineScale = lineWidth / 2f

        val step = when (quality) {
            Quality.DRAFT -> 2
            Quality.BALANCED -> 1
            Quality.ULTRA -> 1
        }

        for (row in 0 until h step step) {
            for (col in 0 until w step step) {
                val (f1, f2, nearestIdx) = findF1F2WithIndexBruteForce(col.toFloat(), row.toFloat(), px, py, numPoints, distanceMetric)
                val crackleRaw = (f2 - f1) / maxCrackle
                // Apply intensity exponent and line width scaling
                val crackle = (crackleRaw / lineScale).coerceIn(0f, 1f).pow(intensity)

                val color = if (crackle < 0.15f) {
                    // Near the crack line - use crack color
                    crackColorInt
                } else {
                    // Cell interior - use fill mode
                    when (fillMode) {
                        "flat-dark" -> {
                            val base = colors[nearestIdx % colors.size]
                            val factor = 0.3f
                            Color.rgb(
                                (Color.red(base) * factor).toInt().coerceIn(0, 255),
                                (Color.green(base) * factor).toInt().coerceIn(0, 255),
                                (Color.blue(base) * factor).toInt().coerceIn(0, 255)
                            )
                        }
                        "flat-light" -> {
                            val base = colors[nearestIdx % colors.size]
                            val factor = 0.85f
                            Color.rgb(
                                (Color.red(base) * factor + 255 * (1f - factor)).toInt().coerceIn(0, 255),
                                (Color.green(base) * factor + 255 * (1f - factor)).toInt().coerceIn(0, 255),
                                (Color.blue(base) * factor + 255 * (1f - factor)).toInt().coerceIn(0, 255)
                            )
                        }
                        "palette" -> {
                            colors[nearestIdx % colors.size]
                        }
                        else -> {
                            // "gradient" - original behavior with brightness gradient
                            val edgeColor = colors[nearestIdx % colors.size]
                            val r = (Color.red(edgeColor) * (1f - crackle * 0.85f)).toInt().coerceIn(0, 255)
                            val g = (Color.green(edgeColor) * (1f - crackle * 0.85f)).toInt().coerceIn(0, 255)
                            val b = (Color.blue(edgeColor) * (1f - crackle * 0.85f)).toInt().coerceIn(0, 255)
                            Color.rgb(r, g, b)
                        }
                    }
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

    private data class F1F2Result(val f1: Float, val f2: Float, val nearestIdx: Int)

    private fun computeDistance(dx: Float, dy: Float, metric: String): Float = when (metric) {
        "Manhattan" -> kotlin.math.abs(dx) + kotlin.math.abs(dy)
        "Chebyshev" -> maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy))
        else -> sqrt(dx * dx + dy * dy)
    }

    private fun findF1F2BruteForce(
        x: Float, y: Float,
        px: FloatArray, py: FloatArray, numPoints: Int,
        metric: String = "Euclidean"
    ): Pair<Float, Float> {
        var f1 = Float.MAX_VALUE
        var f2 = Float.MAX_VALUE
        for (i in 0 until numPoints) {
            val dx = x - px[i]
            val dy = y - py[i]
            val d = computeDistance(dx, dy, metric)
            if (d < f1) {
                f2 = f1
                f1 = d
            } else if (d < f2) {
                f2 = d
            }
        }
        return Pair(f1, f2)
    }

    private fun findF1F2WithIndexBruteForce(
        x: Float, y: Float,
        px: FloatArray, py: FloatArray, numPoints: Int,
        metric: String = "Euclidean"
    ): F1F2Result {
        var f1 = Float.MAX_VALUE
        var f2 = Float.MAX_VALUE
        var nearestIdx = 0
        for (i in 0 until numPoints) {
            val dx = x - px[i]
            val dy = y - py[i]
            val d = computeDistance(dx, dy, metric)
            if (d < f1) {
                f2 = f1
                f1 = d
                nearestIdx = i
            } else if (d < f2) {
                f2 = d
            }
        }
        return F1F2Result(f1, f2, nearestIdx)
    }

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float {
        val n = (params["cellCount"] as? Number)?.toFloat() ?: 80f
        return (n / 200f).coerceIn(0.2f, 1f)
    }
}
