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
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

class PixelHarmonographGenerator : Generator {
    override val id = "pixel-harmonograph"
    override val family = "pixel-art"
    override val styleName = "Pixel Harmonograph"
    override val definition = "Damped pendulum curves rasterized onto a coarse pixel grid — harmonograph figures rendered as retro pixel art with intensity accumulation, color trails, and bloom effects."
    override val algorithmNotes = "Computes parametric harmonograph curves x(t)=sin(fx·t+φ)·e^(−d·t), y(t)=sin(fy·t)·e^(−d·t) and snaps each sample to the nearest cell on a coarse grid. Classic mode traces a single damped pendulum; lateral superimposes two detuned pendulums for beating patterns; rotary adds a circular base orbit for rosette figures; decay-trails maps color by curve parameter t instead of visit count. Render styles: accumulate maps visit density to palette brightness; trail colors pixels by their position along the curve; glow adds a 3×3 box blur for pixel bloom. Animation sweeps the phase offset over time."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema = listOf(
        Parameter.NumberParam("Grid Size", "gridSize", ParamGroup.GEOMETRY, "Pixel grid resolution — lower = chunkier pixels", 32f, 128f, 8f, 64f),
        Parameter.NumberParam("X Frequency", "freqX", ParamGroup.GEOMETRY, "Horizontal pendulum frequency — the ratio freqX:freqY determines the figure shape", 1f, 12f, 1f, 3f),
        Parameter.NumberParam("Y Frequency", "freqY", ParamGroup.GEOMETRY, "Vertical pendulum frequency", 1f, 12f, 1f, 2f),
        Parameter.SelectParam("Mode", "mode", ParamGroup.COMPOSITION, "classic: single damped pendulum | lateral: two detuned pendulums with beating | rotary: circular base + perturbation | decay-trails: color-coded spiral path", listOf("classic", "lateral", "rotary", "decay-trails"), "classic"),
        Parameter.SelectParam("Render Style", "renderStyle", ParamGroup.TEXTURE, "accumulate: brightness by visit count | trail: color by curve position | glow: bloom blur over accumulation", listOf("accumulate", "trail", "glow"), "accumulate"),
        Parameter.NumberParam("Decay", "decay", ParamGroup.GEOMETRY, "Damping factor — 0 gives closed Lissajous figures, higher values spiral inward like a real pendulum", 0f, 0.3f, 0.01f, 0.02f),
        Parameter.NumberParam("Samples", "samples", ParamGroup.COMPOSITION, "Number of curve samples — more fills the grid more densely", 2000f, 20000f, 1000f, 8000f),
        Parameter.NumberParam("Anim Speed", "animSpeed", ParamGroup.FLOW_MOTION, "Animation speed multiplier", 0.1f, 3f, 0.1f, 1f),
        Parameter.NumberParam("Reactivity", "reactivity", ParamGroup.FLOW_MOTION, "Audio reactivity strength", 0f, 2f, 0.1f, 0f)
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "gridSize" to 64f, "freqX" to 3f, "freqY" to 2f, "mode" to "classic",
        "renderStyle" to "accumulate", "decay" to 0.02f, "samples" to 8000f,
        "animSpeed" to 1f, "reactivity" to 0f
    )

    override fun renderCanvas(
        canvas: Canvas, bitmap: Bitmap, params: Map<String, Any>, seed: Int,
        palette: Palette, quality: Quality, time: Float
    ) {
        val sz = PixelArtUtil.pi(params, "gridSize", 64).coerceIn(16, 128)
        val fx = max(1, PixelArtUtil.pi(params, "freqX", 3))
        val fy = max(1, PixelArtUtil.pi(params, "freqY", 2))
        val mode = PixelArtUtil.ps(params, "mode", "classic")
        val renderStyle = PixelArtUtil.ps(params, "renderStyle", "accumulate")
        val decay = max(0f, PixelArtUtil.pf(params, "decay", 0.02f))
        val nSamples = max(500, PixelArtUtil.pi(params, "samples", 8000))
        val speed = PixelArtUtil.pf(params, "animSpeed", 1f)

        val rng = SeededRNG(seed)
        val phaseX = rng.random() * (PI * 2).toFloat()
        val phaseY = rng.random() * (PI * 2).toFloat()
        val animPhase = time * speed * 0.5f

        val effDecay = decay
        val phaseOffset = animPhase
        val scaleAudio = 1f

        val tMax = if (decay > 0.001f) min(80f, ln(200f) / max(decay, 0.001f))
                   else (2f * PI * 4f).toFloat()

        val grid = FloatArray(sz * sz)
        val isTrail = renderStyle == "trail" || mode == "decay-trails"

        val half = (sz - 1) * 0.5f
        val scale = half * 0.92f * scaleAudio

        val detune = 0.07f + rng.random() * 0.08f
        val orbitFreq = (fx + fy).toFloat()
        val orbitAmp = 0.3f + rng.random() * 0.15f

        for (i in 0..nSamples) {
            val t = i.toFloat() / nSamples * tMax
            val env = if (decay > 0.001f) exp(-effDecay * t) else 1f
            val nt = i.toFloat() / nSamples

            val x: Float; val y: Float
            when (mode) {
                "lateral" -> {
                    val x1 = sin(fx * t + phaseX + phaseOffset) * env
                    val x2 = sin((fx + detune) * t + phaseX + phaseOffset + 0.5f) * env
                    val y1 = sin(fy * t + phaseY) * env
                    val y2 = sin((fy + detune) * t + phaseY + 0.5f) * env
                    x = (x1 + x2) * 0.5f
                    y = (y1 + y2) * 0.5f
                }
                "rotary" -> {
                    val baseX = sin(fx * t + phaseX + phaseOffset) * env
                    val baseY = cos(fy * t + phaseY) * env
                    val orbX = orbitAmp * sin(orbitFreq * t)
                    val orbY = orbitAmp * cos(orbitFreq * t)
                    x = baseX + orbX * env
                    y = baseY + orbY * env
                }
                else -> {
                    x = sin(fx * t + phaseX + phaseOffset) * env
                    y = sin(fy * t + phaseY) * env
                }
            }

            val gx = (half + x * scale).roundToInt()
            val gy = (half + y * scale).roundToInt()
            if (gx < 0 || gx >= sz || gy < 0 || gy >= sz) continue

            val idx = gy * sz + gx
            if (isTrail) {
                if (nt > grid[idx]) grid[idx] = nt
            } else {
                grid[idx] += 1f
            }
        }

        var blurred: FloatArray? = null
        if (renderStyle == "glow") {
            val b = FloatArray(sz * sz)
            for (y in 0 until sz) {
                for (x in 0 until sz) {
                    var sum = 0f; var count = 0
                    for (dy in -1..1) {
                        val ny = y + dy
                        if (ny < 0 || ny >= sz) continue
                        for (dx in -1..1) {
                            val nx = x + dx
                            if (nx < 0 || nx >= sz) continue
                            sum += grid[ny * sz + nx]; count++
                        }
                    }
                    b[y * sz + x] = sum / count
                }
            }
            for (i in 0 until sz * sz) b[i] = max(grid[i], b[i] * 1.5f)
            blurred = b
        }

        val src = blurred ?: grid
        var maxVal = 0f
        for (i in 0 until sz * sz) if (src[i] > maxVal) maxVal = src[i]
        val invMax = if (maxVal > 0f) 1f / maxVal else 0f

        val colors = PixelArtUtil.paletteRgb(palette)
        val nc = colors.size
        val bg = colors[0]

        val pixels = IntArray(sz * sz)
        for (i in 0 until sz * sz) {
            val v = src[i] * invMax
            if (v < 0.01f) {
                pixels[i] = PixelArtUtil.rgb(bg[0], bg[1], bg[2])
            } else {
                val ci = 1 + (v * (nc - 1.01f)).toInt()
                val c = colors[min(ci, nc - 1)]
                pixels[i] = PixelArtUtil.rgb(c[0], c[1], c[2])
            }
        }

        PixelArtUtil.blitNearest(canvas, bitmap, pixels, sz)
    }
}
