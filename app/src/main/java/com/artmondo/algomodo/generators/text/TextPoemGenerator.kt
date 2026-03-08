package com.artmondo.algomodo.generators.text

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import com.artmondo.algomodo.core.rng.SeededRNG
import com.artmondo.algomodo.data.palettes.Palette
import com.artmondo.algomodo.generators.Generator
import com.artmondo.algomodo.generators.ParamGroup
import com.artmondo.algomodo.generators.Parameter
import com.artmondo.algomodo.generators.Quality
import com.artmondo.algomodo.rendering.SvgPath

/**
 * Poem / multiline text layout generator.
 *
 * Renders user-supplied multiline text (pipe character as line separator)
 * with configurable alignment, spacing, and artistic formatting.
 */
class TextPoemGenerator : Generator {

    override val id = "text-poem"
    override val family = "text"
    override val styleName = "Poem Layout"
    override val definition =
        "Artistic multiline text rendering with configurable alignment and spacing."
    override val algorithmNotes =
        "Input text is split on pipe characters to produce lines. Each line is rendered " +
        "at the specified font size with the chosen alignment. 'justified' mode spaces " +
        "words evenly across the canvas width. A subtle decorative rule line is drawn " +
        "between stanzas when blank lines are encountered."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema = listOf(
        Parameter.SelectParam("Style", "style", ParamGroup.COMPOSITION, "Poem visual style — classic: centred serif | typewriter: monospace left | blackout: redacted words | concrete: shaped text", listOf("classic", "typewriter", "blackout", "concrete"), "classic"),
        Parameter.NumberParam("Font Scale", "fontScale", ParamGroup.GEOMETRY, "Text size multiplier", 0.5f, 3f, 0.1f, 1f),
        Parameter.SelectParam("Alignment", "alignment", ParamGroup.COMPOSITION, "Text alignment", listOf("left", "center", "right", "justified"), "center"),
        Parameter.NumberParam("Line Spacing", "lineSpacing", ParamGroup.GEOMETRY, "Multiplier for line height", 1f, 3f, 0.1f, 1.5f),
        Parameter.NumberParam("Text Contrast", "textContrast", ParamGroup.COLOR, "Contrast between text and background — 0 = faint, 1 = bold", 0.2f, 1f, 0.05f, 0.85f),
        Parameter.NumberParam("Speed", "speed", ParamGroup.FLOW_MOTION, "Animation speed", 0.1f, 3f, 0.1f, 0.5f),
        Parameter.TextParam("Custom Text", "customText", ParamGroup.COMPOSITION, "Custom poem text (use | for line breaks) — leave empty for random", "", placeholder = "Enter poem text (leave empty for random)", maxLength = 500)
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "style" to "classic",
        "fontScale" to 1f,
        "alignment" to "center",
        "lineSpacing" to 1.5f,
        "textContrast" to 0.85f,
        "speed" to 0.5f,
        "customText" to ""
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
        val text = (params["customText"] as? String)?.ifEmpty {
            "roses are red|violets are blue|algorithms make art|and so can you"
        } ?: "roses are red|violets are blue|algorithms make art|and so can you"
        val fontScale = (params["fontScale"] as? Number)?.toFloat() ?: 1f
        val fontSize = 20f * fontScale
        val alignment = (params["alignment"] as? String) ?: "center"
        val lineSpacing = (params["lineSpacing"] as? Number)?.toFloat() ?: 1.5f
        val speed = (params["speed"] as? Number)?.toFloat() ?: 0.5f

        val rng = SeededRNG(seed)
        val paletteColors = palette.colorInts()

        canvas.drawColor(Color.BLACK)

        val lines = text.split("|")
        val lineHeight = fontSize * lineSpacing
        val totalHeight = lines.size * lineHeight
        val scrollOffset = if (speed > 0f && time > 0f) (time * speed * 20f) % (totalHeight + h) - h * 0.5f else 0f

        // Centre the block vertically
        val startY = (h - totalHeight) / 2f + fontSize - scrollOffset
        val margin = w * 0.1f
        val textWidth = w - margin * 2f

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = fontSize
            typeface = Typeface.SERIF
        }

        val rulePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1f
            color = paletteColors[0]
            alpha = 80
        }

        for ((i, line) in lines.withIndex()) {
            val y = startY + i * lineHeight
            paint.color = paletteColors[i % paletteColors.size]

            if (line.isBlank()) {
                // Draw decorative rule for blank lines (stanza breaks)
                val ruleY = y - fontSize * 0.3f
                val ruleMargin = w * 0.3f
                canvas.drawLine(ruleMargin, ruleY, w - ruleMargin, ruleY, rulePaint)
                continue
            }

            when (alignment) {
                "left" -> {
                    paint.textAlign = Paint.Align.LEFT
                    canvas.drawText(line, margin, y, paint)
                }
                "right" -> {
                    paint.textAlign = Paint.Align.RIGHT
                    canvas.drawText(line, w - margin, y, paint)
                }
                "justified" -> {
                    val words = line.split(" ").filter { it.isNotEmpty() }
                    if (words.size <= 1) {
                        paint.textAlign = Paint.Align.LEFT
                        canvas.drawText(line, margin, y, paint)
                    } else {
                        paint.textAlign = Paint.Align.LEFT
                        val totalWordWidth = words.sumOf { paint.measureText(it).toDouble() }.toFloat()
                        val spacing = (textWidth - totalWordWidth) / (words.size - 1)
                        var x = margin
                        for (word in words) {
                            canvas.drawText(word, x, y, paint)
                            x += paint.measureText(word) + spacing
                        }
                    }
                }
                else -> { // center
                    paint.textAlign = Paint.Align.CENTER
                    canvas.drawText(line, w / 2f, y, paint)
                }
            }
        }

        // Draw decorative brackets at top and bottom
        val bracketPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f
            color = paletteColors[paletteColors.size - 1]
            alpha = 60
        }
        val bracketWidth = w * 0.2f
        val topY = startY - lineHeight * 0.5f
        val bottomY = startY + lines.size * lineHeight - fontSize * 0.5f

        canvas.drawLine(w / 2f - bracketWidth, topY, w / 2f + bracketWidth, topY, bracketPaint)
        canvas.drawLine(w / 2f - bracketWidth, bottomY, w / 2f + bracketWidth, bottomY, bracketPaint)
    }

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float = 0.1f
}
