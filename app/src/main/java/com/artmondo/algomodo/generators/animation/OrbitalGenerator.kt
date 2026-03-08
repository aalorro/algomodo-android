package com.artmondo.algomodo.generators.animation

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import com.artmondo.algomodo.core.rng.SeededRNG
import com.artmondo.algomodo.data.palettes.Palette
import com.artmondo.algomodo.generators.Generator
import com.artmondo.algomodo.generators.ParamGroup
import com.artmondo.algomodo.generators.Parameter
import com.artmondo.algomodo.generators.Quality
import kotlin.math.*

/**
 * Orbital mechanics visualisation — N-body gravitational simulation.
 *
 * A central star (or binary pair) anchors the system. Planets orbit at seeded
 * distances with optional moons. The simulation uses Velocity Verlet integration.
 * Each body draws a coloured trail showing its past trajectory.
 */
class OrbitalGenerator : Generator {

    override val id = "orbital"
    override val family = "animation"
    override val styleName = "Orbital"
    override val definition = "N-body gravitational simulation where each body leaves a coloured trail."
    override val algorithmNotes =
        "A central star with large mass anchors the system. Planets are placed at specified " +
        "orbital radii with tangential velocity v = sqrt(G*M_star/r) * (1 + eccentricity). " +
        "Moons orbit their parent planet. The system is integrated using Velocity Verlet: " +
        "a_i = sum_{j!=i} G*m_j*(r_j - r_i) / |r_j - r_i|^3. Trail positions are stored in " +
        "a ring buffer and drawn as polylines with fading alpha."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema = listOf(
        Parameter.NumberParam(
            name = "Planets",
            key = "bodyCount",
            group = ParamGroup.COMPOSITION,
            help = "Number of planetary bodies",
            min = 1f, max = 14f, step = 1f, default = 5f
        ),
        Parameter.NumberParam(
            name = "Moon Chance",
            key = "moonChance",
            group = ParamGroup.COMPOSITION,
            help = "Probability that each planet hosts a moon",
            min = 0f, max = 1f, step = 0.1f, default = 0.4f
        ),
        Parameter.SelectParam(
            name = "Style",
            key = "orbitStyle",
            group = ParamGroup.COMPOSITION,
            help = "solar: single star system; binary: twin stars orbiting each other",
            options = listOf("solar", "binary"),
            default = "solar"
        ),
        Parameter.NumberParam(
            name = "Speed",
            key = "speed",
            group = ParamGroup.FLOW_MOTION,
            help = "Orbital speed multiplier",
            min = 0.1f, max = 5f, step = 0.1f, default = 1f
        ),
        Parameter.NumberParam(
            name = "Eccentricity",
            key = "eccentricity",
            group = ParamGroup.GEOMETRY,
            help = "0 = circular orbits; higher = elongated elliptical",
            min = 0f, max = 0.75f, step = 0.05f, default = 0.2f
        ),
        Parameter.NumberParam(
            name = "Inner Orbit",
            key = "minRadius",
            group = ParamGroup.GEOMETRY,
            help = null,
            min = 30f, max = 250f, step = 10f, default = 80f
        ),
        Parameter.NumberParam(
            name = "Outer Orbit",
            key = "maxRadius",
            group = ParamGroup.GEOMETRY,
            help = null,
            min = 100f, max = 600f, step = 10f, default = 380f
        ),
        Parameter.NumberParam(
            name = "Body Size",
            key = "bodySize",
            group = ParamGroup.GEOMETRY,
            help = null,
            min = 2f, max = 24f, step = 1f, default = 9f
        ),
        Parameter.NumberParam(
            name = "Glow",
            key = "glowIntensity",
            group = ParamGroup.TEXTURE,
            help = "Intensity of glow halos on stars and planets",
            min = 0f, max = 1f, step = 0.05f, default = 0.65f
        ),
        Parameter.NumberParam(
            name = "Trail",
            key = "trailLength",
            group = ParamGroup.TEXTURE,
            help = "Fraction of orbit arc shown as a fading trail behind each body",
            min = 0f, max = 1f, step = 0.05f, default = 0.4f
        )
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "bodyCount" to 5f,
        "moonChance" to 0.4f,
        "orbitStyle" to "solar",
        "speed" to 1f,
        "eccentricity" to 0.2f,
        "minRadius" to 80f,
        "maxRadius" to 380f,
        "bodySize" to 9f,
        "glowIntensity" to 0.65f,
        "trailLength" to 0.4f
    )

    private data class Body(
        var x: Double, var y: Double,
        var vx: Double, var vy: Double,
        val mass: Double,
        val color: Int,
        val radius: Float,
        val isStar: Boolean = false,
        val isMoon: Boolean = false
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
        val dim = min(w, h)
        val centerX = w / 2.0
        val centerY = h / 2.0

        val planetCount = (params["bodyCount"] as? Number)?.toInt() ?: 5
        val moonChance = (params["moonChance"] as? Number)?.toFloat() ?: 0.4f
        val orbitStyle = (params["orbitStyle"] as? String) ?: "solar"
        val trailLengthFraction = (params["trailLength"] as? Number)?.toFloat() ?: 0.4f
        val showTrails = trailLengthFraction > 0f
        val trailLen = (trailLengthFraction * 600).toInt().coerceAtLeast(20)
        val speed = (params["speed"] as? Number)?.toFloat() ?: 1f
        val eccentricity = (params["eccentricity"] as? Number)?.toFloat() ?: 0.2f
        val minRadius = (params["minRadius"] as? Number)?.toFloat() ?: 80f
        val maxRadius = (params["maxRadius"] as? Number)?.toFloat() ?: 380f
        val bodySize = (params["bodySize"] as? Number)?.toFloat() ?: 9f
        val glowIntensity = (params["glowIntensity"] as? Number)?.toFloat() ?: 0.65f

        val rng = SeededRNG(seed)
        val colors = palette.colorInts()

        // Background — deep space
        canvas.drawColor(Color.rgb(2, 2, 6))

        // Scale radii to canvas
        val radiusScale = dim / 810f  // normalize to reference size
        val scaledMin = minRadius * radiusScale
        val scaledMax = maxRadius * radiusScale

        // Gravitational constant scaled to canvas
        val starMass = 500.0
        val G = 1.0
        val softening = dim * 0.01

        val bodies = mutableListOf<Body>()

        // Star color — bright warm color
        val starColor = Color.rgb(255, 240, 200)

        if (orbitStyle == "binary") {
            // Binary: two stars orbiting center
            val binaryDist = scaledMin * 0.6
            val binaryMass = starMass * 0.5
            val binarySpeed = sqrt(G * binaryMass / binaryDist) * 0.5

            bodies.add(Body(
                centerX - binaryDist, centerY,
                0.0, -binarySpeed,
                binaryMass, starColor, bodySize * 1.3f, isStar = true
            ))
            bodies.add(Body(
                centerX + binaryDist, centerY,
                0.0, binarySpeed,
                binaryMass, Color.rgb(200, 220, 255), bodySize * 1.1f, isStar = true
            ))
        } else {
            // Solar: single central star, fixed in place (massive)
            bodies.add(Body(
                centerX, centerY,
                0.0, 0.0,
                starMass, starColor, bodySize * 1.5f, isStar = true
            ))
        }

        // Place planets at evenly spaced orbital radii
        val starCount = bodies.size
        for (i in 0 until planetCount) {
            val t = if (planetCount == 1) 0.5f else i.toFloat() / (planetCount - 1).toFloat()
            val orbitRadius = (scaledMin + t * (scaledMax - scaledMin)).toDouble()
            val angle = rng.random().toDouble() * 2.0 * PI

            val px = centerX + orbitRadius * cos(angle)
            val py = centerY + orbitRadius * sin(angle)

            // Keplerian velocity for stable orbit + eccentricity perturbation
            val totalStarMass = if (orbitStyle == "binary") starMass else starMass
            val orbitalV = sqrt(G * totalStarMass / orbitRadius)
            val eccFactor = 1.0 + eccentricity * (rng.random().toDouble() * 2.0 - 1.0)

            // Tangential velocity (perpendicular to radius)
            val vx = -sin(angle) * orbitalV * eccFactor
            val vy = cos(angle) * orbitalV * eccFactor

            val planetMass = 0.1 + rng.random().toDouble() * 0.5
            val planetRadius = bodySize * (0.5f + rng.random() * 0.5f)
            val color = colors[(i + 1) % colors.size]

            bodies.add(Body(px, py, vx, vy, planetMass, color, planetRadius))

            // Maybe add a moon
            if (rng.random() < moonChance) {
                val moonDist = planetRadius * 4.0 + rng.random() * planetRadius * 3.0
                val moonAngle = rng.random().toDouble() * 2.0 * PI
                val moonV = sqrt(G * planetMass * 20.0 / moonDist) // boosted for visible orbit
                val mx = px + moonDist * cos(moonAngle)
                val my = py + moonDist * sin(moonAngle)
                // Moon velocity = planet velocity + orbital velocity around planet
                val mvx = vx + (-sin(moonAngle) * moonV)
                val mvy = vy + (cos(moonAngle) * moonV)

                bodies.add(Body(
                    mx, my, mvx, mvy,
                    0.01, color, bodySize * 0.25f, isMoon = true
                ))
            }
        }

        val bodyCount = bodies.size

        // Simulation
        val dt = 0.08 * speed
        val totalSteps = (time / 0.016f).toInt().coerceAtLeast(1)
        val maxSteps = trailLen * 3
        val actualSteps = totalSteps.coerceAtMost(maxSteps)

        // Trail storage
        val trailsX = Array(bodyCount) { FloatArray(trailLen) }
        val trailsY = Array(bodyCount) { FloatArray(trailLen) }
        val trailHeads = IntArray(bodyCount)
        val trailCounts = IntArray(bodyCount)

        fun computeAccel(): Array<Pair<Double, Double>> {
            val accel = Array(bodyCount) { Pair(0.0, 0.0) }
            for (i in 0 until bodyCount) {
                var ax = 0.0
                var ay = 0.0
                for (j in 0 until bodyCount) {
                    if (i == j) continue
                    val dx = bodies[j].x - bodies[i].x
                    val dy = bodies[j].y - bodies[i].y
                    val distSq = dx * dx + dy * dy + softening * softening
                    val dist = sqrt(distSq)
                    val force = G * bodies[j].mass / distSq
                    ax += force * dx / dist
                    ay += force * dy / dist
                }
                accel[i] = Pair(ax, ay)
            }
            return accel
        }

        // Velocity Verlet integration
        for (step in 0 until actualSteps) {
            val accel = computeAccel()

            for (i in 0 until bodyCount) {
                // In solar mode, keep the star fixed
                if (bodies[i].isStar && orbitStyle == "solar") continue

                bodies[i].x += bodies[i].vx * dt + 0.5 * accel[i].first * dt * dt
                bodies[i].y += bodies[i].vy * dt + 0.5 * accel[i].second * dt * dt
            }

            val newAccel = computeAccel()

            for (i in 0 until bodyCount) {
                if (bodies[i].isStar && orbitStyle == "solar") continue
                bodies[i].vx += 0.5 * (accel[i].first + newAccel[i].first) * dt
                bodies[i].vy += 0.5 * (accel[i].second + newAccel[i].second) * dt
            }

            // Store trail positions (skip stars in solar mode)
            for (i in 0 until bodyCount) {
                trailsX[i][trailHeads[i]] = bodies[i].x.toFloat()
                trailsY[i][trailHeads[i]] = bodies[i].y.toFloat()
                trailHeads[i] = (trailHeads[i] + 1) % trailLen
                if (trailCounts[i] < trailLen) trailCounts[i]++
            }
        }

        // Draw orbit guides (faint ellipses)
        val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 0.5f
            color = Color.argb(20, 255, 255, 255)
        }
        for (i in starCount until bodyCount) {
            if (bodies[i].isMoon) continue
            val t = if (planetCount == 1) 0.5f else (i - starCount).toFloat() / maxOf(1, planetCount - 1).toFloat()
            val orbitR = scaledMin + t * (scaledMax - scaledMin)
            canvas.drawCircle(centerX.toFloat(), centerY.toFloat(), orbitR, guidePaint)
        }

        // Draw trails
        val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }

        if (showTrails) {
            for (i in 0 until bodyCount) {
                // Skip star trails in solar mode
                if (bodies[i].isStar && orbitStyle == "solar") continue

                val count = trailCounts[i]
                if (count < 2) continue

                val oldest = if (count < trailLen) 0 else trailHeads[i]
                val baseColor = bodies[i].color
                val tw = if (bodies[i].isMoon) 1f else 2f

                // Draw trail in segments with alpha gradient
                val segCount = minOf(6, count - 1)
                val segSize = maxOf(1, count / segCount)

                for (seg in 0 until segCount) {
                    val path = Path()
                    val startK = seg * segSize
                    val endK = if (seg == segCount - 1) count else minOf((seg + 1) * segSize + 1, count)

                    val idx0 = (oldest + startK) % trailLen
                    path.moveTo(trailsX[i][idx0], trailsY[i][idx0])

                    for (k in startK + 1 until endK) {
                        val idx = (oldest + k) % trailLen
                        val prevIdx = (oldest + k - 1) % trailLen
                        val dx = trailsX[i][idx] - trailsX[i][prevIdx]
                        val dy = trailsY[i][idx] - trailsY[i][prevIdx]
                        if (dx * dx + dy * dy > dim * dim * 0.25f) {
                            path.moveTo(trailsX[i][idx], trailsY[i][idx])
                        } else {
                            path.lineTo(trailsX[i][idx], trailsY[i][idx])
                        }
                    }

                    val alpha = (15 + (seg.toFloat() / segCount * 200).toInt()).coerceIn(15, 215)
                    trailPaint.strokeWidth = tw
                    trailPaint.color = Color.argb(alpha, Color.red(baseColor), Color.green(baseColor), Color.blue(baseColor))
                    canvas.drawPath(path, trailPaint)
                }
            }
        }

        // Draw bodies
        val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

        for (i in 0 until bodyCount) {
            val bx = bodies[i].x.toFloat()
            val by = bodies[i].y.toFloat()
            val r = bodies[i].radius
            val baseColor = bodies[i].color

            if (bodies[i].isStar) {
                // Star glow layers
                val glowAlpha = (glowIntensity * 60).toInt().coerceIn(0, 60)
                glowPaint.color = Color.argb(glowAlpha, Color.red(baseColor), Color.green(baseColor), Color.blue(baseColor))
                canvas.drawCircle(bx, by, r * 5f, glowPaint)
                glowPaint.color = Color.argb((glowAlpha * 1.5f).toInt().coerceAtMost(100),
                    Color.red(baseColor), Color.green(baseColor), Color.blue(baseColor))
                canvas.drawCircle(bx, by, r * 2.5f, glowPaint)
                // Core
                bodyPaint.color = baseColor
                canvas.drawCircle(bx, by, r, bodyPaint)
                bodyPaint.color = Color.argb(230, 255, 255, 255)
                canvas.drawCircle(bx, by, r * 0.6f, bodyPaint)
            } else {
                // Planet/moon glow
                val glowAlpha = (glowIntensity * 40).toInt().coerceIn(0, 50)
                if (glowAlpha > 0 && !bodies[i].isMoon) {
                    glowPaint.color = Color.argb(glowAlpha, Color.red(baseColor), Color.green(baseColor), Color.blue(baseColor))
                    canvas.drawCircle(bx, by, r * 2.5f, glowPaint)
                }
                // Core
                bodyPaint.color = baseColor
                canvas.drawCircle(bx, by, r, bodyPaint)
                // Bright center
                bodyPaint.color = Color.argb(150, 255, 255, 255)
                canvas.drawCircle(bx, by, r * 0.35f, bodyPaint)
            }
        }
    }

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float {
        val bodies = (params["bodyCount"] as? Number)?.toFloat() ?: 5f
        val trailFraction = (params["trailLength"] as? Number)?.toFloat() ?: 0.4f
        val trail = (trailFraction * 600f).coerceAtLeast(20f)
        return ((bodies * bodies * trail) / 25000f).coerceIn(0.1f, 1f)
    }
}
