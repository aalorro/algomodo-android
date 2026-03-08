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
        val customText = (params["customText"] as? String) ?: ""
        val charSetName = (params["charSet"] as? String) ?: "digits"
        val cellSize = (params["gridSize"] as? Number)?.toFloat() ?: 20f
        val noiseScale = (params["noiseScale"] as? Number)?.toFloat() ?: 3f
        val sizeVariation = (params["sizeVariation"] as? Number)?.toFloat() ?: 0f
        val colorMode = (params["colorMode"] as? String) ?: "palette-position"
        val speed = (params["speed"] as? Number)?.toFloat() ?: 0.5f
        val timeOff = time * speed

        // Build character pool from charSet or customText
        val charPool: String = if (customText.isNotEmpty()) {
            customText
        } else {
            when (charSetName) {
                "alphabet" -> "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
                "digits" -> "0123456789"
                "katakana" -> (0x30A0..0x30FF).map { it.toChar() }.joinToString("")
                "symbols" -> "!@#\$%^&*()+-=<>?/[]{}|~"
                "braille" -> (0x2800..0x28FF).map { it.toChar() }.joinToString("")
                else -> "0123456789"
            }
        }
        if (charPool.isEmpty()) return

        val noise = SimplexNoise(seed)
        val rng = SeededRNG(seed)

        canvas.drawColor(Color.BLACK)
        val paint = Paint().apply {
            isAntiAlias = quality != Quality.DRAFT
            typeface = Typeface.MONOSPACE
            textAlign = Paint.Align.CENTER
        }

        val cols = (w / cellSize).toInt()
        val rows = (h / cellSize).toInt()
        val totalCells = (cols * rows).toFloat().coerceAtLeast(1f)

        for (row in 0 until rows) {
            for (col in 0 until cols) {
                val idx = row * cols + col
                val cx = col * cellSize + cellSize / 2f
                val cy = row * cellSize + cellSize / 2f

                // Normalized grid coordinates
                val nx = col.toFloat() / cols.coerceAtLeast(1)
                val ny = row.toFloat() / rows.coerceAtLeast(1)

                // Noise value for this cell (animated)
                val nv = noise.noise2D(
                    nx * noiseScale + timeOff * 0.3f,
                    ny * noiseScale
                ) // [-1, 1]
                val nv01 = (nv + 1f) * 0.5f // [0, 1]

                // Character selection driven by noise for spatial coherence
                val charIdx = ((nv01 * charPool.length * 2f).toInt() + idx) % charPool.length
                val ch = charPool[charIdx.coerceIn(0, charPool.length - 1)].toString()

                // Color based on colorMode
                val color = when (colorMode) {
                    "palette-position" -> {
                        // Diagonal gradient via lerpColor
                        val diagT = (nx + ny) * 0.5f
                        palette.lerpColor(diagT)
                    }
                    "palette-noise" -> {
                        // Noise-mapped via lerpColor
                        palette.lerpColor(nv01)
                    }
                    "monochrome" -> {
                        palette.colorAt(0)
                    }
                    else -> palette.lerpColor((nx + ny) * 0.5f)
                }
                paint.color = color

                // Size variation driven by noise
                val baseFontSize = cellSize * 0.8f
                val sizeFactor = if (sizeVariation > 0f) {
                    val sizeNoise = noise.noise2D(
                        nx * noiseScale * 1.5f + 100f,
                        ny * noiseScale * 1.5f + 100f
                    )
                    1f + sizeNoise * sizeVariation * 0.5f
                } else {
                    1f
                }
                paint.textSize = baseFontSize * sizeFactor.coerceIn(0.3f, 2f)

                // Alpha: noise-driven density (denser in high-noise areas)
                val baseAlpha = 140 + (nv01 * 115f).toInt()
                paint.alpha = baseAlpha.coerceIn(40, 255)

                // Rotation when sizeVariation is high (>0.7)
                if (sizeVariation > 0.7f) {
                    val rotAmount = (sizeVariation - 0.7f) / 0.3f // 0..1
                    val rotNoise = noise.noise2D(
                        nx * noiseScale + 200f,
                        ny * noiseScale + 200f
                    )
                    val rotation = rotNoise * 45f * rotAmount
                    canvas.save()
                    canvas.translate(cx, cy)
                    canvas.rotate(rotation)
                    canvas.drawText(ch, 0f, paint.textSize / 3f, paint)
                    canvas.restore()
                } else {
                    canvas.drawText(ch, cx, cy + paint.textSize / 3f, paint)
                }
            }
        }
    }

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float {
        val cellSize = (params["gridSize"] as? Number)?.toFloat() ?: 20f
        return (20f / cellSize).coerceIn(0.1f, 0.6f)
    }
}
