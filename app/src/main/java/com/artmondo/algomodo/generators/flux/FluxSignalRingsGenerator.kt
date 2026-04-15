package com.artmondo.algomodo.generators.flux

import android.graphics.*
import com.artmondo.algomodo.audio.AudioAnalysis
import com.artmondo.algomodo.core.rng.SeededRNG
import com.artmondo.algomodo.data.palettes.Palette
import com.artmondo.algomodo.generators.Generator
import com.artmondo.algomodo.generators.ParamGroup
import com.artmondo.algomodo.generators.Parameter
import com.artmondo.algomodo.generators.Quality
import kotlin.math.*

/**
 * Concentric rings whose radii are modulated by oscillating signals at different
 * frequencies, creating pulsing interference moire patterns.
 *
 * Two-pass rendering per ring: a wide, low-alpha glow stroke followed by a thin
 * crisp core stroke. Additive (PorterDuff.Mode.ADD) blending creates natural
 * moire interference where rings overlap. Pre-computed cos/sin LUT and ring
 * parameters in FloatArrays. Seamless ring closure via explicit lineTo back to
 * the first point (avoids Path.close() seam artefacts).
 */
class FluxSignalRingsGenerator : Generator {

    override val id = "flux-signal-rings"
    override val family = "flux"
    override val styleName = "Signal Rings"
    override val definition =
        "Concentric rings whose radii are modulated by oscillating signals at different " +
        "frequencies, creating pulsing interference moire patterns"
    override val algorithmNotes =
        "Android Canvas Path rendering -- each ring built once as Path, drawn twice (glow + core). " +
        "Additive blending via PorterDuff.Mode.ADD creates natural moire interference. " +
        "Pre-computed cos/sin LUT and ring parameters in FloatArrays. Seamless ring closure."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema = listOf(
        Parameter.NumberParam(
            name = "Ring Count",
            key = "ringCount",
            group = ParamGroup.COMPOSITION,
            help = "Number of concentric signal rings",
            min = 5f, max = 40f, step = 1f, default = 15f
        ),
        Parameter.SelectParam(
            name = "Signal Type",
            key = "signalType",
            group = ParamGroup.COMPOSITION,
            help = "sine: smooth oscillation | square: hard-edged pulses | sawtooth: ramp waves | noise: stochastic modulation",
            options = listOf("sine", "square", "sawtooth", "noise"),
            default = "sine"
        ),
        Parameter.NumberParam(
            name = "Base Radius",
            key = "baseRadius",
            group = ParamGroup.GEOMETRY,
            help = "Starting radius of the innermost ring as a fraction of canvas size",
            min = 0.1f, max = 0.5f, step = 0.05f, default = 0.4f
        ),
        Parameter.NumberParam(
            name = "Mod Depth",
            key = "modDepth",
            group = ParamGroup.GEOMETRY,
            help = "How far signal modulation displaces each ring radius",
            min = 0.05f, max = 0.5f, step = 0.01f, default = 0.15f
        ),
        Parameter.NumberParam(
            name = "Freq Spread",
            key = "freqSpread",
            group = ParamGroup.GEOMETRY,
            help = "How much signal frequency varies between inner and outer rings",
            min = 0.5f, max = 5f, step = 0.1f, default = 2.0f
        ),
        Parameter.NumberParam(
            name = "Line Width",
            key = "lineWidth",
            group = ParamGroup.TEXTURE,
            help = "Thickness of each ring line",
            min = 0.5f, max = 4f, step = 0.1f, default = 1.5f
        ),
        Parameter.NumberParam(
            name = "Speed",
            key = "speed",
            group = ParamGroup.FLOW_MOTION,
            help = "Animation speed of signal oscillation",
            min = 0.1f, max = 3f, step = 0.05f, default = 0.8f
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
        "ringCount" to 15f,
        "signalType" to "sine",
        "baseRadius" to 0.4f,
        "modDepth" to 0.15f,
        "freqSpread" to 2.0f,
        "lineWidth" to 1.5f,
        "speed" to 0.8f,
        "reactivity" to 1.0f
    )

    // Module-level noise tables for noise signal type
    private val perm = IntArray(512)
    private val vals = FloatArray(256)

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

        // ── Parameters ────────────────────────────────────────────────────
        val ringCount = ((params["ringCount"] as? Number)?.toInt() ?: 15).coerceIn(1, 40)
        val signalType = (params["signalType"] as? String) ?: "sine"
        val baseRadius = (params["baseRadius"] as? Number)?.toFloat() ?: 0.4f
        val modDepthParam = (params["modDepth"] as? Number)?.toFloat() ?: 0.15f
        val freqSpread = (params["freqSpread"] as? Number)?.toFloat() ?: 2.0f
        val lineWidth = (params["lineWidth"] as? Number)?.toFloat() ?: 1.5f
        val spd = (params["speed"] as? Number)?.toFloat() ?: 0.8f
        val rx = (params["reactivity"] as? Number)?.toFloat() ?: 1.0f

        // ── Audio ─────────────────────────────────────────────────────────
        val audioAnalysis = params["_audioAnalysis"] as? AudioAnalysis
        val audioBass = (audioAnalysis?.getBass(time) ?: 0f) * rx
        val audioMid = (audioAnalysis?.getMid(time) ?: 0f) * rx

        val modDepth = modDepthParam * (1f + audioBass * 1.5f)
        val effectiveFreqSpread = freqSpread * (1f + audioMid * 1.0f)

        val t = time * spd
        val minDim = min(w, h).toFloat()
        val halfW = w * 0.5f
        val halfH = h * 0.5f

        val sigId = when (signalType) {
            "sine" -> 0
            "square" -> 1
            "sawtooth" -> 2
            else -> 3 // noise
        }

        // ── Seed-dependent noise tables (noise signal type only) ──────────
        val rng = SeededRNG(seed)

        if (sigId == 3) {
            for (i in 0 until 256) {
                perm[i] = i
                vals[i] = rng.random() * 2f - 1f
            }
            for (i in 255 downTo 1) {
                val j = (rng.random() * (i + 1)).toInt()
                val tmp = perm[i]; perm[i] = perm[j]; perm[j] = tmp
            }
            for (i in 0 until 256) perm[i + 256] = perm[i]
        }

        // ── Value noise function ──────────────────────────────────────────
        fun vN(x: Float, y: Float): Float {
            val xb = x + 65536f
            val yb = y + 65536f
            val xi = xb.toInt()
            val yi = yb.toInt()
            val fx = xb - xi
            val fy = yb - yi
            val xMask = xi and 255
            val yMask = yi and 255
            val sx = fx * fx * (3f - 2f * fx)
            val sy = fy * fy * (3f - 2f * fy)
            val py0 = perm[yMask]
            val py1 = perm[yMask + 1]
            val a = vals[perm[xMask + py0] and 255]
            val b = vals[perm[xMask + 1 + py0] and 255]
            val c = vals[perm[xMask + py1] and 255]
            val d = vals[perm[xMask + 1 + py1] and 255]
            val p = a + sx * (b - a)
            val q = c + sx * (d - c)
            return p + sy * (q - p)
        }

        // ── Pre-compute ring parameters ───────────────────────────────────
        val baseFreq = 3.0f
        val ringSpacing = 0.03f
        val modScale = modDepth * minDim

        val ringBaseRadii = FloatArray(ringCount)
        val ringFreqs = FloatArray(ringCount)
        val ringPhases = FloatArray(ringCount)

        for (i in 0 until ringCount) {
            ringBaseRadii[i] = (baseRadius + i * ringSpacing) * minDim
            ringFreqs[i] = baseFreq * (1f + i * effectiveFreqSpread / ringCount)
            ringPhases[i] = rng.range(0f, TAU)
        }

        // ── Palette colors per ring ───────────────────────────────────────
        val colorInts = palette.colorInts()
        val nC = colorInts.size
        val nCm1 = nC - 1

        val ringCoreColors = IntArray(ringCount)
        val ringGlowColors = IntArray(ringCount)

        for (i in 0 until ringCount) {
            val ci = (i.toFloat() / ringCount) * nCm1
            val i0 = ci.toInt()
            val i1 = if (i0 < nCm1) i0 + 1 else nCm1
            val f = ci - i0
            val r = (Color.red(colorInts[i0]) + (Color.red(colorInts[i1]) - Color.red(colorInts[i0])) * f).toInt()
            val g = (Color.green(colorInts[i0]) + (Color.green(colorInts[i1]) - Color.green(colorInts[i0])) * f).toInt()
            val b = (Color.blue(colorInts[i0]) + (Color.blue(colorInts[i1]) - Color.blue(colorInts[i0])) * f).toInt()
            ringCoreColors[i] = Color.rgb(r, g, b)
            ringGlowColors[i] = Color.argb(89, r, g, b) // ~0.35 * 255 = 89
        }

        // ── Angle samples -- enough for smooth curves ─────────────────────
        val angleSamples = when (quality) {
            Quality.DRAFT -> 180
            Quality.ULTRA -> 720
            else -> 360
        }
        val angleStep = TAU / angleSamples

        // Pre-compute cos/sin LUT
        val cosA = FloatArray(angleSamples)
        val sinA = FloatArray(angleSamples)
        for (j in 0 until angleSamples) {
            val a = j * angleStep
            cosA[j] = cos(a)
            sinA[j] = sin(a)
        }

        // ── Clear to black ────────────────────────────────────────────────
        canvas.drawColor(Color.BLACK)

        // ── Set up paints ─────────────────────────────────────────────────
        val glowWidth = lineWidth * 4f + 2f

        val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = glowWidth
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
        }

        val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = lineWidth
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
        }

        // ── Build Path per ring, draw twice (glow + core) ─────────────────
        for (i in 0 until ringCount) {
            val freq = ringFreqs[i]
            val phase = ringPhases[i]
            val baseR = ringBaseRadii[i]

            val path = Path()

            // Compute first point signal
            val signal0: Float = when (sigId) {
                0 -> sin(phase + t)
                1 -> if (sin(phase + t) >= 0f) 1f else -1f
                2 -> {
                    val raw = t + phase
                    (raw - floor(raw)) * 2f - 1f
                }
                else -> vN(0f, t * 0.5f + phase)
            }
            val r0 = baseR + modScale * signal0
            path.moveTo(halfW + r0 * cosA[0], halfH + r0 * sinA[0])

            // Interior points
            for (j in 1 until angleSamples) {
                val angle = j * angleStep

                val signal: Float = when (sigId) {
                    0 -> sin(angle * freq + t + phase)
                    1 -> if (sin(angle * freq + t + phase) >= 0f) 1f else -1f
                    2 -> {
                        val raw = angle * freq / TAU + t + phase
                        (raw - floor(raw)) * 2f - 1f
                    }
                    else -> vN(angle * freq * 0.5f, t * 0.5f + phase)
                }

                val r = baseR + modScale * signal
                path.lineTo(halfW + r * cosA[j], halfH + r * sinA[j])
            }

            // Close seamlessly back to the first point (avoid path.close() seam)
            path.lineTo(halfW + r0 * cosA[0], halfH + r0 * sinA[0])

            // Pass 1: glow halo
            glowPaint.color = ringGlowColors[i]
            canvas.drawPath(path, glowPaint)

            // Pass 2: crisp core
            corePaint.color = ringCoreColors[i]
            canvas.drawPath(path, corePaint)
        }
    }

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float {
        val rings = (params["ringCount"] as? Number)?.toFloat() ?: 15f
        val isNoise = if ((params["signalType"] as? String) == "noise") 1.3f else 1f
        val base = (rings / 40f) * isNoise
        return when (quality) {
            Quality.DRAFT -> base * 0.3f
            Quality.BALANCED -> base * 0.6f
            Quality.ULTRA -> base
        }.coerceIn(0.05f, 1f)
    }

    companion object {
        private const val TAU = 2.0f * 3.1415926535897932f
    }
}
