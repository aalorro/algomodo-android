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
        if (numPoints <= 0) { canvas.drawColor(Color.BLACK); return }
        val bands = (params["bandCount"] as? Number)?.toInt() ?: 8
        val bandMode = (params["bandMode"] as? String) ?: "hard"
        val contourLineWidth = (params["contourLineWidth"] as? Number)?.toFloat() ?: 1f
        val colorMode = (params["colorMode"] as? String) ?: "palette-cycle"
        val distanceMetric = (params["distanceMetric"] as? String) ?: "Euclidean"
        val relaxed = params["relaxed"] as? Boolean ?: false
        val animSpeed = (params["animSpeed"] as? Number)?.toFloat() ?: 0.4f
        val animAmp = (params["animAmp"] as? Number)?.toFloat() ?: 0.2f

        val metricId = when (distanceMetric.lowercase()) {
            "manhattan" -> 1; "chebyshev" -> 2; else -> 0
        }
        val isEuclidean = metricId == 0

        val rng = SeededRNG(seed)
        val noise = SimplexNoise(seed)
        val px = FloatArray(numPoints)
        val py = FloatArray(numPoints)
        for (i in 0 until numPoints) {
            px[i] = rng.random() * w
            py[i] = rng.random() * h
        }

        if (relaxed) {
            val sampleStep = when (quality) { Quality.DRAFT -> 6; Quality.BALANCED -> 4; Quality.ULTRA -> 3 }
            for (pass in 0 until 3) {
                val sumX = FloatArray(numPoints)
                val sumY = FloatArray(numPoints)
                val count = IntArray(numPoints)
                for (sy in 0 until h step sampleStep) {
                    val yf = sy.toFloat()
                    for (sx in 0 until w step sampleStep) {
                        val xf = sx.toFloat()
                        var bd = Float.MAX_VALUE; var bi = 0
                        for (i in 0 until numPoints) {
                            val dx = xf - px[i]; val dy = yf - py[i]
                            val d = if (isEuclidean) dx * dx + dy * dy
                                    else if (metricId == 1) abs(dx) + abs(dy)
                                    else maxOf(abs(dx), abs(dy))
                            if (d < bd) { bd = d; bi = i }
                        }
                        sumX[bi] += xf; sumY[bi] += yf; count[bi]++
                    }
                }
                for (i in 0 until numPoints) {
                    if (count[i] > 0) { px[i] = sumX[i] / count[i]; py[i] = sumY[i] / count[i] }
                }
            }
        }

        if (time > 0f && animAmp > 0f) {
            val avgCell = sqrt((w.toFloat() * h.toFloat()) / numPoints)
            for (i in 0 until numPoints) {
                px[i] = (px[i] + noise.noise2D(i * 0.3f + 30f, time * 0.18f * animSpeed) * avgCell * animAmp).coerceIn(0f, w - 1f)
                py[i] = (py[i] + noise.noise2D(i * 0.3f + 130f, time * 0.18f * animSpeed) * avgCell * animAmp).coerceIn(0f, h - 1f)
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

        val pixels = IntArray(w * h)

        // Sample maxEdgeDist for normalization
        var maxEdgeDist = 1f
        for (s in 0 until 50) {
            val sx = rng.random() * w; val sy = rng.random() * h
            var sf1 = Float.MAX_VALUE; var sf2 = Float.MAX_VALUE
            for (i in 0 until numPoints) {
                val dx = sx - px[i]; val dy = sy - py[i]
                val d = if (isEuclidean) sqrt(dx * dx + dy * dy)
                        else if (metricId == 1) abs(dx) + abs(dy)
                        else maxOf(abs(dx), abs(dy))
                if (d < sf1) { sf2 = sf1; sf1 = d }
                else if (d < sf2) { sf2 = d }
            }
            val ed = sf2 - sf1
            if (ed > maxEdgeDist) maxEdgeDist = ed
        }

        val paletteColors = palette.colorInts()
        val paletteSize = paletteColors.size
        val colorModeId = when (colorMode) { "palette-gradient" -> 1; "monochrome" -> 2; else -> 0 }
        val lut = if (colorModeId == 1) palette.buildLut(256) else null
        val bandsM1 = (bands - 1).coerceAtLeast(1)
        val invMaxEdge = 1f / maxEdgeDist
        val invBandsM1 = 1f / bandsM1
        val step = when (quality) { Quality.DRAFT -> 2; else -> 1 }
        val drawContourLines = contourLineWidth > 0f && bandMode != "smooth"
        val bandMap = if (drawContourLines) IntArray(w * h) else null
        val isSmooth = bandMode == "smooth"
        val invBands = 1f / bands

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
                    var f1 = Float.MAX_VALUE; var f2 = Float.MAX_VALUE
                    for (cy in cyMin..cyMax) {
                        val ro = cy * gridCols
                        for (cx in cxMin..cxMax) {
                            var idx = gridHeads[ro + cx]
                            while (idx >= 0) {
                                val dx = x - px[idx]; val dy = y - py[idx]
                                val d = dx * dx + dy * dy
                                if (d < f1) { f2 = f1; f1 = d }
                                else if (d < f2) { f2 = d }
                                idx = gridNext[idx]
                            }
                        }
                    }
                    f1 = sqrt(f1); f2 = sqrt(f2)
                    val normalized = ((f2 - f1) * invMaxEdge).coerceIn(0f, 1f)
                    val bandFloat = normalized * bands
                    val bandI = minOf(bandFloat.toInt(), bands - 1)
                    val color = if (isSmooth) {
                        val frac = bandFloat - bandI
                        val smoothFrac = if (frac < invBands) frac * bands else 1f
                        val base = bandI * invBandsM1
                        val t = base + ((bandI + 1) * invBandsM1 - base) * smoothFrac * 0.3f
                        when (colorModeId) {
                            1 -> lut!![(t.coerceIn(0f, 1f) * 255f).toInt().coerceIn(0, 255)]
                            2 -> { val g = (t.coerceIn(0f, 1f) * 255f).toInt().coerceIn(0, 255); Color.rgb(g, g, g) }
                            else -> paletteColors[bandI % paletteSize]
                        }
                    } else {
                        when (colorModeId) {
                            1 -> { val t = (bandI * invBandsM1).coerceIn(0f, 1f); lut!![(t * 255f).toInt().coerceIn(0, 255)] }
                            2 -> { val g = (bandI * invBandsM1 * 255f).toInt().coerceIn(0, 255); Color.rgb(g, g, g) }
                            else -> paletteColors[bandI % paletteSize]
                        }
                    }
                    if (step == 1) {
                        pixels[rowOff + col] = color
                        bandMap?.set(rowOff + col, bandI)
                    } else {
                        val i0 = rowOff + col
                        pixels[i0] = color; bandMap?.set(i0, bandI)
                        if (col + 1 < w) { pixels[i0 + 1] = color; bandMap?.set(i0 + 1, bandI) }
                        if (row + 1 < h) {
                            pixels[i0 + w] = color; bandMap?.set(i0 + w, bandI)
                            if (col + 1 < w) { pixels[i0 + w + 1] = color; bandMap?.set(i0 + w + 1, bandI) }
                        }
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
                    var f1 = Float.MAX_VALUE; var f2 = Float.MAX_VALUE
                    for (cy in cyMin..cyMax) {
                        val ro = cy * gridCols
                        for (cx in cxMin..cxMax) {
                            var idx = gridHeads[ro + cx]
                            while (idx >= 0) {
                                val dx = x - px[idx]; val dy = y - py[idx]
                                val d = if (metricId == 1) abs(dx) + abs(dy)
                                        else maxOf(abs(dx), abs(dy))
                                if (d < f1) { f2 = f1; f1 = d }
                                else if (d < f2) { f2 = d }
                                idx = gridNext[idx]
                            }
                        }
                    }
                    val normalized = ((f2 - f1) * invMaxEdge).coerceIn(0f, 1f)
                    val bandFloat = normalized * bands
                    val bandI = minOf(bandFloat.toInt(), bands - 1)
                    val color = if (isSmooth) {
                        val frac = bandFloat - bandI
                        val smoothFrac = if (frac < invBands) frac * bands else 1f
                        val base = bandI * invBandsM1
                        val t = base + ((bandI + 1) * invBandsM1 - base) * smoothFrac * 0.3f
                        when (colorModeId) {
                            1 -> lut!![(t.coerceIn(0f, 1f) * 255f).toInt().coerceIn(0, 255)]
                            2 -> { val g = (t.coerceIn(0f, 1f) * 255f).toInt().coerceIn(0, 255); Color.rgb(g, g, g) }
                            else -> paletteColors[bandI % paletteSize]
                        }
                    } else {
                        when (colorModeId) {
                            1 -> { val t = (bandI * invBandsM1).coerceIn(0f, 1f); lut!![(t * 255f).toInt().coerceIn(0, 255)] }
                            2 -> { val g = (bandI * invBandsM1 * 255f).toInt().coerceIn(0, 255); Color.rgb(g, g, g) }
                            else -> paletteColors[bandI % paletteSize]
                        }
                    }
                    if (step == 1) {
                        pixels[rowOff + col] = color
                        bandMap?.set(rowOff + col, bandI)
                    } else {
                        val i0 = rowOff + col
                        pixels[i0] = color; bandMap?.set(i0, bandI)
                        if (col + 1 < w) { pixels[i0 + 1] = color; bandMap?.set(i0 + 1, bandI) }
                        if (row + 1 < h) {
                            pixels[i0 + w] = color; bandMap?.set(i0 + w, bandI)
                            if (col + 1 < w) { pixels[i0 + w + 1] = color; bandMap?.set(i0 + w + 1, bandI) }
                        }
                    }
                }
            }
        }

        // Contour lines: 4-neighbor check (fast path for lineRadius=1, 8-dir for larger)
        if (drawContourLines && bandMap != null) {
            val lineRadius = contourLineWidth.toInt().coerceAtLeast(1)
            if (lineRadius == 1) {
                for (row in 0 until h) {
                    val rowOff = row * w
                    for (col in 0 until w) {
                        val idx = rowOff + col
                        val mb = bandMap[idx]
                        if ((col > 0 && bandMap[idx - 1] != mb) ||
                            (col < w - 1 && bandMap[idx + 1] != mb) ||
                            (row > 0 && bandMap[idx - w] != mb) ||
                            (row < h - 1 && bandMap[idx + w] != mb)) {
                            val base = pixels[idx]
                            pixels[idx] = Color.rgb(
                                (Color.red(base) shr 2),
                                (Color.green(base) shr 2),
                                (Color.blue(base) shr 2)
                            )
                        }
                    }
                }
            } else {
                for (row in 0 until h) {
                    val rowOff = row * w
                    for (col in 0 until w) {
                        val idx = rowOff + col
                        val mb = bandMap[idx]
                        var isBoundary = false
                        for (dist in 1..lineRadius) {
                            if (row - dist >= 0 && bandMap[(row - dist) * w + col] != mb) { isBoundary = true; break }
                            if (row + dist < h && bandMap[(row + dist) * w + col] != mb) { isBoundary = true; break }
                            if (col - dist >= 0 && bandMap[rowOff + col - dist] != mb) { isBoundary = true; break }
                            if (col + dist < w && bandMap[rowOff + col + dist] != mb) { isBoundary = true; break }
                            if (row - dist >= 0 && col - dist >= 0 && bandMap[(row - dist) * w + col - dist] != mb) { isBoundary = true; break }
                            if (row - dist >= 0 && col + dist < w && bandMap[(row - dist) * w + col + dist] != mb) { isBoundary = true; break }
                            if (row + dist < h && col - dist >= 0 && bandMap[(row + dist) * w + col - dist] != mb) { isBoundary = true; break }
                            if (row + dist < h && col + dist < w && bandMap[(row + dist) * w + col + dist] != mb) { isBoundary = true; break }
                        }
                        if (isBoundary) {
                            val base = pixels[idx]
                            pixels[idx] = Color.rgb(
                                (Color.red(base) shr 2),
                                (Color.green(base) shr 2),
                                (Color.blue(base) shr 2)
                            )
                        }
                    }
                }
            }
        }

        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
        canvas.drawBitmap(bitmap, 0f, 0f, null)
    }

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float {
        val n = (params["cellCount"] as? Number)?.toFloat() ?: 25f
        return (n / 100f).coerceIn(0.2f, 1f)
    }
}
