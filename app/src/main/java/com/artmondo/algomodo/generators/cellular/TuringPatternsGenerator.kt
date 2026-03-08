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
import kotlin.math.abs
import kotlin.math.min
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
    override val definition = "Multi-scale Turing patterns: overlapping activator-inhibitor systems at different spatial scales produce complex, organic patterns."
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
        Parameter.NumberParam("Warm-up Steps", "warmupSteps", ParamGroup.COMPOSITION, "Steps before the static render — Schnakenberg: 400-800; Gray-Scott needs 800-2000 for developed patterns", 50f, 2000f, 50f, 500f),
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
        "warmupSteps" to 800f,
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
        val warmupSteps = (params["warmupSteps"] as? Number)?.toInt() ?: 800
        val stepsPerFrame = (params["stepsPerFrame"] as? Number)?.toFloat() ?: 5f
        val colorMode = (params["colorMode"] as? String) ?: "palette"

        val w = bitmap.width
        val h = bitmap.height

        // Grid size: use full resolution for quality
        val gw = when (quality) {
            Quality.DRAFT -> gridSize.coerceAtMost(96)
            Quality.BALANCED -> gridSize.coerceAtMost(160)
            Quality.ULTRA -> gridSize
        }
        val gh = gw
        val total = gw * gh
        val use9pt = stencil == "9-point"

        // Animation step count on top of warmup
        val animSteps = if (time > 0.01f) (time * stepsPerFrame * 30f).toInt() else 0
        val totalSteps = warmupSteps + animSteps

        // Time-based drift offset
        val driftOffset = if (paramDrift > 0f) sin(time.toDouble() * 0.5).toFloat() * paramDrift else 0f

        // Initialize concentrations
        val rng = SeededRNG(seed)
        val u = FloatArray(total)
        val v = FloatArray(total)

        if (model == "gray-scott") {
            // Gray-Scott: U=1 everywhere, V=0 with small random patches of V
            for (i in 0 until total) {
                u[i] = 1f
                v[i] = 0f
            }
            // Seed several small square patches of V
            val numPatches = 3 + rng.integer(0, 4)
            for (p in 0 until numPatches) {
                val pcx = rng.integer(gw / 6, gw * 5 / 6)
                val pcy = rng.integer(gh / 6, gh * 5 / 6)
                val pSize = 2 + rng.integer(0, 3)
                for (dy in -pSize..pSize) {
                    for (dx in -pSize..pSize) {
                        val nx = (pcx + dx + gw) % gw
                        val ny = (pcy + dy + gh) % gh
                        val idx = ny * gw + nx
                        u[idx] = 0.5f + rng.random() * 0.02f
                        v[idx] = 0.25f + rng.random() * 0.02f
                    }
                }
            }
        } else {
            // Schnakenberg: start near steady state (a+b, 1/(a+b)^2 * b) with small perturbation
            val uSS = paramA + paramB
            val vSS = paramB / (uSS * uSS)
            for (i in 0 until total) {
                u[i] = uSS + (rng.random() - 0.5f) * 0.1f
                v[i] = vSS + (rng.random() - 0.5f) * 0.1f
            }
        }

        // Temp arrays for Laplacian
        val lapU = FloatArray(total)
        val lapV = FloatArray(total)

        // Integration time step
        val dt = if (model == "gray-scott") 1f else 0.001f

        // Diffusion coefficients
        val du: Float
        val dv: Float
        if (model == "gray-scott") {
            du = 0.16f
            dv = 0.08f
        } else {
            du = paramDu
            dv = paramDv
        }

        // Evolve
        for (step in 0 until totalSteps) {
            // Compute Laplacian for u and v
            for (y in 0 until gh) {
                for (x in 0 until gw) {
                    val idx = y * gw + x
                    val xm = (x - 1 + gw) % gw
                    val xp = (x + 1) % gw
                    val ym = (y - 1 + gh) % gh
                    val yp = (y + 1) % gh

                    if (use9pt) {
                        // 9-point isotropic stencil: cardinal=0.2, diagonal=0.05, center=-1
                        val centerU = u[idx]
                        val centerV = v[idx]
                        lapU[idx] = 0.2f * (u[y * gw + xp] + u[y * gw + xm] + u[yp * gw + x] + u[ym * gw + x]) +
                                0.05f * (u[ym * gw + xm] + u[ym * gw + xp] + u[yp * gw + xm] + u[yp * gw + xp]) -
                                centerU
                        lapV[idx] = 0.2f * (v[y * gw + xp] + v[y * gw + xm] + v[yp * gw + x] + v[ym * gw + x]) +
                                0.05f * (v[ym * gw + xm] + v[ym * gw + xp] + v[yp * gw + xm] + v[yp * gw + xp]) -
                                centerV
                    } else {
                        // Standard 5-point stencil
                        lapU[idx] = u[y * gw + xp] + u[y * gw + xm] + u[yp * gw + x] + u[ym * gw + x] - 4f * u[idx]
                        lapV[idx] = v[y * gw + xp] + v[y * gw + xm] + v[yp * gw + x] + v[ym * gw + x] - 4f * v[idx]
                    }
                }
            }

            // Update concentrations
            if (model == "gray-scott") {
                for (y in 0 until gh) {
                    for (x in 0 until gw) {
                        val idx = y * gw + x
                        val uVal = u[idx]
                        val vVal = v[idx]
                        val uvv = uVal * vVal * vVal

                        // Spatial gradient on k
                        val localK = killK + paramGradient * (x.toFloat() / gw - 0.5f) + driftOffset

                        u[idx] = (uVal + dt * (du * lapU[idx] - uvv + feedF * (1f - uVal))).coerceIn(0f, 1f)
                        v[idx] = (vVal + dt * (dv * lapV[idx] + uvv - (feedF + localK) * vVal)).coerceIn(0f, 1f)
                    }
                }
            } else {
                // Schnakenberg
                for (y in 0 until gh) {
                    for (x in 0 until gw) {
                        val idx = y * gw + x
                        val uVal = u[idx]
                        val vVal = v[idx]
                        val u2v = uVal * uVal * vVal

                        // Spatial gradient on a
                        val localA = paramA + paramGradient * (x.toFloat() / gw - 0.5f) * 0.2f + driftOffset * 0.1f

                        u[idx] = uVal + dt * (du * lapU[idx] + gamma * (localA - uVal + u2v))
                        v[idx] = vVal + dt * (dv * lapV[idx] + gamma * (paramB - u2v))
                    }
                }
            }
        }

        // Find min/max for normalization
        // Use the activator (u for Schnakenberg) or catalyst (v for Gray-Scott) for display
        val displayField = if (model == "gray-scott") v else u
        var fieldMin = Float.MAX_VALUE
        var fieldMax = Float.MIN_VALUE
        for (value in displayField) {
            if (value < fieldMin) fieldMin = value
            if (value > fieldMax) fieldMax = value
        }
        val fieldRange = (fieldMax - fieldMin).coerceAtLeast(0.001f)

        // Render
        val pixels = IntArray(w * h)
        val cellW = w.toFloat() / gw
        val cellH = h.toFloat() / gh

        for (py in 0 until h) {
            val gy = (py / cellH).toInt().coerceAtMost(gh - 1)
            for (px in 0 until w) {
                val gx = (px / cellW).toInt().coerceAtMost(gw - 1)
                val idx = gy * gw + gx
                val normalized = ((displayField[idx] - fieldMin) / fieldRange).coerceIn(0f, 1f)

                pixels[py * w + px] = when (colorMode) {
                    "binary" -> {
                        if (normalized > 0.5f) palette.lerpColor(0.9f) else palette.lerpColor(0.1f)
                    }
                    else -> palette.lerpColor(normalized)
                }
            }
        }

        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
        canvas.drawBitmap(bitmap, 0f, 0f, null)
    }
}
