package com.artmondo.algomodo.generators.voronoi

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
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
 * Voronoi ridge / edge-emphasis generator.
 *
 * Emphasises the boundaries between Voronoi cells as glowing or sharp ridges.
 * Uses the F2-F1 distance field inverted so that edges are bright and cell
 * interiors are dark.
 */
class VoronoiRidgesGenerator : Generator {

    override val id = "voronoi-ridges"
    override val family = "voronoi"
    override val styleName = "Voronoi Ridges"
    override val definition =
        "Voronoi ridge patterns where cell boundaries are rendered as glowing, sharp, or smooth luminous ridges against a dark background."
    override val algorithmNotes =
        "The F2-F1 distance field is computed per pixel. Low values (near cell edges) are mapped to bright " +
        "colours. The 'sharp' style uses a hard threshold; 'smooth' uses a Gaussian falloff; 'glowing' adds " +
        "an additive bloom-like spread. ridgeWidth controls the effective width of the bright zone. " +
        "Animation drifts seed points via simplex noise."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema = listOf(
        Parameter.NumberParam("Cell Count", "cellCount", ParamGroup.COMPOSITION, "", 5f, 200f, 5f, 50f),
        Parameter.NumberParam("Octaves", "octaves", ParamGroup.COMPOSITION, "Layers of Voronoi stacked at increasing frequencies", 1f, 5f, 1f, 3f),
        Parameter.NumberParam("Lacunarity", "lacunarity", ParamGroup.GEOMETRY, "Frequency multiplier per octave", 1.2f, 4f, 0.1f, 2.0f),
        Parameter.NumberParam("Gain", "gain", ParamGroup.GEOMETRY, "Amplitude multiplier per octave", 0.2f, 0.8f, 0.05f, 0.5f),
        Parameter.NumberParam("Ridge Sharpness", "ridgeSharpness", ParamGroup.TEXTURE, "Power curve applied to ridge values — higher = sharper peaks", 0.5f, 4f, 0.1f, 1.5f),
        Parameter.SelectParam("Distance Metric", "distanceMetric", ParamGroup.GEOMETRY, "euclidean: round ridges | manhattan: diamond facets | chebyshev: square crystalline", listOf("euclidean", "manhattan", "chebyshev"), "euclidean"),
        Parameter.SelectParam("Color Mode", "colorMode", ParamGroup.COLOR, "", listOf("palette", "greyscale", "inverted"), "palette"),
        Parameter.NumberParam("Anim Speed", "animSpeed", ParamGroup.FLOW_MOTION, "", 0f, 2f, 0.05f, 0.3f),
        Parameter.NumberParam("Anim Amplitude", "animAmp", ParamGroup.FLOW_MOTION, "Drift distance as a fraction of average cell size", 0f, 1f, 0.05f, 0.15f)
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "cellCount" to 50f,
        "octaves" to 3f,
        "lacunarity" to 2.0f,
        "gain" to 0.5f,
        "ridgeSharpness" to 1.5f,
        "distanceMetric" to "euclidean",
        "colorMode" to "palette",
        "animSpeed" to 0.3f,
        "animAmp" to 0.15f
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
        val octaves = (params["octaves"] as? Number)?.toInt()?.coerceIn(1, 5) ?: 3
        val lacunarity = (params["lacunarity"] as? Number)?.toFloat() ?: 2.0f
        val gain = (params["gain"] as? Number)?.toFloat() ?: 0.5f
        val ridgeSharpness = (params["ridgeSharpness"] as? Number)?.toFloat() ?: 1.5f
        val distMetric = (params["distanceMetric"] as? String) ?: "euclidean"
        val style = (params["colorMode"] as? String) ?: "palette"
        val animSpeed = (params["animSpeed"] as? Number)?.toFloat() ?: 0.3f
        val animAmp = (params["animAmp"] as? Number)?.toFloat() ?: 0.15f

        val metricId = when (distMetric.lowercase()) {
            "manhattan" -> 1; "chebyshev" -> 2; else -> 0
        }
        val isEuclidean = metricId == 0
        val styleId = when (style) { "greyscale" -> 1; "inverted" -> 2; else -> 0 }

        val noise = SimplexNoise(seed)
        val avgCellSize = sqrt((w * h).toFloat() / numPoints)

        // ── Per-octave data + spatial grids ──
        val octPx = Array(octaves) { FloatArray(0) }
        val octPy = Array(octaves) { FloatArray(0) }
        val octCount = IntArray(octaves)
        val octAmp = FloatArray(octaves)
        val octInvMaxEd = FloatArray(octaves)
        val octGh = Array(octaves) { IntArray(0) }
        val octGn = Array(octaves) { IntArray(0) }
        val octGc = IntArray(octaves)
        val octGr = IntArray(octaves)
        val octGcM1 = IntArray(octaves)
        val octGrM1 = IntArray(octaves)
        val octInvGs = FloatArray(octaves)

        for (oct in 0 until octaves) {
            val octRng = SeededRNG(seed + oct * 7919)
            val freq = lacunarity.pow(oct.toFloat())
            val amp = gain.pow(oct.toFloat())
            val octPoints = (numPoints * freq).toInt().coerceIn(3, 4000)

            val opx = FloatArray(octPoints)
            val opy = FloatArray(octPoints)
            for (i in 0 until octPoints) {
                opx[i] = octRng.random() * w
                opy[i] = octRng.random() * h
            }

            if (time > 0f) {
                val driftRange = animAmp * avgCellSize / freq
                for (i in 0 until octPoints) {
                    opx[i] = (opx[i] + noise.noise2D(i * 0.3f + 70f + oct * 100f, time * animSpeed) * driftRange).coerceIn(0f, w - 1f)
                    opy[i] = (opy[i] + noise.noise2D(i * 0.3f + 170f + oct * 100f, time * animSpeed) * driftRange).coerceIn(0f, h - 1f)
                }
            }

            val gs = (maxOf(w, h).toFloat() / sqrt(octPoints.toFloat())).coerceAtLeast(8f)
            val invGs = 1f / gs
            val gc = (w * invGs).toInt() + 1
            val gr = (h * invGs).toInt() + 1
            val gcM1 = gc - 1; val grM1v = gr - 1
            val gh = IntArray(gc * gr) { -1 }
            val gn = IntArray(octPoints) { -1 }
            for (i in 0 until octPoints) {
                val gx = minOf((opx[i] * invGs).toInt(), gcM1)
                val gy = minOf((opy[i] * invGs).toInt(), grM1v)
                val cell = gy * gc + gx
                gn[i] = gh[cell]; gh[cell] = i
            }

            var maxEd = 1f
            val sampleRng = SeededRNG(seed + oct * 3571)
            for (s in 0 until 80) {
                val sx = sampleRng.random() * w; val sy = sampleRng.random() * h
                val sgx = minOf((sx * invGs).toInt(), gcM1)
                val sgy = minOf((sy * invGs).toInt(), grM1v)
                val scyMin = maxOf(sgy - 1, 0); val scyMax = minOf(sgy + 1, grM1v)
                val scxMin = maxOf(sgx - 1, 0); val scxMax = minOf(sgx + 1, gcM1)
                var sf1 = Float.MAX_VALUE; var sf2 = Float.MAX_VALUE
                for (cy in scyMin..scyMax) {
                    val ro = cy * gc
                    for (cx in scxMin..scxMax) {
                        var ii = gh[ro + cx]
                        while (ii >= 0) {
                            val dx = sx - opx[ii]; val dy = sy - opy[ii]
                            val d = if (isEuclidean) dx * dx + dy * dy
                                    else if (metricId == 1) { val ax = if (dx < 0f) -dx else dx; val ay = if (dy < 0f) -dy else dy; ax + ay }
                                    else { val ax = if (dx < 0f) -dx else dx; val ay = if (dy < 0f) -dy else dy; if (ax > ay) ax else ay }
                            if (d < sf1) { sf2 = sf1; sf1 = d }
                            else if (d < sf2) { sf2 = d }
                            ii = gn[ii]
                        }
                    }
                }
                val ed = if (isEuclidean) sqrt(sf2) - sqrt(sf1) else sf2 - sf1
                if (ed > maxEd) maxEd = ed
            }

            octPx[oct] = opx; octPy[oct] = opy; octCount[oct] = octPoints
            octAmp[oct] = amp; octInvMaxEd[oct] = 1f / maxEd
            octGh[oct] = gh; octGn[oct] = gn
            octGc[oct] = gc; octGr[oct] = gr
            octGcM1[oct] = gcM1; octGrM1[oct] = grM1v
            octInvGs[oct] = invGs
        }

        val sharpLut = FloatArray(256) { (it / 255f).pow(ridgeSharpness) }

        var ampSum = 0f
        for (oct in 0 until octaves) ampSum += octAmp[oct]
        val invAmpSum = 1f / ampSum

        val colors = palette.colorInts()
        val colorsSize = colors.size

        // ── Pre-compute color LUTs per cell (eliminates per-pixel Color.rgb + float→int) ──
        val colorLuts = Array(colorsSize) { ci ->
            IntArray(256) { v ->
                when (styleId) {
                    1 -> Color.rgb(v, v, v)
                    2 -> {
                        val base = colors[ci]; val inv = (255 - v) / 255f
                        Color.rgb((Color.red(base) * inv).toInt().coerceIn(0, 255),
                            (Color.green(base) * inv).toInt().coerceIn(0, 255),
                            (Color.blue(base) * inv).toInt().coerceIn(0, 255))
                    }
                    else -> {
                        val base = colors[ci]; val f = v / 255f
                        Color.rgb((Color.red(base) * f).toInt().coerceIn(0, 255),
                            (Color.green(base) * f).toInt().coerceIn(0, 255),
                            (Color.blue(base) * f).toInt().coerceIn(0, 255))
                    }
                }
            }
        }

        // ── Render at reduced resolution, bilinear upscale ──
        val scale = when (quality) { Quality.DRAFT -> 3; Quality.BALANCED -> 2; else -> 1 }
        val rw = if (scale > 1) (w + scale - 1) / scale else w
        val rh = if (scale > 1) (h + scale - 1) / scale else h
        val scaleF = scale.toFloat()
        val pixels = IntArray(rw * rh)

        if (isEuclidean) {
            // ── EUCLIDEAN PATH ──
            for (row in 0 until rh) {
                val y = row.toFloat() * scaleF
                val rowOff = row * rw
                for (col in 0 until rw) {
                    val x = col.toFloat() * scaleF
                    var ridgeSum = 0f
                    var nearestIdx = 0

                    for (oct in 0 until octaves) {
                        val opx = octPx[oct]; val opy = octPy[oct]
                        val gh = octGh[oct]; val gn = octGn[oct]
                        val gc = octGc[oct]; val gcM1 = octGcM1[oct]; val grM1 = octGrM1[oct]
                        val invGs = octInvGs[oct]

                        val gxv = minOf((x * invGs).toInt(), gcM1)
                        val gyv = minOf((y * invGs).toInt(), grM1)
                        val cyMin = maxOf(gyv - 1, 0); val cyMax = minOf(gyv + 1, grM1)
                        val cxMin = maxOf(gxv - 1, 0); val cxMax = minOf(gxv + 1, gcM1)

                        var f1 = Float.MAX_VALUE; var f2 = Float.MAX_VALUE; var ni = 0
                        for (cy in cyMin..cyMax) {
                            val ro = cy * gc
                            for (cx in cxMin..cxMax) {
                                var ii = gh[ro + cx]
                                while (ii >= 0) {
                                    val dx = x - opx[ii]; val dy = y - opy[ii]
                                    val d = dx * dx + dy * dy
                                    if (d < f1) { f2 = f1; f1 = d; ni = ii }
                                    else if (d < f2) { f2 = d }
                                    ii = gn[ii]
                                }
                            }
                        }

                        val edgeDist = sqrt(f2) - sqrt(f1)
                        val normalized = (edgeDist * octInvMaxEd[oct]).coerceIn(0f, 1f)
                        ridgeSum += (1f - normalized) * octAmp[oct]
                        if (oct == 0) nearestIdx = ni
                    }

                    val ridgeNorm = (ridgeSum * invAmpSum).coerceIn(0f, 1f)
                    val ridgeIntensity = sharpLut[(ridgeNorm * 255f).toInt().coerceIn(0, 255)]
                    val intensityIdx = (ridgeIntensity * 255f).toInt().coerceIn(0, 255)
                    pixels[rowOff + col] = colorLuts[nearestIdx % colorsSize][intensityIdx]
                }
            }
        } else if (metricId == 1) {
            // ── MANHATTAN PATH ──
            for (row in 0 until rh) {
                val y = row.toFloat() * scaleF
                val rowOff = row * rw
                for (col in 0 until rw) {
                    val x = col.toFloat() * scaleF
                    var ridgeSum = 0f
                    var nearestIdx = 0

                    for (oct in 0 until octaves) {
                        val opx = octPx[oct]; val opy = octPy[oct]
                        val gh = octGh[oct]; val gn = octGn[oct]
                        val gc = octGc[oct]; val gcM1 = octGcM1[oct]; val grM1 = octGrM1[oct]
                        val invGs = octInvGs[oct]

                        val gxv = minOf((x * invGs).toInt(), gcM1)
                        val gyv = minOf((y * invGs).toInt(), grM1)
                        val cyMin = maxOf(gyv - 1, 0); val cyMax = minOf(gyv + 1, grM1)
                        val cxMin = maxOf(gxv - 1, 0); val cxMax = minOf(gxv + 1, gcM1)

                        var f1 = Float.MAX_VALUE; var f2 = Float.MAX_VALUE; var ni = 0
                        for (cy in cyMin..cyMax) {
                            val ro = cy * gc
                            for (cx in cxMin..cxMax) {
                                var ii = gh[ro + cx]
                                while (ii >= 0) {
                                    val dx = x - opx[ii]; val dy = y - opy[ii]
                                    val adx = if (dx < 0f) -dx else dx
                                    if (adx < f1) {
                                        val ady = if (dy < 0f) -dy else dy
                                        val d = adx + ady
                                        if (d < f1) { f2 = f1; f1 = d; ni = ii }
                                        else if (d < f2) { f2 = d }
                                    }
                                    ii = gn[ii]
                                }
                            }
                        }

                        val normalized = ((f2 - f1) * octInvMaxEd[oct]).coerceIn(0f, 1f)
                        ridgeSum += (1f - normalized) * octAmp[oct]
                        if (oct == 0) nearestIdx = ni
                    }

                    val ridgeNorm = (ridgeSum * invAmpSum).coerceIn(0f, 1f)
                    val ridgeIntensity = sharpLut[(ridgeNorm * 255f).toInt().coerceIn(0, 255)]
                    val intensityIdx = (ridgeIntensity * 255f).toInt().coerceIn(0, 255)
                    pixels[rowOff + col] = colorLuts[nearestIdx % colorsSize][intensityIdx]
                }
            }
        } else {
            // ── CHEBYSHEV PATH ──
            for (row in 0 until rh) {
                val y = row.toFloat() * scaleF
                val rowOff = row * rw
                for (col in 0 until rw) {
                    val x = col.toFloat() * scaleF
                    var ridgeSum = 0f
                    var nearestIdx = 0

                    for (oct in 0 until octaves) {
                        val opx = octPx[oct]; val opy = octPy[oct]
                        val gh = octGh[oct]; val gn = octGn[oct]
                        val gc = octGc[oct]; val gcM1 = octGcM1[oct]; val grM1 = octGrM1[oct]
                        val invGs = octInvGs[oct]

                        val gxv = minOf((x * invGs).toInt(), gcM1)
                        val gyv = minOf((y * invGs).toInt(), grM1)
                        val cyMin = maxOf(gyv - 1, 0); val cyMax = minOf(gyv + 1, grM1)
                        val cxMin = maxOf(gxv - 1, 0); val cxMax = minOf(gxv + 1, gcM1)

                        var f1 = Float.MAX_VALUE; var f2 = Float.MAX_VALUE; var ni = 0
                        for (cy in cyMin..cyMax) {
                            val ro = cy * gc
                            for (cx in cxMin..cxMax) {
                                var ii = gh[ro + cx]
                                while (ii >= 0) {
                                    val dx = x - opx[ii]; val dy = y - opy[ii]
                                    val adx = if (dx < 0f) -dx else dx
                                    if (adx < f1) {
                                        val ady = if (dy < 0f) -dy else dy
                                        val d = if (adx > ady) adx else ady
                                        if (d < f1) { f2 = f1; f1 = d; ni = ii }
                                        else if (d < f2) { f2 = d }
                                    }
                                    ii = gn[ii]
                                }
                            }
                        }

                        val normalized = ((f2 - f1) * octInvMaxEd[oct]).coerceIn(0f, 1f)
                        ridgeSum += (1f - normalized) * octAmp[oct]
                        if (oct == 0) nearestIdx = ni
                    }

                    val ridgeNorm = (ridgeSum * invAmpSum).coerceIn(0f, 1f)
                    val ridgeIntensity = sharpLut[(ridgeNorm * 255f).toInt().coerceIn(0, 255)]
                    val intensityIdx = (ridgeIntensity * 255f).toInt().coerceIn(0, 255)
                    pixels[rowOff + col] = colorLuts[nearestIdx % colorsSize][intensityIdx]
                }
            }
        }

        // ── Output with bilinear upscale ──
        if (scale > 1) {
            val smallBmp = Bitmap.createBitmap(rw, rh, Bitmap.Config.ARGB_8888)
            smallBmp.setPixels(pixels, 0, rw, 0, 0, rw, rh)
            canvas.drawBitmap(smallBmp, null, Rect(0, 0, w, h), Paint(Paint.FILTER_BITMAP_FLAG))
            smallBmp.recycle()
        } else {
            bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
            canvas.drawBitmap(bitmap, 0f, 0f, null)
        }
    }

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float {
        val n = (params["cellCount"] as? Number)?.toFloat() ?: 50f
        return (n / 150f).coerceIn(0.3f, 1f)
    }
}
