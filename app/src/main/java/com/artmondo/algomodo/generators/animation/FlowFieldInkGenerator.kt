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
 * Ink-like flow field lines that grow over time.
 *
 * Lines originate from deterministic seed positions and advance through a 2D noise
 * field. The `curl` parameter adds rotational bias to the flow direction. As `time`
 * increases each line extends further, producing an elegant ink-stroke animation.
 */
class FlowFieldInkGenerator : Generator {

    override val id = "flow-field-ink"
    override val family = "animation"
    override val styleName = "Flow Field Ink"
    override val definition = "Ink-like strokes that flow along a noise vector field, growing over time."
    override val algorithmNotes =
        "Each line starts at a seeded position. Per step the direction is sampled from " +
        "noise2D(x * noiseScale, y * noiseScale) plus a curl offset that biases rotation. " +
        "The number of steps drawn is proportional to time * length, so lines appear to grow. " +
        "Stroke alpha decreases along the line for an ink-wash effect."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema = listOf(
        Parameter.NumberParam(
            name = "Particle Count",
            key = "particleCount",
            group = ParamGroup.COMPOSITION,
            help = null,
            min = 200f, max = 6000f, step = 200f, default = 2000f
        ),
        Parameter.NumberParam(
            name = "Field Scale",
            key = "fieldScale",
            group = ParamGroup.COMPOSITION,
            help = "Spatial frequency of the noise flow field",
            min = 0.3f, max = 6f, step = 0.1f, default = 1.8f
        ),
        Parameter.NumberParam(
            name = "Domain Warp",
            key = "warpStrength",
            group = ParamGroup.COMPOSITION,
            help = "Warp flow coordinates with a second noise layer for turbulent organics",
            min = 0f, max = 2f, step = 0.1f, default = 0.5f
        ),
        Parameter.SelectParam(
            name = "Ink Style",
            key = "inkStyle",
            group = ParamGroup.GEOMETRY,
            help = "fine = thin hairlines; bold = thick strokes; mixed = both; splatter = ink blobs on death",
            options = listOf("fine", "bold", "mixed", "splatter"),
            default = "mixed"
        ),
        Parameter.NumberParam(
            name = "Speed",
            key = "speed",
            group = ParamGroup.FLOW_MOTION,
            help = null,
            min = 0.5f, max = 8f, step = 0.25f, default = 2.5f
        ),
        Parameter.NumberParam(
            name = "Evolution Speed",
            key = "timeScale",
            group = ParamGroup.FLOW_MOTION,
            help = "How fast the flow field morphs over time",
            min = 0f, max = 0.5f, step = 0.01f, default = 0.08f
        ),
        Parameter.NumberParam(
            name = "Line Width",
            key = "lineWidth",
            group = ParamGroup.TEXTURE,
            help = null,
            min = 0.5f, max = 4f, step = 0.25f, default = 1.0f
        ),
        Parameter.NumberParam(
            name = "Trail Decay",
            key = "trailDecay",
            group = ParamGroup.TEXTURE,
            help = "How quickly trails fade (lower = longer, darker ink)",
            min = 0.01f, max = 0.3f, step = 0.01f, default = 0.04f
        ),
        Parameter.NumberParam(
            name = "Stroke Opacity",
            key = "opacity",
            group = ParamGroup.TEXTURE,
            help = null,
            min = 0.1f, max = 1.0f, step = 0.05f, default = 0.55f
        ),
        Parameter.SelectParam(
            name = "Color Mode",
            key = "colorMode",
            group = ParamGroup.COLOR,
            help = "palette = particle color; angle = flow direction → palette; mono = single ink color",
            options = listOf("palette", "angle", "mono"),
            default = "angle"
        )
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "particleCount" to 2000f,
        "fieldScale" to 1.8f,
        "warpStrength" to 0.5f,
        "inkStyle" to "mixed",
        "speed" to 2.5f,
        "timeScale" to 0.08f,
        "lineWidth" to 1.0f,
        "trailDecay" to 0.04f,
        "opacity" to 0.55f,
        "colorMode" to "angle"
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

        val lineCount = ((params["particleCount"] as? Number)?.toInt() ?: 2000).let {
            when (quality) {
                Quality.DRAFT -> (it * 0.5f).toInt()
                Quality.BALANCED -> it
                Quality.ULTRA -> (it * 1.5f).toInt()
            }
        }
        val maxLength = 80 // hardcoded default (not in schema)
        val noiseScale = (params["fieldScale"] as? Number)?.toFloat() ?: 1.8f
        val strokeWidth = (params["lineWidth"] as? Number)?.toFloat() ?: 1.0f
        val curl = (params["warpStrength"] as? Number)?.toFloat() ?: 0.5f

        val rng = SeededRNG(seed)
        val noise = SimplexNoise(seed)
        val colors = palette.colorInts()

        // Background: darkened first color
        val bg = colors[0]
        canvas.drawColor(
            Color.rgb(
                (Color.red(bg) * 0.1f).toInt(),
                (Color.green(bg) * 0.1f).toInt(),
                (Color.blue(bg) * 0.1f).toInt()
            )
        )

        val stepSize = dim * 0.003f // pixel step per iteration
        // Lines grow with time — at time 0 nothing visible, at time ~5 fully grown
        val growFraction = (time * 0.5f).coerceIn(0f, 1f)
        val stepsThisFrame = (maxLength * growFraction).toInt().coerceAtLeast(2)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            this.strokeWidth = strokeWidth
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        for (i in 0 until lineCount) {
            val startX = rng.random() * w
            val startY = rng.random() * h
            val colorBase = colors[(i * 7 + seed) % colors.size]
            // Per-line phase offset for noise animation
            val phase = rng.random() * 100f

            val path = Path()
            var px = startX
            var py = startY
            path.moveTo(px, py)

            var prevAngle = 0f

            for (step in 0 until stepsThisFrame) {
                val nx = px / dim * noiseScale
                val ny = py / dim * noiseScale
                // Base angle from noise
                var angle = noise.noise2D(nx + phase, ny + time * 0.05f) * PI.toFloat() * 2f
                // Apply curl: add a constant rotational bias
                angle += curl * 0.5f

                // Smooth with previous angle to reduce jitter
                if (step > 0) {
                    var diff = angle - prevAngle
                    while (diff > PI) diff -= 2f * PI.toFloat()
                    while (diff < -PI) diff += 2f * PI.toFloat()
                    angle = prevAngle + diff * 0.7f
                }
                prevAngle = angle

                px += cos(angle) * stepSize
                py += sin(angle) * stepSize

                // Boundary check — stop line if it leaves canvas
                if (px < 0 || px >= w || py < 0 || py >= h) break

                path.lineTo(px, py)
            }

            // Alpha fades toward the end of the growth
            val alpha = (220 - (i % 80)).coerceIn(80, 220)
            paint.color = Color.argb(
                alpha,
                Color.red(colorBase),
                Color.green(colorBase),
                Color.blue(colorBase)
            )
            canvas.drawPath(path, paint)
        }
    }

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float {
        val lines = (params["particleCount"] as? Number)?.toFloat() ?: 2000f
        val length = 80f // hardcoded default (not in schema)
        return ((lines * length) / 40000f).coerceIn(0.1f, 1f)
    }
}
