package com.artmondo.algomodo.generators.plotter

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import com.artmondo.algomodo.core.rng.SimplexNoise
import com.artmondo.algomodo.data.palettes.Palette
import com.artmondo.algomodo.generators.Generator
import com.artmondo.algomodo.generators.ParamGroup
import com.artmondo.algomodo.generators.Parameter
import com.artmondo.algomodo.generators.Quality
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Phyllotaxis spiral pattern.
 *
 * Arranges dots in a sunflower-seed spiral using the golden angle. The
 * divergence angle parameter defaults to 137.5 degrees but can be adjusted
 * to produce different packing geometries.
 */
class PlotterPhyllotaxisGenerator : Generator {

    override val id = "plotter-phyllotaxis"
    override val family = "plotter"
    override val styleName = "Phyllotaxis Spiral"
    override val definition =
        "Sunflower-seed spiral pattern using the golden angle for optimal packing."
    override val algorithmNotes =
        "Each dot is placed at polar coordinates (sqrt(index) * scale, index * angle). " +
        "With the golden angle (~137.5 degrees), this produces a Fibonacci-spiral pattern. " +
        "Adjusting the angle creates parastichy spirals of different families."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema = listOf(
        Parameter.NumberParam("Point Count", "pointCount", ParamGroup.COMPOSITION, null, 100f, 5000f, 100f, 1500f),
        Parameter.NumberParam("Spread", "spread", ParamGroup.GEOMETRY, "Scale factor c in r = c * sqrt(i) — controls how tightly packed the spiral is", 0.5f, 6f, 0.25f, 3.0f),
        Parameter.NumberParam("Angle Offset", "angleOffset", ParamGroup.GEOMETRY, "Tiny deviation from golden angle — even 0.01 creates dramatically different spiral arms", -0.05f, 0.05f, 0.002f, 0f),
        Parameter.NumberParam("Dot Size", "dotSize", ParamGroup.GEOMETRY, null, 0.5f, 10f, 0.5f, 3.5f),
        Parameter.SelectParam("Size Mode", "sizeMode", ParamGroup.GEOMETRY, "uniform: same size | grow: bigger toward edge | shrink: bigger at center | wave: sinusoidal pulsing", listOf("uniform", "grow", "shrink", "wave"), "uniform"),
        Parameter.SelectParam("Shape", "shape", ParamGroup.GEOMETRY, "circle: round dots | petal: teardrop pointing outward | star: 4-point star | square: rotated rect", listOf("circle", "petal", "star", "square"), "circle"),
        Parameter.BooleanParam("Connect Lines", "connectLines", ParamGroup.TEXTURE, "Draw a line connecting sequential dots — creates beautiful spiral line art", false),
        Parameter.SelectParam("Color Mode", "colorMode", ParamGroup.COLOR, "palette-radius: by distance | palette-angle: by position | palette-noise: FBM tint | palette-fibonacci: by Fibonacci spiral arm", listOf("monochrome", "palette-radius", "palette-angle", "palette-noise", "palette-fibonacci"), "palette-radius"),
        Parameter.SelectParam("Background", "background", ParamGroup.COLOR, null, listOf("white", "cream", "dark"), "cream"),
        Parameter.NumberParam("Spin Speed", "spinSpeed", ParamGroup.FLOW_MOTION, "Whole-pattern rotation speed (rad/s)", 0f, 0.5f, 0.01f, 0.05f)
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "pointCount" to 1500f,
        "spread" to 3.0f,
        "angleOffset" to 0f,
        "dotSize" to 3.5f,
        "sizeMode" to "uniform",
        "shape" to "circle",
        "connectLines" to false,
        "colorMode" to "palette-radius",
        "background" to "cream",
        "spinSpeed" to 0.05f
    )

    companion object {
        // Golden angle in radians: 2*PI / phi^2 = PI * (3 - sqrt(5))
        private val GOLDEN_ANGLE = (PI * (3.0 - sqrt(5.0))).toFloat()
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

        // --- Extract parameters ---
        val n = ((params["pointCount"] as? Number)?.toInt() ?: 1500).coerceAtLeast(1)
        val c = (params["spread"] as? Number)?.toFloat() ?: 3.0f
        val angleOff = (params["angleOffset"] as? Number)?.toFloat() ?: 0f
        val baseDotR = (params["dotSize"] as? Number)?.toFloat() ?: 3.5f
        val sizeMode = (params["sizeMode"] as? String) ?: "uniform"
        val shape = (params["shape"] as? String) ?: "circle"
        val connectLines = (params["connectLines"] as? Boolean) ?: false
        val colorMode = (params["colorMode"] as? String) ?: "palette-radius"
        val background = (params["background"] as? String) ?: "cream"
        val spinSpeed = (params["spinSpeed"] as? Number)?.toFloat() ?: 0.05f

        val noise = SimplexNoise(seed)
        val paletteColors = palette.colorInts()
        val isDark = background == "dark"

        // --- Background ---
        val bgColor = when (background) {
            "white" -> Color.rgb(248, 248, 245)
            "cream" -> Color.rgb(242, 234, 216)
            "dark" -> Color.rgb(14, 14, 14)
            else -> Color.rgb(242, 234, 216)
        }
        canvas.drawColor(bgColor)

        val cxc = w / 2f
        val cyc = h / 2f
        val maxR = min(w, h) * 0.49f
        val divergence = GOLDEN_ANGLE + angleOff
        val spin = time * spinSpeed

        // --- Pre-compute point positions ---
        data class PhylloPoint(
            val x: Float, val y: Float, val r: Float,
            val dotR: Float, val i: Int, val angle: Float
        )

        val points = ArrayList<PhylloPoint>(n)

        for (i in 0 until n) {
            val angle = i * divergence + spin
            val r = c * sqrt(i.toFloat())
            if (r > maxR) continue

            val x = cxc + r * cos(angle)
            val y = cyc + r * sin(angle)
            if (x < -baseDotR * 4f || x > w + baseDotR * 4f ||
                y < -baseDotR * 4f || y > h + baseDotR * 4f
            ) continue

            // Size mode
            val t = if (maxR > 0f) r / maxR else 0f
            val dotR = when (sizeMode) {
                "grow" -> baseDotR * (0.3f + t * 1.4f)
                "shrink" -> baseDotR * (1.5f - t * 1.2f)
                "wave" -> baseDotR * (0.5f + 0.8f * abs(sin(i * 0.15f)))
                else -> baseDotR // uniform
            }

            points.add(PhylloPoint(x, y, r, dotR, i, angle))
        }

        val baseAlpha = if (isDark) 0.88f else 0.85f
        val depthFade = 0.2f // matching web default (parameter not exposed but used in rendering)
        val glowAmount = 0.3f // matching web default

        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val path = Path()

        // --- Helper: get color for a point ---
        fun getPointColor(p: PhylloPoint, t: Float): Int {
            return when (colorMode) {
                "monochrome" -> {
                    if (isDark) Color.rgb(220, 220, 220) else Color.rgb(30, 30, 30)
                }
                "palette-angle" -> {
                    paletteColors[p.i % paletteColors.size]
                }
                "palette-noise" -> {
                    val nv = noise.fbm(
                        (p.x / w - 0.5f) * 3f + 5f,
                        (p.y / h - 0.5f) * 3f + 5f,
                        3, 2f, 0.5f
                    )
                    val nt = (nv * 0.5f + 0.5f).coerceIn(0f, 1f)
                    palette.lerpColor(nt)
                }
                "palette-fibonacci" -> {
                    val armIndex = p.i % 13
                    paletteColors[armIndex % paletteColors.size]
                }
                else -> {
                    // palette-radius (default)
                    palette.lerpColor(t)
                }
            }
        }

        // --- Draw connecting lines first (behind dots) ---
        if (connectLines && points.size > 1) {
            paint.style = Paint.Style.STROKE
            paint.strokeCap = Paint.Cap.ROUND

            for (si in 1 until points.size) {
                val t = si.toFloat() / points.size
                val lineColor = palette.lerpColor(t)
                val lineAlpha = ((if (isDark) 0.45f else 0.30f) * (1f - t * 0.5f) * 255f).toInt().coerceIn(0, 255)
                val lineWidth = (baseDotR * 0.4f * (1f - t * 0.6f)).coerceAtLeast(0.3f)

                paint.color = lineColor
                paint.alpha = lineAlpha
                paint.strokeWidth = lineWidth

                val prev = points[si - 1]
                val curr = points[si]
                val midX = (prev.x + curr.x) / 2f
                val midY = (prev.y + curr.y) / 2f

                path.reset()
                path.moveTo(prev.x, prev.y)
                path.quadTo(prev.x, prev.y, midX, midY)
                canvas.drawPath(path, paint)
            }
        }

        // --- Glow pass (behind solid shapes) ---
        if (glowAmount > 0f) {
            val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
            glowPaint.style = Paint.Style.FILL

            for (p in points) {
                val t = if (maxR > 0f) p.r / maxR else 0f
                val color = getPointColor(p, t)
                val cr = Color.red(color)
                val cg = Color.green(color)
                val cb = Color.blue(color)

                val glowPulse = if (time > 0f) (1f + 0.12f * sin(time * 1.5f + p.i * 0.02f)) else 1f
                val glowR = p.dotR * (1.5f + glowAmount * 2.5f) * glowPulse
                val depthAlpha = 1f - t * depthFade

                if (glowR < 0.5f) continue

                val centerAlpha = (0.4f * glowAmount * depthAlpha * 255f).toInt().coerceIn(0, 255)
                val midAlpha = (0.12f * glowAmount * depthAlpha * 255f).toInt().coerceIn(0, 255)

                val gradient = RadialGradient(
                    p.x, p.y, glowR,
                    intArrayOf(
                        Color.argb(centerAlpha, cr, cg, cb),
                        Color.argb(midAlpha, cr, cg, cb),
                        Color.argb(0, cr, cg, cb)
                    ),
                    floatArrayOf(0f, 0.4f, 1f),
                    Shader.TileMode.CLAMP
                )
                glowPaint.shader = gradient
                canvas.drawCircle(p.x, p.y, glowR, glowPaint)
            }
            glowPaint.shader = null
        }

        // --- Draw solid shapes ---
        paint.style = Paint.Style.FILL
        paint.shader = null
        paint.strokeWidth = 0f

        for (p in points) {
            val t = if (maxR > 0f) p.r / maxR else 0f
            val color = getPointColor(p, t)
            val cr = Color.red(color)
            val cg = Color.green(color)
            val cb = Color.blue(color)

            val depthAlpha = (baseAlpha * (1f - t * depthFade) * 255f).toInt().coerceIn(0, 255)
            paint.color = Color.argb(depthAlpha, cr, cg, cb)

            when (shape) {
                "petal" -> {
                    val outAngle = atan2(p.y - cyc, p.x - cxc)
                    val noiseWarp = noise.noise2D(p.i * 0.1f + 7.3f, seed * 0.01f) * 0.15f
                    val petalLen = p.dotR * (1.8f + noiseWarp)
                    val petalW = p.dotR * (0.65f + noiseWarp * 0.3f)

                    canvas.save()
                    canvas.translate(p.x, p.y)
                    canvas.rotate(outAngle * 180f / PI.toFloat())

                    path.reset()
                    path.moveTo(petalLen, 0f)
                    path.cubicTo(
                        petalLen * 0.4f, -petalW * 1.1f,
                        -p.dotR * 0.3f, -petalW * 0.5f,
                        -p.dotR * 0.4f, 0f
                    )
                    path.cubicTo(
                        -p.dotR * 0.3f, petalW * 0.5f,
                        petalLen * 0.4f, petalW * 1.1f,
                        petalLen, 0f
                    )
                    canvas.drawPath(path, paint)
                    canvas.restore()
                }
                "star" -> {
                    val outAngle = atan2(p.y - cyc, p.x - cxc)
                    val noiseRot = noise.noise2D(p.i * 0.05f, seed * 0.01f) * 0.3f
                    val rays = 5
                    val outerR = p.dotR
                    val innerR = p.dotR * 0.35f

                    canvas.save()
                    canvas.translate(p.x, p.y)
                    canvas.rotate((outAngle + noiseRot) * 180f / PI.toFloat())

                    path.reset()
                    for (v in 0 until rays * 2) {
                        val sa = (v.toFloat() / (rays * 2)) * PI.toFloat() * 2f
                        val sr = if (v % 2 == 0) outerR else innerR
                        val sx = cos(sa) * sr
                        val sy = sin(sa) * sr
                        if (v == 0) path.moveTo(sx, sy) else path.lineTo(sx, sy)
                    }
                    path.close()
                    canvas.drawPath(path, paint)
                    canvas.restore()
                }
                "square" -> {
                    val outAngle = atan2(p.y - cyc, p.x - cxc)

                    canvas.save()
                    canvas.translate(p.x, p.y)
                    canvas.rotate(outAngle * 180f / PI.toFloat())
                    canvas.drawRect(-p.dotR, -p.dotR, p.dotR, p.dotR, paint)
                    canvas.restore()
                }
                else -> {
                    // circle (default)
                    canvas.drawCircle(p.x, p.y, p.dotR, paint)
                }
            }
        }
    }

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float {
        val points = (params["pointCount"] as? Number)?.toInt() ?: 1500
        return (points / 5000f).coerceIn(0.1f, 1f)
    }
}
