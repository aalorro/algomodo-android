package com.artmondo.algomodo.generators.fractals

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import com.artmondo.algomodo.data.palettes.Palette
import com.artmondo.algomodo.generators.Generator
import com.artmondo.algomodo.generators.ParamGroup
import com.artmondo.algomodo.generators.Parameter
import com.artmondo.algomodo.generators.Quality
import kotlin.math.*

class OrbitTrapsGenerator : Generator {

    override val id = "fractal-orbit-traps"
    override val family = "fractals"
    override val styleName = "Orbit Traps"
    override val definition =
        "Orbit trap fractal — Mandelbrot iteration colored by proximity of orbit points to geometric trap shapes."
    override val algorithmNotes =
        "Runs standard Mandelbrot iteration but colors by minimum distance from orbit points to a geometric trap. " +
        "Includes stalk traps for organic branching, spiral traps, and line traps. Uses logarithmic distance mapping " +
        "for rich contrast. Animation uses dive, drift, pulse, or orbit camera modes."
    override val supportsVector = false
    override val supportsAnimation = true

    companion object {
        private const val TRAP_POINT = 0
        private const val TRAP_CIRCLE = 1
        private const val TRAP_CROSS = 2
        private const val TRAP_RING = 3
        private const val TRAP_SQUARE = 4
        private const val TRAP_STALK = 5
        private const val TRAP_LINE = 6
        private const val TRAP_SPIRAL = 7

        private val ZOOM_TARGETS = arrayOf(
            doubleArrayOf(-0.7435669, 0.1314023),
            doubleArrayOf(-0.1011, 0.9563),
            doubleArrayOf(-1.25, 0.0),
            doubleArrayOf(-0.16, 1.0405),
            doubleArrayOf(0.285, 0.01),
            doubleArrayOf(-0.745, 0.113),
        )

        private const val BAILOUT_SQ = 64.0
        private const val TWO_PI = 2.0 * Math.PI

        // Color mode codes
        private const val CM_DISTANCE = 0
        private const val CM_ANGLE = 1
        private const val CM_ITERATION = 2
        private const val CM_COMPOSITE = 3
        private const val CM_LAYERED = 4
    }

    override val parameterSchema: List<Parameter> = listOf(
        Parameter.SelectParam("Trap Shape", "trapShape", ParamGroup.COMPOSITION,
            "Geometric shape used as the orbit trap",
            listOf("point", "circle", "cross", "ring", "square", "stalk", "line", "spiral"), "stalk"),
        Parameter.NumberParam("Trap Size", "trapSize", ParamGroup.GEOMETRY,
            "Size of the trap shape", 0.05f, 2f, 0.05f, 0.5f),
        Parameter.NumberParam("Center X", "centerX", ParamGroup.COMPOSITION,
            "Real-axis center of the view", -2f, 2f, 0.05f, -0.5f),
        Parameter.NumberParam("Center Y", "centerY", ParamGroup.COMPOSITION,
            "Imaginary-axis center of the view", -2f, 2f, 0.05f, 0f),
        Parameter.NumberParam("Zoom", "zoom", ParamGroup.COMPOSITION,
            "Zoom level", 0.5f, 20f, 0.1f, 1f),
        Parameter.NumberParam("Max Iterations", "maxIterations", ParamGroup.COMPOSITION,
            "Maximum escape-time iterations", 32f, 256f, 8f, 80f),
        Parameter.SelectParam("Color Mode", "colorMode", ParamGroup.COLOR,
            "Coloring method",
            listOf("distance", "angle", "iteration", "composite", "layered"), "layered"),
        Parameter.SelectParam("Movement", "movement", ParamGroup.FLOW_MOTION,
            "Animation camera mode",
            listOf("dive", "drift", "pulse", "orbit"), "dive"),
        Parameter.NumberParam("Speed", "speed", ParamGroup.FLOW_MOTION,
            "Animation speed", 0.1f, 3.0f, 0.1f, 0.5f),
        Parameter.NumberParam("Duration", "duration", ParamGroup.FLOW_MOTION,
            "Zoom cycle length in seconds", 1f, 30f, 1f, 10f),
        Parameter.NumberParam("Zoom Depth", "zoomDepth", ParamGroup.FLOW_MOTION,
            "How deep the zoom travels before looping", 10f, 10000f, 10f, 60f),
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "trapShape" to "stalk",
        "trapSize" to 0.5f,
        "centerX" to -0.5f,
        "centerY" to 0f,
        "zoom" to 1f,
        "maxIterations" to 80f,
        "colorMode" to "layered",
        "movement" to "dive",
        "speed" to 0.5f,
        "duration" to 10f,
        "zoomDepth" to 60f,
    )

    private val filterPaint = Paint(Paint.FILTER_BITMAP_FLAG)

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

        val trapShape = (params["trapShape"] as? String) ?: "stalk"
        var trapSize = (params["trapSize"] as? Number)?.toDouble() ?: 0.5
        val baseCx = (params["centerX"] as? Number)?.toDouble() ?: -0.5
        val baseCy = (params["centerY"] as? Number)?.toDouble() ?: 0.0
        val zoomParam = (params["zoom"] as? Number)?.toDouble() ?: 1.0
        val baseMaxIter = (params["maxIterations"] as? Number)?.toInt() ?: 80
        val colorMode = (params["colorMode"] as? String) ?: "layered"
        val movement = (params["movement"] as? String) ?: "dive"
        val speed = (params["speed"] as? Number)?.toDouble() ?: 0.5
        val duration = (params["duration"] as? Number)?.toDouble() ?: 10.0
        val zoomDepth = (params["zoomDepth"] as? Number)?.toDouble() ?: 60.0

        val isAnim = time > 0f
        val t = time.toDouble()

        val maxIter = when (quality) {
            Quality.DRAFT -> (baseMaxIter / 2).coerceAtLeast(24)
            Quality.BALANCED -> if (isAnim) baseMaxIter.coerceAtMost(48) else baseMaxIter
            Quality.ULTRA -> if (isAnim) baseMaxIter.coerceAtMost(96) else (baseMaxIter * 2).coerceAtMost(256)
        }

        val trapCode = when (trapShape) {
            "point" -> TRAP_POINT; "circle" -> TRAP_CIRCLE; "cross" -> TRAP_CROSS
            "ring" -> TRAP_RING; "square" -> TRAP_SQUARE; "stalk" -> TRAP_STALK
            "line" -> TRAP_LINE; "spiral" -> TRAP_SPIRAL; else -> TRAP_STALK
        }
        val colorModeCode = when (colorMode) {
            "distance" -> CM_DISTANCE; "angle" -> CM_ANGLE; "iteration" -> CM_ITERATION
            "composite" -> CM_COMPOSITE; "layered" -> CM_LAYERED; else -> CM_LAYERED
        }
        val doLayered = colorModeCode == CM_LAYERED
        val needsAngle = colorModeCode == CM_ANGLE || colorModeCode == CM_COMPOSITE || doLayered

        // --- Animation: compute view center, zoom, trap rotation/size modulation ---
        val target = ZOOM_TARGETS[abs(seed) % ZOOM_TARGETS.size]
        var cx: Double; var cy: Double; var viewZoom: Double
        var trapRotation = 0.0

        if (isAnim) {
            when (movement) {
                "dive" -> {
                    trapSize += sin(t * speed * 0.5) * 0.2 + sin(t * speed * 0.17) * 0.08
                    val cycleTime = t % duration
                    viewZoom = zoomParam * zoomDepth.pow(cycleTime / duration)
                    val lerp = 1.0 - 1.0 / viewZoom
                    cx = baseCx + (target[0] - baseCx) * lerp
                    cy = baseCy + (target[1] - baseCy) * lerp
                    trapRotation = t * speed * 0.3
                }
                "drift" -> {
                    trapSize += sin(t * speed * 0.3) * 0.12
                    viewZoom = zoomParam
                    val range = 1.5 / viewZoom
                    cx = baseCx + sin(t * speed * 0.13) * range
                    cy = baseCy + sin(t * speed * 0.09) * range * 0.7
                    trapRotation = t * speed * 0.15
                }
                "pulse" -> {
                    trapSize += sin(t * speed * 0.4) * 0.4 + sin(t * speed * 0.23) * 0.15
                    viewZoom = zoomParam
                    cx = baseCx; cy = baseCy
                    trapRotation = sin(t * speed * 0.25) * 1.5
                }
                "orbit" -> {
                    trapSize += sin(t * speed * 0.35) * 0.1
                    viewZoom = zoomParam
                    val orbitRadius = 0.8 / viewZoom
                    cx = baseCx + cos(t * speed * 0.2) * orbitRadius
                    cy = baseCy + sin(t * speed * 0.2) * orbitRadius
                    trapRotation = t * speed * 0.3
                }
                else -> { viewZoom = zoomParam; cx = baseCx; cy = baseCy }
            }
        } else {
            cx = baseCx; cy = baseCy; viewZoom = zoomParam
        }

        val cosRot = cos(trapRotation)
        val sinRot = sin(trapRotation)
        val hasRotation = trapRotation != 0.0

        val minDim = min(w, h).toDouble()
        val pixelSize = 3.0 / (viewZoom * minDim)
        val halfW = w * 0.5
        val halfH = h * 0.5
        val invMaxIter = 1.0 / maxIter
        val invTrapNorm = 1.0 / (trapSize + 0.5)

        // Palette LUT
        val lutMax = 255
        val paletteLut = IntArray(256) { palette.lerpColor(it.toFloat() / lutMax) }

        // Adaptive resolution during animation
        val renderW = if (isAnim) (w * 3 / 5).coerceAtLeast(w / 2) else w
        val renderH = if (isAnim) (h * 3 / 5).coerceAtLeast(h / 2) else h
        val scaleX = w.toDouble() / renderW
        val scaleY = h.toDouble() / renderH

        val renderPixels = IntArray(renderW * renderH)

        val cores = Runtime.getRuntime().availableProcessors().coerceIn(2, 8)
        val threads = Array(cores) { threadIdx ->
            Thread {
                val y0 = threadIdx * renderH / cores
                val y1 = (threadIdx + 1) * renderH / cores

                for (py in y0 until y1) {
                    val cImag0 = cy + (py * scaleY - halfH) * pixelSize
                    val rowOff = py * renderW

                    for (px in 0 until renderW) {
                        val cReal0 = cx + (px * scaleX - halfW) * pixelSize

                        var zr = 0.0; var zi = 0.0
                        var iter = 0
                        var minDist = 1e30
                        var minTZr = 0.0; var minTZi = 0.0
                        var minIter = 0
                        var sumInvDist = 0.0

                        // Cardioid / period-2 bulb check — skips ~25% of interior
                        val q = (cReal0 - 0.25) * (cReal0 - 0.25) + cImag0 * cImag0
                        if (q * (q + (cReal0 - 0.25)) < 0.25 * cImag0 * cImag0 ||
                            (cReal0 + 1.0) * (cReal0 + 1.0) + cImag0 * cImag0 < 0.0625) {
                            iter = maxIter
                        }

                        // Brent's periodicity detection state
                        var pzr = 0.0; var pzi = 0.0
                        var period = 8; var pCheck = 0

                        while (zr * zr + zi * zi <= BAILOUT_SQ && iter < maxIter) {
                            // Iterate z = z^2 + c FIRST, then measure trap (skip z0=0)
                            val newZr = zr * zr - zi * zi + cReal0
                            zi = 2.0 * zr * zi + cImag0
                            zr = newZr
                            iter++

                            // Apply trap rotation
                            val tZr: Double; val tZi: Double
                            if (hasRotation) {
                                tZr = zr * cosRot - zi * sinRot
                                tZi = zr * sinRot + zi * cosRot
                            } else {
                                tZr = zr; tZi = zi
                            }

                            // Inline trap distance
                            val dist = when (trapCode) {
                                TRAP_POINT -> sqrt((tZr - trapSize) * (tZr - trapSize) + tZi * tZi)
                                TRAP_CIRCLE -> abs(sqrt(tZr * tZr + tZi * tZi) - trapSize)
                                TRAP_CROSS -> min(abs(tZr), abs(tZi))
                                TRAP_RING -> {
                                    val r = sqrt(tZr * tZr + tZi * tZi)
                                    val ringD = r % trapSize
                                    if (ringD < trapSize * 0.5) ringD else trapSize - ringD
                                }
                                TRAP_SQUARE -> abs(max(abs(tZr), abs(tZi)) - trapSize)
                                TRAP_STALK -> if (tZr > 0.0) abs(tZi) else 1e8
                                TRAP_LINE -> abs(tZr - tZi) * 0.7071
                                TRAP_SPIRAL -> {
                                    val r = sqrt(tZr * tZr + tZi * tZi)
                                    val theta = atan2(tZi, tZr)
                                    val ad = abs((r - trapSize * (theta + Math.PI) / TWO_PI) % trapSize)
                                    if (ad < trapSize - ad) ad else trapSize - ad
                                }
                                else -> sqrt(tZr * tZr + tZi * tZi)
                            }

                            if (doLayered) sumInvDist += 1.0 / (1.0 + dist * dist * 10.0)

                            if (dist < minDist) {
                                minDist = dist
                                if (needsAngle) { minTZr = tZr; minTZi = tZi }
                                minIter = iter
                            }

                            // Periodicity check
                            if (abs(zr - pzr) < 1e-10 && abs(zi - pzi) < 1e-10) {
                                iter = maxIter; break
                            }
                            if (++pCheck >= period) {
                                pzr = zr; pzi = zi; pCheck = 0
                                if (period < 512) period = period shl 1
                            }
                        }

                        // --- Color computation ---
                        val rawDist = minDist * invTrapNorm
                        val distT = 1.0 - exp(-rawDist * 3.0)

                        var cr: Double; var cg: Double; var cb: Double

                        if (iter >= maxIter) {
                            // Interior
                            if (doLayered && sumInvDist > 0.0) {
                                val intT = (sumInvDist / (maxIter * 0.1)).coerceAtMost(1.0)
                                val baseC = paletteLut[((intT * 0.5) * lutMax).toInt().coerceIn(0, lutMax)]
                                cr = (baseC shr 16 and 0xFF) * 0.15
                                cg = (baseC shr 8 and 0xFF) * 0.15
                                cb = (baseC and 0xFF) * 0.15
                            } else {
                                val baseC = paletteLut[(distT * lutMax).toInt().coerceIn(0, lutMax)]
                                cr = (baseC shr 16 and 0xFF) * 0.2
                                cg = (baseC shr 8 and 0xFF) * 0.2
                                cb = (baseC and 0xFF) * 0.2
                            }
                        } else {
                            when (colorModeCode) {
                                CM_DISTANCE -> {
                                    val baseC = paletteLut[(distT * lutMax).toInt().coerceIn(0, lutMax)]
                                    cr = (baseC shr 16 and 0xFF).toDouble()
                                    cg = (baseC shr 8 and 0xFF).toDouble()
                                    cb = (baseC and 0xFF).toDouble()
                                    if (minDist < 0.03) {
                                        val glow = (0.03 - minDist) * 33.33
                                        val gs = glow * glow * 0.7
                                        cr = min(255.0, cr + (255.0 - cr) * gs)
                                        cg = min(255.0, cg + (255.0 - cg) * gs)
                                        cb = min(255.0, cb + (255.0 - cb) * gs)
                                    }
                                }
                                CM_ANGLE -> {
                                    val angleT = ((atan2(minTZi, minTZr) / Math.PI) + 1.0) * 0.5 % 1.0
                                    val baseC = paletteLut[(angleT * lutMax).toInt().coerceIn(0, lutMax)]
                                    cr = (baseC shr 16 and 0xFF).toDouble()
                                    cg = (baseC shr 8 and 0xFF).toDouble()
                                    cb = (baseC and 0xFF).toDouble()
                                    val edge = 0.85 + 0.15 * (1.0 - distT)
                                    cr *= edge; cg *= edge; cb *= edge
                                    if (minDist < 0.03) {
                                        val glow = (0.03 - minDist) * 33.33
                                        val gs = glow * glow * 0.5
                                        cr = min(255.0, cr + (255.0 - cr) * gs)
                                        cg = min(255.0, cg + (255.0 - cg) * gs)
                                        cb = min(255.0, cb + (255.0 - cb) * gs)
                                    }
                                }
                                CM_ITERATION -> {
                                    val iterT = minIter * invMaxIter
                                    val baseC = paletteLut[(iterT * lutMax).toInt().coerceIn(0, lutMax)]
                                    cr = (baseC shr 16 and 0xFF).toDouble()
                                    cg = (baseC shr 8 and 0xFF).toDouble()
                                    cb = (baseC and 0xFF).toDouble()
                                    val edge = 0.8 + 0.2 * (1.0 - distT)
                                    cr *= edge; cg *= edge; cb *= edge
                                    if (minDist < 0.03) {
                                        val glow = (0.03 - minDist) * 33.33
                                        val gs = glow * glow * 0.5
                                        cr = min(255.0, cr + (255.0 - cr) * gs)
                                        cg = min(255.0, cg + (255.0 - cg) * gs)
                                        cb = min(255.0, cb + (255.0 - cb) * gs)
                                    }
                                }
                                CM_LAYERED -> {
                                    val angleT = ((atan2(minTZi, minTZr) / Math.PI) + 1.0) * 0.5 % 1.0
                                    val layerT = if (iter > 0) (sumInvDist / (iter * 0.15)).coerceAtMost(1.0) else 0.0
                                    val hueT = (angleT * 0.5 + layerT * 0.5) % 1.0
                                    val baseC = paletteLut[(hueT * lutMax).toInt().coerceIn(0, lutMax)]
                                    cr = (baseC shr 16 and 0xFF).toDouble()
                                    cg = (baseC shr 8 and 0xFF).toDouble()
                                    cb = (baseC and 0xFF).toDouble()
                                    val layerGlow = (layerT * 1.5).coerceAtMost(1.0)
                                    val edgeDim = 0.7 + 0.3 * (1.0 - distT)
                                    val finalBright = (layerGlow * 0.7 + edgeDim * 0.3) * 1.3
                                    cr = min(255.0, cr * finalBright)
                                    cg = min(255.0, cg * finalBright)
                                    cb = min(255.0, cb * finalBright)
                                    if (minDist < 0.03) {
                                        val glow = (0.03 - minDist) * 33.33
                                        val gs = glow * glow * 0.7
                                        cr = min(255.0, cr + (255.0 - cr) * gs)
                                        cg = min(255.0, cg + (255.0 - cg) * gs)
                                        cb = min(255.0, cb + (255.0 - cb) * gs)
                                    }
                                }
                                else -> {
                                    // Composite — angle + iteration blend
                                    val angleT = ((atan2(minTZi, minTZr) / Math.PI) + 1.0) * 0.5 % 1.0
                                    val iterT = minIter * invMaxIter
                                    val blendT = (angleT * 0.6 + iterT * 0.4) % 1.0
                                    val baseC = paletteLut[(blendT * lutMax).toInt().coerceIn(0, lutMax)]
                                    cr = (baseC shr 16 and 0xFF).toDouble()
                                    cg = (baseC shr 8 and 0xFF).toDouble()
                                    cb = (baseC and 0xFF).toDouble()
                                    val edge = 0.75 + 0.25 * (1.0 - distT)
                                    cr *= edge; cg *= edge; cb *= edge
                                    if (minDist < 0.03) {
                                        val glow = (0.03 - minDist) * 33.33
                                        val gs = glow * glow * 0.5
                                        cr = min(255.0, cr + (255.0 - cr) * gs)
                                        cg = min(255.0, cg + (255.0 - cg) * gs)
                                        cb = min(255.0, cb + (255.0 - cb) * gs)
                                    }
                                }
                            }
                        }

                        renderPixels[rowOff + px] =
                            (0xFF shl 24) or
                            (cr.toInt().coerceIn(0, 255) shl 16) or
                            (cg.toInt().coerceIn(0, 255) shl 8) or
                            cb.toInt().coerceIn(0, 255)
                    }
                }
            }.also { it.start() }
        }
        threads.forEach { it.join() }

        // Bilinear upscale for animation, direct copy for static
        if (renderW < w) {
            val smallBmp = Bitmap.createBitmap(renderW, renderH, Bitmap.Config.ARGB_8888)
            smallBmp.setPixels(renderPixels, 0, renderW, 0, 0, renderW, renderH)
            canvas.drawBitmap(smallBmp, null, Rect(0, 0, w, h), filterPaint)
            smallBmp.recycle()
        } else {
            bitmap.setPixels(renderPixels, 0, w, 0, 0, w, h)
            canvas.drawBitmap(bitmap, 0f, 0f, null)
        }
    }

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float {
        val maxIter = (params["maxIterations"] as? Number)?.toInt() ?: 80
        return (maxIter / 400f).coerceIn(0.2f, 1f)
    }
}
