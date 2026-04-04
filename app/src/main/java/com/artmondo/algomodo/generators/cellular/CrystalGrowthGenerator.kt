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

    // ---- Simulation cache: avoids re-running all 600 steps from scratch each frame ----
    private class SimState(
        val gridSize: Int,
        val seed: Int,
        val numSeeds: Int,
        val undercooling: Float,
        val undercoolingGradient: Float,
        val seedRadius: Int,
        val symmetry: Int,
        val anisotropy: Float,
        val interfaceLayers: Int,
        val thermalNoise: Float,
        val crystal: BooleanArray,
        val growthOrder: IntArray,
        val grainId: IntArray,
        val temperature: FloatArray,
        val rng: SeededRNG,
        var growthCount: Int,
        var stepsDone: Int
    )

    @Volatile private var cachedSim: SimState? = null
    @Volatile private var reusePixels: IntArray? = null
    @Volatile private var reuseGridColors: IntArray? = null
    @Volatile private var reuseCandIdx: IntArray? = null
    @Volatile private var reuseCandGrain: IntArray? = null

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
        val interfaceLayers = (interfaceWidth * 100f).toInt().coerceIn(1, 3)
        // Static: use warmupSteps. Animation: grow from scratch so user watches crystal form.
        val targetSteps = if (time <= 0f) warmupSteps
        else (time * stepsPerFrame).toInt().coerceAtLeast(1)
        val totalCells = gridSize * gridSize
        val cx = gridSize / 2
        val cy = gridSize / 2
        val lastIdx = gridSize - 1

        // ---- Get or create simulation state (cache across frames) ----
        val sim = cachedSim?.takeIf {
            it.gridSize == gridSize && it.seed == seed &&
                it.numSeeds == numSeeds &&
                it.undercooling == undercooling &&
                it.undercoolingGradient == undercoolingGradient &&
                it.seedRadius == seedRadius &&
                it.symmetry == symmetry &&
                it.anisotropy == anisotropy &&
                it.interfaceLayers == interfaceLayers &&
                it.thermalNoise == thermalNoise &&
                it.stepsDone <= targetSteps
        } ?: run {
            val rng = SeededRNG(seed)
            val crystal = BooleanArray(totalCells)
            val growthOrder = IntArray(totalCells) { -1 }
            val grainId = IntArray(totalCells) { -1 }
            val temperature = FloatArray(totalCells)
            var growthCount = 0

            // Place seed points with rotational symmetry
            for (s in 0 until numSeeds) {
                val dist = if (numSeeds == 1) 0f else rng.range(gridSize * 0.05f, gridSize * 0.25f)
                val baseAngle = if (numSeeds == 1) 0f else rng.randomAngle()
                for (r in 0 until symmetry) {
                    val angle = baseAngle + r * (2.0 * PI / symmetry).toFloat()
                    val centerX = (cx + dist * cos(angle)).roundToInt().coerceIn(0, lastIdx)
                    val centerY = (cy + dist * sin(angle)).roundToInt().coerceIn(0, lastIdx)
                    for (dy in -seedRadius..seedRadius) {
                        for (dx in -seedRadius..seedRadius) {
                            if (dx * dx + dy * dy <= seedRadius * seedRadius) {
                                val sx = (centerX + dx).coerceIn(0, lastIdx)
                                val sy = (centerY + dy).coerceIn(0, lastIdx)
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

            SimState(
                gridSize, seed, numSeeds, undercooling, undercoolingGradient,
                seedRadius, symmetry, anisotropy, interfaceLayers, thermalNoise,
                crystal, growthOrder, grainId, temperature, rng,
                growthCount, 0
            )
        }
        cachedSim = sim

        val crystal = sim.crystal
        val growthOrder = sim.growthOrder
        val grainId = sim.grainId
        val temperature = sim.temperature
        val rng = sim.rng
        var growthCount = sim.growthCount

        // Pre-compute rotation tables for symmetry (eliminates per-rotation trig)
        val rotCos = DoubleArray(symmetry) { cos(it * 2.0 * PI / symmetry) }
        val rotSin = DoubleArray(symmetry) { sin(it * 2.0 * PI / symmetry) }

        // Flat candidate arrays (replace LinkedHashMap — no boxing, no hashing)
        val rci = reuseCandIdx
        val candIdx: IntArray
        if (rci != null && rci.size >= totalCells) {
            candIdx = rci
        } else {
            candIdx = IntArray(totalCells)
            reuseCandIdx = candIdx
        }
        val rcg = reuseCandGrain
        val candGrain: IntArray
        if (rcg != null && rcg.size >= totalCells) {
            candGrain = rcg
        } else {
            candGrain = IntArray(totalCells)
            reuseCandGrain = candGrain
        }

        val invGridSize = 1f / gridSize

        // ---- Advance simulation from stepsDone to targetSteps ----
        for (step in sim.stepsDone until targetSteps) {
            // Collect boundary candidates using flat arrays
            var candCount = 0
            for (y in 0 until gridSize) {
                val rowOff = y * gridSize
                for (x in 0 until gridSize) {
                    val idx = rowOff + x
                    if (crystal[idx]) continue

                    var nearestDist = Int.MAX_VALUE
                    var nearestGrain = -1
                    for (dy in -interfaceLayers..interfaceLayers) {
                        val ny = y + dy
                        if (ny < 0 || ny > lastIdx) continue
                        val nRowOff = ny * gridSize
                        for (dx in -interfaceLayers..interfaceLayers) {
                            val nx = x + dx
                            if (nx < 0 || nx > lastIdx) continue
                            val nIdx = nRowOff + nx
                            if (crystal[nIdx]) {
                                val dist = abs(dx) + abs(dy)
                                if (dist < nearestDist) {
                                    nearestDist = dist
                                    nearestGrain = grainId[nIdx]
                                }
                            }
                        }
                    }
                    if (nearestDist <= interfaceLayers) {
                        candIdx[candCount] = idx
                        candGrain[candCount] = nearestGrain
                        candCount++
                    }
                }
            }

            // Grow candidates
            val aniso10 = anisotropy * 10f
            for (c in 0 until candCount) {
                val cIdx = candIdx[c]
                val candX = cIdx % gridSize
                val candY = cIdx / gridSize

                // Spatial gradient
                val localUndercooling = (undercooling + undercoolingGradient * (candX * invGridSize - 0.5f))
                    .coerceIn(0.01f, 0.99f)

                // Simplified anisotropy: cos(angle * symmetry) is equivalent to the sector computation
                val relX = (candX - cx).toDouble()
                val relY = (candY - cy).toDouble()
                val cellAngle = atan2(relY, relX)
                val anisotropyFactor = 1f + aniso10 * cos(cellAngle * symmetry).toFloat()

                val noisePerturbation = if (thermalNoise > 0f) {
                    rng.range(-thermalNoise, thermalNoise)
                } else 0f

                val growProb = (localUndercooling * anisotropyFactor + noisePerturbation)
                    .coerceIn(0f, 1f)

                if (rng.random() < growProb) {
                    val cGrain = candGrain[c]
                    val relXi = candX - cx
                    val relYi = candY - cy
                    for (r in 0 until symmetry) {
                        val rotX = (relXi * rotCos[r] - relYi * rotSin[r]).roundToInt() + cx
                        val rotY = (relXi * rotSin[r] + relYi * rotCos[r]).roundToInt() + cy
                        if (rotX in 0..lastIdx && rotY in 0..lastIdx) {
                            val rIdx = rotY * gridSize + rotX
                            if (!crystal[rIdx]) {
                                crystal[rIdx] = true
                                growthOrder[rIdx] = growthCount++
                                grainId[rIdx] = cGrain
                                temperature[rIdx] = 1f
                            }
                        }
                    }
                }
            }

            // Decay temperature field
            for (i in 0 until totalCells) {
                temperature[i] *= 0.98f
            }
        }
        sim.growthCount = growthCount
        sim.stepsDone = targetSteps

        // ---- Phase 1: Compute color per grid cell ----
        val gridColors: IntArray
        val rgc = reuseGridColors
        if (rgc != null && rgc.size >= totalCells) {
            gridColors = rgc
        } else {
            gridColors = IntArray(totalCells)
            reuseGridColors = gridColors
        }

        val maxOrder = growthCount.coerceAtLeast(1)
        val palLutSize = 256
        val palLutMax = palLutSize - 1
        val palLut = IntArray(palLutSize) { palette.lerpColor(it.toFloat() / palLutMax) }

        // Heat field for temperature/composite background glow
        val needHeat = colorMode == "temperature" || colorMode == "composite"
        val heatField: FloatArray?
        if (needHeat) {
            heatField = FloatArray(totalCells)
            for (i in 0 until totalCells) {
                heatField[i] = if (crystal[i]) temperature[i].coerceIn(0f, 1f) else 0f
            }
            val temp = FloatArray(totalCells)
            val dx4 = intArrayOf(0, 1, 0, -1)
            val dy4 = intArrayOf(-1, 0, 1, 0)
            for (pass in 0..2) {
                for (y in 0 until gridSize) {
                    val rowOff = y * gridSize
                    for (x in 0 until gridSize) {
                        val idx = rowOff + x
                        var sum = heatField[idx]
                        var count = 1
                        for (d in 0..3) {
                            val nx = x + dx4[d]
                            val ny = y + dy4[d]
                            if (nx in 0..lastIdx && ny in 0..lastIdx) {
                                sum += heatField[ny * gridSize + nx]
                                count++
                            }
                        }
                        temp[idx] = sum / count
                    }
                }
                System.arraycopy(temp, 0, heatField, 0, totalCells)
            }
        } else {
            heatField = null
        }

        // Grain count for grain coloring
        val grainCount = if (colorMode == "grain") {
            var maxGrain = 0
            for (i in 0 until totalCells) {
                if (grainId[i] > maxGrain) maxGrain = grainId[i]
            }
            (maxGrain + 1).coerceAtLeast(1)
        } else 1

        val invMaxOrder = 1f / maxOrder
        val invGrainCount = 1f / grainCount

        for (i in 0 until totalCells) {
            if (crystal[i]) {
                gridColors[i] = when (colorMode) {
                    "temperature" -> {
                        palLut[(temperature[i].coerceIn(0f, 1f) * palLutMax).toInt().coerceIn(0, palLutMax)]
                    }
                    "composite" -> {
                        val phaseT = growthOrder[i] * invMaxOrder
                        val tempT = temperature[i].coerceIn(0f, 1f)
                        val blended = (phaseT * 0.6f + tempT * 0.4f).coerceIn(0f, 1f)
                        palLut[(blended * palLutMax).toInt().coerceIn(0, palLutMax)]
                    }
                    "grain" -> {
                        val g = grainId[i]
                        if (g >= 0) {
                            val grainBase = g * invGrainCount
                            val orderWithin = growthOrder[i] * invMaxOrder
                            val bandWidth = invGrainCount
                            val t = (grainBase + orderWithin * bandWidth * 0.8f).coerceIn(0f, 1f)
                            palLut[(t * palLutMax).toInt().coerceIn(0, palLutMax)]
                        } else {
                            palLut[0]
                        }
                    }
                    else /* phase */ -> {
                        val t = growthOrder[i] * invMaxOrder
                        palLut[(t * palLutMax).toInt().coerceIn(0, palLutMax)]
                    }
                }
            } else if (needHeat && heatField!![i] > 0.01f) {
                val t = heatField[i].coerceIn(0f, 1f)
                val glowColor = palLut[(t * palLutMax).toInt().coerceIn(0, palLutMax)]
                val alpha = (t * 0.3f).coerceIn(0f, 1f)
                gridColors[i] = Color.rgb(
                    (Color.red(glowColor) * alpha).toInt(),
                    (Color.green(glowColor) * alpha).toInt(),
                    (Color.blue(glowColor) * alpha).toInt()
                )
            } else {
                gridColors[i] = Color.BLACK
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

        val xMap = IntArray(w) { (it * gridSize / w).coerceAtMost(lastIdx) }
        for (py in 0 until h) {
            val gy = (py * gridSize / h).coerceAtMost(lastIdx)
            val gridRow = gy * gridSize
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
        val warmupSteps = (params["warmupSteps"] as? Number)?.toInt() ?: 600
        return (gridSize * gridSize.toLong() * warmupSteps / 100_000_000f).coerceIn(0.1f, 1f)
    }
}
