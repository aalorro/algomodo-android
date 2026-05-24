package com.artmondo.algomodo.generators.physics

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import com.artmondo.algomodo.audio.AudioAnalysis
import com.artmondo.algomodo.core.rng.SeededRNG
import com.artmondo.algomodo.data.palettes.Palette
import com.artmondo.algomodo.generators.Generator
import com.artmondo.algomodo.generators.ParamGroup
import com.artmondo.algomodo.generators.Parameter
import com.artmondo.algomodo.generators.Quality
import kotlin.math.*

class WavePropagationGenerator : Generator {

    override val id = "physics-wave-propagation"
    override val family = "physics"
    override val styleName = "Wave Propagation"
    override val definition =
        "Two-dimensional wave equation simulation — drops create ripples that propagate, reflect, and interfere, with optional dual-layer grids for rich interference patterns"
    override val algorithmNotes =
        "Discrete 2D wave equation: next[i,j] = 2·cur − prev + c²·(∑neighbors − 4·cur), with per-step damping multiplier. " +
        "Two independent wave grids at slightly different speeds produce interference patterns when summed. " +
        "Drops inject Gaussian-like height spikes (quadratic falloff). Three boundary modes: reflect (Neumann), absorb (exponential edge decay), wrap (toroidal). " +
        "Rendering: full-resolution inline bilinear interpolation from grid scalar field with pre-computed weights. " +
        "Caustic mode computes height gradient (∂h/∂x, ∂h/∂y) to simulate light refraction for a water-surface effect."

    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema = listOf(
        Parameter.SelectParam(
            name = "Render Style", key = "style", group = ParamGroup.COMPOSITION,
            help = "gradient: height → palette | caustic: fake light refraction | heightmap: signed height as brightness | 3d-surface: perspective 3D view",
            options = listOf("gradient", "caustic", "heightmap", "3d-surface"),
            default = "caustic"
        ),
        Parameter.SelectParam(
            name = "Drop Pattern", key = "dropPattern", group = ParamGroup.COMPOSITION,
            help = "random: scattered | grid: sequential grid points | circular: ring pattern | spiral: expanding spiral arm",
            options = listOf("random", "grid", "circular", "spiral"),
            default = "random"
        ),
        Parameter.NumberParam(
            name = "Wave Speed", key = "waveSpeed", group = ParamGroup.FLOW_MOTION,
            help = "Propagation speed factor c — higher = faster spreading",
            min = 0.05f, max = 0.5f, step = 0.01f, default = 0.2f
        ),
        Parameter.NumberParam(
            name = "Damping", key = "damping", group = ParamGroup.FLOW_MOTION,
            help = "Energy retention per step — lower = faster decay",
            min = 0.95f, max = 1.0f, step = 0.002f, default = 0.996f
        ),
        Parameter.NumberParam(
            name = "Drop Frequency", key = "dropFreq", group = ParamGroup.FLOW_MOTION,
            help = "Drops per second",
            min = 0.5f, max = 10f, step = 0.5f, default = 3f
        ),
        Parameter.NumberParam(
            name = "Drop Intensity", key = "dropIntensity", group = ParamGroup.FLOW_MOTION,
            help = "Height spike on each drop",
            min = 0.5f, max = 10f, step = 0.5f, default = 4f
        ),
        Parameter.BooleanParam(
            name = "Dual Layer", key = "dualWave", group = ParamGroup.COMPOSITION,
            help = "Two overlapping wave grids with slightly different speeds for interference patterns",
            default = true
        ),
        Parameter.NumberParam(
            name = "Speed Offset", key = "speedOffset", group = ParamGroup.FLOW_MOTION,
            help = "Speed difference between dual wave layers",
            min = 0.01f, max = 0.15f, step = 0.01f, default = 0.05f
        ),
        Parameter.SelectParam(
            name = "Boundary", key = "boundary", group = ParamGroup.GEOMETRY,
            help = "reflect: mirror at edges | absorb: energy absorbed at edges | wrap: toroidal",
            options = listOf("reflect", "absorb", "wrap"),
            default = "reflect"
        ),
        Parameter.NumberParam(
            name = "Sharpness", key = "sharpness", group = ParamGroup.TEXTURE,
            help = "Controls edge crispness — 0 = soft/dreamy, 0.5 = balanced, 1.0 = razor-sharp",
            min = 0f, max = 1f, step = 0.1f, default = 0.7f
        ),
        Parameter.SelectParam(
            name = "Color Mode", key = "colorMode", group = ParamGroup.COLOR,
            help = "palette: current palette | monochrome: blue-white | ocean: deep blue → cyan | lava: black → red → orange → yellow | neon: dark → magenta → cyan → white | thermal: black → blue → red → yellow → white | aurora: deep green → teal → violet → pink",
            options = listOf("palette", "monochrome", "ocean", "lava", "neon", "thermal", "aurora"),
            default = "palette"
        ),
        Parameter.NumberParam(
            name = "Steps / Frame", key = "stepsPerFrame", group = ParamGroup.FLOW_MOTION,
            help = null,
            min = 1f, max = 6f, step = 1f, default = 3f
        ),
        Parameter.NumberParam(
            name = "Camera Dist", key = "cameraDistance", group = ParamGroup.COMPOSITION,
            help = "3D surface: camera distance from grid center",
            min = 2f, max = 10f, step = 0.5f, default = 5f
        ),
        Parameter.NumberParam(
            name = "Camera Angle", key = "cameraAngle", group = ParamGroup.COMPOSITION,
            help = "3D surface: horizontal orbit angle",
            min = 0f, max = 360f, step = 5f, default = 45f
        ),
        Parameter.NumberParam(
            name = "Camera Height", key = "cameraHeight", group = ParamGroup.COMPOSITION,
            help = "3D surface: vertical camera position",
            min = 0.5f, max = 4f, step = 0.1f, default = 2f
        ),
        Parameter.NumberParam(
            name = "FOV", key = "fov", group = ParamGroup.COMPOSITION,
            help = "3D surface: field of view",
            min = 30f, max = 90f, step = 5f, default = 55f
        ),
        Parameter.NumberParam(
            name = "Light Angle", key = "lightAngle", group = ParamGroup.TEXTURE,
            help = "3D surface: horizontal light direction",
            min = 0f, max = 360f, step = 5f, default = 45f
        ),
        Parameter.NumberParam(
            name = "Exposure", key = "exposure", group = ParamGroup.TEXTURE,
            help = "3D surface: tone mapping exposure",
            min = 0.5f, max = 3f, step = 0.1f, default = 1.2f
        ),
        Parameter.NumberParam(
            name = "Speed", key = "speed", group = ParamGroup.FLOW_MOTION,
            help = "3D surface: camera orbit speed",
            min = 0.1f, max = 2f, step = 0.1f, default = 0.5f
        ),
        Parameter.NumberParam(
            name = "Audio Reactivity", key = "reactivity", group = ParamGroup.FLOW_MOTION,
            help = null,
            min = 0f, max = 2f, step = 0.1f, default = 0f
        )
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "style" to "caustic",
        "dropPattern" to "random",
        "waveSpeed" to 0.2f,
        "damping" to 0.996f,
        "dropFreq" to 3f,
        "dropIntensity" to 4f,
        "dualWave" to true,
        "speedOffset" to 0.05f,
        "boundary" to "reflect",
        "sharpness" to 0.7f,
        "colorMode" to "palette",
        "stepsPerFrame" to 3f,
        "cameraDistance" to 5f,
        "cameraAngle" to 45f,
        "cameraHeight" to 2f,
        "fov" to 55f,
        "lightAngle" to 45f,
        "exposure" to 1.2f,
        "speed" to 0.5f,
        "reactivity" to 0f
    )

    // ── Param helpers ──
    private fun pf(p: Map<String, Any>, k: String, default: Float): Float = (p[k] as? Number)?.toFloat() ?: default
    private fun pi(p: Map<String, Any>, k: String, default: Int): Int = (p[k] as? Number)?.toInt() ?: default
    private fun pb(p: Map<String, Any>, k: String, default: Boolean): Boolean = (p[k] as? Boolean) ?: default
    private fun ps(p: Map<String, Any>, k: String, default: String): String = (p[k] as? String) ?: default

    // ── Wave state (cached singleton) ──
    private data class WaveState(
        var key: String,
        val N: Int,
        var cur: FloatArray,
        var prev: FloatArray,
        var cur2: FloatArray,
        var prev2: FloatArray,
        var step: Int,
        val rng: SeededRNG
    )

    companion object {
        @Volatile private var ws: WaveState? = null

        // Palette LUT (256 entries, ARGB)
        @Volatile private var lut: IntArray = IntArray(256)
        @Volatile private var lutKey: String = ""

        // Dynamic contrast LUT
        @Volatile private var contrastLut: IntArray = IntArray(256)
        @Volatile private var contrastExp: Float = -1f

        // Render buffers
        @Volatile private var bufW: Int = 0
        @Volatile private var bufH: Int = 0
        @Volatile private var bufN: Int = 0
        @Volatile private var pixels: IntArray = IntArray(0)
        @Volatile private var scalar: FloatArray = FloatArray(0)
        @Volatile private var sharp: FloatArray = FloatArray(0)
        @Volatile private var scalarN: Int = 0

        @Volatile private var colI0: IntArray = IntArray(0)
        @Volatile private var colS0: FloatArray = FloatArray(0)
        @Volatile private var colS1: FloatArray = FloatArray(0)
        @Volatile private var rowJ0: IntArray = IntArray(0)
        @Volatile private var rowT0: FloatArray = FloatArray(0)
        @Volatile private var rowT1: FloatArray = FloatArray(0)

        // Merged buffer for caustic/gradient
        @Volatile private var merged: FloatArray = FloatArray(0)
        @Volatile private var mergedN: Int = 0

        // Built-in color LUTs
        @Volatile private var monoLut: IntArray = IntArray(256)
        @Volatile private var oceanLut: IntArray = IntArray(256)
        @Volatile private var lavaLut: IntArray = IntArray(256)
        @Volatile private var neonLut: IntArray = IntArray(256)
        @Volatile private var thermalLut: IntArray = IntArray(256)
        @Volatile private var auroraLut: IntArray = IntArray(256)
        @Volatile private var builtinLutsBuilt: Boolean = false
    }

    private fun hexToRgb(hex: String): IntArray {
        val cleaned = hex.removePrefix("#")
        val n = cleaned.toLong(16).toInt()
        return intArrayOf((n shr 16) and 0xFF, (n shr 8) and 0xFF, n and 0xFF)
    }

    private fun buildGradientLut(target: IntArray, colors: Array<IntArray>) {
        for (i in 0 until 256) {
            val t = i / 255f
            val s = t * (colors.size - 1)
            val i0 = s.toInt()
            val i1 = min(colors.size - 1, i0 + 1)
            val f = s - i0
            val r = (colors[i0][0] + (colors[i1][0] - colors[i0][0]) * f).toInt()
            val g = (colors[i0][1] + (colors[i1][1] - colors[i0][1]) * f).toInt()
            val b = (colors[i0][2] + (colors[i1][2] - colors[i0][2]) * f).toInt()
            target[i] = Color.argb(255, r.coerceIn(0, 255), g.coerceIn(0, 255), b.coerceIn(0, 255))
        }
    }

    private fun buildPaletteLut(colors: Array<IntArray>) {
        for (i in 0 until 256) {
            val t = i / 255f
            val s = t * (colors.size - 1)
            val i0 = s.toInt()
            val i1 = min(colors.size - 1, i0 + 1)
            val f = s - i0
            val r = (colors[i0][0] + (colors[i1][0] - colors[i0][0]) * f).toInt()
            val g = (colors[i0][1] + (colors[i1][1] - colors[i0][1]) * f).toInt()
            val b = (colors[i0][2] + (colors[i1][2] - colors[i0][2]) * f).toInt()
            lut[i] = Color.argb(255, r.coerceIn(0, 255), g.coerceIn(0, 255), b.coerceIn(0, 255))
        }
    }

    private fun ensureBuiltinLuts() {
        if (builtinLutsBuilt) return
        synchronized(Companion) {
            if (builtinLutsBuilt) return
            // Monochrome: dark blue → white
            for (i in 0 until 256) {
                val t = i / 255f
                val r = (t * 200).toInt()
                val g = (t * 220).toInt()
                val b = (80 + t * 175).toInt()
                monoLut[i] = Color.argb(255, r.coerceIn(0, 255), g.coerceIn(0, 255), b.coerceIn(0, 255))
            }
            buildGradientLut(oceanLut, arrayOf(
                intArrayOf(5, 10, 40), intArrayOf(10, 40, 120), intArrayOf(20, 100, 180),
                intArrayOf(80, 200, 220), intArrayOf(220, 250, 255)
            ))
            buildGradientLut(lavaLut, arrayOf(
                intArrayOf(5, 0, 0), intArrayOf(80, 0, 0), intArrayOf(180, 30, 0),
                intArrayOf(240, 120, 10), intArrayOf(255, 220, 60), intArrayOf(255, 255, 180)
            ))
            buildGradientLut(neonLut, arrayOf(
                intArrayOf(5, 0, 15), intArrayOf(40, 0, 80), intArrayOf(180, 0, 200),
                intArrayOf(0, 200, 255), intArrayOf(180, 255, 255), intArrayOf(255, 255, 255)
            ))
            buildGradientLut(thermalLut, arrayOf(
                intArrayOf(0, 0, 0), intArrayOf(10, 10, 80), intArrayOf(30, 30, 180),
                intArrayOf(180, 30, 30), intArrayOf(240, 180, 30), intArrayOf(255, 255, 220)
            ))
            buildGradientLut(auroraLut, arrayOf(
                intArrayOf(0, 10, 5), intArrayOf(0, 60, 30), intArrayOf(10, 140, 100),
                intArrayOf(40, 160, 180), intArrayOf(120, 80, 200), intArrayOf(200, 100, 180),
                intArrayOf(255, 180, 220)
            ))
            builtinLutsBuilt = true
        }
    }

    private fun rebuildContrastLut(exp: Float) {
        if (exp == contrastExp) return
        contrastExp = exp
        for (i in 0 until 256) {
            val t = i / 255f
            val tp = t.toDouble().pow(exp.toDouble())
            val ip = (1.0 - t).pow(exp.toDouble())
            val v = tp / (tp + ip + 1e-10)
            contrastLut[i] = (v * 255 + 0.5).toInt().coerceIn(0, 255)
        }
    }

    private fun ensureBuf(w: Int, h: Int, N: Int) {
        if (bufW != w || bufH != h) {
            pixels = IntArray(w * h)
            bufW = w; bufH = h
            bufN = 0
        }
        if (scalarN != N) {
            scalar = FloatArray(N * N)
            sharp = FloatArray(N * N)
            scalarN = N
            bufN = 0
        }
        if (bufN != N) {
            colI0 = IntArray(w)
            colS0 = FloatArray(w)
            colS1 = FloatArray(w)
            val scaleX = (N - 1).toFloat() / w
            for (x in 0 until w) {
                val gx = x * scaleX
                val i0 = min(gx.toInt(), N - 2)
                colI0[x] = i0
                val s1 = gx - i0
                colS0[x] = 1f - s1
                colS1[x] = s1
            }
            rowJ0 = IntArray(h)
            rowT0 = FloatArray(h)
            rowT1 = FloatArray(h)
            val scaleY = (N - 1).toFloat() / h
            for (y in 0 until h) {
                val gy = y * scaleY
                val j0 = min(gy.toInt(), N - 2)
                rowJ0[y] = j0
                val t1 = gy - j0
                rowT0[y] = 1f - t1
                rowT1[y] = t1
            }
            bufN = N
        }
    }

    private fun initWave(N: Int, seed: Int): WaveState {
        val size = N * N
        return WaveState(
            key = "", N = N,
            cur = FloatArray(size),
            prev = FloatArray(size),
            cur2 = FloatArray(size),
            prev2 = FloatArray(size),
            step = 0,
            rng = SeededRNG(seed)
        )
    }

    // ── Wave equation step ──
    private fun stepWave(
        cur: FloatArray, prev: FloatArray,
        N: Int, c2: Float, damping: Float,
        boundary: String
    ) {
        val size = N * N
        val next = prev // reuse prev as scratch

        for (j in 1 until N - 1) {
            val row = j * N
            for (i in 1 until N - 1) {
                val idx = row + i
                val laplacian = cur[idx - 1] + cur[idx + 1] +
                        cur[idx - N] + cur[idx + N] -
                        4f * cur[idx]
                next[idx] = (2f * cur[idx] - prev[idx] + c2 * laplacian) * damping
            }
        }

        if (boundary == "wrap") {
            for (i in 0 until N) {
                val top = i
                val bot = (N - 1) * N + i
                val lapT = cur[if (top == 0) N - 1 else top - 1] +
                        cur[if (top + 1 < N) top + 1 else 0] +
                        cur[(N - 1) * N + i] +
                        cur[N + i] -
                        4f * cur[top]
                next[top] = (2f * cur[top] - prev[top] + c2 * lapT) * damping
                val lapB = cur[if (bot - 1 >= (N - 1) * N) bot - 1 else bot] +
                        cur[if (bot + 1 < size) bot + 1 else (N - 1) * N] +
                        cur[(N - 2) * N + i] +
                        cur[i] -
                        4f * cur[bot]
                next[bot] = (2f * cur[bot] - prev[bot] + c2 * lapB) * damping
            }
            for (j in 1 until N - 1) {
                val left = j * N
                val right = j * N + N - 1
                val lapL = cur[j * N + N - 1] +
                        cur[left + 1] +
                        cur[(j - 1) * N] +
                        cur[(j + 1) * N] -
                        4f * cur[left]
                next[left] = (2f * cur[left] - prev[left] + c2 * lapL) * damping
                val lapR = cur[right - 1] +
                        cur[j * N] +
                        cur[(j - 1) * N + N - 1] +
                        cur[(j + 1) * N + N - 1] -
                        4f * cur[right]
                next[right] = (2f * cur[right] - prev[right] + c2 * lapR) * damping
            }
        } else if (boundary == "absorb") {
            for (i in 0 until N) {
                next[i] = cur[i] * 0.9f * damping
                next[(N - 1) * N + i] = cur[(N - 1) * N + i] * 0.9f * damping
            }
            for (j in 1 until N - 1) {
                next[j * N] = cur[j * N] * 0.9f * damping
                next[j * N + N - 1] = cur[j * N + N - 1] * 0.9f * damping
            }
        } else {
            // Reflect (Neumann)
            for (i in 0 until N) {
                next[i] = next[N + i]
                next[(N - 1) * N + i] = next[(N - 2) * N + i]
            }
            for (j in 0 until N) {
                next[j * N] = next[j * N + 1]
                next[j * N + N - 1] = next[j * N + N - 2]
            }
        }
    }

    // ── Drop injection ──
    private fun addDrop(
        cur: FloatArray, N: Int,
        cx: Float, cy: Float, intensity: Float, radius: Float
    ) {
        val r = max(1, radius.toInt())
        val rSq = r * r
        val i0 = max(0, (cx - r).toInt())
        val i1 = min(N - 1, (cx + r).toInt())
        val j0 = max(0, (cy - r).toInt())
        val j1 = min(N - 1, (cy + r).toInt())
        for (j in j0..j1) {
            val row = j * N
            for (i in i0..i1) {
                val dx = i - cx
                val dy = j - cy
                val dSq = dx * dx + dy * dy
                if (dSq > rSq) continue
                val w = 1f - sqrt(dSq) / r
                cur[row + i] += intensity * w * w
            }
        }
    }

    private fun injectDrops(
        ws: WaveState, dropPattern: String,
        dropFreq: Float, dropIntensity: Float, dualWave: Boolean
    ) {
        val N = ws.N
        val cur = ws.cur
        val cur2 = ws.cur2
        val rng = ws.rng
        val step = ws.step
        val interval = max(1, (60f / dropFreq).roundToInt())
        if (step % interval != 0) return

        val radius = max(1f, N * 0.02f)

        when (dropPattern) {
            "grid" -> {
                val gridN = 4
                val which = (step / interval) % (gridN * gridN)
                val gi = which % gridN
                val gj = which / gridN
                val cx = N * (0.15f + gi * 0.7f / (gridN - 1))
                val cy = N * (0.15f + gj * 0.7f / (gridN - 1))
                addDrop(cur, N, cx, cy, dropIntensity, radius)
                if (dualWave) addDrop(cur2, N, cx, cy, dropIntensity * 0.7f, radius)
            }
            "circular" -> {
                val nDrops = 3 + (step / interval) % 5
                val phase = step * 0.03f
                val cr = N * 0.25f
                for (k in 0 until nDrops) {
                    val a = phase + (k.toFloat() / nDrops) * PI.toFloat() * 2f
                    val cx = N * 0.5f + cos(a) * cr
                    val cy = N * 0.5f + sin(a) * cr
                    addDrop(cur, N, cx, cy, dropIntensity * 0.7f, radius)
                    if (dualWave) {
                        val a2 = phase * 1.3f + (k.toFloat() / nDrops) * PI.toFloat() * 2f
                        addDrop(cur2, N,
                            N * 0.5f + cos(a2) * cr * 0.6f,
                            N * 0.5f + sin(a2) * cr * 0.6f,
                            dropIntensity * 0.5f, radius)
                    }
                }
            }
            "spiral" -> {
                val angle = step * 0.15f
                val r = N * 0.08f + (step % 60) * N * 0.006f
                val cx = N * 0.5f + cos(angle) * r
                val cy = N * 0.5f + sin(angle) * r
                addDrop(cur, N, cx, cy, dropIntensity, radius)
                if (dualWave) {
                    val a2 = angle + PI.toFloat()
                    addDrop(cur2, N,
                        N * 0.5f + cos(a2) * r * 0.7f,
                        N * 0.5f + sin(a2) * r * 0.7f,
                        dropIntensity * 0.7f, radius)
                }
            }
            else -> {
                // random
                val nDrops = 1 + (step / interval) % 3
                for (k in 0 until nDrops) {
                    val cx = N * 0.05f + rng.random() * N * 0.9f
                    val cy = N * 0.05f + rng.random() * N * 0.9f
                    addDrop(cur, N, cx, cy, dropIntensity, radius)
                    if (dualWave) {
                        addDrop(cur2, N,
                            N * 0.05f + rng.random() * N * 0.9f,
                            N * 0.05f + rng.random() * N * 0.9f,
                            dropIntensity * 0.6f, radius)
                    }
                }
            }
        }
    }

    private fun getColorLUT(colorMode: String, palette: Palette): IntArray {
        ensureBuiltinLuts()
        return when (colorMode) {
            "monochrome" -> monoLut
            "ocean" -> oceanLut
            "lava" -> lavaLut
            "neon" -> neonLut
            "thermal" -> thermalLut
            "aurora" -> auroraLut
            else -> {
                val palKey = palette.colors.joinToString(",")
                if (lutKey != palKey) {
                    val cols = Array(palette.colors.size) { hexToRgb(palette.colors[it]) }
                    buildPaletteLut(cols)
                    lutKey = palKey
                }
                lut
            }
        }
    }

    // ── Render helpers ──
    private fun buildMergedHeightField(cur: FloatArray, cur2: FloatArray?, N: Int): FloatArray {
        val size = N * N
        if (mergedN != N) {
            merged = FloatArray(size)
            mergedN = N
        }
        val hf = merged
        if (cur2 != null) {
            for (k in 0 until size) hf[k] = cur[k] + cur2[k]
        } else {
            System.arraycopy(cur, 0, hf, 0, size)
        }
        return hf
    }

    private fun computeGradient(scalar: FloatArray, cur: FloatArray, cur2: FloatArray?, N: Int) {
        val size = N * N
        if (mergedN != N) { merged = FloatArray(size); mergedN = N }
        val m = merged
        if (cur2 != null) {
            for (k in 0 until size) m[k] = cur[k] + cur2[k]
        } else {
            System.arraycopy(cur, 0, m, 0, size)
        }

        var maxGrad = 0.001f
        val step = max(1, N / 80)
        var j = 1
        while (j < N - 1) {
            val row = j * N
            var i = 1
            while (i < N - 1) {
                val idx = row + i
                val dx = m[idx + 1] - m[idx - 1]
                val dy = m[idx + N] - m[idx - N]
                val g = dx * dx + dy * dy
                if (g > maxGrad) maxGrad = g
                i += step
            }
            j += step
        }
        maxGrad = sqrt(maxGrad)
        val invMax = 1f / maxGrad

        for (i in 0 until N) {
            scalar[i] = 0f
            scalar[(N - 1) * N + i] = 0f
        }
        for (jj in 0 until N) {
            scalar[jj * N] = 0f
            scalar[jj * N + N - 1] = 0f
        }

        for (jj in 1 until N - 1) {
            val row = jj * N
            for (i in 1 until N - 1) {
                val idx = row + i
                val dx = m[idx + 1] - m[idx - 1]
                val dy = m[idx + N] - m[idx - N]
                var t = sqrt(dx * dx + dy * dy) * invMax
                if (t > 1f) t = 1f
                scalar[idx] = t
            }
        }
    }

    private fun computeHeightmap(scalar: FloatArray, cur: FloatArray, cur2: FloatArray?, N: Int) {
        var maxAmp = 0.001f
        val size = N * N
        for (k in 0 until size) {
            var v = cur[k]
            if (cur2 != null) v += cur2[k]
            val a = if (v < 0) -v else v
            if (a > maxAmp) maxAmp = a
        }
        val invMax = 1f / maxAmp

        for (j in 0 until N) {
            val row = j * N
            for (i in 0 until N) {
                val idx = row + i
                var v = cur[idx]
                if (cur2 != null) v += cur2[idx]
                var t = v * invMax * 0.5f + 0.5f
                if (t < 0f) t = 0f else if (t > 1f) t = 1f
                scalar[idx] = t
            }
        }
    }

    private fun computeCaustic(scalar: FloatArray, cur: FloatArray, cur2: FloatArray?, N: Int) {
        val size = N * N
        if (mergedN != N) { merged = FloatArray(size); mergedN = N }
        val m = merged
        if (cur2 != null) {
            for (k in 0 until size) m[k] = cur[k] + cur2[k]
        } else {
            System.arraycopy(cur, 0, m, 0, size)
        }

        var maxGrad = 0.001f
        val sampleStep = max(1, N / 60)
        var j = 1
        while (j < N - 1) {
            val row = j * N
            var i = 1
            while (i < N - 1) {
                val idx = row + i
                val gx = -m[idx - 1 - N] + m[idx + 1 - N] -
                        2f * m[idx - 1] + 2f * m[idx + 1] -
                        m[idx - 1 + N] + m[idx + 1 + N]
                val gy = -m[idx - N - 1] - 2f * m[idx - N] - m[idx - N + 1] +
                        m[idx + N - 1] + 2f * m[idx + N] + m[idx + N + 1]
                val grad = gx * gx + gy * gy
                if (grad > maxGrad) maxGrad = grad
                i += sampleStep
            }
            j += sampleStep
        }
        maxGrad = sqrt(maxGrad)
        val invMax = 1f / maxGrad

        val lightX = 0.7f
        val lightY = -0.7f

        for (i in 0 until N) {
            scalar[i] = 0f
            scalar[(N - 1) * N + i] = 0f
        }
        for (jj in 0 until N) {
            scalar[jj * N] = 0f
            scalar[jj * N + N - 1] = 0f
        }

        for (jj in 1 until N - 1) {
            val row = jj * N
            for (i in 1 until N - 1) {
                val idx = row + i
                val gx = -m[idx - 1 - N] + m[idx + 1 - N] -
                        2f * m[idx - 1] + 2f * m[idx + 1] -
                        m[idx - 1 + N] + m[idx + 1 + N]
                val gy = -m[idx - N - 1] - 2f * m[idx - N] - m[idx - N + 1] +
                        m[idx + N - 1] + 2f * m[idx + N] + m[idx + N + 1]

                val gradMag = sqrt(gx * gx + gy * gy)
                val normGrad = min(1f, gradMag * invMax)
                val dot = (gx * lightX + gy * lightY) * invMax

                var intensity = 0.12f + normGrad * 0.28f + dot * 0.4f
                if (dot > 0f) {
                    val d2 = dot * dot
                    intensity += d2 * 0.4f
                    intensity += d2 * d2 * 0.3f
                }
                if (intensity < 0f) intensity = 0f else if (intensity > 1f) intensity = 1f
                scalar[idx] = sqrt(intensity)
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
        val w = bitmap.width
        val h = bitmap.height
        if (w == 0 || h == 0) return

        val N = when (quality) {
            Quality.DRAFT -> 450
            Quality.ULTRA -> 900
            else -> 650
        }

        val waveSpeed = pf(params, "waveSpeed", 0.2f)
        val damping = pf(params, "damping", 0.996f)
        var dropFreq = pf(params, "dropFreq", 3f)
        var dropIntensity = pf(params, "dropIntensity", 4f)
        val dropPattern = ps(params, "dropPattern", "random")
        val dualWave = pb(params, "dualWave", true)
        val speedOffset = pf(params, "speedOffset", 0.05f)
        val boundary = ps(params, "boundary", "reflect")
        val style = ps(params, "style", "caustic")
        val colorMode = ps(params, "colorMode", "palette")
        val sharpness = pf(params, "sharpness", 0.7f).coerceIn(0f, 1f)
        val spf = max(1, pi(params, "stepsPerFrame", 3))

        // Audio reactivity
        val rx = pf(params, "reactivity", 0f)
        val audio = params["_audioAnalysis"] as? AudioAnalysis
        val audioBass = (audio?.getBass(time) ?: 0f) * rx
        val audioMid = (audio?.getMid(time) ?: 0f) * rx
        val audioHigh = (audio?.getHigh(time) ?: 0f) * rx
        val audioEnergy = (audioBass + audioMid + audioHigh) / 3f
        dropIntensity += audioBass * 3f
        dropFreq += audioEnergy * 4f

        val c2 = waveSpeed * waveSpeed
        val c2b = (waveSpeed + speedOffset) * (waveSpeed + speedOffset)

        synchronized(Companion) {
            val key = "$seed|$N|$boundary"
            var state = ws
            if (state == null || state.key != key) {
                state = initWave(N, seed)
                state.key = key
                ws = state
            }

            // Rebuild palette LUT if needed (palette mode)
            val palKey = palette.colors.joinToString(",")
            if (lutKey != palKey) {
                val cols = Array(palette.colors.size) { hexToRgb(palette.colors[it]) }
                buildPaletteLut(cols)
                lutKey = palKey
            }

            ensureBuf(w, h, N)

            // ── Warmup ──
            if (time <= 0f && state.step == 0) {
                for (f in 0 until 90) {
                    injectDrops(state, dropPattern, dropFreq, dropIntensity, dualWave)
                    stepWave(state.cur, state.prev, N, c2, damping, boundary)
                    val tmp = state.prev; state.prev = state.cur; state.cur = tmp
                    if (dualWave) {
                        stepWave(state.cur2, state.prev2, N, c2b, damping, boundary)
                        val tmp2 = state.prev2; state.prev2 = state.cur2; state.cur2 = tmp2
                    }
                    state.step++
                }
            }

            // ── Animation steps ──
            if (time > 0f) {
                for (s in 0 until spf) {
                    injectDrops(state, dropPattern, dropFreq, dropIntensity, dualWave)
                    if (audioEnergy > 0.3f) {
                        addDrop(state.cur, N,
                            N * 0.1f + state.rng.random() * N * 0.8f,
                            N * 0.1f + state.rng.random() * N * 0.8f,
                            dropIntensity * audioEnergy, N * 0.025f)
                    }
                    stepWave(state.cur, state.prev, N, c2, damping, boundary)
                    val tmp = state.prev; state.prev = state.cur; state.cur = tmp
                    if (dualWave) {
                        stepWave(state.cur2, state.prev2, N, c2b, damping, boundary)
                        val tmp2 = state.prev2; state.prev2 = state.cur2; state.cur2 = tmp2
                    }
                    state.step++
                }
            }

            // ── 3D surface fallback ──
            if (style == "3d-surface") {
                render3DSurfaceCPU(canvas, state, params, palette, time, w, h, N, dualWave)
                return
            }

            // ── Compute scalar field at grid resolution ──
            val cur = state.cur
            val cur2 = state.cur2
            val sc = scalar

            when (style) {
                "caustic" -> computeCaustic(sc, cur, if (dualWave) cur2 else null, N)
                "heightmap" -> computeHeightmap(sc, cur, if (dualWave) cur2 else null, N)
                else -> computeGradient(sc, cur, if (dualWave) cur2 else null, N)
            }

            // ── Two-pass Laplacian sharpen on grid scalar field ──
            val alpha = 0.3f + sharpness * 1.5f
            val alpha2 = 0.15f + sharpness * 0.75f
            val sigmoidExp = 1.5f + sharpness * 4.0f
            rebuildContrastLut(sigmoidExp)

            val sh = sharp
            // Pass 1: sc → sh
            for (j in 1 until N - 1) {
                val row = j * N
                for (i in 1 until N - 1) {
                    val idx = row + i
                    val lap = sc[idx - 1] + sc[idx + 1] + sc[idx - N] + sc[idx + N] - 4f * sc[idx]
                    var v = sc[idx] - alpha * lap
                    if (v < 0f) v = 0f else if (v > 1f) v = 1f
                    sh[idx] = v
                }
            }
            for (i in 0 until N) { sh[i] = sc[i]; sh[(N - 1) * N + i] = sc[(N - 1) * N + i] }
            for (j in 0 until N) { sh[j * N] = sc[j * N]; sh[j * N + N - 1] = sc[j * N + N - 1] }
            // Pass 2: sh → sc
            for (j in 1 until N - 1) {
                val row = j * N
                for (i in 1 until N - 1) {
                    val idx = row + i
                    val lap = sh[idx - 1] + sh[idx + 1] + sh[idx - N] + sh[idx + N] - 4f * sh[idx]
                    var v = sh[idx] - alpha2 * lap
                    if (v < 0f) v = 0f else if (v > 1f) v = 1f
                    sc[idx] = v
                }
            }
            for (i in 0 until N) { sc[i] = sh[i]; sc[(N - 1) * N + i] = sh[(N - 1) * N + i] }
            for (j in 0 until N) { sc[j * N] = sh[j * N]; sc[j * N + N - 1] = sh[j * N + N - 1] }
            val sharpField = sc

            val activeLut = getColorLUT(colorMode, palette)
            val cLut = contrastLut

            // ── Full-resolution bilinear upsample + contrast + LUT ──
            val px = pixels
            val cI0 = colI0; val cS0 = colS0; val cS1 = colS1
            val rJ0 = rowJ0; val rT0 = rowT0; val rT1 = rowT1

            for (py in 0 until h) {
                val j0 = rJ0[py]; val t0 = rT0[py]; val t1 = rT1[py]
                val r0 = j0 * N; val r1 = (j0 + 1) * N
                val rowOff = py * w
                for (x in 0 until w) {
                    val i0 = cI0[x]; val s0 = cS0[x]; val s1 = cS1[x]
                    var v = t0 * (s0 * sharpField[r0 + i0] + s1 * sharpField[r0 + i0 + 1]) +
                            t1 * (s0 * sharpField[r1 + i0] + s1 * sharpField[r1 + i0 + 1])
                    if (v < 0f) v = 0f else if (v > 1f) v = 1f
                    px[rowOff + x] = activeLut[cLut[(v * 255f + 0.5f).toInt().coerceIn(0, 255)]]
                }
            }

            bitmap.setPixels(px, 0, w, 0, 0, w, h)
        }
    }

    // ── 3D surface Canvas fallback ──
    private fun render3DSurfaceCPU(
        canvas: Canvas,
        ws: WaveState,
        params: Map<String, Any>,
        palette: Palette,
        time: Float,
        w: Int, h: Int, N: Int, dualWave: Boolean
    ) {
        val hf = buildMergedHeightField(ws.cur, if (dualWave) ws.cur2 else null, N)
        val heightScale = 0.3f
        val spd = pf(params, "speed", 0.5f)
        val camAngle = pf(params, "cameraAngle", 45f) * PI.toFloat() / 180f + time * spd * 0.1f
        val camDist = pf(params, "cameraDistance", 5f)
        val camH = pf(params, "cameraHeight", 2f)
        val fov = pf(params, "fov", 55f)
        val fovFactor = tan(fov * PI.toFloat() / 360f)

        // Camera
        val roX = sin(camAngle) * camDist
        val roY = camH
        val roZ = cos(camAngle) * camDist
        val fwdX = -roX; val fwdY = -roY; val fwdZ = -roZ
        val fLen = sqrt(fwdX * fwdX + fwdY * fwdY + fwdZ * fwdZ).let { if (it == 0f) 1f else it }
        val fx = fwdX / fLen; val fy = fwdY / fLen; val fz = fwdZ / fLen
        var rx = fz; val ry = 0f; var rz = -fx
        val rLen = sqrt(rx * rx + rz * rz).let { if (it == 0f) 1f else it }
        rx /= rLen; rz /= rLen
        val ux = ry * fz - rz * fy
        val uy = rz * fx - rx * fz
        val uz = rx * fy - ry * fx
        val aspect = w.toFloat() / h

        // Subsample grid
        val step = max(2, N / 120)
        val cols = N / step
        val rows = N / step

        // Project function
        fun project(wx: Float, wy: Float, wz: Float, dst: FloatArray) {
            val dx = wx - roX; val dy = wy - roY; val dz = wz - roZ
            val z = dx * fx + dy * fy + dz * fz
            if (z < 0.01f) { dst[0] = -1f; dst[1] = -1f; dst[2] = z; return }
            val x = dx * rx + dy * ry + dz * rz
            val y = dx * ux + dy * uy + dz * uz
            val invZ = 1f / (z * fovFactor)
            val sx = w * 0.5f + x * invZ * w * 0.5f / aspect
            val sy = h * 0.5f - y * invZ * h * 0.5f
            dst[0] = sx; dst[1] = sy; dst[2] = z
        }

        // Dark background
        canvas.drawColor(Color.rgb(10, 11, 16))

        // Build palette RGB array
        val palRgb = Array(palette.colors.size) { hexToRgb(palette.colors[it]) }
        val nColors = palRgb.size

        // Build quad list with distance for sort (Triple<gi, gj, dist>)
        val quadGi = IntArray((rows - 1) * (cols - 1))
        val quadGj = IntArray((rows - 1) * (cols - 1))
        val quadDist = FloatArray((rows - 1) * (cols - 1))
        var qIdx = 0
        for (gj in 0 until rows - 1) {
            for (gi in 0 until cols - 1) {
                val cx = (gi + 0.5f) * step / N - 0.5f
                val cz = (gj + 0.5f) * step / N - 0.5f
                val dx = cx - roX; val dz = cz - roZ
                quadGi[qIdx] = gi
                quadGj[qIdx] = gj
                quadDist[qIdx] = dx * dx + dz * dz
                qIdx++
            }
        }
        // Sort indices by descending distance
        val order = (0 until qIdx).sortedByDescending { quadDist[it] }

        // Light direction
        val lAngle = pf(params, "lightAngle", 45f) * PI.toFloat() / 180f
        val lx = sin(lAngle); val ly = 0.8f; val lz = cos(lAngle)
        val lLen = sqrt(lx * lx + ly * ly + lz * lz)
        val ldx = lx / lLen; val ldy = ly / lLen; val ldz = lz / lLen

        val paint = Paint().apply {
            isAntiAlias = false
            style = Paint.Style.FILL
        }
        val path = Path()
        val p00 = FloatArray(3); val p10 = FloatArray(3)
        val p01 = FloatArray(3); val p11 = FloatArray(3)

        for (qi in order) {
            val gi = quadGi[qi]; val gj = quadGj[qi]
            val i0 = gi * step; val i1 = min((gi + 1) * step, N - 1)
            val j0 = gj * step; val j1 = min((gj + 1) * step, N - 1)

            val h00 = hf[j0 * N + i0] * heightScale
            val h10 = hf[j0 * N + i1] * heightScale
            val h01 = hf[j1 * N + i0] * heightScale
            val h11 = hf[j1 * N + i1] * heightScale

            val wx0 = i0.toFloat() / N - 0.5f; val wx1 = i1.toFloat() / N - 0.5f
            val wz0 = j0.toFloat() / N - 0.5f; val wz1 = j1.toFloat() / N - 0.5f

            project(wx0, h00, wz0, p00)
            project(wx1, h10, wz0, p10)
            project(wx0, h01, wz1, p01)
            project(wx1, h11, wz1, p11)

            if (p00[2] < 0.01f && p10[2] < 0.01f && p01[2] < 0.01f && p11[2] < 0.01f) continue

            // Normal from cross product
            val ax = wx1 - wx0; val ay = h10 - h00; val az = 0f
            val bx = 0f; val by = h01 - h00; val bz = wz1 - wz0
            var nx = ay * bz - az * by
            var ny = az * bx - ax * bz
            var nz = ax * by - ay * bx
            val nLen = sqrt(nx * nx + ny * ny + nz * nz).let { if (it == 0f) 1f else it }
            nx /= nLen; ny /= nLen; nz /= nLen

            val NdotL = max(0f, nx * ldx + ny * ldy + nz * ldz)
            val brightness = 0.15f + 0.85f * NdotL

            val avgH = (h00 + h10 + h01 + h11) * 0.25f
            val palT = (avgH * 0.8f + 0.5f).coerceIn(0f, 1f)
            val cIdx = min((palT * nColors).toInt(), nColors - 1)
            val cr = palRgb[cIdx][0]
            val cg = palRgb[cIdx][1]
            val cb = palRgb[cIdx][2]

            paint.color = Color.rgb(
                (cr * brightness).toInt().coerceIn(0, 255),
                (cg * brightness).toInt().coerceIn(0, 255),
                (cb * brightness).toInt().coerceIn(0, 255)
            )
            path.reset()
            path.moveTo(p00[0], p00[1])
            path.lineTo(p10[0], p10[1])
            path.lineTo(p11[0], p11[1])
            path.lineTo(p01[0], p01[1])
            path.close()
            canvas.drawPath(path, paint)
        }
    }
}
