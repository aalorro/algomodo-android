package com.artmondo.algomodo.generators.text

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import com.artmondo.algomodo.core.rng.SeededRNG
import com.artmondo.algomodo.core.rng.SimplexNoise
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
 * Procedural cursive writing generator.
 *
 * Renders text in connected flowing cursive where characters within words are
 * linked by smooth Bezier curves. Pen pressure varies along the stroke path.
 */
class TextProceduralCursiveGenerator : Generator {

    override val id = "text-procedural-cursive"
    override val family = "text"
    override val styleName = "Procedural Cursive"
    override val definition =
        "Connected flowing cursive writing with smooth Bezier curves and variable pen pressure."
    override val algorithmNotes =
        "Each lowercase letter is defined as a series of Bezier control points. Characters within " +
        "a word are connected by linking exit and entry points with cubicTo curves. Baseline " +
        "wobble uses SimplexNoise, and stroke width varies along the path for pen-pressure effect."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema = listOf(
        Parameter.TextParam("Custom Text", "customText", ParamGroup.COMPOSITION, "Text to render — leave empty for lorem ipsum", "", placeholder = "Enter text (leave empty for random)", maxLength = 400),
        Parameter.SelectParam("Style", "style", ParamGroup.COMPOSITION, "Cursive style variation", listOf("flowing", "formal", "playful"), "flowing"),
        Parameter.NumberParam("Font Size", "fontSize", ParamGroup.GEOMETRY, "Base character height", 16f, 60f, 2f, 28f),
        Parameter.NumberParam("Line Spacing", "lineSpacing", ParamGroup.GEOMETRY, "Multiplier for vertical line gap", 1.0f, 3.0f, 0.1f, 1.8f),
        Parameter.NumberParam("Slant", "slant", ParamGroup.GEOMETRY, "Italic angle in degrees", -30f, 30f, 1f, 8f),
        Parameter.NumberParam("Wobble", "wobble", ParamGroup.TEXTURE, "Organic baseline wobble amount", 0f, 1f, 0.05f, 0.3f),
        Parameter.BooleanParam("Show Baseline", "showBaseline", ParamGroup.COMPOSITION, "Draw faint baseline guides", false),
        Parameter.SelectParam("Color Mode", "colorMode", ParamGroup.COLOR, "How colors are assigned", listOf("line-gradient", "word-color", "char-progress", "monochrome"), "line-gradient"),
        Parameter.SelectParam("Animation", "animMode", ParamGroup.FLOW_MOTION, "Animation style", listOf("none", "write", "fade-in", "wave"), "none"),
        Parameter.NumberParam("Speed", "speed", ParamGroup.FLOW_MOTION, "Animation speed", 0.05f, 1f, 0.05f, 0.3f)
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "customText" to "",
        "style" to "flowing",
        "fontSize" to 28f,
        "lineSpacing" to 1.8f,
        "slant" to 8f,
        "wobble" to 0.3f,
        "showBaseline" to false,
        "colorMode" to "line-gradient",
        "animMode" to "none",
        "speed" to 0.3f
    )

    // Each character: list of curves. Each curve = (x1,y1, cx,cy, x2,y2) where cx,cy is a quadratic control point
    // Coordinates in [0,1] space. Entry point = first curve start, exit = last curve end.
    // Characters designed to flow left-to-right with entry at left-center and exit at right-center.
    private data class CurveSegment(
        val x1: Float, val y1: Float,
        val cx: Float, val cy: Float,
        val x2: Float, val y2: Float
    )

    private val charCurves: Map<Char, List<CurveSegment>> = mapOf(
        'a' to listOf(CurveSegment(0.7f, 0.3f, 0.3f, 0.1f, 0.2f, 0.5f), CurveSegment(0.2f, 0.5f, 0.2f, 1.0f, 0.7f, 0.9f), CurveSegment(0.7f, 0.9f, 0.8f, 0.3f, 0.7f, 0.3f), CurveSegment(0.7f, 0.3f, 0.7f, 0.7f, 0.9f, 1.0f)),
        'b' to listOf(CurveSegment(0.1f, 0.0f, 0.1f, 0.5f, 0.1f, 1.0f), CurveSegment(0.1f, 0.5f, 0.5f, 0.3f, 0.8f, 0.5f), CurveSegment(0.8f, 0.5f, 0.9f, 0.9f, 0.5f, 1.0f), CurveSegment(0.5f, 1.0f, 0.1f, 1.0f, 0.9f, 1.0f)),
        'c' to listOf(CurveSegment(0.8f, 0.3f, 0.4f, 0.1f, 0.2f, 0.5f), CurveSegment(0.2f, 0.5f, 0.2f, 0.9f, 0.8f, 1.0f)),
        'd' to listOf(CurveSegment(0.7f, 0.3f, 0.3f, 0.1f, 0.2f, 0.5f), CurveSegment(0.2f, 0.5f, 0.2f, 1.0f, 0.7f, 0.9f), CurveSegment(0.7f, 0.0f, 0.7f, 0.5f, 0.7f, 0.9f), CurveSegment(0.7f, 0.9f, 0.8f, 1.0f, 0.9f, 1.0f)),
        'e' to listOf(CurveSegment(0.2f, 0.6f, 0.5f, 0.6f, 0.8f, 0.5f), CurveSegment(0.8f, 0.5f, 0.5f, 0.1f, 0.2f, 0.5f), CurveSegment(0.2f, 0.5f, 0.2f, 0.9f, 0.8f, 1.0f)),
        'f' to listOf(CurveSegment(0.7f, 0.1f, 0.5f, 0.0f, 0.3f, 0.2f), CurveSegment(0.3f, 0.2f, 0.3f, 0.6f, 0.3f, 1.0f), CurveSegment(0.1f, 0.5f, 0.3f, 0.5f, 0.6f, 0.5f)),
        'g' to listOf(CurveSegment(0.7f, 0.3f, 0.3f, 0.1f, 0.2f, 0.5f), CurveSegment(0.2f, 0.5f, 0.2f, 0.9f, 0.7f, 0.8f), CurveSegment(0.7f, 0.3f, 0.7f, 0.6f, 0.7f, 1.0f), CurveSegment(0.7f, 1.0f, 0.5f, 1.3f, 0.2f, 1.2f)),
        'h' to listOf(CurveSegment(0.1f, 0.0f, 0.1f, 0.5f, 0.1f, 1.0f), CurveSegment(0.1f, 0.5f, 0.4f, 0.3f, 0.7f, 0.4f), CurveSegment(0.7f, 0.4f, 0.8f, 0.6f, 0.8f, 1.0f)),
        'i' to listOf(CurveSegment(0.4f, 0.4f, 0.4f, 0.7f, 0.5f, 1.0f)),
        'j' to listOf(CurveSegment(0.5f, 0.4f, 0.5f, 0.8f, 0.5f, 1.1f), CurveSegment(0.5f, 1.1f, 0.4f, 1.3f, 0.2f, 1.2f)),
        'k' to listOf(CurveSegment(0.1f, 0.0f, 0.1f, 0.5f, 0.1f, 1.0f), CurveSegment(0.6f, 0.3f, 0.3f, 0.5f, 0.1f, 0.6f), CurveSegment(0.2f, 0.6f, 0.4f, 0.7f, 0.7f, 1.0f)),
        'l' to listOf(CurveSegment(0.4f, 0.0f, 0.4f, 0.5f, 0.4f, 0.9f), CurveSegment(0.4f, 0.9f, 0.5f, 1.0f, 0.6f, 1.0f)),
        'm' to listOf(CurveSegment(0.0f, 1.0f, 0.0f, 0.4f, 0.0f, 0.4f), CurveSegment(0.0f, 0.4f, 0.2f, 0.3f, 0.35f, 0.5f), CurveSegment(0.35f, 0.5f, 0.35f, 0.8f, 0.35f, 1.0f), CurveSegment(0.35f, 0.4f, 0.55f, 0.3f, 0.7f, 0.5f), CurveSegment(0.7f, 0.5f, 0.7f, 0.8f, 0.8f, 1.0f)),
        'n' to listOf(CurveSegment(0.1f, 1.0f, 0.1f, 0.6f, 0.1f, 0.4f), CurveSegment(0.1f, 0.4f, 0.4f, 0.3f, 0.7f, 0.5f), CurveSegment(0.7f, 0.5f, 0.7f, 0.8f, 0.8f, 1.0f)),
        'o' to listOf(CurveSegment(0.5f, 0.3f, 0.2f, 0.3f, 0.2f, 0.7f), CurveSegment(0.2f, 0.7f, 0.3f, 1.0f, 0.6f, 1.0f), CurveSegment(0.6f, 1.0f, 0.8f, 0.9f, 0.8f, 0.5f), CurveSegment(0.8f, 0.5f, 0.7f, 0.3f, 0.5f, 0.3f)),
        'p' to listOf(CurveSegment(0.1f, 0.4f, 0.1f, 0.8f, 0.1f, 1.3f), CurveSegment(0.1f, 0.5f, 0.4f, 0.3f, 0.7f, 0.5f), CurveSegment(0.7f, 0.5f, 0.8f, 0.8f, 0.5f, 1.0f), CurveSegment(0.5f, 1.0f, 0.2f, 1.0f, 0.1f, 0.9f)),
        'q' to listOf(CurveSegment(0.7f, 0.3f, 0.3f, 0.2f, 0.2f, 0.5f), CurveSegment(0.2f, 0.5f, 0.2f, 0.9f, 0.6f, 0.9f), CurveSegment(0.7f, 0.4f, 0.7f, 0.8f, 0.7f, 1.3f)),
        'r' to listOf(CurveSegment(0.1f, 1.0f, 0.1f, 0.6f, 0.1f, 0.4f), CurveSegment(0.1f, 0.4f, 0.4f, 0.3f, 0.7f, 0.4f)),
        's' to listOf(CurveSegment(0.7f, 0.35f, 0.5f, 0.25f, 0.2f, 0.4f), CurveSegment(0.2f, 0.4f, 0.3f, 0.6f, 0.7f, 0.7f), CurveSegment(0.7f, 0.7f, 0.8f, 0.85f, 0.3f, 1.0f)),
        't' to listOf(CurveSegment(0.3f, 0.1f, 0.3f, 0.5f, 0.4f, 1.0f), CurveSegment(0.1f, 0.4f, 0.3f, 0.4f, 0.6f, 0.4f)),
        'u' to listOf(CurveSegment(0.1f, 0.4f, 0.1f, 0.8f, 0.3f, 1.0f), CurveSegment(0.3f, 1.0f, 0.6f, 1.0f, 0.7f, 0.7f), CurveSegment(0.7f, 0.7f, 0.7f, 0.4f, 0.8f, 1.0f)),
        'v' to listOf(CurveSegment(0.1f, 0.4f, 0.3f, 0.9f, 0.45f, 1.0f), CurveSegment(0.45f, 1.0f, 0.6f, 0.8f, 0.8f, 0.4f)),
        'w' to listOf(CurveSegment(0.0f, 0.4f, 0.1f, 0.9f, 0.25f, 1.0f), CurveSegment(0.25f, 1.0f, 0.35f, 0.6f, 0.5f, 0.9f), CurveSegment(0.5f, 0.9f, 0.65f, 1.1f, 0.75f, 0.9f), CurveSegment(0.75f, 0.9f, 0.85f, 0.6f, 1.0f, 0.4f)),
        'x' to listOf(CurveSegment(0.1f, 0.4f, 0.4f, 0.6f, 0.8f, 1.0f), CurveSegment(0.8f, 0.4f, 0.5f, 0.6f, 0.1f, 1.0f)),
        'y' to listOf(CurveSegment(0.1f, 0.4f, 0.2f, 0.7f, 0.45f, 1.0f), CurveSegment(0.8f, 0.4f, 0.6f, 0.8f, 0.3f, 1.3f)),
        'z' to listOf(CurveSegment(0.1f, 0.4f, 0.5f, 0.4f, 0.8f, 0.4f), CurveSegment(0.8f, 0.4f, 0.3f, 0.7f, 0.1f, 1.0f), CurveSegment(0.1f, 1.0f, 0.5f, 1.0f, 0.8f, 1.0f))
    )

    private val loremIpsum = listOf(
        "the quick brown fox jumps over the lazy dog",
        "a gentle breeze drifts across the quiet meadow",
        "stars shimmer softly above the sleeping town",
        "rivers flow endlessly toward the distant sea",
        "music fills the air with invisible colors"
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
        val style = (params["style"] as? String) ?: "flowing"
        val fontSize = (params["fontSize"] as? Number)?.toFloat() ?: 28f
        val lineSpacing = (params["lineSpacing"] as? Number)?.toFloat() ?: 1.8f
        val slant = (params["slant"] as? Number)?.toFloat() ?: 8f
        val wobble = (params["wobble"] as? Number)?.toFloat() ?: 0.3f
        val showBaseline = (params["showBaseline"] as? Boolean) ?: false
        val colorMode = (params["colorMode"] as? String) ?: "line-gradient"
        val animMode = (params["animMode"] as? String) ?: "none"
        val speed = (params["speed"] as? Number)?.toFloat() ?: 0.3f

        val rng = SeededRNG(seed)
        val noise = SimplexNoise(seed)

        canvas.drawColor(Color.BLACK)

        // Build text
        val text = if (customText.isNotEmpty()) {
            customText.lowercase()
        } else {
            val sentenceCount = rng.integer(2, 4)
            (0 until sentenceCount).joinToString(" ") { rng.pick(loremIpsum) }
        }

        // Style parameters
        val charSpacing: Float
        val extraWobble: Float
        val sizeVar: Float
        when (style) {
            "formal" -> { charSpacing = 0.65f; extraWobble = 0f; sizeVar = 0f }
            "playful" -> { charSpacing = 0.85f; extraWobble = 0.5f; sizeVar = 0.2f }
            else -> { charSpacing = 0.75f; extraWobble = 0.2f; sizeVar = 0.1f } // flowing
        }

        val paint = Paint().apply {
            isAntiAlias = quality != Quality.DRAFT
            this.style = Paint.Style.STROKE
            strokeWidth = fontSize * 0.06f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        // Word-wrap into lines
        val margin = w * 0.08f
        val lineHeight = fontSize * lineSpacing
        val approxCharWidth = fontSize * charSpacing
        val maxCharsPerLine = ((w - margin * 2) / approxCharWidth).toInt().coerceAtLeast(1)

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

        // Draw baselines
        if (showBaseline) {
            val bPaint = Paint().apply {
                color = palette.lerpColor(0.5f)
                alpha = 30
                strokeWidth = 1f
                this.style = Paint.Style.STROKE
            }
            for (i in lines.indices) {
                val y = margin + fontSize + i * lineHeight
                canvas.drawLine(margin, y, w - margin, y, bPaint)
            }
        }

        // Count total words for animation
        val totalWords = words.size
        var globalCharIdx = 0
        var globalWordIdx = 0

        for ((lineIdx, line) in lines.withIndex()) {
            val baseY = margin + fontSize + lineIdx * lineHeight
            var cursorX = margin

            val lineWords = line.split(" ")
            for ((wordInLineIdx, word) in lineWords.withIndex()) {
                // Animation: fade-in is per word
                if (animMode == "fade-in") {
                    val revealWords = (time * speed * 8f).toInt()
                    if (globalWordIdx >= revealWords) return
                }

                // Color for this word/line
                val baseColor = when (colorMode) {
                    "line-gradient" -> palette.lerpColor(lineIdx.toFloat() / lines.size.coerceAtLeast(1))
                    "word-color" -> palette.lerpColor(globalWordIdx.toFloat() / totalWords.coerceAtLeast(1))
                    "char-progress" -> palette.lerpColor(globalCharIdx.toFloat() / text.length.coerceAtLeast(1))
                    "monochrome" -> palette.lerpColor(0.5f)
                    else -> palette.lerpColor(0.5f)
                }
                paint.color = baseColor

                // Build connected word path
                val wordPath = Path()
                var lastExitX = 0f
                var lastExitY = 0f
                var isFirstChar = true

                for ((charIdx, ch) in word.withIndex()) {
                    val curves = charCurves[ch] ?: continue
                    val sizeScale = 1f + rng.range(-sizeVar, sizeVar)
                    val cellW = fontSize * charSpacing * sizeScale
                    val cellH = fontSize * sizeScale

                    // Wobble
                    val wob = noise.noise2D(
                        globalCharIdx * 0.4f, lineIdx * 0.5f
                    ) * wobble * fontSize * 0.2f
                    val extraWob = noise.noise2D(
                        globalCharIdx * 0.7f + 20f, lineIdx.toFloat()
                    ) * extraWobble * fontSize * 0.15f

                    // Wave animation
                    val waveOffset = if (animMode == "wave") {
                        sin(time * speed * 4f + globalCharIdx * 0.5f) * fontSize * 0.15f
                    } else 0f

                    val charBaseX = cursorX
                    val charBaseY = baseY + wob + extraWob + waveOffset

                    // Slant transform
                    val slantRad = slant * PI.toFloat() / 180f

                    for ((segIdx, seg) in curves.withIndex()) {
                        val sx1 = charBaseX + seg.x1 * cellW + seg.y1 * cellH * sin(slantRad) * 0.3f
                        val sy1 = charBaseY + (seg.y1 - 0.4f) * cellH
                        val scx = charBaseX + seg.cx * cellW + seg.cy * cellH * sin(slantRad) * 0.3f
                        val scy = charBaseY + (seg.cy - 0.4f) * cellH
                        val sx2 = charBaseX + seg.x2 * cellW + seg.x2 * cellH * sin(slantRad) * 0.3f
                        val sy2 = charBaseY + (seg.y2 - 0.4f) * cellH

                        if (isFirstChar && segIdx == 0) {
                            wordPath.moveTo(sx1, sy1)
                            isFirstChar = false
                        } else if (segIdx == 0) {
                            // Connect from previous character's exit
                            wordPath.quadTo(
                                (lastExitX + sx1) / 2f, (lastExitY + sy1) / 2f - fontSize * 0.05f,
                                sx1, sy1
                            )
                        }

                        wordPath.quadTo(scx, scy, sx2, sy2)
                        lastExitX = sx2
                        lastExitY = sy2
                    }

                    cursorX += cellW
                    globalCharIdx++
                }

                // Draw the word path
                if (animMode == "write") {
                    // Progressive drawing using PathMeasure
                    val pm = PathMeasure(wordPath, false)
                    val totalLen = pm.length
                    val progress = ((time * speed * 200f - globalWordIdx * 80f) / totalLen).coerceIn(0f, 1f)
                    if (progress > 0f) {
                        val partialPath = Path()
                        pm.getSegment(0f, totalLen * progress, partialPath, true)
                        // Vary stroke width for pressure
                        paint.strokeWidth = fontSize * 0.06f
                        canvas.drawPath(partialPath, paint)
                        // Thicker on downstrokes (approximate by drawing again slightly thicker)
                        if (quality != Quality.DRAFT) {
                            paint.strokeWidth = fontSize * 0.03f
                            paint.alpha = 120
                            canvas.drawPath(partialPath, paint)
                            paint.alpha = 255
                        }
                    }
                } else {
                    // Pressure variation: draw at two widths for a thick-thin feel
                    paint.strokeWidth = fontSize * 0.06f
                    canvas.drawPath(wordPath, paint)
                    if (quality != Quality.DRAFT) {
                        paint.strokeWidth = fontSize * 0.09f
                        paint.alpha = 60
                        canvas.drawPath(wordPath, paint)
                        paint.alpha = 255
                    }
                }

                cursorX += fontSize * 0.4f // space between words
                globalWordIdx++
            }
        }
    }

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float = 0.35f
}
