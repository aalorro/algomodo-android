package com.artmondo.algomodo.generators.procedural

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import com.artmondo.algomodo.audio.AudioAnalysis
import com.artmondo.algomodo.data.palettes.Palette
import com.artmondo.algomodo.generators.Generator
import com.artmondo.algomodo.generators.ParamGroup
import com.artmondo.algomodo.generators.Parameter
import com.artmondo.algomodo.generators.Quality
import kotlin.math.*

class DisplacementGenerator : Generator {

    override val id = "procedural-displacement"
    override val family = "procedural"
    override val styleName = "Displacement"
    override val definition =
        "Noise-driven UV displacement mapping — pixels are offset through vector fields to create organic distortion, fracture, radial ripple, and wave effects."
    override val algorithmNotes =
        "Coarse FBM grid (8px spacing) precomputed once per frame, bilinear-interpolated per pixel " +
        "replacing per-pixel FBM (~10x faster). " +
        "Flow: smooth displacement with banded palette coloring. " +
        "Fracture: cell-based displacement with per-cell trig animation and bright edge cracks. " +
        "Radial: warped-radius ripples with angular color bands per ring. " +
        "Wave: dual-axis phase-warped sinusoids with band coloring. " +
        "Chromatic aberration uses noise at warped coords for organic color splitting."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema = listOf(
        Parameter.SelectParam("Mode", "mode", ParamGroup.COMPOSITION,
            "flow: smooth | fracture: hard blocks | radial: ripples | wave: banded distortion",
            listOf("flow", "fracture", "radial", "wave"), "flow"),
        Parameter.NumberParam("Strength", "strength", ParamGroup.GEOMETRY,
            "How far pixels are displaced", 0.01f, 0.5f, 0.01f, 0.15f),
        Parameter.NumberParam("Scale", "scale", ParamGroup.GEOMETRY,
            "Size of the displacement noise field", 0.5f, 8f, 0.1f, 2.0f),
        Parameter.NumberParam("Octaves", "octaves", ParamGroup.COMPOSITION,
            "FBM layers — more = finer detail", 1f, 5f, 1f, 3f),
        Parameter.NumberParam("Distortion", "distortion", ParamGroup.TEXTURE,
            "Secondary domain warp before displacement lookup", 0f, 1f, 0.05f, 0.3f),
        Parameter.NumberParam("Chromatic Shift", "chromaticShift", ParamGroup.COLOR,
            "RGB channel offset for color splitting", 0f, 1f, 0.05f, 0.1f),
        Parameter.NumberParam("Speed", "speed", ParamGroup.FLOW_MOTION,
            "Animation drift speed", 0.1f, 3f, 0.05f, 0.5f),
        Parameter.NumberParam("Reactivity", "reactivity", ParamGroup.FLOW_MOTION,
            "Audio reactivity strength", 0f, 2f, 0.1f, 1.0f)
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "mode" to "flow", "strength" to 0.15f, "scale" to 2.0f, "octaves" to 3f,
        "distortion" to 0.3f, "chromaticShift" to 0.1f, "speed" to 0.5f,
        "reactivity" to 1.0f
    )

    override fun renderCanvas(
        canvas: Canvas, bitmap: Bitmap, params: Map<String, Any>,
        seed: Int, palette: Palette, quality: Quality, time: Float
    ) {
        val w = bitmap.width; val h = bitmap.height
        val step = when (quality) { Quality.DRAFT -> 4; Quality.ULTRA -> 1; else -> 2 }

        val mode = (params["mode"] as? String) ?: "flow"
        val str = (params["strength"] as? Number)?.toFloat() ?: 0.15f
        val scl = (params["scale"] as? Number)?.toFloat() ?: 2.0f
        val nOct = (params["octaves"] as? Number)?.toInt()?.coerceAtLeast(1) ?: 3
        val dist = (params["distortion"] as? Number)?.toFloat() ?: 0.3f
        val ca = (params["chromaticShift"] as? Number)?.toFloat() ?: 0.1f
        val spd = (params["speed"] as? Number)?.toFloat() ?: 0.5f

        val rx = (params["reactivity"] as? Number)?.toFloat() ?: 1.0f
        val audioAnalysis = params["_audioAnalysis"] as? AudioAnalysis
        val audioBass = (audioAnalysis?.getBass(time) ?: 0f) * rx
        val audioMid = (audioAnalysis?.getMid(time) ?: 0f) * rx
        val effStr = str * (1f + audioBass * 3f)
        val effScl = scl * (1f + audioMid * 0.5f)

        val t = time * spd
        val modeId = when (mode) { "flow" -> 0; "fracture" -> 1; "radial" -> 2; else -> 3 }
        val vn = ValueNoise(seed)

        // Palette ramp + color count for banding
        val nC = palette.colorInts().size
        val nCm1f = if (nC > 1) (nC - 1).toFloat() else 1f
        val lut = palette.buildLut(256)
        val rampR = IntArray(256); val rampG = IntArray(256); val rampB = IntArray(256)
        for (i in 0 until 256) {
            rampR[i] = Color.red(lut[i]); rampG[i] = Color.green(lut[i]); rampB[i] = Color.blue(lut[i])
        }

        val halfW = w * 0.5f; val halfH = h * 0.5f
        val invDim2 = 2f / min(w, h)
        val caScale = ca * 0.5f
        val doCa = ca > 0.01f
        val doDist = dist > 0.01f
        val warpAmt = dist * 0.5f
        val maxOct = if (quality == Quality.DRAFT) nOct.coerceAtMost(2) else nOct
        val TAU = PI.toFloat() * 2f

        val tOff1x = t * 0.1f; val tOff1y = t * 0.07f
        val tOff2x = t * 0.07f; val tOff2y = t * 0.1f
        val distNScl = effScl * 2.5f
        val colorScl = effScl * 2f
        val dist2 = dist * 2f

        // ── Coarse FBM grid (8px spacing): bilinear-interpolated per pixel ──
        val CS = 8; val invCS = 1f / CS
        val cCols = w / CS + 2; val cRows = h / CS + 2
        val coarse1 = FloatArray(cCols * cRows)
        val coarse2 = FloatArray(cCols * cRows)

        val cols = ceil(w.toFloat() / step).toInt()
        val rows = ceil(h.toFloat() / step).toInt()
        val smallPixels = IntArray(cols * rows)

        when (modeId) {
            0 -> {
                // ═══ FLOW ═══════════════════════════════════════════════
                for (cy in 0 until cRows) {
                    val cpv = (cy * CS - halfH) * invDim2; val cny = cpv * effScl
                    val ro = cy * cCols
                    for (cx in 0 until cCols) {
                        val cpu = (cx * CS - halfW) * invDim2; val cnx = cpu * effScl
                        coarse1[ro + cx] = vn.fbm(cnx + tOff1x, cny + tOff1y, maxOct)
                        coarse2[ro + cx] = vn.fbm(cnx + 31.7f + tOff2x, cny + 17.3f + tOff2y, maxOct)
                    }
                }
                for (gy in 0 until rows) {
                    val py = gy * step; val v = (py - halfH) * invDim2; val ny = v * effScl
                    val cfy = py * invCS; val cy0 = cfy.toInt(); val fy = cfy - cy0; val cro = cy0 * cCols
                    for (gx in 0 until cols) {
                        val px = gx * step; val u = (px - halfW) * invDim2; val nx = u * effScl
                        val cfx = px * invCS; val cx0 = cfx.toInt(); val fx = cfx - cx0; val ci = cro + cx0
                        val a1 = coarse1[ci]; val b1 = coarse1[ci + 1]
                        val c1 = coarse1[ci + cCols]; val d1 = coarse1[ci + cCols + 1]
                        val n1 = a1 + (b1 - a1) * fx + (c1 - a1) * fy + (a1 - b1 - c1 + d1) * fx * fy
                        val a2 = coarse2[ci]; val b2 = coarse2[ci + 1]
                        val c2 = coarse2[ci + cCols]; val d2 = coarse2[ci + cCols + 1]
                        val n2 = a2 + (b2 - a2) * fx + (c2 - a2) * fy + (a2 - b2 - c2 + d2) * fx * fy
                        var wu = u + n1 * effStr; var wv = v + n2 * effStr
                        if (doDist) {
                            wu += vn.noise(nx * distNScl + 50f, ny * distNScl) * warpAmt
                            wv += vn.noise(nx * distNScl, ny * distNScl + 50f) * warpAmt
                        }
                        val cn = vn.noise(wu * colorScl, wv * colorScl) * 0.5f + 0.5f
                        val flowBand = (cn * nC * 2).toInt()
                        val colorVal = ((flowBand % nC) / nCm1f).coerceIn(0f, 1f)
                        smallPixels[gy * cols + gx] = emitColor(colorVal, wu, wv, doCa, caScale, colorScl, vn, rampR, rampG, rampB)
                    }
                }
            }
            1 -> {
                // ═══ FRACTURE ═══════════════════════════════════════════
                val fractCells = 3f + dist * 12f
                val tSinHalf = sin(t * 0.5f); val tCosHalf = cos(t * 0.5f)
                for (cy in 0 until cRows) {
                    val cpv = (cy * CS - halfH) * invDim2; val cny = cpv * effScl
                    val ro = cy * cCols
                    for (cx in 0 until cCols) {
                        val cpu = (cx * CS - halfW) * invDim2; val cnx = cpu * effScl
                        coarse1[ro + cx] = vn.fbm(cnx + 50f, cny + 50f, maxOct)
                        coarse2[ro + cx] = vn.fbm(cnx + 80f, cny + 80f, maxOct)
                    }
                }
                for (gy in 0 until rows) {
                    val py = gy * step; val v = (py - halfH) * invDim2
                    val cfy = py * invCS; val cy0 = cfy.toInt(); val fy = cfy - cy0; val cro = cy0 * cCols
                    for (gx in 0 until cols) {
                        val px = gx * step; val u = (px - halfW) * invDim2
                        val cfx = px * invCS; val cx0 = cfx.toInt(); val fx = cfx - cx0; val ci = cro + cx0
                        val a1 = coarse1[ci]; val b1 = coarse1[ci + 1]
                        val c1 = coarse1[ci + cCols]; val d1 = coarse1[ci + cCols + 1]
                        val wN1 = a1 + (b1 - a1) * fx + (c1 - a1) * fy + (a1 - b1 - c1 + d1) * fx * fy
                        val a2 = coarse2[ci]; val b2 = coarse2[ci + 1]
                        val c2 = coarse2[ci + cCols]; val d2 = coarse2[ci + cCols + 1]
                        val wN2 = a2 + (b2 - a2) * fx + (c2 - a2) * fy + (a2 - b2 - c2 + d2) * fx * fy
                        val warpU = u + wN1 * effStr * 0.5f
                        val warpV = v + wN2 * effStr * 0.5f
                        val cellX = floor((warpU + 1f) * fractCells).toInt()
                        val cellY = floor((warpV + 1f) * fractCells).toInt()
                        val ch1 = vn.noise(cellX * 1.37f + 0.5f, cellY * 2.71f + 0.5f)
                        val ch2 = vn.noise(cellX * 2.71f + 0.5f, cellY * 1.37f + 0.5f)
                        val cellDx = ch1 * effStr * (tSinHalf * cos(ch1 * TAU) + tCosHalf * sin(ch1 * TAU))
                        val cellDy = ch2 * effStr * (tCosHalf * cos(ch2 * TAU) - tSinHalf * sin(ch2 * TAU))
                        val wu = u + cellDx; val wv = v + cellDy
                        val fracU = (u + 1f) * fractCells - cellX
                        val fracV = (v + 1f) * fractCells - cellY
                        val edgeDist = minOf(fracU, 1f - fracU, fracV, 1f - fracV)
                        val cellColor = ch1 * 0.5f + 0.5f
                        val edgeBright = if (edgeDist < 0.08f) 1f else 0f
                        val colorVal = (cellColor * (1f - edgeBright) + edgeBright).coerceIn(0f, 1f)
                        smallPixels[gy * cols + gx] = emitColor(colorVal, wu, wv, doCa, caScale, colorScl, vn, rampR, rampG, rampB)
                    }
                }
            }
            2 -> {
                // ═══ RADIAL ═════════════════════════════════════════════
                val radialFreq = TAU * (3f + dist * 8f)
                val radialTime = t * 2.5f
                val radialFreqOverTAU = radialFreq / TAU
                val radialTimeColor = radialTime * 0.1f
                for (cy in 0 until cRows) {
                    val cpv = (cy * CS - halfH) * invDim2; val cny = cpv * effScl
                    val ro = cy * cCols
                    for (cx in 0 until cCols) {
                        val cpu = (cx * CS - halfW) * invDim2; val cnx = cpu * effScl
                        coarse1[ro + cx] = vn.fbm(cnx + 70f, cny + 70f, maxOct)
                        coarse2[ro + cx] = vn.noise(cnx * 2f + 40f, cny * 2f + 40f) * 0.8f
                    }
                }
                for (gy in 0 until rows) {
                    val py = gy * step; val v = (py - halfH) * invDim2; val ny = v * effScl
                    val vSq = v * v
                    val cfy = py * invCS; val cy0 = cfy.toInt(); val fy = cfy - cy0; val cro = cy0 * cCols
                    for (gx in 0 until cols) {
                        val px = gx * step; val u = (px - halfW) * invDim2; val nx = u * effScl
                        val cfx = px * invCS; val cx0 = cfx.toInt(); val fx = cfx - cx0; val ci = cro + cx0
                        val a1 = coarse1[ci]; val b1 = coarse1[ci + 1]
                        val c1 = coarse1[ci + cCols]; val d1 = coarse1[ci + cCols + 1]
                        val nWarp = a1 + (b1 - a1) * fx + (c1 - a1) * fy + (a1 - b1 - c1 + d1) * fx * fy
                        val a2 = coarse2[ci]; val b2 = coarse2[ci + 1]
                        val c2 = coarse2[ci + cCols]; val d2 = coarse2[ci + cCols + 1]
                        val angWarp = a2 + (b2 - a2) * fx + (c2 - a2) * fy + (a2 - b2 - c2 + d2) * fx * fy
                        val rad = sqrt(vSq + u * u)
                        val warpedRad = rad + nWarp * 0.15f
                        val ripple = sin(warpedRad * radialFreq - radialTime)
                        val dr = ripple * effStr * (1f + nWarp * dist2)
                        val invRad = if (rad > 0.001f) 1f / rad else 0f
                        var wu = u + u * invRad * dr
                        var wv = v + v * invRad * dr
                        if (doDist) {
                            wu += vn.noise(nx * distNScl + 50f, ny * distNScl) * warpAmt
                            wv += vn.noise(nx * distNScl, ny * distNScl + 50f) * warpAmt
                        }
                        val ang = atan2(v, u)
                        val colorRad = rad + nWarp * 0.2f
                        val colorAng = ang + angWarp
                        val ringBand = floor(colorRad * radialFreqOverTAU - radialTimeColor).toInt()
                        val perRingN = vn.noise(ringBand * 0.73f + 5f, colorAng * 1.5f + ringBand * 0.4f) * 0.5f + 0.5f
                        val colorBand = ((perRingN * nC).toInt() % nC + nC) % nC
                        val colorVal = (colorBand / nCm1f).coerceIn(0f, 1f)
                        smallPixels[gy * cols + gx] = emitColor(colorVal, wu, wv, doCa, caScale, colorScl, vn, rampR, rampG, rampB)
                    }
                }
            }
            else -> {
                // ═══ WAVE ═══════════════════════════════════════════════
                val waveFreq = 4f + dist * 14f
                val waveFreq07 = waveFreq * 0.7f
                val waveTx = t * 1.8f; val waveTy = t * 1.3f
                for (cy in 0 until cRows) {
                    val cpv = (cy * CS - halfH) * invDim2; val cny = cpv * effScl
                    val ro = cy * cCols
                    for (cx in 0 until cCols) {
                        val cpu = (cx * CS - halfW) * invDim2; val cnx = cpu * effScl
                        coarse1[ro + cx] = vn.fbm(cnx + 90f, cny + 90f, maxOct)
                        coarse2[ro + cx] = vn.noise(cnx * 2f + 30f, cny * 2f + 30f)
                    }
                }
                for (gy in 0 until rows) {
                    val py = gy * step; val v = (py - halfH) * invDim2; val ny = v * effScl
                    val cfy = py * invCS; val cy0 = cfy.toInt(); val fy = cfy - cy0; val cro = cy0 * cCols
                    for (gx in 0 until cols) {
                        val px = gx * step; val u = (px - halfW) * invDim2; val nx = u * effScl
                        val cfx = px * invCS; val cx0 = cfx.toInt(); val fx = cfx - cx0; val ci = cro + cx0
                        val a1 = coarse1[ci]; val b1 = coarse1[ci + 1]
                        val c1 = coarse1[ci + cCols]; val d1 = coarse1[ci + cCols + 1]
                        val nWarp = a1 + (b1 - a1) * fx + (c1 - a1) * fy + (a1 - b1 - c1 + d1) * fx * fy
                        val a2 = coarse2[ci]; val b2 = coarse2[ci + 1]
                        val c2 = coarse2[ci + cCols]; val d2 = coarse2[ci + cCols + 1]
                        val nWarp2 = a2 + (b2 - a2) * fx + (c2 - a2) * fy + (a2 - b2 - c2 + d2) * fx * fy
                        val phaseV = v * waveFreq + waveTx + nWarp * 1.5f
                        val phaseU = u * waveFreq07 + waveTy + nWarp2 * 1.5f
                        val waveX = sin(phaseV) * effStr * (1f + nWarp * dist)
                        val waveY = sin(phaseU) * effStr * 0.6f * (1f + nWarp * dist)
                        var wu = u + waveX; var wv = v + waveY
                        if (doDist) {
                            wu += vn.noise(nx * distNScl + 50f, ny * distNScl) * warpAmt
                            wv += vn.noise(nx * distNScl, ny * distNScl + 50f) * warpAmt
                        }
                        val hBand = floor(phaseV).toInt()
                        val vBand = floor(phaseU).toInt()
                        val bandCombined = ((hBand + vBand) % nC + nC) % nC
                        val colorVal = (bandCombined / nCm1f).coerceIn(0f, 1f)
                        smallPixels[gy * cols + gx] = emitColor(colorVal, wu, wv, doCa, caScale, colorScl, vn, rampR, rampG, rampB)
                    }
                }
            }
        }

        // Bilinear upscale if step > 1
        if (step > 1) {
            val smallBmp = Bitmap.createBitmap(cols, rows, Bitmap.Config.ARGB_8888)
            smallBmp.setPixels(smallPixels, 0, cols, 0, 0, cols, rows)
            val scaled = Bitmap.createScaledBitmap(smallBmp, w, h, true)
            val pixels = IntArray(w * h)
            scaled.getPixels(pixels, 0, w, 0, 0, w, h)
            bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
            smallBmp.recycle(); scaled.recycle()
        } else {
            bitmap.setPixels(smallPixels, 0, w, 0, 0, w, h)
        }
        canvas.drawBitmap(bitmap, 0f, 0f, null)
    }

    private fun emitColor(
        colorVal: Float, wu: Float, wv: Float,
        doCa: Boolean, caScale: Float, colorScl: Float,
        vn: ValueNoise, rampR: IntArray, rampG: IntArray, rampB: IntArray
    ): Int {
        val rr: Int; val gg: Int; val bb: Int
        if (doCa) {
            val shift = vn.noise(wu * colorScl + 7.7f, wv * colorScl + 3.3f) * caScale
            rr = rampR[((colorVal + shift).coerceIn(0f, 1f) * 255f).toInt()]
            gg = rampG[(colorVal * 255f).toInt()]
            bb = rampB[((colorVal - shift).coerceIn(0f, 1f) * 255f).toInt()]
        } else {
            val idx = (colorVal * 255f).toInt()
            rr = rampR[idx]; gg = rampG[idx]; bb = rampB[idx]
        }
        return Color.rgb(rr, gg, bb)
    }

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float {
        val oct = (params["octaves"] as? Number)?.toInt() ?: 3
        val mode = (params["mode"] as? String) ?: "flow"
        val modeMult = if (mode == "radial") 1.2f else 1f
        return (oct * 0.1f * modeMult + 0.15f).coerceIn(0.2f, 0.8f)
    }
}
