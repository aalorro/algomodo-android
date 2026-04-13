package com.artmondo.algomodo.generators.flux

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import com.artmondo.algomodo.audio.AudioAnalysis
import com.artmondo.algomodo.core.rng.SeededRNG
import com.artmondo.algomodo.data.palettes.Palette
import com.artmondo.algomodo.generators.Generator
import com.artmondo.algomodo.generators.ParamGroup
import com.artmondo.algomodo.generators.Parameter
import com.artmondo.algomodo.generators.Quality
import kotlin.math.*

/**
 * Renders an isosurface of multiple moving charge points -- classic metaball /
 * blobby implicit-surface visualization using per-pixel field summation.
 *
 * Seed-dependent ball properties are cached by a composite key so the RNG is
 * not re-created every frame.  Separate code paths for field/bands (no nearest
 * tracking) vs. nearest.  256-entry glow LUT eliminates Math.exp from the
 * inner loop.  Step-based rendering with bilinear upscale for animation.
 */
class FluxMetaballs2dGenerator : Generator {

    override val id = "flux-metaballs-2d"
    override val family = "flux"
    override val styleName = "Metaballs 2D"
    override val definition =
        "Renders an isosurface of multiple moving charge points -- classic metaball / " +
        "blobby implicit-surface visualization using per-pixel field summation"
    override val algorithmNotes =
        "Seed-dependent ball properties cached by seed key (avoid per-frame SeededRNG + allocations). " +
        "Color mode resolved to integer before loop -- separate code paths for field/bands (no nearest tracking) vs nearest. " +
        "Glow uses 256-entry pre-computed LUT indexed by field/threshold ratio, eliminating Math.exp from inner loop. " +
        "Step-based rendering with bilinear upscale during animation."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema = listOf(
        Parameter.NumberParam(
            name = "Ball Count",
            key = "ballCount",
            group = ParamGroup.COMPOSITION,
            help = null,
            min = 3f, max = 15f, step = 1f, default = 6f
        ),
        Parameter.NumberParam(
            name = "Ball Size",
            key = "ballSize",
            group = ParamGroup.GEOMETRY,
            help = null,
            min = 0.05f, max = 0.15f, step = 0.01f, default = 0.1f
        ),
        Parameter.NumberParam(
            name = "Threshold",
            key = "threshold",
            group = ParamGroup.TEXTURE,
            help = "Field threshold for the isosurface boundary -- lower = larger blobs",
            min = 0.5f, max = 2f, step = 0.05f, default = 1.0f
        ),
        Parameter.SelectParam(
            name = "Color Mode",
            key = "colorMode",
            group = ParamGroup.COLOR,
            help = "field: map field intensity to palette gradient | nearest: color of closest ball | bands: quantized field levels",
            options = listOf("field", "nearest", "bands"),
            default = "field"
        ),
        Parameter.NumberParam(
            name = "Edge Glow",
            key = "edgeGlow",
            group = ParamGroup.TEXTURE,
            help = "Exponential glow falloff near the isosurface boundary",
            min = 0f, max = 1f, step = 0.05f, default = 0.3f
        ),
        Parameter.NumberParam(
            name = "Speed",
            key = "speed",
            group = ParamGroup.FLOW_MOTION,
            help = "Orbital animation speed",
            min = 0.1f, max = 3f, step = 0.05f, default = 0.6f
        ),
        Parameter.NumberParam(
            name = "Audio Reactivity",
            key = "reactivity",
            group = ParamGroup.FLOW_MOTION,
            help = "Sensitivity to audio input (0 = none)",
            min = 0f, max = 2f, step = 0.1f, default = 1.0f
        )
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "ballCount" to 6f,
        "ballSize" to 0.1f,
        "threshold" to 1.0f,
        "colorMode" to "field",
        "edgeGlow" to 0.3f,
        "speed" to 0.6f,
        "reactivity" to 1.0f
    )

    // ── Cached seed-dependent ball properties ────────────────────────────
    private var cachedSeedKey = -1L
    private var cachedW = 0
    private var cachedH = 0
    private val basePosX = FloatArray(MAX_BALLS)
    private val basePosY = FloatArray(MAX_BALLS)
    private val orbitRad = FloatArray(MAX_BALLS)
    private val orbitSpd = FloatArray(MAX_BALLS)
    private val orbitPhs = FloatArray(MAX_BALLS)

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
        val minDim = min(w, h).toFloat()

        // ── Parameters ──────────────────────────────────────────────────
        val ballCount = (params["ballCount"] as? Number)?.toInt()?.coerceIn(3, MAX_BALLS) ?: 6
        val threshold = (params["threshold"] as? Number)?.toFloat() ?: 1.0f
        val colorMode = (params["colorMode"] as? String) ?: "field"
        val edgeGlowAmt = (params["edgeGlow"] as? Number)?.toFloat() ?: 0.3f
        val spd = (params["speed"] as? Number)?.toFloat() ?: 0.6f
        val rx = (params["reactivity"] as? Number)?.toFloat() ?: 1.0f

        val audioAnalysis = params["_audioAnalysis"] as? AudioAnalysis
        val audioBass = (audioAnalysis?.getBass(time) ?: 0f) * rx
        val audioMid = (audioAnalysis?.getMid(time) ?: 0f) * rx

        val ballSize = ((params["ballSize"] as? Number)?.toFloat() ?: 0.1f) * (1f + audioBass * 0.5f)
        val effectiveSpeed = spd * (1f + audioMid * 0.8f)

        // Color mode as integer to avoid string comparison in inner loop
        val cmId = when (colorMode) {
            "field" -> 0
            "nearest" -> 1
            "bands" -> 2
            else -> 0
        }

        // Quality step -- auto step=2 during animation when balanced
        var step = when (quality) {
            Quality.DRAFT -> 4
            Quality.ULTRA -> 1
            else -> 2
        }
        if (time > 0f && step < 2) step = 2

        // ── Cache seed-dependent ball properties ────────────────────────
        val seedKey = seed.toLong() + w.toLong() * 100000L + h.toLong()
        if (cachedSeedKey != seedKey || cachedW != w || cachedH != h) {
            val rng = SeededRNG(seed)
            for (i in 0 until MAX_BALLS) {
                basePosX[i] = rng.random() * w
                basePosY[i] = rng.random() * h
                orbitRad[i] = (0.05f + rng.random() * 0.15f) * minDim
                orbitSpd[i] = (0.5f + rng.random() * 1.5f) * if (rng.random() < 0.5f) 1f else -1f
                orbitPhs[i] = rng.random() * 2f * PI.toFloat()
            }
            cachedSeedKey = seedKey
            cachedW = w
            cachedH = h
        }

        // ── Compute ball positions for this frame ───────────────────────
        val ballRadius = ballSize * minDim
        val ballR2 = ballRadius * ballRadius

        val bx = FloatArray(ballCount)
        val by = FloatArray(ballCount)
        val br2 = FloatArray(ballCount)

        for (i in 0 until ballCount) {
            val phase = orbitPhs[i] + time * effectiveSpeed * orbitSpd[i]
            bx[i] = basePosX[i] + cos(phase) * orbitRad[i]
            by[i] = basePosY[i] + sin(phase) * orbitRad[i]
            br2[i] = ballR2
        }

        // ── Palette ramp LUT (256 entries) ──────────────────────────────
        val lut = palette.buildLut(256)

        // ── Per-ball colors (nearest mode) ──────────────────────────────
        val ballColors = if (cmId == 1) {
            val colorInts = palette.colorInts()
            val nc = colorInts.size
            IntArray(ballCount) { i -> colorInts[i % nc] }
        } else null

        // ── Glow LUT + glow pixel ramp ──────────────────────────────────
        val doGlow = edgeGlowAmt > 0.01f
        val glowFalloff = 3.0f + (1f - edgeGlowAmt) * 7.0f

        val glowPixels = if (doGlow) {
            IntArray(256) { i ->
                val ratio = i / 255f
                val intensity = exp(-glowFalloff * (1f - ratio))
                if (intensity > 0.01f) {
                    // Use ramp at lower half for glow tint, scaled by intensity
                    val rampIdx = (intensity * 128f).toInt().coerceAtMost(255)
                    val base = lut[rampIdx]
                    val r = (Color.red(base) * intensity).toInt().coerceIn(0, 255)
                    val g = (Color.green(base) * intensity).toInt().coerceIn(0, 255)
                    val b = (Color.blue(base) * intensity).toInt().coerceIn(0, 255)
                    Color.rgb(r, g, b)
                } else {
                    Color.BLACK
                }
            }
        } else null

        val invThreshold = 1f / threshold
        val bandCount = 6
        val invBandCount = 1f / bandCount

        // ── Determine rendering grid ────────────────────────────────────
        val cols = (w + step - 1) / step
        val rows = (h + step - 1) / step

        val smallPixels = IntArray(cols * rows)

        // ── Main pixel loop ─────────────────────────────────────────────
        if (cmId == 1) {
            // ── NEAREST mode -- must track nearest ball ─────────────────
            for (row in 0 until rows) {
                val py = row * step
                val rowOff = row * cols
                for (col in 0 until cols) {
                    val px = col * step
                    var field = 0f
                    var nearestBall = 0
                    var nearestDistSq = Float.MAX_VALUE

                    for (i in 0 until ballCount) {
                        val dx = px - bx[i]
                        val dy = py - by[i]
                        val distSq = dx * dx + dy * dy
                        field += br2[i] / (distSq + 1f)
                        if (distSq < nearestDistSq) {
                            nearestDistSq = distSq
                            nearestBall = i
                        }
                    }

                    smallPixels[rowOff + col] = when {
                        field >= threshold -> ballColors!![nearestBall]
                        doGlow -> {
                            val ratio = field * invThreshold
                            glowPixels!![(ratio * 255f).toInt().coerceIn(0, 255)]
                        }
                        else -> Color.BLACK
                    }
                }
            }
        } else {
            // ── FIELD / BANDS mode -- no nearest tracking needed ────────
            val isBands = cmId == 2

            for (row in 0 until rows) {
                val py = row * step
                val rowOff = row * cols
                for (col in 0 until cols) {
                    val px = col * step
                    var field = 0f

                    for (i in 0 until ballCount) {
                        val dx = px - bx[i]
                        val dy = py - by[i]
                        field += br2[i] / (dx * dx + dy * dy + 1f)
                    }

                    smallPixels[rowOff + col] = when {
                        field >= threshold -> {
                            var t = (field - threshold) * invThreshold
                            if (t > 1f) t = 1f
                            if (isBands) {
                                t = ((t * bandCount).toInt()) * invBandCount
                            }
                            lut[(t * 255f).toInt().coerceIn(0, 255)]
                        }
                        doGlow -> {
                            val ratio = field * invThreshold
                            glowPixels!![(ratio * 255f).toInt().coerceIn(0, 255)]
                        }
                        else -> Color.BLACK
                    }
                }
            }
        }

        // ── Write to bitmap with bilinear upscale when step > 1 ─────────
        if (step == 1) {
            bitmap.setPixels(smallPixels, 0, w, 0, 0, w, h)
            canvas.drawBitmap(bitmap, 0f, 0f, null)
        } else {
            val smallBmp = Bitmap.createBitmap(cols, rows, Bitmap.Config.ARGB_8888)
            smallBmp.setPixels(smallPixels, 0, cols, 0, 0, cols, rows)
            val upscaled = Bitmap.createScaledBitmap(smallBmp, w, h, true)
            canvas.drawBitmap(upscaled, 0f, 0f, null)
            smallBmp.recycle()
            upscaled.recycle()
        }
    }

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float {
        val balls = (params["ballCount"] as? Number)?.toFloat() ?: 6f
        val base = balls / 15f
        return when (quality) {
            Quality.DRAFT -> base * 0.3f
            Quality.BALANCED -> base * 0.6f
            Quality.ULTRA -> base
        }.coerceIn(0.1f, 1f)
    }

    companion object {
        private const val MAX_BALLS = 15
    }
}
