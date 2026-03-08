package com.artmondo.algomodo.generators.animation

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import com.artmondo.algomodo.core.rng.SeededRNG
import com.artmondo.algomodo.core.rng.SimplexNoise
import com.artmondo.algomodo.data.palettes.Palette
import com.artmondo.algomodo.generators.Generator
import com.artmondo.algomodo.generators.ParamGroup
import com.artmondo.algomodo.generators.Parameter
import com.artmondo.algomodo.generators.Quality
import kotlin.math.*

/**
 * Kaleidoscopic animation built from noise with multiple pattern modes.
 *
 * For each pixel the angle from centre is folded into one segment of the
 * kaleidoscope (mirror symmetry). The folded coordinates are used to build
 * structured patterns — concentric rings × spoke lines (geometric), flowing
 * noise bands (organic), or hard-edged faceted cells (crystalline). The result
 * is mapped to the palette with optional contrast sharpening and color modes.
 */
class KaleidoscopeGenerator : Generator {

    override val id = "kaleidoscope"
    override val family = "animation"
    override val styleName = "Kaleidoscope"
    override val definition = "Kaleidoscopic animation created by folding noise across radial segments."
    override val algorithmNotes =
        "For each pixel, compute polar coordinates (r, theta) relative to centre. Fold theta " +
        "into one segment: theta_folded = abs(mod(theta, segmentAngle) - segmentAngle/2). " +
        "Pattern modes: geometric uses sin(r) * sin(theta * rings) for sharp ring/spoke grids; " +
        "organic uses fbm noise for flowing bands; crystalline uses quantised noise for facets. " +
        "Contrast sharpens transitions. Color modes shift the palette lookup by radius or angle+time."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema = listOf(
        Parameter.NumberParam(
            name = "Segments",
            key = "segments",
            group = ParamGroup.COMPOSITION,
            help = "Number of mirror segments — must be ≥ 3",
            min = 3f, max = 24f, step = 1f, default = 8f
        ),
        Parameter.SelectParam(
            name = "Pattern",
            key = "pattern",
            group = ParamGroup.COMPOSITION,
            help = "geometric: concentric rings × spokes · organic: noise-driven flowing bands · crystalline: hard-edged facets",
            options = listOf("geometric", "organic", "crystalline"),
            default = "geometric"
        ),
        Parameter.NumberParam(
            name = "Speed",
            key = "speed",
            group = ParamGroup.FLOW_MOTION,
            help = "Rotation and evolution speed",
            min = 0.1f, max = 3f, step = 0.1f, default = 1f
        ),
        Parameter.NumberParam(
            name = "Scale",
            key = "scale",
            group = ParamGroup.GEOMETRY,
            help = "Spatial zoom of the pattern",
            min = 0.5f, max = 5f, step = 0.5f, default = 2f
        ),
        Parameter.NumberParam(
            name = "Complexity",
            key = "complexity",
            group = ParamGroup.GEOMETRY,
            help = "Number of concentric bands / detail rings",
            min = 1f, max = 8f, step = 1f, default = 3f
        ),
        Parameter.SelectParam(
            name = "Color Mode",
            key = "colorMode",
            group = ParamGroup.COLOR,
            help = "palette: value → gradient · depth: radius shifts hue · iridescent: angle + time chromatic shimmer",
            options = listOf("palette", "depth", "iridescent"),
            default = "palette"
        ),
        Parameter.NumberParam(
            name = "Contrast",
            key = "thickness",
            group = ParamGroup.TEXTURE,
            help = "Edge sharpness — higher pushes patterns toward hard transitions",
            min = 0.3f, max = 3f, step = 0.1f, default = 1.2f
        )
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "segments" to 8f,
        "pattern" to "geometric",
        "speed" to 1f,
        "scale" to 2f,
        "complexity" to 3f,
        "colorMode" to "palette",
        "thickness" to 1.2f
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
        val cx = w / 2f
        val cy = h / 2f
        val dim = min(w, h).toFloat()

        val segments = (params["segments"] as? Number)?.toInt() ?: 8
        val pattern = (params["pattern"] as? String) ?: "geometric"
        val complexity = (params["complexity"] as? Number)?.toInt() ?: 3
        val rotationSpeed = (params["speed"] as? Number)?.toFloat() ?: 1f
        val zoom = (params["scale"] as? Number)?.toFloat() ?: 2f
        val colorMode = (params["colorMode"] as? String) ?: "palette"
        val contrast = (params["thickness"] as? Number)?.toFloat() ?: 1.2f

        val noise = SimplexNoise(seed)
        val rng = SeededRNG(seed)
        val segAngle = (2.0 * PI / segments).toFloat()
        val halfSeg = segAngle / 2f
        val rotation = time * rotationSpeed * 0.5f

        // Seeded offsets for variety
        val offsetX = rng.random() * 100f
        val offsetY = rng.random() * 100f

        val pixels = IntArray(w * h)

        val twoPi = 2f * PI.toFloat()

        for (py in 0 until h) {
            val rawDy = (py - cy) / dim * 2f
            for (px in 0 until w) {
                val rawDx = (px - cx) / dim * 2f

                // Polar coordinates
                val r = sqrt(rawDx * rawDx + rawDy * rawDy)
                var theta = atan2(rawDy, rawDx) + rotation

                // Fold into one segment with mirror symmetry
                theta = ((theta % twoPi) + twoPi) % twoPi
                var segTheta = theta % segAngle
                if (segTheta > halfSeg) {
                    segTheta = segAngle - segTheta
                }

                // Folded Cartesian coordinates for sampling
                val sx = r * cos(segTheta) * zoom
                val sy = r * sin(segTheta) * zoom

                val value: Float = when (pattern) {
                    "geometric" -> {
                        // Sharp concentric rings modulated by spoke lines
                        val ringFreq = complexity * 3f
                        val spokeFreq = complexity * 2f

                        // Rings: sin waves based on radius
                        val rings = sin(r * ringFreq * PI.toFloat() + time * rotationSpeed * 2f)
                        // Spokes: sin waves based on folded angle
                        val spokes = sin(segTheta * spokeFreq * segments.toFloat() + time * 0.5f)
                        // Noise modulation for organic feel
                        val nMod = noise.noise2D(sx * 2f + offsetX + time * 0.15f, sy * 2f + offsetY)
                        // Radial wave
                        val radialWave = sin(r * zoom * 8f - time * rotationSpeed * 3f)

                        // Combine: rings × spokes, modulated by noise
                        val combined = rings * 0.4f + spokes * 0.25f + radialWave * 0.2f + nMod * 0.15f
                        combined
                    }
                    "organic" -> {
                        // Flowing noise bands with domain warping
                        val warpX = noise.noise2D(
                            sx * 1.5f + offsetX + time * 0.12f,
                            sy * 1.5f + offsetY
                        ) * 0.5f
                        val warpY = noise.noise2D(
                            sx * 1.5f + offsetX + 50f,
                            sy * 1.5f + offsetY + time * 0.1f
                        ) * 0.5f

                        val wsx = sx + warpX
                        val wsy = sy + warpY

                        // Layered noise at warped coordinates
                        var v = 0f
                        var amp = 1f
                        var freq = 1f
                        for (oct in 0 until complexity) {
                            v += noise.noise2D(
                                wsx * freq * 2f + offsetX + time * 0.08f * (oct + 1),
                                wsy * freq * 2f + offsetY + time * 0.06f * (oct + 1)
                            ) * amp
                            amp *= 0.5f
                            freq *= 2.1f
                        }
                        // Add radial variation
                        v += sin(r * zoom * 4f + time * 0.8f) * 0.3f
                        v
                    }
                    "crystalline" -> {
                        // Hard-edged faceted cells using quantised noise
                        val cellScale = zoom * 3f
                        val nsx = sx * cellScale + offsetX + time * 0.1f
                        val nsy = sy * cellScale + offsetY + time * 0.08f

                        // Base noise value
                        val n1 = noise.noise2D(nsx, nsy)
                        // Secondary at different scale
                        val n2 = noise.noise2D(nsx * 2.3f + 30f, nsy * 2.3f + time * 0.12f)
                        // Tertiary for radial structure
                        val n3 = sin(r * complexity * 5f + n1 * 3f + time * rotationSpeed)

                        // Quantise to create hard edges
                        val raw = n1 * 0.5f + n2 * 0.3f + n3 * 0.2f
                        val levels = (complexity * 2f + 2f)
                        val quantised = (raw * levels).toInt().toFloat() / levels
                        quantised
                    }
                    else -> noise.noise2D(sx + time * 0.1f, sy + time * 0.08f)
                }

                // Apply contrast sharpening
                val sharpened = if (contrast > 1f) {
                    val centered = value  // already in roughly [-1, 1]
                    // Power curve for contrast
                    val sign = if (centered >= 0f) 1f else -1f
                    sign * abs(centered).pow(1f / contrast)
                } else {
                    value * contrast
                }

                // Map to [0, 1]
                val norm = (sharpened * 0.5f + 0.5f).coerceIn(0f, 1f)

                // Color mode
                val palVal = when (colorMode) {
                    "depth" -> {
                        // Radius shifts the palette lookup
                        ((norm + r * 0.4f) % 1f + 1f) % 1f
                    }
                    "iridescent" -> {
                        // Angle + time creates chromatic shimmer
                        val angleShift = (theta / twoPi) * 0.3f
                        val timeShift = sin(time * rotationSpeed * 0.5f) * 0.1f
                        ((norm + angleShift + timeShift) % 1f + 1f) % 1f
                    }
                    else -> norm
                }

                val baseColor = palette.lerpColor(palVal)

                // Subtle radial vignette — don't dim too aggressively
                val vignette = (1f - (r * 0.4f).coerceAtMost(0.7f)).coerceAtLeast(0.3f)
                val red = (Color.red(baseColor) * vignette).toInt().coerceIn(0, 255)
                val green = (Color.green(baseColor) * vignette).toInt().coerceIn(0, 255)
                val blue = (Color.blue(baseColor) * vignette).toInt().coerceIn(0, 255)

                pixels[py * w + px] = Color.rgb(red, green, blue)
            }
        }

        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
        canvas.drawBitmap(bitmap, 0f, 0f, null)
    }

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float {
        val complexity = (params["complexity"] as? Number)?.toFloat() ?: 3f
        return when (quality) {
            Quality.DRAFT -> complexity / 20f
            Quality.BALANCED -> complexity / 10f
            Quality.ULTRA -> complexity / 7f
        }.coerceIn(0.1f, 1f)
    }
}
