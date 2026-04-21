package com.artmondo.algomodo.generators.image

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import com.artmondo.algomodo.core.rng.SeededRNG
import com.artmondo.algomodo.data.palettes.Palette
import com.artmondo.algomodo.generators.Generator
import com.artmondo.algomodo.generators.ParamGroup
import com.artmondo.algomodo.generators.Parameter
import com.artmondo.algomodo.generators.Quality
import kotlin.math.*

/**
 * Weighted Voronoi relaxation (Lloyd's algorithm) on an uploaded image's
 * luminance. Sites migrate toward the weighted centroid of their Voronoi
 * region, where weight = (1 - luminance)^contrast (dark → attractive).
 * After k iterations, site density approximates image tone.
 */
class StipplePortraitGenerator : Generator {

    override val id = "stipple-portrait"
    override val family = "image"
    override val styleName = "Stipple Portrait"
    override val definition =
        "Weighted Voronoi relaxation (Lloyd's algorithm) approximates your uploaded image with stipple dots."
    override val algorithmNotes =
        "Density field ρ(x,y) = (1 - luminance)^contrast. Sites placed via rejection sampling, " +
        "then refined by Lloyd's weighted-centroid iteration on a low-res integration grid. " +
        "Dot radius tracks local density, cell area (inverse density), or stays uniform. " +
        "Classic Secord 2002 formulation."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema = listOf(
        Parameter.NumberParam("Point Count", "pointCount", ParamGroup.COMPOSITION, "Number of stipple dots — more = higher fidelity, slower", 500f, 20000f, 500f, 6000f),
        Parameter.NumberParam("Relaxation", "iterations", ParamGroup.COMPOSITION, "Lloyd's weighted-centroid iterations — higher = smoother spacing", 3f, 30f, 1f, 8f),
        Parameter.NumberParam("Integration Res", "gridRes", ParamGroup.COMPOSITION, "Resolution of weight-integration grid — higher = more accurate, slower", 160f, 500f, 20f, 240f),
        Parameter.NumberParam("Contrast", "densityContrast", ParamGroup.TEXTURE, "Density exponent — higher sharpens dark/bright separation", 0.8f, 3f, 0.1f, 1.6f),
        Parameter.BooleanParam("Voronoi Edges", "showEdges", ParamGroup.TEXTURE, "Overlay thin Voronoi cell outlines", false),
        Parameter.NumberParam("Min Dot", "minDotSize", ParamGroup.GEOMETRY, "Dot radius in light (sparse) regions", 0.8f, 3f, 0.1f, 1f),
        Parameter.NumberParam("Max Dot", "maxDotSize", ParamGroup.GEOMETRY, "Dot radius in dense (dark) regions", 2.5f, 8f, 0.25f, 3.5f),
        Parameter.SelectParam("Dot Size Mode", "dotSizeMode", ParamGroup.GEOMETRY, "uniform: all same | density: follows image | cell-area: inverse density", listOf("uniform", "density", "cell-area"), "density"),
        Parameter.SelectParam("Dot Shape", "dotShape", ParamGroup.GEOMETRY, "circle | square | diamond", listOf("circle", "square", "diamond"), "circle"),
        Parameter.SelectParam("Color Mode", "colorMode", ParamGroup.COLOR, "mono: one color | sample: image color per dot | palette: palette by density", listOf("mono", "sample", "palette-density"), "sample"),
        Parameter.SelectParam("Background", "background", ParamGroup.COLOR, "Paper color behind the stipples", listOf("white", "cream"), "white"),
        Parameter.BooleanParam("Inverse", "inverse", ParamGroup.COLOR, "Dark background with light dots", false),
        Parameter.NumberParam("Opacity", "opacity", ParamGroup.COLOR, "Dot transparency", 0.65f, 1f, 0.05f, 0.95f),
        Parameter.SelectParam("Anim Mode", "animMode", ParamGroup.FLOW_MOTION, "none: static | jitter: per-site oscillation | drift: slow noise advection", listOf("none", "jitter", "drift"), "none"),
        Parameter.NumberParam("Speed", "animSpeed", ParamGroup.FLOW_MOTION, "Animation speed", 0.05f, 2f, 0.05f, 0.3f)
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "pointCount" to 6000f,
        "iterations" to 8f,
        "gridRes" to 240f,
        "densityContrast" to 1.6f,
        "showEdges" to false,
        "minDotSize" to 1f,
        "maxDotSize" to 3.5f,
        "dotSizeMode" to "density",
        "dotShape" to "circle",
        "colorMode" to "sample",
        "background" to "white",
        "inverse" to false,
        "opacity" to 0.95f,
        "animMode" to "none",
        "animSpeed" to 0.3f
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
        val wf = w.toFloat()
        val hf = h.toFloat()

        val source = params["_sourceImage"] as? Bitmap
        if (source == null) {
            drawPlaceholder(canvas, bitmap)
            return
        }

        // ── Extract parameters ──────────────────────────────────────────────
        var n = (params["pointCount"] as? Number)?.toInt() ?: 6000
        var iterations = (params["iterations"] as? Number)?.toInt() ?: 8
        var gridRes = (params["gridRes"] as? Number)?.toInt() ?: 240
        val densityContrast = (params["densityContrast"] as? Number)?.toDouble() ?: 1.6
        val showEdges = params["showEdges"] as? Boolean ?: false
        val minDotRaw = (params["minDotSize"] as? Number)?.toFloat() ?: 1f
        val maxDotRaw = (params["maxDotSize"] as? Number)?.toFloat() ?: 3.5f
        val minDot = minOf(minDotRaw, maxDotRaw)
        val maxDot = maxOf(minDotRaw, maxDotRaw)
        val dotSizeMode = (params["dotSizeMode"] as? String) ?: "density"
        val dotShape = (params["dotShape"] as? String) ?: "circle"
        val colorMode = (params["colorMode"] as? String) ?: "sample"
        val background = (params["background"] as? String) ?: "white"
        val inverse = params["inverse"] as? Boolean ?: false
        val opacity = (params["opacity"] as? Number)?.toFloat() ?: 0.95f
        val animMode = (params["animMode"] as? String) ?: "none"
        val animSpeed = (params["animSpeed"] as? Number)?.toFloat() ?: 0.3f

        // Quality scaling for mobile performance
        when (quality) {
            Quality.DRAFT -> {
                n = (n * 0.25f).toInt().coerceAtLeast(100)
                iterations = (iterations / 3).coerceAtLeast(1)
                gridRes = (gridRes * 0.4f).toInt().coerceAtLeast(60)
            }
            Quality.BALANCED -> { /* full */ }
            Quality.ULTRA -> { /* full */ }
        }

        val rng = SeededRNG(seed)

        // ── Integration grid: downsampled luminance/density field ────────────
        val ar = wf / hf
        val gh = minOf(gridRes, ceil(gridRes / maxOf(ar, 1f)).toInt()).coerceAtLeast(1)
        val gw = minOf(gridRes, ceil(gridRes * maxOf(ar, 1f)).toInt()).coerceAtLeast(1)

        val scaled = Bitmap.createScaledBitmap(source, gw, gh, true)
        val srcPixels = IntArray(gw * gh)
        scaled.getPixels(srcPixels, 0, gw, 0, 0, gw, gh)
        if (scaled !== source) scaled.recycle()

        // Build density field
        val density = FloatArray(gw * gh)
        var maxDens = 0f
        for (i in srcPixels.indices) {
            val p = srcPixels[i]
            val lum = (0.299f * Color.red(p) + 0.587f * Color.green(p) + 0.114f * Color.blue(p)) / 255f
            val d = (1f - lum).coerceIn(0f, 1f).toDouble().pow(densityContrast).toFloat()
            density[i] = d
            if (d > maxDens) maxDens = d
        }
        if (maxDens <= 1e-6f) maxDens = 1f

        // ── Initial site placement via rejection sampling ───────────────────
        val sitesX = FloatArray(n)
        val sitesY = FloatArray(n)
        var placed = 0
        var attempts = 0
        val maxAttempts = n * 400
        while (placed < n && attempts < maxAttempts) {
            attempts++
            val u = rng.randomDouble().toFloat()
            val v = rng.randomDouble().toFloat()
            val gx = (u * gw).toInt().coerceAtMost(gw - 1)
            val gy = (v * gh).toInt().coerceAtMost(gh - 1)
            if (rng.randomDouble().toFloat() * maxDens <= density[gy * gw + gx]) {
                sitesX[placed] = u * wf
                sitesY[placed] = v * hf
                placed++
            }
        }
        // Fill remainder uniformly if density was flat
        while (placed < n) {
            sitesX[placed] = rng.randomDouble().toFloat() * wf
            sitesY[placed] = rng.randomDouble().toFloat() * hf
            placed++
        }

        // ── Lloyd's weighted relaxation iterations ──────────────────────────
        val sumX = FloatArray(n)
        val sumY = FloatArray(n)
        val sumW = FloatArray(n)
        val cellW = wf / gw
        val cellH = hf / gh

        for (iter in 0 until iterations) {
            if (Thread.currentThread().isInterrupted) return

            sumX.fill(0f); sumY.fill(0f); sumW.fill(0f)

            // Build spatial grid for nearest-neighbor lookup
            val gridCells = sqrt(n.toFloat()).toInt().coerceAtLeast(1)
            val gcW = wf / gridCells
            val gcH = hf / gridCells
            val bucketCounts = IntArray(gridCells * gridCells)
            for (i in 0 until n) {
                val bx = (sitesX[i] / gcW).toInt().coerceIn(0, gridCells - 1)
                val by = (sitesY[i] / gcH).toInt().coerceIn(0, gridCells - 1)
                bucketCounts[by * gridCells + bx]++
            }
            val bucketOffsets = IntArray(bucketCounts.size)
            for (i in 1 until bucketOffsets.size) bucketOffsets[i] = bucketOffsets[i - 1] + bucketCounts[i - 1]
            val bucketIndices = IntArray(n)
            val fillPos = bucketOffsets.copyOf()
            for (i in 0 until n) {
                val bx = (sitesX[i] / gcW).toInt().coerceIn(0, gridCells - 1)
                val by = (sitesY[i] / gcH).toInt().coerceIn(0, gridCells - 1)
                val bi = by * gridCells + bx
                bucketIndices[fillPos[bi]++] = i
            }

            // Walk integration grid — each cell contributes to its nearest site
            for (y in 0 until gh) {
                val rowOff = y * gw
                val py = (y + 0.5f) * cellH
                for (x in 0 until gw) {
                    val weight = density[rowOff + x]
                    if (weight <= 1e-5f) continue
                    val px = (x + 0.5f) * cellW
                    val si = findNearest(px, py, sitesX, sitesY, gridCells, gcW, gcH,
                        bucketCounts, bucketOffsets, bucketIndices)
                    sumX[si] += px * weight
                    sumY[si] += py * weight
                    sumW[si] += weight
                }
            }

            // Move sites to weighted centroids
            for (i in 0 until n) {
                if (sumW[i] > 1e-6f) {
                    sitesX[i] = sumX[i] / sumW[i]
                    sitesY[i] = sumY[i] / sumW[i]
                }
            }
        }

        // ── Per-site density estimation (for sizing + palette-density) ──────
        val siteDensity = FloatArray(n)
        if (iterations > 0) {
            var maxSW = 0f
            for (i in 0 until n) if (sumW[i] > maxSW) maxSW = sumW[i]
            if (maxSW <= 1e-6f) maxSW = 1f
            for (i in 0 until n) siteDensity[i] = sumW[i] / maxSW
        } else {
            for (i in 0 until n) {
                val sx = (sitesX[i] / wf).coerceIn(0f, 1f)
                val sy = (sitesY[i] / hf).coerceIn(0f, 1f)
                val gx = (sx * gw).toInt().coerceAtMost(gw - 1)
                val gy = (sy * gh).toInt().coerceAtMost(gh - 1)
                siteDensity[i] = density[gy * gw + gx] / maxDens
            }
        }

        // Cell-area inverse sizing
        var invMax = 0f
        if (dotSizeMode == "cell-area") {
            for (i in 0 until n) {
                val inv = if (sumW[i] > 1e-6f) 1f / sumW[i] else 0f
                if (inv > invMax) invMax = inv
            }
            if (invMax <= 1e-6f) invMax = 1f
        }

        // ── Background ──────────────────────────────────────────────────────
        val bgColor = if (inverse) Color.rgb(11, 11, 12)
            else if (background == "cream") Color.rgb(243, 236, 217)
            else Color.rgb(248, 248, 245)
        canvas.drawColor(bgColor)

        val bgR = Color.red(bgColor)
        val bgG = Color.green(bgColor)
        val bgB = Color.blue(bgColor)
        val bgLum = (0.299f * bgR + 0.587f * bgG + 0.114f * bgB) / 255f
        val monoR = if (inverse) 232 else 20
        val monoG = if (inverse) 228 else 18
        val monoB = if (inverse) 218 else 16

        // Animation
        val t = time * animSpeed
        val anim = animMode != "none" && time > 0f

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val alphaInt = (opacity * 255).toInt().coerceIn(0, 255)
        val path = Path()

        // ── Render each dot ─────────────────────────────────────────────────
        for (i in 0 until n) {
            var px = sitesX[i]
            var py = sitesY[i]

            // Animation perturbation
            if (anim) {
                when (animMode) {
                    "jitter" -> {
                        val ph = i * 2.39996f // golden angle spacing
                        px += cos(t * 1.8f + ph) * 1.6f
                        py += sin(t * 2.1f + ph * 1.3f) * 1.6f
                    }
                    "drift" -> {
                        // Harmonic approximation of noise advection
                        val nx = px * 0.003f + t * 0.05f
                        val ny = py * 0.003f
                        px += (sin(nx * 7.3f + ny * 3.1f) + sin(nx * 2.7f + ny * 5.9f)) * 2f
                        py += (cos(nx * 4.1f + ny * 6.7f) + cos(nx * 3.3f + ny * 2.3f)) * 2f
                    }
                }
            }

            if (px < 0f || px >= wf || py < 0f || py >= hf) continue

            // Dot size
            val radius = when (dotSizeMode) {
                "uniform" -> (minDot + maxDot) * 0.5f
                "cell-area" -> {
                    val inv = if (sumW[i] > 1e-6f) 1f / sumW[i] else 0f
                    minDot + (maxDot - minDot) * (inv / invMax).coerceAtMost(1f)
                }
                else -> minDot + (maxDot - minDot) * siteDensity[i] // density
            }
            if (radius < 0.4f) continue

            // Dot color
            var r: Int; var g: Int; var b: Int
            when (colorMode) {
                "sample" -> {
                    val sx = ((px / wf) * gw).toInt().coerceIn(0, gw - 1)
                    val sy = ((py / hf) * gh).toInt().coerceIn(0, gh - 1)
                    val p = srcPixels[sy * gw + sx]
                    if (inverse) {
                        r = 255 - Color.red(p); g = 255 - Color.green(p); b = 255 - Color.blue(p)
                    } else {
                        r = Color.red(p); g = Color.green(p); b = Color.blue(p)
                    }
                }
                "palette-density" -> {
                    val c = palette.lerpColor(siteDensity[i])
                    r = Color.red(c); g = Color.green(c); b = Color.blue(c)
                }
                else -> { r = monoR; g = monoG; b = monoB } // mono
            }

            // Contrast guard: ensure dot is visible against background
            val dotLum = (0.299f * r + 0.587f * g + 0.114f * b) / 255f
            val contrast = abs(dotLum - bgLum)
            if (contrast < 0.18f) {
                val k = (0.18f - contrast) / 0.18f
                r = (r + (monoR - r) * k).toInt()
                g = (g + (monoG - g) * k).toInt()
                b = (b + (monoB - b) * k).toInt()
            }

            paint.color = Color.argb(alphaInt, r.coerceIn(0, 255), g.coerceIn(0, 255), b.coerceIn(0, 255))

            when (dotShape) {
                "square" -> canvas.drawRect(px - radius, py - radius, px + radius, py + radius, paint)
                "diamond" -> {
                    path.reset()
                    path.moveTo(px, py - radius)
                    path.lineTo(px + radius, py)
                    path.lineTo(px, py + radius)
                    path.lineTo(px - radius, py)
                    path.close()
                    canvas.drawPath(path, paint)
                }
                else -> canvas.drawCircle(px, py, radius, paint)
            }
        }

        // ── Optional Voronoi edges overlay ──────────────────────────────────
        if (showEdges && quality != Quality.DRAFT) {
            val gridCells = sqrt(n.toFloat()).toInt().coerceAtLeast(1)
            val gcW = wf / gridCells
            val gcH = hf / gridCells
            val bucketCounts = IntArray(gridCells * gridCells)
            for (i in 0 until n) {
                val bx = (sitesX[i] / gcW).toInt().coerceIn(0, gridCells - 1)
                val by = (sitesY[i] / gcH).toInt().coerceIn(0, gridCells - 1)
                bucketCounts[by * gridCells + bx]++
            }
            val bucketOffsets = IntArray(bucketCounts.size)
            for (i in 1 until bucketOffsets.size) bucketOffsets[i] = bucketOffsets[i - 1] + bucketCounts[i - 1]
            val bucketIndices = IntArray(n)
            val fillPos = bucketOffsets.copyOf()
            for (i in 0 until n) {
                val bx = (sitesX[i] / gcW).toInt().coerceIn(0, gridCells - 1)
                val by = (sitesY[i] / gcH).toInt().coerceIn(0, gridCells - 1)
                val bi = by * gridCells + bx
                bucketIndices[fillPos[bi]++] = i
            }

            val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 0.5f
                color = if (inverse) Color.argb(30, 255, 255, 255) else Color.argb(46, 0, 0, 0)
            }

            val step = 3
            for (y in 0 until h step step) {
                if (Thread.currentThread().isInterrupted) return
                var prevSi = -1
                for (x in 0 until w step step) {
                    val si = findNearest(x.toFloat(), y.toFloat(), sitesX, sitesY,
                        gridCells, gcW, gcH, bucketCounts, bucketOffsets, bucketIndices)
                    if (prevSi >= 0 && si != prevSi) {
                        canvas.drawLine((x - step).toFloat(), y.toFloat(), x.toFloat(), y.toFloat(), edgePaint)
                    }
                    prevSi = si
                }
            }
        }
    }

    /**
     * Find nearest site to (px, py) using spatial grid acceleration.
     * Two-level search: ±1 grid cells, then ±2 if no site found.
     */
    private fun findNearest(
        px: Float, py: Float,
        sitesX: FloatArray, sitesY: FloatArray,
        gridCells: Int, gcW: Float, gcH: Float,
        bucketCounts: IntArray, bucketOffsets: IntArray, bucketIndices: IntArray
    ): Int {
        val gcx = (px / gcW).toInt().coerceIn(0, gridCells - 1)
        val gcy = (py / gcH).toInt().coerceIn(0, gridCells - 1)

        var bestDist = Float.MAX_VALUE
        var bestIdx = 0

        for (radius in 0..2) {
            val x0 = (gcx - radius).coerceAtLeast(0)
            val x1 = (gcx + radius).coerceAtMost(gridCells - 1)
            val y0 = (gcy - radius).coerceAtLeast(0)
            val y1 = (gcy + radius).coerceAtMost(gridCells - 1)

            for (by in y0..y1) {
                for (bx in x0..x1) {
                    val bi = by * gridCells + bx
                    val count = bucketCounts[bi]
                    val offset = bucketOffsets[bi]
                    for (k in 0 until count) {
                        val si = bucketIndices[offset + k]
                        val dx = sitesX[si] - px
                        val dy = sitesY[si] - py
                        val d2 = dx * dx + dy * dy
                        if (d2 < bestDist) {
                            bestDist = d2
                            bestIdx = si
                        }
                    }
                }
            }
            if (bestDist < Float.MAX_VALUE) break
        }
        return bestIdx
    }

    private fun drawPlaceholder(canvas: Canvas, bitmap: Bitmap) {
        canvas.drawColor(Color.DKGRAY)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textSize = 24f; textAlign = Paint.Align.CENTER
        }
        canvas.drawText("No source image", bitmap.width / 2f, bitmap.height / 2f, paint)
    }

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float {
        val n = (params["pointCount"] as? Number)?.toInt() ?: 6000
        val it = (params["iterations"] as? Number)?.toInt() ?: 8
        val gr = (params["gridRes"] as? Number)?.toInt() ?: 240
        return ((gr * gr * (it + 1) + n) * 0.001f).coerceIn(0.1f, 1f)
    }
}
