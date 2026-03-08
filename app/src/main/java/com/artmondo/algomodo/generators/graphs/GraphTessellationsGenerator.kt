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
        val fillOpacity = (params["fillOpacity"] as? Number)?.toFloat() ?: 0.85f
        val fillCells = fillOpacity > 0f

        val rng = SeededRNG(seed)
        val noise = SimplexNoise(seed)
        val colors = palette.colorInts()

        canvas.drawColor(Color.BLACK)

        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = lineWidth
            color = Color.rgb(30, 30, 30)
        }

        // Animation offset
        val offsetX = time * 5f
        val offsetY = time * 3f

        val polygons = generateTiling(tiling, cellSize, w, h, offsetX, offsetY)

        for ((index, polygon) in polygons.withIndex()) {
            val path = Path()
            if (polygon.isNotEmpty()) {
                path.moveTo(polygon[0].first, polygon[0].second)
                for (i in 1 until polygon.size) {
                    path.lineTo(polygon[i].first, polygon[i].second)
                }
                path.close()
            }

            if (fillCells) {
                fillPaint.color = colors[index % colors.size]
                canvas.drawPath(path, fillPaint)
            }
            canvas.drawPath(path, strokePaint)
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
            "snub-square" -> generateSnubSquare(cellSize, w, h, offsetX, offsetY)
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
