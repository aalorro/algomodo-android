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
import kotlin.math.sin
import kotlin.math.sqrt

class IslamicPatternsGenerator : Generator {

    override val id = "geo-islamic"
    override val family = "geometry"
    override val styleName = "Islamic Patterns"
    override val definition =
        "Interlocking geometric tilings inspired by traditional Islamic art, featuring rotational symmetry and intricate star polygons."
    override val algorithmNotes =
        "Generates a grid of tile cells (square, hexagonal, or triangular). Within each cell, " +
        "star polygons {n/k} are constructed by connecting every k-th vertex of an n-gon. " +
        "Girih lines connect adjacent star tips, inner detail adds rosette centers, " +
        "and double-line mode draws parallel strokes for ribbon effects."
    override val supportsVector = true
    override val supportsAnimation = true

    override val parameterSchema: List<Parameter> = listOf(
        Parameter.NumberParam(
            name = "Star Points (n)",
            key = "starPoints",
            group = ParamGroup.GEOMETRY,
            help = "Vertices of the primary star polygon — 6 = hexagram, 8 = octagram, 12 = dodecagram",
            min = 4f, max = 16f, step = 1f, default = 8f
        ),
        Parameter.NumberParam(
            name = "Skip (k)",
            key = "starSkip",
            group = ParamGroup.GEOMETRY,
            help = "Every k-th vertex is connected. Must be < n/2. Star {n/k}: {6/2}=hexagram, {8/3}=octagram",
            min = 2f, max = 7f, step = 1f, default = 3f
        ),
        Parameter.SelectParam(
            name = "Tiling",
            key = "tiling",
            group = ParamGroup.COMPOSITION,
            help = "Grid symmetry used to tile stars across the canvas",
            options = listOf("square", "hexagonal", "triangular"),
            default = "square"
        ),
        Parameter.NumberParam(
            name = "Layers",
            key = "layers",
            group = ParamGroup.COMPOSITION,
            help = "Number of concentric star rings per tile cell",
            min = 1f, max = 3f, step = 1f, default = 2f
        ),
        Parameter.NumberParam(
            name = "Cell Size",
            key = "cellSize",
            group = ParamGroup.GEOMETRY,
            help = "Pixel size of each tiling cell",
            min = 40f, max = 300f, step = 10f, default = 100f
        ),
        Parameter.NumberParam(
            name = "Stroke Width",
            key = "strokeWidth",
            group = ParamGroup.TEXTURE,
            help = null,
            min = 0.5f, max = 5f, step = 0.5f, default = 1.5f
        ),
        Parameter.SelectParam(
            name = "Color Mode",
            key = "colorMode",
            group = ParamGroup.COLOR,
            help = "classic: alternating palette fills | radial: distance gradient | layered: per-ring color | monochrome: single stroke color",
            options = listOf("classic", "radial", "layered", "monochrome"),
            default = "classic"
        ),
        Parameter.BooleanParam(
            name = "Girih Lines",
            key = "girihLines",
            group = ParamGroup.GEOMETRY,
            help = "Draw connecting lines between adjacent star tips — the characteristic interlocking web",
            default = true
        ),
        Parameter.BooleanParam(
            name = "Double Line",
            key = "doubleLine",
            group = ParamGroup.TEXTURE,
            help = "Two parallel strokes per edge, creating a band/ribbon effect",
            default = false
        ),
        Parameter.BooleanParam(
            name = "Inner Detail",
            key = "innerDetail",
            group = ParamGroup.GEOMETRY,
            help = "Draw a small regular polygon at each star center (rosette look)",
            default = true
        ),
        Parameter.SelectParam(
            name = "Anim Mode",
            key = "animMode",
            group = ParamGroup.FLOW_MOTION,
            help = "spin: rotate stars | kaleidoscope: alternate directions | breathe: pulse size | wave: ripple from center | none: static",
            options = listOf("spin", "kaleidoscope", "breathe", "wave", "none"),
            default = "spin"
        ),
        Parameter.NumberParam(
            name = "Speed",
            key = "speed",
            group = ParamGroup.FLOW_MOTION,
            help = null,
            min = 0.05f, max = 1f, step = 0.05f, default = 0.2f
        )
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "starPoints" to 8f,
        "starSkip" to 3f,
        "tiling" to "square",
        "layers" to 2f,
        "cellSize" to 100f,
        "strokeWidth" to 1.5f,
        "colorMode" to "classic",
        "girihLines" to true,
        "doubleLine" to false,
        "innerDetail" to true,
        "animMode" to "spin",
        "speed" to 0.2f
    )

    private data class Line(
        val x1: Float, val y1: Float,
        val x2: Float, val y2: Float,
        val layer: Int
    )

    /** Generate star polygon edges and decorations for one tile cell. */
    private fun generateTileLines(
        cx: Float, cy: Float, tileSize: Float,
        n: Int, skip: Int, layers: Int,
        rotation: Float, girihLines: Boolean, innerDetail: Boolean
    ): List<Line> {
        val lines = mutableListOf<Line>()
        val baseRadius = tileSize * 0.45f

        for (layer in 0 until layers) {
            val frac = (layer + 1).toFloat() / layers
            val radius = baseRadius * (0.4f + frac * 0.6f)
            val layerRotation = rotation + if (layer % 2 == 1) PI.toFloat() / n else 0f

            // Star polygon {n/skip}: connect every skip-th vertex
            for (i in 0 until n) {
                val a1 = layerRotation + 2f * PI.toFloat() * i / n
                val a2 = layerRotation + 2f * PI.toFloat() * ((i + skip) % n) / n
                lines.add(Line(
                    cx + radius * cos(a1), cy + radius * sin(a1),
                    cx + radius * cos(a2), cy + radius * sin(a2),
                    layer
                ))
            }

            // Girih lines: connect adjacent star tips to form the interlocking web
            if (girihLines) {
                for (i in 0 until n) {
                    val a1 = layerRotation + 2f * PI.toFloat() * i / n
                    val a2 = layerRotation + 2f * PI.toFloat() * ((i + 1) % n) / n
                    lines.add(Line(
                        cx + radius * cos(a1), cy + radius * sin(a1),
                        cx + radius * cos(a2), cy + radius * sin(a2),
                        layer
                    ))
                }
            }

            // Inner detail: small regular polygon at center
            if (innerDetail && layer == 0) {
                val innerRadius = radius * 0.3f
                val innerN = n.coerceAtMost(8)
                for (i in 0 until innerN) {
                    val a1 = layerRotation + PI.toFloat() / n + 2f * PI.toFloat() * i / innerN
                    val a2 = layerRotation + PI.toFloat() / n + 2f * PI.toFloat() * ((i + 1) % innerN) / innerN
                    lines.add(Line(
                        cx + innerRadius * cos(a1), cy + innerRadius * sin(a1),
                        cx + innerRadius * cos(a2), cy + innerRadius * sin(a2),
                        -1 // special layer for inner detail
                    ))
                }
            }
        }

        // Cross-layer connecting lines between outer and inner rings
        if (layers >= 2 && girihLines) {
            val outerR = baseRadius
            val innerR = baseRadius * (0.4f + 1f / layers * 0.6f)
            for (i in 0 until n) {
                val outerAngle = rotation + 2f * PI.toFloat() * i / n
                val innerAngle = rotation + (if (1 % 2 == 1) PI.toFloat() / n else 0f) +
                    2f * PI.toFloat() * i / n
                lines.add(Line(
                    cx + outerR * cos(outerAngle), cy + outerR * sin(outerAngle),
                    cx + innerR * cos(innerAngle), cy + innerR * sin(innerAngle),
                    0
                ))
            }
        }

        return lines
    }

    /** Compute per-cell rotation based on animation mode. */
    private fun cellRotation(
        animMode: String, speed: Float, time: Float,
        col: Int, row: Int, cx: Float, cy: Float,
        canvasCx: Float, canvasCy: Float
    ): Float {
        return when (animMode) {
            "spin" -> time * speed * 0.3f
            "kaleidoscope" -> {
                val dir = if ((col + row) % 2 == 0) 1f else -1f
                time * speed * 0.3f * dir
            }
            "breathe" -> time * speed * 0.1f // slight rotation + size pulse handled separately
            "wave" -> {
                val dist = sqrt((cx - canvasCx) * (cx - canvasCx) + (cy - canvasCy) * (cy - canvasCy))
                sin(dist * 0.02f - time * speed * 0.5f) * 0.15f
            }
            else -> 0f // "none"
        }
    }

    private fun cellScale(
        animMode: String, speed: Float, time: Float,
        cx: Float, cy: Float, canvasCx: Float, canvasCy: Float
    ): Float {
        return when (animMode) {
            "breathe" -> 0.85f + 0.15f * sin(time * speed * 0.5f)
            "wave" -> {
                val dist = sqrt((cx - canvasCx) * (cx - canvasCx) + (cy - canvasCy) * (cy - canvasCy))
                0.9f + 0.1f * sin(dist * 0.015f - time * speed * 0.5f)
            }
            else -> 1f
        }
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
        val n = (params["starPoints"] as? Number)?.toInt() ?: 8
        val skip = ((params["starSkip"] as? Number)?.toInt() ?: 3).coerceIn(1, (n / 2).coerceAtLeast(1))
        val tiling = (params["tiling"] as? String) ?: "square"
        val layers = (params["layers"] as? Number)?.toInt() ?: 2
        val tileSize = (params["cellSize"] as? Number)?.toFloat() ?: 100f
        val strokeWidth = (params["strokeWidth"] as? Number)?.toFloat() ?: 1.5f
        val colorMode = (params["colorMode"] as? String) ?: "classic"
        val girihLines = (params["girihLines"] as? Boolean) ?: true
        val doubleLine = (params["doubleLine"] as? Boolean) ?: false
        val innerDetail = (params["innerDetail"] as? Boolean) ?: true
        val animMode = (params["animMode"] as? String) ?: "spin"
        val speed = (params["speed"] as? Number)?.toFloat() ?: 0.2f

        canvas.drawColor(Color.BLACK)

        val canvasCx = w / 2f
        val canvasCy = h / 2f
        val paletteColors = palette.colorInts()

        val paint = Paint().apply {
            style = Paint.Style.STROKE
            this.strokeWidth = strokeWidth
            isAntiAlias = quality != Quality.DRAFT
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        val isHex = tiling == "hexagonal"
        val isTri = tiling == "triangular"
        val rowSpacing = if (isHex || isTri) tileSize * 0.866f else tileSize
        val cols = (w / tileSize + 3).toInt()
        val rows = (h / rowSpacing + 3).toInt()

        var globalIdx = 0

        for (row in -1..rows) {
            for (col in -1..cols) {
                val cx: Float
                val cy: Float

                when {
                    isHex -> {
                        val offsetX = if (row % 2 == 0) 0f else tileSize * 0.5f
                        cx = col * tileSize + offsetX
                        cy = row * rowSpacing
                    }
                    isTri -> {
                        val offsetX = if (row % 2 == 0) 0f else tileSize * 0.5f
                        cx = col * tileSize + offsetX
                        cy = row * rowSpacing
                    }
                    else -> {
                        cx = col * tileSize
                        cy = row * tileSize
                    }
                }

                val rotation = cellRotation(animMode, speed, time, col, row, cx, cy, canvasCx, canvasCy)
                val scale = cellScale(animMode, speed, time, cx, cy, canvasCx, canvasCy)
                val scaledTileSize = tileSize * scale

                val tileLines = generateTileLines(cx, cy, scaledTileSize, n, skip, layers, rotation, girihLines, innerDetail)

                for (line in tileLines) {
                    // Color based on colorMode
                    val color = when (colorMode) {
                        "classic" -> paletteColors[globalIdx % paletteColors.size]
                        "radial" -> {
                            val dist = sqrt((cx - canvasCx) * (cx - canvasCx) + (cy - canvasCy) * (cy - canvasCy))
                            val maxDist = sqrt(canvasCx * canvasCx + canvasCy * canvasCy)
                            palette.lerpColor((dist / maxDist).coerceIn(0f, 1f))
                        }
                        "layered" -> {
                            val layerIdx = if (line.layer < 0) layers else line.layer
                            paletteColors[layerIdx % paletteColors.size]
                        }
                        "monochrome" -> paletteColors[0]
                        else -> paletteColors[globalIdx % paletteColors.size]
                    }

                    paint.color = color
                    if (colorMode == "monochrome") {
                        paint.alpha = 200
                    } else {
                        paint.alpha = 255
                    }

                    canvas.drawLine(line.x1, line.y1, line.x2, line.y2, paint)

                    // Double line: draw parallel offset lines
                    if (doubleLine) {
                        val dx = line.x2 - line.x1
                        val dy = line.y2 - line.y1
                        val len = sqrt(dx * dx + dy * dy).coerceAtLeast(0.001f)
                        val nx = -dy / len * strokeWidth * 1.5f
                        val ny = dx / len * strokeWidth * 1.5f
                        paint.alpha = 180
                        canvas.drawLine(line.x1 + nx, line.y1 + ny, line.x2 + nx, line.y2 + ny, paint)
                        canvas.drawLine(line.x1 - nx, line.y1 - ny, line.x2 - nx, line.y2 - ny, paint)
                        paint.alpha = 255
                    }

                    globalIdx++
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
        val n = (params["starPoints"] as? Number)?.toInt() ?: 8
        val skip = ((params["starSkip"] as? Number)?.toInt() ?: 3).coerceIn(1, (n / 2).coerceAtLeast(1))
        val tiling = (params["tiling"] as? String) ?: "square"
        val layers = (params["layers"] as? Number)?.toInt() ?: 2
        val tileSize = (params["cellSize"] as? Number)?.toFloat() ?: 100f
        val strokeWidth = (params["strokeWidth"] as? Number)?.toFloat() ?: 1.5f
        val colorMode = (params["colorMode"] as? String) ?: "classic"
        val girihLines = (params["girihLines"] as? Boolean) ?: true
        val doubleLine = (params["doubleLine"] as? Boolean) ?: false
        val innerDetail = (params["innerDetail"] as? Boolean) ?: true

        val canvasCx = w / 2f
        val canvasCy = h / 2f
        val paletteColors = palette.colorInts()
        val paths = mutableListOf<SvgPath>()

        val isHex = tiling == "hexagonal"
        val isTri = tiling == "triangular"
        val rowSpacing = if (isHex || isTri) tileSize * 0.866f else tileSize
        val cols = (w / tileSize + 3).toInt()
        val rows = (h / rowSpacing + 3).toInt()

        var globalIdx = 0

        for (row in -1..rows) {
            for (col in -1..cols) {
                val cx: Float
                val cy: Float

                when {
                    isHex || isTri -> {
                        val offsetX = if (row % 2 == 0) 0f else tileSize * 0.5f
                        cx = col * tileSize + offsetX
                        cy = row * rowSpacing
                    }
                    else -> {
                        cx = col * tileSize
                        cy = row * tileSize
                    }
                }

                val tileLines = generateTileLines(cx, cy, tileSize, n, skip, layers, 0f, girihLines, innerDetail)

                for (line in tileLines) {
                    val color = when (colorMode) {
                        "classic" -> paletteColors[globalIdx % paletteColors.size]
                        "radial" -> {
                            val dist = sqrt((cx - canvasCx) * (cx - canvasCx) + (cy - canvasCy) * (cy - canvasCy))
                            val maxDist = sqrt(canvasCx * canvasCx + canvasCy * canvasCy)
                            palette.lerpColor((dist / maxDist).coerceIn(0f, 1f))
                        }
                        "layered" -> {
                            val layerIdx = if (line.layer < 0) layers else line.layer
                            paletteColors[layerIdx % paletteColors.size]
                        }
                        "monochrome" -> paletteColors[0]
                        else -> paletteColors[globalIdx % paletteColors.size]
                    }
                    val hexColor = String.format("#%06X", 0xFFFFFF and color)
                    val d = "${SvgBuilder.moveTo(line.x1, line.y1)} ${SvgBuilder.lineTo(line.x2, line.y2)}"
                    paths.add(SvgPath(d = d, stroke = hexColor, strokeWidth = strokeWidth))

                    if (doubleLine) {
                        val dx = line.x2 - line.x1
                        val dy = line.y2 - line.y1
                        val len = sqrt(dx * dx + dy * dy).coerceAtLeast(0.001f)
                        val nx = -dy / len * strokeWidth * 1.5f
                        val ny = dx / len * strokeWidth * 1.5f
                        val d1 = "${SvgBuilder.moveTo(line.x1 + nx, line.y1 + ny)} ${SvgBuilder.lineTo(line.x2 + nx, line.y2 + ny)}"
                        val d2 = "${SvgBuilder.moveTo(line.x1 - nx, line.y1 - ny)} ${SvgBuilder.lineTo(line.x2 - nx, line.y2 - ny)}"
                        paths.add(SvgPath(d = d1, stroke = hexColor, strokeWidth = strokeWidth, opacity = 0.7f))
                        paths.add(SvgPath(d = d2, stroke = hexColor, strokeWidth = strokeWidth, opacity = 0.7f))
                    }

                    globalIdx++
                }
            }
        }

        return paths
    }

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float {
        val layers = (params["layers"] as? Number)?.toInt() ?: 2
        return (layers / 3f).coerceIn(0.2f, 1f)
    }
}
