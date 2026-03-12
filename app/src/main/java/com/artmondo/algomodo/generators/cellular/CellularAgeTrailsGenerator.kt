package com.artmondo.algomodo.generators.cellular

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import com.artmondo.algomodo.core.rng.SeededRNG
import com.artmondo.algomodo.data.palettes.Palette
import kotlin.math.pow
import com.artmondo.algomodo.generators.Generator
import com.artmondo.algomodo.generators.ParamGroup
import com.artmondo.algomodo.generators.Parameter
import com.artmondo.algomodo.generators.Quality

class CellularAgeTrailsGenerator : Generator {

    override val id = "cellular-age-trails"
    override val family = "cellular"
    override val styleName = "Age Trails"
    override val definition = "A Game of Life variant that tracks how recently each cell was alive, producing colorful fading trails."
    override val algorithmNotes = "Standard B3/S23 rules are applied. Each cell maintains an age counter: living cells have age 0, recently dead cells count up. Cells with age below the trail length are rendered with color mapped through the palette gradient."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema = listOf(
        Parameter.NumberParam("Grid Size", "gridSize", ParamGroup.COMPOSITION, "Cell grid resolution", 32f, 256f, 16f, 128f),
        Parameter.NumberParam("Initial Density", "density", ParamGroup.COMPOSITION, "Fraction of cells alive at start — above 0.40 the initial density is too high for patterns to emerge", 0.1f, 0.40f, 0.05f, 0.35f),
        Parameter.SelectParam("CA Rule", "rule", ParamGroup.COMPOSITION, "Life B3/S23 | HighLife B36/S23 (self-replicators) | Maze B3/S12345 | Day & Night B3678/S34678 | Seeds B2/S (explosive, nothing survives)", listOf("life", "highlife", "maze", "daynight", "seeds"), "life"),
        Parameter.NumberParam("Exposure Frames", "warmupSteps", ParamGroup.COMPOSITION, "CA steps blended into the static render — more frames = denser historical record", 50f, 2000f, 50f, 400f),
        Parameter.NumberParam("Trail Decay", "decay", ParamGroup.FLOW_MOTION, "Per-step decay multiplier on accumulated brightness — lower = short crisp trails, higher = long ghostly halos", 0.80f, 0.999f, 0.005f, 0.95f),
        Parameter.NumberParam("Exposure", "exposure", ParamGroup.FLOW_MOTION, "Brightness added per alive-cell frame — raise to saturate stable regions faster", 0.05f, 2.0f, 0.05f, 0.5f),
        Parameter.NumberParam("Steps / Frame", "stepsPerFrame", ParamGroup.FLOW_MOTION, "CA steps advanced per animation frame", 1f, 10f, 1f, 1f),
        Parameter.NumberParam("Gamma", "gamma", ParamGroup.TEXTURE, "Tone-map exponent — < 1 lifts dim trails into view, > 1 compresses midtones for starker contrast", 0.3f, 3.0f, 0.1f, 0.7f),
        Parameter.SelectParam("Color Mode", "colorMode", ParamGroup.COLOR, "palette: map brightness through the active colour ramp | heat: fixed black → red → orange → white heat map", listOf("palette", "heat"), "palette")
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "gridSize" to 128f,
        "density" to 0.35f,
        "rule" to "life",
        "warmupSteps" to 400f,
        "decay" to 0.95f,
        "exposure" to 0.5f,
        "stepsPerFrame" to 1f,
        "gamma" to 0.7f,
        "colorMode" to "palette"
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
        val density = (params["density"] as? Number)?.toFloat() ?: 0.35f
        val rule = (params["rule"] as? String) ?: "life"
        val warmupSteps = (params["warmupSteps"] as? Number)?.toInt() ?: 400
        val stepsPerFrame = (params["stepsPerFrame"] as? Number)?.toFloat() ?: 1f
        val decay = (params["decay"] as? Number)?.toFloat() ?: 0.95f
        val exposure = (params["exposure"] as? Number)?.toFloat() ?: 0.5f
        val gamma = (params["gamma"] as? Number)?.toFloat() ?: 0.7f
        val colorMode = (params["colorMode"] as? String) ?: "palette"

        val w = bitmap.width
        val h = bitmap.height
        val steps = warmupSteps + (time * stepsPerFrame).toInt()

        // Initialize grid from seed
        val rng = SeededRNG(seed)
        val totalCells = gridSize * gridSize
        var alive = BooleanArray(totalCells)
        // Age tracks how many steps since a cell was last alive
        // Derive effective trail length from decay: at decay^trailLen < 0.01, trailLen = log(0.01)/log(decay)
        val effectiveTrailLength = if (decay < 1f) (kotlin.math.ln(0.01f) / kotlin.math.ln(decay)).toInt().coerceIn(1, 200) else 200
        var age = IntArray(totalCells) { effectiveTrailLength + 1 }

        for (i in 0 until totalCells) {
            if (rng.random() < density) {
                alive[i] = true
                age[i] = 0
            }
        }

        // Evolve
        for (s in 0 until steps) {
            val nextAlive = BooleanArray(totalCells)
            val nextAge = IntArray(totalCells)

            for (y in 0 until gridSize) {
                for (x in 0 until gridSize) {
                    val idx = y * gridSize + x
                    var neighbors = 0
                    for (dy in -1..1) {
                        for (dx in -1..1) {
                            if (dx == 0 && dy == 0) continue
                            val nx = (x + dx + gridSize) % gridSize
                            val ny = (y + dy + gridSize) % gridSize
                            if (alive[ny * gridSize + nx]) neighbors++
                        }
                    }
                    val isAlive = alive[idx]
                    // Apply CA rule variant
                    val willLive = when (rule) {
                        "highlife" -> if (isAlive) neighbors == 2 || neighbors == 3 else neighbors == 3 || neighbors == 6
                        "maze" -> if (isAlive) neighbors in 1..5 else neighbors == 3
                        "daynight" -> if (isAlive) neighbors in setOf(3,4,6,7,8) else neighbors in setOf(3,6,7,8)
                        "seeds" -> if (isAlive) false else neighbors == 2
                        else /* life */ -> if (isAlive) neighbors == 2 || neighbors == 3 else neighbors == 3
                    }
                    nextAlive[idx] = willLive
                    nextAge[idx] = if (willLive) 0 else age[idx] + 1
                }
            }
            alive = nextAlive
            age = nextAge
        }

        // Render
        val cellW = w.toFloat() / gridSize
        val cellH = h.toFloat() / gridSize
        val pixels = IntArray(w * h)

        for (py in 0 until h) {
            val gy = (py / cellH).toInt().coerceAtMost(gridSize - 1)
            for (px in 0 until w) {
                val gx = (px / cellW).toInt().coerceAtMost(gridSize - 1)
                val idx = gy * gridSize + gx
                val cellAge = age[idx]

                pixels[py * w + px] = if (cellAge <= effectiveTrailLength) {
                    val t = cellAge.toFloat() / effectiveTrailLength
                    // Apply exposure: brightness boosted for young cells
                    val brightness = ((1f - t) * exposure).coerceIn(0f, 1f)
                    // Apply gamma curve for tone mapping
                    val mapped = brightness.toDouble().pow(gamma.toDouble()).toFloat()

                    when (colorMode) {
                        "heat" -> {
                            // Heat ramp through palette: emphasize bright end
                            val color = palette.lerpColor(mapped)
                            val alpha = (mapped * 255).toInt().coerceIn(0, 255)
                            Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
                        }
                        else /* palette */ -> {
                            val color = palette.lerpColor(t)
                            val alpha = (mapped * 255).toInt().coerceIn(0, 255)
                            Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
                        }
                    }
                } else {
                    Color.BLACK
                }
            }
        }

        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
        canvas.drawBitmap(bitmap, 0f, 0f, null)
    }
}
