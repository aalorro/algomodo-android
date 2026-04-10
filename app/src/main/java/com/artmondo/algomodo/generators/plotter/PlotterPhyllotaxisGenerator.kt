package com.artmondo.algomodo.generators.plotter

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import com.artmondo.algomodo.audio.AudioAnalysis
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
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

class PlotterPhyllotaxisGenerator : Generator {

    override val id = "plotter-phyllotaxis"
    override val family = "plotter"
    override val styleName = "Phyllotaxis Spiral"
    override val definition =
        "Sunflower spiral: dots placed at successive golden-angle increments, radii growing as sqrt(i)."
    override val algorithmNotes =
        "Each point i is placed at angle i*divergenceAngle and radius c*sqrt(i). " +
        "137.508° (golden angle) gives classic Fibonacci spirals; nearby values change spiral arm counts; " +
        "far-off values create entirely different spoke/pinwheel structures. " +
        "Shape variants (petals, stars) and size modes (grow, shrink, wave) add visual variety."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema = listOf(
        Parameter.NumberParam("Point Count", "pointCount", ParamGroup.COMPOSITION,
            null, 100f, 5000f, 100f, 1500f),
        Parameter.NumberParam("Spread", "spread", ParamGroup.GEOMETRY,
            "Scale factor c in r = c * sqrt(i) — controls how tightly packed the spiral is",
            0.5f, 6f, 0.25f, 3.0f),
        Parameter.NumberParam("Canvas Fill", "canvasFill", ParamGroup.COMPOSITION,
            "Minimum fraction of the canvas the spiral should fill (auto-scales the pattern)",
            0.3f, 1.0f, 0.05f, 0.85f),
        Parameter.NumberParam("Divergence Angle", "divergenceAngle", ParamGroup.GEOMETRY,
            "137.508° = golden angle (Fibonacci spirals). Nearby values change spiral arm count. Far-off values create entirely different spoke/pinwheel structures",
            0f, 360f, 0.1f, 137.508f),
        Parameter.NumberParam("Dot Size", "dotSize", ParamGroup.GEOMETRY,
            null, 0.5f, 10f, 0.5f, 3.5f),
        Parameter.SelectParam("Size Mode", "sizeMode", ParamGroup.GEOMETRY,
            "uniform: same size | grow: bigger toward edge | shrink: bigger at center | wave: sinusoidal pulsing",
            listOf("uniform", "grow", "shrink", "wave"), "uniform"),
        Parameter.SelectParam("Shape", "shape", ParamGroup.GEOMETRY,
            "circle: round dots | petal: teardrop pointing outward | star: 5-point star | square: rotated rect",
            listOf("circle", "petal", "star", "square"), "circle"),
        Parameter.BooleanParam("Connect Lines", "connectLines", ParamGroup.TEXTURE,
            "Draw a line connecting sequential dots — creates beautiful spiral line art", false),
        Parameter.NumberParam("Glow", "glow", ParamGroup.TEXTURE,
            "Radial halo around each dot — creates bioluminescent or watercolor bloom effect",
            0f, 1f, 0.1f, 0.3f),
        Parameter.NumberParam("Depth Fade", "depthFade", ParamGroup.TEXTURE,
            "Center-to-edge opacity falloff — creates atmospheric perspective",
            0f, 1f, 0.1f, 0.2f),
        Parameter.SelectParam("Color Mode", "colorMode", ParamGroup.COLOR,
            "palette-radius: by distance | palette-angle: by position | palette-noise: FBM tint | palette-fibonacci: by Fibonacci spiral arm",
            listOf("monochrome", "palette-radius", "palette-angle", "palette-noise", "palette-fibonacci"),
            "palette-radius"),
        Parameter.SelectParam("Background", "background", ParamGroup.COLOR,
            null, listOf("white", "cream", "dark"), "cream"),
        Parameter.NumberParam("Spin Speed", "spinSpeed", ParamGroup.FLOW_MOTION,
            "Whole-pattern rotation speed (rad/s)", 0f, 0.5f, 0.01f, 0.05f),
        Parameter.NumberParam("Reactivity", "reactivity", ParamGroup.FLOW_MOTION,
            "How strongly audio input modulates the visuals (0 = off)", 0f, 2f, 0.1f, 0f)
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "pointCount" to 1500f,
        "spread" to 3.0f,
        "canvasFill" to 0.85f,
        "divergenceAngle" to 137.508f,
        "dotSize" to 3.5f,
        "sizeMode" to "uniform",
        "shape" to "circle",
        "connectLines" to false,
        "glow" to 0.3f,
        "depthFade" to 0.2f,
        "colorMode" to "palette-radius",
        "background" to "cream",
        "spinSpeed" to 0.05f,
        "reactivity" to 0f
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

        // --- Extract parameters ---
        val n = ((params["pointCount"] as? Number)?.toInt() ?: 1500).coerceAtLeast(1)
        val c = (params["spread"] as? Number)?.toFloat() ?: 3.0f
        val canvasFill = ((params["canvasFill"] as? Number)?.toFloat() ?: 0.85f).coerceIn(0.3f, 1f)
        val divAngleDeg = (params["divergenceAngle"] as? Number)?.toFloat() ?: 137.508f
        var baseDotR = (params["dotSize"] as? Number)?.toFloat() ?: 3.5f
        val sizeMode = (params["sizeMode"] as? String) ?: "uniform"
        val shape = (params["shape"] as? String) ?: "circle"
        val connectLines = (params["connectLines"] as? Boolean) ?: false
        val glowAmount = (params["glow"] as? Number)?.toFloat() ?: 0.3f
        val depthFade = (params["depthFade"] as? Number)?.toFloat() ?: 0.2f
        val colorMode = (params["colorMode"] as? String) ?: "palette-radius"
        val background = (params["background"] as? String) ?: "cream"
        val spinSpeed = (params["spinSpeed"] as? Number)?.toFloat() ?: 0.05f

        val rx = (params["reactivity"] as? Number)?.toFloat() ?: 0f
        val audioAnalysis = params["_audioAnalysis"] as? AudioAnalysis
        val audioBass = (audioAnalysis?.getBass(time) ?: 0f) * rx
        baseDotR *= (1f + audioBass)

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

        val bgLum = luminance(Color.red(bgColor), Color.green(bgColor), Color.blue(bgColor))

        // Ensure contrast for palette colors
        val contrastColors = IntArray(paletteColors.size) { i ->
            val cr = Color.red(paletteColors[i])
            val cg = Color.green(paletteColors[i])
            val cb = Color.blue(paletteColors[i])
            val adj = ensureContrast(cr, cg, cb, bgLum, isDark)
            Color.rgb(adj[0], adj[1], adj[2])
        }

        val cxc = w / 2f
        val cyc = h / 2f
        val maxR = min(w, h) * 0.49f
        val spin = time * spinSpeed

        // Auto-scale spiral to fill desired fraction of the canvas
        val naturalMaxR = c * sqrt(n.toFloat())
        val scale = if (naturalMaxR > 0f) (maxR * canvasFill) / naturalMaxR else 1f

        // Divergence angle in radians (user sets in degrees)
        val divergence = divAngleDeg * (PI.toFloat() / 180f)

        // --- Pre-compute point positions ---
        val px = FloatArray(n)
        val py = FloatArray(n)
        val pr = FloatArray(n)
        val pDotR = FloatArray(n)
        val pAngle = FloatArray(n)
        var count = 0

        for (i in 0 until n) {
            val angle = i * divergence + spin
            val r = c * sqrt(i.toFloat()) * scale
            if (r > maxR) continue

            val x = cxc + r * cos(angle)
            val y = cyc + r * sin(angle)
            if (x < -baseDotR * 4f || x > w + baseDotR * 4f ||
                y < -baseDotR * 4f || y > h + baseDotR * 4f
            ) continue

            val t = if (maxR > 0f) r / maxR else 0f
            val dotR = when (sizeMode) {
                "grow" -> baseDotR * (0.3f + t * 1.4f)
                "shrink" -> baseDotR * (1.5f - t * 1.2f)
                "wave" -> baseDotR * (0.5f + 0.8f * abs(sin(i * 0.15f)))
                else -> baseDotR
            }

            px[count] = x
            py[count] = y
            pr[count] = r
            pDotR[count] = dotR
            pAngle[count] = angle
            count++
        }

        val baseAlpha = if (isDark) 0.88f else 0.85f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val path = Path()

        // --- Helper: get color for a point at index ---
        fun getColor(idx: Int): Int {
            val t = if (maxR > 0f) pr[idx] / maxR else 0f
            return when (colorMode) {
                "monochrome" -> if (isDark) Color.rgb(220, 220, 220) else Color.rgb(30, 30, 30)
                "palette-angle" -> contrastColors[idx % contrastColors.size]
                "palette-noise" -> {
                    val nv = noise.fbm(
                        (px[idx] / w - 0.5f) * 3f + 5f,
                        (py[idx] / h - 0.5f) * 3f + 5f,
                        3, 2f, 0.5f
                    )
                    val nt = (nv * 0.5f + 0.5f).coerceIn(0f, 1f)
                    interpolateColors(contrastColors, nt)
                }
                "palette-fibonacci" -> {
                    val armIndex = idx % 13
                    contrastColors[armIndex % contrastColors.size]
                }
                else -> interpolateColors(contrastColors, t) // palette-radius
            }
        }

        // --- Draw connecting lines first (behind dots) ---
        if (connectLines && count > 1) {
            paint.style = Paint.Style.STROKE
            paint.strokeCap = Paint.Cap.ROUND

            for (si in 1 until count) {
                val t = si.toFloat() / count
                val lineColor = interpolateColors(contrastColors, t)
                val lineAlpha = ((if (isDark) 0.45f else 0.30f) * (1f - t * 0.5f) * 255f).toInt().coerceIn(0, 255)
                val lineWidth = (baseDotR * 0.4f * (1f - t * 0.6f)).coerceAtLeast(0.3f)

                paint.color = lineColor
                paint.alpha = lineAlpha
                paint.strokeWidth = lineWidth

                val midX = (px[si - 1] + px[si]) / 2f
                val midY = (py[si - 1] + py[si]) / 2f

                path.reset()
                path.moveTo(px[si - 1], py[si - 1])
                path.quadTo(px[si - 1], py[si - 1], midX, midY)
                canvas.drawPath(path, paint)
            }
        }

        // --- Glow pass (behind solid shapes) ---
        if (glowAmount > 0f) {
            val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
            glowPaint.style = Paint.Style.FILL

            for (idx in 0 until count) {
                val t = if (maxR > 0f) pr[idx] / maxR else 0f
                val color = getColor(idx)
                val cr = Color.red(color)
                val cg = Color.green(color)
                val cb = Color.blue(color)

                val glowPulse = if (time > 0f) (1f + 0.12f * sin(time * 1.5f + idx * 0.02f)) else 1f
                val glowR = pDotR[idx] * (1.5f + glowAmount * 2.5f) * glowPulse
                val depthAlpha = 1f - t * depthFade

                if (glowR < 0.5f) continue

                val centerAlpha = (0.4f * glowAmount * depthAlpha * 255f).toInt().coerceIn(0, 255)
                val midAlpha = (0.12f * glowAmount * depthAlpha * 255f).toInt().coerceIn(0, 255)

                val gradient = RadialGradient(
                    px[idx], py[idx], glowR,
                    intArrayOf(
                        Color.argb(centerAlpha, cr, cg, cb),
                        Color.argb(midAlpha, cr, cg, cb),
                        Color.argb(0, cr, cg, cb)
                    ),
                    floatArrayOf(0f, 0.4f, 1f),
                    Shader.TileMode.CLAMP
                )
                glowPaint.shader = gradient
                canvas.drawCircle(px[idx], py[idx], glowR, glowPaint)
            }
            glowPaint.shader = null
        }

        // --- Draw solid shapes ---
        paint.style = Paint.Style.FILL
        paint.shader = null
        paint.strokeWidth = 0f

        for (idx in 0 until count) {
            val t = if (maxR > 0f) pr[idx] / maxR else 0f
            val color = getColor(idx)
            val cr = Color.red(color)
            val cg = Color.green(color)
            val cb = Color.blue(color)

            val depthAlpha = (baseAlpha * (1f - t * depthFade) * 255f).toInt().coerceIn(0, 255)
            paint.color = Color.argb(depthAlpha, cr, cg, cb)

            val x = px[idx]; val y = py[idx]; val dotR = pDotR[idx]

            when (shape) {
                "petal" -> {
                    val outAngle = atan2(y - cyc, x - cxc)
                    val noiseWarp = noise.noise2D(idx * 0.1f + 7.3f, seed * 0.01f) * 0.15f
                    val petalLen = dotR * (1.8f + noiseWarp)
                    val petalW = dotR * (0.65f + noiseWarp * 0.3f)

                    canvas.save()
                    canvas.translate(x, y)
                    canvas.rotate(outAngle * 180f / PI.toFloat())

                    path.reset()
                    path.moveTo(petalLen, 0f)
                    path.cubicTo(
                        petalLen * 0.4f, -petalW * 1.1f,
                        -dotR * 0.3f, -petalW * 0.5f,
                        -dotR * 0.4f, 0f
                    )
                    path.cubicTo(
                        -dotR * 0.3f, petalW * 0.5f,
                        petalLen * 0.4f, petalW * 1.1f,
                        petalLen, 0f
                    )
                    canvas.drawPath(path, paint)
                    canvas.restore()
                }
                "star" -> {
                    val outAngle = atan2(y - cyc, x - cxc)
                    val noiseRot = noise.noise2D(idx * 0.05f, seed * 0.01f) * 0.3f
                    val rays = 5
                    val outerR = dotR
                    val innerR = dotR * 0.35f

                    canvas.save()
                    canvas.translate(x, y)
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
                    val outAngle = atan2(y - cyc, x - cxc)

                    canvas.save()
                    canvas.translate(x, y)
                    canvas.rotate(outAngle * 180f / PI.toFloat())
                    canvas.drawRect(-dotR, -dotR, dotR, dotR, paint)
                    canvas.restore()
                }
                else -> {
                    canvas.drawCircle(x, y, dotR, paint)
                }
            }
        }
    }

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float {
        val points = (params["pointCount"] as? Number)?.toInt() ?: 1500
        return (points / 5000f).coerceIn(0.1f, 1f)
    }

    companion object {
        private fun luminance(r: Int, g: Int, b: Int): Float =
            (0.299f * r + 0.587f * g + 0.114f * b) / 255f

        private fun ensureContrast(
            r: Int, g: Int, b: Int, bgLum: Float, isDark: Boolean
        ): IntArray {
            val lum = luminance(r, g, b)
            val contrast = abs(lum - bgLum)
            if (contrast >= 0.4f) return intArrayOf(r, g, b)
            val tr = if (isDark) 220 else 25
            val tg = if (isDark) 220 else 25
            val tb = if (isDark) 220 else 25
            val mix = ((0.45f - contrast) / 0.45f + 0.3f).coerceAtMost(1f)
            return intArrayOf(
                (r + (tr - r) * mix).toInt(),
                (g + (tg - g) * mix).toInt(),
                (b + (tb - b) * mix).toInt()
            )
        }

        private fun interpolateColors(colors: IntArray, t: Float): Int {
            val ct = t.coerceIn(0f, 1f) * (colors.size - 1)
            val i0 = ct.toInt()
            val i1 = (i0 + 1).coerceAtMost(colors.size - 1)
            val f = ct - i0
            val r = (Color.red(colors[i0]) + (Color.red(colors[i1]) - Color.red(colors[i0])) * f).toInt()
            val g = (Color.green(colors[i0]) + (Color.green(colors[i1]) - Color.green(colors[i0])) * f).toInt()
            val b = (Color.blue(colors[i0]) + (Color.blue(colors[i1]) - Color.blue(colors[i0])) * f).toInt()
            return Color.rgb(r, g, b)
        }
    }
}
