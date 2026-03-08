package com.artmondo.algomodo.generators.plotter

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import com.artmondo.algomodo.core.rng.SimplexNoise
import com.artmondo.algomodo.data.palettes.Palette
import com.artmondo.algomodo.generators.Generator
import com.artmondo.algomodo.generators.ParamGroup
import com.artmondo.algomodo.generators.Parameter
import com.artmondo.algomodo.generators.Quality
import com.artmondo.algomodo.rendering.SvgPath
import kotlin.math.PI
import kotlin.math.sin

/**
 * Scribble-shading generator.
 *
 * Draws wavy horizontal scribble lines whose density and amplitude vary
 * according to a simplex noise field, simulating hand-drawn tonal shading.
 */
class PlotterScribbleGenerator : Generator {

    override val id = "plotter-scribble-shading"
    override val family = "plotter"
    override val styleName = "Scribble Shading"
    override val definition =
        "Wavy scribble lines with noise-driven density for tonal shading."
    override val algorithmNotes =
        "Horizontal lines are drawn across the canvas. Each line oscillates sinusoidally " +
        "with the given amplitude and frequency. A simplex noise field modulates the local " +
        "line spacing: darker noise values produce tighter line spacing (more ink density)."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema = listOf(
        Parameter.NumberParam("Passes", "passCount", ParamGroup.COMPOSITION, "Number of overlapping angular stroke passes", 1f, 6f, 1f, 3f),
        Parameter.NumberParam("Line Spacing", "lineSpacing", ParamGroup.GEOMETRY, "Gap between parallel lines within each pass (px)", 3f, 24f, 1f, 8f),
        Parameter.SelectParam("Stroke Style", "strokeStyle", ParamGroup.TEXTURE, "straight: wobble only | wavy: sinusoidal | zigzag: sharp angles | loop: curly scribble", listOf("straight", "wavy", "zigzag", "loop"), "straight"),
        Parameter.NumberParam("Wobble", "wobble", ParamGroup.TEXTURE, "Noise-driven lateral deviation of each stroke (px)", 0f, 20f, 1f, 6f),
        Parameter.NumberParam("Density Scale", "densityScale", ParamGroup.TEXTURE, "Spatial frequency of the FBM density field", 0.5f, 8f, 0.25f, 2.5f),
        Parameter.SelectParam("Density Style", "densityStyle", ParamGroup.TEXTURE, "fbm: smooth | ridged: sharp creases | radial: center-focused | turbulent: chaotic", listOf("fbm", "ridged", "radial", "turbulent"), "fbm"),
        Parameter.NumberParam("Density Threshold", "densityThreshold", ParamGroup.TEXTURE, "Minimum density to draw a stroke at a point", 0.0f, 0.8f, 0.05f, 0.3f),
        Parameter.NumberParam("Opacity", "strokeOpacity", ParamGroup.COLOR, null, 0.1f, 1.0f, 0.05f, 0.65f),
        Parameter.NumberParam("Line Width", "lineWidth", ParamGroup.TEXTURE, null, 0.3f, 3f, 0.1f, 0.7f),
        Parameter.BooleanParam("Variable Width", "variableWidth", ParamGroup.TEXTURE, "Line width varies with density — thicker in dense areas", false),
        Parameter.SelectParam("Color Mode", "colorMode", ParamGroup.COLOR, "palette-pass: each angular pass uses a different palette colour", listOf("monochrome", "palette-pass", "palette-density", "palette-noise"), "palette-pass"),
        Parameter.SelectParam("Background", "background", ParamGroup.COLOR, null, listOf("white", "cream", "dark"), "cream"),
        Parameter.NumberParam("Anim Speed", "animSpeed", ParamGroup.FLOW_MOTION, "Flowing density field animation — 0 = static", 0f, 1f, 0.05f, 0f)
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "passCount" to 3f,
        "lineSpacing" to 8f,
        "strokeStyle" to "straight",
        "wobble" to 6f,
        "densityScale" to 2.5f,
        "densityStyle" to "fbm",
        "densityThreshold" to 0.3f,
        "strokeOpacity" to 0.65f,
        "lineWidth" to 0.7f,
        "variableWidth" to false,
        "colorMode" to "palette-pass",
        "background" to "cream",
        "animSpeed" to 0f
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
        val lineSpacing = (params["lineSpacing"] as? Number)?.toFloat() ?: 8f
        val amplitude = 8f
        val frequency = 2f
        val lineWidth = (params["lineWidth"] as? Number)?.toFloat() ?: 0.7f

        val animSpeed = (params["animSpeed"] as? Number)?.toFloat() ?: 0f
        val timeOff = time * animSpeed

        val noise = SimplexNoise(seed)
        val paletteColors = palette.colorInts()

        canvas.drawColor(Color.BLACK)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = lineWidth
            strokeCap = Paint.Cap.ROUND
        }

        val baseSpacing = lineSpacing
        val stepPx = when (quality) {
            Quality.DRAFT -> 4f
            Quality.BALANCED -> 2f
            Quality.ULTRA -> 1f
        }

        var y = 0f
        var lineIdx = 0
        while (y < h) {
            // Noise-modulated local spacing
            val centerNoise = (noise.fbm(0.5f + timeOff, y / h * 3f, 3) + 1f) * 0.5f
            val localSpacing = baseSpacing * (0.3f + centerNoise * 1.4f)

            paint.color = paletteColors[lineIdx % paletteColors.size]
            paint.alpha = 180

            val path = Path()
            var x = 0f
            var first = true

            while (x <= w) {
                val nv = (noise.noise2D(x / w * frequency * 4f + timeOff, y / h * 3f + seed * 0.01f) + 1f) * 0.5f
                val localAmp = amplitude * nv
                val waveY = y + sin(x / w * frequency * 2f * PI.toFloat() * 10f) * localAmp

                if (first) {
                    path.moveTo(x, waveY)
                    first = false
                } else {
                    path.lineTo(x, waveY)
                }
                x += stepPx
            }

            canvas.drawPath(path, paint)
            y += localSpacing.coerceAtLeast(2f)
            lineIdx++
        }
    }

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float {
        val lineSpacing = (params["lineSpacing"] as? Number)?.toFloat() ?: 8f
        return (8f / lineSpacing).coerceIn(0.2f, 1f)
    }
}
