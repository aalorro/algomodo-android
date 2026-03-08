package com.artmondo.algomodo.generators.animation

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import com.artmondo.algomodo.core.rng.SeededRNG
import com.artmondo.algomodo.data.palettes.Palette
import com.artmondo.algomodo.generators.Generator
import com.artmondo.algomodo.generators.ParamGroup
import com.artmondo.algomodo.generators.Parameter
import com.artmondo.algomodo.generators.Quality
import kotlin.math.*

/**
 * Animated plasma effect with domain warping and feedback-like turbulence.
 *
 * Classic plasma built from layered sine/cosine interference, enhanced with
 * double domain-warp (coordinates displaced by independent sine fields before
 * sampling), rotational twist, contrast sharpening, scale pulsing, and multiple
 * blend modes. The result is mapped to the palette for vivid, detailed output.
 */
class PlasmaFeedbackGenerator : Generator {

    override val id = "plasma-feedback"
    override val family = "animation"
    override val styleName = "Plasma Feedback"
    override val definition = "Animated plasma effect built from layered sine-wave interference, mapped to the palette."
    override val algorithmNotes =
        "For each pixel (x, y), apply double domain-warp: dx1 = sin(y*f1+t), dy1 = cos(x*f2+t), " +
        "then dx2 = sin((y+dy1)*f3+t), dy2 = cos((x+dx1)*f4+t). Warped coordinates (x+dx1+dx2, " +
        "y+dy1+dy2) are fed to layered sin*cos interference. Twist rotates sample coords by a " +
        "sine-driven angle. Contrast applies power-curve sharpening. Blend modes transform " +
        "the final value (smooth, additive, bands, ripple) before palette mapping."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema = listOf(
        Parameter.NumberParam(
            name = "Scale",
            key = "scale",
            group = ParamGroup.COMPOSITION,
            help = null,
            min = 0.5f, max = 6f, step = 0.1f, default = 2.0f
        ),
        Parameter.NumberParam(
            name = "Layers",
            key = "layers",
            group = ParamGroup.COMPOSITION,
            help = "Number of overlapping noise octaves — each successive layer doubles the frequency and halves the amplitude",
            min = 1f, max = 5f, step = 1f, default = 3f
        ),
        Parameter.NumberParam(
            name = "Warp",
            key = "warp",
            group = ParamGroup.COMPOSITION,
            help = "Double domain-warp intensity — displaces sample coordinates twice using independent noise fields, creating deep turbulent feedback-like structure. 0 = plain FBM. 1–2 = rich folded plasma.",
            min = 0f, max = 2f, step = 0.1f, default = 0.8f
        ),
        Parameter.NumberParam(
            name = "Twist",
            key = "twist",
            group = ParamGroup.COMPOSITION,
            help = "Rotational warp — a noise-driven rotation angle is applied to each layer's sample coordinates, producing swirling helical and spiral structures. 0 = no rotation.",
            min = 0f, max = 1.5f, step = 0.05f, default = 0f
        ),
        Parameter.NumberParam(
            name = "Contrast",
            key = "contrast",
            group = ParamGroup.TEXTURE,
            help = null,
            min = 0.5f, max = 3f, step = 0.1f, default = 1.4f
        ),
        Parameter.NumberParam(
            name = "Pulse",
            key = "pulse",
            group = ParamGroup.FLOW_MOTION,
            help = "Scale breathing — oscillates the global zoom as scale × (1 + pulse × sin(t)), making the entire field expand and contract rhythmically",
            min = 0f, max = 1f, step = 0.05f, default = 0f
        ),
        Parameter.NumberParam(
            name = "Speed",
            key = "speed",
            group = ParamGroup.FLOW_MOTION,
            help = null,
            min = 0.1f, max = 3f, step = 0.05f, default = 0.6f
        ),
        Parameter.SelectParam(
            name = "Blend Mode",
            key = "blend",
            group = ParamGroup.COLOR,
            help = "smooth: maps [−1,1] linearly to [0,1] | additive: abs(v) for symmetric bright plasma | bands: sin(v·4π + t) animated colour rings | ripple: two-frequency sine interference — multi-scale wave patterns",
            options = listOf("smooth", "additive", "bands", "ripple"),
            default = "smooth"
        )
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "scale" to 2.0f,
        "speed" to 0.6f,
        "layers" to 3f,
        "contrast" to 1.4f,
        "warp" to 0.8f,
        "twist" to 0f,
        "pulse" to 0f,
        "blend" to "smooth"
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
        val dim = min(w, h).toFloat()

        val layers = (params["layers"] as? Number)?.toInt() ?: 3
        val speed = (params["speed"] as? Number)?.toFloat() ?: 0.6f
        val baseZoom = (params["scale"] as? Number)?.toFloat() ?: 2.0f
        val warp = (params["warp"] as? Number)?.toFloat() ?: 0.8f
        val twist = (params["twist"] as? Number)?.toFloat() ?: 0f
        val contrast = (params["contrast"] as? Number)?.toFloat() ?: 1.4f
        val pulse = (params["pulse"] as? Number)?.toFloat() ?: 0f
        val blend = (params["blend"] as? String) ?: "smooth"

        val rng = SeededRNG(seed)

        // Apply pulse: oscillate zoom
        val zoom = baseZoom * (1f + pulse * sin(time * speed * 0.7f))

        val t = time * speed

        // Seeded per-layer parameters
        val freqs = FloatArray(layers)
        val phaseX = FloatArray(layers)
        val phaseY = FloatArray(layers)
        val amps = FloatArray(layers)
        val angleOffsets = FloatArray(layers) // for twist

        for (i in 0 until layers) {
            freqs[i] = (i + 1).toFloat() * 0.8f * zoom
            phaseX[i] = rng.random() * PI.toFloat() * 2f
            phaseY[i] = rng.random() * PI.toFloat() * 2f
            amps[i] = 1f / (i + 1).toFloat()
            angleOffsets[i] = rng.random() * PI.toFloat() * 2f
        }

        // Seeded warp frequencies
        val warpF1 = 2.3f + rng.random() * 1.5f
        val warpF2 = 1.7f + rng.random() * 1.5f
        val warpF3 = 3.1f + rng.random() * 1.5f
        val warpF4 = 2.7f + rng.random() * 1.5f
        val warpPhase1 = rng.random() * PI.toFloat() * 2f
        val warpPhase2 = rng.random() * PI.toFloat() * 2f

        val pi = PI.toFloat()
        val twoPi = 2f * pi

        val pixels = IntArray(w * h)

        for (py in 0 until h) {
            val baseY = py.toFloat() / dim
            for (px in 0 until w) {
                val baseX = px.toFloat() / dim

                var nx = baseX
                var ny = baseY

                // Double domain warp for turbulent feedback structure
                if (warp > 0f) {
                    // First warp pass
                    val dx1 = sin(ny * warpF1 * twoPi + t * 1.3f + warpPhase1) * warp * 0.3f
                    val dy1 = cos(nx * warpF2 * twoPi + t * 1.1f + warpPhase2) * warp * 0.3f

                    // Second warp pass (feeds back from first)
                    val dx2 = sin((ny + dy1) * warpF3 * twoPi + t * 0.7f) * warp * 0.2f
                    val dy2 = cos((nx + dx1) * warpF4 * twoPi + t * 0.9f) * warp * 0.2f

                    nx += dx1 + dx2
                    ny += dy1 + dy2
                }

                // Accumulate layers
                var value = 0f
                var maxAmp = 0f

                for (i in 0 until layers) {
                    val f = freqs[i]
                    var lx = nx * f
                    var ly = ny * f

                    // Rotational twist: rotate sample coords by a sine-driven angle
                    if (twist > 0f) {
                        val twistAngle = sin(
                            (nx + ny) * 3f + t * 0.5f * (i + 1) + angleOffsets[i]
                        ) * twist * pi
                        val cosA = cos(twistAngle)
                        val sinA = sin(twistAngle)
                        val rlx = lx * cosA - ly * sinA
                        val rly = lx * sinA + ly * cosA
                        lx = rlx
                        ly = rly
                    }

                    val timeScaleX = t * (i + 1) * 0.3f
                    val timeScaleY = t * (i + 1) * 0.27f

                    // Primary interference: sin × cos
                    val sinPart = sin(lx * twoPi + timeScaleX + phaseX[i])
                    val cosPart = cos(ly * twoPi + timeScaleY + phaseY[i])

                    // Cross diagonal term for richer interference
                    val cross = sin((lx + ly) * pi + t * 0.2f * (i + 1))

                    // Radial distance term for circular structure
                    val dist = sqrt(
                        (nx - 0.5f) * (nx - 0.5f) + (ny - 0.5f) * (ny - 0.5f)
                    )
                    val radial = sin(dist * f * twoPi * 0.8f - t * (i + 1) * 0.15f)

                    value += amps[i] * (sinPart * cosPart + cross * 0.4f + radial * 0.2f)
                    maxAmp += amps[i] * 1.6f
                }

                // Normalize to [-1, 1]
                val normalized = (value / maxAmp).coerceIn(-1f, 1f)

                // Apply contrast: power-curve sharpening
                val contrasted = if (contrast != 1f) {
                    val sign = if (normalized >= 0f) 1f else -1f
                    sign * abs(normalized).pow(1f / contrast)
                } else {
                    normalized
                }

                // Apply blend mode
                val blended = when (blend) {
                    "additive" -> {
                        // abs() for symmetric bright plasma
                        abs(contrasted)
                    }
                    "bands" -> {
                        // sin() creates animated colour rings
                        sin(contrasted * 4f * pi + t * 1.5f) * 0.5f + 0.5f
                    }
                    "ripple" -> {
                        // Two-frequency sine interference
                        val r1 = sin(contrasted * 6f * pi + t)
                        val r2 = sin(contrasted * 10f * pi - t * 1.3f)
                        (r1 * 0.6f + r2 * 0.4f) * 0.5f + 0.5f
                    }
                    else -> {
                        // smooth: linear map [-1,1] → [0,1]
                        contrasted * 0.5f + 0.5f
                    }
                }

                val palVal = blended.coerceIn(0f, 1f)
                pixels[py * w + px] = palette.lerpColor(palVal)
            }
        }

        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
        canvas.drawBitmap(bitmap, 0f, 0f, null)
    }

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float {
        val layers = (params["layers"] as? Number)?.toFloat() ?: 3f
        val warp = (params["warp"] as? Number)?.toFloat() ?: 0.8f
        val base = layers * (1f + warp * 0.5f)
        return when (quality) {
            Quality.DRAFT -> base / 20f
            Quality.BALANCED -> base / 10f
            Quality.ULTRA -> base / 8f
        }.coerceIn(0.1f, 1f)
    }
}
