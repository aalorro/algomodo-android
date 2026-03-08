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
 * Typographic grid generator.
 *
 * Fills the canvas with a grid of characters drawn from a user-supplied
 * string. Each cell contains a single character, optionally rotated or
 * using variable sizing for visual interest.
 */
class TextGridGenerator : Generator {

    override val id = "text-grid"
    override val family = "text"
    override val styleName = "Typographic Grid"
    override val definition =
        "Grid of characters where each cell draws from the supplied text string."
    override val algorithmNotes =
        "The canvas is divided into a regular grid of cellSize x cellSize cells. " +
        "Each cell gets a character from the text string (cycling). In 'monospace' mode, " +
        "characters are centred uniformly. In 'variable' mode, font size varies per cell. " +
        "In 'rotated' mode, each character is randomly rotated."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema = listOf(
        Parameter.TextParam("Custom Text", "customText", ParamGroup.COMPOSITION, "Custom characters or words — leave empty for random", "", placeholder = "Enter text (leave empty for random)", maxLength = 200),
        Parameter.SelectParam("Char Set", "charSet", ParamGroup.COMPOSITION, "Character set when custom text is empty", listOf("alphabet", "digits", "katakana", "symbols", "braille"), "digits"),
        Parameter.NumberParam("Grid Size", "gridSize", ParamGroup.GEOMETRY, "Cell size in pixels", 8f, 48f, 2f, 20f),
        Parameter.NumberParam("Noise Scale", "noiseScale", ParamGroup.TEXTURE, "Spatial frequency of the noise field driving character selection", 0.5f, 8f, 0.25f, 3f),
        Parameter.NumberParam("Size Variation", "sizeVariation", ParamGroup.TEXTURE, "Random variation in character size — 0 = uniform, 1 = very varied", 0f, 1f, 0.1f, 0f),
        Parameter.SelectParam("Color Mode", "colorMode", ParamGroup.COLOR, "palette-position: color by grid XY | palette-noise: color by noise field | monochrome: single ink", listOf("palette-position", "palette-noise", "monochrome"), "palette-position"),
        Parameter.NumberParam("Speed", "speed", ParamGroup.FLOW_MOTION, "Animation scroll speed", 0.1f, 3f, 0.1f, 0.5f)
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "customText" to "",
        "charSet" to "digits",
        "gridSize" to 20f,
        "noiseScale" to 3f,
        "sizeVariation" to 0f,
        "colorMode" to "palette-position",
        "speed" to 0.5f
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
        val text = (params["customText"] as? String)?.ifEmpty { "0123456789" } ?: "0123456789"
        val cellSize = (params["gridSize"] as? Number)?.toFloat() ?: 20f
        val style = "monospace"
        val speed = (params["speed"] as? Number)?.toFloat() ?: 0.5f
        val charShift = if (speed > 0f && time > 0f) (time * speed * 3f).toInt() else 0

        if (text.isEmpty()) return

        val rng = SeededRNG(seed)
        val paletteColors = palette.colorInts()

        canvas.drawColor(Color.BLACK)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.MONOSPACE
            textAlign = Paint.Align.CENTER
        }

        val cols = (w / cellSize).toInt()
        val rows = (h / cellSize).toInt()
        var idx = 0

        for (row in 0 until rows) {
            for (col in 0 until cols) {
                val charIdx = (idx + charShift) % text.length
                val ch = text[charIdx].toString()

                val cx = col * cellSize + cellSize / 2f
                val cy = row * cellSize + cellSize / 2f

                paint.color = paletteColors[idx % paletteColors.size]

                when (style) {
                    "variable" -> {
                        val sizeFactor = rng.range(0.5f, 1.5f)
                        paint.textSize = cellSize * 0.8f * sizeFactor
                        paint.alpha = (150 + sizeFactor * 70).toInt().coerceAtMost(255)
                        canvas.drawText(ch, cx, cy + paint.textSize / 3f, paint)
                    }
                    "rotated" -> {
                        paint.textSize = cellSize * 0.8f
                        paint.alpha = 220
                        val rotation = rng.range(-90f, 90f)
                        canvas.save()
                        canvas.translate(cx, cy)
                        canvas.rotate(rotation)
                        canvas.drawText(ch, 0f, paint.textSize / 3f, paint)
                        canvas.restore()
                    }
                    else -> { // monospace
                        paint.textSize = cellSize * 0.8f
                        paint.alpha = 220
                        canvas.drawText(ch, cx, cy + paint.textSize / 3f, paint)
                    }
                }
                idx++
            }
        }
    }

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float {
        val cellSize = (params["gridSize"] as? Number)?.toFloat() ?: 20f
        return (20f / cellSize).coerceIn(0.1f, 0.6f)
    }
}
