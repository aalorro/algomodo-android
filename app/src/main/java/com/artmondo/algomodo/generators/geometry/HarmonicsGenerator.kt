package com.artmondo.algomodo.generators.geometry

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import com.artmondo.algomodo.core.rng.SeededRNG
import com.artmondo.algomodo.data.palettes.Palette
import com.artmondo.algomodo.generators.Generator
import com.artmondo.algomodo.generators.ParamGroup
import com.artmondo.algomodo.generators.Parameter
import com.artmondo.algomodo.generators.Quality
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

class HarmonicsGenerator : Generator {

    override val id = "geo-harmonics"
    override val family = "geometry"
    override val styleName = "Harmonics"
    override val definition =
        "Grid of stacked parallelogram tiles with sine-driven heights — alternating left-right lean, " +
        "height-mapped colour gradients, and top-to-bottom occlusion create layered depth."
    override val algorithmNotes =
        "Sets up a grid of rows × cols tiles. Each column gets a seeded random phase offset and " +
        "gradient direction. Stack height for each tile is driven by sin(row × frequency × phaseStretch " +
        "+ columnPhaseOffset + animPhase), scaled by amplitude. Tiles are drawn as filled parallelograms " +
        "with alternating lean. Rows are drawn top-to-bottom so lower rows overlap upper ones, producing " +
        "occlusion and natural depth. Colour is mapped through the palette gradient with per-column " +
        "inversion for contrast."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema: List<Parameter> = listOf(
        Parameter.NumberParam(
            name = "Rows",
            key = "rows",
            group = ParamGroup.COMPOSITION,
            help = "Number of tile rows — more rows create denser vertical layering",
            min = 6f, max = 24f, step = 1f, default = 14f
        ),
        Parameter.NumberParam(
            name = "Columns",
            key = "cols",
            group = ParamGroup.COMPOSITION,
            help = "Number of tile columns",
            min = 4f, max = 20f, step = 1f, default = 10f
        ),
        Parameter.NumberParam(
            name = "Amplitude",
            key = "amplitude",
            group = ParamGroup.GEOMETRY,
            help = "Height variation — how strongly the sine wave modulates stack height",
            min = 0.2f, max = 1.0f, step = 0.05f, default = 0.6f
        ),
        Parameter.NumberParam(
            name = "Lean",
            key = "lean",
            group = ParamGroup.GEOMETRY,
            help = "Parallelogram lean in pixels — alternates left/right per column",
            min = 0f, max = 40f, step = 1f, default = 15f
        ),
        Parameter.NumberParam(
            name = "Frequency",
            key = "frequency",
            group = ParamGroup.GEOMETRY,
            help = "Sine wave frequency — how quickly height oscillates across rows",
            min = 0.5f, max = 4.0f, step = 0.1f, default = 1.5f
        ),
        Parameter.NumberParam(
            name = "3D Depth",
            key = "depth3d",
            group = ParamGroup.GEOMETRY,
            help = "Pseudo-3D extrusion depth — adds top and side faces for an isometric look (0 = flat)",
            min = 0f, max = 30f, step = 1f, default = 0f
        ),
        Parameter.NumberParam(
            name = "Gap",
            key = "gap",
            group = ParamGroup.TEXTURE,
            help = "Horizontal gap between tiles in pixels",
            min = 0f, max = 24f, step = 1f, default = 4f
        ),
        Parameter.BooleanParam(
            name = "Stroke",
            key = "stroke",
            group = ParamGroup.TEXTURE,
            help = "Draw thin edge outlines on each tile for definition",
            default = true
        ),
        Parameter.SelectParam(
            name = "Color Mode",
            key = "colorMode",
            group = ParamGroup.COLOR,
            help = "height: colour mapped to stack height | row: colour by row position | random: golden-ratio palette spread",
            options = listOf("height", "row", "random"),
            default = "height"
        ),
        Parameter.NumberParam(
            name = "Anim Speed",
            key = "animSpeed",
            group = ParamGroup.FLOW_MOTION,
            help = "Phase drift speed during animation",
            min = 0.05f, max = 2f, step = 0.05f, default = 0.3f
        ),
        Parameter.NumberParam(
            name = "Anim Amplitude",
            key = "animAmp",
            group = ParamGroup.FLOW_MOTION,
            help = "How much the sine wave shifts during animation",
            min = 0.05f, max = 1f, step = 0.05f, default = 0.3f
        )
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "rows" to 14f,
        "cols" to 10f,
        "amplitude" to 0.6f,
        "lean" to 15f,
        "frequency" to 1.5f,
        "depth3d" to 0f,
        "gap" to 4f,
        "stroke" to true,
        "colorMode" to "height",
        "animSpeed" to 0.3f,
        "animAmp" to 0.3f
    )

    private fun paletteSample(t: Float, colors: List<Int>): Int {
        val s = t.coerceIn(0f, 1f) * (colors.size - 1)
        val i0 = s.toInt()
        val i1 = min(colors.size - 1, i0 + 1)
        val f = s - i0
        val c0 = colors[i0]
        val c1 = colors[i1]
        val r = ((Color.red(c0) + (Color.red(c1) - Color.red(c0)) * f)).toInt().coerceIn(0, 255)
        val g = ((Color.green(c0) + (Color.green(c1) - Color.green(c0)) * f)).toInt().coerceIn(0, 255)
        val b = ((Color.blue(c0) + (Color.blue(c1) - Color.blue(c0)) * f)).toInt().coerceIn(0, 255)
        return Color.rgb(r, g, b)
    }

    private fun dimColor(color: Int, factor: Float): Int {
        val r = (Color.red(color) * factor).toInt().coerceIn(0, 255)
        val g = (Color.green(color) * factor).toInt().coerceIn(0, 255)
        val b = (Color.blue(color) * factor).toInt().coerceIn(0, 255)
        return Color.rgb(r, g, b)
    }

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

        canvas.drawColor(Color.BLACK)

        val rows = (params["rows"] as? Number)?.toInt()?.coerceAtLeast(4) ?: 14
        val cols = (params["cols"] as? Number)?.toInt()?.coerceAtLeast(2) ?: 10
        val amplitude = (params["amplitude"] as? Number)?.toFloat() ?: 0.6f
        val lean = (params["lean"] as? Number)?.toFloat() ?: 15f
        val freq = (params["frequency"] as? Number)?.toFloat() ?: 1.5f
        val depth3d = (params["depth3d"] as? Number)?.toFloat() ?: 0f
        val gap = (params["gap"] as? Number)?.toFloat() ?: 4f
        val showStroke = (params["stroke"] as? Boolean) ?: true
        val colorMode = (params["colorMode"] as? String) ?: "height"
        val animSpeed = (params["animSpeed"] as? Number)?.toFloat() ?: 0.3f
        val animAmp = (params["animAmp"] as? Number)?.toFloat() ?: 0.3f

        val rng = SeededRNG(seed)
        val colors = palette.colorInts()

        // Scale lean proportionally to canvas width
        val leanScaled = lean * (w / 1080f)
        val gapScaled = gap * (w / 1080f)
        val depth3dScaled = depth3d * (w / 1080f)

        // Animation phase
        val animPhase = time * animSpeed

        // Per-column seeded properties
        val colPhase = FloatArray(cols)
        val colInvert = BooleanArray(cols)
        val colStretch = FloatArray(cols)
        for (c in 0 until cols) {
            colPhase[c] = rng.random() * PI.toFloat() * 2f
            colInvert[c] = rng.random() > 0.5f
            colStretch[c] = 0.8f + rng.random() * 0.4f
        }

        // Layout geometry
        val absLean = abs(leanScaled)
        val tileW = (w - gapScaled * (cols + 1)) / cols - absLean
        val baseH = h / rows
        val maxStackH = baseH * (1.0f + amplitude * 1.6f)
        val minStackH = baseH * (0.6f - amplitude * 0.4f)

        val fillPaint = Paint().apply {
            style = Paint.Style.FILL
            isAntiAlias = quality != Quality.DRAFT
        }
        val strokePaint = Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.5f * (w / 1080f)
            isAntiAlias = quality != Quality.DRAFT
            strokeJoin = Paint.Join.MITER
            strokeCap = Paint.Cap.BUTT
        }

        val path = Path()

        // Draw top-to-bottom for occlusion
        for (row in 0 until rows) {
            val rowT = row.toFloat() / max(1, rows - 1)

            for (col in 0 until cols) {
                // Sine-driven height
                val phase = row * freq * colStretch[col] + colPhase[col] + animPhase
                val sineVal = sin(phase)
                val normalizedSine = (sineVal + 1f) * 0.5f
                val stackH = minStackH + (maxStackH - minStackH) *
                    (normalizedSine * amplitude + (1f - amplitude) * 0.5f)

                // Animation wobble
                val wobble = if (time > 0f) {
                    sin(animPhase * 0.7f + col * 0.4f + row * 0.3f) * animAmp * baseH * 0.15f
                } else 0f

                // Tile position
                val slotW = (w - gapScaled * (cols + 1)) / cols
                val slotX = gapScaled + col * (slotW + gapScaled)
                val x = slotX + (slotW - tileW) * 0.5f
                val yBottom = (row + 1) * baseH + wobble
                val yTop = yBottom - stackH

                // Lean: alternating left/right per column
                val leanDir = if (col % 2 == 0) 1f else -1f
                val leanPx = leanScaled * leanDir

                // Parallelogram corners
                val blX = x; val blY = yBottom
                val brX = x + tileW; val brY = yBottom
                val trX = x + tileW + leanPx; val trY = yTop
                val tlX = x + leanPx; val tlY = yTop

                // Color mapping
                var colorT = when (colorMode) {
                    "row" -> rowT
                    "random" -> (col * 0.618f + row * 0.382f) % 1.0f
                    else -> normalizedSine // "height"
                }
                if (colInvert[col]) colorT = 1f - colorT

                val baseColor = paletteSample(colorT, colors)

                // Depth dimming: upper rows slightly lighter, lower rows darker
                val depthDim = 0.7f + 0.3f * (1f - rowT)
                val faceColor = dimColor(baseColor, depthDim)

                // 3D extrusion: side and top faces before front face
                if (depth3dScaled > 0f) {
                    val dx = depth3dScaled
                    val dy = -depth3dScaled * 0.6f

                    // Right side face — darker
                    val sideColor = dimColor(faceColor, 0.45f)
                    path.reset()
                    path.moveTo(brX, brY)
                    path.lineTo(brX + dx, brY + dy)
                    path.lineTo(trX + dx, trY + dy)
                    path.lineTo(trX, trY)
                    path.close()
                    fillPaint.color = sideColor
                    canvas.drawPath(path, fillPaint)
                    if (showStroke) {
                        strokePaint.color = dimColor(sideColor, 0.5f)
                        strokePaint.strokeWidth = 1f * (w / 1080f)
                        canvas.drawPath(path, strokePaint)
                    }

                    // Top face — lighter
                    val topBright = min(1.4f, 1.0f + depth3d * 0.015f)
                    val topColor = dimColor(faceColor, topBright)
                    path.reset()
                    path.moveTo(tlX, tlY)
                    path.lineTo(trX, trY)
                    path.lineTo(trX + dx, trY + dy)
                    path.lineTo(tlX + dx, tlY + dy)
                    path.close()
                    fillPaint.color = topColor
                    canvas.drawPath(path, fillPaint)
                    if (showStroke) {
                        strokePaint.color = dimColor(topColor, 0.6f)
                        strokePaint.strokeWidth = 1f * (w / 1080f)
                        canvas.drawPath(path, strokePaint)
                    }
                }

                // Front face (main parallelogram)
                path.reset()
                path.moveTo(blX, blY)
                path.lineTo(brX, brY)
                path.lineTo(trX, trY)
                path.lineTo(tlX, tlY)
                path.close()
                fillPaint.color = faceColor
                canvas.drawPath(path, fillPaint)

                if (showStroke) {
                    strokePaint.color = dimColor(faceColor, 0.25f)
                    strokePaint.strokeWidth = 1.5f * (w / 1080f)
                    canvas.drawPath(path, strokePaint)
                }
            }
        }
    }

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float {
        val rows = (params["rows"] as? Number)?.toInt() ?: 14
        val cols = (params["cols"] as? Number)?.toInt() ?: 10
        return (rows * cols / 500f).coerceIn(0.1f, 0.5f)
    }
}
