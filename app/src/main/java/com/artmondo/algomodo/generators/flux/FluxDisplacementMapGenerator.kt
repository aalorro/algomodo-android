package com.artmondo.algomodo.generators.flux

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import com.artmondo.algomodo.audio.AudioAnalysis
import com.artmondo.algomodo.data.palettes.Palette
import com.artmondo.algomodo.generators.Generator
import com.artmondo.algomodo.generators.ParamGroup
import com.artmondo.algomodo.generators.Parameter
import com.artmondo.algomodo.generators.Quality
import com.artmondo.algomodo.generators.procedural.ValueNoise
import kotlin.math.*

class FluxDisplacementMapGenerator : Generator {

    override val id = "flux-displacement-map"
    override val family = "flux"
    override val styleName = "Displacement Map"
    override val definition =
        "A base pattern (stripes, grid, checkerboard, circles) is warped by a secondary noise texture " +
        "used as a UV displacement field \u2014 the noise offsets each pixel\u2019s sample position, bending and " +
        "folding the underlying pattern into organic distortions."
    override val algorithmNotes =
        "Sharp step-based patterns (floor/fract) instead of sine for crisp edges. " +
        "Value noise FBM displacement with precomputed weights. " +
        "Palette LUT cached per frame. Per-row noise Y precomputed outside inner loop. " +
        "Bilinear upscale from reduced buffer in DRAFT/BALANCED quality."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema: List<Parameter> = listOf(
        Parameter.SelectParam(
            "Base Pattern", "basePattern", ParamGroup.COMPOSITION,
            "stripes: sharp alternating bands | grid: line lattice | checkerboard: alternating tiles | circles: concentric rings",
            listOf("stripes", "grid", "checkerboard", "circles"), "stripes"
        ),
        Parameter.NumberParam(
            "Displacement Scale", "displacementScale", ParamGroup.GEOMETRY,
            "How far pixels are displaced by the noise field \u2014 higher = more warped",
            0.1f, 2f, 0.05f, 0.5f
        ),
        Parameter.NumberParam(
            "Noise Scale", "noiseScale", ParamGroup.GEOMETRY,
            "Frequency of the displacement noise \u2014 lower = larger features",
            0.5f, 5f, 0.1f, 2.0f
        ),
        Parameter.NumberParam(
            "Pattern Scale", "patternScale", ParamGroup.GEOMETRY,
            "Frequency of the base pattern \u2014 higher = more repetitions",
            2f, 30f, 1f, 10f
        ),
        Parameter.NumberParam(
            "Octaves", "octaves", ParamGroup.COMPOSITION,
            "FBM layers for the displacement field \u2014 more = finer detail",
            1f, 4f, 1f, 2f
        ),
        Parameter.NumberParam(
            "Speed", "speed", ParamGroup.FLOW_MOTION,
            "Animation drift speed of the displacement field",
            0.1f, 3f, 0.05f, 0.4f
        ),
        Parameter.NumberParam(
            "Audio Reactivity", "reactivity", ParamGroup.FLOW_MOTION,
            "Sensitivity to audio input (0 = none)",
            0f, 2f, 0.1f, 1.0f
        )
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "basePattern" to "stripes",
        "displacementScale" to 0.5f,
        "noiseScale" to 2.0f,
        "patternScale" to 10f,
        "octaves" to 2f,
        "speed" to 0.4f,
        "reactivity" to 1.0f
    )

    override fun renderCanvas(
        canvas: Canvas, bitmap: Bitmap, params: Map<String, Any>,
        seed: Int, palette: Palette, quality: Quality, time: Float
    ) {
        val w = bitmap.width
        val h = bitmap.height
        val step = when (quality) { Quality.DRAFT -> 4; Quality.ULTRA -> 1; else -> 2 }

        // ── Extract parameters ──────────────────────────────────────────
        val basePattern = (params["basePattern"] as? String) ?: "stripes"
        val displacementScale = (params["displacementScale"] as? Number)?.toFloat() ?: 0.5f
        val noiseScale = (params["noiseScale"] as? Number)?.toFloat() ?: 2.0f
        val patternScale = (params["patternScale"] as? Number)?.toFloat() ?: 10f
        val nOct = (params["octaves"] as? Number)?.toInt()?.coerceIn(1, 4) ?: 2
        val speed = (params["speed"] as? Number)?.toFloat() ?: 0.4f
        val rx = (params["reactivity"] as? Number)?.toFloat() ?: 1.0f

        // ── Audio reactivity ────────────────────────────────────────────
        val audioAnalysis = params["_audioAnalysis"] as? AudioAnalysis
        val audioBass = (audioAnalysis?.getBass(time) ?: 0f) * rx
        val audioHigh = (audioAnalysis?.getHigh(time) ?: 0f) * rx

        val effDisplacement = displacementScale * (1f + audioBass * 2f)
        val effPatternScale = patternScale * (1f + audioHigh * 0.5f)

        val t = time * speed

        val patId = when (basePattern) {
            "stripes" -> 0
            "grid" -> 1
            "checkerboard" -> 2
            else -> 3 // circles
        }

        // ── Noise ───────────────────────────────────────────────────────
        val vn = ValueNoise(seed)

        // ── Precompute FBM weights ──────────────────────────────────────
        val maxOct = if (quality == Quality.DRAFT) nOct.coerceAtMost(2) else nOct
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
        val fbmInvTotal = 1f / fbmTotal

        // ── Palette LUT ────────────────────────────────────────────────
        val lut = palette.buildLut(256)

        // ── Precompute geometry ─────────────────────────────────────────
        val minDim = min(w, h)
        val invMinDim = 1f / minDim
        val noiseInv = noiseScale * invMinDim
        val dispScaleMinDim = effDisplacement * minDim
        val patOverMin = effPatternScale * invMinDim
        val tOffY = t * 0.15f

        // Grid line width: fraction of cell that is a "line" (sharp)
        val gridLineW = 0.15f

        val cols = ceil(w.toFloat() / step).toInt()
        val rows = ceil(h.toFloat() / step).toInt()
        val smallPixels = IntArray(cols * rows)

        // ── Main pixel loop ─────────────────────────────────────────────
        for (gy in 0 until rows) {
            val py = gy * step
            val pyNoiseInv = py * noiseInv + tOffY

            for (gx in 0 until cols) {
                val px = gx * step
                val pxNoiseInv = px * noiseInv

                // ── Inline FBM for du, dv ───────────────────────────
                var du = 0f
                var dv = 0f
                for (o in 0 until maxOct) {
                    val f = fbmFreq[o]
                    val a = fbmAmp[o]
                    val nxf = pxNoiseInv * f
                    val nyf = pyNoiseInv * f
                    du += vn.noise(nxf, nyf) * a
                    dv += vn.noise(nxf + 37.7f, nyf + 19.3f) * a
                }
                du *= fbmInvTotal
                dv *= fbmInvTotal

                // Displaced sample coordinates
                val sampleX = px + du * dispScaleMinDim
                val sampleY = py + dv * dispScaleMinDim

                // ── Sharp base pattern evaluation ───────────────────
                val brightness: Float = when (patId) {
                    0 -> {
                        // Stripes -- sharp alternating bands via floor mod 2
                        val band = sampleX * patOverMin
                        val intBand = floor(band).toInt()
                        if (intBand and 1 != 0) 1f else 0f
                    }
                    1 -> {
                        // Grid -- sharp line lattice: lines where fract is near 0
                        val fx = sampleX * patOverMin
                        val fy = sampleY * patOverMin
                        val fracX = fx - floor(fx)
                        val fracY = fy - floor(fy)
                        val onLineX = fracX < gridLineW || fracX > (1f - gridLineW)
                        val onLineY = fracY < gridLineW || fracY > (1f - gridLineW)
                        if (onLineX || onLineY) 1f else 0f
                    }
                    2 -> {
                        // Checkerboard -- already sharp
                        val cx = floor(sampleX * patOverMin).toInt()
                        val cy = floor(sampleY * patOverMin).toInt()
                        if ((cx + cy) and 1 != 0) 1f else 0f
                    }
                    else -> {
                        // Circles -- sharp concentric rings via floor mod 2
                        val dist = sqrt(sampleX * sampleX + sampleY * sampleY)
                        val band = dist * patOverMin
                        val intBand = floor(band).toInt()
                        if (intBand and 1 != 0) 1f else 0f
                    }
                }

                val lutIdx = (brightness * 255f).toInt().coerceIn(0, 255)
                smallPixels[gy * cols + gx] = lut[lutIdx]
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
        val oct = (params["octaves"] as? Number)?.toInt() ?: 2
        return (oct * 0.12f + 0.1f).coerceIn(0.2f, 0.7f)
    }
}
