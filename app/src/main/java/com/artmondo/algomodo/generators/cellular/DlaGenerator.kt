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

class DlaGenerator : Generator {

    override val id = "cellular-dla"
    override val family = "cellular"
    override val styleName = "DLA"
    override val definition = "Diffusion-Limited Aggregation: random walkers stick to a growing aggregate, forming fractal branching structures."
    override val algorithmNotes = "One or more seed cells are placed on the grid. Particles are launched from random boundary positions and perform a random walk. When a walker lands adjacent to an aggregate cell, it sticks with given probability. The arrival order determines the color mapping."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema = listOf(
        Parameter.NumberParam("Grid Size", "gridSize", ParamGroup.COMPOSITION, null, 32f, 256f, 16f, 128f),
        Parameter.SelectParam("Seed Mode", "seedMode", ParamGroup.COMPOSITION, "center: classic radial DLA from a central seed | line-bottom: aggregate grows upward from a full bottom-row seed, like a forest of stalactites | scatter: N random seeds create competing clusters", listOf("center", "line-bottom", "scatter"), "center"),
        Parameter.NumberParam("Scatter Seeds", "scatterSeeds", ParamGroup.COMPOSITION, "Number of random seed points (scatter mode only)", 2f, 16f, 1f, 5f),
        Parameter.NumberParam("Target Particles", "targetParticles", ParamGroup.COMPOSITION, "Particles to grow in the static render", 100f, 8000f, 100f, 3000f),
        Parameter.NumberParam("Particles / Frame", "particlesPerFrame", ParamGroup.FLOW_MOTION, "Particles attempted per animation frame", 1f, 50f, 1f, 8f),
        Parameter.NumberParam("Walk Bias", "walkBias", ParamGroup.FLOW_MOTION, "Directional drift of random walkers — positive = downward gravity, negative = upward; at 0 the walk is isotropic", -0.7f, 0.7f, 0.05f, 0.0f),
        Parameter.NumberParam("Stick Probability", "stickProbability", ParamGroup.TEXTURE, "Base probability a touching walker sticks — below 1.0 rounds tips, producing denser clusters", 0.1f, 1.0f, 0.05f, 1.0f),
        Parameter.NumberParam("Tip Bias", "tipBias", ParamGroup.TEXTURE, "Modulates stickiness by position — positive: outer tips stickier → longer, sparser arms; negative: inner positions stickier → denser, rounder core", -0.9f, 0.9f, 0.1f, 0.0f),
        Parameter.SelectParam("Color Mode", "colorMode", ParamGroup.COLOR, "arrival: palette by order of sticking (first = oldest) | radius: palette by distance from origin/seed | monochrome: uniform last palette color", listOf("arrival", "radius", "monochrome"), "arrival")
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "gridSize" to 128f,
        "seedMode" to "center",
        "scatterSeeds" to 5f,
        "targetParticles" to 3000f,
        "particlesPerFrame" to 8f,
        "walkBias" to 0.0f,
        "stickProbability" to 1.0f,
        "tipBias" to 0.0f,
        "colorMode" to "arrival"
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
        val maxParticles = (params["targetParticles"] as? Number)?.toInt() ?: 3000
        val stickiness = (params["stickProbability"] as? Number)?.toFloat() ?: 1.0f
        val seedCount = (params["scatterSeeds"] as? Number)?.toInt() ?: 5
        val particlesPerFrame = (params["particlesPerFrame"] as? Number)?.toFloat() ?: 8f
        val seedMode = (params["seedMode"] as? String) ?: "center"
        val walkBias = (params["walkBias"] as? Number)?.toFloat() ?: 0.0f
        val tipBias = (params["tipBias"] as? Number)?.toFloat() ?: 0.0f
        val colorMode = (params["colorMode"] as? String) ?: "arrival"

        val w = bitmap.width
        val h = bitmap.height
        val gridSize = (params["gridSize"] as? Number)?.toInt() ?: 128

        val particlesToPlace = ((time * particlesPerFrame).toInt()).coerceAtMost(maxParticles)
        val totalCells = gridSize * gridSize
        val cx = gridSize / 2
        val cy = gridSize / 2

        val rng = SeededRNG(seed)
        val aggregate = BooleanArray(totalCells)
        val order = IntArray(totalCells) { -1 }
        var placed = 0

        // Place seed(s) based on seedMode
        when (seedMode) {
            "line-bottom" -> {
                // Full bottom row as seed
                val sy = gridSize - 1
                for (sx in 0 until gridSize) {
                    val idx = sy * gridSize + sx
                    aggregate[idx] = true
                    order[idx] = placed++
                }
            }
            "scatter" -> {
                // N random seeds scattered across the grid
                for (s in 0 until seedCount) {
                    val sx = rng.integer(0, gridSize - 1)
                    val sy = rng.integer(0, gridSize - 1)
                    val idx = sy * gridSize + sx
                    if (!aggregate[idx]) {
                        aggregate[idx] = true
                        order[idx] = placed++
                    }
                }
            }
            else /* center */ -> {
                for (s in 0 until seedCount) {
                    val sx = cx + (s - seedCount / 2)
                    val sy = cy
                    if (sx in 0 until gridSize && sy in 0 until gridSize) {
                        val idx = sy * gridSize + sx
                        aggregate[idx] = true
                        order[idx] = placed++
                    }
                }
            }
        }

        val dx4 = intArrayOf(0, 1, 0, -1)
        val dy4 = intArrayOf(-1, 0, 1, 0)

        // Maximum random walk steps per particle to prevent infinite loops
        val maxWalkSteps = gridSize * gridSize

        // Launch particles with a maximum attempt limit to prevent infinite loops
        val maxAttempts = particlesToPlace * 10
        var attempts = 0

        while (placed < particlesToPlace && attempts < maxAttempts) {
            attempts++

            // Launch from random position on a circle around center
            val launchRadius = (gridSize / 2 - 2).coerceAtLeast(5)
            val angle = rng.randomAngle()
            var px = (cx + launchRadius * kotlin.math.cos(angle)).toInt().coerceIn(0, gridSize - 1)
            var py = (cy + launchRadius * kotlin.math.sin(angle)).toInt().coerceIn(0, gridSize - 1)

            for (walk in 0 until maxWalkSteps) {
                // Check adjacency to aggregate
                var adjacent = false
                for (d in 0..3) {
                    val nx = px + dx4[d]
                    val ny = py + dy4[d]
                    if (nx in 0 until gridSize && ny in 0 until gridSize && aggregate[ny * gridSize + nx]) {
                        adjacent = true
                        break
                    }
                }

                if (adjacent) {
                    // tipBias: modulate stick probability by distance from center
                    val dist = kotlin.math.sqrt(((px - cx) * (px - cx) + (py - cy) * (py - cy)).toFloat())
                    val maxDist = (gridSize / 2).toFloat()
                    val normalizedDist = (dist / maxDist).coerceIn(0f, 1f)
                    // positive tipBias: outer tips stickier; negative: inner positions stickier
                    val tipFactor = if (tipBias >= 0f) {
                        1f - tipBias * (1f - normalizedDist)
                    } else {
                        1f + tipBias * normalizedDist
                    }.coerceIn(0.05f, 1f)
                    val effectiveStick = stickiness * tipFactor

                    if (rng.random() < effectiveStick) {
                        val idx = py * gridSize + px
                        if (!aggregate[idx]) {
                            aggregate[idx] = true
                            order[idx] = placed++
                        }
                        break
                    }
                }

                // Random walk with directional bias
                // walkBias: positive = downward drift, negative = upward
                val r = rng.random()
                val dir = when {
                    r < 0.25f - walkBias.coerceAtLeast(0f) * 0.1f -> 0 // up
                    r < 0.50f -> 1 // right
                    r < 0.75f + walkBias.coerceAtLeast(0f) * 0.1f -> 2 // down
                    else -> 3 // left
                }
                px += dx4[dir]
                py += dy4[dir]

                // Kill if out of bounds
                if (px < 0 || px >= gridSize || py < 0 || py >= gridSize) break
            }
        }

        // Render
        val pixels = IntArray(w * h)
        val cellW = w.toFloat() / gridSize
        val cellH = h.toFloat() / gridSize
        val maxOrder = placed.coerceAtLeast(1)

        val paletteColors = palette.colorInts()
        val monoColor = paletteColors[paletteColors.size - 1]

        for (ry in 0 until h) {
            val gy = (ry / cellH).toInt().coerceAtMost(gridSize - 1)
            for (rx in 0 until w) {
                val gx = (rx / cellW).toInt().coerceAtMost(gridSize - 1)
                val idx = gy * gridSize + gx
                pixels[ry * w + rx] = if (aggregate[idx]) {
                    when (colorMode) {
                        "radius" -> {
                            // Color by distance from origin/seed center
                            val dist = kotlin.math.sqrt(((gx - cx) * (gx - cx) + (gy - cy) * (gy - cy)).toFloat())
                            val maxDist = (gridSize / 2).toFloat().coerceAtLeast(1f)
                            val t = (dist / maxDist).coerceIn(0f, 1f)
                            palette.lerpColor(t)
                        }
                        "monochrome" -> monoColor
                        else /* arrival */ -> {
                            val t = order[idx].toFloat() / maxOrder
                            palette.lerpColor(t)
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
