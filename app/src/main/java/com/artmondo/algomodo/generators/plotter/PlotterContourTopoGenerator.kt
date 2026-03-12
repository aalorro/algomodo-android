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
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Topographic map contour generator.
 *
 * Extracts elevation iso-contours from an FBM noise height field using
 * Marching Squares, producing clean topographic map line art.
 */
class PlotterContourTopoGenerator : Generator {

    override val id = "plotter-contour-topo"
    override val family = "plotter"
    override val styleName = "Topographic Contours"
    override val definition =
        "Extracts elevation iso-contours from an FBM noise height field using Marching Squares, producing clean topographic map line art"
    override val algorithmNotes =
        "A multi-octave SimplexNoise FBM field is sampled on a uniform grid. Marching Squares " +
        "walks each cell to produce linearly-interpolated contour segments at each iso-level. " +
        "Optional wobble displaces endpoints for a hand-drawn plotter aesthetic."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema = listOf(
        Parameter.NumberParam("Contour Count", "contourCount", ParamGroup.COMPOSITION, "Number of evenly-spaced iso-level lines", 4f, 30f, 1f, 14f),
        Parameter.NumberParam("Terrain Scale", "noiseScale", ParamGroup.COMPOSITION, "Spatial scale of the height field", 0.5f, 6f, 0.1f, 2.5f),
        Parameter.NumberParam("Octaves", "octaves", ParamGroup.COMPOSITION, null, 1f, 7f, 1f, 5f),
        Parameter.NumberParam("Cell Size", "cellSize", ParamGroup.GEOMETRY, "Marching-squares grid resolution — smaller = finer lines, slower", 2f, 14f, 1f, 4f),
        Parameter.NumberParam("Line Width", "lineWidth", ParamGroup.GEOMETRY, null, 0.25f, 3f, 0.25f, 0.7f),
        Parameter.NumberParam("Wobble", "wobble", ParamGroup.TEXTURE, "Hand-drawn jitter applied to contour endpoints", 0f, 4f, 0.25f, 0.4f),
        Parameter.SelectParam("Color Mode", "colorMode", ParamGroup.COLOR, "elevation-palette: color ramps with altitude | alternating: toggles palette ends", listOf("elevation-palette", "alternating", "monochrome"), "elevation-palette"),
        Parameter.SelectParam("Background", "background", ParamGroup.COLOR, null, listOf("white", "cream", "dark"), "cream"),
        Parameter.NumberParam("Anim Speed", "animSpeed", ParamGroup.FLOW_MOTION, "Speed at which the terrain drifts over time (0 = static)", 0f, 1f, 0.05f, 0.1f)
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "contourCount" to 14f,
        "noiseScale" to 2.5f,
        "octaves" to 5f,
        "cellSize" to 4f,
        "lineWidth" to 0.7f,
        "wobble" to 0.4f,
        "colorMode" to "elevation-palette",
        "background" to "cream",
        "animSpeed" to 0.1f
    )

    // Marching squares lookup: case index = TL*8|TR*4|BR*2|BL*1
    // Each entry is a list of [edgeA, edgeB] pairs to connect.
    // Edges: 0=top, 1=right, 2=bottom, 3=left
    private val MS: Array<IntArray> = arrayOf(
        intArrayOf(),                // 0000
        intArrayOf(3, 2),            // 0001 BL
        intArrayOf(2, 1),            // 0010 BR
        intArrayOf(3, 1),            // 0011 BL+BR
        intArrayOf(0, 1),            // 0100 TR
        intArrayOf(0, 1, 3, 2),      // 0101 TR+BL saddle
        intArrayOf(0, 2),            // 0110 TR+BR
        intArrayOf(3, 0),            // 0111 (not TL)
        intArrayOf(3, 0),            // 1000 TL
        intArrayOf(0, 2),            // 1001 TL+BL
        intArrayOf(3, 0, 2, 1),      // 1010 TL+BR saddle
        intArrayOf(0, 1),            // 1011 (not TR)
        intArrayOf(3, 1),            // 1100 TL+TR
        intArrayOf(2, 1),            // 1101 (not BR)
        intArrayOf(3, 2),            // 1110 (not BL)
        intArrayOf()                 // 1111
    )

    /** Background color map matching the web version. */
    private val BG = mapOf(
        "white" to Color.rgb(248, 248, 245),
        "cream" to Color.rgb(242, 234, 216),
        "dark"  to Color.rgb(14, 14, 14)
    )

    /** Linearly interpolated position along a cell edge. */
    private fun edgePt(
        cx: Float, cy: Float, cs: Float,
        edge: Int,
        vTL: Float, vTR: Float, vBR: Float, vBL: Float,
        thr: Float,
        result: FloatArray // reusable [x, y] output
    ) {
        fun t(a: Float, b: Float): Float {
            return if (abs(b - a) < 1e-9f) 0.5f else ((thr - a) / (b - a)).coerceIn(0f, 1f)
        }
        when (edge) {
            0 -> { result[0] = cx + t(vTL, vTR) * cs; result[1] = cy }
            1 -> { result[0] = cx + cs;               result[1] = cy + t(vTR, vBR) * cs }
            2 -> { result[0] = cx + t(vBL, vBR) * cs; result[1] = cy + cs }
            3 -> { result[0] = cx;                     result[1] = cy + t(vTL, vBL) * cs }
            else -> { result[0] = cx + cs / 2f; result[1] = cy + cs / 2f }
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

        // Read parameters
        val background = params["background"] as? String ?: "cream"
        val colorMode = params["colorMode"] as? String ?: "elevation-palette"
        val nLevels = max(2, (params["contourCount"] as? Number)?.toInt() ?: 14)
        val scale = (params["noiseScale"] as? Number)?.toFloat() ?: 2.5f
        val oct = (params["octaves"] as? Number)?.toInt() ?: 5
        val cs = max(2, (params["cellSize"] as? Number)?.toInt() ?: 4)
        val lineWidth = (params["lineWidth"] as? Number)?.toFloat() ?: 0.7f
        val wobble = (params["wobble"] as? Number)?.toFloat() ?: 0.4f
        val animSpeed = (params["animSpeed"] as? Number)?.toFloat() ?: 0.1f
        val isDark = background == "dark"
        val tOff = time * animSpeed * 0.25f

        // Fill background
        val bgColor = BG[background] ?: BG["cream"]!!
        canvas.drawColor(bgColor)

        val noise = SimplexNoise(seed)
        val rng = SeededRNG(seed)

        val cols = (ceil(w.toFloat() / cs).toInt()) + 1
        val rows = (ceil(h.toFloat() / cs).toInt()) + 1

        // Sample height field (centered + time-translated for animation)
        val field = Array(rows) { FloatArray(cols) }
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                field[r][c] = noise.fbm(
                    (c.toFloat() / (cols - 1) - 0.5f) * scale + tOff,
                    (r.toFloat() / (rows - 1) - 0.5f) * scale + tOff * 0.6f,
                    oct, 2f, 0.5f
                )
            }
        }

        // Global min/max for normalisation
        var fMin = Float.MAX_VALUE
        var fMax = -Float.MAX_VALUE
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                if (field[r][c] < fMin) fMin = field[r][c]
                if (field[r][c] > fMax) fMax = field[r][c]
            }
        }
        val fRange = max(fMax - fMin, 1e-6f)

        // Extract palette colors as RGB triples for manual interpolation
        val paletteColors = palette.colorInts()
        val colorTriples = paletteColors.map { c ->
            intArrayOf(Color.red(c), Color.green(c), Color.blue(c))
        }

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = lineWidth
            strokeCap = Paint.Cap.ROUND
        }

        // Reusable arrays for edge point computation
        val ptA = FloatArray(2)
        val ptB = FloatArray(2)

        for (lvl in 0 until nLevels) {
            val t = (lvl + 0.5f) / nLevels // 0->1 across all levels
            val threshold = fMin + t * fRange

            // Compute line color for this level
            val r: Int
            val g: Int
            val b: Int
            when (colorMode) {
                "monochrome" -> {
                    val v = if (isDark) (220 - lvl * 8).coerceIn(0, 255)
                            else (40 + lvl * 8).coerceIn(0, 255)
                    r = v; g = v; b = v
                }
                "alternating" -> {
                    val col = colorTriples[if (lvl % 2 == 0) 0 else colorTriples.size - 1]
                    r = col[0]; g = col[1]; b = col[2]
                }
                else -> {
                    // elevation-palette: interpolate across palette
                    val ci = t * (colorTriples.size - 1)
                    val i0 = floor(ci).toInt()
                    val i1 = min(colorTriples.size - 1, i0 + 1)
                    val f = ci - i0
                    r = (colorTriples[i0][0] + (colorTriples[i1][0] - colorTriples[i0][0]) * f).toInt().coerceIn(0, 255)
                    g = (colorTriples[i0][1] + (colorTriples[i1][1] - colorTriples[i0][1]) * f).toInt().coerceIn(0, 255)
                    b = (colorTriples[i0][2] + (colorTriples[i1][2] - colorTriples[i0][2]) * f).toInt().coerceIn(0, 255)
                }
            }

            val alpha = if (isDark) 217 else 204 // ~0.85 and ~0.8
            paint.color = Color.argb(alpha, r, g, b)

            val path = Path()

            for (row in 0 until rows - 1) {
                for (col in 0 until cols - 1) {
                    val vTL = field[row][col]
                    val vTR = field[row][col + 1]
                    val vBR = field[row + 1][col + 1]
                    val vBL = field[row + 1][col]

                    val caseIdx =
                        (if (vTL >= threshold) 8 else 0) or
                        (if (vTR >= threshold) 4 else 0) or
                        (if (vBR >= threshold) 2 else 0) or
                        (if (vBL >= threshold) 1 else 0)

                    val segments = MS[caseIdx]
                    if (segments.isEmpty()) continue

                    val cx = (col * cs).toFloat()
                    val cy = (row * cs).toFloat()

                    // Iterate pairs: segments stored as [eA, eB, eA, eB, ...]
                    var i = 0
                    while (i < segments.size) {
                        val eA = segments[i]
                        val eB = segments[i + 1]
                        edgePt(cx, cy, cs.toFloat(), eA, vTL, vTR, vBR, vBL, threshold, ptA)
                        edgePt(cx, cy, cs.toFloat(), eB, vTL, vTR, vBR, vBL, threshold, ptB)

                        // Wobble: hand-drawn jitter on endpoints
                        val wx = (rng.random() - 0.5f) * wobble
                        val wy = (rng.random() - 0.5f) * wobble

                        path.moveTo(ptA[0] + wx, ptA[1] + wy)
                        path.lineTo(ptB[0] + wx, ptB[1] + wy)

                        i += 2
                    }
                }
            }

            canvas.drawPath(path, paint)
        }
    }

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float {
        val cs = (params["cellSize"] as? Number)?.toInt() ?: 4
        val levels = (params["contourCount"] as? Number)?.toInt() ?: 14
        return (levels * (1080f / cs) * (1080f / cs) * 0.002f).coerceIn(0.3f, 1f)
    }
}
