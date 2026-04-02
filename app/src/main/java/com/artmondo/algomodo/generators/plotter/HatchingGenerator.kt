package com.artmondo.algomodo.generators.plotter

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import com.artmondo.algomodo.core.rng.SeededRNG
import com.artmondo.algomodo.core.rng.SimplexNoise
import com.artmondo.algomodo.data.palettes.Palette
import com.artmondo.algomodo.generators.Generator
import com.artmondo.algomodo.generators.ParamGroup
import com.artmondo.algomodo.generators.Parameter
import com.artmondo.algomodo.generators.Quality
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Cross-hatching renderer.
 *
 * Draws parallel lines at configurable angles. Line density varies spatially
 * based on simplex noise, creating tonal shading reminiscent of engraving or
 * pen-and-ink illustration.
 */
class HatchingGenerator : Generator {

    override val id = "hatching"
    override val family = "plotter"
    override val styleName = "Cross-Hatching"
    override val definition =
        "Parallel hatching lines at multiple angles with noise-driven density variation."
    override val algorithmNotes =
        "For each layer, parallel lines are swept across the canvas at the specified angle. " +
        "Each line's spacing is modulated by a simplex noise field so that darker noise " +
        "regions produce tighter spacing (more ink). Multiple layers at different angles " +
        "create cross-hatching."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema = listOf(
        Parameter.SelectParam("Style", "style", ParamGroup.COMPOSITION, "parallel: broken lines | crosshatch: perpendicular overlapping | contour: follows topography | wavy: sine-modulated | scribble: curved arcs", listOf("parallel", "crosshatch", "contour", "wavy", "scribble"), "parallel"),
        Parameter.NumberParam("Layers", "layers", ParamGroup.COMPOSITION, "Number of angle passes; each uses the next palette color", 1f, 4f, 1f, 2f),
        Parameter.NumberParam("Line Spacing", "baseSpacing", ParamGroup.GEOMETRY, null, 3f, 28f, 1f, 7f),
        Parameter.NumberParam("Base Angle", "angle", ParamGroup.GEOMETRY, null, 0f, 175f, 5f, 45f),
        Parameter.NumberParam("Angle Step", "angleStep", ParamGroup.GEOMETRY, "Degrees between each layer", 10f, 90f, 5f, 45f),
        Parameter.NumberParam("Density Scale", "densityScale", ParamGroup.COMPOSITION, "Spatial scale of the noise density field", 0.3f, 6f, 0.1f, 2.2f),
        Parameter.NumberParam("Density Contrast", "densityContrast", ParamGroup.TEXTURE, "Exponent sharpening light/dark areas", 0.5f, 4f, 0.25f, 1.8f),
        Parameter.NumberParam("Wobble", "wobble", ParamGroup.TEXTURE, "Per-segment hand-drawn jitter", 0f, 6f, 0.25f, 1.5f),
        Parameter.NumberParam("Line Width", "lineWidth", ParamGroup.TEXTURE, null, 0.25f, 3f, 0.25f, 0.75f),
        Parameter.BooleanParam("Taper", "taper", ParamGroup.TEXTURE, "Vary line width with density — thicker in dark areas, thinner in light", false),
        Parameter.SelectParam("Background", "background", ParamGroup.COLOR, null, listOf("white", "cream", "dark"), "cream"),
        Parameter.NumberParam("Anim Speed", "animSpeed", ParamGroup.FLOW_MOTION, "Speed at which the density field drifts over time (0 = static)", 0f, 1f, 0.05f, 0.12f)
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "style" to "parallel",
        "layers" to 2f,
        "baseSpacing" to 7f,
        "angle" to 45f,
        "angleStep" to 45f,
        "densityScale" to 2.2f,
        "densityContrast" to 1.8f,
        "wobble" to 1.5f,
        "lineWidth" to 0.75f,
        "taper" to false,
        "background" to "cream",
        "animSpeed" to 0.12f
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

        // Extract all parameters
        val style = (params["style"] as? String) ?: "parallel"
        val layers = ((params["layers"] as? Number)?.toInt() ?: 2).coerceAtLeast(1)
        val baseSpacing = ((params["baseSpacing"] as? Number)?.toFloat() ?: 7f).coerceAtLeast(3f)
        val angle = (params["angle"] as? Number)?.toFloat() ?: 45f
        val angleStep = (params["angleStep"] as? Number)?.toFloat() ?: 45f
        val densityScale = (params["densityScale"] as? Number)?.toFloat() ?: 2.2f
        val densityContrast = (params["densityContrast"] as? Number)?.toFloat() ?: 1.8f
        val wobble = (params["wobble"] as? Number)?.toFloat() ?: 1.5f
        val lineWidth = (params["lineWidth"] as? Number)?.toFloat() ?: 0.75f
        val taper = (params["taper"] as? Boolean) ?: false
        val background = (params["background"] as? String) ?: "cream"
        val animSpeed = (params["animSpeed"] as? Number)?.toFloat() ?: 0.12f

        val rng = SeededRNG(seed)
        val noise = SimplexNoise(seed)

        // Background
        canvas.drawColor(BG[background] ?: BG["cream"]!!)

        val isDark = background == "dark"
        val diagonal = sqrt(w * w + h * h)
        val tOff = time * animSpeed * 0.3f

        // Palette colors decomposed into RGB
        val paletteColors = palette.colorInts()
        val colorsRgb = Array(paletteColors.size) {
            intArrayOf(Color.red(paletteColors[it]), Color.green(paletteColors[it]), Color.blue(paletteColors[it]))
        }

        // Density sampling function matching the web version
        fun sampleDensity(px: Float, py: Float): Float {
            // +5 offset keeps canvas center away from FBM origin (which is always 0)
            val n = noise.fbm(
                (px / w - 0.5f) * densityScale + 5f + tOff,
                (py / h - 0.5f) * densityScale + 5f + tOff * 0.7f,
                4, 2.0f, 0.5f
            )
            val v = max(0f, n * 0.5f + 0.5f) // 0-1
            return v.pow(densityContrast)
        }

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.style = Paint.Style.STROKE
            strokeWidth = lineWidth
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        val path = Path()

        // Helper: draw parallel (or wavy) lines at a given angle for one layer
        fun drawParallelLayer(layer: Int, layerAngle: Float, wavyAmp: Float) {
            val cosA = cos(layerAngle)
            val sinA = sin(layerAngle)
            val cosP = cos(layerAngle + PI.toFloat() / 2f)
            val sinP = sin(layerAngle + PI.toFloat() / 2f)

            val rgb = colorsRgb[layer % colorsRgb.size]
            val alpha = if (isDark) 217 else 191 // 0.85*255 ~ 217, 0.75*255 ~ 191
            paint.color = Color.argb(alpha, rgb[0], rgb[1], rgb[2])

            val numLines = ceil(diagonal / baseSpacing).toInt() + 2
            val segStep = 4f

            for (li in -numLines / 2..numLines / 2) {
                val ox = w / 2f + cosP * li * baseSpacing
                val oy = h / 2f + sinP * li * baseSpacing

                val numSegs = ceil(diagonal / segStep).toInt() + 2
                var drawing = false

                path.reset()
                for (si in 0..numSegs) {
                    val along = si * segStep - diagonal / 2f
                    // Wavy: sine offset perpendicular to line direction
                    val waveOff = if (wavyAmp > 0f) {
                        sin(along * 0.02f + li * 0.5f) * wavyAmp * baseSpacing
                    } else 0f
                    val px = ox + cosA * along + cosP * waveOff
                    val py = oy + sinA * along + sinP * waveOff

                    if (px < -wobble - 5f || px > w + wobble + 5f ||
                        py < -wobble - 5f || py > h + wobble + 5f
                    ) {
                        if (drawing) {
                            canvas.drawPath(path, paint)
                            path.reset()
                            drawing = false
                        }
                        continue
                    }

                    val density = sampleDensity(px, py)
                    val threshold = layer * (0.25f / max(layers - 1, 1).toFloat())
                    val shouldDraw = density > threshold

                    if (taper) {
                        paint.strokeWidth = lineWidth * (0.3f + density * 1.4f)
                    }

                    if (shouldDraw) {
                        if (!drawing) {
                            path.moveTo(
                                px + (rng.random() - 0.5f) * wobble,
                                py + (rng.random() - 0.5f) * wobble
                            )
                            drawing = true
                        } else {
                            // Wobbly segment via quadratic curve
                            if (wobble < 0.1f) {
                                path.lineTo(px, py)
                            } else {
                                val prevPx = px - cosA * segStep
                                val prevPy = py - sinA * segStep
                                val mx = (prevPx + px) / 2f + (rng.random() - 0.5f) * wobble
                                val my = (prevPy + py) / 2f + (rng.random() - 0.5f) * wobble
                                path.quadTo(
                                    mx, my,
                                    px + (rng.random() - 0.5f) * wobble * 0.5f,
                                    py + (rng.random() - 0.5f) * wobble * 0.5f
                                )
                            }
                        }
                    } else {
                        if (drawing) {
                            canvas.drawPath(path, paint)
                            path.reset()
                            drawing = false
                        }
                    }
                }
                if (drawing) {
                    canvas.drawPath(path, paint)
                    path.reset()
                }
            }
        }

        // -- Parallel mode --
        when (style) {
            "parallel" -> {
                for (layer in 0 until layers) {
                    val layerAngle = (angle + layer * angleStep) * PI.toFloat() / 180f
                    drawParallelLayer(layer, layerAngle, 0f)
                }
            }

            // -- Crosshatch mode --
            "crosshatch" -> {
                // Each layer draws two perpendicular sets of lines
                for (layer in 0 until layers) {
                    val baseAngle = (angle + layer * angleStep) * PI.toFloat() / 180f
                    drawParallelLayer(layer, baseAngle, 0f)
                    drawParallelLayer(layer, baseAngle + PI.toFloat() / 2f, 0f)
                }
            }

            // -- Wavy mode --
            "wavy" -> {
                for (layer in 0 until layers) {
                    val layerAngle = (angle + layer * angleStep) * PI.toFloat() / 180f
                    drawParallelLayer(layer, layerAngle, 1.5f)
                }
            }

            // -- Contour mode --
            "contour" -> {
                val strokeLen = baseSpacing * 2.5f
                val gridStep = baseSpacing * 0.85f
                val eps = w * 0.003f

                for (layer in 0 until layers) {
                    val rgb = colorsRgb[layer % colorsRgb.size]
                    val alpha = if (isDark) 204 else 179 // 0.8*255 ~ 204, 0.7*255 ~ 179
                    paint.color = Color.argb(alpha, rgb[0], rgb[1], rgb[2])
                    paint.strokeWidth = lineWidth

                    // Offset grid per layer to fill gaps
                    val oxOff = (layer * gridStep * 0.5f) % gridStep
                    val oyOff = (layer * gridStep * 0.7f) % gridStep

                    var py = oyOff
                    while (py < h) {
                        var px = oxOff
                        while (px < w) {
                            val density = sampleDensity(px, py)
                            val threshold = layer * (0.22f / max(layers - 1, 1).toFloat())
                            if (density < threshold + 0.05f) {
                                px += gridStep
                                continue
                            }

                            // Gradient of noise field (consistent centered formula)
                            val gx = noise.fbm(
                                ((px + eps) / w - 0.5f) * densityScale + 5f + tOff,
                                (py / h - 0.5f) * densityScale + 5f + tOff * 0.7f,
                                3, 2f, 0.5f
                            ) - noise.fbm(
                                ((px - eps) / w - 0.5f) * densityScale + 5f + tOff,
                                (py / h - 0.5f) * densityScale + 5f + tOff * 0.7f,
                                3, 2f, 0.5f
                            )
                            val gy = noise.fbm(
                                (px / w - 0.5f) * densityScale + 5f + tOff,
                                ((py + eps) / h - 0.5f) * densityScale + 5f + tOff * 0.7f,
                                3, 2f, 0.5f
                            ) - noise.fbm(
                                (px / w - 0.5f) * densityScale + 5f + tOff,
                                ((py - eps) / h - 0.5f) * densityScale + 5f + tOff * 0.7f,
                                3, 2f, 0.5f
                            )

                            val len = sqrt(gx * gx + gy * gy) + 0.0001f
                            // Perpendicular to gradient = contour direction
                            val tx = -gy / len
                            val ty = gx / len

                            val half = strokeLen / 2f * density
                            val x0 = px - tx * half + (rng.random() - 0.5f) * wobble
                            val y0 = py - ty * half + (rng.random() - 0.5f) * wobble
                            val x1 = px + tx * half + (rng.random() - 0.5f) * wobble
                            val y1 = py + ty * half + (rng.random() - 0.5f) * wobble

                            val mx = (x0 + x1) / 2f + (rng.random() - 0.5f) * wobble * 2f
                            val my = (y0 + y1) / 2f + (rng.random() - 0.5f) * wobble * 2f

                            path.reset()
                            path.moveTo(x0, y0)
                            path.quadTo(mx, my, x1, y1)
                            canvas.drawPath(path, paint)

                            px += gridStep
                        }
                        py += gridStep
                    }
                }
            }

            // -- Scribble mode --
            "scribble" -> {
                val totalStrokes = round(w * h / (baseSpacing * baseSpacing * 4f)).toInt()

                for (layer in 0 until layers) {
                    val rgb = colorsRgb[layer % colorsRgb.size]
                    val alpha = if (isDark) 191 else 166 // 0.75*255 ~ 191, 0.65*255 ~ 166
                    paint.color = Color.argb(alpha, rgb[0], rgb[1], rgb[2])
                    paint.strokeWidth = lineWidth

                    val layerStrokes = round(totalStrokes.toFloat() / layers).toInt()
                    val threshold = layer * (0.2f / max(layers - 1, 1).toFloat())

                    var attempts = 0
                    var drawn = 0
                    while (drawn < layerStrokes && attempts < layerStrokes * 10) {
                        attempts++
                        val px = rng.random() * w
                        val py = rng.random() * h
                        val density = sampleDensity(px, py)

                        if (rng.random() > max(0.25f, density - threshold)) continue

                        val strokeLength = baseSpacing * (0.8f + density * 2.5f)
                        val strokeAngle = (angle + layer * angleStep) * PI.toFloat() / 180f +
                            (rng.random() - 0.5f) * 0.6f

                        val dx = cos(strokeAngle) * strokeLength
                        val dy = sin(strokeAngle) * strokeLength
                        val cx = px + (rng.random() - 0.5f) * wobble * 3f
                        val cy = py + (rng.random() - 0.5f) * wobble * 3f

                        path.reset()
                        path.moveTo(px - dx / 2f, py - dy / 2f)
                        path.quadTo(cx, cy, px + dx / 2f, py + dy / 2f)
                        canvas.drawPath(path, paint)
                        drawn++
                    }
                }
            }
        }
    }

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float {
        val baseSpacing = (params["baseSpacing"] as? Number)?.toFloat() ?: 7f
        val layers = (params["layers"] as? Number)?.toInt() ?: 2
        return (layers / (baseSpacing * 0.5f)).coerceIn(0.2f, 1f)
    }
}
