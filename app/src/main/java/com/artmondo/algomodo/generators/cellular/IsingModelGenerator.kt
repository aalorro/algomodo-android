package com.artmondo.algomodo.generators.cellular

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import com.artmondo.algomodo.core.rng.SeededRNG
import com.artmondo.algomodo.data.palettes.Palette
import com.artmondo.algomodo.generators.Generator
import com.artmondo.algomodo.generators.ParamGroup
import com.artmondo.algomodo.generators.Parameter
import com.artmondo.algomodo.generators.Quality
import kotlin.math.exp

class IsingModelGenerator : Generator {

    override val id = "cellular-ising-model"
    override val family = "cellular"
    override val styleName = "Ising Model"
    override val definition = "2D Ising spin model: a statistical mechanics simulation of magnetic domains with temperature-dependent phase transitions."
    override val algorithmNotes = "Each cell holds a spin (+1 or -1). The Metropolis-Hastings algorithm flips spins probabilistically: flips that lower energy are always accepted, others are accepted with probability exp(-dE/T). At the critical temperature (~2.27), large-scale domain structures emerge."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema = listOf(
        Parameter.NumberParam("Grid Size", "gridSize", ParamGroup.COMPOSITION, null, 32f, 256f, 16f, 128f),
        Parameter.NumberParam("Temperature (T)", "temperature", ParamGroup.COMPOSITION, "Temperature in units where J=1, kB=1 — critical temperature ≈ 2.27; below → ferromagnetic order, above → paramagnetic disorder", 0.5f, 5.0f, 0.05f, 2.27f),
        Parameter.NumberParam("External Field (H)", "externalField", ParamGroup.COMPOSITION, "Applied magnetic field — positive values favour up-spins, negative values favour down-spins", -1.5f, 1.5f, 0.05f, 0f),
        Parameter.NumberParam("Warmup Sweeps", "iterations", ParamGroup.COMPOSITION, "Monte Carlo sweeps for the static render (1 sweep = N² spin-flip attempts)", 10f, 2000f, 10f, 300f),
        Parameter.NumberParam("Sweeps / Frame", "sweepsPerFrame", ParamGroup.FLOW_MOTION, null, 1f, 20f, 1f, 5f),
        Parameter.SelectParam("Display Mode", "displayMode", ParamGroup.COLOR, "spin: white/black | palette: palette first/last | local-mag: 3x3 average → palette gradient | flip-age: recently flipped cells glow", listOf("spin", "palette", "local-mag", "flip-age"), "spin"),
        Parameter.SelectParam("Boundary", "boundary", ParamGroup.GEOMETRY, "periodic: torus topology | open: spins at edges have fewer neighbours", listOf("periodic", "open"), "periodic")
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "gridSize" to 128f,
        "temperature" to 2.27f,
        "externalField" to 0f,
        "iterations" to 300f,
        "sweepsPerFrame" to 5f,
        "displayMode" to "spin",
        "boundary" to "periodic"
    )

    // ---- Simulation cache: avoids re-running all sweeps from scratch each frame ----
    private class SimState(
        val gridSize: Int,
        val seed: Int,
        val boundary: String,
        val spin: IntArray,
        val flipAge: IntArray,
        var sweepsDone: Int,
        var rngState: Long
    )

    @Volatile private var cachedSim: SimState? = null
    @Volatile private var reuseGridColors: IntArray? = null
    @Volatile private var reusePixels: IntArray? = null

    override fun renderCanvas(
        canvas: Canvas,
        bitmap: Bitmap,
        params: Map<String, Any>,
        seed: Int,
        palette: Palette,
        quality: Quality,
        time: Float
    ) {
        val gridSize = (params["gridSize"] as? Number)?.toInt() ?: 128
        val temperature = (params["temperature"] as? Number)?.toFloat() ?: 2.27f
        val externalField = (params["externalField"] as? Number)?.toFloat() ?: 0f
        val iterations = (params["iterations"] as? Number)?.toInt() ?: 300
        val sweepsPerFrame = (params["sweepsPerFrame"] as? Number)?.toFloat() ?: 5f
        val displayMode = (params["displayMode"] as? String) ?: "spin"
        val boundary = (params["boundary"] as? String) ?: "periodic"

        val w = bitmap.width
        val h = bitmap.height
        val N = gridSize
        val totalCells = N * N
        val targetSweeps = iterations + (time * sweepsPerFrame).toInt()
        val periodic = boundary == "periodic"

        // ---- Get or create simulation state (cache across frames) ----
        val sim = cachedSim?.takeIf {
            it.gridSize == N && it.seed == seed &&
                it.boundary == boundary && it.sweepsDone <= targetSweeps
        } ?: run {
            val rng = SeededRNG(seed)
            val spin = IntArray(totalCells) { if (rng.boolean()) 1 else -1 }
            val flipAge = IntArray(totalCells) { -1 }
            SimState(N, seed, boundary, spin, flipAge, 0,
                seed.toLong() xor 0x5DEECE66DL xor (seed.toLong() shl 32))
        }
        cachedSim = sim

        // ---- Precompute Boltzmann acceptance LUT ----
        // For each (spin, neighborSum) pair, precompute acceptance probability.
        // Eliminates exp() from the hot loop entirely.
        // Index: acceptLut[sIdx][neighborSum + 4]
        //   sIdx: s=+1 → 0, s=-1 → 1
        //   neighborSum ∈ [-4, 4] → index [0, 8]
        val beta = 1f / temperature
        val acceptLut = Array(2) { sIdx ->
            val s = if (sIdx == 0) 1f else -1f
            FloatArray(9) { nsIdx ->
                val nsum = nsIdx - 4
                val dE = 2f * s * (nsum + externalField)
                if (dE <= 0f) 1f else exp((-beta * dE).toDouble()).toFloat().coerceAtMost(1f)
            }
        }

        // ---- Advance simulation: checkerboard Metropolis ----
        // Checkerboard update: process even/odd cells in alternating half-sweeps.
        // Each cell's 4 neighbors are on the opposite parity → no data races.
        // Eliminates random x,y selection (2 fewer RNG calls per flip).
        // Uses fast xorshift64 instead of full SeededRNG.
        var rng = sim.rngState
        val spin = sim.spin
        val flipAge = sim.flipAge

        for (sweep in sim.sweepsDone until targetSweeps) {
            for (parity in 0..1) {
                for (y in 0 until N) {
                    val rowOff = y * N
                    val topOff: Int
                    val botOff: Int
                    if (periodic) {
                        topOff = (if (y > 0) y - 1 else N - 1) * N
                        botOff = (if (y < N - 1) y + 1 else 0) * N
                    } else {
                        topOff = if (y > 0) (y - 1) * N else -1
                        botOff = if (y < N - 1) (y + 1) * N else -1
                    }
                    var x = (y + parity) and 1
                    while (x < N) {
                        val idx = rowOff + x
                        val s = spin[idx]
                        val sIdx = (1 - s) ushr 1 // s=1→0, s=-1→1

                        // Neighbor sum (4 reads for periodic, 2-4 for open)
                        var nsum = 0
                        if (topOff >= 0) nsum += spin[topOff + x]
                        if (botOff >= 0) nsum += spin[botOff + x]
                        if (periodic) {
                            nsum += spin[rowOff + (if (x > 0) x - 1 else N - 1)]
                            nsum += spin[rowOff + (if (x < N - 1) x + 1 else 0)]
                        } else {
                            if (x > 0) nsum += spin[idx - 1]
                            if (x < N - 1) nsum += spin[idx + 1]
                        }

                        // Fast xorshift64 RNG
                        rng = rng xor (rng shl 13)
                        rng = rng xor (rng ushr 7)
                        rng = rng xor (rng shl 17)
                        val r = (rng ushr 40 and 0xFFFFFFL).toFloat() / 16777216f

                        if (r < acceptLut[sIdx][nsum + 4]) {
                            spin[idx] = -s
                            flipAge[idx] = sweep
                        }
                        x += 2
                    }
                }
            }
        }
        sim.sweepsDone = targetSweeps
        sim.rngState = rng

        // ---- Phase 1: Compute color per grid cell (not per pixel) ----
        val gridColors: IntArray
        val rgc = reuseGridColors
        if (rgc != null && rgc.size >= totalCells) {
            gridColors = rgc
        } else {
            gridColors = IntArray(totalCells)
            reuseGridColors = gridColors
        }

        when (displayMode) {
            "palette" -> {
                val colorUp = palette.colorAt(0)
                val colorDown = palette.colorAt(palette.colorInts().size - 1)
                for (i in 0 until totalCells) {
                    gridColors[i] = if (spin[i] == 1) colorUp else colorDown
                }
            }
            "local-mag" -> {
                // Pre-compute 3×3 local magnetization per grid cell → palette color.
                // 16K computations instead of 2.6M (one per pixel).
                val palLutSize = 256
                val palLutMax = palLutSize - 1
                val palLut = IntArray(palLutSize) { palette.lerpColor(it.toFloat() / palLutMax) }
                for (gy in 0 until N) {
                    for (gx in 0 until N) {
                        var localSum = 0
                        var count = 0
                        for (dy in -1..1) {
                            for (dx in -1..1) {
                                val nx: Int; val ny: Int
                                if (periodic) {
                                    nx = (gx + dx + N) % N
                                    ny = (gy + dy + N) % N
                                } else {
                                    nx = gx + dx; ny = gy + dy
                                    if (nx !in 0 until N || ny !in 0 until N) continue
                                }
                                localSum += spin[ny * N + nx]
                                count++
                            }
                        }
                        val mag = (localSum.toFloat() / count + 1f) * 0.5f
                        gridColors[gy * N + gx] = palLut[(mag * palLutMax).toInt().coerceIn(0, palLutMax)]
                    }
                }
            }
            "flip-age" -> {
                val palLutSize = 256
                val palLutMax = palLutSize - 1
                val palLut = IntArray(palLutSize) { palette.lerpColor(it.toFloat() / palLutMax) }
                val window = (targetSweeps * 0.15f).toInt().coerceAtLeast(5)
                val invWindow = 1f / window
                for (i in 0 until totalCells) {
                    gridColors[i] = if (flipAge[i] < 0) {
                        Color.BLACK
                    } else {
                        val recency = (1f - (targetSweeps - flipAge[i]) * invWindow).coerceIn(0f, 1f)
                        palLut[(recency * palLutMax).toInt().coerceIn(0, palLutMax)]
                    }
                }
            }
            else /* spin */ -> {
                val colorUp = palette.colorAt(0)
                val colorDown = palette.colorAt(palette.colorInts().size - 1)
                for (i in 0 until totalCells) {
                    gridColors[i] = if (spin[i] == 1) colorUp else colorDown
                }
            }
        }

        // ---- Phase 2: Expand grid colors to full pixel array ----
        val pixels: IntArray
        val rp = reusePixels
        val pixelCount = w * h
        if (rp != null && rp.size >= pixelCount) {
            pixels = rp
        } else {
            pixels = IntArray(pixelCount)
            reusePixels = pixels
        }

        // Pre-compute x → grid column mapping (avoids per-pixel division)
        val xMap = IntArray(w) { (it * N / w).coerceAtMost(N - 1) }
        for (py in 0 until h) {
            val gy = (py * N / h).coerceAtMost(N - 1)
            val gridRow = gy * N
            val pixRow = py * w
            for (px in 0 until w) {
                pixels[pixRow + px] = gridColors[gridRow + xMap[px]]
            }
        }

        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
        canvas.drawBitmap(bitmap, 0f, 0f, null)
    }

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float {
        val gridSize = (params["gridSize"] as? Number)?.toInt() ?: 128
        val iterations = (params["iterations"] as? Number)?.toInt() ?: 300
        return (gridSize * gridSize.toLong() * iterations / 50_000_000f).coerceIn(0.1f, 1f)
    }
}
