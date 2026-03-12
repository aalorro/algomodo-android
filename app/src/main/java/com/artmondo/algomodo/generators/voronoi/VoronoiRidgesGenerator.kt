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
import kotlin.math.max
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

        val noise = SimplexNoise(seed)

        // Average cell spacing used as animation drift reference
        val avgCellSize = sqrt((w * h).toFloat() / numPoints)

        // Generate seed points for each octave. Each octave has more points
        // (numPoints * lacunarity^oct) to create finer detail, with its own
        // deterministic RNG stream offset by octave index.
        data class OctaveData(
            val px: FloatArray,
            val py: FloatArray,
            val count: Int,
            val amplitude: Float,
            val maxEdgeDist: Float
        )

        val octaveDataList = ArrayList<OctaveData>(octaves)

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

            // Animate seed points
            if (time > 0f) {
                val driftRange = animAmp * avgCellSize / freq
                for (i in 0 until octPoints) {
                    opx[i] += noise.noise2D(
                        i * 0.3f + 70f + oct * 100f,
                        time * animSpeed
                    ) * driftRange
                    opy[i] += noise.noise2D(
                        i * 0.3f + 170f + oct * 100f,
                        time * animSpeed
                    ) * driftRange
                    opx[i] = opx[i].coerceIn(0f, w.toFloat() - 1f)
                    opy[i] = opy[i].coerceIn(0f, h.toFloat() - 1f)
                }
            }

            // Sample max edge distance for normalization of this octave
            var maxEd = 1f
            val sampleRng = SeededRNG(seed + oct * 3571)
            for (s in 0 until 80) {
                val sx = sampleRng.random() * w
                val sy = sampleRng.random() * h
                val r = findF1F2WithIndex(sx, sy, opx, opy, octPoints, distMetric)
                val ed = r.f2 - r.f1
                if (ed > maxEd) maxEd = ed
            }

            octaveDataList.add(OctaveData(opx, opy, octPoints, amp, maxEd))
        }

        val pixels = IntArray(w * h)
        val colors = palette.colorInts()

        val step = when (quality) {
            Quality.DRAFT -> 2
            Quality.BALANCED -> 1
            Quality.ULTRA -> 1
        }

        for (row in 0 until h step step) {
            for (col in 0 until w step step) {
                // Accumulate ridge value across octaves (fBm-style)
                var ridgeSum = 0f
                var ampSum = 0f
                var nearestIdx = 0 // from the base (first) octave for color

                for (oct in 0 until octaves) {
                    val od = octaveDataList[oct]
                    val result = findF1F2WithIndex(
                        col.toFloat(), row.toFloat(),
                        od.px, od.py, od.count, distMetric
                    )
                    val edgeDist = result.f2 - result.f1
                    val normalized = (edgeDist / od.maxEdgeDist).coerceIn(0f, 1f)
                    ridgeSum += (1f - normalized) * od.amplitude
                    ampSum += od.amplitude
                    if (oct == 0) nearestIdx = result.nearestIdx
                }

                // Normalize accumulated value and apply sharpness curve
                val ridgeNorm = (ridgeSum / ampSum).coerceIn(0f, 1f)
                val ridgeIntensity = ridgeNorm.pow(ridgeSharpness)

                val color = when (style) {
                    "greyscale" -> {
                        val v = (ridgeIntensity * 255f).toInt().coerceIn(0, 255)
                        Color.rgb(v, v, v)
                    }
                    "inverted" -> {
                        val baseColor = colors[nearestIdx % colors.size]
                        val inv = 1f - ridgeIntensity
                        val r = (Color.red(baseColor) * inv).toInt().coerceIn(0, 255)
                        val g = (Color.green(baseColor) * inv).toInt().coerceIn(0, 255)
                        val b = (Color.blue(baseColor) * inv).toInt().coerceIn(0, 255)
                        Color.rgb(r, g, b)
                    }
                    else -> {
                        // "palette" mode
                        val baseColor = colors[nearestIdx % colors.size]
                        val r = (Color.red(baseColor) * ridgeIntensity).toInt().coerceIn(0, 255)
                        val g = (Color.green(baseColor) * ridgeIntensity).toInt().coerceIn(0, 255)
                        val b = (Color.blue(baseColor) * ridgeIntensity).toInt().coerceIn(0, 255)
                        Color.rgb(r, g, b)
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

    private fun findF1F2WithIndex(
        x: Float, y: Float,
        px: FloatArray, py: FloatArray,
        numPoints: Int,
        metric: String
    ): F1F2Result {
        var f1 = Float.MAX_VALUE
        var f2 = Float.MAX_VALUE
        var nearestIdx = 0

        for (i in 0 until numPoints) {
            val dx = x - px[i]
            val dy = y - py[i]
            val d = when (metric) {
                "manhattan" -> abs(dx) + abs(dy)
                "chebyshev" -> max(abs(dx), abs(dy))
                else -> sqrt(dx * dx + dy * dy) // euclidean
            }
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
        val n = (params["cellCount"] as? Number)?.toFloat() ?: 50f
        return (n / 150f).coerceIn(0.3f, 1f)
    }
}
