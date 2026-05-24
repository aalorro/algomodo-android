package com.artmondo.algomodo.generators.pixelart

import android.graphics.Bitmap
import android.graphics.Canvas
import com.artmondo.algomodo.core.rng.SeededRNG
import com.artmondo.algomodo.data.palettes.Palette
import com.artmondo.algomodo.generators.Generator
import com.artmondo.algomodo.generators.ParamGroup
import com.artmondo.algomodo.generators.Parameter
import com.artmondo.algomodo.generators.Quality
import java.util.ArrayDeque
import kotlin.math.min

class PixelMazeGenerator : Generator {
    override val id = "pixel-maze"
    override val family = "pixel-art"
    override val styleName = "Pixel Maze"
    override val definition = "A procedurally generated maze on a coarse pixel grid with optional solution path and dead-end highlighting."
    override val algorithmNotes = "Generates a maze using recursive backtracking, Prim's, or Kruskal's algorithm. Walls and paths map to palette colors. BFS solves the maze for optional solution display. Animation reveals the maze incrementally."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema = listOf(
        Parameter.SelectParam("Algorithm", "algorithm", ParamGroup.COMPOSITION, "Maze generation algorithm", listOf("backtracker", "prims", "kruskals"), "backtracker"),
        Parameter.NumberParam("Grid Size", "gridSize", ParamGroup.GEOMETRY, "Pixel grid resolution (maze cells = (size-1)/2)", 32f, 128f, 8f, 64f),
        Parameter.BooleanParam("Show Solution", "showSolution", ParamGroup.COLOR, "Highlight the solution path", false),
        Parameter.BooleanParam("Show Dead Ends", "showDeadEnds", ParamGroup.COLOR, "Color dead-end cells differently", false),
        Parameter.NumberParam("Anim Speed", "animSpeed", ParamGroup.FLOW_MOTION, "Animation speed (maze carving reveal)", 0.1f, 3f, 0.1f, 1f),
        Parameter.NumberParam("Steps/Frame", "stepsPerFrame", ParamGroup.FLOW_MOTION, "Cells revealed per frame during animation", 1f, 50f, 1f, 10f),
        Parameter.NumberParam("Reactivity", "reactivity", ParamGroup.FLOW_MOTION, "Audio reactivity strength", 0f, 2f, 0.1f, 0f)
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "algorithm" to "backtracker", "gridSize" to 64f, "showSolution" to false,
        "showDeadEnds" to false, "animSpeed" to 1f, "stepsPerFrame" to 10f, "reactivity" to 0f
    )

    companion object {
        @Volatile private var animKey: String = ""
        @Volatile private var stateGrid: ByteArray = ByteArray(0)
        @Volatile private var stateFullGrid: ByteArray = ByteArray(0)
        @Volatile private var stateCarveOrder: IntArray = IntArray(0)
        @Volatile private var stateCarveIdx: Int = 0
        @Volatile private var stateGw: Int = 0
        @Volatile private var stateGh: Int = 0

        private val DIRS = arrayOf(intArrayOf(0, -1), intArrayOf(1, 0), intArrayOf(0, 1), intArrayOf(-1, 0))
    }

    private fun genBacktracker(grid: ByteArray, gw: Int, w: Int, h: Int, rng: SeededRNG) {
        val visited = ByteArray(w * h)
        val stack = ArrayDeque<Int>()
        visited[0] = 1
        grid[1 * gw + 1] = 1
        stack.push(0)
        while (stack.isNotEmpty()) {
            val ci = stack.peek()
            val cx = ci % w; val cy = ci / w
            val neighbors = ArrayList<Int>(4)
            for (d in 0 until 4) {
                val nx = cx + DIRS[d][0]; val ny = cy + DIRS[d][1]
                if (nx in 0 until w && ny in 0 until h && visited[ny * w + nx].toInt() == 0) {
                    neighbors.add(d)
                }
            }
            if (neighbors.isEmpty()) { stack.pop(); continue }
            val d = neighbors[rng.integer(0, neighbors.size - 1)]
            val nx = cx + DIRS[d][0]; val ny = cy + DIRS[d][1]
            val wallX = cx * 2 + 1 + DIRS[d][0]
            val wallY = cy * 2 + 1 + DIRS[d][1]
            grid[wallY * gw + wallX] = 1
            grid[(ny * 2 + 1) * gw + (nx * 2 + 1)] = 1
            visited[ny * w + nx] = 1
            stack.push(ny * w + nx)
        }
    }

    private fun genPrims(grid: ByteArray, gw: Int, w: Int, h: Int, rng: SeededRNG) {
        val inMaze = ByteArray(w * h)
        val frontier = ArrayList<IntArray>() // [cellIdx, fromCellIdx, dirIdx]
        val sx = rng.integer(0, w - 1); val sy = rng.integer(0, h - 1)
        inMaze[sy * w + sx] = 1
        grid[(sy * 2 + 1) * gw + (sx * 2 + 1)] = 1
        for (d in 0 until 4) {
            val nx = sx + DIRS[d][0]; val ny = sy + DIRS[d][1]
            if (nx in 0 until w && ny in 0 until h) {
                frontier.add(intArrayOf(ny * w + nx, sy * w + sx, d))
            }
        }
        while (frontier.isNotEmpty()) {
            val fi = rng.integer(0, frontier.size - 1)
            val entry = frontier[fi]
            frontier[fi] = frontier[frontier.size - 1]
            frontier.removeAt(frontier.size - 1)
            val ci = entry[0]; val fi2 = entry[1]; val d = entry[2]
            if (inMaze[ci].toInt() != 0) continue
            val cx = ci % w; val cy = ci / w
            val fx = fi2 % w; val fy = fi2 / w
            inMaze[ci] = 1
            grid[(cy * 2 + 1) * gw + (cx * 2 + 1)] = 1
            val wallX = fx * 2 + 1 + DIRS[d][0]
            val wallY = fy * 2 + 1 + DIRS[d][1]
            grid[wallY * gw + wallX] = 1
            for (dd in 0 until 4) {
                val nx = cx + DIRS[dd][0]; val ny = cy + DIRS[dd][1]
                if (nx in 0 until w && ny in 0 until h && inMaze[ny * w + nx].toInt() == 0) {
                    frontier.add(intArrayOf(ny * w + nx, ci, dd))
                }
            }
        }
    }

    private fun genKruskals(grid: ByteArray, gw: Int, w: Int, h: Int, rng: SeededRNG) {
        val parent = IntArray(w * h) { it }
        val rank = ByteArray(w * h)
        fun find(x0: Int): Int {
            var x = x0
            while (parent[x] != x) { parent[x] = parent[parent[x]]; x = parent[x] }
            return x
        }
        fun union(a: Int, b: Int): Boolean {
            val ra = find(a); val rb = find(b)
            if (ra == rb) return false
            if (rank[ra] < rank[rb]) parent[ra] = rb
            else if (rank[ra] > rank[rb]) parent[rb] = ra
            else { parent[rb] = ra; rank[ra]++ }
            return true
        }
        for (y in 0 until h) for (x in 0 until w) grid[(y * 2 + 1) * gw + (x * 2 + 1)] = 1
        val edges = ArrayList<IntArray>()
        for (y in 0 until h) {
            for (x in 0 until w) {
                if (x + 1 < w) edges.add(intArrayOf(y * w + x, y * w + x + 1, x * 2 + 2, y * 2 + 1))
                if (y + 1 < h) edges.add(intArrayOf(y * w + x, (y + 1) * w + x, x * 2 + 1, y * 2 + 2))
            }
        }
        for (i in edges.size - 1 downTo 1) {
            val j = rng.integer(0, i)
            val tmp = edges[i]; edges[i] = edges[j]; edges[j] = tmp
        }
        for (e in edges) {
            if (union(e[0], e[1])) {
                grid[e[3] * gw + e[2]] = 1
            }
        }
    }

    private fun generateMaze(algo: String, w: Int, h: Int, rng: SeededRNG): ByteArray {
        val gw = 2 * w + 1; val gh = 2 * h + 1
        val grid = ByteArray(gw * gh)
        when (algo) {
            "backtracker" -> genBacktracker(grid, gw, w, h, rng)
            "prims" -> genPrims(grid, gw, w, h, rng)
            "kruskals" -> genKruskals(grid, gw, w, h, rng)
        }
        return grid
    }

    private fun solveMaze(grid: ByteArray, gw: Int, gh: Int) {
        val start = 1 * gw + 1
        val end = (gh - 2) * gw + (gw - 2)
        val visited = ByteArray(gw * gh)
        val parent = IntArray(gw * gh) { -1 }
        val queue = ArrayDeque<Int>()
        queue.offer(start)
        visited[start] = 1
        while (queue.isNotEmpty()) {
            val cur = queue.poll()
            if (cur == end) break
            val x = cur % gw; val y = cur / gw
            for (d in DIRS) {
                val nx = x + d[0]; val ny = y + d[1]
                if (nx in 0 until gw && ny in 0 until gh) {
                    val ni = ny * gw + nx
                    if (visited[ni].toInt() == 0 && grid[ni].toInt() == 1) {
                        visited[ni] = 1; parent[ni] = cur; queue.offer(ni)
                    }
                }
            }
        }
        var cur = end
        while (cur != -1 && cur != start) {
            grid[cur] = 2
            cur = parent[cur]
        }
        if (cur == start) grid[start] = 2
    }

    private fun markDeadEnds(grid: ByteArray, gw: Int, gh: Int) {
        for (y in 1 until gh - 1) {
            for (x in 1 until gw - 1) {
                val idx = y * gw + x
                if (grid[idx].toInt() != 1) continue
                var openN = 0
                if (grid[(y - 1) * gw + x].toInt() > 0) openN++
                if (grid[(y + 1) * gw + x].toInt() > 0) openN++
                if (grid[y * gw + x - 1].toInt() > 0) openN++
                if (grid[y * gw + x + 1].toInt() > 0) openN++
                if (openN <= 1) grid[idx] = 3
            }
        }
    }

    private fun renderToPixels(
        pixels: IntArray, grid: ByteArray, gw: Int, gh: Int,
        colors: Array<IntArray>, showSolution: Boolean, showDeadEnds: Boolean
    ) {
        val nc = colors.size
        val wallColor = colors[0]
        val pathColor = colors[min(nc - 1, 1)]
        val solColor = if (nc > 2) colors[2] else colors[nc - 1]
        val deadColor = if (nc > 3) colors[3] else colors[min(nc - 1, 1)]
        for (i in 0 until gw * gh) {
            val v = grid[i].toInt()
            val c = when {
                v == 0 -> wallColor
                v == 2 && showSolution -> solColor
                v == 3 && showDeadEnds -> deadColor
                else -> pathColor
            }
            pixels[i] = PixelArtUtil.rgb(c[0], c[1], c[2])
        }
    }

    override fun renderCanvas(
        canvas: Canvas, bitmap: Bitmap, params: Map<String, Any>, seed: Int,
        palette: Palette, quality: Quality, time: Float
    ) {
        val gridSize = PixelArtUtil.pi(params, "gridSize", 64).coerceIn(16, 128)
        val algo = PixelArtUtil.ps(params, "algorithm", "backtracker")
        val showSolution = PixelArtUtil.pb(params, "showSolution", false)
        val showDeadEnds = PixelArtUtil.pb(params, "showDeadEnds", false)
        val spf = PixelArtUtil.pi(params, "stepsPerFrame", 10).coerceAtLeast(1)

        val cellsW = (gridSize - 1) / 2
        val cellsH = cellsW
        val gw = 2 * cellsW + 1
        val gh = 2 * cellsH + 1

        val colors = PixelArtUtil.paletteRgb(palette)

        if (time == 0f) {
            val rng = SeededRNG(seed)
            val grid = generateMaze(algo, cellsW, cellsH, rng)
            if (showSolution) solveMaze(grid, gw, gh)
            if (showDeadEnds) markDeadEnds(grid, gw, gh)
            val pixels = IntArray(gw * gh)
            renderToPixels(pixels, grid, gw, gh, colors, showSolution, showDeadEnds)
            PixelArtUtil.blitNearest(canvas, bitmap, pixels, gw, gh)
            return
        }

        synchronized(Companion) {
            val key = "$seed|$gw|$gh|$algo|$showSolution|$showDeadEnds"
            if (animKey != key || stateGrid.size != gw * gh) {
                val rng = SeededRNG(seed)
                val full = generateMaze(algo, cellsW, cellsH, rng)
                if (showSolution) solveMaze(full, gw, gh)
                if (showDeadEnds) markDeadEnds(full, gw, gh)
                val carveOrder = ArrayList<Int>()
                for (i in 0 until gw * gh) if (full[i].toInt() > 0) carveOrder.add(i)
                stateFullGrid = full
                stateCarveOrder = carveOrder.toIntArray()
                stateGrid = ByteArray(gw * gh)
                stateCarveIdx = 0
                stateGw = gw; stateGh = gh
                animKey = key
            }
            val end = min(stateCarveOrder.size, stateCarveIdx + spf)
            for (i in stateCarveIdx until end) {
                val ci = stateCarveOrder[i]
                stateGrid[ci] = stateFullGrid[ci]
            }
            stateCarveIdx = end
            val pixels = IntArray(gw * gh)
            renderToPixels(pixels, stateGrid, gw, gh, colors, showSolution, showDeadEnds)
            PixelArtUtil.blitNearest(canvas, bitmap, pixels, gw, gh)
        }
    }
}
