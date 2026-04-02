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
import kotlin.math.sin

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
        val speed = (params["speed"] as? Number)?.toFloat() ?: 1f
        val dropSpeed = (params["dropSpeed"] as? Number)?.toFloat() ?: 5f
        val trailLength = (params["trailLength"] as? Number)?.toInt() ?: 15
        val charSetName = (params["charSet"] as? String) ?: "katakana"
        val brightness = (params["brightness"] as? Number)?.toFloat() ?: 0.8f
        val customText = (params["customText"] as? String) ?: ""

        val rng = SeededRNG(seed)

        canvas.drawColor(Color.BLACK)

        // Build character pool: custom text takes priority
        val charPool = if (customText.isNotEmpty()) {
            customText.toList().map { it.toString() }
        } else {
            when (charSetName) {
                "katakana" -> (0x30A0..0x30FF).map { it.toChar().toString() }
                "digits" -> (0..9).map { it.toString() }
                "latin" -> ('A'..'Z').map { it.toString() } + ('a'..'z').map { it.toString() }
                "mixed" -> (0x30A0..0x30CF).map { it.toChar().toString() } +
                        ('A'..'Z').map { it.toString() } + (0..9).map { it.toString() }
                "binary" -> listOf("0", "1")
                else -> (0x30A0..0x30FF).map { it.toChar().toString() }
            }
        }

        val colWidth = w / columns
        val fontSize = colWidth * 0.9f
        val rowHeight = fontSize * 1.2f
        val rows = (h / rowHeight).toInt() + 2
        val isHighQuality = quality != Quality.DRAFT

        val paint = Paint().apply {
            isAntiAlias = isHighQuality
            typeface = Typeface.MONOSPACE
            textSize = fontSize
            textAlign = Paint.Align.CENTER
        }

        // Glow paint for bloom effect
        val glowPaint = Paint().apply {
            isAntiAlias = isHighQuality
            typeface = Typeface.MONOSPACE
            textSize = fontSize * 1.4f
            textAlign = Paint.Align.CENTER
        }

        for (col in 0 until columns) {
            val colX = col * colWidth + colWidth / 2f
            val colOffset = rng.range(0f, rows.toFloat())
            // dropSpeed controls per-column fall speed, speed is global multiplier
            val colSpeedVariation = rng.range(0.6f, 1.4f)
            val colFallSpeed = dropSpeed * colSpeedVariation * speed

            // Smooth column coloring via lerpColor
            val colT = col.toFloat() / columns.coerceAtLeast(1)
            val colColor = palette.lerpColor(colT)

            // Subtle column sway
            val swayAmount = colWidth * 0.15f
            val swayX = sin(time * speed * 0.5f + col * 0.7f) * swayAmount

            // Head position
            val headRow = (colOffset + time * colFallSpeed) % (rows + trailLength)

            // Seeded RNG per column for character selection
            val colRng = SeededRNG(seed + col * 137)

            for (row in 0 until rows) {
                val y = row * rowHeight + rowHeight

                val distFromHead = headRow - row
                if (distFromHead < 0 || distFromHead > trailLength) continue

                val fadeFactor = 1f - (distFromHead / trailLength)
                val alpha = (fadeFactor * fadeFactor * brightness * 255).toInt().coerceIn(0, 255)
                if (alpha < 5) continue

                // Character flicker: time-based deterministic character changes
                val flickerHash = ((row * 31 + col * 17 + (time * 4f).toInt() * 7) and 0x7FFFFFFF)
                val charIdx = if (distFromHead < 3f) {
                    // Near-head characters flicker more
                    flickerHash % charPool.size
                } else {
                    colRng.integer(0, charPool.size - 1)
                }
                val ch = charPool[charIdx % charPool.size]

                val drawX = colX + swayX

                if (distFromHead < 1f) {
                    // Head character: extra bright with glow/bloom
                    if (isHighQuality) {
                        // Glow pass — larger, faint character behind
                        glowPaint.color = colColor
                        glowPaint.alpha = (brightness * 80).toInt()
                        canvas.drawText(ch, drawX, y, glowPaint)
                    }
                    paint.color = Color.WHITE
                    paint.alpha = (brightness * 255).toInt()
                    canvas.drawText(ch, drawX, y, paint)
                } else if (distFromHead < 3f) {
                    // Near-head: glow effect for the brightest trail characters
                    if (isHighQuality) {
                        glowPaint.color = colColor
                        glowPaint.alpha = (fadeFactor * brightness * 40).toInt().coerceIn(0, 80)
                        canvas.drawText(ch, drawX, y, glowPaint)
                    }
                    paint.color = colColor
                    paint.alpha = alpha
                    canvas.drawText(ch, drawX, y, paint)
                } else {
                    // Trail body
                    paint.color = colColor
                    paint.alpha = alpha
                    canvas.drawText(ch, drawX, y, paint)
                }
            }
        }
    }

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float {
        val columns = (params["columns"] as? Number)?.toInt() ?: 40
        return (columns / 80f).coerceIn(0.2f, 0.7f)
    }
}
