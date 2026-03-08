package com.artmondo.algomodo.generators.plotter

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
import com.artmondo.algomodo.rendering.SvgPath

/**
 * Meander / maze / Hilbert-curve space-filling patterns.
 *
 * Fills the canvas with a continuous space-filling path using one of three
 * styles: Greek key meander, recursive backtracking maze, or Hilbert curve.
 */
class PlotterMeanderMazeGenerator : Generator {

    override val id = "plotter-meander-maze"
    override val family = "plotter"
    override val styleName = "Meander / Maze"
    override val definition =
        "Space-filling maze and meander patterns drawn as a continuous path."
    override val algorithmNotes =
        "In 'meander' mode, a Greek key pattern is tiled across a grid. In 'maze' mode, " +
        "recursive backtracker carves a perfect maze and the solution path is rendered. " +
        "In 'hilbert' mode, a Hilbert curve of appropriate order fills the grid."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema = listOf(
        Parameter.NumberParam("Cell Size", "cellSize", ParamGroup.COMPOSITION, "Size of each maze cell in pixels", 12f, 80f, 2f, 30f),
        Parameter.NumberParam("Margin", "margin", ParamGroup.COMPOSITION, "Border margin as fraction of canvas", 0.01f, 0.12f, 0.01f, 0.04f),
        Parameter.SelectParam("Style", "style", ParamGroup.COMPOSITION, "maze: carved labyrinth | meander: serpentine Greek-key fill", listOf("maze", "meander"), "maze"),
        Parameter.SelectParam("Algorithm", "algorithm", ParamGroup.COMPOSITION, "dfs: long winding corridors | kruskal: uniform random | binary-tree: diagonal bias | sidewinder: horizontal runs", listOf("dfs", "kruskal", "binary-tree", "sidewinder"), "dfs"),
        Parameter.SelectParam("Wall Style", "wallStyle", ParamGroup.TEXTURE, "straight: crisp grid | rounded: smooth corners | wobbly: noise-perturbed organic lines", listOf("straight", "rounded", "wobbly"), "straight"),
        Parameter.BooleanParam("Show Solution", "showSolution", ParamGroup.TEXTURE, "Highlight the path from top-left to bottom-right", false),
        Parameter.BooleanParam("Fill Cells", "fillCells", ParamGroup.COLOR, "Color-fill each cell by BFS distance — creates a heatmap effect", false),
        Parameter.NumberParam("Line Width", "lineWidth", ParamGroup.TEXTURE, null, 0.5f, 4f, 0.25f, 1.25f),
        Parameter.SelectParam("Color Mode", "colorMode", ParamGroup.COLOR, "palette-distance: BFS distance drives gradient | palette-zone: diagonal zone | palette-noise: FBM tint", listOf("monochrome", "palette-distance", "palette-zone", "palette-noise"), "palette-distance"),
        Parameter.SelectParam("Background", "background", ParamGroup.COLOR, null, listOf("white", "cream", "dark"), "cream"),
        Parameter.NumberParam("Speed", "speed", ParamGroup.FLOW_MOTION, "Animation speed — color cycling", 0f, 2f, 0.1f, 0f)
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "cellSize" to 30f,
        "margin" to 0.04f,
        "style" to "maze",
        "algorithm" to "dfs",
        "wallStyle" to "straight",
        "showSolution" to false,
        "fillCells" to false,
        "lineWidth" to 1.25f,
        "colorMode" to "palette-distance",
        "background" to "cream",
        "speed" to 0f
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
        val cellSize = (params["cellSize"] as? Number)?.toFloat() ?: 20f
        val style = (params["style"] as? String) ?: "meander"
        val lineWidth = (params["lineWidth"] as? Number)?.toFloat() ?: 2f

        val speed = (params["speed"] as? Number)?.toFloat() ?: 0f
        val colorShift = if (speed > 0f && time > 0f) (time * speed * 3f).toInt() else 0

        val rng = SeededRNG(seed)
        val basePaletteColors = palette.colorInts()
        // Rotate palette by colorShift for animation
        val paletteColors = if (colorShift > 0 && basePaletteColors.isNotEmpty()) {
            val shift = colorShift % basePaletteColors.size
            basePaletteColors.drop(shift) + basePaletteColors.take(shift)
        } else {
            basePaletteColors
        }

        canvas.drawColor(Color.BLACK)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.style = Paint.Style.STROKE
            strokeWidth = lineWidth
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        when (style) {
            "meander" -> drawMeander(canvas, paint, w, h, cellSize, paletteColors)
            "maze" -> drawMaze(canvas, paint, w, h, cellSize, rng, paletteColors)
            "hilbert" -> drawHilbert(canvas, paint, w, h, cellSize, paletteColors)
        }
    }

    private fun drawMeander(canvas: Canvas, paint: Paint, w: Float, h: Float, cellSize: Float, colors: List<Int>) {
        val cols = (w / cellSize).toInt()
        val rows = (h / cellSize).toInt()
        val halfCell = cellSize / 2f

        var colorIdx = 0
        for (row in 0 until rows) {
            val path = Path()
            val y = row * cellSize + halfCell

            if (row % 2 == 0) {
                path.moveTo(0f, y)
                for (col in 0 until cols) {
                    val x = col * cellSize + halfCell
                    if (col % 2 == 0) {
                        path.lineTo(x, y - halfCell * 0.7f)
                        path.lineTo(x + halfCell * 0.7f, y - halfCell * 0.7f)
                        path.lineTo(x + halfCell * 0.7f, y + halfCell * 0.7f)
                        path.lineTo(x + cellSize, y)
                    } else {
                        path.lineTo(x, y + halfCell * 0.7f)
                        path.lineTo(x + halfCell * 0.7f, y + halfCell * 0.7f)
                        path.lineTo(x + halfCell * 0.7f, y - halfCell * 0.7f)
                        path.lineTo(x + cellSize, y)
                    }
                }
            } else {
                path.moveTo(w, y)
                for (col in cols - 1 downTo 0) {
                    val x = col * cellSize + halfCell
                    if (col % 2 == 0) {
                        path.lineTo(x, y + halfCell * 0.7f)
                        path.lineTo(x - halfCell * 0.7f, y + halfCell * 0.7f)
                        path.lineTo(x - halfCell * 0.7f, y - halfCell * 0.7f)
                        path.lineTo(x - cellSize, y)
                    } else {
                        path.lineTo(x, y - halfCell * 0.7f)
                        path.lineTo(x - halfCell * 0.7f, y - halfCell * 0.7f)
                        path.lineTo(x - halfCell * 0.7f, y + halfCell * 0.7f)
                        path.lineTo(x - cellSize, y)
                    }
                }
            }

            paint.color = colors[colorIdx % colors.size]
            canvas.drawPath(path, paint)
            colorIdx++
        }
    }

    private fun drawMaze(canvas: Canvas, paint: Paint, w: Float, h: Float, cellSize: Float, rng: SeededRNG, colors: List<Int>) {
        val cols = (w / cellSize).toInt()
        val rows = (h / cellSize).toInt()
        val totalCells = cols * rows

        // Recursive backtracker maze generation
        val visited = BooleanArray(totalCells)
        // Walls: bit 0=N, 1=E, 2=S, 3=W
        val walls = IntArray(totalCells) { 0b1111 }
        val stack = mutableListOf<Int>()

        val dx = intArrayOf(0, 1, 0, -1)
        val dy = intArrayOf(-1, 0, 1, 0)
        val opposite = intArrayOf(2, 3, 0, 1)

        var current = rng.integer(0, totalCells - 1)
        visited[current] = true
        stack.add(current)

        while (stack.isNotEmpty()) {
            val cx = current % cols
            val cy = current / cols
            val neighbors = mutableListOf<Int>()
            for (d in 0..3) {
                val nx = cx + dx[d]
                val ny = cy + dy[d]
                if (nx in 0 until cols && ny in 0 until rows) {
                    val ni = ny * cols + nx
                    if (!visited[ni]) neighbors.add(d)
                }
            }
            if (neighbors.isNotEmpty()) {
                val dir = rng.pick(neighbors)
                val nx = cx + dx[dir]
                val ny = cy + dy[dir]
                val ni = ny * cols + nx
                walls[current] = walls[current] and (1 shl dir).inv()
                walls[ni] = walls[ni] and (1 shl opposite[dir]).inv()
                visited[ni] = true
                stack.add(current)
                current = ni
            } else {
                current = stack.removeAt(stack.size - 1)
            }
        }

        // Draw maze walls
        val wallPaint = Paint(paint).apply {
            color = colors[0]
            alpha = 200
        }

        for (cy in 0 until rows) {
            for (cx in 0 until cols) {
                val idx = cy * cols + cx
                val x = cx * cellSize
                val y = cy * cellSize

                if (walls[idx] and 0b0001 != 0) { // North
                    canvas.drawLine(x, y, x + cellSize, y, wallPaint)
                }
                if (walls[idx] and 0b0010 != 0) { // East
                    canvas.drawLine(x + cellSize, y, x + cellSize, y + cellSize, wallPaint)
                }
                if (walls[idx] and 0b0100 != 0) { // South
                    canvas.drawLine(x, y + cellSize, x + cellSize, y + cellSize, wallPaint)
                }
                if (walls[idx] and 0b1000 != 0) { // West
                    canvas.drawLine(x, y, x, y + cellSize, wallPaint)
                }
            }
        }

        // Draw the path visiting all cells (DFS path coloured)
        val pathPaint = Paint(paint).apply {
            strokeWidth = paint.strokeWidth * 0.5f
        }
        val pathVisited = BooleanArray(totalCells)
        val pathStack = mutableListOf(0)
        pathVisited[0] = true
        var step = 0

        while (pathStack.isNotEmpty()) {
            val cell = pathStack.last()
            val cx = cell % cols
            val cy = cell / cols
            var found = false

            for (d in 0..3) {
                if (walls[cell] and (1 shl d) != 0) continue
                val nx = cx + dx[d]
                val ny = cy + dy[d]
                if (nx < 0 || nx >= cols || ny < 0 || ny >= rows) continue
                val ni = ny * cols + nx
                if (pathVisited[ni]) continue

                pathVisited[ni] = true
                pathStack.add(ni)

                val t = step.toFloat() / totalCells.coerceAtLeast(1)
                pathPaint.color = colors[(step / 3) % colors.size]
                pathPaint.alpha = 120
                val x1 = cx * cellSize + cellSize / 2f
                val y1 = cy * cellSize + cellSize / 2f
                val x2 = nx * cellSize + cellSize / 2f
                val y2 = ny * cellSize + cellSize / 2f
                canvas.drawLine(x1, y1, x2, y2, pathPaint)
                step++
                found = true
                break
            }
            if (!found) pathStack.removeAt(pathStack.size - 1)
        }
    }

    private fun drawHilbert(canvas: Canvas, paint: Paint, w: Float, h: Float, cellSize: Float, colors: List<Int>) {
        val cols = (w / cellSize).toInt()
        val rows = (h / cellSize).toInt()
        val size = minOf(cols, rows)

        // Find largest power of 2 that fits
        var order = 1
        var n = 2
        while (n * 2 <= size) {
            n *= 2
            order++
        }

        val points = mutableListOf<Pair<Float, Float>>()
        hilbert(0, 0, n, 0, 1, points)

        val scaleX = w / n
        val scaleY = h / n

        if (points.size < 2) return

        val path = Path()
        path.moveTo(points[0].first * scaleX + scaleX / 2f, points[0].second * scaleY + scaleY / 2f)
        for (i in 1 until points.size) {
            path.lineTo(points[i].first * scaleX + scaleX / 2f, points[i].second * scaleY + scaleY / 2f)
        }

        // Draw segments with color gradient
        val segmentCount = points.size - 1
        val segPaint = Paint(paint)
        for (i in 0 until segmentCount) {
            val t = i.toFloat() / segmentCount
            segPaint.color = colors[(i * colors.size / segmentCount.coerceAtLeast(1)) % colors.size]
            canvas.drawLine(
                points[i].first * scaleX + scaleX / 2f,
                points[i].second * scaleY + scaleY / 2f,
                points[i + 1].first * scaleX + scaleX / 2f,
                points[i + 1].second * scaleY + scaleY / 2f,
                segPaint
            )
        }
    }

    private fun hilbert(x: Int, y: Int, size: Int, dx: Int, dy: Int, points: MutableList<Pair<Float, Float>>) {
        if (size <= 1) {
            points.add(x.toFloat() to y.toFloat())
            return
        }
        val half = size / 2
        hilbert(x, y, half, dy, dx, points)
        hilbert(x + dx * half, y + dy * half, half, dx, dy, points)
        hilbert(x + dx * half + dy * half, y + dy * half + dx * half, half, dx, dy, points)
        hilbert(x + dx * (size - 1), y + dy * (size - 1), half, -dy, -dx, points)
    }

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float {
        val cellSize = (params["cellSize"] as? Number)?.toFloat() ?: 20f
        return (20f / cellSize).coerceIn(0.2f, 1f)
    }
}
