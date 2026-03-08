package com.artmondo.algomodo.generators.fractals

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
import com.artmondo.algomodo.rendering.SvgBuilder
import com.artmondo.algomodo.rendering.SvgPath
import kotlin.math.sin

class RecursiveSubdivisionGenerator : Generator {

    override val id = "fractal-recursive-subdivision"
    override val family = "fractals"
    override val styleName = "Recursive Subdivision"
    override val definition =
        "Recursively subdivides the canvas into nested regions, coloring each by recursion depth for a Mondrian-like fractal composition."
    override val algorithmNotes =
        "At each recursion level, a region is split either horizontally or vertically at a biased " +
        "random position. The split bias controls how balanced/unbalanced the subdivision is. " +
        "In 'quad' mode each region is split into 4 sub-rectangles; 'triangle' mode splits diagonally; " +
        "'irregular' mode uses random axis splits. Jitter controls split randomness."
    override val supportsVector = true
    override val supportsAnimation = true

    override val parameterSchema: List<Parameter> = listOf(
        Parameter.NumberParam(
            name = "Depth",
            key = "depth",
            group = ParamGroup.COMPOSITION,
            help = "Recursion depth \u2014 higher = more cells",
            min = 1f, max = 10f, step = 1f, default = 5f
        ),
        Parameter.SelectParam(
            name = "Split Mode",
            key = "splitMode",
            group = ParamGroup.COMPOSITION,
            help = "quad: 4-way rectangle split | triangle: Sierpinski-like | irregular: random axis splits",
            options = listOf("quad", "triangle", "irregular"),
            default = "quad"
        ),
        Parameter.NumberParam(
            name = "Jitter",
            key = "jitter",
            group = ParamGroup.GEOMETRY,
            help = "Randomness of split positions (0 = uniform, 1 = maximum variation)",
            min = 0f, max = 1f, step = 0.05f, default = 0.3f
        ),
        Parameter.NumberParam(
            name = "Margin",
            key = "margin",
            group = ParamGroup.GEOMETRY,
            help = "Gap between cells in pixels",
            min = 0f, max = 10f, step = 0.5f, default = 2f
        ),
        Parameter.SelectParam(
            name = "Color Mode",
            key = "colorMode",
            group = ParamGroup.COLOR,
            help = "depth: shade by recursion level | noise: simplex noise | random: per-cell random color",
            options = listOf("depth", "noise", "random"),
            default = "depth"
        ),
        Parameter.NumberParam(
            name = "Speed",
            key = "speed",
            group = ParamGroup.FLOW_MOTION,
            help = "Animation speed for depth reveal",
            min = 0.1f, max = 3.0f, step = 0.1f, default = 0.5f
        )
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "depth" to 5f,
        "splitMode" to "quad",
        "jitter" to 0.3f,
        "margin" to 2f,
        "colorMode" to "depth",
        "speed" to 0.5f
    )

    private data class Rect(val x: Float, val y: Float, val w: Float, val h: Float)

    private fun subdivide(
        rect: Rect,
        depth: Int,
        maxDepth: Int,
        jitter: Float,
        mode: String,
        rng: SeededRNG,
        timeOffset: Float,
        results: MutableList<Pair<List<Pair<Float, Float>>, Int>>
    ) {
        if (depth >= maxDepth || rect.w < 4f || rect.h < 4f) {
            // Leaf node — emit polygon
            when (mode) {
                "triangle" -> {
                    // Split rectangle into two triangles along a random diagonal
                    if (rng.boolean(0.5f)) {
                        results.add(
                            listOf(
                                rect.x to rect.y,
                                rect.x + rect.w to rect.y,
                                rect.x + rect.w to rect.y + rect.h
                            ) to depth
                        )
                        results.add(
                            listOf(
                                rect.x to rect.y,
                                rect.x to rect.y + rect.h,
                                rect.x + rect.w to rect.y + rect.h
                            ) to depth
                        )
                    } else {
                        results.add(
                            listOf(
                                rect.x to rect.y,
                                rect.x + rect.w to rect.y,
                                rect.x to rect.y + rect.h
                            ) to depth
                        )
                        results.add(
                            listOf(
                                rect.x + rect.w to rect.y,
                                rect.x + rect.w to rect.y + rect.h,
                                rect.x to rect.y + rect.h
                            ) to depth
                        )
                    }
                }
                else -> {
                    results.add(
                        listOf(
                            rect.x to rect.y,
                            rect.x + rect.w to rect.y,
                            rect.x + rect.w to rect.y + rect.h,
                            rect.x to rect.y + rect.h
                        ) to depth
                    )
                }
            }
            return
        }

        // Animated split perturbation
        val animBias = sin(timeOffset + depth * 0.7f) * 0.04f

        when (mode) {
            "quad" -> {
                // True 4-way split: pick both horizontal and vertical split positions
                val splitFracX = 0.5f + jitter * rng.range(-0.45f, 0.45f) + animBias
                val splitFracY = 0.5f + jitter * rng.range(-0.45f, 0.45f) - animBias
                val splitX = rect.x + rect.w * splitFracX.coerceIn(0.15f, 0.85f)
                val splitY = rect.y + rect.h * splitFracY.coerceIn(0.15f, 0.85f)

                val topLeft = Rect(rect.x, rect.y, splitX - rect.x, splitY - rect.y)
                val topRight = Rect(splitX, rect.y, rect.x + rect.w - splitX, splitY - rect.y)
                val bottomLeft = Rect(rect.x, splitY, splitX - rect.x, rect.y + rect.h - splitY)
                val bottomRight = Rect(splitX, splitY, rect.x + rect.w - splitX, rect.y + rect.h - splitY)

                subdivide(topLeft, depth + 1, maxDepth, jitter, mode, rng, timeOffset, results)
                subdivide(topRight, depth + 1, maxDepth, jitter, mode, rng, timeOffset, results)
                subdivide(bottomLeft, depth + 1, maxDepth, jitter, mode, rng, timeOffset, results)
                subdivide(bottomRight, depth + 1, maxDepth, jitter, mode, rng, timeOffset, results)
            }

            "triangle" -> {
                // Binary split along the longer axis, leaf nodes become triangles
                val horizontal = if (rect.w > rect.h * 1.2f) true
                else if (rect.h > rect.w * 1.2f) false
                else rng.boolean(0.5f)

                val splitFrac = 0.5f + jitter * rng.range(-0.4f, 0.4f) + animBias

                if (horizontal) {
                    val splitX = rect.x + rect.w * splitFrac.coerceIn(0.2f, 0.8f)
                    subdivide(Rect(rect.x, rect.y, splitX - rect.x, rect.h), depth + 1, maxDepth, jitter, mode, rng, timeOffset, results)
                    subdivide(Rect(splitX, rect.y, rect.x + rect.w - splitX, rect.h), depth + 1, maxDepth, jitter, mode, rng, timeOffset, results)
                } else {
                    val splitY = rect.y + rect.h * splitFrac.coerceIn(0.2f, 0.8f)
                    subdivide(Rect(rect.x, rect.y, rect.w, splitY - rect.y), depth + 1, maxDepth, jitter, mode, rng, timeOffset, results)
                    subdivide(Rect(rect.x, splitY, rect.w, rect.y + rect.h - splitY), depth + 1, maxDepth, jitter, mode, rng, timeOffset, results)
                }
            }

            else -> {
                // "irregular" — binary split along random or longer axis
                val horizontal = if (rect.w > rect.h * 1.3f) true
                else if (rect.h > rect.w * 1.3f) false
                else rng.boolean(0.5f)

                val splitFrac = 0.5f + jitter * rng.range(-0.45f, 0.45f) + animBias

                if (horizontal) {
                    val splitX = rect.x + rect.w * splitFrac.coerceIn(0.15f, 0.85f)
                    subdivide(Rect(rect.x, rect.y, splitX - rect.x, rect.h), depth + 1, maxDepth, jitter, mode, rng, timeOffset, results)
                    subdivide(Rect(splitX, rect.y, rect.x + rect.w - splitX, rect.h), depth + 1, maxDepth, jitter, mode, rng, timeOffset, results)
                } else {
                    val splitY = rect.y + rect.h * splitFrac.coerceIn(0.15f, 0.85f)
                    subdivide(Rect(rect.x, rect.y, rect.w, splitY - rect.y), depth + 1, maxDepth, jitter, mode, rng, timeOffset, results)
                    subdivide(Rect(rect.x, splitY, rect.w, rect.y + rect.h - splitY), depth + 1, maxDepth, jitter, mode, rng, timeOffset, results)
                }
            }
        }
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
        val maxDepth = (params["depth"] as? Number)?.toInt() ?: 5
        val splitMode = (params["splitMode"] as? String) ?: "quad"
        val jitter = (params["jitter"] as? Number)?.toFloat() ?: 0.3f
        val margin = (params["margin"] as? Number)?.toFloat() ?: 2f
        val colorMode = (params["colorMode"] as? String) ?: "depth"
        val speed = (params["speed"] as? Number)?.toFloat() ?: 0.5f

        // Animation: progressive depth reveal + split position oscillation
        val timeOffset = time * speed * 0.4f
        val animDepth = if (time > 0.01f) {
            // Gradually reveal deeper levels
            val revealed = (time * speed * 0.8f + 1f).toInt().coerceAtMost(maxDepth)
            revealed
        } else {
            maxDepth
        }

        val rng = SeededRNG(seed)
        val regions = mutableListOf<Pair<List<Pair<Float, Float>>, Int>>()
        subdivide(
            Rect(margin, margin, w - 2 * margin, h - 2 * margin),
            0, animDepth, jitter, splitMode, rng, timeOffset, regions
        )

        // Clear canvas
        canvas.drawColor(Color.BLACK)

        val paint = Paint().apply {
            style = Paint.Style.FILL
            isAntiAlias = quality != Quality.DRAFT
        }

        val gapPaint = Paint().apply {
            style = Paint.Style.STROKE
            color = Color.BLACK
            strokeWidth = margin
            isAntiAlias = quality != Quality.DRAFT
        }

        val noise = SimplexNoise(seed)
        val colorRng = SeededRNG(seed + 7)

        for ((points, depth) in regions) {
            val t = (depth.toFloat() / maxDepth.coerceAtLeast(1)).coerceIn(0f, 1f)

            // Compute cell center for noise-based coloring
            var cx = 0f
            var cy = 0f
            for ((px, py) in points) {
                cx += px
                cy += py
            }
            cx /= points.size
            cy /= points.size

            paint.color = when (colorMode) {
                "noise" -> {
                    // Simplex noise at cell center, modulated by depth
                    val n = (noise.noise2D(cx * 0.008f, cy * 0.008f) + 1f) * 0.5f
                    val blended = (n * 0.7f + t * 0.3f).coerceIn(0f, 1f)
                    palette.lerpColor(blended)
                }
                "random" -> {
                    // Per-cell random color from palette
                    palette.lerpColor(colorRng.random())
                }
                else -> {
                    // "depth" — shade by recursion level
                    palette.lerpColor(t)
                }
            }

            val path = Path()
            if (points.isNotEmpty()) {
                path.moveTo(points[0].first, points[0].second)
                for (i in 1 until points.size) {
                    path.lineTo(points[i].first, points[i].second)
                }
                path.close()
            }

            canvas.drawPath(path, paint)
            if (margin > 0f) {
                canvas.drawPath(path, gapPaint)
            }
        }
    }

    override fun renderVector(
        params: Map<String, Any>,
        seed: Int,
        palette: Palette
    ): List<SvgPath> {
        val w = 1080f
        val h = 1080f
        val maxDepth = (params["depth"] as? Number)?.toInt() ?: 5
        val splitMode = (params["splitMode"] as? String) ?: "quad"
        val jitter = (params["jitter"] as? Number)?.toFloat() ?: 0.3f
        val margin = (params["margin"] as? Number)?.toFloat() ?: 2f
        val colorMode = (params["colorMode"] as? String) ?: "depth"

        val rng = SeededRNG(seed)
        val regions = mutableListOf<Pair<List<Pair<Float, Float>>, Int>>()
        subdivide(
            Rect(margin, margin, w - 2 * margin, h - 2 * margin),
            0, maxDepth, jitter, splitMode, rng, 0f, regions
        )

        val svgPaths = mutableListOf<SvgPath>()
        val noise = SimplexNoise(seed)
        val colorRng = SeededRNG(seed + 7)

        for ((points, depth) in regions) {
            val t = (depth.toFloat() / maxDepth.coerceAtLeast(1)).coerceIn(0f, 1f)

            var cx = 0f
            var cy = 0f
            for ((px, py) in points) {
                cx += px
                cy += py
            }
            cx /= points.size
            cy /= points.size

            val color = when (colorMode) {
                "noise" -> {
                    val n = (noise.noise2D(cx * 0.008f, cy * 0.008f) + 1f) * 0.5f
                    val blended = (n * 0.7f + t * 0.3f).coerceIn(0f, 1f)
                    palette.lerpColor(blended)
                }
                "random" -> palette.lerpColor(colorRng.random())
                else -> palette.lerpColor(t)
            }
            val hexColor = String.format("#%06X", 0xFFFFFF and color)

            val d = if (points.size == 4 && splitMode == "quad") {
                SvgBuilder.rect(
                    points[0].first, points[0].second,
                    points[2].first - points[0].first,
                    points[2].second - points[0].second
                )
            } else {
                SvgBuilder.polygon(points)
            }

            svgPaths.add(
                SvgPath(
                    d = d,
                    fill = hexColor,
                    stroke = if (margin > 0f) "#000000" else null,
                    strokeWidth = margin
                )
            )
        }

        return svgPaths
    }

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float {
        val depth = (params["depth"] as? Number)?.toInt() ?: 5
        return (depth / 8f).coerceIn(0.2f, 1f)
    }
}
