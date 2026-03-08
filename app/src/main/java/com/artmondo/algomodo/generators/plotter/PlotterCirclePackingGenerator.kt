package com.artmondo.algomodo.generators.plotter

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.artmondo.algomodo.core.rng.SeededRNG
import com.artmondo.algomodo.data.palettes.Palette
import com.artmondo.algomodo.generators.Generator
import com.artmondo.algomodo.generators.ParamGroup
import com.artmondo.algomodo.generators.Parameter
import com.artmondo.algomodo.generators.Quality
import com.artmondo.algomodo.rendering.SvgPath
import kotlin.math.sqrt

/**
 * Circle packing generator.
 *
 * Progressively packs non-overlapping circles of varying radii into the canvas
 * area. Larger circles are placed first, then successively smaller ones fill
 * the remaining gaps.
 */
class PlotterCirclePackingGenerator : Generator {

    override val id = "plotter-circle-packing"
    override val family = "plotter"
    override val styleName = "Circle Packing"
    override val definition =
        "Non-overlapping circles packed into the canvas with decreasing radii."
    override val algorithmNotes =
        "Circles are placed by random dart-throwing with collision detection. Each candidate " +
        "is tested against all existing circles; if no overlap is found, it is accepted. " +
        "The algorithm starts with maxRadius and shrinks toward minRadius as space fills up."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema = listOf(
        Parameter.NumberParam("Circle Count", "circleCount", ParamGroup.COMPOSITION, "Upper bound — algorithm also stops when canvas is packed", 500f, 5000f, 100f, 2500f),
        Parameter.NumberParam("Density Scale", "densityScale", ParamGroup.COMPOSITION, null, 0.3f, 6f, 0.1f, 2.0f),
        Parameter.NumberParam("Density Contrast", "densityContrast", ParamGroup.TEXTURE, "Controls color variation by noise density (does not affect circle size)", 0.5f, 4f, 0.25f, 0.8f),
        Parameter.SelectParam("Density Style", "densityStyle", ParamGroup.COMPOSITION, "Shape of the density field — fbm: smooth | ridged: sharp ridges | radial: center-focused | turbulent: creases", listOf("fbm", "ridged", "radial", "turbulent"), "fbm"),
        Parameter.NumberParam("Min Radius", "minRadius", ParamGroup.GEOMETRY, null, 1f, 20f, 1f, 4f),
        Parameter.NumberParam("Max Radius", "maxRadius", ParamGroup.GEOMETRY, null, 5f, 200f, 5f, 80f),
        Parameter.NumberParam("Circle Gap", "padding", ParamGroup.GEOMETRY, "Minimum gap between circle edges", 0f, 10f, 0.5f, 2f),
        Parameter.SelectParam("Shape", "shape", ParamGroup.GEOMETRY, "circles: round | squares: rotated rects | hexagons: 6-sided | mixed: random per element", listOf("circles", "squares", "hexagons", "mixed"), "circles"),
        Parameter.SelectParam("Fill Mode", "fillMode", ParamGroup.TEXTURE, null, listOf("filled", "outline", "filled+outline"), "filled"),
        Parameter.SelectParam("Inner Detail", "innerDetail", ParamGroup.TEXTURE, "Decorative detail drawn inside each shape — rings: concentric | spokes: radial lines | cross: X pattern | spiral: Archimedean spiral", listOf("none", "rings", "spokes", "cross", "spiral"), "none"),
        Parameter.SelectParam("Color Mode", "colorMode", ParamGroup.COLOR, null, listOf("palette-cycle", "by-size", "palette-density"), "palette-cycle"),
        Parameter.SelectParam("Background", "background", ParamGroup.COLOR, null, listOf("white", "cream", "dark"), "cream"),
        Parameter.NumberParam("Anim Speed", "animSpeed", ParamGroup.FLOW_MOTION, "Breathing/pulsing speed — 0 = static", 0f, 1f, 0.05f, 0.15f)
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "circleCount" to 2500f,
        "densityScale" to 2.0f,
        "densityContrast" to 0.8f,
        "densityStyle" to "fbm",
        "minRadius" to 4f,
        "maxRadius" to 80f,
        "padding" to 2f,
        "shape" to "circles",
        "fillMode" to "filled",
        "innerDetail" to "none",
        "colorMode" to "palette-cycle",
        "background" to "cream",
        "animSpeed" to 0.15f
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
        val maxCircles = (params["circleCount"] as? Number)?.toInt() ?: 2500
        val minRadius = (params["minRadius"] as? Number)?.toFloat() ?: 4f
        val maxRadius = (params["maxRadius"] as? Number)?.toFloat() ?: 80f
        val lineWidth = (params["lineWidth"] as? Number)?.toFloat() ?: 1f
        val animSpeed = (params["animSpeed"] as? Number)?.toFloat() ?: 0.15f

        val rng = SeededRNG(seed)
        val paletteColors = palette.colorInts()

        canvas.drawColor(Color.BLACK)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = lineWidth
        }

        data class Circle(val x: Float, val y: Float, val r: Float)

        val circles = mutableListOf<Circle>()
        val maxAttempts = maxCircles * 50
        var currentMaxR = maxRadius

        // Phase 1: Pack circles (collision detection uses original radii)
        for (attempt in 0 until maxAttempts) {
            if (circles.size >= maxCircles) break

            val x = rng.range(currentMaxR, w - currentMaxR)
            val y = rng.range(currentMaxR, h - currentMaxR)

            // Find maximum allowed radius at this position
            var allowedR = currentMaxR
            for (c in circles) {
                val dx = x - c.x
                val dy = y - c.y
                val dist = sqrt(dx * dx + dy * dy) - c.r
                if (dist < allowedR) {
                    allowedR = dist
                }
            }

            // Also constrain to canvas edges
            allowedR = allowedR.coerceAtMost(x).coerceAtMost(y)
                .coerceAtMost(w - x).coerceAtMost(h - y)

            if (allowedR >= minRadius) {
                val r = rng.range(minRadius, allowedR)
                circles.add(Circle(x, y, r))
            } else {
                // Shrink search radius over time to fill small gaps
                currentMaxR = (currentMaxR * 0.99f).coerceAtLeast(minRadius)
            }
        }

        // Phase 2: Draw circles with optional breathing animation
        for ((i, c) in circles.withIndex()) {
            paint.color = paletteColors[(i + 1) % paletteColors.size]
            val animR = c.r * (1f + kotlin.math.sin(time * animSpeed * 4f + i * 0.5f) * 0.15f)
            canvas.drawCircle(c.x, c.y, animR, paint)
        }
    }

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float {
        val maxCircles = (params["circleCount"] as? Number)?.toInt() ?: 2500
        return (maxCircles / 5000f).coerceIn(0.2f, 1f)
    }
}
