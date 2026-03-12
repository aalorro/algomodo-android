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
import com.artmondo.algomodo.rendering.SvgPath
import kotlin.math.*

/**
 * Filled topographic map generator.
 *
 * A noise height field is quantized into coloured elevation bands with optional
 * contour outlines, giving the look of a colour-printed geographic map.
 * Supports fBm, ridged multifractal, and turbulence noise fields.
 */
class PlotterContourLinesGenerator : Generator {

    override val id = "plotter-contour-lines"
    override val family = "plotter"
    override val styleName = "Contour Lines"
    override val definition =
        "Iso-value contour lines extracted from a simplex noise field via marching squares."
    override val algorithmNotes =
        "A 2D simplex noise field is sampled on a regular grid. For each contour level, " +
        "marching squares identifies cells that straddle the threshold and linearly " +
        "interpolates the crossing point along each cell edge. Connected segments form " +
        "smooth contour polylines."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema = listOf(
        Parameter.NumberParam("Levels", "levels", ParamGroup.COMPOSITION, "Number of elevation bands — each filled with a palette color", 3f, 24f, 1f, 10f),
        Parameter.NumberParam("Scale", "scale", ParamGroup.COMPOSITION, null, 0.5f, 8f, 0.25f, 2.5f),
        Parameter.NumberParam("Octaves", "octaves", ParamGroup.COMPOSITION, null, 1f, 8f, 1f, 5f),
        Parameter.SelectParam("Field Type", "fieldType", ParamGroup.GEOMETRY, "fbm: smooth rolling hills | ridged: sharp mountain ridges | turbulence: plasma/fire topology", listOf("fbm", "ridged", "turbulence"), "fbm"),
        Parameter.NumberParam("Cell Size", "cellSize", ParamGroup.GEOMETRY, "Marching-squares grid resolution — smaller = smoother band edges", 2f, 12f, 1f, 4f),
        Parameter.BooleanParam("Show Lines", "showLines", ParamGroup.COLOR, "Draw thin contour lines on band boundaries", true),
        Parameter.NumberParam("Line Width", "lineWidth", ParamGroup.COLOR, null, 0.25f, 3f, 0.25f, 0.5f),
        Parameter.SelectParam("Line Color", "lineColor", ParamGroup.COLOR, "dark: near-black lines | light: near-white lines | palette: each contour matches its band color", listOf("dark", "light", "palette"), "dark"),
        Parameter.NumberParam("Fill Opacity", "fillOpacity", ParamGroup.COLOR, "Opacity of the filled elevation bands — reduce for a ghostly overlay effect", 0.1f, 1f, 0.05f, 1.0f),
        Parameter.SelectParam("Anim Mode", "animMode", ParamGroup.FLOW_MOTION, "drift: field translates over time, bands flow like a slow liquid", listOf("drift", "none"), "drift"),
        Parameter.NumberParam("Speed", "speed", ParamGroup.FLOW_MOTION, null, 0.05f, 2f, 0.05f, 0.2f)
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "levels" to 10f,
        "scale" to 2.5f,
        "octaves" to 5f,
        "fieldType" to "fbm",
        "cellSize" to 4f,
        "showLines" to true,
        "lineWidth" to 0.5f,
        "lineColor" to "dark",
        "fillOpacity" to 1.0f,
        "animMode" to "drift",
        "speed" to 0.2f
    )

    // Marching squares lookup — case index = TL*8 | TR*4 | BR*2 | BL*1
    // Each entry lists [edgeA, edgeB] pairs. Edges: 0=top 1=right 2=bottom 3=left
    companion object {
        private val MS: Array<IntArray> = arrayOf(
            intArrayOf(),                       // 0
            intArrayOf(3, 2),                   // 1
            intArrayOf(2, 1),                   // 2
            intArrayOf(3, 1),                   // 3
            intArrayOf(0, 1),                   // 4
            intArrayOf(0, 1, 3, 2),             // 5 (saddle: two segments)
            intArrayOf(0, 2),                   // 6
            intArrayOf(3, 0),                   // 7
            intArrayOf(3, 0),                   // 8
            intArrayOf(0, 2),                   // 9
            intArrayOf(3, 0, 2, 1),             // 10 (saddle: two segments)
            intArrayOf(0, 1),                   // 11
            intArrayOf(3, 1),                   // 12
            intArrayOf(2, 1),                   // 13
            intArrayOf(3, 2),                   // 14
            intArrayOf()                        // 15
        )
    }

    /**
     * Compute interpolated edge point for marching squares.
     * cx, cy = top-left of cell in pixel space; cs = cell size in pixels.
     * edge: 0=top, 1=right, 2=bottom, 3=left
     */
    private fun edgePt(
        cx: Float, cy: Float, cs: Float, edge: Int,
        vTL: Float, vTR: Float, vBR: Float, vBL: Float, thr: Float
    ): FloatArray {
        fun lerp(a: Float, b: Float): Float {
            return if (abs(b - a) < 1e-9f) 0.5f
            else ((thr - a) / (b - a)).coerceIn(0f, 1f)
        }
        return when (edge) {
            0 -> floatArrayOf(cx + lerp(vTL, vTR) * cs, cy)
            1 -> floatArrayOf(cx + cs, cy + lerp(vTR, vBR) * cs)
            2 -> floatArrayOf(cx + lerp(vBL, vBR) * cs, cy + cs)
            3 -> floatArrayOf(cx, cy + lerp(vTL, vBL) * cs)
            else -> floatArrayOf(cx + cs / 2f, cy + cs / 2f)
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
        val w = bitmap.width
        val h = bitmap.height

        // ── Read parameters ─────────────────────────────────────────────────
        val levels    = ((params["levels"] as? Number)?.toInt() ?: 10).coerceAtLeast(2)
        val scale     = (params["scale"] as? Number)?.toFloat() ?: 2.5f
        val octaves   = ((params["octaves"] as? Number)?.toInt() ?: 5).coerceIn(1, 8)
        val fieldType = (params["fieldType"] as? String) ?: "fbm"
        val cs        = ((params["cellSize"] as? Number)?.toInt() ?: 4).coerceIn(2, 12)
        val showLines = (params["showLines"] as? Boolean) ?: true
        val lw        = (params["lineWidth"] as? Number)?.toFloat() ?: 0.5f
        val lineColor = (params["lineColor"] as? String) ?: "dark"
        val fillOp    = ((params["fillOpacity"] as? Number)?.toFloat() ?: 1.0f).coerceIn(0f, 1f)
        val animMode  = (params["animMode"] as? String) ?: "drift"
        val speed     = (params["speed"] as? Number)?.toFloat() ?: 0.2f
        val t         = time * speed

        val noise = SimplexNoise(seed)

        // Draft quality: coarser grid (double cell size)
        val step = if (quality == Quality.DRAFT) cs * 2 else cs

        val cols = (w.toFloat() / step).toInt() + 2
        val rows = (h.toFloat() / step).toInt() + 2

        val tOff = if (animMode == "drift") t * 0.06f else 0f

        // ── Evaluate the scalar field (matching web version exactly) ────────
        fun sampleField(nx: Float, ny: Float): Float {
            return when (fieldType) {
                "ridged" -> {
                    // Musgrave-style ridged multifractal (matches web)
                    val gain = 0.5f; val lac = 2.0f; val offset = 1.0f
                    var value = 0f; var weight = 1f; var amp = 1f; var freq = 1f
                    for (oct in 0 until octaves) {
                        var s = abs(noise.noise2D(nx * freq, ny * freq))
                        s = (offset - s).coerceAtLeast(0f)
                        s *= s
                        s *= weight
                        weight = (s * gain).coerceAtMost(1f)
                        value += s * amp
                        freq *= lac
                        amp *= gain
                    }
                    (value * (1f - gain)).coerceAtMost(1f)
                }
                "turbulence" -> {
                    // Turbulence (matches web)
                    var value = 0f; var amp = 1f; var freq = 1f; var maxV = 0f
                    for (oct in 0 until octaves) {
                        value += amp * abs(noise.noise2D(nx * freq, ny * freq))
                        maxV += amp
                        amp *= 0.5f
                        freq *= 2f
                    }
                    ((value / maxV) / 0.65f).coerceAtMost(1f)
                }
                else -> {
                    // fBm: map [-1,1] -> [0,1]
                    (noise.fbm(nx, ny, octaves, 2.0f, 0.5f) + 1f) * 0.5f
                }
            }
        }

        // Build field grid
        val field = FloatArray(cols * rows)
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val nx = (c.toFloat() / (cols - 1) - 0.5f) * scale * 4f + tOff
                val ny = (r.toFloat() / (rows - 1) - 0.5f) * scale * 4f + tOff * 0.67f
                field[r * cols + c] = sampleField(nx, ny)
            }
        }

        // ── Filled bands via pixel loop ─────────────────────────────────────
        val pixels = IntArray(w * h)
        val bandSize = 1f / levels
        val fillAlpha = (fillOp * 255f).toInt().coerceIn(0, 255)

        // Bilinear sample of field at pixel position
        fun fieldAt(px: Int, py: Int): Float {
            val fc = (px.toFloat() / w) * (cols - 1)
            val fr = (py.toFloat() / h) * (rows - 1)
            val c0 = fc.toInt()
            val c1 = (c0 + 1).coerceAtMost(cols - 1)
            val r0 = fr.toInt()
            val r1 = (r0 + 1).coerceAtMost(rows - 1)
            val tf = fc - c0
            val tr = fr - r0
            val v00 = field[r0 * cols + c0]
            val v10 = field[r0 * cols + c1]
            val v01 = field[r1 * cols + c0]
            val v11 = field[r1 * cols + c1]
            return v00 * (1 - tf) * (1 - tr) + v10 * tf * (1 - tr) +
                   v01 * (1 - tf) * tr + v11 * tf * tr
        }

        for (py in 0 until h) {
            for (px in 0 until w) {
                val v = fieldAt(px, py)
                val band = (v / bandSize).toInt().coerceIn(0, levels - 1)
                val tPalette = if (levels > 1) band.toFloat() / (levels - 1) else 0.5f
                val baseColor = palette.lerpColor(tPalette)
                val r = Color.red(baseColor)
                val g = Color.green(baseColor)
                val b = Color.blue(baseColor)
                pixels[py * w + px] = Color.argb(fillAlpha, r, g, b)
            }
        }

        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)

        // Draw background first, then composite fill on top
        // Since we set pixels directly with alpha, we need the background behind them.
        // Strategy: draw a black background on canvas, then draw the bitmap on top.
        canvas.drawColor(Color.BLACK)
        val bitmapPaint = Paint().apply {
            isAntiAlias = true
        }
        canvas.drawBitmap(bitmap, 0f, 0f, bitmapPaint)

        // ── Contour lines via marching squares ──────────────────────────────
        if (showLines) {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = lw
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }

            for (level in 0 until levels - 1) {
                val thr = (level + 1) * bandSize // iso-value at band boundary

                // Determine line color
                when (lineColor) {
                    "palette" -> {
                        val pc = palette.lerpColor(thr)
                        paint.color = Color.argb(
                            (0.7f * 255).toInt(),
                            Color.red(pc), Color.green(pc), Color.blue(pc)
                        )
                    }
                    "light" -> {
                        paint.color = Color.argb((0.7f * 255).toInt(), 220, 220, 220)
                    }
                    else -> { // "dark"
                        paint.color = Color.argb((0.7f * 255).toInt(), 20, 20, 20)
                    }
                }

                val path = Path()

                for (r in 0 until rows - 1) {
                    for (c in 0 until cols - 1) {
                        val vTL = field[r * cols + c]
                        val vTR = field[r * cols + (c + 1)]
                        val vBL = field[(r + 1) * cols + c]
                        val vBR = field[(r + 1) * cols + (c + 1)]

                        val mask = (if (vTL >= thr) 8 else 0) or
                                   (if (vTR >= thr) 4 else 0) or
                                   (if (vBR >= thr) 2 else 0) or
                                   (if (vBL >= thr) 1 else 0)

                        val segs = MS[mask]
                        if (segs.isEmpty()) continue

                        val cx = c.toFloat() * step
                        val cy = r.toFloat() * step

                        // Each segment is a pair of edge indices
                        var i = 0
                        while (i < segs.size) {
                            val eA = segs[i]
                            val eB = segs[i + 1]
                            val ptA = edgePt(cx, cy, step.toFloat(), eA, vTL, vTR, vBR, vBL, thr)
                            val ptB = edgePt(cx, cy, step.toFloat(), eB, vTL, vTR, vBR, vBL, thr)
                            path.moveTo(ptA[0], ptA[1])
                            path.lineTo(ptB[0], ptB[1])
                            i += 2
                        }
                    }
                }

                canvas.drawPath(path, paint)
            }
        }
    }

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float {
        val cs = (params["cellSize"] as? Number)?.toInt() ?: 4
        val levels = (params["levels"] as? Number)?.toInt() ?: 10
        val cells = (800f / cs).pow(2)
        return (cells * levels / 500f).toInt().toFloat().coerceIn(0.2f, 1f)
    }
}
