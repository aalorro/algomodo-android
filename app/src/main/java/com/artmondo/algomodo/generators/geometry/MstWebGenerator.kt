package com.artmondo.algomodo.generators.geometry

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.artmondo.algomodo.core.rng.SeededRNG
import com.artmondo.algomodo.data.palettes.Palette
import com.artmondo.algomodo.generators.Generator
import com.artmondo.algomodo.generators.ParamGroup
import com.artmondo.algomodo.generators.Parameter
import com.artmondo.algomodo.generators.Quality

/**
 * Minimum Spanning Tree web generator.
 *
 * Generates random points on the canvas and computes their Minimum Spanning Tree
 * using Prim's algorithm. The resulting tree edges form an organic web-like structure.
 * Points are colored from the palette and edges inherit their endpoint colors.
 * Animation slowly drifts the points over time.
 */
class MstWebGenerator : Generator {

    override val id = "mst-web"
    override val family = "geometry"
    override val styleName = "MST Web"
    override val definition =
        "An organic web structure formed by the Minimum Spanning Tree of randomly placed points."
    override val algorithmNotes =
        "Generates N random points using the seeded RNG, then computes the Minimum Spanning Tree " +
        "using Prim's algorithm (greedy nearest-neighbor expansion). Each edge is drawn as a line " +
        "between connected points. Points are optionally drawn as circles. Animation applies a " +
        "slow drift to each point's position using sinusoidal offsets."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema: List<Parameter> = listOf(
        Parameter.NumberParam(
            name = "Point Count",
            key = "pointCount",
            group = ParamGroup.COMPOSITION,
            help = null,
            min = 10f, max = 600f, step = 10f, default = 400f
        ),
        Parameter.NumberParam(
            name = "Prune %",
            key = "prunePercent",
            group = ParamGroup.COMPOSITION,
            help = "Remove the longest X% of MST edges \u2014 breaks the tree into isolated organic subtree clusters",
            min = 0f, max = 70f, step = 5f, default = 0f
        ),
        Parameter.NumberParam(
            name = "Node Size",
            key = "nodeSize",
            group = ParamGroup.GEOMETRY,
            help = null,
            min = 0f, max = 30f, step = 1f, default = 8f
        ),
        Parameter.NumberParam(
            name = "Edge Width",
            key = "edgeWidth",
            group = ParamGroup.GEOMETRY,
            help = null,
            min = 1f, max = 8f, step = 0.5f, default = 4f
        ),
        Parameter.SelectParam(
            name = "Distribution",
            key = "distribution",
            group = ParamGroup.COMPOSITION,
            help = "uniform: random scatter | gaussian: centre-weighted density | clustered: tight local groups | ring: annular band | fibonacci: phyllotaxis golden-angle spiral",
            options = listOf("uniform", "gaussian", "clustered", "ring", "fibonacci"),
            default = "uniform"
        ),
        Parameter.SelectParam(
            name = "Color Mode",
            key = "colorMode",
            group = ParamGroup.COLOR,
            help = "palette-cycle: edge colour by node indices | edge-length: short\u2192long mapped to palette | depth: MST growth order | radial: edge midpoint distance from centre",
            options = listOf("palette-cycle", "edge-length", "depth", "radial"),
            default = "palette-cycle"
        ),
        Parameter.SelectParam(
            name = "Background",
            key = "background",
            group = ParamGroup.COLOR,
            help = null,
            options = listOf("dark", "light"),
            default = "dark"
        ),
        Parameter.NumberParam(
            name = "Drift",
            key = "drift",
            group = ParamGroup.FLOW_MOTION,
            help = "Node drift amplitude in pixels (animated only)",
            min = 0f, max = 40f, step = 1f, default = 12f
        ),
        Parameter.NumberParam(
            name = "Drift Speed",
            key = "driftSpeed",
            group = ParamGroup.FLOW_MOTION,
            help = null,
            min = 0.02f, max = 0.5f, step = 0.02f, default = 0.1f
        )
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "pointCount" to 400f,
        "prunePercent" to 0f,
        "nodeSize" to 8f,
        "edgeWidth" to 4f,
        "distribution" to "uniform",
        "colorMode" to "palette-cycle",
        "background" to "dark",
        "drift" to 12f,
        "driftSpeed" to 0.1f
    )

    /**
     * Prim's algorithm for Minimum Spanning Tree.
     * Returns list of edges as (index_a, index_b).
     */
    private fun primMST(
        xs: FloatArray, ys: FloatArray, n: Int
    ): List<Pair<Int, Int>> {
        if (n <= 1) return emptyList()

        val inMST = BooleanArray(n)
        val minDist = FloatArray(n) { Float.MAX_VALUE }
        val minEdge = IntArray(n) { -1 }
        val edges = mutableListOf<Pair<Int, Int>>()

        // Start from vertex 0
        inMST[0] = true
        for (j in 1 until n) {
            val dx = xs[0] - xs[j]
            val dy = ys[0] - ys[j]
            minDist[j] = dx * dx + dy * dy // Use squared distance
            minEdge[j] = 0
        }

        for (step in 0 until n - 1) {
            // Find the closest non-MST vertex
            var bestIdx = -1
            var bestDist = Float.MAX_VALUE
            for (j in 0 until n) {
                if (!inMST[j] && minDist[j] < bestDist) {
                    bestDist = minDist[j]
                    bestIdx = j
                }
            }

            if (bestIdx == -1) break

            inMST[bestIdx] = true
            edges.add(minEdge[bestIdx] to bestIdx)

            // Update distances
            for (j in 0 until n) {
                if (!inMST[j]) {
                    val dx = xs[bestIdx] - xs[j]
                    val dy = ys[bestIdx] - ys[j]
                    val dist = dx * dx + dy * dy
                    if (dist < minDist[j]) {
                        minDist[j] = dist
                        minEdge[j] = bestIdx
                    }
                }
            }
        }

        return edges
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
        val w = bitmap.width.toFloat()
        val h = bitmap.height.toFloat()
        val numPoints = (params["pointCount"] as? Number)?.toInt() ?: 400
        val lineWidth = (params["edgeWidth"] as? Number)?.toFloat() ?: 4f
        val pointSize = (params["nodeSize"] as? Number)?.toFloat() ?: 8f

        canvas.drawColor(Color.BLACK)

        val rng = SeededRNG(seed)
        val margin = w * 0.05f

        // Generate random base positions
        val baseXs = FloatArray(numPoints) { rng.range(margin, w - margin) }
        val baseYs = FloatArray(numPoints) { rng.range(margin, h - margin) }

        // Animate: drift points with sinusoidal offsets
        val xs = FloatArray(numPoints)
        val ys = FloatArray(numPoints)
        for (i in 0 until numPoints) {
            val phase = i * 0.37f
            xs[i] = baseXs[i] + kotlin.math.sin(time * 0.3f + phase) * 8f
            ys[i] = baseYs[i] + kotlin.math.cos(time * 0.25f + phase * 1.3f) * 8f
        }

        // Compute MST
        val edges = primMST(xs, ys, numPoints)

        val paletteColors = palette.colorInts()

        val linePaint = Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = lineWidth
            isAntiAlias = quality != Quality.DRAFT
            strokeCap = Paint.Cap.ROUND
        }

        val pointPaint = Paint().apply {
            style = Paint.Style.FILL
            isAntiAlias = quality != Quality.DRAFT
        }

        // Draw edges
        for ((a, b) in edges) {
            val t = a.toFloat() / numPoints
            linePaint.color = palette.lerpColor(t)
            canvas.drawLine(xs[a], ys[a], xs[b], ys[b], linePaint)
        }

        // Draw points (nodeSize > 0 means visible)
        if (pointSize > 0f) {
            for (i in 0 until numPoints) {
                pointPaint.color = paletteColors[i % paletteColors.size]
                canvas.drawCircle(xs[i], ys[i], pointSize, pointPaint)
            }
        }
    }

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float {
        val numPoints = (params["pointCount"] as? Number)?.toInt() ?: 400
        // Prim's is O(n^2)
        return (numPoints * numPoints / 400000f).coerceIn(0.1f, 1f)
    }
}
