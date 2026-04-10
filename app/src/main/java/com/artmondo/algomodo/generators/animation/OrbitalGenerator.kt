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
 * Orbital mechanics visualisation — N-body gravitational simulation with comets,
 * pulsing stars, starfield background, and speed-reactive glow.
 */
class OrbitalGenerator : Generator {

    override val id = "orbital"
    override val family = "animation"
    override val styleName = "Orbital"
    override val definition = "N-body gravitational system with planets, comets, moons, pulsing stars, and a twinkling star field."
    override val algorithmNotes =
        "Stars (1, 2, or 3) anchor the system. Planets orbit at seeded distances with optional moons. " +
        "Comets are placed on highly eccentric orbits and grow brilliant ion tails as they swing past " +
        "the nearest star. The system is integrated with Velocity Verlet using flat SoA acceleration " +
        "arrays. Stars pulse with rotating coronal rays. Bodies glow brighter as they accelerate near " +
        "perihelion. A deterministic background field of ~200 stars twinkles independently."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema = listOf(
        Parameter.NumberParam("Planets", "bodyCount", ParamGroup.COMPOSITION,
            "Number of planetary bodies", 1f, 20f, 1f, 8f),
        Parameter.NumberParam("Comets", "cometCount", ParamGroup.COMPOSITION,
            "Eccentric icy bodies that grow tails near a star", 0f, 12f, 1f, 4f),
        Parameter.NumberParam("Moon Chance", "moonChance", ParamGroup.COMPOSITION,
            "Probability that each planet hosts a moon", 0f, 1f, 0.1f, 0.4f),
        Parameter.SelectParam("Style", "orbitStyle", ParamGroup.COMPOSITION,
            "solar: single star | binary: twin stars | trinary: chaotic three-body",
            listOf("solar", "binary", "trinary"), "solar"),
        Parameter.NumberParam("Speed", "speed", ParamGroup.FLOW_MOTION,
            "Orbital speed multiplier", 0.1f, 5f, 0.1f, 1.6f),
        Parameter.NumberParam("Eccentricity", "eccentricity", ParamGroup.GEOMETRY,
            "0 = circular orbits; higher = elongated elliptical", 0f, 0.95f, 0.05f, 0.45f),
        Parameter.NumberParam("Inner Orbit", "minRadius", ParamGroup.GEOMETRY,
            null, 30f, 250f, 10f, 80f),
        Parameter.NumberParam("Outer Orbit", "maxRadius", ParamGroup.GEOMETRY,
            null, 100f, 600f, 10f, 380f),
        Parameter.NumberParam("Body Size", "bodySize", ParamGroup.GEOMETRY,
            null, 2f, 24f, 1f, 9f),
        Parameter.NumberParam("Glow", "glowIntensity", ParamGroup.TEXTURE,
            "Glow halos and speed-reactive flares", 0f, 1f, 0.05f, 0.75f),
        Parameter.NumberParam("Trail", "trailLength", ParamGroup.TEXTURE,
            "Fraction of orbit arc shown as a fading trail", 0f, 1f, 0.05f, 0.6f),
        Parameter.NumberParam("Star Pulse", "starPulse", ParamGroup.TEXTURE,
            "Strength of star corona, rays, and pulsing", 0f, 1f, 0.05f, 0.7f),
        Parameter.NumberParam("Star Field", "starField", ParamGroup.TEXTURE,
            "Density of twinkling background stars", 0f, 1f, 0.05f, 0.7f)
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "bodyCount" to 8f,
        "cometCount" to 4f,
        "moonChance" to 0.4f,
        "orbitStyle" to "solar",
        "speed" to 1.6f,
        "eccentricity" to 0.45f,
        "minRadius" to 80f,
        "maxRadius" to 380f,
        "bodySize" to 9f,
        "glowIntensity" to 0.75f,
        "trailLength" to 0.6f,
        "starPulse" to 0.7f,
        "starField" to 0.7f
    )

    private data class Body(
        var x: Double, var y: Double,
        var vx: Double, var vy: Double,
        val mass: Double,
        val color: Int,
        val radius: Float,
        val isStar: Boolean = false,
        val isMoon: Boolean = false,
        val isComet: Boolean = false
    )

    override fun renderCanvas(
        canvas: Canvas, bitmap: Bitmap, params: Map<String, Any>,
        seed: Int, palette: Palette, quality: Quality, time: Float
    ) {
        val w = bitmap.width.toFloat()
        val h = bitmap.height.toFloat()
        val dim = min(w, h)
        val centerX = w / 2.0
        val centerY = h / 2.0

        val planetCount = ((params["bodyCount"] as? Number)?.toInt() ?: 8).coerceAtLeast(1)
        val cometCount = ((params["cometCount"] as? Number)?.toInt() ?: 4).coerceAtLeast(0)
        val moonChance = (params["moonChance"] as? Number)?.toFloat() ?: 0.4f
        val orbitStyle = (params["orbitStyle"] as? String) ?: "solar"
        val trailLengthFraction = (params["trailLength"] as? Number)?.toFloat() ?: 0.6f
        val showTrails = trailLengthFraction > 0f
        val trailLen = (trailLengthFraction * 600f).toInt().coerceAtLeast(20)
        val speed = (params["speed"] as? Number)?.toFloat() ?: 1.6f
        val eccentricity = (params["eccentricity"] as? Number)?.toFloat() ?: 0.45f
        val minRadius = (params["minRadius"] as? Number)?.toFloat() ?: 80f
        val maxRadius = (params["maxRadius"] as? Number)?.toFloat() ?: 380f
        val bodySize = (params["bodySize"] as? Number)?.toFloat() ?: 9f
        val glowIntensity = (params["glowIntensity"] as? Number)?.toFloat() ?: 0.75f
        val starPulse = (params["starPulse"] as? Number)?.toFloat() ?: 0.7f
        val starField = (params["starField"] as? Number)?.toFloat() ?: 0.7f

        val rng = SeededRNG(seed)
        val colors = palette.colorInts()

        // Background — deep space
        canvas.drawColor(Color.rgb(2, 2, 8))

        // ── Twinkling background star field ──
        if (starField > 0.01f) drawStarField(canvas, w, h, seed, starField, time)

        // Geometry & physics constants
        val radiusScale = dim / 810f
        val scaledMin = minRadius * radiusScale
        val scaledMax = maxRadius * radiusScale
        val starMass = 500.0
        val G = 1.0
        val softening = dim * 0.01

        val bodies = mutableListOf<Body>()

        // ── Star placement ──
        val warmStar = Color.rgb(255, 235, 180)
        val coolStar = Color.rgb(190, 215, 255)
        val midStar = Color.rgb(255, 200, 220)

        when (orbitStyle) {
            "binary" -> {
                val binaryDist = scaledMin * 0.6
                val binaryMass = starMass * 0.5
                val binarySpeed = sqrt(G * binaryMass / binaryDist) * 0.5
                bodies.add(Body(
                    centerX - binaryDist, centerY, 0.0, -binarySpeed,
                    binaryMass, warmStar, bodySize * 1.4f, isStar = true
                ))
                bodies.add(Body(
                    centerX + binaryDist, centerY, 0.0, binarySpeed,
                    binaryMass, coolStar, bodySize * 1.2f, isStar = true
                ))
            }
            "trinary" -> {
                // Three stars at vertices of an equilateral triangle, orbiting
                // their barycentre. Genuine three-body chaos — beautiful and short-lived.
                val triR = scaledMin * 0.55
                val triMass = starMass * 0.35
                val triV = sqrt(G * triMass / triR) * 0.62
                val triColors = intArrayOf(warmStar, midStar, coolStar)
                for (k in 0 until 3) {
                    val a = k * 2.0 * PI / 3.0 - PI / 2.0
                    val px = centerX + triR * cos(a)
                    val py = centerY + triR * sin(a)
                    val vx = -sin(a) * triV
                    val vy = cos(a) * triV
                    bodies.add(Body(px, py, vx, vy, triMass, triColors[k], bodySize * 1.25f, isStar = true))
                }
            }
            else -> {
                bodies.add(Body(
                    centerX, centerY, 0.0, 0.0,
                    starMass, warmStar, bodySize * 1.5f, isStar = true
                ))
            }
        }
        val starCount = bodies.size

        // ── Planets ──
        for (i in 0 until planetCount) {
            val t = if (planetCount == 1) 0.5f else i.toFloat() / (planetCount - 1).toFloat()
            val orbitRadius = (scaledMin + t * (scaledMax - scaledMin)).toDouble()
            val angle = rng.random().toDouble() * 2.0 * PI
            val px = centerX + orbitRadius * cos(angle)
            val py = centerY + orbitRadius * sin(angle)

            val orbitalV = sqrt(G * starMass / orbitRadius)
            val eccFactor = 1.0 + eccentricity * (rng.random().toDouble() * 2.0 - 1.0)
            val vx = -sin(angle) * orbitalV * eccFactor
            val vy = cos(angle) * orbitalV * eccFactor

            val planetMass = 0.1 + rng.random().toDouble() * 0.5
            val planetRadius = bodySize * (0.5f + rng.random() * 0.5f)
            val color = colors[(i + 1) % colors.size]

            bodies.add(Body(px, py, vx, vy, planetMass, color, planetRadius))

            // Optional moon
            if (rng.random() < moonChance) {
                val moonDist = planetRadius * 4.0 + rng.random() * planetRadius * 3.0
                val moonAngle = rng.random().toDouble() * 2.0 * PI
                val moonV = sqrt(G * planetMass * 20.0 / moonDist)
                val mx = px + moonDist * cos(moonAngle)
                val my = py + moonDist * sin(moonAngle)
                val mvx = vx + (-sin(moonAngle) * moonV)
                val mvy = vy + (cos(moonAngle) * moonV)
                bodies.add(Body(mx, my, mvx, mvy, 0.01, color, bodySize * 0.25f, isMoon = true))
            }
        }

        // ── Comets — high-eccentricity ice balls with tails ──
        // Tuned to swing in close to a star and back out again with serious drama.
        val cometIceColors = intArrayOf(
            Color.rgb(190, 230, 255),
            Color.rgb(220, 240, 255),
            Color.rgb(170, 210, 255),
            Color.rgb(230, 250, 255)
        )
        for (i in 0 until cometCount) {
            val orbitR = (scaledMax * (1.2 + rng.random() * 0.6)).toDouble()
            val angle = rng.random().toDouble() * 2.0 * PI
            val px = centerX + orbitR * cos(angle)
            val py = centerY + orbitR * sin(angle)
            // Sub-circular velocity → highly eccentric ellipse
            val orbitalV = sqrt(G * starMass / orbitR) * (0.32 + rng.random() * 0.18)
            // Slight inward kick so they don't fly off immediately
            val rotSign = if (i % 2 == 0) 1.0 else -1.0
            val vx = -sin(angle) * orbitalV * rotSign
            val vy = cos(angle) * orbitalV * rotSign
            val cometColor = cometIceColors[i % cometIceColors.size]
            bodies.add(Body(px, py, vx, vy, 0.03, cometColor, bodySize * 0.45f, isComet = true))
        }

        val bodyCount = bodies.size

        // ── Simulation (Velocity Verlet) ──
        val dt = 0.08 * speed
        val totalSteps = (time / 0.016f).toInt().coerceAtLeast(1)
        val maxSteps = trailLen * 3
        val actualSteps = totalSteps.coerceAtMost(maxSteps)

        // SoA acceleration buffers — eliminates per-step Pair allocations
        val accelX = DoubleArray(bodyCount)
        val accelY = DoubleArray(bodyCount)
        val newAccelX = DoubleArray(bodyCount)
        val newAccelY = DoubleArray(bodyCount)

        // Trails
        val trailsX = Array(bodyCount) { FloatArray(trailLen) }
        val trailsY = Array(bodyCount) { FloatArray(trailLen) }
        val trailHeads = IntArray(bodyCount)
        val trailCounts = IntArray(bodyCount)

        fun computeAccel(out: DoubleArray, outY: DoubleArray) {
            val softSq = softening * softening
            for (i in 0 until bodyCount) {
                var ax = 0.0
                var ay = 0.0
                val xi = bodies[i].x; val yi = bodies[i].y
                for (j in 0 until bodyCount) {
                    if (i == j) continue
                    val dx = bodies[j].x - xi
                    val dy = bodies[j].y - yi
                    val distSq = dx * dx + dy * dy + softSq
                    val invDistCubed = 1.0 / (distSq * sqrt(distSq))
                    val force = G * bodies[j].mass * invDistCubed
                    ax += force * dx
                    ay += force * dy
                }
                out[i] = ax; outY[i] = ay
            }
        }

        for (step in 0 until actualSteps) {
            computeAccel(accelX, accelY)
            for (i in 0 until bodyCount) {
                if (bodies[i].isStar && orbitStyle == "solar") continue
                bodies[i].x += bodies[i].vx * dt + 0.5 * accelX[i] * dt * dt
                bodies[i].y += bodies[i].vy * dt + 0.5 * accelY[i] * dt * dt
            }
            computeAccel(newAccelX, newAccelY)
            for (i in 0 until bodyCount) {
                if (bodies[i].isStar && orbitStyle == "solar") continue
                bodies[i].vx += 0.5 * (accelX[i] + newAccelX[i]) * dt
                bodies[i].vy += 0.5 * (accelY[i] + newAccelY[i]) * dt
            }
            for (i in 0 until bodyCount) {
                trailsX[i][trailHeads[i]] = bodies[i].x.toFloat()
                trailsY[i][trailHeads[i]] = bodies[i].y.toFloat()
                trailHeads[i] = (trailHeads[i] + 1) % trailLen
                if (trailCounts[i] < trailLen) trailCounts[i]++
            }
        }

        // ── Faint orbit guides for planets ──
        val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 0.5f
            color = Color.argb(18, 255, 255, 255)
        }
        for (i in starCount until starCount + planetCount) {
            if (i >= bodyCount) break
            val t = if (planetCount == 1) 0.5f else (i - starCount).toFloat() / maxOf(1, planetCount - 1).toFloat()
            val orbitR = scaledMin + t * (scaledMax - scaledMin)
            canvas.drawCircle(centerX.toFloat(), centerY.toFloat(), orbitR, guidePaint)
        }

        // ── Star corona, halo, and rotating rays ──
        if (starPulse > 0.01f) {
            drawStarCoronas(canvas, bodies, starCount, starPulse, time)
        }

        // ── Trails ──
        if (showTrails) {
            drawTrails(canvas, bodies, bodyCount, trailsX, trailsY, trailHeads, trailCounts,
                trailLen, dim, orbitStyle)
        }

        // ── Comet tails ──
        drawCometTails(canvas, bodies, bodyCount, starCount, dim)

        // ── Bodies (with speed-reactive glow) ──
        drawBodies(canvas, bodies, bodyCount, glowIntensity, scaledMax, G, starMass)
    }

    // ────────────────────────────────────────────────────────────────────
    // Background star field — deterministic positions, twinkling alphas
    // ────────────────────────────────────────────────────────────────────
    private fun drawStarField(canvas: Canvas, w: Float, h: Float, seed: Int, density: Float, time: Float) {
        val rng = SeededRNG(seed * 7919 + 31)
        val count = (200f * density).toInt().coerceAtLeast(20)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        for (i in 0 until count) {
            val sx = rng.random() * w
            val sy = rng.random() * h
            val size = 0.5f + rng.random() * 1.6f
            val phase = rng.random() * (2f * PI.toFloat())
            val twinkle = 0.4f + 0.6f * (0.5f + 0.5f * sin(time * 1.5f + phase))
            val baseAlpha = (140 * twinkle).toInt().coerceIn(20, 220)
            // Subtle color tint for variety
            val tint = rng.random()
            val r = if (tint > 0.7f) 220 else 255
            val b = if (tint < 0.3f) 220 else 255
            paint.color = Color.argb(baseAlpha, r, 240, b)
            canvas.drawCircle(sx, sy, size, paint)
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // Star corona: pulsing halo rings + rotating radial rays
    // ────────────────────────────────────────────────────────────────────
    private fun drawStarCoronas(
        canvas: Canvas, bodies: List<Body>, starCount: Int,
        starPulse: Float, time: Float
    ) {
        val pulse = 0.85f + 0.15f * sin(time * 2.5f)
        val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val rayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
        }
        for (i in 0 until starCount) {
            val sx = bodies[i].x.toFloat()
            val sy = bodies[i].y.toFloat()
            val sr = bodies[i].radius
            val sc = bodies[i].color
            val rR = Color.red(sc); val gG = Color.green(sc); val bB = Color.blue(sc)

            // Soft halo rings (large, fading outward)
            for (k in 0 until 4) {
                val rk = sr * (3f + k * 2.2f) * pulse
                val a = ((46 - k * 10) * starPulse).toInt().coerceAtLeast(4)
                haloPaint.color = Color.argb(a, rR, gG, bB)
                canvas.drawCircle(sx, sy, rk, haloPaint)
            }

            // Rotating radial rays
            val rayCount = 14
            val rayBaseLen = sr * 8f * pulse
            rayPaint.strokeWidth = max(1f, sr * 0.18f)
            for (k in 0 until rayCount) {
                val baseAngle = k * 2.0 * PI / rayCount + time * 0.35
                // Each ray flickers independently for liveliness
                val flicker = 0.55f + 0.45f * sin(time * 4f + k * 0.83f)
                val len = rayBaseLen * flicker
                val ex = sx + (cos(baseAngle) * len).toFloat()
                val ey = sy + (sin(baseAngle) * len).toFloat()
                val rayAlpha = ((110 * starPulse * flicker).toInt()).coerceIn(0, 200)
                rayPaint.color = Color.argb(rayAlpha, rR, gG, bB)
                canvas.drawLine(sx, sy, ex, ey, rayPaint)
            }
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // Trails — multi-segment polylines with smooth alpha gradient
    // ────────────────────────────────────────────────────────────────────
    private fun drawTrails(
        canvas: Canvas, bodies: List<Body>, bodyCount: Int,
        trailsX: Array<FloatArray>, trailsY: Array<FloatArray>,
        trailHeads: IntArray, trailCounts: IntArray, trailLen: Int,
        dim: Float, orbitStyle: String
    ) {
        val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
        }
        val maxJumpSq = dim * dim * 0.25f

        for (i in 0 until bodyCount) {
            if (bodies[i].isStar && orbitStyle == "solar") continue
            val count = trailCounts[i]
            if (count < 2) continue

            val oldest = if (count < trailLen) 0 else trailHeads[i]
            val baseColor = bodies[i].color
            val rR = Color.red(baseColor); val gG = Color.green(baseColor); val bB = Color.blue(baseColor)
            val tw = when {
                bodies[i].isMoon -> 1.2f
                bodies[i].isComet -> 1.6f
                bodies[i].isStar -> 2.5f
                else -> 2.2f
            }

            // 10 segments → smoother gradient than the old 6
            val segCount = minOf(10, count - 1)
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
                    if (dx * dx + dy * dy > maxJumpSq) {
                        path.moveTo(trailsX[i][idx], trailsY[i][idx])
                    } else {
                        path.lineTo(trailsX[i][idx], trailsY[i][idx])
                    }
                }

                // Newer segments brighter; older fade to nothing
                val tNorm = seg.toFloat() / segCount
                val alpha = (10 + (tNorm * tNorm * 230f).toInt()).coerceIn(10, 240)
                trailPaint.strokeWidth = tw * (0.55f + 0.45f * tNorm)
                trailPaint.color = Color.argb(alpha, rR, gG, bB)
                canvas.drawPath(path, trailPaint)
            }
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // Comet tails — bright ion stream pointing away from nearest star
    // ────────────────────────────────────────────────────────────────────
    private fun drawCometTails(
        canvas: Canvas, bodies: List<Body>, bodyCount: Int, starCount: Int, dim: Float
    ) {
        if (starCount == 0) return
        val tailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
        }
        val comaPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

        for (i in 0 until bodyCount) {
            if (!bodies[i].isComet) continue

            // Find nearest star
            var nearest = 0
            var minDistSq = Double.MAX_VALUE
            for (k in 0 until starCount) {
                val dx = bodies[i].x - bodies[k].x
                val dy = bodies[i].y - bodies[k].y
                val d = dx * dx + dy * dy
                if (d < minDistSq) { minDistSq = d; nearest = k }
            }
            val sx = bodies[nearest].x
            val sy = bodies[nearest].y
            val dx = bodies[i].x - sx
            val dy = bodies[i].y - sy
            val dist = sqrt(minDistSq)
            if (dist < 0.0001) continue

            // Brightness drops with distance — close to star = blazing
            val brightness = (dim.toDouble() * 0.35 / dist).coerceIn(0.0, 1.0).toFloat()
            if (brightness < 0.04f) continue

            val tailLen = dim * 0.22f * brightness
            val invDist = 1.0 / dist
            val tdx = (dx * invDist * tailLen).toFloat()
            val tdy = (dy * invDist * tailLen).toFloat()
            val cx = bodies[i].x.toFloat()
            val cy = bodies[i].y.toFloat()
            val color = bodies[i].color
            val rR = Color.red(color); val gG = Color.green(color); val bB = Color.blue(color)

            // Layered tail (3 strokes of decreasing thickness, increasing brightness)
            tailPaint.strokeWidth = bodies[i].radius * 2.4f * brightness
            tailPaint.color = Color.argb((90 * brightness).toInt(), rR, gG, bB)
            canvas.drawLine(cx, cy, cx + tdx, cy + tdy, tailPaint)

            tailPaint.strokeWidth = bodies[i].radius * 1.2f * brightness
            tailPaint.color = Color.argb((180 * brightness).toInt(), rR, gG, bB)
            canvas.drawLine(cx, cy, cx + tdx * 0.85f, cy + tdy * 0.85f, tailPaint)

            tailPaint.strokeWidth = bodies[i].radius * 0.5f * brightness
            tailPaint.color = Color.argb((230 * brightness).toInt(), 255, 255, 255)
            canvas.drawLine(cx, cy, cx + tdx * 0.6f, cy + tdy * 0.6f, tailPaint)

            // Coma — bright halo around the comet head
            val comaR = bodies[i].radius * (1.6f + brightness * 2.5f)
            comaPaint.color = Color.argb((100 * brightness).toInt(), rR, gG, bB)
            canvas.drawCircle(cx, cy, comaR, comaPaint)
            comaPaint.color = Color.argb((200 * brightness).toInt(), 255, 255, 255)
            canvas.drawCircle(cx, cy, comaR * 0.55f, comaPaint)
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // Bodies — speed-reactive glow + bright core
    // ────────────────────────────────────────────────────────────────────
    private fun drawBodies(
        canvas: Canvas, bodies: List<Body>, bodyCount: Int,
        glowIntensity: Float, scaledMax: Float, G: Double, starMass: Double
    ) {
        val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

        // Reference orbital speed at mid-radius — used to normalize per-body glow
        val refV = sqrt(G * starMass / scaledMax.toDouble().coerceAtLeast(1.0))

        for (i in 0 until bodyCount) {
            val bx = bodies[i].x.toFloat()
            val by = bodies[i].y.toFloat()
            val r = bodies[i].radius
            val baseColor = bodies[i].color

            if (bodies[i].isStar) {
                // Stars are drawn after their corona (drawn earlier in the pipeline)
                bodyPaint.color = baseColor
                canvas.drawCircle(bx, by, r, bodyPaint)
                bodyPaint.color = Color.argb(235, 255, 255, 255)
                canvas.drawCircle(bx, by, r * 0.55f, bodyPaint)
                continue
            }

            // Speed-reactive glow factor: faster bodies blaze brighter (perihelion flare)
            val speedMag = sqrt(bodies[i].vx * bodies[i].vx + bodies[i].vy * bodies[i].vy)
            val speedFactor = (speedMag / (refV + 0.0001)).toFloat().coerceIn(0.4f, 2.5f)

            val isMoon = bodies[i].isMoon
            val isComet = bodies[i].isComet
            val rR = Color.red(baseColor); val gG = Color.green(baseColor); val bB = Color.blue(baseColor)

            if (!isMoon && !isComet) {
                // Outer glow
                val glowAlpha = (glowIntensity * 50f * speedFactor).toInt().coerceIn(0, 110)
                if (glowAlpha > 0) {
                    glowPaint.color = Color.argb(glowAlpha, rR, gG, bB)
                    canvas.drawCircle(bx, by, r * (2.6f + speedFactor * 0.6f), glowPaint)
                }
                // Inner glow
                val innerAlpha = (glowIntensity * 90f).toInt().coerceIn(0, 160)
                glowPaint.color = Color.argb(innerAlpha, rR, gG, bB)
                canvas.drawCircle(bx, by, r * 1.55f, glowPaint)
            }

            // Core
            bodyPaint.color = baseColor
            canvas.drawCircle(bx, by, r, bodyPaint)

            // Specular highlight
            if (!isComet) {
                bodyPaint.color = Color.argb(if (isMoon) 120 else 170, 255, 255, 255)
                canvas.drawCircle(bx, by, r * 0.38f, bodyPaint)
            }
        }
    }

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float {
        val planets = (params["bodyCount"] as? Number)?.toFloat() ?: 8f
        val comets = (params["cometCount"] as? Number)?.toFloat() ?: 4f
        val trailFraction = (params["trailLength"] as? Number)?.toFloat() ?: 0.6f
        val trail = (trailFraction * 600f).coerceAtLeast(20f)
        val n = planets + comets + 2f
        return ((n * n * trail) / 30000f).coerceIn(0.15f, 1f)
    }
}
