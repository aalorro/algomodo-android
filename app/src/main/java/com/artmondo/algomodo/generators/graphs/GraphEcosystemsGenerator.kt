package com.artmondo.algomodo.generators.graphs

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.artmondo.algomodo.core.rng.SeededRNG
import com.artmondo.algomodo.data.palettes.Palette
import com.artmondo.algomodo.generators.Generator
import com.artmondo.algomodo.generators.ParamGroup
import com.artmondo.algomodo.generators.Parameter
import com.artmondo.algomodo.generators.Quality
import kotlin.math.sqrt

/**
 * Ecosystem simulation generator.
 *
 * Simulates a multi-species ecosystem with a circular food chain: each species
 * hunts the previous one and flees the next. Agents steer via flee, chase,
 * flock, and separation forces. Predation removes prey on contact.
 * Five visual styles produce distinct aesthetics from the same simulation.
 */
class GraphEcosystemsGenerator : Generator {

    override val id = "graph-ecosystems"
    override val family = "graphs"
    override val styleName = "Ecosystems"
    override val definition =
        "Multi-species ecosystem with circular food chains, predation, flocking, and five visual styles."
    override val algorithmNotes =
        "N species form a circular food chain: species i hunts (i-1)%N and flees (i+1)%N. " +
        "Agents steer via flee/chase/flock/separation forces. Prey consumed on contact. " +
        "Styles: dots (classic trails), network (dynamic graph), trails-only (organic line art), " +
        "glow (bioluminescent blobs), heatmap (population density field)."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema = listOf(
        Parameter.NumberParam("Population", "population", ParamGroup.COMPOSITION,
            "Total agents split evenly across species", 50f, 400f, 10f, 150f),
        Parameter.NumberParam("Species", "speciesCount", ParamGroup.COMPOSITION,
            "Species in the circular food chain — each hunts the previous", 2f, 5f, 1f, 3f),
        Parameter.SelectParam("Style", "style", ParamGroup.TEXTURE,
            "dots: classic trails | network: dynamic graph | trails-only: organic line art | glow: bioluminescent blobs | heatmap: density field",
            listOf("dots", "network", "trails-only", "glow", "heatmap"), "dots"),
        Parameter.NumberParam("Speed", "speed", ParamGroup.FLOW_MOTION,
            "Base movement speed for all species", 0.5f, 5f, 0.5f, 2f),
        Parameter.NumberParam("Hunt Radius", "huntRadius", ParamGroup.GEOMETRY,
            "Max distance for predator-prey interactions", 40f, 250f, 10f, 120f),
        Parameter.NumberParam("Flock Radius", "flockRadius", ParamGroup.GEOMETRY,
            "Max distance for same-species flocking", 30f, 200f, 10f, 80f),
        Parameter.NumberParam("Aggression", "aggression", ParamGroup.FLOW_MOTION,
            "Chase strength — higher = faster pursuit, more dramatic hunts", 0.5f, 5f, 0.5f, 2f),
        Parameter.NumberParam("Node Size", "nodeSize", ParamGroup.GEOMETRY, null, 2f, 16f, 1f, 6f),
        Parameter.NumberParam("Trail Length", "trailLength", ParamGroup.TEXTURE,
            "Past positions stored per agent", 10f, 80f, 5f, 30f),
        Parameter.SelectParam("Color Mode", "colorMode", ParamGroup.COLOR,
            "species: by type | energy: by speed | density: by local crowd",
            listOf("species", "energy", "density"), "species"),
        Parameter.NumberParam("Steps / Frame", "stepsPerFrame", ParamGroup.FLOW_MOTION, null, 1f, 5f, 1f, 2f)
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "population" to 150f,
        "speciesCount" to 3f,
        "style" to "dots",
        "speed" to 2f,
        "huntRadius" to 120f,
        "flockRadius" to 80f,
        "aggression" to 2f,
        "nodeSize" to 6f,
        "trailLength" to 30f,
        "colorMode" to "species",
        "stepsPerFrame" to 2f
    )

    private class Agent(
        var x: Float, var y: Float,
        var vx: Float, var vy: Float,
        val species: Int,
        var alive: Boolean = true,
        val trailX: FloatArray, val trailY: FloatArray,
        val trailSpd: FloatArray,
        var trailHead: Int = 0, var trailCount: Int = 0
    )

    override fun renderCanvas(
        canvas: Canvas, bitmap: Bitmap,
        params: Map<String, Any>, seed: Int,
        palette: Palette, quality: Quality, time: Float
    ) {
        val w = bitmap.width.toFloat()
        val h = bitmap.height.toFloat()
        val bw = bitmap.width; val bh = bitmap.height
        val population = (params["population"] as? Number)?.toInt() ?: 150
        val speciesCount = (params["speciesCount"] as? Number)?.toInt()?.coerceIn(2, 5) ?: 3
        val style = (params["style"] as? String) ?: "dots"
        val speed = (params["speed"] as? Number)?.toFloat() ?: 2f
        val flockRadius = (params["flockRadius"] as? Number)?.toFloat() ?: 80f
        val huntRadius = (params["huntRadius"] as? Number)?.toFloat() ?: 120f
        val aggression = (params["aggression"] as? Number)?.toFloat() ?: 2f
        val nodeSize = (params["nodeSize"] as? Number)?.toFloat() ?: 6f
        val trailLen = (params["trailLength"] as? Number)?.toInt()?.coerceIn(5, 80) ?: 30
        val colorMode = (params["colorMode"] as? String) ?: "species"
        val stepsPerFrame = (params["stepsPerFrame"] as? Number)?.toInt() ?: 2

        val rng = SeededRNG(seed)

        val qMul = when (quality) {
            Quality.DRAFT -> 0.5f; Quality.BALANCED -> 1f; Quality.ULTRA -> 1.5f
        }
        val totalPop = (population * qMul).toInt()
        val perSpecies = (totalPop / speciesCount).coerceAtLeast(5)

        val flockPx = flockRadius / 300f * w
        val huntPx = huntRadius / 300f * w
        val flockSq = flockPx * flockPx
        val huntSq = huntPx * huntPx
        val killSq = (huntPx * 0.12f).let { it * it }
        val sepDist = flockPx * 0.25f
        val sepSq = sepDist * sepDist

        // --- Initialize agents ---

        val agents = mutableListOf<Agent>()
        for (s in 0 until speciesCount) {
            repeat(perSpecies) {
                agents.add(Agent(
                    x = rng.random() * w, y = rng.random() * h,
                    vx = (rng.random() - 0.5f) * speed * 2f,
                    vy = (rng.random() - 0.5f) * speed * 2f,
                    species = s,
                    trailX = FloatArray(trailLen), trailY = FloatArray(trailLen),
                    trailSpd = FloatArray(trailLen)
                ))
            }
        }

        // --- Simulate ---

        val dt = 0.016f
        val totalSteps = ((time / dt) * stepsPerFrame).toInt().coerceAtMost(3000)
        val maxPop = totalPop * 3

        for (step in 0 until totalSteps) {
            for (agent in agents) {
                if (!agent.alive) continue
                val preyOf = (agent.species - 1 + speciesCount) % speciesCount
                val predOf = (agent.species + 1) % speciesCount

                var fleeX = 0f; var fleeY = 0f; var fleeN = 0
                var chaseX = 0f; var chaseY = 0f; var chaseN = 0
                var flockX = 0f; var flockY = 0f; var flockN = 0
                var sepX = 0f; var sepY = 0f; var sepN = 0

                for (other in agents) {
                    if (!other.alive || other === agent) continue
                    val dx = other.x - agent.x; val dy = other.y - agent.y
                    val dSq = dx * dx + dy * dy
                    if (dSq < 0.01f) continue

                    when (other.species) {
                        agent.species -> {
                            if (dSq < sepSq) {
                                val d = sqrt(dSq)
                                sepX -= dx / d; sepY -= dy / d; sepN++
                            }
                            if (dSq <= flockSq) {
                                flockX += other.x; flockY += other.y; flockN++
                            }
                        }
                        predOf -> if (dSq <= huntSq) {
                            val d = sqrt(dSq)
                            fleeX -= dx / d; fleeY -= dy / d; fleeN++
                        }
                        preyOf -> if (dSq <= huntSq) {
                            val d = sqrt(dSq)
                            chaseX += dx / d; chaseY += dy / d; chaseN++
                            if (dSq < killSq) other.alive = false
                        }
                    }
                }

                var ax = 0f; var ay = 0f
                if (fleeN > 0) { ax += fleeX / fleeN * speed * 3f; ay += fleeY / fleeN * speed * 3f }
                if (chaseN > 0) { ax += chaseX / chaseN * speed * aggression; ay += chaseY / chaseN * speed * aggression }
                if (flockN > 0) {
                    ax += (flockX / flockN - agent.x) * 0.002f * speed
                    ay += (flockY / flockN - agent.y) * 0.002f * speed
                }
                if (sepN > 0) { ax += sepX / sepN * speed * 2f; ay += sepY / sepN * speed * 2f }

                agent.vx += ax * dt; agent.vy += ay * dt
                val spd = sqrt(agent.vx * agent.vx + agent.vy * agent.vy)
                val maxSpd = speed * 3f
                if (spd > maxSpd) { agent.vx = agent.vx / spd * maxSpd; agent.vy = agent.vy / spd * maxSpd }

                agent.x += agent.vx; agent.y += agent.vy
                if (agent.x < 0) agent.x += w; if (agent.x >= w) agent.x -= w
                if (agent.y < 0) agent.y += h; if (agent.y >= h) agent.y -= h

                val curSpd = sqrt(agent.vx * agent.vx + agent.vy * agent.vy)
                agent.trailX[agent.trailHead] = agent.x
                agent.trailY[agent.trailHead] = agent.y
                agent.trailSpd[agent.trailHead] = curSpd
                agent.trailHead = (agent.trailHead + 1) % trailLen
                if (agent.trailCount < trailLen) agent.trailCount++
            }

            agents.removeAll { !it.alive }

            if (step % 60 == 0 && agents.size < maxPop) {
                val born = mutableListOf<Agent>()
                for (a in agents) {
                    if (agents.size + born.size >= maxPop) break
                    if (rng.random() < 0.006f) {
                        born.add(Agent(
                            x = a.x + (rng.random() - 0.5f) * 10f,
                            y = a.y + (rng.random() - 0.5f) * 10f,
                            vx = (rng.random() - 0.5f) * speed * 2f,
                            vy = (rng.random() - 0.5f) * speed * 2f,
                            species = a.species,
                            trailX = FloatArray(trailLen), trailY = FloatArray(trailLen),
                            trailSpd = FloatArray(trailLen)
                        ))
                    }
                }
                agents.addAll(born)
            }
        }

        // --- Compute colors ---

        val agentColors = IntArray(agents.size)
        val maxSpeciesIdx = (speciesCount - 1).coerceAtLeast(1).toFloat()
        when (colorMode) {
            "energy" -> for (i in agents.indices) {
                val a = agents[i]
                val s = sqrt(a.vx * a.vx + a.vy * a.vy)
                agentColors[i] = palette.lerpColor((s / (speed * 3f)).coerceIn(0f, 1f))
            }
            "density" -> {
                val cnt = IntArray(agents.size)
                for (i in agents.indices) for (j in i + 1 until agents.size) {
                    val dx = agents[j].x - agents[i].x; val dy = agents[j].y - agents[i].y
                    if (dx * dx + dy * dy <= flockSq) { cnt[i]++; cnt[j]++ }
                }
                val mx = cnt.maxOrNull()?.coerceAtLeast(1) ?: 1
                for (i in agents.indices) agentColors[i] = palette.lerpColor(cnt[i].toFloat() / mx)
            }
            else -> for (i in agents.indices)
                agentColors[i] = palette.lerpColor(agents[i].species.toFloat() / maxSpeciesIdx)
        }

        // --- Render ---

        canvas.drawColor(Color.BLACK)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val scale = w / 360f
        val dotR = nodeSize * scale * 0.5f

        when (style) {
            "network" -> {
                // Draw edges first
                paint.style = Paint.Style.STROKE
                paint.strokeCap = Paint.Cap.ROUND

                // Flock edges (same species, within flock radius)
                for (i in agents.indices) {
                    val a = agents[i]
                    for (j in i + 1 until agents.size) {
                        val b = agents[j]
                        if (b.species != a.species) continue
                        val dx = b.x - a.x; val dy = b.y - a.y
                        val dSq = dx * dx + dy * dy
                        if (dSq > flockSq || dSq > (w * 0.25f).let { it * it }) continue
                        val t = 1f - sqrt(dSq) / flockPx
                        val alpha = (t * 100).toInt().coerceIn(5, 100)
                        val c = agentColors[i]
                        paint.color = Color.argb(alpha, Color.red(c), Color.green(c), Color.blue(c))
                        paint.strokeWidth = scale * 0.8f
                        canvas.drawLine(a.x, a.y, b.x, b.y, paint)
                    }
                }

                // Hunt edges (predator→prey, within hunt radius)
                for (i in agents.indices) {
                    val a = agents[i]
                    val preyOf = (a.species - 1 + speciesCount) % speciesCount
                    for (j in agents.indices) {
                        if (i == j) continue
                        val b = agents[j]
                        if (b.species != preyOf) continue
                        val dx = b.x - a.x; val dy = b.y - a.y
                        val dSq = dx * dx + dy * dy
                        if (dSq > huntSq || dSq > (w * 0.25f).let { it * it }) continue
                        val t = 1f - sqrt(dSq) / huntPx
                        val alpha = (t * 60).toInt().coerceIn(5, 60)
                        paint.color = Color.argb(alpha, 255, 60, 60)
                        paint.strokeWidth = scale * 0.5f
                        canvas.drawLine(a.x, a.y, b.x, b.y, paint)
                    }
                }

                // Dots on top
                paint.style = Paint.Style.FILL
                for (i in agents.indices) {
                    paint.color = agentColors[i]
                    canvas.drawCircle(agents[i].x, agents[i].y, dotR, paint)
                }
            }

            "trails-only" -> {
                paint.style = Paint.Style.STROKE
                paint.strokeCap = Paint.Cap.ROUND
                for (idx in agents.indices) {
                    val a = agents[idx]
                    if (a.trailCount < 2) continue
                    val baseColor = agentColors[idx]
                    for (k in 1 until a.trailCount) {
                        val pi = if (a.trailCount < trailLen) k - 1 else (a.trailHead + k - 1 + trailLen) % trailLen
                        val ci = if (a.trailCount < trailLen) k else (a.trailHead + k) % trailLen
                        val dx = a.trailX[ci] - a.trailX[pi]; val dy = a.trailY[ci] - a.trailY[pi]
                        if (dx * dx + dy * dy > (w * 0.25f).let { it * it }) continue
                        val progress = k.toFloat() / a.trailCount
                        val alpha = (progress * 220).toInt().coerceIn(10, 220)
                        paint.color = Color.argb(alpha, Color.red(baseColor), Color.green(baseColor), Color.blue(baseColor))
                        // Width varies by speed
                        val spd = a.trailSpd[ci]
                        val widthMul = 0.5f + (spd / (speed * 3f)).coerceIn(0f, 1f) * 1.5f
                        paint.strokeWidth = scale * 2f * widthMul
                        canvas.drawLine(a.trailX[pi], a.trailY[pi], a.trailX[ci], a.trailY[ci], paint)
                    }
                }
            }

            "glow" -> {
                paint.style = Paint.Style.FILL
                val glowR = dotR * 6f
                for (idx in agents.indices) {
                    val a = agents[idx]
                    val c = agentColors[idx]
                    val r = Color.red(c); val g = Color.green(c); val b2 = Color.blue(c)
                    // Draw concentric circles with decreasing alpha for glow
                    for (ring in 4 downTo 0) {
                        val frac = ring / 4f
                        val radius = glowR * (1f - frac * 0.7f)
                        val alpha = (8 + frac * 35).toInt().coerceIn(5, 45)
                        paint.color = Color.argb(alpha, r, g, b2)
                        canvas.drawCircle(a.x, a.y, radius, paint)
                    }
                    // Bright core
                    paint.color = Color.argb(200, r, g, b2)
                    canvas.drawCircle(a.x, a.y, dotR * 0.5f, paint)
                }
            }

            "heatmap" -> {
                val gridW = 40; val gridH = 40
                val density = FloatArray(gridW * gridH)
                val speciesGrid = IntArray(gridW * gridH) { -1 }
                val speciesDensity = FloatArray(gridW * gridH)

                for (a in agents) {
                    val gx = (a.x / w * gridW).toInt().coerceIn(0, gridW - 1)
                    val gy = (a.y / h * gridH).toInt().coerceIn(0, gridH - 1)
                    val idx = gy * gridW + gx
                    density[idx] += 1f
                    if (density[idx] > speciesDensity[idx] || speciesGrid[idx] == a.species) {
                        speciesGrid[idx] = a.species
                        speciesDensity[idx] = density[idx]
                    }
                }

                // Simple blur pass
                val blurred = FloatArray(gridW * gridH)
                val blurSpec = IntArray(gridW * gridH)
                for (gy in 0 until gridH) for (gx in 0 until gridW) {
                    var sum = 0f; var cnt = 0; var bestSpec = 0; var bestD = 0f
                    for (dy in -1..1) for (dx in -1..1) {
                        val nx = gx + dx; val ny = gy + dy
                        if (nx in 0 until gridW && ny in 0 until gridH) {
                            val idx = ny * gridW + nx
                            sum += density[idx]; cnt++
                            if (density[idx] > bestD) { bestD = density[idx]; bestSpec = speciesGrid[idx] }
                        }
                    }
                    val idx = gy * gridW + gx
                    blurred[idx] = sum / cnt
                    blurSpec[idx] = if (bestSpec >= 0) bestSpec else 0
                }

                var maxD = 1f
                for (d in blurred) if (d > maxD) maxD = d

                val pixels = IntArray(bw * bh)
                for (py in 0 until bh) {
                    val gy = (py.toFloat() / bh * gridH).toInt().coerceAtMost(gridH - 1)
                    val rowOff = py * bw
                    for (px in 0 until bw) {
                        val gx = (px.toFloat() / bw * gridW).toInt().coerceAtMost(gridW - 1)
                        val idx = gy * gridW + gx
                        val d = blurred[idx]
                        pixels[rowOff + px] = if (d > 0.05f) {
                            val intensity = (d / maxD).coerceIn(0f, 1f)
                            val specT = blurSpec[idx].toFloat() / maxSpeciesIdx
                            // Blend species color with intensity
                            val baseColor = palette.lerpColor(specT)
                            val br = (Color.red(baseColor) * intensity).toInt().coerceIn(0, 255)
                            val bg = (Color.green(baseColor) * intensity).toInt().coerceIn(0, 255)
                            val bb = (Color.blue(baseColor) * intensity).toInt().coerceIn(0, 255)
                            Color.rgb(br, bg, bb)
                        } else Color.BLACK
                    }
                }
                bitmap.setPixels(pixels, 0, bw, 0, 0, bw, bh)
                canvas.drawBitmap(bitmap, 0f, 0f, null)
            }

            else -> { // "dots"
                val edgeW = scale

                // Trails
                paint.style = Paint.Style.STROKE
                paint.strokeCap = Paint.Cap.ROUND
                for (idx in agents.indices) {
                    val a = agents[idx]
                    if (a.trailCount < 2) continue
                    val baseColor = agentColors[idx]
                    for (k in 1 until a.trailCount) {
                        val pi = if (a.trailCount < trailLen) k - 1 else (a.trailHead + k - 1 + trailLen) % trailLen
                        val ci = if (a.trailCount < trailLen) k else (a.trailHead + k) % trailLen
                        val dx = a.trailX[ci] - a.trailX[pi]; val dy = a.trailY[ci] - a.trailY[pi]
                        if (dx * dx + dy * dy > (w * 0.25f).let { it * it }) continue
                        val alpha = (k.toFloat() / a.trailCount * 180).toInt().coerceIn(10, 180)
                        paint.color = Color.argb(alpha, Color.red(baseColor), Color.green(baseColor), Color.blue(baseColor))
                        paint.strokeWidth = edgeW
                        canvas.drawLine(a.trailX[pi], a.trailY[pi], a.trailX[ci], a.trailY[ci], paint)
                    }
                }

                // Dots
                paint.style = Paint.Style.FILL
                for (i in agents.indices) {
                    paint.color = agentColors[i]
                    canvas.drawCircle(agents[i].x, agents[i].y, dotR, paint)
                }
            }
        }
    }

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float {
        val pop = (params["population"] as? Number)?.toFloat() ?: 150f
        return (pop / 500f).coerceIn(0.3f, 1f)
    }
}
