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
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Halftone dots pattern with SVG vector support.
 *
 * Dots are laid out on a rotated grid and sized according to a simplex noise
 * luminance field. Supports circle, square, and diamond dot shapes.
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
        "Dot shapes include circles, squares, and diamonds. The generator also produces " +
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
        val gridSize = (params["gridSpacing"] as? Number)?.toFloat() ?: 22f
        val maxDotSize = (params["maxRadius"] as? Number)?.toFloat() ?: 10f
        val angleDeg = (params["gridAngle"] as? Number)?.toFloat() ?: 0f
        val style = (params["dotShape"] as? String) ?: "circle"

        val animSpeed = (params["animSpeed"] as? Number)?.toFloat() ?: 0f
        val timeOff = time * animSpeed

        val noise = SimplexNoise(seed)
        val paletteColors = palette.colorInts()

        canvas.drawColor(Color.BLACK)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.style = Paint.Style.FILL
        }

        val angleRad = angleDeg * PI.toFloat() / 180f
        val cosA = cos(angleRad)
        val sinA = sin(angleRad)
        val diagonal = sqrt(w * w + h * h)
        val halfDiag = diagonal / 2f
        val cx = w / 2f
        val cy = h / 2f

        var gy = -halfDiag
        while (gy <= halfDiag) {
            var gx = -halfDiag
            while (gx <= halfDiag) {
                // Rotate grid point
                val px = cx + gx * cosA - gy * sinA
                val py = cy + gx * sinA + gy * cosA

                if (px < -maxDotSize || px > w + maxDotSize || py < -maxDotSize || py > h + maxDotSize) {
                    gx += gridSize
                    continue
                }

                // Noise-based size
                val nv = (noise.fbm(px / w * 4f + timeOff, py / h * 4f, 3) + 1f) * 0.5f
                val dotR = maxDotSize * nv * 0.5f

                if (dotR > 0.3f) {
                    val t = nv
                    paint.color = palette.lerpColor(t)

                    when (style) {
                        "square" -> canvas.drawRect(px - dotR, py - dotR, px + dotR, py + dotR, paint)
                        "diamond" -> {
                            val path = Path()
                            path.moveTo(px, py - dotR)
                            path.lineTo(px + dotR, py)
                            path.lineTo(px, py + dotR)
                            path.lineTo(px - dotR, py)
                            path.close()
                            canvas.drawPath(path, paint)
                        }
                        else -> canvas.drawCircle(px, py, dotR, paint)
                    }
                }

                gx += gridSize
            }
            gy += gridSize
        }
    }

    override fun renderVector(
        params: Map<String, Any>,
        seed: Int,
        palette: Palette
    ): List<SvgPath> {
        val w = 1080
        val h = 1080
        val gridSize = (params["gridSpacing"] as? Number)?.toFloat() ?: 22f
        val maxDotSize = (params["maxRadius"] as? Number)?.toFloat() ?: 10f
        val angleDeg = (params["gridAngle"] as? Number)?.toFloat() ?: 0f
        val style = (params["dotShape"] as? String) ?: "circle"

        val noise = SimplexNoise(seed)
        val paths = mutableListOf<SvgPath>()

        val angleRad = angleDeg * PI.toFloat() / 180f
        val cosA = cos(angleRad)
        val sinA = sin(angleRad)
        val diagonal = sqrt(w.toFloat() * w + h.toFloat() * h)
        val halfDiag = diagonal / 2f
        val cx = w / 2f
        val cy = h / 2f

        var gy = -halfDiag
        while (gy <= halfDiag) {
            var gx = -halfDiag
            while (gx <= halfDiag) {
                val px = cx + gx * cosA - gy * sinA
                val py = cy + gx * sinA + gy * cosA

                if (px < -maxDotSize || px > w + maxDotSize || py < -maxDotSize || py > h + maxDotSize) {
                    gx += gridSize
                    continue
                }

                val nv = (noise.fbm(px / w * 4f, py / h * 4f, 3) + 1f) * 0.5f
                val dotR = maxDotSize * nv * 0.5f

                if (dotR > 0.3f) {
                    val colorInt = palette.lerpColor(nv)
                    val hex = String.format("#%06X", 0xFFFFFF and colorInt)

                    val d = when (style) {
                        "square" -> SvgBuilder.rect(px - dotR, py - dotR, dotR * 2, dotR * 2)
                        "diamond" -> SvgBuilder.polygon(listOf(
                            px to (py - dotR),
                            (px + dotR) to py,
                            px to (py + dotR),
                            (px - dotR) to py
                        ))
                        else -> SvgBuilder.circle(px, py, dotR)
                    }

                    paths.add(SvgPath(d = d, fill = hex, strokeWidth = 0f))
                }

                gx += gridSize
            }
            gy += gridSize
        }

        return paths
    }

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float {
        val gridSize = (params["gridSpacing"] as? Number)?.toFloat() ?: 22f
        return (22f / gridSize).coerceIn(0.2f, 1f)
    }
}
