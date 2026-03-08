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
import kotlin.math.sqrt

/**
 * TSP Art generator (Traveling Salesman Problem).
 *
 * Places random points and computes an approximate shortest tour using a
 * greedy nearest-neighbour heuristic, then draws the continuous path.
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
        val numPoints = (params["pointCount"] as? Number)?.toInt() ?: 600
        val lineWidth = (params["lineWidth"] as? Number)?.toFloat() ?: 1.0f
        val closePath = params["closePath"] as? Boolean ?: true

        val rng = SeededRNG(seed)
        val paletteColors = palette.colorInts()

        canvas.drawColor(Color.BLACK)

        val drift = (params["drift"] as? Number)?.toFloat() ?: 15f
        val driftSpeed = (params["driftSpeed"] as? Number)?.toFloat() ?: 0.1f

        // Generate random points
        val margin = 20f
        val xs = FloatArray(numPoints) { rng.range(margin, w - margin) }
        val ys = FloatArray(numPoints) { rng.range(margin, h - margin) }

        // Animate: displace points with noise-based drift
        if (time > 0f && drift > 0f) {
            val noise = SimplexNoise(seed)
            for (i in 0 until numPoints) {
                xs[i] += noise.noise2D(i * 0.3f + 100f, time * driftSpeed) * drift
                ys[i] += noise.noise2D(i * 0.3f + 200f, time * driftSpeed) * drift
                xs[i] = xs[i].coerceIn(margin, w - margin)
                ys[i] = ys[i].coerceIn(margin, h - margin)
            }
        }

        // Greedy nearest-neighbour TSP
        val visited = BooleanArray(numPoints)
        val tour = IntArray(numPoints)
        tour[0] = 0
        visited[0] = true

        for (step in 1 until numPoints) {
            val prev = tour[step - 1]
            var bestDist = Float.MAX_VALUE
            var bestIdx = 0

            for (j in 0 until numPoints) {
                if (visited[j]) continue
                val dx = xs[prev] - xs[j]
                val dy = ys[prev] - ys[j]
                val dist = dx * dx + dy * dy // skip sqrt for comparison
                if (dist < bestDist) {
                    bestDist = dist
                    bestIdx = j
                }
            }

            tour[step] = bestIdx
            visited[bestIdx] = true
        }

        // Draw tour path
        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = lineWidth
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        val path = Path()
        path.moveTo(xs[tour[0]], ys[tour[0]])
        for (i in 1 until numPoints) {
            val idx = tour[i]
            val t = i.toFloat() / numPoints
            path.lineTo(xs[idx], ys[idx])
        }
        // Close the tour if enabled
        if (closePath) path.lineTo(xs[tour[0]], ys[tour[0]])

        // Draw with gradient coloring by splitting into segments
        val segPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = lineWidth
            strokeCap = Paint.Cap.ROUND
        }

        val segLimit = if (closePath) numPoints else numPoints - 1
        for (i in 0 until segLimit) {
            val cur = tour[i]
            val next = tour[(i + 1) % numPoints]
            val t = i.toFloat() / numPoints
            segPaint.color = palette.lerpColor(t)
            canvas.drawLine(xs[cur], ys[cur], xs[next], ys[next], segPaint)
        }

        // Optionally draw points (when path is not closed, show dots at endpoints)
        if (!closePath) {
            val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = Color.WHITE
            }
            for (i in 0 until numPoints) {
                dotPaint.color = paletteColors[i % paletteColors.size]
                canvas.drawCircle(xs[i], ys[i], lineWidth * 2f, dotPaint)
            }
        }
    }

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float {
        val points = (params["pointCount"] as? Number)?.toInt() ?: 600
        // O(n^2) nearest neighbour
        return (points * points / 4000000f).coerceIn(0.2f, 1f)
    }
}
