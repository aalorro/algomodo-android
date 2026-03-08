package com.artmondo.algomodo.generators.geometry

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import com.artmondo.algomodo.data.palettes.Palette
import com.artmondo.algomodo.generators.Generator
import com.artmondo.algomodo.generators.ParamGroup
import com.artmondo.algomodo.generators.Parameter
import com.artmondo.algomodo.generators.Quality
import com.artmondo.algomodo.rendering.SvgBuilder
import com.artmondo.algomodo.rendering.SvgPath
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

class SpirographGenerator : Generator {

    override val id = "spirograph"
    override val family = "geometry"
    override val styleName = "Spirograph"
    override val definition =
        "Epitrochoid spirograph curves formed by tracing a point on a circle rolling inside another circle."
    override val algorithmNotes =
        "Hypotrochoid: x = (R-r)*cos(t) + d*cos((R-r)*t/r), y = (R-r)*sin(t) - d*sin((R-r)*t/r). " +
        "Epitrochoid: x = (R+r)*cos(t) - d*cos((R+r)*t/r), y = (R+r)*sin(t) - d*sin((R+r)*t/r). " +
        "Multiple rotated layers create radially symmetric mandala patterns. " +
        "Animation rotates the entire figure."
    override val supportsVector = true
    override val supportsAnimation = true

    override val parameterSchema: List<Parameter> = listOf(
        Parameter.NumberParam(
            name = "Radius (R)",
            key = "radius",
            group = ParamGroup.GEOMETRY,
            help = "Fixed outer circle radius",
            min = 10f, max = 500f, step = 10f, default = 200f
        ),
        Parameter.NumberParam(
            name = "Small Radius (r)",
            key = "smallRadius",
            group = ParamGroup.GEOMETRY,
            help = "Rolling circle radius \u2014 the ratio R/r determines petal/lobe count",
            min = 5f, max = 250f, step = 5f, default = 120f
        ),
        Parameter.NumberParam(
            name = "Pen Distance (d)",
            key = "distance",
            group = ParamGroup.GEOMETRY,
            help = "Distance of the pen from the centre of the rolling circle. d < r = inner loop; d = r = no loop (rhodonea); d > r = outer loop",
            min = 0f, max = 500f, step = 10f, default = 100f
        ),
        Parameter.SelectParam(
            name = "Mode",
            key = "mode",
            group = ParamGroup.GEOMETRY,
            help = "hypotrochoid: rolling circle inside the fixed ring (classic Spirograph) | epitrochoid: rolling circle outside",
            options = listOf("hypotrochoid", "epitrochoid"),
            default = "hypotrochoid"
        ),
        Parameter.NumberParam(
            name = "Turns",
            key = "turns",
            group = ParamGroup.COMPOSITION,
            help = "Complete rotations \u2014 increase until the curve closes (governed by R/r ratio)",
            min = 1f, max = 50f, step = 1f, default = 10f
        ),
        Parameter.NumberParam(
            name = "Layers",
            key = "layering",
            group = ParamGroup.COMPOSITION,
            help = "Overlapping copies rotated evenly around the centre \u2014 creates radially symmetric mandala patterns",
            min = 1f, max = 8f, step = 1f, default = 1f
        ),
        Parameter.SelectParam(
            name = "Color Mode",
            key = "colorMode",
            group = ParamGroup.COLOR,
            help = "solid: each layer takes one palette colour | gradient: colour sweeps through the full palette along the curve",
            options = listOf("solid", "gradient"),
            default = "solid"
        ),
        Parameter.NumberParam(
            name = "Stroke Width",
            key = "strokeWidth",
            group = ParamGroup.TEXTURE,
            help = null,
            min = 0.5f, max = 10f, step = 0.5f, default = 2f
        ),
        Parameter.NumberParam(
            name = "Speed",
            key = "speed",
            group = ParamGroup.FLOW_MOTION,
            help = "Rotation speed in animation mode",
            min = 0.01f, max = 2f, step = 0.05f, default = 0.3f
        )
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "radius" to 200f,
        "smallRadius" to 120f,
        "distance" to 100f,
        "mode" to "hypotrochoid",
        "turns" to 10f,
        "layering" to 1f,
        "colorMode" to "solid",
        "strokeWidth" to 2f,
        "speed" to 0.3f
    )

    private fun generatePoints(
        bigR: Float, smallR: Float, pen: Float, rotations: Float,
        cx: Float, cy: Float, scale: Float, samples: Int,
        isEpitrochoid: Boolean, rotationOffset: Float
    ): List<Pair<Float, Float>> {
        val points = mutableListOf<Pair<Float, Float>>()
        val totalT = 2f * PI.toFloat() * rotations
        val cosOff = cos(rotationOffset)
        val sinOff = sin(rotationOffset)

        for (i in 0..samples) {
            val t = totalT * i / samples
            val x: Float
            val y: Float
            if (isEpitrochoid) {
                val sum = bigR + smallR
                x = sum * cos(t) - pen * cos(sum * t / smallR)
                y = sum * sin(t) - pen * sin(sum * t / smallR)
            } else {
                val diff = bigR - smallR
                x = diff * cos(t) + pen * cos(diff * t / smallR)
                y = diff * sin(t) - pen * sin(diff * t / smallR)
            }
            // Apply rotation offset
            val rx = x * cosOff - y * sinOff
            val ry = x * sinOff + y * cosOff
            points.add(cx + rx * scale to cy + ry * scale)
        }
        return points
    }

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
        val bigR = (params["radius"] as? Number)?.toFloat() ?: 200f
        val smallR = (params["smallRadius"] as? Number)?.toFloat() ?: 120f
        val basePen = (params["distance"] as? Number)?.toFloat() ?: 100f
        val mode = (params["mode"] as? String) ?: "hypotrochoid"
        val rotations = (params["turns"] as? Number)?.toFloat() ?: 10f
        val layers = (params["layering"] as? Number)?.toInt() ?: 1
        val colorMode = (params["colorMode"] as? String) ?: "solid"
        val strokeWidth = (params["strokeWidth"] as? Number)?.toFloat() ?: 2f
        val speed = (params["speed"] as? Number)?.toFloat() ?: 0.3f

        val isEpitrochoid = mode == "epitrochoid"

        // Animate: slow rotation + subtle pen oscillation
        val animRotation = time * speed * 0.15f
        val pen = basePen + sin(time * speed * 0.3f) * 3f

        canvas.drawColor(Color.BLACK)

        val cx = w / 2f
        val cy = h / 2f

        // Scale to fit canvas
        val maxExtent = if (isEpitrochoid) {
            (bigR + smallR) + basePen
        } else {
            (bigR - smallR) + basePen
        }
        val scale = min(w, h) * 0.4f / maxExtent.coerceAtLeast(1f)

        val samples = when (quality) {
            Quality.DRAFT -> 3000
            Quality.BALANCED -> 10000
            Quality.ULTRA -> 25000
        }

        val paint = Paint().apply {
            style = Paint.Style.STROKE
            this.strokeWidth = strokeWidth
            isAntiAlias = quality != Quality.DRAFT
            strokeCap = Paint.Cap.ROUND
        }

        val paletteColors = palette.colorInts()

        for (layer in 0 until layers) {
            // Each layer rotated evenly + animation rotation
            val layerAngle = (2f * PI.toFloat() * layer / layers) + animRotation

            val points = generatePoints(
                bigR, smallR, pen, rotations,
                cx, cy, scale, samples,
                isEpitrochoid, layerAngle
            )

            when (colorMode) {
                "solid" -> {
                    // Each layer gets one palette color
                    paint.color = paletteColors[layer % paletteColors.size]
                    paint.alpha = if (layers > 1) (200 + 55 / layers).coerceAtMost(255) else 255

                    val path = Path()
                    path.moveTo(points[0].first, points[0].second)
                    for (j in 1 until points.size) {
                        path.lineTo(points[j].first, points[j].second)
                    }
                    canvas.drawPath(path, paint)
                }
                else -> {
                    // "gradient": color sweeps along the curve
                    val segmentSize = (samples / (paletteColors.size * 6)).coerceAtLeast(10)
                    for (i in 0 until points.size - 1 step segmentSize) {
                        val end = (i + segmentSize + 1).coerceAtMost(points.size)
                        val t = ((i.toFloat() / points.size + layer.toFloat() / layers) % 1f)
                        paint.color = palette.lerpColor(t)
                        paint.alpha = if (layers > 1) (200 + 55 / layers).coerceAtMost(255) else 255

                        val path = Path()
                        path.moveTo(points[i].first, points[i].second)
                        for (j in i + 1 until end) {
                            path.lineTo(points[j].first, points[j].second)
                        }
                        canvas.drawPath(path, paint)
                    }
                }
            }
        }
    }

    override fun renderVector(
        params: Map<String, Any>,
        seed: Int,
        palette: Palette
    ): List<SvgPath> {
        val w = 1080f
        val h = 1080f
        val bigR = (params["radius"] as? Number)?.toFloat() ?: 200f
        val smallR = (params["smallRadius"] as? Number)?.toFloat() ?: 120f
        val pen = (params["distance"] as? Number)?.toFloat() ?: 100f
        val mode = (params["mode"] as? String) ?: "hypotrochoid"
        val rotations = (params["turns"] as? Number)?.toFloat() ?: 10f
        val layers = (params["layering"] as? Number)?.toInt() ?: 1
        val colorMode = (params["colorMode"] as? String) ?: "solid"
        val strokeWidth = (params["strokeWidth"] as? Number)?.toFloat() ?: 2f

        val isEpitrochoid = mode == "epitrochoid"
        val cx = w / 2f
        val cy = h / 2f
        val maxExtent = if (isEpitrochoid) (bigR + smallR) + pen else (bigR - smallR) + pen
        val scale = min(w, h) * 0.4f / maxExtent.coerceAtLeast(1f)
        val samples = 10000

        val paths = mutableListOf<SvgPath>()
        val paletteColors = palette.colorInts()

        for (layer in 0 until layers) {
            val layerAngle = 2f * PI.toFloat() * layer / layers
            val points = generatePoints(
                bigR, smallR, pen, rotations,
                cx, cy, scale, samples,
                isEpitrochoid, layerAngle
            )

            when (colorMode) {
                "solid" -> {
                    val color = paletteColors[layer % paletteColors.size]
                    val hexColor = String.format("#%06X", 0xFFFFFF and color)

                    val sb = StringBuilder()
                    sb.append(SvgBuilder.moveTo(points[0].first, points[0].second))
                    for (j in 1 until points.size) {
                        sb.append(" ").append(SvgBuilder.lineTo(points[j].first, points[j].second))
                    }
                    paths.add(SvgPath(d = sb.toString(), stroke = hexColor, strokeWidth = strokeWidth))
                }
                else -> {
                    val segmentSize = (samples / 20).coerceAtLeast(20)
                    for (i in 0 until points.size - 1 step segmentSize) {
                        val end = (i + segmentSize + 1).coerceAtMost(points.size)
                        val t = ((i.toFloat() / points.size + layer.toFloat() / layers) % 1f)
                        val color = palette.lerpColor(t)
                        val hexColor = String.format("#%06X", 0xFFFFFF and color)

                        val sb = StringBuilder()
                        sb.append(SvgBuilder.moveTo(points[i].first, points[i].second))
                        for (j in i + 1 until end) {
                            sb.append(" ").append(SvgBuilder.lineTo(points[j].first, points[j].second))
                        }
                        paths.add(SvgPath(d = sb.toString(), stroke = hexColor, strokeWidth = strokeWidth))
                    }
                }
            }
        }

        return paths
    }

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float {
        val rotations = (params["turns"] as? Number)?.toFloat() ?: 10f
        val layers = (params["layering"] as? Number)?.toInt() ?: 1
        return (rotations * layers / 50f).coerceIn(0.2f, 1f)
    }
}
