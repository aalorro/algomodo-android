package com.artmondo.algomodo.generators.graphs

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.artmondo.algomodo.core.rng.SeededRNG
import com.artmondo.algomodo.core.rng.SimplexNoise
import com.artmondo.algomodo.data.palettes.Palette
import com.artmondo.algomodo.generators.Generator
import com.artmondo.algomodo.generators.ParamGroup
import com.artmondo.algomodo.generators.Parameter
import com.artmondo.algomodo.generators.Quality
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Ecosystem simulation generator.
 *
 * Simulates a simple predator-prey ecosystem with multiple species moving
 * around the canvas. Each species has distinct behaviours: prey flee from
 * predators, predators chase prey, and same-species flock together.
 * Trails can be drawn for organic motion patterns.
 */
class GraphEcosystemsGenerator : Generator {

    override val id = "graph-ecosystems"
    override val family = "graphs"
    override val styleName = "Ecosystems"
    override val definition =
        "Ecosystem simulation with multiple species of dots that move, interact, and leave trails, creating organic predator-prey motion patterns."
    override val algorithmNotes =
        "Each species is assigned a palette colour and a role in a circular food chain (species N eats species N-1). " +
        "Agents follow simple steering rules: flee from predators, chase nearby prey, and weakly flock with " +
        "same-species neighbours. Movement is integrated forward from a deterministic initial state. " +
        "Trails render past positions with fading alpha. Time controls the simulation progress."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema = listOf(
        Parameter.NumberParam("Initial Prey", "initialPrey", ParamGroup.COMPOSITION, null, 20f, 300f, 10f, 120f),
        Parameter.NumberParam("Initial Predators", "initialPredators", ParamGroup.COMPOSITION, null, 5f, 100f, 5f, 25f),
        Parameter.NumberParam("Prey Speed", "preySpeed", ParamGroup.FLOW_MOTION, null, 0.5f, 5f, 0.5f, 2f),
        Parameter.NumberParam("Predator Speed", "predatorSpeed", ParamGroup.FLOW_MOTION, null, 0.5f, 5f, 0.5f, 1.5f),
        Parameter.NumberParam("Flock Radius", "flockRadius", ParamGroup.GEOMETRY, "Max distance for same-species graph connections", 30f, 200f, 10f, 80f),
        Parameter.NumberParam("Hunt Radius", "huntRadius", ParamGroup.GEOMETRY, "Max distance for predator-prey connections", 40f, 250f, 10f, 120f),
        Parameter.NumberParam("Reproduction Rate", "reproductionRate", ParamGroup.TEXTURE, null, 0.001f, 0.02f, 0.001f, 0.005f),
        Parameter.NumberParam("Node Size", "nodeSize", ParamGroup.GEOMETRY, null, 2f, 16f, 1f, 6f),
        Parameter.NumberParam("Edge Width", "edgeWidth", ParamGroup.GEOMETRY, null, 0.5f, 4f, 0.5f, 1f),
        Parameter.SelectParam("Color Mode", "colorMode", ParamGroup.COLOR, null, listOf("species", "energy", "age", "connections"), "species"),
        Parameter.BooleanParam("Show Trails", "showTrails", ParamGroup.TEXTURE, null, true),
        Parameter.NumberParam("Steps / Frame", "stepsPerFrame", ParamGroup.FLOW_MOTION, null, 1f, 5f, 1f, 2f)
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "initialPrey" to 120f,
        "initialPredators" to 25f,
        "preySpeed" to 2f,
        "predatorSpeed" to 1.5f,
        "flockRadius" to 80f,
        "huntRadius" to 120f,
        "reproductionRate" to 0.005f,
        "nodeSize" to 6f,
        "edgeWidth" to 1f,
        "colorMode" to "species",
        "showTrails" to true,
        "stepsPerFrame" to 2f
    )

    private data class Agent(
        var x: Float,
        var y: Float,
        var vx: Float,
        var vy: Float,
        val species: Int,
        val trailX: FloatArray,
        val trailY: FloatArray,
        var trailHead: Int = 0,
        var trailCount: Int = 0
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
        val w = bitmap.width.toFloat()
        val h = bitmap.height.toFloat()
        val initialPrey = (params["initialPrey"] as? Number)?.toInt() ?: 120
        val initialPredators = (params["initialPredators"] as? Number)?.toInt() ?: 25
        val preySpeed = (params["preySpeed"] as? Number)?.toFloat() ?: 2f
        val predatorSpeed = (params["predatorSpeed"] as? Number)?.toFloat() ?: 1.5f
        val flockRadius = (params["flockRadius"] as? Number)?.toFloat() ?: 80f
        val huntRadius = (params["huntRadius"] as? Number)?.toFloat() ?: 120f
        val reproductionRate = (params["reproductionRate"] as? Number)?.toFloat() ?: 0.005f
        val nodeSize = (params["nodeSize"] as? Number)?.toFloat() ?: 6f
        val edgeWidth = (params["edgeWidth"] as? Number)?.toFloat() ?: 1f
        val colorMode = (params["colorMode"] as? String) ?: "species"
        val showTrails = params["showTrails"] as? Boolean ?: true
        val stepsPerFrame = (params["stepsPerFrame"] as? Number)?.toInt() ?: 2

        val rng = SeededRNG(seed)
        val colors = palette.colorInts()
        val trailLen = 30

        // Adjust population for quality
        val qualityMul = when (quality) {
            Quality.DRAFT -> 0.5f
            Quality.BALANCED -> 1f
            Quality.ULTRA -> 1.5f
        }
        val preyPop = (initialPrey * qualityMul).toInt()
        val predPop = (initialPredators * qualityMul).toInt()

        // Scale radii relative to canvas size
        val flockRadiusPx = flockRadius / 300f * w
        val huntRadiusPx = huntRadius / 300f * w
        val flockSq = flockRadiusPx * flockRadiusPx
        val huntSq = huntRadiusPx * huntRadiusPx

        // Initialize agents: prey (species 0) then predators (species 1)
        val agents = mutableListOf<Agent>()
        for (i in 0 until preyPop) {
            agents.add(Agent(
                x = rng.random() * w,
                y = rng.random() * h,
                vx = (rng.random() - 0.5f) * preySpeed * 2f,
                vy = (rng.random() - 0.5f) * preySpeed * 2f,
                species = 0,
                trailX = FloatArray(trailLen),
                trailY = FloatArray(trailLen)
            ))
        }
        for (i in 0 until predPop) {
            agents.add(Agent(
                x = rng.random() * w,
                y = rng.random() * h,
                vx = (rng.random() - 0.5f) * predatorSpeed * 2f,
                vy = (rng.random() - 0.5f) * predatorSpeed * 2f,
                species = 1,
                trailX = FloatArray(trailLen),
                trailY = FloatArray(trailLen)
            ))
        }

        // Simulate
        val dt = 0.016f
        val totalSteps = ((time / dt) * stepsPerFrame).toInt().coerceAtMost(3000)
        val maxPop = (preyPop + predPop) * 3

        for (step in 0 until totalSteps) {
            for (agent in agents) {
                var fleeX = 0f; var fleeY = 0f
                var chaseX = 0f; var chaseY = 0f
                var flockX = 0f; var flockY = 0f
                var fleeCount = 0; var chaseCount = 0; var flockCount = 0

                val isPredator = agent.species == 1
                val agentSpeed = if (isPredator) predatorSpeed else preySpeed

                for (other in agents) {
                    if (other === agent) continue
                    val dx = other.x - agent.x
                    val dy = other.y - agent.y
                    val distSq = dx * dx + dy * dy
                    if (distSq < 0.01f) continue

                    if (other.species == agent.species) {
                        // Flock with same species
                        if (distSq <= flockSq) {
                            flockX += other.x
                            flockY += other.y
                            flockCount++
                        }
                    } else if (distSq <= huntSq) {
                        val dist = sqrt(distSq)
                        if (isPredator) {
                            // Predator chases prey
                            chaseX += dx / dist
                            chaseY += dy / dist
                            chaseCount++
                        } else {
                            // Prey flees from predator
                            fleeX -= dx / dist
                            fleeY -= dy / dist
                            fleeCount++
                        }
                    }
                }

                // Steering forces
                var ax = 0f; var ay = 0f

                if (fleeCount > 0) {
                    ax += fleeX / fleeCount * agentSpeed * 3f
                    ay += fleeY / fleeCount * agentSpeed * 3f
                }
                if (chaseCount > 0) {
                    ax += chaseX / chaseCount * agentSpeed * 2f
                    ay += chaseY / chaseCount * agentSpeed * 2f
                }
                if (flockCount > 0) {
                    val avgX = flockX / flockCount
                    val avgY = flockY / flockCount
                    ax += (avgX - agent.x) * 0.001f * agentSpeed
                    ay += (avgY - agent.y) * 0.001f * agentSpeed
                }

                // Update velocity
                agent.vx += ax * dt
                agent.vy += ay * dt

                // Limit speed
                val spd = sqrt(agent.vx * agent.vx + agent.vy * agent.vy)
                val maxSpeed = agentSpeed * 3f
                if (spd > maxSpeed) {
                    agent.vx = agent.vx / spd * maxSpeed
                    agent.vy = agent.vy / spd * maxSpeed
                }

                // Update position
                agent.x += agent.vx
                agent.y += agent.vy

                // Wrap
                if (agent.x < 0) agent.x += w
                if (agent.x >= w) agent.x -= w
                if (agent.y < 0) agent.y += h
                if (agent.y >= h) agent.y -= h

                // Record trail
                if (showTrails) {
                    agent.trailX[agent.trailHead] = agent.x
                    agent.trailY[agent.trailHead] = agent.y
                    agent.trailHead = (agent.trailHead + 1) % trailLen
                    if (agent.trailCount < trailLen) agent.trailCount++
                }
            }

            // Reproduction every 50 steps
            if (step % 50 == 0 && agents.size < maxPop) {
                val newAgents = mutableListOf<Agent>()
                for (agent in agents) {
                    if (agents.size + newAgents.size >= maxPop) break
                    if (rng.random() < reproductionRate) {
                        val spd = if (agent.species == 0) preySpeed else predatorSpeed
                        newAgents.add(Agent(
                            x = agent.x + (rng.random() - 0.5f) * 10f,
                            y = agent.y + (rng.random() - 0.5f) * 10f,
                            vx = (rng.random() - 0.5f) * spd * 2f,
                            vy = (rng.random() - 0.5f) * spd * 2f,
                            species = agent.species,
                            trailX = FloatArray(trailLen),
                            trailY = FloatArray(trailLen)
                        ))
                    }
                }
                agents.addAll(newAgents)
            }
        }

        // Pre-compute agent colors
        val agentColors = IntArray(agents.size)
        when (colorMode) {
            "energy" -> {
                for (i in agents.indices) {
                    val a = agents[i]
                    val spd = sqrt(a.vx * a.vx + a.vy * a.vy)
                    val t = (spd / 8f).coerceIn(0f, 1f)
                    agentColors[i] = palette.lerpColor(t)
                }
            }
            "age" -> {
                for (i in agents.indices) {
                    val t = (agents[i].trailCount.toFloat() / trailLen).coerceIn(0f, 1f)
                    agentColors[i] = palette.lerpColor(t)
                }
            }
            "connections" -> {
                val counts = IntArray(agents.size)
                for (i in agents.indices) {
                    for (j in i + 1 until agents.size) {
                        if (agents[j].species != agents[i].species) continue
                        val dx = agents[j].x - agents[i].x
                        val dy = agents[j].y - agents[i].y
                        if (dx * dx + dy * dy <= flockSq) {
                            counts[i]++
                            counts[j]++
                        }
                    }
                }
                val maxCount = counts.maxOrNull()?.coerceAtLeast(1) ?: 1
                for (i in agents.indices) {
                    agentColors[i] = palette.lerpColor(counts[i].toFloat() / maxCount)
                }
            }
            else -> { // "species"
                for (i in agents.indices) {
                    agentColors[i] = colors[agents[i].species % colors.size]
                }
            }
        }

        // Render
        canvas.drawColor(Color.BLACK)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val scaledEdgeWidth = edgeWidth * w / 360f

        if (showTrails) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = scaledEdgeWidth
            paint.strokeCap = Paint.Cap.ROUND

            for ((idx, agent) in agents.withIndex()) {
                if (agent.trailCount < 2) continue
                val baseColor = agentColors[idx]

                for (k in 1 until agent.trailCount) {
                    val prevIdx = if (agent.trailCount < trailLen) k - 1
                    else (agent.trailHead + k - 1 + trailLen) % trailLen
                    val curIdx = if (agent.trailCount < trailLen) k
                    else (agent.trailHead + k) % trailLen

                    val dx = agent.trailX[curIdx] - agent.trailX[prevIdx]
                    val dy = agent.trailY[curIdx] - agent.trailY[prevIdx]
                    if (dx * dx + dy * dy > (w * 0.25f) * (w * 0.25f)) continue

                    val alpha = (k.toFloat() / agent.trailCount * 180).toInt().coerceIn(10, 180)
                    paint.color = Color.argb(alpha, Color.red(baseColor), Color.green(baseColor), Color.blue(baseColor))
                    canvas.drawLine(
                        agent.trailX[prevIdx], agent.trailY[prevIdx],
                        agent.trailX[curIdx], agent.trailY[curIdx],
                        paint
                    )
                }
            }
        }

        // Draw agents as dots
        paint.style = Paint.Style.FILL
        val dotRadius = nodeSize * w / 360f * 0.5f

        for ((idx, agent) in agents.withIndex()) {
            paint.color = agentColors[idx]
            canvas.drawCircle(agent.x, agent.y, dotRadius, paint)
        }
    }

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float {
        val pop = (params["initialPrey"] as? Number)?.toFloat() ?: 120f
        return (pop / 500f).coerceIn(0.3f, 1f)
    }
}
