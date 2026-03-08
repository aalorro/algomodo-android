package com.artmondo.algomodo.generators.plotter

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.artmondo.algomodo.data.palettes.Palette
import com.artmondo.algomodo.generators.Generator
import com.artmondo.algomodo.generators.ParamGroup
import com.artmondo.algomodo.generators.Parameter
import com.artmondo.algomodo.generators.Quality
import com.artmondo.algomodo.rendering.SvgPath
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Phyllotaxis spiral pattern.
 *
 * Arranges dots in a sunflower-seed spiral using the golden angle. The
 * divergence angle parameter defaults to 137.5 degrees but can be adjusted
 * to produce different packing geometries.
 */
class PlotterPhyllotaxisGenerator : Generator {

    override val id = "plotter-phyllotaxis"
    override val family = "plotter"
    override val styleName = "Phyllotaxis Spiral"
    override val definition =
        "Sunflower-seed spiral pattern using the golden angle for optimal packing."
    override val algorithmNotes =
        "Each dot is placed at polar coordinates (sqrt(index) * scale, index * angle). " +
        "With the golden angle (~137.5 degrees), this produces a Fibonacci-spiral pattern. " +
        "Adjusting the angle creates parastichy spirals of different families."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema = listOf(
        Parameter.NumberParam("Point Count", "pointCount", ParamGroup.COMPOSITION, null, 100f, 5000f, 100f, 1500f),
        Parameter.NumberParam("Spread", "spread", ParamGroup.GEOMETRY, "Scale factor c in r = c * sqrt(i) — controls how tightly packed the spiral is", 0.5f, 6f, 0.25f, 3.0f),
        Parameter.NumberParam("Angle Offset", "angleOffset", ParamGroup.GEOMETRY, "Tiny deviation from golden angle — even 0.01 creates dramatically different spiral arms", -0.05f, 0.05f, 0.002f, 0f),
        Parameter.NumberParam("Dot Size", "dotSize", ParamGroup.GEOMETRY, null, 0.5f, 10f, 0.5f, 3.5f),
        Parameter.SelectParam("Size Mode", "sizeMode", ParamGroup.GEOMETRY, "uniform: same size | grow: bigger toward edge | shrink: bigger at center | wave: sinusoidal pulsing", listOf("uniform", "grow", "shrink", "wave"), "uniform"),
        Parameter.SelectParam("Shape", "shape", ParamGroup.GEOMETRY, "circle: round dots | petal: teardrop pointing outward | star: 4-point star | square: rotated rect", listOf("circle", "petal", "star", "square"), "circle"),
        Parameter.BooleanParam("Connect Lines", "connectLines", ParamGroup.TEXTURE, "Draw a line connecting sequential dots — creates beautiful spiral line art", false),
        Parameter.SelectParam("Color Mode", "colorMode", ParamGroup.COLOR, "palette-radius: by distance | palette-angle: by position | palette-noise: FBM tint | palette-fibonacci: by Fibonacci spiral arm", listOf("monochrome", "palette-radius", "palette-angle", "palette-noise", "palette-fibonacci"), "palette-radius"),
        Parameter.SelectParam("Background", "background", ParamGroup.COLOR, null, listOf("white", "cream", "dark"), "cream"),
        Parameter.NumberParam("Spin Speed", "spinSpeed", ParamGroup.FLOW_MOTION, "Whole-pattern rotation speed (rad/s)", 0f, 0.5f, 0.01f, 0.05f)
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "pointCount" to 1500f,
        "spread" to 3.0f,
        "angleOffset" to 0f,
        "dotSize" to 3.5f,
        "sizeMode" to "uniform",
        "shape" to "circle",
        "connectLines" to false,
        "colorMode" to "palette-radius",
        "background" to "cream",
        "spinSpeed" to 0.05f
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
        val pointCount = (params["pointCount"] as? Number)?.toInt() ?: 1500
        val angleOffset = (params["angleOffset"] as? Number)?.toFloat() ?: 0f
        val angleDeg = 137.5f + angleOffset
        val scale = (params["spread"] as? Number)?.toFloat() ?: 3.0f
        val dotSize = (params["dotSize"] as? Number)?.toFloat() ?: 3.5f
        val spinSpeed = (params["spinSpeed"] as? Number)?.toFloat() ?: 0.05f

        val paletteColors = palette.colorInts()

        canvas.drawColor(Color.BLACK)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }

        val cx = w / 2f
        val cy = h / 2f
        val maxR = min(w, h) / 2f * 0.9f
        val angleRad = angleDeg * PI.toFloat() / 180f

        // Compute scale factor so outermost dot is at maxR
        val outerR = sqrt(pointCount.toFloat()) * scale
        val normalizer = if (outerR > 0f) maxR / outerR else 1f

        for (i in 0 until pointCount) {
            val r = sqrt(i.toFloat()) * scale * normalizer
            val theta = i * angleRad + time * spinSpeed
            val px = cx + r * cos(theta)
            val py = cy + r * sin(theta)

            val t = i.toFloat() / pointCount
            paint.color = palette.lerpColor(t)

            // Scale dot size slightly with distance from center
            val sizeScale = 0.5f + 0.5f * (r / maxR)
            canvas.drawCircle(px, py, dotSize * sizeScale, paint)
        }
    }

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float {
        val points = (params["pointCount"] as? Number)?.toInt() ?: 1500
        return (points / 5000f).coerceIn(0.1f, 1f)
    }
}
