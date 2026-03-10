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
import kotlin.math.max
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
        Parameter.NumberParam("Warm-up Steps", "warmupSteps", ParamGroup.COMPOSITION, "Steps before the static render — Schnakenberg: 400-800; Gray-Scott needs 800-2000 for developed patterns", 50f, 2000f, 50f, 800f),
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

    // -----------------------------------------------------------------------
    // Persistent animation state (mirrors web version's _turingAnim)
    // -----------------------------------------------------------------------
    @Volatile private var _cacheKey = ""
    @Volatile private var _u: FloatArray? = null
    @Volatile private var _v: FloatArray? = null
    @Volatile private var _stepCount = 0
    @Volatile private var _gridW = 0

    private companion object {
        const val DT_SCHNAKENBERG = 0.01f
        const val DT_GRAY_SCOTT = 1f
    }

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

        val gw = when (quality) {
            Quality.DRAFT -> gridSize.coerceAtMost(80)
            Quality.BALANCED -> gridSize.coerceAtMost(128)
            Quality.ULTRA -> gridSize
        }
        val gh = gw
        val total = gw * gh
        val use9pt = stencil == "9-point"
        val isGrayScott = model == "gray-scott"

        // Diffusion — Gray-Scott always uses canonical values
        val du = if (isGrayScott) 0.16f else paramDu
        val dv = if (isGrayScott) 0.08f else paramDv
        val dt = if (isGrayScott) DT_GRAY_SCOTT else DT_SCHNAKENBERG

        // Target step count
        val animSteps = if (time > 0.01f) (time * stepsPerFrame * 30f).toInt() else 0
        val targetSteps = warmupSteps + animSteps

        // Cache key (matches web: seed|size|model|a|b|F|k)
        val cacheKey = "$seed|$gw|$model|$paramA|$paramB|$feedF|$killK"
        val needsInit = cacheKey != _cacheKey || _u == null || _gridW != gw ||
                _u!!.size != total

        val u: FloatArray
        val v: FloatArray
        var currentStep: Int

        if (needsInit) {
            u = FloatArray(total)
            v = FloatArray(total)
            val rng = SeededRNG(seed)

            if (isGrayScott) {
                // Gray-Scott: U=1, V=0 with random circular patches
                for (i in 0 until total) { u[i] = 1f; v[i] = 0f }
                val numPatches = 4 + (rng.random() * 8).toInt()
                for (p in 0 until numPatches) {
                    val cx = (rng.random() * gw).toInt()
                    val cy = (rng.random() * gh).toInt()
                    val r = 2 + (rng.random() * 5).toInt()
                    val r2 = r * r
                    for (dy in -r..r) {
                        for (dx in -r..r) {
                            if (dx * dx + dy * dy <= r2) {
                                val nx = ((cx + dx) % gw + gw) % gw
                                val ny = ((cy + dy) % gh + gh) % gh
                                val idx = ny * gw + nx
                                u[idx] = 0.5f + (rng.random() - 0.5f) * 0.1f
                                v[idx] = 0.25f + (rng.random() - 0.5f) * 0.05f
                            }
                        }
                    }
                }
            } else {
                // Schnakenberg: steady state + small noise, clamp >= 0
                val u0 = paramA + paramB
                val v0 = paramB / (u0 * u0)
                for (i in 0 until total) {
                    u[i] = max(0f, u0 + (rng.random() - 0.5f) * 0.1f)
                    v[i] = max(0f, v0 + (rng.random() - 0.5f) * 0.1f)
                }
            }
            currentStep = 0
        } else {
            u = _u!!
            v = _v!!
            currentStep = _stepCount
        }

        val stepsToRun = (targetSteps - currentStep).coerceAtLeast(0)

        if (stepsToRun > 0) {
            // Precompute neighbor indices — eliminates modulo from hot loop
            val xLeft = IntArray(gw) { (it - 1 + gw) % gw }
            val xRight = IntArray(gw) { (it + 1) % gw }
            val yRowOffset = IntArray(gh) { it * gw }
            val yUpOffset = IntArray(gh) { ((it - 1 + gh) % gh) * gw }
            val yDownOffset = IntArray(gh) { ((it + 1) % gh) * gw }

            // Precompute multiplicative spatial gradient per column (matches web)
            val gwM1 = (gw - 1).coerceAtLeast(1).toFloat()
            val gradA = if (!isGrayScott && paramGradient != 0f) {
                FloatArray(gw) { x -> paramA * (1f + paramGradient * (x / gwM1 - 0.5f) * 2f) }
            } else null
            val gradK = if (isGrayScott && paramGradient != 0f) {
                FloatArray(gw) { x -> killK * (1f + paramGradient * (x / gwM1 - 0.5f) * 1.4f) }
            } else null

            val lapU = FloatArray(total)
            val lapV = FloatArray(total)

            // Evolve simulation
            for (step in 0 until stepsToRun) {
                val stepIdx = currentStep + step

                // Per-step drift (matches web: oscillate b for Schnakenberg, F for Gray-Scott)
                val driftB = if (!isGrayScott && paramDrift > 0f) {
                    paramB + paramDrift * paramB * sin(stepIdx.toDouble() * 0.008).toFloat()
                } else paramB
                val driftF = if (isGrayScott && paramDrift > 0f) {
                    feedF + paramDrift * feedF * sin(stepIdx.toDouble() * 0.005).toFloat()
                } else feedF

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
                            lapU[idx] = 0.2f * (u[row + xr] + u[row + xl] + u[rowDown + x] + u[rowUp + x]) +
                                    0.05f * (u[rowUp + xl] + u[rowUp + xr] + u[rowDown + xl] + u[rowDown + xr]) - u[idx]
                            lapV[idx] = 0.2f * (v[row + xr] + v[row + xl] + v[rowDown + x] + v[rowUp + x]) +
                                    0.05f * (v[rowUp + xl] + v[rowUp + xr] + v[rowDown + xl] + v[rowDown + xr]) - v[idx]
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
                            val localK = gradK?.get(x) ?: killK

                            val newU = uVal + dt * (du * lapU[idx] - uvv + driftF * (1f - uVal))
                            val newV = vVal + dt * (dv * lapV[idx] + uvv - (driftF + localK) * vVal)
                            u[idx] = max(0f, min(1f, newU))
                            v[idx] = max(0f, min(1f, newV))
                        }
                    }
                } else {
                    // Schnakenberg — clamp >= 0 (matches web)
                    for (y in 0 until gh) {
                        val row = yRowOffset[y]
                        for (x in 0 until gw) {
                            val idx = row + x
                            val uVal = u[idx]
                            val vVal = v[idx]
                            val u2v = uVal * uVal * vVal
                            val localA = gradA?.get(x) ?: paramA

                            u[idx] = max(0f, uVal + dt * (du * lapU[idx] + gamma * (localA - uVal + u2v)))
                            v[idx] = max(0f, vVal + dt * (dv * lapV[idx] + gamma * (driftB - u2v)))
                        }
                    }
                }
            }
        }

        // Cache state for next frame
        _cacheKey = cacheKey
        _u = u
        _v = v
        _stepCount = currentStep + stepsToRun
        _gridW = gw

        // Fixed normalization ranges (matches web):
        // Schnakenberg: U ∈ [0, 2·(a+b)] → [0,1]
        // Gray-Scott:   V ∈ [0, 0.5]     → [0,1]
        val displayField = if (isGrayScott) v else u
        val fieldMax = if (isGrayScott) 0.5f else 2f * (paramA + paramB)
        val invFieldMax = 1f / fieldMax

        // Precompute palette LUT
        val lutSize = 256
        val paletteLut = IntArray(lutSize) { palette.lerpColor(it.toFloat() / (lutSize - 1)) }
        val isBinary = colorMode == "binary"

        // Render
        val pixels = IntArray(w * h)
        val cellW = w.toFloat() / gw
        val cellH = h.toFloat() / gh

        for (py in 0 until h) {
            val gy = (py / cellH).toInt().coerceAtMost(gh - 1)
            for (px in 0 until w) {
                val gx = (px / cellW).toInt().coerceAtMost(gw - 1)
                val raw = (displayField[gy * gw + gx] * invFieldMax).coerceIn(0f, 1f)

                pixels[py * w + px] = if (isBinary) {
                    val t = if (raw > 0.5f) 1f else 0f
                    paletteLut[(t * (lutSize - 1)).toInt()]
                } else {
                    paletteLut[(raw * (lutSize - 1)).toInt().coerceIn(0, lutSize - 1)]
                }
            }
        }

        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
        canvas.drawBitmap(bitmap, 0f, 0f, null)
    }

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float {
        val gridSize = (params["gridSize"] as? Number)?.toInt() ?: 128
        val warmupSteps = (params["warmupSteps"] as? Number)?.toInt() ?: 800
        return (gridSize.toLong() * gridSize * warmupSteps / 10000000f).coerceIn(0.2f, 1f)
    }
}
