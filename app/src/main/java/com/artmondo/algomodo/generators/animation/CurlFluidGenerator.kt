package com.artmondo.algomodo.generators.animation

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
import kotlin.math.*

/**
 * Curl noise fluid simulation.
 *
 * Particles follow the curl of a 2D simplex noise potential field. The curl
 * (perpendicular to the noise gradient) produces divergence-free flow,
 * giving a realistic fluid appearance. Particles are rendered as flowing line
 * trails that accumulate density, producing a smooth fluid aesthetic.
 *
 * Performance: A coarse grid of noise values is sampled once per step, then
 * the curl field is computed via finite differences on the grid. Each particle
 * is advected using bilinear interpolation of the grid curl — replacing 4
 * noise evaluations per particle with a single cheap interpolation.
 */
class CurlFluidGenerator : Generator {

    override val id = "curl-fluid"
    override val family = "animation"
    override val styleName = "Curl Fluid"
    override val definition = "Particles following curl noise flow — divergence-free fluid-like motion."
    override val algorithmNotes =
        "The curl of a 2D scalar field F is (dF/dy, -dF/dx). A grid of noise values is " +
        "sampled and the curl field is computed via finite differences, then each particle " +
        "is advected using bilinear interpolation of the grid curl. Each particle's recent " +
        "trail is drawn as a line segment batch with fading alpha. " +
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
    @Volatile private var simCache: SimCache? = null

    // Reusable grid arrays to avoid per-frame allocation
    private var gridNoise: FloatArray? = null
    private var gridCurlX: FloatArray? = null
    private var gridCurlY: FloatArray? = null

    private class SimCache(
        val seed: Int,
        val count: Int,
        val trailLen: Int,
        val noiseScale: Float,
        val speed: Float,
        val evolution: Float,
        var stepCount: Int,
        val px: FloatArray,
        val py: FloatArray,
        val trail: FloatArray,         // ring buffer [count * trailLen * 2] interleaved x,y
        val trailHead: IntArray,
        val trailSize: IntArray
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

        // ---- Curl grid setup ----
        // Instead of 4 noise calls per particle per step (~16,000 evaluations),
        // sample noise on a coarse grid (~2,500 evaluations) and bilinear-interpolate.
        val gridW = 48
        val gridH = (48f * h / w).toInt().coerceIn(24, 96)
        val gw1 = gridW + 1   // curl grid has +1 points (vertex-centered)
        val gh1 = gridH + 1
        val noiseW = gridW + 2 // +2 border cells for central differences
        val noiseH = gridH + 2
        val cellW = w / gridW
        val cellH = h / gridH
        val invCellW = 1f / cellW
        val invCellH = 1f / cellH
        val nsOverDim = noiseScale / dim

        // Reuse grid arrays if sizes match
        val noiseSz = noiseW * noiseH
        val curlSz = gw1 * gh1
        val nv = if (gridNoise?.size == noiseSz) gridNoise!! else FloatArray(noiseSz).also { gridNoise = it }
        val cx = if (gridCurlX?.size == curlSz) gridCurlX!! else FloatArray(curlSz).also { gridCurlX = it }
        val cy = if (gridCurlY?.size == curlSz) gridCurlY!! else FloatArray(curlSz).also { gridCurlY = it }

        // ---- Resolve simulation state ----
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
            // Cache hit — advance only the delta steps
            sim = cached
            val stepsNeeded = totalSteps - sim.stepCount
            for (s in 0 until stepsNeeded) {
                val noiseT = (sim.stepCount + s) * dt * evolution
                buildCurlGrid(noise, nv, cx, cy, noiseW, noiseH, gw1, gh1, cellW, cellH, nsOverDim, noiseT, speed)
                advanceWithGrid(sim, cx, cy, gw1, invCellW, invCellH, gridW, gridH, w, h, 1f, true)
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

            // Coarse skip to near the trail start (8x step size, using grid)
            val coarseEnd = (totalSteps - trailLen).coerceAtLeast(0)
            val coarseStep = 8
            var step = 0
            while (step < coarseEnd) {
                val noiseT = step * dt * evolution
                buildCurlGrid(noise, nv, cx, cy, noiseW, noiseH, gw1, gh1, cellW, cellH, nsOverDim, noiseT, speed)
                advanceWithGrid(sim, cx, cy, gw1, invCellW, invCellH, gridW, gridH, w, h, coarseStep.toFloat(), false)
                step += coarseStep
            }

            // Fine steps for trail collection
            for (s in step until totalSteps) {
                val noiseT = s * dt * evolution
                buildCurlGrid(noise, nv, cx, cy, noiseW, noiseH, gw1, gh1, cellW, cellH, nsOverDim, noiseT, speed)
                advanceWithGrid(sim, cx, cy, gw1, invCellW, invCellH, gridW, gridH, w, h, 1f, true)
            }
            sim.stepCount = totalSteps
        }

        simCache = sim

        // ---- Render trails with drawLines (much faster than Path) ----
        val paint = Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = lineWidth + 0.5f
            strokeCap = Paint.Cap.BUTT   // faster than ROUND
            isAntiAlias = time <= 0f      // skip AA during animation
        }

        val decayMul = (1f - trailDecay * 4f).coerceIn(0.05f, 1f)
        val tailAlpha = (40 * decayMul).toInt().coerceIn(5, 160)
        val headAlpha = (tailAlpha + 140 * decayMul).toInt().coerceIn(30, 220)
        val dimSq004 = dim * dim * 0.04f
        // Stride-2 sampling for long trails — halves line segment count
        val stride = if (trailLen > 25) 2 else 1

        if (colorMode == "palette") {
            val nColors = colors.size
            val maxPerHalf = ((particleCount + nColors - 1) / nColors) * (trailLen / stride / 2 + 2)
            val tailBuf = FloatArray(maxPerHalf * 4)
            val headBuf = FloatArray(maxPerHalf * 4)

            for (colorIdx in 0 until nColors) {
                var tCount = 0
                var hCount = 0
                val tLimit = tailBuf.size - 4
                val hLimit = headBuf.size - 4

                var i = colorIdx
                while (i < particleCount) {
                    val size = sim.trailSize[i]
                    if (size >= 2) {
                        val oldest = (sim.trailHead[i] - size + trailLen + trailLen) % trailLen
                        val pBase = i * trailLen
                        val halfSize = size / 2

                        val firstOff = (pBase + oldest) * 2
                        var prevX = sim.trail[firstOff]
                        var prevY = sim.trail[firstOff + 1]

                        var k = stride
                        while (k < size) {
                            val ring = (oldest + k) % trailLen
                            val off = (pBase + ring) * 2
                            val x = sim.trail[off]
                            val y = sim.trail[off + 1]
                            val dx = x - prevX
                            val dy = y - prevY
                            if (dx * dx + dy * dy <= dimSq004) {
                                if (k < halfSize && tCount <= tLimit) {
                                    tailBuf[tCount] = prevX
                                    tailBuf[tCount + 1] = prevY
                                    tailBuf[tCount + 2] = x
                                    tailBuf[tCount + 3] = y
                                    tCount += 4
                                } else if (k >= halfSize && hCount <= hLimit) {
                                    headBuf[hCount] = prevX
                                    headBuf[hCount + 1] = prevY
                                    headBuf[hCount + 2] = x
                                    headBuf[hCount + 3] = y
                                    hCount += 4
                                }
                            }
                            prevX = x
                            prevY = y
                            k += stride
                        }
                    }
                    i += nColors
                }

                val c = colors[colorIdx]
                val r = Color.red(c); val g = Color.green(c); val b = Color.blue(c)
                if (tCount > 0) {
                    paint.color = Color.argb(tailAlpha, r, g, b)
                    canvas.drawLines(tailBuf, 0, tCount, paint)
                }
                if (hCount > 0) {
                    paint.color = Color.argb(headAlpha, r, g, b)
                    canvas.drawLines(headBuf, 0, hCount, paint)
                }
            }
        } else {
            // velocity/position: batch by quantized color buckets
            // Velocity uses the curl grid directly — zero additional noise calls
            val quantBuckets = 24
            val bucketFor = IntArray(particleCount)
            val bucketCount = IntArray(quantBuckets)

            for (i in 0 until particleCount) {
                val b = when (colorMode) {
                    "velocity" -> {
                        val gfx = sim.px[i] * invCellW
                        val gfy = sim.py[i] * invCellH
                        val gx0 = gfx.toInt().coerceIn(0, gridW - 1)
                        val gy0 = gfy.toInt().coerceIn(0, gridH - 1)
                        val idx = gy0 * gw1 + gx0
                        val mag = sqrt(cx[idx] * cx[idx] + cy[idx] * cy[idx]) / (speed * 1.5f)
                        (mag.coerceIn(0f, 0.999f) * quantBuckets).toInt()
                    }
                    else -> {
                        val posVal = (sim.px[i] / w + sim.py[i] / h) * 0.5f
                        (posVal.coerceIn(0f, 0.999f) * quantBuckets).toInt()
                    }
                }
                bucketFor[i] = b
                bucketCount[b]++
            }

            // Count-sort for efficient per-bucket iteration
            val bucketStart = IntArray(quantBuckets + 1)
            for (b in 0 until quantBuckets) bucketStart[b + 1] = bucketStart[b] + bucketCount[b]
            val sorted = IntArray(particleCount)
            val writePos = bucketStart.copyOf(quantBuckets)
            for (i in 0 until particleCount) {
                val b = bucketFor[i]
                sorted[writePos[b]++] = i
            }

            val maxBucket = bucketCount.maxOrNull() ?: 0
            val maxPerHalf = maxBucket * (trailLen / stride / 2 + 2)
            val tailBuf = FloatArray(maxPerHalf * 4)
            val headBuf = FloatArray(maxPerHalf * 4)

            for (bucket in 0 until quantBuckets) {
                if (bucketCount[bucket] == 0) continue
                var tCount = 0
                var hCount = 0
                val tLimit = tailBuf.size - 4
                val hLimit = headBuf.size - 4

                for (j in bucketStart[bucket] until bucketStart[bucket] + bucketCount[bucket]) {
                    val i = sorted[j]
                    val size = sim.trailSize[i]
                    if (size < 2) continue
                    val oldest = (sim.trailHead[i] - size + trailLen + trailLen) % trailLen
                    val pBase = i * trailLen
                    val halfSize = size / 2

                    val firstOff = (pBase + oldest) * 2
                    var prevX = sim.trail[firstOff]
                    var prevY = sim.trail[firstOff + 1]

                    var k = stride
                    while (k < size) {
                        val ring = (oldest + k) % trailLen
                        val off = (pBase + ring) * 2
                        val x = sim.trail[off]
                        val y = sim.trail[off + 1]
                        val dx = x - prevX
                        val dy = y - prevY
                        if (dx * dx + dy * dy <= dimSq004) {
                            if (k < halfSize && tCount <= tLimit) {
                                tailBuf[tCount] = prevX
                                tailBuf[tCount + 1] = prevY
                                tailBuf[tCount + 2] = x
                                tailBuf[tCount + 3] = y
                                tCount += 4
                            } else if (k >= halfSize && hCount <= hLimit) {
                                headBuf[hCount] = prevX
                                headBuf[hCount + 1] = prevY
                                headBuf[hCount + 2] = x
                                headBuf[hCount + 3] = y
                                hCount += 4
                            }
                        }
                        prevX = x
                        prevY = y
                        k += stride
                    }
                }

                val t = (bucket + 0.5f) / quantBuckets
                val c = palette.lerpColor(t)
                val r = Color.red(c); val g = Color.green(c); val b = Color.blue(c)
                if (tCount > 0) {
                    paint.color = Color.argb(tailAlpha, r, g, b)
                    canvas.drawLines(tailBuf, 0, tCount, paint)
                }
                if (hCount > 0) {
                    paint.color = Color.argb(headAlpha, r, g, b)
                    canvas.drawLines(headBuf, 0, hCount, paint)
                }
            }
        }
    }

    /** Build curl vector field on a coarse grid from noise values. */
    private fun buildCurlGrid(
        noise: SimplexNoise,
        nv: FloatArray,
        curlX: FloatArray,
        curlY: FloatArray,
        noiseW: Int, noiseH: Int,
        gw1: Int, gh1: Int,
        cellW: Float, cellH: Float,
        nsOverDim: Float,
        noiseT: Float,
        speed: Float
    ) {
        // Sample noise on (noiseW × noiseH) grid with 1-cell border for differences
        for (gy in 0 until noiseH) {
            val ny = (gy - 1) * cellH * nsOverDim
            val rowOff = gy * noiseW
            for (gx in 0 until noiseW) {
                nv[rowOff + gx] = noise.noise2D((gx - 1) * cellW * nsOverDim, ny + noiseT)
            }
        }
        // Curl via central differences on the grid
        val invDy = speed / (2f * cellH * nsOverDim)
        val invDx = speed / (2f * cellW * nsOverDim)
        for (gy in 0 until gh1) {
            val nRow = (gy + 1) * noiseW
            val cRow = gy * gw1
            for (gx in 0 until gw1) {
                val ng = nRow + gx + 1
                curlX[cRow + gx] = (nv[ng + noiseW] - nv[ng - noiseW]) * invDy
                curlY[cRow + gx] = -(nv[ng + 1] - nv[ng - 1]) * invDx
            }
        }
    }

    /** Advance all particles using bilinear interpolation of the curl grid. */
    private fun advanceWithGrid(
        sim: SimCache,
        curlX: FloatArray, curlY: FloatArray,
        gw1: Int,
        invCellW: Float, invCellH: Float,
        gridW: Int, gridH: Int,
        w: Float, h: Float,
        stepMul: Float,
        recordTrail: Boolean
    ) {
        val tl = sim.trailLen
        for (i in 0 until sim.count) {
            // Bilinear interpolation of curl at particle position
            val fx = sim.px[i] * invCellW
            val fy = sim.py[i] * invCellH
            val gx0 = fx.toInt().coerceIn(0, gridW - 1)
            val gy0 = fy.toInt().coerceIn(0, gridH - 1)
            val sx = fx - gx0
            val sy = fy - gy0
            val omsx = 1f - sx
            val omsy = 1f - sy

            val idx00 = gy0 * gw1 + gx0
            val idx10 = idx00 + 1
            val idx01 = idx00 + gw1
            val idx11 = idx01 + 1

            val w00 = omsx * omsy
            val w10 = sx * omsy
            val w01 = omsx * sy
            val w11 = sx * sy

            sim.px[i] += (curlX[idx00] * w00 + curlX[idx10] * w10 + curlX[idx01] * w01 + curlX[idx11] * w11) * stepMul
            sim.py[i] += (curlY[idx00] * w00 + curlY[idx10] * w10 + curlY[idx01] * w01 + curlY[idx11] * w11) * stepMul

            if (sim.px[i] < 0f) sim.px[i] += w; if (sim.px[i] >= w) sim.px[i] -= w
            if (sim.py[i] < 0f) sim.py[i] += h; if (sim.py[i] >= h) sim.py[i] -= h

            if (recordTrail) {
                val base = (i * tl + sim.trailHead[i]) * 2
                sim.trail[base] = sim.px[i]
                sim.trail[base + 1] = sim.py[i]
                sim.trailHead[i] = (sim.trailHead[i] + 1) % tl
                if (sim.trailSize[i] < tl) sim.trailSize[i]++
            }
        }
    }

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float {
        val count = (params["particleCount"] as? Number)?.toFloat() ?: 4000f
        return (count / 8000f).coerceIn(0.1f, 1f)
    }
}
