package com.artmondo.algomodo.generators.animation

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import com.artmondo.algomodo.core.rng.SeededRNG
import com.artmondo.algomodo.data.palettes.Palette
import com.artmondo.algomodo.generators.Generator
import com.artmondo.algomodo.generators.ParamGroup
import com.artmondo.algomodo.generators.Parameter
import com.artmondo.algomodo.generators.Quality
import kotlin.math.*

/**
 * Strange attractor visualisation using 2D iterative maps with histogram rendering.
 *
 * Iterates a chosen 2D map attractor (Clifford, De Jong, Bedhead, Svensson, Tinkerbell)
 * thousands of times, accumulating hit counts in a density histogram. The histogram is
 * tone-mapped with a log curve and mapped to the palette, producing the classic nebulous
 * attractor aesthetic. Parameters oscillate over time for animation.
 */
class AttractorTrailsGenerator : Generator {

    override val id = "attractor-trails"
    override val family = "animation"
    override val styleName = "Attractor Trails"
    override val definition = "Particles tracing strange attractor trajectories, projected to 2D with coloured trails."
    override val algorithmNotes =
        "2D iterative map attractors (Clifford, De Jong, etc.) are iterated many thousands of " +
        "times. Each iteration produces a new (x,y) point which is mapped to a pixel. A density " +
        "histogram accumulates hit counts per pixel. The histogram is tone-mapped using " +
        "log(1 + count * brightness) and mapped to palette.lerpColor(). Attractor parameters " +
        "oscillate sinusoidally over time at seeded frequencies for animation."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema = listOf(
        Parameter.SelectParam(
            name = "Attractor Type",
            key = "attractorType",
            group = ParamGroup.COMPOSITION,
            help = "clifford / dejong / bedhead: classic sine-based maps · svensson: flame-like variant · tinkerbell: quadratic map with different topology",
            options = listOf("clifford", "dejong", "bedhead", "svensson", "tinkerbell"),
            default = "clifford"
        ),
        Parameter.NumberParam(
            name = "Iterations (×1000)",
            key = "iterations",
            group = ParamGroup.COMPOSITION,
            help = "More iterations = denser histogram; lower values improve animation frame-rate",
            min = 100f, max = 2000f, step = 100f, default = 800f
        ),
        Parameter.NumberParam(
            name = "Brightness",
            key = "brightness",
            group = ParamGroup.TEXTURE,
            help = "Log tone-map exponent — higher lifts dim regions brighter but clips peaks",
            min = 0.5f, max = 4f, step = 0.1f, default = 1.5f
        ),
        Parameter.SelectParam(
            name = "Color Mode",
            key = "colorMode",
            group = ParamGroup.COLOR,
            help = "density: brightness→palette gradient · velocity: local speed · angle: movement direction · multi: overlapping offset layers, each in a distinct palette color",
            options = listOf("density", "velocity", "angle", "multi"),
            default = "density"
        ),
        Parameter.NumberParam(
            name = "Color Shift",
            key = "colorShift",
            group = ParamGroup.COLOR,
            help = "Slide the palette lookup slowly over time — animates colour bands without changing the attractor shape",
            min = 0f, max = 1f, step = 0.05f, default = 0f
        ),
        Parameter.NumberParam(
            name = "Point Size",
            key = "pointSize",
            group = ParamGroup.GEOMETRY,
            help = null,
            min = 1f, max = 4f, step = 1f, default = 1f
        ),
        Parameter.NumberParam(
            name = "Drift Speed",
            key = "driftSpeed",
            group = ParamGroup.FLOW_MOTION,
            help = "How fast the attractor parameters oscillate over time — each at a distinct seeded frequency",
            min = 0f, max = 1.0f, step = 0.05f, default = 0.2f
        ),
        Parameter.NumberParam(
            name = "Drift Amplitude",
            key = "driftAmp",
            group = ParamGroup.FLOW_MOTION,
            help = "Maximum ±offset on each parameter during animation — larger = more extreme morphing",
            min = 0f, max = 0.5f, step = 0.02f, default = 0.15f
        )
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "attractorType" to "clifford",
        "iterations" to 800f,
        "brightness" to 1.5f,
        "colorMode" to "density",
        "colorShift" to 0f,
        "pointSize" to 1f,
        "driftSpeed" to 0.2f,
        "driftAmp" to 0.15f
    )

    /**
     * Base parameters for each attractor type.
     * Returns (a, b, c, d) coefficients and the expected output range for mapping.
     */
    private fun baseParams(type: String, rng: SeededRNG): DoubleArray = when (type) {
        "clifford" -> doubleArrayOf(
            -1.4 + rng.random() * 0.2, 1.6 + rng.random() * 0.2,
            1.0 + rng.random() * 0.2, 0.7 + rng.random() * 0.2
        )
        "dejong" -> doubleArrayOf(
            -2.0 + rng.random() * 0.3, 2.0 + rng.random() * 0.3,
            -1.2 + rng.random() * 0.3, 2.0 + rng.random() * 0.3
        )
        "bedhead" -> doubleArrayOf(
            -0.81 + rng.random() * 0.1, -0.92 + rng.random() * 0.1,
            0.0, 0.0
        )
        "svensson" -> doubleArrayOf(
            1.4 + rng.random() * 0.2, 1.56 + rng.random() * 0.2,
            1.4 + rng.random() * 0.2, -6.56 + rng.random() * 0.2
        )
        "tinkerbell" -> doubleArrayOf(
            0.9 + rng.random() * 0.05, -0.6013 + rng.random() * 0.05,
            2.0 + rng.random() * 0.05, 0.5 + rng.random() * 0.05
        )
        else -> doubleArrayOf(-1.4, 1.6, 1.0, 0.7)
    }

    /** Iterate one step of the chosen attractor map. */
    private fun iterateMap(
        type: String,
        x: Double, y: Double,
        a: Double, b: Double, c: Double, d: Double
    ): Pair<Double, Double> = when (type) {
        "clifford" -> Pair(
            sin(a * y) + c * cos(a * x),
            sin(b * x) + d * cos(b * y)
        )
        "dejong" -> Pair(
            sin(a * y) - cos(b * x),
            sin(c * x) - cos(d * y)
        )
        "bedhead" -> Pair(
            sin(x * y / b) + cos(a * x - y),
            x + sin(y) / b
        )
        "svensson" -> Pair(
            d * sin(a * x) - sin(b * y),
            c * cos(a * x) + cos(b * y)
        )
        "tinkerbell" -> Pair(
            x * x - y * y + a * x + b * y,
            2.0 * x * y + c * x + d * y
        )
        else -> Pair(
            sin(a * y) + c * cos(a * x),
            sin(b * x) + d * cos(b * y)
        )
    }

    /** Expected coordinate range for mapping to canvas. */
    private fun coordRange(type: String): Float = when (type) {
        "clifford" -> 2.8f
        "dejong" -> 2.5f
        "bedhead" -> 3.5f
        "svensson" -> 3.0f
        "tinkerbell" -> 2.5f
        else -> 3.0f
    }

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
        val dim = min(w, h)

        val attractorType = (params["attractorType"] as? String) ?: "clifford"
        val iterationsK = ((params["iterations"] as? Number)?.toInt() ?: 800).let {
            when (quality) {
                Quality.DRAFT -> (it * 0.4f).toInt()
                Quality.BALANCED -> it
                Quality.ULTRA -> (it * 1.5f).toInt()
            }
        }
        val totalIterations = iterationsK * 1000
        val brightness = (params["brightness"] as? Number)?.toFloat() ?: 1.5f
        val colorMode = (params["colorMode"] as? String) ?: "density"
        val colorShift = (params["colorShift"] as? Number)?.toFloat() ?: 0f
        val pointSize = (params["pointSize"] as? Number)?.toInt() ?: 1
        val driftSpeed = (params["driftSpeed"] as? Number)?.toFloat() ?: 0.2f
        val driftAmp = (params["driftAmp"] as? Number)?.toFloat() ?: 0.15f

        val rng = SeededRNG(seed)
        val colors = palette.colorInts()

        // Generate seeded base parameters
        val bp = baseParams(attractorType, rng)

        // Seeded oscillation frequencies for parameter drift
        val freqs = DoubleArray(4) { 0.3 + rng.random() * 0.7 }

        // Apply time-based drift to parameters
        val a = bp[0] + driftAmp * sin(time.toDouble() * driftSpeed * freqs[0] * 2.0 * PI)
        val b = bp[1] + driftAmp * sin(time.toDouble() * driftSpeed * freqs[1] * 2.0 * PI + 1.0)
        val c = bp[2] + driftAmp * sin(time.toDouble() * driftSpeed * freqs[2] * 2.0 * PI + 2.0)
        val d = bp[3] + driftAmp * sin(time.toDouble() * driftSpeed * freqs[3] * 2.0 * PI + 3.0)

        val range = coordRange(attractorType)
        val cx = w / 2f
        val cy = h / 2f
        val scale = dim / (2f * range)

        // Histogram for density
        val histogram = IntArray(w * h)
        // Angle/velocity buffers for color modes
        val angleMap = if (colorMode == "angle") FloatArray(w * h) else null
        val velocityMap = if (colorMode == "velocity") FloatArray(w * h) else null

        // Iterate the attractor
        var x = 0.5
        var y = 0.5
        // Warm up to get on the attractor
        for (i in 0 until 200) {
            val (nx, ny) = iterateMap(attractorType, x, y, a, b, c, d)
            x = nx; y = ny
            if (x.isNaN() || y.isNaN() || x.isInfinite() || y.isInfinite()) {
                x = 0.1; y = 0.1
            }
        }

        var prevX = x
        var prevY = y

        for (i in 0 until totalIterations) {
            val (nx, ny) = iterateMap(attractorType, x, y, a, b, c, d)

            if (nx.isNaN() || ny.isNaN() || nx.isInfinite() || ny.isInfinite()) {
                x = 0.1; y = 0.1
                prevX = x; prevY = y
                continue
            }

            prevX = x; prevY = y
            x = nx; y = ny

            // Map to pixel coordinates
            val px = (cx + x.toFloat() * scale).toInt()
            val py = (cy + y.toFloat() * scale).toInt()

            if (px in 0 until w && py in 0 until h) {
                val idx = py * w + px
                histogram[idx]++

                // Splat for point size > 1
                if (pointSize > 1) {
                    for (dy in -pointSize / 2..pointSize / 2) {
                        for (dx in -pointSize / 2..pointSize / 2) {
                            val sx = px + dx
                            val sy = py + dy
                            if (sx in 0 until w && sy in 0 until h) {
                                histogram[sy * w + sx]++
                            }
                        }
                    }
                }

                angleMap?.let {
                    it[idx] = atan2((y - prevY).toFloat(), (x - prevX).toFloat())
                }
                velocityMap?.let {
                    val vx = (x - prevX).toFloat()
                    val vy = (y - prevY).toFloat()
                    it[idx] = sqrt(vx * vx + vy * vy)
                }
            }
        }

        // Find max for tone mapping
        var maxCount = 1
        for (c in histogram) if (c > maxCount) maxCount = c

        // Tone-map and render to pixels
        val pixels = IntArray(w * h)
        val logMax = ln(1f + maxCount * brightness)
        val timeShift = time * colorShift

        when (colorMode) {
            "multi" -> {
                // Multi-layer: run the attractor multiple times with slight offsets,
                // each layer gets a different palette color
                val layerCount = minOf(colors.size, 4)
                val layerR = IntArray(w * h)
                val layerG = IntArray(w * h)
                val layerB = IntArray(w * h)

                for (layer in 0 until layerCount) {
                    val layerHist = IntArray(w * h)
                    val la = a + layer * 0.05
                    val lb = b + layer * 0.03

                    var lx = 0.5 + layer * 0.1
                    var ly = 0.5 + layer * 0.1
                    for (wi in 0 until 200) {
                        val (nnx, nny) = iterateMap(attractorType, lx, ly, la, lb, c, d)
                        lx = nnx; ly = nny
                        if (lx.isNaN() || ly.isNaN()) { lx = 0.1; ly = 0.1 }
                    }

                    val layerIter = totalIterations / layerCount
                    for (i in 0 until layerIter) {
                        val (nnx, nny) = iterateMap(attractorType, lx, ly, la, lb, c, d)
                        if (nnx.isNaN() || nny.isNaN()) { lx = 0.1; ly = 0.1; continue }
                        lx = nnx; ly = nny
                        val lpx = (cx + lx.toFloat() * scale).toInt()
                        val lpy = (cy + ly.toFloat() * scale).toInt()
                        if (lpx in 0 until w && lpy in 0 until h) {
                            layerHist[lpy * w + lpx]++
                        }
                    }

                    var layerMax = 1
                    for (v in layerHist) if (v > layerMax) layerMax = v
                    val lLogMax = ln(1f + layerMax * brightness)

                    val baseColor = colors[layer % colors.size]
                    val cr = Color.red(baseColor)
                    val cg = Color.green(baseColor)
                    val cb = Color.blue(baseColor)

                    for (j in layerHist.indices) {
                        if (layerHist[j] > 0) {
                            val intensity = (ln(1f + layerHist[j] * brightness) / lLogMax).coerceIn(0f, 1f)
                            layerR[j] += (cr * intensity).toInt()
                            layerG[j] += (cg * intensity).toInt()
                            layerB[j] += (cb * intensity).toInt()
                        }
                    }
                }

                for (j in pixels.indices) {
                    val r = layerR[j].coerceAtMost(255)
                    val g = layerG[j].coerceAtMost(255)
                    val b2 = layerB[j].coerceAtMost(255)
                    pixels[j] = if (r > 0 || g > 0 || b2 > 0) Color.rgb(r, g, b2)
                    else Color.rgb(4, 4, 8)
                }
            }
            else -> {
                for (j in pixels.indices) {
                    if (histogram[j] > 0) {
                        val intensity = (ln(1f + histogram[j] * brightness) / logMax).coerceIn(0f, 1f)

                        val palVal = when (colorMode) {
                            "angle" -> {
                                val ang = angleMap?.get(j) ?: 0f
                                ((ang / (2f * PI.toFloat()) + 0.5f + timeShift) % 1f + 1f) % 1f
                            }
                            "velocity" -> {
                                val vel = velocityMap?.get(j) ?: 0f
                                ((vel * 2f + timeShift) % 1f + 1f) % 1f
                            }
                            else -> { // density
                                ((intensity + timeShift) % 1f + 1f) % 1f
                            }
                        }

                        val baseColor = palette.lerpColor(palVal)
                        val r = (Color.red(baseColor) * intensity).toInt().coerceIn(0, 255)
                        val g = (Color.green(baseColor) * intensity).toInt().coerceIn(0, 255)
                        val b2 = (Color.blue(baseColor) * intensity).toInt().coerceIn(0, 255)
                        pixels[j] = Color.rgb(r, g, b2)
                    } else {
                        pixels[j] = Color.rgb(4, 4, 8)
                    }
                }
            }
        }

        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
        canvas.drawBitmap(bitmap, 0f, 0f, null)
    }

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float {
        val iterations = (params["iterations"] as? Number)?.toFloat() ?: 800f
        return when (quality) {
            Quality.DRAFT -> iterations * 0.4f / 2000f
            Quality.BALANCED -> iterations / 2000f
            Quality.ULTRA -> iterations * 1.5f / 2000f
        }.coerceIn(0.1f, 1f)
    }
}
