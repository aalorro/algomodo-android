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
        if (numPoints <= 0) { canvas.drawColor(Color.BLACK); return }
        val weightVariance = (params["weightSpread"] as? Number)?.toFloat() ?: 0.7f
        val weightMode = (params["weightMode"] as? String) ?: "additive"
        val borderWidth = (params["borderWidth"] as? Number)?.toFloat() ?: 1f
        val colorMode = (params["colorMode"] as? String) ?: "By Index"
        val animSpeed = (params["animSpeed"] as? Number)?.toFloat() ?: 0.4f
        val animAmp = (params["animAmp"] as? Number)?.toFloat() ?: 0.2f

        val weightModeId = when (weightMode) { "power" -> 2; "multiplicative" -> 1; else -> 0 }
        val colorModeId = when (colorMode) { "By Weight" -> 1; "By Distance" -> 2; else -> 0 }
        val hasBorder = borderWidth > 0f
        val borderThreshold = borderWidth * 2f

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
            val speed = animSpeed / 0.4f
            val amp = animAmp / 0.2f
            val wf = w.toFloat() - 1f; val hf = h.toFloat() - 1f
            for (i in 0 until numPoints) {
                px[i] = (px[i] + noise.noise2D(i * 0.3f + 90f, time * 0.12f * speed) * w * 0.03f * amp).coerceIn(0f, wf)
                py[i] = (py[i] + noise.noise2D(i * 0.3f + 190f, time * 0.12f * speed) * h * 0.03f * amp).coerceIn(0f, hf)
                weights[i] *= 1f + noise.noise2D(i * 0.5f + 300f, time * 0.1f) * 0.1f
            }
        }

        // ── Pre-compute weight-derived arrays ──
        val wSq = if (weightModeId == 2) FloatArray(numPoints) { weights[it] * weights[it] } else FloatArray(0)
        val invWSq = if (weightModeId == 1) FloatArray(numPoints) {
            val iw = 1f / weights[it].coerceAtLeast(0.01f); iw * iw
        } else FloatArray(0)

        // ── Pre-compute cell colors (eliminates per-pixel weights.min/max/lerpColor) ──
        val colors = palette.colorInts()
        val colorsSize = colors.size
        val cellColors = IntArray(numPoints)
        when (colorModeId) {
            0 -> for (i in 0 until numPoints) cellColors[i] = colors[i % colorsSize]
            1 -> {
                var minW = Float.MAX_VALUE; var maxW = -Float.MAX_VALUE
                for (i in 0 until numPoints) {
                    if (weights[i] < minW) minW = weights[i]
                    if (weights[i] > maxW) maxW = weights[i]
                }
                val invRange = 1f / (maxW - minW).coerceAtLeast(0.001f)
                val lut = palette.buildLut(256)
                for (i in 0 until numPoints) {
                    val t = ((weights[i] - minW) * invRange).coerceIn(0f, 1f)
                    cellColors[i] = lut[(t * 255f).toInt().coerceIn(0, 255)]
                }
            }
        }
        val distLut = if (colorModeId == 2) palette.buildLut(256) else IntArray(0)
        val invAvgCellSize = 1f / avgCellSize

        val pixels = IntArray(w * h)
        val step = when (quality) { Quality.DRAFT -> 2; else -> 1 }

        // Per-row dy² cache — hoists (y-py[i]) subtract + multiply out of the column loop
        val dySq = FloatArray(numPoints)

        when (weightModeId) {
            // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            // POWER MODE: d = dx²+dy² - w², zero sqrt in hot loop
            // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            2 -> {
                for (row in 0 until h step step) {
                    val y = row.toFloat(); val rowOff = row * w
                    for (i in 0 until numPoints) { val dy = y - py[i]; dySq[i] = dy * dy }
                    for (col in 0 until w step step) {
                        val x = col.toFloat()
                        var bestDist = Float.MAX_VALUE; var secondDist = Float.MAX_VALUE; var bestIdx = 0
                        for (i in 0 until numPoints) {
                            val dx = x - px[i]
                            val d = dx * dx + dySq[i] - wSq[i]
                            if (d < bestDist) { secondDist = bestDist; bestDist = d; bestIdx = i }
                            else if (d < secondDist) secondDist = d
                        }
                        val color = if (hasBorder && (secondDist - bestDist) < borderThreshold) {
                            Color.BLACK
                        } else if (colorModeId == 2) {
                            val dx2 = x - px[bestIdx]
                            val dist = sqrt(dx2 * dx2 + dySq[bestIdx])
                            val t = (dist * invAvgCellSize).coerceIn(0f, 1f)
                            distLut[(t * 255f).toInt().coerceIn(0, 255)]
                        } else {
                            cellColors[bestIdx]
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
            // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            // MULTIPLICATIVE MODE: compare d² = distSq * invWSq, 2 sqrts per pixel
            // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            1 -> {
                for (row in 0 until h step step) {
                    val y = row.toFloat(); val rowOff = row * w
                    for (i in 0 until numPoints) { val dy = y - py[i]; dySq[i] = dy * dy }
                    for (col in 0 until w step step) {
                        val x = col.toFloat()
                        var bestDSq = Float.MAX_VALUE; var secondDSq = Float.MAX_VALUE; var bestIdx = 0
                        for (i in 0 until numPoints) {
                            val dx = x - px[i]
                            val dSq = (dx * dx + dySq[i]) * invWSq[i]
                            if (dSq < bestDSq) { secondDSq = bestDSq; bestDSq = dSq; bestIdx = i }
                            else if (dSq < secondDSq) secondDSq = dSq
                        }
                        val color = if (hasBorder && (sqrt(secondDSq) - sqrt(bestDSq)) < borderThreshold) {
                            Color.BLACK
                        } else if (colorModeId == 2) {
                            val dx2 = x - px[bestIdx]
                            val dist = sqrt(dx2 * dx2 + dySq[bestIdx])
                            val t = (dist * invAvgCellSize).coerceIn(0f, 1f)
                            distLut[(t * 255f).toInt().coerceIn(0, 255)]
                        } else {
                            cellColors[bestIdx]
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
            // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            // ADDITIVE MODE: d = sqrt(distSq) - w, prevBest hint + early-exit filter
            // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            else -> {
                if (hasBorder) {
                    // ── With border: track secondDist for edge detection ──
                    for (row in 0 until h step step) {
                        val y = row.toFloat(); val rowOff = row * w
                        for (i in 0 until numPoints) { val dy = y - py[i]; dySq[i] = dy * dy }
                        var prevBest = 0
                        for (col in 0 until w step step) {
                            val x = col.toFloat()
                            // Seed with previous pixel's winner — adjacent pixels almost always
                            // share the same nearest cell, giving a near-optimal initial bestDist
                            var dx = x - px[prevBest]
                            var bestDist = sqrt(dx * dx + dySq[prevBest]) - weights[prevBest]
                            var bestIdx = prevBest
                            var secondDist = Float.MAX_VALUE
                            for (i in 0 until numPoints) {
                                dx = x - px[i]
                                val distSq = dx * dx + dySq[i]
                                // Filter: skip sqrt when point can't beat secondDist
                                val thresh = secondDist + weights[i]
                                if (thresh > 0f && distSq >= thresh * thresh) continue
                                val d = sqrt(distSq) - weights[i]
                                if (d < bestDist) { secondDist = bestDist; bestDist = d; bestIdx = i }
                                else if (d < secondDist) secondDist = d
                            }
                            prevBest = bestIdx
                            val color = if ((secondDist - bestDist) < borderThreshold) {
                                Color.BLACK
                            } else if (colorModeId == 2) {
                                dx = x - px[bestIdx]
                                val dist = sqrt(dx * dx + dySq[bestIdx])
                                val t = (dist * invAvgCellSize).coerceIn(0f, 1f)
                                distLut[(t * 255f).toInt().coerceIn(0, 255)]
                            } else {
                                cellColors[bestIdx]
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
                    // ── No border: use bestDist for much more aggressive filter ──
                    // Since we only need bestIdx (no edge detection), the filter threshold
                    // is bestDist + w[i] instead of secondDist + w[i] — much tighter bound
                    for (row in 0 until h step step) {
                        val y = row.toFloat(); val rowOff = row * w
                        for (i in 0 until numPoints) { val dy = y - py[i]; dySq[i] = dy * dy }
                        var prevBest = 0
                        for (col in 0 until w step step) {
                            val x = col.toFloat()
                            var dx = x - px[prevBest]
                            var bestDist = sqrt(dx * dx + dySq[prevBest]) - weights[prevBest]
                            var bestIdx = prevBest
                            for (i in 0 until numPoints) {
                                dx = x - px[i]
                                val distSq = dx * dx + dySq[i]
                                // Aggressive filter using bestDist: d = sqrt(distSq) - w >= bestDist
                                // ⟺ sqrt(distSq) >= bestDist + w ⟺ distSq >= (bestDist + w)²
                                val thresh = bestDist + weights[i]
                                if (thresh > 0f && distSq >= thresh * thresh) continue
                                val d = sqrt(distSq) - weights[i]
                                if (d < bestDist) { bestDist = d; bestIdx = i }
                            }
                            prevBest = bestIdx
                            val color = if (colorModeId == 2) {
                                dx = x - px[bestIdx]
                                val dist = sqrt(dx * dx + dySq[bestIdx])
                                val t = (dist * invAvgCellSize).coerceIn(0f, 1f)
                                distLut[(t * 255f).toInt().coerceIn(0, 255)]
                            } else {
                                cellColors[bestIdx]
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
