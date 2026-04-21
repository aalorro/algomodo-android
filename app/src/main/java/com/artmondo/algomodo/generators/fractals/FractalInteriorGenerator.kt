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

class FractalInteriorGenerator : Generator {

    override val id = "fractal-interior"
    override val family = "fractals"
    override val styleName = "Fractal Interior"
    override val definition =
        "Interior coloring of fractal sets — reveals hidden structure inside the Mandelbrot set, Julia sets, and other fractals using orbit traps, period detection, and multiplier analysis."
    override val algorithmNotes =
        "Instead of leaving interior pixels black, this generator colors them using six methods: " +
        "orbit trap (minimum distance to a geometric shape during iteration), period detection (cycle length of converged orbits), " +
        "multiplier magnitude/angle (derivative product over one period cycle), interior distance estimate, and final orbit value. " +
        "Five fractal variants: Mandelbrot, Julia, Newton (z³-1), Tricorn (conjugate), Burning Ship (abs). " +
        "Four exterior modes control escaped-pixel appearance. Animation rotates traps and shifts Julia seed."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema: List<Parameter> = listOf(
        Parameter.SelectParam("Variant", "variant", ParamGroup.COMPOSITION,
            "mandelbrot: z²+c | julia: fixed c | newton: z³-1 root-finding | tricorn: conjugate z | burning-ship: |re|+i|im|",
            listOf("mandelbrot", "julia", "newton", "tricorn", "burning-ship"), "mandelbrot"),
        Parameter.SelectParam("Interior Mode", "interiorMode", ParamGroup.COLOR,
            "trap: orbit trap distance | period: cycle detection | multiplier-mag: derivative magnitude | multiplier-arg: derivative angle | interior-de: distance estimate | final-value: last orbit position",
            listOf("trap", "period", "multiplier-mag", "multiplier-arg", "interior-de", "final-value"), "trap"),
        Parameter.SelectParam("Trap Type", "trapType", ParamGroup.GEOMETRY,
            "point: distance to origin | line: horizontal line | cross: axis cross | circle: ring | polygon: hexagon",
            listOf("point", "line", "cross", "circle", "polygon"), "point"),
        Parameter.SelectParam("Exterior Mode", "exteriorMode", ParamGroup.COLOR,
            "smooth: gradient | banded: discrete steps | off: black exterior | subtle: dim exterior",
            listOf("smooth", "banded", "off", "subtle"), "smooth"),
        Parameter.NumberParam("Max Iterations", "maxIterations", ParamGroup.COMPOSITION, null, 50f, 2000f, 10f, 300f),
        Parameter.NumberParam("Center X", "centerX", ParamGroup.COMPOSITION, null, -2.5f, 1.5f, 0.05f, -0.5f),
        Parameter.NumberParam("Center Y", "centerY", ParamGroup.COMPOSITION, null, -1.5f, 1.5f, 0.05f, 0f),
        Parameter.NumberParam("Zoom", "zoom", ParamGroup.COMPOSITION, null, 0.1f, 50f, 0.1f, 1f),
        Parameter.NumberParam("Trap Center X", "trapCX", ParamGroup.GEOMETRY, null, -2f, 2f, 0.05f, 0f),
        Parameter.NumberParam("Trap Center Y", "trapCY", ParamGroup.GEOMETRY, null, -2f, 2f, 0.05f, 0f),
        Parameter.NumberParam("Julia CX", "juliaCX", ParamGroup.GEOMETRY, "Real part of Julia constant", -2f, 2f, 0.01f, -0.7f),
        Parameter.NumberParam("Julia CY", "juliaCY", ParamGroup.GEOMETRY, "Imaginary part of Julia constant", -2f, 2f, 0.01f, 0.27f),
        Parameter.NumberParam("Trap Radius", "trapRadius", ParamGroup.GEOMETRY, null, 0.01f, 2f, 0.01f, 0.5f)
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "variant" to "mandelbrot",
        "interiorMode" to "trap",
        "trapType" to "point",
        "exteriorMode" to "smooth",
        "maxIterations" to 300f,
        "centerX" to -0.5f,
        "centerY" to 0f,
        "zoom" to 1f,
        "trapCX" to 0f,
        "trapCY" to 0f,
        "juliaCX" to -0.7f,
        "juliaCY" to 0.27f,
        "trapRadius" to 0.5f
    )

    companion object {
        private const val VAR_MANDELBROT = 0
        private const val VAR_JULIA = 1
        private const val VAR_NEWTON = 2
        private const val VAR_TRICORN = 3
        private const val VAR_BURNING_SHIP = 4

        private const val INT_TRAP = 0
        private const val INT_PERIOD = 1
        private const val INT_MULT_MAG = 2
        private const val INT_MULT_ARG = 3
        private const val INT_INTERIOR_DE = 4
        private const val INT_FINAL_VALUE = 5

        private const val TRAP_POINT = 0
        private const val TRAP_LINE = 1
        private const val TRAP_CROSS = 2
        private const val TRAP_CIRCLE = 3
        private const val TRAP_POLYGON = 4

        private const val EXT_SMOOTH = 0
        private const val EXT_BANDED = 1
        private const val EXT_OFF = 2
        private const val EXT_SUBTLE = 3

        private const val RING_SIZE = 64
        private const val RING_MASK = 63

        // Hexagon normals for polygon trap (6-sided)
        private val HEX_NX = DoubleArray(3) { cos(it * PI / 3.0) }
        private val HEX_NY = DoubleArray(3) { sin(it * PI / 3.0) }
    }

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

        val variantStr = (params["variant"] as? String) ?: "mandelbrot"
        val intModeStr = (params["interiorMode"] as? String) ?: "trap"
        val trapTypeStr = (params["trapType"] as? String) ?: "point"
        val extModeStr = (params["exteriorMode"] as? String) ?: "smooth"
        val maxIter = (params["maxIterations"] as? Number)?.toInt() ?: 300
        val centerX = (params["centerX"] as? Number)?.toDouble() ?: -0.5
        val centerY = (params["centerY"] as? Number)?.toDouble() ?: 0.0
        val zoom = (params["zoom"] as? Number)?.toDouble() ?: 1.0
        val baseTrapCX = (params["trapCX"] as? Number)?.toDouble() ?: 0.0
        val baseTrapCY = (params["trapCY"] as? Number)?.toDouble() ?: 0.0
        val baseJuliaCX = (params["juliaCX"] as? Number)?.toDouble() ?: -0.7
        val baseJuliaCY = (params["juliaCY"] as? Number)?.toDouble() ?: 0.27
        val trapRadius = (params["trapRadius"] as? Number)?.toDouble() ?: 0.5

        val variant = when (variantStr) {
            "julia" -> VAR_JULIA; "newton" -> VAR_NEWTON
            "tricorn" -> VAR_TRICORN; "burning-ship" -> VAR_BURNING_SHIP
            else -> VAR_MANDELBROT
        }
        val intMode = when (intModeStr) {
            "period" -> INT_PERIOD; "multiplier-mag" -> INT_MULT_MAG
            "multiplier-arg" -> INT_MULT_ARG; "interior-de" -> INT_INTERIOR_DE
            "final-value" -> INT_FINAL_VALUE; else -> INT_TRAP
        }
        val trapType = when (trapTypeStr) {
            "line" -> TRAP_LINE; "cross" -> TRAP_CROSS
            "circle" -> TRAP_CIRCLE; "polygon" -> TRAP_POLYGON; else -> TRAP_POINT
        }
        val extMode = when (extModeStr) {
            "banded" -> EXT_BANDED; "off" -> EXT_OFF; "subtle" -> EXT_SUBTLE; else -> EXT_SMOOTH
        }

        val scaledMaxIter = when (quality) {
            Quality.DRAFT -> (maxIter / 2).coerceAtLeast(30)
            Quality.BALANCED -> maxIter
            Quality.ULTRA -> (maxIter * 1.5f).toInt()
        }

        // Animation: rotate trap, drift Julia c, color cycle
        val trapAngle = time * 0.3
        val cosTrap = cos(trapAngle)
        val sinTrap = sin(trapAngle)
        val trapCX = baseTrapCX * cosTrap - baseTrapCY * sinTrap
        val trapCY = baseTrapCX * sinTrap + baseTrapCY * cosTrap
        val juliaCX = baseJuliaCX + 0.1 * cos(time * 0.2)
        val juliaCY = baseJuliaCY + 0.1 * sin(time * 0.2)
        val colorCycleOffset = (time * 30).toInt() % 256

        val aspect = w.toDouble() / h.toDouble()
        val rangeY = 2.6 / zoom
        val rangeX = rangeY * aspect
        val invW = 1.0 / w
        val invH = 1.0 / h

        val escapeR2 = 65536.0
        val ln2 = ln(2.0)
        val lnEscR = ln(256.0)

        val lutSize = 256
        val lutMax = lutSize - 1
        val paletteLut = IntArray(lutSize) { palette.lerpColor(it.toFloat() / lutMax) }

        val needsRing = intMode != INT_TRAP && intMode != INT_FINAL_VALUE
        val pixels = IntArray(w * h)

        val cores = Runtime.getRuntime().availableProcessors().coerceIn(2, 8)
        val threads = Array(cores) { t ->
            Thread {
                val y0 = t * h / cores
                val y1 = (t + 1) * h / cores

                // Per-thread ring buffers
                val ringX = if (needsRing) DoubleArray(RING_SIZE) else null
                val ringY = if (needsRing) DoubleArray(RING_SIZE) else null

                for (py in y0 until y1) {
                    val rawY = (py * invH - 0.5) * rangeY
                    for (px in 0 until w) {
                        val rawX = (px * invW - 0.5) * rangeX
                        val pixX = centerX + rawX
                        val pixY = centerY + rawY

                        // Init z and c based on variant
                        var zr: Double; var zi: Double
                        var cr: Double; var ci: Double
                        when (variant) {
                            VAR_JULIA -> {
                                zr = pixX; zi = pixY; cr = juliaCX; ci = juliaCY
                            }
                            VAR_NEWTON -> {
                                zr = pixX; zi = pixY; cr = 0.0; ci = 0.0
                            }
                            else -> {
                                zr = 0.0; zi = 0.0; cr = pixX; ci = pixY
                            }
                        }

                        var iter = 0
                        var minTrapDist = Double.MAX_VALUE
                        var ringHead = 0
                        var escaped = false
                        var convergedNewton = false
                        var finalZr = 0.0
                        var finalZi = 0.0

                        if (variant == VAR_NEWTON) {
                            // Newton iteration: z = z - (z³-1)/(3z²)
                            while (iter < scaledMaxIter) {
                                // Store in ring
                                if (ringX != null) {
                                    ringX[ringHead and RING_MASK] = zr
                                    ringY!![ringHead and RING_MASK] = zi
                                    ringHead++
                                }

                                // Trap distance
                                val td = trapDist(zr, zi, trapType, trapCX, trapCY, trapRadius)
                                if (td < minTrapDist) minTrapDist = td

                                // z³
                                val z2r = zr * zr - zi * zi
                                val z2i = 2.0 * zr * zi
                                val z3r = z2r * zr - z2i * zi
                                val z3i = z2r * zi + z2i * zr
                                // f = z³ - 1, f' = 3z²
                                val fr = z3r - 1.0
                                val fi = z3i
                                val fpr = 3.0 * z2r
                                val fpi = 3.0 * z2i
                                val denom = fpr * fpr + fpi * fpi
                                if (denom < 1e-20) break

                                val newZr = zr - (fr * fpr + fi * fpi) / denom
                                val newZi = zi - (fi * fpr - fr * fpi) / denom

                                // Check convergence
                                val dr = newZr - zr
                                val di = newZi - zi
                                if (dr * dr + di * di < 1e-10) {
                                    convergedNewton = true
                                    finalZr = newZr; finalZi = newZi
                                    iter++
                                    break
                                }

                                zr = newZr; zi = newZi
                                iter++

                                if (zr * zr + zi * zi > 1e10) { escaped = true; break }
                            }
                            finalZr = zr; finalZi = zi
                        } else {
                            // Escape-time variants
                            // Cardioid/bulb skip for Mandelbrot
                            var skipInside = false
                            if (variant == VAR_MANDELBROT) {
                                val crm = cr - 0.25
                                val ci2 = ci * ci
                                val q = crm * crm + ci2
                                if (q * (q + crm) <= 0.25 * ci2) skipInside = true
                                else {
                                    val crp = cr + 1.0
                                    if (crp * crp + ci2 <= 0.0625) skipInside = true
                                }
                            }

                            if (skipInside) {
                                // Known interior — use trap/period coloring with a synthetic orbit
                                iter = scaledMaxIter
                                // Run a few iterations to get orbit data for interior coloring
                                zr = 0.0; zi = 0.0
                                val fakeIters = scaledMaxIter.coerceAtMost(100)
                                for (i in 0 until fakeIters) {
                                    if (ringX != null) {
                                        ringX[ringHead and RING_MASK] = zr
                                        ringY!![ringHead and RING_MASK] = zi
                                        ringHead++
                                    }
                                    val td = trapDist(zr, zi, trapType, trapCX, trapCY, trapRadius)
                                    if (td < minTrapDist) minTrapDist = td
                                    val tmp = zr * zr - zi * zi + cr
                                    zi = 2.0 * zr * zi + ci
                                    zr = tmp
                                }
                                finalZr = zr; finalZi = zi
                            } else {
                                // Brent's periodicity detection
                                var refZr = 0.0; var refZi = 0.0
                                var period = 1; var pCount = 0

                                while (iter < scaledMaxIter) {
                                    if (ringX != null) {
                                        ringX[ringHead and RING_MASK] = zr
                                        ringY!![ringHead and RING_MASK] = zi
                                        ringHead++
                                    }

                                    val td = trapDist(zr, zi, trapType, trapCX, trapCY, trapRadius)
                                    if (td < minTrapDist) minTrapDist = td

                                    // Iterate based on variant
                                    when (variant) {
                                        VAR_TRICORN -> {
                                            val tmp = zr * zr - zi * zi + cr
                                            zi = -2.0 * zr * zi + ci  // conjugate
                                            zr = tmp
                                        }
                                        VAR_BURNING_SHIP -> {
                                            val azr = abs(zr); val azi = abs(zi)
                                            val tmp = azr * azr - azi * azi + cr
                                            zi = 2.0 * azr * azi + ci
                                            zr = tmp
                                        }
                                        else -> { // Mandelbrot, Julia
                                            val tmp = zr * zr - zi * zi + cr
                                            zi = 2.0 * zr * zi + ci
                                            zr = tmp
                                        }
                                    }
                                    iter++

                                    val mag2 = zr * zr + zi * zi
                                    if (mag2 > escapeR2) { escaped = true; break }

                                    // Brent's cycle detection
                                    val bdr = zr - refZr; val bdi = zi - refZi
                                    if (bdr * bdr + bdi * bdi < 1e-20) {
                                        iter = scaledMaxIter; break
                                    }
                                    if (++pCount >= period) {
                                        refZr = zr; refZi = zi; pCount = 0
                                        period = (period shl 1).coerceAtMost(512)
                                    }
                                }
                                finalZr = zr; finalZi = zi
                            }
                        }

                        // Color the pixel
                        val color: Int
                        if (variant == VAR_NEWTON) {
                            color = if (convergedNewton) {
                                // Newton converged = exterior-like (root coloring)
                                val rootAngle = atan2(finalZi, finalZr)
                                val rootIdx = ((rootAngle / (2.0 * PI) + 1.0) % 1.0)
                                val t = ((rootIdx * lutMax).toInt() + colorCycleOffset) % lutSize
                                val baseColor = paletteLut[t.coerceIn(0, lutMax)]
                                val shade = (1.0 - iter.toDouble() / scaledMaxIter).coerceIn(0.3, 1.0)
                                blendShade(baseColor, shade)
                            } else {
                                // Newton didn't converge = interior
                                interiorColor(intMode, minTrapDist, trapRadius, finalZr, finalZi,
                                    ringX, ringY, ringHead, iter, scaledMaxIter,
                                    paletteLut, lutMax, colorCycleOffset, variant)
                            }
                        } else if (escaped) {
                            color = exteriorColor(extMode, zr, zi, iter, scaledMaxIter,
                                paletteLut, lutMax, colorCycleOffset, ln2, lnEscR)
                        } else {
                            color = interiorColor(intMode, minTrapDist, trapRadius, finalZr, finalZi,
                                ringX, ringY, ringHead, iter, scaledMaxIter,
                                paletteLut, lutMax, colorCycleOffset, variant)
                        }

                        pixels[py * w + px] = color
                    }
                }
            }.also { it.start() }
        }
        threads.forEach { it.join() }

        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
        canvas.drawBitmap(bitmap, 0f, 0f, null)
    }

    private fun trapDist(
        zx: Double, zy: Double, trapType: Int,
        tcx: Double, tcy: Double, radius: Double
    ): Double {
        val dx = zx - tcx
        val dy = zy - tcy
        return when (trapType) {
            TRAP_LINE -> abs(dy)
            TRAP_CROSS -> min(abs(dx), abs(dy))
            TRAP_CIRCLE -> abs(sqrt(dx * dx + dy * dy) - radius)
            TRAP_POLYGON -> {
                var maxDot = Double.MIN_VALUE
                for (i in 0 until 3) {
                    val dot = abs(dx * HEX_NX[i] + dy * HEX_NY[i])
                    if (dot > maxDot) maxDot = dot
                }
                (maxDot - radius).coerceAtLeast(0.0)
            }
            else -> sqrt(dx * dx + dy * dy) // TRAP_POINT
        }
    }

    private fun exteriorColor(
        extMode: Int, zr: Double, zi: Double, iter: Int, maxIter: Int,
        lut: IntArray, lutMax: Int, cycleOffset: Int, ln2: Double, lnEscR: Double
    ): Int {
        return when (extMode) {
            EXT_OFF -> Color.BLACK
            EXT_BANDED -> {
                val t = ((iter * 7 + cycleOffset) % (lutMax + 1)).coerceIn(0, lutMax)
                lut[t]
            }
            EXT_SUBTLE -> {
                val zmag = sqrt(zr * zr + zi * zi)
                val smoothIter = iter + 1.0 - ln(ln(zmag) / lnEscR) / ln2
                val rawT = ((smoothIter / maxIter + cycleOffset / 256.0) % 1.0 + 1.0) % 1.0
                val base = lut[(rawT * lutMax).toInt().coerceIn(0, lutMax)]
                blendShade(base, 0.25)
            }
            else -> { // EXT_SMOOTH
                val zmag = sqrt(zr * zr + zi * zi)
                val smoothIter = iter + 1.0 - ln(ln(zmag) / lnEscR) / ln2
                val rawT = ((smoothIter / maxIter * 3.0 + cycleOffset / 256.0) % 1.0 + 1.0) % 1.0
                lut[(rawT * lutMax).toInt().coerceIn(0, lutMax)]
            }
        }
    }

    private fun interiorColor(
        intMode: Int, minTrapDist: Double, trapRadius: Double,
        finalZr: Double, finalZi: Double,
        ringX: DoubleArray?, ringY: DoubleArray?, ringHead: Int,
        iter: Int, maxIter: Int,
        lut: IntArray, lutMax: Int, cycleOffset: Int, variant: Int
    ): Int {
        return when (intMode) {
            INT_PERIOD -> {
                val period = detectPeriod(ringX, ringY, ringHead, iter.coerceAtMost(RING_SIZE))
                if (period <= 0) Color.BLACK
                else {
                    val t = ((period * 8 + cycleOffset) % (lutMax + 1)).coerceIn(0, lutMax)
                    lut[t]
                }
            }
            INT_MULT_MAG -> {
                val period = detectPeriod(ringX, ringY, ringHead, iter.coerceAtMost(RING_SIZE))
                if (period <= 0) Color.BLACK
                else {
                    val (mag, _) = computeMultiplier(ringX!!, ringY!!, ringHead, period, variant)
                    val t = ((mag.coerceIn(0.0, 1.0) * lutMax).toInt() + cycleOffset) % (lutMax + 1)
                    lut[t.coerceIn(0, lutMax)]
                }
            }
            INT_MULT_ARG -> {
                val period = detectPeriod(ringX, ringY, ringHead, iter.coerceAtMost(RING_SIZE))
                if (period <= 0) Color.BLACK
                else {
                    val (_, arg) = computeMultiplier(ringX!!, ringY!!, ringHead, period, variant)
                    val normalized = ((arg / (2.0 * PI)) % 1.0 + 1.0) % 1.0
                    val t = ((normalized * lutMax).toInt() + cycleOffset) % (lutMax + 1)
                    lut[t.coerceIn(0, lutMax)]
                }
            }
            INT_INTERIOR_DE -> {
                val period = detectPeriod(ringX, ringY, ringHead, iter.coerceAtMost(RING_SIZE))
                if (period <= 0) Color.BLACK
                else {
                    val (mag, _) = computeMultiplier(ringX!!, ringY!!, ringHead, period, variant)
                    // Interior DE approximation: smaller multiplier = closer to boundary
                    val de = (1.0 - mag).coerceIn(0.0, 1.0)
                    val t = ((de * lutMax).toInt() + cycleOffset) % (lutMax + 1)
                    lut[t.coerceIn(0, lutMax)]
                }
            }
            INT_FINAL_VALUE -> {
                val mag = sqrt(finalZr * finalZr + finalZi * finalZi).coerceIn(0.0, 4.0) / 4.0
                val angle = ((atan2(finalZi, finalZr) / (2.0 * PI)) % 1.0 + 1.0) % 1.0
                val combined = (mag * 0.5 + angle * 0.5)
                val t = ((combined * lutMax).toInt() + cycleOffset) % (lutMax + 1)
                lut[t.coerceIn(0, lutMax)]
            }
            else -> { // INT_TRAP
                if (minTrapDist >= Double.MAX_VALUE * 0.5) return Color.BLACK
                val normalized = (minTrapDist / trapRadius.coerceAtLeast(0.01)).coerceIn(0.0, 1.0)
                val t = ((normalized * lutMax).toInt() + cycleOffset) % (lutMax + 1)
                lut[t.coerceIn(0, lutMax)]
            }
        }
    }

    private fun detectPeriod(
        ringX: DoubleArray?, ringY: DoubleArray?, ringHead: Int, count: Int
    ): Int {
        if (ringX == null || ringY == null || count < 2) return 0
        val last = ringHead - 1
        val refX = ringX[last and RING_MASK]
        val refY = ringY!![last and RING_MASK]
        val searchLen = count.coerceAtMost(RING_SIZE - 1)
        for (p in 1..searchLen) {
            val idx = (last - p) and RING_MASK
            val dx = ringX[idx] - refX
            val dy = ringY[idx] - refY
            if (dx * dx + dy * dy < 1e-12) return p
        }
        return 0
    }

    private fun computeMultiplier(
        ringX: DoubleArray, ringY: DoubleArray, ringHead: Int, period: Int, variant: Int
    ): Pair<Double, Double> {
        // Multiplier = product of f'(z_i) over one period cycle
        // For z²+c variants, f'(z) = 2z
        var prodR = 1.0
        var prodI = 0.0
        val start = ringHead - period
        for (i in 0 until period) {
            val idx = (start + i) and RING_MASK
            val zx = ringX[idx]
            val zy = ringY[idx]

            val dR: Double; val dI: Double
            if (variant == VAR_NEWTON) {
                // Newton derivative is more complex; approximate with 2z
                dR = 2.0 * zx; dI = 2.0 * zy
            } else {
                // For z²+c: f'(z) = 2z
                dR = 2.0 * zx; dI = 2.0 * zy
            }

            // Complex multiply: prod = prod * d
            val newR = prodR * dR - prodI * dI
            val newI = prodR * dI + prodI * dR
            prodR = newR; prodI = newI
        }
        val mag = sqrt(prodR * prodR + prodI * prodI)
        val arg = atan2(prodI, prodR)
        return Pair(mag.coerceAtMost(10.0) / 10.0, arg)
    }

    private fun blendShade(color: Int, shade: Double): Int {
        val r = ((color shr 16 and 0xFF) * shade).toInt().coerceIn(0, 255)
        val g = ((color shr 8 and 0xFF) * shade).toInt().coerceIn(0, 255)
        val b = ((color and 0xFF) * shade).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float {
        val maxIter = (params["maxIterations"] as? Number)?.toInt() ?: 300
        val intMode = (params["interiorMode"] as? String) ?: "trap"
        val modeMul = when (intMode) {
            "trap", "final-value" -> 1f
            "period" -> 1.2f
            else -> 1.5f
        }
        return (maxIter * modeMul / 800f).coerceIn(0.2f, 1f)
    }
}
