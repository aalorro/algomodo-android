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
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Concrete poetry generator.
 *
 * Scatters individual characters from a user-supplied string across the canvas
 * in artistic arrangements: wave, spiral, or random scatter patterns.
 */
class TextConcreteGenerator : Generator {

    override val id = "text-concrete"
    override val family = "text"
    override val styleName = "Concrete Poetry"
    override val definition =
        "Characters from a text string scattered across the canvas in artistic patterns."
    override val algorithmNotes =
        "Each character of the input text is placed individually. In 'wave' mode, " +
        "characters follow a sinusoidal path. In 'spiral' mode, an Archimedean spiral " +
        "arranges them. In 'scatter' mode, characters are randomly placed with rotation. " +
        "Density controls how many total characters are drawn (repeating the text cyclically)."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema = listOf(
        Parameter.TextParam("Custom Text", "customText", ParamGroup.COMPOSITION, "Custom characters, words, or sentence — leave empty for random", "", placeholder = "Enter text (leave empty for random)", maxLength = 200),
        Parameter.SelectParam("Text Source", "textSource", ParamGroup.COMPOSITION, "Character set when custom text is empty", listOf("alphabet", "digits", "symbols", "words"), "alphabet"),
        Parameter.SelectParam("Path Type", "pathType", ParamGroup.COMPOSITION, "Geometric path for text placement", listOf("spiral", "circle", "wave", "diagonal", "radial"), "spiral"),
        Parameter.NumberParam("Font Size", "fontSize", ParamGroup.GEOMETRY, "Base font size in pixels", 8f, 72f, 2f, 24f),
        Parameter.NumberParam("Density", "density", ParamGroup.GEOMETRY, "How many characters to place along the paths", 0.2f, 2.0f, 0.1f, 1.0f),
        Parameter.NumberParam("Letter Spacing", "letterSpacing", ParamGroup.GEOMETRY, "Spacing multiplier between characters", 0.5f, 3.0f, 0.1f, 1.2f),
        Parameter.NumberParam("Speed", "speed", ParamGroup.FLOW_MOTION, "Animation scroll speed", 0.1f, 3.0f, 0.1f, 0.5f)
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "customText" to "",
        "textSource" to "alphabet",
        "pathType" to "spiral",
        "fontSize" to 24f,
        "density" to 1.0f,
        "letterSpacing" to 1.2f,
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
        val text = (params["customText"] as? String)?.ifEmpty { "ALGOMODO" } ?: "ALGOMODO"
        val fontSize = (params["fontSize"] as? Number)?.toFloat() ?: 24f
        val density = (params["density"] as? Number)?.toFloat() ?: 1.0f
        val arrangement = (params["pathType"] as? String) ?: "spiral"
        val speed = (params["speed"] as? Number)?.toFloat() ?: 0.5f
        val timeOff = time * speed

        if (text.isEmpty()) return

        val rng = SeededRNG(seed)
        val paletteColors = palette.colorInts()

        canvas.drawColor(Color.BLACK)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = fontSize
            typeface = Typeface.MONOSPACE
            textAlign = Paint.Align.CENTER
        }

        val charCount = (w * h * density / (fontSize * fontSize * 1.5f)).toInt().coerceIn(10, 10000)

        when (arrangement) {
            "wave" -> {
                val rows = (h / (fontSize * 1.2f)).toInt()
                val charsPerRow = (w / (fontSize * 0.8f)).toInt()
                var idx = 0

                for (row in 0 until rows) {
                    for (col in 0 until charsPerRow) {
                        if (idx >= charCount) break
                        val charIdx = idx % text.length
                        val ch = text[charIdx].toString()

                        val baseX = col * fontSize * 0.8f + fontSize / 2f
                        val baseY = row * fontSize * 1.2f + fontSize
                        val waveOffset = sin(col * 0.3f + row * 0.5f + timeOff) * fontSize * 2f

                        paint.color = paletteColors[idx % paletteColors.size]
                        paint.alpha = 180 + rng.integer(0, 75)

                        canvas.save()
                        canvas.translate(baseX, baseY + waveOffset)
                        canvas.rotate(sin(col * 0.2f + timeOff) * 15f)
                        canvas.drawText(ch, 0f, 0f, paint)
                        canvas.restore()
                        idx++
                    }
                }
            }
            "spiral" -> {
                val cx = w / 2f
                val cy = h / 2f
                val maxR = kotlin.math.min(w, h) * 0.45f

                for (i in 0 until charCount) {
                    val t = i.toFloat() / charCount
                    val angle = t * 10f * PI.toFloat() + timeOff
                    val r = maxR * t
                    val px = cx + cos(angle) * r
                    val py = cy + sin(angle) * r

                    if (px < 0 || px > w || py < 0 || py > h) continue

                    val charIdx = i % text.length
                    val ch = text[charIdx].toString()

                    paint.color = paletteColors[i % paletteColors.size]
                    paint.alpha = 200

                    canvas.save()
                    canvas.translate(px, py)
                    canvas.rotate(angle * 180f / PI.toFloat() + 90f)
                    canvas.drawText(ch, 0f, 0f, paint)
                    canvas.restore()
                }
            }
            "scatter" -> {
                for (i in 0 until charCount) {
                    val px = rng.range(fontSize, w - fontSize) + sin(i.toFloat() * 0.1f + timeOff) * fontSize
                    val py = rng.range(fontSize, h - fontSize) + cos(i.toFloat() * 0.1f + timeOff) * fontSize
                    val rotation = rng.range(-45f, 45f) + timeOff * 10f
                    val charIdx = i % text.length
                    val ch = text[charIdx].toString()

                    paint.color = paletteColors[i % paletteColors.size]
                    paint.alpha = 150 + rng.integer(0, 105)
                    paint.textSize = fontSize * rng.range(0.5f, 1.5f)

                    canvas.save()
                    canvas.translate(px, py)
                    canvas.rotate(rotation)
                    canvas.drawText(ch, 0f, 0f, paint)
                    canvas.restore()
                }
                paint.textSize = fontSize // restore
            }
        }
    }

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float = 0.3f
}
