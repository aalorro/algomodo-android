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
import kotlin.math.sin

/**
 * Reaction-diffusion Turing patterns.
 *
 * Implements two classical models:
 * - **Schnakenberg**: activator-inhibitor with source terms a, b, reaction rate gamma,
 *   and diffusion coefficients Du, Dv. Produces spots, stripes, and labyrinths.
 * - **Gray-Scott**: substrate U fed at rate F, catalyst V killed at rate k,
 *   canonical diffusion Du=0.16, Dv=0.08. Produces solitons, worms, coral, mitosis.
 *
 * The Laplacian is computed with either a 5-point or 9-point isotropic stencil.
 * Parameters can vary spatially (paramGradient) or temporally (paramDrift) for
 * morphological transitions.
 */
class TuringPatternsGenerator : Generator {

    override val id = "cellular-turing-patterns"
    override val family = "cellular"
    override val styleName = "Turing Patterns"
    override val definition = "Reaction-diffusion Turing patterns: activator-inhibitor systems produce spots, stripes, labyrinths, and coral-like growths."
    override val algorithmNotes =
        "Schnakenberg: du/dt = Du*lap(u) + gamma*(a - u + u²v), dv/dt = Dv*lap(v) + gamma*(b - u²v). " +
        "Gray-Scott: du/dt = Du*lap(u) - u*v² + F*(1-u), dv/dt = Dv*lap(v) + u*v² - (F+k)*v. " +
        "Both use forward Euler integration with configurable Laplacian stencil."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema = listOf(
        Parameter.NumberParam("Grid Size", "gridSize", ParamGroup.COMPOSITION, null, 32f, 256f, 16f, 128f),
        Parameter.SelectParam("Model", "model", ParamGroup.COMPOSITION, "schnakenberg: activator-inhibitor, produces spots/stripes/labyrinths | gray-scott: substrate-catalyst, produces solitons, worms, annular rings, coral-like growths", listOf("schnakenberg", "gray-scott"), "schnakenberg"),
        Parameter.NumberParam("Source a", "a", ParamGroup.COMPOSITION, "Schnakenberg: activator source — low a → stripes, higher a → spots", 0.01f, 0.5f, 0.01f, 0.1f),
        Parameter.NumberParam("Source b", "b", ParamGroup.COMPOSITION, "Schnakenberg: inhibitor source — higher b relative to a pushes toward isolated spots", 0.3f, 1.8f, 0.05f, 0.9f),
        Parameter.NumberParam("Reaction Rate", "gamma", ParamGroup.COMPOSITION, "Schnakenberg: overall reaction speed — higher produces finer, denser patterns", 20f, 300f, 10f, 100f),
        Parameter.NumberParam("Feed Rate F", "F", ParamGroup.COMPOSITION, "Gray-Scott: feed rate of substrate U — F≈0.037 → solitons, F≈0.035 → worms, F≈0.025 → spots", 0.010f, 0.080f, 0.001f, 0.037f),
        Parameter.NumberParam("Kill Rate k", "k", ParamGroup.COMPOSITION, "Gray-Scott: kill rate of catalyst V — k≈0.060 → solitons, k≈0.065 → worms, k≈0.050 → spots", 0.040f, 0.075f, 0.001f, 0.060f),
        Parameter.NumberParam("Diffusion u", "Du", ParamGroup.TEXTURE, "Activator diffusion for Schnakenberg — keep << Dv (target >=10x ratio). Gray-Scott uses a fixed canonical Du=0.16 regardless of this value.", 0.005f, 0.3f, 0.005f, 0.02f),
        Parameter.NumberParam("Diffusion v", "Dv", ParamGroup.TEXTURE, "Inhibitor diffusion for Schnakenberg — Dv/Du ratio drives the Turing instability. Gray-Scott uses a fixed canonical Dv=0.08 regardless of this value.", 0.05f, 2.0f, 0.05f, 0.5f),
        Parameter.SelectParam("Laplacian Stencil", "stencil", ParamGroup.TEXTURE, "9-point isotropic (cardinal 0.2, diagonal 0.05): rounder spots, less grid artefacts | 5-point (standard): slightly faster", listOf("9-point", "5-point"), "9-point"),
        Parameter.NumberParam("Param Gradient", "paramGradient", ParamGroup.COMPOSITION, "Linearly varies the reaction parameter across the canvas (a for Schnakenberg, k for Gray-Scott) — creates a smooth morphological transition from spots to stripes or solitons to waves", 0f, 0.8f, 0.05f, 0.0f),
        Parameter.NumberParam("Param Drift", "paramDrift", ParamGroup.FLOW_MOTION, "Slowly oscillates the reaction parameter during animation — set above 0 to see the pattern continuously morph between morphologies. 0 = static equilibrium.", 0f, 0.5f, 0.05f, 0.0f),
        Parameter.NumberParam("Warm-up Steps", "warmupSteps", ParamGroup.COMPOSITION, "Steps before the static render — Schnakenberg: 300-600; Gray-Scott needs 800-2000 for developed patterns", 50f, 2000f, 50f, 400f),
        Parameter.NumberParam("Steps / Frame", "stepsPerFrame", ParamGroup.FLOW_MOTION, "Simulation steps advanced per animation frame", 1f, 20f, 1f, 5f),
        Parameter.SelectParam("Color Mode", "colorMode", ParamGroup.COLOR, "palette: smooth gradient across activator/catalyst concentration | binary: hard threshold for two-tone animal-coat look", listOf("palette", "binary"), "palette")
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "gridSize" to 128f,
        "model" to "schnakenberg",
        "a" to 0.1f,
        "b" to 0.9f,
        "gamma" to 100f,
        "F" to 0.037f,
        "k" to 0.060f,
        "Du" to 0.02f,
        "Dv" to 0.5f,
        "stencil" to "9-point",
        "paramGradient" to 0.0f,
        "paramDrift" to 0.15f,
        "warmupSteps" to 400f,
        "stepsPerFrame" to 5f,
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
        val model = (params["model"] as? String) ?: "schnakenberg"
        val paramA = (params["a"] as? Number)?.toFloat() ?: 0.1f
        val paramB = (params["b"] as? Number)?.toFloat() ?: 0.9f
        val gamma = (params["gamma"] as? Number)?.toFloat() ?: 100f
        val feedF = (params["F"] as? Number)?.toFloat() ?: 0.037f
        val killK = (params["k"] as? Number)?.toFloat() ?: 0.060f
        val paramDu = (params["Du"] as? Number)?.toFloat() ?: 0.02f
        val paramDv = (params["Dv"] as? Number)?.toFloat() ?: 0.5f
        val stencil = (params["stencil"] as? String) ?: "9-point"
        val paramGradient = (params["paramGradient"] as? Number)?.toFloat() ?: 0f
        val paramDrift = (params["paramDrift"] as? Number)?.toFloat() ?: 0.15f
        val warmupSteps = (params["warmupSteps"] as? Number)?.toInt() ?: 400
        val stepsPerFrame = (params["stepsPerFrame"] as? Number)?.toFloat() ?: 5f
        val colorMode = (params["colorMode"] as? String) ?: "palette"

        val w = bitmap.width
        val h = bitmap.height

        val gw = when (quality) {
            Quality.DRAFT -> gridSize.coerceAtMost(96)
            Quality.BALANCED -> gridSize.coerceAtMost(160)
            Quality.ULTRA -> gridSize
        }
        val gh = gw
        val total = gw * gh
        val use9pt = stencil == "9-point"
        val isGrayScott = model == "gray-scott"

        val animSteps = if (time > 0.01f) (time * stepsPerFrame * 30f).toInt() else 0
        val totalSteps = warmupSteps + animSteps

        val driftOffset = if (paramDrift > 0f) sin(time.toDouble() * 0.5).toFloat() * paramDrift else 0f

        // Precompute neighbor indices — eliminates modulo from hot loop
        val xLeft = IntArray(gw) { (it - 1 + gw) % gw }
        val xRight = IntArray(gw) { (it + 1) % gw }
        val yRowOffset = IntArray(gh) { it * gw }
        val yUpOffset = IntArray(gh) { ((it - 1 + gh) % gh) * gw }
        val yDownOffset = IntArray(gh) { ((it + 1) % gh) * gw }

        // Initialize concentrations
        val rng = SeededRNG(seed)
        val u = FloatArray(total)
        val v = FloatArray(total)

        if (isGrayScott) {
            for (i in 0 until total) { u[i] = 1f; v[i] = 0f }
            // Seed varied circular patches for richer pattern nucleation
            val numPatches = 5 + rng.integer(0, 6)
            for (p in 0 until numPatches) {
                val pcx = rng.integer(gw / 8, gw * 7 / 8)
                val pcy = rng.integer(gh / 8, gh * 7 / 8)
                val radius = 2 + rng.integer(0, 4)
                val r2 = radius * radius
                for (dy in -radius..radius) {
                    for (dx in -radius..radius) {
                        if (dx * dx + dy * dy <= r2) {
                            val nx = (pcx + dx + gw) % gw
                            val ny = (pcy + dy + gh) % gh
                            val idx = ny * gw + nx
                            u[idx] = 0.5f + rng.random() * 0.04f
                            v[idx] = 0.25f + rng.random() * 0.04f
                        }
                    }
                }
            }
        } else {
            // Schnakenberg: steady state + small uniform noise
            val uSS = paramA + paramB
            val vSS = paramB / (uSS * uSS)
            for (i in 0 until total) {
                u[i] = uSS + (rng.random() - 0.5f) * 0.02f
                v[i] = vSS + (rng.random() - 0.5f) * 0.02f
            }
            // Add localized seed blobs with stronger perturbation to break symmetry faster
            val numBlobs = 3 + rng.integer(0, 5)
            for (p in 0 until numBlobs) {
                val bcx = rng.integer(gw / 6, gw * 5 / 6)
                val bcy = rng.integer(gh / 6, gh * 5 / 6)
                val br = 2 + rng.integer(0, 3)
                val br2 = br * br
                for (dy in -br..br) {
                    for (dx in -br..br) {
                        if (dx * dx + dy * dy <= br2) {
                            val nx = (bcx + dx + gw) % gw
                            val ny = (bcy + dy + gh) % gh
                            val idx = ny * gw + nx
                            u[idx] = uSS + (rng.random() - 0.3f) * 0.5f
                            v[idx] = vSS + (rng.random() - 0.3f) * 0.5f
                        }
                    }
                }
            }
        }

        val lapU = FloatArray(total)
        val lapV = FloatArray(total)

        // Schnakenberg: dt=0.01 (10x larger) — well within stability, patterns develop much faster
        // Gray-Scott: dt=1 (unchanged)
        val dt = if (isGrayScott) 1f else 0.01f

        val du: Float
        val dv: Float
        if (isGrayScott) { du = 0.16f; dv = 0.08f }
        else { du = paramDu; dv = paramDv }

        // Precompute spatial gradient factors per column (avoid repeated division in inner loop)
        val gradientPerCol = if (paramGradient != 0f) {
            FloatArray(gw) { x -> paramGradient * (x.toFloat() / gw - 0.5f) }
        } else null

        // Evolve simulation
        for (step in 0 until totalSteps) {
            // Compute Laplacian with precomputed neighbor indices
            for (y in 0 until gh) {
                val row = yRowOffset[y]
                val rowUp = yUpOffset[y]
                val rowDown = yDownOffset[y]
                if (use9pt) {
                    for (x in 0 until gw) {
                        val idx = row + x
                        val xl = xLeft[x]
                        val xr = xRight[x]
                        val cu = u[idx]
                        val cv = v[idx]
                        lapU[idx] = 0.2f * (u[row + xr] + u[row + xl] + u[rowDown + x] + u[rowUp + x]) +
                                0.05f * (u[rowUp + xl] + u[rowUp + xr] + u[rowDown + xl] + u[rowDown + xr]) - cu
                        lapV[idx] = 0.2f * (v[row + xr] + v[row + xl] + v[rowDown + x] + v[rowUp + x]) +
                                0.05f * (v[rowUp + xl] + v[rowUp + xr] + v[rowDown + xl] + v[rowDown + xr]) - cv
                    }
                } else {
                    for (x in 0 until gw) {
                        val idx = row + x
                        val xl = xLeft[x]
                        val xr = xRight[x]
                        lapU[idx] = u[row + xr] + u[row + xl] + u[rowDown + x] + u[rowUp + x] - 4f * u[idx]
                        lapV[idx] = v[row + xr] + v[row + xl] + v[rowDown + x] + v[rowUp + x] - 4f * v[idx]
                    }
                }
            }

            // Update concentrations
            if (isGrayScott) {
                for (y in 0 until gh) {
                    val row = yRowOffset[y]
                    for (x in 0 until gw) {
                        val idx = row + x
                        val uVal = u[idx]
                        val vVal = v[idx]
                        val uvv = uVal * vVal * vVal
                        val localK = if (gradientPerCol != null)
                            killK + gradientPerCol[x] + driftOffset else killK + driftOffset

                        val newU = uVal + dt * (du * lapU[idx] - uvv + feedF * (1f - uVal))
                        val newV = vVal + dt * (dv * lapV[idx] + uvv - (feedF + localK) * vVal)
                        u[idx] = if (newU < 0f) 0f else if (newU > 1f) 1f else newU
                        v[idx] = if (newV < 0f) 0f else if (newV > 1f) 1f else newV
                    }
                }
            } else {
                // Schnakenberg
                for (y in 0 until gh) {
                    val row = yRowOffset[y]
                    for (x in 0 until gw) {
                        val idx = row + x
                        val uVal = u[idx]
                        val vVal = v[idx]
                        val u2v = uVal * uVal * vVal
                        val localA = if (gradientPerCol != null)
                            paramA + gradientPerCol[x] * 0.2f + driftOffset * 0.1f
                        else paramA + driftOffset * 0.1f

                        u[idx] = uVal + dt * (du * lapU[idx] + gamma * (localA - uVal + u2v))
                        v[idx] = vVal + dt * (dv * lapV[idx] + gamma * (paramB - u2v))
                    }
                }
            }
        }

        // Find min/max for normalization
        val displayField = if (isGrayScott) v else u
        var fieldMin = Float.MAX_VALUE
        var fieldMax = -Float.MAX_VALUE
        for (value in displayField) {
            if (value < fieldMin) fieldMin = value
            if (value > fieldMax) fieldMax = value
        }
        val fieldRange = (fieldMax - fieldMin).coerceAtLeast(0.001f)
        val invRange = 1f / fieldRange

        // Precompute normalized field values
        val normalizedField = FloatArray(total)
        for (i in 0 until total) {
            val n = (displayField[i] - fieldMin) * invRange
            normalizedField[i] = if (n < 0f) 0f else if (n > 1f) 1f else n
        }

        // Precompute palette LUT — avoids palette.lerpColor per pixel
        val lutSize = 256
        val paletteLut = IntArray(lutSize) { palette.lerpColor(it.toFloat() / (lutSize - 1)) }
        val isBinary = colorMode == "binary"
        val binaryHigh = paletteLut[(0.9f * (lutSize - 1)).toInt()]
        val binaryLow = paletteLut[(0.1f * (lutSize - 1)).toInt()]

        // Multi-threaded rendering with bilinear interpolation
        val pixels = IntArray(w * h)
        val fScaleX = gw.toFloat() / w
        val fScaleY = gh.toFloat() / h
        val gwM1 = gw - 1
        val ghM1 = gh - 1

        val cores = Runtime.getRuntime().availableProcessors().coerceIn(2, 8)
        val threads = Array(cores) { t ->
            Thread {
                val py0 = t * h / cores
                val py1 = (t + 1) * h / cores
                for (py in py0 until py1) {
                    val fy = py * fScaleY
                    val gy0 = fy.toInt().coerceAtMost(ghM1)
                    val gy1 = (gy0 + 1).coerceAtMost(ghM1)
                    val ty = fy - gy0
                    val ity = 1f - ty
                    val r0 = gy0 * gw
                    val r1 = gy1 * gw

                    val rowOff = py * w
                    for (px in 0 until w) {
                        val fx = px * fScaleX
                        val gx0 = fx.toInt().coerceAtMost(gwM1)
                        val gx1 = (gx0 + 1).coerceAtMost(gwM1)
                        val tx = fx - gx0
                        val itx = 1f - tx

                        // Bilinear interpolation for smooth upscaling
                        val n = normalizedField[r0 + gx0] * itx * ity +
                                normalizedField[r0 + gx1] * tx * ity +
                                normalizedField[r1 + gx0] * itx * ty +
                                normalizedField[r1 + gx1] * tx * ty

                        pixels[rowOff + px] = if (isBinary) {
                            if (n > 0.5f) binaryHigh else binaryLow
                        } else {
                            val lutIdx = (n * (lutSize - 1)).toInt()
                            paletteLut[if (lutIdx < 0) 0 else if (lutIdx >= lutSize) lutSize - 1 else lutIdx]
                        }
                    }
                }
            }.also { it.start() }
        }
        threads.forEach { it.join() }

        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
        canvas.drawBitmap(bitmap, 0f, 0f, null)
    }

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float {
        val gridSize = (params["gridSize"] as? Number)?.toInt() ?: 128
        val warmupSteps = (params["warmupSteps"] as? Number)?.toInt() ?: 400
        return (gridSize.toLong() * gridSize * warmupSteps / 10000000f).coerceIn(0.2f, 1f)
    }
}
