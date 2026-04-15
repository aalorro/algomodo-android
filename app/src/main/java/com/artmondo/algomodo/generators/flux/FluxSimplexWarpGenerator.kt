package com.artmondo.algomodo.generators.flux

import android.graphics.*
import com.artmondo.algomodo.audio.AudioAnalysis
import com.artmondo.algomodo.core.rng.SimplexNoise
import com.artmondo.algomodo.data.palettes.Palette
import com.artmondo.algomodo.generators.Generator
import com.artmondo.algomodo.generators.ParamGroup
import com.artmondo.algomodo.generators.Parameter
import com.artmondo.algomodo.generators.Quality
import com.artmondo.algomodo.generators.procedural.ValueNoise
import kotlin.math.*

/**
 * A regular grid of points displaced by layered simplex noise FBM, rendered as
 * a deformed mesh, pixel heatmap, or scattered dots — visualizing the topology
 * of a continuous warp field.
 *
 * Builds a (gridSize+1)×(gridSize+1) vertex grid spanning the canvas. Each vertex
 * is displaced by two independent FBM evaluations (dx, dy) with phase offsets
 * (100, 200, 300) to decorrelate axes. Time animates by scrolling the noise Y
 * offset. Three render modes: mesh draws quad outlines per grid cell with
 * displacement-mapped palette color; heatmap evaluates per-pixel displacement
 * magnitude using fast value noise with a palette LUT; dots draws circles at
 * each displaced vertex sized by displacement magnitude.
 */
class FluxSimplexWarpGenerator : Generator {

    override val id = "flux-simplex-warp"
    override val family = "flux"
    override val styleName = "Simplex Warp"
    override val definition =
        "A regular grid of points displaced by layered simplex noise FBM, rendered as a deformed mesh, " +
        "pixel heatmap, or scattered dots \u2014 visualizing the topology of a continuous warp field"
    override val algorithmNotes =
        "Builds a (gridSize+1)x(gridSize+1) vertex grid spanning the canvas. Each vertex is displaced by " +
        "two independent FBM evaluations (dx, dy) with phase offsets (100, 200, 300) to decorrelate axes. " +
        "Time animates by scrolling the noise Y offset. Three render modes: mesh draws quad outlines per " +
        "grid cell with displacement-mapped palette color; heatmap bilinearly interpolates displacement " +
        "magnitude across all pixels using a pre-computed palette LUT and cached pixel buffer; dots draws " +
        "circles at each displaced vertex sized by displacement magnitude. Pre-computed FloatArray vertex " +
        "positions and magnitudes. Audio bass modulates warpAmount, mid modulates noiseScale."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema: List<Parameter> = listOf(
        Parameter.SelectParam(
            "Render Mode", "renderMode", ParamGroup.COMPOSITION,
            "mesh: quad outlines colored by displacement | heatmap: pixel-level interpolated displacement map | dots: circles at displaced vertices",
            listOf("mesh", "heatmap", "dots"), "mesh"
        ),
        Parameter.NumberParam(
            "Grid Size", "gridSize", ParamGroup.GEOMETRY,
            "Number of grid divisions along each axis",
            10f, 80f, 1f, 30f
        ),
        Parameter.NumberParam(
            "Noise Scale", "noiseScale", ParamGroup.GEOMETRY,
            "Frequency of the simplex noise field \u2014 higher = more turbulent",
            0.5f, 5f, 0.1f, 2.0f
        ),
        Parameter.NumberParam(
            "Warp Amount", "warpAmount", ParamGroup.GEOMETRY,
            "Strength of vertex displacement relative to cell size",
            0.1f, 3f, 0.1f, 1.0f
        ),
        Parameter.NumberParam(
            "Octaves", "octaves", ParamGroup.COMPOSITION,
            "FBM layers \u2014 more octaves = finer warp detail",
            1f, 4f, 1f, 3f
        ),
        Parameter.NumberParam(
            "Line Width", "lineWidth", ParamGroup.TEXTURE,
            "Stroke width for mesh and dot outlines",
            0.5f, 3f, 0.1f, 1.0f
        ),
        Parameter.NumberParam(
            "Speed", "speed", ParamGroup.FLOW_MOTION,
            "Animation drift speed through the noise field",
            0.1f, 3f, 0.05f, 0.5f
        ),
        Parameter.NumberParam(
            "Audio Reactivity", "reactivity", ParamGroup.FLOW_MOTION,
            "Sensitivity to audio input (0 = none)",
            0f, 2f, 0.1f, 1.0f
        )
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "renderMode" to "mesh",
        "gridSize" to 30f,
        "noiseScale" to 2.0f,
        "warpAmount" to 1.0f,
        "octaves" to 3f,
        "lineWidth" to 1.0f,
        "speed" to 0.5f,
        "reactivity" to 1.0f
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

        // ── Extract parameters ──────────────────────────────────────────
        val renderMode = (params["renderMode"] as? String) ?: "mesh"
        val gridSize = ((params["gridSize"] as? Number)?.toInt() ?: 30).coerceIn(4, 80)
        val noiseScaleParam = (params["noiseScale"] as? Number)?.toFloat() ?: 2.0f
        val warpAmountParam = (params["warpAmount"] as? Number)?.toFloat() ?: 1.0f
        val octaves = ((params["octaves"] as? Number)?.toInt() ?: 3).coerceIn(1, 4)
        val lineWidth = (params["lineWidth"] as? Number)?.toFloat() ?: 1.0f
        val spd = (params["speed"] as? Number)?.toFloat() ?: 0.5f
        val rx = (params["reactivity"] as? Number)?.toFloat() ?: 1.0f

        // ── Audio modulation ────────────────────────────────────────────
        val audioAnalysis = params["_audioAnalysis"] as? AudioAnalysis
        val audioBass = (audioAnalysis?.getBass(time) ?: 0f) * rx
        val audioMid = (audioAnalysis?.getMid(time) ?: 0f) * rx

        // Bass modulates warpAmount, mid modulates noiseScale
        val warpAmount = warpAmountParam * (1f + audioBass * 2f)
        val noiseScale = noiseScaleParam * (1f + audioMid * 0.5f)

        // ── Noise instance ──────────────────────────────────────────────
        val noise = SimplexNoise(seed)

        // ── Grid geometry ───────────────────────────────────────────────
        val cols = gridSize + 1
        val rows = gridSize + 1
        val cellW = w.toFloat() / gridSize
        val cellH = h.toFloat() / gridSize
        val cellSize = min(cellW, cellH)

        // Time offset for animation — scrolls noise Y
        val t = time * spd

        // ── Parse palette to RGB arrays ─────────────────────────────────
        val colorInts = palette.colorInts()
        val numColors = colorInts.size
        val numColorsM1 = numColors - 1
        val rawR = IntArray(numColors)
        val rawG = IntArray(numColors)
        val rawB = IntArray(numColors)
        for (i in 0 until numColors) {
            val c = colorInts[i]
            rawR[i] = Color.red(c)
            rawG[i] = Color.green(c)
            rawB[i] = Color.blue(c)
        }

        // ── Render mode dispatch ────────────────────────────────────────
        if (renderMode == "heatmap") {
            renderHeatmap(
                bitmap, canvas, w, h, seed, noiseScale, warpAmount, octaves,
                cellSize, t, quality, time > 0f, numColors, numColorsM1,
                rawR, rawG, rawB
            )
        } else {
            // Mesh and Dots modes need pre-computed displaced vertices
            renderMeshOrDots(
                canvas, bitmap, w, h, renderMode, gridSize, cols, rows,
                cellW, cellH, cellSize, noiseScale, warpAmount, octaves,
                lineWidth, t, noise, numColors, numColorsM1, rawR, rawG, rawB,
                quality
            )
        }
    }

    // ── MESH / DOTS: Pre-compute displaced vertices, then draw ──────────
    private fun renderMeshOrDots(
        canvas: Canvas,
        bitmap: Bitmap,
        w: Int,
        h: Int,
        renderMode: String,
        gridSize: Int,
        cols: Int,
        rows: Int,
        cellW: Float,
        cellH: Float,
        cellSize: Float,
        noiseScale: Float,
        warpAmount: Float,
        octaves: Int,
        lineWidth: Float,
        t: Float,
        noise: SimplexNoise,
        numColors: Int,
        numColorsM1: Int,
        rawR: IntArray,
        rawG: IntArray,
        rawB: IntArray,
        quality: Quality
    ) {
        val totalVerts = cols * rows
        val vx = FloatArray(totalVerts)
        val vy = FloatArray(totalVerts)
        val mag = FloatArray(totalVerts)

        var maxMag = 0f

        for (iy in 0 until rows) {
            val baseY = iy * cellH
            val gy = iy.toFloat() / gridSize // normalized 0..1
            val rowOff = iy * cols

            for (ix in 0 until cols) {
                val baseX = ix * cellW
                val gx = ix.toFloat() / gridSize // normalized 0..1

                // FBM displacement with phase offsets to decorrelate dx/dy
                val dx = noise.fbm(
                    gx * noiseScale,
                    gy * noiseScale + 100f + t * 0.2f,
                    octaves, 2f, 0.5f
                ) * warpAmount * cellSize

                val dy = noise.fbm(
                    gx * noiseScale + 200f,
                    gy * noiseScale + 300f + t * 0.2f,
                    octaves, 2f, 0.5f
                ) * warpAmount * cellSize

                val idx = rowOff + ix
                vx[idx] = baseX + dx
                vy[idx] = baseY + dy

                val m = sqrt(dx * dx + dy * dy)
                mag[idx] = m
                if (m > maxMag) maxMag = m
            }
        }

        // Normalize magnitudes to 0..1
        val invMaxMag = if (maxMag > 0f) 1f / maxMag else 0f
        for (i in 0 until totalVerts) {
            mag[i] *= invMaxMag
        }

        // Fill background
        canvas.drawColor(Color.BLACK)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        if (renderMode == "mesh") {
            // ── MESH MODE: Draw quad outlines colored by displacement ────
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = lineWidth
            paint.strokeJoin = Paint.Join.ROUND
            val path = Path()

            for (iy in 0 until gridSize) {
                val rowOff = iy * cols
                val nextRowOff = (iy + 1) * cols

                for (ix in 0 until gridSize) {
                    // Four corners of the quad
                    val tl = rowOff + ix
                    val tr = rowOff + ix + 1
                    val bl = nextRowOff + ix
                    val br = nextRowOff + ix + 1

                    // Average magnitude for color
                    val avgMag = (mag[tl] + mag[tr] + mag[bl] + mag[br]) * 0.25f

                    // Map magnitude to palette color via interpolation
                    val ci = avgMag * numColorsM1
                    val i0 = ci.toInt()
                    val i1 = if (i0 < numColorsM1) i0 + 1 else numColorsM1
                    val f = ci - i0
                    val cr = (rawR[i0] + (rawR[i1] - rawR[i0]) * f).toInt()
                    val cg = (rawG[i0] + (rawG[i1] - rawG[i0]) * f).toInt()
                    val cb = (rawB[i0] + (rawB[i1] - rawB[i0]) * f).toInt()

                    paint.color = Color.rgb(cr, cg, cb)

                    path.reset()
                    path.moveTo(vx[tl], vy[tl])
                    path.lineTo(vx[tr], vy[tr])
                    path.lineTo(vx[br], vy[br])
                    path.lineTo(vx[bl], vy[bl])
                    path.close()
                    canvas.drawPath(path, paint)
                }
            }

        } else {
            // ── DOTS MODE: Circle at each displaced vertex ──────────────
            paint.style = Paint.Style.FILL
            val maxRadius = cellSize * 0.4f
            val minRadius = 1f

            for (iy in 0 until rows) {
                val rowOff = iy * cols

                for (ix in 0 until cols) {
                    val idx = rowOff + ix
                    val m = mag[idx]

                    // Size proportional to displacement magnitude
                    val radius = minRadius + m * (maxRadius - minRadius)

                    // Map magnitude to palette color via interpolation
                    val ci = m * numColorsM1
                    val i0 = ci.toInt()
                    val i1 = if (i0 < numColorsM1) i0 + 1 else numColorsM1
                    val f = ci - i0
                    val cr = (rawR[i0] + (rawR[i1] - rawR[i0]) * f).toInt()
                    val cg = (rawG[i0] + (rawG[i1] - rawG[i0]) * f).toInt()
                    val cb = (rawB[i0] + (rawB[i1] - rawB[i0]) * f).toInt()

                    paint.color = Color.rgb(cr, cg, cb)
                    canvas.drawCircle(vx[idx], vy[idx], radius, paint)
                }
            }
        }
    }

    // ── HEATMAP: Per-pixel value noise for crisp detail ─────────────────
    private fun renderHeatmap(
        bitmap: Bitmap,
        canvas: Canvas,
        w: Int,
        h: Int,
        seed: Int,
        noiseScale: Float,
        warpAmount: Float,
        octaves: Int,
        cellSize: Float,
        t: Float,
        quality: Quality,
        isAnimating: Boolean,
        numColors: Int,
        numColorsM1: Int,
        rawR: IntArray,
        rawG: IntArray,
        rawB: IntArray
    ) {
        var step = if (quality == Quality.DRAFT) 3 else 1
        if (isAnimating && step < 2) step = 2

        // ── Value noise for fast per-pixel evaluation ────────────────────
        val vn = ValueNoise(seed)

        // ── Precompute FBM weights ───────────────────────────────────────
        val maxOct = if (quality == Quality.DRAFT) octaves.coerceAtMost(2) else octaves
        val fbmAmp = FloatArray(maxOct)
        val fbmFreq = FloatArray(maxOct)
        var fbmTotal = 0f
        run {
            var amp = 1f
            var freq = 1f
            for (o in 0 until maxOct) {
                fbmAmp[o] = amp
                fbmFreq[o] = freq
                fbmTotal += amp
                freq *= 2f
                amp *= 0.5f
            }
        }
        val fbmInv = 1f / fbmTotal

        // ── Palette LUT (256 entries for fast indexed access) ────────────
        val palLUT = IntArray(256)
        for (i in 0 until 256) {
            val tv = i / 255f
            val ci = tv * numColorsM1
            val i0 = ci.toInt()
            val i1 = if (i0 < numColorsM1) i0 + 1 else numColorsM1
            val f = ci - i0
            val cr = (rawR[i0] + (rawR[i1] - rawR[i0]) * f).toInt()
            val cg = (rawG[i0] + (rawG[i1] - rawG[i0]) * f).toInt()
            val cb = (rawB[i0] + (rawB[i1] - rawB[i0]) * f).toInt()
            palLUT[i] = Color.rgb(cr, cg, cb)
        }

        // ── Per-pixel noise evaluation ───────────────────────────────────
        val invW = noiseScale / w
        val invH = noiseScale / h
        val tOff = t * 0.2f
        val warpCellSize = cellSize * warpAmount

        val cols = ceil(w.toFloat() / step).toInt()
        val rows = ceil(h.toFloat() / step).toInt()
        val smallPixels = IntArray(cols * rows)

        for (gy in 0 until rows) {
            val py = gy * step
            val ny = py * invH
            val nyA = ny + 100f + tOff
            val nyB = ny + 300f + tOff

            for (gx in 0 until cols) {
                val px = gx * step
                val nx = px * invW

                // Inline FBM for du and dv
                var du = 0f
                var dv = 0f
                for (o in 0 until maxOct) {
                    val f = fbmFreq[o]
                    val a = fbmAmp[o]
                    du += vn.noise(nx * f, nyA * f) * a
                    dv += vn.noise((nx + 200f) * f, nyB * f) * a
                }
                du *= fbmInv
                dv *= fbmInv

                // Displacement magnitude -> [0, 1]
                val magSq = du * du + dv * dv
                var v = sqrt(magSq) * 0.707f
                if (v > 1f) v = 1f

                // Quantize to discrete palette bands for crisp color steps
                val qi = (v * numColors).toInt()
                v = (if (qi < numColors) qi else numColors - 1).toFloat() / numColorsM1

                val pixel = palLUT[(v * 255f).toInt().coerceIn(0, 255)]
                smallPixels[gy * cols + gx] = pixel
            }
        }

        // ── Bilinear upscale if step > 1 ────────────────────────────────
        if (step > 1) {
            val smallBmp = Bitmap.createBitmap(cols, rows, Bitmap.Config.ARGB_8888)
            smallBmp.setPixels(smallPixels, 0, cols, 0, 0, cols, rows)
            val scaled = Bitmap.createScaledBitmap(smallBmp, w, h, true)
            val pixels = IntArray(w * h)
            scaled.getPixels(pixels, 0, w, 0, 0, w, h)
            bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
            smallBmp.recycle()
            scaled.recycle()
        } else {
            bitmap.setPixels(smallPixels, 0, w, 0, 0, w, h)
        }
        canvas.drawBitmap(bitmap, 0f, 0f, null)
    }

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float {
        val grid = (params["gridSize"] as? Number)?.toInt() ?: 30
        val oct = (params["octaves"] as? Number)?.toInt() ?: 3
        val mode = (params["renderMode"] as? String) ?: "mesh"
        val modeMul = when (mode) {
            "heatmap" -> 2.5f
            "dots" -> 0.8f
            else -> 1.0f
        }
        // Normalize: raw max case ~80*80*4*2*2.5+100 = 128100
        val raw = (grid * grid * oct * 2f + 100f) * modeMul
        return (raw / 128100f).coerceIn(0.05f, 1f)
    }
}
