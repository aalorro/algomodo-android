package com.artmondo.algomodo.generators.cellular

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import com.artmondo.algomodo.core.rng.SeededRNG
import com.artmondo.algomodo.core.rng.SimplexNoise
import com.artmondo.algomodo.data.palettes.Palette
import com.artmondo.algomodo.generators.Generator
import com.artmondo.algomodo.generators.ParamGroup
import com.artmondo.algomodo.generators.Parameter
import com.artmondo.algomodo.generators.Quality
import kotlin.math.abs
import kotlin.math.pow

class ReactionDiffusionGenerator : Generator {

    override val id = "reaction-diffusion"
    override val family = "cellular"
    override val styleName = "Reaction Diffusion"
    override val definition = "Gray-Scott reaction-diffusion model producing organic dot and stripe patterns from two interacting chemicals."
    override val algorithmNotes = "Two chemicals A and B diffuse at different rates. A is consumed to produce B (feed), B decays (kill). The Laplacian is computed with a 3x3 kernel. Chemical B concentration is mapped to palette colors."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema = listOf(
        Parameter.SelectParam("Preset", "preset", ParamGroup.COMPOSITION, "Preset f/k combinations from the Gray-Scott parameter map — overrides Feed Rate and Kill Rate when not \"custom\"", listOf("custom", "spots", "stripes", "worms", "maze", "mitosis", "coral", "solitons", "spirals"), "spots"),
        Parameter.NumberParam("Feed Rate", "feedRate", ParamGroup.COMPOSITION, "Rate at which U is replenished (active only when preset = custom)", 0.01f, 0.08f, 0.001f, 0.035f),
        Parameter.NumberParam("Kill Rate", "killRate", ParamGroup.COMPOSITION, "Rate at which V is removed (active only when preset = custom)", 0.04f, 0.075f, 0.001f, 0.065f),
        Parameter.NumberParam("Spatial Variation", "spatialVariation", ParamGroup.COMPOSITION, "Noise-based spatial modulation of f/k — creates zones with different pattern types that compete at their borders, producing continuously evolving boundaries", 0f, 1.0f, 0.05f, 0.35f),
        Parameter.SelectParam("Init Mode", "initMode", ParamGroup.GEOMETRY, "patches: random circular seeds | noise: sparse scattered seeds across the whole field | center: single circular seed", listOf("patches", "noise", "center"), "patches"),
        Parameter.NumberParam("Diffusion U", "diffU", ParamGroup.TEXTURE, null, 0.1f, 1.0f, 0.05f, 0.8f),
        Parameter.NumberParam("Diffusion V", "diffV", ParamGroup.TEXTURE, null, 0.01f, 0.5f, 0.01f, 0.3f),
        Parameter.NumberParam("Steps / Frame", "stepsPerFrame", ParamGroup.FLOW_MOTION, "Simulation steps per animation frame — higher = faster evolution", 1f, 30f, 1f, 10f),
        Parameter.NumberParam("Iterations", "iterations", ParamGroup.FLOW_MOTION, "Steps run for static (non-animated) render", 100f, 3000f, 100f, 800f),
        Parameter.SelectParam("Color Mode", "colorMode", ParamGroup.COLOR, "palette: smooth V→palette | uv-mix: palette + reaction-front brightness boost | edge: gradient magnitude (boundary highlighting) | threshold: binary", listOf("palette", "uv-mix", "edge", "threshold"), "palette"),
        Parameter.NumberParam("Color Gamma", "colorGamma", ParamGroup.COLOR, "< 1: lifts dark regions (reveals low-concentration detail) | > 1: increases contrast, darkens background", 0.25f, 4.0f, 0.25f, 1.0f)
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "preset" to "spots",
        "feedRate" to 0.035f,
        "killRate" to 0.065f,
        "spatialVariation" to 0.35f,
        "initMode" to "patches",
        "diffU" to 0.8f,
        "diffV" to 0.3f,
        "stepsPerFrame" to 10f,
        "iterations" to 800f,
        "colorMode" to "palette",
        "colorGamma" to 1.0f
    )

    private fun presetFeedKill(preset: String): Pair<Float, Float>? = when (preset) {
        "spots"    -> 0.035f to 0.065f
        "stripes"  -> 0.035f to 0.060f
        "worms"    -> 0.054f to 0.063f
        "maze"     -> 0.029f to 0.057f
        "mitosis"  -> 0.028f to 0.062f
        "coral"    -> 0.062f to 0.061f
        "solitons" -> 0.030f to 0.060f
        "spirals"  -> 0.014f to 0.045f
        else       -> null
    }

    // ---- Simulation cache: avoids re-running all steps from scratch each frame ----
    private class SimState(
        val gw: Int,
        val gh: Int,
        val seed: Int,
        val baseFeed: Float,
        val baseKill: Float,
        val spatialVariation: Float,
        val dA: Float,
        val dB: Float,
        val initMode: String,
        var curA: FloatArray,
        var curB: FloatArray,
        var nxtA: FloatArray,
        var nxtB: FloatArray,
        val feedGrid: FloatArray,
        val killGrid: FloatArray,
        var stepsDone: Int
    )

    @Volatile private var cachedSim: SimState? = null
    @Volatile private var reusePixels: IntArray? = null
    @Volatile private var reuseGridColors: IntArray? = null

    override fun renderCanvas(
        canvas: Canvas,
        bitmap: Bitmap,
        params: Map<String, Any>,
        seed: Int,
        palette: Palette,
        quality: Quality,
        time: Float
    ) {
        val preset = (params["preset"] as? String) ?: "spots"
        val userFeed = (params["feedRate"] as? Number)?.toFloat() ?: 0.035f
        val userKill = (params["killRate"] as? Number)?.toFloat() ?: 0.065f
        val spatialVariation = (params["spatialVariation"] as? Number)?.toFloat() ?: 0.35f
        val initMode = (params["initMode"] as? String) ?: "patches"
        val dA = (params["diffU"] as? Number)?.toFloat() ?: 0.8f
        val dB = (params["diffV"] as? Number)?.toFloat() ?: 0.3f
        val stepsPerFrame = (params["stepsPerFrame"] as? Number)?.toInt() ?: 10
        val iterations = (params["iterations"] as? Number)?.toInt() ?: 800
        val colorMode = (params["colorMode"] as? String) ?: "palette"
        val colorGamma = (params["colorGamma"] as? Number)?.toFloat() ?: 1.0f

        val presetFK = presetFeedKill(preset)
        val baseFeed = presetFK?.first ?: userFeed
        val baseKill = presetFK?.second ?: userKill

        val w = bitmap.width
        val h = bitmap.height

        val scale = when (quality) {
            Quality.DRAFT -> 4
            Quality.BALANCED -> 2
            Quality.ULTRA -> 1
        }
        val gw = w / scale
        val gh = h / scale
        val totalCells = gw * gh

        val totalSteps = if (time <= 0f) iterations
        else (time * stepsPerFrame * 10).toInt()

        // ---- Get or create simulation state (cache across frames) ----
        val sim = cachedSim?.takeIf {
            it.gw == gw && it.gh == gh && it.seed == seed &&
                it.baseFeed == baseFeed && it.baseKill == baseKill &&
                it.spatialVariation == spatialVariation &&
                it.dA == dA && it.dB == dB &&
                it.initMode == initMode &&
                it.stepsDone <= totalSteps
        } ?: run {
            // Build spatial variation grids
            val noise = SimplexNoise(seed)
            val noiseFreq = 3.0f
            val feedGrid = FloatArray(totalCells)
            val killGrid = FloatArray(totalCells)
            for (y in 0 until gh) {
                for (x in 0 until gw) {
                    val idx = y * gw + x
                    val nx = x.toFloat() / gw * noiseFreq
                    val ny = y.toFloat() / gh * noiseFreq
                    val nv = noise.noise2D(nx, ny) * spatialVariation
                    feedGrid[idx] = (baseFeed + nv * 0.02f).coerceIn(0.01f, 0.08f)
                    killGrid[idx] = (baseKill + nv * 0.01f).coerceIn(0.04f, 0.075f)
                }
            }

            // Initialize chemical concentrations
            val rng = SeededRNG(seed)
            val a = FloatArray(totalCells) { 1f }
            val b = FloatArray(totalCells) { 0f }

            when (initMode) {
                "center" -> {
                    val cx = gw / 2
                    val cy = gh / 2
                    val radius = (gw.coerceAtMost(gh) / 10).coerceAtLeast(3)
                    for (dy in -radius..radius) {
                        for (dx in -radius..radius) {
                            if (dx * dx + dy * dy <= radius * radius) {
                                val nx = (cx + dx + gw) % gw
                                val ny = (cy + dy + gh) % gh
                                b[ny * gw + nx] = 1f
                            }
                        }
                    }
                }
                "noise" -> {
                    for (idx in 0 until totalCells) {
                        if (rng.random() < 0.01f) b[idx] = 1f
                    }
                }
                else -> {
                    val numSeeds = 5 + rng.integer(0, 5)
                    for (s in 0 until numSeeds) {
                        val cx = rng.integer(gw / 4, 3 * gw / 4)
                        val cy = rng.integer(gh / 4, 3 * gh / 4)
                        val radius = rng.integer(2, gw / 15.coerceAtLeast(3))
                        for (dy in -radius..radius) {
                            for (dx in -radius..radius) {
                                if (dx * dx + dy * dy <= radius * radius) {
                                    val nx = (cx + dx + gw) % gw
                                    val ny = (cy + dy + gh) % gh
                                    b[ny * gw + nx] = 1f
                                }
                            }
                        }
                    }
                }
            }

            SimState(
                gw, gh, seed, baseFeed, baseKill, spatialVariation, dA, dB, initMode,
                a, b, FloatArray(totalCells), FloatArray(totalCells),
                feedGrid, killGrid, 0
            )
        }
        cachedSim = sim

        // ---- Evolve simulation: only advance from stepsDone to totalSteps ----
        var curA = sim.curA
        var curB = sim.curB
        var nxtA = sim.nxtA
        var nxtB = sim.nxtB
        val feedGrid = sim.feedGrid
        val killGrid = sim.killGrid
        val gwm1 = gw - 1
        val ghm1 = gh - 1

        for (step in sim.stepsDone until totalSteps) {
            for (y in 0 until gh) {
                val rowOff = y * gw
                val upOff = if (y > 0) (y - 1) * gw else ghm1 * gw
                val downOff = if (y < ghm1) (y + 1) * gw else 0

                for (x in 0 until gw) {
                    val left = if (x > 0) x - 1 else gwm1
                    val right = if (x < gwm1) x + 1 else 0
                    val idx = rowOff + x

                    val aVal = curA[idx]
                    val bVal = curB[idx]

                    // Inlined 5-point Laplacian (no function call, no modulo)
                    val lapA = curA[upOff + x] + curA[downOff + x] + curA[rowOff + left] + curA[rowOff + right] - 4f * aVal
                    val lapB = curB[upOff + x] + curB[downOff + x] + curB[rowOff + left] + curB[rowOff + right] - 4f * bVal

                    val f = feedGrid[idx]
                    val k = killGrid[idx]
                    val abb = aVal * bVal * bVal

                    nxtA[idx] = (aVal + dA * lapA - abb + f * (1f - aVal)).coerceIn(0f, 1f)
                    nxtB[idx] = (bVal + dB * lapB + abb - (k + f) * bVal).coerceIn(0f, 1f)
                }
            }
            // Swap buffers
            val tmpA = curA; curA = nxtA; nxtA = tmpA
            val tmpB = curB; curB = nxtB; nxtB = tmpB
        }
        sim.curA = curA
        sim.curB = curB
        sim.nxtA = nxtA
        sim.nxtB = nxtB
        sim.stepsDone = totalSteps

        // ---- Phase 1: Compute color per grid cell ----
        val gridColors: IntArray
        val rgc = reuseGridColors
        if (rgc != null && rgc.size >= totalCells) {
            gridColors = rgc
        } else {
            gridColors = IntArray(totalCells)
            reuseGridColors = gridColors
        }

        // Pre-compute gamma + palette LUT (eliminates per-cell pow() and lerpColor())
        val palLutSize = 256
        val palLutMax = palLutSize - 1
        val gammaLut = IntArray(palLutSize) { i ->
            val t = (i.toFloat() / palLutMax).pow(colorGamma).coerceIn(0f, 1f)
            palette.lerpColor(t)
        }

        when (colorMode) {
            "uv-mix" -> {
                for (i in 0 until totalCells) {
                    val base = gammaLut[(curB[i] * palLutMax).toInt().coerceIn(0, palLutMax)]
                    val front = (curA[i] * curB[i] * 4f).coerceIn(0f, 1f)
                    val boost = (front * 80f).toInt()
                    gridColors[i] = Color.rgb(
                        (Color.red(base) + boost).coerceAtMost(255),
                        (Color.green(base) + boost).coerceAtMost(255),
                        (Color.blue(base) + boost).coerceAtMost(255)
                    )
                }
            }
            "edge" -> {
                // Compute Laplacian of B per grid cell (not per pixel)
                for (y in 0 until gh) {
                    val rowOff = y * gw
                    val upOff = if (y > 0) (y - 1) * gw else ghm1 * gw
                    val downOff = if (y < ghm1) (y + 1) * gw else 0
                    for (x in 0 until gw) {
                        val left = if (x > 0) x - 1 else gwm1
                        val right = if (x < gwm1) x + 1 else 0
                        val lapB = abs(curB[upOff + x] + curB[downOff + x] + curB[rowOff + left] + curB[rowOff + right] - 4f * curB[rowOff + x])
                        val edgeVal = (lapB * 5f).coerceIn(0f, 1f)
                        gridColors[rowOff + x] = gammaLut[(edgeVal * palLutMax).toInt().coerceIn(0, palLutMax)]
                    }
                }
            }
            "threshold" -> {
                val colorHigh = palette.colorAt(4)
                val colorLow = palette.colorAt(0)
                // Pre-compute threshold in un-gamma'd space
                val threshB = if (colorGamma != 1.0f) 0.25f.pow(1f / colorGamma) else 0.25f
                for (i in 0 until totalCells) {
                    gridColors[i] = if (curB[i] > threshB) colorHigh else colorLow
                }
            }
            else /* palette */ -> {
                for (i in 0 until totalCells) {
                    gridColors[i] = gammaLut[(curB[i] * palLutMax).toInt().coerceIn(0, palLutMax)]
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

        val xMap = IntArray(w) { (it / scale).coerceAtMost(gwm1) }
        for (py in 0 until h) {
            val gy = (py / scale).coerceAtMost(ghm1)
            val gridRow = gy * gw
            val pixRow = py * w
            for (px in 0 until w) {
                pixels[pixRow + px] = gridColors[gridRow + xMap[px]]
            }
        }

        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
        canvas.drawBitmap(bitmap, 0f, 0f, null)
    }

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float {
        val scale = when (quality) {
            Quality.DRAFT -> 4; Quality.BALANCED -> 2; Quality.ULTRA -> 1
        }
        val iterations = (params["iterations"] as? Number)?.toInt() ?: 800
        return (iterations / scale.toFloat() / 500f).coerceIn(0.1f, 1f)
    }
}
