package com.artmondo.algomodo.generators.fractals

import android.graphics.Bitmap
import android.graphics.Canvas
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
        "orbit trap (minimum proximity to a geometric shape during iteration), period detection (cycle length of converged orbits), " +
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
            "point: distance to a point | line: horizontal line | cross: axis cross | circle: ring | polygon: hexagon",
            listOf("point", "line", "cross", "circle", "polygon"), "point"),
        Parameter.SelectParam("Exterior Mode", "exteriorMode", ParamGroup.COLOR,
            "smooth: gradient | banded: discrete steps | off: black exterior | subtle: dim exterior",
            listOf("smooth", "banded", "off", "subtle"), "smooth"),
        Parameter.NumberParam("Max Iterations", "maxIterations", ParamGroup.COMPOSITION, null, 50f, 800f, 10f, 150f),
        Parameter.NumberParam("Center X", "centerX", ParamGroup.COMPOSITION, null, -2.5f, 1.5f, 0.05f, 0f),
        Parameter.NumberParam("Center Y", "centerY", ParamGroup.COMPOSITION, null, -1.5f, 1.5f, 0.05f, 0f),
        Parameter.NumberParam("Zoom", "zoom", ParamGroup.COMPOSITION, null, 0.1f, 3f, 0.1f, 1f),
        Parameter.NumberParam("Trap Center X", "trapCX", ParamGroup.GEOMETRY, null, -2f, 2f, 0.05f, 0.25f),
        Parameter.NumberParam("Trap Center Y", "trapCY", ParamGroup.GEOMETRY, null, -2f, 2f, 0.05f, 0.5f),
        Parameter.NumberParam("Julia CX", "juliaCX", ParamGroup.GEOMETRY, "Real part of Julia constant", -2f, 2f, 0.01f, -0.7f),
        Parameter.NumberParam("Julia CY", "juliaCY", ParamGroup.GEOMETRY, "Imaginary part of Julia constant", -2f, 2f, 0.01f, 0.27f),
        Parameter.NumberParam("Trap Radius", "trapRadius", ParamGroup.GEOMETRY, null, 0.01f, 2f, 0.01f, 0.5f),
        Parameter.NumberParam("Anim Speed", "animSpeed", ParamGroup.FLOW_MOTION, "Multiplier for trap rotation, Julia drift, and color cycling", 0.1f, 5f, 0.1f, 1f)
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "variant" to "mandelbrot",
        "interiorMode" to "trap",
        "trapType" to "point",
        "exteriorMode" to "smooth",
        "maxIterations" to 150f,
        "centerX" to 0f,
        "centerY" to 0f,
        "zoom" to 1f,
        "trapCX" to 0.25f,
        "trapCY" to 0.5f,
        "juliaCX" to -0.7f,
        "juliaCY" to 0.27f,
        "trapRadius" to 0.5f,
        "animSpeed" to 1f
    )

    @Volatile private var cancelled = false
    @Volatile private var reusePixels: IntArray? = null
    @Volatile private var reuseUpscale: IntArray? = null

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
        private const val LN2 = 0.6931471805599453
        private const val LN_ESC_R = 5.545177444479562

        private const val TRAP_SKIP = 3

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
        cancelled = false

        val w = bitmap.width
        val h = bitmap.height

        val variantStr = (params["variant"] as? String) ?: "mandelbrot"
        val intModeStr = (params["interiorMode"] as? String) ?: "trap"
        val trapTypeStr = (params["trapType"] as? String) ?: "point"
        val extModeStr = (params["exteriorMode"] as? String) ?: "smooth"
        val maxIter = (params["maxIterations"] as? Number)?.toInt() ?: 150
        val centerX = (params["centerX"] as? Number)?.toDouble() ?: 0.0
        val centerY = (params["centerY"] as? Number)?.toDouble() ?: 0.0
        val zoom = (params["zoom"] as? Number)?.toDouble() ?: 1.0
        val baseTrapCX = (params["trapCX"] as? Number)?.toDouble() ?: 0.25
        val baseTrapCY = (params["trapCY"] as? Number)?.toDouble() ?: 0.5
        val baseJuliaCX = (params["juliaCX"] as? Number)?.toDouble() ?: -0.7
        val baseJuliaCY = (params["juliaCY"] as? Number)?.toDouble() ?: 0.27
        val trapRadius = (params["trapRadius"] as? Number)?.toDouble() ?: 0.5
        val animSpeed = (params["animSpeed"] as? Number)?.toDouble() ?: 1.0

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

        val isDraft = quality == Quality.DRAFT
        val isAnim = time > 0f
        val scaledMaxIter = when {
            isAnim -> (maxIter / 4).coerceIn(15, 50)
            isDraft -> (maxIter / 4).coerceIn(15, 50)
            quality == Quality.ULTRA -> (maxIter * 1.5f).toInt()
            else -> maxIter
        }

        // Animation: rotate trap, drift Julia c, color cycle
        val trapCX: Double; val trapCY: Double
        val juliaCX: Double; val juliaCY: Double
        val colorCycleOffset: Int
        if (isAnim) {
            val trapAngle = time * 0.3 * animSpeed
            val cosTrap = cos(trapAngle)
            val sinTrap = sin(trapAngle)
            trapCX = baseTrapCX * cosTrap - baseTrapCY * sinTrap
            trapCY = baseTrapCX * sinTrap + baseTrapCY * cosTrap
            juliaCX = baseJuliaCX + 0.1 * cos(time * 0.2 * animSpeed)
            juliaCY = baseJuliaCY + 0.1 * sin(time * 0.2 * animSpeed)
            colorCycleOffset = (time * 30 * animSpeed).toInt() and 0xFF
        } else {
            trapCX = baseTrapCX
            trapCY = baseTrapCY
            juliaCX = baseJuliaCX
            juliaCY = baseJuliaCY
            colorCycleOffset = 0
        }

        // Adaptive resolution for animation
        val renderW: Int; val renderH: Int
        if (isAnim) {
            renderW = (w * 0.5).toInt().coerceAtLeast(w / 2)
            renderH = (h * 0.5).toInt().coerceAtLeast(h / 2)
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

        // Only compute trap distances when interior mode is "trap"
        val needsTrap = intMode == INT_TRAP
        val needsRing = intMode != INT_TRAP && intMode != INT_FINAL_VALUE
        // For point trap, track squared distance in inner loop (avoid sqrt per iter)
        val pointTrapSq = needsTrap && trapType == TRAP_POINT

        // Reusable pixel array — eliminates per-frame GC
        val pixelCount = renderW * renderH
        val rp = reusePixels
        val renderPixels = if (rp != null && rp.size >= pixelCount) rp
            else IntArray(pixelCount).also { reusePixels = it }

        val trapScale = 3.0 / trapRadius.coerceAtLeast(0.01)
        val fastIters = if (isDraft) 10 else 20

        // Pre-compute for smooth coloring — avoid repeated division per escaped pixel
        val invMaxIter = 1.0 / scaledMaxIter
        val colorCycleD = colorCycleOffset / 256.0

        val cores = Runtime.getRuntime().availableProcessors().coerceIn(2, 8)
        val threads = Array(cores) { t ->
            Thread {
                val y0 = t * renderH / cores
                val y1 = (t + 1) * renderH / cores

                val ringX = if (needsRing) DoubleArray(RING_SIZE) else null
                val ringY = if (needsRing) DoubleArray(RING_SIZE) else null
                val multOut = if (needsRing) DoubleArray(2) else null

                for (py in y0 until y1) {
                    if (cancelled) return@Thread

                    val rawY = (py * invH - 0.5) * rangeY
                    val rowOff = py * renderW

                    for (px in 0 until renderW) {
                        val rawX = (px * invW - 0.5) * rangeX
                        val pixX = centerX + rawX
                        val pixY = centerY + rawY

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
                            // ---- Newton z³-1 ----
                            var minTD = Double.MAX_VALUE
                            var ringHead = 0
                            while (iter < scaledMaxIter) {
                                if (ringX != null) {
                                    ringX[ringHead and RING_MASK] = zr
                                    ringY!![ringHead and RING_MASK] = zi
                                    ringHead++
                                }
                                if (needsTrap && iter >= TRAP_SKIP) {
                                    val dx = zr - trapCX; val dy = zi - trapCY
                                    val td = when (trapType) {
                                        TRAP_POINT -> dx * dx + dy * dy
                                        TRAP_LINE -> abs(dy)
                                        TRAP_CROSS -> min(abs(dx), abs(dy))
                                        TRAP_CIRCLE -> abs(sqrt(dx * dx + dy * dy) - trapRadius)
                                        TRAP_POLYGON -> {
                                            var mx = -1e30
                                            for (i in 0..2) {
                                                val dot = abs(dx * HEX_NX[i] + dy * HEX_NY[i])
                                                if (dot > mx) mx = dot
                                            }
                                            (mx - trapRadius).coerceAtLeast(0.0)
                                        }
                                        else -> dx * dx + dy * dy
                                    }
                                    if (td < minTD) minTD = td
                                }

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

                            // Convert squared min distance back to actual distance for point trap
                            if (pointTrapSq && minTD < Double.MAX_VALUE) minTD = sqrt(minTD)

                            renderPixels[rowOff + px] = if (convergedNewton) {
                                val rootAngle = atan2(zi, zr)
                                val rootIdx = ((rootAngle / (2.0 * PI) + 1.0) % 1.0)
                                val t = ((rootIdx * lutMax).toInt() + colorCycleOffset) and 0xFF
                                val shade = (1.0 - iter * invMaxIter).coerceIn(0.3, 1.0)
                                blendShade(paletteLut[t.coerceIn(0, lutMax)], shade)
                            } else {
                                colorInterior(intMode, minTD, trapScale, zr, zi,
                                    ringX, ringY, ringHead, multOut, iter, scaledMaxIter,
                                    paletteLut, lutMax, colorCycleOffset)
                            }
                        } else {
                            // ---- Escape-time variants ----

                            // Cardioid/bulb skip for Mandelbrot
                            if (variant == VAR_MANDELBROT) {
                                val crm = cr - 0.25; val ci2 = ci * ci
                                val q = crm * crm + ci2
                                if (q * (q + crm) <= 0.25 * ci2 ||
                                    (cr + 1.0).let { it * it } + ci2 <= 0.0625) {
                                    renderPixels[rowOff + px] = fastInteriorColor(
                                        intMode, cr, ci, trapType, trapCX, trapCY, trapRadius,
                                        trapScale, pointTrapSq, fastIters,
                                        paletteLut, lutMax, colorCycleOffset)
                                    continue
                                }
                            }

                            var minTD = Double.MAX_VALUE
                            var ringHead = 0

                            var refZr = 0.0; var refZi = 0.0
                            var period = 1; var pCount = 0

                            when (variant) {
                                VAR_MANDELBROT, VAR_JULIA -> {
                                    // Cache zr², zi² — saves 2 multiplies per iteration
                                    var zr2 = zr * zr
                                    var zi2 = zi * zi

                                    while (iter < scaledMaxIter) {
                                        if (ringX != null) {
                                            ringX[ringHead and RING_MASK] = zr
                                            ringY!![ringHead and RING_MASK] = zi
                                            ringHead++
                                        }

                                        // Trap distance — only when interior mode is "trap"
                                        if (needsTrap && iter >= TRAP_SKIP) {
                                            val dx = zr - trapCX; val dy = zi - trapCY
                                            val td = when (trapType) {
                                                TRAP_POINT -> dx * dx + dy * dy // squared — sqrt once at end
                                                TRAP_LINE -> abs(dy)
                                                TRAP_CROSS -> min(abs(dx), abs(dy))
                                                TRAP_CIRCLE -> abs(sqrt(dx * dx + dy * dy) - trapRadius)
                                                TRAP_POLYGON -> {
                                                    var mx = -1e30
                                                    for (i in 0..2) {
                                                        val dot = abs(dx * HEX_NX[i] + dy * HEX_NY[i])
                                                        if (dot > mx) mx = dot
                                                    }
                                                    (mx - trapRadius).coerceAtLeast(0.0)
                                                }
                                                else -> dx * dx + dy * dy
                                            }
                                            if (td < minTD) minTD = td
                                        }

                                        // z² + c iteration using cached squares
                                        zi = 2.0 * zr * zi + ci
                                        zr = zr2 - zi2 + cr
                                        iter++

                                        // Update cached squares for escape check + next iter
                                        zr2 = zr * zr
                                        zi2 = zi * zi
                                        if (zr2 + zi2 > ESCAPE_R2) { escaped = true; break }

                                        // Brent's periodicity detection
                                        val bdr = zr - refZr; val bdi = zi - refZi
                                        if (bdr * bdr + bdi * bdi < 1e-20) { iter = scaledMaxIter; break }
                                        if (++pCount >= period) {
                                            refZr = zr; refZi = zi; pCount = 0
                                            period = (period shl 1).coerceAtMost(512)
                                        }
                                    }
                                }
                                VAR_TRICORN -> {
                                    var zr2 = zr * zr
                                    var zi2 = zi * zi

                                    while (iter < scaledMaxIter) {
                                        if (ringX != null) {
                                            ringX[ringHead and RING_MASK] = zr
                                            ringY!![ringHead and RING_MASK] = zi
                                            ringHead++
                                        }
                                        if (needsTrap && iter >= TRAP_SKIP) {
                                            val dx = zr - trapCX; val dy = zi - trapCY
                                            val td = when (trapType) {
                                                TRAP_POINT -> dx * dx + dy * dy
                                                TRAP_LINE -> abs(dy)
                                                TRAP_CROSS -> min(abs(dx), abs(dy))
                                                TRAP_CIRCLE -> abs(sqrt(dx * dx + dy * dy) - trapRadius)
                                                TRAP_POLYGON -> {
                                                    var mx = -1e30
                                                    for (i in 0..2) {
                                                        val dot = abs(dx * HEX_NX[i] + dy * HEX_NY[i])
                                                        if (dot > mx) mx = dot
                                                    }
                                                    (mx - trapRadius).coerceAtLeast(0.0)
                                                }
                                                else -> dx * dx + dy * dy
                                            }
                                            if (td < minTD) minTD = td
                                        }

                                        // Tricorn: conj(z)² + c
                                        zi = -2.0 * zr * zi + ci
                                        zr = zr2 - zi2 + cr
                                        iter++

                                        zr2 = zr * zr; zi2 = zi * zi
                                        if (zr2 + zi2 > ESCAPE_R2) { escaped = true; break }
                                        val bdr = zr - refZr; val bdi = zi - refZi
                                        if (bdr * bdr + bdi * bdi < 1e-20) { iter = scaledMaxIter; break }
                                        if (++pCount >= period) {
                                            refZr = zr; refZi = zi; pCount = 0
                                            period = (period shl 1).coerceAtMost(512)
                                        }
                                    }
                                }
                                else -> { // BURNING_SHIP
                                    var zr2 = zr * zr
                                    var zi2 = zi * zi

                                    while (iter < scaledMaxIter) {
                                        if (ringX != null) {
                                            ringX[ringHead and RING_MASK] = zr
                                            ringY!![ringHead and RING_MASK] = zi
                                            ringHead++
                                        }
                                        if (needsTrap && iter >= TRAP_SKIP) {
                                            val dx = zr - trapCX; val dy = zi - trapCY
                                            val td = when (trapType) {
                                                TRAP_POINT -> dx * dx + dy * dy
                                                TRAP_LINE -> abs(dy)
                                                TRAP_CROSS -> min(abs(dx), abs(dy))
                                                TRAP_CIRCLE -> abs(sqrt(dx * dx + dy * dy) - trapRadius)
                                                TRAP_POLYGON -> {
                                                    var mx = -1e30
                                                    for (i in 0..2) {
                                                        val dot = abs(dx * HEX_NX[i] + dy * HEX_NY[i])
                                                        if (dot > mx) mx = dot
                                                    }
                                                    (mx - trapRadius).coerceAtLeast(0.0)
                                                }
                                                else -> dx * dx + dy * dy
                                            }
                                            if (td < minTD) minTD = td
                                        }

                                        // Burning Ship: (|Re|+i|Im|)² + c
                                        val azr = abs(zr); val azi = abs(zi)
                                        zi = 2.0 * azr * azi + ci
                                        zr = zr2 - zi2 + cr  // |re|² - |im|² = re² - im²
                                        iter++

                                        zr2 = zr * zr; zi2 = zi * zi
                                        if (zr2 + zi2 > ESCAPE_R2) { escaped = true; break }
                                        val bdr = zr - refZr; val bdi = zi - refZi
                                        if (bdr * bdr + bdi * bdi < 1e-20) { iter = scaledMaxIter; break }
                                        if (++pCount >= period) {
                                            refZr = zr; refZi = zi; pCount = 0
                                            period = (period shl 1).coerceAtMost(512)
                                        }
                                    }
                                }
                            }

                            // Convert squared min distance to actual distance for point trap
                            if (pointTrapSq && minTD < Double.MAX_VALUE) minTD = sqrt(minTD)

                            renderPixels[rowOff + px] = if (escaped) {
                                colorExterior(extMode, zr, zi, iter, invMaxIter,
                                    colorCycleOffset, colorCycleD, paletteLut, lutMax)
                            } else {
                                colorInterior(intMode, minTD, trapScale, zr, zi,
                                    ringX, ringY, ringHead, multOut, iter, scaledMaxIter,
                                    paletteLut, lutMax, colorCycleOffset)
                            }
                        }
                    }
                }
            }.also { it.start() }
        }

        // Join with cancellation propagation
        try {
            threads.forEach { it.join() }
        } catch (_: InterruptedException) {
            cancelled = true
            threads.forEach { it.interrupt() }
            Thread.currentThread().interrupt()
            return
        }
        if (Thread.currentThread().isInterrupted) {
            cancelled = true
            threads.forEach { it.interrupt() }
            return
        }

        if (renderW < w) {
            // Reusable upscale buffer
            val totalPx = w * h
            val ru = reuseUpscale
            val pixels = if (ru != null && ru.size >= totalPx) ru
                else IntArray(totalPx).also { reuseUpscale = it }
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

    /** Exterior color for escaped pixels — extracted to avoid code duplication. */
    private fun colorExterior(
        extMode: Int, zr: Double, zi: Double, iter: Int, invMaxIter: Double,
        cycleOffset: Int, cycleD: Double, lut: IntArray, lutMax: Int
    ): Int {
        return when (extMode) {
            EXT_BANDED -> {
                lut[((iter * 7 + cycleOffset) and 0xFF).coerceIn(0, lutMax)]
            }
            EXT_OFF -> {
                val si = iter + 1.0 - ln(ln(sqrt(zr * zr + zi * zi)) / LN_ESC_R) / LN2
                val rawT = ((si * invMaxIter + cycleD) % 1.0 + 1.0) % 1.0
                blendShade(lut[(rawT * lutMax).toInt().coerceIn(0, lutMax)], 0.15)
            }
            EXT_SUBTLE -> {
                val si = iter + 1.0 - ln(ln(sqrt(zr * zr + zi * zi)) / LN_ESC_R) / LN2
                val rawT = ((si * invMaxIter + cycleD) % 1.0 + 1.0) % 1.0
                blendShade(lut[(rawT * lutMax).toInt().coerceIn(0, lutMax)], 0.4)
            }
            else -> { // EXT_SMOOTH
                val si = iter + 1.0 - ln(ln(sqrt(zr * zr + zi * zi)) / LN_ESC_R) / LN2
                val rawT = ((si * invMaxIter * 3.0 + cycleD) % 1.0 + 1.0) % 1.0
                lut[(rawT * lutMax).toInt().coerceIn(0, lutMax)]
            }
        }
    }

    /** Fast interior color for cardioid/bulb skipped pixels. */
    private fun fastInteriorColor(
        intMode: Int, cr: Double, ci: Double,
        trapType: Int, trapCX: Double, trapCY: Double, trapRadius: Double,
        trapScale: Double, pointTrapSq: Boolean, shortIters: Int,
        lut: IntArray, lutMax: Int, cycleOffset: Int
    ): Int {
        // Only iterate if we need trap data or final value
        if (intMode == INT_PERIOD) {
            val crm = cr - 0.25; val ci2 = ci * ci
            val q = crm * crm + ci2
            val isCardioid = q * (q + crm) <= 0.25 * ci2
            val period = if (isCardioid) 1 else 2
            return lut[((period * 8 + cycleOffset) and 0xFF).coerceIn(0, lutMax)]
        }

        // For non-trap, non-final-value modes, use fallback color
        if (intMode != INT_TRAP && intMode != INT_FINAL_VALUE) {
            return lut[(cycleOffset and 0xFF).coerceIn(0, lutMax)]
        }

        var zr = 0.0; var zi = 0.0
        var minTD = Double.MAX_VALUE
        var zr2 = 0.0; var zi2 = 0.0

        for (i in 0 until shortIters) {
            zi = 2.0 * zr * zi + ci
            zr = zr2 - zi2 + cr
            zr2 = zr * zr; zi2 = zi * zi
            if (intMode == INT_TRAP && i >= TRAP_SKIP) {
                val dx = zr - trapCX; val dy = zi - trapCY
                val td = when (trapType) {
                    TRAP_POINT -> dx * dx + dy * dy
                    TRAP_LINE -> abs(dy)
                    TRAP_CROSS -> min(abs(dx), abs(dy))
                    TRAP_CIRCLE -> abs(sqrt(dx * dx + dy * dy) - trapRadius)
                    TRAP_POLYGON -> {
                        var mx = -1e30
                        for (k in 0..2) {
                            val dot = abs(dx * HEX_NX[k] + dy * HEX_NY[k])
                            if (dot > mx) mx = dot
                        }
                        (mx - trapRadius).coerceAtLeast(0.0)
                    }
                    else -> dx * dx + dy * dy
                }
                if (td < minTD) minTD = td
            }
        }

        return when (intMode) {
            INT_TRAP -> {
                if (pointTrapSq && minTD < Double.MAX_VALUE) minTD = sqrt(minTD)
                val normalized = sqrt(minTD * trapScale).coerceIn(0.0, 1.0)
                val t = ((normalized * lutMax).toInt() + cycleOffset) and 0xFF
                lut[t.coerceIn(0, lutMax)]
            }
            else -> { // INT_FINAL_VALUE
                val mag = sqrt(zr2 + zi2).coerceIn(0.0, 4.0) / 4.0
                val angle = ((atan2(zi, zr) / (2.0 * PI)) % 1.0 + 1.0) % 1.0
                val t = (((mag * 0.5 + angle * 0.5) * lutMax).toInt() + cycleOffset) and 0xFF
                lut[t.coerceIn(0, lutMax)]
            }
        }
    }

    private fun colorInterior(
        intMode: Int, minTD: Double, trapScale: Double,
        finalZr: Double, finalZi: Double,
        ringX: DoubleArray?, ringY: DoubleArray?, ringHead: Int,
        multOut: DoubleArray?, iter: Int, maxIter: Int,
        lut: IntArray, lutMax: Int, cycleOffset: Int
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
                val normalized = sqrt(minTD * trapScale).coerceIn(0.0, 1.0)
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
