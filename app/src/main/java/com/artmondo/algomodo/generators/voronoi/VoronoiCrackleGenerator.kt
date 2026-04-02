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
        if (numPoints <= 0) { canvas.drawColor(Color.BLACK); return }
        val lineWidth = (params["crackWidth"] as? Number)?.toFloat() ?: 2f
        val crackColor = (params["crackColor"] as? String) ?: "black"
        val fillMode = (params["fillMode"] as? String) ?: "gradient"
        val distanceMetric = (params["distanceMetric"] as? String) ?: "Euclidean"
        val animSpeed = (params["animSpeed"] as? Number)?.toFloat() ?: 0.4f
        val animAmp = (params["animAmp"] as? Number)?.toFloat() ?: 0.2f

        val metricId = when (distanceMetric.lowercase()) {
            "manhattan" -> 1; "chebyshev" -> 2; else -> 0
        }
        val isEuclidean = metricId == 0
        val fillModeId = when (fillMode) { "flat-dark" -> 1; "flat-light" -> 2; "palette" -> 3; else -> 0 }

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
            val speed = animSpeed / 0.4f; val amp = animAmp / 0.2f
            val wf = w.toFloat(); val hf = h.toFloat()
            for (i in 0 until numPoints) {
                px[i] = (px[i] + noise.noise2D(i * 0.25f + 40f, time * 0.15f * speed) * wf * 0.03f * amp).coerceIn(0f, wf - 1f)
                py[i] = (py[i] + noise.noise2D(i * 0.25f + 140f, time * 0.15f * speed) * hf * 0.03f * amp).coerceIn(0f, hf - 1f)
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

        // Sample maxCrackle for normalization
        var maxCrackle = 1f
        for (s in 0 until 50) {
            val sx = rng.random() * w; val sy = rng.random() * h
            var sf1 = Float.MAX_VALUE; var sf2 = Float.MAX_VALUE
            for (i in 0 until numPoints) {
                val dx = sx - px[i]; val dy = sy - py[i]
                val d = if (isEuclidean) dx * dx + dy * dy
                        else if (metricId == 1) abs(dx) + abs(dy)
                        else maxOf(abs(dx), abs(dy))
                if (d < sf1) { sf2 = sf1; sf1 = d }
                else if (d < sf2) { sf2 = d }
            }
            if (isEuclidean) { sf1 = sqrt(sf1); sf2 = sqrt(sf2) }
            val ed = sf2 - sf1
            if (ed > maxCrackle) maxCrackle = ed
        }

        val colors = palette.colorInts()
        val colorsSize = colors.size
        val crackColorInt = when (crackColor) {
            "white" -> Color.WHITE
            "palette-first" -> colors.firstOrNull() ?: Color.BLACK
            "palette-last" -> colors.lastOrNull() ?: Color.BLACK
            else -> Color.BLACK
        }
        val lineScale = lineWidth / 2f
        val invMaxCrackle = 1f / maxCrackle
        val pixels = IntArray(w * h)
        val step = when (quality) { Quality.DRAFT -> 2; else -> 1 }

        if (isEuclidean) {
            // ── EUCLIDEAN FAST PATH: no metric branch in inner loop ──
            for (row in 0 until h step step) {
                val y = row.toFloat()
                val rowOff = row * w
                val gy = minOf((y * invGridSize).toInt(), grM1)
                val cyMin = maxOf(gy - 1, 0); val cyMax = minOf(gy + 1, grM1)
                for (col in 0 until w step step) {
                    val x = col.toFloat()
                    val gx = minOf((x * invGridSize).toInt(), gcM1)
                    val cxMin = maxOf(gx - 1, 0); val cxMax = minOf(gx + 1, gcM1)
                    var f1 = Float.MAX_VALUE; var f2 = Float.MAX_VALUE; var nearestIdx = 0
                    for (cy in cyMin..cyMax) {
                        val ro = cy * gridCols
                        for (cx in cxMin..cxMax) {
                            var idx = gridHeads[ro + cx]
                            while (idx >= 0) {
                                val dx = x - px[idx]; val dy = y - py[idx]
                                val d = dx * dx + dy * dy
                                if (d < f1) { f2 = f1; f1 = d; nearestIdx = idx }
                                else if (d < f2) { f2 = d }
                                idx = gridNext[idx]
                            }
                        }
                    }
                    f1 = sqrt(f1); f2 = sqrt(f2)
                    val crackle = ((f2 - f1) * invMaxCrackle / lineScale).coerceIn(0f, 1f)
                    val color = if (crackle < 0.15f) crackColorInt
                    else {
                        val ci = nearestIdx % colorsSize
                        when (fillModeId) {
                            1 -> { // flat-dark
                                val base = colors[ci]
                                Color.rgb((Color.red(base) * 0.3f).toInt(), (Color.green(base) * 0.3f).toInt(), (Color.blue(base) * 0.3f).toInt())
                            }
                            2 -> { // flat-light
                                val base = colors[ci]
                                Color.rgb(
                                    (Color.red(base) * 0.85f + 38.25f).toInt().coerceIn(0, 255),
                                    (Color.green(base) * 0.85f + 38.25f).toInt().coerceIn(0, 255),
                                    (Color.blue(base) * 0.85f + 38.25f).toInt().coerceIn(0, 255)
                                )
                            }
                            3 -> colors[ci] // palette
                            else -> { // gradient
                                val base = colors[ci]
                                val factor = 1f - crackle * 0.85f
                                Color.rgb(
                                    (Color.red(base) * factor).toInt().coerceIn(0, 255),
                                    (Color.green(base) * factor).toInt().coerceIn(0, 255),
                                    (Color.blue(base) * factor).toInt().coerceIn(0, 255)
                                )
                            }
                        }
                    }
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
        } else {
            // ── NON-EUCLIDEAN PATH ──
            for (row in 0 until h step step) {
                val y = row.toFloat()
                val rowOff = row * w
                val gy = minOf((y * invGridSize).toInt(), grM1)
                val cyMin = maxOf(gy - 1, 0); val cyMax = minOf(gy + 1, grM1)
                for (col in 0 until w step step) {
                    val x = col.toFloat()
                    val gx = minOf((x * invGridSize).toInt(), gcM1)
                    val cxMin = maxOf(gx - 1, 0); val cxMax = minOf(gx + 1, gcM1)
                    var f1 = Float.MAX_VALUE; var f2 = Float.MAX_VALUE; var nearestIdx = 0
                    for (cy in cyMin..cyMax) {
                        val ro = cy * gridCols
                        for (cx in cxMin..cxMax) {
                            var idx = gridHeads[ro + cx]
                            while (idx >= 0) {
                                val dx = x - px[idx]; val dy = y - py[idx]
                                val d = if (metricId == 1) abs(dx) + abs(dy)
                                        else maxOf(abs(dx), abs(dy))
                                if (d < f1) { f2 = f1; f1 = d; nearestIdx = idx }
                                else if (d < f2) { f2 = d }
                                idx = gridNext[idx]
                            }
                        }
                    }
                    val crackle = ((f2 - f1) * invMaxCrackle / lineScale).coerceIn(0f, 1f)
                    val color = if (crackle < 0.15f) crackColorInt
                    else {
                        val ci = nearestIdx % colorsSize
                        when (fillModeId) {
                            1 -> {
                                val base = colors[ci]
                                Color.rgb((Color.red(base) * 0.3f).toInt(), (Color.green(base) * 0.3f).toInt(), (Color.blue(base) * 0.3f).toInt())
                            }
                            2 -> {
                                val base = colors[ci]
                                Color.rgb(
                                    (Color.red(base) * 0.85f + 38.25f).toInt().coerceIn(0, 255),
                                    (Color.green(base) * 0.85f + 38.25f).toInt().coerceIn(0, 255),
                                    (Color.blue(base) * 0.85f + 38.25f).toInt().coerceIn(0, 255)
                                )
                            }
                            3 -> colors[ci]
                            else -> {
                                val base = colors[ci]
                                val factor = 1f - crackle * 0.85f
                                Color.rgb(
                                    (Color.red(base) * factor).toInt().coerceIn(0, 255),
                                    (Color.green(base) * factor).toInt().coerceIn(0, 255),
                                    (Color.blue(base) * factor).toInt().coerceIn(0, 255)
                                )
                            }
                        }
                    }
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
        }

        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
        canvas.drawBitmap(bitmap, 0f, 0f, null)
    }

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float {
        val n = (params["cellCount"] as? Number)?.toFloat() ?: 80f
        return (n / 200f).coerceIn(0.2f, 1f)
    }
}
