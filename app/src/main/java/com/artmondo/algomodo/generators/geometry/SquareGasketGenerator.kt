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
import kotlin.math.*

class SquareGasketGenerator : Generator {

    override val id = "geometry-square-gasket"
    override val family = "geometry"
    override val styleName = "Square Gasket"
    override val definition =
        "Fills the canvas with overlapping, randomly placed squares at decreasing scales, creating a fractal-like density where smaller squares rush to fill the visual gaps left between larger ones."
    override val algorithmNotes =
        "Scatter mode iterates through multiple scale tiers (largest first), placing N squares at random positions with optional gap-seeking. " +
        "Packed mode recursively subdivides the canvas into non-overlapping rectangles, placing the largest square that fits in each and subdividing the remainder, producing a tight treemap-style mosaic. " +
        "Nested render style draws each square as concentric colored rings. Color modes control palette assignment (random, sequential, by-size, radial)."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema: List<Parameter> = listOf(
        Parameter.SelectParam("Layout", "layout", ParamGroup.COMPOSITION,
            "scatter: random overlapping positions | packed: non-overlapping subdivision that tiles the canvas",
            listOf("scatter", "packed"), "scatter"),
        Parameter.NumberParam("Scale Levels", "scaleLevels", ParamGroup.COMPOSITION,
            "Number of size tiers from largest to smallest",
            3f, 10f, 1f, 6f),
        Parameter.NumberParam("Scale Ratio", "scaleRatio", ParamGroup.COMPOSITION,
            "Size reduction factor per tier",
            0.3f, 0.7f, 0.05f, 0.5f),
        Parameter.NumberParam("Count / Level", "countPerLevel", ParamGroup.COMPOSITION,
            "Base number of squares per tier (scatter only)",
            20f, 400f, 10f, 80f),
        Parameter.NumberParam("Rotation Range", "rotationRange", ParamGroup.GEOMETRY,
            "Maximum random rotation in degrees",
            0f, 90f, 5f, 45f),
        Parameter.BooleanParam("Gap Seeking", "gapSeeking", ParamGroup.GEOMETRY,
            "Smaller squares preferentially target empty areas (scatter only)", true),
        Parameter.NumberParam("Stroke Weight", "strokeWeight", ParamGroup.TEXTURE,
            "Outline thickness", 1f, 10f, 0.5f, 1f),
        Parameter.SelectParam("Color Mode", "colorMode", ParamGroup.COLOR,
            "random: seeded random per square | sequential: cycle palette | by-size: color by scale tier | radial: color by distance from center",
            listOf("random", "sequential", "by-size", "radial"), "random"),
        Parameter.NumberParam("Fill Opacity", "fillOpacity", ParamGroup.COLOR,
            "Opacity of square fills", 0.2f, 1.0f, 0.05f, 0.85f),
        Parameter.SelectParam("Render Style", "renderStyle", ParamGroup.TEXTURE,
            "filled: solid squares | outlined: stroked only | mixed: alternates | nested: concentric colored rings",
            listOf("filled", "outlined", "mixed", "nested"), "filled"),
        Parameter.NumberParam("Nest Layers", "nestLayers", ParamGroup.TEXTURE,
            "Number of concentric rings per square (nested style only)",
            3f, 8f, 1f, 5f),
        Parameter.NumberParam("Speed", "speed", ParamGroup.FLOW_MOTION,
            "Animation speed", 0.1f, 3.0f, 0.1f, 0.8f),
        Parameter.NumberParam("Anim Amplitude", "animAmp", ParamGroup.FLOW_MOTION,
            "Animation intensity — rotation drift and scale breathing",
            0.0f, 2.0f, 0.1f, 0.5f),
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "layout" to "scatter",
        "scaleLevels" to 6f,
        "scaleRatio" to 0.5f,
        "countPerLevel" to 80f,
        "rotationRange" to 45f,
        "gapSeeking" to true,
        "strokeWeight" to 1f,
        "colorMode" to "random",
        "fillOpacity" to 0.85f,
        "renderStyle" to "filled",
        "nestLayers" to 5f,
        "speed" to 0.8f,
        "animAmp" to 0.5f,
    )

    // ── Square data ──

    private class PlacedSquare(
        val cx: Float,
        val cy: Float,
        val half: Float,
        val angle: Float,
        val colorIdx: Int,
        val tier: Int,
    )

    // ── Occupancy grid for gap-seeking ──

    private class OccupancyGrid(private val w: Int, private val h: Int, res: Int) {
        private val cellSize = max(1, max(w, h) / res)
        private val cols = (w + cellSize - 1) / cellSize
        private val rows = (h + cellSize - 1) / cellSize
        private val cells = IntArray(cols * rows)

        fun mark(cx: Float, cy: Float, half: Float) {
            val x0 = max(0, ((cx - half) / cellSize).toInt())
            val y0 = max(0, ((cy - half) / cellSize).toInt())
            val x1 = min(cols - 1, ((cx + half) / cellSize).toInt())
            val y1 = min(rows - 1, ((cy + half) / cellSize).toInt())
            for (r in y0..y1) {
                val rowOff = r * cols
                for (c in x0..x1) cells[rowOff + c]++
            }
        }

        fun density(cx: Float, cy: Float, half: Float): Float {
            val x0 = max(0, ((cx - half) / cellSize).toInt())
            val y0 = max(0, ((cy - half) / cellSize).toInt())
            val x1 = min(cols - 1, ((cx + half) / cellSize).toInt())
            val y1 = min(rows - 1, ((cy + half) / cellSize).toInt())
            var sum = 0; var count = 0
            for (r in y0..y1) {
                val rowOff = r * cols
                for (c in x0..x1) { sum += cells[rowOff + c]; count++ }
            }
            return if (count > 0) sum.toFloat() / count else 0f
        }
    }

    // ── Square generation ──

    private fun generateScatterSquares(
        w: Int, h: Int, rng: SeededRNG,
        scaleLevels: Int, scaleRatio: Float, countPerLevel: Int,
        rotRange: Float, gapSeeking: Boolean, numColors: Int,
    ): List<PlacedSquare> {
        val squares = mutableListOf<PlacedSquare>()
        val minDim = min(w, h).toFloat()
        val baseSize = minDim * 0.35f
        val grid = if (gapSeeking) OccupancyGrid(w, h, 64) else null
        val maxRot = rotRange * (PI.toFloat() / 180f)

        for (tier in 0 until scaleLevels) {
            val half = baseSize * scaleRatio.pow(tier.toFloat()) * 0.5f
            if (half < 1f) break

            val count = (countPerLevel * 1.4f.pow(tier.toFloat())).roundToInt()

            for (i in 0 until count) {
                val cx: Float; val cy: Float

                if (grid != null && tier > 0) {
                    var bestCx = 0f; var bestCy = 0f; var bestDensity = Float.MAX_VALUE
                    val candidates = min(12, 4 + tier * 2)
                    for (c in 0 until candidates) {
                        val tx = rng.random() * w
                        val ty = rng.random() * h
                        val d = grid.density(tx, ty, half)
                        if (d < bestDensity) {
                            bestDensity = d; bestCx = tx; bestCy = ty
                        }
                    }
                    cx = bestCx; cy = bestCy
                } else {
                    cx = rng.random() * w; cy = rng.random() * h
                }

                val angle = (rng.random() - 0.5f) * 2f * maxRot
                val colorIdx = (rng.random() * numColors).toInt()
                squares.add(PlacedSquare(cx, cy, half, angle, colorIdx, tier))
                grid?.mark(cx, cy, half)
            }
        }
        return squares
    }

    private fun generatePackedSquares(
        w: Int, h: Int, rng: SeededRNG,
        numColors: Int, minSize: Float,
        scaleLevels: Int, scaleRatio: Float, rotRange: Float, gapSeeking: Boolean,
    ): List<PlacedSquare> {
        val squares = mutableListOf<PlacedSquare>()
        var tier = 0
        val maxRot = rotRange * (PI.toFloat() / 180f)
        val sizeFactor = 0.5f + scaleRatio
        val gapFrac = if (gapSeeking) 0.03f else 0f
        val maxDepth = max(3, scaleLevels + 2)

        fun subdivide(x: Float, y: Float, rw: Float, rh: Float, depth: Int) {
            if (rw < minSize || rh < minSize || depth > maxDepth) return

            val fullSide = min(rw, rh)
            val side = max(minSize, fullSide * min(1f, sizeFactor))
            val gap = side * gapFrac

            val flipH = rng.random() > 0.5f
            val flipV = rng.random() > 0.5f

            val sqX = if (flipH) x + rw - side else x
            val sqY = if (flipV) y + rh - side else y

            val half = max(0.5f, (side - gap * 2f) * 0.5f)
            val angle = if (maxRot > 0f) (rng.random() - 0.5f) * 2f * maxRot else 0f
            squares.add(PlacedSquare(
                sqX + side * 0.5f, sqY + side * 0.5f,
                half, angle, (rng.random() * numColors).toInt(), tier++
            ))

            // Fill remaining L-shaped space
            if (rw >= rh) {
                val remW = rw - side
                if (remW >= minSize) {
                    subdivide(if (flipH) x else x + side, y, remW, rh, depth + 1)
                }
                if (side < rh) {
                    val remH = rh - side
                    if (remH >= minSize) {
                        subdivide(sqX, if (flipV) y else y + side, side, remH, depth + 1)
                    }
                }
            } else {
                val remH = rh - side
                if (remH >= minSize) {
                    subdivide(x, if (flipV) y else y + side, rw, remH, depth + 1)
                }
                if (side < rw) {
                    val remW = rw - side
                    if (remW >= minSize) {
                        subdivide(if (flipH) x else x + side, sqY, remW, side, depth + 1)
                    }
                }
            }
        }

        // Initial grid based on scaleLevels
        val gridBase = max(2, min(6, scaleLevels - 2))
        val cols = gridBase + (rng.random() * 2).toInt()
        val rows = gridBase + (rng.random() * 2).toInt()

        // Random column widths and row heights
        val colWidths = FloatArray(cols) { 0.5f + rng.random() }
        val rowHeights = FloatArray(rows) { 0.5f + rng.random() }
        val totalCW = colWidths.sum()
        val totalRH = rowHeights.sum()

        var cx = 0f
        for (c in 0 until cols) {
            val cellW = (colWidths[c] / totalCW) * w
            var cy = 0f
            for (r in 0 until rows) {
                val cellH = (rowHeights[r] / totalRH) * h
                subdivide(cx, cy, cellW, cellH, 0)
                cy += cellH
            }
            cx += cellW
        }
        return squares
    }

    // ── Rendering ──

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
        val rng = SeededRNG(seed)

        val layout = (params["layout"] as? String) ?: "scatter"
        val scaleLevels = ((params["scaleLevels"] as? Number)?.toInt() ?: 6).coerceIn(3, 10)
        val scaleRatio = ((params["scaleRatio"] as? Number)?.toFloat() ?: 0.5f).coerceIn(0.3f, 0.7f)
        val countPerLevel = ((params["countPerLevel"] as? Number)?.toInt() ?: 80).coerceIn(20, 400)
        val rotRange = ((params["rotationRange"] as? Number)?.toFloat() ?: 45f).coerceIn(0f, 90f)
        val gapSeeking = (params["gapSeeking"] as? Boolean) ?: true
        val strokeWeight = ((params["strokeWeight"] as? Number)?.toFloat() ?: 1f).coerceIn(1f, 10f)
        val colorMode = (params["colorMode"] as? String) ?: "random"
        val fillOpacity = ((params["fillOpacity"] as? Number)?.toFloat() ?: 0.85f).coerceIn(0.2f, 1.0f)
        val renderStyle = (params["renderStyle"] as? String) ?: "filled"
        val nestLayers = ((params["nestLayers"] as? Number)?.toInt() ?: 5).coerceIn(3, 8)
        val animSpeed = (params["speed"] as? Number)?.toFloat() ?: 0.8f
        val animAmp = (params["animAmp"] as? Number)?.toFloat() ?: 0.5f

        val colors = palette.colors.map { Color.parseColor(it) }.toIntArray()
        val numColors = colors.size

        // Background: dark version of first palette color
        val bg = colors[0]
        val bgR = ((bg shr 16 and 0xFF) * 0.1f).toInt()
        val bgG = ((bg shr 8 and 0xFF) * 0.1f).toInt()
        val bgB = ((bg and 0xFF) * 0.1f).toInt()
        canvas.drawColor((0xFF shl 24) or (bgR shl 16) or (bgG shl 8) or bgB)

        // Generate squares
        val minPackedSize = max(4f, min(w, h) * (0.06f - countPerLevel * 0.00012f))
        val squares = if (layout == "packed") {
            generatePackedSquares(w, h, rng, numColors, minPackedSize,
                scaleLevels, scaleRatio, rotRange, gapSeeking)
        } else {
            generateScatterSquares(w, h, rng, scaleLevels, scaleRatio, countPerLevel,
                rotRange, gapSeeking, numColors)
        }

        val t = time * animSpeed
        val isNested = renderStyle == "nested"
        val halfW = w * 0.5f
        val halfH = h * 0.5f
        val maxDist = sqrt(halfW * halfW + halfH * halfH)
        val totalSquares = squares.size
        val maxTier = if (layout == "packed") totalSquares else scaleLevels

        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            this.strokeWidth = strokeWeight
        }

        val fillAlpha = (fillOpacity * 255).toInt().coerceIn(0, 255)
        val strokeAlpha = min(255, (fillOpacity * 255 + 38).toInt())

        for (i in 0 until totalSquares) {
            val sq = squares[i]

            // Resolve color index based on colorMode
            val baseColorIdx = when (colorMode) {
                "sequential" -> i % numColors
                "by-size" -> if (layout == "packed") {
                    ((sq.half / min(w, h) * numColors * 2).toInt()) % numColors
                } else {
                    sq.tier % numColors
                }
                "radial" -> {
                    val dx = sq.cx - halfW
                    val dy = sq.cy - halfH
                    val dist = sqrt(dx * dx + dy * dy) / maxDist
                    (dist * (numColors - 1) + 0.5f).toInt().coerceIn(0, numColors - 1)
                }
                else -> sq.colorIdx // random
            }

            // Animation: rotation drift + scale breathing
            var angle = sq.angle
            var half = sq.half
            if (time > 0f && animAmp > 0f) {
                val phase = i * 0.037f
                angle += sin(t * 0.7f + phase) * 0.3f * animAmp
                val breatheAmt = if (layout == "packed") 0.03f else 0.08f
                half *= 1f + sin(t * 1.1f + phase * 1.7f) * breatheAmt * animAmp
            }

            canvas.save()
            canvas.translate(sq.cx, sq.cy)
            if (angle != 0f) canvas.rotate(angle * (180f / PI.toFloat()))

            val color = colors[baseColorIdx % numColors]

            if (isNested) {
                fillPaint.alpha = fillAlpha
                for (layer in 0 until nestLayers) {
                    val layerHalf = half * (1f - layer.toFloat() / nestLayers)
                    if (layerHalf < 0.5f) break
                    val ci = (baseColorIdx + layer) % numColors
                    fillPaint.color = colors[ci]
                    fillPaint.alpha = fillAlpha
                    canvas.drawRect(-layerHalf, -layerHalf, layerHalf, layerHalf, fillPaint)
                }
            } else {
                val isFilled = renderStyle == "filled" ||
                    (renderStyle == "mixed" && i % 3 != 0)

                if (isFilled) {
                    fillPaint.color = color
                    fillPaint.alpha = fillAlpha
                    canvas.drawRect(-half, -half, half, half, fillPaint)
                }

                if (renderStyle == "outlined" || (renderStyle == "mixed" && i % 3 == 0) || renderStyle == "filled") {
                    if (renderStyle == "outlined") {
                        strokePaint.color = color
                        strokePaint.alpha = strokeAlpha
                    } else {
                        strokePaint.color = 0x40FFFFFF.toInt()
                    }
                    canvas.drawRect(-half, -half, half, half, strokePaint)
                }
            }

            canvas.restore()
        }
    }

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float {
        if ((params["layout"] as? String) == "packed") {
            val nestMul = if ((params["renderStyle"] as? String) == "nested")
                ((params["nestLayers"] as? Number)?.toInt() ?: 5) else 1
            return (0.4f * nestMul).coerceAtMost(1f)
        }
        val levels = ((params["scaleLevels"] as? Number)?.toInt() ?: 6).coerceIn(3, 10)
        val count = ((params["countPerLevel"] as? Number)?.toInt() ?: 80).coerceIn(20, 400)
        val nestMul = if ((params["renderStyle"] as? String) == "nested")
            ((params["nestLayers"] as? Number)?.toInt() ?: 5) else 1
        var total = 0f
        for (i in 0 until levels) total += count * 1.4f.pow(i.toFloat())
        return (total * 0.02f * nestMul).coerceIn(0.1f, 1f)
    }
}
