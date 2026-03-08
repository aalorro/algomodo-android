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
import kotlin.math.sqrt

/**
 * Concrete poetry generator.
 *
 * Scatters individual characters from a user-supplied string across the canvas
 * in artistic arrangements: wave, spiral, circle, diagonal, or radial patterns.
 */
class TextConcreteGenerator : Generator {

    override val id = "text-concrete"
    override val family = "text"
    override val styleName = "Concrete Poetry"
    override val definition =
        "Characters from a text string scattered across the canvas in artistic patterns."
    override val algorithmNotes =
        "Each character of the input text is placed individually. In 'wave' mode, " +
        "characters follow noise-displaced wave rows. In 'spiral' mode, an Archimedean spiral " +
        "arranges them. 'circle' places concentric rings. 'diagonal' arranges along diagonal lines. " +
        "'radial' places characters along spoke lines from center."
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
        val customText = (params["customText"] as? String) ?: ""
        val textSource = (params["textSource"] as? String) ?: "alphabet"
        val fontSize = (params["fontSize"] as? Number)?.toFloat() ?: 24f
        val density = (params["density"] as? Number)?.toFloat() ?: 1.0f
        val letterSpacing = (params["letterSpacing"] as? Number)?.toFloat() ?: 1.2f
        val arrangement = (params["pathType"] as? String) ?: "spiral"
        val speed = (params["speed"] as? Number)?.toFloat() ?: 0.5f
        val timeOff = time * speed

        // Build text from customText or textSource
        val text: String = if (customText.isNotEmpty()) {
            customText
        } else {
            when (textSource) {
                "alphabet" -> "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
                "digits" -> "0123456789"
                "symbols" -> "!@#\$%^&*()+-=<>?/[]{}|~"
                "words" -> "HELLO WORLD CODE ART PIXEL WAVE FLOW GRID MESH NODE"
                else -> "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
            }
        }
        if (text.isEmpty()) return

        val noise = SimplexNoise(seed)
        val rng = SeededRNG(seed)

        canvas.drawColor(Color.BLACK)
        val paint = Paint().apply {
            isAntiAlias = quality != Quality.DRAFT
            textSize = fontSize
            typeface = Typeface.MONOSPACE
            textAlign = Paint.Align.CENTER
        }

        val charCount = (w * h * density / (fontSize * fontSize * 1.5f)).toInt().coerceIn(10, 10000)

        when (arrangement) {
            "wave" -> {
                val rowSpacing = fontSize * letterSpacing
                val colSpacing = fontSize * 0.8f * letterSpacing
                val rows = (h / rowSpacing).toInt()
                val charsPerRow = (w / colSpacing).toInt()
                var idx = 0

                for (row in 0 until rows) {
                    for (col in 0 until charsPerRow) {
                        if (idx >= charCount) break
                        val charIdx = idx % text.length
                        val ch = text[charIdx].toString()

                        val nx = col.toFloat() / charsPerRow
                        val ny = row.toFloat() / rows

                        val baseX = col * colSpacing + colSpacing / 2f
                        val baseY = row * rowSpacing + fontSize

                        // Multi-frequency noise displacement
                        val waveOffset = noise.fbm(
                            nx * 3f + timeOff * 0.5f,
                            ny * 2f + timeOff * 0.2f,
                            3
                        ) * fontSize * 3f

                        // Noise-driven size variation
                        val sizeNoise = noise.noise2D(nx * 4f + 50f, ny * 4f + 50f)
                        val sizeFactor = 1f + sizeNoise * 0.3f
                        paint.textSize = fontSize * sizeFactor.coerceIn(0.5f, 1.5f)

                        // Smooth color gradient
                        val t = (nx + ny) * 0.5f
                        paint.color = palette.lerpColor(t)

                        // Noise-driven alpha
                        val alphaNoise = noise.noise2D(nx * 3f + 100f, ny * 3f)
                        paint.alpha = (180 + alphaNoise * 75f).toInt().coerceIn(80, 255)

                        canvas.save()
                        canvas.translate(baseX, baseY + waveOffset)
                        val rotation = noise.noise2D(nx * 2f + timeOff, ny * 2f) * 15f
                        canvas.rotate(rotation)
                        canvas.drawText(ch, 0f, 0f, paint)
                        canvas.restore()
                        idx++
                    }
                }
            }
            "spiral" -> {
                val cx = w / 2f
                val cy = h / 2f
                val maxR = min(w, h) * 0.45f
                val angularSpacing = letterSpacing * 0.15f // controls angular gap

                for (i in 0 until charCount) {
                    val t = i.toFloat() / charCount
                    val angle = t * 10f * PI.toFloat() * (1f / letterSpacing.coerceAtLeast(0.5f)) + timeOff
                    val r = maxR * t
                    val px = cx + cos(angle) * r
                    val py = cy + sin(angle) * r

                    if (px < -fontSize || px > w + fontSize || py < -fontSize || py > h + fontSize) continue

                    val charIdx = i % text.length
                    val ch = text[charIdx].toString()

                    // Smooth gradient from center to edge
                    paint.color = palette.lerpColor(t)

                    // Noise-driven size variation
                    val sizeNoise = noise.noise2D(t * 5f + 30f, angle * 0.5f)
                    paint.textSize = fontSize * (1f + sizeNoise * 0.25f).coerceIn(0.5f, 1.5f)

                    paint.alpha = (200 + sizeNoise * 55f).toInt().coerceIn(100, 255)

                    canvas.save()
                    canvas.translate(px, py)
                    canvas.rotate(angle * 180f / PI.toFloat() + 90f)
                    canvas.drawText(ch, 0f, 0f, paint)
                    canvas.restore()
                }
            }
            "circle" -> {
                val cx = w / 2f
                val cy = h / 2f
                val maxR = min(w, h) * 0.45f
                val ringSpacing = fontSize * letterSpacing
                val ringCount = (maxR / ringSpacing).toInt().coerceAtLeast(1)
                var idx = 0

                for (ring in 0 until ringCount) {
                    val ringT = ring.toFloat() / ringCount.coerceAtLeast(1)
                    val r = ringSpacing * (ring + 1)
                    val circumference = (2f * PI.toFloat() * r)
                    val charsInRing = (circumference / (fontSize * 0.8f * letterSpacing)).toInt().coerceAtLeast(4)

                    for (j in 0 until charsInRing) {
                        if (idx >= charCount) break
                        val angle = j.toFloat() / charsInRing * 2f * PI.toFloat() + timeOff * (if (ring % 2 == 0) 1f else -1f)
                        val px = cx + cos(angle) * r
                        val py = cy + sin(angle) * r

                        val charIdx = idx % text.length
                        val ch = text[charIdx].toString()

                        // Ring-based color gradient
                        paint.color = palette.lerpColor(ringT)

                        // Per-character noise size variation
                        val nv = noise.noise2D(px * 0.01f, py * 0.01f)
                        paint.textSize = fontSize * (1f + nv * 0.3f).coerceIn(0.5f, 1.5f)
                        paint.alpha = (200 + nv * 55f).toInt().coerceIn(100, 255)

                        canvas.save()
                        canvas.translate(px, py)
                        canvas.rotate(angle * 180f / PI.toFloat() + 90f)
                        canvas.drawText(ch, 0f, 0f, paint)
                        canvas.restore()
                        idx++
                    }
                }
            }
            "diagonal" -> {
                val diagonalSpacing = fontSize * letterSpacing
                val cx = w / 2f
                val cy = h / 2f
                val maxDist = sqrt(w * w + h * h)
                val lineCount = (maxDist / diagonalSpacing).toInt().coerceAtLeast(1)
                var idx = 0

                for (line in 0 until lineCount) {
                    if (idx >= charCount) break
                    val lineT = line.toFloat() / lineCount
                    // Alternating direction per line
                    val goingDown = line % 2 == 0
                    val lineLength = maxDist
                    val charsInLine = (lineLength / (fontSize * 0.8f * letterSpacing)).toInt().coerceAtLeast(1)

                    // Phase-offset animation
                    val phaseOffset = timeOff * (if (goingDown) 1f else -1f) + line * 0.5f

                    for (j in 0 until charsInLine) {
                        if (idx >= charCount) break
                        val jt = j.toFloat() / charsInLine

                        // Diagonal from top-left to bottom-right, offset by line index
                        val startX = -fontSize + line * diagonalSpacing * 0.7f
                        val startY = -fontSize
                        val px = startX + jt * w * 0.7f + sin(phaseOffset + jt * 3f) * fontSize * 0.5f
                        val py = startY + jt * h + cos(phaseOffset + jt * 2f) * fontSize * 0.5f

                        if (px < -fontSize || px > w + fontSize || py < -fontSize || py > h + fontSize) {
                            idx++
                            continue
                        }

                        val charIdx = idx % text.length
                        val ch = text[charIdx].toString()

                        paint.color = palette.lerpColor(lineT)

                        val nv = noise.noise2D(px * 0.01f + 40f, py * 0.01f + 40f)
                        paint.textSize = fontSize * (1f + nv * 0.2f).coerceIn(0.6f, 1.4f)
                        paint.alpha = (200 + nv * 55f).toInt().coerceIn(100, 255)

                        canvas.save()
                        canvas.translate(px, py)
                        val angle = if (goingDown) 45f else -45f
                        canvas.rotate(angle + nv * 10f)
                        canvas.drawText(ch, 0f, 0f, paint)
                        canvas.restore()
                        idx++
                    }
                }
            }
            "radial" -> {
                val cx = w / 2f
                val cy = h / 2f
                val maxR = min(w, h) * 0.48f
                val spokeCount = (12 * density).toInt().coerceIn(4, 36)
                val charsPerSpoke = (maxR / (fontSize * letterSpacing * 0.8f)).toInt().coerceAtLeast(2)
                var idx = 0

                for (spoke in 0 until spokeCount) {
                    val spokeAngle = spoke.toFloat() / spokeCount * 2f * PI.toFloat() + timeOff * 0.3f

                    for (j in 0 until charsPerSpoke) {
                        if (idx >= charCount) break
                        val dist = (j + 1).toFloat() / charsPerSpoke * maxR
                        val distT = dist / maxR

                        // Slight angular wobble from noise
                        val wobble = noise.noise2D(dist * 0.02f, spoke.toFloat()) * 0.1f
                        val angle = spokeAngle + wobble

                        val px = cx + cos(angle) * dist
                        val py = cy + sin(angle) * dist

                        val charIdx = idx % text.length
                        val ch = text[charIdx].toString()

                        // Radial color gradient
                        paint.color = palette.lerpColor(distT)

                        // Size tapers toward edge
                        val sizeFactor = 1.2f - distT * 0.4f
                        paint.textSize = fontSize * sizeFactor.coerceIn(0.4f, 1.5f)
                        paint.alpha = (255 - distT * 80f).toInt().coerceIn(120, 255)

                        canvas.save()
                        canvas.translate(px, py)
                        canvas.rotate(angle * 180f / PI.toFloat() + 90f)
                        canvas.drawText(ch, 0f, 0f, paint)
                        canvas.restore()
                        idx++
                    }
                }
            }
        }
    }

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float = 0.3f
}
