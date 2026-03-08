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
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

class CrystalGrowthGenerator : Generator {

    override val id = "cellular-crystal-growth"
    override val family = "cellular"
    override val styleName = "Crystal Growth"
    override val definition = "DLA-like crystal growth simulation producing symmetric, dendritic snowflake-like structures."
    override val algorithmNotes = "Seed points are placed with rotational symmetry. In each step, boundary cells are candidates for growth. Growth probability is biased by neighbor count and a symmetry constraint. The result mimics diffusion-limited aggregation with imposed rotational symmetry."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema = listOf(
        Parameter.NumberParam("Grid Size", "gridSize", ParamGroup.COMPOSITION, null, 32f, 256f, 16f, 128f),
        Parameter.NumberParam("Seed Count", "seedCount", ParamGroup.COMPOSITION, "1 = single central nucleus | 2-12 = polycrystalline: nuclei at random positions with random orientations, producing grain boundaries where they collide", 1f, 12f, 1f, 1f),
        Parameter.NumberParam("Undercooling", "undercooling", ParamGroup.COMPOSITION, "Initial temperature below melting point — higher = faster, more branched growth", 0.1f, 0.9f, 0.05f, 0.5f),
        Parameter.NumberParam("Undercooling Gradient", "undercoolingGradient", ParamGroup.COMPOSITION, "Left-right gradient of initial undercooling — creates asymmetric dendrites that grow faster toward the colder side", 0f, 0.8f, 0.05f, 0.0f),
        Parameter.NumberParam("Seed Radius", "seedRadius", ParamGroup.COMPOSITION, "Radius of each solid nucleus placed before growth begins", 1f, 10f, 1f, 3f),
        Parameter.SelectParam("Crystal Symmetry", "symmetry", ParamGroup.GEOMETRY, "4: cubic/square (4 arms) | 6: hexagonal snowflake (6 arms) | 3: trigonal (3 arms) | 8: octagonal", listOf("4", "6", "3", "8"), "4"),
        Parameter.NumberParam("Anisotropy", "anisotropy", ParamGroup.GEOMETRY, "Strength of orientational preference — 0 = circular blob, 0.04+ = clear dendrite arms; higher values give sharper needles", 0.0f, 0.2f, 0.01f, 0.04f),
        Parameter.NumberParam("Interface Width", "interfaceWidth", ParamGroup.TEXTURE, "Thickness of the solid-liquid interface and overall growth speed — smaller = sharper tips, larger = blunter", 0.005f, 0.03f, 0.002f, 0.01f),
        Parameter.NumberParam("Thermal Noise", "thermalNoise", ParamGroup.TEXTURE, "Random thermal perturbation added to the temperature field each step — breaks dendrite symmetry for more natural, organic growth", 0f, 0.05f, 0.002f, 0.0f),
        Parameter.NumberParam("Growth Steps", "warmupSteps", ParamGroup.COMPOSITION, "Simulation steps before static render — more = larger crystal", 200f, 600f, 50f, 600f),
        Parameter.NumberParam("Steps / Frame", "stepsPerFrame", ParamGroup.FLOW_MOTION, "Steps per animation frame — watch the crystal grow in real time", 5f, 60f, 5f, 20f),
        Parameter.SelectParam("Color Mode", "colorMode", ParamGroup.COLOR, "phase: solid fraction | temperature: thermal halo around growing tips | composite: blend of both | grain: each crystal grain a distinct palette hue (multi-seed mode)", listOf("phase", "temperature", "composite", "grain"), "phase")
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "gridSize" to 128f,
        "seedCount" to 1f,
        "undercooling" to 0.5f,
        "undercoolingGradient" to 0.0f,
        "seedRadius" to 3f,
        "symmetry" to "4",
        "anisotropy" to 0.04f,
        "interfaceWidth" to 0.01f,
        "thermalNoise" to 0.0f,
        "warmupSteps" to 600f,
        "stepsPerFrame" to 20f,
        "colorMode" to "phase"
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
        val numSeeds = (params["seedCount"] as? Number)?.toInt() ?: 1
        val undercooling = (params["undercooling"] as? Number)?.toFloat() ?: 0.5f
        val symmetry = ((params["symmetry"] as? String) ?: "4").toIntOrNull() ?: 4
        val stepsPerFrame = (params["stepsPerFrame"] as? Number)?.toFloat() ?: 20f

        val w = bitmap.width
        val h = bitmap.height

        val gridSize = (params["gridSize"] as? Number)?.toInt() ?: 128

        val steps = (time * stepsPerFrame).toInt()
        val totalCells = gridSize * gridSize
        val cx = gridSize / 2
        val cy = gridSize / 2

        // Initialize from seed
        val rng = SeededRNG(seed)
        // Crystal occupation and growth order for coloring
        val crystal = BooleanArray(totalCells)
        val growthOrder = IntArray(totalCells) { -1 }
        var growthCount = 0

        // Place seed points with rotational symmetry
        for (s in 0 until numSeeds) {
            val dist = rng.range(0f, gridSize * 0.05f)
            val baseAngle = rng.randomAngle()
            for (r in 0 until symmetry) {
                val angle = baseAngle + r * (2.0 * PI / symmetry).toFloat()
                val sx = (cx + dist * cos(angle)).roundToInt().coerceIn(0, gridSize - 1)
                val sy = (cy + dist * sin(angle)).roundToInt().coerceIn(0, gridSize - 1)
                val idx = sy * gridSize + sx
                if (!crystal[idx]) {
                    crystal[idx] = true
                    growthOrder[idx] = growthCount++
                }
            }
        }

        // Evolve: each step, find boundary empty cells and grow with probability
        val dx4 = intArrayOf(0, 1, 0, -1)
        val dy4 = intArrayOf(-1, 0, 1, 0)

        for (step in 0 until steps) {
            // Collect boundary candidates: empty cells adjacent to crystal cells
            val candidates = mutableListOf<Int>()
            for (y in 0 until gridSize) {
                for (x in 0 until gridSize) {
                    val idx = y * gridSize + x
                    if (crystal[idx]) continue
                    var adjCrystal = false
                    for (d in 0..3) {
                        val nx = x + dx4[d]
                        val ny = y + dy4[d]
                        if (nx in 0 until gridSize && ny in 0 until gridSize && crystal[ny * gridSize + nx]) {
                            adjCrystal = true
                            break
                        }
                    }
                    if (adjCrystal) candidates.add(idx)
                }
            }

            // Grow candidates with symmetry
            for (candIdx in candidates) {
                if (rng.random() < undercooling) {
                    val candX = candIdx % gridSize
                    val candY = candIdx / gridSize
                    // Apply rotational symmetry
                    val relX = candX - cx
                    val relY = candY - cy
                    for (r in 0 until symmetry) {
                        val angle = r * (2.0 * PI / symmetry)
                        val rotX = (relX * cos(angle) - relY * sin(angle)).roundToInt() + cx
                        val rotY = (relX * sin(angle) + relY * cos(angle)).roundToInt() + cy
                        if (rotX in 0 until gridSize && rotY in 0 until gridSize) {
                            val rIdx = rotY * gridSize + rotX
                            if (!crystal[rIdx]) {
                                crystal[rIdx] = true
                                growthOrder[rIdx] = growthCount++
                            }
                        }
                    }
                }
            }
        }

        // Render to bitmap
        val pixels = IntArray(w * h)
        val cellW = w.toFloat() / gridSize
        val cellH = h.toFloat() / gridSize
        val maxOrder = growthCount.coerceAtLeast(1)

        for (py in 0 until h) {
            val gy = (py / cellH).toInt().coerceAtMost(gridSize - 1)
            for (px in 0 until w) {
                val gx = (px / cellW).toInt().coerceAtMost(gridSize - 1)
                val idx = gy * gridSize + gx
                pixels[py * w + px] = if (crystal[idx]) {
                    val t = growthOrder[idx].toFloat() / maxOrder
                    palette.lerpColor(t)
                } else {
                    Color.BLACK
                }
            }
        }

        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
        canvas.drawBitmap(bitmap, 0f, 0f, null)
    }
}
