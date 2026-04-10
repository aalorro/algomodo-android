package com.artmondo.algomodo.generators.noise

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import com.artmondo.algomodo.core.rng.SimplexNoise
import com.artmondo.algomodo.data.palettes.Palette
import com.artmondo.algomodo.generators.Generator
import com.artmondo.algomodo.generators.ParamGroup
import com.artmondo.algomodo.generators.Parameter
import com.artmondo.algomodo.generators.Quality
import kotlin.math.*

class NoiseSimplexFieldGenerator : Generator {

    override val id = "noise-simplex-field"
    override val family = "noise"
    override val styleName = "Simplex Noise Field"
    override val definition =
        "Multi-octave simplex noise rendered as colored pixels with a family of noise styles."
    override val algorithmNotes =
        "Samples multi-octave simplex noise at every pixel. Style selects the noise transform: " +
        "smooth fbm, ridged crests (1 - |fbm|)^2, turbulent folds |fbm|, billowing puffs, " +
        "or thin vein-like negative-space lines. The entire style + band + palette chain is " +
        "collapsed into a single 1024-entry LUT, the inner loop is specialized per animation " +
        "mode, the field is rendered at 40% resolution during animation and bilinear-upscaled, " +
        "and rows are partitioned across CPU cores."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema: List<Parameter> = listOf(
        Parameter.NumberParam("Scale", "scale", ParamGroup.COMPOSITION,
            "Noise frequency", 0.5f, 12f, 0.5f, 3f),
        Parameter.NumberParam("Octaves", "octaves", ParamGroup.COMPOSITION,
            "Noise layers — more = finer detail", 1f, 6f, 1f, 4f),
        Parameter.SelectParam("Style", "style", ParamGroup.GEOMETRY,
            "smooth: classic fbm | ridged: sharp crests | turbulent: folded creases | billow: puffy clouds | veins: thin negative-space lines",
            listOf("smooth", "ridged", "turbulent", "billow", "veins"), "ridged"),
        Parameter.NumberParam("Warp Amount", "warpAmount", ParamGroup.COMPOSITION,
            "Domain warping for organic distortion", 0f, 2f, 0.1f, 0f),
        Parameter.SelectParam("Color Mode", "colorMode", ParamGroup.COLOR,
            "palette: smooth gradient | bands: hard contour steps",
            listOf("palette", "bands"), "palette"),
        Parameter.NumberParam("Band Count", "bandCount", ParamGroup.COLOR,
            "Number of contour bands (bands mode)", 2f, 24f, 1f, 6f),
        Parameter.SelectParam("Anim Mode", "animMode", ParamGroup.FLOW_MOTION,
            "drift: pan through field | rotate: spin around center | pulse: wobble back and forth",
            listOf("drift", "rotate", "pulse"), "drift"),
        Parameter.NumberParam("Speed", "speed", ParamGroup.FLOW_MOTION,
            "", 0.1f, 3.0f, 0.1f, 0.5f)
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "scale" to 3f, "octaves" to 4f, "style" to "ridged", "warpAmount" to 0f,
        "colorMode" to "palette", "bandCount" to 6f, "animMode" to "drift", "speed" to 0.5f
    )

    companion object {
        private const val SCL_RES = 1024
        private const val SCL_MAX = SCL_RES - 1
        // Maps raw noise in [-1, 1] to LUT index in [0, SCL_MAX].
        // li = (raw + 1) * (SCL_MAX / 2) = (raw + 1) * SCL_HALF
        private const val SCL_HALF = SCL_MAX * 0.5f
    }

    override fun renderCanvas(
        canvas: Canvas, bitmap: Bitmap, params: Map<String, Any>,
        seed: Int, palette: Palette, quality: Quality, time: Float
    ) {
        val w = bitmap.width; val h = bitmap.height
        val scale = (params["scale"] as? Number)?.toFloat() ?: 3f
        val octaves = ((params["octaves"] as? Number)?.toInt() ?: 4).coerceIn(1, 6)
        val style = (params["style"] as? String) ?: "ridged"
        val warpAmount = (params["warpAmount"] as? Number)?.toFloat() ?: 0f
        val colorMode = (params["colorMode"] as? String) ?: "palette"
        val bandCount = ((params["bandCount"] as? Number)?.toInt() ?: 6).coerceAtLeast(2)
        val animMode = (params["animMode"] as? String) ?: "drift"
        val speed = (params["speed"] as? Number)?.toFloat() ?: 0.5f

        val styleId = when (style) {
            "ridged" -> 1
            "turbulent" -> 2
            "billow" -> 3
            "veins" -> 4
            else -> 0  // smooth
        }
        val animId = when (animMode) {
            "rotate" -> 1
            "pulse" -> 2
            else -> 0  // drift
        }

        // ── Adaptive resolution ─────────────────────────────────────────
        val isAnimating = time > 0f && speed > 0f
        val resScale = when {
            quality == Quality.ULTRA -> 1f
            isAnimating && quality == Quality.DRAFT -> 0.3f
            isAnimating -> 0.4f
            quality == Quality.DRAFT -> 0.5f
            else -> 1f
        }
        val rw = max(2, (w * resScale).toInt())
        val rh = max(2, (h * resScale).toInt())
        val invScale = scale / rw.toFloat()
        val centerX = rw * 0.5f * invScale
        val centerY = rh * 0.5f * invScale

        // Per-frame anim transform constants
        val driftX = time * speed * 0.35f
        val driftY = time * speed * 0.2f
        val rotAngle = time * speed * 0.5f
        val rotSin = sin(rotAngle); val rotCos = cos(rotAngle)
        val pulsePhase = time * speed
        val pulseShiftX = sin(pulsePhase) * 0.9f
        val pulseShiftY = cos(pulsePhase * 1.3f) * 0.9f

        // ── Pre-build combined style → band → palette LUT ───────────────
        // The entire color chain collapses to a single 1024-entry lookup.
        // Computed once per frame; eliminates the inner-loop branch on style.
        val styleColorLut = buildStyleColorLut(palette, styleId, colorMode == "bands", bandCount)

        val warpActive = warpAmount > 0f
        val noise = SimplexNoise(seed)  // immutable after construction → safe to share
        val pixels = IntArray(rw * rh)
        val cores = Runtime.getRuntime().availableProcessors().coerceIn(2, 8)

        // ── Multi-threaded row partition ────────────────────────────────
        // Specialized per animation mode to keep the inner loop branch-free.
        val threads = Array(cores) { t ->
            Thread {
                val y0 = t * rh / cores
                val y1 = (t + 1) * rh / cores
                when (animId) {
                    1 -> renderRowsRotate(
                        pixels, y0, y1, rw, invScale, centerX, centerY, rotSin, rotCos,
                        warpActive, warpAmount, octaves, noise, styleColorLut
                    )
                    2 -> renderRowsOffset(
                        pixels, y0, y1, rw, invScale, pulseShiftX, pulseShiftY,
                        warpActive, warpAmount, octaves, noise, styleColorLut
                    )
                    else -> renderRowsOffset(
                        pixels, y0, y1, rw, invScale, driftX, driftY,
                        warpActive, warpAmount, octaves, noise, styleColorLut
                    )
                }
            }.also { it.start() }
        }
        threads.forEach { it.join() }

        // ── Composite ───────────────────────────────────────────────────
        if (rw == w && rh == h) {
            bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
            canvas.drawBitmap(bitmap, 0f, 0f, null)
        } else {
            // Upscale by drawing the small bitmap onto the canvas with
            // bilinear filtering. The canvas is backed by `bitmap`, so this
            // updates the target bitmap directly — no intermediate IntArray.
            val smallBmp = Bitmap.createBitmap(rw, rh, Bitmap.Config.ARGB_8888)
            smallBmp.setPixels(pixels, 0, rw, 0, 0, rw, rh)
            val srcRect = Rect(0, 0, rw, rh)
            val dstRect = Rect(0, 0, w, h)
            val filterPaint = Paint().apply { isFilterBitmap = true; isAntiAlias = false }
            canvas.drawBitmap(smallBmp, srcRect, dstRect, filterPaint)
            smallBmp.recycle()
        }
    }

    private fun buildStyleColorLut(
        palette: Palette, styleId: Int, isBands: Boolean, bandCount: Int
    ): IntArray {
        val paletteLut = palette.buildLut(256)
        val invBandDenom = 1f / (bandCount - 1f)
        val out = IntArray(SCL_RES)
        for (i in 0 until SCL_RES) {
            // LUT index → raw noise value in [-1, 1]
            val raw = (i.toFloat() / SCL_MAX) * 2f - 1f
            val tt: Float = when (styleId) {
                1 -> {
                    val r = 1f - abs(raw)
                    r * r
                }
                2 -> abs(raw)
                3 -> {
                    val b = abs(raw)
                    b * (2f - b)
                }
                4 -> {
                    val v = abs(raw)
                    1f - v / (0.05f + v)
                }
                else -> (raw + 1f) * 0.5f
            }
            val tClamped = if (tt < 0f) 0f else if (tt > 1f) 1f else tt
            val mt = if (isBands) {
                val band = floor(tClamped * bandCount).toInt().coerceIn(0, bandCount - 1)
                band * invBandDenom
            } else tClamped
            out[i] = paletteLut[(mt * 255f + 0.5f).toInt().coerceIn(0, 255)]
        }
        return out
    }

    /** Inner loop for drift and pulse modes (uniform coordinate offset). */
    private fun renderRowsOffset(
        pixels: IntArray, y0: Int, y1: Int, rw: Int, invScale: Float,
        offX: Float, offY: Float,
        warpActive: Boolean, warpAmount: Float, octaves: Int,
        noise: SimplexNoise, styleColorLut: IntArray
    ) {
        for (py in y0 until y1) {
            val rowOff = py * rw
            val baseY = py * invScale + offY
            for (px in 0 until rw) {
                var nx = px * invScale + offX
                var ny = baseY
                if (warpActive) {
                    val wx = noise.fbm(nx + 5.2f, ny + 1.3f, 3)
                    val wy = noise.fbm(nx + 1.7f, ny + 9.2f, 3)
                    nx += wx * warpAmount; ny += wy * warpAmount
                }
                val raw = if (octaves <= 1) noise.noise2D(nx, ny) else noise.fbm(nx, ny, octaves)
                var li = ((raw + 1f) * SCL_HALF).toInt()
                if (li < 0) li = 0 else if (li > SCL_MAX) li = SCL_MAX
                pixels[rowOff + px] = styleColorLut[li]
            }
        }
    }

    /** Inner loop for rotate mode (per-pixel 2x2 matrix multiply). */
    private fun renderRowsRotate(
        pixels: IntArray, y0: Int, y1: Int, rw: Int, invScale: Float,
        centerX: Float, centerY: Float, rotSin: Float, rotCos: Float,
        warpActive: Boolean, warpAmount: Float, octaves: Int,
        noise: SimplexNoise, styleColorLut: IntArray
    ) {
        for (py in y0 until y1) {
            val rowOff = py * rw
            val baseNy = py * invScale
            val dy = baseNy - centerY
            // Pull dy-dependent terms out of the inner x loop
            val cyRotBase = -dy * rotSin
            val cyRotBaseY = dy * rotCos
            for (px in 0 until rw) {
                val baseNx = px * invScale
                val dx = baseNx - centerX
                var nx = centerX + dx * rotCos + cyRotBase
                var ny = centerY + dx * rotSin + cyRotBaseY
                if (warpActive) {
                    val wx = noise.fbm(nx + 5.2f, ny + 1.3f, 3)
                    val wy = noise.fbm(nx + 1.7f, ny + 9.2f, 3)
                    nx += wx * warpAmount; ny += wy * warpAmount
                }
                val raw = if (octaves <= 1) noise.noise2D(nx, ny) else noise.fbm(nx, ny, octaves)
                var li = ((raw + 1f) * SCL_HALF).toInt()
                if (li < 0) li = 0 else if (li > SCL_MAX) li = SCL_MAX
                pixels[rowOff + px] = styleColorLut[li]
            }
        }
    }

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float {
        val octaves = (params["octaves"] as? Number)?.toInt() ?: 4
        val warp = (params["warpAmount"] as? Number)?.toFloat() ?: 0f
        return (0.3f + octaves * 0.1f + warp * 0.15f).coerceIn(0.3f, 1f)
    }
}
