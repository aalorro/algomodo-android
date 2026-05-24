package com.artmondo.algomodo.generators.pixelart

import android.graphics.Bitmap
import android.graphics.Canvas
import com.artmondo.algomodo.core.rng.SeededRNG
import com.artmondo.algomodo.data.palettes.Palette
import com.artmondo.algomodo.generators.Generator
import com.artmondo.algomodo.generators.ParamGroup
import com.artmondo.algomodo.generators.Parameter
import com.artmondo.algomodo.generators.Quality
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

class PixelDiffusionGenerator : Generator {
    override val id = "pixel-diffusion"
    override val family = "pixel-art"
    override val styleName = "Pixel Diffusion"
    override val definition = "Gray-Scott reaction-diffusion on a low-res grid with multiple seeding patterns, evolving parameters, and rich color modes."
    override val algorithmNotes = "Simulates the Gray-Scott model on a small grid. Multiple seed patterns (patches, rings, scatter, center) create varied initial conditions. Evolving mode slowly oscillates feed/kill rates so patterns continuously morph. Periodic re-seeding drops new chemical spots. Three color modes: quantized bands, smooth gradient (using both U and V), and glow (edge-detected with activity highlights)."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema = listOf(
        Parameter.SelectParam("Seed Pattern", "seedPattern", ParamGroup.COMPOSITION, "Initial chemical seeding pattern", listOf("patches", "ring", "scatter", "center"), "patches"),
        Parameter.NumberParam("Feed Rate", "feedRate", ParamGroup.COMPOSITION, "Feed rate (f) for Gray-Scott model", 0.01f, 0.08f, 0.002f, 0.037f),
        Parameter.NumberParam("Kill Rate", "killRate", ParamGroup.COMPOSITION, "Kill rate (k) for Gray-Scott model", 0.04f, 0.07f, 0.002f, 0.06f),
        Parameter.BooleanParam("Evolving", "evolving", ParamGroup.COMPOSITION, "Slowly oscillate feed/kill rates so patterns keep morphing", true),
        Parameter.NumberParam("Reseed Interval", "reseedInterval", ParamGroup.FLOW_MOTION, "Drop new chemical seeds every N steps (0 = never)", 0f, 2000f, 100f, 500f),
        Parameter.NumberParam("Grid Size", "gridSize", ParamGroup.GEOMETRY, "Pixel grid resolution", 32f, 128f, 8f, 64f),
        Parameter.NumberParam("Iterations", "iterations", ParamGroup.GEOMETRY, "Number of simulation steps for static render", 100f, 5000f, 100f, 2000f),
        Parameter.NumberParam("Steps/Frame", "stepsPerFrame", ParamGroup.FLOW_MOTION, "Simulation steps per animation frame", 1f, 50f, 1f, 10f),
        Parameter.SelectParam("Color Mode", "colorMode", ParamGroup.COLOR, "How chemical concentrations map to colors", listOf("bands", "gradient", "glow"), "glow"),
        Parameter.NumberParam("Color Bands", "colorBands", ParamGroup.COLOR, "Number of discrete color quantization bands", 4f, 32f, 1f, 8f),
        Parameter.NumberParam("Anim Speed", "animSpeed", ParamGroup.FLOW_MOTION, "Animation speed multiplier", 0.1f, 3f, 0.1f, 1f),
        Parameter.NumberParam("Reactivity", "reactivity", ParamGroup.FLOW_MOTION, "Audio reactivity strength", 0f, 2f, 0.1f, 0f)
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "seedPattern" to "patches", "feedRate" to 0.037f, "killRate" to 0.06f, "evolving" to true,
        "reseedInterval" to 500f, "gridSize" to 64f, "iterations" to 2000f, "stepsPerFrame" to 10f,
        "colorMode" to "glow", "colorBands" to 8f, "animSpeed" to 0.5f, "reactivity" to 0f
    )

    companion object {
        @Volatile private var animKey: String = ""
        @Volatile private var uC: DoubleArray = DoubleArray(0)
        @Volatile private var vC: DoubleArray = DoubleArray(0)
        @Volatile private var prevVC: DoubleArray = DoubleArray(0)
        @Volatile private var iterC: Int = 0
        @Volatile private var lastDropIter: Int = 0
        @Volatile private var rngC: SeededRNG? = null
    }

    private fun viableK(f: Float): Float {
        val fc = f.coerceIn(0.01f, 0.08f)
        return if (fc <= 0.05f) 0.04f + (fc - 0.01f) * 0.625f
               else 0.065f - (fc - 0.05f) * 0.2f
    }

    private fun initChemicals(sz: Int, seed: Int, pattern: String): Pair<DoubleArray, DoubleArray> {
        val u = DoubleArray(sz * sz) { 1.0 }
        val v = DoubleArray(sz * sz)
        val rng = SeededRNG(seed)

        when (pattern) {
            "patches" -> {
                val patches = 5 + rng.integer(0, 6)
                for (p in 0 until patches) {
                    val cx = rng.integer(sz / 8, (7 * sz) / 8)
                    val cy = rng.integer(sz / 8, (7 * sz) / 8)
                    val r = rng.integer(max(3, sz / 12), max(5, sz / 5))
                    for (dy in -r..r) {
                        for (dx in -r..r) {
                            if (dx * dx + dy * dy > r * r) continue
                            val x = (cx + dx + sz) % sz
                            val y = (cy + dy + sz) % sz
                            val idx = y * sz + x
                            u[idx] = 0.5 + rng.random() * 0.04
                            v[idx] = 0.25 + rng.random() * 0.04
                        }
                    }
                }
            }
            "ring" -> {
                val ccx = sz / 2.0; val ccy = sz / 2.0
                val ringR = sz * (0.2 + rng.random() * 0.15)
                val ringW = max(2.0, sz * 0.05)
                for (y in 0 until sz) {
                    for (x in 0 until sz) {
                        val dx = x - ccx; val dy = y - ccy
                        val dist = sqrt(dx * dx + dy * dy)
                        if (abs(dist - ringR) < ringW) {
                            val idx = y * sz + x
                            u[idx] = 0.5 + rng.random() * 0.02
                            v[idx] = 0.25 + rng.random() * 0.02
                        }
                    }
                }
                val ox = ccx + rng.integer(-sz / 6, sz / 6)
                val oy = ccy + rng.integer(-sz / 6, sz / 6)
                val r2 = ringR * 0.4
                for (y in 0 until sz) {
                    for (x in 0 until sz) {
                        val dx = x - ox; val dy = y - oy
                        val dist = sqrt(dx * dx + dy * dy)
                        if (abs(dist - r2) < ringW) {
                            val idx = y * sz + x
                            u[idx] = 0.5 + rng.random() * 0.02
                            v[idx] = 0.25 + rng.random() * 0.02
                        }
                    }
                }
            }
            "scatter" -> {
                val numDots = 15 + rng.integer(0, 25)
                for (i in 0 until numDots) {
                    val cx = rng.integer(0, sz - 1)
                    val cy = rng.integer(0, sz - 1)
                    val r = rng.integer(1, max(2, sz / 16))
                    for (dy in -r..r) {
                        for (dx in -r..r) {
                            if (dx * dx + dy * dy > r * r) continue
                            val x = (cx + dx + sz) % sz
                            val y = (cy + dy + sz) % sz
                            val idx = y * sz + x
                            u[idx] = 0.5 + rng.random() * 0.02
                            v[idx] = 0.25 + rng.random() * 0.02
                        }
                    }
                }
            }
            "center" -> {
                val r = max(3, (sz * 0.12).toInt())
                val ccx = sz / 2; val ccy = sz / 2
                for (dy in -r..r) {
                    for (dx in -r..r) {
                        if (dx * dx + dy * dy > r * r) continue
                        val x = (ccx + dx + sz) % sz
                        val y = (ccy + dy + sz) % sz
                        val idx = y * sz + x
                        u[idx] = 0.5 + rng.random() * 0.02
                        v[idx] = 0.25 + rng.random() * 0.02
                    }
                }
            }
        }
        return u to v
    }

    private fun dropSeed(u: DoubleArray, v: DoubleArray, sz: Int, cx: Int, cy: Int, r: Int) {
        for (dy in -r..r) {
            for (dx in -r..r) {
                if (dx * dx + dy * dy > r * r) continue
                val x = (cx + dx + sz) % sz
                val y = (cy + dy + sz) % sz
                val idx = y * sz + x
                u[idx] = 0.5; v[idx] = 0.25
            }
        }
    }

    private fun stepGrayScott(u: DoubleArray, v: DoubleArray, sz: Int, f: Double, k: Double, du: Double, dv: Double) {
        val uN = DoubleArray(sz * sz)
        val vN = DoubleArray(sz * sz)
        for (y in 0 until sz) {
            val yUp = ((y - 1 + sz) % sz) * sz
            val yDn = ((y + 1) % sz) * sz
            val yC = y * sz
            for (x in 0 until sz) {
                val xL = (x - 1 + sz) % sz
                val xR = (x + 1) % sz
                val idx = yC + x
                val uVal = u[idx]
                val vVal = v[idx]
                val uvv = uVal * vVal * vVal
                val lapU = u[yUp + x] + u[yDn + x] + u[yC + xL] + u[yC + xR] - 4 * uVal
                val lapV = v[yUp + x] + v[yDn + x] + v[yC + xL] + v[yC + xR] - 4 * vVal
                uN[idx] = uVal + du * lapU - uvv + f * (1 - uVal)
                vN[idx] = vVal + dv * lapV + uvv - (f + k) * vVal
            }
        }
        System.arraycopy(uN, 0, u, 0, u.size)
        System.arraycopy(vN, 0, v, 0, v.size)
    }

    private fun renderDiffusion(
        pixels: IntArray, u: DoubleArray, v: DoubleArray, prevV: DoubleArray?,
        sz: Int, colors: Array<IntArray>, bands: Int, colorMode: String
    ) {
        val nc = colors.size
        for (i in 0 until sz * sz) {
            val vVal = v[i].coerceIn(0.0, 1.0).toFloat()
            when (colorMode) {
                "gradient" -> {
                    val uVal = u[i].coerceIn(0.0, 1.0).toFloat()
                    val t = vVal * 0.7f + (1f - uVal) * 0.3f
                    val ci = t * (nc - 1)
                    val i0 = ci.toInt()
                    val i1 = min(nc - 1, i0 + 1)
                    val frac = ci - i0
                    val r = (colors[i0][0] + (colors[i1][0] - colors[i0][0]) * frac).toInt()
                    val g = (colors[i0][1] + (colors[i1][1] - colors[i0][1]) * frac).toInt()
                    val b = (colors[i0][2] + (colors[i1][2] - colors[i0][2]) * frac).toInt()
                    pixels[i] = PixelArtUtil.rgb(r, g, b)
                }
                "glow" -> {
                    val band = min(bands - 1, (vVal * bands).toInt())
                    val t = band.toFloat() / max(1, bands - 1)
                    val ci = t * (nc - 1)
                    val i0 = ci.toInt()
                    val i1 = min(nc - 1, i0 + 1)
                    val frac = ci - i0
                    var r = (colors[i0][0] + (colors[i1][0] - colors[i0][0]) * frac).toInt()
                    var g = (colors[i0][1] + (colors[i1][1] - colors[i0][1]) * frac).toInt()
                    var b = (colors[i0][2] + (colors[i1][2] - colors[i0][2]) * frac).toInt()
                    val x = i % sz; val y = i / sz
                    if (x > 0 && x < sz - 1 && y > 0 && y < sz - 1) {
                        val gx = v[y * sz + x + 1] - v[y * sz + x - 1]
                        val gy = v[(y + 1) * sz + x] - v[(y - 1) * sz + x]
                        val edge = min(1.0, sqrt(gx * gx + gy * gy) * 8).toFloat()
                        r = min(255, r + (edge * 120).toInt())
                        g = min(255, g + (edge * 100).toInt())
                        b = min(255, b + (edge * 60).toInt())
                    }
                    if (prevV != null) {
                        val delta = abs(v[i] - prevV[i])
                        val activity = min(1.0, delta * 40).toFloat()
                        r = min(255, r + (activity * 50).toInt())
                        g = min(255, g + (activity * 30).toInt())
                        b = min(255, b + (activity * 70).toInt())
                    }
                    pixels[i] = PixelArtUtil.rgb(r, g, b)
                }
                else -> { // bands
                    val band = min(bands - 1, (vVal * bands).toInt())
                    val t = band.toFloat() / max(1, bands - 1)
                    val ci = t * (nc - 1)
                    val i0 = ci.toInt()
                    val i1 = min(nc - 1, i0 + 1)
                    val frac = ci - i0
                    val r = (colors[i0][0] + (colors[i1][0] - colors[i0][0]) * frac).toInt()
                    val g = (colors[i0][1] + (colors[i1][1] - colors[i0][1]) * frac).toInt()
                    val b = (colors[i0][2] + (colors[i1][2] - colors[i0][2]) * frac).toInt()
                    pixels[i] = PixelArtUtil.rgb(r, g, b)
                }
            }
        }
    }

    override fun renderCanvas(
        canvas: Canvas, bitmap: Bitmap, params: Map<String, Any>, seed: Int,
        palette: Palette, quality: Quality, time: Float
    ) {
        val sz = PixelArtUtil.pi(params, "gridSize", 64).coerceIn(16, 128)
        val fUser = PixelArtUtil.pf(params, "feedRate", 0.037f)
        val kUser = PixelArtUtil.pf(params, "killRate", 0.06f)
        val kCenter = viableK(fUser)
        val fBase = fUser.toDouble()
        val kBase = (kCenter + (kUser - kCenter) * 0.4f).toDouble()
        val bands = PixelArtUtil.pi(params, "colorBands", 8).coerceIn(2, 32)
        val iters = PixelArtUtil.pi(params, "iterations", 2000).coerceAtLeast(1)
        val speed = PixelArtUtil.pf(params, "animSpeed", 0.5f)
        val spf = (PixelArtUtil.pi(params, "stepsPerFrame", 10) * speed).toInt().coerceAtLeast(1)
        val du = 0.2097
        val dv = 0.105
        val pattern = PixelArtUtil.ps(params, "seedPattern", "patches")
        val colorMode = PixelArtUtil.ps(params, "colorMode", "glow")
        val evolving = PixelArtUtil.pb(params, "evolving", true)
        val reseedInterval = PixelArtUtil.pi(params, "reseedInterval", 500)

        val colors = PixelArtUtil.paletteRgb(palette)

        if (time == 0f) {
            val (u, v) = initChemicals(sz, seed, pattern)
            for (i in 0 until iters) {
                val ef = if (evolving) fBase + sin(i * 0.003 + 1.57) * 0.005 else fBase
                val ek = if (evolving) kBase + sin(i * 0.002) * 0.003 else kBase
                stepGrayScott(u, v, sz, ef, ek, du, dv)
            }
            val pixels = IntArray(sz * sz)
            renderDiffusion(pixels, u, v, null, sz, colors, bands, colorMode)
            PixelArtUtil.blitNearest(canvas, bitmap, pixels, sz)
            return
        }

        synchronized(Companion) {
            val key = "$seed|$sz|$fBase|$kBase|$pattern"
            if (animKey != key || uC.size != sz * sz) {
                val (u, v) = initChemicals(sz, seed, pattern)
                uC = u; vC = v
                prevVC = DoubleArray(sz * sz)
                iterC = 0; lastDropIter = 0
                rngC = SeededRNG(seed + 999)
                animKey = key
            }
            System.arraycopy(vC, 0, prevVC, 0, vC.size)
            for (s in 0 until spf) {
                val ef = if (evolving) fBase + sin(iterC * 0.002 + 1.57) * 0.006 else fBase
                val ek = if (evolving) kBase + sin(iterC * 0.0015) * 0.004 else kBase
                stepGrayScott(uC, vC, sz, ef, ek, du, dv)
                iterC++
                if (reseedInterval > 0 && iterC - lastDropIter >= reseedInterval) {
                    val cx = rngC!!.integer(sz / 6, (5 * sz) / 6)
                    val cy = rngC!!.integer(sz / 6, (5 * sz) / 6)
                    val r = rngC!!.integer(2, max(3, sz / 12))
                    dropSeed(uC, vC, sz, cx, cy, r)
                    lastDropIter = iterC
                }
            }
            val pixels = IntArray(sz * sz)
            renderDiffusion(pixels, uC, vC, prevVC, sz, colors, bands, colorMode)
            PixelArtUtil.blitNearest(canvas, bitmap, pixels, sz)
        }
    }
}
