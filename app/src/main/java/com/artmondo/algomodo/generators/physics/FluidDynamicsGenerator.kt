package com.artmondo.algomodo.generators.physics

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import com.artmondo.algomodo.audio.AudioAnalysis
import com.artmondo.algomodo.core.rng.SeededRNG
import com.artmondo.algomodo.core.rng.SimplexNoise
import com.artmondo.algomodo.data.palettes.Palette
import com.artmondo.algomodo.generators.Generator
import com.artmondo.algomodo.generators.ParamGroup
import com.artmondo.algomodo.generators.Parameter
import com.artmondo.algomodo.generators.Quality
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

class FluidDynamicsGenerator : Generator {

    override val id = "physics-fluid-dynamics"
    override val family = "physics"
    override val styleName = "Fluid Dynamics"
    override val definition =
        "Real-time 2D fluid simulation based on Jos Stam's stable Navier-Stokes solver \u2014 " +
        "velocity and dye fields evolve through advection, diffusion, and pressure projection, " +
        "with automated dye and force injection patterns"
    override val algorithmNotes =
        "Jos Stam's \"Stable Fluids\" (1999): (N+2)\u00b2 grid with 1-cell boundary padding. " +
        "Each frame: (1) inject forces/dye via selected pattern (swirl vortices, edge jets, " +
        "Perlin noise field, or radial pulses), (2) diffuse velocity via Gauss-Seidel " +
        "relaxation, (3) project to enforce incompressibility, (4) self-advect velocity via " +
        "semi-Lagrangian back-trace with bilinear interpolation, (5) project again, (6) diffuse " +
        "and advect density. Rendering: full-resolution pixel writes with pre-computed bilinear " +
        "column/row weights for zero function-call overhead. Contrast-boosted via pow(0.4) LUT. " +
        "Vorticity/velocity pre-computed on grid, then bilinear-sampled at canvas resolution."

    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema = listOf(
        Parameter.SelectParam(
            name = "Render Style", key = "style", group = ParamGroup.COMPOSITION,
            help = "density: dye field | velocity: speed magnitude | vorticity: curl/rotation | rainbow: velocity angle as hue",
            options = listOf("density", "velocity", "vorticity", "rainbow"),
            default = "density"
        ),
        Parameter.SelectParam(
            name = "Injection", key = "injectionPattern", group = ParamGroup.COMPOSITION,
            help = "swirl: rotating vortices | jets: edge streams | noise: Perlin force field | pulse: periodic radial bursts",
            options = listOf("swirl", "jets", "noise", "pulse"),
            default = "swirl"
        ),
        Parameter.NumberParam(
            name = "Viscosity", key = "viscosity", group = ParamGroup.FLOW_MOTION,
            help = "Velocity diffusion \u2014 higher = thicker/slower fluid",
            min = 0f, max = 0.001f, step = 0.00005f, default = 0.00002f
        ),
        Parameter.NumberParam(
            name = "Diffusion", key = "diffusion", group = ParamGroup.FLOW_MOTION,
            help = "Dye diffusion rate \u2014 lower = sharper detail",
            min = 0f, max = 0.001f, step = 0.00005f, default = 0.00002f
        ),
        Parameter.NumberParam(
            name = "Force", key = "forceStrength", group = ParamGroup.FLOW_MOTION,
            help = "Injection force magnitude",
            min = 0.5f, max = 10f, step = 0.5f, default = 6f
        ),
        Parameter.NumberParam(
            name = "Dye Rate", key = "injectionRate", group = ParamGroup.FLOW_MOTION,
            help = "Amount of dye injected per frame",
            min = 0.5f, max = 5f, step = 0.5f, default = 2.5f
        ),
        Parameter.NumberParam(
            name = "Solver Iters", key = "solverIterations", group = ParamGroup.FLOW_MOTION,
            help = "Gauss-Seidel iterations \u2014 higher = more accurate but slower",
            min = 1f, max = 20f, step = 1f, default = 2f
        ),
        Parameter.NumberParam(
            name = "Dye Spread", key = "dyeSpread", group = ParamGroup.GEOMETRY,
            help = "Injection source radius in grid cells",
            min = 1f, max = 8f, step = 1f, default = 3f
        ),
        Parameter.NumberParam(
            name = "Steps / Frame", key = "stepsPerFrame", group = ParamGroup.FLOW_MOTION,
            help = null,
            min = 1f, max = 4f, step = 1f, default = 1f
        ),
        Parameter.SelectParam(
            name = "Color Mode", key = "colorMode", group = ParamGroup.COLOR,
            help = "palette: map to current palette | grayscale: white on black | heatmap: black\u2192red\u2192yellow\u2192white",
            options = listOf("palette", "grayscale", "heatmap"),
            default = "palette"
        ),
        Parameter.NumberParam(
            name = "Audio Reactivity", key = "reactivity", group = ParamGroup.FLOW_MOTION,
            help = null,
            min = 0f, max = 2f, step = 0.1f, default = 0f
        )
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "style" to "density",
        "injectionPattern" to "swirl",
        "viscosity" to 0.00002f,
        "diffusion" to 0.00002f,
        "forceStrength" to 6f,
        "injectionRate" to 2.5f,
        "solverIterations" to 2f,
        "dyeSpread" to 3f,
        "stepsPerFrame" to 1f,
        "colorMode" to "palette",
        "reactivity" to 0f
    )

    // ── Param helpers ───────────────────────────────────────────────────────
    private fun pf(p: Map<String, Any>, k: String, default: Float): Float =
        (p[k] as? Number)?.toFloat() ?: default
    private fun pi(p: Map<String, Any>, k: String, default: Int): Int =
        (p[k] as? Number)?.toInt() ?: default
    private fun ps(p: Map<String, Any>, k: String, default: String): String =
        (p[k] as? String) ?: default

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

        val N = when (quality) {
            Quality.DRAFT -> 128
            Quality.ULTRA -> 300
            else -> 220
        }

        val visc = pf(params, "viscosity", 0.00002f)
        val diff = pf(params, "diffusion", 0.00002f)
        var strength = pf(params, "forceStrength", 6f)
        var dyeRate = pf(params, "injectionRate", 2.5f)
        val solverIter = max(1, pi(params, "solverIterations", 2))
        val dyeSpread = pf(params, "dyeSpread", 3f)
        val spf = max(1, pi(params, "stepsPerFrame", 1))
        val style = ps(params, "style", "density")
        val colorMode = ps(params, "colorMode", "palette")
        val pattern = ps(params, "injectionPattern", "swirl")
        val dt = 0.1f

        // ── Audio reactivity ────────────────────────────────────────────────
        val rx = pf(params, "reactivity", 0f)
        val audio = params["_audioAnalysis"] as? AudioAnalysis
        val audioBass = (audio?.getBass(time) ?: 0f) * rx
        val audioMid = (audio?.getMid(time) ?: 0f) * rx
        val audioHigh = (audio?.getHigh(time) ?: 0f) * rx
        val audioEnergy = (audioBass + audioMid + audioHigh) / 3f
        strength += audioBass * 3f
        dyeRate += audioMid * 2f

        // ── Palette LUT ─────────────────────────────────────────────────────
        val colorInts = palette.colorInts()
        val palR = IntArray(colorInts.size) { Color.red(colorInts[it]) }
        val palG = IntArray(colorInts.size) { Color.green(colorInts[it]) }
        val palB = IntArray(colorInts.size) { Color.blue(colorInts[it]) }

        // Build / fetch caches under lock to avoid concurrent races between
        // the live canvas and export threads (both call this singleton).
        val fs: FluidState
        val px: IntArray
        val sc: FloatArray
        val cI0: IntArray
        val cS0: FloatArray
        val cS1: FloatArray
        val rJ0: IntArray
        val rT0: FloatArray
        val rT1: FloatArray
        val lut: IntArray  // 256-entry palette LUT (ARGB)
        val heatLut: IntArray  // 256-entry heatmap LUT (ARGB)
        val cLut: IntArray  // contrast LUT

        synchronized(Companion) {
            // State cache
            val stateKey = "$seed|$N"
            var cur = _fs
            if (cur == null || cur.key != stateKey) {
                cur = initFluid(N, seed)
                cur.key = stateKey
                _fs = cur
            }
            fs = cur

            // Render buffers
            ensureRenderBuf(w, h, N)
            px = _px!!
            sc = _scalar!!
            cI0 = _colI0!!
            cS0 = _colS0!!
            cS1 = _colS1!!
            rJ0 = _rowJ0!!
            rT0 = _rowT0!!
            rT1 = _rowT1!!

            // Palette LUT cache
            val palKey = colorInts.joinToString(",") + "|" + colorMode
            if (_lutKey != palKey) {
                buildLUT(palR, palG, palB)
                _lutKey = palKey
            }
            lut = _lut!!
            heatLut = _heatLut
            cLut = _cLut
        }

        // ── Warmup (only when time <= 0 and step == 0) ────────────────────
        synchronized(Companion) {
            if (time <= 0f && fs.step == 0) {
                var f = 0
                while (f < 80) {
                    injectForPattern(fs, pattern, strength, dyeSpread, dyeRate, f * 0.05f)
                    velStep(fs, visc, dt, solverIter)
                    densStep(fs, diff, dt, solverIter)
                    fs.step++
                    f++
                }
            }

            // ── Animation steps ────────────────────────────────────────────
            if (time > 0f) {
                for (s in 0 until spf) {
                    injectForPattern(fs, pattern, strength, dyeSpread, dyeRate, time + s * dt)
                    if (audioEnergy > 0.3f) {
                        val n2 = fs.N + 2
                        val ei = ((0.2f + fs.rng.random() * 0.6f) * fs.N).toInt()
                        val ej = ((0.2f + fs.rng.random() * 0.6f) * fs.N).toInt()
                        val eAngle = fs.rng.random() * (PI * 2.0).toFloat()
                        val idx = ej * n2 + ei
                        fs.vx[idx] += cos(eAngle) * audioEnergy * 8f
                        fs.vy[idx] += sin(eAngle) * audioEnergy * 8f
                        fs.dens[idx] += audioEnergy * 3f
                    }
                    velStep(fs, visc, dt, solverIter)
                    densStep(fs, diff, dt, solverIter)
                    fs.step++
                }
            }
        }

        // ── Render to small bitmap at canvas resolution (w, h) ──────────────
        val dens = fs.dens
        val vx = fs.vx
        val vy = fs.vy
        val n2 = fs.N + 2

        val useLut: IntArray? = when (colorMode) {
            "heatmap" -> heatLut
            "palette" -> lut
            else -> null
        }

        when (style) {
            "density" -> {
                var maxD = 0f
                for (j in 1..fs.N) {
                    val row = j * n2
                    for (i in 1..fs.N) {
                        val v = dens[row + i]
                        if (v > maxD) maxD = v
                    }
                }
                if (maxD < 0.01f) maxD = 1f
                renderScalar(px, dens, 1f / maxD, w, h, n2, cI0, cS0, cS1, rJ0, rT0, rT1, cLut, useLut)
            }

            "velocity" -> {
                var maxMag = 0f
                for (j in 1..fs.N) {
                    val row = j * n2
                    for (i in 1..fs.N) {
                        val idx = row + i
                        val mag = sqrt(vx[idx] * vx[idx] + vy[idx] * vy[idx])
                        sc[idx] = mag
                        if (mag > maxMag) maxMag = mag
                    }
                }
                if (maxMag < 0.001f) maxMag = 1f
                renderScalar(px, sc, 1f / maxMag, w, h, n2, cI0, cS0, cS1, rJ0, rT0, rT1, cLut, useLut)
            }

            "vorticity" -> {
                var maxCurl = 0f
                for (j in 1..fs.N) {
                    val row = j * n2
                    for (i in 1..fs.N) {
                        val idx = row + i
                        val curl = abs(
                            (vy[idx + 1] - vy[idx - 1]) - (vx[idx + n2] - vx[idx - n2])
                        ) * 0.5f
                        sc[idx] = curl
                        if (curl > maxCurl) maxCurl = curl
                    }
                }
                if (maxCurl < 0.001f) maxCurl = 1f
                renderScalar(px, sc, 1f / maxCurl, w, h, n2, cI0, cS0, cS1, rJ0, rT0, rT1, cLut, useLut)
            }

            else -> {
                // Rainbow
                var maxSpdSq = 0f
                for (j in 1..fs.N) {
                    val row = j * n2
                    for (i in 1..fs.N) {
                        val idx = row + i
                        val spd = vx[idx] * vx[idx] + vy[idx] * vy[idx]
                        if (spd > maxSpdSq) maxSpdSq = spd
                    }
                }
                var maxSpd = sqrt(maxSpdSq)
                if (maxSpd < 0.001f) maxSpd = 1f
                val invSpd = 1f / maxSpd

                if (colorMode == "grayscale") {
                    for (j in 1..fs.N) {
                        val row = j * n2
                        for (i in 1..fs.N) {
                            val idx = row + i
                            sc[idx] = sqrt(vx[idx] * vx[idx] + vy[idx] * vy[idx])
                        }
                    }
                    renderScalar(px, sc, invSpd, w, h, n2, cI0, cS0, cS1, rJ0, rT0, rT1, cLut, null)
                } else {
                    val angleLut = if (colorMode == "heatmap") heatLut else lut
                    val twoPi = (PI * 2.0).toFloat()
                    val piF = PI.toFloat()
                    for (py in 0 until h) {
                        val j0 = rJ0[py]; val t0 = rT0[py]; val t1 = rT1[py]
                        val r0 = j0 * n2; val r1 = (j0 + 1) * n2
                        val rowOff = py * w
                        for (pxC in 0 until w) {
                            val i0 = cI0[pxC]; val s0 = cS0[pxC]; val s1 = cS1[pxC]
                            val u = t0 * (s0 * vx[r0 + i0] + s1 * vx[r0 + i0 + 1]) +
                                    t1 * (s0 * vx[r1 + i0] + s1 * vx[r1 + i0 + 1])
                            val v = t0 * (s0 * vy[r0 + i0] + s1 * vy[r0 + i0 + 1]) +
                                    t1 * (s0 * vy[r1 + i0] + s1 * vy[r1 + i0 + 1])
                            val spd = sqrt(u * u + v * v)
                            val normSpd = min(1f, spd * invSpd)
                            val rawB = (normSpd * 255f + 0.5f).toInt().coerceIn(0, 255)
                            val brightness = cLut[rawB] / 255f
                            if (brightness < 0.01f) {
                                px[rowOff + pxC] = 0xFF000000.toInt()
                                continue
                            }
                            val angle = atan2(v, u) + piF
                            var ai = (angle / twoPi * 255f + 0.5f).toInt()
                            if (ai > 255) ai = 255
                            if (ai < 0) ai = 0
                            val color = angleLut[ai]
                            val cr = ((color shr 16) and 0xFF)
                            val cg = ((color shr 8) and 0xFF)
                            val cb = (color and 0xFF)
                            val nr = (cr * brightness).toInt().coerceIn(0, 255)
                            val ng = (cg * brightness).toInt().coerceIn(0, 255)
                            val nb = (cb * brightness).toInt().coerceIn(0, 255)
                            px[rowOff + pxC] = Color.argb(255, nr, ng, nb)
                        }
                    }
                }
            }
        }

        // ── Blit to canvas ──────────────────────────────────────────────────
        val small = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        small.setPixels(px, 0, w, 0, 0, w, h)
        val srcRect = Rect(0, 0, w, h)
        val dstRect = Rect(0, 0, bitmap.width, bitmap.height)
        val filterPaint = Paint().apply { isFilterBitmap = true; isAntiAlias = false }
        canvas.drawBitmap(small, srcRect, dstRect, filterPaint)
        small.recycle()
    }

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float {
        val iter = pf(params, "solverIterations", 2f)
        return (220f * 220f * iter * 0.003f / 1000f).coerceIn(0.1f, 1f)
    }

    // ── Inline bilinear scalar renderer ─────────────────────────────────────
    private fun renderScalar(
        px: IntArray, arr: FloatArray, invMax: Float,
        w: Int, h: Int, n2: Int,
        cI0: IntArray, cS0: FloatArray, cS1: FloatArray,
        rJ0: IntArray, rT0: FloatArray, rT1: FloatArray,
        cLut: IntArray, lut: IntArray?
    ) {
        for (py in 0 until h) {
            val j0 = rJ0[py]; val t0 = rT0[py]; val t1 = rT1[py]
            val r0 = j0 * n2; val r1 = (j0 + 1) * n2
            val rowOff = py * w
            for (pxC in 0 until w) {
                val i0 = cI0[pxC]; val s0 = cS0[pxC]; val s1 = cS1[pxC]
                var v = (t0 * (s0 * arr[r0 + i0] + s1 * arr[r0 + i0 + 1]) +
                         t1 * (s0 * arr[r1 + i0] + s1 * arr[r1 + i0 + 1])) * invMax
                if (v < 0f) v = 0f else if (v > 1f) v = 1f
                val ci = cLut[(v * 255f + 0.5f).toInt().coerceIn(0, 255)]
                px[rowOff + pxC] = if (lut != null) {
                    lut[ci]
                } else {
                    Color.argb(255, ci, ci, ci)
                }
            }
        }
    }

    // ── Fluid solver ────────────────────────────────────────────────────────
    private class FluidState(
        var key: String,
        val N: Int,
        val size: Int,
        val vx: FloatArray, val vy: FloatArray,
        val vx0: FloatArray, val vy0: FloatArray,
        val dens: FloatArray,
        val dens0: FloatArray,
        val div: FloatArray,
        val p: FloatArray,
        var step: Int,
        val rng: SeededRNG,
        val noise: SimplexNoise,
        val srcX: DoubleArray,
        val srcY: DoubleArray,
        val srcCount: Int
    )

    private fun initFluid(N: Int, seed: Int): FluidState {
        val size = (N + 2) * (N + 2)
        val rng = SeededRNG(seed + 31)
        val srcCount = 3 + (seed % 3).let { if (it < 0) it + 3 else it }
        val srcX = DoubleArray(srcCount)
        val srcY = DoubleArray(srcCount)
        for (k in 0 until srcCount) {
            srcX[k] = 0.2 + rng.randomDouble() * 0.6
            srcY[k] = 0.2 + rng.randomDouble() * 0.6
        }
        return FluidState(
            key = "", N = N, size = size,
            vx = FloatArray(size), vy = FloatArray(size),
            vx0 = FloatArray(size), vy0 = FloatArray(size),
            dens = FloatArray(size),
            dens0 = FloatArray(size),
            div = FloatArray(size),
            p = FloatArray(size),
            step = 0,
            rng = SeededRNG(seed),
            noise = SimplexNoise(seed + 7),
            srcX = srcX, srcY = srcY, srcCount = srcCount
        )
    }

    private fun setBnd(b: Int, x: FloatArray, N: Int) {
        val n2 = N + 2
        val neg1 = if (b == 1) -1f else 1f
        val neg2 = if (b == 2) -1f else 1f
        for (i in 1..N) {
            x[n2 * i] = neg1 * x[n2 * i + 1]
            x[n2 * i + N + 1] = neg1 * x[n2 * i + N]
            x[i] = neg2 * x[n2 + i]
            x[(N + 1) * n2 + i] = neg2 * x[N * n2 + i]
        }
        x[0] = 0.5f * (x[1] + x[n2])
        x[N + 1] = 0.5f * (x[N] + x[n2 + N + 1])
        x[(N + 1) * n2] = 0.5f * (x[(N + 1) * n2 + 1] + x[N * n2])
        x[(N + 1) * n2 + N + 1] = 0.5f * (x[(N + 1) * n2 + N] + x[N * n2 + N + 1])
    }

    private fun diffuse(
        b: Int, x: FloatArray, x0: FloatArray,
        diff: Float, dt: Float, N: Int, iter: Int
    ) {
        val a = dt * diff * N * N
        val div = 1f / (1f + 4f * a)
        val n2 = N + 2
        for (k in 0 until iter) {
            for (j in 1..N) {
                val row = j * n2
                for (i in 1..N) {
                    val idx = row + i
                    x[idx] = (x0[idx] + a * (
                        x[idx - 1] + x[idx + 1] +
                        x[idx - n2] + x[idx + n2]
                    )) * div
                }
            }
            setBnd(b, x, N)
        }
    }

    private fun advect(
        b: Int, d: FloatArray, d0: FloatArray,
        u: FloatArray, v: FloatArray,
        dt: Float, N: Int
    ) {
        val dt0 = dt * N
        val n2 = N + 2
        val nHalf = N + 0.5f
        for (j in 1..N) {
            val row = j * n2
            for (i in 1..N) {
                val idx = row + i
                var sx = i - dt0 * u[idx]
                var sy = j - dt0 * v[idx]
                if (sx < 0.5f) sx = 0.5f else if (sx > nHalf) sx = nHalf
                if (sy < 0.5f) sy = 0.5f else if (sy > nHalf) sy = nHalf
                val i0 = sx.toInt(); val j0 = sy.toInt()
                val s1 = sx - i0; val s0 = 1f - s1
                val t1 = sy - j0; val t0 = 1f - t1
                val r0 = j0 * n2; val r1 = (j0 + 1) * n2
                d[idx] = s0 * (t0 * d0[r0 + i0] + t1 * d0[r1 + i0]) +
                         s1 * (t0 * d0[r0 + i0 + 1] + t1 * d0[r1 + i0 + 1])
            }
        }
        setBnd(b, d, N)
    }

    private fun project(
        u: FloatArray, v: FloatArray,
        p: FloatArray, dv: FloatArray,
        N: Int, iter: Int
    ) {
        val n2 = N + 2
        val hStep = 1f / N
        for (j in 1..N) {
            val row = j * n2
            for (i in 1..N) {
                val idx = row + i
                dv[idx] = -0.5f * hStep * (
                    u[idx + 1] - u[idx - 1] +
                    v[idx + n2] - v[idx - n2]
                )
                p[idx] = 0f
            }
        }
        setBnd(0, dv, N)
        setBnd(0, p, N)

        for (k in 0 until iter) {
            for (j in 1..N) {
                val row = j * n2
                for (i in 1..N) {
                    val idx = row + i
                    p[idx] = (dv[idx] + p[idx - 1] + p[idx + 1] + p[idx - n2] + p[idx + n2]) * 0.25f
                }
            }
            setBnd(0, p, N)
        }

        val h2 = 0.5f * N
        for (j in 1..N) {
            val row = j * n2
            for (i in 1..N) {
                val idx = row + i
                u[idx] -= h2 * (p[idx + 1] - p[idx - 1])
                v[idx] -= h2 * (p[idx + n2] - p[idx - n2])
            }
        }
        setBnd(1, u, N)
        setBnd(2, v, N)
    }

    private fun velStep(fs: FluidState, visc: Float, dt: Float, iter: Int) {
        val N = fs.N
        diffuse(1, fs.vx0, fs.vx, visc, dt, N, iter)
        diffuse(2, fs.vy0, fs.vy, visc, dt, N, iter)
        project(fs.vx0, fs.vy0, fs.p, fs.div, N, iter)
        advect(1, fs.vx, fs.vx0, fs.vx0, fs.vy0, dt, N)
        advect(2, fs.vy, fs.vy0, fs.vx0, fs.vy0, dt, N)
        project(fs.vx, fs.vy, fs.p, fs.div, N, iter)
    }

    private fun densStep(fs: FluidState, diff: Float, dt: Float, iter: Int) {
        val N = fs.N
        diffuse(0, fs.dens0, fs.dens, diff, dt, N, iter)
        advect(0, fs.dens, fs.dens0, fs.vx, fs.vy, dt, N)
    }

    // ── Injection patterns ──────────────────────────────────────────────────
    private fun injectSwirl(
        fs: FluidState, strength: Float, dyeSpread: Float, dyeRate: Float, time: Float
    ) {
        val N = fs.N
        val vx = fs.vx; val vy = fs.vy; val dens = fs.dens
        val n2 = N + 2
        val spreadSq = dyeSpread * dyeSpread
        for (k in 0 until fs.srcCount) {
            val cx = (fs.srcX[k] * N).toFloat()
            val cy = (fs.srcY[k] * N).toFloat()
            val phase = time * 1.2f + k * ((PI * 2.0).toFloat() / fs.srcCount)
            val fx = cos(phase) * strength
            val fy = sin(phase) * strength
            val i0 = max(1, (cx - dyeSpread).toInt())
            val i1 = min(N, (cx + dyeSpread + 1f).toInt())
            val j0 = max(1, (cy - dyeSpread).toInt())
            val j1 = min(N, (cy + dyeSpread + 1f).toInt())
            for (j in j0..j1) {
                val row = j * n2
                for (i in i0..i1) {
                    val dx = i - cx; val dy = j - cy
                    val dSq = dx * dx + dy * dy
                    if (dSq > spreadSq) continue
                    val wWeight = 1f - dSq / spreadSq
                    val idx = row + i
                    vx[idx] += fx * wWeight
                    vy[idx] += fy * wWeight
                    dens[idx] += dyeRate * wWeight
                }
            }
        }
    }

    private fun injectJets(
        fs: FluidState, strength: Float, dyeSpread: Float, dyeRate: Float, time: Float
    ) {
        val N = fs.N
        val vx = fs.vx; val vy = fs.vy; val dens = fs.dens
        val n2 = N + 2
        val spreadSq = dyeSpread * dyeSpread
        val piF = PI.toFloat()
        val srcCap = min(fs.srcCount, 3)
        for (k in 0 until srcCap) {
            val edge = k % 3
            val cx: Float
            val cy: Float
            val angle: Float
            when (edge) {
                0 -> {
                    cx = 2f
                    cy = N * (0.3f + 0.4f * sin(time * 0.3f + k))
                    angle = sin(time * 0.5f + k * 2f) * piF / 4f
                }
                1 -> {
                    cx = (N - 1).toFloat()
                    cy = N * (0.3f + 0.4f * sin(time * 0.35f + k))
                    angle = piF + sin(time * 0.5f + k * 2f) * piF / 4f
                }
                else -> {
                    cx = N * (0.3f + 0.4f * sin(time * 0.4f + k))
                    cy = (N - 1).toFloat()
                    angle = -piF / 2f + sin(time * 0.6f + k) * piF / 3f
                }
            }
            val fx = cos(angle) * strength * 1.5f
            val fy = sin(angle) * strength * 1.5f
            val i0 = max(1, (cx - dyeSpread).toInt())
            val i1 = min(N, (cx + dyeSpread + 1f).toInt())
            val j0 = max(1, (cy - dyeSpread).toInt())
            val j1 = min(N, (cy + dyeSpread + 1f).toInt())
            for (j in j0..j1) {
                val row = j * n2
                for (i in i0..i1) {
                    val dx = i - cx; val dy = j - cy
                    val dSq = dx * dx + dy * dy
                    if (dSq > spreadSq) continue
                    val wWeight = 1f - dSq / spreadSq
                    val idx = row + i
                    vx[idx] += fx * wWeight
                    vy[idx] += fy * wWeight
                    dens[idx] += dyeRate * wWeight * 1.5f
                }
            }
        }
    }

    private fun injectNoise(
        fs: FluidState, strength: Float, dyeSpread: Float, dyeRate: Float, time: Float
    ) {
        val N = fs.N
        val vx = fs.vx; val vy = fs.vy; val dens = fs.dens
        val n2 = N + 2
        val scale = 0.04f
        val tFactor = time * 0.3f
        val step = max(2, (dyeSpread * 0.8f).toInt())
        val twoPi = (PI * 2.0).toFloat()
        var j = 1
        while (j <= N) {
            var i = 1
            while (i <= N) {
                val nx = i * scale
                val ny = j * scale
                val angle = fs.noise.noise2D(nx + tFactor, ny) * twoPi
                val mag = (fs.noise.noise2D(nx, ny + tFactor) + 1f) * 0.5f
                val fx = cos(angle) * strength * mag * 0.3f
                val fy = sin(angle) * strength * mag * 0.3f
                val idx = j * n2 + i
                vx[idx] += fx
                vy[idx] += fy
                if (mag > 0.6f) {
                    val dw = dyeRate * (mag - 0.6f) * 2.5f
                    for (dj in -1..1) {
                        for (di in -1..1) {
                            val ii = i + di; val jj = j + dj
                            if (ii in 1..N && jj in 1..N) {
                                dens[jj * n2 + ii] += dw
                            }
                        }
                    }
                }
                i += step
            }
            j += step
        }
    }

    private fun injectPulse(
        fs: FluidState, strength: Float, dyeSpread: Float, dyeRate: Float, @Suppress("UNUSED_PARAMETER") time: Float
    ) {
        if (fs.step % 40 != 0) return
        val N = fs.N
        val vx = fs.vx; val vy = fs.vy; val dens = fs.dens
        val n2 = N + 2
        val cx = 0.15f + fs.rng.random() * 0.7f
        val cy = 0.15f + fs.rng.random() * 0.7f
        val gi = (cx * N).toInt()
        val gj = (cy * N).toInt()
        val spread = (dyeSpread * 2f).toInt()
        val spreadSq = (spread * spread).toFloat()
        val jLo = max(1, gj - spread)
        val jHi = min(N, gj + spread)
        val iLo = max(1, gi - spread)
        val iHi = min(N, gi + spread)
        for (j in jLo..jHi) {
            val row = j * n2
            for (i in iLo..iHi) {
                val dx = (i - gi).toFloat(); val dy = (j - gj).toFloat()
                val dSq = dx * dx + dy * dy
                if (dSq > spreadSq || dSq < 1f) continue
                val dist = sqrt(dSq)
                val wWeight = 1f - dist / spread
                val idx = row + i
                vx[idx] += (dx / dist) * strength * wWeight * 3f
                vy[idx] += (dy / dist) * strength * wWeight * 3f
                dens[idx] += dyeRate * wWeight * 3f
            }
        }
    }

    private fun injectForPattern(
        fs: FluidState, pattern: String,
        strength: Float, dyeSpread: Float, dyeRate: Float, time: Float
    ) {
        when (pattern) {
            "jets" -> injectJets(fs, strength, dyeSpread, dyeRate, time)
            "noise" -> injectNoise(fs, strength, dyeSpread, dyeRate, time)
            "pulse" -> injectPulse(fs, strength, dyeSpread, dyeRate, time)
            else -> injectSwirl(fs, strength, dyeSpread, dyeRate, time)
        }
    }

    companion object {
        // Fluid state
        @Volatile
        private var _fs: FluidState? = null

        // Pixel buffer
        @Volatile
        private var _px: IntArray? = null
        private var _imgW = 0
        private var _imgH = 0

        // Column bilinear weights
        @Volatile
        private var _colI0: IntArray? = null
        @Volatile
        private var _colS0: FloatArray? = null
        @Volatile
        private var _colS1: FloatArray? = null
        private var _colW = 0
        private var _colN = 0

        // Row bilinear weights
        @Volatile
        private var _rowJ0: IntArray? = null
        @Volatile
        private var _rowT0: FloatArray? = null
        @Volatile
        private var _rowT1: FloatArray? = null
        private var _rowH = 0
        private var _rowN = 0

        // Pre-computed scalar field
        @Volatile
        private var _scalar: FloatArray? = null
        private var _scalarSz = 0

        // Palette LUT (256 entries, ARGB)
        @Volatile
        private var _lut: IntArray? = null
        private var _lutKey: String = ""

        // Heatmap LUT (256 entries, ARGB)
        private val _heatLut: IntArray = IntArray(256).also { arr ->
            for (i in 0 until 256) {
                val t = i / 255f
                val r: Int; val g: Int; val b: Int
                if (t < 0.33f) {
                    val s = t / 0.33f
                    r = (s * 255f).toInt(); g = 0; b = 0
                } else if (t < 0.66f) {
                    val s = (t - 0.33f) / 0.33f
                    r = 255; g = (s * 255f).toInt(); b = 0
                } else {
                    val s = (t - 0.66f) / 0.34f
                    r = 255; g = 255; b = (s * 255f).toInt()
                }
                arr[i] = Color.argb(255, r.coerceIn(0, 255), g.coerceIn(0, 255), b.coerceIn(0, 255))
            }
        }

        // Contrast LUT: pow(t, 0.4)
        private val _cLut: IntArray = IntArray(256).also { arr ->
            for (i in 0 until 256) {
                val t = i / 255.0
                arr[i] = (Math.pow(t, 0.4) * 255.0 + 0.5).toInt().coerceIn(0, 255)
            }
        }

        private fun buildLUT(palR: IntArray, palG: IntArray, palB: IntArray) {
            val n = palR.size
            val lut = _lut ?: IntArray(256).also { _lut = it }
            for (i in 0 until 256) {
                val t = i / 255f
                val s = t * (n - 1)
                val i0 = s.toInt().coerceAtMost(n - 1)
                val i1 = min(n - 1, i0 + 1)
                val f = s - i0
                val r = (palR[i0] + (palR[i1] - palR[i0]) * f).toInt().coerceIn(0, 255)
                val g = (palG[i0] + (palG[i1] - palG[i0]) * f).toInt().coerceIn(0, 255)
                val b = (palB[i0] + (palB[i1] - palB[i0]) * f).toInt().coerceIn(0, 255)
                lut[i] = Color.argb(255, r, g, b)
            }
        }

        private fun ensureRenderBuf(w: Int, h: Int, N: Int) {
            if (_px == null || _imgW != w || _imgH != h) {
                _px = IntArray(w * h)
                _imgW = w; _imgH = h
            }
            if (_colW != w || _colN != N) {
                val cI0 = IntArray(w)
                val cS0 = FloatArray(w)
                val cS1 = FloatArray(w)
                val sx = N.toFloat() / w
                for (pxC in 0 until w) {
                    val gx = 1f + pxC * sx
                    var i0 = gx.toInt()
                    if (i0 > N - 1) i0 = N - 1
                    cI0[pxC] = i0
                    val tx = gx - i0
                    cS0[pxC] = 1f - tx
                    cS1[pxC] = tx
                }
                _colI0 = cI0; _colS0 = cS0; _colS1 = cS1
                _colW = w; _colN = N
            }
            if (_rowH != h || _rowN != N) {
                val rJ0 = IntArray(h)
                val rT0 = FloatArray(h)
                val rT1 = FloatArray(h)
                val sy = N.toFloat() / h
                for (py in 0 until h) {
                    val gy = 1f + py * sy
                    var j0 = gy.toInt()
                    if (j0 > N - 1) j0 = N - 1
                    rJ0[py] = j0
                    val ty = gy - j0
                    rT0[py] = 1f - ty
                    rT1[py] = ty
                }
                _rowJ0 = rJ0; _rowT0 = rT0; _rowT1 = rT1
                _rowH = h; _rowN = N
            }
            val sz = (N + 2) * (N + 2)
            if (_scalarSz != sz) {
                _scalar = FloatArray(sz)
                _scalarSz = sz
            }
        }
    }
}
