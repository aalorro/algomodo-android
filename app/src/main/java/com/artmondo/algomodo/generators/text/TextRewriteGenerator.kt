package com.artmondo.algomodo.generators.text

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import com.artmondo.algomodo.core.rng.SeededRNG
import com.artmondo.algomodo.core.rng.SimplexNoise
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
        "in linear, spiral, grid, or tree layout. The string is truncated to prevent excessive length."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema = listOf(
        Parameter.SelectParam("Preset", "preset", ParamGroup.COMPOSITION, "Predefined L-system rule set — fibonacci: A->AB,B->A | thue-morse: 0->01,1->10 | dragon: X->X+YF+,Y->-FX-Y | sierpinski: A->B-A-B,B->A+B+A | custom: use custom axiom & rules", listOf("fibonacci", "thue-morse", "dragon", "sierpinski", "custom"), "fibonacci"),
        Parameter.NumberParam("Iterations", "iterations", ParamGroup.COMPOSITION, "Number of rewriting passes", 1f, 100f, 1f, 5f),
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
        val colorMode = (params["colorMode"] as? String) ?: "palette-char"
        val customText = (params["customText"] as? String) ?: ""
        val speed = (params["speed"] as? Number)?.toFloat() ?: 0.5f
        val timeOff = time * speed

        canvas.drawColor(Color.BLACK)

        // Determine axiom and rules
        val axiomAndRules: Pair<String, String> = when (preset) {
            "fibonacci" -> "A" to "A->AB,B->A"
            "thue-morse" -> "0" to "0->01,1->10"
            "dragon" -> "X" to "X->X+YF+,Y->-FX-Y"
            "sierpinski" -> "A" to "A->B-A-B,B->A+B+A"
            "custom" -> {
                if (customText.contains(";")) {
                    val parts = customText.split(";", limit = 2)
                    val axiomPart = parts[0].trim()
                    val rulesPart = parts[1].trim()
                    (axiomPart.ifEmpty { "A" }) to (rulesPart.ifEmpty { "A->A" })
                } else if (customText.isNotEmpty()) {
                    // No semicolon — use entire text as axiom with no rewriting rules
                    customText to ""
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

        // Apply rewriting with progressive reveal animation
        val maxLen = 50000
        var result = axiom
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

        // Progressive reveal: grow the string over time
        val revealLen = if (timeOff > 0f) {
            ((timeOff * result.length * 0.3f).toInt()).coerceIn(1, result.length)
        } else {
            result.length
        }
        val displayResult = result.substring(0, revealLen)

        // Build unique character -> color map for palette-char mode
        val uniqueChars = displayResult.toSet().toList().sorted()
        val charColorMap = uniqueChars.mapIndexed { idx, ch ->
            ch to palette.lerpColor(idx.toFloat() / uniqueChars.size.coerceAtLeast(1))
        }.toMap()

        val paint = Paint().apply {
            isAntiAlias = quality != Quality.DRAFT
            textSize = fontSize
            typeface = Typeface.MONOSPACE
            textAlign = Paint.Align.CENTER
        }

        val noise = SimplexNoise(seed)

        when (layout) {
            "linear" -> drawLinear(canvas, displayResult, paint, w, h, fontSize, palette, colorMode, charColorMap, noise)
            "spiral" -> drawSpiral(canvas, displayResult, paint, w, h, fontSize, palette, colorMode, charColorMap, timeOff)
            "grid" -> drawGrid(canvas, displayResult, paint, w, h, fontSize, palette, colorMode, charColorMap, noise, timeOff)
            "tree" -> drawTree(canvas, displayResult, paint, w, h, fontSize, palette, colorMode, charColorMap, quality, timeOff)
        }
    }

    private fun getColor(
        ch: Char, index: Int, total: Int,
        colorMode: String, palette: Palette,
        charColorMap: Map<Char, Int>
    ): Int {
        return when (colorMode) {
            "palette-char" -> charColorMap[ch] ?: palette.colorAt(0)
            "palette-position" -> palette.lerpColor(index.toFloat() / total.coerceAtLeast(1))
            "monochrome" -> palette.colorAt(0)
            else -> charColorMap[ch] ?: palette.colorAt(0)
        }
    }

    private fun drawLinear(
        canvas: Canvas, text: String, paint: Paint, w: Float, h: Float,
        fontSize: Float, palette: Palette, colorMode: String,
        charColorMap: Map<Char, Int>, noise: SimplexNoise
    ) {
        val charWidth = fontSize * 0.7f
        val lineHeight = fontSize * 1.3f
        var x = charWidth / 2f
        var y = lineHeight
        val totalLines = (h / lineHeight).toInt()

        for ((i, ch) in text.withIndex()) {
            if (y > h) break

            paint.color = getColor(ch, i, text.length, colorMode, palette, charColorMap)

            // Edge-fade: top and bottom rows fade out
            val rowFromTop = (y / lineHeight).toInt()
            val rowFromBottom = totalLines - rowFromTop
            val edgeFade = min(rowFromTop, rowFromBottom).toFloat().coerceIn(0f, 3f) / 3f
            paint.alpha = (200 * edgeFade).toInt().coerceIn(20, 200)

            canvas.drawText(ch.toString(), x, y, paint)

            x += charWidth
            if (x > w - charWidth / 2f) {
                x = charWidth / 2f
                y += lineHeight
            }
        }
    }

    private fun drawSpiral(
        canvas: Canvas, text: String, paint: Paint, w: Float, h: Float,
        fontSize: Float, palette: Palette, colorMode: String,
        charColorMap: Map<Char, Int>, angleOffset: Float
    ) {
        val cx = w / 2f
        val cy = h / 2f
        val maxR = min(w, h) * 0.45f
        val charWidth = fontSize * 0.7f
        val totalChars = text.length.coerceAtMost((maxR * 2 * PI.toFloat() / charWidth * 10).toInt())

        for (i in 0 until totalChars) {
            if (i >= text.length) break
            val t = i.toFloat() / totalChars
            val angle = t * 8f * PI.toFloat() + angleOffset
            val r = maxR * t
            val px = cx + cos(angle) * r
            val py = cy + sin(angle) * r

            if (px < 0 || px > w || py < 0 || py > h) continue

            paint.color = getColor(text[i], i, text.length, colorMode, palette, charColorMap)
            paint.alpha = 200

            canvas.save()
            canvas.translate(px, py)
            canvas.rotate(angle * 180f / PI.toFloat() + 90f)
            canvas.drawText(text[i].toString(), 0f, 0f, paint)
            canvas.restore()
        }
    }

    private fun drawGrid(
        canvas: Canvas, text: String, paint: Paint, w: Float, h: Float,
        fontSize: Float, palette: Palette, colorMode: String,
        charColorMap: Map<Char, Int>, noise: SimplexNoise, timeOff: Float
    ) {
        val cellSize = fontSize * 1.1f
        val cols = (w / cellSize).toInt()
        val rows = (h / cellSize).toInt()

        for (row in 0 until rows) {
            for (col in 0 until cols) {
                val idx = row * cols + col
                if (idx >= text.length) return

                val cx = col * cellSize + cellSize / 2f
                val cy = row * cellSize + cellSize / 2f + fontSize / 3f
                val ch = text[idx]

                paint.color = getColor(ch, idx, text.length, colorMode, palette, charColorMap)

                // Noise-based size variation
                val nx = col.toFloat() / cols.coerceAtLeast(1)
                val ny = row.toFloat() / rows.coerceAtLeast(1)
                val nv = noise.noise2D(nx * 3f + timeOff * 0.2f, ny * 3f)
                paint.textSize = fontSize * (1f + nv * 0.25f).coerceIn(0.5f, 1.5f)
                paint.alpha = (180 + nv * 70f).toInt().coerceIn(80, 255)

                canvas.drawText(ch.toString(), cx, cy, paint)
            }
        }
        paint.textSize = fontSize // restore
    }

    private fun drawTree(
        canvas: Canvas, text: String, paint: Paint, w: Float, h: Float,
        fontSize: Float, palette: Palette, colorMode: String,
        charColorMap: Map<Char, Int>, quality: Quality, timeOff: Float
    ) {
        // Turtle-graphics interpretation:
        // + = turn right by angle
        // - = turn left by angle
        // [ = push state (branch)
        // ] = pop state (return from branch)
        // F or any other letter = draw character and advance
        val turnAngle = 25f * PI.toFloat() / 180f
        val stepSize = fontSize * 1.2f

        // Balance brackets when truncating
        val balanced = balanceBrackets(text)

        // First pass: compute bounding box
        var turtleX = 0f
        var turtleY = 0f
        var turtleAngle = -PI.toFloat() / 2f // Start pointing up
        var minX = 0f; var maxX = 0f; var minY = 0f; var maxY = 0f
        val stateStack = ArrayDeque<FloatArray>()
        var depth = 0
        var maxDepth = 0

        for (ch in balanced) {
            when (ch) {
                '+' -> turtleAngle += turnAngle
                '-' -> turtleAngle -= turnAngle
                '[' -> {
                    stateStack.addLast(floatArrayOf(turtleX, turtleY, turtleAngle, depth.toFloat()))
                    depth++
                    if (depth > maxDepth) maxDepth = depth
                }
                ']' -> {
                    if (stateStack.isNotEmpty()) {
                        val state = stateStack.removeLast()
                        turtleX = state[0]; turtleY = state[1]; turtleAngle = state[2]; depth = state[3].toInt()
                    }
                }
                else -> {
                    turtleX += cos(turtleAngle) * stepSize
                    turtleY += sin(turtleAngle) * stepSize
                    if (turtleX < minX) minX = turtleX
                    if (turtleX > maxX) maxX = turtleX
                    if (turtleY < minY) minY = turtleY
                    if (turtleY > maxY) maxY = turtleY
                }
            }
        }

        // Scale and translate to fit canvas
        val treeW = (maxX - minX).coerceAtLeast(1f)
        val treeH = (maxY - minY).coerceAtLeast(1f)
        val margin = fontSize * 2f
        val scaleX = (w - margin * 2f) / treeW
        val scaleY = (h - margin * 2f) / treeH
        val scale = min(scaleX, scaleY).coerceAtMost(3f)
        val offsetX = margin + (w - margin * 2f - treeW * scale) / 2f - minX * scale
        val offsetY = margin + (h - margin * 2f - treeH * scale) / 2f - minY * scale

        // Second pass: draw
        turtleX = 0f; turtleY = 0f; turtleAngle = -PI.toFloat() / 2f
        stateStack.clear()
        depth = 0
        val maxDepthF = maxDepth.toFloat().coerceAtLeast(1f)
        var charIdx = 0

        val linePaint = Paint().apply {
            isAntiAlias = quality != Quality.DRAFT
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }

        for (ch in balanced) {
            when (ch) {
                '+' -> turtleAngle += turnAngle
                '-' -> turtleAngle -= turnAngle
                '[' -> {
                    stateStack.addLast(floatArrayOf(turtleX, turtleY, turtleAngle, depth.toFloat()))
                    depth++
                }
                ']' -> {
                    if (stateStack.isNotEmpty()) {
                        val state = stateStack.removeLast()
                        turtleX = state[0]; turtleY = state[1]; turtleAngle = state[2]; depth = state[3].toInt()
                    }
                }
                else -> {
                    val prevX = turtleX
                    val prevY = turtleY
                    turtleX += cos(turtleAngle) * stepSize
                    turtleY += sin(turtleAngle) * stepSize

                    val screenX1 = prevX * scale + offsetX
                    val screenY1 = prevY * scale + offsetY
                    val screenX2 = turtleX * scale + offsetX
                    val screenY2 = turtleY * scale + offsetY

                    val depthT = depth.toFloat() / maxDepthF

                    // Depth-based alpha and size tapering
                    val depthAlpha = (255 * (1f - depthT * 0.6f)).toInt().coerceIn(40, 255)
                    val depthSize = fontSize * scale * (1f - depthT * 0.5f).coerceIn(0.3f, 1f)

                    // Draw connecting line segment
                    linePaint.color = getColor(ch, charIdx, balanced.length, colorMode, palette, charColorMap)
                    linePaint.alpha = (depthAlpha * 0.4f).toInt()
                    linePaint.strokeWidth = (depthSize * 0.15f).coerceIn(0.5f, 3f)
                    canvas.drawLine(screenX1, screenY1, screenX2, screenY2, linePaint)

                    // Draw character at new position
                    paint.textSize = depthSize.coerceIn(4f, fontSize * 3f)
                    paint.color = getColor(ch, charIdx, balanced.length, colorMode, palette, charColorMap)
                    paint.alpha = depthAlpha

                    canvas.save()
                    canvas.translate(screenX2, screenY2)
                    canvas.rotate(turtleAngle * 180f / PI.toFloat() + 90f)
                    canvas.drawText(ch.toString(), 0f, 0f, paint)
                    canvas.restore()

                    charIdx++
                }
            }
        }
    }

    /** Balance brackets in a truncated L-system string to prevent broken tree structures */
    private fun balanceBrackets(text: String): String {
        var openCount = 0
        val sb = StringBuilder()
        for (ch in text) {
            when (ch) {
                '[' -> { openCount++; sb.append(ch) }
                ']' -> {
                    if (openCount > 0) { openCount--; sb.append(ch) }
                    // else skip unmatched closing bracket
                }
                else -> sb.append(ch)
            }
        }
        // Close any remaining open brackets
        repeat(openCount) { sb.append(']') }
        return sb.toString()
    }

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float {
        val iterations = (params["iterations"] as? Number)?.toInt() ?: 5
        return (iterations / 8f).coerceIn(0.2f, 1f)
    }
}
