package com.artmondo.algomodo.generators.fractals

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import com.artmondo.algomodo.core.rng.SeededRNG
import com.artmondo.algomodo.data.palettes.Palette
import com.artmondo.algomodo.generators.Generator
import com.artmondo.algomodo.generators.ParamGroup
import com.artmondo.algomodo.generators.Parameter
import com.artmondo.algomodo.generators.Quality
import kotlin.math.*

class PythagorasTreeGenerator : Generator {

    override val id = "fractal-pythagoras-tree"
    override val family = "fractals"
    override val styleName = "Pythagoras Tree"
    override val definition =
        "Pythagoras tree fractal — each square sprouts two child squares from its top edge at a branching angle, forming a self-similar binary tree."
    override val algorithmNotes =
        "Iterative stack-based construction: given a parent's base edge (P0,P1), compute top corners via " +
        "perpendicular offset, place apex A using branching angle alpha: A = P3 + cos²(α)·(P2−P3) + " +
        "cos(α)·sin(α)·normal. Left child is built on edge P3→A, right child on A→P2, each scaled by " +
        "the taper factor. Squares are bucket-sorted by depth for batched Path drawing. Animation " +
        "oscillates lean and angle with multi-frequency sinusoids for organic wind-blown sway."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema: List<Parameter> = listOf(
        Parameter.NumberParam("Depth", "depth", ParamGroup.COMPOSITION,
            "Recursion depth — each level doubles the number of shapes", 4f, 14f, 1f, 10f),
        Parameter.NumberParam("Angle", "angle", ParamGroup.GEOMETRY,
            "Branching angle — 45° = classic symmetric, lower = tall narrow, higher = wide bushy",
            15f, 75f, 1f, 45f),
        Parameter.NumberParam("Lean", "lean", ParamGroup.GEOMETRY,
            "Asymmetry — shifts branching apex left or right, creating wind-blown trees",
            -30f, 30f, 1f, 0f),
        Parameter.NumberParam("Taper", "taper", ParamGroup.GEOMETRY,
            "Scale factor per level — 1.0 = exact Pythagoras tree, lower = sparser canopy",
            0.5f, 1.0f, 0.01f, 0.70f),
        Parameter.SelectParam("Shape", "shape", ParamGroup.GEOMETRY,
            "square: classic | circle: organic | diamond: crystal | triangle: arrow-like | hexagon: honeycomb",
            listOf("square", "circle", "diamond", "triangle", "hexagon"), "square"),
        Parameter.SelectParam("Color Mode", "colorMode", ParamGroup.COLOR,
            "depth: by level | angle: by direction | gradient: vertical | rainbow: hue cycle | random: per-square",
            listOf("depth", "angle", "gradient", "rainbow", "random"), "depth"),
        Parameter.SelectParam("Fill Mode", "fill", ParamGroup.TEXTURE,
            "filled: solid shapes | outline: edges only | mixed: filled leaves, outlined trunk",
            listOf("filled", "outline", "mixed"), "filled"),
        Parameter.SelectParam("Style", "style", ParamGroup.TEXTURE,
            "solid: flat | glow: additive luminous | neon: bright outlines | watercolor: soft translucent",
            listOf("solid", "glow", "neon", "watercolor"), "solid"),
        Parameter.NumberParam("Depth Fade", "depthFade", ParamGroup.TEXTURE,
            "Fade opacity toward leaf nodes — 0 = none, 1 = leaves fully transparent",
            0f, 1f, 0.05f, 0f),
        Parameter.NumberParam("Line Width", "lineWidth", ParamGroup.TEXTURE,
            "Stroke width for outline, mixed, and neon modes", 0.5f, 4f, 0.5f, 1f),
        Parameter.NumberParam("Speed", "speed", ParamGroup.FLOW_MOTION,
            "Animation speed — wind sway oscillation", 0.1f, 3.0f, 0.1f, 0.5f)
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "depth" to 10f,
        "angle" to 45f,
        "lean" to 0f,
        "taper" to 0.70f,
        "shape" to "square",
        "colorMode" to "depth",
        "fill" to "filled",
        "style" to "solid",
        "depthFade" to 0f,
        "lineWidth" to 1f,
        "speed" to 0.5f
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
        val w = bitmap.width
        val h = bitmap.height

        var maxDepth = ((params["depth"] as? Number)?.toInt() ?: 10).coerceAtMost(14)
        if (quality == Quality.DRAFT) maxDepth = maxDepth.coerceAtMost(10)
        var angle = (params["angle"] as? Number)?.toFloat() ?: 45f
        var lean = (params["lean"] as? Number)?.toFloat() ?: 0f
        val taper = (params["taper"] as? Number)?.toFloat() ?: 0.70f
        val shape = (params["shape"] as? String) ?: "square"
        val colorMode = (params["colorMode"] as? String) ?: "depth"
        val fillMode = (params["fill"] as? String) ?: "filled"
        val style = (params["style"] as? String) ?: "solid"
        val depthFade = (params["depthFade"] as? Number)?.toFloat() ?: 0f
        val lineWidth = (params["lineWidth"] as? Number)?.toFloat() ?: 1f
        val speed = (params["speed"] as? Number)?.toFloat() ?: 0.5f

        val rng = SeededRNG(seed)

        // Animation: multi-frequency sway
        if (time > 0f) {
            val t = time * speed
            lean += sin(t * 0.37f) * 6f + sin(t * 0.83f) * 2.5f + sin(t * 1.47f) * 1f
            angle += sin(t * 0.19f) * 2f + sin(t * 0.51f) * 0.8f
        }

        angle = angle.coerceIn(5f, 85f)
        val leftAlpha = (angle + lean) * PI.toFloat() / 180f

        // Palette LUT
        val paletteLut = palette.buildLut(256)
        val lutR = IntArray(256)
        val lutG = IntArray(256)
        val lutB = IntArray(256)
        for (i in 0 until 256) {
            lutR[i] = Color.red(paletteLut[i])
            lutG[i] = Color.green(paletteLut[i])
            lutB[i] = Color.blue(paletteLut[i])
        }

        // ── Generate tree (iterative stack) ────────────────────────────
        val maxSquares = 1 shl (maxDepth + 1)
        val p0x = DoubleArray(maxSquares); val p0y = DoubleArray(maxSquares)
        val p1x = DoubleArray(maxSquares); val p1y = DoubleArray(maxSquares)
        val p2x = DoubleArray(maxSquares); val p2y = DoubleArray(maxSquares)
        val p3x = DoubleArray(maxSquares); val p3y = DoubleArray(maxSquares)
        val sDepth = IntArray(maxSquares)
        val sAngle = FloatArray(maxSquares)

        val stP0x = DoubleArray(maxSquares); val stP0y = DoubleArray(maxSquares)
        val stP1x = DoubleArray(maxSquares); val stP1y = DoubleArray(maxSquares)
        val stDepth = IntArray(maxSquares)

        var sqCount = 0
        var stackTop = 1
        stP0x[0] = 0.0; stP0y[0] = 0.0
        stP1x[0] = 1.0; stP1y[0] = 0.0
        stDepth[0] = 0

        val cosA = cos(leftAlpha.toDouble())
        val sinA = sin(leftAlpha.toDouble())
        val cc = cosA * cosA
        val cs = cosA * sinA

        while (stackTop > 0) {
            stackTop--
            val bP0x = stP0x[stackTop]; val bP0y = stP0y[stackTop]
            val bP1x = stP1x[stackTop]; val bP1y = stP1y[stackTop]
            val d = stDepth[stackTop]

            val ex = bP1x - bP0x; val ey = bP1y - bP0y
            val nx = -ey; val ny = ex

            val tP3x = bP0x + nx; val tP3y = bP0y + ny
            val tP2x = bP1x + nx; val tP2y = bP1y + ny

            val si = sqCount++
            p0x[si] = bP0x; p0y[si] = bP0y
            p1x[si] = bP1x; p1y[si] = bP1y
            p2x[si] = tP2x; p2y[si] = tP2y
            p3x[si] = tP3x; p3y[si] = tP3y
            sDepth[si] = d
            sAngle[si] = atan2(ey, ex).toFloat()

            if (d < maxDepth) {
                val topEx = tP2x - tP3x; val topEy = tP2y - tP3y
                val ax = tP3x + cc * topEx + cs * nx
                val ay = tP3y + cc * topEy + cs * ny

                if (taper < 0.999f) {
                    val lDx = ax - tP3x; val lDy = ay - tP3y
                    val aPx = tP3x + lDx * taper; val aPy = tP3y + lDy * taper
                    val rDx = tP2x - ax; val rDy = tP2y - ay
                    val bPx = ax + rDx * taper; val bPy = ay + rDy * taper

                    stP0x[stackTop] = ax; stP0y[stackTop] = ay
                    stP1x[stackTop] = bPx; stP1y[stackTop] = bPy
                    stDepth[stackTop] = d + 1; stackTop++
                    stP0x[stackTop] = tP3x; stP0y[stackTop] = tP3y
                    stP1x[stackTop] = aPx; stP1y[stackTop] = aPy
                    stDepth[stackTop] = d + 1; stackTop++
                } else {
                    stP0x[stackTop] = ax; stP0y[stackTop] = ay
                    stP1x[stackTop] = tP2x; stP1y[stackTop] = tP2y
                    stDepth[stackTop] = d + 1; stackTop++
                    stP0x[stackTop] = tP3x; stP0y[stackTop] = tP3y
                    stP1x[stackTop] = ax; stP1y[stackTop] = ay
                    stDepth[stackTop] = d + 1; stackTop++
                }
            }
        }

        // ── Bounding box and auto-fit ──────────────────────────────────
        var minX = Double.MAX_VALUE; var maxX = -Double.MAX_VALUE
        var minY = Double.MAX_VALUE; var maxY = -Double.MAX_VALUE
        for (i in 0 until sqCount) {
            var c: Double
            c = p0x[i]; if (c < minX) minX = c; if (c > maxX) maxX = c
            c = p1x[i]; if (c < minX) minX = c; if (c > maxX) maxX = c
            c = p2x[i]; if (c < minX) minX = c; if (c > maxX) maxX = c
            c = p3x[i]; if (c < minX) minX = c; if (c > maxX) maxX = c
            c = p0y[i]; if (c < minY) minY = c; if (c > maxY) maxY = c
            c = p1y[i]; if (c < minY) minY = c; if (c > maxY) maxY = c
            c = p2y[i]; if (c < minY) minY = c; if (c > maxY) maxY = c
            c = p3y[i]; if (c < minY) minY = c; if (c > maxY) maxY = c
        }

        val rangeX = if (maxX > minX) maxX - minX else 1.0
        val rangeY = if (maxY > minY) maxY - minY else 1.0
        val padding = 0.05
        val sc = min(w * (1 - 2 * padding) / rangeX, h * (1 - 2 * padding) / rangeY)
        val offX = (w - rangeX * sc) * 0.5 - minX * sc
        val offY = (h + rangeY * sc) * 0.5 + minY * sc

        // ── Clear canvas ────────────────────────────────────────────────
        canvas.drawColor(Color.BLACK)

        // ── Bucket sort by depth ────────────────────────────────────────
        val depthCounts = IntArray(maxDepth + 1)
        for (i in 0 until sqCount) depthCounts[sDepth[i]]++
        val depthOffsets = IntArray(maxDepth + 2)
        for (d in 0..maxDepth) depthOffsets[d + 1] = depthOffsets[d] + depthCounts[d]
        val sorted = IntArray(sqCount)
        val bucketFill = IntArray(maxDepth + 1)
        for (i in 0 until sqCount) {
            val d = sDepth[i]
            sorted[depthOffsets[d] + bucketFill[d]] = i
            bucketFill[d]++
        }

        // ── Pre-generate random color indices ───────────────────────────
        var randomCi: IntArray? = null
        if (colorMode == "random") {
            randomCi = IntArray(sqCount) { (rng.random() * 255 + 0.5f).toInt().coerceIn(0, 255) }
        }

        // ── Y range for gradient mode ───────────────────────────────────
        var gradMinY = Double.MAX_VALUE; var gradMaxY = -Double.MAX_VALUE
        if (colorMode == "gradient") {
            for (i in 0 until sqCount) {
                val cy = (p0y[i] + p2y[i]) * 0.5
                if (cy < gradMinY) gradMinY = cy
                if (cy > gradMaxY) gradMaxY = cy
            }
        }
        val gradRangeY = if (gradMaxY > gradMinY) gradMaxY - gradMinY else 1.0

        // ── Style flags ─────────────────────────────────────────────────
        val isGlow = style == "glow"
        val isNeon = style == "neon"
        val isWatercolor = style == "watercolor"
        val useQuad = shape == "square"
        val isFilled = fillMode == "filled"
        val isOutline = fillMode == "outline"
        val isMixed = fillMode == "mixed"
        val mixedThreshold = (maxDepth * 0.7f).toInt()
        val maxDepthF = maxDepth.coerceAtLeast(1).toFloat()

        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        if (isGlow) paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)

        val glowPaint = if (isNeon) Paint(Paint.ANTI_ALIAS_FLAG).also { gp ->
            gp.maskFilter = BlurMaskFilter(12f, BlurMaskFilter.Blur.NORMAL)
            gp.style = Paint.Style.STROKE
        } else null

        val path = Path()
        val hsv = FloatArray(3)

        // ── Draw shapes batched by depth ────────────────────────────────
        for (d in 0..maxDepth) {
            val start = depthOffsets[d]
            val count = depthCounts[d]
            if (count == 0) continue

            val isLeaf = d >= mixedThreshold
            val shouldFill = isFilled || (isMixed && isLeaf)
            val shouldStroke = isOutline || (isMixed && !isLeaf) || isNeon
            val strokeW = if (isNeon && isLeaf) lineWidth * 1.5f else lineWidth

            // Fast batch path: depth coloring + square shape + no per-square alpha
            val canBatch = colorMode == "depth" && useQuad && depthFade == 0f && !isWatercolor && !isNeon

            if (canBatch) {
                val color = computeColor(
                    sorted[start], d, maxDepthF, colorMode, lutR, lutG, lutB,
                    sAngle, p0y, p2y, gradMinY, gradRangeY, randomCi, hsv,
                    depthFade, isWatercolor, isGlow
                )

                if (shouldFill) {
                    paint.color = color
                    paint.style = Paint.Style.FILL
                    path.reset()
                    for (j in 0 until count) {
                        val si = sorted[start + j]
                        path.moveTo((p0x[si] * sc + offX).toFloat(), (-p0y[si] * sc + offY).toFloat())
                        path.lineTo((p1x[si] * sc + offX).toFloat(), (-p1y[si] * sc + offY).toFloat())
                        path.lineTo((p2x[si] * sc + offX).toFloat(), (-p2y[si] * sc + offY).toFloat())
                        path.lineTo((p3x[si] * sc + offX).toFloat(), (-p3y[si] * sc + offY).toFloat())
                        path.close()
                    }
                    canvas.drawPath(path, paint)
                }
                if (shouldStroke) {
                    paint.color = color
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = strokeW
                    path.reset()
                    for (j in 0 until count) {
                        val si = sorted[start + j]
                        path.moveTo((p0x[si] * sc + offX).toFloat(), (-p0y[si] * sc + offY).toFloat())
                        path.lineTo((p1x[si] * sc + offX).toFloat(), (-p1y[si] * sc + offY).toFloat())
                        path.lineTo((p2x[si] * sc + offX).toFloat(), (-p2y[si] * sc + offY).toFloat())
                        path.lineTo((p3x[si] * sc + offX).toFloat(), (-p3y[si] * sc + offY).toFloat())
                        path.close()
                    }
                    canvas.drawPath(path, paint)
                }
            } else {
                // Per-square rendering (other shapes, color modes, styles)
                for (j in 0 until count) {
                    val si = sorted[start + j]
                    val color = computeColor(
                        si, d, maxDepthF, colorMode, lutR, lutG, lutB,
                        sAngle, p0y, p2y, gradMinY, gradRangeY, randomCi, hsv,
                        depthFade, isWatercolor, isGlow
                    )

                    val sx0 = (p0x[si] * sc + offX).toFloat()
                    val sy0 = (-p0y[si] * sc + offY).toFloat()
                    val sx1 = (p1x[si] * sc + offX).toFloat()
                    val sy1 = (-p1y[si] * sc + offY).toFloat()
                    val sx2 = (p2x[si] * sc + offX).toFloat()
                    val sy2 = (-p2y[si] * sc + offY).toFloat()
                    val sx3 = (p3x[si] * sc + offX).toFloat()
                    val sy3 = (-p3y[si] * sc + offY).toFloat()

                    // Neon glow under-pass
                    if (isNeon) {
                        val cx = (sx0 + sx1 + sx2 + sx3) * 0.25f
                        val cy = (sy0 + sy1 + sy2 + sy3) * 0.25f
                        val edgeLen = sqrt((sx1 - sx0) * (sx1 - sx0) + (sy1 - sy0) * (sy1 - sy0))
                        val r = edgeLen * 0.5f
                        val rot = atan2(sy1 - sy0, sx1 - sx0)
                        glowPaint!!.color = color
                        glowPaint.strokeWidth = strokeW * 2f
                        path.reset()
                        traceShape(path, shape, cx, cy, r, rot, sx0, sy0, sx1, sy1, sx2, sy2, sx3, sy3)
                        canvas.drawPath(path, glowPaint)
                    }

                    if (shouldFill || shouldStroke) {
                        val cx = (sx0 + sx1 + sx2 + sx3) * 0.25f
                        val cy = (sy0 + sy1 + sy2 + sy3) * 0.25f
                        val edgeLen = sqrt((sx1 - sx0) * (sx1 - sx0) + (sy1 - sy0) * (sy1 - sy0))
                        val r = edgeLen * 0.5f
                        val rot = atan2(sy1 - sy0, sx1 - sx0)

                        if (shouldFill) {
                            paint.color = color
                            paint.style = Paint.Style.FILL
                            path.reset()
                            traceShape(path, shape, cx, cy, r, rot, sx0, sy0, sx1, sy1, sx2, sy2, sx3, sy3)
                            canvas.drawPath(path, paint)
                        }
                        if (shouldStroke) {
                            paint.color = color
                            paint.style = Paint.Style.STROKE
                            paint.strokeWidth = strokeW
                            path.reset()
                            traceShape(path, shape, cx, cy, r, rot, sx0, sy0, sx1, sy1, sx2, sy2, sx3, sy3)
                            canvas.drawPath(path, paint)
                        }
                    }
                }
            }
        }

        paint.xfermode = null
    }

    private fun computeColor(
        si: Int, d: Int, maxDepthF: Float,
        colorMode: String,
        lutR: IntArray, lutG: IntArray, lutB: IntArray,
        sAngle: FloatArray,
        p0y: DoubleArray, p2y: DoubleArray,
        gradMinY: Double, gradRangeY: Double,
        randomCi: IntArray?,
        hsv: FloatArray,
        depthFade: Float, isWatercolor: Boolean, isGlow: Boolean
    ): Int {
        val r: Int; val g: Int; val b: Int
        when (colorMode) {
            "depth" -> {
                val ci = ((d / maxDepthF) * 255 + 0.5f).toInt().coerceIn(0, 255)
                r = lutR[ci]; g = lutG[ci]; b = lutB[ci]
            }
            "angle" -> {
                val ci = (((sAngle[si] + PI.toFloat()) / (PI.toFloat() * 2f)) * 255 + 0.5f).toInt().coerceIn(0, 255)
                r = lutR[ci]; g = lutG[ci]; b = lutB[ci]
            }
            "gradient" -> {
                val cy = (p0y[si] + p2y[si]) * 0.5
                val ci = (((cy - gradMinY) / gradRangeY) * 255 + 0.5).toInt().coerceIn(0, 255)
                r = lutR[ci]; g = lutG[ci]; b = lutB[ci]
            }
            "rainbow" -> {
                val hue = ((d / maxDepthF) * 360f + sAngle[si] * 30f).mod(360f)
                hsv[0] = hue; hsv[1] = 0.82f; hsv[2] = 0.93f
                val rgb = Color.HSVToColor(hsv)
                return if (depthFade > 0f) {
                    val alpha = ((1f - depthFade * (d / maxDepthF)).coerceAtLeast(0.05f) * 255f).toInt()
                    Color.argb(alpha, Color.red(rgb), Color.green(rgb), Color.blue(rgb))
                } else if (isWatercolor) {
                    Color.argb(90, Color.red(rgb), Color.green(rgb), Color.blue(rgb))
                } else rgb
            }
            else -> {
                val ci = randomCi!![si]
                r = lutR[ci]; g = lutG[ci]; b = lutB[ci]
            }
        }

        if (depthFade > 0f) {
            val alpha = ((1f - depthFade * (d / maxDepthF)).coerceAtLeast(0.05f) * 255f).toInt()
            return Color.argb(alpha, r, g, b)
        }
        if (isWatercolor) return Color.argb(90, r, g, b)
        if (isGlow) return Color.rgb((r * 0.6f).toInt(), (g * 0.6f).toInt(), (b * 0.6f).toInt())
        return Color.rgb(r, g, b)
    }

    private fun traceShape(
        path: Path, shape: String,
        cx: Float, cy: Float, r: Float, rotAngle: Float,
        sx0: Float, sy0: Float, sx1: Float, sy1: Float,
        sx2: Float, sy2: Float, sx3: Float, sy3: Float
    ) {
        when (shape) {
            "square" -> {
                path.moveTo(sx0, sy0); path.lineTo(sx1, sy1)
                path.lineTo(sx2, sy2); path.lineTo(sx3, sy3); path.close()
            }
            "circle" -> path.addCircle(cx, cy, r, Path.Direction.CW)
            "diamond" -> {
                val cos = cos(rotAngle); val sin = sin(rotAngle)
                val dx = r * cos; val dy = r * sin
                val nx = -r * sin; val ny = r * cos
                path.moveTo(cx + dx, cy + dy); path.lineTo(cx + nx, cy + ny)
                path.lineTo(cx - dx, cy - dy); path.lineTo(cx - nx, cy - ny); path.close()
            }
            "triangle" -> {
                val twoPiOver3 = 2f * PI.toFloat() / 3f
                for (k in 0 until 3) {
                    val a = rotAngle + k * twoPiOver3 - PI.toFloat() / 2f
                    val px = cx + r * cos(a); val py = cy + r * sin(a)
                    if (k == 0) path.moveTo(px, py) else path.lineTo(px, py)
                }
                path.close()
            }
            "hexagon" -> {
                val piOver3 = PI.toFloat() / 3f
                for (k in 0 until 6) {
                    val a = rotAngle + k * piOver3
                    val px = cx + r * cos(a); val py = cy + r * sin(a)
                    if (k == 0) path.moveTo(px, py) else path.lineTo(px, py)
                }
                path.close()
            }
        }
    }

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float {
        val depth = ((params["depth"] as? Number)?.toInt() ?: 10).coerceAtMost(14)
        return ((1 shl (depth + 1)).toFloat() / 32768f).coerceIn(0.1f, 1f)
    }
}
