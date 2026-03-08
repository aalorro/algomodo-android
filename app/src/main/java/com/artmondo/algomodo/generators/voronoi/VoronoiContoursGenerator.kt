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
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * Voronoi contour band generator.
 *
 * Instead of colouring by nearest cell, this uses the distance to the nearest
 * Voronoi edge (F2 - F1 concept) to create banded contour patterns, producing
 * a topographic map-like effect from the Voronoi distance field.
 */
class VoronoiContoursGenerator : Generator {

    override val id = "voronoi-contours"
    override val family = "voronoi"
    override val styleName = "Voronoi Contours"
    override val definition =
        "Voronoi contour bands where the distance field between cells is quantised into coloured bands, producing a topographic map effect."
    override val algorithmNotes =
        "For each pixel the distance to the two nearest seed points is computed. The difference (F2 - F1) " +
        "represents proximity to a Voronoi edge. This value is quantised into bands and mapped to palette colours. " +
        "A smoothing parameter blends band transitions. Animation drifts points via noise."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema = listOf(
        Parameter.NumberParam("Cell Count", "cellCount", ParamGroup.COMPOSITION, "", 3f, 120f, 3f, 25f),
        Parameter.NumberParam("Bands per Cell", "bandCount", ParamGroup.COMPOSITION, "Number of concentric contour rings around each seed", 2f, 20f, 1f, 8f),
        Parameter.SelectParam("Band Mode", "bandMode", ParamGroup.TEXTURE, "hard = crisp contour lines; smooth = continuous gradient; stepped = quantised levels", listOf("hard", "smooth", "stepped"), "hard"),
        Parameter.NumberParam("Contour Line", "contourLineWidth", ParamGroup.GEOMETRY, "Width of the dark contour boundary (hard/stepped modes)", 0f, 6f, 0.5f, 1f),
        Parameter.SelectParam("Color Mode", "colorMode", ParamGroup.COLOR, "", listOf("palette-cycle", "palette-gradient", "monochrome"), "palette-cycle"),
        Parameter.SelectParam("Distance Metric", "distanceMetric", ParamGroup.GEOMETRY, "", listOf("Euclidean", "Manhattan", "Chebyshev"), "Euclidean"),
        Parameter.BooleanParam("Lloyd Relaxed", "relaxed", ParamGroup.GEOMETRY, "", false),
        Parameter.NumberParam("Anim Speed", "animSpeed", ParamGroup.FLOW_MOTION, "", 0f, 2f, 0.05f, 0.4f),
        Parameter.NumberParam("Anim Amplitude", "animAmp", ParamGroup.FLOW_MOTION, "Drift distance as a fraction of average cell size", 0f, 1f, 0.05f, 0.2f)
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "cellCount" to 25f,
        "bandCount" to 8f,
        "bandMode" to "hard",
        "contourLineWidth" to 1f,
        "colorMode" to "palette-cycle",
        "distanceMetric" to "Euclidean",
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
        val numPoints = (params["cellCount"] as? Number)?.toInt() ?: 25
        val bands = (params["bandCount"] as? Number)?.toInt() ?: 8
        val bandMode = (params["bandMode"] as? String) ?: "hard"

        val rng = SeededRNG(seed)
        val noise = SimplexNoise(seed)

        // Generate seed points
        val px = FloatArray(numPoints)
        val py = FloatArray(numPoints)
        for (i in 0 until numPoints) {
            px[i] = rng.random() * w
            py[i] = rng.random() * h
        }

        // Animate
        if (time > 0f) {
            for (i in 0 until numPoints) {
                px[i] += noise.noise2D(i * 0.3f + 30f, time * 0.18f) * w * 0.04f
                py[i] += noise.noise2D(i * 0.3f + 130f, time * 0.18f) * h * 0.04f
                px[i] = px[i].coerceIn(0f, w.toFloat() - 1f)
                py[i] = py[i].coerceIn(0f, h.toFloat() - 1f)
            }
        }

        val pixels = IntArray(w * h)

        // Find max F2-F1 for normalization (sample a few points)
        var maxEdgeDist = 1f
        val sampleCount = 50
        for (s in 0 until sampleCount) {
            val sx = rng.random() * w
            val sy = rng.random() * h
            val (f1, f2) = findF1F2BruteForce(sx, sy, px, py, numPoints)
            val edgeDist = f2 - f1
            if (edgeDist > maxEdgeDist) maxEdgeDist = edgeDist
        }

        val step = when (quality) {
            Quality.DRAFT -> 2
            Quality.BALANCED -> 1
            Quality.ULTRA -> 1
        }

        for (row in 0 until h step step) {
            for (col in 0 until w step step) {
                val (f1, f2) = findF1F2BruteForce(col.toFloat(), row.toFloat(), px, py, numPoints)
                val edgeDist = (f2 - f1) / maxEdgeDist
                val normalized = edgeDist.coerceIn(0f, 1f)

                // Quantise into bands
                val bandFloat = normalized * bands
                val bandIndex = floor(bandFloat).toInt().coerceIn(0, bands - 1)

                val t = when (bandMode) {
                    "smooth" -> {
                        // Smooth transition between bands
                        val frac = bandFloat - floor(bandFloat)
                        val smoothFrac = if (frac < 1f / bands) {
                            frac / (1f / bands)
                        } else {
                            1f
                        }
                        val base = bandIndex.toFloat() / (bands - 1).coerceAtLeast(1)
                        val next = (bandIndex + 1).toFloat() / (bands - 1).coerceAtLeast(1)
                        base + (next - base) * smoothFrac * 0.3f
                    }
                    else -> {
                        // "hard" and "stepped" both use quantised bands
                        bandIndex.toFloat() / (bands - 1).coerceAtLeast(1)
                    }
                }

                val color = palette.lerpColor(t.coerceIn(0f, 1f))

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

    /**
     * Find the first and second nearest distances (F1, F2) from a point to the seed set
     * using brute-force search over all points.
     */
    private fun findF1F2BruteForce(
        x: Float, y: Float,
        px: FloatArray, py: FloatArray, numPoints: Int
    ): Pair<Float, Float> {
        var f1 = Float.MAX_VALUE
        var f2 = Float.MAX_VALUE
        for (i in 0 until numPoints) {
            val dx = x - px[i]
            val dy = y - py[i]
            val d = sqrt(dx * dx + dy * dy)
            if (d < f1) {
                f2 = f1
                f1 = d
            } else if (d < f2) {
                f2 = d
            }
        }
        return Pair(f1, f2)
    }

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float {
        val n = (params["cellCount"] as? Number)?.toFloat() ?: 25f
        return (n / 100f).coerceIn(0.2f, 1f)
    }
}
