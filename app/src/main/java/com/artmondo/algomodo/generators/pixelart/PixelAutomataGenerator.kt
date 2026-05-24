package com.artmondo.algomodo.generators.pixelart

import android.graphics.Bitmap
import android.graphics.Canvas
import com.artmondo.algomodo.core.rng.SeededRNG
import com.artmondo.algomodo.data.palettes.Palette
import com.artmondo.algomodo.generators.Generator
import com.artmondo.algomodo.generators.ParamGroup
import com.artmondo.algomodo.generators.Parameter
import com.artmondo.algomodo.generators.Quality
import kotlin.math.max
import kotlin.math.min

class PixelAutomataGenerator : Generator {
    override val id = "pixel-automata"
    override val family = "pixel-art"
    override val styleName = "Pixel Automata"
    override val definition = "Cellular automata (Game of Life, HighLife, Brian's Brain, Maze, and more) on a coarse pixel grid with glow, ghost trail, and heatmap rendering."
    override val algorithmNotes = "Runs one of 7 CA rules on a small grid. HighLife (B36/S23) produces replicators, Maze (B3/S12345) grows labyrinthine structures, Anneal (B4678/S35678) forms smooth blobs. Render styles: classic (age-tinted), glow (halo around live cells), ghost (fading trails of dead cells), heatmap (age-based heat). Perturbation injects random cells to prevent stagnation."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema = listOf(
        Parameter.SelectParam("Rule Set", "ruleSet", ParamGroup.COMPOSITION, "Cellular automaton rule", listOf("conway", "brians-brain", "seeds", "day-night", "highlife", "maze", "anneal"), "highlife"),
        Parameter.SelectParam("Render Style", "renderStyle", ParamGroup.COLOR, "Visual rendering mode", listOf("classic", "glow", "ghost", "heatmap"), "ghost"),
        Parameter.NumberParam("Grid Size", "gridSize", ParamGroup.GEOMETRY, "Pixel grid resolution", 32f, 128f, 8f, 64f),
        Parameter.NumberParam("Generations", "generations", ParamGroup.GEOMETRY, "Number of generations for static render", 1f, 500f, 1f, 100f),
        Parameter.NumberParam("Initial Density", "density", ParamGroup.COMPOSITION, "Fraction of alive cells at start", 0.1f, 0.9f, 0.05f, 0.4f),
        Parameter.BooleanParam("Wrap Edges", "wrapEdges", ParamGroup.GEOMETRY, "Toroidal wrapping", true),
        Parameter.NumberParam("Perturbation", "perturbRate", ParamGroup.FLOW_MOTION, "Random cells injected per frame to prevent stagnation", 0f, 0.05f, 0.005f, 0.005f),
        Parameter.NumberParam("Steps/Frame", "stepsPerFrame", ParamGroup.FLOW_MOTION, "Automaton steps per animation frame", 1f, 10f, 1f, 1f),
        Parameter.NumberParam("Anim Speed", "animSpeed", ParamGroup.FLOW_MOTION, "Animation speed multiplier", 0.1f, 3f, 0.1f, 0.5f),
        Parameter.NumberParam("Reactivity", "reactivity", ParamGroup.FLOW_MOTION, "Audio reactivity strength", 0f, 2f, 0.1f, 0f)
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "ruleSet" to "highlife", "renderStyle" to "ghost", "gridSize" to 64f,
        "generations" to 100f, "density" to 0.4f, "wrapEdges" to true,
        "perturbRate" to 0.005f, "stepsPerFrame" to 1f, "animSpeed" to 0.5f, "reactivity" to 0f
    )

    companion object {
        @Volatile private var animKey: String = ""
        @Volatile private var stateGrid: ByteArray = ByteArray(0)
        @Volatile private var stateNext: ByteArray = ByteArray(0)
        @Volatile private var stateAge: IntArray = IntArray(0)
        @Volatile private var stateGhost: FloatArray = FloatArray(0)
        @Volatile private var stateGen: Int = 0
        @Volatile private var stableCount: Int = 0
        @Volatile private var prevAlive: Int = 0
        @Volatile private var animRng: SeededRNG? = null
    }

    private fun countNeighbors(grid: ByteArray, w: Int, h: Int, x: Int, y: Int, wrap: Boolean): Int {
        var c = 0
        for (dy in -1..1) {
            for (dx in -1..1) {
                if (dx == 0 && dy == 0) continue
                var nx = x + dx; var ny = y + dy
                if (wrap) {
                    nx = (nx + w) % w; ny = (ny + h) % h
                } else if (nx < 0 || nx >= w || ny < 0 || ny >= h) continue
                if (grid[ny * w + nx].toInt() == 1) c++
            }
        }
        return c
    }

    private fun stepBS(cur: ByteArray, nxt: ByteArray, w: Int, h: Int, wrap: Boolean, birth: IntArray, survive: IntArray) {
        for (y in 0 until h) {
            for (x in 0 until w) {
                val n = countNeighbors(cur, w, h, x, y, wrap)
                val alive = cur[y * w + x].toInt() == 1
                val res = if (alive) (if (survive.contains(n)) 1 else 0) else (if (birth.contains(n)) 1 else 0)
                nxt[y * w + x] = res.toByte()
            }
        }
    }

    private fun stepBriansBrain(cur: ByteArray, nxt: ByteArray, w: Int, h: Int, wrap: Boolean) {
        for (y in 0 until h) {
            for (x in 0 until w) {
                val s = cur[y * w + x].toInt()
                nxt[y * w + x] = when {
                    s == 1 -> 2
                    s == 2 -> 0
                    else -> if (countNeighbors(cur, w, h, x, y, wrap) == 2) 1 else 0
                }.toByte()
            }
        }
    }

    private fun stepSeeds(cur: ByteArray, nxt: ByteArray, w: Int, h: Int, wrap: Boolean) {
        for (y in 0 until h) {
            for (x in 0 until w) {
                if (cur[y * w + x].toInt() == 1) {
                    nxt[y * w + x] = 0
                } else {
                    val n = countNeighbors(cur, w, h, x, y, wrap)
                    nxt[y * w + x] = (if (n == 2) 1 else 0).toByte()
                }
            }
        }
    }

    private fun step(rule: String, cur: ByteArray, nxt: ByteArray, w: Int, h: Int, wrap: Boolean) {
        when (rule) {
            "conway" -> stepBS(cur, nxt, w, h, wrap, intArrayOf(3), intArrayOf(2, 3))
            "highlife" -> stepBS(cur, nxt, w, h, wrap, intArrayOf(3, 6), intArrayOf(2, 3))
            "day-night" -> stepBS(cur, nxt, w, h, wrap, intArrayOf(3, 6, 7, 8), intArrayOf(3, 4, 6, 7, 8))
            "maze" -> stepBS(cur, nxt, w, h, wrap, intArrayOf(3), intArrayOf(1, 2, 3, 4, 5))
            "anneal" -> stepBS(cur, nxt, w, h, wrap, intArrayOf(4, 6, 7, 8), intArrayOf(3, 5, 6, 7, 8))
            "brians-brain" -> stepBriansBrain(cur, nxt, w, h, wrap)
            "seeds" -> stepSeeds(cur, nxt, w, h, wrap)
        }
    }

    private fun initGrid(sz: Int, seed: Int, density: Float): Quad {
        val rng = SeededRNG(seed)
        val grid = ByteArray(sz * sz)
        val next = ByteArray(sz * sz)
        val age = IntArray(sz * sz)
        val ghost = FloatArray(sz * sz)
        for (i in 0 until sz * sz) {
            if (rng.random() < density) {
                grid[i] = 1; age[i] = 1
            }
        }
        return Quad(grid, next, age, ghost)
    }

    private data class Quad(val grid: ByteArray, val next: ByteArray, val age: IntArray, val ghost: FloatArray)

    private fun stepAndSwap(g: ByteArray, n: ByteArray, a: IntArray, gh: FloatArray, rule: String, sz: Int, wrap: Boolean) {
        step(rule, g, n, sz, sz, wrap)
        for (i in 0 until sz * sz) gh[i] *= 0.85f
        for (i in 0 until sz * sz) {
            if (n[i].toInt() == 1) {
                a[i] = if (g[i].toInt() == 1) min(a[i] + 1, 65535) else 1
            } else {
                if (g[i].toInt() == 1) gh[i] = 1f
                a[i] = 0
            }
            g[i] = n[i]
        }
    }

    private fun countAlive(g: ByteArray): Int {
        var c = 0
        for (b in g) if (b.toInt() == 1) c++
        return c
    }

    private fun perturb(g: ByteArray, a: IntArray, sz: Int, rate: Float, rng: SeededRNG) {
        val count = max(1, (sz * sz * rate).toInt())
        for (i in 0 until count) {
            val idx = rng.integer(0, sz * sz - 1)
            g[idx] = 1; a[idx] = 1
        }
    }

    private fun renderGrid(
        pixels: IntArray, grid: ByteArray, age: IntArray, ghost: FloatArray?,
        sz: Int, renderStyle: String, rule: String, colors: Array<IntArray>, maxAge: Int
    ) {
        val nc = colors.size
        val bg = colors[0]
        val fg = colors[nc - 1]
        for (y in 0 until sz) {
            for (x in 0 until sz) {
                val idx = y * sz + x
                val s = grid[idx].toInt()
                var r: Int; var g: Int; var b: Int

                when (renderStyle) {
                    "glow" -> {
                        if (s == 1) {
                            r = min(255, fg[0] + 60); g = min(255, fg[1] + 60); b = min(255, fg[2] + 60)
                        } else if (rule == "brians-brain" && s == 2) {
                            val mid = colors[min(nc - 1, nc / 2)]
                            r = mid[0]; g = mid[1]; b = mid[2]
                        } else {
                            var glowIntensity = 0f
                            for (dy in -1..1) {
                                for (dx in -1..1) {
                                    if (dx == 0 && dy == 0) continue
                                    val nx = (x + dx + sz) % sz
                                    val ny = (y + dy + sz) % sz
                                    if (grid[ny * sz + nx].toInt() == 1) glowIntensity += 0.15f
                                }
                            }
                            if (ghost != null && ghost[idx] > 0.05f) {
                                glowIntensity = max(glowIntensity, ghost[idx] * 0.5f)
                            }
                            if (glowIntensity > 0f) {
                                val gi = min(1f, glowIntensity)
                                r = (bg[0] + (fg[0] - bg[0]) * gi * 0.4f).toInt()
                                g = (bg[1] + (fg[1] - bg[1]) * gi * 0.4f).toInt()
                                b = (bg[2] + (fg[2] - bg[2]) * gi * 0.4f).toInt()
                            } else {
                                r = bg[0]; g = bg[1]; b = bg[2]
                            }
                        }
                    }
                    "ghost" -> {
                        if (s == 1) {
                            r = fg[0]; g = fg[1]; b = fg[2]
                        } else if (rule == "brians-brain" && s == 2) {
                            val mid = colors[min(nc - 1, nc / 2)]
                            r = mid[0]; g = mid[1]; b = mid[2]
                        } else if (ghost != null && ghost[idx] > 0.02f) {
                            val gi = ghost[idx]
                            val ghostCI = gi * (nc - 1)
                            val i0 = ghostCI.toInt()
                            val i1 = min(nc - 1, i0 + 1)
                            val f = ghostCI - i0
                            var rr = (colors[i0][0] + (colors[i1][0] - colors[i0][0]) * f).toInt()
                            var gg = (colors[i0][1] + (colors[i1][1] - colors[i0][1]) * f).toInt()
                            var bb = (colors[i0][2] + (colors[i1][2] - colors[i0][2]) * f).toInt()
                            rr = (bg[0] + (rr - bg[0]) * gi).toInt()
                            gg = (bg[1] + (gg - bg[1]) * gi).toInt()
                            bb = (bg[2] + (bb - bg[2]) * gi).toInt()
                            r = rr; g = gg; b = bb
                        } else {
                            r = bg[0]; g = bg[1]; b = bg[2]
                        }
                    }
                    "heatmap" -> {
                        if (s == 0 && (ghost == null || ghost[idx] < 0.02f)) {
                            r = bg[0]; g = bg[1]; b = bg[2]
                        } else {
                            val t = if (s == 1) min(1f, age[idx].toFloat() / max(1, maxAge))
                                    else if (ghost != null) ghost[idx] * 0.3f else 0f
                            val ci = t * (nc - 1)
                            val i0 = ci.toInt()
                            val i1 = min(nc - 1, i0 + 1)
                            val f = ci - i0
                            r = (colors[i0][0] + (colors[i1][0] - colors[i0][0]) * f).toInt()
                            g = (colors[i0][1] + (colors[i1][1] - colors[i0][1]) * f).toInt()
                            b = (colors[i0][2] + (colors[i1][2] - colors[i0][2]) * f).toInt()
                        }
                    }
                    else -> { // classic
                        if (s == 1) {
                            val t = min(1f, age[idx].toFloat() / max(1, (maxAge * 0.5f).toInt()))
                            val ci = (1f - t) * (nc - 1)
                            val i0 = max(1, ci.toInt())
                            val i1 = min(nc - 1, i0 + 1)
                            val f = ci - ci.toInt()
                            r = (colors[i0][0] + (colors[i1][0] - colors[i0][0]) * f).toInt()
                            g = (colors[i0][1] + (colors[i1][1] - colors[i0][1]) * f).toInt()
                            b = (colors[i0][2] + (colors[i1][2] - colors[i0][2]) * f).toInt()
                        } else if (rule == "brians-brain" && s == 2) {
                            val mid = colors[min(nc - 1, nc / 2)]
                            r = mid[0]; g = mid[1]; b = mid[2]
                        } else {
                            r = bg[0]; g = bg[1]; b = bg[2]
                        }
                    }
                }
                pixels[idx] = PixelArtUtil.rgb(r, g, b)
            }
        }
    }

    override fun renderCanvas(
        canvas: Canvas, bitmap: Bitmap, params: Map<String, Any>, seed: Int,
        palette: Palette, quality: Quality, time: Float
    ) {
        val sz = PixelArtUtil.pi(params, "gridSize", 64).coerceIn(16, 128)
        val rule = PixelArtUtil.ps(params, "ruleSet", "highlife")
        val renderStyle = PixelArtUtil.ps(params, "renderStyle", "ghost")
        val rawDensity = PixelArtUtil.pf(params, "density", 0.4f)
        val density = if ((rule == "conway" || rule == "seeds" || rule == "highlife") && rawDensity > 0.5f) 1f - rawDensity else rawDensity
        val wrap = PixelArtUtil.pb(params, "wrapEdges", true)
        val speed = PixelArtUtil.pf(params, "animSpeed", 0.5f)
        val spf = (PixelArtUtil.pi(params, "stepsPerFrame", 1) * speed).toInt().coerceAtLeast(1)
        val gens = PixelArtUtil.pi(params, "generations", 100).coerceAtLeast(1)
        val perturbRate = PixelArtUtil.pf(params, "perturbRate", 0.005f)

        val colors = PixelArtUtil.paletteRgb(palette)

        if (time == 0f) {
            var (grid, next, age, ghost) = initGrid(sz, seed, density)
            val snapshot = grid.copyOf()
            val snapshotAge = age.copyOf()
            val snapshotGhost = ghost.copyOf()
            var lastAliveGen = 0
            for (i in 0 until gens) {
                if (countAlive(grid) > 0) {
                    System.arraycopy(grid, 0, snapshot, 0, grid.size)
                    System.arraycopy(age, 0, snapshotAge, 0, age.size)
                    System.arraycopy(ghost, 0, snapshotGhost, 0, ghost.size)
                    lastAliveGen = i
                }
                stepAndSwap(grid, next, age, ghost, rule, sz, wrap)
            }
            val alive = countAlive(grid)
            val renderG = if (alive > 0) grid else snapshot
            val renderA = if (alive > 0) age else snapshotAge
            val renderGh = if (alive > 0) ghost else snapshotGhost
            val renderMaxAge = if (alive > 0) gens else max(lastAliveGen, 1)
            val pixels = IntArray(sz * sz)
            renderGrid(pixels, renderG, renderA, renderGh, sz, renderStyle, rule, colors, renderMaxAge)
            PixelArtUtil.blitNearest(canvas, bitmap, pixels, sz)
            return
        }

        synchronized(Companion) {
            val key = "$seed|$sz|$density|$wrap|$rule|$renderStyle|$perturbRate"
            if (animKey != key || stateGrid.size != sz * sz) {
                val q = initGrid(sz, seed, density)
                stateGrid = q.grid; stateNext = q.next; stateAge = q.age; stateGhost = q.ghost
                stateGen = 0; stableCount = 0; prevAlive = 0
                animRng = SeededRNG(seed)
                animKey = key
            }

            for (s in 0 until spf) {
                stepAndSwap(stateGrid, stateNext, stateAge, stateGhost, rule, sz, wrap)
                stateGen++
                val alive = countAlive(stateGrid)
                if (alive == prevAlive) stableCount++ else stableCount = 0
                prevAlive = alive
                if (alive == 0) {
                    val q = initGrid(sz, seed + stateGen, density)
                    stateGrid = q.grid; stateNext = q.next; stateAge = q.age; stateGhost = q.ghost
                    stableCount = 0
                } else if (perturbRate > 0f && stableCount > 20) {
                    perturb(stateGrid, stateAge, sz, perturbRate * 3, animRng!!)
                    stableCount = 0
                } else if (perturbRate > 0f && stateGen % 30 == 0) {
                    perturb(stateGrid, stateAge, sz, perturbRate, animRng!!)
                }
            }

            val pixels = IntArray(sz * sz)
            renderGrid(pixels, stateGrid, stateAge, stateGhost, sz, renderStyle, rule, colors, max(stateGen, 50))
            PixelArtUtil.blitNearest(canvas, bitmap, pixels, sz)
        }
    }
}
