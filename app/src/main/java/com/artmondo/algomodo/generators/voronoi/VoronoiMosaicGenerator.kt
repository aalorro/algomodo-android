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
 * Voronoi mosaic generator.
 *
 * Draws Voronoi cells as mosaic tiles with visible gaps (grout) between them,
 * resembling broken tile or stained glass mosaic artwork.
 */
class VoronoiMosaicGenerator : Generator {

    override val id = "voronoi-mosaic"
    override val family = "voronoi"
    override val styleName = "Voronoi Mosaic"
    override val definition =
        "Mosaic-style Voronoi cells with visible grout gaps between tiles, resembling broken tile or stained glass artwork."
    override val algorithmNotes =
        "Each pixel is assigned to its nearest Voronoi seed. Pixels near cell boundaries (where F2-F1 is small) " +
        "are coloured with the grout colour instead, creating visible gaps. The gap parameter controls grout " +
        "thickness. Cell shading can be flat or textured with subtle noise variation. Animation drifts seeds via noise."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema = listOf(
        Parameter.NumberParam("Cell Count", "cellCount", ParamGroup.COMPOSITION, "", 5f, 200f, 5f, 60f),
        Parameter.NumberParam("Grout Width", "groutWidth", ParamGroup.GEOMETRY, "Width of the grout lines between tiles", 0f, 12f, 0.5f, 3f),
        Parameter.SelectParam("Grout Color", "groutColor", ParamGroup.COLOR, "", listOf("grey", "white", "black", "palette-last"), "grey"),
        Parameter.SelectParam("Tile Style", "tileStyle", ParamGroup.TEXTURE, "flat = solid color; raised/inset adds a shading gradient inside each tile", listOf("flat", "raised", "inset"), "flat"),
        Parameter.SelectParam("Color Mode", "colorMode", ParamGroup.COLOR, "", listOf("palette-cycle", "palette-angle", "palette-distance"), "palette-cycle"),
        Parameter.SelectParam("Distance Metric", "distanceMetric", ParamGroup.GEOMETRY, "", listOf("Euclidean", "Manhattan", "Chebyshev"), "Euclidean"),
        Parameter.BooleanParam("Lloyd Relaxed", "relaxed", ParamGroup.GEOMETRY, "Apply one pass of Lloyd relaxation for more uniform tiles", true),
        Parameter.NumberParam("Anim Speed", "animSpeed", ParamGroup.FLOW_MOTION, "", 0f, 2f, 0.05f, 0.4f),
        Parameter.NumberParam("Anim Amplitude", "animAmp", ParamGroup.FLOW_MOTION, "Drift distance as a fraction of average cell size", 0f, 1f, 0.05f, 0.2f)
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "cellCount" to 60f,
        "groutWidth" to 3f,
        "groutColor" to "grey",
        "tileStyle" to "flat",
        "colorMode" to "palette-cycle",
        "distanceMetric" to "Euclidean",
        "relaxed" to true,
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
        val numPoints = (params["cellCount"] as? Number)?.toInt() ?: 60
        val gap = (params["groutWidth"] as? Number)?.toFloat() ?: 3f
        val groutColor = (params["groutColor"] as? String) ?: "grey"
        val cellShading = (params["tileStyle"] as? String) ?: "flat"

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
            for (i in 0 until numPoints) {
                px[i] += noise.noise2D(i * 0.3f + 80f, time * 0.12f) * w * 0.03f
                py[i] += noise.noise2D(i * 0.3f + 180f, time * 0.12f) * h * 0.03f
                px[i] = px[i].coerceIn(0f, w.toFloat() - 1f)
                py[i] = py[i].coerceIn(0f, h.toFloat() - 1f)
            }
        }

        val colors = palette.colorInts()
        val groutColorInt = when (groutColor) {
            "white" -> Color.WHITE
            "black" -> Color.BLACK
            "palette-last" -> colors.lastOrNull() ?: Color.BLACK
            else -> Color.rgb(128, 128, 128) // "grey"
        }

        val pixels = IntArray(w * h)

        // Sample max edge distance for gap threshold scaling
        var maxEdgeDist = 1f
        for (s in 0 until 60) {
            val sx = rng.random() * w
            val sy = rng.random() * h
            val (f1, f2) = findF1F2BruteForce(sx, sy, px, py, numPoints)
            val ed = f2 - f1
            if (ed > maxEdgeDist) maxEdgeDist = ed
        }

        val gapThreshold = gap * 2f

        val step = when (quality) {
            Quality.DRAFT -> 2
            Quality.BALANCED -> 1
            Quality.ULTRA -> 1
        }

        for (row in 0 until h step step) {
            for (col in 0 until w step step) {
                val (f1, f2, nearestIdx) = findF1F2WithIndexBruteForce(
                    col.toFloat(), row.toFloat(), px, py, numPoints
                )
                val edgeDist = f2 - f1

                val isGrout = edgeDist < gapThreshold

                val color = if (isGrout) {
                    groutColorInt
                } else {
                    val baseColor = colors[nearestIdx % colors.size]
                    when (cellShading) {
                        "raised" -> {
                            // Lighten towards cell centre to simulate raised tile
                            val distToEdge = (edgeDist / gapThreshold.coerceAtLeast(1f)).coerceIn(1f, 3f)
                            val factor = (0.85f + (distToEdge - 1f) * 0.05f).coerceIn(0.85f, 1.15f)
                            val r = (Color.red(baseColor) * factor).toInt().coerceIn(0, 255)
                            val g = (Color.green(baseColor) * factor).toInt().coerceIn(0, 255)
                            val b = (Color.blue(baseColor) * factor).toInt().coerceIn(0, 255)
                            Color.rgb(r, g, b)
                        }
                        "inset" -> {
                            // Darken towards cell centre to simulate inset tile
                            val distToEdge = (edgeDist / gapThreshold.coerceAtLeast(1f)).coerceIn(1f, 3f)
                            val factor = (1.15f - (distToEdge - 1f) * 0.05f).coerceIn(0.85f, 1.15f)
                            val r = (Color.red(baseColor) * factor).toInt().coerceIn(0, 255)
                            val g = (Color.green(baseColor) * factor).toInt().coerceIn(0, 255)
                            val b = (Color.blue(baseColor) * factor).toInt().coerceIn(0, 255)
                            Color.rgb(r, g, b)
                        }
                        else -> baseColor // "flat"
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

    private fun findF1F2WithIndexBruteForce(
        x: Float, y: Float,
        px: FloatArray, py: FloatArray, numPoints: Int
    ): F1F2Result {
        var f1 = Float.MAX_VALUE
        var f2 = Float.MAX_VALUE
        var nearestIdx = 0
        for (i in 0 until numPoints) {
            val dx = x - px[i]
            val dy = y - py[i]
            val d = sqrt(dx * dx + dy * dy)
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
        val n = (params["cellCount"] as? Number)?.toFloat() ?: 60f
        return (n / 200f).coerceIn(0.2f, 1f)
    }
}
