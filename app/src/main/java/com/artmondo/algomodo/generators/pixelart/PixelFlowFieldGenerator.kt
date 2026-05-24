package com.artmondo.algomodo.generators.pixelart

import android.graphics.Bitmap
import android.graphics.Canvas
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
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

class PixelFlowFieldGenerator : Generator {
    override val id = "pixel-flow-field"
    override val family = "pixel-art"
    override val styleName = "Pixel Flow Field"
    override val definition = "Particles trace an evolving vector field on a coarse grid, leaving fading colored pixel trails."
    override val algorithmNotes = "Defines a time-varying vector field (Perlin noise, curl, radial, or spiral) across a small grid. Particles follow field angles, depositing color that fades over time. The field evolves continuously, keeping particles in motion."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema = listOf(
        Parameter.SelectParam("Field Function", "fieldFunction", ParamGroup.COMPOSITION, "Vector field type that guides particles", listOf("perlin", "curl", "radial", "spiral"), "perlin"),
        Parameter.NumberParam("Particle Count", "particleCount", ParamGroup.COMPOSITION, "Number of particles in the flow field", 50f, 2000f, 50f, 500f),
        Parameter.NumberParam("Trail Length", "trailLength", ParamGroup.FLOW_MOTION, "Max steps before a particle resets", 10f, 500f, 10f, 100f),
        Parameter.NumberParam("Grid Size", "gridSize", ParamGroup.GEOMETRY, "Pixel grid resolution", 32f, 128f, 8f, 64f),
        Parameter.NumberParam("Field Scale", "fieldScale", ParamGroup.TEXTURE, "Noise scale for the vector field", 0.5f, 8f, 0.5f, 3f),
        Parameter.NumberParam("Trail Decay", "trailDecay", ParamGroup.TEXTURE, "How quickly trails fade (lower = faster fade)", 0.8f, 0.99f, 0.01f, 0.93f),
        Parameter.NumberParam("Steps/Frame", "stepsPerFrame", ParamGroup.FLOW_MOTION, "Particle steps per animation frame", 1f, 20f, 1f, 5f),
        Parameter.NumberParam("Anim Speed", "animSpeed", ParamGroup.FLOW_MOTION, "Animation speed multiplier", 0.1f, 3f, 0.1f, 1f),
        Parameter.NumberParam("Reactivity", "reactivity", ParamGroup.FLOW_MOTION, "Audio reactivity strength", 0f, 2f, 0.1f, 0f)
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "fieldFunction" to "perlin", "particleCount" to 500f, "trailLength" to 100f,
        "gridSize" to 64f, "fieldScale" to 3f, "trailDecay" to 0.93f,
        "stepsPerFrame" to 5f, "animSpeed" to 0.5f, "reactivity" to 0f
    )

    companion object {
        @Volatile private var animKey: String = ""
        @Volatile private var trailC: FloatArray = FloatArray(0)
        @Volatile private var colorIdxC: FloatArray = FloatArray(0)
        @Volatile private var pxC: DoubleArray = DoubleArray(0)
        @Volatile private var pyC: DoubleArray = DoubleArray(0)
        @Volatile private var pAgeC: DoubleArray = DoubleArray(0)
        @Volatile private var rngC: SeededRNG? = null
    }

    private fun getAngle(fn: String, x: Double, y: Double, sz: Int, scale: Float, noise: SimplexNoise, time: Float): Double {
        val nx = (x / sz * scale).toFloat()
        val ny = (y / sz * scale).toFloat()
        return when (fn) {
            "perlin" -> noise.noise2D(nx + time * 0.4f, ny + time * 0.15f) * PI * 2.0
            "curl" -> {
                val eps = 0.01f
                val t = time * 0.3f
                val dx = noise.noise2D(nx + t, ny + eps) - noise.noise2D(nx + t, ny - eps)
                val dy = noise.noise2D(nx + eps, ny + t) - noise.noise2D(nx - eps, ny + t)
                atan2(-dx.toDouble(), dy.toDouble())
            }
            "radial" -> {
                val cx = sz * 0.5; val cy = sz * 0.5
                val base = atan2(y - cy, x - cx)
                base + noise.noise2D(nx + time * 0.3f, ny) * 1.2 + sin(time * 0.5f) * 0.4
            }
            "spiral" -> {
                val cx = sz * 0.5; val cy = sz * 0.5
                val dx = x - cx; val dy = y - cy
                val r = sqrt(dx * dx + dy * dy)
                atan2(dy, dx) + r * 0.1 + noise.noise2D(nx + time * 0.3f, ny + time * 0.1f) * 0.8 + time * 0.2
            }
            else -> 0.0
        }
    }

    private fun simulateFrame(
        trail: FloatArray, colorIdx: FloatArray,
        px: DoubleArray, py: DoubleArray, pAge: DoubleArray,
        sz: Int, fieldFn: String, scale: Float, noise: SimplexNoise,
        numParticles: Int, steps: Int, trailLength: Int,
        nc: Int, time: Float, decay: Float, rng: SeededRNG
    ) {
        for (i in 0 until sz * sz) trail[i] *= decay

        for (s in 0 until steps) {
            for (p in 0 until numParticles) {
                val x = px[p]; val y = py[p]
                val gx = Math.round(x).toInt()
                val gy = Math.round(y).toInt()
                if (gx in 0 until sz && gy in 0 until sz) {
                    val idx = gy * sz + gx
                    trail[idx] = min(1f, trail[idx] + 0.3f)
                    colorIdx[idx] = ((pAge[p] * 0.15) % nc).toFloat()
                }
                val angle = getAngle(fieldFn, x, y, sz, scale, noise, time)
                px[p] += cos(angle)
                py[p] += sin(angle)
                pAge[p]++
                if (px[p] < 0 || px[p] >= sz || py[p] < 0 || py[p] >= sz || pAge[p] > trailLength) {
                    px[p] = rng.random() * sz.toDouble()
                    py[p] = rng.random() * sz.toDouble()
                    pAge[p] = 0.0
                }
            }
        }
    }

    private fun renderField(
        pixels: IntArray, trail: FloatArray, colorIdx: FloatArray, sz: Int, colors: Array<IntArray>
    ) {
        val nc = colors.size
        val bg = colors[0]
        for (i in 0 until sz * sz) {
            val t = trail[i]
            val r: Int; val g: Int; val b: Int
            if (t < 0.01f) {
                r = bg[0]; g = bg[1]; b = bg[2]
            } else {
                val ci = ((colorIdx[i] % nc) + nc) % nc
                val i0 = ci.toInt()
                val i1 = (i0 + 1) % nc
                val f = ci - i0
                val rr = (colors[i0][0] + (colors[i1][0] - colors[i0][0]) * f).toInt()
                val gg = (colors[i0][1] + (colors[i1][1] - colors[i0][1]) * f).toInt()
                val bb = (colors[i0][2] + (colors[i1][2] - colors[i0][2]) * f).toInt()
                val a = min(1f, t)
                r = (bg[0] + (rr - bg[0]) * a).toInt()
                g = (bg[1] + (gg - bg[1]) * a).toInt()
                b = (bg[2] + (bb - bg[2]) * a).toInt()
            }
            pixels[i] = PixelArtUtil.rgb(r, g, b)
        }
    }

    override fun renderCanvas(
        canvas: Canvas, bitmap: Bitmap, params: Map<String, Any>, seed: Int,
        palette: Palette, quality: Quality, time: Float
    ) {
        val sz = PixelArtUtil.pi(params, "gridSize", 64).coerceIn(16, 128)
        val fieldFn = PixelArtUtil.ps(params, "fieldFunction", "perlin")
        val numParticles = PixelArtUtil.pi(params, "particleCount", 500).coerceIn(10, 2000)
        val trailLength = PixelArtUtil.pi(params, "trailLength", 100).coerceIn(5, 500)
        val scale = PixelArtUtil.pf(params, "fieldScale", 3f)
        val speed = PixelArtUtil.pf(params, "animSpeed", 0.5f)
        val spf = (PixelArtUtil.pi(params, "stepsPerFrame", 5) * speed).toInt().coerceAtLeast(1)
        val decay = PixelArtUtil.pf(params, "trailDecay", 0.93f)
        val nc = palette.colors.size

        val noise = SimplexNoise(seed)
        val colors = PixelArtUtil.paletteRgb(palette)

        if (time == 0f) {
            val rng = SeededRNG(seed)
            val trail = FloatArray(sz * sz)
            val colorIdx = FloatArray(sz * sz)
            val px = DoubleArray(numParticles)
            val py = DoubleArray(numParticles)
            val pAge = DoubleArray(numParticles)
            for (i in 0 until numParticles) {
                px[i] = rng.random() * sz.toDouble()
                py[i] = rng.random() * sz.toDouble()
                pAge[i] = rng.random() * trailLength * 0.5
            }
            val totalFrames = (trailLength * 0.5f).toInt().coerceAtLeast(30)
            for (f in 0 until totalFrames) {
                simulateFrame(trail, colorIdx, px, py, pAge, sz, fieldFn, scale, noise,
                    numParticles, spf, trailLength, nc, f * 0.016f, decay, rng)
            }
            val pixels = IntArray(sz * sz)
            renderField(pixels, trail, colorIdx, sz, colors)
            PixelArtUtil.blitNearest(canvas, bitmap, pixels, sz)
            return
        }

        synchronized(Companion) {
            val key = "$seed|$sz|$fieldFn|$scale|$numParticles|$trailLength"
            if (animKey != key || trailC.size != sz * sz || pxC.size != numParticles) {
                val rng = SeededRNG(seed)
                trailC = FloatArray(sz * sz)
                colorIdxC = FloatArray(sz * sz)
                pxC = DoubleArray(numParticles)
                pyC = DoubleArray(numParticles)
                pAgeC = DoubleArray(numParticles)
                for (i in 0 until numParticles) {
                    pxC[i] = rng.random() * sz.toDouble()
                    pyC[i] = rng.random() * sz.toDouble()
                    pAgeC[i] = rng.random() * trailLength * 0.5
                }
                rngC = rng
                animKey = key
            }
            simulateFrame(trailC, colorIdxC, pxC, pyC, pAgeC, sz, fieldFn, scale, noise,
                numParticles, spf, trailLength, nc, time * speed, decay, rngC!!)

            val pixels = IntArray(sz * sz)
            renderField(pixels, trailC, colorIdxC, sz, colors)
            PixelArtUtil.blitNearest(canvas, bitmap, pixels, sz)
        }
    }
}
