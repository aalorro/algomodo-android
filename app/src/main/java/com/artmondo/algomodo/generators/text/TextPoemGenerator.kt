package com.artmondo.algomodo.generators.text

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import com.artmondo.algomodo.core.rng.SeededRNG
import com.artmondo.algomodo.data.palettes.Palette
import com.artmondo.algomodo.generators.Generator
import com.artmondo.algomodo.generators.ParamGroup
import com.artmondo.algomodo.generators.Parameter
import com.artmondo.algomodo.generators.Quality
import com.artmondo.algomodo.rendering.SvgPath
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

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
        val textContrast = (params["textContrast"] as? Number)?.toFloat() ?: 0.85f
        val speed = (params["speed"] as? Number)?.toFloat() ?: 0.5f
        val style = (params["style"] as? String) ?: "classic"

        val rng = SeededRNG(seed)
        val paletteColors = palette.colorInts()

        canvas.drawColor(Color.BLACK)

        val rawLines = text.split("|")
        val margin = w * 0.1f
        val textWidth = w - margin * 2f

        // Word-wrap lines that are too long
        val measurePaint = Paint().apply {
            textSize = fontSize
            typeface = if (style == "typewriter") Typeface.MONOSPACE else Typeface.SERIF
        }
        val lines = mutableListOf<String>()
        for (rawLine in rawLines) {
            if (rawLine.isBlank() || measurePaint.measureText(rawLine) <= textWidth) {
                lines.add(rawLine)
            } else {
                // Word wrap
                val words = rawLine.split(" ")
                var currentLine = ""
                for (word in words) {
                    val test = if (currentLine.isEmpty()) word else "$currentLine $word"
                    if (measurePaint.measureText(test) <= textWidth) {
                        currentLine = test
                    } else {
                        if (currentLine.isNotEmpty()) lines.add(currentLine)
                        currentLine = word
                    }
                }
                if (currentLine.isNotEmpty()) lines.add(currentLine)
            }
        }

        when (style) {
            "classic" -> drawClassic(canvas, lines, w, h, fontSize, lineSpacing, alignment, margin, textWidth, textContrast, speed, time, palette, rng, quality)
            "typewriter" -> drawTypewriter(canvas, lines, w, h, fontSize, lineSpacing, margin, textContrast, speed, time, palette, rng, quality)
            "blackout" -> drawBlackout(canvas, lines, w, h, fontSize, lineSpacing, alignment, margin, textWidth, textContrast, speed, time, palette, rng, quality)
            "concrete" -> drawConcrete(canvas, lines, w, h, fontSize, lineSpacing, margin, textWidth, textContrast, speed, time, palette, rng, quality)
            else -> drawClassic(canvas, lines, w, h, fontSize, lineSpacing, alignment, margin, textWidth, textContrast, speed, time, palette, rng, quality)
        }
    }

    private fun drawClassic(
        canvas: Canvas, lines: List<String>, w: Float, h: Float,
        fontSize: Float, lineSpacing: Float, alignment: String,
        margin: Float, textWidth: Float, textContrast: Float,
        speed: Float, time: Float, palette: Palette, rng: SeededRNG, quality: Quality
    ) {
        val lineHeight = fontSize * lineSpacing
        val totalHeight = lines.size * lineHeight
        val scrollOffset = if (speed > 0f && time > 0f) (time * speed * 20f) % (totalHeight + h) - h * 0.5f else 0f
        val startY = (h - totalHeight) / 2f + fontSize - scrollOffset
        val baseAlpha = (textContrast * 255).toInt()

        val paint = Paint().apply {
            isAntiAlias = quality != Quality.DRAFT
            textSize = fontSize
            typeface = Typeface.SERIF
        }

        val rulePaint = Paint().apply {
            isAntiAlias = quality != Quality.DRAFT
            style = Paint.Style.STROKE
            strokeWidth = 1f
        }

        for ((i, line) in lines.withIndex()) {
            val y = startY + i * lineHeight
            val lineT = i.toFloat() / lines.size.coerceAtLeast(1)

            // Per-line fade based on distance from viewport center
            val distFromCenter = abs(y - h / 2f) / (h / 2f)
            val fadeFactor = (1f - distFromCenter * 0.3f).coerceIn(0.3f, 1f)

            paint.color = palette.lerpColor(lineT)
            paint.alpha = (baseAlpha * fadeFactor).toInt().coerceIn(0, 255)

            if (line.isBlank()) {
                // Ornamental diamond flourish divider
                rulePaint.color = palette.lerpColor(lineT)
                rulePaint.alpha = (80 * fadeFactor).toInt()
                val ruleY = y - fontSize * 0.3f
                val ruleLen = w * 0.15f
                val cx = w / 2f
                // Diamond shape
                val diamondSize = fontSize * 0.3f
                canvas.drawLine(cx - ruleLen, ruleY, cx - diamondSize, ruleY, rulePaint)
                canvas.drawLine(cx + diamondSize, ruleY, cx + ruleLen, ruleY, rulePaint)
                // Small diamond
                rulePaint.style = Paint.Style.STROKE
                canvas.save()
                canvas.translate(cx, ruleY)
                canvas.rotate(45f)
                canvas.drawRect(-diamondSize * 0.5f, -diamondSize * 0.5f, diamondSize * 0.5f, diamondSize * 0.5f, rulePaint)
                canvas.restore()
                continue
            }

            drawAlignedLine(canvas, line, paint, y, alignment, margin, textWidth, w)
        }
    }

    private fun drawTypewriter(
        canvas: Canvas, lines: List<String>, w: Float, h: Float,
        fontSize: Float, lineSpacing: Float, margin: Float,
        textContrast: Float, speed: Float, time: Float,
        palette: Palette, rng: SeededRNG, quality: Quality
    ) {
        val lineHeight = fontSize * lineSpacing
        val totalHeight = lines.size * lineHeight
        val startY = (h - totalHeight) / 2f + fontSize
        val baseAlpha = (textContrast * 255).toInt()

        val paint = Paint().apply {
            isAntiAlias = quality != Quality.DRAFT
            textSize = fontSize
            typeface = Typeface.MONOSPACE
            textAlign = Paint.Align.LEFT
        }

        val charWidth = paint.measureText("M")
        // Progressive reveal: how many total characters revealed so far
        val totalChars = lines.sumOf { it.length }
        val revealProgress = if (speed > 0f && time > 0f) {
            ((time * speed * 15f) % (totalChars + 20)).toInt()
        } else {
            totalChars
        }

        var charCounter = 0
        for ((i, line) in lines.withIndex()) {
            val y = startY + i * lineHeight
            val lineT = i.toFloat() / lines.size.coerceAtLeast(1)
            paint.color = palette.lerpColor(lineT)

            // Draw character by character for ink-strike effect
            for ((j, ch) in line.withIndex()) {
                if (charCounter >= revealProgress) {
                    // Draw blinking cursor at current position
                    val cursorBlink = ((time * 3f).toInt() % 2 == 0)
                    if (cursorBlink) {
                        val cursorX = margin + j * charWidth
                        paint.alpha = baseAlpha
                        canvas.drawText("_", cursorX, y, paint)
                    }
                    return // Stop rendering — progressive reveal
                }

                val x = margin + j * charWidth
                // Slight alpha variation for ink-strike effect
                val inkVariation = rng.range(0.85f, 1f)
                paint.alpha = (baseAlpha * inkVariation).toInt().coerceIn(0, 255)
                canvas.drawText(ch.toString(), x, y, paint)
                charCounter++
            }
            charCounter++ // Count line break as a char
        }
    }

    private fun drawBlackout(
        canvas: Canvas, lines: List<String>, w: Float, h: Float,
        fontSize: Float, lineSpacing: Float, alignment: String,
        margin: Float, textWidth: Float, textContrast: Float,
        speed: Float, time: Float, palette: Palette, rng: SeededRNG, quality: Quality
    ) {
        // First draw all text, then black out ~70% of words
        val lineHeight = fontSize * lineSpacing
        val totalHeight = lines.size * lineHeight
        val startY = (h - totalHeight) / 2f + fontSize
        val baseAlpha = (textContrast * 255).toInt()

        val paint = Paint().apply {
            isAntiAlias = quality != Quality.DRAFT
            textSize = fontSize
            typeface = Typeface.SERIF
            textAlign = Paint.Align.LEFT
        }

        val blackPaint = Paint().apply {
            color = Color.BLACK
            style = Paint.Style.FILL
        }

        // Build word positions
        data class WordInfo(val word: String, val x: Float, val y: Float, val width: Float, val lineIdx: Int)
        val allWords = mutableListOf<WordInfo>()

        for ((i, line) in lines.withIndex()) {
            if (line.isBlank()) continue
            val y = startY + i * lineHeight
            val words = line.split(" ").filter { it.isNotEmpty() }
            val totalWordWidth = words.sumOf { paint.measureText(it).toDouble() }.toFloat()
            val spacing = if (words.size > 1) {
                ((textWidth - totalWordWidth) / (words.size - 1)).coerceAtMost(fontSize * 2f)
            } else {
                0f
            }
            var x = margin
            for (word in words) {
                val wordWidth = paint.measureText(word)
                allWords.add(WordInfo(word, x, y, wordWidth, i))
                x += wordWidth + spacing
            }
        }

        // Determine which words survive (seeded, ~30% survive)
        val surviveRng = SeededRNG(rng.integer(0, 100000))
        val surviving = allWords.map { surviveRng.random() < 0.3f }

        // Draw all text first
        for ((idx, wordInfo) in allWords.withIndex()) {
            val lineT = wordInfo.lineIdx.toFloat() / lines.size.coerceAtLeast(1)
            paint.color = palette.lerpColor(lineT)
            paint.alpha = baseAlpha
            canvas.drawText(wordInfo.word, wordInfo.x, wordInfo.y, paint)
        }

        // Black out non-surviving words with animated reveal
        val revealProgress = if (speed > 0f && time > 0f) {
            (time * speed * 8f).coerceIn(0f, allWords.size.toFloat()).toInt()
        } else {
            allWords.size
        }

        for ((idx, wordInfo) in allWords.withIndex()) {
            if (idx >= revealProgress) break
            if (!surviving[idx]) {
                val padding = fontSize * 0.15f
                canvas.drawRect(
                    wordInfo.x - padding,
                    wordInfo.y - fontSize + padding,
                    wordInfo.x + wordInfo.width + padding,
                    wordInfo.y + fontSize * 0.3f,
                    blackPaint
                )
            }
        }
    }

    private fun drawConcrete(
        canvas: Canvas, lines: List<String>, w: Float, h: Float,
        fontSize: Float, lineSpacing: Float, margin: Float,
        textWidth: Float, textContrast: Float,
        speed: Float, time: Float, palette: Palette, rng: SeededRNG, quality: Quality
    ) {
        // Text shaped into visual forms — lines vary in margin to create silhouette
        val lineHeight = fontSize * lineSpacing
        val totalHeight = lines.size * lineHeight
        val startY = (h - totalHeight) / 2f + fontSize
        val baseAlpha = (textContrast * 255).toInt()

        val paint = Paint().apply {
            isAntiAlias = quality != Quality.DRAFT
            textSize = fontSize
            typeface = Typeface.SERIF
            textAlign = Paint.Align.CENTER
        }

        // Choose shape based on seed: diamond, circle, or hourglass
        val shapeType = rng.integer(0, 2)
        val centerLine = lines.size / 2f

        for ((i, line) in lines.withIndex()) {
            if (line.isBlank()) continue

            val y = startY + i * lineHeight
            val lineT = i.toFloat() / lines.size.coerceAtLeast(1)
            val distFromCenter = abs(i - centerLine) / centerLine.coerceAtLeast(1f)

            // Calculate line width based on shape
            val shapeWidth = when (shapeType) {
                0 -> {
                    // Diamond: widest at center, narrowing to edges
                    textWidth * (1f - distFromCenter).coerceIn(0.1f, 1f)
                }
                1 -> {
                    // Circle: use circular profile
                    val r = 1f - distFromCenter * distFromCenter
                    textWidth * r.coerceIn(0.1f, 1f)
                }
                else -> {
                    // Hourglass: narrow at center, wide at edges
                    val hourglassWidth = distFromCenter * 0.7f + 0.3f
                    textWidth * hourglassWidth.coerceIn(0.1f, 1f)
                }
            }

            val lineMargin = (w - shapeWidth) / 2f

            // Animate: subtle breathing effect
            val breathe = sin(time * speed + i * 0.3f) * fontSize * 0.2f

            paint.color = palette.lerpColor(lineT)
            paint.alpha = (baseAlpha * (1f - distFromCenter * 0.3f)).toInt().coerceIn(0, 255)

            // Truncate or scale line to fit shape width
            val measuredWidth = paint.measureText(line)
            if (measuredWidth > shapeWidth && shapeWidth > 0) {
                val scale = shapeWidth / measuredWidth
                paint.textSize = fontSize * scale.coerceIn(0.3f, 1f)
            } else {
                paint.textSize = fontSize
            }

            canvas.drawText(line, w / 2f + breathe, y, paint)
        }
    }

    private fun drawAlignedLine(
        canvas: Canvas, line: String, paint: Paint, y: Float,
        alignment: String, margin: Float, textWidth: Float, w: Float
    ) {
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

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float = 0.15f
}
