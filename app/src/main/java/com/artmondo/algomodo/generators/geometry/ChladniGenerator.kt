package com.artmondo.algomodo.generators.geometry

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import com.artmondo.algomodo.data.palettes.Palette
import com.artmondo.algomodo.generators.Generator
import com.artmondo.algomodo.generators.ParamGroup
import com.artmondo.algomodo.generators.Parameter
import com.artmondo.algomodo.generators.Quality
import kotlin.math.*

class ChladniGenerator : Generator {

    override val id = "chladni"
    override val family = "geometry"
    override val styleName = "Chladni Patterns"
    override val definition =
        "Chladni vibration figures showing nodal patterns of a vibrating plate, mapped to palette colors."
    override val algorithmNotes =
        "Evaluates the Chladni equation cos(m*pi*x)*cos(n*pi*y) - cos(n*pi*x)*cos(m*pi*y) " +
        "for each pixel, where x and y are normalized to [-1,1]. Supports square, circular, " +
        "sum, and product formulas. Color modes: nodal lines, full amplitude, phase, or signed S-curve."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema: List<Parameter> = listOf(
        Parameter.NumberParam("M Frequency", "m", ParamGroup.GEOMETRY,
            "Horizontal mode number \u2014 together with N determines the resonant mode shape",
            1f, 12f, 1f, 3f),
        Parameter.NumberParam("N Frequency", "n", ParamGroup.GEOMETRY,
            "Vertical mode number", 1f, 12f, 1f, 5f),
        Parameter.NumberParam("Line Width", "tolerance", ParamGroup.GEOMETRY,
            "Threshold around zero \u2014 wider = thicker nodal lines",
            0.005f, 0.12f, 0.005f, 0.025f),
        Parameter.SelectParam("Formula", "formula", ParamGroup.COMPOSITION,
            "square: classic rectangular plate | circular: circular membrane, Bessel-like rings | sum: additive superposition | product: multiplicative coupling",
            listOf("square", "circular", "sum", "product"), "square"),
        Parameter.NumberParam("Beat Mix", "beatMix", ParamGroup.COMPOSITION,
            "Blend between mode (m, n) and mode (n, m) with a time-oscillating weight. 0 = pure (m,n) mode. 1 = full beat oscillation.",
            0f, 1f, 0.05f, 0f),
        Parameter.SelectParam("Color Mode", "colorMode", ParamGroup.COLOR,
            "nodal: only nodal lines lit | amplitude: full field filled by wave amplitude | phase: positive and negative regions | signed: smooth tanh S-curve",
            listOf("nodal", "amplitude", "phase", "signed"), "nodal"),
        Parameter.NumberParam("Speed", "speed", ParamGroup.FLOW_MOTION,
            "Phase evolution speed \u2014 animates nodal line morphing",
            0.05f, 3f, 0.05f, 0.5f)
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "m" to 3f, "n" to 5f, "tolerance" to 0.025f,
        "formula" to "square", "beatMix" to 0f,
        "colorMode" to "nodal", "speed" to 0.5f
    )

    // Pre-allocated trig buffers for separable rendering
    @Volatile private var colA = FloatArray(0)
    @Volatile private var colB = FloatArray(0)
    @Volatile private var rowA = FloatArray(0)
    @Volatile private var rowB = FloatArray(0)

    private fun ensureTrig(cols: Int, rows: Int) {
        if (colA.size < cols) { colA = FloatArray(cols); colB = FloatArray(cols) }
        if (rowA.size < rows) { rowA = FloatArray(rows); rowB = FloatArray(rows) }
    }

    override fun renderCanvas(
        canvas: Canvas, bitmap: Bitmap, params: Map<String, Any>,
        seed: Int, palette: Palette, quality: Quality, time: Float
    ) {
        val w = bitmap.width; val h = bitmap.height

        val m = max(1, ((params["m"] as? Number)?.toInt() ?: 3))
        val n = max(1, ((params["n"] as? Number)?.toInt() ?: 5))
        val tolerance = max(0.001f, (params["tolerance"] as? Number)?.toFloat() ?: 0.025f)
        val formula = (params["formula"] as? String) ?: "square"
        val beatMix = ((params["beatMix"] as? Number)?.toFloat() ?: 0f).coerceIn(0f, 1f)
        val colorMode = (params["colorMode"] as? String) ?: "nodal"
        val speed = (params["speed"] as? Number)?.toFloat() ?: 0.5f
        val phase = time * speed

        // Render at half-res for circular during animation, or draft quality
        val isCirc = formula == "circular"
        val halfRes = quality == Quality.DRAFT || (isCirc && time > 0f)
        val rw = if (halfRes) (w + 1) shr 1 else w
        val rh = if (halfRes) (h + 1) shr 1 else h
        val total = rw * rh

        val doBeat = beatMix > 0f && m != n
        val beatW = if (doBeat) beatMix * (0.5f + 0.5f * sin(phase * 0.8f)) else 0f
        val invRW = 2f / rw; val invRH = 2f / rh

        // Pre-build palette LUT (256 ARGB entries)
        val palLut = palette.buildLut(256)

        // Pass 1: compute Chladni values
        val vb = FloatArray(total)

        if (!isCirc) {
            // Separable trig: pre-compute cos for each column/row
            ensureTrig(rw, rh)
            val mpi = (m * PI).toFloat(); val npi = (n * PI).toFloat()

            if (formula == "product") {
                val p6 = phase * 0.6f
                for (px in 0 until rw) {
                    val xx = px * invRW - 1f
                    colA[px] = cos(mpi * xx + phase) * cos(npi * xx)
                    if (doBeat) colB[px] = cos(npi * xx + phase) * cos(mpi * xx)
                }
                for (py in 0 until rh) {
                    val yy = py * invRH - 1f
                    rowA[py] = cos(npi * yy) * cos(mpi * yy + p6)
                    if (doBeat) rowB[py] = cos(mpi * yy) * cos(npi * yy + p6)
                }
                val bw1 = 1f - beatW
                var off = 0
                for (py in 0 until rh) {
                    val ra = rowA[py]; val rb = rowB[py]
                    for (px in 0 until rw) {
                        var v = colA[px] * ra
                        if (doBeat) v = bw1 * v + beatW * colB[px] * rb
                        vb[off++] = v
                    }
                }
            } else {
                // square / sum share same 4 trig arrays
                for (px in 0 until rw) {
                    val xx = px * invRW - 1f
                    colA[px] = cos(npi * xx + phase) // cos(nπx+φ)
                    colB[px] = cos(mpi * xx + phase) // cos(mπx+φ)
                }
                for (py in 0 until rh) {
                    val yy = py * invRH - 1f
                    rowA[py] = cos(mpi * yy) // cos(mπy)
                    rowB[py] = cos(npi * yy) // cos(nπy)
                }
                var off = 0
                if (formula == "sum") {
                    for (py in 0 until rh) {
                        val ra = rowA[py]; val rb = rowB[py]
                        for (px in 0 until rw) {
                            vb[off++] = colB[px] * rb + colA[px] * ra
                        }
                    }
                } else if (m == n) {
                    // m==n: classic formula is zero. Use single-mode variant.
                    for (px in 0 until rw) {
                        val xx = px * invRW - 1f
                        colB[px] = cos(npi * xx) // cos(nπx) — no phase
                    }
                    for (py in 0 until rh) {
                        val yy = py * invRH - 1f
                        rowB[py] = cos(npi * yy + phase) // cos(nπy+φ)
                    }
                    for (py in 0 until rh) {
                        val ra = rowA[py]; val rb = rowB[py]
                        for (px in 0 until rw) {
                            vb[off++] = colA[px] * ra + colB[px] * rb
                        }
                    }
                } else {
                    // square = cos(nπx+φ)·cos(mπy) − cos(mπx+φ)·cos(nπy)
                    val f = if (doBeat) (1f - 2f * beatW) else 1f
                    for (py in 0 until rh) {
                        val ra = rowA[py]; val rb = rowB[py]
                        for (px in 0 until rw) {
                            vb[off++] = (colA[px] * ra - colB[px] * rb) * f
                        }
                    }
                }
            }
        } else {
            // Circular: per-pixel trig (not separable)
            val mpi = (m * PI).toFloat(); val npi = (n * PI).toFloat()
            val p7 = phase * 0.7f; val bw1 = 1f - beatW
            var off = 0
            for (py in 0 until rh) {
                val yy = py * invRH - 1f; val yySq = yy * yy
                for (px in 0 until rw) {
                    val xx = px * invRW - 1f
                    val r = sqrt(xx * xx + yySq)
                    val theta = atan2(yy, xx)
                    var v = cos(mpi * r + phase) * cos(n * theta + p7)
                    if (doBeat) v = bw1 * v + beatW * cos(npi * r + phase) * cos(m * theta + p7)
                    vb[off++] = v
                }
            }
        }

        // Pass 2: color mapping
        val pixels = IntArray(total)
        val invTol = 1f / tolerance
        val invTol15 = 1f / (tolerance * 1.5f)
        val black = Color.rgb(10, 10, 10)
        val lutHi = palLut[255]; val lutLo = palLut[0]

        when (colorMode) {
            "nodal" -> {
                for (i in 0 until total) {
                    val v = vb[i]
                    val av = abs(v)
                    if (av >= tolerance) { pixels[i] = black; continue }
                    val bright = 1f - av * invTol
                    var t = v * invTol * 0.5f + 0.5f
                    t = t.coerceIn(0f, 1f)
                    val base = palLut[(t * 255f + 0.5f).toInt().coerceIn(0, 255)]
                    val rr = (Color.red(base) * bright).toInt()
                    val gg = (Color.green(base) * bright).toInt()
                    val bb = (Color.blue(base) * bright).toInt()
                    pixels[i] = Color.rgb(rr, gg, bb)
                }
            }
            "amplitude" -> {
                for (i in 0 until total) {
                    val v = vb[i]
                    var t = v * 0.5f + 0.5f
                    t = t.coerceIn(0f, 1f)
                    val base = palLut[(t * 255f + 0.5f).toInt().coerceIn(0, 255)]
                    val av = abs(v)
                    if (av < tolerance) {
                        // Dim near nodal lines for visible structure
                        val dim = 0.25f + 0.75f * av * invTol
                        val rr = (Color.red(base) * dim).toInt()
                        val gg = (Color.green(base) * dim).toInt()
                        val bb = (Color.blue(base) * dim).toInt()
                        pixels[i] = Color.rgb(rr, gg, bb)
                    } else {
                        pixels[i] = base
                    }
                }
            }
            "signed" -> {
                // Fast tanh approx: x/(1+|x|) scaled by tolerance
                for (i in 0 until total) {
                    val tx = vb[i] * invTol15
                    var t = (tx / (1f + abs(tx))) * 0.5f + 0.5f
                    t = t.coerceIn(0f, 1f)
                    pixels[i] = palLut[(t * 255f + 0.5f).toInt().coerceIn(0, 255)]
                }
            }
            else -> { // phase
                for (i in 0 until total) {
                    val v = vb[i]
                    val av = abs(v)
                    if (av < tolerance) {
                        val fade = av * invTol * 0.5f
                        val base = if (v > 0f) lutHi else lutLo
                        val rr = (Color.red(base) * fade).toInt()
                        val gg = (Color.green(base) * fade).toInt()
                        val bb = (Color.blue(base) * fade).toInt()
                        pixels[i] = Color.rgb(rr, gg, bb)
                    } else {
                        pixels[i] = if (v > 0f) lutHi else lutLo
                    }
                }
            }
        }

        if (halfRes) {
            // Bilinear upscale from half-res
            val smallBmp = Bitmap.createBitmap(rw, rh, Bitmap.Config.ARGB_8888)
            smallBmp.setPixels(pixels, 0, rw, 0, 0, rw, rh)
            val scaled = Bitmap.createScaledBitmap(smallBmp, w, h, true)
            val scaledPixels = IntArray(w * h)
            scaled.getPixels(scaledPixels, 0, w, 0, 0, w, h)
            bitmap.setPixels(scaledPixels, 0, w, 0, 0, w, h)
            smallBmp.recycle()
            scaled.recycle()
        } else {
            bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
        }
        canvas.drawBitmap(bitmap, 0f, 0f, null)
    }

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float = 0.3f
}
