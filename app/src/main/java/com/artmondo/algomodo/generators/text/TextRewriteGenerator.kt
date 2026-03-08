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
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * L-System string rewriting generator.
 *
 * Applies iterative string rewriting rules to an axiom, then renders the
 * resulting string as characters in one of several layout modes.
 */
class TextRewriteGenerator : Generator {

    override val id = "text-rewrite"
    override val family = "text"
    override val styleName = "L-System Text Rewrite"
    override val definition =
        "Iterative string rewriting (L-system) with visual rendering of the resulting text."
    override val algorithmNotes =
        "Starting from an axiom string, production rules are applied iteratively. " +
        "Rules are specified as comma-separated 'LHS->RHS' pairs. After the specified " +
        "number of iterations, the result string is rendered as individual characters " +
        "in linear, spiral, or grid layout. The string is truncated to prevent excessive length."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema = listOf(
        Parameter.SelectParam("Preset", "preset", ParamGroup.COMPOSITION, "Predefined L-system rule set — fibonacci: A->AB,B->A | thue-morse: 0->01,1->10 | dragon: X->X+YF+,Y->-FX-Y | sierpinski: A->B-A-B,B->A+B+A | custom: use custom axiom & rules", listOf("fibonacci", "thue-morse", "dragon", "sierpinski", "custom"), "fibonacci"),
        Parameter.NumberParam("Iterations", "iterations", ParamGroup.COMPOSITION, "Number of rewriting passes", 1f, 10f, 1f, 5f),
        Parameter.NumberParam("Font Size", "fontSize", ParamGroup.GEOMETRY, "Character size in pixels", 4f, 32f, 1f, 12f),
        Parameter.SelectParam("Layout", "layout", ParamGroup.COMPOSITION, "Display layout", listOf("linear", "spiral", "grid", "tree"), "linear"),
        Parameter.SelectParam("Color Mode", "colorMode", ParamGroup.COLOR, "palette-char: color per unique character | palette-position: by position | monochrome: single ink", listOf("palette-char", "palette-position", "monochrome"), "palette-char"),
        Parameter.NumberParam("Speed", "speed", ParamGroup.FLOW_MOTION, "Animation speed", 0.1f, 3f, 0.1f, 0.5f),
        Parameter.TextParam("Custom Text", "customText", ParamGroup.COMPOSITION, "Custom axiom and rules (format: AXIOM;LHS->RHS,LHS->RHS) — only used when preset is 'custom'", "", placeholder = "e.g. A;A->AB,B->A", maxLength = 200)
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "preset" to "fibonacci",
        "iterations" to 5f,
        "fontSize" to 12f,
        "layout" to "linear",
        "colorMode" to "palette-char",
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
        val preset = (params["preset"] as? String) ?: "fibonacci"
        val iterations = (params["iterations"] as? Number)?.toInt() ?: 5
        val fontSize = (params["fontSize"] as? Number)?.toFloat() ?: 12f
        val layout = (params["layout"] as? String) ?: "linear"
        val customText = (params["customText"] as? String) ?: ""
        val speed = (params["speed"] as? Number)?.toFloat() ?: 0.5f
        val charOffset = if (speed > 0f && time > 0f) (time * speed * 5f).toInt() else 0

        val paletteColors = palette.colorInts()

        canvas.drawColor(Color.BLACK)

        // Determine axiom and rules from preset (or custom text)
        val axiomAndRules: Pair<String, String> = when (preset) {
            "fibonacci" -> "A" to "A->AB,B->A"
            "thue-morse" -> "0" to "0->01,1->10"
            "dragon" -> "X" to "X->X+YF+,Y->-FX-Y"
            "sierpinski" -> "A" to "A->B-A-B,B->A+B+A"
            "custom" -> {
                if (customText.contains(";")) {
                    val parts = customText.split(";", limit = 2)
                    parts[0].trim() to parts[1].trim()
                } else {
                    "A" to "A->AB,B->A"
                }
            }
            else -> "A" to "A->AB,B->A"
        }
        val axiom = axiomAndRules.first
        val rulesStr = axiomAndRules.second

        // Parse rules
        val rules = mutableMapOf<String, String>()
        for (rule in rulesStr.split(",")) {
            val parts = rule.trim().split("->")
            if (parts.size == 2) {
                rules[parts[0].trim()] = parts[1].trim()
            }
        }

        // Apply rewriting
        var result = axiom
        val maxLen = 50000
        for (iter in 0 until iterations) {
            val sb = StringBuilder()
            for (ch in result) {
                val replacement = rules[ch.toString()]
                sb.append(replacement ?: ch.toString())
                if (sb.length > maxLen) break
            }
            result = sb.toString()
            if (result.length > maxLen) {
                result = result.substring(0, maxLen)
                break
            }
        }

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = fontSize
            typeface = Typeface.MONOSPACE
            textAlign = Paint.Align.CENTER
        }

        when (layout) {
            "linear" -> drawLinear(canvas, result, paint, w, h, fontSize, paletteColors, charOffset)
            "spiral" -> drawSpiral(canvas, result, paint, w, h, fontSize, paletteColors, time * speed)
            "grid" -> drawGrid(canvas, result, paint, w, h, fontSize, paletteColors, charOffset)
        }
    }

    private fun drawLinear(canvas: Canvas, text: String, paint: Paint, w: Float, h: Float,
                           fontSize: Float, colors: List<Int>, charOffset: Int = 0) {
        val charWidth = fontSize * 0.7f
        val lineHeight = fontSize * 1.3f
        val charsPerLine = (w / charWidth).toInt().coerceAtLeast(1)
        var x = charWidth / 2f
        var y = lineHeight

        for ((i, ch) in text.withIndex()) {
            if (y > h) break
            val shiftedIdx = (i + charOffset) % text.length
            paint.color = colors[i % colors.size]
            paint.alpha = 200
            canvas.drawText(text[shiftedIdx].toString(), x, y, paint)

            x += charWidth
            if (x > w - charWidth / 2f) {
                x = charWidth / 2f
                y += lineHeight
            }
        }
    }

    private fun drawSpiral(canvas: Canvas, text: String, paint: Paint, w: Float, h: Float,
                           fontSize: Float, colors: List<Int>, angleOffset: Float = 0f) {
        val cx = w / 2f
        val cy = h / 2f
        val maxR = min(w, h) * 0.45f
        val charWidth = fontSize * 0.7f
        val totalChars = text.length.coerceAtMost((maxR * 2 * PI.toFloat() / charWidth * 10).toInt())

        for (i in 0 until totalChars) {
            val t = i.toFloat() / totalChars
            val angle = t * 8f * PI.toFloat() + angleOffset
            val r = maxR * t
            val px = cx + cos(angle) * r
            val py = cy + sin(angle) * r

            if (px < 0 || px > w || py < 0 || py > h) continue

            paint.color = colors[i % colors.size]
            paint.alpha = 200

            canvas.save()
            canvas.translate(px, py)
            canvas.rotate(angle * 180f / PI.toFloat() + 90f)
            canvas.drawText(text[i % text.length].toString(), 0f, 0f, paint)
            canvas.restore()
        }
    }

    private fun drawGrid(canvas: Canvas, text: String, paint: Paint, w: Float, h: Float,
                         fontSize: Float, colors: List<Int>, charOffset: Int = 0) {
        val cellSize = fontSize * 1.1f
        val cols = (w / cellSize).toInt()
        val rows = (h / cellSize).toInt()

        for (row in 0 until rows) {
            for (col in 0 until cols) {
                val idx = row * cols + col
                if (idx >= text.length) return

                val shiftedIdx = (idx + charOffset) % text.length
                val cx = col * cellSize + cellSize / 2f
                val cy = row * cellSize + cellSize / 2f + fontSize / 3f

                paint.color = colors[idx % colors.size]
                paint.alpha = 200
                canvas.drawText(text[shiftedIdx].toString(), cx, cy, paint)
            }
        }
    }

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float {
        val iterations = (params["iterations"] as? Number)?.toInt() ?: 5
        return (iterations / 8f).coerceIn(0.2f, 1f)
    }
}
