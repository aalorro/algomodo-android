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
import kotlin.math.floor

/**
 * Digital rain (Matrix effect) generator.
 *
 * Simulates the falling-character effect from The Matrix. Columns of
 * characters scroll downward at varying speeds with brightness falloff.
 */
class TextMatrixGenerator : Generator {

    override val id = "text-matrix"
    override val family = "text"
    override val styleName = "Matrix Rain"
    override val definition =
        "Falling columns of characters inspired by the digital rain from The Matrix."
    override val algorithmNotes =
        "Each column is assigned a random starting offset, speed, and trail length. " +
        "Characters are drawn from the head of each column downward with exponentially " +
        "decaying brightness to create a glowing trail. The time parameter drives the " +
        "vertical scroll offset for animation."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema = listOf(
        Parameter.TextParam("Custom Text", "customText", ParamGroup.COMPOSITION, "Custom characters for the rain — leave empty for random", "", placeholder = "Enter text (leave empty for random)", maxLength = 200),
        Parameter.SelectParam("Char Set", "charSet", ParamGroup.COMPOSITION, "Character set when custom text is empty", listOf("katakana", "digits", "latin", "mixed", "binary"), "katakana"),
        Parameter.NumberParam("Columns", "columns", ParamGroup.GEOMETRY, "Number of character columns", 10f, 120f, 1f, 40f),
        Parameter.NumberParam("Drop Speed", "dropSpeed", ParamGroup.FLOW_MOTION, "Vertical fall speed of each column", 1f, 20f, 0.5f, 5f),
        Parameter.NumberParam("Trail Length", "trailLength", ParamGroup.GEOMETRY, "Number of characters in each falling trail", 4f, 40f, 1f, 15f),
        Parameter.NumberParam("Brightness", "brightness", ParamGroup.COLOR, "Base brightness of characters", 0.3f, 1f, 0.05f, 0.8f),
        Parameter.NumberParam("Speed", "speed", ParamGroup.FLOW_MOTION, "Animation speed multiplier", 0.1f, 3f, 0.1f, 1f)
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "customText" to "",
        "charSet" to "katakana",
        "columns" to 40f,
        "dropSpeed" to 5f,
        "trailLength" to 15f,
        "brightness" to 0.8f,
        "speed" to 1f
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
        val columns = (params["columns"] as? Number)?.toInt() ?: 40
        val speed = (params["speed"] as? Number)?.toFloat() ?: 5f
        val charSetName = (params["charSet"] as? String) ?: "katakana"
        val brightness = (params["brightness"] as? Number)?.toFloat() ?: 0.8f

        val rng = SeededRNG(seed)
        val paletteColors = palette.colorInts()

        canvas.drawColor(Color.BLACK)

        val charPool = when (charSetName) {
            "katakana" -> (0x30A0..0x30FF).map { it.toChar().toString() }
            "digits" -> (0..9).map { it.toString() }
            "latin" -> ('A'..'Z').map { it.toString() } + ('a'..'z').map { it.toString() }
            "mixed" -> (0x30A0..0x30CF).map { it.toChar().toString() } +
                    ('A'..'Z').map { it.toString() } + (0..9).map { it.toString() }
            else -> (0x30A0..0x30FF).map { it.toChar().toString() }
        }

        val colWidth = w / columns
        val fontSize = colWidth * 0.9f
        val rowHeight = fontSize * 1.2f
        val rows = (h / rowHeight).toInt() + 2
        val trailLength = rows / 2

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.MONOSPACE
            textSize = fontSize
            textAlign = Paint.Align.CENTER
        }

        // Each column has a random offset and speed variation
        for (col in 0 until columns) {
            val colX = col * colWidth + colWidth / 2f
            val colOffset = rng.range(0f, rows.toFloat())
            val colSpeed = speed * rng.range(0.5f, 1.5f)
            val colColor = paletteColors[col % paletteColors.size]

            // Head position
            val headRow = (colOffset + time * colSpeed) % (rows + trailLength)

            // Random character assignment per cell (seeded per column)
            val colRng = SeededRNG(seed + col * 137)

            for (row in 0 until rows) {
                val y = row * rowHeight + rowHeight

                val distFromHead = headRow - row
                if (distFromHead < 0 || distFromHead > trailLength) continue

                val fadeFactor = 1f - (distFromHead / trailLength)
                val alpha = (fadeFactor * fadeFactor * brightness * 255).toInt().coerceIn(0, 255)
                if (alpha < 5) continue

                // Head character is brighter / white
                if (distFromHead < 1f) {
                    paint.color = Color.WHITE
                    paint.alpha = (brightness * 255).toInt()
                } else {
                    paint.color = colColor
                    paint.alpha = alpha
                }

                val ch = charPool[colRng.integer(0, charPool.size - 1)]
                canvas.drawText(ch, colX, y, paint)
            }
        }
    }

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float {
        val columns = (params["columns"] as? Number)?.toInt() ?: 40
        return (columns / 80f).coerceIn(0.2f, 0.7f)
    }
}
