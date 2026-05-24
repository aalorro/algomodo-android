package com.artmondo.algomodo.generators.pixelart

import android.graphics.Bitmap
import android.graphics.Canvas
import com.artmondo.algomodo.core.rng.SeededRNG
import com.artmondo.algomodo.data.palettes.Palette
import com.artmondo.algomodo.generators.Generator
import com.artmondo.algomodo.generators.ParamGroup
import com.artmondo.algomodo.generators.Parameter
import com.artmondo.algomodo.generators.Quality
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

class PixelWormGenerator : Generator {
    override val id = "pixel-worm"
    override val family = "pixel-art"
    override val styleName = "Pixel Worm"
    override val definition = "Worms with distinct movement behaviors leave fading colored trails on a coarse grid — from spiraling to grazing to drunken stumbling."
    override val algorithmNotes = "Worms walk a small grid using one of six behaviors: random walk, spiral (gradual rotation), seeker (center-attracted), grazer (avoids visited cells), zigzag (alternating perpendicular segments), or drunk (erratic 8-directional with stumbles). Trails fade over time and worm heads/bodies are visible."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema = listOf(
        Parameter.SelectParam("Worm Style", "wormStyle", ParamGroup.COMPOSITION, "Worm movement behavior", listOf("random", "spiral", "seeker", "grazer", "zigzag", "drunk"), "random"),
        Parameter.NumberParam("Number of Worms", "numWorms", ParamGroup.COMPOSITION, "How many worms walk the grid", 1f, 50f, 1f, 8f),
        Parameter.NumberParam("Step Count", "stepCount", ParamGroup.GEOMETRY, "Total steps for static render", 100f, 10000f, 100f, 2000f),
        Parameter.NumberParam("Grid Size", "gridSize", ParamGroup.GEOMETRY, "Pixel grid resolution", 32f, 128f, 8f, 64f),
        Parameter.NumberParam("Direction Bias", "directionBias", ParamGroup.FLOW_MOTION, "Probability of continuing straight (random/seeker modes)", 0f, 0.9f, 0.1f, 0.4f),
        Parameter.NumberParam("Color Shift", "colorShiftRate", ParamGroup.COLOR, "How fast worm color shifts through palette per step", 0f, 0.5f, 0.01f, 0.05f),
        Parameter.NumberParam("Trail Decay", "trailDecay", ParamGroup.TEXTURE, "How quickly trails fade (lower = faster fade)", 0.9f, 0.999f, 0.001f, 0.985f),
        Parameter.NumberParam("Steps/Frame", "stepsPerFrame", ParamGroup.FLOW_MOTION, "Worm steps per animation frame", 1f, 100f, 1f, 20f),
        Parameter.NumberParam("Anim Speed", "animSpeed", ParamGroup.FLOW_MOTION, "Animation speed multiplier", 0.1f, 3f, 0.1f, 1f),
        Parameter.NumberParam("Reactivity", "reactivity", ParamGroup.FLOW_MOTION, "Audio reactivity strength", 0f, 2f, 0.1f, 0f)
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "wormStyle" to "random", "numWorms" to 8f, "stepCount" to 2000f, "gridSize" to 64f,
        "directionBias" to 0.4f, "colorShiftRate" to 0.05f, "trailDecay" to 0.985f,
        "stepsPerFrame" to 20f, "animSpeed" to 0.5f, "reactivity" to 0f
    )

    companion object {
        private val DX = intArrayOf(0, 1, 0, -1, 1, 1, -1, -1)
        private val DY = intArrayOf(-1, 0, 1, 0, -1, 1, 1, -1)
        private const val BODY_LEN = 8

        @Volatile private var animKey: String = ""
        @Volatile private var stateTrail: FloatArray = FloatArray(0)
        @Volatile private var stateIntensity: FloatArray = FloatArray(0)
        @Volatile private var wormsX: IntArray = IntArray(0)
        @Volatile private var wormsY: IntArray = IntArray(0)
        @Volatile private var wormsColor: DoubleArray = DoubleArray(0)
        @Volatile private var wormsDir: IntArray = IntArray(0)
        @Volatile private var bodyX: IntArray = IntArray(0)
        @Volatile private var bodyY: IntArray = IntArray(0)
        @Volatile private var bodyHead: IntArray = IntArray(0)
        @Volatile private var zigPhase: IntArray = IntArray(0)
        @Volatile private var spiralAngle: DoubleArray = DoubleArray(0)
        @Volatile private var animRng: SeededRNG? = null
    }

    private fun initWorms(numWorms: Int, sz: Int, rng: SeededRNG) {
        wormsX = IntArray(numWorms)
        wormsY = IntArray(numWorms)
        wormsColor = DoubleArray(numWorms)
        wormsDir = IntArray(numWorms)
        bodyX = IntArray(numWorms * BODY_LEN) { -1 }
        bodyY = IntArray(numWorms * BODY_LEN) { -1 }
        bodyHead = IntArray(numWorms)
        zigPhase = IntArray(numWorms)
        spiralAngle = DoubleArray(numWorms)
        for (i in 0 until numWorms) {
            wormsX[i] = rng.integer(0, sz - 1)
            wormsY[i] = rng.integer(0, sz - 1)
            wormsColor[i] = (i.toDouble() / numWorms) * 10.0
            wormsDir[i] = rng.integer(0, 3)
            zigPhase[i] = rng.integer(0, 1)
            spiralAngle[i] = rng.random().toDouble() * Math.PI * 2.0
        }
    }

    private fun initLocal(numWorms: Int, sz: Int, rng: SeededRNG):
        Pair<FloatArray, FloatArray> {
        val trail = FloatArray(sz * sz)
        val intensity = FloatArray(sz * sz)
        return trail to intensity
    }

    private fun pickDirection(
        style: String, w: Int, sz: Int, bias: Float,
        intensity: FloatArray, rng: SeededRNG
    ): Int {
        val cx = wormsX[w]; val cy = wormsY[w]; val curDir = wormsDir[w]
        when (style) {
            "spiral" -> {
                spiralAngle[w] += 0.15 + rng.random() * 0.1
                val angle = spiralAngle[w]
                val dx = cos(angle); val dy = sin(angle)
                var bestDir = 0; var bestDot = Double.NEGATIVE_INFINITY
                for (d in 0 until 4) {
                    val dot = dx * DX[d] + dy * DY[d]
                    if (dot > bestDot) { bestDot = dot; bestDir = d }
                }
                return if (rng.random() < 0.15f) rng.integer(0, 3) else bestDir
            }
            "seeker" -> {
                val centerX = sz * 0.5f; val centerY = sz * 0.5f
                val dx = centerX - cx; val dy = centerY - cy
                val dist = sqrt(dx * dx + dy * dy)
                if (dist > sz * 0.3f && rng.random() < 0.6f) {
                    var bestDir = 0; var bestDot = Float.NEGATIVE_INFINITY
                    for (d in 0 until 4) {
                        val dot = dx * DX[d] + dy * DY[d]
                        if (dot > bestDot) { bestDot = dot; bestDir = d }
                    }
                    return bestDir
                }
                val r = rng.random()
                if (r < bias) return curDir
                return rng.integer(0, 3)
            }
            "grazer" -> {
                var bestDir = curDir; var bestIntensity = Float.POSITIVE_INFINITY
                val dirs = intArrayOf(0, 1, 2, 3)
                for (i in 3 downTo 1) {
                    val j = rng.integer(0, i)
                    val tmp = dirs[i]; dirs[i] = dirs[j]; dirs[j] = tmp
                }
                for (d in dirs) {
                    val nx = ((cx + DX[d]) % sz + sz) % sz
                    val ny = ((cy + DY[d]) % sz + sz) % sz
                    val ni = ny * sz + nx
                    if (intensity[ni] < bestIntensity) {
                        bestIntensity = intensity[ni]; bestDir = d
                    }
                }
                return if (rng.random() < 0.1f) rng.integer(0, 3) else bestDir
            }
            "zigzag" -> {
                val phase = zigPhase[w]
                val zigLen = 3 + (w % 4)
                zigPhase[w]++
                val segment = ((phase / zigLen)) % 2
                val baseDir = curDir
                return if (segment == 0) baseDir else (baseDir + 1) % 4
            }
            "drunk" -> {
                if (rng.random() < 0.1f) return (curDir + 2) % 4
                if (rng.random() < 0.3f) return rng.integer(0, 7)
                val r = rng.random()
                if (r < 0.3f) return curDir
                if (r < 0.6f) return (curDir + 1) % 4
                if (r < 0.9f) return (curDir + 3) % 4
                return (curDir + 2) % 4
            }
            else -> {
                val r = rng.random()
                if (r < bias) return curDir
                if (r < bias + (1 - bias) * 0.4f) return (curDir + 1) % 4
                if (r < bias + (1 - bias) * 0.8f) return (curDir + 3) % 4
                return (curDir + 2) % 4
            }
        }
    }

    private fun stepWorms(
        trail: FloatArray, intensity: FloatArray, numWorms: Int, sz: Int,
        style: String, bias: Float, colorShift: Float, decay: Float,
        rng: SeededRNG, steps: Int
    ) {
        for (s in 0 until steps) {
            for (i in 0 until sz * sz) intensity[i] *= decay
            for (w in 0 until numWorms) {
                val bOff = w * BODY_LEN
                val bh = bodyHead[w]
                bodyX[bOff + bh] = wormsX[w]
                bodyY[bOff + bh] = wormsY[w]
                bodyHead[w] = (bh + 1) % BODY_LEN

                val idx = wormsY[w] * sz + wormsX[w]
                trail[idx] = wormsColor[w].toFloat()
                intensity[idx] = min(1f, intensity[idx] + 0.6f)

                val newDir = pickDirection(style, w, sz, bias, intensity, rng)
                wormsDir[w] = newDir
                val d4 = newDir % 4
                val dx = if (newDir < DX.size) DX[newDir] else DX[d4]
                val dy = if (newDir < DY.size) DY[newDir] else DY[d4]
                wormsX[w] = ((wormsX[w] + dx) % sz + sz) % sz
                wormsY[w] = ((wormsY[w] + dy) % sz + sz) % sz
                wormsColor[w] += colorShift
            }
        }
    }

    private fun renderToPixels(
        pixels: IntArray, trail: FloatArray, intensity: FloatArray,
        numWorms: Int, sz: Int, colors: Array<IntArray>, showHeads: Boolean
    ) {
        val nc = colors.size
        val bg = colors[0]
        for (i in 0 until sz * sz) {
            val a = intensity[i]
            val r: Int; val g: Int; val b: Int
            if (a < 0.01f) {
                r = bg[0]; g = bg[1]; b = bg[2]
            } else {
                val ci = ((trail[i] % nc) + nc) % nc
                val i0 = ci.toInt()
                val i1 = (i0 + 1) % nc
                val f = ci - i0
                val rr = (colors[i0][0] + (colors[i1][0] - colors[i0][0]) * f).toInt()
                val gg = (colors[i0][1] + (colors[i1][1] - colors[i0][1]) * f).toInt()
                val bb = (colors[i0][2] + (colors[i1][2] - colors[i0][2]) * f).toInt()
                val blend = min(1f, a)
                r = (bg[0] + (rr - bg[0]) * blend).toInt()
                g = (bg[1] + (gg - bg[1]) * blend).toInt()
                b = (bg[2] + (bb - bg[2]) * blend).toInt()
            }
            pixels[i] = PixelArtUtil.rgb(r, g, b)
        }
        if (!showHeads) return

        for (w in 0 until numWorms) {
            val ci = ((wormsColor[w] % nc) + nc) % nc
            val i0 = ci.toInt()
            val i1 = (i0 + 1) % nc
            val f = (ci - i0).toFloat()
            val hr = (colors[i0][0] + (colors[i1][0] - colors[i0][0]) * f).toInt()
            val hg = (colors[i0][1] + (colors[i1][1] - colors[i0][1]) * f).toInt()
            val hb = (colors[i0][2] + (colors[i1][2] - colors[i0][2]) * f).toInt()

            val bOff = w * BODY_LEN
            val bh = bodyHead[w]
            for (j in 0 until BODY_LEN) {
                val bi = (bh + j) % BODY_LEN
                val bx = bodyX[bOff + bi]
                val by = bodyY[bOff + bi]
                if (bx < 0 || by < 0) continue
                val fade = 0.3f + 0.4f * (j.toFloat() / BODY_LEN)
                val idx = by * sz + bx
                val curArgb = pixels[idx]
                val cr = (curArgb shr 16) and 0xFF
                val cg = (curArgb shr 8) and 0xFF
                val cb = curArgb and 0xFF
                val nr = min(255, cr + ((hr - cr) * fade).toInt())
                val ng = min(255, cg + ((hg - cg) * fade).toInt())
                val nb = min(255, cb + ((hb - cb) * fade).toInt())
                pixels[idx] = PixelArtUtil.rgb(nr, ng, nb)
            }

            val hx = wormsX[w]; val hy = wormsY[w]
            if (hx in 0 until sz && hy in 0 until sz) {
                pixels[hy * sz + hx] = PixelArtUtil.rgb(
                    min(255, hr + 80), min(255, hg + 80), min(255, hb + 80)
                )
            }
        }
    }

    override fun renderCanvas(
        canvas: Canvas, bitmap: Bitmap, params: Map<String, Any>, seed: Int,
        palette: Palette, quality: Quality, time: Float
    ) {
        val sz = PixelArtUtil.pi(params, "gridSize", 64).coerceIn(16, 128)
        val numWorms = PixelArtUtil.pi(params, "numWorms", 8).coerceIn(1, 50)
        val totalSteps = PixelArtUtil.pi(params, "stepCount", 2000).coerceAtLeast(10)
        val style = PixelArtUtil.ps(params, "wormStyle", "random")
        val bias = PixelArtUtil.pf(params, "directionBias", 0.4f)
        val colorShift = PixelArtUtil.pf(params, "colorShiftRate", 0.05f)
        val decay = PixelArtUtil.pf(params, "trailDecay", 0.985f)
        val speed = PixelArtUtil.pf(params, "animSpeed", 1f)
        val spf = (PixelArtUtil.pi(params, "stepsPerFrame", 20) * speed).toInt().coerceAtLeast(1)

        val colors = PixelArtUtil.paletteRgb(palette)

        synchronized(Companion) {
            if (time == 0f) {
                val rng = SeededRNG(seed)
                initWorms(numWorms, sz, rng)
                val (trail, intensity) = initLocal(numWorms, sz, rng)
                stepWorms(trail, intensity, numWorms, sz, style, bias, colorShift, decay, rng, totalSteps)
                val pixels = IntArray(sz * sz)
                renderToPixels(pixels, trail, intensity, numWorms, sz, colors, true)
                PixelArtUtil.blitNearest(canvas, bitmap, pixels, sz)
                return
            }

            val key = "$seed|$sz|$numWorms|$style|$bias|$colorShift|$decay"
            if (animKey != key || stateTrail.size != sz * sz || wormsX.size != numWorms) {
                val rng = SeededRNG(seed)
                initWorms(numWorms, sz, rng)
                stateTrail = FloatArray(sz * sz)
                stateIntensity = FloatArray(sz * sz)
                animRng = rng
                animKey = key
            }

            stepWorms(stateTrail, stateIntensity, numWorms, sz, style, bias, colorShift, decay, animRng!!, spf)

            val pixels = IntArray(sz * sz)
            renderToPixels(pixels, stateTrail, stateIntensity, numWorms, sz, colors, true)
            PixelArtUtil.blitNearest(canvas, bitmap, pixels, sz)
        }
    }
}
