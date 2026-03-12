package com.artmondo.algomodo.generators.plotter

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import com.artmondo.algomodo.core.rng.SeededRNG
import com.artmondo.algomodo.core.rng.SimplexNoise
import com.artmondo.algomodo.data.palettes.Palette
import com.artmondo.algomodo.generators.Generator
import com.artmondo.algomodo.generators.ParamGroup
import com.artmondo.algomodo.generators.Parameter
import com.artmondo.algomodo.generators.Quality
import com.artmondo.algomodo.rendering.SvgPath
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * TSP Art generator (Traveling Salesman Problem).
 *
 * Places density-weighted stipple points and computes an approximate shortest tour using a
 * greedy nearest-neighbour heuristic with 2-opt refinement, then draws the continuous path.
 */
class PlotterTspGenerator : Generator {

    override val id = "plotter-tsp"
    override val family = "plotter"
    override val styleName = "TSP Art"
    override val definition =
        "Continuous line art by computing a greedy Traveling Salesman tour through random points."
    override val algorithmNotes =
        "Random points are placed across the canvas. A nearest-neighbour greedy heuristic " +
        "builds an approximate TSP tour: starting from a random point, each step visits the " +
        "closest unvisited point. The resulting tour is drawn as a single continuous path " +
        "with optional dot markers at each vertex."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema = listOf(
        Parameter.NumberParam("Point Count", "pointCount", ParamGroup.COMPOSITION, "Number of stipple points that form the TSP tour", 50f, 1500f, 50f, 600f),
        Parameter.NumberParam("Density Scale", "densityScale", ParamGroup.COMPOSITION, null, 0.3f, 6f, 0.1f, 1.8f),
        Parameter.NumberParam("Density Contrast", "densityContrast", ParamGroup.TEXTURE, null, 0.5f, 4f, 0.25f, 2.0f),
        Parameter.SelectParam("Density Style", "densityStyle", ParamGroup.COMPOSITION, "Shape of the density field — fbm: smooth | ridged: sharp ridges | radial: center-focused | turbulent: creases", listOf("fbm", "ridged", "radial", "turbulent"), "fbm"),
        Parameter.NumberParam("2-Opt Passes", "twoOptPasses", ParamGroup.COMPOSITION, "Number of 2-opt improvement passes — more = shorter tour, slower render", 0f, 8f, 1f, 2f),
        Parameter.SelectParam("Path Style", "pathStyle", ParamGroup.GEOMETRY, "straight: line segments | curved: smooth Bezier splines | dotted: dots at nodes with thin lines | dashed: dash pattern along path", listOf("straight", "curved", "dotted", "dashed"), "straight"),
        Parameter.NumberParam("Line Width", "lineWidth", ParamGroup.GEOMETRY, null, 1.0f, 3f, 0.25f, 1.0f),
        Parameter.NumberParam("Width Variation", "lineWidthVar", ParamGroup.GEOMETRY, "Vary stroke width by local density — 0 = uniform, 1 = thick in dense regions, thin in sparse", 0f, 1f, 0.1f, 0f),
        Parameter.BooleanParam("Close Path", "closePath", ParamGroup.GEOMETRY, "Connect last point back to first, completing the tour loop", true),
        Parameter.SelectParam("Color Mode", "colorMode", ParamGroup.COLOR, "monochrome: single ink | palette-progress: shifts along tour | density: by noise field | segment-alternate: alternates palette colors per segment", listOf("monochrome", "palette-progress", "density", "segment-alternate"), "monochrome"),
        Parameter.SelectParam("Background", "background", ParamGroup.COLOR, null, listOf("white", "cream", "dark"), "cream"),
        Parameter.NumberParam("Drift", "drift", ParamGroup.FLOW_MOTION, "Point drift amplitude in pixels (animated only)", 0f, 40f, 1f, 15f),
        Parameter.NumberParam("Drift Speed", "driftSpeed", ParamGroup.FLOW_MOTION, null, 0.02f, 0.5f, 0.02f, 0.1f)
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "pointCount" to 600f,
        "densityScale" to 1.8f,
        "densityContrast" to 2.0f,
        "densityStyle" to "fbm",
        "twoOptPasses" to 2f,
        "pathStyle" to "straight",
        "lineWidth" to 1.0f,
        "lineWidthVar" to 0f,
        "closePath" to true,
        "colorMode" to "monochrome",
        "background" to "cream",
        "drift" to 15f,
        "driftSpeed" to 0.1f
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
        val w = bitmap.width.toFloat()
        val h = bitmap.height.toFloat()

        // Read parameters
        val target = (params["pointCount"] as? Number)?.toInt() ?: 600
        val dScale = (params["densityScale"] as? Number)?.toFloat() ?: 1.8f
        val dContrast = (params["densityContrast"] as? Number)?.toFloat() ?: 2.0f
        val densityStyle = params["densityStyle"] as? String ?: "fbm"
        val optPasses = (params["twoOptPasses"] as? Number)?.toInt()?.coerceAtLeast(0) ?: 2
        val pathStyle = params["pathStyle"] as? String ?: "straight"
        val baseLineWidth = (params["lineWidth"] as? Number)?.toFloat() ?: 1.0f
        val lineWidthVar = (params["lineWidthVar"] as? Number)?.toFloat() ?: 0f
        val shouldClose = params["closePath"] as? Boolean ?: true
        val colorMode = params["colorMode"] as? String ?: "monochrome"
        val background = params["background"] as? String ?: "cream"
        val driftAmt = (params["drift"] as? Number)?.toFloat() ?: 15f
        val driftSpeed = (params["driftSpeed"] as? Number)?.toFloat() ?: 0.1f

        // Background
        val bgColor = when (background) {
            "white" -> Color.rgb(248, 248, 245)
            "dark" -> Color.rgb(14, 14, 14)
            else -> Color.rgb(242, 234, 216) // cream
        }
        canvas.drawColor(bgColor)
        val isDark = background == "dark"

        val rng = SeededRNG(seed)
        val noise = SimplexNoise(seed)
        val paletteColors = palette.colorInts()

        // Density function matching web version
        fun densityFn(x: Float, y: Float): Float {
            val nx = (x / w - 0.5f) * dScale + 5f
            val ny = (y / h - 0.5f) * dScale + 5f
            val n = when (densityStyle) {
                "ridged" -> {
                    val raw = noise.fbm(nx, ny, 4, 2f, 0.5f)
                    val ridge = 1f - abs(raw)
                    ridge * ridge
                }
                "turbulent" -> abs(noise.fbm(nx, ny, 4, 2f, 0.5f))
                "radial" -> {
                    val dx = x / w - 0.5f
                    val dy = y / h - 0.5f
                    val dist = sqrt(dx * dx + dy * dy) * 2f
                    val noiseVal = noise.fbm(nx, ny, 3, 2f, 0.5f) * 0.3f
                    max(0f, 1f - dist + noiseVal)
                }
                else -> noise.fbm(nx, ny, 4, 2f, 0.5f) * 0.5f + 0.5f // fbm
            }
            return max(0f, min(1f, n)).pow(dContrast)
        }

        // --- Phase 1: jittered grid for uniform canvas coverage (~half the points) ---
        val ptsX = mutableListOf<Float>()
        val ptsY = mutableListOf<Float>()
        val baseCount = (target * 0.5f).toInt()
        val cols = max(1, ceil(sqrt(baseCount.toFloat() * (w / h))).toInt())
        val rows = max(1, ceil(baseCount.toFloat() / cols).toInt())
        val cellW = w / cols
        val cellH = h / rows

        for (row in 0 until rows) {
            if (ptsX.size >= baseCount) break
            for (col in 0 until cols) {
                if (ptsX.size >= baseCount) break
                ptsX.add((col + rng.random()) * cellW)
                ptsY.add((row + rng.random()) * cellH)
            }
        }

        // --- Phase 2: density-weighted random points for artistic variation ---
        val remaining = target - ptsX.size
        val maxAttempts = remaining * 30
        var attempts = 0
        while (ptsX.size < target && attempts < maxAttempts) {
            attempts++
            val x = rng.random() * w
            val y = rng.random() * h
            val effectiveDensity = 0.25f + densityFn(x, y) * 0.75f
            if (rng.random() < effectiveDensity) {
                ptsX.add(x)
                ptsY.add(y)
            }
        }

        val pn = ptsX.size
        if (pn < 3) return

        // Convert to arrays for performance
        val xs = ptsX.toFloatArray()
        val ys = ptsY.toFloatArray()

        // --- Nearest-neighbour greedy tour construction ---
        val visited = BooleanArray(pn)
        val tour = IntArray(pn)
        tour[0] = 0
        visited[0] = true

        for (step in 1 until pn) {
            val prev = tour[step - 1]
            var bestDist = Float.MAX_VALUE
            var bestIdx = 0

            for (j in 0 until pn) {
                if (visited[j]) continue
                val dx = xs[prev] - xs[j]
                val dy = ys[prev] - ys[j]
                val d2 = dx * dx + dy * dy
                if (d2 < bestDist) {
                    bestDist = d2
                    bestIdx = j
                }
            }

            tour[step] = bestIdx
            visited[bestIdx] = true
        }

        // --- 2-opt improvement ---
        for (pass in 0 until optPasses) {
            var improved = false
            for (i in 0 until pn - 1) {
                for (j in i + 2 until pn) {
                    if (j == pn - 1 && i == 0) continue
                    val a = tour[i]
                    val b = tour[i + 1]
                    val c = tour[j]
                    val d = tour[(j + 1) % pn]

                    val dxAB = xs[a] - xs[b]; val dyAB = ys[a] - ys[b]
                    val dxCD = xs[c] - xs[d]; val dyCD = ys[c] - ys[d]
                    val before = dxAB * dxAB + dyAB * dyAB + dxCD * dxCD + dyCD * dyCD

                    val dxAC = xs[a] - xs[c]; val dyAC = ys[a] - ys[c]
                    val dxBD = xs[b] - xs[d]; val dyBD = ys[b] - ys[d]
                    val after = dxAC * dxAC + dyAC * dyAC + dxBD * dxBD + dyBD * dyBD

                    if (after < before - 1e-6f) {
                        // Reverse the segment between i+1 and j
                        var lo = i + 1
                        var hi = j
                        while (lo < hi) {
                            val tmp = tour[lo]
                            tour[lo] = tour[hi]
                            tour[hi] = tmp
                            lo++
                            hi--
                        }
                        improved = true
                    }
                }
            }
            if (!improved) break
        }

        // --- Noise drift for animation ---
        val drawXs: FloatArray
        val drawYs: FloatArray
        if (driftAmt > 0f && time > 0f) {
            drawXs = FloatArray(pn) { i ->
                xs[i] + (noise.noise2D(i * 0.4f, time * driftSpeed) - noise.noise2D(i * 0.4f, 0f)) * driftAmt
            }
            drawYs = FloatArray(pn) { i ->
                ys[i] + (noise.noise2D(i * 0.4f + 77f, time * driftSpeed) - noise.noise2D(i * 0.4f + 77f, 0f)) * driftAmt
            }
        } else {
            drawXs = xs
            drawYs = ys
        }

        // --- Color helpers ---
        val alpha = if (isDark) 224 else 209 // 0.88*255 ~ 224, 0.82*255 ~ 209

        fun lerpPaletteColor(t: Float): Int {
            val clamped = t.coerceIn(0f, 1f)
            val ci = clamped * (paletteColors.size - 1)
            val i0 = floor(ci).toInt().coerceIn(0, paletteColors.size - 2)
            val i1 = min(paletteColors.size - 1, i0 + 1)
            val f = ci - i0
            val c0 = paletteColors[i0]
            val c1 = paletteColors[i1]
            val r = (Color.red(c0) + (Color.red(c1) - Color.red(c0)) * f).toInt()
            val g = (Color.green(c0) + (Color.green(c1) - Color.green(c0)) * f).toInt()
            val b = (Color.blue(c0) + (Color.blue(c1) - Color.blue(c0)) * f).toInt()
            return Color.argb(alpha, r, g, b)
        }

        fun getSegColor(i: Int): Int {
            return when (colorMode) {
                "palette-progress" -> {
                    val t = i.toFloat() / (pn - 1)
                    lerpPaletteColor(t)
                }
                "density" -> {
                    val d = densityFn(xs[tour[i]], ys[tour[i]])
                    lerpPaletteColor(d)
                }
                "segment-alternate" -> {
                    val c = paletteColors[i % paletteColors.size]
                    Color.argb(alpha, Color.red(c), Color.green(c), Color.blue(c))
                }
                else -> { // monochrome
                    if (isDark) {
                        Color.argb(alpha, 220, 220, 220)
                    } else {
                        Color.argb(alpha, 30, 30, 30)
                    }
                }
            }
        }

        // --- Pre-compute densities for width variation ---
        val densities = if (lineWidthVar > 0f) FloatArray(pn) { densityFn(xs[it], ys[it]) } else null

        fun getSegWidth(i: Int): Float {
            if (lineWidthVar <= 0f || densities == null) return baseLineWidth
            val d = densities[tour[i]]
            return baseLineWidth * (1f - lineWidthVar * 0.6f + d * lineWidthVar * 1.2f)
        }

        val segCount = if (shouldClose) pn else pn - 1

        // --- Drawing ---
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        when (pathStyle) {
            "curved" -> {
                // Catmull-Rom-style smooth curves through tour points
                paint.style = Paint.Style.STROKE
                for (i in 0 until segCount) {
                    val idx0 = tour[((i - 1) + pn) % pn]
                    val idx1 = tour[i]
                    val idx2 = tour[(i + 1) % pn]
                    val idx3 = tour[(i + 2) % pn]

                    val x0 = drawXs[idx0]; val y0 = drawYs[idx0]
                    val x1 = drawXs[idx1]; val y1 = drawYs[idx1]
                    val x2 = drawXs[idx2]; val y2 = drawYs[idx2]
                    val x3 = drawXs[idx3]; val y3 = drawYs[idx3]

                    // Control points for cubic Bezier approximating Catmull-Rom
                    val cp1x = x1 + (x2 - x0) / 6f
                    val cp1y = y1 + (y2 - y0) / 6f
                    val cp2x = x2 - (x3 - x1) / 6f
                    val cp2y = y2 - (y3 - y1) / 6f

                    paint.color = getSegColor(i)
                    paint.strokeWidth = getSegWidth(i)

                    val path = Path()
                    path.moveTo(x1, y1)
                    path.cubicTo(cp1x, cp1y, cp2x, cp2y, x2, y2)
                    canvas.drawPath(path, paint)
                }
            }

            "dotted" -> {
                // Thin connecting lines + dots at each node
                paint.style = Paint.Style.STROKE
                val thinWidth = baseLineWidth * 0.3f

                if (colorMode == "monochrome") {
                    // Draw all connecting lines in one batch
                    paint.color = getSegColor(0)
                    paint.strokeWidth = thinWidth
                    val path = Path()
                    path.moveTo(drawXs[tour[0]], drawYs[tour[0]])
                    for (i in 1 until pn) {
                        path.lineTo(drawXs[tour[i]], drawYs[tour[i]])
                    }
                    if (shouldClose) path.close()
                    canvas.drawPath(path, paint)
                } else {
                    // Draw connecting lines segment by segment for per-segment color
                    paint.strokeWidth = thinWidth
                    for (i in 0 until segCount) {
                        paint.color = getSegColor(i)
                        val x1 = drawXs[tour[i]]
                        val y1 = drawYs[tour[i]]
                        val x2 = drawXs[tour[(i + 1) % pn]]
                        val y2 = drawYs[tour[(i + 1) % pn]]
                        canvas.drawLine(x1, y1, x2, y2, paint)
                    }
                }

                // Draw dots at each node
                paint.style = Paint.Style.FILL
                for (i in 0 until pn) {
                    val px = drawXs[tour[i]]
                    val py = drawYs[tour[i]]
                    val dotR = getSegWidth(i) * 2.5f
                    paint.color = getSegColor(i)
                    canvas.drawCircle(px, py, dotR, paint)
                }
            }

            "dashed" -> {
                // Dashed segments with gaps
                paint.style = Paint.Style.STROKE
                for (i in 0 until segCount) {
                    val x1 = drawXs[tour[i]]
                    val y1 = drawYs[tour[i]]
                    val x2 = drawXs[tour[(i + 1) % pn]]
                    val y2 = drawYs[tour[(i + 1) % pn]]
                    val dx = x2 - x1
                    val dy = y2 - y1
                    val segLen = sqrt(dx * dx + dy * dy)
                    if (segLen < 0.001f) continue

                    val dashLen = max(3f, segLen * 0.6f)
                    val gapLen = segLen - dashLen
                    val ux = dx / segLen
                    val uy = dy / segLen

                    paint.color = getSegColor(i)
                    paint.strokeWidth = getSegWidth(i)
                    canvas.drawLine(
                        x1 + ux * gapLen * 0.5f,
                        y1 + uy * gapLen * 0.5f,
                        x2 - ux * gapLen * 0.5f,
                        y2 - uy * gapLen * 0.5f,
                        paint
                    )
                }
            }

            else -> {
                // Straight lines (default)
                paint.style = Paint.Style.STROKE
                if (colorMode == "monochrome") {
                    // Draw all lines in one batch for monochrome
                    paint.color = getSegColor(0)
                    paint.strokeWidth = baseLineWidth
                    val path = Path()
                    path.moveTo(drawXs[tour[0]], drawYs[tour[0]])
                    for (i in 1 until pn) {
                        path.lineTo(drawXs[tour[i]], drawYs[tour[i]])
                    }
                    if (shouldClose) path.close()
                    canvas.drawPath(path, paint)
                } else {
                    // Draw segment by segment for per-segment color/width
                    for (i in 0 until segCount) {
                        val x1 = drawXs[tour[i]]
                        val y1 = drawYs[tour[i]]
                        val x2 = drawXs[tour[(i + 1) % pn]]
                        val y2 = drawYs[tour[(i + 1) % pn]]
                        paint.color = getSegColor(i)
                        paint.strokeWidth = getSegWidth(i)
                        canvas.drawLine(x1, y1, x2, y2, paint)
                    }
                }
            }
        }
    }

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float {
        val n = (params["pointCount"] as? Number)?.toInt() ?: 600
        val passes = (params["twoOptPasses"] as? Number)?.toInt() ?: 2
        return (n.toFloat() * n * (1f + passes * 0.4f) * 0.002f).coerceIn(0.2f, 1f)
    }
}
