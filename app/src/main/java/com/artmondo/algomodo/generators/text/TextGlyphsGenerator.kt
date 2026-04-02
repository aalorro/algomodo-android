package com.artmondo.algomodo.generators.text

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import com.artmondo.algomodo.core.rng.SeededRNG
import com.artmondo.algomodo.data.palettes.Palette
import com.artmondo.algomodo.generators.Generator
import com.artmondo.algomodo.generators.ParamGroup
import com.artmondo.algomodo.generators.Parameter
import com.artmondo.algomodo.generators.Quality
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Procedural glyph/symbol generator.
 *
 * Creates an alphabet of abstract glyphs built from random geometric primitives
 * (lines, arcs, circles, dots, crosses, triangles, zigzags) and renders them
 * in configurable layouts.
 */
class TextGlyphsGenerator : Generator {

    override val id = "text-glyphs"
    override val family = "text"
    override val styleName = "Glyphs"
    override val definition =
        "Procedural abstract glyphs composed from random geometric primitives arranged in various layouts."
    override val algorithmNotes =
        "Each unique glyph is deterministically generated from a sub-seed by composing N geometric " +
        "primitives (line, arc, circle, dot, cross, triangle, zigzag) within a normalized cell. " +
        "The finite glyph alphabet is then laid out in grid, scattered, circular, or tablet arrangements."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema = listOf(
        Parameter.NumberParam("Glyph Count", "glyphCount", ParamGroup.COMPOSITION, "Number of unique glyphs in the alphabet", 8f, 64f, 1f, 26f),
        Parameter.NumberParam("Cell Size", "cellSize", ParamGroup.GEOMETRY, "Size of each glyph cell in pixels", 20f, 80f, 2f, 36f),
        Parameter.NumberParam("Complexity", "complexity", ParamGroup.GEOMETRY, "Number of primitives per glyph", 1f, 6f, 1f, 3f),
        Parameter.NumberParam("Stroke Width", "strokeWidth", ParamGroup.GEOMETRY, "Line thickness for glyph strokes", 1f, 5f, 0.5f, 2f),
        Parameter.SelectParam("Layout", "layout", ParamGroup.COMPOSITION, "Arrangement of glyphs on canvas", listOf("grid", "scattered", "circular", "tablet"), "grid"),
        Parameter.BooleanParam("Fill Shapes", "fillShapes", ParamGroup.TEXTURE, "Fill primitives instead of stroking", false),
        Parameter.SelectParam("Color Mode", "colorMode", ParamGroup.COLOR, "How colors are assigned to glyphs", listOf("per-glyph", "per-row", "gradient", "monochrome"), "per-glyph"),
        Parameter.SelectParam("Animation", "animMode", ParamGroup.FLOW_MOTION, "Animation style", listOf("none", "reveal", "rotate", "pulse"), "none"),
        Parameter.NumberParam("Speed", "speed", ParamGroup.FLOW_MOTION, "Animation speed", 0.05f, 1f, 0.05f, 0.3f)
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "glyphCount" to 26f,
        "cellSize" to 36f,
        "complexity" to 3f,
        "strokeWidth" to 2f,
        "layout" to "grid",
        "fillShapes" to false,
        "colorMode" to "per-glyph",
        "animMode" to "none",
        "speed" to 0.3f
    )

    private data class GlyphPrimitive(
        val type: Int, // 0=line, 1=arc, 2=circle, 3=dot, 4=cross, 5=triangle, 6=zigzag
        val x1: Float, val y1: Float,
        val x2: Float, val y2: Float,
        val radius: Float
    )

    private fun generateGlyph(rng: SeededRNG, complexity: Int): List<GlyphPrimitive> {
        val primitives = mutableListOf<GlyphPrimitive>()
        for (i in 0 until complexity) {
            val type = rng.integer(0, 6)
            val x1 = rng.range(0.1f, 0.9f)
            val y1 = rng.range(0.1f, 0.9f)
            val x2 = rng.range(0.1f, 0.9f)
            val y2 = rng.range(0.1f, 0.9f)
            val radius = rng.range(0.05f, 0.3f)
            primitives.add(GlyphPrimitive(type, x1, y1, x2, y2, radius))
        }
        return primitives
    }

    private fun drawGlyph(
        canvas: Canvas, primitives: List<GlyphPrimitive>,
        cx: Float, cy: Float, size: Float,
        paint: Paint, fill: Boolean
    ) {
        val half = size / 2f
        for (p in primitives) {
            val ax = cx - half + p.x1 * size
            val ay = cy - half + p.y1 * size
            val bx = cx - half + p.x2 * size
            val by = cy - half + p.y2 * size
            val r = p.radius * size

            when (p.type) {
                0 -> { // line
                    paint.style = Paint.Style.STROKE
                    canvas.drawLine(ax, ay, bx, by, paint)
                }
                1 -> { // arc
                    paint.style = Paint.Style.STROKE
                    val path = Path()
                    path.moveTo(ax, ay)
                    path.quadTo(cx, cy, bx, by)
                    canvas.drawPath(path, paint)
                }
                2 -> { // circle
                    paint.style = if (fill) Paint.Style.FILL else Paint.Style.STROKE
                    canvas.drawCircle(ax, ay, r, paint)
                }
                3 -> { // dot
                    paint.style = Paint.Style.FILL
                    canvas.drawCircle(ax, ay, r * 0.5f, paint)
                }
                4 -> { // cross
                    paint.style = Paint.Style.STROKE
                    canvas.drawLine(ax - r, ay, ax + r, ay, paint)
                    canvas.drawLine(ax, ay - r, ax, ay + r, paint)
                }
                5 -> { // triangle
                    paint.style = if (fill) Paint.Style.FILL else Paint.Style.STROKE
                    val path = Path()
                    path.moveTo(ax, ay - r)
                    path.lineTo(ax - r * 0.866f, ay + r * 0.5f)
                    path.lineTo(ax + r * 0.866f, ay + r * 0.5f)
                    path.close()
                    canvas.drawPath(path, paint)
                }
                6 -> { // zigzag
                    paint.style = Paint.Style.STROKE
                    val path = Path()
                    path.moveTo(ax, ay)
                    val segments = 3
                    val dx = (bx - ax) / segments
                    val dy = (by - ay) / segments
                    for (s in 0 until segments) {
                        val zigOffset = if (s % 2 == 0) r else -r
                        path.lineTo(ax + dx * (s + 0.5f) - dy * 0.3f + zigOffset * 0.5f,
                                     ay + dy * (s + 0.5f) + dx * 0.3f)
                        path.lineTo(ax + dx * (s + 1f), ay + dy * (s + 1f))
                    }
                    canvas.drawPath(path, paint)
                }
            }
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
        val glyphCount = (params["glyphCount"] as? Number)?.toInt() ?: 26
        val cellSize = (params["cellSize"] as? Number)?.toFloat() ?: 36f
        val complexity = (params["complexity"] as? Number)?.toInt() ?: 3
        val strokeWidth = (params["strokeWidth"] as? Number)?.toFloat() ?: 2f
        val layout = (params["layout"] as? String) ?: "grid"
        val fillShapes = (params["fillShapes"] as? Boolean) ?: false
        val colorMode = (params["colorMode"] as? String) ?: "per-glyph"
        val animMode = (params["animMode"] as? String) ?: "none"
        val speed = (params["speed"] as? Number)?.toFloat() ?: 0.3f

        val rng = SeededRNG(seed)
        canvas.drawColor(Color.BLACK)

        // Generate the glyph alphabet
        val glyphs = (0 until glyphCount).map { i ->
            val glyphRng = SeededRNG(seed + i * 7919)
            generateGlyph(glyphRng, complexity)
        }

        val paint = Paint().apply {
            isAntiAlias = quality != Quality.DRAFT
            this.strokeWidth = strokeWidth
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        // Build positions based on layout
        data class GlyphPos(val x: Float, val y: Float, val glyphIdx: Int, val t: Float, val row: Int)
        val positions = mutableListOf<GlyphPos>()

        when (layout) {
            "grid" -> {
                val cols = (w / (cellSize * 1.4f)).toInt().coerceAtLeast(1)
                val rows = (h / (cellSize * 1.4f)).toInt().coerceAtLeast(1)
                val spacingX = w / cols
                val spacingY = h / rows
                var idx = 0
                for (row in 0 until rows) {
                    for (col in 0 until cols) {
                        val gx = spacingX * 0.5f + col * spacingX
                        val gy = spacingY * 0.5f + row * spacingY
                        val glyphIdx = idx % glyphCount
                        val t = idx.toFloat() / (rows * cols).coerceAtLeast(1)
                        positions.add(GlyphPos(gx, gy, glyphIdx, t, row))
                        idx++
                    }
                }
            }
            "scattered" -> {
                val count = ((w * h) / (cellSize * cellSize * 2f)).toInt().coerceIn(20, 500)
                for (i in 0 until count) {
                    val gx = rng.range(cellSize, w - cellSize)
                    val gy = rng.range(cellSize, h - cellSize)
                    val glyphIdx = rng.integer(0, glyphCount - 1)
                    val t = i.toFloat() / count
                    positions.add(GlyphPos(gx, gy, glyphIdx, t, 0))
                }
            }
            "circular" -> {
                val cx = w / 2f
                val cy = h / 2f
                val maxR = min(w, h) * 0.42f
                val rings = (maxR / (cellSize * 1.3f)).toInt().coerceAtLeast(1)
                var idx = 0
                for (ring in 0 until rings) {
                    val r = cellSize + ring * (maxR / rings)
                    val circumference = 2f * PI.toFloat() * r
                    val glyphsInRing = (circumference / (cellSize * 1.2f)).toInt().coerceAtLeast(4)
                    for (j in 0 until glyphsInRing) {
                        val angle = j.toFloat() / glyphsInRing * 2f * PI.toFloat()
                        val gx = cx + cos(angle) * r
                        val gy = cy + sin(angle) * r
                        val glyphIdx = idx % glyphCount
                        val t = idx.toFloat() / (rings * glyphsInRing).coerceAtLeast(1)
                        positions.add(GlyphPos(gx, gy, glyphIdx, t, ring))
                        idx++
                    }
                }
            }
            "tablet" -> {
                // Dense lines of glyphs like ancient clay tablet writing
                val lineHeight = cellSize * 1.3f
                val charWidth = cellSize * 1.1f
                val marginX = cellSize * 0.8f
                val marginY = cellSize * 0.8f
                val cols = ((w - marginX * 2) / charWidth).toInt().coerceAtLeast(1)
                val rows = ((h - marginY * 2) / lineHeight).toInt().coerceAtLeast(1)
                var idx = 0
                for (row in 0 until rows) {
                    for (col in 0 until cols) {
                        val gx = marginX + col * charWidth + charWidth * 0.5f
                        val gy = marginY + row * lineHeight + lineHeight * 0.5f
                        val glyphIdx = idx % glyphCount
                        val t = idx.toFloat() / (rows * cols).coerceAtLeast(1)
                        positions.add(GlyphPos(gx, gy, glyphIdx, t, row))
                        idx++
                    }
                }
            }
        }

        // Draw each glyph
        val totalGlyphs = positions.size
        for ((i, pos) in positions.withIndex()) {
            // Animation
            when (animMode) {
                "reveal" -> {
                    val revealCount = (time * speed * 30f).toInt()
                    if (i >= revealCount) continue
                }
                "rotate" -> {
                    canvas.save()
                    canvas.translate(pos.x, pos.y)
                    canvas.rotate(time * speed * 90f)
                    canvas.translate(-pos.x, -pos.y)
                }
                "pulse" -> {
                    val pulsePhase = sin(time * speed * 4f + i * 0.3f)
                    val scale = 1f + pulsePhase * 0.25f
                    canvas.save()
                    canvas.translate(pos.x, pos.y)
                    canvas.scale(scale, scale)
                    canvas.translate(-pos.x, -pos.y)
                }
            }

            // Color
            val color = when (colorMode) {
                "per-glyph" -> palette.lerpColor(pos.glyphIdx.toFloat() / glyphCount.coerceAtLeast(1))
                "per-row" -> palette.lerpColor(pos.row.toFloat() / 10f)
                "gradient" -> palette.lerpColor(pos.t)
                "monochrome" -> palette.lerpColor(0.5f)
                else -> palette.lerpColor(pos.t)
            }
            paint.color = color

            drawGlyph(canvas, glyphs[pos.glyphIdx], pos.x, pos.y, cellSize, paint, fillShapes)

            if (animMode == "rotate" || animMode == "pulse") {
                canvas.restore()
            }
        }
    }

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float = 0.3f
}
