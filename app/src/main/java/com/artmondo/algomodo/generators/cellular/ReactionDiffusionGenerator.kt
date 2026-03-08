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

    override fun renderCanvas(
        canvas: Canvas,
        bitmap: Bitmap,
        params: Map<String, Any>,
        seed: Int,
        palette: Palette,
        quality: Quality,
        time: Float
    ) {
        val feed = (params["feedRate"] as? Number)?.toFloat() ?: 0.035f
        val kill = (params["killRate"] as? Number)?.toFloat() ?: 0.065f
        val dA = (params["diffU"] as? Number)?.toFloat() ?: 0.8f
        val dB = (params["diffV"] as? Number)?.toFloat() ?: 0.3f
        val stepsPerFrame = (params["stepsPerFrame"] as? Number)?.toInt() ?: 10

        val w = bitmap.width
        val h = bitmap.height

        // Use a downscaled simulation grid for performance
        val scale = when (quality) {
            Quality.DRAFT -> 4
            Quality.BALANCED -> 2
            Quality.ULTRA -> 1
        }
        val gw = w / scale
        val gh = h / scale

        val totalSteps = (time * stepsPerFrame * 10).toInt() // total evolution steps

        // Initialize from seed
        val rng = SeededRNG(seed)
        var a = FloatArray(gw * gh) { 1f }
        var b = FloatArray(gw * gh) { 0f }

        // Seed several random patches of chemical B
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

        // Evolve using double-buffering
        val dt = 1.0f
        var curA = a
        var curB = b
        var nxtA = FloatArray(gw * gh)
        var nxtB = FloatArray(gw * gh)

        for (step in 0 until totalSteps) {
            for (y in 0 until gh) {
                for (x in 0 until gw) {
                    val idx = y * gw + x
                    val aVal = curA[idx]
                    val bVal = curB[idx]

                    // Laplacian with 5-point stencil
                    val lapA = laplacian(curA, x, y, gw, gh)
                    val lapB = laplacian(curB, x, y, gw, gh)

                    val abb = aVal * bVal * bVal
                    nxtA[idx] = (aVal + (dA * lapA - abb + feed * (1f - aVal)) * dt).coerceIn(0f, 1f)
                    nxtB[idx] = (bVal + (dB * lapB + abb - (kill + feed) * bVal) * dt).coerceIn(0f, 1f)
                }
            }
            // Swap current and next buffers
            val tmpA = curA; curA = nxtA; nxtA = tmpA
            val tmpB = curB; curB = nxtB; nxtB = tmpB
        }

        // curA/curB now hold the final state
        a = curA
        b = curB

        // Render to bitmap
        val pixels = IntArray(w * h)
        for (py in 0 until h) {
            val gy = (py / scale).coerceAtMost(gh - 1)
            for (px in 0 until w) {
                val gx = (px / scale).coerceAtMost(gw - 1)
                val bVal = b[gy * gw + gx]
                pixels[py * w + px] = palette.lerpColor(bVal)
            }
        }

        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
        canvas.drawBitmap(bitmap, 0f, 0f, null)
    }

    private fun laplacian(grid: FloatArray, x: Int, y: Int, w: Int, h: Int): Float {
        val idx = y * w + x
        val center = grid[idx]
        val left = grid[y * w + (x - 1 + w) % w]
        val right = grid[y * w + (x + 1) % w]
        val up = grid[((y - 1 + h) % h) * w + x]
        val down = grid[((y + 1) % h) * w + x]
        // Standard 5-point stencil
        return left + right + up + down - 4f * center
    }
}
