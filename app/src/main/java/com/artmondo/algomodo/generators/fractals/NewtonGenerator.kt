package com.artmondo.algomodo.generators.fractals

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import com.artmondo.algomodo.data.palettes.Palette
import com.artmondo.algomodo.generators.Generator
import com.artmondo.algomodo.generators.ParamGroup
import com.artmondo.algomodo.generators.Parameter
import com.artmondo.algomodo.generators.Quality
import kotlin.math.*

class NewtonGenerator : Generator {

    override val id = "fractal-newton"
    override val family = "fractals"
    override val styleName = "Newton Fractal"
    override val definition =
        "Newton's method fractal with multiple styles — classic, sine, polynomial, Halley, and secant variants."
    override val algorithmNotes =
        "Applies root-finding methods to complex functions. Classic: z^n-1 with standard Newton's method. " +
        "Sine: iterates on sin(z) with roots at k\u03c0. " +
        "Polynomial: z^n+z\u00b2-1 with asymmetric root structure. Halley: 3rd-order convergence for smoother basins. " +
        "Secant: finite-difference approximation producing organic boundaries. " +
        "Damping factor alters convergence \u2014 values \u2260 1 produce elaborate boundary patterns."
    override val supportsVector = false
    override val supportsAnimation = true

    companion object {
        private const val STYLE_CLASSIC = "classic"
        private const val STYLE_SINE = "sine"
        private const val STYLE_POLYNOMIAL = "polynomial"
        private const val STYLE_HALLEY = "halley"
        private const val STYLE_SECANT = "secant"

        private const val TOL_SQ = 1e-4
        private const val BAIL_SQ = 1e10
        private const val DENOM_MIN = 1e-20
        private const val INV_PI = 1.0 / PI
        private const val MAX_ROOTS_LUT = 16
    }

    override val parameterSchema: List<Parameter> = listOf(
        Parameter.SelectParam(
            "Style", "style", ParamGroup.COMPOSITION,
            "classic: z^n-1 | sine: sin(z) | polynomial: z^n+z\u00b2-1 | halley: 3rd-order | secant: finite-difference",
            listOf(STYLE_CLASSIC, STYLE_SINE, STYLE_POLYNOMIAL, STYLE_HALLEY, STYLE_SECANT),
            STYLE_CLASSIC
        ),
        Parameter.NumberParam("Power", "power", ParamGroup.COMPOSITION, "Degree of the polynomial (ignored for sine style)", 2f, 6f, 1f, 3f),
        Parameter.NumberParam("Zoom", "zoom", ParamGroup.COMPOSITION, "Zoom level into the fractal", 0.5f, 4f, 0.5f, 1f),
        Parameter.NumberParam("Max Iterations", "maxIterations", ParamGroup.COMPOSITION, "Maximum Newton iterations per pixel", 16f, 64f, 8f, 32f),
        Parameter.NumberParam("Damping", "damping", ParamGroup.GEOMETRY, "Relaxation factor \u2014 1 = standard Newton, other values create nova fractals", 0.5f, 1.5f, 0.05f, 1f),
        Parameter.SelectParam("Color Mode", "colorMode", ParamGroup.COLOR, "root: color by converged root | iteration: shade by speed | blended: both combined", listOf("root", "iteration", "blended"), "blended"),
        Parameter.NumberParam("Speed", "speed", ParamGroup.FLOW_MOTION, "Speed of damping animation", 0.1f, 3.0f, 0.1f, 0.5f)
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "style" to STYLE_CLASSIC,
        "power" to 3f,
        "zoom" to 1f,
        "maxIterations" to 32f,
        "damping" to 1f,
        "colorMode" to "blended",
        "speed" to 0.5f
    )

    /** Numerically discover roots of f(z) = z^n + z^2 - 1 for the polynomial style. */
    private fun discoverRoots(power: Int): Pair<DoubleArray, DoubleArray> {
        val foundR = mutableListOf<Double>()
        val foundI = mutableListOf<Double>()
        val gridN = 16
        for (gy in 0 until gridN) {
            for (gx in 0 until gridN) {
                var zr = (gx.toDouble() / (gridN - 1)) * 4.0 - 2.0
                var zi = (gy.toDouble() / (gridN - 1)) * 4.0 - 2.0
                for (s in 0 until 200) {
                    var pnm1r = 1.0; var pnm1i = 0.0
                    for (k in 0 until power - 1) {
                        val tr = pnm1r * zr - pnm1i * zi
                        pnm1i = pnm1r * zi + pnm1i * zr
                        pnm1r = tr
                    }
                    val pnr = pnm1r * zr - pnm1i * zi
                    val pni = pnm1r * zi + pnm1i * zr
                    val z2r = zr * zr - zi * zi
                    val z2i = 2.0 * zr * zi
                    val fr = pnr + z2r - 1.0
                    val fi = pni + z2i
                    val fpr = power * pnm1r + 2.0 * zr
                    val fpi = power * pnm1i + 2.0 * zi
                    val denom = fpr * fpr + fpi * fpi
                    if (denom < DENOM_MIN) break
                    zr -= (fr * fpr + fi * fpi) / denom
                    zi -= (fi * fpr - fr * fpi) / denom
                    if (zr * zr + zi * zi > BAIL_SQ) break
                }
                if (zr * zr + zi * zi > 100.0) continue
                var isNew = true
                for (k in foundR.indices) {
                    val dr = zr - foundR[k]
                    val di = zi - foundI[k]
                    if (dr * dr + di * di < 1e-6) { isNew = false; break }
                }
                if (isNew) { foundR.add(zr); foundI.add(zi) }
            }
        }
        if (foundR.isEmpty()) { foundR.add(0.0); foundI.add(0.0) }
        return Pair(foundR.toDoubleArray(), foundI.toDoubleArray())
    }

    /** Build a pre-baked color LUT for zero-allocation pixel coloring. */
    private fun buildColorLut(
        colorMode: String, paletteColors: IntArray, maxIter: Int, palette: Palette
    ): IntArray {
        val nColors = paletteColors.size
        val lutStride = maxIter + 1
        return when (colorMode) {
            "root" -> IntArray(nColors) { paletteColors[it] }
            "iteration" -> IntArray(lutStride) { iter ->
                palette.lerpColor((1f - iter.toFloat() / maxIter).coerceIn(0f, 1f))
            }
            else -> IntArray(MAX_ROOTS_LUT * lutStride).also { lut ->
                for (r in 0 until MAX_ROOTS_LUT) {
                    val base = paletteColors[r % nColors]
                    val cr = Color.red(base); val cg = Color.green(base); val cb = Color.blue(base)
                    val off = r * lutStride
                    for (iter in 0..maxIter) {
                        val shade = 0.3f + 0.7f * (1f - iter.toFloat() / maxIter)
                        lut[off + iter] = Color.rgb(
                            (cr * shade).toInt().coerceIn(0, 255),
                            (cg * shade).toInt().coerceIn(0, 255),
                            (cb * shade).toInt().coerceIn(0, 255)
                        )
                    }
                }
            }
        }
    }

    override fun renderCanvas(
        canvas: Canvas, bitmap: Bitmap, params: Map<String, Any>,
        seed: Int, palette: Palette, quality: Quality, time: Float
    ) {
        val w = bitmap.width
        val h = bitmap.height
        val style = (params["style"] as? String) ?: STYLE_CLASSIC
        val power = (params["power"] as? Number)?.toInt() ?: 3
        val zoom = (params["zoom"] as? Number)?.toFloat() ?: 1f
        val maxIter = (params["maxIterations"] as? Number)?.toInt() ?: 32
        val baseDamping = (params["damping"] as? Number)?.toDouble() ?: 1.0
        val colorMode = (params["colorMode"] as? String) ?: "blended"
        val speed = (params["speed"] as? Number)?.toFloat() ?: 0.5f

        val isAnim = time > 0f
        val scaledMaxIter = when {
            isAnim -> maxIter.coerceIn(8, 20)
            quality == Quality.DRAFT -> (maxIter / 2).coerceAtLeast(8)
            quality == Quality.ULTRA -> (maxIter * 1.5f).toInt()
            else -> maxIter
        }

        val damping = baseDamping + sin(time.toDouble() * speed * 0.3) * 0.1
        val dampIsOne = abs(damping - 1.0) < 1e-12

        val baseRange = if (style == STYLE_SINE) PI * 4.0 else 3.0
        val aspect = w.toDouble() / h.toDouble()
        val rangeY = baseRange / zoom
        val rangeX = rangeY * aspect
        val invW = 1.0 / w
        val invH = 1.0 / h

        val rotAngle = time * speed * 0.08
        val cosR = cos(rotAngle)
        val sinR = sin(rotAngle)

        // Precompute roots
        val rootsR: DoubleArray
        val rootsI: DoubleArray
        val numRoots: Int

        when {
            style == STYLE_SINE -> { numRoots = 0; rootsR = DoubleArray(0); rootsI = DoubleArray(0) }
            style == STYLE_POLYNOMIAL -> {
                val (rr, ri) = discoverRoots(power)
                rootsR = rr; rootsI = ri; numRoots = rr.size
            }
            else -> {
                numRoots = power
                rootsR = DoubleArray(power) { k -> cos(2.0 * PI * k / power) }
                rootsI = DoubleArray(power) { k -> sin(2.0 * PI * k / power) }
            }
        }

        val paletteColors = palette.colorInts().toIntArray()
        val nColors = paletteColors.size
        val colorLut = buildColorLut(colorMode, paletteColors, scaledMaxIter, palette)
        val lutStride = scaledMaxIter + 1
        val isRoot = colorMode == "root"
        val isIter = colorMode == "iteration"
        val darkPx = Color.rgb(10, 10, 10)

        val pixels = IntArray(w * h)

        val cores = Runtime.getRuntime().availableProcessors().coerceIn(2, 8)
        val threads = Array(cores) { t ->
            Thread {
                val y0 = t * h / cores
                val y1 = (t + 1) * h / cores

                when (style) {
                    // ===== CLASSIC: z^n - 1 =====
                    STYLE_CLASSIC -> if (power == 3) {
                        for (py in y0 until y1) {
                            val rawYBase = (py * invH - 0.5) * rangeY
                            for (px in 0 until w) {
                                val rawX = (px * invW - 0.5) * rangeX
                                var zr = rawX * cosR - rawYBase * sinR
                                var zi = rawX * sinR + rawYBase * cosR
                                var iter = 0; var rootIndex = -1
                                while (iter < scaledMaxIter) {
                                    val z2r = zr * zr - zi * zi
                                    val z2i = 2.0 * zr * zi
                                    val pnr = z2r * zr - z2i * zi
                                    val pni = z2r * zi + z2i * zr
                                    val fr = pnr - 1.0; val fi = pni
                                    val fpr = 3.0 * z2r; val fpi = 3.0 * z2i
                                    val d = fpr * fpr + fpi * fpi
                                    if (d < DENOM_MIN) break
                                    val nr = fr * fpr + fi * fpi
                                    val ni = fi * fpr - fr * fpi
                                    if (dampIsOne) { zr -= nr / d; zi -= ni / d }
                                    else { zr -= damping * nr / d; zi -= damping * ni / d }
                                    if (zr * zr + zi * zi > BAIL_SQ) break
                                    if (iter and 1 == 0) {
                                        for (k in 0 until 3) {
                                            val dr = zr - rootsR[k]; val di = zi - rootsI[k]
                                            if (dr * dr + di * di < TOL_SQ) { rootIndex = k; break }
                                        }
                                        if (rootIndex >= 0) break
                                    }
                                    iter++
                                }
                                pixels[py * w + px] = if (rootIndex < 0) darkPx
                                    else if (isRoot) colorLut[rootIndex % nColors]
                                    else if (isIter) colorLut[iter.coerceAtMost(scaledMaxIter)]
                                    else colorLut[(rootIndex % MAX_ROOTS_LUT) * lutStride + iter.coerceAtMost(scaledMaxIter)]
                            }
                        }
                    } else {
                        for (py in y0 until y1) {
                            val rawYBase = (py * invH - 0.5) * rangeY
                            for (px in 0 until w) {
                                val rawX = (px * invW - 0.5) * rangeX
                                var zr = rawX * cosR - rawYBase * sinR
                                var zi = rawX * sinR + rawYBase * cosR
                                var iter = 0; var rootIndex = -1
                                while (iter < scaledMaxIter) {
                                    var pr = 1.0; var pi = 0.0
                                    for (k in 0 until power - 1) {
                                        val nr = pr * zr - pi * zi; val ni = pr * zi + pi * zr
                                        pr = nr; pi = ni
                                    }
                                    val znr = pr * zr - pi * zi; val zni = pr * zi + pi * zr
                                    val fr = znr - 1.0; val fi = zni
                                    val fpr = power * pr; val fpi = power * pi
                                    val d = fpr * fpr + fpi * fpi
                                    if (d < DENOM_MIN) break
                                    val qr = (fr * fpr + fi * fpi) / d
                                    val qi = (fi * fpr - fr * fpi) / d
                                    if (dampIsOne) { zr -= qr; zi -= qi }
                                    else { zr -= damping * qr; zi -= damping * qi }
                                    if (zr * zr + zi * zi > BAIL_SQ) break
                                    if (iter and 1 == 0) {
                                        for (ri in 0 until numRoots) {
                                            val dr = zr - rootsR[ri]; val di = zi - rootsI[ri]
                                            if (dr * dr + di * di < TOL_SQ) { rootIndex = ri; break }
                                        }
                                        if (rootIndex >= 0) break
                                    }
                                    iter++
                                }
                                pixels[py * w + px] = if (rootIndex < 0) darkPx
                                    else if (isRoot) colorLut[rootIndex % nColors]
                                    else if (isIter) colorLut[iter.coerceAtMost(scaledMaxIter)]
                                    else colorLut[(rootIndex % MAX_ROOTS_LUT) * lutStride + iter.coerceAtMost(scaledMaxIter)]
                            }
                        }
                    }

                    // ===== SINE: f(z) = sin(z) =====
                    STYLE_SINE -> for (py in y0 until y1) {
                        val rawYBase = (py * invH - 0.5) * rangeY
                        for (px in 0 until w) {
                            val rawX = (px * invW - 0.5) * rangeX
                            var zr = rawX * cosR - rawYBase * sinR
                            var zi = rawX * sinR + rawYBase * cosR
                            var iter = 0; var rootIndex = -1
                            while (iter < scaledMaxIter) {
                                val sinA = sin(zr); val cosA = cos(zr)
                                val expB = exp(zi); val expNB = 1.0 / expB
                                val sinhB = (expB - expNB) * 0.5
                                val coshB = (expB + expNB) * 0.5
                                val fr = sinA * coshB; val fi = cosA * sinhB
                                val fpr = cosA * coshB; val fpi = -sinA * sinhB
                                val d = fpr * fpr + fpi * fpi
                                if (d < DENOM_MIN) break
                                val nr = fr * fpr + fi * fpi
                                val ni = fi * fpr - fr * fpi
                                if (dampIsOne) { zr -= nr / d; zi -= ni / d }
                                else { zr -= damping * nr / d; zi -= damping * ni / d }
                                val zMag = zr * zr + zi * zi
                                if (zMag > BAIL_SQ || !zMag.isFinite()) break
                                if (iter and 1 == 0) {
                                    val nearK = (zr * INV_PI).roundToInt()
                                    val dr = zr - nearK * PI
                                    if (dr * dr + zi * zi < TOL_SQ) {
                                        rootIndex = ((nearK % MAX_ROOTS_LUT) + MAX_ROOTS_LUT) % MAX_ROOTS_LUT
                                        break
                                    }
                                }
                                iter++
                            }
                            pixels[py * w + px] = if (rootIndex < 0) darkPx
                                else if (isRoot) colorLut[rootIndex % nColors]
                                else if (isIter) colorLut[iter.coerceAtMost(scaledMaxIter)]
                                else colorLut[(rootIndex % MAX_ROOTS_LUT) * lutStride + iter.coerceAtMost(scaledMaxIter)]
                        }
                    }

                    // ===== POLYNOMIAL: f(z) = z^n + z^2 - 1 =====
                    STYLE_POLYNOMIAL -> if (power == 3) {
                        for (py in y0 until y1) {
                            val rawYBase = (py * invH - 0.5) * rangeY
                            for (px in 0 until w) {
                                val rawX = (px * invW - 0.5) * rangeX
                                var zr = rawX * cosR - rawYBase * sinR
                                var zi = rawX * sinR + rawYBase * cosR
                                var iter = 0; var rootIndex = -1
                                while (iter < scaledMaxIter) {
                                    val z2r = zr * zr - zi * zi
                                    val z2i = 2.0 * zr * zi
                                    val pnr = z2r * zr - z2i * zi
                                    val pni = z2r * zi + z2i * zr
                                    val fr = pnr + z2r - 1.0; val fi = pni + z2i
                                    val fpr = 3.0 * (zr * zr - zi * zi) + 2.0 * zr
                                    val fpi = 6.0 * zr * zi + 2.0 * zi
                                    val d = fpr * fpr + fpi * fpi
                                    if (d < DENOM_MIN) break
                                    val nr = fr * fpr + fi * fpi
                                    val ni = fi * fpr - fr * fpi
                                    if (dampIsOne) { zr -= nr / d; zi -= ni / d }
                                    else { zr -= damping * nr / d; zi -= damping * ni / d }
                                    if (zr * zr + zi * zi > BAIL_SQ) break
                                    if (iter and 1 == 0) {
                                        for (k in 0 until numRoots) {
                                            val dr = zr - rootsR[k]; val di = zi - rootsI[k]
                                            if (dr * dr + di * di < TOL_SQ) { rootIndex = k; break }
                                        }
                                        if (rootIndex >= 0) break
                                    }
                                    iter++
                                }
                                pixels[py * w + px] = if (rootIndex < 0) darkPx
                                    else if (isRoot) colorLut[rootIndex % nColors]
                                    else if (isIter) colorLut[iter.coerceAtMost(scaledMaxIter)]
                                    else colorLut[(rootIndex % MAX_ROOTS_LUT) * lutStride + iter.coerceAtMost(scaledMaxIter)]
                            }
                        }
                    } else {
                        for (py in y0 until y1) {
                            val rawYBase = (py * invH - 0.5) * rangeY
                            for (px in 0 until w) {
                                val rawX = (px * invW - 0.5) * rangeX
                                var zr = rawX * cosR - rawYBase * sinR
                                var zi = rawX * sinR + rawYBase * cosR
                                var iter = 0; var rootIndex = -1
                                while (iter < scaledMaxIter) {
                                    var pnm1r = 1.0; var pnm1i = 0.0
                                    for (k in 0 until power - 1) {
                                        val tr = pnm1r * zr - pnm1i * zi
                                        pnm1i = pnm1r * zi + pnm1i * zr
                                        pnm1r = tr
                                    }
                                    val pnr = pnm1r * zr - pnm1i * zi
                                    val pni = pnm1r * zi + pnm1i * zr
                                    val z2r = zr * zr - zi * zi
                                    val z2i = 2.0 * zr * zi
                                    val fr = pnr + z2r - 1.0; val fi = pni + z2i
                                    val fpr = power * pnm1r + 2.0 * zr
                                    val fpi = power * pnm1i + 2.0 * zi
                                    val d = fpr * fpr + fpi * fpi
                                    if (d < DENOM_MIN) break
                                    val nr = fr * fpr + fi * fpi
                                    val ni = fi * fpr - fr * fpi
                                    if (dampIsOne) { zr -= nr / d; zi -= ni / d }
                                    else { zr -= damping * nr / d; zi -= damping * ni / d }
                                    if (zr * zr + zi * zi > BAIL_SQ) break
                                    if (iter and 1 == 0) {
                                        for (k in 0 until numRoots) {
                                            val dr = zr - rootsR[k]; val di = zi - rootsI[k]
                                            if (dr * dr + di * di < TOL_SQ) { rootIndex = k; break }
                                        }
                                        if (rootIndex >= 0) break
                                    }
                                    iter++
                                }
                                pixels[py * w + px] = if (rootIndex < 0) darkPx
                                    else if (isRoot) colorLut[rootIndex % nColors]
                                    else if (isIter) colorLut[iter.coerceAtMost(scaledMaxIter)]
                                    else colorLut[(rootIndex % MAX_ROOTS_LUT) * lutStride + iter.coerceAtMost(scaledMaxIter)]
                            }
                        }
                    }

                    // ===== HALLEY: 3rd-order method on z^n - 1 =====
                    STYLE_HALLEY -> {
                        val pp = power; val ppm1 = power * (power - 1)
                        for (py in y0 until y1) {
                            val rawYBase = (py * invH - 0.5) * rangeY
                            for (px in 0 until w) {
                                val rawX = (px * invW - 0.5) * rangeX
                                var zr = rawX * cosR - rawYBase * sinR
                                var zi = rawX * sinR + rawYBase * cosR
                                var iter = 0; var rootIndex = -1
                                while (iter < scaledMaxIter) {
                                    var pnm2r = 1.0; var pnm2i = 0.0
                                    for (k in 0 until pp - 2) {
                                        val tr = pnm2r * zr - pnm2i * zi
                                        pnm2i = pnm2r * zi + pnm2i * zr
                                        pnm2r = tr
                                    }
                                    val pnm1r = pnm2r * zr - pnm2i * zi
                                    val pnm1i = pnm2r * zi + pnm2i * zr
                                    val pnr = pnm1r * zr - pnm1i * zi
                                    val pni = pnm1r * zi + pnm1i * zr
                                    val fr = pnr - 1.0; val fi = pni
                                    val fpr = pp * pnm1r; val fpi = pp * pnm1i
                                    val fppr = ppm1 * pnm2r; val fppi = ppm1 * pnm2i
                                    val numR = 2.0 * (fr * fpr - fi * fpi)
                                    val numI = 2.0 * (fr * fpi + fi * fpr)
                                    val fp2r = fpr * fpr - fpi * fpi
                                    val fp2i = 2.0 * fpr * fpi
                                    val denR = 2.0 * fp2r - (fr * fppr - fi * fppi)
                                    val denI = 2.0 * fp2i - (fr * fppi + fi * fppr)
                                    val d = denR * denR + denI * denI
                                    if (d < DENOM_MIN) break
                                    val qr = (numR * denR + numI * denI) / d
                                    val qi = (numI * denR - numR * denI) / d
                                    if (dampIsOne) { zr -= qr; zi -= qi }
                                    else { zr -= damping * qr; zi -= damping * qi }
                                    if (zr * zr + zi * zi > BAIL_SQ) break
                                    if (iter and 1 == 0) {
                                        for (k in 0 until numRoots) {
                                            val dr = zr - rootsR[k]; val di = zi - rootsI[k]
                                            if (dr * dr + di * di < TOL_SQ) { rootIndex = k; break }
                                        }
                                        if (rootIndex >= 0) break
                                    }
                                    iter++
                                }
                                pixels[py * w + px] = if (rootIndex < 0) darkPx
                                    else if (isRoot) colorLut[rootIndex % nColors]
                                    else if (isIter) colorLut[iter.coerceAtMost(scaledMaxIter)]
                                    else colorLut[(rootIndex % MAX_ROOTS_LUT) * lutStride + iter.coerceAtMost(scaledMaxIter)]
                            }
                        }
                    }

                    // ===== SECANT: finite-difference on z^n - 1 =====
                    STYLE_SECANT -> {
                        val offset = 0.01 / zoom
                        for (py in y0 until y1) {
                            val rawYBase = (py * invH - 0.5) * rangeY
                            for (px in 0 until w) {
                                val rawX = (px * invW - 0.5) * rangeX
                                var zr = rawX * cosR - rawYBase * sinR
                                var zi = rawX * sinR + rawYBase * cosR
                                var prevZr = zr + offset; var prevZi = zi + offset
                                // f(prevZ) = prevZ^n - 1
                                var ppnr = 1.0; var ppni = 0.0
                                for (k in 0 until power) {
                                    val tr = ppnr * prevZr - ppni * prevZi
                                    ppni = ppnr * prevZi + ppni * prevZr
                                    ppnr = tr
                                }
                                var prevFr = ppnr - 1.0; var prevFi = ppni
                                var iter = 0; var rootIndex = -1
                                while (iter < scaledMaxIter) {
                                    var pnr: Double; var pni: Double
                                    if (power == 3) {
                                        val z2r = zr * zr - zi * zi; val z2i = 2.0 * zr * zi
                                        pnr = z2r * zr - z2i * zi; pni = z2r * zi + z2i * zr
                                    } else {
                                        pnr = 1.0; pni = 0.0
                                        for (k in 0 until power) {
                                            val tr = pnr * zr - pni * zi
                                            pni = pnr * zi + pni * zr
                                            pnr = tr
                                        }
                                    }
                                    val fr = pnr - 1.0; val fi = pni
                                    val dfr = fr - prevFr; val dfi = fi - prevFi
                                    val dfD = dfr * dfr + dfi * dfi
                                    if (dfD < DENOM_MIN) break
                                    val dzr = zr - prevZr; val dzi = zi - prevZi
                                    val nr = fr * dzr - fi * dzi; val ni = fr * dzi + fi * dzr
                                    val qr = (nr * dfr + ni * dfi) / dfD
                                    val qi = (ni * dfr - nr * dfi) / dfD
                                    prevZr = zr; prevZi = zi; prevFr = fr; prevFi = fi
                                    if (dampIsOne) { zr -= qr; zi -= qi }
                                    else { zr -= damping * qr; zi -= damping * qi }
                                    if (zr * zr + zi * zi > BAIL_SQ) break
                                    if (iter and 1 == 0) {
                                        for (k in 0 until numRoots) {
                                            val dr = zr - rootsR[k]; val di = zi - rootsI[k]
                                            if (dr * dr + di * di < TOL_SQ) { rootIndex = k; break }
                                        }
                                        if (rootIndex >= 0) break
                                    }
                                    iter++
                                }
                                pixels[py * w + px] = if (rootIndex < 0) darkPx
                                    else if (isRoot) colorLut[rootIndex % nColors]
                                    else if (isIter) colorLut[iter.coerceAtMost(scaledMaxIter)]
                                    else colorLut[(rootIndex % MAX_ROOTS_LUT) * lutStride + iter.coerceAtMost(scaledMaxIter)]
                            }
                        }
                    }

                    // Fallback to classic
                    else -> {
                        for (py in y0 until y1) {
                            val rawYBase = (py * invH - 0.5) * rangeY
                            for (px in 0 until w) {
                                val rawX = (px * invW - 0.5) * rangeX
                                var zr = rawX * cosR - rawYBase * sinR
                                var zi = rawX * sinR + rawYBase * cosR
                                var iter = 0; var rootIndex = -1
                                while (iter < scaledMaxIter) {
                                    var pr = 1.0; var pi = 0.0
                                    for (k in 0 until power - 1) {
                                        val nr = pr * zr - pi * zi; val ni = pr * zi + pi * zr
                                        pr = nr; pi = ni
                                    }
                                    val znr = pr * zr - pi * zi; val zni = pr * zi + pi * zr
                                    val fr = znr - 1.0; val fi = zni
                                    val fpr = power * pr; val fpi = power * pi
                                    val d = fpr * fpr + fpi * fpi
                                    if (d < DENOM_MIN) break
                                    val qr = (fr * fpr + fi * fpi) / d
                                    val qi = (fi * fpr - fr * fpi) / d
                                    if (dampIsOne) { zr -= qr; zi -= qi }
                                    else { zr -= damping * qr; zi -= damping * qi }
                                    if (zr * zr + zi * zi > BAIL_SQ) break
                                    if (iter and 1 == 0) {
                                        for (ri in 0 until numRoots) {
                                            val dr = zr - rootsR[ri]; val di = zi - rootsI[ri]
                                            if (dr * dr + di * di < TOL_SQ) { rootIndex = ri; break }
                                        }
                                        if (rootIndex >= 0) break
                                    }
                                    iter++
                                }
                                pixels[py * w + px] = if (rootIndex < 0) darkPx
                                    else if (isRoot) colorLut[rootIndex % nColors]
                                    else if (isIter) colorLut[iter.coerceAtMost(scaledMaxIter)]
                                    else colorLut[(rootIndex % MAX_ROOTS_LUT) * lutStride + iter.coerceAtMost(scaledMaxIter)]
                            }
                        }
                    }
                }
            }.also { it.start() }
        }
        threads.forEach { it.join() }

        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
        canvas.drawBitmap(bitmap, 0f, 0f, null)
    }

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float {
        val maxIter = (params["maxIterations"] as? Number)?.toInt() ?: 32
        val power = (params["power"] as? Number)?.toInt() ?: 3
        val style = (params["style"] as? String) ?: STYLE_CLASSIC
        val factor = when (style) {
            STYLE_HALLEY -> 12f
            STYLE_SINE -> 10f
            STYLE_SECANT -> 10f
            else -> 8f
        }
        return (maxIter * power * factor / 300f).coerceIn(0.2f, 1f)
    }
}
