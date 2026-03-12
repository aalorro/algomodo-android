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
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

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

    // ── Background color constants matching web version ──────────────────
    private fun bgColor(key: String): Int = when (key) {
        "white" -> Color.rgb(248, 248, 245)
        "cream" -> Color.rgb(242, 234, 216)
        "dark"  -> Color.rgb(14, 14, 14)
        else    -> Color.rgb(242, 234, 216)
    }

    // ── Maze generation algorithms ──────────────────────────────────────

    private data class MazeWalls(
        val wallH: Array<BooleanArray>, // wallH[row][col], rows-1 rows
        val wallV: Array<BooleanArray>  // wallV[row][col], rows rows, cols-1 cols
    )

    private fun generateMazeDFS(cols: Int, rows: Int, rng: SeededRNG): MazeWalls {
        val wallH = Array(rows - 1) { BooleanArray(cols) { true } }
        val wallV = Array(rows) { BooleanArray(cols - 1) { true } }
        val visited = BooleanArray(cols * rows)
        val stack = mutableListOf(0)
        visited[0] = true
        val dx = intArrayOf(0, 1, 0, -1)
        val dy = intArrayOf(-1, 0, 1, 0)

        while (stack.isNotEmpty()) {
            val curr = stack.last()
            val cx = curr % cols
            val cy = curr / cols
            val nbrs = mutableListOf<Pair<Int, Int>>() // (index, dir)
            for (dir in 0 until 4) {
                val nx = cx + dx[dir]
                val ny = cy + dy[dir]
                if (nx in 0 until cols && ny in 0 until rows && !visited[ny * cols + nx]) {
                    nbrs.add(Pair(ny * cols + nx, dir))
                }
            }
            if (nbrs.isEmpty()) {
                stack.removeAt(stack.size - 1)
                continue
            }
            val (next, dir) = rng.pick(nbrs)
            val nx = cx + dx[dir]
            val ny = cy + dy[dir]
            when (dir) {
                0 -> if (ny >= 0) wallH[ny][cx] = false          // north
                1 -> if (cx < cols - 1) wallV[cy][cx] = false    // east
                2 -> if (cy < rows - 1) wallH[cy][cx] = false    // south
                3 -> if (nx >= 0) wallV[cy][nx] = false           // west
            }
            visited[next] = true
            stack.add(next)
        }
        return MazeWalls(wallH, wallV)
    }

    private fun generateMazeKruskal(cols: Int, rows: Int, rng: SeededRNG): MazeWalls {
        val wallH = Array(rows - 1) { BooleanArray(cols) { true } }
        val wallV = Array(rows) { BooleanArray(cols - 1) { true } }

        // Union-Find
        val parent = IntArray(cols * rows) { -1 }

        fun find(x: Int): Int {
            var v = x
            while (parent[v] >= 0) v = parent[v]
            return v
        }

        fun union(a: Int, b: Int): Boolean {
            val ra = find(a)
            val rb = find(b)
            if (ra == rb) return false
            if (parent[ra] < parent[rb]) {
                parent[ra] += parent[rb]
                parent[rb] = ra
            } else {
                parent[rb] += parent[ra]
                parent[ra] = rb
            }
            return true
        }

        // Collect all edges: (cell1, cell2, isHoriz, r, c)
        data class Edge(val a: Int, val b: Int, val isH: Boolean, val r: Int, val c: Int)

        val edges = mutableListOf<Edge>()
        for (r in 0 until rows - 1) {
            for (c in 0 until cols) {
                edges.add(Edge(r * cols + c, (r + 1) * cols + c, true, r, c))
            }
        }
        for (r in 0 until rows) {
            for (c in 0 until cols - 1) {
                edges.add(Edge(r * cols + c, r * cols + c + 1, false, r, c))
            }
        }
        rng.shuffle(edges)
        for (edge in edges) {
            if (union(edge.a, edge.b)) {
                if (edge.isH) wallH[edge.r][edge.c] = false
                else wallV[edge.r][edge.c] = false
            }
        }
        return MazeWalls(wallH, wallV)
    }

    private fun generateMazeBinaryTree(cols: Int, rows: Int, rng: SeededRNG): MazeWalls {
        val wallH = Array(rows - 1) { BooleanArray(cols) { true } }
        val wallV = Array(rows) { BooleanArray(cols - 1) { true } }
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val canN = r > 0
                val canW = c > 0
                if (canN && canW) {
                    if (rng.random() < 0.5f) wallH[r - 1][c] = false
                    else wallV[r][c - 1] = false
                } else if (canN) {
                    wallH[r - 1][c] = false
                } else if (canW) {
                    wallV[r][c - 1] = false
                }
            }
        }
        return MazeWalls(wallH, wallV)
    }

    private fun generateMazeSidewinder(cols: Int, rows: Int, rng: SeededRNG): MazeWalls {
        val wallH = Array(rows - 1) { BooleanArray(cols) { true } }
        val wallV = Array(rows) { BooleanArray(cols - 1) { true } }
        for (r in 0 until rows) {
            var runStart = 0
            for (c in 0 until cols) {
                if (r == 0) {
                    // First row: carve right
                    if (c < cols - 1) wallV[r][c] = false
                } else {
                    val closeRun = c == cols - 1 || rng.random() < 0.5f
                    if (closeRun) {
                        val pick = runStart + floor(rng.random() * (c - runStart + 1)).toInt()
                        wallH[r - 1][pick] = false // carve north from random cell in run
                        runStart = c + 1
                    } else {
                        wallV[r][c] = false // carve east
                    }
                }
            }
        }
        return MazeWalls(wallH, wallV)
    }

    private fun generateMaze(cols: Int, rows: Int, rng: SeededRNG, algorithm: String): MazeWalls {
        return when (algorithm) {
            "kruskal" -> generateMazeKruskal(cols, rows, rng)
            "binary-tree" -> generateMazeBinaryTree(cols, rows, rng)
            "sidewinder" -> generateMazeSidewinder(cols, rows, rng)
            else -> generateMazeDFS(cols, rows, rng)
        }
    }

    // ── BFS from cell (0,0) ─────────────────────────────────────────────

    private fun bfs(cols: Int, rows: Int, wallH: Array<BooleanArray>, wallV: Array<BooleanArray>): IntArray {
        val dist = IntArray(cols * rows) { -1 }
        val queue = mutableListOf(0)
        dist[0] = 0
        val dx = intArrayOf(0, 1, 0, -1)
        val dy = intArrayOf(-1, 0, 1, 0)
        var head = 0

        while (head < queue.size) {
            val curr = queue[head++]
            val cx = curr % cols
            val cy = curr / cols
            for (dir in 0 until 4) {
                val nx = cx + dx[dir]
                val ny = cy + dy[dir]
                if (nx < 0 || nx >= cols || ny < 0 || ny >= rows) continue
                val passable = when (dir) {
                    0 -> cy > 0 && !wallH[cy - 1][cx]           // north
                    1 -> cx < cols - 1 && !wallV[cy][cx]        // east
                    2 -> cy < rows - 1 && !wallH[cy][cx]        // south
                    3 -> cx > 0 && !wallV[cy][cx - 1]           // west
                    else -> false
                }
                if (passable) {
                    val ni = ny * cols + nx
                    if (dist[ni] == -1) {
                        dist[ni] = dist[curr] + 1
                        queue.add(ni)
                    }
                }
            }
        }
        return dist
    }

    // ── Reconstruct shortest path from cell 0 to target ────────────────

    private fun solvePath(
        cols: Int, rows: Int,
        wallH: Array<BooleanArray>, wallV: Array<BooleanArray>,
        dist: IntArray, target: Int
    ): List<Int> {
        val path = mutableListOf(target)
        var curr = target
        val dx = intArrayOf(0, 1, 0, -1)
        val dy = intArrayOf(-1, 0, 1, 0)

        while (curr != 0) {
            val cx = curr % cols
            val cy = curr / cols
            for (dir in 0 until 4) {
                val nx = cx + dx[dir]
                val ny = cy + dy[dir]
                if (nx < 0 || nx >= cols || ny < 0 || ny >= rows) continue
                val passable = when (dir) {
                    0 -> cy > 0 && !wallH[cy - 1][cx]
                    1 -> cx < cols - 1 && !wallV[cy][cx]
                    2 -> cy < rows - 1 && !wallH[cy][cx]
                    3 -> cx > 0 && !wallV[cy][cx - 1]
                    else -> false
                }
                if (passable) {
                    val ni = ny * cols + nx
                    if (dist[ni] == dist[curr] - 1) {
                        path.add(ni)
                        curr = ni
                        break
                    }
                }
            }
        }
        return path.reversed()
    }

    // ── Main render ─────────────────────────────────────────────────────

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

        val rng = SeededRNG(seed)
        val noise = SimplexNoise(seed)

        // Read parameters
        val cellSize = max(8f, (params["cellSize"] as? Number)?.toFloat() ?: 30f)
        val margin = max(0f, (params["margin"] as? Number)?.toFloat() ?: 0.04f)
        val style = (params["style"] as? String) ?: "maze"
        val algorithm = (params["algorithm"] as? String) ?: "dfs"
        val wallStyle = (params["wallStyle"] as? String) ?: "straight"
        val showSolution = (params["showSolution"] as? Boolean) ?: false
        val fillCells = (params["fillCells"] as? Boolean) ?: false
        val lineWidth = (params["lineWidth"] as? Number)?.toFloat() ?: 1.25f
        val colorMode = (params["colorMode"] as? String) ?: "palette-distance"
        val background = (params["background"] as? String) ?: "cream"
        val speed = (params["speed"] as? Number)?.toFloat() ?: 0f
        val colorShift = if (speed > 0f && time > 0f) (time * speed * 3f).toInt() else 0
        val isDark = background == "dark"

        // Background
        canvas.drawColor(bgColor(background))

        // Margin and grid sizing (matching web version)
        val mx = w * margin
        val my = h * margin
        val availW = w - 2f * mx
        val availH = h - 2f * my
        val cols = max(2, (availW / cellSize).toInt())
        val rows = max(2, (availH / cellSize).toInt())
        val cw = availW / cols
        val ch = availH / rows

        // Palette colors with optional animation shift
        val basePaletteColors = palette.colorInts()
        val paletteColors = if (colorShift > 0 && basePaletteColors.isNotEmpty()) {
            val shift = colorShift % basePaletteColors.size
            basePaletteColors.drop(shift) + basePaletteColors.take(shift)
        } else {
            basePaletteColors
        }

        val maxDist = cols + rows

        // ── Color helpers ───────────────────────────────────────────────

        fun interpColor(t: Float): Int {
            val ct = t.coerceIn(0f, 1f) * (paletteColors.size - 1)
            val i0 = ct.toInt().coerceAtMost(paletteColors.size - 2)
            val i1 = min(paletteColors.size - 1, i0 + 1)
            val f = ct - i0
            val c0 = paletteColors[i0]
            val c1 = paletteColors[i1]
            val r = (Color.red(c0) + (Color.red(c1) - Color.red(c0)) * f).toInt()
            val g = (Color.green(c0) + (Color.green(c1) - Color.green(c0)) * f).toInt()
            val b = (Color.blue(c0) + (Color.blue(c1) - Color.blue(c0)) * f).toInt()
            return Color.rgb(r, g, b)
        }

        fun getColor(col: Int, row: Int, dist: Int): Int {
            val baseColor = when (colorMode) {
                "monochrome" -> {
                    if (isDark) Color.rgb(220, 220, 220) else Color.rgb(30, 30, 30)
                }
                "palette-distance" -> {
                    interpColor(dist.toFloat() / maxDist)
                }
                "palette-zone" -> {
                    interpColor(col.toFloat() / cols * 0.5f + row.toFloat() / rows * 0.5f)
                }
                "palette-noise" -> {
                    val nv = noise.fbm(
                        col.toFloat() / cols * 3f + 5f,
                        row.toFloat() / rows * 3f + 5f,
                        3, 2f, 0.5f
                    )
                    interpColor(max(0f, nv * 0.5f + 0.5f))
                }
                else -> interpColor(dist.toFloat() / maxDist)
            }
            // Apply alpha matching web: isDark ? 0.85 : 0.82
            val alpha = if (isDark) 217 else 209 // 0.85*255≈217, 0.82*255≈209
            return Color.argb(alpha, Color.red(baseColor), Color.green(baseColor), Color.blue(baseColor))
        }

        // ── Paint setup ─────────────────────────────────────────────────

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.style = Paint.Style.STROKE
            strokeWidth = lineWidth
            strokeCap = Paint.Cap.SQUARE
            strokeJoin = Paint.Join.MITER
        }

        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.style = Paint.Style.FILL
        }

        // ── Wall drawing helper ─────────────────────────────────────────

        fun drawWall(x1: Float, y1: Float, x2: Float, y2: Float, col: Int, row: Int, dist: Int) {
            paint.color = getColor(col, row, dist)

            when (wallStyle) {
                "rounded" -> {
                    // Slight curve through a noise-offset midpoint
                    val midX = (x1 + x2) / 2f
                    val midY = (y1 + y2) / 2f
                    val n1 = noise.noise2D(col * 0.7f + row * 0.3f, row * 0.7f + col * 0.3f)
                    val off = lineWidth * 1.5f
                    // Perpendicular offset
                    val ddx = x2 - x1
                    val ddy = y2 - y1
                    val len = sqrt(ddx * ddx + ddy * ddy).coerceAtLeast(1f)
                    val px = -ddy / len
                    val py = ddx / len

                    val path = Path()
                    path.moveTo(x1, y1)
                    path.quadTo(
                        midX + px * n1 * off,
                        midY + py * n1 * off,
                        x2, y2
                    )
                    canvas.drawPath(path, paint)
                }
                "wobbly" -> {
                    // Multi-point wobbly line
                    val steps = 6
                    val ddx = x2 - x1
                    val ddy = y2 - y1
                    val len = sqrt(ddx * ddx + ddy * ddy).coerceAtLeast(1f)
                    val px = -ddy / len
                    val py = ddx / len

                    val path = Path()
                    path.moveTo(x1, y1)
                    for (s in 1..steps) {
                        val t = s.toFloat() / steps
                        val bx = x1 + ddx * t
                        val by = y1 + ddy * t
                        val n1 = noise.noise2D(bx * 0.03f + seed * 0.001f, by * 0.03f)
                        val wobble = n1 * cellSize * 0.15f
                        if (s < steps) {
                            path.lineTo(bx + px * wobble, by + py * wobble)
                        } else {
                            path.lineTo(x2, y2)
                        }
                    }
                    canvas.drawPath(path, paint)
                }
                else -> {
                    // Straight
                    canvas.drawLine(x1, y1, x2, y2, paint)
                }
            }
        }

        // ── Rendering ───────────────────────────────────────────────────

        if (style == "maze") {
            val (wallH, wallV) = generateMaze(cols, rows, rng, algorithm)
            val dist = bfs(cols, rows, wallH, wallV)
            var maxBFS = 1
            for (d in dist) {
                if (d > maxBFS) maxBFS = d
            }

            // Fill cells with color heatmap
            if (fillCells) {
                for (r in 0 until rows) {
                    for (c in 0 until cols) {
                        val d = dist[r * cols + c]
                        if (d < 0) continue
                        val t = d.toFloat() / maxBFS
                        val baseColor = interpColor(t)
                        val alpha = if (isDark) 64 else 46 // 0.25*255≈64, 0.18*255≈46
                        fillPaint.color = Color.argb(
                            alpha,
                            Color.red(baseColor),
                            Color.green(baseColor),
                            Color.blue(baseColor)
                        )
                        canvas.drawRect(
                            mx + c * cw,
                            my + r * ch,
                            mx + (c + 1) * cw,
                            my + (r + 1) * ch,
                            fillPaint
                        )
                    }
                }
            }

            // Outer border
            paint.color = getColor(0, 0, 0)
            paint.style = Paint.Style.STROKE
            canvas.drawRect(mx, my, mx + availW, my + availH, paint)

            // Horizontal walls (wallH has rows-1 rows)
            for (row in 0 until rows - 1) {
                for (col in 0 until cols) {
                    if (wallH[row][col]) {
                        val d = dist[row * cols + col]
                        drawWall(
                            mx + col * cw, my + (row + 1) * ch,
                            mx + (col + 1) * cw, my + (row + 1) * ch,
                            col, row, if (d >= 0) d else 0
                        )
                    }
                }
            }

            // Vertical walls (wallV has rows rows, cols-1 cols)
            for (row in 0 until rows) {
                for (col in 0 until cols - 1) {
                    if (wallV[row][col]) {
                        val d = dist[row * cols + col]
                        drawWall(
                            mx + (col + 1) * cw, my + row * ch,
                            mx + (col + 1) * cw, my + (row + 1) * ch,
                            col, row, if (d >= 0) d else 0
                        )
                    }
                }
            }

            // Solution path overlay
            if (showSolution) {
                val target = (rows - 1) * cols + (cols - 1)
                if (dist[target] >= 0) {
                    val path = solvePath(cols, rows, wallH, wallV, dist, target)
                    val solPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        this.style = Paint.Style.STROKE
                        strokeWidth = lineWidth * 2.5f
                        strokeCap = Paint.Cap.ROUND
                        strokeJoin = Paint.Join.ROUND
                        color = if (isDark)
                            Color.argb(179, 255, 100, 100)  // rgba(255,100,100,0.7)
                        else
                            Color.argb(140, 220, 40, 40)    // rgba(220,40,40,0.55)
                    }
                    val solPath = Path()
                    for (i in path.indices) {
                        val px = mx + (path[i] % cols + 0.5f) * cw
                        val py = my + (path[i] / cols + 0.5f) * ch
                        if (i == 0) solPath.moveTo(px, py) else solPath.lineTo(px, py)
                    }
                    canvas.drawPath(solPath, solPaint)
                }
            }
        } else {
            // Meander: boustrophedon serpentine path
            // Total path length for proper distance normalization
            val totalPathLen = rows * cols

            // Fill cells with color heatmap (same as maze mode)
            if (fillCells) {
                for (r in 0 until rows) {
                    for (c in 0 until cols) {
                        // Path distance: serpentine order
                        val d = if (r % 2 == 0) r * cols + c else r * cols + (cols - 1 - c)
                        val t = d.toFloat() / totalPathLen
                        val baseColor = interpColor(t)
                        val alpha = if (isDark) 64 else 46
                        fillPaint.color = Color.argb(
                            alpha,
                            Color.red(baseColor),
                            Color.green(baseColor),
                            Color.blue(baseColor)
                        )
                        canvas.drawRect(
                            mx + c * cw,
                            my + r * ch,
                            mx + (c + 1) * cw,
                            my + (r + 1) * ch,
                            fillPaint
                        )
                    }
                }
            }

            for (row in 0 until rows) {
                val y = my + (row + 0.5f) * ch

                if (row % 2 == 0) {
                    // Left to right
                    var prevX = mx
                    var prevY = y
                    for (col in 0..cols) {
                        val x = mx + col * cw
                        val c = min(col, cols - 1)
                        val dist = row * cols + c
                        drawWall(prevX, prevY, x, y, c, row, dist * maxDist / totalPathLen)
                        prevX = x
                        prevY = y
                    }
                    // Connector down to next row
                    if (row < rows - 1) {
                        val x = mx + cols * cw
                        val nextY = my + (row + 1.5f) * ch
                        val dist = row * cols + (cols - 1)
                        drawWall(x, y, x, nextY, cols - 1, row, dist * maxDist / totalPathLen)
                    }
                } else {
                    // Right to left
                    var prevX = mx + cols * cw
                    var prevY = y
                    for (col in cols downTo 0) {
                        val x = mx + col * cw
                        val c = max(col, 0)
                        val dist = row * cols + (cols - 1 - c)
                        drawWall(prevX, prevY, x, y, c, row, dist * maxDist / totalPathLen)
                        prevX = x
                        prevY = y
                    }
                    // Connector down to next row
                    if (row < rows - 1) {
                        val x = mx
                        val nextY = my + (row + 1.5f) * ch
                        val dist = (row + 1) * cols
                        drawWall(x, y, x, nextY, 0, row, dist * maxDist / totalPathLen)
                    }
                }
            }
        }
    }

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float {
        val cellSize = (params["cellSize"] as? Number)?.toFloat() ?: 20f
        return (20f / cellSize).coerceIn(0.2f, 1f)
    }
}
