package com.artmondo.algomodo.generators.plotter

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
import com.artmondo.algomodo.rendering.SvgPath
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Concentric offset path generator.
 *
 * Draws concentric outlines that progressively offset outward from a central
 * shape (circle, square, or organic blob). Creates a ripple/contour effect.
 */
class PlotterOffsetPathsGenerator : Generator {

    override val id = "plotter-offset-paths"
    override val family = "plotter"
    override val styleName = "Offset Paths"
    override val definition =
        "Concentric offset outlines expanding outward from a central seed shape."
    override val algorithmNotes =
        "A base shape is defined at the canvas centre. Successive offsets expand the shape " +
        "by the specified spacing. For circles, offset is trivially radius+spacing. For " +
        "squares, each ring is a larger rectangle. Blobs use simplex-noise-distorted radii " +
        "so each ring has organic undulation."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema = listOf(
        Parameter.NumberParam("Ring Count", "ringCount", ParamGroup.COMPOSITION, "Number of concentric offset rings around each seed shape", 4f, 40f, 1f, 16f),
        Parameter.NumberParam("Ring Spacing", "ringSpacing", ParamGroup.GEOMETRY, "Pixel gap between successive rings", 4f, 40f, 1f, 14f),
        Parameter.NumberParam("Shape Count", "shapeCount", ParamGroup.COMPOSITION, "Number of seed shapes to offset around", 1f, 12f, 1f, 4f),
        Parameter.SelectParam("Shape Type", "shapeType", ParamGroup.COMPOSITION, null, listOf("circles", "rectangles", "mixed", "blobs", "triangles", "stars"), "circles"),
        Parameter.NumberParam("Line Width", "lineWidth", ParamGroup.GEOMETRY, null, 0.25f, 3f, 0.25f, 0.8f),
        Parameter.NumberParam("Wobble", "wobble", ParamGroup.TEXTURE, "Noise-based perturbation of the SDF surface — gives hand-drawn character", 0f, 6f, 0.25f, 1.0f),
        Parameter.NumberParam("Wobble Scale", "wobbleScale", ParamGroup.TEXTURE, "Spatial frequency of the wobble noise", 0.5f, 6f, 0.25f, 2.0f),
        Parameter.BooleanParam("Fill Bands", "fillBands", ParamGroup.TEXTURE, "Fill the space between rings with color for a topographic map look", false),
        Parameter.SelectParam("Color Mode", "colorMode", ParamGroup.COLOR, "palette-rings: each ring cycles through palette | elevation: ramps by ring depth | alternating: two-color flip", listOf("palette-rings", "elevation", "alternating"), "palette-rings"),
        Parameter.SelectParam("Background", "background", ParamGroup.COLOR, null, listOf("white", "cream", "dark"), "cream"),
        Parameter.NumberParam("Anim Speed", "animSpeed", ParamGroup.FLOW_MOTION, "Speed of wobble field drift — 0 = static", 0f, 1f, 0.05f, 0.1f)
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "ringCount" to 16f,
        "ringSpacing" to 14f,
        "shapeCount" to 4f,
        "shapeType" to "circles",
        "lineWidth" to 0.8f,
        "wobble" to 1.0f,
        "wobbleScale" to 2.0f,
        "fillBands" to false,
        "colorMode" to "palette-rings",
        "background" to "cream",
        "animSpeed" to 0.1f
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
        val shape = (params["shapeType"] as? String) ?: "circles"
        val offsets = (params["ringCount"] as? Number)?.toInt() ?: 16
        val spacing = (params["ringSpacing"] as? Number)?.toFloat() ?: 14f
        val lineWidth = (params["lineWidth"] as? Number)?.toFloat() ?: 0.8f

        val animSpeed = (params["animSpeed"] as? Number)?.toFloat() ?: 0.1f
        val timeOff = time * animSpeed

        val noise = SimplexNoise(seed)
        val paletteColors = palette.colorInts()

        canvas.drawColor(Color.BLACK)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = lineWidth
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        val cx = w / 2f
        val cy = h / 2f
        val baseRadius = min(w, h) * 0.05f

        val segments = when (quality) {
            Quality.DRAFT -> 48
            Quality.BALANCED -> 72
            Quality.ULTRA -> 120
        }

        for (ring in 0 until offsets) {
            val r = baseRadius + ring * spacing
            paint.color = paletteColors[ring % paletteColors.size]
            paint.alpha = 200

            val path = Path()

            when (shape) {
                "rectangles" -> {
                    val halfSize = r
                    path.addRect(
                        cx - halfSize, cy - halfSize,
                        cx + halfSize, cy + halfSize,
                        Path.Direction.CW
                    )
                }
                "blobs" -> {
                    for (i in 0..segments) {
                        val angle = i.toFloat() / segments * 2f * PI.toFloat()
                        val noiseVal = noise.noise2D(
                            cos(angle) * 2f + ring * 0.1f + timeOff,
                            sin(angle) * 2f + ring * 0.1f
                        )
                        val localR = r * (1f + noiseVal * 0.3f)
                        val px = cx + cos(angle) * localR
                        val py = cy + sin(angle) * localR
                        if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
                    }
                    path.close()
                }
                else -> { // circle
                    path.addCircle(cx, cy, r, Path.Direction.CW)
                }
            }

            canvas.drawPath(path, paint)
        }
    }

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float {
        val offsets = (params["ringCount"] as? Number)?.toInt() ?: 16
        return (offsets / 40f).coerceIn(0.1f, 1f)
    }
}
