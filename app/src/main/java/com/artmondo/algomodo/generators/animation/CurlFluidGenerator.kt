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
 * giving a realistic fluid appearance.
 *
 * Trails are rendered via offscreen accumulation: a semi-transparent dark overlay
 * fades previous content each frame, while new line segments (prev→current position)
 * are drawn on top. Particles respawn at random positions when they leave the canvas
 * or exceed their maximum age, matching the web version's behavior.
 *
 * Supports 6 movement modes (curl, spiral, vortex, wave, orbit, turbulent)
 * and 6 color modes (palette, velocity, position, angle, age, depth).
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
        "is advected using bilinear interpolation of the grid curl. Movement modes add " +
        "spiral, vortex, wave, orbital, or turbulent forces. Trails are rendered via " +
        "offscreen accumulation with semi-transparent overlay fading."
    override val supportsVector = false
    override val supportsAnimation = true

    companion object {
        private const val MOVE_CURL = 0
        private const val MOVE_SPIRAL = 1
        private const val MOVE_VORTEX = 2
        private const val MOVE_WAVE = 3
        private const val MOVE_ORBIT = 4
        private const val MOVE_TURBULENT = 5
        private const val BG_R = 8
        private const val BG_G = 8
        private const val BG_B = 8
    }

    override val parameterSchema = listOf(
        Parameter.NumberParam(
            name = "Particle Count", key = "particleCount", group = ParamGroup.COMPOSITION,
            help = null, min = 500f, max = 4000f, step = 500f, default = 4000f
        ),
        Parameter.NumberParam(
            name = "Noise Scale", key = "noiseScale", group = ParamGroup.COMPOSITION,
            help = "Spatial scale of the curl field",
            min = 0.2f, max = 5f, step = 0.1f, default = 1.2f
        ),
        Parameter.NumberParam(
            name = "Speed", key = "speed", group = ParamGroup.FLOW_MOTION,
            help = null, min = 0.5f, max = 8f, step = 0.25f, default = 3.0f
        ),
        Parameter.NumberParam(
            name = "Trail Decay", key = "trailDecay", group = ParamGroup.TEXTURE,
            help = "Trail fade rate (lower = longer)",
            min = 0.005f, max = 0.2f, step = 0.005f, default = 0.025f
        ),
        Parameter.NumberParam(
            name = "Evolution", key = "evolution", group = ParamGroup.FLOW_MOTION,
            help = "How fast the field evolves",
            min = 0f, max = 0.3f, step = 0.005f, default = 0.05f
        ),
        Parameter.NumberParam(
            name = "Line Width", key = "lineWidth", group = ParamGroup.GEOMETRY,
            help = null, min = 0.25f, max = 3f, step = 0.25f, default = 0.75f
        ),
        Parameter.SelectParam(
            name = "Movement", key = "movement", group = ParamGroup.FLOW_MOTION,
            help = "curl: pure divergence-free flow | spiral: inward pull | vortex: multiple swirl centers | wave: sinusoidal layers | orbit: circular paths | turbulent: chaotic high-frequency",
            options = listOf("curl", "spiral", "vortex", "wave", "orbit", "turbulent"),
            default = "curl"
        ),
        Parameter.SelectParam(
            name = "Color Mode", key = "colorMode", group = ParamGroup.COLOR,
            help = "palette: static index | velocity: speed mapped | position: spatial gradient | angle: flow direction | age: trail lifetime | depth: noise field value",
            options = listOf("palette", "velocity", "position", "angle", "age", "depth"),
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
        "movement" to "curl",
        "colorMode" to "palette"
    )

    // ---- Thread-local render state (isolates live canvas from export thread) ----
    private val threadState = ThreadLocal<RenderState>()

    private class RenderState(
        var sim: SimCache? = null,
        var offBitmap: Bitmap? = null,
        var offW: Int = 0,
        var offH: Int = 0,
        var gridNoise: FloatArray? = null,
        var gridCurlX: FloatArray? = null,
        var gridCurlY: FloatArray? = null,
        var gridTurbX: FloatArray? = null,
        var gridTurbY: FloatArray? = null,
        var lineBuf: FloatArray? = null
    )

    private class SimCache(
        val seed: Int,
        val count: Int,
        val noiseScale: Float,
        val speed: Float,
        val evolution: Float,
        val moveId: Int,
        val w: Float,
        val h: Float,
        var stepCount: Int,
        val px: FloatArray,
        val py: FloatArray,
        val prevX: FloatArray,         // previous position for segment drawing
        val prevY: FloatArray,
        val age: IntArray,             // per-particle age in steps
        val maxAge: IntArray,          // respawn after this many steps
        val vxArr: FloatArray,         // velocity persistence for smoothing (movement modes)
        val vyArr: FloatArray,
        val colorIdx: IntArray         // persistent per-particle palette color index
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
        val wi = bitmap.width
        val hi = bitmap.height
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
        val movement = (params["movement"] as? String) ?: "curl"

        val moveId = when (movement) {
            "spiral" -> MOVE_SPIRAL
            "vortex" -> MOVE_VORTEX
            "wave" -> MOVE_WAVE
            "orbit" -> MOVE_ORBIT
            "turbulent" -> MOVE_TURBULENT
            else -> MOVE_CURL
        }

        val noise = SimplexNoise(seed)
        val colors = palette.colorInts()
        val nColors = colors.size

        val dt = 0.016f
        val totalSteps = (time / dt).toInt().coerceAtLeast(1)
        val fadeAlpha = (trailDecay * 255f).toInt().coerceIn(1, 51)
        val segAlpha = 178 // ~0.7 opacity, matching web version

        // ---- Thread-local render state (isolates live canvas from export thread) ----
        val state = threadState.get() ?: RenderState().also { threadState.set(it) }

        var off = state.offBitmap
        var needClear = false
        if (off == null || off.isRecycled || state.offW != wi || state.offH != hi) {
            off?.recycle()
            off = Bitmap.createBitmap(wi, hi, Bitmap.Config.ARGB_8888)
            state.offBitmap = off
            state.offW = wi; state.offH = hi
            needClear = true
        }
        val oc = Canvas(off)

        // ---- Curl grid setup ----
        val gridW = 48
        val gridH = (48f * h / w).toInt().coerceIn(24, 96)
        val gw1 = gridW + 1
        val gh1 = gridH + 1
        val noiseW = gridW + 3
        val noiseH = gridH + 3
        val cellW = w / gridW
        val cellH = h / gridH
        val invCellW = 1f / cellW
        val invCellH = 1f / cellH
        val nsOverDim = noiseScale / dim
        val halfW = w * 0.5f
        val halfH = h * 0.5f
        val minDim = min(w, h)

        val noiseSz = noiseW * noiseH
        val curlSz = gw1 * gh1
        val nv = if (state.gridNoise?.size == noiseSz) state.gridNoise!! else FloatArray(noiseSz).also { state.gridNoise = it }
        val cx = if (state.gridCurlX?.size == curlSz) state.gridCurlX!! else FloatArray(curlSz).also { state.gridCurlX = it }
        val cy = if (state.gridCurlY?.size == curlSz) state.gridCurlY!! else FloatArray(curlSz).also { state.gridCurlY = it }

        val turbX: FloatArray?
        val turbY: FloatArray?
        if (moveId == MOVE_TURBULENT) {
            turbX = if (state.gridTurbX?.size == curlSz) state.gridTurbX!! else FloatArray(curlSz).also { state.gridTurbX = it }
            turbY = if (state.gridTurbY?.size == curlSz) state.gridTurbY!! else FloatArray(curlSz).also { state.gridTurbY = it }
        } else {
            turbX = null; turbY = null
        }

        // ---- Line drawing paint ----
        val paint = Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = lineWidth + 0.5f
            strokeCap = Paint.Cap.BUTT
            isAntiAlias = false
        }

        // Ensure line buffer is big enough (4 floats per particle segment)
        val bufSize = particleCount * 4
        val buf = if (state.lineBuf != null && state.lineBuf!!.size >= bufSize) state.lineBuf!!
                  else FloatArray(bufSize).also { state.lineBuf = it }

        // Number of drawback frames for cold-start visual buildup
        val drawbackFrames = (4.6f / trailDecay).toInt().coerceIn(60, 300)

        // ---- Resolve simulation state ----
        val cached = state.sim
        val sim: SimCache

        if (!needClear && cached != null &&
            cached.seed == seed &&
            cached.count == particleCount &&
            cached.noiseScale == noiseScale &&
            cached.speed == speed &&
            cached.evolution == evolution &&
            cached.moveId == moveId &&
            cached.w == w &&
            cached.h == h &&
            totalSteps >= cached.stepCount &&
            totalSteps - cached.stepCount < 120
        ) {
            // ---- Incremental update: advance + draw new frames to offscreen ----
            sim = cached
            val stepsNeeded = totalSteps - sim.stepCount
            for (s in 0 until stepsNeeded) {
                val noiseT = (sim.stepCount + s) * dt * evolution
                buildCurlGrid(noise, nv, cx, cy, noiseW, noiseH, gw1, gh1, cellW, cellH, nsOverDim, noiseT, speed)
                if (turbX != null && turbY != null) {
                    buildTurbGrid(noise, turbX, turbY, gw1, gh1, cellW, cellH, nsOverDim, noiseT, speed)
                }
                advanceParticles(sim, cx, cy, turbX, turbY, gw1, invCellW, invCellH, gridW, gridH, w, h,
                    1f, moveId, halfW, halfH, minDim, speed, noiseScale, noiseT, nColors)
                oc.drawColor(Color.argb(fadeAlpha, BG_R, BG_G, BG_B))
                drawSegments(oc, sim, paint, buf, palette, colors, nColors, colorMode,
                    segAlpha, cx, cy, gw1, invCellW, invCellH, gridW, gridH, nv, noiseW, noiseH, speed, w, h)
            }
            sim.stepCount = totalSteps
        } else {
            // ---- Cold start: rebuild simulation + offscreen ----
            sim = SimCache(
                seed = seed,
                count = particleCount,
                noiseScale = noiseScale,
                speed = speed,
                evolution = evolution,
                moveId = moveId,
                w = w, h = h,
                stepCount = 0,
                px = FloatArray(particleCount),
                py = FloatArray(particleCount),
                prevX = FloatArray(particleCount),
                prevY = FloatArray(particleCount),
                age = IntArray(particleCount),
                maxAge = IntArray(particleCount),
                vxArr = FloatArray(particleCount),
                vyArr = FloatArray(particleCount),
                colorIdx = IntArray(particleCount)
            )

            val rng = SeededRNG(seed)
            for (i in 0 until particleCount) {
                sim.px[i] = rng.random() * w
                sim.py[i] = rng.random() * h
                sim.prevX[i] = sim.px[i]
                sim.prevY[i] = sim.py[i]
                sim.maxAge[i] = 100 + (rng.random() * 300f).toInt()
                sim.colorIdx[i] = (rng.random() * nColors).toInt().coerceIn(0, nColors - 1)
            }

            // Coarse skip to near the drawback window (advance only, no drawing)
            val drawStart = (totalSteps - drawbackFrames).coerceAtLeast(0)
            val coarseStep = 8
            var step = 0
            while (step < drawStart) {
                val noiseT = step * dt * evolution
                buildCurlGrid(noise, nv, cx, cy, noiseW, noiseH, gw1, gh1, cellW, cellH, nsOverDim, noiseT, speed)
                if (turbX != null && turbY != null) {
                    buildTurbGrid(noise, turbX, turbY, gw1, gh1, cellW, cellH, nsOverDim, noiseT, speed)
                }
                advanceParticles(sim, cx, cy, turbX, turbY, gw1, invCellW, invCellH, gridW, gridH, w, h,
                    coarseStep.toFloat(), moveId, halfW, halfH, minDim, speed, noiseScale, noiseT, nColors)
                step += coarseStep
            }

            // Clear offscreen for fresh drawback
            off.eraseColor(Color.rgb(BG_R, BG_G, BG_B))

            // Fine steps with drawing — build up the accumulated trail texture
            for (s in step until totalSteps) {
                val noiseT = s * dt * evolution
                buildCurlGrid(noise, nv, cx, cy, noiseW, noiseH, gw1, gh1, cellW, cellH, nsOverDim, noiseT, speed)
                if (turbX != null && turbY != null) {
                    buildTurbGrid(noise, turbX, turbY, gw1, gh1, cellW, cellH, nsOverDim, noiseT, speed)
                }
                advanceParticles(sim, cx, cy, turbX, turbY, gw1, invCellW, invCellH, gridW, gridH, w, h,
                    1f, moveId, halfW, halfH, minDim, speed, noiseScale, noiseT, nColors)
                oc.drawColor(Color.argb(fadeAlpha, BG_R, BG_G, BG_B))
                drawSegments(oc, sim, paint, buf, palette, colors, nColors, colorMode,
                    segAlpha, cx, cy, gw1, invCellW, invCellH, gridW, gridH, nv, noiseW, noiseH, speed, w, h)
            }
            sim.stepCount = totalSteps
        }

        state.sim = sim

        // ---- Copy offscreen to output canvas ----
        canvas.drawBitmap(off, 0f, 0f, null)
    }

    /** Draw one frame's line segments (prev→current) to the offscreen canvas, batched by color. */
    private fun drawSegments(
        oc: Canvas, sim: SimCache, paint: Paint, buf: FloatArray,
        palette: Palette, colors: List<Int>, nColors: Int, colorMode: String,
        segAlpha: Int,
        curlX: FloatArray, curlY: FloatArray, gw1: Int,
        invCellW: Float, invCellH: Float, gridW: Int, gridH: Int,
        nv: FloatArray, noiseW: Int, noiseH: Int,
        speed: Float, w: Float, h: Float
    ) {
        val dimSq004 = min(w, h).let { it * it * 0.04f }
        val count = sim.count

        if (colorMode == "palette") {
            // Batch by persistent palette color index
            for (ci in 0 until nColors) {
                var n = 0
                for (i in 0 until count) {
                    if (sim.colorIdx[i] != ci || sim.age[i] < 2) continue
                    val dx = sim.px[i] - sim.prevX[i]
                    val dy = sim.py[i] - sim.prevY[i]
                    if (dx * dx + dy * dy > dimSq004) continue
                    buf[n] = sim.prevX[i]; buf[n + 1] = sim.prevY[i]
                    buf[n + 2] = sim.px[i]; buf[n + 3] = sim.py[i]
                    n += 4
                }
                if (n > 0) {
                    val c = colors[ci]
                    paint.color = Color.argb(segAlpha, Color.red(c), Color.green(c), Color.blue(c))
                    oc.drawLines(buf, 0, n, paint)
                }
            }
        } else {
            // Quantized bucket mode for velocity/position/angle/age/depth
            val quantBuckets = 24
            val bucketFor = IntArray(count)
            val invPI2 = 1f / (2f * PI.toFloat())
            val ageCycle = 200f

            for (i in 0 until count) {
                if (sim.age[i] < 2) { bucketFor[i] = -1; continue }
                val dx = sim.px[i] - sim.prevX[i]
                val dy = sim.py[i] - sim.prevY[i]
                if (dx * dx + dy * dy > dimSq004) { bucketFor[i] = -1; continue }

                bucketFor[i] = when (colorMode) {
                    "velocity" -> {
                        val gfx = sim.px[i] * invCellW
                        val gfy = sim.py[i] * invCellH
                        val gx0 = gfx.toInt().coerceIn(0, gridW - 1)
                        val gy0 = gfy.toInt().coerceIn(0, gridH - 1)
                        val idx = gy0 * gw1 + gx0
                        val mag = sqrt(curlX[idx] * curlX[idx] + curlY[idx] * curlY[idx]) / (speed * 1.5f)
                        (mag.coerceIn(0f, 0.999f) * quantBuckets).toInt()
                    }
                    "angle" -> {
                        val gfx = sim.px[i] * invCellW
                        val gfy = sim.py[i] * invCellH
                        val gx0 = gfx.toInt().coerceIn(0, gridW - 1)
                        val gy0 = gfy.toInt().coerceIn(0, gridH - 1)
                        val idx = gy0 * gw1 + gx0
                        val ang = (atan2(curlY[idx], curlX[idx]) + PI.toFloat()) * invPI2
                        (ang.coerceIn(0f, 0.999f) * quantBuckets).toInt()
                    }
                    "age" -> {
                        val ageT = (sim.age[i] % ageCycle.toInt()) / ageCycle
                        (ageT.coerceIn(0f, 0.999f) * quantBuckets).toInt()
                    }
                    "depth" -> {
                        val gfx = sim.px[i] * invCellW + 1f
                        val gfy = sim.py[i] * invCellH + 1f
                        val gx0 = gfx.toInt().coerceIn(0, noiseW - 2)
                        val gy0 = gfy.toInt().coerceIn(0, noiseH - 2)
                        val fx = gfx - gx0; val fy = gfy - gy0
                        val idx00 = gy0 * noiseW + gx0
                        val nVal = (1f - fy) * ((1f - fx) * nv[idx00] + fx * nv[idx00 + 1]) +
                                   fy * ((1f - fx) * nv[idx00 + noiseW] + fx * nv[idx00 + noiseW + 1])
                        val t = ((nVal + 1f) * 0.5f).coerceIn(0f, 0.999f)
                        (t * quantBuckets).toInt()
                    }
                    else -> { // position
                        val posVal = (sim.px[i] / w + sim.py[i] / h) * 0.5f
                        (posVal.coerceIn(0f, 0.999f) * quantBuckets).toInt()
                    }
                }
            }

            for (bucket in 0 until quantBuckets) {
                var n = 0
                for (i in 0 until count) {
                    if (bucketFor[i] != bucket) continue
                    buf[n] = sim.prevX[i]; buf[n + 1] = sim.prevY[i]
                    buf[n + 2] = sim.px[i]; buf[n + 3] = sim.py[i]
                    n += 4
                }
                if (n > 0) {
                    val t = (bucket + 0.5f) / quantBuckets
                    val c = palette.lerpColor(t)
                    paint.color = Color.argb(segAlpha, Color.red(c), Color.green(c), Color.blue(c))
                    oc.drawLines(buf, 0, n, paint)
                }
            }
        }
    }

    /** Build curl vector field on a coarse grid from noise values. */
    private fun buildCurlGrid(
        noise: SimplexNoise,
        nv: FloatArray,
        curlX: FloatArray, curlY: FloatArray,
        noiseW: Int, noiseH: Int,
        gw1: Int, gh1: Int,
        cellW: Float, cellH: Float,
        nsOverDim: Float, noiseT: Float, speed: Float
    ) {
        for (gy in 0 until noiseH) {
            val ny = (gy - 1) * cellH * nsOverDim
            val rowOff = gy * noiseW
            for (gx in 0 until noiseW) {
                nv[rowOff + gx] = noise.noise2D((gx - 1) * cellW * nsOverDim, ny + noiseT)
            }
        }
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

    /** Build high-frequency turbulence grids (4x spatial scale). */
    private fun buildTurbGrid(
        noise: SimplexNoise,
        turbX: FloatArray, turbY: FloatArray,
        gw1: Int, gh1: Int,
        cellW: Float, cellH: Float,
        nsOverDim: Float, noiseT: Float, speed: Float
    ) {
        val hfScale = nsOverDim * 4f
        val speedMul = speed * 1.2f
        for (gy in 0 until gh1) {
            val ny = gy * cellH * hfScale
            val cRow = gy * gw1
            for (gx in 0 until gw1) {
                val nx = gx * cellW * hfScale
                turbX[cRow + gx] = noise.noise2D(nx + noiseT * 2f, ny) * speedMul
                turbY[cRow + gx] = noise.noise2D(nx, ny + noiseT * 2f) * speedMul
            }
        }
    }

    /** Advance all particles using bilinear interpolation of the curl grid + movement forces. */
    private fun advanceParticles(
        sim: SimCache,
        curlX: FloatArray, curlY: FloatArray,
        turbX: FloatArray?, turbY: FloatArray?,
        gw1: Int,
        invCellW: Float, invCellH: Float,
        gridW: Int, gridH: Int,
        w: Float, h: Float,
        stepMul: Float,
        moveId: Int, halfW: Float, halfH: Float, minDim: Float,
        speed: Float, noiseScale: Float, noiseT: Float,
        nColors: Int
    ) {
        for (i in 0 until sim.count) {
            // Store previous position for segment drawing
            sim.prevX[i] = sim.px[i]
            sim.prevY[i] = sim.py[i]

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

            var vx = curlX[idx00] * w00 + curlX[idx10] * w10 + curlX[idx01] * w01 + curlX[idx11] * w11
            var vy = curlY[idx00] * w00 + curlY[idx10] * w10 + curlY[idx01] * w01 + curlY[idx11] * w11

            // Apply movement mode forces — scale relative to curl magnitude
            val curlMag = sqrt(vx * vx + vy * vy).coerceAtLeast(0.5f)
            when (moveId) {
                MOVE_SPIRAL -> {
                    val dx = sim.px[i] - halfW
                    val dy = sim.py[i] - halfH
                    val dist = sqrt(dx * dx + dy * dy) + 1f
                    val pull = curlMag * 0.5f / (1f + dist * 0.001f)
                    vx = vx * 0.4f - (dx / dist) * pull
                    vy = vy * 0.4f - (dy / dist) * pull
                }
                MOVE_VORTEX -> {
                    val v1x = halfW * 0.6f; val v1y = halfH * 0.6f
                    val v2x = halfW * 1.4f; val v2y = halfH * 1.4f
                    var tvx = 0f; var tvy = 0f
                    val centers = floatArrayOf(v1x, v1y, v2x, v2y, halfW, halfH)
                    for (ci in 0 until 6 step 2) {
                        val dx = sim.px[i] - centers[ci]
                        val dy = sim.py[i] - centers[ci + 1]
                        val dist = sqrt(dx * dx + dy * dy) + 10f
                        val str = curlMag * 2f / (1f + dist * 0.003f)
                        tvx += -dy / dist * str
                        tvy += dx / dist * str
                    }
                    vx = tvx * 0.7f + vx * 0.3f
                    vy = tvy * 0.7f + vy * 0.3f
                }
                MOVE_WAVE -> {
                    val waveFreq = noiseScale * 3f
                    val waveAmp = curlMag * 0.7f
                    val nx = sim.px[i] / minDim * noiseScale
                    val ny = sim.py[i] / minDim * noiseScale
                    val phase = noiseT * 4f / (sim.evolution.coerceAtLeast(0.001f))
                    vx = vx * 0.5f + sin(ny * waveFreq + phase).toFloat() * waveAmp
                    vy = vy * 0.5f + cos(nx * waveFreq + phase).toFloat() * waveAmp * 0.6f
                }
                MOVE_ORBIT -> {
                    val dx = sim.px[i] - halfW
                    val dy = sim.py[i] - halfH
                    val dist = sqrt(dx * dx + dy * dy) + 1f
                    val orbSpeed = curlMag * 0.8f / (1f + dist * 0.0005f)
                    vx = -dy / dist * orbSpeed + vx * 0.2f
                    vy = dx / dist * orbSpeed + vy * 0.2f
                }
                MOVE_TURBULENT -> {
                    if (turbX != null && turbY != null) {
                        vx += turbX[idx00] * w00 + turbX[idx10] * w10 + turbX[idx01] * w01 + turbX[idx11] * w11
                        vy += turbY[idx00] * w00 + turbY[idx10] * w10 + turbY[idx01] * w01 + turbY[idx11] * w11
                    }
                }
                // MOVE_CURL: pure curl, no modification
            }

            // Velocity smoothing — accumulates directional bias from movement forces over time
            if (moveId != MOVE_CURL) {
                sim.vxArr[i] = sim.vxArr[i] * 0.8f + vx * 0.2f
                sim.vyArr[i] = sim.vyArr[i] * 0.8f + vy * 0.2f
                vx = sim.vxArr[i]
                vy = sim.vyArr[i]
            }

            sim.px[i] += vx * stepMul
            sim.py[i] += vy * stepMul

            sim.age[i] += stepMul.toInt().coerceAtLeast(1)

            // Respawn if off-screen or max age exceeded (matching web version)
            if (sim.px[i] < 0f || sim.px[i] >= w || sim.py[i] < 0f || sim.py[i] >= h ||
                sim.age[i] > sim.maxAge[i]
            ) {
                sim.px[i] = (Math.random() * w).toFloat()
                sim.py[i] = (Math.random() * h).toFloat()
                sim.prevX[i] = sim.px[i]
                sim.prevY[i] = sim.py[i]
                sim.vxArr[i] = 0f
                sim.vyArr[i] = 0f
                sim.age[i] = 0
                sim.maxAge[i] = 100 + (Math.random() * 300).toInt()
                sim.colorIdx[i] = (Math.random() * nColors).toInt().coerceIn(0, nColors - 1)
            }
        }
    }

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float {
        val count = (params["particleCount"] as? Number)?.toFloat() ?: 4000f
        return (count / 8000f).coerceIn(0.1f, 1f)
    }
}
