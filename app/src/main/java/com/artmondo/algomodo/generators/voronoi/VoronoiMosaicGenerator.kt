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
import kotlin.math.atan2
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
        if (numPoints <= 0) { canvas.drawColor(Color.BLACK); return }
        val gap = (params["groutWidth"] as? Number)?.toFloat() ?: 3f
        val groutColor = (params["groutColor"] as? String) ?: "grey"
        val cellShading = (params["tileStyle"] as? String) ?: "flat"
        val colorMode = (params["colorMode"] as? String) ?: "palette-cycle"
        val distanceMetric = (params["distanceMetric"] as? String) ?: "Euclidean"
        val relaxed = params["relaxed"] as? Boolean ?: true
        val animSpeed = (params["animSpeed"] as? Number)?.toFloat() ?: 0.4f
        val animAmp = (params["animAmp"] as? Number)?.toFloat() ?: 0.2f

        val metricId = when (distanceMetric.lowercase()) {
            "manhattan" -> 1; "chebyshev" -> 2; else -> 0
        }
        val isEuclidean = metricId == 0
        val colorModeId = when (colorMode) { "palette-angle" -> 1; "palette-distance" -> 2; else -> 0 }
        val tileStyleId = when (cellShading) { "raised" -> 1; "inset" -> 2; else -> 0 }

        val rng = SeededRNG(seed)
        val noise = SimplexNoise(seed)

        val px = FloatArray(numPoints)
        val py = FloatArray(numPoints)
        for (i in 0 until numPoints) {
            px[i] = rng.random() * w
            py[i] = rng.random() * h
        }

        // Lloyd relaxation using spatial grid
        if (relaxed) {
            val relaxStep = 4
            for (pass in 0 until 3) {
                val rGs = (maxOf(w, h).toFloat() / sqrt(numPoints.toFloat())).coerceAtLeast(8f)
                val rInv = 1f / rGs
                val rGc = (w * rInv).toInt() + 1
                val rGr = (h * rInv).toInt() + 1
                val rGcM1 = rGc - 1; val rGrM1 = rGr - 1
                val rGh = IntArray(rGc * rGr) { -1 }
                val rGn = IntArray(numPoints) { -1 }
                for (i in 0 until numPoints) {
                    val gx = minOf((px[i] * rInv).toInt(), rGcM1)
                    val gy = minOf((py[i] * rInv).toInt(), rGrM1)
                    val cell = gy * rGc + gx
                    rGn[i] = rGh[cell]; rGh[cell] = i
                }
                val sumX = FloatArray(numPoints)
                val sumY = FloatArray(numPoints)
                val count = IntArray(numPoints)
                for (sy in 0 until h step relaxStep) {
                    val yf = sy.toFloat()
                    val gy = minOf((yf * rInv).toInt(), rGrM1)
                    val cyMin = maxOf(gy - 1, 0); val cyMax = minOf(gy + 1, rGrM1)
                    for (sx in 0 until w step relaxStep) {
                        val xf = sx.toFloat()
                        val gx = minOf((xf * rInv).toInt(), rGcM1)
                        val cxMin = maxOf(gx - 1, 0); val cxMax = minOf(gx + 1, rGcM1)
                        var bd = Float.MAX_VALUE; var bi = 0
                        for (cy in cyMin..cyMax) {
                            val ro = cy * rGc
                            for (cx in cxMin..cxMax) {
                                var ii = rGh[ro + cx]
                                while (ii >= 0) {
                                    val dx = xf - px[ii]; val dy = yf - py[ii]
                                    val d = if (isEuclidean) dx * dx + dy * dy
                                            else if (metricId == 1) abs(dx) + abs(dy)
                                            else maxOf(abs(dx), abs(dy))
                                    if (d < bd) { bd = d; bi = ii }
                                    ii = rGn[ii]
                                }
                            }
                        }
                        sumX[bi] += xf; sumY[bi] += yf; count[bi]++
                    }
                }
                for (i in 0 until numPoints) {
                    if (count[i] > 0) { px[i] = sumX[i] / count[i]; py[i] = sumY[i] / count[i] }
                }
            }
        }

        // Animate
        if (time > 0f) {
            val speed = animSpeed / 0.4f; val amp = animAmp / 0.2f
            val wf = w.toFloat(); val hf = h.toFloat()
            for (i in 0 until numPoints) {
                px[i] = (px[i] + noise.noise2D(i * 0.3f + 80f, time * 0.12f * speed) * wf * 0.03f * amp).coerceIn(0f, wf - 1f)
                py[i] = (py[i] + noise.noise2D(i * 0.3f + 180f, time * 0.12f * speed) * hf * 0.03f * amp).coerceIn(0f, hf - 1f)
            }
        }

        // ── Spatial grid (linked-list) ──
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

        val colors = palette.colorInts()
        val colorsSize = colors.size
        val groutColorInt = when (groutColor) {
            "white" -> Color.WHITE
            "black" -> Color.BLACK
            "palette-last" -> colors.lastOrNull() ?: Color.BLACK
            else -> Color.rgb(128, 128, 128) // "grey"
        }

        // Pre-compute cell colors (avoids per-pixel atan2/sqrt/lerpColor)
        val lut = if (colorModeId != 0) palette.buildLut(256) else null
        val cellColors = IntArray(numPoints)
        when (colorModeId) {
            1 -> { // palette-angle
                val halfW = w / 2f; val halfH = h / 2f
                val invTwoPi = 1f / (2f * Math.PI.toFloat())
                for (i in 0 until numPoints) {
                    val angle = atan2(py[i] - halfH, px[i] - halfW)
                    val t = ((angle + Math.PI.toFloat()) * invTwoPi).coerceIn(0f, 1f)
                    cellColors[i] = lut!![(t * 255f).toInt().coerceIn(0, 255)]
                }
            }
            2 -> { // palette-distance
                val halfW = w / 2f; val halfH = h / 2f
                val maxDist = sqrt((w * w + h * h).toFloat()) / 2f
                val invMaxDist = 1f / maxDist
                for (i in 0 until numPoints) {
                    val dx = px[i] - halfW; val dy = py[i] - halfH
                    val t = (sqrt(dx * dx + dy * dy) * invMaxDist).coerceIn(0f, 1f)
                    cellColors[i] = lut!![(t * 255f).toInt().coerceIn(0, 255)]
                }
            }
            else -> { // palette-cycle
                for (i in 0 until numPoints) cellColors[i] = colors[i % colorsSize]
            }
        }

        val gapThreshold = gap * 2f
        val pixels = IntArray(w * h)
        val step = when (quality) { Quality.DRAFT -> 2; else -> 1 }

        if (isEuclidean && tileStyleId == 0) {
            // ── EUCLIDEAN FLAT FAST PATH ──
            // Uses 1-sqrt trick: sqrt(f2)-sqrt(f1) < t ⟺ f2 < f1 + t² + 2t·sqrt(f1)
            val gapThreshSq = gapThreshold * gapThreshold
            for (row in 0 until h step step) {
                val y = row.toFloat()
                val rowOff = row * w
                val gy = minOf((y * invGridSize).toInt(), grM1)
                val cyMin = maxOf(gy - 1, 0); val cyMax = minOf(gy + 1, grM1)
                for (col in 0 until w step step) {
                    val x = col.toFloat()
                    val gx = minOf((x * invGridSize).toInt(), gcM1)
                    val cxMin = maxOf(gx - 1, 0); val cxMax = minOf(gx + 1, gcM1)
                    var f1 = Float.MAX_VALUE; var f2 = Float.MAX_VALUE; var nearIdx = 0
                    for (cy in cyMin..cyMax) {
                        val ro = cy * gridCols
                        for (cx in cxMin..cxMax) {
                            var ii = gridHeads[ro + cx]
                            while (ii >= 0) {
                                val dx = x - px[ii]; val dy = y - py[ii]
                                val d = dx * dx + dy * dy
                                if (d < f1) { f2 = f1; f1 = d; nearIdx = ii }
                                else if (d < f2) { f2 = d }
                                ii = gridNext[ii]
                            }
                        }
                    }
                    val sqrtF1 = sqrt(f1)
                    val isGrout = f2 < f1 + gapThreshSq + 2f * gapThreshold * sqrtF1
                    val color = if (isGrout) groutColorInt else cellColors[nearIdx]

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
            // ── GENERAL PATH ──
            val gapThreshInv = 1f / gapThreshold.coerceAtLeast(1f)
            for (row in 0 until h step step) {
                val y = row.toFloat()
                val rowOff = row * w
                val gy = minOf((y * invGridSize).toInt(), grM1)
                val cyMin = maxOf(gy - 1, 0); val cyMax = minOf(gy + 1, grM1)
                for (col in 0 until w step step) {
                    val x = col.toFloat()
                    val gx = minOf((x * invGridSize).toInt(), gcM1)
                    val cxMin = maxOf(gx - 1, 0); val cxMax = minOf(gx + 1, gcM1)
                    var f1 = Float.MAX_VALUE; var f2 = Float.MAX_VALUE; var nearIdx = 0
                    for (cy in cyMin..cyMax) {
                        val ro = cy * gridCols
                        for (cx in cxMin..cxMax) {
                            var ii = gridHeads[ro + cx]
                            while (ii >= 0) {
                                val dx = x - px[ii]; val dy = y - py[ii]
                                val d = if (isEuclidean) dx * dx + dy * dy
                                        else if (metricId == 1) abs(dx) + abs(dy)
                                        else maxOf(abs(dx), abs(dy))
                                if (d < f1) { f2 = f1; f1 = d; nearIdx = ii }
                                else if (d < f2) { f2 = d }
                                ii = gridNext[ii]
                            }
                        }
                    }

                    val edgeDist: Float = if (isEuclidean) {
                        sqrt(f2) - sqrt(f1)
                    } else {
                        f2 - f1
                    }
                    val isGrout = edgeDist < gapThreshold

                    val color = if (isGrout) groutColorInt
                    else {
                        val baseColor = cellColors[nearIdx]
                        when (tileStyleId) {
                            1 -> { // raised
                                val distToEdge = (edgeDist * gapThreshInv).coerceIn(1f, 3f)
                                val factor = (0.85f + (distToEdge - 1f) * 0.05f).coerceIn(0.85f, 1.15f)
                                Color.rgb((Color.red(baseColor) * factor).toInt().coerceIn(0, 255),
                                    (Color.green(baseColor) * factor).toInt().coerceIn(0, 255),
                                    (Color.blue(baseColor) * factor).toInt().coerceIn(0, 255))
                            }
                            2 -> { // inset
                                val distToEdge = (edgeDist * gapThreshInv).coerceIn(1f, 3f)
                                val factor = (1.15f - (distToEdge - 1f) * 0.05f).coerceIn(0.85f, 1.15f)
                                Color.rgb((Color.red(baseColor) * factor).toInt().coerceIn(0, 255),
                                    (Color.green(baseColor) * factor).toInt().coerceIn(0, 255),
                                    (Color.blue(baseColor) * factor).toInt().coerceIn(0, 255))
                            }
                            else -> baseColor // flat
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
        val n = (params["cellCount"] as? Number)?.toFloat() ?: 60f
        return (n / 200f).coerceIn(0.2f, 1f)
    }
}
