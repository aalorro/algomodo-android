package com.artmondo.algomodo.generators.procedural

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import com.artmondo.algomodo.audio.AudioAnalysis
import com.artmondo.algomodo.core.rng.SeededRNG
import com.artmondo.algomodo.data.palettes.Palette
import com.artmondo.algomodo.generators.Generator
import com.artmondo.algomodo.generators.ParamGroup
import com.artmondo.algomodo.generators.Parameter
import com.artmondo.algomodo.generators.Quality
import kotlin.math.*

class FeedbackSystemsGenerator : Generator {

    override val id = "procedural-feedback-systems"
    override val family = "procedural"
    override val styleName = "Feedback Systems"
    override val definition =
        "Iterative visual feedback loops that zoom, rotate, and color-shift the canvas onto itself creating fractal-like recursive patterns."
    override val algorithmNotes =
        "Draws a seed pattern (circles, lines, grid, or spiral) onto an offscreen bitmap. " +
        "Iteratively transforms (scale + rotate) and composites the buffer back onto itself " +
        "with adjustable blend opacity. Each iteration applies a hue rotation for color drift."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema = listOf(
        Parameter.NumberParam("Iterations", "iterations", ParamGroup.COMPOSITION,
            "Number of recursive feedback passes", 3f, 30f, 1f, 12f),
        Parameter.NumberParam("Zoom Factor", "zoomFactor", ParamGroup.GEOMETRY,
            "Scale per iteration (<1 = zoom in, >1 = zoom out)", 0.85f, 1.15f, 0.01f, 0.97f),
        Parameter.NumberParam("Rotation Rate", "rotationRate", ParamGroup.FLOW_MOTION,
            "Rotation speed per iteration", 0f, 2f, 0.05f, 0.5f),
        Parameter.SelectParam("Seed Shape", "seedShape", ParamGroup.COMPOSITION,
            "Initial pattern fed into the feedback loop",
            listOf("circles", "lines", "grid", "spiral"), "circles"),
        Parameter.NumberParam("Color Drift", "colorDrift", ParamGroup.COLOR,
            "Hue rotation amount per iteration", 0f, 1f, 0.05f, 0.3f),
        Parameter.NumberParam("Blend Opacity", "blendOpacity", ParamGroup.COLOR,
            "Opacity of each feedback composite layer", 0.3f, 1.0f, 0.05f, 0.75f)
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "iterations" to 12f, "zoomFactor" to 0.97f, "rotationRate" to 0.5f,
        "seedShape" to "circles", "colorDrift" to 0.3f, "blendOpacity" to 0.75f
    )

    private fun drawSeedPattern(
        canvas: Canvas, shape: String, w: Int, h: Int,
        rng: SeededRNG, colors: List<Int>
    ) {
        val cx = w / 2f; val cy = h / 2f
        val minDim = min(w, h).toFloat()
        val nC = colors.size
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val TAU = PI.toFloat() * 2f

        when (shape) {
            "circles" -> {
                paint.style = Paint.Style.FILL
                val count = rng.integer(6, 14)
                for (i in 0 until count) {
                    val r = rng.range(minDim * 0.03f, minDim * 0.12f)
                    val x = rng.range(w * 0.15f, w * 0.85f)
                    val y = rng.range(h * 0.15f, h * 0.85f)
                    paint.color = colors[i % nC]
                    canvas.drawCircle(x, y, r, paint)
                }
            }
            "lines" -> {
                paint.style = Paint.Style.STROKE
                paint.strokeCap = Paint.Cap.ROUND
                val count = rng.integer(8, 20)
                for (i in 0 until count) {
                    paint.strokeWidth = rng.range(minDim * 0.003f, minDim * 0.015f)
                    val a = rng.randomAngle()
                    val len = rng.range(minDim * 0.15f, minDim * 0.45f)
                    paint.color = colors[i % nC]
                    canvas.drawLine(
                        cx + cos(a) * len * 0.1f, cy + sin(a) * len * 0.1f,
                        cx + cos(a) * len, cy + sin(a) * len, paint
                    )
                }
            }
            "grid" -> {
                paint.style = Paint.Style.FILL
                val cols = rng.integer(4, 10)
                val rows = rng.integer(4, 10)
                val cellW = w.toFloat() / (cols + 1)
                val cellH = h.toFloat() / (rows + 1)
                val dotR = min(cellW, cellH) * 0.25f
                for (r in 0 until rows) {
                    for (c in 0 until cols) {
                        val x = cellW * (c + 1)
                        val y = cellH * (r + 1)
                        paint.color = colors[(r * cols + c) % nC]
                        canvas.drawCircle(x, y, dotR, paint)
                    }
                }
            }
            else -> { // spiral
                paint.style = Paint.Style.STROKE
                paint.strokeCap = Paint.Cap.ROUND
                paint.strokeWidth = minDim * 0.008f
                val turns = rng.range(3f, 8f)
                val maxR = minDim * 0.4f
                val steps = 300
                val path = Path()
                for (s in 0..steps) {
                    val tt = s.toFloat() / steps
                    val a = tt * turns * TAU
                    val r = tt * maxR
                    val x = cx + cos(a) * r; val y = cy + sin(a) * r
                    if (s == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                paint.color = colors[0]
                canvas.drawPath(path, paint)
            }
        }
    }

    override fun renderCanvas(
        canvas: Canvas, bitmap: Bitmap, params: Map<String, Any>,
        seed: Int, palette: Palette, quality: Quality, time: Float
    ) {
        val w = bitmap.width; val h = bitmap.height
        val midX = w / 2f; val midY = h / 2f
        val rng = SeededRNG(seed)

        val audioAnalysis = params["_audioAnalysis"] as? AudioAnalysis
        val audioBass = audioAnalysis?.getBass(time) ?: 0f
        val audioMid = audioAnalysis?.getMid(time) ?: 0f

        val iterations = (params["iterations"] as? Number)?.toInt()?.coerceAtLeast(1) ?: 12
        var zoom = (params["zoomFactor"] as? Number)?.toFloat() ?: 0.97f
        val rotRate = (params["rotationRate"] as? Number)?.toFloat() ?: 0.5f
        val seedShape = (params["seedShape"] as? String) ?: "circles"
        val colorDrift = (params["colorDrift"] as? Number)?.toFloat() ?: 0.3f
        val blendOpacity = (params["blendOpacity"] as? Number)?.toFloat() ?: 0.75f

        zoom *= (1f + audioBass * 0.15f)
        val audioMidRotation = audioMid * 0.4f

        val doColorDrift = colorDrift > 0f
        val colors = palette.colorInts()

        // Offscreen buffers
        val bufA = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvasA = Canvas(bufA)
        val bufB = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvasB = Canvas(bufB)

        // Draw seed pattern on buffer A
        canvasA.drawColor(Color.BLACK)
        drawSeedPattern(canvasA, seedShape, w, h, rng, colors)

        // Clear main canvas
        canvas.drawColor(Color.rgb(5, 5, 5))

        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val matrix = Matrix()

        for (i in 0 until iterations) {
            val angle = rotRate * (time + i * 0.12f) + audioMidRotation

            // Transform A → B
            bufB.eraseColor(Color.TRANSPARENT)
            val cosA = cos(angle) * zoom
            val sinA = sin(angle) * zoom
            matrix.setValues(floatArrayOf(
                cosA, -sinA, midX - cosA * midX + sinA * midY,
                sinA, cosA, midY - sinA * midX - cosA * midY,
                0f, 0f, 1f
            ))
            canvasB.drawBitmap(bufA, matrix, paint)

            // Apply color drift via hue rotation
            if (doColorDrift) {
                val cm = ColorMatrix()
                cm.setRotate(0, i * colorDrift * 30f) // rotate red axis
                cm.setRotate(1, i * colorDrift * 20f) // rotate green axis
                val huePaint = Paint()
                huePaint.colorFilter = ColorMatrixColorFilter(cm)
                val tmpBitmap = bufB.copy(Bitmap.Config.ARGB_8888, true)
                canvasB.drawBitmap(tmpBitmap, 0f, 0f, huePaint)
                tmpBitmap.recycle()
            }

            // Composite B onto main canvas with additive blending
            paint.alpha = (blendOpacity * 255f).toInt().coerceIn(0, 255)
            paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
            canvas.drawBitmap(bufB, 0f, 0f, paint)
            paint.xfermode = null
            paint.alpha = 255

            // Swap: copy B → A
            bufA.eraseColor(Color.TRANSPARENT)
            canvasA.drawBitmap(bufB, 0f, 0f, null)
        }

        bufA.recycle()
        bufB.recycle()
    }

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float {
        val iterations = (params["iterations"] as? Number)?.toInt() ?: 12
        return (0.25f + iterations * 0.025f).coerceIn(0.3f, 1f)
    }
}
