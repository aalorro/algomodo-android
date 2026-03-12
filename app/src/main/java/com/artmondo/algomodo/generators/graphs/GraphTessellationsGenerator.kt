package com.artmondo.algomodo.generators.graphs

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
import com.artmondo.algomodo.rendering.SvgBuilder
import com.artmondo.algomodo.rendering.SvgPath
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Regular and semi-regular tessellation generator.
 *
 * Draws plane-filling tilings (triangular, square, hexagonal, cairo, snub-square)
 * with each cell coloured from the palette. Supports SVG vector output.
 */
class GraphTessellationsGenerator : Generator {

    override val id = "graph-tessellations"
    override val family = "graphs"
    override val styleName = "Tessellations"
    override val definition =
        "Regular and semi-regular plane tessellations (triangular, square, hexagonal, cairo, snub-square) with palette-coloured cells."
    override val algorithmNotes =
        "Each tiling is generated analytically. Triangular uses alternating up/down triangles. " +
        "Square is a simple grid. Hexagonal uses offset rows. Cairo uses pairs of pentagons. " +
        "Snub-square combines squares and triangles. SVG output produces polygon path elements. " +
        "Animation gently rotates or shifts the tiling origin over time."
    override val supportsVector = true
    override val supportsAnimation = true

    override val parameterSchema = listOf(
        Parameter.SelectParam("Tiling Type", "tilingType", ParamGroup.COMPOSITION, "Type of tessellation pattern", listOf("triangular", "square", "hexagonal", "penrose", "cairo"), "hexagonal"),
        Parameter.NumberParam("Cell Size", "cellSize", ParamGroup.GEOMETRY, "Approximate tile size in pixels", 15f, 200f, 5f, 50f),
        Parameter.NumberParam("Edge Width", "edgeWidth", ParamGroup.GEOMETRY, null, 0f, 5f, 0.5f, 1.5f),
        Parameter.SelectParam("Edge Style", "edgeStyle", ParamGroup.GEOMETRY, null, listOf("dark", "light", "palette", "none"), "dark"),
        Parameter.SelectParam("Color Mode", "colorMode", ParamGroup.COLOR, null, listOf("checkerboard", "palette-cycle", "radial-gradient", "noise-field", "monochrome"), "palette-cycle"),
        Parameter.NumberParam("Fill Opacity", "fillOpacity", ParamGroup.COLOR, null, 0.1f, 1f, 0.05f, 0.85f),
        Parameter.SelectParam("Inner Detail", "innerDetail", ParamGroup.TEXTURE, null, listOf("none", "centroid-dot", "subdivision", "inscribed-circle"), "none"),
        Parameter.NumberParam("Vertex Jitter", "jitter", ParamGroup.TEXTURE, "Random vertex displacement for organic look", 0f, 0.4f, 0.02f, 0f),
        Parameter.SelectParam("Animation", "animMode", ParamGroup.FLOW_MOTION, null, listOf("none", "breathe", "wave", "color-cycle"), "wave"),
        Parameter.NumberParam("Speed", "speed", ParamGroup.FLOW_MOTION, null, 0.05f, 1.5f, 0.05f, 0.3f)
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "tilingType" to "hexagonal",
        "cellSize" to 50f,
        "edgeWidth" to 1.5f,
        "edgeStyle" to "dark",
        "colorMode" to "palette-cycle",
        "fillOpacity" to 0.85f,
        "innerDetail" to "none",
        "jitter" to 0f,
        "animMode" to "wave",
        "speed" to 0.3f
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
        val tiling = (params["tilingType"] as? String) ?: "hexagonal"
        val cellSize = (params["cellSize"] as? Number)?.toFloat() ?: 50f
        val lineWidth = (params["edgeWidth"] as? Number)?.toFloat() ?: 1.5f
        val edgeStyle = (params["edgeStyle"] as? String) ?: "dark"
        val colorMode = (params["colorMode"] as? String) ?: "palette-cycle"
        val fillOpacity = (params["fillOpacity"] as? Number)?.toFloat() ?: 0.85f
        val innerDetail = (params["innerDetail"] as? String) ?: "none"
        val jitter = (params["jitter"] as? Number)?.toFloat() ?: 0f
        val animMode = (params["animMode"] as? String) ?: "wave"
        val speed = (params["speed"] as? Number)?.toFloat() ?: 0.3f
        val fillCells = fillOpacity > 0f

        val rng = SeededRNG(seed)
        val noise = SimplexNoise(seed)
        val colors = palette.colorInts()

        canvas.drawColor(Color.BLACK)

        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
        val showEdges = edgeStyle != "none"
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = lineWidth
            color = when (edgeStyle) {
                "light" -> Color.rgb(220, 220, 220)
                "palette" -> colors[0]
                else -> Color.rgb(30, 30, 30) // "dark"
            }
        }

        // Animation
        val animTime = if (animMode == "none") 0f else time * speed
        var offsetX = 0f
        var offsetY = 0f
        when (animMode) {
            "wave" -> {
                offsetX = animTime * 15f
                offsetY = animTime * 9f
            }
        }

        val polygons = generateTiling(tiling, cellSize, w, h, offsetX, offsetY)
        val diagonal = sqrt(w * w + h * h)
        val detailPaint = Paint(Paint.ANTI_ALIAS_FLAG)

        for ((index, polygon) in polygons.withIndex()) {
            // Apply jitter to vertices
            val jittered = if (jitter > 0f) {
                polygon.mapIndexed { vi, (px, py) ->
                    val jx = noise.noise2D(px * 0.01f + vi, py * 0.01f) * cellSize * jitter
                    val jy = noise.noise2D(px * 0.01f + vi + 100f, py * 0.01f + 100f) * cellSize * jitter
                    Pair(px + jx, py + jy)
                }
            } else polygon

            val path = Path()
            if (jittered.isNotEmpty()) {
                path.moveTo(jittered[0].first, jittered[0].second)
                for (i in 1 until jittered.size) {
                    path.lineTo(jittered[i].first, jittered[i].second)
                }
                path.close()
            }

            // Compute centroid for coloring and detail
            val cx = jittered.map { it.first }.average().toFloat()
            val cy = jittered.map { it.second }.average().toFloat()

            if (fillCells) {
                val colorShift = if (animMode == "color-cycle") animTime * 2f else 0f
                val baseAlpha = if (animMode == "breathe") {
                    val breath = (sin(animTime * 3f + cx * 0.01f + cy * 0.01f) * 0.5f + 0.5f)
                    (fillOpacity * (0.3f + 0.7f * breath) * 255).toInt().coerceIn(0, 255)
                } else {
                    (fillOpacity * 255).toInt().coerceIn(0, 255)
                }

                val baseColor = when (colorMode) {
                    "checkerboard" -> {
                        val ci = ((cx / cellSize).toInt() + (cy / cellSize).toInt()) % 2
                        colors[ci.coerceIn(0, colors.size - 1)]
                    }
                    "radial-gradient" -> {
                        val dx = cx - w / 2f
                        val dy = cy - h / 2f
                        val t = (sqrt(dx * dx + dy * dy) / (diagonal * 0.5f) + colorShift) % 1f
                        palette.lerpColor(t)
                    }
                    "noise-field" -> {
                        val n = (noise.noise2D(cx * 0.005f, cy * 0.005f + colorShift) + 1f) / 2f
                        palette.lerpColor(n.coerceIn(0f, 1f))
                    }
                    "monochrome" -> {
                        val gray = ((noise.noise2D(cx * 0.008f, cy * 0.008f) + 1f) / 2f * 255).toInt().coerceIn(30, 240)
                        Color.rgb(gray, gray, gray)
                    }
                    else -> { // "palette-cycle"
                        val shifted = (index + (colorShift * colors.size).toInt()) % colors.size
                        colors[if (shifted < 0) shifted + colors.size else shifted]
                    }
                }

                fillPaint.color = Color.argb(baseAlpha, Color.red(baseColor), Color.green(baseColor), Color.blue(baseColor))
                canvas.drawPath(path, fillPaint)
            }
            if (showEdges) {
                if (edgeStyle == "palette") {
                    strokePaint.color = colors[(index + 1) % colors.size]
                }
                canvas.drawPath(path, strokePaint)
            }

            // Inner detail
            when (innerDetail) {
                "centroid-dot" -> {
                    detailPaint.style = Paint.Style.FILL
                    detailPaint.color = Color.WHITE
                    canvas.drawCircle(cx, cy, cellSize * 0.06f, detailPaint)
                }
                "inscribed-circle" -> {
                    detailPaint.style = Paint.Style.STROKE
                    detailPaint.strokeWidth = lineWidth * 0.5f
                    detailPaint.color = Color.argb(100, 255, 255, 255)
                    val minDist = jittered.minOf { (px, py) ->
                        sqrt((px - cx) * (px - cx) + (py - cy) * (py - cy))
                    } * 0.6f
                    canvas.drawCircle(cx, cy, minDist, detailPaint)
                }
                "subdivision" -> {
                    detailPaint.style = Paint.Style.STROKE
                    detailPaint.strokeWidth = lineWidth * 0.3f
                    detailPaint.color = Color.argb(60, 255, 255, 255)
                    for ((px, py) in jittered) {
                        canvas.drawLine(cx, cy, px, py, detailPaint)
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
        val tiling = (params["tilingType"] as? String) ?: "hexagonal"
        val cellSize = (params["cellSize"] as? Number)?.toFloat() ?: 50f
        val lineWidth = (params["edgeWidth"] as? Number)?.toFloat() ?: 1.5f
        val fillOpacity = (params["fillOpacity"] as? Number)?.toFloat() ?: 0.85f
        val fillCells = fillOpacity > 0f

        val w = 1080f
        val h = 1080f
        val colors = palette.colorInts()
        val paths = mutableListOf<SvgPath>()

        val polygons = generateTiling(tiling, cellSize, w, h, 0f, 0f)

        for ((index, polygon) in polygons.withIndex()) {
            if (polygon.isEmpty()) continue
            val d = SvgBuilder.polygon(polygon)
            val colorInt = colors[index % colors.size]
            val fillHex = if (fillCells) String.format("#%06X", 0xFFFFFF and colorInt) else null
            paths.add(
                SvgPath(
                    d = d,
                    fill = fillHex,
                    stroke = "#1E1E1E",
                    strokeWidth = lineWidth
                )
            )
        }

        return paths
    }

    private fun generateTiling(
        tiling: String,
        cellSize: Float,
        w: Float, h: Float,
        offsetX: Float, offsetY: Float
    ): List<List<Pair<Float, Float>>> {
        return when (tiling) {
            "triangular" -> generateTriangular(cellSize, w, h, offsetX, offsetY)
            "square" -> generateSquare(cellSize, w, h, offsetX, offsetY)
            "hexagonal" -> generateHexagonal(cellSize, w, h, offsetX, offsetY)
            "cairo" -> generateCairo(cellSize, w, h, offsetX, offsetY)
            "penrose", "snub-square" -> generateSnubSquare(cellSize, w, h, offsetX, offsetY)
            else -> generateHexagonal(cellSize, w, h, offsetX, offsetY)
        }
    }

    private fun generateTriangular(
        size: Float, w: Float, h: Float,
        ox: Float, oy: Float
    ): List<List<Pair<Float, Float>>> {
        val polygons = mutableListOf<List<Pair<Float, Float>>>()
        val triH = size * sqrt(3f) / 2f
        val cols = (w / size).toInt() + 2
        val rows = (h / triH).toInt() + 2

        for (row in -1..rows) {
            for (col in -1..cols) {
                val baseX = col * size + (if (row % 2 != 0) size / 2f else 0f) + (ox % size)
                val baseY = row * triH + (oy % triH)

                // Upward triangle
                polygons.add(listOf(
                    Pair(baseX, baseY + triH),
                    Pair(baseX + size / 2f, baseY),
                    Pair(baseX + size, baseY + triH)
                ))
                // Downward triangle
                polygons.add(listOf(
                    Pair(baseX, baseY),
                    Pair(baseX + size, baseY),
                    Pair(baseX + size / 2f, baseY + triH)
                ))
            }
        }
        return polygons
    }

    private fun generateSquare(
        size: Float, w: Float, h: Float,
        ox: Float, oy: Float
    ): List<List<Pair<Float, Float>>> {
        val polygons = mutableListOf<List<Pair<Float, Float>>>()
        val cols = (w / size).toInt() + 2
        val rows = (h / size).toInt() + 2

        for (row in -1..rows) {
            for (col in -1..cols) {
                val x = col * size + (ox % size)
                val y = row * size + (oy % size)
                polygons.add(listOf(
                    Pair(x, y), Pair(x + size, y),
                    Pair(x + size, y + size), Pair(x, y + size)
                ))
            }
        }
        return polygons
    }

    private fun generateHexagonal(
        size: Float, w: Float, h: Float,
        ox: Float, oy: Float
    ): List<List<Pair<Float, Float>>> {
        val polygons = mutableListOf<List<Pair<Float, Float>>>()
        val hexW = size * 1.5f
        val hexH = size * sqrt(3f)
        val cols = (w / hexW).toInt() + 3
        val rows = (h / hexH).toInt() + 3

        for (row in -1..rows) {
            for (col in -1..cols) {
                val cx = col * hexW + (ox % hexW)
                val cy = row * hexH + (if (col % 2 != 0) hexH / 2f else 0f) + (oy % hexH)

                val hex = (0..5).map { i ->
                    val angle = (PI / 3.0 * i + PI / 6.0).toFloat()
                    Pair(cx + size * cos(angle), cy + size * sin(angle))
                }
                polygons.add(hex)
            }
        }
        return polygons
    }

    private fun generateCairo(
        size: Float, w: Float, h: Float,
        ox: Float, oy: Float
    ): List<List<Pair<Float, Float>>> {
        // Cairo tiling uses pentagons; approximate with a dual-square arrangement
        val polygons = mutableListOf<List<Pair<Float, Float>>>()
        val step = size * 2f
        val cols = (w / step).toInt() + 3
        val rows = (h / step).toInt() + 3
        val half = size

        for (row in -1..rows) {
            for (col in -1..cols) {
                val baseX = col * step + (ox % step)
                val baseY = row * step + (oy % step)
                val cx = baseX + half
                val cy = baseY + half

                // Four pentagons arranged around the centre of a square tile
                val q = size * 0.4f
                // Top pentagon
                polygons.add(listOf(
                    Pair(baseX, baseY), Pair(baseX + step, baseY),
                    Pair(cx + q, cy - q), Pair(cx, cy),
                    Pair(cx - q, cy - q)
                ))
                // Right pentagon
                polygons.add(listOf(
                    Pair(baseX + step, baseY), Pair(baseX + step, baseY + step),
                    Pair(cx + q, cy + q), Pair(cx, cy),
                    Pair(cx + q, cy - q)
                ))
                // Bottom pentagon
                polygons.add(listOf(
                    Pair(baseX + step, baseY + step), Pair(baseX, baseY + step),
                    Pair(cx - q, cy + q), Pair(cx, cy),
                    Pair(cx + q, cy + q)
                ))
                // Left pentagon
                polygons.add(listOf(
                    Pair(baseX, baseY + step), Pair(baseX, baseY),
                    Pair(cx - q, cy - q), Pair(cx, cy),
                    Pair(cx - q, cy + q)
                ))
            }
        }
        return polygons
    }

    private fun generateSnubSquare(
        size: Float, w: Float, h: Float,
        ox: Float, oy: Float
    ): List<List<Pair<Float, Float>>> {
        // Snub square tiling: combination of squares and equilateral triangles
        val polygons = mutableListOf<List<Pair<Float, Float>>>()
        val triH = size * sqrt(3f) / 2f
        val stepX = size + triH
        val stepY = size + triH
        val cols = (w / stepX).toInt() + 3
        val rows = (h / stepY).toInt() + 3

        for (row in -1..rows) {
            for (col in -1..cols) {
                val x = col * stepX + (ox % stepX)
                val y = row * stepY + (oy % stepY)

                // Central square
                polygons.add(listOf(
                    Pair(x, y), Pair(x + size, y),
                    Pair(x + size, y + size), Pair(x, y + size)
                ))

                // Right triangle
                polygons.add(listOf(
                    Pair(x + size, y),
                    Pair(x + size + triH, y + size / 2f),
                    Pair(x + size, y + size)
                ))

                // Bottom triangle
                polygons.add(listOf(
                    Pair(x, y + size),
                    Pair(x + size / 2f, y + size + triH),
                    Pair(x + size, y + size)
                ))

                // Corner triangle (fills the diagonal gap)
                polygons.add(listOf(
                    Pair(x + size, y + size),
                    Pair(x + size + triH, y + size / 2f),
                    Pair(x + size + triH, y + size + triH)
                ))
            }
        }
        return polygons
    }

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float {
        val cellSize = (params["cellSize"] as? Number)?.toFloat() ?: 30f
        return (30f / cellSize).coerceIn(0.2f, 1f)
    }
}
