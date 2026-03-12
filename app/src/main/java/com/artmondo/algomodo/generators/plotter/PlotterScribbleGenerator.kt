package com.artmondo.algomodo.generators.plotter

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import com.artmondo.algomodo.core.rng.SimplexNoise
import com.artmondo.algomodo.data.palettes.Palette
import com.artmondo.algomodo.generators.Generator
import com.artmondo.algomodo.generators.ParamGroup
import com.artmondo.algomodo.generators.Parameter
import com.artmondo.algomodo.generators.Quality
import kotlin.math.*

/**
 * Scribble-shading generator.
 *
 * Multi-pass directional hatching with FBM noise wobble — emulates organic
 * scribble-fill pen-plotter sketch art. Each pass sweeps parallel lines at a
 * distinct angle. Density fields shape where strokes appear.
 */
class PlotterScribbleGenerator : Generator {

    override val id = "plotter-scribble-shading"
    override val family = "plotter"
    override val styleName = "Scribble Shading"
    override val definition =
        "Multi-pass directional hatching with FBM noise wobble — emulates organic scribble-fill pen-plotter sketch art."
    override val algorithmNotes =
        "Each of N passes sweeps parallel lines at a distinct angle. Stroke styles (straight, " +
        "wavy, zigzag, loop) add character beyond simple wobble. Density styles (fbm, ridged, " +
        "radial, turbulent) shape where strokes appear. Variable width makes strokes thicken " +
        "in dense regions."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema = listOf(
        Parameter.NumberParam("Passes", "passCount", ParamGroup.COMPOSITION, "Number of overlapping angular stroke passes", 1f, 6f, 1f, 3f),
        Parameter.NumberParam("Line Spacing", "lineSpacing", ParamGroup.GEOMETRY, "Gap between parallel lines within each pass (px)", 3f, 24f, 1f, 8f),
        Parameter.SelectParam("Stroke Style", "strokeStyle", ParamGroup.TEXTURE, "straight: wobble only | wavy: sinusoidal | zigzag: sharp angles | loop: curly scribble", listOf("straight", "wavy", "zigzag", "loop"), "straight"),
        Parameter.NumberParam("Wobble", "wobble", ParamGroup.TEXTURE, "Noise-driven lateral deviation of each stroke (px)", 0f, 20f, 1f, 6f),
        Parameter.NumberParam("Density Scale", "densityScale", ParamGroup.TEXTURE, "Spatial frequency of the FBM density field", 0.5f, 8f, 0.25f, 2.5f),
        Parameter.SelectParam("Density Style", "densityStyle", ParamGroup.TEXTURE, "fbm: smooth | ridged: sharp creases | radial: center-focused | turbulent: chaotic", listOf("fbm", "ridged", "radial", "turbulent"), "fbm"),
        Parameter.NumberParam("Density Threshold", "densityThreshold", ParamGroup.TEXTURE, "Minimum density to draw a stroke at a point", 0.0f, 0.8f, 0.05f, 0.3f),
        Parameter.NumberParam("Opacity", "strokeOpacity", ParamGroup.COLOR, null, 0.1f, 1.0f, 0.05f, 0.65f),
        Parameter.NumberParam("Line Width", "lineWidth", ParamGroup.TEXTURE, null, 0.3f, 3f, 0.1f, 0.7f),
        Parameter.BooleanParam("Variable Width", "variableWidth", ParamGroup.TEXTURE, "Line width varies with density — thicker in dense areas", false),
        Parameter.SelectParam("Color Mode", "colorMode", ParamGroup.COLOR, "palette-pass: each angular pass uses a different palette colour", listOf("monochrome", "palette-pass", "palette-density", "palette-noise"), "palette-pass"),
        Parameter.SelectParam("Background", "background", ParamGroup.COLOR, null, listOf("white", "cream", "dark"), "cream"),
        Parameter.NumberParam("Anim Speed", "animSpeed", ParamGroup.FLOW_MOTION, "Flowing density field animation — 0 = static", 0f, 1f, 0.05f, 0f)
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "passCount" to 3f,
        "lineSpacing" to 8f,
        "strokeStyle" to "straight",
        "wobble" to 6f,
        "densityScale" to 2.5f,
        "densityStyle" to "fbm",
        "densityThreshold" to 0.3f,
        "strokeOpacity" to 0.65f,
        "lineWidth" to 0.7f,
        "variableWidth" to false,
        "colorMode" to "palette-pass",
        "background" to "cream",
        "animSpeed" to 0f
    )

    companion object {
        private val BG = mapOf(
            "white" to Color.rgb(248, 248, 245),
            "cream" to Color.rgb(242, 234, 216),
            "dark"  to Color.rgb(14, 14, 14)
        )
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

        val noise = SimplexNoise(seed)

        val passCount = ((params["passCount"] as? Number)?.toInt() ?: 3).coerceAtLeast(1)
        val spacing = ((params["lineSpacing"] as? Number)?.toFloat() ?: 8f).coerceAtLeast(2f)
        val wobbleAmt = (params["wobble"] as? Number)?.toFloat() ?: 6f
        val dScale = (params["densityScale"] as? Number)?.toFloat() ?: 2.5f
        val densityStyle = (params["densityStyle"] as? String) ?: "fbm"
        val threshold = (params["densityThreshold"] as? Number)?.toFloat() ?: 0.3f
        val opacity = (params["strokeOpacity"] as? Number)?.toFloat() ?: 0.65f
        val colorMode = (params["colorMode"] as? String) ?: "palette-pass"
        val strokeStyle = (params["strokeStyle"] as? String) ?: "straight"
        val variableWidth = (params["variableWidth"] as? Boolean) ?: false
        val baseLineWidth = (params["lineWidth"] as? Number)?.toFloat() ?: 0.7f
        val background = (params["background"] as? String) ?: "cream"
        val isDark = background == "dark"
        val animSpeed = (params["animSpeed"] as? Number)?.toFloat() ?: 0f
        val timeOff = time * animSpeed * 0.3f

        // Background
        canvas.drawColor(BG[background] ?: BG["cream"]!!)

        val paletteColors = palette.colorInts()
        val colorsRgb = Array(paletteColors.size) {
            intArrayOf(Color.red(paletteColors[it]), Color.green(paletteColors[it]), Color.blue(paletteColors[it]))
        }
        val numColors = colorsRgb.size

        val diagonal = sqrt(w * w + h * h)
        val ccx = w / 2f
        val ccy = h / 2f
        val sweepSteps = (diagonal / 4f).toInt() + 1

        val alphaInt = (opacity * 255f).toInt().coerceIn(0, 255)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = baseLineWidth
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        // Density function with style options
        fun densityFn(bx: Float, by: Float, pass: Int): Float {
            val nx = (bx / w - 0.5f) * dScale + 7f + pass * 3.7f + timeOff
            val ny = (by / h - 0.5f) * dScale + 7f + pass * 2.3f + timeOff * 0.7f
            val n = when (densityStyle) {
                "ridged" -> {
                    val raw = noise.fbm(nx, ny, 4, 2f, 0.5f)
                    val ridge = 1f - abs(raw)
                    ridge * ridge
                }
                "turbulent" -> abs(noise.fbm(nx, ny, 4, 2f, 0.5f))
                "radial" -> {
                    val dx = bx / w - 0.5f; val dy = by / h - 0.5f
                    val dist = sqrt(dx * dx + dy * dy) * 2f
                    val nv = noise.fbm(nx, ny, 3, 2f, 0.5f) * 0.3f
                    max(0f, 1f - dist + nv)
                }
                else -> noise.fbm(nx, ny, 4, 2f, 0.5f) * 0.5f + 0.5f
            }
            return max(0f, n)
        }

        // Reusable path and segment buffer
        val path = Path()
        val segPtsX = FloatArray(sweepSteps + 1)
        val segPtsY = FloatArray(sweepSteps + 1)

        for (pass in 0 until passCount) {
            val angle = pass.toFloat() / passCount * PI.toFloat()
            val cosA = cos(angle); val sinA = sin(angle)
            val cosPx = -sinA; val sinPx = cosA

            // Base color for this pass
            var pr: Int; var pg: Int; var pb: Int
            if (colorMode == "palette-pass") {
                val c = colorsRgb[pass % numColors]
                pr = c[0]; pg = c[1]; pb = c[2]
            } else if (isDark) {
                pr = 220; pg = 220; pb = 220
            } else {
                pr = 30; pg = 30; pb = 30
            }

            var d = -diagonal / 2f
            while (d <= diagonal / 2f) {
                val lx0 = ccx + cosPx * d - cosA * diagonal / 2f
                val ly0 = ccy + sinPx * d - sinA * diagonal / 2f

                // Trace the line, breaking into segments based on density threshold
                var segLen = 0
                val margin = wobbleAmt + 5f

                for (s in 0..sweepSteps) {
                    val t = s.toFloat() / sweepSteps
                    val bx = lx0 + cosA * diagonal * t
                    val by = ly0 + sinA * diagonal * t

                    // Skip if well outside canvas
                    if (bx < -margin || bx > w + margin || by < -margin || by > h + margin) {
                        if (segLen > 1) {
                            drawSegment(canvas, paint, path, segPtsX, segPtsY, segLen,
                                pass, colorMode, variableWidth, baseLineWidth, alphaInt,
                                pr, pg, pb, colorsRgb, numColors, noise, w, h, dScale,
                                ::densityFn)
                        }
                        segLen = 0
                        continue
                    }

                    val density = densityFn(bx, by, pass)
                    if (density > threshold) {
                        // Base wobble from noise
                        val wn = noise.noise2D(
                            (bx / w) * dScale * 2.5f + pass * 13.1f,
                            (by / h) * dScale * 2.5f + pass * 9.7f
                        )
                        var offX = cosPx * wn * wobbleAmt
                        var offY = sinPx * wn * wobbleAmt

                        // Stroke style modifications
                        when (strokeStyle) {
                            "wavy" -> {
                                val wave = sin(t * diagonal * 0.08f + pass * 2f) * spacing * 0.4f
                                offX += cosPx * wave
                                offY += sinPx * wave
                            }
                            "zigzag" -> {
                                val zig = (if (s % 6 < 3) 1f else -1f) * spacing * 0.35f
                                offX += cosPx * zig
                                offY += sinPx * zig
                            }
                            "loop" -> {
                                val loopT = t * diagonal * 0.05f + pass * 1.7f
                                val loopR = spacing * 0.5f * (0.3f + density * 0.7f)
                                offX += cos(loopT * 6f) * loopR
                                offY += sin(loopT * 6f) * loopR
                            }
                        }

                        segPtsX[segLen] = bx + offX
                        segPtsY[segLen] = by + offY
                        segLen++
                    } else {
                        if (segLen > 1) {
                            drawSegment(canvas, paint, path, segPtsX, segPtsY, segLen,
                                pass, colorMode, variableWidth, baseLineWidth, alphaInt,
                                pr, pg, pb, colorsRgb, numColors, noise, w, h, dScale,
                                ::densityFn)
                        }
                        segLen = 0
                    }
                }
                // Flush remaining segment
                if (segLen > 1) {
                    drawSegment(canvas, paint, path, segPtsX, segPtsY, segLen,
                        pass, colorMode, variableWidth, baseLineWidth, alphaInt,
                        pr, pg, pb, colorsRgb, numColors, noise, w, h, dScale,
                        ::densityFn)
                }

                d += spacing
            }
        }
    }

    private fun drawSegment(
        canvas: Canvas, paint: Paint, path: Path,
        ptsX: FloatArray, ptsY: FloatArray, len: Int,
        pass: Int, colorMode: String, variableWidth: Boolean,
        baseLineWidth: Float, alphaInt: Int,
        pr: Int, pg: Int, pb: Int,
        colorsRgb: Array<IntArray>, numColors: Int,
        noise: SimplexNoise, w: Float, h: Float, dScale: Float,
        densityFn: (Float, Float, Int) -> Float
    ) {
        var cr = pr; var cg = pg; var cb = pb

        // Per-segment color for density/noise modes
        if (colorMode == "palette-density" || colorMode == "palette-noise") {
            val midIdx = len / 2
            val mx = ptsX[midIdx]; val my = ptsY[midIdx]
            val tc = if (colorMode == "palette-density") {
                densityFn(mx, my, pass)
            } else {
                val nv = noise.noise2D(mx / w * 4f + 20f, my / h * 4f + 20f)
                max(0f, nv * 0.5f + 0.5f)
            }
            val ci = min(1f, tc) * (numColors - 1)
            val i0 = ci.toInt(); val i1 = min(numColors - 1, i0 + 1)
            val f = ci - i0
            cr = (colorsRgb[i0][0] + (colorsRgb[i1][0] - colorsRgb[i0][0]) * f).toInt()
            cg = (colorsRgb[i0][1] + (colorsRgb[i1][1] - colorsRgb[i0][1]) * f).toInt()
            cb = (colorsRgb[i0][2] + (colorsRgb[i1][2] - colorsRgb[i0][2]) * f).toInt()
        }

        // Variable width
        if (variableWidth) {
            val midIdx = len / 2
            val den = densityFn(ptsX[midIdx], ptsY[midIdx], pass)
            paint.strokeWidth = baseLineWidth * (0.5f + den * 2f)
        } else {
            paint.strokeWidth = baseLineWidth
        }

        paint.color = Color.argb(alphaInt, cr.coerceIn(0, 255), cg.coerceIn(0, 255), cb.coerceIn(0, 255))

        path.reset()
        path.moveTo(ptsX[0], ptsY[0])
        for (i in 1 until len) {
            path.lineTo(ptsX[i], ptsY[i])
        }
        canvas.drawPath(path, paint)
    }

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float {
        val passes = (params["passCount"] as? Number)?.toInt() ?: 3
        val spacing = (params["lineSpacing"] as? Number)?.toFloat() ?: 8f
        return (passes / 6f * 8f / spacing).coerceIn(0.2f, 1f)
    }
}
