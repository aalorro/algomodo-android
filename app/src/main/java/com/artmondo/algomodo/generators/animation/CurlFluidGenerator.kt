package com.artmondo.algomodo.generators.animation

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import com.artmondo.algomodo.core.rng.SeededRNG
import com.artmondo.algomodo.core.rng.SimplexNoise
import com.artmondo.algomodo.data.palettes.Palette
import com.artmondo.algomodo.generators.Generator
import com.artmondo.algomodo.generators.ParamGroup
import com.artmondo.algomodo.generators.Parameter
import com.artmondo.algomodo.generators.Quality
import kotlin.math.*

/**
 * Curl noise fluid simulation.
 *
 * Particles follow the curl of a 2D simplex noise potential field. The curl
 * (perpendicular to the noise gradient) produces divergence-free flow,
 * giving a realistic fluid appearance. Particles are rendered as flowing line
 * trails that accumulate density, producing a smooth fluid aesthetic.
 */
class CurlFluidGenerator : Generator {

    override val id = "curl-fluid"
    override val family = "animation"
    override val styleName = "Curl Fluid"
    override val definition = "Particles following curl noise flow — divergence-free fluid-like motion."
    override val algorithmNotes =
        "The curl of a 2D scalar field F is (dF/dy, -dF/dx). We compute this numerically " +
        "by sampling noise at small offsets: curl_x = (noise(x, y+eps) - noise(x, y-eps)) / (2*eps), " +
        "curl_y = -(noise(x+eps, y) - noise(x-eps, y)) / (2*eps). Particles are advected along " +
        "this field. Each particle's recent trail is drawn as a polyline with fading alpha. " +
        "Because curl fields are divergence-free, particles neither bunch nor disperse."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema = listOf(
        Parameter.NumberParam(
            name = "Particle Count",
            key = "particleCount",
            group = ParamGroup.COMPOSITION,
            help = null,
            min = 500f, max = 10000f, step = 500f, default = 4000f
        ),
        Parameter.NumberParam(
            name = "Noise Scale",
            key = "noiseScale",
            group = ParamGroup.COMPOSITION,
            help = "Spatial scale of the curl field",
            min = 0.2f, max = 5f, step = 0.1f, default = 1.2f
        ),
        Parameter.NumberParam(
            name = "Speed",
            key = "speed",
            group = ParamGroup.FLOW_MOTION,
            help = null,
            min = 0.5f, max = 8f, step = 0.25f, default = 3.0f
        ),
        Parameter.NumberParam(
            name = "Trail Decay",
            key = "trailDecay",
            group = ParamGroup.TEXTURE,
            help = "Trail fade rate (lower = longer)",
            min = 0.005f, max = 0.2f, step = 0.005f, default = 0.025f
        ),
        Parameter.NumberParam(
            name = "Evolution",
            key = "evolution",
            group = ParamGroup.FLOW_MOTION,
            help = "How fast the field evolves",
            min = 0f, max = 0.3f, step = 0.005f, default = 0.05f
        ),
        Parameter.NumberParam(
            name = "Line Width",
            key = "lineWidth",
            group = ParamGroup.GEOMETRY,
            help = null,
            min = 0.25f, max = 3f, step = 0.25f, default = 0.75f
        ),
        Parameter.SelectParam(
            name = "Color Mode",
            key = "colorMode",
            group = ParamGroup.COLOR,
            help = null,
            options = listOf("palette", "velocity", "position"),
            default = "palette"
        )
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "particleCount" to 4000f,
        "noiseScale" to 1.2f,
        "speed" to 3.0f,
        "trailDecay" to 0.025f,
        "evolution" to 0.05f,
        "lineWidth" to 0.75f,
        "colorMode" to "palette"
    )

    // ---- Simulation cache ----
    // Avoids re-simulating the entire particle history each frame.
    // The animation thread calls renderCanvas with incrementing time;
    // we advance particles by only the delta instead of replaying from t=0.
    @Volatile private var simCache: SimCache? = null

    private class SimCache(
        val seed: Int,
        val count: Int,
        val trailLen: Int,
        val noiseScale: Float,
        val speed: Float,
        val evolution: Float,
        var stepCount: Int,            // total simulation steps completed
        val px: FloatArray,            // current x positions [count]
        val py: FloatArray,            // current y positions [count]
        val trail: FloatArray,         // ring buffer [count * trailLen * 2] interleaved x,y
        val trailHead: IntArray,       // next write index per particle [count]
        val trailSize: IntArray        // trail points stored per particle [count]
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

        val particleCount = ((params["particleCount"] as? Number)?.toInt() ?: 4000).let {
            when (quality) {
                Quality.DRAFT -> (it * 0.3f).toInt()
                Quality.BALANCED -> it
                Quality.ULTRA -> (it * 1.5f).toInt()
            }
        }
        val noiseScale = (params["noiseScale"] as? Number)?.toFloat() ?: 1.2f
        val speed = (params["speed"] as? Number)?.toFloat() ?: 3f
        val evolution = (params["evolution"] as? Number)?.toFloat() ?: 0.05f
        val lineWidth = (params["lineWidth"] as? Number)?.toFloat() ?: 0.75f
        val trailDecay = (params["trailDecay"] as? Number)?.toFloat() ?: 0.025f
        val colorMode = (params["colorMode"] as? String) ?: "palette"

        val noise = SimplexNoise(seed)
        val colors = palette.colorInts()

        canvas.drawColor(Color.rgb(4, 4, 8))

        val dt = 0.016f
        val trailLen = when (quality) {
            Quality.DRAFT -> 30
            Quality.BALANCED -> 50
            Quality.ULTRA -> 70
        }
        val totalSteps = (time / dt).toInt().coerceAtLeast(1)

        // ---- Resolve simulation state from cache or build from scratch ----
        val cached = simCache
        val sim: SimCache

        if (cached != null &&
            cached.seed == seed &&
            cached.count == particleCount &&
            cached.trailLen == trailLen &&
            cached.noiseScale == noiseScale &&
            cached.speed == speed &&
            cached.evolution == evolution &&
            totalSteps >= cached.stepCount &&
            totalSteps - cached.stepCount < 120
        ) {
            // Continue from cached state — advance only the delta
            sim = cached
            val stepsNeeded = totalSteps - sim.stepCount
            for (s in 0 until stepsNeeded) {
                val noiseT = (sim.stepCount + s) * dt * evolution
                advanceAll(sim, noise, noiseT, w, h, dim)
            }
            sim.stepCount = totalSteps
        } else {
            // Full simulation from scratch
            sim = SimCache(
                seed = seed,
                count = particleCount,
                trailLen = trailLen,
                noiseScale = noiseScale,
                speed = speed,
                evolution = evolution,
                stepCount = 0,
                px = FloatArray(particleCount),
                py = FloatArray(particleCount),
                trail = FloatArray(particleCount * trailLen * 2),
                trailHead = IntArray(particleCount),
                trailSize = IntArray(particleCount)
            )

            val rng = SeededRNG(seed)
            for (i in 0 until particleCount) {
                sim.px[i] = rng.random() * w
                sim.py[i] = rng.random() * h
            }

            // Coarse skip to near the trail start (8x step size)
            val coarseEnd = (totalSteps - trailLen).coerceAtLeast(0)
            val coarseStep = 8
            val eps = 0.01f
            val invEps2 = 1f / (2f * eps)

            var step = 0
            while (step < coarseEnd) {
                val noiseT = step * dt * evolution
                for (i in 0 until particleCount) {
                    val nx = sim.px[i] / dim * noiseScale
                    val ny = sim.py[i] / dim * noiseScale
                    val dFdy = (noise.noise2D(nx, ny + eps + noiseT) -
                            noise.noise2D(nx, ny - eps + noiseT)) * invEps2
                    val dFdx = (noise.noise2D(nx + eps, ny + noiseT) -
                            noise.noise2D(nx - eps, ny + noiseT)) * invEps2
                    sim.px[i] += dFdy * speed * coarseStep
                    sim.py[i] += -dFdx * speed * coarseStep
                    if (sim.px[i] < 0) sim.px[i] += w; if (sim.px[i] >= w) sim.px[i] -= w
                    if (sim.py[i] < 0) sim.py[i] += h; if (sim.py[i] >= h) sim.py[i] -= h
                }
                step += coarseStep
            }

            // Fine steps for trail collection
            for (s in step until totalSteps) {
                val noiseT = s * dt * evolution
                advanceAll(sim, noise, noiseT, w, h, dim)
            }
            sim.stepCount = totalSteps
        }

        simCache = sim

        // ---- Render trails ----
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = lineWidth + 1f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        val segCount = 2 // tail + head (reduced from 4 for speed)
        val decayMul = (1f - trailDecay * 4f).coerceIn(0.05f, 1f)
        val minAlpha = (40 * decayMul).toInt().coerceAtLeast(5)
        val alphas = IntArray(segCount) { seg ->
            (minAlpha + (seg.toFloat() / segCount * (220 - minAlpha)).toInt()).coerceIn(5, 220)
        }
        val dimSq004 = dim * dim * 0.04f

        if (colorMode == "palette") {
            // Batched rendering: collect all trails by (colorIdx, segment) into
            // shared Path objects, then draw once per group. Reduces draw calls
            // from particleCount*segCount (~8000) to nColors*segCount (~20).
            val nColors = colors.size
            val paths = Array(nColors) { Array(segCount) { Path() } }

            for (i in 0 until particleCount) {
                val size = sim.trailSize[i]
                if (size < 2) continue
                val colorIdx = i % nColors
                val oldest = (sim.trailHead[i] - size + trailLen + trailLen) % trailLen
                val segSize = size / segCount

                for (seg in 0 until segCount) {
                    val startK = seg * segSize
                    val endK = if (seg == segCount - 1) size else (seg + 1) * segSize + 1
                    val path = paths[colorIdx][seg]

                    val firstRing = (oldest + startK) % trailLen
                    val firstBase = (i * trailLen + firstRing) * 2
                    path.moveTo(sim.trail[firstBase], sim.trail[firstBase + 1])
                    var prevX = sim.trail[firstBase]
                    var prevY = sim.trail[firstBase + 1]

                    for (k in startK + 1 until endK) {
                        val ring = (oldest + k) % trailLen
                        val base = (i * trailLen + ring) * 2
                        val x = sim.trail[base]
                        val y = sim.trail[base + 1]
                        val dx = x - prevX
                        val dy = y - prevY
                        if (dx * dx + dy * dy > dimSq004) {
                            path.moveTo(x, y)
                        } else {
                            path.lineTo(x, y)
                        }
                        prevX = x
                        prevY = y
                    }
                }
            }

            for (colorIdx in 0 until nColors) {
                val c = colors[colorIdx]
                val r = Color.red(c); val g = Color.green(c); val b = Color.blue(c)
                for (seg in 0 until segCount) {
                    if (paths[colorIdx][seg].isEmpty) continue
                    paint.color = Color.argb(alphas[seg], r, g, b)
                    canvas.drawPath(paths[colorIdx][seg], paint)
                }
            }
        } else {
            // velocity/position: per-particle colors — reuse single Path
            val path = Path()
            val eps = 0.01f
            val invEps2 = 1f / (2f * eps)
            val noiseT = time * evolution

            for (i in 0 until particleCount) {
                val size = sim.trailSize[i]
                if (size < 2) continue
                val oldest = (sim.trailHead[i] - size + trailLen + trailLen) % trailLen
                val segSize = size / segCount

                for (seg in 0 until segCount) {
                    path.reset()
                    val startK = seg * segSize
                    val endK = if (seg == segCount - 1) size else (seg + 1) * segSize + 1

                    val firstRing = (oldest + startK) % trailLen
                    val firstBase = (i * trailLen + firstRing) * 2
                    path.moveTo(sim.trail[firstBase], sim.trail[firstBase + 1])
                    var prevX = sim.trail[firstBase]
                    var prevY = sim.trail[firstBase + 1]

                    for (k in startK + 1 until endK) {
                        val ring = (oldest + k) % trailLen
                        val base = (i * trailLen + ring) * 2
                        val x = sim.trail[base]
                        val y = sim.trail[base + 1]
                        val dx = x - prevX
                        val dy = y - prevY
                        if (dx * dx + dy * dy > dimSq004) {
                            path.moveTo(x, y)
                        } else {
                            path.lineTo(x, y)
                        }
                        prevX = x
                        prevY = y
                    }

                    val midK = (startK + endK) / 2
                    val midRing = (oldest + midK.coerceAtMost(size - 1)) % trailLen
                    val midBase = (i * trailLen + midRing) * 2

                    val c = when (colorMode) {
                        "velocity" -> {
                            val nx = sim.trail[midBase] / dim * noiseScale
                            val ny = sim.trail[midBase + 1] / dim * noiseScale
                            val dFdy = (noise.noise2D(nx, ny + eps + noiseT) -
                                    noise.noise2D(nx, ny - eps + noiseT)) * invEps2
                            val dFdx = (noise.noise2D(nx + eps, ny + noiseT) -
                                    noise.noise2D(nx - eps, ny + noiseT)) * invEps2
                            val mag = sqrt(dFdy * dFdy + dFdx * dFdx).coerceIn(0f, 1f)
                            palette.lerpColor(mag)
                        }
                        "position" -> {
                            val posVal = (sim.trail[midBase] / w +
                                    sim.trail[midBase + 1] / h) * 0.5f
                            palette.lerpColor(posVal.coerceIn(0f, 1f))
                        }
                        else -> colors[i % colors.size]
                    }

                    paint.color = Color.argb(
                        alphas[seg],
                        Color.red(c), Color.green(c), Color.blue(c)
                    )
                    canvas.drawPath(path, paint)
                }
            }
        }
    }

    /** Advance all particles by one step and record positions in trail ring buffer. */
    private fun advanceAll(
        sim: SimCache,
        noise: SimplexNoise,
        noiseT: Float,
        w: Float, h: Float, dim: Float
    ) {
        val eps = 0.01f
        val invEps2 = 1f / (2f * eps)
        val ns = sim.noiseScale
        val spd = sim.speed
        val tl = sim.trailLen
        val invDim = 1f / dim

        for (i in 0 until sim.count) {
            val nx = sim.px[i] * invDim * ns
            val ny = sim.py[i] * invDim * ns
            val dFdy = (noise.noise2D(nx, ny + eps + noiseT) -
                    noise.noise2D(nx, ny - eps + noiseT)) * invEps2
            val dFdx = (noise.noise2D(nx + eps, ny + noiseT) -
                    noise.noise2D(nx - eps, ny + noiseT)) * invEps2
            sim.px[i] += dFdy * spd
            sim.py[i] += -dFdx * spd
            if (sim.px[i] < 0f) sim.px[i] += w; if (sim.px[i] >= w) sim.px[i] -= w
            if (sim.py[i] < 0f) sim.py[i] += h; if (sim.py[i] >= h) sim.py[i] -= h

            // Store in trail ring buffer
            val base = (i * tl + sim.trailHead[i]) * 2
            sim.trail[base] = sim.px[i]
            sim.trail[base + 1] = sim.py[i]
            sim.trailHead[i] = (sim.trailHead[i] + 1) % tl
            if (sim.trailSize[i] < tl) sim.trailSize[i]++
        }
    }

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float {
        val count = (params["particleCount"] as? Number)?.toFloat() ?: 4000f
        return (count / 8000f).coerceIn(0.1f, 1f)
    }
}
