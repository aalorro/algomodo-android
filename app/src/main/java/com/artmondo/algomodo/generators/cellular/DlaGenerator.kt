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
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

class DlaGenerator : Generator {

    override val id = "cellular-dla"
    override val family = "cellular"
    override val styleName = "DLA"
    override val definition = "Diffusion-Limited Aggregation: random walkers stick to a growing aggregate, forming fractal branching structures."
    override val algorithmNotes = "Seed cells are placed on the grid. Particles launch from adaptive radii and random-walk with circle-jump acceleration. On contact with the aggregate they stick with tunable probability. Optional rotational symmetry creates mandala-like patterns."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema = listOf(
        Parameter.NumberParam("Grid Size", "gridSize", ParamGroup.COMPOSITION, null, 32f, 512f, 16f, 200f),
        Parameter.SelectParam("Seed Mode", "seedMode", ParamGroup.COMPOSITION,
            "center: classic radial DLA | line-bottom: grows upward from bottom edge | scatter: N random seeds | ring: seeds in a circle",
            listOf("center", "line-bottom", "scatter", "ring"), "center"),
        Parameter.NumberParam("Scatter Seeds", "scatterSeeds", ParamGroup.COMPOSITION,
            "Number of seed points (scatter/ring modes)", 2f, 20f, 1f, 5f),
        Parameter.NumberParam("Target Particles", "targetParticles", ParamGroup.COMPOSITION,
            "Total particles to grow", 500f, 30000f, 500f, 8000f),
        Parameter.NumberParam("Particles / Frame", "particlesPerFrame", ParamGroup.FLOW_MOTION,
            "Particles attempted per animation frame", 1f, 200f, 1f, 30f),
        Parameter.NumberParam("Drift Strength", "driftStrength", ParamGroup.FLOW_MOTION,
            "Directional bias of walkers — 0 = isotropic, higher = stronger drift",
            0f, 0.5f, 0.05f, 0.0f),
        Parameter.NumberParam("Drift Angle", "driftAngle", ParamGroup.FLOW_MOTION,
            "Direction walkers drift toward (degrees) — 0 = right, 90 = down, 270 = up",
            0f, 360f, 15f, 270f),
        Parameter.NumberParam("Stick Probability", "stickProbability", ParamGroup.TEXTURE,
            "Probability a walker sticks on contact — below 1.0 produces denser, rounder clusters",
            0.1f, 1.0f, 0.05f, 1.0f),
        Parameter.NumberParam("Tip Bias", "tipBias", ParamGroup.TEXTURE,
            "Positive: outer tips stickier → longer arms | Negative: inner stickier → denser core",
            -0.9f, 0.9f, 0.1f, 0.0f),
        Parameter.SelectParam("Symmetry", "symmetry", ParamGroup.GEOMETRY,
            "Rotational symmetry — 1: none | 2-6: N-fold mandala pattern",
            listOf("1", "2", "3", "4", "5", "6"), "1"),
        Parameter.NumberParam("Particle Size", "particleSize", ParamGroup.TEXTURE,
            "Radius of each stuck particle — larger = thicker, blobby branches",
            1f, 4f, 1f, 1f),
        Parameter.SelectParam("Color Mode", "colorMode", ParamGroup.COLOR,
            "arrival: by sticking order | radius: by distance from center | angle: by angle from center | depth: by tree depth from seed | monochrome: uniform",
            listOf("arrival", "radius", "angle", "depth", "monochrome"), "arrival"),
        Parameter.BooleanParam("Background Glow", "bgGlow", ParamGroup.COLOR,
            "Soft halo around the aggregate", false)
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "gridSize" to 200f,
        "seedMode" to "center",
        "scatterSeeds" to 5f,
        "targetParticles" to 8000f,
        "particlesPerFrame" to 30f,
        "driftStrength" to 0.0f,
        "driftAngle" to 270f,
        "stickProbability" to 1.0f,
        "tipBias" to 0.0f,
        "symmetry" to "1",
        "particleSize" to 1f,
        "colorMode" to "arrival",
        "bgGlow" to false
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
        val gridSize = (params["gridSize"] as? Number)?.toInt() ?: 200
        val maxParticles = (params["targetParticles"] as? Number)?.toInt() ?: 8000
        val stickiness = (params["stickProbability"] as? Number)?.toFloat() ?: 1.0f
        val seedCount = (params["scatterSeeds"] as? Number)?.toInt() ?: 5
        val ppf = (params["particlesPerFrame"] as? Number)?.toFloat() ?: 30f
        val seedMode = (params["seedMode"] as? String) ?: "center"
        val driftStr = (params["driftStrength"] as? Number)?.toFloat() ?: 0.0f
        val driftDeg = (params["driftAngle"] as? Number)?.toFloat() ?: 270f
        val tipBias = (params["tipBias"] as? Number)?.toFloat() ?: 0.0f
        val colorMode = (params["colorMode"] as? String) ?: "arrival"
        val symN = ((params["symmetry"] as? String) ?: "1").toIntOrNull() ?: 1
        val pSize = (params["particleSize"] as? Number)?.toInt() ?: 1
        val bgGlow = (params["bgGlow"] as? Boolean) ?: false

        val w = bitmap.width
        val h = bitmap.height
        val totalCells = gridSize * gridSize
        val cx = gridSize / 2
        val cy = gridSize / 2

        // time=0 → static render (full DLA), time>0 → animation frame
        val target = if (time <= 0f) maxParticles
            else ((time * ppf).toInt()).coerceAtMost(maxParticles)

        val rng = SeededRNG(seed)
        val agg = BooleanArray(totalCells)
        val ord = IntArray(totalCells) { -1 }
        val dep = IntArray(totalCells) { -1 }
        var placed = 0
        var maxAggR = 0f    // aggregate bounding radius from center
        var minAggY = gridSize // topmost aggregate row (for line-bottom)

        val dx4 = intArrayOf(1, 0, -1, 0)
        val dy4 = intArrayOf(0, 1, 0, -1)

        // --- Placement helpers ---

        fun placeOne(x: Int, y: Int, d: Int) {
            val i = y * gridSize + x
            if (agg[i]) return
            agg[i] = true; ord[i] = placed++; dep[i] = d
            val r = sqrt(((x - cx) * (x - cx) + (y - cy) * (y - cy)).toFloat())
            if (r > maxAggR) maxAggR = r
            if (y < minAggY) minAggY = y
        }

        fun placeBlob(x: Int, y: Int, parentDepth: Int) {
            val r = pSize - 1
            if (r == 0) { placeOne(x, y, parentDepth + 1); return }
            for (dy in -r..r) for (dx in -r..r) {
                if (dx * dx + dy * dy <= r * r) {
                    placeOne(
                        (x + dx).coerceIn(0, gridSize - 1),
                        (y + dy).coerceIn(0, gridSize - 1),
                        parentDepth + 1
                    )
                }
            }
        }

        fun placeSym(x: Int, y: Int, parentDepth: Int) {
            if (symN <= 1) { placeBlob(x, y, parentDepth); return }
            val rx = x - cx; val ry = y - cy
            for (k in 0 until symN) {
                val a = k * (2.0 * Math.PI / symN)
                val sx = (rx * cos(a) - ry * sin(a)).roundToInt() + cx
                val sy = (rx * sin(a) + ry * cos(a)).roundToInt() + cy
                if (sx in 0 until gridSize && sy in 0 until gridSize) placeBlob(sx, sy, parentDepth)
            }
        }

        // --- Place seeds ---

        when (seedMode) {
            "line-bottom" -> {
                for (x in 0 until gridSize) placeOne(x, gridSize - 1, 0)
            }
            "scatter" -> repeat(seedCount) {
                placeOne(rng.integer(0, gridSize - 1), rng.integer(0, gridSize - 1), 0)
            }
            "ring" -> {
                val ringR = gridSize * 0.2f
                repeat(seedCount) { s ->
                    val a = s * (2.0 * Math.PI / seedCount)
                    placeOne(
                        (cx + ringR * cos(a)).roundToInt().coerceIn(0, gridSize - 1),
                        (cy + ringR * sin(a)).roundToInt().coerceIn(0, gridSize - 1), 0
                    )
                }
            }
            else -> placeOne(cx, cy, 0)
        }

        // --- Drift direction probabilities (precomputed) ---

        val dRad = driftDeg * (Math.PI.toFloat() / 180f)
        val bx = cos(dRad) * driftStr
        val by = sin(dRad) * driftStr
        val pR = (0.25f + bx * 0.5f).coerceAtLeast(0.001f)
        val pD = (0.25f + by * 0.5f).coerceAtLeast(0.001f)
        val pL = (0.25f - bx * 0.5f).coerceAtLeast(0.001f)
        val pU = (0.25f - by * 0.5f).coerceAtLeast(0.001f)
        val pT = pR + pD + pL + pU
        val cum1 = pR / pT
        val cum2 = (pR + pD) / pT
        val cum3 = (pR + pD + pL) / pT

        // --- Main DLA loop ---

        val margin = 5
        var attempts = 0
        val maxAttempts = target * 8

        while (placed < target && attempts < maxAttempts) {
            attempts++

            // Adaptive launch radius
            val launchR = (maxAggR + margin).coerceIn(5f, (gridSize / 2 - 1).toFloat())
            val killR2 = (launchR * 2.5f + margin).let { it * it }

            // Launch walker
            var px: Int; var py: Int
            if (seedMode == "line-bottom") {
                px = rng.integer(0, gridSize - 1)
                py = (minAggY - margin).coerceAtLeast(0)
            } else {
                val a = rng.randomAngle()
                px = (cx + launchR * cos(a)).toInt().coerceIn(0, gridSize - 1)
                py = (cy + launchR * sin(a)).toInt().coerceIn(0, gridSize - 1)
            }

            val maxSteps = gridSize * gridSize
            for (step in 0 until maxSteps) {
                // Check 4-neighbor adjacency
                var adj = false; var pdep = 0
                for (d in 0..3) {
                    val nx = px + dx4[d]; val ny = py + dy4[d]
                    if (nx in 0 until gridSize && ny in 0 until gridSize) {
                        val ni = ny * gridSize + nx
                        if (agg[ni]) { adj = true; pdep = dep[ni]; break }
                    }
                }

                if (adj) {
                    // Tip bias modulation
                    val dist = sqrt(((px - cx) * (px - cx) + (py - cy) * (py - cy)).toFloat())
                    val nd = (dist / (gridSize / 2f)).coerceIn(0f, 1f)
                    val tf = (if (tipBias >= 0f) 1f - tipBias * (1f - nd)
                              else 1f + tipBias * nd).coerceIn(0.05f, 1f)
                    if (rng.random() < stickiness * tf && !agg[py * gridSize + px]) {
                        placeSym(px, py, pdep)
                    }
                    break
                }

                // Circle-jump optimization: skip empty space
                if (seedMode != "line-bottom") {
                    val dc = sqrt(((px - cx) * (px - cx) + (py - cy) * (py - cy)).toFloat())
                    val gap = dc - maxAggR
                    if (gap > 2f) {
                        val jr = gap - 1f
                        val ja = rng.randomAngle()
                        px = (px + jr * cos(ja)).toInt().coerceIn(0, gridSize - 1)
                        py = (py + jr * sin(ja)).toInt().coerceIn(0, gridSize - 1)
                        continue
                    }
                }

                // Walk step with drift
                val r = rng.random()
                val dir = when { r < cum1 -> 0; r < cum2 -> 1; r < cum3 -> 2; else -> 3 }
                px += dx4[dir]; py += dy4[dir]

                // Kill if out of bounds
                if (px < 0 || px >= gridSize || py < 0 || py >= gridSize) break

                // Kill if too far from aggregate
                if (seedMode == "line-bottom") {
                    if (py < (minAggY - gridSize / 3).coerceAtLeast(0)) break
                } else {
                    val d2 = ((px - cx) * (px - cx) + (py - cy) * (py - cy)).toFloat()
                    if (d2 > killR2) break
                }
            }
        }

        // --- Render ---

        val pixels = IntArray(w * h)
        val cellW = w.toFloat() / gridSize
        val cellH = h.toFloat() / gridSize
        val maxOrd = placed.coerceAtLeast(1)
        val maxDep = run { var m = 1; for (i in 0 until totalCells) if (dep[i] > m) m = dep[i]; m }
        val mono = palette.colorInts().last()

        // Background glow via BFS distance transform
        val glowField: FloatArray? = if (bgGlow) {
            val f = FloatArray(totalCells) { Float.MAX_VALUE }
            val q = ArrayDeque<Int>(placed + 16)
            for (i in 0 until totalCells) if (agg[i]) { f[i] = 0f; q.addLast(i) }
            val glowMax = gridSize * 0.15f
            while (q.isNotEmpty()) {
                val idx = q.removeFirst()
                val ix = idx % gridSize; val iy = idx / gridSize
                val nd = f[idx] + 1f
                if (nd > glowMax) continue
                for (d in 0..3) {
                    val nx = ix + dx4[d]; val ny = iy + dy4[d]
                    if (nx in 0 until gridSize && ny in 0 until gridSize) {
                        val ni = ny * gridSize + nx
                        if (nd < f[ni]) { f[ni] = nd; q.addLast(ni) }
                    }
                }
            }
            f
        } else null
        val glowMax = gridSize * 0.15f

        for (ry in 0 until h) {
            val gy = (ry / cellH).toInt().coerceAtMost(gridSize - 1)
            val rowOff = ry * w
            for (rx in 0 until w) {
                val gx = (rx / cellW).toInt().coerceAtMost(gridSize - 1)
                val idx = gy * gridSize + gx
                pixels[rowOff + rx] = if (agg[idx]) {
                    when (colorMode) {
                        "radius" -> {
                            val d = sqrt(((gx - cx) * (gx - cx) + (gy - cy) * (gy - cy)).toFloat())
                            palette.lerpColor((d / (gridSize / 2f).coerceAtLeast(1f)).coerceIn(0f, 1f))
                        }
                        "angle" -> {
                            val a = atan2((gy - cy).toFloat(), (gx - cx).toFloat())
                            palette.lerpColor(((a + Math.PI.toFloat()) / (2f * Math.PI.toFloat())).coerceIn(0f, 1f))
                        }
                        "depth" -> palette.lerpColor((dep[idx].toFloat() / maxDep).coerceIn(0f, 1f))
                        "monochrome" -> mono
                        else -> palette.lerpColor(ord[idx].toFloat() / maxOrd)
                    }
                } else if (glowField != null && glowField[idx] < glowMax) {
                    val t = 1f - glowField[idx] / glowMax
                    val intensity = t * t * 0.35f
                    val c = palette.lerpColor(0.5f)
                    Color.rgb(
                        (Color.red(c) * intensity).toInt().coerceIn(0, 255),
                        (Color.green(c) * intensity).toInt().coerceIn(0, 255),
                        (Color.blue(c) * intensity).toInt().coerceIn(0, 255)
                    )
                } else {
                    Color.BLACK
                }
            }
        }

        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
        canvas.drawBitmap(bitmap, 0f, 0f, null)
    }
}
