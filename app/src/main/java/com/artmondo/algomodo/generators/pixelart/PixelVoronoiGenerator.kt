package com.artmondo.algomodo.generators.pixelart

import android.graphics.Bitmap
import android.graphics.Canvas
import com.artmondo.algomodo.core.rng.SeededRNG
import com.artmondo.algomodo.data.palettes.Palette
import com.artmondo.algomodo.generators.Generator
import com.artmondo.algomodo.generators.ParamGroup
import com.artmondo.algomodo.generators.Parameter
import com.artmondo.algomodo.generators.Quality
import kotlin.math.abs
import kotlin.math.max

class PixelVoronoiGenerator : Generator {
    override val id = "pixel-voronoi"
    override val family = "pixel-art"
    override val styleName = "Pixel Voronoi"
    override val definition = "Voronoi diagram on a coarse pixel grid — a chunky stained-glass or territory-map look with optional cell borders."
    override val algorithmNotes = "Scatters seed points on a small grid and assigns each pixel the color of its nearest seed. Supports Euclidean, Manhattan, and Chebyshev metrics. Optional borders drawn where the two closest seeds are nearly equidistant."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema = listOf(
        Parameter.NumberParam("Number of Seeds", "numSeeds", ParamGroup.COMPOSITION, "Number of Voronoi seed points", 4f, 64f, 1f, 12f),
        Parameter.NumberParam("Grid Size", "gridSize", ParamGroup.GEOMETRY, "Pixel grid resolution", 32f, 128f, 8f, 64f),
        Parameter.BooleanParam("Show Borders", "showBorders", ParamGroup.GEOMETRY, "Draw cell borders in a darker shade", true),
        Parameter.NumberParam("Border Width", "borderWidth", ParamGroup.GEOMETRY, "Thickness of cell borders", 1f, 4f, 1f, 1f),
        Parameter.SelectParam("Distance Metric", "metric", ParamGroup.COMPOSITION, "Distance function for nearest-seed lookup", listOf("euclidean", "manhattan", "chebyshev"), "euclidean"),
        Parameter.NumberParam("Drift Amount", "driftAmount", ParamGroup.FLOW_MOTION, "How much seeds move during animation", 0f, 5f, 0.5f, 1f),
        Parameter.NumberParam("Anim Speed", "animSpeed", ParamGroup.FLOW_MOTION, "Animation speed multiplier", 0.1f, 3f, 0.1f, 1f),
        Parameter.NumberParam("Reactivity", "reactivity", ParamGroup.FLOW_MOTION, "Audio reactivity strength", 0f, 2f, 0.1f, 0f)
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "numSeeds" to 12f, "gridSize" to 64f, "showBorders" to true, "borderWidth" to 1f,
        "metric" to "euclidean", "driftAmount" to 1f, "animSpeed" to 1f, "reactivity" to 0f
    )

    companion object {
        @Volatile private var animKey: String = ""
        private var sx: DoubleArray = DoubleArray(0)
        private var sy: DoubleArray = DoubleArray(0)
        private var vx: DoubleArray = DoubleArray(0)
        private var vy: DoubleArray = DoubleArray(0)
    }

    private fun dist(x1: Float, y1: Float, x2: Double, y2: Double, metric: String): Float {
        val dx = x1 - x2.toFloat(); val dy = y1 - y2.toFloat()
        return when (metric) {
            "manhattan" -> abs(dx) + abs(dy)
            "chebyshev" -> max(abs(dx), abs(dy))
            else -> dx * dx + dy * dy
        }
    }

    override fun renderCanvas(
        canvas: Canvas, bitmap: Bitmap, params: Map<String, Any>, seed: Int,
        palette: Palette, quality: Quality, time: Float
    ) {
        val sz = PixelArtUtil.pi(params, "gridSize", 64).coerceIn(16, 128)
        val numSeeds = PixelArtUtil.pi(params, "numSeeds", 12).coerceIn(2, 64)
        val metric = PixelArtUtil.ps(params, "metric", "euclidean")
        val showBorders = PixelArtUtil.pb(params, "showBorders", true)
        val borderWidth = PixelArtUtil.pi(params, "borderWidth", 1).coerceIn(1, 4)
        val drift = PixelArtUtil.pf(params, "driftAmount", 1f)
        val speed = PixelArtUtil.pf(params, "animSpeed", 1f)

        val colors = PixelArtUtil.paletteRgb(palette)
        val nc = colors.size

        // Init seeds
        val sxL: DoubleArray; val syL: DoubleArray; val vxL: DoubleArray; val vyL: DoubleArray
        val key = "$seed|$sz|$numSeeds"
        if (time == 0f) {
            val rng = SeededRNG(seed)
            sxL = DoubleArray(numSeeds); syL = DoubleArray(numSeeds)
            vxL = DoubleArray(numSeeds); vyL = DoubleArray(numSeeds)
            for (i in 0 until numSeeds) {
                sxL[i] = rng.random().toDouble() * sz
                syL[i] = rng.random().toDouble() * sz
                vxL[i] = (rng.random() - 0.5).toDouble() * 2.0
                vyL[i] = (rng.random() - 0.5).toDouble() * 2.0
            }
        } else {
            synchronized(Companion) {
                if (animKey != key || sx.size != numSeeds) {
                    val rng = SeededRNG(seed)
                    sx = DoubleArray(numSeeds); sy = DoubleArray(numSeeds)
                    vx = DoubleArray(numSeeds); vy = DoubleArray(numSeeds)
                    for (i in 0 until numSeeds) {
                        sx[i] = rng.random().toDouble() * sz
                        sy[i] = rng.random().toDouble() * sz
                        vx[i] = (rng.random() - 0.5).toDouble() * 2.0
                        vy[i] = (rng.random() - 0.5).toDouble() * 2.0
                    }
                    animKey = key
                }
                // Update positions
                val dt = speed * 0.016 * drift
                for (i in 0 until numSeeds) {
                    sx[i] += vx[i] * dt
                    sy[i] += vy[i] * dt
                    if (sx[i] < 0) { sx[i] = 0.0; vx[i] *= -1 }
                    if (sx[i] >= sz) { sx[i] = sz - 0.01; vx[i] *= -1 }
                    if (sy[i] < 0) { sy[i] = 0.0; vy[i] *= -1 }
                    if (sy[i] >= sz) { sy[i] = sz - 0.01; vy[i] *= -1 }
                }
            }
            sxL = sx; syL = sy; vxL = vx; vyL = vy
        }

        val pixels = IntArray(sz * sz)
        for (y in 0 until sz) {
            for (x in 0 until sz) {
                var minD = Float.MAX_VALUE; var minD2 = Float.MAX_VALUE
                var nearest = 0
                for (s in 0 until numSeeds) {
                    val dd = dist(x.toFloat(), y.toFloat(), sxL[s], syL[s], metric)
                    if (dd < minD) { minD2 = minD; minD = dd; nearest = s }
                    else if (dd < minD2) minD2 = dd
                }
                val idx = y * sz + x
                if (showBorders) {
                    val borderThreshold = if (metric == "euclidean") (borderWidth * borderWidth * 4).toFloat() else (borderWidth * 2).toFloat()
                    if (minD2 - minD < borderThreshold) {
                        val c = colors[0]
                        pixels[idx] = PixelArtUtil.rgb((c[0] * 0.3f).toInt(), (c[1] * 0.3f).toInt(), (c[2] * 0.3f).toInt())
                        continue
                    }
                }
                val c = colors[nearest % nc]
                pixels[idx] = PixelArtUtil.rgb(c[0], c[1], c[2])
            }
        }
        PixelArtUtil.blitNearest(canvas, bitmap, pixels, sz)
    }
}
