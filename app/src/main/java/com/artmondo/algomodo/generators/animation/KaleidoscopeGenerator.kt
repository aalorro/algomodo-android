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
 * noise bands (organic), hard-edged faceted cells (crystalline), layered
 * sacred geometry (mandala), recursive domain-warped noise (fractal),
 * radial burst rays (starburst), or tile-based cells (mosaic).
 * The result is mapped to the palette with contrast sharpening and color modes.
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
        "organic uses fbm noise for flowing bands; crystalline uses quantised noise for facets; " +
        "mandala uses layered harmonic products; fractal uses iterated domain warping; " +
        "starburst uses sharp radial spoke bursts; mosaic uses quantised polar cells. " +
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
            help = "geometric: rings × spokes · organic: noise bands · crystalline: facets · mandala: sacred geometry · fractal: domain warping · starburst: radial bursts · mosaic: tiled cells · interference: wave superposition · electric: plasma arcs · lace: delicate filigree · psychedelic: warped spiral bands",
            options = listOf("geometric", "organic", "crystalline", "mandala", "fractal", "starburst", "mosaic", "interference", "electric", "lace", "psychedelic"),
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
        Parameter.NumberParam(
            name = "Detail",
            key = "detail",
            group = ParamGroup.GEOMETRY,
            help = "Fine-grain detail overlay — adds high-frequency harmonics on top of the base pattern",
            min = 1f, max = 5f, step = 1f, default = 2f
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
        ),
        Parameter.NumberParam(
            name = "Sharpness",
            key = "sharpness",
            group = ParamGroup.TEXTURE,
            help = "Crispness of pattern transitions — higher values produce harder, more defined edges",
            min = 0.5f, max = 5f, step = 0.5f, default = 2f
        )
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "segments" to 8f,
        "pattern" to "geometric",
        "speed" to 1f,
        "scale" to 2f,
        "complexity" to 3f,
        "detail" to 2f,
        "colorMode" to "palette",
        "thickness" to 1.2f,
        "sharpness" to 2f
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
        val detail = (params["detail"] as? Number)?.toInt() ?: 2
        val rotationSpeed = (params["speed"] as? Number)?.toFloat() ?: 1f
        val zoom = (params["scale"] as? Number)?.toFloat() ?: 2f
        val colorMode = (params["colorMode"] as? String) ?: "palette"
        val contrast = (params["thickness"] as? Number)?.toFloat() ?: 1.2f
        val sharpness = (params["sharpness"] as? Number)?.toFloat() ?: 2f

        val noise = SimplexNoise(seed)
        val rng = SeededRNG(seed)
        val segAngle = (2.0 * PI / segments).toFloat()
        val halfSeg = segAngle / 2f
        val rotation = time * rotationSpeed * 0.5f

        val offsetX = rng.random() * 100f
        val offsetY = rng.random() * 100f

        val twoPi = 2f * PI.toFloat()

        // --- Pre-compute LUTs ---
        val paletteLut = palette.buildLut(256)

        // Sharpen LUT: maps [0..255] input to sharpened [0..1] output
        val invContrast = 1f / contrast
        val sharpenLut = FloatArray(256) { i ->
            val v = i / 255f * 2f - 1f          // remap to [-1, 1]
            val sign = if (v >= 0f) 1f else -1f
            val sharpened = if (contrast > 1f) sign * abs(v).pow(invContrast)
            else v * contrast
            (sharpened * 0.5f + 0.5f).coerceIn(0f, 1f)
        }

        // Iridescent time shift (constant for this frame)
        val iriTimeShift = sin(time * rotationSpeed * 0.5f) * 0.1f

        // --- Polar buffer: compute pattern + color in polar space ---
        val radSteps = when (quality) {
            Quality.DRAFT -> 250
            Quality.BALANCED -> 400
            Quality.ULTRA -> 600
        }
        val angSteps = when (quality) {
            Quality.DRAFT -> 450
            Quality.BALANCED -> 720
            Quality.ULTRA -> 1080
        }
        val maxR = sqrt(2f)  // max possible radius in normalized coords

        val polarBuffer = IntArray(radSteps * angSteps)

        for (ri in 0 until radSteps) {
            val r = ri.toFloat() / (radSteps - 1) * maxR

            // Vignette for this radius
            val vignette = (1f - (r * 0.4f).coerceAtMost(0.7f)).coerceAtLeast(0.3f)

            for (ai in 0 until angSteps) {
                val theta = ai.toFloat() / angSteps * twoPi

                // Fold into one segment with mirror symmetry
                val thetaFolded = ((theta + rotation) % twoPi + twoPi) % twoPi
                var segTheta = thetaFolded % segAngle
                if (segTheta > halfSeg) segTheta = segAngle - segTheta

                val sx = r * cos(segTheta) * zoom
                val sy = r * sin(segTheta) * zoom

                var value: Float = when (pattern) {
                    "geometric" -> {
                        val ringFreq = complexity * 3f
                        val spokeFreq = complexity * 2f
                        val rings = sin(r * ringFreq * PI.toFloat() + time * rotationSpeed * 2f)
                        val spokes = sin(segTheta * spokeFreq * segments.toFloat() + time * 0.5f)
                        val nMod = noise.noise2D(sx * 2f + offsetX + time * 0.15f, sy * 2f + offsetY)
                        val radialWave = sin(r * zoom * 8f - time * rotationSpeed * 3f)
                        rings * 0.4f + spokes * 0.25f + radialWave * 0.2f + nMod * 0.15f
                    }
                    "organic" -> {
                        val warpX = noise.noise2D(sx * 1.5f + offsetX + time * 0.12f, sy * 1.5f + offsetY) * 0.5f
                        val warpY = noise.noise2D(sx * 1.5f + offsetX + 50f, sy * 1.5f + offsetY + time * 0.1f) * 0.5f
                        val wsx = sx + warpX
                        val wsy = sy + warpY
                        var v = 0f; var amp = 1f; var freq = 1f
                        for (oct in 0 until complexity) {
                            v += noise.noise2D(
                                wsx * freq * 2f + offsetX + time * 0.08f * (oct + 1),
                                wsy * freq * 2f + offsetY + time * 0.06f * (oct + 1)
                            ) * amp
                            amp *= 0.5f; freq *= 2.1f
                        }
                        v + sin(r * zoom * 4f + time * 0.8f) * 0.3f
                    }
                    "crystalline" -> {
                        val cellScale = zoom * 3f
                        val nsx = sx * cellScale + offsetX + time * 0.1f
                        val nsy = sy * cellScale + offsetY + time * 0.08f
                        val n1 = noise.noise2D(nsx, nsy)
                        val n2 = noise.noise2D(nsx * 2.3f + 30f, nsy * 2.3f + time * 0.12f)
                        val n3 = sin(r * complexity * 5f + n1 * 3f + time * rotationSpeed)
                        val raw = n1 * 0.5f + n2 * 0.3f + n3 * 0.2f
                        val levels = (complexity * 2f + 2f)
                        (raw * levels).toInt().toFloat() / levels
                    }
                    "mandala" -> {
                        // Layered harmonic products — sacred geometry flower patterns
                        var v = 0f
                        for (harm in 1..complexity) {
                            val rFreq = harm * 2f * PI.toFloat()
                            val aFreq = (harm * segments / 2f)
                            val radial = cos(r * rFreq * zoom + time * rotationSpeed * 0.3f * harm)
                            val angular = sin(segTheta * aFreq + time * 0.2f * harm)
                            v += radial * angular / harm
                        }
                        // Add petal envelope
                        val petal = abs(cos(segTheta * segments * 0.5f)).pow(0.5f)
                        v * 0.7f + petal * sin(r * zoom * 6f + time * rotationSpeed) * 0.3f
                    }
                    "fractal" -> {
                        // Iterated domain warping — self-similar branching structures
                        var fx = sx * 2f + offsetX
                        var fy = sy * 2f + offsetY
                        var v = 0f
                        var amp = 1f
                        for (iter in 0 until complexity.coerceAtMost(5)) {
                            val n = noise.noise2D(fx + time * 0.05f * (iter + 1), fy + time * 0.04f * (iter + 1))
                            v += n * amp
                            // Domain warp: feed noise output back as coordinate offset
                            fx += n * 1.5f
                            fy += noise.noise2D(fy + 77f, fx + time * 0.03f) * 1.5f
                            amp *= 0.6f
                        }
                        v
                    }
                    "starburst" -> {
                        // Sharp radial burst rays with modulation
                        val spokeCount = segments.toFloat() * complexity
                        val spoke = abs(cos(segTheta * spokeCount * 0.5f))
                        val sharpSpoke = spoke.pow(sharpness * 2f)
                        val radialPulse = sin(r * zoom * 10f - time * rotationSpeed * 4f)
                        val radialEnvelope = (1f - r * 0.5f).coerceAtLeast(0f)
                        val burst = sharpSpoke * (0.6f + radialPulse * 0.4f) * radialEnvelope
                        val glow = exp(-r * 2f) * sin(time * rotationSpeed * 2f + segTheta * segments) * 0.3f
                        burst + glow
                    }
                    "mosaic" -> {
                        // Quantised polar cells — tile-based pattern
                        val radialBands = (complexity * 3f + 2f)
                        val angularBands = (segments * complexity * 2f)
                        val rCell = floor(r * zoom * radialBands) / radialBands
                        val aCell = floor(segTheta / segAngle * angularBands) / angularBands
                        // Noise-driven color per cell
                        val cellNoise = noise.noise2D(
                            rCell * 10f + offsetX + time * 0.08f,
                            aCell * 10f + offsetY + time * 0.06f
                        )
                        // Sharp cell edges via distance to cell boundary
                        val rFrac = (r * zoom * radialBands) % 1f
                        val aFrac = (segTheta / segAngle * angularBands) % 1f
                        val edgeDist = min(min(rFrac, 1f - rFrac), min(aFrac, 1f - aFrac))
                        val edge = (edgeDist * sharpness * 5f).coerceAtMost(1f)
                        cellNoise * edge
                    }
                    "interference" -> {
                        // Superposition of multiple wave sources at different positions
                        var v = 0f
                        for (src in 0 until complexity.coerceAtMost(6)) {
                            val srcAngle = src * twoPi / complexity + time * rotationSpeed * 0.2f
                            val srcR = 0.3f + src * 0.15f
                            val srcX = cos(srcAngle) * srcR * zoom
                            val srcY = sin(srcAngle) * srcR * zoom
                            val dist = sqrt((sx - srcX).pow(2) + (sy - srcY).pow(2))
                            v += sin(dist * complexity * 8f - time * rotationSpeed * 3f) / (1f + dist * 2f)
                        }
                        v
                    }
                    "electric" -> {
                        // Plasma arcs — ridged noise with bright arc-like ridges
                        val ex = sx * 3f + offsetX + time * 0.12f
                        val ey = sy * 3f + offsetY + time * 0.1f
                        var v = 0f; var amp = 1f; var freq = 1f
                        for (oct in 0 until complexity.coerceAtMost(6)) {
                            val n = noise.noise2D(ex * freq, ey * freq + time * 0.05f * (oct + 1))
                            v += abs(n) * amp  // ridged: abs() creates sharp creases
                            amp *= 0.5f; freq *= 2.2f
                        }
                        // Invert to make ridges bright, valleys dark
                        val ridged = 1f - v * 0.7f
                        // Add radial arc tendrils
                        val arc = abs(sin(segTheta * segments * 2f + r * zoom * 5f + time * rotationSpeed * 2f))
                        ridged * 0.6f + arc.pow(sharpness) * 0.4f
                    }
                    "lace" -> {
                        // Delicate filigree — thin curves from sin products
                        val thetaN = segTheta * segments
                        var v = 0f
                        for (layer in 1..complexity.coerceAtMost(5)) {
                            val rWave = sin(r * zoom * layer * 4f + time * rotationSpeed * 0.3f * layer)
                            val aWave = cos(thetaN * layer * 0.5f + time * 0.15f * layer)
                            val thread = abs(rWave * aWave)
                            // Thin the lines: raise to high power for delicate threads
                            v += (1f - thread).pow(sharpness * 3f) / layer
                        }
                        v * 0.6f
                    }
                    "psychedelic" -> {
                        // Warped spiral bands with colour cycling
                        val spiralAngle = segTheta * segments + r * zoom * 8f
                        val warp = noise.noise2D(sx * 2f + offsetX + time * 0.15f, sy * 2f + offsetY + time * 0.12f)
                        val spiral = sin(spiralAngle + warp * complexity * 3f - time * rotationSpeed * 2f)
                        val bands = sin(r * zoom * complexity * 4f + spiral * 2f + time * rotationSpeed)
                        val morph = noise.noise2D(sx * 0.5f + time * 0.05f, sy * 0.5f + offsetY) * 0.4f
                        spiral * 0.4f + bands * 0.4f + morph * 0.2f
                    }
                    else -> noise.noise2D(sx + time * 0.1f, sy + time * 0.08f)
                }

                // Add fine-grain detail overlay
                if (detail > 1) {
                    val detailFreq = detail * 4f
                    val detailAmp = 0.05f * detail
                    val d = noise.noise2D(
                        sx * detailFreq + offsetX + 200f + time * 0.1f,
                        sy * detailFreq + offsetY + 200f + time * 0.08f
                    )
                    value += d * detailAmp
                }

                // Apply sharpness via tanh steepening
                value = tanh(value * sharpness) / tanh(sharpness)

                // Sharpen via LUT: map value from [-1,1] to LUT index [0,255]
                val lutIdx = ((value * 0.5f + 0.5f).coerceIn(0f, 1f) * 255f).toInt()
                val norm = sharpenLut[lutIdx]

                // Color mode
                val palVal = when (colorMode) {
                    "depth" -> ((norm + r * 0.4f) % 1f + 1f) % 1f
                    "iridescent" -> {
                        val angleShift = (thetaFolded / twoPi) * 0.3f
                        ((norm + angleShift + iriTimeShift) % 1f + 1f) % 1f
                    }
                    else -> norm
                }

                // Palette LUT + vignette
                val baseColor = paletteLut[(palVal * 255f).toInt().coerceIn(0, 255)]
                val red = (Color.red(baseColor) * vignette).toInt().coerceIn(0, 255)
                val green = (Color.green(baseColor) * vignette).toInt().coerceIn(0, 255)
                val blue = (Color.blue(baseColor) * vignette).toInt().coerceIn(0, 255)

                polarBuffer[ri * angSteps + ai] = Color.rgb(red, green, blue)
            }
        }

        // --- Screen fill: bilinear interpolation from polar buffer ---
        val pixels = IntArray(w * h)
        val invDim2 = 2f / dim
        val rScale = (radSteps - 1) / maxR
        val aScale = angSteps / twoPi

        for (py in 0 until h) {
            val rawDy = (py - cy) * invDim2
            val rawDy2 = rawDy * rawDy
            val rowOff = py * w
            for (px in 0 until w) {
                val rawDx = (px - cx) * invDim2
                val rf = sqrt(rawDx * rawDx + rawDy2) * rScale
                val ri0 = rf.toInt()
                if (ri0 >= radSteps - 1) {
                    pixels[rowOff + px] = Color.BLACK
                    continue
                }
                val theta = atan2(rawDy, rawDx)
                val af = ((theta + PI.toFloat()) * aScale)
                val ai0 = af.toInt() % angSteps
                val ai1 = (ai0 + 1) % angSteps
                val ri1 = ri0 + 1

                val fr = rf - ri0
                val fa = af - af.toInt()

                // Bilinear interpolation of 4 surrounding polar buffer cells
                val c00 = polarBuffer[ri0 * angSteps + ai0]
                val c10 = polarBuffer[ri1 * angSteps + ai0]
                val c01 = polarBuffer[ri0 * angSteps + ai1]
                val c11 = polarBuffer[ri1 * angSteps + ai1]

                val invFr = 1f - fr
                val invFa = 1f - fa
                val w00 = invFr * invFa
                val w10 = fr * invFa
                val w01 = invFr * fa
                val w11 = fr * fa

                val red = (Color.red(c00) * w00 + Color.red(c10) * w10 +
                        Color.red(c01) * w01 + Color.red(c11) * w11).toInt().coerceIn(0, 255)
                val green = (Color.green(c00) * w00 + Color.green(c10) * w10 +
                        Color.green(c01) * w01 + Color.green(c11) * w11).toInt().coerceIn(0, 255)
                val blue = (Color.blue(c00) * w00 + Color.blue(c10) * w10 +
                        Color.blue(c01) * w01 + Color.blue(c11) * w11).toInt().coerceIn(0, 255)

                pixels[rowOff + px] = Color.rgb(red, green, blue)
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
