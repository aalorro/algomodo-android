package com.artmondo.algomodo.generators.fractals

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

class IfsBarnsleyGenerator : Generator {

    override val id = "fractal-ifs-barnsley"
    override val family = "fractals"
    override val styleName = "IFS / Barnsley Fern"
    override val definition =
        "Iterated Function Systems — chaos game with affine transforms; lean, curl, and spread morph the fractal shape; rotational symmetry and log-density histogram rendering."
    override val algorithmNotes =
        "Chaos game with affine transforms modified by three shape parameters: lean adds global shear, " +
        "curl rotates each transform's matrix for spiraling distortion, spread scales translations. " +
        "Points are accumulated into a density histogram with 1-8 fold rotational symmetry. " +
        "Three color modes: flame (running transform-index blend), height (y-position gradient), " +
        "density (log-density mapped to palette). All modes use log-density tone mapping with gamma correction."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema: List<Parameter> = listOf(
        Parameter.SelectParam(
            name = "Preset",
            key = "preset",
            group = ParamGroup.COMPOSITION,
            help = "Classic IFS fractals or seed-based random systems",
            options = listOf("barnsley", "sierpinski", "maple", "dragon", "tree", "spiral", "crystal", "koch", "random"),
            default = "barnsley"
        ),
        Parameter.NumberParam(
            name = "Iterations",
            key = "iterations",
            group = ParamGroup.COMPOSITION,
            help = "More iterations = denser, more detailed image",
            min = 50000f, max = 500000f, step = 50000f, default = 200000f
        ),
        Parameter.NumberParam(
            name = "Lean",
            key = "lean",
            group = ParamGroup.GEOMETRY,
            help = "Shear the fractal sideways — negative leans left, positive leans right",
            min = -0.5f, max = 0.5f, step = 0.02f, default = 0f
        ),
        Parameter.NumberParam(
            name = "Curl",
            key = "curl",
            group = ParamGroup.GEOMETRY,
            help = "Rotational curl applied to each transform — creates spiraling and twisting distortion",
            min = -1f, max = 1f, step = 0.05f, default = 0f
        ),
        Parameter.NumberParam(
            name = "Spread",
            key = "spread",
            group = ParamGroup.GEOMETRY,
            help = "Scale the translation offsets — wider spread opens the fractal structure up",
            min = 0.5f, max = 2.0f, step = 0.05f, default = 1.0f
        ),
        Parameter.NumberParam(
            name = "Symmetry",
            key = "symmetry",
            group = ParamGroup.GEOMETRY,
            help = "Rotational symmetry order — 1 = none, 2-8 = kaleidoscopic fold",
            min = 1f, max = 8f, step = 1f, default = 1f
        ),
        Parameter.SelectParam(
            name = "Color Mode",
            key = "colorMode",
            group = ParamGroup.COLOR,
            help = "flame: color by transform blending | height: by y-position | density: by point density",
            options = listOf("flame", "height", "density"),
            default = "flame"
        ),
        Parameter.NumberParam(
            name = "Gamma",
            key = "gamma",
            group = ParamGroup.COLOR,
            help = "Tone mapping gamma — higher values reveal more subtle structure",
            min = 1f, max = 5f, step = 0.1f, default = 2.5f
        ),
        Parameter.NumberParam(
            name = "Brightness",
            key = "brightness",
            group = ParamGroup.COLOR,
            help = "Overall brightness multiplier applied during tone mapping",
            min = 0.5f, max = 3f, step = 0.1f, default = 1.5f
        ),
        Parameter.NumberParam(
            name = "Speed",
            key = "speed",
            group = ParamGroup.FLOW_MOTION,
            help = "Animation morphing speed — lean and curl oscillate over time",
            min = 0.1f, max = 3.0f, step = 0.1f, default = 0.5f
        )
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "preset" to "barnsley",
        "iterations" to 200000f,
        "lean" to 0f,
        "curl" to 0f,
        "spread" to 1.0f,
        "symmetry" to 1f,
        "colorMode" to "flame",
        "gamma" to 2.5f,
        "brightness" to 1.5f,
        "speed" to 0.5f
    )

    // ── Preset transform definitions ─────────────────────────────────────────

    private fun getPresetTransforms(preset: String, rng: SeededRNG): Array<FloatArray> {
        // Each transform: [a, b, c, d, e, f, probability]
        return when (preset) {
            "barnsley" -> arrayOf(
                floatArrayOf(0f, 0f, 0f, 0.16f, 0f, 0f, 0.01f),
                floatArrayOf(0.85f, 0.04f, -0.04f, 0.85f, 0f, 1.6f, 0.85f),
                floatArrayOf(0.2f, -0.26f, 0.23f, 0.22f, 0f, 1.6f, 0.07f),
                floatArrayOf(-0.15f, 0.28f, 0.26f, 0.24f, 0f, 0.44f, 0.07f)
            )
            "sierpinski" -> arrayOf(
                floatArrayOf(0.5f, 0f, 0f, 0.5f, 0f, 0f, 0.333f),
                floatArrayOf(0.5f, 0f, 0f, 0.5f, 0.5f, 0f, 0.333f),
                floatArrayOf(0.5f, 0f, 0f, 0.5f, 0.25f, 0.433f, 0.334f)
            )
            "maple" -> arrayOf(
                floatArrayOf(0.14f, 0.01f, 0f, 0.51f, -0.08f, -1.31f, 0.10f),
                floatArrayOf(0.43f, 0.52f, -0.45f, 0.50f, 1.49f, -0.75f, 0.35f),
                floatArrayOf(0.45f, -0.49f, 0.47f, 0.47f, -1.62f, -0.74f, 0.35f),
                floatArrayOf(0.49f, 0f, 0f, 0.51f, 0.02f, 1.62f, 0.20f)
            )
            "dragon" -> arrayOf(
                floatArrayOf(0.824074f, 0.281482f, -0.212346f, 0.864198f, -1.88229f, -0.110607f, 0.787473f),
                floatArrayOf(0.088272f, 0.520988f, -0.463889f, -0.377778f, 0.78536f, 8.095795f, 0.212527f)
            )
            "tree" -> arrayOf(
                floatArrayOf(0f, 0f, 0f, 0.5f, 0f, 0f, 0.05f),
                floatArrayOf(0.42f, -0.42f, 0.42f, 0.42f, 0f, 0.2f, 0.40f),
                floatArrayOf(0.42f, 0.42f, -0.42f, 0.42f, 0f, 0.2f, 0.40f),
                floatArrayOf(0.1f, 0f, 0f, 0.1f, 0f, 0.2f, 0.15f)
            )
            "spiral" -> arrayOf(
                floatArrayOf(0.7879f, -0.4242f, 0.2424f, 0.859f, 1.758f, 1.408f, 0.90f),
                floatArrayOf(-0.1212f, 0.2576f, 0.1515f, 0.053f, -6.7214f, 1.3772f, 0.10f)
            )
            "crystal" -> arrayOf(
                floatArrayOf(0.382f, 0f, 0f, 0.382f, 0.3072f, 0.619f, 0.25f),
                floatArrayOf(0.382f, 0f, 0f, 0.382f, 0.6033f, 0.4044f, 0.25f),
                floatArrayOf(0.382f, 0f, 0f, 0.382f, 0.0139f, 0.4044f, 0.25f),
                floatArrayOf(0.382f, 0f, 0f, 0.382f, 0.1253f, 0.0595f, 0.25f)
            )
            "koch" -> arrayOf(
                floatArrayOf(0.333f, 0f, 0f, 0.333f, 0f, 0f, 0.25f),
                floatArrayOf(0.167f, -0.289f, 0.289f, 0.167f, 0.333f, 0f, 0.25f),
                floatArrayOf(0.167f, 0.289f, -0.289f, 0.167f, 0.5f, 0.289f, 0.25f),
                floatArrayOf(0.333f, 0f, 0f, 0.333f, 0.667f, 0f, 0.25f)
            )
            "random" -> generateRandomIFS(rng)
            else -> getPresetTransforms("barnsley", rng)
        }
    }

    private fun generateRandomIFS(rng: SeededRNG): Array<FloatArray> {
        val template = rng.integer(0, 3)
        return when (template) {
            0 -> {
                // Spiral type
                val n = rng.integer(2, 3)
                val transforms = Array(n) { FloatArray(7) }
                var totalP = 0f
                for (i in 0 until n) {
                    val s = rng.range(0.6f, 0.9f)
                    val angle = rng.range(0.3f, 2.0f) * if (rng.random() > 0.5f) 1f else -1f
                    val ca = cos(angle) * s; val sa = sin(angle) * s
                    val p = rng.range(0.3f, 1.0f)
                    transforms[i] = floatArrayOf(ca, -sa, sa, ca, rng.range(-2f, 2f), rng.range(-1f, 3f), p)
                    totalP += p
                }
                for (t in transforms) t[6] /= totalP
                transforms
            }
            1 -> {
                // Symmetric type
                val s = rng.range(0.4f, 0.7f)
                val angle = rng.range(0.2f, 1.5f)
                val ca = cos(angle) * s; val sa = sin(angle) * s
                val tx = rng.range(0f, 2f); val ty = rng.range(0f, 2f)
                arrayOf(
                    floatArrayOf(ca, -sa, sa, ca, tx, ty, 0.5f),
                    floatArrayOf(ca, sa, -sa, ca, -tx, ty, 0.5f)
                )
            }
            2 -> {
                // Fern-like
                val transforms = mutableListOf<FloatArray>()
                transforms.add(floatArrayOf(0f, 0f, 0f, rng.range(0.1f, 0.25f), 0f, 0f, 0.02f))
                val s = rng.range(0.75f, 0.9f)
                val skew = rng.range(0.01f, 0.08f)
                transforms.add(floatArrayOf(s, skew, -skew, s, 0f, rng.range(1f, 2.5f), 0.80f))
                for (i in 0..1) {
                    val sign = if (i == 0) 1f else -1f
                    val a = rng.range(0.15f, 0.35f)
                    transforms.add(floatArrayOf(a, sign * rng.range(-0.4f, -0.15f), sign * rng.range(0.15f, 0.4f), a, 0f, rng.range(0.5f, 2f), 0.09f))
                }
                var totalP = 0f
                for (t in transforms) totalP += t[6]
                for (t in transforms) t[6] /= totalP
                transforms.toTypedArray()
            }
            else -> {
                // Tiling type
                val n = rng.integer(3, 5)
                val s = 1f / sqrt(n.toFloat())
                val transforms = Array(n) { FloatArray(7) }
                for (i in 0 until n) {
                    val angle = rng.range(-0.5f, 0.5f)
                    val ca = cos(angle) * s; val sa = sin(angle) * s
                    transforms[i] = floatArrayOf(ca, -sa, sa, ca, rng.range(-1.5f, 1.5f), rng.range(-0.5f, 2.5f), 1f / n)
                }
                transforms
            }
        }
    }

    // ── Apply lean, curl, spread to transforms ───────────────────────────────

    private fun modifyTransforms(transforms: Array<FloatArray>, lean: Float, curl: Float, spread: Float) {
        val curlRad = curl * PI.toFloat() * 0.3f
        val ct = cos(curlRad); val st = sin(curlRad)

        for (t in transforms) {
            // Curl: rotate 2x2 matrix M' = R(θ) · M
            val a = t[0]; val b = t[1]; val c = t[2]; val d = t[3]
            t[0] = a * ct - c * st
            t[1] = b * ct - d * st
            t[2] = a * st + c * ct
            t[3] = b * st + d * ct

            // Lean: shear (x' gets y-dependent offset)
            t[1] += lean * 0.4f

            // Spread: scale translation offsets
            t[4] *= spread
            t[5] *= spread
        }
    }

    // ── Render ────────────────────────────────────────────────────────────────

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
        if (w == 0 || h == 0) return

        val preset = (params["preset"] as? String) ?: "barnsley"
        val colorMode = (params["colorMode"] as? String) ?: "flame"
        val gamma = (params["gamma"] as? Number)?.toFloat() ?: 2.5f
        val brightness = (params["brightness"] as? Number)?.toFloat() ?: 1.5f
        val speed = (params["speed"] as? Number)?.toFloat() ?: 0.5f
        val symmetry = (params["symmetry"] as? Number)?.toInt()?.coerceIn(1, 8) ?: 1
        var lean = (params["lean"] as? Number)?.toFloat() ?: 0f
        var curl = (params["curl"] as? Number)?.toFloat() ?: 0f
        var spread = (params["spread"] as? Number)?.toFloat() ?: 1.0f

        var totalIter = (params["iterations"] as? Number)?.toInt() ?: 200000
        totalIter = when (quality) {
            Quality.DRAFT -> maxOf(20000, totalIter / 4)
            Quality.BALANCED -> totalIter
            Quality.ULTRA -> minOf(800000, totalIter * 2)
        }

        val rng = SeededRNG(seed)

        // ── Generate transforms ──────────────────────────────────────────
        val baseTransforms = getPresetTransforms(preset, rng)
        // Deep copy so modifiers don't mutate preset
        val transforms = Array(baseTransforms.size) { baseTransforms[it].copyOf() }

        // ── Animation: oscillate lean and curl ───────────────────────────
        if (time > 0f) {
            val t = time * speed
            lean += sin(t * 0.31f) * 0.08f + sin(t * 0.53f) * 0.04f
            curl += sin(t * 0.23f) * 0.15f + cos(t * 0.41f) * 0.06f
            spread += sin(t * 0.19f) * 0.08f
        }

        // ── Apply shape modifiers ────────────────────────────────────────
        modifyTransforms(transforms, lean, curl, spread)

        // ── Cumulative probability ───────────────────────────────────────
        val nT = transforms.size
        val cumProb = FloatArray(nT)
        var cp = 0f
        for (i in 0 until nT) { cp += transforms[i][6]; cumProb[i] = cp }

        // Per-transform flame color index LUT
        val tcLut = FloatArray(nT) { i -> if (nT > 1) i.toFloat() / (nT - 1) else 0.5f }

        // ── Symmetry pre-computation ─────────────────────────────────────
        val sym1 = symmetry == 1
        val symCos = FloatArray(symmetry)
        val symSin = FloatArray(symmetry)
        for (s in 0 until symmetry) {
            val a = s.toFloat() / symmetry * 2f * PI.toFloat()
            symCos[s] = cos(a); symSin[s] = sin(a)
        }

        // ── Palette LUT (256 entries, separate R/G/B for histogram accumulation) ──
        val palLutR = IntArray(256)
        val palLutG = IntArray(256)
        val palLutB = IntArray(256)
        for (i in 0 until 256) {
            val c = palette.lerpColor(i / 255f)
            palLutR[i] = Color.red(c)
            palLutG[i] = Color.green(c)
            palLutB[i] = Color.blue(c)
        }
        val paletteLut = palette.buildLut(256) // for density mode final render

        val isDensity = colorMode == "density"
        val isColor = !isDensity
        val isHeight = colorMode == "height"

        // ── Histogram buffers ────────────────────────────────────────────
        val npix = w * h
        val hist = IntArray(npix)
        val histR: FloatArray?
        val histG: FloatArray?
        val histB: FloatArray?
        if (isColor) {
            histR = FloatArray(npix)
            histG = FloatArray(npix)
            histB = FloatArray(npix)
        } else {
            histR = null; histG = null; histB = null
        }

        // ── Bounding box pre-pass ────────────────────────────────────────
        val warmup = 50
        var x = 0f; var y = 0f
        var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        val sampleIter = minOf(5000, totalIter)

        for (i in 0 until sampleIter) {
            val rv = rng.random()
            var ti = 0
            for (j in 0 until nT - 1) { if (rv < cumProb[j]) { ti = j; break } else ti = j + 1 }
            val tf = transforms[ti]
            val nx = tf[0] * x + tf[1] * y + tf[4]
            val ny = tf[2] * x + tf[3] * y + tf[5]
            x = nx; y = ny

            if (i >= warmup) {
                if (sym1) {
                    if (x < minX) minX = x; if (x > maxX) maxX = x
                    if (y < minY) minY = y; if (y > maxY) maxY = y
                } else {
                    for (s in 0 until symmetry) {
                        val sx = x * symCos[s] - y * symSin[s]
                        val sy = x * symSin[s] + y * symCos[s]
                        if (sx < minX) minX = sx; if (sx > maxX) maxX = sx
                        if (sy < minY) minY = sy; if (sy > maxY) maxY = sy
                    }
                }
            }
        }

        // Expand bounds with padding
        val rangeX = if (maxX > minX) maxX - minX else 2f
        val rangeY = if (maxY > minY) maxY - minY else 2f
        val padX = rangeX * 0.08f; val padY = rangeY * 0.08f
        minX -= padX; maxX += padX
        minY -= padY; maxY += padY
        val finalRangeX = maxX - minX
        val finalRangeY = maxY - minY
        val sc = minOf(w / finalRangeX, h / finalRangeY)
        val offX = (w - finalRangeX * sc) * 0.5f
        val offY = (h - finalRangeY * sc) * 0.5f
        val invFinalRangeY = 1f / finalRangeY
        val hm1 = h - 1

        // ── Main chaos game ──────────────────────────────────────────────
        x = 0f; y = 0f
        var flameColor = 0.5f

        for (i in 0 until totalIter) {
            val rv = rng.random()
            var ti = 0
            for (j in 0 until nT - 1) { if (rv < cumProb[j]) { ti = j; break } else ti = j + 1 }
            val tf = transforms[ti]
            val nx = tf[0] * x + tf[1] * y + tf[4]
            val ny = tf[2] * x + tf[3] * y + tf[5]
            x = nx; y = ny

            if (i < warmup) continue

            // Reject divergent points
            if (x.isNaN() || y.isNaN() || abs(x) > 1e6f || abs(y) > 1e6f) {
                x = rng.range(-1f, 1f); y = rng.range(-1f, 1f)
                flameColor = 0.5f
                continue
            }

            // Flame color: running blend
            if (isColor && !isHeight) {
                flameColor = (flameColor + tcLut[ti]) * 0.5f
            }

            // ── Point plotting (symmetry=1 fast path) ────────────────────
            if (sym1) {
                val px = ((x - minX) * sc + offX).toInt()
                val fpy = hm1 - ((y - minY) * sc + offY).toInt()
                if (px in 0 until w && fpy in 0 until h) {
                    val idx = fpy * w + px
                    hist[idx]++
                    if (isColor) {
                        val ci: Int = if (isHeight) {
                            ((y - minY) * invFinalRangeY * 255f + 0.5f).toInt().coerceIn(0, 255)
                        } else {
                            (flameColor * 255f + 0.5f).toInt().coerceAtMost(255)
                        }
                        histR!![idx] += palLutR[ci]
                        histG!![idx] += palLutG[ci]
                        histB!![idx] += palLutB[ci]
                    }
                }
            } else {
                for (s in 0 until symmetry) {
                    val sx = x * symCos[s] - y * symSin[s]
                    val sy = x * symSin[s] + y * symCos[s]
                    val px = ((sx - minX) * sc + offX).toInt()
                    val fpy = hm1 - ((sy - minY) * sc + offY).toInt()
                    if (px in 0 until w && fpy in 0 until h) {
                        val idx = fpy * w + px
                        hist[idx]++
                        if (isColor) {
                            val ci: Int = if (isHeight) {
                                ((sy - minY) * invFinalRangeY * 255f + 0.5f).toInt().coerceIn(0, 255)
                            } else {
                                (flameColor * 255f + 0.5f).toInt().coerceAtMost(255)
                            }
                            histR!![idx] += palLutR[ci]
                            histG!![idx] += palLutG[ci]
                            histB!![idx] += palLutB[ci]
                        }
                    }
                }
            }
        }

        // ── Tone mapping LUT ─────────────────────────────────────────────
        var maxDensity = 0
        for (d in hist) { if (d > maxDensity) maxDensity = d }
        if (maxDensity == 0) {
            // Nothing rendered — black canvas
            canvas.drawColor(Color.BLACK)
            return
        }

        val logMaxD = ln(1f + maxDensity)
        val invGamma = 1f / gamma
        val toneLut = FloatArray(maxDensity + 1) { d ->
            val alpha = ln(1f + d.toFloat()) / logMaxD
            minOf(1f, alpha.pow(invGamma) * brightness)
        }

        // ── Render to pixel array ────────────────────────────────────────
        val pixels = IntArray(npix)

        if (isDensity) {
            for (i in 0 until npix) {
                val density = hist[i]
                if (density == 0) continue
                val ca = toneLut[density]
                val ci = (ca * 255f + 0.5f).toInt().coerceIn(0, 255)
                pixels[i] = paletteLut[ci]
            }
        } else {
            for (i in 0 until npix) {
                val density = hist[i]
                if (density == 0) continue
                val invD = toneLut[density] / density
                val r = (histR!![i] * invD).toInt().coerceIn(0, 255)
                val g = (histG!![i] * invD).toInt().coerceIn(0, 255)
                val b = (histB!![i] * invD).toInt().coerceIn(0, 255)
                pixels[i] = Color.rgb(r, g, b)
            }
        }

        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
        canvas.drawBitmap(bitmap, 0f, 0f, null)
    }

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float {
        val iterations = (params["iterations"] as? Number)?.toInt() ?: 200000
        val symmetry = (params["symmetry"] as? Number)?.toInt() ?: 1
        return (iterations * symmetry / 500000f).coerceIn(0.1f, 1f)
    }
}
