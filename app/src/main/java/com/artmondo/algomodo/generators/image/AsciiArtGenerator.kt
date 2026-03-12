package com.artmondo.algomodo.generators.image

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import com.artmondo.algomodo.data.palettes.Palette
import com.artmondo.algomodo.generators.Generator
import com.artmondo.algomodo.generators.ParamGroup
import com.artmondo.algomodo.generators.Parameter
import com.artmondo.algomodo.generators.Quality
import com.artmondo.algomodo.rendering.SvgPath

/**
 * ASCII art generator from a source image.
 *
 * Converts the source bitmap into a grid of characters where each character
 * is chosen based on the average brightness of its cell.
 */
class AsciiArtGenerator : Generator {

    override val id = "ascii-art"
    override val family = "image"
    override val styleName = "ASCII Art"
    override val definition =
        "Convert a source image into ASCII characters mapped by brightness."
    override val algorithmNotes =
        "The source image is divided into cells. Each cell's average luminance is computed " +
        "and mapped to a character from a brightness ramp. 'standard' uses \" .:-=+*#%@\", " +
        "'blocks' uses Unicode block elements, 'braille' uses braille dot patterns. " +
        "Optional coloring preserves the source image's hue per cell."
    override val supportsVector = false
    override val supportsAnimation = true

    companion object {
        private const val STANDARD_RAMP = " .,:;i1tfLCG08@"
        private const val BLOCK_RAMP = " \u2591\u2592\u2593\u2588"
        private const val BRAILLE_RAMP = " \u2801\u2803\u2807\u280F\u281F\u283F\u287F\u28FF"
    }

    override val parameterSchema = listOf(
        Parameter.NumberParam("Cell Size", "cellSize", ParamGroup.GEOMETRY, "Pixel size of each character cell", 3f, 20f, 1f, 8f),
        Parameter.SelectParam("Char Set", "charSet", ParamGroup.TEXTURE, "Character set for brightness mapping", listOf("standard", "blocks", "braille", "katakana", "digits"), "standard"),
        Parameter.BooleanParam("Colored", "colored", ParamGroup.COLOR, "Use source image colours instead of monochrome", true),
        Parameter.BooleanParam("Invert", "invert", ParamGroup.COLOR, "Invert brightness mapping — light chars on dark background", false),
        Parameter.NumberParam("Contrast", "contrast", ParamGroup.TEXTURE, "Boost luminance contrast before mapping", 0.5f, 3f, 0.1f, 1f),
        Parameter.SelectParam("Background", "background", ParamGroup.COLOR, null, listOf("black", "white", "source"), "black"),
        Parameter.NumberParam("Speed", "speed", ParamGroup.FLOW_MOTION, "Animation speed — characters cycle over time", 0f, 2f, 0.1f, 0f)
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "cellSize" to 8f,
        "charSet" to "standard",
        "colored" to true,
        "invert" to false,
        "contrast" to 1f,
        "background" to "black",
        "speed" to 0f
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
        val w = bitmap.width
        val h = bitmap.height
        val cellSize = (params["cellSize"] as? Number)?.toInt() ?: 8
        val charSet = (params["charSet"] as? String) ?: "standard"
        val colored = params["colored"] as? Boolean ?: true
        val invertBrightness = params["invert"] as? Boolean ?: false
        val contrast = (params["contrast"] as? Number)?.toFloat() ?: 1f
        val background = (params["background"] as? String) ?: "black"

        val source = params["_sourceImage"] as? Bitmap
        if (source == null) {
            drawPlaceholder(canvas, bitmap)
            return
        }

        val ramp = when (charSet) {
            "blocks" -> BLOCK_RAMP
            "braille" -> BRAILLE_RAMP
            else -> STANDARD_RAMP
        }
        val speed = (params["speed"] as? Number)?.toFloat() ?: 0f
        val rampShift = if (speed > 0f && time > 0f) (time * speed * 3f).toInt() % ramp.length else 0

        val bgColor = when (background) {
            "white" -> Color.WHITE
            "source" -> Color.DKGRAY  // will be overwritten per-cell if needed
            else -> Color.BLACK
        }
        canvas.drawColor(bgColor)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.MONOSPACE
            textSize = cellSize.toFloat() * 1.0f
            textAlign = Paint.Align.CENTER
        }

        // Scale source to match output dimensions
        val scaled = if (source.width == w && source.height == h) source else Bitmap.createScaledBitmap(source, w, h, true)

        val cols = w / cellSize
        val rows = h / cellSize

        for (row in 0 until rows) {
            for (col in 0 until cols) {
                // Average brightness and color in cell
                var rSum = 0L; var gSum = 0L; var bSum = 0L; var count = 0

                for (dy in 0 until cellSize) {
                    for (dx in 0 until cellSize) {
                        val px = col * cellSize + dx
                        val py = row * cellSize + dy
                        if (px < w && py < h) {
                            val pixel = scaled.getPixel(px, py)
                            rSum += Color.red(pixel)
                            gSum += Color.green(pixel)
                            bSum += Color.blue(pixel)
                            count++
                        }
                    }
                }

                if (count == 0) continue

                val avgR = (rSum / count).toInt()
                val avgG = (gSum / count).toInt()
                val avgB = (bSum / count).toInt()
                var luma = (0.299f * avgR + 0.587f * avgG + 0.114f * avgB) / 255f
                // Apply contrast
                luma = ((luma - 0.5f) * contrast + 0.5f).coerceIn(0f, 1f)
                // Apply invert
                if (invertBrightness) luma = 1f - luma

                val charIdx = ((luma * (ramp.length - 1)).toInt() + rampShift).coerceIn(0, ramp.length - 1)
                val ch = ramp[charIdx].toString()

                // Draw background source colour per cell if background == "source"
                if (background == "source") {
                    val bgPaint = Paint().apply {
                        color = Color.rgb(
                            (avgR * 0.3f).toInt().coerceIn(0, 255),
                            (avgG * 0.3f).toInt().coerceIn(0, 255),
                            (avgB * 0.3f).toInt().coerceIn(0, 255)
                        )
                        style = Paint.Style.FILL
                    }
                    canvas.drawRect(
                        (col * cellSize).toFloat(), (row * cellSize).toFloat(),
                        ((col + 1) * cellSize).toFloat(), ((row + 1) * cellSize).toFloat(),
                        bgPaint
                    )
                }

                if (colored) {
                    paint.color = Color.rgb(avgR, avgG, avgB)
                } else {
                    val gray = (luma * 255).toInt().coerceIn(0, 255)
                    paint.color = Color.rgb(gray, gray, gray)
                }

                val cx = col * cellSize + cellSize / 2f
                val cy = row * cellSize + cellSize * 0.8f
                canvas.drawText(ch, cx, cy, paint)
            }
        }

        if (scaled !== source) scaled.recycle()
    }

    private fun drawPlaceholder(canvas: Canvas, bitmap: Bitmap) {
        canvas.drawColor(Color.DKGRAY)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 24f
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("No source image", bitmap.width / 2f, bitmap.height / 2f, paint)
    }

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float {
        val cellSize = (params["cellSize"] as? Number)?.toInt() ?: 8
        return (8f / cellSize).coerceIn(0.2f, 1f)
    }
}
