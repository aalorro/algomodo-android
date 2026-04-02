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
import com.artmondo.algomodo.rendering.SvgPath
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
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

    // ── Background color map matching web BG record ──────────────────────
    private fun bgColor(key: String): Int = when (key) {
        "white" -> Color.rgb(248, 248, 245)
        "dark"  -> Color.rgb(14, 14, 14)
        else    -> Color.rgb(242, 234, 216) // cream (default)
    }

    // ── Density-weighted Poisson disc sampling (matches web algorithm) ───
    private data class StipplePoint(val x: Float, val y: Float, val density: Float)

    private fun weightedPoissonDisc(
        w: Float, h: Float,
        minDist: Float,
        maxPoints: Int,
        rng: SeededRNG,
        densityFn: (Float, Float) -> Float
    ): List<StipplePoint> {
        val cellSize = max(1f, minDist / sqrt(2f))
        val gw = max(1, ceil(w / cellSize).toInt())
        val gh = max(1, ceil(h / cellSize).toInt())
        val grid = IntArray(gw * gh) { -1 }

        val points = mutableListOf<StipplePoint>()
        val active = mutableListOf<Int>()

        fun addPoint(x: Float, y: Float) {
            val d = densityFn(x, y)
            val idx = points.size
            points.add(StipplePoint(x, y, d))
            active.add(idx)
            val gx = min(gw - 1, floor(x / cellSize).toInt())
            val gy = min(gh - 1, floor(y / cellSize).toInt())
            grid[gy * gw + gx] = idx
        }

        // Seed with first random point
        addPoint(rng.random() * w, rng.random() * h)

        while (active.isNotEmpty() && points.size < maxPoints) {
            val ai = rng.integer(0, active.size - 1)
            val pt = points[active[ai]]

            // Adaptive minimum distance: moderate ratio so whole canvas fills
            val localMinDist = minDist * (0.65f + (1f - pt.density) * 0.55f)

            var found = false
            for (k in 0 until 25) {
                val angle = rng.random() * PI.toFloat() * 2f
                val dist = localMinDist * (1f + rng.random() * 1.5f)
                val nx = pt.x + cos(angle) * dist
                val ny = pt.y + sin(angle) * dist
                if (nx < 0f || nx >= w || ny < 0f || ny >= h) continue

                val gnx = min(gw - 1, floor(nx / cellSize).toInt())
                val gny = min(gh - 1, floor(ny / cellSize).toInt())
                val sr = ceil(localMinDist / cellSize).toInt() + 1

                var ok = true
                for (sx in max(0, gnx - sr)..min(gw - 1, gnx + sr)) {
                    if (!ok) break
                    for (sy in max(0, gny - sr)..min(gh - 1, gny + sr)) {
                        val pi = grid[sy * gw + sx]
                        if (pi >= 0) {
                            val dx = nx - points[pi].x
                            val dy = ny - points[pi].y
                            if (dx * dx + dy * dy < localMinDist * localMinDist) {
                                ok = false
                                break
                            }
                        }
                    }
                }

                if (ok) {
                    addPoint(nx, ny)
                    found = true
                    break
                }
            }

            if (!found) active.removeAt(ai)
        }

        return points
    }

    // ── Draw a single stipple mark ───────────────────────────────────────
    private fun drawMark(
        canvas: Canvas,
        paint: Paint,
        dotShape: String,
        px: Float, py: Float,
        radius: Float,
        rng: SeededRNG
    ) {
        when (dotShape) {
            "square" -> {
                canvas.drawRect(
                    px - radius, py - radius,
                    px + radius, py + radius,
                    paint
                )
            }
            "diamond" -> {
                val path = Path()
                path.moveTo(px, py - radius)
                path.lineTo(px + radius, py)
                path.lineTo(px, py + radius)
                path.lineTo(px - radius, py)
                path.close()
                canvas.drawPath(path, paint)
            }
            "dash" -> {
                val a = rng.random() * PI.toFloat()
                val dx = cos(a) * radius * 1.5f
                val dy = sin(a) * radius * 1.5f
                val strokePaint = Paint(paint)
                strokePaint.style = Paint.Style.STROKE
                strokePaint.strokeWidth = radius * 0.7f
                strokePaint.strokeCap = Paint.Cap.ROUND
                canvas.drawLine(px - dx, py - dy, px + dx, py + dy, strokePaint)
            }
            "star" -> {
                val path = Path()
                for (i in 0 until 5) {
                    val outerA = (i * 2f * PI.toFloat() / 5f) - PI.toFloat() / 2f
                    val innerA = outerA + PI.toFloat() / 5f
                    val ox = px + cos(outerA) * radius
                    val oy = py + sin(outerA) * radius
                    val ix = px + cos(innerA) * radius * 0.4f
                    val iy = py + sin(innerA) * radius * 0.4f
                    if (i == 0) path.moveTo(ox, oy) else path.lineTo(ox, oy)
                    path.lineTo(ix, iy)
                }
                path.close()
                canvas.drawPath(path, paint)
            }
            else -> { // "circle"
                canvas.drawCircle(px, py, radius, paint)
            }
        }
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

        // ── Read all parameters ──────────────────────────────────────────
        val pointCount = (params["pointCount"] as? Number)?.toInt() ?: 8000
        val densityScale = (params["densityScale"] as? Number)?.toFloat() ?: 2.5f
        val densityContrast = (params["densityContrast"] as? Number)?.toFloat() ?: 2.0f
        val minDotSize = (params["minDotSize"] as? Number)?.toFloat() ?: 1.0f
        val maxDotSize = (params["maxDotSize"] as? Number)?.toFloat() ?: 4.5f
        val minDistance = (params["minDistance"] as? Number)?.toFloat() ?: 5f
        val dotShape = (params["dotShape"] as? String) ?: "circle"
        val sizeVariation = (params["sizeVariation"] as? Number)?.toFloat() ?: 0f
        val densityStyle = (params["densityStyle"] as? String) ?: "fbm"
        val colorMode = (params["colorMode"] as? String) ?: "palette-density"
        val opacity = (params["opacity"] as? Number)?.toFloat() ?: 0.85f
        val background = (params["background"] as? String) ?: "cream"
        val animSpeed = (params["animSpeed"] as? Number)?.toFloat() ?: 0f

        val timeOff = time * animSpeed

        val rng = SeededRNG(seed)
        val noise = SimplexNoise(seed)
        val paletteColors = palette.colorInts()

        // ── Background ───────────────────────────────────────────────────
        canvas.drawColor(bgColor(background))

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }

        // ── Density function (matches web densityFn) ─────────────────────
        val densityFn: (Float, Float) -> Float = { x, y ->
            val nx = (x / w - 0.5f) * densityScale + 5f + timeOff
            val ny = (y / h - 0.5f) * densityScale + 5f

            val n: Float = when (densityStyle) {
                "ridged" -> {
                    val raw = noise.fbm(nx, ny, 5, 2.0f, 0.5f)
                    val ridge = 1f - abs(raw)
                    ridge * ridge
                }
                "turbulent" -> {
                    abs(noise.fbm(nx, ny, 5, 2.0f, 0.5f))
                }
                "radial" -> {
                    val dx = x / w - 0.5f
                    val dy = y / h - 0.5f
                    val dist = sqrt(dx * dx + dy * dy) * 2f
                    val noiseVal = noise.fbm(nx, ny, 3, 2.0f, 0.5f) * 0.3f
                    max(0f, 1f - dist + noiseVal)
                }
                else -> { // "fbm"
                    noise.fbm(nx, ny, 5, 2.0f, 0.5f) * 0.5f + 0.5f
                }
            }

            max(0f, min(1f, n)).pow(densityContrast)
        }

        // ── Alpha helper ─────────────────────────────────────────────────
        val alphaInt = (opacity * 255f).toInt().coerceIn(0, 255)

        // ── Multi-layer mode: separate Poisson disc pass per palette color ──
        if (colorMode == "multi-layer") {
            val perLayer = ceil(pointCount.toFloat() / paletteColors.size).toInt()

            for (li in paletteColors.indices) {
                val layerNoise = SimplexNoise(seed + li * 7919)
                val layerRng = SeededRNG(seed + li * 113)

                val layerDensity: (Float, Float) -> Float = { x, y ->
                    val lnx = (x / w - 0.5f) * densityScale + 5f + li * 1.3f + timeOff
                    val lny = (y / h - 0.5f) * densityScale + 5f + li * 0.9f
                    val n = layerNoise.fbm(lnx, lny, 4, 2.0f, 0.5f)
                    max(0f, min(1f, n * 0.5f + 0.5f)).pow(densityContrast)
                }

                val pts = weightedPoissonDisc(w, h, minDistance * 1.4f, perLayer, layerRng, layerDensity)
                val baseColor = paletteColors[li]
                val cr = Color.red(baseColor)
                val cg = Color.green(baseColor)
                val cb = Color.blue(baseColor)

                for (pt in pts) {
                    var radius = minDotSize + (maxDotSize - minDotSize) * pt.density
                    if (sizeVariation > 0f) {
                        radius *= 1f + (rng.random() - 0.5f) * sizeVariation
                    }
                    radius = max(0.5f, radius)

                    val layerAlpha = (opacity * pt.density * 255f).toInt().coerceIn(0, 255)
                    paint.color = Color.argb(layerAlpha, cr, cg, cb)
                    drawMark(canvas, paint, dotShape, pt.x, pt.y, radius, rng)
                }
            }
            return
        }

        // ── Single-pass weighted Poisson disc ────────────────────────────
        val pts = weightedPoissonDisc(w, h, minDistance, pointCount, rng, densityFn)

        for (pt in pts) {
            var radius = minDotSize + (maxDotSize - minDotSize) * pt.density
            if (sizeVariation > 0f) {
                radius *= 1f + (rng.random() - 0.5f) * sizeVariation
            }
            radius = max(0.5f, radius)

            when (colorMode) {
                "monochrome" -> {
                    val v = (pt.density * 200f).toInt().coerceIn(0, 255)
                    paint.color = Color.argb(alphaInt, v, v, v)
                }
                "palette-position" -> {
                    val t = pt.x / w * 0.6f + pt.y / h * 0.4f
                    val ci = min(floor(t * paletteColors.size).toInt(), paletteColors.size - 1)
                        .coerceIn(0, paletteColors.size - 1)
                    val c = paletteColors[ci]
                    paint.color = Color.argb(alphaInt, Color.red(c), Color.green(c), Color.blue(c))
                }
                else -> { // "palette-density"
                    // Lerp through palette using density, with density-modulated alpha
                    val c = palette.lerpColor(pt.density)
                    val densityAlpha = (opacity * (0.5f + pt.density * 0.5f) * 255f)
                        .toInt().coerceIn(0, 255)
                    paint.color = Color.argb(densityAlpha, Color.red(c), Color.green(c), Color.blue(c))
                }
            }

            drawMark(canvas, paint, dotShape, pt.x, pt.y, radius, rng)
        }
    }

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float {
        val dots = (params["pointCount"] as? Number)?.toInt() ?: 8000
        return (dots / 30000f).coerceIn(0.2f, 1f)
    }
}
