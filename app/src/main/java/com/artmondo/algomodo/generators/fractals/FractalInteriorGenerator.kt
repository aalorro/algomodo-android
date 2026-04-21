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
        Parameter.NumberParam("Max Iterations", "maxIterations", ParamGroup.COMPOSITION, null, 50f, 800f, 10f, 150f),
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
        "maxIterations" to 150f,
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

        private const val ESCAPE_R2 = 65536.0
        private const val LN2 = 0.6931471805599453      // ln(2)
        private const val LN_ESC_R = 5.545177444479562   // ln(256)

        // Hexagon normals for polygon trap (3 axes, 6-fold symmetry)
        private val HEX_NX = doubleArrayOf(1.0, cos(PI / 3.0), cos(2.0 * PI / 3.0))
        private val HEX_NY = doubleArrayOf(0.0, sin(PI / 3.0), sin(2.0 * PI / 3.0))
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
        val maxIter = (params["maxIterations"] as? Number)?.toInt() ?: 150
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

        val isAnim = time > 0f
        val scaledMaxIter = when {
            isAnim -> (maxIter / 3).coerceIn(20, 80)
            quality == Quality.DRAFT -> (maxIter / 3).coerceIn(20, 80)
            quality == Quality.ULTRA -> (maxIter * 1.5f).toInt()
            else -> maxIter
        }

        // Animation: rotate trap, drift Julia c, color cycle
        val trapAngle = time * 0.3
        val cosTrap = cos(trapAngle)
        val sinTrap = sin(trapAngle)
        val trapCX = baseTrapCX * cosTrap - baseTrapCY * sinTrap
        val trapCY = baseTrapCX * sinTrap + baseTrapCY * cosTrap
        val juliaCX = baseJuliaCX + 0.1 * cos(time * 0.2)
        val juliaCY = baseJuliaCY + 0.1 * sin(time * 0.2)
        val colorCycleOffset = (time * 30).toInt() and 0xFF

        // Adaptive resolution: render smaller during animation, upscale after
        val renderW: Int; val renderH: Int
        if (isAnim) {
            renderW = (w * 0.55).toInt().coerceAtLeast(w / 2)
            renderH = (h * 0.55).toInt().coerceAtLeast(h / 2)
        } else {
            renderW = w; renderH = h
        }

        val aspect = renderW.toDouble() / renderH.toDouble()
        val rangeY = 2.6 / zoom
        val rangeX = rangeY * aspect
        val invW = 1.0 / renderW
        val invH = 1.0 / renderH

        val lutSize = 256
        val lutMax = lutSize - 1
        val paletteLut = IntArray(lutSize) { palette.lerpColor(it.toFloat() / lutMax) }

        val needsRing = intMode != INT_TRAP && intMode != INT_FINAL_VALUE
        val renderPixels = IntArray(renderW * renderH)

        // Pre-compute trap radius squared for point trap (avoid sqrt per iter)
        val trapR2 = trapRadius * trapRadius

        val cores = Runtime.getRuntime().availableProcessors().coerceIn(2, 8)
        val threads = Array(cores) { t ->
            Thread {
                val y0 = t * renderH / cores
                val y1 = (t + 1) * renderH / cores

                // Per-thread ring buffers (only allocated when needed)
                val ringX = if (needsRing) DoubleArray(RING_SIZE) else null
                val ringY = if (needsRing) DoubleArray(RING_SIZE) else null
                // Reusable output for multiplier (avoid Pair allocation)
                val multOut = if (needsRing) DoubleArray(2) else null

                for (py in y0 until y1) {
                    // Interrupt check every 4 rows
                    if ((py and 3) == 0 && Thread.currentThread().isInterrupted) return@Thread

                    val rawY = (py * invH - 0.5) * rangeY
                    val rowOff = py * renderW

                    for (px in 0 until renderW) {
                        val rawX = (px * invW - 0.5) * rangeX
                        val pixX = centerX + rawX
                        val pixY = centerY + rawY

                        // Init z and c based on variant
                        var zr: Double; var zi: Double
                        var cr: Double; var ci: Double
                        when (variant) {
                            VAR_JULIA -> { zr = pixX; zi = pixY; cr = juliaCX; ci = juliaCY }
                            VAR_NEWTON -> { zr = pixX; zi = pixY; cr = 0.0; ci = 0.0 }
                            else -> { zr = 0.0; zi = 0.0; cr = pixX; ci = pixY }
                        }

                        var iter = 0
                        var escaped = false
                        var convergedNewton = false

                        if (variant == VAR_NEWTON) {
                            // ---- Newton: z = z - (z³-1)/(3z²) ----
                            var minTD = Double.MAX_VALUE
                            var ringHead = 0
                            while (iter < scaledMaxIter) {
                                if (ringX != null) {
                                    ringX[ringHead and RING_MASK] = zr
                                    ringY!![ringHead and RING_MASK] = zi
                                    ringHead++
                                }
                                // Inline trap distance
                                val td = inlineTrapDist(zr, zi, trapType, trapCX, trapCY, trapRadius)
                                if (td < minTD) minTD = td

                                val z2r = zr * zr - zi * zi
                                val z2i = 2.0 * zr * zi
                                val z3r = z2r * zr - z2i * zi
                                val z3i = z2r * zi + z2i * zr
                                val fr = z3r - 1.0; val fi = z3i
                                val fpr = 3.0 * z2r; val fpi = 3.0 * z2i
                                val denom = fpr * fpr + fpi * fpi
                                if (denom < 1e-20) break

                                val nzr = zr - (fr * fpr + fi * fpi) / denom
                                val nzi = zi - (fi * fpr - fr * fpi) / denom
                                val dr = nzr - zr; val di = nzi - zi
                                if (dr * dr + di * di < 1e-10) {
                                    convergedNewton = true; zr = nzr; zi = nzi; iter++; break
                                }
                                zr = nzr; zi = nzi; iter++
                                if (zr * zr + zi * zi > 1e10) { escaped = true; break }
                            }

                            renderPixels[rowOff + px] = if (convergedNewton) {
                                val rootAngle = atan2(zi, zr)
                                val rootIdx = ((rootAngle / (2.0 * PI) + 1.0) % 1.0)
                                val t = ((rootIdx * lutMax).toInt() + colorCycleOffset) and 0xFF
                                val shade = (1.0 - iter.toDouble() / scaledMaxIter).coerceIn(0.3, 1.0)
                                blendShade(paletteLut[t.coerceIn(0, lutMax)], shade)
                            } else {
                                colorInterior(intMode, minTD, trapRadius, zr, zi,
                                    ringX, ringY, ringHead, multOut, iter, scaledMaxIter,
                                    paletteLut, lutMax, colorCycleOffset, variant)
                            }
                        } else {
                            // ---- Escape-time variants ----

                            // Cardioid/bulb skip for Mandelbrot — color directly, no fake iteration
                            if (variant == VAR_MANDELBROT) {
                                val crm = cr - 0.25; val ci2 = ci * ci
                                val q = crm * crm + ci2
                                if (q * (q + crm) <= 0.25 * ci2 ||
                                    (cr + 1.0).let { it * it } + ci2 <= 0.0625) {
                                    // Known interior — fast-path: compute a simple interior color
                                    // For trap mode use low synthetic distance, for others use palette base
                                    renderPixels[rowOff + px] = when (intMode) {
                                        INT_TRAP -> {
                                            // Point near origin: trap dist ≈ |c|
                                            val cd = sqrt(cr * cr + ci * ci)
                                            val norm = (cd / trapRadius.coerceAtLeast(0.01)).coerceIn(0.0, 1.0)
                                            val t = ((norm * lutMax).toInt() + colorCycleOffset) and 0xFF
                                            paletteLut[t.coerceIn(0, lutMax)]
                                        }
                                        INT_FINAL_VALUE -> {
                                            // Use c as approximate final value
                                            val mag = sqrt(cr * cr + ci * ci).coerceIn(0.0, 2.0) / 2.0
                                            val angle = ((atan2(ci, cr) / (2.0 * PI)) % 1.0 + 1.0) % 1.0
                                            val t = (((mag * 0.5 + angle * 0.5) * lutMax).toInt() + colorCycleOffset) and 0xFF
                                            paletteLut[t.coerceIn(0, lutMax)]
                                        }
                                        INT_PERIOD -> {
                                            // Cardioid = period 1, bulb = period 2
                                            val crm2 = cr - 0.25; val ci22 = ci * ci
                                            val q2 = crm2 * crm2 + ci22
                                            val isCardioid = q2 * (q2 + crm2) <= 0.25 * ci22
                                            val period = if (isCardioid) 1 else 2
                                            val t = ((period * 8 + colorCycleOffset) and 0xFF)
                                            paletteLut[t.coerceIn(0, lutMax)]
                                        }
                                        else -> {
                                            // multiplier modes — period-1 bulb has multiplier ≈ 0, cardioid varies
                                            val norm = sqrt(cr * cr + ci * ci).coerceIn(0.0, 2.0) / 2.0
                                            val t = ((norm * lutMax).toInt() + colorCycleOffset) and 0xFF
                                            paletteLut[t.coerceIn(0, lutMax)]
                                        }
                                    }
                                    continue
                                }
                            }

                            var minTD = Double.MAX_VALUE
                            var ringHead = 0

                            // Brent's periodicity detection
                            var refZr = 0.0; var refZi = 0.0
                            var period = 1; var pCount = 0

                            // Dispatch by variant OUTSIDE inner loop to avoid branch per iteration
                            when (variant) {
                                VAR_MANDELBROT, VAR_JULIA -> {
                                    while (iter < scaledMaxIter) {
                                        if (ringX != null) {
                                            ringX[ringHead and RING_MASK] = zr
                                            ringY!![ringHead and RING_MASK] = zi
                                            ringHead++
                                        }
                                        // Inline trap dist for each type (no function call)
                                        when (trapType) {
                                            TRAP_POINT -> {
                                                val dx = zr - trapCX; val dy = zi - trapCY
                                                val d2 = dx * dx + dy * dy
                                                if (d2 < minTD) minTD = d2 // compare squared, sqrt later
                                            }
                                            TRAP_LINE -> {
                                                val d = abs(zi - trapCY)
                                                if (d < minTD) minTD = d
                                            }
                                            TRAP_CROSS -> {
                                                val d = min(abs(zr - trapCX), abs(zi - trapCY))
                                                if (d < minTD) minTD = d
                                            }
                                            TRAP_CIRCLE -> {
                                                val dx = zr - trapCX; val dy = zi - trapCY
                                                val d = abs(sqrt(dx * dx + dy * dy) - trapRadius)
                                                if (d < minTD) minTD = d
                                            }
                                            else -> { // POLYGON
                                                val dx = zr - trapCX; val dy = zi - trapCY
                                                var maxDot = -1e30
                                                for (i in 0..2) {
                                                    val dot = abs(dx * HEX_NX[i] + dy * HEX_NY[i])
                                                    if (dot > maxDot) maxDot = dot
                                                }
                                                val d = (maxDot - trapRadius).coerceAtLeast(0.0)
                                                if (d < minTD) minTD = d
                                            }
                                        }

                                        val tmp = zr * zr - zi * zi + cr
                                        zi = 2.0 * zr * zi + ci
                                        zr = tmp
                                        iter++

                                        if (zr * zr + zi * zi > ESCAPE_R2) { escaped = true; break }

                                        val bdr = zr - refZr; val bdi = zi - refZi
                                        if (bdr * bdr + bdi * bdi < 1e-20) { iter = scaledMaxIter; break }
                                        if (++pCount >= period) {
                                            refZr = zr; refZi = zi; pCount = 0
                                            period = (period shl 1).coerceAtMost(512)
                                        }
                                    }
                                }
                                VAR_TRICORN -> {
                                    while (iter < scaledMaxIter) {
                                        if (ringX != null) {
                                            ringX[ringHead and RING_MASK] = zr
                                            ringY!![ringHead and RING_MASK] = zi
                                            ringHead++
                                        }
                                        val td = inlineTrapDist(zr, zi, trapType, trapCX, trapCY, trapRadius)
                                        if (td < minTD) minTD = td

                                        val tmp = zr * zr - zi * zi + cr
                                        zi = -2.0 * zr * zi + ci
                                        zr = tmp
                                        iter++

                                        if (zr * zr + zi * zi > ESCAPE_R2) { escaped = true; break }
                                        val bdr = zr - refZr; val bdi = zi - refZi
                                        if (bdr * bdr + bdi * bdi < 1e-20) { iter = scaledMaxIter; break }
                                        if (++pCount >= period) {
                                            refZr = zr; refZi = zi; pCount = 0
                                            period = (period shl 1).coerceAtMost(512)
                                        }
                                    }
                                }
                                else -> { // BURNING_SHIP
                                    while (iter < scaledMaxIter) {
                                        if (ringX != null) {
                                            ringX[ringHead and RING_MASK] = zr
                                            ringY!![ringHead and RING_MASK] = zi
                                            ringHead++
                                        }
                                        val td = inlineTrapDist(zr, zi, trapType, trapCX, trapCY, trapRadius)
                                        if (td < minTD) minTD = td

                                        val azr = abs(zr); val azi = abs(zi)
                                        val tmp = azr * azr - azi * azi + cr
                                        zi = 2.0 * azr * azi + ci
                                        zr = tmp
                                        iter++

                                        if (zr * zr + zi * zi > ESCAPE_R2) { escaped = true; break }
                                        val bdr = zr - refZr; val bdi = zi - refZi
                                        if (bdr * bdr + bdi * bdi < 1e-20) { iter = scaledMaxIter; break }
                                        if (++pCount >= period) {
                                            refZr = zr; refZi = zi; pCount = 0
                                            period = (period shl 1).coerceAtMost(512)
                                        }
                                    }
                                }
                            }

                            // For point trap we tracked squared distance — take sqrt now
                            if (trapType == TRAP_POINT && (variant == VAR_MANDELBROT || variant == VAR_JULIA)) {
                                minTD = sqrt(minTD)
                            }

                            renderPixels[rowOff + px] = if (escaped) {
                                when (extMode) {
                                    EXT_OFF -> Color.BLACK
                                    EXT_BANDED -> {
                                        paletteLut[((iter * 7 + colorCycleOffset) and 0xFF).coerceIn(0, lutMax)]
                                    }
                                    EXT_SUBTLE -> {
                                        val zmag = sqrt(zr * zr + zi * zi)
                                        val si = iter + 1.0 - ln(ln(zmag) / LN_ESC_R) / LN2
                                        val rawT = ((si / scaledMaxIter + colorCycleOffset / 256.0) % 1.0 + 1.0) % 1.0
                                        blendShade(paletteLut[(rawT * lutMax).toInt().coerceIn(0, lutMax)], 0.25)
                                    }
                                    else -> { // SMOOTH
                                        val zmag = sqrt(zr * zr + zi * zi)
                                        val si = iter + 1.0 - ln(ln(zmag) / LN_ESC_R) / LN2
                                        val rawT = ((si / scaledMaxIter * 3.0 + colorCycleOffset / 256.0) % 1.0 + 1.0) % 1.0
                                        paletteLut[(rawT * lutMax).toInt().coerceIn(0, lutMax)]
                                    }
                                }
                            } else {
                                colorInterior(intMode, minTD, trapRadius, zr, zi,
                                    ringX, ringY, ringHead, multOut, iter, scaledMaxIter,
                                    paletteLut, lutMax, colorCycleOffset, variant)
                            }
                        }
                    }
                }
            }.also { it.start() }
        }
        threads.forEach { it.join() }

        // Upscale to full bitmap if rendered at reduced resolution
        if (renderW < w) {
            val pixels = IntArray(w * h)
            val xMap = IntArray(w) { (it * renderW / w).coerceAtMost(renderW - 1) }
            for (py in 0 until h) {
                val sy = (py * renderH / h).coerceAtMost(renderH - 1)
                val srcOff = sy * renderW
                val dstOff = py * w
                for (px in 0 until w) {
                    pixels[dstOff + px] = renderPixels[srcOff + xMap[px]]
                }
            }
            bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
        } else {
            bitmap.setPixels(renderPixels, 0, w, 0, 0, w, h)
        }
        canvas.drawBitmap(bitmap, 0f, 0f, null)
    }

    /** Trap distance — used by Tricorn/BurningShip paths. Mandelbrot/Julia inline it directly. */
    private fun inlineTrapDist(
        zx: Double, zy: Double, trapType: Int,
        tcx: Double, tcy: Double, radius: Double
    ): Double {
        val dx = zx - tcx; val dy = zy - tcy
        return when (trapType) {
            TRAP_LINE -> abs(dy)
            TRAP_CROSS -> min(abs(dx), abs(dy))
            TRAP_CIRCLE -> abs(sqrt(dx * dx + dy * dy) - radius)
            TRAP_POLYGON -> {
                var maxDot = -1e30
                for (i in 0..2) {
                    val dot = abs(dx * HEX_NX[i] + dy * HEX_NY[i])
                    if (dot > maxDot) maxDot = dot
                }
                (maxDot - radius).coerceAtLeast(0.0)
            }
            else -> sqrt(dx * dx + dy * dy) // POINT
        }
    }

    private fun colorInterior(
        intMode: Int, minTrapDist: Double, trapRadius: Double,
        finalZr: Double, finalZi: Double,
        ringX: DoubleArray?, ringY: DoubleArray?, ringHead: Int,
        multOut: DoubleArray?, iter: Int, maxIter: Int,
        lut: IntArray, lutMax: Int, cycleOffset: Int, variant: Int
    ): Int {
        return when (intMode) {
            INT_PERIOD -> {
                val period = detectPeriod(ringX, ringY, ringHead, iter.coerceAtMost(RING_SIZE))
                if (period <= 0) lut[(cycleOffset and 0xFF).coerceIn(0, lutMax)]
                else {
                    val t = ((period * 8 + cycleOffset) and 0xFF).coerceIn(0, lutMax)
                    lut[t]
                }
            }
            INT_MULT_MAG -> {
                val period = detectPeriod(ringX, ringY, ringHead, iter.coerceAtMost(RING_SIZE))
                if (period <= 0) lut[(cycleOffset and 0xFF).coerceIn(0, lutMax)]
                else {
                    computeMultiplier(ringX!!, ringY!!, ringHead, period, multOut!!)
                    val t = ((multOut[0].coerceIn(0.0, 1.0) * lutMax).toInt() + cycleOffset) and 0xFF
                    lut[t.coerceIn(0, lutMax)]
                }
            }
            INT_MULT_ARG -> {
                val period = detectPeriod(ringX, ringY, ringHead, iter.coerceAtMost(RING_SIZE))
                if (period <= 0) lut[(cycleOffset and 0xFF).coerceIn(0, lutMax)]
                else {
                    computeMultiplier(ringX!!, ringY!!, ringHead, period, multOut!!)
                    val normalized = ((multOut[1] / (2.0 * PI)) % 1.0 + 1.0) % 1.0
                    val t = ((normalized * lutMax).toInt() + cycleOffset) and 0xFF
                    lut[t.coerceIn(0, lutMax)]
                }
            }
            INT_INTERIOR_DE -> {
                val period = detectPeriod(ringX, ringY, ringHead, iter.coerceAtMost(RING_SIZE))
                if (period <= 0) lut[(cycleOffset and 0xFF).coerceIn(0, lutMax)]
                else {
                    computeMultiplier(ringX!!, ringY!!, ringHead, period, multOut!!)
                    val de = (1.0 - multOut[0]).coerceIn(0.0, 1.0)
                    val t = ((de * lutMax).toInt() + cycleOffset) and 0xFF
                    lut[t.coerceIn(0, lutMax)]
                }
            }
            INT_FINAL_VALUE -> {
                val mag = sqrt(finalZr * finalZr + finalZi * finalZi).coerceIn(0.0, 4.0) / 4.0
                val angle = ((atan2(finalZi, finalZr) / (2.0 * PI)) % 1.0 + 1.0) % 1.0
                val combined = mag * 0.5 + angle * 0.5
                val t = ((combined * lutMax).toInt() + cycleOffset) and 0xFF
                lut[t.coerceIn(0, lutMax)]
            }
            else -> { // INT_TRAP
                if (minTrapDist >= 1e30) return lut[(cycleOffset and 0xFF).coerceIn(0, lutMax)]
                val normalized = (minTrapDist / trapRadius.coerceAtLeast(0.01)).coerceIn(0.0, 1.0)
                val t = ((normalized * lutMax).toInt() + cycleOffset) and 0xFF
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
        val refY = ringY[last and RING_MASK]
        val searchLen = count.coerceAtMost(RING_SIZE - 1)
        for (p in 1..searchLen) {
            val idx = (last - p) and RING_MASK
            val dx = ringX[idx] - refX
            val dy = ringY[idx] - refY
            if (dx * dx + dy * dy < 1e-12) return p
        }
        return 0
    }

    /** Writes [mag, arg] into out array. No allocation. */
    private fun computeMultiplier(
        ringX: DoubleArray, ringY: DoubleArray, ringHead: Int, period: Int,
        out: DoubleArray
    ) {
        var prodR = 1.0; var prodI = 0.0
        val start = ringHead - period
        for (i in 0 until period) {
            val idx = (start + i) and RING_MASK
            val dR = 2.0 * ringX[idx]; val dI = 2.0 * ringY[idx]
            val nR = prodR * dR - prodI * dI
            val nI = prodR * dI + prodI * dR
            prodR = nR; prodI = nI
        }
        out[0] = sqrt(prodR * prodR + prodI * prodI).coerceAtMost(10.0) / 10.0
        out[1] = atan2(prodI, prodR)
    }

    private fun blendShade(color: Int, shade: Double): Int {
        val r = ((color shr 16 and 0xFF) * shade).toInt().coerceIn(0, 255)
        val g = ((color shr 8 and 0xFF) * shade).toInt().coerceIn(0, 255)
        val b = ((color and 0xFF) * shade).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float {
        val maxIter = (params["maxIterations"] as? Number)?.toInt() ?: 150
        val intMode = (params["interiorMode"] as? String) ?: "trap"
        val modeMul = when (intMode) {
            "trap", "final-value" -> 1f
            "period" -> 1.2f
            else -> 1.5f
        }
        return (maxIter * modeMul / 500f).coerceIn(0.2f, 1f)
    }
}
