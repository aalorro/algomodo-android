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
import com.artmondo.algomodo.rendering.SvgBuilder
import com.artmondo.algomodo.rendering.SvgPath
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Halftone dots pattern with SVG vector support.
 *
 * Dots are laid out on a rotated grid and sized according to a simplex noise
 * luminance field. Supports circle, square, diamond, and line dot shapes.
 * Grid types include square, hex (offset rows), and diamond (45-degree rotated).
 */
class PlotterHalftoneDotsGenerator : Generator {

    override val id = "plotter-halftone-dots"
    override val family = "plotter"
    override val styleName = "Halftone Dots"
    override val definition =
        "Halftone-style dot grid where dot size maps to noise-derived luminance."
    override val algorithmNotes =
        "A regular grid (optionally rotated) covers the canvas. At each grid point, " +
        "simplex noise determines a 'luminance' value that scales the dot radius. " +
        "Dot shapes include circles, squares, diamonds, and lines. The generator also produces " +
        "SVG output for pen-plotter compatibility."
    override val supportsVector = true
    override val supportsAnimation = true

    override val parameterSchema = listOf(
        Parameter.NumberParam("Grid Spacing", "gridSpacing", ParamGroup.COMPOSITION, "Distance between dot centres in pixels", 8f, 60f, 2f, 22f),
        Parameter.SelectParam("Grid Type", "gridType", ParamGroup.COMPOSITION, "square: standard | hex: offset rows (classic halftone) | diamond: 45 deg rotated", listOf("square", "hex", "diamond"), "square"),
        Parameter.NumberParam("Grid Angle", "gridAngle", ParamGroup.COMPOSITION, "Rotation of the entire dot grid in degrees", 0f, 45f, 5f, 0f),
        Parameter.NumberParam("Max Radius", "maxRadius", ParamGroup.GEOMETRY, "Radius of the largest (densest) dot", 2f, 28f, 1f, 10f),
        Parameter.SelectParam("Dot Shape", "dotShape", ParamGroup.GEOMETRY, "circle: round | square: filled rect | diamond: rotated square | line: density-driven stroke", listOf("circle", "square", "diamond", "line"), "circle"),
        Parameter.NumberParam("Density Scale", "densityScale", ParamGroup.COMPOSITION, "Spatial scale of the noise density field", 0.5f, 8f, 0.25f, 2.5f),
        Parameter.NumberParam("Density Contrast", "densityContrast", ParamGroup.TEXTURE, "Gamma exponent sharpening dense vs sparse regions", 0.5f, 4f, 0.25f, 2.0f),
        Parameter.SelectParam("Color Mode", "colorMode", ParamGroup.COLOR, "invert: largest dots on low-density regions (negative halftone)", listOf("monochrome", "palette-density", "palette-position", "invert"), "palette-density"),
        Parameter.SelectParam("Background", "background", ParamGroup.COLOR, null, listOf("white", "cream", "dark"), "cream"),
        Parameter.NumberParam("Anim Speed", "animSpeed", ParamGroup.FLOW_MOTION, "Flowing density animation speed — 0 = static", 0f, 1f, 0.05f, 0f)
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "gridSpacing" to 22f,
        "gridType" to "square",
        "gridAngle" to 0f,
        "maxRadius" to 10f,
        "dotShape" to "circle",
        "densityScale" to 2.5f,
        "densityContrast" to 2.0f,
        "colorMode" to "palette-density",
        "background" to "cream",
        "animSpeed" to 0f
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

        // Read all parameters
        val spacing = max(4f, (params["gridSpacing"] as? Number)?.toFloat() ?: 22f)
        val maxR = min(spacing * 0.5f, (params["maxRadius"] as? Number)?.toFloat() ?: 10f)
        val dScale = (params["densityScale"] as? Number)?.toFloat() ?: 2.5f
        val dContrast = (params["densityContrast"] as? Number)?.toFloat() ?: 2.0f
        val colorMode = (params["colorMode"] as? String) ?: "palette-density"
        val gridType = (params["gridType"] as? String) ?: "square"
        val gridAngle = ((params["gridAngle"] as? Number)?.toFloat() ?: 0f) * PI.toFloat() / 180f
        val dotShape = (params["dotShape"] as? String) ?: "circle"
        val animSpeed = (params["animSpeed"] as? Number)?.toFloat() ?: 0f
        val background = (params["background"] as? String) ?: "cream"

        // Background color
        val bgColor = when (background) {
            "white" -> Color.rgb(248, 248, 245)
            "cream" -> Color.rgb(242, 234, 216)
            "dark" -> Color.rgb(14, 14, 14)
            else -> Color.rgb(242, 234, 216)
        }
        val isDark = background == "dark"

        canvas.drawColor(bgColor)

        val noise = SimplexNoise(seed)
        val paletteColors = palette.colorInts()

        // Time-based density field offset
        val timeOff = time * animSpeed * 0.5f

        // Density function matching web: centered FBM + contrast exponent
        val densityFn = { x: Float, y: Float ->
            val n = noise.fbm(
                (x / w - 0.5f) * dScale + 5f + timeOff,
                (y / h - 0.5f) * dScale + 5f + timeOff * 0.7f,
                4, 2f, 0.5f
            )
            max(0f, n * 0.5f + 0.5f).pow(dContrast)
        }

        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // Grid rotation
        val cosA = cos(gridAngle)
        val sinA = sin(gridAngle)
        val hcx = w / 2f
        val hcy = h / 2f

        // Expand bounds to cover canvas after rotation
        val diag = sqrt(w * w + h * h)
        val margin = spacing
        val startX = -diag / 2f - margin
        val startY = -diag / 2f - margin
        val endX = diag / 2f + margin
        val endY = diag / 2f + margin

        val cols = ceil((endX - startX) / spacing).toInt() + 1
        val rows = ceil((endY - startY) / spacing).toInt() + 1

        // Alpha for dots
        val alpha = if (isDark) 224 else 217 // 0.88*255 ~ 224, 0.85*255 ~ 217

        for (row in 0 until rows) {
            for (col in 0 until cols) {
                var gx = startX + col * spacing
                var gy = startY + row * spacing

                // Grid type transforms
                when (gridType) {
                    "hex" -> {
                        if (row % 2 == 1) gx += spacing * 0.5f
                        gy *= 0.866f // sin(60 deg) vertical compression
                    }
                    "diamond" -> {
                        val dx = gx
                        val dy = gy
                        gx = (dx - dy) * 0.7071f
                        gy = (dx + dy) * 0.7071f
                    }
                }

                // Apply user grid angle rotation
                val rx = gx * cosA - gy * sinA + hcx
                val ry = gx * sinA + gy * cosA + hcy

                // Skip dots outside canvas
                if (rx < -maxR || rx > w + maxR || ry < -maxR || ry > h + maxR) continue

                var density = densityFn(rx, ry)
                if (colorMode == "invert") density = 1f - density
                val r = density * maxR
                if (r < 0.3f) continue

                // Determine color based on colorMode
                val cr: Int
                val cg: Int
                val cb: Int
                when (colorMode) {
                    "monochrome" -> {
                        if (isDark) {
                            cr = 220; cg = 220; cb = 220
                        } else {
                            cr = 30; cg = 30; cb = 30
                        }
                    }
                    "palette-position" -> {
                        val t = (rx / w) * 0.6f + (ry / h) * 0.4f
                        val ci = min(
                            floor(max(0f, t) * paletteColors.size).toInt(),
                            paletteColors.size - 1
                        )
                        val c = paletteColors[ci]
                        cr = Color.red(c)
                        cg = Color.green(c)
                        cb = Color.blue(c)
                    }
                    else -> {
                        // palette-density and invert: interpolate by raw density
                        val rawDensity = densityFn(rx, ry)
                        val ci = rawDensity * (paletteColors.size - 1)
                        val i0 = floor(ci).toInt()
                        val i1 = min(paletteColors.size - 1, i0 + 1)
                        val f = ci - i0
                        val c0 = paletteColors[i0.coerceIn(0, paletteColors.size - 1)]
                        val c1 = paletteColors[i1]
                        cr = (Color.red(c0) + (Color.red(c1) - Color.red(c0)) * f).toInt()
                        cg = (Color.green(c0) + (Color.green(c1) - Color.green(c0)) * f).toInt()
                        cb = (Color.blue(c0) + (Color.blue(c1) - Color.blue(c0)) * f).toInt()
                    }
                }

                val dotColor = Color.argb(alpha, cr, cg, cb)

                when (dotShape) {
                    "square" -> {
                        paint.style = Paint.Style.FILL
                        paint.color = dotColor
                        canvas.drawRect(rx - r, ry - r, rx + r, ry + r, paint)
                    }
                    "diamond" -> {
                        paint.style = Paint.Style.FILL
                        paint.color = dotColor
                        val path = Path()
                        path.moveTo(rx, ry - r)
                        path.lineTo(rx + r, ry)
                        path.lineTo(rx, ry + r)
                        path.lineTo(rx - r, ry)
                        path.close()
                        canvas.drawPath(path, paint)
                    }
                    "line" -> {
                        // Vertical stroke whose thickness = density
                        paint.style = Paint.Style.STROKE
                        paint.color = dotColor
                        paint.strokeWidth = max(0.5f, r * 0.8f)
                        canvas.drawLine(rx, ry - r, rx, ry + r, paint)
                    }
                    else -> {
                        // circle (default)
                        paint.style = Paint.Style.FILL
                        paint.color = dotColor
                        canvas.drawCircle(rx, ry, r, paint)
                    }
                }
            }
        }
    }

    override fun renderVector(
        params: Map<String, Any>,
        seed: Int,
        palette: Palette
    ): List<SvgPath> {
        val viewW = 1080f
        val viewH = 1080f

        val spacing = max(4f, (params["gridSpacing"] as? Number)?.toFloat() ?: 22f)
        val maxR = min(spacing * 0.5f, (params["maxRadius"] as? Number)?.toFloat() ?: 10f)
        val dScale = (params["densityScale"] as? Number)?.toFloat() ?: 2.5f
        val dContrast = (params["densityContrast"] as? Number)?.toFloat() ?: 2.0f
        val colorMode = (params["colorMode"] as? String) ?: "palette-density"
        val gridType = (params["gridType"] as? String) ?: "square"
        val gridAngle = ((params["gridAngle"] as? Number)?.toFloat() ?: 0f) * PI.toFloat() / 180f
        val dotShape = (params["dotShape"] as? String) ?: "circle"

        val noise = SimplexNoise(seed)
        val paletteColors = palette.colorInts()
        val paths = mutableListOf<SvgPath>()

        val densityFn = { x: Float, y: Float ->
            val n = noise.fbm(
                (x / viewW - 0.5f) * dScale + 5f,
                (y / viewH - 0.5f) * dScale + 5f,
                4, 2f, 0.5f
            )
            max(0f, n * 0.5f + 0.5f).pow(dContrast)
        }

        val cosA = cos(gridAngle)
        val sinA = sin(gridAngle)
        val hcx = viewW / 2f
        val hcy = viewH / 2f

        val diag = sqrt(viewW * viewW + viewH * viewH)
        val margin = spacing
        val startX = -diag / 2f - margin
        val startY = -diag / 2f - margin
        val endX = diag / 2f + margin
        val endY = diag / 2f + margin

        val cols = ceil((endX - startX) / spacing).toInt() + 1
        val rows = ceil((endY - startY) / spacing).toInt() + 1

        for (row in 0 until rows) {
            for (col in 0 until cols) {
                var gx = startX + col * spacing
                var gy = startY + row * spacing

                when (gridType) {
                    "hex" -> {
                        if (row % 2 == 1) gx += spacing * 0.5f
                        gy *= 0.866f
                    }
                    "diamond" -> {
                        val dx = gx
                        val dy = gy
                        gx = (dx - dy) * 0.7071f
                        gy = (dx + dy) * 0.7071f
                    }
                }

                val rx = gx * cosA - gy * sinA + hcx
                val ry = gx * sinA + gy * cosA + hcy

                if (rx < -maxR || rx > viewW + maxR || ry < -maxR || ry > viewH + maxR) continue

                var density = densityFn(rx, ry)
                if (colorMode == "invert") density = 1f - density
                val r = density * maxR
                if (r < 0.3f) continue

                val cr: Int
                val cg: Int
                val cb: Int
                when (colorMode) {
                    "monochrome" -> {
                        cr = 30; cg = 30; cb = 30
                    }
                    "palette-position" -> {
                        val t = (rx / viewW) * 0.6f + (ry / viewH) * 0.4f
                        val ci = min(
                            floor(max(0f, t) * paletteColors.size).toInt(),
                            paletteColors.size - 1
                        )
                        val c = paletteColors[ci]
                        cr = Color.red(c)
                        cg = Color.green(c)
                        cb = Color.blue(c)
                    }
                    else -> {
                        val rawDensity = densityFn(rx, ry)
                        val ci = rawDensity * (paletteColors.size - 1)
                        val i0 = floor(ci).toInt()
                        val i1 = min(paletteColors.size - 1, i0 + 1)
                        val f = ci - i0
                        val c0 = paletteColors[i0.coerceIn(0, paletteColors.size - 1)]
                        val c1 = paletteColors[i1]
                        cr = (Color.red(c0) + (Color.red(c1) - Color.red(c0)) * f).toInt()
                        cg = (Color.green(c0) + (Color.green(c1) - Color.green(c0)) * f).toInt()
                        cb = (Color.blue(c0) + (Color.blue(c1) - Color.blue(c0)) * f).toInt()
                    }
                }

                val hex = String.format("#%02X%02X%02X", cr, cg, cb)

                val d = when (dotShape) {
                    "square" -> SvgBuilder.rect(rx - r, ry - r, r * 2, r * 2)
                    "diamond" -> SvgBuilder.polygon(listOf(
                        rx to (ry - r),
                        (rx + r) to ry,
                        rx to (ry + r),
                        (rx - r) to ry
                    ))
                    "line" -> {
                        // Vertical line as SVG path
                        val lw = max(0.5f, r * 0.8f)
                        "M ${String.format("%.2f", rx)} ${String.format("%.2f", ry - r)} L ${String.format("%.2f", rx)} ${String.format("%.2f", ry + r)}"
                    }
                    else -> SvgBuilder.circle(rx, ry, r)
                }

                if (dotShape == "line") {
                    val lw = max(0.5f, r * 0.8f)
                    paths.add(SvgPath(d = d, fill = "none", stroke = hex, strokeWidth = lw))
                } else {
                    paths.add(SvgPath(d = d, fill = hex, strokeWidth = 0f))
                }
            }
        }

        return paths
    }

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float {
        val gridSize = (params["gridSpacing"] as? Number)?.toFloat() ?: 22f
        return (22f / gridSize).coerceIn(0.2f, 1f)
    }
}
