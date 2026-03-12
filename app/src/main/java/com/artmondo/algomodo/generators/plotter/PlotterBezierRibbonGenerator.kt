package com.artmondo.algomodo.generators.plotter

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import com.artmondo.algomodo.core.rng.SeededRNG
import com.artmondo.algomodo.core.rng.SimplexNoise
import com.artmondo.algomodo.data.palettes.Palette
import com.artmondo.algomodo.generators.Generator
import com.artmondo.algomodo.generators.ParamGroup
import com.artmondo.algomodo.generators.Parameter
import com.artmondo.algomodo.generators.Quality
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Bezier ribbon weave generator.
 *
 * Horizontal and vertical bezier ribbon strands woven in configurable patterns
 * with 3D shading. Matches the web TypeScript implementation.
 */
class PlotterBezierRibbonGenerator : Generator {

    override val id = "plotter-bezier-ribbon-weaves"
    override val family = "plotter"
    override val styleName = "Bezier Ribbon Weaves"
    override val definition =
        "Horizontal and vertical bezier ribbon strands woven in configurable patterns with 3D shading"
    override val algorithmNotes =
        "N horizontal and N vertical ribbons traverse the canvas as cubic bezier paths. " +
        "Weave patterns (basket, twill, satin, herringbone, diamond) define over/under crossings. " +
        "Ribbon styles add shaded 3D depth, silk gradients, or embossed edges. Crossing shadows " +
        "enhance depth. Over/under rendering uses clipped redraws for clean crossings."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema = listOf(
        Parameter.NumberParam("Strand Count", "strandCount", ParamGroup.COMPOSITION, "Number of horizontal (and vertical) ribbon strands", 2f, 14f, 1f, 6f),
        Parameter.NumberParam("Ribbon Width", "ribbonWidth", ParamGroup.GEOMETRY, "Thickness of each ribbon stroke in pixels", 4f, 60f, 2f, 22f),
        Parameter.NumberParam("Amplitude", "amplitude", ParamGroup.GEOMETRY, "Bezier control-point jitter \u2014 how much each ribbon curves", 0f, 80f, 2f, 28f),
        Parameter.NumberParam("Wave Frequency", "frequency", ParamGroup.GEOMETRY, "Number of full sine cycles per strand in animation mode", 0.5f, 4f, 0.25f, 1.5f),
        Parameter.NumberParam("Twist", "twist", ParamGroup.GEOMETRY, "Ribbon width variation along path \u2014 simulates twisting ribbons", 0f, 1f, 0.1f, 0f),
        Parameter.SelectParam("Weave Pattern", "weavePattern", ParamGroup.COMPOSITION, "basket: 1/1 \u00B7 twill: 2/2 diagonal \u00B7 satin: irregular floats \u00B7 herringbone: zigzag \u00B7 diamond: centered motif", listOf("basket", "twill", "satin", "herringbone", "diamond"), "basket"),
        Parameter.SelectParam("Ribbon Style", "ribbonStyle", ParamGroup.TEXTURE, "flat: solid \u00B7 shaded: 3D depth \u00B7 striped: center stripe \u00B7 silk: glossy gradient \u00B7 embossed: raised edge effect", listOf("flat", "shaded", "striped", "silk", "embossed"), "flat"),
        Parameter.NumberParam("Crossing Shadow", "crossingShadow", ParamGroup.TEXTURE, "Shadow intensity at crossing points for 3D depth illusion", 0f, 1f, 0.1f, 0.3f),
        Parameter.SelectParam("Color Mode", "colorMode", ParamGroup.COLOR, "palette-pair: H/V use halves \u00B7 palette-index: each unique \u00B7 gradient: smooth interpolation \u00B7 rainbow-twist: color shifts along each ribbon", listOf("palette-pair", "palette-index", "monochrome", "gradient", "rainbow-twist"), "palette-pair"),
        Parameter.SelectParam("Background", "background", ParamGroup.COLOR, null, listOf("white", "cream", "dark"), "cream"),
        Parameter.NumberParam("Wave Speed", "waveSpeed", ParamGroup.FLOW_MOTION, "Speed at which bezier control points oscillate (animation)", 0f, 1.0f, 0.05f, 0.25f)
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "strandCount" to 6f,
        "ribbonWidth" to 22f,
        "amplitude" to 28f,
        "frequency" to 1.5f,
        "twist" to 0f,
        "weavePattern" to "basket",
        "ribbonStyle" to "flat",
        "crossingShadow" to 0.3f,
        "colorMode" to "palette-pair",
        "background" to "cream",
        "waveSpeed" to 0.25f
    )

    companion object {
        private val BG = mapOf(
            "white" to Color.rgb(248, 248, 245),
            "cream" to Color.rgb(242, 234, 216),
            "dark"  to Color.rgb(14, 14, 14)
        )
    }

    // ── Cubic bezier evaluation ────────────────────────────────────────
    private fun cubicBezier(p0: Float, p1: Float, p2: Float, p3: Float, t: Float): Float {
        val mt = 1f - t
        return mt * mt * mt * p0 + 3f * mt * mt * t * p1 + 3f * mt * t * t * p2 + t * t * t * p3
    }

    /** Sample the Y of a horizontal strand at a given X position. */
    private fun sampleHorizY(w: Float, y0: Float, cp1y: Float, cp2y: Float, x: Float): Float {
        return cubicBezier(y0, cp1y, cp2y, y0, (x / w).coerceIn(0f, 1f))
    }

    /** Sample the X of a vertical strand at a given Y position. */
    private fun sampleVertX(h: Float, x0: Float, cp1x: Float, cp2x: Float, y: Float): Float {
        return cubicBezier(x0, cp1x, cp2x, x0, (y / h).coerceIn(0f, 1f))
    }

    /** Determine whether the vertical strand is on top at crossing (i, j). */
    private fun verticalIsOver(i: Int, j: Int, pattern: String, n: Int): Boolean {
        return when (pattern) {
            "twill" -> ((i + j) % 4) < 2
            "satin" -> ((i + j * 3) % 5) == 0
            "herringbone" -> {
                val dir = if (i % 2 == 0) 1 else -1
                ((i + j * dir + n) % 2) == 0
            }
            "diamond" -> {
                val ci = abs(i - (n - 1) / 2f)
                val cj = abs(j - (n - 1) / 2f)
                ((ci.roundToInt() + cj.roundToInt()) % 2) == 0
            }
            else -> (i + j) % 2 == 0 // basket
        }
    }

    /** Linearly interpolate between two RGB colors packed as Int. */
    private fun lerpColorInt(c0: Int, c1: Int, f: Float): Int {
        val r = (Color.red(c0) + (Color.red(c1) - Color.red(c0)) * f).toInt()
        val g = (Color.green(c0) + (Color.green(c1) - Color.green(c0)) * f).toInt()
        val b = (Color.blue(c0) + (Color.blue(c1) - Color.blue(c0)) * f).toInt()
        return Color.rgb(r.coerceIn(0, 255), g.coerceIn(0, 255), b.coerceIn(0, 255))
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

        // ── Parse parameters ───────────────────────────────────────────
        val n = max(2, ((params["strandCount"] as? Number)?.toInt() ?: 6))
        val ribbonW = max(2f, (params["ribbonWidth"] as? Number)?.toFloat() ?: 22f)
        val amp = (params["amplitude"] as? Number)?.toFloat() ?: 28f
        val freq = (params["frequency"] as? Number)?.toFloat() ?: 1.5f
        val twist = (params["twist"] as? Number)?.toFloat() ?: 0f
        val waveSpeed = (params["waveSpeed"] as? Number)?.toFloat() ?: 0.25f
        val colorMode = (params["colorMode"] as? String) ?: "palette-pair"
        val weavePattern = (params["weavePattern"] as? String) ?: "basket"
        val ribbonStyle = (params["ribbonStyle"] as? String) ?: "flat"
        val crossingShadow = (params["crossingShadow"] as? Number)?.toFloat() ?: 0.3f
        val background = (params["background"] as? String) ?: "cream"

        // ── Background ─────────────────────────────────────────────────
        val bgColor = BG[background] ?: BG["cream"]!!
        canvas.drawColor(bgColor)

        val isDark = background == "dark"
        val rng = SeededRNG(seed)
        val noise = SimplexNoise(seed)
        val colors = palette.colorInts()

        // ── Color helpers ──────────────────────────────────────────────
        val halfLen = max(1, colors.size / 2)

        fun strandColor(isHoriz: Boolean, index: Int): Int {
            return when (colorMode) {
                "monochrome" -> if (isDark) Color.rgb(220, 220, 220) else Color.rgb(30, 30, 30)
                "palette-pair" -> {
                    if (isHoriz) {
                        colors[index % halfLen]
                    } else {
                        colors[halfLen + (index % (colors.size - halfLen))]
                    }
                }
                "palette-index" -> colors[(if (isHoriz) index else index + n) % colors.size]
                "rainbow-twist" -> colors[index % colors.size]
                "gradient" -> {
                    val t = index.toFloat() / max(1, n - 1).toFloat()
                    val ci = t * (colors.size - 1)
                    val i0 = floor(ci).toInt()
                    val i1 = min(colors.size - 1, i0 + 1)
                    lerpColorInt(colors[i0], colors[i1], ci - i0)
                }
                else -> colors[index % colors.size]
            }
        }

        fun shiftedColor(baseColor: Int, index: Int, t: Float): Int {
            if (colorMode != "rainbow-twist") return baseColor
            val shifted = (index + t * 2f) % colors.size
            val i0 = floor(shifted).toInt()
            val i1 = ceil(shifted).toInt() % colors.size
            val f = shifted - i0
            return lerpColorInt(colors[i0 % colors.size], colors[i1], f)
        }

        // ── Layout ─────────────────────────────────────────────────────
        val margin = ribbonW * 1.2f
        val spacingH = (h - 2f * margin) / (n - 1)
        val spacingW = (w - 2f * margin) / (n - 1)

        // ── Pre-generate bezier control points with animation ──────────
        val hCenters = FloatArray(n)
        val hCp1y = FloatArray(n)
        val hCp2y = FloatArray(n)
        for (i in 0 until n) {
            val yc = margin + i * spacingH
            hCenters[i] = yc
            val phase1 = rng.random() * PI.toFloat() * 2f
            val phase2 = rng.random() * PI.toFloat() * 2f
            val strandFreq = freq * (0.8f + rng.random() * 0.4f)
            hCp1y[i] = yc + amp * sin(time * waveSpeed * strandFreq + phase1)
            hCp2y[i] = yc + amp * sin(time * waveSpeed * strandFreq + phase2 + PI.toFloat())
        }

        val vCenters = FloatArray(n)
        val vCp1x = FloatArray(n)
        val vCp2x = FloatArray(n)
        for (j in 0 until n) {
            val xc = margin + j * spacingW
            vCenters[j] = xc
            val phase1 = rng.random() * PI.toFloat() * 2f
            val phase2 = rng.random() * PI.toFloat() * 2f
            val strandFreq = freq * (0.8f + rng.random() * 0.4f)
            vCp1x[j] = xc + amp * sin(time * waveSpeed * strandFreq + phase1)
            vCp2x[j] = xc + amp * sin(time * waveSpeed * strandFreq + phase2 + PI.toFloat())
        }

        val alpha = if (isDark) 224 else 217 // ~0.88 and ~0.85 of 255

        // ── Twist width helper ─────────────────────────────────────────
        fun twistWidth(t: Float, strandIndex: Int): Float {
            if (twist <= 0f) return ribbonW
            val nv = noise.noise2D(strandIndex * 3.7f + 5f, t * 4f)
            return ribbonW * (1f - twist * 0.4f * (0.5f + 0.5f * nv))
        }

        // ── Reusable paint objects ─────────────────────────────────────
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }

        // ── Draw a horizontal bezier strand as a single cubic ──────────
        fun drawH(i: Int) {
            val path = Path()
            path.moveTo(0f, hCenters[i])
            path.cubicTo(w / 3f, hCp1y[i], 2f * w / 3f, hCp2y[i], w, hCenters[i])
            canvas.drawPath(path, strokePaint)
        }

        // ── Draw a horizontal strand with twist width variation ────────
        fun drawHTwist(i: Int) {
            val steps = 40
            for (s in 0 until steps) {
                val t0 = s.toFloat() / steps
                val t1 = (s + 1).toFloat() / steps
                val x0 = t0 * w
                val x1 = t1 * w
                val y0 = cubicBezier(hCenters[i], hCp1y[i], hCp2y[i], hCenters[i], t0)
                val y1 = cubicBezier(hCenters[i], hCp1y[i], hCp2y[i], hCenters[i], t1)
                val tw = twistWidth(t0, i)
                strokePaint.strokeWidth = tw
                val seg = Path()
                seg.moveTo(x0, y0)
                seg.lineTo(x1, y1)
                canvas.drawPath(seg, strokePaint)
            }
        }

        // ── Draw a vertical bezier strand, optionally skipping Y-ranges ─
        fun drawV(j: Int, skipRanges: List<FloatArray>? = null) {
            if (skipRanges == null || skipRanges.isEmpty()) {
                val path = Path()
                path.moveTo(vCenters[j], 0f)
                path.cubicTo(vCp1x[j], h / 3f, vCp2x[j], 2f * h / 3f, vCenters[j], h)
                canvas.drawPath(path, strokePaint)
                return
            }
            val sorted = skipRanges.sortedBy { it[0] }
            val ranges = mutableListOf<FloatArray>()
            var prev = 0f
            for (skip in sorted) {
                val yT = skip[0]
                val yB = skip[1]
                if (prev < yT) ranges.add(floatArrayOf(prev, yT))
                prev = yB
            }
            if (prev < h) ranges.add(floatArrayOf(prev, h))

            for (range in ranges) {
                val segTop = range[0]
                val segBot = range[1]
                val tS = segTop / h
                val tE = segBot / h
                val steps = max(2, ceil((segBot - segTop) / 2f).toInt())
                val path = Path()
                for (s in 0..steps) {
                    val t = tS + (tE - tS) * (s.toFloat() / steps)
                    val px = cubicBezier(vCenters[j], vCp1x[j], vCp2x[j], vCenters[j], t)
                    val py = t * h
                    if (s == 0) path.moveTo(px, py) else path.lineTo(px, py)
                }
                canvas.drawPath(path, strokePaint)
            }
        }

        // ── Ribbon rendering: applies style on top of a draw function ──
        fun drawRibbon(color: Int, drawFn: () -> Unit, isHoriz: Boolean, strandIndex: Int) {
            val cr = Color.red(color)
            val cg = Color.green(color)
            val cb = Color.blue(color)

            when (ribbonStyle) {
                "shaded" -> {
                    // Shadow
                    strokePaint.color = Color.argb(if (isDark) 77 else 31, 0, 0, 0) // 0.3 / 0.12
                    strokePaint.strokeWidth = ribbonW + 3f
                    drawFn()
                    // Main fill
                    strokePaint.color = Color.argb(alpha, cr, cg, cb)
                    strokePaint.strokeWidth = ribbonW
                    drawFn()
                    // Highlight
                    strokePaint.color = Color.argb(if (isDark) 46 else 89, 255, 255, 255) // 0.18 / 0.35
                    strokePaint.strokeWidth = ribbonW * 0.2f
                    drawFn()
                }
                "striped" -> {
                    // Main ribbon
                    strokePaint.color = Color.argb(alpha, cr, cg, cb)
                    strokePaint.strokeWidth = ribbonW
                    drawFn()
                    // Dual decorative stripes
                    val sc = if (isDark) min(255, cr + 60) else max(0, cr - 60)
                    val sg = if (isDark) min(255, cg + 60) else max(0, cg - 60)
                    val sb = if (isDark) min(255, cb + 60) else max(0, cb - 60)
                    strokePaint.color = Color.argb((alpha * 0.55f).toInt(), sc, sg, sb)
                    strokePaint.strokeWidth = ribbonW * 0.12f
                    drawFn()
                    // Edge lines
                    strokePaint.color = Color.argb(if (isDark) 77 else 89, if (isDark) 0 else 255, if (isDark) 0 else 255, if (isDark) 0 else 255)
                    strokePaint.strokeWidth = 1f
                    drawFn()
                }
                "silk" -> {
                    // Outer darker stroke for depth
                    val darken = 0.6f
                    strokePaint.color = Color.argb(alpha, (cr * darken).toInt(), (cg * darken).toInt(), (cb * darken).toInt())
                    strokePaint.strokeWidth = ribbonW
                    drawFn()
                    // Inner brighter gradient-like stroke
                    strokePaint.color = Color.argb((alpha * 0.7f).toInt(), min(255, cr + 50), min(255, cg + 50), min(255, cb + 50))
                    strokePaint.strokeWidth = ribbonW * 0.55f
                    drawFn()
                    // Hot highlight
                    strokePaint.color = Color.argb(if (isDark) 51 else 89, 255, 255, 255)
                    strokePaint.strokeWidth = ribbonW * 0.12f
                    drawFn()
                }
                "embossed" -> {
                    // Bottom-right shadow using setShadowLayer
                    strokePaint.color = Color.argb(alpha, cr, cg, cb)
                    strokePaint.strokeWidth = ribbonW
                    strokePaint.setShadowLayer(4f, 2f, 2f, Color.argb(if (isDark) 128 else 51, 0, 0, 0))
                    drawFn()
                    strokePaint.clearShadowLayer()
                    // Top-left inner highlight
                    strokePaint.color = Color.argb(if (isDark) 31 else 64, 255, 255, 255)
                    strokePaint.strokeWidth = ribbonW * 0.3f
                    drawFn()
                }
                else -> {
                    // Flat
                    strokePaint.color = Color.argb(alpha, cr, cg, cb)
                    strokePaint.strokeWidth = ribbonW
                    drawFn()
                    // Thin border
                    strokePaint.color = Color.argb(if (isDark) 89 else 102, if (isDark) 0 else 255, if (isDark) 0 else 255, if (isDark) 0 else 255)
                    strokePaint.strokeWidth = 1f
                    drawFn()
                }
            }

            // Rainbow-twist: overlay shifted color segments
            if (colorMode == "rainbow-twist") {
                val segments = 12
                for (s in 0 until segments) {
                    val t = s.toFloat() / segments
                    val col = shiftedColor(color, strandIndex, t)
                    strokePaint.color = Color.argb(64, Color.red(col), Color.green(col), Color.blue(col)) // ~0.25 alpha
                    strokePaint.strokeWidth = ribbonW * 0.6f
                    drawFn()
                }
            }
        }

        // ── Draw crossing shadows for 3D depth ────────────────────────
        fun drawCrossingShadows() {
            if (crossingShadow <= 0f) return

            for (i in 0 until n) {
                for (j in 0 until n) {
                    val yCross = sampleHorizY(w, hCenters[i], hCp1y[i], hCp2y[i], vCenters[j])
                    val xCross = sampleVertX(h, vCenters[j], vCp1x[j], vCp2x[j], hCenters[i])

                    val shadowR = ribbonW * 0.7f
                    val gradient = RadialGradient(
                        xCross, yCross, shadowR,
                        intArrayOf(
                            Color.argb((crossingShadow * 0.3f * 255f).toInt().coerceIn(0, 255), 0, 0, 0),
                            Color.argb((crossingShadow * 0.12f * 255f).toInt().coerceIn(0, 255), 0, 0, 0),
                            Color.argb(0, 0, 0, 0)
                        ),
                        floatArrayOf(0f, 0.5f, 1f),
                        Shader.TileMode.CLAMP
                    )
                    fillPaint.shader = gradient
                    canvas.drawCircle(xCross, yCross, shadowR, fillPaint)
                }
            }
            fillPaint.shader = null
        }

        val useTwist = twist > 0f

        // ======================================================================
        // Step 1: Draw crossing shadows beneath everything
        // ======================================================================
        drawCrossingShadows()

        // ======================================================================
        // Step 2: Draw all horizontal ribbons
        // ======================================================================
        for (i in 0 until n) {
            val drawFn: () -> Unit = if (useTwist) {
                { drawHTwist(i) }
            } else {
                { drawH(i) }
            }
            drawRibbon(strandColor(true, i), drawFn, true, i)
        }

        // ======================================================================
        // Step 3: Draw vertical ribbons, skipping "under" crossings
        // ======================================================================
        for (j in 0 until n) {
            val skipRanges = mutableListOf<FloatArray>()
            for (i in 0 until n) {
                if (!verticalIsOver(i, j, weavePattern, n)) {
                    val yCross = sampleHorizY(w, hCenters[i], hCp1y[i], hCp2y[i], vCenters[j])
                    val halfRib = ribbonW / 2f + 2f
                    skipRanges.add(floatArrayOf(yCross - halfRib, yCross + halfRib))
                }
            }
            drawRibbon(strandColor(false, j), { drawV(j, skipRanges) }, false, j)
        }

        // ======================================================================
        // Step 4: Redraw horizontal sections at "over" crossings (H over V)
        //         Where verticalIsOver is false, horizontal is over, so we clip
        //         to the crossing region and redraw the horizontal strand on top.
        // ======================================================================
        for (i in 0 until n) {
            for (j in 0 until n) {
                if (verticalIsOver(i, j, weavePattern, n)) continue
                // Horizontal is over at this crossing
                val xCross = sampleVertX(h, vCenters[j], vCp1x[j], vCp2x[j], hCenters[i])
                val halfRib = ribbonW / 2f + 2f
                canvas.save()
                val clipPath = Path()
                clipPath.addRect(
                    xCross - halfRib, 0f,
                    xCross + halfRib, h,
                    Path.Direction.CW
                )
                canvas.clipPath(clipPath)
                val drawFn: () -> Unit = if (useTwist) {
                    { drawHTwist(i) }
                } else {
                    { drawH(i) }
                }
                drawRibbon(strandColor(true, i), drawFn, true, i)
                canvas.restore()
            }
        }
    }

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float {
        val n = (params["strandCount"] as? Number)?.toInt() ?: 6
        return (n * n * 4f / 1000f).coerceIn(0.2f, 1f)
    }
}
