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

class BriansBrainGenerator : Generator {

    override val id = "cellular-brians-brain"
    override val family = "cellular"
    override val styleName = "Brian's Brain"
    override val definition = "A three-state cellular automaton that produces chaotic, self-propagating patterns."
    override val algorithmNotes = "Three states: On (firing), Dying (refractory), Off (ready). On cells become Dying, Dying cells become Off, Off cells become On if exactly 2 Moore neighbors are On. Produces dynamic, turbulent patterns."
    override val supportsVector = false
    override val supportsAnimation = true

    companion object {
        private const val OFF = 0
        private const val ON = 1
        private const val DYING = 2
    }

    override val parameterSchema = listOf(
        Parameter.NumberParam("Grid Size", "gridSize", ParamGroup.COMPOSITION, null, 32f, 256f, 16f, 128f),
        Parameter.NumberParam("Initial ON Density", "initialDensity", ParamGroup.COMPOSITION, "Fraction of cells starting ON (an equal fraction start DYING)", 0.05f, 0.20f, 0.05f, 0.15f),
        Parameter.NumberParam("Warmup Steps", "warmupSteps", ParamGroup.COMPOSITION, "Steps run before the static render snapshot", 0f, 200f, 5f, 30f),
        Parameter.NumberParam("Steps / Frame", "stepsPerFrame", ParamGroup.FLOW_MOTION, null, 1f, 10f, 1f, 1f),
        Parameter.SelectParam("Color Mode", "colorMode", ParamGroup.COLOR, "classic: white / blue / dark | palette: last / mid / first palette colours for ON / DYING / OFF", listOf("classic", "palette"), "classic")
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "gridSize" to 128f,
        "initialDensity" to 0.15f,
        "warmupSteps" to 30f,
        "stepsPerFrame" to 1f,
        "colorMode" to "classic"
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
        val gridSize = (params["gridSize"] as? Number)?.toInt() ?: 80
        val initialDensity = (params["initialDensity"] as? Number)?.toFloat() ?: 0.15f
        val warmupSteps = (params["warmupSteps"] as? Number)?.toInt() ?: 30
        val stepsPerFrame = (params["stepsPerFrame"] as? Number)?.toFloat() ?: 1f
        val colorMode = (params["colorMode"] as? String) ?: "classic"

        val w = bitmap.width
        val h = bitmap.height
        val steps = warmupSteps + (time * stepsPerFrame).toInt()
        val totalCells = gridSize * gridSize

        // Initialize from seed
        val rng = SeededRNG(seed)
        var grid = IntArray(totalCells)
        for (i in 0 until totalCells) {
            grid[i] = if (rng.random() < initialDensity) ON else OFF
        }

        // Evolve
        for (s in 0 until steps) {
            val next = IntArray(totalCells)
            for (y in 0 until gridSize) {
                for (x in 0 until gridSize) {
                    val idx = y * gridSize + x
                    when (grid[idx]) {
                        ON -> next[idx] = DYING
                        DYING -> next[idx] = OFF
                        OFF -> {
                            var onNeighbors = 0
                            for (dy in -1..1) {
                                for (dx in -1..1) {
                                    if (dx == 0 && dy == 0) continue
                                    val nx = (x + dx + gridSize) % gridSize
                                    val ny = (y + dy + gridSize) % gridSize
                                    if (grid[ny * gridSize + nx] == ON) onNeighbors++
                                }
                            }
                            next[idx] = if (onNeighbors == 2) ON else OFF
                        }
                    }
                }
            }
            grid = next
        }

        // Render
        val cellW = w.toFloat() / gridSize
        val cellH = h.toFloat() / gridSize
        val pixels = IntArray(w * h)
        val paletteColors = palette.colorInts()

        // Determine colors based on colorMode
        val onColor: Int
        val dyingColor: Int
        val offColor: Int
        when (colorMode) {
            "palette" -> {
                // last / mid / first palette colours for ON / DYING / OFF
                onColor = paletteColors[paletteColors.size - 1]
                dyingColor = paletteColors[paletteColors.size / 2]
                offColor = paletteColors[0]
            }
            else /* classic */ -> {
                onColor = Color.WHITE
                dyingColor = Color.rgb(60, 100, 200) // blue
                offColor = Color.rgb(20, 20, 30) // dark
            }
        }

        for (py in 0 until h) {
            val gy = (py / cellH).toInt().coerceAtMost(gridSize - 1)
            for (px in 0 until w) {
                val gx = (px / cellW).toInt().coerceAtMost(gridSize - 1)
                val idx = gy * gridSize + gx
                pixels[py * w + px] = when (grid[idx]) {
                    ON -> onColor
                    DYING -> dyingColor
                    else -> offColor
                }
            }
        }

        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
        canvas.drawBitmap(bitmap, 0f, 0f, null)
    }
}
