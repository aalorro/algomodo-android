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
import kotlin.math.abs
import kotlin.math.atan2
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
        // Read ALL parameters
        val numSeeds = (params["seedCount"] as? Number)?.toInt() ?: 1
        val undercooling = (params["undercooling"] as? Number)?.toFloat() ?: 0.5f
        val undercoolingGradient = (params["undercoolingGradient"] as? Number)?.toFloat() ?: 0.0f
        val seedRadius = (params["seedRadius"] as? Number)?.toInt() ?: 3
        val symmetry = ((params["symmetry"] as? String) ?: "4").toIntOrNull() ?: 4
        val anisotropy = (params["anisotropy"] as? Number)?.toFloat() ?: 0.04f
        val interfaceWidth = (params["interfaceWidth"] as? Number)?.toFloat() ?: 0.01f
        val thermalNoise = (params["thermalNoise"] as? Number)?.toFloat() ?: 0.0f
        val warmupSteps = (params["warmupSteps"] as? Number)?.toInt() ?: 600
        val stepsPerFrame = (params["stepsPerFrame"] as? Number)?.toFloat() ?: 20f
        val colorMode = (params["colorMode"] as? String) ?: "phase"
        val gridSize = (params["gridSize"] as? Number)?.toInt() ?: 128

        val w = bitmap.width
        val h = bitmap.height

        // Interface width scaled to grid: controls how many layers of neighbors are candidates
        // Range 0.005..0.03 maps to 1..3 neighbor layers
        val interfaceLayers = (interfaceWidth * 100f).toInt().coerceIn(1, 3)

        val steps = warmupSteps + (time * stepsPerFrame).toInt()
        val totalCells = gridSize * gridSize
        val cx = gridSize / 2
        val cy = gridSize / 2

        // Initialize from seed
        val rng = SeededRNG(seed)
        // Crystal occupation, growth order, and grain ID for coloring
        val crystal = BooleanArray(totalCells)
        val growthOrder = IntArray(totalCells) { -1 }
        val grainId = IntArray(totalCells) { -1 }
        // Temperature field for "temperature" and "composite" color modes
        val temperature = FloatArray(totalCells) { 0f }
        var growthCount = 0

        // Place seed points with rotational symmetry, using seedRadius for circle seeds
        for (s in 0 until numSeeds) {
            val dist = if (numSeeds == 1) 0f else rng.range(gridSize * 0.05f, gridSize * 0.25f)
            val baseAngle = if (numSeeds == 1) 0f else rng.randomAngle()
            for (r in 0 until symmetry) {
                val angle = baseAngle + r * (2.0 * PI / symmetry).toFloat()
                val centerX = (cx + dist * cos(angle)).roundToInt().coerceIn(0, gridSize - 1)
                val centerY = (cy + dist * sin(angle)).roundToInt().coerceIn(0, gridSize - 1)
                // Place a circle of radius seedRadius around the seed point
                for (dy in -seedRadius..seedRadius) {
                    for (dx in -seedRadius..seedRadius) {
                        if (dx * dx + dy * dy <= seedRadius * seedRadius) {
                            val sx = (centerX + dx).coerceIn(0, gridSize - 1)
                            val sy = (centerY + dy).coerceIn(0, gridSize - 1)
                            val idx = sy * gridSize + sx
                            if (!crystal[idx]) {
                                crystal[idx] = true
                                growthOrder[idx] = growthCount++
                                grainId[idx] = s
                                temperature[idx] = 1f
                            }
                        }
                    }
                }
            }
        }

        // Evolve: each step, find boundary empty cells and grow with probability
        val dx4 = intArrayOf(0, 1, 0, -1)
        val dy4 = intArrayOf(-1, 0, 1, 0)

        for (step in 0 until steps) {
            // Collect boundary candidates based on interfaceLayers
            // Each candidate also stores the grain ID of the nearest crystal neighbor
            val candidateSet = LinkedHashMap<Int, Int>() // idx -> nearest grain ID
            for (y in 0 until gridSize) {
                for (x in 0 until gridSize) {
                    val idx = y * gridSize + x
                    if (crystal[idx]) continue
                    var nearestDist = Int.MAX_VALUE
                    var nearestGrain = -1
                    // Search in a square of radius interfaceLayers
                    for (dy in -interfaceLayers..interfaceLayers) {
                        for (dx in -interfaceLayers..interfaceLayers) {
                            val nx = x + dx
                            val ny = y + dy
                            if (nx in 0 until gridSize && ny in 0 until gridSize) {
                                val nIdx = ny * gridSize + nx
                                if (crystal[nIdx]) {
                                    val dist = abs(dx) + abs(dy) // Manhattan distance
                                    if (dist < nearestDist) {
                                        nearestDist = dist
                                        nearestGrain = grainId[nIdx]
                                    }
                                }
                            }
                        }
                    }
                    if (nearestDist <= interfaceLayers) {
                        candidateSet[idx] = nearestGrain
                    }
                }
            }

            // Grow candidates with symmetry
            for ((candIdx, candGrain) in candidateSet) {
                val candX = candIdx % gridSize
                val candY = candIdx / gridSize

                // Compute local undercooling with spatial gradient (left-to-right)
                val xFrac = candX.toFloat() / gridSize.toFloat() // 0 on left, 1 on right
                val localUndercooling = (undercooling + undercoolingGradient * (xFrac - 0.5f))
                    .coerceIn(0.01f, 0.99f)

                // Compute anisotropy factor: boost probability along symmetry axes
                val relX = (candX - cx).toFloat()
                val relY = (candY - cy).toFloat()
                val cellAngle = atan2(relY, relX)
                // Angular distance to the nearest symmetry axis
                val sectorAngle = (2.0 * PI / symmetry).toFloat()
                val angleInSector = ((cellAngle % sectorAngle) + sectorAngle) % sectorAngle
                val distToAxis = if (angleInSector <= sectorAngle / 2f) angleInSector else sectorAngle - angleInSector
                // anisotropy factor: 1.0 on axis, reduced off axis
                // cos^2 shape: maximum at axis, minimum at midpoint between axes
                val anisotropyFactor = 1f + anisotropy * 10f * cos(distToAxis * symmetry).coerceIn(-1f, 1f)

                // Thermal noise perturbation
                val noisePerturbation = if (thermalNoise > 0f) {
                    rng.range(-thermalNoise, thermalNoise)
                } else 0f

                // Final growth probability
                val growProb = (localUndercooling * anisotropyFactor + noisePerturbation)
                    .coerceIn(0f, 1f)

                if (rng.random() < growProb) {
                    // Apply rotational symmetry
                    val relXi = candX - cx
                    val relYi = candY - cy
                    for (r in 0 until symmetry) {
                        val angle = r * (2.0 * PI / symmetry)
                        val rotX = (relXi * cos(angle) - relYi * sin(angle)).roundToInt() + cx
                        val rotY = (relXi * sin(angle) + relYi * cos(angle)).roundToInt() + cy
                        if (rotX in 0 until gridSize && rotY in 0 until gridSize) {
                            val rIdx = rotY * gridSize + rotX
                            if (!crystal[rIdx]) {
                                crystal[rIdx] = true
                                growthOrder[rIdx] = growthCount++
                                grainId[rIdx] = candGrain
                                temperature[rIdx] = 1f
                            }
                        }
                    }
                }
            }

            // Decay temperature field each step (for temperature-based coloring)
            for (i in 0 until totalCells) {
                temperature[i] *= 0.98f
            }
            // Heat up cells that just grew (already set to 1f above)
        }

        // Render to bitmap
        val pixels = IntArray(w * h)
        val cellW = w.toFloat() / gridSize
        val cellH = h.toFloat() / gridSize
        val maxOrder = growthCount.coerceAtLeast(1)

        // Precompute distance-to-crystal for temperature mode background glow
        // Simple: use temperature array values for crystal cells, for non-crystal compute
        // a proximity-based heat value by checking distance to nearest crystal cell
        val heatField = FloatArray(totalCells)
        if (colorMode == "temperature" || colorMode == "composite") {
            // Diffuse: run a few blur passes on the temperature field including crystal contributions
            for (i in 0 until totalCells) {
                heatField[i] = if (crystal[i]) temperature[i].coerceIn(0f, 1f) else 0f
            }
            // Simple box-blur diffusion, 3 passes
            val temp = FloatArray(totalCells)
            for (pass in 0..2) {
                for (y in 0 until gridSize) {
                    for (x in 0 until gridSize) {
                        val idx = y * gridSize + x
                        var sum = heatField[idx]
                        var count = 1
                        for (d in 0..3) {
                            val nx = x + dx4[d]
                            val ny = y + dy4[d]
                            if (nx in 0 until gridSize && ny in 0 until gridSize) {
                                sum += heatField[ny * gridSize + nx]
                                count++
                            }
                        }
                        temp[idx] = sum / count
                    }
                }
                System.arraycopy(temp, 0, heatField, 0, totalCells)
            }
        }

        // Determine unique grain count for grain coloring
        val grainCount = if (colorMode == "grain") {
            var maxGrain = 0
            for (i in 0 until totalCells) {
                if (grainId[i] > maxGrain) maxGrain = grainId[i]
            }
            (maxGrain + 1).coerceAtLeast(1)
        } else 1

        for (py in 0 until h) {
            val gy = (py / cellH).toInt().coerceAtMost(gridSize - 1)
            for (px in 0 until w) {
                val gx = (px / cellW).toInt().coerceAtMost(gridSize - 1)
                val idx = gy * gridSize + gx
                pixels[py * w + px] = if (crystal[idx]) {
                    when (colorMode) {
                        "temperature" -> {
                            // Color by temperature: recently grown cells are hot
                            val t = temperature[idx].coerceIn(0f, 1f)
                            palette.lerpColor(t)
                        }
                        "composite" -> {
                            // Blend phase (growth order) and temperature
                            val phaseT = growthOrder[idx].toFloat() / maxOrder
                            val tempT = temperature[idx].coerceIn(0f, 1f)
                            val blended = phaseT * 0.6f + tempT * 0.4f
                            palette.lerpColor(blended.coerceIn(0f, 1f))
                        }
                        "grain" -> {
                            // Each grain gets a distinct palette hue band
                            val g = grainId[idx]
                            if (g >= 0) {
                                val grainBase = g.toFloat() / grainCount
                                val orderWithinGrain = growthOrder[idx].toFloat() / maxOrder
                                // Map to a narrow band within the palette for this grain
                                val bandWidth = 1f / grainCount
                                val t = grainBase + orderWithinGrain * bandWidth * 0.8f
                                palette.lerpColor(t.coerceIn(0f, 1f))
                            } else {
                                palette.lerpColor(0f)
                            }
                        }
                        else -> {
                            // "phase" mode: color by growth order
                            val t = growthOrder[idx].toFloat() / maxOrder
                            palette.lerpColor(t)
                        }
                    }
                } else {
                    // Background: for temperature/composite modes, show a faint thermal glow
                    if ((colorMode == "temperature" || colorMode == "composite") && heatField[idx] > 0.01f) {
                        val t = heatField[idx].coerceIn(0f, 1f)
                        // Faint background glow: interpolate from black toward the palette's warm end
                        val glowColor = palette.lerpColor(t)
                        val alpha = (t * 0.3f).coerceIn(0f, 1f)
                        val r = (Color.red(glowColor) * alpha).toInt()
                        val g = (Color.green(glowColor) * alpha).toInt()
                        val b = (Color.blue(glowColor) * alpha).toInt()
                        Color.rgb(r, g, b)
                    } else {
                        Color.BLACK
                    }
                }
            }
        }

        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
        canvas.drawBitmap(bitmap, 0f, 0f, null)
    }
}
