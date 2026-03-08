package com.artmondo.algomodo.generators.plotter

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
import com.artmondo.algomodo.rendering.SvgPath
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Cross-hatching renderer.
 *
 * Draws parallel lines at configurable angles. Line density varies spatially
 * based on simplex noise, creating tonal shading reminiscent of engraving or
 * pen-and-ink illustration.
 */
class HatchingGenerator : Generator {

    override val id = "hatching"
    override val family = "plotter"
    override val styleName = "Cross-Hatching"
    override val definition =
        "Parallel hatching lines at multiple angles with noise-driven density variation."
    override val algorithmNotes =
        "For each layer, parallel lines are swept across the canvas at the specified angle. " +
        "Each line's spacing is modulated by a simplex noise field so that darker noise " +
        "regions produce tighter spacing (more ink). Multiple layers at different angles " +
        "create cross-hatching."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema = listOf(
        Parameter.SelectParam("Style", "style", ParamGroup.COMPOSITION, "parallel: broken lines | crosshatch: perpendicular overlapping | contour: follows topography | wavy: sine-modulated | scribble: curved arcs", listOf("parallel", "crosshatch", "contour", "wavy", "scribble"), "parallel"),
        Parameter.NumberParam("Layers", "layers", ParamGroup.COMPOSITION, "Number of angle passes; each uses the next palette color", 1f, 4f, 1f, 2f),
        Parameter.NumberParam("Line Spacing", "baseSpacing", ParamGroup.GEOMETRY, null, 3f, 28f, 1f, 7f),
        Parameter.NumberParam("Base Angle", "angle", ParamGroup.GEOMETRY, null, 0f, 175f, 5f, 45f),
        Parameter.NumberParam("Angle Step", "angleStep", ParamGroup.GEOMETRY, "Degrees between each layer", 10f, 90f, 5f, 45f),
        Parameter.NumberParam("Density Scale", "densityScale", ParamGroup.COMPOSITION, "Spatial scale of the noise density field", 0.3f, 6f, 0.1f, 2.2f),
        Parameter.NumberParam("Density Contrast", "densityContrast", ParamGroup.TEXTURE, "Exponent sharpening light/dark areas", 0.5f, 4f, 0.25f, 1.8f),
        Parameter.NumberParam("Wobble", "wobble", ParamGroup.TEXTURE, "Per-segment hand-drawn jitter", 0f, 6f, 0.25f, 1.5f),
        Parameter.NumberParam("Line Width", "lineWidth", ParamGroup.TEXTURE, null, 0.25f, 3f, 0.25f, 0.75f),
        Parameter.BooleanParam("Taper", "taper", ParamGroup.TEXTURE, "Vary line width with density — thicker in dark areas, thinner in light", false),
        Parameter.SelectParam("Background", "background", ParamGroup.COLOR, null, listOf("white", "cream", "dark"), "cream"),
        Parameter.NumberParam("Anim Speed", "animSpeed", ParamGroup.FLOW_MOTION, "Speed at which the density field drifts over time (0 = static)", 0f, 1f, 0.05f, 0.12f)
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "style" to "parallel",
        "layers" to 2f,
        "baseSpacing" to 7f,
        "angle" to 45f,
        "angleStep" to 45f,
        "densityScale" to 2.2f,
        "densityContrast" to 1.8f,
        "wobble" to 1.5f,
        "lineWidth" to 0.75f,
        "taper" to false,
        "background" to "cream",
        "animSpeed" to 0.12f
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
        val baseSpacing = (params["baseSpacing"] as? Number)?.toFloat() ?: 7f
        val baseAngle = (params["angle"] as? Number)?.toFloat() ?: 45f
        val layers = (params["layers"] as? Number)?.toInt() ?: 2
        val lineWidth = (params["lineWidth"] as? Number)?.toFloat() ?: 0.75f
        val animSpeed = (params["animSpeed"] as? Number)?.toFloat() ?: 0.12f

        val noise = SimplexNoise(seed)
        val rng = SeededRNG(seed)
        val paletteColors = palette.colorInts()

        canvas.drawColor(Color.BLACK)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = lineWidth
            strokeCap = Paint.Cap.ROUND
        }

        val diagonal = sqrt(w * w + h * h)

        for (layer in 0 until layers) {
            val angleDeg = baseAngle + layer * (180f / layers)
            val angleRad = angleDeg * PI.toFloat() / 180f
            val dx = cos(angleRad)
            val dy = sin(angleRad)
            val perpX = -dy
            val perpY = dx

            paint.color = paletteColors[layer % paletteColors.size]
            paint.alpha = 180

            val cx = w / 2f
            val cy = h / 2f

            var offset = -diagonal
            while (offset < diagonal) {
                // Sample noise at the centre of this line
                val sampleX = cx + perpX * offset
                val sampleY = cy + perpY * offset
                val nv = (noise.fbm(sampleX / w * 3f + time * animSpeed, sampleY / h * 3f + time * animSpeed, 3) + 1f) * 0.5f
                val localSpacing = baseSpacing * (0.3f + nv * 1.4f)

                // Line start and end (clip to canvas bounds via large extent)
                val x1 = cx + perpX * offset - dx * diagonal
                val y1 = cy + perpY * offset - dy * diagonal
                val x2 = cx + perpX * offset + dx * diagonal
                val y2 = cy + perpY * offset + dy * diagonal

                canvas.drawLine(x1, y1, x2, y2, paint)

                offset += localSpacing.coerceAtLeast(1f)
            }
        }
    }

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float {
        val baseSpacing = (params["baseSpacing"] as? Number)?.toFloat() ?: 7f
        val layers = (params["layers"] as? Number)?.toInt() ?: 2
        return (layers / (baseSpacing * 0.5f)).coerceIn(0.2f, 1f)
    }
}
