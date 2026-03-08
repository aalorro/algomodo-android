package com.artmondo.algomodo.generators.plotter

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.artmondo.algomodo.core.rng.SeededRNG
import com.artmondo.algomodo.core.rng.SimplexNoise
import com.artmondo.algomodo.data.palettes.Palette
import com.artmondo.algomodo.generators.Generator
import com.artmondo.algomodo.generators.ParamGroup
import com.artmondo.algomodo.generators.Parameter
import com.artmondo.algomodo.generators.Quality
import com.artmondo.algomodo.rendering.SvgPath
import kotlin.math.sqrt

/**
 * Stippled dot placement using weighted Poisson-disk sampling.
 *
 * Places thousands of small dots across the canvas. Three distribution modes
 * control clustering: uniform random, noise-weighted density, and clustered
 * groups. The result resembles pen-plotter stipple shading.
 */
class StipplingGenerator : Generator {

    override val id = "stippling"
    override val family = "plotter"
    override val styleName = "Stippling"
    override val definition =
        "Stippled dots placed via weighted Poisson-disk sampling to create tonal shading."
    override val algorithmNotes =
        "Dots are placed using a dart-throwing approach with minimum-distance rejection. " +
        "In 'weighted' mode, simplex noise modulates the local acceptance radius so denser " +
        "regions appear darker. 'Clustered' mode uses multiple seed points with falloff."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema = listOf(
        Parameter.NumberParam("Point Count", "pointCount", ParamGroup.COMPOSITION, null, 500f, 30000f, 500f, 8000f),
        Parameter.NumberParam("Density Scale", "densityScale", ParamGroup.COMPOSITION, "Spatial scale of the density field", 0.3f, 8f, 0.1f, 2.5f),
        Parameter.NumberParam("Density Contrast", "densityContrast", ParamGroup.TEXTURE, "Exponent sharpening dense vs sparse regions", 0.5f, 5f, 0.25f, 2.0f),
        Parameter.NumberParam("Min Dot Size", "minDotSize", ParamGroup.GEOMETRY, null, 0.5f, 4f, 0.25f, 1.0f),
        Parameter.NumberParam("Max Dot Size", "maxDotSize", ParamGroup.GEOMETRY, "Dots are larger in dense regions", 1f, 10f, 0.5f, 4.5f),
        Parameter.NumberParam("Min Spacing", "minDistance", ParamGroup.GEOMETRY, "Minimum gap between dot centres", 1f, 30f, 1f, 5f),
        Parameter.SelectParam("Dot Shape", "dotShape", ParamGroup.GEOMETRY, "Shape of each stipple mark", listOf("circle", "square", "diamond", "dash", "star"), "circle"),
        Parameter.NumberParam("Size Variation", "sizeVariation", ParamGroup.TEXTURE, "Random size jitter for a more organic feel — 0 = uniform, 1 = very varied", 0f, 1f, 0.1f, 0f),
        Parameter.SelectParam("Density Style", "densityStyle", ParamGroup.COMPOSITION, "Shape of the density field — fbm: standard noise | ridged: ridge lines | turbulent: creases | radial: center-focused gradient", listOf("fbm", "ridged", "turbulent", "radial"), "fbm"),
        Parameter.SelectParam("Color Mode", "colorMode", ParamGroup.COLOR, "palette-density: color follows noise | multi-layer: separate passes per color", listOf("palette-density", "palette-position", "monochrome", "multi-layer"), "palette-density"),
        Parameter.NumberParam("Opacity", "opacity", ParamGroup.COLOR, null, 0.2f, 1.0f, 0.05f, 0.85f),
        Parameter.SelectParam("Background", "background", ParamGroup.COLOR, null, listOf("white", "cream", "dark"), "cream"),
        Parameter.NumberParam("Anim Speed", "animSpeed", ParamGroup.FLOW_MOTION, "Density field drift speed — 0 = static", 0f, 1f, 0.05f, 0f)
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "pointCount" to 8000f,
        "densityScale" to 2.5f,
        "densityContrast" to 2.0f,
        "minDotSize" to 1.0f,
        "maxDotSize" to 4.5f,
        "minDistance" to 5f,
        "dotShape" to "circle",
        "sizeVariation" to 0f,
        "densityStyle" to "fbm",
        "colorMode" to "palette-density",
        "opacity" to 0.85f,
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
        val dotCount = (params["pointCount"] as? Number)?.toInt() ?: 8000
        val minDotSize = (params["minDotSize"] as? Number)?.toFloat() ?: 1.0f
        val maxDotSize = (params["maxDotSize"] as? Number)?.toFloat() ?: 4.5f
        val distribution = "uniform"

        val animSpeed = (params["animSpeed"] as? Number)?.toFloat() ?: 0f
        val timeOff = time * animSpeed

        val rng = SeededRNG(seed)
        val noise = SimplexNoise(seed)
        val paletteColors = palette.colorInts()

        canvas.drawColor(Color.BLACK)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // Minimum distance for Poisson-disk rejection
        val area = w * h
        val minDist = sqrt(area / (dotCount * 1.5f))

        // Store placed dots for rejection sampling
        val cellSize = minDist / 1.414f
        val gridW = (w / cellSize).toInt() + 1
        val gridH = (h / cellSize).toInt() + 1
        val grid = arrayOfNulls<FloatArray>(gridW * gridH)

        val maxAttempts = dotCount * 8
        var placed = 0

        for (attempt in 0 until maxAttempts) {
            if (placed >= dotCount) break

            val x = rng.range(0f, w)
            val y = rng.range(0f, h)

            // Compute local acceptance radius based on distribution mode
            val localMin = when (distribution) {
                "weighted" -> {
                    val nv = (noise.fbm(x / w * 4f + timeOff, y / h * 4f, 4) + 1f) * 0.5f
                    minDist * (0.3f + nv * 1.4f)
                }
                "clustered" -> {
                    // Create cluster centers
                    val cx = w * 0.5f
                    val cy = h * 0.5f
                    val dx = (x - cx) / w
                    val dy = (y - cy) / h
                    val dist = sqrt(dx * dx + dy * dy)
                    val nv = (noise.noise2D(x / w * 6f + timeOff, y / h * 6f) + 1f) * 0.5f
                    minDist * (0.2f + dist * 2f) * (0.5f + nv)
                }
                else -> minDist
            }

            // Check against nearby dots in grid
            val gx = (x / cellSize).toInt().coerceIn(0, gridW - 1)
            val gy = (y / cellSize).toInt().coerceIn(0, gridH - 1)

            var tooClose = false
            val searchRadius = 2
            for (dy in -searchRadius..searchRadius) {
                for (dx in -searchRadius..searchRadius) {
                    val nx = gx + dx
                    val ny = gy + dy
                    if (nx < 0 || nx >= gridW || ny < 0 || ny >= gridH) continue
                    val cell = grid[ny * gridW + nx]
                    if (cell != null) {
                        val ddx = x - cell[0]
                        val ddy = y - cell[1]
                        if (ddx * ddx + ddy * ddy < localMin * localMin) {
                            tooClose = true
                            break
                        }
                    }
                }
                if (tooClose) break
            }

            if (!tooClose) {
                grid[gy * gridW + gx] = floatArrayOf(x, y)
                val t = (x + y) / (w + h)
                paint.color = paletteColors[(placed % paletteColors.size)]
                paint.alpha = 200 + rng.integer(0, 55)
                val dotSize = rng.range(minDotSize, maxDotSize)
                canvas.drawCircle(x, y, dotSize, paint)
                placed++
            }
        }
    }

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float {
        val dots = (params["pointCount"] as? Number)?.toInt() ?: 8000
        return (dots / 30000f).coerceIn(0.2f, 1f)
    }
}
