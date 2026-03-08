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
        val sweepsPerFrame = (params["sweepsPerFrame"] as? Number)?.toFloat() ?: 5f

        val w = bitmap.width
        val h = bitmap.height
        val sweeps = (time * sweepsPerFrame).toInt()
        val totalCells = gridSize * gridSize

        // Initialize spins randomly from seed
        val rng = SeededRNG(seed)
        val spin = IntArray(totalCells) {
            if (rng.boolean()) 1 else -1
        }

        // Precompute acceptance probabilities for Metropolis
        // dE can be -8, -4, 0, 4, 8 for 2D Ising
        val beta = 1f / temperature
        val acceptProb = FloatArray(17) // index = dE + 8
        for (de in -8..8) {
            acceptProb[de + 8] = if (de <= 0) 1f else exp(-beta * de).coerceAtMost(1f)
        }

        // Metropolis Monte Carlo sweeps
        for (sweep in 0 until sweeps) {
            // Each sweep: attempt N*N flips
            for (attempt in 0 until totalCells) {
                val x = rng.integer(0, gridSize - 1)
                val y = rng.integer(0, gridSize - 1)
                val idx = y * gridSize + x
                val s = spin[idx]

                // Sum of neighbors
                val top = spin[((y - 1 + gridSize) % gridSize) * gridSize + x]
                val bottom = spin[((y + 1) % gridSize) * gridSize + x]
                val left = spin[y * gridSize + (x - 1 + gridSize) % gridSize]
                val right = spin[y * gridSize + (x + 1) % gridSize]
                val neighborSum = top + bottom + left + right

                // Energy change for flipping
                val dE = 2 * s * neighborSum // dE = 2 * s_i * sum(s_j)

                if (rng.random() < acceptProb[dE + 8]) {
                    spin[idx] = -s
                }
            }
        }

        // Render: map +1 and -1 to two palette colors
        val cellW = w.toFloat() / gridSize
        val cellH = h.toFloat() / gridSize
        val pixels = IntArray(w * h)
        val colorUp = palette.colorAt(0)
        val colorDown = palette.colorAt(palette.colorInts().size - 1)

        for (py in 0 until h) {
            val gy = (py / cellH).toInt().coerceAtMost(gridSize - 1)
            for (px in 0 until w) {
                val gx = (px / cellW).toInt().coerceAtMost(gridSize - 1)
                val idx = gy * gridSize + gx
                pixels[py * w + px] = if (spin[idx] == 1) colorUp else colorDown
            }
        }

        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
        canvas.drawBitmap(bitmap, 0f, 0f, null)
    }
}
