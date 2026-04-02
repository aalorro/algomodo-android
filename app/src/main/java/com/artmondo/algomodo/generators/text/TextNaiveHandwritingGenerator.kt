package com.artmondo.algomodo.generators.text

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
import kotlin.math.sin

/**
 * Naive handwriting generator.
 *
 * Renders text in a childlike, organic handwriting style where each letter
 * is drawn as jittered polyline strokes instead of font glyphs. Baseline
 * wobbles, sizes vary, and slight rotations give a hand-drawn feel.
 */
class TextNaiveHandwritingGenerator : Generator {

    override val id = "text-naive-handwriting"
    override val family = "text"
    override val styleName = "Naive Handwriting"
    override val definition =
        "Childlike handwriting where each letter is rendered as jittered polyline strokes with organic wobble."
    override val algorithmNotes =
        "Each character A-Z and 0-9 is defined as a list of strokes (polylines) in normalized " +
        "coordinates. When rendering, points are scaled to cell size with per-point jitter via " +
        "gaussian noise and SimplexNoise for organic feel. Tool styles affect stroke width and alpha."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema = listOf(
        Parameter.TextParam("Custom Text", "customText", ParamGroup.COMPOSITION, "Text to render — leave empty for random words", "", placeholder = "Enter text (leave empty for random)", maxLength = 300),
        Parameter.SelectParam("Tool", "tool", ParamGroup.TEXTURE, "Drawing tool style", listOf("pencil", "crayon", "marker"), "pencil"),
        Parameter.NumberParam("Font Size", "fontSize", ParamGroup.GEOMETRY, "Base character size", 20f, 64f, 2f, 32f),
        Parameter.NumberParam("Jitter", "jitter", ParamGroup.TEXTURE, "Amount of hand-tremor wobble", 0f, 1f, 0.05f, 0.4f),
        Parameter.NumberParam("Size Variation", "sizeVariation", ParamGroup.GEOMETRY, "Random variation in character sizes", 0f, 0.5f, 0.05f, 0.15f),
        Parameter.BooleanParam("Show Ruled Lines", "showRuledLines", ParamGroup.COMPOSITION, "Draw ruled notebook lines", false),
        Parameter.SelectParam("Color Mode", "colorMode", ParamGroup.COLOR, "How colors are assigned", listOf("per-word", "per-line", "rainbow", "monochrome"), "per-word"),
        Parameter.SelectParam("Animation", "animMode", ParamGroup.FLOW_MOTION, "Animation style", listOf("none", "scrawl", "wobble", "erase"), "none"),
        Parameter.NumberParam("Speed", "speed", ParamGroup.FLOW_MOTION, "Animation speed", 0.05f, 1f, 0.05f, 0.25f)
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "customText" to "",
        "tool" to "pencil",
        "fontSize" to 32f,
        "jitter" to 0.4f,
        "sizeVariation" to 0.15f,
        "showRuledLines" to false,
        "colorMode" to "per-word",
        "animMode" to "none",
        "speed" to 0.25f
    )

    // Stroke data: each character maps to a list of strokes, each stroke is a list of (x,y) in [0,1]
    private val charStrokes: Map<Char, List<List<Pair<Float, Float>>>> = mapOf(
        'A' to listOf(listOf(0.0f to 1.0f, 0.5f to 0.0f, 1.0f to 1.0f), listOf(0.2f to 0.6f, 0.8f to 0.6f)),
        'B' to listOf(listOf(0.0f to 1.0f, 0.0f to 0.0f, 0.7f to 0.0f, 0.8f to 0.2f, 0.7f to 0.5f, 0.0f to 0.5f, 0.7f to 0.5f, 0.8f to 0.8f, 0.7f to 1.0f, 0.0f to 1.0f)),
        'C' to listOf(listOf(0.9f to 0.2f, 0.5f to 0.0f, 0.1f to 0.2f, 0.0f to 0.5f, 0.1f to 0.8f, 0.5f to 1.0f, 0.9f to 0.8f)),
        'D' to listOf(listOf(0.0f to 0.0f, 0.0f to 1.0f, 0.5f to 1.0f, 0.9f to 0.7f, 0.9f to 0.3f, 0.5f to 0.0f, 0.0f to 0.0f)),
        'E' to listOf(listOf(0.8f to 0.0f, 0.0f to 0.0f, 0.0f to 1.0f, 0.8f to 1.0f), listOf(0.0f to 0.5f, 0.6f to 0.5f)),
        'F' to listOf(listOf(0.8f to 0.0f, 0.0f to 0.0f, 0.0f to 1.0f), listOf(0.0f to 0.5f, 0.6f to 0.5f)),
        'G' to listOf(listOf(0.9f to 0.2f, 0.5f to 0.0f, 0.1f to 0.2f, 0.0f to 0.5f, 0.1f to 0.8f, 0.5f to 1.0f, 0.9f to 0.8f, 0.9f to 0.5f, 0.5f to 0.5f)),
        'H' to listOf(listOf(0.0f to 0.0f, 0.0f to 1.0f), listOf(1.0f to 0.0f, 1.0f to 1.0f), listOf(0.0f to 0.5f, 1.0f to 0.5f)),
        'I' to listOf(listOf(0.3f to 0.0f, 0.7f to 0.0f), listOf(0.5f to 0.0f, 0.5f to 1.0f), listOf(0.3f to 1.0f, 0.7f to 1.0f)),
        'J' to listOf(listOf(0.3f to 0.0f, 0.8f to 0.0f), listOf(0.6f to 0.0f, 0.6f to 0.8f, 0.4f to 1.0f, 0.1f to 0.9f)),
        'K' to listOf(listOf(0.0f to 0.0f, 0.0f to 1.0f), listOf(0.8f to 0.0f, 0.0f to 0.5f, 0.8f to 1.0f)),
        'L' to listOf(listOf(0.0f to 0.0f, 0.0f to 1.0f, 0.8f to 1.0f)),
        'M' to listOf(listOf(0.0f to 1.0f, 0.0f to 0.0f, 0.5f to 0.4f, 1.0f to 0.0f, 1.0f to 1.0f)),
        'N' to listOf(listOf(0.0f to 1.0f, 0.0f to 0.0f, 1.0f to 1.0f, 1.0f to 0.0f)),
        'O' to listOf(listOf(0.5f to 0.0f, 0.1f to 0.2f, 0.0f to 0.5f, 0.1f to 0.8f, 0.5f to 1.0f, 0.9f to 0.8f, 1.0f to 0.5f, 0.9f to 0.2f, 0.5f to 0.0f)),
        'P' to listOf(listOf(0.0f to 1.0f, 0.0f to 0.0f, 0.7f to 0.0f, 0.8f to 0.2f, 0.7f to 0.5f, 0.0f to 0.5f)),
        'Q' to listOf(listOf(0.5f to 0.0f, 0.1f to 0.2f, 0.0f to 0.5f, 0.1f to 0.8f, 0.5f to 1.0f, 0.9f to 0.8f, 1.0f to 0.5f, 0.9f to 0.2f, 0.5f to 0.0f), listOf(0.7f to 0.8f, 1.0f to 1.0f)),
        'R' to listOf(listOf(0.0f to 1.0f, 0.0f to 0.0f, 0.7f to 0.0f, 0.8f to 0.2f, 0.7f to 0.5f, 0.0f to 0.5f), listOf(0.5f to 0.5f, 0.9f to 1.0f)),
        'S' to listOf(listOf(0.8f to 0.1f, 0.5f to 0.0f, 0.1f to 0.1f, 0.0f to 0.3f, 0.2f to 0.5f, 0.8f to 0.5f, 1.0f to 0.7f, 0.9f to 0.9f, 0.5f to 1.0f, 0.1f to 0.9f)),
        'T' to listOf(listOf(0.0f to 0.0f, 1.0f to 0.0f), listOf(0.5f to 0.0f, 0.5f to 1.0f)),
        'U' to listOf(listOf(0.0f to 0.0f, 0.0f to 0.8f, 0.3f to 1.0f, 0.7f to 1.0f, 1.0f to 0.8f, 1.0f to 0.0f)),
        'V' to listOf(listOf(0.0f to 0.0f, 0.5f to 1.0f, 1.0f to 0.0f)),
        'W' to listOf(listOf(0.0f to 0.0f, 0.25f to 1.0f, 0.5f to 0.4f, 0.75f to 1.0f, 1.0f to 0.0f)),
        'X' to listOf(listOf(0.0f to 0.0f, 1.0f to 1.0f), listOf(1.0f to 0.0f, 0.0f to 1.0f)),
        'Y' to listOf(listOf(0.0f to 0.0f, 0.5f to 0.5f, 1.0f to 0.0f), listOf(0.5f to 0.5f, 0.5f to 1.0f)),
        'Z' to listOf(listOf(0.0f to 0.0f, 1.0f to 0.0f, 0.0f to 1.0f, 1.0f to 1.0f)),
        '0' to listOf(listOf(0.5f to 0.0f, 0.1f to 0.2f, 0.0f to 0.5f, 0.1f to 0.8f, 0.5f to 1.0f, 0.9f to 0.8f, 1.0f to 0.5f, 0.9f to 0.2f, 0.5f to 0.0f)),
        '1' to listOf(listOf(0.3f to 0.2f, 0.5f to 0.0f, 0.5f to 1.0f), listOf(0.3f to 1.0f, 0.7f to 1.0f)),
        '2' to listOf(listOf(0.1f to 0.2f, 0.4f to 0.0f, 0.8f to 0.0f, 0.9f to 0.2f, 0.8f to 0.45f, 0.0f to 1.0f, 0.9f to 1.0f)),
        '3' to listOf(listOf(0.1f to 0.1f, 0.5f to 0.0f, 0.9f to 0.15f, 0.9f to 0.4f, 0.5f to 0.5f, 0.9f to 0.6f, 0.9f to 0.85f, 0.5f to 1.0f, 0.1f to 0.9f)),
        '4' to listOf(listOf(0.7f to 1.0f, 0.7f to 0.0f, 0.0f to 0.65f, 0.9f to 0.65f)),
        '5' to listOf(listOf(0.8f to 0.0f, 0.1f to 0.0f, 0.0f to 0.45f, 0.6f to 0.4f, 0.9f to 0.6f, 0.9f to 0.85f, 0.5f to 1.0f, 0.1f to 0.9f)),
        '6' to listOf(listOf(0.7f to 0.0f, 0.3f to 0.0f, 0.0f to 0.3f, 0.0f to 0.7f, 0.3f to 1.0f, 0.7f to 1.0f, 0.9f to 0.7f, 0.7f to 0.5f, 0.0f to 0.5f)),
        '7' to listOf(listOf(0.0f to 0.0f, 0.9f to 0.0f, 0.4f to 1.0f)),
        '8' to listOf(listOf(0.5f to 0.5f, 0.1f to 0.3f, 0.2f to 0.05f, 0.5f to 0.0f, 0.8f to 0.05f, 0.9f to 0.3f, 0.5f to 0.5f, 0.0f to 0.7f, 0.1f to 0.95f, 0.5f to 1.0f, 0.9f to 0.95f, 1.0f to 0.7f, 0.5f to 0.5f)),
        '9' to listOf(listOf(0.9f to 0.5f, 0.7f to 0.0f, 0.3f to 0.0f, 0.1f to 0.2f, 0.3f to 0.5f, 0.9f to 0.5f, 0.9f to 0.8f, 0.5f to 1.0f, 0.2f to 1.0f))
    )

    private val randomWords = listOf(
        "HELLO", "WORLD", "DREAM", "STARS", "OCEAN", "LIGHT", "MAGIC", "CLOUD",
        "RIVER", "BLOOM", "DANCE", "FLAME", "QUIET", "SPACE", "MUSIC", "PAINT",
        "SMILE", "STONE", "WINDS", "TREES", "WAVES", "BIRDS", "GHOST", "FROST"
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
        val customText = (params["customText"] as? String) ?: ""
        val tool = (params["tool"] as? String) ?: "pencil"
        val fontSize = (params["fontSize"] as? Number)?.toFloat() ?: 32f
        val jitter = (params["jitter"] as? Number)?.toFloat() ?: 0.4f
        val sizeVariation = (params["sizeVariation"] as? Number)?.toFloat() ?: 0.15f
        val showRuledLines = (params["showRuledLines"] as? Boolean) ?: false
        val colorMode = (params["colorMode"] as? String) ?: "per-word"
        val animMode = (params["animMode"] as? String) ?: "none"
        val speed = (params["speed"] as? Number)?.toFloat() ?: 0.25f

        val rng = SeededRNG(seed)
        val noise = SimplexNoise(seed)

        canvas.drawColor(Color.BLACK)

        // Build text
        val text = if (customText.isNotEmpty()) {
            customText.uppercase()
        } else {
            // Pick random words
            val wordCount = rng.integer(8, 16)
            (0 until wordCount).joinToString(" ") { rng.pick(randomWords) }
        }

        // Tool style
        val baseStrokeWidth: Float
        val baseAlpha: Int
        when (tool) {
            "crayon" -> { baseStrokeWidth = fontSize * 0.18f; baseAlpha = 200 }
            "marker" -> { baseStrokeWidth = fontSize * 0.1f; baseAlpha = 255 }
            else -> { baseStrokeWidth = fontSize * 0.05f; baseAlpha = 240 } // pencil
        }

        val paint = Paint().apply {
            isAntiAlias = quality != Quality.DRAFT
            style = Paint.Style.STROKE
            strokeWidth = baseStrokeWidth
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        // Layout: word-wrap into lines
        val margin = w * 0.08f
        val lineHeight = fontSize * 1.6f
        val charWidth = fontSize * 0.7f
        val maxCharsPerLine = ((w - margin * 2) / charWidth).toInt().coerceAtLeast(1)

        val words = text.split(" ").filter { it.isNotEmpty() }
        val lines = mutableListOf<String>()
        var currentLine = ""
        for (word in words) {
            val test = if (currentLine.isEmpty()) word else "$currentLine $word"
            if (test.length <= maxCharsPerLine) {
                currentLine = test
            } else {
                if (currentLine.isNotEmpty()) lines.add(currentLine)
                currentLine = word
            }
        }
        if (currentLine.isNotEmpty()) lines.add(currentLine)

        // Draw ruled lines
        if (showRuledLines) {
            val linePaint = Paint().apply {
                color = palette.lerpColor(0.5f)
                alpha = 40
                strokeWidth = 1f
                style = Paint.Style.STROKE
            }
            for (i in lines.indices) {
                val y = margin + (i + 1) * lineHeight
                canvas.drawLine(margin, y, w - margin, y, linePaint)
            }
        }

        // Count total characters for animation
        val totalChars = lines.sumOf { it.length }
        var globalCharIdx = 0
        var globalWordIdx = 0

        for ((lineIdx, line) in lines.withIndex()) {
            val baseY = margin + (lineIdx + 1) * lineHeight
            var cursorX = margin

            val lineWords = line.split(" ")
            for ((wordInLineIdx, word) in lineWords.withIndex()) {
                for ((charInWordIdx, ch) in word.withIndex()) {
                    val upperCh = ch.uppercaseChar()
                    val strokes = charStrokes[upperCh] ?: continue

                    // Animation checks
                    when (animMode) {
                        "scrawl" -> {
                            val revealCount = (time * speed * 20f).toInt()
                            if (globalCharIdx >= revealCount) return
                        }
                        "erase" -> {
                            val eraseStart = (time * speed * 15f).toInt()
                            if (globalCharIdx < eraseStart) {
                                cursorX += charWidth * (1f + rng.range(-sizeVariation, sizeVariation))
                                globalCharIdx++
                                continue
                            }
                        }
                    }

                    // Per-char size variation
                    val sizeScale = 1f + rng.range(-sizeVariation, sizeVariation)
                    val cellW = charWidth * sizeScale
                    val cellH = fontSize * sizeScale

                    // Baseline wobble via noise
                    val wobbleY = noise.noise2D(globalCharIdx * 0.3f, lineIdx.toFloat()) * fontSize * 0.15f
                    val wobbleAngle = noise.noise2D(globalCharIdx * 0.5f + 10f, lineIdx.toFloat()) * 8f

                    // Animation wobble
                    val animWobble = if (animMode == "wobble") {
                        sin(time * speed * 5f + globalCharIdx * 0.7f) * jitter * fontSize * 0.3f
                    } else 0f

                    // Color
                    val color = when (colorMode) {
                        "per-word" -> palette.lerpColor(globalWordIdx.toFloat() / words.size.coerceAtLeast(1))
                        "per-line" -> palette.lerpColor(lineIdx.toFloat() / lines.size.coerceAtLeast(1))
                        "rainbow" -> palette.lerpColor(globalCharIdx.toFloat() / totalChars.coerceAtLeast(1))
                        "monochrome" -> palette.lerpColor(0.5f)
                        else -> palette.lerpColor(0.5f)
                    }
                    paint.color = color
                    paint.alpha = baseAlpha

                    canvas.save()
                    canvas.translate(cursorX, baseY + wobbleY + animWobble)
                    canvas.rotate(wobbleAngle)

                    // Draw each stroke of the character with jitter
                    for (stroke in strokes) {
                        if (stroke.size < 2) continue
                        val path = Path()
                        for ((ptIdx, pt) in stroke.withIndex()) {
                            val jx = pt.first * cellW + noise.noise2D(
                                globalCharIdx + ptIdx * 0.7f, pt.second * 3f
                            ) * jitter * cellW * 0.2f
                            val jy = pt.second * cellH + noise.noise2D(
                                pt.first * 3f, globalCharIdx + ptIdx * 0.7f
                            ) * jitter * cellH * 0.2f

                            if (ptIdx == 0) path.moveTo(jx, jy)
                            else path.lineTo(jx, jy)
                        }
                        // Crayon: draw multiple slightly offset passes for texture
                        if (tool == "crayon") {
                            for (pass in 0..2) {
                                val ox = rng.range(-1.5f, 1.5f)
                                val oy = rng.range(-1.5f, 1.5f)
                                paint.alpha = rng.integer(150, 210)
                                canvas.save()
                                canvas.translate(ox, oy)
                                canvas.drawPath(path, paint)
                                canvas.restore()
                            }
                        } else {
                            canvas.drawPath(path, paint)
                        }
                    }

                    canvas.restore()
                    cursorX += cellW
                    globalCharIdx++
                }
                // Space between words
                cursorX += charWidth * 0.5f
                globalWordIdx++
            }
        }
    }

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float = 0.3f
}
