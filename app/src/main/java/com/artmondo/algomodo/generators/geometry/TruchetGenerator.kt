package com.artmondo.algomodo.generators.geometry

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import com.artmondo.algomodo.core.rng.SeededRNG
import com.artmondo.algomodo.data.palettes.Palette
import com.artmondo.algomodo.generators.Generator
import com.artmondo.algomodo.generators.ParamGroup
import com.artmondo.algomodo.generators.Parameter
import com.artmondo.algomodo.generators.Quality
import kotlin.math.sin

class TruchetGenerator : Generator {

    override val id = "geo-truchet"
    override val family = "geometry"
    override val styleName = "Truchet Tiles"
    override val definition =
        "Flowing patterns created by randomly orienting simple tile motifs that connect across boundaries to form emergent structures."
    override val algorithmNotes =
        "Divides the canvas into a grid of square cells. Each cell is randomly assigned one of " +
        "two orientations using a seeded RNG. The tile motif (arcs, diagonals, quarter-circles, or weave) " +
        "is drawn in the chosen orientation, creating connections with neighboring tiles. " +
        "Animation flips tiles via noise-based wavefronts or smooth phase evolution."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema: List<Parameter> = listOf(
        Parameter.NumberParam(
            name = "Cell Size",
            key = "cellSize",
            group = ParamGroup.GEOMETRY,
            help = "Pixel size of each square tile",
            min = 10f, max = 200f, step = 5f, default = 60f
        ),
        Parameter.SelectParam(
            name = "Variant",
            key = "variant",
            group = ParamGroup.GEOMETRY,
            help = "arc: quarter-circle arcs (classic Truchet 1704) | diagonal: straight lines | quarter: filled quarter-circle wedges | weave: both arc orientations with over-under parity",
            options = listOf("arc", "diagonal", "quarter", "weave"),
            default = "arc"
        ),
        Parameter.NumberParam(
            name = "Stroke Width",
            key = "strokeWidth",
            group = ParamGroup.TEXTURE,
            help = null,
            min = 0.5f, max = 10f, step = 0.5f, default = 2f
        ),
        Parameter.SelectParam(
            name = "Color Mode",
            key = "colorMode",
            group = ParamGroup.COLOR,
            help = "mono: single color | two-tone: alternating palette colors per orientation | depth: diagonal position mapped to palette gradient | noise: smooth palette gradient driven by the underlying noise field",
            options = listOf("mono", "two-tone", "depth", "noise"),
            default = "two-tone"
        ),
        Parameter.SelectParam(
            name = "Background",
            key = "background",
            group = ParamGroup.COLOR,
            help = null,
            options = listOf("dark", "light", "mid"),
            default = "dark"
        ),
        Parameter.NumberParam(
            name = "Bias",
            key = "bias",
            group = ParamGroup.COMPOSITION,
            help = "Orientation distribution bias \u2014 0.5 = 50/50 split; lower values tilt toward orientation 0; higher toward orientation 1",
            min = 0.1f, max = 0.9f, step = 0.05f, default = 0.5f
        ),
        Parameter.SelectParam(
            name = "Anim Mode",
            key = "animMode",
            group = ParamGroup.FLOW_MOTION,
            help = "none: static seed-based | wave: diagonal noise wavefront sweeps through | flow: per-cell slow smooth phase evolution",
            options = listOf("none", "wave", "flow"),
            default = "wave"
        ),
        Parameter.NumberParam(
            name = "Speed",
            key = "speed",
            group = ParamGroup.FLOW_MOTION,
            help = null,
            min = 0.05f, max = 2f, step = 0.05f, default = 0.3f
        )
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "cellSize" to 60f,
        "variant" to "arc",
        "strokeWidth" to 2f,
        "colorMode" to "two-tone",
        "background" to "dark",
        "bias" to 0.5f,
        "animMode" to "wave",
        "speed" to 0.3f
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
        val cellSize = (params["cellSize"] as? Number)?.toFloat() ?: 60f
        val variant = (params["variant"] as? String) ?: "arc"
        val lineWidth = (params["strokeWidth"] as? Number)?.toFloat() ?: 2f
        val colorMode = (params["colorMode"] as? String) ?: "two-tone"
        val background = (params["background"] as? String) ?: "dark"
        val bias = (params["bias"] as? Number)?.toFloat() ?: 0.5f
        val animMode = (params["animMode"] as? String) ?: "wave"
        val speed = (params["speed"] as? Number)?.toFloat() ?: 0.3f

        // Background color
        val bgColor = when (background) {
            "light" -> Color.rgb(240, 240, 235)
            "mid" -> Color.rgb(100, 100, 100)
            else -> Color.BLACK
        }
        canvas.drawColor(bgColor)

        val cols = (w / cellSize).toInt().coerceAtLeast(1)
        val rows = (h / cellSize).toInt().coerceAtLeast(1)
        val cellW = w / cols
        val cellH = h / rows
        val rng = SeededRNG(seed)
        val paletteColors = palette.colorInts()

        val paint = Paint().apply {
            this.strokeWidth = lineWidth
            isAntiAlias = quality != Quality.DRAFT
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        // Pre-generate base tile orientations using bias
        val baseOrientations = Array(rows) { BooleanArray(cols) { rng.boolean(bias) } }

        for (row in 0 until rows) {
            for (col in 0 until cols) {
                val x = col * cellW
                val y = row * cellH

                // Determine orientation based on animation mode
                val flipped = when (animMode) {
                    "none" -> baseOrientations[row][col]
                    "wave" -> {
                        // Diagonal wavefront sweeping across the grid
                        val diag = (row + col).toFloat() / (rows + cols)
                        val wave = sin((diag * 6f - time * speed * 0.8f).toDouble()).toFloat()
                        if (wave > 0f) baseOrientations[row][col]
                        else !baseOrientations[row][col]
                    }
                    "flow" -> {
                        // Per-cell smooth phase based on position hash
                        val cellPhase = ((row * 37 + col * 13 + seed) % 1000) / 1000f
                        val phase = sin((cellPhase * 6.28f + time * speed * 0.5f).toDouble()).toFloat()
                        if (phase > 0f) baseOrientations[row][col]
                        else !baseOrientations[row][col]
                    }
                    else -> baseOrientations[row][col]
                }

                // Determine color based on color mode
                val color0: Int
                val color1: Int
                when (colorMode) {
                    "mono" -> {
                        color0 = paletteColors[0]
                        color1 = paletteColors[0]
                    }
                    "depth" -> {
                        val t = ((row + col).toFloat() / (rows + cols)).coerceIn(0f, 1f)
                        val c = palette.lerpColor(t)
                        color0 = c
                        color1 = c
                    }
                    "noise" -> {
                        // Smooth gradient based on position
                        val t0 = ((row.toFloat() / rows + col.toFloat() / cols) * 0.5f).coerceIn(0f, 1f)
                        val t1 = ((row.toFloat() / rows * 0.7f + col.toFloat() / cols * 0.3f + 0.3f) % 1f)
                        color0 = palette.lerpColor(t0)
                        color1 = palette.lerpColor(t1)
                    }
                    else -> {
                        // "two-tone"
                        color0 = paletteColors[0 % paletteColors.size]
                        color1 = paletteColors[1.coerceAtMost(paletteColors.size - 1)]
                    }
                }

                when (variant) {
                    "arc" -> drawArcTile(canvas, x, y, cellW, cellH, flipped, paint, color0, color1)
                    "diagonal" -> drawDiagonalTile(canvas, x, y, cellW, cellH, flipped, paint, color0)
                    "quarter" -> drawQuarterTile(canvas, x, y, cellW, cellH, flipped, paint, color0, color1)
                    "weave" -> drawWeaveTile(canvas, x, y, cellW, cellH, flipped, row, col, paint, lineWidth, color0, color1)
                }
            }
        }
    }

    private fun drawArcTile(
        canvas: Canvas, x: Float, y: Float, w: Float, h: Float,
        flipped: Boolean, paint: Paint, color0: Int, color1: Int
    ) {
        paint.style = Paint.Style.STROKE
        val r = w / 2f
        if (flipped) {
            paint.color = color0
            canvas.drawArc(RectF(x - r, y - r, x + r, y + r), 0f, 90f, false, paint)
            paint.color = color1
            canvas.drawArc(RectF(x + w - r, y + h - r, x + w + r, y + h + r), 180f, 90f, false, paint)
        } else {
            paint.color = color0
            canvas.drawArc(RectF(x + w - r, y - r, x + w + r, y + r), 90f, 90f, false, paint)
            paint.color = color1
            canvas.drawArc(RectF(x - r, y + h - r, x + r, y + h + r), 270f, 90f, false, paint)
        }
    }

    private fun drawDiagonalTile(
        canvas: Canvas, x: Float, y: Float, w: Float, h: Float,
        flipped: Boolean, paint: Paint, color: Int
    ) {
        paint.style = Paint.Style.STROKE
        paint.color = color
        if (flipped) {
            canvas.drawLine(x, y, x + w, y + h, paint)
        } else {
            canvas.drawLine(x + w, y, x, y + h, paint)
        }
    }

    private fun drawQuarterTile(
        canvas: Canvas, x: Float, y: Float, w: Float, h: Float,
        flipped: Boolean, paint: Paint, color0: Int, color1: Int
    ) {
        // Filled quarter-circle wedges creating organic blob shapes
        paint.style = Paint.Style.FILL
        val r = w
        if (flipped) {
            // Quarter circles at top-left and bottom-right
            paint.color = color0
            val path0 = Path()
            path0.moveTo(x, y)
            path0.arcTo(RectF(x - r, y - r, x + r, y + r), 0f, 90f, false)
            path0.close()
            canvas.drawPath(path0, paint)

            paint.color = color1
            val path1 = Path()
            path1.moveTo(x + w, y + h)
            path1.arcTo(RectF(x + w - r, y + h - r, x + w + r, y + h + r), 180f, 90f, false)
            path1.close()
            canvas.drawPath(path1, paint)
        } else {
            // Quarter circles at top-right and bottom-left
            paint.color = color0
            val path0 = Path()
            path0.moveTo(x + w, y)
            path0.arcTo(RectF(x + w - r, y - r, x + w + r, y + r), 90f, 90f, false)
            path0.close()
            canvas.drawPath(path0, paint)

            paint.color = color1
            val path1 = Path()
            path1.moveTo(x, y + h)
            path1.arcTo(RectF(x - r, y + h - r, x + r, y + h + r), 270f, 90f, false)
            path1.close()
            canvas.drawPath(path1, paint)
        }
    }

    private fun drawWeaveTile(
        canvas: Canvas, x: Float, y: Float, w: Float, h: Float,
        flipped: Boolean, row: Int, col: Int,
        paint: Paint, lineWidth: Float, color0: Int, color1: Int
    ) {
        // Over-under weave pattern: draw both arc orientations, checkerboard determines which is on top
        paint.style = Paint.Style.STROKE
        val r = w / 2f
        val isOver = (row + col) % 2 == 0
        val gap = lineWidth * 1.5f

        // Arc pair A: connects top-left to bottom-right corners
        // Arc pair B: connects top-right to bottom-left corners
        val drawA = {
            paint.color = color0
            canvas.drawArc(RectF(x - r, y - r, x + r, y + r), 0f, 90f, false, paint)
            canvas.drawArc(RectF(x + w - r, y + h - r, x + w + r, y + h + r), 180f, 90f, false, paint)
        }
        val drawB = {
            paint.color = color1
            canvas.drawArc(RectF(x + w - r, y - r, x + w + r, y + r), 90f, 90f, false, paint)
            canvas.drawArc(RectF(x - r, y + h - r, x + r, y + h + r), 270f, 90f, false, paint)
        }

        // Draw under first, then over (with a small gap erased for the crossing)
        val savedWidth = paint.strokeWidth
        if (isOver) {
            drawB()
            // Erase crossing gap
            paint.color = Color.BLACK
            paint.strokeWidth = savedWidth + gap
            // Short segment at center to create gap
            canvas.drawArc(RectF(x - r, y - r, x + r, y + r), 40f, 10f, false, paint)
            canvas.drawArc(RectF(x + w - r, y + h - r, x + w + r, y + h + r), 220f, 10f, false, paint)
            paint.strokeWidth = savedWidth
            drawA()
        } else {
            drawA()
            paint.color = Color.BLACK
            paint.strokeWidth = savedWidth + gap
            canvas.drawArc(RectF(x + w - r, y - r, x + w + r, y + r), 130f, 10f, false, paint)
            canvas.drawArc(RectF(x - r, y + h - r, x + r, y + h + r), 310f, 10f, false, paint)
            paint.strokeWidth = savedWidth
            drawB()
        }
    }

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float {
        val cellSize = (params["cellSize"] as? Number)?.toFloat() ?: 60f
        val gridSize = (1080f / cellSize).toInt().coerceAtLeast(1)
        return (gridSize * gridSize / 900f).coerceIn(0.1f, 1f)
    }
}
